// The Babel steps of the justfile i18n recipes, without Python.
//
//   node babel.mjs extract [-o POT]       write the (empty) POT template
//   node babel.mjs update [-i POT] [-d DIR] [--no-fuzzy-matching]
//                                         merge the POT into every PO catalog
//   node babel.mjs compile                no-op (see below)
//
// `extract` mirrors `pybabel extract -F babel.cfg -o messages.pot scripts`:
// babel.cfg has no gettext sources left, so Babel only wrote the header-only
// template that the Rust and web extractors then fill. `update` mirrors
// `pybabel update -i messages.pot -d stricknani/locales` (fuzzy matching and
// obsolete entries included). `compile` wrote `.mo` files; they are
// git-ignored and nothing in the repository loads them, so it is not ported.

import { existsSync, readdirSync, statSync } from "node:fs";
import { join } from "node:path";
import { parseArgs } from "node:util";
import { pathToFileURL } from "node:url";

import { Catalog, readPo, writePo } from "./catalog.mjs";
import { LOCALES, POT_PATH } from "./paths.mjs";

/**
 * Write the header-only template. An existing template's header (comment and
 * POT-Creation-Date) is kept so re-running on an unchanged tree produces no
 * diff; pass `refreshDate` to behave like Babel and stamp the current time.
 */
/** @param {string} [potPath] @param {{ refreshDate?: boolean }} [options] */
export function extractTemplate(
  potPath = POT_PATH,
  { refreshDate = false } = {},
) {
  const catalog =
    existsSync(potPath) && !refreshDate ? readPo(potPath) : new Catalog();
  catalog.messages.clear();
  catalog.obsolete.clear();
  writePo(potPath, catalog);
}

/** @param {string} [inputFile] @param {string} [outputDir] @param {{ noFuzzyMatching?: boolean, domain?: string }} [options] */
export function updateCatalogs(
  inputFile = POT_PATH,
  outputDir = LOCALES,
  { noFuzzyMatching = false, domain = "messages" } = {},
) {
  const template = readPo(inputFile);
  const updated = [];
  for (const name of readdirSync(outputDir).sort()) {
    const poFile = join(outputDir, name, "LC_MESSAGES", `${domain}.po`);
    if (!statSync(join(outputDir, name)).isDirectory() || !existsSync(poFile)) {
      continue;
    }
    const catalog = readPo(poFile, { locale: name });
    catalog.update(template, { noFuzzyMatching });
    writePo(poFile, catalog);
    updated.push(poFile);
  }
  if (!updated.length) {
    throw new Error(`no message catalogs found for domain '${domain}'`);
  }
  return updated;
}

export function main(argv = process.argv.slice(2)) {
  const [command, ...rest] = argv;
  const { values } = parseArgs({
    args: rest,
    options: {
      "output-file": { type: "string", short: "o" },
      "input-file": { type: "string", short: "i" },
      "output-dir": { type: "string", short: "d" },
      "no-fuzzy-matching": { type: "boolean", default: false },
      "refresh-pot-date": { type: "boolean", default: false },
    },
  });
  try {
    if (command === "extract") {
      extractTemplate(values["output-file"] ?? POT_PATH, {
        refreshDate: values["refresh-pot-date"],
      });
    } else if (command === "update") {
      updateCatalogs(
        values["input-file"] ?? POT_PATH,
        values["output-dir"] ?? LOCALES,
        {
          noFuzzyMatching: values["no-fuzzy-matching"],
        },
      );
    } else if (command === "compile") {
      console.log(
        "compile: .mo catalogs are not used by this repository; nothing to do.",
      );
    } else {
      console.error("usage: babel.mjs extract|update|compile [options]");
      return 2;
    }
  } catch (error) {
    console.error(`babel ${command} failed: ${error.message}`);
    return 1;
  }
  return 0;
}

if (import.meta.url === pathToFileURL(process.argv[1] ?? "").href) {
  process.exitCode = main();
}
