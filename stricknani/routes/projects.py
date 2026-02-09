"""Project routes."""

import asyncio
import json
import logging
import re
import shutil
from datetime import UTC, datetime
from pathlib import Path
from typing import Annotated, Any

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
from sqlalchemy import delete, func, insert, select, update
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm import joinedload, selectinload

from stricknani.config import config
from stricknani.database import get_db
from stricknani.models import (
    Attachment,
    Category,
    Image,
    Project,
    Step,
    User,
    user_favorites,
)
from stricknani.models import (
    Yarn as YarnModel,
)
from stricknani.routes.auth import get_current_user, require_auth
from stricknani.services.images import get_image_dimensions
from stricknani.services.projects.attachments import (
    consume_pending_project_import_attachment,
    store_pending_project_import_attachment_bytes,
    store_project_attachment,
    store_project_attachment_bytes,
)
from stricknani.services.projects.categories import (
    ensure_category,
    get_user_categories,
    sync_project_categories,
)
from stricknani.services.projects.helpers import (
    build_ai_hints,
    dedupe_project_attachments,
    localize_garnstudio_symbol_images,
)
from stricknani.services.projects.images import (
    upload_step_image as service_upload_step_image,
)
from stricknani.services.projects.images import (
    upload_stitch_sample_image as service_upload_stitch_sample_image,
)
from stricknani.services.projects.images import (
    upload_title_image as service_upload_title_image,
)
from stricknani.services.projects.import_images import (
    import_project_images_from_urls,
    import_step_images_from_urls,
    load_existing_image_checksums,
    load_existing_image_similarities,
)
from stricknani.services.projects.steps import (
    create_step as service_create_step,
)
from stricknani.services.projects.steps import (
    update_step as service_update_step,
)
from stricknani.services.projects.tags import (
    get_user_tags,
    normalize_tags,
    serialize_tags,
)
from stricknani.services.projects.yarns import (
    ensure_yarns_by_text,
    get_exclusive_yarns,
    get_user_yarns,
    load_owned_yarns,
    resolve_yarn_preview,
)
from stricknani.utils.ai_provider import has_ai_api_key
from stricknani.utils.files import (
    delete_file,
    get_file_url,
    get_thumbnail_url,
)
from stricknani.utils.i18n import install_i18n
from stricknani.utils.image_similarity import (
    SimilarityImage,
)
from stricknani.utils.import_trace import ImportTrace
from stricknani.utils.importer import (
    filter_import_image_urls,
    is_garnstudio_url,
    trim_import_strings,
)
from stricknani.utils.markdown import render_markdown
from stricknani.utils.search_tokens import extract_search_token, parse_import_image_urls
from stricknani.utils.wayback import (
    _should_request_archive,
    build_wayback_fallback_url,
    store_wayback_snapshot,
)
from stricknani.web.templating import get_language, render_template, templates

logger = logging.getLogger(__name__)

router = APIRouter(
    prefix="/projects",
    tags=["projects"],
    responses={404: {"description": "Not found"}},
)


def _parse_import_image_urls(raw: list[str] | str | None) -> list[str]:
    return parse_import_image_urls(raw)


def _extract_search_token(search: str, prefix: str) -> tuple[str | None, str]:
    return extract_search_token(search, prefix)


_ensure_yarns_by_text = ensure_yarns_by_text
_get_exclusive_yarns = get_exclusive_yarns


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
        all_tags = await get_user_tags(db, current_user.id)
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

    categories = await get_user_categories(db, current_user.id)

    if request.headers.get("accept") == "application/json":
        return JSONResponse(projects_data)

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
            "has_openai_key": config.FEATURE_AI_IMPORT_ENABLED and has_ai_api_key(),
        },
    )


@router.get("/new", response_class=HTMLResponse)
async def new_project_form(
    request: Request,
    current_user: User | None = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
) -> Response:
    """Show new project form."""
    if not current_user:
        return RedirectResponse(url="/login", status_code=status.HTTP_303_SEE_OTHER)

    categories = await get_user_categories(db, current_user.id)
    yarn_options = await get_user_yarns(db, current_user.id)
    tag_suggestions = await get_user_tags(db, current_user.id)

    # Check if an AI provider API key is available for AI import
    has_openai_key = config.FEATURE_AI_IMPORT_ENABLED and bool(has_ai_api_key())

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
    files: Annotated[list[UploadFile] | None, File()] = None,
    attachment_ids: Annotated[list[int] | None, Form()] = None,
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
        files: Uploaded files (when type='file')
        attachment_ids: IDs of existing attachments to import
        use_ai: If True, use AI-powered extraction (requires AI provider key)
        project_id: Optional project ID to import into
        db: Database session
        current_user: Authenticated user
    """
    files = files or []
    attachment_ids = attachment_ids or []

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
        source_contents: list[dict[str, Any]] = []  # Temporary storage

        # Helper to simplify content type detection
        from stricknani.importing.models import ContentType, RawContent

        def get_content_type(mime: str | None, filename: str | None) -> ContentType:
            mime = mime or ""
            filename = filename or ""
            if mime.startswith("image/") or filename.lower().endswith(
                (".jpg", ".jpeg", ".png", ".webp", ".gif")
            ):
                return ContentType.IMAGE
            if mime == "application/pdf" or filename.lower().endswith(".pdf"):
                return ContentType.PDF
            return ContentType.TEXT

        # Collect uploaded files
        for f in files or []:
            content = await f.read()
            c_type = get_content_type(f.content_type, f.filename)
            source_contents.append(
                {
                    "content": content,
                    "content_type": c_type,
                    "filename": f.filename,
                    "original_mime": f.content_type,
                }
            )

        # Collect attachments
        if attachment_ids:
            # Fetch attachments ensuring they belong to user's project
            # optimizing with one query
            res = await db.execute(
                select(Attachment)
                .join(Project)
                .where(
                    Attachment.id.in_(attachment_ids),
                    Project.owner_id == current_user.id,
                )
            )
            attachments = res.scalars().all()

            for att in attachments:
                file_path = (
                    config.MEDIA_ROOT / "projects" / str(att.project_id) / att.filename
                )
                if file_path.exists():
                    content = file_path.read_bytes()
                    c_type = get_content_type(att.content_type, att.original_filename)
                    source_contents.append(
                        {
                            "content": content,
                            "content_type": c_type,
                            "filename": att.original_filename,
                            "original_mime": att.content_type,
                            "is_attachment": True,  # Flag to avoid re-saving
                            "attachment_id": att.id,
                        }
                    )

        use_ai_enabled = config.FEATURE_AI_IMPORT_ENABLED and bool(has_ai_api_key())

        async def store_source_files_for_import() -> dict[str, Any]:
            if not source_contents:
                return {"import_attachment_tokens": [], "source_attachments": []}

            tokens = []
            attachments_meta = []

            # If project_id exists, we are adding to a project directly
            if project_id is not None:
                res = await db.execute(
                    select(Project).where(
                        Project.id == project_id,
                        Project.owner_id == current_user.id,
                    )
                )
                project_obj = res.scalar_one_or_none()
                if not project_obj:
                    return {"import_attachment_tokens": [], "source_attachments": []}

            for item in source_contents:
                # If it's already an attachment from THIS project, we might
                # skip saving? But imports usually create new derived data.
                # For now, let's treat it as a source.
                # If we are importing FROM an attachment, we don't need to
                # re-save it as an attachment usually?
                # User flow: "From File" -> Select "Attachment.pdf".
                # We use it for ANALYSIS. We don't necessarily need to
                # DUPLICATE it as "Source Attachment".
                # BUT the `source_attachments` list is used to show what was
                # imported. Let's simple check if we need to save.

                if item.get("is_attachment"):
                    # Use existing attachment info
                    # We might need to query it again or just use what we have?
                    # We fetched it above.
                    att_id = item["attachment_id"]
                    # Retrieve full object again or pass it? Simple query again
                    # to get URL logic standardized
                    stored_att = await db.get(Attachment, att_id)  # Should exist
                    if stored_att:
                        attachments_meta.append(
                            {
                                "id": stored_att.id,
                                "filename": stored_att.filename,
                                "original_filename": stored_att.original_filename,
                                "content_type": stored_att.content_type,
                                "size_bytes": stored_att.size_bytes,
                                "url": get_file_url(
                                    stored_att.filename,
                                    stored_att.project_id,
                                    subdir="projects",
                                ),
                                # thumbnail?
                            }
                        )
                    continue

                # It's a new upload
                if project_id is not None:
                    stored = await store_project_attachment_bytes(
                        project_id,
                        content=item["content"],
                        original_filename=item["filename"],
                        content_type=item["original_mime"],
                    )
                    attachment = Attachment(
                        filename=stored.filename,
                        original_filename=stored.original_filename,
                        content_type=stored.content_type,
                        size_bytes=stored.size_bytes,
                        project_id=project_id,
                    )
                    db.add(attachment)
                    await db.commit()
                    await db.refresh(attachment)

                    attachments_meta.append(
                        {
                            "id": attachment.id,
                            "filename": attachment.filename,
                            "original_filename": attachment.original_filename,
                            "content_type": attachment.content_type,
                            "size_bytes": attachment.size_bytes,
                            "url": get_file_url(
                                attachment.filename,
                                project_id,
                                subdir="projects",
                            ),
                            "thumbnail_url": stored.thumbnail_url,
                            "width": stored.width,
                            "height": stored.height,
                        }
                    )
                else:
                    token = await store_pending_project_import_attachment_bytes(
                        current_user.id,
                        content=item["content"],
                        original_filename=item["filename"],
                        content_type=item["original_mime"],
                    )
                    tokens.append(token)

            return {
                "import_attachment_tokens": tokens,
                "source_attachments": attachments_meta,
            }

        # Extract content based on import type
        should_use_files = len(source_contents) > 0

        # Validate file imports have files attached
        if import_type == "file" and not should_use_files:
            raise HTTPException(
                status_code=status.HTTP_400_BAD_REQUEST,
                detail="File is required",
            )

        if import_type == "url" and not should_use_files:
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
            if is_garnstudio_url(url):
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
            if use_ai_enabled and not is_garnstudio_url(url):
                try:
                    from stricknani.utils.ai_importer import AIPatternImporter

                    ai_importer = AIPatternImporter(
                        url,
                        hints=build_ai_hints(basic_data),
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
            existing_gallery_similarities: list[SimilarityImage] | None = None
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
                        (await load_existing_image_checksums(db, project_obj.id)).keys()
                    )
                    existing_gallery_similarities = (
                        await load_existing_image_similarities(db, project_obj.id)
                    )

            image_urls = data.get("image_urls")
            if isinstance(image_urls, list) and image_urls:
                data["image_urls"] = await filter_import_image_urls(
                    image_urls,
                    referer=source_url,
                    skip_checksums=existing_gallery_checksums,
                    skip_similarities=existing_gallery_similarities,
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

        elif should_use_files:
            # File / Attachment Import Logic
            from stricknani.importing.extractors.ai import OPENAI_AVAILABLE, AIExtractor
            from stricknani.importing.models import ContentType, RawContent

            if use_ai_enabled and not OPENAI_AVAILABLE:
                # If user requested AI but it's not installed/configured
                # (Though earlier check might have handled missing AI API key)
                # If files are images/PDF, we generally need AI.
                # If pure text, we might survive.
                pass

            # Prepare raw contents
            raw_input_list = []
            for item in source_contents:
                # Determine proper content type enum if not already
                ctype = ContentType.UNKNOWN
                existing_type = item["content_type"]

                if isinstance(existing_type, ContentType):
                    ctype = existing_type
                else:
                    # Fallback if it somehow is a string
                    if existing_type == "application/pdf":
                        ctype = ContentType.PDF
                    elif isinstance(existing_type, str) and existing_type.startswith(
                        "image/"
                    ):
                        ctype = ContentType.IMAGE
                    elif isinstance(existing_type, str) and (
                        existing_type.startswith("text/")
                        or existing_type in ("application/json",)
                    ):
                        ctype = ContentType.TEXT

                raw_input_list.append(
                    RawContent(
                        content=item["content"],
                        content_type=ctype,
                        metadata={"filename": item["filename"]},
                    )
                )

            # If AI is enabled (preferred for files)
            if use_ai_enabled:
                if not OPENAI_AVAILABLE:
                    raise HTTPException(
                        status_code=503, detail="AI libraries not available"
                    )

                extractor = AIExtractor()
                try:
                    extracted = await extractor.extract(raw_input_list)
                except Exception as e:
                    logger.error(f"AI Extraction failed: {e}")
                    raise HTTPException(
                        status_code=400, detail=f"Analysis failed: {str(e)}"
                    ) from e

                # Map extracted data
                data = {
                    "name": extracted.name,
                    "description": extracted.description,
                    "category": extracted.category,
                    "yarn": extracted.yarn,
                    "needles": extracted.needles,
                    "other_materials": extracted.other_materials,
                    "stitch_sample": extracted.stitch_sample,
                    "brand": extracted.brand,
                    "steps": [
                        {
                            "step_number": step.step_number,
                            "title": step.title,
                            "description": step.description,
                            "images": step.images,
                        }
                        for step in extracted.steps
                    ],
                    # Don't add uploaded images to gallery
                    # - they should be attachments only
                    "image_urls": [],
                    "link": None,
                    "is_ai_enhanced": True,
                }

                # Handle PDF Pages from extras
                if extracted.extras and "pdf_rendered_pages" in extracted.extras:
                    rendered_pages = extracted.extras["pdf_rendered_pages"]
                    tokens = []
                    src_atts = []
                    for i, img_bytes in enumerate(rendered_pages):
                        fname = f"pdf_page_{i + 1}.jpg"
                        token = await store_pending_project_import_attachment_bytes(
                            current_user.id,
                            content=img_bytes,
                            original_filename=fname,
                            content_type="image/jpeg",
                        )
                        tokens.append(token)
                        url = get_file_url(
                            fname, entity_id=current_user.id, pending_token=token
                        )
                        src_atts.append(
                            {
                                "id": None,
                                "token": token,
                                "original_filename": fname,
                                "content_type": "image/jpeg",
                                "size_bytes": len(img_bytes),
                                "url": url,
                                "thumbnail_url": url,
                            }
                        )

                    data.setdefault("import_attachment_tokens", []).extend(tokens)
                    data.setdefault("source_attachments", []).extend(src_atts)

                # Store the uploaded source files/attachments
                source_file_data = await store_source_files_for_import()
                if source_file_data.get("import_attachment_tokens"):
                    data.setdefault("import_attachment_tokens", []).extend(
                        source_file_data["import_attachment_tokens"]
                    )
                if source_file_data.get("source_attachments"):
                    data.setdefault("source_attachments", []).extend(
                        source_file_data["source_attachments"]
                    )

                data = trim_import_strings(data)
                return JSONResponse(content=data)

            else:
                # AI Disabled - Check content types
                # Text files can be processed without AI
                all_text = all(
                    item["content_type"] == ContentType.TEXT for item in source_contents
                )

                if all_text and source_contents:
                    # Process text files without AI
                    combined_text = "\n\n".join(
                        item["content"].decode("utf-8", errors="ignore")
                        for item in source_contents
                    )
                    data = {
                        "name": "Imported Text File",
                        "description": combined_text,
                        "steps": [{"step_number": 1, "description": combined_text}],
                        "is_ai_enhanced": False,
                    }
                    data = trim_import_strings(data)
                    return JSONResponse(content=data)
                else:
                    # PDF/Image files require AI
                    raise HTTPException(
                        status_code=status.HTTP_422_UNPROCESSABLE_CONTENT,
                        detail="AI processing is required for PDF/image file imports",
                    )

        elif import_type == "text":
            if not text or not text.strip():
                raise HTTPException(status_code=400, detail="Text required")
            content_text = text.strip()

            if use_ai_enabled:
                from stricknani.importing.extractors.ai import (
                    OPENAI_AVAILABLE,
                    AIExtractor,
                )
                from stricknani.importing.models import ContentType, RawContent

                if not OPENAI_AVAILABLE:
                    raise HTTPException(
                        status_code=503, detail="AI libraries not available"
                    )

                extractor = AIExtractor()
                extracted = await extractor.extract(
                    RawContent(
                        content=content_text.encode("utf-8"),
                        content_type=ContentType.TEXT,
                        metadata={"filename": "text_import.txt"},
                    )
                )

                data = {
                    "name": extracted.name,
                    "description": extracted.description,
                    "category": extracted.category,
                    "yarn": extracted.yarn,
                    "needles": extracted.needles,
                    "other_materials": extracted.other_materials,
                    "stitch_sample": extracted.stitch_sample,
                    "brand": extracted.brand,
                    "steps": [
                        {
                            "step_number": step.step_number,
                            "title": step.title,
                            "description": step.description,
                            "images": step.images,
                        }
                        for step in extracted.steps
                    ],
                    "image_urls": extracted.image_urls,
                    "link": None,
                    "is_ai_enhanced": True,
                }
                data = trim_import_strings(data)
                return JSONResponse(content=data)
            else:
                # Basic Parser fallback logic (replicated/simplified)
                from stricknani.utils.importer import PatternImporter

                # PatternImporter takes URL, cannot easily parse raw text?
                # The original code just did nothing or had logic in `finally`?
                # I'll just return basic object.
                data = {
                    "name": "Imported Text",
                    "description": content_text,
                    "steps": [{"step_number": 1, "description": content_text}],
                    "is_ai_enhanced": False,
                }
                data = trim_import_strings(data)
                return JSONResponse(content=data)

        else:
            raise HTTPException(
                status_code=400, detail=f"Invalid import type: {import_type}"
            )

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
    await sync_project_categories(db, current_user.id)

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
            width, height = await get_image_dimensions(att.filename, project.id)
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
    project_attachments = dedupe_project_attachments(project_attachments)

    # Prepare project-level images (exclude step images)
    title_images = []
    stitch_sample_images = []
    has_seen_title = False

    # Sort images to ensure consistent title selection (primary first, then by ID)
    sorted_images = sorted(project.images, key=lambda i: (not i.is_title_image, i.id))

    for img in sorted_images:
        if img.step_id is not None:
            continue

        width, height = await get_image_dimensions(img.filename, project.id)
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
            width, height = await get_image_dimensions(img.filename, project.id)
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

    favorite_rows = await db.execute(
        select(user_favorites.c.project_id).where(
            user_favorites.c.user_id == current_user.id
        )
    )
    favorite_ids = {row[0] for row in favorite_rows}
    is_favorite = project.id in favorite_ids

    # Swipe navigation: follow the same ordering as the list view (favorites first, then
    # name).
    nav_rows = await db.execute(
        select(Project.id, Project.name).where(Project.owner_id == current_user.id)
    )
    nav_projects = [(row[0], row[1] or "") for row in nav_rows]
    nav_projects.sort(
        key=lambda item: (
            item[0] not in favorite_ids,
            item[1].casefold(),
            item[0],
        )
    )
    nav_ids = [item[0] for item in nav_projects]
    swipe_prev_href = None
    swipe_next_href = None
    try:
        idx = nav_ids.index(project.id)
    except ValueError:
        idx = -1
    if idx > 0:
        swipe_prev_href = f"/projects/{nav_ids[idx - 1]}"
    if idx != -1 and idx < len(nav_ids) - 1:
        swipe_next_href = f"/projects/{nav_ids[idx + 1]}"
    exclusive_yarns = await get_exclusive_yarns(db, project)

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
        "other_materials": project.other_materials or "",
        "other_materials_html": render_markdown(
            project.other_materials, f"project-{project.id}"
        )
        if project.other_materials
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
                **resolve_yarn_preview(yarn),
            }
            for yarn in project.yarns
        ],
        "exclusive_yarns": [
            {
                "id": yarn.id,
                "name": yarn.name,
                "brand": yarn.brand,
                "colorway": yarn.colorway,
                **resolve_yarn_preview(yarn),
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
            "swipe_prev_href": swipe_prev_href,
            "swipe_next_href": swipe_next_href,
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
            width, height = await get_image_dimensions(att.filename, project.id)
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
    project_attachments = dedupe_project_attachments(project_attachments)

    title_images = []
    stitch_sample_images = []
    has_seen_title = False

    # Sort images to ensure consistent title selection (primary first, then by ID)
    sorted_images = sorted(project.images, key=lambda i: (not i.is_title_image, i.id))

    for img in sorted_images:
        if img.step_id is not None:
            continue
        width, height = await get_image_dimensions(img.filename, project.id)

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
            width, height = await get_image_dimensions(img.filename, project.id)
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

    exclusive_yarns = await get_exclusive_yarns(db, project)
    project_data = {
        "id": project.id,
        "name": project.name,
        "category": project.category,
        "yarn": project.yarn,
        "needles": project.needles,
        "stitch_sample": project.stitch_sample or "",
        "description": project.description or "",
        "notes": project.notes or "",
        "other_materials": project.other_materials or "",
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
                **resolve_yarn_preview(yarn),
            }
            for yarn in exclusive_yarns
        ],
    }

    categories = await get_user_categories(db, current_user.id)
    yarn_options = await get_user_yarns(db, current_user.id)
    tag_suggestions = await get_user_tags(db, current_user.id)

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
            "has_openai_key": has_ai_api_key(),
        },
    )


@router.post("/")
async def create_project(
    request: Request,
    name: Annotated[str, Form()],
    category: Annotated[str | None, Form()] = None,
    needles: Annotated[str | None, Form()] = None,
    notes: Annotated[str | None, Form()] = None,
    other_materials: Annotated[str | None, Form()] = None,
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
    import_attachment_tokens: Annotated[str | None, Form()] = None,
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
    parsed_yarn_ids = await ensure_yarns_by_text(
        db,
        current_user.id,
        yarn_text,
        parsed_yarn_ids,
        yarn_brand=yarn_brand,
        yarn_details=parsed_yarn_details,
    )

    normalized_category = await ensure_category(db, current_user.id, category)
    normalized_tags = normalize_tags(tags)

    project = Project(
        name=name.strip(),
        category=normalized_category,
        needles=needles.strip() if needles else None,
        stitch_sample=stitch_sample.strip() if stitch_sample else None,
        description=description.strip() if description else None,
        notes=notes.strip() if notes else None,
        other_materials=other_materials.strip() if other_materials else None,
        link=link.strip() if link else None,
        owner_id=current_user.id,
        tags=serialize_tags(normalized_tags),
        is_ai_enhanced=bool(is_ai_enhanced),
    )
    if project.link and _should_request_archive(archive_on_save):
        project.link_archive_requested_at = datetime.now(UTC)
    project.yarns = list(await load_owned_yarns(db, current_user.id, parsed_yarn_ids))
    project.yarn = project.yarns[0].name if project.yarns else None
    db.add(project)
    await db.flush()  # Get project ID

    # Cache for consumed tokens to allow same image in multiple places
    token_cache: dict[str, tuple[bytes, str, str]] = {}
    permanently_saved_tokens: set[str] = set()
    post_commit_file_deletes: list[str] = []

    async def get_token_data(t: str) -> tuple[bytes, str, str]:
        if t in token_cache:
            return token_cache[t]
        data = await consume_pending_project_import_attachment(current_user.id, token=t)
        token_cache[t] = data
        return data

    # Create steps if provided
    if steps_data:
        steps_list = json.loads(steps_data)
        for step_data in steps_list:
            step_description = step_data.get("description")
            if isinstance(step_description, str):
                step_data["description"] = await localize_garnstudio_symbol_images(
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
            step_images = step_data.get("image_urls") or step_data.get("images")
            if step_images:
                # Handle both regular URLs and temporary pdf_image URLs
                regular_urls = []
                for url in step_images:
                    if "/media/imports/projects/" in url:
                        # Extract token from URL
                        # Format: /media/imports/projects/{user_id}/{token}{ext}
                        match = re.search(r"/([a-f0-9]{32})\.[a-z]{3,4}$", url)
                        if match:
                            token = match.group(1)
                            try:
                                (
                                    pending_bytes,
                                    original_filename,
                                    content_type,
                                ) = await get_token_data(token)
                                # Mock UploadFile for the service
                                import io

                                from fastapi import UploadFile
                                from starlette.datastructures import Headers

                                from stricknani.services.projects.images import (
                                    upload_step_image,
                                )

                                mock_file = UploadFile(
                                    filename=original_filename,
                                    file=io.BytesIO(pending_bytes),
                                    headers=Headers({"content-type": content_type}),
                                )
                                await upload_step_image(
                                    db,
                                    step_id=step.id,
                                    project_id=project.id,
                                    file=mock_file,
                                )
                                permanently_saved_tokens.add(token)
                            except FileNotFoundError:
                                logger.warning(
                                    "Could not find pending image for token %s", token
                                )
                    else:
                        regular_urls.append(url)

                if regular_urls:
                    await import_step_images_from_urls(
                        db,
                        step,
                        regular_urls,
                        permanently_saved_tokens=permanently_saved_tokens,
                        deferred_deletions=post_commit_file_deletes,
                    )

    image_urls = _parse_import_image_urls(import_image_urls)
    if image_urls:
        # Handle both regular URLs and temporary import URLs
        regular_project_urls = []
        for url in image_urls:
            if "/media/imports/projects/" in url:
                # Extract token from URL
                match = re.search(r"/([a-f0-9]{32})\.[a-z]{3,4}$", url)
                if match:
                    token = match.group(1)
                    try:
                        (
                            pending_bytes,
                            original_filename,
                            content_type,
                        ) = await get_token_data(token)

                        # Skip if it was already saved to a step
                        if token in permanently_saved_tokens:
                            continue

                        # Guard: Skip rendered PDF pages in the gallery
                        # (embedded images are allowed in the gallery).
                        if original_filename.startswith("pdf_page_"):
                            continue

                        # Mock UploadFile for the service
                        import io

                        from fastapi import UploadFile
                        from starlette.datastructures import Headers

                        from stricknani.services.projects.images import (
                            upload_title_image,
                        )

                        mock_file = UploadFile(
                            filename=original_filename,
                            file=io.BytesIO(pending_bytes),
                            headers=Headers({"content-type": content_type}),
                        )
                        # Check if this should be the title image
                        is_title = url == import_title_image_url
                        upload_result = await upload_title_image(
                            db,
                            project_id=project.id,
                            file=mock_file,
                            alt_text=original_filename,
                        )
                        permanently_saved_tokens.add(token)
                        if is_title and "id" in upload_result:
                            # Force this one as title image
                            await db.execute(
                                update(Image)
                                .where(Image.project_id == project.id)
                                .values(is_title_image=False)
                            )
                            await db.execute(
                                update(Image)
                                .where(Image.id == int(str(upload_result["id"])))
                                .values(is_title_image=True)
                            )
                    except FileNotFoundError:
                        # Might have been consumed by a step
                        pass
            else:
                regular_project_urls.append(url)

        if regular_project_urls:
            await import_project_images_from_urls(
                db,
                project,
                regular_project_urls,
                title_url=import_title_image_url,
                permanently_saved_tokens=permanently_saved_tokens,
                deferred_deletions=post_commit_file_deletes,
            )

    # Attach imported source files that were uploaded before the project existed.
    # This also handles any remaining PDF images not already consumed by steps
    # or gallery.
    if import_attachment_tokens:
        try:
            raw_tokens = json.loads(import_attachment_tokens)
        except json.JSONDecodeError:
            raw_tokens = []

        tokens: list[str] = [
            t for t in raw_tokens if isinstance(t, str) and len(t) == 32
        ]
        for token in tokens:
            if token in permanently_saved_tokens:
                continue

            try:
                (
                    pending_bytes,
                    original_filename,
                    content_type,
                ) = await get_token_data(token)
            except FileNotFoundError:
                # Might have been consumed by a step or gallery loop above
                continue

            # Save as a regular attachment if it's NOT a gallery image
            # or it's a rendered PDF page.
            is_pdf_asset = original_filename.startswith("pdf_page_")
            if (
                is_pdf_asset
                or not content_type
                or not content_type.startswith("image/")
            ):
                stored = await store_project_attachment_bytes(
                    project.id,
                    content=pending_bytes,
                    original_filename=original_filename,
                    content_type=content_type,
                )
                db.add(
                    Attachment(
                        filename=stored.filename,
                        original_filename=stored.original_filename,
                        content_type=stored.content_type,
                        size_bytes=stored.size_bytes,
                        project_id=project.id,
                    )
                )
                permanently_saved_tokens.add(token)
            else:
                # It's a general image (not from PDF pages) - save to gallery
                # but ONLY if we haven't saved it yet (dedupe)
                import io

                from fastapi import UploadFile
                from starlette.datastructures import Headers

                from stricknani.services.projects.images import upload_title_image

                mock_file = UploadFile(
                    filename=original_filename,
                    file=io.BytesIO(pending_bytes),
                    headers=Headers({"content-type": content_type}),
                )
                # upload_title_image now has internal checksum deduplication
                await upload_title_image(
                    db,
                    project_id=project.id,
                    file=mock_file,
                    alt_text=original_filename,
                )
                permanently_saved_tokens.add(token)

    await db.commit()
    for filename in post_commit_file_deletes:
        try:
            delete_file(filename, project.id)
        except OSError as exc:
            logger.warning(
                "Failed to remove replaced project image file %s: %s",
                filename,
                exc,
            )
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
    other_materials: Annotated[str | None, Form()] = None,
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
    import_attachment_tokens: Annotated[str | None, Form()] = None,
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
    parsed_yarn_ids = await ensure_yarns_by_text(
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
    project.category = await ensure_category(db, current_user.id, category)
    selected_yarns = list(await load_owned_yarns(db, current_user.id, parsed_yarn_ids))
    project.yarns = selected_yarns
    project.yarn = selected_yarns[0].name if selected_yarns else None
    project.needles = needles.strip() if needles else None
    project.stitch_sample = stitch_sample.strip() if stitch_sample else None
    project.description = description.strip() if description else None
    project.notes = notes.strip() if notes else None
    project.other_materials = other_materials.strip() if other_materials else None
    project.link = link.strip() if link else None
    project.tags = serialize_tags(normalize_tags(tags))
    project.is_ai_enhanced = bool(is_ai_enhanced)
    if project.link and _should_request_archive(archive_on_save):
        project.link_archive_requested_at = datetime.now(UTC)

    # Cache for consumed tokens to allow same image in multiple places
    token_cache: dict[str, tuple[bytes, str, str]] = {}
    permanently_saved_tokens: set[str] = set()
    post_commit_file_deletes: list[str] = []

    async def get_token_data(t: str) -> tuple[bytes, str, str]:
        if t in token_cache:
            return token_cache[t]
        data = await consume_pending_project_import_attachment(current_user.id, token=t)
        token_cache[t] = data
        return data

    image_urls = _parse_import_image_urls(import_image_urls)
    if image_urls:
        # Handle both regular URLs and temporary import URLs
        regular_project_urls = []
        for url in image_urls:
            if "/media/imports/projects/" in url:
                # Extract token from URL
                match = re.search(r"/([a-f0-9]{32})\.[a-z]{3,4}$", url)
                if match:
                    token = match.group(1)
                    try:
                        (
                            pending_bytes,
                            original_filename,
                            content_type,
                        ) = await get_token_data(token)

                        # Skip if it was already saved to a step
                        if token in permanently_saved_tokens:
                            continue

                        # Guard: Skip PDF assets in the gallery
                        # they should be attachments only.
                        if original_filename.startswith(
                            "pdf_image_"
                        ) or original_filename.startswith("pdf_page_"):
                            continue

                        # Mock UploadFile for the service
                        import io

                        from fastapi import UploadFile
                        from starlette.datastructures import Headers

                        from stricknani.services.projects.images import (
                            upload_title_image,
                        )

                        mock_file = UploadFile(
                            filename=original_filename,
                            file=io.BytesIO(pending_bytes),
                            headers=Headers({"content-type": content_type}),
                        )
                        # Check if this should be the title image
                        is_title = url == import_title_image_url
                        upload_result = await upload_title_image(
                            db,
                            project_id=project.id,
                            file=mock_file,
                            alt_text=original_filename,
                        )
                        permanently_saved_tokens.add(token)
                        if is_title and "id" in upload_result:
                            # Force this one as title image
                            await db.execute(
                                update(Image)
                                .where(Image.project_id == project.id)
                                .values(is_title_image=False)
                            )
                            await db.execute(
                                update(Image)
                                .where(Image.id == int(str(upload_result["id"])))
                                .values(is_title_image=True)
                            )
                    except FileNotFoundError:
                        # Might have been consumed by a step
                        pass
            else:
                regular_project_urls.append(url)

        if regular_project_urls:
            await import_project_images_from_urls(
                db,
                project,
                regular_project_urls,
                deferred_deletions=post_commit_file_deletes,
            )

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
                post_commit_file_deletes.append(img.filename)

            # Explicitly delete image records from DB since bulk Step delete
            # doesn't trigger ORM cascades
            await db.execute(delete(Image).where(Image.step_id.in_(steps_to_delete)))
            await db.execute(delete(Step).where(Step.id.in_(steps_to_delete)))

        # Update or create steps
        for step_data in steps_list:
            step_description = step_data.get("description")
            if isinstance(step_description, str):
                step_data["description"] = await localize_garnstudio_symbol_images(
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

            step_images = step_data.get("image_urls") or step_data.get("images")
            if step_images:
                # Handle both regular URLs and temporary pdf_image URLs
                regular_urls = []
                for url in step_images:
                    if "/media/imports/projects/" in url:
                        # Extract token from URL
                        match = re.search(r"/([a-f0-9]{32})\.[a-z]{3,4}$", url)
                        if match:
                            token = match.group(1)
                            try:
                                (
                                    pending_bytes,
                                    original_filename,
                                    content_type,
                                ) = await get_token_data(token)
                                # Mock UploadFile for the service
                                import io

                                from fastapi import UploadFile
                                from starlette.datastructures import Headers

                                from stricknani.services.projects.images import (
                                    upload_step_image,
                                )

                                mock_file = UploadFile(
                                    filename=original_filename,
                                    file=io.BytesIO(pending_bytes),
                                    headers=Headers({"content-type": content_type}),
                                )
                                await upload_step_image(
                                    db,
                                    step_id=step.id,
                                    project_id=project.id,
                                    file=mock_file,
                                )
                                permanently_saved_tokens.add(token)
                            except FileNotFoundError:
                                logger.warning(
                                    "Could not find pending image for token %s", token
                                )
                    else:
                        regular_urls.append(url)

                if regular_urls:
                    await import_step_images_from_urls(
                        db,
                        step,
                        regular_urls,
                        permanently_saved_tokens=permanently_saved_tokens,
                        deferred_deletions=post_commit_file_deletes,
                    )

    # Attach imported source files that were uploaded before the project existed.
    # This also handles any remaining PDF images not already consumed by steps
    # or gallery.
    if import_attachment_tokens:
        try:
            raw_tokens = json.loads(import_attachment_tokens)
        except json.JSONDecodeError:
            raw_tokens = []

        tokens: list[str] = [
            t for t in raw_tokens if isinstance(t, str) and len(t) == 32
        ]
        for token in tokens:
            if token in permanently_saved_tokens:
                continue

            try:
                (
                    pending_bytes,
                    original_filename,
                    content_type,
                ) = await get_token_data(token)
            except FileNotFoundError:
                # Might have been consumed by a step or gallery loop above
                continue

            # Save as a regular attachment if it's NOT a gallery image
            # or it's a rendered PDF page.
            is_pdf_asset = original_filename.startswith("pdf_page_")
            if (
                is_pdf_asset
                or not content_type
                or not content_type.startswith("image/")
            ):
                from stricknani.models import Attachment
                from stricknani.services.projects.attachments import (
                    store_project_attachment_bytes,
                )

                stored = await store_project_attachment_bytes(
                    project.id,
                    content=pending_bytes,
                    original_filename=original_filename,
                    content_type=content_type,
                )
                db.add(
                    Attachment(
                        filename=stored.filename,
                        original_filename=stored.original_filename,
                        content_type=stored.content_type,
                        size_bytes=stored.size_bytes,
                        project_id=project.id,
                    )
                )
                permanently_saved_tokens.add(token)
            else:
                # It's a general image (not from PDF pages) - save to gallery
                # but ONLY if we haven't saved it yet (dedupe)
                import io

                from fastapi import UploadFile
                from starlette.datastructures import Headers

                from stricknani.services.projects.images import upload_title_image

                mock_file = UploadFile(
                    filename=original_filename,
                    file=io.BytesIO(pending_bytes),
                    headers=Headers({"content-type": content_type}),
                )
                # upload_title_image now has internal checksum deduplication
                await upload_title_image(
                    db,
                    project_id=project.id,
                    file=mock_file,
                    alt_text=original_filename,
                )
                permanently_saved_tokens.add(token)

    await db.commit()
    for filename in post_commit_file_deletes:
        try:
            delete_file(filename, project_id)
        except OSError as exc:
            logger.warning("Failed to remove step image file %s: %s", filename, exc)

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
        exclusive_yarns = await get_exclusive_yarns(db, project)
        exclusive_by_id = {yarn.id: yarn for yarn in exclusive_yarns}
        for yarn_id in delete_yarn_ids:
            yarn = exclusive_by_id.get(yarn_id)
            if yarn:
                exclusive_yarns_to_delete.append(yarn)
    elif delete_yarns:
        exclusive_yarns_to_delete = await get_exclusive_yarns(db, project)

    yarn_dirs_to_cleanup: list[tuple[Path, Path]] = []
    if exclusive_yarns_to_delete:
        for yarn in exclusive_yarns_to_delete:
            yarn_media_dir = config.MEDIA_ROOT / "yarns" / str(yarn.id)
            yarn_thumb_dir = config.MEDIA_ROOT / "thumbnails" / "yarns" / str(yarn.id)
            yarn_dirs_to_cleanup.append((yarn_media_dir, yarn_thumb_dir))
            await db.delete(yarn)

    project_media_dir = config.MEDIA_ROOT / "projects" / str(project_id)
    project_thumb_dir = config.MEDIA_ROOT / "thumbnails" / "projects" / str(project_id)

    await db.delete(project)
    await db.commit()

    for media_dir, thumb_dir in yarn_dirs_to_cleanup:
        try:
            if media_dir.exists():
                shutil.rmtree(media_dir)
            if thumb_dir.exists():
                shutil.rmtree(thumb_dir)
        except OSError as exc:
            logger.warning("Failed to remove yarn media directories: %s", exc)

    try:
        if project_media_dir.exists():
            shutil.rmtree(project_media_dir)
        if project_thumb_dir.exists():
            shutil.rmtree(project_thumb_dir)
    except OSError as exc:
        logger.warning("Failed to remove project media directories: %s", exc)

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

    is_favorite = False
    if favorite_exists.first():
        await db.execute(
            delete(user_favorites).where(
                user_favorites.c.user_id == current_user.id,
                user_favorites.c.project_id == project_id,
            )
        )
    else:
        await db.execute(
            insert(user_favorites).values(
                user_id=current_user.id, project_id=project_id
            )
        )
        is_favorite = True
    await db.commit()

    if request.headers.get("HX-Request"):
        if variant == "profile" and not is_favorite:
            return HTMLResponse(content="")
        return _render_favorite_toggle(request, project_id, is_favorite, variant)

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

    payload = await service_upload_title_image(
        db,
        project_id=project_id,
        file=file,
        alt_text=alt_text,
    )
    await db.commit()
    return JSONResponse(payload)


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

    payload = await service_upload_stitch_sample_image(
        db,
        project_id=project_id,
        file=file,
        alt_text=alt_text,
    )
    await db.commit()
    return JSONResponse(payload)


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

    payload = await service_upload_step_image(
        db,
        project_id=project_id,
        step_id=step_id,
        file=file,
        alt_text=alt_text,
    )
    await db.commit()
    return JSONResponse(payload)


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

    filename = image.filename
    await db.delete(image)
    await db.commit()
    try:
        delete_file(filename, project_id)
    except OSError as exc:
        logger.warning("Failed to remove project image file %s: %s", filename, exc)

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
    project = await db.get(Project, project_id)
    if not project:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND, detail="Project not found"
        )
    if project.owner_id != current_user.id:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN, detail="Not authorized"
        )

    stored = await store_project_attachment(project_id, file)

    attachment = Attachment(
        filename=stored.filename,
        original_filename=stored.original_filename,
        content_type=stored.content_type,
        size_bytes=stored.size_bytes,
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
            "thumbnail_url": stored.thumbnail_url,
            "width": stored.width,
            "height": stored.height,
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

    filename = attachment.filename
    await db.delete(attachment)
    await db.commit()
    try:
        delete_file(filename, project_id)
    except OSError as exc:
        logger.warning(
            "Failed to remove project attachment file %s: %s",
            filename,
            exc,
        )

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

    def _coerce_step_number(raw: object, default: int) -> int:
        try:
            if isinstance(raw, bool):
                return default
            if isinstance(raw, (int, float, str)):
                return int(raw)
        except (TypeError, ValueError):
            pass
        return default

    step = await service_create_step(
        db,
        project_id=project_id,
        title=str(title),
        description=description if isinstance(description, str) else None,
        step_number=_coerce_step_number(step_number, 1),
    )

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
    title = data.get("title")
    description = data.get("description")
    step_number = data.get("step_number")

    def _coerce_step_number(raw: object, default: int) -> int:
        try:
            if isinstance(raw, bool):
                return default
            if isinstance(raw, (int, float, str)):
                return int(raw)
        except (TypeError, ValueError):
            pass
        return default

    await service_update_step(
        db,
        step=step,
        title=str(title) if isinstance(title, str) else None,
        description=str(description) if isinstance(description, str) else None,
        step_number=_coerce_step_number(step_number, step.step_number)
        if step_number is not None
        else None,
    )

    return {
        "id": step.id,
        "title": step.title,
        "description": step.description,
        "step_number": step.step_number,
    }
