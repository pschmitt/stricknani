"""Per-object media authorization tests (T70).

``/media`` is served through ``stricknani.routes.media`` instead of a raw
static mount. These tests exercise the real auth flow (no dependency
overrides for ``require_auth``/``get_current_user``, unlike ``test_client``
in ``conftest.py``) so cross-user and unauthenticated denial are actually
verified end to end.
"""

from collections.abc import AsyncGenerator
from typing import Any

import pytest
from httpx import ASGITransport, AsyncClient
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker, create_async_engine

from stricknani.config import config
from stricknani.database import get_db
from stricknani.main import app
from stricknani.models import ApiToken, Base, Image, Project, User, Yarn, YarnImage
from stricknani.utils.auth import (
    create_access_token,
    generate_api_token,
    get_password_hash,
)


@pytest.fixture
async def media_authz_client(
    tmp_path: Any,
) -> AsyncGenerator[dict[str, Any]]:
    engine = create_async_engine("sqlite+aiosqlite:///:memory:?cache=shared")
    async with engine.begin() as conn:
        await conn.run_sync(Base.metadata.create_all)

    session_factory = async_sessionmaker(engine, expire_on_commit=False)

    async with session_factory() as session:
        owner = User(
            email="owner@example.com",
            hashed_password=get_password_hash("secret"),
        )
        other = User(
            email="other@example.com",
            hashed_password=get_password_hash("secret"),
        )
        admin = User(
            email="admin@example.com",
            hashed_password=get_password_hash("secret"),
            is_admin=True,
        )
        session.add_all([owner, other, admin])
        await session.commit()
        for user in (owner, other, admin):
            await session.refresh(user)

        project = Project(name="Owner's Project", owner_id=owner.id)
        session.add(project)
        await session.commit()
        await session.refresh(project)

        image = Image(
            filename="20260101_000000_aaaaaaaa.jpg",
            original_filename="photo.jpg",
            image_type="photo",
            alt_text="A photo",
            project_id=project.id,
        )
        session.add(image)

        yarn = Yarn(name="Owner's Yarn", owner_id=owner.id)
        session.add(yarn)
        await session.commit()
        await session.refresh(yarn)

        yarn_image = YarnImage(
            filename="20260101_000000_bbbbbbbb.jpg",
            original_filename="skein.jpg",
            yarn_id=yarn.id,
        )
        session.add(yarn_image)
        await session.commit()

        owner_raw_api_token, owner_token_hash = generate_api_token()
        session.add(
            ApiToken(
                user_id=owner.id, name="Owner API Token", token_hash=owner_token_hash
            )
        )
        other_raw_api_token, other_token_hash = generate_api_token()
        session.add(
            ApiToken(
                user_id=other.id, name="Other API Token", token_hash=other_token_hash
            )
        )
        await session.commit()

        owner_id = owner.id
        other_id = other.id
        admin_id = admin.id
        project_id = project.id
        yarn_id = yarn.id

    original_media_root = config.MEDIA_ROOT
    config.MEDIA_ROOT = tmp_path / "media"
    config.ensure_media_dirs()

    project_dir = config.MEDIA_ROOT / "projects" / str(project_id)
    project_dir.mkdir(parents=True, exist_ok=True)
    (project_dir / "20260101_000000_aaaaaaaa.jpg").write_bytes(b"fake-jpeg-bytes")
    (project_dir / "20260101_000000_cccccccc.pdf").write_bytes(b"fake-pdf-bytes")
    (project_dir / "20260101_000000_dddddddd.svg").write_bytes(
        b"<svg onload='alert(1)'></svg>"
    )

    project_thumb_dir = config.MEDIA_ROOT / "thumbnails" / "projects" / str(project_id)
    project_thumb_dir.mkdir(parents=True, exist_ok=True)
    (project_thumb_dir / "thumb_20260101_000000_aaaaaaaa.jpg").write_bytes(b"thumb")

    yarn_dir = config.MEDIA_ROOT / "yarns" / str(yarn_id)
    yarn_dir.mkdir(parents=True, exist_ok=True)
    (yarn_dir / "20260101_000000_bbbbbbbb.jpg").write_bytes(b"fake-yarn-bytes")

    owner_avatar_dir = config.MEDIA_ROOT / "users" / str(owner_id)
    owner_avatar_dir.mkdir(parents=True, exist_ok=True)
    (owner_avatar_dir / "avatar.jpg").write_bytes(b"fake-avatar-bytes")

    trace_dir = config.MEDIA_ROOT / "import-traces" / "whatever"
    trace_dir.mkdir(parents=True, exist_ok=True)
    (trace_dir / "trace.json").write_bytes(b"{}")

    async def override_get_db() -> AsyncGenerator[AsyncSession]:
        async with session_factory() as session:
            yield session

    app.dependency_overrides[get_db] = override_get_db

    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        yield {
            "client": client,
            "owner_id": owner_id,
            "other_id": other_id,
            "admin_id": admin_id,
            "project_id": project_id,
            "yarn_id": yarn_id,
            "owner_token": create_access_token(data={"sub": owner.email}),
            "other_token": create_access_token(data={"sub": other.email}),
            "admin_token": create_access_token(data={"sub": admin.email}),
            "owner_api_token": owner_raw_api_token,
            "other_api_token": other_raw_api_token,
        }

    app.dependency_overrides.clear()
    config.MEDIA_ROOT = original_media_root
    await engine.dispose()


def _as(client: AsyncClient, token: str) -> None:
    client.cookies.set("session_token", token)


def _anonymous(client: AsyncClient) -> None:
    client.cookies.clear()


async def test_owner_can_access_own_project_image(
    media_authz_client: dict[str, Any],
) -> None:
    client = media_authz_client["client"]
    _as(client, media_authz_client["owner_token"])

    response = await client.get(
        f"/media/projects/{media_authz_client['project_id']}/20260101_000000_aaaaaaaa.jpg"
    )

    assert response.status_code == 200
    assert response.content == b"fake-jpeg-bytes"
    assert response.headers["x-content-type-options"] == "nosniff"
    assert response.headers["content-disposition"] == "inline"


async def test_pdf_attachment_served_inline(
    media_authz_client: dict[str, Any],
) -> None:
    client = media_authz_client["client"]
    _as(client, media_authz_client["owner_token"])

    response = await client.get(
        f"/media/projects/{media_authz_client['project_id']}/20260101_000000_cccccccc.pdf"
    )

    assert response.status_code == 200
    assert response.headers["content-disposition"] == "inline"


async def test_non_renderable_attachment_forced_to_download(
    media_authz_client: dict[str, Any],
) -> None:
    """T107: an attachment with a browser-renderable-but-unvalidated extension
    (e.g. .svg, which can carry a <script>/onload payload) must never be
    served `inline` - that would execute it in the app's own origin."""
    client = media_authz_client["client"]
    _as(client, media_authz_client["owner_token"])

    response = await client.get(
        f"/media/projects/{media_authz_client['project_id']}/20260101_000000_dddddddd.svg"
    )

    assert response.status_code == 200
    assert response.headers["content-disposition"] == (
        'attachment; filename="20260101_000000_dddddddd.svg"'
    )


async def test_owner_can_access_own_project_thumbnail(
    media_authz_client: dict[str, Any],
) -> None:
    client = media_authz_client["client"]
    _as(client, media_authz_client["owner_token"])

    response = await client.get(
        "/media/thumbnails/projects/"
        f"{media_authz_client['project_id']}/thumb_20260101_000000_aaaaaaaa.jpg"
    )

    assert response.status_code == 200
    assert response.content == b"thumb"


async def test_owner_can_access_own_yarn_image(
    media_authz_client: dict[str, Any],
) -> None:
    client = media_authz_client["client"]
    _as(client, media_authz_client["owner_token"])

    response = await client.get(
        f"/media/yarns/{media_authz_client['yarn_id']}/20260101_000000_bbbbbbbb.jpg"
    )

    assert response.status_code == 200
    assert response.content == b"fake-yarn-bytes"


async def test_owner_can_access_own_avatar(
    media_authz_client: dict[str, Any],
) -> None:
    client = media_authz_client["client"]
    _as(client, media_authz_client["owner_token"])

    owner_id = media_authz_client["owner_id"]
    response = await client.get(f"/media/users/{owner_id}/avatar.jpg")

    assert response.status_code == 200
    assert response.content == b"fake-avatar-bytes"


async def test_non_owner_is_forbidden_from_project_image(
    media_authz_client: dict[str, Any],
) -> None:
    client = media_authz_client["client"]
    _as(client, media_authz_client["other_token"])

    response = await client.get(
        f"/media/projects/{media_authz_client['project_id']}/20260101_000000_aaaaaaaa.jpg"
    )

    assert response.status_code == 403


async def test_non_owner_is_forbidden_from_project_thumbnail(
    media_authz_client: dict[str, Any],
) -> None:
    client = media_authz_client["client"]
    _as(client, media_authz_client["other_token"])

    response = await client.get(
        "/media/thumbnails/projects/"
        f"{media_authz_client['project_id']}/thumb_20260101_000000_aaaaaaaa.jpg"
    )

    assert response.status_code == 403


async def test_non_owner_is_forbidden_from_yarn_image(
    media_authz_client: dict[str, Any],
) -> None:
    client = media_authz_client["client"]
    _as(client, media_authz_client["other_token"])

    response = await client.get(
        f"/media/yarns/{media_authz_client['yarn_id']}/20260101_000000_bbbbbbbb.jpg"
    )

    assert response.status_code == 403


async def test_non_owner_is_forbidden_from_avatar(
    media_authz_client: dict[str, Any],
) -> None:
    client = media_authz_client["client"]
    _as(client, media_authz_client["other_token"])

    owner_id = media_authz_client["owner_id"]
    response = await client.get(f"/media/users/{owner_id}/avatar.jpg")

    assert response.status_code == 403


async def test_admin_can_access_any_avatar(
    media_authz_client: dict[str, Any],
) -> None:
    client = media_authz_client["client"]
    _as(client, media_authz_client["admin_token"])

    owner_id = media_authz_client["owner_id"]
    response = await client.get(f"/media/users/{owner_id}/avatar.jpg")

    assert response.status_code == 200
    assert response.content == b"fake-avatar-bytes"


async def test_admin_has_no_bypass_for_project_images(
    media_authz_client: dict[str, Any],
) -> None:
    """Admins can manage users, but the rest of the app grants them no special
    access to other users' projects/yarns; media serving must match that."""
    client = media_authz_client["client"]
    _as(client, media_authz_client["admin_token"])

    response = await client.get(
        f"/media/projects/{media_authz_client['project_id']}/20260101_000000_aaaaaaaa.jpg"
    )

    assert response.status_code == 403


async def test_unauthenticated_user_is_denied_project_image(
    media_authz_client: dict[str, Any],
) -> None:
    client = media_authz_client["client"]
    _anonymous(client)

    response = await client.get(
        f"/media/projects/{media_authz_client['project_id']}/20260101_000000_aaaaaaaa.jpg"
    )

    assert response.status_code == 401


async def test_unauthenticated_user_is_denied_avatar(
    media_authz_client: dict[str, Any],
) -> None:
    client = media_authz_client["client"]
    _anonymous(client)

    owner_id = media_authz_client["owner_id"]
    response = await client.get(f"/media/users/{owner_id}/avatar.jpg")

    assert response.status_code == 401


async def test_nonexistent_project_returns_404(
    media_authz_client: dict[str, Any],
) -> None:
    client = media_authz_client["client"]
    _as(client, media_authz_client["owner_token"])

    response = await client.get("/media/projects/999999/whatever.jpg")

    assert response.status_code == 404


async def test_existing_project_missing_file_on_disk_returns_404(
    media_authz_client: dict[str, Any],
) -> None:
    client = media_authz_client["client"]
    _as(client, media_authz_client["owner_token"])

    response = await client.get(
        f"/media/projects/{media_authz_client['project_id']}/does-not-exist.jpg"
    )

    assert response.status_code == 404


async def test_import_traces_directory_is_never_served(
    media_authz_client: dict[str, Any],
) -> None:
    client = media_authz_client["client"]
    _as(client, media_authz_client["owner_token"])

    response = await client.get("/media/import-traces/whatever/trace.json")

    assert response.status_code == 404


async def test_pending_imports_directory_is_never_served(
    media_authz_client: dict[str, Any],
) -> None:
    client = media_authz_client["client"]
    _as(client, media_authz_client["owner_token"])

    response = await client.get(
        f"/media/imports/projects/{media_authz_client['owner_id']}/token.jpg"
    )

    assert response.status_code == 404


async def test_unknown_subdir_returns_404(
    media_authz_client: dict[str, Any],
) -> None:
    client = media_authz_client["client"]
    _as(client, media_authz_client["owner_token"])

    response = await client.get("/media/whatever/1/file.jpg")

    assert response.status_code == 404


async def test_bearer_token_owner_can_access_own_project_image(
    media_authz_client: dict[str, Any],
) -> None:
    """T74/SNA-4: the API-token auth path works alongside the session cookie."""
    client = media_authz_client["client"]
    _anonymous(client)

    response = await client.get(
        f"/media/projects/{media_authz_client['project_id']}/20260101_000000_aaaaaaaa.jpg",
        headers={"Authorization": f"Bearer {media_authz_client['owner_api_token']}"},
    )

    assert response.status_code == 200
    assert response.content == b"fake-jpeg-bytes"


async def test_bearer_token_non_owner_is_forbidden(
    media_authz_client: dict[str, Any],
) -> None:
    client = media_authz_client["client"]
    _anonymous(client)

    response = await client.get(
        f"/media/projects/{media_authz_client['project_id']}/20260101_000000_aaaaaaaa.jpg",
        headers={"Authorization": f"Bearer {media_authz_client['other_api_token']}"},
    )

    assert response.status_code == 403


async def test_invalid_bearer_token_is_unauthorized(
    media_authz_client: dict[str, Any],
) -> None:
    client = media_authz_client["client"]
    _anonymous(client)

    response = await client.get(
        f"/media/projects/{media_authz_client['project_id']}/20260101_000000_aaaaaaaa.jpg",
        headers={"Authorization": "Bearer sna_not-a-real-token"},
    )

    assert response.status_code == 401
