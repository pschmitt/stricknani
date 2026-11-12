import { t, type Locale } from "#lib/i18n.svelte.ts";

export type AuditClientKind = "web" | "android" | "api" | "system" | "unknown";

/** The client that caused an audit event. Administrator audit responses only. */
export interface AuditClient {
  kind: AuditClientKind;
  /** App version with its build parameters, as the client reported it. */
  version: string | null;
  /** Short server-made label such as `Firefox 159 · Linux`; no localisation needed. */
  label: string | null;
  /** The raw User-Agent header, at most 256 characters. */
  user_agent: string | null;
}

const kinds: readonly AuditClientKind[] = [
  "web",
  "android",
  "api",
  "system",
  "unknown",
];

function text(value: unknown): string | null {
  return typeof value === "string" && value.length > 0 ? value : null;
}

/** Events from before clients were recorded, or malformed values, read as an unknown client. */
export function parseAuditClient(value: unknown): AuditClient {
  if (typeof value !== "object" || value === null)
    return { kind: "unknown", version: null, label: null, user_agent: null };
  const candidate = value as Record<string, unknown>;
  const kind = kinds.find((kind) => kind === candidate.kind) ?? "unknown";
  return {
    kind,
    version: text(candidate.version),
    label: text(candidate.label),
    user_agent: text(candidate.user_agent),
  };
}

/** The chip text; the same rules as the Android app (`AuditPresentation.clientLabel`). */
export function clientLabel(client: AuditClient, locale: Locale): string {
  switch (client.kind) {
    case "system":
      return t("System", locale);
    case "unknown":
      return t("Unknown client", locale);
    case "web":
      return client.label ?? t("Web", locale);
    case "android":
      return client.label ?? t("Android", locale);
    case "api":
      return client.label ?? t("API", locale);
  }
}
