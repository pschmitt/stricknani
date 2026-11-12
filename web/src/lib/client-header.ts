/** The request header that tells the server which client made a request (audit log, admin only). */
export const clientHeaderName = "x-stricknani-client";

const versionPattern = /^[0-9A-Za-z._+-]{1,32}$/;
// A commit as the build records it: a short or full SHA, optionally "-dirty".
const commitPattern = /^([0-9a-f]{7,40})(-dirty)?$/i;

/**
 * `web/<version> (build <commit>)`, the value of the client header. The server drops values it
 * cannot parse, so anything outside its charset is omitted here: a malformed version gives
 * `web/unknown`, a missing or unusable commit gives no build part.
 */
export function clientHeaderValue(version: string, revision: string): string {
  const safeVersion = versionPattern.test(version) ? version : "unknown";
  const commit = commitPattern.exec(revision.trim());
  return commit
    ? `web/${safeVersion} (build ${commit[1].slice(0, 8)}${commit[2] ?? ""})`
    : `web/${safeVersion}`;
}

/** Value for this bundle, from the build-time version and commit. */
export const clientHeader: string = clientHeaderValue(
  typeof __STRICKNANI_VERSION__ === "string" ? __STRICKNANI_VERSION__ : "",
  typeof __STRICKNANI_GIT_REVISION__ === "string"
    ? __STRICKNANI_GIT_REVISION__
    : "",
);
