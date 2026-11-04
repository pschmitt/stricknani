import { tmpdir } from "node:os";
import { join } from "node:path";
import { defineConfig } from "@playwright/test";

// The journeys start their own disposable Rust server per spec (see
// e2e/lib/harness.ts), so there is no webServer and no parallelism.
export default defineConfig({
  testDir: "./e2e",
  testMatch: "**/*.e2e.ts",
  workers: 1,
  fullyParallel: false,
  retries: 0,
  // One journey is one test; a slow journey needs far more than the 30 s default.
  timeout: 20 * 60_000,
  // Playwright Test defaults these to "no timeout" for every context the
  // journeys create themselves; keep the library's 30 s so a missing element
  // or navigation fails the journey instead of hanging it.
  use: { actionTimeout: 30_000, navigationTimeout: 30_000 },
  reporter: "list",
  // Keep Playwright artifacts (traces, videos) out of the checkout.
  outputDir: join(tmpdir(), "stricknani-playwright-results"),
});
