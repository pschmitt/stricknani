"""URL import utilities for extracting knitting pattern data."""

import logging
import re
from typing import Any

import httpx
from bs4 import BeautifulSoup
from bs4.element import Tag

logger = logging.getLogger("stricknani.imports")


class PatternImporter:
    """Extract knitting pattern data from URLs."""

    def __init__(self, url: str, timeout: int = 10) -> None:
        """Initialize with URL to import."""
        self.url = url
        self.timeout = timeout

    async def fetch_and_parse(self) -> dict[str, Any]:
        """Fetch URL and extract pattern data."""
        logger.info("Importing pattern from %s", self.url)
        async with httpx.AsyncClient(
            timeout=self.timeout,
            follow_redirects=True,
            headers={
                "User-Agent": "Stricknani Importer/0.1",
                "Accept": (
                    "text/html,application/xhtml+xml,application/xml;"
                    "q=0.9,*/*;q=0.8"
                ),
            },
        ) as client:
            response = await client.get(self.url)
            response.raise_for_status()
            logger.debug(
                "Import response %s %s",
                response.status_code,
                response.headers.get("content-type", ""),
            )

        soup = BeautifulSoup(response.text, "html.parser")

        steps = self._extract_steps(soup)
        images = self._extract_images(soup)
        data = {
            "title": self._extract_title(soup),
            "needles": self._extract_needles(soup),
            "yarn": self._extract_yarn(soup),
            "gauge_stitches": self._extract_gauge_stitches(soup),
            "gauge_rows": self._extract_gauge_rows(soup),
            "comment": self._extract_description(soup),
            "steps": steps,
            "link": self.url,
            "image_urls": images,
        }
        logger.info(
            "Import extracted title=%s needles=%s yarn=%s steps=%s images=%s",
            data.get("title"),
            data.get("needles"),
            data.get("yarn"),
            len(steps),
            len(images),
        )
        return data

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
        steps: list[dict[str, Any]] = []

        # Look for numbered lists or instruction sections
        candidates = [
            soup.find(
                ["div", "section"], class_=re.compile(r"instruction|pattern|step", re.I)
            ),
            soup.find(
                ["div", "section"], id=re.compile(r"pattern[_-]?text|instruction", re.I)
            ),
            soup.find(
                ["div", "section"], class_=re.compile(r"pattern[_-]?text", re.I)
            ),
            soup.find("article"),
            soup.find("main"),
        ]

        for instructions_section in [c for c in candidates if c]:
            # Try to find ordered list
            ol = instructions_section.find("ol")
            if ol:
                for i, li in enumerate(ol.find_all("li", recursive=False), 1):
                    text = li.get_text(strip=True)
                    step_images = []
                    for img in li.find_all("img"):
                        src = img.get("src") or img.get("data-src")
                        if src:
                            resolved = self._resolve_image_url(src)
                            if resolved:
                                step_images.append(resolved)

                    if text or step_images:
                        steps.append(
                            {
                                "step_number": i,
                                "title": f"Step {i}",
                                "description": text,
                                "images": step_images,
                            }
                        )

            # Try to find headings with content
            if not steps:
                headings = instructions_section.find_all(["h2", "h3", "h4"], limit=10)
                for i, heading in enumerate(headings, 1):
                    title = heading.get_text(strip=True)
                    # Get next sibling content
                    content = []
                    step_images = []
                    
                    for sibling in heading.next_siblings:
                        if hasattr(sibling, "name") and sibling.name in [
                            "h2",
                            "h3",
                            "h4",
                        ]:
                            break
                        
                        # Extract images
                        if hasattr(sibling, "name") and sibling.name == "img":
                            src = sibling.get("src") or sibling.get("data-src")
                            if src:
                                resolved = self._resolve_image_url(src)
                                if resolved:
                                    step_images.append(resolved)
                        elif hasattr(sibling, "find_all"):
                            for img in sibling.find_all("img"):
                                src = img.get("src") or img.get("data-src")
                                if src:
                                    resolved = self._resolve_image_url(src)
                                    if resolved:
                                        step_images.append(resolved)

                        if hasattr(sibling, "get_text"):
                            text = sibling.get_text(strip=True)
                            if text:
                                content.append(text)

                        # Try to find content in parent siblings if direct content was empty
                        if not content or not step_images:
                            curr = heading
                            # Climb up to 5 levels to find a wrapper that has content siblings
                            for _ in range(5):
                                if not curr.parent or curr.parent == instructions_section:
                                    break
                                curr = curr.parent

                                parent_content = []
                                parent_images = []
                                # Check next siblings of this parent
                                for sibling in curr.next_siblings:
                                    if hasattr(sibling, "name") and sibling.name in ["h2", "h3", "h4"]:
                                        break

                                    # Only check content of Tags, not strings
                                    if getattr(sibling, "name", None):
                                        if sibling.find("h2") or sibling.find("h3") or sibling.find("h4"):
                                            # Sibling contains a heading, so it's likely the next section
                                            break
                                    
                                    # Extract images from parent siblings
                                    if hasattr(sibling, "name") and sibling.name == "img":
                                        src = sibling.get("src") or sibling.get("data-src")
                                        if src:
                                            resolved = self._resolve_image_url(src)
                                            if resolved:
                                                parent_images.append(resolved)
                                    elif hasattr(sibling, "find_all"):
                                        for img in sibling.find_all("img"):
                                            src = img.get("src") or img.get("data-src")
                                            if src:
                                                resolved = self._resolve_image_url(src)
                                                if resolved:
                                                    parent_images.append(resolved)

                                    if hasattr(sibling, "get_text"):
                                        text = sibling.get_text(strip=True)
                                        if text:
                                            parent_content.append(text)

                                if parent_content:
                                    if not content:
                                        content = parent_content
                                if parent_images:
                                    if not step_images:
                                        step_images = parent_images
                                
                                if content and step_images:
                                    break

                        steps.append(
                            {
                                "step_number": i,
                                "title": title,
                                "description": "\n\n".join(content)
                                if content
                                else None,
                                "images": step_images,
                            }
                        )

            if not steps:
                steps = self._build_steps_from_text(instructions_section)

            if steps:
                break

        # Deduplicate steps based on title and description
        unique_steps: dict[tuple[str, str], dict[str, Any]] = {}
        for step in steps:
            key = (step["title"], step["description"] or "")
            if key in unique_steps:
                # Merge images if step exists
                existing_images = unique_steps[key].get("images", [])
                new_images = step.get("images", [])
                for img in new_images:
                    if img not in existing_images:
                        existing_images.append(img)
                unique_steps[key]["images"] = existing_images
            else:
                unique_steps[key] = step

        # Re-index step numbers
        final_steps = list(unique_steps.values())
        for i, step in enumerate(final_steps, 1):
            step["step_number"] = i
            # Correct generic titles if re-indexing shifted them
            if step["title"].startswith("Step ") and step["title"][5:].isdigit():
                 step["title"] = f"Step {i}"

        return final_steps

    def _build_steps_from_text(self, container: Tag) -> list[dict[str, Any]]:
        text = container.get_text("\n", strip=True)
        if not text:
            return []

        lines = []
        for line in text.splitlines():
            cleaned = line.strip()
            if not cleaned:
                lines.append("")
                continue
            if set(cleaned) <= {"-"}:
                continue
            lines.append(cleaned)

        steps: list[dict[str, Any]] = []
        current_title: str | None = None
        current_lines: list[str] = []

        def flush() -> None:
            if not current_lines:
                return
            description = " ".join(current_lines).strip()
            if not description:
                return
            step_number = len(steps) + 1
            steps.append(
                {
                    "step_number": step_number,
                    "title": current_title or f"Step {step_number}",
                    "description": description,
                }
            )

        for line in lines:
            if not line:
                flush()
                current_title = None
                current_lines = []
                continue
            if line.endswith(":") and len(line) <= 80:
                flush()
                current_title = line.rstrip(":").strip()
                current_lines = []
                continue
            current_lines.append(line)

        flush()
        return steps

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

    def _extract_images(self, soup: BeautifulSoup) -> list[str]:
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

            # Filter out small images (likely icons/logos)
            width = img.get("width")
            height = img.get("height")
            if width and height:
                try:
                    if int(str(width)) < 100 or int(str(height)) < 100:
                        continue
                except (ValueError, TypeError):
                    pass

            # Skip common non-pattern images
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

        return images[:10]  # Limit to 10 images
