#!/usr/bin/env python3
"""Exercise import review journeys against a disposable Rust fixture."""

from __future__ import annotations

import argparse
import ipaddress
import os
import re
import sys
from pathlib import Path
from urllib.parse import urlsplit

from playwright.sync_api import Page, Response, Route, sync_playwright
from playwright.sync_api import TimeoutError as PlaywrightTimeoutError

from ci.stricknani import rust_fixture, seed_rust, web_ui_browser


class AcceptanceFailure(RuntimeError):
    """An actionable, sanitized acceptance failure."""


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


def _assert_no_overflow(page: Page, width: int) -> None:
    sizes = page.evaluate(
        "() => ({ viewport: innerWidth, document: "
        "document.documentElement.scrollWidth, "
        "body: document.body.scrollWidth })"
    )
    _require(
        sizes["viewport"] == width
        and sizes["document"] <= width
        and sizes["body"] <= width,
        f"import page overflows at {width}px",
    )


def run_live_fixture(
    binary: Path,
    web_root: Path,
    private_dir: Path,
    chromium_executable: Path | None = None,
) -> Path:
    fixture_file = private_dir / "fixture.json"
    state_file = private_dir / "fixture-state.json"
    token_file = private_dir / "seed-token.json"
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
                "synthetic account fixture could not be prepared"
            ) from error
        finally:
            token_file.unlink(missing_ok=True)
        return run_acceptance(
            fixture_file, private_dir / "screenshots", chromium_executable
        )
    finally:
        if started:
            rust_fixture.stop_fixture(state_file)


def run_acceptance(
    fixture_file: Path,
    screenshot_path: Path | None = None,
    chromium_executable: Path | None = None,
) -> Path:
    fixture = rust_fixture.load_fixture_environment(fixture_file)
    origin = _loopback_origin(fixture["STRICKNANI_RUST_TEST_URL"])
    email = fixture["STRICKNANI_RUST_TEST_EMAIL"]
    password = fixture["STRICKNANI_RUST_TEST_PASSWORD"]
    screenshots = web_ui_browser._private_screenshot_dir(screenshot_path)
    stage_file = screenshots.parent / "import-browser-stage.txt"
    auth_status_file = screenshots.parent / "import-auth-status.txt"

    def mark_stage(value: str) -> None:
        stage_file.write_text(value, encoding="ascii")
        stage_file.chmod(0o600)

    auth_statuses: dict[str, int | None] = {"session": None, "login": None}

    def record_auth_statuses() -> None:
        lines = [
            f"{name}={status if status is not None else 'not-seen'}"
            for name, status in auth_statuses.items()
        ]
        auth_status_file.write_text("\n".join(lines) + "\n", encoding="ascii")
        auth_status_file.chmod(0o600)

    record_auth_statuses()

    if chromium_executable is not None:
        chromium_executable = chromium_executable.expanduser().resolve()
        if not chromium_executable.is_file() or not os.access(
            chromium_executable, os.X_OK
        ):
            raise AcceptanceFailure("configured Chromium executable is unavailable")

    csp_errors: list[bool] = []
    page_errors: list[bool] = []
    import_responses: list[tuple[str, int]] = []
    resource_failures: list[str] = []
    file_preview_yarn_counts: list[int] = []

    def observe_response(response: Response) -> None:
        path = urlsplit(response.url).path
        if path == "/api/v2/session":
            auth_statuses["session"] = response.status
            record_auth_statuses()
        elif path == "/api/v2/auth/login":
            auth_statuses["login"] = response.status
            record_auth_statuses()
        if path.startswith("/api/v2/imports/") or path in {
            "/api/v2/projects",
            "/api/v2/yarns",
        }:
            import_responses.append((path, response.status))
        if path == "/api/v2/imports/preview-file" and response.status == 200:
            try:
                payload = response.json()
                project = payload["data"]["draft"]["project"]
                yarns = project["yarns"]
                if isinstance(yarns, list):
                    file_preview_yarn_counts.append(len(yarns))
            except (KeyError, TypeError, ValueError):
                pass
        if response.status >= 400 and (
            path.startswith("/api/v2/imports/")
            or path in {"/api/v2/projects", "/api/v2/yarns"}
        ):
            resource_failures.append(f"HTTP {response.status} API resource")

    def observe_console(message: object) -> None:
        if getattr(message, "type", "") != "error":
            return
        text = getattr(message, "text", "").lower()
        if "content security policy" in text or (
            "violat" in text and ("script-src" in text or "style-src" in text)
        ):
            csp_errors.append(True)

    with sync_playwright() as playwright:
        mark_stage("launching Chromium")
        browser = playwright.chromium.launch(
            headless=True,
            executable_path=(str(chromium_executable) if chromium_executable else None),
        )
        try:
            context = browser.new_context(
                viewport={"width": 390, "height": 844},
                reduced_motion="reduce",
                color_scheme="light",
                locale="en-US",
            )
            page = context.new_page()
            page.on("response", observe_response)
            page.on("console", observe_console)
            page.on("pageerror", lambda _error: page_errors.append(True))

            mark_stage("loading disposable login route")
            response = page.goto(f"{origin}/app/login", wait_until="networkidle")
            if response is None or response.status != 200:
                raise AcceptanceFailure("login route failed")
            mark_stage("checking strict frontend CSP")
            policy = response.headers.get("content-security-policy", "")
            _require(
                "default-src 'self'" in policy
                and "script-src 'self'" in policy
                and "style-src 'self'" in policy
                and "style-src-attr 'unsafe-hashes'" in policy
                and "unsafe-inline" not in policy
                and "unsafe-eval" not in policy,
                "strict frontend CSP is missing",
            )
            mark_stage("choosing English for the import journey")
            page.get_by_role("button", name=re.compile(r"^(English|Englisch)$")).click()
            page.get_by_role("heading", name="Sign in", exact=True).wait_for(
                state="visible"
            )
            mark_stage("filling disposable login email")
            page.locator("#email").fill(email)
            mark_stage("filling disposable login password")
            page.locator("#password").fill(password)
            mark_stage("submitting disposable login")
            try:
                page.get_by_role("button", name="Sign in").click()
            except PlaywrightTimeoutError:
                # Do not expose Playwright's locator log or form values. These
                # fixed booleans distinguish a disabled control, locale change,
                # invalid form and client exception without capturing secrets.
                controls = page.evaluate(
                    """() => ({
                      form: Boolean(document.querySelector('form.auth-form')),
                      enabled: Boolean(document.querySelector(
                        'form.auth-form button[type="submit"]:enabled'
                      )),
                      valid: Boolean(document.querySelector(
                        'form.auth-form:valid'
                      )),
                      inert: Boolean(document.querySelector(
                        'form.auth-form'
                      )?.closest('[inert]')),
                      english: document.documentElement.lang === 'en',
                      german: document.documentElement.lang === 'de'
                    })"""
                )
                flags = ",".join(
                    f"{key}:{int(controls.get(key) is True)}"
                    for key in (
                        "form",
                        "enabled",
                        "valid",
                        "inert",
                        "english",
                        "german",
                    )
                )
                raise AcceptanceFailure(
                    "disposable login control timed out ("
                    f"{flags},page-error:{int(bool(page_errors))},"
                    f"csp-error:{int(bool(csp_errors))})"
                ) from None
            mark_stage("waiting for authenticated app shell")
            page.locator(".app-shell").wait_for(state="visible")

            mark_stage("checking capability-gated import options")
            page.goto(f"{origin}/app/imports", wait_until="networkidle")
            page.get_by_role("heading", name="Import a project").wait_for(
                state="visible"
            )
            _require(
                page.get_by_role("button", name="From a link").is_disabled(),
                "URL import was not disabled for the fixture capability",
            )
            page.get_by_text("AI enhancement is unavailable.").wait_for(state="visible")
            _require(
                page.get_by_label("Ask AI to improve extraction").count() == 0,
                "AI opt-in appeared without the AI capability",
            )

            mark_stage("previewing text and checking review fields")
            source_text = (
                "Cozy import fixture\n"
                "A reviewed pattern for a small test swatch.\n"
                "Cast on 48 stitches. Work four rows in garter stitch."
            )
            page.get_by_label("Pattern text or instructions").fill(source_text)
            page.get_by_role("button", name="Preview and review").click()
            page.get_by_role("heading", name="Review and correct").wait_for(
                state="visible"
            )
            project_name = page.get_by_label("Project name")
            project_name.wait_for(state="visible")
            _require(
                bool(project_name.input_value()), "text preview did not extract a title"
            )

            mark_stage("checking source and draft preservation after network failure")
            project_name.fill("Edited title retained after failure")
            page.get_by_label("Pattern text or instructions").fill(
                source_text + "\nUpdated source."
            )
            mark_stage("cancelling replacement of a dirty review")
            page.once("dialog", lambda dialog: dialog.dismiss())
            page.get_by_role("button", name="Preview and review").click()
            _require(
                project_name.input_value() == "Edited title retained after failure"
                and page.get_by_label("Pattern text or instructions").input_value()
                == source_text + "\nUpdated source."
                and page.get_by_role("heading", name="Review and correct").is_visible(),
                "cancelling review replacement discarded the existing draft",
            )
            failed_once = [False]

            def fail_next_preview(route: Route) -> None:
                if not failed_once[0]:
                    failed_once[0] = True
                    route.abort()
                else:
                    route.continue_()

            page.route("**/api/v2/imports/preview", fail_next_preview)
            page.once("dialog", lambda dialog: dialog.accept())
            page.get_by_role("button", name="Preview and review").click()
            page.get_by_role("alert").wait_for(state="visible")
            _require(failed_once[0], "network failure route was not exercised")
            _require(
                project_name.input_value() == "Edited title retained after failure"
                and page.get_by_label("Pattern text or instructions").input_value()
                == source_text + "\nUpdated source."
                and page.get_by_role("heading", name="Review and correct").is_visible(),
                "a failed preview discarded its current source or reviewed draft",
            )
            page.unroute("**/api/v2/imports/preview", fail_next_preview)

            mark_stage("checking the unsaved import navigation guard")
            page.once("dialog", lambda dialog: dialog.dismiss())
            page.locator(".mobile-navigation").get_by_role(
                "link", name="Yarns", exact=True
            ).click()
            _require(
                urlsplit(page.url).path == "/app/imports"
                and project_name.input_value() == "Edited title retained after failure",
                "dismissed navigation warning did not preserve the import review",
            )

            mark_stage("clearing the required project name")
            project_name.fill("")
            mark_stage("submitting project validation")
            page.get_by_role("button", name="Create project").click()
            mark_stage("waiting for project validation feedback")
            page.get_by_role("alert").wait_for(state="visible")
            mark_stage("checking project review remains after validation")
            validation_state = page.evaluate(
                "() => ({ path: location.pathname, "
                "review: document.querySelector('.review-card') !== null, "
                "name: document.querySelector('input[required]') !== null, "
                "alert: document.querySelector('[role=alert]') !== null })"
            )
            _require(
                validation_state["path"] == "/app/imports"
                and validation_state["review"] is True
                and validation_state["name"] is True
                and validation_state["alert"] is True,
                "validation feedback did not preserve the reviewed draft "
                f"(review={validation_state['review']}, "
                f"name={validation_state['name']}, "
                f"alert={validation_state['alert']})",
            )
            mark_stage("restoring project title after validation")
            project_name.fill("Reviewed import swatch")
            mark_stage("saving import notes before project create")
            note_fields = page.locator(".review-fields > label.field textarea")
            _require(
                note_fields.count() > 0,
                "review page rendered without editable text areas",
            )
            note_fields.last.fill("Created after checking every imported field.")
            for width in (320, 390, 1280):
                page.set_viewport_size({"width": width, "height": 844})
                _assert_no_overflow(page, width)
                target_heights = page.locator(
                    ".source-tabs button, .primary, .secondary"
                ).evaluate_all(
                    "nodes => nodes.filter(node => node.getClientRects().length).map("
                    "node => node.getBoundingClientRect().height)"
                )
                _require(
                    not target_heights or min(target_heights) >= 44,
                    f"an import action target is below 44px at {width}px",
                )
            page.set_viewport_size({"width": 390, "height": 844})
            web_ui_browser._mask_fixture_identity(page)
            page.keyboard.press("Escape")
            review_heading = page.get_by_role("heading", name="Review and correct")
            review_heading.evaluate(
                "element => { element.tabIndex = -1; element.focus(); }"
            )
            review_heading.scroll_into_view_if_needed()
            page.evaluate("() => window.scrollBy(0, -80)")
            page.screenshot(
                path=str(screenshots / "import-review-mobile.png"),
                caret="initial",
            )

            mark_stage("checking save action clears the mobile navigation")
            create_button = page.get_by_role("button", name="Create project")
            create_button.scroll_into_view_if_needed()
            create_rect = create_button.bounding_box()
            navigation_rect = page.locator(".mobile-navigation").bounding_box()
            _require(
                create_rect is not None
                and navigation_rect is not None
                and create_rect["height"] >= 44
                and create_rect["y"] + create_rect["height"] <= navigation_rect["y"],
                "the create action is obscured by mobile navigation",
            )
            page.screenshot(
                path=str(screenshots / "import-form-bottom-mobile.png"),
                caret="initial",
            )

            mark_stage("creating reviewed project through API v2")
            # Delay the real API response to cover the in-flight mutation path.
            # Fields should be locked while saving, and a navigation attempt
            # must still preserve the reviewed draft until the request settles.
            cdp = context.new_cdp_session(page)
            cdp.send("Network.enable")
            cdp.send(
                "Network.emulateNetworkConditions",
                {
                    "offline": False,
                    "latency": 900,
                    "downloadThroughput": 1_000_000,
                    "uploadThroughput": 1_000_000,
                    "connectionType": "cellular3g",
                },
            )
            create_button.click()
            _require(
                project_name.is_disabled(),
                "review fields remained editable during project creation",
            )
            page.once("dialog", lambda dialog: dialog.dismiss())
            page.locator(".mobile-navigation").get_by_role(
                "link", name="Yarns", exact=True
            ).click()
            _require(
                urlsplit(page.url).path == "/app/imports",
                "dismissed navigation warning left the pending import",
            )
            page.get_by_role("link", name="Open created project").wait_for(
                state="visible"
            )
            cdp.send(
                "Network.emulateNetworkConditions",
                {
                    "offline": False,
                    "latency": 0,
                    "downloadThroughput": -1,
                    "uploadThroughput": -1,
                },
            )
            cdp.detach()
            _require(
                any(
                    path == "/api/v2/projects" and status == 201
                    for path, status in import_responses
                ),
                "reviewed project was not created through API v2",
            )

            mark_stage("starting a second import")
            page.get_by_role("button", name="Start another import").click()
            mark_stage("choosing the file source")
            page.get_by_role("button", name="Upload file").click()
            html = """<!doctype html><html><head>
<title>Imported sample cardigan</title>
<script type="application/ld+json">
{"@context":"https://schema.org","@type":"Recipe",
"name":"Imported sample cardigan",
"description":"A small file-import fixture.",
"yarns":[{"name":"Fixture blue wool","brand":{"name":"Test mill"},
"weight":"100 g","length":"210 m",
"image":"https://images.example.test/yarn.jpg"}],
"recipeInstructions":[{"@type":"HowToStep","name":"Cast on",
"text":"Cast on 32 stitches."}]}
</script>
</head><body><h1>Imported sample cardigan</h1>
<p>Fixture content only.</p><img src="https://images.example.test/cover.jpg">
</body></html>"""
            mark_stage("selecting synthetic HTML file")
            page.get_by_label("Choose a file").set_input_files(
                {
                    "name": "sample.html",
                    "mimeType": "text/html",
                    "buffer": html.encode("utf-8"),
                }
            )
            mark_stage("submitting HTML file preview")
            page.get_by_role("button", name="Preview and review").click()
            mark_stage("waiting for HTML file review")
            try:
                page.get_by_role("heading", name="Review and correct").wait_for(
                    state="visible"
                )
            except PlaywrightTimeoutError:
                file_statuses = [
                    status
                    for path, status in import_responses
                    if path == "/api/v2/imports/preview-file"
                ]
                file_status = file_statuses[-1] if file_statuses else "not observed"
                raise AcceptanceFailure(
                    f"HTML file preview did not show review (API status {file_status})"
                ) from None
            mark_stage("checking extracted yarn in HTML review")
            try:
                page.get_by_role("heading", name="Yarn from this import").wait_for(
                    state="visible"
                )
            except PlaywrightTimeoutError:
                count = (
                    file_preview_yarn_counts[-1]
                    if file_preview_yarn_counts
                    else "unavailable"
                )
                raise AcceptanceFailure(
                    f"HTML preview did not render imported yarn details (count {count})"
                ) from None
            mark_stage("checking the extracted yarn name field")
            imported_yarn_name = page.locator(".yarn-card input").first
            imported_yarn_name.wait_for(state="visible")
            _require(
                imported_yarn_name.input_value() == "Fixture blue wool",
                "HTML import did not populate the editable yarn name",
            )
            _require(
                page.get_by_text("External images are disabled.").count() > 0,
                "disabled external image capability was not explained",
            )
            page.get_by_role("button", name="Save yarn to library").click()
            page.get_by_role("status").filter(
                has_text="Yarn added to your library"
            ).wait_for(state="visible")
            page.get_by_role("button", name="Create project").click()
            page.get_by_role("link", name="Open created project").wait_for(
                state="visible"
            )
            _require(
                any(
                    path == "/api/v2/yarns" and status == 201
                    for path, status in import_responses
                )
                and sum(
                    path == "/api/v2/projects" and status == 201
                    for path, status in import_responses
                )
                >= 2,
                "imported yarn and project were not created through normal v2 APIs",
            )

            _require(not csp_errors, "browser reported a CSP violation")
            _require(not page_errors, "browser reported an uncaught page exception")
            _require(
                not resource_failures, "an API resource returned an unexpected error"
            )
            return screenshots
        finally:
            browser.close()


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--binary", type=Path, required=True)
    parser.add_argument("--web-root", type=Path, required=True)
    parser.add_argument("--private-dir", type=Path)
    parser.add_argument("--chromium-executable", type=Path)
    args = parser.parse_args(argv)
    private_dir: Path | None = None
    try:
        private_dir = web_ui_browser._private_work_dir(args.private_dir)
        screenshots = run_live_fixture(
            args.binary,
            args.web_root,
            private_dir,
            args.chromium_executable,
        )
    except (rust_fixture.FixtureError, AcceptanceFailure) as error:
        print(f"Import browser acceptance failed: {error}", file=sys.stderr)
        return 1
    except Exception as error:
        stage_file = private_dir / "import-browser-stage.txt" if private_dir else None
        stage = (
            stage_file.read_text(encoding="ascii")
            if stage_file is not None and stage_file.is_file()
            else None
        )
        auth_status_file = (
            private_dir / "import-auth-status.txt" if private_dir else None
        )
        auth_statuses: dict[str, str] = {}
        if auth_status_file is not None and auth_status_file.is_file():
            for line in auth_status_file.read_text(encoding="ascii").splitlines():
                match = re.fullmatch(r"(session|login)=(not-seen|[1-5][0-9]{2})", line)
                if match:
                    auth_statuses[match.group(1)] = match.group(2)
        auth_summary = ""
        if auth_statuses:
            auth_summary = "; auth-status=" + ",".join(
                f"{name}:{auth_statuses.get(name, 'not-seen')}"
                for name in ("session", "login")
            )
        error_context = (
            f" ({type(error).__name__}{auth_summary}); "
            "diagnostic details were suppressed."
        )
        print(
            "Import browser acceptance failed"
            + (f" during {stage}" if stage else "")
            + error_context,
            file=sys.stderr,
        )
        return 1
    print(
        "Import browser acceptance passed: preview, preservation, file, yarn, "
        "project, CSP."
    )
    print(f"Sanitized fixture screenshots: {screenshots}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
