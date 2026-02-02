import pytest
from bs4 import BeautifulSoup

from stricknani.utils.importer import GarnstudioPatternImporter


@pytest.mark.asyncio
async def test_garnstudio_stitch_sample_greedy_noise() -> None:
    """Reproduce the issue where update notices are captured as stitch sample."""
    html = """
    <div class="pattern-info">
        <div class="maschenprobe">
            <b>MASCHENPROBE:</b><br>
            21 Maschen in der Breite und 28 Reihen in der Höhe glatt rechts
            auf Stricknadel Nr. 4 = 10 x 10 cm.<br>
            BITTE BEACHTEN: Die Angabe der Nadelstärke ist nur eine Orientierungshilfe.
            Wenn Sie auf 10 cm mehr Maschen als oben genannt haben, zu einer
            dickeren Nadelstärke wechseln. Wenn Sie auf 10 cm weniger Maschen
            als oben genannt haben, zu einer dünneren Nadelstärke wechseln.
        </div>
        <div class="updates">
            Diese Anleitung wurde korrigiert. Hier klicken, um die Korrektur(en)
            zu sehen Online aktualisiert am: 20.02.2025
            Die Anleitung wurde aktualisiert. Kleine Änderung bei der rechten
            und linken Schulter. Online aktualisiert am: 09.05.2025
            Die Anleitung wurde aktualisiert. Korrektur an der Passe.
        </div>
        <h3>HINWEISE ZUR ANLEITUNG:</h3>
        <p>Some notes here...</p>
    </div>
    """
    soup = BeautifulSoup(html, "html.parser")
    importer = GarnstudioPatternImporter(
        "https://www.garnstudio.com/pattern.php?id=123"
    )

    stitch_sample = importer._extract_stitch_sample(soup)

    assert stitch_sample is not None
    # It should contain the gauge info
    assert "21 Maschen" in stitch_sample
    # It should NOT contain the update notice (the user's complaint)
    assert "Anleitung wurde korrigiert" not in stitch_sample
    assert "aktualisiert am" not in stitch_sample
    # It should NOT contain the next section
    assert "HINWEISE ZUR ANLEITUNG" not in stitch_sample


@pytest.mark.asyncio
async def test_garnstudio_12105_reproduction() -> None:
    """Test reproduction for pattern 12105."""
    html = """
    <div class="pattern-info">
        <span class="quickexplanation">MASCHENPROBE</span>:<br />
        21 Maschen in der Breite und 28 <span class="quickexplanation">Reihen</span>
        in der Höhe <span class="quickexplanation">glatt rechts</span>
        auf Stricknadel Nr. 4 = 10 x 10 cm. <br />
        BITTE BEACHTEN: Die Angabe der Nadelstärke ist nur eine Orientierungshilfe.
    </div>
    <div class="pattern-instructions">
        STREIFEN: <br />
        Streifen wie folgt stricken:<br />
        6 Runden mit der Farbe hellbeige / marzipan,
        2 Runden mit der Farbe glockenblume / kobaltblau.
    </div>
    """
    soup = BeautifulSoup(html, "html.parser")
    url = "https://www.garnstudio.com/pattern.php?id=12105&cid=9"
    importer = GarnstudioPatternImporter(url)

    stitch_sample = importer._extract_stitch_sample(soup)
    assert stitch_sample is not None
    # Check it's not cropped
    assert "21 Maschen" in stitch_sample
    assert "28 Reihen" in stitch_sample
    assert "10 x 10 cm" in stitch_sample

    steps = importer._extract_steps(soup)
    titles = [s["title"] for s in steps]
    assert "STREIFEN" in titles
