import { test } from "@playwright/test";
import { e2eConfig, withLiveFixture } from "./lib/harness";
import { runYarnJourney } from "./journeys/yarn";

test("yarn and category browser journey", async () => {
  const cfg = e2eConfig();
  await withLiveFixture("yarn", {}, (f) => runYarnJourney(f, cfg));
});
