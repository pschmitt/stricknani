"""Image metadata helpers."""

from __future__ import annotations

from pathlib import Path

import anyio
from PIL import Image as PilImage

from stricknani.config import config


async def get_image_dimensions(
    filename: str,
    entity_id: int,
    *,
    subdir: str = "projects",
) -> tuple[int | None, int | None]:
    """Return (width, height) for an image in the media directory.

    This runs in a thread to avoid blocking the async event loop.
    """
    image_path = config.MEDIA_ROOT / subdir / str(entity_id) / filename
    if not image_path.exists():
        return None, None

    def _read(path: Path) -> tuple[int | None, int | None]:
        try:
            with PilImage.open(path) as img:
                width, height = img.size
                return int(width), int(height)
        except (OSError, ValueError):
            return None, None

    return await anyio.to_thread.run_sync(_read, image_path)
