"""Yarn-related helpers for project routes."""

from __future__ import annotations

import asyncio
import logging
import re
from collections.abc import Sequence
from datetime import UTC, datetime
from typing import Any

from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm import selectinload

from stricknani.config import config
from stricknani.models import Project
from stricknani.models import Yarn as YarnModel
from stricknani.models.associations import project_yarns
from stricknani.services.audit import create_audit_log
from stricknani.utils.files import get_thumbnail_url
from stricknani.utils.wayback import store_wayback_snapshot

logger = logging.getLogger(__name__)


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


async def ensure_yarns_by_text(
    db: AsyncSession,
    user_id: int,
    yarn_text: str | None,
    current_yarn_ids: list[int],
    yarn_brand: str | None = None,
    yarn_details: list[dict[str, Any]] | None = None,
) -> list[int]:
    """Link against a real yarn, or create a new one when there is no match."""
    updated_ids = list(current_yarn_ids)

    # 1. Handle structured yarn details first (highest quality)
    if yarn_details:
        for detail in yarn_details:
            name = detail.get("name")
            link = detail.get("link")
            if not name and not link:
                continue

            db_yarn_obj = None
            if link:
                res_match = await db.execute(
                    select(YarnModel).where(
                        YarnModel.owner_id == user_id,
                        YarnModel.link == link,
                    )
                )
                db_yarn_obj = res_match.scalar_one_or_none()

            if not db_yarn_obj and name:
                res_match = await db.execute(
                    select(YarnModel).where(
                        YarnModel.owner_id == user_id,
                        func.lower(YarnModel.name) == name.lower(),
                    )
                )
                db_yarn_obj = res_match.scalar_one_or_none()

            if not db_yarn_obj:
                db_yarn_obj = YarnModel(
                    name=name or "Unknown Yarn",
                    owner_id=user_id,
                    brand=detail.get("brand") or yarn_brand,
                    colorway=detail.get("colorway"),
                    link=link,
                )
                db.add(db_yarn_obj)
                await db.flush()
                await create_audit_log(
                    db,
                    actor_user_id=user_id,
                    entity_type="yarn",
                    entity_id=db_yarn_obj.id,
                    action="created",
                    details={
                        "name": db_yarn_obj.name,
                        "brand": db_yarn_obj.brand,
                        "colorway": db_yarn_obj.colorway,
                        "source": "project_yarn_resolution",
                    },
                )

                if db_yarn_obj.link:
                    try:
                        from stricknani.services.yarn.images import (
                            import_yarn_images_from_urls,
                        )
                        from stricknani.utils.importer import (
                            GarnstudioPatternImporter,
                            PatternImporter,
                            is_garnstudio_url,
                        )

                        im_ptr: PatternImporter
                        if is_garnstudio_url(db_yarn_obj.link):
                            im_ptr = GarnstudioPatternImporter(db_yarn_obj.link)
                        else:
                            im_ptr = PatternImporter(db_yarn_obj.link)

                        yarn_data = await im_ptr.fetch_and_parse()

                        imported_name = yarn_data.get("yarn") or yarn_data.get("title")
                        if imported_name and len(imported_name) > len(db_yarn_obj.name):
                            db_yarn_obj.name = imported_name

                        if yarn_data.get("brand") and not db_yarn_obj.brand:
                            db_yarn_obj.brand = yarn_data.get("brand")
                        if yarn_data.get("colorway") and not db_yarn_obj.colorway:
                            db_yarn_obj.colorway = yarn_data.get("colorway")
                        if yarn_data.get("fiber_content"):
                            db_yarn_obj.fiber_content = yarn_data.get("fiber_content")
                        if yarn_data.get("weight_grams"):
                            db_yarn_obj.weight_grams = yarn_data.get("weight_grams")
                        if yarn_data.get("length_meters"):
                            db_yarn_obj.length_meters = yarn_data.get("length_meters")
                        if yarn_data.get("weight_category"):
                            db_yarn_obj.weight_category = yarn_data.get(
                                "weight_category"
                            )
                        if yarn_data.get("needles"):
                            db_yarn_obj.recommended_needles = yarn_data.get("needles")
                        if not db_yarn_obj.description:
                            db_yarn_obj.description = yarn_data.get(
                                "notes"
                            ) or yarn_data.get("comment")

                        img_urls = yarn_data.get("image_urls")
                        if img_urls:
                            await import_yarn_images_from_urls(
                                db,
                                db_yarn_obj,
                                img_urls,
                            )

                        if config.FEATURE_WAYBACK_ENABLED and db_yarn_obj.link:
                            db_yarn_obj.link_archive_requested_at = datetime.now(UTC)
                            await db.flush()
                            asyncio.create_task(
                                store_wayback_snapshot(
                                    YarnModel, db_yarn_obj.id, db_yarn_obj.link
                                )
                            )

                    except Exception as exc:
                        logger.warning(
                            "Failed to auto-import yarn from %s: %s",
                            db_yarn_obj.link,
                            exc,
                        )

            if db_yarn_obj.id not in updated_ids:
                updated_ids.append(db_yarn_obj.id)

        return updated_ids

    # 2. Fallback to raw text parsing
    if not yarn_text:
        return updated_ids

    if "\n" in yarn_text.strip():
        raw_names = []
        for line in yarn_text.splitlines():
            line = line.strip()
            if not line or line.lower() == "oder:":
                continue
            if line.lower().startswith("oder:"):
                line = line[5:].strip()
            if line:
                raw_names.append(line)
        yarn_names = raw_names
    else:
        if re.search(r"(?:farbe|color|colour)\s*\d+\s*,\s*", yarn_text, re.I):
            yarn_names = [yarn_text.strip()]
        else:
            yarn_names = [n.strip() for n in yarn_text.split(",") if n.strip()]

    if not yarn_names:
        return updated_ids

    existing_linked_yarns = []
    if updated_ids:
        res = await db.execute(
            select(YarnModel.name).where(YarnModel.id.in_(updated_ids))
        )
        existing_linked_yarns = [row[0].lower() for row in res]

    for name in yarn_names:
        if name.lower() in existing_linked_yarns:
            continue

        res_match = await db.execute(
            select(YarnModel).where(
                YarnModel.owner_id == user_id,
                func.lower(YarnModel.name) == name.lower(),
            )
        )
        db_yarn_obj = res_match.scalar_one_or_none()

        if not db_yarn_obj:
            db_yarn_obj = YarnModel(name=name, owner_id=user_id, brand=yarn_brand)
            db.add(db_yarn_obj)
            await db.flush()
            await create_audit_log(
                db,
                actor_user_id=user_id,
                entity_type="yarn",
                entity_id=db_yarn_obj.id,
                action="created",
                details={
                    "name": db_yarn_obj.name,
                    "brand": db_yarn_obj.brand,
                    "source": "project_yarn_resolution",
                },
            )

        if db_yarn_obj.id not in updated_ids:
            updated_ids.append(db_yarn_obj.id)

    return updated_ids


async def get_exclusive_yarns(db: AsyncSession, project: Project) -> list[YarnModel]:
    """Return yarns linked only to this project."""
    exclusive = []
    for yarn in project.yarns:
        res = await db.execute(
            select(func.count()).where(project_yarns.c.yarn_id == yarn.id)
        )
        count = res.scalar() or 0
        if count == 1:
            exclusive.append(yarn)
    return exclusive
