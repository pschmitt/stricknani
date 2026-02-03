from typing import Any

import pytest


@pytest.mark.asyncio
async def test_healthz(test_client: Any) -> None:
    """Verify that the health check endpoint responds with 200 OK."""
    client, _, _, _, _ = test_client
    response = await client.get("/healthz")
    assert response.status_code == 200
    assert response.json() == {"status": "ok"}


@pytest.mark.asyncio
async def test_root_redirect(test_client: Any) -> None:
    """Verify that the root endpoint redirects to projects."""
    client, _, _, _, _ = test_client
    response = await client.get("/", follow_redirects=False)
    assert response.status_code == 303
    assert response.headers["location"] == "/projects"


@pytest.mark.asyncio
async def test_index_smoke(test_client: Any) -> None:
    """Smoke test to ensure the main page (after redirect) renders without errors."""
    client, _, _, _, _ = test_client
    # The fixture overrides get_current_user to return an authenticated user,
    # so /projects/ should render the list template.
    response = await client.get("/projects/")
    assert response.status_code == 200
    assert "Stricknani" in response.text
    assert "tester@example.com" in response.text
