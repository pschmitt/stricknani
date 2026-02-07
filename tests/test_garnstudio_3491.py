import pytest

from stricknani.utils.importer import GarnstudioPatternImporter


@pytest.mark.asyncio
async def test_garnstudio_3491_outdoor_fun_yarn_needles_steps() -> None:
    url = "https://www.garnstudio.com/pattern.php?id=3491&cid=9"
    importer = GarnstudioPatternImporter(url)
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
