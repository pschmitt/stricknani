import json
from io import BytesIO
from unittest.mock import AsyncMock, MagicMock, patch

import pytest
from httpx import AsyncClient
from PIL import Image as PILImage
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker

from stricknani.config import config
from stricknani.models import Attachment, Image, ProjectCategory, Step


async def _fetch_steps(
    session_factory: async_sessionmaker[AsyncSession],
    project_id: int,
) -> list[Step]:
    async with session_factory() as session:
        result = await session.execute(
            select(Step).where(Step.project_id == project_id).order_by(Step.step_number)
        )
        return list(result.scalars())


async def _fetch_images(
    session_factory: async_sessionmaker[AsyncSession],
    project_id: int,
) -> list[Image]:
    async with session_factory() as session:
        result = await session.execute(
            select(Image).where(Image.project_id == project_id)
        )
        return list(result.scalars())


async def _fetch_attachments(
    session_factory: async_sessionmaker[AsyncSession],
    project_id: int,
) -> list[Attachment]:
    async with session_factory() as session:
        result = await session.execute(
            select(Attachment).where(Attachment.project_id == project_id)
        )
        return list(result.scalars())


@pytest.mark.asyncio
async def test_update_project_manages_steps(
    test_client: tuple[AsyncClient, async_sessionmaker[AsyncSession], int, int, int],
) -> None:
    client, session_factory, _user_id, project_id, existing_step_id = test_client

    # Add a new step and update the existing one
    payload = json.dumps(
        [
            {
                "id": existing_step_id,
                "title": "Updated Step",
                "description": "Revised instructions",
                "step_number": 1,
            },
            {
                "title": "Blocking",
                "description": "Wet block the project overnight",
                "step_number": 2,
            },
        ]
    )

    response = await client.post(
        f"/projects/{project_id}",
        data={
            "name": "Sample Project",
            "category": ProjectCategory.SCHAL.value,
            "steps_data": payload,
        },
        follow_redirects=False,
    )

    assert response.status_code == 303

    steps = await _fetch_steps(session_factory, project_id)
    assert len(steps) == 2
    assert steps[0].id == existing_step_id
    assert steps[0].title == "Updated Step"
    assert steps[1].title == "Blocking"

    # Remove the original step to verify deletion logic
    prune_payload = json.dumps(
        [
            {
                "title": "Finishing",
                "description": "Sew in the ends",
                "step_number": 1,
            }
        ]
    )

    response = await client.post(
        f"/projects/{project_id}",
        data={
            "name": "Sample Project",
            "category": ProjectCategory.SCHAL.value,
            "steps_data": prune_payload,
        },
        follow_redirects=False,
    )

    assert response.status_code == 303

    steps = await _fetch_steps(session_factory, project_id)
    assert len(steps) == 1
    assert steps[0].title == "Finishing"


def _generate_image_bytes(color: str = "blue") -> BytesIO:
    stream = BytesIO()
    image = PILImage.new("RGB", (128, 128), color=color)
    image.save(stream, format="PNG")
    stream.seek(0)
    return stream


@pytest.mark.asyncio
async def test_upload_title_image_creates_image_record(
    test_client: tuple[AsyncClient, async_sessionmaker[AsyncSession], int, int, int],
) -> None:
    client, session_factory, _user_id, project_id, _step_id = test_client

    image_stream = _generate_image_bytes("green")

    response = await client.post(
        f"/projects/{project_id}/images/title",
        files={"file": ("title.png", image_stream, "image/png")},
        data={"alt_text": "Cover"},
    )

    assert response.status_code == 200
    data = response.json()
    assert data["alt_text"] == "Cover"

    images = await _fetch_images(session_factory, project_id)
    assert len(images) == 1
    assert images[0].is_title_image

    media_path = config.MEDIA_ROOT / "projects" / str(project_id) / images[0].filename
    thumb_path = config.MEDIA_ROOT / "thumbnails" / "projects" / str(project_id)

    assert media_path.exists()
    assert thumb_path.exists()
    assert any(thumb_path.iterdir())


@pytest.mark.asyncio
async def test_create_project_imports_images(
    test_client: tuple[AsyncClient, async_sessionmaker[AsyncSession], int, int, int],
) -> None:
    client, session_factory, _user_id, _project_id, _step_id = test_client

    image_one = _generate_image_bytes("red").getvalue()
    image_two = _generate_image_bytes("blue").getvalue()

    def _mock_response(content: bytes) -> MagicMock:
        response = MagicMock()
        response.status_code = 200
        response.headers = {
            "content-type": "image/png",
            "content-length": str(len(content)),
        }
        response.content = content
        response.raise_for_status = MagicMock()
        return response

    with patch(
        "httpx.AsyncClient.get",
        new=AsyncMock(
            side_effect=[_mock_response(image_one), _mock_response(image_two)]
        ),
    ):
        response = await client.post(
            "/projects/",
            data={
                "name": "Imported Project",
                "category": ProjectCategory.SCHAL.value,
                "import_image_urls": json.dumps(
                    [
                        "https://example.com/one.png",
                        "https://example.com/two.png",
                    ]
                ),
            },
            follow_redirects=False,
        )

    assert response.status_code == 303
    location = response.headers["location"]
    # Handle query parameters (e.g. ?toast=...)
    path = location.split("?")[0]
    project_id = int(path.strip("/").split("/")[1])

    images = await _fetch_images(session_factory, project_id)
    assert len(images) == 2
    assert any(image.is_title_image for image in images)

    media_path = config.MEDIA_ROOT / "projects" / str(project_id) / images[0].filename
    thumb_path = config.MEDIA_ROOT / "thumbnails" / "projects" / str(project_id)

    assert media_path.exists()
    assert thumb_path.exists()
    assert any(thumb_path.iterdir())


@pytest.mark.asyncio
async def test_upload_step_image_creates_image_record(
    test_client: tuple[AsyncClient, async_sessionmaker[AsyncSession], int, int, int],
) -> None:
    client, session_factory, _user_id, project_id, step_id = test_client

    image_stream = _generate_image_bytes("purple")

    response = await client.post(
        f"/projects/{project_id}/steps/{step_id}/images",
        files={"file": ("step.png", image_stream, "image/png")},
        data={"alt_text": "Step detail"},
    )

    assert response.status_code == 200
    data = response.json()
    assert data["alt_text"] == "Step detail"

    images = await _fetch_images(session_factory, project_id)
    assert len(images) == 1
    assert images[0].step_id == step_id

    media_path = config.MEDIA_ROOT / "projects" / str(project_id) / images[0].filename
    thumb_path = config.MEDIA_ROOT / "thumbnails" / "projects" / str(project_id)

    assert media_path.exists()
    assert thumb_path.exists()
    assert any(thumb_path.iterdir())


@pytest.mark.asyncio
async def test_upload_pdf_attachment_renders_preview_button(
    test_client: tuple[AsyncClient, async_sessionmaker[AsyncSession], int, int, int],
) -> None:
    client, session_factory, _user_id, project_id, _step_id = test_client

    pdf_bytes = (
        b"%PDF-1.4\n"
        b"%fake\n"
        b"1 0 obj\n"
        b"<<>>\n"
        b"endobj\n"
        b"trailer\n"
        b"<<>>\n"
        b"%%EOF\n"
    )
    pdf_stream = BytesIO(pdf_bytes)
    response = await client.post(
        f"/projects/{project_id}/attachments",
        files={"file": ("example.pdf", pdf_stream, "application/pdf")},
    )

    assert response.status_code == 200
    data = response.json()
    assert data["content_type"] == "application/pdf"
    assert data["url"].endswith(".pdf")

    attachments = await _fetch_attachments(session_factory, project_id)
    assert len(attachments) == 1
    media_path = (
        config.MEDIA_ROOT / "projects" / str(project_id) / attachments[0].filename
    )
    assert media_path.exists()

    detail = await client.get(f"/projects/{project_id}")
    assert detail.status_code == 200
    assert 'data-action="open-attachment"' in detail.text
    assert data["url"] in detail.text

    edit = await client.get(f"/projects/{project_id}/edit")
    assert edit.status_code == 200
    assert 'data-action="open-attachment"' in edit.text
    assert data["url"] in edit.text


@pytest.mark.asyncio
async def test_upload_image_attachment_renders_preview_button(
    test_client: tuple[AsyncClient, async_sessionmaker[AsyncSession], int, int, int],
) -> None:
    client, session_factory, _user_id, project_id, _step_id = test_client

    image_stream = _generate_image_bytes("black")
    response = await client.post(
        f"/projects/{project_id}/attachments",
        files={"file": ("attached.png", image_stream, "image/png")},
    )

    assert response.status_code == 200
    data = response.json()
    assert data["content_type"] == "image/png"

    attachments = await _fetch_attachments(session_factory, project_id)
    assert len(attachments) == 1

    detail = await client.get(f"/projects/{project_id}")
    assert detail.status_code == 200
    assert 'data-action="open-attachment"' in detail.text
    assert data["url"] in detail.text

    edit = await client.get(f"/projects/{project_id}/edit")
    assert edit.status_code == 200
    assert 'data-action="open-attachment"' in edit.text
    assert data["url"] in edit.text


@pytest.mark.asyncio
async def test_manage_categories_includes_project_categories(
    test_client: tuple[AsyncClient, async_sessionmaker[AsyncSession], int, int, int],
) -> None:
    client, _session_factory, _user_id, _project_id, _step_id = test_client

    response = await client.get("/projects/categories")

    assert response.status_code == 200
    assert 'value="Schal"' in response.text


@pytest.mark.asyncio
async def test_delete_image_removes_file_and_record(
    test_client: tuple[AsyncClient, async_sessionmaker[AsyncSession], int, int, int],
) -> None:
    client, session_factory, _user_id, project_id, _step_id = test_client

    # Upload an image
    image_stream = _generate_image_bytes("yellow")
    response = await client.post(
        f"/projects/{project_id}/images/title",
        files={"file": ("delete_me.png", image_stream, "image/png")},
        data={"alt_text": "To be deleted"},
    )
    assert response.status_code == 200
    image_id = response.json()["id"]

    # Delete the image
    response = await client.delete(f"/projects/{project_id}/images/{image_id}")
    assert response.status_code == 200
    assert response.json()["message"] == "Image deleted"

    # Verify deletion from DB
    images = await _fetch_images(session_factory, project_id)
    assert len(images) == 0
