"""Import pipeline orchestrator.

Coordinates the flow: Source → Extractor → Target
"""

from __future__ import annotations

import logging
from typing import TYPE_CHECKING, Any

if TYPE_CHECKING:
    from sqlalchemy.ext.asyncio import AsyncSession

    from stricknani.importing.extractors import ContentExtractor
    from stricknani.importing.models import ExtractedData, ImportResult
    from stricknani.importing.sources import ImportSource
    from stricknani.importing.targets import ImportTarget

logger = logging.getLogger("stricknani.imports")


class ImportPipeline:
    """Orchestrates the import process from source to target.

    The pipeline coordinates:
    1. Fetch raw content from a Source
    2. Extract structured data using an Extractor
    3. Persist the entity using a Target

    Example:
        pipeline = ImportPipeline(db, owner_id=user.id)
        result = await pipeline.run(
            source=URLSource("https://example.com/pattern"),
            extractors=[HTMLExtractor(), AIFallbackExtractor()],
            target=ProjectTarget(db, user.id),
        )
    """

    def __init__(
        self,
        db: AsyncSession,
        owner_id: int,
        *,
        trace: Any | None = None,
    ) -> None:
        """Initialize the pipeline.

        Args:
            db: Database session
            owner_id: ID of the user who will own imported entities
            trace: Optional import trace for debugging
        """
        self.db = db
        self.owner_id = owner_id
        self.trace = trace

    async def run(
        self,
        *,
        source: ImportSource,
        extractors: list[ContentExtractor],
        target: ImportTarget,
        hints: dict[str, Any] | None = None,
    ) -> ImportResult:
        """Run the import pipeline.

        Args:
            source: Source to fetch content from
            extractors: List of extractors to try (in order)
            target: Target to persist the extracted data
            hints: Optional hints for extraction

        Returns:
            ImportResult with success status and entity info
        """
        from stricknani.importing.models import ImportResult

        if self.trace:
            self.trace.add_event(
                "pipeline_start",
                {
                    "source_type": source.source_type.name,
                    "extractors": [e.name for e in extractors],
                    "target_type": target.target_type,
                },
            )

        # Step 1: Fetch from source
        try:
            if not source.can_fetch():
                error_msg = "Source is not properly configured"
                logger.error(error_msg)
                if self.trace:
                    self.trace.record_error("source", Exception(error_msg))
                return ImportResult(
                    success=False,
                    errors=[error_msg],
                )

            raw_content = await source.fetch()
            logger.info(
                "Fetched %s content from %s",
                raw_content.content_type.name,
                source.source_id or "unknown",
            )

            if self.trace:
                self.trace.add_event(
                    "source_fetched",
                    {
                        "content_type": raw_content.content_type.name,
                        "source_url": raw_content.source_url,
                        "size": len(raw_content.content)
                        if isinstance(raw_content.content, (str, bytes))
                        else 0,
                    },
                )

        except Exception as exc:
            error_msg = f"Source fetch failed: {exc}"
            logger.error(error_msg)
            if self.trace:
                self.trace.record_error("source", exc)
            return ImportResult(success=False, errors=[error_msg])

        # Step 2: Extract data
        extracted_data: ExtractedData | None = None
        extraction_errors: list[str] = []

        for extractor in extractors:
            try:
                if not extractor.can_extract(raw_content):
                    logger.debug(
                        "Extractor %s cannot handle this content", extractor.name
                    )
                    continue

                logger.info("Extracting with %s", extractor.name)
                extracted_data = await extractor.extract(
                    raw_content,
                    hints=hints,
                )

                if self.trace:
                    self.trace.add_event(
                        "extracted",
                        {
                            "extractor": extractor.name,
                            "has_name": extracted_data.name is not None,
                            "has_description": extracted_data.description is not None,
                            "steps_count": len(extracted_data.steps),
                            "images_count": len(extracted_data.image_urls),
                        },
                    )

                logger.info(
                    "Extracted name=%s steps=%s images=%s",
                    extracted_data.name,
                    len(extracted_data.steps),
                    len(extracted_data.image_urls),
                )
                break

            except Exception as exc:
                error_msg = f"Extractor {extractor.name} failed: {exc}"
                logger.warning(error_msg)
                extraction_errors.append(error_msg)
                if self.trace:
                    self.trace.record_error(f"extractor_{extractor.name}", exc)
                continue

        if extracted_data is None:
            error_msg = "No extractor could process the content"
            logger.error(error_msg)
            if self.trace:
                self.trace.record_error("extraction", Exception(error_msg))
            return ImportResult(
                success=False,
                errors=[error_msg] + extraction_errors,
            )

        # Step 3: Create target entity
        try:
            result = await target.create(extracted_data)

            if self.trace:
                self.trace.add_event(
                    "target_created",
                    {
                        "success": result.success,
                        "entity_id": result.entity_id,
                        "entity_type": result.entity_type,
                        "imported_images": result.imported_images,
                    },
                )

            return result

        except Exception as exc:
            error_msg = f"Target creation failed: {exc}"
            logger.error(error_msg)
            if self.trace:
                self.trace.record_error("target", exc)
            return ImportResult(
                success=False,
                errors=[error_msg],
            )

    async def run_simple_url_import(
        self,
        url: str,
        *,
        target: ImportTarget,
        use_ai: bool = False,
    ) -> ImportResult:
        """Simple import from a URL using default extractors.

        Convenience method for the common case of importing from a URL.

        Args:
            url: The URL to import from
            target: Target to persist to
            use_ai: Whether to use AI extraction as fallback

        Returns:
            ImportResult
        """
        from stricknani.importing.extractors import FallbackExtractor
        from stricknani.importing.extractors.html import HTMLExtractor
        from stricknani.importing.sources.url import URLSource

        source = URLSource(url)
        extractors: list[ContentExtractor] = [HTMLExtractor(url=url)]

        if use_ai:
            # Add AI extractor for enhanced extraction
            try:
                from stricknani.importing.extractors.ai import AIExtractor

                extractors.append(AIExtractor(url=url))
            except ImportError:
                logger.debug("AI extractor not available (openai package missing)")

        extractors.append(FallbackExtractor())

        return await self.run(
            source=source,
            extractors=extractors,
            target=target,
        )


__all__ = ["ImportPipeline"]
