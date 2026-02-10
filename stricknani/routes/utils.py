"""Utility routes (OCR, previews, etc.)."""

from __future__ import annotations

import shutil
from datetime import UTC, datetime
from pathlib import Path
from urllib.parse import urlparse

from fastapi import APIRouter, Depends, File, Form, HTTPException, UploadFile, status
from fastapi.responses import JSONResponse
from pydantic import BaseModel
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from stricknani.config import config
from stricknani.database import get_db
from stricknani.models import Image, ImageType, Project, User, Yarn, YarnImage
from stricknani.routes.auth import require_auth
from stricknani.services.images import get_image_dimensions
from stricknani.utils.files import create_thumbnail, get_file_url, get_thumbnail_url
from stricknani.utils.ocr import DEFAULT_OCR_LANG, extract_text_from_media_file

router: APIRouter = APIRouter(prefix="/utils", tags=["utils"])


class OcrRequest(BaseModel):
    src: str
    lang: str | None = None
    force: bool = False


class OcrResponse(BaseModel):
    text: str


def _resolve_media_file(src: str) -> tuple[str, int, Path]:
    parsed = urlparse(src)
    path = parsed.path
    if not path.startswith("/media/"):
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST, detail="invalid_src"
        )

    rel = path.removeprefix("/media/")
    parts = rel.split("/", 2)
    if len(parts) != 3:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST, detail="invalid_src"
        )

    kind, id_str, filename = parts
    if kind not in {"projects", "yarns"}:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST, detail="invalid_src"
        )

    try:
        entity_id = int(id_str)
    except ValueError as exc:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST, detail="invalid_src"
        ) from exc

    if not filename or "/" in filename or filename in {".", ".."}:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST, detail="invalid_src"
        )

    file_path = config.MEDIA_ROOT / kind / str(entity_id) / filename
    return kind, entity_id, file_path


@router.post("/ocr", response_model=OcrResponse)
async def ocr_image(
    payload: OcrRequest,
    db: AsyncSession = Depends(get_db),
    current_user: User = Depends(require_auth),
) -> OcrResponse | JSONResponse:
    try:
        kind, entity_id, file_path = _resolve_media_file(payload.src)

        if not file_path.is_file():
            raise HTTPException(
                status_code=status.HTTP_404_NOT_FOUND, detail="not_found"
            )

        # Access control: the URL implies the owning entity (project/yarn).
        if kind == "projects":
            project = await db.get(Project, entity_id)
            if not project or project.owner_id != current_user.id:
                raise HTTPException(
                    status_code=status.HTTP_404_NOT_FOUND, detail="not_found"
                )
        else:
            yarn = await db.get(Yarn, entity_id)
            if not yarn or yarn.owner_id != current_user.id:
                raise HTTPException(
                    status_code=status.HTTP_404_NOT_FOUND, detail="not_found"
                )

        if shutil.which("tesseract") is None:
            raise HTTPException(
                status_code=status.HTTP_501_NOT_IMPLEMENTED,
                detail="ocr_not_available",
            )

        if file_path.stat().st_size > 25 * 1024 * 1024:
            raise HTTPException(
                status_code=status.HTTP_400_BAD_REQUEST, detail="invalid_src"
            )

        lang = payload.lang or DEFAULT_OCR_LANG
        if lang:
            # Keep this conservative: prevent shell-ish input and let tesseract validate
            # actual language packs.
            if not all(ch.isalnum() or ch in {"_", "+", "-"} for ch in lang):
                raise HTTPException(
                    status_code=status.HTTP_400_BAD_REQUEST, detail="invalid_src"
                )

        text = await extract_text_from_media_file(
            file_path=file_path,
            kind=kind,
            entity_id=entity_id,
            lang=lang,
            force=payload.force,
        )
        return OcrResponse(text=text)
    except HTTPException as exc:
        return JSONResponse(status_code=exc.status_code, content={"detail": exc.detail})
    except Exception:
        return JSONResponse(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            content={"detail": "ocr_failed"},
        )


@router.post("/crop-image")
async def crop_image(
    file: UploadFile = File(...),
    original_src: str = Form(...),
    db: AsyncSession = Depends(get_db),
    current_user: User = Depends(require_auth),
) -> JSONResponse:
    """Save a cropped image alongside the original."""
    try:
        kind, entity_id, original_path = _resolve_media_file(original_src)

        if not original_path.is_file():
            raise HTTPException(
                status_code=status.HTTP_404_NOT_FOUND, detail="not_found"
            )

        # Access control and get original image metadata
        if kind == "projects":
            project = await db.get(Project, entity_id)
            if not project or project.owner_id != current_user.id:
                raise HTTPException(
                    status_code=status.HTTP_404_NOT_FOUND, detail="not_found"
                )

            # Find original image in database to get metadata
            original_filename = original_path.name
            result = await db.execute(
                select(Image).where(
                    Image.project_id == entity_id, Image.filename == original_filename
                )
            )
            original_image = result.scalar_one_or_none()
            original_yarn_image = None
        else:
            yarn = await db.get(Yarn, entity_id)
            if not yarn or yarn.owner_id != current_user.id:
                raise HTTPException(
                    status_code=status.HTTP_404_NOT_FOUND, detail="not_found"
                )
            original_image = None

            # Find original yarn image in database
            original_filename = original_path.name
            result = await db.execute(
                select(YarnImage).where(
                    YarnImage.yarn_id == entity_id,
                    YarnImage.filename == original_filename,
                )
            )
            original_yarn_image = result.scalar_one_or_none()

        # Generate filename for cropped image
        original_name = original_path.stem
        original_ext = original_path.suffix
        crop_name = f"{original_name}_crop{original_ext}"
        crop_path = original_path.parent / crop_name

        # Ensure unique filename
        counter = 1
        while crop_path.exists():
            crop_name = f"{original_name}_crop_{counter}{original_ext}"
            crop_path = original_path.parent / crop_name
            counter += 1

        # Save the cropped file
        content = await file.read()
        crop_path.write_bytes(content)

        # Create thumbnail for the cropped image
        subdir = "yarns" if kind == "yarns" else "projects"
        await create_thumbnail(crop_path, entity_id, subdir=subdir)

        # Create database record
        new_image_id = None
        if kind == "projects" and original_image:
            cropped_image = Image(
                filename=crop_path.name,
                original_filename=f"cropped_{original_image.original_filename}",
                image_type=ImageType.PHOTO.value,
                alt_text=f"Cropped: {original_image.alt_text}"
                if original_image.alt_text
                else "Cropped image",
                is_title_image=False,
                is_stitch_sample=original_image.is_stitch_sample,
                project_id=entity_id,
                step_id=original_image.step_id,
                created_at=datetime.now(UTC),
            )
            db.add(cropped_image)
            await db.commit()
            await db.refresh(cropped_image)
            new_image_id = cropped_image.id
        elif kind == "yarns" and original_yarn_image:
            cropped_yarn_image = YarnImage(
                filename=crop_path.name,
                original_filename=f"cropped_{original_yarn_image.original_filename}",
                alt_text=f"Cropped: {original_yarn_image.alt_text}"
                if original_yarn_image.alt_text
                else "Cropped image",
                is_primary=False,
                yarn_id=entity_id,
                created_at=datetime.now(UTC),
            )
            db.add(cropped_yarn_image)
            await db.commit()
            await db.refresh(cropped_yarn_image)
            new_image_id = cropped_yarn_image.id

        # Return URLs
        filename = crop_path.name
        url_subdir = "yarns" if kind == "yarns" else "projects"

        # Get dimensions of the cropped image
        width, height = await get_image_dimensions(
            filename, entity_id, subdir=url_subdir
        )

        return JSONResponse(
            content={
                "url": get_file_url(filename, entity_id, subdir=url_subdir),
                "thumbnail_url": get_thumbnail_url(
                    filename, entity_id, subdir=url_subdir
                ),
                "filename": filename,
                "id": new_image_id,
                "kind": kind,
                "width": width,
                "height": height,
            }
        )

    except HTTPException as exc:
        return JSONResponse(status_code=exc.status_code, content={"detail": exc.detail})
    except Exception as e:
        import logging

        logging.getLogger("stricknani").error(f"Crop failed: {e}", exc_info=True)
        return JSONResponse(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            content={"detail": "crop_failed"},
        )
