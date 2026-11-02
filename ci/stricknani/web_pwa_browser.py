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
import sys
import tempfile
import time
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
            if (!worker) return resolve('');
            const channel = new MessageChannel();
            const timer = setTimeout(() => resolve('timeout'), 1500);
            channel.port1.onmessage = event => {
              clearTimeout(timer);
              resolve(event.data?.version ?? 'invalid');
              channel.port1.close();
            };
            worker.postMessage({ type: 'GET_VERSION' }, [channel.port2]);
          });
          return {
            scope: new URL(registration.scope).pathname,
            active: await ask(registration.active),
            activeState: registration.active?.state ?? '',
            installing: await ask(registration.installing),
            installingState: registration.installing?.state ?? '',
            waiting: await ask(registration.waiting),
            waitingState: registration.waiting?.state ?? '',
          };
        }"""
    )
    if not isinstance(value, dict):
        raise AcceptanceFailure("service worker registration state is malformed")
    return value


def _registration_state_snapshot(page: Page) -> dict[str, object]:
    value: object = page.evaluate(
        """async () => {
          const registration = await navigator.serviceWorker.getRegistration('/app/');
          if (!registration) return { scope: '', activeState: '', waitingState: '' };
          return {
            scope: new URL(registration.scope).pathname,
            activeState: registration.active?.state ?? '',
            installingState: registration.installing?.state ?? '',
            waitingState: registration.waiting?.state ?? '',
            cacheNames: (await caches.keys()).filter(name =>
              name.startsWith('stricknani-app-shell-')
            ),
          };
        }"""
    )
    if not isinstance(value, dict):
        raise AcceptanceFailure("service worker lifecycle state is malformed")
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


def _read_asset_manifest(web_root: Path) -> tuple[str, list[str]]:
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
    digest_rows: list[str] = []
    for path in assets:
        asset_path = web_root / path.removeprefix("/app/")
        try:
            digest = hashlib.sha256(asset_path.read_bytes()).hexdigest()
        except OSError as error:
            raise AcceptanceFailure("generated PWA asset is missing") from error
        digest_rows.append(f"{path}\0{digest}\n")
    calculated_version = hashlib.sha256(
        ("".join(digest_rows) + "\0" + worker_template).encode()
    ).hexdigest()
    if calculated_version != version:
        raise AcceptanceFailure("generated PWA version does not match its assets")
    expected_worker = worker_template.replace("__APP_VERSION__", version).replace(
        "__APP_ASSETS__", json.dumps(assets, separators=(",", ":"))
    )
    try:
        actual_worker = (web_root / "app-sw.js").read_text(encoding="utf-8")
    except (OSError, UnicodeError) as error:
        raise AcceptanceFailure("generated app worker is unavailable") from error
    if "__APP_" in actual_worker or actual_worker != expected_worker:
        raise AcceptanceFailure("generated app worker differs from the build output")
    return version, assets


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
    version = hashlib.sha256(("".join(rows) + "\0" + template).encode()).hexdigest()
    manifest["version"] = version
    manifest_path.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    worker = template.replace("__APP_VERSION__", version).replace(
        "__APP_ASSETS__", json.dumps(sorted(assets), separators=(",", ":"))
    )
    if "__APP_" in worker:
        raise AcceptanceFailure("generated app worker has an unexpanded marker")
    (target / "app-sw.js").write_text(worker, encoding="utf-8")
    return version


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
    runtime_name: str = "runtime-v2",
) -> tuple[Path, Path, str]:
    expected_version = _write_next_web_root(web_root, second_root, version_marker)
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
    return fixture_file, state_file, expected_version


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
    expected_version, expected_assets = _read_asset_manifest(web_root)
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

    def persist_reopen_snapshot(
        stage: str,
        registration: dict[str, object],
        request_status: int | None = None,
    ) -> None:
        snapshot_path = private_dir / "pwa-reopen-diagnostic.json"
        payload = {
            "stage": stage,
            "registration": registration,
            "cache_paths": _cache_snapshot(monitor),
            "request_status": request_status,
            "request_events": reopen_events[-8:],
            "page_paths": sorted(
                urlsplit(candidate.url).path or "/" for candidate in context.pages
            ),
        }
        temporary_path = snapshot_path.with_suffix(".tmp")
        temporary_path.write_text(
            json.dumps(payload, sort_keys=True) + "\n", encoding="utf-8"
        )
        temporary_path.chmod(0o600)
        temporary_path.replace(snapshot_path)

    if chromium_executable is not None:
        chromium_executable = chromium_executable.expanduser().resolve()
        if not chromium_executable.is_file() or not os.access(
            chromium_executable, os.X_OK
        ):
            raise AcceptanceFailure("configured Chromium executable is unavailable")

    page_errors: list[bool] = []
    csp_errors: list[bool] = []
    activation_worker_errors: list[tuple[str, int | None]] = []
    service_worker_asset_requests: list[str] = []
    service_worker_asset_responses: list[str] = []
    service_worker_asset_finished: list[str] = []
    reopen_events: list[str] = []

    def record_worker_error(event: object) -> None:
        if not current_stage.startswith(("closing", "reopening")):
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
            page = context.new_page()
            cdp = context.new_cdp_session(page)
            cdp.send("ServiceWorker.enable")
            cdp.on("ServiceWorker.workerErrorReported", record_worker_error)
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
                expected_version in worker_text and "__APP_" not in worker_text,
                "Rust did not serve the generated versioned worker",
            )
            _require(
                "skipWaiting" not in worker_text and "clients.claim" not in worker_text,
                "app worker attempts eager activation or claims existing tabs",
            )
            _require(
                'pathname === "/app"' in worker_text,
                "worker cache retention does not recognize an exact /app client",
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
            _, _, failed_version = _prepare_browser_update(
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

            mark_stage("restarting the actual Rust fixture with a new worker version")
            _, _, next_version = _prepare_browser_update(
                binary,
                web_root,
                private_dir,
                fixture_file,
                state_file,
                origin,
                port,
                second_root,
                version_marker="disposable successful update fixture",
                runtime_name="runtime-pwa-success",
            )
            mark_stage("checking the served candidate worker version")
            candidate_worker = second_tab.request.get(f"{origin}/app/sw.js")
            _require(
                candidate_worker.status == 200
                and next_version in candidate_worker.text(),
                "served update worker has the wrong version",
            )
            second_tab.goto(f"{origin}/app/login", wait_until="networkidle")
            second_tab.evaluate(
                """async () => {
                  const registration = await navigator.serviceWorker
                    .getRegistration('/app/');
                  if (!registration) throw new Error('App worker missing');
                  await registration.update();
                }"""
            )
            _wait_for_async_condition(
                second_tab,
                """expected => navigator.serviceWorker
                  .getRegistration('/app/').then(async registration => {
                    const worker = registration?.waiting;
                    if (!worker) return false;
                    return await new Promise(resolve => {
                      const channel = new MessageChannel();
                      const timer = setTimeout(() => resolve(false), 1000);
                      channel.port1.onmessage = event => {
                        clearTimeout(timer);
                        resolve(event.data?.version === expected);
                        channel.port1.close();
                      };
                      worker.postMessage({ type: 'GET_VERSION' }, [channel.port2]);
                    });
                  })""",
                next_version,
                "candidate worker waiting state",
            )
            waiting = _version_snapshot(second_tab)
            _require(
                waiting.get("active") == expected_version,
                "active worker changed while app tabs were open",
            )
            _require(
                waiting.get("waiting") == next_version,
                "new worker did not wait for open app tabs "
                f"(active: {waiting.get('active')}, "
                f"active state: {waiting.get('activeState')}, "
                f"installing: {waiting.get('installing')}, "
                f"installing state: {waiting.get('installingState')}, "
                f"waiting: {waiting.get('waiting')}, "
                f"waiting state: {waiting.get('waitingState')}, "
                f"expected: {next_version}, page: {second_tab.url})",
            )
            waiting_caches = _cache_snapshot(second_tab)
            candidate_cache_paths = waiting_caches.get(
                f"stricknani-app-shell-{next_version}"
            )
            _require(
                candidate_cache_paths is not None
                and set(candidate_cache_paths) == REQUIRED_OFFLINE_ASSETS,
                "waiting candidate did not cache only the required offline assets",
            )
            _require(
                second_tab.get_by_role("heading", name="Update available").is_visible(),
                "the app did not announce a waiting worker",
            )
            _require(
                second_tab.get_by_text(
                    "Close all Stricknani tabs and reopen the app to finish "
                    "installing the update."
                ).is_visible(),
                "the update notice does not explain the safe activation step",
            )

            mark_stage("checking out-of-scope activation monitor")
            _require(
                monitor.url.endswith("/healthz"), "worker monitor left health scope"
            )
            mark_stage("closing the dirty editor and app tabs for activation")
            close_dialogs.clear()

            def accept_dirty_close(dialog: Dialog) -> None:
                close_dialogs.append(dialog.type)
                dialog.accept()

            page.once("dialog", accept_dirty_close)
            with page.expect_event("close", timeout=10_000):
                page.close(run_before_unload=True)
            _require(
                close_dialogs == ["beforeunload"] and page.is_closed(),
                "confirmed dirty close did not leave the app client",
            )
            _require(
                _version_snapshot(second_tab).get("active") == expected_version,
                "waiting worker activated before the dirty tab left the app",
            )

            mark_stage("closing all app tabs before reopening the app")
            second_tab.close()
            closed_tabs_snapshot = _registration_state_snapshot(monitor)
            _require(
                (
                    closed_tabs_snapshot.get("activeState") == "activated"
                    and closed_tabs_snapshot.get("waitingState") == "installed"
                )
                or (
                    closed_tabs_snapshot.get("activeState") == "activated"
                    and closed_tabs_snapshot.get("waitingState") == ""
                ),
                "worker versions changed unexpectedly after closing the app tabs",
            )

            mark_stage("reopening app to finish worker activation")
            app_url = f"{origin}/app/login"
            state_before_reopen = _registration_state_snapshot(monitor)
            persist_reopen_snapshot("before-direct-request", state_before_reopen)
            direct_response = monitor.request.get(app_url, timeout=10_000)
            _require(
                direct_response.status == 200,
                "app resource failed before the reopen navigation",
            )
            persist_reopen_snapshot(
                "before-browser-navigation",
                _registration_state_snapshot(monitor),
                direct_response.status,
            )
            try:
                reopen_events.clear()
                response = monitor.goto(app_url, wait_until="commit", timeout=10_000)
            except PlaywrightError as error:
                raise AcceptanceFailure(
                    "app reopen navigation timed out after a successful direct request "
                    f"(safe page path: {urlsplit(monitor.url).path}, "
                    f"registration before reopen: {state_before_reopen}, "
                    f"app request events: {reopen_events[-8:]})"
                ) from error
            _require(
                response is not None and response.status == 200,
                "app did not reopen after closing the old clients",
            )
            try:
                _wait_for_async_condition(
                    monitor,
                    """async expected => {
                  const registration = await navigator.serviceWorker
                    .getRegistration('/app/');
                  if (registration?.active?.state !== 'activated'
                      || registration.waiting !== null) return false;
                  const names = await caches.keys();
                  const appCaches = names.filter(name =>
                    name.startsWith('stricknani-app-shell-')
                  );
                  return appCaches.length === 1
                    && appCaches[0] === `stricknani-app-shell-${expected}`
                    && registration.active?.state === 'activated'
                    && registration.waiting === null;
                }""",
                    next_version,
                    "stable active worker and cache after app reopen",
                    timeout_ms=20_000,
                )
            except (PlaywrightError, AcceptanceFailure) as error:
                open_page_paths = sorted(
                    urlsplit(candidate.url).path or "/" for candidate in context.pages
                )
                monitor_controlled = monitor.evaluate(
                    "() => Boolean(navigator.serviceWorker.controller)"
                )
                raise AcceptanceFailure(
                    "candidate worker/cache did not reach one stable active version "
                    f"(caches: {_cache_snapshot(monitor)}, "
                    f"registration: {_version_snapshot(monitor)}, "
                    f"open page paths: {open_page_paths}, "
                    f"monitor controller: {monitor_controlled}, "
                    f"worker errors: {activation_worker_errors[-8:]})"
                ) from error
            activated_snapshot = _version_snapshot(monitor)
            _require(
                activated_snapshot.get("active") == next_version
                and activated_snapshot.get("activeState") == "activated"
                and activated_snapshot.get("waiting") == "",
                "candidate worker activated with an unexpected version",
            )
            final_caches = _cache_snapshot(monitor)
            _require(
                len(final_caches) == 1 and next_version in next(iter(final_caches)),
                "activation did not leave exactly the new bounded app cache "
                f"(caches: {final_caches}, expected: {next_version}, "
                f"worker errors: {activation_worker_errors[-8:]}, "
                f"registration: {_version_snapshot(monitor)})",
            )
            _require(
                activation_worker_errors == [],
                "browser reported an app worker activation error "
                f"(sanitized class/line: {activation_worker_errors[-8:]})",
            )
            final_paths = next(iter(final_caches.values()))
            _require(
                REQUIRED_OFFLINE_ASSETS.issubset(final_paths)
                and set(final_paths).issubset(expected_assets),
                "reopened app cache omitted core assets or contained an "
                "unallowlisted path",
            )
            _wait_for_controlled_worker(monitor, next_version)
            monitor.wait_for_load_state("networkidle", timeout=15_000)
            _wait_for_async_condition(
                monitor,
                """async expected => {
                  const loaded = [...new Set(
                    performance.getEntriesByType('resource').map(entry => {
                      try { return new URL(entry.name).pathname; }
                      catch { return ''; }
                    }).filter(path => path.startsWith('/app/_app/immutable/')
                      && (path.endsWith('.js') || path.endsWith('.css')))
                  )];
                  const cache = await caches.open(
                    `stricknani-app-shell-${expected}`
                  );
                  const cached = await Promise.all(
                    loaded.map(async path => Boolean(await cache.match(path)))
                  );
                  return cached.every(Boolean);
                }""",
                next_version,
                "reopened route immutable assets to finish caching",
                timeout_ms=15_000,
            )
            recovered_caches = _cache_snapshot(monitor)
            recovered_paths = set(next(iter(recovered_caches.values())))
            _require(
                REQUIRED_OFFLINE_ASSETS.issubset(recovered_paths)
                and _loaded_immutable_assets(monitor).issubset(recovered_paths)
                and recovered_paths.issubset(expected_assets),
                "updated worker did not recover using only core and on-demand "
                "public assets",
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
        print(
            "PWA browser acceptance failed during "
            f"{stage} ({type(error).__name__}); details suppressed.",
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
