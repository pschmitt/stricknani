"""Authentication routes."""

from typing import Annotated
from urllib.parse import urlparse

from fastapi import (
    APIRouter,
    Cookie,
    Depends,
    Form,
    Header,
    HTTPException,
    Request,
    Response,
    status,
)
from fastapi.responses import RedirectResponse
from sqlalchemy.ext.asyncio import AsyncSession

from stricknani.config import config
from stricknani.database import get_db
from stricknani.models import User
from stricknani.utils.auth import (
    PasswordPolicyError,
    authenticate_user,
    create_access_token,
    create_user,
    decode_access_token,
    get_user_by_email,
    get_user_from_api_token,
    validate_password_policy,
)
from stricknani.utils.i18n import gettext
from stricknani.utils.rate_limit import is_rate_limited, record_attempt
from stricknani.web.templating import get_language, render_template

router: APIRouter = APIRouter(prefix="/auth", tags=["auth"])


def _client_ip(request: Request) -> str:
    """Best-effort client IP for rate-limit keying."""
    return request.client.host if request.client else "unknown"


def _password_policy_message(exc: PasswordPolicyError, language: str) -> str:
    """Map a `PasswordPolicyError` to a localized, user-facing message."""
    if exc.reason == "too_short":
        return gettext("Password must be at least 8 characters long.", language)
    return gettext(
        "This password is too common. Please choose a stronger one.", language
    )


async def get_current_user(
    session_token: Annotated[str | None, Cookie()] = None,
    db: AsyncSession = Depends(get_db),
) -> User | None:
    """Get current user from session token."""
    if not session_token:
        return None

    decoded = decode_access_token(session_token)
    if not decoded:
        return None
    email, token_version = decoded

    user = await get_user_by_email(db, email)
    if not user or not user.is_active:
        return None

    # Revocable sessions (T69): a version mismatch means the token was
    # issued before a logout or password change and must be rejected even
    # though its signature is still valid.
    if user.token_version != token_version:
        return None

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


async def require_admin(
    current_user: User = Depends(require_auth),
) -> User:
    """Require admin access."""
    if not current_user.is_admin:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Admin access required",
        )
    return current_user


async def get_current_user_from_api_token(
    authorization: Annotated[str | None, Header()] = None,
    db: AsyncSession = Depends(get_db),
) -> User | None:
    """Get the current user from a `Authorization: Bearer <token>` header.

    Independent of the cookie-session `get_current_user` above - used by the
    non-browser JSON API (`stricknani/routes/api/`) rather than the
    HTML/HTMX routes.
    """
    if not authorization:
        return None
    scheme, _, raw_token = authorization.partition(" ")
    if scheme.lower() != "bearer" or not raw_token:
        return None
    return await get_user_from_api_token(db, raw_token)


async def require_api_token(
    current_user: User | None = Depends(get_current_user_from_api_token),
) -> User:
    """Require a valid API token, for the non-browser JSON API."""
    if not current_user:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Invalid or missing API token",
        )
    return current_user


@router.post("/signup")
async def signup(
    request: Request,
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

    # Rate limit signup attempts per IP (T69) to slow down automated mass
    # account creation. Counted regardless of outcome.
    signup_key = f"signup:ip:{_client_ip(request)}"
    if is_rate_limited(
        signup_key,
        config.RATE_LIMIT_SIGNUP_MAX_ATTEMPTS,
        config.RATE_LIMIT_SIGNUP_WINDOW_SECONDS,
    ):
        raise HTTPException(
            status_code=status.HTTP_429_TOO_MANY_REQUESTS,
            detail="Too many signup attempts. Please try again later.",
            headers={"Retry-After": str(config.RATE_LIMIT_SIGNUP_WINDOW_SECONDS)},
        )
    record_attempt(signup_key)

    # Check if user already exists
    existing_user = await get_user_by_email(db, email)
    if existing_user:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Email already registered",
        )

    try:
        validate_password_policy(password)
    except PasswordPolicyError as exc:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=_password_policy_message(exc, get_language(request)),
        ) from exc

    # Create user
    user = await create_user(db, email, password)

    # Create access token
    access_token = create_access_token(
        data={"sub": user.email, "ver": str(user.token_version)}
    )

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
    request: Request,
    email: Annotated[str, Form()],
    password: Annotated[str, Form()],
    db: AsyncSession = Depends(get_db),
) -> Response:
    """User login."""
    # Rate limit login attempts per IP and per account (T69) to slow down
    # brute-force/credential-stuffing attempts. Only failed attempts count
    # against the limit, so legitimate repeated logins (e.g. multiple tabs)
    # aren't penalized.
    ip_key = f"login:ip:{_client_ip(request)}"
    email_key = f"login:email:{email.strip().lower()}"
    if is_rate_limited(
        ip_key,
        config.RATE_LIMIT_LOGIN_MAX_ATTEMPTS,
        config.RATE_LIMIT_LOGIN_WINDOW_SECONDS,
    ) or is_rate_limited(
        email_key,
        config.RATE_LIMIT_LOGIN_MAX_ATTEMPTS,
        config.RATE_LIMIT_LOGIN_WINDOW_SECONDS,
    ):
        raise HTTPException(
            status_code=status.HTTP_429_TOO_MANY_REQUESTS,
            detail="Too many login attempts. Please try again later.",
            headers={"Retry-After": str(config.RATE_LIMIT_LOGIN_WINDOW_SECONDS)},
        )

    user = await authenticate_user(db, email, password)
    if not user:
        record_attempt(ip_key)
        record_attempt(email_key)
        return await render_template(
            "auth/login.html",
            request,
            {
                "current_user": None,
                "signup_enabled": config.FEATURE_SIGNUP_ENABLED,
                "login_error": True,
                "login_email": email,
            },
            status_code=status.HTTP_401_UNAUTHORIZED,
        )

    # Create access token
    access_token = create_access_token(
        data={"sub": user.email, "ver": str(user.token_version)}
    )

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
async def logout(
    current_user: Annotated[User | None, Depends(get_current_user)],
    db: AsyncSession = Depends(get_db),
) -> Response:
    """User logout.

    Bumps `token_version` (T69) so the outstanding JWT is rejected by
    `get_current_user` from this point on, rather than merely clearing the
    cookie while the token itself stays valid until it expires.
    """
    if current_user:
        current_user.token_version += 1
        await db.commit()

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
