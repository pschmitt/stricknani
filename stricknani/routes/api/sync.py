"""Delta-sync endpoints for the JSON API (SNA-3).

Rather than a naive full refetch on every sync, the app calls these with the
`since` cursor from its last successful sync (the `server_time` field of
that response) and gets back only what changed: entities updated since then,
plus ids deleted since then - sourced from the existing `AuditLog` table
(which already records project/yarn deletions) rather than a new tombstone
table.

`since` is captured *before* running the queries below, not derived from the
max `updated_at`/`created_at` seen in the results: a row committed between
"now" and query execution is safe to see again on the next sync (upserts are
idempotent), whereas deriving the cursor from the results themselves could
skip a row that committed in that same instant.

`full_resync_required` in the response is currently always `False`:
`AuditLog` has no retention/pruning today, so it never actually has a gap
that would make a delta unsafe. A naive "is `since` older than the oldest
`AuditLog` row" check was tried and reverted - it produced false positives
for the completely ordinary case of a `since` captured before the very
first `AuditLog` row a fresh account ever creates, which has nothing to do
with missing deletion coverage. The field is kept in the response shape as
a forward-compatible hook: if a retention/pruning job is ever added for
`AuditLog`, it should record its own cutoff timestamp for this endpoint to
compare `since` against, rather than "oldest row that happens to exist".
"""

from __future__ import annotations

from datetime import UTC, datetime

from fastapi import APIRouter, Depends, Query
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm import selectinload

from stricknani.database import get_db
from stricknani.models import AuditLog, Project, User, Yarn
from stricknani.routes.api.categories import _user_categories
from stricknani.routes.api.projects import (
    _DETAIL_OPTIONS,
    _favorite_project_ids,
    _serialize_project,
)
from stricknani.routes.api.schemas import (
    CategorySyncResponse,
    ProjectSyncResponse,
    YarnSyncResponse,
)
from stricknani.routes.api.yarns import _favorite_yarn_ids, _serialize_yarn
from stricknani.routes.auth import require_api_token

router: APIRouter = APIRouter(prefix="/sync", tags=["api-sync"])


def _normalize_since(since: datetime | None) -> datetime | None:
    """Strip tzinfo (converting to UTC first) from an incoming `since`.

    `created_at`/`updated_at` columns are stored as naive UTC (see the
    "Handle naive datetime from SQLite" pattern elsewhere in this repo).
    SQLite has no real datetime type - it compares the string
    representation SQLAlchemy binds, so a naive value must be compared
    against a naive value or the comparison silently does the wrong thing
    once one side carries a timezone suffix and the other doesn't.
    """
    if since is not None and since.tzinfo is not None:
        return since.astimezone(UTC).replace(tzinfo=None)
    return since


async def _deleted_entity_ids(
    db: AsyncSession, *, entity_type: str, user_id: int, since: datetime
) -> list[int]:
    result = await db.execute(
        select(AuditLog.entity_id).where(
            AuditLog.entity_type == entity_type,
            AuditLog.action == "deleted",
            AuditLog.actor_user_id == user_id,
            AuditLog.created_at > since,
        )
    )
    return [row[0] for row in result]


@router.get("/projects", response_model=ProjectSyncResponse)
async def sync_projects(
    since: datetime | None = Query(None),
    db: AsyncSession = Depends(get_db),
    current_user: User = Depends(require_api_token),
) -> ProjectSyncResponse:
    server_time = datetime.now(UTC)
    since = _normalize_since(since)

    query = (
        select(Project)
        .where(Project.owner_id == current_user.id)
        .options(*_DETAIL_OPTIONS)
    )
    if since is not None:
        query = query.where(Project.updated_at > since)
    result = await db.execute(query)
    projects = list(result.scalars().all())

    favorite_ids = await _favorite_project_ids(db, current_user.id)
    deleted_ids = (
        await _deleted_entity_ids(
            db, entity_type="project", user_id=current_user.id, since=since
        )
        if since is not None
        else []
    )

    return ProjectSyncResponse(
        updated=[
            _serialize_project(project, is_favorite=project.id in favorite_ids)
            for project in projects
        ],
        deleted_ids=deleted_ids,
        server_time=server_time,
    )


@router.get("/yarns", response_model=YarnSyncResponse)
async def sync_yarns(
    since: datetime | None = Query(None),
    db: AsyncSession = Depends(get_db),
    current_user: User = Depends(require_api_token),
) -> YarnSyncResponse:
    server_time = datetime.now(UTC)
    since = _normalize_since(since)

    query = (
        select(Yarn)
        .where(Yarn.owner_id == current_user.id)
        .options(selectinload(Yarn.photos), selectinload(Yarn.projects))
    )
    if since is not None:
        query = query.where(Yarn.updated_at > since)
    result = await db.execute(query)
    yarns = list(result.scalars().all())

    favorite_ids = await _favorite_yarn_ids(db, current_user.id)
    deleted_ids = (
        await _deleted_entity_ids(
            db, entity_type="yarn", user_id=current_user.id, since=since
        )
        if since is not None
        else []
    )

    return YarnSyncResponse(
        updated=[
            _serialize_yarn(yarn, is_favorite=yarn.id in favorite_ids) for yarn in yarns
        ],
        deleted_ids=deleted_ids,
        server_time=server_time,
    )


@router.get("/categories", response_model=CategorySyncResponse)
async def sync_categories(
    # Accepted for a consistent shape with the other two endpoints; see
    # this function's docstring for why it's unused.
    since: datetime | None = Query(None),
    db: AsyncSession = Depends(get_db),
    current_user: User = Depends(require_api_token),
) -> CategorySyncResponse:
    """Category sync always returns the full current list.

    Categories have no `updated_at` and (unlike projects/yarns) their
    deletions aren't recorded in `AuditLog`, so there's no reliable way to
    compute a delta for them. The list is small enough that a full
    "resync" every time is cheap - `since`/`deleted_ids` are accepted for a
    consistent shape with the other two endpoints but are effectively
    unused here.
    """
    server_time = datetime.now(UTC)
    categories = await _user_categories(db, current_user.id)
    return CategorySyncResponse(
        updated=categories, deleted_ids=[], server_time=server_time
    )
