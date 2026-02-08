from __future__ import annotations

import io
import json
from typing import Any
from unittest.mock import AsyncMock, patch

import pytest
from PIL import Image
from sqlalchemy import select

from stricknani.config import config
from stricknani.importing.models import ExtractedData
from stricknani.models import Attachment


def _tiny_png_bytes() -> bytes:
    buf = io.BytesIO()
    Image.new("RGB", (1, 1), (255, 0, 0)).save(buf, format="PNG")
    return buf.getvalue()


@pytest.mark.asyncio
async def test_project_file_import_stores_attachment_for_existing_project(
    test_client: Any,
) -> None:
    client, session_factory, user_id, project_id, _step_id = test_client

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
        data = {"type": "file", "use_ai": "true", "project_id": str(project_id)}
        resp = await client.post("/projects/import", data=data, files=files)
        assert resp.status_code == 200

    async with session_factory() as session:
        res = await session.execute(
            select(Attachment).where(Attachment.project_id == project_id)
        )
        att = res.scalars().first()
        assert att is not None

        stored_path = config.MEDIA_ROOT / "projects" / str(project_id) / att.filename
        assert stored_path.exists()


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
        res = await session.execute(
            select(Attachment).where(Attachment.project_id == new_project_id)
        )
        att = res.scalars().first()
        assert att is not None

    assert not pending_meta.exists()
