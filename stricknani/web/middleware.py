"""Security-related response middleware (T58)."""

from __future__ import annotations

from collections.abc import Awaitable, Callable

from starlette.middleware.base import BaseHTTPMiddleware
from starlette.requests import Request
from starlette.responses import Response

from stricknani.config import config

# Baseline Content-Security-Policy.
#
# This is intentionally PERMISSIVE and must not break the current app. Today
# the frontend relies on the runtime Tailwind browser build (which uses
# eval) plus a number of inline <script>/<style> blocks, so we cannot use a
# nonce-based, unsafe-inline-free policy without breaking rendering. All
# scripts are still served from our own origin (vendored under /static), so
# this policy keeps meaningful protections (frame-ancestors, base-uri,
# object-src, form-action) while allowing the inline/eval bits the current
# UI needs.
#
# TODO(T1/T36): once runtime Tailwind is replaced by a prebuilt static bundle
# (T1) and templated inline JS/CSS is minimized (T36), tighten this to a
# nonce-based policy and drop 'unsafe-inline'/'unsafe-eval'.
_CONTENT_SECURITY_POLICY = "; ".join(
    [
        "default-src 'self'",
        "base-uri 'self'",
        "object-src 'none'",
        "frame-ancestors 'none'",
        "form-action 'self'",
        "img-src 'self' data: blob: https:",
        "font-src 'self' data:",
        "style-src 'self' 'unsafe-inline'",
        "script-src 'self' 'unsafe-inline' 'unsafe-eval'",
        "connect-src 'self' https:",
    ]
)

_HSTS_VALUE = "max-age=31536000; includeSubDomains"


def _is_secure_request(request: Request) -> bool:
    """Best-effort detection of whether the request arrived over TLS.

    Honours ``X-Forwarded-Proto`` for deployments behind a TLS-terminating
    reverse proxy.
    """
    if request.url.scheme == "https":
        return True
    forwarded = request.headers.get("x-forwarded-proto", "")
    return "https" in forwarded.lower().split(",")[0].strip()


class SecurityHeadersMiddleware(BaseHTTPMiddleware):
    """Attach a baseline set of security response headers to every response."""

    async def dispatch(
        self,
        request: Request,
        call_next: Callable[[Request], Awaitable[Response]],
    ) -> Response:
        response = await call_next(request)
        headers = response.headers
        headers.setdefault("X-Content-Type-Options", "nosniff")
        headers.setdefault("X-Frame-Options", "DENY")
        headers.setdefault("Referrer-Policy", "strict-origin-when-cross-origin")
        headers.setdefault("Content-Security-Policy", _CONTENT_SECURITY_POLICY)
        # Only advertise HSTS on real TLS connections in production; sending it
        # over plaintext or in DEBUG would be wrong (and could lock out local
        # http:// development).
        if not config.DEBUG and _is_secure_request(request):
            headers.setdefault("Strict-Transport-Security", _HSTS_VALUE)
        return response
