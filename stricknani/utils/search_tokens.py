"""Small parsing helpers shared by multiple list/search routes."""

from __future__ import annotations

import json
import re


def parse_import_image_urls(raw: list[str] | str | None) -> list[str]:
    """Parse image URLs sent from import forms.

    We accept either:
    - a single URL string (optionally comma-separated)
    - JSON encoded list of URLs
    - a list of strings where each element may be a URL or JSON
    """
    if not raw:
        return []

    if isinstance(raw, list):
        urls: list[str] = []
        for item in raw:
            if not item:
                continue
            try:
                data = json.loads(item)
                if isinstance(data, list):
                    urls.extend([str(u).strip() for u in data if u])
                else:
                    urls.append(str(data).strip())
            except (ValueError, TypeError):
                urls.append(item.strip())
        return [
            u
            for u in urls
            if u.startswith("http") or u.startswith("/media/") or u.startswith("/")
        ]

    try:
        data = json.loads(raw)
        if isinstance(data, list):
            return [str(item).strip() for item in data if str(item).strip()]
    except (ValueError, TypeError):
        pass

    if raw and (
        raw.startswith("http") or raw.startswith("/media/") or raw.startswith("/")
    ):
        return [s.strip() for s in raw.split(",") if s.strip()]
    return []


def strip_wrapping_quotes(value: str) -> str:
    """Strip wrapping single or double quotes from a search token."""
    cleaned = value.strip()
    if len(cleaned) >= 2 and cleaned[0] == cleaned[-1] and cleaned[0] in {'"', "'"}:
        return cleaned[1:-1].strip()
    return cleaned


def extract_search_token(search: str, prefix: str) -> tuple[str | None, str]:
    """Extract a prefix token (with optional quotes) and return (token, remaining)."""
    pattern = rf"(?i)(?:^|\s){re.escape(prefix)}(?:\"([^\"]+)\"|'([^']+)'|(\S+))"
    match = re.search(pattern, search)
    if not match:
        return None, search
    token = next((group for group in match.groups() if group), None)
    if token is None:
        return None, search
    start, end = match.span()
    remaining = (search[:start] + search[end:]).strip()
    return token.strip(), remaining
