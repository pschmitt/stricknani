"""Shared helpers for project imports and tag/category handling."""

from __future__ import annotations

import asyncio
import json
import logging
from dataclasses import dataclass
from io import BytesIO
from pathlib import Path
from typing import Any
from urllib.parse import urlparse

import httpx
from PIL import Image as PilImage
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm import selectinload

from stricknani.config import config
from stricknani.models import Category, Image, ImageType, Project, Yarn
from stricknani.utils.files import create_thumbnail, delete_file, save_bytes
from stricknani.utils.image_similarity import (
    SimilarityImage,
    build_similarity_image,
    compute_similarity_score,
)
from stricknani.utils.importer import (
    IMPORT_IMAGE_MIN_DIMENSION,
    IMPORT_IMAGE_SSIM_THRESHOLD,
)


@dataclass
class _ImportedSimilarity:
    similarity: SimilarityImage
    image: Image
    filename: str


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
            await db.execute(select(Category).where(Category.user_id == user_id))
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
        "notes": data.get("notes"),
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

    logger = logging.getLogger("stricknani.cli")
    headers = {
        "User-Agent": (
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            "AppleWebKit/537.36 (KHTML, like Gecko) "
            "Chrome/120.0.0.0 Safari/537.36"
        )
    }

    imported_similarities: list[_ImportedSimilarity] = []

    async with httpx.AsyncClient(
        timeout=20,
        follow_redirects=True,
        headers=headers,
    ) as client:
        for index, image_url in enumerate(image_urls, 1):
            try:
                response = await client.get(image_url)
                response.raise_for_status()
            except Exception as exc:
                logger.warning("Failed to download image %s: %s", image_url, exc)
                continue

            content = response.content
            if not content:
                logger.warning("Downloaded image %s was empty", image_url)
                continue

            try:
                def _inspect_image(
                    payload: bytes,
                ) -> tuple[int, int, SimilarityImage | None]:
                    with PilImage.open(BytesIO(payload)) as img:
                        width, height = img.size
                        width_i = int(width)
                        height_i = int(height)
                        if (
                            width_i < IMPORT_IMAGE_MIN_DIMENSION
                            or height_i < IMPORT_IMAGE_MIN_DIMENSION
                        ):
                            return width_i, height_i, None
                        return width_i, height_i, build_similarity_image(img)

                width, height, similarity = await asyncio.to_thread(
                    _inspect_image,
                    content,
                )
                if similarity is None:
                    logger.warning(
                        "Skipping small image %s (%sx%s)",
                        image_url,
                        width,
                        height,
                    )
                    continue
            except Exception as exc:
                logger.warning("Skipping unreadable image %s: %s", image_url, exc)
                continue

            skip_thumbnail = False
            to_remove: list[_ImportedSimilarity] = []
            for candidate in imported_similarities:
                score = compute_similarity_score(candidate.similarity, similarity)
                if score is None or score < IMPORT_IMAGE_SSIM_THRESHOLD:
                    continue
                if similarity.pixels <= candidate.similarity.pixels:
                    logger.info(
                        "Skipping thumbnail image %s (ssim %.3f)",
                        image_url,
                        score,
                    )
                    skip_thumbnail = True
                    break
                to_remove.append(candidate)

            if skip_thumbnail:
                continue

            for entry in to_remove:
                await db.delete(entry.image)
                delete_file(entry.filename, project.id)
                imported_similarities.remove(entry)

            parsed = urlparse(image_url)
            original_name = Path(parsed.path).name or f"imported-image-{index}.jpg"

            filename, original_filename = save_bytes(
                content,
                original_name,
                project.id,
                subdir="projects",
            )
            file_path = config.MEDIA_ROOT / "projects" / str(project.id) / filename
            try:
                await create_thumbnail(file_path, project.id)
            except Exception as exc:
                delete_file(filename, project.id)
                logger.warning(
                    "Failed to create thumbnail for %s: %s",
                    original_filename,
                    exc,
                )
                continue
            image = Image(
                filename=filename,
                original_filename=original_filename,
                image_type=ImageType.PHOTO.value,
                alt_text=project.name,
                is_title_image=False,
                project_id=project.id,
            )
            db.add(image)
            imported_similarities.append(
                _ImportedSimilarity(
                    similarity=similarity,
                    image=image,
                    filename=filename,
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
