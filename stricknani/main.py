"""Main FastAPI application."""

import logging
import sys
import time
from collections.abc import AsyncGenerator, Awaitable, Callable
from contextlib import asynccontextmanager
from pathlib import Path
from typing import Annotated, Any

from fastapi import Depends, FastAPI, Form, HTTPException, Request, Response
from fastapi.responses import FileResponse, HTMLResponse, RedirectResponse
from fastapi_csrf_protect.exceptions import CsrfProtectError
from fastapi_csrf_protect.flexible import CsrfProtect as FlexibleCsrfProtect
from starlette.middleware.trustedhost import TrustedHostMiddleware

from stricknani import __version__
from stricknani.config import config
from stricknani.database import init_db
from stricknani.logging_config import configure_logging
from stricknani.models import User
from stricknani.routes.auth import require_auth
from stricknani.utils.auth import ensure_initial_admin
from stricknani.utils.markdown import render_markdown
from stricknani.web.middleware import SecurityHeadersMiddleware
from stricknani.web.staticfiles import CachedStaticFiles
from stricknani.web.templating import render_template


@asynccontextmanager
async def lifespan(app: FastAPI) -> AsyncGenerator[None]:
    """Application lifespan manager."""
    # Startup
    config.validate_secrets()
    await init_db()
    await ensure_initial_admin()
    yield
    # Shutdown
    pass


configure_logging(debug=config.DEBUG)
if config.SENTRY_DSN_BACKEND:
    import sentry_sdk
    from sentry_sdk.integrations.fastapi import FastApiIntegration

    sentry_sdk.init(
        dsn=config.SENTRY_DSN_BACKEND,
        environment=config.SENTRY_ENVIRONMENT,
        traces_sample_rate=config.SENTRY_TRACES_SAMPLE_RATE,
        integrations=[FastApiIntegration()],
    )
config.ensure_media_dirs()


@FlexibleCsrfProtect.load_config
def get_csrf_config() -> list[tuple[str, Any]]:
    """Load CSRF configuration."""
    return [
        ("secret_key", config.CSRF_SECRET_KEY),
        ("cookie_samesite", config.COOKIE_SAMESITE),
        ("token_key", "csrf_token"),
    ]


# Routes that authenticate via a request-body credential (not a cookie, not yet a
# Bearer token - that's the whole point) rather than the normal Bearer-header
# exemption below. Exempt by exact path since there's no other signal available
# before the handler runs (SNA-13's password-login onboarding path:
# /api/v1/auth/token mints the very first token from an email+password body).
_CSRF_EXEMPT_PATHS = frozenset({"/api/v1/auth/token"})


async def csrf_validation_dependency(
    request: Request, csrf_protect: FlexibleCsrfProtect = Depends()
) -> None:
    """Global CSRF validation dependency.

    Bearer-authenticated requests (the non-browser JSON API) are exempt:
    CSRF protects *cookie*-authenticated state, and a cross-site page can't
    make a browser attach a custom `Authorization` header to a request, so
    there's nothing for a CSRF token to protect there.
    """
    if config.TESTING:
        return
    if request.headers.get("Authorization", "").lower().startswith("bearer "):
        return
    if request.url.path in _CSRF_EXEMPT_PATHS:
        return
    if request.method in {"POST", "PUT", "DELETE", "PATCH"}:
        try:
            await csrf_protect.validate_csrf(request)
        except CsrfProtectError as exc:
            access_logger.warning(
                "CSRF validation failed for %s %s: %s",
                request.method,
                request.url.path,
                exc.message,
            )
            raise


app = FastAPI(
    title="Stricknani",
    description="A self-hosted web app for managing knitting projects",
    version="0.1.0",
    lifespan=lifespan,
    dependencies=[Depends(csrf_validation_dependency)],
)

# Baseline security response headers (T58).
app.add_middleware(SecurityHeadersMiddleware)

# Reject requests with an unexpected Host header (T58). Guard against the
# wildcard / empty configuration so a misconfigured ALLOWED_HOSTS does not turn
# into a blanket 400 for every request. Skip under pytest, whose ASGI client
# uses a synthetic Host that would otherwise be rejected.
_allowed_hosts = [host.strip() for host in config.ALLOWED_HOSTS if host.strip()]
_under_pytest = "pytest" in sys.modules
if _allowed_hosts and "*" not in _allowed_hosts and not _under_pytest:
    app.add_middleware(TrustedHostMiddleware, allowed_hosts=_allowed_hosts)


@app.exception_handler(CsrfProtectError)
async def csrf_protect_exception_handler(
    request: Request, exc: CsrfProtectError
) -> HTMLResponse:
    """Handle CSRF errors."""
    return await render_template(
        "errors/403.html",
        request,
        context={
            "current_user": None,
            "error_title": "CSRF Error",
            "error_message": exc.message,
        },
        status_code=403,
    )


@app.exception_handler(404)
async def not_found_exception_handler(
    request: Request, exc: HTTPException
) -> HTMLResponse:
    """Handle 404 errors by rendering a custom template."""
    return await render_template(
        "errors/404.html",
        request,
        context={"current_user": None},
        status_code=404,
    )


@app.exception_handler(401)
async def unauthorized_exception_handler(
    request: Request, exc: HTTPException
) -> HTMLResponse:
    """Handle 401 errors by rendering a custom template."""
    return await render_template(
        "errors/401.html",
        request,
        context={"current_user": None},
        status_code=401,
    )


@app.exception_handler(403)
async def forbidden_exception_handler(
    request: Request, exc: HTTPException
) -> HTMLResponse:
    """Handle 403 errors by rendering a custom template."""
    return await render_template(
        "errors/403.html",
        request,
        context={"current_user": None},
        status_code=403,
    )


@app.exception_handler(429)
async def too_many_requests_exception_handler(
    request: Request, exc: HTTPException
) -> HTMLResponse:
    """Handle 429 (rate limit) errors by rendering a custom template (T69)."""
    response = await render_template(
        "errors/429.html",
        request,
        context={"current_user": None},
        status_code=429,
    )
    retry_after = getattr(exc, "headers", None) or {}
    if "Retry-After" in retry_after:
        response.headers["Retry-After"] = retry_after["Retry-After"]
    return response


@app.exception_handler(Exception)
async def catch_all_exception_handler(request: Request, exc: Exception) -> HTMLResponse:
    """Handle all other unhandled exceptions by rendering a 500 template."""
    if isinstance(exc, HTTPException):
        if exc.status_code == 404:
            return await not_found_exception_handler(request, exc)
        if exc.status_code == 401:
            return await unauthorized_exception_handler(request, exc)
        if exc.status_code == 403:
            return await forbidden_exception_handler(request, exc)
        if exc.status_code == 429:
            return await too_many_requests_exception_handler(request, exc)

    # Log the exception for debugging
    access_logger.exception("Unhandled exception: %s", str(exc))
    return await render_template(
        "errors/500.html",
        request,
        context={"current_user": None},
        status_code=500,
    )


static_path = Path(__file__).parent / "static"
static_path.mkdir(exist_ok=True)
app.mount("/static", CachedStaticFiles(directory=str(static_path)), name="static")

# Media files are served through an ownership-checked route (T70), not a raw
# static mount: stricknani.routes.media resolves the owning project/yarn/user
# for each requested path and only streams the file once the current user is
# confirmed to own it. See that module's docstring for details.

access_logger = logging.getLogger("stricknani.access")

# Changes on every process start, so it doubles as a cheap "did we just
# deploy" signal for both the dev auto-reload banner and the service worker
# cache version below.
dev_reload_token = str(time.time_ns())

# Cache names derived from this change on every deploy (process restart),
# so the service worker's `activate` handler cleans up the previous
# version's caches instead of serving stale HTML/CSS/JS indefinitely.
SW_BUILD_ID = f"{__version__}-{dev_reload_token}"


@app.get("/manifest.webmanifest")
async def pwa_manifest() -> FileResponse:
    return FileResponse(
        static_path / "manifest.webmanifest",
        media_type="application/manifest+json",
    )


@app.get("/sw.js")
async def service_worker() -> Response:
    """Serve the service worker with the current build's cache version baked in."""
    content = (static_path / "js" / "sw.js").read_text(encoding="utf-8")
    content = content.replace("__STRICKNANI_BUILD_VERSION__", SW_BUILD_ID)
    return Response(
        content=content,
        media_type="application/javascript",
        # Service workers should always be revalidated so updates (and their
        # new cache version) are picked up promptly after a deploy.
        headers={"Cache-Control": "no-cache"},
    )


@app.middleware("http")
async def log_requests(
    request: Request, call_next: Callable[[Request], Awaitable[Response]]
) -> Response:
    """Log incoming requests similar to the access log."""

    response = await call_next(request)
    client_host = "-"
    if request.client is not None:
        client_host = request.client.host or "-"

    access_logger.info(
        '%s - "%s %s" %s',
        client_host,
        request.method,
        request.url.path,
        response.status_code,
    )
    return response


# Markdown preview length cap (T106): bounds the cost of nh3 sanitization +
# python-markdown parsing per request. Comfortably above any legitimate
# project/yarn description or notes field a user would type.
MAX_MARKDOWN_PREVIEW_CHARS = 50_000


@app.post("/utils/preview/markdown", response_class=HTMLResponse)
async def preview_markdown(
    request: Request,
    content: Annotated[str, Form()] = "",
    _current_user: User = Depends(require_auth),
) -> HTMLResponse:
    """Render markdown content for preview.

    Requires authentication (T106): this endpoint is only ever called from
    the wysiwyg editor on authenticated project/yarn forms, but had no auth
    check of its own, letting anonymous callers hit python-markdown/nh3
    sanitization (real CPU/memory cost) for free, repeatedly.
    """
    if not content:
        return HTMLResponse("")
    if len(content) > MAX_MARKDOWN_PREVIEW_CHARS:
        raise HTTPException(
            status_code=413, detail="Markdown content too large to preview"
        )
    return HTMLResponse(render_markdown(content))


@app.get("/healthz")
async def healthz() -> dict[str, str]:
    """Health check endpoint."""
    return {"status": "ok"}


@app.get("/__dev__/reload-token")
async def get_dev_reload_token() -> dict[str, str]:
    """Return an instance token so dev clients can detect server restarts."""
    return {"token": dev_reload_token}


# Import routes
from stricknani.routes import (  # noqa: E402
    admin,
    auth,
    gauge,
    legal,
    media,
    projects,
    search,
    user,
    utils,
    yarn,
)
from stricknani.routes import api as json_api  # noqa: E402

app.include_router(auth.router)
app.include_router(projects.router)
app.include_router(search.router)
app.include_router(gauge.router)
app.include_router(legal.router)
app.include_router(user.router)
app.include_router(yarn.router)
app.include_router(admin.router)
app.include_router(utils.router)
app.include_router(json_api.router)
# Registered last: media's catch-all "/media/{path:path}" deny route must not
# shadow any more specific route declared above (none currently overlap, but
# this keeps the ordering intentional).
app.include_router(media.router)


# Offline fallback page (precached by the service worker; see
# stricknani/static/js/sw.js). Served when a navigation fails while offline
# and no cached copy of the requested page exists.
@app.get("/offline", response_class=HTMLResponse)
async def offline_page(request: Request) -> HTMLResponse:
    """Show the offline fallback page."""
    return await render_template(
        "offline.html",
        request,
        {"current_user": None},
    )


# Login page
@app.get("/login", response_class=HTMLResponse)
async def login_page(request: Request) -> HTMLResponse:
    """Show login page."""
    return await render_template(
        "auth/login.html",
        request,
        {
            "current_user": None,
            "signup_enabled": config.FEATURE_SIGNUP_ENABLED,
        },
    )


# Root redirect
@app.get("/", response_class=HTMLResponse)
async def root(request: Request) -> RedirectResponse:
    """Root endpoint - redirect to projects."""
    return RedirectResponse(url="/projects", status_code=303)
