"""Coverage for the yarn resolution helpers used by the JSON API."""

from typing import Any

import pytest
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker

from stricknani.models import Yarn, YarnImage
from stricknani.services.projects.yarns import (
    ensure_yarns_by_text,
    get_user_yarns,
    load_owned_yarns,
    resolve_yarn_preview,
)

TestClientFixture = tuple[Any, async_sessionmaker[AsyncSession], int, int, int]


def test_resolve_yarn_preview_handles_empty_and_primary_photo() -> None:
    yarn = Yarn(id=7, name="Merino")
    assert resolve_yarn_preview(yarn) == {
        "preview_url": None,
        "preview_alt": None,
    }

    yarn.photos = [
        YarnImage(
            filename="merino.png",
            original_filename="merino.png",
            alt_text="Swatch",
        )
    ]
    assert resolve_yarn_preview(yarn) == {
        "preview_url": "/media/thumbnails/yarns/7/thumb_merino.jpg",
        "preview_alt": "Swatch",
    }


@pytest.mark.asyncio
async def test_load_and_list_owned_yarns_include_thumbnail_previews(
    test_client: TestClientFixture,
) -> None:
    _client, session_factory, user_id, _project_id, _step_id = test_client

    async with session_factory() as db:
        yarn = Yarn(name="A yarn", owner_id=user_id)
        other_yarn = Yarn(name="Other yarn", owner_id=user_id)
        db.add_all([yarn, other_yarn])
        await db.flush()
        db.add(
            YarnImage(
                filename="swatch.png",
                original_filename="swatch.png",
                alt_text="",
                yarn_id=yarn.id,
            )
        )
        await db.commit()
        yarn_id = yarn.id
        other_yarn_id = other_yarn.id

    async with session_factory() as db:
        assert await load_owned_yarns(db, user_id, []) == []
        owned = await load_owned_yarns(db, user_id, [other_yarn_id, yarn_id])
        assert {item.id for item in owned} == {yarn_id, other_yarn_id}

        listed = await get_user_yarns(db, user_id)
        assert [item.name for item in listed][-2:] == ["A yarn", "Other yarn"]
        assert listed[0].photos[0].filename == (
            f"/media/thumbnails/yarns/{yarn_id}/thumb_swatch.jpg"
        )


@pytest.mark.asyncio
async def test_ensure_yarns_by_text_reuses_and_creates_raw_names(
    test_client: TestClientFixture,
) -> None:
    _client, session_factory, user_id, _project_id, _step_id = test_client

    async with session_factory() as db:
        existing = Yarn(name="Merino", owner_id=user_id)
        db.add(existing)
        await db.flush()

        resolved = await ensure_yarns_by_text(
            db,
            user_id,
            "Merino, Alpaca",
            [existing.id],
            yarn_brand="Example",
        )
        await db.commit()

        assert resolved[0] == existing.id
        assert len(resolved) == 2
        created = await db.get(Yarn, resolved[1])
        assert created is not None
        assert created.name == "Alpaca"
        assert created.brand == "Example"

        multiline = await ensure_yarns_by_text(
            db,
            user_id,
            "Cotton\noder: Linen\n\noder: Wool",
            [],
        )
        assert len(multiline) == 3


@pytest.mark.asyncio
async def test_ensure_yarns_by_text_supports_structured_details_and_empty_input(
    test_client: TestClientFixture,
) -> None:
    _client, session_factory, user_id, _project_id, _step_id = test_client

    async with session_factory() as db:
        assert await ensure_yarns_by_text(db, user_id, None, [42]) == [42]
        resolved = await ensure_yarns_by_text(
            db,
            user_id,
            None,
            [],
            yarn_brand="Fallback brand",
            yarn_details=[
                {},
                {
                    "name": "Silk",
                    "brand": "Luxury",
                    "colorway": "Blue",
                },
            ],
        )
        await db.commit()

        assert len(resolved) == 1
        created = await db.get(Yarn, resolved[0])
        assert created is not None
        assert created.brand == "Luxury"
        assert created.colorway == "Blue"
