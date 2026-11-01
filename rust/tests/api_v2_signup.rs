use axum::{
    body::Body,
    http::{Request, StatusCode},
};
use http_body_util::BodyExt;
use sqlx::SqlitePool;
use stricknani_server::{api_v2, db};
use tower::ServiceExt;

const EMAIL: &str = "new-member@example.test";
const PASSWORD: &str = "signup-password-long-enough";
const HOST: &str = "app.example.test";
const ORIGIN: &str = "https://app.example.test";
const CSRF_COOKIE: &str = "__Host-stricknani_v2_login_csrf";

async fn fixture(signup_enabled: bool) -> (axum::Router, SqlitePool) {
    let pool = db::connect("sqlite::memory:")
        .await
        .expect("in-memory database should migrate");
    let state = db::AppState::new(pool.clone())
        .with_secure_cookies(true)
        .with_signup_enabled(signup_enabled);
    (api_v2::router(state, false), pool)
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
    .expect("response body should be UTF-8")
}

async fn preauth(app: &axum::Router) -> (String, String) {
    let response = app
        .clone()
        .oneshot(
            Request::get("/session")
                .body(Body::empty())
                .expect("request is valid"),
        )
        .await
        .expect("router should respond");
    assert_eq!(response.status(), StatusCode::OK);
    let csrf_cookie = response
        .headers()
        .get_all("set-cookie")
        .iter()
        .filter_map(|value| value.to_str().ok())
        .find_map(|value| value.strip_prefix(&format!("{CSRF_COOKIE}=")))
        .and_then(|value| value.split(';').next())
        .expect("secure preauth csrf cookie should be set")
        .to_owned();
    let set_cookie = response
        .headers()
        .get_all("set-cookie")
        .iter()
        .filter_map(|value| value.to_str().ok())
        .find(|value| value.starts_with(&format!("{CSRF_COOKIE}=")))
        .expect("csrf cookie should be set");
    assert!(set_cookie.contains("; Path=/"));
    assert!(set_cookie.contains("; HttpOnly"));
    assert!(set_cookie.contains("; SameSite=Lax"));
    assert!(set_cookie.contains("; Secure"));
    assert!(!set_cookie.to_ascii_lowercase().contains("domain="));
    let json: serde_json::Value =
        serde_json::from_str(&body(response).await).expect("response is JSON");
    let csrf = json["data"]["csrf_token"]
        .as_str()
        .expect("anonymous session includes csrf token")
        .to_owned();
    (csrf_cookie, csrf)
}

fn signup_request(
    cookie: Option<&str>,
    csrf: Option<&str>,
    origin: &str,
    body: String,
) -> Request<Body> {
    let mut request = Request::post("/auth/signup")
        .header("host", HOST)
        .header("origin", origin)
        .header("sec-fetch-site", "same-origin")
        .header("content-type", "application/json");
    if let Some(cookie) = cookie {
        request = request.header("cookie", format!("{CSRF_COOKIE}={cookie}"));
    }
    if let Some(csrf) = csrf {
        request = request.header("x-csrf-token", csrf);
    }
    request.body(Body::from(body)).expect("request is valid")
}

fn signup_body(email: &str, password: &str) -> String {
    serde_json::json!({"email": email, "password": password}).to_string()
}

async fn user_count(pool: &SqlitePool) -> i64 {
    sqlx::query_scalar("SELECT COUNT(*) FROM users")
        .fetch_one(pool)
        .await
        .expect("user count should be readable")
}

#[tokio::test]
async fn signup_creates_member_without_implicitly_signing_in() {
    let (app, pool) = fixture(true).await;
    let (cookie, csrf) = preauth(&app).await;
    let response = app
        .clone()
        .oneshot(signup_request(
            Some(&cookie),
            Some(&csrf),
            ORIGIN,
            signup_body(" New-Member@Example.Test ", PASSWORD),
        ))
        .await
        .expect("router should respond");

    assert_eq!(response.status(), StatusCode::CREATED);
    assert_eq!(response.headers()["cache-control"], "no-store");
    let clear_cookie = response
        .headers()
        .get_all("set-cookie")
        .iter()
        .filter_map(|value| value.to_str().ok())
        .find(|value| value.starts_with(&format!("{CSRF_COOKIE}=")))
        .expect("preauth csrf cookie should be cleared");
    assert!(clear_cookie.contains("Max-Age=0"));
    assert!(clear_cookie.contains("; Secure"));
    assert!(
        response
            .headers()
            .get_all("set-cookie")
            .iter()
            .filter_map(|value| value.to_str().ok())
            .all(|value| !value.starts_with("__Host-stricknani_session="))
    );
    let json: serde_json::Value =
        serde_json::from_str(&body(response).await).expect("response is JSON");
    assert_eq!(json["data"]["email"], "new-member@example.test");
    assert_eq!(json["data"]["is_admin"], false);
    assert!(json["data"]["id"].as_i64().is_some());
    assert_eq!(user_count(&pool).await, 1);
    let role: bool = sqlx::query_scalar("SELECT is_admin FROM users WHERE email = ?")
        .bind("new-member@example.test")
        .fetch_one(&pool)
        .await
        .expect("created member should exist");
    assert!(!role);

    let (fresh_cookie, fresh_csrf) = preauth(&app).await;
    let login = app
        .oneshot(
            Request::post("/auth/login")
                .header("host", HOST)
                .header("origin", ORIGIN)
                .header("sec-fetch-site", "same-origin")
                .header("cookie", format!("{CSRF_COOKIE}={fresh_cookie}"))
                .header("x-csrf-token", fresh_csrf)
                .header("content-type", "application/json")
                .body(Body::from(signup_body("new-member@example.test", PASSWORD)))
                .expect("login request is valid"),
        )
        .await
        .expect("router should respond");
    assert_eq!(login.status(), StatusCode::OK);
}

#[tokio::test]
async fn signup_rejects_missing_csrf_and_cross_origin_without_creating_users() {
    let (app, pool) = fixture(true).await;
    let (cookie, csrf) = preauth(&app).await;
    for request in [
        signup_request(None, None, ORIGIN, signup_body(EMAIL, PASSWORD)),
        signup_request(
            Some(&cookie),
            Some(&csrf),
            "https://attacker.example.test",
            signup_body(EMAIL, PASSWORD),
        ),
    ] {
        let response = app
            .clone()
            .oneshot(request)
            .await
            .expect("router should respond");
        assert_eq!(response.status(), StatusCode::FORBIDDEN);
        let json: serde_json::Value =
            serde_json::from_str(&body(response).await).expect("response is JSON");
        assert_eq!(json["error"]["code"], "csrf_failed");
    }
    assert_eq!(user_count(&pool).await, 0);
}

#[tokio::test]
async fn signup_is_disabled_when_capability_is_false() {
    let (app, pool) = fixture(false).await;
    let response = app
        .clone()
        .oneshot(
            Request::get("/session")
                .body(Body::empty())
                .expect("request is valid"),
        )
        .await
        .expect("router should respond");
    let json: serde_json::Value =
        serde_json::from_str(&body(response).await).expect("response is JSON");
    assert_eq!(json["data"]["capabilities"]["signup"], false);
    let response = app
        .oneshot(signup_request(
            None,
            None,
            ORIGIN,
            signup_body(EMAIL, PASSWORD),
        ))
        .await
        .expect("router should respond");
    assert_eq!(response.status(), StatusCode::NOT_FOUND);
    let json: serde_json::Value =
        serde_json::from_str(&body(response).await).expect("response is JSON");
    assert_eq!(json["error"]["code"], "signup_disabled");
    assert_eq!(user_count(&pool).await, 0);
}

#[tokio::test]
async fn signup_maps_malformed_and_oversized_json_to_stable_errors() {
    let (app, _pool) = fixture(true).await;
    let (cookie, csrf) = preauth(&app).await;
    let malformed = app
        .clone()
        .oneshot(signup_request(
            Some(&cookie),
            Some(&csrf),
            ORIGIN,
            "{".to_owned(),
        ))
        .await
        .expect("router should respond");
    assert_eq!(malformed.status(), StatusCode::BAD_REQUEST);
    assert_eq!(
        serde_json::from_str::<serde_json::Value>(&body(malformed).await)
            .expect("response is JSON")["error"]["code"],
        "invalid_json"
    );

    let oversized = app
        .oneshot(signup_request(
            Some(&cookie),
            Some(&csrf),
            ORIGIN,
            " ".repeat(9 * 1024),
        ))
        .await
        .expect("router should respond");
    assert_eq!(oversized.status(), StatusCode::PAYLOAD_TOO_LARGE);
    assert_eq!(
        serde_json::from_str::<serde_json::Value>(&body(oversized).await)
            .expect("response is JSON")["error"]["code"],
        "payload_too_large"
    );
}

#[tokio::test]
async fn signup_validates_email_password_and_duplicate_accounts() {
    let (app, pool) = fixture(true).await;
    let (cookie, csrf) = preauth(&app).await;
    for (email, password, field) in [
        ("not-an-email", PASSWORD, "email"),
        (EMAIL, "short", "password"),
        (EMAIL, "password123", "password"),
    ] {
        let response = app
            .clone()
            .oneshot(signup_request(
                Some(&cookie),
                Some(&csrf),
                ORIGIN,
                signup_body(email, password),
            ))
            .await
            .expect("router should respond");
        assert_eq!(response.status(), StatusCode::UNPROCESSABLE_ENTITY);
        let json: serde_json::Value =
            serde_json::from_str(&body(response).await).expect("response is JSON");
        assert!(json["error"]["fields"][field].is_string());
    }

    db::create_user(&pool, EMAIL, PASSWORD.to_owned())
        .await
        .expect("existing member should be created");
    let response = app
        .oneshot(signup_request(
            Some(&cookie),
            Some(&csrf),
            ORIGIN,
            signup_body(EMAIL, PASSWORD),
        ))
        .await
        .expect("router should respond");
    assert_eq!(response.status(), StatusCode::CONFLICT);
    assert_eq!(
        serde_json::from_str::<serde_json::Value>(&body(response).await).expect("response is JSON")
            ["error"]["code"],
        "account_exists"
    );
    assert_eq!(user_count(&pool).await, 1);
}

#[tokio::test]
async fn signup_uses_the_existing_shared_auth_limiter() {
    let (app, pool) = fixture(true).await;
    let (cookie, csrf) = preauth(&app).await;
    for attempt in 0..5 {
        let response = app
            .clone()
            .oneshot(signup_request(
                Some(&cookie),
                Some(&csrf),
                ORIGIN,
                signup_body("rate-limited@example.test", &format!("invalid-{attempt}")),
            ))
            .await
            .expect("router should respond");
        assert_eq!(response.status(), StatusCode::UNPROCESSABLE_ENTITY);
    }
    let limited = app
        .oneshot(signup_request(
            Some(&cookie),
            Some(&csrf),
            ORIGIN,
            signup_body("rate-limited@example.test", "invalid-next"),
        ))
        .await
        .expect("router should respond");
    assert_eq!(limited.status(), StatusCode::TOO_MANY_REQUESTS);
    assert_eq!(limited.headers()["retry-after"], "300");
    assert_eq!(
        serde_json::from_str::<serde_json::Value>(&body(limited).await).expect("response is JSON")
            ["error"]["code"],
        "rate_limited"
    );
    assert_eq!(user_count(&pool).await, 0);
}
