"""Yarn image import helpers."""

from __future__ import annotations

import logging
from collections.abc import Sequence

import anyio
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from stricknani.config import config
from stricknani.importing.images import IMPORT_IMAGE_MAX_COUNT, ImageDownloader
from stricknani.models import Yarn, YarnImage
from stricknani.utils.files import (
    build_import_filename,
    compute_file_checksum,
    create_thumbnail,
    delete_file,
    save_bytes,
)

logger = logging.getLogger("stricknani.imports")


async def load_existing_yarn_checksums(
    db: AsyncSession, yarn_id: int
) -> dict[str, YarnImage]:
    """Return existing image checksums for a yarn."""
    result = await db.execute(select(YarnImage).where(YarnImage.yarn_id == yarn_id))
    images = result.scalars().all()
    checksums: dict[str, YarnImage] = {}
    for image in images:
        file_path = config.MEDIA_ROOT / "yarns" / str(yarn_id) / image.filename
        checksum = compute_file_checksum(file_path)
        if checksum:
            checksums.setdefault(checksum, image)
    return checksums


async def import_yarn_images_from_urls(
    db: AsyncSession,
    yarn: Yarn,
    image_urls: Sequence[str],
    *,
    primary_url: str | None = None,
    deferred_deletions: list[str] | None = None,
) -> int:
    """Download and attach imported images to a yarn."""
    _ = deferred_deletions

    if not image_urls:
        return 0

    existing_checksums = set((await load_existing_yarn_checksums(db, yarn.id)).keys())
    downloader = ImageDownloader(referer=yarn.link)
    download_result = await downloader.download_images(
        image_urls,
        existing_checksums=existing_checksums,
        limit=min(IMPORT_IMAGE_MAX_COUNT, len(image_urls)),
    )

    imported = 0
    has_existing_primary = any(photo.is_primary for photo in yarn.photos)

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
                yarn.id,
                "yarns",
            )
            file_path = config.MEDIA_ROOT / "yarns" / str(yarn.id) / filename
            await create_thumbnail(file_path, yarn.id, subdir="yarns")
        except Exception as exc:
            if filename:
                delete_file(filename, yarn.id, subdir="yarns")
            logger.warning("Failed to store image %s: %s", image_url, exc)
            continue

        if primary_url:
            is_primary = image_url == primary_url
        else:
            is_primary = imported == 0 and not yarn.photos and not has_existing_primary

        photo = YarnImage(
            filename=filename,
            original_filename=original_filename,
            alt_text=yarn.name or original_filename,
            yarn_id=yarn.id,
            is_primary=is_primary,
        )
        db.add(photo)
        imported += 1

    return imported
