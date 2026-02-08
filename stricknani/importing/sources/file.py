"""File-based import source.

Handles importing from uploaded files (images, PDFs, text files, etc.).
"""

from __future__ import annotations

import logging
from pathlib import Path
from typing import TYPE_CHECKING

from stricknani.importing.models import ContentType, ImportSourceType, RawContent
from stricknani.importing.sources import ImportSource, ImportSourceError

if TYPE_CHECKING:
    from collections.abc import Mapping

logger = logging.getLogger("stricknani.imports")


# Mapping of file extensions to content types
EXTENSION_TO_CONTENT_TYPE: Mapping[str, ContentType] = {
    # Images
    ".jpg": ContentType.IMAGE,
    ".jpeg": ContentType.IMAGE,
    ".png": ContentType.IMAGE,
    ".gif": ContentType.IMAGE,
    ".webp": ContentType.IMAGE,
    ".bmp": ContentType.IMAGE,
    ".tiff": ContentType.IMAGE,
    ".tif": ContentType.IMAGE,
    # Documents
    ".pdf": ContentType.PDF,
    # Web/Text
    ".html": ContentType.HTML,
    ".htm": ContentType.HTML,
    ".txt": ContentType.TEXT,
    ".md": ContentType.MARKDOWN,
    ".markdown": ContentType.MARKDOWN,
}


class FileSource(ImportSource):
    """Import source that reads from uploaded files.

    Supports images, PDFs, HTML, text, and markdown files.
    """

    def __init__(
        self,
        file_path: Path | str,
        *,
        original_filename: str | None = None,
        source_id: str | None = None,
    ) -> None:
        """Initialize the file source.

        Args:
            file_path: Path to the file to import
            original_filename: Original filename (if different from file_path)
            source_id: Optional identifier for this source
        """
        super().__init__(source_id=source_id)
        self.file_path = Path(file_path)
        self.original_filename = original_filename or self.file_path.name

    @property
    def source_type(self) -> ImportSourceType:
        """Return the type of this source."""
        return ImportSourceType.FILE

    def can_fetch(self) -> bool:
        """Check if the file exists and is readable."""
        return self.file_path.exists() and self.file_path.is_file()

    async def fetch(self) -> RawContent:
        """Read content from the file.

        Returns:
            RawContent containing the file data

        Raises:
            ImportSourceError: If the file cannot be read
        """
        if not self.can_fetch():
            raise ImportSourceError(
                f"File not found or not readable: {self.file_path}",
                source_type=self.source_type,
            )

        content_type = self._detect_content_type()

        try:
            content: bytes | str
            if content_type == ContentType.IMAGE:
                # Read images as binary
                content = self.file_path.read_bytes()
            elif content_type == ContentType.PDF:
                # Read PDFs as binary
                content = self.file_path.read_bytes()
            else:
                # Read text-based files as text
                content = self._read_text()

            logger.info(
                "Read %s file: %s (%s bytes)",
                content_type.name,
                self.original_filename,
                len(content) if isinstance(content, (str, bytes)) else 0,
            )

            return RawContent(
                content=content,
                content_type=content_type,
                source_path=self.file_path,
                metadata={
                    "original_filename": self.original_filename,
                    "file_size": self.file_path.stat().st_size,
                    "file_extension": self.file_path.suffix.lower(),
                },
            )

        except Exception as exc:
            raise ImportSourceError(
                f"Failed to read file {self.file_path}: {exc}",
                source_type=self.source_type,
            ) from exc

    def _detect_content_type(self) -> ContentType:
        """Detect content type from file extension."""
        suffix = self.file_path.suffix.lower()
        return EXTENSION_TO_CONTENT_TYPE.get(suffix, ContentType.UNKNOWN)

    def _read_text(self) -> str:
        """Read file as text, trying multiple encodings."""
        encodings = ["utf-8", "utf-8-sig", "latin-1", "cp1252"]

        for encoding in encodings:
            try:
                return self.file_path.read_text(encoding=encoding)
            except UnicodeDecodeError:
                continue

        # Fallback: read as bytes and decode with replacement
        return self.file_path.read_bytes().decode("utf-8", errors="replace")


class MultiFileSource(ImportSource):
    """Import source that aggregates multiple files.

    Useful when uploading multiple images or documents at once.
    The first file is treated as primary, others as supplementary.
    """

    def __init__(
        self,
        file_paths: list[Path | str],
        *,
        source_id: str | None = None,
    ) -> None:
        """Initialize the multi-file source.

        Args:
            file_paths: List of file paths to import
            source_id: Optional identifier for this source
        """
        super().__init__(source_id=source_id)
        self.file_paths = [Path(fp) for fp in file_paths]
        self._sources = [FileSource(fp) for fp in self.file_paths]

    @property
    def source_type(self) -> ImportSourceType:
        """Return the type of this source."""
        return ImportSourceType.FILE

    def can_fetch(self) -> bool:
        """Check if at least one file exists and is readable."""
        return any(source.can_fetch() for source in self._sources)

    async def fetch(self) -> RawContent:
        """Fetch the primary file (first in the list).

        Other files are stored in metadata for later processing.
        """
        if not self._sources:
            raise ImportSourceError(
                "No files provided",
                source_type=self.source_type,
            )

        # Fetch primary file
        primary = self._sources[0]
        primary_content = await primary.fetch()

        # Add supplementary files to metadata
        supplementary = []
        for source in self._sources[1:]:
            if source.can_fetch():
                supplementary.append(
                    {
                        "path": str(source.file_path),
                        "filename": source.original_filename,
                        "content_type": source._detect_content_type().name,
                    }
                )

        if supplementary:
            primary_content.metadata["supplementary_files"] = supplementary

        return primary_content

    async def fetch_all(self) -> list[RawContent]:
        """Fetch all files as separate RawContent objects.

        Returns:
            List of RawContent, one per file
        """
        contents = []
        for source in self._sources:
            if source.can_fetch():
                try:
                    content = await source.fetch()
                    contents.append(content)
                except ImportSourceError as exc:
                    logger.warning("Skipping file %s: %s", source.file_path, exc)
        return contents


__all__ = ["FileSource", "MultiFileSource"]
