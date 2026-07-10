"""Hardened :class:`StaticFiles` subclass for ``/static`` (T57).

User media (``/media``) is no longer served through a static-files mount at
all; see ``stricknani.routes.media`` for the ownership-checked route that
replaced it (T70).
"""

from __future__ import annotations

from fastapi.staticfiles import StaticFiles
from starlette.responses import Response
from starlette.types import Scope

# One year, marked immutable. Safe because bundled vendor assets are
# content-addressed (hashed filenames), so a given URL always maps to the
# same bytes.
_IMMUTABLE_CACHE_CONTROL = "public, max-age=31536000, immutable"


class ImmutableStaticFiles(StaticFiles):
    """Serve static files with a long-lived, immutable ``Cache-Control``."""

    async def get_response(self, path: str, scope: Scope) -> Response:
        response = await super().get_response(path, scope)
        # Only apply aggressive caching to real file responses (2xx). Error
        # responses (e.g. 404) should not be cached for a year.
        if 200 <= response.status_code < 300:
            response.headers["Cache-Control"] = _IMMUTABLE_CACHE_CONTROL
        return response
