import { beforeEach, describe, expect, it, vi } from "vitest";

const sentry = vi.hoisted(() => ({
  init: vi.fn(),
  close: vi.fn(async () => true),
  globalHandlersIntegration: vi.fn(() => ({ name: "globalHandlers" })),
  linkedErrorsIntegration: vi.fn(() => ({ name: "linkedErrors" })),
  dedupeIntegration: vi.fn(() => ({ name: "dedupe" })),
  httpContextIntegration: vi.fn(() => ({ name: "httpContext" })),
  functionToStringIntegration: vi.fn(() => ({ name: "functionToString" })),
}));
vi.mock("@sentry/svelte", () => sentry);

import {
  parseErrorReporting,
  releaseName,
  reportingConfig,
  secureTransport,
  resetErrorReportingForTests,
  scrubEvent,
  scrubText,
  scrubUrl,
  syncErrorReporting,
  type ErrorReportingInfo,
} from "./error-reporting";

const info = (patch: Partial<ErrorReportingInfo> = {}): ErrorReportingInfo => ({
  available: true,
  dsn: "https://pub@sentry.example.test/7",
  tunnel_path: "/api/v2/telemetry/sentry",
  environment: "production",
  enabled: true,
  ...patch,
});

beforeEach(() => {
  vi.clearAllMocks();
  resetErrorReportingForTests();
});

describe("error reporting gating", () => {
  it("needs an available instance, a DSN, a same-origin tunnel and no opt-out", () => {
    expect(reportingConfig(info())).toEqual({
      dsn: "https://pub@sentry.example.test/7",
      tunnel: "/api/v2/telemetry/sentry",
      environment: "production",
    });
    expect(reportingConfig(null)).toBeNull();
    expect(reportingConfig(info({ available: false }))).toBeNull();
    expect(reportingConfig(info({ enabled: false }))).toBeNull();
    expect(reportingConfig(info({ dsn: null }))).toBeNull();
    expect(reportingConfig(info({ tunnel_path: null }))).toBeNull();
    expect(
      reportingConfig(info({ tunnel_path: "https://evil.test/x" })),
    ).toBeNull();
    expect(reportingConfig(info({ tunnel_path: "//evil.test/x" }))).toBeNull();
  });

  it("reports only from HTTPS pages, or plain HTTP on this machine", () => {
    const https = { protocol: "https:", hostname: "wolle.example.test" };
    expect(reportingConfig(info(), https)).not.toBeNull();
    for (const hostname of ["localhost", "127.0.0.1", "[::1]"])
      expect(
        reportingConfig(info(), { protocol: "http:", hostname }),
      ).not.toBeNull();
    for (const page of [
      { protocol: "http:", hostname: "wolle.example.test" },
      { protocol: "http:", hostname: "192.168.1.20" },
      { protocol: "ftp:", hostname: "localhost" },
    ]) {
      expect(secureTransport(page)).toBe(false);
      expect(reportingConfig(info(), page)).toBeNull();
    }
  });

  it("ignores malformed server payloads", () => {
    expect(parseErrorReporting(undefined)).toBeNull();
    expect(parseErrorReporting({ available: "yes", enabled: true })).toBeNull();
    expect(
      parseErrorReporting({ available: false, enabled: true, dsn: 4 }),
    ).toEqual({
      available: false,
      dsn: null,
      tunnel_path: null,
      environment: null,
      enabled: true,
    });
  });

  it("does not load or initialise the SDK when reporting is off", async () => {
    await syncErrorReporting(undefined);
    await syncErrorReporting(info({ available: false }));
    await syncErrorReporting(info({ enabled: false }));
    expect(sentry.init).not.toHaveBeenCalled();
  });

  it("initialises once through the tunnel, without tracing or replay", async () => {
    await syncErrorReporting(info());
    await syncErrorReporting(info());
    expect(sentry.init).toHaveBeenCalledTimes(1);
    const options = sentry.init.mock.calls[0]?.[0];
    expect(options).toMatchObject({
      dsn: "https://pub@sentry.example.test/7",
      tunnel: "/api/v2/telemetry/sentry",
      defaultIntegrations: false,
      sendClientReports: false,
    });
    expect(options.tracesSampleRate).toBeUndefined();
    expect(options.replaysSessionSampleRate).toBeUndefined();
    expect(options.beforeBreadcrumb()).toBeNull();
  });

  it("tags every event as web and names the release with the commit", async () => {
    await syncErrorReporting(info());
    const options = sentry.init.mock.calls[0]?.[0];
    expect(options.initialScope).toEqual({ tags: { component: "web" } });
    expect(options.release).toMatch(/^stricknani-web@\d+\.\d+\.\d+/);
    expect(releaseName("v1.2.3", "abc1234")).toBe(
      "stricknani-web@1.2.3+abc1234",
    );
    expect(releaseName("v1.2.3", "")).toBe("stricknani-web@1.2.3");
  });

  it("uses the DSN the server hands out, which is the backend DSN unless a frontend DSN is set", async () => {
    await syncErrorReporting(
      info({ dsn: "https://front@sentry.example.test/9" }),
    );
    expect(sentry.init.mock.calls[0]?.[0].dsn).toBe(
      "https://front@sentry.example.test/9",
    );
  });

  it("stops at once when the person opts out, and drops late events", async () => {
    await syncErrorReporting(info());
    const options = sentry.init.mock.calls[0]?.[0];
    expect(options.beforeSend({ message: "boom" })).toMatchObject({
      message: "boom",
    });
    await syncErrorReporting(info({ enabled: false }));
    expect(sentry.close).toHaveBeenCalledTimes(1);
    expect(options.beforeSend({ message: "late" })).toBeNull();
    await syncErrorReporting(info());
    expect(sentry.init).toHaveBeenCalledTimes(2);
  });
});

describe("error report scrubbing", () => {
  it("redacts emails, bearer tokens, secrets and token-like words", () => {
    expect(scrubText("bob@example.test sent Bearer abc123def456")).toBe(
      "[email] sent Bearer [redacted]",
    );
    expect(scrubText("GET /x?token=abc&y=1 failed")).toBe(
      "GET /x?[redacted] failed",
    );
    expect(scrubText("password=hunter2")).toBe("password=[redacted]");
    expect(scrubText("id 0123456789abcdef0123456789abcdef0123")).toBe(
      "id [redacted]",
    );
    expect(scrubText("Cannot read properties of undefined")).toBe(
      "Cannot read properties of undefined",
    );
  });

  it("strips credentials, query, fragment and token path segments", () => {
    expect(
      scrubUrl(
        "https://u:p@stricknani.test/app/signup/AbCdEf0123456789AbCdEf0123456789?token=x#y",
      ),
    ).toBe("https://stricknani.test/app/signup/[redacted]");
  });

  it("removes the user, request details and extras from an event", () => {
    const event = scrubEvent({
      message: "mail bob@example.test failed",
      server_name: "host",
      user: { id: "1", email: "bob@example.test", ip_address: "{{auto}}" },
      extra: { body: "secret" },
      request: {
        url: "https://s.test/app/yarns?token=abc",
        headers: { cookie: "a=b" },
        cookies: { a: "b" },
        data: "x",
        query_string: "token=abc",
      },
      exception: { values: [{ value: "token=abc broke" }] },
      breadcrumbs: [{ message: "click bob@example.test", data: { a: 1 } }],
    });
    expect(event).toEqual({
      message: "mail [email] failed",
      request: { url: "https://s.test/app/yarns" },
      exception: { values: [{ value: "token=[redacted] broke" }] },
      breadcrumbs: [{ message: "click [email]" }],
    });
  });
});
