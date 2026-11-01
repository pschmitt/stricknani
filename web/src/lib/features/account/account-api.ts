import { ApiError, apiRequest, type SessionData } from "#lib/api.ts";
import { t } from "#lib/i18n.svelte.ts";

export interface AccountProfile {
  id: number;
  email: string;
  is_admin: boolean;
  avatar_url: string | null;
}

export interface ApiToken {
  id: number;
  name: string;
  created_at: string;
  expires_at: string | null;
}

export interface CreatedApiToken extends ApiToken {
  token: string;
}

const record = (value: unknown): value is Record<string, unknown> =>
  typeof value === "object" && value !== null && !Array.isArray(value);

const validId = (value: unknown): value is number =>
  Number.isSafeInteger(value) && Number(value) > 0;

const nullableString = (value: unknown): value is string | null =>
  value === null || typeof value === "string";

function invalidResponse(): ApiError {
  return new ApiError(
    t("The server returned an unexpected response."),
    200,
    "invalid_response",
  );
}

function parseProfile(value: unknown): AccountProfile {
  if (
    !record(value) ||
    !validId(value.id) ||
    typeof value.email !== "string" ||
    typeof value.is_admin !== "boolean" ||
    !nullableString(value.avatar_url)
  )
    throw invalidResponse();
  return {
    id: value.id,
    email: value.email,
    is_admin: value.is_admin,
    avatar_url: value.avatar_url,
  };
}

function parseToken(value: unknown): ApiToken {
  const expiry = record(value) ? value.expires_at : undefined;
  if (
    !record(value) ||
    !validId(value.id) ||
    typeof value.name !== "string" ||
    typeof value.created_at !== "string" ||
    (expiry !== undefined && !nullableString(expiry))
  )
    throw invalidResponse();
  return {
    id: value.id,
    name: value.name,
    created_at: value.created_at,
    expires_at: expiry ?? null,
  };
}

export async function getAccount(
  signal?: AbortSignal,
): Promise<AccountProfile> {
  const payload: unknown = await apiRequest("/api/v2/account", { signal });
  if (!record(payload)) throw invalidResponse();
  return parseProfile(payload.data);
}

export async function listApiTokens(signal?: AbortSignal): Promise<ApiToken[]> {
  const payload: unknown = await apiRequest("/api/v2/account/api-tokens", {
    signal,
  });
  if (!record(payload) || !Array.isArray(payload.data)) throw invalidResponse();
  return payload.data.map(parseToken);
}

export async function createApiToken(
  name: string,
  currentSession: SessionData,
): Promise<CreatedApiToken> {
  const payload: unknown = await apiRequest("/api/v2/account/api-tokens", {
    method: "POST",
    csrfToken: currentSession.csrf_token,
    body: { name },
  });
  if (!record(payload) || !record(payload.data)) throw invalidResponse();
  const token = parseToken(payload.data);
  if (typeof payload.data.token !== "string" || !payload.data.token)
    throw invalidResponse();
  return { ...token, token: payload.data.token };
}

export async function revokeApiToken(
  id: number,
  currentSession: SessionData,
): Promise<void> {
  await apiRequest(`/api/v2/account/api-tokens/${id}`, {
    method: "DELETE",
    csrfToken: currentSession.csrf_token,
  });
}

export async function changePassword(
  currentPassword: string,
  newPassword: string,
  currentSession: SessionData,
): Promise<void> {
  await apiRequest<void>("/api/v2/account/password", {
    method: "PUT",
    csrfToken: currentSession.csrf_token,
    body: { current_password: currentPassword, new_password: newPassword },
  });
}

export async function uploadAvatar(
  file: File,
  currentSession: SessionData,
): Promise<string> {
  const form = new FormData();
  form.append("file", file, file.name);
  const payload: unknown = await apiRequest("/api/v2/account/avatar", {
    method: "POST",
    csrfToken: currentSession.csrf_token,
    body: form,
  });
  if (!record(payload) || !record(payload.data)) throw invalidResponse();
  if (typeof payload.data.avatar_url !== "string") throw invalidResponse();
  return payload.data.avatar_url;
}

export async function deleteAvatar(currentSession: SessionData): Promise<void> {
  await apiRequest("/api/v2/account/avatar", {
    method: "DELETE",
    csrfToken: currentSession.csrf_token,
  });
}
