import pytest
from httpx import AsyncClient
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker


@pytest.mark.anyio
async def test_global_search_returns_results(
    test_client: tuple[AsyncClient, async_sessionmaker[AsyncSession], int, int, int],
) -> None:
    client, _session_factory, _user_id, _project_id, _step_id = test_client
    resp = await client.get("/search/global", params={"q": "Sam"})
    assert resp.status_code == 200
    assert "Sample Project" in resp.text
