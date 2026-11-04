// Extract literal browser UI messages and build the public locale catalog.
// Port of scripts/generate_web_i18n.py.
//
// Usage: node generate-web.mjs [--extract | --update | --check]

import { existsSync } from "node:fs";
import { parseArgs } from "node:util";
import { pathToFileURL } from "node:url";

import {
  Catalog,
  compareStr,
  readPo,
  readText,
  writePo,
  writeText,
} from "./catalog.mjs";
import { sortedLocationUnion } from "./extract-rust.mjs";
import { renderJson } from "./json-catalog.mjs";
import {
  ENGLISH_PO,
  GERMAN_PO,
  POT_PATH,
  WEB_RUNTIME_CATALOG,
  WEB_SOURCE,
  comparePaths,
  relativeToRoot,
  walkFiles,
} from "./paths.mjs";

export class ExtractionError extends Error {}

const SOURCE_SUFFIXES = new Set([".svelte", ".ts", ".js"]);

/** Python `PurePath.suffix`. */
function pathSuffix(path) {
  const name = path.split("/").pop().split("\\").pop();
  const dot = name.lastIndexOf(".");
  return dot > 0 && dot < name.length - 1 ? name.slice(dot) : "";
}

/** Svelte and TypeScript sources in stable path order. */
export function sourceFiles(sourceDir = WEB_SOURCE) {
  return walkFiles(sourceDir)
    .filter((path) => SOURCE_SUFFIXES.has(pathSuffix(path)))
    .sort(comparePaths);
}

const isAlpha = (c) => /^\p{L}$/u.test(c);
const isAlnum = (c) => /^[\p{L}\p{N}]$/u.test(c);
const isSpace = (c) =>
  (/^\s$/.test(c) && c !== "﻿") || (c >= "\x1c" && c <= "\x1f");
const identifierStart = (c) => isAlpha(c) || c === "_" || c === "$";
const identifierContinue = (c) => isAlnum(c) || c === "_" || c === "$";

function codepoint(digits, expectedLength, filename) {
  if (
    digits.length !== expectedLength ||
    !digits ||
    !/^[0-9a-fA-F]+$/.test(digits)
  ) {
    throw new ExtractionError(`${filename}: invalid Unicode escape`);
  }
  const value = parseInt(digits, 16);
  if (value > 0x10ffff) {
    throw new ExtractionError(`${filename}: invalid Unicode escape`);
  }
  return String.fromCodePoint(value);
}

/** Read a quoted literal at `start`; returns [decoded text, next index]. */
function readQuoted(source, start, filename) {
  const quote = source[start];
  if (quote === "`") {
    let index = start + 1;
    while (index < source.length) {
      if (source[index] === "\\") {
        index += 2;
      } else if (source[index] === "`") {
        return ["", index + 1];
      } else {
        index += 1;
      }
    }
    throw new ExtractionError(`${filename}: unterminated template literal`);
  }

  let result = "";
  let index = start + 1;
  const escapes = {
    b: "\b",
    f: "\f",
    n: "\n",
    r: "\r",
    t: "\t",
    v: "\v",
    0: "\0",
  };
  while (index < source.length) {
    const char = source[index];
    if (char === quote) {
      return [result, index + 1];
    }
    if (char === "\r" || char === "\n") {
      throw new ExtractionError(
        `${filename}: newline in quoted translation key`,
      );
    }
    if (char !== "\\") {
      result += char;
      index += 1;
      continue;
    }
    index += 1;
    if (index >= source.length) {
      break;
    }
    const escaped = source[index];
    if (Object.hasOwn(escapes, escaped)) {
      result += escapes[escaped];
      index += 1;
    } else if (escaped === "'" || escaped === '"' || escaped === "\\") {
      result += escaped;
      index += 1;
    } else if (escaped === "x") {
      result += codepoint(source.slice(index + 1, index + 3), 2, filename);
      index += 3;
    } else if (escaped === "u") {
      if (index + 1 < source.length && source[index + 1] === "{") {
        const closing = source.indexOf("}", index + 2);
        if (closing === -1) {
          throw new ExtractionError(`${filename}: invalid Unicode escape`);
        }
        const digits = source.slice(index + 2, closing);
        result += codepoint(digits, digits.length, filename);
        index = closing + 1;
      } else {
        result += codepoint(source.slice(index + 1, index + 5), 4, filename);
        index += 5;
      }
    } else if (escaped === "\n") {
      index += 1;
    } else if (
      escaped === "\r" &&
      index + 1 < source.length &&
      source[index + 1] === "\n"
    ) {
      index += 2;
    } else {
      // JavaScript treats unknown escapes as the escaped character itself.
      result += escaped;
      index += 1;
    }
  }
  throw new ExtractionError(`${filename}: unterminated quoted translation key`);
}

function isFunctionDeclaration(source, start) {
  const prefix = source.slice(Math.max(0, start - 24), start);
  const stripped = prefix.replace(/\s+$/, "");
  return (
    stripped.endsWith("function") &&
    (stripped.length === "function".length ||
      !identifierContinue(stripped[stripped.length - "function".length - 1]))
  );
}

const lineAt = (source, index) =>
  (source.slice(0, index).match(/\n/g) ?? []).length + 1;

/**
 * Extract literal `t` and `msg` keys; allow dynamic `t` lookups only.
 * Returns a Map of message -> line numbers.
 */
export function extractMessages(source, filename = "<source>") {
  const messages = new Map();
  let index = 0;
  while (index < source.length) {
    if (source.startsWith("//", index)) {
      const newline = source.indexOf("\n", index + 2);
      index = newline === -1 ? source.length : newline + 1;
      continue;
    }
    if (source.startsWith("/*", index)) {
      const end = source.indexOf("*/", index + 2);
      if (end === -1) {
        throw new ExtractionError(`${filename}: unterminated block comment`);
      }
      index = end + 2;
      continue;
    }
    const char = source[index];
    if (char === "'" || char === '"' || char === "`") {
      [, index] = readQuoted(source, index, filename);
      continue;
    }
    if (!identifierStart(char)) {
      index += 1;
      continue;
    }

    const start = index;
    index += 1;
    while (index < source.length && identifierContinue(source[index])) {
      index += 1;
    }
    const name = source.slice(start, index);
    if (name !== "t" && name !== "msg") {
      continue;
    }
    if (name === "msg" && isFunctionDeclaration(source, start)) {
      continue;
    }
    let openParen = index;
    while (openParen < source.length && isSpace(source[openParen])) {
      openParen += 1;
    }
    if (openParen >= source.length || source[openParen] !== "(") {
      continue;
    }
    let argument = openParen + 1;
    while (argument < source.length && isSpace(source[argument])) {
      argument += 1;
    }
    if (
      argument >= source.length ||
      (source[argument] !== "'" && source[argument] !== '"')
    ) {
      if (name === "msg") {
        throw new ExtractionError(
          `${filename}:${lineAt(source, start)}: msg() requires a literal English key`,
        );
      }
      continue;
    }

    const [message, afterLiteral] = readQuoted(source, argument, filename);
    let endArgument = afterLiteral;
    while (endArgument < source.length && isSpace(source[endArgument])) {
      endArgument += 1;
    }
    if (
      endArgument < source.length &&
      source[endArgument] !== "," &&
      source[endArgument] !== ")"
    ) {
      if (name === "msg") {
        throw new ExtractionError(
          `${filename}:${lineAt(source, start)}: msg() requires a literal English key`,
        );
      }
      continue;
    }
    if (!message.trim()) {
      throw new ExtractionError(
        `${filename}:${lineAt(source, start)}: translation key is empty`,
      );
    }
    if (!messages.has(message)) {
      messages.set(message, []);
    }
    messages.get(message).push(lineAt(source, start));
    index = afterLiteral;
  }
  return messages;
}

/** All literal messages with sorted source locations: Map msgid -> [[file, line]]. */
export function extractTree(sourceDir = WEB_SOURCE) {
  const references = new Map();
  for (const sourcePath of sourceFiles(sourceDir)) {
    const relative = relativeToRoot(sourcePath);
    for (const [message, lines] of extractMessages(
      readText(sourcePath),
      relative,
    )) {
      if (!references.has(message)) {
        references.set(message, []);
      }
      references.get(message).push(...lines.map((line) => [relative, line]));
    }
  }
  return new Map(
    [...references.keys()]
      .sort(compareStr)
      .map((message) => [
        message,
        sortedLocationUnion(references.get(message), []),
      ]),
  );
}

function loadCatalog(path, locale) {
  if (!existsSync(path)) {
    return new Catalog({ locale, charset: "utf-8" });
  }
  return readPo(path);
}

/** Merge browser message ids and source locations into the POT. */
export function mergeIntoPot(references, potPath = POT_PATH) {
  const catalog = loadCatalog(potPath, "en");
  for (const [messageId, locations] of references) {
    const entry = catalog.get(messageId);
    if (!entry) {
      catalog.add(messageId, { locations });
    } else {
      entry.locations = sortedLocationUnion(entry.locations, locations);
    }
  }
  writePo(potPath, catalog);
  return references.size;
}

const sameLocations = (a, b) =>
  a.length === b.length &&
  a.every((x, i) => x[0] === b[i][0] && x[1] === b[i][1]);

/** Add newly extracted ids and locations without overwriting translations. */
export function updatePoFiles(
  references,
  englishPo = ENGLISH_PO,
  germanPo = GERMAN_PO,
) {
  const counts = [0, 0];
  [
    [englishPo, "en"],
    [germanPo, "de"],
  ].forEach(([path, locale], localeIndex) => {
    const catalog = loadCatalog(path, locale);
    let changed = false;
    for (const [messageId, locations] of references) {
      const entry = catalog.get(messageId);
      if (!entry) {
        catalog.add(messageId, {
          string: locale === "en" ? messageId : "",
          locations,
        });
        changed = true;
        counts[localeIndex] += 1;
        continue;
      }
      if (locale === "en") {
        if (entry.fuzzy) {
          entry.string = messageId;
          entry.flags.delete("fuzzy");
          changed = true;
        } else if (!entry.string.trim()) {
          entry.string = messageId;
          changed = true;
        }
      }
      const combined = sortedLocationUnion(entry.locations, locations);
      if (!sameLocations(combined, entry.locations)) {
        entry.locations = combined;
        changed = true;
      }
    }
    if (changed) {
      writePo(path, catalog);
    }
  });
  return counts;
}

export function translationErrors(catalog, messages, locale) {
  const errors = [];
  for (const messageId of [...messages].sort(compareStr)) {
    const entry = catalog.get(messageId);
    if (!entry) {
      errors.push(`${locale}: missing msgid ${pyRepr(messageId)}`);
    } else if (entry.fuzzy) {
      errors.push(`${locale}: fuzzy translation for ${pyRepr(messageId)}`);
    } else if (typeof entry.string !== "string" || !entry.string.trim()) {
      errors.push(`${locale}: empty translation for ${pyRepr(messageId)}`);
    }
  }
  return errors;
}

/** Python `repr(str)`: single quotes unless the text has ' and no ". */
export function pyRepr(text) {
  const quote = text.includes("'") && !text.includes('"') ? '"' : "'";
  let out = quote;
  for (const c of text) {
    const code = c.codePointAt(0);
    if (c === quote || c === "\\") {
      out += `\\${c}`;
    } else if (c === "\n") {
      out += "\\n";
    } else if (c === "\r") {
      out += "\\r";
    } else if (c === "\t") {
      out += "\\t";
    } else if (code < 0x20 || code === 0x7f) {
      out += `\\x${code.toString(16).padStart(2, "0")}`;
    } else {
      out += c;
    }
  }
  return out + quote;
}

function runtimeData(references, english, german) {
  const keys = [...references.keys()].sort(compareStr);
  return {
    en: Object.fromEntries(keys.map((key) => [key, english.get(key).string])),
    de: Object.fromEntries(keys.map((key) => [key, german.get(key).string])),
  };
}

export function renderCatalog(data) {
  return renderJson(data);
}

export function checkRuntimeCatalog(
  references,
  englishPo = ENGLISH_PO,
  germanPo = GERMAN_PO,
  outputPath = WEB_RUNTIME_CATALOG,
) {
  const english = loadCatalog(englishPo, "en");
  const german = loadCatalog(germanPo, "de");
  const keys = new Set(references.keys());
  const errors = [
    ...translationErrors(english, keys, "en"),
    ...translationErrors(german, keys, "de"),
  ];
  if (errors.length) {
    return [false, errors];
  }
  const expected = renderCatalog(runtimeData(references, english, german));
  return [existsSync(outputPath) && readText(outputPath) === expected, []];
}

export function generateRuntimeCatalog(
  references,
  englishPo = ENGLISH_PO,
  germanPo = GERMAN_PO,
  outputPath = WEB_RUNTIME_CATALOG,
) {
  const english = loadCatalog(englishPo, "en");
  const german = loadCatalog(germanPo, "de");
  const keys = new Set(references.keys());
  const errors = [
    ...translationErrors(english, keys, "en"),
    ...translationErrors(german, keys, "de"),
  ];
  if (errors.length) {
    return errors;
  }
  writeText(
    outputPath,
    renderCatalog(runtimeData(references, english, german)),
  );
  return [];
}

export function main(argv = process.argv.slice(2)) {
  const { values } = parseArgs({
    args: argv,
    options: {
      extract: { type: "boolean", default: false },
      update: { type: "boolean", default: false },
      check: { type: "boolean", default: false },
    },
  });
  if (
    [values.extract, values.update, values.check].filter(Boolean).length > 1
  ) {
    console.error("--extract, --update and --check are mutually exclusive");
    return 2;
  }
  try {
    const references = extractTree();
    if (values.extract) {
      const count = mergeIntoPot(references);
      console.log(`Merged ${count} web UI messages into the Babel POT.`);
      return 0;
    }
    if (values.update) {
      const [englishAdded, germanAdded] = updatePoFiles(references);
      console.log(
        `Added ${englishAdded} English and ${germanAdded} German messages.`,
      );
    }
    if (values.check) {
      const [current, errors] = checkRuntimeCatalog(references);
      if (errors.length) {
        console.error("Web UI translations need attention:");
        console.error(errors.map((error) => `- ${error}`).join("\n"));
        return 1;
      }
      if (current) {
        console.log("Web UI translation catalog is current.");
        return 0;
      }
      console.error("Web UI catalog is stale; run `just i18n-update`.");
      return 1;
    }
    const errors = generateRuntimeCatalog(references);
    if (errors.length) {
      console.error("Web UI translations need attention:");
      console.error(errors.map((error) => `- ${error}`).join("\n"));
      return 1;
    }
    console.log(`Generated ${references.size} web UI messages.`);
  } catch (error) {
    console.error(`Web UI i18n generation failed: ${error.message}`);
    return 1;
  }
  return 0;
}

if (import.meta.url === pathToFileURL(process.argv[1] ?? "").href) {
  process.exitCode = main();
}
