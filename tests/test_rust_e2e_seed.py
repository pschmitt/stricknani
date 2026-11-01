"""Tests for the loopback-only Rust Android E2E seeder."""

from __future__ import annotations

import io
import json
import stat
import urllib.error
import urllib.request
from email.message import Message
from pathlib import Path

import pytest

from ci.stricknani import seed_rust
from ci.stricknani.rust_fixture import write_private_json

TEST_PAT = f"sna_{'a' * 64}"


def request_data(request: urllib.request.Request) -> bytes:
    data = request.data
    assert isinstance(data, bytes)
    return data


class FakeResponse:
    def __init__(self, status: int, body: bytes) -> None:
        self.status = status
        self.body = io.BytesIO(body)

    def __enter__(self) -> FakeResponse:
        return self

    def __exit__(self, *_args: object) -> None:
        return None

    def read(self, limit: int = -1) -> bytes:
        return self.body.read(limit)


class FakeOpener:
    def __init__(
        self,
        *,
        failure: Exception | None = None,
        failure_at: int = 1,
        response: FakeResponse | None = None,
    ) -> None:
        self.failure = failure
        self.failure_at = failure_at
        self.response = response
        self.calls: list[tuple[urllib.request.Request, int]] = []

    def open(self, request: urllib.request.Request, *, timeout: int) -> FakeResponse:
        self.calls.append((request, timeout))
        if self.failure is not None and len(self.calls) == self.failure_at:
            raise self.failure
        if self.response is not None:
            return self.response
        path = urllib.parse.urlsplit(request.full_url).path
        if path.endswith("/auth/token"):
            value: object = {"token": TEST_PAT}
        elif path == "/api/v1/categories":
            value = {"id": 3, "name": "Schal"}
        elif path == "/api/v1/yarns" and request.method == "POST":
            value = {
                "id": 8,
                "name": "Riverbend Merino DK",
                "photos": [],
            }
        elif path == "/api/v1/projects" and request.method == "POST":
            payload = json.loads(request_data(request))
            value = {
                "id": 11,
                "name": "Heirloom Baby Blanket",
                "category": "Schal",
                "description": payload.get("description"),
                "yarn_ids": [8],
            }
        elif path == "/api/v1/yarns/8/photos":
            value = {
                "id": 20,
                "url": "/media/yarns/8/yarn.jpg",
                "thumbnail_url": "/media/thumbnails/yarns/8/yarn.jpg",
                "alt_text": "Riverbend Merino DK yarn",
                "is_primary": True,
            }
        elif path == "/api/v1/projects/11/images/title":
            value = {
                "id": 21,
                "url": "/media/projects/11/project.jpg",
                "thumbnail_url": "/media/thumbnails/projects/11/project.jpg",
                "alt_text": "Heirloom baby blanket",
            }
        elif path == "/api/v1/yarns/8":
            value = {
                "id": 8,
                "name": "Riverbend Merino DK",
                "photos": [{"url": "/media/yarns/8/yarn.jpg"}],
            }
        elif path == "/api/v1/projects/11":
            value = {
                "id": 11,
                "name": "Heirloom Baby Blanket",
                "yarn_ids": [8],
                "images": [{"url": "/media/projects/11/project.jpg"}],
            }
        elif path == "/api/v1/projects" and request.method == "GET":
            value = {
                "items": [
                    {
                        "id": 11,
                        "name": "Heirloom Baby Blanket",
                        "preview_url": "/media/thumbnails/projects/11/project.jpg",
                    }
                ]
            }
        elif path == "/api/v1/yarns" and request.method == "GET":
            value = {
                "items": [
                    {
                        "id": 8,
                        "name": "Riverbend Merino DK",
                        "preview_url": "/media/thumbnails/yarns/8/yarn.jpg",
                    }
                ]
            }
        elif path.startswith("/media/"):
            return FakeResponse(200, b"image bytes")
        else:
            pytest.fail(f"unexpected request path: {path}")

        if path.startswith("/api/v1/") and path != "/api/v1/auth/token":
            assert request.get_header("Authorization") == f"Bearer {TEST_PAT}"
        body = json.dumps(value).encode()
        return FakeResponse(201 if request.method == "POST" else 200, body)


def fixture_file(tmp_path: Path, *, url: str = "http://127.0.0.1:7674") -> Path:
    fixture = tmp_path / "fixture.json"
    write_private_json(
        fixture,
        {
            "url": url,
            "email": "seed@example.invalid",
            "password": "disposable-fixture-password",
        },
    )
    return fixture


@pytest.mark.parametrize(
    "url",
    [
        "https://127.0.0.1:7674",
        "http://example.invalid:7674",
        "http://10.0.0.2:7674",
        "http://127.0.0.1:7674/path",
        "http://127.0.0.1:7674/?token=secret",
        "http://user:pass@127.0.0.1:7674",
    ],
)
def test_rejects_non_loopback_or_non_origin_urls(url: str) -> None:
    with pytest.raises(seed_rust.SeedError):
        seed_rust.validate_base_url(url)


def test_uses_a_proxy_free_opener_and_disables_redirects() -> None:
    opener = seed_rust._opener()
    handlers = vars(opener)["handlers"]
    assert not any(hasattr(handler, "proxy_open") for handler in handlers)
    assert any(isinstance(handler, seed_rust.NoRedirect) for handler in handlers)


def test_redirect_is_not_followed_and_error_body_is_not_reported(
    tmp_path: Path,
) -> None:
    secret = "private-fixture-secret"
    error = urllib.error.HTTPError(
        "http://127.0.0.1:7674/api/v1/auth/token",
        302,
        secret,
        Message(),
        io.BytesIO(secret.encode()),
    )
    opener = FakeOpener(failure=error, failure_at=1)
    with pytest.raises(seed_rust.SeedError) as result:
        seed_rust.seed_fixture(
            fixture_file(tmp_path), tmp_path / "token.txt", opener=opener
        )
    assert len(opener.calls) == 1
    assert "302" in str(result.value)
    assert secret not in str(result.value)
    assert "seed@example.invalid" not in str(result.value)
    assert "disposable-fixture-password" not in str(result.value)


def test_response_body_and_request_timeout_are_bounded() -> None:
    opener = FakeOpener(
        response=FakeResponse(200, b"{" + b" " * seed_rust.MAX_JSON_BYTES)
    )
    client = seed_rust.ApiClient("http://127.0.0.1:7674", opener)
    with pytest.raises(seed_rust.SeedError, match="size limit"):
        client.request("GET", "/api/v1/example")
    assert opener.calls[0][1] == seed_rust.REQUEST_TIMEOUT


def test_post_authentication_errors_do_not_expose_pat_or_response_body(
    tmp_path: Path,
) -> None:
    secret_body = "server echoed private data"
    error = urllib.error.HTTPError(
        "http://127.0.0.1:7674/api/v1/categories",
        500,
        secret_body,
        Message(),
        io.BytesIO(secret_body.encode()),
    )
    opener = FakeOpener(failure=error, failure_at=2)
    with pytest.raises(seed_rust.SeedError) as result:
        seed_rust.seed_fixture(
            fixture_file(tmp_path), tmp_path / "token.json", opener=opener
        )
    assert str(result.value) == "POST /api/v1/categories returned HTTP 500"
    assert TEST_PAT not in str(result.value)
    assert secret_body not in str(result.value)


def test_seeds_linked_records_and_media_and_writes_only_private_token(
    tmp_path: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    token_file = tmp_path / "secrets" / "pat.txt"
    opener = FakeOpener()

    seed_rust.seed_fixture(fixture_file(tmp_path), token_file, opener=opener)

    assert json.loads(token_file.read_text(encoding="utf-8")) == {"token": TEST_PAT}
    assert stat.S_IMODE(token_file.stat().st_mode) == 0o600
    assert TEST_PAT not in capsys.readouterr().out
    requests = [
        (
            request.method,
            urllib.parse.urlsplit(request.full_url).path,
            request,
        )
        for request, _ in opener.calls
    ]
    assert [(method, path) for method, path, _ in requests] == [
        ("POST", "/api/v1/auth/token"),
        ("POST", "/api/v1/categories"),
        ("POST", "/api/v1/yarns"),
        ("POST", "/api/v1/projects"),
        ("POST", "/api/v1/yarns/8/photos"),
        ("POST", "/api/v1/projects/11/images/title"),
        ("GET", "/api/v1/yarns/8"),
        ("GET", "/api/v1/projects/11"),
        ("GET", "/api/v1/projects"),
        ("GET", "/api/v1/yarns"),
        ("GET", "/media/yarns/8/yarn.jpg"),
        ("GET", "/media/thumbnails/yarns/8/yarn.jpg"),
        ("GET", "/media/projects/11/project.jpg"),
        ("GET", "/media/thumbnails/projects/11/project.jpg"),
    ]
    assert all(timeout == seed_rust.REQUEST_TIMEOUT for _, timeout in opener.calls)
    auth_body = json.loads(request_data(requests[0][2]))
    assert auth_body == {
        "email": "seed@example.invalid",
        "password": "disposable-fixture-password",
        "token_name": "Android E2E",
    }
    yarn_payload = json.loads(request_data(requests[2][2]))
    assert yarn_payload["name"] == "Riverbend Merino DK"
    project_payload = json.loads(request_data(requests[3][2]))
    assert project_payload["name"] == "Heirloom Baby Blanket"
    assert project_payload["category"] == "Schal"
    assert project_payload["description"]
    assert project_payload["yarn_ids"] == [8]
    for index, asset_name in ((4, "demo_image_1.jpg"), (5, "demo_image_3.jpg")):
        request = requests[index][2]
        assert request.get_header("Authorization") == f"Bearer {TEST_PAT}"
        content_type = request.get_header("Content-type")
        assert content_type is not None
        assert content_type.startswith("multipart/form-data; boundary=")
        body = request_data(request)
        assert asset_name.encode() in body
        assert b'name="file"' in body
        assert (b'name="alt_text"' in body) is (index == 5)


def test_existing_token_file_is_not_overwritten(tmp_path: Path) -> None:
    token = tmp_path / "pat.txt"
    token.write_text("existing", encoding="utf-8")
    with pytest.raises(seed_rust.SeedError, match="already exists"):
        seed_rust._write_token(token, "new-secret")
    assert token.read_text(encoding="utf-8") == "existing"


def test_token_path_is_preflighted_before_api_mutations(tmp_path: Path) -> None:
    fixture = fixture_file(tmp_path)
    opener = FakeOpener()
    with pytest.raises(seed_rust.SeedError, match="must not replace"):
        seed_rust.seed_fixture(fixture, fixture, opener=opener)
    assert opener.calls == []


def test_media_url_must_be_local_media_path() -> None:
    for url in (
        "https://attacker.invalid/image.jpg",
        "//attacker.invalid/image.jpg",
        "/other/path/image.jpg",
        "/media/image.jpg?token=secret",
    ):
        with pytest.raises(seed_rust.SeedError):
            seed_rust._media_path(url, "media", "/media/projects/1/")
