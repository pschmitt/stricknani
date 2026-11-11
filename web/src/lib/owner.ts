/** Who owns a project or yarn. Every v2 project and yarn resource carries one. */
export interface ResourceOwner {
  id: number;
  email: string;
  /** Short display name, e.g. "Anika". */
  name: string;
  avatar_url: string | null;
}

/** "anika.bergmann@x" as "Anika Bergmann", the server's fallback when nobody set a name. */
export function nameFromEmail(email: string): string {
  const words = (email.split("@")[0] ?? "")
    .split(/[._-]/)
    .filter(Boolean)
    .map((word) => word.charAt(0).toLocaleUpperCase() + word.slice(1));
  return words.length ? words.join(" ") : email;
}

/** Parse an optional `owner` field; anything malformed becomes `null` (no badge). */
export function parseOwner(value: unknown): ResourceOwner | null {
  if (typeof value !== "object" || value === null) return null;
  const owner = value as Record<string, unknown>;
  if (
    typeof owner.id !== "number" ||
    !Number.isSafeInteger(owner.id) ||
    owner.id <= 0 ||
    typeof owner.email !== "string"
  )
    return null;
  const name =
    typeof owner.name === "string" && owner.name.trim()
      ? owner.name.trim()
      : nameFromEmail(owner.email);
  const avatar = owner.avatar_url;
  return {
    id: owner.id,
    email: owner.email,
    name,
    avatar_url:
      typeof avatar === "string" && avatar.startsWith("/") ? avatar : null,
  };
}

/** The owner when the signed-in user does not own the resource, else `null`. */
export function foreignOwner(
  owner: ResourceOwner | null | undefined,
  userId: number | null | undefined,
): ResourceOwner | null {
  if (!owner || userId === null || userId === undefined) return null;
  return owner.id === userId ? null : owner;
}

/** Spread into a parsed resource: adds `owner` only when the server sent a valid one. */
export function ownerField(value: unknown): { owner?: ResourceOwner } {
  const owner = parseOwner(value);
  return owner ? { owner } : {};
}
