"""PDF content extractor.

Extracts text and images from PDF files for knitting pattern import.
"""

from __future__ import annotations

import importlib.util
import logging
from io import BytesIO
from typing import TYPE_CHECKING, Any

from stricknani.importing.extractors import ContentExtractor, ExtractorError
from stricknani.importing.models import ContentType, ExtractedData, RawContent

if TYPE_CHECKING:
    pass

logger = logging.getLogger("stricknani.imports")

# Try to import optional PDF libraries
PYMUPDF_AVAILABLE = importlib.util.find_spec("fitz") is not None
PYPDF_AVAILABLE = importlib.util.find_spec("pypdf") is not None


class PDFExtractor(ContentExtractor):
    """Extract pattern data from PDF files.

    Uses PyMuPDF (fitz) or pypdf to extract text from PDFs.
    Can also extract images for AI analysis.
    """

    def __init__(self, extract_images: bool = False) -> None:
        """Initialize the PDF extractor.

        Args:
            extract_images: Whether to extract images from the PDF
        """
        self.should_extract_images = extract_images

    @property
    def name(self) -> str:
        """Return the name of this extractor."""
        return "pdf"

    def can_extract(self, content: RawContent) -> bool:
        """Check if content is a PDF and libraries are available."""
        if content.content_type != ContentType.PDF:
            return False

        return PYMUPDF_AVAILABLE or PYPDF_AVAILABLE

    async def extract(
        self,
        content: RawContent,
        *,
        hints: dict[str, Any] | None = None,
    ) -> ExtractedData:
        """Extract text from PDF.

        Args:
            content: Raw PDF content
            hints: Optional hints

        Returns:
            ExtractedData with text content

        Raises:
            ExtractorError: If extraction fails
        """
        pdf_bytes = (
            content.content
            if isinstance(content.content, bytes)
            else content.content.encode()
        )

        try:
            if PYMUPDF_AVAILABLE:
                text = self._extract_with_pymupdf(pdf_bytes)
            elif PYPDF_AVAILABLE:
                text = self._extract_with_pypdf(pdf_bytes)
            else:
                raise ExtractorError(
                    "No PDF library available. Install PyMuPDF or pypdf.",
                    extractor_name=self.name,
                )

            # Build result
            return ExtractedData(
                name=hints.get("title") if hints else None,
                description=text[:2000]
                if text
                else None,  # First 2000 chars as description
                extras={
                    "full_text": text,
                    "page_count": hints.get("page_count") if hints else None,
                },
            )

        except Exception as exc:
            raise ExtractorError(
                f"PDF extraction failed: {exc}",
                extractor_name=self.name,
            ) from exc

    def _extract_with_pymupdf(self, pdf_bytes: bytes) -> str:
        """Extract text using PyMuPDF (fitz)."""
        import fitz

        text_parts = []

        with fitz.open(stream=pdf_bytes, filetype="pdf") as doc:
            for page_num in range(len(doc)):
                page = doc.load_page(page_num)
                text = page.get_text()
                if text.strip():
                    text_parts.append(f"--- Page {page_num + 1} ---\n{text}")

        return "\n\n".join(text_parts)

    def _extract_with_pypdf(self, pdf_bytes: bytes) -> str:
        """Extract text using pypdf."""
        from pypdf import PdfReader

        reader = PdfReader(BytesIO(pdf_bytes))
        text_parts = []

        for i, page in enumerate(reader.pages):
            text = page.extract_text()
            if text and text.strip():
                text_parts.append(f"--- Page {i + 1} ---\n{text}")

        return "\n\n".join(text_parts)

    async def extract_images_from_pdf(self, content: RawContent) -> list[bytes]:
        """Extract images from PDF for AI analysis.

        Args:
            content: Raw PDF content

        Returns:
            List of image bytes
        """
        if not PYMUPDF_AVAILABLE:
            return []

        pdf_bytes = (
            content.content
            if isinstance(content.content, bytes)
            else content.content.encode()
        )
        images = []

        try:
            import fitz

            with fitz.open(stream=pdf_bytes, filetype="pdf") as doc:
                for page_num in range(len(doc)):
                    page = doc.load_page(page_num)
                    image_list = page.get_images()

                    for _, img in enumerate(image_list, start=1):
                        xref = img[0]
                        base_image = doc.extract_image(xref)
                        image_bytes = base_image["image"]
                        images.append(image_bytes)

        except Exception as exc:
            logger.warning("Failed to extract images from PDF: %s", exc)

        return images


__all__ = ["PDFExtractor"]
