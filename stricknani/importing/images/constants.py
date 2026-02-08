"""Image import constants.

All image import configuration in one place for consistency.
"""

from __future__ import annotations

# Size limits
IMPORT_IMAGE_MAX_BYTES = 5 * 1024 * 1024  # 5 MB
IMPORT_IMAGE_MAX_COUNT = 10
IMPORT_IMAGE_MIN_DIMENSION = 64  # pixels

# Timeouts
IMPORT_IMAGE_TIMEOUT = 10  # seconds

# Similarity threshold for duplicate detection (SSIM)
IMPORT_IMAGE_SSIM_THRESHOLD = 0.95

# HTTP headers for image requests
IMPORT_IMAGE_HEADERS = {
    "User-Agent": "Stricknani Importer/0.1",
    "Accept": "image/*",
}

# Allowed MIME types
IMPORT_ALLOWED_IMAGE_TYPES = {
    "image/jpeg",
    "image/png",
    "image/webp",
    "image/gif",
}

# Allowed file extensions
IMPORT_ALLOWED_IMAGE_EXTENSIONS = {".jpg", ".jpeg", ".png", ".webp", ".gif"}

__all__ = [
    "IMPORT_ALLOWED_IMAGE_EXTENSIONS",
    "IMPORT_ALLOWED_IMAGE_TYPES",
    "IMPORT_IMAGE_HEADERS",
    "IMPORT_IMAGE_MAX_BYTES",
    "IMPORT_IMAGE_MAX_COUNT",
    "IMPORT_IMAGE_MIN_DIMENSION",
    "IMPORT_IMAGE_SSIM_THRESHOLD",
    "IMPORT_IMAGE_TIMEOUT",
]
