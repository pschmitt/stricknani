import { t } from "./i18n.svelte";

export type Capability =
  "signup" | "external_imports" | "ai" | "ocr" | "archive";
export type ApiMethod = "GET" | "POST" | "PUT" | "PATCH" | "DELETE";

export interface SessionUser {
  id: number;
  email: string;
  is_admin: boolean;
}

export interface SessionData {
  user: SessionUser | null;
  csrf_token: string;
  capabilities: Record<Capability, boolean>;
  locale: "en" | "de";
}

export interface RequestOptions {
  method?: ApiMethod;
  csrfToken?: string;
  body?: unknown;
  signal?: AbortSignal;
}

interface DataEnvelope<T> {
  data: T;
}

interface ErrorPayload {
  code: string;
  message: string;
  fields?: Record<string, string>;
}

export class ApiError extends Error {
  readonly code: string;
  readonly status: number;
  readonly fields?: Record<string, string>;

  constructor(
    message: string,
    status: number,
    code = "request_failed",
    fields?: Record<string, string>,
  ) {
    super(message);
    this.name = "ApiError";
    this.code = code;
    this.status = status;
    this.fields = fields;
  }
}

let unauthorizedHandler: (() => void) | undefined;

export function setUnauthorizedHandler(handler: () => void): void {
  unauthorizedHandler = handler;
}

export async function getSession(signal?: AbortSignal): Promise<SessionData> {
  const response = await apiRequest<DataEnvelope<unknown>>("/api/v2/session", {
    signal,
  });
  if (!isDataEnvelope(response) || !isSessionData(response.data))
    throw invalidResponse();
  return response.data;
}

export async function login(
  email: string,
  password: string,
  csrfToken: string,
): Promise<SessionData> {
  const response = await apiRequest<DataEnvelope<unknown>>(
    "/api/v2/auth/login",
    {
      method: "POST",
      csrfToken,
      body: { email, password },
    },
  );
  if (!isDataEnvelope(response) || !isSessionData(response.data))
    throw invalidResponse();
  return response.data;
}

export async function logout(csrfToken: string): Promise<void> {
  await apiRequest<void>("/api/v2/auth/logout", {
    method: "POST",
    csrfToken,
    body: {},
  });
}

/** Same-origin API client. Mutations are never retried automatically. */
export async function apiRequest<T = unknown>(
  path: string,
  options: RequestOptions = {},
): Promise<T> {
  const method = options.method ?? "GET";
  const headers = new Headers({ accept: "application/json" });
  let body: BodyInit | undefined;
  if (options.body instanceof FormData) {
    body = options.body;
  } else if (options.body !== undefined) {
    headers.set("content-type", "application/json");
    body = JSON.stringify(options.body);
  }
  if (options.csrfToken) headers.set("x-csrf-token", options.csrfToken);

  let response: Response;
  try {
    response = await fetch(path, {
      method,
      credentials: "same-origin",
      headers,
      signal: options.signal,
      body,
    });
  } catch (error) {
    if (error instanceof DOMException && error.name === "AbortError")
      throw error;
    throw new ApiError(
      t(
        "The server could not be reached. Check your connection and try again.",
      ),
      0,
      "network_error",
    );
  }

  if (response.status === 204) return undefined as T;
  let payload: unknown;
  try {
    payload = await response.json();
  } catch {
    throw invalidResponse(response.status);
  }

  if (!response.ok) {
    const failure = readErrorPayload(payload);
    if (response.status === 401 && path !== "/api/v2/auth/login")
      unauthorizedHandler?.();
    const code = failure?.code ?? "request_failed";
    throw new ApiError(
      localizedError(code),
      response.status,
      code,
      failure?.fields,
    );
  }
  return payload as T;
}

function isDataEnvelope(value: unknown): value is DataEnvelope<unknown> {
  return (
    typeof value === "object" &&
    value !== null &&
    !Array.isArray(value) &&
    "data" in value
  );
}

function readErrorPayload(value: unknown): ErrorPayload | undefined {
  if (
    typeof value !== "object" ||
    value === null ||
    Array.isArray(value) ||
    !("error" in value)
  )
    return;
  const error = value.error;
  if (typeof error !== "object" || error === null || Array.isArray(error))
    return;
  const candidate = error as Record<string, unknown>;
  if (
    typeof candidate.code !== "string" ||
    typeof candidate.message !== "string"
  )
    return;
  const fields = candidate.fields;
  return {
    code: candidate.code,
    message: candidate.message,
    ...(isStringMap(fields) ? { fields } : {}),
  };
}

function localizedError(code: string): string {
  const known: Record<string, string> = {
    invalid_credentials: t("The email or password is incorrect."),
    csrf_invalid: t(
      "Your session has expired. Refresh the page and try again.",
    ),
    csrf_missing: t(
      "Your session has expired. Refresh the page and try again.",
    ),
    unauthorized: t("Your session has expired. Sign in again."),
    forbidden: t("You do not have permission to do that."),
    validation_error: t("Check the highlighted fields and try again."),
    not_found: t("That item could not be found."),
    rate_limited: t("Too many attempts. Wait a moment and try again."),
  };
  return known[code] ?? t("The request could not be completed.");
}

function isSessionData(value: unknown): value is SessionData {
  if (typeof value !== "object" || value === null || Array.isArray(value))
    return false;
  const candidate = value as Partial<SessionData>;
  const user = candidate.user;
  return (
    typeof candidate.csrf_token === "string" &&
    (candidate.locale === "en" || candidate.locale === "de") &&
    isCapabilities(candidate.capabilities) &&
    (user === null ||
      (typeof user === "object" &&
        Number.isSafeInteger(user.id) &&
        typeof user.email === "string" &&
        typeof user.is_admin === "boolean"))
  );
}

function isCapabilities(value: unknown): value is SessionData["capabilities"] {
  if (typeof value !== "object" || value === null || Array.isArray(value))
    return false;
  const candidate = value as Record<string, unknown>;
  return ["signup", "external_imports", "ai", "ocr", "archive"].every(
    (key) => typeof candidate[key] === "boolean",
  );
}

function isStringMap(value: unknown): value is Record<string, string> {
  return (
    typeof value === "object" &&
    value !== null &&
    !Array.isArray(value) &&
    Object.values(value).every((item) => typeof item === "string")
  );
}

function invalidResponse(status = 200): ApiError {
  return new ApiError(
    t("The server returned an unexpected response."),
    status,
    "invalid_response",
  );
}
