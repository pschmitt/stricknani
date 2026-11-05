//! Optional Sentry error reporting: the account setting, what clients are told,
//! and the same-origin tunnel (DSN validation, size cap, opt-out, scrubbing).

use std::sync::{Arc, Mutex};

use axum::{
    Router,
    body::{Body, Bytes},
    extract::State,
    http::{Request, StatusCode},
    middleware::from_fn,
    response::Response,
    routing::{get, post},
};
use http_body_util::BodyExt;
use sha2::{Digest, Sha256};
use sqlx::SqlitePool;
use stricknani_server::{
    api_v2, db,
    error_reporting::{ErrorReportingSettings, MAX_ENVELOPE_BYTES},
};
use tower::ServiceExt;

const USER_PAT: &str = "sentry-user-pat";
const OTHER_PAT: &str = "sentry-other-pat";

#[derive(Default)]
struct Upstream {
    received: Mutex<Vec<(String, Vec<u8>)>>,
}

async fn start_upstream() -> (Arc<Upstream>, String) {
    async fn envelope(State(upstream): State<Arc<Upstream>>, body: Bytes) -> StatusCode {
        upstream
            .received
            .lock()
            .unwrap()
            .push(("envelope".to_owned(), body.to_vec()));
        StatusCode::OK
    }
    let upstream = Arc::new(Upstream::default());
    let app = Router::new()
        .route("/api/7/envelope/", post(envelope))
        .with_state(upstream.clone());
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let addr = listener.local_addr().unwrap();
    tokio::spawn(async move { axum::serve(listener, app).await.unwrap() });
    (upstream, format!("http://pubkey@{addr}/7"))
}

struct Fixture {
    app: Router,
    pool: SqlitePool,
    user_id: i64,
    dsn: String,
}

async fn add_pat(pool: &SqlitePool, user_id: i64, secret: &str) {
    sqlx::query(
        "INSERT INTO api_tokens (user_id, name, token_hash, purpose, created_at) \
         VALUES (?, 'fixture', ?, 'api', unixepoch())",
    )
    .bind(user_id)
    .bind(hex::encode(Sha256::digest(secret.as_bytes())))
    .execute(pool)
    .await
    .unwrap();
}

async fn fixture(dsn: Option<&str>) -> Fixture {
    let pool = db::connect("sqlite::memory:").await.unwrap();
    let user_id = db::create_user(&pool, "alice@example.test", "alice-password-long".into())
        .await
        .unwrap();
    let other_id = db::create_user(&pool, "bob@example.test", "bob-password-long".into())
        .await
        .unwrap();
    add_pat(&pool, user_id, USER_PAT).await;
    add_pat(&pool, other_id, OTHER_PAT).await;
    let settings = match dsn {
        Some(dsn) => ErrorReportingSettings::new(dsn, "test".to_owned(), 0.0).unwrap(),
        None => ErrorReportingSettings::default(),
    };
    let state = db::AppState::new(pool.clone()).with_error_reporting(settings);
    Fixture {
        app: api_v2::router(state, false),
        pool,
        user_id,
        dsn: dsn.unwrap_or_default().to_owned(),
    }
}

fn request(method: &str, path: &str, token: Option<&str>, body: Vec<u8>) -> Request<Body> {
    let mut builder = Request::builder().method(method).uri(path);
    if let Some(token) = token {
        builder = builder.header("authorization", format!("Bearer {token}"));
    }
    builder.body(Body::from(body)).unwrap()
}

fn json_request(method: &str, path: &str, token: &str, body: serde_json::Value) -> Request<Body> {
    let mut request = request(method, path, Some(token), body.to_string().into_bytes());
    request
        .headers_mut()
        .insert("content-type", "application/json".parse().unwrap());
    request
}

async fn send(f: &Fixture, request: Request<Body>) -> Response {
    f.app.clone().oneshot(request).await.unwrap()
}

async fn json(response: Response) -> serde_json::Value {
    let bytes = response.into_body().collect().await.unwrap().to_bytes();
    serde_json::from_slice(&bytes).unwrap_or(serde_json::Value::Null)
}

fn envelope(dsn: &str, payload: &str) -> Vec<u8> {
    format!(
        "{{\"dsn\":\"{dsn}\"}}\n{{\"type\":\"event\",\"length\":{}}}\n{payload}\n",
        payload.len()
    )
    .into_bytes()
}

const TUNNEL: &str = "/telemetry/sentry";

#[tokio::test]
async fn unconfigured_instance_hides_the_feature() {
    let f = fixture(None).await;
    let meta = json(send(&f, request("GET", "/meta", Some(USER_PAT), vec![])).await).await;
    let info = &meta["data"]["error_reporting"];
    assert_eq!(info["available"], false);
    assert!(info["dsn"].is_null() && info["tunnel_path"].is_null());
    let account = json(send(&f, request("GET", "/account", Some(USER_PAT), vec![])).await).await;
    assert_eq!(account["data"]["error_reporting_available"], false);
    let response = send(
        &f,
        request("POST", TUNNEL, None, envelope("http://k@h.test/7", "{}")),
    )
    .await;
    assert_eq!(response.status(), StatusCode::NOT_FOUND);
}

async fn internal_server_error() -> StatusCode {
    StatusCode::INTERNAL_SERVER_ERROR
}

async fn opted_out_server_error() -> StatusCode {
    sentry::Hub::current().configure_scope(|scope| {
        scope.set_tag("stricknani.error_reporting_opt_out", "1");
    });
    StatusCode::INTERNAL_SERVER_ERROR
}

async fn client_error() -> StatusCode {
    StatusCode::BAD_REQUEST
}

#[test]
fn reports_only_sanitized_server_errors_and_keeps_request_opt_out_isolated() {
    let options =
        sentry::ClientOptions::new().before_send(stricknani_server::error_reporting::scrub_event);
    let events = sentry::test::with_captured_events_options(
        || {
            tokio::runtime::Builder::new_current_thread()
                .build()
                .unwrap()
                .block_on(async {
                    let app = Router::new()
                        .route("/items/{item}", get(internal_server_error))
                        .route("/opt-out/{item}", get(opted_out_server_error))
                        .route("/client-error/{item}", get(client_error))
                        .layer(from_fn(
                            stricknani_server::error_reporting::report_http_errors,
                        ))
                        .layer(sentry::integrations::tower::SentryHttpLayer::new())
                        .layer(sentry::integrations::tower::NewSentryLayer::<
                            axum::extract::Request,
                        >::new_from_top());

                    let normal = app.clone().oneshot(request(
                        "GET",
                        "/items/alice%40example.test?token=query-secret",
                        None,
                        b"body-secret".to_vec(),
                    ));
                    let opted_out =
                        app.clone()
                            .oneshot(request("GET", "/opt-out/sample-42", None, vec![]));
                    let client_error =
                        app.oneshot(request("GET", "/client-error/sample-43", None, vec![]));
                    let (normal, opted_out, client_error) =
                        tokio::join!(normal, opted_out, client_error);
                    assert_eq!(normal.unwrap().status(), StatusCode::INTERNAL_SERVER_ERROR);
                    assert_eq!(
                        opted_out.unwrap().status(),
                        StatusCode::INTERNAL_SERVER_ERROR
                    );
                    assert_eq!(client_error.unwrap().status(), StatusCode::BAD_REQUEST);
                });
        },
        options,
    );

    assert_eq!(events.len(), 1);
    let event = &events[0];
    assert_eq!(event.message.as_deref(), Some("http.server.response_error"));
    assert_eq!(
        event.tags.get("http.status_code").map(String::as_str),
        Some("500")
    );
    assert_eq!(
        event.tags.get("http.method").map(String::as_str),
        Some("GET")
    );
    assert_eq!(
        event.tags.get("http.route").map(String::as_str),
        Some("/items/{item}")
    );
    assert!(event.request.is_none());
    let event_text = format!("{event:?}");
    assert!(!event_text.contains("alice@example.test"));
    assert!(!event_text.contains("query-secret"));
    assert!(!event_text.contains("body-secret"));
    assert!(!event_text.contains("sample-42"));
    assert!(!event_text.contains("sample-43"));
}

#[tokio::test]
async fn configured_instance_advertises_the_tunnel_and_the_default() {
    let (_upstream, dsn) = start_upstream().await;
    let f = fixture(Some(&dsn)).await;
    let meta = json(send(&f, request("GET", "/meta", None, vec![])).await).await;
    let info = &meta["data"]["error_reporting"];
    assert_eq!(info["available"], true);
    assert_eq!(info["dsn"], dsn);
    assert_eq!(info["tunnel_path"], "/api/v2/telemetry/sentry");
    assert_eq!(info["enabled"], true);
    let account = json(send(&f, request("GET", "/account", Some(USER_PAT), vec![])).await).await;
    assert_eq!(account["data"]["error_reporting_available"], true);
    assert_eq!(account["data"]["error_reporting_enabled"], true);
}

#[tokio::test]
async fn account_setting_round_trips_and_validates() {
    let (_upstream, dsn) = start_upstream().await;
    let f = fixture(Some(&dsn)).await;
    let response = send(
        &f,
        json_request(
            "PATCH",
            "/account",
            USER_PAT,
            serde_json::json!({"error_reporting_enabled": false}),
        ),
    )
    .await;
    assert_eq!(response.status(), StatusCode::OK);
    assert_eq!(
        json(response).await["data"]["error_reporting_enabled"],
        false
    );
    let stored: i64 = sqlx::query_scalar("SELECT error_reporting FROM users WHERE id = ?")
        .bind(f.user_id)
        .fetch_one(&f.pool)
        .await
        .unwrap();
    assert_eq!(stored, 0);
    let meta = json(send(&f, request("GET", "/meta", Some(USER_PAT), vec![])).await).await;
    assert_eq!(meta["data"]["error_reporting"]["enabled"], false);
    let invalid = send(
        &f,
        json_request(
            "PATCH",
            "/account",
            USER_PAT,
            serde_json::json!({"error_reporting_enabled": "no"}),
        ),
    )
    .await;
    assert_eq!(invalid.status(), StatusCode::BAD_REQUEST);
}

#[tokio::test]
async fn tunnel_forwards_valid_envelopes_to_the_configured_dsn_only() {
    let (upstream, dsn) = start_upstream().await;
    let f = fixture(Some(&dsn)).await;
    let body = envelope(
        &dsn,
        "{\"message\":\"hello bob@example.test\",\"request\":{\"url\":\"https://s.test/x?token=abc\"}}",
    );
    let response = send(&f, request("POST", TUNNEL, Some(USER_PAT), body.clone())).await;
    assert_eq!(response.status(), StatusCode::NO_CONTENT);
    {
        let received = upstream.received.lock().unwrap();
        assert_eq!(received.len(), 1);
        let forwarded = String::from_utf8_lossy(&received[0].1);
        assert!(!forwarded.contains("bob@example.test") && !forwarded.contains("token=abc"));
    }
    // Signed-out callers follow the instance default.
    let anonymous = send(&f, request("POST", TUNNEL, None, body)).await;
    assert_eq!(anonymous.status(), StatusCode::NO_CONTENT);
    assert_eq!(upstream.received.lock().unwrap().len(), 2);

    let wrong = envelope("http://pubkey@127.0.0.1:1/8", "{}");
    let response = send(&f, request("POST", TUNNEL, Some(USER_PAT), wrong)).await;
    assert_eq!(response.status(), StatusCode::FORBIDDEN);
    assert_eq!(upstream.received.lock().unwrap().len(), 2);
    let garbage = send(
        &f,
        request("POST", TUNNEL, Some(USER_PAT), b"nope".to_vec()),
    )
    .await;
    assert_eq!(garbage.status(), StatusCode::BAD_REQUEST);
}

#[tokio::test]
async fn tunnel_accepts_the_android_dsn_path() {
    let (upstream, dsn) = start_upstream().await;
    let f = fixture(Some(&dsn)).await;
    // Android is given a DSN pointing at this server; its SDK posts to
    // `<dsn path>/api/<project>/envelope/`.
    let body = envelope(
        "http://pubkey@knit.example.test/api/v2/telemetry/sentry/7",
        "{}",
    );
    let response = send(
        &f,
        request("POST", "/telemetry/sentry/api/7/envelope/", None, body),
    )
    .await;
    assert_eq!(response.status(), StatusCode::NO_CONTENT);
    assert_eq!(upstream.received.lock().unwrap().len(), 1);
}

#[tokio::test]
async fn tunnel_rejects_oversize_envelopes() {
    let (upstream, dsn) = start_upstream().await;
    let f = fixture(Some(&dsn)).await;
    let big = envelope(
        &dsn,
        &format!("{{\"message\":\"{}\"}}", "a".repeat(MAX_ENVELOPE_BYTES)),
    );
    let response = send(&f, request("POST", TUNNEL, Some(USER_PAT), big)).await;
    assert_eq!(response.status(), StatusCode::PAYLOAD_TOO_LARGE);
    assert!(upstream.received.lock().unwrap().is_empty());
}

#[tokio::test]
async fn tunnel_drops_reports_from_opted_out_users_only() {
    let (upstream, dsn) = start_upstream().await;
    let f = fixture(Some(&dsn)).await;
    sqlx::query("UPDATE users SET error_reporting = 0 WHERE id = ?")
        .bind(f.user_id)
        .execute(&f.pool)
        .await
        .unwrap();
    let body = envelope(&f.dsn, "{\"message\":\"boom\"}");
    let dropped = send(&f, request("POST", TUNNEL, Some(USER_PAT), body.clone())).await;
    assert_eq!(dropped.status(), StatusCode::NO_CONTENT);
    assert!(upstream.received.lock().unwrap().is_empty());
    let kept = send(&f, request("POST", TUNNEL, Some(OTHER_PAT), body)).await;
    assert_eq!(kept.status(), StatusCode::NO_CONTENT);
    assert_eq!(upstream.received.lock().unwrap().len(), 1);
}
