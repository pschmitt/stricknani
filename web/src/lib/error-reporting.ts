/**
 * Optional, operator-configured error reporting (Sentry).
 *
 * Nothing is loaded or sent unless the server says reporting is available and
 * the person has not turned it off. Events go through the server's same-origin
 * tunnel (never to a third-party origin), are scrubbed here and again on the
 * server, and carry no account contents, tokens, cookies or IP addresses.
 */
import { appVersion } from "./app-version";

/** What `/api/v2/session` and `/api/v2/meta` say about error reporting. */
export interface ErrorReportingInfo {
  available: boolean;
  /** Public DSN (no secret key). */
  dsn: string | null;
  /** Same-origin path of the server's tunnel. */
  tunnel_path: string | null;
  environment: string | null;
  /** The signed-in person's choice, or the instance default when signed out. */
  enabled: boolean;
}

export interface ReportingConfig {
  dsn: string;
  tunnel: string;
  environment: string;
}

const REDACTED = "[redacted]";

/** Every event carries this tag so one Sentry project can be filtered. */
export const COMPONENT_TAG = "component";
export const COMPONENT_WEB = "web";

/** `stricknani-web@<version>+<commit>`; the commit is left out when unknown. */
export function releaseName(version: string, revision: string): string {
  const commit = revision.trim();
  const base = `stricknani-web@${version.replace(/^v/, "")}`;
  return commit && commit !== "unknown" ? `${base}+${commit}` : base;
}

const buildRevision: string =
  typeof __STRICKNANI_GIT_REVISION__ === "string"
    ? __STRICKNANI_GIT_REVISION__
    : "";
const tokenLike = (segment: string, minLength: number): boolean =>
  segment.length >= minLength &&
  /^[A-Za-z0-9_-]+$/.test(segment) &&
  /\d/.test(segment);

/** Normalise an untrusted `error_reporting` payload. */
export function parseErrorReporting(value: unknown): ErrorReportingInfo | null {
  if (typeof value !== "object" || value === null) return null;
  const v = value as Record<string, unknown>;
  if (typeof v.available !== "boolean" || typeof v.enabled !== "boolean")
    return null;
  const text = (item: unknown) => (typeof item === "string" ? item : null);
  return {
    available: v.available,
    dsn: text(v.dsn),
    tunnel_path: text(v.tunnel_path),
    environment: text(v.environment),
    enabled: v.enabled,
  };
}

/**
 * Whether error reporting may run, and with which settings. Reporting needs an
 * instance DSN and tunnel, and the person must not have opted out.
 */
export function reportingConfig(
  info: ErrorReportingInfo | null | undefined,
): ReportingConfig | null {
  if (!info || !info.available || !info.enabled) return null;
  const { dsn, tunnel_path: tunnel } = info;
  if (!dsn || !tunnel) return null;
  if (!tunnel.startsWith("/") || tunnel.startsWith("//")) return null;
  return { dsn, tunnel, environment: info.environment ?? "production" };
}

// Escapes keep quote characters out of the literals (the i18n extractor lexes quotes).
const separators = /(\s+|[\x22\x27<>()[\]{},;\x60])/;
const separatorOnly = /^(\s+|[\x22\x27<>()[\]{},;\x60])$/;

/** Redact emails, bearer tokens, `secret=value` pairs and token-like words. */
export function scrubText(input: string): string {
  let previousWasBearer = false;
  return input
    .split(separators)
    .map((word) => {
      if (word === "" || separatorOnly.test(word)) return word;
      const redacted = previousWasBearer ? REDACTED : scrubWord(word);
      previousWasBearer = word.toLowerCase() === "bearer";
      return redacted;
    })
    .join("");
}

const sensitiveKey =
  /token|secret|password|passwd|key|auth|cookie|session|code|state|bearer|email|csrf/i;

function scrubWord(word: string): string {
  const at = word.indexOf("@");
  if (at > 0 && word.indexOf(".", at) > 0) return "[email]";
  const question = word.indexOf("?");
  if (question >= 0)
    return `${scrubWord(word.slice(0, question))}${question + 1 < word.length ? "?[redacted]" : ""}`;
  const hash = word.indexOf("#");
  if (hash >= 0) return scrubWord(word.slice(0, hash));
  const equals = word.indexOf("=");
  if (equals >= 0) {
    const key = word.slice(0, equals);
    const value = word.slice(equals + 1);
    return value && sensitiveKey.test(key)
      ? `${key}=${REDACTED}`
      : `${key}=${scrubWord(value)}`;
  }
  if (word.includes("/"))
    return word
      .split("/")
      .map((segment) => (tokenLike(segment, 24) ? REDACTED : segment))
      .join("/");
  return tokenLike(word, 32) ? REDACTED : word;
}

/** Drop credentials, query, fragment and token-like path segments. */
export function scrubUrl(input: string): string {
  try {
    const url = new URL(input);
    url.username = "";
    url.password = "";
    url.search = "";
    url.hash = "";
    url.pathname = url.pathname
      .split("/")
      .map((segment) => (tokenLike(segment, 24) ? REDACTED : segment))
      .join("/");
    return url.toString();
  } catch {
    return scrubText(input);
  }
}

interface Scrubbable {
  server_name?: unknown;
  user?: unknown;
  extra?: unknown;
  message?: string;
  transaction?: string;
  request?: {
    url?: string;
    headers?: unknown;
    cookies?: unknown;
    data?: unknown;
    query_string?: unknown;
  };
  exception?: { values?: Array<{ value?: string }> };
  breadcrumbs?: Array<{ message?: string; data?: unknown }>;
  tags?: Record<string, unknown>;
}

/**
 * Remove everything identifying from an event: the user, request details
 * except a scrubbed URL, extra data, and secrets in free text. Works in place
 * and returns the event.
 */
export function scrubEvent<T extends Scrubbable>(event: T): T {
  delete event.server_name;
  delete event.user;
  delete event.extra;
  if (event.message) event.message = scrubText(event.message);
  if (event.transaction) event.transaction = scrubUrl(event.transaction);
  const request = event.request;
  if (request) {
    delete request.headers;
    delete request.cookies;
    delete request.data;
    delete request.query_string;
    if (request.url) request.url = scrubUrl(request.url);
  }
  for (const exception of event.exception?.values ?? [])
    if (exception.value) exception.value = scrubText(exception.value);
  for (const crumb of event.breadcrumbs ?? []) {
    if (crumb.message) crumb.message = scrubText(crumb.message);
    delete crumb.data;
  }
  if (event.tags)
    for (const [key, value] of Object.entries(event.tags))
      if (typeof value === "string") event.tags[key] = scrubText(value);
  return event;
}

type SentryModule = typeof import("@sentry/svelte");

let sentry: SentryModule | null = null;
let activeKey: string | null = null;
let allowed = false;
let generation = 0;

/**
 * Start or stop reporting to match the server's answer. Safe to call whenever
 * the session changes: it does nothing unless the effective state changes.
 * The SDK is loaded lazily, so people who report nothing never download it.
 */
export async function syncErrorReporting(
  info: ErrorReportingInfo | null | undefined,
): Promise<void> {
  const config = reportingConfig(info);
  const key = config ? JSON.stringify(config) : null;
  if (key === activeKey) return;
  const mine = ++generation;
  activeKey = key;
  allowed = false;
  if (!config) {
    await sentry?.close(0);
    return;
  }
  const module = await import("@sentry/svelte");
  // A newer call (for example an opt-out) won while the SDK was loading.
  if (mine !== generation) return;
  sentry = module;
  allowed = true;
  module.init({
    dsn: config.dsn,
    tunnel: config.tunnel,
    environment: config.environment,
    release: releaseName(appVersion, buildRevision),
    initialScope: { tags: { [COMPONENT_TAG]: COMPONENT_WEB } },
    // Errors only: no tracing, no replay, no sessions, no client reports.
    sendClientReports: false,
    defaultIntegrations: false,
    integrations: [
      module.globalHandlersIntegration(),
      module.linkedErrorsIntegration(),
      module.dedupeIntegration(),
      module.httpContextIntegration(),
      module.functionToStringIntegration(),
    ],
    beforeBreadcrumb: () => null,
    beforeSend: (event) => (allowed ? scrubEvent(event) : null),
  });
}

/** Test helper: forget what was started. */
export function resetErrorReportingForTests(): void {
  sentry = null;
  activeKey = null;
  allowed = false;
  generation = 0;
}
