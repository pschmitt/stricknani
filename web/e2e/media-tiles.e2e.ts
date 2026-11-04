import { test } from "@playwright/test";
import { runMediaTilesJourney } from "./journeys/media-tiles";
import { e2eConfig, withLiveFixture } from "./lib/harness";

test("media tiles browser journey", async () => {
  const cfg = e2eConfig();
  await withLiveFixture("media-tiles", {}, (f) => runMediaTilesJourney(f, cfg));
});
