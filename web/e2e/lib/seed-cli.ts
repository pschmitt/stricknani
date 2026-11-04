/**
 * Command line entry for the e2e seeder (port of the `main()` of
 * ci/stricknani/seed_rust.py), run through `e2e/lib/run-ts.mjs`:
 *
 *   npm --prefix web run seed -- --fixture-file F --token-file T \
 *     [--screenshot-detail]
 */
import { parseArgs } from "node:util";
import { FixtureError } from "./fixture";
import { SeedError, seedFixture } from "./seed";

export async function main(argv: string[]): Promise<number> {
  let values: Record<string, string | boolean | undefined>;
  try {
    values = parseArgs({
      args: argv,
      options: {
        "fixture-file": { type: "string" },
        "token-file": { type: "string" },
        "screenshot-detail": { type: "boolean" },
      },
      strict: true,
      allowPositionals: false,
    }).values;
  } catch (error) {
    console.error(
      `usage error: ${error instanceof Error ? error.message : "bad arguments"}`,
    );
    return 2;
  }
  const fixtureFile = values["fixture-file"];
  const tokenFile = values["token-file"];
  if (typeof fixtureFile !== "string" || typeof tokenFile !== "string") {
    console.error(
      "usage error: the following arguments are required: " +
        "--fixture-file, --token-file",
    );
    return 2;
  }
  try {
    await seedFixture(fixtureFile, tokenFile, {
      screenshotDetail: values["screenshot-detail"] === true,
    });
  } catch (error) {
    if (error instanceof FixtureError || error instanceof SeedError) {
      console.error(`Rust E2E seed failed: ${error.message}`);
      return 1;
    }
    throw error;
  }
  console.log("Rust E2E fixture seeded; PAT written to protected token file.");
  return 0;
}
