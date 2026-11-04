import type { ErrorReportingInfo } from "./error-reporting";
import { t } from "./i18n.svelte";

export type Capability =
  | "signup"
  | "external_imports"
  | "ai"
  | "ocr"
  | "archive"
  | "media_upload"
  | "media_delete"
  | "import_preview"
  | "archive_state"
  | "account_actions"
  | "admin_actions";
export type ApiMethod = "GET" | "POST" | "PUT" | "PATCH" | "DELETE";

/** Where the avatar comes from: an upload, or the server-fetched Gravatar. */
export type AvatarSource = "upload" | "gravatar";

export interface SessionUser {
  id: number;
  email: string;
  is_admin: boolean;
  avatar_url: string | null;
  avatar_source?: AvatarSource | null;
}

export interface SessionData {
  user: SessionUser | null;
  csrf_token: string;
  capabilities: Record<Capability, boolean>;
  /** Optional error reporting; absent on servers that predate it. */
  error_reporting?: ErrorReportingInfo;
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
  current?: unknown;
}

export class ApiError extends Error {
  readonly code: string;
  readonly status: number;
  readonly fields?: Record<string, string>;
  readonly current?: unknown;
  readonly retryAfterSeconds?: number;

  constructor(
    message: string,
    status: number,
    code = "request_failed",
    fields?: Record<string, string>,
    current?: unknown,
    retryAfterSeconds?: number,
  ) {
    super(message);
    this.name = "ApiError";
    this.code = code;
    this.status = status;
    this.fields = fields;
    this.current = current;
    this.retryAfterSeconds = retryAfterSeconds;
  }
}

let unauthorizedHandler: (() => void) | undefined;
let authEpoch = 0;
let pendingApiMutations = 0;

export function setUnauthorizedHandler(handler: () => void): void {
  unauthorizedHandler = handler;
}

/** Invalidate pending requests before the current authentication state changes. */
export function advanceAuthEpoch(): void {
  authEpoch += 1;
}

export function hasPendingApiMutations(): boolean {
  return pendingApiMutations > 0;
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
  if ((options.method ?? "GET") === "GET") {
    return performApiRequest<T>(path, options);
  }

  pendingApiMutations += 1;
  try {
    return await performApiRequest<T>(path, options);
  } finally {
    pendingApiMutations -= 1;
  }
}

async function performApiRequest<T = unknown>(
  path: string,
  options: RequestOptions,
): Promise<T> {
  const method = options.method ?? "GET";
  const requestAuthEpoch = authEpoch;
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
  const retryAfterSeconds = parseRetryAfter(
    response.headers.get("retry-after"),
  );
  let payload: unknown;
  try {
    payload = await response.json();
  } catch {
    throw invalidResponse(response.status, retryAfterSeconds);
  }

  if (!response.ok) {
    const failure = readErrorPayload(payload);
    if (
      response.status === 401 &&
      path !== "/api/v2/auth/login" &&
      failure?.code !== "invalid_credentials" &&
      !options.signal?.aborted &&
      requestAuthEpoch === authEpoch
    )
      unauthorizedHandler?.();
    const code = failure?.code ?? "request_failed";
    throw new ApiError(
      localizedError(code),
      response.status,
      code,
      failure?.fields,
      failure?.current,
      retryAfterSeconds,
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
    ...(Object.hasOwn(candidate, "current")
      ? { current: candidate.current }
      : {}),
  };
}

const MAX_RETRY_AFTER_SECONDS = 86_400;
const HTTP_DATE_PATTERN =
  /^(?:Mon|Tue|Wed|Thu|Fri|Sat|Sun), \d{2} (?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec) \d{4} \d{2}:\d{2}:\d{2} GMT$/;

function parseRetryAfter(value: string | null): number | undefined {
  const header = value?.trim();
  if (!header) return;

  if (/^\d+$/.test(header)) {
    const seconds = Number(header);
    if (!Number.isSafeInteger(seconds)) return;
    return Math.min(seconds, MAX_RETRY_AFTER_SECONDS);
  }

  if (!HTTP_DATE_PATTERN.test(header)) return;
  const retryAt = Date.parse(header);
  if (!Number.isFinite(retryAt)) return;
  const retryDate = new Date(retryAt);
  if (retryDate.toUTCString() !== header) return;

  const seconds = (retryAt - Date.now()) / 1000;
  if (seconds < 0) return;
  return Math.min(Math.ceil(seconds), MAX_RETRY_AFTER_SECONDS);
}

function localizedError(code: string): string {
  const known: Record<string, string> = {
    invalid_credentials: t("The email or password is incorrect."),
    csrf_invalid: t(
      "Your session has expired. Refresh the page and try again.",
    ),
    csrf_failed: t(
      "Your session check failed. Refresh the page and try again.",
    ),
    csrf_missing: t(
      "Your session has expired. Refresh the page and try again.",
    ),
    session_expired: t("Your session expired. Sign in again."),
    unauthorized: t("Your session has expired. Sign in again."),
    forbidden: t("You do not have permission to do that."),
    validation_error: t("Check the highlighted fields and try again."),
    not_found: t("That item could not be found."),
    rate_limited: t("Too many attempts. Wait a moment and try again."),
    conflict: t(
      "This item changed in another window. Your unsaved changes are still here.",
    ),
    payload_too_large: t("This upload is larger than the allowed size."),
    invalid_json: t("The request could not be read. Refresh and try again."),
    feature_disabled: t("This feature is not enabled for this account."),
    invalid_multipart: t(
      "The uploaded form could not be read. Choose the file again.",
    ),
    invalid_image: t("This is not a valid image. Choose a different file."),
    media_unavailable: t("Media storage is unavailable. Try again later."),
    ocr_unavailable: t(
      "Text recognition is unavailable right now. You can enter the text manually.",
    ),
    ocr_timeout: t(
      "Text recognition took too long. Try again or enter the text manually.",
    ),
    ocr_failed: t(
      "Text recognition could not complete. You can enter the text manually.",
    ),
    invalid_language: t("Choose a supported language for text recognition."),
    invalid_src: t("This image is not available for text recognition."),
    import_unavailable: t(
      "Import preview is unavailable right now. Try again later.",
    ),
    import_timeout: t("The source took too long to import. Try again later."),
    import_fetch_failed: t(
      "The source could not be downloaded. Check the link and try again.",
    ),
    unsafe_url: t("This link cannot be imported for security reasons."),
    unsupported_document: t(
      "This document type is not supported. Try a web page, text, Markdown, PDF, or image.",
    ),
    invalid_document: t("This document could not be read. Try another file."),
    archive_unavailable: t("Archiving is unavailable right now."),
    image_import_unavailable: t(
      "External image importing is not available right now.",
    ),
    internal_error: t("The server could not complete this request. Try again."),
    reauth_required: t("Confirm it is you to continue."),
    oidc_identity_unlinked: t(
      "No Stricknani account is linked to this login yet. Sign in with your password, then link it in your account settings.",
    ),
    oidc_disabled: t("This sign-in method is not available."),
    identity_already_linked: t(
      "This login is already linked to a Stricknani account.",
    ),
    last_login_method: t(
      "This is your only way to sign in. Set up another sign-in method first.",
    ),
    invalid_oidc_flow: t(
      "That sign-in attempt expired or could not be verified. Start again.",
    ),
    oidc_unavailable: t(
      "The login provider is unavailable right now. Try again later or use your password.",
    ),
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
        typeof user.is_admin === "boolean" &&
        isAvatarUrl(user.avatar_url, user.id) &&
        isAvatarSource(user.avatar_source)))
  );
}

function isAvatarSource(value: unknown): boolean {
  return (
    value === undefined ||
    value === null ||
    value === "upload" ||
    value === "gravatar"
  );
}

function isAvatarUrl(value: unknown, userId: unknown): value is string | null {
  if (value === null) return true;
  if (
    typeof value !== "string" ||
    !Number.isSafeInteger(userId) ||
    typeof userId !== "number"
  )
    return false;
  const prefix = `/media/thumbnails/users/${userId}/`;
  if (!value.startsWith(prefix)) return false;
  const filename = value.slice(prefix.length);
  // The Gravatar is served by the same protected route under a fixed name.
  return (
    filename === "gravatar" || /^thumb_[A-Za-z0-9._-]+\.jpg$/.test(filename)
  );
}

function isCapabilities(value: unknown): value is SessionData["capabilities"] {
  if (typeof value !== "object" || value === null || Array.isArray(value))
    return false;
  const candidate = value as Record<string, unknown>;
  return [
    "signup",
    "external_imports",
    "ai",
    "ocr",
    "archive",
    "media_upload",
    "media_delete",
    "import_preview",
    "archive_state",
    "account_actions",
    "admin_actions",
  ].every((key) => typeof candidate[key] === "boolean");
}

function isStringMap(value: unknown): value is Record<string, string> {
  return (
    typeof value === "object" &&
    value !== null &&
    !Array.isArray(value) &&
    Object.values(value).every((item) => typeof item === "string")
  );
}

function invalidResponse(status = 200, retryAfterSeconds?: number): ApiError {
  return new ApiError(
    t("The server returned an unexpected response."),
    status,
    "invalid_response",
    undefined,
    undefined,
    retryAfterSeconds,
  );
}
