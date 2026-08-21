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

Clients that need to cap response size can add `limit=1..50` to the project
or yarn endpoint.  The response then includes at most that many combined
updated entities and deletion ids, plus `has_more` and an opaque `next_cursor`
when another page exists.  Send that cursor as `cursor` for the next request;
it carries the original `since` boundary and a fixed server snapshot so pages
do not move the sync window.  `cursor` and `since` are mutually exclusive.
Omitting `limit` retains the original unbounded response for existing clients.
"""

from __future__ import annotations

import base64
import binascii
import json
from dataclasses import dataclass
from datetime import UTC, datetime
from typing import Any, Literal

from fastapi import APIRouter, Depends, HTTPException, Query, status
from sqlalchemy import and_, or_, select
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

SYNC_PAGE_MAX = 50
_CURSOR_VERSION = 1


@dataclass(frozen=True)
class _Position:
    """A stable keyset position within one of the two change feeds."""

    changed_at: datetime
    row_id: int


@dataclass(frozen=True)
class _SyncCursor:
    """Opaque pagination state reconstructed from a client cursor."""

    entity_type: Literal["project", "yarn"]
    user_id: int
    since: datetime | None
    snapshot: datetime
    updated: _Position | None
    deleted: _Position | None
    limit: int


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


def _as_utc(dt: datetime) -> datetime:
    return dt if dt.tzinfo is not None else dt.replace(tzinfo=UTC)


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


def _invalid_cursor() -> HTTPException:
    return HTTPException(
        status_code=status.HTTP_400_BAD_REQUEST,
        detail="Invalid sync cursor",
    )


def _position_payload(position: _Position | None) -> dict[str, Any] | None:
    if position is None:
        return None
    return {"at": position.changed_at.isoformat(), "id": position.row_id}


def _parse_position(value: object) -> _Position | None:
    if value is None:
        return None
    if not isinstance(value, dict):
        raise ValueError("position is not an object")
    changed_at = value.get("at")
    row_id = value.get("id")
    if (
        not isinstance(changed_at, str)
        or isinstance(row_id, bool)
        or not isinstance(row_id, int)
    ):
        raise ValueError("position has invalid fields")
    normalized = _normalize_since(datetime.fromisoformat(changed_at))
    if normalized is None or row_id < 1:
        raise ValueError("position has invalid values")
    return _Position(changed_at=normalized, row_id=row_id)


def _encode_cursor(cursor: _SyncCursor) -> str:
    payload = {
        "v": _CURSOR_VERSION,
        "entity": cursor.entity_type,
        "user_id": cursor.user_id,
        "since": cursor.since.isoformat() if cursor.since is not None else None,
        "snapshot": cursor.snapshot.isoformat(),
        "updated": _position_payload(cursor.updated),
        "deleted": _position_payload(cursor.deleted),
        "limit": cursor.limit,
    }
    encoded = base64.urlsafe_b64encode(
        json.dumps(payload, separators=(",", ":"), sort_keys=True).encode()
    )
    return encoded.decode().rstrip("=")


def _decode_cursor(
    raw: str, *, entity_type: Literal["project", "yarn"], user_id: int
) -> _SyncCursor:
    try:
        padded = raw + "=" * (-len(raw) % 4)
        payload = json.loads(base64.urlsafe_b64decode(padded).decode())
        if not isinstance(payload, dict):
            raise ValueError("cursor is not an object")
        if (
            payload.get("v") != _CURSOR_VERSION
            or payload.get("entity") != entity_type
            or isinstance(payload.get("user_id"), bool)
            or not isinstance(payload.get("user_id"), int)
            or payload.get("user_id") != user_id
        ):
            raise ValueError("cursor metadata does not match request")

        raw_since = payload.get("since")
        if raw_since is not None and not isinstance(raw_since, str):
            raise ValueError("cursor has an invalid since value")
        since = (
            _normalize_since(datetime.fromisoformat(raw_since))
            if isinstance(raw_since, str)
            else None
        )
        raw_snapshot = payload.get("snapshot")
        if not isinstance(raw_snapshot, str):
            raise ValueError("cursor has no snapshot")
        snapshot = _as_utc(datetime.fromisoformat(raw_snapshot))
        raw_limit = payload.get("limit")
        if (
            isinstance(raw_limit, bool)
            or not isinstance(raw_limit, int)
            or not 1 <= raw_limit <= SYNC_PAGE_MAX
        ):
            raise ValueError("cursor has invalid page settings")
        return _SyncCursor(
            entity_type=entity_type,
            user_id=user_id,
            since=since,
            snapshot=snapshot,
            updated=_parse_position(payload.get("updated")),
            deleted=_parse_position(payload.get("deleted")),
            limit=raw_limit,
        )
    except (
        ValueError,
        TypeError,
        UnicodeDecodeError,
        json.JSONDecodeError,
        binascii.Error,
    ):
        raise _invalid_cursor() from None


def _page_cursor(
    *,
    raw_cursor: str | None,
    since: datetime | None,
    limit: int | None,
    entity_type: Literal["project", "yarn"],
    user_id: int,
    snapshot: datetime,
) -> _SyncCursor | None:
    """Resolve legacy and paginated requests into one keyset context."""
    if raw_cursor is not None:
        if since is not None:
            raise HTTPException(
                status_code=status.HTTP_400_BAD_REQUEST,
                detail="Use either since or cursor, not both",
            )
        cursor = _decode_cursor(raw_cursor, entity_type=entity_type, user_id=user_id)
        if limit is not None:
            cursor = _SyncCursor(
                entity_type=cursor.entity_type,
                user_id=cursor.user_id,
                since=cursor.since,
                snapshot=cursor.snapshot,
                updated=cursor.updated,
                deleted=cursor.deleted,
                limit=limit,
            )
        return cursor

    if limit is None:
        # No limit is the original unbounded API contract.  Keep it intact so
        # existing Android clients cannot silently drop rows they do not know
        # how to request from a subsequent page.
        return None

    return _SyncCursor(
        entity_type=entity_type,
        user_id=user_id,
        since=_normalize_since(since),
        snapshot=snapshot,
        updated=None,
        deleted=None,
        limit=limit,
    )


def _after_position(column: Any, row_id_column: Any, position: _Position) -> Any:
    return or_(
        column > position.changed_at,
        and_(column == position.changed_at, row_id_column > position.row_id),
    )


def _choose_events(
    updated: list[tuple[datetime, int, Any]],
    deleted: list[tuple[datetime, int, int]],
    limit: int,
) -> tuple[list[Any], list[int], _Position | None, _Position | None, bool]:
    """Merge update and deletion feeds into one bounded, deterministic page."""
    updated_index = 0
    deleted_index = 0
    selected_updated: list[Any] = []
    selected_deleted: list[int] = []
    updated_position: _Position | None = None
    deleted_position: _Position | None = None

    while len(selected_updated) + len(selected_deleted) < limit:
        next_updated = updated[updated_index] if updated_index < len(updated) else None
        next_deleted = deleted[deleted_index] if deleted_index < len(deleted) else None
        if next_updated is None and next_deleted is None:
            break

        # Updates win an exact timestamp tie; the source is part of the
        # ordering, so the two independent queries form one total order.
        use_updated = next_deleted is None or (
            next_updated is not None
            and (next_updated[0], 0, next_updated[1])
            <= (next_deleted[0], 1, next_deleted[1])
        )
        if use_updated:
            assert next_updated is not None
            changed_at, row_id, entity = next_updated
            selected_updated.append(entity)
            updated_position = _Position(changed_at, row_id)
            updated_index += 1
        else:
            assert next_deleted is not None
            changed_at, row_id, entity_id = next_deleted
            selected_deleted.append(entity_id)
            deleted_position = _Position(changed_at, row_id)
            deleted_index += 1

    has_more = updated_index < len(updated) or deleted_index < len(deleted)
    return (
        selected_updated,
        selected_deleted,
        updated_position,
        deleted_position,
        has_more,
    )


@router.get("/projects", response_model=ProjectSyncResponse)
async def sync_projects(
    since: datetime | None = Query(None),
    limit: int | None = Query(
        None,
        ge=1,
        le=SYNC_PAGE_MAX,
        description=(
            "Optional page size; omitting it preserves the complete legacy response."
        ),
    ),
    cursor: str | None = Query(
        None,
        max_length=4096,
        description="Opaque cursor returned by the previous bounded page.",
    ),
    db: AsyncSession = Depends(get_db),
    current_user: User = Depends(require_api_token),
) -> ProjectSyncResponse:
    server_time = datetime.now(UTC)
    page = _page_cursor(
        raw_cursor=cursor,
        since=since,
        limit=limit,
        entity_type="project",
        user_id=current_user.id,
        snapshot=server_time,
    )

    if page is None:
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

    query = (
        select(Project)
        .where(Project.owner_id == current_user.id)
        .options(*_DETAIL_OPTIONS)
    )
    if page.updated is not None:
        query = query.where(
            _after_position(Project.updated_at, Project.id, page.updated)
        )
    elif page.since is not None:
        query = query.where(Project.updated_at > page.since)
    query = query.where(Project.updated_at <= _normalize_since(page.snapshot))
    query = query.order_by(Project.updated_at.asc(), Project.id.asc()).limit(
        page.limit + 1
    )
    result = await db.execute(query)
    projects = list(result.scalars().all())

    deleted_query = select(AuditLog).where(
        AuditLog.entity_type == "project",
        AuditLog.action == "deleted",
        AuditLog.actor_user_id == current_user.id,
        AuditLog.created_at <= _normalize_since(page.snapshot),
    )
    if page.deleted is not None:
        deleted_query = deleted_query.where(
            _after_position(AuditLog.created_at, AuditLog.id, page.deleted)
        )
    elif page.since is not None:
        deleted_query = deleted_query.where(AuditLog.created_at > page.since)
    deleted_result = await db.execute(
        deleted_query.order_by(AuditLog.created_at.asc(), AuditLog.id.asc()).limit(
            page.limit + 1
        )
    )
    deletion_events = [
        (entry.created_at, entry.id, entry.entity_id)
        for entry in deleted_result.scalars().all()
    ]
    update_events = [(project.updated_at, project.id, project) for project in projects]
    (
        selected_projects,
        selected_deleted_ids,
        updated_position,
        deleted_position,
        has_more,
    ) = _choose_events(update_events, deletion_events, page.limit)
    next_cursor = None
    if has_more:
        next_cursor = _encode_cursor(
            _SyncCursor(
                entity_type=page.entity_type,
                user_id=page.user_id,
                since=page.since,
                snapshot=page.snapshot,
                updated=updated_position or page.updated,
                deleted=deleted_position or page.deleted,
                limit=page.limit,
            )
        )

    favorite_ids = await _favorite_project_ids(db, current_user.id)

    return ProjectSyncResponse(
        updated=[
            _serialize_project(project, is_favorite=project.id in favorite_ids)
            for project in selected_projects
        ],
        deleted_ids=selected_deleted_ids,
        server_time=page.snapshot,
        has_more=has_more,
        next_cursor=next_cursor,
    )


@router.get("/yarns", response_model=YarnSyncResponse)
async def sync_yarns(
    since: datetime | None = Query(None),
    limit: int | None = Query(
        None,
        ge=1,
        le=SYNC_PAGE_MAX,
        description=(
            "Optional page size; omitting it preserves the complete legacy response."
        ),
    ),
    cursor: str | None = Query(
        None,
        max_length=4096,
        description="Opaque cursor returned by the previous bounded page.",
    ),
    db: AsyncSession = Depends(get_db),
    current_user: User = Depends(require_api_token),
) -> YarnSyncResponse:
    server_time = datetime.now(UTC)
    page = _page_cursor(
        raw_cursor=cursor,
        since=since,
        limit=limit,
        entity_type="yarn",
        user_id=current_user.id,
        snapshot=server_time,
    )

    if page is None:
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
                _serialize_yarn(yarn, is_favorite=yarn.id in favorite_ids)
                for yarn in yarns
            ],
            deleted_ids=deleted_ids,
            server_time=server_time,
        )

    query = (
        select(Yarn)
        .where(Yarn.owner_id == current_user.id)
        .options(selectinload(Yarn.photos), selectinload(Yarn.projects))
    )
    if page.updated is not None:
        query = query.where(_after_position(Yarn.updated_at, Yarn.id, page.updated))
    elif page.since is not None:
        query = query.where(Yarn.updated_at > page.since)
    query = query.where(Yarn.updated_at <= _normalize_since(page.snapshot))
    query = query.order_by(Yarn.updated_at.asc(), Yarn.id.asc()).limit(page.limit + 1)
    result = await db.execute(query)
    yarns = list(result.scalars().all())

    deleted_query = select(AuditLog).where(
        AuditLog.entity_type == "yarn",
        AuditLog.action == "deleted",
        AuditLog.actor_user_id == current_user.id,
        AuditLog.created_at <= _normalize_since(page.snapshot),
    )
    if page.deleted is not None:
        deleted_query = deleted_query.where(
            _after_position(AuditLog.created_at, AuditLog.id, page.deleted)
        )
    elif page.since is not None:
        deleted_query = deleted_query.where(AuditLog.created_at > page.since)
    deleted_result = await db.execute(
        deleted_query.order_by(AuditLog.created_at.asc(), AuditLog.id.asc()).limit(
            page.limit + 1
        )
    )
    deletion_events = [
        (entry.created_at, entry.id, entry.entity_id)
        for entry in deleted_result.scalars().all()
    ]
    update_events = [(yarn.updated_at, yarn.id, yarn) for yarn in yarns]
    (
        selected_yarns,
        selected_deleted_ids,
        updated_position,
        deleted_position,
        has_more,
    ) = _choose_events(update_events, deletion_events, page.limit)
    next_cursor = None
    if has_more:
        next_cursor = _encode_cursor(
            _SyncCursor(
                entity_type=page.entity_type,
                user_id=page.user_id,
                since=page.since,
                snapshot=page.snapshot,
                updated=updated_position or page.updated,
                deleted=deleted_position or page.deleted,
                limit=page.limit,
            )
        )

    favorite_ids = await _favorite_yarn_ids(db, current_user.id)

    return YarnSyncResponse(
        updated=[
            _serialize_yarn(yarn, is_favorite=yarn.id in favorite_ids)
            for yarn in selected_yarns
        ],
        deleted_ids=selected_deleted_ids,
        server_time=page.snapshot,
        has_more=has_more,
        next_cursor=next_cursor,
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
