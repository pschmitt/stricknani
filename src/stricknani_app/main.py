from __future__ import annotations

from pathlib import Path

from fastapi import Depends, FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import HTMLResponse, RedirectResponse
from fastapi.staticfiles import StaticFiles
from fastapi.templating import Jinja2Templates

from .database import init_database
from .dependencies import authenticate_request, get_current_user, get_db
from .routers import auth, dashboard

init_database()

app = FastAPI(title="Project Studio")
static_path = Path(__file__).parent / "static"
app.mount("/static", StaticFiles(directory=static_path), name="static")
templates = Jinja2Templates(directory=str(Path(__file__).parent / "templates"))

app.include_router(auth.router)
app.include_router(dashboard.router)


@app.exception_handler(RequestValidationError)
async def validation_exception_handler(request: Request, exc: RequestValidationError) -> HTMLResponse:
    return templates.TemplateResponse(request, "error.html", {"errors": exc.errors()}, status_code=422)


@app.get("/", response_class=HTMLResponse)
async def index(
    request: Request,
    user=Depends(get_current_user),
    db=Depends(get_db),
) -> HTMLResponse:
    context = dashboard.get_homepage_context(db=db, user=user)
    return templates.TemplateResponse(request, "home.html", context)


@app.get("/projects/{project_id}", response_class=HTMLResponse)
async def project_detail(
    project_id: int,
    request: Request,
    user=Depends(get_current_user),
    db=Depends(get_db),
) -> HTMLResponse:
    context = dashboard.get_project_context(db=db, user=user, project_id=project_id)
    return templates.TemplateResponse(request, "project_detail.html", context)


@app.get("/auth/login", response_class=HTMLResponse)
async def login_page(request: Request) -> HTMLResponse:
    return templates.TemplateResponse(request, "login.html")


@app.get("/auth/register", response_class=HTMLResponse)
async def register_page(request: Request) -> HTMLResponse:
    return templates.TemplateResponse(request, "register.html")


@app.get("/auth/logout")
async def logout_redirect() -> RedirectResponse:
    return await auth.logout()


@app.middleware("http")
async def ensure_user(request: Request, call_next):
    if request.url.path.startswith("/auth") or request.url.path.startswith("/static"):
        return await call_next(request)

    user = authenticate_request(request)
    if user is None:
        return RedirectResponse(url="/auth/login")

    request.state.user = user
    response = await call_next(request)
    return response
