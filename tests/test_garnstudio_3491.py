from pathlib import Path
from unittest.mock import MagicMock, patch

import pytest

from stricknani.utils.importer import GarnstudioPatternImporter

FIXTURE_DIR = Path(__file__).parent / "fixtures" / "garnstudio"


@pytest.mark.asyncio
async def test_garnstudio_3491_outdoor_fun_yarn_needles_steps() -> None:
    url = "https://www.garnstudio.com/pattern.php?id=3491&cid=9"
    importer = GarnstudioPatternImporter(url)

    html = (FIXTURE_DIR / "pattern_3491.html").read_text(encoding="utf-8")
    with patch("stricknani.importing.fetch.fetch_url") as mock_get:
        mock_response = MagicMock()
        mock_response.text = html
        mock_response.status_code = 200
        mock_get.return_value = mock_response
        data = await importer.fetch_and_parse(image_limit=0)

    assert data.get("title") == "Outdoor Fun"

    # Yarn: should not leak notions like needles/buttons into the yarn text.
    yarn = data.get("yarn", "")
    assert "DROPS Alaska" in yarn
    assert "DROPS Nadelspiel" not in yarn
    assert "DROPS Knopf" not in yarn

    yarn_details = data.get("yarn_details") or []
    assert len(yarn_details) == 1
    assert "Alaska" in (yarn_details[0].get("name") or "")
    assert "yarn.php?show=drops-alaska" in (yarn_details[0].get("link") or "")

    # Needles: should not pick up navigation/category garbage.
    needles = data.get("needles", "") or ""
    assert "& Häkelnadeln" not in needles
    assert "Nadelspiel" in needles

    # Steps: sections in mixed-case should become step titles.
    titles = [s["title"] for s in data.get("steps", [])]
    assert "HUNDEPULLOVER" in titles
    assert "Oberer Teil" in titles
    assert "Unterer Teil" in titles
    assert "Häkelkante" in titles
    assert "= 38-50-62 M." not in titles

    # Description: technical notes should not include the actual instructions/steps.
    description = data.get("description") or ""
    assert "ZUNAHMETIPP" in description
    assert "HUNDEPULLOVER" not in description
    assert "Oberer Teil" not in description
