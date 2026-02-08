"""Project image helpers (upload, thumbnails, metadata)."""

from __future__ import annotations

import asyncio

from fastapi import UploadFile
from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from stricknani.config import config
from stricknani.models import Image, ImageType
from stricknani.services.images import get_image_dimensions
from stricknani.utils.files import (
    create_thumbnail,
    get_file_url,
    get_thumbnail_url,
    save_uploaded_file,
)
from stricknani.utils.ocr import is_ocr_available, precompute_ocr_for_media_file


async def upload_title_image(
    db: AsyncSession,
    *,
    project_id: int,
    file: UploadFile,
    alt_text: str = "",
) -> dict[str, object]:
    filename, original_filename = await save_uploaded_file(file, project_id)
    file_path = config.MEDIA_ROOT / "projects" / str(project_id) / filename
    await create_thumbnail(file_path, project_id)
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
        project_id=project_id,
    )
    db.add(image)
    await db.commit()
    await db.refresh(image)

    width, height = await get_image_dimensions(filename, project_id)

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
    filename, original_filename = await save_uploaded_file(file, project_id)
    file_path = config.MEDIA_ROOT / "projects" / str(project_id) / filename
    await create_thumbnail(file_path, project_id)
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
        project_id=project_id,
    )
    db.add(image)
    await db.commit()
    await db.refresh(image)

    width, height = await get_image_dimensions(filename, project_id)

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
    filename, original_filename = await save_uploaded_file(file, project_id)
    file_path = config.MEDIA_ROOT / "projects" / str(project_id) / filename
    await create_thumbnail(file_path, project_id)
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
        project_id=project_id,
        step_id=step_id,
    )
    db.add(image)
    await db.commit()
    await db.refresh(image)

    width, height = await get_image_dimensions(filename, project_id)

    return {
        "id": image.id,
        "url": get_file_url(filename, project_id),
        "thumbnail_url": get_thumbnail_url(filename, project_id),
        "alt_text": image.alt_text,
        "width": width,
        "height": height,
    }
