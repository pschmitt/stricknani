"""CSRF protection tests.

The rest of the suite runs with ``config.TESTING = True``, which short-circuits
the global CSRF validation dependency. These tests explicitly flip that flag off
so the real ``fastapi-csrf-protect`` token flow is exercised end to end: a POST
without a valid token must be rejected with 403, and a POST carrying the token
minted for the session must succeed.
"""

import re

from httpx import AsyncClient
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker

from stricknani.config import config

_CSRF_META_RE = re.compile(r'name="csrf-token" content="([^"]+)"')


def _extract_csrf_token(html: str) -> str:
    match = _CSRF_META_RE.search(html)
    assert match is not None, "csrf-token meta tag missing from rendered page"
    return match.group(1)


async def test_post_without_csrf_token_is_forbidden(
    test_client: tuple[
        AsyncClient,
        async_sessionmaker[AsyncSession],
        int,
        int,
        int,
    ],
) -> None:
    client, _session_factory, _user_id, project_id, _step_id = test_client

    # Turn off the test bypass so the real CSRF dependency runs.
    config.TESTING = False

    # No CSRF cookie and no token header -> validation fails fast.
    response = await client.post(f"/projects/{project_id}/favorite")

    assert response.status_code == 403


async def test_post_with_valid_csrf_token_succeeds(
    test_client: tuple[
        AsyncClient,
        async_sessionmaker[AsyncSession],
        int,
        int,
        int,
    ],
) -> None:
    client, _session_factory, _user_id, project_id, _step_id = test_client

    config.TESTING = False

    # Rendering a page mints a CSRF token: the raw token lands in the
    # csrf-token meta tag and the signed counterpart is set as a cookie
    # (the AsyncClient stores it for the follow-up request).
    page = await client.get(f"/projects/{project_id}")
    assert page.status_code == 200
    token = _extract_csrf_token(page.text)

    response = await client.post(
        f"/projects/{project_id}/favorite",
        headers={"X-CSRF-Token": token},
        follow_redirects=False,
    )

    assert response.status_code == 303
