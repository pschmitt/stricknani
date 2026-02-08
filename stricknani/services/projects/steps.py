"""Project step helpers."""

from __future__ import annotations

from sqlalchemy.ext.asyncio import AsyncSession

from stricknani.models import Step


async def create_step(
    db: AsyncSession,
    *,
    project_id: int,
    title: str,
    description: str | None,
    step_number: int,
) -> Step:
    step = Step(
        title=title,
        description=description,
        step_number=step_number,
        project_id=project_id,
    )
    db.add(step)
    await db.commit()
    await db.refresh(step)
    return step


async def update_step(
    db: AsyncSession,
    *,
    step: Step,
    title: str | None,
    description: str | None,
    step_number: int | None,
) -> Step:
    if title is not None:
        step.title = title
    if description is not None:
        step.description = description
    if step_number is not None:
        step.step_number = step_number

    await db.commit()
    await db.refresh(step)
    return step
