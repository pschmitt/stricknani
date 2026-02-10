"""Yarn stash routes."""

import asyncio
import logging
from datetime import UTC, datetime
from typing import Annotated, Any

import anyio
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
from sqlalchemy import delete, func, insert, select, update
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm import selectinload

from stricknani.config import config
from stricknani.database import get_db
from stricknani.models import Project, User, Yarn, YarnImage, user_favorite_yarns
from stricknani.routes.auth import get_current_user, require_auth
from stricknani.services.audit import (
    build_field_changes,
    create_audit_log,
    list_audit_logs,
    serialize_audit_log,
)
from stricknani.services.yarn import (
    get_yarn_photo_dimensions,
    import_yarn_images_from_urls,
    resolve_project_preview,
    resolve_yarn_preview,
    serialize_yarn_cards,
    serialize_yarn_photos,
)
from stricknani.utils.ai_provider import has_ai_api_key
from stricknani.utils.files import (
    create_thumbnail,
    delete_file,
    get_file_url,
    get_thumbnail_url,
    save_uploaded_file,
)
from stricknani.utils.importer import (
    filter_import_image_urls,
    trim_import_strings,
)
from stricknani.utils.markdown import render_markdown
from stricknani.utils.ocr import is_ocr_available, precompute_ocr_for_media_file
from stricknani.utils.search_tokens import (
    extract_search_token,
    parse_import_image_urls,
    strip_wrapping_quotes,
)
from stricknani.utils.wayback import (
    _should_request_archive,
    build_wayback_fallback_url,
    store_wayback_snapshot,
)
from stricknani.web.templating import render_template

router: APIRouter = APIRouter(prefix="/yarn", tags=["yarn"])
logger = logging.getLogger(__name__)


def _parse_import_image_urls(raw: list[str] | str | None) -> list[str]:
    return parse_import_image_urls(raw)


def _strip_wrapping_quotes(value: str) -> str:
    return strip_wrapping_quotes(value)


def _extract_search_token(search: str, prefix: str) -> tuple[str | None, str]:
    return extract_search_token(search, prefix)


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


def _yarn_audit_snapshot(yarn: Yarn) -> dict[str, object]:
    return {
        "name": yarn.name,
        "description": yarn.description,
        "brand": yarn.brand,
        "colorway": yarn.colorway,
        "dye_lot": yarn.dye_lot,
        "fiber_content": yarn.fiber_content,
        "weight_category": yarn.weight_category,
        "recommended_needles": yarn.recommended_needles,
        "weight_grams": yarn.weight_grams,
        "length_meters": yarn.length_meters,
        "notes": yarn.notes,
        "link": yarn.link,
        "is_ai_enhanced": yarn.is_ai_enhanced,
        "photo_count": len(yarn.photos),
    }


@router.post("/import")
async def import_yarn(
    import_type: Annotated[str, Form(alias="type")] = "url",
    url: Annotated[str | None, Form()] = None,
    text: Annotated[str | None, Form()] = None,
    file: UploadFile | None = File(default=None),
    files: Annotated[list[UploadFile] | None, File()] = None,
    use_ai: Annotated[bool, Form()] = False,
    current_user: User = Depends(require_auth),
) -> JSONResponse:
    """Import yarn data from URL, file, or text."""
    import logging

    from stricknani.utils.importer import (
        GarnstudioPatternImporter,
        PatternImporter,
        is_garnstudio_url,
    )

    logger = logging.getLogger(__name__)

    try:
        data: dict[str, Any] = {}
        source_url = None

        selected_file = file
        if files:
            selected_file = files[0]

        if import_type == "url" and selected_file is not None:
            import_type = "file"

        if import_type == "url":
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

            source_url = url
            importer: PatternImporter
            if is_garnstudio_url(url):
                importer = GarnstudioPatternImporter(url)
            else:
                importer = PatternImporter(url)

            data = await importer.fetch_and_parse()

        elif import_type == "file":
            if not selected_file:
                raise HTTPException(
                    status_code=status.HTTP_400_BAD_REQUEST,
                    detail="File is required",
                )

            # Read file content
            content_bytes = await selected_file.read()
            filename = selected_file.filename or "unknown"

            from stricknani.importing.extractors.ai import OPENAI_AVAILABLE, AIExtractor
            from stricknani.importing.models import ContentType, RawContent

            use_ai_enabled = config.FEATURE_AI_IMPORT_ENABLED and bool(has_ai_api_key())

            if not use_ai_enabled or not OPENAI_AVAILABLE:
                raise HTTPException(
                    status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
                    detail="AI analysis requires a configured AI provider API key",
                )

            content_type = ContentType.UNKNOWN
            if selected_file.content_type:
                if selected_file.content_type.startswith("image/"):
                    content_type = ContentType.IMAGE
                elif selected_file.content_type == "application/pdf":
                    content_type = ContentType.PDF
                elif selected_file.content_type.startswith("text/"):
                    content_type = ContentType.TEXT

            if content_type == ContentType.UNKNOWN:
                # Fallback based on extension
                if filename.lower().endswith(".pdf"):
                    content_type = ContentType.PDF
                elif filename.lower().endswith((".jpg", ".jpeg", ".png", ".webp")):
                    content_type = ContentType.IMAGE
                else:
                    content_type = ContentType.TEXT

            raw_content = RawContent(
                content=content_bytes,
                content_type=content_type,
                metadata={"filename": filename},
            )

            ai_extractor = AIExtractor()
            extracted = await ai_extractor.extract(raw_content)

            # Map ExtractedData to dict
            data = {
                "yarn": extracted.yarn or extracted.name,
                "brand": extracted.brand,
                "colorway": extracted.colorway,
                "weight_category": extracted.weight_category,
                "fiber_content": extracted.fiber_content,
                "needles": extracted.needles,
                "description": extracted.description,
                "notes": None,  # AI usually puts notes in description
                "image_urls": [],  # Images handled separately
                "link": None,
                "is_ai_enhanced": True,
            }

            # Try to parse weight/length if available in extras or generic fields
            if extracted.extras:
                if "weight_grams" in extracted.extras:
                    data["weight_grams"] = extracted.extras["weight_grams"]
                if "length_meters" in extracted.extras:
                    data["length_meters"] = extracted.extras["length_meters"]

        elif import_type == "text":
            if not text or not text.strip():
                raise HTTPException(
                    status_code=status.HTTP_400_BAD_REQUEST,
                    detail="Text is required",
                )

            from stricknani.importing.extractors.ai import OPENAI_AVAILABLE, AIExtractor
            from stricknani.importing.models import ContentType, RawContent

            use_ai_enabled = config.FEATURE_AI_IMPORT_ENABLED and bool(has_ai_api_key())

            if use_ai_enabled and OPENAI_AVAILABLE:
                raw_content = RawContent(
                    content=text,
                    content_type=ContentType.TEXT,
                )
                ai_extractor = AIExtractor()
                extracted = await ai_extractor.extract(raw_content)
                # Map ExtractedData to dict
                data = {
                    "yarn": extracted.yarn or extracted.name,
                    "brand": extracted.brand,
                    "colorway": extracted.colorway,
                    "weight_category": extracted.weight_category,
                    "fiber_content": extracted.fiber_content,
                    "needles": extracted.needles,
                    "description": extracted.description,
                    "notes": None,
                    "link": None,
                    "is_ai_enhanced": True,
                }
            else:
                # Basic text fallback
                data = {
                    "description": text,
                    "notes": None,
                    "link": None,
                }

        else:
            raise HTTPException(
                status_code=status.HTTP_400_BAD_REQUEST,
                detail=f"Invalid import type: {import_type}",
            )

        data = trim_import_strings(data)

        # Map extracted data to yarn fields (normalization)
        recommended_needles = data.get("needles")
        if isinstance(recommended_needles, str):
            recommended_needles = recommended_needles.strip() or None

        # Determine yarn name
        yarn_name = data.get("yarn") or data.get("title") or data.get("name")

        # Determine weight/length if not already set
        weight_grams = data.get("weight_grams")
        length_meters = data.get("length_meters")

        # If we have unstructured yarn text (from basic import), try to parse it
        if not weight_grams and not length_meters and isinstance(yarn_name, str):
            # Simple heuristic or regex could go here, but PatternImporter usually
            # handles it
            pass

        yarn_data = {
            "name": yarn_name,
            "brand": data.get("brand"),
            "colorway": data.get("colorway"),
            "weight_grams": weight_grams,
            "length_meters": length_meters,
            "weight_category": data.get("weight_category"),
            "fiber_content": data.get("fiber_content"),
            "recommended_needles": recommended_needles,
            "description": data.get("description"),
            "link": source_url or data.get("link"),
            "image_urls": data.get("image_urls", [])[:5],
            "notes": data.get("notes") or data.get("comment"),
            "is_ai_enhanced": data.get("is_ai_enhanced", False),
        }

        image_urls = yarn_data.get("image_urls")
        if isinstance(image_urls, list) and image_urls:
            yarn_data["image_urls"] = await filter_import_image_urls(
                image_urls,
                referer=source_url,
                limit=5,
            )

        return JSONResponse(content=yarn_data)

    except HTTPException:
        raise
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
                "yarns": serialize_yarn_cards(yarns, current_user),
                "search": search or "",
                "selected_brand": brand,
            },
        )

    if request.headers.get("accept") == "application/json":
        return JSONResponse(serialize_yarn_cards(yarns, current_user))

    has_openai_key = config.FEATURE_AI_IMPORT_ENABLED and bool(has_ai_api_key())

    return await render_template(
        "yarn/list.html",
        request,
        {
            "current_user": current_user,
            "yarns": serialize_yarn_cards(yarns, current_user),
            "search": search or "",
            "selected_brand": brand,
            "has_openai_key": has_openai_key,
        },
    )


@router.get("/new", response_class=HTMLResponse)
async def new_yarn(
    request: Request,
    current_user: User = Depends(require_auth),
) -> HTMLResponse:
    """Show creation form."""

    has_openai_key = config.FEATURE_AI_IMPORT_ENABLED and bool(has_ai_api_key())

    return await render_template(
        "yarn/form.html",
        request,
        {
            "current_user": current_user,
            "yarn": None,
            "has_openai_key": has_openai_key,
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
        if is_ocr_available():
            asyncio.create_task(
                precompute_ocr_for_media_file(
                    file_path=source_path,
                    kind="yarns",
                    entity_id=yarn.id,
                )
            )
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

    deferred_deletions: list[str] = []
    # Handle imported image URLs
    if import_image_urls:
        urls = _parse_import_image_urls(import_image_urls)
        if urls:
            await import_yarn_images_from_urls(
                db,
                yarn,
                urls,
                primary_url=import_primary_image_url,
                deferred_deletions=deferred_deletions,
            )

    await create_audit_log(
        db,
        actor_user_id=current_user.id,
        entity_type="yarn",
        entity_id=yarn.id,
        action="created",
        details={
            "name": yarn.name,
            "brand": yarn.brand,
            "colorway": yarn.colorway,
            "photo_count": len(yarn.photos),
        },
    )
    await db.commit()
    for filename in deferred_deletions:
        try:
            delete_file(filename, yarn.id, subdir="yarns")
        except OSError as exc:
            logger.warning("Failed to remove replaced yarn image %s: %s", filename, exc)
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

    favorite_ids = {entry.id for entry in current_user.favorite_yarns}
    is_favorite = yarn.id in favorite_ids

    nav_rows = await db.execute(
        select(Yarn.id, Yarn.name).where(Yarn.owner_id == current_user.id)
    )
    nav_yarns = [(row[0], row[1] or "") for row in nav_rows]
    nav_yarns.sort(
        key=lambda item: (
            item[0] not in favorite_ids,
            item[1].casefold(),
            item[0],
        )
    )
    nav_ids = [item[0] for item in nav_yarns]
    swipe_prev_href = None
    swipe_next_href = None
    try:
        idx = nav_ids.index(yarn.id)
    except ValueError:
        idx = -1
    if idx > 0:
        swipe_prev_href = f"/yarn/{nav_ids[idx - 1]}"
    if idx != -1 and idx < len(nav_ids) - 1:
        swipe_next_href = f"/yarn/{nav_ids[idx + 1]}"

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
            asyncio.create_task(store_wayback_snapshot(Yarn, yarn.id, yarn.link))

    audit_entries = [
        serialize_audit_log(entry)
        for entry in await list_audit_logs(
            db,
            entity_type="yarn",
            entity_id=yarn.id,
            limit=200,
        )
    ]

    return await render_template(
        "yarn/detail.html",
        request,
        {
            "current_user": current_user,
            "yarn": yarn,
            "is_favorite": is_favorite,
            "is_ai_enhanced": yarn.is_ai_enhanced,
            "swipe_prev_href": swipe_prev_href,
            "swipe_next_href": swipe_next_href,
            "description_html": render_markdown(yarn.description)
            if yarn.description
            else None,
            "notes_html": render_markdown(yarn.notes) if yarn.notes else None,
            "preview_url": resolve_yarn_preview(yarn),
            "photos": await anyio.to_thread.run_sync(serialize_yarn_photos, yarn),
            "linked_projects": [
                {
                    "id": project.id,
                    "name": project.name,
                    "category": project.category,
                    **resolve_project_preview(project),
                }
                for project in yarn.projects
            ],
            "metadata": {
                "created": yarn.created_at.strftime("%Y-%m-%d"),
                "updated": yarn.updated_at.strftime("%Y-%m-%d"),
                "photo_count": len(yarn.photos),
                "project_count": len(yarn.projects),
            },
            "link_archive_fallback": (
                build_wayback_fallback_url(yarn.link) if yarn.link else None
            ),
            "audit_entries": audit_entries,
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

    has_openai_key = config.FEATURE_AI_IMPORT_ENABLED and bool(has_ai_api_key())

    return await render_template(
        "yarn/form.html",
        request,
        {
            "current_user": current_user,
            "yarn": yarn,
            "photos": await anyio.to_thread.run_sync(serialize_yarn_photos, yarn),
            "is_ai_enhanced": yarn.is_ai_enhanced,
            "has_openai_key": has_openai_key,
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

    before_snapshot = _yarn_audit_snapshot(yarn)

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

    deferred_deletions: list[str] = []
    # Handle imported image URLs
    if import_image_urls:
        urls = _parse_import_image_urls(import_image_urls)
        if urls:
            await import_yarn_images_from_urls(
                db,
                yarn,
                urls,
                primary_url=import_primary_image_url,
                deferred_deletions=deferred_deletions,
            )

    changes = build_field_changes(before_snapshot, _yarn_audit_snapshot(yarn))
    if changes:
        await create_audit_log(
            db,
            actor_user_id=current_user.id,
            entity_type="yarn",
            entity_id=yarn.id,
            action="updated",
            details={"changes": changes},
        )
    await db.commit()
    for filename in deferred_deletions:
        try:
            delete_file(filename, yarn.id, subdir="yarns")
        except OSError as exc:
            logger.warning("Failed to remove replaced yarn image %s: %s", filename, exc)
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
    if is_ocr_available():
        asyncio.create_task(
            precompute_ocr_for_media_file(
                file_path=source_path,
                kind="yarns",
                entity_id=yarn_id,
            )
        )

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
    await db.flush()
    await create_audit_log(
        db,
        actor_user_id=current_user.id,
        entity_type="yarn",
        entity_id=yarn_id,
        action="photo_uploaded",
        details={
            "photo_id": photo.id,
            "filename": photo.filename,
            "original_filename": photo.original_filename,
            "is_primary": photo.is_primary,
        },
    )
    await db.commit()
    await db.refresh(photo)

    width, height = get_yarn_photo_dimensions(yarn_id, saved_name)

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
            url=f"/yarn/{yarn.id}?toast=archive_requested",
            status_code=status.HTTP_303_SEE_OTHER,
        )

    return RedirectResponse(
        url=f"/yarn/{yarn.id}?toast=archive_request_unavailable",
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

    await create_audit_log(
        db,
        actor_user_id=current_user.id,
        entity_type="yarn",
        entity_id=yarn_id,
        action="photo_promoted",
        details={"photo_id": photo_id},
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

    filenames = [photo.filename for photo in yarn.photos]
    await create_audit_log(
        db,
        actor_user_id=current_user.id,
        entity_type="yarn",
        entity_id=yarn.id,
        action="deleted",
        details={
            "name": yarn.name,
            "brand": yarn.brand,
            "photo_count": len(yarn.photos),
        },
    )
    await db.delete(yarn)
    await db.commit()
    for filename in filenames:
        try:
            delete_file(filename, yarn.id, subdir="yarns")
        except OSError as exc:
            logger.warning("Failed to remove yarn image file %s: %s", filename, exc)

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

    filename = target.filename
    details = {
        "photo_id": target.id,
        "filename": target.filename,
        "original_filename": target.original_filename,
        "is_primary": target.is_primary,
    }
    await db.delete(target)
    await create_audit_log(
        db,
        actor_user_id=current_user.id,
        entity_type="yarn",
        entity_id=yarn_id,
        action="photo_deleted",
        details=details,
    )
    await db.commit()
    try:
        delete_file(filename, yarn.id, subdir="yarns")
    except OSError as exc:
        logger.warning("Failed to remove yarn image file %s: %s", filename, exc)

    if (
        request.headers.get("accept") == "application/json"
        or request.headers.get("content-type") == "application/json"
    ):
        return Response(status_code=status.HTTP_200_OK)

    return RedirectResponse(
        url=f"/yarn/{yarn.id}",
        status_code=status.HTTP_303_SEE_OTHER,
    )
