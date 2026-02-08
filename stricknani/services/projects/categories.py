"""Project category helpers."""

from __future__ import annotations

from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from stricknani.models import Category, Project


async def sync_project_categories(db: AsyncSession, user_id: int) -> None:
    """Ensure categories used in projects exist in the category table."""
    project_result = await db.execute(
        select(Project.category).where(Project.owner_id == user_id).distinct()
    )
    project_categories = {row[0] for row in project_result if row[0]}
    if not project_categories:
        return

    existing_result = await db.execute(
        select(Category.name).where(Category.user_id == user_id)
    )
    existing = {row[0] for row in existing_result}
    missing = project_categories - existing
    if not missing:
        return

    for name in sorted(missing):
        db.add(Category(name=name, user_id=user_id))
    await db.commit()


async def get_user_categories(db: AsyncSession, user_id: int) -> list[str]:
    """Return all categories for a user."""
    await sync_project_categories(db, user_id)

    result = await db.execute(
        select(Category).where(Category.user_id == user_id).order_by(Category.name)
    )
    return [category.name for category in result.scalars()]


async def ensure_category(
    db: AsyncSession,
    user_id: int,
    name: str | None,
) -> str | None:
    """Ensure category exists for the user and return the sanitized label."""
    if not name:
        return None

    cleaned = name.strip()
    if not cleaned:
        return None

    result = await db.execute(
        select(Category).where(
            Category.user_id == user_id,
            func.lower(Category.name) == cleaned.lower(),
        )
    )
    category = result.scalar_one_or_none()
    if category is None:
        category = Category(name=cleaned, user_id=user_id)
        db.add(category)
        await db.flush()
        return category.name

    return category.name
