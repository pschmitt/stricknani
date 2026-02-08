"""Pattern importing package.

Prefer importing from this package instead of :mod:`stricknani.utils.importer`.
The old module remains as a compatibility shim.
"""

from __future__ import annotations

from .importer import (
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
    filter_import_image_urls,
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
    "filter_import_image_urls",
    "trim_import_strings",
]
