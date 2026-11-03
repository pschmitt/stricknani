import { ApiError, apiRequest } from "#lib/api.ts";
import { t, type Locale } from "#lib/i18n.svelte.ts";

/**
 * Client for the optional OIDC login and identity-linking API
 * (docs/oidc-v2-contract.md). Nothing here stores or logs provider codes or
 * tokens: the browser only ever sees the provider authorization URL.
 */

export interface OidcProvider {
  id: string;
  name: string;
}

export interface AuthOptions {
  password: boolean;
  oidc: OidcProvider[];
}

export interface LinkedIdentity {
  id: number;
  provider: string;
  subject_hint: string;
  email: string | null;
  linked_at: string;
  can_unlink: boolean;
}

export interface IdentityList {
  identities: LinkedIdentity[];
  passwordEnabled: boolean;
}

export interface OidcStart {
  authorizationUrl: string;
  expiresAt: string | null;
}

export type ReauthAction =
  | "link_identity"
  | "unlink_identity"
  | "admin_unlink_identity"
  | "change_password"
  | "create_api_token"
  | "revoke_api_token";

export interface ReauthTarget {
  provider?: string;
  identity_id?: number;
  user_id?: number;
}

export interface ReauthRequest {
  action: ReauthAction;
  target?: ReauthTarget;
}

export type OidcLoginError = "unlinked" | "failed";

export const NO_AUTH_OPTIONS: AuthOptions = { password: true, oidc: [] };

const record = (value: unknown): value is Record<string, unknown> =>
  typeof value === "object" && value !== null && !Array.isArray(value);

const validId = (value: unknown): value is number =>
  Number.isSafeInteger(value) && Number(value) > 0;

const PROVIDER_ID = /^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$/;

function invalidResponse(): ApiError {
  return new ApiError(
    t("The server returned an unexpected response."),
    200,
    "invalid_response",
  );
}

/**
 * Read the additive `data.auth` fragment of `GET /api/v2/meta`. Anything
 * missing or malformed degrades to "no providers" so the sign-in page keeps
 * its password-only appearance.
 */
export function parseAuthOptions(payload: unknown): AuthOptions {
  if (!record(payload) || !record(payload.data)) return NO_AUTH_OPTIONS;
  const auth = payload.data.auth;
  if (!record(auth)) return NO_AUTH_OPTIONS;
  const seen = new Set<string>();
  const oidc: OidcProvider[] = [];
  if (Array.isArray(auth.oidc)) {
    for (const entry of auth.oidc) {
      if (
        !record(entry) ||
        typeof entry.id !== "string" ||
        !PROVIDER_ID.test(entry.id) ||
        typeof entry.name !== "string"
      )
        continue;
      const name = entry.name.trim();
      if (!name || name.length > 80 || seen.has(entry.id)) continue;
      seen.add(entry.id);
      oidc.push({ id: entry.id, name });
    }
  }
  return { password: auth.password !== false, oidc };
}

export async function getAuthOptions(
  signal?: AbortSignal,
): Promise<AuthOptions> {
  try {
    return parseAuthOptions(await apiRequest("/api/v2/meta", { signal }));
  } catch (error) {
    if (error instanceof DOMException && error.name === "AbortError")
      throw error;
    return NO_AUTH_OPTIONS;
  }
}

/** The provider authorization URL must be https (http only for local dev hosts). */
export function parseStartResponse(payload: unknown): OidcStart {
  if (!record(payload) || !record(payload.data)) throw invalidResponse();
  const raw = payload.data.authorization_url;
  if (typeof raw !== "string") throw invalidResponse();
  let url: URL;
  try {
    url = new URL(raw);
  } catch {
    throw invalidResponse();
  }
  const local = ["localhost", "127.0.0.1", "[::1]"].includes(url.hostname);
  if (
    (url.protocol !== "https:" && !(url.protocol === "http:" && local)) ||
    url.username ||
    url.password
  )
    throw invalidResponse();
  const expires = payload.data.expires_at;
  return {
    authorizationUrl: url.toString(),
    expiresAt: typeof expires === "string" ? expires : null,
  };
}

export function parseIdentity(value: unknown): LinkedIdentity {
  if (
    !record(value) ||
    !validId(value.id) ||
    typeof value.provider !== "string" ||
    typeof value.subject_hint !== "string" ||
    typeof value.linked_at !== "string" ||
    typeof value.can_unlink !== "boolean" ||
    !(value.email === null || typeof value.email === "string")
  )
    throw invalidResponse();
  return {
    id: value.id,
    provider: value.provider,
    subject_hint: value.subject_hint,
    email: value.email,
    linked_at: value.linked_at,
    can_unlink: value.can_unlink,
  };
}

export function parseIdentityList(payload: unknown): IdentityList {
  if (!record(payload) || !Array.isArray(payload.data)) throw invalidResponse();
  const meta = record(payload.meta) ? payload.meta : {};
  return {
    identities: payload.data.map(parseIdentity),
    passwordEnabled: meta.password_enabled !== false,
  };
}

function parseReauthDone(payload: unknown): void {
  if (
    !record(payload) ||
    !record(payload.data) ||
    payload.data.reauthenticated !== true
  )
    throw invalidResponse();
}

/** Read `?oidc_error=` from a login or account URL; unknown values are generic failures. */
export function readOidcError(search: string): OidcLoginError | null {
  const value = new URLSearchParams(search).get("oidc_error");
  if (value === null) return null;
  return value === "unlinked" ? "unlinked" : "failed";
}

export function readOidcLinked(search: string): boolean {
  return new URLSearchParams(search).get("oidc") === "linked";
}

/** The URL without the OIDC result parameters, or null when nothing needs cleaning. */
export function cleanOidcQuery(href: string): string | null {
  const url = new URL(href, "https://stricknani.invalid");
  const had =
    url.searchParams.has("oidc_error") || url.searchParams.has("oidc");
  if (!had) return null;
  url.searchParams.delete("oidc_error");
  url.searchParams.delete("oidc");
  return `${url.pathname}${url.search}${url.hash}`;
}

export function oidcLoginErrorMessage(
  error: OidcLoginError,
  locale: Locale,
  providerName = "Authelia",
): string {
  return error === "unlinked"
    ? t(
        "No Stricknani account is linked to this {name} login yet. Sign in with your password, then link {name} in your account settings.",
        locale,
      ).replaceAll("{name}", providerName)
    : t("Signing in with {name} did not work. Try again.", locale).replaceAll(
        "{name}",
        providerName,
      );
}

export async function startOidcLogin(
  provider: string,
  csrfToken: string,
  returnTo?: string,
): Promise<OidcStart> {
  return parseStartResponse(
    await apiRequest("/api/v2/auth/oidc/start", {
      method: "POST",
      csrfToken,
      body: {
        provider,
        client: "browser",
        ...(returnTo ? { return_to: returnTo } : {}),
      },
    }),
  );
}

export async function listIdentities(
  signal?: AbortSignal,
): Promise<IdentityList> {
  return parseIdentityList(
    await apiRequest("/api/v2/account/identities", { signal }),
  );
}

export async function reauthWithPassword(
  password: string,
  request: ReauthRequest,
  csrfToken: string,
): Promise<void> {
  parseReauthDone(
    await apiRequest("/api/v2/account/reauth", {
      method: "POST",
      csrfToken,
      body: {
        password,
        action: request.action,
        ...(request.target ? { target: request.target } : {}),
      },
    }),
  );
}

export async function startOidcReauth(
  provider: string,
  request: ReauthRequest,
  csrfToken: string,
): Promise<OidcStart> {
  return parseStartResponse(
    await apiRequest("/api/v2/account/reauth/oidc/start", {
      method: "POST",
      csrfToken,
      body: {
        provider,
        action: request.action,
        ...(request.target ? { target: request.target } : {}),
        client: "browser",
      },
    }),
  );
}

export async function startLinkIdentity(
  provider: string,
  csrfToken: string,
): Promise<OidcStart> {
  return parseStartResponse(
    await apiRequest("/api/v2/account/identities/link", {
      method: "POST",
      csrfToken,
      body: { provider, client: "browser" },
    }),
  );
}

export async function unlinkIdentity(
  identityId: number,
  csrfToken: string,
): Promise<void> {
  await apiRequest(`/api/v2/account/identities/${identityId}`, {
    method: "DELETE",
    csrfToken,
  });
}

export async function listAdminUserIdentities(
  userId: number,
  signal?: AbortSignal,
): Promise<IdentityList> {
  return parseIdentityList(
    await apiRequest(`/api/v2/admin/users/${userId}/identities`, { signal }),
  );
}

export async function adminUnlinkIdentity(
  userId: number,
  identityId: number,
  csrfToken: string,
): Promise<void> {
  await apiRequest(`/api/v2/admin/users/${userId}/identities/${identityId}`, {
    method: "DELETE",
    csrfToken,
  });
}

export function isReauthRequired(error: unknown): boolean {
  return (
    error instanceof ApiError &&
    error.status === 403 &&
    error.code === "reauth_required"
  );
}

export function formatLinkedDate(value: string, locale: Locale): string {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return new Intl.DateTimeFormat(locale, { dateStyle: "medium" }).format(date);
}

/** Assign the browser to the provider; the URL is validated by parseStartResponse. */
export function navigateToProvider(start: OidcStart): void {
  window.location.assign(start.authorizationUrl);
}
