"""Tests for yarn import functionality."""

from typing import Any
from unittest.mock import MagicMock, patch

import pytest

from stricknani.config import config


@pytest.mark.asyncio
async def test_import_yarn_wolle_roedel(test_client: Any) -> None:
    """Test importing yarn from Wolle Roedel."""
    client, _, _, _, _ = test_client

    # Mock HTML from the URL provided by the user
    mock_html = """
    <html>
        <head>
            <title>Creative Melange Alpaca Wonderball dk | Autumn | 3725351</title>
        </head>
        <body>
            <h1>Rico Design Creative Melange Alpaca Wonderball dk 100g 300m</h1>
            <div class="product-info">
                <h2>Produktbeschreibung</h2>
    <p>Wonderball DK ist die Alpaka-Variante zu dem Garn
    Creative Melange Alpaca Wonderball Aran.</p>
    <ul>
        <li>Zusammensetzung: 42% Alpaka, 40% Polyacryl, 18% Wolle</li>
                    <li>Lauflänge: 300m / 100g</li>
                    <li>Nadelstärke: 4-4,5mm</li>
                    <li>Farbe: Autumn</li>
                    <li>Maschenprobe: 22M und 28R = 10 x 10 cm</li>
                    <li>Verbrauch: Gr. 40 = ca. 500 g</li>
                    <li>Pflege: 30 Grad Wollwäsche/Feinwäsche</li>
                </ul>
                <div class="manufacturer">
                    Hersteller: Rico Design
                </div>
            </div>
        </body>
    </html>
    """

    with patch("stricknani.importing.fetch.fetch_url") as mock_get:
        mock_response = MagicMock()
        mock_response.text = mock_html
        mock_response.status_code = 200
        mock_get.return_value = mock_response

        response = await client.post(
            "/yarn/import",
            data={
                "url": "https://www.wolle-roedel.com/rico-design-creative-melange-alpaca-wonderball-dk-100g-300m/3725351",
            },
        )

    assert response.status_code == 200
    data = response.json()

    assert data["name"] == "Rico Design Creative Melange Alpaca Wonderball dk"
    assert data["brand"] == "Rico Design"
    assert data["colorway"] == "Autumn"
    assert data["weight_grams"] == 100
    assert data["length_meters"] == 300
    assert data["fiber_content"] == "42% Alpaka, 40% Polyacryl, 18% Wolle"
    assert data["recommended_needles"] == "4-4,5mm"


@pytest.mark.asyncio
async def test_import_yarn_from_url_is_rate_limited(test_client: Any) -> None:
    """T107: URL imports had no cap, letting a user make the server fetch an
    attacker-influenced remote URL on their behalf as often as they like -
    the SSRF guard (T52) restricts *where* it fetches from, not *how often*."""
    client, _, _, _, _ = test_client

    original_max = config.RATE_LIMIT_IMPORT_MAX_ATTEMPTS
    config.RATE_LIMIT_IMPORT_MAX_ATTEMPTS = 1
    try:
        with patch("stricknani.importing.fetch.fetch_url") as mock_get:
            mock_response = MagicMock()
            mock_response.text = "<html></html>"
            mock_response.status_code = 200
            mock_get.return_value = mock_response

            first = await client.post(
                "/yarn/import", data={"url": "https://example.com/pattern"}
            )
            assert first.status_code == 200

            blocked = await client.post(
                "/yarn/import", data={"url": "https://example.com/pattern"}
            )
    finally:
        config.RATE_LIMIT_IMPORT_MAX_ATTEMPTS = original_max

    assert blocked.status_code == 429
    assert "Retry-After" in blocked.headers
