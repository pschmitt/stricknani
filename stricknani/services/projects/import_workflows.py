"""Shared project import workflows used by create and update handlers."""

from __future__ import annotations

import io
import json
import re
from collections.abc import Sequence
from typing import Any

from fastapi import UploadFile
from sqlalchemy import update
from sqlalchemy.ext.asyncio import AsyncSession
from starlette.datastructures import Headers

from stricknani.models import Attachment, Image, Project, Step, User
from stricknani.services.projects.attachments import (
    consume_pending_project_import_attachment,
    store_project_attachment_bytes,
)
from stricknani.services.projects.images import upload_step_image, upload_title_image
from stricknani.services.projects.import_images import (
    import_project_images_from_urls,
    import_step_images_from_urls,
)

PENDING_IMPORT_TOKEN_RE = re.compile(r"/([a-f0-9]{32})\.[a-z]{3,4}$")


def parse_yarn_ids(raw: str | None) -> list[int]:
    """Parse comma-separated yarn IDs."""
    if not raw:
        return []
    try:
        return [int(item.strip()) for item in raw.split(",") if item.strip()]
    except ValueError:
        return []


def parse_yarn_details(raw: str | None) -> list[dict[str, Any]] | None:
    """Parse structured yarn details payload."""
    if not raw:
        return None
    try:
        parsed = json.loads(raw)
    except json.JSONDecodeError:
        return None
    return parsed if isinstance(parsed, list) else None


def extract_pending_token(url: str) -> str | None:
    """Extract pending import token from import-media URL."""
    match = PENDING_IMPORT_TOKEN_RE.search(url)
    if not match:
        return None
    return match.group(1)


class PendingImportTokenResolver:
    """Caches pending import token lookups to support multi-use within a request."""

    def __init__(self, user: User) -> None:
        self._user = user
        self._cache: dict[str, tuple[bytes, str, str]] = {}

    async def get(self, token: str) -> tuple[bytes, str, str]:
        if token in self._cache:
            return self._cache[token]
        data = await consume_pending_project_import_attachment(
            self._user.id,
            token=token,
        )
        self._cache[token] = data
        return data


async def import_step_images_from_mixed_sources(
    db: AsyncSession,
    *,
    project: Project,
    step: Step,
    step_images: Sequence[str],
    resolver: PendingImportTokenResolver,
    permanently_saved_tokens: set[str],
    deferred_deletions: list[str],
) -> None:
    """Import step images from regular URLs and pending-token URLs."""
    regular_urls: list[str] = []
    for url in step_images:
        if "/media/imports/projects/" not in url:
            regular_urls.append(url)
            continue

        token = extract_pending_token(url)
        if not token:
            continue
        try:
            pending_bytes, original_filename, content_type = await resolver.get(token)
        except FileNotFoundError:
            continue

        mock_file = UploadFile(
            filename=original_filename,
            file=io.BytesIO(pending_bytes),
            headers=Headers({"content-type": content_type}),
        )
        await upload_step_image(
            db,
            step_id=step.id,
            project_id=project.id,
            file=mock_file,
        )
        permanently_saved_tokens.add(token)

    if regular_urls:
        await import_step_images_from_urls(
            db,
            step,
            regular_urls,
            permanently_saved_tokens=permanently_saved_tokens,
            deferred_deletions=deferred_deletions,
        )


async def import_project_images_from_mixed_sources(
    db: AsyncSession,
    *,
    project: Project,
    image_urls: Sequence[str],
    title_url: str | None,
    resolver: PendingImportTokenResolver,
    permanently_saved_tokens: set[str],
    deferred_deletions: list[str],
) -> None:
    """Import project gallery images from regular URLs and pending-token URLs."""
    regular_urls: list[str] = []
    for url in image_urls:
        if "/media/imports/projects/" not in url:
            regular_urls.append(url)
            continue

        token = extract_pending_token(url)
        if not token or token in permanently_saved_tokens:
            continue
        try:
            pending_bytes, original_filename, content_type = await resolver.get(token)
        except FileNotFoundError:
            continue

        # Keep rendered PDF assets as attachments, not gallery entries.
        if original_filename.startswith(("pdf_image_", "pdf_page_")):
            continue

        mock_file = UploadFile(
            filename=original_filename,
            file=io.BytesIO(pending_bytes),
            headers=Headers({"content-type": content_type}),
        )
        is_title = url == title_url
        upload_result = await upload_title_image(
            db,
            project_id=project.id,
            file=mock_file,
            alt_text=original_filename,
        )
        permanently_saved_tokens.add(token)

        if is_title and "id" in upload_result:
            await db.execute(
                update(Image)
                .where(Image.project_id == project.id)
                .values(is_title_image=False)
            )
            await db.execute(
                update(Image)
                .where(Image.id == int(str(upload_result["id"])))
                .values(is_title_image=True)
            )

    if regular_urls:
        await import_project_images_from_urls(
            db,
            project,
            regular_urls,
            title_url=title_url,
            permanently_saved_tokens=permanently_saved_tokens,
            deferred_deletions=deferred_deletions,
        )


async def persist_remaining_import_tokens(
    db: AsyncSession,
    *,
    project: Project,
    raw_tokens: str | None,
    resolver: PendingImportTokenResolver,
    permanently_saved_tokens: set[str],
) -> None:
    """Persist remaining pending import tokens as attachments or gallery images."""
    if not raw_tokens:
        return

    try:
        parsed = json.loads(raw_tokens)
    except json.JSONDecodeError:
        parsed = []

    tokens = [token for token in parsed if isinstance(token, str) and len(token) == 32]
    for token in tokens:
        if token in permanently_saved_tokens:
            continue

        try:
            pending_bytes, original_filename, content_type = await resolver.get(token)
        except FileNotFoundError:
            continue

        is_pdf_asset = original_filename.startswith("pdf_page_")
        if is_pdf_asset or not content_type or not content_type.startswith("image/"):
            stored = await store_project_attachment_bytes(
                project.id,
                content=pending_bytes,
                original_filename=original_filename,
                content_type=content_type,
            )
            db.add(
                Attachment(
                    filename=stored.filename,
                    original_filename=stored.original_filename,
                    content_type=stored.content_type,
                    size_bytes=stored.size_bytes,
                    project_id=project.id,
                )
            )
            permanently_saved_tokens.add(token)
            continue

        mock_file = UploadFile(
            filename=original_filename,
            file=io.BytesIO(pending_bytes),
            headers=Headers({"content-type": content_type}),
        )
        await upload_title_image(
            db,
            project_id=project.id,
            file=mock_file,
            alt_text=original_filename,
        )
        permanently_saved_tokens.add(token)


def parse_token_count(raw_tokens: str | None) -> int:
    """Count valid import tokens for audit metadata."""
    if not raw_tokens:
        return 0
    try:
        parsed = json.loads(raw_tokens)
    except json.JSONDecodeError:
        return 0
    if not isinstance(parsed, list):
        return 0
    return len([token for token in parsed if isinstance(token, str)])
