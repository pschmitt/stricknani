export const NEW_CATEGORY_VALUE = "__new_category__";

/**
 * The names offered by the category dropdown: the user's categories sorted by name, plus the
 * project's current category when it is not among them (a legacy or just typed name).
 */
export function categoryOptions(
  categories: ReadonlyArray<{ name: string }>,
  current: string,
): string[] {
  const names: string[] = [];
  const seen = new Set<string>();
  for (const { name } of categories) {
    const key = name.trim().toLowerCase();
    if (!key || seen.has(key)) continue;
    seen.add(key);
    names.push(name);
  }
  const trimmed = current.trim();
  if (trimmed && !seen.has(trimmed.toLowerCase())) names.push(trimmed);
  return names.sort((a, b) =>
    a.localeCompare(b, undefined, { sensitivity: "base" }),
  );
}

/** The option that stands for `current`; the server matches names case-insensitively. */
export function selectedCategory(options: string[], current: string): string {
  const trimmed = current.trim();
  if (!trimmed) return "";
  return (
    options.find((name) => name.toLowerCase() === trimmed.toLowerCase()) ??
    trimmed
  );
}
