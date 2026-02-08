"""Attachment storage helpers for projects."""

from __future__ import annotations

import asyncio
import logging
from dataclasses import dataclass
from pathlib import Path

import anyio
from fastapi import UploadFile

from stricknani.config import config
from stricknani.services.images import get_image_dimensions
from stricknani.utils.files import (
    create_pdf_thumbnail,
    create_thumbnail,
    get_thumbnail_url,
    save_bytes,
)

logger = logging.getLogger("stricknani.attachments")


@dataclass(frozen=True)
class StoredAttachment:
    filename: str
    original_filename: str
    content_type: str
    size_bytes: int
    thumbnail_url: str | None
    width: int | None
    height: int | None


async def store_project_attachment(
    project_id: int,
    upload_file: UploadFile,
) -> StoredAttachment:
    content = await upload_file.read()
    size_bytes = len(content)

    filename, original_filename = await anyio.to_thread.run_sync(
        save_bytes,
        content,
        upload_file.filename or "file",
        project_id,
    )

    content_type = upload_file.content_type or "application/octet-stream"
    thumb_path = (
        config.MEDIA_ROOT
        / "thumbnails"
        / "projects"
        / str(project_id)
        / f"thumb_{Path(filename).stem}.jpg"
    )
    source_path = config.MEDIA_ROOT / "projects" / str(project_id) / filename

    thumbnail_url: str | None = None
    width: int | None = None
    height: int | None = None

    if content_type.startswith("image/"):
        width, height = await get_image_dimensions(filename, project_id)
        try:
            await create_thumbnail(source_path, project_id, subdir="projects")
            if thumb_path.exists():
                thumbnail_url = get_thumbnail_url(
                    filename,
                    project_id,
                    subdir="projects",
                )
        except Exception:
            logger.info("Could not create attachment thumbnail for %s", filename)
    elif content_type == "application/pdf":
        try:
            await asyncio.to_thread(
                create_pdf_thumbnail,
                source_path,
                project_id,
                "projects",
            )
            if thumb_path.exists():
                thumbnail_url = get_thumbnail_url(
                    filename,
                    project_id,
                    subdir="projects",
                )
        except Exception:
            logger.info("Could not create PDF thumbnail for %s", filename)

    return StoredAttachment(
        filename=filename,
        original_filename=original_filename,
        content_type=content_type,
        size_bytes=size_bytes,
        thumbnail_url=thumbnail_url,
        width=width,
        height=height,
    )
