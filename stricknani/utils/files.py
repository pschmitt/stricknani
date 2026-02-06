"""File management utilities."""

import hashlib
import mimetypes
import shutil
import subprocess
import uuid
from datetime import UTC, datetime
from pathlib import Path
from urllib.parse import urlparse

from fastapi import UploadFile
from PIL import Image

from stricknani.config import config


def generate_unique_filename(original_filename: str) -> str:
    """Generate a unique filename to prevent overwrites.

    Args:
        original_filename: Original filename from upload

    Returns:
        Unique filename with timestamp and UUID
    """
    # Get file extension
    ext = Path(original_filename).suffix.lower()

    # Generate unique name with timestamp and short UUID
    timestamp = datetime.now(UTC).strftime("%Y%m%d_%H%M%S")
    short_uuid = str(uuid.uuid4())[:8]

    return f"{timestamp}_{short_uuid}{ext}"


def compute_checksum(content: bytes) -> str:
    """Compute a SHA-256 checksum for the given content."""
    digest = hashlib.sha256()
    digest.update(content)
    return digest.hexdigest()


def compute_file_checksum(path: Path) -> str | None:
    """Compute a SHA-256 checksum for a file on disk."""
    if not path.exists():
        return None
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def build_import_filename(url: str | None, content_type: str | None) -> str:
    """Build a safe filename for imported images."""
    extension = ""
    if content_type:
        guessed = mimetypes.guess_extension(content_type.split(";", 1)[0].strip())
        if guessed:
            extension = guessed

    if not extension and url:
        parsed = urlparse(url)
        extension = Path(parsed.path).suffix.lower()

    if not extension:
        extension = ".jpg"

    base_name = "imported"
    if url:
        parsed = urlparse(url)
        candidate = Path(parsed.path).name
        if candidate:
            base_name = Path(candidate).stem or base_name

    return f"{base_name}{extension}"


async def save_uploaded_file(
    upload_file: UploadFile, entity_id: int, subdir: str = "projects"
) -> tuple[str, str]:
    """Save an uploaded file to media directory.

    Args:
        upload_file: FastAPI UploadFile object
        entity_id: ID of the entity directory
        subdir: Subdirectory under media root (e.g., 'projects')

    Returns:
        Tuple of (filename, original_filename)
    """
    if not upload_file.filename:
        raise ValueError("No filename provided")

    # Generate unique filename
    filename = generate_unique_filename(upload_file.filename)

    # Create directory structure: media/<subdir>/{entity_id}/
    project_dir = config.MEDIA_ROOT / subdir / str(entity_id)
    project_dir.mkdir(parents=True, exist_ok=True)

    # Save file
    file_path = project_dir / filename
    content = await upload_file.read()
    file_path.write_bytes(content)

    return filename, upload_file.filename


def save_bytes(
    content: bytes, original_filename: str, entity_id: int, subdir: str = "projects"
) -> tuple[str, str]:
    """Save raw bytes to the media directory with a generated filename."""
    if not original_filename:
        raise ValueError("No filename provided")

    filename = generate_unique_filename(original_filename)

    target_dir = config.MEDIA_ROOT / subdir / str(entity_id)
    target_dir.mkdir(parents=True, exist_ok=True)

    file_path = target_dir / filename
    file_path.write_bytes(content)

    return filename, original_filename


async def create_thumbnail(
    source_path: Path,
    entity_id: int,
    max_size: tuple[int, int] = (300, 300),
    subdir: str = "projects",
) -> str:
    """Create a thumbnail from an image.

    Args:
        source_path: Path to source image
        entity_id: ID of the entity directory
        max_size: Maximum thumbnail size (width, height)
        subdir: Subdirectory under media/thumbnails root

    Returns:
        Filename of the thumbnail
    """
    # Open and resize image
    with Image.open(source_path) as img:
        # Convert RGBA to RGB if necessary
        if img.mode in ("RGBA", "LA", "P"):
            background = Image.new("RGB", img.size, (255, 255, 255))
            if img.mode == "P":
                img = img.convert("RGBA")
            background.paste(img, mask=img.split()[-1] if img.mode == "RGBA" else None)
            img = background

        # Resize maintaining aspect ratio
        img.thumbnail(max_size, Image.Resampling.LANCZOS)

        # Generate thumbnail filename
        thumbnail_name = f"thumb_{source_path.stem}.jpg"

        # Create directory structure
        thumb_dir = config.MEDIA_ROOT / "thumbnails" / subdir / str(entity_id)
        thumb_dir.mkdir(parents=True, exist_ok=True)

        # Save thumbnail
        thumb_path = thumb_dir / thumbnail_name
        img.save(thumb_path, "JPEG", quality=85, optimize=True)

        return thumbnail_name


def create_pdf_thumbnail(
    source_path: Path,
    entity_id: int,
    subdir: str = "projects",
    width: int = 300,
) -> str | None:
    """Create a thumbnail for the first page of a PDF, if `pdftoppm` is available.

    Returns the thumbnail filename (e.g. `thumb_<stem>.jpg`) or None if thumbnail
    generation is unavailable/failed.
    """
    pdftoppm = shutil.which("pdftoppm")
    if not pdftoppm:
        return None

    thumb_dir = config.MEDIA_ROOT / "thumbnails" / subdir / str(entity_id)
    thumb_dir.mkdir(parents=True, exist_ok=True)

    thumb_basename = f"thumb_{source_path.stem}"
    out_prefix = thumb_dir / thumb_basename
    out_path = thumb_dir / f"{thumb_basename}.jpg"

    try:
        subprocess.run(
            [
                pdftoppm,
                "-f",
                "1",
                "-l",
                "1",
                "-singlefile",
                "-jpeg",
                "-scale-to",
                str(width),
                str(source_path),
                str(out_prefix),
            ],
            check=True,
            capture_output=True,
            text=True,
        )
    except Exception:
        return None

    if out_path.exists():
        return out_path.name
    return None


def delete_file(filename: str, entity_id: int, subdir: str = "projects") -> None:
    """Delete a file from media directory.

    Args:
        filename: Filename to delete
        entity_id: ID of the entity directory
        subdir: Subdirectory under media root
    """
    file_path = config.MEDIA_ROOT / subdir / str(entity_id) / filename
    if file_path.exists():
        file_path.unlink()

    # Also try to delete thumbnail
    thumb_name = f"thumb_{Path(filename).stem}.jpg"
    thumb_path = config.MEDIA_ROOT / "thumbnails" / subdir / str(entity_id) / thumb_name
    if thumb_path.exists():
        thumb_path.unlink()


def get_file_url(filename: str, entity_id: int, subdir: str = "projects") -> str:
    """Get the URL for a media file.

    Args:
        filename: Filename
        entity_id: ID of the entity directory
        subdir: Subdirectory under media root

    Returns:
        URL path to the file
    """
    return f"/media/{subdir}/{entity_id}/{filename}"


def get_thumbnail_url(filename: str, entity_id: int, subdir: str = "projects") -> str:
    """Get the URL for a thumbnail.

    Args:
        filename: Original filename
        entity_id: ID of the entity directory

    Returns:
        URL path to the thumbnail
    """
    thumbnail_name = f"thumb_{Path(filename).stem}.jpg"
    return f"/media/thumbnails/{subdir}/{entity_id}/{thumbnail_name}"
