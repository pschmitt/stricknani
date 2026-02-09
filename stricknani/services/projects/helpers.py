"""Shared helper logic extracted from project routes."""

from __future__ import annotations

import re
from pathlib import Path
from typing import Any
from urllib.parse import urlparse

import httpx

from stricknani.config import config
from stricknani.utils.files import build_import_filename, compute_checksum
from stricknani.utils.importer import (
    IMPORT_IMAGE_HEADERS,
    IMPORT_IMAGE_MAX_BYTES,
    IMPORT_IMAGE_TIMEOUT,
)

_GARNSTUDIO_SYMBOL_URL_RE = re.compile(
    r"(https?://[^\s)\"'>]+?/drops/symbols/[^\s)\"'>]+)",
    re.IGNORECASE,
)


async def localize_garnstudio_symbol_images(
    project_id: int,
    description: str | None,
    *,
    referer: str | None = None,
) -> str | None:
    """Download inline Garnstudio symbol images and replace remote URLs."""
    if not description or "/drops/symbols/" not in description:
        return description

    urls = sorted(set(_GARNSTUDIO_SYMBOL_URL_RE.findall(description)))
    if not urls:
        return description

    symbol_dir = (
        config.MEDIA_ROOT
        / "projects"
        / str(project_id)
        / "inline"
        / "garnstudio-symbols"
    )
    symbol_dir.mkdir(parents=True, exist_ok=True)

    headers = dict(IMPORT_IMAGE_HEADERS)
    if referer:
        headers["Referer"] = referer

    replacements: dict[str, str] = {}
    async with httpx.AsyncClient(
        timeout=IMPORT_IMAGE_TIMEOUT,
        follow_redirects=True,
        headers=headers,
    ) as client:
        for url in urls:
            try:
                response = await client.get(url)
                response.raise_for_status()
            except httpx.HTTPError:
                continue

            if not response.content:
                continue

            if len(response.content) > min(IMPORT_IMAGE_MAX_BYTES, 512 * 1024):
                continue

            content_type = response.headers.get("content-type")
            if content_type and not content_type.lower().startswith("image/"):
                continue

            parsed = urlparse(url)
            ext = Path(parsed.path).suffix.lower()
            if not ext:
                ext = Path(build_import_filename(url, content_type)).suffix.lower()
            if not ext:
                ext = ".gif"

            checksum = compute_checksum(response.content)
            filename = f"{checksum[:16]}{ext}"
            target_path = symbol_dir / filename
            if not target_path.exists():
                target_path.write_bytes(response.content)

            replacements[url] = (
                f"/media/projects/{project_id}/inline/garnstudio-symbols/{filename}"
            )

    localized = description
    for src, dst in replacements.items():
        localized = localized.replace(src, dst)
    return localized


def build_ai_hints(data: dict[str, Any]) -> dict[str, Any]:
    """Prepare lightweight hints for the AI importer."""
    hints: dict[str, Any] = {}
    for key in [
        "title",
        "name",
        "needles",
        "yarn",
        "brand",
        "category",
        "notes",
        "link",
    ]:
        value = data.get(key)
        if value:
            hints[key] = value

    steps = data.get("steps")
    if isinstance(steps, list) and steps:
        hints["steps"] = steps[:5]

    image_urls = data.get("image_urls")
    if isinstance(image_urls, list) and image_urls:
        hints["image_urls"] = image_urls[:5]

    return hints


def dedupe_project_attachments(
    attachments: list[dict[str, Any]],
) -> list[dict[str, Any]]:
    """Deduplicate attachment dicts for display.

    Prefer `pdf_page_N.*` over `pdf_image_N.*` when both exist.
    """
    out: list[dict[str, Any]] = []
    index_by_key: dict[tuple[object, ...], int] = {}
    priority_by_key: dict[tuple[object, ...], int] = {}

    for att in attachments:
        original = str(att.get("original_filename") or "")
        content_type = str(att.get("content_type") or "")
        size_bytes = int(att.get("size_bytes") or 0)

        match = re.match(r"^(pdf_page|pdf_image)_(\d+)\.[a-z0-9]+$", original, re.I)
        if match:
            kind = match.group(1).lower()
            idx = int(match.group(2))
            key: tuple[object, ...] = ("pdf_page_idx", idx)
            prio = 2 if kind == "pdf_page" else 1
        else:
            key = ("exact", content_type, original, size_bytes)
            prio = 0

        if key not in index_by_key:
            index_by_key[key] = len(out)
            priority_by_key[key] = prio
            out.append(att)
            continue

        if prio > priority_by_key.get(key, 0):
            out[index_by_key[key]] = att
            priority_by_key[key] = prio

    return out
