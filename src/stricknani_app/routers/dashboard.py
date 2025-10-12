from __future__ import annotations

from collections import defaultdict
from pathlib import Path
from typing import Any

from fastapi import APIRouter, Depends, File, Form, HTTPException, UploadFile
from fastapi.responses import RedirectResponse
from sqlalchemy.orm import Session

from ..dependencies import get_current_user, get_db
from ..models import (
    Category,
    Project,
    ProjectPhoto,
    ProjectStatus,
    ProjectUpdate,
    Task,
    TaskStatus,
)

router = APIRouter(tags=["dashboard"])
UPLOAD_DIRECTORY = Path(__file__).resolve().parent.parent / "static" / "uploads"
UPLOAD_DIRECTORY.mkdir(parents=True, exist_ok=True)


def get_homepage_context(db: Session, user) -> dict[str, Any]:
    categories = db.query(Category).order_by(Category.name).all()
    projects = db.query(Project).order_by(Project.name).all()

    project_summary = []
    for project in projects:
        status_counts = defaultdict(int)
        for task in project.tasks:
            status_counts[task.status] += 1
        project_summary.append(
            {
                "project": project,
                "status_counts": status_counts,
                "latest_update": max(
                    project.updates, key=lambda update: update.created_at, default=None
                ),
            }
        )

    return {"categories": categories, "project_summary": project_summary, "user": user}


def get_project_context(db: Session, user, project_id: int) -> dict[str, Any]:
    project = db.query(Project).filter(Project.id == project_id).first()
    if project is None:
        raise HTTPException(status_code=404)

    tasks_by_status: dict[TaskStatus, list[Task]] = defaultdict(list)
    for task in project.tasks:
        tasks_by_status[task.status].append(task)

    return {
        "project": project,
        "tasks_by_status": tasks_by_status,
        "statuses": list(TaskStatus),
        "user": user,
    }


@router.post("/categories")
async def create_category(
    name: str = Form(...),
    description: str | None = Form(default=None),
    db: Session = Depends(get_db),
    user=Depends(get_current_user),
) -> RedirectResponse:
    category = Category(name=name, description=description)
    db.add(category)
    db.commit()

    return RedirectResponse(url="/", status_code=303)


@router.post("/projects")
async def create_project(
    name: str = Form(...),
    summary: str = Form(...),
    category_id: int | None = Form(default=None),
    status: ProjectStatus = Form(default=ProjectStatus.IDEATION),
    db: Session = Depends(get_db),
    user=Depends(get_current_user),
) -> RedirectResponse:
    project = Project(
        name=name,
        summary=summary,
        category_id=category_id,
        status=status,
    )
    db.add(project)
    db.commit()
    return RedirectResponse(url=f"/projects/{project.id}", status_code=303)


@router.post("/projects/{project_id}/tasks")
async def create_task(
    project_id: int,
    title: str = Form(...),
    notes: str | None = Form(default=None),
    status: TaskStatus = Form(default=TaskStatus.TODO),
    db: Session = Depends(get_db),
    user=Depends(get_current_user),
) -> RedirectResponse:
    task = Task(project_id=project_id, title=title, notes=notes, status=status)
    db.add(task)
    db.commit()
    return RedirectResponse(url=f"/projects/{project_id}", status_code=303)


@router.post("/projects/{project_id}/tasks/{task_id}/status")
async def update_task_status(
    project_id: int,
    task_id: int,
    status: TaskStatus = Form(...),
    db: Session = Depends(get_db),
    user=Depends(get_current_user),
) -> RedirectResponse:
    task = db.query(Task).filter(Task.id == task_id, Task.project_id == project_id).first()
    if task is None:
        raise HTTPException(status_code=404)
    task.status = status
    db.commit()
    return RedirectResponse(url=f"/projects/{project_id}", status_code=303)


@router.post("/projects/{project_id}/updates")
async def create_project_update(
    project_id: int,
    headline: str = Form(...),
    content: str = Form(...),
    db: Session = Depends(get_db),
    user=Depends(get_current_user),
) -> RedirectResponse:
    update = ProjectUpdate(project_id=project_id, headline=headline, content=content)
    db.add(update)
    db.commit()
    return RedirectResponse(url=f"/projects/{project_id}", status_code=303)


@router.post("/projects/{project_id}/photos")
async def upload_photo(
    project_id: int,
    title: str = Form(default=""),
    file: UploadFile = File(...),
    db: Session = Depends(get_db),
    user=Depends(get_current_user),
) -> RedirectResponse:
    file_path = UPLOAD_DIRECTORY / file.filename
    contents = await file.read()
    file_path.write_bytes(contents)
    photo = ProjectPhoto(project_id=project_id, title=title or None, filename=file.filename)
    db.add(photo)
    db.commit()
    return RedirectResponse(url=f"/projects/{project_id}", status_code=303)


__all__ = [
    "router",
    "get_homepage_context",
    "get_project_context",
]
