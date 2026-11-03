// Content and helpers behind the About page. The Android app shows the same content in
// Settings > About; keep the two lists in step.
import { apiRequest } from "#lib/api.ts";
import { appVersion } from "#lib/app-version.ts";

export const sourceUrl = "https://github.com/pschmitt/stricknani";
export const sponsorUrl = "https://github.com/sponsors/pschmitt";
export const issuesUrl = "https://github.com/pschmitt/stricknani/issues";

export const commitUrlBase = "https://github.com/pschmitt/stricknani/commit/";

/** Git commit of the web bundle as injected at build time ("" when unknown). */
export const bundleRevision: string =
  typeof __STRICKNANI_GIT_REVISION__ === "string"
    ? __STRICKNANI_GIT_REVISION__
    : "";

export interface Commit {
  /** What to show: `9a7bfc70` or `9a7bfc70-dirty`. */
  display: string;
  /** Commit page on GitHub, never carrying the `-dirty` suffix. */
  url: string;
  /** The revision as given, for the Developer card and bug reports. */
  full: string;
}

/** Parse a build-time revision (a SHA, optionally `-dirty`); null when blank or unknown. */
export function parseCommit(raw: string | null | undefined): Commit | null {
  const full = raw?.trim() ?? "";
  if (!full || full === "unknown") return null;
  const dirty = full.endsWith("-dirty");
  const sha = dirty ? full.slice(0, -"-dirty".length) : full;
  if (!/^[0-9a-f]{7,40}$/i.test(sha)) return null;
  return {
    display: `${sha.slice(0, 8)}${dirty ? "-dirty" : ""}`,
    url: `${commitUrlBase}${sha}`,
    full,
  };
}

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
  commit: Commit | null = null,
): string {
  const lines = [`Stricknani ${displayVersion(server, web)}`];
  const build = shortBuildId(server.buildId);
  if (build) lines.push(`Build: ${build}`);
  lines.push(`Web: ${web}`);
  if (commit) lines.push(`Commit: ${commit.display}`);
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
