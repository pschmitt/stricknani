"""Hardened :class:`StaticFiles` subclasses.

These add long-lived caching headers (T57) and, for user media, defensive
serving headers plus a deny-list for internal directories (T59).
"""

from __future__ import annotations

from pathlib import PurePosixPath

from fastapi import HTTPException
from fastapi.staticfiles import StaticFiles
from starlette.responses import Response
from starlette.types import Scope

# One year, marked immutable. Safe because bundled vendor assets and user
# media are content-addressed (hashed / timestamped filenames), so a given
# URL always maps to the same bytes.
_IMMUTABLE_CACHE_CONTROL = "public, max-age=31536000, immutable"

# Media sub-directories that must never be served to clients. These hold
# import traces and raw import payloads which may contain sensitive data.
_DENIED_MEDIA_DIRS = frozenset({"import-traces", "imports"})


class ImmutableStaticFiles(StaticFiles):
    """Serve static files with a long-lived, immutable ``Cache-Control``."""

    async def get_response(self, path: str, scope: Scope) -> Response:
        response = await super().get_response(path, scope)
        # Only apply aggressive caching to real file responses (2xx). Error
        # responses (e.g. 404) should not be cached for a year.
        if 200 <= response.status_code < 300:
            response.headers["Cache-Control"] = _IMMUTABLE_CACHE_CONTROL
        return response


class MediaStaticFiles(ImmutableStaticFiles):
    """Serve user media defensively (T59).

    - Denies access to internal directories such as ``import-traces`` and
      ``imports`` (returns 404 so their existence is not disclosed).
    - Forces ``X-Content-Type-Options: nosniff`` so browsers cannot be tricked
      into interpreting user-uploaded bytes as a different content type.
    - Sends ``Content-Disposition: inline`` so media renders in-page (images
      are displayed throughout the UI) without allowing active content to run
      in the app's origin via sniffing.

    NOTE: Per-object ownership authorization (only the owner may fetch a given
    media object) is intentionally NOT implemented here. That is a larger
    change touching the media layout / route layer and is left as a follow-up;
    this class only adds transport-level hardening for the serving side.
    """

    async def get_response(self, path: str, scope: Scope) -> Response:
        parts = PurePosixPath(path).parts
        if parts and parts[0] in _DENIED_MEDIA_DIRS:
            raise HTTPException(status_code=404)
        response = await super().get_response(path, scope)
        response.headers["X-Content-Type-Options"] = "nosniff"
        response.headers.setdefault("Content-Disposition", "inline")
        return response
