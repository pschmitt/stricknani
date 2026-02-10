"""Unified image download and processing utilities.

This module provides a consolidated interface for downloading images from URLs,
validating them, deduplicating, and preparing them for storage.
"""

from __future__ import annotations

import logging
from collections.abc import Sequence
from dataclasses import dataclass, field
from typing import TYPE_CHECKING

import httpx

from stricknani.importing.images.constants import (
    IMPORT_IMAGE_HEADERS,
    IMPORT_IMAGE_MAX_BYTES,
    IMPORT_IMAGE_MAX_COUNT,
    IMPORT_IMAGE_TIMEOUT,
)
from stricknani.importing.images.deduplicator import (
    ImageInspectionResult,
    async_inspect_image_content,
    is_duplicate_by_checksum,
    is_duplicate_by_similarity,
    should_skip_as_thumbnail,
)
from stricknani.importing.images.validator import (
    is_allowed_import_image,
    is_valid_import_url,
)

if TYPE_CHECKING:
    from stricknani.utils.image_similarity import SimilarityImage

logger = logging.getLogger("stricknani.imports")


@dataclass
class DownloadedImage:
    """A successfully downloaded and validated image ready for storage."""

    url: str
    content: bytes
    content_type: str | None
    inspection: ImageInspectionResult
    similarity: SimilarityImage


@dataclass
class ImageDownloadResult:
    """Result of a batch image download operation."""

    images: list[DownloadedImage] = field(default_factory=list)
    skipped: list[tuple[str, str]] = field(default_factory=list)  # (url, reason)
    errors: list[tuple[str, str]] = field(default_factory=list)  # (url, error)

    @property
    def count(self) -> int:
        """Number of successfully downloaded images."""
        return len(self.images)


class ImageDownloader:
    """Downloads and validates images from URLs with deduplication.

    This class provides a unified interface for image importing that handles:
    - URL validation
    - HTTP downloading with proper headers/timeouts
    - Content-type validation
    - Size limits
    - Checksum-based deduplication
    - Similarity-based deduplication (SSIM)
    - Thumbnail detection

    Example:
        downloader = ImageDownloader(referer="https://example.com")
        result = await downloader.download_images(
            image_urls=["https://example.com/img1.jpg"],
            existing_checksums=set(),
            existing_similarities=[],
            limit=5,
        )
    """

    def __init__(
        self,
        *,
        referer: str | None = None,
        timeout: int = IMPORT_IMAGE_TIMEOUT,
        max_bytes: int = IMPORT_IMAGE_MAX_BYTES,
        max_count: int = IMPORT_IMAGE_MAX_COUNT,
    ) -> None:
        """Initialize the downloader.

        Args:
            referer: Optional referer header for HTTP requests
            timeout: HTTP request timeout in seconds
            max_bytes: Maximum image size in bytes
            max_count: Maximum number of images to download
        """
        self.referer = referer
        self.timeout = timeout
        self.max_bytes = max_bytes
        self.max_count = max_count
        self._headers = dict(IMPORT_IMAGE_HEADERS)
        if referer:
            self._headers["Referer"] = referer

    async def download_images(
        self,
        image_urls: Sequence[str],
        *,
        existing_checksums: set[str] | None = None,
        existing_similarities: Sequence[SimilarityImage] | None = None,
        limit: int | None = None,
    ) -> ImageDownloadResult:
        """Download and validate a batch of images.

        Args:
            image_urls: URLs to download
            existing_checksums: Checksums of already imported images
            existing_similarities: Similarity payloads of existing images
            limit: Max images to download (defaults to max_count from init)

        Returns:
            Download result with images, skipped list, and errors
        """
        result = ImageDownloadResult()
        seen_checksums: set[str] = set()
        accepted_images: list[DownloadedImage] = []
        max_images = limit or self.max_count

        checksums = existing_checksums or set()
        similarities = list(existing_similarities or [])

        async with httpx.AsyncClient(
            timeout=self.timeout,
            follow_redirects=True,
            headers=self._headers,
        ) as client:
            for url in image_urls:
                if result.count >= max_images:
                    break

                if not is_valid_import_url(url):
                    result.skipped.append((url, "invalid URL"))
                    continue

                try:
                    downloaded, removed = await self._download_single(
                        client,
                        url,
                        checksums,
                        seen_checksums,
                        similarities,
                        accepted_images,
                    )

                    if downloaded:
                        for removed_image in removed:
                            if removed_image in accepted_images:
                                accepted_images.remove(removed_image)
                            if removed_image in result.images:
                                result.images.remove(removed_image)

                        accepted_images.append(downloaded)
                        result.images.append(downloaded)
                        seen_checksums.add(downloaded.inspection.checksum)

                except Exception as exc:
                    error_msg = str(exc)
                    logger.debug("Failed to download image %s: %s", url, error_msg)
                    result.errors.append((url, error_msg))

        return result

    async def _download_single(
        self,
        client: httpx.AsyncClient,
        url: str,
        existing_checksums: set[str],
        seen_checksums: set[str],
        existing_similarities: Sequence[SimilarityImage],
        accepted_images: list[DownloadedImage],
    ) -> tuple[DownloadedImage | None, list[DownloadedImage]]:
        """Download and validate a single image.

        Returns:
            DownloadedImage if successful, None if skipped/failed
        """
        # Download
        try:
            response = await client.get(url)
            response.raise_for_status()
        except httpx.HTTPError as exc:
            logger.debug("HTTP error for %s: %s", url, exc)
            return None, []

        # Check content type
        content_type = response.headers.get("content-type")
        if not is_allowed_import_image(content_type, url):
            logger.debug("Skipping non-image URL: %s", url)
            return None, []

        # Check content length header
        content_length = response.headers.get("content-length")
        if content_length:
            try:
                if int(content_length) > self.max_bytes:
                    logger.debug("Skipping large image %s (header)", url)
                    return None, []
            except ValueError:
                pass

        # Check content
        if not response.content:
            logger.debug("Skipping empty image response: %s", url)
            return None, []

        if len(response.content) > self.max_bytes:
            logger.debug("Skipping large image %s (content)", url)
            return None, []

        # Inspect image
        inspection = await async_inspect_image_content(response.content)
        if inspection is None:
            logger.debug("Skipping unreadable or too small image: %s", url)
            return None, []

        # Check checksum duplicates
        if is_duplicate_by_checksum(inspection.checksum, existing_checksums):
            logger.debug("Skipping already imported image %s", url)
            return None, []

        if is_duplicate_by_checksum(inspection.checksum, seen_checksums):
            logger.debug("Skipping duplicate image in batch %s", url)
            return None, []

        # Check similarity duplicates against existing stored images.
        is_duplicate, score = is_duplicate_by_similarity(
            inspection.similarity,
            existing_similarities,
        )
        if is_duplicate:
            if score is None:
                logger.debug("Skipping similar existing image %s", url)
            else:
                logger.debug(
                    "Skipping similar existing image %s (ssim %.3f)",
                    url,
                    score,
                )
            return None, []

        # Check similarity duplicates
        accepted_similarities = [img.similarity for img in accepted_images]
        skip, to_remove = should_skip_as_thumbnail(
            inspection.similarity,
            accepted_similarities,
        )
        if skip:
            logger.debug("Skipping thumbnail image %s", url)
            return None, []

        # Remove thumbnails that this image supersedes
        removed_images = [
            accepted for accepted in accepted_images if accepted.similarity in to_remove
        ]

        return (
            DownloadedImage(
                url=url,
                content=response.content,
                content_type=content_type,
                inspection=inspection,
                similarity=inspection.similarity,
            ),
            removed_images,
        )

    async def download_single(
        self,
        url: str,
        *,
        existing_checksums: set[str] | None = None,
        existing_similarities: Sequence[SimilarityImage] | None = None,
    ) -> DownloadedImage | None:
        """Download a single image.

        Convenience method for downloading just one image.

        Args:
            url: Image URL
            existing_checksums: Checksums to deduplicate against
            existing_similarities: Similarities to deduplicate against

        Returns:
            DownloadedImage or None if failed/skipped
        """
        result = await self.download_images(
            [url],
            existing_checksums=existing_checksums,
            existing_similarities=existing_similarities,
            limit=1,
        )
        return result.images[0] if result.images else None


__all__ = [
    "DownloadedImage",
    "ImageDownloadResult",
    "ImageDownloader",
]
