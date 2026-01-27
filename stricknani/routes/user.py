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
from sqlalchemy.ext.asyncio import AsyncSession

from stricknani.config import config
from stricknani.database import get_db
from stricknani.models import User
from stricknani.routes.auth import require_auth
from stricknani.utils.files import (
    create_thumbnail,
    delete_file,
    save_uploaded_file,
)

logger = logging.getLogger(__name__)

router: APIRouter = APIRouter(prefix="/user", tags=["user"])


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
        return Response(status_code=status.HTTP_204_NO_CONTENT)

    return RedirectResponse(url="/", status_code=status.HTTP_303_SEE_OTHER)
