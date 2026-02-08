"""Image deduplication utilities.

Handles checksum-based and similarity-based duplicate detection.
"""

from __future__ import annotations

import logging
from collections.abc import Sequence
from dataclasses import dataclass
from io import BytesIO
from typing import TYPE_CHECKING

import anyio
from PIL import Image as PilImage

from stricknani.importing.images.constants import (
    IMPORT_IMAGE_MIN_DIMENSION,
    IMPORT_IMAGE_SSIM_THRESHOLD,
)
from stricknani.utils.files import compute_checksum

if TYPE_CHECKING:
    from stricknani.utils.image_similarity import SimilarityImage

logger = logging.getLogger("stricknani.imports")


@dataclass
class ImageInspectionResult:
    """Result of inspecting an image for import eligibility."""

    width: int
    height: int
    similarity: SimilarityImage
    checksum: str


def is_too_small(width: int, height: int) -> bool:
    """Check if image dimensions are below minimum threshold."""
    return width < IMPORT_IMAGE_MIN_DIMENSION or height < IMPORT_IMAGE_MIN_DIMENSION


def inspect_image_content(content: bytes) -> ImageInspectionResult | None:
    """Inspect image content and build similarity data.

    Args:
        content: Raw image bytes

    Returns:
        Inspection result with dimensions, similarity payload, and checksum,
        or None if image cannot be processed.
    """
    from stricknani.utils.image_similarity import build_similarity_image

    try:
        with PilImage.open(BytesIO(content)) as img:
            width, height = img.size
            width_i = int(width)
            height_i = int(height)

            if is_too_small(width_i, height_i):
                return None

            similarity = build_similarity_image(img)
            checksum = compute_checksum(content)

            return ImageInspectionResult(
                width=width_i,
                height=height_i,
                similarity=similarity,
                checksum=checksum,
            )
    except Exception as exc:
        logger.debug("Failed to inspect image: %s", exc)
        return None


def is_duplicate_by_checksum(checksum: str, existing_checksums: set[str]) -> bool:
    """Check if checksum exists in known checksums."""
    return checksum in existing_checksums


def is_duplicate_by_similarity(
    similarity: SimilarityImage,
    existing_similarities: Sequence[SimilarityImage],
    threshold: float = IMPORT_IMAGE_SSIM_THRESHOLD,
) -> tuple[bool, float | None]:
    """Check if image is similar to any existing image.

    Args:
        similarity: Similarity payload for candidate image
        existing_similarities: List of existing image similarity payloads
        threshold: SSIM threshold above which images are considered duplicates

    Returns:
        Tuple of (is_duplicate, highest_similarity_score)
    """
    from stricknani.utils.image_similarity import compute_similarity_score

    max_score: float | None = None

    for existing in existing_similarities:
        score = compute_similarity_score(existing, similarity)
        if score is None:
            continue

        if max_score is None or score > max_score:
            max_score = score

        if score >= threshold:
            return True, score

    return False, max_score


def should_skip_as_thumbnail(
    similarity: SimilarityImage,
    accepted_similarities: Sequence[SimilarityImage],
    threshold: float = IMPORT_IMAGE_SSIM_THRESHOLD,
) -> tuple[bool, list[SimilarityImage]]:
    """Check if image should be skipped as a thumbnail of already accepted images.

    Args:
        similarity: Similarity payload for candidate image
        accepted_similarities: Images already accepted for import
        threshold: SSIM threshold for similarity detection

    Returns:
        Tuple of (should_skip, larger_duplicates_to_remove)
    """
    from stricknani.utils.image_similarity import compute_similarity_score

    to_remove: list[SimilarityImage] = []

    for accepted in accepted_similarities:
        score = compute_similarity_score(accepted, similarity)
        if score is None or score < threshold:
            continue

        # If candidate has fewer pixels, it's a thumbnail - skip it
        if similarity.pixels <= accepted.pixels:
            return True, []

        # If candidate is larger, mark the accepted one for removal
        to_remove.append(accepted)

    return False, to_remove


async def async_inspect_image_content(
    content: bytes,
) -> ImageInspectionResult | None:
    """Async wrapper for inspect_image_content.

    Args:
        content: Raw image bytes

    Returns:
        Inspection result or None
    """
    return await anyio.to_thread.run_sync(inspect_image_content, content)


__all__ = [
    "ImageInspectionResult",
    "async_inspect_image_content",
    "inspect_image_content",
    "is_duplicate_by_checksum",
    "is_duplicate_by_similarity",
    "is_too_small",
    "should_skip_as_thumbnail",
]
