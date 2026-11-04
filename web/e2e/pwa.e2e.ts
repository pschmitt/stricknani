import { test } from "@playwright/test";

import { runPwaJourney } from "./journeys/pwa";
import { e2eConfig, withLiveFixture } from "./lib/harness";

// The journey restarts the fixture itself (startFixture/stopFixture), so the
// live fixture is started unseeded and without the optional service binary,
// exactly like the python runner's run_live_fixture.
test("pwa browser journey", async () => {
  const cfg = e2eConfig();
  test.skip(
    cfg.browser !== "chromium",
    "the PWA acceptance run uses Chromium DevTools Protocol sessions",
  );
  await withLiveFixture("pwa", { seed: false, serverBinary: undefined }, (f) =>
    runPwaJourney(f, cfg),
  );
});
