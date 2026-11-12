// Check that the image viewer (lightbox) shows every photo in full.
//
// Runs against the disposable Rust fixture only. A yarn gets a portrait
// (1728x3072), a wide landscape and a square photo on top of the seeded one;
// the viewer then steps through all of them at desktop and phone size. For each
// photo the displayed picture must lie inside the stage and keep the natural
// aspect ratio (no cropping), must not be upscaled beyond its natural size, and
// the stage itself must not change size from one photo to the next.
import { deflateSync } from "node:zlib";
import { mkdirSync } from "node:fs";
import path from "node:path";
import { expect } from "@playwright/test";
import type { Page } from "@playwright/test";
import { AcceptanceFailure, fn } from "../lib/common";
import type { E2eConfig, LiveFixture } from "../lib/harness";
import { launchBrowser } from "../lib/harness";
import { YARN_NAME } from "../lib/seed";
import { dismissPhotoEditor } from "../lib/ui-helpers";

const TOLERANCE_PX = 1.5;
const RATIO_TOLERANCE = 0.01;

const PROFILES = [
  { name: "desktop", width: 1400, height: 900, touch: false },
  { name: "phone", width: 390, height: 844, touch: true },
];

/** Photo sizes: a tall portrait, a wide landscape, a square. */
const PHOTOS = [
  { name: "portrait", width: 1728, height: 3072 },
  { name: "landscape", width: 3200, height: 1200 },
  { name: "square", width: 900, height: 900 },
];

function crc32(buffer: Buffer): number {
  let crc = 0xffffffff;
  for (const byte of buffer) {
    crc ^= byte;
    for (let bit = 0; bit < 8; bit += 1) {
      crc = (crc >>> 1) ^ (0xedb88320 & -(crc & 1));
    }
  }
  return (crc ^ 0xffffffff) >>> 0;
}

function chunk(type: string, data: Buffer): Buffer {
  const head = Buffer.alloc(8);
  head.writeUInt32BE(data.length, 0);
  head.write(type, 4, "ascii");
  const crc = Buffer.alloc(4);
  crc.writeUInt32BE(crc32(Buffer.concat([head.subarray(4), data])), 0);
  return Buffer.concat([head, data, crc]);
}

/**
 * A PNG with a coloured frame, a diagonal and horizontal bands, so a cropped
 * edge is easy to see in a screenshot. Only the extents matter to the checks.
 */
export function syntheticPng(width: number, height: number): Buffer {
  const stride = width * 3 + 1;
  const raw = Buffer.alloc(stride * height);
  const frame = Math.max(8, Math.round(Math.min(width, height) / 40));
  for (let y = 0; y < height; y += 1) {
    const row = y * stride;
    const band = Math.floor((y * 8) / height);
    for (let x = 0; x < width; x += 1) {
      const offset = row + 1 + x * 3;
      let color: [number, number, number] = [
        60 + band * 25,
        120 + ((band * 40) % 100),
        200 - band * 20,
      ];
      if (x < frame || y < frame || x >= width - frame || y >= height - frame) {
        color = y < height / 2 ? [230, 60, 60] : [40, 160, 70];
      } else if (Math.abs(x / width - y / height) < 0.01) {
        color = [250, 250, 250];
      }
      raw[offset] = color[0];
      raw[offset + 1] = color[1];
      raw[offset + 2] = color[2];
    }
  }
  const header = Buffer.alloc(13);
  header.writeUInt32BE(width, 0);
  header.writeUInt32BE(height, 4);
  header[8] = 8;
  header[9] = 2;
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk("IHDR", header),
    chunk("IDAT", deflateSync(raw, { level: 6 })),
    chunk("IEND", Buffer.alloc(0)),
  ]);
}

type Box = { left: number; top: number; width: number; height: number };

type Measure = {
  stage: Box;
  image: Box;
  naturalWidth: number;
  naturalHeight: number;
  src: string;
  complete: boolean;
};

async function measure(page: Page): Promise<Measure> {
  const value: unknown = await page.evaluate(
    fn(`() => {
    const box = element => {
      const r = element.getBoundingClientRect();
      return {left: r.left, top: r.top, width: r.width, height: r.height};
    };
    const dialog = document.querySelector("dialog.lightbox[open]");
    const stage = dialog.querySelector(".zoom-frame");
    const image = dialog.querySelector(".zoom-frame img:not(.placeholder)");
    return {
      stage: box(stage),
      image: box(image),
      naturalWidth: image.naturalWidth,
      naturalHeight: image.naturalHeight,
      src: image.currentSrc,
      complete: image.complete,
    };
  }`),
  );
  return value as Measure;
}

const failures: string[] = [];

/** Record the result; every photo is still measured and shot, the journey fails at the end. */
function check(ok: boolean, label: string, detail: unknown = ""): void {
  const text = `${ok ? "PASS" : "FAIL"} ${label} ${
    typeof detail === "string" ? detail : JSON.stringify(detail)
  }`;
  console.log(text);
  if (!ok) failures.push(text);
}

async function signIn(
  page: Page,
  origin: string,
  email: string,
  password: string,
): Promise<void> {
  await page.goto(`${origin}/app/login`, { waitUntil: "networkidle" });
  await page.getByRole("radio", { name: /^(English|Englisch)$/ }).click();
  await page.locator("#email").fill(email);
  await page.locator("#password").fill(password);
  await page.getByRole("button", { name: "Sign in" }).click();
  await page.waitForURL("**/app/projects");
}

export async function runMediaViewerJourney(
  f: LiveFixture,
  cfg: E2eConfig,
): Promise<void> {
  const { origin } = f;
  const shots = process.env.STRICKNANI_VIEWER_SHOTS ?? "";
  if (shots) mkdirSync(shots, { recursive: true });
  const browser = await launchBrowser(cfg);
  try {
    let yarnId = 0;
    for (const profile of PROFILES) {
      const context = await browser.newContext({
        viewport: { width: profile.width, height: profile.height },
        locale: "en-US",
        colorScheme: "dark",
        hasTouch: profile.touch,
      });
      const page = await context.newPage();
      await signIn(
        page,
        origin,
        f.env.STRICKNANI_TEST_EMAIL,
        f.env.STRICKNANI_TEST_PASSWORD,
      );
      if (!yarnId) {
        await page.goto(`${origin}/app/yarns`, { waitUntil: "networkidle" });
        await page.getByRole("link", { name: YARN_NAME }).first().click();
        await page.waitForURL(/.*\/app\/yarns\/\d+$/);
        yarnId = Number(new URL(page.url()).pathname.split("/").pop());
        await page.goto(`${origin}/app/yarns/${yarnId}/edit`, {
          waitUntil: "networkidle",
        });
        for (const photo of PHOTOS) {
          await page.getByLabel("Choose a yarn photo").setInputFiles({
            name: `${photo.name}.png`,
            mimeType: "image/png",
            buffer: syntheticPng(photo.width, photo.height),
          });
          await page
            .getByRole("status")
            .filter({ hasText: "Photo uploaded." })
            .first()
            .waitFor({ state: "visible", timeout: 60_000 });
          await dismissPhotoEditor(page);
        }
      }

      await page.goto(`${origin}/app/yarns/${yarnId}`, {
        waitUntil: "networkidle",
      });
      await page.locator(".photo-strip__open").first().click();
      const dialog = page.getByRole("dialog", { name: "Project image viewer" });
      await dialog.waitFor({ state: "visible" });
      const count = await dialog.locator("figcaption span").innerText();
      const total = Number(count.split("/")[1]);
      check(total >= 4, `${profile.name}: gallery holds all photos`, count);

      let referenceStage: Box | null = null;
      const seenRatios: number[] = [];
      for (let index = 0; index < total; index += 1) {
        const photo = dialog.locator(".zoom-frame img:not(.placeholder)");
        await expect(photo).toBeVisible();
        await expect
          .poll(
            async () =>
              photo.evaluate(fn("img => img.complete && img.naturalWidth > 0")),
            { timeout: 15_000 },
          )
          .toBe(true);
        await photo.evaluate(fn("img => img.decode()")).catch(() => undefined);
        // Let the 120 ms fade-in and any resize settle before measuring.
        await page.waitForTimeout(300);
        const m = await measure(page);
        const tag = `${profile.name} photo ${index + 1}/${total}`;
        check(
          m.complete && m.naturalWidth > 0 && !m.src.includes("/thumbnails/"),
          `${tag}: the full image (not the thumbnail) is loaded`,
          m.src,
        );
        const { stage, image } = m;
        check(
          image.left >= stage.left - TOLERANCE_PX &&
            image.top >= stage.top - TOLERANCE_PX &&
            image.left + image.width <=
              stage.left + stage.width + TOLERANCE_PX &&
            image.top + image.height <= stage.top + stage.height + TOLERANCE_PX,
          `${tag}: the picture lies inside the stage`,
          { stage, image },
        );
        const naturalRatio = m.naturalWidth / m.naturalHeight;
        const shownRatio = image.width / image.height;
        check(
          Math.abs(shownRatio / naturalRatio - 1) <= RATIO_TOLERANCE,
          `${tag}: the displayed box keeps the natural aspect ratio`,
          { naturalRatio, shownRatio },
        );
        const fit = Math.min(
          1,
          stage.width / m.naturalWidth,
          stage.height / m.naturalHeight,
        );
        check(
          Math.abs(image.width - m.naturalWidth * fit) <= TOLERANCE_PX &&
            Math.abs(image.height - m.naturalHeight * fit) <= TOLERANCE_PX,
          `${tag}: shown as large as fits, never above natural size`,
          { fit, image, natural: [m.naturalWidth, m.naturalHeight] },
        );
        if (referenceStage === null) referenceStage = stage;
        check(
          Math.abs(stage.width - referenceStage.width) <= 0.5 &&
            Math.abs(stage.height - referenceStage.height) <= 0.5,
          `${tag}: the stage keeps its size between photos`,
          { stage, referenceStage },
        );
        check(
          stage.top >= 0 &&
            stage.top + stage.height <= profile.height &&
            stage.left >= 0 &&
            stage.left + stage.width <= profile.width,
          `${tag}: the stage fits the viewport`,
          stage,
        );
        seenRatios.push(naturalRatio);
        if (shots) {
          await dialog.screenshot({
            path: path.join(shots, `${profile.name}-${index + 1}.png`),
          });
        }
        if (index < total - 1) {
          await page.keyboard.press("ArrowRight");
          await expect(dialog.locator("figcaption span")).toHaveText(
            `${index + 2} / ${total}`,
          );
        }
      }
      check(
        seenRatios.some((r) => r < 0.7) &&
          seenRatios.some((r) => r > 2) &&
          seenRatios.some((r) => Math.abs(r - 1) < 0.01),
        `${profile.name}: portrait, landscape and square were all checked`,
        seenRatios,
      );
      await context.close();
    }
  } finally {
    await browser.close();
  }
  if (failures.length > 0) {
    throw new AcceptanceFailure(
      `${failures.length} viewer check(s) failed:\n${failures.join("\n")}`,
    );
  }
}
