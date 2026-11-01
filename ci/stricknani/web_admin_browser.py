#!/usr/bin/env python3
"""Exercise administrator users and audit screens on a private Rust fixture."""

from __future__ import annotations

import argparse
import ipaddress
import json
import os
import secrets
import shutil
import sys
import tempfile
from pathlib import Path
from urllib.parse import urlsplit

from playwright.sync_api import Page, Response, Route, expect, sync_playwright

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


def _assert_no_horizontal_overflow(page: Page) -> None:
    overflow = page.evaluate(
        "() => document.documentElement.scrollWidth > window.innerWidth"
    )
    viewport = page.viewport_size
    width = viewport["width"] if viewport is not None else 0
    _require(
        not overflow,
        f"admin content overflows the {width}px viewport",
    )


def run_acceptance(
    fixture_file: Path,
    admin_email: str,
    admin_password: str,
    chromium_executable: Path | None = None,
    stage_file: Path | None = None,
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

            mark("disable and reset member")
            member_row = page.locator(".user-row").filter(has_text=member_email)
            disable_button = member_row.locator(".row-actions button").first
            disable_button.click()
            mark("open member disable confirmation")
            active_dialog = page.get_by_role("dialog", name="Disable this account?")
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
            reset_dialog.get_by_label("New temporary password").fill(reset_password)
            reset_dialog.get_by_role(
                "button", name="Reset password", exact=True
            ).click()
            mark("wait for member password reset completion")
            reset_dialog.wait_for(state="hidden")
            mark("wait for reactivated member status")
            expect(member_row.locator(".status-pill")).to_contain_text("Active")
            mark("wait for member reset confirmation")
            page.get_by_role("status").filter(
                has_text="Password reset. The user must sign in again."
            ).wait_for(state="visible")

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
            page.goto(f"{origin}/app/admin/audit", wait_until="networkidle")
            page.get_by_role("heading", name="Audit log").wait_for(state="visible")
            page.locator(".event-row").first.wait_for(state="visible")
            page.get_by_label("Entity").select_option("project")
            page.get_by_label("Action").fill("created")
            page.get_by_role("button", name="Filter").click()
            page.wait_for_url("**/app/admin/audit?entity=project&action=created")
            page.locator(".event-row").first.wait_for(state="visible")
            _require(
                all(
                    "project" in row.inner_text().casefold()
                    and "created" in row.inner_text().casefold()
                    for row in page.locator(".event-row").all()
                ),
                "audit filters returned an event outside the selected entity/action",
            )
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
            await_page = page
            await_page.goto(
                f"{origin}/app/admin/audit?page=9999&entity=project&action=created",
                wait_until="networkidle",
            )
            await_page.get_by_role("heading", name="Audit log").wait_for(
                state="visible"
            )
            await_page.get_by_text("Page 1", exact=True).wait_for(state="visible")
            _require(
                "page=" not in urlsplit(await_page.url).query,
                "out-of-range audit page did not clamp to the filtered last page",
            )
            _require(
                audit_response.get("status") == 200
                and audit_response.get("safe") is True,
                "audit API did not return its safe DTO projection",
            )

            mark("member is denied admin API access")
            mark("locate administrator sign-out control")
            sign_out = page.locator("button.header-sign-out")
            sign_out.wait_for(state="visible")
            mark("click administrator sign-out")
            sign_out.click()
            mark("wait for administrator sign-out")
            page.locator("#email").wait_for(state="visible")
            mark("fill member sign-in")
            page.locator("#email").fill(member_email)
            page.locator("#password").fill(reset_password)
            page.get_by_role("button", name="Sign in").click()
            mark("wait for member sign-in")
            page.get_by_role("heading", name="Projects").wait_for(state="visible")
            mark("open admin route as member")
            page.goto(f"{origin}/app/admin/users", wait_until="networkidle")
            mark("wait for member access denial")
            page.get_by_role(
                "heading", name="You do not have access to this page."
            ).wait_for(state="visible")
            mark("check member API denial")
            forbidden_status = page.evaluate(
                "async () => (await fetch('/api/v2/admin/audit')).status"
            )
            _require(
                forbidden_status == 403,
                "a member session was not denied by the audit API",
            )

            mark("administrator reset requires fresh sign-in")
            page.locator("button.header-sign-out").click()
            mark("wait for member sign-out")
            page.locator("#email").wait_for(state="visible")
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

            mark("responsive layout and sensitive data check")
            page.goto(f"{origin}/app/admin/users", wait_until="networkidle")
            page.get_by_role("heading", name="User accounts").wait_for(state="visible")
            for width in (320, 390, 768, 1280):
                page.set_viewport_size({"width": width, "height": 900})
                _assert_no_horizontal_overflow(page)
            page.goto(f"{origin}/app/admin/audit", wait_until="networkidle")
            page.get_by_role("heading", name="Audit log").wait_for(state="visible")
            for width in (320, 390, 768, 1280):
                page.set_viewport_size({"width": width, "height": 900})
                _assert_no_horizontal_overflow(page)
            local_keys = page.evaluate("() => Object.keys(window.localStorage)")
            _require(
                not any(
                    any(word in key.casefold() for word in ("password", "admin-draft"))
                    for key in local_keys
                ),
                "administrator passwords or drafts were persisted in local storage",
            )
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
        web_root = args.web_root.expanduser().resolve()
        if not web_root.is_dir():
            raise AcceptanceFailure("web root must be an existing directory")
        run_live_fixture(
            args.binary.expanduser().resolve(),
            web_root,
            private_dir,
            args.chromium_executable,
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
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
