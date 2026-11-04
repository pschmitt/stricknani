// Check for missing or fuzzy translations. Port of scripts/check_translations.py.

import { join } from "node:path";
import { pathToFileURL } from "node:url";

import { compareStr, readPo } from "./catalog.mjs";
import { LOCALES } from "./paths.mjs";

const sorted = (values) => [...values].sort(compareStr);

export function checkTranslations(localesDir = LOCALES) {
  const template = readPo(join(localesDir, "messages.pot"));
  const german = readPo(join(localesDir, "de", "LC_MESSAGES", "messages.po"));

  const templateIds = new Set(
    [...template.messages.values()].filter((m) => m.id).map((m) => m.id),
  );
  const deById = new Map(
    [...german.messages.values()].filter((m) => m.id).map((m) => [m.id, m]),
  );

  const missingIds = sorted([...templateIds].filter((id) => !deById.has(id)));
  const missingStrings = sorted(
    [...deById]
      .filter(([id, m]) => templateIds.has(id) && !m.string.trim())
      .map(([id]) => id),
  );
  const fuzzyStrings = sorted(
    [...deById]
      .filter(([id, m]) => templateIds.has(id) && m.fuzzy)
      .map(([id]) => id),
  );
  const multilineIds = sorted(
    [...templateIds].filter((id) => id.includes("\n")),
  );
  return { missingIds, missingStrings, fuzzyStrings, multilineIds };
}

export function main() {
  const { missingIds, missingStrings, fuzzyStrings, multilineIds } =
    checkTranslations();
  if (!(
    missingIds.length ||
    missingStrings.length ||
    fuzzyStrings.length ||
    multilineIds.length
  )) {
    console.log("Translations look complete.");
    return 0;
  }
  const lines = [];
  if (missingIds.length) {
    lines.push(
      "Missing msgids in de catalog:",
      ...missingIds.map((id) => `  - ${id}`),
    );
  }
  if (missingStrings.length) {
    lines.push(
      "Missing msgstr entries in de catalog:",
      ...missingStrings.map((id) => `  - ${id}`),
    );
  }
  if (fuzzyStrings.length) {
    lines.push(
      "Fuzzy msgstr entries in de catalog:",
      ...fuzzyStrings.map((id) => `  - ${id}`),
    );
  }
  if (multilineIds.length) {
    lines.push(
      "Multiline msgids found (use a single line in templates):",
      ...multilineIds.map(
        (id) =>
          `  - ${Array.from(id.replaceAll("\n", "\\n")).slice(0, 60).join("")}...`,
      ),
    );
  }
  console.log(lines.join("\n"));
  return 1;
}

if (import.meta.url === pathToFileURL(process.argv[1] ?? "").href) {
  process.exitCode = main();
}
