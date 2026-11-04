// Seed missing German Rust labels and generate the runtime JSON catalog.
// Port of scripts/generate_rust_i18n.py.
//
// Usage: node generate-rust.mjs [--seed-german] [--check]

import { existsSync } from "node:fs";
import { parseArgs } from "node:util";
import { pathToFileURL } from "node:url";

import { readPo, readText, writePo, writeText } from "./catalog.mjs";
import { rustLabelPairsForTree } from "./extract-rust.mjs";
import { renderJson } from "./json-catalog.mjs";
import { GERMAN_PO, RUST_RUNTIME_CATALOG, RUST_SOURCE } from "./paths.mjs";

/** Fill blank/fuzzy German entries with Rust text; keep reviewed ones. */
export function seedGermanCatalog(
  rustSource = RUST_SOURCE,
  germanPo = GERMAN_PO,
) {
  const labels = rustLabelPairsForTree(rustSource);
  const catalog = readPo(germanPo);
  let changed = 0;
  for (const [msgid, fallback] of labels) {
    const message = catalog.get(msgid);
    if (!message) {
      catalog.add(msgid, { string: fallback });
      changed += 1;
      continue;
    }
    let updated = false;
    if (!message.string || !message.string.trim() || message.fuzzy) {
      // Fuzzy matches are suggestions from another msgid, not a reviewed
      // translation; the source's explicit German pair is authoritative.
      message.string = fallback;
      updated = true;
    }
    if (message.flags.has("fuzzy")) {
      message.flags.delete("fuzzy");
      updated = true;
    }
    changed += Number(updated);
  }
  writePo(germanPo, catalog);
  return changed;
}

/** msgid -> msgstr for entries that are non-empty and not fuzzy. */
export function germanTranslations(germanPo) {
  const catalog = readPo(germanPo);
  const translations = new Map();
  for (const message of catalog.messages.values()) {
    if (message.id && message.string.trim() && !message.fuzzy) {
      translations.set(message.id, message.string);
    }
  }
  return translations;
}

export function rustTranslations(rustSource, germanPo) {
  const labels = rustLabelPairsForTree(rustSource);
  const catalog = germanTranslations(germanPo);
  return new Map(
    [...labels].map(([msgid, fallback]) => [
      msgid,
      catalog.get(msgid) ?? fallback,
    ]),
  );
}

export function generateRuntimeCatalog(
  germanPo = GERMAN_PO,
  outputPath = RUST_RUNTIME_CATALOG,
  rustSource = RUST_SOURCE,
) {
  const translations = rustTranslations(rustSource, germanPo);
  writeText(outputPath, renderJson({ de: Object.fromEntries(translations) }));
}

export function checkRuntimeCatalog(
  germanPo = GERMAN_PO,
  outputPath = RUST_RUNTIME_CATALOG,
  rustSource = RUST_SOURCE,
) {
  const labels = rustLabelPairsForTree(rustSource);
  const translations = germanTranslations(germanPo);
  for (const msgid of labels.keys()) {
    if (!translations.has(msgid)) {
      return false;
    }
  }
  const expected = renderJson({
    de: Object.fromEntries(
      [...labels.keys()].map((msgid) => [msgid, translations.get(msgid)]),
    ),
  });
  return existsSync(outputPath) && readText(outputPath) === expected;
}

export function main(argv = process.argv.slice(2)) {
  const { values } = parseArgs({
    args: argv,
    options: {
      "seed-german": { type: "boolean", default: false },
      check: { type: "boolean", default: false },
    },
  });
  try {
    if (values["seed-german"]) {
      const changed = seedGermanCatalog();
      console.log(`Updated ${changed} German Rust catalog entries.`);
    }
    if (values.check) {
      if (checkRuntimeCatalog()) {
        console.log("Rust runtime catalog is current.");
        return 0;
      }
      console.error("Rust catalog is stale; run `just i18n-update`.");
      return 1;
    }
    generateRuntimeCatalog();
  } catch (error) {
    console.error(`Rust i18n catalog generation failed: ${error.message}`);
    return 1;
  }
  return 0;
}

if (import.meta.url === pathToFileURL(process.argv[1] ?? "").href) {
  process.exitCode = main();
}
