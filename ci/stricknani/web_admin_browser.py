#!/usr/bin/env python3
"""Exercise administrator users and audit screens on a private Rust fixture."""

from __future__ import annotations

import argparse
import ipaddress
import json
import os
import re
import secrets
import shutil
import sys
import tempfile
import zlib
from collections.abc import Callable
from pathlib import Path
from urllib.parse import urlsplit

from playwright.sync_api import (
    Dialog,
    Locator,
    Page,
    Response,
    Route,
    expect,
    sync_playwright,
)
from playwright.sync_api import TimeoutError as PlaywrightTimeoutError

from ci.stricknani import rust_fixture, seed_rust


class AcceptanceFailure(RuntimeError):
    """A safe-to-display browser assertion failure."""


ACCOUNT_HEADING_NAME = re.compile(r"^(?:Account|Konto)$")
ACCOUNT_SETTINGS_ITEM_NAME = re.compile(r"^(?:Account settings|Kontoeinstellungen)$")
SIGN_OUT_BUTTON_NAME = re.compile(r"^(?:Sign out|Abmelden)$")
UPLOAD_PICTURE_BUTTON_NAME = re.compile(r"^(?:Upload picture|Bild hochladen)$")


def _require(condition: bool, message: str) -> None:
    if not condition:
        raise AcceptanceFailure(message)


def _diagnostic_list(value: object) -> str:
    if isinstance(value, list) and all(isinstance(item, str) for item in value):
        return ",".join(value)
    return "invalid"


def _svg_shape_signature(icon: Locator) -> str:
    signature: object = icon.evaluate(
        """svg => [
          ...svg.querySelectorAll(
            'path, circle, rect, line, polyline, polygon'
          )
        ].map((shape) => {
            const attributes = [
              'd', 'cx', 'cy', 'r', 'x', 'y', 'width', 'height',
              'x1', 'x2', 'y1', 'y2', 'points'
            ];
            const drawing = attributes
              .filter((name) => shape.hasAttribute(name))
              .map((name) => `${name}=${shape.getAttribute(name)}`)
              .join(';');
            return `${shape.tagName.toLowerCase()}:${drawing}`;
          })
          .sort()
          .join('|')"""
    )
    return signature if isinstance(signature, str) else ""


def _is_admin_avatar_response(response: Response, origin: str) -> bool:
    parsed = urlsplit(response.url)
    expected_origin = urlsplit(origin)
    parts = parsed.path.split("/")
    return (
        response.request.resource_type == "image"
        and parsed.scheme == expected_origin.scheme
        and parsed.netloc == expected_origin.netloc
        and len(parts) == 8
        and parts[1:5] == ["api", "v2", "admin", "users"]
        and parts[5].isdigit()
        and parts[6] == "avatar"
        and bool(parts[7])
    )


def _loopback_origin(url: str) -> str:
    try:
        parsed = urlsplit(url)
        port = parsed.port
        loopback = parsed.hostname == "localhost"
        if parsed.hostname and not loopback:
            loopback = ipaddress.ip_address(parsed.hostname).is_loopback
    except ValueError as error:
        raise AcceptanceFailure("fixture URL is invalid") from error
    if (
        parsed.scheme != "http"
        or not parsed.hostname
        or not loopback
        or parsed.username is not None
        or parsed.password is not None
        or parsed.query
        or parsed.fragment
        or port is None
        or not 1 <= port <= 65535
    ):
        raise AcceptanceFailure("fixture URL must use plain HTTP on loopback")
    return f"http://127.0.0.1:{port}"


def _private_work_dir(path: Path | None) -> Path:
    if path is None:
        target = Path(tempfile.mkdtemp(prefix="stricknani-admin-browser-"))
    else:
        target = rust_fixture._outside_repository(path, "private work directory")
        target.mkdir(mode=0o700, parents=True, exist_ok=False)
    target = rust_fixture._outside_repository(target, "private work directory")
    target.chmod(0o700)
    return target


def _guard_loopback(page: Page, origin: str) -> None:
    def route_request(route: Route) -> None:
        parsed = urlsplit(route.request.url)
        if f"{parsed.scheme}://{parsed.netloc}" != origin:
            route.abort("blockedbyclient")
            return
        route.continue_()

    page.route("**/*", route_request)


def _sign_in(page: Page, origin: str, email: str, password: str) -> None:
    page.goto(f"{origin}/app/admin/users", wait_until="networkidle")
    page.locator("#email").wait_for(state="visible")
    page.locator("#email").fill(email)
    page.locator("#password").fill(password)
    page.get_by_role("button", name="Sign in").click()
    page.get_by_role("heading", name="Projects").wait_for(state="visible")
    page.goto(f"{origin}/app/admin/users", wait_until="networkidle")


def _create_user(
    page: Page,
    *,
    email: str,
    password: str,
    admin: bool,
) -> None:
    page.get_by_role("button", name="Create user").click()
    dialog = page.get_by_role("dialog", name="Create a user account")
    dialog.wait_for(state="visible")
    dialog.get_by_label("Email address").fill(email)
    dialog.get_by_label("Temporary password").fill(password)
    checkbox = dialog.get_by_label("Administrator access")
    if admin:
        checkbox.check()
    else:
        checkbox.uncheck()
    dialog.get_by_role("button", name="Create user", exact=True).click()
    dialog.wait_for(state="hidden")
    page.locator(".user-row").filter(has_text=email).wait_for(state="visible")


def _open_account(page: Page) -> None:
    profile = page.locator(".app-header .header-account")
    profile.wait_for(state="visible")
    profile.click()
    page.get_by_role("menuitem", name=ACCOUNT_SETTINGS_ITEM_NAME).click()
    page.wait_for_url("**/app/account")
    page.get_by_role("heading", name=ACCOUNT_HEADING_NAME).wait_for(state="visible")


def _sign_out(page: Page) -> None:
    _open_account(page)
    page.get_by_role("button", name=SIGN_OUT_BUTTON_NAME).click()
    page.wait_for_url("**/app/login")
    page.locator("#email").wait_for(state="visible")


def _sign_in_account(page: Page, origin: str, email: str, password: str) -> None:
    page.goto(f"{origin}/app/login", wait_until="networkidle")
    page.locator("#email").fill(email)
    page.locator("#password").fill(password)
    page.get_by_role("button", name="Sign in").click()
    page.get_by_role("heading", name="Projects").wait_for(state="visible")


def _create_admin_audit_project(
    page: Page, origin: str, mark: Callable[[str], None]
) -> None:
    mark("open Projects for admin audit activity")
    page.goto(f"{origin}/app/projects", wait_until="networkidle")
    page.get_by_role("heading", name="Projects", exact=True).wait_for(state="visible")
    page.get_by_role("link", name="New project", exact=True).click()
    page.get_by_role("heading", name="New project", exact=True).wait_for(
        state="visible"
    )
    mark("fill admin audit project")
    project_optional_details = page.locator("details.optional-details")
    _require(
        project_optional_details.count() == 1,
        "project details disclosure is missing from the create form",
    )
    if project_optional_details.get_attribute("open") is None:
        project_optional_details.locator("summary").click()
    _require(
        project_optional_details.count() == 1
        and project_optional_details.get_attribute("open") is not None,
        "More project details could not be expanded for the audit fixture",
    )
    page.get_by_label("Project name", exact=True).fill("Admin audit fixture")
    page.get_by_label("Category", exact=True).fill("Blankets")
    page.get_by_label("Needles", exact=True).fill("4 mm circular needles")
    page.get_by_label("Stitch sample", exact=True).fill("18 stitches over 10 cm")
    page.get_by_label("Pattern", exact=True).fill("Synthetic audit fixture pattern")
    page.get_by_label("Notes", exact=True).fill("Synthetic audit fixture notes")
    page.get_by_label("Other materials", exact=True).fill("Synthetic fixture markers")
    page.get_by_role("button", name="Add step", exact=True).click()
    page.get_by_label("Step title", exact=True).first.fill("Fixture step")
    page.get_by_label("Step instructions", exact=True).first.fill(
        "Complete the synthetic audit fixture step."
    )
    with page.expect_response(
        lambda response: (
            urlsplit(response.url).path == "/api/v2/projects"
            and response.request.method == "POST"
        )
    ) as create_response_info:
        mark("submit admin audit project")
        page.get_by_role("button", name="Create project", exact=True).click()
    _require(
        create_response_info.value.status == 201,
        "synthetic administrator audit project creation failed",
    )
    page.wait_for_url(re.compile(r".*/app/projects/[0-9]+$"))
    project_id_path = urlsplit(page.url).path.removeprefix("/app/projects/")
    _require(
        project_id_path.isdigit(),
        "created administrator project did not open its detail route",
    )
    page.get_by_role("heading", name="Admin audit fixture", exact=True).wait_for(
        state="visible"
    )
    mark("open admin audit project editor from detail")
    page.get_by_role("link", name="Edit project", exact=True).click()
    page.wait_for_url(f"**/app/projects/{project_id_path}/edit")
    page.get_by_role("heading", name="Edit project", exact=True).wait_for(
        state="visible"
    )
    project_optional_details = page.locator("details.optional-details")
    project_optional_details.wait_for(state="visible")
    _require(
        project_optional_details.count() == 1
        and project_optional_details.get_attribute("open") is not None,
        "More project details should be expanded after project creation",
    )
    mark("update admin audit project")
    with page.expect_response(
        lambda response: (
            urlsplit(response.url).path.startswith("/api/v2/projects/")
            and response.request.method == "PUT"
        )
    ) as update_response_info:
        page.get_by_label("Needles", exact=True).fill("5 mm circular needles")
        page.get_by_role("button", name="Save changes", exact=True).click()
    _require(
        update_response_info.value.status == 200,
        "synthetic administrator audit project update failed",
    )


def _browser_csrf_token(page: Page, origin: str) -> str:
    response = page.context.request.get(f"{origin}/api/v2/session")
    if response.status != 200:
        raise AcceptanceFailure("browser fixture session could not be verified")
    payload: object = response.json()
    if not isinstance(payload, dict):
        raise AcceptanceFailure("browser fixture session response was invalid")
    session_data = payload.get("data")
    if not isinstance(session_data, dict):
        raise AcceptanceFailure("browser fixture session response was invalid")
    token = session_data.get("csrf_token")
    if not isinstance(token, str) or not token:
        raise AcceptanceFailure("browser fixture session did not provide CSRF state")
    return token


def _create_cross_owner_resource(
    page: Page, origin: str, mark: Callable[[str], None]
) -> tuple[int, int, str, int, str]:
    name = "Cross-owner read-only fixture"
    mark("create synthetic owner project through authenticated v2 API")
    csrf_token = _browser_csrf_token(page, origin)
    step_payload: dict[str, object] = {
        "title": "Nested media step",
        "description": "A synthetic step image and attachment belong to this owner.",
        "step_number": 1,
    }
    headers = {
        "accept": "application/json",
        "origin": origin,
        "sec-fetch-site": "same-origin",
        "x-csrf-token": csrf_token,
    }
    create_payload = {
        "name": name,
        "category": "Blankets",
        "needles": None,
        "stitch_sample": None,
        "description": "Synthetic read-only resource",
        "notes": None,
        "other_materials": None,
        "tags": [],
        "link": None,
        "is_ai_enhanced": False,
        "yarn_ids": [],
        "steps": [step_payload],
    }
    create_response = page.context.request.post(
        f"{origin}/api/v2/projects", headers=headers, data=create_payload
    )
    if create_response.status != 201:
        raise AcceptanceFailure(
            "cross-owner fixture project creation failed "
            f"(HTTP {create_response.status})"
        )
    create_body: object = create_response.json()
    if not isinstance(create_body, dict):
        raise AcceptanceFailure("cross-owner fixture response was invalid")
    project_data = create_body.get("data")
    if not isinstance(project_data, dict):
        raise AcceptanceFailure("cross-owner fixture response was invalid")
    project = project_data
    project_id = project.get("id")
    steps = project.get("steps")
    if not isinstance(steps, list) or not steps or not isinstance(steps[0], dict):
        raise AcceptanceFailure("cross-owner fixture did not return its project step")
    step_id = steps[0].get("id")
    if (
        not isinstance(project_id, int)
        or project_id <= 0
        or not isinstance(step_id, int)
        or step_id <= 0
    ):
        raise AcceptanceFailure("cross-owner fixture did not return valid resource IDs")

    yarn_name = "Cross-owner fixture yarn"
    yarn_response = page.context.request.post(
        f"{origin}/api/v2/yarns",
        headers=headers,
        data={
            "name": yarn_name,
            "description": "Synthetic yarn for read-only card acceptance.",
            "brand": "Fixture mill",
            "colorway": "Blue sample",
            "weight_category": "DK",
            "weight_grams": 100,
            "length_meters": 220,
        },
    )
    if yarn_response.status != 201:
        raise AcceptanceFailure("cross-owner fixture yarn creation failed")
    yarn_body: object = yarn_response.json()
    yarn_data = yarn_body.get("data") if isinstance(yarn_body, dict) else None
    yarn_id = yarn_data.get("id") if isinstance(yarn_data, dict) else None
    if not isinstance(yarn_id, int) or yarn_id <= 0:
        raise AcceptanceFailure("cross-owner fixture yarn response was invalid")
    yarn_photo_response = page.context.request.post(
        f"{origin}/api/v2/yarns/{yarn_id}/photos",
        headers=headers,
        multipart={
            "file": {
                "name": "synthetic-yarn-photo.png",
                "mimeType": "image/png",
                "buffer": _tiny_png_fixture(),
            },
        },
    )
    if yarn_photo_response.status != 201:
        safe_error_code = "unavailable"
        try:
            response_body: object = yarn_photo_response.json()
        except Exception:
            response_body = None
        if isinstance(response_body, dict):
            error = response_body.get("error")
            if isinstance(error, dict):
                code = error.get("code")
                if isinstance(code, str) and code in {
                    "not_found",
                    "media_unavailable",
                    "payload_too_large",
                    "invalid_image",
                    "invalid_multipart",
                    "internal_error",
                    "unauthorized",
                    "csrf_failed",
                }:
                    safe_error_code = code
        raise AcceptanceFailure(
            "synthetic yarn photo upload failed "
            f"(HTTP {yarn_photo_response.status}, code {safe_error_code})"
        )

    title_image_response = page.context.request.post(
        f"{origin}/api/v2/projects/{project_id}/images/title",
        headers=headers,
        multipart={
            "file": {
                "name": "synthetic-project-cover.png",
                "mimeType": "image/png",
                "buffer": _tiny_png_fixture(),
            },
            "alt_text": "Synthetic project cover",
        },
    )
    if title_image_response.status != 201:
        raise AcceptanceFailure("synthetic project cover upload failed")

    mark("upload synthetic step image and attachment through authenticated v2 API")
    image_response = page.context.request.post(
        f"{origin}/api/v2/projects/{project_id}/steps/{step_id}/images",
        headers=headers,
        multipart={
            "file": {
                "name": "synthetic-step-image.png",
                "mimeType": "image/png",
                "buffer": _tiny_png_fixture(),
            },
            "alt_text": "Nested fixture step image",
        },
    )
    if image_response.status != 201:
        raise AcceptanceFailure("synthetic step image upload failed")
    attachment_response = page.context.request.post(
        f"{origin}/api/v2/projects/{project_id}/attachments",
        headers=headers,
        multipart={
            "file": {
                "name": "synthetic-pattern.txt",
                "mimeType": "text/plain",
                "buffer": b"Synthetic fixture attachment.",
            }
        },
    )
    if attachment_response.status != 201:
        raise AcceptanceFailure("synthetic attachment upload failed")

    detail_response = page.context.request.get(f"{origin}/api/v2/projects/{project_id}")
    if detail_response.status != 200:
        raise AcceptanceFailure("cross-owner fixture project could not be reloaded")
    detail_body: object = detail_response.json()
    if not isinstance(detail_body, dict):
        raise AcceptanceFailure("cross-owner fixture project response was invalid")
    detail_data = detail_body.get("data")
    if not isinstance(detail_data, dict):
        raise AcceptanceFailure("cross-owner fixture project response was invalid")
    updated_at = detail_data.get("updated_at")
    if not isinstance(updated_at, str):
        raise AcceptanceFailure("cross-owner fixture project timestamp was invalid")

    updated_name = "Cross-owner fixture <b>literal</b>"
    update_payload = {
        **create_payload,
        "name": updated_name,
        "expected_updated_at": updated_at,
        "yarn_ids": [yarn_id],
        "steps": [{**step_payload, "id": step_id}],
    }
    update_response = page.context.request.put(
        f"{origin}/api/v2/projects/{project_id}",
        headers=headers,
        data=update_payload,
    )
    if update_response.status != 200:
        raise AcceptanceFailure("cross-owner fixture project update failed")
    return project_id, step_id, updated_name, yarn_id, yarn_name


def _delete_cross_owner_resource(page: Page, origin: str, project_id: int) -> None:
    csrf_token = _browser_csrf_token(page, origin)
    response = page.context.request.delete(
        f"{origin}/api/v2/projects/{project_id}",
        headers={
            "accept": "application/json",
            "origin": origin,
            "sec-fetch-site": "same-origin",
            "x-csrf-token": csrf_token,
        },
    )
    if response.status != 204:
        raise AcceptanceFailure("owner could not delete the synthetic project")


def _tiny_png_fixture() -> bytes:
    def chunk(kind: bytes, data: bytes) -> bytes:
        checksum = zlib.crc32(kind + data) & 0xFFFFFFFF
        return len(data).to_bytes(4, "big") + kind + data + checksum.to_bytes(4, "big")

    header = (1).to_bytes(4, "big") + (1).to_bytes(4, "big") + bytes([8, 6, 0, 0, 0])
    pixel = zlib.compress(bytes([0, 36, 118, 105, 255]))
    return (
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", header)
        + chunk(b"IDAT", pixel)
        + chunk(b"IEND", b"")
    )


def _save_screenshot(page: Page, directory: Path | None, name: str) -> None:
    if directory is None:
        return
    destination = directory / name
    page.screenshot(path=str(destination), full_page=True)
    destination.chmod(0o600)


def _find_existing_no_snapshot_event(page: Page, origin: str) -> int:
    for page_number in range(1, 5):
        response = page.context.request.get(
            f"{origin}/api/v2/admin/audit?page={page_number}"
        )
        if response.status != 200:
            raise AcceptanceFailure("audit fixture page could not be verified")
        payload: object = response.json()
        data = payload.get("data") if isinstance(payload, dict) else None
        if not isinstance(data, list):
            raise AcceptanceFailure("audit fixture page response was invalid")
        for event in data:
            if not isinstance(event, dict) or event.get("has_changes") is not False:
                continue
            event_id = event.get("id")
            if not isinstance(event_id, int) or event_id <= 0:
                continue
            detail_response = page.context.request.get(
                f"{origin}/api/v2/admin/audit/{event_id}"
            )
            if detail_response.status != 200:
                continue
            detail_payload: object = detail_response.json()
            detail_data = (
                detail_payload.get("data") if isinstance(detail_payload, dict) else None
            )
            if (
                isinstance(detail_data, dict)
                and detail_data.get("changes") is None
                and detail_data.get("unavailable_reason") == "not_recorded"
            ):
                return event_id
    raise AcceptanceFailure("no existing no-snapshot audit event was available")


def _assert_decoded_card_image(card: Locator, *, message: str) -> str:
    image = card.locator(".library-card__media img, .resource-card__image img")
    image.scroll_into_view_if_needed()
    image.wait_for(state="visible")
    source = image.get_attribute("src") or ""
    decoded = image.evaluate(
        "node => Promise.race([node.decode().then(() => node.complete "
        "&& node.naturalWidth > 0), new Promise(resolve => "
        "setTimeout(() => resolve(false), 3000))]).catch(() => false)"
    )
    _require(bool(decoded), message)
    return source


def _assert_owner_resource_cards(
    page: Page,
    origin: str,
    project_id: int,
    project_name: str,
    yarn_id: int,
    yarn_name: str,
    screenshot_dir: Path | None,
) -> None:
    page.goto(f"{origin}/app/projects/{project_id}", wait_until="networkidle")
    page.get_by_role("heading", name=project_name, exact=True).wait_for(state="visible")
    materials = page.locator("details.materials-card")
    _require(
        materials.get_attribute("open") is None,
        "project materials should start collapsed",
    )
    materials.locator("summary").focus()
    page.keyboard.press("Enter")
    expect(materials).to_have_attribute("open", "")
    embedded_card = page.locator(".materials-card .resource-card").filter(
        has_text=yarn_name
    )
    embedded_card.wait_for(state="visible")
    embedded_link = embedded_card.locator("a.resource-card__link")
    embedded_href = embedded_link.get_attribute("href") or ""
    _require(
        urlsplit(embedded_href).path.endswith(f"/app/yarns/{yarn_id}"),
        "project yarn card did not use the owner's yarn route",
    )
    embedded_image = _assert_decoded_card_image(
        embedded_card, message="project yarn card preview did not decode"
    )
    _require(
        urlsplit(embedded_image).path.startswith(f"/media/thumbnails/yarns/{yarn_id}/"),
        "project yarn card did not use the owner-scoped yarn photo route",
    )
    for width, suffix in ((390, "390"), (1280, "desktop")):
        page.set_viewport_size({"width": width, "height": 900})
        _assert_no_horizontal_overflow(page, "project-reader")
        _save_screenshot(page, screenshot_dir, f"project-reader-{suffix}.png")
    embedded_link.click()
    page.get_by_role("heading", name=yarn_name, exact=True).wait_for(state="visible")

    page.goto(f"{origin}/app/projects", wait_until="networkidle")
    project_card = page.locator(".project-card").filter(has_text=project_name)
    project_card.wait_for(state="visible")
    _require(
        urlsplit(
            project_card.locator("a.resource-card__link").get_attribute("href") or ""
        ).path.endswith(f"/app/projects/{project_id}"),
        "project library card did not use the owner's project route",
    )
    project_image = _assert_decoded_card_image(
        project_card, message="project library card preview did not decode"
    )
    _require(
        urlsplit(project_image).path.startswith(
            f"/media/thumbnails/projects/{project_id}/"
        ),
        "project library card did not use the owner-scoped image route",
    )
    for width, suffix in ((390, "390"), (1280, "desktop")):
        page.set_viewport_size({"width": width, "height": 900})
        _assert_no_horizontal_overflow(page, "project-library")
        _save_screenshot(page, screenshot_dir, f"project-library-{suffix}.png")
    project_card.locator("a.resource-card__link").click()
    page.wait_for_url(f"**/app/projects/{project_id}")
    page.get_by_role("heading", name=project_name, exact=True).wait_for(state="visible")

    page.goto(f"{origin}/app/yarns", wait_until="networkidle")
    yarn_card = page.locator(".yarn-card").filter(has_text=yarn_name)
    yarn_card.wait_for(state="visible")
    _require(
        urlsplit(
            yarn_card.locator("a.resource-card__link").get_attribute("href") or ""
        ).path.endswith(f"/app/yarns/{yarn_id}"),
        "yarn library card did not use the owner's yarn route",
    )
    yarn_image = _assert_decoded_card_image(
        yarn_card, message="yarn library card preview did not decode"
    )
    _require(
        urlsplit(yarn_image).path.startswith(f"/media/thumbnails/yarns/{yarn_id}/"),
        "yarn library card did not use the owner-scoped photo route",
    )
    for width, suffix in ((390, "390"), (1280, "desktop")):
        page.set_viewport_size({"width": width, "height": 900})
        _assert_no_horizontal_overflow(page, "yarn-library")
        _save_screenshot(page, screenshot_dir, f"yarn-library-{suffix}.png")

    yarn_card.locator("a.resource-card__link").click()
    page.wait_for_url(f"**/app/yarns/{yarn_id}")
    page.get_by_role("heading", name=yarn_name, exact=True).wait_for(state="visible")


def _upload_account_avatar(page: Page) -> None:
    page.locator('input[type="file"]').set_input_files(
        {
            "name": "synthetic-admin-avatar.png",
            "mimeType": "image/png",
            "buffer": _tiny_png_fixture(),
        }
    )
    with page.expect_response(
        lambda response: (
            urlsplit(response.url).path == "/api/v2/account/avatar"
            and response.request.method == "POST"
        )
    ) as upload_response_info:
        page.get_by_role("button", name=UPLOAD_PICTURE_BUTTON_NAME).click()
    _require(
        upload_response_info.value.status == 200,
        "synthetic profile avatar upload failed",
    )
    portrait = page.locator(".avatar__portrait img")
    portrait.wait_for(state="visible")
    _require(
        bool(
            portrait.evaluate(
                "node => node.decode().then(() => node.complete "
                "&& node.naturalWidth > 0).catch(() => false)"
            )
        ),
        "synthetic profile avatar did not render in Account",
    )


def _assert_user_avatar(avatar: Locator, *, initial: str, image_expected: bool) -> None:
    avatar.wait_for(state="visible")
    image = avatar.locator("img[alt='']")
    if image_expected:
        image.wait_for(state="visible")
        _require(
            bool(
                image.evaluate(
                    "node => node.decode().then(() => node.complete "
                    "&& node.naturalWidth > 0).catch(() => false)"
                )
            )
            and avatar.locator("span").count() == 0,
            "user avatar image failed to render with the initial fallback active",
        )
        return
    fallback = avatar.locator("span")
    fallback.wait_for(state="visible")
    _require(
        image.count() == 0 and fallback.inner_text().strip() == initial,
        "missing or broken avatar did not show the user's initial",
    )


def _assert_more_navigation(page: Page, *, is_admin: bool) -> None:
    origin = f"{urlsplit(page.url).scheme}://{urlsplit(page.url).netloc}"
    page.goto(f"{origin}/app/create")
    page.get_by_role(
        "heading", name="What would you like to add?", exact=True
    ).wait_for(state="visible")
    create_import = page.locator(".create-options a[href$='/imports']")
    _require(
        create_import.count() == 1,
        "Create does not contain exactly one Imports link",
    )
    _require(
        page.locator(".secondary-nav a[href$='/imports']").count() == 0,
        "Imports is duplicated in Workspace navigation",
    )

    page.goto(f"{origin}/app/more")
    page.get_by_role("heading", name="More", exact=True).wait_for(state="visible")
    cards = page.locator(".more-links")
    expected_cards = [
        ("Categories", "/categories"),
        ("Gauge calculator", "/gauge"),
    ]
    if is_admin:
        expected_cards.extend(
            (("Admin", "/admin/users"), ("Audit log", "/admin/audit"))
        )
    # About and the external Android app link are workspace-less tiles; the
    # Privacy Policy is linked from About only.
    _require(
        cards.locator("a").count() == len(expected_cards) + 2
        and cards.locator("a[href$='/privacy']").count() == 0
        and cards.locator("a[href$='/about']").count() == 1
        and cards.locator("a[target='_blank']").count() == 1
        and cards.locator("a[href$='/imports']").count() == 0
        and page.locator(".secondary-nav a[href$='/imports']").count() == 0,
        "More or Workspace contains unexpected links",
    )
    _require(
        cards.get_by_role("link", name="Account", exact=True).count() == 0
        and page.locator(".secondary-nav a[href$='/account']").count() == 0,
        "More or Workspace contains a duplicate Account link",
    )
    _require(
        page.locator(
            ".app-header button.header-account[aria-haspopup='dialog']"
        ).count()
        == 1,
        "Account is unavailable from the profile links",
    )
    for label, href in expected_cards:
        card = cards.locator(f"a[href$='{href}']")
        title = card.locator(".ui-navigation-tile__title")
        description = card.locator(".ui-navigation-tile__description")
        more_icon = card.locator(
            ".ui-navigation-tile__icon svg[aria-hidden='true'][focusable='false']"
        )
        sidebar_icon = page.locator(f".secondary-nav a[href$='{href}'] .nav-icon svg")
        more_signature = (
            _svg_shape_signature(more_icon) if more_icon.count() == 1 else ""
        )
        sidebar_signature = (
            _svg_shape_signature(sidebar_icon) if sidebar_icon.count() == 1 else ""
        )
        card_ok = card.count() == 1
        title_ok = title.count() == 1 and title.inner_text().strip() == label
        description_ok = description.count() == 1 and bool(
            description.inner_text().strip()
        )
        href_ok = (card.get_attribute("href") or "").endswith(href)
        more_icon_ok = more_icon.count() == 1
        sidebar_icon_ok = sidebar_icon.count() == 1
        path_parity = bool(more_signature) and more_signature == sidebar_signature
        _require(
            card_ok
            and title_ok
            and description_ok
            and href_ok
            and more_icon_ok
            and sidebar_icon_ok
            and path_parity,
            "a More card failed its link/description/icon parity check "
            f"(label={label}, card={card_ok}, title={title_ok}, "
            f"description={description_ok}, "
            f"href={href_ok}, more_icon={more_icon_ok}, "
            f"sidebar_icon={sidebar_icon_ok}, path_parity={path_parity})",
        )


def _assert_audit_visual_measurements(page: Page) -> None:
    viewport = page.viewport_size
    if viewport is None:
        raise AcceptanceFailure("audit viewport dimensions were unavailable")
    original_width = viewport["width"]
    try:
        for width in (320, 390, 1280):
            page.set_viewport_size({"width": width, "height": viewport["height"]})
            page.evaluate(
                "() => new Promise(resolve => requestAnimationFrame("
                "() => requestAnimationFrame(resolve)))"
            )
            measurements: object = page.evaluate(
                """() => {
          const rows = [...document.querySelectorAll('.event-row')];
          const badges = [...document.querySelectorAll('.audit-action-badge')];
          const linear = (value) => {
            const numbers = value.match(/[\\d.]+/g)?.slice(0, 3).map(Number);
            if (!numbers || numbers.length !== 3) return null;
            const scale = Math.max(...numbers) <= 1 ? 255 : 1;
            const channels = numbers.map((number) => number * scale / 255);
            return channels.map((channel) => channel <= 0.04045
              ? channel / 12.92
              : ((channel + 0.055) / 1.055) ** 2.4);
          };
          const luminance = (color) => {
            const channels = linear(color);
            return channels && channels[0] * 0.2126
              + channels[1] * 0.7152 + channels[2] * 0.0722;
          };
          const contrast = (foreground, background) => {
            const first = luminance(foreground);
            const second = luminance(background);
            if (first === null || second === null) return 0;
            return (Math.max(first, second) + 0.05)
              / (Math.min(first, second) + 0.05);
          };
          const rowMetrics = rows.map((row) => {
            const rowRect = row.getBoundingClientRect();
            const slot = row.querySelector('.event-diff-slot');
            const date = row.querySelector('.event-meta time');
            const slotRect = slot?.getBoundingClientRect();
            const dateRect = date?.getBoundingClientRect();
            const diff = row.querySelector('.show-diff');
            const diffRect = diff?.getBoundingClientRect();
            return {
              height: rowRect.height,
              slotOffset: slotRect ? slotRect.top - rowRect.top : null,
              slotLeft: slotRect ? slotRect.left - rowRect.left : null,
              slotHeight: slotRect?.height ?? 0,
              dateOffset: dateRect ? dateRect.top - rowRect.top : null,
              dateLeft: dateRect ? dateRect.left - rowRect.left : null,
              hasDiff: Boolean(diff),
              diffTargetOk: !diff || Boolean(diffRect
                && diffRect.width >= 44 && diffRect.height >= 44),
            };
          });
          const colorsByTone = new Map();
          const badgeMetrics = badges.map((badge) => {
            const style = getComputedStyle(badge);
            const tone = [...badge.classList].find((name) =>
              name.startsWith('action-badge--')) ?? 'unknown';
            if (tone !== 'action-badge--unknown')
              colorsByTone.set(tone, style.color);
            return {
              tone,
              contrast: contrast(style.color, style.backgroundColor),
              unclipped: badge.scrollWidth <= badge.clientWidth + 1,
            };
          });
          const spread = (values) => values.every(Number.isFinite)
            && Math.max(...values) - Math.min(...values) <= 1;
          const heights = rowMetrics.map((row) => row.height);
          const slotOffsets = rowMetrics.map((row) => row.slotOffset);
          const dateOffsets = rowMetrics.map((row) => row.dateOffset);
          const slotLefts = rowMetrics.map((row) => row.slotLeft);
          const dateLefts = rowMetrics.map((row) => row.dateLeft);
          return {
            rowCount: rows.length,
            badgeCount: badges.length,
            equalRowHeights: heights.length > 0 && spread(heights),
            stableSlotOffset: slotOffsets.length > 0 && spread(slotOffsets),
            stableSlotLeft: slotLefts.length > 0 && spread(slotLefts),
            stableDateOffset: dateOffsets.length > 0 && spread(dateOffsets),
            stableDateLeft: dateLefts.length > 0 && spread(dateLefts),
            hasDiffCount: rowMetrics.filter((row) => row.hasDiff).length,
            withoutDiffCount: rowMetrics.filter((row) => !row.hasDiff).length,
            allDiffTargetsAtLeast44: rowMetrics.every((row) => row.diffTargetOk),
            slotsAtLeast44: rowMetrics.every((row) => row.slotHeight >= 44),
            textContrastAtLeast45: badgeMetrics.length > 0
              && badgeMetrics.every((badge) => badge.contrast >= 4.5),
            badgeTextUnclipped: badgeMetrics.every((badge) => badge.unclipped),
            distinctKnownToneColors: colorsByTone.size >= 2
              && new Set(colorsByTone.values()).size === colorsByTone.size,
          };
                }"""
            )
            if not isinstance(measurements, dict):
                raise AcceptanceFailure("audit visual measurements were unavailable")
            _require(
                measurements.get("rowCount", 0) >= 2
                and measurements.get("badgeCount", 0) >= 2
                and measurements.get("equalRowHeights") is True
                and measurements.get("stableSlotOffset") is True
                and measurements.get("stableSlotLeft") is True
                and measurements.get("stableDateOffset") is True
                and measurements.get("stableDateLeft") is True
                and measurements.get("allDiffTargetsAtLeast44") is True
                and measurements.get("slotsAtLeast44") is True
                and measurements.get("textContrastAtLeast45") is True
                and measurements.get("badgeTextUnclipped") is True
                and measurements.get("distinctKnownToneColors") is True,
                f"audit action colors, contrast, or row alignment failed at {width}px "
                f"({ {k: v for k, v in measurements.items() if v is not True} })",
            )
    finally:
        page.set_viewport_size({"width": original_width, "height": viewport["height"]})


def _assert_mobile_header_alignment(
    page: Page, expected_parent_href: str | None = None
) -> None:
    expected_parent = expected_parent_href is not None
    page.wait_for_function(
        "() => { const chevron = document.querySelector("
        "'.app-header .brand-chevron'); return chevron "
        "&& getComputedStyle(chevron).opacity === "
        + ("'1'; }" if expected_parent else "'0'; }"),
        timeout=5_000,
    )
    alignment = page.evaluate(
        """() => {
          const header = document.querySelector('.app-header');
          const brand = document.querySelector('.app-header .brand-lockup');
          const mark = brand?.querySelector('.brand-mark-wrap');
          const chevron = brand?.querySelector('.brand-chevron');
          const icon = chevron?.querySelector('svg');
          const account = document.querySelector('.app-header .header-account');
          if (!header || !brand || !mark || !chevron || !account) return null;
          const rect = (element) => {
            const box = element.getBoundingClientRect();
            return {
              left: box.left,
              right: box.right,
              width: box.width,
              height: box.height,
            };
          };
          return {
            header: rect(header),
            brand: rect(brand),
            mark: rect(mark),
            chevron: rect(chevron),
            icon: icon ? rect(icon) : null,
            parentHref: brand.classList.contains('parent-link')
              ? brand.getAttribute('href') : null,
            parentLabel: brand.getAttribute('aria-label'),
            chevronOpacity: getComputedStyle(chevron).opacity,
            account: rect(account),
          };
        }"""
    )
    brand = alignment.get("brand") if isinstance(alignment, dict) else None
    icon = alignment.get("icon") if isinstance(alignment, dict) else None
    _require(
        isinstance(alignment, dict)
        and isinstance(brand, dict)
        and brand["width"] >= 44
        and brand["height"] >= 44
        and (alignment["parentHref"] is not None) is expected_parent
        and (
            not expected_parent
            or (
                alignment["parentHref"] == expected_parent_href
                and alignment["parentLabel"] == "Back"
                and alignment["chevronOpacity"] == "1"
                and isinstance(icon, dict)
            )
        )
        and (
            expected_parent
            or (
                alignment["parentLabel"] == "Stricknani"
                and alignment["chevronOpacity"] == "0"
            )
        )
        and alignment["mark"]["left"] >= alignment["header"]["left"] - 1
        and alignment["mark"]["right"] <= alignment["account"]["left"] + 1
        and alignment["account"]["right"] <= alignment["header"]["right"] + 1,
        "mobile parent-link brand and account controls are misaligned",
    )


def _assert_no_horizontal_overflow(page: Page, route: str) -> None:
    if route not in {
        "users",
        "audit",
        "resource",
        "diff",
        "project-reader",
        "project-library",
        "yarn-library",
    }:
        raise AcceptanceFailure("invalid admin responsive route")
    measurements = page.evaluate(
        """(allowedClasses) => {
          const root = document.documentElement;
          const viewport = window.innerWidth;
          const offenders = [...document.querySelectorAll('body *')]
            .flatMap((element) => {
              const rect = element.getBoundingClientRect();
              const left = Math.round(rect.left * 10) / 10;
              const right = Math.round(rect.right * 10) / 10;
              const scrollWidth = element.scrollWidth;
              const clientWidth = element.clientWidth;
              if (
                right <= viewport + 1 &&
                left >= -1 &&
                scrollWidth <= clientWidth + 1
              ) return [];
              const classes = [...element.classList]
                .filter((name) => allowedClasses.includes(name))
                .slice(0, 4);
              return [{
                tag: element.tagName.toLowerCase(),
                classes,
                left,
                right,
                scrollWidth,
                clientWidth,
                rightOverflow: Math.max(0, Math.round((right - viewport) * 10) / 10),
              }];
            })
            .sort((left, right) =>
              Math.max(right.rightOverflow, right.scrollWidth - right.clientWidth) -
              Math.max(left.rightOverflow, left.scrollWidth - left.clientWidth)
            );
          return {
            viewport,
            documentClientWidth: root.clientWidth,
            documentScrollWidth: root.scrollWidth,
            offenders: offenders.slice(0, 8),
          };
        }""",
        [
            "app-shell",
            "audit-filters",
            "change-values",
            "diff-page",
            "audit-action-badge",
            "event-row",
            "page-button",
            "page-controls",
            "page-indicator",
            "pagination",
            "resource-content",
            "resource-card",
            "resource-card__copy",
            "resource-card__image",
            "resource-card__link",
            "resource-page",
            "resource-section",
            "user-actions",
            "user-identity",
            "user-row",
        ],
    )
    if measurements["documentScrollWidth"] <= measurements["viewport"]:
        return
    offender_details = []
    for item in measurements["offenders"]:
        classes = ".".join(item["classes"])
        class_suffix = f".{classes}" if classes else ""
        offender_details.append(
            f"{item['tag']}{class_suffix} left={item['left']} right={item['right']} "
            f"scroll={item['scrollWidth']} client={item['clientWidth']}"
        )
    details = "; ".join(offender_details) or "no element geometry matched"
    raise AcceptanceFailure(
        f"admin {route} page overflows at {measurements['viewport']}px "
        f"(document scroll={measurements['documentScrollWidth']}, "
        f"client={measurements['documentClientWidth']}); {details}"
    )


def run_acceptance(
    fixture_file: Path,
    admin_email: str,
    admin_password: str,
    chromium_executable: Path | None = None,
    stage_file: Path | None = None,
    screenshot_dir: Path | None = None,
) -> None:
    fixture = rust_fixture.load_fixture_environment(fixture_file)
    origin = _loopback_origin(fixture["STRICKNANI_RUST_TEST_URL"])
    if chromium_executable is not None:
        chromium_executable = chromium_executable.expanduser().resolve()
        if not chromium_executable.is_file() or not os.access(
            chromium_executable, os.X_OK
        ):
            raise AcceptanceFailure("configured Chromium executable is unavailable")

    member_email = f"member-{secrets.token_hex(5)}@example.invalid"
    member_password = f"A temporary password {secrets.token_urlsafe(14)}"
    second_admin_email = f"admin-{secrets.token_hex(5)}@example.invalid"
    second_admin_password = f"A temporary password {secrets.token_urlsafe(14)}"
    reset_password = f"A reset password {secrets.token_urlsafe(14)}"

    def mark(stage: str) -> None:
        if stage_file is not None:
            stage_file.write_text(stage, encoding="ascii")

    with sync_playwright() as playwright:
        mark("launch Chromium")
        browser = playwright.chromium.launch(
            headless=True,
            executable_path=(
                str(chromium_executable) if chromium_executable is not None else None
            ),
        )
        try:
            context = browser.new_context(
                locale="en-US",
                viewport={"width": 1280, "height": 900},
                service_workers="block",
            )
            page = context.new_page()
            _guard_loopback(page, origin)
            mark("sign in administrator")
            _sign_in(page, origin, admin_email, admin_password)
            page.get_by_role("heading", name="User accounts").wait_for(state="visible")

            mark("check logical parent link preserves dirty-navigation confirmation")
            page.set_viewport_size({"width": 390, "height": 900})
            page.locator('a[href="/app/projects"]:visible').first.click()
            page.wait_for_url("**/app/projects")
            page.locator('.mobile-navigation a[href="/app/create"]').click()
            page.wait_for_url("**/app/create")
            page.locator('.create-options a[href="/app/projects/new"]').click()
            page.wait_for_url("**/app/projects/new")
            page.get_by_role("heading", name="New project", exact=True).wait_for(
                state="visible"
            )
            project_name_field = page.get_by_label("Project name", exact=True)
            project_name_field.fill("Unsaved header back fixture")
            parent_probe: object = page.evaluate(
                """() => {
                  const link = document.querySelector(
                    '.app-header .brand-lockup.parent-link');
                  const rect = link?.getBoundingClientRect();
                  const icon = link?.querySelector('.brand-chevron svg')
                    ?.getBoundingClientRect();
                  const logo = link?.querySelector('.brand-mark-wrap')
                    ?.getBoundingClientRect();
                  return {
                    route: location.pathname === '/app/projects/new',
                    parentHref: link?.getAttribute('href') ?? null,
                    parentLabel: link?.getAttribute('aria-label') ?? null,
                    count: document.querySelectorAll(
                      '.app-header .brand-lockup.parent-link').length,
                    visible: Boolean(link && rect && rect.width > 0
                      && rect.height > 0 && getComputedStyle(link).visibility
                      === 'visible'),
                    targetSize: Boolean(rect && rect.width >= 44
                      && rect.height >= 44),
                    iconInsideTarget: Boolean(rect && icon
                      && icon.left >= rect.left && icon.right <= rect.right
                      && icon.top >= rect.top && icon.bottom <= rect.bottom),
                    markX: logo?.left ?? null,
                    viewport: Boolean(rect && rect.left >= 0
                      && rect.right <= innerWidth && rect.top >= 0
                      && rect.bottom <= innerHeight)
                  };
                }"""
            )
            if not isinstance(parent_probe, dict):
                raise AcceptanceFailure(
                    "header parent diagnostics returned an invalid shape"
                )
            _require(
                parent_probe.get("route") is True
                and parent_probe.get("parentHref") == "/app/projects"
                and parent_probe.get("parentLabel") == "Back"
                and parent_probe.get("count") == 1
                and parent_probe.get("visible") is True
                and parent_probe.get("targetSize") is True
                and parent_probe.get("iconInsideTarget") is True
                and isinstance(parent_probe.get("markX"), (float, int))
                and parent_probe.get("viewport") is True,
                "header parent link was not an aligned same-app target",
            )
            _assert_mobile_header_alignment(page, "/app/projects")

            def dismiss_dirty_prompt(dialog: Dialog) -> None:
                dialog.dismiss()

            page.once("dialog", dismiss_dirty_prompt)
            with page.expect_event("dialog", timeout=5_000) as cancelled_back:
                page.get_by_role("link", name="Back", exact=True).click()
            _require(
                cancelled_back.value.type == "confirm",
                "header back did not open the dirty-navigation confirmation",
            )
            page.wait_for_function(
                "() => location.pathname === '/app/projects/new' "
                "&& document.querySelector('#project-name')?.value "
                "=== 'Unsaved header back fixture'",
                timeout=5_000,
            )
            page.get_by_role("heading", name="New project", exact=True).wait_for(
                state="visible"
            )
            _require(
                project_name_field.input_value() == "Unsaved header back fixture",
                "cancelling header back discarded the unsaved project name",
            )
            page.once("dialog", lambda dialog: dialog.accept())
            with page.expect_event("dialog", timeout=5_000) as accepted_back:
                page.get_by_role("link", name="Back", exact=True).click()
            _require(
                accepted_back.value.type == "confirm",
                "accepting header back did not show the dirty-navigation confirmation",
            )
            page.wait_for_url("**/app/projects")
            page.get_by_role("heading", name="Projects", exact=True).wait_for(
                state="visible"
            )
            page.mouse.move(200, 500)
            page.evaluate(
                "() => new Promise(resolve => requestAnimationFrame("
                "() => requestAnimationFrame(resolve)))"
            )
            parent_mark_x = (
                parent_probe.get("markX") if isinstance(parent_probe, dict) else None
            )
            if isinstance(parent_mark_x, (float, int)):
                # The chevron slot eases in; wait for the settled position
                # rather than sampling mid-transition after two frames.
                try:
                    page.wait_for_function(
                        "expected => Math.abs(document.querySelector("
                        "'.app-header .brand-mark-wrap').getBoundingClientRect()"
                        ".left - expected) <= 1",
                        arg=float(parent_mark_x),
                        timeout=2_000,
                    )
                except PlaywrightTimeoutError:
                    pass
            top_level_mark_x: object = page.locator(
                ".app-header .brand-mark-wrap"
            ).evaluate("element => element.getBoundingClientRect().left")
            _require(
                isinstance(top_level_mark_x, (float, int))
                and isinstance(parent_mark_x, (float, int))
                and abs(float(top_level_mark_x) - float(parent_mark_x)) <= 1,
                "brand mark shifted when the chevron changed route state at 390px",
            )
            # The following icon parity check measures the desktop rail SVGs.
            # Restore desktop width after the mobile dirty-navigation check.
            page.set_viewport_size({"width": 1280, "height": 900})
            page.goto(f"{origin}/app/admin/users", wait_until="networkidle")
            page.get_by_role("heading", name="User accounts").wait_for(state="visible")

            mark("check admin More cards and profile-only Account")
            _assert_more_navigation(page, is_admin=True)

            mark("upload administrator actor avatar")
            _open_account(page)
            _upload_account_avatar(page)
            page.goto(f"{origin}/app/admin/users", wait_until="networkidle")
            page.get_by_role("heading", name="User accounts").wait_for(state="visible")

            mark("create member")
            _create_user(
                page, email=member_email, password=member_password, admin=False
            )
            member_row = page.locator(".user-row").filter(has_text=member_email)
            _require(
                "Member" in member_row.inner_text()
                and "Active" in member_row.inner_text(),
                "new account did not have the member role and active status",
            )
            _assert_user_avatar(
                member_row.locator(".user-identity .user-avatar"),
                initial=member_email[0].upper(),
                image_expected=False,
            )

            mark("create administrator")
            _create_user(
                page,
                email=second_admin_email,
                password=second_admin_password,
                admin=True,
            )
            admin_row = page.locator(".user-row").filter(has_text=second_admin_email)
            _require(
                "Administrator" in admin_row.inner_text(), "admin role was not applied"
            )

            mark("preserve email after duplicate validation")
            create_opener = page.get_by_role("button", name="Create user")
            create_opener.click()
            create_dialog = page.get_by_role("dialog", name="Create a user account")
            create_dialog.get_by_label("Email address").fill(member_email)
            create_dialog.get_by_label("Temporary password").fill(member_password)
            create_dialog.get_by_role("button", name="Create user", exact=True).click()
            create_dialog.get_by_text(
                "An account with that email already exists."
            ).wait_for(state="visible")
            _require(
                create_dialog.get_by_label("Email address").input_value()
                == member_email
                and create_dialog.get_by_label("Temporary password").input_value()
                == member_password,
                "a validation error discarded the user form",
            )
            unload_guarded = page.evaluate(
                """() => {
                  const event = new Event('beforeunload', { cancelable: true });
                  window.dispatchEvent(event);
                  return event.defaultPrevented;
                }"""
            )
            _require(
                unload_guarded, "an unsaved password did not guard document unload"
            )
            page.once("dialog", lambda dialog: dialog.accept())
            create_dialog.get_by_role("button", name="Cancel").click()
            _require(
                create_opener.evaluate(
                    "(element) => element === document.activeElement"
                ),
                "focus did not return to the create-user control",
            )

            mark("upload member avatar through account lifecycle")
            _sign_out(page)
            _sign_in_account(page, origin, member_email, member_password)
            _open_account(page)
            _upload_account_avatar(page)
            mark("create cross-owner resource with step image and attachment")
            (
                cross_owner_project_id,
                _,
                cross_owner_name,
                cross_owner_yarn_id,
                cross_owner_yarn_name,
            ) = _create_cross_owner_resource(page, origin, mark)
            mark("verify owner project and yarn cards")
            _assert_owner_resource_cards(
                page,
                origin,
                cross_owner_project_id,
                cross_owner_name,
                cross_owner_yarn_id,
                cross_owner_yarn_name,
                screenshot_dir,
            )
            _sign_out(page)
            _sign_in(page, origin, admin_email, admin_password)
            page.get_by_role("heading", name="User accounts").wait_for(state="visible")
            member_row = page.locator(".user-row").filter(has_text=member_email)
            _assert_user_avatar(
                member_row.locator(".user-identity .user-avatar"),
                initial=member_email[0].upper(),
                image_expected=True,
            )

            mark("disable and reset member")
            member_row = page.locator(".user-row").filter(has_text=member_email)
            disable_button = member_row.locator(".row-actions button").first
            disable_button.click()
            mark("open member disable confirmation")
            active_dialog = page.get_by_role("dialog", name="Disable this account?")
            _assert_user_avatar(
                active_dialog.locator(".target-identity .user-avatar"),
                initial=member_email[0].upper(),
                image_expected=True,
            )
            active_dialog.get_by_role("button", name="Disable account").click()
            mark("wait for member disabled status")
            expect(member_row.locator(".status-pill")).to_contain_text("Disabled")
            _require(
                disable_button.evaluate(
                    "(element) => element === document.activeElement"
                ),
                "focus did not return to the changed account action",
            )
            mark("open member password reset")
            member_row.get_by_role("button", name="Reset password").click()
            reset_dialog = page.get_by_role("dialog", name="Reset this password?")
            _assert_user_avatar(
                reset_dialog.locator(".target-identity .user-avatar"),
                initial=member_email[0].upper(),
                image_expected=True,
            )
            reset_dialog.get_by_label("New temporary password").fill(reset_password)
            reset_dialog.get_by_role(
                "button", name="Reset password", exact=True
            ).click()
            mark("wait for member password reset completion")
            reset_dialog.wait_for(state="hidden")
            mark("reset keeps the disabled member disabled")
            expect(member_row.locator(".status-pill")).to_contain_text("Disabled")
            mark("wait for member reset confirmation")
            page.get_by_role("status").filter(
                has_text="Password reset. The user must sign in again."
            ).wait_for(state="visible")
            mark("re-enable member")
            member_row.get_by_role("button", name="Enable", exact=True).click()
            page.get_by_role("dialog", name="Enable this account?").get_by_role(
                "button", name="Enable account"
            ).click()
            expect(member_row.locator(".status-pill")).to_contain_text("Active")

            mark("last active admin guard")
            own_row = page.locator(".user-row").filter(has_text=admin_email)
            own_disable_button = own_row.get_by_role("button", name="Disable")
            own_disable_button.click()
            own_dialog = page.get_by_role("dialog", name="Disable this account?")
            own_dialog.get_by_role("button", name="Disable account").click()
            own_dialog.get_by_text(
                "This change would leave the account system without an active "
                "administrator."
            ).wait_for(state="visible")
            own_dialog.get_by_role("button", name="Cancel").click()
            _require(
                own_disable_button.evaluate(
                    "(element) => element === document.activeElement"
                ),
                "focus did not return after the rejected self-disable attempt",
            )

            mark("audit list and filters")
            _create_admin_audit_project(page, origin, mark)
            avatar_response_statuses: list[int] = []

            def record_avatar_response(response: Response) -> None:
                if _is_admin_avatar_response(response, origin):
                    avatar_response_statuses.append(response.status)

            page.on("response", record_avatar_response)
            admin_image_response_statuses: list[int] = []

            def record_admin_image_response(response: Response) -> None:
                path = urlsplit(response.url).path
                if re.fullmatch(
                    r"/api/v2/admin/resources/project/\d+/media/image/\d+(?:/thumbnail)?",
                    path,
                ):
                    admin_image_response_statuses.append(response.status)

            page.on("response", record_admin_image_response)
            page.goto(f"{origin}/app/admin/audit", wait_until="networkidle")
            page.get_by_role("heading", name="Audit log").wait_for(state="visible")
            page.wait_for_function(
                "() => { const chevron = document.querySelector("
                "'.app-header .brand-chevron'); return chevron "
                "&& getComputedStyle(chevron).opacity === '0'; }",
                timeout=5_000,
            )
            top_level_brand = page.locator(".app-header .brand-lockup")
            _require(
                top_level_brand.count() == 1
                and top_level_brand.is_visible()
                and top_level_brand.get_attribute("href") == "/app/"
                and top_level_brand.get_attribute("aria-label") == "Stricknani"
                and page.locator(".app-header .brand-lockup.parent-link").count() == 0
                and top_level_brand.locator(".brand-chevron").evaluate(
                    "element => getComputedStyle(element).opacity === '0'"
                ),
                "top-level Audit brand showed a parent link or visible chevron",
            )
            page.locator(".event-row").first.wait_for(state="visible")
            mark("check cross-owner audit resource card and protected image")
            audit_resource_row = (
                page.locator(".event-row").filter(has_text=cross_owner_name).first
            )
            audit_resource_card = audit_resource_row.locator(".resource-card")
            audit_resource_card.wait_for(state="visible")
            audit_resource_href = (
                audit_resource_card.locator("a.resource-card__link").get_attribute(
                    "href"
                )
                or ""
            )
            _require(
                urlsplit(audit_resource_href).path
                == f"/app/admin/resources/project/{cross_owner_project_id}",
                "audit resource card did not use its authorized admin route",
            )
            audit_project_image = _assert_decoded_card_image(
                audit_resource_card,
                message="cross-owner audit project thumbnail did not decode",
            )
            _require(
                re.fullmatch(
                    rf"/api/v2/admin/resources/project/{cross_owner_project_id}/media/image/\d+/thumbnail",
                    urlsplit(audit_project_image).path,
                )
                is not None,
                "cross-owner audit project card used a non-admin image route",
            )
            audit_yarn_row = (
                page.locator(".event-row").filter(has_text=cross_owner_yarn_name).first
            )
            audit_yarn_card = audit_yarn_row.locator(".resource-card")
            audit_yarn_card.wait_for(state="visible")
            audit_yarn_href = (
                audit_yarn_card.locator("a.resource-card__link").get_attribute("href")
                or ""
            )
            _require(
                urlsplit(audit_yarn_href).path
                == f"/app/admin/resources/yarn/{cross_owner_yarn_id}",
                "audit yarn card did not use its authorized admin route",
            )
            audit_yarn_image = _assert_decoded_card_image(
                audit_yarn_card,
                message="cross-owner audit yarn thumbnail did not decode",
            )
            _require(
                re.fullmatch(
                    rf"/api/v2/admin/resources/yarn/{cross_owner_yarn_id}/media/photo/\d+/thumbnail",
                    urlsplit(audit_yarn_image).path,
                )
                is not None,
                "cross-owner audit yarn card used a non-admin photo route",
            )
            for width, suffix in ((390, "390"), (1280, "desktop")):
                page.set_viewport_size({"width": width, "height": 900})
                _assert_no_horizontal_overflow(page, "audit")
                _save_screenshot(page, screenshot_dir, f"admin-audit-{suffix}.png")
            broken_preview_url = f"{origin}{urlsplit(audit_project_image).path}"

            def abort_card_preview(route: Route) -> None:
                if route.request.url == broken_preview_url:
                    route.abort("failed")
                else:
                    route.continue_()

            page.route(
                "**/api/v2/admin/resources/project/*/media/image/*/thumbnail",
                abort_card_preview,
            )
            page.reload(wait_until="networkidle")
            broken_card = (
                page.locator(".event-row")
                .filter(has_text=cross_owner_name)
                .first.locator(".resource-card")
            )
            broken_card.locator(".resource-card__image svg").wait_for(state="visible")
            _require(
                broken_card.locator(".resource-card__image img").count() == 0,
                "failed audit preview remained as a broken image element",
            )
            page.unroute(
                "**/api/v2/admin/resources/project/*/media/image/*/thumbnail",
                abort_card_preview,
            )
            page.reload(wait_until="networkidle")
            page.locator(".event-row").filter(has_text=cross_owner_name).first.locator(
                "a.resource-card__link"
            ).click()
            page.get_by_role("heading", name=cross_owner_name, exact=True).wait_for(
                state="visible"
            )
            page.goto(f"{origin}/app/admin/audit", wait_until="networkidle")
            mark("read-only cross-owner resource and lazy audit diff")
            audit_detail_requests: list[str] = []
            owner_resource_requests: list[str] = []

            def record_audit_detail_request(request: object) -> None:
                request_url = urlsplit(getattr(request, "url", ""))
                if re.fullmatch(r"/api/v2/admin/audit/\d+", request_url.path):
                    audit_detail_requests.append(request_url.path)
                if re.fullmatch(r"/api/v2/(?:projects|yarns)/\d+", request_url.path):
                    owner_resource_requests.append(
                        f"{getattr(request, 'method', '')} {request_url.path}"
                    )

            page.on("request", record_audit_detail_request)
            mark("open cross-owner resource route")
            page.set_viewport_size({"width": 390, "height": 900})
            detail_api_statuses: list[int] = []
            detail_api_error_codes: list[str] = []
            page_errors: list[None] = []

            def record_resource_detail_response(response: Response) -> None:
                expected_path = (
                    f"/api/v2/admin/resources/project/{cross_owner_project_id}"
                )
                if urlsplit(response.url).path != expected_path:
                    return
                detail_api_statuses.append(response.status)
                if response.status == 200:
                    return
                try:
                    response_body: object = response.json()
                except Exception:
                    response_body = None
                if isinstance(response_body, dict):
                    error = response_body.get("error")
                    if isinstance(error, dict):
                        code = error.get("code")
                        if isinstance(code, str) and code in {
                            "admin_required",
                            "not_found",
                            "internal_error",
                        }:
                            detail_api_error_codes.append(code)

            page.on("response", record_resource_detail_response)
            page.on("pageerror", lambda _error: page_errors.append(None))
            expected_resource_path = (
                f"/app/admin/resources/project/{cross_owner_project_id}"
            )
            try:
                detail_page_response = page.goto(
                    f"{origin}{expected_resource_path}",
                    wait_until="commit",
                    timeout=10_000,
                )
            except PlaywrightTimeoutError:
                detail_page_response = None
            page.locator(".reader.page-container--standard").wait_for(
                state="visible", timeout=10_000
            )
            resource_heading_visible = page.get_by_role(
                "heading", name=cross_owner_name, exact=True
            ).is_visible()
            if not resource_heading_visible:
                diagnostic_screenshot = "admin-resource-route-failure.png"
                _save_screenshot(page, screenshot_dir, diagnostic_screenshot)
                page_status = (
                    detail_page_response.status if detail_page_response else "none"
                )
                api_status = detail_api_statuses[-1] if detail_api_statuses else "none"
                api_code = (
                    detail_api_error_codes[-1] if detail_api_error_codes else "none"
                )
                content_visible = page.locator(".reader").is_visible()
                error_visible = page.locator(".reader-state [role=alert]").is_visible()
                loading_visible = page.locator(
                    ".reader-loading[aria-live=polite]"
                ).is_visible()
                shell_visible = page.locator(".app-shell").is_visible()
                auth_form_visible = page.locator(".auth-form").is_visible()
                route_matches = urlsplit(page.url).path == expected_resource_path
                screenshot_state = "saved" if screenshot_dir else "disabled"
                diagnostic = (
                    "direct admin resource view did not render the resource "
                    f"(page_status={page_status}, api_status={api_status}, "
                    f"api_code={api_code}, content={content_visible}, "
                    f"error={error_visible}, loading={loading_visible}, "
                    f"app_shell={shell_visible}, auth_form={auth_form_visible}, "
                    f"route_match={route_matches}, page_errors={len(page_errors)}, "
                    f"screenshot={screenshot_state})"
                )
                _require(
                    False,
                    diagnostic,
                )
            resource_parent = page.locator(".app-header .brand-lockup.parent-link")
            _require(
                resource_parent.count() == 1
                and resource_parent.get_attribute("href") == "/app/admin/audit"
                and resource_parent.get_attribute("aria-label") == "Back",
                "a direct admin resource entry did not point its header parent "
                "to Audit",
            )
            for width in (390, 1280):
                page.set_viewport_size({"width": width, "height": 900})
                parent_bounds = resource_parent.bounding_box()
                _require(
                    parent_bounds is not None
                    and parent_bounds["width"] >= 44
                    and parent_bounds["height"] >= 44,
                    "the admin resource parent link target is smaller than 44px",
                )
                if width == 390:
                    _assert_mobile_header_alignment(page, "/app/admin/audit")
                _require(
                    page.locator(".reader .back-link").count() == 0,
                    "admin project reader rendered a duplicate body Back link",
                )
            page.set_viewport_size({"width": 390, "height": 900})
            mark("return from direct resource to audit")
            resource_parent.click()
            mark("confirm explicit resource link to audit")
            page.wait_for_url("**/app/admin/audit")
            mark("reopen cross-owner resource after history fallbacks")
            page.goto(
                f"{origin}/app/admin/resources/project/{cross_owner_project_id}",
                wait_until="domcontentloaded",
            )
            page.get_by_role("heading", name=cross_owner_name, exact=True).wait_for(
                state="visible"
            )
            mark("check resource view is read-only and does not fetch history")
            _require(
                audit_detail_requests == [],
                "resource view eagerly fetched audit history",
            )
            _require(
                owner_resource_requests == [],
                "admin project reader fetched owner-scoped project or yarn data",
            )
            _require(
                page.locator(
                    ".reader form, .reader input, .reader textarea, .reader select"
                ).count()
                == 0,
                "admin project reader exposed editable form fields",
            )
            _require(
                page.locator(".reader-admin-owner")
                .get_by_text(member_email, exact=True)
                .count()
                == 1,
                "read-only resource view did not identify its owner",
            )
            _assert_user_avatar(
                page.locator(".reader-admin-owner .user-avatar"),
                initial=member_email[0].upper(),
                image_expected=True,
            )
            materials = page.locator("details.materials-card")
            materials.wait_for(state="visible")
            if materials.get_attribute("open") is None:
                materials.locator("summary").click()
            related_yarn_card = materials.locator(".resource-card").filter(
                has_text=cross_owner_yarn_name
            )
            related_yarn_card.wait_for(state="visible")
            related_yarn_href = (
                related_yarn_card.locator("a.resource-card__link").get_attribute("href")
                or ""
            )
            related_yarn_image = _assert_decoded_card_image(
                related_yarn_card,
                message="admin related-yarn thumbnail did not decode",
            )
            _require(
                urlsplit(related_yarn_href).path
                == f"/app/admin/resources/yarn/{cross_owner_yarn_id}"
                and re.fullmatch(
                    rf"/api/v2/admin/resources/yarn/{cross_owner_yarn_id}/media/photo/\d+/thumbnail",
                    urlsplit(related_yarn_image).path,
                )
                is not None,
                "admin related-yarn card did not use scoped resource routes",
            )
            _require(
                page.locator(".reader-heading-actions .favorite-button").count() == 0
                and page.locator(".reader-edit-link").count() == 0
                and page.locator(".archive-control").count() == 0
                and page.locator(".ocr-control").count() == 0,
                "admin project reader exposed owner-only mutation controls",
            )
            utility_overflow = page.locator(
                ".reader-heading-actions details.action-overflow"
            )
            _require(
                utility_overflow.count() == 1
                and utility_overflow.get_attribute("open") is None
                and utility_overflow.locator(".print-button").count() == 1
                and utility_overflow.locator(".text-size-controls button").count() == 2,
                "admin project reader did not keep print and text-size utilities "
                "in the collapsed overflow",
            )
            for width in (320, 390, 1280):
                page.set_viewport_size({"width": width, "height": 900})
                reader = page.locator(".reader.page-container--standard")
                reader_bounds = reader.bounding_box()
                _require(
                    reader_bounds is not None
                    and reader_bounds["x"] >= 0
                    and reader_bounds["x"] + reader_bounds["width"] <= width + 1,
                    f"admin project reader exceeded the viewport at {width}px",
                )
                _assert_no_horizontal_overflow(page, "project-reader")
            mark("load cross-owner step image")
            step_media = page.locator(".step-photo-card").filter(
                has=page.locator('img[alt="Nested fixture step image"]')
            )
            step_image = step_media.locator("img.screen-reference-image")
            image_count = step_image.count()
            image_visible = image_count == 1 and step_image.is_visible()
            image_url_is_admin = image_count == 1 and bool(
                re.fullmatch(
                    r"/api/v2/admin/resources/project/\d+/media/image/\d+/thumbnail",
                    urlsplit(step_image.get_attribute("src") or "").path,
                )
            )
            image_decoded = image_count == 1 and bool(
                step_image.evaluate(
                    "node => Promise.race(["
                    "node.decode().then(() => node.complete && node.naturalWidth > 0)"
                    ", new Promise(resolve => setTimeout(() => resolve(false), 3000))"
                    "]).catch(() => false)"
                )
            )
            _require(
                image_count == 1
                and image_visible
                and image_url_is_admin
                and image_decoded,
                "cross-owner step image check failed "
                f"(count={image_count}, visible={image_visible}, "
                f"admin_route={image_url_is_admin}, decoded={image_decoded}, "
                f"statuses={admin_image_response_statuses})",
            )
            step = page.locator(".steps-list > li").filter(has=step_media)
            _require(
                step.count() == 1
                and step.locator(":scope > .step-number").inner_text().strip() == "1",
                "read-only resource view did not identify the image's step",
            )
            mark("download cross-owner attachment")
            # The link's accessible name also carries its type and size.
            attachment_link = page.locator(".attachments-card a").filter(
                has=page.locator("strong", has_text="synthetic-pattern.txt")
            )
            _require(
                attachment_link.count() == 1,
                "admin project reader did not list the synthetic attachment once",
            )
            attachment_link.wait_for(state="visible")
            attachment_url = attachment_link.get_attribute("href") or ""
            attachment_response = page.request.get(f"{origin}{attachment_url}")
            _require(
                attachment_url.startswith("/api/v2/admin/resources/project/")
                and attachment_response.status == 200
                and "attachment;"
                in (attachment_response.headers.get("content-disposition") or ""),
                "cross-owner attachment did not use the admin media download route",
            )
            mark("open audit list for lazy diff")
            page.goto(f"{origin}/app/admin/audit", wait_until="networkidle")
            cross_owner_row = (
                page.locator(".event-row").filter(has_text=cross_owner_name).first
            )
            cross_owner_row.wait_for(state="visible")
            show_diff = cross_owner_row.get_by_role("link", name="Show diff")
            show_diff.wait_for(state="visible")
            mark("open audit diff")
            show_diff.click()
            page.get_by_role("heading", name=cross_owner_name, exact=True).wait_for(
                state="visible"
            )
            name_change = page.locator(".change-list li").filter(
                has=page.get_by_role("heading", name="Project name", exact=True)
            )
            _require(
                bool(audit_detail_requests)
                and name_change.count() == 1
                and name_change.locator(".change-values .value").last.inner_text()
                == cross_owner_name
                and page.locator(".change-values img").count() == 0,
                "lazy audit diff did not render escaped, human-readable changes",
            )
            mark("check audit diff request count and responsive layout")
            _require(
                len(audit_detail_requests) == 1,
                "audit diff detail request was not lazy or was fetched more than once",
            )
            for width, suffix in ((390, "390"), (1280, "desktop")):
                page.set_viewport_size({"width": width, "height": 900})
                _require(
                    page.locator(".diff-back-link").count() == 0,
                    "audit diff rendered a duplicate body Back link",
                )
                _assert_no_horizontal_overflow(page, "diff")
                _save_screenshot(
                    page,
                    screenshot_dir,
                    f"admin-audit-detail-with-diff-{suffix}.png",
                )
            no_snapshot_event_id = _find_existing_no_snapshot_event(page, origin)
            mark("open existing audit event without a change snapshot")
            no_snapshot_api: dict[str, object] = {}

            def record_no_snapshot_detail(response: Response) -> None:
                if urlsplit(response.url).path != (
                    f"/api/v2/admin/audit/{no_snapshot_event_id}"
                ):
                    return
                no_snapshot_api["status"] = response.status
                if response.status != 200:
                    return
                try:
                    payload: object = response.json()
                except Exception:
                    no_snapshot_api["reason"] = "invalid_response"
                    return
                data = payload.get("data") if isinstance(payload, dict) else None
                no_snapshot_api["reason"] = (
                    data.get("unavailable_reason")
                    if isinstance(data, dict)
                    else "invalid_response"
                )
                no_snapshot_api["changes_missing"] = (
                    isinstance(data, dict) and data.get("changes") is None
                )

            page.on("response", record_no_snapshot_detail)
            try:
                no_snapshot_page_response = page.goto(
                    f"{origin}/app/admin/audit/{no_snapshot_event_id}",
                    wait_until="domcontentloaded",
                    timeout=10_000,
                )
            except PlaywrightTimeoutError:
                no_snapshot_page_response = None
            no_snapshot_notice = page.locator(".diff-page > p.notice-panel")
            try:
                no_snapshot_notice.wait_for(state="visible", timeout=10_000)
            except PlaywrightTimeoutError:
                pass
            no_snapshot_visible = no_snapshot_notice.is_visible()
            no_snapshot_loading = page.locator(
                ".diff-page [aria-live=polite]"
            ).is_visible()
            no_snapshot_error = page.locator(".diff-page [role=alert]").is_visible()
            no_snapshot_route_matches = (
                urlsplit(page.url).path == f"/app/admin/audit/{no_snapshot_event_id}"
            )
            if not (
                no_snapshot_visible
                and not no_snapshot_loading
                and not no_snapshot_error
                and no_snapshot_api.get("status") == 200
                and no_snapshot_api.get("reason") == "not_recorded"
                and no_snapshot_api.get("changes_missing") is True
            ):
                diagnostic_screenshot = "admin-audit-no-snapshot-failure.png"
                _save_screenshot(page, screenshot_dir, diagnostic_screenshot)
                no_snapshot_page_status = (
                    no_snapshot_page_response.status
                    if no_snapshot_page_response
                    else "none"
                )
                _require(
                    False,
                    "no-snapshot detail did not render its unavailable notice "
                    f"(route={no_snapshot_route_matches}, "
                    f"page_status={no_snapshot_page_status}, "
                    f"api_status={no_snapshot_api.get('status', 'none')}, "
                    f"api_reason={no_snapshot_api.get('reason', 'none')}, "
                    f"changes_missing={no_snapshot_api.get('changes_missing', False)}, "
                    f"notice={no_snapshot_visible}, loading={no_snapshot_loading}, "
                    f"error={no_snapshot_error}, "
                    f"screenshot={'saved' if screenshot_dir else 'disabled'})",
                )
            page.remove_listener("response", record_no_snapshot_detail)
            audit_parent = page.locator(".app-header .brand-lockup.parent-link")
            _require(
                audit_parent.count() == 1
                and audit_parent.get_attribute("href") == "/app/admin/audit"
                and audit_parent.get_attribute("aria-label") == "Back",
                "an audit detail entry did not point its header parent to Audit",
            )
            for width, suffix in ((390, "390"), (1280, "desktop")):
                page.set_viewport_size({"width": width, "height": 900})
                audit_parent_bounds = audit_parent.bounding_box()
                _require(
                    audit_parent_bounds is not None
                    and audit_parent_bounds["width"] >= 44
                    and audit_parent_bounds["height"] >= 44,
                    "the audit detail parent link target is smaller than 44px",
                )
                if width == 390:
                    _assert_mobile_header_alignment(page, "/app/admin/audit")
                _require(
                    page.locator(".diff-back-link").count() == 0,
                    "no-snapshot audit diff rendered a duplicate body Back link",
                )
                _assert_no_horizontal_overflow(page, "diff")
                _save_screenshot(
                    page,
                    screenshot_dir,
                    f"admin-audit-detail-no-snapshot-{suffix}.png",
                )
            mark("click no-snapshot return-to-audit link")
            audit_parent.click()
            mark("confirm no-snapshot return to audit")
            page.wait_for_url("**/app/admin/audit")
            for width in (320, 390, 768, 1280):
                page.set_viewport_size({"width": width, "height": 900})
                _assert_no_horizontal_overflow(page, "diff")
            page.set_viewport_size({"width": 390, "height": 900})
            mark("open More before audit resource history journey")
            page.goto(f"{origin}/app/more", wait_until="networkidle")
            page.get_by_role("heading", name="More", exact=True).wait_for(
                state="visible"
            )
            mark("open Audit from More")
            page.locator(".more-links").get_by_role(
                "link", name="Audit log", exact=False
            ).click()
            page.wait_for_url("**/app/admin/audit")
            page.get_by_role("heading", name="Audit log", exact=True).wait_for(
                state="visible"
            )
            page.get_by_label("Entity").select_option("project")
            page.get_by_label("Action").fill("updated")
            page.get_by_role("button", name="Filter").click()
            page.wait_for_url("**/app/admin/audit?entity=project&action=updated")
            history_resource_row = (
                page.locator(".event-row").filter(has_text=cross_owner_name).first
            )
            history_resource_row.wait_for(state="visible")
            history_resource_row.scroll_into_view_if_needed()
            audit_scroll_before_resource = page.evaluate("() => window.scrollY")
            history_resource_row.locator("a.resource-card__link").click()
            page.wait_for_url(
                f"**/app/admin/resources/project/{cross_owner_project_id}"
            )
            mark("return from resource to filtered audit with native browser history")
            page.go_back(wait_until="domcontentloaded")
            page.wait_for_url("**/app/admin/audit?entity=project&action=updated")
            history_resource_row.wait_for(state="visible")
            page.wait_for_function(
                "target => Math.abs(window.scrollY - target) <= 2",
                arg=audit_scroll_before_resource,
                timeout=5_000,
            )
            restored_entity = page.get_by_label("Entity").input_value() == "project"
            restored_action = page.get_by_label("Action").input_value() == "updated"
            audit_scroll_after_back = page.evaluate("() => window.scrollY")
            scroll_restored = (
                abs(audit_scroll_after_back - audit_scroll_before_resource) <= 2
            )
            _require(
                restored_entity and restored_action and scroll_restored,
                "browser Back did not restore filtered audit state and scroll "
                f"(entity={restored_entity}, action={restored_action}, "
                f"scroll_restored={scroll_restored})",
            )
            mark("return from audit to More with native browser history")
            page.go_back(wait_until="domcontentloaded")
            page.wait_for_url("**/app/more")
            page.get_by_role("heading", name="More", exact=True).wait_for(
                state="visible"
            )
            page.goto(
                f"{origin}/app/admin/resources/project/{cross_owner_project_id}",
                wait_until="networkidle",
            )
            mark("check resource responsive layout")
            for width in (320, 390, 768, 1280):
                page.set_viewport_size({"width": width, "height": 900})
                _assert_no_horizontal_overflow(page, "resource")
            page.goto(f"{origin}/app/admin/audit", wait_until="networkidle")
            page.remove_listener("request", record_audit_detail_request)
            mark("check audit actor avatar and known action icons")
            admin_actor_row = (
                page.locator(".event-row")
                .filter(has=page.get_by_text(admin_email, exact=True))
                .first
            )
            actor_row_count = admin_actor_row.count()
            mark(f"wait for admin actor row (found={actor_row_count > 0})")
            admin_actor_row.wait_for(state="visible")
            actor_avatar = admin_actor_row.locator(".actor-identity .user-avatar")
            actor_image = actor_avatar.locator("img[alt='']")
            actor_image_count = actor_image.count()
            actor_image_has_src = bool(actor_image.get_attribute("src"))
            latest_avatar_status = (
                avatar_response_statuses[-1] if avatar_response_statuses else "none"
            )
            mark(
                "wait for admin actor image "
                f"(element={actor_image_count > 0}; src={actor_image_has_src}; "
                f"http={latest_avatar_status})"
            )
            _assert_user_avatar(
                actor_avatar,
                initial=admin_email[0].upper(),
                image_expected=True,
            )
            mark("inspect known audit action icons")
            visible_actions = page.locator(
                ".audit-action-badge > span"
            ).all_inner_texts()
            known_action_shapes: dict[str, str] = {}
            for label in ("Created", "Updated", "Deleted", "Uploaded", "Removed"):
                if label not in visible_actions:
                    continue
                mark(f"inspect {label} audit action icon")
                event = (
                    page.locator(".event-row")
                    .filter(has=page.get_by_text(label, exact=True))
                    .first
                )
                icon = event.locator(
                    ".audit-action-badge svg[aria-hidden='true'][focusable='false']"
                )
                _require(
                    icon.count() == 1,
                    "a known audit action did not render one decorative SVG",
                )
                known_action_shapes[label] = _svg_shape_signature(icon)
            _require(
                "Created" in visible_actions
                and "Updated" in visible_actions
                and bool(known_action_shapes.get("Created"))
                and bool(known_action_shapes.get("Updated"))
                and known_action_shapes["Created"] != known_action_shapes["Updated"],
                "create and update audit actions did not render distinct shapes",
            )
            filtered_audit_response: dict[str, object] = {}

            def record_filtered_audit_response(response: Response) -> None:
                parsed = urlsplit(response.url)
                if (
                    parsed.path != "/api/v2/admin/audit"
                    or parsed.query != "entity=project&action=created"
                ):
                    return
                filtered_audit_response["status"] = response.status
                if response.status != 200:
                    return
                body = response.json()
                data = body.get("data") if isinstance(body, dict) else None
                filtered_audit_response["events"] = data

            page.on("response", record_filtered_audit_response)
            mark("filter audit events by entity")
            page.get_by_label("Entity").select_option("project")
            # Choosing an entity filters at once and reloads the list, which
            # resets the action draft; let that settle before typing one.
            page.wait_for_url("**/app/admin/audit?entity=project")
            page.wait_for_load_state("networkidle")
            page.locator(".event-row").first.wait_for(state="visible")
            mark("filter audit events by action")
            page.get_by_label("Action").fill("created")
            page.get_by_role("button", name="Filter").click()
            mark("wait for the filtered audit URL")
            page.wait_for_url("**/app/admin/audit?entity=project&action=created")
            mark("wait for filtered audit rows")
            page.locator(".event-row").first.wait_for(state="visible")
            mark("read filtered audit rows")
            api_events_value = filtered_audit_response.get("events")
            api_events = api_events_value if isinstance(api_events_value, list) else []
            rows = page.locator(".event-row").all()
            expected_rows: list[tuple[str, str]] = []
            for event in api_events:
                if not isinstance(event, dict):
                    continue
                entity_type = event.get("entity_type")
                action = event.get("action")
                entity_id = event.get("entity_id")
                resource = event.get("resource")
                if (
                    entity_type != "project"
                    or action != "created"
                    or not isinstance(entity_id, int)
                    or not isinstance(resource, dict)
                    or resource.get("kind") != "project"
                ):
                    continue
                resource_name = resource.get("name")
                if resource_name is not None and not isinstance(resource_name, str):
                    continue
                expected_rows.append(
                    (
                        "Created",
                        resource_name or f"project #{entity_id}",
                    )
                )
            rendered_rows = [
                (
                    row.locator(".audit-action-badge > span").inner_text().strip(),
                    row.locator(".resource-card__name").inner_text().strip(),
                )
                for row in rows
            ]
            filters_match_api = (
                filtered_audit_response.get("status") == 200
                and isinstance(api_events_value, list)
                and len(expected_rows) == len(api_events)
            )
            rendered_matches_api = rendered_rows == expected_rows
            _require(
                bool(filters_match_api and rendered_matches_api),
                "audit filters returned an event outside the selected entity/action",
            )
            page.remove_listener("response", record_filtered_audit_response)
            audit_response: dict[str, object] = {}

            def check_audit_response(response: Response) -> None:
                if urlsplit(response.url).path != "/api/v2/admin/audit":
                    return
                if response.status != 200:
                    audit_response["status"] = response.status
                    return
                body = response.json()
                data = body.get("data") if isinstance(body, dict) else None
                audit_response["status"] = response.status
                audit_response["safe"] = isinstance(data, list) and all(
                    isinstance(item, dict) and "details" not in item for item in data
                )

            page.on("response", check_audit_response)
            mark("clamp an out-of-range audit page")
            await_page = page
            await_page.goto(
                f"{origin}/app/admin/audit?page=9999&entity=project&action=created",
                wait_until="networkidle",
            )
            await_page.get_by_role("heading", name="Audit log").wait_for(
                state="visible"
            )
            # Page controls only render when there is more than one page, so
            # a clamped, short filtered list shows its rows and no pager.
            await_page.locator(".event-row").first.wait_for(state="visible")
            await_page.wait_for_function(
                "() => !new URLSearchParams(location.search).has('page')"
            )
            _require(
                "page=" not in urlsplit(await_page.url).query,
                "out-of-range audit page did not clamp to the filtered last page",
            )
            _require(
                audit_response.get("status") == 200
                and audit_response.get("safe") is True,
                "audit API did not return its safe DTO projection",
            )

            mark("check generic icon for an unknown audit action")
            injected_unknown_action = False

            def inject_unknown_action(route: Route) -> None:
                nonlocal injected_unknown_action
                response = route.fetch()
                payload = response.json()
                data = payload.get("data") if isinstance(payload, dict) else None
                if not injected_unknown_action and isinstance(data, list) and data:
                    template = next(
                        (
                            event
                            for event in data
                            if isinstance(event, dict)
                            and event.get("actor_email") == admin_email
                            and isinstance(event.get("actor_avatar_url"), str)
                            and event.get("actor_avatar_url")
                            and isinstance(event.get("id"), int)
                        ),
                        None,
                    )
                    if isinstance(template, dict):
                        unknown = dict(template)
                        unknown["id"] = (
                            max(
                                entry["id"]
                                for entry in data
                                if isinstance(entry, dict)
                                and isinstance(entry.get("id"), int)
                            )
                            + 1
                        )
                        unknown["action"] = "unrecognized_fixture_action"
                        unknown["has_changes"] = False
                        data.insert(0, unknown)
                        injected_unknown_action = True
                route.fulfill(response=response, json=payload)

            page.route("**/api/v2/admin/audit*", inject_unknown_action)
            page.goto(f"{origin}/app/admin/audit", wait_until="networkidle")
            unknown_row = page.locator(".event-row").filter(
                has=page.get_by_text("unrecognized_fixture_action", exact=True)
            )
            unknown_row.wait_for(state="visible")
            mark("measure audit action colors, contrast, and row geometry")
            _assert_audit_visual_measurements(page)
            unknown_action_label = unknown_row.locator(".audit-action-badge > span")
            unknown_badge = unknown_row.locator(".audit-action-badge")
            unknown_icon = unknown_row.locator(
                ".audit-action-badge svg[aria-hidden='true'][focusable='false']"
            )
            _require(
                injected_unknown_action
                and unknown_action_label.inner_text().strip()
                == "unrecognized_fixture_action"
                and "action-badge--unknown"
                in (unknown_badge.get_attribute("class") or "")
                and not any(
                    tone in (unknown_badge.get_attribute("class") or "")
                    for tone in (
                        "action-badge--create",
                        "action-badge--update",
                        "action-badge--upload",
                        "action-badge--delete",
                    )
                )
                and unknown_icon.count() == 1
                and bool(_svg_shape_signature(unknown_icon)),
                "an unknown audit action lost its raw label or generic SVG",
            )
            _assert_user_avatar(
                unknown_row.locator(".actor-identity .user-avatar"),
                initial=admin_email[0].upper(),
                image_expected=True,
            )
            page.unroute("**/api/v2/admin/audit*", inject_unknown_action)

            mark("check broken member avatar initial fallback")
            page.goto(f"{origin}/app/admin/users", wait_until="networkidle")
            member_row = page.locator(".user-row").filter(has_text=member_email)
            member_avatar = member_row.locator(".user-identity .user-avatar")
            member_image = member_avatar.locator("img[alt='']")
            member_image.wait_for(state="visible")
            _assert_user_avatar(
                member_avatar,
                initial=member_email[0].upper(),
                image_expected=True,
            )
            avatar_source = member_image.evaluate("image => image.currentSrc")
            avatar_parts = urlsplit(avatar_source)
            _require(
                f"{avatar_parts.scheme}://{avatar_parts.netloc}" == origin,
                "member avatar did not use the loopback fixture media URL",
            )

            def block_fixture_avatar(route: Route) -> None:
                route.abort("failed")

            page.route(avatar_source, block_fixture_avatar)
            page.reload(wait_until="networkidle")
            member_avatar = (
                page.locator(".user-row")
                .filter(has_text=member_email)
                .locator(".user-identity .user-avatar")
            )
            _assert_user_avatar(
                member_avatar,
                initial=member_email[0].upper(),
                image_expected=False,
            )
            page.unroute(avatar_source, block_fixture_avatar)

            mark("member is denied admin API access")
            mark("locate administrator sign-out control")
            mark("click administrator sign-out")
            _sign_out(page)
            mark("fill member sign-in")
            page.locator("#email").fill(member_email)
            page.locator("#password").fill(reset_password)
            page.get_by_role("button", name="Sign in").click()
            mark("wait for member sign-in")
            page.get_by_role("heading", name="Projects").wait_for(state="visible")
            mark("ordinary user is denied cross-owner resource view")
            page.goto(
                f"{origin}/app/admin/resources/project/{cross_owner_project_id}",
                wait_until="networkidle",
            )
            page.get_by_role(
                "heading", name="You do not have access to this page."
            ).wait_for(state="visible")
            resource_forbidden_status = page.evaluate(
                "async projectId => (await fetch("
                "`/api/v2/admin/resources/project/${projectId}`)).status",
                cross_owner_project_id,
            )
            _require(
                resource_forbidden_status == 403,
                "a member session was not denied by the admin resource API",
            )
            mark("owner deletes synthetic resource through authenticated v2 API")
            _delete_cross_owner_resource(page, origin, cross_owner_project_id)

            _sign_out(page)
            mark("wait for member sign-out")
            mark("fill administrator sign-in")
            page.locator("#email").fill(admin_email)
            page.locator("#password").fill(admin_password)
            page.get_by_role("button", name="Sign in").click()
            mark("wait for administrator sign-in")
            page.get_by_role("heading", name="Projects").wait_for(state="visible")
            mark("open administrator user route")
            page.goto(f"{origin}/app/admin/users", wait_until="networkidle")
            own_row = page.locator(".user-row").filter(has_text=admin_email)
            own_row.get_by_role("button", name="Reset password").click()
            own_reset_dialog = page.get_by_role("dialog", name="Reset this password?")
            own_reset_dialog.get_by_label("New temporary password").fill(admin_password)
            own_reset_dialog.get_by_role(
                "button", name="Reset password", exact=True
            ).click()
            page.locator("#email").wait_for(state="visible")
            _require(
                page.locator("#password").input_value() == "",
                "self password reset retained a sign-in password field",
            )
            page.locator("#email").fill(admin_email)
            page.locator("#password").fill(admin_password)
            page.get_by_role("button", name="Sign in").click()
            page.get_by_role("heading", name="Projects").wait_for(state="visible")

            mark("deleted resource remains linked to its event detail")
            page.goto(f"{origin}/app/admin/audit", wait_until="networkidle")
            deleted_event_row = (
                page.locator(".event-row")
                .filter(has_text="Deleted")
                .filter(has_text=cross_owner_name)
            )
            deleted_resource_link = deleted_event_row.locator("a.resource-card__link")
            deleted_resource_link.wait_for(state="visible")
            deleted_event_href = deleted_resource_link.get_attribute("href") or ""
            deleted_event_match = re.fullmatch(
                r"/app/admin/audit/(\d+)", deleted_event_href
            )
            if deleted_event_match is None:
                raise AcceptanceFailure(
                    "deleted resource link did not use the event detail route"
                )
            deleted_event_api_path = (
                f"/api/v2/admin/audit/{deleted_event_match.group(1)}"
            )
            deleted_event_response: dict[str, object] = {}

            def record_deleted_event(response: Response) -> None:
                if urlsplit(response.url).path != deleted_event_api_path:
                    return
                if response.status != 200:
                    deleted_event_response["status"] = response.status
                    return
                payload = response.json()
                data = payload.get("data") if isinstance(payload, dict) else None
                event = data.get("event") if isinstance(data, dict) else None
                resource = event.get("resource") if isinstance(event, dict) else None
                deleted_event_response["exists"] = (
                    resource.get("exists") if isinstance(resource, dict) else None
                )
                deleted_event_response["name"] = (
                    resource.get("name") if isinstance(resource, dict) else None
                )
                deleted_event_response["status"] = response.status

            page.on("response", record_deleted_event)
            deleted_resource_link.click()
            page.get_by_role("heading", name=cross_owner_name, exact=True).wait_for(
                state="visible"
            )
            page.get_by_text("Resource unavailable", exact=True).wait_for(
                state="visible"
            )
            _require(
                deleted_event_response.get("status") == 200
                and deleted_event_response.get("exists") is False
                and deleted_event_response.get("name") == cross_owner_name,
                "deleted resource did not resolve to an honest event detail",
            )
            page.remove_listener("response", record_deleted_event)

            mark("responsive layout and sensitive data check")
            for route, heading in (
                ("users", "User accounts"),
                ("audit", "Audit log"),
            ):
                mark(f"measure admin {route} responsive layout")
                page.goto(f"{origin}/app/admin/{route}", wait_until="networkidle")
                page.get_by_role("heading", name=heading).wait_for(state="visible")
                for width in (320, 390, 768, 1280):
                    page.set_viewport_size({"width": width, "height": 900})
                    _assert_no_horizontal_overflow(page, route)
                if route == "audit" and width <= 390:
                    _assert_mobile_header_alignment(page)
            local_keys = page.evaluate("() => Object.keys(window.localStorage)")
            _require(
                not any(
                    any(word in key.casefold() for word in ("password", "admin-draft"))
                    for key in local_keys
                ),
                "administrator passwords or drafts were persisted in local storage",
            )
            mark("top-level direct audit entry has a reserved slot and no parent link")
            fresh_page = context.new_page()
            _guard_loopback(fresh_page, origin)
            fresh_page.goto("about:blank")
            fresh_page.evaluate(
                "target => location.replace(target)",
                f"{origin}/app/admin/audit",
            )
            fresh_page.wait_for_url("**/app/admin/audit")
            fresh_page.get_by_role("heading", name="Audit log", exact=True).wait_for(
                state="visible"
            )
            fresh_page.wait_for_function(
                "() => { const chevron = document.querySelector("
                "'.app-header .brand-chevron'); return chevron "
                "&& getComputedStyle(chevron).opacity === '0'; }",
                timeout=5_000,
            )
            fresh_top_level_brand = fresh_page.locator(".app-header .brand-lockup")
            _require(
                fresh_page.evaluate("() => history.length") == 1
                and fresh_top_level_brand.count() == 1
                and fresh_top_level_brand.get_attribute("href") == "/app/"
                and fresh_top_level_brand.get_attribute("aria-label") == "Stricknani"
                and fresh_page.locator(".app-header .brand-lockup.parent-link").count()
                == 0
                and fresh_top_level_brand.locator(".brand-chevron").evaluate(
                    "element => getComputedStyle(element).opacity === '0'"
                ),
                "top-level direct audit entry showed a parent link or chevron",
            )
            fresh_page.close()
            context.close()
        finally:
            browser.close()


def _create_fixture_admin(
    binary: Path,
    private_dir: Path,
    fixture_file: Path,
    state_file: Path,
    web_root: Path,
) -> tuple[str, str]:
    try:
        fixture = rust_fixture.load_fixture_environment(fixture_file)
        state_path = rust_fixture._outside_repository(state_file, "fixture state file")
        state = json.loads(state_path.read_text(encoding="utf-8"))
        runtime = rust_fixture._outside_repository(
            Path(state["runtime"]), "fixture runtime"
        )
        runtime_parent = rust_fixture._outside_repository(
            private_dir / "runtime", "fixture runtime parent"
        )
        if runtime.parent != runtime_parent.resolve():
            raise AcceptanceFailure(
                "fixture runtime is outside the private runner directory"
            )
        marker = runtime / rust_fixture.OWNER_FILE
        if marker.read_text(encoding="ascii") != state["owner"]:
            raise AcceptanceFailure("fixture runtime owner marker did not match")
        marker_stat = marker.stat()
        if marker_stat.st_mode & 0o077 or not marker.is_file():
            raise AcceptanceFailure("fixture runtime owner marker is not private")
        port = urlsplit(fixture["STRICKNANI_RUST_TEST_URL"]).port
        if port is None:
            raise AcceptanceFailure("fixture URL has no port")
        env = rust_fixture._server_environment(runtime, port, web_root)
    except (OSError, KeyError, TypeError, ValueError, json.JSONDecodeError) as error:
        raise AcceptanceFailure("fixture runtime could not be validated") from error

    email = f"admin-{secrets.token_hex(6)}@example.invalid"
    password = f"A synthetic admin password {secrets.token_urlsafe(18)}"
    rust_fixture._mask_github_secret(email)
    rust_fixture._mask_github_secret(password)
    rust_fixture.run_cmd(
        [str(binary.expanduser().resolve()), "create-admin", email],
        env=env,
        input_bytes=f"{password}\n".encode(),
    )
    return email, password


def run_live_fixture(
    binary: Path,
    web_root: Path,
    private_dir: Path,
    chromium_executable: Path | None = None,
    screenshot_dir: Path | None = None,
) -> None:
    fixture_file = private_dir / "fixture.json"
    state_file = private_dir / "fixture-state.json"
    token_file = private_dir / "browser-seed-token.json"
    started = False
    try:
        rust_fixture.start_fixture(
            binary=binary,
            temp_dir=private_dir / "runtime",
            fixture_file=fixture_file,
            state_file=state_file,
            web_root=web_root,
        )
        started = True
        try:
            seed_rust.seed_fixture(fixture_file, token_file)
        except seed_rust.SeedError as error:
            raise AcceptanceFailure(
                "synthetic audit events could not be seeded"
            ) from error
        finally:
            token_file.unlink(missing_ok=True)
        admin_email, admin_password = _create_fixture_admin(
            binary, private_dir, fixture_file, state_file, web_root
        )
        run_acceptance(
            fixture_file,
            admin_email,
            admin_password,
            chromium_executable,
            private_dir / "browser-stage.txt",
            screenshot_dir,
        )
    finally:
        if started:
            rust_fixture.stop_fixture(state_file)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--binary", type=Path, required=True)
    parser.add_argument("--web-root", type=Path, required=True)
    parser.add_argument("--private-dir", type=Path)
    parser.add_argument("--screenshots-dir", type=Path)
    parser.add_argument("--chromium-executable", type=Path)
    args = parser.parse_args(argv)
    private_dir: Path | None = None
    screenshot_dir: Path | None = None
    stage_file: Path | None = None
    try:
        private_dir = _private_work_dir(args.private_dir)
        stage_file = private_dir / "browser-stage.txt"
        if args.screenshots_dir is not None:
            screenshot_dir = rust_fixture._outside_repository(
                args.screenshots_dir, "screenshot directory"
            )
            screenshot_dir.mkdir(mode=0o700, parents=True, exist_ok=False)
            screenshot_dir.chmod(0o700)
        web_root = args.web_root.expanduser().resolve()
        if not web_root.is_dir():
            raise AcceptanceFailure("web root must be an existing directory")
        run_live_fixture(
            args.binary.expanduser().resolve(),
            web_root,
            private_dir,
            args.chromium_executable,
            screenshot_dir,
        )
    except rust_fixture.FixtureError as error:
        print(
            f"Admin browser acceptance could not start safely: {error}", file=sys.stderr
        )
        return 1
    except AcceptanceFailure as error:
        print(f"Admin browser acceptance failed: {error}", file=sys.stderr)
        return 1
    except Exception as error:
        stage = "administrator users and audit journey"
        if stage_file is not None and stage_file.is_file():
            stage = stage_file.read_text(encoding="ascii")
        print(
            f"Admin browser acceptance failed during {stage} "
            f"({type(error).__name__}); diagnostic details were suppressed.",
            file=sys.stderr,
        )
        return 1
    finally:
        if private_dir is not None:
            shutil.rmtree(private_dir, ignore_errors=True)
    print(
        "Admin browser acceptance passed: account management, access denial, "
        "and audit filters."
    )
    if screenshot_dir is not None:
        print(f"Screenshots: {screenshot_dir}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
