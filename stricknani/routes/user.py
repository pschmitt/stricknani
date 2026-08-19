"""User preference routes."""

import logging
from typing import Annotated

from fastapi import (
    APIRouter,
    Depends,
    File,
    Form,
    HTTPException,
    Request,
    Response,
    UploadFile,
    status,
)
from fastapi.responses import HTMLResponse, RedirectResponse
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from stricknani.config import config
from stricknani.database import get_db
from stricknani.models import ApiToken, User
from stricknani.routes.auth import require_auth
from stricknani.utils.auth import generate_api_token
from stricknani.utils.files import (
    UploadTooLargeError,
    create_thumbnail,
    delete_file,
    save_uploaded_image,
)
from stricknani.utils.qr_setup import (
    build_setup_uri,
    render_qr_data_uri,
    request_base_url,
)
from stricknani.web.middleware import _is_secure_request
from stricknani.web.templating import render_template

logger = logging.getLogger(__name__)

router: APIRouter = APIRouter(prefix="/user", tags=["user"])


async def _user_api_tokens(db: AsyncSession, user_id: int) -> list[ApiToken]:
    result = await db.execute(
        select(ApiToken)
        .where(ApiToken.user_id == user_id)
        .order_by(ApiToken.created_at.desc())
    )
    return list(result.scalars().all())


@router.get("/api-tokens", response_class=HTMLResponse)
async def api_tokens_page(
    request: Request,
    db: AsyncSession = Depends(get_db),
    current_user: User = Depends(require_auth),
) -> HTMLResponse:
    """Show the personal access token management page."""
    tokens = await _user_api_tokens(db, current_user.id)
    return await render_template(
        "user/api_tokens.html",
        request,
        {
            "current_user": current_user,
            "tokens": tokens,
            "new_token": None,
            "qr_setup": None,
        },
    )


@router.post("/api-tokens", response_class=HTMLResponse)
async def create_api_token(
    request: Request,
    name: Annotated[str, Form()],
    db: AsyncSession = Depends(get_db),
    current_user: User = Depends(require_auth),
) -> HTMLResponse:
    """Create a new personal access token.

    The raw token is only ever available in this response - it's shown once
    and only its hash is persisted, so it cannot be recovered afterwards.
    """
    display_name = name.strip() or "Stricknani Android"
    raw_token, token_hash = generate_api_token()
    db.add(ApiToken(user_id=current_user.id, name=display_name, token_hash=token_hash))
    await db.commit()

    tokens = await _user_api_tokens(db, current_user.id)
    return await render_template(
        "user/api_tokens.html",
        request,
        {
            "current_user": current_user,
            "tokens": tokens,
            "new_token": raw_token,
            "qr_setup": None,
        },
    )


@router.post("/api-tokens/qr-setup", response_class=HTMLResponse)
async def create_qr_setup_token(
    request: Request,
    db: AsyncSession = Depends(get_db),
    current_user: User = Depends(require_auth),
) -> HTMLResponse:
    """Mint a token and render it as a QR code the Android app can scan (SNA-13).

    Same one-shown-once token as `create_api_token` above - just also
    packaged as a `stricknani://setup` QR so onboarding a new device doesn't
    require manually copying a server URL and pasting a token.
    """
    raw_token, token_hash = generate_api_token()
    db.add(ApiToken(user_id=current_user.id, name="QR setup", token_hash=token_hash))
    await db.commit()

    scheme = "https" if _is_secure_request(request) else "http"
    host = request.headers.get("host") or request.url.netloc
    base_url = request_base_url(scheme, host)
    setup_uri = build_setup_uri(base_url, raw_token)

    tokens = await _user_api_tokens(db, current_user.id)
    return await render_template(
        "user/api_tokens.html",
        request,
        {
            "current_user": current_user,
            "tokens": tokens,
            "new_token": None,
            "qr_setup": {
                "uri": setup_uri,
                "qr_data_uri": render_qr_data_uri(setup_uri),
            },
        },
    )


@router.post("/api-tokens/{token_id}/delete")
async def delete_api_token(
    token_id: int,
    db: AsyncSession = Depends(get_db),
    current_user: User = Depends(require_auth),
) -> RedirectResponse:
    """Revoke (delete) a personal access token."""
    result = await db.execute(
        select(ApiToken).where(
            ApiToken.id == token_id, ApiToken.user_id == current_user.id
        )
    )
    token = result.scalar_one_or_none()
    if not token:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND, detail="Token not found"
        )

    await db.delete(token)
    await db.commit()

    return RedirectResponse(
        url="/user/api-tokens", status_code=status.HTTP_303_SEE_OTHER
    )


@router.post("/profile-image", response_class=HTMLResponse)
async def upload_profile_image(
    request: Request,
    file: UploadFile = File(...),
    db: AsyncSession = Depends(get_db),
    current_user: User = Depends(require_auth),
) -> Response:
    """Upload a new profile image for the current user."""

    user = await db.get(User, current_user.id)
    if not user:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND, detail="User not found"
        )

    try:
        filename, _ = await save_uploaded_image(file, user.id, subdir="users")
    except ValueError as exc:
        raise HTTPException(
            status_code=(
                status.HTTP_413_CONTENT_TOO_LARGE
                if isinstance(exc, UploadTooLargeError)
                else status.HTTP_400_BAD_REQUEST
            ),
            detail=(
                "Uploaded file is too large"
                if isinstance(exc, UploadTooLargeError)
                else str(exc)
            ),
        ) from exc

    file_path = config.MEDIA_ROOT / "users" / str(user.id) / filename
    try:
        await create_thumbnail(file_path, user.id, subdir="users")
    except Exception as exc:  # noqa: BLE001
        logger.exception("Failed to create profile image thumbnail")
        delete_file(filename, user.id, subdir="users")
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Could not process the uploaded image.",
        ) from exc

    previous_image = user.profile_image
    user.profile_image = filename
    await db.commit()
    await db.refresh(user)

    if previous_image and previous_image != filename:
        delete_file(previous_image, user.id, subdir="users")

    if request.headers.get("HX-Request"):
        return Response(status_code=status.HTTP_204_NO_CONTENT)

    return RedirectResponse(url="/", status_code=status.HTTP_303_SEE_OTHER)
