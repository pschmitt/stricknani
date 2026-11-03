/** Parse a valid http(s) URL; null for anything else, including javascript: links. */
export function parseHttpUrl(text: string): URL | null {
  try {
    const url = new URL(text.trim());
    return url.protocol === "https:" || url.protocol === "http:" ? url : null;
  } catch {
    return null;
  }
}

export function displayDomain(url: URL | null): string {
  return url ? url.hostname.replace(/^www\./, "") : "";
}

export function stripNewlines(text: string): string {
  return text.replace(/[\r\n]+/g, "");
}
