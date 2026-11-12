import { test } from "@playwright/test";
import { runMediaViewerJourney } from "./journeys/media-viewer";
import { e2eConfig, withLiveFixture } from "./lib/harness";

test("image viewer shows every photo in full", async () => {
  const cfg = e2eConfig();
  await withLiveFixture("media-viewer", {}, (f) =>
    runMediaViewerJourney(f, cfg),
  );
});
