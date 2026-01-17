"""AI-powered URL import using OpenAI for better pattern extraction."""

import inspect
import json as json_module
import os
from typing import Any

import httpx
from bs4 import BeautifulSoup
from sqlalchemy import Integer, String, Text

# Check if OpenAI is available
try:
    from openai import AsyncOpenAI

    OPENAI_AVAILABLE = True
except ImportError:
    OPENAI_AVAILABLE = False


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


class AIPatternImporter:
    """Extract knitting pattern data from URLs using AI."""

    def __init__(self, url: str, timeout: int = 30) -> None:
        """Initialize with URL to import."""
        self.url = url
        self.timeout = timeout
        self.api_key = os.getenv("OPENAI_API_KEY")

    async def fetch_and_parse(self) -> dict[str, Any]:
        """Fetch URL and extract pattern data using AI."""
        if not self.api_key or not OPENAI_AVAILABLE:
            raise ValueError(
                "OpenAI API key not configured or openai package not installed. "
                "Set OPENAI_API_KEY environment variable."
            )

        # Fetch the page
        async with httpx.AsyncClient(
            timeout=self.timeout, follow_redirects=True
        ) as client:
            response = await client.get(self.url)
            response.raise_for_status()

        soup = BeautifulSoup(response.text, "html.parser")

        # Remove script and style elements
        for script in soup(["script", "style", "nav", "footer", "header"]):
            script.decompose()

        # Get text content
        text_content = soup.get_text(separator="\n", strip=True)

        # Limit text length to avoid token limits
        if len(text_content) > 8000:
            text_content = text_content[:8000]

        # Extract images
        images = await self._extract_images(soup)

        # Use AI to parse the content
        extracted_data = await self._ai_extract(text_content, images[:5])

        # Add the source URL
        extracted_data["link"] = self.url

        # Add image URLs
        extracted_data["image_urls"] = images[:10]  # Limit to 10 images

        return extracted_data

    async def _extract_images(self, soup: BeautifulSoup) -> list[str]:
        """Extract image URLs from the page."""
        images: list[str] = []

        # Look for images in common pattern containers
        for img in soup.find_all("img"):
            src = img.get("src") or img.get("data-src")
            if not src or not isinstance(src, str):
                continue

            # Make absolute URL
            if src.startswith("//"):
                src = f"https:{src}"
            elif src.startswith("/"):
                from urllib.parse import urlparse

                parsed = urlparse(self.url)
                src = f"{parsed.scheme}://{parsed.netloc}{src}"
            elif not src.startswith("http"):
                continue

            # Skip tiny images, icons, logos
            width = img.get("width")
            height = img.get("height")
            if width and height:
                try:
                    if int(str(width)) < 100 or int(str(height)) < 100:
                        continue
                except (ValueError, TypeError):
                    pass

            # Skip common non-pattern images
            if any(
                x in src.lower()
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

            images.append(src)

        return images

    async def _ai_extract(
        self, text_content: str, image_urls: list[str]
    ) -> dict[str, Any]:
        """Use OpenAI to extract pattern information."""
        from stricknani.models import Project

        client = AsyncOpenAI(api_key=self.api_key)

        # Build schema dynamically from Project model
        schema = _build_schema_from_model(Project)

        # Create example based on schema
        example: dict[str, Any] = {}
        for field, props in schema["properties"].items():
            if field == "name":
                example[field] = "Cozy Scarf"
            elif field == "needles":
                example[field] = "4mm"
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

        system_prompt = f"""You are an expert at extracting knitting \
pattern information.
Extract the following fields from the provided text:

{json_module.dumps(schema, indent=2)}

Return valid JSON only. Use null for missing values.
Example format:
{json_module.dumps(example, indent=2)}"""

        user_prompt = (
            f"Extract knitting pattern information from this text:\n\n{text_content}"
        )

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

            result: dict[str, Any] = json_parser.loads(
                response.choices[0].message.content or "{}"
            )
            return result

        except Exception as e:
            # Log the error but continue with fallback
            import logging

            logger = logging.getLogger(__name__)
            logger.error(f"AI extraction failed: {e}", exc_info=True)

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
