"""Shared helpers for project imports and tag/category handling."""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession
from stricknani.config import config
from stricknani.models import Category, Image, ImageType, Project, Yarn
from stricknani.utils.files import save_bytes
from stricknani.utils.importer import PatternImporter


def normalize_tags(raw_tags: str | None) -> list[str]:
    """Normalize user-supplied tags into a list of strings."""

    if not raw_tags:
        return []

    try:
        data = json.loads(raw_tags)
    except json.JSONDecodeError:
        data = None

    if isinstance(data, list):
        return [str(tag).strip() for tag in data if str(tag).strip()]

    return [
        segment.strip()
        for segment in raw_tags.replace(";", ",").split(",")
        if segment.strip()
    ]


def serialize_tags(tags: list[str]) -> str | None:
    """Serialize tags to a JSON string, or return None if empty."""

    if not tags:
        return None
    return json.dumps(sorted(set(tags), key=str.casefold))


async def sync_project_categories(db: AsyncSession, user_id: int) -> None:
    """Ensure categories exist for all projects for a given user."""

    result = await db.execute(
        select(Project)
        .where(Project.owner_id == user_id)
        .options(selectinload(Project.owner))
    )
    projects = result.scalars().all()
    categories = {
        category.name
        for category in (
            await db.execute(
                select(Category).where(Category.user_id == user_id)
            )
        ).scalars()
    }
    for project in projects:
        if project.category and project.category not in categories:
            db.add(Category(name=project.category, user_id=user_id))
            categories.add(project.category)


def build_ai_hints(data: dict[str, Any]) -> dict[str, Any]:
    """Build import hints for the AI importer from project data."""

    return {
        "title": data.get("name"),
        "category": data.get("category"),
        "yarn": data.get("yarn"),
        "needles": data.get("needles"),
        "gauge_stitches": data.get("gauge_stitches"),
        "gauge_rows": data.get("gauge_rows"),
        "notes": data.get("comment"),
        "tags": data.get("tags"),
    }


async def import_images_from_urls(
    db: AsyncSession,
    project: Project,
    image_urls: list[str],
) -> None:
    """Import images from URLs into the project's media directory."""

    if not image_urls:
        return

    importer = PatternImporter(
        config.MEDIA_ROOT / "projects" / str(project.id)
    )
    imported_images = await importer.download_images(image_urls)

    for image_path, original_name in imported_images:
        filename, original_filename = save_bytes(
            Path(image_path).read_bytes(),
            original_name,
            project.id,
            subdir="projects",
        )
        db.add(
            Image(
                filename=filename,
                original_filename=original_filename,
                image_type=ImageType.PHOTO.value,
                alt_text=project.name,
                is_title_image=False,
                project_id=project.id,
            )
        )


async def link_yarns_by_name(
    db: AsyncSession, project: Project, yarn_names: list[str]
) -> None:
    """Link yarns to a project using yarn names."""

    if not yarn_names:
        return

    result = await db.execute(
        select(Yarn).where(
            Yarn.owner_id == project.owner_id,
            Yarn.name.in_(yarn_names),
        )
    )
    yarns = result.scalars().all()
    for yarn in yarns:
        if yarn not in project.yarns:
            project.yarns.append(yarn)
