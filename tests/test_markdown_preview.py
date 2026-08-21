"""Tests for the `/utils/preview/markdown` endpoint (T106).

Covers the auth requirement and length cap added after the audit found this
endpoint reachable by anonymous callers with no size bound - free CPU/memory
cost via repeated markdown-sanitization requests.
"""

from httpx import AsyncClient
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker

from stricknani.main import MAX_MARKDOWN_PREVIEW_CHARS, app
from stricknani.routes.auth import get_current_user, require_auth

TestClient = tuple[AsyncClient, async_sessionmaker[AsyncSession], int, int, int]


async def test_preview_markdown_renders_for_authenticated_user(
    test_client: TestClient,
) -> None:
    client, *_ = test_client

    response = await client.post(
        "/utils/preview/markdown", data={"content": "**bold**"}
    )

    assert response.status_code == 200
    assert "<strong>bold</strong>" in response.text


async def test_preview_markdown_empty_content_returns_empty_body(
    test_client: TestClient,
) -> None:
    client, *_ = test_client

    response = await client.post("/utils/preview/markdown", data={"content": ""})

    assert response.status_code == 200
    assert response.text == ""


async def test_preview_markdown_rejects_oversized_content(
    test_client: TestClient,
) -> None:
    client, *_ = test_client

    response = await client.post(
        "/utils/preview/markdown",
        data={"content": "a" * (MAX_MARKDOWN_PREVIEW_CHARS + 1)},
    )

    assert response.status_code == 413


async def test_preview_markdown_requires_authentication(
    test_client: TestClient,
) -> None:
    client, *_ = test_client

    # The fixture overrides both `require_auth` and the `get_current_user`
    # dependency it wraps (so mocking only one still resolves to the
    # overridden authenticated user); drop both to exercise the real chain.
    # The fixture clears all overrides on teardown, so no need to restore.
    del app.dependency_overrides[require_auth]
    del app.dependency_overrides[get_current_user]
    response = await client.post("/utils/preview/markdown", data={"content": "hello"})

    assert response.status_code == 401
