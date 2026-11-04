use std::sync::Arc;

use axum::{Router, http::StatusCode};
use base64::{Engine, engine::general_purpose::URL_SAFE_NO_PAD};
use serde_json::{Value, json};
use sha2::{Digest, Sha256};
use sqlx::{Row, SqlitePool};
use stricknani_server::{api_v2, db, oidc::OidcProvider, oidc_http_state::OidcRuntime};
use tower::ServiceExt;

mod common;

use common::oidc::MockOidcIssuer;

async fn app_with_mock(pool: SqlitePool) -> (Router, Arc<MockOidcIssuer>, SqlitePool) {
    let mock = Arc::new(MockOidcIssuer::start().await);
    let provider = Arc::new(
        OidcProvider::discover(mock.provider_config(vec!["stricknani://oidc-callback".into()]))
            .await
            .unwrap(),
    );
    let runtime = Arc::new(OidcRuntime::new(provider));
    let state = db::AppState::new(pool.clone())
        .with_secure_cookies(true)
        .with_oidc(runtime);
    (api_v2::router(state, false), mock, pool)
}

async fn pool() -> SqlitePool {
    db::connect("sqlite::memory:")
        .await
        .expect("test DB migrates")
}

async fn start_browser(app: &Router, return_to: &str) -> (url::Url, String) {
    let bootstrap = app
        .clone()
        .oneshot(
            axum::http::Request::builder()
                .uri("/session")
                .body(axum::body::Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();
    let csrf_cookie = bootstrap
        .headers()
        .get_all("set-cookie")
        .iter()
        .find_map(|value| {
            let value = value.to_str().ok()?;
            value
                .starts_with("__Host-stricknani_v2_login_csrf=")
                .then(|| value.split(';').next().unwrap().to_owned())
        })
        .unwrap();
    let body: Value = serde_json::from_slice(
        &axum::body::to_bytes(bootstrap.into_body(), 16 * 1024)
            .await
            .unwrap(),
    )
    .unwrap();
    let start = app
        .clone()
        .oneshot(
            axum::http::Request::builder()
                .method("POST")
                .uri("/auth/oidc/start")
                .header("content-type", "application/json")
                .header("origin", "https://app.example.test")
                .header("host", "app.example.test")
                .header("cookie", csrf_cookie)
                .header("x-csrf-token", body["data"]["csrf_token"].as_str().unwrap())
                .body(axum::body::Body::from(
                    json!({
                        "provider": "authelia",
                        "client": "browser",
                        "return_to": return_to
                    })
                    .to_string(),
                ))
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(start.status(), StatusCode::OK);
    let flow_cookie = start
        .headers()
        .get("set-cookie")
        .unwrap()
        .to_str()
        .unwrap()
        .split(';')
        .next()
        .unwrap()
        .to_owned();
    let body: Value = serde_json::from_slice(
        &axum::body::to_bytes(start.into_body(), 16 * 1024)
            .await
            .unwrap(),
    )
    .unwrap();
    (
        url::Url::parse(body["data"]["authorization_url"].as_str().unwrap()).unwrap(),
        flow_cookie,
    )
}

async fn start_native(app: &Router, verifier: &str, app_state: &str) -> url::Url {
    let challenge = URL_SAFE_NO_PAD.encode(Sha256::digest(verifier.as_bytes()));
    let response = app
        .clone()
        .oneshot(
            axum::http::Request::builder()
                .method("POST")
                .uri("/auth/oidc/start")
                .header("content-type", "application/json")
                .body(axum::body::Body::from(
                    json!({
                        "provider": "authelia",
                        "client": "android",
                        "redirect_uri": "stricknani://oidc-callback",
                        "app_state": app_state,
                        "code_challenge": challenge,
                        "code_challenge_method": "S256"
                    })
                    .to_string(),
                ))
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    assert!(response.headers().get("set-cookie").is_none());
    let body: Value = serde_json::from_slice(
        &axum::body::to_bytes(response.into_body(), 16 * 1024)
            .await
            .unwrap(),
    )
    .unwrap();
    url::Url::parse(body["data"]["authorization_url"].as_str().unwrap()).unwrap()
}

async fn callback(
    app: &Router,
    issuer: &MockOidcIssuer,
    authorization_url: &url::Url,
    subject: &str,
    email: &str,
    flow_cookie: Option<&str>,
) -> axum::response::Response {
    issuer
        .prepare_callback(authorization_url, subject, email)
        .await;
    let state = authorization_state(authorization_url);
    let mut target =
        url::Url::parse("https://app.example.test/auth/oidc/callback/authelia").unwrap();
    target
        .query_pairs_mut()
        .append_pair("code", "mock-code")
        .append_pair("state", &state)
        .append_pair("iss", issuer.issuer());
    let mut request = axum::http::Request::builder().uri(format!(
        "{}{}",
        target.path(),
        target.query().map(|q| format!("?{q}")).unwrap_or_default()
    ));
    if let Some(cookie) = flow_cookie {
        request = request.header("cookie", cookie);
    }
    app.clone()
        .oneshot(request.body(axum::body::Body::empty()).unwrap())
        .await
        .unwrap()
}

async fn seed_identity(pool: &SqlitePool, issuer: &str, subject: &str, active: bool) -> i64 {
    let user_id = db::create_user(pool, "oidc-user@example.test", "test-password-123".into())
        .await
        .unwrap();
    if !active {
        sqlx::query("UPDATE users SET active = 0 WHERE id = ?")
            .bind(user_id)
            .execute(pool)
            .await
            .unwrap();
    }
    sqlx::query(
        "INSERT INTO oidc_identities (user_id, provider_id, issuer, subject, verified_email, linked_at) \
         VALUES (?, 'authelia', ?, ?, 'oidc-user@example.test', unixepoch())",
    )
    .bind(user_id)
    .bind(issuer)
    .bind(subject)
    .execute(pool)
    .await
    .unwrap();
    user_id
}

fn authorization_state(url: &url::Url) -> String {
    url.query_pairs()
        .find(|(key, _)| key == "state")
        .unwrap()
        .1
        .into_owned()
}

async fn native_grant(
    app: &Router,
    issuer: &MockOidcIssuer,
    verifier: &str,
    app_state: &str,
    subject: &str,
) -> (String, url::Url) {
    let authorization_url = start_native(app, verifier, app_state).await;
    let response = callback(
        app,
        issuer,
        &authorization_url,
        subject,
        "oidc-user@example.test",
        None,
    )
    .await;
    assert_eq!(response.status(), StatusCode::SEE_OTHER);
    assert!(response.headers().get("set-cookie").is_none());
    let location = response.headers()["location"].to_str().unwrap();
    let redirect = url::Url::parse(location).unwrap();
    let query: std::collections::HashMap<String, String> =
        redirect.query_pairs().into_owned().collect();
    assert_eq!(query.get("state").map(String::as_str), Some(app_state));
    assert_eq!(query.get("flow").map(String::as_str), Some("login"));
    let code = query
        .get("code")
        .expect("successful callback returns grant")
        .clone();
    (code, redirect)
}

fn request_exchange(
    code: &str,
    verifier: &str,
    redirect_uri: &str,
) -> axum::http::Request<axum::body::Body> {
    request_exchange_with(code, verifier, redirect_uri, json!({}))
}

fn request_exchange_with(
    code: &str,
    verifier: &str,
    redirect_uri: &str,
    extra: Value,
) -> axum::http::Request<axum::body::Body> {
    let mut payload = json!({
        "code": code,
        "code_verifier": verifier,
        "redirect_uri": redirect_uri,
        "token_name": "HTTP OIDC test"
    });
    for (key, value) in extra.as_object().unwrap() {
        payload[key] = value.clone();
    }
    axum::http::Request::builder()
        .method("POST")
        .uri("/auth/oidc/exchange")
        .header("content-type", "application/json")
        .body(axum::body::Body::from(payload.to_string()))
        .unwrap()
}

#[tokio::test]
async fn native_start_is_direct_and_callback_error_returns_only_the_allowlisted_app_uri() {
    let (app, issuer, _) = app_with_mock(pool().await).await;
    let response = app
        .clone()
        .oneshot(
            axum::http::Request::builder()
                .method("POST")
                .uri("/auth/oidc/start")
                .header("content-type", "application/json")
                .body(axum::body::Body::from(
                    json!({
                        "provider": "authelia",
                        "client": "android",
                        "redirect_uri": "stricknani://oidc-callback",
                        "app_state": "test-state_1",
                        "code_challenge": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                        "code_challenge_method": "S256"
                    })
                    .to_string(),
                ))
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    assert!(response.headers().get("set-cookie").is_none());
    let body: Value = serde_json::from_slice(
        &axum::body::to_bytes(response.into_body(), 16 * 1024)
            .await
            .unwrap(),
    )
    .unwrap();
    let authorization_url = body["data"]["authorization_url"].as_str().unwrap();
    let authorization_url = url::Url::parse(authorization_url).unwrap();
    assert_eq!(
        authorization_url.origin().ascii_serialization(),
        url::Url::parse(issuer.issuer())
            .unwrap()
            .origin()
            .ascii_serialization()
    );
    let provider_state = authorization_url
        .query_pairs()
        .find(|(key, _)| key == "state")
        .unwrap()
        .1
        .into_owned();
    let callback_uri = format!(
        "/auth/oidc/callback/authelia?state={provider_state}&iss={}&error=access_denied",
        issuer.issuer()
    );
    let response = app
        .clone()
        .oneshot(
            axum::http::Request::builder()
                .uri(&callback_uri)
                .body(axum::body::Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::SEE_OTHER);
    let location = response
        .headers()
        .get("location")
        .unwrap()
        .to_str()
        .unwrap();
    let callback = url::Url::parse(location).unwrap();
    assert_eq!(callback.scheme(), "stricknani");
    assert_eq!(callback.host_str(), Some("oidc-callback"));
    let query: std::collections::HashMap<String, String> =
        callback.query_pairs().into_owned().collect();
    assert_eq!(query.get("state").map(String::as_str), Some("test-state_1"));
    assert_eq!(query.get("flow").map(String::as_str), Some("login"));
    assert_eq!(query.get("error").map(String::as_str), Some("oidc_failed"));
    assert!(!location.contains("access_denied"));
    assert_eq!(issuer.token_calls(), 1);
    assert_eq!(
        app.oneshot(
            axum::http::Request::builder()
                .uri(callback_uri)
                .body(axum::body::Body::empty())
                .unwrap(),
        )
        .await
        .unwrap()
        .status(),
        StatusCode::BAD_REQUEST,
        "the provider error consumes callback state"
    );
}

#[tokio::test]
async fn browser_start_requires_anonymous_csrf_and_binds_callback_to_secure_cookie() {
    let (app, issuer, _) = app_with_mock(pool().await).await;
    let bootstrap = app
        .clone()
        .oneshot(
            axum::http::Request::builder()
                .uri("/session")
                .body(axum::body::Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(bootstrap.status(), StatusCode::OK);
    let csrf_cookie = bootstrap
        .headers()
        .get_all("set-cookie")
        .iter()
        .find_map(|value| {
            let value = value.to_str().ok()?;
            value
                .starts_with("__Host-stricknani_v2_login_csrf=")
                .then(|| value.split(';').next().unwrap().to_owned())
        })
        .unwrap();
    let bootstrap_body: Value = serde_json::from_slice(
        &axum::body::to_bytes(bootstrap.into_body(), 16 * 1024)
            .await
            .unwrap(),
    )
    .unwrap();
    let csrf = bootstrap_body["data"]["csrf_token"].as_str().unwrap();
    let denied = app
        .clone()
        .oneshot(
            axum::http::Request::builder()
                .method("POST")
                .uri("/auth/oidc/start")
                .header("content-type", "application/json")
                .body(axum::body::Body::from(
                    json!({ "provider": "authelia", "client": "browser" }).to_string(),
                ))
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(denied.status(), StatusCode::FORBIDDEN);

    let start = app
        .clone()
        .oneshot(
            axum::http::Request::builder()
                .method("POST")
                .uri("/auth/oidc/start")
                .header("content-type", "application/json")
                .header("origin", "https://app.example.test")
                .header("host", "app.example.test")
                .header("cookie", csrf_cookie)
                .header("x-csrf-token", csrf)
                .body(axum::body::Body::from(
                    json!({
                        "provider": "authelia",
                        "client": "browser",
                        "return_to": "/app/account?tab=identities"
                    })
                    .to_string(),
                ))
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(start.status(), StatusCode::OK);
    let flow_cookie = start
        .headers()
        .get("set-cookie")
        .unwrap()
        .to_str()
        .unwrap()
        .to_owned();
    assert!(flow_cookie.contains("HttpOnly"));
    assert!(flow_cookie.contains("Secure"));
    assert!(flow_cookie.contains("SameSite=Lax"));
    let flow_cookie = flow_cookie.split(';').next().unwrap();
    let start_body: Value = serde_json::from_slice(
        &axum::body::to_bytes(start.into_body(), 16 * 1024)
            .await
            .unwrap(),
    )
    .unwrap();
    let authorize =
        url::Url::parse(start_body["data"]["authorization_url"].as_str().unwrap()).unwrap();
    let provider_state = authorize
        .query_pairs()
        .find(|(key, _)| key == "state")
        .unwrap()
        .1
        .into_owned();
    let response = app
        .clone()
        .oneshot(
            axum::http::Request::builder()
                .uri(format!(
                    "/auth/oidc/callback/authelia?state={provider_state}&iss={}&error=access_denied",
                    issuer.issuer()
                ))
                .header("cookie", flow_cookie)
                .body(axum::body::Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::SEE_OTHER);
    assert_eq!(response.headers()["referrer-policy"], "no-referrer");
    assert_eq!(response.headers()["cache-control"], "no-store");
    let location = response.headers()["location"].to_str().unwrap();
    assert_eq!(location, "/app/account?tab=identities&oidc_error=failed");
    assert!(!location.contains("access_denied"));
}

#[tokio::test]
async fn disabled_oidc_does_not_advertise_or_start_a_provider() {
    let app = api_v2::router(db::AppState::new(pool().await), false);
    let response = app
        .oneshot(
            axum::http::Request::builder()
                .method("POST")
                .uri("/auth/oidc/start")
                .header("content-type", "application/json")
                .body(axum::body::Body::from(
                    json!({ "provider": "authelia", "client": "android" }).to_string(),
                ))
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::NOT_FOUND);
    let body: Value = serde_json::from_slice(
        &axum::body::to_bytes(response.into_body(), 16 * 1024)
            .await
            .unwrap(),
    )
    .unwrap();
    assert_eq!(body["error"]["code"], "oidc_disabled");
}

#[tokio::test]
async fn signed_browser_callback_sets_a_finite_session_and_clean_redirect() {
    let database = pool().await;
    let (app, issuer, database) = app_with_mock(database).await;
    let user_id = seed_identity(&database, issuer.issuer(), "browser-subject", true).await;
    let (authorization_url, flow_cookie) = start_browser(&app, "/app/account?tab=security").await;

    let response = callback(
        &app,
        &issuer,
        &authorization_url,
        "browser-subject",
        "oidc-user@example.test",
        Some(&flow_cookie),
    )
    .await;
    assert_eq!(response.status(), StatusCode::SEE_OTHER);
    assert_eq!(response.headers()["location"], "/app/account?tab=security");
    assert_eq!(response.headers()["referrer-policy"], "no-referrer");
    assert_eq!(response.headers()["cache-control"], "no-store");
    let cookies: Vec<String> = response
        .headers()
        .get_all("set-cookie")
        .iter()
        .map(|value| value.to_str().unwrap().to_owned())
        .collect();
    let session_cookie = cookies
        .iter()
        .find(|cookie| cookie.starts_with("__Host-stricknani_session="))
        .expect("successful login sets a session cookie");
    assert!(session_cookie.contains("; Secure"));
    assert!(cookies.iter().any(|cookie| {
        cookie.starts_with("__Host-stricknani_v2_oidc_flow=") && cookie.contains("Max-Age=0")
    }));
    assert!(
        !response.headers()["location"]
            .to_str()
            .unwrap()
            .contains("oidc_error")
    );

    let token: (String, String, i64, i64, Option<i64>, Option<String>) = sqlx::query_as(
        "SELECT auth_origin, purpose, created_at, expires_at, oidc_identity_id, oidc_provider_id \
         FROM api_tokens WHERE user_id = ? AND purpose = 'browser'",
    )
    .bind(user_id)
    .fetch_one(&database)
    .await
    .unwrap();
    assert_eq!(token.0, "oidc");
    assert_eq!(token.1, "browser");
    assert!(token.2 < token.3 && token.3 <= token.2 + 14 * 24 * 60 * 60);
    assert!(token.4.is_some());
    assert_eq!(token.5.as_deref(), Some("authelia"));
    assert_eq!(issuer.token_calls(), 1);
}

#[tokio::test]
async fn native_signed_callback_mints_finite_pat_and_bad_exchange_attempts_burn_grants() {
    let database = pool().await;
    let (app, issuer, database) = app_with_mock(database).await;
    let user_id = seed_identity(&database, issuer.issuer(), "native-subject", true).await;
    let verifier = "native-code-verifier-0123456789abcdef0123456789abcdef";

    let (wrong_verifier_code, _) = native_grant(
        &app,
        &issuer,
        verifier,
        "wrong-verifier-state",
        "native-subject",
    )
    .await;
    let wrong_verifier = app
        .clone()
        .oneshot(request_exchange(
            &wrong_verifier_code,
            "incorrect-native-code-verifier-0123456789abcdef0123456789",
            "stricknani://oidc-callback",
        ))
        .await
        .unwrap();
    assert_eq!(wrong_verifier.status(), StatusCode::BAD_REQUEST);
    assert_eq!(
        app.clone()
            .oneshot(request_exchange(
                &wrong_verifier_code,
                verifier,
                "stricknani://oidc-callback",
            ))
            .await
            .unwrap()
            .status(),
        StatusCode::BAD_REQUEST,
        "a failed verifier attempt consumes the grant"
    );

    let (wrong_redirect_code, _) = native_grant(
        &app,
        &issuer,
        verifier,
        "wrong-redirect-state",
        "native-subject",
    )
    .await;
    assert_eq!(
        app.clone()
            .oneshot(request_exchange(
                &wrong_redirect_code,
                verifier,
                "stricknani://attacker-callback",
            ))
            .await
            .unwrap()
            .status(),
        StatusCode::BAD_REQUEST
    );
    assert_eq!(
        app.clone()
            .oneshot(request_exchange(
                &wrong_redirect_code,
                verifier,
                "stricknani://oidc-callback",
            ))
            .await
            .unwrap()
            .status(),
        StatusCode::BAD_REQUEST,
        "a failed redirect attempt consumes the grant"
    );

    let (code, _) = native_grant(
        &app,
        &issuer,
        verifier,
        "successful-state",
        "native-subject",
    )
    .await;
    let response = app
        .clone()
        .oneshot(request_exchange(
            &code,
            verifier,
            "stricknani://oidc-callback",
        ))
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    let body: Value = serde_json::from_slice(
        &axum::body::to_bytes(response.into_body(), 16 * 1024)
            .await
            .unwrap(),
    )
    .unwrap();
    assert_eq!(body["data"]["auth_origin"], "oidc");
    let raw_token = body["data"]["token"].as_str().unwrap();
    assert!(!raw_token.is_empty());
    assert_eq!(
        app.oneshot(request_exchange(
            &code,
            verifier,
            "stricknani://oidc-callback",
        ))
        .await
        .unwrap()
        .status(),
        StatusCode::BAD_REQUEST,
        "a successful exchange consumes the grant"
    );

    let token = sqlx::query(
        "SELECT auth_origin, purpose, token_hash, created_at, expires_at, oidc_identity_id, oidc_provider_id \
         FROM api_tokens WHERE user_id = ? AND purpose = 'api'",
    )
    .bind(user_id)
    .fetch_one(&database)
    .await
    .unwrap();
    assert_eq!(token.get::<String, _>("auth_origin"), "oidc");
    assert_eq!(token.get::<String, _>("purpose"), "api");
    assert_ne!(token.get::<String, _>("token_hash"), raw_token);
    let created_at: i64 = token.get("created_at");
    let expires_at: i64 = token.get("expires_at");
    assert!(created_at < expires_at && expires_at <= created_at + 7 * 24 * 60 * 60);
    assert!(token.get::<Option<i64>, _>("oidc_identity_id").is_some());
    assert_eq!(
        token
            .get::<Option<String>, _>("oidc_provider_id")
            .as_deref(),
        Some("authelia")
    );
    assert_eq!(issuer.token_calls(), 3);
}

#[tokio::test]
async fn unknown_and_inactive_identities_have_the_same_neutral_result_without_tokens() {
    let database = pool().await;
    let (app, issuer, database) = app_with_mock(database).await;
    seed_identity(&database, issuer.issuer(), "inactive-subject", false).await;

    let unknown_result = browser_callback_result(&app, &issuer, "unknown-subject").await;
    let inactive_result = browser_callback_result(&app, &issuer, "inactive-subject").await;
    assert_eq!(unknown_result, "/app/login?oidc_error=unlinked");
    assert_eq!(inactive_result, unknown_result);
    assert_eq!(issuer.token_calls(), 2);
    let token_count: i64 =
        sqlx::query_scalar("SELECT count(*) FROM api_tokens WHERE auth_origin = 'oidc'")
            .fetch_one(&database)
            .await
            .unwrap();
    assert_eq!(token_count, 0);
}

async fn browser_callback_result(app: &Router, issuer: &MockOidcIssuer, subject: &str) -> String {
    let (authorization_url, flow_cookie) = start_browser(app, "/app/login").await;
    let response = callback(
        app,
        issuer,
        &authorization_url,
        subject,
        "oidc-user@example.test",
        Some(&flow_cookie),
    )
    .await;
    assert_eq!(response.status(), StatusCode::SEE_OTHER);
    assert!(
        response
            .headers()
            .get_all("set-cookie")
            .iter()
            .all(|cookie| !cookie
                .to_str()
                .unwrap()
                .starts_with("__Host-stricknani_session="))
    );
    response.headers()["location"].to_str().unwrap().to_owned()
}

#[tokio::test]
async fn native_exchange_names_the_token_after_device_name_and_limits_its_length() {
    let database = pool().await;
    let (app, issuer, database) = app_with_mock(database).await;
    let user_id = seed_identity(&database, issuer.issuer(), "device-subject", true).await;
    let verifier = "native-code-verifier-0123456789abcdef0123456789abcdef";
    let redirect = "stricknani://oidc-callback";

    // Too long: rejected as a validation error.
    let (code, _) = native_grant(&app, &issuer, verifier, "device-long", "device-subject").await;
    let response = app
        .clone()
        .oneshot(request_exchange_with(
            &code,
            verifier,
            redirect,
            json!({"device_name": "d".repeat(101)}),
        ))
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::UNPROCESSABLE_ENTITY);

    let (code, _) = native_grant(&app, &issuer, verifier, "device-ok", "device-subject").await;
    let response = app
        .clone()
        .oneshot(request_exchange_with(
            &code,
            verifier,
            redirect,
            json!({"device_name": "  Pixel 9  "}),
        ))
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    let name: String = sqlx::query_scalar(
        "SELECT name FROM api_tokens WHERE user_id = ? AND purpose = 'api' ORDER BY id DESC",
    )
    .bind(user_id)
    .fetch_one(&database)
    .await
    .unwrap();
    assert_eq!(name, "Android \u{b7} Pixel 9");

    // Without device_name the supplied token_name still applies.
    let (code, _) = native_grant(&app, &issuer, verifier, "device-none", "device-subject").await;
    let response = app
        .clone()
        .oneshot(request_exchange(&code, verifier, redirect))
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    let name: String = sqlx::query_scalar(
        "SELECT name FROM api_tokens WHERE user_id = ? AND purpose = 'api' ORDER BY id DESC",
    )
    .bind(user_id)
    .fetch_one(&database)
    .await
    .unwrap();
    assert_eq!(name, "HTTP OIDC test");
}
