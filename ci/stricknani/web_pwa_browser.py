#!/usr/bin/env python3
"""Check the app-scoped offline worker with a disposable Rust server and Chromium."""

from __future__ import annotations

import argparse
import hashlib
import ipaddress
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import time
import traceback
from pathlib import Path
from typing import Protocol
from urllib.parse import urlsplit

from playwright.sync_api import BrowserContext, Dialog, Page, sync_playwright
from playwright.sync_api import Error as PlaywrightError

from ci.stricknani import rust_fixture, web_ui_browser


class AcceptanceFailure(RuntimeError):
    """A safe-to-display browser assertion failure."""


class AsyncConditionPage(Protocol):
    def evaluate(self, expression: str, arg: object = ...) -> object: ...

    def wait_for_timeout(self, timeout: float) -> None: ...


APP_CACHE_PREFIX = "stricknani-app-shell-"
IMMUTABLE_ASSET_PREFIX = "/app/_app/immutable/"
REQUIRED_OFFLINE_ASSETS = {
    "/app/manifest.webmanifest",
    "/app/offline.html",
    "/app/offline.css",
    "/app/favicon.svg",
    "/app/icons/icon-192.png",
    "/app/icons/icon-512.png",
}
REPO_ROOT = Path(__file__).resolve().parents[2]


def _require(condition: bool, message: str) -> None:
    if not condition:
        raise AcceptanceFailure(message)


def _origin(url: str) -> tuple[str, int]:
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
        raise AcceptanceFailure("fixture URL must be plain HTTP on loopback")
    return f"http://127.0.0.1:{port}", port


def _version_snapshot(page: Page) -> dict[str, object]:
    value: object = page.evaluate(
        """async () => {
          const registration = await navigator.serviceWorker.getRegistration('/app/');
          if (!registration) return { scope: '', active: '', waiting: '' };
          const ask = worker => new Promise(resolve => {
            if (!worker) return resolve(null);
            const channel = new MessageChannel();
            const timer = setTimeout(() => resolve(null), 1500);
            channel.port1.onmessage = event => {
              clearTimeout(timer);
              resolve(event.data ?? null);
              channel.port1.close();
            };
            worker.postMessage({ type: 'GET_VERSION' }, [channel.port2]);
          });
          const active = await ask(registration.active);
          const installing = await ask(registration.installing);
          const waiting = await ask(registration.waiting);
          return {
            scope: new URL(registration.scope).pathname,
            active: active?.version ?? '',
            activeAppVersion: active?.appVersion ?? '',
            activeWorkerVersion: active?.workerVersion ?? '',
            activeState: registration.active?.state ?? '',
            installing: installing?.version ?? '',
            installingState: registration.installing?.state ?? '',
            waiting: waiting?.version ?? '',
            waitingAppVersion: waiting?.appVersion ?? '',
            waitingWorkerVersion: waiting?.workerVersion ?? '',
            waitingState: registration.waiting?.state ?? '',
          };
        }"""
    )
    if not isinstance(value, dict):
        raise AcceptanceFailure("service worker registration state is malformed")
    return value


def _cache_snapshot(page: Page) -> dict[str, list[str]]:
    value: object = page.evaluate(
        """async prefix => {
          const result = {};
          const names = (await caches.keys()).filter(key => key.startsWith(prefix));
          for (const name of names) {
            const cache = await caches.open(name);
            result[name] = (await cache.keys())
              .map(request => new URL(request.url).pathname)
              .sort();
          }
          return result;
        }""",
        APP_CACHE_PREFIX,
    )
    if not isinstance(value, dict) or any(
        not isinstance(name, str)
        or not isinstance(paths, list)
        or any(not isinstance(path, str) for path in paths)
        for name, paths in value.items()
    ):
        raise AcceptanceFailure("CacheStorage state is malformed")
    return value


def _loaded_immutable_assets(page: Page) -> set[str]:
    value: object = page.evaluate(
        """() => performance.getEntriesByType('resource').map(entry => {
          try { return new URL(entry.name).pathname; } catch { return ''; }
        }).filter(path => path.startsWith('/app/_app/immutable/'))"""
    )
    if not isinstance(value, list) or any(not isinstance(path, str) for path in value):
        raise AcceptanceFailure("browser resource list is malformed")
    return {path for path in value if path.endswith((".js", ".css"))}


def _read_asset_manifest(web_root: Path) -> tuple[str, str, list[str]]:
    try:
        manifest = json.loads(
            (web_root / "pwa-assets.json").read_text(encoding="utf-8")
        )
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        raise AcceptanceFailure(
            "generated PWA asset manifest is unavailable"
        ) from error
    version = manifest.get("version") if isinstance(manifest, dict) else None
    assets = manifest.get("assets") if isinstance(manifest, dict) else None
    if (
        not isinstance(version, str)
        or re.fullmatch(r"[a-f0-9]{64}", version) is None
        or not isinstance(assets, list)
        or not assets
        or len(assets) > 512
        or any(
            not isinstance(path, str)
            or not path.startswith("/app/")
            or "?" in path
            or "#" in path
            or ".." in path
            for path in assets
        )
    ):
        raise AcceptanceFailure("generated PWA asset manifest is invalid")
    if len(set(assets)) != len(assets):
        raise AcceptanceFailure("generated PWA asset manifest has duplicates")
    worker_template = _production_worker_template()
    try:
        app_version_data = json.loads(
            (web_root / "_app/version.json").read_text(encoding="utf-8")
        )
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        raise AcceptanceFailure("compiled app version is unavailable") from error
    app_code_version = (
        app_version_data.get("version") if isinstance(app_version_data, dict) else None
    )
    if (
        not isinstance(app_code_version, str)
        or not app_code_version
        or len(app_code_version) > 128
    ):
        raise AcceptanceFailure("compiled app version is invalid")
    digest_rows: list[str] = []
    for path in assets:
        asset_path = web_root / path.removeprefix("/app/")
        try:
            digest = hashlib.sha256(asset_path.read_bytes()).hexdigest()
        except OSError as error:
            raise AcceptanceFailure("generated PWA asset is missing") from error
        digest_rows.append(f"{path}\0{digest}\n")
    calculated_version = hashlib.sha256(
        (
            app_code_version + "\0" + "".join(digest_rows) + "\0" + worker_template
        ).encode()
    ).hexdigest()
    if calculated_version != version:
        raise AcceptanceFailure("generated PWA version does not match its assets")
    expected_worker = (
        worker_template.replace("__APP_VERSION__", version)
        .replace("__APP_CODE_VERSION__", app_code_version)
        .replace("__APP_ASSETS__", json.dumps(assets, separators=(",", ":")))
    )
    try:
        actual_worker = (web_root / "app-sw.js").read_text(encoding="utf-8")
    except (OSError, UnicodeError) as error:
        raise AcceptanceFailure("generated app worker is unavailable") from error
    if "__APP_" in actual_worker or actual_worker != expected_worker:
        raise AcceptanceFailure("generated app worker differs from the build output")
    return version, app_code_version, assets


def _wait_for_async_condition(
    page: AsyncConditionPage,
    expression: str,
    expected: object,
    description: str,
    timeout_ms: int = 15_000,
) -> None:
    """Poll an awaited browser condition.

    ``wait_for_function`` treats unresolved Promises as truthy.
    """
    deadline = time.monotonic() + timeout_ms / 1000
    while True:
        if page.evaluate(expression, expected) is True:
            return
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise AcceptanceFailure(f"timed out waiting for {description}")
        page.wait_for_timeout(min(100, max(1, int(remaining * 1000))))


def _production_worker_template() -> str:
    template_path = REPO_ROOT / "web/static/service-worker.js"
    return template_path.read_text(encoding="utf-8")


def _write_next_web_root(
    web_root: Path,
    target: Path,
    version_marker: str = "disposable browser update fixture",
) -> str:
    shutil.copytree(web_root, target, symlinks=True)
    for copied_path in (target, *target.rglob("*")):
        if copied_path.is_symlink():
            continue
        copied_path.chmod(0o700 if copied_path.is_dir() else 0o600)
    manifest_path = target / "pwa-assets.json"
    try:
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        raise AcceptanceFailure(
            "generated PWA asset manifest is unavailable"
        ) from error
    assets = manifest.get("assets") if isinstance(manifest, dict) else None
    if not isinstance(assets, list) or not assets:
        raise AcceptanceFailure("generated PWA asset manifest is invalid")
    try:
        app_version_data = json.loads(
            (target / "_app/version.json").read_text(encoding="utf-8")
        )
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        raise AcceptanceFailure("compiled app version is unavailable") from error
    app_code_version = (
        app_version_data.get("version") if isinstance(app_version_data, dict) else None
    )
    if (
        not isinstance(app_code_version, str)
        or not app_code_version
        or len(app_code_version) > 128
    ):
        raise AcceptanceFailure("compiled app version is invalid")

    css_path = target / "offline.css"
    with css_path.open("a", encoding="utf-8") as output:
        output.write(f"\n/* {version_marker} */\n")

    template = _production_worker_template()
    rows = []
    for path in sorted(assets):
        disk_path = target / path.removeprefix("/app/")
        try:
            content = disk_path.read_bytes()
        except OSError as error:
            raise AcceptanceFailure("a generated app cache asset is missing") from error
        rows.append(f"{path}\0{hashlib.sha256(content).hexdigest()}\n")
    version = hashlib.sha256(
        (app_code_version + "\0" + "".join(rows) + "\0" + template).encode()
    ).hexdigest()
    manifest["version"] = version
    manifest_path.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    worker = (
        template.replace("__APP_VERSION__", version)
        .replace("__APP_CODE_VERSION__", app_code_version)
        .replace("__APP_ASSETS__", json.dumps(sorted(assets), separators=(",", ":")))
    )
    if "__APP_" in worker:
        raise AcceptanceFailure("generated app worker has an unexpanded marker")
    (target / "app-sw.js").write_text(worker, encoding="utf-8")
    return version


def _build_candidate_web_root(private_dir: Path, app_code_version: str) -> Path:
    source_web = REPO_ROOT / "web"
    dependencies = source_web / "node_modules"
    npm = shutil.which("npm")
    if not dependencies.is_dir() or npm is None:
        raise AcceptanceFailure("candidate SvelteKit build tools are unavailable")

    candidate_source = private_dir / "candidate-app-source"
    shutil.copytree(
        source_web,
        candidate_source,
        ignore=shutil.ignore_patterns(
            ".git", "node_modules", "build", ".svelte-kit", ".env", ".env.*"
        ),
    )
    (candidate_source / "node_modules").symlink_to(
        dependencies.resolve(), target_is_directory=True
    )
    environment = os.environ.copy()
    environment["STRICKNANI_PWA_TEST_VERSION"] = app_code_version
    try:
        result = subprocess.run(
            [npm, "run", "build"],
            cwd=candidate_source,
            env=environment,
            capture_output=True,
            check=False,
            timeout=240,
        )
    except (OSError, subprocess.TimeoutExpired) as error:
        raise AcceptanceFailure("candidate SvelteKit build did not complete") from error
    if result.returncode != 0:
        raise AcceptanceFailure(
            f"candidate SvelteKit build failed with status {result.returncode}"
        )

    candidate_root = candidate_source / "build"
    _version, candidate_app_version, _assets = _read_asset_manifest(candidate_root)
    if candidate_app_version != app_code_version:
        raise AcceptanceFailure("candidate build used the wrong compiled app version")
    if not candidate_root.is_dir() or not (candidate_root / "200.html").is_file():
        raise AcceptanceFailure("candidate SvelteKit build output is incomplete")
    return candidate_root


def _wait_for_active_worker(page: Page, expected_version: str) -> None:
    _wait_for_async_condition(
        page,
        """expected => navigator.serviceWorker
          .getRegistration('/app/').then(async registration => {
          const worker = registration?.active;
          if (worker?.state !== 'activated') return false;
          const version = await new Promise(resolve => {
            const channel = new MessageChannel();
            const timer = setTimeout(() => resolve(''), 1000);
            channel.port1.onmessage = event => {
              clearTimeout(timer);
              resolve(event.data?.version ?? '');
              channel.port1.close();
            };
            worker.postMessage({ type: 'GET_VERSION' }, [channel.port2]);
          });
          return version === expected
            && worker.state === 'activated'
            && registration.active === worker;
        })""",
        expected_version,
        "active app worker version",
    )


def _wait_for_controlled_worker(page: Page, expected_version: str) -> None:
    try:
        page.wait_for_function(
            """() => Boolean(navigator.serviceWorker.controller?.scriptURL
              .endsWith('/app/sw.js'))""",
            timeout=10_000,
        )
    except PlaywrightError as error:
        state = page.evaluate(
            """() => {
              const registration = navigator.serviceWorker
                .getRegistration('/app/');
              return registration.then(value => ({
                page: location.pathname,
                controller: navigator.serviceWorker.controller
                  ? new URL(navigator.serviceWorker.controller.scriptURL).pathname
                  : '',
                scope: value ? new URL(value.scope).pathname : '',
                active: value?.active?.state ?? ''
              }));
            }"""
        )
        raise AcceptanceFailure(
            f"app worker did not control page (safe paths/state: {state})"
        ) from error
    try:
        _wait_for_async_condition(
            page,
            """expected => {
              const worker = navigator.serviceWorker.controller;
              if (!worker) return false;
              return new Promise(resolve => {
                const channel = new MessageChannel();
                const timer = setTimeout(() => resolve(false), 1000);
                channel.port1.onmessage = event => {
                  clearTimeout(timer);
                  resolve(event.data?.version === expected);
                  channel.port1.close();
                };
                worker.postMessage({ type: 'GET_VERSION' }, [channel.port2]);
              }).then(versionMatches => versionMatches
                && navigator.serviceWorker.controller === worker);
            }""",
            expected_version,
            "app controller version",
            timeout_ms=10_000,
        )
    except (PlaywrightError, AcceptanceFailure) as error:
        state = page.evaluate(
            """() => {
              const controller = navigator.serviceWorker.controller;
              return {
                page: location.pathname,
                controller: controller
                  ? new URL(controller.scriptURL).pathname
                  : '',
                controllerState: controller?.state ?? '',
                scope: navigator.serviceWorker.ready.then(
                  value => new URL(value.scope).pathname
                )
              };
            }"""
        )
        raise AcceptanceFailure(
            f"app controller did not confirm the expected version (safe state: {state})"
        ) from error


def _wait_for_initial_worker(page: Page, expected_version: str) -> None:
    _wait_for_active_worker(page, expected_version)
    page.reload(wait_until="networkidle")
    _wait_for_controlled_worker(page, expected_version)


def _prepare_browser_update(
    binary: Path,
    web_root: Path,
    private_dir: Path,
    fixture_file: Path,
    state_file: Path,
    origin: str,
    port: int,
    second_root: Path,
    *,
    version_marker: str = "disposable browser update fixture",
    missing_asset: str | None = None,
    candidate_build_root: Path | None = None,
    runtime_name: str = "runtime-v2",
) -> tuple[Path, Path, str, set[str]]:
    if candidate_build_root is None:
        expected_version = _write_next_web_root(web_root, second_root, version_marker)
    else:
        shutil.copytree(candidate_build_root, second_root, symlinks=True)
        candidate_manifest = json.loads(
            (second_root / "pwa-assets.json").read_text(encoding="utf-8")
        )
        expected_version = candidate_manifest.get("version")
        if not isinstance(expected_version, str):
            raise AcceptanceFailure("candidate PWA worker version is invalid")
    if missing_asset is not None:
        if not missing_asset.startswith("/app/") or ".." in missing_asset:
            raise AcceptanceFailure("failed-install fixture asset path is invalid")
        try:
            (second_root / missing_asset.removeprefix("/app/")).unlink()
        except OSError as error:
            raise AcceptanceFailure(
                "failed-install fixture asset is unavailable"
            ) from error
    rust_fixture.stop_fixture(state_file)
    original_port_allocator = rust_fixture._unused_port
    rust_fixture._unused_port = lambda: port
    try:
        rust_fixture.start_fixture(
            binary=binary,
            temp_dir=private_dir / runtime_name,
            fixture_file=fixture_file,
            state_file=state_file,
            web_root=second_root,
        )
    finally:
        rust_fixture._unused_port = original_port_allocator
    # Confirm that the replacement server is still on the original origin.
    fixture = rust_fixture.load_fixture_environment(fixture_file)
    replacement_origin, replacement_port = _origin(fixture["STRICKNANI_RUST_TEST_URL"])
    _require(
        replacement_origin == origin and replacement_port == port,
        "replacement fixture did not retain the browser origin",
    )
    return fixture_file, state_file, expected_version, set()


def _sign_in_through_api(page: Page, fixture_file: Path) -> None:
    """Sign the browser context in again after a fixture restart.

    Every fixture runtime has its own database, so a restart ends the session.
    Signing in through the API sets the cookie for every tab without
    navigating one, so open tabs keep the app version they loaded.
    """
    current = rust_fixture.load_fixture_environment(fixture_file)
    status: object = page.evaluate(
        """async ({ email, password }) => {
          const session = await (await fetch('/api/v2/session', {
            cache: 'no-store',
          })).json();
          const response = await fetch('/api/v2/auth/login', {
            method: 'POST',
            headers: {
              'content-type': 'application/json',
              'x-csrf-token': session?.data?.csrf_token ?? '',
            },
            body: JSON.stringify({ email, password }),
          });
          return response.status;
        }""",
        {
            "email": current["STRICKNANI_RUST_TEST_EMAIL"],
            "password": current["STRICKNANI_RUST_TEST_PASSWORD"],
        },
    )
    _require(status == 200, f"API sign-in after a fixture restart failed ({status})")


def run_acceptance(
    binary: Path,
    web_root: Path,
    private_dir: Path,
    fixture_file: Path,
    state_file: Path,
    chromium_executable: Path | None = None,
) -> Path:
    fixture = rust_fixture.load_fixture_environment(fixture_file)
    origin, port = _origin(fixture["STRICKNANI_RUST_TEST_URL"])
    email = fixture["STRICKNANI_RUST_TEST_EMAIL"]
    password = fixture["STRICKNANI_RUST_TEST_PASSWORD"]
    expected_version, expected_app_version, expected_assets = _read_asset_manifest(
        web_root
    )
    _require(
        REQUIRED_OFFLINE_ASSETS.issubset(expected_assets),
        "generated allowlist omits a required offline fallback asset",
    )
    second_root = private_dir / "web-root-next"
    screenshots = web_ui_browser._private_screenshot_dir(private_dir / "screenshots")
    stage_file = private_dir / "browser-stage.txt"
    current_stage = ""

    def mark_stage(value: str) -> None:
        nonlocal current_stage
        current_stage = value
        stage_file.write_text(value, encoding="ascii")
        stage_file.chmod(0o600)

    if chromium_executable is not None:
        chromium_executable = chromium_executable.expanduser().resolve()
        if not chromium_executable.is_file() or not os.access(
            chromium_executable, os.X_OK
        ):
            raise AcceptanceFailure("configured Chromium executable is unavailable")

    page_errors: list[bool] = []
    csp_errors: list[bool] = []
    activation_worker_errors: list[tuple[str, int | None]] = []
    service_worker_versions: list[dict[str, str]] = []
    service_worker_asset_requests: list[str] = []
    service_worker_asset_responses: list[str] = []
    service_worker_asset_finished: list[str] = []
    service_worker_asset_failures: list[str] = []
    reopen_events: list[str] = []

    def record_worker_error(event: object) -> None:
        if not current_stage.startswith(("update", "committing", "reloading")):
            return
        if not isinstance(event, dict):
            activation_worker_errors.append(("unknown", None))
            return
        message = event.get("errorMessage")
        error_class = "other"
        if isinstance(message, str):
            match = re.search(r"\b([A-Za-z]+Error)\b", message)
            if match:
                error_class = match.group(1)
        line = event.get("lineNumber")
        activation_worker_errors.append(
            (error_class, line if isinstance(line, int) else None)
        )

    def record_worker_versions(event: object) -> None:
        if not isinstance(event, dict):
            return
        versions = event.get("versions")
        if not isinstance(versions, list):
            return
        for version in versions:
            if not isinstance(version, dict):
                continue
            script_url = version.get("scriptURL")
            version_id = version.get("versionId")
            status = version.get("status")
            if (
                isinstance(script_url, str)
                and urlsplit(script_url).path == "/app/sw.js"
                and isinstance(version_id, str)
                and isinstance(status, str)
            ):
                service_worker_versions.append(
                    {"version_id": version_id, "status": status}
                )

    def record_service_worker_asset_request(request: object) -> None:
        request_url = getattr(request, "url", "")
        try:
            path = urlsplit(request_url).path
        except ValueError:
            return
        if (
            path in expected_assets
            and getattr(request, "service_worker", None) is not None
        ):
            service_worker_asset_requests.append(path)

    def record_service_worker_asset_response(response: object) -> None:
        request = getattr(response, "request", None)
        request_url = getattr(request, "url", "")
        try:
            path = urlsplit(request_url).path
        except ValueError:
            return
        if (
            path in expected_assets
            and getattr(request, "service_worker", None) is not None
        ):
            service_worker_asset_responses.append(
                f"{path}:{getattr(response, 'status', 'unknown')}"
            )

    def record_service_worker_asset_finished(request: object) -> None:
        request_url = getattr(request, "url", "")
        try:
            path = urlsplit(request_url).path
        except ValueError:
            return
        if (
            path in expected_assets
            and getattr(request, "service_worker", None) is not None
        ):
            service_worker_asset_finished.append(path)

    def record_service_worker_asset_failure(request: object) -> None:
        request_url = getattr(request, "url", "")
        try:
            path = urlsplit(request_url).path
        except ValueError:
            return
        if (
            path in expected_assets
            and getattr(request, "service_worker", None) is not None
        ):
            service_worker_asset_failures.append(path)

    with sync_playwright() as playwright:
        mark_stage("launching packaged Chromium")
        try:
            browser = playwright.chromium.launch(
                headless=True,
                executable_path=str(chromium_executable)
                if chromium_executable
                else None,
            )
        except Exception as error:
            raise AcceptanceFailure("configured Chromium could not start") from error
        try:
            context: BrowserContext = browser.new_context(
                viewport={"width": 390, "height": 844},
                service_workers="allow",
                reduced_motion="reduce",
                color_scheme="light",
                locale="en-US",
            )
            context.on("request", record_service_worker_asset_request)
            context.on("response", record_service_worker_asset_response)
            context.on("requestfinished", record_service_worker_asset_finished)
            context.on("requestfailed", record_service_worker_asset_failure)
            page = context.new_page()
            cdp = context.new_cdp_session(page)
            cdp.send("ServiceWorker.enable")
            cdp.on("ServiceWorker.workerErrorReported", record_worker_error)
            cdp.on("ServiceWorker.workerVersionUpdated", record_worker_versions)
            page.on("pageerror", lambda _error: page_errors.append(True))
            page.on(
                "console",
                lambda message: (
                    csp_errors.append(True)
                    if "content security policy" in message.text.lower()
                    or "refused to" in message.text.lower()
                    else None
                ),
            )
            monitor = context.new_page()
            monitor.on(
                "request",
                lambda request: (
                    reopen_events.append(f"request:{urlsplit(request.url).path}")
                    if urlsplit(request.url).path.startswith("/app/")
                    else None
                ),
            )
            monitor.on(
                "response",
                lambda response: (
                    reopen_events.append(
                        f"response:{response.status}:{urlsplit(response.url).path}"
                    )
                    if urlsplit(response.url).path.startswith("/app/")
                    else None
                ),
            )
            monitor.on(
                "requestfailed",
                lambda request: (
                    reopen_events.append(f"failed:{urlsplit(request.url).path}")
                    if urlsplit(request.url).path.startswith("/app/")
                    else None
                ),
            )
            monitor.on("pageerror", lambda _error: page_errors.append(True))
            monitor.on(
                "console",
                lambda message: (
                    csp_errors.append(True)
                    if "content security policy" in message.text.lower()
                    or "refused to" in message.text.lower()
                    else None
                ),
            )
            health = monitor.goto(f"{origin}/healthz", wait_until="domcontentloaded")
            _require(
                health is not None and health.status == 200,
                "Rust fixture health endpoint failed during worker activation",
            )
            try:
                built_worker = (web_root / "app-sw.js").read_bytes()
            except OSError as error:
                raise AcceptanceFailure(
                    "generated app worker is unavailable"
                ) from error
            served_worker = monitor.request.get(f"{origin}/app/sw.js", timeout=10_000)
            _require(
                served_worker.status == 200 and served_worker.body() == built_worker,
                "Rust fixture did not serve the frozen generated worker bytes",
            )
            mark_stage("requesting the canonical app root")
            response = page.goto(f"{origin}/app", wait_until="networkidle")
            _require(
                response is not None and response.status == 200,
                "canonical app root failed",
            )
            mark_stage("checking canonical app root URL")
            _require(
                page.url == f"{origin}/app/",
                "app root without a trailing slash did not canonicalize into "
                "worker scope",
            )
            mark_stage("waiting for app worker registration")
            _wait_for_async_condition(
                page,
                "expected => navigator.serviceWorker"
                ".getRegistration('/app/').then(registration => Boolean(registration))",
                None,
                "app worker registration",
                timeout_ms=10_000,
            )
            mark_stage("checking active app worker version")
            _wait_for_active_worker(page, expected_version)
            _require(
                page.url == f"{origin}/app/",
                "canonical app root escaped the service worker scope",
            )
            mark_stage("checking initial worker scope and version")
            initial_snapshot = _version_snapshot(page)
            _require(
                initial_snapshot.get("scope") == "/app/"
                and initial_snapshot.get("active") == expected_version,
                "initial worker is not active under the canonical app root",
            )
            mark_stage("checking initial offline cache")
            initial_caches = _cache_snapshot(page)
            _require(len(initial_caches) == 1, "initial app cache count is unexpected")
            initial_paths = set(next(iter(initial_caches.values())))
            _require(
                initial_paths == REQUIRED_OFFLINE_ASSETS,
                "cold worker install cached something beyond the offline "
                "recovery assets",
            )
            cold_install_requests = set(service_worker_asset_requests)
            _require(
                REQUIRED_OFFLINE_ASSETS.issubset(cold_install_requests),
                "browser did not observe every required offline asset fetched "
                "during install",
            )
            _require(
                not any(
                    path.startswith(IMMUTABLE_ASSET_PREFIX)
                    for path in cold_install_requests
                ),
                "cold worker install fetched immutable route chunks before "
                "they were needed",
            )
            mark_stage("reloading the app root to confirm worker control")
            response = page.reload(wait_until="networkidle")
            _require(
                response is not None and response.status == 200,
                "app root failed after the worker installed",
            )
            mark_stage("checking canonical app root worker control")
            _wait_for_controlled_worker(page, expected_version)
            _require(
                page.evaluate("Boolean(navigator.serviceWorker.controller)"),
                "canonical app root is not controlled by the app-scoped worker",
            )

            response = page.goto(f"{origin}/app/login", wait_until="networkidle")
            _require(
                response is not None and response.status == 200,
                "app login route failed",
            )

            snapshot = _version_snapshot(page)
            _require(snapshot.get("scope") == "/app/", "worker escaped the app scope")
            _require(
                snapshot.get("active") == expected_version,
                "active app worker version differs from build",
            )
            _require(
                snapshot.get("activeAppVersion") == expected_app_version,
                "active worker compiled-app version differs from the build",
            )
            _require(
                snapshot.get("waiting") == "", "initial app worker unexpectedly waits"
            )

            worker_response = page.request.get(f"{origin}/app/sw.js")
            _require(worker_response.status == 200, "app worker route failed")
            _require(
                worker_response.headers.get("service-worker-allowed") == "/app/",
                "app worker scope header is incorrect",
            )
            worker_text = worker_response.text()
            _require(
                expected_version in worker_text
                and f'const APP_CODE_VERSION = "{expected_app_version}"' in worker_text
                and "__APP_" not in worker_text,
                "Rust did not serve the generated versioned worker",
            )
            _require(
                worker_text.count("self.skipWaiting()") == 1
                and worker_text.count("self.clients.claim()") == 1
                and "PREPARE_UPDATE" not in worker_text
                and "matchAll(" not in worker_text,
                "app worker does not take over on its own or still asks other tabs",
            )

            caches = _cache_snapshot(page)
            _require(len(caches) == 1, "initial app cache count is unexpected")
            cached_paths = next(iter(caches.values()))
            _require(
                REQUIRED_OFFLINE_ASSETS.issubset(cached_paths),
                "required offline recovery assets are missing from CacheStorage",
            )
            _require(
                set(cached_paths).issubset(expected_assets),
                "CacheStorage contains a path outside the exact generated allowlist",
            )
            loaded_assets = _loaded_immutable_assets(page)
            _require(
                loaded_assets.issubset(cached_paths),
                "an immutable asset loaded by the app was not cached on demand",
            )
            for forbidden in (
                "/app/messages.json",
                "/app/projects",
                "/api/v2/session",
                "/media/projects/",
            ):
                _require(
                    all(forbidden not in path for path in cached_paths),
                    "private or mutable app content entered CacheStorage",
                )
            for path in (
                "/app/pwa-assets.json",
                "/app/csp.json",
                "/app/service-worker.js",
            ):
                hidden = page.request.get(f"{origin}{path}")
                _require(
                    hidden.status == 404, "internal worker build artifact is public"
                )
            catalog = page.request.get(f"{origin}/app/messages.json")
            _require(
                catalog.status == 200
                and "no-store" in catalog.headers.get("cache-control", ""),
                "translation catalog must remain uncached",
            )

            mark_stage("checking responsive offline fallback")
            context.set_offline(True)
            offline_response = page.goto(
                f"{origin}/app/projects/42",
                wait_until="domcontentloaded",
                timeout=10_000,
            )
            _require(
                offline_response is not None and offline_response.status == 200,
                "offline page did not load",
            )
            page.get_by_role(
                "heading", name="Your workspace is waiting online."
            ).wait_for()
            _require(
                page.get_by_text(
                    "Dein Arbeitsbereich ist online wieder da."
                ).is_visible(),
                "German offline copy is missing",
            )
            _require(
                page.get_by_text("are not stored offline.").is_visible(),
                "offline page does not explain that private edits are not stored",
            )
            for width in (320, 390):
                page.set_viewport_size({"width": width, "height": 844})
                dimensions = page.evaluate(
                    """() => ({
                      viewport: window.innerWidth,
                      document: document.documentElement.scrollWidth
                    })"""
                )
                _require(
                    dimensions["document"] <= dimensions["viewport"],
                    "offline page overflows a phone viewport",
                )
                retry_box = page.get_by_role("link", name="Try again").bounding_box()
                _require(
                    retry_box is not None and retry_box["height"] >= 44,
                    "offline retry link is smaller than the touch target budget",
                )
            page.set_viewport_size({"width": 390, "height": 844})
            page.screenshot(path=str(screenshots / "offline-mobile.png"), caret="hide")
            context.set_offline(False)
            recovered = page.goto(f"{origin}/app/login", wait_until="networkidle")
            _require(
                recovered is not None and recovered.status == 200,
                "app did not recover after the browser returned online",
            )

            mark_stage("logging in to check dirty-tab close behavior")
            mark_stage("waiting for recovered sign-in form")
            page.locator("#email").wait_for(state="visible", timeout=10_000)
            page.get_by_role("heading", name="Sign in", exact=True).wait_for(
                state="visible", timeout=10_000
            )
            mark_stage("filling recovered sign-in form")
            page.locator("#email").fill(email)
            page.locator("#password").fill(password)
            mark_stage("submitting recovered sign-in form")
            page.get_by_role("button", name="Sign in").click()
            mark_stage("waiting for authenticated shell after offline recovery")
            page.locator(".app-shell").wait_for(state="visible", timeout=10_000)
            before_editor = _cache_snapshot(page)
            before_editor_paths = set(next(iter(before_editor.values())))
            page.goto(f"{origin}/app/projects/new", wait_until="networkidle")
            page.locator("#project-name").wait_for(state="visible")
            editor_cache = _cache_snapshot(page)
            editor_paths = set(next(iter(editor_cache.values())))
            new_editor_chunks = {
                path
                for path in editor_paths - before_editor_paths
                if path.startswith(IMMUTABLE_ASSET_PREFIX) and path.endswith(".js")
            }
            _require(
                bool(new_editor_chunks),
                "project editor did not load its immutable code on demand",
            )
            draft_title = "Disposable offline update draft"
            project_name = page.locator("#project-name")
            project_name.click()
            project_name.press_sequentially(draft_title)
            page.locator(".dirty-note").wait_for(state="visible")

            second_tab = context.new_page()
            second_tab.on("pageerror", lambda _error: page_errors.append(True))
            second_tab.on(
                "console",
                lambda message: (
                    csp_errors.append(True)
                    if "content security policy" in message.text.lower()
                    or "refused to" in message.text.lower()
                    else None
                ),
            )
            second_tab.goto(f"{origin}/app/login", wait_until="networkidle")
            _wait_for_initial_worker(second_tab, expected_version)

            mark_stage("cancelling navigation away from a dirty editor")
            close_dialogs: list[str] = []

            def cancel_dirty_close(dialog: Dialog) -> None:
                close_dialogs.append(dialog.type)
                dialog.dismiss()

            editor_url = page.url
            page.once("dialog", cancel_dirty_close)
            try:
                page.goto(f"{origin}/app/login", wait_until="domcontentloaded")
            except PlaywrightError:
                # Chromium reports the canceled cross-document navigation.
                pass
            _require(
                close_dialogs == ["beforeunload"]
                and not page.is_closed()
                and page.url == editor_url,
                "dirty editor navigation was not cancellable "
                f"(dialog types: {close_dialogs}, URL preserved: "
                f"{page.url == editor_url})",
            )
            _require(
                page.locator("#project-name").input_value() == draft_title,
                "dirty draft disappeared after canceling tab close",
            )

            mark_stage("preparing a candidate with a missing required asset")
            failed_root = private_dir / "web-root-failed-install"
            _, _, failed_version, _ = _prepare_browser_update(
                binary,
                web_root,
                private_dir,
                fixture_file,
                state_file,
                origin,
                port,
                failed_root,
                version_marker="disposable failed install fixture",
                missing_asset="/app/offline.css",
                runtime_name="runtime-pwa-failed",
            )
            mark_stage("verifying the candidate asset is unavailable")
            missing_asset = second_tab.request.get(f"{origin}/app/offline.css")
            _require(
                missing_asset.status == 503,
                "failed-install fixture did not make the allowlisted asset unavailable",
            )
            mark_stage("serving the failed-install candidate worker")
            failed_worker_url = second_tab.request.get(f"{origin}/app/sw.js")
            _require(
                failed_worker_url.status == 200
                and failed_version in failed_worker_url.text(),
                "failed-install candidate worker was not served",
            )
            mark_stage("asking the browser to install the failed candidate")
            second_tab.evaluate(
                """async () => {
                  window.__pwaInstallStates = [];
                  const registration = await navigator.serviceWorker
                    .getRegistration('/app/');
                  registration.addEventListener('updatefound', () => {
                    const installing = registration.installing;
                    if (!installing) return;
                    const record = () => window.__pwaInstallStates
                      .push(installing.state);
                    record();
                    installing.addEventListener('statechange', record);
                  });
                  await registration.update();
                }"""
            )
            mark_stage("waiting for failed candidate rollback")
            rolled_back = second_tab.evaluate(
                """() => {
                  if (window.__pwaInstallStates?.includes('redundant')) {
                    return Promise.resolve(true);
                  }
                  return new Promise(resolve => {
                    const registrationPromise = navigator.serviceWorker
                      .getRegistration('/app/');
                    const timer = setTimeout(() => resolve(false), 15_000);
                    registrationPromise.then(registration => {
                      const worker = registration?.installing;
                      if (!worker) {
                        clearTimeout(timer);
                        resolve(false);
                        return;
                      }
                      const check = () => {
                        if (worker.state !== 'redundant') return;
                        clearTimeout(timer);
                        resolve(true);
                      };
                      worker.addEventListener('statechange', check);
                      check();
                    });
                  });
                }"""
            )
            if not rolled_back:
                state = second_tab.evaluate(
                    """async () => {
                      const registration = await navigator.serviceWorker
                        .getRegistration('/app/');
                      return {
                        page: location.pathname,
                        ready: document.readyState,
                        installStates: window.__pwaInstallStates ?? null,
                        installing: registration?.installing?.state ?? '',
                        waiting: registration?.waiting?.state ?? '',
                        active: registration?.active?.state ?? '',
                        cachePaths: await Promise.all((await caches.keys())
                          .filter(
                          name => name.startsWith('stricknani-app-shell-')
                          )
                          .map(async name => ({
                            name,
                            paths: (await (await caches.open(name)).keys())
                              .map(request => new URL(request.url).pathname)
                              .sort()
                          })))
                      };
                    }"""
                )
                raise AcceptanceFailure(
                    "failed candidate rollback was not observed "
                    f"(safe state: {state}; asset responses: "
                    f"{service_worker_asset_responses[-12:]}; finished: "
                    f"{service_worker_asset_finished[-12:]})"
                )
            mark_stage("verifying failed update retained the active cache")
            failed_snapshot = _version_snapshot(second_tab)
            _require(
                failed_snapshot.get("active") == expected_version
                and failed_snapshot.get("waiting") == "",
                "failed update changed the active worker or left a waiting worker",
            )
            failed_caches = _cache_snapshot(second_tab)
            _require(
                len(failed_caches) == 1
                and expected_version in next(iter(failed_caches)),
                "failed update did not preserve only the active cache",
            )
            _require(
                set(next(iter(failed_caches.values()))).issubset(expected_assets),
                "failed update left a path outside the active version allowlist",
            )

            mark_stage("before update: opening an old-version tab the browser freezes")
            # Frozen background tabs never answer messages. The former update
            # protocol needed every tab to answer, so one frozen tab blocked
            # every update until all tabs were closed.
            _sign_in_through_api(second_tab, fixture_file)
            frozen_tab = context.new_page()
            frozen_tab.on("pageerror", lambda _error: page_errors.append(True))
            frozen_response = frozen_tab.goto(
                f"{origin}/app/projects", wait_until="networkidle", timeout=15_000
            )
            _require(
                frozen_response is not None and frozen_response.status == 200,
                "old-version tab did not load",
            )
            frozen_tab.locator(".app-shell").wait_for(state="visible", timeout=10_000)
            _wait_for_controlled_worker(frozen_tab, expected_version)
            frozen_time_origin = frozen_tab.evaluate("() => performance.timeOrigin")
            frozen_old_assets = _loaded_immutable_assets(frozen_tab)
            frozen_cdp = context.new_cdp_session(frozen_tab)
            frozen_cdp.send("Page.setWebLifecycleState", {"state": "frozen"})

            mark_stage("before update: opening a tab that ignores worker messages")
            context.add_init_script(
                """() => {
                  if (!new URL(location.href).searchParams
                      .has('pwa-unresponsive')) return;
                  const prototype = ServiceWorkerContainer.prototype;
                  const addEventListener = prototype.addEventListener;
                  prototype.addEventListener = function(type, listener, options) {
                    if (type === 'message') return;
                    return addEventListener.call(this, type, listener, options);
                  };
                }"""
            )
            unresponsive_tab = context.new_page()
            unresponsive_tab.on("pageerror", lambda _error: page_errors.append(True))
            unresponsive_tab.goto(
                f"{origin}/app/login?pwa-unresponsive=1", wait_until="networkidle"
            )
            _wait_for_controlled_worker(unresponsive_tab, expected_version)

            mark_stage("before update: putting the clean tab on an app page")
            second_tab.goto(f"{origin}/app/projects", wait_until="networkidle")
            second_tab.locator(".app-shell").wait_for(state="visible", timeout=10_000)
            _wait_for_controlled_worker(second_tab, expected_version)
            page_time_origin = page.evaluate("() => performance.timeOrigin")
            second_time_origin = second_tab.evaluate("() => performance.timeOrigin")

            mark_stage("restarting the actual Rust fixture with a new worker version")
            candidate_app_version = (
                "pwa-update-fixture-"
                + hashlib.sha256(
                    f"{expected_app_version}:candidate-v1".encode()
                ).hexdigest()[:24]
            )
            candidate_build_root = _build_candidate_web_root(
                private_dir, candidate_app_version
            )
            (
                candidate_build_version,
                verified_candidate_app_version,
                candidate_assets,
            ) = _read_asset_manifest(candidate_build_root)
            _require(
                verified_candidate_app_version == candidate_app_version,
                "second SvelteKit build did not use a distinct compiled version",
            )
            _, _, next_version, _ = _prepare_browser_update(
                binary,
                web_root,
                private_dir,
                fixture_file,
                state_file,
                origin,
                port,
                second_root,
                candidate_build_root=candidate_build_root,
                runtime_name="runtime-pwa-success",
            )
            _require(
                next_version == candidate_build_version,
                "Rust fixture did not serve the second build's worker version",
            )
            mark_stage("checking the served candidate worker version")
            candidate_worker = second_tab.request.get(f"{origin}/app/sw.js")
            _require(
                candidate_worker.status == 200
                and next_version in candidate_worker.text(),
                "served update worker has the wrong version",
            )

            mark_stage("update: letting the candidate take over without asking tabs")
            second_tab.evaluate(
                """async () => {
                  const registration = await navigator.serviceWorker
                    .getRegistration('/app/');
                  if (!registration) throw new Error('App worker missing');
                  await registration.update();
                }"""
            )
            _wait_for_active_worker(second_tab, next_version)
            takeover = _version_snapshot(second_tab)
            _require(
                takeover.get("active") == next_version
                and takeover.get("activeAppVersion") == candidate_app_version
                and takeover.get("waiting") == "",
                "the candidate worker waited instead of taking over "
                f"(active: {takeover.get('active')}, "
                f"waiting: {takeover.get('waiting')}, expected: {next_version})",
            )
            for open_tab in (second_tab, page, unresponsive_tab):
                _wait_for_controlled_worker(open_tab, next_version)
            _require(
                page.evaluate("() => performance.timeOrigin") == page_time_origin
                and second_tab.evaluate("() => performance.timeOrigin")
                == second_time_origin,
                "the worker takeover reloaded an open tab",
            )
            _require(
                page.locator("#project-name").input_value() == draft_title,
                "the worker takeover changed the unsaved project draft",
            )

            _sign_in_through_api(second_tab, fixture_file)

            mark_stage("update: checking retained caches after the takeover")
            takeover_caches = _cache_snapshot(second_tab)
            _require(
                f"{APP_CACHE_PREFIX}{expected_version}" in takeover_caches
                and f"{APP_CACHE_PREFIX}{next_version}" in takeover_caches
                and len(takeover_caches) == 2,
                "the takeover did not keep exactly the previous and the new cache "
                f"(caches: {sorted(takeover_caches)})",
            )
            _require(
                set(takeover_caches[f"{APP_CACHE_PREFIX}{next_version}"])
                == REQUIRED_OFFLINE_ASSETS,
                "the candidate install cached more than the offline assets",
            )
            _require(
                second_tab.evaluate(
                    "() => caches.has('stricknani-app-update-state-v1')"
                )
                is False,
                "the legacy update-state cache survived activation",
            )

            mark_stage("making a cached immutable route asset unavailable")
            cached_immutable_asset = next(
                (
                    asset
                    for asset in sorted(
                        (before_editor_paths | new_editor_chunks)
                        & set(candidate_assets)
                    )
                    if asset.startswith(IMMUTABLE_ASSET_PREFIX)
                    and asset.endswith(".js")
                    if (web_root / asset.removeprefix("/app/")).is_file()
                    and (candidate_build_root / asset.removeprefix("/app/")).is_file()
                    and hashlib.sha256(
                        (web_root / asset.removeprefix("/app/")).read_bytes()
                    ).digest()
                    == hashlib.sha256(
                        (
                            candidate_build_root / asset.removeprefix("/app/")
                        ).read_bytes()
                    ).digest()
                ),
                None,
            )
            _require(
                cached_immutable_asset is not None,
                "candidate build has no unchanged cached immutable asset",
            )
            assert cached_immutable_asset is not None
            unavailable_candidate_asset = (
                second_root / cached_immutable_asset.removeprefix("/app/")
            )
            _require(
                unavailable_candidate_asset.is_file(),
                "cached editor asset is absent from the update candidate",
            )
            unavailable_candidate_asset.unlink()
            direct_missing_asset = second_tab.request.get(
                f"{origin}{cached_immutable_asset}", timeout=10_000
            )
            _require(
                direct_missing_asset.status >= 400,
                "candidate still serves the selected cached editor asset",
            )

            mark_stage("update: detecting the new version in an open tab")
            # SvelteKit checks version.json when a tab regains focus.
            second_tab.evaluate("() => window.dispatchEvent(new Event('focus'))")
            second_tab.get_by_text("New version ready", exact=True).wait_for(
                state="visible", timeout=10_000
            )
            _require(
                second_tab.evaluate("() => performance.timeOrigin")
                == second_time_origin,
                "detecting the new version reloaded the tab by itself",
            )
            second_tab.screenshot(
                path=str(screenshots / "update-ready-mobile.png"), caret="hide"
            )

            mark_stage("update: refusing to reload a dirty tab")
            page.evaluate("() => window.dispatchEvent(new Event('focus'))")
            page.get_by_text("New version ready", exact=True).wait_for(
                state="visible", timeout=10_000
            )
            page.get_by_role("button", name="Reload", exact=True).click()
            page.get_by_text(
                "Save your changes first, then reload.", exact=True
            ).wait_for(state="visible", timeout=5_000)
            page.screenshot(
                path=str(screenshots / "update-blocked-dirty-mobile.png"), caret="hide"
            )
            _require(
                page.evaluate("() => performance.timeOrigin") == page_time_origin
                and page.locator("#project-name").input_value() == draft_title,
                "the reload chip reloaded a dirty tab or lost its draft",
            )

            mark_stage("update: loading the new version on a clean navigation")
            update_dialogs: list[str] = []
            second_tab.on("dialog", lambda dialog: update_dialogs.append(dialog.type))
            second_tab.get_by_role("link", name="Yarns", exact=True).first.click()
            second_tab.wait_for_url(f"{origin}/app/yarns", timeout=15_000)
            _wait_for_async_condition(
                second_tab,
                "origin => performance.timeOrigin > origin",
                second_time_origin,
                "a full load after the clean navigation",
            )
            second_tab.locator(".app-shell").wait_for(state="visible", timeout=10_000)
            _require(not update_dialogs, "the update navigation opened a dialog")
            new_assets = _loaded_immutable_assets(second_tab)
            _require(
                bool(new_assets) and new_assets.issubset(candidate_assets),
                "the clean navigation did not load the candidate build",
            )
            _require(
                second_tab.get_by_text("New version ready", exact=True).count() == 0,
                "the reloaded tab still announces an update",
            )
            second_tab.get_by_text("Stricknani was updated.", exact=True).wait_for(
                state="visible", timeout=5_000
            )
            second_tab.screenshot(
                path=str(screenshots / "updated-mobile.png"), caret="hide"
            )

            mark_stage("update: loading the new version once the draft is clean")
            project_name.fill("")
            page.locator(".dirty-note").wait_for(state="hidden", timeout=5_000)
            page.get_by_role("link", name="Projects", exact=True).first.click()
            page.wait_for_url(f"{origin}/app/projects", timeout=15_000)
            _wait_for_async_condition(
                page,
                "origin => performance.timeOrigin > origin",
                page_time_origin,
                "a full load after the draft was cleared",
            )
            page.locator(".app-shell").wait_for(state="visible", timeout=10_000)
            _require(
                _loaded_immutable_assets(page).issubset(candidate_assets),
                "the cleaned tab did not load the candidate build",
            )

            mark_stage("update: thawing the frozen old-version tab")
            frozen_cdp.send("Page.setWebLifecycleState", {"state": "active"})
            _require(
                frozen_tab.evaluate("() => performance.timeOrigin")
                == frozen_time_origin,
                "the frozen tab was reloaded while it was frozen",
            )
            _wait_for_controlled_worker(frozen_tab, next_version)
            # The candidate build differs only in its version name, so its
            # chunks match the old ones. Remove one of the old tab's cached
            # chunks from the server to prove the tab still gets it from the
            # retained previous-version cache.
            old_cache_paths = set(
                _cache_snapshot(frozen_tab).get(
                    f"{APP_CACHE_PREFIX}{expected_version}", []
                )
            )
            old_only_asset = next(
                (
                    asset
                    for asset in sorted(frozen_old_assets & old_cache_paths)
                    if asset.endswith(".js")
                    and asset != cached_immutable_asset
                    and (web_root / asset.removeprefix("/app/")).is_file()
                ),
                None,
            )
            _require(
                old_only_asset is not None,
                "the old tab has no chunk in the retained previous-version cache",
            )
            assert old_only_asset is not None
            (second_root / old_only_asset.removeprefix("/app/")).unlink(missing_ok=True)
            _require(
                frozen_tab.request.get(f"{origin}{old_only_asset}").status >= 400,
                "the server still serves the removed previous-version chunk",
            )
            old_chunk = frozen_tab.evaluate(
                """async asset => {
                  const response = await fetch(asset, { cache: 'no-store' });
                  const bytes = await response.arrayBuffer();
                  const digest = await crypto.subtle.digest('SHA-256', bytes);
                  return {
                    status: response.status,
                    sha256: Array.from(new Uint8Array(digest))
                      .map(value => value.toString(16).padStart(2, '0')).join(''),
                  };
                }""",
                old_only_asset,
            )
            _require(
                isinstance(old_chunk, dict)
                and old_chunk.get("status") == 200
                and old_chunk.get("sha256")
                == hashlib.sha256(
                    (web_root / old_only_asset.removeprefix("/app/")).read_bytes()
                ).hexdigest(),
                "the old tab could not load its previous-version chunk",
            )
            frozen_tab.evaluate("() => window.dispatchEvent(new Event('focus'))")
            frozen_tab.get_by_text("New version ready", exact=True).wait_for(
                state="visible", timeout=10_000
            )
            frozen_tab.get_by_role("link", name="Yarns", exact=True).first.click()
            frozen_tab.wait_for_url(f"{origin}/app/yarns", timeout=15_000)
            _wait_for_async_condition(
                frozen_tab,
                "origin => performance.timeOrigin > origin",
                frozen_time_origin,
                "a full load of the thawed tab",
            )
            frozen_tab.locator(".app-shell").wait_for(state="visible", timeout=10_000)
            _require(
                _loaded_immutable_assets(frozen_tab).issubset(candidate_assets),
                "the thawed tab did not move to the candidate build",
            )
            frozen_tab.close()
            unresponsive_tab.close()

            mark_stage("update: preparing a network-failure fallback tab")
            asset_response_start = len(service_worker_asset_responses)
            network_failure_tab = context.new_page()
            network_dialogs: list[str] = []

            def dismiss_unexpected_network_dialog(dialog: Dialog) -> None:
                network_dialogs.append(dialog.type)
                dialog.dismiss()

            network_failure_tab.on("dialog", dismiss_unexpected_network_dialog)
            network_failure_tab.on("pageerror", lambda _error: page_errors.append(True))
            network_failure_tab.on(
                "console",
                lambda message: (
                    csp_errors.append(True)
                    if "content security policy" in message.text.lower()
                    or "refused to" in message.text.lower()
                    else None
                ),
            )
            network_page = network_failure_tab.goto(
                f"{origin}/app/login",
                wait_until="domcontentloaded",
                timeout=15_000,
            )
            _require(
                network_page is not None and network_page.status == 200,
                "network-failure fixture tab did not load the app",
            )
            _wait_for_controlled_worker(network_failure_tab, next_version)

            mark_stage("forcing a network error for a retained immutable asset")
            network_failure_tab.bring_to_front()
            visibility_state = network_failure_tab.evaluate(
                "() => document.visibilityState"
            )
            _require(
                visibility_state == "visible" and not network_dialogs,
                "network-failure fixture tab is not foregrounded or has a dialog",
            )
            context.set_offline(True)
            try:
                page_state = network_failure_tab.evaluate(
                    """() => ({
                      offline: navigator.onLine === false,
                      controlled: Boolean(
                        navigator.serviceWorker.controller?.scriptURL.endsWith('/app/sw.js'),
                      ),
                    })"""
                )
                _require(
                    isinstance(page_state, dict)
                    and page_state.get("offline") is True
                    and page_state.get("controlled") is True,
                    "browser page was not responsive and controlled after offline mode",
                )
                mark_stage("clearing the candidate cache entry for network fallback")
                network_failure_tab.evaluate(
                    """async ({ asset, cacheName }) => {
                      const operation = (async () => {
                        return await (await caches.open(cacheName)).delete(
                          asset, { ignoreSearch: false },
                        );
                      })();
                      return await Promise.race([
                        operation,
                        new Promise(resolve => setTimeout(() => resolve(null), 3000)),
                      ]);
                    }""",
                    {
                        "asset": cached_immutable_asset,
                        "cacheName": f"{APP_CACHE_PREFIX}{next_version}",
                    },
                )
                previous_state = network_failure_tab.evaluate(
                    """async ({ asset, oldCache, candidateCache }) => {
                      const operation = (async () => {
                        const previous = await caches.open(oldCache);
                        const candidate = await caches.open(candidateCache);
                        return {
                          cacheOperationsSettled: true,
                          oldEntryPresent: Boolean(await previous.match(
                            asset, { ignoreSearch: false },
                          )),
                          candidateMissBeforeFetch: !await candidate.match(
                            asset, { ignoreSearch: false },
                          ),
                        };
                      })();
                      return await Promise.race([
                        operation,
                        new Promise(resolve => setTimeout(
                          () => resolve({ cacheOperationsSettled: false }), 3000,
                        )),
                      ]);
                    }""",
                    {
                        "asset": cached_immutable_asset,
                        "oldCache": f"{APP_CACHE_PREFIX}{expected_version}",
                        "candidateCache": f"{APP_CACHE_PREFIX}{next_version}",
                    },
                )
                _require(
                    isinstance(previous_state, dict)
                    and previous_state.get("cacheOperationsSettled") is True
                    and previous_state.get("oldEntryPresent") is True
                    and previous_state.get("candidateMissBeforeFetch") is True,
                    "network fallback fixture did not isolate the retained entry",
                )
                mark_stage(
                    "requesting the retained asset while browser networking fails"
                )
                asset_failure_start = len(service_worker_asset_failures)
                fetch_state = network_failure_tab.evaluate(
                    """async ({ asset, oldCache }) => {
                      const retained = await (await caches.open(oldCache)).match(
                        asset, { ignoreSearch: false },
                      );
                      const hash = async bytes => Array.from(new Uint8Array(
                        await crypto.subtle.digest('SHA-256', bytes),
                      )).map(value => value.toString(16).padStart(2, '0')).join('');
                      const retainedHash = retained
                        ? await hash(await retained.arrayBuffer())
                        : '';
                      const controller = new AbortController();
                      const request = fetch(asset, {
                        cache: 'no-store',
                        signal: controller.signal,
                      }).then(async response => {
                        const bytes = await response.arrayBuffer();
                        return {
                          fetchSettled: true,
                          status: response.status,
                          byteLength: bytes.byteLength,
                          matchesRetainedBytes: retainedHash !== '' &&
                            await hash(bytes) === retainedHash,
                          fetchErrorName: '',
                        };
                      }).catch(error => ({
                        fetchSettled: true,
                        status: 0,
                        byteLength: 0,
                        matchesRetainedBytes: false,
                        fetchErrorName: error?.name ?? 'unknown',
                      }));
                      const result = await Promise.race([
                        request,
                        new Promise(resolve => setTimeout(
                          () => resolve(null), 8000,
                        )),
                      ]);
                      if (result === null) {
                        controller.abort();
                        return {
                          fetchSettled: false,
                          status: 0,
                          byteLength: 0,
                          matchesRetainedBytes: false,
                          fetchErrorName: 'timeout',
                        };
                      }
                      return result;
                    }""",
                    {
                        "asset": cached_immutable_asset,
                        "oldCache": f"{APP_CACHE_PREFIX}{expected_version}",
                    },
                )
                _require(
                    isinstance(fetch_state, dict)
                    and fetch_state.get("fetchSettled") is True,
                    "controlled network-failure request did not settle",
                )
                mark_stage("waiting for the retained asset cache promotion")
                _wait_for_async_condition(
                    network_failure_tab,
                    """async ({ asset, cacheName }) => Boolean(await (
                      await Promise.race([
                        (async () => Boolean(await (
                          await caches.open(cacheName)
                        ).match(asset, { ignoreSearch: false })))(),
                        new Promise(resolve => setTimeout(() => resolve(false), 500)),
                      ])
                    ))""",
                    {
                        "asset": cached_immutable_asset,
                        "cacheName": f"{APP_CACHE_PREFIX}{next_version}",
                    },
                    "network-failure asset cache promotion",
                    timeout_ms=5_000,
                )
                worker_failure_count = len(service_worker_asset_failures) - (
                    asset_failure_start
                )
                worker_response_count = sum(
                    response == f"{cached_immutable_asset}:200"
                    for response in service_worker_asset_responses[
                        asset_response_start:
                    ]
                )
                promoted_state = network_failure_tab.evaluate(
                    """async ({ asset, cacheName }) => {
                      const operation = (async () => {
                        const candidate = await caches.open(cacheName);
                        const promoted = Boolean(await candidate.match(
                          asset, { ignoreSearch: false },
                        ));
                        const clearedForHttpFallback = promoted && await (
                          candidate.delete(asset, { ignoreSearch: false })
                        );
                        return {
                          cacheOperationsSettled: true,
                          promoted,
                          clearedForHttpFallback,
                        };
                      })();
                      return await Promise.race([
                        operation,
                        new Promise(resolve => setTimeout(
                          () => resolve({
                            cacheOperationsSettled: false,
                            promoted: false,
                            clearedForHttpFallback: false,
                          }), 3000,
                        )),
                      ]);
                    }""",
                    {
                        "asset": cached_immutable_asset,
                        "cacheName": f"{APP_CACHE_PREFIX}{next_version}",
                    },
                )
                fallback_state = {
                    **page_state,
                    **previous_state,
                    **fetch_state,
                    **promoted_state,
                    "workerFailureCount": worker_failure_count,
                    "workerResponseCount": worker_response_count,
                    "offlineObserved": page_state["offline"],
                    "controlledByAppWorker": page_state["controlled"],
                    "diagnosticTimeout": False,
                    "timeoutStage": "",
                }
            finally:
                context.set_offline(False)
            _require(
                isinstance(fallback_state, dict)
                and fallback_state.get("offlineObserved") is True
                and fallback_state.get("oldEntryPresent") is True
                and fallback_state.get("candidateMissBeforeFetch") is True
                and fallback_state.get("fetchSettled") is True
                and fallback_state.get("status") == 200
                and isinstance(fallback_state.get("byteLength"), int)
                and fallback_state["byteLength"] > 0
                and fallback_state.get("matchesRetainedBytes") is True
                and fallback_state.get("workerFailureCount", 0) > 0
                and fallback_state.get("promoted") is True
                and fallback_state.get("cacheOperationsSettled") is True
                and fallback_state.get("clearedForHttpFallback") is True,
                "network-failure fallback was not promoted into the candidate cache "
                f"(safe state: {{'timeout': {fallback_state.get('diagnosticTimeout')}, "
                f"'stage': {fallback_state.get('timeoutStage')}, "
                f"'settled': {fallback_state.get('fetchSettled')}, "
                f"'status': {fallback_state.get('status')}, "
                f"'error': {fallback_state.get('fetchErrorName')}, "
                f"'controlled': {fallback_state.get('controlledByAppWorker')}, "
                f"'oldEntry': {fallback_state.get('oldEntryPresent')}, "
                f"'workerFailures': {fallback_state.get('workerFailureCount')}, "
                f"'workerResponses': {fallback_state.get('workerResponseCount')}, "
                f"'dialog': {bool(network_dialogs)}, "
                f"'promoted': {fallback_state.get('promoted')}}})",
            )
            mark_stage("checking HTTP-error fallback from a controlled app page")
            missing_asset_response = network_failure_tab.request.get(
                f"{origin}{cached_immutable_asset}", timeout=10_000
            )
            _require(
                missing_asset_response.status >= 400,
                "HTTP-error fallback fixture unexpectedly serves the immutable asset",
            )
            http_fallback_state = network_failure_tab.evaluate(
                """async ({ asset, oldCache }) => {
                  const retained = await (await caches.open(oldCache)).match(
                    asset, { ignoreSearch: false },
                  );
                  const hash = async bytes => Array.from(new Uint8Array(
                    await crypto.subtle.digest('SHA-256', bytes),
                  )).map(value => value.toString(16).padStart(2, '0')).join('');
                  const retainedHash = retained
                    ? await hash(await retained.arrayBuffer())
                    : '';
                  try {
                    const response = await fetch(asset, { cache: 'no-store' });
                    const bytes = await response.arrayBuffer();
                    return {
                      settled: true,
                      status: response.status,
                      byteLength: bytes.byteLength,
                      matchesRetainedBytes: retainedHash !== '' &&
                        await hash(bytes) === retainedHash,
                    };
                  } catch {
                    return {
                      settled: true,
                      status: 0,
                      byteLength: 0,
                      matchesRetainedBytes: false,
                    };
                  }
                }""",
                {
                    "asset": cached_immutable_asset,
                    "oldCache": f"{APP_CACHE_PREFIX}{expected_version}",
                },
            )
            _require(
                isinstance(http_fallback_state, dict)
                and http_fallback_state.get("settled") is True
                and http_fallback_state.get("status") == 200
                and isinstance(http_fallback_state.get("byteLength"), int)
                and http_fallback_state["byteLength"] > 0
                and http_fallback_state.get("matchesRetainedBytes") is True,
                "HTTP-error fallback did not return the retained old-cache bytes",
            )
            _wait_for_async_condition(
                network_failure_tab,
                """async ({ asset, cacheName }) => Boolean(await (
                  await caches.open(cacheName)
                ).match(asset, { ignoreSearch: false }))""",
                {
                    "asset": cached_immutable_asset,
                    "cacheName": f"{APP_CACHE_PREFIX}{next_version}",
                },
                "HTTP-error asset promotion into the candidate cache",
                timeout_ms=10_000,
            )
            network_failure_tab.close()

            mark_stage("update: pruning to the newest three app versions")
            later_versions: list[str] = []
            for index in (3, 4):
                _, _, later_version, _ = _prepare_browser_update(
                    binary,
                    candidate_build_root,
                    private_dir,
                    fixture_file,
                    state_file,
                    origin,
                    port,
                    private_dir / f"web-root-v{index}",
                    version_marker=f"disposable cache retention fixture {index}",
                    runtime_name=f"runtime-pwa-v{index}",
                )
                later_versions.append(later_version)
                page.evaluate(
                    """async () => {
                      const registration = await navigator.serviceWorker
                        .getRegistration('/app/');
                      await registration.update();
                    }"""
                )
                _wait_for_active_worker(page, later_version)
            retained = _cache_snapshot(page)
            _require(
                set(retained)
                == {
                    f"{APP_CACHE_PREFIX}{version}"
                    for version in (next_version, *later_versions)
                },
                "the worker did not keep exactly the newest three app caches "
                f"(caches: {sorted(retained)})",
            )
            _require(
                activation_worker_errors == [],
                "browser reported an app worker error during updates "
                f"(sanitized class/line: {activation_worker_errors[-8:]})",
            )

            mark_stage("checking online recovery and console policy")
            _require(page_errors == [], "browser reported a page exception")
            _require(csp_errors == [], "browser reported a CSP violation")
            context.close()
            browser.close()
        except BaseException:
            browser.close()
            raise
    return screenshots


def run_live_fixture(
    binary: Path,
    web_root: Path,
    private_dir: Path,
    chromium_executable: Path | None,
) -> Path:
    fixture_file = private_dir / "fixture.json"
    state_file = private_dir / "fixture-state.json"
    rust_fixture.start_fixture(
        binary=binary,
        temp_dir=private_dir / "runtime-v1",
        fixture_file=fixture_file,
        state_file=state_file,
        web_root=web_root,
    )
    try:
        return run_acceptance(
            binary,
            web_root,
            private_dir,
            fixture_file,
            state_file,
            chromium_executable,
        )
    finally:
        if state_file.exists():
            rust_fixture.stop_fixture(state_file)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--binary", type=Path, required=True)
    parser.add_argument("--web-root", type=Path, required=True)
    parser.add_argument("--private-dir", type=Path)
    parser.add_argument("--chromium-executable", type=Path)
    args = parser.parse_args(argv)
    private_dir: Path | None = None
    try:
        private_dir = (
            web_ui_browser._private_work_dir(args.private_dir)
            if args.private_dir is not None
            else Path(tempfile.mkdtemp(prefix="stricknani-web-pwa-"))
        )
        private_dir.chmod(0o700)
        screenshots = run_live_fixture(
            args.binary,
            args.web_root,
            private_dir,
            args.chromium_executable,
        )
    except (rust_fixture.FixtureError, AcceptanceFailure) as error:
        print(f"PWA browser acceptance failed: {error}", file=sys.stderr)
        return 1
    except (
        PlaywrightError,
        AssertionError,
        AttributeError,
        OSError,
        RuntimeError,
        TypeError,
        ValueError,
    ) as error:
        stage = "unknown stage"
        if private_dir is not None:
            stage_file = private_dir / "browser-stage.txt"
            if stage_file.is_file():
                stage = stage_file.read_text(encoding="ascii")
        frames = [
            frame
            for frame in traceback.extract_tb(error.__traceback__)
            if Path(frame.filename).name == "web_pwa_browser.py"
        ][-3:]
        safe_frames = ", ".join(f"{frame.name}:{frame.lineno}" for frame in frames)
        print(
            "PWA browser acceptance failed during "
            f"{stage} ({type(error).__name__}; frames: {safe_frames}); "
            "details suppressed.",
            file=sys.stderr,
        )
        return 1
    print(
        "PWA browser acceptance passed: app-scope, exact cache allowlist, "
        "core-only install, on-demand routes, failed-install rollback, dirty close, "
        "update lifecycle, and offline recovery."
    )
    print(f"Sanitized screenshots: {screenshots}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
