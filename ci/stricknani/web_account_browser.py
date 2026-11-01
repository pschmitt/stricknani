#!/usr/bin/env python3
"""Exercise account settings against a disposable Rust server without screenshots."""

from __future__ import annotations

import argparse
import ipaddress
import os
import shutil
import sys
import tempfile
import zlib
from pathlib import Path
from urllib.parse import urlsplit

from playwright.sync_api import Dialog, Page, Response, Route, sync_playwright

from ci.stricknani import rust_fixture, seed_rust


class AcceptanceFailure(RuntimeError):
    """A safe-to-display browser assertion failure."""


def _require(condition: bool, message: str) -> None:
    if not condition:
        raise AcceptanceFailure(message)


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
        raise AcceptanceFailure("fixture URL must use plain HTTP on loopback")
    if port is None or not 1 <= port <= 65535:
        raise AcceptanceFailure("fixture URL has no valid port")
    return f"http://127.0.0.1:{port}"


def _private_work_dir(path: Path | None) -> Path:
    if path is None:
        target = Path(tempfile.mkdtemp(prefix="stricknani-account-browser-"))
    else:
        target = rust_fixture._outside_repository(path, "private work directory")
        target.mkdir(mode=0o700, parents=True, exist_ok=False)
    target = rust_fixture._outside_repository(target, "private work directory")
    target.chmod(0o700)
    return target


def _png_fixture() -> bytes:
    def chunk(kind: bytes, data: bytes) -> bytes:
        return (
            len(data).to_bytes(4, "big")
            + kind
            + data
            + (zlib.crc32(kind + data) & 0xFFFFFFFF).to_bytes(4, "big")
        )

    header = (1).to_bytes(4, "big") + (1).to_bytes(4, "big") + bytes([8, 6, 0, 0, 0])
    pixel = zlib.compress(bytes([0, 36, 118, 105, 255]))
    return (
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", header)
        + chunk(b"IDAT", pixel)
        + chunk(b"IEND", b"")
    )


def _guard_loopback(page: Page, origin: str) -> None:
    def route_request(route: Route) -> None:
        request = route.request
        parsed = urlsplit(request.url)
        request_origin = f"{parsed.scheme}://{parsed.netloc}"
        if request_origin != origin:
            route.abort("blockedbyclient")
            return
        route.continue_()

    page.route("**/*", route_request)


def _value_is_nonempty(page: Page, selector: str) -> bool:
    return bool(
        page.locator(selector).evaluate(
            "(element) => typeof element.value === 'string' && element.value.length > 0"
        )
    )


def _assert_navigation_stays(page: Page, expected_path: str = "/app/account") -> None:
    prompts: list[str] = []

    def dismiss_prompt(dialog: Dialog) -> None:
        prompts.append(dialog.type)
        dialog.dismiss()

    page.once("dialog", dismiss_prompt)
    project_link = page.get_by_role("link", name="Projects").first
    destination = project_link.evaluate("(link) => new URL(link.href).pathname")
    project_link.click()
    _require(
        urlsplit(page.url).path == expected_path and destination != expected_path,
        "navigation left the account page despite the unsaved-data guard",
    )


def _assert_unload_is_guarded(page: Page) -> None:
    blocked: bool = page.evaluate(
        """() => {
          const event = new Event('beforeunload', { cancelable: true });
          window.dispatchEvent(event);
          return event.defaultPrevented;
        }"""
    )
    _require(blocked, "unsaved account data did not guard document unload")


def _mark_stage(stage_file: Path | None, stage: str) -> None:
    if stage_file is not None:
        stage_file.write_text(stage, encoding="ascii")


def run_acceptance(
    fixture_file: Path,
    chromium_executable: Path | None = None,
    stage_file: Path | None = None,
) -> None:
    fixture = rust_fixture.load_fixture_environment(fixture_file)
    origin = _loopback_origin(fixture["STRICKNANI_RUST_TEST_URL"])
    email = fixture["STRICKNANI_RUST_TEST_EMAIL"]
    old_password = fixture["STRICKNANI_RUST_TEST_PASSWORD"]
    if chromium_executable is not None:
        chromium_executable = chromium_executable.expanduser().resolve()
        if not chromium_executable.is_file() or not os.access(
            chromium_executable, os.X_OK
        ):
            raise AcceptanceFailure("configured Chromium executable is unavailable")

    with sync_playwright() as playwright:
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

            def record_token_response(response: Response) -> None:
                path = urlsplit(response.url).path
                if path == "/api/v2/account/api-tokens":
                    action = "create" if response.request.method == "POST" else "list"
                    _mark_stage(
                        stage_file,
                        f"token {action} HTTP {response.status}",
                    )
                elif (
                    path.startswith("/api/v2/account/api-tokens/")
                    and response.request.method == "DELETE"
                ):
                    _mark_stage(stage_file, f"token revoke HTTP {response.status}")

            page.on("response", record_token_response)

            _mark_stage(stage_file, "sign in")
            page.goto(f"{origin}/app/account", wait_until="networkidle")
            page.locator("#email").wait_for(state="visible")
            page.locator("#email").fill(email)
            page.locator("#password").fill(old_password)
            page.get_by_role("button", name="Sign in").click()
            page.get_by_role("heading", name="Projects").wait_for(state="visible")

            _mark_stage(stage_file, "navigate to account route")
            page.goto(f"{origin}/app/account", wait_until="networkidle")
            _mark_stage(stage_file, "wait for account heading")
            page.get_by_role("heading", name="Account").wait_for(state="visible")
            _mark_stage(stage_file, "wait for profile heading")
            page.get_by_role("heading", name="Profile").wait_for(state="visible")
            _mark_stage(stage_file, "load profile data")
            page.locator(".profile-email, .profile-copy .notice-error").first.wait_for(
                state="visible"
            )
            _require(
                page.locator(".profile-email").count() == 1,
                "the account profile request failed",
            )
            _require(
                page.locator(".profile-email").inner_text().strip() == email,
                "the profile email did not match the signed-in fixture",
            )

            _mark_stage(stage_file, "protect token-name draft during navigation")
            page.get_by_label("Token name").fill("Browser acceptance token")
            _assert_unload_is_guarded(page)
            _assert_navigation_stays(page)
            _require(
                page.get_by_label("Token name").input_value()
                == "Browser acceptance token",
                "navigation discarded the token-name draft",
            )

            _mark_stage(stage_file, "mobile layout")
            for width in (390, 320):
                page.set_viewport_size({"width": width, "height": 844})
                page.get_by_label("Token name").wait_for(state="visible")
                mobile_metrics: dict[str, int | float] = page.evaluate(
                    """() => {
                      const field = document.querySelector('#token-name');
                      const button = document.querySelector('.token-create-row button');
                      return {
                        viewport: document.documentElement.clientWidth,
                        content: document.documentElement.scrollWidth,
                        fieldFont: field
                          ? parseFloat(getComputedStyle(field).fontSize)
                          : 0,
                        buttonHeight: button
                          ? button.getBoundingClientRect().height
                          : 0,
                      };
                    }"""
                )
                _require(
                    mobile_metrics["content"] <= mobile_metrics["viewport"]
                    and mobile_metrics["fieldFont"] >= 16
                    and mobile_metrics["buttonHeight"] >= 44,
                    "account settings overflow or have undersized mobile controls",
                )
            page.set_viewport_size({"width": 1280, "height": 900})

            _mark_stage(stage_file, "submit new API token name")
            page.get_by_role("button", name="Create token").click()
            _mark_stage(stage_file, "show one-time token dialog")
            token_dialog = page.locator("dialog").first
            page.wait_for_function(
                "() => document.querySelector('[data-secret-value]') !== null "
                + "|| document.querySelector('.token-section [role=alert]') !== null",
                timeout=5000,
            )
            dialog_open = token_dialog.evaluate("(dialog) => dialog.open")
            if not dialog_open:
                error_visible = page.locator(".token-section [role=alert]").is_visible()
                _mark_stage(
                    stage_file,
                    f"token dialog did not open; token error visible={error_visible}",
                )
            token_dialog.wait_for(state="visible")
            token_dialog.get_by_role("heading", name="Your new API token").wait_for(
                state="visible"
            )
            _mark_stage(stage_file, "verify one-time token display")
            _require(
                bool(
                    page.locator("[data-secret-value]").evaluate(
                        "(input) => input instanceof HTMLInputElement "
                        + "&& input.value.length > 0"
                    )
                ),
                "the one-time token was not shown",
            )
            _mark_stage(stage_file, "protect one-time token reveal")
            page.keyboard.press("Escape")
            _require(
                token_dialog.is_visible(),
                "Escape dismissed a token that had not been acknowledged",
            )
            _assert_unload_is_guarded(page)
            _mark_stage(stage_file, "copy one-time token")
            page.evaluate(
                """() => {
                  window.__accountClipboardDenied = false;
                  Object.defineProperty(navigator, 'clipboard', {
                    configurable: true,
                    value: {
                      writeText: () => {
                        window.__accountClipboardDenied = true;
                        return Promise.reject(
                          new DOMException('Permission denied', 'NotAllowedError')
                        );
                      },
                    },
                  });
                }"""
            )
            page.get_by_role("button", name="Copy token").click()
            token_dialog.locator(".copy-status").wait_for(state="visible")
            _require(
                token_dialog.locator(".copy-status").inner_text()
                == "Copy was blocked. Select and copy the token manually.",
                "the denied clipboard write did not show its manual-copy fallback",
            )
            _require(
                page.evaluate("() => window.__accountClipboardDenied === true"),
                "the browser clipboard denial case was not exercised",
            )
            _require(
                page.locator("[data-secret-value]").evaluate(
                    "(input) => input === document.activeElement"
                ),
                "the manual-copy fallback did not focus the token field",
            )
            _require(
                page.locator("[data-secret-value]").evaluate(
                    "(input) => input.selectionStart === 0 "
                    + "&& input.selectionEnd === input.value.length "
                    + "&& input.value.length > 0"
                ),
                "the manual-copy fallback did not select the full token",
            )
            page.evaluate("() => delete window.__accountClipboardDenied")
            _mark_stage(stage_file, "acknowledge one-time token")
            page.get_by_label("I saved this token somewhere safe.").check()
            _mark_stage(stage_file, "hide one-time token")
            page.get_by_role("button", name="Hide token").click()
            page.wait_for_timeout(50)
            token_dialog_state: dict[str, bool] = page.evaluate(
                """() => ({
                  open: document.querySelector('dialog[open]') !== null,
                  secret: document.querySelector('[data-secret-value]') !== null,
                  acknowledged: (() => {
                    const input = document.querySelector('.acknowledge-token input');
                    return input instanceof HTMLInputElement && input.checked;
                  })(),
                })"""
            )
            _mark_stage(
                stage_file,
                "after hide: "
                f"open={token_dialog_state['open']}; "
                f"secret={token_dialog_state['secret']}; "
                f"ack={token_dialog_state['acknowledged']}",
            )
            page.wait_for_function(
                "() => document.querySelector('dialog[open]') === null "
                + "&& document.querySelector('[data-secret-value]') === null",
                timeout=5000,
            )

            _mark_stage(stage_file, "open API-token revoke confirmation")
            row = page.locator(".token-row").filter(has_text="Browser acceptance token")
            row.get_by_role(
                "button", name="Revoke token Browser acceptance token"
            ).click()
            revoke_dialog = page.get_by_role("dialog").filter(
                has=page.get_by_role("heading", name="Revoke this token?")
            )
            revoke_dialog.wait_for(state="visible")
            _mark_stage(stage_file, "revoke API token")
            revoke_dialog.get_by_role("button", name="Revoke token").click()
            _mark_stage(stage_file, "verify revoked token removal")
            row.wait_for(state="detached")
            _mark_stage(stage_file, "verify revoke focus target")
            _require(
                page.get_by_label("Token name").evaluate(
                    "(element) => element === document.activeElement"
                ),
                "focus did not move to the token-name field after revocation",
            )

            _mark_stage(stage_file, "upload and remove avatar")
            avatar_png = _png_fixture()
            page.locator('input[type="file"]').set_input_files(
                {
                    "name": "profile.png",
                    "mimeType": "image/png",
                    "buffer": avatar_png,
                }
            )
            _mark_stage(stage_file, "protect avatar draft during navigation")
            _assert_unload_is_guarded(page)
            _assert_navigation_stays(page)
            _require(
                page.get_by_text("profile.png", exact=True).is_visible(),
                "navigation discarded the selected avatar",
            )
            page.get_by_role("button", name="Upload picture").click()
            avatar = page.locator(".profile-portrait img")
            avatar.wait_for(state="visible")
            _require(
                bool(
                    avatar.evaluate(
                        "(image) => image.complete && image.naturalWidth > 0"
                    )
                ),
                "the uploaded profile picture did not render",
            )
            page.get_by_role("button", name="Remove picture").click()
            avatar.wait_for(state="detached")
            _require(
                page.locator(".profile-portrait").inner_text().strip() == "R",
                "the profile placeholder did not return after removal",
            )

            _mark_stage(stage_file, "reject incorrect current password")
            page.locator("#current-password").fill("wrong synthetic password")
            page.locator("#new-password").fill("New synthetic account password 2026")
            page.locator("#confirm-password").fill(
                "New synthetic account password 2026"
            )
            _mark_stage(stage_file, "protect password draft during navigation")
            _assert_unload_is_guarded(page)
            _assert_navigation_stays(page)
            _require(
                _value_is_nonempty(page, "#current-password")
                and _value_is_nonempty(page, "#new-password")
                and _value_is_nonempty(page, "#confirm-password"),
                "navigation discarded the password draft",
            )
            page.get_by_role("button", name="Save new password").click()
            page.locator("#current-password-error").wait_for(state="visible")
            _require(
                _value_is_nonempty(page, "#current-password")
                and _value_is_nonempty(page, "#new-password"),
                "password fields were not preserved after a validation error",
            )

            _mark_stage(stage_file, "change password and sign in again")
            new_password = "New synthetic account password 2026"
            page.locator("#current-password").fill(old_password)
            page.locator("#new-password").fill(new_password)
            page.locator("#confirm-password").fill(new_password)
            page.get_by_role("button", name="Save new password").click()
            page.locator("#email").wait_for(state="visible")
            page.locator("#email").fill(email)
            page.locator("#password").fill(new_password)
            page.get_by_role("button", name="Sign in").click()
            page.get_by_role("heading", name="Projects").wait_for(state="visible")

            _mark_stage(stage_file, "check browser persistence")
            local_keys = page.evaluate("() => Object.keys(window.localStorage)")
            _require(
                not any(
                    any(
                        term in key.casefold()
                        for term in ("token", "password", "draft")
                    )
                    for key in local_keys
                ),
                "account secrets or drafts were persisted in local storage",
            )
            context.close()
        finally:
            browser.close()


def run_live_fixture(
    binary: Path,
    web_root: Path,
    private_dir: Path,
    chromium_executable: Path | None = None,
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
                "synthetic project fixture could not be seeded"
            ) from error
        finally:
            token_file.unlink(missing_ok=True)
        run_acceptance(
            fixture_file, chromium_executable, private_dir / "browser-stage.txt"
        )
    finally:
        if started:
            rust_fixture.stop_fixture(state_file)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--binary", type=Path, required=True)
    parser.add_argument("--web-root", type=Path, required=True)
    parser.add_argument("--private-dir", type=Path)
    parser.add_argument("--chromium-executable", type=Path)
    args = parser.parse_args(argv)
    private_dir: Path | None = None
    stage_file: Path | None = None
    try:
        private_dir = _private_work_dir(args.private_dir)
        stage_file = private_dir / "browser-stage.txt"
        run_live_fixture(
            args.binary,
            args.web_root,
            private_dir,
            args.chromium_executable,
        )
    except rust_fixture.FixtureError as error:
        print(
            f"Account browser acceptance could not start safely: {error}",
            file=sys.stderr,
        )
        return 1
    except AcceptanceFailure as error:
        print(f"Account browser acceptance failed: {error}", file=sys.stderr)
        return 1
    except Exception:
        stage = "account settings journey"
        if stage_file is not None and stage_file.is_file():
            stage = stage_file.read_text(encoding="ascii")
        print(
            "Account browser acceptance failed during "
            f"{stage}; diagnostic details were suppressed.",
            file=sys.stderr,
        )
        return 1
    finally:
        if private_dir is not None:
            shutil.rmtree(private_dir, ignore_errors=True)
    print(
        "Account browser acceptance passed: profile image, one-time API token, "
        "password change, and fresh sign-in."
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
