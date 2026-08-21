"""Test authentication utilities."""

from collections.abc import AsyncGenerator
from typing import Any

import pytest
from httpx import ASGITransport, AsyncClient
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker, create_async_engine

from stricknani.config import config
from stricknani.database import get_db
from stricknani.main import app
from stricknani.models import Base
from stricknani.routes import auth
from stricknani.utils.auth import (
    PasswordPolicyError,
    authenticate_user,
    create_access_token,
    create_user,
    get_password_hash,
    get_user_by_email,
    validate_password_policy,
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


@pytest.fixture
async def http_client() -> AsyncGenerator[
    tuple[AsyncClient, async_sessionmaker[AsyncSession]]
]:
    """An end-to-end HTTP client backed by a real (in-memory) database.

    Unlike the `test_client` fixture in `tests/conftest.py`, this does *not*
    override `get_current_user`/`require_auth`, so the real auth dependency
    chain (JWT decode + token_version check) runs for every request. That's
    needed to exercise login/signup/logout rate limiting and revocable
    sessions (T69) end to end.
    """
    engine = create_async_engine("sqlite+aiosqlite:///:memory:?cache=shared")
    async with engine.begin() as conn:
        await conn.run_sync(Base.metadata.create_all)

    session_factory = async_sessionmaker(engine, expire_on_commit=False)

    async def override_get_db() -> AsyncGenerator[AsyncSession]:
        async with session_factory() as session:
            yield session

    app.dependency_overrides[get_db] = override_get_db

    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        yield client, session_factory

    app.dependency_overrides.pop(get_db, None)
    await engine.dispose()


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


async def test_authenticate_user_inactive(db_session: AsyncSession) -> None:
    """Inactive users should not authenticate."""
    email = "inactive@example.com"
    password = "test_password"

    user = await create_user(db_session, email, password)
    user.is_active = False
    await db_session.commit()

    authenticated = await authenticate_user(db_session, email, password)
    assert authenticated is None


async def test_get_current_user_inactive_returns_none(db_session: AsyncSession) -> None:
    """Inactive users with valid tokens should be treated as unauthenticated."""
    email = "inactive-current@example.com"
    password = "test_password"

    user = await create_user(db_session, email, password)
    user.is_active = False
    await db_session.commit()

    token = create_access_token({"sub": email})
    resolved = await auth.get_current_user(token, db_session)
    assert resolved is None


@pytest.mark.asyncio
async def test_login_cookie_not_secure_by_default(monkeypatch: Any) -> None:
    """The session cookie should omit the Secure flag when disabled in config."""

    class DummyUser:
        def __init__(self) -> None:
            self.email = "tester@example.com"
            self.token_version = 0

    async def fake_auth(_db: Any, _email: str, _password: str) -> DummyUser:
        return DummyUser()

    def fake_token(data: dict[str, Any], expires_delta: Any = None) -> str:
        return "dummy-token"

    async def override_db() -> AsyncGenerator[None]:
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


# --- T69: password policy -------------------------------------------------


def test_validate_password_policy_rejects_short_password() -> None:
    """Passwords shorter than the minimum length must be rejected."""
    with pytest.raises(PasswordPolicyError) as excinfo:
        validate_password_policy("short1")
    assert excinfo.value.reason == "too_short"


def test_validate_password_policy_rejects_common_password() -> None:
    """Common/trivially guessable passwords must be rejected even if long enough."""
    with pytest.raises(PasswordPolicyError) as excinfo:
        validate_password_policy("Password1")
    assert excinfo.value.reason == "common"


def test_validate_password_policy_accepts_strong_password() -> None:
    """A sufficiently long, non-common password should pass."""
    validate_password_policy("correct-horse-battery-staple")


async def test_signup_rejects_short_password(
    http_client: tuple[AsyncClient, async_sessionmaker[AsyncSession]],
) -> None:
    client, session_factory = http_client
    original_enabled = config.FEATURE_SIGNUP_ENABLED
    config.FEATURE_SIGNUP_ENABLED = True
    try:
        response = await client.post(
            "/auth/signup",
            data={"email": "weakling@example.com", "password": "short1"},
        )
    finally:
        config.FEATURE_SIGNUP_ENABLED = original_enabled

    assert response.status_code == 400

    async with session_factory() as session:
        user = await get_user_by_email(session, "weakling@example.com")
    assert user is None


async def test_signup_creates_user_with_strong_password(
    http_client: tuple[AsyncClient, async_sessionmaker[AsyncSession]],
) -> None:
    client, session_factory = http_client
    original_enabled = config.FEATURE_SIGNUP_ENABLED
    config.FEATURE_SIGNUP_ENABLED = True
    try:
        response = await client.post(
            "/auth/signup",
            data={
                "email": "strongling@example.com",
                "password": "correct-horse-battery-staple",
            },
            follow_redirects=False,
        )
    finally:
        config.FEATURE_SIGNUP_ENABLED = original_enabled

    assert response.status_code == 303

    async with session_factory() as session:
        user = await get_user_by_email(session, "strongling@example.com")
    assert user is not None


# --- T69: rate limiting -----------------------------------------------------


async def test_login_rate_limited_after_repeated_failures(
    http_client: tuple[AsyncClient, async_sessionmaker[AsyncSession]],
) -> None:
    client, session_factory = http_client

    async with session_factory() as session:
        await create_user(session, "bruteforce@example.com", "correct-password")

    original_max = config.RATE_LIMIT_LOGIN_MAX_ATTEMPTS
    config.RATE_LIMIT_LOGIN_MAX_ATTEMPTS = 3
    try:
        for _ in range(3):
            response = await client.post(
                "/auth/login",
                data={
                    "email": "bruteforce@example.com",
                    "password": "wrong-password",
                },
            )
            assert response.status_code == 401

        blocked = await client.post(
            "/auth/login",
            data={"email": "bruteforce@example.com", "password": "wrong-password"},
        )
    finally:
        config.RATE_LIMIT_LOGIN_MAX_ATTEMPTS = original_max

    assert blocked.status_code == 429
    assert "Retry-After" in blocked.headers


async def test_login_does_not_count_successful_attempts(
    http_client: tuple[AsyncClient, async_sessionmaker[AsyncSession]],
) -> None:
    """Successful logins shouldn't count against the failure-based limiter."""
    client, session_factory = http_client

    async with session_factory() as session:
        await create_user(session, "repeat-login@example.com", "correct-password")

    original_max = config.RATE_LIMIT_LOGIN_MAX_ATTEMPTS
    config.RATE_LIMIT_LOGIN_MAX_ATTEMPTS = 2
    try:
        for _ in range(5):
            response = await client.post(
                "/auth/login",
                data={
                    "email": "repeat-login@example.com",
                    "password": "correct-password",
                },
                follow_redirects=False,
            )
            assert response.status_code == 303
    finally:
        config.RATE_LIMIT_LOGIN_MAX_ATTEMPTS = original_max


async def test_signup_rate_limited_after_repeated_attempts(
    http_client: tuple[AsyncClient, async_sessionmaker[AsyncSession]],
) -> None:
    client, _session_factory = http_client

    original_enabled = config.FEATURE_SIGNUP_ENABLED
    original_max = config.RATE_LIMIT_SIGNUP_MAX_ATTEMPTS
    config.FEATURE_SIGNUP_ENABLED = True
    config.RATE_LIMIT_SIGNUP_MAX_ATTEMPTS = 2
    try:
        for index in range(2):
            response = await client.post(
                "/auth/signup",
                data={
                    "email": f"signup-{index}@example.com",
                    "password": "correct-horse-battery-staple",
                },
                follow_redirects=False,
            )
            assert response.status_code == 303

        blocked = await client.post(
            "/auth/signup",
            data={
                "email": "signup-blocked@example.com",
                "password": "correct-horse-battery-staple",
            },
        )
    finally:
        config.FEATURE_SIGNUP_ENABLED = original_enabled
        config.RATE_LIMIT_SIGNUP_MAX_ATTEMPTS = original_max

    assert blocked.status_code == 429
    assert "Retry-After" in blocked.headers


# --- T69: revocable sessions -------------------------------------------------


async def test_get_current_user_rejects_stale_token_version(
    db_session: AsyncSession,
) -> None:
    """A JWT minted before a token_version bump must stop authenticating."""
    email = "stale-version@example.com"
    user = await create_user(db_session, email, "test_password123")

    token = create_access_token({"sub": email, "ver": str(user.token_version)})
    resolved = await auth.get_current_user(token, db_session)
    assert resolved is not None
    assert resolved.email == email

    user.token_version += 1
    await db_session.commit()

    stale_resolved = await auth.get_current_user(token, db_session)
    assert stale_resolved is None


async def test_logout_revokes_existing_session(
    http_client: tuple[AsyncClient, async_sessionmaker[AsyncSession]],
) -> None:
    """Logout must invalidate the JWT server-side, not just clear the cookie."""
    client, session_factory = http_client

    async with session_factory() as session:
        await create_user(session, "logout-me@example.com", "correct-password")

    login_response = await client.post(
        "/auth/login",
        data={"email": "logout-me@example.com", "password": "correct-password"},
        follow_redirects=False,
    )
    assert login_response.status_code == 303
    session_token = client.cookies.get("session_token")
    assert session_token

    me_response = await client.get("/auth/me")
    assert me_response.status_code == 200

    logout_response = await client.post("/auth/logout", follow_redirects=False)
    assert logout_response.status_code == 303

    # Re-attach the cookie logout just deleted client-side, to prove the
    # server-side token_version bump (not just cookie clearing) is what
    # revokes the session.
    client.cookies.set("session_token", session_token)
    me_after_logout = await client.get("/auth/me")
    assert me_after_logout.status_code == 401
