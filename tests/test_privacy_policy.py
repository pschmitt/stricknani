"""Tests for the public web privacy policy (T90)."""

from typing import Any

import pytest


@pytest.mark.asyncio
async def test_privacy_policy_is_public_and_links_public_entry_points(
    test_client: Any,
) -> None:
    """The policy must render without authentication and be linked from auth UI."""
    client = test_client[0]

    policy_response = await client.get("/privacy")
    assert policy_response.status_code == 200
    assert "Privacy Policy" in policy_response.text
    assert "Android app and offline data" in policy_response.text
    assert "Information handled by the web app" in policy_response.text

    login_response = await client.get("/login")
    assert login_response.status_code == 200
    assert login_response.text.count('href="/privacy"') >= 2


@pytest.mark.asyncio
async def test_privacy_policy_uses_german_catalog(test_client: Any) -> None:
    """German requests render the translated policy rather than English copy."""
    client = test_client[0]

    response = await client.get("/privacy", headers={"Accept-Language": "de"})

    assert response.status_code == 200
    assert "Datenschutzerklärung" in response.text
    assert "Android-App und Offline-Daten" in response.text
    assert "Privacy Policy" not in response.text
