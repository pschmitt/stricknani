"""HTTP fetch helper with TLS impersonation.

garnstudio.com / dropsdesign.com (DROPS Design) sit behind Cloudflare bot
management that fingerprints the TLS/HTTP2 handshake and blocks plain ``httpx``
with an HTTP 403 "Just a moment..." interstitial regardless of the
``User-Agent`` header. ``curl_cffi`` impersonates a real Chrome TLS fingerprint,
which passes the check.

All URL imports go through :func:`fetch_url` so the impersonation is applied
consistently (it is harmless for sites that do not need it and makes imports
more robust against other Cloudflare-protected shops).
"""

from __future__ import annotations

import logging
from dataclasses import dataclass
from typing import TYPE_CHECKING
from urllib.parse import urljoin

if TYPE_CHECKING:
    from collections.abc import Mapping

logger = logging.getLogger("stricknani.imports")

# Chrome is a good default: it exercises the most common/maintained TLS
# fingerprint in curl-impersonate and is what defeats garnstudio's Cloudflare.
DEFAULT_IMPERSONATE = "chrome"

# HTTP status codes that indicate a redirect (with a Location header).
_REDIRECT_STATUSES = {301, 302, 303, 307, 308}
# Cap manual redirect following to avoid loops / redirect chains.
_MAX_REDIRECTS = 5


@dataclass
class FetchResponse:
    """Normalized HTTP response returned by :func:`fetch_url`."""

    text: str
    content: bytes
    status_code: int
    headers: dict[str, str]
    encoding: str | None = None


class FetchError(Exception):
    """Raised when a URL fetch fails (network error or HTTP error status)."""

    def __init__(self, message: str, *, status_code: int | None = None) -> None:
        """Store the message and optional HTTP status code."""
        super().__init__(message)
        self.status_code = status_code


async def fetch_url(
    url: str,
    *,
    timeout: int = 10,
    headers: Mapping[str, str] | None = None,
    follow_redirects: bool = True,
    impersonate: str = DEFAULT_IMPERSONATE,
) -> FetchResponse:
    """Fetch ``url`` using curl_cffi with a browser TLS fingerprint.

    Args:
        url: The URL to fetch.
        timeout: Request timeout in seconds.
        headers: Optional extra HTTP headers.
        follow_redirects: Whether to follow HTTP redirects.
        impersonate: curl-impersonate browser target (e.g. ``"chrome"``).

    Returns:
        A :class:`FetchResponse` with the decoded body and metadata.

    Raises:
        FetchError: If the request fails or returns an error status.
        SSRFError: If the URL (or a redirect target) resolves to a
            private/loopback/reserved address.
    """
    from curl_cffi.requests import AsyncSession
    from curl_cffi.requests.exceptions import RequestException

    from stricknani.importing.ssrf import validate_public_url

    request_headers = dict(headers) if headers else None

    # SSRF guard: reject the initial URL before making any outbound request.
    # Redirects are followed manually below (curl_cffi's automatic redirect
    # handling would connect to a redirect target before we could inspect it),
    # re-validating each hop's Location so a redirect cannot be used to reach an
    # internal host.
    validate_public_url(url)

    current_url = url
    try:
        async with AsyncSession() as session:
            for _ in range(_MAX_REDIRECTS + 1):
                response = await session.get(
                    current_url,
                    timeout=timeout,
                    headers=request_headers,
                    allow_redirects=False,
                    impersonate=impersonate,
                )
                location = response.headers.get("location")
                if (
                    follow_redirects
                    and response.status_code in _REDIRECT_STATUSES
                    and location
                ):
                    current_url = urljoin(current_url, location)
                    validate_public_url(current_url)
                    continue
                break
            else:
                raise FetchError(f"Too many redirects for {url}")
            response.raise_for_status()
    except RequestException as exc:
        status = getattr(getattr(exc, "response", None), "status_code", None)
        raise FetchError(str(exc), status_code=status) from exc

    normalized_headers = {
        str(key).lower(): str(value) for key, value in response.headers.items()
    }
    logger.debug(
        "Fetched %s %s (impersonate=%s)",
        response.status_code,
        normalized_headers.get("content-type", ""),
        impersonate,
    )
    return FetchResponse(
        text=response.text,
        content=response.content,
        status_code=response.status_code,
        headers=normalized_headers,
        encoding=getattr(response, "encoding", None),
    )


def import_fetch_http_error(exc: Exception) -> tuple[int, str]:
    """Map a fetch/SSRF failure to an ``(HTTP status, user message)`` pair.

    Used at the web import boundary so a fetch failure produces a friendly
    4xx/5xx response instead of an HTTP 500 that leaks the raw exception string.

    Args:
        exc: The exception raised while fetching an import URL.

    Returns:
        A ``(status_code, detail)`` tuple with a user-facing message that does
        not echo the raw exception.
    """
    from stricknani.importing.ssrf import SSRFError

    if isinstance(exc, SSRFError):
        return 400, "The URL is not allowed."
    if isinstance(exc, FetchError):
        status = exc.status_code
        if status is not None and 400 <= status < 500:
            return 400, "Could not fetch the URL (the page returned an error)."
        if status is not None and 500 <= status < 600:
            return 502, "The remote server returned an error."
        # No HTTP status code means a network/timeout/connection failure.
        return 504, "Could not reach the URL (the request timed out or failed)."
    return 500, "Failed to import the URL."


__all__ = [
    "DEFAULT_IMPERSONATE",
    "FetchError",
    "FetchResponse",
    "fetch_url",
    "import_fetch_http_error",
]
