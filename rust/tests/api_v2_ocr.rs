use axum::{
    body::Body,
    http::{Request, StatusCode},
};
use http_body_util::BodyExt;
use sqlx::SqlitePool;
use std::{
    future::Future,
    io::Cursor,
    pin::Pin,
    sync::{
        Arc,
        atomic::{AtomicUsize, Ordering},
    },
};
use stricknani_server::{
    app_with_services, db,
    ocr::{OcrError, OcrLanguage, OcrService, OcrTransport},
};
use tempfile::TempDir;
use tower::ServiceExt;

const HOST: &str = "app.example.test";
const ORIGIN: &str = "https://app.example.test";

struct Fixture {
    _root: TempDir,
    app: axum::Router,
    pool: SqlitePool,
    owner_project_id: i64,
    foreign_project_id: i64,
    calls: Arc<AtomicUsize>,
}

struct FixtureTransport(Arc<AtomicUsize>);

impl OcrTransport for FixtureTransport {
    fn recognize<'a>(
        &'a self,
        image_bytes: Vec<u8>,
        _language: OcrLanguage,
    ) -> Pin<Box<dyn Future<Output = Result<String, OcrError>> + Send + 'a>> {
        self.0.fetch_add(1, Ordering::SeqCst);
        Box::pin(async move {
            if image_bytes.is_empty() {
                Err(OcrError::InvalidImage)
            } else {
                Ok("fixture text".to_owned())
            }
        })
    }
}

async fn make_fixture(ocr_enabled: bool, service_enabled: bool) -> Fixture {
    let pool = db::connect("sqlite::memory:").await.unwrap();
    let owner_id = db::create_user(
        &pool,
        "ocr-api-owner@example.test",
        "ocr-api-password".to_owned(),
    )
    .await
    .unwrap();
    let foreign_id = db::create_user(
        &pool,
        "ocr-api-foreign@example.test",
        "ocr-api-password".to_owned(),
    )
    .await
    .unwrap();
    let owner_project_id = create_project(&pool, owner_id).await;
    let foreign_project_id = create_project(&pool, foreign_id).await;
    let root = tempfile::tempdir().unwrap();
    let filename = "test-image.png";
    let owner_path = root
        .path()
        .join("projects")
        .join(owner_project_id.to_string());
    tokio::fs::create_dir_all(&owner_path).await.unwrap();
    tokio::fs::write(owner_path.join(filename), png_bytes())
        .await
        .unwrap();
    let foreign_path = root
        .path()
        .join("projects")
        .join(foreign_project_id.to_string());
    tokio::fs::create_dir_all(&foreign_path).await.unwrap();
    tokio::fs::write(foreign_path.join("foreign-image.png"), png_bytes())
        .await
        .unwrap();
    sqlx::query(
        "INSERT INTO media_files (owner_id,project_id,kind,public_id,filename,original_filename,content_type,size_bytes) \
         VALUES (?,?,'title',1,?,'test-image.png','image/png',?)",
    )
    .bind(owner_id)
    .bind(owner_project_id)
    .bind(filename)
    .bind(png_bytes().len() as i64)
    .execute(&pool)
    .await
    .unwrap();
    sqlx::query(
        "INSERT INTO media_files (owner_id,project_id,kind,public_id,filename,original_filename,content_type,size_bytes) \
         VALUES (?,?,'title',2,'foreign-image.png','foreign-image.png','image/png',?)",
    )
    .bind(foreign_id)
    .bind(foreign_project_id)
    .bind(png_bytes().len() as i64)
    .execute(&pool)
    .await
    .unwrap();
    sqlx::query(
        "INSERT INTO media_files (owner_id,project_id,kind,public_id,filename,original_filename,content_type,size_bytes) \
         VALUES (?,?,'attachment',1,'private.pdf','private.pdf','application/pdf',1)",
    )
    .bind(owner_id)
    .bind(owner_project_id)
    .execute(&pool)
    .await
    .unwrap();
    let calls = Arc::new(AtomicUsize::new(0));
    let service = service_enabled
        .then(|| OcrService::with_transport(Arc::new(FixtureTransport(calls.clone()))));
    let state = db::AppState::new(pool.clone())
        .with_secure_cookies(true)
        .with_media_root(root.path().to_path_buf())
        .with_ocr_enabled(ocr_enabled);
    let app = app_with_services(state, std::path::PathBuf::new(), false, None, service);
    Fixture {
        _root: root,
        app,
        pool,
        owner_project_id,
        foreign_project_id,
        calls,
    }
}

async fn create_project(pool: &SqlitePool, owner_id: i64) -> i64 {
    sqlx::query_scalar(
        "INSERT INTO projects (owner_id,name,created_at,updated_at) \
         VALUES (?,'OCR API project','2026-01-01T00:00:00Z','2026-01-01T00:00:00Z') RETURNING id",
    )
    .bind(owner_id)
    .fetch_one(pool)
    .await
    .unwrap()
}

fn png_bytes() -> Vec<u8> {
    let mut output = Cursor::new(Vec::new());
    image::DynamicImage::new_rgb8(2, 2)
        .write_to(&mut output, image::ImageFormat::Png)
        .unwrap();
    output.into_inner()
}

async fn json(response: axum::response::Response) -> serde_json::Value {
    let bytes = response.into_body().collect().await.unwrap().to_bytes();
    serde_json::from_slice(&bytes).unwrap()
}

fn cookie(response: &axum::response::Response, prefix: &str) -> String {
    response
        .headers()
        .get_all("set-cookie")
        .iter()
        .filter_map(|value| value.to_str().ok())
        .find_map(|value| value.strip_prefix(prefix))
        .and_then(|value| value.split(';').next())
        .unwrap()
        .to_owned()
}

async fn signed_in(app: &axum::Router) -> (String, String) {
    signed_in_as(app, "ocr-api-owner@example.test", "ocr-api-password").await
}

async fn signed_in_as(app: &axum::Router, email: &str, password: &str) -> (String, String) {
    let bootstrap = app
        .clone()
        .oneshot(Request::get("/api/v2/session").body(Body::empty()).unwrap())
        .await
        .unwrap();
    let preauth = cookie(&bootstrap, "__Host-stricknani_v2_login_csrf=");
    let pre_csrf = json(bootstrap).await["data"]["csrf_token"]
        .as_str()
        .unwrap()
        .to_owned();
    let login = app
        .clone()
        .oneshot(
            Request::post("/api/v2/auth/login")
                .header("host", HOST)
                .header("origin", ORIGIN)
                .header("sec-fetch-site", "same-origin")
                .header(
                    "cookie",
                    format!("__Host-stricknani_v2_login_csrf={preauth}"),
                )
                .header("x-csrf-token", pre_csrf)
                .header("content-type", "application/json")
                .body(Body::from(
                    serde_json::json!({
                        "email":email,
                        "password":password
                    })
                    .to_string(),
                ))
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(login.status(), StatusCode::OK);
    let session = cookie(&login, "__Host-stricknani_session=");
    let csrf = json(login).await["data"]["csrf_token"]
        .as_str()
        .unwrap()
        .to_owned();
    (session, csrf)
}

fn ocr_request(path: &str, session: Option<&str>, csrf: Option<&str>, src: &str) -> Request<Body> {
    let mut request = Request::builder()
        .method("POST")
        .uri(path)
        .header("content-type", "application/json")
        .header("host", HOST)
        .header("origin", ORIGIN)
        .header("sec-fetch-site", "same-origin");
    if let Some(session) = session {
        request = request.header("cookie", format!("__Host-stricknani_session={session}"));
    }
    if let Some(csrf) = csrf {
        request = request.header("x-csrf-token", csrf);
    }
    request
        .body(Body::from(
            serde_json::json!({"src":src,"lang":"eng"}).to_string(),
        ))
        .unwrap()
}

#[tokio::test]
async fn ocr_reads_only_owned_canonical_image_and_uses_configured_service() {
    let fixture = make_fixture(true, true).await;
    let (session, csrf) = signed_in(&fixture.app).await;
    let capabilities = fixture
        .app
        .clone()
        .oneshot(Request::get("/api/v2/session").body(Body::empty()).unwrap())
        .await
        .unwrap();
    assert_eq!(
        json(capabilities).await["data"]["capabilities"]["ocr"],
        true
    );

    let success = fixture
        .app
        .clone()
        .oneshot(ocr_request(
            "/api/v2/utils/ocr",
            Some(&session),
            Some(&csrf),
            &format!(
                "/media/projects/{}/test-image.png",
                fixture.owner_project_id
            ),
        ))
        .await
        .unwrap();
    assert_eq!(success.status(), StatusCode::OK);
    assert_eq!(json(success).await["data"]["text"], "fixture text");
    assert_eq!(fixture.calls.load(Ordering::SeqCst), 1);

    let foreign = fixture
        .app
        .clone()
        .oneshot(ocr_request(
            "/api/v2/utils/ocr",
            Some(&session),
            Some(&csrf),
            &format!(
                "/media/projects/{}/test-image.png",
                fixture.foreign_project_id
            ),
        ))
        .await
        .unwrap();
    assert_eq!(foreign.status(), StatusCode::NOT_FOUND);
    assert_eq!(fixture.calls.load(Ordering::SeqCst), 1);

    let csrf_rejected = fixture
        .app
        .clone()
        .oneshot(ocr_request(
            "/api/v2/utils/ocr",
            Some(&session),
            None,
            &format!(
                "/media/projects/{}/test-image.png",
                fixture.owner_project_id
            ),
        ))
        .await
        .unwrap();
    assert_eq!(csrf_rejected.status(), StatusCode::FORBIDDEN);
    assert_eq!(fixture.calls.load(Ordering::SeqCst), 1);

    let invalid_path = fixture
        .app
        .clone()
        .oneshot(ocr_request(
            "/api/v2/utils/ocr",
            Some(&session),
            Some(&csrf),
            "https://example.test/media/projects/1/test-image.png",
        ))
        .await
        .unwrap();
    assert_eq!(invalid_path.status(), StatusCode::UNPROCESSABLE_ENTITY);

    let attachment = fixture
        .app
        .clone()
        .oneshot(ocr_request(
            "/api/v2/utils/ocr",
            Some(&session),
            Some(&csrf),
            &format!("/media/projects/{}/private.pdf", fixture.owner_project_id),
        ))
        .await
        .unwrap();
    assert_eq!(attachment.status(), StatusCode::NOT_FOUND);

    let invalid_language = fixture
        .app
        .clone()
        .oneshot(
            Request::post("/api/v2/utils/ocr")
                .header("host", HOST)
                .header("origin", ORIGIN)
                .header("sec-fetch-site", "same-origin")
                .header("cookie", format!("__Host-stricknani_session={session}"))
                .header("x-csrf-token", csrf)
                .header("content-type", "application/json")
                .body(Body::from(
                    serde_json::json!({
                        "src":format!("/media/projects/{}/test-image.png", fixture.owner_project_id),
                        "lang":"eng;touch"
                    })
                    .to_string(),
                ))
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(invalid_language.status(), StatusCode::UNPROCESSABLE_ENTITY);
    assert_eq!(
        json(invalid_language).await["error"]["code"],
        "invalid_language"
    );
    assert_eq!(fixture.calls.load(Ordering::SeqCst), 1);
}

#[tokio::test]
async fn ocr_enforces_declared_and_actual_media_size_caps() {
    let fixture = make_fixture(true, true).await;
    let (session, csrf) = signed_in(&fixture.app).await;
    let path = format!(
        "/media/projects/{}/test-image.png",
        fixture.owner_project_id
    );
    let declared_size = i64::try_from(stricknani_server::ocr::MAX_OCR_BYTES + 1).unwrap();
    sqlx::query("UPDATE media_files SET size_bytes = ? WHERE filename = 'test-image.png'")
        .bind(declared_size)
        .execute(&fixture.pool)
        .await
        .unwrap();
    let declared = fixture
        .app
        .clone()
        .oneshot(ocr_request(
            "/api/v2/utils/ocr",
            Some(&session),
            Some(&csrf),
            &path,
        ))
        .await
        .unwrap();
    assert_eq!(declared.status(), StatusCode::PAYLOAD_TOO_LARGE);

    sqlx::query("UPDATE media_files SET size_bytes = 16 WHERE filename = 'test-image.png'")
        .execute(&fixture.pool)
        .await
        .unwrap();
    let media_path = fixture
        ._root
        .path()
        .join("projects")
        .join(fixture.owner_project_id.to_string())
        .join("test-image.png");
    let file = tokio::fs::OpenOptions::new()
        .write(true)
        .open(media_path)
        .await
        .unwrap();
    file.set_len((stricknani_server::ocr::MAX_OCR_BYTES + 1) as u64)
        .await
        .unwrap();
    let actual = fixture
        .app
        .clone()
        .oneshot(ocr_request(
            "/api/v2/utils/ocr",
            Some(&session),
            Some(&csrf),
            &path,
        ))
        .await
        .unwrap();
    assert_eq!(actual.status(), StatusCode::PAYLOAD_TOO_LARGE);
    assert_eq!(fixture.calls.load(Ordering::SeqCst), 0);
}

#[tokio::test]
async fn ocr_quota_is_bounded_per_owner_and_denied_work_does_not_read_or_run() {
    let fixture = make_fixture(true, true).await;
    let (owner_session, owner_csrf) = signed_in(&fixture.app).await;
    let owner_src = format!(
        "/media/projects/{}/test-image.png",
        fixture.owner_project_id
    );

    for _ in 0..5 {
        let response = fixture
            .app
            .clone()
            .oneshot(ocr_request(
                "/api/v2/utils/ocr",
                Some(&owner_session),
                Some(&owner_csrf),
                &owner_src,
            ))
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::OK);
    }
    assert_eq!(fixture.calls.load(Ordering::SeqCst), 5);
    tokio::fs::remove_file(
        fixture
            ._root
            .path()
            .join("projects")
            .join(fixture.owner_project_id.to_string())
            .join("test-image.png"),
    )
    .await
    .unwrap();

    let denied = fixture
        .app
        .clone()
        .oneshot(ocr_request(
            "/api/v2/utils/ocr",
            Some(&owner_session),
            Some(&owner_csrf),
            &owner_src,
        ))
        .await
        .unwrap();
    assert_eq!(denied.status(), StatusCode::TOO_MANY_REQUESTS);
    let retry_after = denied
        .headers()
        .get("retry-after")
        .and_then(|value| value.to_str().ok())
        .and_then(|value| value.parse::<u64>().ok())
        .expect("429 should include Retry-After");
    assert!(retry_after > 0);
    assert_eq!(json(denied).await["error"]["code"], "rate_limited");
    assert_eq!(fixture.calls.load(Ordering::SeqCst), 5);

    let (other_session, other_csrf) = signed_in_as(
        &fixture.app,
        "ocr-api-foreign@example.test",
        "ocr-api-password",
    )
    .await;
    let other = fixture
        .app
        .clone()
        .oneshot(ocr_request(
            "/api/v2/utils/ocr",
            Some(&other_session),
            Some(&other_csrf),
            &format!(
                "/media/projects/{}/foreign-image.png",
                fixture.foreign_project_id
            ),
        ))
        .await
        .unwrap();
    assert_eq!(other.status(), StatusCode::OK);
    assert_eq!(fixture.calls.load(Ordering::SeqCst), 6);
}

#[tokio::test]
async fn ocr_requires_browser_session_and_real_enabled_service() {
    let fixture = make_fixture(true, true).await;
    let unauthenticated = fixture
        .app
        .clone()
        .oneshot(ocr_request(
            "/api/v2/utils/ocr",
            None,
            None,
            &format!(
                "/media/projects/{}/test-image.png",
                fixture.owner_project_id
            ),
        ))
        .await
        .unwrap();
    assert_eq!(unauthenticated.status(), StatusCode::UNAUTHORIZED);

    let disabled = make_fixture(false, true).await;
    let (session, csrf) = signed_in(&disabled.app).await;
    let response = disabled
        .app
        .clone()
        .oneshot(ocr_request(
            "/api/v2/utils/ocr",
            Some(&session),
            Some(&csrf),
            &format!(
                "/media/projects/{}/test-image.png",
                disabled.owner_project_id
            ),
        ))
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::SERVICE_UNAVAILABLE);
    assert_eq!(json(response).await["error"]["code"], "ocr_unavailable");
    assert_eq!(
        json(
            disabled
                .app
                .clone()
                .oneshot(Request::get("/api/v2/session").body(Body::empty()).unwrap())
                .await
                .unwrap()
        )
        .await["data"]["capabilities"]["ocr"],
        false
    );

    let no_service = make_fixture(true, false).await;
    let (session, csrf) = signed_in(&no_service.app).await;
    let response = no_service
        .app
        .clone()
        .oneshot(ocr_request(
            "/api/v2/utils/ocr",
            Some(&session),
            Some(&csrf),
            &format!(
                "/media/projects/{}/test-image.png",
                no_service.owner_project_id
            ),
        ))
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::SERVICE_UNAVAILABLE);
    assert_eq!(json(response).await["error"]["code"], "ocr_unavailable");
    assert_eq!(
        json(
            no_service
                .app
                .clone()
                .oneshot(Request::get("/api/v2/session").body(Body::empty()).unwrap())
                .await
                .unwrap()
        )
        .await["data"]["capabilities"]["ocr"],
        false
    );
}
