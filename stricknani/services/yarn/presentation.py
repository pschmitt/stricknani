"""Presentation helpers for yarn list/detail views."""

from __future__ import annotations

from collections.abc import Iterable
from pathlib import Path

from PIL import Image as PilImage

from stricknani.config import config
from stricknani.models import Project, User, Yarn
from stricknani.utils.files import get_file_url, get_thumbnail_url


def resolve_yarn_preview(yarn: Yarn) -> str | None:
    """Return the thumbnail URL for the first photo, if any."""
    if not yarn.photos:
        return None
    first = yarn.photos[0]
    return get_thumbnail_url(first.filename, yarn.id, subdir="yarns")


def resolve_project_preview(project: Project) -> dict[str, str | None]:
    """Return preview image data for a project if any images exist."""
    candidates = [img for img in project.images if img.is_title_image]
    if not candidates and project.images:
        candidates = [project.images[0]]
    if not candidates:
        return {"preview_url": None, "preview_alt": None}

    image = candidates[0]
    thumb_name = f"thumb_{Path(image.filename).stem}.jpg"
    thumb_path = (
        config.MEDIA_ROOT / "thumbnails" / "projects" / str(project.id) / thumb_name
    )
    url = None
    if thumb_path.exists():
        url = get_thumbnail_url(
            image.filename,
            project.id,
            subdir="projects",
        )
    file_path = config.MEDIA_ROOT / "projects" / str(project.id) / image.filename
    if file_path.exists():
        url = get_file_url(
            image.filename,
            project.id,
            subdir="projects",
        )

    return {"preview_url": url, "preview_alt": image.alt_text or project.name}


def get_yarn_photo_dimensions(
    yarn_id: int, filename: str
) -> tuple[int | None, int | None]:
    image_path = config.MEDIA_ROOT / "yarns" / str(yarn_id) / filename
    if not image_path.exists():
        return None, None
    try:
        with PilImage.open(image_path) as img:
            width, height = img.size
            return int(width), int(height)
    except (OSError, ValueError):
        return None, None


def serialize_yarn_photos(yarn: Yarn) -> list[dict[str, object]]:
    """Prepare photo metadata for templates."""
    payload: list[dict[str, object]] = []
    has_seen_primary = False

    sorted_photos = sorted(yarn.photos, key=lambda p: (not p.is_primary, p.id))
    for photo in sorted_photos:
        width, height = get_yarn_photo_dimensions(yarn.id, photo.filename)

        is_primary = photo.is_primary
        if is_primary:
            if has_seen_primary:
                is_primary = False
            else:
                has_seen_primary = True

        payload.append(
            {
                "id": photo.id,
                "thumbnail_url": get_thumbnail_url(
                    photo.filename,
                    yarn.id,
                    subdir="yarns",
                ),
                "full_url": get_file_url(
                    photo.filename,
                    yarn.id,
                    subdir="yarns",
                ),
                "alt_text": photo.alt_text,
                "is_primary": is_primary,
                "width": width,
                "height": height,
            }
        )

    if not has_seen_primary and payload:
        payload[0]["is_primary"] = True

    return payload


def serialize_yarn_cards(
    yarns: Iterable[Yarn],
    current_user: User | None = None,
) -> list[dict[str, object]]:
    """Prepare yarn entries for list rendering with preview URLs."""
    favorites = set()
    if current_user:
        favorites = {y.id for y in current_user.favorite_yarns}

    return [
        {
            "yarn": {
                "id": yarn.id,
                "name": yarn.name,
                "brand": yarn.brand,
                "colorway": yarn.colorway,
                "dye_lot": yarn.dye_lot,
                "fiber_content": yarn.fiber_content,
                "weight_category": yarn.weight_category,
                "weight_grams": yarn.weight_grams,
                "length_meters": yarn.length_meters,
                "description": yarn.description,
                "notes": yarn.notes,
                "created_at": yarn.created_at.isoformat() if yarn.created_at else None,
                "updated_at": yarn.updated_at.isoformat() if yarn.updated_at else None,
                "project_count": len(yarn.projects),
                "is_favorite": yarn.id in favorites,
                "is_ai_enhanced": yarn.is_ai_enhanced,
            },
            "preview_url": resolve_yarn_preview(yarn),
        }
        for yarn in yarns
    ]
