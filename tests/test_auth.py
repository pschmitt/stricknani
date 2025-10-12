"""Test authentication utilities."""

from collections.abc import AsyncGenerator

import pytest
from sqlalchemy.ext.asyncio import (
    AsyncSession,
    async_sessionmaker,
    create_async_engine,
)

from stricknani.models import Base
from stricknani.utils.auth import (
    authenticate_user,
    create_user,
    get_password_hash,
    get_user_by_email,
    verify_password,
)


@pytest.fixture
async def db_session() -> AsyncGenerator[AsyncSession]:
    """Create a test database session."""
    engine = create_async_engine("sqlite+aiosqlite:///:memory:")
    async with engine.begin() as conn:
        await conn.run_sync(Base.metadata.create_all)

    async_session = async_sessionmaker(
        engine, class_=AsyncSession, expire_on_commit=False
    )
    async with async_session() as session:
        yield session


async def test_password_hashing() -> None:
    """Test password hashing and verification."""
    password = "test_password_123"
    hashed = get_password_hash(password)

    assert hashed != password
    assert verify_password(password, hashed)
    assert not verify_password("wrong_password", hashed)


async def test_create_user(db_session: AsyncSession) -> None:
    """Test user creation."""
    email = "test@example.com"
    password = "test_password"

    user = await create_user(db_session, email, password)

    assert user.email == email
    assert user.hashed_password != password
    assert verify_password(password, user.hashed_password)


async def test_get_user_by_email(db_session: AsyncSession) -> None:
    """Test getting user by email."""
    email = "test@example.com"
    password = "test_password"

    await create_user(db_session, email, password)
    user = await get_user_by_email(db_session, email)

    assert user is not None
    assert user.email == email


async def test_authenticate_user(db_session: AsyncSession) -> None:
    """Test user authentication."""
    email = "test@example.com"
    password = "test_password"

    await create_user(db_session, email, password)

    # Test successful authentication
    user = await authenticate_user(db_session, email, password)
    assert user is not None
    assert user.email == email

    # Test failed authentication with wrong password
    user = await authenticate_user(db_session, email, "wrong_password")
    assert user is None

    # Test failed authentication with non-existent user
    user = await authenticate_user(db_session, "nonexistent@example.com", password)
    assert user is None
