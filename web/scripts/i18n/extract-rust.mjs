// Merge Rust `tr(english, german)` literals into the POT catalog.
// Port of scripts/extract_rust_i18n.py.

import { existsSync } from "node:fs";
import { pathToFileURL } from "node:url";

import { compareStr, readPo, readText, writePo } from "./catalog.mjs";
import {
  POT_PATH,
  RUST_SOURCE,
  RUST_SOURCE_DIR,
  comparePaths,
  relativeToRoot,
  walkFiles,
} from "./paths.mjs";

const STRING = String.raw`"(?:\\.|[^"\\])*"`;
const CALL_SOURCE = String.raw`\btr\s*\(\s*(?<english>${STRING})\s*,\s*(?<german>${STRING})\s*\)`;

/** Fresh global regex (stateful `lastIndex`). */
function callPattern() {
  return new RegExp(CALL_SOURCE, "gs");
}

/**
 * Evaluate a double-quoted literal the way Python's `ast.literal_eval` does.
 * Rust-only escapes such as `\u{...}` are rejected, like in the Python tool.
 */
export function pythonStringLiteral(literal) {
  const body = literal.slice(1, -1);
  let result = "";
  for (let i = 0; i < body.length; i += 1) {
    const c = body[i];
    if (c === "\n") {
      throw new SyntaxError("unterminated string literal");
    }
    if (c !== "\\") {
      result += c;
      continue;
    }
    i += 1;
    const e = body[i];
    const simple = {
      "\n": "",
      "\\": "\\",
      "'": "'",
      '"': '"',
      a: "\x07",
      b: "\b",
      f: "\f",
      n: "\n",
      r: "\r",
      t: "\t",
      v: "\v",
    };
    if (e in simple) {
      result += simple[e];
    } else if (/[0-7]/.test(e)) {
      const octal = /^[0-7]{1,3}/.exec(body.slice(i))[0];
      result += String.fromCodePoint(parseInt(octal, 8));
      i += octal.length - 1;
    } else if (e === "x" || e === "u" || e === "U") {
      const length = { x: 2, u: 4, U: 8 }[e];
      const digits = body.slice(i + 1, i + 1 + length);
      if (digits.length !== length || !/^[0-9a-fA-F]+$/.test(digits)) {
        throw new SyntaxError("truncated escape sequence");
      }
      const value = parseInt(digits, 16);
      if (value > 0x10ffff) {
        throw new SyntaxError("illegal Unicode character");
      }
      result += String.fromCodePoint(value);
      i += length;
    } else if (e === "N") {
      throw new SyntaxError("\\N{...} escapes are not supported");
    } else if (e === undefined) {
      throw new SyntaxError("unterminated string literal");
    } else {
      // Unknown escapes keep their backslash (Python SyntaxWarning only).
      result += `\\${e}`;
    }
  }
  return result;
}

/** Unique UI msgid/fallback pairs, rejecting conflicting German text. */
export function rustLabelPairs(source) {
  const messages = new Map();
  for (const match of source.matchAll(callPattern())) {
    const english = pythonStringLiteral(match.groups.english);
    const german = pythonStringLiteral(match.groups.german);
    if (english === "en" && german === "de") {
      continue;
    }
    if (messages.has(english) && messages.get(english) !== german) {
      throw new Error(
        `conflicting Rust translations for msgid: ${JSON.stringify(english)}`,
      );
    }
    messages.set(english, german);
  }
  return messages;
}

/** Rust sources under `src/`, in Python `sorted(Path.rglob("*.rs"))` order. */
export function rustSourceFiles(sourceDir = RUST_SOURCE_DIR) {
  return walkFiles(sourceDir)
    .filter((path) => path.endsWith(".rs"))
    .sort(comparePaths);
}

export function rustLabelPairsFromFiles(sourcePaths) {
  const messages = new Map();
  for (const sourcePath of sourcePaths) {
    for (const [msgid, fallback] of rustLabelPairs(readText(sourcePath))) {
      if (messages.has(msgid) && messages.get(msgid) !== fallback) {
        throw new Error(
          `conflicting Rust translations for msgid: ${JSON.stringify(msgid)}`,
        );
      }
      messages.set(msgid, fallback);
    }
  }
  return messages;
}

/** All Rust modules by default; a custom single source file otherwise. */
export function rustLabelPairsForTree(rustSource = RUST_SOURCE) {
  if (rustSource === RUST_SOURCE) {
    return rustLabelPairsFromFiles(rustSourceFiles());
  }
  return rustLabelPairs(readText(rustSource));
}

/**
 * Add Rust UI msgids and source locations to the POT in place.
 * @param {{ sourcePath?: string | null, potPath?: string }} [options]
 */
export function mergeIntoPot({ sourcePath = null, potPath = POT_PATH } = {}) {
  const linesByMessage = new Map();
  const sourcePaths = sourcePath ? [sourcePath] : rustSourceFiles();
  const messages = rustLabelPairsFromFiles(sourcePaths);
  for (const current of sourcePaths) {
    const source = readText(current);
    for (const match of source.matchAll(callPattern())) {
      const english = pythonStringLiteral(match.groups.english);
      const german = pythonStringLiteral(match.groups.german);
      if (english === "en" && german === "de") {
        continue;
      }
      const line = (source.slice(0, match.index).match(/\n/g) ?? []).length + 1;
      if (!linesByMessage.has(english)) {
        linesByMessage.set(english, []);
      }
      linesByMessage.get(english).push([relativeToRoot(current), line]);
    }
  }
  const catalog = readPo(potPath);
  for (const english of messages.keys()) {
    const existing = catalog.get(english);
    const locations = linesByMessage.get(english) ?? [];
    if (!existing) {
      catalog.add(english, { locations });
    } else {
      existing.locations = sortedLocationUnion(existing.locations, locations);
    }
  }
  writePo(potPath, catalog);
  return messages.size;
}

/** `sorted(set(a).union(b))` for `(file, line)` tuples. */
export function sortedLocationUnion(a, b) {
  const unique = new Map();
  for (const location of [...a, ...b]) {
    unique.set(`${location[0]}\u0000${location[1]}`, location);
  }
  return [...unique.values()].sort(
    (x, y) => compareStr(x[0], y[0]) || (x[1] ?? -1) - (y[1] ?? -1),
  );
}

export function main() {
  if (!existsSync(POT_PATH)) {
    console.error(`Rust i18n extraction failed: missing ${POT_PATH}`);
    return 1;
  }
  try {
    const count = mergeIntoPot();
    console.log(`Extracted ${count} Rust UI messages.`);
  } catch (error) {
    console.error(`Rust i18n extraction failed: ${error.message}`);
    return 1;
  }
  return 0;
}

if (import.meta.url === pathToFileURL(process.argv[1] ?? "").href) {
  process.exitCode = main();
}
