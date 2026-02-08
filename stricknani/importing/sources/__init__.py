"""Import source base classes.

Sources are responsible for fetching raw content from various inputs
(URL, file upload, etc.).
"""

from __future__ import annotations

from abc import ABC, abstractmethod
from typing import TYPE_CHECKING

if TYPE_CHECKING:
    from stricknani.importing.models import ImportSourceType, RawContent


class ImportSource(ABC):
    """Abstract base class for import sources.

    A source is responsible for fetching raw content from some input
    (URL, file, etc.) and returning it as RawContent for extraction.
    """

    def __init__(self, source_id: str | None = None) -> None:
        """Initialize the source.

        Args:
            source_id: Optional identifier for this source instance
        """
        self.source_id = source_id

    @property
    @abstractmethod
    def source_type(self) -> ImportSourceType:
        """Return the type of this source."""
        pass

    @abstractmethod
    async def fetch(self) -> RawContent:
        """Fetch raw content from the source.

        Returns:
            RawContent containing the fetched data

        Raises:
            ImportSourceError: If fetching fails
        """
        pass

    @abstractmethod
    def can_fetch(self) -> bool:
        """Check if this source is properly configured and can fetch.

        Returns:
            True if fetch() can be called successfully
        """
        pass


class ImportSourceError(Exception):
    """Exception raised when an import source fails."""

    def __init__(
        self, message: str, source_type: ImportSourceType | None = None
    ) -> None:
        """Initialize the error.

        Args:
            message: Error message
            source_type: Type of source that failed
        """
        super().__init__(message)
        self.source_type = source_type


__all__ = ["ImportSource", "ImportSourceError"]
