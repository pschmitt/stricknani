//! Restoring a data export (`POST /api/v2/account/import`): round trips through the real
//! export endpoint, both containers, all-or-nothing behaviour and the archive safety checks.

use std::{
    collections::BTreeSet,
    io::{Cursor, Write},
    path::{Path, PathBuf},
};

use axum::{
    body::{Body, Bytes},
    http::{Request, StatusCode},
};
use http_body_util::BodyExt;
use serde_json::{Value, json};
use sha2::{Digest, Sha256};
use sqlx::SqlitePool;
use stricknani_server::{api_v2, db};
use tower::ServiceExt;

const OLD: &str = "2026-01-01T00:00:00.000000Z";

struct Fx {
    root: tempfile::TempDir,
    app: axum::Router,
    pool: SqlitePool,
    anika: i64,
    ben: i64,
    cara: i64,
    dora: i64,
}

fn pat(name: &str) -> String {
    format!("restore-{name}-api-pat")
}

async fn seed_user(pool: &SqlitePool, name: &str) -> i64 {
    let id = sqlx::query(
        "INSERT INTO users (email, password_hash, is_admin, active) VALUES (?, 'test-only-hash', 0, 1)",
    )
    .bind(format!("{name}@example.test"))
    .execute(pool)
    .await
    .unwrap()
    .last_insert_rowid();
    sqlx::query(
        "INSERT INTO api_tokens (user_id, name, token_hash, created_at, expires_at, purpose) \
         VALUES (?, 'restore fixture', ?, unixepoch(), unixepoch() + 86400, 'api')",
    )
    .bind(id)
    .bind(hex::encode(Sha256::digest(pat(name).as_bytes())))
    .execute(pool)
    .await
    .unwrap();
    id
}

async fn fixture_with_limit(limit: Option<u64>) -> Fx {
    let pool = db::connect("sqlite::memory:").await.unwrap();
    let root = tempfile::tempdir().unwrap();
    let state = db::AppState::new(pool.clone())
        .with_secure_cookies(true)
        .with_media_root(root.path().to_path_buf());
    let anika = seed_user(&pool, "anika").await;
    let ben = seed_user(&pool, "ben").await;
    let cara = seed_user(&pool, "cara").await;
    let dora = seed_user(&pool, "dora").await;
    let app = match limit {
        Some(limit) => api_v2::router_with_restore_limit(state, false, limit),
        None => api_v2::router(state, false),
    };
    Fx {
        root,
        app,
        pool,
        anika,
        ben,
        cara,
        dora,
    }
}

async fn fixture() -> Fx {
    fixture_with_limit(None).await
}

// ---------------------------------------------------------------------------
// Source data

fn png(seed: u8) -> Vec<u8> {
    let image = image::RgbaImage::from_fn(8, 8, |x, y| {
        image::Rgba([
            seed,
            (x * 30) as u8,
            (y * 30) as u8,
            255 - seed.wrapping_mul(3),
        ])
    });
    let mut output = Cursor::new(Vec::new());
    image::DynamicImage::ImageRgba8(image)
        .write_to(&mut output, image::ImageFormat::Png)
        .unwrap();
    output.into_inner()
}

fn pdf() -> Vec<u8> {
    b"%PDF-1.4\n1 0 obj\n<<>>\nendobj\ntrailer\n<<>>\n%%EOF\n".to_vec()
}

fn sha(bytes: &[u8]) -> String {
    hex::encode(Sha256::digest(bytes))
}

async fn insert_id(pool: &SqlitePool, sql: &str, binds: &[&str]) -> i64 {
    let mut query = sqlx::query_scalar(sqlx::AssertSqlSafe(sql));
    for bind in binds {
        query = query.bind(*bind);
    }
    query.fetch_one(pool).await.unwrap()
}

struct Source {
    sha_set: BTreeSet<String>,
    shared_project: i64,
    own_project: i64,
}

struct Seeder<'a> {
    f: &'a Fx,
    public_id: i64,
    sha_set: BTreeSet<String>,
}

impl Seeder<'_> {
    #[allow(clippy::too_many_arguments)]
    async fn media(
        &mut self,
        owner: i64,
        project: Option<i64>,
        yarn: Option<i64>,
        step: Option<i64>,
        kind: &str,
        name: &str,
        bytes: &[u8],
        position: i64,
        primary: bool,
        cover: bool,
    ) {
        self.public_id += 1;
        let content_type = if kind == "attachment" {
            "application/pdf"
        } else {
            "image/png"
        };
        sqlx::query(
            "INSERT INTO media_files (owner_id,project_id,yarn_id,step_id,kind,public_id,filename, \
             original_filename,content_type,size_bytes,alt_text,is_primary,position,is_project_cover) \
             VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        )
        .bind(owner)
        .bind(project)
        .bind(yarn)
        .bind(step)
        .bind(kind)
        .bind(self.public_id)
        .bind(name)
        .bind(format!("orig-{name}"))
        .bind(content_type)
        .bind(bytes.len() as i64)
        .bind(format!("alt {name}"))
        .bind(primary)
        .bind(position)
        .bind(cover)
        .execute(&self.f.pool)
        .await
        .unwrap();
        let (subdir, parent) = match project {
            Some(project) => ("projects", project),
            None => ("yarns", yarn.unwrap()),
        };
        let dir = self.f.root.path().join(subdir).join(parent.to_string());
        std::fs::create_dir_all(&dir).unwrap();
        std::fs::write(dir.join(name), bytes).unwrap();
        self.sha_set.insert(sha(bytes));
    }
}

/// Data for Anika: a full project, a linked yarn, a favourite, and a project shared by Ben.
async fn seed_source(f: &Fx) -> Source {
    let pool = &f.pool;
    sqlx::query("INSERT INTO categories (user_id, name) VALUES (?, 'Socks')")
        .bind(f.anika)
        .execute(pool)
        .await
        .unwrap();
    let category: i64 = sqlx::query_scalar("SELECT id FROM categories WHERE user_id = ?")
        .bind(f.anika)
        .fetch_one(pool)
        .await
        .unwrap();
    let own_project: i64 = sqlx::query_scalar(
        "INSERT INTO projects (owner_id, category_id, name, needles, stitch_sample, description, \
         notes, other_materials, tags, link, link_archive, is_ai_enhanced, created_at, updated_at) \
         VALUES (?, ?, 'Wool socks', '3 mm', '20 stitches', 'cosy', 'my notes', 'buttons', \
         '[\"socks\",\"wool\"]', 'https://example.test/pattern', 'https://archive.example.test/p', 1, ?, ?) \
         RETURNING id",
    )
    .bind(f.anika)
    .bind(category)
    .bind(OLD)
    .bind(OLD)
    .fetch_one(pool)
    .await
    .unwrap();
    let step1: i64 = insert_id(
        pool,
        &format!(
            "INSERT INTO project_steps (project_id,title,description,step_number) \
             VALUES ({own_project}, 'cast on', 'loosely', 1) RETURNING id"
        ),
        &[],
    )
    .await;
    insert_id(
        pool,
        &format!(
            "INSERT INTO project_steps (project_id,title,description,step_number) \
             VALUES ({own_project}, 'heel', NULL, 2) RETURNING id"
        ),
        &[],
    )
    .await;
    sqlx::query("INSERT INTO user_favorites (user_id, project_id) VALUES (?, ?)")
        .bind(f.anika)
        .bind(own_project)
        .execute(pool)
        .await
        .unwrap();
    let own_yarn: i64 = sqlx::query_scalar(
        "INSERT INTO yarns (owner_id,name,brand,colorway,fiber_content,weight_grams,length_meters, \
         notes,link,is_ai_enhanced,created_at,updated_at) \
         VALUES (?, 'Merino', 'Acme', 'blue', '100% wool', 100, 400, 'soft', 'https://example.test/y', 0, ?, ?) \
         RETURNING id",
    )
    .bind(f.anika)
    .bind(OLD)
    .bind(OLD)
    .fetch_one(pool)
    .await
    .unwrap();
    sqlx::query("INSERT INTO project_yarns (project_id, yarn_id) VALUES (?, ?)")
        .bind(own_project)
        .bind(own_yarn)
        .execute(pool)
        .await
        .unwrap();
    sqlx::query("INSERT INTO user_favorite_yarns (user_id, yarn_id) VALUES (?, ?)")
        .bind(f.anika)
        .bind(own_yarn)
        .execute(pool)
        .await
        .unwrap();

    // Ben's project shared with Anika, with a derived yarn.
    let shared_project: i64 = sqlx::query_scalar(
        "INSERT INTO projects (owner_id,name,tags,created_at,updated_at) \
         VALUES (?, 'Ben scarf', '[]', ?, ?) RETURNING id",
    )
    .bind(f.ben)
    .bind(OLD)
    .bind(OLD)
    .fetch_one(pool)
    .await
    .unwrap();
    let ben_yarn: i64 = sqlx::query_scalar(
        "INSERT INTO yarns (owner_id,name,created_at,updated_at) VALUES (?, 'Ben yarn', ?, ?) RETURNING id",
    )
    .bind(f.ben)
    .bind(OLD)
    .bind(OLD)
    .fetch_one(pool)
    .await
    .unwrap();
    sqlx::query("INSERT INTO project_yarns (project_id, yarn_id) VALUES (?, ?)")
        .bind(shared_project)
        .bind(ben_yarn)
        .execute(pool)
        .await
        .unwrap();
    sqlx::query(
        "INSERT INTO resource_shares (kind, resource_id, user_id, role, created_by, created_at, updated_at) \
         VALUES ('project', ?, ?, 'viewer', ?, ?, ?)",
    )
    .bind(shared_project)
    .bind(f.anika)
    .bind(f.ben)
    .bind(OLD)
    .bind(OLD)
    .execute(pool)
    .await
    .unwrap();

    let mut seeder = Seeder {
        f,
        public_id: 100,
        sha_set: BTreeSet::new(),
    };
    seeder
        .media(
            f.anika,
            Some(own_project),
            None,
            None,
            "title",
            "t1.png",
            &png(1),
            0,
            true,
            true,
        )
        .await;
    seeder
        .media(
            f.anika,
            Some(own_project),
            None,
            None,
            "title",
            "t2.png",
            &png(2),
            1,
            false,
            false,
        )
        .await;
    seeder
        .media(
            f.anika,
            Some(own_project),
            None,
            Some(step1),
            "step",
            "s1.png",
            &png(3),
            0,
            false,
            false,
        )
        .await;
    seeder
        .media(
            f.anika,
            Some(own_project),
            None,
            None,
            "attachment",
            "doc.pdf",
            &pdf(),
            0,
            false,
            false,
        )
        .await;
    seeder
        .media(
            f.anika,
            None,
            Some(own_yarn),
            None,
            "photo",
            "y1.png",
            &png(4),
            0,
            true,
            false,
        )
        .await;
    seeder
        .media(
            f.anika,
            None,
            Some(own_yarn),
            None,
            "photo",
            "y2.png",
            &png(5),
            1,
            false,
            false,
        )
        .await;
    seeder
        .media(
            f.ben,
            Some(shared_project),
            None,
            None,
            "title",
            "b1.png",
            &png(6),
            0,
            true,
            true,
        )
        .await;
    seeder
        .media(
            f.ben,
            None,
            Some(ben_yarn),
            None,
            "photo",
            "b2.png",
            &png(7),
            0,
            true,
            false,
        )
        .await;
    Source {
        sha_set: seeder.sha_set,
        shared_project,
        own_project,
    }
}

// ---------------------------------------------------------------------------
// Requests

async fn export(f: &Fx, name: &str, archive: &str) -> Vec<u8> {
    let response = f
        .app
        .clone()
        .oneshot(
            Request::get(format!("/account/export?archive={archive}"))
                .header("authorization", format!("Bearer {}", pat(name)))
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    response
        .into_body()
        .collect()
        .await
        .unwrap()
        .to_bytes()
        .to_vec()
}

async fn post_import(f: &Fx, name: Option<&str>, body: Vec<u8>) -> (StatusCode, Value) {
    let mut request = Request::post("/account/import");
    if let Some(name) = name {
        request = request.header("authorization", format!("Bearer {}", pat(name)));
    }
    let response = f
        .app
        .clone()
        .oneshot(request.body(Body::from(body)).unwrap())
        .await
        .unwrap();
    let status = response.status();
    let bytes = response.into_body().collect().await.unwrap().to_bytes();
    (
        status,
        serde_json::from_slice(&bytes).unwrap_or(Value::Null),
    )
}

async fn restore_ok(f: &Fx, name: &str, body: Vec<u8>) -> Value {
    let (status, value) = post_import(f, Some(name), body).await;
    assert_eq!(status, StatusCode::OK, "{value}");
    value["data"].clone()
}

async fn restore_err(f: &Fx, name: &str, body: Vec<u8>, status: StatusCode, code: &str) -> Value {
    let (got, value) = post_import(f, Some(name), body).await;
    assert_eq!(got, status, "{value}");
    assert_eq!(value["error"]["code"], code, "{value}");
    value
}

// ---------------------------------------------------------------------------
// Crafted archives

const TOP: &str = "stricknani-export-test";

fn manifest(version: Value) -> Vec<u8> {
    serde_json::to_vec(&json!({
        "format": "stricknani-export",
        "format_version": version,
        "created_at": "2026-11-07T18:30:00Z",
    }))
    .unwrap()
}

fn raw_tar_entry(
    builder: &mut tar::Builder<flate2::write::GzEncoder<Vec<u8>>>,
    name: &str,
    kind: tar::EntryType,
    data: &[u8],
) {
    let mut header = tar::Header::new_ustar();
    let bytes = name.as_bytes();
    header.as_old_mut().name[..bytes.len()].copy_from_slice(bytes);
    header.set_size(data.len() as u64);
    header.set_mode(0o644);
    header.set_entry_type(kind);
    if kind == tar::EntryType::Symlink {
        header.set_size(0);
        header.set_link_name("/etc/passwd").unwrap();
    }
    header.set_cksum();
    builder
        .append(
            &header,
            if kind == tar::EntryType::Symlink {
                &b""[..]
            } else {
                data
            },
        )
        .unwrap();
}

fn tar_gz(entries: &[(String, Vec<u8>)]) -> Vec<u8> {
    let mut builder = tar::Builder::new(flate2::write::GzEncoder::new(
        Vec::new(),
        flate2::Compression::fast(),
    ));
    for (name, data) in entries {
        raw_tar_entry(&mut builder, name, tar::EntryType::Regular, data);
    }
    builder.into_inner().unwrap().finish().unwrap()
}

fn zip_archive(entries: &[(String, Vec<u8>)]) -> Vec<u8> {
    let mut zip = zip::ZipWriter::new(Cursor::new(Vec::new()));
    for (name, data) in entries {
        zip.start_file(
            name.as_str(),
            zip::write::SimpleFileOptions::default()
                .compression_method(zip::CompressionMethod::Stored),
        )
        .unwrap();
        zip.write_all(data).unwrap();
    }
    zip.finish().unwrap().into_inner()
}

fn entry(name: &str, data: Vec<u8>) -> (String, Vec<u8>) {
    (format!("{TOP}/{name}"), data)
}

/// A minimal valid archive with the given projects and extra entries.
fn archive(projects: Value, extra: Vec<(String, Vec<u8>)>) -> Vec<u8> {
    let mut entries = vec![
        entry("manifest.json", manifest(json!(1))),
        entry("projects.json", serde_json::to_vec(&projects).unwrap()),
        entry("yarns.json", b"[]".to_vec()),
        entry("categories.json", b"[]".to_vec()),
    ];
    entries.extend(extra);
    tar_gz(&entries)
}

fn project_with_image(file: Option<&str>, sha256: Option<String>) -> Value {
    json!([{
        "id": 1, "name": "Crafted", "tags": [], "steps": [], "yarn_ids": [999],
        "images": [{
            "file": file, "original_filename": "p.png", "content_type": "image/png",
            "position": 0, "is_primary": true, "sha256": sha256,
            "missing": file.is_none(),
        }],
        "attachments": [],
    }])
}

// ---------------------------------------------------------------------------
// Inspection

async fn count(pool: &SqlitePool, sql: &str, user: i64) -> i64 {
    sqlx::query_scalar(sqlx::AssertSqlSafe(sql))
        .bind(user)
        .fetch_one(pool)
        .await
        .unwrap()
}

fn files_under(path: &Path) -> Vec<PathBuf> {
    let mut found = Vec::new();
    let Ok(entries) = std::fs::read_dir(path) else {
        return found;
    };
    for entry in entries.flatten() {
        let path = entry.path();
        if path.is_dir() {
            found.extend(files_under(&path));
        } else {
            found.push(path);
        }
    }
    found
}

async fn assert_nothing_left(f: &Fx, user: i64, projects_before: i64) {
    assert_eq!(
        count(
            &f.pool,
            "SELECT count(*) FROM projects WHERE owner_id = ?",
            user
        )
        .await,
        projects_before
    );
    assert_eq!(
        count(
            &f.pool,
            "SELECT count(*) FROM yarns WHERE owner_id = ?",
            user
        )
        .await,
        0
    );
    assert_eq!(
        count(
            &f.pool,
            "SELECT count(*) FROM media_files WHERE owner_id = ?",
            user
        )
        .await,
        0
    );
    let leftovers: Vec<_> = files_under(f.root.path());
    assert!(leftovers.is_empty(), "files left behind: {leftovers:?}");
    let entries: Vec<_> = std::fs::read_dir(f.root.path())
        .unwrap()
        .flatten()
        .filter(|entry| entry.file_name().to_string_lossy().starts_with(".restore"))
        .collect();
    assert!(entries.is_empty(), "scratch entries left behind");
}

// ---------------------------------------------------------------------------
// Round trips

async fn round_trip(archive_kind: &str) {
    let f = fixture().await;
    let source = seed_source(&f).await;
    // Cara already has a category with the same name in other case, and a project of her own.
    sqlx::query("INSERT INTO categories (user_id, name) VALUES (?, 'socks')")
        .bind(f.cara)
        .execute(&f.pool)
        .await
        .unwrap();
    let cara_category: i64 = sqlx::query_scalar("SELECT id FROM categories WHERE user_id = ?")
        .bind(f.cara)
        .fetch_one(&f.pool)
        .await
        .unwrap();
    let shares_before = count(&f.pool, "SELECT count(*) + ? FROM resource_shares", 0).await;
    let ben_media_before: Vec<(i64, String)> =
        sqlx::query_as("SELECT id, filename FROM media_files WHERE owner_id = ? ORDER BY id")
            .bind(f.ben)
            .fetch_all(&f.pool)
            .await
            .unwrap();
    let ben_updated_before: String =
        sqlx::query_scalar("SELECT updated_at FROM projects WHERE id = ?")
            .bind(source.shared_project)
            .fetch_one(&f.pool)
            .await
            .unwrap();
    let ben_project_media_before = count(
        &f.pool,
        "SELECT count(*) FROM media_files WHERE owner_id = ?",
        f.ben,
    )
    .await;

    let body = export(&f, "anika", archive_kind).await;
    let started = chrono::Utc::now();
    let summary = restore_ok(&f, "cara", body.clone()).await;
    assert_eq!(
        summary,
        json!({
            "format_version": 1, "projects": 2, "yarns": 2, "categories_created": 0,
            "media_files": 8, "media_skipped": 0,
        })
    );

    // Items and fields.
    assert_eq!(
        count(
            &f.pool,
            "SELECT count(*) FROM projects WHERE owner_id = ?",
            f.cara
        )
        .await,
        2
    );
    assert_eq!(
        count(
            &f.pool,
            "SELECT count(*) FROM yarns WHERE owner_id = ?",
            f.cara
        )
        .await,
        2
    );
    let project: (i64, Option<i64>, String, String, String, String, String, i64, String, String) =
        sqlx::query_as(
            "SELECT id, category_id, needles || '|' || stitch_sample || '|' || description, notes, \
             other_materials, tags, link || '|' || link_archive, is_ai_enhanced, created_at, updated_at \
             FROM projects WHERE owner_id = ? AND name = 'Wool socks'",
        )
        .bind(f.cara)
        .fetch_one(&f.pool)
        .await
        .unwrap();
    let new_project = project.0;
    assert_ne!(new_project, source.own_project);
    assert_eq!(project.1, Some(cara_category), "category merged by name");
    assert_eq!(project.2, "3 mm|20 stitches|cosy");
    assert_eq!(project.3, "my notes");
    assert_eq!(project.4, "buttons");
    assert_eq!(project.5, "[\"socks\",\"wool\"]");
    assert_eq!(
        project.6,
        "https://example.test/pattern|https://archive.example.test/p"
    );
    assert_eq!(project.7, 1);
    assert_eq!(project.8, OLD, "created_at is kept");
    let updated = chrono::DateTime::parse_from_rfc3339(&project.9).unwrap();
    assert!(updated >= started, "updated_at is the restore time");
    assert_eq!(
        count(
            &f.pool,
            "SELECT count(*) FROM categories WHERE user_id = ?",
            f.cara
        )
        .await,
        1
    );

    let steps: Vec<(String, Option<String>, i64)> = sqlx::query_as(
        "SELECT title, description, step_number FROM project_steps WHERE project_id = ? ORDER BY step_number",
    )
    .bind(new_project)
    .fetch_all(&f.pool)
    .await
    .unwrap();
    assert_eq!(
        steps,
        vec![
            ("cast on".into(), Some("loosely".into()), 1),
            ("heel".into(), None, 2)
        ]
    );

    // Links and favourites.
    let links: Vec<(String, String)> = sqlx::query_as(
        "SELECT p.name, y.name FROM project_yarns l JOIN projects p ON p.id = l.project_id \
         JOIN yarns y ON y.id = l.yarn_id WHERE p.owner_id = ? AND y.owner_id = ? ORDER BY p.name",
    )
    .bind(f.cara)
    .bind(f.cara)
    .fetch_all(&f.pool)
    .await
    .unwrap();
    assert_eq!(
        links,
        vec![
            ("Ben scarf".into(), "Ben yarn".into()),
            ("Wool socks".into(), "Merino".into())
        ]
    );
    let favourite_names: Vec<String> = sqlx::query_scalar(
        "SELECT p.name FROM user_favorites f JOIN projects p ON p.id = f.project_id WHERE f.user_id = ?",
    )
    .bind(f.cara)
    .fetch_all(&f.pool)
    .await
    .unwrap();
    assert_eq!(favourite_names, vec!["Wool socks".to_owned()]);
    assert_eq!(
        count(
            &f.pool,
            "SELECT count(*) FROM user_favorite_yarns WHERE user_id = ?",
            f.cara
        )
        .await,
        1
    );
    let yarn: (String, i64, i64) = sqlx::query_as(
        "SELECT brand || '|' || colorway || '|' || fiber_content, weight_grams, length_meters \
         FROM yarns WHERE owner_id = ? AND name = 'Merino'",
    )
    .bind(f.cara)
    .fetch_one(&f.pool)
    .await
    .unwrap();
    assert_eq!(yarn, ("Acme|blue|100% wool".into(), 100, 400));

    // Media: rows, flags, files, thumbnails and checksums.
    let titles: Vec<(i64, bool, bool, String)> = sqlx::query_as(
        "SELECT position, is_primary, is_project_cover, original_filename FROM media_files \
         WHERE project_id = ? AND kind = 'title' ORDER BY position",
    )
    .bind(new_project)
    .fetch_all(&f.pool)
    .await
    .unwrap();
    assert_eq!(
        titles,
        vec![
            (0, true, true, "orig-t1.png".into()),
            (1, false, false, "orig-t2.png".into())
        ]
    );
    let step_images: i64 = sqlx::query_scalar(
        "SELECT count(*) FROM media_files m JOIN project_steps s ON s.id = m.step_id \
         WHERE m.project_id = ? AND m.kind = 'step' AND s.title = 'cast on'",
    )
    .bind(new_project)
    .fetch_one(&f.pool)
    .await
    .unwrap();
    assert_eq!(step_images, 1);
    let photos: Vec<(bool, i64)> = sqlx::query_as(
        "SELECT is_primary, position FROM media_files m JOIN yarns y ON y.id = m.yarn_id \
         WHERE y.owner_id = ? AND y.name = 'Merino' ORDER BY position",
    )
    .bind(f.cara)
    .fetch_all(&f.pool)
    .await
    .unwrap();
    assert_eq!(photos, vec![(true, 0), (false, 1)]);

    let rows: Vec<(Option<i64>, Option<i64>, String, String)> = sqlx::query_as(
        "SELECT project_id, yarn_id, kind, filename FROM media_files WHERE owner_id = ?",
    )
    .bind(f.cara)
    .fetch_all(&f.pool)
    .await
    .unwrap();
    assert_eq!(rows.len(), 8);
    let mut restored_sha = BTreeSet::new();
    for (project_id, yarn_id, kind, filename) in rows {
        let (subdir, parent) = match project_id {
            Some(id) => ("projects", id),
            None => ("yarns", yarn_id.unwrap()),
        };
        let path = f
            .root
            .path()
            .join(subdir)
            .join(parent.to_string())
            .join(&filename);
        restored_sha.insert(sha(&std::fs::read(&path).expect("restored file exists")));
        if kind != "attachment" {
            let stem = filename.rsplit_once('.').unwrap().0;
            let thumb = f
                .root
                .path()
                .join("thumbnails")
                .join(subdir)
                .join(parent.to_string())
                .join(format!("thumb_{stem}.jpg"));
            assert!(thumb.is_file(), "thumbnail generated for {kind}");
        }
    }
    assert_eq!(restored_sha, source.sha_set, "originals are byte-identical");
    assert!(
        files_under(f.root.path())
            .iter()
            .all(|path| { !path.to_string_lossy().contains(".restore") })
    );

    // Nothing of Ben's changed and no share exists for Cara.
    assert_eq!(
        count(&f.pool, "SELECT count(*) + ? FROM resource_shares", 0).await,
        shares_before
    );
    assert_eq!(
        count(
            &f.pool,
            "SELECT count(*) FROM resource_shares WHERE user_id = ?",
            f.cara
        )
        .await,
        0
    );
    let ben_media_after: Vec<(i64, String)> =
        sqlx::query_as("SELECT id, filename FROM media_files WHERE owner_id = ? ORDER BY id")
            .bind(f.ben)
            .fetch_all(&f.pool)
            .await
            .unwrap();
    assert_eq!(ben_media_after, ben_media_before);
    assert_eq!(ben_media_after.len() as i64, ben_project_media_before);
    let ben_updated_after: String =
        sqlx::query_scalar("SELECT updated_at FROM projects WHERE id = ?")
            .bind(source.shared_project)
            .fetch_one(&f.pool)
            .await
            .unwrap();
    assert_eq!(ben_updated_after, ben_updated_before);
    assert_eq!(
        count(
            &f.pool,
            "SELECT count(*) FROM projects WHERE owner_id = ?",
            f.ben
        )
        .await,
        1
    );

    // Sync and history see the new items.
    let sync = |path: &'static str| {
        let app = f.app.clone();
        async move {
            let response = app
                .oneshot(
                    Request::get(path)
                        .header("authorization", format!("Bearer {}", pat("cara")))
                        .body(Body::empty())
                        .unwrap(),
                )
                .await
                .unwrap();
            assert_eq!(response.status(), StatusCode::OK);
            let bytes = response.into_body().collect().await.unwrap().to_bytes();
            serde_json::from_slice::<Value>(&bytes).unwrap()
        }
    };
    let projects = sync("/sync/projects?since=2020-01-01T00%3A00%3A00Z").await;
    assert_eq!(projects["data"]["updated"].as_array().unwrap().len(), 2);
    let yarns = sync("/sync/yarns?since=2020-01-01T00%3A00%3A00Z").await;
    assert_eq!(yarns["data"]["updated"].as_array().unwrap().len(), 2);
    let categories = sync("/sync/categories?since=2020-01-01T00%3A00%3A00Z").await;
    assert_eq!(categories["data"]["updated"].as_array().unwrap().len(), 1);
    let history = f
        .app
        .clone()
        .oneshot(
            Request::get(format!("/projects/{new_project}/history"))
                .header("authorization", format!("Bearer {}", pat("cara")))
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(history.status(), StatusCode::OK);
    let history: Value =
        serde_json::from_slice(&history.into_body().collect().await.unwrap().to_bytes()).unwrap();
    assert!(
        history["data"]
            .as_array()
            .unwrap()
            .iter()
            .any(|event| event["action"] == "created"),
        "{history}"
    );
    let created_events: i64 = sqlx::query_scalar(
        "SELECT count(*) FROM audit_events WHERE actor_user_id = ? AND action = 'created'",
    )
    .bind(f.cara)
    .fetch_one(&f.pool)
    .await
    .unwrap();
    assert_eq!(created_events, 4, "one created event per restored item");

    // Restoring the same file again gives every item twice.
    let again = restore_ok(&f, "cara", body).await;
    assert_eq!(again["projects"], 2);
    assert_eq!(again["categories_created"], 0);
    assert_eq!(
        count(
            &f.pool,
            "SELECT count(*) FROM projects WHERE owner_id = ?",
            f.cara
        )
        .await,
        4
    );
    assert_eq!(
        count(
            &f.pool,
            "SELECT count(*) FROM media_files WHERE owner_id = ?",
            f.cara
        )
        .await,
        16
    );
}

#[tokio::test]
async fn tar_gz_export_restores_into_another_account() {
    round_trip("tar.gz").await;
}

#[tokio::test]
async fn zip_export_restores_into_another_account() {
    round_trip("zip").await;
}

#[tokio::test]
async fn restore_into_the_exporting_account_adds_copies_and_creates_missing_categories() {
    let f = fixture().await;
    let source = seed_source(&f).await;
    let body = export(&f, "anika", "tar.gz").await;
    let summary = restore_ok(&f, "anika", body).await;
    assert_eq!(summary["categories_created"], 0);
    // Anika owned one project and now owns that, plus copies of both exported projects.
    assert_eq!(
        count(
            &f.pool,
            "SELECT count(*) FROM projects WHERE owner_id = ?",
            f.anika
        )
        .await,
        3
    );
    let original_tags: String = sqlx::query_scalar("SELECT tags FROM projects WHERE id = ?")
        .bind(source.own_project)
        .fetch_one(&f.pool)
        .await
        .unwrap();
    assert_eq!(original_tags, "[\"socks\",\"wool\"]");
    // A fresh account has no category: restoring creates it.
    let body = export(&f, "anika", "zip").await;
    let summary = restore_ok(&f, "dora", body).await;
    assert_eq!(summary["categories_created"], 1);
    assert_eq!(
        count(
            &f.pool,
            "SELECT count(*) FROM categories WHERE user_id = ?",
            f.dora
        )
        .await,
        1
    );
}

// ---------------------------------------------------------------------------
// Authentication and versions

#[tokio::test]
async fn restore_requires_authentication() {
    let f = fixture().await;
    let (status, _) = post_import(&f, None, archive(json!([]), vec![])).await;
    // Without credentials the request is a cookie mutation: the Origin/CSRF gate answers first.
    assert!(
        matches!(status, StatusCode::UNAUTHORIZED | StatusCode::FORBIDDEN),
        "{status}"
    );
    let response = f
        .app
        .clone()
        .oneshot(
            Request::post("/account/import")
                .header("authorization", "Bearer not-a-token")
                .body(Body::from(archive(json!([]), vec![])))
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::UNAUTHORIZED);
}

#[tokio::test]
async fn format_versions_are_checked() {
    let f = fixture().await;
    let with = |manifest: Vec<u8>| {
        tar_gz(&[
            entry("manifest.json", manifest),
            entry("projects.json", b"[]".to_vec()),
        ])
    };
    let value = restore_err(
        &f,
        "cara",
        with(manifest(json!(2))),
        StatusCode::UNPROCESSABLE_ENTITY,
        "unsupported_format_version",
    )
    .await;
    assert!(
        value["error"]["message"]
            .as_str()
            .unwrap()
            .contains("update")
    );
    for bad in [json!(0), json!(-3), json!("1"), Value::Null, json!(1.5)] {
        restore_err(
            &f,
            "cara",
            with(manifest(bad)),
            StatusCode::BAD_REQUEST,
            "invalid_archive",
        )
        .await;
    }
    restore_err(
        &f,
        "cara",
        with(serde_json::to_vec(&json!({"format_version": 1})).unwrap()),
        StatusCode::BAD_REQUEST,
        "invalid_archive",
    )
    .await;
    restore_err(
        &f,
        "cara",
        with(serde_json::to_vec(&json!({"format": "other", "format_version": 1})).unwrap()),
        StatusCode::BAD_REQUEST,
        "invalid_archive",
    )
    .await;
    // The current version is accepted, even when the lists are absent.
    let summary = restore_ok(&f, "cara", with(manifest(json!(1)))).await;
    assert_eq!(summary["projects"], 0);
}

// ---------------------------------------------------------------------------
// Invalid archives

#[tokio::test]
async fn malformed_archives_are_rejected() {
    let f = fixture().await;
    for body in [
        Vec::new(),
        b"this is not an archive at all".to_vec(),
        vec![0x1f, 0x8b, 0, 0, 0, 0],
        b"PK\x03\x04garbage".to_vec(),
    ] {
        restore_err(&f, "cara", body, StatusCode::BAD_REQUEST, "invalid_archive").await;
    }
    // No manifest.
    restore_err(
        &f,
        "cara",
        tar_gz(&[entry("projects.json", b"[]".to_vec())]),
        StatusCode::BAD_REQUEST,
        "invalid_archive",
    )
    .await;
    restore_err(
        &f,
        "cara",
        zip_archive(&[entry("projects.json", b"[]".to_vec())]),
        StatusCode::BAD_REQUEST,
        "invalid_archive",
    )
    .await;
    // Malformed JSON, and a project without a name.
    restore_err(
        &f,
        "cara",
        tar_gz(&[
            entry("manifest.json", manifest(json!(1))),
            entry("projects.json", b"{not json".to_vec()),
        ]),
        StatusCode::BAD_REQUEST,
        "invalid_archive",
    )
    .await;
    restore_err(
        &f,
        "cara",
        archive(json!([{"id": 1, "name": "  "}]), vec![]),
        StatusCode::BAD_REQUEST,
        "invalid_archive",
    )
    .await;
    assert_nothing_left(&f, f.cara, 0).await;
}

#[tokio::test]
async fn unsafe_entries_are_refused_even_when_unused() {
    let f = fixture().await;
    let base = || {
        vec![
            entry("manifest.json", manifest(json!(1))),
            entry("projects.json", b"[]".to_vec()),
        ]
    };
    for name in [
        format!("{TOP}/../escape.txt"),
        "../escape.txt".to_owned(),
        "/etc/escape.txt".to_owned(),
        format!("{TOP}/media/../../escape.txt"),
    ] {
        let mut entries = base();
        entries.push((name.clone(), b"x".to_vec()));
        restore_err(
            &f,
            "cara",
            tar_gz(&entries),
            StatusCode::BAD_REQUEST,
            "invalid_archive",
        )
        .await;
        restore_err(
            &f,
            "cara",
            zip_archive(&entries),
            StatusCode::BAD_REQUEST,
            "invalid_archive",
        )
        .await;
    }
    // A symlink entry.
    let mut builder = tar::Builder::new(flate2::write::GzEncoder::new(
        Vec::new(),
        flate2::Compression::fast(),
    ));
    for (name, data) in base() {
        raw_tar_entry(&mut builder, &name, tar::EntryType::Regular, &data);
    }
    raw_tar_entry(
        &mut builder,
        &format!("{TOP}/media/link"),
        tar::EntryType::Symlink,
        b"",
    );
    let body = builder.into_inner().unwrap().finish().unwrap();
    restore_err(&f, "cara", body, StatusCode::BAD_REQUEST, "invalid_archive").await;
    // A media path that leaves media/.
    for file in [
        "../manifest.json",
        "/etc/passwd",
        "projects.json",
        "media/../x",
    ] {
        restore_err(
            &f,
            "cara",
            archive(project_with_image(Some(file), None), vec![]),
            StatusCode::BAD_REQUEST,
            "invalid_archive",
        )
        .await;
    }
    assert_nothing_left(&f, f.cara, 0).await;
}

#[tokio::test]
async fn decompression_is_capped() {
    let f = fixture().await;
    // 65 MiB of zeros is a few dozen KiB compressed: a JSON entry past its cap fails fast.
    let bomb = vec![b' '; 65 * 1024 * 1024];
    let body = tar_gz(&[
        entry("manifest.json", manifest(json!(1))),
        entry("projects.json", bomb),
    ]);
    assert!(body.len() < 1024 * 1024);
    restore_err(&f, "cara", body, StatusCode::BAD_REQUEST, "invalid_archive").await;
    // A media entry over the upload limit.
    let huge = vec![0_u8; 26 * 1024 * 1024];
    let body = archive(
        project_with_image(Some("media/projects/1/big.png"), None),
        vec![entry("media/projects/1/big.png", huge)],
    );
    restore_err(&f, "cara", body, StatusCode::BAD_REQUEST, "invalid_archive").await;
    assert_nothing_left(&f, f.cara, 0).await;
}

#[tokio::test]
async fn bad_media_fails_the_whole_restore_and_leaves_nothing() {
    let f = fixture().await;
    let good = png(9);
    // One good image first, so something is staged before the failure.
    let projects = json!([
        {"id": 1, "name": "Fine", "images": [{"file": "media/projects/1/ok.png", "sha256": sha(&good)}]},
        {"id": 2, "name": "Broken", "images": [{"file": "media/projects/2/missing.png"}]},
    ]);
    let body = archive(
        projects.clone(),
        vec![entry("media/projects/1/ok.png", good.clone())],
    );
    restore_err(&f, "cara", body, StatusCode::BAD_REQUEST, "invalid_archive").await;
    assert_nothing_left(&f, f.cara, 0).await;

    // The file exists but is not an image.
    let body = archive(
        json!([{"id": 1, "name": "x", "images": [{"file": "media/projects/1/a.png"}]}]),
        vec![entry("media/projects/1/a.png", b"not an image".to_vec())],
    );
    restore_err(&f, "cara", body, StatusCode::BAD_REQUEST, "invalid_archive").await;

    // sha256 mismatch.
    let body = archive(
        project_with_image(Some("media/projects/1/ok.png"), Some(sha(b"other"))),
        vec![entry("media/projects/1/ok.png", good.clone())],
    );
    restore_err(&f, "cara", body, StatusCode::BAD_REQUEST, "invalid_archive").await;
    assert_nothing_left(&f, f.cara, 0).await;

    // Matching checksum (in upper case) works, and the dangling yarn link is dropped.
    let body = archive(
        project_with_image(
            Some("media/projects/1/ok.png"),
            Some(sha(&good).to_uppercase()),
        ),
        vec![entry("media/projects/1/ok.png", good)],
    );
    let summary = restore_ok(&f, "cara", body).await;
    assert_eq!(summary["media_files"], 1);
    assert_eq!(
        count(&f.pool, "SELECT count(*) + ? FROM project_yarns", 0).await,
        0
    );
}

#[tokio::test]
async fn a_failure_after_files_were_stored_rolls_back_rows_and_files() {
    let f = fixture().await;
    // The attachment insert fails after the title image was stored and inserted.
    sqlx::query(
        "CREATE TRIGGER restore_test_fail BEFORE INSERT ON media_files WHEN NEW.kind = 'attachment' \
         BEGIN SELECT RAISE(ABORT, 'forced failure'); END",
    )
    .execute(&f.pool)
    .await
    .unwrap();
    let projects = json!([{
        "id": 1, "name": "Doomed",
        "images": [{"file": "media/projects/1/a.png", "position": 0}],
        "attachments": [{"file": "media/projects/1/doc.pdf", "content_type": "application/pdf"}],
    }]);
    let body = archive(
        projects,
        vec![
            entry("media/projects/1/a.png", png(11)),
            entry("media/projects/1/doc.pdf", pdf()),
        ],
    );
    let (status, value) = post_import(&f, Some("cara"), body).await;
    assert_eq!(status, StatusCode::INTERNAL_SERVER_ERROR, "{value}");
    assert_nothing_left(&f, f.cara, 0).await;
    assert_eq!(
        count(&f.pool, "SELECT count(*) + ? FROM media_gc", 0).await,
        0,
        "removed files are not queued"
    );
    assert_eq!(
        count(
            &f.pool,
            "SELECT count(*) FROM categories WHERE user_id = ?",
            f.cara
        )
        .await,
        0
    );
}

#[tokio::test]
async fn entries_without_a_file_are_skipped_and_counted() {
    let f = fixture().await;
    let body = archive(project_with_image(None, None), vec![]);
    let summary = restore_ok(&f, "cara", body).await;
    assert_eq!(
        summary,
        json!({
            "format_version": 1, "projects": 1, "yarns": 0, "categories_created": 0,
            "media_files": 0, "media_skipped": 1,
        })
    );
    assert_eq!(
        count(
            &f.pool,
            "SELECT count(*) FROM media_files WHERE owner_id = ?",
            f.cara
        )
        .await,
        0
    );
}

#[tokio::test]
async fn attachments_and_nested_archive_layouts_restore() {
    let f = fixture().await;
    let doc = pdf();
    let projects = json!([{
        "id": 7, "name": "With file", "tags": null, "steps": null,
        "attachments": [{"file": "media/projects/7/doc.pdf", "original_filename": "pattern.pdf",
                         "content_type": "application/pdf", "sha256": sha(&doc)}],
    }]);
    let mut entries = vec![
        entry("manifest.json", manifest(json!(1))),
        entry("projects.json", serde_json::to_vec(&projects).unwrap()),
        entry("media/projects/7/doc.pdf", doc.clone()),
        // Unknown files and a second top-level directory are ignored.
        entry("README.txt", b"hello".to_vec()),
        ("other-dir/projects.json".to_owned(), b"garbage".to_vec()),
    ];
    entries.insert(0, ("stricknani-export-test/".to_owned(), Vec::new()));
    let summary = restore_ok(&f, "cara", zip_archive(&entries)).await;
    assert_eq!(summary["media_files"], 1);
    let (project_id, filename, original, content_type): (i64, String, String, String) =
        sqlx::query_as(
            "SELECT project_id, filename, original_filename, content_type FROM media_files WHERE owner_id = ?",
        )
        .bind(f.cara)
        .fetch_one(&f.pool)
        .await
        .unwrap();
    assert_eq!(original, "pattern.pdf");
    assert_eq!(content_type, "application/pdf");
    assert!(filename.ends_with(".pdf"));
    let stored = std::fs::read(
        f.root
            .path()
            .join("projects")
            .join(project_id.to_string())
            .join(filename),
    )
    .unwrap();
    assert_eq!(stored, doc);
}

// ---------------------------------------------------------------------------
// Limits

#[tokio::test]
async fn bodies_over_the_cap_are_rejected() {
    let f = fixture_with_limit(Some(1000)).await;
    restore_err(
        &f,
        "cara",
        vec![0_u8; 5000],
        StatusCode::PAYLOAD_TOO_LARGE,
        "payload_too_large",
    )
    .await;
    // A declared length is checked before reading anything.
    let response = f
        .app
        .clone()
        .oneshot(
            Request::post("/account/import")
                .header("authorization", format!("Bearer {}", pat("cara")))
                .header("content-length", "5000")
                .body(Body::from(vec![0_u8; 10]))
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::PAYLOAD_TOO_LARGE);
    assert_nothing_left(&f, f.cara, 0).await;
    // A small archive under the cap still works.
    let small = tar_gz(&[
        entry("manifest.json", manifest(json!(1))),
        entry("projects.json", b"[]".to_vec()),
    ]);
    assert!(small.len() < 1000);
    restore_ok(&f, "cara", small).await;
}

#[tokio::test]
async fn only_one_restore_per_user_runs_at_a_time() {
    let f = fixture().await;
    let (tx, rx) = tokio::sync::mpsc::channel::<Result<Bytes, std::io::Error>>(4);
    let app = f.app.clone();
    let token = pat("cara");
    let slow = tokio::spawn(async move {
        app.oneshot(
            Request::post("/account/import")
                .header("authorization", format!("Bearer {token}"))
                .body(Body::from_stream(
                    tokio_stream::wrappers::ReceiverStream::new(rx),
                ))
                .unwrap(),
        )
        .await
        .unwrap()
    });
    tx.send(Ok(Bytes::from_static(&[0x1f, 0x8b])))
        .await
        .unwrap();
    tokio::time::sleep(std::time::Duration::from_millis(400)).await;

    let valid = || {
        tar_gz(&[
            entry("manifest.json", manifest(json!(1))),
            entry("projects.json", b"[]".to_vec()),
        ])
    };
    let (status, value) = post_import(&f, Some("cara"), valid()).await;
    assert_eq!(status, StatusCode::TOO_MANY_REQUESTS, "{value}");
    assert_eq!(value["error"]["code"], "rate_limited");
    // Another account is not blocked, and neither is the export of the same account.
    restore_ok(&f, "dora", valid()).await;
    let exported = export(&f, "cara", "tar.gz").await;
    assert!(!exported.is_empty());

    drop(tx);
    let response = slow.await.unwrap();
    assert_eq!(response.status(), StatusCode::BAD_REQUEST);
    // The slot is free again.
    restore_ok(&f, "cara", valid()).await;
}

#[tokio::test]
async fn restore_keeps_project_status_and_defaults_missing_or_unknown_to_in_progress() {
    let f = fixture().await;
    let projects = json!([
        {"id": 1, "name": "Finished", "status": "done", "tags": [], "steps": [],
         "yarn_ids": [], "images": [], "attachments": []},
        {"id": 2, "name": "Old export", "tags": [], "steps": [],
         "yarn_ids": [], "images": [], "attachments": []},
        {"id": 3, "name": "Odd", "status": "paused", "tags": [], "steps": [],
         "yarn_ids": [], "images": [], "attachments": []},
    ]);
    restore_ok(&f, "dora", archive(projects, Vec::new())).await;
    let rows: Vec<(String, String)> = sqlx::query_as(
        "SELECT name, status FROM projects WHERE owner_id = ? ORDER BY name",
    )
    .bind(f.dora)
    .fetch_all(&f.pool)
    .await
    .unwrap();
    assert_eq!(
        rows,
        vec![
            ("Finished".to_owned(), "done".to_owned()),
            ("Odd".to_owned(), "in_progress".to_owned()),
            ("Old export".to_owned(), "in_progress".to_owned()),
        ]
    );
}
