use axum::body::Body;
use axum::http::{Request, StatusCode};
use http_body_util::BodyExt;
use sha2::{Digest, Sha256};
use sqlx::SqlitePool;
use std::io::Cursor;
use std::os::unix::fs::symlink;
use stricknani_server::{db, media};
use tempfile::TempDir;
use tower::ServiceExt;

const OWNER_TOKEN: &str = "media-owner-token";
const OTHER_TOKEN: &str = "media-other-token";
const REVOKED_TOKEN: &str = "media-revoked-token";

struct Harness {
    app: axum::Router,
    pool: SqlitePool,
    state: db::AppState,
    owner_id: i64,
    project_id: i64,
    other_project_id: i64,
    step_id: i64,
    other_step_id: i64,
    yarn_id: i64,
}

async fn harness(root: &TempDir) -> Harness {
    let pool = db::connect("sqlite::memory:")
        .await
        .expect("in-memory database should migrate");
    let owner_id = seed_user(&pool, "owner@example.test", OWNER_TOKEN).await;
    let other_id = seed_user(&pool, "other@example.test", OTHER_TOKEN).await;
    let project_id = insert_project(&pool, owner_id, "Owner project").await;
    let other_project_id = insert_project(&pool, other_id, "Other project").await;
    let step_id = insert_step(&pool, project_id).await;
    let other_step_id = insert_step(&pool, other_project_id).await;
    let yarn_id = insert_yarn(&pool, owner_id).await;
    let state = db::AppState::new(pool.clone()).with_media_root(root.path().to_path_buf());
    let app = media::router().with_state(state.clone());
    Harness {
        app,
        pool,
        state,
        owner_id,
        project_id,
        other_project_id,
        step_id,
        other_step_id,
        yarn_id,
    }
}

async fn seed_user(pool: &SqlitePool, email: &str, token: &str) -> i64 {
    let user_id =
        sqlx::query("INSERT INTO users (email, password_hash) VALUES (?, 'test-only-hash')")
            .bind(email)
            .execute(pool)
            .await
            .expect("test account should be inserted")
            .last_insert_rowid();
    insert_token(pool, user_id, token, false).await;
    user_id
}

async fn insert_token(pool: &SqlitePool, user_id: i64, token: &str, revoked: bool) {
    let token_hash = hex::encode(Sha256::digest(token.as_bytes()));
    sqlx::query(
        "INSERT INTO api_tokens (user_id, name, token_hash, created_at, revoked_at) \
         VALUES (?, 'test', ?, unixepoch(), ?)",
    )
    .bind(user_id)
    .bind(token_hash)
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
    .expect("project should be inserted")
    .last_insert_rowid()
}

async fn insert_step(pool: &SqlitePool, project_id: i64) -> i64 {
    sqlx::query("INSERT INTO project_steps (project_id, title, step_number) VALUES (?, 'Step', 1)")
        .bind(project_id)
        .execute(pool)
        .await
        .expect("step should be inserted")
        .last_insert_rowid()
}

async fn insert_yarn(pool: &SqlitePool, owner_id: i64) -> i64 {
    let now = "2020-01-01T00:00:00.000000Z";
    sqlx::query(
        "INSERT INTO yarns (owner_id, name, created_at, updated_at) VALUES (?, 'Wool', ?, ?)",
    )
    .bind(owner_id)
    .bind(now)
    .bind(now)
    .execute(pool)
    .await
    .expect("yarn should be inserted")
    .last_insert_rowid()
}

fn png_bytes() -> Vec<u8> {
    let image = image::DynamicImage::new_rgba8(2, 2);
    let mut output = Cursor::new(Vec::new());
    image
        .write_to(&mut output, image::ImageFormat::Png)
        .expect("test PNG should encode");
    output.into_inner()
}

fn multipart_request(
    method: &str,
    path: &str,
    token: &str,
    file_name: &str,
    claimed_type: &str,
    bytes: &[u8],
    alt_text: Option<&str>,
) -> Request<Body> {
    let boundary = "codex-media-boundary";
    let mut body = Vec::new();
    if let Some(alt_text) = alt_text {
        body.extend_from_slice(
            format!(
                "--{boundary}\r\nContent-Disposition: form-data; name=\"alt_text\"\r\n\r\n{alt_text}\r\n"
            )
            .as_bytes(),
        );
    }
    body.extend_from_slice(
        format!(
            "--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"{file_name}\"\r\nContent-Type: {claimed_type}\r\n\r\n"
        )
        .as_bytes(),
    );
    body.extend_from_slice(bytes);
    body.extend_from_slice(format!("\r\n--{boundary}--\r\n").as_bytes());
    Request::builder()
        .method(method)
        .uri(path)
        .header("authorization", format!("Bearer {token}"))
        .header(
            "content-type",
            format!("multipart/form-data; boundary={boundary}"),
        )
        .body(Body::from(body))
        .expect("multipart request is valid")
}

fn get_request(path: &str, token: &str) -> Request<Body> {
    Request::get(path)
        .header("authorization", format!("Bearer {token}"))
        .body(Body::empty())
        .expect("GET request is valid")
}

fn delete_request(path: &str, token: &str) -> Request<Body> {
    Request::delete(path)
        .header("authorization", format!("Bearer {token}"))
        .body(Body::empty())
        .expect("DELETE request is valid")
}

async fn json_body(response: axum::response::Response) -> serde_json::Value {
    let body = response
        .into_body()
        .collect()
        .await
        .expect("response body is readable")
        .to_bytes();
    serde_json::from_slice(&body).expect("response is JSON")
}

#[tokio::test]
async fn project_image_upload_serving_authorization_and_gc_are_safe() {
    let root = tempfile::tempdir().expect("temporary media root should be created");
    let harness = harness(&root).await;
    let png = png_bytes();

    let wrong_step = harness
        .app
        .clone()
        .oneshot(multipart_request(
            "POST",
            &format!(
                "/api/v1/projects/{}/steps/{}/images",
                harness.project_id, harness.other_step_id
            ),
            OWNER_TOKEN,
            "photo.png",
            "text/html",
            &png,
            Some("alt"),
        ))
        .await
        .expect("router should respond");
    assert_eq!(wrong_step.status(), StatusCode::NOT_FOUND);

    let invalid = harness
        .app
        .clone()
        .oneshot(multipart_request(
            "POST",
            &format!("/api/v1/projects/{}/images/title", harness.project_id),
            OWNER_TOKEN,
            "not-an-image.html",
            "text/html",
            b"<script>bad()</script>",
            Some("Bad image"),
        ))
        .await
        .expect("router should respond");
    assert_eq!(invalid.status(), StatusCode::BAD_REQUEST);

    let too_large = vec![0_u8; 25 * 1024 * 1024 + 1];
    let oversized = harness
        .app
        .clone()
        .oneshot(multipart_request(
            "POST",
            &format!("/api/v1/projects/{}/images/title", harness.project_id),
            OWNER_TOKEN,
            "large.png",
            "image/png",
            &too_large,
            None,
        ))
        .await
        .expect("router should respond");
    assert_eq!(oversized.status(), StatusCode::PAYLOAD_TOO_LARGE);

    let uploaded = harness
        .app
        .clone()
        .oneshot(multipart_request(
            "POST",
            &format!("/api/v1/projects/{}/images/title", harness.project_id),
            OWNER_TOKEN,
            "../portrait.png",
            "text/html",
            &png,
            Some("Portrait"),
        ))
        .await
        .expect("router should respond");
    assert_eq!(uploaded.status(), StatusCode::CREATED);
    let uploaded = json_body(uploaded).await;
    assert_eq!(uploaded["is_title_image"], true);
    assert_eq!(uploaded["image_type"], "photo");
    assert_eq!(uploaded["alt_text"], "Portrait");
    let url = uploaded["url"].as_str().unwrap();
    let filename = url.rsplit('/').next().unwrap();
    assert!(!filename.contains("portrait"));

    let second_gallery_upload = harness
        .app
        .clone()
        .oneshot(multipart_request(
            "POST",
            &format!("/api/v1/projects/{}/images/title", harness.project_id),
            OWNER_TOKEN,
            "second.png",
            "image/png",
            &png,
            Some("Second"),
        ))
        .await
        .expect("router should respond");
    assert_eq!(second_gallery_upload.status(), StatusCode::CREATED);
    let second_gallery_upload = json_body(second_gallery_upload).await;
    assert_eq!(second_gallery_upload["is_title_image"], false);
    let second_primary: i64 = sqlx::query_scalar(
        "SELECT is_primary FROM media_files WHERE public_id = ? AND project_id = ?",
    )
    .bind(second_gallery_upload["id"].as_i64().unwrap())
    .bind(harness.project_id)
    .fetch_one(&harness.pool)
    .await
    .expect("second gallery row should be present");
    assert_eq!(second_primary, 0);

    let served = harness
        .app
        .clone()
        .oneshot(get_request(url, OWNER_TOKEN))
        .await
        .expect("router should respond");
    assert_eq!(served.status(), StatusCode::OK);
    assert_eq!(served.headers()["content-type"], "image/png");
    assert_eq!(served.headers()["x-content-type-options"], "nosniff");
    let served_bytes = served.into_body().collect().await.unwrap().to_bytes();
    assert_eq!(served_bytes.as_ref(), png.as_slice());

    let thumbnail = harness
        .app
        .clone()
        .oneshot(get_request(
            uploaded["thumbnail_url"].as_str().unwrap(),
            OWNER_TOKEN,
        ))
        .await
        .expect("router should respond");
    assert_eq!(thumbnail.status(), StatusCode::OK);
    assert_eq!(thumbnail.headers()["content-type"], "image/jpeg");

    let other_access = harness
        .app
        .clone()
        .oneshot(get_request(url, OTHER_TOKEN))
        .await
        .expect("router should respond");
    assert_eq!(other_access.status(), StatusCode::FORBIDDEN);
    let wrong_parent_delete = harness
        .app
        .clone()
        .oneshot(delete_request(
            &format!(
                "/api/v1/projects/{}/images/{}",
                harness.other_project_id, uploaded["id"]
            ),
            OTHER_TOKEN,
        ))
        .await
        .expect("router should respond");
    assert_eq!(wrong_parent_delete.status(), StatusCode::NOT_FOUND);

    insert_token(&harness.pool, harness.owner_id, REVOKED_TOKEN, true).await;
    let revoked = harness
        .app
        .clone()
        .oneshot(get_request(url, REVOKED_TOKEN))
        .await
        .expect("router should respond");
    assert_eq!(revoked.status(), StatusCode::UNAUTHORIZED);

    let step_upload = harness
        .app
        .clone()
        .oneshot(multipart_request(
            "POST",
            &format!(
                "/api/v1/projects/{}/steps/{}/images",
                harness.project_id, harness.step_id
            ),
            OWNER_TOKEN,
            "step.png",
            "image/png",
            &png,
            Some("Step image"),
        ))
        .await
        .expect("router should respond");
    assert_eq!(step_upload.status(), StatusCode::CREATED);
    assert_eq!(json_body(step_upload).await["step_id"], harness.step_id);

    let filename_on_disk = root
        .path()
        .join("projects")
        .join(harness.project_id.to_string())
        .join(filename);
    let external_root = tempfile::tempdir().expect("external test root should be created");
    let external_file = external_root.path().join("secret.txt");
    tokio::fs::write(&external_file, b"outside media root")
        .await
        .expect("external test file should be written");
    tokio::fs::remove_file(&filename_on_disk)
        .await
        .expect("uploaded file should be replaceable in the test");
    symlink(&external_file, &filename_on_disk).expect("media path symlink should be created");
    let symlink_response = harness
        .app
        .clone()
        .oneshot(get_request(url, OWNER_TOKEN))
        .await
        .expect("router should respond");
    assert_eq!(symlink_response.status(), StatusCode::NOT_FOUND);

    let deleted = harness
        .app
        .clone()
        .oneshot(delete_request(
            &format!(
                "/api/v1/projects/{}/images/{}",
                harness.project_id, uploaded["id"]
            ),
            OWNER_TOKEN,
        ))
        .await
        .expect("router should respond");
    assert_eq!(deleted.status(), StatusCode::NO_CONTENT);
    let media_audit_actions: Vec<String> = sqlx::query_scalar(
        "SELECT action FROM audit_events WHERE actor_user_id = ? AND entity_type = 'project' \
         AND entity_id = ? ORDER BY id",
    )
    .bind(harness.owner_id)
    .bind(harness.project_id)
    .fetch_all(&harness.pool)
    .await
    .expect("media audit events should be queryable");
    assert_eq!(
        media_audit_actions,
        vec!["uploaded", "uploaded", "uploaded", "trashed"]
            .into_iter()
            .map(str::to_owned)
            .collect::<Vec<_>>()
    );
    assert!(
        filename_on_disk.exists(),
        "GC must leave an escaping symlink alone"
    );
    tokio::fs::remove_file(&filename_on_disk)
        .await
        .expect("test symlink should be removed");
    media::cleanup_pending(&harness.state)
        .await
        .expect("media cleanup should retry queued files");
    let queued_gc: i64 = sqlx::query_scalar("SELECT count(*) FROM media_gc")
        .fetch_one(&harness.pool)
        .await
        .expect("media gc queue should be readable");
    assert_eq!(queued_gc, 0);
}

#[tokio::test]
async fn attachments_download_safely_and_yarn_photos_promote_primary() {
    let root = tempfile::tempdir().expect("temporary media root should be created");
    let harness = harness(&root).await;
    let attachment_bytes = b"<html><script>alert(1)</script></html>";
    let attachment = harness
        .app
        .clone()
        .oneshot(multipart_request(
            "POST",
            &format!("/api/v1/projects/{}/attachments", harness.project_id),
            OWNER_TOKEN,
            "../active.html",
            "text/html",
            attachment_bytes,
            None,
        ))
        .await
        .expect("router should respond");
    assert_eq!(attachment.status(), StatusCode::CREATED);
    let attachment = json_body(attachment).await;
    assert_eq!(attachment["content_type"], "text/html");
    assert_eq!(attachment["original_filename"], "active.html");
    assert!(!attachment["filename"].as_str().unwrap().contains("active"));

    let downloaded = harness
        .app
        .clone()
        .oneshot(get_request(
            attachment["url"].as_str().unwrap(),
            OWNER_TOKEN,
        ))
        .await
        .expect("router should respond");
    assert_eq!(downloaded.status(), StatusCode::OK);
    assert_eq!(downloaded.headers()["content-type"], "text/html");
    assert!(
        downloaded.headers()["content-disposition"]
            .to_str()
            .unwrap()
            .starts_with("attachment;")
    );
    assert_eq!(downloaded.headers()["x-content-type-options"], "nosniff");
    assert_eq!(
        downloaded
            .into_body()
            .collect()
            .await
            .unwrap()
            .to_bytes()
            .as_ref(),
        attachment_bytes
    );

    let denied = harness
        .app
        .clone()
        .oneshot(get_request(
            attachment["url"].as_str().unwrap(),
            OTHER_TOKEN,
        ))
        .await
        .expect("router should respond");
    assert_eq!(denied.status(), StatusCode::FORBIDDEN);
    let attachment_filename = attachment["filename"].as_str().unwrap();
    let deleted_attachment = harness
        .app
        .clone()
        .oneshot(delete_request(
            &format!(
                "/api/v1/projects/{}/attachments/{}",
                harness.project_id, attachment["id"]
            ),
            OWNER_TOKEN,
        ))
        .await
        .expect("router should respond");
    assert_eq!(deleted_attachment.status(), StatusCode::NO_CONTENT);
    let attachment_audit: Vec<String> = sqlx::query_scalar(
        "SELECT action FROM audit_events WHERE actor_user_id = ? AND entity_type = 'project' \
         AND entity_id = ? ORDER BY id",
    )
    .bind(harness.owner_id)
    .bind(harness.project_id)
    .fetch_all(&harness.pool)
    .await
    .expect("attachment audit events should be queryable");
    assert_eq!(
        attachment_audit,
        vec!["uploaded", "trashed"]
            .into_iter()
            .map(str::to_owned)
            .collect::<Vec<_>>()
    );
    assert!(
        !root
            .path()
            .join("projects")
            .join(harness.project_id.to_string())
            .join(attachment_filename)
            .exists()
    );

    let png = png_bytes();
    let first = harness
        .app
        .clone()
        .oneshot(multipart_request(
            "POST",
            &format!("/api/v1/yarns/{}/photos", harness.yarn_id),
            OWNER_TOKEN,
            "first.png",
            "application/octet-stream",
            &png,
            None,
        ))
        .await
        .expect("router should respond");
    assert_eq!(first.status(), StatusCode::CREATED);
    let first = json_body(first).await;
    assert_eq!(first["is_primary"], true);
    let second = harness
        .app
        .clone()
        .oneshot(multipart_request(
            "POST",
            &format!("/api/v1/yarns/{}/photos", harness.yarn_id),
            OWNER_TOKEN,
            "second.png",
            "application/octet-stream",
            &png,
            None,
        ))
        .await
        .expect("router should respond");
    assert_eq!(second.status(), StatusCode::CREATED);
    let second = json_body(second).await;
    assert_eq!(second["is_primary"], false);
    let yarn_upload_events: i64 = sqlx::query_scalar(
        "SELECT count(*) FROM audit_events WHERE actor_user_id = ? AND entity_type = 'yarn' \
         AND entity_id = ? AND action = 'uploaded'",
    )
    .bind(harness.owner_id)
    .bind(harness.yarn_id)
    .fetch_one(&harness.pool)
    .await
    .expect("yarn upload events should be queryable");
    assert_eq!(yarn_upload_events, 2);

    let second_delete = harness
        .app
        .clone()
        .oneshot(delete_request(
            &format!("/api/v1/yarns/{}/photos/{}", harness.yarn_id, first["id"]),
            OWNER_TOKEN,
        ))
        .await
        .expect("router should respond");
    assert_eq!(second_delete.status(), StatusCode::NO_CONTENT);
    let yarn_remove_events: i64 = sqlx::query_scalar(
        "SELECT count(*) FROM audit_events WHERE actor_user_id = ? AND entity_type = 'yarn' \
         AND entity_id = ? AND action = 'trashed'",
    )
    .bind(harness.owner_id)
    .bind(harness.yarn_id)
    .fetch_one(&harness.pool)
    .await
    .expect("yarn remove event should be queryable");
    assert_eq!(yarn_remove_events, 1);
    let promoted: i64 = sqlx::query_scalar(
        "SELECT is_primary FROM media_files WHERE kind = 'photo' AND public_id = ? AND yarn_id = ?",
    )
    .bind(second["id"].as_i64().unwrap())
    .bind(harness.yarn_id)
    .fetch_one(&harness.pool)
    .await
    .expect("remaining photo should be promoted");
    assert_eq!(promoted, 1);
}

#[tokio::test]
async fn missing_media_storage_returns_service_unavailable() {
    let pool = db::connect("sqlite::memory:")
        .await
        .expect("in-memory database should migrate");
    let owner_id = seed_user(&pool, "owner@example.test", OWNER_TOKEN).await;
    let project_id = insert_project(&pool, owner_id, "Project").await;
    let app = media::router().with_state(db::AppState::new(pool));
    let response = app
        .oneshot(multipart_request(
            "POST",
            &format!("/api/v1/projects/{project_id}/images/title"),
            OWNER_TOKEN,
            "image.png",
            "image/png",
            &png_bytes(),
            None,
        ))
        .await
        .expect("router should respond");
    assert_eq!(response.status(), StatusCode::SERVICE_UNAVAILABLE);
}

#[tokio::test]
async fn imported_legacy_title_flag_keeps_later_gallery_upload_secondary() {
    let root = tempfile::tempdir().expect("temporary media root should be created");
    let harness = harness(&root).await;
    sqlx::query(
        "INSERT INTO media_files \
         (owner_id, project_id, kind, public_id, filename, original_filename, content_type, \
          size_bytes, is_primary, legacy_is_title_image) \
         VALUES (?, ?, 'title', 90001, 'legacy-title.jpg', 'legacy-title.jpg', \
                 'image/jpeg', 20, 0, 1)",
    )
    .bind(harness.owner_id)
    .bind(harness.project_id)
    .execute(&harness.pool)
    .await
    .expect("legacy title media should be seeded");

    let response = harness
        .app
        .oneshot(multipart_request(
            "POST",
            &format!("/api/v1/projects/{}/images/title", harness.project_id),
            OWNER_TOKEN,
            "later.png",
            "image/png",
            &png_bytes(),
            None,
        ))
        .await
        .expect("router should respond");
    assert_eq!(response.status(), StatusCode::CREATED);
    let uploaded = json_body(response).await;
    assert_eq!(uploaded["is_title_image"], false);
}
