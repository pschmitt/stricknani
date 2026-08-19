"""Project endpoints for the JSON API."""

from __future__ import annotations

import logging
from datetime import UTC, datetime

from fastapi import (
    APIRouter,
    Depends,
    File,
    Form,
    HTTPException,
    Query,
    UploadFile,
    status,
)
from sqlalchemy import delete, insert, select
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm import selectinload

from stricknani.database import get_db
from stricknani.models import Attachment, Image, Project, Step, User
from stricknani.models.associations import user_favorites
from stricknani.routes.api.schemas import (
    AttachmentResponse,
    ImageResponse,
    ProjectListItemResponse,
    ProjectPage,
    ProjectResponse,
    ProjectWriteRequest,
    StepResponse,
)
from stricknani.routes.auth import require_api_token
from stricknani.services.audit import create_audit_log
from stricknani.services.projects.attachments import store_project_attachment
from stricknani.services.projects.categories import ensure_category
from stricknani.services.projects.images import upload_step_image, upload_title_image
from stricknani.services.projects.import_images import (
    import_project_images_from_urls,
    import_step_images_from_urls,
)
from stricknani.services.projects.tags import (
    deserialize_tags,
    normalize_tags,
    serialize_tags,
)
from stricknani.services.projects.yarns import load_owned_yarns
from stricknani.services.yarn.presentation import resolve_project_preview
from stricknani.utils.files import delete_file, get_file_url, get_thumbnail_url

logger = logging.getLogger("stricknani.api.projects")

router: APIRouter = APIRouter(prefix="/projects", tags=["api-projects"])

API_PAGE_SIZE = 50

_DETAIL_OPTIONS = (
    selectinload(Project.steps).selectinload(Step.images),
    selectinload(Project.images),
    selectinload(Project.attachments),
    selectinload(Project.yarns),
)


def _as_utc(dt: datetime) -> datetime:
    return dt if dt.tzinfo else dt.replace(tzinfo=UTC)


def _differs(expected: datetime, actual: datetime) -> bool:
    """Compares two `updated_at` values, tolerant of naive-vs-UTC-aware mismatches
    (SNA-33) - this codebase's `DateTime` columns store UTC but SQLite hands back
    naive datetimes on read."""
    return _as_utc(expected) != _as_utc(actual)


async def _get_owned_project(
    db: AsyncSession, project_id: int, user_id: int, *, with_detail: bool = True
) -> Project:
    query = select(Project).where(Project.id == project_id, Project.owner_id == user_id)
    if with_detail:
        query = query.options(*_DETAIL_OPTIONS)
    result = await db.execute(query)
    project = result.scalar_one_or_none()
    if project is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND, detail="Project not found"
        )
    return project


async def _favorite_project_ids(db: AsyncSession, user_id: int) -> set[int]:
    result = await db.execute(
        select(user_favorites.c.project_id).where(user_favorites.c.user_id == user_id)
    )
    return {row[0] for row in result}


def _serialize_image(image: Image, project_id: int) -> ImageResponse:
    return ImageResponse(
        id=image.id,
        url=get_file_url(image.filename, project_id),
        thumbnail_url=get_thumbnail_url(image.filename, project_id),
        alt_text=image.alt_text,
        image_type=image.image_type,
        is_title_image=image.is_title_image,
        is_stitch_sample=bool(image.is_stitch_sample),
        step_id=image.step_id,
    )


def _serialize_attachment(
    attachment: Attachment, project_id: int
) -> AttachmentResponse:
    return AttachmentResponse(
        id=attachment.id,
        filename=attachment.filename,
        original_filename=attachment.original_filename,
        content_type=attachment.content_type,
        size_bytes=attachment.size_bytes,
        url=get_file_url(attachment.filename, project_id),
    )


def _serialize_project(project: Project, *, is_favorite: bool) -> ProjectResponse:
    return ProjectResponse(
        id=project.id,
        name=project.name,
        category=project.category,
        yarn=project.yarn,
        needles=project.needles,
        stitch_sample=project.stitch_sample,
        description=project.description,
        notes=project.notes,
        other_materials=project.other_materials,
        tags=deserialize_tags(project.tags),
        link=project.link,
        link_archive=project.link_archive,
        is_ai_enhanced=project.is_ai_enhanced,
        is_favorite=is_favorite,
        created_at=project.created_at,
        updated_at=project.updated_at,
        yarn_ids=[yarn.id for yarn in project.yarns],
        steps=[
            StepResponse(
                id=step.id,
                title=step.title,
                description=step.description,
                step_number=step.step_number,
            )
            for step in project.steps
        ],
        images=[_serialize_image(image, project.id) for image in project.images],
        attachments=[
            _serialize_attachment(attachment, project.id)
            for attachment in project.attachments
        ],
    )


@router.get("", response_model=ProjectPage)
async def list_projects(
    page: int = Query(1, ge=1),
    category: str | None = Query(None),
    tag: str | None = Query(None),
    favorite: bool | None = Query(None),
    db: AsyncSession = Depends(get_db),
    current_user: User = Depends(require_api_token),
) -> ProjectPage:
    """List projects for the current user, most recently updated first."""
    favorite_ids = await _favorite_project_ids(db, current_user.id)

    query = (
        select(Project)
        .where(Project.owner_id == current_user.id)
        .options(selectinload(Project.images))
        .order_by(Project.updated_at.desc(), Project.id.desc())
    )
    if category:
        query = query.where(Project.category == category)
    if tag:
        query = query.where(Project.tags.ilike(f"%{tag}%"))
    if favorite is True:
        query = query.where(Project.id.in_(favorite_ids))
    elif favorite is False:
        query = query.where(Project.id.notin_(favorite_ids))

    offset = (page - 1) * API_PAGE_SIZE
    result = await db.execute(query.offset(offset).limit(API_PAGE_SIZE + 1))
    projects = list(result.scalars().all())
    has_more = len(projects) > API_PAGE_SIZE
    projects = projects[:API_PAGE_SIZE]

    items = [
        ProjectListItemResponse(
            id=project.id,
            name=project.name,
            category=project.category,
            tags=deserialize_tags(project.tags),
            is_favorite=project.id in favorite_ids,
            updated_at=project.updated_at,
            preview_url=resolve_project_preview(project)["preview_url"],
        )
        for project in projects
    ]
    return ProjectPage(
        items=items, page=page, per_page=API_PAGE_SIZE, has_more=has_more
    )


@router.get("/{project_id}", response_model=ProjectResponse)
async def get_project(
    project_id: int,
    db: AsyncSession = Depends(get_db),
    current_user: User = Depends(require_api_token),
) -> ProjectResponse:
    project = await _get_owned_project(db, project_id, current_user.id)
    favorite_ids = await _favorite_project_ids(db, current_user.id)
    return _serialize_project(project, is_favorite=project.id in favorite_ids)


@router.post("", response_model=ProjectResponse, status_code=status.HTTP_201_CREATED)
async def create_project(
    payload: ProjectWriteRequest,
    db: AsyncSession = Depends(get_db),
    current_user: User = Depends(require_api_token),
) -> ProjectResponse:
    normalized_category = await ensure_category(db, current_user.id, payload.category)
    normalized_tags = normalize_tags(",".join(payload.tags))

    project = Project(
        name=payload.name,
        category=normalized_category,
        needles=payload.needles or None,
        stitch_sample=payload.stitch_sample or None,
        description=payload.description or None,
        notes=payload.notes or None,
        other_materials=payload.other_materials or None,
        link=payload.link or None,
        owner_id=current_user.id,
        tags=serialize_tags(normalized_tags),
        is_ai_enhanced=payload.is_ai_enhanced,
    )
    project.yarns = list(await load_owned_yarns(db, current_user.id, payload.yarn_ids))
    project.yarn = project.yarns[0].name if project.yarns else None
    db.add(project)
    await db.flush()

    created_steps: list[Step] = []
    for step_payload in payload.steps:
        step = Step(
            title=step_payload.title,
            description=step_payload.description,
            step_number=step_payload.step_number,
            project_id=project.id,
        )
        db.add(step)
        created_steps.append(step)

    await db.flush()
    imported_images = await import_project_images_from_urls(
        db, project, payload.image_urls
    )
    imported_step_images = 0
    for step_payload, step in zip(payload.steps, created_steps, strict=True):
        imported_step_images += await import_step_images_from_urls(
            db, step, step_payload.image_urls
        )

    await create_audit_log(
        db,
        actor_user_id=current_user.id,
        entity_type="project",
        entity_id=project.id,
        action="created",
        details={
            "name": project.name,
            "category": project.category,
            "yarn_count": len(project.yarns),
            "step_count": len(payload.steps),
            "image_count": imported_images + imported_step_images,
        },
    )
    await db.commit()
    project = await _get_owned_project(db, project.id, current_user.id)
    return _serialize_project(project, is_favorite=False)


@router.put("/{project_id}", response_model=ProjectResponse)
async def update_project(
    project_id: int,
    payload: ProjectWriteRequest,
    db: AsyncSession = Depends(get_db),
    current_user: User = Depends(require_api_token),
) -> ProjectResponse:
    """Update a project.

    SNA-33: when `payload.expected_updated_at` is set and no longer matches the
    project's current `updated_at`, it changed since the client last saw it (edited
    elsewhere). Reject with 409 and the project's current server state in the body,
    so the caller can adopt it immediately instead of silently overwriting a newer
    edit (previously an unconditional last-write-wins - see android's
    `WriteReplayWorker`). Callers that omit `expected_updated_at` (e.g. the web UI,
    which always edits the live row it's looking at) keep the old
    unconditional-write behavior.
    """
    project = await _get_owned_project(db, project_id, current_user.id)

    if payload.expected_updated_at is not None and _differs(
        payload.expected_updated_at, project.updated_at
    ):
        favorite_ids = await _favorite_project_ids(db, current_user.id)
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail=_serialize_project(
                project, is_favorite=project.id in favorite_ids
            ).model_dump(mode="json"),
        )

    normalized_category = await ensure_category(db, current_user.id, payload.category)
    normalized_tags = normalize_tags(",".join(payload.tags))

    project.name = payload.name
    project.category = normalized_category
    project.needles = payload.needles or None
    project.stitch_sample = payload.stitch_sample or None
    project.description = payload.description or None
    project.notes = payload.notes or None
    project.other_materials = payload.other_materials or None
    project.link = payload.link or None
    project.tags = serialize_tags(normalized_tags)
    project.is_ai_enhanced = payload.is_ai_enhanced
    project.yarns = list(await load_owned_yarns(db, current_user.id, payload.yarn_ids))
    project.yarn = project.yarns[0].name if project.yarns else None

    # Steps are fully replaced on update - the app sends the complete
    # current step list (matching how the web form's steps editor works).
    # Mutating the relationship collection (rather than a raw DELETE query)
    # lets the "all, delete-orphan" cascade on Project.steps clean up the
    # removed rows (and their images) correctly at flush time.
    project.steps.clear()
    for step_payload in payload.steps:
        project.steps.append(
            Step(
                title=step_payload.title,
                description=step_payload.description,
                step_number=step_payload.step_number,
            )
        )

    await create_audit_log(
        db,
        actor_user_id=current_user.id,
        entity_type="project",
        entity_id=project.id,
        action="updated",
        details={"name": project.name},
    )
    await db.commit()
    project = await _get_owned_project(db, project.id, current_user.id)
    favorite_ids = await _favorite_project_ids(db, current_user.id)
    return _serialize_project(project, is_favorite=project.id in favorite_ids)


@router.delete("/{project_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_project(
    project_id: int,
    db: AsyncSession = Depends(get_db),
    current_user: User = Depends(require_api_token),
) -> None:
    project = await _get_owned_project(db, project_id, current_user.id)
    image_filenames = [image.filename for image in project.images]
    attachment_filenames = [attachment.filename for attachment in project.attachments]

    await create_audit_log(
        db,
        actor_user_id=current_user.id,
        entity_type="project",
        entity_id=project.id,
        action="deleted",
        details={"name": project.name},
    )
    await db.delete(project)
    await db.commit()

    for filename in image_filenames + attachment_filenames:
        try:
            delete_file(filename, project_id)
        except OSError as exc:
            logger.warning("Failed to remove project file %s: %s", filename, exc)


@router.post(
    "/{project_id}/favorite",
    response_model=ProjectResponse,
    status_code=status.HTTP_200_OK,
)
async def favorite_project(
    project_id: int,
    db: AsyncSession = Depends(get_db),
    current_user: User = Depends(require_api_token),
) -> ProjectResponse:
    project = await _get_owned_project(db, project_id, current_user.id)
    existing = await db.execute(
        select(user_favorites.c.project_id).where(
            user_favorites.c.user_id == current_user.id,
            user_favorites.c.project_id == project_id,
        )
    )
    if existing.first() is None:
        await db.execute(
            insert(user_favorites).values(
                user_id=current_user.id, project_id=project_id
            )
        )
        await db.commit()
        project = await _get_owned_project(db, project_id, current_user.id)
    return _serialize_project(project, is_favorite=True)


@router.delete(
    "/{project_id}/favorite",
    response_model=ProjectResponse,
    status_code=status.HTTP_200_OK,
)
async def unfavorite_project(
    project_id: int,
    db: AsyncSession = Depends(get_db),
    current_user: User = Depends(require_api_token),
) -> ProjectResponse:
    project = await _get_owned_project(db, project_id, current_user.id)
    await db.execute(
        delete(user_favorites).where(
            user_favorites.c.user_id == current_user.id,
            user_favorites.c.project_id == project_id,
        )
    )
    await db.commit()
    project = await _get_owned_project(db, project_id, current_user.id)
    return _serialize_project(project, is_favorite=False)


@router.post(
    "/{project_id}/attachments",
    response_model=AttachmentResponse,
    status_code=status.HTTP_201_CREATED,
)
async def upload_attachment(
    project_id: int,
    file: UploadFile = File(...),
    db: AsyncSession = Depends(get_db),
    current_user: User = Depends(require_api_token),
) -> AttachmentResponse:
    await _get_owned_project(db, project_id, current_user.id, with_detail=False)

    stored = await store_project_attachment(project_id, file)
    attachment = Attachment(
        filename=stored.filename,
        original_filename=stored.original_filename,
        content_type=stored.content_type,
        size_bytes=stored.size_bytes,
        project_id=project_id,
    )
    db.add(attachment)
    await db.flush()

    await create_audit_log(
        db,
        actor_user_id=current_user.id,
        entity_type="project",
        entity_id=project_id,
        action="attachment_uploaded",
        details={
            "attachment_id": attachment.id,
            "filename": attachment.filename,
        },
    )
    await db.commit()
    await db.refresh(attachment)
    return _serialize_attachment(attachment, project_id)


@router.delete(
    "/{project_id}/attachments/{attachment_id}",
    status_code=status.HTTP_204_NO_CONTENT,
)
async def delete_attachment(
    project_id: int,
    attachment_id: int,
    db: AsyncSession = Depends(get_db),
    current_user: User = Depends(require_api_token),
) -> None:
    await _get_owned_project(db, project_id, current_user.id, with_detail=False)

    attachment = await db.get(Attachment, attachment_id)
    if not attachment or attachment.project_id != project_id:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND, detail="Attachment not found"
        )

    filename = attachment.filename
    await create_audit_log(
        db,
        actor_user_id=current_user.id,
        entity_type="project",
        entity_id=project_id,
        action="attachment_deleted",
        details={"attachment_id": attachment.id, "filename": filename},
    )
    await db.delete(attachment)
    await db.commit()

    try:
        delete_file(filename, project_id)
    except OSError as exc:
        logger.warning("Failed to remove project attachment file %s: %s", filename, exc)


@router.post(
    "/{project_id}/images/title",
    response_model=ImageResponse,
    status_code=status.HTTP_201_CREATED,
)
async def upload_project_title_image(
    project_id: int,
    file: UploadFile = File(...),
    alt_text: str = Form(""),
    db: AsyncSession = Depends(get_db),
    current_user: User = Depends(require_api_token),
) -> ImageResponse:
    await _get_owned_project(db, project_id, current_user.id, with_detail=False)

    uploaded = await upload_title_image(
        db, project_id=project_id, file=file, alt_text=alt_text
    )
    await create_audit_log(
        db,
        actor_user_id=current_user.id,
        entity_type="project",
        entity_id=project_id,
        action="title_image_uploaded",
        details={"image_id": uploaded["id"]},
    )
    await db.commit()

    result = await db.execute(select(Image).where(Image.id == uploaded["id"]))
    image = result.scalar_one()
    return _serialize_image(image, project_id)


@router.post(
    "/{project_id}/steps/{step_id}/images",
    response_model=ImageResponse,
    status_code=status.HTTP_201_CREATED,
)
async def upload_project_step_image(
    project_id: int,
    step_id: int,
    file: UploadFile = File(...),
    alt_text: str = Form(""),
    db: AsyncSession = Depends(get_db),
    current_user: User = Depends(require_api_token),
) -> ImageResponse:
    await _get_owned_project(db, project_id, current_user.id, with_detail=False)

    step = await db.get(Step, step_id)
    if step is None or step.project_id != project_id:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND, detail="Step not found"
        )

    uploaded = await upload_step_image(
        db, project_id=project_id, step_id=step_id, file=file, alt_text=alt_text
    )
    await create_audit_log(
        db,
        actor_user_id=current_user.id,
        entity_type="project",
        entity_id=project_id,
        action="step_image_uploaded",
        details={"image_id": uploaded["id"], "step_id": step_id},
    )
    await db.commit()

    result = await db.execute(select(Image).where(Image.id == uploaded["id"]))
    image = result.scalar_one()
    return _serialize_image(image, project_id)


@router.delete(
    "/{project_id}/images/{image_id}", status_code=status.HTTP_204_NO_CONTENT
)
async def delete_image(
    project_id: int,
    image_id: int,
    db: AsyncSession = Depends(get_db),
    current_user: User = Depends(require_api_token),
) -> None:
    await _get_owned_project(db, project_id, current_user.id, with_detail=False)

    result = await db.execute(
        select(Image).where(Image.id == image_id, Image.project_id == project_id)
    )
    image = result.scalar_one_or_none()
    if image is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND, detail="Image not found"
        )

    filename = image.filename
    await create_audit_log(
        db,
        actor_user_id=current_user.id,
        entity_type="project",
        entity_id=project_id,
        action="image_deleted",
        details={
            "image_id": image.id,
            "filename": filename,
            "step_id": image.step_id,
            "is_title_image": image.is_title_image,
        },
    )
    await db.delete(image)
    await db.commit()

    try:
        delete_file(filename, project_id)
    except OSError as exc:
        logger.warning("Failed to remove project image file %s: %s", filename, exc)
