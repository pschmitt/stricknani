"""Utility routes (OCR, previews, etc.)."""

from __future__ import annotations

import shutil
import subprocess
import tempfile
from pathlib import Path
from urllib.parse import urlparse

from fastapi import APIRouter, Depends, HTTPException, status
from fastapi.responses import JSONResponse
from PIL import Image
from pydantic import BaseModel
from sqlalchemy.ext.asyncio import AsyncSession

from stricknani.config import config
from stricknani.database import get_db
from stricknani.models import Project, User, Yarn
from stricknani.routes.auth import require_auth

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


def _build_ocr_cache_paths(
    *,
    kind: str,
    entity_id: int,
    filename: str,
    lang: str,
) -> tuple[Path, Path]:
    base = config.MEDIA_ROOT / "ocr-cache" / kind / str(entity_id)
    safe_lang = "".join(ch for ch in lang if ch.isalnum() or ch in {"_", "+", "-"})
    text_path = base / f"{filename}.{safe_lang}.txt"
    meta_path = base / f"{filename}.{safe_lang}.json"
    return text_path, meta_path


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

        tesseract_args: list[str] = ["tesseract"]
        lang = payload.lang or "deu+eng"
        if lang:
            # Keep this conservative: prevent shell-ish input and let tesseract validate
            # actual language packs.
            if not all(ch.isalnum() or ch in {"_", "+", "-"} for ch in lang):
                raise HTTPException(
                    status_code=status.HTTP_400_BAD_REQUEST, detail="invalid_src"
                )
            tesseract_args.extend(["-l", lang])

        source_mtime_ns = file_path.stat().st_mtime_ns
        cache_text_path, cache_meta_path = _build_ocr_cache_paths(
            kind=kind,
            entity_id=entity_id,
            filename=file_path.name,
            lang=lang,
        )
        if (
            not payload.force
            and cache_text_path.is_file()
            and cache_meta_path.is_file()
        ):
            try:
                meta = cache_meta_path.read_text(encoding="utf-8")
                cached_mtime = int(
                    __import__("json").loads(meta).get("source_mtime_ns", 0)
                )
            except Exception:
                cached_mtime = 0

            if cached_mtime == source_mtime_ns:
                try:
                    return OcrResponse(text=cache_text_path.read_text(encoding="utf-8"))
                except Exception:
                    pass

        with Image.open(file_path) as img:
            img = img.convert("RGB")
            with tempfile.NamedTemporaryFile(suffix=".png", delete=True) as tmp:
                img.save(tmp.name, format="PNG")
                proc = subprocess.run(
                    [*tesseract_args, tmp.name, "stdout"],
                    capture_output=True,
                    text=True,
                    timeout=30,
                    check=False,
                )

        if proc.returncode != 0:
            raise HTTPException(
                status_code=status.HTTP_500_INTERNAL_SERVER_ERROR, detail="ocr_failed"
            )

        text = proc.stdout or ""
        try:
            cache_text_path.parent.mkdir(parents=True, exist_ok=True)
            cache_text_path.write_text(text, encoding="utf-8")
            cache_meta_path.write_text(
                __import__("json").dumps({"source_mtime_ns": source_mtime_ns}),
                encoding="utf-8",
            )
        except Exception:
            pass

        return OcrResponse(text=text)
    except HTTPException as exc:
        return JSONResponse(status_code=exc.status_code, content={"detail": exc.detail})
    except Exception:
        return JSONResponse(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            content={"detail": "ocr_failed"},
        )
