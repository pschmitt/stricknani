import { mkdtemp, readFile, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import {
  ERROR_PAGES,
  mascotSvg,
  renderErrorPage,
  renderErrorPages,
} from "../../scripts/error-pages.mjs";

describe("static error pages", () => {
  it("covers the statuses nginx and the Rust server hand out", () => {
    expect(ERROR_PAGES.map((page) => page.status)).toEqual([
      404, 502, 503, 504,
    ]);
  });

  it("are bilingual, self-contained and CSP-friendly", () => {
    for (const page of ERROR_PAGES) {
      const html = renderErrorPage(page);
      expect(html).toContain(`<section lang="en"`);
      expect(html).toContain(`<section lang="de"`);
      expect(html).toContain(page.en.title);
      expect(html).toContain(page.de.title);
      expect(html).toContain("<style>");
      expect(html).toContain("prefers-color-scheme: dark");
      expect(html).toContain("prefers-reduced-motion: no-preference");
      // Inline style attributes are rejected by the strict style-src CSP.
      expect(html).not.toMatch(/<[a-z][^>]*\sstyle\s*=/i);
      expect(html).not.toMatch(/\son[a-z]+\s*=/i);
      // Nothing is loaded from elsewhere: only the favicon and app links by path.
      expect(html).not.toMatch(/https?:\/\//);
      expect(html).not.toMatch(
        /<(?:script|link|img)[^>]+(?:src|href)=\x22(?!\/app\/)/,
      );
      expect(html.includes('http-equiv="refresh"')).toBe(page.refresh);
    }
  });

  it("gives every page its own mood and no two pages share a headline", () => {
    const titles = new Set(ERROR_PAGES.map((page) => page.en.title));
    expect(titles.size).toBe(ERROR_PAGES.length);
    for (const mood of ["confused", "dizzy", "tired", "sleepy"] as const) {
      expect(mascotSvg(mood)).toContain(`mascot-${mood}`);
    }
  });

  it("are written to errors/<status>.html", async () => {
    const root = await mkdtemp(join(tmpdir(), "stricknani-errors-"));
    try {
      await renderErrorPages(root);
      for (const page of ERROR_PAGES) {
        const html = await readFile(
          join(root, "errors", `${page.status}.html`),
          "utf8",
        );
        expect(html).toContain(`${page.status} · Stricknani`);
      }
    } finally {
      await rm(root, { recursive: true, force: true });
    }
  });
});
