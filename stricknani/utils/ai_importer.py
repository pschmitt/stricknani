"""AI-powered URL import using OpenAI for better pattern extraction."""

import inspect
import json as json_module
import logging
import os
import re
from urllib.parse import urlparse
from typing import TYPE_CHECKING, Any

import httpx
from bs4 import BeautifulSoup
from sqlalchemy import Integer, String, Text

# Check if OpenAI is available
try:
    from openai import AsyncOpenAI

    OPENAI_AVAILABLE = True
except ImportError:
    OPENAI_AVAILABLE = False

logger = logging.getLogger("stricknani.imports")
IMPORT_HEADERS = {
    "User-Agent": "Stricknani Importer/0.1",
    "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
}


def _is_garnstudio_url(url: str) -> bool:
    parsed = urlparse(url)
    host = (parsed.netloc or "").lower()
    if host.startswith("www."):
        host = host[4:]
    return host.endswith(
        (
            "garnstudio.com",
            "garnstudio.no",
            "dropsdesign.com",
            "dropsdesign.no",
        )
    )


def _looks_like_image_url(url: str) -> bool:
    lower = url.lower()
    if any(token in lower for token in ["diagram", "chart", "schema", "schem"]):
        return True
    path = urlparse(lower).path
    return path.endswith((".png", ".jpg", ".jpeg", ".gif", ".webp"))


def _extract_garnstudio_text(soup: BeautifulSoup) -> str:
    candidates = [
        soup.find(["div", "section"], class_=re.compile(r"pattern-instructions", re.I)),
        soup.find(["div", "section"], id=re.compile(r"pattern-instructions", re.I)),
        soup.find(["div", "section"], id=re.compile(r"pattern[_-]?text", re.I)),
        soup.find(["div", "section"], class_=re.compile(r"pattern[_-]?text", re.I)),
        soup.find("article"),
        soup.find("main"),
    ]

    for container in [c for c in candidates if c]:
        text = container.get_text(separator="\n", strip=True)
        if text:
            return text

    return soup.get_text(separator="\n", strip=True)

if TYPE_CHECKING:
    from stricknani.utils.import_trace import ImportTrace


def _build_schema_from_model(model_class: type) -> dict[str, Any]:
    """Build JSON schema from SQLAlchemy model dynamically."""
    from sqlalchemy.orm import ColumnProperty

    schema: dict[str, Any] = {
        "type": "object",
        "properties": {},
        "required": [],
    }

    # Get all Mapped columns from the model
    for name, _annotation in inspect.get_annotations(model_class).items():
        # Skip relationships, foreign keys, and timestamps
        if name in {
            "id",
            "owner_id",
            "owner",
            "created_at",
            "updated_at",
            "images",
            "steps",
            "yarns",
            "link",  # Skip link as we set it manually from the URL
            "link_archive",
            "link_archive_requested_at",
        }:
            continue

        # Get the column from the model
        if not hasattr(model_class, name):
            continue

        col = getattr(model_class, name)
        if not hasattr(col, "property") or not isinstance(col.property, ColumnProperty):
            continue

        # Get column info
        columns = list(col.property.columns)
        if not columns:
            continue

        column = columns[0]
        column_type = column.type
        is_nullable = column.nullable

        json_type = "string"  # Default
        description = f"The {name.replace('_', ' ')}"

        if isinstance(column_type, Integer):
            json_type = "integer"
        elif isinstance(column_type, (String, Text)):
            json_type = "string"

        # Special handling for specific fields
        if name == "gauge_stitches":
            description = "Number of stitches per 10cm (integer)"
        elif name == "gauge_rows":
            description = "Number of rows per 10cm (integer)"
        elif name == "needles":
            description = "Needle size (e.g. '3.5mm', 'US 6')"
        elif name == "recommended_needles":
            description = "Recommended needle size from the pattern"
        elif name == "yarn":
            description = "Yarn name and weight"
        elif name == "comment":
            description = "A brief description or notes about the pattern"
        elif name == "category":
            description = "Project category (e.g. 'Pullover', 'Schal', 'Mütze')"
        elif name == "name":
            description = "The pattern or project name"
            schema["required"].append(name)

        prop: dict[str, Any] = {"type": json_type, "description": description}

        if is_nullable or name not in schema["required"]:
            prop["nullable"] = True

        schema["properties"][name] = prop

    # Add steps field (not a direct column but important for patterns)
    schema["properties"]["steps"] = {
        "type": "array",
        "description": "Array of instruction steps",
        "items": {
            "type": "object",
            "properties": {
                "step_number": {"type": "integer"},
                "title": {"type": "string"},
                "description": {"type": "string"},
            },
        },
    }

    return schema


def _build_example_from_schema(schema: dict[str, Any]) -> dict[str, Any]:
    example: dict[str, Any] = {}
    for field, props in schema["properties"].items():
        if field == "name":
            example[field] = "Cozy Scarf"
        elif field == "needles":
            example[field] = "4mm"
        elif field == "recommended_needles":
            example[field] = "3.5mm"
        elif field == "yarn":
            example[field] = "Worsted weight wool"
        elif field == "gauge_stitches":
            example[field] = 20
        elif field == "gauge_rows":
            example[field] = 28
        elif field == "comment":
            example[field] = "A simple beginner-friendly scarf pattern"
        elif field == "category":
            example[field] = "Schal"
        elif field == "steps":
            example[field] = [
                {
                    "step_number": 1,
                    "title": "Cast On",
                    "description": "Cast on 40 stitches",
                },
                {
                    "step_number": 2,
                    "title": "Body",
                    "description": "Knit in stockinette stitch",
                },
            ]
        elif props.get("nullable"):
            example[field] = None
    return example


def _build_ai_prompts(
    *,
    schema: dict[str, Any],
    text_content: str,
    hints: dict[str, Any] | None = None,
    image_urls: list[str] | None = None,
    source_url: str | None = None,
) -> tuple[str, str]:
    example = _build_example_from_schema(schema)

    system_prompt = (
        "You are an expert at extracting knitting pattern information.\n"
        "Extract the following fields from the provided text.\n"
        "Use the exact field names from the schema. Use null for missing values.\n"
        "Prefer structured steps: split instructions into ordered "
        "steps when possible.\n"
        "Do not invent data that is not present in the source.\n\n"
        f"{json_module.dumps(schema, indent=2)}\n\n"
        "Return valid JSON only.\n"
        "Example format:\n"
        f"{json_module.dumps(example, indent=2)}"
    )

    hint_block = ""
    if hints:
        hint_block = (
            "\n\nHeuristic parser hints (may be wrong or incomplete; "
            "use only if supported by the source text):\n"
            f"{json_module.dumps(hints, indent=2)}"
        )

    image_block = ""
    if image_urls:
        image_block = (
            "\n\nImage URLs (for reference only; may include non-pattern images):\n"
            + "\n".join(f"- {url}" for url in image_urls)
        )

    source_block = ""
    if source_url:
        source_block = f"\n\nSource URL: {source_url}"

    user_prompt = (
        "Extract knitting pattern information from this text:\n\n"
        f"{text_content}{hint_block}{image_block}{source_block}"
    )

    return system_prompt, user_prompt


class AIPatternImporter:
    """Extract knitting pattern data from URLs using AI."""

    def __init__(
        self,
        url: str,
        timeout: int = 30,
        hints: dict[str, Any] | None = None,
        trace: "ImportTrace | None" = None,
    ) -> None:
        """Initialize with URL to import."""
        self.url = url
        self.timeout = timeout
        self.api_key = os.getenv("OPENAI_API_KEY")
        self.hints = hints
        self.trace = trace

    async def fetch_and_parse(self) -> dict[str, Any]:
        """Fetch URL and extract pattern data using AI."""
        if not self.api_key or not OPENAI_AVAILABLE:
            raise ValueError(
                "OpenAI API key not configured or openai package not installed. "
                "Set OPENAI_API_KEY environment variable."
            )

        # Fetch the page
        logger.info("Importing pattern with AI from %s", self.url)
        async with httpx.AsyncClient(
            timeout=self.timeout, follow_redirects=True, headers=IMPORT_HEADERS
        ) as client:
            response = await client.get(self.url)
            response.raise_for_status()
            logger.debug(
                "AI import response %s %s",
                response.status_code,
                response.headers.get("content-type", ""),
            )

        soup = BeautifulSoup(response.text, "html.parser")

        # Remove script and style elements
        for script in soup(["script", "style", "nav", "footer", "header"]):
            script.decompose()

        # Get text content using Trafilatura if available
        text_content = ""
        if _is_garnstudio_url(self.url):
            text_content = _extract_garnstudio_text(soup)
        try:
            import trafilatura

            # Trafilatura needs the raw HTML string, which we have in response.text
            if not text_content:
                text_content = (
                    trafilatura.extract(
                        response.text,
                        include_comments=False,
                        include_tables=False,
                        no_fallback=False,
                    )
                    or ""
                )
        except ImportError:
            pass

        # Fallback to BeautifulSoup if Trafilatura fails or returns empty
        if not text_content:
            text_content = soup.get_text(separator="\n", strip=True)

        # Limit text length to avoid token limits
        if text_content and len(text_content) > 12000:
            text_content = text_content[:12000]

        # Extract images
        images = await self._extract_images(soup)
        logger.debug("AI import image URLs: %s", images[:5])

        if self.trace:
            self.trace.add_event(
                "source_extracted",
                {
                    "url": self.url,
                    "text_length": len(text_content),
                    "image_count": len(images),
                },
            )
            self.trace.record_text_blob("source_text", text_content)

        # Use AI to parse the content
        extracted_data = await self._ai_extract(text_content, images[:5], self.hints)

        # Add the source URL
        extracted_data["link"] = self.url

        # Add image URLs
        extracted_data["image_urls"] = images[:10]  # Limit to 10 images

        logger.info(
            "AI import extracted name=%s needles=%s yarn=%s steps=%s images=%s",
            extracted_data.get("name"),
            extracted_data.get("needles"),
            extracted_data.get("yarn"),
            len(extracted_data.get("steps") or []),
            len(extracted_data.get("image_urls") or []),
        )
        return extracted_data

    async def _extract_images(self, soup: BeautifulSoup) -> list[str]:
        """Extract image URLs from the page."""
        images: list[str] = []
        seen: set[str] = set()

        meta_image = soup.find("meta", property="og:image")
        if meta_image:
            content = meta_image.get("content")
            if isinstance(content, str):
                resolved = self._resolve_image_url(content)
                if resolved:
                    images.append(resolved)
                    seen.add(resolved)

        for source in soup.find_all("source"):
            for attr in ["srcset", "data-srcset"]:
                value = source.get(attr)
                if not value or not isinstance(value, str):
                    continue
                srcset_url = self._pick_srcset_url(value)
                if not srcset_url:
                    continue
                resolved = self._resolve_image_url(srcset_url)
                if not resolved or resolved in seen:
                    continue
                images.append(resolved)
                seen.add(resolved)

        # Look for images in common pattern containers
        for img in soup.find_all("img"):
            candidates: list[str] = []
            for attr in [
                "src",
                "data-src",
                "data-original",
                "data-lazy-src",
                "data-image",
                "data-pin-media",
                "srcset",
                "data-srcset",
                "data-lazy-srcset",
            ]:
                value = img.get(attr)
                if not value or not isinstance(value, str):
                    continue
                if "srcset" in attr:
                    srcset_url = self._pick_srcset_url(value)
                    if srcset_url:
                        candidates.append(srcset_url)
                else:
                    candidates.append(value)

            if not candidates:
                continue

            # Skip tiny images, icons, logos
            width = img.get("width")
            height = img.get("height")
            if width and height:
                try:
                    if int(str(width)) < 128 or int(str(height)) < 128:
                        if not self._allow_small_image(img, candidates):
                            continue
                except (ValueError, TypeError):
                    pass

            for candidate in candidates:
                resolved = self._resolve_image_url(candidate)
                if not resolved or resolved in seen:
                    continue

                if any(
                    x in resolved.lower()
                    for x in [
                        "logo",
                        "icon",
                        "avatar",
                        "button",
                        "badge",
                        "banner",
                        "ad",
                    ]
                ):
                    continue

                images.append(resolved)
                seen.add(resolved)

        if _is_garnstudio_url(self.url):
            for anchor in soup.find_all("a"):
                href = anchor.get("href")
                if not href or not isinstance(href, str):
                    continue
                if not _looks_like_image_url(href):
                    continue
                resolved = self._resolve_image_url(href)
                if not resolved or resolved in seen:
                    continue
                images.append(resolved)
                seen.add(resolved)

        return images

    def _resolve_image_url(self, src: str) -> str | None:
        cleaned = src.strip()
        if not cleaned or cleaned.startswith(("data:", "javascript:")):
            return None
        if cleaned.startswith("//"):
            return f"https:{cleaned}"
        if cleaned.startswith(("http://", "https://")):
            return cleaned

        from urllib.parse import urljoin

        return urljoin(self.url, cleaned)

    def _pick_srcset_url(self, srcset: str) -> str | None:
        candidates: list[tuple[int, str]] = []
        for entry in srcset.split(","):
            parts = entry.strip().split()
            if not parts:
                continue
            url = parts[0]
            descriptor = parts[1] if len(parts) > 1 else ""
            score = 0
            if descriptor.endswith("w"):
                try:
                    score = int(descriptor[:-1])
                except ValueError:
                    score = 0
            elif descriptor.endswith("x"):
                try:
                    score = int(float(descriptor[:-1]) * 1000)
                except ValueError:
                    score = 0
            candidates.append((score, url))

        if not candidates:
            return None

        return max(candidates, key=lambda item: item[0])[1]

    def _allow_small_image(self, img: Any, candidates: list[str]) -> bool:
        if not _is_garnstudio_url(self.url):
            return False

        attr_bits: list[str] = []
        for key in ["class", "id", "alt", "title"]:
            value = img.get(key)
            if not value:
                continue
            if isinstance(value, list):
                attr_bits.extend([str(item) for item in value])
            else:
                attr_bits.append(str(value))

        combined = " ".join(attr_bits + candidates).lower()
        return any(
            token in combined
            for token in ["diagram", "chart", "schema", "schem", "muster", "pattern"]
        )

    async def _ai_extract(
        self,
        text_content: str,
        image_urls: list[str],
        hints: dict[str, Any] | None,
    ) -> dict[str, Any]:
        """Use OpenAI to extract pattern information."""
        from stricknani.models import Project

        client = AsyncOpenAI(api_key=self.api_key)

        # Build schema dynamically from Project model
        schema = _build_schema_from_model(Project)

        system_prompt, user_prompt = _build_ai_prompts(
            schema=schema,
            text_content=text_content,
            hints=hints,
            image_urls=image_urls,
            source_url=self.url,
        )
        _log_ai_prompt(system_prompt, user_prompt)
        if self.trace:
            self.trace.record_ai_prompt(system_prompt, user_prompt)

        try:
            response = await client.chat.completions.create(
                model="gpt-4o-mini",  # Fast and cheap
                messages=[
                    {"role": "system", "content": system_prompt},
                    {"role": "user", "content": user_prompt},
                ],
                response_format={"type": "json_object"},
                temperature=0.1,
            )

            import json as json_parser

            raw_content = response.choices[0].message.content or ""
            _log_ai_response(raw_content)
            if self.trace:
                self.trace.record_ai_response(raw_content)
            result: dict[str, Any] = json_parser.loads(raw_content or "{}")
            return result

        except Exception as exc:
            logger.error("AI extraction failed", exc_info=True)
            if self.trace:
                self.trace.record_error("ai_extract", exc)

            # Fallback to empty data if AI extraction fails
            return {
                "title": None,
                "needles": None,
                "yarn": None,
                "gauge_stitches": None,
                "gauge_rows": None,
                "comment": (
                    f"Imported from {self.url}\n\n"
                    "(AI extraction failed - please fill in manually)"
                ),
                "steps": [],
            }


def _log_ai_response(raw_content: str) -> None:
    if not raw_content:
        logger.debug("AI raw response: <empty>")
        return
    truncated = raw_content
    if len(truncated) > 4000:
        truncated = f"{truncated[:4000]}... (truncated)"
    logger.debug("AI raw response: %s", truncated)


def _log_ai_prompt(system_prompt: str, user_prompt: str) -> None:
    logger.debug("AI system prompt: %s", _truncate_prompt(system_prompt))
    logger.debug("AI user prompt: %s", _truncate_prompt(user_prompt))


def _truncate_prompt(value: str, limit: int = 4000) -> str:
    if len(value) <= limit:
        return value
    return f"{value[:limit]}... (truncated)"
