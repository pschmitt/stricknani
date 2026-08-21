"""Security-related response middleware (T58, tightened to a nonce-based CSP in T71)."""

from __future__ import annotations

import secrets
from collections.abc import Awaitable, Callable

from starlette.middleware.base import BaseHTTPMiddleware
from starlette.requests import Request
from starlette.responses import Response

from stricknani.config import config

# Strict, nonce-based Content-Security-Policy (T71).
#
# Runtime Tailwind (T1) and templated inline JS/CSS (T36) have both been
# removed/minimized, so we no longer need 'unsafe-inline'/'unsafe-eval' for
# script-src. A handful of small inline <script> blocks remain (mostly
# templated i18n strings that need server-rendered values); those are
# allow-listed per-request via a random nonce instead of blanket
# 'unsafe-inline'. There are no remaining inline <style> blocks or style=""
# attributes, so style-src can drop 'unsafe-inline' entirely.
_CONTENT_SECURITY_POLICY_TEMPLATE = "; ".join(
    [
        "default-src 'self'",
        "base-uri 'self'",
        "object-src 'none'",
        "frame-ancestors 'none'",
        "form-action 'self'",
        "img-src 'self' data: blob: https:",
        "font-src 'self' data:",
        "style-src 'self'",
        "script-src 'self' 'nonce-{nonce}'",
        "connect-src 'self' https:",
    ]
)

_HSTS_VALUE = "max-age=31536000; includeSubDomains"


def generate_csp_nonce() -> str:
    """Generate a fresh per-request CSP nonce.

    Uses a URL-safe base64 token; per the CSP spec the nonce is matched
    byte-for-byte against the ``nonce`` attribute on `<script>` tags, so
    templates must render the exact same value handed out here.
    """
    return secrets.token_urlsafe(16)


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
        # Generate the nonce before calling the downstream app so route
        # handlers/templates (which share this same `request` object) can
        # read it back via `request.state.csp_nonce` while rendering.
        nonce = generate_csp_nonce()
        request.state.csp_nonce = nonce

        response = await call_next(request)
        headers = response.headers
        headers.setdefault("X-Content-Type-Options", "nosniff")
        headers.setdefault("X-Frame-Options", "DENY")
        headers.setdefault("Referrer-Policy", "strict-origin-when-cross-origin")
        headers.setdefault(
            "Content-Security-Policy",
            _CONTENT_SECURITY_POLICY_TEMPLATE.format(nonce=nonce),
        )
        # Only advertise HSTS on real TLS connections in production; sending it
        # over plaintext or in DEBUG would be wrong (and could lock out local
        # http:// development).
        if not config.DEBUG and _is_secure_request(request):
            headers.setdefault("Strict-Transport-Security", _HSTS_VALUE)
        return response
