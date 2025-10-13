"""Project routes."""

import json
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
from sqlalchemy import delete, insert, select
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm import selectinload

from stricknani.config import config
from stricknani.database import get_db
from stricknani.main import get_language, render_template, templates
from stricknani.models import (
    Image,
    ImageType,
    Project,
    ProjectCategory,
    Step,
    User,
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
from stricknani.utils.markdown import render_markdown
from stricknani.utils.i18n import install_i18n

router = APIRouter(prefix="/projects", tags=["projects"])


def get_categories() -> list[str]:
    """Get list of project categories."""
    return [cat.value for cat in ProjectCategory]


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
) -> HTMLResponse:
    """List all projects for the current user."""
    if not current_user:
        return RedirectResponse(url="/login", status_code=status.HTTP_303_SEE_OTHER)

    query = select(Project).where(Project.owner_id == current_user.id)

    if category:
        query = query.where(Project.category == category)

    if search:
        query = query.where(Project.name.ilike(f"%{search}%"))

    query = query.order_by(Project.created_at.desc())

    result = await db.execute(query)
    projects = result.scalars().all()

    favorite_rows = await db.execute(
        select(user_favorites.c.project_id).where(
            user_favorites.c.user_id == current_user.id
        )
    )
    favorite_ids = {row[0] for row in favorite_rows}

    # If this is an HTMX request, only return the projects list
    if request.headers.get("HX-Request"):
        projects_data = [
            {
                "id": p.id,
                "name": p.name,
                "category": p.category,
                "created_at": p.created_at.isoformat(),
                "is_favorite": p.id in favorite_ids,
            }
            for p in projects
        ]
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

    projects_data = [
        {
            "id": p.id,
            "name": p.name,
            "category": p.category,
            "created_at": p.created_at.isoformat(),
            "is_favorite": p.id in favorite_ids,
        }
        for p in projects
    ]

    return render_template(
        "projects/list.html",
        request,
        {
            "current_user": current_user,
            "projects": projects_data,
            "categories": get_categories(),
        },
    )


@router.get("/new", response_class=HTMLResponse)
async def new_project_form(
    request: Request,
    current_user: User | None = Depends(get_current_user),
) -> HTMLResponse:
    """Show new project form."""
    if not current_user:
        return RedirectResponse(url="/login", status_code=status.HTTP_303_SEE_OTHER)

    return render_template(
        "projects/form.html",
        request,
        {
            "current_user": current_user,
            "project": None,
            "categories": get_categories(),
        },
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
        for img in project.images if img.is_title_image
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
        "comment_html": render_markdown(project.comment) if project.comment else None,
        "created_at": project.created_at.isoformat(),
        "updated_at": project.updated_at.isoformat(),
        "title_images": title_images,
        "steps": [
            {
                **step,
                "description_html": render_markdown(step["description"])
                if step["description"]
                else "",
            }
            for step in base_steps
        ],
        "is_favorite": is_favorite,
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
    }

    return render_template(
        "projects/form.html",
        request,
        {
            "current_user": current_user,
            "project": project_data,
            "categories": get_categories(),
        },
    )


@router.post("/")
async def create_project(
    name: Annotated[str, Form()],
    category: Annotated[str, Form()],
    yarn: Annotated[str | None, Form()] = None,
    needles: Annotated[str | None, Form()] = None,
    gauge_stitches: Annotated[int | None, Form()] = None,
    gauge_rows: Annotated[int | None, Form()] = None,
    comment: Annotated[str | None, Form()] = None,
    steps_data: Annotated[str | None, Form()] = None,
    db: AsyncSession = Depends(get_db),
    current_user: User = Depends(require_auth),
) -> RedirectResponse:
    """Create a new project."""
    project = Project(
        name=name,
        category=category,
        yarn=yarn,
        needles=needles,
        gauge_stitches=gauge_stitches,
        gauge_rows=gauge_rows,
        comment=comment,
        owner_id=current_user.id,
    )
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
    yarn: Annotated[str | None, Form()] = None,
    needles: Annotated[str | None, Form()] = None,
    gauge_stitches: Annotated[int | None, Form()] = None,
    gauge_rows: Annotated[int | None, Form()] = None,
    comment: Annotated[str | None, Form()] = None,
    steps_data: Annotated[str | None, Form()] = None,
    db: AsyncSession = Depends(get_db),
    current_user: User = Depends(require_auth),
) -> RedirectResponse:
    """Update a project."""
    result = await db.execute(
        select(Project)
        .where(Project.id == project_id)
        .options(selectinload(Project.steps))
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

    project.name = name
    project.category = category
    project.yarn = yarn
    project.needles = needles
    project.gauge_stitches = gauge_stitches
    project.gauge_rows = gauge_rows
    project.comment = comment

    # Update steps
    if steps_data:
        steps_list = json.loads(steps_data)
        existing_step_ids = {step.id for step in project.steps}
        new_step_ids = {
            step_data.get("id")
            for step_data in steps_list
            if step_data.get("id")
        }

        # Delete removed steps
        steps_to_delete = existing_step_ids - new_step_ids
        if steps_to_delete:
            await db.execute(
                delete(Step).where(Step.id.in_(steps_to_delete))
            )

        # Update or create steps
        for step_data in steps_list:
            step_id = step_data.get("id")
            if step_id and step_id in existing_step_ids:
                # Update existing step
                step_result = await db.execute(
                    select(Step).where(Step.id == step_id)
                )
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


@router.delete("/{project_id}")
async def delete_project(
    project_id: int,
    db: AsyncSession = Depends(get_db),
    current_user: User = Depends(require_auth),
) -> dict[str, str]:
    """Delete a project."""
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

    await db.delete(project)
    await db.commit()

    return {"message": "Project deleted"}


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

    return JSONResponse({
        "id": image.id,
        "url": get_file_url(filename, project_id),
        "thumbnail_url": get_thumbnail_url(filename, project_id),
        "alt_text": image.alt_text,
    })


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

    return JSONResponse({
        "id": image.id,
        "url": get_file_url(filename, project_id),
        "thumbnail_url": get_thumbnail_url(filename, project_id),
        "alt_text": image.alt_text,
    })


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
