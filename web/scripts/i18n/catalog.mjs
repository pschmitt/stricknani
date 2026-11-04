// Minimal gettext catalog model, PO reader and PO writer.
//
// This is a focused port of the parts of Python Babel (2.18) that the
// Stricknani i18n tooling uses: `babel.messages.pofile.read_po/write_po`,
// `Catalog.update` (including difflib fuzzy matching) and the header handling
// of `Catalog`. Output formatting (header, wrapping at 76 columns, location
// comments, flags, ordering, obsolete entries) is meant to be byte-identical
// to Babel's. Plural forms and message contexts are not used by this project
// and are rejected with an error instead of being silently mangled.

import { readFileSync, writeFileSync, mkdirSync } from "node:fs";
import { dirname } from "node:path";

/** Version written to the `Generated-By` header (matches the pinned Babel). */
export const BABEL_VERSION = "2.18.0";

const DEFAULT_HEADER = `# Translations template for PROJECT.
# Copyright (C) YEAR ORGANIZATION
# This file is distributed under the same license as the PROJECT project.
# FIRST AUTHOR <EMAIL@ADDRESS>, YEAR.
#`;

const ENGLISH_NAMES = { de: "German", en: "English" };

// ---------------------------------------------------------------------------
// Small Python-compatibility helpers
// ---------------------------------------------------------------------------

/** Compare strings by Unicode code point (Python `str` ordering). */
export function compareStr(a, b) {
  return Buffer.compare(Buffer.from(a, "utf8"), Buffer.from(b, "utf8"));
}

/** Number of code points (Python `len`). */
export function cpLen(text) {
  let count = 0;
  for (const _ of text) {
    count += 1;
  }
  return count;
}

/** Read a text file like Python `read_text`: UTF-8, universal newlines. */
export function readText(path) {
  return readFileSync(path, "utf8").replace(/\r\n?/g, "\n");
}

export function writeText(path, text) {
  mkdirSync(dirname(path), { recursive: true });
  writeFileSync(path, text, "utf8");
}

/** Python `str.splitlines(True)`. */
function splitlinesKeepends(text) {
  const lines = [];
  let start = 0;
  for (let i = 0; i < text.length; i += 1) {
    const c = text[i];
    if ("\n\r\v\f\x1c\x1d\x1e\x85\u2028\u2029".includes(c)) {
      let end = i + 1;
      if (c === "\r" && text[i + 1] === "\n") {
        end += 1;
        i += 1;
      }
      lines.push(text.slice(start, end));
      start = end;
    }
  }
  if (start < text.length) {
    lines.push(text.slice(start));
  }
  return lines;
}

function splitlines(text) {
  return splitlinesKeepends(text).map((line) =>
    line.replace(/(\r\n|[\n\r\v\f\x1c\x1d\x1e\x85\u2028\u2029])$/, ""),
  );
}

// ---------------------------------------------------------------------------
// textwrap.TextWrapper as customised by babel.util.TextWrapper
// ---------------------------------------------------------------------------

const WORDSEP = /(\s+|(?<=[\p{L}\p{N}_!"'&.,?])-{2,}(?=[\p{L}\p{N}_]))/u;
const ENCLOSED_FILENAME = /(⁨[^⁨]+?⁩(?::-?\d+)?)/u;

function expandTabs(text) {
  let out = "";
  let column = 0;
  for (const c of text) {
    if (c === "\t") {
      const pad = 8 - (column % 8);
      out += " ".repeat(pad);
      column += pad;
    } else if (c === "\n" || c === "\r") {
      out += c;
      column = 0;
    } else {
      out += c;
      column += 1;
    }
  }
  return out;
}

function splitChunks(text) {
  if (!text.includes("⁨")) {
    return text.split(WORDSEP).filter((chunk) => chunk);
  }
  const chunks = [];
  for (const chunk of text.split(ENCLOSED_FILENAME)) {
    if (chunk.startsWith("⁨")) {
      chunks.push(chunk);
    } else {
      chunks.push(...chunk.split(WORDSEP).filter((c) => c));
    }
  }
  return chunks.filter((c) => c);
}

/** `TextWrapper(width, subsequent_indent, break_long_words=False).wrap`. */
export function wrapText(text, width, subsequentIndent = "") {
  const munged = expandTabs(text).replace(/[\t\n\v\f\r]/g, " ");
  const chunks = splitChunks(munged).reverse();
  const lines = [];
  const isBlank = (chunk) => chunk.trim() === "";
  while (chunks.length) {
    let current = [];
    let currentLen = 0;
    const indent = lines.length ? subsequentIndent : "";
    const room = width - cpLen(indent);
    if (isBlank(chunks[chunks.length - 1]) && lines.length) {
      chunks.pop();
    }
    while (chunks.length) {
      const length = cpLen(chunks[chunks.length - 1]);
      if (currentLen + length <= room) {
        current.push(chunks.pop());
        currentLen += length;
      } else {
        break;
      }
    }
    if (chunks.length && cpLen(chunks[chunks.length - 1]) > room) {
      if (!current.length) {
        current.push(chunks.pop());
      }
      currentLen = current.reduce((sum, chunk) => sum + cpLen(chunk), 0);
    }
    if (current.length && isBlank(current[current.length - 1])) {
      currentLen -= cpLen(current[current.length - 1]);
      current.pop();
    }
    if (current.length) {
      lines.push(indent + current.join(""));
    }
  }
  return lines;
}

// ---------------------------------------------------------------------------
// babel.messages.pofile: escape / normalize / unescape
// ---------------------------------------------------------------------------

const WORD_SEP =
  /(\s+|[^\s\p{L}\p{N}_]*[\p{L}\p{N}_]+[a-zA-Z]-(?=[\p{L}\p{N}_]+[a-zA-Z])|(?<=[\p{L}\p{N}_!"'&.,?])-{2,}(?=[\p{L}\p{N}_]))/u;

export function escapePo(text) {
  return `"${text
    .replaceAll("\\", "\\\\")
    .replaceAll("\t", "\\t")
    .replaceAll("\r", "\\r")
    .replaceAll("\n", "\\n")
    .replaceAll('"', '\\"')}"`;
}

export function normalizePo(text, prefix = "", width = 76) {
  let lines;
  if (width && width > 0) {
    const prefixLen = cpLen(prefix);
    lines = [];
    for (const line of splitlinesKeepends(text)) {
      if (cpLen(escapePo(line)) + prefixLen > width) {
        const chunks = line.split(WORD_SEP).reverse();
        while (chunks.length) {
          const buf = [];
          let size = 2;
          while (chunks.length) {
            const length =
              cpLen(escapePo(chunks[chunks.length - 1])) - 2 + prefixLen;
            if (size + length < width) {
              buf.push(chunks.pop());
              size += length;
            } else {
              if (!buf.length) {
                buf.push(chunks.pop());
              }
              break;
            }
          }
          lines.push(buf.join(""));
        }
      } else {
        lines.push(line);
      }
    }
  } else {
    lines = splitlinesKeepends(text);
  }
  if (lines.length <= 1) {
    return escapePo(text);
  }
  if (lines.length && !lines[lines.length - 1]) {
    lines.pop();
    lines[lines.length - 1] += "\n";
  }
  return `""\n${lines.map((line) => prefix + escapePo(line)).join("\n")}`;
}

function unescapePo(quoted) {
  const inner = quoted.slice(1, -1);
  if (!inner.includes("\\")) {
    return inner;
  }
  return inner.replace(/\\([\\trn"])/g, (_, c) =>
    c === "n" ? "\n" : c === "t" ? "\t" : c === "r" ? "\r" : c,
  );
}

// ---------------------------------------------------------------------------
// Message
// ---------------------------------------------------------------------------

const PYTHON_FORMAT =
  /%(?:\([\p{L}\p{N}_]*\))?([-# +0]?(?:\*|\p{Nd}+)?(?:\.(?:\*|\p{Nd}+))?[hlL]?)([diouxXeEfFgGcrs%])/u;

/** Port of `string.Formatter().parse`: does it contain a replacement field? */
function hasPythonBraceFormat(text) {
  if (!text.includes("{")) {
    return false;
  }
  let fieldSeen = false;
  let i = 0;
  const n = text.length;
  while (i < n) {
    const c = text[i];
    if (c === "}") {
      if (text[i + 1] === "}") {
        i += 2;
        continue;
      }
      return false; // Single '}' encountered in format string
    }
    if (c !== "{") {
      i += 1;
      continue;
    }
    if (text[i + 1] === "{") {
      i += 2;
      continue;
    }
    if (i + 1 >= n) {
      return false; // Single '{' encountered in format string
    }
    // Find the matching closing brace.
    let depth = 1;
    let j = i + 1;
    while (j < n) {
      if (text[j] === "{") {
        depth += 1;
      } else if (text[j] === "}") {
        depth -= 1;
        if (depth === 0) {
          break;
        }
      }
      j += 1;
    }
    if (depth !== 0) {
      return false; // expected '}' before end of string
    }
    const field = text.slice(i + 1, j);
    // Split field_name / conversion / format_spec like MarkupIterator.
    let k = 0;
    let bracket = false;
    while (k < field.length) {
      const f = field[k];
      if (f === "[") {
        bracket = true;
      } else if (f === "]") {
        bracket = false;
      } else if (!bracket && (f === ":" || f === "!")) {
        break;
      } else if (f === "{") {
        return false; // unexpected '{' in field name
      }
      k += 1;
    }
    if (field[k] === "!") {
      if (k + 1 >= field.length) {
        return false; // end of string while looking for conversion specifier
      }
      if (k + 2 < field.length && field[k + 2] !== ":") {
        return false; // expected ':' after conversion specifier
      }
    }
    fieldSeen = true;
    i = j + 1;
  }
  return fieldSeen;
}

function dedupeLocations(locations) {
  const seen = new Set();
  const result = [];
  for (const [file, line] of locations) {
    const key = `${file}\u0000${line}`;
    if (!seen.has(key)) {
      seen.add(key);
      result.push([file, line]);
    }
  }
  return result;
}

function dedupe(values) {
  return [...new Set(values)];
}

/**
 * @typedef {[string, number | null]} Location
 * @typedef {{
 *   locations?: Location[],
 *   flags?: Iterable<string>,
 *   autoComments?: string[],
 *   userComments?: string[],
 * }} MessageOptions
 */

export class Message {
  /**
   * @param {string} id
   * @param {string} [string]
   * @param {MessageOptions} [options]
   */
  constructor(
    id,
    string = "",
    { locations = [], flags = [], autoComments = [], userComments = [] } = {},
  ) {
    this.id = id;
    this.string = string;
    this.locations = dedupeLocations(locations);
    this.flags = new Set(flags);
    this.autoComments = dedupe(autoComments);
    this.userComments = dedupe(userComments);
    if (id && PYTHON_FORMAT.test(id)) {
      this.flags.add("python-format");
    } else {
      this.flags.delete("python-format");
    }
    if (id && hasPythonBraceFormat(id)) {
      this.flags.add("python-brace-format");
    } else {
      this.flags.delete("python-brace-format");
    }
  }

  get fuzzy() {
    return this.flags.has("fuzzy");
  }

  clone() {
    return new Message(this.id, this.string, {
      locations: this.locations,
      flags: this.flags,
      autoComments: this.autoComments,
      userComments: this.userComments,
    });
  }
}

// ---------------------------------------------------------------------------
// Catalog
// ---------------------------------------------------------------------------

function pad2(value) {
  return String(value).padStart(2, "0");
}

/** `format_datetime(now, "yyyy-MM-dd HH:mmZ")` in the local time zone. */
function nowHeaderDate() {
  const d = new Date();
  const offset = -d.getTimezoneOffset();
  const sign = offset < 0 ? "-" : "+";
  const abs = Math.abs(offset);
  return (
    `${d.getFullYear()}-${pad2(d.getMonth() + 1)}-${pad2(d.getDate())} ` +
    `${pad2(d.getHours())}:${pad2(d.getMinutes())}` +
    `${sign}${pad2(Math.floor(abs / 60))}${pad2(abs % 60)}`
  );
}

function parseMimeHeaders(text) {
  const headers = [];
  for (const line of text.split("\n")) {
    if (!line.trim()) {
      continue;
    }
    if (/^[ \t]/.test(line) && headers.length) {
      headers[headers.length - 1][1] += ` ${line.trim()}`;
      continue;
    }
    const colon = line.indexOf(":");
    if (colon > 0) {
      headers.push([line.slice(0, colon), line.slice(colon + 1).trim()]);
    }
  }
  return headers;
}

export class Catalog {
  /** @param {{ locale?: string | null, charset?: string | null, fuzzy?: boolean }} [options] */
  constructor({ locale = null, charset = null, fuzzy = true } = {}) {
    this.locale = locale;
    this.headerCommentRaw = DEFAULT_HEADER;
    this.messages = new Map();
    this.obsolete = new Map();
    this.project = "PROJECT";
    this.version = "VERSION";
    this.copyrightHolder = "ORGANIZATION";
    this.msgidBugsAddress = "EMAIL@ADDRESS";
    this.lastTranslator = "FULL NAME <EMAIL@ADDRESS>";
    this.languageTeam = "LANGUAGE <LL@li.org>";
    this.charset = charset || "utf-8";
    this.creationDate = nowHeaderDate();
    this.revisionDate = "YEAR-MO-DA HO:MI+ZONE";
    this.numPlurals = 2;
    this.pluralExpr = "(n != 1)";
    this.fuzzy = fuzzy;
  }

  get headerComment() {
    const revision = /^(\d{4})-/.exec(this.revisionDate);
    const year =
      revision && !this.revisionDate.includes("YEAR")
        ? revision[1]
        : String(new Date().getFullYear());
    let comment = this.headerCommentRaw
      .replaceAll("PROJECT", this.project)
      .replaceAll("VERSION", this.version)
      .replaceAll("YEAR", year)
      .replaceAll("ORGANIZATION", this.copyrightHolder);
    const localeName = this.locale
      ? (ENGLISH_NAMES[this.locale] ?? this.locale)
      : null;
    if (localeName) {
      comment = comment.replaceAll(
        "Translations template",
        `${localeName} translations`,
      );
    }
    return comment;
  }

  get mimeHeaders() {
    let languageTeam = this.languageTeam;
    if (this.locale && languageTeam.includes("LANGUAGE")) {
      languageTeam = languageTeam.replaceAll("LANGUAGE", this.locale);
    }
    const headers = [
      ["Project-Id-Version", `${this.project} ${this.version}`],
      ["Report-Msgid-Bugs-To", this.msgidBugsAddress],
      ["POT-Creation-Date", this.creationDate],
      ["PO-Revision-Date", this.revisionDate],
      ["Last-Translator", this.lastTranslator],
    ];
    if (this.locale) {
      headers.push(["Language", this.locale]);
    }
    headers.push(["Language-Team", languageTeam]);
    if (this.locale) {
      headers.push([
        "Plural-Forms",
        `nplurals=${this.numPlurals}; plural=${this.pluralExpr};`,
      ]);
    }
    headers.push(
      ["MIME-Version", "1.0"],
      ["Content-Type", `text/plain; charset=${this.charset}`],
      ["Content-Transfer-Encoding", "8bit"],
      ["Generated-By", `Babel ${BABEL_VERSION}\n`],
    );
    return headers;
  }

  setMimeHeaders(text) {
    for (const [rawName, value] of parseMimeHeaders(text)) {
      const name = rawName.toLowerCase();
      if (name === "project-id-version") {
        const parts = value.split(" ");
        this.project = parts.slice(0, -1).join(" ");
        this.version = parts[parts.length - 1];
      } else if (name === "report-msgid-bugs-to") {
        this.msgidBugsAddress = value;
      } else if (name === "last-translator") {
        this.lastTranslator = value;
      } else if (name === "language") {
        this.locale = value.replaceAll("-", "_") || null;
      } else if (name === "language-team") {
        this.languageTeam = value;
      } else if (name === "content-type") {
        const charset = /charset\s*=\s*"?([^;"\s]+)/i.exec(value);
        if (charset) {
          this.charset = charset[1].toLowerCase();
        }
      } else if (name === "plural-forms") {
        const nplurals = /nplurals\s*=\s*(\d+)/.exec(value);
        const plural = /plural\s*=\s*([^;]*)/.exec(value);
        this.numPlurals = nplurals ? Number(nplurals[1]) : 2;
        this.pluralExpr = plural ? plural[1].trim() : "(n != 1)";
      } else if (name === "pot-creation-date") {
        this.creationDate = value;
      } else if (name === "po-revision-date") {
        if (!value.includes("YEAR")) {
          this.revisionDate = value;
        }
      }
    }
  }

  get(id) {
    return this.messages.get(id);
  }

  has(id) {
    return this.messages.has(id);
  }

  /** `Catalog.__setitem__` for regular (non-header) messages. */
  set(message) {
    const current = this.messages.get(message.id);
    if (current) {
      current.locations = dedupeLocations([
        ...current.locations,
        ...message.locations,
      ]);
      current.autoComments = dedupe([
        ...current.autoComments,
        ...message.autoComments,
      ]);
      current.userComments = dedupe([
        ...current.userComments,
        ...message.userComments,
      ]);
      for (const flag of message.flags) {
        current.flags.add(flag);
      }
    } else {
      this.messages.set(message.id, message);
    }
  }

  /**
   * `Catalog.add`; `string` defaults to the empty msgstr (Babel: None).
   * @param {string} id
   * @param {{ string?: string, locations?: Location[], flags?: Iterable<string> }} [options]
   */
  add(id, { string = "", locations = [], flags = [] } = {}) {
    const message = new Message(id, string, { locations, flags });
    this.set(message);
    return message;
  }

  /**
   * `Catalog.update(template)` with fuzzy matching, like `pybabel update`.
   * @param {Catalog} template
   * @param {{ noFuzzyMatching?: boolean, updateHeaderComment?: boolean }} [options]
   */
  update(
    template,
    { noFuzzyMatching = false, updateHeaderComment = false } = {},
  ) {
    const messages = this.messages;
    const remaining = new Map(messages);
    this.messages = new Map();
    const fuzzyCandidates = new Map();
    if (!noFuzzyMatching) {
      for (const [msgid, message] of messages) {
        if (msgid && message.string) {
          fuzzyCandidates.set(msgid.toLowerCase().trim(), msgid);
        }
      }
    }
    const fuzzyMatches = new Set();

    const merge = (message, oldKey, newKey) => {
      const merged = message.clone();
      let fuzzy = false;
      let old;
      if (oldKey !== newKey) {
        fuzzy = true;
        fuzzyMatches.add(oldKey);
        old = messages.get(oldKey);
      } else {
        old = remaining.get(oldKey);
        remaining.delete(oldKey);
      }
      merged.string = old.string;
      if (old.userComments.length) {
        merged.userComments = dedupe(old.userComments);
      }
      for (const flag of old.flags) {
        merged.flags.add(flag);
      }
      if (fuzzy) {
        merged.flags.add("fuzzy");
      }
      this.set(merged);
    };

    for (const message of template.messages.values()) {
      if (!message.id) {
        continue;
      }
      if (messages.has(message.id)) {
        merge(message, message.id, message.id);
        continue;
      }
      if (!noFuzzyMatching) {
        const matches = getCloseMatches(message.id.toLowerCase().trim(), [
          ...fuzzyCandidates.keys(),
        ]);
        if (matches.length) {
          merge(message, fuzzyCandidates.get(matches[0]), message.id);
          continue;
        }
      }
      this.set(message);
    }
    for (const [msgid, message] of remaining) {
      if (noFuzzyMatching || !fuzzyMatches.has(msgid)) {
        this.obsolete.set(msgid, message);
      }
    }
    if (updateHeaderComment) {
      this.headerCommentRaw = template.headerComment;
    }
    this.creationDate = template.creationDate;
  }
}

// ---------------------------------------------------------------------------
// difflib.get_close_matches(word, possibilities, n=1, cutoff=0.6), with
// autojunk=False and no junk predicate (as in babel.messages.catalog).
// ---------------------------------------------------------------------------

class SequenceMatcher {
  constructor(b) {
    this.b = Array.from(b);
    this.b2j = new Map();
    this.b.forEach((element, j) => {
      const indexes = this.b2j.get(element);
      if (indexes) {
        indexes.push(j);
      } else {
        this.b2j.set(element, [j]);
      }
    });
    this.fullBCount = new Map();
    for (const element of this.b) {
      this.fullBCount.set(element, (this.fullBCount.get(element) ?? 0) + 1);
    }
  }

  setSeq1(a) {
    this.a = Array.from(a);
  }

  findLongestMatch(alo, ahi, blo, bhi) {
    const { a, b, b2j } = this;
    let besti = alo;
    let bestj = blo;
    let bestsize = 0;
    let j2len = new Map();
    for (let i = alo; i < ahi; i += 1) {
      const newJ2len = new Map();
      for (const j of b2j.get(a[i]) ?? []) {
        if (j < blo) {
          continue;
        }
        if (j >= bhi) {
          break;
        }
        const k = (j2len.get(j - 1) ?? 0) + 1;
        newJ2len.set(j, k);
        if (k > bestsize) {
          besti = i - k + 1;
          bestj = j - k + 1;
          bestsize = k;
        }
      }
      j2len = newJ2len;
    }
    while (besti > alo && bestj > blo && a[besti - 1] === b[bestj - 1]) {
      besti -= 1;
      bestj -= 1;
      bestsize += 1;
    }
    while (
      besti + bestsize < ahi &&
      bestj + bestsize < bhi &&
      a[besti + bestsize] === b[bestj + bestsize]
    ) {
      bestsize += 1;
    }
    return [besti, bestj, bestsize];
  }

  matchingSize() {
    const queue = [[0, this.a.length, 0, this.b.length]];
    let total = 0;
    while (queue.length) {
      const [alo, ahi, blo, bhi] = queue.pop();
      const [i, j, k] = this.findLongestMatch(alo, ahi, blo, bhi);
      if (k) {
        total += k;
        if (alo < i && blo < j) {
          queue.push([alo, i, blo, j]);
        }
        if (i + k < ahi && j + k < bhi) {
          queue.push([i + k, ahi, j + k, bhi]);
        }
      }
    }
    return total;
  }

  static ratioOf(matches, length) {
    return length ? (2.0 * matches) / length : 1.0;
  }

  realQuickRatio() {
    const la = this.a.length;
    const lb = this.b.length;
    return SequenceMatcher.ratioOf(Math.min(la, lb), la + lb);
  }

  quickRatio() {
    const avail = new Map();
    let matches = 0;
    for (const element of this.a) {
      const numb = avail.has(element)
        ? avail.get(element)
        : (this.fullBCount.get(element) ?? 0);
      avail.set(element, numb - 1);
      if (numb > 0) {
        matches += 1;
      }
    }
    return SequenceMatcher.ratioOf(matches, this.a.length + this.b.length);
  }

  ratio() {
    return SequenceMatcher.ratioOf(
      this.matchingSize(),
      this.a.length + this.b.length,
    );
  }
}

export function getCloseMatches(word, possibilities, cutoff = 0.6) {
  const matcher = new SequenceMatcher(word);
  let best = null;
  for (const candidate of possibilities) {
    matcher.setSeq1(candidate);
    if (matcher.realQuickRatio() >= cutoff && matcher.quickRatio() >= cutoff) {
      const score = matcher.ratio();
      if (score >= cutoff) {
        // max() over (score, candidate) tuples.
        if (
          !best ||
          score > best.score ||
          (score === best.score && compareStr(candidate, best.candidate) > 0)
        ) {
          best = { score, candidate };
        }
      }
    }
  }
  return best ? [best.candidate] : [];
}

// ---------------------------------------------------------------------------
// read_po
// ---------------------------------------------------------------------------

function normalizedString(...parts) {
  return parts.map((part) => part.trim());
}

function denormalize(parts) {
  return parts.map(unescapePo).join("");
}

function parseLocations(text) {
  if (!text.includes("⁨") && !text.includes("⁩")) {
    return text.trim().split(/\s+/).filter(Boolean);
  }
  const locations = [];
  let location = "";
  let inFilename = false;
  for (const c of text) {
    if (c === "⁨") {
      if (inFilename) {
        throw new Error("location comment has unbalanced isolate characters");
      }
      inFilename = true;
    } else if (c === "⁩") {
      if (!inFilename) {
        throw new Error("location comment has unbalanced isolate characters");
      }
      inFilename = false;
    } else if (c === " ") {
      if (inFilename) {
        location += c;
      } else if (location) {
        locations.push(location);
        location = "";
      }
    } else {
      location += c;
    }
  }
  if (location) {
    if (inFilename) {
      throw new Error("location comment has unbalanced isolate characters");
    }
    locations.push(location);
  }
  return locations;
}

/**
 * @param {string} text
 * @param {{ locale?: string | null }} [options]
 */
export function parsePo(text, { locale = null } = {}) {
  const catalog = new Catalog({ locale });
  let state;
  let counter = 0;
  const reset = () => {
    state = {
      messages: [],
      translations: [],
      locations: [],
      flags: [],
      userComments: [],
      autoComments: [],
      context: null,
      obsolete: false,
      inMsgid: false,
      inMsgstr: false,
      inMsgctxt: false,
    };
  };
  reset();

  const invalid = (line, lineno, message) => {
    console.log(`WARNING: ${message}`);
    console.log(
      `WARNING: Problem on line ${lineno + 1}: ${JSON.stringify(line)}`,
    );
  };

  const addMessage = () => {
    if (state.messages.length > 1) {
      throw new Error("plural messages are not supported");
    }
    if (state.context) {
      throw new Error("message contexts (msgctxt) are not supported");
    }
    const msgid = denormalize(state.messages[0]);
    const string = denormalize(state.translations[0][1]);
    const message = new Message(msgid, string, {
      locations: state.locations,
      flags: state.flags,
      autoComments: state.autoComments,
      userComments: state.userComments,
    });
    if (state.obsolete) {
      catalog.obsolete.set(msgid, message);
    } else if (msgid === "") {
      // Header message.
      catalog.setMimeHeaders(string);
      catalog.headerCommentRaw = message.userComments
        .map((comment) => `# ${comment}`.replace(/\s+$/, ""))
        .join("\n");
      catalog.fuzzy = message.fuzzy;
    } else {
      catalog.set(message);
    }
    counter += 1;
    reset();
  };

  const finishCurrent = (lineno) => {
    if (state.messages.length) {
      if (!state.translations.length) {
        invalid("", lineno, "missing msgstr");
        state.translations.push([0, normalizedString()]);
      }
      addMessage();
    }
  };

  const processKeywordLine = (lineno, line, obsolete) => {
    const space = line.indexOf(" ");
    const keyword = space === -1 ? line : line.slice(0, space);
    const arg = space === -1 ? "" : line.slice(space + 1);
    if (keyword === "msgid" || keyword === "msgctxt") {
      finishCurrent(lineno);
    }
    state.obsolete = obsolete;
    if (keyword === "msgid" || keyword === "msgid_plural") {
      state.inMsgctxt = false;
      state.inMsgid = true;
      state.messages.push(normalizedString(arg));
      return;
    }
    if (keyword === "msgctxt") {
      state.inMsgctxt = true;
      state.context = normalizedString(arg);
      return;
    }
    if (keyword === "msgstr" || keyword.startsWith("msgstr[")) {
      state.inMsgid = false;
      state.inMsgstr = true;
      const bracket = keyword.indexOf("[");
      const idx = bracket === -1 ? 0 : Number(keyword.slice(bracket + 1, -1));
      state.translations.push([
        idx,
        arg !== '""' ? normalizedString(arg) : normalizedString(),
      ]);
      return;
    }
    invalid(line, lineno, "Unknown or misformatted keyword");
  };

  const processMessageLine = (lineno, line, obsolete) => {
    if (!line) {
      return;
    }
    if (line[0] === '"') {
      let target;
      if (state.inMsgid) {
        target = state.messages[state.messages.length - 1];
      } else if (state.inMsgstr) {
        target = state.translations[state.translations.length - 1][1];
      } else if (state.inMsgctxt) {
        target = state.context;
      } else {
        invalid(
          line,
          lineno,
          'Got line starting with " but not in msgid, msgstr or msgctxt',
        );
        return;
      }
      target.push(line.trim());
    } else {
      processKeywordLine(lineno, line, obsolete);
    }
  };

  const processComment = (lineno, line) => {
    finishCurrent(lineno);
    const prefix = line.slice(0, 2);
    if (prefix === "#:") {
      for (const location of parseLocations(line.slice(2))) {
        const colon = location.lastIndexOf(":");
        if (colon !== -1) {
          const tail = location.slice(colon + 1);
          if (/^[+-]?\d+$/.test(tail.trim())) {
            state.locations.push([location.slice(0, colon), Number(tail)]);
          }
        } else {
          state.locations.push([location, null]);
        }
      }
      return;
    }
    if (prefix === "#,") {
      state.flags.push(
        ...line
          .slice(2)
          .trimStart()
          .split(",")
          .map((flag) => flag.trim()),
      );
      return;
    }
    if (prefix === "#.") {
      const comment = line.slice(2).trim();
      if (comment) {
        state.autoComments.push(comment);
      }
      return;
    }
    state.userComments.push(line.slice(1).trim());
  };

  text.split(/\r\n|\r|\n/).forEach((rawLine, lineno) => {
    const line = rawLine.trim();
    if (!line) {
      return;
    }
    if (line[0] === "#") {
      if (line.slice(0, 2) === "#~") {
        processMessageLine(lineno, line.slice(2).trimStart(), true);
      } else {
        processComment(lineno, line);
      }
    } else {
      processMessageLine(lineno, line, false);
    }
  });
  finishCurrent(-1);
  return catalog;
}

/**
 * @param {string} path
 * @param {{ locale?: string | null }} [options]
 */
export function readPo(path, options = {}) {
  return parsePo(readText(path), options);
}

// ---------------------------------------------------------------------------
// write_po
// ---------------------------------------------------------------------------

function compareLocations(x, y) {
  const byFile = compareStr(x[0], y[0]);
  if (byFile) {
    return byFile;
  }
  return (x[1] || -1) - (y[1] || -1);
}

function encloseFilename(filename) {
  if (!filename.includes(" ") && !filename.includes("\t")) {
    return filename;
  }
  let result = filename;
  if (!result.startsWith("⁨")) {
    result = `⁨${result}`;
  }
  if (!result.endsWith("⁩")) {
    result += "⁩";
  }
  return result;
}

/**
 * @param {Catalog} catalog
 * @param {{ width?: number, ignoreObsolete?: boolean, omitHeader?: boolean }} [options]
 */
export function renderPo(
  catalog,
  { width = 76, ignoreObsolete = false, omitHeader = false } = {},
) {
  const out = [];
  const commentWidth = width && width > 0 ? width : 76;
  const formatComment = (comment, prefix = "") => {
    for (const line of wrapText(comment, commentWidth)) {
      out.push(`#${prefix} ${line.trim()}\n`);
    }
  };
  const formatMessage = (message, prefix = "") => {
    out.push(
      `${prefix}msgid ${normalizePo(message.id, prefix, width)}\n`,
      `${prefix}msgstr ${normalizePo(message.string || "", prefix, width)}\n`,
    );
  };

  if (!omitHeader) {
    let header = catalog.headerComment;
    if (width && width > 0) {
      const lines = [];
      for (const line of splitlines(header)) {
        lines.push(...wrapText(line, width, "# "));
      }
      header = lines.join("\n");
    }
    out.push(`${header}\n`);
    if (catalog.fuzzy) {
      out.push("#, fuzzy\n");
    }
    const headerString = catalog.mimeHeaders
      .map(([name, value]) => `${name}: ${value}`)
      .join("\n");
    out.push(
      `msgid ${normalizePo("", "", width)}\n`,
      `msgstr ${normalizePo(headerString, "", width)}\n`,
      "\n",
    );
  }
  for (const message of catalog.messages.values()) {
    for (const comment of message.userComments) {
      formatComment(comment);
    }
    for (const comment of message.autoComments) {
      formatComment(comment, ".");
    }
    const locs = [];
    const sorted = [...message.locations].sort(compareLocations);
    for (const [filename, lineno] of sorted) {
      let location = encloseFilename(filename.replaceAll("\\", "/"));
      if (lineno) {
        location = `${location}:${lineno}`;
      }
      if (!locs.includes(location)) {
        locs.push(location);
      }
    }
    formatComment(locs.join(" "), ":");
    if (message.flags.size) {
      const flags = [...message.flags].sort(compareStr);
      out.push(`#, ${flags.join(", ")}\n`);
    }
    formatMessage(message);
    out.push("\n");
  }
  if (!ignoreObsolete) {
    for (const message of catalog.obsolete.values()) {
      for (const comment of message.userComments) {
        formatComment(comment);
      }
      formatMessage(message, "#~ ");
      out.push("\n");
    }
  }
  // Babel ends with a blank line; the repository's end-of-file-fixer hook keeps
  // exactly one trailing newline, so match it rather than fight it on every edit.
  return out.join("").replace(/\n+$/, "\n");
}

/**
 * @param {string} path
 * @param {Catalog} catalog
 * @param {{ width?: number, ignoreObsolete?: boolean, omitHeader?: boolean }} [options]
 */
export function writePo(path, catalog, options = {}) {
  writeText(path, renderPo(catalog, options));
}
