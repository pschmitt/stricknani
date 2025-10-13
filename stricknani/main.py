"""Main FastAPI application."""

import logging
from collections.abc import AsyncGenerator
from contextlib import asynccontextmanager
from pathlib import Path

from fastapi import FastAPI, Request
from fastapi.responses import HTMLResponse, RedirectResponse
from fastapi.staticfiles import StaticFiles
from fastapi.templating import Jinja2Templates

from stricknani.config import config
from stricknani.database import init_db
from stricknani.logging_config import configure_logging
from stricknani.utils.files import get_file_url, get_thumbnail_url
from stricknani.utils.i18n import install_i18n


@asynccontextmanager
async def lifespan(app: FastAPI) -> AsyncGenerator[None]:
    """Application lifespan manager."""
    # Startup
    await init_db()
    yield
    # Shutdown
    pass


configure_logging(debug=config.DEBUG)
config.ensure_media_dirs()


app = FastAPI(
    title="Stricknani",
    description="A self-hosted web app for managing knitting projects",
    version="0.1.0",
    lifespan=lifespan,
)

# Mount static files
static_path = Path(__file__).parent / "static"
static_path.mkdir(exist_ok=True)
app.mount("/static", StaticFiles(directory=str(static_path)), name="static")

# Mount media files
app.mount("/media", StaticFiles(directory=str(config.MEDIA_ROOT)), name="media")

# Setup templates
templates_path = Path(__file__).parent / "templates"
templates = Jinja2Templates(directory=str(templates_path))


access_logger = logging.getLogger("stricknani.access")


@app.middleware("http")
async def log_requests(request: Request, call_next):
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
    for lang in config.SUPPORTED_LANGUAGES:
        if lang in accept_language.lower():
            return lang

    # Default language
    return config.DEFAULT_LANGUAGE


def render_template(
    template_name: str, request: Request, context: dict | None = None
) -> HTMLResponse:
    """Render a template with i18n support.

    Args:
        template_name: Name of the template file
        request: FastAPI request object
        context: Additional context variables

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

    theme_preference = request.cookies.get("theme", "system")
    context.setdefault("current_theme", theme_preference)
    context.setdefault("theme_preference", theme_preference)

    current_user = context.get("current_user")
    avatar_url = None
    avatar_thumb = None
    if current_user is not None:
        profile_image = getattr(current_user, "profile_image", None)
        user_id = getattr(current_user, "id", None)
        if profile_image and user_id:
            avatar_url = get_file_url(profile_image, user_id, subdir="users")
            avatar_thumb = get_thumbnail_url(profile_image, user_id, subdir="users")

    context.setdefault("current_user_avatar_url", avatar_url)
    context.setdefault("current_user_avatar_thumbnail", avatar_thumb)

    return templates.TemplateResponse(template_name, context)


# Health check endpoint
@app.get("/healthz")
async def healthz() -> dict[str, str]:
    """Health check endpoint."""
    return {"status": "ok"}


# Import routes
from stricknani.routes import auth, gauge, projects, user  # noqa: E402

app.include_router(auth.router)
app.include_router(projects.router)
app.include_router(gauge.router)
app.include_router(user.router)


# Login page
@app.get("/login", response_class=HTMLResponse)
async def login_page(request: Request) -> HTMLResponse:
    """Show login page."""
    return render_template(
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
