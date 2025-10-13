"""Test authentication utilities."""

from collections.abc import AsyncGenerator

import pytest
from httpx import ASGITransport, AsyncClient
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker, create_async_engine

from stricknani.config import config
from stricknani.database import get_db
from stricknani.main import app
from stricknani.models import Base
from stricknani.routes import auth
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


@pytest.mark.asyncio
async def test_login_cookie_not_secure_by_default(monkeypatch) -> None:
    """The session cookie should omit the Secure flag when disabled in config."""

    class DummyUser:
        def __init__(self) -> None:
            self.email = "tester@example.com"

    async def fake_auth(_db, _email, _password):
        return DummyUser()

    def fake_token(data, expires_delta=None):  # type: ignore[unused-argument]
        return "dummy-token"

    async def override_db():
        yield None

    monkeypatch.setattr(config, "SESSION_COOKIE_SECURE", False)
    monkeypatch.setattr(config, "COOKIE_SAMESITE", "lax")
    monkeypatch.setattr(auth, "authenticate_user", fake_auth)
    monkeypatch.setattr(auth, "create_access_token", fake_token)

    app.dependency_overrides[get_db] = override_db

    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        response = await client.post(
            "/auth/login",
            data={"email": "tester@example.com", "password": "password"},
            follow_redirects=False,
        )

    app.dependency_overrides.pop(get_db, None)

    assert response.status_code == 303
    cookie_header = response.headers.get("set-cookie", "")
    assert "session_token=" in cookie_header
    assert "Secure" not in cookie_header
