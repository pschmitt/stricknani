from __future__ import annotations

import json
from typing import Any

import pytest
from sqlalchemy import select

from stricknani.models import Project
from stricknani.services.images.dimensions import get_image_dimensions
from stricknani.services.projects.categories import ensure_category, get_user_categories
from stricknani.services.projects.steps import create_step, update_step
from stricknani.services.projects.tags import (
    deserialize_tags,
    get_user_tags,
    normalize_tags,
    serialize_tags,
)


def test_normalize_tags() -> None:
    assert normalize_tags(None) == []
    assert normalize_tags("") == []
    assert normalize_tags("  ") == []

    # Split on commas, hashes and whitespace; dedupe case-insensitively.
    assert normalize_tags("Foo, foo #Bar baz") == ["Foo", "Bar", "baz"]


def test_serialize_deserialize_tags_json() -> None:
    raw = serialize_tags(["Foo", "Bar"])
    assert raw is not None
    assert json.loads(raw) == ["Foo", "Bar"]
    assert deserialize_tags(raw) == ["Foo", "Bar"]


def test_deserialize_tags_fallback_csv() -> None:
    assert deserialize_tags(" Foo, Bar ,") == ["Foo", "Bar"]


@pytest.mark.asyncio
async def test_get_user_tags_dedup_and_sort(test_client: Any) -> None:
    _client, session_factory, user_id, _project_id, _step_id = test_client

    async with session_factory() as db:
        db.add(Project(name="P1", owner_id=user_id, tags='["Foo", "bar"]'))
        db.add(Project(name="P2", owner_id=user_id, tags='["foo", "Baz"]'))
        await db.commit()

    async with session_factory() as db:
        tags = await get_user_tags(db, user_id)

    assert tags == ["bar", "Baz", "Foo"]


@pytest.mark.asyncio
async def test_step_create_and_update(test_client: Any) -> None:
    _client, session_factory, _user_id, project_id, _step_id = test_client

    async with session_factory() as db:
        step = await create_step(
            db,
            project_id=project_id,
            title="One",
            description=None,
            step_number=2,
        )

        updated = await update_step(
            db,
            step=step,
            title="Two",
            description="Updated",
            step_number=3,
        )

        assert updated.id == step.id
        assert updated.title == "Two"
        assert updated.description == "Updated"
        assert updated.step_number == 3

        # Ensure persisted.
        result = await db.execute(select(type(step)).where(type(step).id == step.id))
        persisted = result.scalar_one()
        assert persisted.title == "Two"


@pytest.mark.asyncio
async def test_ensure_category_creates_and_dedupes(test_client: Any) -> None:
    _client, session_factory, user_id, _project_id, _step_id = test_client

    async with session_factory() as db:
        name = await ensure_category(db, user_id, " Hats ")
        assert name == "Hats"
        await db.commit()

    async with session_factory() as db:
        name2 = await ensure_category(db, user_id, "hats")
        assert name2 == "Hats"
        await db.commit()

        categories = await get_user_categories(db, user_id)
        assert "Hats" in categories


@pytest.mark.asyncio
async def test_get_image_dimensions_reads_from_media_root(
    test_client: Any,
    tmp_path: Any,
) -> None:
    _client, _session_factory, _user_id, project_id, _step_id = test_client

    from PIL import Image as PilImage

    # Fixture sets config.MEDIA_ROOT to tmp_path/media.
    media_root = tmp_path / "media" / "projects" / str(project_id)
    media_root.mkdir(parents=True, exist_ok=True)
    filename = "test.png"
    image_path = media_root / filename
    with PilImage.new("RGB", (123, 45), color=(255, 0, 0)) as img:
        img.save(image_path, format="PNG")

    width, height = await get_image_dimensions(filename, project_id, subdir="projects")
    assert (width, height) == (123, 45)
