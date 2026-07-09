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

if TYPE_CHECKING:
    from collections.abc import Mapping

logger = logging.getLogger("stricknani.imports")

# Chrome is a good default: it exercises the most common/maintained TLS
# fingerprint in curl-impersonate and is what defeats garnstudio's Cloudflare.
DEFAULT_IMPERSONATE = "chrome"


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
    """
    from curl_cffi.requests import AsyncSession
    from curl_cffi.requests.exceptions import RequestException

    request_headers = dict(headers) if headers else None
    try:
        async with AsyncSession() as session:
            response = await session.get(
                url,
                timeout=timeout,
                headers=request_headers,
                allow_redirects=follow_redirects,
                impersonate=impersonate,
            )
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


__all__ = ["DEFAULT_IMPERSONATE", "FetchError", "FetchResponse", "fetch_url"]
