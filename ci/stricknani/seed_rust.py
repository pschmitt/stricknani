#!/usr/bin/env python3
"""Seed disposable Rust API data for Android end-to-end runs."""

from __future__ import annotations

import argparse
import ipaddress
import json
import os
import re
import secrets
import sys
import urllib.error
import urllib.parse
import urllib.request
from collections.abc import Mapping
from pathlib import Path
from typing import Any

from ci.stricknani.rust_fixture import FixtureError, load_fixture_environment

REPO_ROOT = Path(__file__).resolve().parents[2]
MAX_JSON_BYTES = 1024 * 1024
MAX_MEDIA_BYTES = 5 * 1024 * 1024
REQUEST_TIMEOUT = 8
PROJECT_NAME = "Heirloom Baby Blanket"
YARN_NAME = "Riverbend Merino DK"
SCREENSHOT_PROJECT_DETAIL = {
    "needles": "4.0 mm circular, 80 cm",
    "stitch_sample": "22 stitches and 30 rows = 10 cm in seed stitch, blocked",
    "notes": "Knit the border first, then pick up along the edge for the centre panel.",
}
CATEGORY_NAME = "Schal"


class SeedError(RuntimeError):
    """An error safe to display without exposing fixture values or API bodies."""


def validate_base_url(value: str) -> str:
    """Accept only plain HTTP URLs with an explicit numeric loopback address."""
    try:
        parsed = urllib.parse.urlsplit(value)
        address = ipaddress.ip_address(parsed.hostname or "")
        port = parsed.port
    except ValueError as error:
        raise SeedError("fixture URL must use a numeric loopback address") from error
    if (
        parsed.scheme != "http"
        or not address.is_loopback
        or port is None
        or not 1 <= port <= 65535
        or parsed.username is not None
        or parsed.password is not None
        or parsed.path not in ("", "/")
        or parsed.query
        or parsed.fragment
    ):
        raise SeedError("fixture URL must be a plain HTTP loopback origin")
    return f"http://{parsed.netloc}"


class NoRedirect(urllib.request.HTTPRedirectHandler):
    """Prevent redirects from forwarding fixture credentials or bearer tokens."""

    def redirect_request(self, *_args: object, **_kwargs: object) -> None:
        return None


def _opener() -> urllib.request.OpenerDirector:
    return urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())


class ApiClient:
    def __init__(
        self,
        base_url: str,
        opener: Any,
        *,
        timeout: int = REQUEST_TIMEOUT,
    ) -> None:
        self.base_url = validate_base_url(base_url)
        self.opener = opener
        self.timeout = timeout
        self.token: str | None = None

    def request(
        self,
        method: str,
        path: str,
        *,
        payload: Mapping[str, object] | None = None,
        binary: bool = False,
    ) -> Any:
        if not path.startswith("/api/v1/") and not path.startswith("/media/"):
            raise SeedError("invalid internal API path")
        accept = "application/octet-stream" if binary else "application/json"
        headers = {"Accept": accept}
        body: bytes | None = None
        if payload is not None:
            body = json.dumps(payload, separators=(",", ":")).encode("utf-8")
            headers["Content-Type"] = "application/json"
        if self.token is not None:
            headers["Authorization"] = f"Bearer {self.token}"
        request = urllib.request.Request(
            f"{self.base_url}{path}", data=body, headers=headers, method=method
        )
        try:
            with self.opener.open(request, timeout=self.timeout) as response:
                status = response.status
                limit = MAX_MEDIA_BYTES if binary else MAX_JSON_BYTES
                response_body = response.read(limit + 1)
        except urllib.error.HTTPError as error:
            # Do not read or expose server-provided error bodies.
            raise SeedError(f"{method} {path} returned HTTP {error.code}") from None
        except (OSError, TimeoutError, urllib.error.URLError):
            raise SeedError(f"{method} {path} failed") from None
        if status < 200 or status >= 300:
            raise SeedError(f"{method} {path} returned HTTP {status}")
        if len(response_body) > limit:
            raise SeedError(f"{method} {path} response exceeded the size limit")
        if binary:
            if not response_body:
                raise SeedError(f"{method} {path} returned an empty media response")
            return response_body
        try:
            decoded = json.loads(response_body)
        except (UnicodeError, json.JSONDecodeError):
            raise SeedError(f"{method} {path} returned invalid JSON") from None
        if not isinstance(decoded, (dict, list)):
            raise SeedError(f"{method} {path} returned an invalid JSON value")
        return decoded


def _object(payload: object, path: str) -> dict[str, Any]:
    if not isinstance(payload, dict):
        raise SeedError(f"{path} returned an invalid object")
    return payload


def _positive_id(payload: Mapping[str, Any], path: str) -> int:
    identifier = payload.get("id")
    if (
        not isinstance(identifier, int)
        or isinstance(identifier, bool)
        or identifier <= 0
    ):
        raise SeedError(f"{path} returned an invalid identifier")
    return identifier


def _media_path(value: object, path: str, expected_prefix: str) -> str:
    if not isinstance(value, str):
        raise SeedError(f"{path} returned an invalid media link")
    parsed = urllib.parse.urlsplit(value)
    if (
        parsed.scheme
        or parsed.netloc
        or parsed.query
        or parsed.fragment
        or not parsed.path.startswith("/media/")
        or "\\" in parsed.path
        or "%" in parsed.path
        or any(segment in ("", ".", "..") for segment in parsed.path.split("/")[1:])
        or not parsed.path.startswith(expected_prefix)
    ):
        raise SeedError(f"{path} returned an invalid media link")
    return parsed.path


def _upload(
    client: ApiClient,
    path: str,
    asset: Path,
    *,
    alt_text: str | None,
) -> dict[str, Any]:
    try:
        content = asset.read_bytes()
    except OSError:
        raise SeedError("checked-in demo image could not be read") from None
    if not content or len(content) > MAX_MEDIA_BYTES:
        raise SeedError("checked-in demo image has an invalid size")
    boundary = f"----stricknani-{secrets.token_hex(16)}"
    # The part name and filename are fixed constants, so neither can inject headers.
    parts = [
        (
            f"--{boundary}\r\nContent-Disposition: form-data; "
            f'name="file"; filename="{asset.name}"\r\n'
            "Content-Type: image/jpeg\r\n\r\n"
        ).encode(),
        content,
    ]
    if alt_text is not None:
        parts.append(
            (
                f"\r\n--{boundary}\r\nContent-Disposition: form-data; "
                'name="alt_text"\r\n\r\n'
                f"{alt_text}"
            ).encode()
        )
    parts.append(f"\r\n--{boundary}--\r\n".encode())
    body = b"".join(parts)
    request = urllib.request.Request(
        f"{client.base_url}{path}",
        data=body,
        headers={
            "Accept": "application/json",
            "Authorization": f"Bearer {client.token}",
            "Content-Type": f"multipart/form-data; boundary={boundary}",
        },
        method="POST",
    )
    try:
        with client.opener.open(request, timeout=client.timeout) as response:
            response_body = response.read(MAX_JSON_BYTES + 1)
            status = response.status
    except urllib.error.HTTPError as error:
        raise SeedError(f"POST {path} returned HTTP {error.code}") from None
    except (OSError, TimeoutError, urllib.error.URLError):
        raise SeedError(f"POST {path} failed") from None
    if status < 200 or status >= 300:
        raise SeedError(f"POST {path} returned HTTP {status}")
    if len(response_body) > MAX_JSON_BYTES:
        raise SeedError(f"POST {path} response exceeded the size limit")
    try:
        return _object(json.loads(response_body), path)
    except (UnicodeError, json.JSONDecodeError):
        raise SeedError(f"POST {path} returned invalid JSON") from None


def _write_token(path: Path, token: str) -> None:
    resolved = path.expanduser().resolve()
    if resolved == REPO_ROOT or REPO_ROOT in resolved.parents:
        raise SeedError("token file must be outside the repository")
    try:
        resolved.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
        descriptor = os.open(resolved, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        try:
            with os.fdopen(descriptor, "w", encoding="utf-8") as output:
                json.dump({"token": token}, output, separators=(",", ":"))
        except BaseException:
            resolved.unlink(missing_ok=True)
            raise
    except FileExistsError:
        raise SeedError("token file already exists") from None
    except OSError:
        raise SeedError("private token file could not be written") from None


def _validate_token_path(path: Path, fixture_file: Path) -> Path:
    candidate = path.expanduser()
    resolved = candidate.resolve()
    fixture = fixture_file.expanduser().resolve()
    if resolved == REPO_ROOT or REPO_ROOT in resolved.parents:
        raise SeedError("token file must be outside the repository")
    if resolved == fixture:
        raise SeedError("token file must not replace the fixture file")
    if os.path.lexists(candidate):
        raise SeedError("token file already exists")
    return resolved


def seed_fixture(
    fixture_file: Path,
    token_file: Path,
    *,
    opener: Any | None = None,
    screenshot_detail: bool = False,
) -> None:
    """Create the demo records and private PAT in an existing disposable fixture.

    ``screenshot_detail`` also fills the project's needles, stitch sample and notes,
    which the Android screenshot lane captures as detail cards. E2E lanes leave them
    empty.
    """
    environment = load_fixture_environment(fixture_file)
    token_target = _validate_token_path(token_file, fixture_file)
    client = ApiClient(environment["STRICKNANI_RUST_TEST_URL"], opener or _opener())

    token_payload = _object(
        client.request(
            "POST",
            "/api/v1/auth/token",
            payload={
                "email": environment["STRICKNANI_RUST_TEST_EMAIL"],
                "password": environment["STRICKNANI_RUST_TEST_PASSWORD"],
                "token_name": "Android E2E",
            },
        ),
        "/api/v1/auth/token",
    )
    token = token_payload.get("token")
    if not isinstance(token, str) or re.fullmatch(r"sna_[0-9a-f]{64}", token) is None:
        raise SeedError("/api/v1/auth/token returned an invalid token")
    client.token = token

    category = _object(
        client.request("POST", "/api/v1/categories", payload={"name": CATEGORY_NAME}),
        "/api/v1/categories",
    )
    if category.get("name") != CATEGORY_NAME:
        raise SeedError("/api/v1/categories did not create the expected category")

    yarn = _object(
        client.request(
            "POST",
            "/api/v1/yarns",
            payload={
                "name": YARN_NAME,
                "description": "Soft merino yarn for the blanket sample.",
                "brand": "Riverbend Spinning Co.",
                "colorway": "Cloud Blue",
                "fiber_content": "100% merino wool",
                "weight_category": "DK",
                "link": "https://example.invalid/yarns/riverbend-merino-dk",
            },
        ),
        "/api/v1/yarns",
    )
    yarn_id = _positive_id(yarn, "/api/v1/yarns")
    if yarn.get("name") != YARN_NAME:
        raise SeedError("/api/v1/yarns returned an unexpected yarn")

    project = _object(
        client.request(
            "POST",
            "/api/v1/projects",
            payload={
                "name": PROJECT_NAME,
                "category": CATEGORY_NAME,
                "description": (
                    "A soft heirloom blanket knitted in a simple textured stitch."
                ),
                "link": "https://example.invalid/projects/heirloom-baby-blanket",
                "yarn_ids": [yarn_id],
                **(SCREENSHOT_PROJECT_DETAIL if screenshot_detail else {}),
            },
        ),
        "/api/v1/projects",
    )
    project_id = _positive_id(project, "/api/v1/projects")
    if (
        project.get("name") != PROJECT_NAME
        or project.get("category") != CATEGORY_NAME
        or not isinstance(project.get("description"), str)
        or not project["description"].strip()
        or project.get("yarn_ids") != [yarn_id]
    ):
        raise SeedError("/api/v1/projects returned an invalid demo project")

    asset_root = REPO_ROOT / "demo_assets"
    yarn_photo = _upload(
        client,
        f"/api/v1/yarns/{yarn_id}/photos",
        asset_root / "demo_image_1.jpg",
        alt_text=None,
    )
    project_image = _upload(
        client,
        f"/api/v1/projects/{project_id}/images/title",
        asset_root / "demo_image_3.jpg",
        alt_text="Heirloom baby blanket",
    )
    yarn_url = _media_path(
        yarn_photo.get("url"), "yarn photo", f"/media/yarns/{yarn_id}/"
    )
    yarn_thumbnail = _media_path(
        yarn_photo.get("thumbnail_url"),
        "yarn photo",
        f"/media/thumbnails/yarns/{yarn_id}/",
    )
    project_url = _media_path(
        project_image.get("url"), "project image", f"/media/projects/{project_id}/"
    )
    project_thumbnail = _media_path(
        project_image.get("thumbnail_url"),
        "project image",
        f"/media/thumbnails/projects/{project_id}/",
    )
    if not yarn_photo.get("alt_text") or not project_image.get("alt_text"):
        raise SeedError("media upload returned an invalid alternative text")

    yarn_detail = _object(
        client.request("GET", f"/api/v1/yarns/{yarn_id}"),
        "/api/v1/yarns/{id}",
    )
    project_detail = _object(
        client.request("GET", f"/api/v1/projects/{project_id}"),
        "/api/v1/projects/{id}",
    )
    if (
        yarn_detail.get("name") != YARN_NAME
        or not isinstance(yarn_detail.get("photos"), list)
        or not any(
            photo.get("url") == yarn_url
            for photo in yarn_detail["photos"]
            if isinstance(photo, dict)
        )
        or project_detail.get("name") != PROJECT_NAME
        or project_detail.get("yarn_ids") != [yarn_id]
        or not isinstance(project_detail.get("images"), list)
        or not any(
            image.get("url") == project_url
            for image in project_detail["images"]
            if isinstance(image, dict)
        )
    ):
        raise SeedError("detail API did not retain seeded media and associations")

    project_list = _object(
        client.request("GET", "/api/v1/projects?page=1"), "/api/v1/projects"
    )
    yarn_list = _object(client.request("GET", "/api/v1/yarns?page=1"), "/api/v1/yarns")
    project_item = next(
        (
            item
            for item in project_list.get("items", [])
            if isinstance(item, dict) and item.get("id") == project_id
        ),
        None,
    )
    yarn_item = next(
        (
            item
            for item in yarn_list.get("items", [])
            if isinstance(item, dict) and item.get("id") == yarn_id
        ),
        None,
    )
    if (
        not isinstance(project_item, dict)
        or not isinstance(yarn_item, dict)
        or project_item.get("name") != PROJECT_NAME
        or yarn_item.get("name") != YARN_NAME
        or _media_path(
            project_item.get("preview_url"),
            "project list",
            f"/media/thumbnails/projects/{project_id}/",
        )
        != project_thumbnail
        or _media_path(
            yarn_item.get("preview_url"),
            "yarn list",
            f"/media/thumbnails/yarns/{yarn_id}/",
        )
        != yarn_thumbnail
    ):
        raise SeedError("list API did not expose the seeded previews")

    # Verify the returned paths are actually served by this loopback fixture.
    client.request("GET", yarn_url, binary=True)
    client.request("GET", yarn_thumbnail, binary=True)
    client.request("GET", project_url, binary=True)
    client.request("GET", project_thumbnail, binary=True)
    _write_token(token_target, token)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--fixture-file", type=Path, required=True)
    parser.add_argument("--token-file", type=Path, required=True)
    parser.add_argument(
        "--screenshot-detail",
        action="store_true",
        help="also fill the stitch sample and notes shown in Android screenshots",
    )
    args = parser.parse_args(argv)
    try:
        seed_fixture(
            args.fixture_file,
            args.token_file,
            screenshot_detail=args.screenshot_detail,
        )
    except (FixtureError, SeedError) as error:
        print(f"Rust E2E seed failed: {error}", file=sys.stderr)
        return 1
    print("Rust E2E fixture seeded; PAT written to protected token file.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
