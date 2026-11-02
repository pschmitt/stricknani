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


def _activation_state_snapshot(page: Page) -> dict[str, bool]:
    value: object = page.evaluate(
        """async () => {
          const cacheName = 'stricknani-app-update-state-v1';
          const cachePresent = await caches.has(cacheName);
          if (!cachePresent) return { cachePresent: false, entryPresent: false };
          const cache = await caches.open(cacheName);
          const url = new URL(
            '/__stricknani_app_update_activation_v1__', location.origin,
          ).href;
          return {
            cachePresent: true,
            entryPresent: Boolean(await cache.match(url, { ignoreSearch: false })),
          };
        }"""
    )
    if (
        not isinstance(value, dict)
        or not isinstance(value.get("cachePresent"), bool)
        or not isinstance(value.get("entryPresent"), bool)
    ):
        raise AcceptanceFailure("activation state snapshot is malformed")
    return value


def _request_cache_prune(page: Page) -> dict[str, object]:
    value: object = page.evaluate(
        """async () => {
          const worker = navigator.serviceWorker.controller;
          if (!worker) return { type: 'NO_CONTROLLER', pruned: false };
          return await new Promise(resolve => {
            const channel = new MessageChannel();
            const timer = setTimeout(() => {
              channel.port1.close();
              resolve({ type: 'TIMEOUT', pruned: false });
            }, 8000);
            channel.port1.onmessage = event => {
              clearTimeout(timer);
              channel.port1.close();
              resolve({
                type: event.data?.type ?? '',
                pruned: event.data?.pruned === true,
              });
            };
            worker.postMessage(
              { type: 'PRUNE_APP_CACHES' },
              [channel.port2],
            );
          });
        }"""
    )
    if not isinstance(value, dict):
        raise AcceptanceFailure("cache prune reply is malformed")
    return value


def _safe_update_state(page: Page) -> dict[str, bool]:
    value: object = page.evaluate(
        """() => {
          const shell = document.querySelector('.app-shell');
          const notice = document.querySelector('.update-notice');
          const buttons = notice ? [...notice.querySelectorAll('button')] : [];
          const visible = element => Boolean(
            element && element.getClientRects().length > 0
          );
          const copy = notice?.textContent ?? '';
          const retryButton = buttons.find(
            button => button.textContent?.trim() === 'Reload to finish',
          );
          const retryRect = retryButton?.getBoundingClientRect();
          const retryCenter = retryRect
            ? {
                x: retryRect.left + retryRect.width / 2,
                y: retryRect.top + retryRect.height / 2,
              }
            : null;
          const hit = retryCenter
            ? document.elementFromPoint(retryCenter.x, retryCenter.y)
            : null;
          let inertAncestor = false;
          for (let element = retryButton; element; element = element.parentElement) {
            if (element.inert) {
              inertAncestor = true;
              break;
            }
          }
          const hitStyle = hit ? getComputedStyle(hit) : null;
          return {
            appShellPresent: Boolean(shell),
            appShellInert: shell?.inert === true,
            noticeVisible: visible(notice),
            retryButtonVisible: visible(retryButton),
            retryButtonEnabled: visible(retryButton)
              && retryButton?.disabled === false,
            retryButtonInViewport: Boolean(
              retryRect &&
              retryRect.width > 0 &&
              retryRect.height > 0 &&
              retryRect.right > 0 &&
              retryRect.left < innerWidth &&
              retryRect.bottom > 0 &&
              retryRect.top < innerHeight
            ),
            retryButtonOwnsCenterHit: Boolean(
              retryButton && hit &&
              (retryButton === hit || retryButton.contains(hit))
            ),
            retryButtonInertAncestor: inertAncestor,
            retryButtonPointerEventsAllowed:
              getComputedStyle(retryButton ?? document.body).pointerEvents !== 'none',
            centerHitIsFixedOrSticky:
              Boolean(
                hitStyle?.position === 'fixed' ||
                hitStyle?.position === 'sticky'
              ),
            activatedCopyVisible: copy.includes(
              'Reloading this tab with the update',
            ),
            workingButtonVisible: buttons.some(button =>
              visible(button) && button.textContent?.trim() === 'Working…'
            ),
            nativeCancelCopyVisible: copy.includes(
              'The browser kept this tab open',
            ),
            dirtyReasonVisible: copy.includes('unsaved changes'),
            busyReasonVisible: copy.includes('operation is still in progress'),
            unresponsiveReasonVisible: copy.includes(
              'Another app tab did not respond',
            ),
            reloadButtonVisible: buttons.some(button =>
              visible(button) && button.textContent?.includes('Reload')
            ),
            cancelButtonVisible: buttons.some(button =>
              visible(button) && button.textContent?.includes('Cancel')
            ),
            preparingCopyVisible: copy.includes('Checking open tabs'),
            preparedTokenPresent: sessionStorage.getItem(
              'stricknani.pwa-update',
            ) !== null,
          };
        }"""
    )
    if not isinstance(value, dict):
        return {"diagnosticsAvailable": False}
    return {
        key: item
        for key, item in value.items()
        if isinstance(key, str) and isinstance(item, bool)
    }


def _install_update_history(page: Page) -> None:
    page.evaluate(
        """() => {
          const allowed = new Set([
            'preparing', 'committing', 'blocked', 'cancelled',
            'activated', 'reload-cancelled',
          ]);
          window.__pwaUpdateHistory = [];
          window.addEventListener('stricknani:pwa-update', event => {
            const state = event.detail?.state;
            if (!allowed.has(state)) return;
            const history = window.__pwaUpdateHistory;
            history.push(state);
            if (history.length > 32) history.shift();
          });
        }"""
    )


def _safe_update_history(page: Page) -> dict[str, bool]:
    value: object = page.evaluate(
        """() => {
          const history = Array.isArray(window.__pwaUpdateHistory)
            ? window.__pwaUpdateHistory
            : [];
          const preparingIndex = history.indexOf('preparing');
          return {
            preparingObserved: preparingIndex >= 0,
            cancelledAfterPreparing: preparingIndex >= 0
              && history.indexOf('cancelled', preparingIndex + 1) >= 0,
            blockedObserved: history.includes('blocked'),
            committingObserved: history.includes('committing'),
            activatedObserved: history.includes('activated'),
            reloadCancelledObserved: history.includes('reload-cancelled'),
          };
        }"""
    )
    if not isinstance(value, dict):
        return {"diagnosticsAvailable": False}
    return {
        key: item
        for key, item in value.items()
        if isinstance(key, str) and isinstance(item, bool)
    }


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
                and "async function commitUpdate" in worker_text
                and "clients.claim" not in worker_text,
                "app worker activation is not limited to the checked explicit commit",
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

            mark_stage("opening an old-version cache-retention tab")
            held_old_tab = context.new_page()
            held_old_response = held_old_tab.goto(
                f"{origin}/app/login",
                wait_until="domcontentloaded",
                timeout=15_000,
            )
            _require(
                held_old_response is not None and held_old_response.status == 200,
                "old-version cache-retention tab did not load",
            )
            held_old_tab.locator(".app-shell, .signed-out-screen").wait_for(
                state="visible",
                timeout=10_000,
            )
            _wait_for_controlled_worker(held_old_tab, expected_version)
            _install_update_history(held_old_tab)
            held_old_time_origin = held_old_tab.evaluate("() => performance.timeOrigin")

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
                second_tab.get_by_role(
                    "button", name="Reload to update", exact=True
                ).is_visible(),
                "the explicit update action is unavailable",
            )
            _require(
                waiting.get("waitingAppVersion") == candidate_app_version,
                "waiting worker reports a different compiled app version",
            )

            mark_stage("protecting a waiting candidate from old-worker pruning")
            prune_reply = _request_cache_prune(page)
            caches_after_prune = _cache_snapshot(page)
            _require(
                prune_reply.get("type") == "PRUNE_REQUEST_ACCEPTED"
                and prune_reply.get("pruned") is False
                and f"{APP_CACHE_PREFIX}{next_version}" in caches_after_prune,
                "old active worker pruned while an update candidate was waiting",
            )

            mark_stage("checking out-of-scope activation monitor")
            _require(
                monitor.url.endswith("/healthz"), "worker monitor left health scope"
            )

            mark_stage("blocking update while the project draft is dirty")
            page.get_by_role("button", name="Reload to update", exact=True).click()
            page.get_by_text(
                "An open form has unsaved changes. Save or discard them, "
                "then try again.",
                exact=True,
            ).wait_for(state="visible", timeout=5_000)
            _require(
                _version_snapshot(page).get("active") == expected_version
                and _version_snapshot(page).get("waiting") == next_version,
                "a dirty page activated the waiting worker",
            )
            _require(
                page.locator("#project-name").input_value() == draft_title,
                "blocked update changed the unsaved project draft",
            )
            _require(
                page.evaluate(
                    "() => document.querySelector('.app-shell')?.inert === false"
                ),
                "a dirty update attempt left the app locked",
            )

            mark_stage("clearing the disposable editor draft")
            project_name.fill("")
            page.locator(".dirty-note").wait_for(state="hidden", timeout=5_000)

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
            legacy_tab = context.new_page()
            legacy_tab.on("pageerror", lambda _error: page_errors.append(True))
            legacy_tab.on(
                "console",
                lambda message: (
                    csp_errors.append(True)
                    if "content security policy" in message.text.lower()
                    or "refused to" in message.text.lower()
                    else None
                ),
            )
            legacy_tab.goto(
                f"{origin}/app/login?pwa-unresponsive=1", wait_until="networkidle"
            )
            _wait_for_initial_worker(legacy_tab, expected_version)

            mark_stage("verifying prepare cleanup after an unresponsive app tab")
            _install_update_history(page)
            _install_update_history(second_tab)
            second_tab.get_by_role(
                "button", name="Reload to update", exact=True
            ).click()
            _wait_for_async_condition(
                page,
                """() => {
                  const states = window.__pwaUpdateHistory ?? [];
                  const preparing = states.indexOf('preparing');
                  return preparing >= 0
                    && states.indexOf('cancelled', preparing + 1) >= 0;
                }""",
                None,
                "prepared tab unlock after unresponsive-client abort",
                timeout_ms=10_000,
            )
            project_state = _safe_update_state(page)
            project_history = _safe_update_history(page)
            initiator_state = _safe_update_state(second_tab)
            initiator_history = _safe_update_history(second_tab)
            _require(
                project_history.get("preparingObserved") is True
                and project_history.get("cancelledAfterPreparing") is True
                and project_state.get("appShellInert") is False,
                "clean project tab did not unlock after worker rejection",
            )
            _require(
                initiator_history.get("preparingObserved") is True
                and initiator_history.get("cancelledAfterPreparing") is True
                and initiator_state.get("appShellInert") is False
                and initiator_state.get("unresponsiveReasonVisible") is True,
                "initiator did not report the unresponsive tab and unlock",
            )
            _require(
                _version_snapshot(second_tab).get("active") == expected_version
                and _version_snapshot(second_tab).get("waiting") == next_version,
                "an unresponsive client allowed the waiting worker to activate",
            )
            _require(
                second_tab.get_by_role(
                    "button", name="Reload to update", exact=True
                ).is_visible(),
                "an unresponsive-tab rejection did not leave update retry available",
            )

            mark_stage("closing the unresponsive fixture tab")
            legacy_tab.close()

            mark_stage("holding a prepared commit for explicit user cancellation")
            second_tab.evaluate(
                """() => {
                  const nativePostMessage = ServiceWorker.prototype.postMessage;
                  let held = null;
                  ServiceWorker.prototype.postMessage = function(message, transfer) {
                    if (message?.type === 'COMMIT_UPDATE' && held === null) {
                      held = { worker: this, message, transfer };
                      window.__pwaCommitHeld = true;
                      return;
                    }
                    return nativePostMessage.call(this, message, transfer);
                  };
                  window.__releasePwaCommit = () => {
                    ServiceWorker.prototype.postMessage = nativePostMessage;
                    const current = held;
                    held = null;
                    if (!current) return false;
                    nativePostMessage.call(
                      current.worker,
                      current.message,
                      current.transfer,
                    );
                    return true;
                  };
                }"""
            )
            project_time_before_cancel = page.evaluate("() => performance.timeOrigin")
            login_time_before_cancel = second_tab.evaluate(
                "() => performance.timeOrigin"
            )
            second_tab.get_by_role("button", name="Reload to update", exact=True).click(
                no_wait_after=True
            )
            _wait_for_async_condition(
                second_tab,
                "() => window.__pwaCommitHeld === true",
                None,
                "prepared update commit pause",
                timeout_ms=10_000,
            )
            _wait_for_async_condition(
                page,
                "() => document.querySelector('.app-shell')?.inert === true",
                None,
                "clean tab lock before explicit cancellation",
                timeout_ms=5_000,
            )
            second_tab.evaluate(
                """() => {
                  const NativeMessageChannel = window.MessageChannel;
                  window.__pwaCancelAcknowledged = false;
                  window.MessageChannel = class extends NativeMessageChannel {
                    constructor(...args) {
                      super(...args);
                      this.port1.addEventListener('message', event => {
                        if (event.data?.type === 'UPDATE_CANCELLED') {
                          window.__pwaCancelAcknowledged = true;
                        }
                      });
                    }
                  };
                  window.__restorePwaMessageChannel = () => {
                    window.MessageChannel = NativeMessageChannel;
                  };
                }"""
            )
            second_tab.get_by_role("button", name="Cancel update", exact=True).click(
                no_wait_after=True
            )
            _wait_for_async_condition(
                page,
                "() => document.querySelector('.app-shell')?.inert === false",
                None,
                "clean tab unlock after explicit cancellation",
                timeout_ms=8_000,
            )
            _wait_for_async_condition(
                second_tab,
                "() => document.querySelector('.app-shell')?.inert === false",
                None,
                "initiating tab unlock after explicit cancellation",
                timeout_ms=8_000,
            )
            _wait_for_async_condition(
                second_tab,
                "() => window.__pwaCancelAcknowledged === true",
                None,
                "waiting for worker cancellation acknowledgement",
                timeout_ms=8_000,
            )
            second_tab.evaluate("() => window.__restorePwaMessageChannel()")
            _require(
                second_tab.evaluate("() => window.__releasePwaCommit()") is True,
                "paused commit was not released after explicit cancellation",
            )
            second_tab.get_by_role(
                "button", name="Reload to update", exact=True
            ).wait_for(state="visible", timeout=5_000)
            _require(
                page.evaluate("() => performance.timeOrigin")
                == project_time_before_cancel
                and second_tab.evaluate("() => performance.timeOrigin")
                == login_time_before_cancel,
                "explicit cancellation reloaded a prepared tab",
            )
            _require(
                page.evaluate(
                    "() => sessionStorage.getItem('stricknani.pwa-update') === null"
                )
                and second_tab.evaluate(
                    "() => sessionStorage.getItem('stricknani.pwa-update') === null"
                ),
                "explicit cancellation retained a prepared update token",
            )
            cancelled_snapshot = _version_snapshot(second_tab)
            cancelled_caches = _cache_snapshot(second_tab)
            cancelled_activation_state = _activation_state_snapshot(second_tab)
            cancelled_state_diagnostics = {
                "activeVersionMatches": (
                    cancelled_snapshot.get("active") == expected_version
                ),
                "waitingVersionMatches": (
                    cancelled_snapshot.get("waiting") == next_version
                ),
                "cacheCount": len(cancelled_caches),
                "oldCachePresent": (
                    f"{APP_CACHE_PREFIX}{expected_version}" in cancelled_caches
                ),
                "candidateCachePresent": (
                    f"{APP_CACHE_PREFIX}{next_version}" in cancelled_caches
                ),
                "activationStateCachePresent": cancelled_activation_state[
                    "cachePresent"
                ],
                "activationRecordPresent": cancelled_activation_state["entryPresent"],
            }
            _require(
                cancelled_state_diagnostics["activeVersionMatches"] is True
                and cancelled_state_diagnostics["waitingVersionMatches"] is True
                and cancelled_state_diagnostics["cacheCount"] == 2
                and cancelled_state_diagnostics["oldCachePresent"] is True
                and cancelled_state_diagnostics["candidateCachePresent"] is True
                and cancelled_state_diagnostics["activationRecordPresent"] is False,
                "explicit cancellation state failed "
                f"(safe state: {cancelled_state_diagnostics})",
            )

            mark_stage("restarting a prepared waiting worker before commit")
            second_tab.evaluate(
                """() => {
                  const nativePostMessage = ServiceWorker.prototype.postMessage;
                  let held = null;
                  ServiceWorker.prototype.postMessage = function(message, transfer) {
                    if (message?.type === 'COMMIT_UPDATE' && held === null) {
                      held = { worker: this, message, transfer };
                      window.__pwaCommitHeld = true;
                      return;
                    }
                    return nativePostMessage.call(this, message, transfer);
                  };
                  window.__releasePwaCommit = () => {
                    ServiceWorker.prototype.postMessage = nativePostMessage;
                    const current = held;
                    held = null;
                    if (!current) return false;
                    nativePostMessage.call(
                      current.worker,
                      current.message,
                      current.transfer,
                    );
                    return true;
                  };
                }"""
            )
            second_tab.get_by_role(
                "button", name="Reload to update", exact=True
            ).click()
            _wait_for_async_condition(
                second_tab,
                "() => window.__pwaCommitHeld === true",
                None,
                "explicit commit pause after all clients prepare",
                timeout_ms=10_000,
            )
            _wait_for_async_condition(
                page,
                "() => document.querySelector('.app-shell')?.inert === true",
                None,
                "other prepared tab lock before worker restart",
                timeout_ms=5_000,
            )
            waiting_versions = [
                item
                for item in service_worker_versions
                if item["status"] == "installed"
            ]
            _require(
                bool(waiting_versions),
                "browser did not expose the installed waiting worker "
                "for restart testing",
            )
            cdp.send(
                "ServiceWorker.stopWorker",
                {"versionId": waiting_versions[-1]["version_id"]},
            )
            _require(
                second_tab.evaluate("() => window.__releasePwaCommit()") is True,
                "paused explicit commit was not released after worker restart",
            )
            second_tab.get_by_text(
                "The update timed out. You can safely try again.",
                exact=True,
            ).wait_for(state="visible", timeout=10_000)
            _wait_for_async_condition(
                page,
                "() => document.querySelector('.app-shell')?.inert === false",
                None,
                "prepared tab unlock after waiting worker restart",
                timeout_ms=8_000,
            )
            _require(
                _version_snapshot(second_tab).get("active") == expected_version
                and _version_snapshot(second_tab).get("waiting") == next_version,
                "restarted worker accepted a commit without its prepare state",
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

            mark_stage("checking the served candidate worker version")
            candidate_worker = second_tab.request.get(f"{origin}/app/sw.js")
            _require(
                candidate_worker.status == 200
                and next_version in candidate_worker.text(),
                "served update worker has the wrong version",
            )
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
                "active worker changed before explicit update approval",
            )
            _require(
                waiting.get("waiting") == next_version,
                "new worker did not wait for explicit update approval "
                f"(active: {waiting.get('active')}, "
                f"waiting: {waiting.get('waiting')}, expected: {next_version})",
            )
            _require(
                waiting.get("activeAppVersion") == expected_app_version
                and waiting.get("waitingAppVersion") == candidate_app_version,
                "candidate worker compiled-app version did not change independently",
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
                second_tab.get_by_role(
                    "button", name="Reload to update", exact=True
                ).is_visible(),
                "the explicit update action is unavailable",
            )

            page_time_origin = page.evaluate("() => performance.timeOrigin")
            second_time_origin = second_tab.evaluate("() => performance.timeOrigin")
            asset_response_start = len(service_worker_asset_responses)
            reload_cancel_dialogs: list[str] = []

            def cancel_one_prepared_reload(dialog: Dialog) -> None:
                reload_cancel_dialogs.append(dialog.type)
                dialog.dismiss()

            page.evaluate(
                """() => {
                  const cancelReload = event => {
                    event.preventDefault();
                    event.returnValue = '';
                  };
                  window.addEventListener('beforeunload', cancelReload, {
                    once: true,
                  });
                }"""
            )
            held_old_tab.evaluate(
                """() => {
                  const cancelReload = event => {
                    event.preventDefault();
                    event.returnValue = '';
                  };
                  window.addEventListener('beforeunload', cancelReload, {
                    once: true,
                  });
                }"""
            )
            page.once("dialog", cancel_one_prepared_reload)

            held_old_reload_dialogs: list[str] = []

            def cancel_held_old_reload(dialog: Dialog) -> None:
                held_old_reload_dialogs.append(dialog.type)
                dialog.dismiss()

            held_old_tab.once("dialog", cancel_held_old_reload)
            mark_stage("retrying commit after prepared native reload cancellation")
            second_tab.get_by_role("button", name="Reload to update", exact=True).click(
                no_wait_after=True, timeout=5_000
            )
            try:
                _wait_for_async_condition(
                    held_old_tab,
                    "() => window.__pwaUpdateHistory?.includes('preparing') === true",
                    None,
                    "cache-retention tab preparation acknowledgement",
                    timeout_ms=5_000,
                )
            except AcceptanceFailure:
                held_state = _safe_update_state(held_old_tab)
                held_history = _safe_update_history(held_old_tab)
                raise AcceptanceFailure(
                    "cache-retention tab did not prepare "
                    f"(app shell present: {held_state.get('appShellPresent')}, "
                    f"inert: {held_state.get('appShellInert')}, "
                    f"notice visible: {held_state.get('noticeVisible')}, "
                    f"preparing observed: {held_history.get('preparingObserved')}, "
                    f"blocked observed: {held_history.get('blockedObserved')})"
                ) from None
            mark_stage("waiting for the prepared login tab to reload")
            try:
                _wait_for_async_condition(
                    second_tab,
                    "expected => performance.timeOrigin > expected",
                    second_time_origin,
                    "prepared login tab reload",
                    timeout_ms=20_000,
                )
            except Exception:
                page_closed = True
                try:
                    page_closed = second_tab.is_closed()
                    retry_versions = (
                        _version_snapshot(second_tab) if not page_closed else {}
                    )
                    retry_state = (
                        _safe_update_state(second_tab) if not page_closed else {}
                    )
                    same_document = (
                        not page_closed
                        and second_tab.evaluate("() => performance.timeOrigin")
                        == second_time_origin
                    )
                except Exception:
                    retry_versions = {}
                    retry_state = {}
                    same_document = False
                raise AcceptanceFailure(
                    "prepared login reload did not complete "
                    f"(page open: {not page_closed}, "
                    f"same document: {same_document}, "
                    f"active candidate: "
                    f"{retry_versions.get('active') == next_version}, "
                    f"waiting candidate: "
                    f"{retry_versions.get('waiting') == next_version}, "
                    f"reload action visible: "
                    f"{retry_state.get('reloadButtonVisible')}, "
                    f"cancel action visible: "
                    f"{retry_state.get('cancelButtonVisible')})"
                ) from None
            mark_stage("checking the native reload-cancel notice")
            try:
                page.get_by_text(
                    "The browser kept this tab open. Reload when it is safe.",
                    exact=True,
                ).wait_for(state="visible", timeout=10_000)
            except Exception:
                page_state = _safe_update_state(page)
                page_closed = True
                try:
                    page_closed = page.is_closed()
                    same_page_document = (
                        not page_closed
                        and page.evaluate("() => performance.timeOrigin")
                        == page_time_origin
                    )
                    page_path = urlsplit(page.url).path if not page_closed else ""
                    token_present = not page_closed and page.evaluate(
                        "() => sessionStorage.getItem('stricknani.pwa-update') !== null"
                    )
                except Exception:
                    same_page_document = False
                    page_path = ""
                    token_present = False
                raise AcceptanceFailure(
                    "native reload-cancel state failed "
                    f"(dialog observed: {reload_cancel_dialogs == ['beforeunload']}, "
                    f"same document: {same_page_document}, "
                    f"editor route: {page_path == '/app/projects/new'}, "
                    f"notice visible: {page_state.get('noticeVisible')}, "
                    f"retry action visible: {page_state.get('reloadButtonVisible')}, "
                    f"prepared token present: {token_present})"
                ) from None
            mark_stage("verifying the cancelled native reload state")
            state_check_succeeded = False
            same_page_document = False
            prepared_token_present = False
            page_closed = True
            try:
                page_closed = page.is_closed()
                if not page_closed:
                    same_page_document = (
                        page.evaluate("() => performance.timeOrigin")
                        == page_time_origin
                    )
                    prepared_token_present = page.evaluate(
                        "() => sessionStorage.getItem('stricknani.pwa-update') !== null"
                    )
                    state_check_succeeded = True
            except Exception:
                pass
            _require(
                state_check_succeeded
                and reload_cancel_dialogs == ["beforeunload"]
                and same_page_document
                and prepared_token_present,
                "cancelled native reload state failed "
                f"(state query succeeded: {state_check_succeeded}, "
                f"dialog observed: {reload_cancel_dialogs == ['beforeunload']}, "
                f"same document: {same_page_document}, "
                f"prepared token: {prepared_token_present})",
            )
            mark_stage("checking the held old-version tab")
            held_old_tab.get_by_text(
                "The browser kept this tab open. Reload when it is safe.",
                exact=True,
            ).wait_for(state="visible", timeout=10_000)
            _require(
                held_old_reload_dialogs == ["beforeunload"]
                and held_old_tab.evaluate("() => performance.timeOrigin")
                == held_old_time_origin,
                "cache-retention tab did not remain on its original document",
            )
            mark_stage("verifying retained old and candidate app caches")
            retained_caches = _cache_snapshot(page)
            retained_activation_state = _activation_state_snapshot(page)
            retained_state_diagnostics = {
                "cacheCount": len(retained_caches),
                "oldCachePresent": (
                    f"{APP_CACHE_PREFIX}{expected_version}" in retained_caches
                ),
                "candidateCachePresent": (
                    f"{APP_CACHE_PREFIX}{next_version}" in retained_caches
                ),
                "activationStateCachePresent": retained_activation_state[
                    "cachePresent"
                ],
                "activationRecordPresent": retained_activation_state["entryPresent"],
                "originalPageStillOpen": not page.is_closed(),
                "sameDocumentBeforeReload": page.evaluate(
                    "() => performance.timeOrigin"
                )
                == page_time_origin,
                "preparedTokenPresent": page.evaluate(
                    "() => sessionStorage.getItem('stricknani.pwa-update') !== null"
                ),
            }
            _require(
                len(retained_caches) == 2
                and f"{APP_CACHE_PREFIX}{expected_version}" in retained_caches
                and f"{APP_CACHE_PREFIX}{next_version}" in retained_caches
                and retained_activation_state["entryPresent"],
                "old app cache or activation record was removed "
                "before the old page reloaded "
                f"(safe state: {retained_state_diagnostics})",
            )
            mark_stage("retrying the prepared reload before cache diagnostics")
            mark_stage("reading retry button state")
            retry_button_state = _safe_update_state(page)
            _require(
                retry_button_state.get("retryButtonVisible") is True
                and retry_button_state.get("retryButtonEnabled") is True,
                "prepared reload retry button is unavailable "
                f"(safe state: {retry_button_state})",
            )
            retry_locator = page.get_by_role(
                "button",
                name="Reload to finish",
                exact=True,
            )
            retry_dialogs: list[str] = []

            def dismiss_retry_dialog(dialog: Dialog) -> None:
                retry_dialogs.append(dialog.type)
                dialog.dismiss()

            page.on("dialog", dismiss_retry_dialog)
            mark_stage("installing retry click observer")
            page.evaluate(
                """() => {
                  window.__pwaRetryClickObserved = false;
                  const observe = event => {
                    const target = event.target;
                    if (
                      target instanceof Element &&
                      target.closest('.update-notice button.primary-action')
                    ) {
                      window.__pwaRetryClickObserved = true;
                    }
                  };
                  document.addEventListener('click', observe, true);
                  window.__removePwaRetryObserver = () => {
                    document.removeEventListener('click', observe, true);
                  };
                }"""
            )
            mark_stage("counting semantic retry button")
            retry_role_count = retry_locator.count()
            mark_stage("checking semantic retry button visibility")
            retry_role_visible = retry_locator.is_visible()
            mark_stage("checking semantic retry button enabled state")
            retry_role_enabled = retry_locator.is_enabled()
            try:
                mark_stage("clicking the retry button")
                retry_locator.click(no_wait_after=True, timeout=5_000)
            except PlaywrightError:
                retry_failure_state = _safe_update_state(page)
                retry_click_observed = page.evaluate(
                    "() => window.__pwaRetryClickObserved === true"
                )
                page.screenshot(
                    path=str(screenshots / "prepared-retry-click-failure.png"),
                    caret="hide",
                )
                page.evaluate("() => window.__removePwaRetryObserver?.()")
                page.remove_listener("dialog", dismiss_retry_dialog)
                raise AcceptanceFailure(
                    "prepared reload click did not complete "
                    f"(role match count one: {retry_role_count == 1}, "
                    f"role visible: {retry_role_visible}, "
                    f"role enabled: {retry_role_enabled}, "
                    f"click event observed: {retry_click_observed}, "
                    f"dialog seen: {bool(retry_dialogs)}, "
                    f"safe state: {retry_failure_state})"
                ) from None
            finally:
                page.remove_listener("dialog", dismiss_retry_dialog)
                if not page.is_closed():
                    page.evaluate("() => window.__removePwaRetryObserver?.()")
            mark_stage("waiting for retry navigation")
            try:
                _wait_for_async_condition(
                    page,
                    "expected => performance.timeOrigin > expected",
                    page_time_origin,
                    "prepared project tab retry reload",
                    timeout_ms=20_000,
                )
            except (AcceptanceFailure, PlaywrightError):
                post_click_state: dict[str, bool] = {}
                post_click_history: dict[str, bool] = {}
                post_click_versions: dict[str, object] = {}
                same_document_after_retry = False
                click_observed_after_retry = False
                if not page.is_closed():
                    try:
                        post_click_state = _safe_update_state(page)
                        post_click_history = _safe_update_history(page)
                        post_click_versions = _version_snapshot(page)
                        same_document_after_retry = (
                            page.evaluate("() => performance.timeOrigin")
                            == page_time_origin
                        )
                        click_observed_after_retry = page.evaluate(
                            "() => window.__pwaRetryClickObserved === true"
                        )
                        page.screenshot(
                            path=str(screenshots / "prepared-retry-no-reload.png"),
                            caret="hide",
                        )
                    except PlaywrightError:
                        pass
                raise AcceptanceFailure(
                    "prepared reload click did not navigate "
                    f"(click event observed: {click_observed_after_retry}, "
                    f"same document: {same_document_after_retry}, "
                    f"prepared token: "
                    f"{post_click_state.get('preparedTokenPresent')}, "
                    f"retry visible: "
                    f"{post_click_state.get('retryButtonVisible')}, "
                    f"retry enabled: "
                    f"{post_click_state.get('retryButtonEnabled')}, "
                    f"working visible: "
                    f"{post_click_state.get('workingButtonVisible')}, "
                    f"history: {post_click_history}, "
                    f"active candidate: "
                    f"{post_click_versions.get('active') == next_version}, "
                    f"waiting candidate: "
                    f"{post_click_versions.get('waiting') == next_version})"
                ) from None
            mark_stage("waiting for the reloaded editor")
            _wait_for_async_condition(
                page,
                "() => Boolean(document.querySelector('#project-name')"
                " || document.querySelector('#email'))",
                None,
                "post-update editor or sign-in form",
                timeout_ms=15_000,
            )
            post_reload_state = page.evaluate(
                """() => ({
                  path: location.pathname,
                  editor: Boolean(document.querySelector('#project-name')),
                  signIn: Boolean(document.querySelector('#email')),
                  appShell: Boolean(document.querySelector('.app-shell')),
                })"""
            )
            if not post_reload_state.get("editor"):
                _require(
                    post_reload_state.get("signIn") is True,
                    "updated page showed neither the editor nor the sign-in form",
                )
                current_fixture = rust_fixture.load_fixture_environment(fixture_file)
                mark_stage("signing in after the fixture runtime was replaced")
                page.locator("#email").fill(
                    current_fixture["STRICKNANI_RUST_TEST_EMAIL"]
                )
                page.locator("#password").fill(
                    current_fixture["STRICKNANI_RUST_TEST_PASSWORD"]
                )
                page.get_by_role("button", name="Sign in", exact=True).click()
                page.locator(".app-shell").wait_for(state="visible", timeout=10_000)
                mark_stage("reopening the editor after fixture sign-in")
                page.goto(f"{origin}/app/projects/new", wait_until="networkidle")
            page.locator("#project-name").wait_for(state="visible", timeout=15_000)
            _wait_for_controlled_worker(page, next_version)

            mark_stage("confirming retained cache while the old tab remains open")
            retained_after_retry = _cache_snapshot(page)
            _require(
                f"{APP_CACHE_PREFIX}{expected_version}" in retained_after_retry
                and f"{APP_CACHE_PREFIX}{next_version}" in retained_after_retry,
                "retrying one prepared page removed the cache still used by an old tab",
            )
            mark_stage("preparing an active-worker network-failure fallback tab")
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
                network_failure_tab.close()
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
            _require(
                page.evaluate("() => performance.timeOrigin") > page_time_origin,
                "prepared project tab did not retry before cache diagnostics",
            )
            mark_stage("checking the reauthenticated editor after offline fallback")
            try:
                page.locator("#project-name").wait_for(state="visible", timeout=15_000)
            except PlaywrightError:
                editor_state = page.evaluate(
                    """() => ({
                      path: location.pathname,
                      language: document.documentElement.lang,
                      appShell: Boolean(document.querySelector('.app-shell')),
                      editor: Boolean(document.querySelector('#project-name')),
                      signIn: Boolean(document.querySelector('#email')),
                      offline: Boolean(document.querySelector('.offline-page')),
                      controlled: navigator.serviceWorker.controller !== null,
                    })"""
                )
                page.screenshot(
                    path=str(screenshots / "editor-after-fallback-timeout.png"),
                    caret="hide",
                )
                raise AcceptanceFailure(
                    f"reopened editor did not appear (safe page state: {editor_state})"
                ) from None
            mark_stage("checking the secondary tab after offline fallback")
            try:
                second_tab.locator(".app-shell, .signed-out-screen").wait_for(
                    state="visible", timeout=15_000
                )
            except PlaywrightError:
                second_tab_state = second_tab.evaluate(
                    """() => ({
                      path: location.pathname,
                      language: document.documentElement.lang,
                      appShell: Boolean(document.querySelector('.app-shell')),
                      signedOut: Boolean(document.querySelector('.signed-out-screen')),
                      signIn: Boolean(document.querySelector('#email')),
                      controlled: navigator.serviceWorker.controller !== null,
                    })"""
                )
                second_tab.screenshot(
                    path=str(screenshots / "secondary-tab-after-fallback-timeout.png"),
                    caret="hide",
                )
                raise AcceptanceFailure(
                    "secondary tab app shell did not appear "
                    f"(safe page state: {second_tab_state})"
                ) from None
            _wait_for_controlled_worker(page, next_version)
            _wait_for_controlled_worker(second_tab, next_version)

            mark_stage(
                "verifying compiled page acknowledgements and safe cache cleanup"
            )
            for prepared_page in (page, second_tab):
                runtime_version = prepared_page.evaluate(
                    """async () => {
                      const response = await fetch('/app/_app/version.json', {
                        cache: 'no-store',
                      });
                      if (!response.ok) return '';
                      const data = await response.json();
                      return typeof data.version === 'string' ? data.version : '';
                    }"""
                )
                _require(
                    runtime_version == candidate_app_version,
                    "reloaded page does not use the candidate compiled app version",
                )
                snapshot = _version_snapshot(prepared_page)
                _require(
                    snapshot.get("active") == next_version
                    and snapshot.get("activeAppVersion") == candidate_app_version
                    and snapshot.get("waiting") == "",
                    "reloaded page is controlled by a mismatched app worker",
                )
                _wait_for_async_condition(
                    prepared_page,
                    "() => sessionStorage.getItem('stricknani.pwa-update') === null",
                    None,
                    "page-version reload acknowledgement",
                    timeout_ms=10_000,
                )
            mark_stage("releasing the retained old-version cache client")
            held_old_tab.close()
            prune_after_old_client = _request_cache_prune(page)
            _require(
                prune_after_old_client.get("type") == "PRUNE_REQUEST_ACCEPTED"
                and prune_after_old_client.get("pruned") is True,
                "candidate worker did not prune after the final old-version tab closed",
            )
            _wait_for_async_condition(
                page,
                "() => caches.keys().then(keys => "
                "!keys.includes('stricknani-app-update-state-v1'))",
                None,
                "activation record cleanup after every live page acknowledges",
                timeout_ms=15_000,
            )
            activated_snapshot = _version_snapshot(page)
            final_caches = _cache_snapshot(page)
            _require(
                activated_snapshot.get("active") == next_version
                and activated_snapshot.get("activeState") == "activated"
                and activated_snapshot.get("waiting") == "",
                "candidate worker activated with an unexpected version",
            )
            _require(
                len(final_caches) == 1 and next_version in next(iter(final_caches)),
                "activation did not leave exactly the new bounded app cache "
                f"(caches: {final_caches}, expected: {next_version})",
            )
            _require(
                activation_worker_errors == [],
                "browser reported an app worker activation error "
                f"(sanitized class/line: {activation_worker_errors[-8:]})",
            )
            final_paths = next(iter(final_caches.values()))
            _require(
                REQUIRED_OFFLINE_ASSETS.issubset(final_paths)
                and set(final_paths).issubset(candidate_assets),
                "updated app cache omitted core assets or contained an "
                "unallowlisted path",
            )
            _require(
                cached_immutable_asset in final_paths,
                "the retained immutable asset was not cached by the new worker",
            )
            for prepared_page in (page, second_tab):
                _wait_for_async_condition(
                    prepared_page,
                    "() => caches.keys().then(keys => "
                    "!keys.includes('stricknani-app-update-state-v1'))",
                    None,
                    "prepared page observing activation-state cleanup",
                    timeout_ms=10_000,
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
