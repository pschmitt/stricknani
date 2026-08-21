"""Authorized media serving (T70).

``/media`` used to be a raw, unauthenticated ``StaticFiles`` mount. T59 added
transport-level hardening (nosniff, ``Content-Disposition``, a deny-list for
internal import directories, an upload extension allowlist) but explicitly
left per-object ownership authorization for a follow-up: anyone with a
guessed or leaked URL could still view another user's private photos or
attachments (IDOR).

This module replaces that mount with routes that resolve the owning object
(project, yarn, or user) for a requested media path and only stream the file
once the current authenticated user is confirmed to own it.

Stricknani has no concept of publicly-viewable/demo projects: the seeded
"demo" account (``stricknani/scripts/seed_demo.py``) is an ordinary user
whose content is only visible to itself (like any other user), so no
unauthenticated bypass is needed here.

Authenticated via ``require_auth_or_api_token`` (T74/SNA-4), so the same
routes serve both the browser (session cookie) and the Android app
(``Authorization: Bearer <token>``), which loads project/yarn images
straight from the JSON API responses' URLs.
"""

from __future__ import annotations

from collections.abc import Callable, Coroutine
from pathlib import Path

from fastapi import APIRouter, Depends, HTTPException, status
from fastapi.responses import FileResponse
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from stricknani.config import config
from stricknani.database import get_db
from stricknani.models import Project, User, Yarn
from stricknani.routes.auth import require_auth_or_api_token

router: APIRouter = APIRouter(tags=["media"])

# Mirrors ImmutableStaticFiles: media filenames are unique (timestamp + short
# uuid), so a given URL always maps to the same bytes.
_IMMUTABLE_CACHE_CONTROL = "public, max-age=31536000, immutable"

# T107: project attachments keep the client-supplied extension verbatim (no
# allowlist at upload time, unlike images - see stricknani/services/projects/
# attachments.py), so a browser-renderable extension like .svg/.html/.htm can
# end up stored as-is. Only serve extensions we know are safe to render in
# the browser as `inline`; everything else is forced to download instead, so
# an attacker who uploads e.g. `evil.svg` as an "attachment" can't get it
# rendered (and any embedded script executed) from the app's own origin.
_INLINE_SAFE_EXTENSIONS: frozenset[str] = frozenset(
    {".jpg", ".jpeg", ".png", ".gif", ".webp", ".pdf"}
)


async def _project_owner_id(entity_id: int, db: AsyncSession) -> int | None:
    result = await db.execute(select(Project.owner_id).where(Project.id == entity_id))
    return result.scalar_one_or_none()


async def _yarn_owner_id(entity_id: int, db: AsyncSession) -> int | None:
    result = await db.execute(select(Yarn.owner_id).where(Yarn.id == entity_id))
    return result.scalar_one_or_none()


async def _user_owner_id(entity_id: int, db: AsyncSession) -> int | None:
    # A user's own avatar is "owned" by that same user id. Only look up
    # existence so an unknown user id 404s instead of matching everyone.
    result = await db.execute(select(User.id).where(User.id == entity_id))
    return result.scalar_one_or_none()


# Every legitimate media subdirectory, mapped to a lookup that resolves the
# owning user id for a given entity id. Any subdir not listed here (e.g. the
# internal ``import-traces``/``imports`` directories) is never served.
_OWNER_RESOLVERS: dict[
    str, Callable[[int, AsyncSession], Coroutine[None, None, int | None]]
] = {
    "projects": _project_owner_id,
    "yarns": _yarn_owner_id,
    "users": _user_owner_id,
}


def _is_safe_filename(filename: str) -> bool:
    """Reject empty/traversal-shaped filenames.

    ``entity_id`` is already constrained to ``int`` by FastAPI's path
    converter and ``filename``/``subdir`` are single path segments (no ``/``
    can reach us through normal routing), but we double-check here and again
    via ``_resolve_media_path`` so a routing/encoding quirk can't turn into a
    path traversal.
    """
    return bool(filename) and filename not in {".", ".."} and "/" not in filename


def _resolve_media_path(*parts: str) -> Path | None:
    """Resolve ``parts`` under ``MEDIA_ROOT``, rejecting escapes."""
    root = config.MEDIA_ROOT.resolve()
    candidate = root.joinpath(*parts).resolve()
    try:
        candidate.relative_to(root)
    except ValueError:
        return None
    return candidate


async def _authorize(
    subdir: str,
    entity_id: str,
    filename: str,
    db: AsyncSession,
    current_user: User,
) -> None:
    """Raise 404/403 unless ``current_user`` may access this media object.

    ``entity_id`` is taken as a raw path segment (not an ``int`` path
    param) so that a non-numeric id 404s here like everything else instead
    of surfacing a validation-error status code that would distinguish
    "malformed" from "not found" for an unauthenticated caller.
    """
    resolver = _OWNER_RESOLVERS.get(subdir)
    if resolver is None or not _is_safe_filename(filename):
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND)

    try:
        numeric_entity_id = int(entity_id)
    except ValueError as exc:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND) from exc

    owner_id = await resolver(numeric_entity_id, db)
    if owner_id is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND)

    if owner_id == current_user.id:
        return
    # Admins manage user accounts (including avatars) from the admin panel;
    # mirror that existing capability here. Projects/yarns have no such
    # bypass anywhere else in the app, so none is granted here either.
    if subdir == "users" and current_user.is_admin:
        return

    raise HTTPException(status_code=status.HTTP_403_FORBIDDEN)


def _file_response(file_path: Path | None) -> FileResponse:
    if file_path is None or not file_path.is_file():
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND)
    disposition = "inline"
    if file_path.suffix.lower() not in _INLINE_SAFE_EXTENSIONS:
        disposition = f'attachment; filename="{file_path.name}"'
    return FileResponse(
        file_path,
        headers={
            "X-Content-Type-Options": "nosniff",
            "Content-Disposition": disposition,
            "Cache-Control": _IMMUTABLE_CACHE_CONTROL,
        },
    )


@router.get("/media/thumbnails/{subdir}/{entity_id}/{filename}")
async def serve_media_thumbnail(
    subdir: str,
    entity_id: str,
    filename: str,
    current_user: User = Depends(require_auth_or_api_token),
    db: AsyncSession = Depends(get_db),
) -> FileResponse:
    """Serve a thumbnail once ownership of the source object is confirmed."""
    await _authorize(subdir, entity_id, filename, db, current_user)
    file_path = _resolve_media_path("thumbnails", subdir, entity_id, filename)
    return _file_response(file_path)


@router.get("/media/{subdir}/{entity_id}/{filename}")
async def serve_media(
    subdir: str,
    entity_id: str,
    filename: str,
    current_user: User = Depends(require_auth_or_api_token),
    db: AsyncSession = Depends(get_db),
) -> FileResponse:
    """Serve a media object once ownership of it is confirmed."""
    await _authorize(subdir, entity_id, filename, db, current_user)
    file_path = _resolve_media_path(subdir, entity_id, filename)
    return _file_response(file_path)


@router.get("/media/{path:path}")
async def deny_media(path: str) -> None:
    """Deny any other ``/media`` shape (e.g. ``import-traces``, ``imports``).

    Returns 404 rather than 401/403 so existence of these internal
    directories is not disclosed, matching the previous static-mount
    deny-list behavior from T59.
    """
    raise HTTPException(status_code=status.HTTP_404_NOT_FOUND)
