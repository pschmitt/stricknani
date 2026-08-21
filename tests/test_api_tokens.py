"""Tests for personal access tokens (SNA-1): model, auth helpers, and routes."""

import re
from collections.abc import AsyncGenerator
from datetime import UTC, datetime, timedelta

import pytest
from httpx import AsyncClient
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker, create_async_engine

from stricknani.config import config
from stricknani.models import ApiToken, Base, User
from stricknani.utils.auth import (
    generate_api_token as _generate_api_token,
)
from stricknani.utils.auth import (
    get_password_hash,
    get_user_from_api_token,
    hash_api_token,
)

_TOKEN_ID_RE = re.compile(r"/user/api-tokens/(\d+)/delete")


@pytest.fixture
async def db_session() -> AsyncGenerator[AsyncSession]:
    """A fresh in-memory database session, independent of the app's engine."""
    engine = create_async_engine("sqlite+aiosqlite:///:memory:")
    async with engine.begin() as conn:
        await conn.run_sync(Base.metadata.create_all)

    async_session = async_sessionmaker(
        engine, class_=AsyncSession, expire_on_commit=False
    )
    async with async_session() as session:
        yield session


async def _make_user(db: AsyncSession, *, is_active: bool = True) -> User:
    user = User(
        email="tokentester@example.com",
        hashed_password=get_password_hash("secret"),
        is_active=is_active,
    )
    db.add(user)
    await db.commit()
    await db.refresh(user)
    return user


def test_generate_api_token_hash_roundtrip() -> None:
    """The generated raw token must hash to the returned hash, and hashing is stable."""
    raw_token, token_hash = _generate_api_token()

    assert raw_token.startswith("sna_")
    assert token_hash == hash_api_token(raw_token)
    # Every generated token is unique.
    other_raw, other_hash = _generate_api_token()
    assert other_raw != raw_token
    assert other_hash != token_hash


async def test_get_user_from_api_token_valid(db_session: AsyncSession) -> None:
    """A valid, unexpired token for an active user resolves to that user."""
    user = await _make_user(db_session)
    raw_token, token_hash = _generate_api_token()
    db_session.add(ApiToken(user_id=user.id, name="Test Token", token_hash=token_hash))
    await db_session.commit()

    resolved = await get_user_from_api_token(db_session, raw_token)

    assert resolved is not None
    assert resolved.id == user.id


async def test_get_user_from_api_token_bumps_last_used(
    db_session: AsyncSession,
) -> None:
    user = await _make_user(db_session)
    raw_token, token_hash = _generate_api_token()
    token = ApiToken(user_id=user.id, name="Test Token", token_hash=token_hash)
    db_session.add(token)
    await db_session.commit()
    assert token.last_used_at is None

    await get_user_from_api_token(db_session, raw_token)

    result = await db_session.execute(select(ApiToken).where(ApiToken.id == token.id))
    refreshed = result.scalar_one()
    assert refreshed.last_used_at is not None


async def test_get_user_from_api_token_unknown_token(
    db_session: AsyncSession,
) -> None:
    resolved = await get_user_from_api_token(db_session, "sna_does-not-exist")
    assert resolved is None


async def test_get_user_from_api_token_expired(db_session: AsyncSession) -> None:
    user = await _make_user(db_session)
    raw_token, token_hash = _generate_api_token()
    db_session.add(
        ApiToken(
            user_id=user.id,
            name="Expired Token",
            token_hash=token_hash,
            expires_at=datetime.now(UTC) - timedelta(days=1),
        )
    )
    await db_session.commit()

    resolved = await get_user_from_api_token(db_session, raw_token)

    assert resolved is None


async def test_get_user_from_api_token_inactive_user(db_session: AsyncSession) -> None:
    user = await _make_user(db_session, is_active=False)
    raw_token, token_hash = _generate_api_token()
    db_session.add(ApiToken(user_id=user.id, name="Test Token", token_hash=token_hash))
    await db_session.commit()

    resolved = await get_user_from_api_token(db_session, raw_token)

    assert resolved is None


async def test_create_list_and_revoke_api_token(
    test_client: tuple[
        AsyncClient,
        async_sessionmaker[AsyncSession],
        int,
        int,
        int,
    ],
) -> None:
    client, session_factory, user_id, _project_id, _step_id = test_client

    create_response = await client.post("/user/api-tokens", data={"name": "My Phone"})
    assert create_response.status_code == 200
    assert "My Phone" in create_response.text

    match = re.search(r'value="(sna_[^"]+)"', create_response.text)
    assert match is not None, "raw token value missing from create response"
    raw_token = match.group(1)

    # The raw token is never persisted - only its hash.
    async with session_factory() as session:
        result = await session.execute(
            select(ApiToken).where(ApiToken.user_id == user_id)
        )
        stored = result.scalar_one()
        assert stored.token_hash == hash_api_token(raw_token)
        assert stored.token_hash != raw_token
        token_id = stored.id

    list_response = await client.get("/user/api-tokens")
    assert list_response.status_code == 200
    assert "My Phone" in list_response.text
    # The raw token must not be shown again on a fresh page load.
    assert raw_token not in list_response.text

    id_match = _TOKEN_ID_RE.search(list_response.text)
    assert id_match is not None
    assert int(id_match.group(1)) == token_id

    delete_response = await client.post(
        f"/user/api-tokens/{token_id}/delete", follow_redirects=False
    )
    assert delete_response.status_code == 303

    async with session_factory() as session:
        result = await session.execute(select(ApiToken).where(ApiToken.id == token_id))
        assert result.scalar_one_or_none() is None


async def test_qr_setup_creates_token_and_renders_qr(
    test_client: tuple[
        AsyncClient,
        async_sessionmaker[AsyncSession],
        int,
        int,
        int,
    ],
) -> None:
    """SNA-13: /user/api-tokens/qr-setup mints a token and embeds it in a QR image."""
    client, session_factory, user_id, _project_id, _step_id = test_client

    response = await client.post("/user/api-tokens/qr-setup")
    assert response.status_code == 200
    assert "QR setup" in response.text
    assert 'src="data:image/png;base64,' in response.text
    assert 'class="qr-code-frame"' in response.text
    assert 'class="qr-code-image"' in response.text
    assert 'class="qr-code-url' in response.text
    assert "stricknani://setup?p=" in response.text

    async with session_factory() as session:
        result = await session.execute(
            select(ApiToken).where(
                ApiToken.user_id == user_id, ApiToken.name == "QR setup"
            )
        )
        assert result.scalar_one_or_none() is not None


async def test_cannot_revoke_another_users_token(
    test_client: tuple[
        AsyncClient,
        async_sessionmaker[AsyncSession],
        int,
        int,
        int,
    ],
) -> None:
    client, session_factory, _user_id, _project_id, _step_id = test_client

    async with session_factory() as session:
        other_user = User(
            email="otheruser@example.com",
            hashed_password=get_password_hash("secret"),
        )
        session.add(other_user)
        await session.commit()
        await session.refresh(other_user)

        _raw_token, token_hash = _generate_api_token()
        other_token = ApiToken(
            user_id=other_user.id, name="Not Yours", token_hash=token_hash
        )
        session.add(other_token)
        await session.commit()
        await session.refresh(other_token)
        other_token_id = other_token.id

    response = await client.post(f"/user/api-tokens/{other_token_id}/delete")
    assert response.status_code == 404

    async with session_factory() as session:
        result = await session.execute(
            select(ApiToken).where(ApiToken.id == other_token_id)
        )
        assert result.scalar_one_or_none() is not None


async def test_post_with_bearer_header_skips_csrf(
    test_client: tuple[
        AsyncClient,
        async_sessionmaker[AsyncSession],
        int,
        int,
        int,
    ],
) -> None:
    """A Bearer-authenticated request is exempt from CSRF (main.py).

    Uses a real state-changing route (`/projects/{id}/favorite`) with the
    real CSRF dependency enabled - only the `Authorization` header should
    matter here, independent of whether the token itself is valid (this
    fixture overrides `require_auth`/`get_current_user` directly).
    """
    client, _session_factory, _user_id, project_id, _step_id = test_client

    config.TESTING = False

    response = await client.post(
        f"/projects/{project_id}/favorite",
        headers={"Authorization": "Bearer sna_whatever-not-validated-here"},
        follow_redirects=False,
    )

    assert response.status_code != 403


async def test_api_token_creation_is_rate_limited(
    test_client: tuple[
        AsyncClient,
        async_sessionmaker[AsyncSession],
        int,
        int,
        int,
    ],
) -> None:
    """T107: minting tokens has no cap by default, letting a compromised or
    malicious session mint an unbounded number of long-lived credentials."""
    client, *_ = test_client

    original_max = config.RATE_LIMIT_API_TOKEN_MAX_ATTEMPTS
    config.RATE_LIMIT_API_TOKEN_MAX_ATTEMPTS = 2
    try:
        for _ in range(2):
            response = await client.post("/user/api-tokens", data={"name": "Device"})
            assert response.status_code == 200

        blocked = await client.post("/user/api-tokens", data={"name": "Device"})
    finally:
        config.RATE_LIMIT_API_TOKEN_MAX_ATTEMPTS = original_max

    assert blocked.status_code == 429
    assert "Retry-After" in blocked.headers
