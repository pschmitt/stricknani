import pytest
from fastapi import HTTPException
from httpx import AsyncClient
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker

from stricknani.config import config
from stricknani.main import app
from stricknani.routes.auth import require_auth


@pytest.mark.anyio
async def test_global_search_returns_results(
    test_client: tuple[AsyncClient, async_sessionmaker[AsyncSession], int, int, int],
) -> None:
    client, _session_factory, _user_id, _project_id, _step_id = test_client
    resp = await client.get("/search/global", params={"q": "Sam"})
    assert resp.status_code == 200
    assert "Sample Project" in resp.text


@pytest.mark.anyio
async def test_global_search_renders_with_csrf_protection_enabled(
    test_client: tuple[AsyncClient, async_sessionmaker[AsyncSession], int, int, int],
) -> None:
    client, _session_factory, _user_id, _project_id, _step_id = test_client
    original_testing = config.TESTING
    config.TESTING = False
    try:
        response = await client.get("/search/global", params={"q": "Sam"})
    finally:
        config.TESTING = original_testing

    assert response.status_code == 200
    assert "Sample Project" in response.text


@pytest.mark.anyio
async def test_global_search_requires_authentication(
    test_client: tuple[AsyncClient, async_sessionmaker[AsyncSession], int, int, int],
) -> None:
    client, _session_factory, _user_id, _project_id, _step_id = test_client
    previous_override = app.dependency_overrides.get(require_auth)

    async def deny_authentication() -> None:
        raise HTTPException(status_code=401, detail="Not authenticated")

    app.dependency_overrides[require_auth] = deny_authentication
    try:
        response = await client.get("/search/global", params={"q": "Sam"})
    finally:
        if previous_override is None:
            app.dependency_overrides.pop(require_auth, None)
        else:
            app.dependency_overrides[require_auth] = previous_override

    assert response.status_code == 401
