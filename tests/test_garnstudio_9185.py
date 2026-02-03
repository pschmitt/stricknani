import pytest
from bs4 import BeautifulSoup

from stricknani.utils.importer import GarnstudioPatternImporter


@pytest.mark.asyncio
async def test_garnstudio_9185_crochet_hook_extraction(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Test extraction of crochet hook for pattern 9185."""
    # Simplified HTML based on the actual page structure of 9185
    html = """
    <div id="material_text">
        <div class="row">
            <div class="col-sm-12">
                <b>HÄKELNADEL:</b><br>
                DROPS HÄKELNADEL Nr. 4.<br>
                Die Angabe der Nadelstärke ist nur eine Orientierungshilfe.
                Wenn für 10 cm mehr Maschen als in der Maschenprobe
                angegeben benötigt werden, zu einer dickeren Nadelstärke wechseln.
                Wenn für 10 cm weniger Maschen als in der Maschenprobe
                angegeben benötigt werden, zu einer dünneren Nadelstärke wechseln.
            </div>
        </div>
        <div class="row">
            <div class="col-sm-12">
                <b>MASCHENPROBE:</b><br>
                18 Stäbchen in der Breite und 9 Reihen in der Höhe = 10 x 10 cm.
            </div>
        </div>
    </div>
    """
    url = "https://www.garnstudio.com/pattern.php?id=9185&cid=9"
    importer = GarnstudioPatternImporter(url)

    # Mocking _extract_garnstudio_text to return a clean version of our HTML
    text_content = (
        "HÄKELNADEL:\nDROPS HÄKELNADEL Nr. 4.\n"
        "Die Angabe der Nadelstärke ist nur eine Orientierungshilfe.\n"
        "MASCHENPROBE:\n"
        "18 Stäbchen in der Breite und 9 Reihen in der Höhe = 10 x 10 cm."
    )
    monkeypatch.setattr(importer, "_extract_garnstudio_text", lambda s: text_content)

    soup = BeautifulSoup(html, "html.parser")
    needles = importer._extract_needles(soup)

    assert needles is not None
    assert "DROPS HÄKELNADEL Nr. 4" in needles
    assert "MASCHENPROBE" not in needles
