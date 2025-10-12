"""Authentication routes."""

from typing import Annotated

from fastapi import APIRouter, Cookie, Depends, Form, HTTPException, Request, Response, status
from fastapi.responses import HTMLResponse, RedirectResponse
from sqlalchemy.ext.asyncio import AsyncSession

from stricknani.config import config
from stricknani.database import get_db
from stricknani.main import templates
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
        secure=not config.DEBUG,
        samesite="lax",
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
        secure=not config.DEBUG,
        samesite="lax",
    )
    return response


@router.post("/logout")
async def logout() -> Response:
    """User logout."""
    response = RedirectResponse(url="/", status_code=status.HTTP_303_SEE_OTHER)
    response.delete_cookie(key="session_token")
    return response


@router.get("/me")
async def me(current_user: User = Depends(require_auth)) -> dict[str, str]:
    """Get current user info."""
    return {"email": current_user.email, "id": str(current_user.id)}
