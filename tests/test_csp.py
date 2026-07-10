"""Content-Security-Policy tests (T71).

T58 shipped a baseline CSP that allowed `'unsafe-inline'`/`'unsafe-eval'` for
scripts as a stopgap. T71 tightens that to a strict, per-request nonce-based
policy now that runtime Tailwind (T1) and templated inline JS (T36) are gone.
These tests assert the header shape and that the nonce baked into the header
is the same one echoed into rendered `<script nonce="...">` tags, so a
page's own inline scripts are actually allowed to run under its own policy.
"""

import re

from httpx import AsyncClient
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker

_NONCE_ATTR_RE = re.compile(r'<script[^>]*\bnonce="([^"]+)"')


async def test_csp_header_uses_nonce_not_unsafe_inline(
    test_client: tuple[
        AsyncClient,
        async_sessionmaker[AsyncSession],
        int,
        int,
        int,
    ],
) -> None:
    client, _session_factory, _user_id, _project_id, _step_id = test_client

    response = await client.get("/projects/")
    assert response.status_code == 200

    csp = response.headers.get("content-security-policy")
    assert csp is not None

    script_src_directive = next(
        part.strip() for part in csp.split(";") if part.strip().startswith("script-src")
    )
    assert "'unsafe-inline'" not in script_src_directive
    assert "'unsafe-eval'" not in script_src_directive
    assert re.search(r"'nonce-[^']+'", script_src_directive), script_src_directive

    style_src_directive = next(
        part.strip() for part in csp.split(";") if part.strip().startswith("style-src")
    )
    assert "'unsafe-inline'" not in style_src_directive


async def test_csp_header_preserves_other_t58_directives(
    test_client: tuple[
        AsyncClient,
        async_sessionmaker[AsyncSession],
        int,
        int,
        int,
    ],
) -> None:
    client, _session_factory, _user_id, _project_id, _step_id = test_client

    response = await client.get("/projects/")
    csp = response.headers.get("content-security-policy")
    assert csp is not None

    for directive in [
        "default-src 'self'",
        "base-uri 'self'",
        "object-src 'none'",
        "frame-ancestors 'none'",
        "form-action 'self'",
    ]:
        assert directive in csp


async def test_csp_nonce_matches_rendered_inline_script(
    test_client: tuple[
        AsyncClient,
        async_sessionmaker[AsyncSession],
        int,
        int,
        int,
    ],
) -> None:
    """The nonce advertised in the header must equal the one on the page's
    own inline `<script nonce="...">` tags, otherwise the browser would
    refuse to execute them under this exact policy."""
    client, _session_factory, _user_id, _project_id, _step_id = test_client

    response = await client.get("/projects/")
    assert response.status_code == 200

    csp = response.headers.get("content-security-policy")
    assert csp is not None
    header_nonce_match = re.search(r"script-src[^;]*'nonce-([^']+)'", csp)
    assert header_nonce_match is not None
    header_nonce = header_nonce_match.group(1)

    body_nonces = _NONCE_ATTR_RE.findall(response.text)
    assert body_nonces, "expected at least one inline <script nonce=...> tag"
    assert all(nonce == header_nonce for nonce in body_nonces)


async def test_csp_nonce_differs_per_request(
    test_client: tuple[
        AsyncClient,
        async_sessionmaker[AsyncSession],
        int,
        int,
        int,
    ],
) -> None:
    client, _session_factory, _user_id, _project_id, _step_id = test_client

    first = await client.get("/projects/")
    second = await client.get("/projects/")

    first_nonce = re.search(
        r"script-src[^;]*'nonce-([^']+)'", first.headers["content-security-policy"]
    )
    second_nonce = re.search(
        r"script-src[^;]*'nonce-([^']+)'", second.headers["content-security-policy"]
    )
    assert first_nonce is not None
    assert second_nonce is not None
    assert first_nonce.group(1) != second_nonce.group(1)


async def test_no_inline_event_handlers_in_rendered_page(
    test_client: tuple[
        AsyncClient,
        async_sessionmaker[AsyncSession],
        int,
        int,
        int,
    ],
) -> None:
    """Regression guard: strict CSP script-src (no 'unsafe-hashes') means any
    inline on*= handler that slips back in would silently stop working."""
    client, _session_factory, _user_id, _project_id, _step_id = test_client

    response = await client.get("/projects/")
    assert response.status_code == 200
    assert not re.search(r"<[a-z][^>]*\son[a-z]+\s*=", response.text)
