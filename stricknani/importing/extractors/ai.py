"""AI-powered content extractor.

Uses OpenAI (or other AI services) to extract pattern/project data
from images, PDFs, and text.
"""

from __future__ import annotations

import base64
import json
import logging
import os
from io import BytesIO
from typing import TYPE_CHECKING, Any

from PIL import Image as PilImage

from stricknani.importing.extractors import ContentExtractor, ExtractorError
from stricknani.importing.models import (
    ContentType,
    ExtractedData,
    ExtractedStep,
    RawContent,
)

if TYPE_CHECKING:
    pass

logger = logging.getLogger("stricknani.imports")

# Check if OpenAI is available
try:
    from openai import AsyncOpenAI

    OPENAI_AVAILABLE = True
except ImportError:
    OPENAI_AVAILABLE = False


class AIExtractor(ContentExtractor):
    """Extract pattern data using AI (OpenAI GPT-4 Vision).

    This extractor can analyze images, PDFs, and text to extract
    structured knitting pattern information.
    """

    def __init__(
        self,
        *,
        url: str | None = None,
        api_key: str | None = None,
        model: str = "gpt-4o-mini",
        max_tokens: int = 4000,
        temperature: float = 0.1,
    ) -> None:
        """Initialize the AI extractor.

        Args:
            url: Optional source URL for context
            api_key: OpenAI API key (defaults to OPENAI_API_KEY env var)
            model: Model to use for extraction
            max_tokens: Maximum tokens in response
            temperature: Temperature for generation
        """
        self.url = url
        self.api_key = api_key or os.getenv("OPENAI_API_KEY")
        self.model = model
        self.max_tokens = max_tokens
        self.temperature = temperature

    @property
    def name(self) -> str:
        """Return the name of this extractor."""
        return "ai"

    def can_extract(self, content: RawContent) -> bool:
        """Check if AI extraction is available and content is supported.

        Supports images, text, HTML, and PDFs.
        """
        if not OPENAI_AVAILABLE or not self.api_key:
            return False

        return content.content_type in (
            ContentType.IMAGE,
            ContentType.TEXT,
            ContentType.HTML,
            ContentType.PDF,
        )

    async def extract(
        self,
        content: RawContent,
        *,
        hints: dict[str, Any] | None = None,
    ) -> ExtractedData:
        """Extract pattern data using AI.

        Args:
            content: Raw content to analyze
            hints: Optional hints from previous extractors

        Returns:
            Extracted structured data

        Raises:
            ExtractorError: If AI extraction fails
        """
        if not OPENAI_AVAILABLE:
            raise ExtractorError(
                "OpenAI package not installed",
                extractor_name=self.name,
            )

        if not self.api_key:
            raise ExtractorError(
                "OpenAI API key not configured",
                extractor_name=self.name,
            )

        if content.content_type == ContentType.IMAGE:
            return await self._extract_from_image(content, hints)
        elif content.content_type in (ContentType.TEXT, ContentType.HTML):
            return await self._extract_from_text(content, hints)
        elif content.content_type == ContentType.PDF:
            return await self._extract_from_pdf(content, hints)
        else:
            raise ExtractorError(
                f"Unsupported content type: {content.content_type}",
                extractor_name=self.name,
            )

    async def _extract_from_image(
        self,
        content: RawContent,
        hints: dict[str, Any] | None,
    ) -> ExtractedData:
        """Extract data from an image using vision API."""
        client = AsyncOpenAI(api_key=self.api_key)

        # Prepare image
        image_bytes = (
            content.content
            if isinstance(content.content, bytes)
            else content.content.encode()
        )

        # Resize if needed to stay within token limits
        processed_image = await self._prepare_image(image_bytes)

        # Convert to base64
        base64_image = base64.b64encode(processed_image).decode("utf-8")

        # Build prompt
        system_prompt = self._build_system_prompt()
        user_prompt = self._build_image_prompt(hints)

        try:
            response = await client.chat.completions.create(
                model=self.model,
                messages=[
                    {"role": "system", "content": system_prompt},
                    {
                        "role": "user",
                        "content": [
                            {"type": "text", "text": user_prompt},
                            {
                                "type": "image_url",
                                "image_url": {
                                    "url": f"data:image/jpeg;base64,{base64_image}",
                                },
                            },
                        ],
                    },
                ],
                max_tokens=self.max_tokens,
                temperature=self.temperature,
                response_format={"type": "json_object"},
            )

            raw_content_str = response.choices[0].message.content or "{}"
            return self._parse_ai_response(raw_content_str)

        except Exception as exc:
            raise ExtractorError(
                f"AI vision extraction failed: {exc}",
                extractor_name=self.name,
            ) from exc

    async def _extract_from_text(
        self,
        content: RawContent,
        hints: dict[str, Any] | None,
    ) -> ExtractedData:
        """Extract data from text using GPT."""
        client = AsyncOpenAI(api_key=self.api_key)

        text = content.get_text()

        # Limit text length
        if len(text) > 12000:
            text = text[:12000] + "\n[Content truncated...]"

        system_prompt = self._build_system_prompt()
        user_prompt = self._build_text_prompt(text, hints)

        try:
            response = await client.chat.completions.create(
                model=self.model,
                messages=[
                    {"role": "system", "content": system_prompt},
                    {"role": "user", "content": user_prompt},
                ],
                max_tokens=self.max_tokens,
                temperature=self.temperature,
                response_format={"type": "json_object"},
            )

            raw_content_str = response.choices[0].message.content or "{}"
            return self._parse_ai_response(raw_content_str)

        except Exception as exc:
            raise ExtractorError(
                f"AI text extraction failed: {exc}",
                extractor_name=self.name,
            ) from exc

    async def _extract_from_pdf(
        self,
        content: RawContent,
        hints: dict[str, Any] | None,
    ) -> ExtractedData:
        """Extract data from PDF.

        OpenAI vision endpoints only accept image formats (png/jpeg/gif/webp),
        so we must not send raw PDFs as "images".

        Current strategy:
        - Extract text from the PDF using `PDFExtractor` (pypdf/PyMuPDF).
        - Feed that text into the text extraction flow.
        """
        from stricknani.importing.extractors.pdf import PDFExtractor

        pdf_extractor = PDFExtractor(extract_images=False)
        if not pdf_extractor.can_extract(content):
            raise ExtractorError(
                "PDF extraction is not available (install pypdf or PyMuPDF)",
                extractor_name=self.name,
            )

        try:
            pdf_data = await pdf_extractor.extract(content, hints=hints)
        except ExtractorError as exc:
            raise ExtractorError(
                f"AI PDF preprocessing failed: {exc}",
                extractor_name=self.name,
            ) from exc

        full_text = ""
        if pdf_data.extras:
            full_text = str(pdf_data.extras.get("full_text") or "")
        if not full_text.strip():
            full_text = str(pdf_data.description or "")

        if not full_text.strip():
            raise ExtractorError(
                "PDF contains no extractable text. If this is a scanned PDF, "
                "upload images instead (or install PyMuPDF to enable "
                "image-based extraction).",
                extractor_name=self.name,
            )

        # Re-route through the text pipeline.
        text_content = RawContent(
            content=full_text,
            content_type=ContentType.TEXT,
            metadata={
                **(content.metadata or {}),
                "source_content_type": "application/pdf",
            },
        )
        return await self._extract_from_text(text_content, hints)

    async def _prepare_image(self, image_bytes: bytes, max_size: int = 1024) -> bytes:
        """Resize image if needed to reduce token usage."""
        try:
            with PilImage.open(BytesIO(image_bytes)) as img:
                # Convert to RGB if necessary
                if img.mode in ("RGBA", "P"):
                    img = img.convert("RGB")

                # Resize if too large
                if max(img.size) > max_size:
                    ratio = max_size / max(img.size)
                    new_size = (int(img.size[0] * ratio), int(img.size[1] * ratio))
                    img = img.resize(new_size, PilImage.Resampling.LANCZOS)

                # Save to bytes
                output = BytesIO()
                img.save(output, format="JPEG", quality=85)
                return output.getvalue()

        except Exception as exc:
            logger.warning("Failed to process image: %s", exc)
            return image_bytes

    def _build_system_prompt(self) -> str:
        """Build the system prompt for AI extraction."""
        return (
            "You are an expert at analyzing knitting patterns and projects. "
            "Extract as much information as possible from the provided content "
            "and return it as JSON.\n\n"
            "Fields to extract:\n"
            "- name: Project/pattern name\n"
            "- description: Brief description or summary\n"
            "- category: Type (Pullover, Schal, Mütze, etc.)\n"
            "- yarn: General yarn information (string)\n"
            "- brand: Yarn brand if visible\n"
            "- colorway: Color name/number\n"
            "- weight_category: Yarn weight (Lace, Fingering, DK, Worsted, Aran, "
            "Bulky)\n"
            "- fiber_content: Material composition\n"
            "- weight_grams: Weight of a single ball in grams (integer)\n"
            "- length_meters: Length of a single ball in meters (integer)\n"
            "- needles: Needle size and type\n"
            "- stitch_sample: Gauge information\n"
            "- steps: Array of instruction steps with step_number, title, "
            "description\n"
            "\nReturn valid JSON only. Use null for unknown fields."
        )

    def _build_image_prompt(self, hints: dict[str, Any] | None) -> str:
        """Build prompt for image analysis."""
        prompt = (
            "Analyze this knitting pattern image and extract all available "
            "information. Look for:\n"
            "- Pattern name and description\n"
            "- Yarn requirements (brand, weight, color, yardage)\n"
            "- Needle sizes\n"
            "- Gauge/stitch sample information\n"
            "- Written instructions or charts\n"
            "- Any other relevant details\n\n"
        )

        if hints:
            prompt += f"\nAdditional context: {json.dumps(hints, indent=2)}\n\n"

        prompt += "Return the extracted data as JSON."
        return prompt

    def _build_text_prompt(self, text: str, hints: dict[str, Any] | None) -> str:
        """Build prompt for text analysis."""
        prompt = f"Extract knitting pattern information from this text:\n\n{text}\n\n"

        if hints:
            prompt += f"\nAdditional context: {json.dumps(hints, indent=2)}\n\n"

        prompt += "Return the extracted data as JSON."
        return prompt

    def _parse_ai_response(self, raw_content: str) -> ExtractedData:
        """Parse AI JSON response into ExtractedData."""
        try:
            data = json.loads(raw_content)
        except json.JSONDecodeError as exc:
            raise ExtractorError(
                f"AI returned invalid JSON: {exc}",
                extractor_name=self.name,
            ) from exc

        # Parse steps
        steps: list[ExtractedStep] = []
        for step_data in data.get("steps", []):
            if isinstance(step_data, dict):
                steps.append(
                    ExtractedStep(
                        step_number=step_data.get("step_number", len(steps) + 1),
                        title=step_data.get("title"),
                        description=step_data.get("description"),
                    )
                )

        return ExtractedData(
            name=data.get("name"),
            description=data.get("description"),
            category=data.get("category"),
            yarn=data.get("yarn"),
            brand=data.get("brand"),
            colorway=data.get("colorway"),
            weight_category=data.get("weight_category"),
            fiber_content=data.get("fiber_content"),
            needles=data.get("needles"),
            stitch_sample=data.get("stitch_sample"),
            steps=steps,
            extras={
                k: v
                for k, v in data.items()
                if k
                not in {
                    "name",
                    "description",
                    "category",
                    "yarn",
                    "brand",
                    "colorway",
                    "weight_category",
                    "fiber_content",
                    "needles",
                    "stitch_sample",
                    "steps",
                    "image_urls",
                    "link",
                }
            },
        )


__all__ = ["AIExtractor"]
