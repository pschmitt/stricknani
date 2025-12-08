"""Project routes."""

import json
import re
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
from sqlalchemy import delete, func, insert, select, update
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm import selectinload

from stricknani.config import config
from stricknani.database import get_db
from stricknani.main import get_language, render_template, templates
from stricknani.models import (
    Category,
    Image,
    ImageType,
    Project,
    ProjectCategory,
    Step,
    User,
    Yarn,
    user_favorites,
)
from stricknani.routes.auth import get_current_user, require_auth
from stricknani.utils.files import (
    create_thumbnail,
    delete_file,
    get_file_url,
    get_thumbnail_url,
    save_uploaded_file,
)
from stricknani.utils.i18n import install_i18n
from stricknani.utils.markdown import render_markdown

router = APIRouter(prefix="/projects", tags=["projects"])


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


async def _get_user_categories(db: AsyncSession, user_id: int) -> list[str]:
    """Return all categories for a user including defaults."""

    # Get user defined categories
    result = await db.execute(
        select(Category).where(Category.user_id == user_id).order_by(Category.name)
    )
    user_categories = {category.name for category in result.scalars()}

    # Add defaults
    defaults = {cat.value for cat in ProjectCategory}

    # Combine and sort
    return sorted(user_categories | defaults)


async def _get_user_yarns(db: AsyncSession, user_id: int) -> list[Yarn]:
    """Return all yarns for a user ordered by name."""

    result = await db.execute(
        select(Yarn)
        .where(Yarn.owner_id == user_id)
        .order_by(Yarn.name)
        .options(selectinload(Yarn.photos))
    )
    return result.scalars().all()


async def _load_owned_yarns(
    db: AsyncSession, user_id: int, yarn_ids: list[int]
) -> list[Yarn]:
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


async def _ensure_category(db: AsyncSession, user_id: int, name: str) -> str:
    """Ensure category exists for the user and return the sanitized label."""

    cleaned = name.strip()
    if not cleaned:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Category is required",
        )

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

    result = await db.execute(query)
    projects = result.scalars().unique().all()

    favorite_rows = await db.execute(
        select(user_favorites.c.project_id).where(
            user_favorites.c.user_id == current_user.id
        )
    )
    favorite_ids = {row[0] for row in favorite_rows}

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
                preview_images.append({
                    "url": url,
                    "alt": img.alt_text or project.name,
                })

        # Backwards compatibility for templates expecting single image
        thumbnail_url = preview_images[0]["url"] if preview_images else None
        image_alt = preview_images[0]["alt"] if preview_images else project.name

        return {
            "id": project.id,
            "name": project.name,
            "category": project.category,
            "created_at": project.created_at.isoformat(),
            "updated_at": project.updated_at.isoformat(),
            "yarn_count": len(project.yarns),
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
        },
    )


@router.get("/new", response_class=HTMLResponse)
async def new_project_form(
    request: Request,
    current_user: User | None = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
) -> HTMLResponse:
    """Show new project form."""
    if not current_user:
        return RedirectResponse(url="/login", status_code=status.HTTP_303_SEE_OTHER)

    categories = await _get_user_categories(db, current_user.id)
    yarn_options = await _get_user_yarns(db, current_user.id)

    return render_template(
        "projects/form.html",
        request,
        {
            "current_user": current_user,
            "project": None,
            "categories": categories,
            "yarns": yarn_options,
        },
    )


async def _render_categories_page(
    request: Request,
    db: AsyncSession,
    current_user: User,
    *,
    message: str | None = None,
    error: str | None = None,
) -> HTMLResponse:
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
) -> HTMLResponse:
    """Get a specific project."""
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
        "gauge_stitches": project.gauge_stitches,
        "gauge_rows": project.gauge_rows,
        "comment": project.comment or "",
        "comment_html": render_markdown(project.comment, f"project-{project.id}")
        if project.comment
        else None,
        "created_at": project.created_at.isoformat(),
        "updated_at": project.updated_at.isoformat(),
        "title_images": title_images,
        "steps": [
            {
                **step,
                "description_html": render_markdown(
                    step["description"], f"project-{project.id}"
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
) -> HTMLResponse:
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
        "gauge_stitches": project.gauge_stitches,
        "gauge_rows": project.gauge_rows,
        "comment": project.comment or "",
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
    category: Annotated[str, Form()],
    needles: Annotated[str | None, Form()] = None,
    gauge_stitches: Annotated[str | None, Form()] = None,
    gauge_rows: Annotated[str | None, Form()] = None,
    comment: Annotated[str | None, Form()] = None,
    tags: Annotated[str | None, Form()] = None,
    steps_data: Annotated[str | None, Form()] = None,
    yarn_ids: Annotated[list[int] | None, Form()] = None,
    db: AsyncSession = Depends(get_db),
    current_user: User = Depends(require_auth),
) -> RedirectResponse:
    """Create a new project."""
    if yarn_ids is None:
        yarn_ids = []
    gauge_stitches_value = _parse_optional_int("gauge_stitches", gauge_stitches)
    gauge_rows_value = _parse_optional_int("gauge_rows", gauge_rows)
    normalized_category = await _ensure_category(db, current_user.id, category)
    normalized_tags = _normalize_tags(tags)

    project = Project(
        name=name.strip(),
        category=normalized_category,
        needles=needles.strip() if needles else None,
        gauge_stitches=gauge_stitches_value,
        gauge_rows=gauge_rows_value,
        comment=comment.strip() if comment else None,
        owner_id=current_user.id,
        tags=_serialize_tags(normalized_tags),
    )
    project.yarns = await _load_owned_yarns(db, current_user.id, yarn_ids)
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

    await db.commit()
    await db.refresh(project)

    return RedirectResponse(
        url=f"/projects/{project.id}/edit", status_code=status.HTTP_303_SEE_OTHER
    )


@router.post("/{project_id}")
async def update_project(
    project_id: int,
    name: Annotated[str, Form()],
    category: Annotated[str, Form()],
    needles: Annotated[str | None, Form()] = None,
    gauge_stitches: Annotated[str | None, Form()] = None,
    gauge_rows: Annotated[str | None, Form()] = None,
    comment: Annotated[str | None, Form()] = None,
    tags: Annotated[str | None, Form()] = None,
    steps_data: Annotated[str | None, Form()] = None,
    yarn_ids: Annotated[list[int] | None, Form()] = None,
    db: AsyncSession = Depends(get_db),
    current_user: User = Depends(require_auth),
) -> RedirectResponse:
    """Update a project."""
    if yarn_ids is None:
        yarn_ids = []
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
    selected_yarns = await _load_owned_yarns(db, current_user.id, yarn_ids)
    project.yarns = selected_yarns
    project.yarn = selected_yarns[0].name if selected_yarns else None
    project.needles = needles.strip() if needles else None
    project.gauge_stitches = _parse_optional_int("gauge_stitches", gauge_stitches)
    project.gauge_rows = _parse_optional_int("gauge_rows", gauge_rows)
    project.comment = comment.strip() if comment else None
    project.tags = _serialize_tags(_normalize_tags(tags))

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

    await db.commit()

    return RedirectResponse(
        url=f"/projects/{project.id}", status_code=status.HTTP_303_SEE_OTHER
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

    return RedirectResponse(url="/projects", status_code=status.HTTP_303_SEE_OTHER)


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
