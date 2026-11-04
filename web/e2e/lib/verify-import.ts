// Verify an isolated Rust import against a copied legacy SQLite snapshot.
//
// Port of ci/stricknani/verify_rust_import.py. Uses the built-in `node:sqlite`
// module (Node 22.13+). The module has no relative imports so it can also run
// directly: `node e2e/lib/verify-import.ts --source-db ...`.
import { createHash } from "node:crypto";
import {
  closeSync,
  existsSync,
  openSync,
  readSync,
  realpathSync,
  statSync,
} from "node:fs";
import { DatabaseSync } from "node:sqlite";
import { basename, dirname, isAbsolute, join, sep } from "node:path";
import { pathToFileURL } from "node:url";

/** Safe verifier error; never includes input paths or database values. */
export class VerificationError extends Error {
  constructor(message: string, options?: ErrorOptions) {
    super(message, options);
    this.name = "VerificationError";
  }
}

export interface Report {
  checks: Record<string, boolean>;
  counts: Record<string, Record<string, number>>;
  mismatches: string[];
}

type Value = string | number | bigint | null | Uint8Array;
type Row = Record<string, Value>;
type Cell = Value | undefined;

export function canonicalPath(value: string, directory: boolean): string {
  let resolved: string;
  try {
    resolved = realpathSync(value);
  } catch (error) {
    throw new VerificationError("invalid_input_path", { cause: error });
  }
  if (!isAbsolute(value) || value !== resolved) {
    throw new VerificationError("input_path_not_canonical");
  }
  const info = statSync(resolved);
  if (directory !== info.isDirectory() || (!directory && !info.isFile())) {
    throw new VerificationError("input_path_wrong_type");
  }
  return resolved;
}

/** Rows that throw on access to a column the query did not return. */
function strictRow(row: Record<string, unknown>): Row {
  return new Proxy(row as Row, {
    get(target, property, receiver) {
      if (typeof property === "string" && !(property in target)) {
        throw new TypeError("missing column");
      }
      return Reflect.get(target, property, receiver) as Value;
    },
  });
}

function open(path: string): DatabaseSync {
  for (const suffix of ["-wal", "-shm"]) {
    if (existsSync(join(dirname(path), basename(path) + suffix))) {
      throw new VerificationError("sqlite_sidecar_present");
    }
  }
  try {
    const connection = new DatabaseSync(
      pathToFileURL(path).href + "?mode=ro&immutable=1",
      { readOnly: true },
    );
    connection.exec("PRAGMA query_only = ON");
    return connection;
  } catch (error) {
    throw new VerificationError("sqlite_open_failed", { cause: error });
  }
}

function allRows(
  connection: DatabaseSync,
  query: string,
  ...parameters: (string | number)[]
): Row[] {
  return connection
    .prepare(query)
    .all(...parameters)
    .map((row) => strictRow(row));
}

/** Positional values of a row; column order follows the query. */
function values(row: Row): Cell[] {
  return Object.values(row);
}

function one(
  connection: DatabaseSync,
  query: string,
  ...parameters: (string | number)[]
): Row {
  const row = allRows(connection, query, ...parameters)[0];
  if (row === undefined) {
    throw new TypeError("missing row");
  }
  return row;
}

function count(connection: DatabaseSync, table: string): number {
  return Number(values(one(connection, `SELECT COUNT(*) FROM "${table}"`))[0]);
}

const ISO =
  /^(\d{4})-(\d{2})-(\d{2})(?:[T ](\d{2})(?::(\d{2})(?::(\d{2})(?:[.,](\d{1,6})\d*)?)?)?)?\s*(Z|[+-]\d{2}(?::?\d{2})?)?$/i;

/** Timestamp as microseconds since the epoch (UTC); naive values are UTC. */
function timestamp(value: Cell): number | null {
  if (value === null || value === undefined) {
    return null;
  }
  if (typeof value === "number" || typeof value === "bigint") {
    return Math.round(Number(value) * 1_000_000);
  }
  const text = String(value);
  const match = ISO.exec(text);
  if (match === null) {
    throw new RangeError("invalid isoformat string");
  }
  const [, year, month, day, hour, minute, second, fraction, zone] = match;
  const millis = Date.UTC(
    Number(year),
    Number(month) - 1,
    Number(day),
    Number(hour ?? 0),
    Number(minute ?? 0),
    Number(second ?? 0),
  );
  const checked = new Date(millis);
  if (
    Number.isNaN(millis) ||
    checked.getUTCMonth() !== Number(month) - 1 ||
    checked.getUTCDate() !== Number(day) ||
    Number(hour ?? 0) > 23 ||
    Number(minute ?? 0) > 59 ||
    Number(second ?? 0) > 59
  ) {
    throw new RangeError("invalid isoformat string");
  }
  const micros = Number((fraction ?? "").padEnd(6, "0") || 0);
  let offsetMinutes = 0;
  if (zone !== undefined && zone.toUpperCase() !== "Z") {
    const sign = zone.startsWith("-") ? -1 : 1;
    const digits = zone.slice(1).replace(":", "");
    offsetMinutes =
      sign * (Number(digits.slice(0, 2)) * 60 + Number(digits.slice(2) || 0));
  }
  return (millis - offsetMinutes * 60_000) * 1000 + micros;
}

function epochSeconds(value: Cell): number | null {
  const parsed = timestamp(value);
  return parsed === null ? null : Math.trunc(parsed / 1_000_000);
}

/** Stable key for comparing a row of scalar values (None/null included). */
function key(items: readonly Cell[]): string {
  return JSON.stringify(items, (_name, item: unknown) =>
    typeof item === "bigint" ? Number(item) : item,
  );
}

function fileDigest(path: string, root: string): string {
  try {
    const resolved = realpathSync(path);
    if (
      resolved !== path ||
      !statSync(resolved).isFile() ||
      !resolved.startsWith(root + sep)
    ) {
      throw new VerificationError("unsafe_media_path");
    }
    const digest = createHash("sha256");
    const descriptor = openSync(resolved, "r");
    try {
      const chunk = Buffer.alloc(1024 * 1024);
      for (;;) {
        const read = readSync(descriptor, chunk, 0, chunk.length, null);
        if (read === 0) break;
        digest.update(chunk.subarray(0, read));
      }
    } finally {
      closeSync(descriptor);
    }
    return digest.digest("hex");
  } catch (error) {
    if (error instanceof VerificationError) {
      throw error;
    }
    throw new VerificationError("media_read_failed", { cause: error });
  }
}

function filesMatch(
  source: string,
  target: string,
  sourceRoot: string,
  targetRoot: string,
): boolean {
  try {
    return fileDigest(source, sourceRoot) === fileDigest(target, targetRoot);
  } catch (error) {
    if (error instanceof VerificationError) {
      return false;
    }
    throw error;
  }
}

function compareRows(
  report: Report,
  name: string,
  sourceRows: readonly (readonly Cell[])[],
  targetRows: readonly (readonly Cell[])[],
  allowTargetExtras = false,
): void {
  const toMap = (rows: readonly (readonly Cell[])[]): Map<string, string> =>
    new Map(rows.map((row) => [key([row[0]]), key(row.slice(1))]));
  const source = toMap(sourceRows);
  const target = toMap(targetRows);
  let missing = 0;
  let different = 0;
  let extra = 0;
  for (const [id, value] of source) {
    const other = target.get(id);
    if (other === undefined) {
      missing += 1;
    } else if (other !== value) {
      different += 1;
    }
  }
  for (const id of target.keys()) {
    if (!source.has(id)) {
      extra += 1;
    }
  }
  report.checks[name] =
    missing === 0 && different === 0 && (allowTargetExtras || extra === 0);
  if (missing) {
    report.mismatches.push(`${name}.missing_rows:${missing}`);
  }
  if (different) {
    report.mismatches.push(`${name}.changed_rows:${different}`);
  }
  if (extra && !allowTargetExtras) {
    report.mismatches.push(`${name}.extra_rows:${extra}`);
  }
}

function checkCounts(
  report: Report,
  name: string,
  sourceCount: number,
  targetCount: number,
  allowTargetExtras = false,
): void {
  report.counts[name] = { source: sourceCount, target: targetCount };
  const passed = allowTargetExtras
    ? targetCount >= sourceCount
    : targetCount === sourceCount;
  report.checks[`count_${name}`] = passed;
  if (!passed) {
    report.mismatches.push(`count_${name}`);
  }
}

function tableNames(connection: DatabaseSync): Set<string> {
  return new Set(
    allRows(
      connection,
      "SELECT name FROM sqlite_master WHERE type='table'",
    ).map((row) => String(row.name)),
  );
}

function isSubset<T>(subset: Iterable<T>, superset: Set<T>): boolean {
  return [...subset].every((item) => superset.has(item));
}

function pick(row: Row, columns: readonly string[]): Cell[] {
  return columns.map((column) => row[column]);
}

function sameJson(left: readonly Cell[], right: readonly Cell[]): boolean {
  return key(left) === key(right);
}

const YARN_FIELDS = [
  "id",
  "owner_id",
  "name",
  "description",
  "brand",
  "colorway",
  "dye_lot",
  "fiber_content",
  "weight_category",
  "recommended_needles",
  "weight_grams",
  "length_meters",
  "notes",
  "link",
  "link_archive",
] as const;

const PROJECT_LEADING_FIELDS = [
  "id",
  "owner_id",
  "name",
  "needles",
  "stitch_sample",
  "description",
  "notes",
  "other_materials",
] as const;

export function compareImport(
  sourceDb: string,
  sourceMedia: string,
  destination: string,
): Report {
  const rustDb = join(destination, "database.sqlite3");
  const rustMedia = join(destination, "media");
  if (
    !existsSync(rustDb) ||
    !statSync(rustDb).isFile() ||
    !existsSync(rustMedia) ||
    !statSync(rustMedia).isDirectory() ||
    realpathSync(rustDb) !== rustDb
  ) {
    throw new VerificationError("destination_incomplete");
  }
  const source = open(sourceDb);
  let target: DatabaseSync;
  try {
    target = open(rustDb);
  } catch (error) {
    source.close();
    throw error;
  }
  const report: Report = { checks: {}, counts: {}, mismatches: [] };
  try {
    const sourceTables = tableNames(source);
    const targetTables = tableNames(target);
    const requiredSource = [
      "users",
      "api_tokens",
      "categories",
      "yarns",
      "projects",
      "steps",
      "project_yarns",
      "user_favorites",
      "user_favorite_yarns",
      "images",
      "attachments",
      "yarn_images",
      "audit_logs",
    ];
    const requiredTarget = [
      "users",
      "api_tokens",
      "categories",
      "yarns",
      "projects",
      "project_steps",
      "project_yarns",
      "user_favorites",
      "user_favorite_yarns",
      "media_files",
      "media_url_aliases",
      "user_avatars",
      "audit_events",
    ];
    report.checks.required_schema =
      isSubset(requiredSource, sourceTables) &&
      isSubset(requiredTarget, targetTables);
    if (!report.checks.required_schema) {
      report.mismatches.push("required_schema");
      return report;
    }

    const sourceIntegrity =
      values(one(source, "PRAGMA integrity_check"))[0] === "ok";
    report.checks.source_integrity = sourceIntegrity;
    if (!sourceIntegrity) {
      report.mismatches.push("source_integrity");
    }

    const sourceFk = allRows(source, "PRAGMA foreign_key_check")
      .map(values)
      .filter((row) => !(row[0] === "audit_logs" && row[2] === "users"));
    const targetFk = allRows(target, "PRAGMA foreign_key_check");
    report.checks.source_relationships = sourceFk.length === 0;
    report.checks.target_foreign_keys = targetFk.length === 0;
    if (sourceFk.length) {
      report.mismatches.push(`source_relationships:${sourceFk.length}`);
    }
    if (targetFk.length) {
      report.mismatches.push(`target_foreign_keys:${targetFk.length}`);
    }

    const mappings: [string, string, boolean][] = [
      ["users", "users", false],
      ["api_tokens", "api_tokens", false],
      ["categories", "categories", true],
      ["yarns", "yarns", false],
      ["projects", "projects", false],
      ["steps", "project_steps", false],
      ["project_yarns", "project_yarns", false],
      ["user_favorites", "user_favorites", false],
      ["user_favorite_yarns", "user_favorite_yarns", false],
    ];
    for (const [sourceName, targetName, allowExtra] of mappings) {
      checkCounts(
        report,
        sourceName,
        count(source, sourceName),
        count(target, targetName),
        allowExtra,
      );
    }

    compareRows(
      report,
      "user_identity_and_passwords",
      allRows(
        source,
        "SELECT id,email,hashed_password,is_active,is_admin,token_version," +
          "created_at,profile_image FROM users ORDER BY id",
      ).map((row) => [
        row.id,
        row.email,
        row.hashed_password,
        row.is_active,
        row.is_admin,
        row.token_version,
        timestamp(row.created_at),
        row.profile_image,
      ]),
      allRows(
        target,
        "SELECT id,legacy_email,password_hash,active,is_admin," +
          "legacy_token_version,created_at,profile_image " +
          "FROM users ORDER BY id",
      ).map((row) => [
        row.id,
        row.legacy_email,
        row.password_hash,
        row.active,
        row.is_admin,
        row.legacy_token_version,
        timestamp(row.created_at),
        row.profile_image,
      ]),
    );
    // Login email is normalized; exact source spelling remains in legacy_email.
    const oldEmails = new Map(
      allRows(source, "SELECT id,email FROM users").map((row) => [
        key([row.id]),
        String(row.email).trim().toLowerCase(),
      ]),
    );
    const newEmails = new Map(
      allRows(target, "SELECT id,email FROM users").map((row) => [
        key([row.id]),
        row.email,
      ]),
    );
    let normalizedEmailOk = oldEmails.size === newEmails.size;
    for (const [id, email] of oldEmails) {
      if (newEmails.get(id) !== email) {
        normalizedEmailOk = false;
      }
    }
    report.checks.normalized_login_emails = normalizedEmailOk;
    if (!normalizedEmailOk) {
      report.mismatches.push("normalized_login_emails");
    }

    const tokenQuery =
      "SELECT id,user_id,token_hash,name,created_at,last_used_at,expires_at " +
      "FROM api_tokens ORDER BY id";
    const tokenSource = new Map(
      allRows(source, tokenQuery).map((row) => [key([row.id]), values(row)]),
    );
    const tokenTarget = new Map(
      allRows(target, tokenQuery).map((row) => [key([row.id]), values(row)]),
    );
    let tokenOk = [...tokenSource.keys()].every((id) => tokenTarget.has(id));
    if (tokenOk) {
      for (const [id, sourceToken] of tokenSource) {
        const targetToken = tokenTarget.get(id) as Cell[];
        if (!sameJson(sourceToken.slice(1, 4), targetToken.slice(1, 4))) {
          tokenOk = false;
          break;
        }
        const sourceTimes = [4, 5, 6].map((index) =>
          epochSeconds(sourceToken[index]),
        );
        const targetTimes = [4, 5, 6].map((index) => targetToken[index]);
        if (!sameJson(sourceTimes, targetTimes)) {
          tokenOk = false;
          break;
        }
      }
    }
    report.checks.api_token_hashes_and_timestamps = tokenOk;
    if (!tokenOk) {
      report.mismatches.push("api_token_hashes_and_timestamps");
    }

    compareRows(
      report,
      "yarn_ownership",
      allRows(source, "SELECT id, owner_id FROM yarns ORDER BY id").map(values),
      allRows(target, "SELECT id, owner_id FROM yarns ORDER BY id").map(values),
    );
    compareRows(
      report,
      "project_ownership_and_legacy_yarn",
      allRows(
        source,
        "SELECT id, owner_id, yarn FROM projects ORDER BY id",
      ).map(values),
      allRows(
        target,
        "SELECT id, owner_id, legacy_yarn_text FROM projects ORDER BY id",
      ).map(values),
    );
    compareRows(
      report,
      "yarn_record_fields_and_timestamps",
      allRows(source, "SELECT * FROM yarns ORDER BY id").map((row) => [
        ...pick(row, YARN_FIELDS),
        timestamp(row.link_archive_requested_at),
        row.link_archive_failed,
        row.is_ai_enhanced,
        timestamp(row.created_at),
        timestamp(row.updated_at),
      ]),
      allRows(target, "SELECT * FROM yarns ORDER BY id").map((row) => [
        ...pick(row, YARN_FIELDS),
        timestamp(row.archive_requested_at),
        row.archive_failed,
        row.is_ai_enhanced,
        timestamp(row.created_at),
        timestamp(row.updated_at),
      ]),
    );
    compareRows(
      report,
      "project_record_fields_and_timestamps",
      allRows(source, "SELECT * FROM projects ORDER BY id").map((row) => [
        ...pick(row, PROJECT_LEADING_FIELDS),
        row.tags || "[]",
        row.link,
        row.link_archive,
        row.is_ai_enhanced,
        timestamp(row.created_at),
        timestamp(row.updated_at),
        row.yarn,
        timestamp(row.link_archive_requested_at),
        row.link_archive_failed,
      ]),
      allRows(target, "SELECT * FROM projects ORDER BY id").map((row) => [
        ...pick(row, PROJECT_LEADING_FIELDS),
        row.tags,
        row.link,
        row.link_archive,
        row.is_ai_enhanced,
        timestamp(row.created_at),
        timestamp(row.updated_at),
        row.legacy_yarn_text,
        timestamp(row.archive_requested_at),
        row.archive_failed,
      ]),
    );

    const tables: {
      sourceTable: string;
      targetTable: string;
      sourceCols: string;
      targetCols: string;
      orderKey: string | null;
    }[] = [
      {
        sourceTable: "categories",
        targetTable: "categories",
        sourceCols: "id,user_id,name,created_at",
        targetCols: "id,user_id,name,created_at",
        orderKey: "id",
      },
      {
        sourceTable: "steps",
        targetTable: "project_steps",
        sourceCols: "id,project_id,title,description,step_number,created_at",
        targetCols: "id,project_id,title,description,step_number,created_at",
        orderKey: "id",
      },
      {
        sourceTable: "project_yarns",
        targetTable: "project_yarns",
        sourceCols: "project_id,yarn_id",
        targetCols: "project_id,yarn_id",
        orderKey: null,
      },
      {
        sourceTable: "user_favorites",
        targetTable: "user_favorites",
        sourceCols: "user_id,project_id",
        targetCols: "user_id,project_id",
        orderKey: null,
      },
      {
        sourceTable: "user_favorite_yarns",
        targetTable: "user_favorite_yarns",
        sourceCols: "user_id,yarn_id",
        targetCols: "user_id,yarn_id",
        orderKey: null,
      },
    ];
    for (const spec of tables) {
      const { sourceTable, targetTable, sourceCols, targetCols, orderKey } =
        spec;
      if (orderKey !== null) {
        const sourceRows = allRows(
          source,
          `SELECT ${sourceCols} FROM ${sourceTable} ORDER BY ${orderKey}`,
        ).map(values);
        const targetRows = allRows(
          target,
          `SELECT ${targetCols} FROM ${targetTable} ORDER BY ${orderKey}`,
        ).map(values);
        const normalize = (rows: Cell[][]): Cell[][] =>
          sourceTable === "categories" || sourceTable === "steps"
            ? rows.map((row) => [
                ...row.slice(0, -1),
                timestamp(row[row.length - 1]),
              ])
            : rows;
        compareRows(
          report,
          `${sourceTable}_records`,
          normalize(sourceRows),
          normalize(targetRows),
          sourceTable === "categories",
        );
      } else {
        const tally = (rows: Cell[][]): Map<string, number> => {
          const counter = new Map<string, number>();
          for (const row of rows) {
            const item = key(row);
            counter.set(item, (counter.get(item) ?? 0) + 1);
          }
          return counter;
        };
        const sourcePairs = tally(
          allRows(source, `SELECT ${sourceCols} FROM ${sourceTable}`).map(
            values,
          ),
        );
        const targetPairs = tally(
          allRows(target, `SELECT ${targetCols} FROM ${targetTable}`).map(
            values,
          ),
        );
        let ok = sourcePairs.size === targetPairs.size;
        for (const [item, total] of sourcePairs) {
          if (targetPairs.get(item) !== total) {
            ok = false;
          }
        }
        report.checks[`${sourceTable}_records`] = ok;
        if (!ok) {
          report.mismatches.push(`${sourceTable}_records`);
        }
      }
    }

    const sourceMediaRecords: [string, Row][] = [];
    for (const table of ["images", "attachments", "yarn_images"]) {
      for (const row of allRows(source, `SELECT * FROM ${table} ORDER BY id`)) {
        sourceMediaRecords.push([table, row]);
      }
    }
    const targetMedia = new Map<string, Row>(
      allRows(
        target,
        "SELECT * FROM media_files WHERE legacy_source IS NOT NULL",
      ).map((row) => [key([row.legacy_source, row.legacy_id]), row]),
    );
    const ownerOf = (table: "projects" | "yarns", id: Cell): Cell =>
      values(
        one(source, `SELECT owner_id FROM ${table} WHERE id=?`, id as number),
      )[0];
    let mediaOk = true;
    let namespaceOk = true;
    for (const [table, row] of sourceMediaRecords) {
      const imported = targetMedia.get(key([table, row.id]));
      if (imported === undefined) {
        mediaOk = false;
        continue;
      }
      let subdir: string;
      let parentId: Cell;
      if (table === "images") {
        subdir = "projects";
        parentId = row.project_id;
        const owner = ownerOf("projects", parentId);
        const got = [
          imported.owner_id,
          imported.project_id,
          imported.step_id,
          imported.kind,
          imported.original_filename,
          imported.alt_text,
          imported.legacy_image_type,
          imported.is_stitch_sample,
          imported.legacy_is_title_image,
          timestamp(imported.created_at),
        ];
        const wanted = [
          owner,
          row.project_id,
          row.step_id,
          row.step_id !== null ? "step" : "title",
          row.original_filename,
          row.alt_text,
          row.image_type,
          row.is_stitch_sample,
          row.is_title_image,
          timestamp(row.created_at),
        ];
        mediaOk = sameJson(got, wanted) && imported.is_primary === 0 && mediaOk;
      } else if (table === "attachments") {
        subdir = "projects";
        parentId = row.project_id;
        const owner = ownerOf("projects", parentId);
        mediaOk =
          sameJson(
            [
              imported.owner_id,
              imported.project_id,
              imported.kind,
              imported.original_filename,
              imported.content_type,
              imported.size_bytes,
              timestamp(imported.created_at),
            ],
            [
              owner,
              parentId,
              "attachment",
              row.original_filename,
              row.content_type,
              row.size_bytes,
              timestamp(row.created_at),
            ],
          ) && mediaOk;
      } else {
        subdir = "yarns";
        parentId = row.yarn_id;
        const owner = ownerOf("yarns", parentId);
        mediaOk =
          sameJson(
            [
              imported.owner_id,
              imported.yarn_id,
              imported.kind,
              imported.original_filename,
              imported.alt_text,
              imported.is_primary,
              timestamp(imported.created_at),
            ],
            [
              owner,
              parentId,
              "photo",
              row.original_filename,
              row.alt_text,
              row.is_primary,
              timestamp(row.created_at),
            ],
          ) && mediaOk;
      }
      namespaceOk = key([imported.public_id]) === key([row.id]) && namespaceOk;
      const sourcePath = join(
        sourceMedia,
        subdir,
        String(parentId),
        String(row.filename),
      );
      const targetPath = join(
        rustMedia,
        subdir,
        String(parentId),
        String(imported.filename),
      );
      mediaOk =
        filesMatch(sourcePath, targetPath, sourceMedia, rustMedia) && mediaOk;
    }
    for (const table of ["images", "attachments", "yarn_images"]) {
      checkCounts(
        report,
        `media_${table}`,
        count(source, table),
        Number(
          values(
            one(
              target,
              "SELECT COUNT(*) FROM media_files WHERE legacy_source=?",
              table,
            ),
          )[0],
        ),
      );
    }
    report.checks.media_records_and_original_bytes = mediaOk;
    report.checks.media_public_id_namespaces = namespaceOk;
    if (!mediaOk) {
      report.mismatches.push("media_records_and_original_bytes");
    }
    if (!namespaceOk) {
      report.mismatches.push("media_public_id_namespaces");
    }

    let aliasOk = true;
    const targetAliases = new Map<string, Cell>(
      allRows(target, "SELECT * FROM media_url_aliases").map((row) => [
        key([row.subdir, row.parent_id, row.legacy_filename]),
        row.media_id,
      ]),
    );
    const expectedAliasRecords = new Map<string, Set<string>>();
    for (const [table, row] of sourceMediaRecords) {
      const subdir = table === "yarn_images" ? "yarns" : "projects";
      const parentId = table === "yarn_images" ? row.yarn_id : row.project_id;
      const aliasKey = key([subdir, parentId, row.filename]);
      const records = expectedAliasRecords.get(aliasKey) ?? new Set<string>();
      records.add(key([table, row.id]));
      expectedAliasRecords.set(aliasKey, records);
    }
    for (const [aliasKey, expectedRecords] of expectedAliasRecords) {
      const aliasMediaId = targetAliases.get(aliasKey);
      if (aliasMediaId === undefined || aliasMediaId === null) {
        aliasOk = false;
        continue;
      }
      const associated = allRows(
        target,
        "SELECT legacy_source,legacy_id FROM media_files WHERE id=?",
        aliasMediaId as number,
      )[0];
      if (
        associated === undefined ||
        !expectedRecords.has(
          key([associated.legacy_source, associated.legacy_id]),
        )
      ) {
        aliasOk = false;
      }
    }
    report.checks.legacy_media_aliases = aliasOk;
    if (!aliasOk) {
      report.mismatches.push("legacy_media_aliases");
    }

    const categoryTargets = new Set(
      allRows(target, "SELECT user_id,name FROM categories").map((row) =>
        key([row.user_id, row.name]),
      ),
    );
    const categoryLinksOk = allRows(
      source,
      "SELECT owner_id,category FROM projects",
    ).every(
      (row) =>
        row.category === null ||
        categoryTargets.has(key([row.owner_id, row.category])),
    );
    report.checks.project_category_ownership = categoryLinksOk;
    if (!categoryLinksOk) {
      report.mismatches.push("project_category_ownership");
    }

    const avatarRows = allRows(
      source,
      "SELECT id,profile_image FROM users WHERE profile_image IS NOT NULL " +
        "ORDER BY id",
    );
    const avatars = new Map(
      allRows(target, "SELECT * FROM user_avatars").map((row) => [
        key([row.user_id]),
        row,
      ]),
    );
    let avatarOk = avatarRows.length === avatars.size;
    for (const row of avatarRows) {
      const imported = avatars.get(key([row.id]));
      if (
        imported === undefined ||
        imported.original_filename !== row.profile_image
      ) {
        avatarOk = false;
        continue;
      }
      avatarOk =
        filesMatch(
          join(sourceMedia, "users", String(row.id), String(row.profile_image)),
          join(rustMedia, "users", String(row.id), String(imported.filename)),
          sourceMedia,
          rustMedia,
        ) && avatarOk;
    }
    report.counts.user_avatars = {
      source: avatarRows.length,
      target: avatars.size,
    };
    report.checks.avatars_and_original_bytes = avatarOk;
    if (!avatarOk) {
      report.mismatches.push("avatars_and_original_bytes");
    }

    const auditRows = allRows(
      source,
      "SELECT id,actor_user_id,entity_type,entity_id,action,details,created_at " +
        "FROM audit_logs ORDER BY id",
    );
    const knownUsers = new Set(
      allRows(source, "SELECT id FROM users").map((row) => key([row.id])),
    );
    const auditTarget = new Map(
      allRows(target, "SELECT * FROM audit_events").map((row) => [
        key([row.id]),
        row,
      ]),
    );
    let auditOk = true;
    for (const row of auditRows) {
      const event = auditTarget.get(key([row.id]));
      const actor = knownUsers.has(key([row.actor_user_id]))
        ? row.actor_user_id
        : null;
      if (
        event === undefined ||
        !sameJson(
          [
            event.actor_user_id,
            event.legacy_actor_user_id,
            event.entity_type,
            event.entity_id,
            event.action,
            event.details,
            timestamp(event.created_at),
          ],
          [
            actor,
            row.actor_user_id,
            row.entity_type,
            row.entity_id,
            row.action,
            row.details,
            timestamp(row.created_at),
          ],
        )
      ) {
        auditOk = false;
      }
    }
    report.counts.audit_events = {
      legacy: auditRows.length,
      rust_total: auditTarget.size,
      native_extra: Math.max(0, auditTarget.size - auditRows.length),
    };
    report.checks.legacy_audit_preserved = auditOk;
    if (!auditOk) {
      report.mismatches.push("legacy_audit_preserved");
    }
    return report;
  } catch (error) {
    if (error instanceof VerificationError) {
      throw error;
    }
    throw new VerificationError("database_schema_or_data_invalid", {
      cause: error,
    });
  } finally {
    source.close();
    target.close();
  }
}

function sortedJson(value: unknown): string {
  const sort = (item: unknown): unknown => {
    if (Array.isArray(item)) {
      return item.map(sort);
    }
    if (item !== null && typeof item === "object") {
      return Object.fromEntries(
        Object.entries(item as Record<string, unknown>)
          .sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0))
          .map(([name, child]) => [name, sort(child)]),
      );
    }
    return item;
  };
  return JSON.stringify(sort(value));
}

const OPTION_NAMES = ["--source-db", "--source-media", "--destination"];

function parseArguments(argv: readonly string[]): Record<string, string> {
  const parsed: Record<string, string> = {};
  for (let index = 0; index < argv.length; index += 1) {
    const argument = argv[index] as string;
    const [name, inline] = argument.split(/=(.*)/s, 2) as [string, string?];
    if (!OPTION_NAMES.includes(name)) {
      throw new VerificationError("invalid_arguments");
    }
    const value = inline ?? argv[++index];
    if (value === undefined) {
      throw new VerificationError("invalid_arguments");
    }
    parsed[name] = value;
  }
  if (!OPTION_NAMES.every((name) => name in parsed)) {
    throw new VerificationError("invalid_arguments");
  }
  return parsed;
}

/** Command-line entry; prints one aggregate JSON line and returns the exit code. */
export function main(
  argv: readonly string[] = process.argv.slice(2),
  print: (line: string) => void = (line) => process.stdout.write(`${line}\n`),
): number {
  let report: Report;
  try {
    const args = parseArguments(argv);
    const sourceDb = canonicalPath(args["--source-db"] as string, false);
    const sourceMedia = canonicalPath(args["--source-media"] as string, true);
    const destination = canonicalPath(args["--destination"] as string, true);
    const rustMedia = join(destination, "media");
    if (
      sourceDb === join(destination, "database.sqlite3") ||
      sourceMedia === rustMedia ||
      rustMedia.startsWith(sourceMedia + sep)
    ) {
      throw new VerificationError("source_and_destination_must_be_separate");
    }
    report = compareImport(sourceDb, sourceMedia, destination);
  } catch (error) {
    report = {
      checks: {},
      counts: {},
      mismatches: [
        error instanceof VerificationError
          ? error.message
          : "verification_failed",
      ],
    };
  }
  print(sortedJson(report));
  return report.mismatches.length > 0 ? 1 : 0;
}

if (
  process.argv[1] &&
  import.meta.url === pathToFileURL(process.argv[1]).href
) {
  process.exitCode = main();
}
