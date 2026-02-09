from __future__ import annotations

from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from stricknani.importing.extractors.ai import AIExtractor
from stricknani.importing.models import ContentType, ExtractedData, RawContent


@pytest.mark.asyncio
async def test_ai_extractor_pdf_rendering_primary() -> None:
    """Verify that PDF is rendered as images for AI extraction (primary method)."""
    extractor = AIExtractor(api_key="test-key")

    pdf_content = RawContent(
        content=b"%PDF-1.4 fake",
        content_type=ContentType.PDF,
        metadata={"filename": "test.pdf"},
    )

    mock_completion = MagicMock()
    mock_completion.choices = [
        MagicMock(message=MagicMock(content='{"name": "Rendered PDF Project"}'))
    ]

    with (
        patch("stricknani.importing.extractors.ai.OPENAI_AVAILABLE", True),
        patch("stricknani.importing.extractors.ai.AsyncOpenAI") as mock_openai_class,
        patch(
            "stricknani.importing.extractors.pdf.PDFExtractor.extract_images_from_pdf",
            new=AsyncMock(return_value=[b"fake-local-image"]),
        ),
        patch(
            "stricknani.importing.extractors.pdf.PDFExtractor.render_pages_as_images",
            new=AsyncMock(return_value=[b"fake-rendered-page"]),
        ),
        patch(
            "stricknani.importing.extractors.pdf.PDFExtractor.extract",
            new=AsyncMock(
                return_value=ExtractedData(
                    description="PDF desc",
                    extras={"full_text": "local text"},
                )
            ),
        ),
    ):
        mock_client = mock_openai_class.return_value
        mock_client.chat.completions.create = AsyncMock(return_value=mock_completion)

        result = await extractor.extract(pdf_content)

    assert result.name == "Rendered PDF Project"
    assert "pdf_rendered_pages" in result.extras
    assert result.extras["pdf_rendered_pages"][0] == b"fake-rendered-page"

    # Verify multimodal chat completion was called with base64 images
    mock_client.chat.completions.create.assert_called_once()
    args, kwargs = mock_client.chat.completions.create.call_args
    messages = kwargs["messages"]

    # System prompt
    assert "Markdown" in messages[0]["content"]

    # User content
    user_content = messages[1]["content"]
    assert isinstance(user_content, list)

    found_text_with_hint = False
    found_image = False
    for item in user_content:
        if item["type"] == "text":
            assert "Analyze this knitting pattern image" in item["text"]
            assert "PDF desc" in item["text"]
            found_text_with_hint = True
        if item["type"] == "image_url":
            assert "data:image/jpeg;base64," in item["image_url"]["url"]
            found_image = True

    assert found_text_with_hint
    assert found_image

    # Verify files.create (PDF upload) was NOT called
    assert mock_client.files.create.called is False


@pytest.mark.asyncio
async def test_ai_extractor_pdf_rendering_fallback_to_text() -> None:
    """Verify that we fallback to text extraction if PDF rendering returns no images."""
    extractor = AIExtractor(api_key="test-key")

    pdf_content = RawContent(
        content=b"%PDF-1.4 fake",
        content_type=ContentType.PDF,
        metadata={"filename": "test.pdf"},
    )

    mock_completion = MagicMock()
    mock_completion.choices = [
        MagicMock(message=MagicMock(content='{"name": "Text Fallback Success"}'))
    ]

    with (
        patch("stricknani.importing.extractors.ai.OPENAI_AVAILABLE", True),
        patch("stricknani.importing.extractors.ai.AsyncOpenAI") as mock_openai_class,
        patch(
            "stricknani.importing.extractors.pdf.PDFExtractor.extract_images_from_pdf",
            new=AsyncMock(return_value=[]),
        ),
        patch(
            "stricknani.importing.extractors.pdf.PDFExtractor.render_pages_as_images",
            new=AsyncMock(return_value=[]),  # Rendering failed
        ),
        patch(
            "stricknani.importing.extractors.pdf.PDFExtractor.can_extract",
            return_value=True,
        ),
        patch(
            "stricknani.importing.extractors.pdf.PDFExtractor.extract",
            new=AsyncMock(
                return_value=ExtractedData(
                    description="Local Text",
                    extras={"full_text": "Local Text"},
                )
            ),
        ),
    ):
        mock_client = mock_openai_class.return_value
        mock_client.chat.completions.create = AsyncMock(return_value=mock_completion)

        result = await extractor.extract(pdf_content)

    assert result.name == "Text Fallback Success"
