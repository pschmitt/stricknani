from io import BytesIO
from typing import Any

import pytest
from PIL import Image as PILImage

from stricknani.config import config
from stricknani.models import User


def _generate_avatar_bytes(color: str = "orange") -> BytesIO:
    stream = BytesIO()
    image = PILImage.new("RGB", (48, 48), color=color)
    image.save(stream, format="PNG")
    stream.seek(0)
    return stream


@pytest.mark.asyncio
async def test_upload_profile_image_updates_avatar(test_client: Any) -> None:
    client, session_factory, user_id, _project_id, _step_id = test_client

    response = await client.post(
        "/user/profile-image",
        files={"file": ("avatar.png", _generate_avatar_bytes(), "image/png")},
        headers={"HX-Request": "true"},
    )

    assert response.status_code == 204

    async with session_factory() as session:
        user = await session.get(User, user_id)
        assert user is not None
        assert user.profile_image
        profile_filename = user.profile_image

    media_path = config.MEDIA_ROOT / "users" / str(user_id) / profile_filename
    thumb_dir = config.MEDIA_ROOT / "thumbnails" / "users" / str(user_id)

    assert media_path.exists()
    assert thumb_dir.exists()
    assert any(thumb_dir.iterdir())
