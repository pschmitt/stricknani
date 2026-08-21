"""Versioned JSON API for non-browser clients (the Android app).

Every route here is authenticated via `require_api_token`
(`Authorization: Bearer <token>`, see `stricknani/routes/auth.py`) rather than
the cookie-session flow used by the HTML/HTMX routes, and is exempt from CSRF
validation (see `csrf_validation_dependency` in `stricknani/main.py`).
"""

from __future__ import annotations

from fastapi import APIRouter

from stricknani.routes.api import auth, categories, meta, projects, sync, yarns

router: APIRouter = APIRouter(prefix="/api/v1")
router.include_router(auth.router)
router.include_router(meta.router)
router.include_router(categories.router)
router.include_router(yarns.router)
router.include_router(projects.router)
router.include_router(sync.router)
