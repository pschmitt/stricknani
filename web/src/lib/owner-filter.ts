import { apiRequest } from "#lib/api.ts";
import { parseOwner, type ResourceOwner } from "#lib/owner.ts";
import type { LibraryScope } from "#lib/library-scope.svelte.ts";

/**
 * The owner filter of the project and yarn lists: empty shows everything, `me` the caller's own
 * items, `shared` everybody else's (shared with the caller) and a number one owner's items. The
 * same values are the `owner` URL parameter of `/app/projects` and `/app/yarns` (other pages link
 * to them) and the `owner` query parameter of the API.
 */
export type OwnerChoice = "" | "me" | "shared" | `${number}`;

/** A URL or API value as a choice; anything unknown is no filter. */
export function ownerChoiceFromParam(value: string | null): OwnerChoice {
  const text = value?.trim() ?? "";
  if (text === "me" || text === "mine") return "me";
  if (text === "shared") return "shared";
  if (/^[1-9][0-9]{0,15}$/.test(text) && Number.isSafeInteger(Number(text)))
    return text as `${number}`;
  return "";
}

export function ownerChoiceIsUser(choice: OwnerChoice): boolean {
  return choice !== "" && choice !== "me" && choice !== "shared";
}

/** The owners (other than the caller) of the items the caller sees: the filter's options. */
export async function listOwners(
  kind: "projects" | "yarns",
  signal: AbortSignal,
  scope: LibraryScope = "mine",
): Promise<ResourceOwner[]> {
  const payload: unknown = await apiRequest(
    `/api/v2/${kind}/owners?scope=${scope}`,
    { signal },
  );
  if (
    typeof payload !== "object" ||
    payload === null ||
    !Array.isArray((payload as { data?: unknown }).data)
  )
    throw new Error("Invalid response");
  return (payload as { data: unknown[] }).data.flatMap((value) => {
    const owner = parseOwner(value);
    return owner ? [owner] : [];
  });
}
