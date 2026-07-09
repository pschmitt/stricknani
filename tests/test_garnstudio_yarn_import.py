from pathlib import Path
from unittest.mock import MagicMock, patch

import pytest

from stricknani.utils.importer import GarnstudioPatternImporter

FIXTURE_DIR = Path(__file__).parent / "fixtures" / "garnstudio"


@pytest.mark.asyncio
async def test_garnstudio_pattern_to_yarn_links_extraction() -> None:
    """Test that yarn links and images are extracted from a Garnstudio pattern page."""
    url = "https://www.garnstudio.com/pattern.php?id=12174&cid=9"
    importer = GarnstudioPatternImporter(url)

    html = (FIXTURE_DIR / "pattern_12174.html").read_text(encoding="utf-8")
    with patch("httpx.AsyncClient.get") as mock_get:
        mock_response = MagicMock()
        mock_response.text = html
        mock_response.status_code = 200
        mock_get.return_value = mock_response
        data = await importer.fetch_and_parse()

    assert data.get("title") == "Violet Reverie"
    yarn_details = data.get("yarn_details", [])
    assert len(yarn_details) > 0

    # Check for Kid-Silk link
    kid_silk = next((y for y in yarn_details if "KID-SILK" in y["name"].upper()), None)
    assert kid_silk is not None
    assert "yarn.php?show=drops-kid-silk" in kid_silk["link"]
    assert kid_silk.get("image_url") is not None
    assert "shademap/kid-silk" in kid_silk["image_url"]


@pytest.mark.asyncio
async def test_garnstudio_yarn_page_extraction() -> None:
    """Test detailed metadata extraction from a Garnstudio yarn page."""
    url = "https://www.garnstudio.com/yarn.php?show=drops-kid-silk&cid=9"
    importer = GarnstudioPatternImporter(url)

    html = (FIXTURE_DIR / "yarn_drops-kid-silk.html").read_text(encoding="utf-8")
    with patch("httpx.AsyncClient.get") as mock_get:
        mock_response = MagicMock()
        mock_response.text = html
        mock_response.status_code = 200
        mock_get.return_value = mock_response
        data = await importer.fetch_and_parse()

    # Name should be cleaned (no DROPS prefix, no subtitle)
    assert data.get("name") == "Kid-Silk"
    assert data.get("brand") == "DROPS"

    # Subtitle should be prepended to description
    description = data.get("description", "")
    assert "Eine wunderbare Mischung aus Kid Mohair und Seide" in description
    assert "Dieses luxuriöse, leicht angeraute Garn" in description

    # Technical specs
    assert data.get("weight_grams") == 25
    assert data.get("length_meters") == 210
    assert "3,5 mm" in data.get("needles", "")
    assert "75% Mohair" in data.get("fiber_content", "")

    # Images
    assert len(data.get("image_urls", [])) > 0
    assert any(
        "shademap/kid-silk/drops-kid-silk1.jpg" in img for img in data["image_urls"]
    )

    # Ensure related patterns didn't leak in
    assert not any("drops/mag/" in img for img in data["image_urls"])
