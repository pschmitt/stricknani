/**
 * The "Mine / Everyone" choice of an administrator. Everyone else always sees only their own
 * items, so the scope is `mine` for them and the choice is never stored.
 */
export type LibraryScope = "mine" | "all";

interface ScopeUser {
  id: number;
  is_admin: boolean;
}

const KEY_PREFIX = "stricknani.library-scope.";

/** Choices made in this page session, so the UI reacts before storage is read again. */
const chosen = $state<Record<number, LibraryScope>>({});

function readStored(userId: number): LibraryScope {
  try {
    const value = localStorage.getItem(`${KEY_PREFIX}${userId}`);
    if (value === "mine" || value === "all") return value;
  } catch {
    // Storage can be blocked; Everyone is the default.
  }
  return "all";
}

/** The scope to request for this user: admins default to Everyone and the choice is per user. */
export function scopeFor(user: ScopeUser | null | undefined): LibraryScope {
  if (!user || !user.is_admin) return "mine";
  return chosen[user.id] ?? readStored(user.id);
}

export function setScope(user: ScopeUser, scope: LibraryScope): void {
  if (!user.is_admin) return;
  chosen[user.id] = scope;
  try {
    localStorage.setItem(`${KEY_PREFIX}${user.id}`, scope);
  } catch {
    // Not remembered across visits; still applies to this page session.
  }
}
