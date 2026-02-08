"""Tag parsing/serialization for projects."""

from __future__ import annotations

import json
import re


def normalize_tags(raw_tags: str | None) -> list[str]:
    """Convert raw tag input into a list of unique tags."""
    if not raw_tags:
        return []

    candidates = re.split(r"[,#\\s]+", raw_tags)
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

