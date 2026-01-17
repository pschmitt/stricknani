"""AI-powered URL import using OpenAI for better pattern extraction."""

import os
from typing import Any

import httpx
from bs4 import BeautifulSoup

# Check if OpenAI is available
try:
    from openai import AsyncOpenAI

    OPENAI_AVAILABLE = True
except ImportError:
    OPENAI_AVAILABLE = False


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
        client = AsyncOpenAI(api_key=self.api_key)

        system_prompt = """You are an expert at extracting knitting pattern information.
Extract the following from the provided text:
- title: The pattern name
- needles: Needle size (e.g. "3.5mm", "US 6")
- yarn: Yarn name and weight
- gauge_stitches: Number of stitches per 10cm (integer)
- gauge_rows: Number of rows per 10cm (integer)
- comment: A brief description or notes about the pattern
- steps: Array of instruction steps with title and description

Return valid JSON only. Use null for missing values.
Example format:
{
  "title": "Cozy Scarf",
  "needles": "4mm",
  "yarn": "Worsted weight wool",
  "gauge_stitches": 20,
  "gauge_rows": 28,
  "comment": "A simple beginner-friendly scarf pattern",
  "steps": [
    {"step_number": 1, "title": "Cast On", "description": "Cast on 40 stitches"},
    {"step_number": 2, "title": "Body", "description": "Knit in stockinette stitch"}
  ]
}"""

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

            import json

            result: dict[str, Any] = json.loads(
                response.choices[0].message.content or "{}"
            )
            return result

        except Exception:
            # Fallback to empty data if AI extraction fails
            return {
                "title": None,
                "needles": None,
                "yarn": None,
                "gauge_stitches": None,
                "gauge_rows": None,
                "comment": f"Imported from {self.url}",
                "steps": [],
            }
