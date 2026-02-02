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
    assert "DROPS DAISY" in data["yarn"]
    assert "DROPS KARISMA" in data["yarn"]

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
