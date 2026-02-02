"URL import utilities for extracting knitting pattern data."

import html
import logging
import re
from pathlib import Path
from typing import Any
from urllib.parse import urljoin, urlparse

import httpx
from bs4 import BeautifulSoup
from bs4.element import AttributeValueList, NavigableString, PageElement, Tag

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


def _is_valid_import_url(url: str) -> bool:
    """Ensure the import URL uses http(s) and has a host."""
    parsed = urlparse(url)
    return parsed.scheme in {"http", "https"} and bool(parsed.netloc)


# Image import related constants
IMPORT_IMAGE_MAX_BYTES = 5 * 1024 * 1024
IMPORT_IMAGE_MAX_COUNT = 10
IMPORT_IMAGE_TIMEOUT = 10
IMPORT_IMAGE_MIN_DIMENSION = 64
IMPORT_IMAGE_HEADERS = {
    "User-Agent": "Stricknani Importer/0.1",
    "Accept": "image/*",
}
IMPORT_ALLOWED_IMAGE_TYPES = {
    "image/jpeg",
    "image/png",
    "image/webp",
    "image/gif",
}
IMPORT_ALLOWED_IMAGE_EXTENSIONS = {".jpg", ".jpeg", ".png", ".webp", ".gif"}


def _is_allowed_import_image(content_type: str | None, url: str) -> bool:
    """Validate content type or file extension for image imports."""
    if content_type:
        normalized = content_type.split(";", 1)[0].strip().lower()
        if normalized in IMPORT_ALLOWED_IMAGE_TYPES:
            return True
    extension = Path(urlparse(url).path).suffix.lower()
    return extension in IMPORT_ALLOWED_IMAGE_EXTENSIONS


class PatternImporter:
    """Extract knitting pattern data from URLs."""

    def __init__(self, url: str, timeout: int = 10) -> None:
        """Initialize with URL to import."""
        self.url = url
        self.timeout = timeout
        self.is_garnstudio = _is_garnstudio_url(url)
        self._garnstudio_gauge_cache: tuple[int | None, int | None] | None = None

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

        # Pre-clean Garnstudio soup to remove UI noise globally
        if self.is_garnstudio:
            # Targeted noise removal
            noise_selectors = [
                ".pcalc", ".pcalc-wrapper", ".btn", ".pattern-print", ".dropdown",
                ".lessons-wrapper", ".mobile-only", ".re-material", ".pcalc-title",
                ".pcalc-content", ".pcalc-form", ".pcalc-result", ".pcalc-close",
                "#pcalc-wrapper", "#pcalc-container", ".pattern-print-link",
                "script", "style", "nav", "footer", "header"
            ]
            for selector in noise_selectors:
                for noise in soup.select(selector):
                    noise.decompose()

            # Also look for elements by ID if they don't have classes
            for noise_id in ["pcalc-wrapper", "pcalc-container"]:
                element = soup.find(id=noise_id)
                if element:
                    element.decompose()

        steps = self._extract_steps(soup)
        images = self._extract_images(soup)
        description = self._extract_description(soup)
        if self.is_garnstudio:
            garn_notes = self._extract_garnstudio_notes(soup)
            if garn_notes:
                # For Garnstudio, the technical notes are the most important part
                # of the description.
                description = garn_notes

        comment = None
        image_urls = images
        if image_limit > 0:
            image_urls = images[:image_limit]

        data: dict[str, Any] = {
            "title": self._extract_title(soup),
            "needles": self._extract_needles(soup),
            "yarn": self._extract_yarn(soup),
            "brand": self._extract_brand(soup),
            "fiber_content": self._extract_fiber_content(soup),
            "colorway": self._extract_colorway(soup),
            "weight_grams": self._extract_weight_grams(soup),
            "length_meters": self._extract_length_meters(soup),
            "weight_category": self._extract_weight_category(soup),
            "gauge_stitches": self._extract_gauge_stitches(soup),
            "gauge_rows": self._extract_gauge_rows(soup),
            "stitch_sample": self._extract_stitch_sample(soup),
            "description": description,
            "comment": comment,
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
                soup, ["NADELN", "NEEDLES", "PINNER"]
            )
            if val:
                return val

        # Try finding by label first
        val = self._find_info_by_label(
            soup, ["nadelstärke", "needles", "needle size", "nadeln"]
        )
        if val:
            return val

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

    def _extract_yarn(self, soup: BeautifulSoup) -> str | None:
        """Extract yarn information."""
        if self.is_garnstudio:
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
                yarn_text = match.group(1).strip()
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

    def _extract_brand(self, soup: BeautifulSoup) -> str | None:
        """Extract brand/manufacturer information."""
        # 1. Try to find known brands in the title first.
        # This is extremely reliable for shop product pages.
        title = self._extract_title(soup)
        if title:
            known_brands = [
                "Rico Design",
                "Drops",
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
                return "Drops"
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
        val = self._find_info_by_label(soup, ["gewicht", "ball weight", "weight"])
        if val:
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
            soup, ["lauflänge", "yardage", "length", "laufweite"]
        )
        if val:
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

    def _extract_gauge_stitches(self, soup: BeautifulSoup) -> int | None:
        """Extract gauge stitches per 10cm."""
        if self.is_garnstudio:
            stitches, _rows = self._get_garnstudio_gauge(soup)
            if stitches:
                return stitches

        # Try finding by label first
        val = self._find_info_by_label(soup, ["maschenprobe", "gauge", "tension"])
        if val:
            # 22 M und 28 R = 10 x 10 cm
            match = re.search(r"(\d+)\s*(?:m|maschen|sts|stitches)", val, re.I)
            if match:
                return int(match.group(1))

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

        # Try finding by label first
        val = self._find_info_by_label(soup, ["maschenprobe", "gauge", "tension"])
        if val:
            # 22 M und 28 R = 10 x 10 cm
            match = re.search(r"und\s*(\d+)\s*(?:r|reihen|rows)", val, re.I)
            if match:
                return int(match.group(1))
            # Fallback if it's just '28 R' or similar
            match = re.search(r"(\d+)\s*(?:r|reihen|rows)", val, re.I)
            if match:
                return int(match.group(1))

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
        ]
        lower_text = text.lower()
        return any(hint in lower_text for hint in ui_hints) or len(text) < 1

    def _find_info_by_label(self, soup: BeautifulSoup, labels: list[str]) -> str | None:
        """Find value associated with labels in the page text/structure."""
        # 1. Search for Label: Value in flat text
        text = soup.get_text(separator="\n", strip=True)
        for label in labels:
            # Match label optionally followed by colon and then
            # capture until end of line
            pattern = rf"(?:{label})\s*[:：]?\s*([^\n]+)"
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
        for tag in ["article", "main", "div"]:
            container = soup.find(
                tag, class_=re.compile(r"description|content|product-info", re.I)
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

        # Look for the beginning of technical notes
        # Often starts with HINWEISE or just specific technique names
        lines = [line.rstrip() for line in text.splitlines()]

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
            "MASCHENPROBE",
            "GAUGE",
            "STRIKKEFASTHET",
            "ABMESSUNGEN",
            "MEASUREMENTS",
            "DIE ARBEIT BEGINNT HIER",
            "START HERE",
            "KURZBESCHREIBUNG",
        }

        collected: list[str] = []
        for line in lines:
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

        # 1. Identify where instructions actually start
        # Garnstudio patterns usually have technical notes first, then materials,
        # then instructions starting with something like "DIE ARBEIT BEGINNT HIER"
        # or "KURZBESCHREIBUNG".
        instruction_markers = [
            "DIE ARBEIT BEGINNT HIER",
            "START HERE",
            "KURZBESCHREIBUNG",
            "ANLEITUNG",
            "INSTRUCTIONS",
        ]

        lines = text.splitlines()
        start_index = 0
        for i, line in enumerate(lines):
            upper = line.strip().upper()
            if any(marker in upper for marker in instruction_markers):
                start_index = i
                break

        instruction_text = "\n".join(lines[start_index:])
        normalized_text = self._normalize_garnstudio_text(instruction_text)

        # 2. Iterate lines and group into steps
        steps: list[dict[str, Any]] = []
        current_step: dict[str, Any] | None = None

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

        # Comprehensive list of Garnstudio section markers (German and English)
        headings = [
            # Main sections
            "HINWEISE ZUR ANLEITUNG",
            "HINWEISE ZU ANLEITUNG",
            "HINWEIS ZUR ANLEITUNG",
            "PATTERN NOTES",
            "NOTES FOR THE PATTERN",
            "NOTES FOR INSTRUCTIONS",
            "MUSTER",
            "PATTERN",
            "STRIKKEMØNSTER",
            "ZUNAHMETIPP",
            "INCREASING TIP",
            "ØKETIPS",
            "ABNAHMETIPP",
            "DECREASING TIP",
            "FELLINGSTIPS",
            "MASCHENPROBE",
            "GAUGE",
            "STRIKKEFASTHET",
            "GROESSEN",
            "GRÖSSEN",
            "SIZE",
            "SIZES",
            "STØRRELSER",
            "GARN",
            "YARN",
            "MATERIAL",
            "MATERIALS",
            "NADELN",
            "NEEDLES",
            "PINNER",
            "RIPPEN",
            "RIB",
            "VRANGBORD",
            "ABNAHMEN",
            "DECREASES",
            "ZUNAHMEN",
            "INCREASES",
            "ARM",
            "SLEEVE",
            "ERME",
            "RAGLAN",
            "YOKE",
            "BÆRESTYKKE",
            "VORDER- UND RÜCKENTEIL",
            "BODY",
            "BOLEN",
            "HALS",
            "NECK",
            "AUSARBEITUNG",
            "FINISHING",
            "MONTERING",
        ]

        # 1. First, find all-caps lines that are known headings and make them h3
        lines = normalized.splitlines()
        structured_lines = []
        for line in lines:
            stripped = line.strip()
            upper_stripped = stripped.rstrip(":").upper()

            is_heading = False
            if upper_stripped in headings:
                is_heading = True
            elif stripped.isupper() and 3 < len(stripped) < 50:
                # Heuristic for other all-caps lines that might be headings
                is_heading = True

            if is_heading:
                structured_lines.append(f"\n\n### {stripped.rstrip(':')}\n")
            else:
                structured_lines.append(line)

        normalized = "\n".join(structured_lines)

        # 2. Fix inline colon-style headings
        # Match "WORD IN CAPS:" at the start of a line or after double newline
        # Also handles known headings. We allow a newline before the colon.
        normalized = re.sub(
            r"^([A-Z0-9\s-]{3,50})\s*\n?:",
            r"### \1\n",
            normalized,
            flags=re.MULTILINE,
        )

        # 3. Clean up excessive newlines
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

        meta_image = soup.find("meta", property="og:image")
        if meta_image:
            content = meta_image.get("content")
            if isinstance(content, str):
                resolved = self._resolve_image_url(content)
                if resolved:
                    extracted.append((resolved, meta_image))
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
                extracted.append((resolved, source))
                seen.add(resolved)

        # Skip common non-pattern images
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

            for candidate in candidates:
                resolved = self._resolve_image_url(candidate)
                if not resolved or resolved in seen:
                    continue

                # Extension check
                if (
                    Path(urlparse(resolved).path).suffix.lower()
                    not in IMPORT_ALLOWED_IMAGE_EXTENSIONS
                ):
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
                        "design",
                        "social",
                        "facebook",
                        "twitter",
                        "instagram",
                        "pinterest",
                    ]
                ):
                    continue

                extracted.append((resolved, img))
                seen.add(resolved)

        if self.is_garnstudio:
            # Check for fancybox/lightbox links which often hold diagrams
            for anchor in soup.find_all(
                "a", class_=lambda x: x and "fancybox" in str(x)
            ):
                href = anchor.get("href")
                if href and isinstance(href, str):
                    resolved = self._resolve_image_url(href)
                    if (
                        resolved
                        and resolved not in seen
                        and Path(urlparse(resolved).path).suffix.lower()
                        in IMPORT_ALLOWED_IMAGE_EXTENSIONS
                    ):
                        extracted.append((resolved, anchor))
                        seen.add(resolved)

            for anchor in soup.find_all("a"):
                href = anchor.get("href")
                if not href or not isinstance(href, str):
                    continue
                if not self._looks_like_image_url(href):
                    continue
                resolved = self._resolve_image_url(href)
                if (
                    not resolved
                    or resolved in seen
                    or Path(urlparse(resolved).path).suffix.lower()
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
            r"[-/]\d*[dc]\.(?:jpe?g|png)$", lower_url
        ):
            return True
        return False

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
        # 1. Try finding bold/strong labels anywhere
        for tag in soup.find_all(["b", "strong"]):
            text = tag.get_text().strip().rstrip(":").upper()
            if any(h == text for h in target_headings):
                # Found the heading
                collected = []
                curr = tag.next_sibling
                while curr:
                    if isinstance(curr, Tag):
                        # Stop if we hit another bold tag (likely another heading)
                        # But ignore tiny tags like <a> inside the text
                        tag_text = curr.get_text().strip()
                        if curr.name in ["b", "strong"] and len(tag_text) > 2:
                            break
                        if curr.name == "br":
                            collected.append("\n")
                        else:
                            collected.append(curr.get_text())
                    elif isinstance(curr, NavigableString):
                        collected.append(str(curr))
                    curr = curr.next_sibling

                val = "".join(collected).strip()
                if val:
                    # Normalize whitespace
                    return " ".join(val.split())

        # 2. Try regex search in the whole text if direct tag match fails
        text = self._extract_garnstudio_text(soup)
        if not text:
            return None

        lines = [line.rstrip() for line in text.splitlines()]
        start_index = -1
        for i, line in enumerate(lines):
            upper = line.strip().rstrip(":").upper()
            if any(h == upper for h in target_headings):
                start_index = i
                break

        if start_index == -1:
            return None

        # Headers that indicate the end of this section
        stop_headings = {
            "GARN",
            "YARN",
            "MATERIAL",
            "MATERIALS",
            "NADELN",
            "NEEDLES",
            "PINNER",
            "MASCHENPROBE",
            "GAUGE",
            "STRIKKEFASTHET",
            "ABMESSUNGEN",
            "MEASUREMENTS",
            "DIE ARBEIT BEGINNT HIER",
            "START HERE",
            "KURZBESCHREIBUNG",
        }

        collected_lines: list[str] = []
        for line in lines[start_index + 1 :]:
            stripped = line.strip()
            if stripped:
                label = stripped[:-1] if stripped.endswith(":") else stripped
                if label.isupper() and label in stop_headings:
                    break
            collected_lines.append(line)

        val = "\n".join(collected_lines).strip()
        if val:
            return " ".join(val.split())
        return None

    def _extract_garnstudio_yarn(self, soup: BeautifulSoup) -> str | None:
        """Extract yarn list from Garnstudio pattern."""
        text = self._extract_garnstudio_text(soup)
        if not text:
            return None

        lines = [line.rstrip() for line in text.splitlines()]
        yarn_headings = {"GARN", "YARN", "MATERIAL", "MATERIALS"}

        start_index = -1
        for i, line in enumerate(lines):
            upper = line.strip().rstrip(":").upper()
            if upper in yarn_headings:
                start_index = i
                break

        if start_index == -1:
            return None

        stop_headings = {
            "NADELN",
            "NEEDLES",
            "PINNER",
            "MASCHENPROBE",
            "GAUGE",
            "STRIKKEFASTHET",
            "ABMESSUNGEN",
            "MEASUREMENTS",
            "DIE ARBEIT BEGINNT HIER",
            "START HERE",
            "KURZBESCHREIBUNG",
        }

        collected: list[str] = []
        for line in lines[start_index + 1:]:
            stripped = line.strip()
            if stripped:
                label = stripped[:-1] if stripped.endswith(":") else stripped
                if label.isupper() and label in stop_headings:
                    break
            collected.append(line)

        return "\n".join(collected).strip() or None

    def _extract_garnstudio_text(self, soup: BeautifulSoup) -> str:
        """Extract clean text from Garnstudio pattern page."""
        # Target specific content areas
        parts = []
        # Garnstudio uses these classes for the actual pattern data
        selectors = [
            ".pattern-info",
            ".pattern-instructions",
            ".print-diagrams",
        ]

        for selector in selectors:
            container = soup.select_one(selector)
            if container:
                # Get text with newlines to preserve structure
                text = container.get_text(separator="\n", strip=True)
                if text:
                    parts.append(text)

        if parts:
            return "\n\n".join(parts)

        # Fallback to older logic if specific containers not found
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

    async def fetch_and_parse(self, image_limit: int = 10) -> dict[str, Any]:
        """Fetch URL and extract data with Garnstudio post-processing."""
        # Use -1 to get all images for diagram extraction
        data = await super().fetch_and_parse(image_limit=-1)

        # Identify diagrams in image_urls
        diagrams = [
            url for url in data.get("image_urls", []) if self._is_diagram_url(url)
        ]

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

        # Slice back to the requested limit
        data["image_urls"] = data["image_urls"][:image_limit]
        return data
