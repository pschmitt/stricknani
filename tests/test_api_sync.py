"""Tests for the delta-sync endpoints (SNA-3): projects, yarns, categories."""

from collections.abc import AsyncGenerator
from datetime import datetime
from typing import Any

import pytest
from httpx import ASGITransport, AsyncClient
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker, create_async_engine

from stricknani.config import config
from stricknani.database import get_db
from stricknani.main import app
from stricknani.models import ApiToken, Base, User
from stricknani.utils.auth import generate_api_token, get_password_hash

ClientFixture = tuple[AsyncClient, async_sessionmaker[AsyncSession], int]


@pytest.fixture
async def api_client(tmp_path: Any) -> AsyncGenerator[ClientFixture]:
    """Same shape as test_api_v1.py's fixture - a Bearer-authenticated client
    backed by a real in-memory database (not overriding `require_api_token`),
    so the sync endpoints' real auth + DB queries run end to end."""
    engine = create_async_engine("sqlite+aiosqlite:///:memory:?cache=shared")
    async with engine.begin() as conn:
        await conn.run_sync(Base.metadata.create_all)

    session_factory = async_sessionmaker(engine, expire_on_commit=False)

    async with session_factory() as session:
        user = User(
            email="synctester@example.com",
            hashed_password=get_password_hash("secret"),
        )
        session.add(user)
        await session.commit()
        await session.refresh(user)
        user_id = user.id

        raw_token, token_hash = generate_api_token()
        session.add(
            ApiToken(user_id=user_id, name="Sync Client", token_hash=token_hash)
        )
        await session.commit()

    config.TESTING = True
    original_media_root = config.MEDIA_ROOT
    config.MEDIA_ROOT = tmp_path / "media"
    config.ensure_media_dirs()

    async def override_get_db() -> AsyncGenerator[AsyncSession]:
        async with session_factory() as session:
            yield session

    app.dependency_overrides[get_db] = override_get_db

    transport = ASGITransport(app=app)
    async with AsyncClient(
        transport=transport,
        base_url="http://test",
        headers={"Authorization": f"Bearer {raw_token}"},
    ) as client:
        yield client, session_factory, user_id

    app.dependency_overrides.clear()
    config.MEDIA_ROOT = original_media_root
    await engine.dispose()


async def test_sync_projects_initial_full_sync(api_client: ClientFixture) -> None:
    client, _session_factory, _user_id = api_client

    await client.post("/api/v1/projects", json={"name": "Project A"})
    await client.post("/api/v1/projects", json={"name": "Project B"})

    response = await client.get("/api/v1/sync/projects")
    assert response.status_code == 200
    body = response.json()
    assert len(body["updated"]) == 2
    assert body["deleted_ids"] == []
    assert body["full_resync_required"] is False
    assert body["has_more"] is False
    assert body["next_cursor"] is None
    assert "server_time" in body


async def test_sync_projects_paginates_with_an_opaque_cursor(
    api_client: ClientFixture,
) -> None:
    client, _session_factory, _user_id = api_client

    project_ids = []
    for name in ("Project A", "Project B", "Project C", "Project D", "Project E"):
        response = await client.post("/api/v1/projects", json={"name": name})
        project_ids.append(response.json()["id"])

    seen_ids: list[int] = []
    cursor: str | None = None
    pages = 0
    snapshot: str | None = None
    while True:
        params: dict[str, str | int] = {"limit": 2}
        if cursor is not None:
            params["cursor"] = cursor
        response = await client.get("/api/v1/sync/projects", params=params)
        assert response.status_code == 200
        body = response.json()
        assert len(body["updated"]) + len(body["deleted_ids"]) <= 2
        if snapshot is None:
            snapshot = body["server_time"]
        else:
            assert body["server_time"] == snapshot
        seen_ids.extend(project["id"] for project in body["updated"])
        pages += 1
        if not body["has_more"]:
            assert body["next_cursor"] is None
            break
        assert body["next_cursor"]
        cursor = body["next_cursor"]
        assert pages < 10

    assert pages == 3
    assert set(seen_ids) == set(project_ids)
    assert len(seen_ids) == len(set(seen_ids))


async def test_sync_projects_pagination_merges_updates_and_deletions(
    api_client: ClientFixture,
) -> None:
    client, _session_factory, _user_id = api_client

    old_response = await client.post("/api/v1/projects", json={"name": "Old Project"})
    deleted_id = old_response.json()["id"]
    baseline = (await client.get("/api/v1/sync/projects")).json()["server_time"]

    updated_ids = []
    for name in ("New Project A", "New Project B", "New Project C"):
        response = await client.post("/api/v1/projects", json={"name": name})
        updated_ids.append(response.json()["id"])
    await client.delete(f"/api/v1/projects/{deleted_id}")

    seen_updated: list[int] = []
    seen_deleted: list[int] = []
    cursor: str | None = None
    snapshot: str | None = None
    while True:
        params: dict[str, str | int] = {"since": baseline, "limit": 2}
        if cursor is not None:
            params = {"cursor": cursor, "limit": 2}
        body = (await client.get("/api/v1/sync/projects", params=params)).json()
        assert len(body["updated"]) + len(body["deleted_ids"]) <= 2
        if snapshot is None:
            snapshot = body["server_time"]
        else:
            assert body["server_time"] == snapshot
        seen_updated.extend(project["id"] for project in body["updated"])
        seen_deleted.extend(body["deleted_ids"])
        if not body["has_more"]:
            break
        cursor = body["next_cursor"]

    assert set(seen_updated) == set(updated_ids)
    assert seen_deleted == [deleted_id]
    assert len(seen_updated) == len(set(seen_updated))
    assert len(seen_deleted) == len(set(seen_deleted))


async def test_sync_yarns_paginates_with_a_cursor(api_client: ClientFixture) -> None:
    client, _session_factory, _user_id = api_client

    yarn_ids = []
    for name in ("Yarn A", "Yarn B", "Yarn C"):
        response = await client.post("/api/v1/yarns", json={"name": name})
        yarn_ids.append(response.json()["id"])

    first = (await client.get("/api/v1/sync/yarns", params={"limit": 1})).json()
    assert len(first["updated"]) == 1
    assert first["deleted_ids"] == []
    assert first["has_more"] is True

    second = (
        await client.get("/api/v1/sync/yarns", params={"cursor": first["next_cursor"]})
    ).json()
    third = (
        await client.get("/api/v1/sync/yarns", params={"cursor": second["next_cursor"]})
    ).json()
    assert second["has_more"] is True
    assert third["has_more"] is False
    assert third["next_cursor"] is None
    seen_ids = [
        first["updated"][0]["id"],
        second["updated"][0]["id"],
        third["updated"][0]["id"],
    ]
    assert set(seen_ids) == set(yarn_ids)
    assert len(seen_ids) == len(set(seen_ids))


async def test_sync_cursor_is_bound_to_endpoint_and_cannot_mix_with_since(
    api_client: ClientFixture,
) -> None:
    client, _session_factory, _user_id = api_client

    await client.post("/api/v1/projects", json={"name": "Project A"})
    await client.post("/api/v1/projects", json={"name": "Project B"})
    first = (await client.get("/api/v1/sync/projects", params={"limit": 1})).json()
    cursor = first["next_cursor"]
    assert cursor

    mixed = await client.get(
        "/api/v1/sync/projects",
        params={"cursor": cursor, "since": first["server_time"]},
    )
    assert mixed.status_code == 400

    wrong_endpoint = await client.get("/api/v1/sync/yarns", params={"cursor": cursor})
    assert wrong_endpoint.status_code == 400

    malformed = await client.get(
        "/api/v1/sync/projects", params={"cursor": "not-a-cursor"}
    )
    assert malformed.status_code == 400


async def test_sync_limit_is_bounded(api_client: ClientFixture) -> None:
    client, _session_factory, _user_id = api_client

    too_large = await client.get("/api/v1/sync/projects", params={"limit": 51})
    assert too_large.status_code == 422

    zero = await client.get("/api/v1/sync/projects", params={"limit": 0})
    assert zero.status_code == 422


async def test_sync_projects_delta_only_returns_recently_updated(
    api_client: ClientFixture,
) -> None:
    client, _session_factory, _user_id = api_client

    await client.post("/api/v1/projects", json={"name": "Old Project"})
    cursor_response = await client.get("/api/v1/sync/projects")
    cursor = cursor_response.json()["server_time"]

    create_response = await client.post(
        "/api/v1/projects", json={"name": "New Project"}
    )
    new_project_id = create_response.json()["id"]

    delta_response = await client.get("/api/v1/sync/projects", params={"since": cursor})
    assert delta_response.status_code == 200
    body = delta_response.json()
    assert [p["id"] for p in body["updated"]] == [new_project_id]
    assert body["full_resync_required"] is False


async def test_sync_projects_reports_deletions_since_cursor(
    api_client: ClientFixture,
) -> None:
    client, _session_factory, _user_id = api_client

    create_response = await client.post(
        "/api/v1/projects", json={"name": "To Be Deleted"}
    )
    project_id = create_response.json()["id"]

    baseline = (await client.get("/api/v1/sync/projects")).json()["server_time"]

    await client.delete(f"/api/v1/projects/{project_id}")

    delta_response = await client.get(
        "/api/v1/sync/projects", params={"since": baseline}
    )
    body = delta_response.json()
    assert body["updated"] == []
    assert body["deleted_ids"] == [project_id]


async def test_sync_yarns_delta_and_deletion(api_client: ClientFixture) -> None:
    client, _session_factory, _user_id = api_client

    baseline = (await client.get("/api/v1/sync/yarns")).json()["server_time"]

    create_response = await client.post("/api/v1/yarns", json={"name": "New Yarn"})
    yarn_id = create_response.json()["id"]

    delta_response = await client.get("/api/v1/sync/yarns", params={"since": baseline})
    body = delta_response.json()
    assert [y["id"] for y in body["updated"]] == [yarn_id]

    second_baseline = body["server_time"]
    await client.delete(f"/api/v1/yarns/{yarn_id}")

    after_delete = (
        await client.get("/api/v1/sync/yarns", params={"since": second_baseline})
    ).json()
    assert after_delete["updated"] == []
    assert after_delete["deleted_ids"] == [yarn_id]


async def test_sync_projects_never_requires_full_resync_for_an_ancient_since(
    api_client: ClientFixture,
) -> None:
    """An old `since` (predating any AuditLog row, e.g. a fresh account's
    first-ever sync) must not be confused with an unsafe gap in deletion
    coverage - see sync.py's module docstring for why this field is
    currently always False."""
    client, _session_factory, _user_id = api_client

    create_response = await client.post(
        "/api/v1/projects", json={"name": "Some Project"}
    )
    project_id = create_response.json()["id"]

    ancient_since = datetime(2000, 1, 1).isoformat()
    response = await client.get(
        "/api/v1/sync/projects", params={"since": ancient_since}
    )
    body = response.json()
    assert body["full_resync_required"] is False
    assert [p["id"] for p in body["updated"]] == [project_id]
    assert body["deleted_ids"] == []


async def test_sync_categories_returns_full_list(api_client: ClientFixture) -> None:
    client, _session_factory, _user_id = api_client

    await client.post("/api/v1/categories", json={"name": "Socks"})
    await client.post("/api/v1/categories", json={"name": "Sweaters"})

    response = await client.get("/api/v1/sync/categories")
    assert response.status_code == 200
    body = response.json()
    names = {c["name"] for c in body["updated"]}
    assert names == {"Socks", "Sweaters"}
    assert body["deleted_ids"] == []
