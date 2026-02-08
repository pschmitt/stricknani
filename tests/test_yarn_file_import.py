"Tests for yarn file import functionality."

from typing import Any
from unittest.mock import AsyncMock, patch

import pytest

from stricknani.importing.models import ExtractedData


@pytest.mark.asyncio
async def test_import_yarn_from_file_with_ai(test_client: Any) -> None:
    """Test importing yarn from a file using mocked AI."""
    client = test_client[0]  # Unpack test_client tuple if needed

    # Mock extracted data
    mock_extracted = ExtractedData(
        name="Mock Yarn",
        brand="Mock Brand",
        colorway="Blue",
        weight_category="DK",
        fiber_content="100% Wool",
        needles="4mm",
        description="A nice mock yarn",
        extras={"weight_grams": 50, "length_meters": 100},
    )

    # Mock OPENAI_AVAILABLE and os.getenv to simulate AI enabled
    # Patch where they are defined/imported
    with (
        patch("stricknani.importing.extractors.ai.OPENAI_AVAILABLE", True),
        patch("os.getenv", return_value="sk-test"),
        patch("stricknani.routes.yarn.config.FEATURE_AI_IMPORT_ENABLED", True),
        patch("stricknani.importing.extractors.ai.AIExtractor") as MockAIExtractor,
    ):
        mock_instance = MockAIExtractor.return_value
        mock_instance.extract = AsyncMock(return_value=mock_extracted)

        # Create a dummy file
        files = {"file": ("yarn_label.jpg", b"fake_image_content", "image/jpeg")}
        data = {"type": "file", "use_ai": "true"}

        response = await client.post("/yarn/import", data=data, files=files)

        assert response.status_code == 200
        json_data = response.json()

        assert json_data["name"] == "Mock Yarn"
        assert json_data["brand"] == "Mock Brand"
        assert json_data["colorway"] == "Blue"
        assert json_data["weight_category"] == "DK"
        assert json_data["fiber_content"] == "100% Wool"
        assert json_data["recommended_needles"] == "4mm"
        assert json_data["weight_grams"] == 50
        assert json_data["length_meters"] == 100
        assert json_data["is_ai_enhanced"] is True


@pytest.mark.asyncio
async def test_import_yarn_from_text_with_ai(test_client: Any) -> None:
    """Test importing yarn from text using mocked AI."""
    client = test_client[0]

    mock_extracted = ExtractedData(
        name="Text Yarn", brand="Text Brand", description="Text description", extras={}
    )

    with (
        patch("stricknani.importing.extractors.ai.OPENAI_AVAILABLE", True),
        patch("os.getenv", return_value="sk-test"),
        patch("stricknani.routes.yarn.config.FEATURE_AI_IMPORT_ENABLED", True),
        patch("stricknani.importing.extractors.ai.AIExtractor") as MockAIExtractor,
    ):
        mock_instance = MockAIExtractor.return_value
        mock_instance.extract = AsyncMock(return_value=mock_extracted)

        data = {"type": "text", "text": "Some yarn text", "use_ai": "true"}

        response = await client.post("/yarn/import", data=data)

        assert response.status_code == 200
        json_data = response.json()

        assert json_data["name"] == "Text Yarn"
        assert json_data["brand"] == "Text Brand"
        assert json_data["is_ai_enhanced"] is True
