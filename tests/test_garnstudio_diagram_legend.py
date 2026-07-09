from pathlib import Path
from unittest.mock import MagicMock, patch

import pytest

from stricknani.utils.importer import GarnstudioPatternImporter

FIXTURE_DIR = Path(__file__).parent / "fixtures" / "garnstudio"


@pytest.mark.asyncio
async def test_garnstudio_diagram_legend_is_attached_to_diagram_steps() -> None:
    # Crochet pattern with a diagram symbol legend table (diag_symbols).
    url = "https://www.garnstudio.com/pattern.php?id=9185&cid=9"
    importer = GarnstudioPatternImporter(url)

    html = (FIXTURE_DIR / "pattern_9185.html").read_text(encoding="utf-8")
    with patch("httpx.AsyncClient.get") as mock_get:
        mock_response = MagicMock()
        mock_response.text = html
        mock_response.status_code = 200
        mock_get.return_value = mock_response
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
