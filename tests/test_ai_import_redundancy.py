"""Tests for AI import redundancy reduction."""

from typing import TYPE_CHECKING
from unittest.mock import MagicMock, patch

import pytest

if TYPE_CHECKING:
    from httpx import AsyncClient
    from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker

    TestClientFixture = tuple[
        AsyncClient,
        async_sessionmaker[AsyncSession],
        int,
        int,
        int,
    ]


@pytest.mark.asyncio
async def test_ai_import_minimizes_redundancy(test_client: "TestClientFixture") -> None:
    """Test that AI import logic and prompt aim to reduce redundancy."""
    client, _, _, _, _ = test_client

    mock_html = (
        "<html><body><h1>Redundant Pattern</h1>"
        "<p>Notes: Knit 10 rows.</p></body></html>"
    )

    # AI returns the same info in description and steps
    mock_ai_response = {
        "title": "Redundant Pattern",
        "description": "Instruction Notes: Knit 10 rows.",
        "steps": [
            {
                "step_number": 1,
                "title": "Main Part",
                "description": "Instruction Notes: Knit 10 rows.",
            }
        ],
        "image_urls": [],
        "notes": "Instruction Notes: Knit 10 rows.",
    }

    with (
        patch("httpx.AsyncClient.get") as mock_get,
        patch(
            "stricknani.utils.ai_importer.AIPatternImporter.fetch_and_parse"
        ) as mock_ai,
        patch("os.getenv") as mock_env,
    ):
        mock_response = MagicMock()
        mock_response.text = mock_html
        mock_response.status_code = 200
        mock_get.return_value = mock_response
        mock_ai.return_value = mock_ai_response
        mock_env.return_value = "fake-key"

        response = await client.post(
            "/projects/import",
            data={
                "type": "url",
                "url": "https://www.example.com/pattern/123",
                "use_ai": True,
            },
        )

        assert response.status_code == 200
        data = response.json()

    # Even if AI returns it in both, our merging logic should ideally NOT
    # duplicate it further.
    # Note: Our code currently appends Garnstudio notes if missing.
    # But if it's already in description, it shouldn't append.

    # Check that description doesn't have it THREE times
    # (1 from AI description, 1 from AI notes merge, 1 from manual notes block)
    # The fix I implemented ensures it's only added if NOT in description
    # AND NOT in steps.

    desc = data.get("description", "")
    assert desc.count("Knit 10 rows") == 1
