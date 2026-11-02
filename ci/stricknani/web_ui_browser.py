#!/usr/bin/env python3
"""Run sanitized browser acceptance checks against the disposable Rust fixture."""

from __future__ import annotations

import argparse
import ipaddress
import json
import os
import re
import secrets
import sys
import tempfile
import traceback
import zlib
from collections.abc import Callable
from pathlib import Path
from urllib.parse import parse_qs, urlsplit

from playwright.sync_api import Locator, Page, Request, Response, Route, sync_playwright
from playwright.sync_api import TimeoutError as PlaywrightTimeoutError

from ci.stricknani import rust_fixture, seed_rust


class AcceptanceFailure(RuntimeError):
    """A safe-to-display browser assertion failure."""


ACCOUNT_HEADING_NAME = re.compile(r"^(?:Account|Konto)$")
SIGN_OUT_BUTTON_NAME = re.compile(r"^(?:Sign out|Abmelden)$")
UPLOAD_PICTURE_BUTTON_NAME = re.compile(r"^(?:Upload picture|Bild hochladen)$")
REMOVE_PICTURE_BUTTON_NAME = re.compile(r"^(?:Remove picture|Bild entfernen)$")


def _playwright_error_category(error: Exception) -> str:
    message = str(error).lower()
    if "timeout" in message or "timed out" in message:
        return "timeout"
    if "closed" in message or "crashed" in message:
        return "closed-or-crashed"
    if "execution context" in message:
        return "execution-context"
    return "other"


def _require(condition: bool, message: str) -> None:
    if not condition:
        raise AcceptanceFailure(message)


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
    ):
        raise AcceptanceFailure("fixture URL must be plain HTTP on loopback")
    if port is None or not 1 <= port <= 65535:
        raise AcceptanceFailure("fixture URL has no valid port")
    return f"http://127.0.0.1:{port}"


def _private_screenshot_dir(path: Path | None) -> Path:
    if path is None:
        target = Path(tempfile.mkdtemp(prefix="stricknani-web-ui-"))
    else:
        target = rust_fixture._outside_repository(path, "screenshot directory")
        target.mkdir(mode=0o700, parents=True, exist_ok=False)
    target = rust_fixture._outside_repository(target, "screenshot directory")
    if not target.is_dir():
        raise AcceptanceFailure("screenshot path must be a directory")
    target.chmod(0o700)
    return target


def _private_work_dir(path: Path | None) -> Path:
    if path is None:
        target = Path(tempfile.mkdtemp(prefix="stricknani-web-ui-"))
    else:
        target = rust_fixture._outside_repository(path, "private work directory")
        target.mkdir(mode=0o700, parents=True, exist_ok=False)
    target = rust_fixture._outside_repository(target, "private work directory")
    if not target.is_dir():
        raise AcceptanceFailure("private work path must be a directory")
    target.chmod(0o700)
    return target


def _response_path(response: Response) -> str:
    return urlsplit(response.url).path


def _prepare_project_reader_fixture(page: Page) -> int:
    result: object = page.evaluate(
        """async () => {
          const sessionResponse = await fetch('/api/v2/session');
          if (!sessionResponse.ok) return { status: sessionResponse.status, id: 0 };
          const sessionEnvelope = await sessionResponse.json();
          const token = sessionEnvelope?.data?.csrf_token;
          const listResponse = await fetch('/api/v2/projects');
          if (!listResponse.ok) return { status: listResponse.status, id: 0 };
          const listEnvelope = await listResponse.json();
          const id = listEnvelope?.data?.[0]?.id;
          if (!Number.isSafeInteger(id) || id < 1 || typeof token !== 'string') {
            return { status: 0, id: 0 };
          }
          const detailResponse = await fetch(`/api/v2/projects/${id}`);
          if (!detailResponse.ok) return { status: detailResponse.status, id: 0 };
          const detailEnvelope = await detailResponse.json();
          const project = detailEnvelope?.data;
          const yarnId = project?.yarn_ids?.[0];
          if (!Number.isSafeInteger(yarnId) || yarnId < 1) {
            return { status: detailResponse.status, id, photo_status: 0 };
          }
          const yarnResponse = await fetch(`/api/v2/yarns/${yarnId}`);
          if (!yarnResponse.ok) {
            return {
              status: detailResponse.status,
              id,
              photo_status: 0,
              yarn_status: yarnResponse.status,
            };
          }
          const yarn = (await yarnResponse.json())?.data;
          const yarnUpdateResponse = await fetch(`/api/v2/yarns/${yarnId}`, {
            method: 'PUT',
            headers: {
              'accept': 'application/json',
              'content-type': 'application/json',
              'x-csrf-token': token,
            },
            body: JSON.stringify({
              name: yarn.name,
              description: yarn.description,
              brand: yarn.brand,
              colorway: yarn.colorway,
              dye_lot: yarn.dye_lot,
              fiber_content: yarn.fiber_content,
              weight_category: yarn.weight_category,
              recommended_needles: yarn.recommended_needles,
              weight_grams: yarn.weight_grams,
              length_meters: yarn.length_meters,
              notes: yarn.notes,
              link: 'https://ravelry.com/yarns/fixture',
              is_ai_enhanced: yarn.is_ai_enhanced,
              expected_updated_at: yarn.updated_at,
            }),
          });
          if (!yarnUpdateResponse.ok) {
            return {
              status: detailResponse.status,
              id,
              photo_status: 0,
              yarn_status: yarnUpdateResponse.status,
            };
          }
          const updateResponse = await fetch(`/api/v2/projects/${id}`, {
            method: 'PUT',
            headers: {
              'accept': 'application/json',
              'content-type': 'application/json',
              'x-csrf-token': token,
            },
            body: JSON.stringify({
              name: project.name,
              category: project.category,
              needles: '4 mm circular needles',
              stitch_sample: '18 stitches and 24 rows over 10 cm',
              description: 'Knit a soft **textured blanket** with broad, even bands.',
              notes: 'After the border, pause and check the width before continuing.',
              other_materials: 'Two stitch markers and a tapestry needle.',
              tags: ['heirloom', 'textured', 'textured'],
              link: 'https://ravelry.com/patterns/fixture',
              is_ai_enhanced: false,
              yarn_ids: project.yarn_ids,
              steps: [
                {
                  title: 'Cast on and knit the border',
                  description: 'Cast on 120 stitches and work the lower border.',
                  step_number: 1,
                },
                {
                  title: 'Work the textured panel',
                  description: 'Repeat the four-row pattern to length.',
                  step_number: 2,
                },
              ],
              expected_updated_at: project.updated_at,
            }),
          });
          if (!updateResponse.ok) {
            return { status: updateResponse.status, id, photo_status: 0 };
          }
          const updatedEnvelope = await updateResponse.json();
          const updatedProject = updatedEnvelope?.data;
          const step = updatedProject?.steps?.find(candidate =>
            candidate.title === 'Work the textured panel');
          const cover = project.images?.find(image => image.step_id === null);
          if (!Number.isSafeInteger(step?.id) || typeof cover?.url !== 'string') {
            return { status: updateResponse.status, id, photo_status: 0 };
          }
          const imageResponse = await fetch(cover.url);
          if (!imageResponse.ok) {
            return {
              status: updateResponse.status,
              id,
              photo_status: imageResponse.status,
            };
          }
          const image = await imageResponse.blob();
          const form = new FormData();
          form.append('file', image, 'textured-panel-reference.jpg');
          form.append('alt_text', 'Textured panel reference');
          const photoResponse = await fetch(
            `/api/v2/projects/${id}/steps/${step.id}/images`, {
              method: 'POST',
              headers: { 'x-csrf-token': token },
              body: form,
            });
          return {
            status: updateResponse.status,
            id,
            photo_status: photoResponse.status,
            yarn_status: yarnUpdateResponse.status,
          };
        }"""
    )
    if (
        not isinstance(result, dict)
        or result.get("status") != 200
        or not isinstance(result.get("id"), int)
        or isinstance(result.get("id"), bool)
        or result["id"] < 1
        or result.get("photo_status") != 201
        or result.get("yarn_status") != 200
    ):
        raise AcceptanceFailure(
            "the synthetic project and step-photo fixture could not be prepared"
        )
    return int(result["id"])


def _save_remote_project_version(page: Page, project_id: int, name: str) -> None:
    result: object = page.evaluate(
        """async ({ id, name }) => {
          const sessionResponse = await fetch('/api/v2/session');
          if (!sessionResponse.ok) return { status: sessionResponse.status };
          const session = await sessionResponse.json();
          const currentResponse = await fetch(`/api/v2/projects/${id}`);
          if (!currentResponse.ok) return { status: currentResponse.status };
          const current = (await currentResponse.json())?.data;
          const response = await fetch(`/api/v2/projects/${id}`, {
            method: 'PUT',
            headers: {
              'accept': 'application/json',
              'content-type': 'application/json',
              'x-csrf-token': session?.data?.csrf_token,
            },
            body: JSON.stringify({
              name,
              category: current.category,
              needles: current.needles,
              stitch_sample: current.stitch_sample,
              description: current.description,
              notes: current.notes,
              other_materials: current.other_materials,
              tags: current.tags,
              link: current.link,
              is_ai_enhanced: current.is_ai_enhanced,
              yarn_ids: current.yarn_ids,
              steps: current.steps.map(step => ({
                id: step.id,
                title: step.title,
                description: step.description,
                step_number: step.step_number,
              })),
              expected_updated_at: current.updated_at,
            }),
          });
          return { status: response.status };
        }""",
        {"id": project_id, "name": name},
    )
    _require(
        isinstance(result, dict) and result.get("status") == 200,
        "a second client could not save the synthetic conflict version",
    )


def _assert_no_horizontal_overflow(page: Page, viewport_width: int) -> None:
    dimensions = page.evaluate(
        """() => ({
          viewport: window.innerWidth,
          document: document.documentElement.scrollWidth,
          body: document.body.scrollWidth
        })"""
    )
    if (
        dimensions["document"] > viewport_width
        or dimensions["body"] > viewport_width
        or dimensions["viewport"] != viewport_width
    ):
        offenders: object = page.evaluate(
            """() => Array.from(document.querySelectorAll('body *'))
              .map(element => ({
                tag: element.tagName.toLowerCase(),
                className: element.getAttribute('class') || '',
                right: Math.round(element.getBoundingClientRect().right * 100) / 100,
                left: Math.round(element.getBoundingClientRect().left * 100) / 100,
              }))
              .filter(element => element.right > window.innerWidth || element.left < 0)
              .slice(0, 5)"""
        )
        raise AcceptanceFailure(
            f"viewport width {viewport_width}px overflowed "
            f"(inner={dimensions['viewport']}, document={dimensions['document']}, "
            f"body={dimensions['body']}; elements={offenders})"
        )


def _measure_compact_library_heading(
    page: Page,
    *,
    intro_selector: str,
    action_selector: str,
    route_name: str,
    width: int,
    require_stacked: bool = False,
) -> dict[str, float]:
    measured: object = page.evaluate(
        "selectors => { "
        "const intro = document.querySelector(selectors.intro); "
        "const action = document.querySelector(selectors.action); "
        "if (!intro || !action) return null; "
        "const introRect = intro.getBoundingClientRect(); "
        "const actionRect = action.getBoundingClientRect(); "
        "return {gap: actionRect.top - introRect.bottom, "
        "horizontalGap: actionRect.left - introRect.right, "
        "actionHeight: actionRect.height, "
        "stacked: actionRect.top >= introRect.bottom - 1}; }",
        {"intro": intro_selector, "action": action_selector},
    )
    if not isinstance(measured, dict):
        raise AcceptanceFailure(
            f"{route_name} compact heading elements were not rendered"
        )
    gap = measured.get("gap")
    horizontal_gap = measured.get("horizontalGap")
    action_height = measured.get("actionHeight")
    if (
        isinstance(gap, bool)
        or not isinstance(gap, (int, float))
        or isinstance(horizontal_gap, bool)
        or not isinstance(horizontal_gap, (int, float))
        or isinstance(action_height, bool)
        or not isinstance(action_height, (int, float))
    ):
        raise AcceptanceFailure(
            f"{route_name} compact heading geometry was unavailable"
        )
    stacked = measured.get("stacked") is True
    if stacked:
        _require(
            0 <= gap <= 32 and action_height >= 44,
            f"{route_name} stacked heading gap or target size is outside its limit",
        )
    else:
        _require(
            not require_stacked and horizontal_gap >= 0 and action_height >= 44,
            f"{route_name} row heading has overlap or a small primary target",
        )
    return {
        "gap": float(gap),
        "horizontalGap": float(horizontal_gap),
        "actionHeight": float(action_height),
        "stacked": float(stacked),
        "width": float(width),
    }


def _visible_heights(page: Page, selector: str) -> list[float]:
    values: object = page.locator(selector).evaluate_all(
        "elements => elements.filter(el => el.getClientRects().length).map("
        "el => el.getBoundingClientRect().height)"
    )
    if not isinstance(values, list) or any(
        isinstance(value, bool) or not isinstance(value, (int, float))
        for value in values
    ):
        raise AcceptanceFailure("browser returned invalid navigation target sizes")
    return [float(value) for value in values]


def _assert_step_in_usable_viewport(page: Page, step_id: int) -> None:
    bounds: object = page.locator(f"#step-{step_id}").evaluate(
        """element => {
          const step = element.getBoundingClientRect();
          const header = document.querySelector('.app-header');
          const bottomNavigation = document.querySelector('.mobile-navigation');
          const headerBottom = header && getComputedStyle(header).display !== 'none'
            ? header.getBoundingClientRect().bottom
            : 0;
          const navigationTop = bottomNavigation &&
              getComputedStyle(bottomNavigation).display !== 'none'
            ? bottomNavigation.getBoundingClientRect().top
            : window.innerHeight;
          return {
            intersectsUsableViewport: step.bottom > headerBottom &&
              step.top < navigationTop && step.bottom > 0 &&
              step.top < window.innerHeight,
            height: step.height,
          };
        }"""
    )
    if (
        not isinstance(bounds, dict)
        or bounds.get("intersectsUsableViewport") is not True
        or not isinstance(bounds.get("height"), (float, int))
        or isinstance(bounds.get("height"), bool)
        or bounds["height"] <= 0
    ):
        raise AcceptanceFailure(
            "the current pattern step is outside the usable viewport"
        )


def _assert_mobile_app_header(page: Page) -> None:
    _require(
        page.get_by_text("Welcome back", exact=True).count() == 0,
        "the redundant welcome greeting remains in the web UI",
    )
    _require(
        page.locator(
            ".content-main .page-header, .header-user, "
            ".header-sign-out, .mobile-sign-out"
        ).count()
        == 0,
        "the authenticated shell still renders a legacy page header or sign-out",
    )
    _require(
        page.locator(".secondary-nav .nav-icon svg[aria-hidden='true']").count() > 0,
        "the authenticated secondary navigation has no decorative icons",
    )
    _require(
        page.locator(".app-header .locale-switcher").count() == 0,
        "the compact mobile header still reserves space for language controls",
    )
    account_target = page.locator(".app-header .header-account")
    _require(
        account_target.is_visible()
        and ACCOUNT_HEADING_NAME.fullmatch(
            account_target.get_attribute("aria-label") or ""
        )
        is not None
        and urlsplit(account_target.get_attribute("href") or "").path == "/app/account",
        "the mobile profile link does not open Account",
    )
    dimensions: object = account_target.evaluate(
        "node => { const rect = node.getBoundingClientRect(); "
        "const wordmark = document.querySelector('.app-header .brand-lockup'); "
        "const header = document.querySelector('.app-header'); "
        "const wordmarkRight = wordmark?.getBoundingClientRect().right ?? -1; "
        "const headerRight = header?.getBoundingClientRect().right ?? -1; "
        "return {width: rect.width, height: rect.height, left: rect.left, "
        "right: rect.right, wordmarkRight, headerRight}; }"
    )
    _require(
        isinstance(dimensions, dict)
        and dimensions.get("width", 0) >= 44
        and dimensions.get("height", 0) >= 44
        and dimensions.get("left", 0) >= dimensions.get("wordmarkRight", 0)
        and dimensions.get("right", 0) >= dimensions.get("headerRight", 0) - 32,
        "the compact profile target is too small or not at the right edge",
    )


def _assert_desktop_account_target(page: Page) -> None:
    account_target = page.locator(".app-header .header-account")
    _require(
        account_target.is_visible()
        and ACCOUNT_HEADING_NAME.fullmatch(
            account_target.get_attribute("aria-label") or ""
        )
        is not None
        and urlsplit(account_target.get_attribute("href") or "").path == "/app/account",
        "the desktop profile link does not open Account",
    )
    dimensions: object = account_target.evaluate(
        "node => ({width: node.getBoundingClientRect().width, "
        "height: node.getBoundingClientRect().height})"
    )
    _require(
        isinstance(dimensions, dict) and dimensions.get("height", 0) >= 44,
        "the desktop profile target is smaller than 44px",
    )


def _assert_locale_switcher_flags(page: Page, selector: str) -> None:
    buttons = page.locator(f"{selector} button")
    _require(buttons.count() == 2, "the locale switcher does not have two buttons")
    expected = (
        ("EN", {"English", "Englisch"}),
        ("DE", {"German", "Deutsch"}),
    )
    for index, (visible_label, accessible_labels) in enumerate(expected):
        button = buttons.nth(index)
        flag = button.locator("svg.language-flag")
        _require(
            button.inner_text().strip() == visible_label
            and (button.get_attribute("aria-label") or "") in accessible_labels,
            "a locale control lost its visible or accessible language name",
        )
        _require(
            flag.count() == 1
            and flag.get_attribute("aria-hidden") == "true"
            and flag.get_attribute("focusable") == "false",
            "a locale flag is missing or exposed as separate content",
        )
        button_size: object = button.evaluate(
            "node => ({width: node.getBoundingClientRect().width, "
            "height: node.getBoundingClientRect().height})"
        )
        flag_size: object = flag.evaluate(
            "node => ({width: node.getBoundingClientRect().width, "
            "height: node.getBoundingClientRect().height})"
        )
        _require(
            isinstance(button_size, dict)
            and button_size.get("width", 0) >= 44
            and button_size.get("height", 0) >= 44
            and isinstance(flag_size, dict)
            and 8 <= flag_size.get("width", 0) <= 16
            and 6 <= flag_size.get("height", 0) <= 12,
            "a locale flag or language control has an unexpected size",
        )


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


def _assert_shell_avatar(page: Page, *, mobile: bool, present: bool) -> None:
    target = ".app-header .header-account"
    image = page.locator(f"{target} .user-avatar img")
    if not present:
        _require(image.count() == 0, "the removed avatar remains in the app shell")
        return
    image.wait_for(state="visible", timeout=10_000)
    _require(
        bool(
            image.evaluate(
                "node => node.decode().then(() => node.complete "
                "&& node.naturalWidth > 0).catch(() => false)"
            )
        ),
        "the profile avatar did not render in the app shell",
    )


def _open_account_from_profile(page: Page, *, mobile: bool = False) -> None:
    if mobile:
        _assert_mobile_app_header(page)
    else:
        _assert_desktop_account_target(page)
    selector = ".app-header .header-account"
    account_link = page.locator(selector)
    _require(account_link.is_visible(), "the Account profile link is unavailable")
    _require(
        account_link.evaluate("element => element.tagName === 'A'")
        and ACCOUNT_HEADING_NAME.fullmatch(
            account_link.get_attribute("aria-label") or ""
        )
        is not None
        and urlsplit(account_link.get_attribute("href") or "").path == "/app/account",
        "the profile control is not an accessible Account link",
    )
    account_link.click()
    page.wait_for_url("**/app/account", timeout=10_000)
    page.get_by_role("heading", name=ACCOUNT_HEADING_NAME).wait_for(
        state="visible", timeout=10_000
    )


def _account_sign_out_button(page: Page) -> Locator:
    button = page.get_by_role("button", name=SIGN_OUT_BUTTON_NAME, exact=True)
    _require(button.is_visible(), "Account sign-out action is unavailable")
    return button


def _assert_service_capabilities(page: Page, enabled: bool) -> None:
    capabilities: object = page.evaluate(
        """async () => {
          const response = await fetch('/api/v2/session');
          if (!response.ok) return null;
          const envelope = await response.json();
          return envelope?.data?.capabilities ?? null;
        }"""
    )
    if (
        not isinstance(capabilities, dict)
        or capabilities.get("ocr") is not enabled
        or capabilities.get("archive") is not enabled
    ):
        raise AcceptanceFailure(
            "the browser fixture reported unexpected OCR/archive capabilities"
        )


def _exercise_project_service_tools(
    page: Page,
    origin: str,
    project_id: int,
    enabled: bool,
    ocr_mode: str,
    archive_mode: str,
    mark_stage: Callable[[str], None],
    diagnostic_dir: Path,
) -> None:
    _assert_service_capabilities(page, enabled)
    if not enabled:
        _require(
            page.locator(".ocr-control, .archive-control").count() == 0,
            "disabled service controls were exposed in the project reader",
        )
        return

    mark_stage("opening OCR controls for a stored project image")
    ocr = page.locator(".steps-list .step-photo-card .ocr-control").first
    try:
        ocr.wait_for(state="visible", timeout=5_000)
    except PlaywrightTimeoutError:
        try:
            page.set_default_timeout(1_000)
            counts: object = page.evaluate(
                """() => ({
                  photoCards: document.querySelectorAll(
                    '.steps-list .step-photo-card').length,
                  ocrControls: document.querySelectorAll('.ocr-control').length,
                  routeReady: document.querySelectorAll('.reader-columns').length > 0,
                  authenticated: document.querySelectorAll(
                    '.app-header .header-account').length > 0,
                  viewportVisible: document.visibilityState === 'visible'
                })""",
            )
        except PlaywrightTimeoutError:
            counts = "page script did not respond to the diagnostic query"
        mask_succeeded = False
        page.set_default_timeout(1_500)
        try:
            _mask_fixture_identity(page)
            mask_succeeded = True
        except PlaywrightTimeoutError:
            counts = f"{counts}; identity mask timed out, screenshot suppressed"
        if mask_succeeded:
            try:
                page.screenshot(
                    path=str(diagnostic_dir / "project-ocr-control-debug.png"),
                    caret="initial",
                    timeout=2_000,
                )
            except PlaywrightTimeoutError:
                counts = f"{counts}; sanitized screenshot timed out"
        page.set_default_timeout(30_000)
        raise AcceptanceFailure(
            "project OCR control did not become visible "
            f"(sanitized DOM checks: {counts})"
        ) from None
    mark_stage("opening the project OCR control")
    ocr.locator("summary").click()
    mark_stage("selecting the project OCR language")
    html_language = page.locator("html").get_attribute("lang")
    if html_language == "en":
        language_label = "Recognition language"
    elif html_language == "de":
        language_label = "Erkennungssprache"
    else:
        raise AcceptanceFailure("the OCR fixture had an unsupported page language")
    language = ocr.get_by_label(language_label, exact=True)
    try:
        language.wait_for(state="visible", timeout=5_000)
    except PlaywrightTimeoutError:
        details_open = ocr.evaluate("node => node.open")
        selector_labels = ocr.locator("select").evaluate_all(
            "nodes => nodes.map(node => ({"
            "visible: !!(node.offsetWidth || node.offsetHeight), "
            "labels: Array.from(node.labels ?? []).map(label => "
            "label.textContent?.trim() ?? '') }))"
        )
        _mask_fixture_identity(page)
        page.screenshot(
            path=str(diagnostic_dir / "project-ocr-language-debug.png"),
            caret="initial",
        )
        raise AcceptanceFailure(
            "the OCR language selector was not available by its translated label "
            f"(details_open={details_open}; selectors={selector_labels})"
        ) from None
    _require(
        language.input_value() == "deu+eng",
        "OCR did not start with the configured German and English languages",
    )
    language.select_option("deu")
    mark_stage("submitting project OCR")
    if ocr_mode == "failure":
        with page.expect_response(
            lambda response: (
                urlsplit(response.url).path == "/api/v2/utils/ocr"
                and response.request.method == "POST"
            )
        ) as ocr_response_info:
            ocr.get_by_role("button", name="Recognize text", exact=True).click()
        _require(
            ocr_response_info.value.status >= 500,
            "the configured OCR failure did not reach the real API route",
        )
        ocr.get_by_role("alert").wait_for(state="visible")
        _require(
            ocr.get_by_role("alert").inner_text()
            == "Text recognition could not complete. You can enter the text manually.",
            "the OCR failure was not translated into actionable text",
        )
    else:
        page.context.grant_permissions(
            ["clipboard-read", "clipboard-write"], origin=origin
        )
        with page.expect_response(
            lambda response: (
                urlsplit(response.url).path == "/api/v2/utils/ocr"
                and response.request.method == "POST"
            )
        ) as ocr_response_info:
            ocr.get_by_role("button", name="Recognize text", exact=True).click()
        ocr_response = ocr_response_info.value
        _require(ocr_response.status == 200, "the OCR API did not succeed")
        try:
            request_payload = json.loads(ocr_response.request.post_data or "")
            result_payload = ocr_response.json()
        except (TypeError, ValueError):
            raise AcceptanceFailure(
                "the OCR API returned an unreadable result"
            ) from None
        _require(
            isinstance(request_payload, dict)
            and isinstance(request_payload.get("src"), str)
            and request_payload["src"].startswith("/media/projects/")
            and request_payload.get("lang") == "deu"
            and request_payload.get("force") is False,
            "OCR did not send the selected language and owned media path",
        )
        data = result_payload.get("data") if isinstance(result_payload, dict) else None
        if not isinstance(data, dict) or not isinstance(data.get("text"), str):
            raise AcceptanceFailure("the OCR API returned unexpected result types")
        recognized_text = data["text"]
        textarea = ocr.locator("textarea")
        textarea.wait_for(state="visible", timeout=5_000)
        _require(
            textarea.input_value() == recognized_text and recognized_text.strip(),
            "the OCR result did not display the exact real API response",
        )
        ocr.get_by_role("button", name="Copy recognized text", exact=True).click()
        ocr.get_by_role("button", name="Text copied", exact=True).wait_for(
            state="visible", timeout=5_000
        )
        _require(
            page.evaluate("navigator.clipboard.readText()") == recognized_text,
            "copy did not transfer the recognized text to the clipboard",
        )

    mark_stage("requesting a project source archive")
    mark_stage("loading project archive controls")
    archive = page.locator(".source-card .archive-control")
    archive.wait_for(state="visible", timeout=5_000)
    mark_stage("checking project archive status")
    if archive_mode == "failure":
        retry = archive.get_by_role(
            "button",
            name=re.compile(r"^(?:Request a new archive|Archive this source)$"),
        )
        mark_stage("waiting for the project archive retry action")
        retry.wait_for(state="visible", timeout=10_000)
        with page.expect_response(
            lambda response: (
                urlsplit(response.url).path == f"/api/v2/projects/{project_id}/archive"
                and response.request.method == "POST"
            )
        ) as archive_response_info:
            retry.click()
        _require(
            archive_response_info.value.status == 202,
            "the project archive request was not queued by the Rust API",
        )
        archive.get_by_text("The archive could not be completed.", exact=True).wait_for(
            state="visible", timeout=15_000
        )
        _require(
            archive.get_by_role(
                "button", name="Request a new archive", exact=True
            ).is_visible(),
            "the failed archive did not offer an explicit retry",
        )
        _require(
            page.get_by_role("link", name="Open archived copy", exact=True).count()
            == 0,
            "a failed archive was shown as an available copy",
        )
    else:
        ready = archive.get_by_text("An archived copy is ready.", exact=True)
        try:
            mark_stage("waiting for the project archive to complete")
            ready.wait_for(state="visible", timeout=10_000)
        except PlaywrightTimeoutError:
            archive_button = archive.get_by_role(
                "button",
                name=re.compile(r"^(?:Request a new archive|Archive this source)$"),
            )
            mark_stage("waiting for the project archive action")
            archive_button.wait_for(state="visible", timeout=10_000)
            with page.expect_response(
                lambda response: (
                    urlsplit(response.url).path
                    == f"/api/v2/projects/{project_id}/archive"
                    and response.request.method == "POST"
                )
            ) as archive_response_info:
                archive_button.click()
            _require(
                archive_response_info.value.status == 202,
                "the project archive request was not queued by the Rust API",
            )
            mark_stage("waiting for the project archive action to complete")
            ready.wait_for(state="visible", timeout=20_000)
        archived_link = page.get_by_role(
            "link", name=re.compile(r"^Open archived copy")
        )
        mark_stage("verifying the refreshed project archive link")
        try:
            archived_link.wait_for(state="visible", timeout=5_000)
        except PlaywrightTimeoutError:
            details: object = page.evaluate(
                """async projectId => {
                  const response = await fetch(`/api/v2/projects/${projectId}`);
                  const envelope = response.ok ? await response.json() : null;
                  const text = document.querySelector('.source-card .archive-status')
                    ?.textContent?.trim() ?? null;
                  return {
                    responseStatus: response.status,
                    detailHasArchive: typeof envelope?.data?.link_archive === 'string',
                    statusText: text,
                    linkCount: document.querySelectorAll(
                      '.source-card a[href*="web.archive.org"]').length
                  };
                }""",
                project_id,
            )
            raise AcceptanceFailure(
                "the project archive completed but its link was not shown "
                f"(sanitized state: {details})"
            ) from None
        _require(
            "web.archive.org" in (archived_link.get_attribute("href") or ""),
            "the freshly loaded project detail did not expose its archived link",
        )
    if ocr_mode == "success":
        _require(
            ocr.locator("textarea").input_value().strip() != "",
            "project OCR result disappeared after the archive update",
        )
    else:
        _require(
            ocr.get_by_role("alert").is_visible(),
            "project OCR error disappeared after the archive update",
        )
    _mask_fixture_identity(page)
    _prepare_screenshot(page)
    page.screenshot(
        path=str(diagnostic_dir / "project-media-tools.png"),
        caret="initial",
        full_page=True,
    )


def _exercise_yarn_service_tools(
    page: Page,
    origin: str,
    yarn_id: int,
    enabled: bool,
    ocr_mode: str,
    archive_mode: str,
    mark_stage: Callable[[str], None],
    screenshots: Path,
) -> None:
    _assert_service_capabilities(page, enabled)
    if not enabled:
        _require(
            page.locator(".ocr-control, .archive-control").count() == 0,
            "disabled service controls were exposed on the yarn detail",
        )
        return

    mark_stage("opening OCR controls for a stored yarn photo")
    ocr = page.locator(".photo-item .ocr-control").first
    ocr.wait_for(state="visible", timeout=5_000)
    ocr.locator("summary").click()
    if ocr_mode == "failure":
        with page.expect_response(
            lambda response: (
                urlsplit(response.url).path == "/api/v2/utils/ocr"
                and response.request.method == "POST"
            )
        ) as ocr_response_info:
            ocr.get_by_role("button", name="Recognize text", exact=True).click()
        _require(
            ocr_response_info.value.status >= 500,
            "the yarn OCR failure did not reach the real API route",
        )
        ocr.get_by_role("alert").wait_for(state="visible")
    else:
        with page.expect_response(
            lambda response: (
                urlsplit(response.url).path == "/api/v2/utils/ocr"
                and response.request.method == "POST"
            )
        ) as ocr_response_info:
            ocr.get_by_role("button", name="Recognize text", exact=True).click()
        response = ocr_response_info.value
        _require(response.status == 200, "yarn photo OCR did not succeed")
        try:
            request_payload = json.loads(response.request.post_data or "")
            payload = response.json()
        except (TypeError, ValueError):
            raise AcceptanceFailure("the yarn OCR response was unreadable") from None
        data = payload.get("data") if isinstance(payload, dict) else None
        if (
            not isinstance(request_payload, dict)
            or not isinstance(request_payload.get("src"), str)
            or not request_payload["src"].startswith("/media/yarns/")
            or not isinstance(data, dict)
            or not isinstance(data.get("text"), str)
        ):
            raise AcceptanceFailure("the yarn OCR response had unexpected types")
        textarea = ocr.locator("textarea")
        textarea.wait_for(state="visible", timeout=5_000)
        _require(
            textarea.input_value() == data["text"] and data["text"].strip(),
            "the yarn OCR control did not display the exact API result",
        )

    mark_stage("requesting a yarn source archive")
    mark_stage("loading yarn archive controls")
    archive = page.locator(".spec-panel .archive-control")
    archive.wait_for(state="visible", timeout=5_000)
    mark_stage("checking yarn archive status")
    if archive_mode == "failure":
        retry = archive.get_by_role(
            "button",
            name=re.compile(r"^(?:Request a new archive|Archive this source)$"),
        )
        mark_stage("waiting for the yarn archive retry action")
        retry.wait_for(state="visible", timeout=10_000)
        with page.expect_response(
            lambda response: (
                urlsplit(response.url).path == f"/api/v2/yarns/{yarn_id}/archive"
                and response.request.method == "POST"
            )
        ) as archive_response_info:
            retry.click()
        _require(
            archive_response_info.value.status == 202,
            "the yarn archive request was not queued by the Rust API",
        )
        archive.get_by_text("The archive could not be completed.", exact=True).wait_for(
            state="visible", timeout=15_000
        )
        _require(
            archive.get_by_role(
                "button", name="Request a new archive", exact=True
            ).is_visible(),
            "the failed yarn archive did not offer an explicit retry",
        )
        _require(
            page.get_by_role("link", name="Archived source", exact=True).count() == 0,
            "a failed yarn archive was shown as an available copy",
        )
    else:
        ready = archive.get_by_text("An archived copy is ready.", exact=True)
        try:
            mark_stage("waiting for the yarn archive to complete")
            ready.wait_for(state="visible", timeout=10_000)
        except PlaywrightTimeoutError:
            archive_button = archive.get_by_role(
                "button",
                name=re.compile(r"^(?:Request a new archive|Archive this source)$"),
            )
            mark_stage("waiting for the yarn archive action")
            archive_button.wait_for(state="visible", timeout=10_000)
            with page.expect_response(
                lambda response: (
                    urlsplit(response.url).path == f"/api/v2/yarns/{yarn_id}/archive"
                    and response.request.method == "POST"
                )
            ) as archive_response_info:
                archive_button.click()
            _require(
                archive_response_info.value.status == 202,
                "the yarn archive request was not queued by the Rust API",
            )
            mark_stage("waiting for the yarn archive action to complete")
            ready.wait_for(state="visible", timeout=20_000)
        archived_link = page.get_by_role("link", name="Archived source", exact=True)
        archived_link.wait_for(state="visible", timeout=5_000)
        _require(
            "web.archive.org" in (archived_link.get_attribute("href") or ""),
            "the freshly loaded yarn detail did not expose its archived link",
        )
    if ocr_mode == "success":
        _require(
            ocr.locator("textarea").input_value().strip() != "",
            "yarn OCR result disappeared after the archive update",
        )
    else:
        _require(
            ocr.get_by_role("alert").is_visible(),
            "yarn OCR error disappeared after the archive update",
        )
    _mask_fixture_identity(page)
    _prepare_screenshot(page)
    page.screenshot(
        path=str(screenshots / "yarn-media-tools.png"),
        caret="initial",
        full_page=True,
    )


def _exercise_gauge_calculator(
    page: Page,
    origin: str,
    signed_in: bool,
    mark_stage: Callable[[str], None],
    screenshots: Path,
) -> None:
    account_state = "signed-in" if signed_in else "anonymous"
    mark_stage(f"loading {account_state} gauge route")
    route_response = page.goto(f"{origin}/app/gauge", wait_until="networkidle")
    _require(
        route_response is not None and route_response.status == 200,
        "the gauge calculator route did not load",
    )
    page.get_by_role("heading", name="Gauge calculator", exact=True).wait_for(
        state="visible"
    )
    if signed_in:
        page.locator(".app-header .header-account").click()
        page.get_by_role("heading", name="Account", exact=True).wait_for(
            state="visible"
        )
        language_switcher = page.locator(".account-language .locale-switcher")
    else:
        language_switcher = page.locator(".public-tool-header .locale-switcher")
    english_button = language_switcher.get_by_role("button", name="English", exact=True)
    german_button = language_switcher.get_by_role("button", name="German", exact=True)
    _require(
        english_button.is_visible() and german_button.is_visible(),
        "the language switch is missing from the gauge page",
    )
    mark_stage(f"checking {account_state} language preference persistence")
    english_button.click()
    if signed_in:
        page.get_by_role("heading", name="Account", exact=True).wait_for(
            state="visible"
        )
        page.goto(f"{origin}/app/gauge", wait_until="networkidle")
        page.get_by_role("heading", name="Gauge calculator", exact=True).wait_for(
            state="visible"
        )
    page.get_by_role("heading", name="Gauge calculator", exact=True).wait_for(
        state="visible"
    )
    if signed_in:
        page.locator(".app-header .header-account").click()
        page.get_by_role("heading", name="Account", exact=True).wait_for(
            state="visible"
        )
        language_switcher = page.locator(".account-language .locale-switcher")
        german_button = language_switcher.get_by_role(
            "button", name="German", exact=True
        )
    german_button.click()
    if signed_in:
        page.get_by_role("heading", name="Konto", exact=True).wait_for(state="visible")
        page.goto(f"{origin}/app/gauge", wait_until="networkidle")
    page.get_by_role("heading", name="Maschenprobe berechnen", exact=True).wait_for(
        state="visible"
    )
    _require(
        page.locator("html").get_attribute("lang") == "de"
        and "language=de" in page.evaluate("document.cookie"),
        "the German preference did not update the document and language cookie",
    )
    page.locator(".gauge-form").evaluate(
        "form => form.dispatchEvent(new SubmitEvent('submit', "
        "{ bubbles: true, cancelable: true }))"
    )
    page.get_by_role("alert").wait_for(state="visible")
    _require(
        page.get_by_role("alert").inner_text()
        == "Prüfe die markierten Felder und versuche es erneut.",
        "the gauge validation message was not translated into German",
    )
    page.reload(wait_until="networkidle")
    page.get_by_role("heading", name="Maschenprobe berechnen", exact=True).wait_for(
        state="visible"
    )
    if signed_in:
        page.locator(".app-header .header-account").click()
        page.get_by_role("heading", name="Konto", exact=True).wait_for(state="visible")
        page.locator(".account-language .locale-switcher button").nth(0).click()
        page.goto(f"{origin}/app/gauge", wait_until="networkidle")
    else:
        page.locator(".public-tool-header .locale-switcher button").nth(0).click()
    page.get_by_role("heading", name="Gauge calculator", exact=True).wait_for(
        state="visible"
    )

    mark_stage(f"checking {account_state} gauge layout and viewport targets")
    if signed_in:
        page.locator(".app-shell").wait_for(state="visible")
        _require(
            page.locator(".mobile-navigation").is_visible()
            and page.locator(".public-tool-shell").count() == 0,
            "the signed-in gauge route did not retain the app shell",
        )
    else:
        _require(
            page.locator(".public-tool-shell").count() == 1
            and page.locator(".app-shell").count() == 0
            and page.locator(".mobile-navigation").count() == 0
            and page.get_by_role("link", name="Projects", exact=True).count() == 0
            and page.get_by_role("link", name="Yarns", exact=True).count() == 0,
            "the public gauge route exposed private library navigation",
        )

    for width in (320, 390, 1280):
        page.set_viewport_size({"width": width, "height": 844})
        _assert_no_horizontal_overflow(page, width)
        controls = _visible_heights(page, ".gauge-form input, .calculate-button")
        _require(
            len(controls) == 7 and min(controls) >= 44,
            "a gauge form control is below the 44px target size",
        )

    page.set_viewport_size({"width": 390, "height": 844})
    mark_stage(f"capturing sanitized {account_state} gauge screen")
    _mask_fixture_identity(page)
    _prepare_screenshot(page)
    page.screenshot(
        path=str(screenshots / f"gauge-{account_state}-390.png"),
        caret="initial",
    )
    page.reload(wait_until="networkidle")
    page.get_by_role("heading", name="Gauge calculator", exact=True).wait_for(
        state="visible"
    )
    expected_input = {
        "pattern_gauge_stitches": 20,
        "pattern_gauge_rows": 28,
        "user_gauge_stitches": 22,
        "user_gauge_rows": 30,
        "pattern_cast_on_stitches": 80,
        "pattern_row_count": 100,
    }
    mark_stage(f"filling {account_state} gauge inputs")
    for field, value in expected_input.items():
        page.locator(f"[name='{field}']").fill(str(value))

    mark_stage(f"submitting {account_state} gauge calculation")
    _require(
        page.locator(".gauge-form").evaluate("form => form.checkValidity()"),
        "the seeded gauge form is not valid before submit",
    )
    gauge_request_methods: list[str] = []
    gauge_response_statuses: list[int] = []
    gauge_page_error_locations: list[str] = []

    def observe_gauge_request(request: object) -> None:
        if urlsplit(getattr(request, "url", "")).path == "/api/v2/tools/gauge":
            gauge_request_methods.append(getattr(request, "method", "unknown"))

    def observe_gauge_response(response: Response) -> None:
        if _response_path(response) == "/api/v2/tools/gauge":
            gauge_response_statuses.append(response.status)

    def observe_gauge_page_error(error: object) -> None:
        stack = getattr(error, "stack", "")
        match = re.search(r"(/app/_app/immutable/[^\s:)]+\.js):(\d+):(\d+)", stack)
        gauge_page_error_locations.append(
            f"{match.group(1)}:{match.group(2)}:{match.group(3)}"
            if match
            else "script-location-unavailable"
        )

    page.on("request", observe_gauge_request)
    page.on("response", observe_gauge_response)
    page.on("pageerror", observe_gauge_page_error)
    click_completed = False
    try:
        with page.expect_response(
            lambda response: (
                urlsplit(response.url).path == "/api/v2/tools/gauge"
                and response.request.method == "POST"
            ),
            timeout=10_000,
        ) as gauge_response_info:
            page.get_by_role("button", name="Calculate", exact=True).click(
                timeout=5_000
            )
            click_completed = True
    except PlaywrightTimeoutError:
        form_valid = page.locator(".gauge-form").evaluate(
            "form => form.checkValidity()"
        )
        alert_visible = page.locator('[role="alert"]').is_visible()
        submit_disabled = page.get_by_role(
            "button", name="Calculate", exact=True
        ).is_disabled()
        _mask_fixture_identity(page)
        _prepare_screenshot(page)
        diagnostic_path = screenshots / f"gauge-{account_state}-failure.png"
        page.screenshot(path=str(diagnostic_path), caret="initial")
        raise AcceptanceFailure(
            "the gauge form did not issue its API request "
            f"(route={urlsplit(page.url).path}; form valid={form_valid}; "
            f"submit disabled={submit_disabled}; alert visible={alert_visible}; "
            f"click completed={click_completed}; "
            f"request methods={gauge_request_methods}; "
            f"responses={gauge_response_statuses}; "
            f"page error locations={gauge_page_error_locations}; "
            f"screenshot={diagnostic_path})"
        ) from None
    except Exception as error:
        raise AcceptanceFailure(
            "gauge submission raised "
            f"{type(error).__name__} before a response "
            f"(request methods={gauge_request_methods}; "
            f"responses={gauge_response_statuses})"
        ) from None
    finally:
        page.remove_listener("request", observe_gauge_request)
        page.remove_listener("response", observe_gauge_response)
        page.remove_listener("pageerror", observe_gauge_page_error)

    gauge_response = gauge_response_info.value
    mark_stage(f"checking {account_state} gauge response envelope")
    _require(
        gauge_response.status == 200,
        "the gauge API did not return a successful calculation",
    )
    try:
        request_input = json.loads(gauge_response.request.post_data or "")
        envelope = gauge_response.json()
    except (TypeError, ValueError):
        raise AcceptanceFailure("the gauge API returned an unreadable result") from None
    _require(
        request_input == expected_input,
        "the browser sent non-numeric or changed gauge inputs",
    )
    data = envelope.get("data") if isinstance(envelope, dict) else None
    if (
        not isinstance(data, dict)
        or not isinstance(data.get("adjusted_stitches"), str)
        or not isinstance(data.get("adjusted_rows"), str)
    ):
        raise AcceptanceFailure("the gauge API returned a result with unexpected types")
    page.locator(".results").wait_for(state="visible")
    _require(
        page.locator(".result-grid strong").all_inner_texts()
        == [data["adjusted_stitches"], data["adjusted_rows"]],
        "the gauge page changed the exact server-calculated result strings",
    )

    mark_stage(f"checking {account_state} stale gauge request cancellation")
    stitch_input = page.locator("[name='user_gauge_stitches']")
    stitch_input.fill("23")
    page.evaluate(
        """() => {
          const originalFetch = window.fetch.bind(window);
          const hold = {
            armed: true,
            responseReady: false,
            aborted: false,
            originalFetch,
          };
          window.__gaugeResponseHold = hold;
          window.fetch = (input, init = {}) => {
            const requestUrl = new URL(
              typeof input === 'string' ? input : input.url,
              window.location.href,
            );
            const shouldHold = hold.armed
              && requestUrl.pathname === '/api/v2/tools/gauge'
              && (init.method || 'GET').toUpperCase() === 'POST';
            if (!shouldHold) return originalFetch(input, init);
            hold.armed = false;
            return originalFetch(input, init).then(response => new Promise(
              (resolve, reject) => {
                hold.responseReady = true;
                const signal = init.signal;
                if (!signal) {
                  hold.missingSignal = true;
                  resolve(response);
                  return;
                }
                if (signal.aborted) {
                  hold.aborted = true;
                  reject(new DOMException('Aborted', 'AbortError'));
                  return;
                }
                signal.addEventListener('abort', () => {
                  hold.aborted = true;
                  reject(new DOMException('Aborted', 'AbortError'));
                }, { once: true });
                hold.release = () => resolve(response);
              },
            ));
          };
        }"""
    )

    def request_filter(request: Request) -> bool:
        return (
            urlsplit(request.url).path == "/api/v2/tools/gauge"
            and request.method == "POST"
        )

    try:
        with page.expect_request(request_filter, timeout=5_000) as delayed_request_info:
            page.get_by_role("button", name="Calculate", exact=True).click(
                timeout=5_000
            )
        delayed_request = delayed_request_info.value
        race_start_input = dict(expected_input)
        race_start_input["user_gauge_stitches"] = 23
        try:
            request_payload = json.loads(delayed_request.post_data or "")
        except (TypeError, ValueError):
            raise AcceptanceFailure(
                "the delayed gauge request body was unreadable"
            ) from None
        _require(
            request_payload == race_start_input,
            "the delayed gauge request did not use the current input values",
        )
        page.wait_for_function(
            "() => window.__gaugeResponseHold?.responseReady", timeout=5_000
        )
        stitch_input.fill("24")
        page.wait_for_function(
            "() => window.__gaugeResponseHold?.aborted", timeout=5_000
        )
        _require(
            not page.evaluate("() => window.__gaugeResponseHold?.missingSignal"),
            "editing the gauge did not abort its in-flight API request",
        )
    finally:
        page.evaluate(
            "() => { if (window.__gaugeResponseHold) "
            "window.fetch = window.__gaugeResponseHold.originalFetch; }"
        )

    _require(
        stitch_input.input_value() == "24"
        and page.locator(".results").count() == 0
        and not page.get_by_role("button", name="Calculate", exact=True).is_disabled(),
        "a stale gauge result replaced the edited values or left submit locked",
    )
    with page.expect_response(
        lambda response: (
            urlsplit(response.url).path == "/api/v2/tools/gauge"
            and response.request.method == "POST"
        )
    ) as current_response_info:
        page.get_by_role("button", name="Calculate", exact=True).click()
    current_response = current_response_info.value
    _require(
        current_response.status == 200,
        "the gauge could not be recalculated after an edit cancelled a request",
    )
    try:
        current_request_input = json.loads(current_response.request.post_data or "")
        current_envelope = current_response.json()
    except (TypeError, ValueError):
        raise AcceptanceFailure(
            "the recalculated gauge response was unreadable"
        ) from None
    _require(
        current_request_input == {**race_start_input, "user_gauge_stitches": 24},
        "the recalculated gauge request did not preserve the edited values",
    )
    current_data = (
        current_envelope.get("data") if isinstance(current_envelope, dict) else None
    )
    if (
        not isinstance(current_data, dict)
        or not isinstance(current_data.get("adjusted_stitches"), str)
        or not isinstance(current_data.get("adjusted_rows"), str)
    ):
        raise AcceptanceFailure("the recalculated gauge result had unexpected types")
    page.locator(".results").wait_for(state="visible")
    _require(
        page.locator(".result-grid strong").all_inner_texts()
        == [current_data["adjusted_stitches"], current_data["adjusted_rows"]],
        "the recalculated result did not match the exact server response",
    )


def _mask_fixture_identity(page: Page) -> None:
    """Mask disposable account identifiers before screenshot capture."""
    page.locator(".profile-email").evaluate_all(
        "nodes => nodes.forEach(node => node.textContent = 'Demo account')"
    )
    page.locator(".user-avatar").evaluate_all(
        "nodes => nodes.forEach(node => node.textContent = 'D')"
    )
    page.locator("#email, #password").evaluate_all(
        "nodes => nodes.forEach(node => { node.value = ''; })"
    )


def _check_mobile_language_and_screenshots(
    page: Page,
    origin: str,
    screenshots: Path,
    mark_stage: Callable[[str], None],
) -> None:
    page.set_viewport_size({"width": 390, "height": 844})
    _mask_fixture_identity(page)
    _prepare_screenshot(page)
    page.screenshot(
        path=str(screenshots / "projects-mobile-light.png"), caret="initial"
    )
    page.emulate_media(color_scheme="dark")
    page.reload(wait_until="networkidle")
    page.locator(".project-card .resource-card__link").first.wait_for(state="visible")
    page.evaluate("document.fonts.ready")
    page.screenshot(path=str(screenshots / "projects-mobile-dark.png"), caret="initial")
    project_contrast: object = page.evaluate(
        """() => {
          const card = document.querySelector('.project-card');
          const title = card?.querySelector('h2');
          const link = card?.querySelector('.resource-card__link');
          if (!card || !title || !link) return { ratio: 0 };
          const titleColor = getComputedStyle(title).color;
          const linkColor = getComputedStyle(link).color;
          const cardColor = getComputedStyle(card).color;
          const libraryColor = getComputedStyle(card.closest('.library')).color;
          const rootColor = getComputedStyle(document.documentElement).color;
          const color = titleColor.match(/[\\d.]+/g)?.map(Number);
          const surface = getComputedStyle(card).backgroundColor
            .match(/[\\d.]+/g)?.map(Number);
          if (!color || !surface || color.length < 3 || surface.length < 3) {
            return {
              ratio: 0,
              titleColor,
              linkColor,
              cardColor,
              libraryColor,
              rootColor,
            };
          }
          const luminance = ([r, g, b]) => [r, g, b]
            .map(value => value / 255)
            .map(value => value <= 0.04045
              ? value / 12.92
              : Math.pow((value + 0.055) / 1.055, 2.4))
            .reduce((sum, value, index) => {
              const weights = [0.2126, 0.7152, 0.0722];
              return sum + value * weights[index];
            }, 0);
          const values = [luminance(color), luminance(surface)].sort((a, b) => b - a);
          return {
            ratio: (values[0] + 0.05) / (values[1] + 0.05),
            titleColor,
            linkColor,
            cardColor,
            libraryColor,
            rootColor,
            surfaceColor: getComputedStyle(card).backgroundColor,
          };
        }"""
    )
    contrast_ratio = (
        project_contrast.get("ratio") if isinstance(project_contrast, dict) else None
    )
    print(
        "Dark project title contrast: "
        + json.dumps(
            {
                key: project_contrast.get(key)
                for key in (
                    "ratio",
                    "titleColor",
                    "linkColor",
                    "cardColor",
                    "surfaceColor",
                )
            }
            if isinstance(project_contrast, dict)
            else {"ratio": None},
            sort_keys=True,
        )
    )
    _require(
        isinstance(contrast_ratio, (int, float))
        and not isinstance(contrast_ratio, bool)
        and contrast_ratio >= 4.5,
        f"project card title contrast is below 4.5:1 ({project_contrast})",
    )
    page.emulate_media(color_scheme="light")
    page.reload(wait_until="networkidle")
    page.locator(".project-card .resource-card__link").first.wait_for(state="visible")

    _require(
        page.locator(".app-header .locale-switcher").count() == 0,
        "the mobile header still reserves space for language controls",
    )
    page.locator(".app-header .header-account").click()
    page.get_by_role("heading", name="Account", exact=True).wait_for(state="visible")
    page.locator(".account-language .locale-switcher").wait_for(state="visible")
    _require(
        page.locator(".page-header .locale-switcher").count() == 0
        and page.locator(".account-language .locale-switcher").count() == 1,
        "language controls did not move into Account",
    )
    _assert_locale_switcher_flags(page, ".account-language .locale-switcher")
    language_targets = _visible_heights(
        page, ".account-language .locale-switcher button"
    )
    _require(
        len(language_targets) == 2 and min(language_targets) >= 44,
        "a mobile account language target is below 44px",
    )
    _assert_no_horizontal_overflow(page, 390)
    _mask_fixture_identity(page)
    _prepare_screenshot(page)
    page.screenshot(path=str(screenshots / "account-mobile-light.png"), caret="initial")
    page.emulate_media(color_scheme="dark")
    page.reload(wait_until="networkidle")
    page.get_by_role("heading", name="Account", exact=True).wait_for(state="visible")
    page.evaluate("document.fonts.ready")
    page.screenshot(path=str(screenshots / "account-mobile-dark.png"), caret="initial")
    page.emulate_media(color_scheme="light")
    page.reload(wait_until="networkidle")
    page.get_by_role("heading", name="Account", exact=True).wait_for(state="visible")

    page.evaluate(
        "document.activeElement instanceof HTMLElement && document.activeElement.blur()"
    )
    reached_language_by_keyboard = False
    for _ in range(18):
        page.keyboard.press("Tab")
        focused_label = page.evaluate(
            "document.activeElement?.getAttribute('aria-label') || ''"
        )
        if focused_label in {"English", "German"}:
            reached_language_by_keyboard = True
            break
    _require(
        reached_language_by_keyboard,
        "mobile language controls are not reachable in the keyboard tab order",
    )
    page.locator('.account-language button[aria-label="German"]').focus()
    page.keyboard.press("Enter")
    page.wait_for_function(
        "() => document.documentElement.lang === 'de'", timeout=5_000
    )
    _require(
        page.get_by_role("heading", name="Konto", exact=True).is_visible(),
        "German locale selection did not update the account interface",
    )
    page.reload(wait_until="networkidle")
    _require(
        page.locator("html").get_attribute("lang") == "de",
        "the account language choice did not persist after reload",
    )
    for color_scheme in ("light", "dark"):
        page.emulate_media(color_scheme=color_scheme)
        for width in (320, 390, 768, 1280):
            mark_stage(
                f"checking account layout and locale at {width}px "
                f"in {color_scheme} mode"
            )
            page.set_viewport_size({"width": width, "height": 844})
            _assert_no_horizontal_overflow(page, width)
            switcher = ".account-language .locale-switcher"
            page.locator(switcher).wait_for(state="visible")
            _assert_locale_switcher_flags(page, switcher)
            control_heights = _visible_heights(page, f"{switcher} button")
            _require(
                len(control_heights) == 2 and min(control_heights) >= 44,
                f"locale controls are not 44px targets at {width}px",
            )
            for locale_index, locale_code in ((1, "de"), (0, "en")):
                page.locator(switcher).get_by_role("button").nth(locale_index).click()
                page.wait_for_function(
                    "locale => document.documentElement.lang === locale",
                    arg=locale_code,
                    timeout=5_000,
                )
                _assert_no_horizontal_overflow(page, width)
    page.emulate_media(color_scheme="light")
    page.set_viewport_size({"width": 390, "height": 844})
    page.goto(f"{origin}/app/projects", wait_until="networkidle")
    page.get_by_role("heading", name="Heirloom Baby Blanket", exact=True).wait_for(
        state="visible"
    )


def _prepare_screenshot(page: Page) -> None:
    """Capture the viewport from the top without a focused skip-link overlay."""
    page.evaluate(
        "() => { window.scrollTo(0, 0); "
        "if (document.activeElement instanceof HTMLElement) "
        "document.activeElement.blur(); }"
    )


def run_live_fixture(
    binary: Path,
    web_root: Path,
    private_dir: Path,
    chromium_executable: Path | None = None,
    service_fixture_binary: Path | None = None,
    ocr_mode: str = "success",
    archive_mode: str = "success",
    signup_enabled: bool = False,
    *,
    browser_name: str = "chromium",
    browser_executable: Path | None = None,
) -> Path:
    """Start, exercise, and safely stop one disposable server fixture."""
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
            server_binary=service_fixture_binary,
            ocr_mode=ocr_mode,
            archive_mode=archive_mode,
            signup_enabled=signup_enabled,
        )
        started = True
        try:
            seed_rust.seed_fixture(fixture_file, token_file)
        except seed_rust.SeedError as error:
            raise AcceptanceFailure(
                f"synthetic project and photo fixture could not be seeded: {error}"
            ) from None
        finally:
            token_file.unlink(missing_ok=True)
        return run_acceptance(
            fixture_file,
            private_dir / "screenshots",
            chromium_executable,
            service_fixture_enabled=service_fixture_binary is not None,
            ocr_mode=ocr_mode,
            archive_mode=archive_mode,
            signup_enabled=signup_enabled,
            browser_name=browser_name,
            browser_executable=browser_executable,
        )
    finally:
        if started:
            rust_fixture.stop_fixture(state_file)


def run_acceptance(
    fixture_file: Path,
    screenshot_path: Path | None = None,
    chromium_executable: Path | None = None,
    *,
    service_fixture_enabled: bool = False,
    ocr_mode: str = "success",
    archive_mode: str = "success",
    signup_enabled: bool = False,
    browser_name: str = "chromium",
    browser_executable: Path | None = None,
) -> Path:
    fixture = rust_fixture.load_fixture_environment(fixture_file)
    origin = _loopback_origin(fixture["STRICKNANI_RUST_TEST_URL"])
    email = fixture["STRICKNANI_RUST_TEST_EMAIL"]
    password = fixture["STRICKNANI_RUST_TEST_PASSWORD"]
    screenshots = _private_screenshot_dir(screenshot_path)
    stage_file = screenshots.parent / "browser-stage.txt"
    current_stage = [""]
    csp_event_summaries: list[dict[str, object]] = []

    def mark_stage(value: str) -> None:
        current_stage[0] = value
        stage_file.write_text(value, encoding="ascii")
        stage_file.chmod(0o600)

    if chromium_executable is not None and browser_executable is not None:
        raise AcceptanceFailure(
            "use only one of --browser-executable and --chromium-executable"
        )
    if chromium_executable is not None and browser_name != "chromium":
        raise AcceptanceFailure(
            "--chromium-executable is only valid with --browser chromium"
        )
    executable = browser_executable or chromium_executable
    if browser_name not in {"chromium", "firefox", "webkit"}:
        raise AcceptanceFailure("browser must be chromium, firefox, or webkit")
    if executable is not None:
        executable = executable.expanduser().resolve()
        if not executable.is_file() or not os.access(executable, os.X_OK):
            raise AcceptanceFailure("configured browser executable is unavailable")

    session_observations: list[tuple[int, bool, bool, bool]] = []
    session_identity_observations: list[tuple[int, str | None]] = []
    csrf_requests: list[tuple[str, bool]] = []
    auth_responses: list[tuple[str, int]] = []
    project_responses: list[tuple[str, int]] = []
    project_list_sizes: list[int | None] = []
    project_list_queries: list[dict[str, list[str]]] = []
    yarn_responses: list[tuple[str, int]] = []
    service_responses: list[tuple[str, int]] = []
    category_responses: list[tuple[str, int]] = []
    app_asset_statuses: list[int] = []
    catalog_statuses: list[int] = []
    original_project_image_requests: list[str] = []
    original_project_image_responses: list[tuple[str, int]] = []
    csp_errors: list[str] = []
    console_errors: list[str] = []
    console_error_locations: list[str] = []
    page_errors: list[bool] = []
    page_error_types: list[str] = []
    responsive_failures: list[str] = []
    expected_404_path = "/app/this-route-does-not-exist"
    expected_404_window = [False]
    expected_conflict_window = [False]
    expected_signup_conflict_window = [False]
    expected_signup_conflict_pending = [False]
    expected_conflict_paths: set[str] = set()
    expected_print_abort_window = [False]
    expected_print_abort_paths: set[str] = set()

    def observe_request(request: object) -> None:
        url = getattr(request, "url", "")
        request_url = urlsplit(url)
        path = request_url.path
        if path.startswith("/media/projects/"):
            original_project_image_requests.append(path)
            if expected_print_abort_window[0]:
                expected_print_abort_paths.add(path)
        if path == "/api/v2/projects":
            project_list_queries.append(parse_qs(request_url.query))
        if path not in {
            "/api/v2/auth/login",
            "/api/v2/auth/logout",
            "/api/v2/auth/signup",
        } and not (path.startswith("/api/v2/projects/") and path.endswith("/favorite")):
            return
        headers = getattr(request, "headers", {})
        csrf_requests.append((path, bool(headers.get("x-csrf-token"))))

    def observe_response(response: Response) -> None:
        path = _response_path(response)
        if path.startswith("/media/projects/"):
            original_project_image_responses.append((path, response.status))
        if path in {
            "/api/v2/auth/login",
            "/api/v2/auth/logout",
            "/api/v2/auth/signup",
        }:
            auth_responses.append((path, response.status))
            if (
                path == "/api/v2/auth/signup"
                and expected_signup_conflict_window[0]
                and response.request.method == "POST"
                and response.status == 409
            ):
                expected_signup_conflict_pending[0] = True
        if path.startswith("/api/v2/projects/") or path == "/api/v2/projects":
            project_responses.append((path, response.status))
            if (
                expected_conflict_window[0]
                and path.startswith("/api/v2/projects/")
                and response.request.method == "PUT"
                and response.status == 409
            ):
                expected_conflict_paths.add(path)
        if path == "/api/v2/projects" and response.request.method == "GET":
            size: int | None = None
            if response.status == 200:
                try:
                    payload = response.json()
                    data = payload.get("data") if isinstance(payload, dict) else None
                    if isinstance(data, list):
                        size = len(data)
                except Exception:
                    pass
            project_list_sizes.append(size)
        if path.startswith("/api/v2/yarns/") or path == "/api/v2/yarns":
            yarn_responses.append((path, response.status))
        if path == "/api/v2/utils/ocr":
            service_responses.append((path, response.status))
        if path.startswith("/api/v2/categories"):
            category_responses.append((path, response.status))
        if path in {"/api/v2/session", "/api/v2/auth/login"}:
            session_user_is_null = False
            csrf_present = False
            numeric_user_id = False
            if response.status == 200:
                try:
                    payload = response.json()
                except Exception:
                    payload = None
                data = payload.get("data") if isinstance(payload, dict) else None
                if isinstance(data, dict):
                    csrf_present = isinstance(data.get("csrf_token"), str) and bool(
                        data["csrf_token"]
                    )
                    user = data.get("user")
                    session_user_is_null = user is None
                    numeric_user_id = (
                        isinstance(user, dict)
                        and isinstance(user.get("id"), int)
                        and not isinstance(user.get("id"), bool)
                        and 0 < user["id"] < 2**53
                    )
            session_observations.append(
                (response.status, session_user_is_null, csrf_present, numeric_user_id)
            )
            session_email: str | None = None
            if response.status == 200:
                try:
                    payload = response.json()
                except Exception:
                    payload = None
                data = payload.get("data") if isinstance(payload, dict) else None
                user = data.get("user") if isinstance(data, dict) else None
                email = user.get("email") if isinstance(user, dict) else None
                if isinstance(email, str):
                    session_email = email
            session_identity_observations.append((response.status, session_email))
        elif path.startswith("/app/_app/"):
            app_asset_statuses.append(response.status)
        elif path == "/app/messages.json":
            catalog_statuses.append(response.status)

    def observe_console(message: object) -> None:
        text = getattr(message, "text", "").lower()
        csp_violation = "content security policy" in text or (
            "violat" in text and ("script-src" in text or "style-src" in text)
        )
        if getattr(message, "type", "") != "error":
            return
        if csp_violation:
            directives = (
                "script-src",
                "style-src",
                "default-src",
                "img-src",
                "font-src",
                "connect-src",
            )
            directive = next(
                (item for item in directives if item in text), "CSP directive"
            )
            location = getattr(message, "location", {})
            source_path = urlsplit(location.get("url", "")).path
            if directive == "style-src":
                if "style attribute" in text or "style attr" in text:
                    violation_kind = "style-attribute"
                elif "inline" in text:
                    violation_kind = "inline-style"
                else:
                    violation_kind = "stylesheet-or-rule"
                csp_errors.append(f"{directive}:{violation_kind}")
            else:
                csp_errors.append(directive)
            csp_event_summaries.append(
                {
                    "stage": current_stage[0],
                    "directive": directive,
                    "blockedURIClass": (
                        "inline"
                        if "inline" in text
                        else "data"
                        if "data:" in text
                        else "external"
                    ),
                    "sourcePath": source_path,
                    "lineNumber": location.get("lineNumber"),
                    "columnNumber": location.get("columnNumber"),
                }
            )
            console_errors.append("csp_violation")
            return
        location = getattr(message, "location", {})
        source_path = urlsplit(location.get("url", "")).path
        if (
            expected_404_window[0]
            and ("404" in text or "not found" in text)
            and source_path == expected_404_path
        ):
            console_errors.append("expected_unknown_route_404")
        elif (
            source_path in expected_conflict_paths
            and "failed to load resource" in text
            and any(
                response_path == source_path and status == 409
                for response_path, status in project_responses
            )
        ):
            console_errors.append("expected_project_conflict_409")
        elif (
            expected_signup_conflict_pending[0]
            and source_path == "/api/v2/auth/signup"
            and "failed to load resource" in text
            and any(
                response_path == source_path and status == 409
                for response_path, status in auth_responses
            )
        ):
            console_errors.append("expected_signup_conflict_409")
            expected_signup_conflict_pending[0] = False
        elif (
            source_path == "/api/v2/utils/ocr"
            and ocr_mode == "failure"
            and any(
                response_path == source_path and status >= 500
                for response_path, status in service_responses
            )
        ):
            console_errors.append("expected_ocr_failure_5xx")
        elif (
            expected_print_abort_window[0]
            and source_path in expected_print_abort_paths
            and "failed to load resource" in text
        ):
            console_errors.append("expected_print_image_abort")
        else:
            if source_path.startswith("/api/v2/projects/"):
                source_class = "project-api"
            elif source_path == "/api/v2/projects":
                source_class = "project-list-api"
            elif source_path.startswith("/api/v2/"):
                source_class = "resource-api"
            elif source_path.startswith("/app/_app/"):
                source_class = "app-asset"
            elif source_path.startswith("/app/"):
                source_class = "app-route"
            else:
                source_class = "browser"
            console_errors.append(f"unexpected_console_error:{source_class}")
            status = re.search(r"status of (\d+)", text)
            console_error_locations.append(
                f"{source_path or '/'}:{status.group(1) if status else 'unknown'}"
            )

    def observe_page_error(_error: object) -> None:
        page_errors.append(True)
        error_name = getattr(_error, "name", "unknown")
        page_error_types.append(
            error_name if isinstance(error_name, str) else "unknown"
        )

    with sync_playwright() as playwright:
        mark_stage(f"launching packaged {browser_name}")
        try:
            browser_type = getattr(playwright, browser_name)
            browser = browser_type.launch(
                headless=True,
                executable_path=(str(executable) if executable is not None else None),
            )
        except Exception as error:
            raise AcceptanceFailure(
                f"configured {browser_name} could not start in headless mode"
            ) from error
        try:
            context = browser.new_context(
                viewport={"width": 390, "height": 844},
                reduced_motion="reduce",
                color_scheme="light",
                locale="en-US",
            )
            page = context.new_page()
            if browser_name == "webkit":
                # Playwright's WebKit screenshot path injects inline <style>;
                # keep the real strict-CSP check enabled and omit captures here.
                page.screenshot = lambda *_args, **_kwargs: None
            page.on("request", observe_request)
            page.on("response", observe_response)
            page.on("console", observe_console)
            page.on("pageerror", observe_page_error)

            mark_stage("navigating to anonymous sign-in")
            response = page.goto(f"{origin}/app/login", wait_until="domcontentloaded")
            if response is None or response.status != 200:
                raise AcceptanceFailure("direct login route did not load")
            mark_stage("waiting for anonymous sign-in form")
            page.locator("#email").wait_for(state="visible", timeout=10_000)
            mark_stage("waiting for sign-in heading")
            page.get_by_role("heading", name="Sign in").wait_for(
                state="visible", timeout=10_000
            )
            _require(
                page.locator(".app-shell").count() == 0,
                "protected navigation appeared while signed out",
            )
            _require(
                page.locator(".mobile-navigation").count() == 0,
                "mobile library navigation appeared while signed out",
            )
            gauge_link = page.get_by_role("link", name="Gauge calculator", exact=True)
            _require(
                gauge_link.count() == 1
                and gauge_link.get_attribute("href") == "/app/gauge",
                "the signed-out screen did not expose the public gauge tool",
            )

            browser_email = email
            browser_password = password
            if signup_enabled:
                mark_stage("checking the capability-gated signup link")
                signup_link = page.get_by_role(
                    "link", name="Create an account", exact=True
                )
                try:
                    signup_link.wait_for(state="visible", timeout=5_000)
                except PlaywrightTimeoutError:
                    capability: object = page.evaluate(
                        """async () => {
                          const response = await fetch('/api/v2/session');
                          const envelope = response.ok ? await response.json() : null;
                          return {
                            status: response.status,
                            signup: envelope?.data?.capabilities?.signup ?? null,
                            signInVisible: !!document.querySelector('#password')
                          };
                        }"""
                    )
                    raise AcceptanceFailure(
                        "the signup link did not match the anonymous capability "
                        f"(sanitized checks={capability}; matching link count="
                        f"{signup_link.count()})"
                    ) from None
                mark_stage("selecting German for the signup page")
                page.locator(".auth-footer").get_by_role(
                    "button", name="German", exact=True
                ).click()
                page.wait_for_function(
                    "() => document.documentElement.lang === 'de'", timeout=5_000
                )
                mark_stage("clicking the translated signup link")
                page.get_by_role("link", name="Konto erstellen", exact=True).click()
                mark_stage("waiting for the translated signup route")
                try:
                    page.wait_for_url("**/app/signup", timeout=5_000)
                except PlaywrightTimeoutError:
                    raise AcceptanceFailure(
                        "the translated signup link did not reach its route "
                        f"(path={urlsplit(page.url).path}; "
                        f"signup form count={page.locator('#signup-email').count()}; "
                        f"page language={page.locator('html').get_attribute('lang')})"
                    ) from None
                page.set_default_timeout(2_000)
                route_state: dict[str, object] = {"path": urlsplit(page.url).path}
                diagnostics: tuple[tuple[str, Callable[[], object]], ...] = (
                    ("language", lambda: page.locator("html").get_attribute("lang")),
                    ("signupPanel", lambda: page.locator(".signup-panel").count() > 0),
                    ("signInPassword", lambda: page.locator("#password").count() > 0),
                    (
                        "englishHeading",
                        lambda: (
                            page.get_by_role(
                                "heading", name="Create an account", exact=True
                            ).count()
                            > 0
                        ),
                    ),
                    (
                        "germanHeading",
                        lambda: (
                            page.get_by_role(
                                "heading", name="Konto erstellen", exact=True
                            ).count()
                            > 0
                        ),
                    ),
                    (
                        "germanHeadingVisible",
                        lambda: page.get_by_role(
                            "heading", name="Konto erstellen", exact=True
                        ).is_visible(),
                    ),
                    (
                        "germanHeadingBox",
                        lambda: page.get_by_role(
                            "heading", name="Konto erstellen", exact=True
                        ).bounding_box(),
                    ),
                    (
                        "signupFormVisible",
                        lambda: page.locator("#signup-email").is_visible(),
                    ),
                    (
                        "emailLabelCount",
                        lambda: page.get_by_label("E-Mail", exact=True).count(),
                    ),
                    (
                        "emailLabelVisible",
                        lambda: page.get_by_label("E-Mail", exact=True).is_visible(),
                    ),
                    (
                        "signupCapability",
                        lambda: page.evaluate(
                            """async () => {
                              const result = await Promise.race([
                                fetch('/api/v2/session', {credentials: 'same-origin'})
                                  .then(async response => ({
                                    available: true,
                                    enabled: response.ok &&
                                      (await response.json())?.data?.capabilities
                                        ?.signup === true
                                  })),
                                new Promise(resolve => setTimeout(() =>
                                  resolve({available: false, enabled: false}), 1500))
                              ]);
                              return result;
                            }"""
                        ),
                    ),
                )
                for key, query in diagnostics:
                    try:
                        route_state[key] = query()
                    except Exception as diagnostic_error:
                        route_state[key] = type(diagnostic_error).__name__
                page.set_default_timeout(10_000)
                mark_stage(
                    "waiting for translated signup content; preflight="
                    + json.dumps(route_state, ensure_ascii=True, sort_keys=True)
                )
                try:
                    page.get_by_role(
                        "heading", name="Konto erstellen", exact=True
                    ).wait_for(state="visible", timeout=5_000)
                except PlaywrightTimeoutError:
                    raise AcceptanceFailure(
                        "translated signup content was not visible; "
                        "sanitized preflight="
                        + json.dumps(route_state, ensure_ascii=True, sort_keys=True)
                    ) from None
                mark_stage("signup heading visible")
                _require(
                    page.locator(".app-shell").count() == 0,
                    "private navigation appeared on the signup route",
                )
                mark_stage("waiting for translated signup email label")
                page.get_by_label("E-Mail", exact=True).wait_for(state="visible")
                mark_stage("signup email label visible")
                _require(
                    page.locator("html").get_attribute("lang") == "de",
                    "the signup page did not retain the chosen German locale",
                )
                page.set_viewport_size({"width": 320, "height": 760})
                mark_stage("checking signup at 320px")
                _assert_no_horizontal_overflow(page, 320)
                mark_stage("signup 320px overflow check passed")
                signup_control_heights = _visible_heights(
                    page,
                    "#signup-email, #signup-password, "
                    "#signup-confirmation, .signup-form button",
                )
                _require(
                    len(signup_control_heights) == 4
                    and min(signup_control_heights) >= 44,
                    "a signup form control is smaller than the 44px target",
                )
                mark_stage("signup 44px controls passed")
                page.set_viewport_size({"width": 390, "height": 844})
                mark_stage("filling signup confirmation mismatch")
                signup_email = f"signup-{secrets.token_hex(8)}@example.test"
                signup_password = secrets.token_urlsafe(24)
                mark_stage("filling signup email")
                page.locator("#signup-email").fill(signup_email)
                mark_stage("signup email filled")
                page.locator("#signup-password").fill(signup_password)
                mark_stage("signup password filled")
                page.locator("#signup-confirmation").fill("mismatch")
                mark_stage(
                    "signup mismatch filled; page errors="
                    + ",".join(page_error_types[:3])
                    + "; signup requests="
                    + str(
                        sum(path == "/api/v2/auth/signup" for path, _ in auth_responses)
                    )
                )
                signup_submit = page.locator(".signup-form button")
                mark_stage("counting signup submit controls")
                submit_count = signup_submit.count()
                mark_stage(f"signup submit control count={submit_count}")
                submit_state: dict[str, object] = {
                    "count": submit_count,
                    "pageErrors": page_error_types[:3],
                }
                if submit_count == 1:
                    submit_state["text"] = signup_submit.inner_text()
                    submit_state["disabled"] = signup_submit.evaluate(
                        "button => button.disabled"
                    )
                    submit_state["visible"] = signup_submit.is_visible()
                mark_stage(
                    "signup submit state="
                    + json.dumps(submit_state, ensure_ascii=True, sort_keys=True)
                )
                _require(
                    submit_state["visible"] is True
                    and submit_state["disabled"] is False,
                    "signup submit was not actionable after anonymous bootstrap",
                )
                signup_submit.click(timeout=5_000)
                mark_stage("waiting for local password mismatch copy")
                try:
                    page.get_by_text(
                        "Die Passwörter stimmen nicht überein.", exact=True
                    ).wait_for(state="visible")
                except PlaywrightTimeoutError:
                    try:
                        safe_state = page.evaluate(
                            """() => {
                              const button = document.querySelector(
                                '.signup-form button'
                              );
                              return {
                                path: location.pathname,
                                queryKeys: Array.from(
                                  new URL(location.href).searchParams.keys()
                                ),
                                language: document.documentElement.lang,
                                signupForm: Boolean(
                                  document.querySelector('.signup-form')
                                ),
                                headingTexts: Array.from(
                                  document.querySelectorAll('h1')
                                )
                                  .map(node => node.textContent?.trim() ?? ''),
                                errorTexts: Array.from(document.querySelectorAll(
                                  '.field-error, .signup-error, [role=alert]'
                                )).map(node => node.textContent?.trim() ?? ''),
                                buttonText: button?.textContent?.trim() ?? null,
                                buttonDisabled: button?.disabled ?? null
                              };
                            }"""
                        )
                    except Exception as diagnostic_error:
                        safe_state = {
                            "browserResponsive": False,
                            "diagnosticError": type(diagnostic_error).__name__,
                            "pageErrorTypes": page_error_types[:3],
                        }
                    mark_stage(
                        "signup mismatch diagnostic="
                        + json.dumps(safe_state, ensure_ascii=True, sort_keys=True)
                    )
                    raise AcceptanceFailure(
                        "localized password mismatch feedback did not appear"
                    ) from None
                _require(
                    not any(
                        path == "/api/v2/auth/signup" for path, _ in auth_responses
                    ),
                    "password confirmation mismatch reached the signup API",
                )
                mark_stage("local password mismatch verified")
                page.locator(".signup-footer").get_by_role(
                    "button", name="Englisch", exact=True
                ).click()
                page.goto(f"{origin}/app/signup", wait_until="networkidle")
                page.get_by_role(
                    "heading", name="Create an account", exact=True
                ).wait_for(state="visible")
                page.locator("#signup-email").fill(signup_email)
                page.locator("#signup-password").fill(signup_password)
                page.locator("#signup-confirmation").fill(signup_password)
                mark_stage("creating an account through the real signup API")
                sessions_before_signup = len(session_observations)
                with page.expect_response(
                    lambda candidate: (
                        _response_path(candidate) == "/api/v2/auth/signup"
                        and candidate.request.method == "POST"
                    )
                ) as signup_response_info:
                    page.get_by_role(
                        "button", name="Create account", exact=True
                    ).click()
                signup_response = signup_response_info.value
                _require(
                    signup_response.status == 201,
                    "account registration did not succeed against the Rust API",
                )
                try:
                    signup_request = json.loads(signup_response.request.post_data or "")
                    signup_envelope = signup_response.json()
                except (TypeError, ValueError):
                    raise AcceptanceFailure(
                        "the signup API returned an unreadable result"
                    ) from None
                _require(
                    signup_request
                    == {
                        "email": signup_email,
                        "password": signup_password,
                    },
                    "signup sent unexpected fields to the Rust API",
                )
                _require(
                    any(
                        path == "/api/v2/auth/signup" and has_csrf
                        for path, has_csrf in csrf_requests
                    ),
                    "signup did not send its pre-authentication CSRF header",
                )
                signup_data = (
                    signup_envelope.get("data")
                    if isinstance(signup_envelope, dict)
                    else None
                )
                _require(
                    isinstance(signup_data, dict)
                    and isinstance(signup_data.get("id"), int)
                    and not isinstance(signup_data.get("id"), bool)
                    and signup_data.get("email") == signup_email
                    and signup_data.get("is_admin") is False,
                    "signup returned an unexpected member account",
                )
                _require(
                    page.locator(".app-shell").count() == 0,
                    "signup implicitly authenticated the new member",
                )
                page.wait_for_url("**/app/login?created=1")
                page.get_by_text(
                    "Your account is ready. Sign in with your email and password.",
                    exact=True,
                ).wait_for(state="visible")
                _require(
                    any(
                        status == 200 and user_null and csrf
                        for status, user_null, csrf, _ in session_observations[
                            sessions_before_signup:
                        ]
                    ),
                    "signup did not refresh an anonymous session and CSRF token",
                )
                page.goto(f"{origin}/app/signup", wait_until="networkidle")
                page.locator("#signup-email").fill(signup_email)
                page.locator("#signup-password").fill(signup_password)
                page.locator("#signup-confirmation").fill(signup_password)
                mark_stage("checking duplicate-account recovery copy")
                expected_signup_conflict_window[0] = True
                try:
                    with page.expect_response(
                        lambda candidate: (
                            _response_path(candidate) == "/api/v2/auth/signup"
                            and candidate.request.method == "POST"
                        )
                    ) as duplicate_response_info:
                        page.get_by_role(
                            "button", name="Create account", exact=True
                        ).click()
                finally:
                    expected_signup_conflict_window[0] = False
                _require(
                    duplicate_response_info.value.status == 409,
                    "a duplicate signup did not return the account conflict",
                )
                page.get_by_text(
                    "An account already exists for this email.", exact=True
                ).wait_for(state="visible")
                page.goto(f"{origin}/app/login?created=1", wait_until="networkidle")
                page.locator("#email").wait_for(state="visible")
                page.locator("#email").fill(signup_email)
                page.locator("#password").fill(signup_password)
                mark_stage("explicitly signing in with the new account")
                page.get_by_role("button", name="Sign in", exact=True).click()
                page.locator(".app-shell").wait_for(state="visible")
                page.set_viewport_size({"width": 1280, "height": 844})
                _require(
                    any(
                        status == 200 and email == signup_email
                        for status, email in session_identity_observations[
                            sessions_before_signup:
                        ]
                    ),
                    "the explicit sign-in session did not identify the new account",
                )
                mark_stage("returning to anonymous sign-in after signup check")
                _open_account_from_profile(page)
                _require(
                    page.locator(".profile-email").inner_text() == signup_email,
                    "the profile did not show the signed-in account identity",
                )
                _account_sign_out_button(page).click()
                page.wait_for_url("**/app/login", timeout=10_000)
                page.locator("#email").wait_for(state="visible")
                browser_email = email
                browser_password = password
            else:
                _require(
                    page.get_by_role(
                        "link", name="Create an account", exact=True
                    ).count()
                    == 0,
                    "signup was linked while the server capability was disabled",
                )
                mark_stage("direct route for disabled account registration")
                disabled_response = page.goto(
                    f"{origin}/app/signup", wait_until="networkidle"
                )
                _require(
                    disabled_response is not None and disabled_response.status == 200,
                    "direct signup route did not load its unavailable state",
                )
                page.get_by_role(
                    "heading", name="Account registration is unavailable.", exact=True
                ).wait_for(state="visible")
                _require(
                    page.locator("#signup-password").count() == 0,
                    "the disabled signup route exposed its account form",
                )
                page.goto(f"{origin}/app/login", wait_until="networkidle")
                page.locator("#email").wait_for(state="visible")

            gauge_link.click()
            page.wait_for_url("**/app/gauge")
            page.get_by_role("heading", name="Gauge calculator", exact=True).wait_for(
                state="visible"
            )
            _require(
                page.locator(".public-tool-shell").count() == 1
                and page.locator(".app-shell").count() == 0,
                "the signed-out gauge link opened a private shell",
            )
            page.goto(f"{origin}/app/login", wait_until="networkidle")
            page.locator("#email").wait_for(state="visible")

            policy = response.headers.get("content-security-policy", "")
            _require(
                "default-src 'self'" in policy
                and "script-src 'self'" in policy
                and "style-src 'self'" in policy
                and "unsafe-inline" not in policy
                and "unsafe-eval" not in policy
                and "sha256-" in policy,
                "strict frontend CSP was not attached to the app shell",
            )
            _require(
                bool(app_asset_statuses)
                and all(status == 200 for status in app_asset_statuses),
                "a bundled app asset failed to load",
            )
            _require(
                bool(catalog_statuses)
                and all(status == 200 for status in catalog_statuses),
                "the public translation catalog failed to load",
            )
            favicon_response = page.request.get(f"{origin}/app/favicon.svg")
            _require(favicon_response.status == 200, "the app favicon failed to load")
            _require(
                any(
                    status == 200 and user_null and csrf
                    for status, user_null, csrf, _ in session_observations
                ),
                "anonymous session bootstrap did not provide CSRF state",
            )

            mark_stage("anonymous responsive layout")
            for width in (320, 390, 768, 1280):
                page.set_viewport_size({"width": width, "height": 844})
                _assert_no_horizontal_overflow(page, width)
                form_heights = _visible_heights(
                    page, "#email, #password, .auth-form button"
                )
                signed_out_tool_heights = _visible_heights(
                    page, ".auth-footer a, .auth-footer .locale-switcher button"
                )
                _require(
                    len(form_heights) == 3 and min(form_heights) >= 44,
                    "a sign-in control is below the 44px target size",
                )
                _require(
                    len(signed_out_tool_heights) == 3
                    and min(signed_out_tool_heights) >= 44,
                    "a signed-out tool or language control is below 44px",
                )
            page.set_viewport_size({"width": 390, "height": 844})
            _prepare_screenshot(page)
            page.screenshot(
                path=str(screenshots / "signed-out-mobile.png"), caret="initial"
            )

            mark_stage("real password login and session cookie")
            page.locator("#email").fill(browser_email)
            page.locator("#password").fill(browser_password)
            page.get_by_role("button", name="Sign in").click()
            mark_stage("waiting for authenticated app shell")
            page.locator(".app-shell").wait_for(state="visible")
            mark_stage("waiting for authenticated mobile navigation")
            page.locator(".mobile-navigation").wait_for(state="visible")
            _assert_mobile_app_header(page)
            current_route = urlsplit(page.url).path
            mark_stage(f"checking project route after login at {current_route}")
            _require(
                current_route.rstrip("/") == "/app/projects",
                "password login did not open the project library route",
            )
            _require(
                any(
                    path == "/api/v2/auth/login" and has_csrf
                    for path, has_csrf in csrf_requests
                ),
                "login did not send the CSRF header",
            )
            _require(
                any(
                    status == 200 and numeric
                    for status, _, _, numeric in session_observations
                ),
                "authenticated session did not expose a numeric user ID",
            )
            login_response = next(
                (
                    status
                    for path, status in auth_responses
                    if path == "/api/v2/auth/login"
                ),
                None,
            )
            _require(
                login_response == 200,
                "password login did not succeed against the Rust API",
            )

            cookies = context.cookies(origin)
            session_cookie = next(
                (
                    cookie
                    for cookie in cookies
                    if cookie["name"] == "stricknani_session"
                ),
                None,
            )
            _require(
                session_cookie is not None
                and session_cookie.get("httpOnly") is True
                and session_cookie.get("sameSite") == "Lax",
                "login did not establish the expected protected browser session cookie",
            )
            cookies.clear()
            session_cookie = None
            mark_stage("preparing the synthetic project reader fixture")
            project_id = _prepare_project_reader_fixture(page)
            mark_stage("opening the authenticated project library")
            page.goto(f"{origin}/app/projects", wait_until="networkidle")
            mark_stage("waiting for the seeded project card")
            try:
                page.get_by_role(
                    "heading", name="Heirloom Baby Blanket", exact=True
                ).wait_for(state="visible", timeout=10_000)
            except PlaywrightTimeoutError:
                list_statuses = [
                    status
                    for path, status in project_responses
                    if path == "/api/v2/projects"
                ]
                api_status = list_statuses[-1] if list_statuses else "not observed"
                list_size = (
                    project_list_sizes[-1] if project_list_sizes else "not observed"
                )
                card_count = page.locator(".project-card").count()
                empty_count = page.locator(".empty-state").count()
                loading = page.locator(".loading-grid").count() > 0
                ui_error_visible = page.locator(".notice-error").count() > 0
                library_count = page.locator(".library").count()
                debug_screenshot = screenshots / "project-library-debug.png"
                _mask_fixture_identity(page)
                _prepare_screenshot(page)
                page.screenshot(path=str(debug_screenshot), caret="initial")
                raise AcceptanceFailure(
                    "seeded project did not render "
                    f"(API status {api_status}; list items={list_size}; "
                    f"cards={card_count}; empty state={empty_count}; "
                    f"still loading={loading}; UI error visible={ui_error_visible}; "
                    f"library component={library_count}; screenshot={debug_screenshot})"
                ) from None
            _require(
                any(
                    path == "/api/v2/projects" and status == 200
                    for path, status in project_responses
                ),
                "the real project library API did not load",
            )
            duplicate_tag_buttons = (
                page.locator(".project-card")
                .filter(
                    has=page.get_by_role(
                        "heading", name="Heirloom Baby Blanket", exact=True
                    )
                )
                .locator(".tag-list button")
            )
            _require(
                duplicate_tag_buttons.all_inner_texts()
                == ["heirloom", "textured", "textured"],
                "the project library collapsed or misrendered repeated tag labels",
            )

            mobile_links = page.locator(".mobile-navigation a")
            _require(
                mobile_links.count() == 4, "signed-in mobile navigation is incomplete"
            )
            expected_links = {
                "Projects": "/app/projects",
                "Yarns": "/app/yarns",
                "Create": "/app/create",
                "More": "/app/more",
            }
            for label, href in expected_links.items():
                link = page.locator(".mobile-navigation a").filter(has_text=label)
                _require(
                    link.count() == 1 and link.get_attribute("href") == href,
                    "a mobile navigation link has the wrong destination",
                )
            mark_stage("project library search and URL filters")
            project_card = page.locator(".project-card").filter(
                has=page.get_by_role(
                    "heading", name="Heirloom Baby Blanket", exact=True
                )
            )
            _require(project_card.count() == 1, "the seeded project card is missing")
            project_photo = project_card.locator(".resource-card__image img")
            _require(
                project_photo.count() == 1
                and project_photo.evaluate("image => image.src.includes('/media/')"),
                "the project card did not use the seeded same-origin photo",
            )
            search_box = page.get_by_role("searchbox", name="Search your projects")
            search_box.fill("Heirloom")
            mark_stage("searching project library by name")
            with page.expect_response(
                lambda response: (
                    urlsplit(response.url).path == "/api/v2/projects"
                    and parse_qs(urlsplit(response.url).query).get("q") == ["Heirloom"]
                )
            ) as search_response_info:
                page.get_by_role("button", name="Search", exact=True).click()
            _require(
                search_response_info.value.status == 200,
                "project-name search did not succeed against the Rust API",
            )
            page.locator(".project-card").wait_for(state="visible")
            _require(
                page.locator(".project-card").count() == 1,
                "project search did not return the matching card",
            )
            mark_stage("filtering project library by category")
            selected_category: list[str] = []
            category_select = page.get_by_label("Category", exact=True)
            try:
                category_select.wait_for(state="visible", timeout=5_000)
            except PlaywrightTimeoutError:
                _mask_fixture_identity(page)
                _prepare_screenshot(page)
                category_debug = page.evaluate(
                    """() => ({
                      selectCount: document.querySelectorAll('select').length,
                      libraryCount: document.querySelectorAll('.library').length,
                      visible: document.visibilityState === 'visible',
                      hasBody: Boolean(document.body),
                    })"""
                )
                category_debug_screenshot = screenshots / "category-stage-debug.png"
                page.screenshot(path=str(category_debug_screenshot), caret="initial")
                raise AcceptanceFailure(
                    "project category control did not become visible "
                    f"(diagnostics={category_debug}; "
                    f"native select count={page.locator('select').count()}; "
                    f"exact Category label count={category_select.count()})"
                ) from None
            _require(category_select.count() == 1, "category filter is ambiguous")
            _require(
                category_select.evaluate("select => select.labels.length > 0"),
                "the project category filter is not programmatically labeled",
            )
            try:
                with page.expect_response(
                    lambda response: (
                        urlsplit(response.url).path == "/api/v2/projects"
                        and parse_qs(urlsplit(response.url).query).get("category")
                        == ["Schal"]
                    ),
                    timeout=10_000,
                ) as category_filter_response_info:
                    selected_category = category_select.select_option(
                        label="Schal", timeout=5_000
                    )
            except PlaywrightTimeoutError:
                category_in_url = parse_qs(urlsplit(page.url).query).get(
                    "category"
                ) == ["Schal"]
                category_request_seen = any(
                    query.get("category") == ["Schal"] for query in project_list_queries
                )
                category_value_selected = (
                    category_select.input_value(timeout=1_000) == "Schal"
                )
                raise AcceptanceFailure(
                    "category filter did not reach the project API "
                    f"(URL updated={category_in_url}; "
                    f"request observed={category_request_seen}; "
                    f"select returned value={selected_category == ['Schal']}; "
                    f"selected in DOM={category_value_selected})"
                ) from None
            _require(
                category_filter_response_info.value.status == 200,
                "category filtering did not succeed against the Rust API",
            )
            page.locator(".project-card").wait_for(state="visible")
            _require(
                any(
                    path == "/api/v2/categories" and status == 200
                    for path, status in category_responses
                ),
                "project categories did not load from the Rust API",
            )
            mark_stage("filtering project library by tag")
            with page.expect_response(
                lambda response: (
                    urlsplit(response.url).path == "/api/v2/projects"
                    and parse_qs(urlsplit(response.url).query).get("tag")
                    == ["heirloom"]
                )
            ) as tag_filter_response_info:
                page.get_by_role("button", name="heirloom", exact=True).click()
            _require(
                tag_filter_response_info.value.status == 200,
                "tag filtering did not succeed against the Rust API",
            )
            page.locator(".project-card").wait_for(state="visible")
            search_box = page.get_by_role("searchbox", name="Search your projects")
            search_box.fill("no such knitting project")
            mark_stage("checking the project no-match state")
            with page.expect_response(
                lambda response: (
                    urlsplit(response.url).path == "/api/v2/projects"
                    and parse_qs(urlsplit(response.url).query).get("q")
                    == ["no such knitting project"]
                )
            ) as no_match_response_info:
                page.get_by_role("button", name="Search", exact=True).click()
            _require(
                no_match_response_info.value.status == 200,
                "no-match search did not succeed against the Rust API",
            )
            page.get_by_role(
                "heading", name="No projects match those filters."
            ).wait_for(state="visible")
            _require(
                page.locator(".project-card").count() == 0,
                "the no-match state still showed a project",
            )
            mark_stage("clearing project filters")
            with page.expect_response(
                lambda response: (
                    urlsplit(response.url).path == "/api/v2/projects"
                    and not urlsplit(response.url).query
                )
            ) as clear_filters_response_info:
                page.locator(".filters").get_by_role(
                    "button", name="Clear filters", exact=True
                ).click()
            _require(
                clear_filters_response_info.value.status == 200,
                "clearing filters did not reload the project list",
            )
            page.get_by_role(
                "heading", name="Heirloom Baby Blanket", exact=True
            ).wait_for(state="visible")
            _assert_mobile_app_header(page)
            project_card = page.locator(".project-card").filter(
                has=page.get_by_role(
                    "heading", name="Heirloom Baby Blanket", exact=True
                )
            )
            mark_stage("saving a project favorite")
            with page.expect_response(
                lambda response: (
                    urlsplit(response.url).path.endswith("/favorite")
                    and response.request.method == "PUT"
                )
            ) as favorite_response_info:
                page.get_by_role("button", name="Add to favorites").click()
            _require(
                favorite_response_info.value.status == 200,
                "saving the project favorite failed against the Rust API",
            )
            page.get_by_role("button", name="Remove from favorites").wait_for(
                state="visible"
            )
            mark_stage("filtering project library by favorites")
            with page.expect_response(
                lambda response: (
                    urlsplit(response.url).path == "/api/v2/projects"
                    and parse_qs(urlsplit(response.url).query).get("favorite")
                    == ["true"]
                )
            ) as favorite_filter_response_info:
                page.get_by_label("Favorites", exact=True).check()
            _require(
                favorite_filter_response_info.value.status == 200,
                "favorite filtering did not succeed against the Rust API",
            )
            page.locator(".project-card").wait_for(state="visible")
            _require(
                page.locator(".project-card").count() == 1,
                "favorite filtering did not keep the saved project",
            )
            _require(
                any(
                    path.endswith("/favorite") and has_csrf
                    for path, has_csrf in csrf_requests
                ),
                "the favorite action did not send CSRF",
            )
            _require(
                any(
                    path.endswith("/favorite") and status == 200
                    for path, status in project_responses
                ),
                "the favorite action did not persist through the Rust API",
            )
            mark_stage("checking mobile account language controls")
            _check_mobile_language_and_screenshots(
                page, origin, screenshots, mark_stage
            )
            mark_stage("authenticated responsive layout")
            for width in (320, 390, 768, 1280):
                page.set_viewport_size({"width": width, "height": 844})
                _assert_no_horizontal_overflow(page, width)
                if width == 320:
                    _require(
                        page.locator(
                            ".app-header .brand-lockup .brand-title"
                        ).is_visible()
                        and page.locator(
                            ".app-header .brand-lockup .brand-version"
                        ).is_visible(),
                        "the brand title or version is not visible in the 320px header",
                    )
                if width == 768:
                    metrics = page.evaluate(
                        """() => {
                          const card = document.querySelector('.project-card');
                          const rect = card?.getBoundingClientRect();
                          return {
                            viewport: window.innerWidth,
                            clientWidth: document.documentElement.clientWidth,
                            scrollWidth: document.documentElement.scrollWidth,
                            cardLeft: rect?.left ?? null,
                            cardRight: rect?.right ?? null,
                            cardWidth: rect?.width ?? null,
                          };
                        }"""
                    )
                    print(
                        "Project library viewport at 768px: "
                        + json.dumps(metrics, sort_keys=True)
                    )
                selector = (
                    ".mobile-navigation a"
                    if width <= 760
                    else ".primary-nav a, .secondary-nav a"
                )
                heights = _visible_heights(page, selector)
                if not heights:
                    responsive_failures.append(
                        f"no signed-in navigation targets were visible at {width}px"
                    )
                elif min(heights) < 44:
                    responsive_failures.append(
                        f"signed-in navigation target measured {min(heights):.1f}px "
                        f"at {width}px"
                    )
                _require(
                    page.evaluate(
                        "matchMedia('(prefers-reduced-motion: reduce)').matches"
                    ),
                    "reduced-motion preference was not honored by the browser context",
                )
                _require(
                    page.evaluate(
                        "getComputedStyle(document.documentElement).scrollBehavior"
                    )
                    == "auto",
                    "reduced-motion scroll behavior was not applied",
                )
            page.set_viewport_size({"width": 390, "height": 844})
            mark_stage("project reader, ordered steps, media, and print")
            full_resolution_requests_before_reader = len(
                original_project_image_requests
            )
            with page.expect_response(
                lambda response: (
                    urlsplit(response.url).path == f"/api/v2/projects/{project_id}"
                    and response.request.method == "GET"
                )
            ) as reader_response_info:
                page.locator(
                    f".project-card a[href='/app/projects/{project_id}']"
                ).click()
            page.wait_for_url(f"**/app/projects/{project_id}")
            reader_response = reader_response_info.value
            _require(reader_response.status == 200, "project detail API failed")
            mark_stage("checking project reader title and ordered steps")
            page.get_by_role(
                "heading", name="Heirloom Baby Blanket", exact=True
            ).wait_for(state="visible")
            step_titles = page.locator(".steps-list h3").all_inner_texts()
            _require(
                step_titles
                == [
                    "Cast on and knit the border",
                    "Work the textured panel",
                ],
                "the reader did not display pattern steps in order",
            )
            reader_steps = reader_response.json().get("data", {}).get("steps", [])
            step_ids = [step.get("id") for step in reader_steps]
            _require(
                len(step_ids) == 2
                and all(isinstance(step_id, int) for step_id in step_ids),
                "the reader fixture did not return two numeric step IDs",
            )
            reader_tags = page.locator(".reader-tags li").all_inner_texts()
            _require(
                reader_tags == ["heirloom", "textured", "textured"],
                "the project reader collapsed or misrendered repeated tag labels",
            )
            _exercise_project_service_tools(
                page,
                origin,
                project_id,
                service_fixture_enabled,
                ocr_mode,
                archive_mode,
                mark_stage,
                screenshots,
            )
            project_data = reader_response.json().get("data", {})
            yarn_ids = project_data.get("yarn_ids", [])
            if (
                not isinstance(yarn_ids, list)
                or not yarn_ids
                or not isinstance(yarn_ids[0], int)
            ):
                raise AcceptanceFailure(
                    "the project fixture did not return a linked yarn ID"
                )
            yarn_id = yarn_ids[0]
            mark_stage("checking OCR and archive controls on linked yarn photos")
            page.goto(f"{origin}/app/yarns/{yarn_id}", wait_until="networkidle")
            page.get_by_role(
                "heading", name="Riverbend Merino DK", exact=True
            ).wait_for(state="visible")
            _assert_mobile_app_header(page)
            _exercise_yarn_service_tools(
                page,
                origin,
                yarn_id,
                service_fixture_enabled,
                ocr_mode,
                archive_mode,
                mark_stage,
                screenshots,
            )
            mark_stage("measuring the yarn library at tablet width")
            page.goto(f"{origin}/app/yarns", wait_until="networkidle")
            page.get_by_role(
                "heading", name="Riverbend Merino DK", exact=True
            ).wait_for(state="visible")
            page.set_viewport_size({"width": 768, "height": 844})
            _assert_no_horizontal_overflow(page, 768)
            yarn_metrics = page.evaluate(
                """() => {
                  const card = document.querySelector('.yarn-card');
                  const rect = card?.getBoundingClientRect();
                  return {
                    viewport: window.innerWidth,
                    clientWidth: document.documentElement.clientWidth,
                    scrollWidth: document.documentElement.scrollWidth,
                    cardLeft: rect?.left ?? null,
                    cardRight: rect?.right ?? null,
                    cardWidth: rect?.width ?? null,
                  };
                }"""
            )
            print(
                "Yarn library viewport at 768px: "
                + json.dumps(yarn_metrics, sort_keys=True)
            )
            page.set_viewport_size({"width": 390, "height": 844})
            page.goto(f"{origin}/app/projects/{project_id}", wait_until="networkidle")
            page.get_by_role(
                "heading", name="Heirloom Baby Blanket", exact=True
            ).wait_for(state="visible")
            mark_stage(
                "checking step progress, hash links, and previous/next navigation"
            )
            step_progress = page.locator(".step-progress")
            step_progress.wait_for(state="visible")
            _require(
                step_progress.inner_text() == "Step 1 of 2",
                "reader progress did not start at the first ordered step",
            )
            next_step = page.locator(".next-step-link")
            _require(
                next_step.get_attribute("href") == f"#step-{step_ids[1]}",
                "next-step control did not expose the expected addressable hash",
            )
            next_step.click()
            page.wait_for_function(
                "stepId => location.hash === `#step-${stepId}`",
                arg=step_ids[1],
            )
            _require(
                step_progress.inner_text() == "Step 2 of 2"
                and page.locator(f"#step-{step_ids[1]}").get_attribute("aria-current")
                == "step",
                "next-step navigation did not update the current-step indicator",
            )
            page.get_by_role("link", name=re.compile("Previous step")).click()
            page.wait_for_function(
                "stepId => location.hash === `#step-${stepId}`",
                arg=step_ids[0],
            )
            _require(
                step_progress.inner_text() == "Step 1 of 2",
                "previous-step navigation did not restore the first step",
            )
            page.locator(".next-step-link").click()
            page.wait_for_function(
                "stepId => location.hash === `#step-${stepId}`",
                arg=step_ids[1],
            )
            mark_stage("restoring step position after returning to the library")
            page.get_by_role("link", name="Back to projects").first.click()
            page.wait_for_url("**/app/projects")
            page.locator(
                f".project-card a.resource-card__link"
                f"[href='/app/projects/{project_id}']"
            ).click()
            page.wait_for_url(f"**/app/projects/{project_id}**")
            page.wait_for_function(
                "stepId => location.hash === `#step-${stepId}`",
                arg=step_ids[1],
            )
            page.locator(".step-progress").wait_for(state="visible")
            _require(
                page.locator(".step-progress").inner_text() == "Step 2 of 2",
                "the reader did not restore the last step after "
                "returning from the library",
            )
            _assert_step_in_usable_viewport(page, step_ids[1])
            mark_stage("opening a direct reader step link")
            page.goto(
                f"{origin}/app/projects/{project_id}#step-{step_ids[0]}",
                wait_until="networkidle",
            )
            page.locator(".step-progress").wait_for(state="visible")
            _require(
                page.locator(".step-progress").inner_text() == "Step 1 of 2",
                "direct hash navigation did not open the requested step",
            )
            _assert_step_in_usable_viewport(page, step_ids[0])
            mark_stage("checking the seeded project-step reference photo")
            step_photo = page.locator(".steps-list .step-image .screen-reference-image")
            step_photo.wait_for(state="visible", timeout=5_000)
            step_photo.scroll_into_view_if_needed()
            step_photo.evaluate("image => image.decode()")
            _require(
                step_photo.evaluate(
                    "image => image.complete && image.naturalWidth > 0 "
                    "&& image.currentSrc.includes('/media/thumbnails/projects/')"
                ),
                "the project-step reference photo did not load from same-origin media",
            )
            _require(
                len(original_project_image_requests)
                == full_resolution_requests_before_reader,
                "normal pattern reading requested full-resolution project photos",
            )
            mark_stage("checking sanitized pattern text")
            description = page.locator(".pattern-section > .reading-copy > .rich-text")
            sanitized_text = description.locator("strong")
            if sanitized_text.count() != 1 or sanitized_text.inner_text() != (
                "textured blanket"
            ):
                text_debug = page.locator(".pattern-section .rich-text").evaluate_all(
                    "nodes => nodes.map(node => ({"
                    "strongCount: node.querySelectorAll('strong').length, "
                    "textLength: node.textContent?.length ?? 0, "
                    "hasFixturePhrase: "
                    "node.textContent?.includes('textured blanket') ?? false, "
                    "hasMarkdownMarkers: node.textContent?.includes('**') ?? false"
                    "}))"
                )
                _mask_fixture_identity(page)
                _prepare_screenshot(page)
                page.screenshot(
                    path=str(screenshots / "reader-text-debug.png"),
                    caret="initial",
                )
                raise AcceptanceFailure(
                    "sanitized description structure did not match "
                    f"the fixture (blocks={text_debug}; "
                    f"strong elements={sanitized_text.count()})"
                )
            _require(
                "**" not in description.inner_text(),
                "raw Markdown was displayed in the pattern reader",
            )
            mark_stage("checking linked yarn API details")
            page.get_by_text("Riverbend Merino DK", exact=True).wait_for(
                state="visible", timeout=5_000
            )
            _require(
                any(
                    path.startswith("/api/v2/yarns/") and status == 200
                    for path, status in yarn_responses
                ),
                "linked yarn details did not load from the Rust API",
            )
            _require(
                page.locator(".gallery-image").count() == 1,
                "the single-image project fixture changed unexpectedly",
            )
            page.set_viewport_size({"width": 320, "height": 844})
            mark_stage("opening the single seeded project photo at 320px")
            page.locator(".gallery-image").first.scroll_into_view_if_needed()
            page.locator(".gallery-image").first.click()
            dialog = page.get_by_role("dialog", name="Project image viewer")
            dialog.wait_for(state="visible")
            lightbox_image = dialog.locator("img")
            lightbox_image.wait_for(state="visible")
            lightbox_image.evaluate("image => image.decode()")
            image_bounds = lightbox_image.bounding_box()
            _require(
                lightbox_image.evaluate(
                    "image => image.complete && image.naturalWidth > 0"
                )
                and image_bounds is not None
                and image_bounds["width"] >= 180
                and image_bounds["height"] >= 140,
                "the seeded photo did not load at a useful size in the 320px viewer",
            )
            mark_stage("checking Escape close and focus restoration")
            page.keyboard.press("Escape")
            dialog.wait_for(state="hidden")
            _require(
                page.evaluate(
                    "document.activeElement === "
                    "document.querySelector('.gallery-image')"
                ),
                "closing the image viewer did not restore focus",
            )
            page.set_viewport_size({"width": 390, "height": 844})
            mark_stage("checking reader text size and print layout")
            reading_copy = page.locator(".pattern-section > .reading-copy")
            small_font = float(
                reading_copy.evaluate(
                    "element => getComputedStyle(element).fontSize"
                ).removesuffix("px")
            )
            page.get_by_role("button", name="Increase text size").click()
            try:
                page.wait_for_function(
                    "initial => {"
                    "const element = document.querySelector("
                    "'.pattern-section > .reading-copy'); "
                    "return element !== null && "
                    "parseFloat(getComputedStyle(element).fontSize) > initial;"
                    "}",
                    arg=small_font,
                    timeout=5_000,
                )
            except PlaywrightTimeoutError:
                raise AcceptanceFailure(
                    "reader text-size control did not update the description text "
                    f"(class={reading_copy.get_attribute('class')}; "
                    f"font size={
                        reading_copy.evaluate(
                            'element => getComputedStyle(element).fontSize'
                        )
                    })"
                ) from None
            large_font = float(
                reading_copy.evaluate(
                    "element => getComputedStyle(element).fontSize"
                ).removesuffix("px")
            )
            _require(
                large_font > small_font, "reader text-size control did not grow text"
            )
            mark_stage("checking complete pattern instructions in print")
            blocked_print_image_requests: list[dict[str, object]] = []

            def block_print_image(route: Route) -> None:
                request = route.request
                blocked_print_image_requests.append(
                    {
                        "path": urlsplit(request.url).path,
                        "get": request.method == "GET",
                        "image": request.resource_type == "image",
                        "project_media": urlsplit(request.url).path.startswith(
                            "/media/projects/"
                        ),
                    }
                )
                route.abort()

            page.route("**/media/projects/**", block_print_image)
            expected_print_abort_window[0] = True
            mark_stage("installing print failure instrumentation")
            page.evaluate(
                "() => { "
                "window.__printDialogCalls = 0; "
                "window.__expectedPrintUrls = Array.from(document.querySelectorAll("
                "'.print-reference-image')).map(image => { try { return "
                "new URL(image.dataset.printSrc, location.href).href; } "
                "catch { return ''; } }); "
                "window.__printImagesAtCall = []; "
                "window.print = () => { "
                "window.__printDialogCalls += 1; "
                "window.__printImagesAtCall = Array.from(document.querySelectorAll("
                "'.print-reference-image')).map((image, index) => ({ "
                "hasSource: Boolean(image.getAttribute('src')), "
                "complete: image.complete, loaded: image.naturalWidth > 0, "
                "currentSrcEmpty: image.currentSrc === '', "
                "srcEqualsOriginal: image.src === window.__expectedPrintUrls[index], "
                "currentSrcEqualsOriginal: image.currentSrc === "
                "window.__expectedPrintUrls[index] })); }; "
                "}"
            )
            mark_stage("checking failed print image baseline")
            print_images_before_click = page.locator(
                ".print-reference-image"
            ).evaluate_all(
                "images => images.map((image, index) => ({hasSource: "
                "Boolean(image.getAttribute('src')), complete: image.complete, "
                "loaded: image.naturalWidth > 0, "
                "currentSrcEmpty: image.currentSrc === '', "
                "srcEqualsOriginal: image.src === "
                "window.__expectedPrintUrls[index], "
                "currentSrcEqualsOriginal: image.currentSrc === "
                "window.__expectedPrintUrls[index]}))"
            )
            mark_stage("clicking print with blocked reference photo")
            page.get_by_role("button", name="Print pattern", exact=True).click()
            mark_stage("waiting for blocked print image error")
            try:
                page.get_by_role("alert").filter(
                    has_text=(
                        "A reference photo could not be prepared for printing. "
                        "Try again."
                    )
                ).wait_for(state="visible", timeout=5_000)
            except Exception:
                try:
                    print_failure_diagnostic = page.evaluate(
                        "() => ({path: location.pathname, lang: "
                        "document.documentElement.lang, alerts: Array.from("
                        "document.querySelectorAll('[role=alert]')).map(node => "
                        "node.textContent?.trim() ?? ''), button: (() => { "
                        "const node = document.querySelector('.print-button'); "
                        "return node ? {disabled: node.disabled, "
                        "text: node.textContent?.trim() ?? ''} : null; })(), "
                        "calls: window.__printDialogCalls, "
                        "images: Array.from(document.querySelectorAll("
                        "'.print-reference-image')).map((image, index) => ({ "
                        "hasSource: Boolean(image.getAttribute('src')), "
                        "complete: image.complete, loaded: image.naturalWidth > 0, "
                        "currentSrcEmpty: image.currentSrc === '', "
                        "srcEqualsOriginal: image.src === "
                        "window.__expectedPrintUrls[index], "
                        "currentSrcEqualsOriginal: image.currentSrc === "
                        "window.__expectedPrintUrls[index] }))})"
                    )
                except Exception as diagnostic_error:
                    print_failure_diagnostic = {
                        "path": "unavailable",
                        "diagnosticError": type(diagnostic_error).__name__,
                    }
                raise AcceptanceFailure(
                    "failed image did not produce the localized print error; "
                    + json.dumps(print_failure_diagnostic, sort_keys=True)
                ) from None
            mark_stage("reading failed print image state")
            failed_print_state = page.evaluate(
                "() => ({calls: window.__printDialogCalls, "
                "imagesAtCall: window.__printImagesAtCall, "
                "images: Array.from(document.querySelectorAll("
                "'.print-reference-image')).map((image, index) => ({ "
                "hasSource: Boolean(image.getAttribute('src')), "
                "complete: image.complete, loaded: image.naturalWidth > 0, "
                "currentSrcEmpty: image.currentSrc === '', "
                "srcEqualsOriginal: image.src === "
                "window.__expectedPrintUrls[index], "
                "currentSrcEqualsOriginal: image.currentSrc === "
                "window.__expectedPrintUrls[index] }))})"
            )
            _require(
                failed_print_state["calls"] == 0
                and len(blocked_print_image_requests) >= 1
                and all(
                    request["get"] and request["image"] and request["project_media"]
                    for request in blocked_print_image_requests
                ),
                "printing continued after a reference photo failed to load "
                f"(print_calls={failed_print_state['calls']}, "
                f"blocked_requests={len(blocked_print_image_requests)}, "
                f"before_click={print_images_before_click}, "
                f"images_at_call={failed_print_state['imagesAtCall']}, "
                f"images={failed_print_state['images']})",
            )
            mark_stage("restoring print test overrides")
            page.unroute("**/media/projects/**")
            expected_print_abort_window[0] = False
            mark_stage("waiting for failed print attempt to finish")
            try:
                page.wait_for_function(
                    "() => { const button = document.querySelector('.print-button'); "
                    "return button !== null && !button.disabled; }",
                    timeout=5_000,
                )
            except Exception:
                try:
                    retry_state = page.evaluate(
                        "() => ({button: (() => { const node = "
                        "document.querySelector('.print-button'); return node ? "
                        "{disabled: node.disabled, "
                        "text: node.textContent?.trim() ?? ''} "
                        ": null; })(), calls: window.__printDialogCalls, "
                        "images: Array.from(document.querySelectorAll("
                        "'.print-reference-image')).map(image => ({complete: "
                        "image.complete, loaded: image.naturalWidth > 0}))})"
                    )
                except Exception as diagnostic_error:
                    retry_state = {
                        "pageClosed": page.is_closed(),
                        "diagnosticError": type(diagnostic_error).__name__,
                    }
                raise AcceptanceFailure(
                    "failed print attempt did not release the retry control; "
                    + json.dumps(retry_state, sort_keys=True)
                ) from None
            success_request_start = len(original_project_image_requests)
            success_response_start = len(original_project_image_responses)
            mark_stage("installing successful print instrumentation")
            page.evaluate(
                "() => { window.__printDialogCalls = 0; "
                "window.__printEvents = []; "
                "window.__printImageExpectedUrls = Array.from("
                "document.querySelectorAll('.print-reference-image')).map(image => "
                "{ try { return new URL(image.dataset.printSrc, location.href).href; } "
                "catch { return ''; } }); "
                "window.__printImageState = []; "
                "window.print = () => { "
                "window.__printDialogCalls += 1; "
                "window.__printStack = new Error().stack; "
                "window.__printImageState = Array.from(document.querySelectorAll("
                "'.print-reference-image')).map((image, index) => ({ "
                "complete: image.complete, loaded: image.naturalWidth > 0, "
                "sameOrigin: image.currentSrc.startsWith(location.origin + '/'), "
                "path: image.currentSrc.includes('/media/projects/'), "
                "currentSrcEmpty: image.currentSrc === '', "
                "srcEqualsOriginal: image.src === "
                "window.__printImageExpectedUrls[index], "
                "currentSrcEqualsOriginal: image.currentSrc === "
                "window.__printImageExpectedUrls[index] })); }; }"
            )
            mark_stage("clicking successful print action")
            page.get_by_role("button", name="Print pattern", exact=True).click()
            mark_stage("waiting for successful print call")
            print_wait_error_type: str | None = None
            try:
                page.wait_for_function(
                    "() => window.__printDialogCalls > 0", timeout=20_000
                )
            except Exception as wait_error:
                print_wait_error_type = type(wait_error).__name__
                print_wait_error_summary = str(wait_error).splitlines()[0]
                print_wait_error_summary = re.sub(
                    r"https?://[^\s]+", "<url>", print_wait_error_summary
                )[:160]
                try:
                    successful_print_diagnostic = page.evaluate(
                        "() => ({path: location.pathname, calls: "
                        "window.__printDialogCalls, events: window.__printEvents, "
                        "stack: window.__printStack || '', "
                        "button: (() => { const node = document.querySelector("
                        "'.print-button'); return node ? {disabled: node.disabled, "
                        "text: node.textContent?.trim() ?? ''} : null; })(), "
                        "alerts: Array.from(document.querySelectorAll("
                        "'[role=alert]')).map(node => node.textContent?.trim() ?? ''), "
                        "images: Array.from(document.querySelectorAll("
                        "'.print-reference-image')).map((image, index) => ({hasSource: "
                        "Boolean(image.getAttribute('src')), complete: image.complete, "
                        "loaded: image.naturalWidth > 0, currentSrcEmpty: "
                        "image.currentSrc === '', srcEqualsOriginal: image.src === "
                        "window.__printImageExpectedUrls[index], "
                        "currentSrcEqualsOriginal: image.currentSrc === "
                        "window.__printImageExpectedUrls[index]}))})"
                    )
                except Exception as diagnostic_error:
                    successful_print_diagnostic = {
                        "pageClosed": page.is_closed(),
                        "diagnosticError": type(diagnostic_error).__name__,
                    }
                raise AcceptanceFailure(
                    "successful print did not reach window.print; "
                    f"wait_error_type={print_wait_error_type}, "
                    f"wait_error_category={_playwright_error_category(wait_error)}, "
                    f"wait_error_summary={print_wait_error_summary!r}, "
                    f"page_error_types={page_error_types[:3]}, "
                    + json.dumps(successful_print_diagnostic, sort_keys=True)
                ) from None
            mark_stage("checking successful print image state")
            button_print_state = page.evaluate("window.__printImageState")
            _require(
                len(button_print_state) == 2
                and all(
                    image["complete"]
                    and image["loaded"]
                    and image["sameOrigin"]
                    and image["path"]
                    and not image["currentSrcEmpty"]
                    and image["srcEqualsOriginal"]
                    and image["currentSrcEqualsOriginal"]
                    for image in button_print_state
                ),
                "the print action opened before all original photos loaded",
            )
            expected_print_paths = page.evaluate(
                "() => window.__printImageExpectedUrls.map(url => "
                "new URL(url).pathname)"
            )
            _require(
                len(expected_print_paths) == 2
                and all(
                    path.startswith("/media/projects/") for path in expected_print_paths
                )
                and len(set(expected_print_paths)) == 2,
                "the reader did not expose two distinct full-resolution photo URLs",
            )
            successful_print_requests = original_project_image_requests[
                success_request_start:
            ]
            successful_print_responses = original_project_image_responses[
                success_response_start:
            ]
            successful_original_paths = {
                path
                for path, status in original_project_image_responses
                if status == 200
            }
            _require(
                set(expected_print_paths).issubset(successful_original_paths)
                and set(successful_print_requests).issubset(set(expected_print_paths))
                and all(
                    path in expected_print_paths and status == 200
                    for path, status in successful_print_responses
                ),
                "each full-resolution photo must have loaded from its exact URL "
                "with HTTP 200 before printing (expected paths="
                f"{sorted(expected_print_paths)}, observed requests="
                f"{successful_print_requests}, observed responses="
                f"{successful_print_responses})",
            )
            page.evaluate("window.dispatchEvent(new Event('beforeprint'))")
            page.wait_for_function(
                "() => { const images = Array.from(document.querySelectorAll("
                "'.print-reference-image')); return images.length === 2 && "
                "images.every(image => image.complete && image.naturalWidth > 0); }",
                timeout=10_000,
            )
            page.emulate_media(media="print")
            print_reference_state = page.locator(
                ".image-gallery .print-reference-image, "
                ".steps-list .print-reference-image"
            ).evaluate_all(
                "nodes => nodes.map((node, index) => ({visible: "
                "getComputedStyle(node).display !== 'none', "
                "complete: node.complete, loaded: node.naturalWidth > 0, "
                "currentSrcEmpty: node.currentSrc === '', "
                "srcEqualsOriginal: node.src === "
                "window.__printImageExpectedUrls[index], "
                "currentSrcEqualsOriginal: node.currentSrc === "
                "window.__printImageExpectedUrls[index]}))"
            )
            print_interactive_image_state = page.locator(
                ".image-gallery .gallery-image, .steps-list .step-image"
            ).evaluate_all(
                "nodes => nodes.map(node => getComputedStyle(node).display !== 'none')"
            )
            screen_image_state = page.locator(
                ".image-gallery .screen-reference-image, "
                ".steps-list .screen-reference-image"
            ).evaluate_all(
                "nodes => nodes.map(node => getComputedStyle(node).display !== 'none')"
            )
            _require(
                not page.locator(".reader-topline").is_visible()
                and page.locator(".steps-list > li").nth(1).is_visible()
                and len(print_interactive_image_state) == 2
                and all(print_interactive_image_state)
                and len(print_reference_state) == 2
                and all(
                    image["visible"]
                    and image["complete"]
                    and image["loaded"]
                    and not image["currentSrcEmpty"]
                    and image["srcEqualsOriginal"]
                    and image["currentSrcEqualsOriginal"]
                    for image in print_reference_state
                )
                and len(screen_image_state) == 2
                and not any(screen_image_state)
                and page.locator(".image-gallery").is_visible(),
                "print layout omitted instructions or full-resolution reference photos",
            )
            page.evaluate("window.dispatchEvent(new Event('afterprint'))")
            page.emulate_media(media="screen")
            page.get_by_role("button", name="Remove from favorites").click()
            page.get_by_role("button", name="Add to favorites").wait_for(
                state="visible"
            )
            _mask_fixture_identity(page)
            _prepare_screenshot(page)
            page.screenshot(
                path=str(screenshots / "project-reader-mobile.png"),
                caret="initial",
            )

            mark_stage("opening the project editor from the reader")
            reader_edit_link = page.get_by_role("link", name="Edit project", exact=True)
            reader_edit_link.wait_for(state="visible")
            _require(
                reader_edit_link.evaluate(
                    "element => element.getBoundingClientRect().height >= 44"
                ),
                "the reader edit link is smaller than the 44 px target minimum",
            )
            reader_edit_link.click()
            page.wait_for_url(f"**/app/projects/{project_id}/edit")
            page.get_by_role("heading", name="Edit project", exact=True).wait_for(
                state="visible"
            )

            mark_stage("direct routes and explicit unfinished states")
            route_headings = {
                "/app/": "Projects",
                "/app/login": "You are already signed in.",
                "/app/more": "More",
                "/app/create": "What would you like to add?",
                "/app/admin/users": "You do not have access to this page.",
                "/app/admin/audit": "You do not have access to this page.",
            }
            for route, heading in route_headings.items():
                mark_stage(f"checking direct route {route}")
                route_response = page.goto(f"{origin}{route}", wait_until="networkidle")
                _require(
                    route_response is not None and route_response.status == 200,
                    "a supported direct app route did not return 200",
                )
                page.get_by_role("heading", name=heading).wait_for(state="visible")
                if route == "/app/more":
                    more_links = page.locator(".more-links")
                    more_cards = (
                        ("Imports", "/imports"),
                        ("Categories", "/categories"),
                        ("Gauge calculator", "/gauge"),
                    )
                    icon_failures = 0
                    for label, href in more_cards:
                        card = more_links.get_by_role("link", name=label, exact=False)
                        icon = card.locator(
                            ".more-link-icon svg[aria-hidden='true'][focusable='false']"
                        )
                        sidebar_icon = page.locator(
                            f".secondary-nav a[href$='{href}'] .nav-icon svg"
                        )
                        if (
                            card.count() != 1
                            or card.get_by_text("Available now", exact=True).count()
                            != 1
                            or card.get_attribute("href") is None
                            or not card.get_attribute("href").endswith(href)
                            or icon.count() != 1
                            or sidebar_icon.count() != 1
                        ):
                            icon_failures += 1
                            continue
                        more_shape = _svg_shape_signature(icon)
                        sidebar_shape = _svg_shape_signature(sidebar_icon)
                        icon_failures += int(
                            not more_shape or more_shape != sidebar_shape
                        )
                    account_link = more_links.get_by_role(
                        "link", name="Account", exact=False
                    )
                    profile_links = (
                        page.locator(
                            ".app-header .header-account[href$='/account']"
                        ).count()
                        == 1
                    )
                    duplicate_accounts = (
                        account_link.count()
                        + page.locator(".secondary-nav a[href$='/account']").count()
                    )
                    stale_notices = page.get_by_text(
                        "This area is not connected yet."
                    ).count()
                    if (
                        more_links.locator("a").count() != len(more_cards)
                        or icon_failures != 0
                        or not profile_links
                        or duplicate_accounts != 0
                        or stale_notices != 0
                    ):
                        _mask_fixture_identity(page)
                        _prepare_screenshot(page)
                        page.screenshot(
                            path=str(screenshots / "more-route-debug.png"),
                            caret="initial",
                        )
                        raise AcceptanceFailure(
                            "More-route cards or profile-only Account links "
                            "did not match expectations "
                            f"(icon_failures={icon_failures}, "
                            f"duplicate_accounts={duplicate_accounts}, "
                            f"stale_notice={stale_notices})"
                        )
                if route == "/app/create":
                    _require(
                        page.locator(".create-options a[href$='/projects/new']").count()
                        == 1
                        and page.locator(
                            ".create-options a[href$='/yarns/new']"
                        ).count()
                        == 1
                        and page.get_by_text(
                            "These creation forms are part of a later preview slice."
                        ).count()
                        == 0,
                        "the create page showed stale preview copy or "
                        "missing creation links",
                    )
            mark_stage("checking signed-in gauge calculator and exact API result")
            _exercise_gauge_calculator(
                page,
                origin,
                signed_in=True,
                mark_stage=mark_stage,
                screenshots=screenshots,
            )
            mark_stage("creating a project through the browser editor")
            library_response = page.goto(
                f"{origin}/app/projects", wait_until="networkidle"
            )
            _require(
                library_response is not None and library_response.status == 200,
                "the project library did not load before project creation",
            )
            page.get_by_role("heading", name="Projects", exact=True).wait_for(
                state="visible"
            )
            page.get_by_role("link", name="New project", exact=True).click()
            page.wait_for_url("**/app/projects/new")
            page.get_by_role("heading", name="New project", exact=True).wait_for(
                state="visible"
            )
            mark_stage("filling the new project form")
            mark_stage("filling the project name")
            page.get_by_label("Project name", exact=True).fill("Soft winter throw")
            mark_stage("filling the project category")
            page.get_by_label("Category", exact=True).fill("Blankets")
            mark_stage("filling the project needles")
            page.get_by_label("Needles", exact=True).fill("4 mm circular needles")
            mark_stage("filling the project stitch sample")
            page.get_by_label("Stitch sample", exact=True).fill(
                "18 stitches over 10 cm"
            )
            mark_stage("filling the project tags")
            page.locator("#project-tag-entry").fill("color, texture")
            page.get_by_role("button", name="Add tag", exact=True).click()
            page.locator("#project-tag-entry").fill("winter")
            page.get_by_role("button", name="Add tag", exact=True).click()
            mark_stage("filling the project pattern")
            page.get_by_label("Pattern", exact=True).fill(
                "Work an even textured panel."
            )
            mark_stage("filling the project notes")
            page.get_by_label("Notes", exact=True).fill(
                "Check the width after each repeat."
            )
            mark_stage("filling the project materials")
            page.get_by_label("Other materials", exact=True).fill("Two stitch markers.")
            mark_stage("adding the first project step")
            page.get_by_role("button", name="Add step", exact=True).click()
            mark_stage("checking client-side project validation")
            first_step_title = page.get_by_label("Step title", exact=True).first
            first_step_title.fill("")
            page.get_by_label("Step instructions", exact=True).first.fill(
                "Cast on and establish the border."
            )
            page.get_by_role("button", name="Create project", exact=True).click()
            page.get_by_text(
                "Each step needs a title of 256 characters or fewer.", exact=True
            ).wait_for(state="visible")
            first_step_title.fill("Cast on")
            mark_stage("searching for a yarn to associate")
            page.get_by_label("Find yarn", exact=True).fill("Riverbend")
            page.get_by_role("button", name="Search", exact=True).click()
            page.get_by_text("Riverbend Merino DK", exact=True).wait_for(
                state="visible", timeout=5_000
            )
            page.get_by_role("button", name="Add yarn", exact=True).click()
            mark_stage("submitting the new project")
            with page.expect_response(
                lambda response: (
                    urlsplit(response.url).path == "/api/v2/projects"
                    and response.request.method == "POST"
                )
            ) as create_project_response_info:
                page.get_by_role("button", name="Create project", exact=True).click()
            create_project_response = create_project_response_info.value
            _require(
                create_project_response.status == 201,
                "project creation did not succeed against the Rust API",
            )
            created_payload = create_project_response.json()
            created_project = (
                created_payload.get("data")
                if isinstance(created_payload, dict)
                else None
            )
            if not isinstance(created_project, dict):
                raise AcceptanceFailure("project creation returned an invalid resource")
            expected_tags = ["color, texture", "winter"]
            _require(
                created_project.get("tags") == expected_tags,
                "project creation changed a tag containing a comma",
            )
            created_id = created_project.get("id")
            if not isinstance(created_id, int) or isinstance(created_id, bool):
                raise AcceptanceFailure(
                    "project creation did not return a numeric resource ID"
                )
            editor_project_id = created_id
            mark_stage("opening the saved project editor")
            page.get_by_role("heading", name="Edit project", exact=True).wait_for(
                state="visible"
            )
            page.get_by_label("Project name").wait_for(state="visible")
            page.locator("#project-image-file").wait_for(state="visible")
            _require(
                page.locator("#project-image-file").is_enabled(),
                "clean saved projects did not enable media management",
            )

            mark_stage("preserving comma-containing tags during an unrelated edit")
            page.get_by_label("Needles", exact=True).fill("5 mm circular needles")
            with page.expect_response(
                lambda response: (
                    urlsplit(response.url).path == f"/api/v2/projects/{created_id}"
                    and response.request.method == "PUT"
                )
            ) as tag_preservation_response_info:
                page.get_by_role("button", name="Save changes", exact=True).click()
            tag_preservation_response = tag_preservation_response_info.value
            _require(
                tag_preservation_response.status == 200,
                "an unrelated project edit did not save",
            )
            tag_preservation_payload = tag_preservation_response.json()
            tag_preserved_project = (
                tag_preservation_payload.get("data")
                if isinstance(tag_preservation_payload, dict)
                else None
            )
            _require(
                isinstance(tag_preserved_project, dict)
                and tag_preserved_project.get("tags") == expected_tags,
                "an unrelated edit split or otherwise changed an existing tag label",
            )

            mark_stage("preserving editor drafts through dirty navigation and conflict")
            project_name = page.get_by_label("Project name", exact=True)
            project_name.fill("Chosen local draft")
            mark_stage("checking that media controls are disabled for an unsaved draft")
            _require(
                page.locator("#project-image-file").is_disabled(),
                "media management stayed enabled with an unsaved project draft",
            )
            page.once("dialog", lambda dialog: dialog.dismiss())
            mark_stage("checking the dirty navigation confirmation")
            page.get_by_role("link", name="Back to project", exact=True).click()
            mark_stage("checking that a canceled navigation preserves the draft")
            _require(
                urlsplit(page.url).path.endswith(
                    f"/app/projects/{editor_project_id}/edit"
                )
                and project_name.input_value() == "Chosen local draft",
                "the dirty-navigation guard did not keep the local project draft",
            )
            page.set_viewport_size({"width": 1280, "height": 844})
            project_name.fill("Account navigation draft")
            account_link = page.locator(".app-header .header-account")
            _require(
                account_link.is_visible()
                and ACCOUNT_HEADING_NAME.fullmatch(
                    account_link.get_attribute("aria-label") or ""
                )
                is not None
                and account_link.get_attribute("href") == "/app/account",
                "the desktop profile link does not target Account",
            )
            page.once("dialog", lambda dialog: dialog.dismiss())
            mark_stage("checking the dirty-editor guard on Account navigation")
            account_link.click()
            _require(
                urlsplit(page.url).path.endswith(
                    f"/app/projects/{editor_project_id}/edit"
                )
                and project_name.input_value() == "Account navigation draft",
                "canceled Account navigation did not preserve the project draft",
            )
            project_name.fill("Chosen local draft")
            mark_stage("saving the concurrent project version")
            _save_remote_project_version(
                page, editor_project_id, "Concurrent saved version"
            )
            mark_stage("checking stale project save conflict")
            expected_conflict_window[0] = True
            try:
                with page.expect_response(
                    lambda response: (
                        urlsplit(response.url).path
                        == f"/api/v2/projects/{editor_project_id}"
                        and response.request.method == "PUT"
                    )
                ) as project_conflict_response_info:
                    page.get_by_role("button", name="Save changes", exact=True).click()
            finally:
                expected_conflict_window[0] = False
            _require(
                project_conflict_response_info.value.status == 409,
                "a stale project edit did not return a conflict",
            )
            mark_stage("checking that the conflict retains the project draft")
            _mask_fixture_identity(page)
            _prepare_screenshot(page)
            page.screenshot(
                path=str(screenshots / "project-editor-conflict-debug.png"),
                caret="initial",
            )
            if page.locator(".conflict-panel").count() != 1:
                raise AcceptanceFailure(
                    "a 409 response did not show the project conflict choices"
                )
            conflict_heading = page.get_by_role(
                "heading",
                name="This project changed in another window.",
                exact=True,
            )
            if conflict_heading.count() != 1:
                raise AcceptanceFailure(
                    "the conflict choices did not include their expected heading"
                )
            conflict_heading.wait_for(state="visible")
            mark_stage("explicitly saving the project draft over latest")
            with page.expect_response(
                lambda response: (
                    urlsplit(response.url).path
                    == f"/api/v2/projects/{editor_project_id}"
                    and response.request.method == "PUT"
                )
            ) as keep_draft_response_info:
                page.get_by_role(
                    "button", name="Save my draft over latest", exact=True
                ).click()
            _require(
                keep_draft_response_info.value.status == 200,
                "explicitly saving the project draft over latest did not succeed",
            )
            page.get_by_text("Project changes saved.", exact=True).wait_for(
                state="visible"
            )
            _require(
                project_name.input_value() == "Chosen local draft",
                "saving the editor draft over latest changed its contents",
            )

            mark_stage("preparing a second project conflict")
            project_name.fill("Draft to discard")
            _save_remote_project_version(
                page, editor_project_id, "Latest server version"
            )
            mark_stage("checking the second stale project save")
            expected_conflict_window[0] = True
            try:
                with page.expect_response(
                    lambda response: (
                        urlsplit(response.url).path
                        == f"/api/v2/projects/{editor_project_id}"
                        and response.request.method == "PUT"
                    )
                ) as second_project_conflict_info:
                    page.get_by_role("button", name="Save changes", exact=True).click()
            finally:
                expected_conflict_window[0] = False
            _require(
                second_project_conflict_info.value.status == 409,
                "a second stale project edit did not return a conflict",
            )
            mark_stage("loading latest project values by explicit choice")
            page.get_by_role("button", name="Load latest values", exact=True).click()
            _require(
                project_name.input_value() == "Latest server version",
                "explicitly loading latest values did not replace the saved draft",
            )

            mark_stage("saving ordered project steps and refreshing project media")
            page.get_by_role("button", name="Add step", exact=True).click()
            page.get_by_label("Step title").nth(1).fill("Finish the border")
            page.get_by_label("Step instructions").nth(1).fill(
                "Bind off loosely and weave in the ends."
            )
            page.get_by_role("button", name="Move up Step 2", exact=True).click()
            step_title_inputs = page.get_by_label("Step title", exact=True)
            _require(
                step_title_inputs.nth(0).input_value() == "Finish the border"
                and step_title_inputs.nth(1).input_value() == "Cast on",
                "project step reordering did not update the draft order",
            )
            with page.expect_response(
                lambda response: (
                    urlsplit(response.url).path
                    == f"/api/v2/projects/{editor_project_id}"
                    and response.request.method == "PUT"
                )
            ) as ordered_steps_response_info:
                page.get_by_role("button", name="Save changes", exact=True).click()
            _require(
                ordered_steps_response_info.value.status == 200,
                "ordered project steps did not save",
            )
            ordered_project_payload = ordered_steps_response_info.value.json()
            ordered_project = ordered_project_payload.get("data", {})
            ordered_steps = ordered_project.get("steps", [])
            _require(
                [step.get("title") for step in ordered_steps]
                == ["Finish the border", "Cast on"],
                "the Rust API did not persist the reordered project steps",
            )

            image_asset = seed_rust.REPO_ROOT / "demo_assets" / "demo_image_3.jpg"
            with page.expect_response(
                lambda response: (
                    urlsplit(response.url).path
                    == f"/api/v2/projects/{editor_project_id}/images/title"
                    and response.request.method == "POST"
                )
            ) as editor_media_response_info:
                page.locator("#project-image-file").set_input_files(image_asset)
            _require(
                editor_media_response_info.value.status == 201,
                "the clean project editor could not upload a project photo",
            )
            page.get_by_text("Project media updated.", exact=True).wait_for(
                state="visible"
            )
            page.locator(".media-list img").wait_for(state="visible")
            _require(
                page.locator(".media-list img").count() == 1,
                "the project media list did not refresh after upload",
            )

            mark_stage("confirming project deletion through the editor")
            page.once("dialog", lambda dialog: dialog.accept())
            with page.expect_response(
                lambda response: (
                    urlsplit(response.url).path
                    == f"/api/v2/projects/{editor_project_id}"
                    and response.request.method == "DELETE"
                )
            ) as editor_delete_response_info:
                page.get_by_role("button", name="Delete project", exact=True).click()
            _require(
                editor_delete_response_info.value.status == 204,
                "project deletion did not complete with 204",
            )
            page.locator(".project-grid").wait_for(state="visible")
            _require(
                page.get_by_role("heading", name="Soft winter throw").count() == 0,
                "the deleted editor project remained in the library",
            )

            mark_stage("checking unknown route rejection")
            expected_404_window[0] = True
            try:
                unknown = page.request.get(f"{origin}{expected_404_path}")
            finally:
                expected_404_window[0] = False
            _require(
                unknown.status == 404, "unknown app route was not rejected with 404"
            )

            page.goto(f"{origin}/app/projects", wait_until="networkidle")
            page.locator(".app-shell").wait_for(state="visible")
            mark_stage("checking compact project and yarn heading spacing")
            compact_heading_cases = (
                (
                    "projects",
                    "/app/projects",
                    ".library-heading .library-intro",
                    ".library-heading .new-project",
                    "Projects",
                ),
                (
                    "yarns",
                    "/app/yarns",
                    ".library-heading .intro",
                    ".library-heading .primary-action",
                    "Yarns",
                ),
            )
            for (
                route_name,
                route_path,
                intro_selector,
                action_selector,
                heading,
            ) in compact_heading_cases:
                page.goto(f"{origin}{route_path}", wait_until="networkidle")
                page.get_by_role("heading", name=heading, exact=True).wait_for(
                    state="visible"
                )
                for width in (390, 430, 480, 640):
                    page.set_viewport_size({"width": width, "height": 844})
                    _assert_no_horizontal_overflow(page, width)
                    metrics = _measure_compact_library_heading(
                        page,
                        intro_selector=intro_selector,
                        action_selector=action_selector,
                        route_name=route_name,
                        width=width,
                        require_stacked=route_name == "yarns",
                    )
                    print(
                        "Compact library heading: "
                        + json.dumps({"route": route_name, **metrics}, sort_keys=True)
                    )

            mark_stage("checking Create page icons and navigation affordances")
            page.set_viewport_size({"width": 390, "height": 844})
            page.goto(f"{origin}/app/create", wait_until="networkidle")
            page.get_by_role(
                "heading", name="What would you like to add?", exact=True
            ).wait_for(state="visible")
            create_options = page.locator(".create-options > a")
            _require(
                create_options.count() == 2
                and page.locator(
                    ".create-options .option-mark svg[aria-hidden='true']"
                ).count()
                == 2,
                "Create choices are missing their decorative icons",
            )
            _require(
                create_options.nth(0).get_attribute("href") == "/app/projects/new"
                and create_options.nth(1).get_attribute("href") == "/app/yarns/new"
                and all(
                    height >= 44
                    for height in _visible_heights(page, ".create-options > a")
                ),
                "Create choices do not expose both destinations as touch targets",
            )

            page.goto(f"{origin}/app/projects", wait_until="networkidle")
            page.locator(".app-shell").wait_for(state="visible")
            page.set_viewport_size({"width": 1280, "height": 844})
            mark_stage("checking profile avatar propagation in app shell")
            _open_account_from_profile(page)
            page.locator('input[type="file"]').set_input_files(
                {
                    "name": "synthetic-avatar.png",
                    "mimeType": "image/png",
                    "buffer": _tiny_png_fixture(),
                }
            )
            with page.expect_response(
                lambda response: (
                    urlsplit(response.url).path == "/api/v2/account/avatar"
                    and response.request.method == "POST"
                )
            ) as avatar_upload_response_info:
                page.get_by_role("button", name=UPLOAD_PICTURE_BUTTON_NAME).click()
            _require(
                avatar_upload_response_info.value.status == 200,
                "the synthetic profile picture upload did not succeed",
            )
            page.locator(".profile-portrait img").wait_for(state="visible")
            _assert_shell_avatar(page, mobile=False, present=True)

            page.set_viewport_size({"width": 390, "height": 844})
            _assert_mobile_app_header(page)
            _assert_shell_avatar(page, mobile=True, present=True)
            page.reload(wait_until="networkidle")
            page.locator(".app-shell").wait_for(state="visible")
            page.get_by_role("heading", name=ACCOUNT_HEADING_NAME).wait_for(
                state="visible"
            )
            _assert_shell_avatar(page, mobile=True, present=True)

            page.set_viewport_size({"width": 1280, "height": 844})
            _assert_shell_avatar(page, mobile=False, present=True)
            with page.expect_response(
                lambda response: (
                    urlsplit(response.url).path == "/api/v2/account/avatar"
                    and response.request.method == "DELETE"
                )
            ) as avatar_delete_response_info:
                page.get_by_role("button", name=REMOVE_PICTURE_BUTTON_NAME).click()
            _require(
                avatar_delete_response_info.value.status == 204,
                "removing the synthetic profile picture did not succeed",
            )
            page.locator(".profile-portrait img").wait_for(state="detached")
            _assert_shell_avatar(page, mobile=False, present=False)

            page.set_viewport_size({"width": 390, "height": 844})
            _assert_mobile_app_header(page)
            _assert_shell_avatar(page, mobile=True, present=False)
            page.reload(wait_until="networkidle")
            page.locator(".app-shell").wait_for(state="visible")
            page.get_by_role("heading", name=ACCOUNT_HEADING_NAME).wait_for(
                state="visible"
            )
            _assert_shell_avatar(page, mobile=True, present=False)

            page.goto(f"{origin}/app/projects", wait_until="networkidle")
            page.locator(".app-shell").wait_for(state="visible")
            mark_stage("logout and anonymous session restoration")
            page.set_viewport_size({"width": 1280, "height": 844})
            _open_account_from_profile(page)
            logout_button = _account_sign_out_button(page)
            logout_button.click()
            page.wait_for_url("**/app/login", timeout=10_000)
            page.locator("#email").wait_for(state="visible")
            page.get_by_role("heading", name="Sign in").wait_for(state="visible")
            _require(
                any(
                    path == "/api/v2/auth/logout" and has_csrf
                    for path, has_csrf in csrf_requests
                ),
                "logout did not send the CSRF header",
            )
            _require(
                any(
                    path == "/api/v2/auth/logout" and status == 204
                    for path, status in auth_responses
                ),
                "logout did not complete with 204",
            )
            remaining_cookies = context.cookies(origin)
            _require(
                not any(
                    cookie["name"] == "stricknani_session"
                    for cookie in remaining_cookies
                ),
                "logout did not clear the browser session cookie",
            )
            remaining_cookies.clear()
            page.wait_for_function(
                "() => document.querySelector('.app-shell') === null",
            )
            _require(
                any(
                    status == 200 and user_null and csrf
                    for status, user_null, csrf, _ in session_observations
                ),
                "anonymous session was not restored after logout",
            )
            page.locator("#email").fill("")
            page.locator("#password").fill("")
            page.set_viewport_size({"width": 390, "height": 844})
            _prepare_screenshot(page)
            page.screenshot(
                path=str(screenshots / "signed-out-after-logout.png"),
                caret="initial",
            )
            mark_stage("checking public gauge calculator after logout")
            _exercise_gauge_calculator(
                page,
                origin,
                signed_in=False,
                mark_stage=mark_stage,
                screenshots=screenshots,
            )

            _require(
                not csp_errors,
                "CSP console violation from: "
                + ", ".join(sorted(set(csp_errors)))
                + "; details="
                + json.dumps(
                    csp_event_summaries[:10],
                    sort_keys=True,
                ),
            )
            _require(not page_errors, "the browser reported a page error")
            _require(
                all(
                    error
                    in {
                        "expected_unknown_route_404",
                        "expected_project_conflict_409",
                        "expected_signup_conflict_409",
                        "expected_ocr_failure_5xx",
                        "expected_print_image_abort",
                    }
                    for error in console_errors
                ),
                "the browser reported an unexpected console error: "
                + ", ".join(sorted(set(console_errors)))
                + "; path/status="
                + ", ".join(sorted(set(console_error_locations))),
            )
            _require(
                not responsive_failures,
                responsive_failures[0]
                if responsive_failures
                else "responsive target failure",
            )
            stage_file.unlink(missing_ok=True)
            context.close()
        finally:
            for screenshot in screenshots.glob("*.png"):
                screenshot.chmod(0o600)
            browser.close()

    return screenshots


def _argument_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--binary", type=Path, required=True)
    parser.add_argument("--web-root", type=Path, required=True)
    parser.add_argument("--private-dir", type=Path)
    parser.add_argument(
        "--browser", choices=("chromium", "firefox", "webkit"), default="chromium"
    )
    executable_group = parser.add_mutually_exclusive_group()
    executable_group.add_argument("--browser-executable", type=Path)
    executable_group.add_argument("--chromium-executable", type=Path)
    parser.add_argument("--service-fixture-binary", type=Path)
    parser.add_argument("--ocr-mode", choices=("success", "failure"), default="success")
    parser.add_argument(
        "--archive-mode", choices=("success", "failure"), default="success"
    )
    parser.add_argument("--signup-enabled", action="store_true")
    return parser


def _validate_browser_args(
    args: argparse.Namespace, parser: argparse.ArgumentParser
) -> None:
    if args.chromium_executable is not None and args.browser != "chromium":
        parser.error("--chromium-executable is only valid with --browser chromium")


def main(argv: list[str] | None = None) -> int:
    parser = _argument_parser()
    args = parser.parse_args(argv)
    _validate_browser_args(args, parser)
    private_dir: Path | None = None
    try:
        private_dir = _private_work_dir(args.private_dir)
        screenshots = run_live_fixture(
            args.binary,
            args.web_root,
            private_dir,
            args.chromium_executable,
            args.service_fixture_binary,
            args.ocr_mode,
            args.archive_mode,
            args.signup_enabled,
            browser_name=args.browser,
            browser_executable=args.browser_executable,
        )
    except rust_fixture.FixtureError as error:
        print(
            f"Web UI browser acceptance could not start safely: {error}",
            file=sys.stderr,
        )
        return 1
    except AcceptanceFailure as error:
        print(f"Web UI browser acceptance failed: {error}", file=sys.stderr)
        return 1
    except Exception as error:
        stage_file = (
            private_dir / "browser-stage.txt" if private_dir is not None else None
        )
        if stage_file is not None and stage_file.is_file():
            stage = stage_file.read_text(encoding="ascii")
            locations = ",".join(
                f"{Path(frame.filename).name}:{frame.lineno}"
                for frame in traceback.extract_tb(error.__traceback__)[-3:]
            )
            print(
                f"Web UI browser acceptance failed during {stage}; "
                f"exception type {type(error).__name__}; "
                f"failure locations {locations}; diagnostic details were suppressed.",
                file=sys.stderr,
            )
        else:
            print(
                "Web UI browser acceptance failed; diagnostic details were suppressed.",
                file=sys.stderr,
            )
        return 1
    print(
        "Web UI browser acceptance passed: strict CSP, session auth, routes, "
        "responsive layout, and logout."
    )
    if args.browser == "webkit":
        print(
            "WebKit screenshot capture omitted because Playwright injects an "
            "inline style element that strict CSP correctly blocks."
        )
    else:
        print(f"Sanitized screenshots: {screenshots}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
