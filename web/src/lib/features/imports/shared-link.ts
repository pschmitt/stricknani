import { isHttpUrl } from "./import-draft";

const WRAPPING_PREFIX = /^[<(\[\x22\x27]+/;
const WRAPPING_SUFFIX = /[>)\]\x22\x27.,;!?]+$/;

/**
 * The first whitespace-separated token of `text` that is an http(s) URL with a
 * host. Browsers share a bare URL or "title URL"; other schemes (`file:`,
 * `javascript:` ...) never match, and neither does a link that only sits in a
 * query string of another scheme.
 */
export function extractSharedUrl(
  text: string | null | undefined,
): string | null {
  if (!text) return null;
  for (const token of text.split(/\s+/)) {
    const candidate = token
      .replace(WRAPPING_PREFIX, "")
      .replace(WRAPPING_SUFFIX, "");
    if (isHttpUrl(candidate)) return candidate;
  }
  return null;
}

/**
 * The link of a Web Share Target request (`?url=`, `?text=`, `?title=`, in
 * that order: many apps put the link in `text` and only a title in `title`).
 */
export function sharedUrlFromSearch(search: string): string | null {
  const params = new URLSearchParams(search);
  for (const key of ["url", "text", "title"]) {
    const found = extractSharedUrl(params.get(key));
    if (found) return found;
  }
  return null;
}

/** A link to prefill the import page with (`?url=`); anything else is ignored. */
export function prefillUrlFromSearch(search: string): string | null {
  const value = new URLSearchParams(search).get("url")?.trim() ?? "";
  return isHttpUrl(value) ? value : null;
}

/** The import page's query that prefills it with `url`. */
export function importPrefillQuery(url: string): string {
  return `?url=${encodeURIComponent(url)}`;
}
