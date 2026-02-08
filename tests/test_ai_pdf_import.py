from __future__ import annotations

from unittest.mock import AsyncMock, patch

import pytest

from stricknani.importing.extractors.ai import AIExtractor
from stricknani.importing.models import ContentType, ExtractedData, RawContent


@pytest.mark.asyncio
async def test_ai_extractor_pdf_routes_via_text_extraction() -> None:
    extractor = AIExtractor(api_key="test-key")

    pdf_content = RawContent(
        content=b"%PDF-1.4 fake",
        content_type=ContentType.PDF,
        metadata={"filename": "test.pdf"},
    )

    with (
        patch("stricknani.importing.extractors.ai.OPENAI_AVAILABLE", True),
        patch(
            "stricknani.importing.extractors.pdf.PDFExtractor.can_extract",
            return_value=True,
        ),
        patch(
            "stricknani.importing.extractors.pdf.PDFExtractor.extract",
            new=AsyncMock(
                return_value=ExtractedData(
                    name=None,
                    description=None,
                    extras={"full_text": "Some PDF text"},
                )
            ),
        ),
        patch.object(
            AIExtractor,
            "_extract_from_text",
            new=AsyncMock(return_value=ExtractedData(name="ok")),
        ) as mock_text,
        patch.object(
            AIExtractor,
            "_extract_from_image",
            new=AsyncMock(side_effect=AssertionError("should not be called")),
        ),
    ):
        result = await extractor.extract(pdf_content)

    assert result.name == "ok"
    assert mock_text.await_count == 1
    assert mock_text.call_args is not None
    called_raw = mock_text.call_args.args[0]
    assert isinstance(called_raw, RawContent)
    assert called_raw.content_type == ContentType.TEXT
