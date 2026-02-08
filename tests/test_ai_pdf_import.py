from __future__ import annotations

from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from stricknani.importing.extractors.ai import AIExtractor
from stricknani.importing.models import ContentType, ExtractedData, RawContent


@pytest.mark.asyncio
async def test_ai_extractor_pdf_direct_upload() -> None:
    extractor = AIExtractor(api_key="test-key")

    pdf_content = RawContent(
        content=b"%PDF-1.4 fake",
        content_type=ContentType.PDF,
        metadata={"filename": "test.pdf"},
    )

    # Mock OpenAI client and responses
    mock_file = MagicMock()
    mock_file.id = "file-123"

    mock_completion = MagicMock()
    mock_completion.choices = [
        MagicMock(message=MagicMock(content='{"name": "Direct PDF Project"}'))
    ]

    with (
        patch("stricknani.importing.extractors.ai.OPENAI_AVAILABLE", True),
        patch("stricknani.importing.extractors.ai.AsyncOpenAI") as mock_openai_class,
        patch(
            "stricknani.importing.extractors.pdf.PDFExtractor.extract_images_from_pdf",
            new=AsyncMock(return_value=[b"fake-image-bytes"]),
        ),
    ):
        mock_client = mock_openai_class.return_value
        mock_client.files.create = AsyncMock(return_value=mock_file)
        mock_client.chat.completions.create = AsyncMock(return_value=mock_completion)
        mock_client.files.delete = AsyncMock()

        result = await extractor.extract(pdf_content)

    assert result.name == "Direct PDF Project"
    assert "pdf_images" in result.extras
    assert len(result.extras["pdf_images"]) == 1
    assert result.extras["pdf_images"][0] == b"fake-image-bytes"
    assert result.image_urls == []

    # Verify file was uploaded with purpose="vision"
    mock_client.files.create.assert_called_once()
    args, kwargs = mock_client.files.create.call_args
    assert kwargs["purpose"] == "vision"
    assert kwargs["file"][0] == "test.pdf"
    assert kwargs["file"][2] == "application/pdf"

    # Verify chat completion was called with input_file
    mock_client.chat.completions.create.assert_called_once()
    args, kwargs = mock_client.chat.completions.create.call_args
    messages = kwargs["messages"]
    user_content = messages[1]["content"]
    
    found_input_file = False
    found_image_indices = False
    for item in user_content:
        if item["type"] == "input_file":
            assert item["input_file"]["file_id"] == "file-123"
            found_input_file = True
        if item["type"] == "text" and "Image 1" in item["text"]:
            found_image_indices = True
    assert found_input_file
    assert found_image_indices

    # Verify file was deleted
    mock_client.files.delete.assert_called_once_with("file-123")


@pytest.mark.asyncio
async def test_ai_extractor_pdf_fallback_on_error() -> None:
    from openai import BadRequestError
    extractor = AIExtractor(api_key="test-key")

    pdf_content = RawContent(
        content=b"%PDF-1.4 fake",
        content_type=ContentType.PDF,
        metadata={"filename": "test.pdf"},
    )

    with (
        patch("stricknani.importing.extractors.ai.OPENAI_AVAILABLE", True),
        patch("stricknani.importing.extractors.ai.AsyncOpenAI") as mock_openai_class,
        patch(
            "stricknani.importing.extractors.pdf.PDFExtractor.extract_images_from_pdf",
            new=AsyncMock(return_value=[b"fake-image-bytes"]),
        ),
        patch(
            "stricknani.importing.extractors.pdf.PDFExtractor.can_extract",
            return_value=True,
        ),
        patch(
            "stricknani.importing.extractors.pdf.PDFExtractor.extract",
            new=AsyncMock(
                return_value=ExtractedData(
                    description="Fallback description",
                    extras={"full_text": "extracted text"},
                )
            ),
        ),
        patch.object(
            AIExtractor,
            "_extract_from_text",
            new=AsyncMock(return_value=ExtractedData(name="Fallback Success")),
        ),
    ):
        mock_client = mock_openai_class.return_value

        # Simulate OpenAI error for PDF upload
        mock_response = MagicMock()
        mock_response.status_code = 400
        mock_client.files.create.side_effect = BadRequestError(
            "Invalid file format application/pdf",
            response=mock_response,
            body={"error": {"message": "Invalid file format application/pdf"}}
        )

        result = await extractor.extract(pdf_content)

    assert result.name == "Fallback Success"
    assert "pdf_images" in result.extras
    assert len(result.extras["pdf_images"]) == 1
    assert result.extras["pdf_images"][0] == b"fake-image-bytes"
    assert result.image_urls == []
    mock_client.files.create.assert_called_once()