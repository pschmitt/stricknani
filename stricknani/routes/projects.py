"""Project routes."""

import asyncio
import json
import logging
import re
from collections.abc import Sequence
from dataclasses import dataclass
from datetime import UTC, datetime
from io import BytesIO
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
    Query,
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
    Attachment,
    Category,
    Image,
    ImageType,
    Project,
    Step,
    User,
    YarnImage,
    project_yarns,
    user_favorites,
)
from stricknani.models import (
    Yarn as YarnModel,
)
from stricknani.routes.auth import get_current_user, require_auth
from stricknani.utils.files import (
    build_import_filename,
    compute_checksum,
    compute_file_checksum,
    create_pdf_thumbnail,
    create_thumbnail,
    delete_file,
    get_file_url,
    get_thumbnail_url,
    save_bytes,
    save_uploaded_file,
)
from stricknani.utils.i18n import install_i18n
from stricknani.utils.image_similarity import (
    SimilarityImage,
    build_similarity_image,
    compute_similarity_score,
)
from stricknani.utils.import_trace import ImportTrace
from stricknani.utils.importer import (
    IMPORT_IMAGE_HEADERS,
    IMPORT_IMAGE_MAX_BYTES,
    IMPORT_IMAGE_MAX_COUNT,
    IMPORT_IMAGE_MIN_DIMENSION,
    IMPORT_IMAGE_SSIM_THRESHOLD,
    IMPORT_IMAGE_TIMEOUT,
    _is_allowed_import_image,
    _is_garnstudio_url,
    _is_valid_import_url,
    filter_import_image_urls,
    trim_import_strings,
)
from stricknani.utils.markdown import render_markdown
from stricknani.utils.wayback import (
    _should_request_archive,
    build_wayback_fallback_url,
    store_wayback_snapshot,
)

router: APIRouter = APIRouter(prefix="/projects", tags=["projects"])


@dataclass
class _ImportedSimilarity:
    similarity: SimilarityImage
    image: Image
    filename: str
    is_title_image: bool


_GARNSTUDIO_SYMBOL_URL_RE = re.compile(
    r"(https?://[^\s)\"'>]+?/drops/symbols/[^\s)\"'>]+)",
    re.IGNORECASE,
)


async def _localize_garnstudio_symbol_images(
    project_id: int,
    description: str | None,
    *,
    referer: str | None = None,
) -> str | None:
    if not description or "/drops/symbols/" not in description:
        return description

    urls = sorted(set(_GARNSTUDIO_SYMBOL_URL_RE.findall(description)))
    if not urls:
        return description

    symbol_dir = (
        config.MEDIA_ROOT
        / "projects"
        / str(project_id)
        / "inline"
        / "garnstudio-symbols"
    )
    symbol_dir.mkdir(parents=True, exist_ok=True)

    headers = dict(IMPORT_IMAGE_HEADERS)
    if referer:
        headers["Referer"] = referer

    replacements: dict[str, str] = {}
    async with httpx.AsyncClient(
        timeout=IMPORT_IMAGE_TIMEOUT,
        follow_redirects=True,
        headers=headers,
    ) as client:
        for url in urls:
            try:
                response = await client.get(url)
                response.raise_for_status()
            except httpx.HTTPError:
                continue

            if not response.content:
                continue

            # These are tiny symbol images; we still guard against abuse.
            if len(response.content) > min(IMPORT_IMAGE_MAX_BYTES, 512 * 1024):
                continue

            content_type = response.headers.get("content-type")
            if content_type and not content_type.lower().startswith("image/"):
                continue

            parsed = urlparse(url)
            ext = Path(parsed.path).suffix.lower()
            if not ext:
                ext = Path(build_import_filename(url, content_type)).suffix.lower()
            if not ext:
                ext = ".gif"

            checksum = compute_checksum(response.content)
            filename = f"{checksum[:16]}{ext}"
            target_path = symbol_dir / filename
            if not target_path.exists():
                target_path.write_bytes(response.content)

            replacements[url] = (
                f"/media/projects/{project_id}/inline/garnstudio-symbols/{filename}"
            )

    localized = description
    for src, dst in replacements.items():
        localized = localized.replace(src, dst)
    return localized


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


def _resolve_yarn_preview(yarn: YarnModel) -> dict[str, str | None]:
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
        "brand",
        "category",
        "notes",
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


def _normalize_for_comparison(text: str) -> str:
    """Normalize text for fuzzy comparison by removing non-alphanumeric chars."""
    return re.sub(r"\W+", "", text).lower()


async def _load_existing_image_checksums(
    db: AsyncSession,
    project_id: int,
    *,
    step_id: int | None = None,
) -> dict[str, Image]:
    """Return existing image checksums for a project or a specific step."""
    query = select(Image).where(Image.project_id == project_id)
    if step_id is None:
        query = query.where(Image.step_id.is_(None))
    else:
        query = query.where(Image.step_id == step_id)

    result = await db.execute(query)
    images = result.scalars().all()
    checksums: dict[str, Image] = {}
    for image in images:
        file_path = config.MEDIA_ROOT / "projects" / str(project_id) / image.filename
        checksum = compute_file_checksum(file_path)
        if checksum:
            checksums.setdefault(checksum, image)
    return checksums


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
    existing_checksums = await _load_existing_image_checksums(db, project.id)
    seen_checksums: set[str] = set()
    imported_similarities: list[_ImportedSimilarity] = []

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

            checksum = compute_checksum(response.content)
            if checksum in existing_checksums or checksum in seen_checksums:
                logger.info("Skipping duplicate image %s", image_url)
                if (
                    title_url
                    and image_url == title_url
                    and title_available
                    and checksum in existing_checksums
                ):
                    existing_checksums[checksum].is_title_image = True
                    title_available = False
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

            removed_title = any(entry.is_title_image for entry in to_remove)
            for entry in to_remove:
                await db.delete(entry.image)
                delete_file(entry.filename, project.id)
                imported_similarities.remove(entry)
                imported = max(0, imported - 1)

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

            if removed_title:
                is_title = True

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
            seen_checksums.add(checksum)
            imported_similarities.append(
                _ImportedSimilarity(
                    similarity=similarity,
                    image=image,
                    filename=filename,
                    is_title_image=is_title,
                )
            )
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
    existing_checksums = await _load_existing_image_checksums(
        db, step.project_id, step_id=step.id
    )
    seen_checksums: set[str] = set()
    imported_similarities: list[_ImportedSimilarity] = []

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

            checksum = compute_checksum(response.content)
            if checksum in existing_checksums or checksum in seen_checksums:
                logger.info("Skipping duplicate step image %s", image_url)
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
                    similarity = build_similarity_image(img)
            except Exception as exc:
                logger.info("Skipping unreadable step image %s: %s", image_url, exc)
                continue

            skip_thumbnail = False
            to_remove: list[_ImportedSimilarity] = []
            for candidate in imported_similarities:
                score = compute_similarity_score(candidate.similarity, similarity)
                if score is None or score < IMPORT_IMAGE_SSIM_THRESHOLD:
                    continue
                if similarity.pixels <= candidate.similarity.pixels:
                    logger.info(
                        "Skipping thumbnail step image %s (ssim %.3f)",
                        image_url,
                        score,
                    )
                    skip_thumbnail = True
                    break
                to_remove.append(candidate)

            if skip_thumbnail:
                continue

            for entry in to_remove:
                await db.delete(entry.image)
                delete_file(entry.filename, step.project_id)
                imported_similarities.remove(entry)
                imported = max(0, imported - 1)

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
            seen_checksums.add(checksum)
            imported_similarities.append(
                _ImportedSimilarity(
                    similarity=similarity,
                    image=image,
                    filename=filename,
                    is_title_image=False,
                )
            )

    return imported


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


async def _get_user_yarns(db: AsyncSession, user_id: int) -> Sequence[YarnModel]:
    """Return all yarns for a user ordered by name."""

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

    return await render_template(
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

    return await render_template(
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
    project_id: Annotated[int | None, Form()] = None,
    db: AsyncSession = Depends(get_db),
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
            basic_data["is_ai_enhanced"] = False
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

            # Skip AI for Garnstudio URLs as the basic parser is now high quality
            if use_ai_enabled and not _is_garnstudio_url(url):
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

                    # 4. Check if name/title is actually set
                    if (
                        not ai_data.get("name")
                        and not ai_data.get("title")
                        and basic_data.get("title")
                    ):
                        ai_data["title"] = basic_data["title"]
                    if ai_data.get("title") and not ai_data.get("name"):
                        ai_data["name"] = ai_data.get("title")

                    # 5. Merge stitch sample: Favor heuristic over AI as it's more
                    # precise and less likely to include unrelated paragraphs.
                    basic_sample = basic_data.get("stitch_sample")
                    if basic_sample:
                        ai_data["stitch_sample"] = basic_sample

                    data = ai_data
                    data["is_ai_enhanced"] = True

                    # Check if AI extraction actually worked (not just URL/images).
                    if all(
                        v is None or v == [] or v == ""
                        for k, v in data.items()
                        if k
                        not in {
                            "link",
                            "image_urls",
                            "description",
                            "notes",
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
                # Even if not fully failed, if we used basic steps, tag the notes
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
            data = trim_import_strings(data)

            existing_gallery_checksums: set[str] | None = None
            if project_id is not None:
                # When importing into an existing project, skip images that are already
                # present in the project's gallery (dedupe will also happen on save,
                # but we prefer showing the effective result in the UI).
                res = await db.execute(
                    select(Project).where(
                        Project.id == project_id,
                        Project.owner_id == current_user.id,
                    )
                )
                project_obj = res.scalar_one_or_none()
                if project_obj:
                    existing_gallery_checksums = set(
                        (
                            await _load_existing_image_checksums(db, project_obj.id)
                        ).keys()
                    )

            image_urls = data.get("image_urls")
            if isinstance(image_urls, list) and image_urls:
                data["image_urls"] = await filter_import_image_urls(
                    image_urls,
                    referer=source_url,
                    skip_checksums=existing_gallery_checksums,
                )

            steps = data.get("steps")
            if isinstance(steps, list):
                for step in steps:
                    step_images = step.get("images")
                    if isinstance(step_images, list) and step_images:
                        step["images"] = await filter_import_image_urls(
                            step_images,
                            referer=source_url,
                        )

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
                    data = trim_import_strings(data)
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
            "yarn": None,
            "brand": None,
            "description": content_text[:2000] if content_text else None,
            "notes": None,
            "steps": [],
            "link": source_url,
        }
        if data.get("title") and not data.get("name"):
            data["name"] = data.get("title")
        if trace:
            data["import_trace_id"] = trace.trace_id

        data = trim_import_strings(data)
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

    return await render_template(
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


async def _import_yarn_images_from_urls(
    db: AsyncSession,
    yarn: YarnModel,
    image_urls: Sequence[str],
) -> int:
    """Download and attach imported images to a yarn."""
    if not image_urls:
        return 0

    logger = logging.getLogger("stricknani.imports")
    imported = 0
    seen_checksums: set[str] = set()
    imported_similarities: list[Any] = []

    headers = dict(IMPORT_IMAGE_HEADERS)
    if yarn.link:
        headers["Referer"] = yarn.link

    async with httpx.AsyncClient(
        timeout=IMPORT_IMAGE_TIMEOUT,
        follow_redirects=True,
        headers=headers,
    ) as client:
        for image_url in image_urls:
            if imported >= 5:
                break
            if not _is_valid_import_url(image_url):
                continue

            try:
                response = await client.get(image_url)
                response.raise_for_status()
            except httpx.HTTPError as exc:
                logger.warning("Failed to download yarn image %s: %s", image_url, exc)
                continue

            content_type = response.headers.get("content-type")
            if not _is_allowed_import_image(content_type, image_url):
                continue

            if not response.content or len(response.content) > IMPORT_IMAGE_MAX_BYTES:
                continue

            checksum = compute_checksum(response.content)
            if checksum in seen_checksums:
                continue

            try:
                with PilImage.open(BytesIO(response.content)) as img:
                    width, height = img.size
                    if (
                        width < IMPORT_IMAGE_MIN_DIMENSION
                        or height < IMPORT_IMAGE_MIN_DIMENSION
                    ):
                        continue
                    similarity = build_similarity_image(img)
            except Exception:
                continue

            skip_thumbnail = False
            for candidate_sim in imported_similarities:
                score = compute_similarity_score(candidate_sim, similarity)
                if score is not None and score >= IMPORT_IMAGE_SSIM_THRESHOLD:
                    skip_thumbnail = True
                    break

            if skip_thumbnail:
                continue

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
                logger.warning("Failed to store yarn image %s: %s", image_url, exc)
                continue

            photo = YarnImage(
                filename=filename,
                original_filename=original_filename,
                alt_text=yarn.name or original_filename,
                yarn_id=yarn.id,
                is_primary=(imported == 0),
            )
            db.add(photo)
            imported += 1
            seen_checksums.add(checksum)
            imported_similarities.append(similarity)

    return imported


async def _ensure_yarns_by_text(
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

            # Try to find match in DB
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
                # Create new yarn with all available details
                db_yarn_obj = YarnModel(
                    name=name or "Unknown Yarn",
                    owner_id=user_id,
                    brand=detail.get("brand") or yarn_brand,
                    colorway=detail.get("colorway"),
                    link=link,
                )
                db.add(db_yarn_obj)
                await db.flush()

                # If we have a link, follow it to get more data
                if db_yarn_obj.link:
                    try:
                        from stricknani.utils.importer import (
                            GarnstudioPatternImporter,
                            PatternImporter,
                            _is_garnstudio_url,
                        )

                        im_ptr: PatternImporter
                        if _is_garnstudio_url(db_yarn_obj.link):
                            im_ptr = GarnstudioPatternImporter(db_yarn_obj.link)
                        else:
                            im_ptr = PatternImporter(db_yarn_obj.link)

                        yarn_data = await im_ptr.fetch_and_parse()

                        # Update name if importer found a better one
                        imported_name = yarn_data.get("yarn") or yarn_data.get("title")
                        if imported_name and len(imported_name) > len(db_yarn_obj.name):
                            db_yarn_obj.name = imported_name

                        # Populate more fields if they are missing
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

                        # Import images
                        img_urls = yarn_data.get("image_urls")
                        if img_urls:
                            await _import_yarn_images_from_urls(
                                db, db_yarn_obj, img_urls
                            )

                        # Handle Wayback archival for the yarn link
                        if config.FEATURE_WAYBACK_ENABLED and db_yarn_obj.link:
                            db_yarn_obj.link_archive_requested_at = datetime.now(UTC)
                            await db.flush()
                            asyncio.create_task(
                                store_wayback_snapshot(
                                    YarnModel, db_yarn_obj.id, db_yarn_obj.link
                                )
                            )

                    except Exception as e:
                        logger = logging.getLogger("stricknani.imports")
                        logger.warning(
                            f"Failed to auto-import yarn from {db_yarn_obj.link}: {e}"
                        )

            if db_yarn_obj.id not in updated_ids:
                updated_ids.append(db_yarn_obj.id)

        # If we had structured details, we consider them exhaustive for the text
        return updated_ids

    # 2. Fallback to raw text parsing
    if not yarn_text:
        return updated_ids

    # Normalize yarn names from text.
    # Garnstudio uses newlines for multiple yarns, and commas for color info.
    # We should prefer newline splitting if multiple lines exist.
    if "\n" in yarn_text.strip():
        # Split by newlines and handle "Oder:" (alternative yarn)
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
        # Fallback to comma splitting if it's a single line but avoid splitting
        # on commas that are likely part of a color spec (Garnstudio style)
        if re.search(r"(?:farbe|color|colour)\s*\d+\s*,\s*", yarn_text, re.I):
            yarn_names = [yarn_text.strip()]
        else:
            yarn_names = [n.strip() for n in yarn_text.split(",") if n.strip()]

    if not yarn_names:
        return updated_ids

    # Pre-load already linked yarns names to avoid double linking
    existing_linked_yarns = []
    if updated_ids:
        res = await db.execute(
            select(YarnModel.name).where(YarnModel.id.in_(updated_ids))
        )
        existing_linked_yarns = [row[0].lower() for row in res]

    for name in yarn_names:
        if name.lower() in existing_linked_yarns:
            continue

        # Try to find match in DB
        res_match = await db.execute(
            select(YarnModel).where(
                YarnModel.owner_id == user_id,
                func.lower(YarnModel.name) == name.lower(),
            )
        )
        db_yarn_obj = res_match.scalar_one_or_none()

        if not db_yarn_obj:
            # Create new yarn
            db_yarn_obj = YarnModel(name=name, owner_id=user_id, brand=yarn_brand)
            db.add(db_yarn_obj)
            await db.flush()

        if db_yarn_obj.id not in updated_ids:
            updated_ids.append(db_yarn_obj.id)

    return updated_ids


async def _get_exclusive_yarns(db: AsyncSession, project: Project) -> list[YarnModel]:
    """Return yarns linked ONLY to this project."""
    exclusive = []
    # Project needs to have yarns loaded
    for y in project.yarns:
        # We need to count how many projects this yarn belongs to
        # Since project_yarns is a secondary table, we check it directly
        res = await db.execute(
            select(func.count()).where(project_yarns.c.yarn_id == y.id)
        )
        count = res.scalar() or 0
        if count == 1:
            exclusive.append(y)
    return exclusive


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
            selectinload(Project.attachments),
            selectinload(Project.steps).selectinload(Step.images),
            selectinload(Project.yarns).selectinload(YarnModel.photos),
            selectinload(Project.yarns).selectinload(YarnModel.projects),
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

    # Prepare attachments
    project_attachments = []
    for att in project.attachments:
        thumbnail_url = None
        width, height = None, None
        if (
            att.content_type.startswith("image/")
            or att.content_type == "application/pdf"
        ):
            thumb_path = (
                config.MEDIA_ROOT
                / "thumbnails"
                / "projects"
                / str(project.id)
                / f"thumb_{Path(att.filename).stem}.jpg"
            )
            if thumb_path.exists():
                thumbnail_url = get_thumbnail_url(
                    att.filename, project.id, subdir="projects"
                )
        if att.content_type.startswith("image/"):
            width, height = _get_image_dimensions(att.filename, project.id)
        project_attachments.append(
            {
                "id": att.id,
                "filename": att.filename,
                "original_filename": att.original_filename,
                "content_type": att.content_type,
                "size_bytes": att.size_bytes,
                "url": get_file_url(att.filename, project.id, subdir="projects"),
                "thumbnail_url": thumbnail_url,
                "width": width,
                "height": height,
                "created_at": att.created_at.isoformat(),
            }
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
    exclusive_yarns = await _get_exclusive_yarns(db, project)

    # Check for stale archive request (self-healing)
    if (
        config.FEATURE_WAYBACK_ENABLED
        and project.archive_pending
        and project.link_archive_requested_at
        and project.link
    ):
        # Handle naive datetime from SQLite
        requested_at = project.link_archive_requested_at
        if requested_at.tzinfo is None:
            requested_at = requested_at.replace(tzinfo=UTC)

        elapsed = datetime.now(UTC) - requested_at
        if elapsed.total_seconds() > 900:  # 15 minutes
            # Reset timestamp to now so we don't spam checks immediately
            project.link_archive_requested_at = datetime.now(UTC)
            await db.commit()
            # Retry the snapshot request
            asyncio.create_task(
                store_wayback_snapshot(Project, project.id, project.link)
            )

    project_data = {
        "id": project.id,
        "name": project.name,
        "category": project.category,
        "yarn": project.yarn,
        "needles": project.needles,
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
        "notes": project.notes or "",
        "notes_html": render_markdown(project.notes, f"project-{project.id}")
        if project.notes
        else None,
        "link": project.link,
        "link_archive": project.link_archive,
        "link_archive_failed": project.link_archive_failed,
        "link_archive_requested_at": project.link_archive_requested_at,
        "link_archive_fallback": (
            build_wayback_fallback_url(project.link) if project.link else None
        ),
        "archive_pending": project.archive_pending,
        "created_at": project.created_at.isoformat(),
        "updated_at": project.updated_at.isoformat(),
        "is_ai_enhanced": project.is_ai_enhanced,
        "title_images": title_images,
        "stitch_sample_images": stitch_sample_images,
        "attachments": project_attachments,
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
        "exclusive_yarns": [
            {
                "id": yarn.id,
                "name": yarn.name,
                "brand": yarn.brand,
                "colorway": yarn.colorway,
                **_resolve_yarn_preview(yarn),
            }
            for yarn in exclusive_yarns
        ],
    }

    return await render_template(
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
            selectinload(Project.attachments),
            selectinload(Project.steps).selectinload(Step.images),
            selectinload(Project.yarns).selectinload(YarnModel.projects),
            selectinload(Project.yarns).selectinload(YarnModel.photos),
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

    # Prepare attachments
    project_attachments = []
    for att in project.attachments:
        thumbnail_url = None
        width, height = None, None
        if (
            att.content_type.startswith("image/")
            or att.content_type == "application/pdf"
        ):
            thumb_path = (
                config.MEDIA_ROOT
                / "thumbnails"
                / "projects"
                / str(project.id)
                / f"thumb_{Path(att.filename).stem}.jpg"
            )
            if thumb_path.exists():
                thumbnail_url = get_thumbnail_url(
                    att.filename, project.id, subdir="projects"
                )
        if att.content_type.startswith("image/"):
            width, height = _get_image_dimensions(att.filename, project.id)
        project_attachments.append(
            {
                "id": att.id,
                "filename": att.filename,
                "original_filename": att.original_filename,
                "content_type": att.content_type,
                "size_bytes": att.size_bytes,
                "url": get_file_url(att.filename, project.id, subdir="projects"),
                "thumbnail_url": thumbnail_url,
                "width": width,
                "height": height,
                "created_at": att.created_at.isoformat(),
            }
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

    exclusive_yarns = await _get_exclusive_yarns(db, project)
    project_data = {
        "id": project.id,
        "name": project.name,
        "category": project.category,
        "yarn": project.yarn,
        "needles": project.needles,
        "stitch_sample": project.stitch_sample or "",
        "description": project.description or "",
        "notes": project.notes or "",
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
        "attachments": project_attachments,
        "steps": steps_data,
        "tags": project.tag_list(),
        "yarn_ids": [y.id for y in project.yarns],
        "exclusive_yarns": [
            {
                "id": yarn.id,
                "name": yarn.name,
                "brand": yarn.brand,
                "colorway": yarn.colorway,
                **_resolve_yarn_preview(yarn),
            }
            for yarn in exclusive_yarns
        ],
    }

    categories = await _get_user_categories(db, current_user.id)
    yarn_options = await _get_user_yarns(db, current_user.id)
    tag_suggestions = await _get_user_tags(db, current_user.id)

    return await render_template(
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
    notes: Annotated[str | None, Form()] = None,
    stitch_sample: Annotated[str | None, Form()] = None,
    description: Annotated[str | None, Form()] = None,
    tags: Annotated[str | None, Form()] = None,
    link: Annotated[str | None, Form()] = None,
    steps_data: Annotated[str | None, Form()] = None,
    yarn_ids: Annotated[str | None, Form()] = None,
    yarn_text: Annotated[str | None, Form()] = None,
    yarn_details: Annotated[str | None, Form()] = None,
    yarn_brand: Annotated[str | None, Form()] = None,
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

    # Parse structured yarn details if available
    parsed_yarn_details = None
    if yarn_details:
        try:
            parsed_yarn_details = json.loads(yarn_details)
        except json.JSONDecodeError:
            pass

    # Ensure yarn matches or creates
    parsed_yarn_ids = await _ensure_yarns_by_text(
        db,
        current_user.id,
        yarn_text,
        parsed_yarn_ids,
        yarn_brand=yarn_brand,
        yarn_details=parsed_yarn_details,
    )

    normalized_category = await _ensure_category(db, current_user.id, category)
    normalized_tags = _normalize_tags(tags)

    project = Project(
        name=name.strip(),
        category=normalized_category,
        needles=needles.strip() if needles else None,
        stitch_sample=stitch_sample.strip() if stitch_sample else None,
        description=description.strip() if description else None,
        notes=notes.strip() if notes else None,
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
            step_description = step_data.get("description")
            if isinstance(step_description, str):
                step_data["description"] = await _localize_garnstudio_symbol_images(
                    project.id,
                    step_description,
                    referer=project.link,
                )
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
    notes: Annotated[str | None, Form()] = None,
    stitch_sample: Annotated[str | None, Form()] = None,
    description: Annotated[str | None, Form()] = None,
    tags: Annotated[str | None, Form()] = None,
    link: Annotated[str | None, Form()] = None,
    steps_data: Annotated[str | None, Form()] = None,
    yarn_ids: Annotated[str | None, Form()] = None,
    yarn_text: Annotated[str | None, Form()] = None,
    yarn_details: Annotated[str | None, Form()] = None,
    yarn_brand: Annotated[str | None, Form()] = None,
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

    # Parse structured yarn details if available
    parsed_yarn_details = None
    if yarn_details:
        try:
            parsed_yarn_details = json.loads(yarn_details)
        except json.JSONDecodeError:
            pass

    # Ensure yarn matches or creates
    parsed_yarn_ids = await _ensure_yarns_by_text(
        db,
        current_user.id,
        yarn_text,
        parsed_yarn_ids,
        yarn_brand=yarn_brand,
        yarn_details=parsed_yarn_details,
    )

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
    project.stitch_sample = stitch_sample.strip() if stitch_sample else None
    project.description = description.strip() if description else None
    project.notes = notes.strip() if notes else None
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
            step_description = step_data.get("description")
            if isinstance(step_description, str):
                step_data["description"] = await _localize_garnstudio_symbol_images(
                    project.id,
                    step_description,
                    referer=project.link,
                )
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
            url=f"/projects/{project.id}?toast=archive_requested",
            status_code=status.HTTP_303_SEE_OTHER,
        )

    return RedirectResponse(
        url=f"/projects/{project.id}?toast=archive_request_unavailable",
        status_code=status.HTTP_303_SEE_OTHER,
    )


@router.delete("/{project_id}", response_class=Response)
async def delete_project(
    project_id: int,
    request: Request,
    delete_yarns: bool = False,
    delete_yarn_ids: list[int] | None = Query(default=None),
    db: AsyncSession = Depends(get_db),
    current_user: User = Depends(require_auth),
) -> Response:
    """Delete a project."""
    result = await db.execute(
        select(Project)
        .where(Project.id == project_id)
        .options(
            selectinload(Project.images),
            selectinload(Project.steps),
            selectinload(Project.yarns).selectinload(YarnModel.projects),
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

    # Handle exclusive yarn deletion
    exclusive_yarns_to_delete: list[YarnModel] = []
    if delete_yarn_ids:
        exclusive_yarns = await _get_exclusive_yarns(db, project)
        exclusive_by_id = {yarn.id: yarn for yarn in exclusive_yarns}
        for yarn_id in delete_yarn_ids:
            yarn = exclusive_by_id.get(yarn_id)
            if yarn:
                exclusive_yarns_to_delete.append(yarn)
    elif delete_yarns:
        exclusive_yarns_to_delete = await _get_exclusive_yarns(db, project)

    if exclusive_yarns_to_delete:
        for yarn in exclusive_yarns_to_delete:
            # Delete yarn media if any
            yarn_media_dir = config.MEDIA_ROOT / "yarns" / str(yarn.id)
            yarn_thumb_dir = config.MEDIA_ROOT / "thumbnails" / "yarns" / str(yarn.id)
            import shutil

            if yarn_media_dir.exists():
                shutil.rmtree(yarn_media_dir)
            if yarn_thumb_dir.exists():
                shutil.rmtree(yarn_thumb_dir)
            await db.delete(yarn)

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
            response = await render_template(
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

    width, height = _get_image_dimensions(filename, project_id)

    return JSONResponse(
        {
            "id": image.id,
            "url": get_file_url(filename, project_id),
            "thumbnail_url": get_thumbnail_url(filename, project_id),
            "alt_text": image.alt_text,
            "width": width,
            "height": height,
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

    width, height = _get_image_dimensions(filename, project_id)

    return JSONResponse(
        {
            "id": image.id,
            "url": get_file_url(filename, project_id),
            "thumbnail_url": get_thumbnail_url(filename, project_id),
            "alt_text": image.alt_text,
            "width": width,
            "height": height,
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

    width, height = _get_image_dimensions(filename, project_id)

    return JSONResponse(
        {
            "id": image.id,
            "url": get_file_url(filename, project_id),
            "thumbnail_url": get_thumbnail_url(filename, project_id),
            "alt_text": image.alt_text,
            "width": width,
            "height": height,
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


@router.post("/{project_id}/attachments")
async def upload_attachment(
    project_id: int,
    file: UploadFile = File(...),
    db: AsyncSession = Depends(get_db),
    current_user: User = Depends(require_auth),
) -> JSONResponse:
    """Upload an attachment to a project."""
    logger = logging.getLogger(__name__)
    project = await db.get(Project, project_id)
    if not project:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND, detail="Project not found"
        )
    if project.owner_id != current_user.id:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN, detail="Not authorized"
        )

    content = await file.read()
    size_bytes = len(content)

    # Save file
    filename, original_filename = save_bytes(
        content, file.filename or "file", project_id
    )

    thumbnail_url: str | None = None
    content_type = file.content_type or "application/octet-stream"
    width, height = None, None
    thumb_path = (
        config.MEDIA_ROOT
        / "thumbnails"
        / "projects"
        / str(project_id)
        / f"thumb_{Path(filename).stem}.jpg"
    )
    source_path = config.MEDIA_ROOT / "projects" / str(project_id) / filename
    if content_type.startswith("image/"):
        width, height = _get_image_dimensions(filename, project_id)
        try:
            await create_thumbnail(source_path, project_id, subdir="projects")
            if thumb_path.exists():
                thumbnail_url = get_thumbnail_url(
                    filename, project_id, subdir="projects"
                )
        except Exception:
            logger.info("Could not create attachment thumbnail for %s", filename)
    elif content_type == "application/pdf":
        try:
            await asyncio.to_thread(
                create_pdf_thumbnail, source_path, project_id, "projects"
            )
            if thumb_path.exists():
                thumbnail_url = get_thumbnail_url(
                    filename, project_id, subdir="projects"
                )
        except Exception:
            logger.info("Could not create PDF thumbnail for %s", filename)

    attachment = Attachment(
        filename=filename,
        original_filename=original_filename,
        content_type=content_type,
        size_bytes=size_bytes,
        project_id=project_id,
    )
    db.add(attachment)
    await db.commit()
    await db.refresh(attachment)

    return JSONResponse(
        {
            "id": attachment.id,
            "filename": attachment.filename,
            "original_filename": attachment.original_filename,
            "content_type": attachment.content_type,
            "size_bytes": attachment.size_bytes,
            "url": get_file_url(attachment.filename, project_id, subdir="projects"),
            "thumbnail_url": thumbnail_url,
            "width": width,
            "height": height,
        }
    )


@router.delete("/{project_id}/attachments/{attachment_id}")
async def delete_attachment(
    project_id: int,
    attachment_id: int,
    db: AsyncSession = Depends(get_db),
    current_user: User = Depends(require_auth),
) -> Response:
    """Delete an attachment from a project."""
    attachment = await db.get(Attachment, attachment_id)
    if not attachment or attachment.project_id != project_id:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND, detail="Attachment not found"
        )

    project = await db.get(Project, project_id)
    if not project or project.owner_id != current_user.id:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN, detail="Not authorized"
        )

    # Delete file from storage
    delete_file(attachment.filename, project_id)

    await db.delete(attachment)
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
