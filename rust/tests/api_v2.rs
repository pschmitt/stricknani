use axum::{
    body::Body,
    http::{Request, StatusCode},
};
use http_body_util::BodyExt;
use sha2::{Digest, Sha256};
use sqlx::SqlitePool;
use stricknani_server::{api_v2, app_with_state, db};
use tower::ServiceExt;

const EMAIL: &str = "browser@example.test";
const PASSWORD: &str = "a-long-browser-password";
const HOST: &str = "app.example.test";
const ORIGIN: &str = "https://app.example.test";

async fn fixture() -> (axum::Router, SqlitePool) {
    let pool = db::connect("sqlite::memory:")
        .await
        .expect("in-memory database should migrate");
    db::create_user(&pool, EMAIL, PASSWORD.to_owned())
        .await
        .expect("user should be created");
    let state = db::AppState::new(pool.clone())
        .with_secure_cookies(true)
        .with_signup_enabled(true)
        .with_external_imports(true)
        .with_archive_enabled(true)
        .with_ocr_enabled(true);
    (api_v2::router(state, true), pool)
}

async fn body(response: axum::response::Response) -> String {
    String::from_utf8(
        response
            .into_body()
            .collect()
            .await
            .expect("response body should be readable")
            .to_bytes()
            .to_vec(),
    )
    .expect("response should be UTF-8")
}

fn cookie_value(response: &axum::response::Response, prefix: &str) -> String {
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

async fn bootstrap(app: &axum::Router) -> (String, String) {
    let response = app
        .clone()
        .oneshot(
            Request::get("/session")
                .header("accept-language", "de")
                .body(Body::empty())
                .expect("request is valid"),
        )
        .await
        .expect("router should respond");
    assert_eq!(response.status(), StatusCode::OK);
    assert_eq!(response.headers()["cache-control"], "no-store");
    let cookie = cookie_value(&response, "__Host-stricknani_v2_login_csrf=");
    let csrf = serde_json::from_str::<serde_json::Value>(&body(response).await)
        .expect("body is JSON")["data"]["csrf_token"]
        .as_str()
        .expect("anonymous bootstrap includes a CSRF token")
        .to_owned();
    (cookie, csrf)
}

fn login_request(
    cookie: &str,
    csrf: &str,
    email: &str,
    password: &str,
    origin: &str,
) -> Request<Body> {
    Request::post("/auth/login")
        .header("host", HOST)
        .header("origin", origin)
        .header("sec-fetch-site", "same-origin")
        .header(
            "cookie",
            format!("__Host-stricknani_v2_login_csrf={cookie}"),
        )
        .header("x-csrf-token", csrf)
        .header("content-type", "application/json")
        .body(Body::from(
            serde_json::json!({"email":email,"password":password}).to_string(),
        ))
        .expect("request is valid")
}

fn logout_request(session: &str, csrf: &str) -> Request<Body> {
    Request::post("/auth/logout")
        .header("host", HOST)
        .header("origin", ORIGIN)
        .header("sec-fetch-site", "same-origin")
        .header("cookie", format!("__Host-stricknani_session={session}"))
        .header("x-csrf-token", csrf)
        .header("content-type", "application/json")
        .body(Body::from("{}"))
        .expect("request is valid")
}

#[tokio::test]
async fn anonymous_bootstrap_sets_signed_csrf_cookie_and_reports_real_capabilities() {
    let (app, _) = fixture().await;
    let response = app
        .oneshot(
            Request::get("/session")
                .body(Body::empty())
                .expect("request is valid"),
        )
        .await
        .expect("router should respond");

    assert_eq!(response.status(), StatusCode::OK);
    assert_eq!(response.headers()["cache-control"], "no-store");
    let set_cookie = response.headers()["set-cookie"]
        .to_str()
        .expect("cookie is text");
    assert!(set_cookie.starts_with("__Host-stricknani_v2_login_csrf="));
    assert!(set_cookie.contains("; Path=/"));
    assert!(set_cookie.contains("; HttpOnly"));
    assert!(set_cookie.contains("; SameSite=Lax"));
    assert!(set_cookie.contains("; Secure"));
    assert!(!set_cookie.to_ascii_lowercase().contains("domain="));
    let json: serde_json::Value =
        serde_json::from_str(&body(response).await).expect("JSON response");
    assert!(json["data"]["user"].is_null());
    assert_eq!(json["data"]["locale"], "de");
    assert_eq!(json["data"]["capabilities"]["signup"], true);
    assert_eq!(json["data"]["capabilities"]["external_imports"], true);
    assert_eq!(json["data"]["capabilities"]["ai"], false);
    assert_eq!(json["data"]["capabilities"]["ocr"], false);
    assert_eq!(json["data"]["capabilities"]["archive"], true);
    for capability in [
        "media_upload",
        "media_delete",
        "import_preview",
        "archive_state",
        "account_actions",
        "admin_actions",
    ] {
        assert_eq!(json["data"]["capabilities"][capability], false);
    }
}

#[tokio::test]
async fn login_creates_limited_browser_session_without_reflecting_secret_and_logout_revokes_it() {
    let (app, pool) = fixture().await;
    let (csrf_cookie, csrf) = bootstrap(&app).await;
    let response = app
        .clone()
        .oneshot(login_request(&csrf_cookie, &csrf, EMAIL, PASSWORD, ORIGIN))
        .await
        .expect("router should respond");

    assert_eq!(response.status(), StatusCode::OK);
    assert_eq!(response.headers()["cache-control"], "no-store");
    let set_cookies = response
        .headers()
        .get_all("set-cookie")
        .iter()
        .filter_map(|value| value.to_str().ok().map(str::to_owned))
        .collect::<Vec<_>>();
    let session_cookie = set_cookies
        .iter()
        .find(|value| value.starts_with("__Host-stricknani_session="))
        .expect("browser session cookie should be set");
    assert!(session_cookie.contains("; Path=/"));
    assert!(session_cookie.contains("; HttpOnly"));
    assert!(session_cookie.contains("; SameSite=Lax"));
    assert!(session_cookie.contains("; Secure"));
    assert!(!session_cookie.to_ascii_lowercase().contains("domain="));
    let session_token = session_cookie
        .strip_prefix("__Host-stricknani_session=")
        .and_then(|value| value.split(';').next())
        .expect("session token in cookie")
        .to_owned();
    let response_body = body(response).await;
    assert!(!response_body.contains(&session_token));
    assert!(!response_body.contains("sna_"));
    let data: serde_json::Value = serde_json::from_str(&response_body).expect("JSON response");
    assert_eq!(data["data"]["user"]["email"], EMAIL);
    assert_eq!(data["data"]["user"]["is_admin"], false);
    assert_eq!(data["data"]["capabilities"]["account_actions"], true);
    assert_eq!(data["data"]["capabilities"]["admin_actions"], false);
    assert_eq!(data["data"]["capabilities"]["archive_state"], true);
    assert_eq!(data["data"]["capabilities"]["media_upload"], false);
    assert_eq!(data["data"]["capabilities"]["media_delete"], false);
    assert_eq!(data["data"]["capabilities"]["import_preview"], false);

    let session = app
        .clone()
        .oneshot(
            Request::get("/session")
                .header(
                    "cookie",
                    format!("__Host-stricknani_session={session_token}"),
                )
                .body(Body::empty())
                .expect("request is valid"),
        )
        .await
        .expect("router should respond");
    let session: serde_json::Value =
        serde_json::from_str(&body(session).await).expect("session should return JSON");
    assert_eq!(
        session["data"]["capabilities"],
        data["data"]["capabilities"]
    );

    let (purpose, expiry): (String, Option<i64>) =
        sqlx::query_as("SELECT purpose, expires_at FROM api_tokens WHERE token_hash = ?")
            .bind(hex::encode(Sha256::digest(session_token.as_bytes())))
            .fetch_one(&pool)
            .await
            .expect("session token row should exist");
    assert_eq!(purpose, "browser");
    assert!(expiry.is_some());

    let logout = app
        .clone()
        .oneshot(logout_request(
            &session_token,
            data["data"]["csrf_token"].as_str().expect("CSRF token"),
        ))
        .await
        .expect("router should respond");
    assert_eq!(logout.status(), StatusCode::NO_CONTENT);
    assert_eq!(logout.headers()["cache-control"], "no-store");
    assert!(
        logout.headers()["set-cookie"]
            .to_str()
            .expect("cookie is text")
            .contains("Max-Age=0")
    );
    let revoked: Option<i64> =
        sqlx::query_scalar("SELECT revoked_at FROM api_tokens WHERE token_hash = ?")
            .bind(hex::encode(Sha256::digest(session_token.as_bytes())))
            .fetch_one(&pool)
            .await
            .expect("session row remains for audit");
    assert!(revoked.is_some());
}

#[tokio::test]
async fn action_capabilities_require_an_active_browser_session_and_admin_role() {
    let (app, pool) = fixture().await;
    let token = "sna_capability_api_only_fixture";
    sqlx::query(
        "INSERT INTO api_tokens (user_id,name,token_hash,created_at,purpose) \
         VALUES (1,'API fixture',?,unixepoch(),'api')",
    )
    .bind(hex::encode(Sha256::digest(token.as_bytes())))
    .execute(&pool)
    .await
    .expect("API-only token should insert");

    let invalid_cookie = app
        .clone()
        .oneshot(
            Request::get("/session")
                .header("cookie", "__Host-stricknani_session=invalid-session")
                .body(Body::empty())
                .expect("request is valid"),
        )
        .await
        .expect("router should respond");
    let invalid_cookie: serde_json::Value =
        serde_json::from_str(&body(invalid_cookie).await).expect("JSON response");
    assert!(invalid_cookie["data"]["user"].is_null());
    for capability in [
        "media_upload",
        "media_delete",
        "import_preview",
        "archive_state",
        "account_actions",
        "admin_actions",
    ] {
        assert_eq!(invalid_cookie["data"]["capabilities"][capability], false);
    }

    let bearer_only = app
        .clone()
        .oneshot(
            Request::get("/session")
                .header("authorization", format!("Bearer {token}"))
                .body(Body::empty())
                .expect("request is valid"),
        )
        .await
        .expect("router should respond");
    let bearer_only: serde_json::Value =
        serde_json::from_str(&body(bearer_only).await).expect("JSON response");
    assert!(bearer_only["data"]["user"].is_null());
    for capability in [
        "media_upload",
        "media_delete",
        "import_preview",
        "archive_state",
        "account_actions",
        "admin_actions",
    ] {
        assert_eq!(bearer_only["data"]["capabilities"][capability], false);
    }

    sqlx::query("UPDATE users SET is_admin = 1 WHERE email = ?")
        .bind(EMAIL)
        .execute(&pool)
        .await
        .expect("fixture user should be promoted");
    let (csrf_cookie, csrf) = bootstrap(&app).await;
    let login = app
        .clone()
        .oneshot(login_request(&csrf_cookie, &csrf, EMAIL, PASSWORD, ORIGIN))
        .await
        .expect("router should respond");
    let session_token = cookie_value(&login, "__Host-stricknani_session=");
    let login: serde_json::Value =
        serde_json::from_str(&body(login).await).expect("login should return JSON");
    assert_eq!(login["data"]["capabilities"]["account_actions"], true);
    assert_eq!(login["data"]["capabilities"]["admin_actions"], true);

    sqlx::query("UPDATE users SET active = 0 WHERE email = ?")
        .bind(EMAIL)
        .execute(&pool)
        .await
        .expect("fixture user should be disabled");
    let disabled = app
        .oneshot(
            Request::get("/session")
                .header(
                    "cookie",
                    format!("__Host-stricknani_session={session_token}"),
                )
                .body(Body::empty())
                .expect("request is valid"),
        )
        .await
        .expect("router should respond");
    let disabled: serde_json::Value =
        serde_json::from_str(&body(disabled).await).expect("JSON response");
    assert!(disabled["data"]["user"].is_null());
    for capability in [
        "media_upload",
        "media_delete",
        "import_preview",
        "archive_state",
        "account_actions",
        "admin_actions",
    ] {
        assert_eq!(disabled["data"]["capabilities"][capability], false);
    }
}

#[tokio::test]
async fn login_requires_signed_csrf_and_same_origin_then_rate_limits_bad_passwords() {
    let (app, _) = fixture().await;
    let (cookie, csrf) = bootstrap(&app).await;

    let missing_csrf = app
        .clone()
        .oneshot(login_request(&cookie, "", EMAIL, PASSWORD, ORIGIN))
        .await
        .expect("router should respond");
    assert_eq!(missing_csrf.status(), StatusCode::FORBIDDEN);

    let invalid_csrf = app
        .clone()
        .oneshot(login_request(
            &cookie,
            "invalid-token",
            EMAIL,
            PASSWORD,
            ORIGIN,
        ))
        .await
        .expect("router should respond");
    assert_eq!(invalid_csrf.status(), StatusCode::FORBIDDEN);

    let cross_origin = app
        .clone()
        .oneshot(login_request(
            &cookie,
            &csrf,
            EMAIL,
            PASSWORD,
            "https://evil.example.test",
        ))
        .await
        .expect("router should respond");
    assert_eq!(cross_origin.status(), StatusCode::FORBIDDEN);

    let mut cross_site_fetch = login_request(&cookie, &csrf, EMAIL, PASSWORD, ORIGIN);
    *cross_site_fetch
        .headers_mut()
        .get_mut("sec-fetch-site")
        .expect("Fetch Metadata header is present") =
        "cross-site".parse().expect("header is valid");
    let rejected_fetch = app
        .clone()
        .oneshot(cross_site_fetch)
        .await
        .expect("router should respond");
    assert_eq!(rejected_fetch.status(), StatusCode::FORBIDDEN);

    for attempt in 0..5 {
        let bad = app
            .clone()
            .oneshot(login_request(
                &cookie,
                &csrf,
                EMAIL,
                "incorrect-password",
                ORIGIN,
            ))
            .await
            .expect("router should respond");
        assert_eq!(bad.status(), StatusCode::UNAUTHORIZED, "attempt {attempt}");
        let json: serde_json::Value =
            serde_json::from_str(&body(bad).await).expect("JSON response");
        assert_eq!(json["error"]["code"], "invalid_credentials");
    }
    let limited = app
        .oneshot(login_request(
            &cookie,
            &csrf,
            EMAIL,
            "incorrect-password",
            ORIGIN,
        ))
        .await
        .expect("router should respond");
    assert_eq!(limited.status(), StatusCode::TOO_MANY_REQUESTS);
    assert!(limited.headers().contains_key("retry-after"));
}

#[tokio::test]
async fn expired_session_returns_401_and_clears_cookie() {
    let (app, pool) = fixture().await;
    let token = "sna_expired-browser-session";
    sqlx::query("INSERT INTO api_tokens (user_id,name,token_hash,created_at,purpose,expires_at) VALUES (1,'Browser session',?,unixepoch(),'browser',unixepoch()-1)")
        .bind(hex::encode(Sha256::digest(token.as_bytes())))
        .execute(&pool)
        .await
        .expect("expired session should insert");
    let response = app
        .oneshot(logout_request(token, "irrelevant"))
        .await
        .expect("router should respond");
    assert_eq!(response.status(), StatusCode::UNAUTHORIZED);
    assert_eq!(response.headers()["cache-control"], "no-store");
    let clear_session = response.headers()["set-cookie"]
        .to_str()
        .expect("cookie is text")
        .to_owned();
    assert_eq!(
        serde_json::from_str::<serde_json::Value>(&body(response).await).expect("JSON response")["error"]
            ["code"],
        "session_expired"
    );
    assert!(clear_session.contains("Max-Age=0"));
}

#[tokio::test]
async fn api_v1_still_rejects_browser_cookie_and_accepts_bearer_token() {
    let pool = db::connect("sqlite::memory:")
        .await
        .expect("in-memory database should migrate");
    let user_id = db::create_user(&pool, EMAIL, PASSWORD.to_owned())
        .await
        .expect("user should be created");
    let token = "sna_android-api-token";
    sqlx::query("INSERT INTO api_tokens (user_id,name,token_hash,created_at,purpose) VALUES (?,'Android test',?,unixepoch(),'api')")
        .bind(user_id)
        .bind(hex::encode(Sha256::digest(token.as_bytes())))
        .execute(&pool)
        .await
        .expect("v1 token should insert");
    let static_root =
        std::path::PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../stricknani/static");
    let app = app_with_state(
        static_root,
        db::AppState::new(pool).with_secure_cookies(true),
    );

    let cookie_only = app
        .clone()
        .oneshot(
            Request::get("/api/v1/projects")
                .header("cookie", "__Host-stricknani_session=sna_browser-cookie")
                .body(Body::empty())
                .expect("request is valid"),
        )
        .await
        .expect("router should respond");
    assert_eq!(cookie_only.status(), StatusCode::UNAUTHORIZED);

    let bearer = app
        .oneshot(
            Request::get("/api/v1/projects")
                .header("authorization", format!("Bearer {token}"))
                .body(Body::empty())
                .expect("request is valid"),
        )
        .await
        .expect("router should respond");
    assert_eq!(bearer.status(), StatusCode::OK);
}
