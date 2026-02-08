"""Content extractor base classes.

Extractors are responsible for parsing raw content and extracting
structured pattern/project data from it.
"""

from __future__ import annotations

from abc import ABC, abstractmethod
from typing import TYPE_CHECKING, Any

if TYPE_CHECKING:
    from stricknani.importing.models import ExtractedData, RawContent


class ContentExtractor(ABC):
    """Abstract base class for content extractors.

    An extractor takes RawContent and extracts structured data from it.
    Different extractors handle different content types (HTML, AI parsing, etc.).
    """

    @property
    @abstractmethod
    def name(self) -> str:
        """Return the name of this extractor."""
        pass

    @abstractmethod
    def can_extract(self, content: RawContent) -> bool:
        """Check if this extractor can handle the given content.

        Args:
            content: The raw content to check

        Returns:
            True if this extractor can extract data from the content
        """
        pass

    @abstractmethod
    async def extract(
        self,
        content: RawContent,
        *,
        hints: dict[str, Any] | None = None,
    ) -> ExtractedData:
        """Extract structured data from raw content.

        Args:
            content: The raw content to extract from
            hints: Optional hints from previous extraction attempts

        Returns:
            Extracted structured data

        Raises:
            ExtractorError: If extraction fails
        """
        pass


class ExtractorError(Exception):
    """Exception raised when content extraction fails."""

    def __init__(
        self,
        message: str,
        extractor_name: str | None = None,
    ) -> None:
        """Initialize the error.

        Args:
            message: Error message
            extractor_name: Name of the extractor that failed
        """
        super().__init__(message)
        self.extractor_name = extractor_name


class FallbackExtractor(ContentExtractor):
    """Fallback extractor that creates minimal extracted data.

    This extractor always succeeds and provides basic extraction
    for any content type. It's used as a last resort.
    """

    @property
    def name(self) -> str:
        """Return the name of this extractor."""
        return "fallback"

    def can_extract(self, content: RawContent) -> bool:
        """Can extract from any content."""
        return True

    async def extract(
        self,
        content: RawContent,
        *,
        hints: dict[str, Any] | None = None,
    ) -> ExtractedData:
        """Extract minimal data - just the raw content as description."""
        from stricknani.importing.models import ExtractedData

        text = content.get_text()[:500]  # First 500 chars as description
        return ExtractedData(
            name="Imported Item",
            description=text if text else "Imported from external source",
            link=content.source_url,
        )


__all__ = [
    "ContentExtractor",
    "ExtractorError",
    "FallbackExtractor",
]
