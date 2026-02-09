"""Yarn image import helpers."""

from __future__ import annotations

import logging
from collections.abc import Sequence
from dataclasses import dataclass
from io import BytesIO

import anyio
import httpx
from PIL import Image as PilImage
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from stricknani.config import config
from stricknani.models import Yarn, YarnImage
from stricknani.utils.files import (
    build_import_filename,
    compute_checksum,
    compute_file_checksum,
    create_thumbnail,
    delete_file,
    save_bytes,
)
from stricknani.utils.image_similarity import (
    SimilarityImage,
    build_similarity_image,
    compute_similarity_score,
)
from stricknani.utils.importer import (
    IMPORT_IMAGE_HEADERS,
    IMPORT_IMAGE_MAX_BYTES,
    IMPORT_IMAGE_MAX_COUNT,
    IMPORT_IMAGE_MIN_DIMENSION,
    IMPORT_IMAGE_SSIM_THRESHOLD,
    IMPORT_IMAGE_TIMEOUT,
    is_allowed_import_image,
    is_valid_import_url,
)

logger = logging.getLogger("stricknani.imports")


@dataclass
class ImportedSimilarity:
    similarity: SimilarityImage
    image: YarnImage
    filename: str
    is_primary: bool


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
    if not image_urls:
        return 0

    imported = 0
    existing_checksums = await load_existing_yarn_checksums(db, yarn.id)
    seen_checksums: set[str] = set()
    imported_similarities: list[ImportedSimilarity] = []
    deferred_deletions = deferred_deletions or []

    headers = dict(IMPORT_IMAGE_HEADERS)
    if yarn.link:
        headers["Referer"] = yarn.link

    async with httpx.AsyncClient(
        timeout=IMPORT_IMAGE_TIMEOUT,
        follow_redirects=True,
        headers=headers,
    ) as client:
        for image_url in image_urls:
            if imported >= IMPORT_IMAGE_MAX_COUNT:
                break
            if not is_valid_import_url(image_url):
                logger.info("Skipping invalid image URL: %s", image_url)
                continue

            try:
                response = await client.get(image_url)
                response.raise_for_status()
            except httpx.HTTPError as exc:
                logger.warning("Failed to download image %s: %s", image_url, exc)
                continue

            content_type = response.headers.get("content-type")
            if not is_allowed_import_image(content_type, image_url):
                logger.info("Skipping non-image URL: %s", image_url)
                continue

            if not response.content or len(response.content) > IMPORT_IMAGE_MAX_BYTES:
                logger.info("Skipping empty or large image %s", image_url)
                continue

            checksum = compute_checksum(response.content)
            if checksum in existing_checksums or checksum in seen_checksums:
                logger.info("Skipping duplicate image %s", image_url)
                if primary_url and image_url == primary_url:
                    existing = existing_checksums.get(checksum)
                    if existing and not existing.is_primary:
                        existing.is_primary = True
                continue

            try:

                def _inspect_image(
                    content: bytes,
                ) -> tuple[int, int, SimilarityImage | None]:
                    with PilImage.open(BytesIO(content)) as img:
                        width, height = img.size
                        width_i = int(width)
                        height_i = int(height)
                        if (
                            width_i < IMPORT_IMAGE_MIN_DIMENSION
                            or height_i < IMPORT_IMAGE_MIN_DIMENSION
                        ):
                            return width_i, height_i, None
                        return width_i, height_i, build_similarity_image(img)

                width, height, similarity = await anyio.to_thread.run_sync(
                    _inspect_image,
                    response.content,
                )
                if similarity is None:
                    logger.info(
                        "Skipping small image %s (%sx%s)",
                        image_url,
                        width,
                        height,
                    )
                    continue
            except Exception as exc:
                logger.info("Skipping unreadable image %s: %s", image_url, exc)
                continue

            skip_thumbnail = False
            to_remove: list[ImportedSimilarity] = []
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

            removed_primary = any(entry.is_primary for entry in to_remove)
            for entry in to_remove:
                await db.delete(entry.image)
                deferred_deletions.append(entry.filename)
                imported_similarities.remove(entry)
                imported = max(0, imported - 1)

            original_filename = build_import_filename(image_url, content_type)
            filename = ""
            try:
                filename, original_filename = await anyio.to_thread.run_sync(
                    save_bytes,
                    response.content,
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
                is_primary = imported == 0 and not yarn.photos

            if removed_primary:
                is_primary = True

            photo = YarnImage(
                filename=filename,
                original_filename=original_filename,
                alt_text=yarn.name or original_filename,
                yarn_id=yarn.id,
                is_primary=is_primary,
            )
            db.add(photo)
            imported += 1
            seen_checksums.add(checksum)
            imported_similarities.append(
                ImportedSimilarity(
                    similarity=similarity,
                    image=photo,
                    filename=filename,
                    is_primary=is_primary,
                )
            )

    return imported
