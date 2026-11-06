import { closeBrowser } from "../lib/common";
import * as fs from "node:fs";
import * as path from "node:path";
import type { Page } from "@playwright/test";
import { AcceptanceFailure, check } from "../lib/common";
import type { E2eConfig, LiveFixture } from "../lib/harness";
import { launchBrowser, stageMarker } from "../lib/harness";
import { PROJECT_NAME, YARN_NAME } from "../lib/seed";

type Theme = "light" | "dark";
const THEMES: readonly Theme[] = ["light", "dark"];

type Rec = Record<string, unknown>;

const REQUIRED_CATEGORIES = [
  "heading",
  "body-text",
  "muted-text",
  "link-text",
  "control-text",
  "placeholder",
  "control-border",
  "keyboard-focus",
];

async function sample(
  page: Page,
  category: string,
  selector: string,
): Promise<Rec[]> {
  const value: unknown = await page.evaluate(
    ({ category, selector }) => {
      type Color = number[];
      const parse = (value: string): Color | null => {
        if (value === "transparent") return [0, 0, 0, 0];
        if (!/^rgba?\(/i.test(value)) return null;
        const parts = value.match(/[\d.]+/g)?.map(Number);
        if (!parts || parts.length < 3) return null;
        return [parts[0] ?? 0, parts[1] ?? 0, parts[2] ?? 0, parts[3] ?? 1];
      };
      const over = (top: Color, bottom: Color): Color => {
        const topAlpha = top[3] ?? 1;
        const bottomAlpha = bottom[3] ?? 1;
        const alpha = topAlpha + bottomAlpha * (1 - topAlpha);
        if (alpha === 0) return [0, 0, 0, 0];
        return [0, 1, 2]
          .map(
            (index) =>
              ((top[index] ?? 0) * topAlpha +
                (bottom[index] ?? 0) * bottomAlpha * (1 - topAlpha)) /
              alpha,
          )
          .concat(alpha);
      };
      const luminance = (color: Color): number =>
        color
          .slice(0, 3)
          .map((value) => value / 255)
          .map((value) =>
            value <= 0.04045 ? value / 12.92 : ((value + 0.055) / 1.055) ** 2.4,
          )
          .reduce(
            (sum, value, index) =>
              sum + value * (([0.2126, 0.7152, 0.0722][index] as number) ?? 0),
            0,
          );
      const cssColor = (color: Color): string =>
        `rgb(${color
          .slice(0, 3)
          .map((value) => Math.round(value))
          .join(", ")})`;
      const selectorPath = (element: Element): string => {
        const parts: string[] = [];
        for (
          let node: Element | null = element;
          node instanceof Element;
          node = node.parentElement
        ) {
          const classes = [...node.classList]
            .slice(0, 3)
            .map((name) => `.${CSS.escape(name)}`)
            .join("");
          let part = `${node.tagName.toLowerCase()}${classes}`;
          if (node.parentElement) {
            const current: Element = node;
            const siblings = [...node.parentElement.children].filter(
              (sibling) => sibling.tagName === current.tagName,
            );
            if (siblings.length > 1) {
              part += `:nth-of-type(${siblings.indexOf(current) + 1})`;
            }
          }
          parts.unshift(part);
          if (node.tagName === "MAIN") break;
        }
        return parts.join(" > ");
      };
      const ratio = (foreground: Color, background: Color): number => {
        const values = [luminance(foreground), luminance(background)].sort(
          (a, b) => b - a,
        );
        return ((values[0] as number) + 0.05) / ((values[1] as number) + 0.05);
      };
      const visible = (element: Element): boolean => {
        const style = getComputedStyle(element);
        const rect = element.getBoundingClientRect();
        return (
          style.display !== "none" &&
          style.visibility !== "hidden" &&
          !("disabled" in element && (element as HTMLInputElement).disabled) &&
          Number(style.opacity) > 0 &&
          rect.width > 0 &&
          rect.height > 0
        );
      };
      const background = (
        element: Element | null,
      ): { unsupported: true } | { color: Color } => {
        let color: Color = [255, 255, 255, 1];
        const chain: Element[] = [];
        for (
          let node: Element | null = element;
          node && node instanceof Element;
          node = node.parentElement
        ) {
          chain.push(node);
        }
        for (const node of chain.reverse()) {
          const style = getComputedStyle(node);
          if (style.backgroundImage !== "none" || Number(style.opacity) !== 1) {
            return { unsupported: true };
          }
          const layer = parse(style.backgroundColor);
          if (layer) color = over(layer, color);
        }
        return { color };
      };
      const elements = [...document.querySelectorAll(selector)]
        .filter(visible)
        .slice(0, 8);
      return elements.map((element) => {
        const style = getComputedStyle(element);
        const backdrop = background(
          category === "control-border" ? element.parentElement : element,
        );
        if ("unsupported" in backdrop)
          return { skip: "image-or-opacity-layer" };
        const bg = backdrop.color;
        const fg = parse(style.color);
        if (!fg) return { skip: "unparsed-foreground" };
        const visibleFg = over(fg, bg);
        const fontSize = parseFloat(style.fontSize);
        const large =
          fontSize >= 24 ||
          (fontSize >= 18.66 && Number.parseInt(style.fontWeight, 10) >= 700);
        const entry = {
          ratio: ratio(visibleFg, bg),
          threshold: large ? 3 : 4.5,
          large_text: large,
          target_selector: selectorPath(element),
          foreground: cssColor(visibleFg),
          background: cssColor(bg),
        };
        if (category === "placeholder") {
          const placeholderStyle = getComputedStyle(element, "::placeholder");
          const placeholder = parse(placeholderStyle.color);
          if (!placeholder) return { skip: "unparsed-placeholder" };
          placeholder[3] =
            (placeholder[3] ?? 1) * Number(placeholderStyle.opacity);
          const visiblePlaceholder = over(placeholder, bg);
          entry.ratio = ratio(visiblePlaceholder, bg);
          entry.foreground = cssColor(visiblePlaceholder);
        }
        if (category === "control-border") {
          const border = parse(style.borderTopColor);
          if (
            !border ||
            style.borderTopStyle === "none" ||
            parseFloat(style.borderTopWidth) < 1
          ) {
            return { skip: "no-distinguishing-border" };
          }
          const visibleBorder = over(border, bg);
          entry.ratio = ratio(visibleBorder, bg);
          entry.foreground = cssColor(visibleBorder);
          entry.threshold = 3;
        }
        return entry;
      });
    },
    { category, selector },
  );
  if (!Array.isArray(value)) {
    throw new AcceptanceFailure(
      "browser returned invalid contrast measurements",
    );
  }
  return value.filter(
    (item): item is Rec =>
      item !== null && typeof item === "object" && !Array.isArray(item),
  );
}

async function focusCheck(page: Page): Promise<Rec> {
  const control = page
    .locator(
      "main input:not([type=hidden]):not([type=file]):visible, " +
        "main button:visible, main select:visible, main textarea:visible",
    )
    .first();
  check(
    (await control.count()) === 1,
    "route has no visible focusable control",
  );
  await page.evaluate(() => {
    if (document.activeElement instanceof HTMLElement) {
      document.activeElement.blur();
    }
  });
  for (let i = 0; i < 80; i += 1) {
    if (
      await control.evaluate((element) => document.activeElement === element)
    ) {
      break;
    }
    await page.keyboard.press("Tab");
  }
  check(
    await control.evaluate((element) => document.activeElement === element),
    "Tab traversal did not reach the selected control",
  );
  const probe: unknown = await control.evaluate((element) => {
    type Color = number[];
    const style = getComputedStyle(element);
    const rect = element.getBoundingClientRect();
    const parse = (value: string): Color | null => {
      if (value === "transparent") return [0, 0, 0, 0];
      if (!/^rgba?\(/i.test(value)) return null;
      const parts = value.match(/[\d.]+/g)?.map(Number);
      return parts && parts.length >= 3
        ? [parts[0] ?? 0, parts[1] ?? 0, parts[2] ?? 0, parts[3] ?? 1]
        : null;
    };
    const color = parse(style.outlineColor);
    const parts: string[] = [];
    for (
      let node: Element | null = element;
      node instanceof Element;
      node = node.parentElement
    ) {
      const classes = [...node.classList]
        .slice(0, 3)
        .map((name) => `.${CSS.escape(name)}`)
        .join("");
      parts.unshift(`${node.tagName.toLowerCase()}${classes}`);
      if (node.tagName === "MAIN") break;
    }
    let surface: Color = [255, 255, 255, 1];
    const chain: Element[] = [];
    for (
      let node: Element | null = element.parentElement;
      node && node instanceof Element;
      node = node.parentElement
    ) {
      chain.push(node);
    }
    for (const node of chain.reverse()) {
      const ancestorStyle = getComputedStyle(node);
      if (
        ancestorStyle.backgroundImage !== "none" ||
        Number(ancestorStyle.opacity) !== 1
      ) {
        return { skipped: "unsupported-background" };
      }
      const layer = parse(ancestorStyle.backgroundColor);
      if (layer) {
        const layerAlpha = layer[3] ?? 1;
        const surfaceAlpha = surface[3] ?? 1;
        const alpha = layerAlpha + surfaceAlpha * (1 - layerAlpha);
        const previous: Color = surface;
        surface = [0, 1, 2]
          .map(
            (index) =>
              ((layer[index] ?? 0) * layerAlpha +
                (previous[index] ?? 0) * surfaceAlpha * (1 - layerAlpha)) /
              alpha,
          )
          .concat(alpha);
      }
    }
    if (style.backgroundImage !== "none" || Number(style.opacity) !== 1) {
      return { skipped: "unsupported-background" };
    }
    if (!color || style.outlineStyle === "none") {
      return { skipped: "no-outline" };
    }
    const lum = (c: Color): number =>
      c
        .slice(0, 3)
        .map((v) => v / 255)
        .map((v) => (v <= 0.04045 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4))
        .reduce(
          (sum, v, i) =>
            sum + v * (([0.2126, 0.7152, 0.0722][i] as number) ?? 0),
          0,
        );
    const colorAlpha = color[3] ?? 1;
    const paintedOutline = [0, 1, 2].map(
      (index) =>
        (color[index] ?? 0) * colorAlpha +
        (surface[index] ?? 0) * (1 - colorAlpha),
    );
    const pair = [lum(paintedOutline), lum(surface)].sort((a, b) => b - a);
    return {
      target_selector: parts.join(" > "),
      ratio: ((pair[0] as number) + 0.05) / ((pair[1] as number) + 0.05),
      outline_width_css_px: parseFloat(style.outlineWidth),
      visible:
        rect.width > 0 && rect.height > 0 && element.matches(":focus-visible"),
    };
  });
  if (probe === null || typeof probe !== "object" || Array.isArray(probe)) {
    throw new AcceptanceFailure("keyboard focus indicator is not measurable");
  }
  const source = probe as Rec;
  const result: Rec = {};
  for (const key of [
    "target_selector",
    "ratio",
    "outline_width_css_px",
    "visible",
    "skipped",
  ]) {
    if (key in source) result[key] = source[key];
  }
  return result;
}

function writeReport(reportPath: string, records: Rec[]): void {
  fs.writeFileSync(reportPath, JSON.stringify({ checks: records }), {
    encoding: "utf-8",
    mode: 0o600,
  });
  fs.chmodSync(reportPath, 0o600);
}

function num(value: unknown, fallback: number): number {
  return typeof value === "number" ? value : fallback;
}

async function checkRoute(
  page: Page,
  origin: string,
  route: string,
  label: string,
  theme: string,
  reportPath: string,
  reportRecords: Rec[],
): Promise<Rec[]> {
  const response = await page.goto(`${origin}${route}`, {
    waitUntil: "domcontentloaded",
  });
  check(response !== null && response.status() === 200, "page route failed");
  await page.getByRole("heading", { level: 1 }).first().waitFor({
    state: "visible",
  });
  const loadedSelectors: Record<string, string> = {
    login: ".auth-form",
    projects: ".project-card a.resource-card__link",
    reader: ".reader h1",
    editor: ".project-editor form.editor-form",
    "yarn-editor": ".yarn-form",
    account: ".account-page .profile__email",
    gauge: ".gauge-page form.gauge-form",
  };
  const loadedSelector = loadedSelectors[label];
  check(loadedSelector !== undefined, "page route failed");
  await page.locator(loadedSelector).first().waitFor({
    state: "visible",
    timeout: 10_000,
  });
  if (label === "projects" || label === "reader") {
    await page
      .getByRole("heading", { name: PROJECT_NAME, exact: true })
      .waitFor({ state: "visible", timeout: 10_000 });
  }
  const selectors: [string, string][] = [
    ["heading", "main h1"],
    ["body-text", "main p:not(.field-hint):not([aria-hidden='true'])"],
    ["muted-text", "main .field-hint"],
    ["link-text", "main a"],
    [
      "control-text",
      "main input:not([type=hidden]):not([type=file]):not([type=checkbox]):" +
        "not([type=radio]):not([type=range]), main textarea, main select, " +
        "main button",
    ],
    [
      "placeholder",
      "main input[placeholder]:placeholder-shown:not([type=hidden]):" +
        "not([type=file]):not([type=checkbox]):not([type=radio]):" +
        "not([type=range]), main textarea[placeholder]:placeholder-shown",
    ],
    [
      "control-border",
      "main input:not([type=hidden]):not([type=file]):not([type=checkbox]):" +
        "not([type=radio]):not([type=range]), main textarea, main select",
    ],
  ];
  const records: Rec[] = [];
  const emit = (record: Rec): void => {
    records.push(record);
    reportRecords.push(record);
    writeReport(reportPath, reportRecords);
  };
  const optional = new Set([
    "body-text",
    "muted-text",
    "link-text",
    "placeholder",
    "control-border",
  ]);
  for (const [category, selector] of selectors) {
    const samples = await sample(page, category, selector);
    if (samples.length === 0) {
      if (optional.has(category)) {
        emit({
          route: label,
          theme,
          category,
          skipped: "no-visible-target",
        });
        continue;
      }
      throw new AcceptanceFailure(
        `route ${route} has no sample for ${category}`,
      );
    }
    const assessable = samples.filter((s) => "ratio" in s);
    if (assessable.length === 0) {
      emit({
        route: label,
        theme,
        category,
        skipped: "unsupported-background",
      });
      continue;
    }
    const thresholdOf = (s: Rec): number => num(s.threshold, 4.5);
    const failures = assessable.filter((s) => num(s.ratio, 0) < thresholdOf(s));
    const candidates = failures.length > 0 ? failures : assessable;
    let weakestSample = candidates[0] as Rec;
    for (const candidate of candidates) {
      if (
        num(candidate.ratio, 0) / thresholdOf(candidate) <
        num(weakestSample.ratio, 0) / thresholdOf(weakestSample)
      ) {
        weakestSample = candidate;
      }
    }
    const weakest = num(weakestSample.ratio, 0);
    const threshold = thresholdOf(weakestSample);
    const passed = assessable.every((s) => num(s.ratio, 0) >= thresholdOf(s));
    emit({
      route: label,
      theme,
      category,
      worst_ratio: Math.round(weakest * 100) / 100,
      threshold,
      samples: assessable.length,
      unsupported_samples: samples.length - assessable.length,
      target_selector: weakestSample.target_selector ?? "unknown",
      foreground: weakestSample.foreground ?? "unknown",
      background: weakestSample.background ?? "unknown",
      passed,
    });
  }
  const focus: Rec = {
    route: label,
    theme,
    category: "keyboard-focus",
    ...(await focusCheck(page)),
  };
  if (focus.skipped) {
    emit(focus);
    return records;
  }
  focus.threshold = 3;
  focus.passed =
    focus.visible === true &&
    typeof focus.outline_width_css_px === "number" &&
    focus.outline_width_css_px >= 1 &&
    typeof focus.ratio === "number" &&
    focus.ratio >= 3;
  emit(focus);
  return records;
}

export async function runContrastJourney(
  f: LiveFixture,
  cfg: E2eConfig,
): Promise<Rec[]> {
  const origin = f.origin;
  const stage = stageMarker(f.privateDir);
  const markStage = (value: string): void => stage.mark(value);
  const output: Rec[] = [];
  const reportPath = path.join(f.privateDir, "contrast-results.json");

  markStage("launching Chromium");
  const browser = await launchBrowser(cfg);
  try {
    markStage("creating synthetic browser context");
    const context = await browser.newContext({
      locale: "en-US",
      viewport: { width: 1280, height: 900 },
    });
    try {
      markStage("opening synthetic sign-in page");
      const page = await context.newPage();
      const response = await page.goto(`${origin}/app/login`, {
        waitUntil: "domcontentloaded",
      });
      check(
        response !== null && response.status() === 200,
        "sign-in page failed",
      );
      for (const theme of THEMES) {
        await page.emulateMedia({ colorScheme: theme });
        markStage(`${theme} login`);
        await checkRoute(
          page,
          origin,
          "/app/login",
          "login",
          theme,
          reportPath,
          output,
        );
      }
      markStage("entering synthetic sign-in");
      await page
        .getByLabel("Email", { exact: true })
        .fill(f.env.STRICKNANI_TEST_EMAIL);
      await page
        .getByLabel("Password", { exact: true })
        .fill(f.env.STRICKNANI_TEST_PASSWORD);
      await page.getByRole("button", { name: "Sign in", exact: true }).click();
      markStage("waiting for synthetic Projects route");
      await page.waitForURL("**/app/projects", { timeout: 10_000 });
      const cardLink = page
        .locator(".project-card a.resource-card__link")
        .first();
      await cardLink.waitFor({ state: "visible" });
      const projectPath = await cardLink.getAttribute("href");
      if (!projectPath) {
        throw new AcceptanceFailure("seed project link is missing");
      }
      await page.goto(`${origin}/app/yarns`, { waitUntil: "domcontentloaded" });
      const yarnCard = page.locator(".yarn-card").filter({
        has: page.getByRole("heading", { name: YARN_NAME, exact: true }),
      });
      await yarnCard.waitFor({ state: "visible", timeout: 10_000 });
      const yarnPath = await yarnCard
        .locator("a.resource-card__link")
        .getAttribute("href");
      if (!yarnPath) {
        throw new AcceptanceFailure("seed yarn link is missing");
      }
      const routes: [string, string][] = [
        ["projects", "/app/projects"],
        ["reader", projectPath],
        ["editor", `${projectPath.replace(/\/+$/, "")}/edit`],
        ["yarn-editor", `${yarnPath.replace(/\/+$/, "")}/edit`],
        ["account", "/app/account"],
        ["gauge", "/app/gauge"],
      ];
      for (const theme of THEMES) {
        await page.emulateMedia({ colorScheme: theme });
        for (const [label, route] of routes) {
          markStage(`${theme} ${label}`);
          await checkRoute(
            page,
            origin,
            route,
            label,
            theme,
            reportPath,
            output,
          );
        }
      }
      for (const theme of THEMES) {
        const categories = new Set(
          output
            .filter(
              (record) =>
                record.theme === theme &&
                ("worst_ratio" in record || "ratio" in record),
            )
            .map((record) => String(record.category)),
        );
        const missing = REQUIRED_CATEGORIES.filter(
          (category) => !categories.has(category),
        ).sort();
        check(
          missing.length === 0,
          `theme=${theme} missing assessable categories=${missing.join(",")}`,
        );
      }
      const failures = output.filter((record) => record.passed === false);
      const first = failures[0];
      if (first !== undefined) {
        throw new AcceptanceFailure(
          "contrast below threshold: " +
            `theme=${String(first.theme)}, route=${String(first.route)}, ` +
            `category=${String(first.category)}, ` +
            "selector=" +
            `${String(first.target_selector ?? "not-applicable")}, ` +
            "ratio=" +
            `${String(first.worst_ratio ?? first.ratio ?? 0)}, ` +
            `threshold=${String(first.threshold ?? "3.0")}; ` +
            `additional_failures=${failures.length - 1}`,
        );
      }
    } finally {
      await context.close();
    }
  } finally {
    await closeBrowser(browser);
  }
  console.log(JSON.stringify({ checks: output }));
  return output;
}
