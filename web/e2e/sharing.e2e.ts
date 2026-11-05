import { test } from "@playwright/test";

import { runSharingJourney } from "./journeys/sharing";
import { e2eConfig, withLiveFixture } from "./lib/harness";

test("sharing and ownership transfer browser journey", async () => {
  const cfg = e2eConfig();
  await withLiveFixture("sharing", { seed: false }, (f) =>
    runSharingJourney(f, cfg),
  );
});
