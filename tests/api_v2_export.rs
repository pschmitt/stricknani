//! Per-user data export (`GET /api/v2/account/export`): archive layout, access
//! rules, missing media and the one-export-at-a-time limit.

use std::io::{Cursor, Read};

use axum::{
    body::Body,
    http::{Request, StatusCode},
    response::Response,
};
use http_body_util::BodyExt;
use serde_json::Value;
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
    admin: i64,
}

fn pat(name: &str) -> String {
    format!("export-{name}-api-pat")
}

async fn seed_user(pool: &SqlitePool, name: &str, admin: bool) -> i64 {
    let id = sqlx::query(
        "INSERT INTO users (email, password_hash, is_admin, active) VALUES (?, 'test-only-hash', ?, 1)",
    )
    .bind(format!("{name}@example.test"))
    .bind(admin)
    .execute(pool)
    .await
    .unwrap()
    .last_insert_rowid();
    sqlx::query(
        "INSERT INTO api_tokens (user_id, name, token_hash, created_at, expires_at, purpose) \
         VALUES (?, 'export fixture', ?, unixepoch(), unixepoch() + 86400, 'api')",
    )
    .bind(id)
    .bind(hex::encode(Sha256::digest(pat(name).as_bytes())))
    .execute(pool)
    .await
    .unwrap();
    id
}

async fn fixture() -> Fx {
    let pool = db::connect("sqlite::memory:").await.unwrap();
    let root = tempfile::tempdir().unwrap();
    let state = db::AppState::new(pool.clone())
        .with_secure_cookies(true)
        .with_media_root(root.path().to_path_buf());
    let anika = seed_user(&pool, "anika", false).await;
    let ben = seed_user(&pool, "ben", false).await;
    let admin = seed_user(&pool, "admin", true).await;
    Fx {
        root,
        app: api_v2::router(state, false),
        pool,
        anika,
        ben,
        admin,
    }
}

async fn project(pool: &SqlitePool, owner: i64, name: &str) -> i64 {
    sqlx::query_scalar(
        "INSERT INTO projects (owner_id,name,tags,created_at,updated_at) \
         VALUES (?,?,'[]',?,?) RETURNING id",
    )
    .bind(owner)
    .bind(name)
    .bind(OLD)
    .bind(OLD)
    .fetch_one(pool)
    .await
    .unwrap()
}

async fn yarn(pool: &SqlitePool, owner: i64, name: &str) -> i64 {
    sqlx::query_scalar(
        "INSERT INTO yarns (owner_id,name,created_at,updated_at) VALUES (?,?,?,?) RETURNING id",
    )
    .bind(owner)
    .bind(name)
    .bind(OLD)
    .bind(OLD)
    .fetch_one(pool)
    .await
    .unwrap()
}

async fn link(pool: &SqlitePool, project: i64, yarn: i64) {
    sqlx::query("INSERT INTO project_yarns (project_id, yarn_id) VALUES (?, ?)")
        .bind(project)
        .bind(yarn)
        .execute(pool)
        .await
        .unwrap();
}

async fn share(pool: &SqlitePool, kind: &str, id: i64, user: i64, role: &str, by: i64) {
    sqlx::query(
        "INSERT INTO resource_shares (kind, resource_id, user_id, role, created_by, created_at, updated_at) \
         VALUES (?, ?, ?, ?, ?, ?, ?)",
    )
    .bind(kind)
    .bind(id)
    .bind(user)
    .bind(role)
    .bind(by)
    .bind(OLD)
    .bind(OLD)
    .execute(pool)
    .await
    .unwrap();
}

async fn step(pool: &SqlitePool, project: i64, number: i64) -> i64 {
    sqlx::query_scalar(
        "INSERT INTO project_steps (project_id,title,step_number) VALUES (?, 'cast on', ?) RETURNING id",
    )
    .bind(project)
    .bind(number)
    .fetch_one(pool)
    .await
    .unwrap()
}

struct Media<'a> {
    owner: i64,
    project: Option<i64>,
    yarn: Option<i64>,
    step: Option<i64>,
    kind: &'a str,
    public_id: i64,
    filename: &'a str,
    original: &'a str,
    content_type: &'a str,
    position: i64,
    primary: bool,
}

/// Insert a media row and, unless `bytes` is `None`, its file under the media root.
async fn media(f: &Fx, m: Media<'_>, bytes: Option<&[u8]>) {
    sqlx::query(
        "INSERT INTO media_files (owner_id,project_id,yarn_id,step_id,kind,public_id,filename, \
         original_filename,content_type,size_bytes,alt_text,is_primary,position,is_project_cover) \
         VALUES (?,?,?,?,?,?,?,?,?,?, 'alt',?,?,?)",
    )
    .bind(m.owner)
    .bind(m.project)
    .bind(m.yarn)
    .bind(m.step)
    .bind(m.kind)
    .bind(m.public_id)
    .bind(m.filename)
    .bind(m.original)
    .bind(m.content_type)
    .bind(bytes.map_or(10, |bytes| bytes.len() as i64))
    .bind(m.primary)
    .bind(m.position)
    .bind(m.primary && m.kind != "photo" && m.kind != "attachment")
    .execute(&f.pool)
    .await
    .unwrap();
    if let Some(bytes) = bytes {
        let (subdir, parent) = match m.project {
            Some(project) => ("projects", project),
            None => ("yarns", m.yarn.unwrap()),
        };
        let dir = f.root.path().join(subdir).join(parent.to_string());
        std::fs::create_dir_all(&dir).unwrap();
        std::fs::write(dir.join(m.filename), bytes).unwrap();
    }
}

fn get_export(token: Option<&str>) -> Request<Body> {
    let mut request = Request::get("/account/export");
    if let Some(token) = token {
        request = request.header("authorization", format!("Bearer {token}"));
    }
    request.body(Body::empty()).unwrap()
}

async fn export(f: &Fx, name: &str) -> Archive {
    let response = f
        .app
        .clone()
        .oneshot(get_export(Some(&pat(name))))
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    Archive::read(response).await
}

struct Archive {
    top: String,
    files: std::collections::BTreeMap<String, Vec<u8>>,
    order: Vec<String>,
    modes: Vec<(u32, u64)>,
}

impl Archive {
    async fn read(response: Response) -> Self {
        let bytes = response.into_body().collect().await.unwrap().to_bytes();
        if !bytes.starts_with(&[0x1f, 0x8b]) {
            return Self::read_zip(&bytes);
        }
        let mut tar = tar::Archive::new(flate2::read::GzDecoder::new(Cursor::new(bytes.to_vec())));
        let mut files = std::collections::BTreeMap::new();
        let mut order = Vec::new();
        let mut modes = Vec::new();
        let mut top = String::new();
        for entry in tar.entries().expect("body should be a tar.gz") {
            let mut entry = entry.unwrap();
            assert!(entry.header().entry_type().is_file());
            modes.push((
                entry.header().mode().unwrap(),
                entry.header().mtime().unwrap(),
            ));
            let path = entry.path().unwrap().to_string_lossy().into_owned();
            let (dir, rest) = path.split_once('/').expect("one top-level directory");
            if top.is_empty() {
                top = dir.to_owned();
            }
            assert_eq!(top, dir);
            let mut data = Vec::new();
            entry.read_to_end(&mut data).unwrap();
            order.push(rest.to_owned());
            files.insert(rest.to_owned(), data);
        }
        Self {
            top,
            files,
            order,
            modes,
        }
    }

    fn read_zip(bytes: &[u8]) -> Self {
        let mut zip = zip::ZipArchive::new(Cursor::new(bytes.to_vec())).expect("a zip");
        let mut files = std::collections::BTreeMap::new();
        let mut order = Vec::new();
        let mut modes = Vec::new();
        let mut top = String::new();
        for index in 0..zip.len() {
            let mut entry = zip.by_index(index).unwrap();
            let path = entry.name().unwrap().into_owned();
            let (dir, rest) = path.split_once('/').expect("one top-level directory");
            if top.is_empty() {
                top = dir.to_owned();
            }
            assert_eq!(top, dir);
            // Every entry is deflated: sequential readers (java.util.zip.ZipInputStream)
            // refuse stored entries that carry a data descriptor.
            assert_eq!(
                entry.compression(),
                zip::CompressionMethod::Deflated,
                "{rest}"
            );
            modes.push((0o644, 0));
            let mut data = Vec::new();
            entry.read_to_end(&mut data).unwrap();
            order.push(rest.to_owned());
            files.insert(rest.to_owned(), data);
        }
        Self {
            top,
            files,
            order,
            modes,
        }
    }

    fn file(&mut self, name: &str) -> Vec<u8> {
        self.files
            .get(name)
            .unwrap_or_else(|| panic!("{name} should be in the archive"))
            .clone()
    }

    fn json(&mut self, name: &str) -> Value {
        serde_json::from_slice(&self.file(name)).unwrap()
    }

    fn names(&self) -> Vec<String> {
        self.order.clone()
    }
}

fn sha(bytes: &[u8]) -> String {
    hex::encode(Sha256::digest(bytes))
}

fn by_id(items: &Value, id: i64) -> &Value {
    items
        .as_array()
        .unwrap()
        .iter()
        .find(|item| item["id"] == id)
        .unwrap_or_else(|| panic!("item {id} should be exported"))
}

fn ids(items: &Value) -> Vec<i64> {
    items
        .as_array()
        .unwrap()
        .iter()
        .map(|item| item["id"].as_i64().unwrap())
        .collect()
}

#[tokio::test]
async fn anonymous_export_is_unauthorized() {
    let f = fixture().await;
    let response = f.app.clone().oneshot(get_export(None)).await.unwrap();
    assert_eq!(response.status(), StatusCode::UNAUTHORIZED);
    let bad = f
        .app
        .clone()
        .oneshot(get_export(Some("not-a-token")))
        .await
        .unwrap();
    assert_eq!(bad.status(), StatusCode::UNAUTHORIZED);
}

#[tokio::test]
async fn bearer_export_has_headers_manifest_and_counts() {
    let f = fixture().await;
    sqlx::query("INSERT INTO categories (user_id, name) VALUES (?, 'Socks')")
        .bind(f.anika)
        .execute(&f.pool)
        .await
        .unwrap();
    sqlx::query("INSERT INTO categories (user_id, name) VALUES (?, 'Not mine')")
        .bind(f.ben)
        .execute(&f.pool)
        .await
        .unwrap();
    let p = project(&f.pool, f.anika, "Scarf").await;
    yarn(&f.pool, f.anika, "Wool").await;
    media(
        &f,
        Media {
            owner: f.anika,
            project: Some(p),
            yarn: None,
            step: None,
            kind: "title",
            public_id: 1,
            filename: "t1.png",
            original: "IMG_1.png",
            content_type: "image/png",
            position: 0,
            primary: true,
        },
        Some(b"title bytes"),
    )
    .await;

    let response = f
        .app
        .clone()
        .oneshot(get_export(Some(&pat("anika"))))
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    let headers = response.headers().clone();
    assert_eq!(headers["content-type"], "application/gzip");
    assert_eq!(headers["cache-control"], "no-store");
    assert!(headers.get("content-length").is_none());
    let disposition = headers["content-disposition"].to_str().unwrap();
    assert!(disposition.starts_with("attachment; filename=\"stricknani-export-"));
    assert!(disposition.ends_with(".tar.gz\""));
    let mut archive = Archive::read(response).await;

    assert_eq!(archive.names()[0], "manifest.json", "manifest comes first");
    assert!(archive.top.starts_with("stricknani-export-"));
    assert!(archive.modes.iter().all(|(mode, _)| *mode == 0o644));
    let mtimes: std::collections::HashSet<_> = archive.modes.iter().map(|(_, m)| *m).collect();
    assert_eq!(mtimes.len(), 1, "one export time");
    let manifest = archive.json("manifest.json");
    assert_eq!(manifest["format"], "stricknani-export");
    assert_eq!(manifest["format_version"], 1);
    assert_eq!(manifest["server"]["version"], env!("CARGO_PKG_VERSION"));
    assert!(manifest["server"].get("revision").is_some());
    assert_eq!(manifest["account"]["id"], f.anika);
    assert_eq!(manifest["account"]["email"], "anika@example.test");
    assert!(manifest["created_at"].as_str().unwrap().ends_with('Z'));
    assert_eq!(
        manifest["counts"],
        serde_json::json!({"projects": 1, "yarns": 1, "categories": 1, "media_files": 1})
    );
    let categories = archive.json("categories.json");
    assert_eq!(categories.as_array().unwrap().len(), 1);
    assert_eq!(categories[0]["name"], "Socks");
}

#[tokio::test]
async fn own_project_and_yarn_media_are_written_with_matching_checksums() {
    let f = fixture().await;
    let p = project(&f.pool, f.anika, "Cardigan").await;
    let y = yarn(&f.pool, f.anika, "Merino").await;
    link(&f.pool, p, y).await;
    let s = step(&f.pool, p, 1).await;
    let title = b"\x89PNG title image".to_vec();
    let step_image = b"step image bytes".repeat(10_000);
    let attachment = b"%PDF-1.4 pattern".to_vec();
    let photo = b"yarn photo".to_vec();
    let base = |kind, public_id, filename, original, content_type| Media {
        owner: f.anika,
        project: Some(p),
        yarn: None,
        step: None,
        kind,
        public_id,
        filename,
        original,
        content_type,
        position: 0,
        primary: false,
    };
    media(
        &f,
        Media {
            primary: true,
            ..base("title", 1, "title1.png", "IMG_2041.png", "image/png")
        },
        Some(&title),
    )
    .await;
    media(
        &f,
        Media {
            step: Some(s),
            ..base("step", 2, "step1.jpg", "step.jpg", "image/jpeg")
        },
        Some(&step_image),
    )
    .await;
    media(
        &f,
        base(
            "attachment",
            1,
            "pattern1.pdf",
            "pattern.pdf",
            "application/pdf",
        ),
        Some(&attachment),
    )
    .await;
    media(
        &f,
        Media {
            project: None,
            yarn: Some(y),
            primary: true,
            ..base("photo", 1, "yarn1.jpg", "ball.jpg", "image/jpeg")
        },
        Some(&photo),
    )
    .await;

    let mut archive = export(&f, "anika").await;
    let names = archive.names();
    for name in [
        "manifest.json",
        "projects.json",
        "yarns.json",
        "categories.json",
        &format!("media/projects/{p}/title1.png"),
        &format!("media/projects/{p}/step1.jpg"),
        &format!("media/projects/{p}/pattern1.pdf"),
        &format!("media/yarns/{y}/yarn1.jpg"),
    ] {
        assert!(names.iter().any(|n| n == name), "{name} missing: {names:?}");
    }
    let manifest = archive.json("manifest.json");
    assert_eq!(manifest["counts"]["media_files"], 4);

    let projects = archive.json("projects.json");
    let project = by_id(&projects, p);
    assert_eq!(project["name"], "Cardigan");
    assert_eq!(project["access"]["role"], "owner");
    assert_eq!(project["owner"]["id"], f.anika);
    assert_eq!(project["yarn_ids"], serde_json::json!([y]));

    let image = &project["images"][0];
    assert_eq!(image["file"], format!("media/projects/{p}/title1.png"));
    assert_eq!(image["original_filename"], "IMG_2041.png");
    assert_eq!(image["content_type"], "image/png");
    assert_eq!(image["position"], 0);
    assert_eq!(image["is_primary"], true);
    assert_eq!(image["size_bytes"], title.len());
    assert_eq!(image["sha256"], sha(&title));
    assert_eq!(project["images"].as_array().unwrap().len(), 1);

    let step_entry = &project["steps"][0]["images"][0];
    assert_eq!(step_entry["file"], format!("media/projects/{p}/step1.jpg"));
    assert_eq!(step_entry["sha256"], sha(&step_image));
    assert_eq!(step_entry["size_bytes"], step_image.len());

    let attached = &project["attachments"][0];
    assert_eq!(attached["file"], format!("media/projects/{p}/pattern1.pdf"));
    assert_eq!(attached["content_type"], "application/pdf");
    assert_eq!(attached["is_primary"], false);
    assert_eq!(attached["sha256"], sha(&attachment));

    let yarns = archive.json("yarns.json");
    let yarn = by_id(&yarns, y);
    assert_eq!(yarn["project_ids"], serde_json::json!([p]));
    assert_eq!(yarn["access"]["role"], "owner");
    let entry = &yarn["photos"][0];
    assert_eq!(entry["file"], format!("media/yarns/{y}/yarn1.jpg"));
    assert_eq!(entry["is_primary"], true);
    assert_eq!(entry["sha256"], sha(&photo));

    // The files in the zip are the stored bytes.
    assert_eq!(
        archive.file(&format!("media/projects/{p}/title1.png")),
        title
    );
    assert_eq!(
        archive.file(&format!("media/projects/{p}/step1.jpg")),
        step_image
    );
    assert_eq!(archive.file(&format!("media/yarns/{y}/yarn1.jpg")), photo);
}

#[tokio::test]
async fn shared_items_carry_owner_and_role_and_revocation_removes_them() {
    let f = fixture().await;
    let shared = project(&f.pool, f.anika, "Shared sweater").await;
    let private = project(&f.pool, f.anika, "Private hat").await;
    let own = project(&f.pool, f.ben, "Ben own").await;
    let linked = yarn(&f.pool, f.anika, "Linked wool").await;
    let other_use = yarn(&f.pool, f.anika, "Private wool").await;
    link(&f.pool, shared, linked).await;
    link(&f.pool, private, linked).await;
    link(&f.pool, private, other_use).await;
    share(&f.pool, "project", shared, f.ben, "editor", f.anika).await;
    media(
        &f,
        Media {
            owner: f.anika,
            project: Some(shared),
            yarn: None,
            step: None,
            kind: "title",
            public_id: 1,
            filename: "shared.png",
            original: "shared.png",
            content_type: "image/png",
            position: 0,
            primary: true,
        },
        Some(b"shared image"),
    )
    .await;

    let mut archive = export(&f, "ben").await;
    let projects = archive.json("projects.json");
    assert_eq!(ids(&projects), vec![shared, own]);
    let entry = by_id(&projects, shared);
    assert_eq!(entry["owner"]["id"], f.anika);
    assert_eq!(entry["access"]["role"], "editor");
    assert_eq!(by_id(&projects, own)["access"]["role"], "owner");
    assert_eq!(
        entry["images"][0]["sha256"],
        sha(b"shared image"),
        "shared media are part of the archive"
    );
    // The yarn is visible through the shared project; the other project of
    // that yarn and the other yarn stay out.
    let yarns = archive.json("yarns.json");
    assert_eq!(ids(&yarns), vec![linked]);
    let derived = by_id(&yarns, linked);
    assert_eq!(derived["owner"]["id"], f.anika);
    assert_eq!(derived["access"]["role"], "viewer");
    assert_eq!(derived["access"]["derived"], true);
    assert_eq!(derived["project_ids"], serde_json::json!([shared]));
    assert_eq!(archive.json("manifest.json")["counts"]["projects"], 2);

    // Revoking the share leaves nothing of it behind.
    sqlx::query("DELETE FROM resource_shares WHERE user_id = ?")
        .bind(f.ben)
        .execute(&f.pool)
        .await
        .unwrap();
    let mut archive = export(&f, "ben").await;
    assert_eq!(ids(&archive.json("projects.json")), vec![own]);
    assert!(ids(&archive.json("yarns.json")).is_empty());
    assert!(
        archive
            .names()
            .iter()
            .all(|name| !name.starts_with("media/projects/") || name.contains(&own.to_string())),
        "no media of the revoked project: {:?}",
        archive.names()
    );
    assert_eq!(archive.json("manifest.json")["counts"]["media_files"], 0);
}

#[tokio::test]
async fn other_users_unshared_items_and_admin_scope_are_excluded() {
    let f = fixture().await;
    let mine = project(&f.pool, f.ben, "Ben project").await;
    let theirs = project(&f.pool, f.anika, "Anika project").await;
    let their_yarn = yarn(&f.pool, f.anika, "Anika yarn").await;
    let admin_own = yarn(&f.pool, f.admin, "Admin yarn").await;
    sqlx::query("INSERT INTO categories (user_id, name) VALUES (?, 'Anika category')")
        .bind(f.anika)
        .execute(&f.pool)
        .await
        .unwrap();

    let mut ben = export(&f, "ben").await;
    assert_eq!(ids(&ben.json("projects.json")), vec![mine]);
    assert!(ids(&ben.json("yarns.json")).is_empty());
    assert!(ben.json("categories.json").as_array().unwrap().is_empty());

    // An administrator gets what a normal user could read, not every account.
    let mut admin = export(&f, "admin").await;
    assert!(ids(&admin.json("projects.json")).is_empty());
    assert_eq!(ids(&admin.json("yarns.json")), vec![admin_own]);
    assert!(admin.json("categories.json").as_array().unwrap().is_empty());
    assert_eq!(admin.json("manifest.json")["counts"]["projects"], 0);
    let _ = (theirs, their_yarn);
}

#[tokio::test]
async fn admin_with_a_share_gets_the_share_role() {
    let f = fixture().await;
    let p = project(&f.pool, f.anika, "Shared with admin").await;
    share(&f.pool, "project", p, f.admin, "viewer", f.anika).await;
    let mut archive = export(&f, "admin").await;
    let projects = archive.json("projects.json");
    assert_eq!(ids(&projects), vec![p]);
    assert_eq!(by_id(&projects, p)["access"]["role"], "viewer");
}

#[tokio::test]
async fn missing_media_file_is_listed_as_missing() {
    let f = fixture().await;
    let p = project(&f.pool, f.anika, "Gaps").await;
    media(
        &f,
        Media {
            owner: f.anika,
            project: Some(p),
            yarn: None,
            step: None,
            kind: "title",
            public_id: 1,
            filename: "gone.png",
            original: "gone.png",
            content_type: "image/png",
            position: 0,
            primary: true,
        },
        None,
    )
    .await;
    media(
        &f,
        Media {
            owner: f.anika,
            project: Some(p),
            yarn: None,
            step: None,
            kind: "attachment",
            public_id: 1,
            filename: "here.pdf",
            original: "here.pdf",
            content_type: "application/pdf",
            position: 0,
            primary: false,
        },
        Some(b"present"),
    )
    .await;
    let mut archive = export(&f, "anika").await;
    let projects = archive.json("projects.json");
    let image = &by_id(&projects, p)["images"][0];
    assert!(image["file"].is_null());
    assert_eq!(image["missing"], true);
    assert_eq!(image["original_filename"], "gone.png");
    assert!(image["sha256"].is_null());
    let attached = &by_id(&projects, p)["attachments"][0];
    assert_eq!(attached["sha256"], sha(b"present"));
    assert!(attached.get("missing").is_none());
    assert_eq!(archive.json("manifest.json")["counts"]["media_files"], 1);
    assert!(
        !archive
            .names()
            .iter()
            .any(|name| name.ends_with("gone.png"))
    );
}

#[tokio::test]
async fn a_second_concurrent_export_of_the_same_user_is_rate_limited() {
    let f = fixture().await;
    project(&f.pool, f.anika, "Busy").await;
    let first = f
        .app
        .clone()
        .oneshot(get_export(Some(&pat("anika"))))
        .await
        .unwrap();
    assert_eq!(first.status(), StatusCode::OK);

    let second = f
        .app
        .clone()
        .oneshot(get_export(Some(&pat("anika"))))
        .await
        .unwrap();
    assert_eq!(second.status(), StatusCode::TOO_MANY_REQUESTS);
    assert!(second.headers().get("retry-after").is_some());
    let bytes = second.into_body().collect().await.unwrap().to_bytes();
    let body: Value = serde_json::from_slice(&bytes).unwrap();
    assert_eq!(body["error"]["code"], "rate_limited");

    // Another user is not affected.
    let other = f
        .app
        .clone()
        .oneshot(get_export(Some(&pat("ben"))))
        .await
        .unwrap();
    assert_eq!(other.status(), StatusCode::OK);
    drop(other);

    // The slot is released when the body is finished or dropped.
    let mut archive = Archive::read(first).await;
    assert_eq!(archive.json("manifest.json")["counts"]["projects"], 1);
    let third = f
        .app
        .clone()
        .oneshot(get_export(Some(&pat("anika"))))
        .await
        .unwrap();
    assert_eq!(third.status(), StatusCode::OK);
    drop(third);
    let fourth = f
        .app
        .clone()
        .oneshot(get_export(Some(&pat("anika"))))
        .await
        .unwrap();
    assert_eq!(fourth.status(), StatusCode::OK);
}

#[tokio::test]
async fn zip_archive_option_has_the_same_content() {
    let f = fixture().await;
    let p = project(&f.pool, f.anika, "Zipped").await;
    media(
        &f,
        Media {
            owner: f.anika,
            project: Some(p),
            yarn: None,
            step: None,
            kind: "attachment",
            public_id: 1,
            filename: "doc.pdf",
            original: "doc.pdf",
            content_type: "application/pdf",
            position: 0,
            primary: false,
        },
        Some(b"zip payload"),
    )
    .await;
    let response = f
        .app
        .clone()
        .oneshot(
            Request::get("/account/export?archive=zip")
                .header("authorization", format!("Bearer {}", pat("anika")))
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    assert_eq!(response.headers()["content-type"], "application/zip");
    let disposition = response.headers()["content-disposition"].to_str().unwrap();
    assert!(disposition.ends_with(".zip\""), "{disposition}");
    let mut archive = Archive::read(response).await;
    assert_eq!(archive.names()[0], "manifest.json");
    assert!(archive.top.starts_with("stricknani-export-"));
    assert_eq!(archive.json("manifest.json")["counts"]["media_files"], 1);
    let projects = archive.json("projects.json");
    let attached = &by_id(&projects, p)["attachments"][0];
    assert_eq!(attached["sha256"], sha(b"zip payload"));
    assert_eq!(
        archive.file(attached["file"].as_str().unwrap()),
        b"zip payload"
    );

    // An explicit tar.gz is the default container.
    let response = f
        .app
        .clone()
        .oneshot(
            Request::get("/account/export?archive=tar.gz")
                .header("authorization", format!("Bearer {}", pat("anika")))
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(response.headers()["content-type"], "application/gzip");
}

#[tokio::test]
async fn unknown_archive_value_is_an_invalid_request() {
    let f = fixture().await;
    let response = f
        .app
        .clone()
        .oneshot(
            Request::get("/account/export?archive=rar")
                .header("authorization", format!("Bearer {}", pat("anika")))
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::BAD_REQUEST);
    let bytes = response.into_body().collect().await.unwrap().to_bytes();
    let body: Value = serde_json::from_slice(&bytes).unwrap();
    assert_eq!(body["error"]["code"], "invalid_request");
}
