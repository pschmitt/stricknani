"""Tests for pattern import functionality."""

import io
import json
from io import BytesIO
from typing import TYPE_CHECKING, Any
from unittest.mock import AsyncMock, MagicMock, patch

import pytest
from PIL import Image

from stricknani.config import config

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

try:
    import openai  # noqa: F401  # type: ignore

    OPENAI_AVAILABLE = True
except ImportError:
    OPENAI_AVAILABLE = False


@pytest.mark.asyncio
async def test_import_url_basic(test_client: "TestClientFixture") -> None:
    """Test basic URL import without AI."""
    client, _, _, _, _ = test_client

    mock_html = """
    <html>
        <head><title>Test Pattern</title></head>
        <body>
            <h1>Simple Scarf Pattern</h1>
            <p>Needles: 5mm</p>
            <p>Yarn: Merino wool</p>
            <p>Gauge: 20 stitches and 28 rows per 10cm</p>
            <div class="instructions">
                <p>Cast on 40 stitches.</p>
                <p>Knit in garter stitch until desired length.</p>
            </div>
        </body>
    </html>
    """

    with patch("httpx.AsyncClient.get") as mock_get:
        mock_response = MagicMock()
        mock_response.text = mock_html
        mock_response.status_code = 200
        mock_get.return_value = mock_response

        response = await client.post(
            "/projects/import",
            data={
                "type": "url",
                "url": "https://example.com/pattern",
                "use_ai": False,
            },
        )

    assert response.status_code == 200
    data = response.json()

    # Check that basic fields were extracted
    assert data.get("title") is not None or data.get("name") is not None
    # Basic parser should extract some content
    assert "notes" in data or "instructions" in data


@pytest.mark.asyncio
async def test_import_url_extracts_steps_and_images(
    test_client: "TestClientFixture",
) -> None:
    """Test URL import extracts steps from pattern text and picture sources."""
    client, _, _, _, _ = test_client

    mock_html = """
    <html>
        <head>
            <title>Example Pattern</title>
            <meta property="og:image" content="https://example.com/og-image.jpg">
        </head>
        <body>
            <div id="pattern_text">
                Step One:<br>
                Cast on 20 stitches.<br><br>
                Step Two:<br>
                Knit for 10 rows.<br>
            </div>
            <picture>
                <source srcset="https://example.com/pattern-800.jpg 800w">
                <img src="https://example.com/fallback.jpg">
            </picture>
        </body>
    </html>
    """

    async def _mock_get(url: str, **kwargs: Any) -> MagicMock:
        # Create a unique valid JPEG for each URL based on its hash
        import hashlib

        h = int(hashlib.md5(str(url).encode()).hexdigest(), 16) % 255
        img = Image.new("RGB", (100, 100), color=(h, h, h))
        img_bytes = io.BytesIO()
        img.save(img_bytes, format="JPEG")
        img_content = img_bytes.getvalue()

        mock_resp = MagicMock()
        mock_resp.text = mock_html
        mock_resp.content = img_content
        mock_resp.status_code = 200
        mock_resp.headers = {
            "content-type": "image/jpeg",
            "content-length": str(len(img_content)),
        }
        return mock_resp

    with patch("httpx.AsyncClient.get", side_effect=_mock_get):
        response = await client.post(
            "/projects/import",
            data={
                "type": "url",
                "url": "https://example.com/pattern",
                "use_ai": False,
            },
        )

    assert response.status_code == 200
    data = response.json()
    assert data.get("link") == "https://example.com/pattern"
    assert len(data.get("steps") or []) >= 2
    assert "https://example.com/pattern-800.jpg" in data.get("image_urls", [])


@pytest.mark.skipif(not OPENAI_AVAILABLE, reason="OpenAI not installed")
@pytest.mark.asyncio
async def test_import_url_with_ai(test_client: "TestClientFixture") -> None:
    """Test URL import with AI extraction."""
    client, _, _, _, _ = test_client

    mock_html = """
    <html>
        <head><title>Cozy Sweater Pattern</title></head>
        <body>
            <h1>Beginner's Pullover</h1>
            <p>Materials: 200g worsted weight yarn, 4mm needles</p>
            <p>Gauge: 22 sts x 30 rows = 10cm in stockinette</p>
            <div class="pattern">
                <h2>Instructions</h2>
                <p>Step 1: Cast on 100 stitches</p>
                <p>Step 2: Work in ribbing for 5cm</p>
            </div>
        </body>
    </html>
    """

    mock_ai_response = {
        "title": "Beginner's Pullover",
        "needles": "4mm",
        "yarn": "200g worsted weight yarn",
        "gauge_stitches": 22,
        "gauge_rows": 30,
        "category": "Pullover",
        "notes": "A cozy beginner-friendly sweater pattern",
        "steps": [
            {
                "step_number": 1,
                "title": "Cast On",
                "description": "Cast on 100 stitches",
            },
            {
                "step_number": 2,
                "title": "Ribbing",
                "description": "Work in ribbing for 5cm",
            },
        ],
        "image_urls": [],
    }

    with (
        patch("httpx.AsyncClient.get") as mock_get,
        patch(
            "stricknani.utils.ai_importer.AIPatternImporter.fetch_and_parse"
        ) as mock_ai,
        patch("os.getenv") as mock_env,
    ):
        # Mock HTML fetch
        mock_response = MagicMock()
        mock_response.text = mock_html
        mock_response.status_code = 200
        mock_get.return_value = mock_response

        # Mock AI extraction
        mock_ai.return_value = mock_ai_response

        # Mock API key presence
        mock_env.return_value = "fake-api-key"

        response = await client.post(
            "/projects/import",
            data={
                "type": "url",
                "url": "https://example.com/sweater",
                "use_ai": True,
            },
        )

    assert response.status_code == 200
    data = response.json()

    # Check AI-extracted fields
    assert data["title"] == "Beginner's Pullover"
    assert data["needles"] == "4mm"
    assert data["yarn"] == "200g worsted weight yarn"
    assert data["gauge_stitches"] == 22
    assert data["gauge_rows"] == 30
    assert data["category"] == "Pullover"
    assert len(data["steps"]) == 2
    assert data["steps"][0]["title"] == "Cast On"


@pytest.mark.asyncio
async def test_import_ai_fallback(test_client: "TestClientFixture") -> None:
    """Test that AI failure falls back to basic parser."""
    client, _, _, _, _ = test_client

    mock_html = """
    <html>
        <head><title>Hat Pattern</title></head>
        <body>
            <h1>Simple Beanie</h1>
            <p>Use 5mm needles and chunky yarn</p>
        </body>
    </html>
    """

    with (
        patch("httpx.AsyncClient.get") as mock_get,
        patch(
            "stricknani.utils.ai_importer.AIPatternImporter.fetch_and_parse"
        ) as mock_ai,
        patch(
            "stricknani.utils.importer.PatternImporter.fetch_and_parse"
        ) as mock_basic,
        patch("os.getenv") as mock_env,
    ):
        # Mock HTML fetch
        mock_response = MagicMock()
        mock_response.text = mock_html
        mock_response.status_code = 200
        mock_get.return_value = mock_response

        # Mock AI failure
        mock_ai.side_effect = Exception("AI service unavailable")

        # Mock basic parser success
        mock_basic.return_value = {
            "title": "Simple Beanie",
            "needles": "5mm",
            "yarn": "chunky yarn",
            "notes": "Pattern instructions",
            "image_urls": [],
        }

        # Mock API key presence
        mock_env.return_value = "fake-api-key"

        response = await client.post(
            "/projects/import",
            data={
                "type": "url",
                "url": "https://example.com/hat",
                "use_ai": True,
            },
        )

    assert response.status_code == 200
    data = response.json()

    # Check that basic parser was used
    assert data["title"] == "Simple Beanie"
    assert data["ai_fallback"] is True
    assert "AI extraction failed" in data["description"]


@pytest.mark.asyncio
async def test_import_text(test_client: "TestClientFixture") -> None:
    """Test text import without AI."""
    client, _, _, _, _ = test_client

    pattern_text = """
    Baby Blanket Pattern

    Materials:
    - Soft baby yarn (200g)
    - 4mm needles

    Gauge: 20 sts x 26 rows = 10cm

    Instructions:
    Cast on 120 stitches.
    Work in seed stitch for 60cm.
    Bind off.
    """

    response = await client.post(
        "/projects/import",
        data={
            "type": "text",
            "text": pattern_text,
            "use_ai": False,
        },
    )

    assert response.status_code == 200
    data = response.json()

    # Without AI, text should just be in description
    assert data["description"] is not None
    # The basic parser may strip the title line, so check for content that remains
    assert "Cast on 120 stitches" in data["description"]


@pytest.mark.skipif(not OPENAI_AVAILABLE, reason="OpenAI not installed")
@pytest.mark.asyncio
async def test_import_text_with_ai(test_client: "TestClientFixture") -> None:
    """Test text import with AI extraction."""
    client, _, _, _, _ = test_client

    pattern_text = """
    Striped Scarf

    You'll need 100g of blue yarn and 100g of white yarn.
    Use 5mm needles.
    Gauge: 18 stitches per 10cm.

    Step 1: Cast on 30 stitches with blue yarn.
    Step 2: Knit 20 rows in garter stitch.
    Step 3: Switch to white yarn and knit 20 rows.
    Step 4: Repeat stripes until desired length.
    """

    mock_ai_response_json = {
        "name": "Striped Scarf",
        "needles": "5mm",
        "yarn": "100g blue yarn, 100g white yarn",
        "gauge_stitches": 18,
        "category": "Schal",
        "steps": [
            {
                "step_number": 1,
                "title": "Cast On",
                "description": "Cast on 30 stitches with blue yarn",
            },
            {
                "step_number": 2,
                "title": "Blue Section",
                "description": "Knit 20 rows in garter stitch",
            },
        ],
    }

    mock_completion = MagicMock()
    mock_completion.choices = [
        MagicMock(message=MagicMock(content=json.dumps(mock_ai_response_json)))
    ]

    with (
        patch("stricknani.importing.extractors.ai.AsyncOpenAI") as mock_openai_class,
        patch("os.getenv") as mock_env,
    ):
        # Mock OpenAI response
        mock_client = mock_openai_class.return_value
        mock_client.chat.completions.create = AsyncMock(return_value=mock_completion)

        # Mock API key presence
        mock_env.return_value = "fake-api-key"

        response = await client.post(
            "/projects/import",
            data={
                "type": "text",
                "text": pattern_text,
                "use_ai": True,
            },
        )

    assert response.status_code == 200
    data = response.json()

    # Check AI extraction worked
    assert data["name"] == "Striped Scarf"
    assert data["needles"] == "5mm"
    assert len(data["steps"]) == 2


@pytest.mark.asyncio
async def test_import_text_file(test_client: "TestClientFixture") -> None:
    """Test text file upload."""
    client, _, _, _, _ = test_client

    file_content = (
        b"Simple Pattern\n\nNeedles: 4mm\nYarn: Cotton\n\nCast on 50 stitches."
    )

    # Ensure AI is disabled for this test
    original_ai_setting = config.FEATURE_AI_IMPORT_ENABLED
    config.FEATURE_AI_IMPORT_ENABLED = False

    try:
        response = await client.post(
            "/projects/import",
            data={"type": "file", "use_ai": False},
            files={"files": ("pattern.txt", BytesIO(file_content), "text/plain")},
        )

        assert response.status_code == 200
        data = response.json()

        # Without AI, content goes to description
        assert data["description"] is not None
        # Check that the content was preserved
        assert "Simple Pattern" in data["description"]
        assert data["is_ai_enhanced"] is False
    finally:
        config.FEATURE_AI_IMPORT_ENABLED = original_ai_setting


@pytest.mark.asyncio
async def test_import_pdf_requires_ai(test_client: "TestClientFixture") -> None:
    """Test that PDF import requires AI when AI is not enabled."""
    client, _, _, _, _ = test_client

    # Temporarily disable AI import feature
    original_ai_setting = config.FEATURE_AI_IMPORT_ENABLED
    config.FEATURE_AI_IMPORT_ENABLED = False

    try:
        response = await client.post(
            "/projects/import",
            data={"type": "file", "use_ai": False},
            files={"files": ("pattern.pdf", BytesIO(b"fake pdf"), "application/pdf")},
        )

        # Without AI enabled, should return 422 (unprocessable)
        assert response.status_code == 422
        assert "ai" in response.json()["detail"].lower()
    finally:
        config.FEATURE_AI_IMPORT_ENABLED = original_ai_setting


@pytest.mark.asyncio
async def test_import_image_requires_ai(test_client: "TestClientFixture") -> None:
    """Test that image import requires AI when AI is not enabled."""
    client, _, _, _, _ = test_client

    # Temporarily disable AI import feature
    original_ai_setting = config.FEATURE_AI_IMPORT_ENABLED
    config.FEATURE_AI_IMPORT_ENABLED = False

    try:
        response = await client.post(
            "/projects/import",
            data={"type": "file", "use_ai": False},
            files={"files": ("pattern.jpg", BytesIO(b"fake image"), "image/jpeg")},
        )

        # Without AI enabled, should return 422 (unprocessable)
        assert response.status_code == 422
        assert "ai" in response.json()["detail"].lower()
    finally:
        config.FEATURE_AI_IMPORT_ENABLED = original_ai_setting


@pytest.mark.asyncio
async def test_import_requires_auth(test_client: "TestClientFixture") -> None:
    """Test that import endpoint requires authentication."""
    client, _, _, _, _ = test_client

    # Temporarily clear auth override to test auth requirement
    from stricknani.main import app
    from stricknani.routes.auth import get_current_user, require_auth

    original_overrides = app.dependency_overrides.copy()
    app.dependency_overrides.pop(require_auth, None)
    app.dependency_overrides.pop(get_current_user, None)

    response = await client.post(
        "/projects/import",
        data={
            "type": "url",
            "url": "https://example.com/pattern",
            "use_ai": False,
        },
    )

    # Restore overrides
    app.dependency_overrides = original_overrides

    assert response.status_code == 401


@pytest.mark.asyncio
async def test_import_invalid_url(test_client: "TestClientFixture") -> None:
    """Test that invalid URLs are rejected."""
    client, _, _, _, _ = test_client

    response = await client.post(
        "/projects/import",
        data={
            "type": "url",
            "url": "not-a-valid-url",
            "use_ai": False,
        },
    )

    assert response.status_code == 400
    assert "invalid" in response.json()["detail"].lower()


@pytest.mark.asyncio
async def test_import_missing_url(test_client: "TestClientFixture") -> None:
    """Test that missing URL is rejected."""
    client, _, _, _, _ = test_client

    response = await client.post(
        "/projects/import",
        data={
            "type": "url",
            "use_ai": False,
        },
    )

    assert response.status_code == 400
    assert "required" in response.json()["detail"].lower()


@pytest.mark.asyncio
async def test_import_missing_text(test_client: "TestClientFixture") -> None:
    """Test that missing text is rejected."""
    client, _, _, _, _ = test_client

    response = await client.post(
        "/projects/import",
        data={
            "type": "text",
            "use_ai": False,
        },
    )

    assert response.status_code == 400
    assert "required" in response.json()["detail"].lower()


@pytest.mark.asyncio
async def test_import_missing_file(test_client: "TestClientFixture") -> None:
    """Test that missing file is rejected."""
    client, _, _, _, _ = test_client

    response = await client.post(
        "/projects/import",
        data={
            "type": "file",
            "use_ai": False,
        },
    )

    assert response.status_code == 400
    assert "file is required" in response.json()["detail"].lower()


@pytest.mark.asyncio
async def test_import_trace_written(
    test_client: "TestClientFixture", tmp_path: Any
) -> None:
    """Ensure import traces are persisted when enabled."""
    client, _, _, _, _ = test_client

    from stricknani.config import config

    original_enabled = config.IMPORT_TRACE_ENABLED
    original_dir = config.IMPORT_TRACE_DIR
    original_max = config.IMPORT_TRACE_MAX_CHARS

    config.IMPORT_TRACE_ENABLED = True
    config.IMPORT_TRACE_DIR = tmp_path / "import-traces"
    config.IMPORT_TRACE_MAX_CHARS = 2000

    mock_html = """
    <html>
        <head><title>Trace Pattern</title></head>
        <body>
            <h1>Trace Scarf</h1>
            <p>Needles: 4mm</p>
        </body>
    </html>
    """

    try:
        with patch("httpx.AsyncClient.get") as mock_get:
            mock_response = MagicMock()
            mock_response.text = mock_html
            mock_response.status_code = 200
            mock_get.return_value = mock_response

            response = await client.post(
                "/projects/import",
                data={
                    "type": "url",
                    "url": "https://example.com/trace",
                    "use_ai": False,
                },
            )

        assert response.status_code == 200
        data = response.json()
        trace_id = data.get("import_trace_id")
        assert trace_id
        trace_file = config.IMPORT_TRACE_DIR / f"{trace_id}.json"
        assert trace_file.exists()

        payload = json.loads(trace_file.read_text(encoding="utf-8"))
        assert payload["trace_id"] == trace_id
        assert any(event["name"] == "basic_import" for event in payload["events"])
    finally:
        config.IMPORT_TRACE_ENABLED = original_enabled
        config.IMPORT_TRACE_DIR = original_dir
        config.IMPORT_TRACE_MAX_CHARS = original_max
