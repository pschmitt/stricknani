import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";

// @ts-ignore -- untyped .mjs module
import * as catalogLib from "../../../scripts/i18n/catalog.mjs";
// @ts-ignore -- untyped .mjs module
import * as paths from "../../../scripts/i18n/paths.mjs";

const {
  Catalog,
  getCloseMatches,
  normalizePo,
  parsePo,
  readPo,
  renderPo,
  wrapText,
} = catalogLib;
const { ENGLISH_PO, GERMAN_PO, POT_PATH } = paths;

describe("PO writer", () => {
  it.each([
    ["template", POT_PATH, null],
    ["English catalog", ENGLISH_PO, "en"],
    ["German catalog", GERMAN_PO, "de"],
  ])("reproduces the committed %s byte for byte", (_name, path, locale) => {
    expect(renderPo(readPo(path, { locale }))).toBe(readFileSync(path, "utf8"));
  });

  it("wraps msgids like Babel (76 columns, hyphen aware)", () => {
    const text =
      "A very long message with well-known hyphenated words such as mid-row, " +
      "short-row and cast-on, which must wrap";
    expect(normalizePo(text)).toBe(
      '""\n"A very long message with well-known hyphenated words such as mid-row, "\n' +
        '"short-row and cast-on, which must wrap"',
    );
    expect(normalizePo("Line one\nLine two\n")).toBe(
      '""\n"Line one\\n"\n"Line two\\n"',
    );
    expect(normalizePo('single\\ "q"')).toBe('"single\\\\ \\"q\\""');
  });

  it("wraps location comments at 76 columns without splitting names", () => {
    expect(
      wrapText(
        "rust/src/web.rs:2156 rust/src/web_accounts.rs:844 rust/src/web_audit.rs:129 web/src/a.svelte:1",
        76,
      ),
    ).toEqual([
      "rust/src/web.rs:2156 rust/src/web_accounts.rs:844 rust/src/web_audit.rs:129",
      "web/src/a.svelte:1",
    ]);
  });

  it("derives python-format flags like Babel", () => {
    const catalog = new Catalog({ locale: "en" });
    catalog.add("Hello {name}!");
    catalog.add("100% sure");
    catalog.add("Plain");
    const text = renderPo(catalog);
    expect(text).toContain('#, python-brace-format\nmsgid "Hello {name}!"');
    expect(text).toContain('#, python-format\nmsgid "100% sure"');
    expect(text).not.toContain('#, python-format\nmsgid "Plain"');
  });

  it("round-trips obsolete entries and sorts locations", () => {
    const po = parsePo(
      [
        'msgid ""',
        'msgstr ""',
        '"Language: de\\n"',
        "",
        "#: b.svelte:2 a.svelte:10 a.svelte:2",
        'msgid "X"',
        'msgstr "Y"',
        "",
        '#~ msgid "Old"',
        '#~ msgstr "Alt"',
        "",
      ].join("\n"),
    );
    const out = renderPo(po);
    expect(out).toContain('#: a.svelte:2 a.svelte:10 b.svelte:2\nmsgid "X"');
    expect(out.endsWith('#~ msgid "Old"\n#~ msgstr "Alt"\n')).toBe(true);
    expect(out.endsWith("\n\n")).toBe(false);
  });
});

describe("difflib fuzzy matching", () => {
  it("matches like difflib.get_close_matches(n=1, cutoff=0.6)", () => {
    expect(
      getCloseMatches("new web label", ["existing web label", "zzz"]),
    ).toEqual(["existing web label"]);
    expect(getCloseMatches("abc", ["xyz"])).toEqual([]);
    // Equal scores: the greater string wins (max over (score, string)).
    expect(getCloseMatches("ab", ["ab", "ab"])).toEqual(["ab"]);
  });
});
