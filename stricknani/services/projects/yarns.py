"""Yarn-related helpers for project routes."""

from __future__ import annotations

from collections.abc import Sequence

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm import selectinload

from stricknani.models import Yarn as YarnModel
from stricknani.utils.files import get_thumbnail_url


def resolve_yarn_preview(yarn: YarnModel) -> dict[str, str | None]:
    """Return preview image data for a yarn if a photo exists."""
    if not yarn.photos:
        return {"preview_url": None, "preview_alt": None}

    first = yarn.photos[0]
    return {
        "preview_url": get_thumbnail_url(
            first.filename,
            yarn.id,
            subdir="yarns",
        ),
        "preview_alt": first.alt_text or yarn.name,
    }


async def get_user_yarns(db: AsyncSession, user_id: int) -> Sequence[YarnModel]:
    """Return all yarns for a user ordered by name.

    Note: this mutates photo filenames to point at thumbnails for backwards
    compatibility with existing templates.
    """
    result = await db.execute(
        select(YarnModel)
        .where(YarnModel.owner_id == user_id)
        .order_by(YarnModel.name)
        .options(selectinload(YarnModel.photos))
    )
    yarns = result.scalars().all()
    for yarn in yarns:
        for photo in yarn.photos:
            photo.filename = get_thumbnail_url(photo.filename, yarn.id, subdir="yarns")
    return yarns


async def load_owned_yarns(
    db: AsyncSession,
    user_id: int,
    yarn_ids: list[int],
) -> Sequence[YarnModel]:
    """Load yarns that belong to the user from provided IDs."""
    if not yarn_ids:
        return []

    result = await db.execute(
        select(YarnModel).where(
            YarnModel.owner_id == user_id,
            YarnModel.id.in_(yarn_ids),
        )
    )
    return result.scalars().all()
