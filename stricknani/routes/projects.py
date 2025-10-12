"""Project routes."""

from typing import Annotated

from fastapi import APIRouter, Depends, Form, HTTPException, Request, status
from fastapi.responses import HTMLResponse, RedirectResponse
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from stricknani.database import get_db
from stricknani.main import render_template, templates
from stricknani.models import Project, ProjectCategory, User
from stricknani.routes.auth import get_current_user, require_auth

router = APIRouter(prefix="/projects", tags=["projects"])


def get_categories() -> list[str]:
    """Get list of project categories."""
    return [cat.value for cat in ProjectCategory]


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

    # If this is an HTMX request, only return the projects list
    if request.headers.get("HX-Request"):
        projects_data = [
            {
                "id": p.id,
                "name": p.name,
                "category": p.category,
                "created_at": p.created_at.isoformat(),
            }
            for p in projects
        ]
        return templates.TemplateResponse(
            "projects/_list_partial.html",
            {"request": request, "projects": projects_data},
        )
    
    projects_data = [
        {
            "id": p.id,
            "name": p.name,
            "category": p.category,
            "created_at": p.created_at.isoformat(),
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

    project_data = {
        "id": project.id,
        "name": project.name,
        "category": project.category,
        "yarn": project.yarn,
        "needles": project.needles,
        "gauge_stitches": project.gauge_stitches,
        "gauge_rows": project.gauge_rows,
        "instructions": project.instructions,
        "comment": project.comment,
        "created_at": project.created_at.isoformat(),
        "updated_at": project.updated_at.isoformat(),
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
    
    return render_template(
        "projects/form.html",
        request,
        {
            "current_user": current_user,
            "project": project,
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
    instructions: Annotated[str | None, Form()] = None,
    comment: Annotated[str | None, Form()] = None,
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
        instructions=instructions,
        comment=comment,
        owner_id=current_user.id,
    )
    db.add(project)
    await db.commit()
    await db.refresh(project)

    return RedirectResponse(
        url=f"/projects/{project.id}", status_code=status.HTTP_303_SEE_OTHER
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
    instructions: Annotated[str | None, Form()] = None,
    comment: Annotated[str | None, Form()] = None,
    db: AsyncSession = Depends(get_db),
    current_user: User = Depends(require_auth),
) -> RedirectResponse:
    """Update a project."""
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

    project.name = name
    project.category = category
    project.yarn = yarn
    project.needles = needles
    project.gauge_stitches = gauge_stitches
    project.gauge_rows = gauge_rows
    project.instructions = instructions
    project.comment = comment

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
