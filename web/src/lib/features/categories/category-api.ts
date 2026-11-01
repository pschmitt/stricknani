import { ApiError, apiRequest, type SessionData } from "#lib/api.ts";
import { t } from "#lib/i18n.svelte.ts";

export interface Category {
  id: number;
  name: string;
}

const isRecord = (value: unknown): value is Record<string, unknown> =>
  typeof value === "object" && value !== null && !Array.isArray(value);

function parseCategory(value: unknown): Category {
  if (
    !isRecord(value) ||
    !Number.isSafeInteger(value.id) ||
    Number(value.id) <= 0 ||
    typeof value.name !== "string"
  )
    throw new ApiError(
      t("The server returned an unexpected response."),
      200,
      "invalid_response",
    );
  return { id: Number(value.id), name: value.name };
}

export async function listCategories(
  signal?: AbortSignal,
): Promise<Category[]> {
  const payload: unknown = await apiRequest("/api/v2/categories", { signal });
  if (!isRecord(payload) || !Array.isArray(payload.data))
    throw invalidResponse();
  return payload.data.map(parseCategory);
}

export async function createCategory(
  name: string,
  session: SessionData,
): Promise<Category> {
  const payload: unknown = await apiRequest("/api/v2/categories", {
    method: "POST",
    csrfToken: session.csrf_token,
    body: { name },
  });
  if (!isRecord(payload)) throw invalidResponse();
  return parseCategory(payload.data);
}

export async function renameCategory(
  id: number,
  name: string,
  session: SessionData,
): Promise<Category> {
  const payload: unknown = await apiRequest(`/api/v2/categories/${id}`, {
    method: "PUT",
    csrfToken: session.csrf_token,
    body: { name },
  });
  if (!isRecord(payload)) throw invalidResponse();
  return parseCategory(payload.data);
}

export async function deleteCategory(
  id: number,
  session: SessionData,
): Promise<void> {
  await apiRequest(`/api/v2/categories/${id}`, {
    method: "DELETE",
    csrfToken: session.csrf_token,
  });
}

function invalidResponse(): ApiError {
  return new ApiError(
    t("The server returned an unexpected response."),
    200,
    "invalid_response",
  );
}
