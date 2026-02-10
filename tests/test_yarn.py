import json

import pytest
from httpx import AsyncClient
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker

from stricknani.models import AuditLog, Yarn


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


@pytest.mark.asyncio
async def test_create_and_update_yarn_write_audit_logs(
    test_client: tuple[AsyncClient, async_sessionmaker[AsyncSession], int, int, int],
) -> None:
    client, session_factory, _user_id, _project_id, _step_id = test_client

    create_response = await client.post(
        "/yarn/",
        data={
            "name": "Audit Yarn",
            "brand": "Brand A",
        },
        follow_redirects=False,
    )
    assert create_response.status_code == 303
    yarn_id = int(
        create_response.headers["location"].split("?")[0].strip("/").split("/")[1]
    )

    update_response = await client.post(
        f"/yarn/{yarn_id}/edit",
        data={
            "name": "Audit Yarn",
            "brand": "Brand B",
            "notes": "Updated notes",
        },
        follow_redirects=False,
    )
    assert update_response.status_code == 303

    async with session_factory() as session:
        result = await session.execute(
            select(AuditLog)
            .where(
                AuditLog.entity_type == "yarn",
                AuditLog.entity_id == yarn_id,
            )
            .order_by(AuditLog.id.asc())
        )
        audit_entries = result.scalars().all()

    actions = [entry.action for entry in audit_entries]
    assert "created" in actions
    assert "updated" in actions

    updated_entry = next(entry for entry in audit_entries if entry.action == "updated")
    assert updated_entry.details is not None
    changes = json.loads(updated_entry.details).get("changes", {})
    assert "brand" in changes
