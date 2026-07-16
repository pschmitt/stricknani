"""Hardened :class:`StaticFiles` subclass for ``/static`` (T57).

User media (``/media``) is no longer served through a static-files mount at
all; see ``stricknani.routes.media`` for the ownership-checked route that
replaced it (T70).
"""

from __future__ import annotations

from fastapi.staticfiles import StaticFiles
from starlette.responses import Response
from starlette.types import Scope

# Unlike /media (see stricknani.routes.media), files under /static are NOT
# content-addressed -- app.js, app.css, the built Tailwind bundle, and the
# vendored libraries all keep stable filenames across deploys/version bumps.
# A long `immutable` Cache-Control would make browsers keep serving pre-deploy
# bytes for up to a year. Cache for a short window and require revalidation
# after that instead; Starlette's StaticFiles already sets ETag/Last-Modified,
# so an unchanged file only costs a cheap conditional GET (304), not a full
# re-download.
_CACHE_CONTROL = "public, max-age=300, must-revalidate"


class CachedStaticFiles(StaticFiles):
    """Serve static files with short-lived, revalidated ``Cache-Control``."""

    async def get_response(self, path: str, scope: Scope) -> Response:
        response = await super().get_response(path, scope)
        # Only apply caching to real file responses (2xx). Error responses
        # (e.g. 404) should not be cached.
        if 200 <= response.status_code < 300:
            response.headers["Cache-Control"] = _CACHE_CONTROL
        return response
