import json
from io import BytesIO

import pytest
from httpx import AsyncClient
from PIL import Image as PilImage
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker

from stricknani.models import AuditLog, User, Yarn, YarnImage, user_favorite_yarns
from stricknani.utils.auth import get_password_hash


def _make_png_bytes(color: tuple[int, int, int, int]) -> bytes:
    buffer = BytesIO()
    image = PilImage.new("RGBA", (2, 2), color)
    image.save(buffer, format="PNG")
    return buffer.getvalue()


@pytest.mark.asyncio
async def test_toggle_favorite_on_own_yarn_succeeds(
    test_client: tuple[AsyncClient, async_sessionmaker[AsyncSession], int, int, int],
) -> None:
    client, session_factory, user_id, _project_id, _step_id = test_client

    async with session_factory() as session:
        yarn = Yarn(name="My Yarn", owner_id=user_id)
        session.add(yarn)
        await session.commit()
        await session.refresh(yarn)
        yarn_id = yarn.id

    response = await client.post(f"/yarn/{yarn_id}/favorite", follow_redirects=False)
    assert response.status_code == 303

    async with session_factory() as session:
        result = await session.execute(
            select(user_favorite_yarns).where(
                user_favorite_yarns.c.user_id == user_id,
                user_favorite_yarns.c.yarn_id == yarn_id,
            )
        )
        assert result.first() is not None


@pytest.mark.asyncio
async def test_toggle_favorite_on_other_users_yarn_is_forbidden(
    test_client: tuple[AsyncClient, async_sessionmaker[AsyncSession], int, int, int],
) -> None:
    """T106: `toggle_favorite` used to skip the ownership check that
    `favorite_project`/`unfavorite_project` already had, letting any
    authenticated user favorite/unfavorite another user's yarn and use the
    404-vs-200/403 response as an existence oracle for private yarn ids."""
    client, session_factory, _user_id, _project_id, _step_id = test_client

    async with session_factory() as session:
        other_owner = User(
            email="other-yarn-owner@example.com",
            hashed_password=get_password_hash("secret"),
        )
        session.add(other_owner)
        await session.commit()
        await session.refresh(other_owner)

        other_yarn = Yarn(name="Someone Else's Yarn", owner_id=other_owner.id)
        session.add(other_yarn)
        await session.commit()
        await session.refresh(other_yarn)
        other_yarn_id = other_yarn.id

    response = await client.post(f"/yarn/{other_yarn_id}/favorite")
    assert response.status_code == 403

    async with session_factory() as session:
        result = await session.execute(
            select(user_favorite_yarns).where(
                user_favorite_yarns.c.yarn_id == other_yarn_id,
            )
        )
        assert result.first() is None


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


@pytest.mark.asyncio
async def test_uploading_yarn_photos_keeps_single_primary(
    test_client: tuple[AsyncClient, async_sessionmaker[AsyncSession], int, int, int],
) -> None:
    client, session_factory, _user_id, _project_id, _step_id = test_client

    create_response = await client.post(
        "/yarn/",
        data={"name": "Primary Yarn"},
        follow_redirects=False,
    )
    assert create_response.status_code == 303
    yarn_id = int(
        create_response.headers["location"].split("?")[0].strip("/").split("/")[1]
    )

    png1 = _make_png_bytes((255, 0, 0, 255))
    png2 = _make_png_bytes((0, 255, 0, 255))

    upload1 = await client.post(
        f"/yarn/{yarn_id}/photos",
        files={"file": ("first.png", png1, "image/png")},
    )
    assert upload1.status_code == 200
    upload2 = await client.post(
        f"/yarn/{yarn_id}/photos",
        files={"file": ("second.png", png2, "image/png")},
    )
    assert upload2.status_code == 200

    async with session_factory() as session:
        result = await session.execute(
            select(YarnImage).where(YarnImage.yarn_id == yarn_id).order_by(YarnImage.id)
        )
        photos = result.scalars().all()

    assert len(photos) == 2
    assert sum(1 for photo in photos if photo.is_primary) == 1
    assert photos[0].is_primary is True


@pytest.mark.asyncio
async def test_deleting_primary_yarn_photo_promotes_fallback(
    test_client: tuple[AsyncClient, async_sessionmaker[AsyncSession], int, int, int],
) -> None:
    client, session_factory, _user_id, _project_id, _step_id = test_client

    create_response = await client.post(
        "/yarn/",
        data={"name": "Delete Primary Yarn"},
        follow_redirects=False,
    )
    assert create_response.status_code == 303
    yarn_id = int(
        create_response.headers["location"].split("?")[0].strip("/").split("/")[1]
    )

    png1 = _make_png_bytes((255, 0, 0, 255))
    png2 = _make_png_bytes((0, 255, 0, 255))

    await client.post(
        f"/yarn/{yarn_id}/photos",
        files={"file": ("first.png", png1, "image/png")},
    )
    await client.post(
        f"/yarn/{yarn_id}/photos",
        files={"file": ("second.png", png2, "image/png")},
    )

    async with session_factory() as session:
        result = await session.execute(
            select(YarnImage).where(YarnImage.yarn_id == yarn_id).order_by(YarnImage.id)
        )
        photos = result.scalars().all()
    primary = next(photo for photo in photos if photo.is_primary)

    delete_response = await client.post(
        f"/yarn/{yarn_id}/photos/{primary.id}/delete",
        headers={"accept": "application/json"},
    )
    assert delete_response.status_code == 200

    async with session_factory() as session:
        result = await session.execute(
            select(YarnImage).where(YarnImage.yarn_id == yarn_id).order_by(YarnImage.id)
        )
        remaining = result.scalars().all()

    assert len(remaining) == 1
    assert remaining[0].is_primary is True
