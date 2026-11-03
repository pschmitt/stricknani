use axum::{
    body::Body,
    http::{Request, StatusCode},
};
use http_body_util::BodyExt;
use sqlx::SqlitePool;
use stricknani_server::{api_v2, db};
use tower::ServiceExt;

const HOST: &str = "app.example.test";
const ORIGIN: &str = "https://app.example.test";
const PREVIEW_PATH: &str = "/projects/preview";

async fn fixture() -> (axum::Router, SqlitePool) {
    let pool = db::connect("sqlite::memory:")
        .await
        .expect("in-memory database should migrate");
    let state = db::AppState::new(pool.clone()).with_secure_cookies(true);
    (api_v2::router(state, false), pool)
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

async fn signed_in(app: &axum::Router, pool: &SqlitePool) -> (String, String) {
    db::create_user(
        pool,
        "preview@example.test",
        "preview-password-long".to_owned(),
    )
    .await
    .expect("test user should be created");

    let bootstrap = app
        .clone()
        .oneshot(Request::get("/session").body(Body::empty()).unwrap())
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
            Request::post("/auth/login")
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
                        "email": "preview@example.test",
                        "password": "preview-password-long"
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

fn preview_request(
    session: Option<&str>,
    csrf: Option<&str>,
    origin: &str,
    body: String,
) -> Request<Body> {
    let mut request = Request::post(PREVIEW_PATH)
        .header("host", HOST)
        .header("origin", origin)
        .header("sec-fetch-site", "same-origin")
        .header("content-type", "application/json");
    if let Some(session) = session {
        request = request.header("cookie", format!("__Host-stricknani_session={session}"));
    }
    if let Some(csrf) = csrf {
        request = request.header("x-csrf-token", csrf);
    }
    request.body(Body::from(body)).unwrap()
}

#[tokio::test]
async fn project_preview_requires_session_origin_and_csrf_before_body_processing() {
    let (app, pool) = fixture().await;
    let oversized_body = format!("{{\"description\":\"{}\"}}", "x".repeat(2 * 1024 * 1024));

    let unauthenticated = app
        .clone()
        .oneshot(preview_request(None, None, ORIGIN, oversized_body.clone()))
        .await
        .unwrap();
    assert_eq!(unauthenticated.status(), StatusCode::UNAUTHORIZED);

    let (session, csrf) = signed_in(&app, &pool).await;
    let bad_origin = app
        .clone()
        .oneshot(preview_request(
            Some(&session),
            Some(&csrf),
            "https://attacker.example",
            "{}".to_owned(),
        ))
        .await
        .unwrap();
    assert_eq!(bad_origin.status(), StatusCode::FORBIDDEN);

    let bad_csrf = app
        .oneshot(preview_request(
            Some(&session),
            Some("invalid"),
            ORIGIN,
            "{}".to_owned(),
        ))
        .await
        .unwrap();
    assert_eq!(bad_csrf.status(), StatusCode::FORBIDDEN);
}

#[tokio::test]
async fn project_preview_sanitizes_markdown_and_preserves_null_and_empty_fields() {
    let (app, pool) = fixture().await;
    let (session, csrf) = signed_in(&app, &pool).await;
    let response = app
        .clone()
        .oneshot(preview_request(
            Some(&session),
            Some(&csrf),
            ORIGIN,
            serde_json::json!({
                "description": "**safe**\n\n<script>alert(1)</script>\n\n[x](javascript:alert(2))",
                "notes": ""
            })
            .to_string(),
        ))
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    assert_eq!(response.headers()["cache-control"], "no-store");
    let body = json(response).await;
    let description = body["data"]["description_html"].as_str().unwrap();
    assert!(description.contains("<strong>safe</strong>"));
    assert!(!description.contains("<script"));
    assert!(!description.contains("javascript:"));
    assert_eq!(body["data"]["notes_html"], "");

    let response = app
        .oneshot(preview_request(
            Some(&session),
            Some(&csrf),
            ORIGIN,
            serde_json::json!({"description": null}).to_string(),
        ))
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    let body = json(response).await;
    assert!(body["data"]["description_html"].is_null());
    assert!(body["data"]["notes_html"].is_null());
}

#[tokio::test]
async fn project_preview_rejects_unknown_and_wrongly_typed_fields_and_caps_body() {
    let (app, pool) = fixture().await;
    let (session, csrf) = signed_in(&app, &pool).await;

    for body in [
        r#"{"description":"ok","unexpected":"field"}"#,
        r#"{"description":12}"#,
        r#"{"notes":false}"#,
    ] {
        let response = app
            .clone()
            .oneshot(preview_request(
                Some(&session),
                Some(&csrf),
                ORIGIN,
                body.to_owned(),
            ))
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::UNPROCESSABLE_ENTITY);
        assert_eq!(json(response).await["error"]["code"], "validation_error");
    }

    let oversized_body = format!("{{\"description\":\"{}\"}}", "x".repeat(2 * 1024 * 1024));
    let response = app
        .oneshot(preview_request(
            Some(&session),
            Some(&csrf),
            ORIGIN,
            oversized_body,
        ))
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::PAYLOAD_TOO_LARGE);
    assert_eq!(json(response).await["error"]["code"], "payload_too_large");
}

#[tokio::test]
async fn project_preview_rate_limit_is_bounded_and_returns_retry_after() {
    let (app, pool) = fixture().await;
    let (session, csrf) = signed_in(&app, &pool).await;
    for _ in 0..20 {
        let response = app
            .clone()
            .oneshot(preview_request(
                Some(&session),
                Some(&csrf),
                ORIGIN,
                "{}".to_owned(),
            ))
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::OK);
    }
    let limited = app
        .oneshot(preview_request(
            Some(&session),
            Some(&csrf),
            ORIGIN,
            "{}".to_owned(),
        ))
        .await
        .unwrap();
    assert_eq!(limited.status(), StatusCode::TOO_MANY_REQUESTS);
    assert!(limited.headers().contains_key("retry-after"));
    assert_eq!(json(limited).await["error"]["code"], "rate_limited");
}
