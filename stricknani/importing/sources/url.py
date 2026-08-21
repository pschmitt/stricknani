"""URL-based import source.

Fetches content from HTTP/HTTPS URLs.
"""

from __future__ import annotations

import logging
from typing import TYPE_CHECKING

from stricknani.importing.models import ContentType, ImportSourceType, RawContent
from stricknani.importing.sources import ImportSource, ImportSourceError

if TYPE_CHECKING:
    from collections.abc import Mapping

    from stricknani.importing.fetch import FetchResponse

logger = logging.getLogger("stricknani.imports")


class URLSource(ImportSource):
    """Import source that fetches content from a URL."""

    def __init__(
        self,
        url: str,
        *,
        timeout: int = 10,
        headers: Mapping[str, str] | None = None,
        follow_redirects: bool = True,
        source_id: str | None = None,
    ) -> None:
        """Initialize the URL source.

        Args:
            url: The URL to fetch
            timeout: HTTP request timeout in seconds
            headers: Optional custom HTTP headers
            follow_redirects: Whether to follow HTTP redirects
            source_id: Optional identifier for this source
        """
        super().__init__(source_id=source_id)
        self.url = url
        self.timeout = timeout
        self.headers = dict(headers) if headers else {}
        self.follow_redirects = follow_redirects

        # Default headers to appear more like a browser
        if "User-Agent" not in self.headers:
            self.headers["User-Agent"] = (
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                "AppleWebKit/537.36 (KHTML, like Gecko) "
                "Chrome/120.0.0.0 Safari/537.36"
            )

    @property
    def source_type(self) -> ImportSourceType:
        """Return the type of this source."""
        return ImportSourceType.URL

    def can_fetch(self) -> bool:
        """Check if the URL is valid and can be fetched."""
        from stricknani.importing.images.validator import is_valid_import_url

        return is_valid_import_url(self.url)

    async def fetch(self) -> RawContent:
        """Fetch content from the URL.

        Returns:
            RawContent containing the fetched HTML/text

        Raises:
            ImportSourceError: If the fetch fails
        """
        if not self.can_fetch():
            raise ImportSourceError(
                f"Invalid URL: {self.url}",
                source_type=self.source_type,
            )

        logger.info("Fetching content from %s", self.url)

        # Use curl_cffi (via fetch_url) with a browser TLS fingerprint so that
        # Cloudflare-protected sites (e.g. garnstudio.com) are not blocked with
        # an HTTP 403 "Just a moment..." interstitial.
        from stricknani.importing.fetch import FetchError, fetch_url

        try:
            response = await fetch_url(
                self.url,
                timeout=self.timeout,
                headers=self.headers,
                follow_redirects=self.follow_redirects,
            )
        except FetchError as exc:
            if exc.status_code is not None:
                raise ImportSourceError(
                    f"HTTP {exc.status_code} for {self.url}",
                    source_type=self.source_type,
                ) from exc
            raise ImportSourceError(
                f"Failed to fetch {self.url}: {exc}",
                source_type=self.source_type,
            ) from exc

        # Determine content type
        content_type = self._detect_content_type(response)

        # Get text content for HTML/text types
        if content_type in (ContentType.HTML, ContentType.TEXT, ContentType.MARKDOWN):
            text = response.text
        else:
            text = response.content.decode("utf-8", errors="replace")

        logger.debug(
            "Fetched %s bytes (type: %s) from %s",
            len(response.content),
            content_type.name,
            self.url,
        )

        return RawContent(
            content=text,
            content_type=content_type,
            source_url=self.url,
            metadata={
                "status_code": response.status_code,
                "content_length": len(response.content),
                "encoding": response.encoding,
            },
        )

    def _detect_content_type(self, response: FetchResponse) -> ContentType:
        """Detect content type from response headers and URL."""
        content_type_header = response.headers.get("content-type", "").lower()

        if "text/html" in content_type_header:
            return ContentType.HTML
        elif "text/markdown" in content_type_header:
            return ContentType.MARKDOWN
        elif "text/" in content_type_header:
            return ContentType.TEXT
        elif "application/pdf" in content_type_header:
            return ContentType.PDF

        # Fallback to URL extension
        url_lower = self.url.lower()
        if url_lower.endswith((".html", ".htm", ".php")):
            return ContentType.HTML
        elif url_lower.endswith(".md"):
            return ContentType.MARKDOWN
        elif url_lower.endswith(".txt"):
            return ContentType.TEXT
        elif url_lower.endswith(".pdf"):
            return ContentType.PDF

        # Default to HTML for URLs without clear extension
        return ContentType.HTML


__all__ = ["URLSource"]
