"""Import images (projects, steps, yarns) from remote URLs."""

from __future__ import annotations

import logging
import re
from collections.abc import Sequence
from pathlib import Path

import anyio
from PIL import Image as PilImage
from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from stricknani.config import config
from stricknani.importing.images import IMPORT_IMAGE_MAX_COUNT, ImageDownloader
from stricknani.models import Image, ImageType, Project, Step
from stricknani.utils.files import (
    build_import_filename,
    compute_file_checksum,
    create_thumbnail,
    delete_file,
    save_bytes,
)
from stricknani.utils.image_similarity import (
    SimilarityImage,
    build_similarity_image,
)

logger = logging.getLogger("stricknani.imports")


async def load_existing_image_checksums(
    db: AsyncSession,
    project_id: int,
    *,
    step_id: int | None = None,
) -> dict[str, Image]:
    """Return existing image checksums for a project or a specific step."""
    query = select(Image).where(Image.project_id == project_id)
    if step_id is None:
        query = query.where(Image.step_id.is_(None))
    else:
        query = query.where(Image.step_id == step_id)

    result = await db.execute(query)
    images = result.scalars().all()
    checksums: dict[str, Image] = {}
    for image in images:
        file_path = config.MEDIA_ROOT / "projects" / str(project_id) / image.filename
        checksum = await anyio.to_thread.run_sync(compute_file_checksum, file_path)
        if checksum:
            checksums.setdefault(checksum, image)
    return checksums


async def load_existing_image_similarities(
    db: AsyncSession,
    project_id: int,
    *,
    step_id: int | None = None,
    limit: int = 25,
) -> list[SimilarityImage]:
    """Return existing image similarity payloads for a project or a specific step."""
    query = select(Image).where(Image.project_id == project_id)
    if step_id is None:
        query = query.where(Image.step_id.is_(None))
    else:
        query = query.where(Image.step_id == step_id)

    result = await db.execute(query)
    images = result.scalars().all()
    similarities: list[SimilarityImage] = []

    def _build(path: Path) -> SimilarityImage | None:
        try:
            with PilImage.open(path) as img:
                return build_similarity_image(img)
        except Exception:
            return None

    for image in images[:limit]:
        file_path = config.MEDIA_ROOT / "projects" / str(project_id) / image.filename
        similarity = await anyio.to_thread.run_sync(_build, file_path)
        if similarity is not None:
            similarities.append(similarity)

    return similarities


async def import_project_images_from_urls(
    db: AsyncSession,
    project: Project,
    image_urls: Sequence[str],
    *,
    title_url: str | None = None,
    permanently_saved_tokens: set[str] | None = None,
    deferred_deletions: list[str] | None = None,
) -> int:
    """Download and attach imported images to a project."""
    _ = deferred_deletions

    if not image_urls:
        return 0

    existing_checksums = set(
        (await load_existing_image_checksums(db, project.id)).keys()
    )

    existing_title_images = await db.execute(
        select(func.count())
        .select_from(Image)
        .where(Image.project_id == project.id, Image.is_title_image.is_(True))
    )
    title_available = existing_title_images.scalar_one() == 0

    filtered_urls: list[str] = []
    for image_url in image_urls:
        if len(filtered_urls) >= IMPORT_IMAGE_MAX_COUNT:
            break
        if permanently_saved_tokens and "/media/imports/projects/" in image_url:
            match = re.search(r"/([a-f0-9]{32})\.[a-z]{3,4}$", image_url)
            if match and match.group(1) in permanently_saved_tokens:
                continue
        filtered_urls.append(image_url)

    downloader = ImageDownloader(referer=project.link)
    download_result = await downloader.download_images(
        filtered_urls,
        existing_checksums=existing_checksums,
        limit=IMPORT_IMAGE_MAX_COUNT,
    )

    imported = 0
    for downloaded in download_result.images:
        image_url = downloaded.url
        content_type = downloaded.content_type
        original_filename = build_import_filename(image_url, content_type)
        filename = ""
        try:
            filename, original_filename = await anyio.to_thread.run_sync(
                save_bytes,
                downloaded.content,
                original_filename,
                project.id,
            )
            file_path = config.MEDIA_ROOT / "projects" / str(project.id) / filename
            await create_thumbnail(file_path, project.id)
        except Exception as exc:
            if filename:
                delete_file(filename, project.id)
            logger.warning("Failed to store image %s: %s", image_url, exc)
            continue

        alt_text = (
            f"{project.name} (imported image {imported + 1})"
            if project.name
            else original_filename
        )
        if title_url:
            is_title = image_url == title_url
        else:
            is_title = title_available

        image = Image(
            filename=filename,
            original_filename=original_filename,
            image_type=ImageType.PHOTO.value,
            alt_text=alt_text,
            is_title_image=is_title,
            project_id=project.id,
        )
        db.add(image)
        imported += 1

        if permanently_saved_tokens and "/media/imports/projects/" in image_url:
            match = re.search(r"/([a-f0-9]{32})\.[a-z]{3,4}$", image_url)
            if match:
                permanently_saved_tokens.add(match.group(1))

        if is_title:
            title_available = False

    return imported


async def import_step_images_from_urls(
    db: AsyncSession,
    step: Step,
    image_urls: Sequence[str],
    *,
    permanently_saved_tokens: set[str] | None = None,
    deferred_deletions: list[str] | None = None,
) -> int:
    """Download and attach imported images to a step."""
    _ = deferred_deletions

    if not image_urls:
        return 0

    existing_checksums = set(
        (
            await load_existing_image_checksums(
                db,
                step.project_id,
                step_id=step.id,
            )
        ).keys()
    )

    filtered_urls: list[str] = []
    for image_url in image_urls:
        if len(filtered_urls) >= IMPORT_IMAGE_MAX_COUNT:
            break
        if permanently_saved_tokens and "/media/imports/projects/" in image_url:
            match = re.search(r"/([a-f0-9]{32})\.[a-z]{3,4}$", image_url)
            if match and match.group(1) in permanently_saved_tokens:
                continue
        filtered_urls.append(image_url)

    downloader = ImageDownloader()
    download_result = await downloader.download_images(
        filtered_urls,
        existing_checksums=existing_checksums,
        limit=IMPORT_IMAGE_MAX_COUNT,
    )

    imported = 0
    for downloaded in download_result.images:
        image_url = downloaded.url
        content_type = downloaded.content_type
        original_filename = build_import_filename(image_url, content_type)
        filename = ""
        try:
            filename, original_filename = await anyio.to_thread.run_sync(
                save_bytes,
                downloaded.content,
                original_filename,
                step.project_id,
            )
            file_path = config.MEDIA_ROOT / "projects" / str(step.project_id) / filename
            await create_thumbnail(file_path, step.project_id)
        except Exception as exc:
            if filename:
                delete_file(filename, step.project_id)
            logger.warning("Failed to store step image %s: %s", image_url, exc)
            continue

        image = Image(
            filename=filename,
            original_filename=original_filename,
            image_type=ImageType.PHOTO.value,
            alt_text=original_filename,
            is_title_image=False,
            project_id=step.project_id,
            step_id=step.id,
        )
        db.add(image)
        imported += 1

        if permanently_saved_tokens and "/media/imports/projects/" in image_url:
            match = re.search(r"/([a-f0-9]{32})\.[a-z]{3,4}$", image_url)
            if match:
                permanently_saved_tokens.add(match.group(1))

    return imported
