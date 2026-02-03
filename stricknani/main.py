"""Main FastAPI application."""

import logging
from collections.abc import AsyncGenerator, Awaitable, Callable
from contextlib import asynccontextmanager
from pathlib import Path
from typing import Annotated, Any

from fastapi import Depends, FastAPI, Form, HTTPException, Request, Response
from fastapi.responses import HTMLResponse, RedirectResponse
from fastapi.staticfiles import StaticFiles
from fastapi.templating import Jinja2Templates
from fastapi_csrf_protect.exceptions import CsrfProtectError
from fastapi_csrf_protect.flexible import CsrfProtect as FlexibleCsrfProtect

from stricknani.config import config
from stricknani.database import init_db
from stricknani.logging_config import configure_logging
from stricknani.utils.auth import ensure_initial_admin
from stricknani.utils.i18n import install_i18n
from stricknani.utils.markdown import render_markdown


@asynccontextmanager
async def lifespan(app: FastAPI) -> AsyncGenerator[None]:
    """Application lifespan manager."""
    # Startup
    await init_db()
    await ensure_initial_admin()
    yield
    # Shutdown
    pass


configure_logging(debug=config.DEBUG)
if config.SENTRY_DSN:
    import sentry_sdk
    from sentry_sdk.integrations.fastapi import FastApiIntegration

    sentry_sdk.init(
        dsn=config.SENTRY_DSN,
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
        ("token_location", "header"),
        ("token_key", "csrf_token"),
    ]


async def csrf_validation_dependency(
    request: Request, csrf_protect: FlexibleCsrfProtect = Depends()
) -> None:
    """Global CSRF validation dependency."""
    if config.TESTING:
        return
    if request.method in {"POST", "PUT", "DELETE", "PATCH"}:
        # Try to get token from form first if it's a form submission
        token = None
        if request.headers.get("content-type") == "application/x-www-form-urlencoded":
            try:
                form_data = await request.form()
                token = form_data.get("csrf_token")
            except Exception:
                pass

        # Fallback to header if not in form
        if not token:
            token = request.headers.get("X-CSRF-Token")

        # Log for debugging if needed (only in debug mode)
        if config.DEBUG:
            access_logger.debug(
                "CSRF validation for %s %s. Token found: %s",
                request.method,
                request.url.path,
                "yes" if token else "no",
            )

        try:
            await csrf_protect.validate_csrf(request)
        except Exception as e:
            access_logger.error("CSRF Validation failed: %s", str(e))
            raise e


app = FastAPI(
    title="Stricknani",
    description="A self-hosted web app for managing knitting projects",
    version="0.1.0",
    lifespan=lifespan,
    dependencies=[Depends(csrf_validation_dependency)],
)


@app.exception_handler(CsrfProtectError)
async def csrf_protect_exception_handler(
    request: Request, exc: CsrfProtectError
) -> HTMLResponse:
    """Handle CSRF errors."""
    return await render_template(
        "errors/403.html",
        request,
        context={
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
    return await render_template("errors/404.html", request, status_code=404)


@app.exception_handler(401)
async def unauthorized_exception_handler(
    request: Request, exc: HTTPException
) -> HTMLResponse:
    """Handle 401 errors by rendering a custom template."""
    return await render_template("errors/401.html", request, status_code=401)


@app.exception_handler(403)
async def forbidden_exception_handler(
    request: Request, exc: HTTPException
) -> HTMLResponse:
    """Handle 403 errors by rendering a custom template."""
    return await render_template("errors/403.html", request, status_code=403)


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

    # Log the exception for debugging
    access_logger.exception("Unhandled exception: %s", str(exc))
    return await render_template("errors/500.html", request, status_code=500)


static_path = Path(__file__).parent / "static"
static_path.mkdir(exist_ok=True)
app.mount("/static", StaticFiles(directory=str(static_path)), name="static")

# Mount media files
app.mount("/media", StaticFiles(directory=str(config.MEDIA_ROOT)), name="media")

# Setup templates
templates_path = Path(__file__).parent / "templates"
templates = Jinja2Templates(directory=str(templates_path))


def category_color_filter(category_name: str | None) -> str:
    """Map category names to colorful Tailwind classes using deterministic hashing."""
    if not category_name or category_name == "None":
        return "badge-ghost"

    # Use a deterministic hash of the category name to pick a color
    hash_val = sum(ord(c) for c in category_name)
    palette = [
        ("blue", "blue"),
        ("indigo", "indigo"),
        ("teal", "teal"),
        ("rose", "rose"),
        ("amber", "amber"),
        ("emerald", "emerald"),
        ("orange", "orange"),
        ("purple", "purple"),
        ("pink", "pink"),
        ("cyan", "cyan"),
        ("violet", "violet"),
    ]

    base, dark = palette[hash_val % len(palette)]
    return (
        f"bg-{base}-200 text-{base}-950 border-{base}-300 "
        f"dark:bg-{dark}-950/60 dark:text-{dark}-200 dark:border-{dark}-700"
    )


templates.env.filters["category_color"] = category_color_filter


access_logger = logging.getLogger("stricknani.access")


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


def get_language(request: Request) -> str:
    """Get language from request.

    Args:
        request: FastAPI request object

    Returns:
        Language code
    """
    # Check for language cookie first
    lang_cookie = request.cookies.get("language")
    if lang_cookie and lang_cookie in config.SUPPORTED_LANGUAGES:
        return lang_cookie

    # Check Accept-Language header
    accept_language = request.headers.get("accept-language", "")
    if accept_language:
        candidates: list[tuple[str, float]] = []
        for part in accept_language.split(","):
            raw = part.strip()
            if not raw:
                continue
            pieces = [segment.strip() for segment in raw.split(";")]
            lang_code = pieces[0].lower()
            if "-" in lang_code:
                lang_code = lang_code.split("-", 1)[0]
            quality = 1.0
            for segment in pieces[1:]:
                if segment.startswith("q="):
                    try:
                        quality = float(segment[2:])
                    except ValueError:
                        quality = 0.0
            candidates.append((lang_code, quality))
        candidates.sort(key=lambda item: item[1], reverse=True)
        for lang_code, _ in candidates:
            if lang_code in config.SUPPORTED_LANGUAGES:
                return lang_code

    # Default language
    if "en" in config.SUPPORTED_LANGUAGES:
        return "en"
    return config.DEFAULT_LANGUAGE


async def render_template(
    template_name: str,
    request: Request,
    context: dict[str, Any] | None = None,
    status_code: int = 200,
) -> HTMLResponse:
    """Render a template with i18n support.

    Args:
        template_name: Name of the template file
        request: FastAPI request object
        context: Additional context variables
        status_code: HTTP status code for the response

    Returns:
        HTMLResponse with rendered template
    """
    if context is None:
        context = {}

    # Get language and install translations
    language = get_language(request)
    install_i18n(templates.env, language)

    # Add request and language to context
    context["request"] = request
    context["current_language"] = language
    context.setdefault(
        "is_dev_instance",
        request.url.hostname in {"localhost", "127.0.0.1"} or config.DEBUG,
    )
    context.setdefault("feature_wayback_enabled", config.FEATURE_WAYBACK_ENABLED)

    # CSRF protection
    csrf = FlexibleCsrfProtect()
    csrf_token, signed_token = csrf.generate_csrf_tokens()
    context["csrf_token"] = csrf_token

    # Automatically fetch current user if not provided
    if "current_user" not in context:
        from stricknani.database import get_db
        from stricknani.routes.auth import get_current_user

        session_token = request.cookies.get("session_token")
        async for db in get_db():
            context["current_user"] = await get_current_user(session_token, db)
            break

    current_user = context.get("current_user")
    avatar_url = None
    avatar_thumb = None
    if current_user is not None:
        avatar_url = getattr(current_user, "avatar_url", None)
        avatar_thumb = getattr(current_user, "avatar_thumbnail_url", None)

    context.setdefault("current_user_avatar_url", avatar_url)
    context.setdefault("current_user_avatar_thumbnail", avatar_thumb)

    response = templates.TemplateResponse(
        request=request,
        name=template_name,
        context=context,
        status_code=status_code,
    )

    # Set CSRF cookie
    csrf.set_csrf_cookie(signed_token, response)

    return response


# Health check endpoint
@app.post("/utils/preview/markdown", response_class=HTMLResponse)
async def preview_markdown(
    request: Request,
    content: Annotated[str, Form()] = "",
) -> HTMLResponse:
    """Render markdown content for preview."""
    if not content:
        return HTMLResponse("")
    return HTMLResponse(render_markdown(content))


@app.get("/healthz")
async def healthz() -> dict[str, str]:
    """Health check endpoint."""
    return {"status": "ok"}


# Import routes
from stricknani.routes import (  # noqa: E402
    admin,
    auth,
    gauge,
    projects,
    search,
    user,
    yarn,
)

app.include_router(auth.router)
app.include_router(projects.router)
app.include_router(search.router)
app.include_router(gauge.router)
app.include_router(user.router)
app.include_router(yarn.router)
app.include_router(admin.router)


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
