import type { Locator, Page } from "@playwright/test";

/**
 * Opens the ⋮ menu of a project or yarn page and returns its item with this name. Favorite and
 * an idle timer live there; only a running timer stays in the header.
 */
export async function detailMenuItem(
  page: Page,
  name: string,
): Promise<Locator> {
  const trigger = page.locator('summary[aria-label="More actions"]').first();
  const item = page.getByRole("menuitem", { name, exact: true });
  if (!(await item.isVisible())) await trigger.click();
  await item.waitFor({ state: "visible" });
  return item;
}
