"""Attachment storage helpers for projects."""

from __future__ import annotations

import asyncio
import json
import logging
import uuid
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
    return await store_project_attachment_bytes(
        project_id,
        content=content,
        original_filename=upload_file.filename or "file",
        content_type=upload_file.content_type,
    )


async def store_project_attachment_bytes(
    project_id: int,
    *,
    content: bytes,
    original_filename: str,
    content_type: str | None,
) -> StoredAttachment:
    size_bytes = len(content)

    filename, original_filename = await anyio.to_thread.run_sync(
        save_bytes,
        content,
        original_filename,
        project_id,
    )

    resolved_content_type = content_type or "application/octet-stream"
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

    if resolved_content_type.startswith("image/"):
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
    elif resolved_content_type == "application/pdf":
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
        content_type=resolved_content_type,
        size_bytes=size_bytes,
        thumbnail_url=thumbnail_url,
        width=width,
        height=height,
    )


async def store_pending_project_import_attachment_bytes(
    user_id: int,
    *,
    content: bytes,
    original_filename: str,
    content_type: str | None,
) -> str:
    """Store an uploaded source file temporarily for later attachment on save.

    This is used for imports that happen before the project exists (e.g. on
    /projects/new). The client stores the returned token and submits it with the
    project form; on save we will move/copy it into the project attachments.
    """
    token = uuid.uuid4().hex
    ext = Path(original_filename).suffix.lower() or ".bin"
    pending_dir = config.MEDIA_ROOT / "imports" / "projects" / str(user_id)
    pending_dir.mkdir(parents=True, exist_ok=True)

    stored_filename = f"{token}{ext}"
    stored_path = pending_dir / stored_filename
    meta_path = pending_dir / f"{token}.json"
    resolved_content_type = content_type or "application/octet-stream"

    def _write() -> None:
        stored_path.write_bytes(content)
        meta_path.write_text(
            json.dumps(
                {
                    "token": token,
                    "stored_filename": stored_filename,
                    "original_filename": original_filename,
                    "content_type": resolved_content_type,
                    "size_bytes": len(content),
                }
            ),
            encoding="utf-8",
        )

    await anyio.to_thread.run_sync(_write)
    return token


async def consume_pending_project_import_attachment(
    user_id: int,
    *,
    token: str,
) -> tuple[bytes, str, str]:
    """Load and delete a pending import attachment for a user."""
    pending_dir = config.MEDIA_ROOT / "imports" / "projects" / str(user_id)
    meta_path = pending_dir / f"{token}.json"
    if not meta_path.exists():
        raise FileNotFoundError("pending attachment metadata not found")

    def _read_and_delete() -> tuple[bytes, str, str]:
        meta = json.loads(meta_path.read_text(encoding="utf-8"))
        stored_filename = str(meta["stored_filename"])
        original_filename = str(meta["original_filename"])
        content_type = str(meta["content_type"])

        stored_path = pending_dir / stored_filename
        content = stored_path.read_bytes()

        try:
            stored_path.unlink(missing_ok=True)
        finally:
            meta_path.unlink(missing_ok=True)

        return content, original_filename, content_type

    return await anyio.to_thread.run_sync(_read_and_delete)
