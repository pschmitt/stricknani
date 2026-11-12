//! Administrator backups: listing, preview, the guarded restore request, and
//! the crash-safe swap that a restart performs.

use std::{
    fs,
    io::Write,
    os::unix::fs::{PermissionsExt, symlink},
    path::{Path, PathBuf},
    sync::Arc,
};

use axum::{
    Router,
    body::Body,
    http::{Request, StatusCode},
};
use http_body_util::BodyExt;
use serde_json::{Value, json};
use sha2::{Digest, Sha256};
use sqlx::{
    SqlitePool,
    sqlite::{SqliteConnectOptions, SqlitePoolOptions},
};
use stricknani_server::{
    api_v2,
    backups::{self, BackupConfig, BackupService},
    db,
};
use tower::ServiceExt;

const HOST: &str = "app.example.test";
const ORIGIN: &str = "https://app.example.test";
const ADMIN_TOKEN: &str = "sna_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
const MEMBER_TOKEN: &str = "sna_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
const API_TOKEN: &str = "sna_cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc";
const ADMIN_PASSWORD: &str = "administrator-password";

const NIGHTLY: &str = "stricknani-20260101-020000.tar.gz";
const NIGHTLY_NO_MEDIA: &str = "stricknani-20260102-020000.tar.gz";
const PREDEPLOY: &str = "predeploy-1.7.0-20260103-030000.sqlite3";
const PRE_MIGRATE: &str = "pre-migrate-34-to-37-20260104-040000.sqlite3";
const MANUAL: &str = "my-copy.sqlite3";
const FUTURE: &str = "predeploy-9.9.9-20260105-050000.sqlite3";

struct Env {
    _root: tempfile::TempDir,
    data: PathBuf,
    db_path: PathBuf,
    db_url: String,
    backups: PathBuf,
    media: PathBuf,
}

fn env() -> Env {
    let root = tempfile::tempdir().unwrap();
    let data = root.path().join("data");
    let backups = data.join("backups");
    let media = data.join("media");
    fs::create_dir_all(&backups).unwrap();
    fs::create_dir_all(&media).unwrap();
    let db_path = data.join("stricknani.sqlite3");
    Env {
        db_url: format!("sqlite:{}", db_path.display()),
        _root: root,
        data,
        db_path,
        backups,
        media,
    }
}

struct Live {
    app: Router,
    pool: SqlitePool,
    service: Arc<BackupService>,
}

/// A live file-backed database with an admin and a member, behind the router.
async fn live(env: &Env) -> Live {
    let pool = db::connect(&env.db_url).await.unwrap();
    let admin_id = db::create_user(&pool, "admin@example.test", ADMIN_PASSWORD.into())
        .await
        .unwrap();
    sqlx::query("UPDATE users SET is_admin = 1 WHERE id = ?")
        .bind(admin_id)
        .execute(&pool)
        .await
        .unwrap();
    let member_id = db::create_user(&pool, "member@example.test", "member-password-long".into())
        .await
        .unwrap();
    add_token(&pool, admin_id, ADMIN_TOKEN, "browser").await;
    add_token(&pool, member_id, MEMBER_TOKEN, "browser").await;
    add_token(&pool, admin_id, API_TOKEN, "api").await;
    let config =
        BackupConfig::resolve(&env.db_url, None, Some(env.media.as_path()), false).unwrap();
    assert_eq!(config.dir, env.backups);
    let service = Arc::new(BackupService::for_config(config));
    let state = db::AppState::new(pool.clone())
        .with_secure_cookies(true)
        .with_media_root(env.media.clone())
        .with_backups(service.clone());
    Live {
        app: api_v2::router(state, false),
        pool,
        service,
    }
}

async fn add_token(pool: &SqlitePool, user_id: i64, token: &str, purpose: &str) {
    sqlx::query(
        "INSERT INTO api_tokens (user_id, name, token_hash, created_at, expires_at, purpose) \
         VALUES (?, 'test token', ?, unixepoch(), unixepoch() + 86400, ?)",
    )
    .bind(user_id)
    .bind(hex::encode(Sha256::digest(token.as_bytes())))
    .bind(purpose)
    .execute(pool)
    .await
    .unwrap();
}

fn csrf(token: &str) -> String {
    let mut hash = Sha256::new();
    hash.update(b"stricknani browser csrf v1\0");
    hash.update(token.as_bytes());
    hex::encode(hash.finalize())
}

async fn send(app: &Router, request: Request<Body>) -> (StatusCode, Value) {
    let response = app.clone().oneshot(request).await.unwrap();
    let status = response.status();
    let bytes = response.into_body().collect().await.unwrap().to_bytes();
    (
        status,
        serde_json::from_slice(&bytes).unwrap_or(Value::Null),
    )
}

async fn browser(
    app: &Router,
    method: &str,
    path: &str,
    token: &str,
    body: Option<Value>,
) -> (StatusCode, Value) {
    let mut request = Request::builder()
        .method(method)
        .uri(path)
        .header("host", HOST)
        .header("origin", ORIGIN)
        .header("sec-fetch-site", "same-origin")
        .header("cookie", format!("__Host-stricknani_session={token}"))
        .header("x-csrf-token", csrf(token));
    let body = match body {
        Some(value) => {
            request = request.header("content-type", "application/json");
            Body::from(value.to_string())
        }
        None => Body::empty(),
    };
    send(app, request.body(body).unwrap()).await
}

async fn bearer(
    app: &Router,
    method: &str,
    path: &str,
    extra: &[(&str, &str)],
    body: Value,
) -> (StatusCode, Value) {
    let mut request = Request::builder()
        .method(method)
        .uri(path)
        .header("authorization", format!("Bearer {API_TOKEN}"))
        .header("content-type", "application/json");
    for (name, value) in extra {
        request = request.header(*name, *value);
    }
    send(app, request.body(Body::from(body.to_string())).unwrap()).await
}

async fn stamp_reauth(pool: &SqlitePool, seconds_ago: i64) {
    sqlx::query("UPDATE api_tokens SET reauth_at = unixepoch() - ? WHERE purpose = 'browser'")
        .bind(seconds_ago)
        .execute(pool)
        .await
        .unwrap();
}

// ------------------------------------------------------------------ fixtures

/// A database file with `users` accounts and the given content, optionally
/// stopped at an older schema version, written with `VACUUM INTO`.
async fn make_database(
    dest: &Path,
    users: usize,
    projects: usize,
    yarns: usize,
    trashed: usize,
    schema: Option<i64>,
) {
    let scratch = tempfile::tempdir().unwrap();
    let source = scratch.path().join("source.sqlite3");
    let pool = SqlitePoolOptions::new()
        .max_connections(1)
        .connect_with(
            SqliteConnectOptions::new()
                .filename(&source)
                .create_if_missing(true)
                .foreign_keys(true),
        )
        .await
        .unwrap();
    match schema {
        Some(version) => sqlx::migrate!().run_to(version, &pool).await.unwrap(),
        None => sqlx::migrate!().run(&pool).await.unwrap(),
    }
    for index in 0..users {
        sqlx::query("INSERT INTO users (email, password_hash) VALUES (?, 'x')")
            .bind(format!("user{index}@backup.test"))
            .execute(&pool)
            .await
            .unwrap();
    }
    for index in 0..projects {
        sqlx::query(
            "INSERT INTO projects (owner_id, name, created_at, updated_at) \
             VALUES (1, ?, '2026-01-01T00:00:00Z', '2026-01-01T00:00:00Z')",
        )
        .bind(format!("project {index}"))
        .execute(&pool)
        .await
        .unwrap();
    }
    for index in 0..yarns {
        sqlx::query(
            "INSERT INTO yarns (owner_id, name, created_at, updated_at) \
             VALUES (1, ?, '2026-01-01T00:00:00Z', '2026-01-01T00:00:00Z')",
        )
        .bind(format!("yarn {index}"))
        .execute(&pool)
        .await
        .unwrap();
    }
    if schema.is_none_or(|version| version >= 31) {
        for index in 0..trashed {
            sqlx::query(
                "INSERT INTO yarns (owner_id, name, created_at, updated_at, deleted_at) \
                 VALUES (1, ?, '2026-01-01T00:00:00Z', '2026-01-01T00:00:00Z', '2026-01-02T00:00:00Z')",
            )
            .bind(format!("trashed {index}"))
            .execute(&pool)
            .await
            .unwrap();
        }
    }
    sqlx::query("VACUUM INTO ?")
        .bind(dest.to_str().unwrap())
        .execute(&pool)
        .await
        .unwrap();
    pool.close().await;
}

/// Mark a database as written by a newer server.
async fn mark_future(path: &Path) {
    let pool = SqlitePoolOptions::new()
        .max_connections(1)
        .connect_with(SqliteConnectOptions::new().filename(path))
        .await
        .unwrap();
    sqlx::query(
        "INSERT INTO _sqlx_migrations (version, description, success, checksum, execution_time) \
         VALUES (9999, 'future', 1, X'00', 0)",
    )
    .execute(&pool)
    .await
    .unwrap();
    pool.close().await;
}

enum Media<'a> {
    None,
    Files(&'a [(&'a str, &'a [u8])]),
    /// Files plus a raw entry whose name tries to leave the media tree.
    FilesAndEscape(&'a [(&'a str, &'a [u8])]),
}

fn make_archive(dest: &Path, database: &Path, media: Media<'_>) {
    let file = fs::File::create(dest).unwrap();
    let encoder = flate2::write::GzEncoder::new(file, flate2::Compression::fast());
    let mut tar = tar::Builder::new(encoder);
    tar.append_path_with_name(database, "database.sqlite3")
        .unwrap();
    let (files, escape) = match media {
        Media::None => return finish(tar),
        Media::Files(files) => (files, false),
        Media::FilesAndEscape(files) => (files, true),
    };
    let mut dir = tar::Header::new_gnu();
    dir.set_entry_type(tar::EntryType::Directory);
    dir.set_mode(0o755);
    dir.set_size(0);
    tar.append_data(&mut dir, "media/", std::io::empty())
        .unwrap();
    for (name, content) in files {
        let mut header = tar::Header::new_gnu();
        header.set_size(content.len() as u64);
        header.set_mode(0o644);
        tar.append_data(&mut header, format!("media/{name}"), *content)
            .unwrap();
    }
    if escape {
        let mut header = tar::Header::new_gnu();
        header.set_size(4);
        header.set_mode(0o644);
        let name = b"media/../escaped.txt";
        header.as_old_mut().name[..name.len()].copy_from_slice(name);
        header.set_cksum();
        tar.append(&header, &b"evil"[..]).unwrap();
    }
    finish(tar);

    fn finish(tar: tar::Builder<flate2::write::GzEncoder<fs::File>>) {
        let mut encoder = tar.into_inner().unwrap();
        encoder.flush().unwrap();
        encoder.finish().unwrap();
    }
}

/// The standard set: every kind, with and without media, plus noise.
async fn seed_backups(env: &Env) {
    let scratch = tempfile::tempdir().unwrap();
    let full = scratch.path().join("full.sqlite3");
    make_database(&full, 3, 2, 1, 1, None).await;
    make_archive(
        &env.backups.join(NIGHTLY),
        &full,
        Media::Files(&[("a.jpg", b"archive-a"), ("b.jpg", b"archive-b")]),
    );
    make_archive(&env.backups.join(NIGHTLY_NO_MEDIA), &full, Media::None);
    fs::copy(&full, env.backups.join(PREDEPLOY)).unwrap();
    make_database(&env.backups.join(PRE_MIGRATE), 2, 0, 0, 0, Some(34)).await;
    fs::copy(&full, env.backups.join(MANUAL)).unwrap();
    fs::copy(&full, env.backups.join(FUTURE)).unwrap();
    mark_future(&env.backups.join(FUTURE)).await;
    // Noise that must never show up.
    fs::write(env.backups.join("notes.txt"), b"hello").unwrap();
    fs::write(env.backups.join(".hidden.sqlite3"), b"hidden").unwrap();
    fs::write(env.backups.join("garbage.sqlite3"), b"this is not sqlite").unwrap();
    let secret = scratch.path().join("outside.sqlite3");
    fs::copy(&full, &secret).unwrap();
    symlink(&secret, env.backups.join("linked.sqlite3")).unwrap();
}

fn names(listing: &Value) -> Vec<String> {
    listing["data"]["backups"]
        .as_array()
        .unwrap()
        .iter()
        .map(|entry| entry["name"].as_str().unwrap().to_owned())
        .collect()
}

fn entry<'a>(listing: &'a Value, name: &str) -> &'a Value {
    listing["data"]["backups"]
        .as_array()
        .unwrap()
        .iter()
        .find(|entry| entry["name"] == name)
        .unwrap_or_else(|| panic!("{name} missing from the listing"))
}

// ------------------------------------------------------------------- listing

#[tokio::test]
async fn listing_reports_kinds_versions_counts_and_ignores_noise() {
    let env = env();
    let live = live(&env).await;
    seed_backups(&env).await;
    let (status, body) = browser(&live.app, "GET", "/admin/backups", ADMIN_TOKEN, None).await;
    assert_eq!(status, StatusCode::OK, "{body}");
    let listed = names(&body);
    for expected in [
        NIGHTLY,
        NIGHTLY_NO_MEDIA,
        PREDEPLOY,
        PRE_MIGRATE,
        MANUAL,
        FUTURE,
        "garbage.sqlite3",
    ] {
        assert!(
            listed.contains(&expected.to_owned()),
            "{expected}: {listed:?}"
        );
    }
    for hidden in ["notes.txt", ".hidden.sqlite3", "linked.sqlite3"] {
        assert!(!listed.contains(&hidden.to_owned()), "{hidden} leaked");
    }
    let latest = body["data"]["server_schema_version"].as_i64().unwrap();
    assert!(latest >= 37);

    let nightly = entry(&body, NIGHTLY);
    assert_eq!(nightly["kind"], "nightly");
    assert_eq!(nightly["created_at"], "2026-01-01T02:00:00Z");
    assert_eq!(nightly["has_media"], true);
    assert_eq!(nightly["schema_version"], latest);
    assert_eq!(nightly["counts"]["users"], 3);
    assert_eq!(nightly["counts"]["projects"], 2);
    assert_eq!(nightly["counts"]["yarns"], 1);
    assert_eq!(nightly["counts"]["trashed"], 1);
    assert_eq!(nightly["restorable"], true);
    assert!(nightly["size_bytes"].as_u64().unwrap() > 0);

    assert_eq!(entry(&body, NIGHTLY_NO_MEDIA)["has_media"], false);
    assert_eq!(entry(&body, PREDEPLOY)["kind"], "predeploy");
    assert_eq!(entry(&body, PREDEPLOY)["has_media"], false);
    let pre_migrate = entry(&body, PRE_MIGRATE);
    assert_eq!(pre_migrate["kind"], "pre_migrate");
    assert_eq!(pre_migrate["schema_version"], 34);
    assert_eq!(pre_migrate["counts"]["users"], 2);
    assert_eq!(pre_migrate["restorable"], true);
    assert_eq!(entry(&body, MANUAL)["kind"], "manual");

    let future = entry(&body, FUTURE);
    assert_eq!(future["schema_version"], 9999);
    assert_eq!(future["newer_schema"], true);
    assert_eq!(future["restorable"], false);

    let garbage = entry(&body, "garbage.sqlite3");
    assert_eq!(garbage["readable"], false);
    assert_eq!(garbage["restorable"], false);
    assert!(garbage["error"].is_string());
    assert!(body["data"]["latest_pre_restore"].is_null());
}

#[tokio::test]
async fn listing_notices_a_replaced_file() {
    let env = env();
    let live = live(&env).await;
    seed_backups(&env).await;
    let (_, first) = browser(&live.app, "GET", "/admin/backups", ADMIN_TOKEN, None).await;
    assert_eq!(entry(&first, MANUAL)["counts"]["users"], 3);
    // The cache is keyed by name, mtime and size: a replaced file is read again.
    let scratch = tempfile::tempdir().unwrap();
    let other = scratch.path().join("other.sqlite3");
    make_database(&other, 5, 0, 0, 0, None).await;
    fs::copy(&other, env.backups.join(MANUAL)).unwrap();
    let (_, second) = browser(&live.app, "GET", "/admin/backups", ADMIN_TOKEN, None).await;
    assert_eq!(entry(&second, MANUAL)["counts"]["users"], 5);
}

#[tokio::test]
async fn archives_whose_database_comes_after_the_media_are_still_read() {
    let env = env();
    let live = live(&env).await;
    let scratch = tempfile::tempdir().unwrap();
    let database = scratch.path().join("db.sqlite3");
    make_database(&database, 4, 0, 0, 0, None).await;
    let archive = env.backups.join("stricknani-20260110-020000.tar.gz");
    let file = fs::File::create(&archive).unwrap();
    let mut tar = tar::Builder::new(flate2::write::GzEncoder::new(
        file,
        flate2::Compression::fast(),
    ));
    let mut header = tar::Header::new_gnu();
    header.set_size(3);
    header.set_mode(0o644);
    tar.append_data(&mut header, "media/x.jpg", &b"abc"[..])
        .unwrap();
    tar.append_path_with_name(&database, "database.sqlite3")
        .unwrap();
    tar.into_inner().unwrap().finish().unwrap();
    let (_, body) = browser(&live.app, "GET", "/admin/backups", ADMIN_TOKEN, None).await;
    let found = entry(&body, "stricknani-20260110-020000.tar.gz");
    assert_eq!(found["counts"]["users"], 4);
    assert_eq!(found["has_media"], true);
}

// ------------------------------------------------------------ authorization

#[tokio::test]
async fn only_administrators_may_use_the_endpoints() {
    let env = env();
    let live = live(&env).await;
    seed_backups(&env).await;
    let restore = format!("/admin/backups/{PREDEPLOY}/restore");
    let preview = format!("/admin/backups/{PREDEPLOY}/restore-preview");
    for (method, path, body) in [
        ("GET", "/admin/backups".to_owned(), None),
        ("GET", preview.clone(), None),
        (
            "POST",
            restore.clone(),
            Some(json!({"confirmation": PREDEPLOY})),
        ),
    ] {
        let (status, body_json) =
            browser(&live.app, method, &path, MEMBER_TOKEN, body.clone()).await;
        assert_eq!(status, StatusCode::FORBIDDEN, "{path}: {body_json}");
        assert_eq!(body_json["error"]["code"], "admin_required");
        let anonymous = Request::builder()
            .method(method)
            .uri(&path)
            .header("host", HOST)
            .header("origin", ORIGIN)
            .header("sec-fetch-site", "same-origin")
            .header("content-type", "application/json")
            .body(Body::from(body.map(|b| b.to_string()).unwrap_or_default()))
            .unwrap();
        let (status, _) = send(&live.app, anonymous).await;
        assert_eq!(status, StatusCode::UNAUTHORIZED, "{path}");
    }
    assert!(!fs::read_dir(&env.backups).unwrap().flatten().any(|entry| {
        entry
            .file_name()
            .to_string_lossy()
            .starts_with("pre-restore-")
    }));
}

#[tokio::test]
async fn names_cannot_escape_the_backup_directory() {
    let env = env();
    let live = live(&env).await;
    seed_backups(&env).await;
    stamp_reauth(&live.pool, 0).await;
    for name in [
        "..%2F..%2Fstricknani.sqlite3",
        "%2Fetc%2Fpasswd.sqlite3",
        "a%2Fb.sqlite3",
        "..%5Cx.sqlite3",
        "linked.sqlite3",
        ".hidden.sqlite3",
        "missing.sqlite3",
        "notes.txt",
    ] {
        let (status, _) = browser(
            &live.app,
            "GET",
            &format!("/admin/backups/{name}/restore-preview"),
            ADMIN_TOKEN,
            None,
        )
        .await;
        assert_eq!(status, StatusCode::NOT_FOUND, "{name}");
        let (status, _) = browser(
            &live.app,
            "POST",
            &format!("/admin/backups/{name}/restore"),
            ADMIN_TOKEN,
            Some(json!({"confirmation": name})),
        )
        .await;
        assert_eq!(status, StatusCode::NOT_FOUND, "{name}");
    }
    // The database file one level up is still there and unchanged in place.
    assert!(env.db_path.exists());
}

// ------------------------------------------------------------------- preview

#[tokio::test]
async fn preview_compares_current_and_backup_data() {
    let env = env();
    let live = live(&env).await;
    seed_backups(&env).await;
    let (status, body) = browser(
        &live.app,
        "GET",
        &format!("/admin/backups/{NIGHTLY}/restore-preview"),
        ADMIN_TOKEN,
        None,
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{body}");
    let data = &body["data"];
    assert_eq!(data["current"]["counts"]["users"], 2);
    assert_eq!(data["backup_snapshot"]["counts"]["users"], 3);
    assert_eq!(data["backup"]["name"], NIGHTLY);
    assert_eq!(data["media_options"], json!(["none", "add_missing"]));
    assert_eq!(data["restarts_server"], false);
    assert!(data["changes_since_backup"].is_i64());
    let (_, body) = browser(
        &live.app,
        "GET",
        &format!("/admin/backups/{PREDEPLOY}/restore-preview"),
        ADMIN_TOKEN,
        None,
    )
    .await;
    assert_eq!(body["data"]["media_options"], json!(["none"]));
}

// ------------------------------------------------------------------- restore

#[tokio::test]
async fn restore_needs_a_recent_reauthentication() {
    let env = env();
    let live = live(&env).await;
    seed_backups(&env).await;
    let path = format!("/admin/backups/{PREDEPLOY}/restore");
    let body = json!({"confirmation": PREDEPLOY});
    let (status, error) = browser(&live.app, "POST", &path, ADMIN_TOKEN, Some(body.clone())).await;
    assert_eq!(status, StatusCode::FORBIDDEN);
    assert_eq!(error["error"]["code"], "reauth_required");
    stamp_reauth(&live.pool, 3600).await;
    let (status, error) = browser(&live.app, "POST", &path, ADMIN_TOKEN, Some(body.clone())).await;
    assert_eq!(status, StatusCode::FORBIDDEN, "stale stamp: {error}");
    assert!(!env.backups.join(".restore-request.json").exists());
    assert!(
        !fs::read_dir(&env.backups)
            .unwrap()
            .flatten()
            .any(|entry| entry.file_name().to_string_lossy().contains("pre-restore"))
    );
}

#[tokio::test]
async fn password_reauthentication_unlocks_the_restore_for_browsers() {
    let env = env();
    let live = live(&env).await;
    seed_backups(&env).await;
    let (status, body) = browser(
        &live.app,
        "POST",
        "/account/reauth",
        ADMIN_TOKEN,
        Some(json!({"password": ADMIN_PASSWORD, "action": "restore_backup"})),
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{body}");
    let (status, body) = browser(
        &live.app,
        "POST",
        &format!("/admin/backups/{PREDEPLOY}/restore"),
        ADMIN_TOKEN,
        Some(json!({"confirmation": PREDEPLOY})),
    )
    .await;
    assert_eq!(status, StatusCode::ACCEPTED, "{body}");
}

#[tokio::test]
async fn wrong_confirmation_is_rejected_before_anything_is_written() {
    let env = env();
    let live = live(&env).await;
    seed_backups(&env).await;
    stamp_reauth(&live.pool, 0).await;
    for confirmation in [json!("predeploy"), json!(""), json!(NIGHTLY)] {
        let (status, body) = browser(
            &live.app,
            "POST",
            &format!("/admin/backups/{PREDEPLOY}/restore"),
            ADMIN_TOKEN,
            Some(json!({"confirmation": confirmation})),
        )
        .await;
        assert_eq!(status, StatusCode::UNPROCESSABLE_ENTITY, "{body}");
        assert_eq!(body["error"]["code"], "confirmation_mismatch");
    }
    let hidden: Vec<_> = fs::read_dir(&env.backups)
        .unwrap()
        .flatten()
        .map(|entry| entry.file_name().to_string_lossy().into_owned())
        .filter(|name| name.starts_with("pre-restore") || name.starts_with(".restore"))
        .collect();
    assert!(hidden.is_empty(), "{hidden:?}");
}

#[tokio::test]
async fn newer_schema_unreadable_and_medialess_options_are_refused() {
    let env = env();
    let live = live(&env).await;
    seed_backups(&env).await;
    stamp_reauth(&live.pool, 0).await;
    let (status, body) = browser(
        &live.app,
        "POST",
        &format!("/admin/backups/{FUTURE}/restore"),
        ADMIN_TOKEN,
        Some(json!({"confirmation": FUTURE})),
    )
    .await;
    assert_eq!(status, StatusCode::CONFLICT, "{body}");
    assert_eq!(body["error"]["code"], "backup_newer_schema");
    let (status, body) = browser(
        &live.app,
        "POST",
        "/admin/backups/garbage.sqlite3/restore",
        ADMIN_TOKEN,
        Some(json!({"confirmation": "garbage.sqlite3"})),
    )
    .await;
    assert_eq!(status, StatusCode::CONFLICT, "{body}");
    assert_eq!(body["error"]["code"], "backup_unreadable");
    let (status, body) = browser(
        &live.app,
        "POST",
        &format!("/admin/backups/{NIGHTLY_NO_MEDIA}/restore"),
        ADMIN_TOKEN,
        Some(json!({"confirmation": NIGHTLY_NO_MEDIA, "media": "add_missing"})),
    )
    .await;
    assert_eq!(status, StatusCode::UNPROCESSABLE_ENTITY, "{body}");
    assert_eq!(body["error"]["code"], "media_unavailable");
    assert!(!env.backups.join(".restore-request.json").exists());
}

fn dir_names(dir: &Path) -> Vec<String> {
    let mut names: Vec<_> = fs::read_dir(dir)
        .unwrap()
        .flatten()
        .map(|entry| entry.file_name().to_string_lossy().into_owned())
        .collect();
    names.sort();
    names
}

#[tokio::test]
async fn restore_writes_a_verified_pre_restore_backup_and_stages_the_swap() {
    let env = env();
    let live = live(&env).await;
    seed_backups(&env).await;
    stamp_reauth(&live.pool, 0).await;
    let (status, body) = browser(
        &live.app,
        "POST",
        &format!("/admin/backups/{NIGHTLY}/restore"),
        ADMIN_TOKEN,
        Some(json!({"confirmation": NIGHTLY})),
    )
    .await;
    assert_eq!(status, StatusCode::ACCEPTED, "{body}");
    assert_eq!(body["data"]["backup"], NIGHTLY);
    let pre_restore = body["data"]["pre_restore"].as_str().unwrap().to_owned();
    assert!(pre_restore.starts_with("pre-restore-") && pre_restore.ends_with(".sqlite3"));

    // The pre-restore copy holds the *current* data and opens cleanly.
    let copy = env.backups.join(&pre_restore);
    assert_eq!(
        fs::metadata(&copy).unwrap().permissions().mode() & 0o777,
        0o600
    );
    let pool = SqlitePoolOptions::new()
        .connect_with(SqliteConnectOptions::new().filename(&copy).read_only(true))
        .await
        .unwrap();
    let emails: Vec<String> = sqlx::query_scalar("SELECT email FROM users ORDER BY id")
        .fetch_all(&pool)
        .await
        .unwrap();
    assert_eq!(emails, ["admin@example.test", "member@example.test"]);
    pool.close().await;

    let marker: Value =
        serde_json::from_slice(&fs::read(env.backups.join(".restore-request.json")).unwrap())
            .unwrap();
    assert_eq!(marker["backup"], NIGHTLY);
    assert_eq!(marker["pre_restore"], pre_restore);
    assert_eq!(marker["phase"], "staged");
    assert_eq!(marker["media"], "none");
    assert_eq!(marker["requested_by"]["email"], "admin@example.test");
    let staged = env.backups.join(marker["staged"].as_str().unwrap());
    assert!(staged.exists());
    // The live database is untouched until the restart.
    let users: i64 = sqlx::query_scalar("SELECT count(*) FROM users")
        .fetch_one(&live.pool)
        .await
        .unwrap();
    assert_eq!(users, 2);
    assert!(live.service.restore_pending());

    // The listing shows the pending restore and the undo target.
    let (_, listing) = browser(&live.app, "GET", "/admin/backups", ADMIN_TOKEN, None).await;
    assert_eq!(listing["data"]["pending_restore"]["backup"], NIGHTLY);
    assert_eq!(listing["data"]["latest_pre_restore"], pre_restore);
    assert_eq!(entry(&listing, &pre_restore)["kind"], "pre_restore");
    // Only one restore at a time.
    let (status, body) = browser(
        &live.app,
        "POST",
        &format!("/admin/backups/{PREDEPLOY}/restore"),
        ADMIN_TOKEN,
        Some(json!({"confirmation": PREDEPLOY})),
    )
    .await;
    assert_eq!(status, StatusCode::CONFLICT, "{body}");
    assert_eq!(
        dir_names(&env.backups)
            .iter()
            .filter(|n| n.starts_with("pre-restore"))
            .count(),
        1
    );
}

#[tokio::test]
async fn native_clients_use_a_one_time_proof() {
    let env = env();
    let live = live(&env).await;
    seed_backups(&env).await;
    let path = format!("/admin/backups/{PREDEPLOY}/restore");
    let request = json!({"confirmation": PREDEPLOY});
    let (status, body) = bearer(&live.app, "POST", &path, &[], request.clone()).await;
    assert_eq!(status, StatusCode::FORBIDDEN, "{body}");
    assert_eq!(body["error"]["code"], "reauth_required");
    let (status, reauth) = bearer(
        &live.app,
        "POST",
        "/account/reauth",
        &[],
        json!({"password": ADMIN_PASSWORD, "action": "restore_backup"}),
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{reauth}");
    let proof = reauth["data"]["reauth_token"].as_str().unwrap().to_owned();
    // A proof for another action is useless here.
    let (_, other) = bearer(
        &live.app,
        "POST",
        "/account/reauth",
        &[],
        json!({"password": ADMIN_PASSWORD, "action": "change_password"}),
    )
    .await;
    let other = other["data"]["reauth_token"].as_str().unwrap().to_owned();
    let (status, _) = bearer(
        &live.app,
        "POST",
        &path,
        &[("x-reauth-token", &other)],
        request.clone(),
    )
    .await;
    assert_eq!(status, StatusCode::FORBIDDEN);
    // A typo does not spend the proof.
    let (status, _) = bearer(
        &live.app,
        "POST",
        &path,
        &[("x-reauth-token", &proof)],
        json!({"confirmation": "nope"}),
    )
    .await;
    assert_eq!(status, StatusCode::UNPROCESSABLE_ENTITY);
    let (status, body) = bearer(
        &live.app,
        "POST",
        &path,
        &[("x-reauth-token", &proof)],
        request.clone(),
    )
    .await;
    assert_eq!(status, StatusCode::ACCEPTED, "{body}");
    // Spent: replaying it fails (and a second restore would be busy anyway).
    fs::remove_file(env.backups.join(".restore-request.json")).unwrap();
    let (status, _) = bearer(
        &live.app,
        "POST",
        &path,
        &[("x-reauth-token", &proof)],
        request,
    )
    .await;
    assert_eq!(status, StatusCode::FORBIDDEN);
}

#[tokio::test]
async fn a_failing_pre_restore_backup_aborts_without_staging() {
    let env = env();
    let live = live(&env).await;
    seed_backups(&env).await;
    stamp_reauth(&live.pool, 0).await;
    let before = dir_names(&env.backups);
    fs::set_permissions(&env.backups, fs::Permissions::from_mode(0o555)).unwrap();
    let writable = fs::File::create(env.backups.join("probe")).is_ok();
    if writable {
        // Running as a user that ignores directory permissions (root).
        fs::set_permissions(&env.backups, fs::Permissions::from_mode(0o755)).unwrap();
        let _ = fs::remove_file(env.backups.join("probe"));
        return;
    }
    let (status, body) = browser(
        &live.app,
        "POST",
        &format!("/admin/backups/{PREDEPLOY}/restore"),
        ADMIN_TOKEN,
        Some(json!({"confirmation": PREDEPLOY})),
    )
    .await;
    fs::set_permissions(&env.backups, fs::Permissions::from_mode(0o755)).unwrap();
    assert_eq!(status, StatusCode::INTERNAL_SERVER_ERROR, "{body}");
    assert_eq!(body["error"]["code"], "pre_restore_failed");
    assert_eq!(dir_names(&env.backups), before);
    assert!(!live.service.restore_pending());
}

#[tokio::test]
async fn writes_are_rejected_while_a_restore_is_pending() {
    let env = env();
    let live = live(&env).await;
    seed_backups(&env).await;
    stamp_reauth(&live.pool, 0).await;
    let state = db::AppState::new(live.pool.clone())
        .with_secure_cookies(true)
        .with_media_root(env.media.clone())
        .with_backups(live.service.clone());
    let app = stricknani_server::app_with_state(state);
    let (status, _) = browser(
        &app,
        "POST",
        &format!("/api/v2/admin/backups/{PREDEPLOY}/restore"),
        ADMIN_TOKEN,
        Some(json!({"confirmation": PREDEPLOY})),
    )
    .await;
    assert_eq!(status, StatusCode::ACCEPTED);
    let (status, body) = browser(
        &app,
        "POST",
        "/api/v2/projects",
        ADMIN_TOKEN,
        Some(json!({"name": "blocked"})),
    )
    .await;
    assert_eq!(status, StatusCode::SERVICE_UNAVAILABLE, "{body}");
    assert_eq!(body["error"]["code"], "restore_in_progress");
    let (status, _) = browser(&app, "GET", "/api/v2/admin/backups", ADMIN_TOKEN, None).await;
    assert_eq!(status, StatusCode::OK);
}

#[tokio::test]
async fn meta_exposes_the_data_epoch() {
    let env = env();
    let live = live(&env).await;
    let request = Request::builder().uri("/meta").body(Body::empty()).unwrap();
    let (status, body) = send(&live.app, request).await;
    assert_eq!(status, StatusCode::OK);
    let epoch = body["data"]["data_epoch"].as_str().unwrap();
    assert_eq!(epoch, live.service.data_epoch());
    assert!(!epoch.is_empty());
    assert!(body["data"]["last_restore"].is_null());
}

// ------------------------------------------------------- the swap at startup

/// Request a restore over HTTP, stop the "server" (close the pool) and return
/// the response data.
async fn stage_restore(env: &Env, live: &Live, name: &str, media: &str) -> Value {
    stamp_reauth(&live.pool, 0).await;
    let (status, body) = browser(
        &live.app,
        "POST",
        &format!("/admin/backups/{name}/restore"),
        ADMIN_TOKEN,
        Some(json!({"confirmation": name, "media": media})),
    )
    .await;
    assert_eq!(status, StatusCode::ACCEPTED, "{body}");
    assert!(env.backups.join(".restore-request.json").exists());
    body["data"].clone()
}

async fn read_all(url: &str) -> (Vec<String>, i64, Vec<(String, String)>) {
    let pool = db::connect(url).await.unwrap();
    let emails = sqlx::query_scalar("SELECT email FROM users ORDER BY id")
        .fetch_all(&pool)
        .await
        .unwrap();
    let version: i64 = sqlx::query_scalar("SELECT max(version) FROM _sqlx_migrations")
        .fetch_one(&pool)
        .await
        .unwrap();
    let audit: Vec<(String, String)> = sqlx::query_as(
        "SELECT action, coalesce(details, '') FROM auth_audit_events WHERE action = 'backup_restored'",
    )
    .fetch_all(&pool)
    .await
    .unwrap();
    pool.close().await;
    (emails, version, audit)
}

fn leftovers(env: &Env) -> Vec<String> {
    dir_names(&env.data)
        .into_iter()
        .filter(|name| name.contains("restore-"))
        .chain(dir_names(&env.backups).into_iter().filter(|name| {
            name.starts_with(".restore-request") || name.starts_with(".restore-staging")
        }))
        .collect()
}

#[tokio::test]
async fn startup_applies_the_staged_restore_and_migrates_forward() {
    let env = env();
    let live = live(&env).await;
    seed_backups(&env).await;
    let epoch_before = live.service.data_epoch().to_owned();
    let accepted = stage_restore(&env, &live, PRE_MIGRATE, "none").await;
    let pre_restore = accepted["pre_restore"].as_str().unwrap().to_owned();
    live.pool.close().await;
    drop(live);

    let service = backups::prepare_startup(&env.db_url, &env.media)
        .await
        .unwrap()
        .expect("file databases have backups");
    let (emails, version, audit) = read_all(&env.db_url).await;
    // The data of the backup, migrated to this server's schema.
    assert_eq!(emails, ["user0@backup.test", "user1@backup.test"]);
    assert_eq!(version, backups::latest_migration());
    assert_eq!(audit.len(), 1);
    let details: Value = serde_json::from_str(&audit[0].1).unwrap();
    assert_eq!(details["backup"], PRE_MIGRATE);
    assert_eq!(details["pre_restore"], pre_restore);
    assert_eq!(details["media"], "none");
    assert_eq!(details["actor_email"], "admin@example.test");
    // Success cleans up: marker, staged copy and the aside originals.
    assert_eq!(leftovers(&env), Vec::<String>::new());
    let outcome = service.last_restore().unwrap();
    assert_eq!(outcome.status, "succeeded");
    assert_eq!(outcome.backup, PRE_MIGRATE);
    assert_ne!(service.data_epoch(), epoch_before);
    // The original data lives on in the pre-restore backup ("Undo last restore").
    assert!(env.backups.join(pre_restore).exists());
}

#[tokio::test]
async fn failed_migration_rolls_back_to_the_original_database() {
    let env = env();
    let live = live(&env).await;
    seed_backups(&env).await;
    // Readable and plausible, but its first migration was edited: the server
    // refuses to migrate it.
    let scratch = tempfile::tempdir().unwrap();
    let broken = scratch.path().join("broken.sqlite3");
    make_database(&broken, 1, 0, 0, 0, Some(34)).await;
    let pool = SqlitePoolOptions::new()
        .max_connections(1)
        .connect_with(SqliteConnectOptions::new().filename(&broken))
        .await
        .unwrap();
    sqlx::query("UPDATE _sqlx_migrations SET checksum = X'0102' WHERE version = 1")
        .execute(&pool)
        .await
        .unwrap();
    pool.close().await;
    let name = "predeploy-1.0.0-20260106-060000.sqlite3";
    fs::copy(&broken, env.backups.join(name)).unwrap();
    let epoch_before = live.service.data_epoch().to_owned();
    stage_restore(&env, &live, name, "none").await;
    live.pool.close().await;
    drop(live);

    let service = backups::prepare_startup(&env.db_url, &env.media)
        .await
        .unwrap()
        .unwrap();
    let (emails, _, audit) = read_all(&env.db_url).await;
    assert_eq!(emails, ["admin@example.test", "member@example.test"]);
    assert!(audit.is_empty());
    let outcome = service.last_restore().unwrap();
    assert_eq!(outcome.status, "failed");
    assert!(
        outcome
            .error
            .as_deref()
            .unwrap()
            .contains("could not be used")
    );
    assert_eq!(service.data_epoch(), epoch_before);
    assert_eq!(leftovers(&env), Vec::<String>::new());
}

#[tokio::test]
async fn a_damaged_staged_file_is_never_swapped_in() {
    let env = env();
    let live = live(&env).await;
    seed_backups(&env).await;
    stage_restore(&env, &live, PREDEPLOY, "none").await;
    live.pool.close().await;
    drop(live);
    let marker: Value =
        serde_json::from_slice(&fs::read(env.backups.join(".restore-request.json")).unwrap())
            .unwrap();
    let staged = env.backups.join(marker["staged"].as_str().unwrap());
    let mut bytes = fs::read(&staged).unwrap();
    let middle = bytes.len() / 2;
    bytes[middle] ^= 0xff;
    fs::write(&staged, bytes).unwrap();
    let service = backups::prepare_startup(&env.db_url, &env.media)
        .await
        .unwrap()
        .unwrap();
    let (emails, _, _) = read_all(&env.db_url).await;
    assert_eq!(emails, ["admin@example.test", "member@example.test"]);
    assert_eq!(service.last_restore().unwrap().status, "failed");
    assert_eq!(leftovers(&env), Vec::<String>::new());
}

#[tokio::test]
async fn a_stale_request_is_discarded() {
    let env = env();
    let live = live(&env).await;
    seed_backups(&env).await;
    stage_restore(&env, &live, PREDEPLOY, "none").await;
    live.pool.close().await;
    drop(live);
    let path = env.backups.join(".restore-request.json");
    let mut marker: Value = serde_json::from_slice(&fs::read(&path).unwrap()).unwrap();
    marker["requested_at"] = json!(1_700_000_000);
    fs::write(&path, marker.to_string()).unwrap();
    let service = backups::prepare_startup(&env.db_url, &env.media)
        .await
        .unwrap()
        .unwrap();
    let (emails, _, _) = read_all(&env.db_url).await;
    assert_eq!(emails, ["admin@example.test", "member@example.test"]);
    assert_eq!(service.last_restore().unwrap().status, "failed");
}

#[tokio::test]
async fn an_interrupted_swap_resumes_without_losing_the_original() {
    let env = env();
    let live = live(&env).await;
    seed_backups(&env).await;
    stage_restore(&env, &live, PREDEPLOY, "none").await;
    live.pool.close().await;
    drop(live);
    // Simulate a crash right after the live files moved aside: the database
    // and its sidecars are gone from their place, the marker says "swapping".
    let path = env.backups.join(".restore-request.json");
    let mut marker: Value = serde_json::from_slice(&fs::read(&path).unwrap()).unwrap();
    marker["phase"] = json!("swapping");
    fs::write(&path, marker.to_string()).unwrap();
    let aside =
        |suffix: &str| PathBuf::from(format!("{}{suffix}.restore-aside", env.db_path.display()));
    fs::rename(&env.db_path, aside("")).unwrap();
    // A stale WAL of the old database must not survive next to the new file.
    let wal = PathBuf::from(format!("{}-wal", env.db_path.display()));
    fs::write(&wal, b"stale wal").unwrap();
    let service = backups::prepare_startup(&env.db_url, &env.media)
        .await
        .unwrap()
        .unwrap();
    assert_eq!(service.last_restore().unwrap().status, "succeeded");
    let (emails, _, audit) = read_all(&env.db_url).await;
    assert_eq!(emails.len(), 3);
    assert_eq!(audit.len(), 1);
    assert!(!aside("").exists());
}

#[tokio::test]
async fn startup_without_a_request_changes_nothing() {
    let env = env();
    let live = live(&env).await;
    live.pool.close().await;
    let before = fs::read(&env.db_path).unwrap();
    let service = backups::prepare_startup(&env.db_url, &env.media)
        .await
        .unwrap()
        .unwrap();
    assert!(service.last_restore().is_none());
    assert_eq!(fs::read(&env.db_path).unwrap(), before);
    assert!(
        backups::prepare_startup("sqlite::memory:", &env.media)
            .await
            .unwrap()
            .is_none()
    );
}

#[tokio::test]
async fn media_add_missing_never_overwrites_or_deletes() {
    let env = env();
    let live = live(&env).await;
    let scratch = tempfile::tempdir().unwrap();
    let database = scratch.path().join("db.sqlite3");
    make_database(&database, 2, 0, 0, 0, None).await;
    make_archive(
        &env.backups.join(NIGHTLY),
        &database,
        Media::FilesAndEscape(&[
            ("a.jpg", b"from-archive"),
            ("b.jpg", b"archive-b"),
            ("sub/c.jpg", b"archive-c"),
        ]),
    );
    fs::write(env.media.join("a.jpg"), b"current-a").unwrap();
    fs::write(env.media.join("only-here.jpg"), b"keep me").unwrap();
    stage_restore(&env, &live, NIGHTLY, "add_missing").await;
    live.pool.close().await;
    drop(live);
    let service = backups::prepare_startup(&env.db_url, &env.media)
        .await
        .unwrap()
        .unwrap();
    let outcome = service.last_restore().unwrap();
    assert_eq!(outcome.status, "succeeded", "{outcome:?}");
    assert_eq!(outcome.media, "add_missing");
    assert_eq!(outcome.media_added, Some(2));
    assert_eq!(fs::read(env.media.join("a.jpg")).unwrap(), b"current-a");
    assert_eq!(fs::read(env.media.join("b.jpg")).unwrap(), b"archive-b");
    assert_eq!(fs::read(env.media.join("sub/c.jpg")).unwrap(), b"archive-c");
    assert_eq!(
        fs::read(env.media.join("only-here.jpg")).unwrap(),
        b"keep me"
    );
    // The escaping entry went nowhere.
    assert!(!env.data.join("escaped.txt").exists());
    assert!(!env.media.join("escaped.txt").exists());
    assert!(
        dir_names(&env.media)
            .iter()
            .all(|name| !name.contains("restore-part"))
    );
}

#[tokio::test]
async fn media_none_leaves_the_media_tree_alone() {
    let env = env();
    let live = live(&env).await;
    seed_backups(&env).await;
    fs::write(env.media.join("a.jpg"), b"current-a").unwrap();
    stage_restore(&env, &live, NIGHTLY, "none").await;
    live.pool.close().await;
    drop(live);
    backups::prepare_startup(&env.db_url, &env.media)
        .await
        .unwrap()
        .unwrap();
    assert_eq!(dir_names(&env.media), ["a.jpg"]);
    assert_eq!(fs::read(env.media.join("a.jpg")).unwrap(), b"current-a");
}
