// Repository layout shared by the i18n scripts. `STRICKNANI_ROOT` overrides
// the repository root (used by PARITY.sh and the tests).

import { readdirSync, statSync } from "node:fs";
import { dirname, join, relative, resolve, sep } from "node:path";
import { fileURLToPath } from "node:url";

import { compareStr } from "./catalog.mjs";

export const ROOT = resolve(
  process.env.STRICKNANI_ROOT ??
    join(dirname(fileURLToPath(import.meta.url)), "..", "..", ".."),
);
export const RUST_SOURCE_DIR = join(ROOT, "src");
export const RUST_SOURCE = join(RUST_SOURCE_DIR, "web.rs");
export const WEB_SOURCE = join(ROOT, "web", "src");
export const LOCALES = join(ROOT, "stricknani", "locales");
export const POT_PATH = join(LOCALES, "messages.pot");
export const ENGLISH_PO = join(LOCALES, "en", "LC_MESSAGES", "messages.po");
export const GERMAN_PO = join(LOCALES, "de", "LC_MESSAGES", "messages.po");
export const RUST_RUNTIME_CATALOG = join(ROOT, "locales", "messages.json");
export const WEB_RUNTIME_CATALOG = join(ROOT, "web", "static", "messages.json");

/** Path relative to the repository root with `/` separators. */
export function relativeToRoot(path) {
  const rel = relative(ROOT, path);
  if (rel.startsWith("..")) {
    return path.split(sep).pop();
  }
  return rel.split(sep).join("/");
}

/** Python `Path` ordering: compare component by component. */
export function comparePaths(a, b) {
  const left = a.split(sep);
  const right = b.split(sep);
  const length = Math.min(left.length, right.length);
  for (let i = 0; i < length; i += 1) {
    const order = compareStr(left[i], right[i]);
    if (order) {
      return order;
    }
  }
  return left.length - right.length;
}

/** `Path.rglob("*")` restricted to files (symlinked directories not followed). */
export function walkFiles(directory) {
  const files = [];
  const visit = (current) => {
    for (const entry of readdirSync(current, { withFileTypes: true })) {
      const path = join(current, entry.name);
      if (entry.isDirectory()) {
        visit(path);
      } else if (entry.isFile()) {
        files.push(path);
      } else if (entry.isSymbolicLink()) {
        try {
          if (statSync(path).isFile()) {
            files.push(path);
          }
        } catch {
          // Dangling link: ignore, like Path.is_file().
        }
      }
    }
  };
  visit(directory);
  return files;
}
