"""Image importing utilities.

Unified interface for downloading, validating, and deduplicating images
from remote URLs during project/yarn imports.

Example:
    from stricknani.importing.images import ImageDownloader

    downloader = ImageDownloader(referer="https://example.com")
    result = await downloader.download_images(
        image_urls=["https://example.com/img1.jpg"],
        existing_checksums=set(),
        existing_similarities=[],
    )

    for img in result.images:
        print(f"Downloaded: {img.url} ({img.inspection.width}x{img.inspection.height})")
"""

from __future__ import annotations

# Constants
from stricknani.importing.images.constants import (
    IMPORT_ALLOWED_IMAGE_EXTENSIONS,
    IMPORT_ALLOWED_IMAGE_TYPES,
    IMPORT_IMAGE_HEADERS,
    IMPORT_IMAGE_MAX_BYTES,
    IMPORT_IMAGE_MAX_COUNT,
    IMPORT_IMAGE_MIN_DIMENSION,
    IMPORT_IMAGE_SSIM_THRESHOLD,
    IMPORT_IMAGE_TIMEOUT,
)

# Deduplication utilities
from stricknani.importing.images.deduplicator import (
    ImageInspectionResult,
    async_inspect_image_content,
    inspect_image_content,
    is_duplicate_by_checksum,
    is_duplicate_by_similarity,
    is_too_small,
    should_skip_as_thumbnail,
)

# Downloader
from stricknani.importing.images.downloader import (
    DownloadedImage,
    ImageDownloader,
    ImageDownloadResult,
)

# Validation utilities
from stricknani.importing.images.validator import (
    is_allowed_import_image,
    is_valid_import_url,
    validate_import_url,
)

__all__ = [
    # Constants
    "IMPORT_ALLOWED_IMAGE_EXTENSIONS",
    "IMPORT_ALLOWED_IMAGE_TYPES",
    "IMPORT_IMAGE_HEADERS",
    "IMPORT_IMAGE_MAX_BYTES",
    "IMPORT_IMAGE_MAX_COUNT",
    "IMPORT_IMAGE_MIN_DIMENSION",
    "IMPORT_IMAGE_SSIM_THRESHOLD",
    "IMPORT_IMAGE_TIMEOUT",
    # Deduplication
    "ImageInspectionResult",
    "async_inspect_image_content",
    "inspect_image_content",
    "is_duplicate_by_checksum",
    "is_duplicate_by_similarity",
    "is_too_small",
    "should_skip_as_thumbnail",
    # Downloader
    "DownloadedImage",
    "ImageDownloadResult",
    "ImageDownloader",
    # Validation
    "is_allowed_import_image",
    "is_valid_import_url",
    "validate_import_url",
]
