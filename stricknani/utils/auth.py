"""Authentication utilities."""

from datetime import UTC, datetime, timedelta

import bcrypt
from jose import JWTError, jwt
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from stricknani.config import config
from stricknani.database import AsyncSessionLocal
from stricknani.models import User

# Minimum password policy (T69): enforced at signup and password-change
# time. Kept intentionally simple (length + a small common-password
# blocklist) rather than pulling in an external strength-scoring dependency.
MIN_PASSWORD_LENGTH = 8

_COMMON_WEAK_PASSWORDS = frozenset(
    {
        "password",
        "password1",
        "password123",
        "12345678",
        "123456789",
        "1234567890",
        "qwertyui",
        "qwerty123",
        "letmein1",
        "iloveyou1",
        "admin1234",
        "welcome1",
        "welcome123",
        "abcd1234",
        "11111111",
        "00000000",
        "changeme1",
        "trustno1",
        "sunshine1",
        "monkey123",
    }
)


class PasswordPolicyError(ValueError):
    """Raised when a candidate password fails the minimum policy.

    `reason` is a stable machine-readable code ("too_short" or "common") so
    callers can render a localized, user-facing message.
    """

    def __init__(self, reason: str) -> None:
        self.reason = reason
        super().__init__(reason)


def validate_password_policy(password: str) -> None:
    """Enforce the minimum password policy.

    Raises:
        PasswordPolicyError: if the password is too short or a common/trivially
            guessable password.
    """
    if len(password) < MIN_PASSWORD_LENGTH:
        raise PasswordPolicyError("too_short")
    if password.strip().lower() in _COMMON_WEAK_PASSWORDS:
        raise PasswordPolicyError("common")


def verify_password(plain_password: str, hashed_password: str) -> bool:
    """Verify a password against a hash."""
    return bcrypt.checkpw(
        plain_password.encode("utf-8"), hashed_password.encode("utf-8")
    )


def get_password_hash(password: str) -> str:
    """Hash a password."""
    hashed = bcrypt.hashpw(password.encode("utf-8"), bcrypt.gensalt())
    return hashed.decode("utf-8")


def create_access_token(
    data: dict[str, str], expires_delta: timedelta | None = None
) -> str:
    """Create JWT access token."""
    to_encode: dict[str, object] = {**data}
    if expires_delta:
        expire = datetime.now(UTC) + expires_delta
    else:
        expire = datetime.now(UTC) + timedelta(
            minutes=config.ACCESS_TOKEN_EXPIRE_MINUTES
        )
    to_encode["exp"] = expire
    encoded_jwt: str = jwt.encode(
        to_encode, config.SECRET_KEY, algorithm=config.ALGORITHM
    )
    return encoded_jwt


def decode_access_token(token: str) -> tuple[str, int] | None:
    """Decode a JWT access token and return `(email, token_version)`.

    `token_version` (claim `"ver"`) supports revocable sessions (T69):
    `get_current_user` compares it against the user's current
    `token_version` and rejects the session on mismatch (e.g. after logout
    or a password change), even though the JWT signature itself is still
    valid. Tokens minted before this claim existed default to version 0,
    matching newly created users' default `token_version`.
    """
    try:
        payload = jwt.decode(token, config.SECRET_KEY, algorithms=[config.ALGORITHM])
    except JWTError:
        return None

    email = payload.get("sub")
    if not email:
        return None

    raw_version = payload.get("ver", 0)
    try:
        version = int(raw_version)
    except (TypeError, ValueError):
        version = 0

    return email, version


async def get_user_by_email(db: AsyncSession, email: str) -> User | None:
    """Get user by email."""
    result = await db.execute(select(User).where(User.email == email))
    return result.scalar_one_or_none()


async def authenticate_user(db: AsyncSession, email: str, password: str) -> User | None:
    """Authenticate user."""
    user = await get_user_by_email(db, email)
    if not user:
        return None
    if not user.is_active:
        return None
    if not verify_password(password, user.hashed_password):
        return None
    return user


async def create_user(
    db: AsyncSession, email: str, password: str, is_admin: bool = False
) -> User:
    """Create a new user."""
    hashed_password = get_password_hash(password)
    user = User(email=email, hashed_password=hashed_password, is_admin=is_admin)
    db.add(user)
    await db.commit()
    await db.refresh(user)
    return user


async def ensure_initial_admin() -> None:
    """Create an initial admin user if configured."""
    email = config.INITIAL_ADMIN_EMAIL
    password = config.INITIAL_ADMIN_PASSWORD
    if not email or not password:
        return

    async with AsyncSessionLocal() as session:
        existing = await get_user_by_email(session, email)
        if existing:
            if not existing.is_active:
                existing.is_active = True
            if not existing.is_admin:
                existing.is_admin = True
            await session.commit()
            return

        # If any user exists, do nothing to avoid clobbering existing installs
        existing_any = await session.execute(select(User.id).limit(1))
        if existing_any.scalar_one_or_none() is not None:
            return

        hashed_password = get_password_hash(password)
        user = User(
            email=email,
            hashed_password=hashed_password,
            is_active=True,
            is_admin=True,
        )
        session.add(user)
        await session.commit()
        await session.refresh(user)
