import { test } from "@playwright/test";

import { check, fn } from "./lib/common";
import { e2eConfig, launchBrowser, withLiveFixture } from "./lib/harness";
import { detailMenuItem } from "./lib/detail-menu";

test("project timer edits, runs, persists, and reminds", async () => {
  const cfg = e2eConfig();
  await withLiveFixture("project-timer", {}, async (fixture) => {
    const browser = await launchBrowser(cfg);
    const context = await browser.newContext({ serviceWorkers: "block" });
    const page = await context.newPage();
    try {
      await page.goto(`${fixture.origin}/app/`, { waitUntil: "networkidle" });
      await page.locator("#email").fill(fixture.env.STRICKNANI_TEST_EMAIL);
      await page
        .locator("#password")
        .fill(fixture.env.STRICKNANI_TEST_PASSWORD);
      await page.getByRole("button", { name: "Sign in", exact: true }).click();
      await page
        .getByRole("heading", { name: "Projects" })
        .waitFor({ state: "visible" });

      await page.goto(`${fixture.origin}/app/account`, {
        waitUntil: "networkidle",
      });
      const timerPreference = page.getByRole("switch", {
        name: "Project timers",
      });
      await timerPreference.waitFor({ state: "visible" });
      check(
        await timerPreference.isChecked(),
        "project timers should default to enabled",
      );
      await timerPreference.uncheck();
      let timerGetCount = 0;
      page.on("request", (request) => {
        const url = new URL(request.url());
        if (
          request.method() === "GET" &&
          /\/api\/v2\/projects\/\d+\/timer$/.test(url.pathname)
        ) {
          timerGetCount += 1;
        }
      });

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
      check(
        (await page.locator(".project-timer").count()) === 0 &&
          timerGetCount === 0,
        "disabled project timers were visible or requested",
      );
      await page.goto(`${fixture.origin}/app/account`, {
        waitUntil: "networkidle",
      });
      await page.getByRole("switch", { name: "Project timers" }).check();
      await page.goto(`${fixture.origin}/app/projects/${projectId}`, {
        waitUntil: "networkidle",
      });
      check(
        timerGetCount > 0,
        "enabling project timers did not load the timer",
      );
      // A timer that is not running sits in the ⋮ menu, not in the header.
      check(
        !(await page.locator(".timer-chip").isVisible()),
        "an idle timer was shown in the header",
      );
      await (await detailMenuItem(page, "Knitting time")).click();
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
          // Keep the real started_by: only the user who started the timer is reminded.
          const real = (await (await route.fetch()).json()) as {
            data: Record<string, unknown>;
          };
          await route.fulfill({
            status: 200,
            contentType: "application/json",
            body: JSON.stringify({
              data: {
                ...real.data,
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
      // Stopped: the timer is in the ⋮ menu; its dialog carries the full time.
      await (await detailMenuItem(page, "Knitting time")).click();
      await page
        .getByRole("heading", { name: "Knitting time" })
        .waitFor({ state: "visible" });
      await page.getByText(/^01:02:0[3-9]$/).waitFor({ state: "visible" });

      await page.route(
        `**/api/v2/projects/${projectId}/timer`,
        async (route) => {
          if (route.request().method() !== "GET") return route.continue();
          await route.fulfill({ status: 404, body: "" });
        },
      );
      await page.reload({ waitUntil: "networkidle" });
      await page
        .locator(".reader-heading-actions .action-overflow")
        .waitFor({ state: "visible" });
      check(
        (await page.locator(".project-timer").count()) === 0,
        "an unsupported server still showed the project timer",
      );
      await page.unroute(`**/api/v2/projects/${projectId}/timer`);
      await page.reload({ waitUntil: "networkidle" });
      await detailMenuItem(page, "Knitting time");
    } finally {
      await context.close();
      await browser.close();
    }
  });
});
