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


@pytest.mark.asyncio
async def test_upload_profile_image_rejects_non_image(test_client: Any) -> None:
    """Profile uploads must not retain arbitrary user-controlled extensions."""
    client, session_factory, user_id, _project_id, _step_id = test_client

    response = await client.post(
        "/user/profile-image",
        files={"file": ("avatar.html", b"<html>not an image</html>", "text/html")},
        headers={"HX-Request": "true"},
    )

    assert response.status_code == 400
    async with session_factory() as session:
        user = await session.get(User, user_id)
        assert user is not None
        assert user.profile_image is None


@pytest.mark.asyncio
async def test_upload_profile_image_enforces_size_cap(
    test_client: Any, monkeypatch: pytest.MonkeyPatch
) -> None:
    """The shared upload reader rejects oversized bodies before writing them."""
    client, session_factory, user_id, _project_id, _step_id = test_client
    monkeypatch.setattr(config, "MAX_UPLOAD_BYTES", 16)

    response = await client.post(
        "/user/profile-image",
        files={"file": ("avatar.png", _generate_avatar_bytes(), "image/png")},
        headers={"HX-Request": "true"},
    )

    assert response.status_code == 413
    async with session_factory() as session:
        user = await session.get(User, user_id)
        assert user is not None
        assert user.profile_image is None


@pytest.mark.asyncio
async def test_avatar_thumbnail_falls_back_when_thumbnail_missing(
    test_client: Any,
) -> None:
    _client, session_factory, user_id, _project_id, _step_id = test_client

    filename = "avatar.png"
    media_dir = config.MEDIA_ROOT / "users" / str(user_id)
    media_dir.mkdir(parents=True, exist_ok=True)
    (media_dir / filename).write_bytes(b"not-a-real-png")

    # Ensure thumbnail is missing.
    thumb_dir = config.MEDIA_ROOT / "thumbnails" / "users" / str(user_id)
    if thumb_dir.exists():
        for path in thumb_dir.iterdir():
            path.unlink()

    async with session_factory() as session:
        user = await session.get(User, user_id)
        assert user is not None
        user.profile_image = filename
        await session.commit()

    async with session_factory() as session:
        user = await session.get(User, user_id)
        assert user is not None
        assert user.avatar_thumbnail_url == f"/media/users/{user_id}/{filename}"


@pytest.mark.asyncio
async def test_avatar_falls_back_to_gravatar_when_profile_image_missing(
    test_client: Any,
) -> None:
    _client, session_factory, user_id, _project_id, _step_id = test_client

    async with session_factory() as session:
        user = await session.get(User, user_id)
        assert user is not None
        user.profile_image = "missing-avatar.png"
        await session.commit()

    async with session_factory() as session:
        user = await session.get(User, user_id)
        assert user is not None
        assert user.avatar_url.startswith("https://www.gravatar.com/avatar/")
