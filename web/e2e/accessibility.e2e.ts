import { test } from "@playwright/test";

import { runAccessibilityJourney } from "./journeys/accessibility";
import { e2eConfig, withLiveFixture } from "./lib/harness";

test("accessibility browser journey", async () => {
  const cfg = e2eConfig();
  await withLiveFixture("accessibility", { signupEnabled: false }, (f) =>
    runAccessibilityJourney(f, cfg),
  );
});
