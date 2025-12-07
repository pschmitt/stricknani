"""Authentication utilities."""

from datetime import datetime, timedelta

import bcrypt
from jose import JWTError, jwt
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from stricknani.config import config
from stricknani.database import AsyncSessionLocal
from stricknani.models import User


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
        expire = datetime.utcnow() + expires_delta
    else:
        expire = datetime.utcnow() + timedelta(
            minutes=config.ACCESS_TOKEN_EXPIRE_MINUTES
        )
    to_encode["exp"] = expire
    encoded_jwt: str = jwt.encode(
        to_encode, config.SECRET_KEY, algorithm=config.ALGORITHM
    )
    return encoded_jwt


def decode_access_token(token: str) -> str | None:
    """Decode JWT access token and return user email."""
    try:
        payload = jwt.decode(token, config.SECRET_KEY, algorithms=[config.ALGORITHM])
        email: str | None = payload.get("sub")
        return email
    except JWTError:
        return None


async def get_user_by_email(db: AsyncSession, email: str) -> User | None:
    """Get user by email."""
    result = await db.execute(select(User).where(User.email == email))
    return result.scalar_one_or_none()


async def authenticate_user(
    db: AsyncSession, email: str, password: str
) -> User | None:
    """Authenticate user."""
    user = await get_user_by_email(db, email)
    if not user:
        return None
    if not verify_password(password, user.hashed_password):
        return None
    return user


async def create_user(db: AsyncSession, email: str, password: str) -> User:
    """Create a new user."""
    hashed_password = get_password_hash(password)
    user = User(email=email, hashed_password=hashed_password)
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
        # If any user exists, do nothing to avoid clobbering existing installs
        existing_any = await session.execute(select(User.id).limit(1))
        if existing_any.scalar_one_or_none() is not None:
            return

        existing = await get_user_by_email(session, email)
        if existing:
            if not existing.is_active:
                existing.is_active = True
                await session.commit()
            return

        hashed_password = get_password_hash(password)
        user = User(email=email, hashed_password=hashed_password, is_active=True)
        session.add(user)
        await session.commit()
        await session.refresh(user)
