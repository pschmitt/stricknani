import { test } from "@playwright/test";

import { check } from "./lib/common";
import { e2eConfig, launchBrowser, withLiveFixture } from "./lib/harness";

async function settleLayout(
  page: import("@playwright/test").Page,
): Promise<void> {
  await page.evaluate(async () => {
    await document.fonts.ready;
    await new Promise<void>((resolve) =>
      requestAnimationFrame(() => requestAnimationFrame(() => resolve())),
    );
  });
}

test("trash card and activity polish", async ({}, testInfo) => {
  const cfg = e2eConfig();
  await withLiveFixture("trash-polish", {}, async (fixture) => {
    const browser = await launchBrowser(cfg);
    const context = await browser.newContext({
      viewport: { width: 390, height: 844 },
      serviceWorkers: "block",
    });
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
      const seed = await page.evaluate(async () => {
        const [projectsResponse, sessionResponse] = await Promise.all([
          fetch("/api/v2/projects"),
          fetch("/api/v2/session"),
        ]);
        const projects = (await projectsResponse.json()).data;
        const project = projects.find(
          (item: { name: string }) => item.name === "Heirloom Baby Blanket",
        );
        const session = (await sessionResponse.json()).data;
        if (!project || typeof project.id !== "number")
          throw new Error("seed project missing");
        const removed = await fetch(`/api/v2/projects/${project.id}`, {
          method: "DELETE",
          headers: { "x-csrf-token": session.csrf_token },
        });
        if (!removed.ok) throw new Error("seed project could not be trashed");
        return { projectId: project.id as number, ownerId: session.user.id };
      });
      await page.route("**/api/v2/trash?*", async (route) => {
        const session = (
          await (
            await route.fetch({ url: `${fixture.origin}/api/v2/session` })
          ).json()
        ).data;
        const now = new Date();
        await route.fulfill({
          status: 200,
          contentType: "application/json",
          body: JSON.stringify({
            data: [
              {
                kind: "image",
                id: 987654321,
                name: "Blanket detail image",
                owner: {
                  id: seed.ownerId + 1,
                  email: "demo@example.test",
                  name: "Demo Knitter",
                  avatar_url: null,
                },
                can_restore: true,
                deleted_at: now.toISOString(),
                purge_at: new Date(now.getTime() + 90 * 86400000).toISOString(),
                days_remaining: 90,
                parent: {
                  kind: "project",
                  id: seed.projectId,
                  name: "Heirloom Baby Blanket",
                },
                detail: null,
                thumbnail_url: null,
              },
            ],
            meta: { scope: "mine", retention_days: 90 },
          }),
        });
      });
      await page.goto(`${fixture.origin}/app/trash`, {
        waitUntil: "networkidle",
      });
      const row = page.locator('[data-testid="trash-list"] .trash__row');
      await row.waitFor({ state: "visible" });
      const fallbackIcon = row.locator(".trash__thumb-icon svg");
      await fallbackIcon.waitFor({ state: "visible" });
      const iconSize = await fallbackIcon.evaluate((element) => {
        const rect = element.getBoundingClientRect();
        return { width: rect.width, height: rect.height };
      });
      check(
        iconSize.width >= 24 && iconSize.height >= 24,
        "trash placeholder icon is too small",
      );
      const parent = page.getByTestId("trash-parent-link");
      check(
        (await parent.textContent())?.trim() ===
          "In project Heirloom Baby Blanket",
        "parent wording changed",
      );
      check(
        new URL((await parent.getAttribute("href")) ?? "", fixture.origin)
          .pathname === `/app/projects/${seed.projectId}`,
        "parent link target is incorrect",
      );
      check(
        await page
          .locator(".trash__owner .user-label__name")
          .filter({ hasText: "Demo Knitter" })
          .isVisible(),
        "owner line is missing",
      );

      for (const width of [390, 900]) {
        await page.setViewportSize({ width, height: 844 });
        await settleLayout(page);
        const dimensions = await page.evaluate(() => ({
          viewport: innerWidth,
          document: document.documentElement.scrollWidth,
        }));
        check(
          dimensions.document <= dimensions.viewport,
          "trash page has horizontal overflow",
        );
        const actionHeights = await row
          .locator(".trash__actions button")
          .evaluateAll((buttons) =>
            buttons.map((button) => button.getBoundingClientRect().height),
          );
        check(
          actionHeights.length === 2 &&
            actionHeights.every((height) => height >= 44),
          "trash action targets are too small",
        );
        if (width === 390) {
          const positions = await row.evaluate((element) => ({
            main: element.querySelector(".trash__main")!.getBoundingClientRect()
              .bottom,
            actions: element
              .querySelector(".trash__actions")!
              .getBoundingClientRect().top,
          }));
          check(
            positions.actions >= positions.main,
            "mobile actions are not below the trash content",
          );
        }
        await page.screenshot({
          path: testInfo.outputPath(`trash-polish-${width}.png`),
          fullPage: true,
        });
      }

      await parent.click();
      await page
        .getByRole("heading", { name: "Heirloom Baby Blanket", exact: true })
        .waitFor({ state: "visible" });
      await page.goto(`${fixture.origin}/app/activity`, {
        waitUntil: "networkidle",
      });
      const dangerNode = page.locator('.tl__node[data-tone="delete"]').first();
      await dangerNode.waitFor({ state: "visible" });
      const colors = await dangerNode.evaluate((element) => {
        const probe = document.createElement("span");
        probe.style.color = "var(--danger)";
        element.append(probe);
        const colors = {
          color: getComputedStyle(element).color,
          danger: getComputedStyle(probe).color,
        };
        probe.remove();
        return colors;
      });
      check(
        colors.color === colors.danger,
        "trash icon does not use the danger color",
      );
      const attribution = page.locator(".tl__meta .user-label").first();
      await attribution.waitFor({ state: "visible" });
      const order = await attribution.evaluate((element) => {
        const avatar = element.querySelector(".user-avatar");
        const name = element.querySelector(".user-label__name");
        return (
          !!avatar &&
          !!name &&
          !!(
            avatar.compareDocumentPosition(name) &
            Node.DOCUMENT_POSITION_FOLLOWING
          )
        );
      });
      check(
        order,
        "activity attribution does not place the avatar before the name",
      );
    } finally {
      await context.close();
      await browser.close();
    }
  });
});
