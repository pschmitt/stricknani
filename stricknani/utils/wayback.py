"""Wayback Machine utilities."""

import logging
from datetime import UTC, datetime
from typing import Any
from urllib.parse import quote

import httpx

from stricknani.database import AsyncSessionLocal
from stricknani.utils.importer import _is_valid_import_url

WAYBACK_SAVE_TIMEOUT = 15

logger = logging.getLogger("stricknani.wayback")


def _should_request_archive(raw: str | None) -> bool:
    if raw is None:
        return False
    return str(raw).strip().lower() in {"1", "true", "yes", "on"}


async def _request_wayback_snapshot(url: str) -> str | None:
    if not _is_valid_import_url(url):
        return None
    save_url = f"https://web.archive.org/save/{quote(url, safe='')}"
    async with httpx.AsyncClient(timeout=WAYBACK_SAVE_TIMEOUT) as client:
        try:
            response = await client.get(save_url)
            archive_url: str | None = None
            content_location = response.headers.get("content-location")
            location = response.headers.get("location")
            for candidate in [content_location, location]:
                if not candidate:
                    continue
                if candidate.startswith("/"):
                    archive_url = f"https://web.archive.org{candidate}"
                    break
                if candidate.startswith("http"):
                    archive_url = candidate
                    break
            logger.info(
                "Wayback snapshot request %s -> %s",
                url,
                response.status_code,
            )
            if not archive_url:
                try:
                    availability = await client.get(
                        "https://archive.org/wayback/available",
                        params={"url": url},
                    )
                    if availability.status_code == 200:
                        payload = availability.json()
                        closest = payload.get("archived_snapshots", {}).get(
                            "closest", {}
                        )
                        if isinstance(closest, dict):
                            archive_url = closest.get("url")
                except (httpx.HTTPError, ValueError, TypeError) as exc:
                    logger.info(
                        "Wayback availability check failed for %s: %s", url, exc
                    )
            return archive_url
        except httpx.HTTPError as exc:
            logger.info("Wayback snapshot request failed for %s: %s", url, exc)
    return None


async def store_wayback_snapshot(model_class: Any, entity_id: int, url: str) -> None:
    """Request and store a wayback snapshot for a given entity."""
    try:
        async with AsyncSessionLocal() as session:
            entity = await session.get(model_class, entity_id)
            if not entity or entity.link_archive:
                return

            # Update requested_at first to avoid multiple requests
            if hasattr(entity, "link_archive_requested_at"):
                entity.link_archive_requested_at = datetime.now(UTC)
                await session.commit()

            try:
                archive_url = await _request_wayback_snapshot(url)
            except Exception as exc:
                logger.error(
                    "Unexpected error in _request_wayback_snapshot for %s: %s",
                    url,
                    exc,
                )
                archive_url = None

            if archive_url:
                entity.link_archive = archive_url
                if hasattr(entity, "link_archive_failed"):
                    entity.link_archive_failed = False
                await session.commit()
            else:
                logger.info("Wayback snapshot not available for %s", url)
                if hasattr(entity, "link_archive_failed"):
                    entity.link_archive_failed = True
                    await session.commit()

    except Exception as exc:
        logger.exception("Failed to store wayback snapshot for %s: %s", url, exc)

