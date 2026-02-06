import pytest

from stricknani.utils.importer import GarnstudioPatternImporter


@pytest.mark.asyncio
async def test_garnstudio_diagram_legend_is_attached_to_diagram_steps() -> None:
    # Crochet pattern with a diagram symbol legend table (diag_symbols).
    url = "https://www.garnstudio.com/pattern.php?id=9185&cid=9"
    importer = GarnstudioPatternImporter(url)

    data = await importer.fetch_and_parse()

    steps = data.get("steps", [])
    assert isinstance(steps, list)
    diagram_steps = [
        s
        for s in steps
        if isinstance(s, dict)
        and isinstance(s.get("title"), str)
        and "diagram" in s["title"].lower()
    ]
    assert diagram_steps, "Expected at least one diagram step"

    # Legend is a mix of images (drops/symbols/...) and text labels (e.g. Luftmasche).
    descriptions = "\n\n".join(
        str(s.get("description") or "") for s in diagram_steps if isinstance(s, dict)
    )
    assert "drops/symbols/" in descriptions
    assert "Luftmasche" in descriptions
