"""URL import utilities for extracting knitting pattern data."""

import re
from typing import Any

import httpx
from bs4 import BeautifulSoup


class PatternImporter:
    """Extract knitting pattern data from URLs."""

    def __init__(self, url: str, timeout: int = 10) -> None:
        """Initialize with URL to import."""
        self.url = url
        self.timeout = timeout

    async def fetch_and_parse(self) -> dict[str, Any]:
        """Fetch URL and extract pattern data."""
        async with httpx.AsyncClient(
            timeout=self.timeout, follow_redirects=True
        ) as client:
            response = await client.get(self.url)
            response.raise_for_status()

        soup = BeautifulSoup(response.text, "html.parser")

        return {
            "title": self._extract_title(soup),
            "needles": self._extract_needles(soup),
            "yarn": self._extract_yarn(soup),
            "gauge_stitches": self._extract_gauge_stitches(soup),
            "gauge_rows": self._extract_gauge_rows(soup),
            "comment": self._extract_description(soup),
            "steps": self._extract_steps(soup),
            "link": self.url,
        }

    def _extract_title(self, soup: BeautifulSoup) -> str | None:
        """Extract pattern title."""
        # Try various title patterns
        patterns = [
            soup.find("h1", class_=re.compile(r"pattern|title|name", re.I)),
            soup.find("h1"),
            soup.find("meta", property="og:title"),
            soup.find("title"),
        ]

        for pattern in patterns:
            if pattern:
                if hasattr(pattern, "name") and pattern.name == "meta":
                    content = pattern.get("content")
                    if isinstance(content, str):
                        return content
                text = pattern.get_text(strip=True)
                if text and len(text) > 3:
                    return text

        return None

    def _extract_needles(self, soup: BeautifulSoup) -> str | None:
        """Extract needle information."""
        patterns = [
            r"needle[s]?\s*[:：]\s*([^\n]+)",
            r"([0-9.]+\s*mm)",
            r"(US\s*\d+)",
        ]

        text = soup.get_text()
        for pattern in patterns:
            match = re.search(pattern, text, re.I)
            if match:
                return match.group(1).strip()

        return None

    def _extract_yarn(self, soup: BeautifulSoup) -> str | None:
        """Extract yarn information."""
        patterns = [
            r"yarn[s]?\s*[:：]\s*([^\n]+)",
            r"material[s]?\s*[:：]\s*([^\n]+)",
        ]

        text = soup.get_text()
        for pattern in patterns:
            match = re.search(pattern, text, re.I)
            if match:
                yarn_text = match.group(1).strip()
                # Limit length
                if len(yarn_text) > 200:
                    yarn_text = yarn_text[:200] + "..."
                return yarn_text

        return None

    def _extract_gauge_stitches(self, soup: BeautifulSoup) -> int | None:
        """Extract gauge stitches per 10cm."""
        patterns = [
            r"(\d+)\s*st(?:itches?)?\s*(?:per|in|over|=)\s*10\s*cm",
            r"gauge[:\s]+(\d+)\s*st",
        ]

        text = soup.get_text()
        for pattern in patterns:
            match = re.search(pattern, text, re.I)
            if match:
                try:
                    return int(match.group(1))
                except ValueError:
                    pass

        return None

    def _extract_gauge_rows(self, soup: BeautifulSoup) -> int | None:
        """Extract gauge rows per 10cm."""
        patterns = [
            r"(\d+)\s*row[s]?\s*(?:per|in|over|=)\s*10\s*cm",
            r"gauge[:\s]+\d+\s*st[^,]+,\s*(\d+)\s*row",
        ]

        text = soup.get_text()
        for pattern in patterns:
            match = re.search(pattern, text, re.I)
            if match:
                try:
                    return int(match.group(1))
                except ValueError:
                    pass

        return None

    def _extract_description(self, soup: BeautifulSoup) -> str | None:
        """Extract pattern description."""
        # Try meta description first
        meta_desc = soup.find("meta", {"name": "description"})
        if meta_desc:
            content = meta_desc.get("content")
            if isinstance(content, str):
                return content.strip()

        # Try og:description
        og_desc = soup.find("meta", property="og:description")
        if og_desc:
            content = og_desc.get("content")
            if isinstance(content, str):
                return content.strip()

        # Try first paragraph in article or main content
        for tag in ["article", "main", "div"]:
            container = soup.find(tag, class_=re.compile(r"description|content", re.I))
            if container:
                p = container.find("p")
                if p:
                    text = p.get_text(strip=True)
                    if len(text) > 20:
                        return text

        return None

    def _extract_steps(self, soup: BeautifulSoup) -> list[dict[str, Any]]:
        """Extract pattern instructions as steps."""
        steps = []

        # Look for numbered lists or instruction sections
        instructions_section = soup.find(
            ["div", "section"], class_=re.compile(r"instruction|pattern|step", re.I)
        )

        if instructions_section:
            # Try to find ordered list
            ol = instructions_section.find("ol")
            if ol:
                for i, li in enumerate(ol.find_all("li", recursive=False), 1):
                    text = li.get_text(strip=True)
                    if text:
                        steps.append(
                            {
                                "step_number": i,
                                "title": f"Step {i}",
                                "description": text,
                            }
                        )

            # Try to find headings with content
            if not steps:
                headings = instructions_section.find_all(["h2", "h3", "h4"], limit=10)
                for i, heading in enumerate(headings, 1):
                    title = heading.get_text(strip=True)
                    # Get next sibling content
                    content = []
                    for sibling in heading.next_siblings:
                        if hasattr(sibling, "name") and sibling.name in [
                            "h2",
                            "h3",
                            "h4",
                        ]:
                            break
                        if hasattr(sibling, "get_text"):
                            text = sibling.get_text(strip=True)
                            if text:
                                content.append(text)

                    if title:
                        steps.append(
                            {
                                "step_number": i,
                                "title": title,
                                "description": "\n\n".join(content)
                                if content
                                else None,
                            }
                        )

        return steps
