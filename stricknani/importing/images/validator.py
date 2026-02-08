"""Image import validation utilities.

Validates URLs, content types, and file extensions for image imports.
"""

from __future__ import annotations

from pathlib import Path
from urllib.parse import urlparse

from stricknani.importing.images.constants import (
    IMPORT_ALLOWED_IMAGE_EXTENSIONS,
    IMPORT_ALLOWED_IMAGE_TYPES,
)


def is_valid_import_url(url: str) -> bool:
    """Ensure the import URL uses http(s) and has a host."""
    parsed = urlparse(url)
    return parsed.scheme in {"http", "https"} and bool(parsed.netloc)


def is_allowed_import_image(content_type: str | None, url: str) -> bool:
    """Validate content type or file extension for image imports.

    Args:
        content_type: MIME type from HTTP response
        url: Source URL for extension fallback

    Returns:
        True if image is allowed for import
    """
    if content_type:
        normalized = content_type.split(";", 1)[0].strip().lower()
        if normalized in IMPORT_ALLOWED_IMAGE_TYPES:
            return True
    extension = Path(urlparse(url).path).suffix.lower()
    return extension in IMPORT_ALLOWED_IMAGE_EXTENSIONS


def validate_import_url(url: str) -> tuple[bool, str | None]:
    """Validate an import URL and return status with reason.

    Args:
        url: URL to validate

    Returns:
        Tuple of (is_valid, reason_if_invalid)
    """
    if not url:
        return False, "URL is empty"

    parsed = urlparse(url)

    if parsed.scheme not in {"http", "https"}:
        return False, f"Invalid scheme: {parsed.scheme}"

    if not parsed.netloc:
        return False, "Missing host"

    return True, None


__all__ = [
    "is_allowed_import_image",
    "is_valid_import_url",
    "validate_import_url",
]
