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
    ai::{AiClient, AiConfig, AiError, AiTransport, ApiKey},
    app_with_services, db,
};
use tempfile::TempDir;
use tower::ServiceExt;

const HOST: &str = "app.example.test";
const ORIGIN: &str = "https://app.example.test";

struct Fixture {
    _root: TempDir,
    app: axum::Router,
    pool: SqlitePool,
}

async fn fixture(external_imports: bool) -> Fixture {
    fixture_with_ai(external_imports, None).await
}

async fn fixture_with_ai(external_imports: bool, ai: Option<AiClient>) -> Fixture {
    let pool = db::connect("sqlite::memory:")
        .await
        .expect("in-memory database should migrate");
    db::create_user(
        &pool,
        "import-owner@example.test",
        "import-owner-password".to_owned(),
    )
    .await
    .unwrap();
    let root = tempfile::tempdir().expect("static root should be created");
    let state = db::AppState::new(pool.clone()).with_secure_cookies(true);
    let app = app_with_services(
        state,
        std::path::PathBuf::from(env!("CARGO_BIN_EXE_stricknani-server")),
        external_imports,
        ai,
        None,
    );
    Fixture {
        _root: root,
        app,
        pool,
    }
}

type PostFuture<'a> = Pin<Box<dyn Future<Output = Result<serde_json::Value, AiError>> + Send + 'a>>;

struct CountingTransport(Arc<AtomicUsize>);

impl AiTransport for CountingTransport {
    fn post<'a>(
        &'a self,
        _endpoint: &'a url::Url,
        _api_key: &'a ApiKey,
        _body: &'a serde_json::Value,
    ) -> PostFuture<'a> {
        self.0.fetch_add(1, Ordering::SeqCst);
        Box::pin(async {
            Ok(serde_json::json!({
                "choices": [{"message": {"content": "{\"name\":\"AI result\"}"}}]
            }))
        })
    }
}

async fn json(response: axum::response::Response) -> serde_json::Value {
    let bytes = response
        .into_body()
        .collect()
        .await
        .expect("response body should be readable")
        .to_bytes();
    serde_json::from_slice(&bytes).expect("response should be JSON")
}

fn cookie(response: &axum::response::Response, prefix: &str) -> String {
    response
        .headers()
        .get_all("set-cookie")
        .iter()
        .filter_map(|value| value.to_str().ok())
        .find_map(|value| value.strip_prefix(prefix))
        .and_then(|value| value.split(';').next())
        .expect("expected cookie should be set")
        .to_owned()
}

async fn signed_in(app: &axum::Router) -> (String, String) {
    let bootstrap = app
        .clone()
        .oneshot(Request::get("/api/v2/session").body(Body::empty()).unwrap())
        .await
        .unwrap();
    let preauth = cookie(&bootstrap, "__Host-stricknani_v2_login_csrf=");
    let bootstrap_data = json(bootstrap).await;
    assert_eq!(
        bootstrap_data["data"]["capabilities"]["import_preview"],
        false
    );
    assert_eq!(
        bootstrap_data["data"]["capabilities"]["account_actions"],
        false
    );
    let pre_csrf = bootstrap_data["data"]["csrf_token"]
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
                        "email":"import-owner@example.test",
                        "password":"import-owner-password"
                    })
                    .to_string(),
                ))
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(login.status(), StatusCode::OK);
    let session = cookie(&login, "__Host-stricknani_session=");
    let login_data = json(login).await;
    assert_eq!(login_data["data"]["capabilities"]["media_upload"], false);
    assert_eq!(login_data["data"]["capabilities"]["media_delete"], false);
    assert_eq!(login_data["data"]["capabilities"]["import_preview"], true);
    assert_eq!(login_data["data"]["capabilities"]["archive_state"], false);
    assert_eq!(login_data["data"]["capabilities"]["account_actions"], true);
    assert_eq!(login_data["data"]["capabilities"]["admin_actions"], false);
    let csrf = login_data["data"]["csrf_token"]
        .as_str()
        .unwrap()
        .to_owned();
    (session, csrf)
}

fn preview_request(
    session: &str,
    csrf: &str,
    origin: &str,
    payload: serde_json::Value,
) -> Request<Body> {
    Request::post("/api/v2/imports/preview")
        .header("host", HOST)
        .header("origin", origin)
        .header("sec-fetch-site", "same-origin")
        .header("cookie", format!("__Host-stricknani_session={session}"))
        .header("x-csrf-token", csrf)
        .header("content-type", "application/json")
        .body(Body::from(payload.to_string()))
        .unwrap()
}

#[tokio::test]
async fn text_preview_returns_reviewable_draft_and_explicit_ai_fallback() {
    let fixture = fixture(false).await;
    let (session, csrf) = signed_in(&fixture.app).await;
    let response = fixture
        .app
        .clone()
        .oneshot(preview_request(
            &session,
            &csrf,
            ORIGIN,
            serde_json::json!({"type":"text", "text":"Cozy socks\nCast on 48 stitches.", "use_ai":true}),
        ))
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    let body = json(response).await;
    assert_eq!(body["data"]["draft"]["kind"], "plain_text");
    assert!(
        body["data"]["draft"]["project"]["name"]
            .as_str()
            .is_some_and(|name| !name.is_empty())
    );
    assert_eq!(body["data"]["ai_requested"], true);
    assert_eq!(body["data"]["is_ai_enhanced"], false);
    assert_eq!(body["data"]["ai_fallback"], true);
    assert_eq!(body["data"]["content_sha256"].as_str().unwrap().len(), 64);
}

#[tokio::test]
async fn reviewed_preview_creates_project_through_normal_v2_resource_validation() {
    let fixture = fixture(false).await;
    let (session, csrf) = signed_in(&fixture.app).await;
    let preview = fixture
        .app
        .clone()
        .oneshot(preview_request(
            &session,
            &csrf,
            ORIGIN,
            serde_json::json!({"type":"text", "text":"Cast on 48 stitches."}),
        ))
        .await
        .unwrap();
    assert_eq!(preview.status(), StatusCode::OK);
    let draft = json(preview).await["data"]["draft"]["project"].clone();

    let created = fixture
        .app
        .clone()
        .oneshot(
            Request::post("/api/v2/projects")
                .header("host", HOST)
                .header("origin", ORIGIN)
                .header("sec-fetch-site", "same-origin")
                .header("cookie", format!("__Host-stricknani_session={session}"))
                .header("x-csrf-token", csrf)
                .header("content-type", "application/json")
                .body(Body::from(
                    serde_json::json!({
                        "name":"Reviewed socks",
                        "description":draft["description"],
                        "image_urls":draft["imageUrls"],
                        "steps":draft["steps"]
                    })
                    .to_string(),
                ))
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(created.status(), StatusCode::CREATED);
    let response = json(created).await;
    assert_eq!(response["data"]["name"], "Reviewed socks");
    let owner_id = sqlx::query_scalar::<_, i64>(
        "SELECT owner_id FROM projects WHERE id = ? AND name = 'Reviewed socks'",
    )
    .bind(response["data"]["id"].as_i64().unwrap())
    .fetch_one(&fixture.pool)
    .await
    .unwrap();
    assert_eq!(
        sqlx::query_scalar::<_, i64>("SELECT id FROM users WHERE email = ?")
            .bind("import-owner@example.test")
            .fetch_one(&fixture.pool)
            .await
            .unwrap(),
        owner_id
    );
}

#[tokio::test]
async fn ai_capability_requires_configured_client_and_unrequested_ai_is_not_called() {
    let calls = Arc::new(AtomicUsize::new(0));
    let config = AiConfig::from_lookup(true, |key| match key {
        "AI_API_KEY" => Some("test-ai-key".to_owned()),
        _ => None,
    })
    .expect("fixture AI config is valid")
    .expect("fixture AI is enabled");
    let ai = AiClient::with_transport(config, Arc::new(CountingTransport(calls.clone())));
    let fixture = fixture_with_ai(false, Some(ai)).await;
    let (session, csrf) = signed_in(&fixture.app).await;

    let response = fixture
        .app
        .clone()
        .oneshot(preview_request(
            &session,
            &csrf,
            ORIGIN,
            serde_json::json!({"type":"text", "text":"Cozy socks", "use_ai":false}),
        ))
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    assert_eq!(json(response).await["data"]["ai_requested"], false);
    assert_eq!(calls.load(Ordering::SeqCst), 0);

    let session_response = fixture
        .app
        .oneshot(
            Request::get("/api/v2/session")
                .header("host", HOST)
                .header("cookie", format!("__Host-stricknani_session={session}"))
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(
        json(session_response).await["data"]["capabilities"]["ai"],
        true
    );
}

#[tokio::test]
async fn import_preview_rate_limit_is_separate_and_returns_retry_after() {
    let fixture = fixture(false).await;
    let (session, csrf) = signed_in(&fixture.app).await;
    for _ in 0..20 {
        let response = fixture
            .app
            .clone()
            .oneshot(preview_request(
                &session,
                &csrf,
                ORIGIN,
                serde_json::json!({"type":"unsupported"}),
            ))
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::UNPROCESSABLE_ENTITY);
    }
    let limited = fixture
        .app
        .oneshot(preview_request(
            &session,
            &csrf,
            ORIGIN,
            serde_json::json!({"type":"unsupported"}),
        ))
        .await
        .unwrap();
    assert_eq!(limited.status(), StatusCode::TOO_MANY_REQUESTS);
    assert!(limited.headers().contains_key("retry-after"));
    assert_eq!(json(limited).await["error"]["code"], "rate_limited");
}

#[tokio::test]
async fn import_preview_auth_origin_feature_and_size_failures_are_stable() {
    let fixture = fixture(false).await;
    let (session, csrf) = signed_in(&fixture.app).await;

    let anonymous = fixture
        .app
        .clone()
        .oneshot(
            Request::post("/api/v2/imports/preview")
                .header("host", HOST)
                .header("origin", ORIGIN)
                .header("content-type", "application/json")
                .body(Body::from(r#"{"type":"text","text":"hello"}"#))
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(anonymous.status(), StatusCode::UNAUTHORIZED);

    let cross_origin = fixture
        .app
        .clone()
        .oneshot(preview_request(
            &session,
            &csrf,
            "https://evil.example.test",
            serde_json::json!({"type":"text", "text":"hello"}),
        ))
        .await
        .unwrap();
    assert_eq!(cross_origin.status(), StatusCode::FORBIDDEN);
    assert_eq!(json(cross_origin).await["error"]["code"], "csrf_failed");

    let disabled_url = fixture
        .app
        .clone()
        .oneshot(preview_request(
            &session,
            &csrf,
            ORIGIN,
            serde_json::json!({"type":"url", "url":"https://example.test/pattern"}),
        ))
        .await
        .unwrap();
    assert_eq!(disabled_url.status(), StatusCode::SERVICE_UNAVAILABLE);
    assert_eq!(
        json(disabled_url).await["error"]["code"],
        "feature_disabled"
    );

    let oversized = fixture
        .app
        .oneshot(preview_request(
            &session,
            &csrf,
            ORIGIN,
            serde_json::json!({"type":"text", "text":"x".repeat(5 * 1024 * 1024 + 1)}),
        ))
        .await
        .unwrap();
    assert_eq!(oversized.status(), StatusCode::PAYLOAD_TOO_LARGE);
    assert_eq!(json(oversized).await["error"]["code"], "payload_too_large");
    assert_eq!(
        sqlx::query_scalar::<_, i64>("SELECT COUNT(*) FROM projects")
            .fetch_one(&fixture.pool)
            .await
            .unwrap(),
        0
    );
}

#[tokio::test]
async fn url_preview_rejects_private_addresses_before_fetching() {
    let fixture = fixture(true).await;
    let (session, csrf) = signed_in(&fixture.app).await;
    let response = fixture
        .app
        .oneshot(preview_request(
            &session,
            &csrf,
            ORIGIN,
            serde_json::json!({"type":"url", "url":"http://127.0.0.1/"}),
        ))
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::UNPROCESSABLE_ENTITY);
    assert_eq!(json(response).await["error"]["code"], "unsafe_url");
}

#[tokio::test]
async fn yarn_target_is_accepted_and_still_blocks_private_addresses() {
    let fixture = fixture(true).await;
    let (session, csrf) = signed_in(&fixture.app).await;
    let response = fixture
        .app
        .oneshot(preview_request(
            &session,
            &csrf,
            ORIGIN,
            serde_json::json!({"type":"url", "url":"http://10.0.0.5/yarn", "target":"yarn", "use_ai": true}),
        ))
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::UNPROCESSABLE_ENTITY);
    assert_eq!(json(response).await["error"]["code"], "unsafe_url");
}

#[tokio::test]
async fn unknown_import_target_is_a_validation_error() {
    let fixture = fixture(true).await;
    let (session, csrf) = signed_in(&fixture.app).await;
    let response = fixture
        .app
        .oneshot(preview_request(
            &session,
            &csrf,
            ORIGIN,
            serde_json::json!({"type":"url", "url":"https://example.test/", "target":"socks"}),
        ))
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::UNPROCESSABLE_ENTITY);
}

#[tokio::test]
async fn thumbnail_proxy_needs_a_session_the_feature_and_a_public_address() {
    let disabled = fixture(false).await;
    let (session, _csrf) = signed_in(&disabled.app).await;
    let get = |url: &str, session: Option<&str>| {
        let mut builder = Request::get(format!(
            "/api/v2/imports/image?url={}",
            url.replace(':', "%3A").replace('/', "%2F")
        ))
        .header("host", HOST);
        if let Some(session) = session {
            builder = builder.header("cookie", format!("__Host-stricknani_session={session}"));
        }
        builder.body(Body::empty()).unwrap()
    };
    let anonymous = disabled
        .app
        .clone()
        .oneshot(get("https://example.test/a.jpg", None))
        .await
        .unwrap();
    assert_eq!(anonymous.status(), StatusCode::UNAUTHORIZED);
    let off = disabled
        .app
        .clone()
        .oneshot(get("https://example.test/a.jpg", Some(&session)))
        .await
        .unwrap();
    assert_eq!(off.status(), StatusCode::SERVICE_UNAVAILABLE);

    let enabled = fixture(true).await;
    let (session, _csrf) = signed_in(&enabled.app).await;
    let private = enabled
        .app
        .oneshot(get("http://127.0.0.1/a.jpg", Some(&session)))
        .await
        .unwrap();
    assert_eq!(private.status(), StatusCode::UNPROCESSABLE_ENTITY);
}

#[tokio::test]
async fn file_preview_checks_csrf_before_fields_and_accepts_ai_opt_in_field() {
    let fixture = fixture(false).await;
    let (session, csrf) = signed_in(&fixture.app).await;
    let image = image_bytes();
    let boundary = "v2-import-boundary";
    let mut body = format!(
        "--{boundary}\r\nContent-Disposition: form-data; name=\"use_ai\"\r\n\r\ntrue\r\n--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"pattern.png\"\r\nContent-Type: image/png\r\n\r\n"
    )
    .into_bytes();
    body.extend_from_slice(&image);
    body.extend_from_slice(format!("\r\n--{boundary}--\r\n").as_bytes());

    let response = fixture
        .app
        .clone()
        .oneshot(
            Request::post("/api/v2/imports/preview-file")
                .header("host", HOST)
                .header("origin", ORIGIN)
                .header("sec-fetch-site", "same-origin")
                .header("cookie", format!("__Host-stricknani_session={session}"))
                .header("x-csrf-token", csrf)
                .header(
                    "content-type",
                    format!("multipart/form-data; boundary={boundary}"),
                )
                .body(Body::from(body.clone()))
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    let response_body = json(response).await;
    assert_eq!(response_body["data"]["draft"]["kind"], "image");
    assert_eq!(response_body["data"]["ai_requested"], true);
    assert_eq!(response_body["data"]["ai_fallback"], true);

    let denied = fixture
        .app
        .oneshot(
            Request::post("/api/v2/imports/preview-file")
                .header("host", HOST)
                .header("origin", ORIGIN)
                .header("sec-fetch-site", "same-origin")
                .header("cookie", format!("__Host-stricknani_session={session}"))
                .header("x-csrf-token", "invalid")
                .header(
                    "content-type",
                    format!("multipart/form-data; boundary={boundary}"),
                )
                .body(Body::from(body))
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(denied.status(), StatusCode::FORBIDDEN);
    assert_eq!(json(denied).await["error"]["code"], "csrf_failed");
}

#[tokio::test]
async fn oversized_multipart_stream_returns_413_error_envelope() {
    let fixture = fixture(false).await;
    let (session, csrf) = signed_in(&fixture.app).await;
    let boundary = "oversized-v2-import";
    let mut body = format!(
        "--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"large.pdf\"\r\nContent-Type: application/pdf\r\n\r\n"
    )
    .into_bytes();
    body.extend(std::iter::repeat_n(b'x', 5 * 1024 * 1024 + 128 * 1024));
    body.extend_from_slice(format!("\r\n--{boundary}--\r\n").as_bytes());

    let response = fixture
        .app
        .oneshot(
            Request::post("/api/v2/imports/preview-file")
                .header("host", HOST)
                .header("origin", ORIGIN)
                .header("sec-fetch-site", "same-origin")
                .header("cookie", format!("__Host-stricknani_session={session}"))
                .header("x-csrf-token", csrf)
                .header(
                    "content-type",
                    format!("multipart/form-data; boundary={boundary}"),
                )
                .body(Body::from(body))
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::PAYLOAD_TOO_LARGE);
    assert_eq!(json(response).await["error"]["code"], "payload_too_large");
}

fn image_bytes() -> Vec<u8> {
    let image = image::DynamicImage::new_rgba8(2, 2);
    let mut output = Cursor::new(Vec::new());
    image
        .write_to(&mut output, image::ImageFormat::Png)
        .expect("fixture image encodes");
    output.into_inner()
}
