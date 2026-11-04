import { test } from "@playwright/test";

import { runAccountJourney } from "./journeys/account";
import { e2eConfig, withLiveFixture } from "./lib/harness";

test("account browser journey", async () => {
  const cfg = e2eConfig();
  await withLiveFixture("account", {}, (f) => runAccountJourney(f, cfg));
});
