//! Renaming the calling PAT, `device_name` on the PAT-minting endpoints and
//! `last_used_at` tracking.

use axum::{
    body::Body,
    http::{Request, StatusCode},
};
use http_body_util::BodyExt;
use serde_json::{Value, json};
use sha2::{Digest, Sha256};
use sqlx::SqlitePool;
use std::time::Duration;
use stricknani_server::{api_v2, db};
use tower::ServiceExt;

const HOST: &str = "app.example.test";
const ORIGIN: &str = "https://app.example.test";
const EMAIL: &str = "names@example.test";
const PASSWORD: &str = "token-names-password-long";

async fn fixture() -> (axum::Router, SqlitePool, i64) {
    let pool = db::connect("sqlite::memory:").await.unwrap();
    let state = db::AppState::new(pool.clone()).with_secure_cookies(true);
    let user_id = db::create_user(&pool, EMAIL, PASSWORD.to_owned())
        .await
        .unwrap();
    (api_v2::router(state, false), pool, user_id)
}

async fn add_pat(pool: &SqlitePool, user_id: i64, secret: &str, name: &str) -> i64 {
    sqlx::query_scalar(
        "INSERT INTO api_tokens (user_id, name, token_hash, purpose, created_at) \
         VALUES (?, ?, ?, 'api', unixepoch()) RETURNING id",
    )
    .bind(user_id)
    .bind(name)
    .bind(hex::encode(Sha256::digest(secret.as_bytes())))
    .fetch_one(pool)
    .await
    .unwrap()
}

async fn json_body(response: axum::response::Response) -> Value {
    let bytes = response.into_body().collect().await.unwrap().to_bytes();
    serde_json::from_slice(&bytes).unwrap_or(Value::Null)
}

fn bearer(method: &str, path: &str, token: &str, body: Option<Value>) -> Request<Body> {
    let mut builder = Request::builder()
        .method(method)
        .uri(path)
        .header("authorization", format!("Bearer {token}"));
    if body.is_some() {
        builder = builder.header("content-type", "application/json");
    }
    builder
        .body(body.map_or_else(Body::empty, |b| Body::from(b.to_string())))
        .unwrap()
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
        .oneshot(Request::get("/session").body(Body::empty()).unwrap())
        .await
        .unwrap();
    let preauth = cookie(&bootstrap, "__Host-stricknani_v2_login_csrf=");
    let pre_csrf = json_body(bootstrap).await["data"]["csrf_token"]
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
                    json!({"email": EMAIL, "password": PASSWORD}).to_string(),
                ))
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(login.status(), StatusCode::OK);
    let session = cookie(&login, "__Host-stricknani_session=");
    let csrf = json_body(login).await["data"]["csrf_token"]
        .as_str()
        .unwrap()
        .to_owned();
    (session, csrf)
}

async fn last_used(pool: &SqlitePool, id: i64) -> Option<i64> {
    sqlx::query_scalar("SELECT last_used_at FROM api_tokens WHERE id = ?")
        .bind(id)
        .fetch_one(pool)
        .await
        .unwrap()
}

/// The write is off the request path, so wait for it to land.
async fn wait_for_last_used(pool: &SqlitePool, id: i64) -> i64 {
    for _ in 0..100 {
        if let Some(value) = last_used(pool, id).await {
            return value;
        }
        tokio::time::sleep(Duration::from_millis(20)).await;
    }
    panic!("last_used_at was never written");
}

#[tokio::test]
async fn bearer_renames_only_its_own_token() {
    let (app, pool, user_id) = fixture().await;
    let current = add_pat(&pool, user_id, "rename-pat", "Android (password login)").await;
    let other = add_pat(&pool, user_id, "other-pat", "Other").await;

    let response = app
        .clone()
        .oneshot(bearer(
            "PATCH",
            "/account/api-tokens/current",
            "rename-pat",
            Some(json!({"name": "  Pixel 9  "})),
        ))
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    let body = json_body(response).await;
    assert_eq!(body["data"]["id"], current);
    assert_eq!(body["data"]["name"], "Pixel 9");
    assert!(body["data"]["created_at"].is_string());
    assert!(body["data"]["expires_at"].is_null());

    let names: Vec<(i64, String)> = sqlx::query_as("SELECT id, name FROM api_tokens ORDER BY id")
        .fetch_all(&pool)
        .await
        .unwrap();
    assert_eq!(
        names,
        vec![(current, "Pixel 9".into()), (other, "Other".into())]
    );

    let exactly_100 = "x".repeat(100);
    let response = app
        .clone()
        .oneshot(bearer(
            "PATCH",
            "/account/api-tokens/current",
            "rename-pat",
            Some(json!({"name": exactly_100})),
        ))
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::OK);
}

#[tokio::test]
async fn rename_validates_the_name_and_requires_a_live_pat() {
    let (app, pool, user_id) = fixture().await;
    add_pat(&pool, user_id, "rename-pat", "Before").await;
    for name in ["", "   ", &"x".repeat(101)] {
        let response = app
            .clone()
            .oneshot(bearer(
                "PATCH",
                "/account/api-tokens/current",
                "rename-pat",
                Some(json!({"name": name})),
            ))
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::UNPROCESSABLE_ENTITY);
        let body = json_body(response).await;
        assert_eq!(body["error"]["code"], "validation_error");
        assert!(body["error"]["fields"]["name"].is_string());
    }
    let name: String = sqlx::query_scalar("SELECT name FROM api_tokens")
        .fetch_one(&pool)
        .await
        .unwrap();
    assert_eq!(name, "Before");

    let response = app
        .clone()
        .oneshot(bearer(
            "PATCH",
            "/account/api-tokens/current",
            "unknown-pat",
            Some(json!({"name": "Nope"})),
        ))
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::UNAUTHORIZED);
    assert_eq!(
        json_body(response).await["error"]["code"],
        "session_expired"
    );
}

#[tokio::test]
async fn cookie_sessions_cannot_rename() {
    let (app, _pool, _user_id) = fixture().await;
    let (session, csrf) = signed_in(&app).await;
    let response = app
        .clone()
        .oneshot(
            Request::patch("/account/api-tokens/current")
                .header("cookie", format!("__Host-stricknani_session={session}"))
                .header("host", HOST)
                .header("origin", ORIGIN)
                .header("sec-fetch-site", "same-origin")
                .header("x-csrf-token", csrf)
                .header("content-type", "application/json")
                .body(Body::from(json!({"name": "Browser"}).to_string()))
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::FORBIDDEN);
    assert_eq!(json_body(response).await["error"]["code"], "forbidden");
}

fn password_mint(extra: Value) -> Request<Body> {
    let mut payload = json!({"email": EMAIL, "password": PASSWORD});
    for (key, value) in extra.as_object().unwrap() {
        payload[key] = value.clone();
    }
    Request::post("/auth/token")
        .header("content-type", "application/json")
        .body(Body::from(payload.to_string()))
        .unwrap()
}

async fn newest_name(pool: &SqlitePool) -> String {
    sqlx::query_scalar("SELECT name FROM api_tokens WHERE purpose = 'api' ORDER BY id DESC")
        .fetch_one(pool)
        .await
        .unwrap()
}

#[tokio::test]
async fn password_mint_applies_device_name_and_ignores_it_when_absent() {
    let (app, pool, _user_id) = fixture().await;
    let response = app.clone().oneshot(password_mint(json!({}))).await.unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    assert_eq!(newest_name(&pool).await, "Android (password login)");

    let response = app
        .clone()
        .oneshot(password_mint(json!({"device_name": "  Pixel 9  "})))
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    assert_eq!(newest_name(&pool).await, "Android \u{b7} Pixel 9");

    // A blank device name falls back to the usual name resolution.
    let response = app
        .clone()
        .oneshot(password_mint(
            json!({"device_name": "   ", "token_name": "Custom"}),
        ))
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    assert_eq!(newest_name(&pool).await, "Custom");

    let before: i64 = sqlx::query_scalar("SELECT count(*) FROM api_tokens")
        .fetch_one(&pool)
        .await
        .unwrap();
    let response = app
        .clone()
        .oneshot(password_mint(json!({"device_name": "d".repeat(101)})))
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::UNPROCESSABLE_ENTITY);
    assert!(json_body(response).await["error"]["fields"]["device_name"].is_string());
    let after: i64 = sqlx::query_scalar("SELECT count(*) FROM api_tokens")
        .fetch_one(&pool)
        .await
        .unwrap();
    assert_eq!(before, after);

    let response = app
        .clone()
        .oneshot(password_mint(json!({"device_name": "d".repeat(100)})))
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::OK);
}

#[tokio::test]
async fn last_used_is_set_once_per_window_and_listed() {
    let (app, pool, user_id) = fixture().await;
    let used = add_pat(&pool, user_id, "used-pat", "Used").await;
    let unused = add_pat(&pool, user_id, "unused-pat", "Unused").await;
    assert_eq!(last_used(&pool, used).await, None);

    let list = |app: axum::Router| async move {
        let response = app
            .oneshot(bearer("GET", "/account/api-tokens", "used-pat", None))
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::OK);
        json_body(response).await
    };

    // First use stamps the token; the list shows it, and null for the unused one.
    let first = list(app.clone()).await;
    let stamped = wait_for_last_used(&pool, used).await;
    assert!((chrono::Utc::now().timestamp() - stamped).abs() < 60);
    let second = list(app.clone()).await;
    let find = |body: &Value, id: i64| {
        body["data"]
            .as_array()
            .unwrap()
            .iter()
            .find(|token| token["id"] == id)
            .unwrap()
            .clone()
    };
    assert!(find(&first, unused)["last_used_at"].is_null());
    assert!(find(&second, used)["last_used_at"].is_string());
    assert!(find(&second, unused)["last_used_at"].is_null());
    assert_eq!(last_used(&pool, unused).await, None);

    // Inside the throttle window the value is not rewritten.
    let recent = chrono::Utc::now().timestamp() - 60;
    sqlx::query("UPDATE api_tokens SET last_used_at = ? WHERE id = ?")
        .bind(recent)
        .bind(used)
        .execute(&pool)
        .await
        .unwrap();
    list(app.clone()).await;
    tokio::time::sleep(Duration::from_millis(300)).await;
    assert_eq!(last_used(&pool, used).await, Some(recent));

    // A stale value is refreshed.
    let stale = chrono::Utc::now().timestamp() - 3600;
    sqlx::query("UPDATE api_tokens SET last_used_at = ? WHERE id = ?")
        .bind(stale)
        .bind(used)
        .execute(&pool)
        .await
        .unwrap();
    list(app.clone()).await;
    for _ in 0..100 {
        if last_used(&pool, used).await != Some(stale) {
            break;
        }
        tokio::time::sleep(Duration::from_millis(20)).await;
    }
    assert!(last_used(&pool, used).await.unwrap() > stale + 3000);
}

#[tokio::test]
async fn cookie_token_list_includes_last_used_at() {
    let (app, pool, user_id) = fixture().await;
    let id = add_pat(&pool, user_id, "listed-pat", "Listed").await;
    let (session, _csrf) = signed_in(&app).await;
    let response = app
        .clone()
        .oneshot(
            Request::get("/account/api-tokens")
                .header("cookie", format!("__Host-stricknani_session={session}"))
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    let body = json_body(response).await;
    let token = body["data"]
        .as_array()
        .unwrap()
        .iter()
        .find(|token| token["id"] == id)
        .unwrap();
    assert!(token.get("last_used_at").is_some_and(Value::is_null));
}
