import { ApiError, apiRequest, type SessionData } from "#lib/api.ts";
import { parseAccess, type ResourceAccess } from "#lib/access.ts";
import { t } from "#lib/i18n.svelte.ts";
import type { ResourceOwner } from "#lib/owner.ts";

export type ShareKind = "projects" | "yarns";
export type ShareRole = "viewer" | "editor";

/** A person as the sharing API shows them: never an email address. */
export interface Person {
  id: number;
  name: string;
  avatar_url: string | null;
}

export interface Share {
  user: Person;
  role: ShareRole;
  created_at: string;
  updated_at: string;
}

export interface ShareList {
  owner: Person;
  shares: Share[];
}

export interface PeoplePage {
  items: Person[];
  page: number;
  has_more: boolean;
}

export interface YarnCandidate {
  id: number;
  name: string;
  brand: string | null;
  colorway: string | null;
  weight_category: string | null;
  linked: boolean;
}

export interface YarnCandidatePage {
  items: YarnCandidate[];
  page: number;
  has_more: boolean;
}

export interface TransferResult {
  new_owner: Person;
  access: ResourceAccess;
  moved_yarn_ids: number[];
}

export const SEARCH_QUERY_LIMIT = 64;

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function positive(value: unknown): value is number {
  return Number.isSafeInteger(value) && typeof value === "number" && value > 0;
}

function nullableString(value: unknown): value is string | null {
  return value === null || typeof value === "string";
}

function invalidResponse(): ApiError {
  return new ApiError(
    t("The server returned an unexpected response."),
    500,
    "invalid_response",
  );
}

export function parsePerson(value: unknown): Person {
  if (!isRecord(value) || !positive(value.id) || typeof value.name !== "string")
    throw invalidResponse();
  const avatar = value.avatar_url;
  return {
    id: value.id,
    name: value.name.trim() || "?",
    avatar_url:
      typeof avatar === "string" && avatar.startsWith("/") ? avatar : null,
  };
}

function parseRole(value: unknown): ShareRole {
  if (value !== "viewer" && value !== "editor") throw invalidResponse();
  return value;
}

export function parseShare(value: unknown): Share {
  if (
    !isRecord(value) ||
    typeof value.created_at !== "string" ||
    typeof value.updated_at !== "string"
  )
    throw invalidResponse();
  return {
    user: parsePerson(value.user),
    role: parseRole(value.role),
    created_at: value.created_at,
    updated_at: value.updated_at,
  };
}

export function parseShareList(value: unknown): ShareList {
  if (!isRecord(value) || !Array.isArray(value.shares)) throw invalidResponse();
  return {
    owner: parsePerson(value.owner),
    shares: value.shares.map(parseShare),
  };
}

export function parsePeoplePage(value: unknown): PeoplePage {
  if (
    !isRecord(value) ||
    !Array.isArray(value.items) ||
    !positive(value.page) ||
    typeof value.has_more !== "boolean"
  )
    throw invalidResponse();
  return {
    items: value.items.map(parsePerson),
    page: value.page,
    has_more: value.has_more,
  };
}

export function parseYarnCandidatePage(value: unknown): YarnCandidatePage {
  if (
    !isRecord(value) ||
    !Array.isArray(value.items) ||
    !positive(value.page) ||
    typeof value.has_more !== "boolean"
  )
    throw invalidResponse();
  return {
    items: value.items.map((item: unknown) => {
      if (
        !isRecord(item) ||
        !positive(item.id) ||
        typeof item.name !== "string" ||
        !nullableString(item.brand) ||
        !nullableString(item.colorway) ||
        !nullableString(item.weight_category) ||
        typeof item.linked !== "boolean"
      )
        throw invalidResponse();
      return {
        id: item.id,
        name: item.name,
        brand: item.brand,
        colorway: item.colorway,
        weight_category: item.weight_category,
        linked: item.linked,
      };
    }),
    page: value.page,
    has_more: value.has_more,
  };
}

export function parseTransferResult(value: unknown): TransferResult {
  const access = isRecord(value) ? parseAccess(value.access) : null;
  if (
    !isRecord(value) ||
    access === null ||
    !Array.isArray(value.moved_yarn_ids) ||
    !value.moved_yarn_ids.every(positive)
  )
    throw invalidResponse();
  return {
    new_owner: parsePerson(value.new_owner),
    access,
    moved_yarn_ids: value.moved_yarn_ids,
  };
}

function data(payload: unknown): unknown {
  if (!isRecord(payload)) throw invalidResponse();
  return payload.data;
}

function base(kind: ShareKind, id: number): string {
  return `/api/v2/${kind}/${id}`;
}

export async function listShares(
  kind: ShareKind,
  id: number,
  signal?: AbortSignal,
): Promise<ShareList> {
  return parseShareList(
    data(await apiRequest(`${base(kind, id)}/shares`, { signal })),
  );
}

export async function addShare(
  kind: ShareKind,
  id: number,
  userId: number,
  role: ShareRole,
  session: SessionData,
): Promise<Share> {
  return parseShare(
    data(
      await apiRequest(`${base(kind, id)}/shares`, {
        method: "POST",
        csrfToken: session.csrf_token,
        body: { user_id: userId, role },
      }),
    ),
  );
}

export async function changeShareRole(
  kind: ShareKind,
  id: number,
  userId: number,
  role: ShareRole,
  session: SessionData,
): Promise<Share> {
  return parseShare(
    data(
      await apiRequest(`${base(kind, id)}/shares/${userId}`, {
        method: "PATCH",
        csrfToken: session.csrf_token,
        body: { role },
      }),
    ),
  );
}

export async function revokeShare(
  kind: ShareKind,
  id: number,
  userId: number,
  session: SessionData,
): Promise<void> {
  await apiRequest(`${base(kind, id)}/shares/${userId}`, {
    method: "DELETE",
    csrfToken: session.csrf_token,
  });
}

/** Remove the caller's own share. */
export async function leaveShare(
  kind: ShareKind,
  id: number,
  session: SessionData,
): Promise<void> {
  await apiRequest(`${base(kind, id)}/leave`, {
    method: "POST",
    csrfToken: session.csrf_token,
  });
}

export async function transferOwnership(
  kind: ShareKind,
  id: number,
  newOwnerId: number,
  moveLinkedYarns: boolean,
  session: SessionData,
): Promise<TransferResult> {
  const body: Record<string, unknown> = { new_owner_id: newOwnerId };
  if (kind === "projects") body.move_linked_yarns = moveLinkedYarns;
  return parseTransferResult(
    data(
      await apiRequest(`${base(kind, id)}/transfer`, {
        method: "POST",
        csrfToken: session.csrf_token,
        body,
      }),
    ),
  );
}

export function peoplePath(query: string, page: number): string {
  const params = new URLSearchParams();
  const trimmed = query.trim().slice(0, SEARCH_QUERY_LIMIT);
  if (trimmed) params.set("q", trimmed);
  if (page > 1) params.set("page", String(page));
  const suffix = params.size ? `?${params}` : "";
  return `/api/v2/people${suffix}`;
}

export async function searchPeople(
  query: string,
  page: number,
  signal?: AbortSignal,
): Promise<PeoplePage> {
  return parsePeoplePage(
    data(await apiRequest(peoplePath(query, page), { signal })),
  );
}

export async function listYarnCandidates(
  projectId: number,
  query: string,
  page: number,
  signal?: AbortSignal,
): Promise<YarnCandidatePage> {
  const params = new URLSearchParams();
  if (query.trim()) params.set("q", query.trim());
  if (page > 1) params.set("page", String(page));
  const suffix = params.size ? `?${params}` : "";
  return parseYarnCandidatePage(
    data(
      await apiRequest(
        `${base("projects", projectId)}/yarn-candidates${suffix}`,
        {
          signal,
        },
      ),
    ),
  );
}

/** A Person in the shape resource owners use (the email stays empty). */
export function personAsOwner(person: Person): ResourceOwner {
  return {
    id: person.id,
    email: "",
    name: person.name,
    avatar_url: person.avatar_url,
  };
}
