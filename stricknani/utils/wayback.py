"""Wayback Machine utilities."""

import asyncio
import logging
from datetime import UTC, datetime
from typing import Any

import waybackpy

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

    loop = asyncio.get_running_loop()

    def _save() -> str | None:
        wayback = waybackpy.Url(url, user_agent="Stricknani/0.1.0")
        try:
            archive = wayback.save()
            return str(archive.archive_url)
        except Exception as exc:
            logger.info("Wayback save failed for %s: %s", url, exc)
            # If save fails, try to get newest
            try:
                archive = wayback.newest()
                if archive:
                    return str(archive.archive_url)
            except Exception as inner_exc:
                logger.info("Wayback newest lookup failed for %s: %s", url, inner_exc)
        return None

    try:
        return await loop.run_in_executor(None, _save)
    except Exception as exc:
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
