"""Utility routes (OCR, previews, etc.)."""

from __future__ import annotations

import shutil
from pathlib import Path
from urllib.parse import urlparse

from fastapi import APIRouter, Depends, File, Form, HTTPException, UploadFile, status
from fastapi.responses import JSONResponse
from pydantic import BaseModel
from sqlalchemy.ext.asyncio import AsyncSession

from stricknani.config import config
from stricknani.database import get_db
from stricknani.models import Project, User, Yarn
from stricknani.routes.auth import require_auth
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

        # Access control
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
        await create_thumbnail(crop_path, entity_id)

        # Return URLs
        filename = crop_path.name
        return JSONResponse(
            content={
                "url": get_file_url(filename, entity_id),
                "thumbnail_url": get_thumbnail_url(filename, entity_id),
                "filename": filename,
            }
        )

    except HTTPException as exc:
        return JSONResponse(status_code=exc.status_code, content={"detail": exc.detail})
    except Exception:
        return JSONResponse(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            content={"detail": "crop_failed"},
        )
