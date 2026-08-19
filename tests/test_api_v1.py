"""Tests for the versioned JSON API (SNA-2): categories, yarns, projects."""

import io
from collections.abc import AsyncGenerator
from typing import Any

import pytest
from httpx import ASGITransport, AsyncClient
from PIL import Image as PilImage
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker, create_async_engine

from stricknani.config import config
from stricknani.database import get_db
from stricknani.main import app
from stricknani.models import ApiToken, Base, User
from stricknani.utils.auth import generate_api_token, get_password_hash


def _png_bytes() -> bytes:
    buffer = io.BytesIO()
    PilImage.new("RGB", (4, 4), color="red").save(buffer, format="PNG")
    return buffer.getvalue()


@pytest.fixture
async def api_client(
    tmp_path: Any,
) -> AsyncGenerator[tuple[AsyncClient, async_sessionmaker[AsyncSession], int]]:
    """An end-to-end client authenticated with a real Bearer API token.

    Unlike `test_client` in conftest.py (which overrides `require_auth` for
    the cookie-session HTML routes), this exercises the real
    `require_api_token` dependency chain end to end.
    """
    engine = create_async_engine("sqlite+aiosqlite:///:memory:?cache=shared")
    async with engine.begin() as conn:
        await conn.run_sync(Base.metadata.create_all)

    session_factory = async_sessionmaker(engine, expire_on_commit=False)

    async with session_factory() as session:
        user = User(
            email="apitester@example.com",
            hashed_password=get_password_hash("secret"),
        )
        session.add(user)
        await session.commit()
        await session.refresh(user)
        user_id = user.id

        raw_token, token_hash = generate_api_token()
        session.add(
            ApiToken(user_id=user_id, name="Test Client", token_hash=token_hash)
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


ClientFixture = tuple[AsyncClient, async_sessionmaker[AsyncSession], int]


async def test_meta_does_not_require_auth() -> None:
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        response = await client.get("/api/v1/meta")
    assert response.status_code == 200
    body = response.json()
    assert "version" in body
    assert "build_id" in body


async def test_api_requires_bearer_token() -> None:
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        response = await client.get("/api/v1/yarns")
    assert response.status_code == 401


async def test_api_rejects_invalid_bearer_token(api_client: ClientFixture) -> None:
    # Reuses api_client's DB override (get_user_from_api_token still has to
    # run a real query) rather than a bare client against the app's real,
    # unmigrated-in-CI database - a per-request header overrides the
    # fixture's default valid one (httpx: request-level headers win).
    client, _session_factory, _user_id = api_client
    response = await client.get(
        "/api/v1/yarns", headers={"Authorization": "Bearer sna_not-a-real-token"}
    )
    assert response.status_code == 401


async def test_category_list_and_create(api_client: ClientFixture) -> None:
    client, _session_factory, _user_id = api_client

    create_response = await client.post("/api/v1/categories", json={"name": "Socks"})
    assert create_response.status_code == 201
    assert create_response.json()["name"] == "Socks"

    list_response = await client.get("/api/v1/categories")
    assert list_response.status_code == 200
    names = [c["name"] for c in list_response.json()]
    assert "Socks" in names


async def test_yarn_crud_and_favorite(api_client: ClientFixture) -> None:
    client, _session_factory, _user_id = api_client

    create_response = await client.post(
        "/api/v1/yarns",
        json={"name": "Merino Wool", "brand": "Acme", "weight_grams": 100},
    )
    assert create_response.status_code == 201
    yarn = create_response.json()
    assert yarn["name"] == "Merino Wool"
    assert yarn["is_favorite"] is False
    yarn_id = yarn["id"]

    get_response = await client.get(f"/api/v1/yarns/{yarn_id}")
    assert get_response.status_code == 200
    assert get_response.json()["brand"] == "Acme"

    list_response = await client.get("/api/v1/yarns")
    assert list_response.status_code == 200
    page = list_response.json()
    assert page["page"] == 1
    assert any(item["id"] == yarn_id for item in page["items"])

    update_response = await client.put(
        f"/api/v1/yarns/{yarn_id}",
        json={"name": "Merino Wool DK", "brand": "Acme"},
    )
    assert update_response.status_code == 200
    assert update_response.json()["name"] == "Merino Wool DK"

    fav_response = await client.post(f"/api/v1/yarns/{yarn_id}/favorite")
    assert fav_response.status_code == 200
    assert fav_response.json()["is_favorite"] is True

    fav_list = await client.get("/api/v1/yarns", params={"favorite": "true"})
    assert any(item["id"] == yarn_id for item in fav_list.json()["items"])

    unfav_response = await client.request("DELETE", f"/api/v1/yarns/{yarn_id}/favorite")
    assert unfav_response.status_code == 200
    assert unfav_response.json()["is_favorite"] is False

    delete_response = await client.delete(f"/api/v1/yarns/{yarn_id}")
    assert delete_response.status_code == 204

    missing_response = await client.get(f"/api/v1/yarns/{yarn_id}")
    assert missing_response.status_code == 404


async def test_yarn_update_conflict_when_expected_updated_at_stale(
    api_client: ClientFixture,
) -> None:
    """SNA-33: see the project equivalent's docstring - same conflict contract."""
    client, _session_factory, _user_id = api_client

    create_response = await client.post("/api/v1/yarns", json={"name": "Original"})
    yarn = create_response.json()
    yarn_id = yarn["id"]
    original_updated_at = yarn["updated_at"]

    first_edit = await client.put(
        f"/api/v1/yarns/{yarn_id}", json={"name": "Edited elsewhere"}
    )
    assert first_edit.status_code == 200

    stale_edit = await client.put(
        f"/api/v1/yarns/{yarn_id}",
        json={"name": "Stale local edit", "expected_updated_at": original_updated_at},
    )
    assert stale_edit.status_code == 409
    assert stale_edit.json()["detail"]["name"] == "Edited elsewhere"

    get_response = await client.get(f"/api/v1/yarns/{yarn_id}")
    assert get_response.json()["name"] == "Edited elsewhere"


async def test_yarn_photo_upload_and_delete(api_client: ClientFixture) -> None:
    client, _session_factory, _user_id = api_client

    create_response = await client.post("/api/v1/yarns", json={"name": "Photo Yarn"})
    yarn_id = create_response.json()["id"]

    upload_response = await client.post(
        f"/api/v1/yarns/{yarn_id}/photos",
        files={"file": ("swatch.png", _png_bytes(), "image/png")},
    )
    assert upload_response.status_code == 201
    photo = upload_response.json()
    assert photo["is_primary"] is True
    photo_id = photo["id"]

    detail = await client.get(f"/api/v1/yarns/{yarn_id}")
    assert len(detail.json()["photos"]) == 1

    delete_response = await client.delete(f"/api/v1/yarns/{yarn_id}/photos/{photo_id}")
    assert delete_response.status_code == 204

    detail_after = await client.get(f"/api/v1/yarns/{yarn_id}")
    assert detail_after.json()["photos"] == []


async def test_project_crud_with_steps_and_yarn_links(
    api_client: ClientFixture,
) -> None:
    client, _session_factory, _user_id = api_client

    yarn_response = await client.post("/api/v1/yarns", json={"name": "Sock Yarn"})
    yarn_id = yarn_response.json()["id"]

    create_response = await client.post(
        "/api/v1/projects",
        json={
            "name": "Striped Socks",
            "category": "Socken",
            "tags": ["socks", "stripes"],
            "yarn_ids": [yarn_id],
            "steps": [
                {"title": "Cast on", "description": "CO 64", "step_number": 1},
                {"title": "Knit heel", "step_number": 2},
            ],
        },
    )
    assert create_response.status_code == 201
    project = create_response.json()
    assert project["name"] == "Striped Socks"
    assert project["category"] == "Socken"
    assert sorted(project["tags"]) == ["socks", "stripes"]
    assert project["yarn_ids"] == [yarn_id]
    assert len(project["steps"]) == 2
    project_id = project["id"]

    get_response = await client.get(f"/api/v1/projects/{project_id}")
    assert get_response.status_code == 200

    list_response = await client.get("/api/v1/projects", params={"category": "Socken"})
    assert any(item["id"] == project_id for item in list_response.json()["items"])

    tag_list_response = await client.get("/api/v1/projects", params={"tag": "socks"})
    assert any(item["id"] == project_id for item in tag_list_response.json()["items"])

    update_response = await client.put(
        f"/api/v1/projects/{project_id}",
        json={
            "name": "Striped Socks v2",
            "tags": ["socks"],
            "yarn_ids": [],
            "steps": [{"title": "Cast on", "step_number": 1}],
        },
    )
    assert update_response.status_code == 200
    updated = update_response.json()
    assert updated["name"] == "Striped Socks v2"
    assert updated["yarn_ids"] == []
    assert len(updated["steps"]) == 1

    fav_response = await client.post(f"/api/v1/projects/{project_id}/favorite")
    assert fav_response.status_code == 200
    assert fav_response.json()["is_favorite"] is True

    fav_list = await client.get("/api/v1/projects", params={"favorite": "true"})
    assert any(item["id"] == project_id for item in fav_list.json()["items"])

    delete_response = await client.delete(f"/api/v1/projects/{project_id}")
    assert delete_response.status_code == 204

    missing = await client.get(f"/api/v1/projects/{project_id}")
    assert missing.status_code == 404


async def test_project_update_conflict_when_expected_updated_at_stale(
    api_client: ClientFixture,
) -> None:
    """SNA-33: a stale `expected_updated_at` is rejected with 409 and the current
    server state, instead of silently overwriting whatever changed in between."""
    client, _session_factory, _user_id = api_client

    create_response = await client.post("/api/v1/projects", json={"name": "Original"})
    project = create_response.json()
    project_id = project["id"]
    original_updated_at = project["updated_at"]

    # A second edit lands first (e.g. from another device), moving updated_at forward.
    first_edit = await client.put(
        f"/api/v1/projects/{project_id}", json={"name": "Edited elsewhere"}
    )
    assert first_edit.status_code == 200

    # A stale client, still holding the original updated_at, writes on top of it.
    stale_edit = await client.put(
        f"/api/v1/projects/{project_id}",
        json={"name": "Stale local edit", "expected_updated_at": original_updated_at},
    )
    assert stale_edit.status_code == 409
    conflict_body = stale_edit.json()["detail"]
    assert conflict_body["name"] == "Edited elsewhere"

    # The project's name is untouched by the rejected write.
    get_response = await client.get(f"/api/v1/projects/{project_id}")
    assert get_response.json()["name"] == "Edited elsewhere"

    # A client that re-reads the current updated_at can then write successfully.
    fresh_edit = await client.put(
        f"/api/v1/projects/{project_id}",
        json={
            "name": "Fresh local edit",
            "expected_updated_at": conflict_body["updated_at"],
        },
    )
    assert fresh_edit.status_code == 200
    assert fresh_edit.json()["name"] == "Fresh local edit"


async def test_project_update_without_expected_updated_at_still_overwrites(
    api_client: ClientFixture,
) -> None:
    """Omitting `expected_updated_at` (e.g. the web UI) keeps the old
    unconditional-write behavior - SNA-33 only changes callers that opt in."""
    client, _session_factory, _user_id = api_client

    create_response = await client.post("/api/v1/projects", json={"name": "Original"})
    project_id = create_response.json()["id"]

    await client.put(f"/api/v1/projects/{project_id}", json={"name": "First edit"})
    second_edit = await client.put(
        f"/api/v1/projects/{project_id}", json={"name": "Second edit"}
    )
    assert second_edit.status_code == 200
    assert second_edit.json()["name"] == "Second edit"


async def test_project_title_image_step_image_and_attachment(
    api_client: ClientFixture,
) -> None:
    client, _session_factory, _user_id = api_client

    create_response = await client.post(
        "/api/v1/projects",
        json={
            "name": "Media Project",
            "steps": [{"title": "Step one", "step_number": 1}],
        },
    )
    project = create_response.json()
    project_id = project["id"]
    step_id = project["steps"][0]["id"]

    title_response = await client.post(
        f"/api/v1/projects/{project_id}/images/title",
        files={"file": ("title.png", _png_bytes(), "image/png")},
    )
    assert title_response.status_code == 201
    title_image = title_response.json()
    assert title_image["is_title_image"] is True

    step_image_response = await client.post(
        f"/api/v1/projects/{project_id}/steps/{step_id}/images",
        files={"file": ("step.png", _png_bytes(), "image/png")},
    )
    assert step_image_response.status_code == 201
    step_image = step_image_response.json()
    assert step_image["step_id"] == step_id

    attachment_response = await client.post(
        f"/api/v1/projects/{project_id}/attachments",
        files={"file": ("notes.txt", b"hello world", "text/plain")},
    )
    assert attachment_response.status_code == 201
    attachment = attachment_response.json()
    assert attachment["original_filename"] == "notes.txt"

    detail = await client.get(f"/api/v1/projects/{project_id}")
    body = detail.json()
    assert len(body["images"]) == 2
    assert len(body["attachments"]) == 1

    delete_image_response = await client.delete(
        f"/api/v1/projects/{project_id}/images/{title_image['id']}"
    )
    assert delete_image_response.status_code == 204

    delete_attachment_response = await client.delete(
        f"/api/v1/projects/{project_id}/attachments/{attachment['id']}"
    )
    assert delete_attachment_response.status_code == 204

    detail_after = await client.get(f"/api/v1/projects/{project_id}")
    body_after = detail_after.json()
    assert len(body_after["images"]) == 1
    assert body_after["attachments"] == []


async def test_cannot_access_another_users_project_or_yarn(
    api_client: ClientFixture,
) -> None:
    client, session_factory, _user_id = api_client

    async with session_factory() as session:
        other_user = User(
            email="other-api-user@example.com",
            hashed_password=get_password_hash("secret"),
        )
        session.add(other_user)
        await session.commit()
        await session.refresh(other_user)
        other_raw_token, other_token_hash = generate_api_token()
        session.add(
            ApiToken(
                user_id=other_user.id, name="Other Client", token_hash=other_token_hash
            )
        )
        await session.commit()

    project_response = await client.post("/api/v1/projects", json={"name": "Mine Only"})
    project_id = project_response.json()["id"]

    other_get = await client.get(
        f"/api/v1/projects/{project_id}",
        headers={"Authorization": f"Bearer {other_raw_token}"},
    )
    assert other_get.status_code == 404

    other_delete = await client.delete(
        f"/api/v1/projects/{project_id}",
        headers={"Authorization": f"Bearer {other_raw_token}"},
    )
    assert other_delete.status_code == 404
