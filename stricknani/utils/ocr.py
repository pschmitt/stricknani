"""OCR helpers and cache management.

The UI calls `/utils/ocr` with a `/media/{kind}/{id}/{filename}` src. We store OCR
results on disk next to media so extraction can be reused without recomputing.
"""

from __future__ import annotations

import json
import shutil
import subprocess
import tempfile
from functools import partial
from pathlib import Path

import anyio
from PIL import Image

from stricknani.config import config

DEFAULT_OCR_LANG = "deu+eng"
MAX_OCR_BYTES = 25 * 1024 * 1024

# Avoid starting too many tesseract processes in parallel.
_ocr_semaphore = anyio.Semaphore(2)


def is_ocr_available() -> bool:
    return shutil.which("tesseract") is not None


def build_ocr_cache_paths(
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


def _extract_text_sync(
    *,
    file_path: Path,
    kind: str,
    entity_id: int,
    lang: str,
    force: bool,
) -> str:
    if not file_path.is_file():
        raise FileNotFoundError(file_path)

    if not is_ocr_available():
        raise RuntimeError("ocr_not_available")

    if file_path.stat().st_size > MAX_OCR_BYTES:
        raise ValueError("file_too_large")

    tesseract_args: list[str] = ["tesseract"]
    if lang:
        tesseract_args.extend(["-l", lang])

    source_mtime_ns = file_path.stat().st_mtime_ns
    cache_text_path, cache_meta_path = build_ocr_cache_paths(
        kind=kind,
        entity_id=entity_id,
        filename=file_path.name,
        lang=lang,
    )

    if not force and cache_text_path.is_file() and cache_meta_path.is_file():
        try:
            meta = json.loads(cache_meta_path.read_text(encoding="utf-8"))
            cached_mtime = int(meta.get("source_mtime_ns", 0))
        except Exception:
            cached_mtime = 0

        if cached_mtime == source_mtime_ns:
            return cache_text_path.read_text(encoding="utf-8")

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
        raise RuntimeError("ocr_failed")

    text = proc.stdout or ""

    try:
        cache_text_path.parent.mkdir(parents=True, exist_ok=True)
        cache_text_path.write_text(text, encoding="utf-8")
        cache_meta_path.write_text(
            json.dumps({"source_mtime_ns": source_mtime_ns}),
            encoding="utf-8",
        )
    except Exception:
        # Best-effort cache: extraction is still useful without persistence.
        pass

    return text


async def extract_text_from_media_file(
    *,
    file_path: Path,
    kind: str,
    entity_id: int,
    lang: str = DEFAULT_OCR_LANG,
    force: bool = False,
) -> str:
    async with _ocr_semaphore:
        fn = partial(
            _extract_text_sync,
            file_path=file_path,
            kind=kind,
            entity_id=entity_id,
            lang=lang,
            force=force,
        )
        return await anyio.to_thread.run_sync(fn)


async def precompute_ocr_for_media_file(
    *,
    file_path: Path,
    kind: str,
    entity_id: int,
    lang: str = DEFAULT_OCR_LANG,
) -> None:
    try:
        await extract_text_from_media_file(
            file_path=file_path,
            kind=kind,
            entity_id=entity_id,
            lang=lang,
            force=False,
        )
    except Exception:
        # Silent best-effort; interactive OCR will still work (or fail) later.
        return
