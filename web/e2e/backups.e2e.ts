import { test } from "@playwright/test";

import { runBackupsJourney } from "./journeys/backups";
import { e2eConfig, withLiveFixture } from "./lib/harness";

test("data export and restore browser journey", async () => {
  const cfg = e2eConfig();
  await withLiveFixture("backups", { seed: false }, (f) =>
    runBackupsJourney(f, cfg),
  );
});
