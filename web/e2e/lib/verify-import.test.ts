// Tests for the read-only Rust import acceptance verifier.
import {
  mkdirSync,
  mkdtempSync,
  realpathSync,
  rmSync,
  unlinkSync,
  writeFileSync,
} from "node:fs";
import { DatabaseSync } from "node:sqlite";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterEach, beforeEach, describe, expect, it } from "vitest";

import { VerificationError, compareImport, main } from "./verify-import";

// Embedded SQL fixtures intentionally keep schema statements readable.
const SOURCE_SCHEMA = `
CREATE TABLE users(id INTEGER PRIMARY KEY,email TEXT,hashed_password TEXT,is_active INTEGER,
 is_admin INTEGER,token_version INTEGER,created_at TEXT,profile_image TEXT);
CREATE TABLE api_tokens(id INTEGER PRIMARY KEY,name TEXT,token_hash TEXT,created_at TEXT,
 last_used_at TEXT,expires_at TEXT,user_id INTEGER);
CREATE TABLE categories(id INTEGER PRIMARY KEY,name TEXT,created_at TEXT,user_id INTEGER);
CREATE TABLE yarns(id INTEGER PRIMARY KEY,name TEXT,description TEXT,brand TEXT,colorway TEXT,
 dye_lot TEXT,fiber_content TEXT,weight_category TEXT,recommended_needles TEXT,weight_grams INTEGER,
 length_meters INTEGER,notes TEXT,link TEXT,link_archive TEXT,link_archive_requested_at TEXT,
 link_archive_failed INTEGER,is_ai_enhanced INTEGER,created_at TEXT,updated_at TEXT,owner_id INTEGER);
CREATE TABLE projects(id INTEGER PRIMARY KEY,name TEXT,category TEXT,yarn TEXT,needles TEXT,
 stitch_sample TEXT,description TEXT,notes TEXT,other_materials TEXT,tags TEXT,link TEXT,
 link_archive TEXT,link_archive_requested_at TEXT,link_archive_failed INTEGER,is_ai_enhanced INTEGER,
 created_at TEXT,updated_at TEXT,owner_id INTEGER);
CREATE TABLE steps(id INTEGER PRIMARY KEY,title TEXT,description TEXT,step_number INTEGER,
 created_at TEXT,project_id INTEGER);
CREATE TABLE project_yarns(project_id INTEGER,yarn_id INTEGER);
CREATE TABLE user_favorites(user_id INTEGER,project_id INTEGER);
CREATE TABLE user_favorite_yarns(user_id INTEGER,yarn_id INTEGER);
CREATE TABLE images(id INTEGER PRIMARY KEY,filename TEXT,original_filename TEXT,image_type TEXT,
 alt_text TEXT,is_title_image INTEGER,is_stitch_sample INTEGER,width INTEGER,height INTEGER,
 created_at TEXT,project_id INTEGER,step_id INTEGER);
CREATE TABLE attachments(id INTEGER PRIMARY KEY,filename TEXT,original_filename TEXT,content_type TEXT,
 size_bytes INTEGER,created_at TEXT,project_id INTEGER);
CREATE TABLE yarn_images(id INTEGER PRIMARY KEY,filename TEXT,original_filename TEXT,alt_text TEXT,
 is_primary INTEGER,created_at TEXT,yarn_id INTEGER);
CREATE TABLE audit_logs(id INTEGER PRIMARY KEY,actor_user_id INTEGER,entity_type TEXT,
 entity_id INTEGER,action TEXT,details TEXT,created_at TEXT);
INSERT INTO users VALUES (1,'Owner@Example.test','password-hash-fixture',1,0,3,
 '2026-01-01 00:00:00.123456','avatar.png');
INSERT INTO yarns VALUES (11,'Yarn',NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,
 'https://example.test/yarn',NULL,'2026-01-02 00:00:00.000001',0,0,
 '2026-01-02 00:00:00.000002','2026-01-02 00:00:00.000003',1);
INSERT INTO api_tokens VALUES (2,'token','token-hash-fixture','2026-01-02 00:00:00.987654',
 '2026-01-03 00:00:00.987654','2027-01-02 00:00:00.000000',1);
INSERT INTO projects VALUES (10,'Project',NULL,'old yarn text',NULL,NULL,NULL,NULL,NULL,'[]',NULL,
 NULL,NULL,0,0,'2026-01-01 00:00:00.000000','2026-01-01 00:00:00.000000',1);
INSERT INTO images VALUES (1,'source.png','original.png','diagram','sample',0,NULL,2,2,
 '2026-01-04 00:00:00.000001',10,NULL);
INSERT INTO attachments VALUES (1,'attachment.pdf','original.pdf','application/pdf',12,
 '2026-01-04 00:00:00.000003',10);
INSERT INTO yarn_images VALUES (1,'photo.png','photo-original.png','yarn photo',1,
 '2026-01-04 00:00:00.000004',11);
INSERT INTO audit_logs VALUES (20,1,'project',10,'created','details-fixture',
 '2026-01-05 00:00:00.000002');
INSERT INTO audit_logs VALUES (21,404,'yarn',11,'updated','unknown-actor-fixture',
 '2026-01-05 00:00:00.000003');
`;

const TARGET_SCHEMA = `
CREATE TABLE users(id INTEGER PRIMARY KEY,email TEXT,legacy_email TEXT,password_hash TEXT,
 active INTEGER,is_admin INTEGER,legacy_token_version INTEGER,created_at TEXT,profile_image TEXT);
CREATE TABLE api_tokens(id INTEGER PRIMARY KEY,user_id INTEGER,name TEXT,token_hash TEXT,
 created_at INTEGER,last_used_at INTEGER,expires_at INTEGER);
CREATE TABLE categories(id INTEGER PRIMARY KEY,name TEXT,created_at TEXT,user_id INTEGER);
CREATE TABLE yarns(id INTEGER PRIMARY KEY,name TEXT,description TEXT,brand TEXT,colorway TEXT,
 dye_lot TEXT,fiber_content TEXT,weight_category TEXT,recommended_needles TEXT,weight_grams INTEGER,
 length_meters INTEGER,notes TEXT,link TEXT,link_archive TEXT,is_ai_enhanced INTEGER,created_at TEXT,
 updated_at TEXT,owner_id INTEGER,archive_requested_at TEXT,archive_failed INTEGER);
CREATE TABLE projects(id INTEGER PRIMARY KEY,name TEXT,category_id INTEGER,needles TEXT,
 stitch_sample TEXT,description TEXT,notes TEXT,other_materials TEXT,tags TEXT,link TEXT,
 link_archive TEXT,is_ai_enhanced INTEGER,created_at TEXT,updated_at TEXT,legacy_yarn_text TEXT,
 archive_requested_at TEXT,archive_failed INTEGER,owner_id INTEGER);
CREATE TABLE project_steps(id INTEGER PRIMARY KEY,title TEXT,description TEXT,step_number INTEGER,
 created_at TEXT,project_id INTEGER);
CREATE TABLE project_yarns(project_id INTEGER,yarn_id INTEGER);
CREATE TABLE user_favorites(user_id INTEGER,project_id INTEGER);
CREATE TABLE user_favorite_yarns(user_id INTEGER,yarn_id INTEGER);
CREATE TABLE media_files(id INTEGER PRIMARY KEY,owner_id INTEGER,project_id INTEGER,yarn_id INTEGER,
 step_id INTEGER,kind TEXT,public_id INTEGER,filename TEXT,original_filename TEXT,content_type TEXT,
 size_bytes INTEGER,width INTEGER,height INTEGER,alt_text TEXT,is_primary INTEGER,
 created_at TEXT,legacy_image_type TEXT,is_stitch_sample INTEGER,legacy_is_title_image INTEGER,
 legacy_source TEXT,legacy_id INTEGER);
CREATE TABLE media_url_aliases(subdir TEXT,parent_id INTEGER,legacy_filename TEXT,
 legacy_thumbnail TEXT,media_id INTEGER);
CREATE TABLE user_avatars(user_id INTEGER PRIMARY KEY,filename TEXT,original_filename TEXT);
CREATE TABLE audit_events(id INTEGER PRIMARY KEY,actor_user_id INTEGER,legacy_actor_user_id INTEGER,
 entity_type TEXT,entity_id INTEGER,action TEXT,details TEXT,created_at TEXT);
INSERT INTO users VALUES (1,'owner@example.test','Owner@Example.test','password-hash-fixture',
 1,0,3,'2026-01-01T00:00:00.123456Z','avatar.png');
INSERT INTO yarns VALUES (11,'Yarn',NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,
 'https://example.test/yarn',NULL,0,'2026-01-02T00:00:00.000002Z',
 '2026-01-02T00:00:00.000003Z',1,'2026-01-02T00:00:00.000001Z',0);
INSERT INTO api_tokens VALUES (2,1,'token','token-hash-fixture',1767312000,1767398400,1798848000);
INSERT INTO projects VALUES (10,'Project',NULL,NULL,NULL,NULL,NULL,NULL,'[]',NULL,NULL,0,
 '2026-01-01T00:00:00.000000Z','2026-01-01T00:00:00.000000Z','old yarn text',NULL,0,1);
INSERT INTO media_files VALUES (1,1,10,NULL,NULL,'title',1,'image-new.png','original.png',
 'image/png',8,2,2,'sample',0,'2026-01-04T00:00:00.000001Z','diagram',NULL,0,'images',1);
INSERT INTO media_files VALUES (2,1,10,NULL,NULL,'attachment',1,'attachment-new.pdf',
 'original.pdf','application/pdf',12,NULL,NULL,'original.pdf',0,
 '2026-01-04T00:00:00.000003Z',NULL,NULL,NULL,'attachments',1);
INSERT INTO media_files VALUES (3,1,NULL,11,NULL,'photo',1,'photo-new.png',
 'photo-original.png','image/png',11,2,2,'yarn photo',1,
 '2026-01-04T00:00:00.000004Z',NULL,NULL,NULL,'yarn_images',1);
INSERT INTO media_url_aliases VALUES ('projects',10,'source.png',NULL,1);
INSERT INTO media_url_aliases VALUES ('projects',10,'attachment.pdf',NULL,2);
INSERT INTO media_url_aliases VALUES ('yarns',11,'photo.png',NULL,3);
INSERT INTO user_avatars VALUES (1,'avatar-new.png','avatar.png');
INSERT INTO audit_events VALUES (20,1,1,'project',10,'created','details-fixture',
 '2026-01-05T00:00:00.000002Z');
INSERT INTO audit_events VALUES (21,NULL,404,'yarn',11,'updated','unknown-actor-fixture',
 '2026-01-05T00:00:00.000003Z');
`;

let tmp: string;

beforeEach(() => {
  tmp = realpathSync(mkdtempSync(join(tmpdir(), "verify-import-")));
});

afterEach(() => {
  rmSync(tmp, { recursive: true, force: true });
});

function exec(path: string, sql: string): void {
  const connection = new DatabaseSync(path);
  try {
    connection.exec(sql);
  } finally {
    connection.close();
  }
}

function createFixture(): [string, string, string] {
  const sourceDb = join(tmp, "source.sqlite3");
  const sourceMedia = join(tmp, "source-media");
  const destination = join(tmp, "rust-import");
  for (const directory of [
    join(sourceMedia, "projects", "10"),
    join(sourceMedia, "yarns", "11"),
    join(sourceMedia, "users", "1"),
    join(destination, "media", "projects", "10"),
    join(destination, "media", "yarns", "11"),
    join(destination, "media", "users", "1"),
  ]) {
    mkdirSync(directory, { recursive: true });
  }
  const files: [string, string][] = [
    [join(sourceMedia, "projects", "10", "source.png"), "image-bytes"],
    [join(sourceMedia, "projects", "10", "attachment.pdf"), "pdf-bytes-12"],
    [join(sourceMedia, "yarns", "11", "photo.png"), "photo-bytes"],
    [join(sourceMedia, "users", "1", "avatar.png"), "avatar-bytes"],
    [
      join(destination, "media", "projects", "10", "image-new.png"),
      "image-bytes",
    ],
    [
      join(destination, "media", "projects", "10", "attachment-new.pdf"),
      "pdf-bytes-12",
    ],
    [join(destination, "media", "yarns", "11", "photo-new.png"), "photo-bytes"],
    [
      join(destination, "media", "users", "1", "avatar-new.png"),
      "avatar-bytes",
    ],
  ];
  for (const [path, content] of files) {
    writeFileSync(path, content);
  }
  exec(sourceDb, SOURCE_SCHEMA);
  exec(join(destination, "database.sqlite3"), TARGET_SCHEMA);
  return [sourceDb, sourceMedia, destination];
}

function breakMediaOwner(destination: string): void {
  exec(
    join(destination, "database.sqlite3"),
    "UPDATE media_files SET owner_id = 99 WHERE legacy_source='images'",
  );
}

// Each case builds two synthetic SQLite databases; under a loaded build host that
// takes longer than Vitest's 5 s default.
describe("compareImport", { timeout: 30_000 }, () => {
  it("verifies a synthetic import without disclosing row values", () => {
    const report = compareImport(...createFixture());

    expect(report.mismatches).toEqual([]);
    expect(Object.values(report.checks).every(Boolean)).toBe(true);
    const rendered = JSON.stringify(report);
    expect(rendered).not.toContain("Owner@Example.test");
    expect(rendered).not.toContain("password-hash-fixture");
    expect(rendered).not.toContain("token-hash-fixture");
    expect(rendered).not.toContain("original.png");
  });

  it("detects a media owner mismatch", () => {
    const [sourceDb, sourceMedia, destination] = createFixture();
    breakMediaOwner(destination);

    const report = compareImport(sourceDb, sourceMedia, destination);

    expect(report.mismatches).toContain("media_records_and_original_bytes");
  });

  it("detects missing destination original media", () => {
    const [sourceDb, sourceMedia, destination] = createFixture();
    unlinkSync(join(destination, "media", "projects", "10", "image-new.png"));

    let report;
    try {
      report = compareImport(sourceDb, sourceMedia, destination);
    } catch (error) {
      expect((error as Error).message).toBe("media_read_failed");
      return;
    }
    expect(report.mismatches).toContain("media_records_and_original_bytes");
  });

  it("rejects a WAL sidecar without reading the source", () => {
    const [sourceDb, sourceMedia, destination] = createFixture();
    writeFileSync(join(tmp, "source.sqlite3-wal"), "sidecar");

    expect(() => compareImport(sourceDb, sourceMedia, destination)).toThrow(
      new VerificationError("sqlite_sidecar_present"),
    );
  });
});

describe("main", () => {
  it("emits only aggregate JSON and a non-zero status on mismatch", () => {
    const [sourceDb, sourceMedia, destination] = createFixture();
    breakMediaOwner(destination);
    const lines: string[] = [];

    const result = main(
      [
        "--source-db",
        sourceDb,
        "--source-media",
        sourceMedia,
        "--destination",
        destination,
      ],
      (line) => lines.push(line),
    );
    const output = JSON.parse(lines.join("\n")) as { mismatches: string[] };

    expect(result).toBe(1);
    expect(output.mismatches).toContain("media_records_and_original_bytes");
    const rendered = JSON.stringify(output);
    expect(rendered).not.toContain("Owner@Example.test");
    expect(rendered).not.toContain("password-hash-fixture");
    expect(rendered).not.toContain("token-hash-fixture");
    expect(rendered).not.toContain(sourceDb);
  });

  it("reports invalid arguments without echoing them", () => {
    const lines: string[] = [];
    expect(main(["--bogus"], (line) => lines.push(line))).toBe(1);
    expect(lines.join("")).toContain("invalid_arguments");
  });
});
