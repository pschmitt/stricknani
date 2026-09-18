"""Enum types for models."""

from enum import StrEnum


class ProjectCategory(StrEnum):
    """Project category enum."""

    PULLOVER = "Pullover"
    JACKE = "Jacke"
    SCHAL = "Schal"
    MUTZE = "Mütze"
    STIRNBAND = "Stirnband"


class ImageType(StrEnum):
    """Image type enum."""

    PHOTO = "photo"
    DIAGRAM = "diagram"
