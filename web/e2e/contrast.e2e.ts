import { test } from "@playwright/test";
import { e2eConfig, withLiveFixture } from "./lib/harness";
import { runContrastJourney } from "./journeys/contrast";

test("contrast browser journey", async () => {
  const cfg = e2eConfig();
  await withLiveFixture("contrast", { signupEnabled: false }, (f) =>
    runContrastJourney(f, cfg),
  );
});
