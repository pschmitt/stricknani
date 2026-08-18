"""Tests for the password-login onboarding endpoint (SNA-13): /api/v1/auth/token."""

from collections.abc import AsyncGenerator

import pytest
from httpx import ASGITransport, AsyncClient
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker, create_async_engine

from stricknani.config import config
from stricknani.database import get_db
from stricknani.main import app
from stricknani.models import ApiToken, Base
from stricknani.utils.auth import create_user, get_user_from_api_token


@pytest.fixture
async def http_client() -> AsyncGenerator[
    tuple[AsyncClient, async_sessionmaker[AsyncSession]]
]:
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


async def test_mint_token_with_correct_credentials(
    http_client: tuple[AsyncClient, async_sessionmaker[AsyncSession]],
) -> None:
    client, session_factory = http_client
    async with session_factory() as session:
        await create_user(session, "minter@example.com", "correct-password")

    response = await client.post(
        "/api/v1/auth/token",
        json={"email": "minter@example.com", "password": "correct-password"},
    )

    assert response.status_code == 200
    token = response.json()["token"]
    assert token

    async with session_factory() as session:
        user = await get_user_from_api_token(session, token)
    assert user is not None
    assert user.email == "minter@example.com"


async def test_mint_token_uses_custom_name(
    http_client: tuple[AsyncClient, async_sessionmaker[AsyncSession]],
) -> None:
    client, session_factory = http_client
    async with session_factory() as session:
        await create_user(session, "named@example.com", "correct-password")

    await client.post(
        "/api/v1/auth/token",
        json={
            "email": "named@example.com",
            "password": "correct-password",
            "token_name": "My Pixel 5",
        },
    )

    async with session_factory() as session:
        result = await session.execute(
            select(ApiToken).where(ApiToken.name == "My Pixel 5")
        )
        assert result.scalar_one_or_none() is not None


async def test_mint_token_rejects_wrong_password(
    http_client: tuple[AsyncClient, async_sessionmaker[AsyncSession]],
) -> None:
    client, session_factory = http_client
    async with session_factory() as session:
        await create_user(session, "wrongpw@example.com", "correct-password")

    response = await client.post(
        "/api/v1/auth/token",
        json={"email": "wrongpw@example.com", "password": "wrong-password"},
    )

    assert response.status_code == 401


async def test_mint_token_rejects_unknown_email(
    http_client: tuple[AsyncClient, async_sessionmaker[AsyncSession]],
) -> None:
    client, _session_factory = http_client

    response = await client.post(
        "/api/v1/auth/token",
        json={"email": "nobody@example.com", "password": "whatever"},
    )

    assert response.status_code == 401


async def test_mint_token_rate_limited_after_repeated_failures(
    http_client: tuple[AsyncClient, async_sessionmaker[AsyncSession]],
) -> None:
    client, session_factory = http_client
    async with session_factory() as session:
        await create_user(session, "bruteforce-mint@example.com", "correct-password")

    original_max = config.RATE_LIMIT_LOGIN_MAX_ATTEMPTS
    config.RATE_LIMIT_LOGIN_MAX_ATTEMPTS = 3
    try:
        for _ in range(3):
            response = await client.post(
                "/api/v1/auth/token",
                json={
                    "email": "bruteforce-mint@example.com",
                    "password": "wrong-password",
                },
            )
            assert response.status_code == 401

        blocked = await client.post(
            "/api/v1/auth/token",
            json={
                "email": "bruteforce-mint@example.com",
                "password": "wrong-password",
            },
        )
    finally:
        config.RATE_LIMIT_LOGIN_MAX_ATTEMPTS = original_max

    assert blocked.status_code == 429
    assert "Retry-After" in blocked.headers
