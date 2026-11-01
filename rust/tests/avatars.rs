use axum::body::Body;
use axum::http::{Request, StatusCode};
use http_body_util::BodyExt;
use sha2::{Digest, Sha256};
use sqlx::SqlitePool;
use std::path::PathBuf;
use stricknani_server::{db, media, projects};
use tempfile::TempDir;
use tower::ServiceExt;

const OWNER_TOKEN: &str = "avatar-owner-token";
const OTHER_TOKEN: &str = "avatar-other-token";

struct Harness {
    app: axum::Router,
    state: db::AppState,
    pool: SqlitePool,
    owner_id: i64,
    project_id: i64,
}

async fn harness(root: &TempDir) -> Harness {
    let pool = db::connect("sqlite::memory:")
        .await
        .expect("database should migrate");
    let owner_id = seed_user(&pool, "avatar-owner@example.test", OWNER_TOKEN).await;
    let _other_id = seed_user(&pool, "avatar-other@example.test", OTHER_TOKEN).await;
    let project_id = insert_project(&pool, owner_id).await;
    let state = db::AppState::new(pool.clone()).with_media_root(root.path().to_path_buf());
    Harness {
        app: projects::router()
            .merge(media::router())
            .with_state(state.clone()),
        state,
        pool,
        owner_id,
        project_id,
    }
}

async fn seed_user(pool: &SqlitePool, email: &str, token: &str) -> i64 {
    let user_id =
        sqlx::query("INSERT INTO users (email, password_hash) VALUES (?, 'test-only-hash')")
            .bind(email)
            .execute(pool)
            .await
            .expect("user should be inserted")
            .last_insert_rowid();
    let hash = hex::encode(Sha256::digest(token.as_bytes()));
    sqlx::query(
        "INSERT INTO api_tokens (user_id, name, token_hash, created_at) \
         VALUES (?, 'test', ?, unixepoch())",
    )
    .bind(user_id)
    .bind(hash)
    .execute(pool)
    .await
    .expect("token should be inserted");
    user_id
}

async fn insert_project(pool: &SqlitePool, owner_id: i64) -> i64 {
    let now = "2020-01-01T00:00:00.000000Z";
    sqlx::query(
        "INSERT INTO projects (owner_id, name, tags, created_at, updated_at) \
         VALUES (?, 'Metadata project', '[]', ?, ?)",
    )
    .bind(owner_id)
    .bind(now)
    .bind(now)
    .execute(pool)
    .await
    .expect("project should be inserted")
    .last_insert_rowid()
}

async fn insert_avatar(pool: &SqlitePool, user_id: i64, filename: &str, content_type: &str) {
    sqlx::query(
        "INSERT INTO user_avatars \
         (user_id, filename, original_filename, content_type, width, height, created_at) \
         VALUES (?, ?, ?, ?, 1, 1, '2020-01-01T00:00:00.000000Z')",
    )
    .bind(user_id)
    .bind(filename)
    .bind(filename)
    .bind(content_type)
    .execute(pool)
    .await
    .expect("avatar registry row should be inserted");
}

fn request(path: &str, token: Option<&str>) -> Request<Body> {
    let mut builder = Request::get(path);
    if let Some(token) = token {
        builder = builder.header("authorization", format!("Bearer {token}"));
    }
    builder
        .body(Body::empty())
        .expect("request should be valid")
}

async fn body_bytes(response: axum::response::Response) -> Vec<u8> {
    response
        .into_body()
        .collect()
        .await
        .expect("response body should be readable")
        .to_bytes()
        .to_vec()
}

fn avatar_paths(root: &TempDir, user_id: i64, filename: &str) -> (PathBuf, PathBuf) {
    let source = root.path().join("users").join(user_id.to_string()).join(filename);
    let thumbnail = root.path()
        .join("thumbnails")
        .join("users")
        .join(user_id.to_string())
        .join("thumb_old portrait.jpg");
    (source, thumbnail)
}

async fn write_avatar_files(root: &TempDir, user_id: i64, filename: &str) {
    let (source, thumbnail) = avatar_paths(root, user_id, filename);
    tokio::fs::create_dir_all(source.parent().unwrap())
        .await
        .expect("avatar directory should be created");
    tokio::fs::create_dir_all(thumbnail.parent().unwrap())
        .await
        .expect("thumbnail directory should be created");
    tokio::fs::write(&source, b"<svg><script>unsafe()</script></svg>")
        .await
        .expect("avatar should be written");
    tokio::fs::write(&thumbnail, b"thumbnail bytes")
        .await
        .expect("thumbnail should be written");
}

#[tokio::test]
async fn own_avatar_and_thumbnail_are_protected_and_svg_downloads() {
    let root = tempfile::tempdir().expect("media root should be created");
    let harness = harness(&root).await;
    let filename = "old portrait.svg";
    insert_avatar(&harness.pool, harness.owner_id, filename, "image/svg+xml").await;
    write_avatar_files(&root, harness.owner_id, filename).await;
    let source_path = format!("/media/users/{}/old%20portrait.svg", harness.owner_id);
    let thumb_path = format!(
        "/media/thumbnails/users/{}/thumb_old%20portrait.jpg",
        harness.owner_id
    );

    let source = harness
        .app
        .clone()
        .oneshot(request(&source_path, Some(OWNER_TOKEN)))
        .await
        .expect("avatar request should complete");
    assert_eq!(source.status(), StatusCode::OK);
    assert_eq!(source.headers()["content-type"], "image/svg+xml");
    assert!(
        source.headers()["content-disposition"]
            .to_str()
            .unwrap()
            .starts_with("attachment;")
    );
    assert_eq!(
        body_bytes(source).await,
        b"<svg><script>unsafe()</script></svg>"
    );

    let thumbnail = harness
        .app
        .clone()
        .oneshot(request(&thumb_path, Some(OWNER_TOKEN)))
        .await
        .expect("avatar thumbnail request should complete");
    assert_eq!(thumbnail.status(), StatusCode::OK);
    assert_eq!(thumbnail.headers()["content-type"], "image/jpeg");
    assert_eq!(body_bytes(thumbnail).await, b"thumbnail bytes");

    let unauthenticated = harness
        .app
        .clone()
        .oneshot(request(&source_path, None))
        .await
        .expect("unauthenticated request should complete");
    assert_eq!(unauthenticated.status(), StatusCode::UNAUTHORIZED);
    let foreign = harness
        .app
        .clone()
        .oneshot(request(&source_path, Some(OTHER_TOKEN)))
        .await
        .expect("foreign request should complete");
    assert_eq!(foreign.status(), StatusCode::FORBIDDEN);

    let invalid = harness
        .app
        .clone()
        .oneshot(request(
            &format!("/media/users/{}/%2e%2e%2Fsecret.svg", harness.owner_id),
            Some(OWNER_TOKEN),
        ))
        .await
        .expect("invalid path request should complete");
    assert!(!invalid.status().is_success());
}

#[tokio::test]
async fn deleting_user_queues_and_cleans_avatar_files() {
    let root = tempfile::tempdir().expect("media root should be created");
    let harness = harness(&root).await;
    let filename = "old portrait.svg";
    insert_avatar(&harness.pool, harness.owner_id, filename, "image/svg+xml").await;
    write_avatar_files(&root, harness.owner_id, filename).await;
    let (source, thumbnail) = avatar_paths(&root, harness.owner_id, filename);

    sqlx::query("DELETE FROM users WHERE id = ?")
        .bind(harness.owner_id)
        .execute(&harness.pool)
        .await
        .expect("user should be deleted");
    let queued: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM avatar_gc WHERE user_id = ?")
        .bind(harness.owner_id)
        .fetch_one(&harness.pool)
        .await
        .expect("avatar GC queue should be queryable");
    assert_eq!(queued, 1);

    media::cleanup_pending(&harness.state)
        .await
        .expect("avatar cleanup should run");
    assert!(!source.exists());
    assert!(!thumbnail.exists());
    let remaining: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM avatar_gc")
        .fetch_one(&harness.pool)
        .await
        .expect("avatar GC queue should be queryable");
    assert_eq!(remaining, 0);
}

#[tokio::test]
async fn imported_false_title_flag_overrides_kind_fallback() {
    let root = tempfile::tempdir().expect("media root should be created");
    let harness = harness(&root).await;
    sqlx::query(
        "INSERT INTO media_files \
         (owner_id, project_id, kind, public_id, filename, original_filename, content_type, \
          size_bytes, legacy_is_title_image) \
         VALUES (?, ?, 'title', 1, 'imported.webp', 'imported.webp', 'image/webp', 10, 0)",
    )
    .bind(harness.owner_id)
    .bind(harness.project_id)
    .execute(&harness.pool)
    .await
    .expect("imported media should be inserted");

    let response = harness
        .app
        .clone()
        .oneshot(request(
            &format!("/projects/{}", harness.project_id),
            Some(OWNER_TOKEN),
        ))
        .await
        .expect("project request should complete");
    assert_eq!(response.status(), StatusCode::OK);
    let body = body_bytes(response).await;
    let project: serde_json::Value =
        serde_json::from_slice(&body).expect("project response should be JSON");
    assert_eq!(project["images"][0]["is_title_image"], false);
}
