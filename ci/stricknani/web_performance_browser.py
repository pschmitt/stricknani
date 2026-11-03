#!/usr/bin/env python3
"""Measure controlled synthetic mobile performance on the Rust web fixture."""

from __future__ import annotations

import argparse
import gzip
import http.client
import json
import math
import statistics
import sys
import tempfile
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any
from urllib.parse import parse_qs, urlsplit

from ci.stricknani import rust_fixture, seed_rust
from playwright.sync_api import (
    Browser,
    BrowserContext,
    Page,
    Response,
    StorageState,
    sync_playwright,
)
from playwright.sync_api import Error as PlaywrightError

SAMPLE_COUNT = 5
JS_BUDGET = 150 * 1024
CSS_BUDGET = 50 * 1024
EVENT_DURATION_THRESHOLD_MS = 16
LAB_INP_TARGET_MS = 200
MIN_OBSERVED_INTERACTION_COVERAGE = 0.5
NETWORK = {
    "latency": 150,
    "downloadThroughput": int(1_600_000 / 8),
    "uploadThroughput": int(750_000 / 8),
    "offline": False,
    "connectionType": "cellular3g",
}
_CURRENT_STAGE = "initialization"
GZIP_TYPES = (
    "application/javascript",
    "application/json",
    "application/manifest+json",
    "application/x-javascript",
    "image/svg+xml",
    "text/css",
    "text/html",
    "text/plain",
    "text/javascript",
)


def _gzip_response(
    accept_encoding: str, content_type: str, body: bytes
) -> bytes | None:
    """Return gzip bytes when this synthetic proxy should compress a response."""
    accepts_gzip = False
    for token in accept_encoding.split(","):
        encoding, *parameters = token.strip().split(";")
        if encoding.lower() != "gzip":
            continue
        quality = next(
            (
                parameter.partition("=")[2].strip()
                for parameter in parameters
                if parameter.partition("=")[0].strip().lower() == "q"
            ),
            "1",
        )
        try:
            accepts_gzip = float(quality) > 0
        except ValueError:
            accepts_gzip = False
        break
    media_type = content_type.split(";", maxsplit=1)[0].strip().lower()
    if not accepts_gzip or media_type not in GZIP_TYPES or len(body) < 1024:
        return None
    return gzip.compress(body, compresslevel=6, mtime=0)


class _LoopbackGzipProxy:
    """Local reverse proxy that models gzip transport without external tools."""

    def __init__(self, backend_origin: str) -> None:
        parsed = urlsplit(backend_origin)
        if parsed.scheme != "http" or not parsed.hostname or not parsed.port:
            raise PerformanceFailure("fixture origin cannot be proxied safely")
        backend_host = parsed.hostname
        backend_port = parsed.port

        class Handler(BaseHTTPRequestHandler):
            protocol_version = "HTTP/1.1"

            def log_message(self, _format: str, *_args: object) -> None:
                return

            def do_GET(self) -> None:
                self._proxy()

            def do_HEAD(self) -> None:
                self._proxy()

            def do_POST(self) -> None:
                self._proxy()

            def do_PUT(self) -> None:
                self._proxy()

            def do_PATCH(self) -> None:
                self._proxy()

            def do_DELETE(self) -> None:
                self._proxy()

            def _proxy(self) -> None:
                try:
                    body_length = int(self.headers.get("content-length", "0"))
                    if body_length < 0 or body_length > 10 * 1024 * 1024:
                        self.send_error(413)
                        return
                    request_body = self.rfile.read(body_length) if body_length else None
                    hop_headers = {
                        "connection",
                        "keep-alive",
                        "proxy-authenticate",
                        "proxy-authorization",
                        "te",
                        "trailers",
                        "transfer-encoding",
                        "upgrade",
                    }
                    request_headers = {
                        name: value
                        for name, value in self.headers.items()
                        if name.lower() not in hop_headers
                        and name.lower() != "accept-encoding"
                    }
                    # Keep the browser-facing Host, Origin, Cookie and CSRF headers
                    # intact while asking the backend for identity-encoded bytes.
                    request_headers["Accept-Encoding"] = "identity"
                    connection = http.client.HTTPConnection(
                        backend_host, backend_port, timeout=20
                    )
                    connection.request(
                        self.command,
                        self.path,
                        body=request_body,
                        headers=request_headers,
                    )
                    upstream = connection.getresponse()
                    response_body = upstream.read()
                    response_headers = upstream.getheaders()
                    content_type = next(
                        (
                            value
                            for name, value in response_headers
                            if name.lower() == "content-type"
                        ),
                        "",
                    )
                    compressed = _gzip_response(
                        self.headers.get("accept-encoding", ""),
                        content_type,
                        response_body,
                    )
                    if compressed is not None:
                        response_body = compressed
                    self.send_response(upstream.status, upstream.reason)
                    hop_headers.add("content-length")
                    if compressed is not None:
                        hop_headers.update({"content-encoding", "vary"})
                    for name, value in response_headers:
                        if name.lower() not in hop_headers:
                            self.send_header(name, value)
                    if compressed is not None:
                        self.send_header("Content-Encoding", "gzip")
                        vary = next(
                            (
                                value
                                for name, value in response_headers
                                if name.lower() == "vary"
                            ),
                            "",
                        )
                        vary_tokens = {item.strip().lower() for item in vary.split(",")}
                        if "accept-encoding" not in vary_tokens:
                            vary = (
                                f"{vary}, Accept-Encoding"
                                if vary
                                else "Accept-Encoding"
                            )
                        self.send_header("Vary", vary)
                    self.send_header("Content-Length", str(len(response_body)))
                    self.send_header("Connection", "close")
                    self.end_headers()
                    if self.command != "HEAD":
                        self.wfile.write(response_body)
                    connection.close()
                except (http.client.HTTPException, OSError, ValueError):
                    self.close_connection = True

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.server.daemon_threads = True
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.origin = f"http://127.0.0.1:{self.server.server_port}"

    def close(self) -> None:
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=5)


class PerformanceFailure(RuntimeError):
    """A safe failure with no page content or fixture credentials."""


def _private_work_dir(path: Path | None) -> Path:
    target = (
        Path(tempfile.mkdtemp(prefix="stricknani-performance-"))
        if path is None
        else rust_fixture._outside_repository(path, "private work directory")
    )
    if path is not None:
        target.mkdir(mode=0o700, parents=True, exist_ok=False)
    target = rust_fixture._outside_repository(target, "private work directory")
    target.chmod(0o700)
    return target


def _route_ready(page: Page, origin: str) -> float:
    start = time.perf_counter()
    page.goto(f"{origin}/app/projects", wait_until="domcontentloaded")
    page.get_by_role("heading", name="Projects", exact=True).wait_for(
        state="visible", timeout=20_000
    )
    page.get_by_role("heading", name="Heirloom Baby Blanket", exact=True).wait_for(
        state="visible", timeout=20_000
    )
    return (time.perf_counter() - start) * 1000


def _settle(page: Page) -> None:
    page.evaluate(
        """async () => {
          await new Promise(resolve => requestAnimationFrame(() =>
            requestAnimationFrame(resolve)));
          return undefined;
        }"""
    )


def _open_reader_actions(page: Page) -> None:
    disclosure = page.locator("details.action-overflow")
    disclosure.wait_for(state="attached", timeout=10_000)
    if disclosure.count() != 1:
        raise PerformanceFailure("reader actions disclosure was ambiguous")
    summary = disclosure.locator("summary")
    if summary.get_attribute("aria-label") != "More actions":
        raise PerformanceFailure("reader actions disclosure label changed")
    if disclosure.get_attribute("open") is None:
        summary.click()
    page.wait_for_function(
        "() => document.querySelector('details.action-overflow')?.open === true",
        timeout=5_000,
    )


def _observer_metrics(page: Page) -> dict[str, Any]:
    value: object = page.evaluate(
        """() => {
          const state = window.__stricknaniPerformance;
          if (!state) return null;
          const record = (entry, source) => {
            if (source === 'first-input') {
              state.firstInputEntryCount += 1;
              return;
            }
            const key = [entry.interactionId, entry.name, entry.startTime,
              entry.duration].join(':');
            if (state.seenEventEntries.has(key)) return;
            state.seenEventEntries.add(key);
            state.eventEntryCount += 1;
            if (!entry.interactionId) return;
            state.minInteractionId = Math.min(
              state.minInteractionId,
              entry.interactionId,
            );
            state.maxInteractionId = Math.max(
              state.maxInteractionId,
              entry.interactionId,
            );
            state.events.push({
              interaction_id: entry.interactionId,
              duration_ms: entry.duration,
              event_type: entry.name,
              source,
            });
          };
          for (const observerRecord of state.eventObservers) {
            for (const entry of observerRecord.observer.takeRecords()) {
              record(entry, observerRecord.source);
            }
          }
          const hasNativeInteractionCount =
            typeof performance.interactionCount === 'number';
          const interactionCount = hasNativeInteractionCount
            ? performance.interactionCount - state.initialInteractionCount
            : state.maxInteractionId >= state.minInteractionId
              ? (state.maxInteractionId - state.minInteractionId) / 7 + 1
              : 0;
          return {
            lcp: state.lcp.length ? state.lcp[state.lcp.length - 1] : null,
            cls: state.shifts.reduce((sum, value) => sum + value, 0),
            time_origin_ms: performance.timeOrigin,
            event_observer_supported: state.eventObserverSupported,
            first_input_supported: state.firstInputSupported,
            event_entry_count: state.eventEntryCount,
            first_input_entry_count: state.firstInputEntryCount,
            interaction_count: interactionCount,
            interaction_count_source: hasNativeInteractionCount
              ? 'native'
              : 'web-vitals-polyfill-estimate',
            events: state.events,
            resources: performance.getEntriesByType('resource').map(entry => {
              const path = new URL(entry.name).pathname;
              const type = path.endsWith('.js') ? 'js'
                : path.endsWith('.css') ? 'css'
                : path === '/app/messages.json' ? 'catalog' : null;
              return type ? {
                type,
                encoded: entry.encodedBodySize,
                transfer: entry.transferSize,
              } : null;
            }).filter(Boolean),
          };
        }"""
    )
    if not isinstance(value, dict):
        raise PerformanceFailure("browser performance observers were unavailable")
    return value


def _interaction_groups(entries: object) -> list[dict[str, Any]]:
    if not isinstance(entries, list):
        raise PerformanceFailure("browser Event Timing entries were unavailable")
    groups: dict[int, dict[str, Any]] = {}
    for entry in entries:
        if not isinstance(entry, dict):
            raise PerformanceFailure("browser Event Timing entry was invalid")
        interaction_id = entry.get("interaction_id")
        duration = entry.get("duration_ms")
        event_type = entry.get("event_type")
        if (
            not isinstance(interaction_id, int)
            or isinstance(interaction_id, bool)
            or interaction_id <= 0
            or not isinstance(duration, (int, float))
            or isinstance(duration, bool)
            or not math.isfinite(duration)
            or duration < EVENT_DURATION_THRESHOLD_MS
            or not isinstance(event_type, str)
        ):
            raise PerformanceFailure("browser Event Timing entry was invalid")
        group = groups.setdefault(
            interaction_id,
            {
                "interaction_id": interaction_id,
                "duration_ms": duration,
                "event_count": 0,
                "event_types": set(),
            },
        )
        group["duration_ms"] = max(group["duration_ms"], duration)
        group["event_count"] += 1
        group["event_types"].add(event_type)
    return list(groups.values())


def _estimate_lab_inp(entries: object, interaction_count: object) -> dict[str, Any]:
    groups = _interaction_groups(entries)
    if not isinstance(interaction_count, (int, float)) or isinstance(
        interaction_count, bool
    ):
        raise PerformanceFailure("document interaction count was unavailable")
    if not math.isfinite(interaction_count) or interaction_count <= 0:
        raise PerformanceFailure("document did not record trusted interactions")
    if not groups:
        raise PerformanceFailure("Event Timing contained no measured interactions")
    if interaction_count < len(groups):
        raise PerformanceFailure(
            "Event Timing groups exceeded document interaction count"
        )

    groups.sort(key=lambda group: group["duration_ms"], reverse=True)
    candidates = groups[:10]
    candidate_index = min(len(candidates) - 1, math.floor(interaction_count / 50))
    candidate_ms = candidates[candidate_index]["duration_ms"]
    coverage = len(groups) / interaction_count
    duration_buckets = {
        "16_to_39_ms": 0,
        "40_to_99_ms": 0,
        "100_to_199_ms": 0,
        "200_ms_or_more": 0,
    }
    event_type_counts: dict[str, int] = {}
    for group in groups:
        duration = group["duration_ms"]
        if duration < 40:
            duration_buckets["16_to_39_ms"] += 1
        elif duration < 100:
            duration_buckets["40_to_99_ms"] += 1
        elif duration < LAB_INP_TARGET_MS:
            duration_buckets["100_to_199_ms"] += 1
        else:
            duration_buckets["200_ms_or_more"] += 1
        for event_type in group["event_types"]:
            event_type_counts[event_type] = event_type_counts.get(event_type, 0) + 1

    return {
        "lab_inp_candidate_ms": round(float(candidate_ms), 1),
        "lab_inp_candidate_index": candidate_index,
        "lab_inp_target_ms": LAB_INP_TARGET_MS,
        "lab_inp_target_pass": candidate_ms <= LAB_INP_TARGET_MS,
        "document_interaction_count": interaction_count,
        "observed_interaction_count": len(groups),
        "observed_interaction_coverage": round(coverage, 3),
        "lab_inp_candidate_assessable": (
            len(groups) >= 8 and coverage >= MIN_OBSERVED_INTERACTION_COVERAGE
        ),
        "event_timing_entry_count": sum(group["event_count"] for group in groups),
        "observed_duration_threshold_ms": EVENT_DURATION_THRESHOLD_MS,
        "interaction_duration_buckets": duration_buckets,
        "event_type_interaction_counts": dict(sorted(event_type_counts.items())),
    }


def _new_interaction_latency(before: object, after: object) -> float | None:
    before_groups = _interaction_groups(before)
    after_groups = _interaction_groups(after)
    previous_ids = {group["interaction_id"] for group in before_groups}
    new_durations = [
        group["duration_ms"]
        for group in after_groups
        if group["interaction_id"] not in previous_ids
    ]
    return max(new_durations, default=None)


def _gzip_bytes(responses: list[Response]) -> tuple[int, int]:
    seen: set[str] = set()
    total = 0
    count = 0
    for response in responses:
        path = urlsplit(response.url).path
        if path in seen:
            continue
        seen.add(path)
        try:
            body = response.body()
        except PlaywrightError:
            raise PerformanceFailure(
                "a first-route asset could not be measured"
            ) from None
        total += len(gzip.compress(body, compresslevel=6, mtime=0))
        count += 1
    return total, count


def _make_context(
    browser: Browser, storage_state: StorageState
) -> tuple[BrowserContext, Page]:
    context = browser.new_context(
        viewport={"width": 390, "height": 844},
        device_scale_factor=1,
        is_mobile=True,
        has_touch=True,
        locale="en-US",
        storage_state=storage_state,
    )
    page = context.new_page()
    client = context.new_cdp_session(page)
    client.send("Emulation.setCPUThrottlingRate", {"rate": 4})
    client.send("Network.enable")
    client.send("Network.emulateNetworkConditions", NETWORK)
    page.add_init_script(
        """(() => {
          window.__stricknaniPerformance = {
            lcp: [],
            shifts: [],
            events: [],
            eventObservers: [],
            seenEventEntries: new Set(),
            eventEntryCount: 0,
            firstInputEntryCount: 0,
            eventObserverSupported: false,
            firstInputSupported: false,
            initialInteractionCount:
              typeof performance.interactionCount === 'number'
                ? performance.interactionCount : 0,
            minInteractionId: Infinity,
            maxInteractionId: 0,
          };
          const state = window.__stricknaniPerformance;
          for (const [type, key] of [
            ['largest-contentful-paint', 'lcp'],
            ['layout-shift', 'shifts'],
          ]) {
            try {
              const observer = new PerformanceObserver(list => {
                for (const entry of list.getEntries()) {
                  if (key === 'lcp') state.lcp.push(entry.startTime);
                  else if (!entry.hadRecentInput) state.shifts.push(entry.value);
                }
              });
              observer.observe({ type, buffered: true });
            } catch (_) {}
          }
          const recordEventEntries = (entries, source) => {
            for (const entry of entries) {
              if (source === 'first-input') {
                state.firstInputEntryCount += 1;
                continue;
              }
              const interactionId = entry.interactionId || 0;
              const key = [interactionId, entry.name, entry.startTime,
                entry.duration].join(':');
              if (state.seenEventEntries.has(key)) continue;
              state.seenEventEntries.add(key);
              state.eventEntryCount += 1;
              if (!interactionId) continue;
              state.minInteractionId = Math.min(
                state.minInteractionId, interactionId,
              );
              state.maxInteractionId = Math.max(
                state.maxInteractionId, interactionId,
              );
              state.events.push({
                interaction_id: interactionId,
                duration_ms: entry.duration,
                event_type: entry.name,
                source,
              });
            }
          };
          for (const [type, source, options] of [
            ['event', 'event', { durationThreshold: 16 }],
            ['first-input', 'first-input', {}],
          ]) {
            if (!PerformanceObserver.supportedEntryTypes.includes(type)) continue;
            try {
              const observer = new PerformanceObserver(list =>
                recordEventEntries(list.getEntries(), source));
              observer.observe({ type, buffered: true, ...options });
              state.eventObservers.push({ observer, source });
              if (type === 'event') state.eventObserverSupported = true;
              else state.firstInputSupported = true;
            } catch (_) {}
          }
        })();"""
    )
    return context, page


def _auth_storage_state(
    browser: Browser, fixture_file: Path, browser_origin: str
) -> StorageState:
    env = rust_fixture.load_fixture_environment(fixture_file)
    context = browser.new_context(viewport={"width": 390, "height": 844})
    page = context.new_page()
    page.goto(f"{browser_origin}/app/login", wait_until="domcontentloaded")
    result: object = page.evaluate(
        """async ({ email, password }) => {
          const sessionResponse = await fetch('/api/v2/session');
          if (!sessionResponse.ok) {
            return { sessionStatus: sessionResponse.status, loginStatus: 0 };
          }
          const session = await sessionResponse.json();
          const response = await fetch('/api/v2/auth/login', {
            method: 'POST',
            credentials: 'same-origin',
            headers: {
              'content-type': 'application/json',
              'x-csrf-token': session.data.csrf_token,
            },
            body: JSON.stringify({ email, password }),
          });
          if (!response.ok) {
            return {
              sessionStatus: sessionResponse.status,
              loginStatus: response.status,
              projectStatus: 0,
            };
          }
          const authenticated = await response.json();
          const listResponse = await fetch('/api/v2/projects?page=1');
          if (!listResponse.ok) {
            return {
              sessionStatus: sessionResponse.status,
              loginStatus: response.status,
              projectStatus: listResponse.status,
            };
          }
          const projects = await listResponse.json();
          const project = projects.data.find(item =>
            item.name === 'Heirloom Baby Blanket');
          if (!project) {
            return {
              sessionStatus: sessionResponse.status,
              loginStatus: response.status,
              projectStatus: 404,
            };
          }
          const detailResponse = await fetch(`/api/v2/projects/${project.id}`);
          if (!detailResponse.ok) {
            return {
              sessionStatus: sessionResponse.status,
              loginStatus: response.status,
              projectStatus: detailResponse.status,
            };
          }
          const detail = (await detailResponse.json()).data;
          const updateResponse = await fetch(`/api/v2/projects/${project.id}`, {
            method: 'PUT',
            credentials: 'same-origin',
            headers: {
              'content-type': 'application/json',
              'x-csrf-token': authenticated.data.csrf_token,
            },
            body: JSON.stringify({
              name: detail.name,
              category: detail.category,
              needles: detail.needles,
              stitch_sample: detail.stitch_sample,
              description: detail.description,
              notes: detail.notes,
              other_materials: detail.other_materials,
              tags: detail.tags,
              link: detail.link,
              is_ai_enhanced: detail.is_ai_enhanced,
              yarn_ids: detail.yarn_ids,
              steps: [
                {
                  title: 'Cast on',
                  description: 'Cast on 64 stitches.',
                  step_number: 1,
                },
                {
                  title: 'Knit the blanket',
                  description: 'Work the textured pattern for 8 rows.',
                  step_number: 2,
                },
              ],
              expected_updated_at: detail.updated_at,
            }),
          });
          return {
            sessionStatus: sessionResponse.status,
            loginStatus: response.status,
            projectStatus: updateResponse.status,
          };
        }""",
        {
            "email": env["STRICKNANI_RUST_TEST_EMAIL"],
            "password": env["STRICKNANI_RUST_TEST_PASSWORD"],
        },
    )
    if (
        not isinstance(result, dict)
        or result.get("loginStatus") != 200
        or result.get("projectStatus") != 200
    ):
        context.close()
        session_status = (
            result.get("sessionStatus", 0) if isinstance(result, dict) else 0
        )
        login_status = result.get("loginStatus", 0) if isinstance(result, dict) else 0
        raise PerformanceFailure(
            "synthetic fixture sign-in failed "
            f"(session HTTP {session_status}, login HTTP {login_status}, "
            "project setup HTTP "
            f"{result.get('projectStatus', 0) if isinstance(result, dict) else 0})"
        )
    state = context.storage_state()
    context.close()
    return state


def _one_sample(
    browser: Browser, state: StorageState, origin: str, sample_number: int
) -> dict[str, Any]:
    global _CURRENT_STAGE
    _CURRENT_STAGE = f"sample {sample_number}: cold Projects route"
    context, page = _make_context(browser, state)
    page.set_default_timeout(15_000)
    app_responses: list[Response] = []
    catalog_responses: list[Response] = []
    asset_encodings: dict[str, set[str]] = {"js": set(), "css": set(), "catalog": set()}

    def capture(response: Response) -> None:
        path = urlsplit(response.url).path
        if path.startswith("/app/_app/immutable/") and path.endswith((".js", ".css")):
            app_responses.append(response)
            kind = "js" if path.endswith(".js") else "css"
            asset_encodings[kind].add(
                response.headers.get("content-encoding", "identity")
            )
        elif path == "/app/messages.json":
            catalog_responses.append(response)
            asset_encodings["catalog"].add(
                response.headers.get("content-encoding", "identity")
            )

    page.on("response", capture)
    cold_ms = _route_ready(page, origin)
    _CURRENT_STAGE = f"sample {sample_number}: cold route assets and metrics"
    page.wait_for_load_state("networkidle", timeout=20_000)
    _settle(page)
    initial_observed = _observer_metrics(page)
    js_bytes, js_count = _gzip_bytes(
        [
            response
            for response in app_responses
            if urlsplit(response.url).path.endswith(".js")
        ]
    )
    css_bytes, css_count = _gzip_bytes(
        [
            response
            for response in app_responses
            if urlsplit(response.url).path.endswith(".css")
        ]
    )
    catalog_bytes, catalog_count = _gzip_bytes(catalog_responses)
    http_sizes = {
        kind: {
            "encoded": sum(
                item["encoded"]
                for item in initial_observed["resources"]
                if item["type"] == kind
            ),
            "transfer": sum(
                item["transfer"]
                for item in initial_observed["resources"]
                if item["type"] == kind
            ),
            "count": sum(
                1 for item in initial_observed["resources"] if item["type"] == kind
            ),
        }
        for kind in ("js", "css", "catalog")
    }

    _CURRENT_STAGE = f"sample {sample_number}: search filter interaction"
    search = page.get_by_role("searchbox", name="Search your projects")
    search_before = _observer_metrics(page)
    search_start = time.perf_counter()
    search.press_sequentially("No such project")
    with page.expect_response(
        lambda response: (
            urlsplit(response.url).path == "/api/v2/projects"
            and parse_qs(urlsplit(response.url).query).get("q") == ["No such project"]
        )
    ) as search_response:
        search.press("Enter")
    if search_response.value.status != 200:
        raise PerformanceFailure("synthetic project search did not succeed")
    page.wait_for_url("**/app/projects?*", timeout=15_000)
    page.get_by_role(
        "heading", name="No projects match those filters.", exact=True
    ).wait_for(state="visible", timeout=15_000)
    if page.locator(".project-card").count() != 0:
        raise PerformanceFailure("project search did not render the empty state")
    search_ready_ms = (time.perf_counter() - search_start) * 1000
    _settle(page)
    search_metrics = _observer_metrics(page)
    search_latency = _new_interaction_latency(
        search_before["events"], search_metrics["events"]
    )

    _CURRENT_STAGE = f"sample {sample_number}: clear search filter"
    _CURRENT_STAGE = f"sample {sample_number}: clear filter button"
    page.locator(".empty-state").get_by_role(
        "button", name="Clear filters", exact=True
    ).click()
    _CURRENT_STAGE = f"sample {sample_number}: cleared filter URL"
    page.wait_for_url("**/app/projects", timeout=15_000)
    _CURRENT_STAGE = f"sample {sample_number}: restored synthetic project card"
    page.get_by_role("heading", name="Heirloom Baby Blanket", exact=True).wait_for(
        state="visible", timeout=15_000
    )
    page.locator(".project-card").wait_for(state="visible", timeout=15_000)

    _CURRENT_STAGE = f"sample {sample_number}: warm reader navigation"
    card = page.locator(".project-card").filter(
        has=page.get_by_role("heading", name="Heirloom Baby Blanket", exact=True)
    )
    link = card.locator("a.resource-card__link")
    reader_href = link.get_attribute("href")
    if not reader_href:
        raise PerformanceFailure("the synthetic reader link was unavailable")
    reader_before = _observer_metrics(page)
    start = time.perf_counter()
    link.click()
    page.wait_for_url(f"**{reader_href}", timeout=15_000)
    page.locator("article.reader").get_by_role(
        "heading", name="Heirloom Baby Blanket", exact=True
    ).wait_for(state="visible", timeout=15_000)
    warm_ms = (time.perf_counter() - start) * 1000
    _settle(page)
    reader_metrics = _observer_metrics(page)
    reader_latency = _new_interaction_latency(
        reader_before["events"], reader_metrics["events"]
    )

    _CURRENT_STAGE = f"sample {sample_number}: reader step navigation"
    page.locator(".next-step-link").click()
    page.get_by_text("Step 2 of 2", exact=True).wait_for(
        state="visible", timeout=10_000
    )
    page.locator(".step-navigation-link").filter(has_text="Previous step").click()
    page.get_by_text("Step 1 of 2", exact=True).wait_for(
        state="visible", timeout=10_000
    )

    _CURRENT_STAGE = f"sample {sample_number}: reader text-size controls"
    reading_copy = page.locator(".pattern-section .reading-copy").first
    initial_text_size = reading_copy.get_attribute("class")
    _open_reader_actions(page)
    page.get_by_role("button", name="Increase text size", exact=True).click()
    increased_text_size = reading_copy.get_attribute("class")
    if increased_text_size == initial_text_size:
        raise PerformanceFailure("pattern text size control had no visible effect")
    page.get_by_role("button", name="Decrease text size", exact=True).click()
    if reading_copy.get_attribute("class") != initial_text_size:
        raise PerformanceFailure("pattern text size could not be restored")

    _CURRENT_STAGE = f"sample {sample_number}: image viewer open and close"
    page.locator(".gallery-image").first.click()
    dialog = page.get_by_role("dialog", name="Project image viewer", exact=True)
    dialog.wait_for(state="visible", timeout=10_000)
    page.keyboard.press("Escape")
    dialog.wait_for(state="hidden", timeout=10_000)

    _CURRENT_STAGE = f"sample {sample_number}: editor input and undo"
    page.get_by_role("link", name="Edit project", exact=True).click()
    page.get_by_role("heading", name="Edit project", exact=True).wait_for(
        state="visible", timeout=15_000
    )
    name_field = page.get_by_role("textbox", name="Project name", exact=True)
    original_name = name_field.input_value()
    name_field.press("End")
    name_field.press_sequentially(" revised")
    if name_field.input_value() == original_name:
        raise PerformanceFailure("project editor input did not update")
    name_field.press("Control+z")
    if name_field.input_value() != original_name:
        raise PerformanceFailure("project editor draft could not be undone")
    project_parent = page.locator(".app-header .brand-lockup.parent-link")
    parent_bounds = (
        project_parent.bounding_box() if project_parent.count() == 1 else None
    )
    if (
        project_parent.count() != 1
        or project_parent.get_attribute("href") != reader_href
        or project_parent.get_attribute("aria-label") != "Back"
        or project_parent.locator(".brand-mark-wrap").count() != 1
        or parent_bounds is None
        or parent_bounds["width"] < 44
        or parent_bounds["height"] < 44
    ):
        raise PerformanceFailure(
            "project editor header parent link has the wrong route or target size"
        )
    project_parent.click()
    page.locator("article.reader").get_by_role(
        "heading", name="Heirloom Baby Blanket", exact=True
    ).wait_for(state="visible", timeout=15_000)
    _settle(page)
    observed = _observer_metrics(page)
    if observed["time_origin_ms"] != initial_observed["time_origin_ms"]:
        raise PerformanceFailure(
            "scripted journey changed documents before session measurement completed"
        )
    if (
        not observed["event_observer_supported"]
        or not observed["first_input_supported"]
    ):
        raise PerformanceFailure(
            "browser did not support required Event Timing observers"
        )
    if (
        observed["interaction_count"] < 8
        or len(_interaction_groups(observed["events"])) < 8
    ):
        raise PerformanceFailure(
            "scripted browser session recorded too few interactions"
        )
    lab_inp = _estimate_lab_inp(observed["events"], observed["interaction_count"])
    context.close()

    return {
        "cold_route_ready_ms": round(cold_ms, 1),
        "lcp_ms": round(initial_observed["lcp"], 1)
        if isinstance(initial_observed["lcp"], (int, float))
        else None,
        "cls": round(float(initial_observed["cls"]), 4),
        "js_gzip6_bytes": js_bytes,
        "js_assets": js_count,
        "css_gzip6_bytes": css_bytes,
        "css_assets": css_count,
        "catalog_gzip6_bytes": catalog_bytes,
        "catalog_assets": catalog_count,
        "http_body_bytes": {
            kind: {
                "encoded": values["encoded"],
                "transfer": values["transfer"],
                "resources": values["count"],
            }
            for kind, values in http_sizes.items()
        },
        "http_content_encoding": {
            kind: sorted(encodings) for kind, encodings in asset_encodings.items()
        },
        "search_ready_ms": round(search_ready_ms, 1),
        "search_event_latency_ms": round(search_latency, 1)
        if search_latency is not None
        else None,
        "warm_reader_ready_ms": round(warm_ms, 1),
        "reader_event_latency_ms": round(reader_latency, 1)
        if reader_latency is not None
        else None,
        "interaction_count_source": observed["interaction_count_source"],
        "event_observer_supported": observed["event_observer_supported"],
        "first_input_supported": observed["first_input_supported"],
        "first_input_entry_count": observed["first_input_entry_count"],
        **lab_inp,
        "resource_count": len(initial_observed["resources"]),
    }


def run(
    binary: Path, web_root: Path, work_dir: Path, browser_executable: Path | None
) -> dict[str, Any]:
    global _CURRENT_STAGE
    fixture_file = work_dir / "fixture.json"
    state_file = work_dir / "fixture-state.json"
    token_file = work_dir / "seed-token.json"
    started = False
    try:
        _CURRENT_STAGE = "start synthetic fixture"
        rust_fixture.start_fixture(
            binary=binary,
            temp_dir=work_dir / "runtime",
            fixture_file=fixture_file,
            state_file=state_file,
            web_root=web_root,
        )
        started = True
        seed_rust.seed_fixture(fixture_file, token_file)
        token_file.unlink(missing_ok=True)
        origin = rust_fixture.load_fixture_environment(fixture_file)[
            "STRICKNANI_RUST_TEST_URL"
        ]
        proxy = _LoopbackGzipProxy(origin)
        try:
            with sync_playwright() as playwright:
                _CURRENT_STAGE = "launch Chromium"
                browser_type = playwright.chromium
                launch_args: dict[str, Any] = {"headless": True}
                if browser_executable is not None:
                    launch_args["executable_path"] = str(browser_executable)
                browser = browser_type.launch(**launch_args)
                try:
                    state = _auth_storage_state(browser, fixture_file, proxy.origin)
                    samples = [
                        _one_sample(browser, state, proxy.origin, sample_number)
                        for sample_number in range(1, SAMPLE_COUNT + 1)
                    ]
                finally:
                    browser.close()
        finally:
            proxy.close()
        js_pass = all(item["js_gzip6_bytes"] <= JS_BUDGET for item in samples)
        css_pass = all(item["css_gzip6_bytes"] <= CSS_BUDGET for item in samples)
        inp_pass = all(
            item["lab_inp_candidate_assessable"] and item["lab_inp_target_pass"]
            for item in samples
        )
        report = {
            "profile": {
                "viewport": "390x844",
                "cpu_throttle": "4x",
                "network": "150ms RTT, 1.6Mbps down, 750kbps up",
                "samples": SAMPLE_COUNT,
                "browser": "Chromium headless mobile emulation",
            },
            "samples": samples,
            "summary": {
                "median_lcp_ms": _median(samples, "lcp_ms"),
                "median_cls": _median(samples, "cls"),
                "median_search_event_latency_ms": _median(
                    samples, "search_event_latency_ms"
                ),
                "median_reader_event_latency_ms": _median(
                    samples, "reader_event_latency_ms"
                ),
                "median_lab_inp_candidate_ms": _median(samples, "lab_inp_candidate_ms"),
                "lab_inp_200ms_target_pass": inp_pass,
                "lab_inp_target_ms": LAB_INP_TARGET_MS,
                "js_gzip6_budget_pass": js_pass,
                "css_gzip6_budget_pass": css_pass,
                "js_gzip6_budget_bytes": JS_BUDGET,
                "css_gzip6_budget_bytes": CSS_BUDGET,
            },
            "limits": [
                "Synthetic fixture and browser-mobile emulation; not a physical "
                "handset.",
                "The lab INP candidate groups Event Timing entries by interaction "
                "ID, uses the longest entry per interaction, and selects the "
                "98th-percentile "
                "candidate using the browser document interaction count. Observation "
                "uses the Event Timing 16ms threshold, so shorter interactions are not "
                "measured and coverage is reported explicitly.",
                "This scripted document-session lab candidate is not human field data, "
                "a population estimate, or production p75.",
                "Asset sizes are gzip level 6 estimates from decoded response bodies; "
                "HTTP encoding is reported separately.",
            ],
        }
        return report
    except (rust_fixture.FixtureError, seed_rust.SeedError):
        raise PerformanceFailure(
            "synthetic performance fixture could not be prepared"
        ) from None
    finally:
        token_file.unlink(missing_ok=True)
        if started:
            rust_fixture.stop_fixture(state_file)


def _median(samples: list[dict[str, Any]], key: str) -> float | None:
    values = [
        sample[key] for sample in samples if isinstance(sample[key], (int, float))
    ]
    return round(statistics.median(values), 1) if values else None


def _argument_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--binary", type=Path, required=True)
    parser.add_argument("--web-root", type=Path, required=True)
    parser.add_argument("--private-dir", type=Path)
    parser.add_argument("--browser-executable", type=Path)
    return parser


def main(argv: list[str] | None = None) -> int:
    args = _argument_parser().parse_args(argv)
    work_dir: Path | None = None
    try:
        work_dir = _private_work_dir(args.private_dir)
        report = run(args.binary, args.web_root, work_dir, args.browser_executable)
    except (PerformanceFailure, rust_fixture.FixtureError) as error:
        print(f"Web performance acceptance failed: {error}", file=sys.stderr)
        return 1
    except Exception as error:  # noqa: BLE001 - suppress unexpected browser diagnostics
        print(
            f"Web performance acceptance failed during {_CURRENT_STAGE}; "
            "diagnostic details were suppressed "
            f"(exception type {type(error).__name__}).",
            file=sys.stderr,
        )
        return 1
    print(json.dumps(report, indent=2, sort_keys=True))
    return (
        0
        if report["summary"]["js_gzip6_budget_pass"]
        and report["summary"]["css_gzip6_budget_pass"]
        and report["summary"]["lab_inp_200ms_target_pass"]
        else 1
    )


if __name__ == "__main__":
    raise SystemExit(main())
