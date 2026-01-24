"""Admin management routes."""

from urllib.parse import urlencode

from fastapi import APIRouter, Depends, Form, Request, status
from fastapi.responses import HTMLResponse, PlainTextResponse, RedirectResponse, Response
from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from stricknani.database import get_db
from stricknani.main import get_language, render_template, templates
from stricknani.models import User
from stricknani.routes.auth import require_admin
from stricknani.utils.auth import get_password_hash
from stricknani.utils.gravatar import gravatar_url
from stricknani.utils.i18n import gettext, install_i18n

router: APIRouter = APIRouter(prefix="/admin", tags=["admin"])


def _admin_users_redirect(toast_key: str) -> RedirectResponse:
    query = urlencode({"toast": toast_key})
    return RedirectResponse(
        url=f"/admin/users?{query}", status_code=status.HTTP_303_SEE_OTHER
    )


def _admin_error_response(
    request: Request, toast_key: str, message: str
) -> Response:
    if request.headers.get("HX-Request"):
        language = get_language(request)
        return PlainTextResponse(
            gettext(message, language),
            status_code=status.HTTP_400_BAD_REQUEST,
        )
    return _admin_users_redirect(toast_key)


def _render_user_card_response(
    request: Request,
    user: User,
    current_user: User,
    user_count: int,
) -> HTMLResponse:
    language = get_language(request)
    install_i18n(templates.env, language)
    context = {
        "request": request,
        "current_user": current_user,
        "user": user,
        "gravatar_url": gravatar_url,
        "user_count": user_count,
    }
    card_html = templates.get_template("admin/_user_card.html").render(**context)
    count_html = templates.get_template("admin/_user_count.html").render(**context)
    return HTMLResponse(card_html + count_html)


def _render_user_deleted_response(request: Request, user_id: int, user_count: int) -> HTMLResponse:
    language = get_language(request)
    install_i18n(templates.env, language)
    count_html = templates.get_template("admin/_user_count.html").render(
        request=request,
        user_count=user_count,
    )
    placeholder = (
        f'<div id="user-card-{user_id}" data-user-card class="hidden"></div>'
    )
    return HTMLResponse(placeholder + count_html)


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
    result = await db.execute(select(User).order_by(User.email))
    users = result.scalars().all()
    return render_template(
        "admin/users.html",
        request,
        {
            "current_user": current_user,
            "users": users,
            "gravatar_url": gravatar_url,
        },
    )


@router.post("/users/{user_id}/toggle-admin")
async def toggle_admin_status(
    user_id: int,
    request: Request,
    db: AsyncSession = Depends(get_db),
    current_user: User = Depends(require_admin),
) -> Response:
    """Toggle admin status for a user."""
    user = await db.get(User, user_id)
    if not user:
        return _admin_error_response(
            request,
            "user_not_found",
            "User not found.",
        )

    if user.is_admin:
        admin_count = await _get_admin_count(db)
        if admin_count <= 1:
            return _admin_error_response(
                request,
                "cannot_remove_last_admin",
                "Cannot remove the last admin.",
            )
        if user.id == current_user.id:
            return _admin_error_response(
                request,
                "cannot_remove_own_admin",
                "Cannot remove your own admin access.",
            )
        user.is_admin = False
    else:
        user.is_admin = True

    await db.commit()
    if request.headers.get("HX-Request"):
        result = await db.execute(select(func.count()).select_from(User))
        user_count = int(result.scalar_one())
        return _render_user_card_response(request, user, current_user, user_count)
    return _admin_users_redirect(
        "admin_revoked" if not user.is_admin else "admin_granted"
    )


@router.post("/users/{user_id}/toggle-active")
async def toggle_active_status(
    user_id: int,
    request: Request,
    db: AsyncSession = Depends(get_db),
    current_user: User = Depends(require_admin),
) -> Response:
    """Toggle active status for a user."""
    user = await db.get(User, user_id)
    if not user:
        return _admin_error_response(
            request,
            "user_not_found",
            "User not found.",
        )

    if user.id == current_user.id and user.is_active:
        return _admin_error_response(
            request,
            "cannot_deactivate_self",
            "You cannot disable your own account.",
        )

    user.is_active = not user.is_active
    await db.commit()
    if request.headers.get("HX-Request"):
        result = await db.execute(select(func.count()).select_from(User))
        user_count = int(result.scalar_one())
        return _render_user_card_response(request, user, current_user, user_count)
    return _admin_users_redirect(
        "user_deactivated" if not user.is_active else "user_activated"
    )


@router.post("/users/{user_id}/delete")
async def delete_user_admin(
    user_id: int,
    request: Request,
    db: AsyncSession = Depends(get_db),
    current_user: User = Depends(require_admin),
) -> Response:
    """Delete a user and their data from the admin UI."""
    user = await db.get(User, user_id)
    if not user:
        return _admin_error_response(
            request,
            "user_not_found",
            "User not found.",
        )

    if user.id == current_user.id:
        return _admin_error_response(
            request,
            "cannot_delete_self",
            "You cannot delete your own account.",
        )

    if user.is_admin:
        admin_count = await _get_admin_count(db)
        if admin_count <= 1:
            return _admin_error_response(
                request,
                "cannot_delete_last_admin",
                "Cannot delete the last admin.",
            )

    await db.delete(user)
    await db.commit()
    if request.headers.get("HX-Request"):
        result = await db.execute(select(func.count()).select_from(User))
        user_count = int(result.scalar_one())
        return _render_user_deleted_response(request, user_id, user_count)
    return _admin_users_redirect("user_deleted")


@router.post("/users/{user_id}/reset-password")
async def reset_password(
    user_id: int,
    request: Request,
    password: str = Form(...),
    db: AsyncSession = Depends(get_db),
    current_user: User = Depends(require_admin),
) -> Response:
    """Reset a user's password."""
    user = await db.get(User, user_id)
    if not user:
        return _admin_error_response(
            request,
            "user_not_found",
            "User not found.",
        )

    if not password:
        return _admin_error_response(
            request,
            "password_empty",
            "Password cannot be empty.",
        )

    user.hashed_password = get_password_hash(password)
    user.is_active = True
    await db.commit()
    if request and request.headers.get("HX-Request"):
        return Response(status_code=status.HTTP_204_NO_CONTENT)
    return _admin_users_redirect("password_reset")


@router.post("/users/create")
async def create_user_admin(
    request: Request,
    email: str = Form(...),
    password: str = Form(...),
    is_admin: bool = Form(False),
    db: AsyncSession = Depends(get_db),
    current_user: User = Depends(require_admin),
) -> Response:
    """Create a new user from the admin UI."""
    normalized_email = email.strip().lower()
    if not normalized_email:
        return _admin_error_response(
            request,
            "email_empty",
            "Email cannot be empty.",
        )
    if not password:
        return _admin_error_response(
            request,
            "password_empty",
            "Password cannot be empty.",
        )

    existing = await db.execute(select(User.id).where(User.email == normalized_email))
    if existing.scalar_one_or_none() is not None:
        return _admin_error_response(
            request,
            "email_exists",
            "Email already registered.",
        )

    user = User(
        email=normalized_email,
        hashed_password=get_password_hash(password),
        is_active=True,
        is_admin=is_admin,
    )
    db.add(user)
    await db.commit()
    if request and request.headers.get("HX-Request"):
        result = await db.execute(select(func.count()).select_from(User))
        user_count = int(result.scalar_one())
        return _render_user_card_response(request, user, current_user, user_count)
    return _admin_users_redirect("user_created")
