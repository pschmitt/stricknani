from typing import Any

import pytest
from PIL import Image

from stricknani.config import config
from stricknani.models import Project, ProjectCategory, User
from stricknani.utils.auth import get_password_hash


@pytest.mark.anyio
async def test_ocr_requires_tesseract(
    test_client: Any, monkeypatch: pytest.MonkeyPatch
) -> None:
    client, _session_factory, _user_id, project_id, _step_id = test_client

    image_path = config.MEDIA_ROOT / "projects" / str(project_id) / "ocr.png"
    image_path.parent.mkdir(parents=True, exist_ok=True)
    Image.new("RGB", (10, 10), color=(255, 255, 255)).save(image_path)

    import shutil

    monkeypatch.setattr(shutil, "which", lambda _cmd: None)
    resp = await client.post(
        "/utils/ocr", json={"src": f"/media/projects/{project_id}/ocr.png"}
    )
    assert resp.status_code == 501
    assert resp.json()["detail"] == "ocr_not_available"


@pytest.mark.anyio
async def test_ocr_happy_path(
    test_client: Any, monkeypatch: pytest.MonkeyPatch
) -> None:
    client, _session_factory, _user_id, project_id, _step_id = test_client

    image_path = config.MEDIA_ROOT / "projects" / str(project_id) / "ocr.png"
    image_path.parent.mkdir(parents=True, exist_ok=True)
    Image.new("RGB", (10, 10), color=(255, 255, 255)).save(image_path)

    import shutil
    import subprocess

    class _Proc:
        def __init__(self) -> None:
            self.returncode = 0
            self.stdout = "hello world\n"

    monkeypatch.setattr(shutil, "which", lambda _cmd: "/usr/bin/tesseract")
    monkeypatch.setattr(subprocess, "run", lambda *a, **k: _Proc())

    resp = await client.post(
        "/utils/ocr", json={"src": f"/media/projects/{project_id}/ocr.png"}
    )
    assert resp.status_code == 200
    assert resp.json() == {"text": "hello world\n"}

    # Force rescan bypasses cache.
    calls = {"count": 0}

    def _run(*_args: Any, **_kwargs: Any) -> _Proc:
        calls["count"] += 1
        return _Proc()

    monkeypatch.setattr(subprocess, "run", _run)
    resp = await client.post(
        "/utils/ocr",
        json={"src": f"/media/projects/{project_id}/ocr.png", "force": True},
    )
    assert resp.status_code == 200
    assert calls["count"] == 1

    # Second request should be served from backend cache.
    monkeypatch.setattr(
        subprocess,
        "run",
        lambda *a, **k: (_ for _ in ()).throw(AssertionError("unexpected OCR run")),
    )
    resp = await client.post(
        "/utils/ocr", json={"src": f"/media/projects/{project_id}/ocr.png"}
    )
    assert resp.status_code == 200
    assert resp.json() == {"text": "hello world\n"}


@pytest.mark.anyio
async def test_ocr_denies_other_users_media(
    test_client: Any, monkeypatch: pytest.MonkeyPatch
) -> None:
    client, session_factory, _user_id, project_id, _step_id = test_client

    async with session_factory() as session:
        other_user = User(
            email="other@example.com",
            hashed_password=get_password_hash("secret"),
        )
        session.add(other_user)
        await session.commit()
        await session.refresh(other_user)

        other_project = Project(
            name="Other Project",
            category=ProjectCategory.SCHAL.value,
            owner_id=other_user.id,
        )
        session.add(other_project)
        await session.commit()
        await session.refresh(other_project)

        other_project_id = other_project.id

    image_path = config.MEDIA_ROOT / "projects" / str(other_project_id) / "ocr.png"
    image_path.parent.mkdir(parents=True, exist_ok=True)
    Image.new("RGB", (10, 10), color=(255, 255, 255)).save(image_path)

    import shutil
    import subprocess

    class _Proc:
        def __init__(self) -> None:
            self.returncode = 0
            self.stdout = "hello\n"

    monkeypatch.setattr(shutil, "which", lambda _cmd: "/usr/bin/tesseract")
    monkeypatch.setattr(subprocess, "run", lambda *a, **k: _Proc())

    resp = await client.post(
        "/utils/ocr", json={"src": f"/media/projects/{other_project_id}/ocr.png"}
    )
    assert resp.status_code == 404
    assert resp.json()["detail"] == "not_found"


@pytest.mark.anyio
async def test_ocr_rejects_invalid_src(test_client: Any) -> None:
    client, _session_factory, _user_id, _project_id, _step_id = test_client

    resp = await client.post("/utils/ocr", json={"src": "https://example.com/x.png"})
    assert resp.status_code == 400
    assert resp.json()["detail"] == "invalid_src"
