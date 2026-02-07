import base64
import json
from pathlib import Path
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from stricknani.utils.ai_ingest import build_schema_for_target, ingest_with_openai


def build_required_payload(
    schema: dict[str, object], overrides: dict[str, object]
) -> dict[str, object]:
    required = schema.get("required")
    properties = schema.get("properties")
    if not isinstance(required, list) or not isinstance(properties, dict):
        raise AssertionError("Unexpected schema shape")

    payload: dict[str, object] = {str(key): None for key in required}
    payload.update(overrides)
    return payload


def build_required_yarn(overrides: dict[str, object]) -> dict[str, object]:
    schema = build_schema_for_target("yarn")
    required = schema.get("required")
    if not isinstance(required, list):
        raise AssertionError("Unexpected schema shape")
    payload: dict[str, object] = {str(key): None for key in required}
    payload.update(overrides)
    return payload


def test_schema_project_is_strict_and_has_name_required() -> None:
    schema = build_schema_for_target("project")
    assert schema["type"] == "object"
    assert schema["additionalProperties"] is False
    assert "name" in schema["required"]
    assert "steps" in schema["required"]
    assert "steps" in schema["properties"]

    category = schema["properties"]["category"]
    # Category is nullable in the DB model, so we represent it with anyOf.
    assert "anyOf" in category


def test_schema_yarn_has_name_required() -> None:
    schema = build_schema_for_target("yarn")
    assert schema["type"] == "object"
    assert "name" in schema["required"]
    assert "steps" not in schema["properties"]


@pytest.mark.asyncio
async def test_ingest_deduplicates_image_urls(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("OPENAI_API_KEY", "test-key")

    schema = build_schema_for_target("project")
    fake_json: dict[str, object] = build_required_payload(
        schema,
        {
            "name": "Img Dedup",
            "category": None,
            "description": None,
            "steps": [],
            "image_urls": [
                "https://example.com/pic-300x300.jpg",
                "https://example.com/pic-1024x1024.jpg",
                "https://example.com/pic.jpg",
            ],
        },
    )

    with patch("openai.AsyncOpenAI") as MockOpenAI:
        mock_client = MockOpenAI.return_value
        mock_response = MagicMock()
        mock_response.output_text = json.dumps(fake_json)
        mock_client.responses.create = AsyncMock(return_value=mock_response)

        result = await ingest_with_openai(
            target="project",
            schema=schema,
            source_url=None,
            source_text="text",
            file_paths=None,
            instructions="Extract",
            model="gpt-4o-mini",
            temperature=0.1,
            max_output_tokens=500,
        )

    assert result["image_urls"] == ["https://example.com/pic.jpg"]


@pytest.mark.asyncio
async def test_ingest_url_calls_openai_with_json_schema(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setenv("OPENAI_API_KEY", "test-key")

    schema = build_schema_for_target("project")
    fake_json: dict[str, object] = build_required_payload(
        schema,
        {
            "name": "Test Pattern",
            "category": None,
            "image_urls": [],
            "steps": [],
            "description": None,
        },
    )

    with (
        patch("stricknani.utils.ai_ingest.extract_url") as mock_extract,
        patch("openai.AsyncOpenAI") as MockOpenAI,
    ):
        mock_extract.return_value = MagicMock(
            text="Some pattern text",
            image_urls=["https://example.com/a.jpg"],
            yarn_candidates=[],
        )

        mock_client = MockOpenAI.return_value
        mock_response = MagicMock()
        mock_response.output_text = json.dumps(fake_json)
        mock_client.responses.create = AsyncMock(return_value=mock_response)

        result = await ingest_with_openai(
            target="project",
            schema=schema,
            source_url="https://example.com/pattern",
            source_text=None,
            file_paths=None,
            instructions="Extract",
            model="gpt-4o-mini",
            temperature=0.1,
            max_output_tokens=500,
        )

        assert result["name"] == "Test Pattern"

        _args, kwargs = mock_client.responses.create.call_args
        assert kwargs["text"]["format"]["type"] == "json_schema"
        assert kwargs["text"]["format"]["strict"] is True
        assert kwargs["text"]["format"]["schema"]["additionalProperties"] is False


@pytest.mark.asyncio
async def test_ingest_deduplicates_yarns_and_fills_link(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setenv("OPENAI_API_KEY", "test-key")

    schema = build_schema_for_target("project")
    fake_json: dict[str, object] = build_required_payload(
        schema,
        {
            "name": "Yarn Merge",
            "category": None,
            "description": None,
            "steps": [],
            "image_urls": [],
            "yarns": [
                build_required_yarn(
                    {
                        "name": "Air",
                        "brand": "DROPS",
                        "recommended_needles": "5.5mm",
                        "link": None,
                    }
                ),
                build_required_yarn(
                    {
                        "name": "Air",
                        "brand": "DROPS",
                        "recommended_needles": "4.5mm",
                        "link": None,
                    }
                ),
            ],
        },
    )

    with (
        patch("stricknani.utils.ai_ingest.extract_url") as mock_extract,
        patch("openai.AsyncOpenAI") as MockOpenAI,
    ):
        mock_extract.return_value = MagicMock(
            text="Some pattern text",
            image_urls=[],
            yarn_candidates=[
                {
                    "name": "Air",
                    "link": "https://www.garnstudio.com/yarn.php?show=drops-air&cid=9",
                }
            ],
        )

        mock_client = MockOpenAI.return_value
        mock_response = MagicMock()
        mock_response.output_text = json.dumps(fake_json)
        mock_client.responses.create = AsyncMock(return_value=mock_response)

        result = await ingest_with_openai(
            target="project",
            schema=schema,
            source_url="https://example.com/pattern",
            source_text=None,
            file_paths=None,
            instructions="Extract",
            model="gpt-4o-mini",
            temperature=0.1,
            max_output_tokens=500,
        )

    yarns = result.get("yarns")
    assert isinstance(yarns, list)
    assert len(yarns) == 1
    assert (
        yarns[0]["link"] == "https://www.garnstudio.com/yarn.php?show=drops-air&cid=9"
    )
    assert yarns[0]["recommended_needles"] == "5.5mm, 4.5mm"


@pytest.mark.asyncio
async def test_ingest_image_uses_input_image(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    monkeypatch.setenv("OPENAI_API_KEY", "test-key")

    img_path = tmp_path / "pattern.jpg"
    img_path.write_bytes(b"\xff\xd8\xff" + b"fakejpeg")

    schema = build_schema_for_target("project")
    fake_json: dict[str, object] = build_required_payload(
        schema,
        {
            "name": "Img Pattern",
            "category": None,
            "image_urls": [],
            "steps": [],
            "description": None,
        },
    )

    with patch("openai.AsyncOpenAI") as MockOpenAI:
        mock_client = MockOpenAI.return_value
        mock_response = MagicMock()
        mock_response.output_text = json.dumps(fake_json)
        mock_client.responses.create = AsyncMock(return_value=mock_response)

        result = await ingest_with_openai(
            target="project",
            schema=schema,
            source_url=None,
            source_text=None,
            file_paths=[img_path],
            instructions="Extract",
            model="gpt-4o-mini",
            temperature=0.1,
            max_output_tokens=500,
        )

        assert result["name"] == "Img Pattern"

        _args, kwargs = mock_client.responses.create.call_args
        content = kwargs["input"][0]["content"]
        assert any(item["type"] == "input_image" for item in content)
        img_item = next(item for item in content if item["type"] == "input_image")
        assert img_item["image_url"].startswith("data:image/jpeg;base64,")


@pytest.mark.asyncio
async def test_ingest_pdf_uses_input_file(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    monkeypatch.setenv("OPENAI_API_KEY", "test-key")

    pdf_path = tmp_path / "pattern.pdf"
    pdf_bytes = b"%PDF-1.4 fake"
    pdf_path.write_bytes(pdf_bytes)

    schema = build_schema_for_target("project")
    fake_json: dict[str, object] = build_required_payload(
        schema,
        {
            "name": "PDF Pattern",
            "category": None,
            "image_urls": [],
            "steps": [],
            "description": None,
        },
    )

    with patch("openai.AsyncOpenAI") as MockOpenAI:
        mock_client = MockOpenAI.return_value
        mock_response = MagicMock()
        mock_response.output_text = json.dumps(fake_json)
        mock_client.responses.create = AsyncMock(return_value=mock_response)

        result = await ingest_with_openai(
            target="project",
            schema=schema,
            source_url=None,
            source_text=None,
            file_paths=[pdf_path],
            instructions="Extract",
            model="gpt-4o-mini",
            temperature=0.1,
            max_output_tokens=500,
        )

        assert result["name"] == "PDF Pattern"

        _args, kwargs = mock_client.responses.create.call_args
        content = kwargs["input"][0]["content"]
        assert any(item["type"] == "input_file" for item in content)
        file_item = next(item for item in content if item["type"] == "input_file")
        assert file_item["filename"] == "pattern.pdf"
        assert base64.b64decode(file_item["file_data"]) == pdf_bytes


@pytest.mark.asyncio
async def test_ingest_multiple_files_attaches_all(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    monkeypatch.setenv("OPENAI_API_KEY", "test-key")

    img_path = tmp_path / "pattern.jpg"
    img_path.write_bytes(b"\xff\xd8\xff" + b"fakejpeg")

    pdf_path = tmp_path / "pattern.pdf"
    pdf_path.write_bytes(b"%PDF-1.4 fake")

    schema = build_schema_for_target("project")
    fake_json: dict[str, object] = build_required_payload(
        schema,
        {
            "name": "Multi File Pattern",
            "category": None,
            "image_urls": [],
            "steps": [],
            "description": None,
        },
    )

    with patch("openai.AsyncOpenAI") as MockOpenAI:
        mock_client = MockOpenAI.return_value
        mock_response = MagicMock()
        mock_response.output_text = json.dumps(fake_json)
        mock_client.responses.create = AsyncMock(return_value=mock_response)

        result = await ingest_with_openai(
            target="project",
            schema=schema,
            source_url=None,
            source_text=None,
            file_paths=[pdf_path, img_path],
            instructions="Extract",
            model="gpt-4o-mini",
            temperature=0.1,
            max_output_tokens=500,
        )

    assert result["name"] == "Multi File Pattern"
    _args, kwargs = mock_client.responses.create.call_args
    content = kwargs["input"][0]["content"]
    assert sum(1 for item in content if item["type"] == "input_image") == 1
    assert sum(1 for item in content if item["type"] == "input_file") == 1
