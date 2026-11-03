// Content and helpers behind the About page. The Android app shows the same content in
// Settings > About; keep the two lists in step.
import { apiRequest } from "#lib/api.ts";
import { appVersion } from "#lib/app-version.ts";

export const sourceUrl = "https://github.com/pschmitt/stricknani";
export const sponsorUrl = "https://github.com/sponsors/pschmitt";
export const issuesUrl = "https://github.com/pschmitt/stricknani/issues";

export interface ServerBuild {
  /** Server version without a leading "v", or null when the server did not report one. */
  version: string | null;
  /** Full build id as reported by the server. */
  buildId: string | null;
}

export const emptyServerBuild: ServerBuild = { version: null, buildId: null };

/** Short form of the build id, long enough to tell builds apart in a bug report. */
export function shortBuildId(
  buildId: string | null | undefined,
): string | null {
  const trimmed = buildId?.trim();
  return trimmed ? trimmed.slice(0, 8) : null;
}

function text(value: unknown): string | null {
  return typeof value === "string" && value.trim() ? value.trim() : null;
}

/** Read `version` and `build_id` from a `GET /api/v2/meta` response, tolerating anything else. */
export function parseServerBuild(response: unknown): ServerBuild {
  const data =
    typeof response === "object" && response !== null && "data" in response
      ? (response as { data: unknown }).data
      : null;
  if (typeof data !== "object" || data === null) return emptyServerBuild;
  const record = data as Record<string, unknown>;
  return { version: text(record.version), buildId: text(record.build_id) };
}

export async function fetchServerBuild(
  signal?: AbortSignal,
): Promise<ServerBuild> {
  try {
    return parseServerBuild(await apiRequest("/api/v2/meta", { signal }));
  } catch (error) {
    if (error instanceof DOMException && error.name === "AbortError")
      throw error;
    return emptyServerBuild;
  }
}

/** The app version: the server one when known, else the bundled web one, with a "v" prefix. */
export function displayVersion(
  server: ServerBuild,
  web: string = appVersion,
): string {
  return server.version ? `v${server.version.replace(/^v/, "")}` : web;
}

/** Plain-text block to paste into a bug report. */
export function versionReport(
  server: ServerBuild,
  web: string = appVersion,
): string {
  const lines = [`Stricknani ${displayVersion(server, web)}`];
  const build = shortBuildId(server.buildId);
  if (build) lines.push(`Build: ${build}`);
  lines.push(`Web: ${web}`);
  return lines.join("\n");
}

export interface Credit {
  name: string;
  detail: string;
  licence: string;
  url: string;
}

/** Third-party material shipped with the app, with the licence of each. */
export const credits: readonly Credit[] = [
  {
    name: "Phosphor Icons",
    detail: "Icons",
    licence: "MIT",
    url: "https://phosphoricons.com",
  },
  {
    name: "Fraunces",
    detail: "Display typeface",
    licence: "SIL OFL 1.1",
    url: "https://github.com/undercasetype/Fraunces",
  },
  {
    name: "Nunito",
    detail: "Text typeface",
    licence: "SIL OFL 1.1",
    url: "https://github.com/googlefonts/nunito",
  },
];

export const appLicence = {
  name: "GNU GPL v3",
  url: "https://github.com/pschmitt/stricknani/blob/main/LICENSE",
};
