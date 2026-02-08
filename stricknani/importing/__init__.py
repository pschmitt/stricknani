"""Pattern importing package.

Prefer importing from this package instead of :mod:`stricknani.utils.importer`.
The old module remains as a compatibility shim.

New Import Pipeline Architecture:

The import system has been refactored into a pipeline with three stages:
1. **Source** - Fetches raw content (URL, file, etc.)
2. **Extractor** - Parses raw content into structured data
3. **Target** - Persists structured data to the database

Example:
    from stricknani.importing import ImportPipeline, URLSource, HTMLExtractor
    from stricknani.importing.targets.projects import ProjectTarget

    pipeline = ImportPipeline(db, owner_id=user.id)
    result = await pipeline.run(
        source=URLSource("https://example.com/pattern"),
        extractors=[HTMLExtractor()],
        target=ProjectTarget(db, user.id),
    )
"""

from __future__ import annotations

from stricknani.importing.extractors import (
    ContentExtractor,
    ExtractorError,
    FallbackExtractor,
)
from stricknani.importing.extractors.ai import AIExtractor
from stricknani.importing.extractors.html import HTMLExtractor
from stricknani.importing.extractors.pdf import PDFExtractor

# Phase 1: Image handling consolidation
from stricknani.importing.images import (
    IMPORT_ALLOWED_IMAGE_EXTENSIONS,
    IMPORT_ALLOWED_IMAGE_TYPES,
    IMPORT_IMAGE_HEADERS,
    IMPORT_IMAGE_MAX_BYTES,
    IMPORT_IMAGE_MAX_COUNT,
    IMPORT_IMAGE_MIN_DIMENSION,
    IMPORT_IMAGE_SSIM_THRESHOLD,
    IMPORT_IMAGE_TIMEOUT,
    DownloadedImage,
    ImageDownloader,
    ImageDownloadResult,
    ImageInspectionResult,
    async_inspect_image_content,
    inspect_image_content,
    is_allowed_import_image,
    is_duplicate_by_checksum,
    is_duplicate_by_similarity,
    is_too_small,
    is_valid_import_url,
    should_skip_as_thumbnail,
    validate_import_url,
)

# Phase 2: Pipeline architecture
from stricknani.importing.models import (
    ContentType,
    ExtractedData,
    ExtractedStep,
    ExtractedYarn,
    ImportResult,
    ImportSourceType,
    RawContent,
)
from stricknani.importing.pipeline import ImportPipeline
from stricknani.importing.sources import ImportSource, ImportSourceError
from stricknani.importing.sources.file import FileSource, MultiFileSource
from stricknani.importing.sources.url import URLSource
from stricknani.importing.targets import ImportTarget, TargetError

# Legacy imports (for backward compatibility)
from .importer import (
    GarnstudioPatternImporter,
    PatternImporter,
    filter_import_image_urls,
    is_garnstudio_url,
    trim_import_strings,
)

__all__ = [
    # Legacy importers
    "GarnstudioPatternImporter",
    "PatternImporter",
    # Legacy constants
    "IMPORT_ALLOWED_IMAGE_EXTENSIONS",
    "IMPORT_ALLOWED_IMAGE_TYPES",
    "IMPORT_IMAGE_HEADERS",
    "IMPORT_IMAGE_MAX_BYTES",
    "IMPORT_IMAGE_MAX_COUNT",
    "IMPORT_IMAGE_MIN_DIMENSION",
    "IMPORT_IMAGE_SSIM_THRESHOLD",
    "IMPORT_IMAGE_TIMEOUT",
    # Legacy functions
    "filter_import_image_urls",
    "is_allowed_import_image",
    "is_garnstudio_url",
    "is_valid_import_url",
    "trim_import_strings",
    # Image handling (Phase 1)
    "DownloadedImage",
    "ImageDownloadResult",
    "ImageDownloader",
    "ImageInspectionResult",
    "async_inspect_image_content",
    "inspect_image_content",
    "is_duplicate_by_checksum",
    "is_duplicate_by_similarity",
    "is_too_small",
    "should_skip_as_thumbnail",
    "validate_import_url",
    # Pipeline models (Phase 2)
    "ContentType",
    "ExtractedData",
    "ExtractedStep",
    "ExtractedYarn",
    "ImportResult",
    "ImportSourceType",
    "RawContent",
    # Pipeline core
    "ImportPipeline",
    # Sources
    "ImportSource",
    "ImportSourceError",
    "URLSource",
    "FileSource",
    "MultiFileSource",
    # Extractors
    "ContentExtractor",
    "ExtractorError",
    "FallbackExtractor",
    "HTMLExtractor",
    "AIExtractor",
    "PDFExtractor",
    # Targets
    "ImportTarget",
    "TargetError",
]
