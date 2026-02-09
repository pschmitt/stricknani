"""Import images (projects, steps, yarns) from remote URLs."""

from __future__ import annotations

import logging
import re
from collections.abc import Sequence
from dataclasses import dataclass
from io import BytesIO
from pathlib import Path

import anyio
import httpx
from PIL import Image as PilImage
from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from stricknani.config import config
from stricknani.models import Image, ImageType, Project, Step, YarnImage
from stricknani.models import Yarn as YarnModel
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
    image: Image
    filename: str
    is_title_image: bool


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
    if not image_urls:
        return 0

    imported = 0
    existing_checksums = await load_existing_image_checksums(db, project.id)
    seen_checksums: set[str] = set()
    imported_similarities: list[ImportedSimilarity] = []

    existing_title_images = await db.execute(
        select(func.count())
        .select_from(Image)
        .where(Image.project_id == project.id, Image.is_title_image.is_(True))
    )
    title_available = existing_title_images.scalar_one() == 0

    headers = dict(IMPORT_IMAGE_HEADERS)
    if project.link:
        headers["Referer"] = project.link

    async with httpx.AsyncClient(
        timeout=IMPORT_IMAGE_TIMEOUT,
        follow_redirects=True,
        headers=headers,
    ) as client:
        for image_url in image_urls:
            if imported >= IMPORT_IMAGE_MAX_COUNT:
                break

            # Skip if it's a token that was already saved
            if permanently_saved_tokens and "/media/imports/projects/" in image_url:
                match = re.search(r"/([a-f0-9]{32})\.[a-z]{3,4}$", image_url)
                if match and match.group(1) in permanently_saved_tokens:
                    continue

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

            content_length = response.headers.get("content-length")
            if content_length:
                try:
                    if int(content_length) > IMPORT_IMAGE_MAX_BYTES:
                        logger.info("Skipping large image %s", image_url)
                        continue
                except ValueError:
                    pass

            if not response.content:
                logger.info("Skipping empty image response: %s", image_url)
                continue

            if len(response.content) > IMPORT_IMAGE_MAX_BYTES:
                logger.info("Skipping large image %s", image_url)
                continue

            checksum = compute_checksum(response.content)
            if checksum in existing_checksums or checksum in seen_checksums:
                logger.info("Skipping duplicate image %s", image_url)
                if (
                    title_url
                    and image_url == title_url
                    and title_available
                    and checksum in existing_checksums
                ):
                    existing_checksums[checksum].is_title_image = True
                    title_available = False
                continue

            try:

                def _inspect(
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
                    _inspect,
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

            removed_title = any(entry.is_title_image for entry in to_remove)
            for entry in to_remove:
                await db.delete(entry.image)
                if deferred_deletions is not None:
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

            if removed_title:
                is_title = True

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
            seen_checksums.add(checksum)

            if permanently_saved_tokens and "/media/imports/projects/" in image_url:
                match = re.search(r"/([a-f0-9]{32})\.[a-z]{3,4}$", image_url)
                if match:
                    permanently_saved_tokens.add(match.group(1))

            imported_similarities.append(
                ImportedSimilarity(
                    similarity=similarity,
                    image=image,
                    filename=filename,
                    is_title_image=is_title,
                )
            )
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
    if not image_urls:
        return 0

    imported = 0
    existing_checksums = await load_existing_image_checksums(
        db,
        step.project_id,
        step_id=step.id,
    )
    seen_checksums: set[str] = set()
    imported_similarities: list[ImportedSimilarity] = []

    headers = dict(IMPORT_IMAGE_HEADERS)
    async with httpx.AsyncClient(
        timeout=IMPORT_IMAGE_TIMEOUT,
        follow_redirects=True,
        headers=headers,
    ) as client:
        for image_url in image_urls:
            if imported >= IMPORT_IMAGE_MAX_COUNT:
                break

            # Skip if it's a token that was already saved
            if permanently_saved_tokens and "/media/imports/projects/" in image_url:
                match = re.search(r"/([a-f0-9]{32})\.[a-z]{3,4}$", image_url)
                if match and match.group(1) in permanently_saved_tokens:
                    continue

            if not is_valid_import_url(image_url):
                continue

            try:
                response = await client.get(image_url)
                response.raise_for_status()
            except httpx.HTTPError as exc:
                logger.warning("Failed to download step image %s: %s", image_url, exc)
                continue

            content_type = response.headers.get("content-type")
            if not is_allowed_import_image(content_type, image_url):
                continue

            content_length = response.headers.get("content-length")
            if content_length:
                try:
                    if int(content_length) > IMPORT_IMAGE_MAX_BYTES:
                        continue
                except ValueError:
                    pass

            if not response.content or len(response.content) > IMPORT_IMAGE_MAX_BYTES:
                continue

            checksum = compute_checksum(response.content)
            if checksum in existing_checksums or checksum in seen_checksums:
                logger.info("Skipping duplicate step image %s", image_url)
                continue

            try:

                def _inspect(
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
                    _inspect,
                    response.content,
                )
                if similarity is None:
                    logger.info(
                        "Skipping small step image %s (%sx%s)",
                        image_url,
                        width,
                        height,
                    )
                    continue
            except Exception as exc:
                logger.info("Skipping unreadable step image %s: %s", image_url, exc)
                continue

            skip_thumbnail = False
            to_remove: list[ImportedSimilarity] = []
            for candidate in imported_similarities:
                score = compute_similarity_score(candidate.similarity, similarity)
                if score is None or score < IMPORT_IMAGE_SSIM_THRESHOLD:
                    continue
                if similarity.pixels <= candidate.similarity.pixels:
                    logger.info(
                        "Skipping thumbnail step image %s (ssim %.3f)",
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
                if deferred_deletions is not None:
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
                    step.project_id,
                )
                file_path = (
                    config.MEDIA_ROOT / "projects" / str(step.project_id) / filename
                )
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
            seen_checksums.add(checksum)

            if permanently_saved_tokens and "/media/imports/projects/" in image_url:
                match = re.search(r"/([a-f0-9]{32})\.[a-z]{3,4}$", image_url)
                if match:
                    permanently_saved_tokens.add(match.group(1))

            imported_similarities.append(
                ImportedSimilarity(
                    similarity=similarity,
                    image=image,
                    filename=filename,
                    is_title_image=False,
                )
            )

    return imported


async def import_yarn_images_from_urls(
    db: AsyncSession,
    yarn: YarnModel,
    image_urls: Sequence[str],
) -> int:
    """Download and attach imported images to a yarn."""
    if not image_urls:
        return 0

    imported = 0
    seen_checksums: set[str] = set()
    imported_similarities: list[SimilarityImage] = []

    headers = dict(IMPORT_IMAGE_HEADERS)
    if yarn.link:
        headers["Referer"] = yarn.link

    async with httpx.AsyncClient(
        timeout=IMPORT_IMAGE_TIMEOUT,
        follow_redirects=True,
        headers=headers,
    ) as client:
        for image_url in image_urls:
            if imported >= 5:
                break
            if not is_valid_import_url(image_url):
                continue

            try:
                response = await client.get(image_url)
                response.raise_for_status()
            except httpx.HTTPError as exc:
                logger.warning("Failed to download yarn image %s: %s", image_url, exc)
                continue

            content_type = response.headers.get("content-type")
            if not is_allowed_import_image(content_type, image_url):
                continue

            if not response.content or len(response.content) > IMPORT_IMAGE_MAX_BYTES:
                continue

            checksum = compute_checksum(response.content)
            if checksum in seen_checksums:
                continue

            try:

                def _inspect(content: bytes) -> SimilarityImage | None:
                    with PilImage.open(BytesIO(content)) as img:
                        width, height = img.size
                        if (
                            int(width) < IMPORT_IMAGE_MIN_DIMENSION
                            or int(height) < IMPORT_IMAGE_MIN_DIMENSION
                        ):
                            return None
                        return build_similarity_image(img)

                similarity = await anyio.to_thread.run_sync(_inspect, response.content)
                if similarity is None:
                    continue
            except Exception:
                continue

            skip_thumbnail = False
            for candidate_sim in imported_similarities:
                score = compute_similarity_score(candidate_sim, similarity)
                if score is not None and score >= IMPORT_IMAGE_SSIM_THRESHOLD:
                    skip_thumbnail = True
                    break

            if skip_thumbnail:
                continue

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
                logger.warning("Failed to store yarn image %s: %s", image_url, exc)
                continue

            photo = YarnImage(
                filename=filename,
                original_filename=original_filename,
                alt_text=yarn.name or original_filename,
                yarn_id=yarn.id,
                is_primary=(imported == 0),
            )
            db.add(photo)
            imported += 1
            seen_checksums.add(checksum)
            imported_similarities.append(similarity)

    return imported
