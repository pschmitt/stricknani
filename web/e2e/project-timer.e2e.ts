import { test } from "@playwright/test";

import { fn } from "./lib/common";
import { e2eConfig, launchBrowser, withLiveFixture } from "./lib/harness";

test("project timer edits, runs, persists, and reminds", async () => {
  const cfg = e2eConfig();
  await withLiveFixture("project-timer", {}, async (fixture) => {
    const browser = await launchBrowser(cfg);
    const context = await browser.newContext();
    const page = await context.newPage();
    try {
      await page.goto(`${fixture.origin}/app/`, { waitUntil: "networkidle" });
      await page.locator("#email").fill(fixture.env.STRICKNANI_RUST_TEST_EMAIL);
      await page
        .locator("#password")
        .fill(fixture.env.STRICKNANI_RUST_TEST_PASSWORD);
      await page.getByRole("button", { name: "Sign in" }).click();
      await page
        .getByRole("heading", { name: "Projects" })
        .waitFor({ state: "visible" });

      const projectId = await page.evaluate(
        fn(`async () => {
        const response = await fetch("/api/v2/projects", { credentials: "same-origin" });
        const payload = await response.json();
        const project = payload.data.find((item) => item.name === "Heirloom Baby Blanket");
        return project?.id ?? null;
      }`),
      );
      if (typeof projectId !== "number")
        throw new Error("seed project was unavailable");
      await page.goto(`${fixture.origin}/app/projects/${projectId}`, {
        waitUntil: "networkidle",
      });
      await page
        .getByRole("heading", { name: "Knitting time" })
        .waitFor({ state: "visible" });

      await page.getByRole("button", { name: "Edit time" }).click();
      await page.getByRole("textbox", { name: "Hours" }).fill("1");
      await page.getByRole("textbox", { name: "Minutes" }).fill("2");
      await page.getByRole("textbox", { name: "Seconds" }).fill("3");
      await page.getByRole("button", { name: "Save time" }).click();
      await page.getByText("01:02:03").waitFor({ state: "visible" });

      await page.getByRole("button", { name: "Start", exact: true }).click();
      await page
        .getByRole("button", { name: "Stop", exact: true })
        .waitFor({ state: "visible" });
      await page.route(
        `**/api/v2/projects/${projectId}/timer`,
        async (route) => {
          if (route.request().method() !== "GET") return route.continue();
          const serverTime = new Date().toISOString();
          const startedAt = new Date(Date.now() - 3_601_000).toISOString();
          await route.fulfill({
            status: 200,
            contentType: "application/json",
            body: JSON.stringify({
              data: {
                total_seconds: 3_601,
                started_at: startedAt,
                server_time: serverTime,
              },
            }),
          });
        },
      );
      await page.reload({ waitUntil: "networkidle" });
      await page
        .getByRole("alert")
        .filter({ hasText: "running for a while" })
        .waitFor({ state: "visible" });
      await page.getByRole("button", { name: "Keep timing" }).click();
      await page
        .getByRole("alert")
        .filter({ hasText: "running for a while" })
        .waitFor({ state: "hidden" });

      await page.unroute(`**/api/v2/projects/${projectId}/timer`);
      await page.getByRole("button", { name: "Stop", exact: true }).click();
      await page
        .getByRole("button", { name: "Start", exact: true })
        .waitFor({ state: "visible" });
      await page.reload({ waitUntil: "networkidle" });
      await page
        .getByRole("heading", { name: "Knitting time" })
        .waitFor({ state: "visible" });
      await page.getByText(/^01:02:0[3-9]$/).waitFor({ state: "visible" });
    } finally {
      await context.close();
      await browser.close();
    }
  });
});
