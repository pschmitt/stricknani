import type { SessionData } from "#lib/api.ts";

/**
 * What the server lets this session import. Mirrors `imports: { url, ai }` of
 * `GET /api/v2/meta`: link imports need the server switch and an import
 * service, AI needs the AI switch, a configured provider and that service.
 */
export type ImportAvailability = {
  /** Link (URL) imports can run. */
  url: boolean;
  /** AI-assisted extraction can run. */
  ai: boolean;
};

export function importAvailability(
  capabilities: Partial<SessionData["capabilities"]> | null | undefined,
): ImportAvailability {
  return {
    url:
      capabilities?.external_imports === true &&
      capabilities?.import_preview === true,
    ai: capabilities?.ai === true && capabilities?.import_preview === true,
  };
}
