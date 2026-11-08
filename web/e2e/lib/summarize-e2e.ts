// Print a bounded, redacted summary of Android instrumentation failures.
//
// Port of ci/stricknani/summarize_rust_e2e.py. The module has no relative
// imports so it can also run directly: `node e2e/lib/summarize-e2e.ts`.
import {
  closeSync,
  constants,
  lstatSync,
  openSync,
  readFileSync,
  readSync,
  readdirSync,
  realpathSync,
} from "node:fs";
import { dirname, join, resolve, sep } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";
import { parseArgs } from "node:util";

const REPO_ROOT = resolve(dirname(fileURLToPath(import.meta.url)), "../../..");
export const DEFAULT_RESULTS_DIR = join(
  REPO_ROOT,
  "android/app/build/outputs/androidTest-results/connected/debug",
);
export const MAX_REPORT_FILES = 32;
export const MAX_SCAN_ENTRIES = 512;
export const MAX_REPORT_DEPTH = 3;
export const MAX_FILE_BYTES = 2 * 1024 * 1024;
export const MAX_TOTAL_BYTES = 8 * 1024 * 1024;
export const MAX_FAILURES = 40;
export const MAX_OUTPUT_LINES = 64;
export const MAX_LINE_CHARS = 360;
export const MAX_PRIVATE_FILE_BYTES = 16 * 1024;

const EMAIL = /\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}\b/gi;
const URL_PATTERN = /\bhttps?:\/\/[^\s"'<>]+/gi;
const BEARER = /\bBearer\s+[^\s"'<>]+/gi;
const PAT = /\bsna_[A-Za-z0-9_-]{8,}\b/g;
const CREDENTIAL_FIELD =
  /\b(password|passwd|passphrase|token|secret|authorization)\b\s*(?:[:=]|\bis\b)\s*["']?[^\s,;"']+/gi;
const OFFLINE_RECORD =
  /\bE2E (?:offline yarn|project)\s+\d+\b|\bRust replay [A-Za-z0-9-]{4,} (?:project|yarn)\b/gi;
const FIXTURE_RECORDS = [
  "Heirloom Baby Blanket",
  "Riverbend Merino DK",
  "Schal",
];

export class Redactor {
  readonly #secrets: readonly string[];

  constructor(secrets: readonly string[] = []) {
    this.#secrets = secrets;
  }

  clean(value: string): string {
    let result = value.replace(URL_PATTERN, "[redacted-url]");
    for (const secret of this.#secrets) {
      if (secret) {
        result = result.split(secret).join("[redacted]");
      }
    }
    result = result.replace(BEARER, "Bearer [redacted]");
    result = result.replace(PAT, "[redacted-token]");
    result = result.replace(EMAIL, "[redacted-email]");
    result = result.replace(CREDENTIAL_FIELD, "$1=[redacted]");
    result = result.replace(OFFLINE_RECORD, "[fixture-record]");
    for (const fixtureValue of FIXTURE_RECORDS) {
      result = result.split(fixtureValue).join("[fixture-record]");
    }
    result = result.split(/\s+/).filter(Boolean).join(" ");
    return result.slice(0, MAX_LINE_CHARS);
  }

  // Never reveal the secrets through console.log / util.inspect.
  [Symbol.for("nodejs.util.inspect.custom")](): string {
    return "Redactor";
  }
}

function privateStrings(path: string | undefined): string[] {
  if (path === undefined) {
    return [];
  }
  let value: unknown;
  try {
    const candidate = path.startsWith("~/")
      ? join(process.env.HOME ?? "", path.slice(2))
      : path;
    const resolved = realpathSync(candidate);
    const info = lstatSync(candidate);
    if (
      info.isSymbolicLink() ||
      !info.isFile() ||
      resolved === REPO_ROOT ||
      resolved.startsWith(REPO_ROOT + sep) ||
      (info.mode & 0o077) !== 0 ||
      info.size > MAX_PRIVATE_FILE_BYTES
    ) {
      return [];
    }
    value = JSON.parse(readFileSync(resolved, "utf-8"));
  } catch {
    return [];
  }
  if (typeof value !== "object" || value === null || Array.isArray(value)) {
    return [];
  }
  const record = value as Record<string, unknown>;
  return ["url", "email", "password", "token"]
    .map((key) => record[key])
    .filter((item): item is string => typeof item === "string" && item !== "");
}

/** Read only the known private fixture fields for output redaction. */
export function loadRedactor(
  fixtureFile?: string,
  tokenFiles?: string | readonly string[],
): Redactor {
  const files =
    tokenFiles === undefined
      ? []
      : typeof tokenFiles === "string"
        ? [tokenFiles]
        : tokenFiles;
  const values = new Set([
    ...privateStrings(fixtureFile),
    ...files.flatMap((file) => privateStrings(file)),
  ]);
  return new Redactor([...values].sort((a, b) => b.length - a.length));
}

function reportFiles(resultsDir: string): {
  files: string[];
  truncated: boolean;
} {
  try {
    const top = lstatSync(resultsDir);
    if (top.isSymbolicLink() || !top.isDirectory()) {
      return { files: [], truncated: false };
    }
    const files: string[] = [];
    const directories: [string, number][] = [[resultsDir, 0]];
    let scannedEntries = 0;
    while (directories.length > 0) {
      const [directory, depth] = directories.pop() as [string, number];
      for (const entry of readdirSync(directory, { withFileTypes: true })) {
        scannedEntries += 1;
        if (scannedEntries > MAX_SCAN_ENTRIES) {
          return { files, truncated: true };
        }
        if (entry.isSymbolicLink()) {
          continue;
        }
        const entryPath = join(directory, entry.name);
        if (entry.isFile()) {
          if (!(
            entry.name.startsWith("TEST-") && entry.name.endsWith(".xml")
          )) {
            continue;
          }
          if (files.length === MAX_REPORT_FILES) {
            return { files, truncated: true };
          }
          files.push(entryPath);
        } else if (depth < MAX_REPORT_DEPTH && entry.isDirectory()) {
          directories.push([entryPath, depth + 1]);
        }
      }
    }
    return { files, truncated: false };
  } catch {
    return { files: [], truncated: false };
  }
}

// --- minimal XML reader ------------------------------------------------------
// The JUnit reports only need elements, attributes and text. This reader is
// deliberately small and strict: anything malformed throws XmlParseError.

class XmlParseError extends Error {}

interface XmlElement {
  tag: string;
  attrs: Record<string, string>;
  /** Text before the first child element, like ElementTree's `.text`. */
  text: string | null;
  children: XmlElement[];
}

function decodeEntities(value: string): string {
  return value.replace(
    /&(#x[0-9a-fA-F]+|#[0-9]+|lt|gt|amp|quot|apos);/g,
    (_m, name: string) => {
      if (name === "lt") return "<";
      if (name === "gt") return ">";
      if (name === "amp") return "&";
      if (name === "quot") return '"';
      if (name === "apos") return "'";
      const code = name.startsWith("#x")
        ? Number.parseInt(name.slice(2), 16)
        : Number.parseInt(name.slice(1), 10);
      if (!Number.isInteger(code) || code < 0 || code > 0x10ffff) {
        throw new XmlParseError("bad character reference");
      }
      return String.fromCodePoint(code);
    },
  );
}

function parseXml(source: string): XmlElement {
  let position = 0;
  let root: XmlElement | null = null;
  const stack: XmlElement[] = [];
  const NAME = "[A-Za-z_:][-A-Za-z0-9_:.]*";
  const attributePattern = new RegExp(
    `\\s+(${NAME})\\s*=\\s*(?:"([^"<]*)"|'([^'<]*)')`,
    "y",
  );

  const appendText = (text: string): void => {
    if (text === "") return;
    const current = stack.at(-1);
    if (current === undefined) {
      if (text.trim() !== "") throw new XmlParseError("text outside root");
      return;
    }
    if (current.children.length === 0) {
      current.text = (current.text ?? "") + text;
    }
  };

  const start = source.charCodeAt(0) === 0xfeff ? 1 : 0;
  position = start;
  while (position < source.length) {
    const next = source.indexOf("<", position);
    if (next === -1) {
      appendText(decodeEntities(source.slice(position)));
      position = source.length;
      break;
    }
    if (next > position) {
      const raw = source.slice(position, next);
      if (raw.includes("]]>")) throw new XmlParseError("bad text");
      appendText(decodeEntities(raw));
    }
    position = next;
    if (source.startsWith("<!--", position)) {
      const end = source.indexOf("-->", position + 4);
      if (end === -1) throw new XmlParseError("unterminated comment");
      position = end + 3;
    } else if (source.startsWith("<![CDATA[", position)) {
      const end = source.indexOf("]]>", position + 9);
      if (end === -1 || stack.length === 0)
        throw new XmlParseError("bad CDATA");
      appendText(source.slice(position + 9, end));
      position = end + 3;
    } else if (source.startsWith("<?", position)) {
      const end = source.indexOf("?>", position + 2);
      if (end === -1) throw new XmlParseError("unterminated instruction");
      position = end + 2;
    } else if (source.startsWith("</", position)) {
      const match = new RegExp(`</(${NAME})\\s*>`, "y");
      match.lastIndex = position;
      const closing = match.exec(source);
      const open = stack.pop();
      if (closing === null || open === undefined || open.tag !== closing[1]) {
        throw new XmlParseError("mismatched closing tag");
      }
      position = match.lastIndex;
    } else if (source.startsWith("<!", position)) {
      throw new XmlParseError("unsupported declaration");
    } else {
      const open = new RegExp(`<(${NAME})`, "y");
      open.lastIndex = position;
      const head = open.exec(source);
      if (head === null) throw new XmlParseError("bad tag");
      position = open.lastIndex;
      const attrs: Record<string, string> = {};
      for (;;) {
        attributePattern.lastIndex = position;
        const attribute = attributePattern.exec(source);
        if (attribute === null) break;
        const name = attribute[1] as string;
        if (name in attrs) throw new XmlParseError("duplicate attribute");
        attrs[name] = decodeEntities((attribute[2] ?? attribute[3]) as string);
        position = attributePattern.lastIndex;
      }
      const tail = /\s*(\/?)>/y;
      tail.lastIndex = position;
      const close = tail.exec(source);
      if (close === null) throw new XmlParseError("bad tag end");
      position = tail.lastIndex;
      const element: XmlElement = {
        tag: head[1] as string,
        attrs,
        text: null,
        children: [],
      };
      const parent = stack.at(-1);
      if (parent === undefined) {
        if (root !== null) throw new XmlParseError("multiple roots");
        root = element;
      } else {
        parent.children.push(element);
      }
      if (close[1] !== "/") stack.push(element);
    }
  }
  if (root === null || stack.length > 0) {
    throw new XmlParseError("incomplete document");
  }
  return root;
}

function* iterate(element: XmlElement, tag: string): Generator<XmlElement> {
  if (element.tag === tag) yield element;
  for (const child of element.children) yield* iterate(child, tag);
}

function failureDetails(
  root: XmlElement,
  redactor: Redactor,
): { caseCount: number; failureCount: number; details: string[] } {
  const cases = [...iterate(root, "testcase")];
  const details: string[] = [];
  let failureCount = 0;
  let failedCaseCount = 0;
  let detailedCaseCount = 0;
  for (const testCase of cases) {
    const failures = testCase.children.filter(
      (child) => child.tag === "failure" || child.tag === "error",
    );
    const failure = failures[0];
    if (failure === undefined) continue;
    failureCount += failures.length;
    failedCaseCount += 1;
    if (detailedCaseCount >= MAX_FAILURES) continue;
    const testName = testCase.attrs.name ?? "unknown test";
    const className = testCase.attrs.classname ?? "unknown class";
    const kind = failure.tag === "error" ? "error" : "failure";
    const failureType = failure.attrs.type ?? "";
    let message = failure.attrs.message ?? "";
    if (!message && failure.text) {
      message =
        failure.text
          .split(/\r?\n/)
          .map((line) => line.trim())
          .find((line) => line !== "") ?? "";
    }
    const label = redactor.clean(`${className}#${testName}`);
    const summary = redactor.clean(`${kind} ${failureType}: ${message}`);
    details.push(`- ${label}: ${summary || kind}`);
    detailedCaseCount += 1;
    if (failure.text) {
      const frames = failure.text
        .split(/\r?\n/)
        .map((line) => line.trim())
        .filter((line) => line.startsWith("at "));
      const isApp = (frame: string): boolean =>
        frame.startsWith("at blue.anika.wolle.");
      const appFrame = frames.findIndex(isApp);
      const selected = new Set(
        frames
          .map((frame, index) => [frame, index] as const)
          .filter(([frame]) => !isApp(frame))
          .map(([, index]) => index)
          .slice(0, 2),
      );
      if (appFrame !== -1) selected.add(appFrame);
      frames.forEach((frame, index) => {
        if (selected.has(index)) details.push(`  ${redactor.clean(frame)}`);
      });
    }
  }
  if (failedCaseCount > detailedCaseCount) {
    details.push(
      `- ${failedCaseCount - detailedCaseCount} additional failed test cases omitted.`,
    );
  }
  return { caseCount: cases.length, failureCount, details };
}

function readBounded(path: string, limit: number): Buffer {
  const descriptor = openSync(path, constants.O_RDONLY | constants.O_NOFOLLOW);
  try {
    const buffer = Buffer.alloc(limit + 1);
    let filled = 0;
    while (filled < buffer.length) {
      const read = readSync(
        descriptor,
        buffer,
        filled,
        buffer.length - filled,
        null,
      );
      if (read === 0) break;
      filled += read;
    }
    return buffer.subarray(0, filled);
  } finally {
    closeSync(descriptor);
  }
}

/** Summarize JUnit failures without exposing full reports or fixture values. */
export function summarizeResults(
  resultsDir: string,
  redactor: Redactor = new Redactor(),
): string[] {
  const { files, truncated: fileListTruncated } = reportFiles(resultsDir);
  if (files.length === 0) {
    return ["No Android instrumentation XML report was found."];
  }
  const output: string[] = [];
  let totalCases = 0;
  let totalFailures = 0;
  let totalBytes = 0;
  const detailLines: string[] = [];
  let reportCount = 0;
  let skippedReports = 0;
  for (const path of files) {
    let contents: Buffer;
    try {
      const remaining = MAX_TOTAL_BYTES - totalBytes;
      const readLimit = Math.min(MAX_FILE_BYTES, remaining);
      contents = readBounded(path, readLimit);
      if (contents.length > readLimit) {
        output.push(
          "An Android test report exceeded the diagnostic size limit; omitted.",
        );
        skippedReports += 1;
        continue;
      }
    } catch {
      output.push("An Android test report could not be read; omitted.");
      skippedReports += 1;
      continue;
    }
    totalBytes += contents.length;
    reportCount += 1;
    const upper = contents.toString("latin1").toUpperCase();
    if (upper.includes("<!DOCTYPE") || upper.includes("<!ENTITY")) {
      output.push(
        "An Android test report used unsupported XML declarations; omitted.",
      );
      skippedReports += 1;
      continue;
    }
    let root: XmlElement;
    try {
      root = parseXml(
        new TextDecoder("utf-8", { fatal: true }).decode(contents),
      );
    } catch {
      output.push("An Android test report could not be parsed; omitted.");
      skippedReports += 1;
      continue;
    }
    const { caseCount, failureCount, details } = failureDetails(root, redactor);
    totalCases += caseCount;
    totalFailures += failureCount;
    detailLines.push(...details);
  }

  output.unshift(
    "Android instrumentation summary: " +
      `${totalCases} tests, ${totalFailures} failures/errors, ` +
      `${reportCount} report files inspected.`,
  );
  if (fileListTruncated) {
    output.push("Report scan stopped at its file or directory-entry limit.");
  }
  if (totalFailures === 0 && skippedReports === 0) {
    output.push("No failing test cases were present in the parsed reports.");
  }
  const availableLines = Math.max(0, MAX_OUTPUT_LINES - output.length);
  if (detailLines.length > availableLines && availableLines > 0) {
    output.push(...detailLines.slice(0, availableLines - 1));
    output.push("Additional failure details omitted by the output limit.");
  } else {
    output.push(...detailLines.slice(0, availableLines));
  }
  return output.slice(0, MAX_OUTPUT_LINES);
}

export function main(
  argv: readonly string[] = process.argv.slice(2),
  print: (line: string) => void = (line) => process.stdout.write(`${line}\n`),
): number {
  let values: {
    "results-dir"?: string;
    "fixture-file"?: string;
    "token-file"?: string[];
  };
  try {
    values = parseArgs({
      args: [...argv],
      options: {
        "results-dir": { type: "string" },
        "fixture-file": { type: "string" },
        // Repeatable: the main and the peer token file.
        "token-file": { type: "string", multiple: true },
      },
      strict: true,
    }).values;
  } catch {
    process.stderr.write("summarize-e2e: invalid arguments\n");
    return 2;
  }
  const redactor = loadRedactor(values["fixture-file"], values["token-file"]);
  for (const line of summarizeResults(
    values["results-dir"] ?? DEFAULT_RESULTS_DIR,
    redactor,
  )) {
    print(line);
  }
  return 0;
}

if (
  process.argv[1] &&
  import.meta.url === pathToFileURL(process.argv[1]).href
) {
  process.exitCode = main();
}
