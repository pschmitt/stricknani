import { closeBrowser } from "../lib/common";
import { fn } from "../lib/common";
// Measure controlled synthetic mobile performance on the Rust web fixture.
// Port of ci/stricknani/web_performance_browser.py (Chromium only: it needs
// CDP CPU and network throttling).
import { chromium } from "@playwright/test";
import type {
  Browser,
  BrowserContext,
  LaunchOptions,
  Page,
  Response,
} from "@playwright/test";
import { loadFixtureEnvironment } from "../lib/fixture";
import type { E2eConfig, LiveFixture } from "../lib/harness";
import {
  CSS_BUDGET,
  JS_BUDGET,
  LAB_INP_TARGET_MS,
  LoopbackGzipProxy,
  PerformanceFailure,
  SAMPLE_COUNT,
  estimateLabInp,
  gzipLevel6,
  interactionGroups,
  median,
  newInteractionLatency,
  roundTo,
} from "../lib/performance";
import type { LabInp } from "../lib/performance";

type StorageState = Awaited<ReturnType<BrowserContext["storageState"]>>;

const NETWORK = {
  latency: 150,
  downloadThroughput: Math.trunc(1_600_000 / 8),
  uploadThroughput: Math.trunc(750_000 / 8),
  offline: false,
  connectionType: "cellular3g",
};
const BLANKET = "Heirloom Baby Blanket";

let currentStage = "initialization";

function markStage(value: string): void {
  currentStage = value;
}

type ObservedResource = { type: string; encoded: number; transfer: number };

type ObserverMetrics = {
  lcp: number | null;
  cls: number;
  time_origin_ms: number;
  event_observer_supported: boolean;
  first_input_supported: boolean;
  event_entry_count: number;
  first_input_entry_count: number;
  interaction_count: number;
  interaction_count_source: string;
  events: unknown[];
  resources: ObservedResource[];
};

type Sample = {
  cold_route_ready_ms: number;
  lcp_ms: number | null;
  cls: number;
  js_gzip6_bytes: number;
  js_assets: number;
  css_gzip6_bytes: number;
  css_assets: number;
  catalog_gzip6_bytes: number;
  catalog_assets: number;
  http_body_bytes: Record<
    string,
    { encoded: number; transfer: number; resources: number }
  >;
  http_content_encoding: Record<string, string[]>;
  search_ready_ms: number;
  search_event_latency_ms: number | null;
  warm_reader_ready_ms: number;
  reader_event_latency_ms: number | null;
  interaction_count_source: string;
  event_observer_supported: boolean;
  first_input_supported: boolean;
  first_input_entry_count: number;
  resource_count: number;
} & LabInp;

const OBSERVER_METRICS_SCRIPT = `        () => {
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
        }`;

const INIT_SCRIPT = `        (() => {
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
        })();`;

const AUTH_SCRIPT = `        async ({ email, password }) => {
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
          const detailResponse = await fetch(\`/api/v2/projects/\${project.id}\`);
          if (!detailResponse.ok) {
            return {
              sessionStatus: sessionResponse.status,
              loginStatus: response.status,
              projectStatus: detailResponse.status,
            };
          }
          const detail = (await detailResponse.json()).data;
          const updateResponse = await fetch(\`/api/v2/projects/\${project.id}\`, {
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
        }`;

async function routeReady(page: Page, origin: string): Promise<number> {
  const start = performance.now();
  await page.goto(`${origin}/app/projects`, { waitUntil: "domcontentloaded" });
  await page
    .getByRole("heading", { name: "Projects", exact: true })
    .waitFor({ state: "visible", timeout: 20_000 });
  await page
    .getByRole("heading", { name: BLANKET, exact: true })
    .waitFor({ state: "visible", timeout: 20_000 });
  return performance.now() - start;
}

async function settle(page: Page): Promise<void> {
  await page.evaluate(
    fn(`async () => {
    await new Promise(resolve => requestAnimationFrame(() =>
      requestAnimationFrame(resolve)));
    return undefined;
  }`),
  );
}

async function openReaderActions(page: Page): Promise<void> {
  const disclosure = page.locator("details.action-overflow");
  await disclosure.waitFor({ state: "attached", timeout: 10_000 });
  if ((await disclosure.count()) !== 1) {
    throw new PerformanceFailure("reader actions disclosure was ambiguous");
  }
  const summary = disclosure.locator("summary");
  if ((await summary.getAttribute("aria-label")) !== "More actions") {
    throw new PerformanceFailure("reader actions disclosure label changed");
  }
  if ((await disclosure.getAttribute("open")) === null) {
    await summary.click();
  }
  await page.waitForFunction(
    fn(
      "() => document.querySelector('details.action-overflow')?.open === true",
    ),
    undefined,
    { timeout: 5_000 },
  );
}

async function observerMetrics(page: Page): Promise<ObserverMetrics> {
  const value: unknown = await page.evaluate(fn(OBSERVER_METRICS_SCRIPT));
  if (typeof value !== "object" || value === null || Array.isArray(value)) {
    throw new PerformanceFailure(
      "browser performance observers were unavailable",
    );
  }
  return value as ObserverMetrics;
}

async function gzipBytes(responses: Response[]): Promise<[number, number]> {
  const seen = new Set<string>();
  let total = 0;
  let count = 0;
  for (const response of responses) {
    const path = new URL(response.url()).pathname;
    if (seen.has(path)) {
      continue;
    }
    seen.add(path);
    let body: Buffer;
    try {
      body = await response.body();
    } catch {
      throw new PerformanceFailure("a first-route asset could not be measured");
    }
    total += gzipLevel6(body).length;
    count += 1;
  }
  return [total, count];
}

async function makeContext(
  browser: Browser,
  storageState: StorageState,
): Promise<[BrowserContext, Page]> {
  const context = await browser.newContext({
    viewport: { width: 390, height: 844 },
    deviceScaleFactor: 1,
    isMobile: true,
    hasTouch: true,
    locale: "en-US",
    storageState,
  });
  const page = await context.newPage();
  const client = await context.newCDPSession(page);
  await client.send("Emulation.setCPUThrottlingRate", { rate: 4 });
  await client.send("Network.enable");
  await client.send("Network.emulateNetworkConditions", {
    ...NETWORK,
    connectionType: "none" as const,
  });
  await page.addInitScript({ content: INIT_SCRIPT });
  return [context, page];
}

async function authStorageState(
  browser: Browser,
  fixtureFile: string,
  browserOrigin: string,
): Promise<StorageState> {
  const env = loadFixtureEnvironment(fixtureFile);
  const context = await browser.newContext({
    viewport: { width: 390, height: 844 },
  });
  const page = await context.newPage();
  await page.goto(`${browserOrigin}/app/login`, {
    waitUntil: "domcontentloaded",
  });
  const result: unknown = await page.evaluate(fn(AUTH_SCRIPT), {
    email: env.STRICKNANI_RUST_TEST_EMAIL,
    password: env.STRICKNANI_RUST_TEST_PASSWORD,
  });
  const fields =
    typeof result === "object" && result !== null
      ? (result as Record<string, unknown>)
      : null;
  if (
    fields === null ||
    fields["loginStatus"] !== 200 ||
    fields["projectStatus"] !== 200
  ) {
    await context.close();
    const sessionStatus = fields?.["sessionStatus"] ?? 0;
    const loginStatus = fields?.["loginStatus"] ?? 0;
    const projectStatus = fields?.["projectStatus"] ?? 0;
    throw new PerformanceFailure(
      "synthetic fixture sign-in failed " +
        `(session HTTP ${String(sessionStatus)}, login HTTP ` +
        `${String(loginStatus)}, project setup HTTP ${String(projectStatus)})`,
    );
  }
  const state = await context.storageState();
  await context.close();
  return state;
}

async function oneSample(
  browser: Browser,
  state: StorageState,
  origin: string,
  sampleNumber: number,
): Promise<Sample> {
  markStage(`sample ${sampleNumber}: cold Projects route`);
  const [context, page] = await makeContext(browser, state);
  page.setDefaultTimeout(15_000);
  const appResponses: Response[] = [];
  const catalogResponses: Response[] = [];
  const assetEncodings: Record<string, Set<string>> = {
    js: new Set(),
    css: new Set(),
    catalog: new Set(),
  };

  page.on("response", (response) => {
    const path = new URL(response.url()).pathname;
    const encoding = response.headers()["content-encoding"] ?? "identity";
    if (
      path.startsWith("/app/_app/immutable/") &&
      (path.endsWith(".js") || path.endsWith(".css"))
    ) {
      appResponses.push(response);
      (assetEncodings[path.endsWith(".js") ? "js" : "css"] as Set<string>).add(
        encoding,
      );
    } else if (path === "/app/messages.json") {
      catalogResponses.push(response);
      (assetEncodings["catalog"] as Set<string>).add(encoding);
    }
  });
  const coldMs = await routeReady(page, origin);
  markStage(`sample ${sampleNumber}: cold route assets and metrics`);
  await page.waitForLoadState("networkidle", { timeout: 20_000 });
  await settle(page);
  const initialObserved = await observerMetrics(page);
  const [jsBytes, jsCount] = await gzipBytes(
    appResponses.filter((response) =>
      new URL(response.url()).pathname.endsWith(".js"),
    ),
  );
  const [cssBytes, cssCount] = await gzipBytes(
    appResponses.filter((response) =>
      new URL(response.url()).pathname.endsWith(".css"),
    ),
  );
  const [catalogBytes, catalogCount] = await gzipBytes(catalogResponses);
  const httpSizes: Sample["http_body_bytes"] = {};
  for (const kind of ["js", "css", "catalog"]) {
    const items = initialObserved.resources.filter(
      (item) => item.type === kind,
    );
    httpSizes[kind] = {
      encoded: items.reduce((sum, item) => sum + item.encoded, 0),
      transfer: items.reduce((sum, item) => sum + item.transfer, 0),
      resources: items.length,
    };
  }

  markStage(`sample ${sampleNumber}: search filter interaction`);
  const search = page.getByRole("searchbox", { name: "Search your projects" });
  const searchBefore = await observerMetrics(page);
  const searchStart = performance.now();
  await search.pressSequentially("No such project");
  const [searchResponse] = await Promise.all([
    page.waitForResponse((response) => {
      const url = new URL(response.url());
      const queries = url.searchParams.getAll("q");
      return (
        url.pathname === "/api/v2/projects" &&
        queries.length === 1 &&
        queries[0] === "No such project"
      );
    }),
    search.press("Enter"),
  ]);
  if (searchResponse.status() !== 200) {
    throw new PerformanceFailure("synthetic project search did not succeed");
  }
  await page.waitForURL("**/app/projects?*", { timeout: 15_000 });
  await page
    .getByRole("heading", {
      name: "No projects match those filters.",
      exact: true,
    })
    .waitFor({ state: "visible", timeout: 15_000 });
  if ((await page.locator(".project-card").count()) !== 0) {
    throw new PerformanceFailure(
      "project search did not render the empty state",
    );
  }
  const searchReadyMs = performance.now() - searchStart;
  await settle(page);
  const searchMetrics = await observerMetrics(page);
  const searchLatency = newInteractionLatency(
    searchBefore.events,
    searchMetrics.events,
  );

  markStage(`sample ${sampleNumber}: clear search filter`);
  markStage(`sample ${sampleNumber}: clear filter button`);
  await page
    .locator(".empty-state")
    .getByRole("button", { name: "Clear filters", exact: true })
    .click();
  markStage(`sample ${sampleNumber}: cleared filter URL`);
  await page.waitForURL("**/app/projects", { timeout: 15_000 });
  markStage(`sample ${sampleNumber}: restored synthetic project card`);
  await page
    .getByRole("heading", { name: BLANKET, exact: true })
    .waitFor({ state: "visible", timeout: 15_000 });
  await page
    .locator(".project-card")
    .waitFor({ state: "visible", timeout: 15_000 });

  markStage(`sample ${sampleNumber}: warm reader navigation`);
  const card = page.locator(".project-card").filter({
    has: page.getByRole("heading", { name: BLANKET, exact: true }),
  });
  const link = card.locator("a.resource-card__link");
  const readerHref = await link.getAttribute("href");
  if (!readerHref) {
    throw new PerformanceFailure("the synthetic reader link was unavailable");
  }
  const readerBefore = await observerMetrics(page);
  const start = performance.now();
  await link.click();
  await page.waitForURL(`**${readerHref}`, { timeout: 15_000 });
  await page
    .locator("article.reader")
    .getByRole("heading", { name: BLANKET, exact: true })
    .waitFor({ state: "visible", timeout: 15_000 });
  const warmMs = performance.now() - start;
  await settle(page);
  const readerMetrics = await observerMetrics(page);
  const readerLatency = newInteractionLatency(
    readerBefore.events,
    readerMetrics.events,
  );

  markStage(`sample ${sampleNumber}: reader step navigation`);
  await page.locator(".next-step-link").click();
  await page
    .getByText("Step 2 of 2", { exact: true })
    .waitFor({ state: "visible", timeout: 10_000 });
  await page
    .locator(".step-navigation-link")
    .filter({ hasText: "Previous step" })
    .click();
  await page
    .getByText("Step 1 of 2", { exact: true })
    .waitFor({ state: "visible", timeout: 10_000 });

  markStage(`sample ${sampleNumber}: reader text-size controls`);
  const readingCopy = page.locator(".pattern-section .reading-copy").first();
  const initialTextSize = await readingCopy.getAttribute("class");
  await openReaderActions(page);
  await page
    .getByRole("button", { name: "Increase text size", exact: true })
    .click();
  const increasedTextSize = await readingCopy.getAttribute("class");
  if (increasedTextSize === initialTextSize) {
    throw new PerformanceFailure(
      "pattern text size control had no visible effect",
    );
  }
  await page
    .getByRole("button", { name: "Decrease text size", exact: true })
    .click();
  if ((await readingCopy.getAttribute("class")) !== initialTextSize) {
    throw new PerformanceFailure("pattern text size could not be restored");
  }

  markStage(`sample ${sampleNumber}: image viewer open and close`);
  // The actions menu stays open after the text-size buttons and would cover the
  // cover image; dismiss it as a reader would.
  await page.keyboard.press("Escape");
  await page
    .getByRole("button", { name: "Decrease text size", exact: true })
    .waitFor({ state: "hidden", timeout: 5_000 });
  await page.locator(".photo-strip__open").first().click();
  const dialog = page.getByRole("dialog", {
    name: "Project image viewer",
    exact: true,
  });
  await dialog.waitFor({ state: "visible", timeout: 10_000 });
  await page.keyboard.press("Escape");
  await dialog.waitFor({ state: "hidden", timeout: 10_000 });

  markStage(`sample ${sampleNumber}: editor input and undo`);
  await page.getByRole("link", { name: "Edit project", exact: true }).click();
  await page
    .getByRole("heading", { name: "Edit project", exact: true })
    .waitFor({ state: "visible", timeout: 15_000 });
  const nameField = page.getByRole("textbox", {
    name: "Project name",
    exact: true,
  });
  const originalName = await nameField.inputValue();
  await nameField.press("End");
  await nameField.pressSequentially(" revised");
  if ((await nameField.inputValue()) === originalName) {
    throw new PerformanceFailure("project editor input did not update");
  }
  await nameField.press("Control+z");
  if ((await nameField.inputValue()) !== originalName) {
    throw new PerformanceFailure("project editor draft could not be undone");
  }
  const projectParent = page.locator(".app-header .brand-lockup.parent-link");
  const parentCount = await projectParent.count();
  const parentBounds =
    parentCount === 1 ? await projectParent.boundingBox() : null;
  if (
    parentCount !== 1 ||
    (await projectParent.getAttribute("href")) !== readerHref ||
    (await projectParent.getAttribute("aria-label")) !== "Back" ||
    (await projectParent.locator(".brand-mark-wrap").count()) !== 1 ||
    parentBounds === null ||
    parentBounds.width < 44 ||
    parentBounds.height < 44
  ) {
    throw new PerformanceFailure(
      "project editor header parent link has the wrong route or target size",
    );
  }
  await projectParent.click();
  await page
    .locator("article.reader")
    .getByRole("heading", { name: BLANKET, exact: true })
    .waitFor({ state: "visible", timeout: 15_000 });
  await settle(page);
  const observed = await observerMetrics(page);
  if (observed.time_origin_ms !== initialObserved.time_origin_ms) {
    throw new PerformanceFailure(
      "scripted journey changed documents before session measurement completed",
    );
  }
  if (!observed.event_observer_supported || !observed.first_input_supported) {
    throw new PerformanceFailure(
      "browser did not support required Event Timing observers",
    );
  }
  if (
    observed.interaction_count < 8 ||
    interactionGroups(observed.events).length < 8
  ) {
    throw new PerformanceFailure(
      "scripted browser session recorded too few interactions",
    );
  }
  const labInp = estimateLabInp(observed.events, observed.interaction_count);
  await context.close();

  return {
    cold_route_ready_ms: roundTo(coldMs, 1),
    lcp_ms:
      typeof initialObserved.lcp === "number"
        ? roundTo(initialObserved.lcp, 1)
        : null,
    cls: roundTo(Number(initialObserved.cls), 4),
    js_gzip6_bytes: jsBytes,
    js_assets: jsCount,
    css_gzip6_bytes: cssBytes,
    css_assets: cssCount,
    catalog_gzip6_bytes: catalogBytes,
    catalog_assets: catalogCount,
    http_body_bytes: httpSizes,
    http_content_encoding: Object.fromEntries(
      Object.entries(assetEncodings).map(([kind, encodings]) => [
        kind,
        [...encodings].sort(),
      ]),
    ),
    search_ready_ms: roundTo(searchReadyMs, 1),
    search_event_latency_ms:
      searchLatency !== null ? roundTo(searchLatency, 1) : null,
    warm_reader_ready_ms: roundTo(warmMs, 1),
    reader_event_latency_ms:
      readerLatency !== null ? roundTo(readerLatency, 1) : null,
    interaction_count_source: observed.interaction_count_source,
    event_observer_supported: observed.event_observer_supported,
    first_input_supported: observed.first_input_supported,
    first_input_entry_count: observed.first_input_entry_count,
    ...labInp,
    resource_count: initialObserved.resources.length,
  };
}

function sortKeys(value: unknown): unknown {
  if (Array.isArray(value)) {
    return value.map(sortKeys);
  }
  if (typeof value === "object" && value !== null) {
    const record = value as Record<string, unknown>;
    return Object.fromEntries(
      Object.keys(record)
        .sort()
        .map((key) => [key, sortKeys(record[key])]),
    );
  }
  return value;
}

export type PerformanceReport = {
  profile: Record<string, unknown>;
  samples: Sample[];
  summary: Record<string, unknown>;
  limits: string[];
};

async function measure(
  f: LiveFixture,
  cfg: E2eConfig,
): Promise<PerformanceReport> {
  const origin = loadFixtureEnvironment(f.fixtureFile).STRICKNANI_RUST_TEST_URL;
  const proxy = await LoopbackGzipProxy.start(origin);
  let samples: Sample[] = [];
  try {
    markStage("launch Chromium");
    const launchOptions: LaunchOptions = {
      headless: true,
    };
    if (cfg.browser === "chromium" && cfg.browserExecutable) {
      launchOptions.executablePath = cfg.browserExecutable;
    }
    const browser = await chromium.launch(launchOptions);
    try {
      const state = await authStorageState(
        browser,
        f.fixtureFile,
        proxy.origin,
      );
      samples = [];
      for (let sampleNumber = 1; sampleNumber <= SAMPLE_COUNT; sampleNumber++) {
        samples.push(
          await oneSample(browser, state, proxy.origin, sampleNumber),
        );
      }
    } finally {
      await closeBrowser(browser);
    }
  } finally {
    await proxy.close();
  }
  const jsPass = samples.every((item) => item.js_gzip6_bytes <= JS_BUDGET);
  const cssPass = samples.every((item) => item.css_gzip6_bytes <= CSS_BUDGET);
  const inpPass = samples.every(
    (item) => item.lab_inp_candidate_assessable && item.lab_inp_target_pass,
  );
  return {
    profile: {
      viewport: "390x844",
      cpu_throttle: "4x",
      network: "150ms RTT, 1.6Mbps down, 750kbps up",
      samples: SAMPLE_COUNT,
      browser: "Chromium headless mobile emulation",
    },
    samples,
    summary: {
      median_lcp_ms: median(samples, "lcp_ms"),
      median_cls: median(samples, "cls"),
      median_search_event_latency_ms: median(
        samples,
        "search_event_latency_ms",
      ),
      median_reader_event_latency_ms: median(
        samples,
        "reader_event_latency_ms",
      ),
      median_lab_inp_candidate_ms: median(samples, "lab_inp_candidate_ms"),
      lab_inp_200ms_target_pass: inpPass,
      lab_inp_target_ms: LAB_INP_TARGET_MS,
      js_gzip6_budget_pass: jsPass,
      css_gzip6_budget_pass: cssPass,
      js_gzip6_budget_bytes: JS_BUDGET,
      css_gzip6_budget_bytes: CSS_BUDGET,
    },
    limits: [
      "Synthetic fixture and browser-mobile emulation; not a physical handset.",
      "The lab INP candidate groups Event Timing entries by interaction ID, " +
        "uses the longest entry per interaction, and selects the " +
        "98th-percentile candidate using the browser document interaction " +
        "count. Observation uses the Event Timing 16ms threshold, so shorter " +
        "interactions are not measured and coverage is reported explicitly.",
      "This scripted document-session lab candidate is not human field data, " +
        "a population estimate, or production p75.",
      "Asset sizes are gzip level 6 estimates from decoded response bodies; " +
        "HTTP encoding is reported separately.",
    ],
  };
}

/**
 * Run the synthetic performance journey against a started, seeded fixture.
 * Throws when a budget or the lab INP target fails; the failure messages never
 * contain page content or fixture credentials.
 */
export async function runPerformanceJourney(
  f: LiveFixture,
  cfg: E2eConfig,
): Promise<PerformanceReport> {
  markStage("start synthetic fixture");
  let report: PerformanceReport;
  try {
    report = await measure(f, cfg);
  } catch (error) {
    if (error instanceof PerformanceFailure) {
      throw new PerformanceFailure(
        `Web performance acceptance failed: ${error.message}`,
      );
    }
    const kind = error instanceof Error ? error.name : typeof error;
    throw new PerformanceFailure(
      `Web performance acceptance failed during ${currentStage}; ` +
        `diagnostic details were suppressed (exception type ${kind}).`,
    );
  }
  console.log(JSON.stringify(sortKeys(report), null, 2));
  const summary = report.summary;
  if (
    summary["js_gzip6_budget_pass"] !== true ||
    summary["css_gzip6_budget_pass"] !== true ||
    summary["lab_inp_200ms_target_pass"] !== true
  ) {
    throw new PerformanceFailure(
      "Web performance acceptance failed: a bundle budget or the lab INP " +
        "target was not met (see the printed report)",
    );
  }
  return report;
}
