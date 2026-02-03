"""Global search routes."""

from typing import Any

from fastapi import APIRouter, Depends, Request
from fastapi.responses import HTMLResponse
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm import selectinload

from stricknani.database import get_db
from stricknani.main import render_template
from stricknani.models import Project, User, Yarn
from stricknani.routes.auth import require_auth
from stricknani.utils.files import get_thumbnail_url

router: APIRouter = APIRouter(prefix="/search", tags=["search"])


@router.get("/global", response_class=HTMLResponse)
async def global_search(
    request: Request,
    q: str = "",
    db: AsyncSession = Depends(get_db),
    current_user: User = Depends(require_auth),
) -> HTMLResponse:
    """Global search for projects and yarns."""
    q = q.strip()
    if not q or len(q) < 2:
        return HTMLResponse("")

    # Search Projects
    project_query = (
        select(Project)
        .where(
            Project.owner_id == current_user.id,
            Project.name.ilike(f"%{q}%")
            | Project.category.ilike(f"%{q}%")
            | Project.tags.ilike(f"%{q}%"),
        )
        .options(selectinload(Project.images))
        .limit(10)
    )

    # Search Yarns
    yarn_query = (
        select(Yarn)
        .where(
            Yarn.owner_id == current_user.id,
            Yarn.name.ilike(f"%{q}%")
            | Yarn.brand.ilike(f"%{q}%")
            | Yarn.colorway.ilike(f"%{q}%"),
        )
        .options(selectinload(Yarn.photos))
        .limit(10)
    )

    project_results = await db.execute(project_query)
    yarn_results = await db.execute(yarn_query)

    projects = project_results.scalars().unique().all()
    yarns = yarn_results.scalars().unique().all()

    results: list[dict[str, Any]] = []

    for p in projects:
        # Find title image or first image
        thumb_url = None
        title_img = next((img for img in p.images if img.is_title_image), None)
        if not title_img and p.images:
            title_img = p.images[0]

        if title_img:
            thumb_url = get_thumbnail_url(title_img.filename, p.id, subdir="projects")

        results.append(
            {
                "id": p.id,
                "title": p.name,
                "subtitle": p.category or "",
                "type": "project",
                "url": f"/projects/{p.id}",
                "icon": "mdi-folder-outline",
                "thumbnail_url": thumb_url,
            }
        )

    for y in yarns:
        thumb_url = None
        if y.photos:
            primary = next((img for img in y.photos if img.is_primary), y.photos[0])
            thumb_url = get_thumbnail_url(primary.filename, y.id, subdir="yarns")

        results.append(
            {
                "id": y.id,
                "title": y.name,
                "subtitle": y.brand or "",
                "type": "yarn",
                "url": f"/yarn/{y.id}",
                "icon": "mdi-sheep",
                "thumbnail_url": thumb_url,
            }
        )

    # Sort results by title
    results.sort(key=lambda x: x["title"].lower())

    return await render_template(
        "shared/_global_search_results.html",
        request,
        {
            "results": results,
            "query": q,
        },
    )
