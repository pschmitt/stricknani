"""Tag parsing/serialization for projects."""

from __future__ import annotations

import json
import re

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from stricknani.models import Project


def normalize_tags(raw_tags: str | None) -> list[str]:
    """Convert raw tag input into a list of unique tags."""
    if not raw_tags:
        return []

    candidates = re.split(r"[,#\s]+", raw_tags)
    seen: set[str] = set()
    tags: list[str] = []
    for candidate in candidates:
        cleaned = candidate.strip()
        if not cleaned:
            continue
        key = cleaned.lower()
        if key in seen:
            continue
        seen.add(key)
        tags.append(cleaned)
    return tags


def serialize_tags(tags: list[str]) -> str | None:
    """Serialize tags list for storage."""
    if not tags:
        return None
    return json.dumps(tags)


def deserialize_tags(raw: str | None) -> list[str]:
    """Deserialize stored tags string into a list."""
    if not raw:
        return []
    try:
        data = json.loads(raw)
    except (ValueError, TypeError):
        data = None

    if isinstance(data, list):
        return [str(item).strip() for item in data if str(item).strip()]

    return [segment.strip() for segment in raw.split(",") if segment.strip()]


async def get_user_tags(db: AsyncSession, user_id: int) -> list[str]:
    """Return a sorted list of unique tags for a user."""
    result = await db.execute(select(Project.tags).where(Project.owner_id == user_id))
    tag_map: dict[str, str] = {}
    for (raw_tags,) in result:
        for tag in deserialize_tags(raw_tags):
            key = tag.casefold()
            if key not in tag_map:
                tag_map[key] = tag
    return sorted(tag_map.values(), key=str.casefold)
