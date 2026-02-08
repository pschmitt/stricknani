"""Compatibility shim for the legacy import path.

New code should import from :mod:`stricknani.importing`.
"""

from __future__ import annotations

# Import image constants and validation from new consolidated location
from stricknani.importing.images import (
    IMPORT_ALLOWED_IMAGE_EXTENSIONS,
    IMPORT_ALLOWED_IMAGE_TYPES,
    IMPORT_IMAGE_HEADERS,
    IMPORT_IMAGE_MAX_BYTES,
    IMPORT_IMAGE_MAX_COUNT,
    IMPORT_IMAGE_MIN_DIMENSION,
    IMPORT_IMAGE_SSIM_THRESHOLD,
    IMPORT_IMAGE_TIMEOUT,
    is_allowed_import_image,
    is_valid_import_url,
)
from stricknani.importing.importer import (
    GarnstudioPatternImporter,
    PatternImporter,
    _is_garnstudio_url,
    filter_import_image_urls,
    is_garnstudio_url,
    trim_import_strings,
)


# Provide private wrappers for backward compatibility
def _is_valid_import_url(url: str) -> bool:
    """Ensure the import URL uses http(s) and has a host."""
    return is_valid_import_url(url)


def _is_allowed_import_image(content_type: str | None, url: str) -> bool:
    """Validate content type or file extension for image imports."""
    return is_allowed_import_image(content_type, url)


__all__ = [
    "GarnstudioPatternImporter",
    "IMPORT_ALLOWED_IMAGE_EXTENSIONS",
    "IMPORT_ALLOWED_IMAGE_TYPES",
    "IMPORT_IMAGE_HEADERS",
    "IMPORT_IMAGE_MAX_BYTES",
    "IMPORT_IMAGE_MAX_COUNT",
    "IMPORT_IMAGE_MIN_DIMENSION",
    "IMPORT_IMAGE_SSIM_THRESHOLD",
    "IMPORT_IMAGE_TIMEOUT",
    "PatternImporter",
    "_is_allowed_import_image",
    "_is_garnstudio_url",
    "_is_valid_import_url",
    "filter_import_image_urls",
    "is_allowed_import_image",
    "is_garnstudio_url",
    "is_valid_import_url",
    "trim_import_strings",
]
