import {
  mkdirSync,
  mkdtempSync,
  readFileSync,
  rmSync,
  writeFileSync,
} from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterEach, beforeEach, describe, expect, it } from "vitest";

// The i18n tooling is plain Node ESM (no declarations needed for the tests).
// @ts-ignore -- untyped .mjs module
import * as catalogLib from "../../../scripts/i18n/catalog.mjs";
// @ts-ignore -- untyped .mjs module
import * as web from "../../../scripts/i18n/generate-web.mjs";
// @ts-ignore -- untyped .mjs module
import * as babel from "../../../scripts/i18n/babel.mjs";

const { Catalog, readPo, writePo } = catalogLib;
const {
  ExtractionError,
  checkRuntimeCatalog,
  extractMessages,
  generateRuntimeCatalog,
  mergeIntoPot,
  renderCatalog,
  updatePoFiles,
} = web;

type Entries = Record<string, [string, string[]]>;

let dir = "";
beforeEach(() => {
  dir = mkdtempSync(join(tmpdir(), "stricknani-i18n-"));
});
afterEach(() => {
  rmSync(dir, { recursive: true, force: true });
});

function writeCatalog(path: string, locale: string, entries: Entries): void {
  const catalog = new Catalog({ locale, charset: "utf-8" });
  for (const [id, [string, flags]] of Object.entries(entries)) {
    catalog.add(id, { string, flags });
  }
  writePo(path, catalog);
}

describe("web i18n extraction", () => {
  it("extracts literal messages and decodes JavaScript escapes", () => {
    const source = String.raw`function msg(key: string): string { return key; }
const fake = "msg('quoted fake')";
// msg('comment fake')
const single = msg('Can\'t open \\ this');
const double = t("A \"quoted\" label", locale);
const dynamic = t(item.label, locale);
`;
    expect(Object.fromEntries(extractMessages(source, "sample.ts"))).toEqual({
      'A "quoted" label': [5],
      "Can't open \\ this": [4],
    });
  });

  it("rejects dynamic msg keys but allows dynamic t keys", () => {
    expect(() => extractMessages("msg(item.label);", "sample.svelte")).toThrow(
      /msg\(\) requires a literal/,
    );
    expect(() => extractMessages("msg(item.label);", "sample.svelte")).toThrow(
      ExtractionError,
    );
    expect(extractMessages("t(item.label, locale);", "sample.ts").size).toBe(0);
  });
});

describe("web i18n catalogs", () => {
  it("blocks catalog generation on fuzzy or missing translations", () => {
    const references = new Map([
      ["Open projects", [["web/src/page.svelte", 5]]],
    ]);
    const englishPo = join(dir, "en.po");
    const germanPo = join(dir, "de.po");
    const output = join(dir, "messages.json");

    writeCatalog(englishPo, "en", { "Open projects": ["Open projects", []] });
    writeCatalog(germanPo, "de", {
      "Open projects": ["Projekte öffnen", ["fuzzy"]],
    });
    expect(
      generateRuntimeCatalog(references, englishPo, germanPo, output),
    ).toEqual(["de: fuzzy translation for 'Open projects'"]);
    expect(() => readFileSync(output)).toThrow();

    writeCatalog(germanPo, "de", {});
    expect(
      checkRuntimeCatalog(references, englishPo, germanPo, output)[1],
    ).toEqual(["de: missing msgid 'Open projects'"]);

    writeCatalog(germanPo, "de", { "Open projects": ["", []] });
    expect(
      checkRuntimeCatalog(references, englishPo, germanPo, output)[1],
    ).toEqual(["de: empty translation for 'Open projects'"]);
  });

  it("preserves existing translations and adds blank German entries", () => {
    const englishPo = join(dir, "en.po");
    const germanPo = join(dir, "de.po");
    writeCatalog(englishPo, "en", { Projects: ["Project library", []] });
    writeCatalog(germanPo, "de", { Projects: ["Projektübersicht", []] });
    const references = new Map([
      ["Projects", [["web/src/page.svelte", 4]]],
      ["Save changes", [["web/src/edit.svelte", 9]]],
    ]);

    expect(updatePoFiles(references, englishPo, germanPo)).toEqual([1, 1]);
    const english = readPo(englishPo);
    const german = readPo(germanPo);
    expect(english.get("Projects").string).toBe("Project library");
    expect(german.get("Projects").string).toBe("Projektübersicht");
    expect(english.get("Save changes").string).toBe("Save changes");
    expect(german.get("Save changes").string).toBe("");
  });

  it("generates a stable catalog and detects stale output", () => {
    const references = new Map([
      ["Yarns", [["web/src/yarns.svelte", 3]]],
      ["Projects", [["web/src/projects.svelte", 2]]],
    ]);
    const englishPo = join(dir, "en.po");
    const germanPo = join(dir, "de.po");
    const output = join(dir, "messages.json");
    writeCatalog(englishPo, "en", {
      Yarns: ["Yarns", []],
      Projects: ["Projects", []],
    });
    writeCatalog(germanPo, "de", {
      Yarns: ["Garne", []],
      Projects: ["Projekte", []],
    });

    expect(
      generateRuntimeCatalog(references, englishPo, germanPo, output),
    ).toEqual([]);
    expect(
      checkRuntimeCatalog(references, englishPo, germanPo, output),
    ).toEqual([true, []]);
    expect(readFileSync(output, "utf8")).toBe(
      renderCatalog({
        en: { Projects: "Projects", Yarns: "Yarns" },
        de: { Projects: "Projekte", Yarns: "Garne" },
      }),
    );
    // Same layout as json.dumps(..., indent=2, sort_keys=True) + "\n".
    expect(readFileSync(output, "utf8")).toBe(
      '{\n  "de": {\n    "Projects": "Projekte",\n    "Yarns": "Garne"\n  },\n' +
        '  "en": {\n    "Projects": "Projects",\n    "Yarns": "Yarns"\n  }\n}\n',
    );

    writeFileSync(output, JSON.stringify({ en: {}, de: {} }), "utf8");
    expect(
      checkRuntimeCatalog(references, englishPo, germanPo, output),
    ).toEqual([false, []]);
  });

  it("keeps translations through the POT merge and update", () => {
    const potPath = join(dir, "messages.pot");
    const pot = new Catalog({ charset: "utf-8" });
    pot.add("Existing web label");
    writePo(potPath, pot);

    const references = new Map([
      ["Existing web label", [["web/src/layout.svelte", 4]]],
      ["New web label", [["web/src/page.svelte", 9]]],
    ]);
    expect(mergeIntoPot(references, potPath)).toBe(2);

    const locales = join(dir, "locales");
    const germanPo = join(locales, "de", "LC_MESSAGES", "messages.po");
    mkdirSync(join(locales, "de", "LC_MESSAGES"), { recursive: true });
    writeCatalog(germanPo, "de", {
      "Existing web label": ["Bestehender Webtext", []],
    });
    babel.updateCatalogs(potPath, locales);

    const updated = readPo(germanPo);
    expect(updated.get("Existing web label").string).toBe(
      "Bestehender Webtext",
    );
    // difflib fuzzy matching, like `pybabel update`.
    expect(updated.get("New web label").string).toBe("Bestehender Webtext");
    expect(updated.get("New web label").fuzzy).toBe(true);
  });
});
