import { test } from "@playwright/test";

import { runUiJourney } from "./journeys/ui";
import { e2eConfig, withLiveFixture } from "./lib/harness";

// Mirrors the three CI invocations of the web UI browser acceptance run.

test("ui browser journey: core, optional services disabled", async () => {
  const cfg = e2eConfig();
  await withLiveFixture("ui-core", {}, (f) =>
    runUiJourney(f, cfg, {
      serviceFixtureEnabled: false,
      ocrMode: "success",
      archiveMode: "success",
      signupEnabled: false,
    }),
  );
});

test("ui browser journey: services success, signup enabled", async () => {
  const cfg = e2eConfig();
  test.skip(
    cfg.serviceFixtureBinary === undefined,
    "STRICKNANI_FIXTURE_BINARY is not set",
  );
  await withLiveFixture(
    "ui-services-success",
    {
      serverBinary: cfg.serviceFixtureBinary,
      ocrMode: "success",
      archiveMode: "success",
      signupEnabled: true,
    },
    (f) =>
      runUiJourney(f, cfg, {
        serviceFixtureEnabled: true,
        ocrMode: "success",
        archiveMode: "success",
        signupEnabled: true,
      }),
  );
});

test("ui browser journey: services failure, signup enabled", async () => {
  const cfg = e2eConfig();
  test.skip(
    cfg.serviceFixtureBinary === undefined,
    "STRICKNANI_FIXTURE_BINARY is not set",
  );
  await withLiveFixture(
    "ui-services-failure",
    {
      serverBinary: cfg.serviceFixtureBinary,
      ocrMode: "failure",
      archiveMode: "failure",
      signupEnabled: true,
    },
    (f) =>
      runUiJourney(f, cfg, {
        serviceFixtureEnabled: true,
        ocrMode: "failure",
        archiveMode: "failure",
        signupEnabled: true,
      }),
  );
});
