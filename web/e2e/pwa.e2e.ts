import { test } from "@playwright/test";
import { existsSync, realpathSync } from "node:fs";
import { basename, dirname, join, resolve } from "node:path";

import { runPwaJourney } from "./journeys/pwa";
import { e2eConfig, withLiveFixture } from "./lib/harness";

function binaryForWebRootOverride(binary: string): string {
  const resolved = realpathSync(resolve(binary));
  const unwrapped = join(dirname(resolved), ".stricknani-server-wrapped");
  // The Nix launcher pins WEB_ROOT to its packaged site. The PWA journey
  // restarts the fixture with temporary roots, so use its core executable.
  return basename(resolved) === "stricknani-server" && existsSync(unwrapped)
    ? unwrapped
    : resolved;
}

// The journey restarts the fixture itself (startFixture/stopFixture), so the
// live fixture is started unseeded and without the optional service binary,
// exactly like the python runner's run_live_fixture.
test("pwa browser journey", async () => {
  const cfg = e2eConfig();
  cfg.binary = binaryForWebRootOverride(cfg.binary);
  test.skip(
    cfg.browser !== "chromium",
    "the PWA acceptance run uses Chromium DevTools Protocol sessions",
  );
  await withLiveFixture("pwa", { seed: false, serverBinary: undefined }, (f) =>
    runPwaJourney(f, cfg),
  );
});
