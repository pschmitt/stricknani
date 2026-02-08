"""Compatibility shim for the legacy import path.

New code should import from :mod:`stricknani.importing`.
"""

from __future__ import annotations

from stricknani.importing.importer import (
    IMPORT_ALLOWED_IMAGE_EXTENSIONS,
    IMPORT_ALLOWED_IMAGE_TYPES,
    IMPORT_IMAGE_HEADERS,
    IMPORT_IMAGE_MAX_BYTES,
    IMPORT_IMAGE_MAX_COUNT,
    IMPORT_IMAGE_MIN_DIMENSION,
    IMPORT_IMAGE_SSIM_THRESHOLD,
    IMPORT_IMAGE_TIMEOUT,
    GarnstudioPatternImporter,
    PatternImporter,
    _is_allowed_import_image,
    _is_garnstudio_url,
    _is_valid_import_url,
    filter_import_image_urls,
    is_allowed_import_image,
    is_garnstudio_url,
    is_valid_import_url,
    trim_import_strings,
)

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
