import { test } from "@playwright/test";

import { runAdminJourney } from "./journeys/admin";
import { e2eConfig, withLiveFixture } from "./lib/harness";

test("admin browser journey", async () => {
  const cfg = e2eConfig();
  await withLiveFixture("admin", {}, (f) => runAdminJourney(f, cfg));
});
