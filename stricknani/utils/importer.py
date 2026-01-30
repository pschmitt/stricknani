"""URL import utilities for extracting knitting pattern data."""

import logging
import re
from typing import Any
from urllib.parse import urlparse

import httpx
from bs4 import BeautifulSoup
from bs4.element import AttributeValueList, NavigableString, Tag

logger = logging.getLogger("stricknani.imports")


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


class PatternImporter:
    """Extract knitting pattern data from URLs."""

    def __init__(self, url: str, timeout: int = 10) -> None:
        """Initialize with URL to import."""
        self.url = url
        self.timeout = timeout
        self.is_garnstudio = _is_garnstudio_url(url)
        self._garnstudio_gauge_cache: tuple[int | None, int | None] | None = None

    async def fetch_and_parse(self) -> dict[str, Any]:
        """Fetch URL and extract pattern data."""
        logger.info("Importing pattern from %s", self.url)
        async with httpx.AsyncClient(
            timeout=self.timeout,
            follow_redirects=True,
            headers={
                "User-Agent": (
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                    "AppleWebKit/537.36 (KHTML, like Gecko) "
                    "Chrome/120.0.0.0 Safari/537.36"
                ),
                "Accept": (
                    "text/html,application/xhtml+xml,application/xml;"
                    "q=0.9,image/avif,image/webp,image/apng,*/*;"
                    "q=0.8,application/signed-exchange;v=b3;q=0.7"
                ),
                "Accept-Language": "en-US,en;q=0.9",
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
        comment = self._extract_description(soup)
        if self.is_garnstudio:
            garnstudio_notes = self._extract_garnstudio_notes(soup)
            if garnstudio_notes:
                if comment:
                    if garnstudio_notes not in comment:
                        comment = f"{comment}\n\n{garnstudio_notes}"
                else:
                    comment = garnstudio_notes
        data = {
            "title": self._extract_title(soup),
            "needles": self._extract_needles(soup),
            "yarn": self._extract_yarn(soup),
            "gauge_stitches": self._extract_gauge_stitches(soup),
            "gauge_rows": self._extract_gauge_rows(soup),
            "comment": comment,
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
                # If the match looks like a whole paragraph, skip it.
                if len(yarn_text) > 150:
                    continue
                return yarn_text

        return None

    def _extract_gauge_stitches(self, soup: BeautifulSoup) -> int | None:
        """Extract gauge stitches per 10cm."""
        if self.is_garnstudio:
            stitches, _rows = self._get_garnstudio_gauge(soup)
            if stitches:
                return stitches
        patterns = [
            r"(\d+)\s*st(?:itches?)?\s*(?:per|in|over|=)\s*10\s*cm",
            r"(\d+)\s*st(?:itches?)?\s+in\s+width",
            r"gauge[:\s]+(\d+)\s*st",
            r"(\d+)\s*(?:m|maschen)\s*(?:pro|per|in|auf|über)?\s*10\s*cm",
            r"maschenprobe[^\d]*(\d+)\s*m",
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
        if self.is_garnstudio:
            _stitches, rows = self._get_garnstudio_gauge(soup)
            if rows:
                return rows
        patterns = [
            r"(\d+)\s*row[s]?\s*(?:per|in|over|=)\s*10\s*cm",
            r"(\d+)\s*row[s]?\s+in\s+height",
            r"gauge[:\s]+\d+\s*st[^,]+,\s*(\d+)\s*row",
            r"(\d+)\s*(?:r|reihen)\s*(?:pro|per|in|auf|über)?\s*10\s*cm",
            r"maschenprobe[^\d]*(?:\d+\s*m[^\d]+)?(\d+)\s*r",
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

    def _get_garnstudio_gauge(
        self, soup: BeautifulSoup
    ) -> tuple[int | None, int | None]:
        if self._garnstudio_gauge_cache is not None:
            return self._garnstudio_gauge_cache

        text = soup.get_text(separator=" ", strip=True)
        patterns = [
            r"(\d+)\s*(?:m|maschen)\s*[x×]\s*(\d+)\s*(?:r|reihen)\s*=\s*10\s*(?:x\s*10\s*)?cm",
            r"(\d+)\s*sts?\s*[x×]\s*(\d+)\s*rows?\s*=\s*10\s*(?:x\s*10\s*)?cm",
            r"(\d+)\s*stitches?\s*[x×]\s*(\d+)\s*rows?\s*(?:per|in|over|=)\s*10\s*cm",
        ]
        for pattern in patterns:
            match = re.search(pattern, text, re.I)
            if match:
                try:
                    stitches = int(match.group(1))
                    rows = int(match.group(2))
                except ValueError:
                    continue
                self._garnstudio_gauge_cache = (stitches, rows)
                return self._garnstudio_gauge_cache

        self._garnstudio_gauge_cache = (None, None)
        return self._garnstudio_gauge_cache

    def _extract_garnstudio_notes(self, soup: BeautifulSoup) -> str | None:
        text = self._extract_garnstudio_text(soup)
        if not text:
            return None

        dashed_block = re.search(
            r"(-{5,}\s*\n\s*HINWEISE\s+ZUR\s+ANLEITUNG:?\s*\n-{5,}.*?)(?=\n-{5,}|\Z)",
            text,
            re.I | re.S,
        )
        if dashed_block:
            return dashed_block.group(1).strip()

        note_headings = [
            "HINWEISE ZUR ANLEITUNG",
            "HINWEISE ZU ANLEITUNG",
            "HINWEIS ZUR ANLEITUNG",
            "PATTERN NOTES",
            "NOTES FOR THE PATTERN",
            "NOTES FOR INSTRUCTIONS",
        ]

        lines = [line.rstrip() for line in text.splitlines()]
        start_index: int | None = None
        for idx, line in enumerate(lines):
            upper = line.strip().upper()
            if any(heading in upper for heading in note_headings):
                start_index = idx
                break

        if start_index is None:
            return None

        stop_headings = {
            "GRÖSSEN",
            "GROESSEN",
            "SIZE",
            "SIZES",
            "MATERIAL",
            "MATERIALS",
            "GARN",
            "YARN",
            "NADELN",
            "NEEDLES",
            "MASCHENPROBE",
            "GAUGE",
            "ABMESSUNGEN",
            "MEASUREMENTS",
        }

        collected: list[str] = []
        for line in lines[start_index:]:
            stripped = line.strip()
            if stripped:
                label = stripped[:-1] if stripped.endswith(":") else stripped
                if label.isupper() and label in stop_headings:
                    break
            collected.append(line)

        notes = "\n".join(collected).strip()
        return notes or None

    def _extract_steps(self, soup: BeautifulSoup) -> list[dict[str, Any]]:
        """Extract pattern instructions as steps."""
        steps: list[dict[str, Any]] = []

        if self.is_garnstudio:
            steps = self._extract_garnstudio_steps(soup)
            if steps:
                return steps

        # Look for numbered lists or instruction sections
        candidates = [
            # Specific ID/Class matches first (Garnstudio uses pattern-instructions)
            soup.find(["div", "section"], class_="pattern-instructions"),
            soup.find(["div", "section"], class_="instructions"),
            soup.find(["div", "section"], id="instructions"),
            soup.find(["div", "section"], id="pattern-instructions"),
            # Regex matches (stricter)
            soup.find(["div", "section"], class_=re.compile(r"instruction(s)?$", re.I)),
            soup.find(["div", "section"], id=re.compile(r"pattern[_-]?text", re.I)),
            soup.find(["div", "section"], class_=re.compile(r"pattern[_-]?text", re.I)),
            # Fallbacks
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

            # Try to find headings or mixed content
            if not steps:
                steps = self._extract_mixed_content_steps(instructions_section)

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

    def _extract_mixed_content_steps(self, container: Tag) -> list[dict[str, Any]]:
        """
        Extract steps by traversing the DOM mixed content (text, images, headings).
        This fallback handles cases where steps aren't clearly structured with top-level
        headings.
        """
        steps: list[dict[str, Any]] = []
        current_title: str | None = None
        current_lines: list[str] = []
        current_images: list[str] = []

        def flush() -> None:
            nonlocal current_title, current_lines, current_images
            description = " ".join(current_lines).strip()

            # Don't save empty steps unless they have images
            if not description and not current_images:
                return

            step_number = len(steps) + 1
            steps.append(
                {
                    "step_number": step_number,
                    "title": current_title or f"Step {step_number}",
                    "description": description,
                    "images": list(current_images),
                }
            )
            # Reset for next step
            current_title = None
            current_lines = []
            current_images = []

        def process_node(node: Any) -> None:
            nonlocal current_title

            if isinstance(node, NavigableString):
                text = str(node).strip()
                if not text:
                    return

                # Check if this text line looks like a title (e.g. "NECK:")
                # Heuristic: ends with colon, short-ish, or all caps
                is_title = False
                if text.endswith(":") and len(text) < 50:
                    is_title = True
                elif text.isupper() and len(text) < 50 and len(text) > 3:
                    is_title = True

                if is_title:
                    flush()
                    current_title = text.rstrip(":").strip()
                else:
                    if set(text) <= {"-", "_"}:  # Separator lines
                        return
                    current_lines.append(text)
                return

            if isinstance(node, Tag):
                if node.name in ["script", "style", "noscript"]:
                    return

                if node.name in ["h1", "h2", "h3", "h4", "h5", "h6"]:
                    flush()
                    current_title = node.get_text(strip=True)
                    return  # Don't process children (text is already title)

                if node.name == "br":
                    pass

                if node.name == "img":
                    src = node.get("src") or node.get("data-src")
                    if src:
                        resolved = self._resolve_image_url(src)
                        if resolved:
                            current_images.append(resolved)
                    return

                # Recurse for other tags (div, span, p, etc)
                for child in node.children:
                    process_node(child)

        # Start traversal
        process_node(container)
        flush()

        return steps

    def _extract_garnstudio_steps(self, soup: BeautifulSoup) -> list[dict[str, Any]]:
        text = self._extract_garnstudio_text(soup)
        if not text:
            return []

        normalized_text = self._normalize_garnstudio_text(text)
        lines = [line.rstrip() for line in normalized_text.splitlines()]
        paragraphs: list[str] = []
        current: list[str] = []

        def flush() -> None:
            nonlocal current
            if current:
                paragraph = "\n".join(current).strip()
                if paragraph:
                    paragraphs.append(paragraph)
            current = []

        for line in lines:
            stripped = line.strip()
            if not stripped:
                flush()
                continue

            if current:
                prev = current[-1]
                if prev.endswith("-"):
                    current[-1] = prev[:-1] + stripped
                    continue
                if stripped.startswith("(") or stripped[:1].islower():
                    current[-1] = f"{current[-1]} {stripped}".strip()
                    continue
                if prev.endswith("("):
                    current[-1] = f"{current[-1]} {stripped}".strip()
                    continue

            current.append(stripped)

        flush()

        merged: list[str] = []
        index = 0
        while index < len(paragraphs):
            paragraph = paragraphs[index]
            if index + 1 < len(paragraphs):
                next_paragraph = paragraphs[index + 1]
                if (
                    paragraph.isupper()
                    and len(paragraph) < 40
                    and (
                        next_paragraph.startswith("(")
                        or next_paragraph[:1].islower()
                        or len(next_paragraph) < 40
                    )
                ):
                    merged.append(f"{paragraph}\n{next_paragraph}".strip())
                    index += 2
                    continue
            merged.append(paragraph)
            index += 1

        steps: list[dict[str, Any]] = []
        for i, paragraph in enumerate(merged, 1):
            steps.append(
                {
                    "step_number": i,
                    "title": f"Step {i}",
                    "description": paragraph,
                    "images": [],
                }
            )
        return steps

    def _normalize_garnstudio_text(self, text: str) -> str:
        normalized = text.replace("\r\n", "\n").replace("\r", "\n")
        headings = [
            "HINWEISE ZUR ANLEITUNG",
            "HINWEISE ZU ANLEITUNG",
            "HINWEIS ZUR ANLEITUNG",
            "MUSTER",
            "ZUNAHMETIPP",
            "ABNAHMETIPP",
            "MASCHENPROBE",
            "GROESSEN",
            "GRÖSSEN",
            "GARN",
            "MATERIAL",
            "NADELN",
            "RIPPEN",
            "ABNAHMEN",
            "ZUNAHMEN",
            "ARM",
            "RAGLAN",
        ]

        for heading in headings:
            normalized = re.sub(
                rf"\\s*({re.escape(heading)}\\s*:)",
                r"\n\n\\1",
                normalized,
                flags=re.I,
            )

        return normalized

    def _resolve_image_url(self, src: str | AttributeValueList) -> str | None:
        if isinstance(src, list):
            if not src:
                return None
            cleaned = " ".join(src).strip()
        else:
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
                    if int(str(width)) < 128 or int(str(height)) < 128:
                        if not self._allow_small_image(img, candidates):
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

        if self.is_garnstudio:
            for anchor in soup.find_all("a"):
                href = anchor.get("href")
                if not href or not isinstance(href, str):
                    continue
                if not self._looks_like_image_url(href):
                    continue
                resolved = self._resolve_image_url(href)
                if not resolved or resolved in seen:
                    continue
                images.append(resolved)
                seen.add(resolved)

        return images[:10]  # Limit to 10 images

    def _allow_small_image(self, img: Tag, candidates: list[str]) -> bool:
        if not self.is_garnstudio:
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

    def _looks_like_image_url(self, url: str) -> bool:
        lower = url.lower()
        if any(token in lower for token in ["diagram", "chart", "schema", "schem"]):
            return True
        path = urlparse(lower).path
        return path.endswith((".png", ".jpg", ".jpeg", ".gif", ".webp"))

    def _extract_garnstudio_text(self, soup: BeautifulSoup) -> str:
        candidates = [
            soup.find(
                ["div", "section"], class_=re.compile(r"pattern-instructions", re.I)
            ),
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


class GarnstudioPatternImporter(PatternImporter):
    """Importer with Garnstudio-specific extraction rules."""

    def __init__(self, url: str, timeout: int = 10) -> None:
        super().__init__(url, timeout)
        self.is_garnstudio = True
