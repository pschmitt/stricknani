"""Authentication utilities."""

import hashlib
import secrets
from datetime import UTC, datetime, timedelta

import bcrypt
from jose import JWTError, jwt
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from stricknani.config import config
from stricknani.database import AsyncSessionLocal
from stricknani.models import ApiToken, User

# Prefix on generated personal access tokens, purely for readability (so a
# token is recognizable at a glance, e.g. in logs or a paste) - it carries no
# security meaning and is included in the hashed value like the rest of the
# token.
API_TOKEN_PREFIX = "sna_"

# Only bump `last_used_at` if it's stale by more than this, so a client
# polling the API frequently doesn't turn every request into a write.
API_TOKEN_LAST_USED_RESOLUTION = timedelta(seconds=60)

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


def hash_api_token(raw_token: str) -> str:
    """Hash a raw API token for storage/lookup.

    A plain SHA-256 digest (not bcrypt) is deliberate here: the token itself
    is a high-entropy random secret (unlike a user-chosen password), so it
    doesn't need a slow, salted KDF - and a fast hash keeps every
    Bearer-authenticated request cheap.
    """
    return hashlib.sha256(raw_token.encode("utf-8")).hexdigest()


def generate_api_token() -> tuple[str, str]:
    """Generate a new personal access token.

    Returns `(raw_token, token_hash)`. Only `token_hash` should ever be
    persisted; `raw_token` must be shown to the user once and is not
    recoverable afterwards.
    """
    raw_token = API_TOKEN_PREFIX + secrets.token_urlsafe(32)
    return raw_token, hash_api_token(raw_token)


async def get_user_from_api_token(db: AsyncSession, raw_token: str) -> User | None:
    """Resolve a raw `Authorization: Bearer <token>` value to its owning user.

    Also opportunistically bumps `last_used_at` on the token and rejects
    expired or inactive-user tokens.
    """
    token_hash = hash_api_token(raw_token)
    result = await db.execute(select(ApiToken).where(ApiToken.token_hash == token_hash))
    api_token = result.scalar_one_or_none()
    if not api_token:
        return None

    now = datetime.now(UTC)

    expires_at = api_token.expires_at
    if expires_at is not None:
        if expires_at.tzinfo is None:  # Handle naive datetime from SQLite
            expires_at = expires_at.replace(tzinfo=UTC)
        if expires_at < now:
            return None

    user = await db.get(User, api_token.user_id)
    if not user or not user.is_active:
        return None

    last_used_at = api_token.last_used_at
    if last_used_at is not None and last_used_at.tzinfo is None:
        last_used_at = last_used_at.replace(tzinfo=UTC)
    if last_used_at is None or (now - last_used_at) > API_TOKEN_LAST_USED_RESOLUTION:
        api_token.last_used_at = now
        await db.commit()

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
