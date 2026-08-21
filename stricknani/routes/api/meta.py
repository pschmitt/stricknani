"""Server identity endpoint for the JSON API."""

from __future__ import annotations

import time

from fastapi import APIRouter

from stricknani import __version__
from stricknani.routes.api.schemas import MetaResponse

router: APIRouter = APIRouter(tags=["api-meta"])

# Set once at process start, like `stricknani.main`'s `dev_reload_token` - lets
# a client detect it needs a full resync after a deploy/restart, independent
# of whether the version number itself changed.
_BUILD_ID = f"{__version__}-{time.time_ns()}"


@router.get("/meta", response_model=MetaResponse)
async def get_meta() -> MetaResponse:
    """Server version/build id, so the app can detect a breaking upgrade."""
    return MetaResponse(version=__version__, build_id=_BUILD_ID)
