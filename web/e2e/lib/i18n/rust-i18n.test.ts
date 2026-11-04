import { mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterEach, beforeEach, describe, expect, it } from "vitest";

// @ts-ignore -- untyped .mjs module
import * as catalogLib from "../../../scripts/i18n/catalog.mjs";
// @ts-ignore -- untyped .mjs module
import * as rust from "../../../scripts/i18n/extract-rust.mjs";
// @ts-ignore -- untyped .mjs module
import * as generate from "../../../scripts/i18n/generate-rust.mjs";
// @ts-ignore -- untyped .mjs module
import * as paths from "../../../scripts/i18n/paths.mjs";

const { Catalog, readPo, writePo } = catalogLib;
const {
  rustLabelPairs,
  rustLabelPairsForTree,
  rustLabelPairsFromFiles,
  rustSourceFiles,
} = rust;
const { germanTranslations, seedGermanCatalog } = generate;
const { GERMAN_PO } = paths;

let dir = "";
beforeEach(() => {
  dir = mkdtempSync(join(tmpdir(), "stricknani-i18n-"));
});
afterEach(() => {
  rmSync(dir, { recursive: true, force: true });
});

describe("Rust i18n extraction", () => {
  it("skips the language code pair", () => {
    const labels = rustLabelPairs(
      'let language = tr("en", "de");\n' +
        'let title = tr(\n "Gauge calculator",\n "Maschenprobe berechnen"\n);',
    );
    expect(Object.fromEntries(labels)).toEqual({
      "Gauge calculator": "Maschenprobe berechnen",
    });
  });

  it("rejects conflicting pairs", () => {
    expect(() =>
      rustLabelPairs('tr("Yarn", "Garn"); tr("Yarn", "Wolle");'),
    ).toThrow(/conflicting Rust translations/);
  });

  it("has German catalog entries for every extracted label", () => {
    const labels = rustLabelPairsForTree();
    const translations = germanTranslations(GERMAN_PO);
    expect(labels.size).toBeGreaterThan(0);
    for (const msgid of labels.keys()) {
      expect(translations.has(msgid)).toBe(true);
    }
  });

  it("scans the whole source tree", () => {
    const names = new Set(
      rustSourceFiles().map((path: string) => path.split("/").pop()),
    );
    for (const name of ["web.rs", "lib.rs", "mod.rs"]) {
      expect(names.has(name)).toBe(true);
    }
  });

  it("rejects cross-file fallback conflicts", () => {
    const first = join(dir, "one.rs");
    const second = join(dir, "two.rs");
    writeFileSync(first, 'tr("Shared label", "Erste Übersetzung");', "utf8");
    writeFileSync(second, 'tr("Shared label", "Zweite Übersetzung");', "utf8");
    expect(() => rustLabelPairsFromFiles([first, second])).toThrow(
      /conflicting Rust translations/,
    );
  });

  it("evaluates literals like Python and rejects Rust-only escapes", () => {
    expect(
      Object.fromEntries(
        rustLabelPairs('tr("A\\tB \\"q\\" \\u00e4", "x\\\\y");'),
      ),
    ).toEqual({ 'A\tB "q" ä': "x\\y" });
    expect(() => rustLabelPairs('tr("Smile \\u{1F600}", "x");')).toThrow();
  });
});

describe("German seeding", () => {
  it("keeps existing translations and fills blank ones", () => {
    const source = join(dir, "web.rs");
    writeFileSync(
      source,
      'tr("Projects", "Projekte"); tr("Yarns", "Garne");',
      "utf8",
    );
    const catalogPath = join(dir, "messages.po");
    const catalog = new Catalog({ locale: "de" });
    catalog.add("Projects", { string: "Projektliste" });
    catalog.add("Yarns", { string: "" });
    writePo(catalogPath, catalog);

    expect(seedGermanCatalog(source, catalogPath)).toBe(1);

    const updated = readPo(catalogPath);
    expect(updated.get("Projects").string).toBe("Projektliste");
    expect(updated.get("Yarns").string).toBe("Garne");
  });
});
