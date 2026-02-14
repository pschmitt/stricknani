import json

import pytest
from httpx import AsyncClient


@pytest.mark.anyio
async def test_manifest_is_served(
    test_client: tuple[AsyncClient, object, int, int, int],
) -> None:
    client, _session_factory, _user_id, _project_id, _step_id = test_client

    resp = await client.get("/manifest.webmanifest")
    assert resp.status_code == 200
    assert "application/manifest+json" in resp.headers.get("content-type", "")

    data = json.loads(resp.text)
    assert data["name"] == "Stricknani"
    assert data["start_url"] == "/"
    icons = {icon["sizes"]: icon["src"] for icon in data.get("icons", [])}
    assert "192x192" in icons
    assert "512x512" in icons


@pytest.mark.anyio
async def test_service_worker_is_served(
    test_client: tuple[AsyncClient, object, int, int, int],
) -> None:
    client, _session_factory, _user_id, _project_id, _step_id = test_client

    resp = await client.get("/sw.js")
    assert resp.status_code == 200
    assert "javascript" in resp.headers.get("content-type", "")
    assert "service worker" in resp.text.lower()
