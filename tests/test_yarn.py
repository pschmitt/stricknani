import pytest
from httpx import AsyncClient
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker

from stricknani.models import Yarn


@pytest.mark.asyncio
async def test_create_yarn_saves_recommended_needles(
    test_client: tuple[AsyncClient, async_sessionmaker[AsyncSession], int, int, int],
) -> None:
    client, session_factory, user_id, _project_id, _step_id = test_client

    response = await client.post(
        "/yarn/",
        data={
            "name": "Merino DK",
            "recommended_needles": "4.0mm",
        },
        follow_redirects=False,
    )

    assert response.status_code == 303

    async with session_factory() as session:
        result = await session.execute(select(Yarn).where(Yarn.owner_id == user_id))
        yarn = result.scalars().one()

    assert yarn.recommended_needles == "4.0mm"
