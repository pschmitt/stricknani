"""HTML content extractor using BeautifulSoup.

Wraps the existing PatternImporter to integrate with the new pipeline.
"""

from __future__ import annotations

import logging
from typing import TYPE_CHECKING, Any

from stricknani.importing.extractors import ContentExtractor, ExtractorError
from stricknani.importing.models import (
    ContentType,
    ExtractedData,
    ExtractedStep,
    ExtractedYarn,
    RawContent,
)

if TYPE_CHECKING:
    from stricknani.importing.importer import PatternImporter

logger = logging.getLogger("stricknani.imports")


class HTMLExtractor(ContentExtractor):
    """Extract pattern data from HTML content using BeautifulSoup.

    This extractor wraps the existing PatternImporter to provide
    compatibility with the new import pipeline architecture.
    """

    def __init__(self, url: str | None = None, timeout: int = 10) -> None:
        """Initialize the HTML extractor.

        Args:
            url: The source URL (for metadata and relative URL resolution)
            timeout: HTTP timeout (for PatternImporter compatibility)
        """
        self.url = url
        self.timeout = timeout

    @property
    def name(self) -> str:
        """Return the name of this extractor."""
        return "html"

    def can_extract(self, content: RawContent) -> bool:
        """Check if content is HTML."""
        return content.content_type == ContentType.HTML

    async def extract(
        self,
        content: RawContent,
        *,
        hints: dict[str, Any] | None = None,
    ) -> ExtractedData:
        """Extract data from HTML content.

        Args:
            content: Raw HTML content
            hints: Optional hints (passed to PatternImporter if used)

        Returns:
            Extracted structured data

        Raises:
            ExtractorError: If extraction fails
        """
        from stricknani.importing.importer import PatternImporter

        html_text = content.get_text()
        url = content.source_url or self.url or ""

        if not url:
            raise ExtractorError(
                "HTML extraction requires a source URL",
                extractor_name=self.name,
            )

        try:
            # Use the existing PatternImporter but skip the fetch
            # by using the HTML we already have
            importer = PatternImporter(url, timeout=self.timeout)

            # Parse the HTML directly using the importer's methods
            from bs4 import BeautifulSoup

            soup = BeautifulSoup(html_text, "html.parser")
            importer._last_soup = soup
            from stricknani.importing.importer import is_garnstudio_url

            importer.is_garnstudio = is_garnstudio_url(url)

            # Extract all the data
            data = self._extract_from_soup(importer, soup, url)

            return data

        except Exception as exc:
            raise ExtractorError(
                f"HTML extraction failed: {exc}",
                extractor_name=self.name,
            ) from exc

    def _extract_from_soup(
        self,
        importer: PatternImporter,
        soup: Any,
        url: str,
    ) -> ExtractedData:
        """Extract data from BeautifulSoup object."""

        # Get basic data using importer methods
        yarn_text = importer._extract_yarn(soup)
        yarn_details = None

        if importer.is_garnstudio and yarn_text:
            yarn_links = importer._extract_garnstudio_yarn_links(soup)
            if yarn_links:
                yarn_details = [
                    ExtractedYarn(
                        name=info.get("name"),
                        brand="Garnstudio",
                        link=info.get("link"),
                        image_url=info.get("image_url"),
                    )
                    for info in yarn_links.values()
                ]
            else:
                yarn_lines = yarn_text.split("\n")
                yarn_details = []
                for line in yarn_lines:
                    if not line.strip():
                        continue
                    detail = importer._parse_garnstudio_yarn_string(line)
                    yarn_details.append(
                        ExtractedYarn(
                            name=detail.get("name"),
                            brand=detail.get("brand") or "Garnstudio",
                            colorway=detail.get("colorway"),
                            weight=detail.get("weight"),
                        )
                    )

        # Clean up Garnstudio soup
        if importer.is_garnstudio:
            self._clean_garnstudio_soup(soup)

        # Extract steps and images
        steps_data = importer._extract_steps(soup)
        images = importer._extract_images(soup)

        # Deduplicate images
        step_images = set()
        for step in steps_data:
            for img in step.get("images", []):
                step_images.add(img)

        if step_images:
            images = [img for img in images if img not in step_images]

        # Build ExtractedSteps
        steps = [
            ExtractedStep(
                step_number=step.get("step_number", idx + 1),
                title=step.get("title"),
                description=step.get("description"),
                images=step.get("images", []),
            )
            for idx, step in enumerate(steps_data)
        ]

        # Extract description
        description = importer._extract_description(soup)
        if importer.is_garnstudio:
            description = self._extract_garnstudio_description(
                importer, soup, description
            )

        # Build final data
        data = ExtractedData(
            name=importer._extract_title(soup),
            description=description,
            yarn=yarn_text,
            yarns=yarn_details or [],
            needles=importer._extract_needles(soup),
            stitch_sample=importer._extract_stitch_sample(soup),
            brand=importer._extract_brand(soup),
            fiber_content=importer._extract_fiber_content(soup),
            colorway=importer._extract_colorway(soup),
            weight_category=importer._extract_weight_category(soup),
            steps=steps,
            image_urls=images[:10],  # Limit to 10 images
            link=url,
        )

        return data

    def _clean_garnstudio_soup(self, soup: Any) -> None:
        """Remove UI noise from Garnstudio pages."""
        # This mirrors the logic in PatternImporter
        noise_selectors = [
            ".pcalc",
            ".pcalc-wrapper",
            ".btn",
            ".pattern-print",
            ".dropdown",
            ".lessons-wrapper",
            ".mobile-only",
            ".updates",
            ".pattern_copyright",
            ".pattern-share-new",
            ".pattern-ad",
            ".pattern-prices",
            ".selected-filters",
            ".lesson-list-pattern",
            ".video-list-pattern",
            ".nav-pattern",
            ".m-menu",
            ".dropdown-menu",
            "#menu",
            "nav",
            ".modal",
            ".table-products",
            ".sn",
            "#related-patterns",
            "#yarn-patterns",
            ".feature-cats",
        ]
        for selector in noise_selectors:
            for noise in soup.select(selector):
                noise.decompose()

    def _extract_garnstudio_description(
        self,
        importer: Any,
        soup: Any,
        base_description: str | None,
    ) -> str | None:
        """Extract Garnstudio-specific description enhancements."""
        description_parts = []

        # Add subtitle from title
        title_tag = soup.find("title")
        if title_tag:
            title_text = title_tag.get_text()
            if " - " in title_text:
                subtitle = title_text.split(" - ", 1)[1].strip()
                description_parts.append(subtitle)

        # Add technical specs
        specs = importer._extract_garnstudio_yarn_specs(soup)
        if specs:
            description_parts.append(specs)

        # Add base description if present
        if base_description:
            description_parts.append(base_description)

        # Add notes
        notes = importer._extract_garnstudio_notes(soup)
        if notes:
            description_parts.append(notes)

        if description_parts:
            return "\n\n".join(description_parts)

        return base_description


__all__ = ["HTMLExtractor"]
