import { test } from "@playwright/test";

import { runPaletteJourney } from "./journeys/palette";
import { e2eConfig, withLiveFixture } from "./lib/harness";

test("command palette browser journey", async () => {
  const cfg = e2eConfig();
  await withLiveFixture("palette", {}, (f) => runPaletteJourney(f, cfg));
});
