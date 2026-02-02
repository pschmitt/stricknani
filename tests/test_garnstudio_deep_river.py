import pytest

from stricknani.utils.importer import GarnstudioPatternImporter


@pytest.mark.asyncio
async def test_garnstudio_deep_river_cardigan() -> None:
    url = "https://www.garnstudio.com/pattern.php?id=11991&cid=9"
    importer = GarnstudioPatternImporter(url)

    # We use a real fetch here to see what trafilatura does in the real app
    data = await importer.fetch_and_parse()

    assert data["title"] == "Deep River Cardigan"

    # Check yarns
    yarn_text = data["yarn"]
    assert "DROPS DAISY" in yarn_text
    assert "DROPS KARISMA" in yarn_text

    # Simulate create_project's splitting logic
    if "\n" in yarn_text.strip():
        raw_names = []
        for line in yarn_text.splitlines():
            line = line.strip()
            if not line or line.lower() == "oder:":
                continue
            if line.lower().startswith("oder:"):
                line = line[5:].strip()
            if line:
                raw_names.append(line)
        yarn_names = raw_names
    else:
        yarn_names = [n.strip() for n in yarn_text.split(",") if n.strip()]

    assert len(yarn_names) == 2
    assert any("DROPS DAISY" in name for name in yarn_names)
    assert any("DROPS KARISMA" in name for name in yarn_names)

    # Check needles
    assert "RUNDNADELN Nr. 4" in data["needles"]
    assert "NADELSPIEL Nr. 3" in data["needles"]

    # Check stitch sample
    assert "21 Maschen" in data["stitch_sample"]
    assert "28 Reihen" in data["stitch_sample"]

    # Check description (Technical notes/HINWEISE)
    assert "KRAUSRIPPEN" in data["description"]
    assert "RAGLANZUNAHMEN" in data["description"]
    assert "KNOPFLÖCHER" in data["description"]

    # Check steps
    titles = [s["title"] for s in data["steps"]]
    print("\nExtracted Step Titles:")
    for t in titles:
        print(f"  - {t}")

    assert "JACKE - KURZBESCHREIBUNG DER ARBEIT" in titles
    assert "HALSAUSSCHNITT" in titles
    assert "RAGLANZUNAHMEN" in titles
    assert "V-AUSSCHNITT" in titles
    assert "TEILUNG FÜR DAS RUMPFTEIL UND DIE ÄRMEL" in titles
    assert "RUMPFTEIL" in titles
    assert "ÄRMEL" in titles
    assert "BLENDE" in titles

    # Check for diagrams
    diagram_step = next((s for s in data["steps"] if "Diagram" in s["title"]), None)
    assert diagram_step is not None
    assert len(diagram_step["images"]) > 0

    # Ensure no images from "related patterns" (drops/mag/...) leaked through
    # Pattern ID is 11991, drops mag images usually have the pattern ID in them
    # or mag number.
    # Related patterns mentioned earlier: mag 254/12, 252/10, 252/9
    for url in data["image_urls"]:
        assert "254/12/" not in url
        assert "252/10/" not in url
        assert "252/9/" not in url

@pytest.mark.asyncio
async def test_garnstudio_yarn_split_regression_11899() -> None:
    """Test regression for pattern 11899 where yarns were split incorrectly by comma."""
    url = "https://www.garnstudio.com/pattern.php?id=11899&cid=9"
    importer = GarnstudioPatternImporter(url)
    data = await importer.fetch_and_parse()

    yarn_text = data["yarn"]
    assert "DROPS ALPACA" in yarn_text
    assert "Farbe 9020, hell perlgrau" in yarn_text

    # Simulate create_project's splitting logic (and frontend selectByName)
    import re
    if "\n" in yarn_text.strip():
        raw_names = [n.strip() for n in yarn_text.splitlines() if n.strip()]
    else:
        if re.search(r"(?:farbe|color|colour)\s*\d+\s*,\s*", yarn_text, re.I):
            raw_names = [yarn_text.strip()]
        else:
            raw_names = [n.strip() for n in yarn_text.split(",") if n.strip()]

    assert len(raw_names) == 1
    assert "DROPS ALPACA" in raw_names[0]
    assert "hell perlgrau" in raw_names[0]

    # Verify images
    assert len(data["image_urls"]) >= 5
    # Ensure they are the correct pattern images (252/9/)
    for url in data["image_urls"]:
        assert "/252/9/" in url or "og:image" in url or "og-image" in url
