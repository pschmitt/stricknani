"""Project routes."""

import asyncio
import json
import logging
import re
from collections.abc import Sequence
from datetime import UTC, datetime
from io import BytesIO
from pathlib import Path
from typing import Annotated, Any

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
from sqlalchemy.orm import joinedload, selectinload

from stricknani.config import config
from stricknani.database import get_db
from stricknani.main import get_language, render_template, templates
from stricknani.models import (
    Category,
    Image,
    ImageType,
    Project,
    Step,
    User,
    Yarn,
    user_favorites,
)
from stricknani.routes.auth import get_current_user, require_auth
from stricknani.utils.files import (
    build_import_filename,
    create_thumbnail,
    delete_file,
    get_file_url,
    get_thumbnail_url,
    save_bytes,
    save_uploaded_file,
)
from stricknani.utils.i18n import install_i18n
from stricknani.utils.import_trace import ImportTrace
from stricknani.utils.importer import (
    IMPORT_IMAGE_HEADERS,
    IMPORT_IMAGE_MAX_BYTES,
    IMPORT_IMAGE_MAX_COUNT,
    IMPORT_IMAGE_MIN_DIMENSION,
    IMPORT_IMAGE_TIMEOUT,
    _is_allowed_import_image,
    _is_garnstudio_url,
    _is_valid_import_url,
)
from stricknani.utils.markdown import render_markdown
from stricknani.utils.wayback import (
    _should_request_archive,
    store_wayback_snapshot,
)

router: APIRouter = APIRouter(prefix="/projects", tags=["projects"])


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


def _resolve_yarn_preview(yarn: Yarn) -> dict[str, str | None]:
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


def _get_image_dimensions(
    filename: str,
    entity_id: int,
    subdir: str = "projects",
) -> tuple[int | None, int | None]:
    image_path = config.MEDIA_ROOT / subdir / str(entity_id) / filename
    if not image_path.exists():
        return None, None
    try:
        with PilImage.open(image_path) as img:
            width, height = img.size
            return int(width), int(height)
    except (OSError, ValueError):
        return None, None


def _build_ai_hints(data: dict[str, Any]) -> dict[str, Any]:
    """Prepare lightweight hints for the AI importer."""
    hints: dict[str, Any] = {}
    for key in [
        "title",
        "name",
        "needles",
        "yarn",
        "gauge_stitches",
        "gauge_rows",
        "category",
        "comment",
        "link",
    ]:
        value = data.get(key)
        if value:
            hints[key] = value

    steps = data.get("steps")
    if isinstance(steps, list) and steps:
        hints["steps"] = steps[:5]

    image_urls = data.get("image_urls")
    if isinstance(image_urls, list) and image_urls:
        hints["image_urls"] = image_urls[:5]

    return hints


def _extract_garnstudio_notes_block(comment: str) -> str | None:
    if not comment:
        return None

    dashed_block = re.search(
        r"(-{5,}\s*\n\s*HINWEISE\s+ZUR\s+ANLEITUNG:?\s*\n-{5,}.*?)(?=\n-{5,}|\Z)",
        comment,
        re.I | re.S,
    )
    if dashed_block:
        return dashed_block.group(1).strip()

    heading_match = re.search(
        r"(HINWEISE\s+ZUR\s+ANLEITUNG:?.*)",
        comment,
        re.I | re.S,
    )
    if heading_match:
        return heading_match.group(1).strip()

    return None


async def _import_images_from_urls(
    db: AsyncSession,
    project: Project,
    image_urls: Sequence[str],
    *,
    title_url: str | None = None,
) -> int:
    """Download and attach imported images to a project."""
    if not image_urls:
        return 0

    logger = logging.getLogger("stricknani.imports")
    imported = 0

    existing_title_images = await db.execute(
        select(func.count())
        .select_from(Image)
        .where(Image.project_id == project.id, Image.is_title_image.is_(True))
    )
    title_available = existing_title_images.scalar_one() == 0

    headers = dict(IMPORT_IMAGE_HEADERS)
    if project.link:
        headers["Referer"] = project.link
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

            content_length = response.headers.get("content-length")
            if content_length:
                try:
                    if int(content_length) > IMPORT_IMAGE_MAX_BYTES:
                        logger.info("Skipping large image %s", image_url)
                        continue
                except ValueError:
                    pass

            if not response.content:
                logger.info("Skipping empty image response: %s", image_url)
                continue

            if len(response.content) > IMPORT_IMAGE_MAX_BYTES:
                logger.info("Skipping large image %s", image_url)
                continue
            try:
                from PIL import Image as PilImage

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
            except Exception as exc:
                logger.info("Skipping unreadable image %s: %s", image_url, exc)
                continue

            original_filename = build_import_filename(image_url, content_type)
            filename = ""
            try:
                filename, original_filename = save_bytes(
                    response.content, original_filename, project.id
                )
                file_path = config.MEDIA_ROOT / "projects" / str(project.id) / filename
                await create_thumbnail(file_path, project.id)
            except Exception as exc:
                if filename:
                    delete_file(filename, project.id)
                logger.warning("Failed to store image %s: %s", image_url, exc)
                continue

            alt_text = (
                f"{project.name} (imported image {imported + 1})"
                if project.name
                else original_filename
            )
            if title_url:
                is_title = image_url == title_url
            else:
                is_title = title_available

            image = Image(
                filename=filename,
                original_filename=original_filename,
                image_type=ImageType.PHOTO.value,
                alt_text=alt_text,
                is_title_image=is_title,
                project_id=project.id,
            )
            db.add(image)
            imported += 1
            if is_title:
                title_available = False

    return imported


async def _import_step_images(
    db: AsyncSession, step: Step, image_urls: Sequence[str]
) -> int:
    """Download and attach imported images to a step."""
    if not image_urls:
        return 0

    logger = logging.getLogger("stricknani.imports")
    imported = 0

    headers = dict(IMPORT_IMAGE_HEADERS)
    # Use project link as referer if available (fetch project associated with step).
    # Since step.project might not be loaded yet, rely on caller context or fetch it.
    # For now, simplistic headers.

    async with httpx.AsyncClient(
        timeout=IMPORT_IMAGE_TIMEOUT,
        follow_redirects=True,
        headers=headers,
    ) as client:
        for image_url in image_urls:
            if imported >= IMPORT_IMAGE_MAX_COUNT:
                break
            if not _is_valid_import_url(image_url):
                continue

            try:
                response = await client.get(image_url)
                response.raise_for_status()
            except httpx.HTTPError as exc:
                logger.warning("Failed to download step image %s: %s", image_url, exc)
                continue

            content_type = response.headers.get("content-type")
            if not _is_allowed_import_image(content_type, image_url):
                continue

            content_length = response.headers.get("content-length")
            if content_length:
                try:
                    if int(content_length) > IMPORT_IMAGE_MAX_BYTES:
                        continue
                except ValueError:
                    pass

            if not response.content or len(response.content) > IMPORT_IMAGE_MAX_BYTES:
                continue
            try:
                from PIL import Image as PilImage

                with PilImage.open(BytesIO(response.content)) as img:
                    width, height = img.size
                    if (
                        width < IMPORT_IMAGE_MIN_DIMENSION
                        or height < IMPORT_IMAGE_MIN_DIMENSION
                    ):
                        logger.info(
                            "Skipping small step image %s (%sx%s)",
                            image_url,
                            width,
                            height,
                        )
                        continue
            except Exception as exc:
                logger.info("Skipping unreadable step image %s: %s", image_url, exc)
                continue

            original_filename = build_import_filename(image_url, content_type)
            filename = ""
            try:
                filename, original_filename = save_bytes(
                    response.content, original_filename, step.project_id
                )
                file_path = (
                    config.MEDIA_ROOT / "projects" / str(step.project_id) / filename
                )
                await create_thumbnail(file_path, step.project_id)
            except Exception as exc:
                if filename:
                    delete_file(filename, step.project_id)
                logger.warning("Failed to store step image %s: %s", image_url, exc)
                continue

            alt_text = original_filename
            image = Image(
                filename=filename,
                original_filename=original_filename,
                image_type=ImageType.PHOTO.value,
                alt_text=alt_text,
                is_title_image=False,
                project_id=step.project_id,
                step_id=step.id,
            )
            db.add(image)
            imported += 1

    return imported


def _parse_optional_int(field_name: str, value: str | None) -> int | None:
    """Parse optional integer fields coming from forms."""

    if value is None:
        return None

    cleaned = str(value).strip()
    if not cleaned:
        return None

    try:
        return int(cleaned)
    except ValueError as exc:  # pragma: no cover - guarded validation
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=f"Invalid value for {field_name}",
        ) from exc


def _normalize_tags(raw_tags: str | None) -> list[str]:
    """Convert raw tag input into a list of unique tags."""

    if not raw_tags:
        return []

    candidates = re.split(r"[,#\s]+", raw_tags)
    seen: set[str] = set()
    tags: list[str] = []
    for candidate in candidates:
        cleaned = candidate.strip()
        if not cleaned:
            continue
        key = cleaned.lower()
        if key in seen:
            continue
        seen.add(key)
        tags.append(cleaned)
    return tags


def _serialize_tags(tags: list[str]) -> str | None:
    """Serialize tags list for storage."""

    if not tags:
        return None
    return json.dumps(tags)


def _deserialize_tags(raw: str | None) -> list[str]:
    """Deserialize stored tags string into a list."""

    if not raw:
        return []
    try:
        data = json.loads(raw)
    except (ValueError, TypeError):
        data = None

    if isinstance(data, list):
        return [str(item).strip() for item in data if str(item).strip()]

    return [segment.strip() for segment in raw.split(",") if segment.strip()]


async def _sync_project_categories(db: AsyncSession, user_id: int) -> None:
    """Ensure categories used in projects exist in the category table."""
    project_result = await db.execute(
        select(Project.category).where(Project.owner_id == user_id).distinct()
    )
    project_categories = {row[0] for row in project_result if row[0]}
    if not project_categories:
        return

    existing_result = await db.execute(
        select(Category.name).where(Category.user_id == user_id)
    )
    existing = {row[0] for row in existing_result}
    missing = project_categories - existing
    if not missing:
        return

    for name in sorted(missing):
        db.add(Category(name=name, user_id=user_id))
    await db.commit()


async def _get_user_categories(db: AsyncSession, user_id: int) -> list[str]:
    """Return all categories for a user."""
    await _sync_project_categories(db, user_id)

    result = await db.execute(
        select(Category).where(Category.user_id == user_id).order_by(Category.name)
    )
    return [category.name for category in result.scalars()]


async def _get_user_yarns(db: AsyncSession, user_id: int) -> Sequence[Yarn]:
    """Return all yarns for a user ordered by name."""

    result = await db.execute(
        select(Yarn)
        .where(Yarn.owner_id == user_id)
        .order_by(Yarn.name)
        .options(selectinload(Yarn.photos))
    )
    yarns = result.scalars().all()
    for yarn in yarns:
        for photo in yarn.photos:
            photo.filename = get_thumbnail_url(photo.filename, yarn.id, subdir="yarns")
    return yarns


async def _get_user_tags(db: AsyncSession, user_id: int) -> list[str]:
    """Return a sorted list of unique tags for a user."""

    result = await db.execute(select(Project.tags).where(Project.owner_id == user_id))
    tag_map: dict[str, str] = {}
    for (raw_tags,) in result:
        for tag in _deserialize_tags(raw_tags):
            key = tag.casefold()
            if key not in tag_map:
                tag_map[key] = tag
    return sorted(tag_map.values(), key=str.casefold)


async def _load_owned_yarns(
    db: AsyncSession, user_id: int, yarn_ids: list[int]
) -> Sequence[Yarn]:
    """Load yarns that belong to the user from provided IDs."""

    if not yarn_ids:
        return []

    result = await db.execute(
        select(Yarn).where(
            Yarn.owner_id == user_id,
            Yarn.id.in_(yarn_ids),
        )
    )
    return result.scalars().all()


async def _ensure_category(
    db: AsyncSession, user_id: int, name: str | None
) -> str | None:
    """Ensure category exists for the user and return the sanitized label."""

    if not name:
        return None

    cleaned = name.strip()
    if not cleaned:
        return None

    result = await db.execute(
        select(Category).where(
            Category.user_id == user_id,
            func.lower(Category.name) == cleaned.lower(),
        )
    )
    category = result.scalar_one_or_none()
    if category is None:
        category = Category(name=cleaned, user_id=user_id)
        db.add(category)
        await db.flush()
        return category.name

    return category.name


def _render_favorite_toggle(
    request: Request,
    project_id: int,
    is_favorite: bool,
    variant: str,
) -> HTMLResponse:
    language = get_language(request)
    install_i18n(templates.env, language)
    return templates.TemplateResponse(
        "projects/_favorite_toggle.html",
        {
            "request": request,
            "project_id": project_id,
            "is_favorite": is_favorite,
            "variant": variant,
            "current_language": language,
        },
    )


@router.get("/search-suggestions")
async def search_suggestions(
    type: str,
    q: str = "",
    db: AsyncSession = Depends(get_db),
    current_user: User = Depends(require_auth),
) -> JSONResponse:
    """Return search suggestions for categories or tags."""
    if type == "cat":
        result = await db.execute(
            select(Category.name)
            .where(
                Category.user_id == current_user.id,
                Category.name.ilike(f"%{q}%"),
            )
            .order_by(Category.name)
            .limit(10)
        )
        suggestions = [row[0] for row in result]
    elif type == "tag":
        all_tags = await _get_user_tags(db, current_user.id)
        suggestions = [t for t in all_tags if q.lower() in t.lower()][:10]
    else:
        suggestions = []

    return JSONResponse(suggestions)


@router.get("/", response_class=HTMLResponse)
async def list_projects(
    request: Request,
    db: AsyncSession = Depends(get_db),
    current_user: User | None = Depends(get_current_user),
    category: str | None = None,
    tag: str | None = None,
    search: str | None = None,
) -> Response:
    """List all projects for the current user."""
    if not current_user:
        return RedirectResponse(url="/login", status_code=status.HTTP_303_SEE_OTHER)

    query = select(Project).where(Project.owner_id == current_user.id)

    if search:
        category_token, remaining = _extract_search_token(search, "cat:")
        if category_token:
            category = category_token
            search = remaining or None
        tag_token, remaining = _extract_search_token(search or "", "tag:")
        if tag_token:
            tag = tag_token
            search = remaining or None
        hash_token, remaining = _extract_search_token(search or "", "#")
        if hash_token:
            tag = hash_token
            search = remaining or None

    if category:
        query = query.where(Project.category == category)

    if tag:
        query = query.where(Project.tags.ilike(f"%{tag}%"))

    if search:
        query = query.where(Project.name.ilike(f"%{search}%"))

    query = query.options(
        selectinload(Project.images).selectinload(Image.step),
        selectinload(Project.yarns),
    ).order_by(Project.created_at.desc())

    favorite_rows = await db.execute(
        select(user_favorites.c.project_id).where(
            user_favorites.c.user_id == current_user.id
        )
    )
    favorite_ids = {row[0] for row in favorite_rows}
    result = await db.execute(query)
    projects = result.scalars().unique().all()
    projects = sorted(
        projects,
        key=lambda project: (
            project.id not in favorite_ids,
            project.name.casefold(),
        ),
    )

    def _serialize_project(project: Project) -> dict[str, object]:
        # Get all title images (or first image if no title image set)
        candidates = [img for img in project.images if img.is_title_image]
        if not candidates and project.images:
            candidates = [project.images[0]]

        preview_images = []
        for img in candidates[:3]:
            thumb_name = f"thumb_{Path(img.filename).stem}.jpg"
            thumb_path = (
                config.MEDIA_ROOT
                / "thumbnails"
                / "projects"
                / str(project.id)
                / thumb_name
            )

            url = None
            if thumb_path.exists():
                url = get_thumbnail_url(img.filename, project.id, subdir="projects")
            file_path = config.MEDIA_ROOT / "projects" / str(project.id) / img.filename
            if file_path.exists():
                url = get_file_url(img.filename, project.id, subdir="projects")

            if url:
                preview_images.append(
                    {
                        "url": url,
                        "alt": img.alt_text or project.name,
                    }
                )

        # Backwards compatibility for templates expecting single image
        thumbnail_url = preview_images[0]["url"] if preview_images else None
        image_alt = preview_images[0]["alt"] if preview_images else project.name

        yarn_names = sorted(
            {yarn.name for yarn in project.yarns if yarn.name},
            key=str.casefold,
        )

        return {
            "id": project.id,
            "name": project.name,
            "category": project.category,
            "created_at": project.created_at.isoformat(),
            "updated_at": project.updated_at.isoformat(),
            "yarn_count": len(project.yarns),
            "yarn_names": yarn_names,
            "is_favorite": project.id in favorite_ids,
            "is_ai_enhanced": project.is_ai_enhanced,
            "thumbnail_url": thumbnail_url,
            "image_alt": image_alt,
            "preview_images": preview_images,
            "tags": project.tag_list(),
        }

    # If this is an HTMX request, only return the projects list
    if request.headers.get("HX-Request"):
        projects_data = [_serialize_project(p) for p in projects]
        language = get_language(request)
        install_i18n(templates.env, language)
        return templates.TemplateResponse(
            "projects/_list_partial.html",
            {
                "request": request,
                "projects": projects_data,
                "current_language": language,
                "search": search or "",
                "selected_category": category,
                "selected_tag": tag,
            },
        )

    projects_data = [_serialize_project(p) for p in projects]

    if request.headers.get("accept") == "application/json":
        return JSONResponse(projects_data)

    categories = await _get_user_categories(db, current_user.id)

    return render_template(
        "projects/list.html",
        request,
        {
            "current_user": current_user,
            "projects": projects_data,
            "categories": categories,
            "selected_category": category,
            "selected_tag": tag,
            "search": search,
            "has_openai_key": config.FEATURE_AI_IMPORT_ENABLED
            and bool(config.OPENAI_API_KEY),
        },
    )


@router.get("/new", response_class=HTMLResponse)
async def new_project_form(
    request: Request,
    current_user: User | None = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
) -> Response:
    """Show new project form."""
    import os

    if not current_user:
        return RedirectResponse(url="/login", status_code=status.HTTP_303_SEE_OTHER)

    categories = await _get_user_categories(db, current_user.id)
    yarn_options = await _get_user_yarns(db, current_user.id)
    tag_suggestions = await _get_user_tags(db, current_user.id)

    # Check if OpenAI API key is available for AI import
    has_openai_key = config.FEATURE_AI_IMPORT_ENABLED and bool(
        os.getenv("OPENAI_API_KEY")
    )

    return render_template(
        "projects/form.html",
        request,
        {
            "current_user": current_user,
            "project": None,
            "categories": categories,
            "yarns": yarn_options,
            "tag_suggestions": tag_suggestions,
            "has_openai_key": has_openai_key,
        },
    )


@router.post("/import")
async def import_pattern(
    import_type: Annotated[str, Form(alias="type")] = "url",
    url: Annotated[str | None, Form()] = None,
    text: Annotated[str | None, Form()] = None,
    file: UploadFile | None = None,
    use_ai: Annotated[bool, Form()] = False,
    current_user: User = Depends(require_auth),
) -> JSONResponse:
    """Import pattern data from URL, file, or text.

    Args:
        import_type: Type of import ('url', 'file', or 'text')
        url: The URL to import from (when type='url')
        text: Plain text to parse (when type='text')
        file: Uploaded file (when type='file')
        use_ai: If True, use AI-powered extraction (requires OPENAI_API_KEY)
        current_user: Authenticated user
    """
    import logging
    import os

    logger = logging.getLogger(__name__)
    trace: ImportTrace | None = None
    if config.IMPORT_TRACE_ENABLED:
        trace = ImportTrace.create(
            config.IMPORT_TRACE_DIR,
            max_chars=config.IMPORT_TRACE_MAX_CHARS,
        )
        trace.add_event(
            "request",
            {
                "import_type": import_type,
                "use_ai": use_ai,
                "user_id": current_user.id,
            },
        )

    try:
        content_text = ""
        source_url = None

        use_ai_enabled = config.FEATURE_AI_IMPORT_ENABLED and bool(
            os.getenv("OPENAI_API_KEY")
        )

        # Extract content based on import type
        if import_type == "url":
            if not url or not url.strip():
                raise HTTPException(
                    status_code=status.HTTP_400_BAD_REQUEST,
                    detail="URL is required",
                )

            url = url.strip()

            # Basic URL validation
            if not url.startswith(("http://", "https://")):
                raise HTTPException(
                    status_code=status.HTTP_400_BAD_REQUEST,
                    detail="Invalid URL format",
                )

            source_url = url
            if trace:
                trace.add_event("source_url", {"url": source_url})

            # For URLs, use the existing importers
            data: dict[str, Any]
            ai_failed = False
            basic_data: dict[str, Any]

            from stricknani.utils.importer import (
                GarnstudioPatternImporter,
                PatternImporter,
            )

            basic_importer: PatternImporter
            if _is_garnstudio_url(url):
                basic_importer = GarnstudioPatternImporter(url)
            else:
                basic_importer = PatternImporter(url)
            basic_data = await basic_importer.fetch_and_parse()
            data = basic_data
            if trace:
                trace.add_event(
                    "basic_import",
                    {
                        "steps": len(basic_data.get("steps", [])),
                        "images": len(basic_data.get("image_urls", [])),
                        "title": basic_data.get("title"),
                        "name": basic_data.get("name"),
                    },
                )

            logger.info(
                "Basic importer found %d steps and %d images",
                len(basic_data.get("steps", [])),
                len(basic_data.get("image_urls", [])),
            )

            if use_ai_enabled:
                try:
                    from stricknani.utils.ai_importer import AIPatternImporter

                    ai_importer = AIPatternImporter(
                        url,
                        hints=_build_ai_hints(basic_data),
                        trace=trace,
                    )
                    ai_data = await ai_importer.fetch_and_parse()

                    logger.info(
                        "AI importer found %d steps and %d images",
                        len(ai_data.get("steps", [])),
                        len(ai_data.get("image_urls", [])),
                    )

                    # Use AI data but intelligently fallback or merge
                    # 1. Use basic data steps if AI has none or significantly fewer
                    basic_steps = basic_data.get("steps", [])
                    ai_steps = ai_data.get("steps", [])

                    if not ai_steps and basic_steps:
                        ai_data["steps"] = basic_steps
                        logger.info("AI found no steps, using basic parser steps")
                    elif len(ai_steps) <= 1 and len(basic_steps) > 3:
                        # AI likely failed to split steps correctly, prefer heuristic
                        ai_data["steps"] = basic_steps
                        logger.info(
                            "AI returned %d steps vs basic %d, preferring basic",
                            len(ai_steps),
                            len(basic_steps),
                        )

                    # 2. Use basic image urls if AI has none or fewer
                    if len(ai_data.get("image_urls", [])) < len(
                        basic_data.get("image_urls", [])
                    ):
                        ai_data["image_urls"] = basic_data["image_urls"]
                        logger.info("AI found fewer images, using basic parser images")

                    # 3. Garnstudio notes: keep the detailed notes block if missing
                    if _is_garnstudio_url(url):
                        basic_desc = basic_data.get("description") or ""
                        notes_block = _extract_garnstudio_notes_block(basic_desc)
                        if notes_block:
                            ai_desc = ai_data.get("description")
                            if ai_desc:
                                if notes_block not in ai_desc:
                                    ai_data["description"] = (
                                        f"{ai_desc}\n\n{notes_block}"
                                    )
                            else:
                                ai_data["description"] = notes_block

                    # 4. Check if name/title is actually set
                    if (
                        not ai_data.get("name")
                        and not ai_data.get("title")
                        and basic_data.get("title")
                    ):
                        ai_data["title"] = basic_data["title"]
                    if ai_data.get("title") and not ai_data.get("name"):
                        ai_data["name"] = ai_data.get("title")

                    data = ai_data

                    # Check if AI extraction actually worked (not just URL/images).
                    if all(
                        v is None or v == [] or v == ""
                        for k, v in data.items()
                        if k
                        not in {
                            "link",
                            "image_urls",
                            "description",
                            "comment",
                            "steps",
                        }
                    ):
                        ai_failed = True
                        data = basic_data

                except Exception as e:
                    # Log the AI failure and fall back to basic parser
                    logger.warning(
                        f"AI import failed, falling back to basic parser: {e}"
                    )
                    if trace:
                        trace.record_error("ai_import", e)
                    ai_failed = True
                    data = basic_data

            # Add flag if AI failed
            if ai_failed:
                data["ai_fallback"] = True
                if data.get("description"):
                    data["description"] = (
                        data.get("description", "")
                        + "\n\n(Note: AI extraction failed, used basic parser)"
                    )
                else:
                    data["description"] = (
                        "(Note: AI extraction failed, used basic parser)"
                    )
            else:
                # Even if not fully failed, if we used basic steps, tag the comment
                if (
                    data.get("steps") == basic_data.get("steps")
                    and use_ai_enabled
                    and data is not basic_data
                ):
                    if data.get("description"):
                        data["description"] = (
                            data.get("description", "")
                            + "\n\n(Note: AI used for metadata, basic parser for steps)"
                        )
                    else:
                        data["description"] = (
                            "(Note: AI used for metadata, basic parser for steps)"
                        )

            if data.get("title") and not data.get("name"):
                data["name"] = data.get("title")
            if trace:
                trace.add_event(
                    "import_result",
                    {
                        "steps": len(data.get("steps", [])),
                        "images": len(data.get("image_urls", [])),
                        "ai_fallback": bool(data.get("ai_fallback")),
                    },
                )
                data["import_trace_id"] = trace.trace_id
            return JSONResponse(content=data)

        elif import_type == "text":
            if not text or not text.strip():
                raise HTTPException(
                    status_code=status.HTTP_400_BAD_REQUEST,
                    detail="Text is required",
                )
            content_text = text.strip()
            if trace:
                trace.record_text_blob("source_text", content_text)

        elif import_type == "file":
            if not file:
                raise HTTPException(
                    status_code=status.HTTP_400_BAD_REQUEST,
                    detail="File is required",
                )

            # Read file content
            content_bytes = await file.read()
            if trace:
                trace.add_event(
                    "file_upload",
                    {
                        "filename": file.filename,
                        "content_type": file.content_type,
                        "size": len(content_bytes),
                    },
                )

            # Handle different file types
            if file.filename and file.filename.lower().endswith(".pdf"):
                # TODO: Add PDF parsing with pypdf or similar
                raise HTTPException(
                    status_code=status.HTTP_501_NOT_IMPLEMENTED,
                    detail="PDF parsing not yet implemented",
                )
            elif file.content_type and file.content_type.startswith("image/"):
                # TODO: Add OCR with tesseract or similar
                raise HTTPException(
                    status_code=status.HTTP_501_NOT_IMPLEMENTED,
                    detail="Image OCR not yet implemented",
                )
            else:
                # Assume text file
                try:
                    content_text = content_bytes.decode("utf-8")
                except UnicodeDecodeError as e:
                    raise HTTPException(
                        status_code=status.HTTP_400_BAD_REQUEST,
                        detail="File is not valid UTF-8 text",
                    ) from e
            if content_text and trace:
                trace.record_text_blob("source_text", content_text)

        else:
            raise HTTPException(
                status_code=status.HTTP_400_BAD_REQUEST,
                detail=f"Invalid import type: {import_type}",
            )

        # For text and file imports, use AI to extract pattern data
        if content_text and use_ai and use_ai_enabled:
            try:
                from openai import AsyncOpenAI
            except ImportError:
                logger.warning(
                    "OpenAI package not installed, AI extraction unavailable"
                )
            else:
                from stricknani.utils.ai_importer import (
                    _build_ai_prompts,
                    _build_schema_from_model,
                )

                client = AsyncOpenAI(api_key=os.getenv("OPENAI_API_KEY"))
                schema = _build_schema_from_model(Project)

                system_prompt, user_prompt = _build_ai_prompts(
                    schema=schema,
                    text_content=content_text[:8000],
                    source_url=source_url,
                )
                if trace:
                    trace.record_ai_prompt(system_prompt, user_prompt)

                try:
                    response = await client.chat.completions.create(
                        model="gpt-4o-mini",
                        messages=[
                            {"role": "system", "content": system_prompt},
                            {"role": "user", "content": user_prompt},
                        ],
                        response_format={"type": "json_object"},
                        temperature=0.1,
                    )

                    raw_content = response.choices[0].message.content or ""
                    if trace:
                        trace.record_ai_response(raw_content)
                    data = json.loads(raw_content or "{}")
                    if not data.get("description"):
                        data["description"] = content_text[:2000]
                    if source_url:
                        data["link"] = source_url
                    if data.get("title") and not data.get("name"):
                        data["name"] = data.get("title")
                    if trace:
                        data["import_trace_id"] = trace.trace_id
                    return JSONResponse(content=data)

                except Exception as e:
                    logger.error(f"AI extraction failed for text/file: {e}")
                    if trace:
                        trace.record_error("ai_import_text_file", e)
                    # Fall through to basic extraction

        # Basic extraction for text/file without AI
        data = {
            "title": None,
            "needles": None,
            "recommended_needles": None,
            "yarn": None,
            "gauge_stitches": None,
            "gauge_rows": None,
            "description": content_text[:2000] if content_text else None,
            "comment": None,
            "steps": [],
            "link": source_url,
        }
        if data.get("title") and not data.get("name"):
            data["name"] = data.get("title")
        if trace:
            data["import_trace_id"] = trace.trace_id

        return JSONResponse(content=data)

    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Import failed: {e}", exc_info=True)
        if trace:
            trace.record_error("import_failure", e)
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=(
                f"Failed to import: {str(e)}"
                + (f" (trace: {trace.trace_id})" if trace else "")
            ),
        ) from e
    finally:
        if trace:
            trace.save()


async def _render_categories_page(
    request: Request,
    db: AsyncSession,
    current_user: User,
    *,
    message: str | None = None,
    error: str | None = None,
) -> HTMLResponse:
    await _sync_project_categories(db, current_user.id)

    categories_result = await db.execute(
        select(Category)
        .where(Category.user_id == current_user.id)
        .order_by(Category.name)
    )
    categories = list(categories_result.scalars())

    counts_result = await db.execute(
        select(Project.category, func.count())
        .where(Project.owner_id == current_user.id)
        .group_by(Project.category)
    )
    counts = {row[0]: row[1] for row in counts_result}

    category_rows = [
        {
            "id": category.id,
            "name": category.name,
            "project_count": counts.get(category.name, 0),
        }
        for category in categories
    ]

    return render_template(
        "projects/categories.html",
        request,
        {
            "current_user": current_user,
            "categories": category_rows,
            "message": message,
            "error": error,
        },
    )


@router.get("/categories", response_class=HTMLResponse)
async def manage_categories(
    request: Request,
    db: AsyncSession = Depends(get_db),
    current_user: User = Depends(require_auth),
) -> HTMLResponse:
    return await _render_categories_page(request, db, current_user)


@router.post("/categories")
async def create_category(
    request: Request,
    name: Annotated[str, Form()],
    db: AsyncSession = Depends(get_db),
    current_user: User = Depends(require_auth),
) -> Response:
    cleaned = name.strip()
    if not cleaned:
        return await _render_categories_page(
            request, db, current_user, error="Category name cannot be empty"
        )

    existing = await db.execute(
        select(Category).where(
            Category.user_id == current_user.id,
            func.lower(Category.name) == cleaned.lower(),
        )
    )
    if existing.scalar_one_or_none():
        return await _render_categories_page(
            request, db, current_user, error="Category already exists"
        )

    db.add(Category(name=cleaned, user_id=current_user.id))
    await db.commit()

    return RedirectResponse(
        url="/projects/categories?toast=category_created",
        status_code=status.HTTP_303_SEE_OTHER,
    )


@router.post("/categories/{category_id}")
async def rename_category(
    request: Request,
    category_id: int,
    name: Annotated[str, Form()],
    db: AsyncSession = Depends(get_db),
    current_user: User = Depends(require_auth),
) -> Response:
    category = await db.get(Category, category_id)
    if category is None or category.user_id != current_user.id:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND)

    cleaned = name.strip()
    if not cleaned:
        return await _render_categories_page(
            request, db, current_user, error="Category name cannot be empty"
        )

    conflict = await db.execute(
        select(Category).where(
            Category.user_id == current_user.id,
            func.lower(Category.name) == cleaned.lower(),
            Category.id != category_id,
        )
    )
    if conflict.scalar_one_or_none():
        return await _render_categories_page(
            request,
            db,
            current_user,
            error="Another category already uses that name",
        )

    old_name = category.name
    category.name = cleaned
    await db.flush()

    await db.execute(
        update(Project)
        .where(Project.owner_id == current_user.id, Project.category == old_name)
        .values(category=cleaned)
    )
    await db.commit()

    return RedirectResponse(
        url="/projects/categories?toast=category_updated",
        status_code=status.HTTP_303_SEE_OTHER,
    )


@router.post("/categories/{category_id}/delete")
async def delete_category(
    request: Request,
    category_id: int,
    db: AsyncSession = Depends(get_db),
    current_user: User = Depends(require_auth),
) -> RedirectResponse:
    category = await db.get(Category, category_id)
    if category is None or category.user_id != current_user.id:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND)

    await db.execute(
        update(Project)
        .where(
            Project.owner_id == current_user.id,
            Project.category == category.name,
        )
        .values(category=None)
    )
    await db.delete(category)
    await db.commit()

    return RedirectResponse(
        url="/projects/categories?toast=category_deleted",
        status_code=status.HTTP_303_SEE_OTHER,
    )


@router.get("/{project_id}", response_class=HTMLResponse)
async def get_project(
    request: Request,
    project_id: int,
    db: AsyncSession = Depends(get_db),
    current_user: User | None = Depends(get_current_user),
) -> Response:
    """Get a specific project."""
    if not current_user:
        return RedirectResponse(url="/login", status_code=status.HTTP_303_SEE_OTHER)

    result = await db.execute(
        select(Project)
        .where(Project.id == project_id)
        .options(
            selectinload(Project.images),
            selectinload(Project.steps).selectinload(Step.images),
            selectinload(Project.yarns).selectinload(Yarn.photos),
        )
    )
    project = result.scalar_one_or_none()

    if not project:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND, detail="Project not found"
        )

    if project.owner_id != current_user.id:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN, detail="Not authorized"
        )

    # Prepare project-level images (exclude step images)
    title_images = []
    stitch_sample_images = []
    has_seen_title = False

    # Sort images to ensure consistent title selection (primary first, then by ID)
    sorted_images = sorted(project.images, key=lambda i: (not i.is_title_image, i.id))

    for img in sorted_images:
        if img.step_id is not None:
            continue

        width, height = _get_image_dimensions(img.filename, project.id)
        if img.is_stitch_sample:
            stitch_sample_images.append(
                {
                    "id": img.id,
                    "url": get_file_url(img.filename, project.id),
                    "thumbnail_url": get_thumbnail_url(img.filename, project.id),
                    "alt_text": img.alt_text,
                    "width": width,
                    "height": height,
                }
            )
        else:
            is_title = img.is_title_image
            if is_title:
                if has_seen_title:
                    is_title = False
                else:
                    has_seen_title = True

            title_images.append(
                {
                    "id": img.id,
                    "url": get_file_url(img.filename, project.id),
                    "thumbnail_url": get_thumbnail_url(img.filename, project.id),
                    "alt_text": img.alt_text,
                    "is_title_image": is_title,
                    "width": width,
                    "height": height,
                }
            )

    # If no image was marked as title but we have gallery images, mark the first one
    if not has_seen_title and title_images:
        title_images[0]["is_title_image"] = True

    title_images.sort(key=lambda item: (not item["is_title_image"], item["id"]))

    # Prepare steps with images
    base_steps: list[dict[str, object]] = []
    for step in sorted(project.steps, key=lambda s: s.step_number):
        step_images = []
        for img in step.images:
            width, height = _get_image_dimensions(img.filename, project.id)
            step_images.append(
                {
                    "id": img.id,
                    "url": get_file_url(img.filename, project.id),
                    "thumbnail_url": get_thumbnail_url(img.filename, project.id),
                    "alt_text": img.alt_text,
                    "step_info": f"Step {step.step_number}: {step.title}",
                    "width": width,
                    "height": height,
                }
            )
        base_steps.append(
            {
                "id": step.id,
                "title": step.title,
                "description": step.description or "",
                "step_number": step.step_number,
                "images": step_images,
            }
        )

    favorite_lookup = await db.execute(
        select(user_favorites.c.project_id).where(
            user_favorites.c.user_id == current_user.id,
            user_favorites.c.project_id == project.id,
        )
    )
    is_favorite = favorite_lookup.first() is not None
    project_data = {
        "id": project.id,
        "name": project.name,
        "category": project.category,
        "yarn": project.yarn,
        "needles": project.needles,
        "recommended_needles": project.recommended_needles,
        "gauge_stitches": project.gauge_stitches,
        "gauge_rows": project.gauge_rows,
        "description": project.description or "",
        "description_html": (
            render_markdown(project.description, f"project-{project.id}")
            if project.description
            else None
        ),
        "stitch_sample": project.stitch_sample or "",
        "stitch_sample_html": (
            render_markdown(project.stitch_sample, f"project-{project.id}")
            if project.stitch_sample
            else None
        ),
        "comment": project.comment or "",
        "comment_html": render_markdown(project.comment, f"project-{project.id}")
        if project.comment
        else None,
        "link": project.link,
        "link_archive": project.link_archive,
        "archive_pending": bool(
            project.link
            and not project.link_archive
            and project.link_archive_requested_at
        ),
        "created_at": project.created_at.isoformat(),
        "updated_at": project.updated_at.isoformat(),
        "is_ai_enhanced": project.is_ai_enhanced,
        "title_images": title_images,
        "stitch_sample_images": stitch_sample_images,
        "steps": [
            {
                **step,
                "description_html": render_markdown(
                    str(step["description"]),
                    f"project-{project.id}",
                    step_info=f"Step {step['step_number']}: {step['title']}",
                )
                if step["description"]
                else "",
            }
            for step in base_steps
        ],
        "is_favorite": is_favorite,
        "tags": project.tag_list(),
        "linked_yarns": [
            {
                "id": yarn.id,
                "name": yarn.name,
                "brand": yarn.brand,
                "colorway": yarn.colorway,
                **_resolve_yarn_preview(yarn),
            }
            for yarn in project.yarns
        ],
    }

    return render_template(
        "projects/detail.html",
        request,
        {
            "current_user": current_user,
            "project": project_data,
            "is_ai_enhanced": project.is_ai_enhanced,
        },
    )


@router.get("/{project_id}/edit", response_class=HTMLResponse)
async def edit_project_form(
    request: Request,
    project_id: int,
    db: AsyncSession = Depends(get_db),
    current_user: User | None = Depends(get_current_user),
) -> Response:
    """Show edit project form."""
    if not current_user:
        return RedirectResponse(url="/login", status_code=status.HTTP_303_SEE_OTHER)

    result = await db.execute(
        select(Project)
        .where(Project.id == project_id)
        .options(
            selectinload(Project.images),
            selectinload(Project.steps).selectinload(Step.images),
            selectinload(Project.yarns),
        )
    )
    project = result.scalar_one_or_none()

    if not project:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND, detail="Project not found"
        )

    if project.owner_id != current_user.id:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN, detail="Not authorized"
        )

    title_images = []
    stitch_sample_images = []
    has_seen_title = False

    # Sort images to ensure consistent title selection (primary first, then by ID)
    sorted_images = sorted(project.images, key=lambda i: (not i.is_title_image, i.id))

    for img in sorted_images:
        if img.step_id is not None:
            continue
        width, height = _get_image_dimensions(img.filename, project.id)

        is_title = img.is_title_image
        if not img.is_stitch_sample:
            if is_title:
                if has_seen_title:
                    is_title = False
                else:
                    has_seen_title = True

        img_data = {
            "id": img.id,
            "url": get_file_url(img.filename, project.id),
            "thumbnail_url": get_thumbnail_url(img.filename, project.id),
            "alt_text": img.alt_text,
            "is_title_image": is_title,
            "width": width,
            "height": height,
        }
        if img.is_stitch_sample:
            stitch_sample_images.append(img_data)
        else:
            title_images.append(img_data)

    # If no image was marked as title but we have gallery images, mark the first one
    if not has_seen_title and title_images:
        title_images[0]["is_title_image"] = True

    title_images.sort(key=lambda item: (not item["is_title_image"], item["id"]))

    steps_data = []
    for step in sorted(project.steps, key=lambda s: s.step_number):
        step_images = []
        for img in step.images:
            width, height = _get_image_dimensions(img.filename, project.id)
            step_images.append(
                {
                    "id": img.id,
                    "url": get_file_url(img.filename, project.id),
                    "thumbnail_url": get_thumbnail_url(img.filename, project.id),
                    "alt_text": img.alt_text,
                    "step_info": f"Step {step.step_number}: {step.title}",
                    "width": width,
                    "height": height,
                }
            )
        steps_data.append(
            {
                "id": step.id,
                "title": step.title,
                "description": step.description or "",
                "step_number": step.step_number,
                "images": step_images,
            }
        )

    project_data = {
        "id": project.id,
        "name": project.name,
        "category": project.category,
        "yarn": project.yarn,
        "needles": project.needles,
        "recommended_needles": project.recommended_needles,
        "gauge_stitches": project.gauge_stitches,
        "gauge_rows": project.gauge_rows,
        "stitch_sample": project.stitch_sample or "",
        "description": project.description or "",
        "comment": project.comment or "",
        "link": project.link,
        "link_archive": project.link_archive,
        "archive_pending": bool(
            project.link
            and not project.link_archive
            and project.link_archive_requested_at
        ),
        "is_ai_enhanced": project.is_ai_enhanced,
        "title_images": title_images,
        "stitch_sample_images": stitch_sample_images,
        "steps": steps_data,
        "tags": project.tag_list(),
        "yarn_ids": [y.id for y in project.yarns],
    }

    categories = await _get_user_categories(db, current_user.id)
    yarn_options = await _get_user_yarns(db, current_user.id)
    tag_suggestions = await _get_user_tags(db, current_user.id)

    return render_template(
        "projects/form.html",
        request,
        {
            "current_user": current_user,
            "project": project_data,
            "categories": categories,
            "yarns": yarn_options,
            "tag_suggestions": tag_suggestions,
            "is_ai_enhanced": project.is_ai_enhanced,
            "has_openai_key": bool(config.OPENAI_API_KEY),
        },
    )


@router.post("/")
async def create_project(
    request: Request,
    name: Annotated[str, Form()],
    category: Annotated[str | None, Form()] = None,
    needles: Annotated[str | None, Form()] = None,
    recommended_needles: Annotated[str | None, Form()] = None,
    gauge_stitches: Annotated[str | None, Form()] = None,
    gauge_rows: Annotated[str | None, Form()] = None,
    comment: Annotated[str | None, Form()] = None,
    stitch_sample: Annotated[str | None, Form()] = None,
    description: Annotated[str | None, Form()] = None,
    tags: Annotated[str | None, Form()] = None,
    link: Annotated[str | None, Form()] = None,
    steps_data: Annotated[str | None, Form()] = None,
    yarn_ids: Annotated[str | None, Form()] = None,
    import_image_urls: Annotated[list[str] | None, Form()] = None,
    import_title_image_url: Annotated[str | None, Form()] = None,
    archive_on_save: Annotated[str | None, Form()] = None,
    is_ai_enhanced: Annotated[bool | None, Form()] = False,
    db: AsyncSession = Depends(get_db),
    current_user: User = Depends(require_auth),
) -> Response:
    """Create a new project."""
    # Parse comma-separated yarn IDs
    parsed_yarn_ids = []
    if yarn_ids:
        try:
            parsed_yarn_ids = [
                int(id.strip()) for id in yarn_ids.split(",") if id.strip()
            ]
        except ValueError:
            pass
    gauge_stitches_value = _parse_optional_int("gauge_stitches", gauge_stitches)
    gauge_rows_value = _parse_optional_int("gauge_rows", gauge_rows)
    normalized_category = await _ensure_category(db, current_user.id, category)
    normalized_tags = _normalize_tags(tags)

    project = Project(
        name=name.strip(),
        category=normalized_category,
        needles=needles.strip() if needles else None,
        recommended_needles=(
            recommended_needles.strip() if recommended_needles else None
        ),
        gauge_stitches=gauge_stitches_value,
        gauge_rows=gauge_rows_value,
        stitch_sample=stitch_sample.strip() if stitch_sample else None,
        description=description.strip() if description else None,
        comment=comment.strip() if comment else None,
        link=link.strip() if link else None,
        owner_id=current_user.id,
        tags=_serialize_tags(normalized_tags),
        is_ai_enhanced=bool(is_ai_enhanced),
    )
    if project.link and _should_request_archive(archive_on_save):
        project.link_archive_requested_at = datetime.now(UTC)
    project.yarns = list(await _load_owned_yarns(db, current_user.id, parsed_yarn_ids))
    project.yarn = project.yarns[0].name if project.yarns else None
    db.add(project)
    await db.flush()  # Get project ID

    # Create steps if provided
    if steps_data:
        steps_list = json.loads(steps_data)
        for step_data in steps_list:
            step = Step(
                title=step_data.get("title", ""),
                description=step_data.get("description"),
                step_number=step_data.get("step_number", 0),
                project_id=project.id,
            )
            db.add(step)
            await db.flush()  # Get step ID
            step_images = step_data.get("image_urls")
            if step_images:
                await _import_step_images(db, step, step_images)

    image_urls = _parse_import_image_urls(import_image_urls)
    if image_urls:
        await _import_images_from_urls(
            db, project, image_urls, title_url=import_title_image_url
        )

    await db.commit()
    await db.refresh(project)

    if (
        config.FEATURE_WAYBACK_ENABLED
        and project.link
        and _should_request_archive(archive_on_save)
    ):
        asyncio.create_task(store_wayback_snapshot(Project, project.id, project.link))

    if request.headers.get("accept") == "application/json":
        return JSONResponse(
            {
                "id": project.id,
                "url": f"/projects/{project.id}",
                "name": project.name,
            },
            status_code=status.HTTP_201_CREATED,
        )

    return RedirectResponse(
        url=f"/projects/{project.id}?toast=project_created",
        status_code=status.HTTP_303_SEE_OTHER,
    )


@router.post("/{project_id}")
async def update_project(
    project_id: int,
    name: Annotated[str, Form()],
    category: Annotated[str | None, Form()] = None,
    needles: Annotated[str | None, Form()] = None,
    recommended_needles: Annotated[str | None, Form()] = None,
    gauge_stitches: Annotated[str | None, Form()] = None,
    gauge_rows: Annotated[str | None, Form()] = None,
    comment: Annotated[str | None, Form()] = None,
    stitch_sample: Annotated[str | None, Form()] = None,
    description: Annotated[str | None, Form()] = None,
    tags: Annotated[str | None, Form()] = None,
    link: Annotated[str | None, Form()] = None,
    steps_data: Annotated[str | None, Form()] = None,
    yarn_ids: Annotated[str | None, Form()] = None,
    import_image_urls: Annotated[list[str] | None, Form()] = None,
    archive_on_save: Annotated[str | None, Form()] = None,
    is_ai_enhanced: Annotated[bool | None, Form()] = False,
    db: AsyncSession = Depends(get_db),
    current_user: User = Depends(require_auth),
) -> RedirectResponse:
    """Update a project."""
    # Parse comma-separated yarn IDs
    parsed_yarn_ids = []
    if yarn_ids:
        try:
            parsed_yarn_ids = [
                int(id.strip()) for id in yarn_ids.split(",") if id.strip()
            ]
        except ValueError:
            pass
    result = await db.execute(
        select(Project)
        .where(Project.id == project_id)
        .options(selectinload(Project.steps), selectinload(Project.yarns))
    )
    project = result.scalar_one_or_none()

    if not project:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND, detail="Project not found"
        )

    if project.owner_id != current_user.id:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN, detail="Not authorized"
        )

    project.name = name.strip()
    project.category = await _ensure_category(db, current_user.id, category)
    selected_yarns = list(await _load_owned_yarns(db, current_user.id, parsed_yarn_ids))
    project.yarns = selected_yarns
    project.yarn = selected_yarns[0].name if selected_yarns else None
    project.needles = needles.strip() if needles else None
    project.recommended_needles = (
        recommended_needles.strip() if recommended_needles else None
    )
    project.gauge_stitches = _parse_optional_int("gauge_stitches", gauge_stitches)
    project.gauge_rows = _parse_optional_int("gauge_rows", gauge_rows)
    project.stitch_sample = stitch_sample.strip() if stitch_sample else None
    project.description = description.strip() if description else None
    project.comment = comment.strip() if comment else None
    project.link = link.strip() if link else None
    project.tags = _serialize_tags(_normalize_tags(tags))
    project.is_ai_enhanced = bool(is_ai_enhanced)
    if project.link and _should_request_archive(archive_on_save):
        project.link_archive_requested_at = datetime.now(UTC)

    image_urls = _parse_import_image_urls(import_image_urls)
    if image_urls:
        await _import_images_from_urls(db, project, image_urls)

    # Update steps
    if steps_data:
        steps_list = json.loads(steps_data)
        existing_step_ids = {step.id for step in project.steps}

        def _coerce_step_id(raw: object) -> int | None:
            try:
                if isinstance(raw, (str, int, float)):
                    return int(raw)
                return None
            except (TypeError, ValueError, AttributeError):
                return None

        new_step_ids = {
            step_id
            for step_data in steps_list
            if (step_id := _coerce_step_id(step_data.get("id"))) is not None
        }

        # Delete removed steps
        steps_to_delete = existing_step_ids - new_step_ids
        if steps_to_delete:
            # Fetch images for these steps to delete files from disk
            images_to_delete_result = await db.execute(
                select(Image).where(Image.step_id.in_(steps_to_delete))
            )
            for img in images_to_delete_result.scalars():
                delete_file(img.filename, project_id)

            # Explicitly delete image records from DB since bulk Step delete
            # doesn't trigger ORM cascades
            await db.execute(delete(Image).where(Image.step_id.in_(steps_to_delete)))
            await db.execute(delete(Step).where(Step.id.in_(steps_to_delete)))

        # Update or create steps
        for step_data in steps_list:
            step_id = _coerce_step_id(step_data.get("id"))
            if step_id and step_id in existing_step_ids:
                # Update existing step
                step_result = await db.execute(select(Step).where(Step.id == step_id))
                step = step_result.scalar_one()
                step.title = step_data.get("title", "")
                step.description = step_data.get("description")
                step.step_number = step_data.get("step_number", 0)
            else:
                # Create new step
                step = Step(
                    title=step_data.get("title", ""),
                    description=step_data.get("description"),
                    step_number=step_data.get("step_number", 0),
                    project_id=project.id,
                )
                db.add(step)
                await db.flush()
                step_images = step_data.get("image_urls")
                if step_images:
                    await _import_step_images(db, step, step_images)

    await db.commit()

    if (
        config.FEATURE_WAYBACK_ENABLED
        and project.link
        and _should_request_archive(archive_on_save)
    ):
        asyncio.create_task(store_wayback_snapshot(Project, project.id, project.link))

    return RedirectResponse(
        url=f"/projects/{project.id}?toast=project_updated",
        status_code=status.HTTP_303_SEE_OTHER,
    )


@router.post("/{project_id}/retry-archive", response_class=Response)
async def retry_project_archive(
    project_id: int,
    db: AsyncSession = Depends(get_db),
    current_user: User = Depends(require_auth),
) -> Response:
    """Retry requesting a wayback snapshot for a project."""
    result = await db.execute(
        select(Project).where(
            Project.id == project_id,
            Project.owner_id == current_user.id,
        )
    )
    project = result.scalar_one_or_none()
    if not project:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND)

    if config.FEATURE_WAYBACK_ENABLED and project.link:
        project.link_archive_failed = False
        project.link_archive_requested_at = datetime.now(UTC)
        await db.commit()
        asyncio.create_task(store_wayback_snapshot(Project, project.id, project.link))

    return RedirectResponse(
        url=f"/projects/{project.id}",
        status_code=status.HTTP_303_SEE_OTHER,
    )


@router.delete("/{project_id}", response_class=Response)
async def delete_project(
    project_id: int,
    request: Request,
    db: AsyncSession = Depends(get_db),
    current_user: User = Depends(require_auth),
) -> Response:
    """Delete a project."""
    result = await db.execute(
        select(Project)
        .where(Project.id == project_id)
        .options(selectinload(Project.images), selectinload(Project.steps))
    )
    project = result.scalar_one_or_none()

    if not project:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND, detail="Project not found"
        )

    if project.owner_id != current_user.id:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN, detail="Not authorized"
        )

    # Delete all project assets before deleting the database record
    import shutil

    project_media_dir = config.MEDIA_ROOT / "projects" / str(project_id)
    project_thumb_dir = config.MEDIA_ROOT / "thumbnails" / "projects" / str(project_id)

    if project_media_dir.exists():
        shutil.rmtree(project_media_dir)
    if project_thumb_dir.exists():
        shutil.rmtree(project_thumb_dir)

    await db.delete(project)
    await db.commit()

    if request.headers.get("HX-Request"):
        # Check if any projects remain
        result = await db.execute(
            select(func.count(Project.id)).where(Project.owner_id == current_user.id)
        )
        count = result.scalar() or 0

        if count == 0:
            response = render_template(
                "projects/_empty_state.html",
                request,
                {"current_user": current_user},
            )
            response.headers["HX-Retarget"] = "#projects-list"
            response.headers["HX-Reswap"] = "innerHTML"
            return response

        return Response(status_code=status.HTTP_200_OK)

    return RedirectResponse(
        url="/projects?toast=project_deleted",
        status_code=status.HTTP_303_SEE_OTHER,
    )


# Image upload endpoints


@router.post("/{project_id}/favorite", response_class=HTMLResponse)
async def favorite_project(
    request: Request,
    project_id: int,
    variant: str = "detail",
    db: AsyncSession = Depends(get_db),
    current_user: User = Depends(require_auth),
) -> Response:
    result = await db.execute(select(Project).where(Project.id == project_id))
    project = result.scalar_one_or_none()

    if not project:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND, detail="Project not found"
        )

    if project.owner_id != current_user.id:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN, detail="Not authorized"
        )

    favorite_exists = await db.execute(
        select(user_favorites.c.project_id).where(
            user_favorites.c.user_id == current_user.id,
            user_favorites.c.project_id == project_id,
        )
    )

    if not favorite_exists.first():
        await db.execute(
            insert(user_favorites).values(
                user_id=current_user.id, project_id=project_id
            )
        )
        await db.commit()

    if request.headers.get("HX-Request"):
        return _render_favorite_toggle(request, project_id, True, variant)

    return RedirectResponse(
        url=f"/projects/{project_id}", status_code=status.HTTP_303_SEE_OTHER
    )


@router.delete("/{project_id}/favorite", response_class=HTMLResponse)
async def unfavorite_project(
    request: Request,
    project_id: int,
    variant: str = "detail",
    db: AsyncSession = Depends(get_db),
    current_user: User = Depends(require_auth),
) -> Response:
    result = await db.execute(select(Project).where(Project.id == project_id))
    project = result.scalar_one_or_none()

    if not project:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND, detail="Project not found"
        )

    if project.owner_id != current_user.id:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN, detail="Not authorized"
        )

    await db.execute(
        delete(user_favorites).where(
            user_favorites.c.user_id == current_user.id,
            user_favorites.c.project_id == project_id,
        )
    )
    await db.commit()

    if request.headers.get("HX-Request"):
        if variant == "profile":
            return HTMLResponse(content="")
        return _render_favorite_toggle(request, project_id, False, variant)

    return RedirectResponse(
        url=f"/projects/{project_id}", status_code=status.HTTP_303_SEE_OTHER
    )


@router.post("/{project_id}/images/title")
async def upload_title_image(
    project_id: int,
    file: UploadFile = File(...),
    alt_text: Annotated[str, Form()] = "",
    db: AsyncSession = Depends(get_db),
    current_user: User = Depends(require_auth),
) -> JSONResponse:
    """Upload a title image for a project."""
    # Verify project ownership
    result = await db.execute(select(Project).where(Project.id == project_id))
    project = result.scalar_one_or_none()

    if not project or project.owner_id != current_user.id:
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN)

    # Save file
    filename, original_filename = await save_uploaded_file(file, project_id)

    # Create thumbnail
    file_path = config.MEDIA_ROOT / "projects" / str(project_id) / filename
    await create_thumbnail(file_path, project_id)

    # Check if a title image already exists
    count_result = await db.execute(
        select(func.count(Image.id)).where(
            Image.project_id == project_id,
            Image.is_title_image.is_(True),
            Image.is_stitch_sample.is_(False),
            Image.step_id.is_(None),
        )
    )
    has_title_image = (count_result.scalar() or 0) > 0

    # Create database record
    image = Image(
        filename=filename,
        original_filename=original_filename,
        image_type=ImageType.PHOTO.value,
        alt_text=alt_text or original_filename,
        is_title_image=not has_title_image,
        project_id=project_id,
    )
    db.add(image)
    await db.commit()
    await db.refresh(image)

    return JSONResponse(
        {
            "id": image.id,
            "url": get_file_url(filename, project_id),
            "thumbnail_url": get_thumbnail_url(filename, project_id),
            "alt_text": image.alt_text,
        }
    )


@router.post("/{project_id}/images/stitch-sample")
async def upload_stitch_sample_image(
    project_id: int,
    file: UploadFile = File(...),
    alt_text: Annotated[str, Form()] = "",
    db: AsyncSession = Depends(get_db),
    current_user: User = Depends(require_auth),
) -> JSONResponse:
    """Upload a stitch sample image for a project."""
    # Verify project ownership
    result = await db.execute(select(Project).where(Project.id == project_id))
    project = result.scalar_one_or_none()

    if not project or project.owner_id != current_user.id:
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN)

    # Save file
    filename, original_filename = await save_uploaded_file(file, project_id)

    # Create thumbnail
    file_path = config.MEDIA_ROOT / "projects" / str(project_id) / filename
    await create_thumbnail(file_path, project_id)

    # Create database record
    image = Image(
        filename=filename,
        original_filename=original_filename,
        image_type=ImageType.PHOTO.value,
        alt_text=alt_text or original_filename,
        is_title_image=False,
        is_stitch_sample=True,
        project_id=project_id,
    )
    db.add(image)
    await db.commit()
    await db.refresh(image)

    return JSONResponse(
        {
            "id": image.id,
            "url": get_file_url(filename, project_id),
            "thumbnail_url": get_thumbnail_url(filename, project_id),
            "alt_text": image.alt_text,
        }
    )


@router.post("/{project_id}/steps/{step_id}/images")
async def upload_step_image(
    project_id: int,
    step_id: int,
    file: UploadFile = File(...),
    alt_text: Annotated[str, Form()] = "",
    db: AsyncSession = Depends(get_db),
    current_user: User = Depends(require_auth),
) -> JSONResponse:
    """Upload an image for a step."""
    # Verify project ownership and that the step belongs to the project
    result = await db.execute(
        select(Project.owner_id)
        .join(Step, Step.project_id == Project.id)
        .where(Project.id == project_id, Step.id == step_id)
    )
    owner_id = result.scalar_one_or_none()

    if owner_id is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND)

    if owner_id != current_user.id:
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN)

    # Save file
    filename, original_filename = await save_uploaded_file(file, project_id)

    # Create thumbnail
    file_path = config.MEDIA_ROOT / "projects" / str(project_id) / filename
    await create_thumbnail(file_path, project_id)

    # Create database record
    image = Image(
        filename=filename,
        original_filename=original_filename,
        image_type=ImageType.PHOTO.value,
        alt_text=alt_text or original_filename,
        is_title_image=False,
        project_id=project_id,
        step_id=step_id,
    )
    db.add(image)
    await db.commit()
    await db.refresh(image)

    return JSONResponse(
        {
            "id": image.id,
            "url": get_file_url(filename, project_id),
            "thumbnail_url": get_thumbnail_url(filename, project_id),
            "alt_text": image.alt_text,
        }
    )


@router.delete("/{project_id}/images/{image_id}")
async def delete_image(
    project_id: int,
    image_id: int,
    db: AsyncSession = Depends(get_db),
    current_user: User = Depends(require_auth),
) -> dict[str, str]:
    """Delete an image."""
    result = await db.execute(
        select(Image)
        .options(joinedload(Image.project))
        .join(Project)
        .where(Image.id == image_id, Project.id == project_id)
    )
    image = result.scalar_one_or_none()

    if not image or image.project.owner_id != current_user.id:
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN)

    # Delete file from disk
    delete_file(image.filename, project_id)

    # Delete database record
    await db.delete(image)
    await db.commit()

    return {"message": "Image deleted"}


@router.post("/{project_id}/images/{image_id}/promote", response_class=Response)
async def promote_project_image(
    project_id: int,
    image_id: int,
    db: AsyncSession = Depends(get_db),
    current_user: User = Depends(require_auth),
) -> Response:
    """Promote an image to be the title image for a project."""
    # Ensure project exists and belongs to user
    result = await db.execute(
        select(Project).where(
            Project.id == project_id,
            Project.owner_id == current_user.id,
        )
    )
    project = result.scalar_one_or_none()
    if not project:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND)

    # Set all images for this project to non-title
    await db.execute(
        update(Image).where(Image.project_id == project_id).values(is_title_image=False)
    )

    # Set selected image to title
    await db.execute(
        update(Image)
        .where(Image.id == image_id, Image.project_id == project_id)
        .values(is_title_image=True)
    )

    await db.commit()
    return Response(status_code=status.HTTP_204_NO_CONTENT)


@router.post("/{project_id}/steps", response_class=JSONResponse)
async def create_step(
    project_id: int,
    request: Request,
    db: AsyncSession = Depends(get_db),
    current_user: User = Depends(require_auth),
) -> dict[str, Any]:
    """Create a new step for a project."""
    # Verify project ownership
    result = await db.execute(select(Project).where(Project.id == project_id))
    project = result.scalar_one_or_none()

    if not project:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND, detail="Project not found"
        )

    if project.owner_id != current_user.id:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN, detail="Not authorized"
        )

    # Parse JSON body
    data = await request.json()
    title = data.get("title", "")
    description = data.get("description")
    step_number = data.get("step_number", 1)

    # Create step
    step = Step(
        title=title,
        description=description,
        step_number=step_number,
        project_id=project_id,
    )
    db.add(step)
    await db.commit()
    await db.refresh(step)

    return {
        "id": step.id,
        "title": step.title,
        "description": step.description,
        "step_number": step.step_number,
    }


@router.put("/{project_id}/steps/{step_id}", response_class=JSONResponse)
async def update_step(
    project_id: int,
    step_id: int,
    request: Request,
    db: AsyncSession = Depends(get_db),
    current_user: User = Depends(require_auth),
) -> dict[str, Any]:
    """Update an existing step."""
    # Verify project ownership and step existence
    result = await db.execute(
        select(Step)
        .join(Project)
        .where(Step.id == step_id, Project.id == project_id)
        .options(selectinload(Step.project))
    )
    step = result.scalar_one_or_none()

    if not step:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND, detail="Step not found"
        )

    if step.project.owner_id != current_user.id:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN, detail="Not authorized"
        )

    # Parse JSON body
    data = await request.json()
    step.title = data.get("title", step.title)
    step.description = data.get("description", step.description)
    step.step_number = data.get("step_number", step.step_number)

    await db.commit()
    await db.refresh(step)

    return {
        "id": step.id,
        "title": step.title,
        "description": step.description,
        "step_number": step.step_number,
    }
