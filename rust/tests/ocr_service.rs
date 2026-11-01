use std::{
    future::Future,
    io::Cursor,
    pin::Pin,
    sync::{Arc, Mutex},
};

use axum::{body::Body, http::Request};
use http_body_util::BodyExt;
use serde_json::{Value, json};
use sha2::{Digest, Sha256};
use sqlx::SqlitePool;
use stricknani_server::{db, media, ocr::OcrService, ocr_service};
use tower::ServiceExt;

const OWNER_TOKEN: &str = "ocr-owner-api-token";
const OTHER_TOKEN: &str = "ocr-other-api-token";
const BROWSER_TOKEN: &str = "ocr-owner-browser-token";

type OcrFuture<'a> =
    Pin<Box<dyn Future<Output = Result<String, stricknani_server::ocr::OcrError>> + Send + 'a>>;

#[derive(Default)]
struct FixtureTransport {
    languages: Mutex<Vec<String>>,
}

impl stricknani_server::ocr::OcrTransport for FixtureTransport {
    fn recognize<'a>(
        &'a self,
        _image_bytes: Vec<u8>,
        language: stricknani_server::ocr::OcrLanguage,
    ) -> OcrFuture<'a> {
        self.languages
            .lock()
            .expect("fixture lock")
            .push(language.as_str().to_owned());
        Box::pin(async { Ok("recognized text".to_owned()) })
    }
}

struct Harness {
    app: axum::Router,
    pool: SqlitePool,
    media_root: tempfile::TempDir,
    owner_project_id: i64,
    other_project_id: i64,
    owner_yarn_id: i64,
}

async fn harness(service: Option<OcrService>) -> Harness {
    let pool = db::connect("sqlite::memory:")
        .await
        .expect("test database migrates");
    let owner_id = insert_user(&pool, "ocr-owner@example.test", OWNER_TOKEN, "api").await;
    insert_token(&pool, owner_id, BROWSER_TOKEN, "browser").await;
    let other_id = insert_user(&pool, "ocr-other@example.test", OTHER_TOKEN, "api").await;
    let owner_project_id = insert_project(&pool, owner_id, "Owner project").await;
    let other_project_id = insert_project(&pool, other_id, "Other project").await;
    let owner_yarn_id = insert_yarn(&pool, owner_id).await;
    let media_root = tempfile::tempdir().expect("temporary media root");
    add_image_file(
        &pool,
        media_root.path(),
        owner_id,
        "projects",
        owner_project_id,
    )
    .await;
    add_image_file(&pool, media_root.path(), owner_id, "yarns", owner_yarn_id).await;
    add_image_file(
        &pool,
        media_root.path(),
        other_id,
        "projects",
        other_project_id,
    )
    .await;
    let state = db::AppState::new(pool.clone()).with_media_root(media_root.path().to_path_buf());
    let media_api = media::router().with_state(state.clone());
    let app = ocr_service::router(media_api, state, service);

    Harness {
        app,
        pool,
        media_root,
        owner_project_id,
        other_project_id,
        owner_yarn_id,
    }
}

async fn insert_user(pool: &SqlitePool, email: &str, token: &str, purpose: &str) -> i64 {
    let user_id =
        sqlx::query("INSERT INTO users (email, password_hash) VALUES (?, 'fixture-hash')")
            .bind(email)
            .execute(pool)
            .await
            .expect("fixture user inserted")
            .last_insert_rowid();
    insert_token(pool, user_id, token, purpose).await;
    user_id
}

async fn insert_token(pool: &SqlitePool, user_id: i64, token: &str, purpose: &str) {
    sqlx::query(
        "INSERT INTO api_tokens (user_id, name, token_hash, created_at, purpose) \
         VALUES (?, 'fixture', ?, unixepoch(), ?)",
    )
    .bind(user_id)
    .bind(hex::encode(Sha256::digest(token.as_bytes())))
    .bind(purpose)
    .execute(pool)
    .await
    .expect("fixture token inserted");
}

async fn insert_project(pool: &SqlitePool, owner_id: i64, name: &str) -> i64 {
    sqlx::query(
        "INSERT INTO projects (owner_id, name, tags, created_at, updated_at) \
         VALUES (?, ?, '[]', '2020-01-01T00:00:00.000000Z', '2020-01-01T00:00:00.000000Z')",
    )
    .bind(owner_id)
    .bind(name)
    .execute(pool)
    .await
    .expect("fixture project inserted")
    .last_insert_rowid()
}

async fn insert_yarn(pool: &SqlitePool, owner_id: i64) -> i64 {
    sqlx::query(
        "INSERT INTO yarns (owner_id, name, created_at, updated_at) \
         VALUES (?, 'Fixture yarn', '2020-01-01T00:00:00.000000Z', '2020-01-01T00:00:00.000000Z')",
    )
    .bind(owner_id)
    .execute(pool)
    .await
    .expect("fixture yarn inserted")
    .last_insert_rowid()
}

fn png_bytes() -> Vec<u8> {
    let image = image::DynamicImage::new_rgba8(2, 2);
    let mut output = Cursor::new(Vec::new());
    image
        .write_to(&mut output, image::ImageFormat::Png)
        .expect("fixture PNG encodes");
    output.into_inner()
}

async fn add_image_file(
    pool: &SqlitePool,
    root: &std::path::Path,
    owner_id: i64,
    kind: &str,
    entity_id: i64,
) {
    let bytes = png_bytes();
    let folder = root.join(kind).join(entity_id.to_string());
    tokio::fs::create_dir_all(&folder)
        .await
        .expect("fixture media directory created");
    let filename = fixture_filename(kind, entity_id);
    tokio::fs::write(folder.join(&filename), &bytes)
        .await
        .expect("fixture image stored");
    let (project_id, yarn_id, media_kind) = if kind == "projects" {
        (Some(entity_id), None, "title")
    } else {
        (None, Some(entity_id), "photo")
    };
    let namespace = if media_kind == "photo" {
        "photo"
    } else {
        "image"
    };
    let public_id: i64 = sqlx::query_scalar(
        "SELECT COALESCE(MAX(public_id), 0) + 1 FROM media_files \
         WHERE CASE kind WHEN 'title' THEN 'image' WHEN 'step' THEN 'image' \
           WHEN 'photo' THEN 'photo' ELSE 'attachment' END = ?",
    )
    .bind(namespace)
    .fetch_one(pool)
    .await
    .expect("fixture public ID allocated");
    sqlx::query(
        "INSERT INTO media_files \
         (owner_id, project_id, yarn_id, kind, public_id, filename, original_filename, \
          content_type, size_bytes, width, height, alt_text, is_primary, is_safe_inline) \
         VALUES (?, ?, ?, ?, ?, ?, ?, 'image/png', ?, 2, 2, '', 1, 1)",
    )
    .bind(owner_id)
    .bind(project_id)
    .bind(yarn_id)
    .bind(media_kind)
    .bind(public_id)
    .bind(&filename)
    .bind(&filename)
    .bind(bytes.len() as i64)
    .execute(pool)
    .await
    .expect("fixture image row inserted");
}

fn fixture_filename(kind: &str, entity_id: i64) -> String {
    format!("fixture-{kind}-{entity_id}.png")
}

fn csrf(token: &str) -> String {
    let mut hash = Sha256::new();
    hash.update(b"stricknani browser csrf v1\0");
    hash.update(token.as_bytes());
    hex::encode(hash.finalize())
}

fn bearer_request(src: &str, token: &str, lang: Option<&str>) -> Request<Body> {
    let mut payload = json!({"src": src});
    if let Some(lang) = lang {
        payload["lang"] = json!(lang);
    }
    Request::post("/utils/ocr")
        .header("authorization", format!("Bearer {token}"))
        .header("content-type", "application/json")
        .body(Body::from(payload.to_string()))
        .expect("OCR request builds")
}

fn browser_request(src: &str, token: &str, csrf_token: Option<&str>) -> Request<Body> {
    let mut request = Request::post("/utils/ocr")
        .header("cookie", format!("stricknani_session={token}"))
        .header("content-type", "application/json");
    if let Some(csrf_token) = csrf_token {
        request = request.header("x-csrf-token", csrf_token);
    }
    request
        .body(Body::from(json!({"src": src}).to_string()))
        .expect("browser OCR request builds")
}

async fn response_json(response: axum::response::Response) -> Value {
    let bytes = response
        .into_body()
        .collect()
        .await
        .expect("response body reads")
        .to_bytes();
    serde_json::from_slice(&bytes).expect("response body is JSON")
}

fn fixture_service(transport: Arc<FixtureTransport>) -> OcrService {
    OcrService::with_transport(transport)
}

#[tokio::test]
async fn authenticates_bearer_and_browser_with_csrf() {
    let transport = Arc::new(FixtureTransport::default());
    let harness = harness(Some(fixture_service(transport.clone()))).await;
    let src = format!(
        "/media/projects/{}/{}",
        harness.owner_project_id,
        fixture_filename("projects", harness.owner_project_id)
    );

    let unauthenticated = harness
        .app
        .clone()
        .oneshot(
            Request::post("/utils/ocr")
                .header("content-type", "application/json")
                .body(Body::from(json!({"src": src}).to_string()))
                .expect("request builds"),
        )
        .await
        .expect("router responds");
    assert_eq!(
        unauthenticated.status(),
        axum::http::StatusCode::UNAUTHORIZED
    );

    let missing_csrf = harness
        .app
        .clone()
        .oneshot(browser_request(&src, BROWSER_TOKEN, None))
        .await
        .expect("router responds");
    assert_eq!(missing_csrf.status(), axum::http::StatusCode::FORBIDDEN);

    let browser = harness
        .app
        .clone()
        .oneshot(browser_request(
            &src,
            BROWSER_TOKEN,
            Some(&csrf(BROWSER_TOKEN)),
        ))
        .await
        .expect("router responds");
    assert_eq!(browser.status(), axum::http::StatusCode::OK);
    assert_eq!(response_json(browser).await["text"], "recognized text");

    let bearer = harness
        .app
        .oneshot(bearer_request(&src, OWNER_TOKEN, None))
        .await
        .expect("router responds");
    assert_eq!(bearer.status(), axum::http::StatusCode::OK);
    assert_eq!(transport.languages.lock().expect("fixture lock").len(), 2);
}

#[tokio::test]
async fn serves_only_owned_project_or_yarn_media_and_hides_foreign_files() {
    let transport = Arc::new(FixtureTransport::default());
    let harness = harness(Some(fixture_service(transport.clone()))).await;
    let own_project_src = format!(
        "/media/projects/{}/{}",
        harness.owner_project_id,
        fixture_filename("projects", harness.owner_project_id)
    );
    let own_yarn_src = format!(
        "/media/yarns/{}/{}",
        harness.owner_yarn_id,
        fixture_filename("yarns", harness.owner_yarn_id)
    );
    let foreign_src = format!(
        "/media/projects/{}/{}",
        harness.other_project_id,
        fixture_filename("projects", harness.other_project_id)
    );
    let missing_src = format!("/media/projects/{}/missing.png", harness.owner_project_id);

    let own = harness
        .app
        .clone()
        .oneshot(bearer_request(&own_project_src, OWNER_TOKEN, None))
        .await
        .expect("router responds");
    assert_eq!(own.status(), axum::http::StatusCode::OK);

    let yarn = harness
        .app
        .clone()
        .oneshot(bearer_request(&own_yarn_src, OWNER_TOKEN, Some("eng")))
        .await
        .expect("router responds");
    assert_eq!(yarn.status(), axum::http::StatusCode::OK);

    let foreign = harness
        .app
        .clone()
        .oneshot(bearer_request(&foreign_src, OWNER_TOKEN, None))
        .await
        .expect("router responds");
    assert_eq!(foreign.status(), axum::http::StatusCode::NOT_FOUND);

    let missing = harness
        .app
        .oneshot(bearer_request(&missing_src, OWNER_TOKEN, None))
        .await
        .expect("router responds");
    assert_eq!(missing.status(), axum::http::StatusCode::NOT_FOUND);

    assert_eq!(
        *transport.languages.lock().expect("fixture lock"),
        vec!["deu+eng".to_owned(), "eng".to_owned()]
    );
}

#[tokio::test]
async fn rejects_invalid_sources_and_languages_before_ocr_transport() {
    let transport = Arc::new(FixtureTransport::default());
    let harness = harness(Some(fixture_service(transport.clone()))).await;
    for src in [
        "https://example.test/media/projects/1/photo.png",
        "//example.test/media/projects/1/photo.png",
        "/media/users/1/avatar.png",
        "/media/projects/1/a%2Fb.png",
        "/media/projects/1/photo.png?x=1",
    ] {
        let response = harness
            .app
            .clone()
            .oneshot(bearer_request(src, OWNER_TOKEN, None))
            .await
            .expect("router responds");
        assert_eq!(
            response.status(),
            axum::http::StatusCode::BAD_REQUEST,
            "{src}"
        );
    }

    let src = format!(
        "/media/projects/{}/{}",
        harness.owner_project_id,
        fixture_filename("projects", harness.owner_project_id)
    );
    let invalid_language = harness
        .app
        .clone()
        .oneshot(bearer_request(&src, OWNER_TOKEN, Some("--version")))
        .await
        .expect("router responds");
    assert_eq!(
        invalid_language.status(),
        axum::http::StatusCode::BAD_REQUEST
    );
    assert!(transport.languages.lock().expect("fixture lock").is_empty());

    sqlx::query("UPDATE media_files SET content_type = 'application/pdf' WHERE project_id = ?")
        .bind(harness.owner_project_id)
        .execute(&harness.pool)
        .await
        .expect("fixture media type updates");
    let non_image = harness
        .app
        .oneshot(bearer_request(&src, OWNER_TOKEN, None))
        .await
        .expect("router responds");
    assert_eq!(non_image.status(), axum::http::StatusCode::BAD_REQUEST);
    assert!(transport.languages.lock().expect("fixture lock").is_empty());
}

#[tokio::test]
async fn disabled_service_returns_503_without_loading_media_or_running_ocr() {
    let harness = harness(None).await;
    let response = harness
        .app
        .oneshot(bearer_request(
            "/media/projects/999/missing.png",
            OWNER_TOKEN,
            None,
        ))
        .await
        .expect("router responds");
    assert_eq!(
        response.status(),
        axum::http::StatusCode::SERVICE_UNAVAILABLE
    );
    assert_eq!(response_json(response).await["detail"], "ocr_not_available");
}

#[tokio::test]
async fn rejects_media_bodies_over_25_mib_before_calling_ocr() {
    let transport = Arc::new(FixtureTransport::default());
    let harness = harness(Some(fixture_service(transport.clone()))).await;
    let owner_id: i64 = sqlx::query_scalar("SELECT owner_id FROM projects WHERE id = ?")
        .bind(harness.owner_project_id)
        .fetch_one(&harness.pool)
        .await
        .expect("owner id reads");
    let oversized_name = "fixture-oversized.png";
    let size = 25 * 1024 * 1024 + 1;
    let folder = harness
        .media_root
        .path()
        .join("projects")
        .join(harness.owner_project_id.to_string());
    tokio::fs::write(folder.join(oversized_name), vec![0_u8; size])
        .await
        .expect("oversized media file written");
    sqlx::query(
        "INSERT INTO media_files \
         (owner_id, project_id, kind, public_id, filename, original_filename, content_type, size_bytes, \
          width, height, alt_text, is_primary, is_safe_inline) \
         VALUES (?, ?, 'title', 3, ?, ?, 'image/png', ?, 2, 2, '', 0, 1)",
    )
    .bind(owner_id)
    .bind(harness.owner_project_id)
    .bind(oversized_name)
    .bind(oversized_name)
    .bind(size as i64)
    .execute(&harness.pool)
    .await
    .expect("oversized media row inserted");
    let src = format!(
        "/media/projects/{}/{}",
        harness.owner_project_id, oversized_name
    );

    let response = harness
        .app
        .oneshot(bearer_request(&src, OWNER_TOKEN, None))
        .await
        .expect("router responds");
    assert_eq!(response.status(), axum::http::StatusCode::PAYLOAD_TOO_LARGE);
    assert_eq!(response_json(response).await["detail"], "file_too_large");
    assert!(transport.languages.lock().expect("fixture lock").is_empty());
}
