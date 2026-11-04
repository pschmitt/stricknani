use axum::body::Body;
use axum::http::{Request, StatusCode};
use http_body_util::BodyExt;
use sha2::{Digest, Sha256};
use sqlx::SqlitePool;
use std::path::Path;
use stricknani_server::{db, media};
use tempfile::TempDir;
use tower::ServiceExt;

const OWNER_TOKEN: &str = "media-alias-owner-token";
const OTHER_TOKEN: &str = "media-alias-other-token";
const REVOKED_TOKEN: &str = "media-alias-revoked-token";

struct Fixture {
    app: axum::Router,
    pool: SqlitePool,
    owner_project_id: i64,
    other_project_id: i64,
    owner_media_id: i64,
    owner_legacy_name: String,
    owner_legacy_thumbnail: String,
}

async fn fixture(root: &TempDir) -> Fixture {
    let pool = db::connect("sqlite::memory:")
        .await
        .expect("database migrations should run");
    let owner_id = seed_user(&pool, "media-alias-owner@example.test", OWNER_TOKEN).await;
    let other_id = seed_user(&pool, "media-alias-other@example.test", OTHER_TOKEN).await;
    let owner_project_id = insert_project(&pool, owner_id, "Owner project").await;
    let other_project_id = insert_project(&pool, other_id, "Other project").await;
    insert_token(&pool, owner_id, REVOKED_TOKEN, true).await;

    let owner_legacy_name = "same name café.png".to_owned();
    let owner_legacy_thumbnail = "thumb_same name café.jpg".to_owned();
    let other_media_id = insert_media(
        &pool,
        root.path(),
        other_id,
        other_project_id,
        "physical-other.png",
        b"other source bytes",
        b"other thumbnail bytes",
        &owner_legacy_name,
        &owner_legacy_thumbnail,
        2,
    )
    .await;
    let owner_media_id = insert_media(
        &pool,
        root.path(),
        owner_id,
        owner_project_id,
        "physical-owner.png",
        b"owner source bytes",
        b"owner thumbnail bytes",
        &owner_legacy_name,
        &owner_legacy_thumbnail,
        1,
    )
    .await;
    assert_ne!(owner_media_id, other_media_id);

    let state = db::AppState::new(pool.clone()).with_media_root(root.path().to_path_buf());
    Fixture {
        app: media::router().with_state(state),
        pool,
        owner_project_id,
        other_project_id,
        owner_media_id,
        owner_legacy_name,
        owner_legacy_thumbnail,
    }
}

async fn seed_user(pool: &SqlitePool, email: &str, token: &str) -> i64 {
    let user_id =
        sqlx::query("INSERT INTO users (email, password_hash) VALUES (?, 'test-only-hash')")
            .bind(email)
            .execute(pool)
            .await
            .expect("test user should be inserted")
            .last_insert_rowid();
    insert_token(pool, user_id, token, false).await;
    user_id
}

async fn insert_token(pool: &SqlitePool, user_id: i64, token: &str, revoked: bool) {
    let hash = hex::encode(Sha256::digest(token.as_bytes()));
    sqlx::query(
        "INSERT INTO api_tokens (user_id, name, token_hash, created_at, revoked_at) \
         VALUES (?, 'test', ?, unixepoch(), ?)",
    )
    .bind(user_id)
    .bind(hash)
    .bind(revoked.then_some(1_i64))
    .execute(pool)
    .await
    .expect("test token should be inserted");
}

async fn insert_project(pool: &SqlitePool, owner_id: i64, name: &str) -> i64 {
    let now = "2020-01-01T00:00:00.000000Z";
    sqlx::query(
        "INSERT INTO projects (owner_id, name, tags, created_at, updated_at) \
         VALUES (?, ?, '[]', ?, ?)",
    )
    .bind(owner_id)
    .bind(name)
    .bind(now)
    .bind(now)
    .execute(pool)
    .await
    .expect("test project should be inserted")
    .last_insert_rowid()
}

#[allow(clippy::too_many_arguments)]
async fn insert_media(
    pool: &SqlitePool,
    root: &Path,
    owner_id: i64,
    project_id: i64,
    filename: &str,
    source: &[u8],
    thumbnail: &[u8],
    legacy_name: &str,
    legacy_thumbnail: &str,
    public_id: i64,
) -> i64 {
    let parent_dir = root.join("projects").join(project_id.to_string());
    let thumbnail_dir = root
        .join("thumbnails")
        .join("projects")
        .join(project_id.to_string());
    tokio::fs::create_dir_all(&thumbnail_dir)
        .await
        .expect("media directories should be created");
    tokio::fs::create_dir_all(&parent_dir)
        .await
        .expect("source directory should be created");
    tokio::fs::write(parent_dir.join(filename), source)
        .await
        .expect("source bytes should be written");
    let stem = filename.strip_suffix(".png").expect("physical PNG name");
    tokio::fs::write(thumbnail_dir.join(format!("thumb_{stem}.jpg")), thumbnail)
        .await
        .expect("thumbnail bytes should be written");

    let media_id = sqlx::query(
        "INSERT INTO media_files \
         (owner_id, project_id, kind, filename, original_filename, content_type, size_bytes, \
          width, height, alt_text, is_primary, is_safe_inline, public_id, legacy_image_type, \
          is_stitch_sample) \
         VALUES (?, ?, 'title', ?, ?, 'image/png', ?, 1, 1, '', 1, 1, ?, 'photo', 0)",
    )
    .bind(owner_id)
    .bind(project_id)
    .bind(filename)
    .bind(filename)
    .bind(source.len() as i64)
    .bind(public_id)
    .execute(pool)
    .await
    .expect("media row should be inserted")
    .last_insert_rowid();
    sqlx::query(
        "INSERT INTO media_url_aliases \
         (subdir, parent_id, legacy_filename, legacy_thumbnail, media_id) \
         VALUES ('projects', ?, ?, ?, ?)",
    )
    .bind(project_id)
    .bind(legacy_name)
    .bind(legacy_thumbnail)
    .bind(media_id)
    .execute(pool)
    .await
    .expect("legacy URL alias should be inserted");
    media_id
}

fn get_request(path: &str, token: Option<&str>) -> Request<Body> {
    let mut request = Request::get(path);
    if let Some(token) = token {
        request = request.header("authorization", format!("Bearer {token}"));
    }
    request
        .body(Body::empty())
        .expect("GET request should be valid")
}

async fn response_bytes(response: axum::response::Response) -> (StatusCode, Vec<u8>) {
    let status = response.status();
    let body = response
        .into_body()
        .collect()
        .await
        .expect("response body should be readable")
        .to_bytes()
        .to_vec();
    (status, body)
}

#[tokio::test]
async fn legacy_source_and_thumbnail_aliases_resolve_with_owner_scope() {
    let root = tempfile::tempdir().expect("media root should be created");
    let fixture = fixture(&root).await;
    let source_url = format!(
        "/media/projects/{}/{}",
        fixture.owner_project_id,
        fixture
            .owner_legacy_name
            .replace(' ', "%20")
            .replace('é', "%C3%A9")
    );
    let thumbnail_url = format!(
        "/media/thumbnails/projects/{}/{}",
        fixture.owner_project_id,
        fixture
            .owner_legacy_thumbnail
            .replace(' ', "%20")
            .replace('é', "%C3%A9")
    );

    let source = fixture
        .app
        .clone()
        .oneshot(get_request(&source_url, Some(OWNER_TOKEN)))
        .await
        .expect("source request should complete");
    assert_eq!(
        response_bytes(source).await,
        (StatusCode::OK, b"owner source bytes".to_vec())
    );

    let thumbnail = fixture
        .app
        .clone()
        .oneshot(get_request(&thumbnail_url, Some(OWNER_TOKEN)))
        .await
        .expect("thumbnail request should complete");
    assert_eq!(
        response_bytes(thumbnail).await,
        (StatusCode::OK, b"owner thumbnail bytes".to_vec())
    );

    let cross_owner = format!(
        "/media/projects/{}/{}",
        fixture.other_project_id,
        fixture
            .owner_legacy_name
            .replace(' ', "%20")
            .replace('é', "%C3%A9")
    );
    let denied = fixture
        .app
        .clone()
        .oneshot(get_request(&cross_owner, Some(OWNER_TOKEN)))
        .await
        .expect("cross-owner request should complete");
    assert_eq!(denied.status(), StatusCode::FORBIDDEN);

    let other_cannot_read_owner = fixture
        .app
        .clone()
        .oneshot(get_request(&source_url, Some(OTHER_TOKEN)))
        .await
        .expect("other owner request should complete");
    assert_eq!(other_cannot_read_owner.status(), StatusCode::FORBIDDEN);
}

#[tokio::test]
async fn alias_urls_require_live_auth_and_aliases_cascade_with_media() {
    let root = tempfile::tempdir().expect("media root should be created");
    let fixture = fixture(&root).await;
    let source_url = format!(
        "/media/projects/{}/{}",
        fixture.owner_project_id,
        fixture
            .owner_legacy_name
            .replace(' ', "%20")
            .replace('é', "%C3%A9")
    );

    let unauthenticated = fixture
        .app
        .clone()
        .oneshot(get_request(&source_url, None))
        .await
        .expect("unauthenticated request should complete");
    assert_eq!(unauthenticated.status(), StatusCode::UNAUTHORIZED);
    let revoked = fixture
        .app
        .clone()
        .oneshot(get_request(&source_url, Some(REVOKED_TOKEN)))
        .await
        .expect("revoked-token request should complete");
    assert_eq!(revoked.status(), StatusCode::UNAUTHORIZED);

    sqlx::query("DELETE FROM media_files WHERE id = ?")
        .bind(fixture.owner_media_id)
        .execute(&fixture.pool)
        .await
        .expect("media row should be deletable");
    let aliases: i64 =
        sqlx::query_scalar("SELECT COUNT(*) FROM media_url_aliases WHERE media_id = ?")
            .bind(fixture.owner_media_id)
            .fetch_one(&fixture.pool)
            .await
            .expect("alias count should be queryable");
    assert_eq!(aliases, 0);

    let gone = fixture
        .app
        .oneshot(get_request(&source_url, Some(OWNER_TOKEN)))
        .await
        .expect("deleted alias request should complete");
    assert_eq!(gone.status(), StatusCode::NOT_FOUND);
}
