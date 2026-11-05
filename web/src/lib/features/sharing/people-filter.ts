import type { Person } from "./sharing-api.ts";

/**
 * The people to offer right now. The server matches a case-insensitive substring of the name;
 * doing the same on what is already loaded keeps the list honest while the next request is
 * still on its way, so a stale full directory never sits under a typed query.
 */
export function visiblePeople(
  results: readonly Person[],
  excludeIds: readonly number[],
  query: string,
): Person[] {
  const needle = query.trim().toLocaleLowerCase();
  const excluded = new Set(excludeIds);
  return results.filter(
    (person) =>
      !excluded.has(person.id) &&
      (needle === "" || person.name.toLocaleLowerCase().includes(needle)),
  );
}
