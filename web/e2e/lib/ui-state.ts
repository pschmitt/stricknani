// Shared state for the web UI browser journey (port of the closure locals of
// ci/stricknani/web_ui_browser.py `run_acceptance`).
//
// Part A (journeys/ui.ts), part B (journeys/ui-reader.ts) and part C
// (journeys/ui-editor.ts) all receive one `UiCtx` and read/write the same
// `UiState`. Field names are the camelCase of the python names.
import type {
  Browser,
  BrowserContext,
  ConsoleMessage,
  Page,
  Request,
  Response,
} from "@playwright/test";
import type { E2eConfig } from "./harness";
import { responsePath } from "./common";

/** Per-run options (python run_acceptance keyword arguments). */
export interface UiJourneyOptions {
  /** python `service_fixture_enabled`: the optional-service fixture runs. */
  serviceFixtureEnabled: boolean;
  /** python `ocr_mode`. */
  ocrMode: "success" | "failure";
  /** python `archive_mode`. */
  archiveMode: "success" | "failure";
  /** python `signup_enabled`. */
  signupEnabled: boolean;
}

/** python `csp_event_summaries` entries. */
export interface CspEventSummary {
  stage: string;
  directive: string;
  blockedURIClass: string;
  sourcePath: string;
  lineNumber: number | undefined;
  columnNumber: number | undefined;
}

/**
 * Every closure local that python `run_acceptance` declares before the browser
 * launches. Tuples keep the python tuple order so ported `any(...)` checks
 * read the same.
 *
 * IMPORTANT: response observers read JSON bodies asynchronously. They push
 * their entry synchronously (so order and `.length` snapshots match python)
 * and fill the body-derived fields later. Always `await settleObservers(st)`
 * before reading `sessionObservations`, `sessionIdentityObservations` or
 * `projectListSizes`.
 */
export interface UiState {
  /** Stage marker (`stageMarker(...)` of the harness); python mark_stage/current_stage. */
  stage: { mark(value: string): void; current(): string };
  cspEventSummaries: CspEventSummary[];
  /** [status, userIsNull, csrfPresent, numericUserId] for session + login. */
  sessionObservations: Array<[number, boolean, boolean, boolean]>;
  /** [status, session user email or null]; same indexes as sessionObservations. */
  sessionIdentityObservations: Array<[number, string | null]>;
  /** [path, hasCsrfHeader] for login/logout/signup/favorite requests. */
  csrfRequests: Array<[string, boolean]>;
  authResponses: Array<[string, number]>;
  projectResponses: Array<[string, number]>;
  /** [method, path] of project mutations (POST/PUT/PATCH/DELETE). */
  projectMutationRequests: Array<[string, string]>;
  /** Length of the `data` list of each GET /api/v2/projects, null when unknown. */
  projectListSizes: Array<number | null>;
  /** Parsed query (python parse_qs: blank values dropped) of each list request. */
  projectListQueries: Array<Record<string, string[]>>;
  yarnResponses: Array<[string, number]>;
  serviceResponses: Array<[string, number]>;
  categoryResponses: Array<[string, number]>;
  appAssetStatuses: number[];
  catalogStatuses: number[];
  originalProjectImageRequests: string[];
  originalProjectImageResponses: Array<[string, number]>;
  cspErrors: string[];
  consoleErrors: string[];
  consoleErrorLocations: string[];
  /** python page_errors (list of True); length is what matters. */
  pageErrors: boolean[];
  pageErrorTypes: string[];
  responsiveFailures: string[];
  /** Console 404 path tolerated while `expected404Window` is true. */
  expected404Path: string;
  expected404Window: boolean;
  expectedConflictWindow: boolean;
  expectedSignupConflictWindow: boolean;
  expectedSignupConflictPending: boolean;
  expectedConflictPaths: Set<string>;
  /** ocr_mode; the console observer tolerates 5xx OCR errors in failure mode. */
  ocrMode: "success" | "failure";
  /** In-flight async observer work; see settleObservers. */
  pending: Promise<void>[];
}

/**
 * Context handed to every journey part.
 *
 * Part A (`uiPartA`) fills `projectId` (0 until the reader fixture is
 * prepared). All other fields are final when the part starts.
 */
export interface UiCtx {
  /** The single page used for the whole journey (390x844 mobile, light, en-US). */
  page: Page;
  /** Its browser context (cookies: context.cookies(origin)). */
  context: BrowserContext;
  /** The launched browser (python `browser`). */
  browser: Browser;
  /** All observer state (see UiState). */
  st: UiState;
  /** Loopback origin of the fixture, no trailing slash (python `origin`). */
  origin: string;
  /** Fixture account e-mail (python `email`). */
  email: string;
  /** Fixture account password (python `password`). Never put it into messages. */
  password: string;
  /** Private screenshot directory (python `screenshots`, a path string). */
  screenshots: string;
  /** Path of browser-stage.txt; part C unlinks it at the end (python stage_file). */
  stageFile: string;
  /** python mark_stage: records the sanitised stage (also st.stage.mark). */
  markStage: (value: string) => void;
  /** Run configuration (browser name: cfg.browser). */
  cfg: E2eConfig;
  /** Journey options (ocrMode, archiveMode, signupEnabled, serviceFixtureEnabled). */
  o: UiJourneyOptions;
  /**
   * python `project_id`: numeric id of the seeded project, returned by
   * prepareProjectReaderFixture. Assigned by part A before part B starts.
   */
  projectId: number;
}

export function createUiState(
  ocrMode: "success" | "failure",
  stage: { mark(value: string): void; current(): string },
): UiState {
  return {
    stage,
    cspEventSummaries: [],
    sessionObservations: [],
    sessionIdentityObservations: [],
    csrfRequests: [],
    authResponses: [],
    projectResponses: [],
    projectMutationRequests: [],
    projectListSizes: [],
    projectListQueries: [],
    yarnResponses: [],
    serviceResponses: [],
    categoryResponses: [],
    appAssetStatuses: [],
    catalogStatuses: [],
    originalProjectImageRequests: [],
    originalProjectImageResponses: [],
    cspErrors: [],
    consoleErrors: [],
    consoleErrorLocations: [],
    pageErrors: [],
    pageErrorTypes: [],
    responsiveFailures: [],
    expected404Path: "/app/this-route-does-not-exist",
    expected404Window: false,
    expectedConflictWindow: false,
    expectedSignupConflictWindow: false,
    expectedSignupConflictPending: false,
    expectedConflictPaths: new Set<string>(),
    ocrMode,
    pending: [],
  };
}

/** python parse_qs(urlsplit(url).query): blank values are dropped. */
export function parseQs(url: string): Record<string, string[]> {
  const result: Record<string, string[]> = {};
  for (const [key, value] of new URL(url).searchParams) {
    if (value === "") {
      continue;
    }
    (result[key] ??= []).push(value);
  }
  return result;
}

/** python `parse_qs(urlsplit(url).query).get(key) == [value]`. */
export function queryIs(url: string, key: string, value: string): boolean {
  const values = parseQs(url)[key];
  return values !== undefined && values.length === 1 && values[0] === value;
}

/** python `urlsplit(url).path`. */
export function urlPath(url: string): string {
  return new URL(url).pathname;
}

function safePath(url: string | undefined): string {
  if (!url) {
    return "";
  }
  try {
    return urlPath(url);
  } catch {
    return "";
  }
}

/** Wait until every in-flight async observer has stored its result. */
export async function settleObservers(st: UiState): Promise<void> {
  while (st.pending.length > 0) {
    await Promise.allSettled(st.pending.splice(0));
  }
}

async function readEnvelopeData(
  response: Response,
): Promise<Record<string, unknown> | null> {
  if (response.status() !== 200) {
    return null;
  }
  let payload: unknown;
  try {
    payload = await response.json();
  } catch {
    return null;
  }
  if (typeof payload !== "object" || payload === null) {
    return null;
  }
  const data = (payload as Record<string, unknown>).data;
  if (typeof data !== "object" || data === null || Array.isArray(data)) {
    return null;
  }
  return data as Record<string, unknown>;
}

/** python observe_request. */
export function observeRequest(st: UiState, request: Request): void {
  const requestUrl = request.url();
  const path = urlPath(requestUrl);
  const method = request.method();
  if (
    (path === "/api/v2/projects" || path.startsWith("/api/v2/projects/")) &&
    ["POST", "PUT", "PATCH", "DELETE"].includes(method)
  ) {
    st.projectMutationRequests.push([method, path]);
  }
  if (path.startsWith("/media/projects/")) {
    st.originalProjectImageRequests.push(path);
  }
  if (path === "/api/v2/projects") {
    st.projectListQueries.push(parseQs(requestUrl));
  }
  if (
    ![
      "/api/v2/auth/login",
      "/api/v2/auth/logout",
      "/api/v2/auth/signup",
    ].includes(path) &&
    !(path.startsWith("/api/v2/projects/") && path.endsWith("/favorite"))
  ) {
    return;
  }
  st.csrfRequests.push([path, Boolean(request.headers()["x-csrf-token"])]);
}

/** python observe_response (JSON-derived fields are filled asynchronously). */
export function observeResponse(st: UiState, response: Response): void {
  const path = responsePath(response);
  const status = response.status();
  const method = response.request().method();
  if (path.startsWith("/media/projects/")) {
    st.originalProjectImageResponses.push([path, status]);
  }
  if (
    path === "/api/v2/auth/login" ||
    path === "/api/v2/auth/logout" ||
    path === "/api/v2/auth/signup"
  ) {
    st.authResponses.push([path, status]);
    if (
      path === "/api/v2/auth/signup" &&
      st.expectedSignupConflictWindow &&
      method === "POST" &&
      status === 409
    ) {
      st.expectedSignupConflictPending = true;
    }
  }
  if (path.startsWith("/api/v2/projects/") || path === "/api/v2/projects") {
    st.projectResponses.push([path, status]);
    if (
      st.expectedConflictWindow &&
      path.startsWith("/api/v2/projects/") &&
      method === "PUT" &&
      status === 409
    ) {
      st.expectedConflictPaths.add(path);
    }
  }
  if (path === "/api/v2/projects" && method === "GET") {
    const index = st.projectListSizes.length;
    st.projectListSizes.push(null);
    if (status === 200) {
      st.pending.push(
        (async () => {
          try {
            const payload: unknown = await response.json();
            const data =
              typeof payload === "object" && payload !== null
                ? (payload as Record<string, unknown>).data
                : null;
            if (Array.isArray(data)) {
              st.projectListSizes[index] = data.length;
            }
          } catch {
            // size stays null (python: size = None)
          }
        })(),
      );
    }
  }
  if (path.startsWith("/api/v2/yarns/") || path === "/api/v2/yarns") {
    st.yarnResponses.push([path, status]);
  }
  if (path === "/api/v2/utils/ocr") {
    st.serviceResponses.push([path, status]);
  }
  if (path.startsWith("/api/v2/categories")) {
    st.categoryResponses.push([path, status]);
  }
  if (path === "/api/v2/session" || path === "/api/v2/auth/login") {
    const observation: [number, boolean, boolean, boolean] = [
      status,
      false,
      false,
      false,
    ];
    const identity: [number, string | null] = [status, null];
    st.sessionObservations.push(observation);
    st.sessionIdentityObservations.push(identity);
    st.pending.push(
      (async () => {
        const data = await readEnvelopeData(response);
        if (data === null) {
          return;
        }
        const csrf = data.csrf_token;
        observation[2] = typeof csrf === "string" && csrf.length > 0;
        const user = data.user;
        observation[1] = user === null || user === undefined;
        if (typeof user === "object" && user !== null) {
          const record = user as Record<string, unknown>;
          const id = record.id;
          observation[3] =
            typeof id === "number" &&
            Number.isInteger(id) &&
            id > 0 &&
            id < 2 ** 53;
          if (typeof record.email === "string") {
            identity[1] = record.email;
          }
        }
      })(),
    );
  } else if (path.startsWith("/app/_app/")) {
    st.appAssetStatuses.push(status);
  } else if (path === "/app/messages.json") {
    st.catalogStatuses.push(status);
  }
}

/** python observe_console. */
export function observeConsole(st: UiState, message: ConsoleMessage): void {
  const text = message.text().toLowerCase();
  const cspViolation =
    text.includes("content security policy") ||
    (text.includes("violat") &&
      (text.includes("script-src") || text.includes("style-src")));
  if (message.type() !== "error") {
    return;
  }
  const location = message.location();
  const source = safePath(location.url);
  if (cspViolation) {
    const directives = [
      "script-src",
      "style-src",
      "default-src",
      "img-src",
      "font-src",
      "connect-src",
    ];
    const directive =
      directives.find((item) => text.includes(item)) ?? "CSP directive";
    if (directive === "style-src") {
      let violationKind: string;
      if (text.includes("style attribute") || text.includes("style attr")) {
        violationKind = "style-attribute";
      } else if (text.includes("inline")) {
        violationKind = "inline-style";
      } else {
        violationKind = "stylesheet-or-rule";
      }
      st.cspErrors.push(`${directive}:${violationKind}`);
    } else {
      st.cspErrors.push(directive);
    }
    st.cspEventSummaries.push({
      stage: st.stage.current(),
      directive,
      blockedURIClass: text.includes("inline")
        ? "inline"
        : text.includes("data:")
          ? "data"
          : "external",
      sourcePath: source,
      lineNumber: location.lineNumber,
      columnNumber: location.columnNumber,
    });
    st.consoleErrors.push("csp_violation");
    return;
  }
  const hasResponse = (wanted: number): boolean =>
    st.projectResponses.some(
      ([responsePath, status]) => responsePath === source && status === wanted,
    );
  if (
    st.expected404Window &&
    (text.includes("404") || text.includes("not found")) &&
    source === st.expected404Path
  ) {
    st.consoleErrors.push("expected_unknown_route_404");
  } else if (
    st.expectedConflictPaths.has(source) &&
    text.includes("failed to load resource") &&
    hasResponse(409)
  ) {
    st.consoleErrors.push("expected_project_conflict_409");
  } else if (
    source.startsWith("/api/v2/projects/") &&
    text.includes("failed to load resource") &&
    text.includes("503") &&
    hasResponse(503)
  ) {
    st.consoleErrors.push("expected_project_save_failure_503");
  } else if (
    st.expectedSignupConflictPending &&
    source === "/api/v2/auth/signup" &&
    text.includes("failed to load resource") &&
    st.authResponses.some(
      ([responsePath, status]) => responsePath === source && status === 409,
    )
  ) {
    st.consoleErrors.push("expected_signup_conflict_409");
    st.expectedSignupConflictPending = false;
  } else if (
    source === "/api/v2/utils/ocr" &&
    st.ocrMode === "failure" &&
    st.serviceResponses.some(
      ([responsePath, status]) => responsePath === source && status >= 500,
    )
  ) {
    st.consoleErrors.push("expected_ocr_failure_5xx");
  } else {
    let sourceClass: string;
    if (source.startsWith("/api/v2/projects/")) {
      sourceClass = "project-api";
    } else if (source === "/api/v2/projects") {
      sourceClass = "project-list-api";
    } else if (source.startsWith("/api/v2/")) {
      sourceClass = "resource-api";
    } else if (source.startsWith("/app/_app/")) {
      sourceClass = "app-asset";
    } else if (source.startsWith("/app/")) {
      sourceClass = "app-route";
    } else {
      sourceClass = "browser";
    }
    st.consoleErrors.push(`unexpected_console_error:${sourceClass}`);
    const statusMatch = /status of (\d+)/.exec(text);
    st.consoleErrorLocations.push(
      `${source || "/"}:${statusMatch ? statusMatch[1] : "unknown"}`,
    );
  }
}

/** python observe_page_error. */
export function observePageError(st: UiState, error: Error): void {
  st.pageErrors.push(true);
  st.pageErrorTypes.push(
    typeof error?.name === "string" && error.name ? error.name : "unknown",
  );
}
