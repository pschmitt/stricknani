"""Image-related helpers (safe from async request handlers)."""

from __future__ import annotations

from .dimensions import get_image_dimensions

__all__ = ["get_image_dimensions"]
