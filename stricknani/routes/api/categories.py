"""Category endpoints for the JSON API."""

from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy import func, select, update
from sqlalchemy.ext.asyncio import AsyncSession

from stricknani.database import get_db
from stricknani.models import Category, Project, User
from stricknani.routes.api.schemas import (
    CategoryCreateRequest,
    CategoryResponse,
    CategoryUpdateRequest,
)
from stricknani.routes.auth import require_api_token
from stricknani.services.projects.categories import (
    ensure_category,
    sync_project_categories,
)

router: APIRouter = APIRouter(prefix="/categories", tags=["api-categories"])


async def _user_categories(db: AsyncSession, user_id: int) -> list[CategoryResponse]:
    await sync_project_categories(db, user_id)
    result = await db.execute(
        select(Category).where(Category.user_id == user_id).order_by(Category.name)
    )
    return [
        CategoryResponse(id=category.id, name=category.name)
        for category in result.scalars()
    ]


@router.get("", response_model=list[CategoryResponse])
async def list_categories(
    db: AsyncSession = Depends(get_db),
    current_user: User = Depends(require_api_token),
) -> list[CategoryResponse]:
    """List the current user's categories."""
    return await _user_categories(db, current_user.id)


@router.post("", response_model=CategoryResponse, status_code=201)
async def create_category(
    payload: CategoryCreateRequest,
    db: AsyncSession = Depends(get_db),
    current_user: User = Depends(require_api_token),
) -> CategoryResponse:
    """Create (or return the existing) category by name."""
    name = await ensure_category(db, current_user.id, payload.name)
    if name is None:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST, detail="Category name is required"
        )
    await db.commit()

    result = await db.execute(
        select(Category).where(
            Category.user_id == current_user.id, Category.name == name
        )
    )
    category = result.scalar_one()
    return CategoryResponse(id=category.id, name=category.name)


@router.put("/{category_id}", response_model=CategoryResponse)
async def update_category(
    category_id: int,
    payload: CategoryUpdateRequest,
    db: AsyncSession = Depends(get_db),
    current_user: User = Depends(require_api_token),
) -> CategoryResponse:
    """Rename one of the current user's categories and its projects."""
    category = await db.get(Category, category_id)
    if category is None or category.user_id != current_user.id:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND)

    name = payload.name.strip()
    if not name:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST, detail="Category name is required"
        )

    conflict = await db.execute(
        select(Category).where(
            Category.user_id == current_user.id,
            func.lower(Category.name) == name.lower(),
            Category.id != category_id,
        )
    )
    if conflict.scalar_one_or_none() is not None:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="Another category already uses that name",
        )

    old_name = category.name
    category.name = name
    await db.execute(
        update(Project)
        .where(Project.owner_id == current_user.id, Project.category == old_name)
        .values(category=name)
    )
    await db.commit()
    return CategoryResponse(id=category.id, name=category.name)


@router.delete("/{category_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_category(
    category_id: int,
    db: AsyncSession = Depends(get_db),
    current_user: User = Depends(require_api_token),
) -> None:
    """Delete a category and clear it from the owner's projects."""
    category = await db.get(Category, category_id)
    if category is None or category.user_id != current_user.id:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND)

    await db.execute(
        update(Project)
        .where(Project.owner_id == current_user.id, Project.category == category.name)
        .values(category=None)
    )
    await db.delete(category)
    await db.commit()
