import { asset } from "$app/paths";

export type Locale = "en" | "de";
type Catalog = Record<string, string>;
type Catalogs = Record<Locale, Catalog>;

let catalogs = $state<Catalogs>({ en: {}, de: {} });
let activeLocale: Locale = "en";

/** Mark a literal message key when its lookup happens through a dynamic component or list. */
export function msg(message: string): string {
  return message;
}

/** Load the PO-generated public catalogue without bundling or caching it in storage. */
export async function loadCatalog(signal?: AbortSignal): Promise<void> {
  try {
    const response = await fetch(asset("messages.json"), {
      credentials: "same-origin",
      signal,
    });
    if (!response.ok) return;
    const payload: unknown = await response.json();
    if (!isCatalogs(payload)) return;
    catalogs = payload;
  } catch (error) {
    if (error instanceof DOMException && error.name === "AbortError")
      throw error;
  }
}

/** Translate an English source message; use that source text until a locale entry exists. */
export function setLocale(locale: Locale): void {
  activeLocale = locale;
}

export function t(message: string, locale: Locale = activeLocale): string {
  return catalogs[locale]?.[message] ?? message;
}

function isCatalogs(value: unknown): value is Catalogs {
  if (typeof value !== "object" || value === null) return false;
  const candidate = value as Record<string, unknown>;
  return isCatalog(candidate.en) && isCatalog(candidate.de);
}

function isCatalog(value: unknown): value is Catalog {
  return (
    typeof value === "object" &&
    value !== null &&
    Object.values(value).every((text) => typeof text === "string")
  );
}
