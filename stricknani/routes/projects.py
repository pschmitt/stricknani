"""Project routes."""

import json
import logging
import re
from collections.abc import Sequence
from pathlib import Path
from typing import Annotated, Any
from urllib.parse import urlparse

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
from stricknani.utils.markdown import render_markdown

router: APIRouter = APIRouter(prefix="/projects", tags=["projects"])

IMPORT_IMAGE_MAX_BYTES = 5 * 1024 * 1024
IMPORT_IMAGE_MAX_COUNT = 10
IMPORT_IMAGE_TIMEOUT = 10
IMPORT_IMAGE_HEADERS = {
    "User-Agent": "Stricknani Importer/0.1",
    "Accept": "image/*",
}
IMPORT_ALLOWED_IMAGE_TYPES = {
    "image/jpeg",
    "image/png",
    "image/webp",
    "image/gif",
}
IMPORT_ALLOWED_IMAGE_EXTENSIONS = {".jpg", ".jpeg", ".png", ".webp", ".gif"}


def _parse_import_image_urls(raw: str | None) -> list[str]:
    """Parse image URLs sent from the import form."""
    if not raw:
        return []
    try:
        data = json.loads(raw)
    except (ValueError, TypeError):
        return []
    if not isinstance(data, list):
        return []
    cleaned = [str(item).strip() for item in data if str(item).strip()]
    return cleaned


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


def _is_valid_import_url(url: str) -> bool:
    """Ensure the import URL uses http(s) and has a host."""
    parsed = urlparse(url)
    return parsed.scheme in {"http", "https"} and bool(parsed.netloc)


def _is_allowed_import_image(content_type: str | None, url: str) -> bool:
    """Validate content type or file extension for image imports."""
    if content_type:
        normalized = content_type.split(";", 1)[0].strip().lower()
        if normalized in IMPORT_ALLOWED_IMAGE_TYPES:
            return True
    extension = Path(urlparse(url).path).suffix.lower()
    return extension in IMPORT_ALLOWED_IMAGE_EXTENSIONS


async def _import_images_from_urls(
    db: AsyncSession, project: Project, image_urls: Sequence[str]
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
            image = Image(
                filename=filename,
                original_filename=original_filename,
                image_type=ImageType.PHOTO.value,
                alt_text=alt_text,
                is_title_image=title_available,
                project_id=project.id,
            )
            db.add(image)
            imported += 1
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
            photo.filename = get_thumbnail_url(
                photo.filename, yarn.id, subdir="yarns"
            )
    return yarns


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


@router.get("/", response_class=HTMLResponse)
async def list_projects(
    request: Request,
    db: AsyncSession = Depends(get_db),
    current_user: User | None = Depends(get_current_user),
    category: str | None = None,
    search: str | None = None,
) -> Response:
    """List all projects for the current user."""
    if not current_user:
        return RedirectResponse(url="/login", status_code=status.HTTP_303_SEE_OTHER)

    query = select(Project).where(Project.owner_id == current_user.id)

    if category:
        query = query.where(Project.category == category)

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
            "search": search,
            "has_openai_key": bool(config.OPENAI_API_KEY),
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

    # Check if OpenAI API key is available for AI import
    has_openai_key = bool(os.getenv("OPENAI_API_KEY"))

    return render_template(
        "projects/form.html",
        request,
        {
            "current_user": current_user,
            "project": None,
            "categories": categories,
            "yarns": yarn_options,
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

    try:
        content_text = ""
        source_url = None

        use_ai_enabled = bool(os.getenv("OPENAI_API_KEY"))

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

            # For URLs, use the existing importers
            data: dict[str, Any]
            ai_failed = False
            basic_data: dict[str, Any]

            from stricknani.utils.importer import PatternImporter

            basic_importer = PatternImporter(url)
            basic_data = await basic_importer.fetch_and_parse()
            data = basic_data

            logger.info(
                "Basic importer found %d steps and %d images",
                len(basic_data.get("steps", [])),
                len(basic_data.get("image_urls", [])),
            )

            if use_ai_enabled:
                try:
                    from stricknani.utils.ai_importer import AIPatternImporter

                    ai_importer = AIPatternImporter(
                        url, hints=_build_ai_hints(basic_data)
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

                    # 3. Check if name/title is actually set
                    if (
                        not ai_data.get("name")
                        and not ai_data.get("title")
                        and basic_data.get("title")
                    ):
                        ai_data["title"] = basic_data["title"]

                    data = ai_data

                    # Check if AI extraction actually worked (not just URL/images).
                    if all(
                        v is None or v == [] or v == ""
                        for k, v in data.items()
                        if k not in {"link", "image_urls", "comment", "steps"}
                    ):
                        ai_failed = True
                        data = basic_data

                except Exception as e:
                    # Log the AI failure and fall back to basic parser
                    logger.warning(
                        f"AI import failed, falling back to basic parser: {e}"
                    )
                    ai_failed = True
                    data = basic_data

            # Add flag if AI failed
            if ai_failed:
                data["ai_fallback"] = True
                if data.get("comment"):
                    data["comment"] = (
                        data.get("comment", "")
                        + "\n\n(Note: AI extraction failed, used basic parser)"
                    )
                else:
                    data["comment"] = "(Note: AI extraction failed, used basic parser)"
            else:
                # Even if not fully failed, if we used basic steps, tag the comment
                if (
                    data.get("steps") == basic_data.get("steps")
                    and use_ai_enabled
                    and data is not basic_data
                ):
                    if data.get("comment"):
                        data["comment"] = (
                            data.get("comment", "")
                            + "\n\n(Note: AI used for metadata, basic parser for steps)"
                        )
                    else:
                        data["comment"] = (
                            "(Note: AI used for metadata, basic parser for steps)"
                        )

            return JSONResponse(content=data)

        elif import_type == "text":
            if not text or not text.strip():
                raise HTTPException(
                    status_code=status.HTTP_400_BAD_REQUEST,
                    detail="Text is required",
                )
            content_text = text.strip()

        elif import_type == "file":
            if not file:
                raise HTTPException(
                    status_code=status.HTTP_400_BAD_REQUEST,
                    detail="File is required",
                )

            # Read file content
            content_bytes = await file.read()

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

        else:
            raise HTTPException(
                status_code=status.HTTP_400_BAD_REQUEST,
                detail=f"Invalid import type: {import_type}",
            )

        # For text and file imports, use AI to extract pattern data
        if content_text and use_ai_enabled:
            try:
                from openai import AsyncOpenAI
            except ImportError:
                logger.warning(
                    "OpenAI package not installed, AI extraction unavailable"
                )
            else:
                from stricknani.utils.ai_importer import _build_schema_from_model

                client = AsyncOpenAI(api_key=os.getenv("OPENAI_API_KEY"))
                schema = _build_schema_from_model(Project)

                system_prompt = f"""You are an expert at extracting knitting \
pattern information.
Extract the following fields from the provided text:

{json.dumps(schema, indent=2)}

Return valid JSON only. Use null for missing values."""

                user_prompt = (
                    f"Extract knitting pattern information from this text:\n\n"
                    f"{content_text[:8000]}"
                )

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

                    data = json.loads(response.choices[0].message.content or "{}")
                    if not data.get("comment"):
                        data["comment"] = content_text[:2000]
                    if source_url:
                        data["link"] = source_url
                    return JSONResponse(content=data)

                except Exception as e:
                    logger.error(f"AI extraction failed for text/file: {e}")
                    # Fall through to basic extraction

        # Basic extraction for text/file without AI
        data = {
            "title": None,
            "needles": None,
            "recommended_needles": None,
            "yarn": None,
            "gauge_stitches": None,
            "gauge_rows": None,
            "comment": content_text[:2000] if content_text else None,
            "steps": [],
            "link": source_url,
        }

        return JSONResponse(content=data)

    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Import failed: {e}", exc_info=True)
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=f"Failed to import: {str(e)}",
        ) from e


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
) -> HTMLResponse:
    cleaned = name.strip()
    if not cleaned:
        return await _render_categories_page(
            request, db, current_user, error="Category name cannot be empty."
        )

    existing = await db.execute(
        select(Category).where(
            Category.user_id == current_user.id,
            func.lower(Category.name) == cleaned.lower(),
        )
    )
    if existing.scalar_one_or_none():
        return await _render_categories_page(
            request, db, current_user, error="Category already exists."
        )

    db.add(Category(name=cleaned, user_id=current_user.id))
    await db.commit()

    return await _render_categories_page(
        request, db, current_user, message="Category created."
    )


@router.post("/categories/{category_id}")
async def rename_category(
    request: Request,
    category_id: int,
    name: Annotated[str, Form()],
    db: AsyncSession = Depends(get_db),
    current_user: User = Depends(require_auth),
) -> HTMLResponse:
    category = await db.get(Category, category_id)
    if category is None or category.user_id != current_user.id:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND)

    cleaned = name.strip()
    if not cleaned:
        return await _render_categories_page(
            request, db, current_user, error="Category name cannot be empty."
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
            error="Another category already uses that name.",
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

    return await _render_categories_page(
        request, db, current_user, message="Category updated."
    )


@router.post("/categories/{category_id}/delete")
async def delete_category(
    request: Request,
    category_id: int,
    db: AsyncSession = Depends(get_db),
    current_user: User = Depends(require_auth),
) -> HTMLResponse:
    category = await db.get(Category, category_id)
    if category is None or category.user_id != current_user.id:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND)

    in_use = await db.execute(
        select(func.count())
        .select_from(Project)
        .where(
            Project.owner_id == current_user.id,
            Project.category == category.name,
        )
    )
    if in_use.scalar_one() > 0:
        return await _render_categories_page(
            request,
            db,
            current_user,
            error="Cannot delete a category that is still assigned to projects.",
        )

    await db.delete(category)
    await db.commit()

    return await _render_categories_page(
        request, db, current_user, message="Category deleted."
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

    # Prepare title images
    title_images = [
        {
            "id": img.id,
            "url": get_file_url(img.filename, project.id),
            "thumbnail_url": get_thumbnail_url(img.filename, project.id),
            "alt_text": img.alt_text,
        }
        for img in project.images
        if img.is_title_image
    ]

    # Prepare steps with images
    base_steps: list[dict[str, object]] = []
    for step in sorted(project.steps, key=lambda s: s.step_number):
        step_images = [
            {
                "id": img.id,
                "url": get_file_url(img.filename, project.id),
                "thumbnail_url": get_thumbnail_url(img.filename, project.id),
                "alt_text": img.alt_text,
                "step_info": f"Step {step.step_number}: {step.title}",
            }
            for img in step.images
        ]
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
        "comment": project.comment or "",
        "comment_html": render_markdown(project.comment, f"project-{project.id}")
        if project.comment
        else None,
        "link": project.link,
        "created_at": project.created_at.isoformat(),
        "updated_at": project.updated_at.isoformat(),
        "title_images": title_images,
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
        {"current_user": current_user, "project": project_data},
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

    title_images = [
        {
            "id": img.id,
            "url": get_file_url(img.filename, project.id),
            "thumbnail_url": get_thumbnail_url(img.filename, project.id),
            "alt_text": img.alt_text,
        }
        for img in project.images
        if img.is_title_image
    ]

    steps_data = []
    for step in sorted(project.steps, key=lambda s: s.step_number):
        step_images = [
            {
                "id": img.id,
                "url": get_file_url(img.filename, project.id),
                "thumbnail_url": get_thumbnail_url(img.filename, project.id),
                "alt_text": img.alt_text,
                "step_info": f"Step {step.step_number}: {step.title}",
            }
            for img in step.images
        ]
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
        "comment": project.comment or "",
        "link": project.link,
        "title_images": title_images,
        "steps": steps_data,
        "tags": project.tag_list(),
        "yarn_ids": [y.id for y in project.yarns],
    }

    categories = await _get_user_categories(db, current_user.id)
    yarn_options = await _get_user_yarns(db, current_user.id)

    return render_template(
        "projects/form.html",
        request,
        {
            "current_user": current_user,
            "project": project_data,
            "categories": categories,
            "yarns": yarn_options,
        },
    )


@router.post("/")
async def create_project(
    name: Annotated[str, Form()],
    category: Annotated[str | None, Form()] = None,
    needles: Annotated[str | None, Form()] = None,
    recommended_needles: Annotated[str | None, Form()] = None,
    gauge_stitches: Annotated[str | None, Form()] = None,
    gauge_rows: Annotated[str | None, Form()] = None,
    comment: Annotated[str | None, Form()] = None,
    tags: Annotated[str | None, Form()] = None,
    link: Annotated[str | None, Form()] = None,
    steps_data: Annotated[str | None, Form()] = None,
    yarn_ids: Annotated[str | None, Form()] = None,
    import_image_urls: Annotated[str | None, Form()] = None,
    db: AsyncSession = Depends(get_db),
    current_user: User = Depends(require_auth),
) -> RedirectResponse:
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
        comment=comment.strip() if comment else None,
        link=link.strip() if link else None,
        owner_id=current_user.id,
        tags=_serialize_tags(normalized_tags),
    )
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
        await _import_images_from_urls(db, project, image_urls)

    await db.commit()
    await db.refresh(project)

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
    tags: Annotated[str | None, Form()] = None,
    link: Annotated[str | None, Form()] = None,
    steps_data: Annotated[str | None, Form()] = None,
    yarn_ids: Annotated[str | None, Form()] = None,
    import_image_urls: Annotated[str | None, Form()] = None,
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
    project.comment = comment.strip() if comment else None
    project.link = link.strip() if link else None
    project.tags = _serialize_tags(_normalize_tags(tags))

    image_urls = _parse_import_image_urls(import_image_urls)
    if image_urls:
        await _import_images_from_urls(db, project, image_urls)

    # Update steps
    if steps_data:
        steps_list = json.loads(steps_data)
        existing_step_ids = {step.id for step in project.steps}
        new_step_ids = {
            step_data.get("id") for step_data in steps_list if step_data.get("id")
        }

        # Delete removed steps
        steps_to_delete = existing_step_ids - new_step_ids
        if steps_to_delete:
            await db.execute(delete(Step).where(Step.id.in_(steps_to_delete)))

        # Update or create steps
        for step_data in steps_list:
            step_id = step_data.get("id")
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

    return RedirectResponse(
        url=f"/projects/{project.id}?toast=project_updated",
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
    project_thumb_dir = config.MEDIA_ROOT / "thumbnails" / str(project_id)

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

    # Create database record
    image = Image(
        filename=filename,
        original_filename=original_filename,
        image_type=ImageType.PHOTO.value,
        alt_text=alt_text or original_filename,
        is_title_image=True,
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
