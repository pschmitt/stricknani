import { ApiError, apiRequest } from "#lib/api.ts";
import { t } from "#lib/i18n.svelte.ts";

export interface CreatedAccount {
  id: number;
  email: string;
  is_admin: false;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

export async function createAccount(
  email: string,
  password: string,
  csrfToken: string,
  signal?: AbortSignal,
): Promise<CreatedAccount> {
  const payload: unknown = await apiRequest("/api/v2/auth/signup", {
    method: "POST",
    csrfToken,
    body: { email, password },
    signal,
  });
  if (!isRecord(payload) || !isRecord(payload.data)) throw invalidResponse();

  const account = payload.data;
  if (
    !Number.isSafeInteger(account.id) ||
    Number(account.id) < 1 ||
    account.email !== email.trim().toLowerCase() ||
    account.is_admin !== false
  )
    throw invalidResponse();

  return {
    id: Number(account.id),
    email: account.email,
    is_admin: false,
  };
}

function invalidResponse(): ApiError {
  return new ApiError(
    t("The server returned an unexpected response."),
    201,
    "invalid_response",
  );
}
