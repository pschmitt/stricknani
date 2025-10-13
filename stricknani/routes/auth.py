"""Authentication routes."""

from typing import Annotated

from urllib.parse import urlparse

from fastapi import (
    APIRouter,
    Cookie,
    Depends,
    Form,
    HTTPException,
    Request,
    Response,
    status,
)
from fastapi.responses import JSONResponse, RedirectResponse
from sqlalchemy.ext.asyncio import AsyncSession

from stricknani.config import config
from stricknani.database import get_db
from stricknani.models import User
from stricknani.utils.auth import (
    authenticate_user,
    create_access_token,
    create_user,
    decode_access_token,
    get_user_by_email,
)

router = APIRouter(prefix="/auth", tags=["auth"])


async def get_current_user(
    session_token: Annotated[str | None, Cookie()] = None,
    db: AsyncSession = Depends(get_db),
) -> User | None:
    """Get current user from session token."""
    if not session_token:
        return None

    email = decode_access_token(session_token)
    if not email:
        return None

    user = await get_user_by_email(db, email)
    return user


async def require_auth(
    current_user: User | None = Depends(get_current_user),
) -> User:
    """Require authentication."""
    if not current_user:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Not authenticated",
        )
    return current_user


@router.post("/signup")
async def signup(
    email: Annotated[str, Form()],
    password: Annotated[str, Form()],
    db: AsyncSession = Depends(get_db),
) -> Response:
    """User signup."""
    if not config.FEATURE_SIGNUP_ENABLED:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Signup is disabled",
        )

    # Check if user already exists
    existing_user = await get_user_by_email(db, email)
    if existing_user:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Email already registered",
        )

    # Create user
    user = await create_user(db, email, password)

    # Create access token
    access_token = create_access_token(data={"sub": user.email})

    # Set cookie and redirect
    response = RedirectResponse(url="/projects", status_code=status.HTTP_303_SEE_OTHER)
    response.set_cookie(
        key="session_token",
        value=access_token,
        httponly=True,
        secure=config.SESSION_COOKIE_SECURE,
        samesite=config.COOKIE_SAMESITE,
    )
    return response


@router.post("/login")
async def login(
    email: Annotated[str, Form()],
    password: Annotated[str, Form()],
    db: AsyncSession = Depends(get_db),
) -> Response:
    """User login."""
    user = await authenticate_user(db, email, password)
    if not user:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Incorrect email or password",
        )

    # Create access token
    access_token = create_access_token(data={"sub": user.email})

    # Set cookie and redirect
    response = RedirectResponse(url="/projects", status_code=status.HTTP_303_SEE_OTHER)
    response.set_cookie(
        key="session_token",
        value=access_token,
        httponly=True,
        secure=config.SESSION_COOKIE_SECURE,
        samesite=config.COOKIE_SAMESITE,
    )
    return response


@router.post("/logout")
async def logout() -> Response:
    """User logout."""
    response = RedirectResponse(url="/", status_code=status.HTTP_303_SEE_OTHER)
    response.delete_cookie(
        key="session_token",
        secure=config.SESSION_COOKIE_SECURE,
        httponly=True,
        samesite=config.COOKIE_SAMESITE,
    )
    return response


@router.get("/me")
async def me(current_user: User = Depends(require_auth)) -> dict[str, str]:
    """Get current user info."""
    return {"email": current_user.email, "id": str(current_user.id)}


@router.post("/set-language")
async def set_language(
    request: Request,
    language: Annotated[str, Form()],
    next_url: Annotated[str | None, Form()] = None,
) -> Response:
    """Set the user's language preference."""
    if language not in config.SUPPORTED_LANGUAGES:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Invalid language",
        )

    # Determine redirect target preference: explicit next, referer, projects list
    redirect_target = (
        _resolve_safe_redirect(request, next_url)
        or _resolve_safe_redirect(request, request.headers.get("referer"))
        or "/projects"
    )

    response = RedirectResponse(
        url=redirect_target,
        status_code=status.HTTP_303_SEE_OTHER,
    )
    response.set_cookie(
        key="language",
        value=language,
        httponly=False,
        secure=config.LANGUAGE_COOKIE_SECURE,
        samesite=config.COOKIE_SAMESITE,
        max_age=31536000,  # 1 year
    )
    return response


@router.post("/set-theme")
async def set_theme(request: Request) -> Response:
    """Persist the user's theme preference via cookie."""

    content_type = request.headers.get("content-type", "")

    theme: str | None = None
    next_url: str | None = None
    payload: dict[str, str] | None = None

    if "application/json" in content_type:
        try:
            payload = await request.json()
        except ValueError:
            payload = {}
        theme = payload.get("theme") if isinstance(payload, dict) else None
        next_url = payload.get("next") if isinstance(payload, dict) else None
    else:
        form = await request.form()
        theme = form.get("theme")
        next_url = form.get("next")

    if theme not in {"light", "dark", "system"}:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Invalid theme",
        )

    redirect_target = (
        _resolve_safe_redirect(request, next_url)
        or _resolve_safe_redirect(request, request.headers.get("referer"))
        or "/projects"
    )

    if "application/json" in content_type or request.headers.get("HX-Request"):
        response: Response = JSONResponse({"status": "ok", "theme": theme})
    else:
        response = RedirectResponse(url=redirect_target, status_code=status.HTTP_303_SEE_OTHER)

    if theme == "system":
        response.delete_cookie(
            key="theme",
            secure=config.THEME_COOKIE_SECURE,
            samesite=config.COOKIE_SAMESITE,
        )
        return response

    response.set_cookie(
        key="theme",
        value=theme,
        httponly=False,
        secure=config.THEME_COOKIE_SECURE,
        samesite=config.COOKIE_SAMESITE,
        max_age=31536000,
    )

    return response


def _resolve_safe_redirect(request: Request, target: str | None) -> str | None:
    """Resolve a safe redirect target limited to the current host."""

    if not target:
        return None

    parsed = urlparse(target)

    if not parsed.scheme and not parsed.netloc and not parsed.path:
        return None

    # Reject absolute URLs that point to a different host
    if parsed.netloc and parsed.netloc != request.url.netloc:
        return None

    path = parsed.path or "/"

    if not path.startswith("/"):
        return None

    if parsed.query:
        path = f"{path}?{parsed.query}"

    if parsed.fragment:
        path = f"{path}#{parsed.fragment}"

    return path
