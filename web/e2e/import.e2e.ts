import { test } from "@playwright/test";

import { runImportJourney } from "./journeys/import";
import { e2eConfig, withLiveFixture } from "./lib/harness";

test("import browser journey", async () => {
  const cfg = e2eConfig();
  await withLiveFixture("import", {}, (f) => runImportJourney(f, cfg));
});
