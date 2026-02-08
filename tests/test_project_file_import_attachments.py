from __future__ import annotations

import json
from typing import Any
from unittest.mock import AsyncMock, patch

import pytest
from sqlalchemy import select

from stricknani.config import config
from stricknani.importing.models import ExtractedData
from stricknani.models import Image


def _tiny_png_bytes() -> bytes:
    # A 1x1 transparent PNG
    return (
        b"\x89PNG\r\n\x1a\n\x00\x00\x00\rIHDR\x00\x00\x00\x01\x00\x00\x00\x01\x08\x06"
        b"\x00\x00\x00\x1f\x15\xc4\x89\x00\x00\x00\nIDATx\x9cc\x00\x01\x00\x00\x05"
        b"\x00\x01\r\n-\xb4\x00\x00\x00\x00IEND\xaeB`\x82"
    )


@pytest.mark.asyncio
async def test_project_file_import_stores_attachment_for_existing_project(
    test_client: Any,
) -> None:
    client, _session_factory, _user_id, project_id, _step_id = test_client

    mock_extracted = ExtractedData(
        name="Existing Attachment", description="desc", extras={}
    )

    # Mock AI extractor to avoid real network calls and errors
    with (
        patch("stricknani.importing.extractors.ai.OPENAI_AVAILABLE", True),
        patch("os.getenv", return_value="sk-test"),
        patch("stricknani.routes.projects.config.FEATURE_AI_IMPORT_ENABLED", True),
        patch("stricknani.importing.extractors.ai.AIExtractor") as MockAIExtractor,
    ):
        mock_instance = MockAIExtractor.return_value
        mock_instance.extract = AsyncMock(return_value=mock_extracted)

        # Use a more realistic PDF header to pass validation
        pdf_content = (
            b"%PDF-1.4\n1 0 obj\n<<\n/Type /Catalog\n/Pages 2 0 R\n>>\nendobj\n"
            b"2 0 obj\n<<\n/Type /Pages\n/Count 1\n/Kids [3 0 R]\n>>\nendobj\n"
            b"3 0 obj\n<<\n/Type /Page\n/Parent 2 0 R\n/Resources << >>\n"
            b"/MediaBox [0 0 612 792]\n/Contents 4 0 R\n>>\nendobj\n"
            b"4 0 obj\n<< /Length 20 >>\nstream\nBT /F1 12 Tf ET\nendstream\n"
            b"endobj\nxref\n0 5\n0000000000 65535 f \n0000000009 00000 n \n"
            b"0000000056 00000 n \n0000000111 00000 n \n0000000212 00000 n \n"
            b"trailer\n<<\n/Size 5\n/Root 1 0 R\n>>\nstartxref\n281\n%%EOF"
        )
        files = {"file": ("attachment.pdf", pdf_content, "application/pdf")}
        data = {"type": "file", "project_id": project_id}

        resp = await client.post("/projects/import", data=data, files=files)
        assert resp.status_code == 200
        payload = resp.json()

    assert "source_attachments" in payload
    assert len(payload["source_attachments"]) == 1
    assert payload["source_attachments"][0]["original_filename"] == "attachment.pdf"


@pytest.mark.asyncio
async def test_project_file_import_stores_pending_token_and_attaches_on_create(
    test_client: Any,
) -> None:
    client, session_factory, user_id, _project_id, _step_id = test_client

    mock_extracted = ExtractedData(name="Imported", description="desc", extras={})

    with (
        patch("stricknani.importing.extractors.ai.OPENAI_AVAILABLE", True),
        patch("os.getenv", return_value="sk-test"),
        patch("stricknani.routes.projects.config.FEATURE_AI_IMPORT_ENABLED", True),
        patch("stricknani.importing.extractors.ai.AIExtractor") as MockAIExtractor,
    ):
        mock_instance = MockAIExtractor.return_value
        mock_instance.extract = AsyncMock(return_value=mock_extracted)

        files = {"file": ("pattern.png", _tiny_png_bytes(), "image/png")}
        data = {"type": "file", "use_ai": "true"}
        resp = await client.post("/projects/import", data=data, files=files)
        assert resp.status_code == 200
        payload = resp.json()

    tokens = payload.get("import_attachment_tokens")
    assert isinstance(tokens, list)
    assert len(tokens) == 1
    token = tokens[0]

    pending_meta = (
        config.MEDIA_ROOT / "imports" / "projects" / str(user_id) / f"{token}.json"
    )
    assert pending_meta.exists()

    create_resp = await client.post(
        "/projects/",
        data={
            "name": "New Project",
            "import_attachment_tokens": json.dumps(tokens),
            "csrf_token": "test",
        },
        headers={"accept": "application/json"},
    )
    assert create_resp.status_code == 201
    new_project_id = create_resp.json()["id"]

    async with session_factory() as session:
        # Since it was a .png file, it should now be an Image record
        res = await session.execute(
            select(Image).where(Image.project_id == new_project_id)
        )
        img = res.scalars().first()
        assert img is not None
        assert img.original_filename == "pattern.png"

    assert not pending_meta.exists()
