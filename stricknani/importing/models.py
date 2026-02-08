"""Core data models and types for the import pipeline.

These models represent the data structures that flow through the import pipeline
from source → extractor → target.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from enum import Enum, auto
from pathlib import Path
from typing import Any


class ContentType(Enum):
    """Types of content that can be imported."""

    HTML = auto()
    TEXT = auto()
    PDF = auto()
    IMAGE = auto()
    MARKDOWN = auto()
    UNKNOWN = auto()


class ImportSourceType(Enum):
    """Types of import sources."""

    URL = auto()
    FILE = auto()
    AI_VISION = auto()


@dataclass
class RawContent:
    """Raw content fetched from a source before extraction.

    This is the output of a Source and input to an Extractor.
    """

    content: bytes | str
    content_type: ContentType
    source_url: str | None = None
    source_path: Path | None = None
    metadata: dict[str, Any] = field(default_factory=dict)

    @property
    def is_binary(self) -> bool:
        """Check if content is binary (bytes) rather than text."""
        return isinstance(self.content, bytes)

    def get_text(self) -> str:
        """Get content as text, decoding if necessary."""
        if isinstance(self.content, str):
            return self.content
        encoding = self.metadata.get("encoding", "utf-8")
        return self.content.decode(encoding, errors="replace")


@dataclass
class ExtractedStep:
    """A single step/instruction extracted from a pattern."""

    step_number: int
    title: str | None = None
    description: str | None = None
    images: list[str] = field(default_factory=list)  # URLs or paths


@dataclass
class ExtractedYarn:
    """Yarn information extracted from a pattern."""

    name: str | None = None
    brand: str | None = None
    colorway: str | None = None
    weight: str | None = None  # e.g., "100g"
    length: str | None = None  # e.g., "300m"
    weight_category: str | None = None  # e.g., "DK", "Worsted"
    fiber_content: str | None = None
    link: str | None = None
    image_url: str | None = None


@dataclass
class ExtractedData:
    """Structured data extracted from raw content.

    This is the output of an Extractor and input to a Target.
    Fields are optional and depend on what was found in the source.
    """

    # Basic info
    name: str | None = None
    description: str | None = None
    category: str | None = None

    # Materials
    yarn: str | None = None  # Legacy: combined yarn string
    yarns: list[ExtractedYarn] = field(default_factory=list)
    needles: str | None = None
    other_materials: str | None = None

    # Gauge / technical
    stitch_sample: str | None = None
    gauge_width: int | None = None
    gauge_height: int | None = None

    # Fiber content
    fiber_content: str | None = None
    colorway: str | None = None
    weight_category: str | None = None

    # Instructions
    steps: list[ExtractedStep] = field(default_factory=list)

    # Media
    image_urls: list[str] = field(default_factory=list)

    # Source
    link: str | None = None
    brand: str | None = None

    # Extra fields for extensibility
    extras: dict[str, Any] = field(default_factory=dict)

    def to_project_dict(self) -> dict[str, Any]:
        """Convert to a dictionary suitable for Project model creation."""
        return {
            "name": self.name,
            "description": self.description,
            "category": self.category,
            "yarn": self.yarn,
            "needles": self.needles,
            "other_materials": self.other_materials,
            "stitch_sample": self.stitch_sample,
            "link": self.link,
            "brand": self.brand,
        }


@dataclass
class ImportResult:
    """Result of an import operation.

    Contains the created entity and metadata about the import.
    """

    success: bool
    entity_id: int | None = None
    entity_type: str | None = None  # "project", "yarn", "step"
    imported_images: int = 0
    errors: list[str] = field(default_factory=list)
    warnings: list[str] = field(default_factory=list)
    metadata: dict[str, Any] = field(default_factory=dict)

    @property
    def has_errors(self) -> bool:
        """Check if import had any errors."""
        return len(self.errors) > 0


__all__ = [
    "ContentType",
    "ExtractedData",
    "ExtractedStep",
    "ExtractedYarn",
    "ImportResult",
    "ImportSourceType",
    "RawContent",
]
