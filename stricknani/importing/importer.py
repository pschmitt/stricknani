"URL import utilities for extracting knitting pattern data."

from __future__ import annotations

import html
import logging
import re
from collections.abc import Sequence
from dataclasses import dataclass
from io import BytesIO
from pathlib import Path
from typing import TYPE_CHECKING, Any
from urllib.parse import parse_qs, urljoin, urlparse

import anyio
import httpx
from bs4 import BeautifulSoup
from bs4.element import AttributeValueList, NavigableString, PageElement, Tag
from PIL import Image as PilImage

if TYPE_CHECKING:
    from stricknani.utils.image_similarity import SimilarityImage

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


def is_garnstudio_url(url: str) -> bool:
    """Public wrapper for Garnstudio URL detection."""
    return _is_garnstudio_url(url)


def trim_import_strings(value: Any) -> Any:
    """Recursively trim leading/trailing whitespace from imported strings."""
    if isinstance(value, str):
        return value.strip()
    if isinstance(value, list):
        return [trim_import_strings(item) for item in value]
    if isinstance(value, dict):
        return {key: trim_import_strings(item) for key, item in value.items()}
    return value


# Import image constants from centralized location
from stricknani.importing.images.constants import (  # noqa: E402
    IMPORT_ALLOWED_IMAGE_EXTENSIONS,
    IMPORT_IMAGE_HEADERS,
    IMPORT_IMAGE_MAX_BYTES,
    IMPORT_IMAGE_MAX_COUNT,
    IMPORT_IMAGE_MIN_DIMENSION,
    IMPORT_IMAGE_SSIM_THRESHOLD,
    IMPORT_IMAGE_TIMEOUT,
)
from stricknani.importing.images.validator import (  # noqa: E402
    is_allowed_import_image,
    is_valid_import_url,
)


@dataclass
class _FilteredImageCandidate:
    url: str
    similarity: SimilarityImage


async def filter_import_image_urls(
    image_urls: Sequence[str],
    *,
    referer: str | None = None,
    skip_checksums: set[str] | None = None,
    skip_similarities: Sequence[SimilarityImage] | None = None,
    limit: int = IMPORT_IMAGE_MAX_COUNT,
) -> list[str]:
    """Filter import image URLs by validity, size, and similarity."""
    if not image_urls:
        return []

    from stricknani.utils.image_similarity import (
        build_similarity_image,
        compute_similarity_score,
    )

    logger = logging.getLogger("stricknani.imports")
    headers = dict(IMPORT_IMAGE_HEADERS)
    if referer:
        headers["Referer"] = referer

    accepted: list[_FilteredImageCandidate] = []
    existing_similarities: list[SimilarityImage] = list(skip_similarities or [])

    async with httpx.AsyncClient(
        timeout=IMPORT_IMAGE_TIMEOUT,
        follow_redirects=True,
        headers=headers,
    ) as client:
        for image_url in image_urls:
            if len(accepted) >= limit:
                break
            if not is_valid_import_url(image_url):
                logger.info("Skipping invalid image URL: %s", image_url)
                continue

            try:
                response = await client.get(image_url)
                response.raise_for_status()
            except httpx.HTTPError as exc:
                logger.info("Skipping unreachable image %s: %s", image_url, exc)
                continue

            content_type = response.headers.get("content-type")
            if not is_allowed_import_image(content_type, image_url):
                logger.info("Skipping non-image URL: %s", image_url)
                continue

            content_length = response.headers.get("content-length")
            if content_length:
                try:
                    if int(content_length) > IMPORT_IMAGE_MAX_BYTES:
                        logger.info("Skipping large image %s", image_url)
                        continue
                except ValueError:
                    pass

            if not response.content:
                logger.info("Skipping empty image response: %s", image_url)
                continue

            if len(response.content) > IMPORT_IMAGE_MAX_BYTES:
                logger.info("Skipping large image %s", image_url)
                continue

            if skip_checksums:
                from stricknani.utils.files import compute_checksum

                checksum = compute_checksum(response.content)
                if checksum in skip_checksums:
                    logger.info("Skipping already imported image %s", image_url)
                    continue

            try:

                def _inspect_image(
                    content: bytes,
                ) -> tuple[int, int, SimilarityImage | None]:
                    with PilImage.open(BytesIO(content)) as img:
                        width, height = img.size
                        width_i = int(width)
                        height_i = int(height)
                        if (
                            width_i < IMPORT_IMAGE_MIN_DIMENSION
                            or height_i < IMPORT_IMAGE_MIN_DIMENSION
                        ):
                            return width_i, height_i, None
                        return width_i, height_i, build_similarity_image(img)

                width, height, similarity = await anyio.to_thread.run_sync(
                    _inspect_image,
                    response.content,
                )
                if similarity is None:
                    logger.info(
                        "Skipping small image %s (%sx%s)",
                        image_url,
                        width,
                        height,
                    )
                    continue
            except Exception as exc:
                logger.info("Skipping unreadable image %s: %s", image_url, exc)
                continue

            is_duplicate_existing = False
            for existing in existing_similarities:
                score = compute_similarity_score(existing, similarity)
                if score is None or score < IMPORT_IMAGE_SSIM_THRESHOLD:
                    continue
                logger.info(
                    "Skipping already imported image %s (ssim %.3f)",
                    image_url,
                    score,
                )
                is_duplicate_existing = True
                break

            if is_duplicate_existing:
                continue

            skip_thumbnail = False
            to_remove: list[_FilteredImageCandidate] = []
            for candidate in accepted:
                score = compute_similarity_score(candidate.similarity, similarity)
                if score is None or score < IMPORT_IMAGE_SSIM_THRESHOLD:
                    continue
                if similarity.pixels <= candidate.similarity.pixels:
                    logger.info(
                        "Skipping thumbnail image %s (ssim %.3f)",
                        image_url,
                        score,
                    )
                    skip_thumbnail = True
                    break
                to_remove.append(candidate)

            if skip_thumbnail:
                continue

            for entry in to_remove:
                accepted.remove(entry)

            accepted.append(
                _FilteredImageCandidate(
                    url=image_url,
                    similarity=similarity,
                )
            )

    return [candidate.url for candidate in accepted]


class PatternImporter:
    """Extract knitting pattern data from URLs."""

    def __init__(self, url: str, timeout: int = 10) -> None:
        """Initialize with URL to import."""
        self.url = url
        self.timeout = timeout
        self.is_garnstudio = is_garnstudio_url(url)
        self._garnstudio_gauge_cache: tuple[int | None, int | None] | None = None
        self._last_soup: BeautifulSoup | None = None

    async def fetch_and_parse(self, image_limit: int = 10) -> dict[str, Any]:
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
        self._last_soup = soup

        yarn_text = self._extract_yarn(soup)
        yarn_details = None
        if self.is_garnstudio and yarn_text:
            # Collect yarn links for Garnstudio
            yarn_links = self._extract_garnstudio_yarn_links(soup)
            if yarn_links:
                # Prefer links over text splitting. Garnstudio patterns often contain
                # "Oder:" alternative yarns and other notions in the material block,
                # and we do not want to auto-create a bunch of yarn entries.
                yarn_details = []
                for info in yarn_links.values():
                    yarn_details.append(
                        {
                            "name": info.get("name"),
                            "brand": "Garnstudio",
                            "colorway": None,
                            "weight": None,
                            "link": info.get("link"),
                            "image_url": info.get("image_url"),
                        }
                    )
            else:
                # If multiple yarns, split and parse each
                yarn_lines = yarn_text.split("\n")
                yarn_details = []
                for line in yarn_lines:
                    if not line.strip():
                        continue
                    detail = self._parse_garnstudio_yarn_string(line)
                    match_key = self._match_garnstudio_yarn_link_name(line, yarn_links)
                    name = detail.get("name")
                    if match_key and match_key in yarn_links:
                        info = yarn_links[match_key]
                        detail["name"] = info.get("name") or name
                        detail["link"] = info.get("link")
                        detail["image_url"] = info.get("image_url")
                    elif name and name.lower() in yarn_links:
                        info = yarn_links[name.lower()]
                        detail["name"] = info.get("name") or name
                        detail["link"] = info.get("link")
                        detail["image_url"] = info.get("image_url")
                    yarn_details.append(detail)

        # Pre-clean Garnstudio soup to remove UI noise globally
        if self.is_garnstudio:
            # Targeted noise removal
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

            # Remove sections by heading
            noise_keywords = [
                "vielleicht gefällt",
                "you might also like",
                "brauchen sie hilfe",
                "need some help",
                "schritt-für-schritt",
                "step-by-step",
                "anleitungen für",
                "patterns for",
                "anleitungen mit",
                "patterns with",
                "andere garne",
                "other yarns",
                "andere qualitäten",
                "other qualities",
            ]
            for heading in soup.find_all(["h2", "h3", "h4", "h5", "p"]):
                if heading.name == "p" and not heading.find("strong"):
                    continue

                text = heading.get_text().lower()
                if any(x in text for x in noise_keywords):
                    # Find parent row or container and decompose
                    parent = heading.find_parent("div", class_="row")
                    if parent:
                        # Decompose this row and potentially the next one
                        # if it's a related content block
                        nxt = parent.find_next_sibling("div", class_="row")
                        if nxt and (
                            nxt.select(".img-rel") or nxt.select(".ratio-16-9")
                        ):
                            nxt.decompose()
                        parent.decompose()
                    else:
                        heading.decompose()

        steps = self._extract_steps(soup)
        images = self._extract_images(soup)
        description = self._extract_description(soup)
        if self.is_garnstudio:
            if "yarn.php" in self.url:
                description_parts = []
                # 1. Subtitle from <title> tag
                # e.g. DROPS Kid-Silk - Eine wunderbare Mischung aus Kid Mohair und
                # Seide
                title_tag = soup.find("title")
                if title_tag:
                    title_text = title_tag.get_text()
                    if " - " in title_text:
                        subtitle = title_text.split(" - ", 1)[1].strip()
                        description_parts.append(subtitle)

                # 2. Technical specs
                specs = self._extract_garnstudio_yarn_specs(soup)
                if specs:
                    description_parts.append(specs)

                # 3. "About this yarn" section
                about_container = soup.select_one("#about") or soup.select_one(
                    ".yarn-description"
                )
                if about_container:
                    import trafilatura

                    about_text = trafilatura.extract(
                        about_container.decode(), include_comments=False
                    )
                    if not about_text:
                        about_text = about_container.get_text(
                            separator="\n\n", strip=True
                        )
                    if about_text:
                        description_parts.append(about_text)

                if description_parts:
                    description = "\n\n".join(description_parts)
            else:
                # For patterns, try to get the subtitle from the <title> tag
                title_tag = soup.find("title")
                if title_tag:
                    title_text = title_tag.get_text()
                    if " - " in title_text:
                        subtitle = title_text.split(" - ", 1)[1].strip()
                        if description:
                            description = f"{subtitle}\n\n{description}"
                        else:
                            description = subtitle

                garn_notes = self._extract_garnstudio_notes(soup)
                if garn_notes:
                    # For Garnstudio, the technical notes are the most important part
                    # of the description.
                    if description:
                        description = f"{description}\n\n{garn_notes}"
                    else:
                        description = garn_notes

        notes = None
        image_urls = images

        # Deduplicate images: remove images that are already in steps
        step_images = set()
        for step in steps:
            for img in step.get("images", []):
                step_images.add(img)

        if step_images:
            image_urls = [img for img in images if img not in step_images]

        if image_limit > 0:
            image_urls = image_urls[:image_limit]

        data: dict[str, Any] = {
            "title": self._extract_title(soup),
            "name": yarn_text,
            "needles": self._extract_needles(soup),
            "yarn": yarn_text,
            "yarn_details": yarn_details,
            "brand": self._extract_brand(soup),
            "fiber_content": self._extract_fiber_content(soup),
            "colorway": self._extract_colorway(soup),
            "weight_grams": self._extract_weight_grams(soup),
            "length_meters": self._extract_length_meters(soup),
            "weight_category": self._extract_weight_category(soup),
            "stitch_sample": self._extract_stitch_sample(soup),
            "description": description,
            "notes": notes,
            "steps": steps,
            "link": self.url,
            "image_urls": image_urls,
        }

        logger.info(
            "Import extracted title=%s needles=%s yarn=%s category=%s "
            "steps=%s images=%s",
            data.get("title"),
            data.get("needles"),
            data.get("yarn"),
            data.get("weight_category"),
            len(steps),
            len(image_urls),
        )

        # Decode HTML entities in all string fields
        from typing import cast

        return cast(dict[str, Any], self._unescape_data(data))

    def _unescape_data(self, data: Any) -> Any:
        """Recursively unescape HTML entities in data."""
        if isinstance(data, str):
            return html.unescape(data)
        if isinstance(data, list):
            return [self._unescape_data(item) for item in data]
        if isinstance(data, dict):
            return {k: self._unescape_data(v) for k, v in data.items()}
        return data

    def _extract_title(self, soup: BeautifulSoup) -> str | None:
        """Extract pattern title or yarn name."""
        # Try various title patterns
        patterns = [
            soup.find("h1", class_=re.compile(r"pattern|title|name", re.I)),
            soup.find("h1"),
            soup.find("meta", property="og:title"),
            soup.find("title"),
        ]

        title = None
        for pattern in patterns:
            if pattern:
                if hasattr(pattern, "name") and pattern.name == "meta":
                    content = pattern.get("content")
                    if isinstance(content, str):
                        title = content
                        break
                text = pattern.get_text(strip=True)
                if text and len(text) > 3:
                    title = text
                    break

        if title and any(x in title.lower() for x in ["yarn", "garn", "wolle", "ball"]):
            return self._clean_yarn_name(title)
        return title

    def _extract_needles(self, soup: BeautifulSoup) -> str | None:
        """Extract needle information."""
        if self.is_garnstudio:
            val = self._extract_garnstudio_info_by_heading(
                soup,
                [
                    "NADELN",
                    "NEEDLES",
                    "PINNER",
                    "HÄKELNADEL",
                    "HÄKELNADELN",
                    "CROCHET HOOK",
                    "CROCHET HOOKS",
                    "HEKLENÅL",
                    "HEKLENÅLER",
                ],
            )
            if val:
                return val

            # Some Garnstudio patterns list needles inside the "Material" block without
            # a dedicated "NADELN" heading. Prefer parsing from there over generic
            # label matching, which can pick up navigation/category noise.
            material_needles = self._extract_garnstudio_needles_from_material(soup)
            if material_needles:
                return material_needles

        # Try finding by label first
        val = self._find_info_by_label(
            soup,
            [
                "nadelstärke",
                "needles",
                "needle size",
                "nadeln",
                "empfohlene nadelstärke",
                "recommended needle size",
                "anbefalt pinnestørrelse",
            ],
        )
        if val:
            cleaned = val.strip()
            # Guard against known Garnstudio UI/category noise such as
            # "Stricknadeln & Häkelnadeln" leaking into the extraction.
            if cleaned.startswith("&"):
                return None
            if "häkelnadeln" in cleaned.lower() and not any(
                c.isdigit() for c in cleaned
            ):
                return None
            return cleaned

        patterns = [
            r"(?:needle[s]?|nadelstärke|nadeln)\s*[:：]\s*([^\n]+)",
            r"([0-9.]+\s*mm)",
            r"(US\s*\d+)",
        ]

        text = soup.get_text()
        for pattern in patterns:
            match = re.search(pattern, text, re.I)
            if match:
                return match.group(1).strip()

        return None

    def _extract_garnstudio_needles_from_material(
        self, soup: BeautifulSoup
    ) -> str | None:
        material = soup.find(id=re.compile(r"material_text(_print)?"))
        if not material:
            material = soup.select_one(".pattern-material")
        if not material:
            return None

        text = material.get_text(separator="\n", strip=True)
        lines = [line.strip() for line in text.splitlines() if line.strip()]

        blocks: list[str] = []
        current: list[str] = []
        for line in lines:
            upper = line.upper()
            if upper.startswith("DROPS "):
                if current:
                    blocks.append(" ".join(current))
                current = [line]
                continue
            if current:
                current.append(line)
        if current:
            blocks.append(" ".join(current))

        needle_blocks: list[str] = []
        for block in blocks:
            lower = block.lower()
            if any(
                kw in lower
                for kw in [
                    "nadel",
                    "needle",
                    "rundnadel",
                    "nadelspiel",
                    "häkelnadel",
                    "crochet",
                    "hook",
                    "pinner",
                ]
            ) and not any(kw in lower for kw in ["knopf", "button"]):
                needle_blocks.append(block)

        res = " ".join(needle_blocks).strip()
        return res or None

    def _extract_yarn(self, soup: BeautifulSoup) -> str | None:
        """Extract yarn information."""
        if self.is_garnstudio:
            # For Garnstudio yarn pages, the title is usually the best source
            if "yarn.php" in self.url:
                title = self._extract_title(soup)
                if title:
                    return self._clean_yarn_name(title)

            yarn = self._extract_garnstudio_yarn(soup)
            if yarn:
                return yarn

        patterns = [
            r"yarn[s]?\s*[:：]\s*([^\n]+)",
            r"material[s]?\s*[:：]\s*([^\n]+)",
        ]

        text = soup.get_text()
        yarn_text = None
        for pattern in patterns:
            match = re.search(pattern, text, re.I)
            if match:
                val = match.group(1).strip()
                # Skip known Garnstudio noise if it matched the material pattern
                if self.is_garnstudio and any(
                    x in val.lower() for x in ["herkunft", "rohmapterial", "made in"]
                ):
                    continue

                yarn_text = val
                # If the match looks like a whole paragraph, skip it.
                if len(yarn_text) > 150:
                    yarn_text = None
                    continue
                break

        if not yarn_text:
            # Try to use product name if we are on a yarn product page
            title = self._extract_title(soup)
            if title and any(
                x in title.lower() for x in ["yarn", "garn", "wolle", "ball"]
            ):
                yarn_text = title

        return self._clean_yarn_name(yarn_text)

    def _clean_yarn_name(self, name: str | None) -> str | None:
        """Strip weight and length specs from yarn name."""
        if not name:
            return name

        # If it's a Garnstudio pattern, use the more sophisticated parser
        if self.is_garnstudio:
            details = self._parse_garnstudio_yarn_string(name)
            return details["name"]

        # Remove patterns like "100g", "300m", "50 g", "100 g", "300 m"
        # Also handles "100g/300m" or similar
        # \b doesn't always work with / so we use a more inclusive pattern
        patterns = [
            r"\d+\s*g\b",
            r"\d+\s*m\b",
            r"\d+\s*oz\b",
            r"\d+\s*yds?\b",
            r"\d+\s*yards\b",
            r"\bvon\s+Garnstudio\b",
            r"\bby\s+Garnstudio\b",
        ]

        cleaned = name
        for p in patterns:
            cleaned = re.sub(p, "", cleaned, flags=re.I)

        # Handle leftover separators like / or - at the end or mid-string
        cleaned = re.sub(r"\s*[/|:-]\s*", " ", cleaned)
        # Final trim of any remaining punctuation at the end
        cleaned = re.sub(r"[\s/|:-]+$", "", cleaned)

        # Clean up whitespace
        return " ".join(cleaned.split()).strip()

    def _parse_garnstudio_yarn_string(self, yarn_str: str) -> dict[str, str | None]:
        """Parse a complex Garnstudio yarn string into its components.

        Example: DROPS BRUSHED ALPACA SILK von Garnstudio (gehört zur Garngruppe C)
        100-125-125-150-150-175 g Farbe 22, hellrostrot
        """
        data: dict[str, str | None] = {
            "name": yarn_str,
            "brand": "Garnstudio",
            "colorway": None,
            "weight": None,
        }

        cleaned = yarn_str.replace("\n", " ").strip()

        # 1. Extract Colorway (Farbe ...)
        color_pat = r"(?:Farbe|Color|Farge)\s+([^,]+(?:,\s*[^,]+)?)"
        color_match = re.search(color_pat, cleaned, re.I)
        if color_match:
            data["colorway"] = color_match.group(1).strip()
            cleaned = cleaned[: color_match.start()] + cleaned[color_match.end() :]

        # 2. Extract Brand info (von Garnstudio / by Garnstudio)
        brand_match = re.search(r"\b(?:von|by|av)\s+Garnstudio\b", cleaned, re.I)
        if brand_match:
            data["brand"] = "Garnstudio"
            cleaned = cleaned[: brand_match.start()] + cleaned[brand_match.end() :]

        # 3. Extract Yarn Group (gehört zur Garngruppe ...)
        group_pat = r"\(?(?:gehört zur |belongs to )?Garngruppe\s+[A-F]\)?"
        group_match = re.search(group_pat, cleaned, re.I)
        if group_match:
            cleaned = cleaned[: group_match.start()] + cleaned[group_match.end() :]

        # 4. Extract weight/amount (100-125 g)
        weight_match = re.search(r"[\d-]+\s*g\b", cleaned, re.I)
        if weight_match:
            data["weight"] = weight_match.group(0).strip()
            cleaned = cleaned[: weight_match.start()] + cleaned[weight_match.end() :]

        # 5. Clean up the remaining name
        # Handle "Oder:" (alternative yarn)
        cleaned = re.sub(r"\bOder:\s*", "", cleaned, flags=re.I)
        # Strip "DROPS" prefix if it exists
        cleaned = re.sub(r"\bDROPS\s+", "", cleaned, flags=re.I)
        # Handle subtitles in title (e.g. DROPS Kid-Silk - Eine wunderbare...)
        if " - " in cleaned:
            cleaned = cleaned.split(" - ", 1)[0]
        # Remove trailing/leading punctuation and extra whitespace
        cleaned = re.sub(r"^\s*[:,-]+", "", cleaned)
        cleaned = re.sub(r"[:,-]+\s*$", "", cleaned)

        data["name"] = " ".join(cleaned.split()).strip()

        return data

    def _extract_brand(self, soup: BeautifulSoup) -> str | None:
        """Extract brand/manufacturer information."""
        # 1. Try to find known brands in the title first.
        # This is extremely reliable for shop product pages.
        title = self._extract_title(soup)
        if title:
            known_brands = [
                "Rico Design",
                "DROPS",
                "Garnstudio",
                "Lana Grossa",
                "Lang Yarns",
                "Sandnes Garn",
                "Schachenmayr",
                "Pascuali",
                "Rosy Green Wool",
            ]
            for brand in known_brands:
                if brand.lower() in title.lower():
                    return brand

        # 2. Try technical info labels
        val = self._find_info_by_label(
            soup, ["brand", "manufacturer", "hersteller", "marke"]
        )
        if val:
            return val

        patterns = [
            r"(?:brand|manufacturer|hersteller|marke)\s*[:：]\s*([^\n<]+)",
        ]
        text = soup.get_text()
        for pattern in patterns:
            match = re.search(pattern, text, re.I)
            if match:
                res = match.group(1).strip()
                if not self._is_ui_text(res):
                    return res

        # 3. Try meta
        meta_brand = soup.find("meta", property="product:brand")
        if meta_brand:
            content = meta_brand.get("content")
            if isinstance(content, str) and not self._is_ui_text(content):
                return content.strip()

        # 4. Fallback for Garnstudio
        if self.is_garnstudio:
            if title and "drops" in title.lower():
                return "DROPS"
            return "Garnstudio"

        return None

    def _extract_fiber_content(self, soup: BeautifulSoup) -> str | None:
        """Extract fiber content / composition."""
        val = self._find_info_by_label(
            soup, ["zusammensetzung", "fiber content", "composition", "material"]
        )
        if val:
            return val

        patterns = [
            r"(?:fiber content|composition|zusammensetzung|material)"
            r"\s*[:：]\s*([^\n<]+)",
        ]
        text = soup.get_text()
        for pattern in patterns:
            match = re.search(pattern, text, re.I)
            if match:
                return match.group(1).strip()
        return None

    def _extract_colorway(self, soup: BeautifulSoup) -> str | None:
        """Extract colorway/color information."""
        val = self._find_info_by_label(
            soup, ["colorway", "color", "farbe", "farbbezeichnung"]
        )
        if val:
            return val

        patterns = [
            r"(?:colorway|color|farbe|farbbezeichnung)\s*[:：]\s*([^\n<]+)",
        ]
        text = soup.get_text()
        for pattern in patterns:
            match = re.search(pattern, text, re.I)
            if match:
                return match.group(1).strip()

        # Try to extract from title if it has bars or separators
        title = self._extract_title(soup)
        if title and "|" in title:
            parts = [p.strip() for p in title.split("|")]
            if len(parts) >= 2:
                # Often it's Name | Color | ID
                return parts[1]

        return None

    def _extract_weight_grams(self, soup: BeautifulSoup) -> int | None:
        """Extract weight in grams."""
        # Try labeled search first
        val = self._find_info_by_label(
            soup,
            [
                "gewicht",
                "ball weight",
                "weight",
                "gewicht / lauflänge",
                "weight / yardage",
                "vekt / løpelengde",
            ],
        )
        if val:
            match = re.search(r"(\d+)\s*g", val, re.I)
            if not match:
                match = re.search(r"(\d+)", val)
            if match:
                return int(match.group(1))

        text = soup.get_text()

        # Look for patterns like "300m / 100g" or "300m pro 100g"
        complex_patterns = [
            r"(\d+)\s*m\s*/\s*(\d+)\s*g",
            r"(\d+)\s*m\s*pro\s*(\d+)\s*g",
        ]
        for p in complex_patterns:
            match = re.search(p, text, re.I)
            if match:
                return int(match.group(2))

        patterns = [
            r"(\d+)\s*g(?:\s|/|$)",
            r"weight\s*[:：]\s*(\d+)\s*g",
        ]
        for pattern in patterns:
            match = re.search(pattern, text, re.I)
            if match:
                try:
                    return int(match.group(1))
                except ValueError:
                    pass
        return None

    def _extract_length_meters(self, soup: BeautifulSoup) -> int | None:
        """Extract length in meters."""
        # Try labeled search first
        val = self._find_info_by_label(
            soup,
            [
                "lauflänge",
                "yardage",
                "length",
                "laufweite",
                "gewicht / lauflänge",
                "weight / yardage",
                "vekt / løpelengde",
            ],
        )
        if val:
            # Look for patterns like "210 m" or "210 Meter"
            match = re.search(r"(\d+)\s*(?:m|meter|yards?|yds?)\b", val, re.I)
            if not match:
                match = re.search(r"(\d+)", val)
            if match:
                return int(match.group(1))

        text = soup.get_text()

        # Look for patterns like "300m / 100g"
        complex_patterns = [
            r"(\d+)\s*m\s*/\s*(\d+)\s*g",
            r"(\d+)\s*m\s*pro\s*(\d+)\s*g",
        ]
        for p in complex_patterns:
            match = re.search(p, text, re.I)
            if match:
                return int(match.group(1))

        patterns = [
            r"(\d+)\s*m(?:\s|/|$)",
            r"length\s*[:：]\s*(\d+)\s*m",
            r"lauflänge\s*[:：]\s*(\d+)\s*m",
            r"laufweite\s*[:：]\s*(\d+)\s*m",
        ]
        for pattern in patterns:
            match = re.search(pattern, text, re.I)
            if match:
                try:
                    return int(match.group(1))
                except ValueError:
                    pass
        return None

    def _is_ui_text(self, text: str | None) -> bool:
        """Check if text looks like UI labels/icons rather than actual data."""
        if not text:
            return False
        ui_hints = [
            "pfeil nach unten",
            "pfeil nach oben",
            "dropdown",
            "toggle",
            "menü",
            "menu",
            "suche",
            "search",
            "warenkorb",
            "login",
            "anmelden",
            "nadeln & häkelnadeln",
            "nadeln & h\u00e4kelnadeln",
            "garne & nadeln",
            "alle garne",
            "alle anschauen",
            "bestellen",
            "andere sprache",
            "drucken",
            "weitere infos",
            "pflegehinweise",
        ]
        lower_text = text.lower()
        return any(hint in lower_text for hint in ui_hints) or len(text) < 1

    def _find_info_by_label(self, soup: BeautifulSoup, labels: list[str]) -> str | None:
        """Find value associated with labels in the page text/structure."""
        # 1. Search for Label: Value in flat text
        text = soup.get_text(separator="\n", strip=True)
        for label in labels:
            # Match label at start of line optionally followed by colon
            # and then capture until end of line
            pattern = rf"(?m)^(?:{label})\s*[:：]?\s*([^\n]+)"
            match = re.search(pattern, text, re.I)
            if match:
                val = match.group(1).strip()
                if val and not self._is_ui_text(val):
                    return val

        # 2. Search for Label followed by Value in DT/DD or Tables or next siblings
        for label in labels:
            # Find element containing label precisely
            # Use a closure to capture the current label for the lambda
            def _match_tag(t: Any, lbl: str = label) -> bool:
                return (
                    t.name in ["th", "td", "dt", "span", "div", "b", "strong", "label"]
                    and lbl.lower() == t.get_text().strip().rstrip(":").lower()
                )

            target = soup.find(_match_tag)
            if not target:
                continue

            # If it's a TH/TD, look for next TD
            if target.name == "th":
                val = target.find_next_sibling("td")
                if val:
                    val_text = val.get_text().strip()
                    if not self._is_ui_text(val_text):
                        return val_text

            # If it's a DT, look for next DD
            if target.name == "dt":
                val = target.find_next_sibling("dd")
                if val:
                    val_text = val.get_text().strip()
                    if not self._is_ui_text(val_text):
                        return val_text

            # If it's a generic tag, check next meaningful sibling or child of parent
            # Some shops like Wolle Roedel put labels in a header/button
            # and value in a following div.
            # Garnstudio often has <b>Label:</b> Value<br>

            # 1. Check direct next sibling (often a string)
            sib = target.next_sibling
            if sib and isinstance(sib, NavigableString):
                val_text = str(sib).strip()
                if val_text and not self._is_ui_text(val_text):
                    return val_text

            # 2. Check next tag
            nxt = target.find_next()
            if nxt and nxt != target:
                val_text = nxt.get_text().strip()
                # If the next element is short, it's likely the value
                if val_text and len(val_text) < 255:
                    if not self._is_ui_text(val_text):
                        return val_text

        return None

    def _extract_stitch_sample(self, soup: BeautifulSoup) -> str | None:
        """Extract stitch sample / gauge info."""
        if self.is_garnstudio:
            val = self._extract_garnstudio_info_by_heading(
                soup, ["MASCHENPROBE", "GAUGE", "STRIKKEFASTHET"]
            )
            if val:
                return val

        headings = ["maschenprobe", "gauge", "stitch sample", "tension"]

        for heading_tag in soup.find_all(
            ["h1", "h2", "h3", "h4", "h5", "h6", "strong", "span", "b", "div"]
        ):
            h_text = heading_tag.get_text().strip().lower().rstrip(":")
            if any(h_text == h for h in headings):
                # 1. Start collecting from siblings
                collected: list[str] = []
                curr: PageElement = heading_tag
                for _ in range(25):
                    nxt = curr.next_sibling
                    if not nxt:
                        # Move up to parent's sibling if it's an inline container
                        if (
                            curr.parent
                            and isinstance(curr.parent, Tag)
                            and curr.parent.name in ["span", "b", "strong", "i", "a"]
                            and curr.parent != soup
                        ):
                            curr = curr.parent
                            continue
                        break

                    curr = nxt
                    text = ""
                    is_hard_block = False
                    if isinstance(nxt, NavigableString):
                        text = str(nxt).strip()
                    elif isinstance(nxt, Tag):
                        # Stop if we hit another real heading
                        if nxt.name in ["h1", "h2", "h3", "h4", "h5", "h6", "h7"]:
                            break

                        text = nxt.get_text(" ", strip=True)
                        is_hard_block = nxt.name in [
                            "p",
                            "div",
                            "section",
                            "article",
                            "hr",
                        ]

                    if not text:
                        if isinstance(nxt, Tag) and nxt.name == "br":
                            # We allow crossing one or two BRs
                            continue
                        continue

                    # If it's a known other heading, stop
                    lower_text = text.lower()
                    if any(
                        h in lower_text
                        for h in ["anleitung", "material", "nadeln", "size"]
                    ):
                        break

                    # If we encounter a hard block and we already have gauge info, stop.
                    if is_hard_block:
                        current_combined = " ".join(collected)
                        if self._looks_like_stitch_sample(current_combined):
                            break

                    collected.append(text)

                if collected:
                    combined = " ".join(collected).strip()
                    # Clean up multiple spaces
                    combined = " ".join(combined.split())
                    if self._looks_like_stitch_sample(combined):
                        return combined

        # 3. Fallback to label search
        val = self._find_info_by_label(soup, headings)
        if val:
            return " ".join(val.split())
        return None

    def _looks_like_stitch_sample(self, text: str) -> bool:
        """Check if text contains typical gauge information."""
        if not text or len(text) < 5:
            return False

        # Noise check
        lower = text.lower()
        if "aktualisiert" in lower or "korrigiert" in lower:
            return False

        # Metrics check (maschen, reihen, sts, rows, 10 cm)
        metrics = ["masche", "reihe", "sts", "row", "stitches"]
        has_metrics = any(kw in lower for kw in metrics)
        has_10cm = "10" in lower or "cm" in lower

        # Numbers check
        has_numbers = any(char.isdigit() for char in text)

        return (
            (has_metrics and has_numbers)
            or (has_metrics and has_10cm)
            or (has_10cm and has_numbers)
        )

    def _extract_description(self, soup: BeautifulSoup) -> str | None:
        """Extract pattern description."""
        # Try finding a description heading first
        description_headings = [
            "produktbeschreibung",
            "beschreibung",
            "description",
            "product description",
            "über das produkt",
        ]
        for heading_tag in soup.find_all(
            ["h1", "h2", "h3", "h4", "h5", "h6", "strong", "span"]
        ):
            h_text = heading_tag.get_text().strip().lower()
            if any(h_text == h for h in description_headings):
                # Try to find the next meaningful sibling
                curr = heading_tag
                # Go up a few levels if needed to find siblings
                for _ in range(3):
                    next_node = curr.find_next_sibling(["div", "p", "section"])
                    if next_node:
                        text = next_node.get_text("\n", strip=True)
                        if len(text) > 100:
                            return text
                    # If no direct sibling, look at children of parent
                    if curr.parent:
                        curr = curr.parent

        # Try meta description next - but ONLY if it looks complete
        meta_desc = soup.find("meta", {"name": "description"}) or soup.find(
            "meta", property="og:description"
        )
        if meta_desc:
            content = meta_desc.get("content")
            if (
                isinstance(content, str)
                and len(content) > 50
                and not content.endswith("...")
                and not content.endswith("…")
            ):
                return content.strip()

        # Try first long paragraph in article or main content
        # Prioritize specific IDs and classes first
        for tag in ["div", "section", "article", "main"]:
            container = soup.find(
                tag, id=re.compile(r"about|description", re.I)
            ) or soup.find(
                tag, class_=re.compile(r"yarn-description|product-info", re.I)
            )

            if container:
                p_tags = container.find_all("p")
                for p in p_tags:
                    text = p.get_text(strip=True)
                    if len(text) > 100:
                        return text

        # Fallback to more generic containers
        for tag in ["article", "main", "div", "section"]:
            container = soup.find(
                tag,
                class_=re.compile(r"description|content", re.I),
            )

            if container:
                p_tags = container.find_all("p")
                for p in p_tags:
                    text = p.get_text(strip=True)
                    if len(text) > 150:
                        return text

        # Fallback to meta if it's all we have and it's not too short
        if meta_desc:
            content = meta_desc.get("content")
            if isinstance(content, str) and len(content) > 30:
                return content.strip()

        return None

    def _extract_weight_category(self, soup: BeautifulSoup) -> str | None:
        """Extract yarn weight category (e.g. DK, Aran, Lace)."""
        # Look in title first as DK is common there
        title = self._extract_title(soup)
        if title:
            # Common yarn weight terms
            weights = [
                "lace",
                "fingering",
                "sport",
                "dk",
                "worsted",
                "aran",
                "bulky",
                "super bulky",
                "jumbo",
                "sock",
                "baby",
            ]
            for weight in weights:
                # Search with word boundaries for short terms like DK
                if re.search(rf"\b{weight}\b", title, re.I):
                    return weight.upper()

        # Look for explicit labels
        text = soup.get_text(separator=" ", strip=True)
        patterns = [
            r"(?:garnstärke|yarn\s*weight|weight|category)\s*[:：]?\s*(\b[a-z0-9-]{1,15}\b)",
        ]
        for pattern in patterns:
            match = re.search(pattern, text, re.I)
            if match:
                val = match.group(1).strip().upper()
                # Validate it's likely a weight term
                if val in [
                    "DK",
                    "ARAN",
                    "LACE",
                    "SPORT",
                    "WORSTED",
                    "BULKY",
                    "JUMBO",
                    "FINGERING",
                ]:
                    return val

        return None

    def _extract_garnstudio_yarn_specs(self, soup: BeautifulSoup) -> str | None:
        """Extract technical specifications for Garnstudio yarns."""
        specs = []

        target_labels = [
            "Zusammensetzung",
            "Garngruppe",
            "Garnguppe",  # Garnstudio typo
            "Gewicht / Lauflänge",
            "Empfohlene Nadelstärke",
            "Maschenprobe",
            "Pflege",
            "Superwash",
            "Made in",
            "Herkunft des Rohmaterials",
            # English
            "Composition",
            "Yarn group",
            "Weight / yardage",
            "Recommended needle size",
            "Gauge",
            "Care",
            "Origin of raw material",
            # Norwegian
            "Sammensetning",
            "Vekt / løpelengde",
            "Anbefalt pinnestørrelse",
            "Strikkefasthet",
            "Vask",
            "Opprinnelse av råmateriale",
        ]

        # Garnstudio typically uses <strong>Label:</strong> Value
        for p in soup.find_all("p"):
            strongs = p.find_all("strong")
            if not strongs:
                continue

            p_specs = []
            for strong in strongs:
                label_raw = strong.get_text(strip=True)
                label = label_raw.rstrip(":")

                label_lower = label.lower()
                if any(
                    target_label.lower() in label_lower
                    for target_label in target_labels
                ):
                    value_parts = []
                    curr = strong.next_sibling
                    while curr and (not isinstance(curr, Tag) or curr.name != "strong"):
                        if isinstance(curr, NavigableString):
                            value_parts.append(str(curr))
                        elif isinstance(curr, Tag):
                            if curr.name == "br":
                                break
                            value_parts.append(curr.get_text())
                        curr = curr.next_sibling

                    value = "".join(value_parts).strip().lstrip(":").strip()
                    if value:
                        # Clean up value (remove extra whitespace)
                        value = " ".join(value.split())
                        p_specs.append(f"**{label}:** {value}")

            if p_specs:
                specs.append("\n".join(p_specs))

        return "\n\n".join(specs) if specs else None

    def _get_garnstudio_gauge(
        self,
        soup: BeautifulSoup,
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

        normalized = self._normalize_garnstudio_text(text)
        lines = normalized.splitlines()

        tech_terms = {
            "TIPP",
            "TIP",
            "INFO",
            "MUSTER",
            "PATTERN",
            "KRAUSRIPPEN",
            "KNOPFLÖCHER",
            "ABNAHMEN",
            "ZUNAHMEN",
            "DECREASE",
            "INCREASE",
        }

        # Instruction start markers (must match _extract_garnstudio_steps)
        instruction_markers = [
            "DIE ARBEIT BEGINNT HIER",
            "START HERE",
            "KURZBESCHREIBUNG",
            "ANLEITUNG -",
            "KLEID",
            "PULLOVER",
            "JACKE",
            "SOCKEN",
            "MÜTZE",
            "HANDSCHUHE",
            "SCHAL",
            "TUCH",
            "DECKE",
            "KISSEN",
            "DRESS",
            "SWEATER",
            "JACKET",
            "CARDIGAN",
            "SOCKS",
            "HAT",
            "GLOVES",
            "SCARF",
            "SHAWL",
            "BLANKET",
            "CUSHION",
        ]

        def is_instruction_start(line_upper: str) -> bool:
            check_upper = line_upper.lstrip("#").rstrip(":").strip()
            if any(
                check_upper == marker or check_upper.startswith(f"{marker} -")
                for marker in instruction_markers
            ):
                return True

            # Handle compound headings like "HUNDEPULLOVER" which should be treated
            # as instruction starts (contains "PULLOVER" marker).
            return any(
                marker in check_upper
                for marker in [
                    "KLEID",
                    "PULLOVER",
                    "JACKE",
                    "SOCKEN",
                    "MÜTZE",
                    "HANDSCHUHE",
                    "SCHAL",
                    "TUCH",
                    "DECKE",
                    "KISSEN",
                    "DRESS",
                    "SWEATER",
                    "JACKET",
                    "CARDIGAN",
                    "SOCKS",
                    "HAT",
                    "GLOVES",
                    "SCARF",
                    "SHAWL",
                    "BLANKET",
                    "CUSHION",
                ]
            )

        def is_tech_heading(line_upper: str) -> bool:
            if not line_upper.startswith("### "):
                return False
            title_upper = line_upper[4:].strip().rstrip(":").strip().upper()
            return any(term in title_upper for term in tech_terms)

        # 1. Try to find section between HINWEISE ZUR ANLEITUNG and instructions
        start_index = -1
        stop_index = -1

        for i, line in enumerate(lines):
            upper = line.strip().upper()
            if "### HINWEISE ZUR ANLEITUNG" in upper:
                start_index = i

            # Stop early if we hit the instruction start before the notes heading.
            if start_index == -1 and is_instruction_start(upper):
                break

        if start_index != -1:
            # Find the first "real" instruction heading after the notes section.
            for j in range(start_index + 1, len(lines)):
                upper = lines[j].strip().upper()
                if is_instruction_start(upper):
                    stop_index = j
                    break

        if start_index != -1:
            if stop_index != -1:
                notes = "\n".join(lines[start_index + 1 : stop_index]).strip()
            else:
                notes = "\n".join(lines[start_index + 1 :]).strip()
            notes = re.sub(r"-{3,}", "", notes).strip()
            return notes or None

        # 2. Fallback: collect any technical tips found before instructions
        collected: list[str] = []
        collecting = False
        for line in lines:
            upper = line.strip().upper()
            if is_instruction_start(upper):
                break

            if upper.startswith("### "):
                if is_tech_heading(upper):
                    collecting = True
                    collected.append(line)
                    continue
                if collecting:
                    break
                continue

            if collecting and line.strip():
                collected.append(line)

        if collected:
            return "\n".join(collected).strip()

        return None

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

        # 1. Identify where instructions actually start
        # Garnstudio patterns usually have technical notes first, then materials,
        # then instructions starting with something like "DIE ARBEIT BEGINNT HIER"
        # or "KURZBESCHREIBUNG".
        instruction_markers = [
            "DIE ARBEIT BEGINNT HIER",
            "START HERE",
            "KURZBESCHREIBUNG",
            "ANLEITUNG -",
            "KLEID",
            "PULLOVER",
            "JACKE",
            "SOCKEN",
            "MÜTZE",
            "HANDSCHUHE",
            "SCHAL",
            "TUCH",
            "DECKE",
            "KISSEN",
            "DRESS",
            "SWEATER",
            "JACKET",
            "CARDIGAN",
            "SOCKS",
            "HAT",
            "GLOVES",
            "SCARF",
            "SHAWL",
            "BLANKET",
            "CUSHION",
        ]

        lines = text.splitlines()
        start_index = 0
        found = False
        for i, line in enumerate(lines):
            upper = line.strip().upper()
            # Strip Markdown header prefix and trailing colon if present
            check_upper = upper.lstrip("#").rstrip(":").strip()
            # Look for markers, either as standalone words or followed by "-"
            if any(
                check_upper == marker
                or check_upper.startswith(f"{marker} -")
                or marker in check_upper
                for marker in instruction_markers
            ):
                start_index = i
                found = True
                break

        # If we didn't find a clear marker, but we found "HINWEISE ZUR ANLEITUNG",
        # the instructions likely start after the technical notes.
        if not found:
            for i, line in enumerate(lines):
                if "HINWEISE ZUR ANLEITUNG" in line.upper():
                    # Look for the next heading that is NOT a technical note
                    for j in range(i + 1, len(lines)):
                        l_text = lines[j].strip()
                        is_tech = any(
                            t in l_text.upper()
                            for t in ["TIPP", "KNOPFLÖCHER", "KRAUSRIPPEN"]
                        )
                        if l_text.isupper() and len(l_text) > 3 and not is_tech:
                            start_index = j
                            found = True
                            break
                    if found:
                        break

        instruction_text = "\n".join(lines[start_index:])
        normalized_text = self._normalize_garnstudio_text(instruction_text)

        # 2. Iterate lines and group into steps
        steps: list[dict[str, Any]] = []
        current_step: dict[str, Any] | None = None

        # Skip the marker itself if it became a heading
        skip_headings = {
            "DIE ARBEIT BEGINNT HIER",
            "START HERE",
            "ANLEITUNG",
            "MASCHENPROBE",
            "GAUGE",
            "STRIKKEFASTHET",
            "MATERIAL",
            "MATERIALS",
            "GARN",
            "YARN",
            "NADELN",
            "NEEDLES",
            "PINNER",
            "HÄKELNADEL",
            "HÄKELNADELN",
            "CROCHET HOOK",
            "CROCHET HOOKS",
            "HEKLENÅL",
            "HEKLENÅLER",
            "GRÖSSEN",
            "GROESSEN",
            "SIZE",
            "SIZES",
            "STØRRELSER",
        }

        lines = [line.rstrip() for line in normalized_text.splitlines()]
        for line in lines:
            stripped = line.strip()
            if not stripped:
                if (
                    current_step
                    and current_step["description"]
                    and not current_step["description"].endswith("\n")
                ):
                    current_step["description"] += "\n"
                continue

            if stripped.startswith("### "):
                title = stripped[4:].strip()
                if title.upper() in skip_headings:
                    continue

                current_step = {
                    "step_number": len(steps) + 1,
                    "title": title,
                    "description": "",
                    "images": [],
                }
                steps.append(current_step)
                continue

            if not current_step:
                current_step = {
                    "step_number": len(steps) + 1,
                    "title": f"Step {len(steps) + 1}",
                    "description": "",
                    "images": [],
                }
                steps.append(current_step)

            desc = current_step["description"]
            if desc and not desc.endswith("\n"):
                if (
                    stripped.startswith("(")
                    or stripped[:1].islower()
                    or desc.endswith("-")
                ):
                    if desc.endswith("-"):
                        current_step["description"] = desc[:-1] + stripped
                    else:
                        current_step["description"] += " " + stripped
                else:
                    current_step["description"] += "\n" + stripped
            else:
                current_step["description"] += stripped

        final_steps = []
        for s in steps:
            s["description"] = s["description"].strip()
            if s["description"]:
                final_steps.append(s)

        for i, s in enumerate(final_steps, 1):
            s["step_number"] = i
            if s["title"].startswith("Step "):
                s["title"] = f"Step {i}"

        return final_steps

    def _normalize_garnstudio_text(self, text: str) -> str:
        """Improve Garnstudio text structure by converting headers to Markdown."""
        normalized = text.replace("\r\n", "\n").replace("\r", "\n")

        # 1. First, find all-caps lines or lines ending with colon
        # that are likely headings
        lines = normalized.splitlines()
        structured_lines = []

        # Heading keywords to always recognize
        headings = {
            "HINWEISE ZUR ANLEITUNG",
            "PATTERN NOTES",
            "MUSTER",
            "PATTERN",
            "ZUNAHMETIPP",
            "INCREASING TIP",
            "ABNAHMETIPP",
            "DECREASING TIP",
            "MASCHENPROBE",
            "GAUGE",
            "STRIKKEFASTHET",
            "GROESSEN",
            "GRÖSSEN",
            "SIZE",
            "SIZES",
            "GARN",
            "YARN",
            "NADELN",
            "NEEDLES",
            "PINNER",
            "HÄKELNADEL",
            "HÄKELNADELN",
            "CROCHET HOOK",
            "CROCHET HOOKS",
            "HEKLENÅL",
            "HEKLENÅLER",
            "RIPPEN",
            "RIB",
            "ABNAHMEN",
            "DECREASES",
            "ZUNAHMEN",
            "INCREASES",
            "ARM",
            "SLEEVE",
            "ERME",
            "RAGLAN",
            "YOKE",
            "VORDER- UND RÜCKENTEIL",
            "BODY",
            "BOLEN",
            "HALS",
            "NECK",
            "AUSARBEITUNG",
            "FINISHING",
            "MONTERING",
            "JACKE - KURZBESCHREIBUNG DER ARBEIT",
            "HALSAUSSCHNITT",
            "RAGLANZUNAHMEN",
            "V-AUSSCHNITT",
            "TEILUNG FÜR DAS RUMPFTEIL UND DIE ÄRMEL",
            "RUMPFTEIL",
            "ÄRMEL",
            "BLENDE",
            "KNOPFLÖCHER",
            "STRICKTIPP",
            "STREIFEN",
            "KRAUSRIPPEN / KRAUS RECHTS (in Hin- und Rück-Reihen)",
            "KRAUSRIPPEN / KRAUS RECHTS",
            "DIE ARBEIT BEGINNT HIER",
            "START HERE",
            "KURZBESCHREIBUNG",
            "ANLEITUNG",
        }

        for line in lines:
            stripped = line.strip()
            if not stripped:
                structured_lines.append("")
                continue

            match_line = stripped.rstrip(":").strip()
            upper_line = match_line.upper()

            is_heading = False
            if upper_line in headings:
                is_heading = True
            elif (
                match_line.isupper()
                and 3 < len(match_line) < 70
                and sum(1 for c in match_line if c.isalpha()) >= 3
                and match_line[:1].isalpha()
            ):
                # Heuristic for other all-caps lines that contain at least one letter
                is_heading = True
            elif stripped.endswith(":"):
                # Many Garnstudio sections are not written in all-caps, but still use
                # a trailing ":" to mark a header (e.g. "Oberer Teil:").
                words = [w for w in match_line.split() if w]
                word_count = len(words)
                is_heading = (
                    1 <= word_count <= 8
                    and len(match_line) < 70
                    and any(c.isalpha() for c in match_line)
                    and match_line[:1].isalpha()
                )
                if is_heading and not match_line.isupper():
                    # Avoid turning sentences like "Streifen wie folgt stricken:" into
                    # headings. Mixed-case headings are usually title-like.
                    is_heading = all(w[:1].isupper() for w in words)

            if is_heading:
                # Use the original capitalization if it's not all-caps,
                # but for Garnstudio most are all-caps anyway.
                structured_lines.append(f"### {match_line}")
            else:
                structured_lines.append(line)

        normalized = "\n".join(structured_lines)

        # 2. Fix inline colon-style headings that weren't captured
        # Match "WORD IN CAPS:" at the start of a line
        normalized = re.sub(
            r"^([A-Z0-9\s\-/()]{3,70}):\s*$",
            r"### \1",
            normalized,
            flags=re.MULTILINE,
        )

        # 3. Clean up excessive newlines and separators
        normalized = re.sub(r"-{3,}", "", normalized)
        normalized = re.sub(r"\n{3,}", "\n\n", normalized)

        return normalized.strip()

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
        # Use a list of tuples (url, tag) to allow scoring based on tag attributes
        extracted: list[tuple[str, Tag | None]] = []
        seen: set[str] = set()

        def _is_related_pattern(tag: Tag | None) -> bool:
            if not self.is_garnstudio or tag is None:
                return False
            if isinstance(tag, Tag):
                alt_text = str(tag.get("alt") or "").lower()
                title_text = str(tag.get("title") or "").lower()
                related_keywords = [
                    "related pattern",
                    "patterns with",
                    "anleitungen für",
                    "anleitungen mit",
                    "free patterns",
                ]
                if any(x in alt_text for x in related_keywords) or any(
                    x in title_text for x in related_keywords
                ):
                    return True
            for parent in [tag, *list(tag.parents)]:
                if not isinstance(parent, Tag):
                    continue
                parent_id = str(parent.get("id") or "").lower()
                if "related-patterns" in parent_id or "patterns" in parent_id:
                    return True
                parent_class = parent.get("class")
                class_str = (
                    " ".join(parent_class)
                    if isinstance(parent_class, list)
                    else str(parent_class or "")
                )
                if "related-pattern" in class_str or "re-material" in class_str:
                    return True

                # If on a pattern page, exclude images linked to yarn pages
                # or images that are explicitly marked as product images (yarns)
                if "pattern.php" in self.url:
                    if isinstance(parent, Tag) and parent.name == "a":
                        href = str(parent.get("href") or "")
                        onclick = str(parent.get("onclick") or "")
                        if "yarn.php" in href:
                            return True
                        if "Location': 'Pattern'" in onclick:
                            return True

                    if isinstance(tag, Tag):
                        alt = str(tag.get("alt") or "").lower()
                        if "product image" in alt:
                            return True
            return False

        meta_image = soup.find("meta", property="og:image")
        if meta_image:
            content = meta_image.get("content")
            if isinstance(content, str):
                resolved = self._resolve_image_url(content)
                if resolved:
                    extracted.append((resolved, meta_image))
                    seen.add(resolved)

        for source in soup.find_all("source"):
            if _is_related_pattern(source):
                continue
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
                extracted.append((resolved, source))
                seen.add(resolved)

        # Skip common non-pattern images
        for img in soup.find_all("img"):
            if _is_related_pattern(img):
                continue
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

            for candidate in candidates:
                resolved = self._resolve_image_url(candidate)
                if not resolved or resolved in seen:
                    continue

                if self._is_skipped_image(resolved):
                    continue

                # Extension check
                if (
                    Path(urlparse(resolved).path).suffix.lower()
                    not in IMPORT_ALLOWED_IMAGE_EXTENSIONS
                ):
                    continue

                extracted.append((resolved, img))
                seen.add(resolved)

        if self.is_garnstudio:
            # Check for fancybox/lightbox links which often hold diagrams
            for anchor in soup.find_all(
                "a", class_=lambda x: x and "fancybox" in str(x)
            ):
                if _is_related_pattern(anchor):
                    continue
                href = anchor.get("href")
                if href and isinstance(href, str):
                    resolved = self._resolve_image_url(href)
                    if not resolved or resolved in seen:
                        continue

                    if self._is_skipped_image(resolved):
                        continue

                    if (
                        Path(urlparse(resolved).path).suffix.lower()
                        in IMPORT_ALLOWED_IMAGE_EXTENSIONS
                    ):
                        extracted.append((resolved, anchor))
                        seen.add(resolved)

            for anchor in soup.find_all("a"):
                if _is_related_pattern(anchor):
                    continue
                href = anchor.get("href")
                if not href or not isinstance(href, str):
                    continue
                if not self._looks_like_image_url(href):
                    continue
                resolved = self._resolve_image_url(href)
                if not resolved or resolved in seen:
                    continue

                if self._is_skipped_image(resolved):
                    continue

                if (
                    Path(urlparse(resolved).path).suffix.lower()
                    not in IMPORT_ALLOWED_IMAGE_EXTENSIONS
                ):
                    continue
                extracted.append((resolved, anchor))
                seen.add(resolved)

        # Prioritize diagrams, charts, and sketches
        def _score_image(item: tuple[str, Tag | None]) -> int:
            url, tag = item
            score = 0
            lower_url = url.lower()

            is_diagram = self._is_diagram_url(url)

            # Keywords in URL indicating photos
            photo_keywords = ["large", "high", "orig", "photo", "image", "pic"]
            is_photo = any(x in lower_url for x in photo_keywords)

            if is_diagram:
                # Diagrams are important but shouldn't be primary
                score += 8
            elif is_photo:
                score += 15
            else:
                score += 10

            # Boost OG Image significantly to make it primary
            if tag and tag.name == "meta" and tag.get("property") == "og:image":
                score += 50

            # Check tag attributes if available
            if tag:
                # Check alt, title, and class of the tag
                tag_alt = tag.get("alt")
                tag_title = tag.get("title")
                tag_class = tag.get("class")

                alt_str = str(tag_alt) if tag_alt else ""
                title_str = str(tag_title) if tag_title else ""
                class_str = (
                    " ".join(tag_class)
                    if isinstance(tag_class, list)
                    else str(tag_class or "")
                )

                tag_text = f"{alt_str} {title_str} {class_str}".lower()

                if any(
                    x in tag_text
                    for x in [
                        "diagram",
                        "chart",
                        "skizze",
                        "measure",
                        "schema",
                        "proportions",
                    ]
                ):
                    score += 5  # Add a bit more if it's explicitly a diagram
                elif any(x in tag_text for x in ["pattern", "product", "main"]):
                    score += 20

                # Check parent class (Garnstudio uses print-diagrams)
                parent = tag.parent
                if isinstance(parent, Tag):
                    p_class = parent.get("class")
                    parent_class = (
                        " ".join(p_class)
                        if isinstance(p_class, list)
                        else str(p_class or "")
                    )
                    if (
                        "diagram" in parent_class.lower()
                        or "skizze" in parent_class.lower()
                    ):
                        score += 5

            return score

        extracted.sort(key=_score_image, reverse=True)

        return [url for url, _tag in extracted]

    def _is_diagram_url(self, url: str) -> bool:
        """Check if an image URL looks like a diagram or chart."""
        lower_url = url.lower()
        diagram_keywords = [
            "diagram",
            "chart",
            "skizze",
            "measure",
            "schema",
            "proportions",
        ]
        if any(kw in lower_url for kw in diagram_keywords):
            return True
        if self.is_garnstudio and re.search(
            r"[-/](?:diag\d*|\d*[dc])\.(?:jpe?g|png)$", lower_url
        ):
            return True
        return False

    def _is_skipped_image(self, url: str) -> bool:
        """Check if an image URL matches any of the skip patterns."""
        lower_url = url.lower()
        skip_list = [
            "/logo",
            "/icon",
            "avatar",
            "button",
            "badge",
            "banner",
            "/ad/",
            "-ad-",
            "social",
            "facebook",
            "twitter",
            "instagram",
            "pinterest",
            "/img/school/lessons/",
            "/img/activity/",
            "/img/products/",
            "/drops/symbols/",
        ]
        return any(x in lower_url for x in skip_list)

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
            for token in [
                "diagram",
                "chart",
                "schema",
                "schem",
                "muster",
                "pattern",
                "skizze",
                "measure",
            ]
        )

    def _looks_like_image_url(self, url: str) -> bool:
        lower = url.lower()
        if any(
            token in lower
            for token in ["diagram", "chart", "schema", "schem", "skizze", "measure"]
        ):
            return True
        path = urlparse(lower).path
        return path.endswith((".png", ".jpg", ".jpeg", ".gif", ".webp"))

    def _extract_garnstudio_info_by_heading(
        self, soup: BeautifulSoup, target_headings: list[str]
    ) -> str | None:
        """Extract a block of text following one of the target headings."""
        text = self._extract_garnstudio_text(soup)
        if not text:
            return None

        lines = [line.strip() for line in text.splitlines()]
        start_index = -1

        # Normalize target headings to upper case for comparison
        targets = [h.upper() for h in target_headings]

        for i, line in enumerate(lines):
            # Check for "HEADING:" or "HEADING"
            match_line = line.rstrip(":").strip().upper()
            if match_line in targets:
                # Ensure it's not just a part of a larger sentence
                if len(match_line) == len(line.strip().rstrip(":")):
                    # Check if the next line(s) look like actual data or UI noise
                    # If it's UI noise (like 'Bestellen'), keep looking for
                    # another heading
                    is_noise = False
                    for j in range(i + 1, min(i + 3, len(lines))):
                        if self._is_ui_text(lines[j]):
                            is_noise = True
                            break

                    if not is_noise:
                        start_index = i
                        break

        if start_index == -1:
            return None

        # Headers that indicate the end of this section
        stop_headings = {
            "GRÖSSEN",
            "GROESSEN",
            "SIZE",
            "SIZES",
            "STØRRELSER",
            "MATERIAL",
            "MATERIALS",
            "GARN",
            "YARN",
            "NADELN",
            "NEEDLES",
            "PINNER",
            "HÄKELNADEL",
            "HÄKELNADELN",
            "CROCHET HOOK",
            "CROCHET HOOKS",
            "HEKLENÅL",
            "HEKLENÅLER",
            "MASCHENPROBE",
            "GAUGE",
            "STRIKKEFASTHET",
            "ABMESSUNGEN",
            "MEASUREMENTS",
            "DIE ARBEIT BEGINNT HIER",
            "START HERE",
            "KURZBESCHREIBUNG",
            "HINWEISE ZUR ANLEITUNG",
            "PATTERN NOTES",
            "MUSTER",
            "PATTERN",
            "ANLEITUNGEN FÜR",
            "PATTERNS FOR",
            "ANLEITUNGEN MIT",
            "PATTERNS WITH",
        }

        collected_lines: list[str] = []
        for line in lines[start_index + 1 :]:
            if not line:
                if collected_lines:  # Stop at empty line if we already have content
                    break
                continue

            match_line = line.rstrip(":").strip().upper()
            if match_line in stop_headings:
                break

            # If it's another all-caps line (likely a heading), stop
            if line.isupper() and len(line) > 3 and not any(c.isdigit() for c in line):
                break

            collected_lines.append(line)

        val = " ".join(collected_lines).strip()
        return val or None

    def _extract_garnstudio_yarn_links(
        self, soup: BeautifulSoup
    ) -> dict[str, dict[str, str]]:
        """Extract links and images for yarns from Garnstudio pattern page."""
        yarn_data: dict[str, dict[str, str]] = {}

        # Look for all yarn links
        for a in soup.find_all("a", href=re.compile(r"yarn\.php")):
            href_raw = a.get("href")
            if not href_raw:
                continue

            # Skip navigation/menu links which list all yarns and can pollute imports.
            if (
                a.find_parent("nav") is not None
                or a.find_parent(id=re.compile(r"^menu$", re.I)) is not None
                or a.find_parent(class_=re.compile(r"\bm-menu\b")) is not None
                or (a.get("class") and "nav-link" in (a.get("class") or []))
            ):
                continue

            # Skip known "related" sections; these are not materials for the pattern.
            if (
                a.find_parent(id="related-patterns") is not None
                or a.find_parent(id="yarn-patterns") is not None
                or a.find_parent(class_=re.compile(r"\bfeature-cats\b")) is not None
            ):
                continue

            href = (
                " ".join(href_raw) if isinstance(href_raw, list) else str(href_raw)
            ).strip()

            # Skip menu links - they usually have 'MenuYarn' in onclick
            onclick_raw = a.get("onclick", "")
            onclick = (
                " ".join(onclick_raw)
                if isinstance(onclick_raw, list)
                else str(onclick_raw or "")
            )
            if "MenuYarn" in onclick:
                continue

            full_url = urljoin(self.url, href)
            image_url = ""

            # If we have a "show=" slug, we can always derive a stable yarn name.
            name_from_show = None
            try:
                show = parse_qs(urlparse(full_url).query).get("show", [None])[0]
                if show:
                    slug = show
                    if slug.startswith("drops-"):
                        slug = slug[6:]
                    if slug:
                        name_from_show = "-".join(
                            part.capitalize() for part in slug.split("-") if part
                        )
            except Exception:
                name_from_show = None

            # Try to get the yarn name and image
            # 1. Direct text
            name = a.get_text(strip=True)
            # 2. Strong tag inside
            if not name:
                strong = a.find("strong")
                if isinstance(strong, Tag):
                    name = strong.get_text(strip=True)
            # 3. Image alt in the same container or sibling container
            # Look in parent or siblings for an image
            parent = a.find_parent(["div", "li"])
            if isinstance(parent, Tag):
                img = parent.find("img")

                # If not found in the same container, look in siblings
                # (Garnstudio layout)
                if not img:
                    row = parent.find_parent("div", class_="row")
                    if row:
                        img = row.find("img")

                if isinstance(img, Tag):
                    if not name:
                        alt_raw = img.get("alt")
                        title_raw = img.get("title")
                        alt = (
                            " ".join(alt_raw)
                            if isinstance(alt_raw, list)
                            else str(alt_raw or "")
                        )
                        title = (
                            " ".join(title_raw)
                            if isinstance(title_raw, list)
                            else str(title_raw or "")
                        )
                        name = alt or title

                    src_raw = img.get("src")
                    if src_raw:
                        src = (
                            " ".join(src_raw)
                            if isinstance(src_raw, list)
                            else str(src_raw)
                        )
                        image_url = urljoin(self.url, src)

            cleaned_name = self._clean_yarn_name(name) if name else None
            if cleaned_name:
                lower_cleaned = cleaned_name.lower()
                if lower_cleaned in ["yarn", "garn", "garne"]:
                    cleaned_name = None
                if "product image" in lower_cleaned or "produktbild" in lower_cleaned:
                    cleaned_name = None

            if not cleaned_name and name_from_show:
                cleaned_name = name_from_show

            if cleaned_name:
                key = cleaned_name.lower()
                if key not in yarn_data:
                    yarn_data[key] = {
                        "link": full_url,
                        "image_url": image_url,
                        "name": cleaned_name,
                    }
                elif image_url and not yarn_data[key].get("image_url"):
                    yarn_data[key]["image_url"] = image_url

        return yarn_data

    def _match_garnstudio_yarn_link_name(
        self, line: str, yarn_links: dict[str, dict[str, str]]
    ) -> str | None:
        if not yarn_links:
            return None

        line_lower = line.lower()
        for candidate in sorted(yarn_links.keys(), key=len, reverse=True):
            if candidate and candidate in line_lower:
                return candidate
        return None

    def _extract_garnstudio_yarn(self, soup: BeautifulSoup) -> str | None:
        """Extract yarn list from Garnstudio pattern."""
        material = soup.find(id=re.compile(r"material_text(_print)?"))
        if not material:
            material = soup.select_one(".pattern-material")

        if not material:
            return None

        text = material.get_text(separator="\n", strip=True)
        lines = [line.strip() for line in text.splitlines() if line.strip()]
        yarn_headings = {"GARN", "YARN", "MATERIAL", "MATERIALS"}

        start_index = -1
        for i, line in enumerate(lines):
            upper = line.strip().rstrip(":").upper()
            if upper in yarn_headings:
                start_index = i
                break

        if start_index == -1:
            return None

        # Garnstudio sometimes mixes yarn and notions (needles, hooks, buttons) inside
        # the same "Material:" block. We group by "DROPS ..." blocks and filter.
        collected_blocks: list[tuple[str, bool]] = []
        current: list[str] = []
        current_is_alternative = False
        pending_alternative = False
        stop_headings = {
            "NADELN",
            "NEEDLES",
            "PINNER",
            "HÄKELNADEL",
            "HÄKELNADELN",
            "CROCHET HOOK",
            "CROCHET HOOKS",
            "HEKLENÅL",
            "HEKLENÅLER",
            "KNÖPFE",
            "BUTTONS",
            "KNAPPER",
            "MASCHENPROBE",
            "GAUGE",
            "STRIKKEFASTHET",
            "ABMESSUNGEN",
            "MEASUREMENTS",
            "DIE ARBEIT BEGINNT HIER",
            "START HERE",
            "KURZBESCHREIBUNG",
        }

        for line in lines[start_index + 1 :]:
            heading_key = line.rstrip(":").strip().upper()
            if heading_key in stop_headings:
                if current:
                    collected_blocks.append((" ".join(current), current_is_alternative))
                    current = []
                break

            lower = line.lower()
            if lower in ["oder:", "or:"]:
                pending_alternative = True
                continue

            if line.upper().startswith("DROPS "):
                if current:
                    collected_blocks.append((" ".join(current), current_is_alternative))
                current = [line]
                current_is_alternative = pending_alternative
                pending_alternative = False
                continue

            if current:
                current.append(line)

        if current:
            collected_blocks.append((" ".join(current), current_is_alternative))

        final_yarns: list[str] = []
        for block, _is_alternative in collected_blocks:
            lower = block.lower()
            if any(
                kw in lower
                for kw in [
                    "nadel",
                    "needle",
                    "rundnadel",
                    "nadelspiel",
                    "häkelnadel",
                    "crochet",
                    "hook",
                    "knopf",
                    "button",
                    "maschenprobe",
                    "gauge",
                ]
            ):
                continue

            details = self._parse_garnstudio_yarn_string(block)
            name_upper = (details.get("name") or "").upper()
            if not name_upper or name_upper in ["DROPS", "GARNSTUDIO"]:
                continue

            final_yarns.append(block)

        return "\n".join(final_yarns).strip() or None

    def _extract_garnstudio_text(self, soup: BeautifulSoup) -> str:
        """Extract clean text from Garnstudio pattern page using trafilatura."""
        import trafilatura

        # Targeted noise removal before trafilatura
        noise_selectors = [
            ".pcalc",
            ".pcalc-wrapper",
            ".btn",
            ".pattern-print",
            ".dropdown",
            ".lessons-wrapper",
            ".mobile-only",
            ".re-material",
            ".updates",
            ".pattern_copyright",
            ".pattern-share-new",
            ".pattern-ad",
            ".pattern-prices",
            ".m-menu",
            ".dropdown-menu",
            "#menu",
            "nav",
            ".modal",
            ".table-products",
            ".sn",
            "script",
        ]
        for noise in soup.select(", ".join(noise_selectors)):
            noise.decompose()

        # Garnstudio has content in these IDs/classes
        # #material_text contains GRÖSSE, GARN, NADELN, MASCHENPROBE
        # #instruction_text contains technical notes and the actual pattern
        # #about contains yarn description on yarn pages
        material = soup.find(id=re.compile(r"material_text(_print)?"))
        instruction = soup.find(id=re.compile(r"instruction_text(_print)?"))
        about = soup.select_one("#about")
        yarn_desc = soup.select_one(".yarn-description")

        parts = []
        if material:
            # Trafilatura might be too aggressive for the short material block
            # try with specific settings or fallback to BeautifulSoup
            info_text = trafilatura.extract(
                material.decode(),
                include_comments=False,
                include_tables=True,
                no_fallback=False,
            )
            if not info_text or len(info_text) < 50:
                info_text = material.get_text(separator="\n", strip=True)
            if info_text:
                parts.append(info_text)

        if instruction:
            instr_text = trafilatura.extract(
                instruction.decode(), include_comments=False, include_tables=True
            )
            if not instr_text or len(instr_text) < 20:
                instr_text = instruction.get_text(separator="\n", strip=True)
            if instr_text:
                parts.append(instr_text)

        if about:
            about_text = trafilatura.extract(
                about.decode(), include_comments=False, include_tables=True
            )
            if not about_text or len(about_text) < 20:
                about_text = about.get_text(separator="\n", strip=True)
            if about_text:
                parts.append(about_text)

        if yarn_desc:
            desc_text = trafilatura.extract(
                yarn_desc.decode(), include_comments=False, include_tables=True
            )
            if not desc_text or len(desc_text) < 20:
                desc_text = yarn_desc.get_text(separator="\n", strip=True)
            if desc_text:
                parts.append(desc_text)

        if parts:
            return "\n\n".join(parts)

        # Ultimate fallback
        return trafilatura.extract(soup.decode(), include_comments=False) or ""


class GarnstudioPatternImporter(PatternImporter):
    """Importer with Garnstudio-specific extraction rules."""

    def __init__(self, url: str, timeout: int = 10) -> None:
        super().__init__(url, timeout)
        self.is_garnstudio = True

    async def fetch_and_parse(self, image_limit: int = 10) -> dict[str, Any]:
        """Fetch URL and extract data with Garnstudio post-processing."""
        # Use -1 to get all images for diagram extraction
        data = await super().fetch_and_parse(image_limit=-1)

        # Identify diagrams in image_urls
        diagrams = [
            url for url in data.get("image_urls", []) if self._is_diagram_url(url)
        ]

        legend_title, legend_entries = self._extract_garnstudio_diagram_legend(
            self._last_soup
        )
        legend_markdown = self._format_garnstudio_diagram_legend(
            legend_title, legend_entries
        )

        if diagrams:
            # Check if we already have a step for diagrams
            has_diagram_step = any(
                "diagram" in s["title"].lower() or "skizze" in s["title"].lower()
                for s in data["steps"]
            )

            if not has_diagram_step:
                # Basic language detection
                is_english = "cid=1" in self.url or "dropsdesign.com" in self.url
                title = "Diagram" if is_english else "Diagramm"
                desc = (
                    "Measurements and diagrams for this pattern."
                    if is_english
                    else "Maßskizzen und Diagramme für diese Anleitung."
                )

                # Add a "Diagramm" step at the end
                data["steps"].append(
                    {
                        "step_number": len(data["steps"]) + 1,
                        "title": title,
                        "description": desc,
                        "images": diagrams,
                    }
                )

                # Remove diagrams from main gallery to avoid clutter
                data["image_urls"] = [
                    url for url in data["image_urls"] if url not in diagrams
                ]

        if legend_markdown:
            # Attach legend to all diagram-related steps; some patterns have multiple
            # "Diagram A.1", "Diagram A.2", ... steps.
            diagram_steps = [
                step
                for step in data["steps"]
                if "diagram" in step["title"].lower()
                or "skizze" in step["title"].lower()
            ]

            if not diagram_steps:
                is_english = "cid=1" in self.url or "dropsdesign.com" in self.url
                title = "Diagram" if is_english else "Diagramm"
                diagram_step = {
                    "step_number": len(data["steps"]) + 1,
                    "title": title,
                    "description": "",
                    "images": diagrams,
                }
                data["steps"].append(diagram_step)
                diagram_steps = [diagram_step]

            for step in diagram_steps:
                description = step.get("description") or ""
                # Avoid duplicating the legend if a page contains multiple legend tables
                # or import is retried.
                if "drops/symbols/" in description or legend_markdown in description:
                    continue
                if description:
                    step["description"] = f"{description}\n\n{legend_markdown}"
                else:
                    step["description"] = legend_markdown

        # Slice back to the requested limit
        data["image_urls"] = data["image_urls"][:image_limit]
        return data

    def _extract_garnstudio_diagram_legend(
        self, soup: BeautifulSoup | None
    ) -> tuple[str | None, list[dict[str, str]]]:
        if soup is None:
            return None, []

        table = soup.find("table", id="diag_symbols")
        if not table:
            return None, []

        heading = table.find_previous(["h2", "h3", "h4", "strong", "b"])
        title = heading.get_text(" ", strip=True) if heading else None

        entries: list[dict[str, str]] = []
        # Garnstudio uses (at least) two legend table layouts:
        # 1) One symbol per row: [img] [=] [label]
        # 2) Multiple symbol/label pairs per row (print view):
        #    [img] [label] [img] [label] ...
        for row in table.find_all("tr"):
            cells = row.find_all("td")
            if not cells:
                continue

            i = 0
            extracted_any = False
            while i < len(cells):
                cell = cells[i]
                img = cell.find("img")
                if not img:
                    i += 1
                    continue

                symbol_url = ""
                src = img.get("src")
                if isinstance(src, str) and src.strip():
                    symbol_url = urljoin(self.url, src.strip())

                label = ""
                # Prefer explicit label from subsequent cells.
                if i + 1 < len(cells):
                    next_text = cells[i + 1].get_text(" ", strip=True)
                    if next_text.strip() in {"=", "=."} and i + 2 < len(cells):
                        label = cells[i + 2].get_text(" ", strip=True)
                        i += 3
                    else:
                        label = next_text
                        i += 2
                else:
                    i += 1

                label = label.lstrip("=").strip()
                if not label:
                    alt = img.get("alt")
                    if isinstance(alt, str) and alt.strip():
                        label = alt.strip()

                if label or symbol_url:
                    entry: dict[str, str] = {}
                    if symbol_url:
                        entry["symbol_url"] = symbol_url
                    if label:
                        entry["label"] = label
                    entries.append(entry)
                    extracted_any = True

            if extracted_any:
                continue

            # Fallback for odd rows: no <td> with <img> (rare), but still text.
            fallback_label = row.get_text(" ", strip=True).lstrip("=").strip()
            if fallback_label:
                entries.append({"label": fallback_label})

        return title, entries

    def _format_garnstudio_diagram_legend(
        self, title: str | None, entries: list[dict[str, str]]
    ) -> str:
        if not entries:
            return ""

        lines: list[str] = []
        if title:
            lines.append(f"**{title}**")
            lines.append("")

        for entry in entries:
            label = entry.get("label", "").strip()
            symbol_url = entry.get("symbol_url", "").strip()
            if symbol_url and label:
                lines.append(f"- ![]({symbol_url}) = {label}")
            elif symbol_url:
                lines.append(f"- ![]({symbol_url})")
            elif label:
                lines.append(f"- {label}")

        return "\n".join(lines).strip()
