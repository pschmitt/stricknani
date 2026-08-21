"""Project image helpers (upload, thumbnails, metadata)."""

from __future__ import annotations

import asyncio

import anyio
from fastapi import HTTPException, UploadFile, status
from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from stricknani.config import config
from stricknani.models import Image, ImageType
from stricknani.services.images import get_image_dimensions
from stricknani.utils.files import (
    InvalidImageError,
    UploadTooLargeError,
    compute_checksum,
    create_thumbnail,
    get_file_url,
    get_thumbnail_url,
    read_upload_content,
    save_bytes,
    validate_image_upload,
)
from stricknani.utils.ocr import is_ocr_available, precompute_ocr_for_media_file


def _validate_image_or_400(content: bytes) -> str:
    """Validate uploaded image content and return its safe stored extension.

    Raises an HTTP 400 if the content is not a supported/valid image.
    """
    try:
        _content_type, extension = validate_image_upload(content)
    except InvalidImageError as exc:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=str(exc),
        ) from exc
    return extension


async def _read_image_content(file: UploadFile) -> bytes:
    """Read an image upload and translate the shared size cap to HTTP 413."""
    try:
        return await read_upload_content(file)
    except UploadTooLargeError as exc:
        raise HTTPException(
            status_code=status.HTTP_413_CONTENT_TOO_LARGE,
            detail="Uploaded file is too large",
        ) from exc


def _save_image_bytes(
    content: bytes, original_filename: str, project_id: int, extension: str
) -> tuple[str, str]:
    """Persist image bytes with a validated, safe stored extension.

    Wrapper so the keyword-only ``extension`` can be passed through
    ``anyio.to_thread.run_sync`` (which forwards positional args only).
    """
    return save_bytes(content, original_filename, project_id, extension=extension)


async def upload_title_image(
    db: AsyncSession,
    *,
    project_id: int,
    file: UploadFile,
    alt_text: str = "",
) -> dict[str, object]:
    content = await _read_image_content(file)
    safe_extension = _validate_image_or_400(content)
    checksum = compute_checksum(content)

    # Check for existing image with same checksum in this project
    from stricknani.services.projects.import_images import load_existing_image_checksums

    existing = await load_existing_image_checksums(db, project_id)
    if checksum in existing:
        image = existing[checksum]
        width, height = await get_image_dimensions(image.filename, project_id)
        return {
            "id": image.id,
            "url": get_file_url(image.filename, project_id),
            "thumbnail_url": get_thumbnail_url(image.filename, project_id),
            "alt_text": image.alt_text,
            "width": width,
            "height": height,
        }

    filename, original_filename = await anyio.to_thread.run_sync(
        _save_image_bytes,
        content,
        file.filename or "image.jpg",
        project_id,
        safe_extension,
    )
    file_path = config.MEDIA_ROOT / "projects" / str(project_id) / filename
    await create_thumbnail(file_path, project_id)
    width, height = await get_image_dimensions(filename, project_id)
    if is_ocr_available():
        asyncio.create_task(
            precompute_ocr_for_media_file(
                file_path=file_path,
                kind="projects",
                entity_id=project_id,
            )
        )

    count_result = await db.execute(
        select(func.count(Image.id)).where(
            Image.project_id == project_id,
            Image.is_title_image.is_(True),
            Image.is_stitch_sample.is_(False),
            Image.step_id.is_(None),
        )
    )
    has_title_image = (count_result.scalar() or 0) > 0

    image = Image(
        filename=filename,
        original_filename=original_filename,
        image_type=ImageType.PHOTO.value,
        alt_text=alt_text or original_filename,
        is_title_image=not has_title_image,
        width=width,
        height=height,
        project_id=project_id,
    )
    db.add(image)
    await db.flush()
    await db.refresh(image)

    return {
        "id": image.id,
        "url": get_file_url(filename, project_id),
        "thumbnail_url": get_thumbnail_url(filename, project_id),
        "alt_text": image.alt_text,
        "width": width,
        "height": height,
    }


async def upload_stitch_sample_image(
    db: AsyncSession,
    *,
    project_id: int,
    file: UploadFile,
    alt_text: str = "",
) -> dict[str, object]:
    content = await _read_image_content(file)
    safe_extension = _validate_image_or_400(content)
    checksum = compute_checksum(content)

    from stricknani.services.projects.import_images import load_existing_image_checksums

    existing = await load_existing_image_checksums(db, project_id)
    if checksum in existing:
        image = existing[checksum]
        width, height = await get_image_dimensions(image.filename, project_id)
        return {
            "id": image.id,
            "url": get_file_url(image.filename, project_id),
            "thumbnail_url": get_thumbnail_url(image.filename, project_id),
            "alt_text": image.alt_text,
            "width": width,
            "height": height,
        }

    filename, original_filename = await anyio.to_thread.run_sync(
        _save_image_bytes,
        content,
        file.filename or "image.jpg",
        project_id,
        safe_extension,
    )
    file_path = config.MEDIA_ROOT / "projects" / str(project_id) / filename
    await create_thumbnail(file_path, project_id)
    width, height = await get_image_dimensions(filename, project_id)
    if is_ocr_available():
        asyncio.create_task(
            precompute_ocr_for_media_file(
                file_path=file_path,
                kind="projects",
                entity_id=project_id,
            )
        )

    image = Image(
        filename=filename,
        original_filename=original_filename,
        image_type=ImageType.PHOTO.value,
        alt_text=alt_text or original_filename,
        is_title_image=False,
        is_stitch_sample=True,
        width=width,
        height=height,
        project_id=project_id,
    )
    db.add(image)
    await db.flush()
    await db.refresh(image)

    return {
        "id": image.id,
        "url": get_file_url(filename, project_id),
        "thumbnail_url": get_thumbnail_url(filename, project_id),
        "alt_text": image.alt_text,
        "width": width,
        "height": height,
    }


async def upload_step_image(
    db: AsyncSession,
    *,
    project_id: int,
    step_id: int,
    file: UploadFile,
    alt_text: str = "",
) -> dict[str, object]:
    content = await _read_image_content(file)
    safe_extension = _validate_image_or_400(content)
    checksum = compute_checksum(content)

    from stricknani.services.projects.import_images import load_existing_image_checksums

    existing = await load_existing_image_checksums(db, project_id, step_id=step_id)
    if checksum in existing:
        image = existing[checksum]
        width, height = await get_image_dimensions(image.filename, project_id)
        return {
            "id": image.id,
            "url": get_file_url(image.filename, project_id),
            "thumbnail_url": get_thumbnail_url(image.filename, project_id),
            "alt_text": image.alt_text,
            "width": width,
            "height": height,
        }

    filename, original_filename = await anyio.to_thread.run_sync(
        _save_image_bytes,
        content,
        file.filename or "image.jpg",
        project_id,
        safe_extension,
    )
    file_path = config.MEDIA_ROOT / "projects" / str(project_id) / filename
    await create_thumbnail(file_path, project_id)
    width, height = await get_image_dimensions(filename, project_id)
    if is_ocr_available():
        asyncio.create_task(
            precompute_ocr_for_media_file(
                file_path=file_path,
                kind="projects",
                entity_id=project_id,
            )
        )

    image = Image(
        filename=filename,
        original_filename=original_filename,
        image_type=ImageType.PHOTO.value,
        alt_text=alt_text or original_filename,
        is_title_image=False,
        width=width,
        height=height,
        project_id=project_id,
        step_id=step_id,
    )
    db.add(image)
    await db.flush()
    await db.refresh(image)

    return {
        "id": image.id,
        "url": get_file_url(filename, project_id),
        "thumbnail_url": get_thumbnail_url(filename, project_id),
        "alt_text": image.alt_text,
        "width": width,
        "height": height,
    }
