"""Enum types for models."""

from enum import Enum


class ProjectCategory(str, Enum):
    """Project category enum."""

    PULLOVER = "Pullover"
    JACKE = "Jacke"
    SCHAL = "Schal"
    MUTZE = "Mütze"
    STIRNBAND = "Stirnband"


class ImageType(str, Enum):
    """Image type enum."""

    PHOTO = "photo"
    DIAGRAM = "diagram"
