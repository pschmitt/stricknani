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
    from openai.types.chat import ChatCompletionContentPartParam

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
        content: RawContent | list[RawContent],
        *,
        hints: dict[str, Any] | None = None,
    ) -> ExtractedData:
        """Extract pattern data using AI.

        Args:
            content: Raw content or list of contents to analyze
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

        contents = content if isinstance(content, list) else [content]
        if not contents:
            raise ExtractorError("No content provided", extractor_name=self.name)

        # Check content types
        has_image = any(c.content_type == ContentType.IMAGE for c in contents)
        has_pdf = any(c.content_type == ContentType.PDF for c in contents)
        has_text = any(
            c.content_type in (ContentType.TEXT, ContentType.HTML) for c in contents
        )

        try:
            # Strategy: If any visual content (Image/PDF), treat EVERYTHING as visual context if possible?
            # Or convert PDF to images and process as images.
            if has_image or has_pdf:
                # Resolve PDF to images
                final_images: list[RawContent] = []
                extra_text = []

                from stricknani.importing.extractors.pdf import PDFExtractor

                pdf_extractor = PDFExtractor(extract_images=True)

                for c in contents:
                    if c.content_type == ContentType.IMAGE:
                        final_images.append(c)
                    elif c.content_type == ContentType.PDF:
                        # Render PDF pages as high-res images (preferred for AI analysis)
                        page_images = await pdf_extractor.render_pages_as_images(c)
                        if page_images:
                            for i, img_bytes in enumerate(page_images):
                                final_images.append(
                                    RawContent(
                                        content=img_bytes, 
                                        content_type=ContentType.IMAGE,
                                        metadata={"filename": f"{c.metadata.get('filename', 'doc')}_page_{i+1}.jpg"}
                                    )
                                )
                        
                        # Also get text as hint/context
                        try:
                            text_data = await pdf_extractor.extract(c)
                            if text_data.get("description"):
                                extra_text.append(f"Context from PDF {c.metadata.get('filename')}:\n{text_data['description']}")
                        except Exception:
                            pass
                    elif c.content_type in (ContentType.TEXT, ContentType.HTML):
                         extra_text.append(f"Context from {c.metadata.get('filename')}:\n{c.get_text()}")

                # Process all images
                result = await self._extract_from_images(final_images, hints, extra_context="\n\n".join(extra_text))
                
                # Collect rendered PDF pages to return in extras (for UI attachment display)
                rendered_pages = []
                for img in final_images:
                    # Identify pages by metadata or source checks?
                    # We constructed metadata as "..._page_N.jpg"
                    if img.metadata.get("filename", "").endswith(".jpg") and "_page_" in img.metadata.get("filename", ""):
                         if isinstance(img.content, bytes):
                             rendered_pages.append(img.content)
                
                if rendered_pages:
                    # Ensure extras dict exists
                    if result.extras is None:
                        result.extras = {}
                    result.extras["pdf_rendered_pages"] = rendered_pages

                return result

            elif has_text:
                # Concatenate all text
                full_text = "\n\n".join([c.get_text() for c in contents])
                # Mock a combined content object
                combined = RawContent(
                    content=full_text.encode("utf-8"),
                    content_type=ContentType.TEXT,
                    metadata={"filename": "combined_text.txt"},
                )
                return await self._extract_from_text(combined, hints)

            else:
                 raise ExtractorError(
                    f"Unsupported content types in: {[c.content_type for c in contents]}",
                    extractor_name=self.name,
                )

        except Exception as e:
             raise ExtractorError(
                f"AI extraction failed: {str(e)}",
                extractor_name=self.name,
            ) from e

    async def _extract_from_images(
        self,
        contents: list[RawContent],
        hints: dict[str, Any] | None,
        extra_context: str = "",
    ) -> ExtractedData:
        """Extract data from images using vision API."""
        client = AsyncOpenAI(api_key=self.api_key)

        # Build prompt
        system_prompt = self._build_system_prompt()
        base_prompt = self._build_image_prompt(hints)
        if extra_context:
            base_prompt += f"\n\nAdditional Context:\n{extra_context}"

        user_content = [{"type": "text", "text": base_prompt}]

        for content in contents:
            image_bytes = (
                content.content
                if isinstance(content.content, bytes)
                else content.content.encode()
            )
            # Resize if needed to stay within token limits
            processed_image = await self._prepare_image(image_bytes)
            
            # Convert to base64
            base64_image = base64.b64encode(processed_image).decode("utf-8")
            
            user_content.append({
                "type": "image_url",
                "image_url": {
                    "url": f"data:image/jpeg;base64,{base64_image}",
                },
            })

        try:
            response = await client.chat.completions.create(
                model=self.model,
                messages=[
                    {"role": "system", "content": system_prompt},
                    {
                        "role": "user",
                        "content": user_content,
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
        extra_prompt: str | None = None,
    ) -> ExtractedData:
        """Extract data from text using GPT."""
        client = AsyncOpenAI(api_key=self.api_key)

        text = content.get_text()

        # Limit text length
        if len(text) > 12000:
            text = text[:12000] + "\n[Content truncated...]"

        system_prompt = self._build_system_prompt()
        user_prompt = self._build_text_prompt(text, hints)
        if extra_prompt:
            user_prompt += f"\n\n{extra_prompt}"

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
            "CRITICAL INSTRUCTIONS:\n"
            "1. LANGUAGE: Output MUST be in the same language as the source text. "
            "   Do NOT translate content. If the pattern is in German, the description, "
            "   steps, and notes MUST be in German.\n"
            "2. COMPLETENESS: Capture ALL text content. Do not summarize aggressively. "
            "   If there are 'Information', 'Attention', 'Notes', or 'Warning' sections "
            "   that don't fit into a specific step, you MUST include them in the "
            "   'description' field. Do not drop them.\n"
            "3. FORMATTING: Use Markdown for all text fields to preserve structure.\n"
            "4. IMAGES:\n"
            "   - 'image_urls': Should contain ONLY images of the FINISHED object (title images).\n"
            "   - 'steps.images': Should contain images that illustrate that SPECIFIC step.\n"
            "   - Do NOT put finished object images in the last step unless it is explicitly "
            "     about finishing/blocking and shows that process.\n\n"
            "Fields to extract:\n"
            "- name: Project/pattern name\n"
            "- description: Full description, including general info/warnings\n"
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
            "- other_materials: Buttons, zippers, or other notions needed\n"
            "- stitch_sample: Gauge information\n"
            "- steps: Array of instruction steps with step_number, title, "
            "description, and images (array of URLs or identifiers)\n"
            "- image_urls: Array of general gallery/title image URLs or identifiers\n"
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

        def normalize_to_string(value: Any) -> str | None:
            """Convert AI response values to strings, handling objects/arrays."""
            if value is None:
                return None
            if isinstance(value, str):
                return value if value.strip() else None
            if isinstance(value, (list, tuple)):
                # Join list items into a string
                items = [str(item) for item in value if item]
                return ", ".join(items) if items else None
            if isinstance(value, dict):
                # Convert dict to a readable string
                parts = []
                for k, v in value.items():
                    if v:
                        parts.append(f"{k}: {v}")
                return ", ".join(parts) if parts else None
            # For numbers, booleans, etc.
            return str(value)

        # Parse steps
        steps: list[ExtractedStep] = []
        for step_data in data.get("steps", []):
            if isinstance(step_data, dict):
                steps.append(
                    ExtractedStep(
                        step_number=step_data.get("step_number", len(steps) + 1),
                        title=step_data.get("title"),
                        description=step_data.get("description"),
                        images=step_data.get("images", []),
                    )
                )

        return ExtractedData(
            name=normalize_to_string(data.get("name")),
            description=normalize_to_string(data.get("description")),
            category=normalize_to_string(data.get("category")),
            yarn=normalize_to_string(data.get("yarn")),
            brand=normalize_to_string(data.get("brand")),
            colorway=normalize_to_string(data.get("colorway")),
            weight_category=normalize_to_string(data.get("weight_category")),
            fiber_content=normalize_to_string(data.get("fiber_content")),
            needles=normalize_to_string(data.get("needles")),
            other_materials=normalize_to_string(data.get("other_materials")),
            stitch_sample=normalize_to_string(data.get("stitch_sample")),
            steps=steps,
            image_urls=data.get("image_urls", []),
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
                    "other_materials",
                    "stitch_sample",
                    "steps",
                    "image_urls",
                    "link",
                }
            },
        )


__all__ = ["AIExtractor"]
