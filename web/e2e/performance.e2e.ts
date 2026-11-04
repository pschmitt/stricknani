import { test } from "@playwright/test";
import { runPerformanceJourney } from "./journeys/performance";
import { e2eConfig, withLiveFixture } from "./lib/harness";

test("synthetic mobile performance journey", async () => {
  const cfg = e2eConfig();
  await withLiveFixture("performance", {}, (f) =>
    runPerformanceJourney(f, cfg),
  );
});
