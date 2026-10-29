"""Tests for PDF extraction and rendering."""

import pymupdf
import pytest

from stricknani.importing.extractors.pdf import PDFExtractor
from stricknani.importing.models import ContentType, RawContent


def _make_pdf(text: str) -> bytes:
    with pymupdf.open() as document:  # type: ignore[no-untyped-call]
        page = document.new_page()
        page.insert_text((72, 72), text)
        return bytes(document.tobytes())


@pytest.mark.asyncio
async def test_extract_text_and_render_pages_with_pymupdf() -> None:
    """Exercise the PyMuPDF import, text extraction, and page rendering APIs."""
    content = RawContent(
        content=_make_pdf("PyMuPDF extractor smoke test"),
        content_type=ContentType.PDF,
    )
    extractor = PDFExtractor()

    extracted = await extractor.extract(content)
    rendered_pages = await extractor.render_pages_as_images(content)

    assert "PyMuPDF extractor smoke test" in extracted.extras["full_text"]
    assert len(rendered_pages) == 1
    assert rendered_pages[0].startswith(bytes.fromhex("ffd8"))
