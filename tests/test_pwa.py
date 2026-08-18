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
    assert resp.headers.get("cache-control") == "no-cache"


@pytest.mark.anyio
async def test_service_worker_has_versioned_build_id_injected(
    test_client: tuple[AsyncClient, object, int, int, int],
) -> None:
    """The build-version placeholder must be replaced, and cache names should
    be tied to it so deploys don't serve stale HTML/CSS/JS indefinitely.
    """
    client, _session_factory, _user_id, _project_id, _step_id = test_client

    resp = await client.get("/sw.js")
    assert resp.status_code == 200
    assert "__STRICKNANI_BUILD_VERSION__" not in resp.text
    assert 'const BUILD_VERSION = "0.1.0-' in resp.text

    # A second request within the same process must reuse the same build id
    # (it's derived once at startup), so cache names stay stable across
    # requests but change on the next deploy/process restart.
    resp2 = await client.get("/sw.js")
    assert resp.text == resp2.text


@pytest.mark.anyio
async def test_service_worker_implements_offline_caching_strategies(
    test_client: tuple[AsyncClient, object, int, int, int],
) -> None:
    client, _session_factory, _user_id, _project_id, _step_id = test_client

    resp = await client.get("/sw.js")
    assert resp.status_code == 200
    text = resp.text
    assert 'addEventListener("install"' in text
    assert 'addEventListener("activate"' in text
    assert 'addEventListener("fetch"' in text
    assert "/offline" in text
    # base.html links this for core styling; a cold-offline install must not
    # render unstyled just because it was missing from the precache list.
    assert "/static/css/tailwind.css" in text


@pytest.mark.anyio
async def test_offline_fallback_page_is_served(
    test_client: tuple[AsyncClient, object, int, int, int],
) -> None:
    client, _session_factory, _user_id, _project_id, _step_id = test_client

    resp = await client.get("/offline")
    assert resp.status_code == 200
    assert "text/html" in resp.headers.get("content-type", "")
    assert "offline" in resp.text.lower()


@pytest.mark.anyio
async def test_base_html_includes_offline_status_banner(
    test_client: tuple[AsyncClient, object, int, int, int],
) -> None:
    client, _session_factory, _user_id, _project_id, _step_id = test_client

    resp = await client.get("/login")
    assert resp.status_code == 200
    assert 'id="offlineBanner"' in resp.text
