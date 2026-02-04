"""Yarn stash routes."""

import asyncio
import json
import logging
import re
from collections.abc import Iterable, Sequence
from dataclasses import dataclass
from datetime import UTC, datetime
from io import BytesIO
from pathlib import Path
from typing import Annotated

import httpx
from fastapi import (
    APIRouter,
    Depends,
    File,
    Form,
    HTTPException,
    Request,
    Response,
    UploadFile,
    status,
)
from fastapi.responses import HTMLResponse, JSONResponse, RedirectResponse
from PIL import Image as PilImage
from sqlalchemy import delete, func, insert, select, update
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm import selectinload

from stricknani.config import config
from stricknani.database import get_db
from stricknani.main import render_template
from stricknani.models import Project, User, Yarn, YarnImage, user_favorite_yarns
from stricknani.routes.auth import get_current_user, require_auth
from stricknani.utils.files import (
    build_import_filename,
    compute_checksum,
    compute_file_checksum,
    create_thumbnail,
    delete_file,
    get_file_url,
    get_thumbnail_url,
    save_bytes,
    save_uploaded_file,
)
from stricknani.utils.image_similarity import (
    SimilarityImage,
    build_similarity_image,
    compute_similarity_score,
)
from stricknani.utils.importer import (
    IMPORT_IMAGE_HEADERS,
    IMPORT_IMAGE_MAX_BYTES,
    IMPORT_IMAGE_MAX_COUNT,
    IMPORT_IMAGE_MIN_DIMENSION,
    IMPORT_IMAGE_SSIM_THRESHOLD,
    IMPORT_IMAGE_TIMEOUT,
    _is_allowed_import_image,
    _is_valid_import_url,
    filter_import_image_urls,
    trim_import_strings,
)
from stricknani.utils.markdown import render_markdown
from stricknani.utils.wayback import (
    _should_request_archive,
    store_wayback_snapshot,
)

router: APIRouter = APIRouter(prefix="/yarn", tags=["yarn"])


@dataclass
class _ImportedSimilarity:
    similarity: SimilarityImage
    image: YarnImage
    filename: str
    is_primary: bool


async def _load_existing_yarn_checksums(
    db: AsyncSession, yarn_id: int
) -> dict[str, YarnImage]:
    """Return existing image checksums for a yarn."""
    result = await db.execute(select(YarnImage).where(YarnImage.yarn_id == yarn_id))
    images = result.scalars().all()
    checksums: dict[str, YarnImage] = {}
    for image in images:
        file_path = config.MEDIA_ROOT / "yarns" / str(yarn_id) / image.filename
        checksum = compute_file_checksum(file_path)
        if checksum:
            checksums.setdefault(checksum, image)
    return checksums


async def _import_yarn_images_from_urls(
    db: AsyncSession,
    yarn: Yarn,
    image_urls: Sequence[str],
    *,
    primary_url: str | None = None,
) -> int:
    """Download and attach imported images to a yarn."""
    if not image_urls:
        return 0

    logger = logging.getLogger("stricknani.imports")
    imported = 0
    existing_checksums = await _load_existing_yarn_checksums(db, yarn.id)
    seen_checksums: set[str] = set()
    imported_similarities: list[_ImportedSimilarity] = []

    headers = dict(IMPORT_IMAGE_HEADERS)
    if yarn.link:
        headers["Referer"] = yarn.link

    async with httpx.AsyncClient(
        timeout=IMPORT_IMAGE_TIMEOUT,
        follow_redirects=True,
        headers=headers,
    ) as client:
        for image_url in image_urls:
            if imported >= IMPORT_IMAGE_MAX_COUNT:
                break
            if not _is_valid_import_url(image_url):
                logger.info("Skipping invalid image URL: %s", image_url)
                continue

            try:
                response = await client.get(image_url)
                response.raise_for_status()
            except httpx.HTTPError as exc:
                logger.warning("Failed to download image %s: %s", image_url, exc)
                continue

            content_type = response.headers.get("content-type")
            if not _is_allowed_import_image(content_type, image_url):
                logger.info("Skipping non-image URL: %s", image_url)
                continue

            if not response.content or len(response.content) > IMPORT_IMAGE_MAX_BYTES:
                logger.info("Skipping empty or large image %s", image_url)
                continue

            checksum = compute_checksum(response.content)
            if checksum in existing_checksums or checksum in seen_checksums:
                logger.info("Skipping duplicate image %s", image_url)
                if primary_url and image_url == primary_url:
                    existing = existing_checksums.get(checksum)
                    if existing and not existing.is_primary:
                        existing.is_primary = True
                continue

            try:
                with PilImage.open(BytesIO(response.content)) as img:
                    width, height = img.size
                    if (
                        width < IMPORT_IMAGE_MIN_DIMENSION
                        or height < IMPORT_IMAGE_MIN_DIMENSION
                    ):
                        logger.info(
                            "Skipping small image %s (%sx%s)",
                            image_url,
                            width,
                            height,
                        )
                        continue
                    similarity = build_similarity_image(img)
            except Exception as exc:
                logger.info("Skipping unreadable image %s: %s", image_url, exc)
                continue

            skip_thumbnail = False
            to_remove: list[_ImportedSimilarity] = []
            for candidate in imported_similarities:
                score = compute_similarity_score(candidate.similarity, similarity)
                if score is None or score < IMPORT_IMAGE_SSIM_THRESHOLD:
                    continue
                if similarity.pixels <= candidate.similarity.pixels:
                    logger.info(
                        "Skipping thumbnail image %s (ssim %.3f)",
                        image_url,
                        score,
                    )
                    skip_thumbnail = True
                    break
                to_remove.append(candidate)

            if skip_thumbnail:
                continue

            removed_primary = any(entry.is_primary for entry in to_remove)
            for entry in to_remove:
                await db.delete(entry.image)
                delete_file(entry.filename, yarn.id, subdir="yarns")
                imported_similarities.remove(entry)
                imported = max(0, imported - 1)

            original_filename = build_import_filename(image_url, content_type)
            filename = ""
            try:
                filename, original_filename = save_bytes(
                    response.content, original_filename, yarn.id, subdir="yarns"
                )
                file_path = config.MEDIA_ROOT / "yarns" / str(yarn.id) / filename
                await create_thumbnail(file_path, yarn.id, subdir="yarns")
            except Exception as exc:
                if filename:
                    delete_file(filename, yarn.id, subdir="yarns")
                logger.warning("Failed to store image %s: %s", image_url, exc)
                continue

            if primary_url:
                is_primary = image_url == primary_url
            else:
                # If no primary_url is provided, the first imported photo becomes
                # primary if there are no existing photos.
                is_primary = imported == 0 and not yarn.photos

            if removed_primary:
                is_primary = True

            photo = YarnImage(
                filename=filename,
                original_filename=original_filename,
                alt_text=yarn.name or original_filename,
                yarn_id=yarn.id,
                is_primary=is_primary,
            )
            db.add(photo)
            imported += 1
            seen_checksums.add(checksum)
            imported_similarities.append(
                _ImportedSimilarity(
                    similarity=similarity,
                    image=photo,
                    filename=filename,
                    is_primary=is_primary,
                )
            )

    return imported


def _parse_import_image_urls(raw: list[str] | str | None) -> list[str]:
    """Parse image URLs sent from the import form."""
    if not raw:
        return []

    if isinstance(raw, list):
        urls = []
        for item in raw:
            if not item:
                continue
            try:
                data = json.loads(item)
                if isinstance(data, list):
                    urls.extend([str(u).strip() for u in data if u])
                else:
                    urls.append(str(data).strip())
            except (ValueError, TypeError):
                urls.append(item.strip())
        return [u for u in urls if u.startswith("http")]

    try:
        data = json.loads(raw)
        if isinstance(data, list):
            return [str(item).strip() for item in data if str(item).strip()]
    except (ValueError, TypeError):
        pass

    if raw and raw.startswith("http"):
        return [s.strip() for s in raw.split(",") if s.strip()]
    return []


def _strip_wrapping_quotes(value: str) -> str:
    """Strip wrapping single or double quotes from a search token."""

    cleaned = value.strip()
    if len(cleaned) >= 2 and cleaned[0] == cleaned[-1] and cleaned[0] in {'"', "'"}:
        return cleaned[1:-1].strip()
    return cleaned


def _extract_search_token(search: str, prefix: str) -> tuple[str | None, str]:
    """Extract a prefix token (with optional quotes) and remaining text."""

    pattern = rf"(?i)(?:^|\s){re.escape(prefix)}(?:\"([^\"]+)\"|'([^']+)'|(\S+))"
    match = re.search(pattern, search)
    if not match:
        return None, search
    token = next((group for group in match.groups() if group), None)
    if token is None:
        return None, search
    start, end = match.span()
    remaining = (search[:start] + search[end:]).strip()
    return token.strip(), remaining


def _parse_optional_int(field_name: str, value: str | None) -> int | None:
    """Parse an optional integer form field."""

    if value is None:
        return None

    cleaned = str(value).strip()
    if not cleaned:
        return None

    try:
        return int(cleaned)
    except ValueError as exc:  # pragma: no cover - simple validation guard
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=f"Invalid value for {field_name}",
        ) from exc


def _resolve_preview(yarn: Yarn) -> str | None:
    """Return the thumbnail URL for the first photo, if any."""

    if not yarn.photos:
        return None
    first = yarn.photos[0]
    return get_thumbnail_url(first.filename, yarn.id, subdir="yarns")


def _resolve_project_preview(project: Project) -> dict[str, str | None]:
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


def _get_photo_dimensions(yarn_id: int, filename: str) -> tuple[int | None, int | None]:
    image_path = config.MEDIA_ROOT / "yarns" / str(yarn_id) / filename
    if not image_path.exists():
        return None, None
    try:
        with PilImage.open(image_path) as img:
            width, height = img.size
            return int(width), int(height)
    except (OSError, ValueError):
        return None, None


def _serialize_photos(yarn: Yarn) -> list[dict[str, object]]:
    """Prepare photo metadata for templates."""

    payload: list[dict[str, object]] = []
    has_seen_primary = False

    # Sort photos: primary first, then by ID
    sorted_photos = sorted(yarn.photos, key=lambda p: (not p.is_primary, p.id))

    for photo in sorted_photos:
        width, height = _get_photo_dimensions(yarn.id, photo.filename)

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

    # If no primary photo seen but we have photos, mark the first as primary
    if not has_seen_primary and payload:
        payload[0]["is_primary"] = True

    return payload


def _serialize_yarn_cards(
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
            "preview_url": _resolve_preview(yarn),
        }
        for yarn in yarns
    ]


@router.post("/import")
async def import_yarn(
    url: Annotated[str, Form()],
    current_user: User = Depends(require_auth),
) -> JSONResponse:
    """Import yarn data from URL."""
    import logging

    from stricknani.utils.importer import (
        GarnstudioPatternImporter,
        PatternImporter,
        _is_garnstudio_url,
    )

    logger = logging.getLogger(__name__)

    if not url or not url.strip():
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="URL is required",
        )

    url = url.strip()
    if not url.startswith(("http://", "https://")):
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Invalid URL format",
        )

    try:
        importer: PatternImporter
        if _is_garnstudio_url(url):
            importer = GarnstudioPatternImporter(url)
        else:
            importer = PatternImporter(url)

        data = await importer.fetch_and_parse()
        data = trim_import_strings(data)

        # Map pattern data to yarn fields
        recommended_needles = data.get("needles")
        if isinstance(recommended_needles, str):
            recommended_needles = recommended_needles.strip() or None

        yarn_data = {
            "name": data.get("yarn") or data.get("title"),
            "brand": data.get("brand"),
            "colorway": data.get("colorway"),
            "weight_grams": data.get("weight_grams"),
            "length_meters": data.get("length_meters"),
            "weight_category": data.get("weight_category"),
            "fiber_content": data.get("fiber_content"),
            "recommended_needles": recommended_needles,
            "description": data.get("notes") or data.get("comment"),
            "link": url,
            "image_urls": data.get("image_urls", [])[:5],
            "notes": None,
            "is_ai_enhanced": False,
        }

        image_urls = yarn_data.get("image_urls")
        if isinstance(image_urls, list) and image_urls:
            yarn_data["image_urls"] = await filter_import_image_urls(
                image_urls,
                referer=url,
                limit=5,
            )

        return JSONResponse(content=yarn_data)

    except Exception as e:
        logger.error(f"Yarn import failed: {e}", exc_info=True)
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=f"Failed to import: {str(e)}",
        ) from e


@router.get("/search-suggestions")
async def search_suggestions(
    type: str,
    q: str = "",
    db: AsyncSession = Depends(get_db),
    current_user: User = Depends(require_auth),
) -> JSONResponse:
    """Return search suggestions for brands."""
    if type == "brand":
        result = await db.execute(
            select(Yarn.brand)
            .where(
                Yarn.owner_id == current_user.id,
                Yarn.brand.ilike(f"%{q}%"),
            )
            .distinct()
            .order_by(Yarn.brand)
            .limit(10)
        )
        suggestions = [row[0] for row in result if row[0]]
    else:
        suggestions = []

    return JSONResponse(suggestions)


@router.get("/", response_class=HTMLResponse)
async def list_yarns(
    request: Request,
    db: AsyncSession = Depends(get_db),
    current_user: User | None = Depends(get_current_user),
    search: str | None = None,
    brand: str | None = None,
) -> Response:
    """List yarn stash for the current user."""

    if not current_user:
        return RedirectResponse(url="/login", status_code=status.HTTP_303_SEE_OTHER)

    # Eager load favorites for the current user
    await db.refresh(current_user, ["favorite_yarns"])

    query = (
        select(Yarn)
        .where(Yarn.owner_id == current_user.id)
        .options(selectinload(Yarn.photos), selectinload(Yarn.projects))
        .order_by(Yarn.created_at.desc())
    )

    if search:
        extracted_brand, remaining = _extract_search_token(search, "brand:")
        if extracted_brand:
            brand = extracted_brand
            search = remaining or None

    if brand:
        brand = _strip_wrapping_quotes(brand)
        query = query.where(Yarn.brand.ilike(f"%{brand}%"))

    if search:
        ilike = f"%{search}%"
        query = query.where(
            Yarn.name.ilike(ilike)
            | Yarn.brand.ilike(ilike)
            | Yarn.colorway.ilike(ilike)
            | Yarn.dye_lot.ilike(ilike)
            | Yarn.fiber_content.ilike(ilike)
        )

    result = await db.execute(query)
    yarns = result.scalars().unique().all()
    favorite_ids = {yarn.id for yarn in current_user.favorite_yarns}
    yarns = sorted(
        yarns,
        key=lambda yarn: (yarn.id not in favorite_ids, (yarn.name or "").casefold()),
    )

    if request.headers.get("HX-Request"):
        return await render_template(
            "yarn/_list_partial.html",
            request,
            {
                "current_user": current_user,
                "yarns": _serialize_yarn_cards(yarns, current_user),
                "search": search or "",
                "selected_brand": brand,
            },
        )

    if request.headers.get("accept") == "application/json":
        return JSONResponse(_serialize_yarn_cards(yarns, current_user))

    return await render_template(
        "yarn/list.html",
        request,
        {
            "current_user": current_user,
            "yarns": _serialize_yarn_cards(yarns, current_user),
            "search": search or "",
            "selected_brand": brand,
        },
    )


@router.get("/new", response_class=HTMLResponse)
async def new_yarn(
    request: Request,
    current_user: User = Depends(require_auth),
) -> HTMLResponse:
    """Show creation form."""

    return await render_template(
        "yarn/form.html",
        request,
        {
            "current_user": current_user,
            "yarn": None,
        },
    )


async def _handle_photo_uploads(
    files: list[UploadFile | str],
    yarn: Yarn,
    db: AsyncSession,
) -> None:
    """Persist uploaded photos for a yarn."""

    for upload in files:
        if isinstance(upload, str):
            continue
        if not upload.filename:
            continue
        saved_name, original = await save_uploaded_file(
            upload,
            yarn.id,
            subdir="yarns",
        )
        source_path = config.MEDIA_ROOT / "yarns" / str(yarn.id) / saved_name
        await create_thumbnail(source_path, yarn.id, subdir="yarns")
        photo = YarnImage(
            filename=saved_name,
            original_filename=original,
            alt_text=yarn.name,
        )
        yarn.photos.append(photo)
    await db.flush()


@router.post("/", response_class=Response)
async def create_yarn(
    request: Request,
    name: Annotated[str, Form()],
    description: Annotated[str | None, Form()] = None,
    brand: Annotated[str | None, Form()] = None,
    colorway: Annotated[str | None, Form()] = None,
    dye_lot: Annotated[str | None, Form()] = None,
    fiber_content: Annotated[str | None, Form()] = None,
    weight_category: Annotated[str | None, Form()] = None,
    recommended_needles: Annotated[str | None, Form()] = None,
    weight_grams: Annotated[str | None, Form()] = None,
    length_meters: Annotated[str | None, Form()] = None,
    notes: Annotated[str | None, Form()] = None,
    link: Annotated[str | None, Form()] = None,
    import_image_urls: Annotated[list[str] | None, Form()] = None,
    import_primary_image_url: Annotated[str | None, Form()] = None,
    photos: Annotated[list[UploadFile | str] | None, File()] = None,
    archive_on_save: Annotated[str | None, Form()] = None,
    is_ai_enhanced: Annotated[bool | None, Form()] = False,
    db: AsyncSession = Depends(get_db),
    current_user: User = Depends(require_auth),
) -> Response:
    """Create a yarn entry."""
    if photos is None:
        photos = []

    yarn = Yarn(
        name=name.strip(),
        description=description.strip() if description else None,
        brand=brand.strip() if brand else None,
        colorway=colorway.strip() if colorway else None,
        dye_lot=dye_lot.strip() if dye_lot else None,
        fiber_content=fiber_content.strip() if fiber_content else None,
        weight_category=weight_category.strip() if weight_category else None,
        recommended_needles=(
            recommended_needles.strip() if recommended_needles else None
        ),
        weight_grams=_parse_optional_int("weight_grams", weight_grams),
        length_meters=_parse_optional_int("length_meters", length_meters),
        notes=notes.strip() if notes else None,
        link=link.strip() if link else None,
        owner_id=current_user.id,
        is_ai_enhanced=bool(is_ai_enhanced),
    )
    yarn.photos = []
    db.add(yarn)
    await db.flush()

    await _handle_photo_uploads(photos, yarn, db)

    # Handle imported image URLs
    if import_image_urls:
        urls = _parse_import_image_urls(import_image_urls)
        if urls:
            await _import_yarn_images_from_urls(
                db, yarn, urls, primary_url=import_primary_image_url
            )

    await db.commit()
    await db.refresh(yarn)

    if (
        config.FEATURE_WAYBACK_ENABLED
        and yarn.link
        and _should_request_archive(archive_on_save)
    ):
        asyncio.create_task(store_wayback_snapshot(Yarn, yarn.id, yarn.link))

    return RedirectResponse(
        url=f"/yarn/{yarn.id}?toast=yarn_created",
        status_code=status.HTTP_303_SEE_OTHER,
    )


@router.get("/{yarn_id}", response_class=HTMLResponse)
async def yarn_detail(
    yarn_id: int,
    request: Request,
    db: AsyncSession = Depends(get_db),
    current_user: User | None = Depends(get_current_user),
) -> Response:
    """Show yarn details."""

    if not current_user:
        return RedirectResponse(url="/login", status_code=status.HTTP_303_SEE_OTHER)

    await db.refresh(current_user, ["favorite_yarns"])
    result = await db.execute(
        select(Yarn)
        .where(Yarn.id == yarn_id)
        .options(
            selectinload(Yarn.photos),
            selectinload(Yarn.projects).selectinload(Project.images),
        )
    )
    yarn = result.scalar_one_or_none()

    if yarn is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND)

    if yarn.owner_id != current_user.id:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN, detail="Not authorized"
        )

    is_favorite = any(entry.id == yarn.id for entry in current_user.favorite_yarns)

    # Check for stale archive request (self-healing)
    if (
        config.FEATURE_WAYBACK_ENABLED
        and yarn.archive_pending
        and yarn.link_archive_requested_at
        and yarn.link
    ):
        # Handle naive datetime from SQLite
        requested_at = yarn.link_archive_requested_at
        if requested_at.tzinfo is None:
            requested_at = requested_at.replace(tzinfo=UTC)

        elapsed = datetime.now(UTC) - requested_at
        if elapsed.total_seconds() > 900:  # 15 minutes
            # Reset timestamp to now so we don't spam checks immediately
            yarn.link_archive_requested_at = datetime.now(UTC)
            await db.commit()
            # Retry the snapshot request
            asyncio.create_task(
                store_wayback_snapshot(Yarn, yarn.id, yarn.link)
            )

    return await render_template(
        "yarn/detail.html",
        request,
        {
            "current_user": current_user,
            "yarn": yarn,
            "is_favorite": is_favorite,
            "is_ai_enhanced": yarn.is_ai_enhanced,
            "description_html": render_markdown(yarn.description)
            if yarn.description
            else None,
            "notes_html": render_markdown(yarn.notes) if yarn.notes else None,
            "preview_url": _resolve_preview(yarn),
            "photos": _serialize_photos(yarn),
            "linked_projects": [
                {
                    "id": project.id,
                    "name": project.name,
                    "category": project.category,
                    **_resolve_project_preview(project),
                }
                for project in yarn.projects
            ],
            "metadata": {
                "created": yarn.created_at.strftime("%Y-%m-%d"),
                "updated": yarn.updated_at.strftime("%Y-%m-%d"),
                "photo_count": len(yarn.photos),
                "project_count": len(yarn.projects),
            },
        },
    )


@router.get("/{yarn_id}/edit", response_class=HTMLResponse)
async def edit_yarn(
    yarn_id: int,
    request: Request,
    db: AsyncSession = Depends(get_db),
    current_user: User = Depends(require_auth),
) -> HTMLResponse:
    """Show edit form for a yarn."""

    result = await db.execute(
        select(Yarn)
        .where(Yarn.id == yarn_id)
        .options(
            selectinload(Yarn.photos),
            selectinload(Yarn.projects).selectinload(Project.images),
        )
    )
    yarn = result.scalar_one_or_none()

    if yarn is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND)

    if yarn.owner_id != current_user.id:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN, detail="Not authorized"
        )

    return await render_template(
        "yarn/form.html",
        request,
        {
            "current_user": current_user,
            "yarn": yarn,
            "photos": _serialize_photos(yarn),
            "is_ai_enhanced": yarn.is_ai_enhanced,
        },
    )


@router.post("/{yarn_id}/edit", response_class=Response)
async def update_yarn(
    yarn_id: int,
    request: Request,
    name: Annotated[str, Form()],
    description: Annotated[str | None, Form()] = None,
    brand: Annotated[str | None, Form()] = None,
    colorway: Annotated[str | None, Form()] = None,
    dye_lot: Annotated[str | None, Form()] = None,
    fiber_content: Annotated[str | None, Form()] = None,
    weight_category: Annotated[str | None, Form()] = None,
    recommended_needles: Annotated[str | None, Form()] = None,
    weight_grams: Annotated[str | None, Form()] = None,
    length_meters: Annotated[str | None, Form()] = None,
    notes: Annotated[str | None, Form()] = None,
    link: Annotated[str | None, Form()] = None,
    import_image_urls: Annotated[list[str] | None, Form()] = None,
    import_primary_image_url: Annotated[str | None, Form()] = None,
    new_photos: Annotated[list[UploadFile | str] | None, File()] = None,
    archive_on_save: Annotated[str | None, Form()] = None,
    is_ai_enhanced: Annotated[bool | None, Form()] = False,
    db: AsyncSession = Depends(get_db),
    current_user: User = Depends(require_auth),
) -> Response:
    """Update a yarn entry."""
    if new_photos is None:
        new_photos = []

    result = await db.execute(
        select(Yarn)
        .where(Yarn.id == yarn_id)
        .options(
            selectinload(Yarn.photos),
            selectinload(Yarn.projects).selectinload(Project.images),
        )
    )
    yarn = result.scalar_one_or_none()

    if yarn is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND)

    if yarn.owner_id != current_user.id:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN, detail="Not authorized"
        )

    yarn.name = name.strip()
    yarn.description = description.strip() if description else None
    yarn.brand = brand.strip() if brand else None
    yarn.colorway = colorway.strip() if colorway else None
    yarn.dye_lot = dye_lot.strip() if dye_lot else None
    yarn.fiber_content = fiber_content.strip() if fiber_content else None
    yarn.weight_category = weight_category.strip() if weight_category else None
    yarn.recommended_needles = (
        recommended_needles.strip() if recommended_needles else None
    )
    yarn.weight_grams = _parse_optional_int("weight_grams", weight_grams)
    yarn.length_meters = _parse_optional_int("length_meters", length_meters)
    yarn.notes = notes.strip() if notes else None
    yarn.link = link.strip() if link else None
    yarn.is_ai_enhanced = bool(is_ai_enhanced)

    await _handle_photo_uploads(new_photos, yarn, db)

    # Handle imported image URLs
    if import_image_urls:
        urls = _parse_import_image_urls(import_image_urls)
        if urls:
            await _import_yarn_images_from_urls(
                db, yarn, urls, primary_url=import_primary_image_url
            )

    await db.commit()
    await db.refresh(yarn)

    if (
        config.FEATURE_WAYBACK_ENABLED
        and yarn.link
        and _should_request_archive(archive_on_save)
    ):
        asyncio.create_task(store_wayback_snapshot(Yarn, yarn.id, yarn.link))

    return RedirectResponse(
        url=f"/yarn/{yarn.id}?toast=yarn_updated",
        status_code=status.HTTP_303_SEE_OTHER,
    )


@router.post("/{yarn_id}/photos")
async def upload_yarn_photo(
    yarn_id: int,
    file: UploadFile = File(...),
    db: AsyncSession = Depends(get_db),
    current_user: User = Depends(require_auth),
) -> JSONResponse:
    """Upload a photo for a yarn entry."""
    result = await db.execute(select(Yarn).where(Yarn.id == yarn_id))
    yarn = result.scalar_one_or_none()

    if yarn is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND)

    if yarn.owner_id != current_user.id:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN, detail="Not authorized"
        )

    # Save file
    saved_name, original = await save_uploaded_file(file, yarn_id, subdir="yarns")

    # Create thumbnail
    source_path = config.MEDIA_ROOT / "yarns" / str(yarn_id) / saved_name
    await create_thumbnail(source_path, yarn_id, subdir="yarns")

    # Check if a primary image already exists
    count_result = await db.execute(
        select(func.count(YarnImage.id)).where(
            YarnImage.yarn_id == yarn_id,
            YarnImage.is_primary.is_(True),
        )
    )
    has_primary = (count_result.scalar() or 0) > 0

    # Create database record
    photo = YarnImage(
        filename=saved_name,
        original_filename=original,
        alt_text=yarn.name or original,
        yarn_id=yarn_id,
        is_primary=not has_primary,
    )
    db.add(photo)
    await db.commit()
    await db.refresh(photo)

    width, height = _get_photo_dimensions(yarn_id, saved_name)

    return JSONResponse(
        {
            "id": photo.id,
            "thumbnail_url": get_thumbnail_url(saved_name, yarn_id, subdir="yarns"),
            "full_url": get_file_url(saved_name, yarn_id, subdir="yarns"),
            "alt_text": photo.alt_text,
            "is_primary": photo.is_primary,
            "width": width,
            "height": height,
        }
    )


@router.post("/{yarn_id}/retry-archive", response_class=Response)
async def retry_yarn_archive(
    yarn_id: int,
    db: AsyncSession = Depends(get_db),
    current_user: User = Depends(require_auth),
) -> Response:
    """Retry requesting a wayback snapshot for a yarn."""
    result = await db.execute(select(Yarn).where(Yarn.id == yarn_id))
    yarn = result.scalar_one_or_none()

    if yarn is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND)

    if yarn.owner_id != current_user.id:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN, detail="Not authorized"
        )

    if config.FEATURE_WAYBACK_ENABLED and yarn.link:
        yarn.link_archive_failed = False
        yarn.link_archive_requested_at = datetime.now(UTC)
        await db.commit()
        asyncio.create_task(store_wayback_snapshot(Yarn, yarn.id, yarn.link))

    return RedirectResponse(
        url=f"/yarn/{yarn.id}",
        status_code=status.HTTP_303_SEE_OTHER,
    )


@router.post("/{yarn_id}/favorite", response_class=Response)
async def toggle_favorite(
    yarn_id: int,
    request: Request,
    variant: str = "card",
    db: AsyncSession = Depends(get_db),
    current_user: User = Depends(require_auth),
) -> Response:
    """Toggle favorite status for a yarn."""

    # Check if yarn exists
    result = await db.execute(select(Yarn).where(Yarn.id == yarn_id))
    yarn = result.scalar_one_or_none()
    if not yarn:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND)

    # Check if already favorited
    stmt = select(user_favorite_yarns).where(
        user_favorite_yarns.c.user_id == current_user.id,
        user_favorite_yarns.c.yarn_id == yarn_id,
    )
    result = await db.execute(stmt)
    existing = result.first()

    is_favorite = False
    if existing:
        # Remove favorite
        await db.execute(
            delete(user_favorite_yarns).where(
                user_favorite_yarns.c.user_id == current_user.id,
                user_favorite_yarns.c.yarn_id == yarn_id,
            )
        )
    else:
        # Add favorite
        await db.execute(
            insert(user_favorite_yarns).values(
                user_id=current_user.id,
                yarn_id=yarn_id,
            )
        )
        is_favorite = True

    await db.commit()

    # Return partial for HTMX
    if request.headers.get("HX-Request"):
        if variant == "profile" and not is_favorite:
            return HTMLResponse(content="")

        return await render_template(
            "yarn/_favorite_toggle.html",
            request,
            {"yarn_id": yarn_id, "is_favorite": is_favorite, "variant": variant},
        )

    return RedirectResponse(
        url=request.headers.get("referer") or "/yarn",
        status_code=status.HTTP_303_SEE_OTHER,
    )


@router.post("/{yarn_id}/photos/{photo_id}/promote", response_class=Response)
async def promote_yarn_photo(
    yarn_id: int,
    photo_id: int,
    db: AsyncSession = Depends(get_db),
    current_user: User = Depends(require_auth),
) -> Response:
    """Promote a photo to be the primary image for a yarn."""
    result = await db.execute(select(Yarn).where(Yarn.id == yarn_id))
    yarn = result.scalar_one_or_none()

    if yarn is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND)

    if yarn.owner_id != current_user.id:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN, detail="Not authorized"
        )

    # Set all photos for this yarn to non-primary
    await db.execute(
        update(YarnImage).where(YarnImage.yarn_id == yarn_id).values(is_primary=False)
    )

    # Set selected photo to primary
    await db.execute(
        update(YarnImage)
        .where(YarnImage.id == photo_id, YarnImage.yarn_id == yarn_id)
        .values(is_primary=True)
    )

    await db.commit()
    return Response(status_code=status.HTTP_204_NO_CONTENT)


@router.post("/{yarn_id}/delete", response_class=Response)
async def delete_yarn(
    yarn_id: int,
    request: Request,
    db: AsyncSession = Depends(get_db),
    current_user: User = Depends(require_auth),
) -> Response:
    """Delete a yarn and its photos."""

    result = await db.execute(
        select(Yarn).where(Yarn.id == yarn_id).options(selectinload(Yarn.photos))
    )
    yarn = result.scalar_one_or_none()

    if yarn is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND)

    if yarn.owner_id != current_user.id:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN, detail="Not authorized"
        )

    for photo in list(yarn.photos):
        delete_file(photo.filename, yarn.id, subdir="yarns")
    await db.delete(yarn)
    await db.commit()

    if request.headers.get("HX-Request"):
        # Check if any yarns remain
        result = await db.execute(
            select(func.count(Yarn.id)).where(Yarn.owner_id == current_user.id)
        )
        count = result.scalar() or 0

        if count == 0:
            response = await render_template(
                "yarn/_empty_state.html",
                request,
                {"current_user": current_user},
            )
            response.headers["HX-Retarget"] = "#yarn-content"
            response.headers["HX-Reswap"] = "innerHTML"
            return response

        return Response(status_code=status.HTTP_200_OK)

    return RedirectResponse(
        url="/yarn?toast=yarn_deleted",
        status_code=status.HTTP_303_SEE_OTHER,
    )


@router.post("/{yarn_id}/photos/{photo_id}/delete", response_class=Response)
async def delete_yarn_photo(
    yarn_id: int,
    photo_id: int,
    request: Request,
    db: AsyncSession = Depends(get_db),
    current_user: User = Depends(require_auth),
) -> Response:
    """Remove a specific yarn photo."""

    result = await db.execute(
        select(Yarn).where(Yarn.id == yarn_id).options(selectinload(Yarn.photos))
    )
    yarn = result.scalar_one_or_none()

    if yarn is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND)

    if yarn.owner_id != current_user.id:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN, detail="Not authorized"
        )

    target = next((p for p in yarn.photos if p.id == photo_id), None)
    if target is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND)

    delete_file(target.filename, yarn.id, subdir="yarns")
    await db.delete(target)
    await db.commit()

    if (
        request.headers.get("accept") == "application/json"
        or request.headers.get("content-type") == "application/json"
    ):
        return Response(status_code=status.HTTP_200_OK)

    return RedirectResponse(
        url=f"/yarn/{yarn.id}",
        status_code=status.HTTP_303_SEE_OTHER,
    )
