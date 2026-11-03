/** The name of the token a one-click "Connect the app" creates; the app renames it later. */
export const setupTokenName = "Stricknani app (setup)";

function base64Url(text: string): string {
  const bytes = new TextEncoder().encode(text);
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary)
    .replaceAll("+", "-")
    .replaceAll("/", "_")
    .replace(/=+$/, "");
}

/**
 * The `stricknani://setup?p=<payload>` link the Android app reads from a QR code or an intent:
 * a base64url (no padding) JSON envelope with the server's base URL and a fresh API token.
 */
export function setupUri(
  baseUrl: string,
  token: string,
  createdAt: number = Date.now(),
): string {
  const envelope = { version: 1, createdAt, baseUrl, token };
  return `stricknani://setup?p=${base64Url(JSON.stringify(envelope))}`;
}

const minute = 60_000;
const hour = 60 * minute;
const day = 24 * hour;

/**
 * "2 hours ago" for the last month, then a plain date; the value is an RFC 3339 timestamp.
 * Unparseable input is returned unchanged.
 */
export function formatLastUsed(
  value: string,
  locale: string,
  now: number = Date.now(),
): string {
  const at = new Date(value);
  if (Number.isNaN(at.getTime())) return value;
  const age = Math.max(0, now - at.getTime());
  if (age >= 30 * day) {
    return new Intl.DateTimeFormat(locale, { dateStyle: "medium" }).format(at);
  }
  const rtf = new Intl.RelativeTimeFormat(locale, { numeric: "auto" });
  if (age < minute) return rtf.format(0, "minute");
  if (age < hour) return rtf.format(-Math.floor(age / minute), "minute");
  if (age < day) return rtf.format(-Math.floor(age / hour), "hour");
  return rtf.format(-Math.floor(age / day), "day");
}
