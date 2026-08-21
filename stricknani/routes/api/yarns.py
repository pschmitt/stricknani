"""Yarn endpoints for the JSON API."""

from __future__ import annotations

import asyncio
import logging
from datetime import UTC, datetime

from fastapi import APIRouter, Depends, File, HTTPException, Query, UploadFile, status
from sqlalchemy import delete, insert, select
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm import selectinload

from stricknani.config import config
from stricknani.database import get_db
from stricknani.models import User, Yarn, YarnImage
from stricknani.models.associations import user_favorite_yarns
from stricknani.routes.api.schemas import (
    YarnListItemResponse,
    YarnPage,
    YarnPhotoResponse,
    YarnResponse,
    YarnWriteRequest,
)
from stricknani.routes.auth import require_api_token
from stricknani.services.audit import create_audit_log
from stricknani.services.yarn.presentation import resolve_yarn_preview
from stricknani.utils.files import (
    InvalidImageError,
    UploadTooLargeError,
    create_thumbnail,
    delete_file,
    get_file_url,
    get_thumbnail_url,
    save_uploaded_image,
)
from stricknani.utils.ocr import is_ocr_available, precompute_ocr_for_media_file

logger = logging.getLogger("stricknani.api.yarns")

router: APIRouter = APIRouter(prefix="/yarns", tags=["api-yarns"])

API_PAGE_SIZE = 50


def _as_utc(dt: datetime) -> datetime:
    return dt if dt.tzinfo else dt.replace(tzinfo=UTC)


def _differs(expected: datetime, actual: datetime) -> bool:
    """See `routes/api/projects.py`'s identical helper - same tz-tolerant comparison."""
    return _as_utc(expected) != _as_utc(actual)


async def _get_owned_yarn(
    db: AsyncSession, yarn_id: int, user_id: int, *, with_photos: bool = True
) -> Yarn:
    query = select(Yarn).where(Yarn.id == yarn_id, Yarn.owner_id == user_id)
    if with_photos:
        query = query.options(selectinload(Yarn.photos), selectinload(Yarn.projects))
    result = await db.execute(query)
    yarn = result.scalar_one_or_none()
    if yarn is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND, detail="Yarn not found"
        )
    return yarn


def _serialize_photo(photo: YarnImage, yarn_id: int) -> YarnPhotoResponse:
    return YarnPhotoResponse(
        id=photo.id,
        url=get_file_url(photo.filename, yarn_id, subdir="yarns"),
        thumbnail_url=get_thumbnail_url(photo.filename, yarn_id, subdir="yarns"),
        alt_text=photo.alt_text,
        is_primary=photo.is_primary,
    )


def _serialize_yarn(yarn: Yarn, *, is_favorite: bool) -> YarnResponse:
    return YarnResponse(
        id=yarn.id,
        name=yarn.name,
        description=yarn.description,
        brand=yarn.brand,
        colorway=yarn.colorway,
        dye_lot=yarn.dye_lot,
        fiber_content=yarn.fiber_content,
        weight_category=yarn.weight_category,
        recommended_needles=yarn.recommended_needles,
        weight_grams=yarn.weight_grams,
        length_meters=yarn.length_meters,
        notes=yarn.notes,
        link=yarn.link,
        link_archive=yarn.link_archive,
        is_ai_enhanced=yarn.is_ai_enhanced,
        is_favorite=is_favorite,
        created_at=yarn.created_at,
        updated_at=yarn.updated_at,
        project_ids=[project.id for project in yarn.projects],
        photos=[_serialize_photo(photo, yarn.id) for photo in yarn.photos],
    )


async def _favorite_yarn_ids(db: AsyncSession, user_id: int) -> set[int]:
    result = await db.execute(
        select(user_favorite_yarns.c.yarn_id).where(
            user_favorite_yarns.c.user_id == user_id
        )
    )
    return {row[0] for row in result}


def _apply_write_fields(yarn: Yarn, payload: YarnWriteRequest) -> None:
    yarn.name = payload.name
    yarn.description = payload.description or None
    yarn.brand = payload.brand or None
    yarn.colorway = payload.colorway or None
    yarn.dye_lot = payload.dye_lot or None
    yarn.fiber_content = payload.fiber_content or None
    yarn.weight_category = payload.weight_category or None
    yarn.recommended_needles = payload.recommended_needles or None
    yarn.weight_grams = payload.weight_grams
    yarn.length_meters = payload.length_meters
    yarn.notes = payload.notes or None
    yarn.link = payload.link or None
    yarn.is_ai_enhanced = payload.is_ai_enhanced


@router.get("", response_model=YarnPage)
async def list_yarns(
    page: int = Query(1, ge=1),
    favorite: bool | None = Query(None),
    db: AsyncSession = Depends(get_db),
    current_user: User = Depends(require_api_token),
) -> YarnPage:
    """List yarns for the current user, most recently updated first."""
    favorite_ids = await _favorite_yarn_ids(db, current_user.id)

    query = (
        select(Yarn)
        .where(Yarn.owner_id == current_user.id)
        .options(selectinload(Yarn.photos))
        .order_by(Yarn.updated_at.desc(), Yarn.id.desc())
    )
    if favorite is True:
        query = query.where(Yarn.id.in_(favorite_ids))
    elif favorite is False:
        query = query.where(Yarn.id.notin_(favorite_ids))

    offset = (page - 1) * API_PAGE_SIZE
    result = await db.execute(query.offset(offset).limit(API_PAGE_SIZE + 1))
    yarns = list(result.scalars().all())
    has_more = len(yarns) > API_PAGE_SIZE
    yarns = yarns[:API_PAGE_SIZE]

    items = [
        YarnListItemResponse(
            id=yarn.id,
            name=yarn.name,
            brand=yarn.brand,
            colorway=yarn.colorway,
            weight_category=yarn.weight_category,
            is_favorite=yarn.id in favorite_ids,
            updated_at=yarn.updated_at,
            preview_url=resolve_yarn_preview(yarn),
        )
        for yarn in yarns
    ]
    return YarnPage(items=items, page=page, per_page=API_PAGE_SIZE, has_more=has_more)


@router.get("/{yarn_id}", response_model=YarnResponse)
async def get_yarn(
    yarn_id: int,
    db: AsyncSession = Depends(get_db),
    current_user: User = Depends(require_api_token),
) -> YarnResponse:
    yarn = await _get_owned_yarn(db, yarn_id, current_user.id)
    favorite_ids = await _favorite_yarn_ids(db, current_user.id)
    return _serialize_yarn(yarn, is_favorite=yarn.id in favorite_ids)


@router.post("", response_model=YarnResponse, status_code=status.HTTP_201_CREATED)
async def create_yarn(
    payload: YarnWriteRequest,
    db: AsyncSession = Depends(get_db),
    current_user: User = Depends(require_api_token),
) -> YarnResponse:
    yarn = Yarn(owner_id=current_user.id)
    _apply_write_fields(yarn, payload)
    db.add(yarn)
    await db.flush()

    await create_audit_log(
        db,
        actor_user_id=current_user.id,
        entity_type="yarn",
        entity_id=yarn.id,
        action="created",
        details={"name": yarn.name, "brand": yarn.brand, "colorway": yarn.colorway},
    )
    await db.commit()
    # A fresh, eager-loaded fetch rather than db.refresh(): commit expires
    # relationship attributes too, and this async session can't satisfy a
    # lazy-load of yarn.photos/yarn.projects triggered by _serialize_yarn.
    yarn = await _get_owned_yarn(db, yarn.id, current_user.id)
    return _serialize_yarn(yarn, is_favorite=False)


@router.put("/{yarn_id}", response_model=YarnResponse)
async def update_yarn(
    yarn_id: int,
    payload: YarnWriteRequest,
    db: AsyncSession = Depends(get_db),
    current_user: User = Depends(require_api_token),
) -> YarnResponse:
    """Update a yarn. SNA-33: same conflict contract as `update_project` - see its
    docstring."""
    yarn = await _get_owned_yarn(db, yarn_id, current_user.id)

    if payload.expected_updated_at is not None and _differs(
        payload.expected_updated_at, yarn.updated_at
    ):
        favorite_ids = await _favorite_yarn_ids(db, current_user.id)
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail=_serialize_yarn(
                yarn, is_favorite=yarn.id in favorite_ids
            ).model_dump(mode="json"),
        )

    _apply_write_fields(yarn, payload)

    await create_audit_log(
        db,
        actor_user_id=current_user.id,
        entity_type="yarn",
        entity_id=yarn.id,
        action="updated",
        details={"name": yarn.name},
    )
    await db.commit()
    yarn = await _get_owned_yarn(db, yarn_id, current_user.id)

    favorite_ids = await _favorite_yarn_ids(db, current_user.id)
    return _serialize_yarn(yarn, is_favorite=yarn.id in favorite_ids)


@router.delete("/{yarn_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_yarn(
    yarn_id: int,
    db: AsyncSession = Depends(get_db),
    current_user: User = Depends(require_api_token),
) -> None:
    yarn = await _get_owned_yarn(db, yarn_id, current_user.id)
    filenames = [photo.filename for photo in yarn.photos]

    await create_audit_log(
        db,
        actor_user_id=current_user.id,
        entity_type="yarn",
        entity_id=yarn.id,
        action="deleted",
        details={"name": yarn.name},
    )
    await db.delete(yarn)
    await db.commit()

    for filename in filenames:
        try:
            delete_file(filename, yarn_id, subdir="yarns")
        except OSError as exc:
            logger.warning("Failed to remove yarn image file %s: %s", filename, exc)


@router.post(
    "/{yarn_id}/favorite", response_model=YarnResponse, status_code=status.HTTP_200_OK
)
async def favorite_yarn(
    yarn_id: int,
    db: AsyncSession = Depends(get_db),
    current_user: User = Depends(require_api_token),
) -> YarnResponse:
    yarn = await _get_owned_yarn(db, yarn_id, current_user.id)
    existing = await db.execute(
        select(user_favorite_yarns).where(
            user_favorite_yarns.c.user_id == current_user.id,
            user_favorite_yarns.c.yarn_id == yarn_id,
        )
    )
    if existing.first() is None:
        await db.execute(
            insert(user_favorite_yarns).values(user_id=current_user.id, yarn_id=yarn_id)
        )
        await db.commit()
        yarn = await _get_owned_yarn(db, yarn_id, current_user.id)
    return _serialize_yarn(yarn, is_favorite=True)


@router.delete(
    "/{yarn_id}/favorite", response_model=YarnResponse, status_code=status.HTTP_200_OK
)
async def unfavorite_yarn(
    yarn_id: int,
    db: AsyncSession = Depends(get_db),
    current_user: User = Depends(require_api_token),
) -> YarnResponse:
    yarn = await _get_owned_yarn(db, yarn_id, current_user.id)
    await db.execute(
        delete(user_favorite_yarns).where(
            user_favorite_yarns.c.user_id == current_user.id,
            user_favorite_yarns.c.yarn_id == yarn_id,
        )
    )
    await db.commit()
    yarn = await _get_owned_yarn(db, yarn_id, current_user.id)
    return _serialize_yarn(yarn, is_favorite=False)


@router.post(
    "/{yarn_id}/photos",
    response_model=YarnPhotoResponse,
    status_code=status.HTTP_201_CREATED,
)
async def upload_yarn_photo(
    yarn_id: int,
    file: UploadFile = File(...),
    db: AsyncSession = Depends(get_db),
    current_user: User = Depends(require_api_token),
) -> YarnPhotoResponse:
    yarn = await _get_owned_yarn(db, yarn_id, current_user.id)

    try:
        saved_name, original = await save_uploaded_image(file, yarn.id, subdir="yarns")
    except UploadTooLargeError as exc:
        raise HTTPException(
            status_code=status.HTTP_413_CONTENT_TOO_LARGE,
            detail="Uploaded file is too large",
        ) from exc
    except InvalidImageError as exc:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Uploaded file is not a supported image",
        ) from exc
    source_path = config.MEDIA_ROOT / "yarns" / str(yarn.id) / saved_name
    await create_thumbnail(source_path, yarn.id, subdir="yarns")
    if is_ocr_available():
        asyncio.create_task(
            precompute_ocr_for_media_file(
                file_path=source_path, kind="yarns", entity_id=yarn.id
            )
        )

    is_first_photo = len(yarn.photos) == 0
    photo = YarnImage(
        filename=saved_name,
        original_filename=original,
        alt_text=yarn.name,
        is_primary=is_first_photo,
        yarn_id=yarn.id,
    )
    db.add(photo)

    await create_audit_log(
        db,
        actor_user_id=current_user.id,
        entity_type="yarn",
        entity_id=yarn.id,
        action="photo_uploaded",
        details={"filename": original},
    )
    await db.commit()
    await db.refresh(photo)
    return _serialize_photo(photo, yarn.id)


@router.delete("/{yarn_id}/photos/{photo_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_yarn_photo(
    yarn_id: int,
    photo_id: int,
    db: AsyncSession = Depends(get_db),
    current_user: User = Depends(require_api_token),
) -> None:
    await _get_owned_yarn(db, yarn_id, current_user.id, with_photos=False)

    result = await db.execute(
        select(YarnImage).where(YarnImage.id == photo_id, YarnImage.yarn_id == yarn_id)
    )
    photo = result.scalar_one_or_none()
    if photo is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND, detail="Photo not found"
        )

    filename = photo.filename
    was_primary = photo.is_primary

    await create_audit_log(
        db,
        actor_user_id=current_user.id,
        entity_type="yarn",
        entity_id=yarn_id,
        action="photo_deleted",
        details={"filename": filename},
    )
    await db.delete(photo)
    await db.flush()

    if was_primary:
        next_photo_result = await db.execute(
            select(YarnImage)
            .where(YarnImage.yarn_id == yarn_id)
            .order_by(YarnImage.created_at)
            .limit(1)
        )
        next_photo = next_photo_result.scalar_one_or_none()
        if next_photo is not None:
            next_photo.is_primary = True

    await db.commit()

    try:
        delete_file(filename, yarn_id, subdir="yarns")
    except OSError as exc:
        logger.warning("Failed to remove yarn image file %s: %s", filename, exc)
