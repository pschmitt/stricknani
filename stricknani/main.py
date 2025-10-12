"""Main FastAPI application."""

from collections.abc import AsyncGenerator
from contextlib import asynccontextmanager
from pathlib import Path

from fastapi import FastAPI, Request
from fastapi.responses import HTMLResponse, RedirectResponse
from fastapi.staticfiles import StaticFiles
from fastapi.templating import Jinja2Templates

from stricknani.config import config
from stricknani.database import init_db


@asynccontextmanager
async def lifespan(app: FastAPI) -> AsyncGenerator[None]:
    """Application lifespan manager."""
    # Startup
    await init_db()
    config.ensure_media_dirs()
    yield
    # Shutdown
    pass


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


# Health check endpoint
@app.get("/healthz")
async def healthz() -> dict[str, str]:
    """Health check endpoint."""
    return {"status": "ok"}


# Import routes
from stricknani.routes import auth, gauge, projects  # noqa: E402

app.include_router(auth.router)
app.include_router(projects.router)
app.include_router(gauge.router)


# Login page
@app.get("/login", response_class=HTMLResponse)
async def login_page(request: Request) -> HTMLResponse:
    """Show login page."""
    return templates.TemplateResponse(
        "auth/login.html", {"request": request, "current_user": None}
    )


# Root redirect
@app.get("/", response_class=HTMLResponse)
async def root(request: Request) -> RedirectResponse:
    """Root endpoint - redirect to projects."""
    return RedirectResponse(url="/projects", status_code=303)
