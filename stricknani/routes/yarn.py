"""Yarn stash routes."""

import re
from collections.abc import Iterable
from pathlib import Path
from typing import Annotated

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
from sqlalchemy import delete, func, insert, select
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm import selectinload

from stricknani.config import config
from stricknani.database import get_db
from stricknani.main import render_template
from stricknani.models import Project, User, Yarn, YarnImage, user_favorite_yarns
from stricknani.routes.auth import get_current_user, require_auth
from stricknani.utils.files import (
    create_thumbnail,
    delete_file,
    get_file_url,
    get_thumbnail_url,
    save_uploaded_file,
)
from stricknani.utils.markdown import render_markdown

router: APIRouter = APIRouter(prefix="/yarn", tags=["yarn"])


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


async def _fetch_yarn(db: AsyncSession, yarn_id: int, owner_id: int) -> Yarn | None:
    """Fetch a yarn entry for the owner with photos eagerly loaded."""

    result = await db.execute(
        select(Yarn)
        .where(Yarn.id == yarn_id, Yarn.owner_id == owner_id)
        .options(
            selectinload(Yarn.photos),
            selectinload(Yarn.projects).selectinload(Project.images),
        )
    )
    return result.scalar_one_or_none()


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
    for photo in yarn.photos:
        width, height = _get_photo_dimensions(yarn.id, photo.filename)
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
                "width": width,
                "height": height,
            }
        )
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
            },
            "preview_url": _resolve_preview(yarn),
        }
        for yarn in yarns
    ]


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
        return render_template(
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

    return render_template(
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

    return render_template(
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
    photos: Annotated[list[UploadFile | str] | None, File()] = None,
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
    )
    yarn.photos = []
    db.add(yarn)
    await db.flush()

    await _handle_photo_uploads(photos, yarn, db)
    await db.commit()

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
    yarn = await _fetch_yarn(db, yarn_id, current_user.id)
    if yarn is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND)
    is_favorite = any(entry.id == yarn.id for entry in current_user.favorite_yarns)

    return render_template(
        "yarn/detail.html",
        request,
        {
            "current_user": current_user,
            "yarn": yarn,
            "is_favorite": is_favorite,
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

    yarn = await _fetch_yarn(db, yarn_id, current_user.id)
    if yarn is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND)

    return render_template(
        "yarn/form.html",
        request,
        {
            "current_user": current_user,
            "yarn": yarn,
            "photos": _serialize_photos(yarn),
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
    new_photos: Annotated[list[UploadFile | str] | None, File()] = None,
    db: AsyncSession = Depends(get_db),
    current_user: User = Depends(require_auth),
) -> Response:
    """Update a yarn entry."""
    if new_photos is None:
        new_photos = []

    yarn = await _fetch_yarn(db, yarn_id, current_user.id)
    if yarn is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND)

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

    await _handle_photo_uploads(new_photos, yarn, db)
    await db.commit()

    return RedirectResponse(
        url=f"/yarn/{yarn.id}?toast=yarn_updated",
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

        return render_template(
            "yarn/_favorite_toggle.html",
            request,
            {"yarn_id": yarn_id, "is_favorite": is_favorite, "variant": variant},
        )

    return RedirectResponse(
        url=request.headers.get("referer") or "/yarn",
        status_code=status.HTTP_303_SEE_OTHER,
    )


@router.post("/{yarn_id}/delete", response_class=Response)
async def delete_yarn(
    yarn_id: int,
    request: Request,
    db: AsyncSession = Depends(get_db),
    current_user: User = Depends(require_auth),
) -> Response:
    """Delete a yarn and its photos."""

    yarn = await _fetch_yarn(db, yarn_id, current_user.id)
    if yarn is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND)

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
            response = render_template(
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

    yarn = await _fetch_yarn(db, yarn_id, current_user.id)
    if yarn is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND)

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
