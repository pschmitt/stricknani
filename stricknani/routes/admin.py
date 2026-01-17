"""Admin management routes."""

from fastapi import APIRouter, Depends, Form, HTTPException, Request, status
from fastapi.responses import HTMLResponse, RedirectResponse
from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from stricknani.database import get_db
from stricknani.main import render_template
from stricknani.models import User
from stricknani.routes.auth import require_admin
from stricknani.utils.auth import get_password_hash

router: APIRouter = APIRouter(prefix="/admin", tags=["admin"])


async def _get_admin_count(db: AsyncSession) -> int:
    result = await db.execute(
        select(func.count()).select_from(User).where(User.is_admin.is_(True))
    )
    return int(result.scalar_one())


@router.get("/users", response_class=HTMLResponse)
async def admin_users_view(
    request: Request,
    db: AsyncSession = Depends(get_db),
    current_user: User = Depends(require_admin),
) -> HTMLResponse:
    """Render the admin user management view."""
    result = await db.execute(select(User).order_by(User.created_at.desc()))
    users = result.scalars().all()
    return render_template(
        "admin/users.html",
        request,
        {
            "current_user": current_user,
            "users": users,
        },
    )


@router.post("/users/{user_id}/toggle-admin")
async def toggle_admin_status(
    user_id: int,
    db: AsyncSession = Depends(get_db),
    current_user: User = Depends(require_admin),
) -> RedirectResponse:
    """Toggle admin status for a user."""
    user = await db.get(User, user_id)
    if not user:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND, detail="User not found"
        )

    if user.is_admin:
        admin_count = await _get_admin_count(db)
        if admin_count <= 1:
            raise HTTPException(
                status_code=status.HTTP_400_BAD_REQUEST,
                detail="Cannot remove the last admin",
            )
        if user.id == current_user.id:
            raise HTTPException(
                status_code=status.HTTP_400_BAD_REQUEST,
                detail="Cannot remove your own admin access",
            )
        user.is_admin = False
    else:
        user.is_admin = True

    await db.commit()
    return RedirectResponse(url="/admin/users", status_code=status.HTTP_303_SEE_OTHER)


@router.post("/users/{user_id}/toggle-active")
async def toggle_active_status(
    user_id: int,
    db: AsyncSession = Depends(get_db),
    current_user: User = Depends(require_admin),
) -> RedirectResponse:
    """Toggle active status for a user."""
    user = await db.get(User, user_id)
    if not user:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND, detail="User not found"
        )

    if user.id == current_user.id and user.is_active:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Cannot deactivate your own account",
        )

    user.is_active = not user.is_active
    await db.commit()
    return RedirectResponse(url="/admin/users", status_code=status.HTTP_303_SEE_OTHER)


@router.post("/users/{user_id}/delete")
async def delete_user_admin(
    user_id: int,
    db: AsyncSession = Depends(get_db),
    current_user: User = Depends(require_admin),
) -> RedirectResponse:
    """Delete a user and their data from the admin UI."""
    user = await db.get(User, user_id)
    if not user:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND, detail="User not found"
        )

    if user.id == current_user.id:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Cannot delete your own account",
        )

    if user.is_admin:
        admin_count = await _get_admin_count(db)
        if admin_count <= 1:
            raise HTTPException(
                status_code=status.HTTP_400_BAD_REQUEST,
                detail="Cannot delete the last admin",
            )

    await db.delete(user)
    await db.commit()
    return RedirectResponse(url="/admin/users", status_code=status.HTTP_303_SEE_OTHER)


@router.post("/users/{user_id}/reset-password")
async def reset_password(
    user_id: int,
    password: str = Form(...),
    db: AsyncSession = Depends(get_db),
    current_user: User = Depends(require_admin),
) -> RedirectResponse:
    """Reset a user's password."""
    user = await db.get(User, user_id)
    if not user:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND, detail="User not found"
        )

    if not password:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Password cannot be empty",
        )

    user.hashed_password = get_password_hash(password)
    user.is_active = True
    await db.commit()
    return RedirectResponse(url="/admin/users", status_code=status.HTTP_303_SEE_OTHER)


@router.post("/users/create")
async def create_user_admin(
    email: str = Form(...),
    password: str = Form(...),
    is_admin: bool = Form(False),
    db: AsyncSession = Depends(get_db),
    current_user: User = Depends(require_admin),
) -> RedirectResponse:
    """Create a new user from the admin UI."""
    normalized_email = email.strip().lower()
    if not normalized_email:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Email cannot be empty",
        )
    if not password:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Password cannot be empty",
        )

    existing = await db.execute(select(User.id).where(User.email == normalized_email))
    if existing.scalar_one_or_none() is not None:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Email already registered",
        )

    user = User(
        email=normalized_email,
        hashed_password=get_password_hash(password),
        is_active=True,
        is_admin=is_admin,
    )
    db.add(user)
    await db.commit()
    return RedirectResponse(url="/admin/users", status_code=status.HTTP_303_SEE_OTHER)
