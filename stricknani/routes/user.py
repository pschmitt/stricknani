"""User preference routes."""

import logging

from fastapi import (
    APIRouter,
    Depends,
    File,
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
from stricknani.main import render_template
from stricknani.models import Project, User, Yarn, user_favorite_yarns, user_favorites
from stricknani.routes.auth import require_auth
from stricknani.utils.files import (
    create_thumbnail,
    delete_file,
    get_file_url,
    get_thumbnail_url,
    save_uploaded_file,
)
from stricknani.utils.gravatar import gravatar_url

logger = logging.getLogger(__name__)

router: APIRouter = APIRouter(prefix="/user", tags=["user"])


@router.get("/profile", response_class=HTMLResponse)
async def profile_view(
    request: Request,
    db: AsyncSession = Depends(get_db),
    current_user: User = Depends(require_auth),
) -> HTMLResponse:
    """Render the user profile page."""

    favorites_result = await db.execute(
        select(Project)
        .join(user_favorites, user_favorites.c.project_id == Project.id)
        .where(user_favorites.c.user_id == current_user.id)
        .order_by(Project.created_at.desc())
    )
    favorites = favorites_result.scalars().all()

    favorite_yarns_result = await db.execute(
        select(Yarn)
        .join(user_favorite_yarns, user_favorite_yarns.c.yarn_id == Yarn.id)
        .where(user_favorite_yarns.c.user_id == current_user.id)
        .order_by(Yarn.created_at.desc())
    )
    favorite_yarns = favorite_yarns_result.scalars().all()

    profile_image_url = None
    profile_thumbnail_url = None
    if current_user.profile_image:
        profile_image_url = get_file_url(
            current_user.profile_image, current_user.id, subdir="users"
        )
        profile_thumbnail_url = get_thumbnail_url(
            current_user.profile_image, current_user.id, subdir="users"
        )
    else:
        profile_thumbnail_url = gravatar_url(current_user.email)

    return render_template(
        "user/profile.html",
        request,
        {
            "current_user": current_user,
            "favorites": favorites,
            "favorite_yarns": favorite_yarns,
            "profile_image_url": profile_image_url,
            "profile_thumbnail_url": profile_thumbnail_url,
            "supported_languages": config.SUPPORTED_LANGUAGES,
        },
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
        filename, _ = await save_uploaded_file(file, user.id, subdir="users")
    except ValueError as exc:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST, detail=str(exc)
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
        profile_image_url = None
        profile_thumbnail_url = None
        if user.profile_image:
            profile_image_url = get_file_url(
                user.profile_image, user.id, subdir="users"
            )
            profile_thumbnail_url = get_thumbnail_url(
                user.profile_image, user.id, subdir="users"
            )
        else:
            profile_thumbnail_url = gravatar_url(user.email)

        return render_template(
            "user/_profile_avatar.html",
            request,
            {
                "current_user": user,
                "profile_image_url": profile_image_url,
                "profile_thumbnail_url": profile_thumbnail_url,
            },
        )

    return RedirectResponse(url="/user/profile", status_code=status.HTTP_303_SEE_OTHER)
