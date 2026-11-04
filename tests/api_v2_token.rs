use axum::{
    Router,
    body::Body,
    http::{Request, StatusCode},
};
use http_body_util::BodyExt;
use serde_json::{Value, json};
use sqlx::SqlitePool;
use stricknani_server::{api_v2, auth, db};
use tower::ServiceExt;

const EMAIL: &str = "native-token@example.test";
const PASSWORD: &str = "account-test-password-long";

async fn fixture() -> (Router, SqlitePool) {
    let pool = db::connect("sqlite::memory:")
        .await
        .expect("in-memory database should migrate");
    let state = db::AppState::new(pool.clone());
    let app = Router::new()
        .nest("/api/v2", api_v2::router(state.clone(), false))
        .nest("/api/v1", auth::router().with_state(state));
    (app, pool)
}

fn token_request(path: &str, email: &str, password: &str, name: Option<&str>) -> Request<Body> {
    let mut payload = json!({"email": email, "password": password});
    if let Some(name) = name {
        payload["token_name"] = json!(name);
    }
    Request::post(path)
        .header("content-type", "application/json")
        .body(Body::from(payload.to_string()))
        .expect("request should be valid")
}

fn raw_token_request(path: &str, body: impl Into<Body>) -> Request<Body> {
    Request::post(path)
        .header("content-type", "application/json")
        .body(body.into())
        .expect("request should be valid")
}

async fn response_json(response: axum::response::Response) -> Value {
    let bytes = response
        .into_body()
        .collect()
        .await
        .expect("response body should be readable")
        .to_bytes();
    serde_json::from_slice(&bytes).expect("response body should be JSON")
}

#[tokio::test]
async fn native_token_is_api_scoped_works_as_a_pat_and_sets_no_cookie() {
    let (app, pool) = fixture().await;
    let user_id = db::create_user(&pool, EMAIL, PASSWORD.to_owned())
        .await
        .expect("test account should be created");

    let response = app
        .clone()
        .oneshot(token_request(
            "/api/v2/auth/token",
            EMAIL,
            PASSWORD,
            Some("  Native tablet  "),
        ))
        .await
        .expect("native token request should complete");
    assert_eq!(response.status(), StatusCode::OK);
    assert_eq!(response.headers()["cache-control"], "no-store");
    assert!(!response.headers().contains_key("set-cookie"));
    let token = response_json(response).await["data"]["token"]
        .as_str()
        .expect("response should contain a token")
        .to_owned();

    let response = app
        .clone()
        .oneshot(
            Request::get("/api/v2/projects")
                .header("authorization", format!("Bearer {token}"))
                .body(Body::empty())
                .expect("PAT request should be valid"),
        )
        .await
        .expect("PAT request should complete");
    assert_eq!(response.status(), StatusCode::OK);

    let (name, purpose, expires_at): (String, String, Option<i64>) = sqlx::query_as(
        "SELECT name, purpose, expires_at FROM api_tokens \
         WHERE user_id = ? ORDER BY id DESC LIMIT 1",
    )
    .bind(user_id)
    .fetch_one(&pool)
    .await
    .expect("minted token metadata should be stored");
    assert_eq!(name, "Native tablet");
    assert_eq!(purpose, "api");
    assert_eq!(expires_at, None);
}

#[tokio::test]
async fn blank_token_name_uses_the_existing_native_default() {
    let (app, pool) = fixture().await;
    let user_id = db::create_user(&pool, EMAIL, PASSWORD.to_owned())
        .await
        .expect("test account should be created");

    let response = app
        .clone()
        .oneshot(token_request(
            "/api/v2/auth/token",
            EMAIL,
            PASSWORD,
            Some("   "),
        ))
        .await
        .expect("native token request should complete");
    assert_eq!(response.status(), StatusCode::OK);

    let name: String = sqlx::query_scalar(
        "SELECT name FROM api_tokens WHERE user_id = ? ORDER BY id DESC LIMIT 1",
    )
    .bind(user_id)
    .fetch_one(&pool)
    .await
    .expect("minted token name should be stored");
    assert_eq!(name, "Android (password login)");

    let response = app
        .oneshot(token_request(
            "/api/v2/auth/token",
            EMAIL,
            PASSWORD,
            Some(&"x".repeat(256)),
        ))
        .await
        .expect("oversized token name request should complete");
    assert_eq!(response.status(), StatusCode::UNPROCESSABLE_ENTITY);
    let body = response_json(response).await;
    assert_eq!(body["error"]["code"], "validation_error");
    assert!(body["error"]["fields"]["token_name"].is_string());
}

#[tokio::test]
async fn malformed_and_oversized_bodies_use_v2_errors() {
    let (app, _) = fixture().await;
    let path = "/api/v2/auth/token";
    for body in [
        "{".to_owned(),
        json!({"email": 1, "password": "x"}).to_string(),
    ] {
        let response = app
            .clone()
            .oneshot(raw_token_request(path, body))
            .await
            .expect("malformed JSON request should complete");
        assert_eq!(response.status(), StatusCode::BAD_REQUEST);
        let body = response_json(response).await;
        assert_eq!(body["error"]["code"], "invalid_json");
    }

    let response = app
        .oneshot(raw_token_request(path, vec![b'x'; 8 * 1024 + 1]))
        .await
        .expect("oversized request should complete");
    assert_eq!(response.status(), StatusCode::PAYLOAD_TOO_LARGE);
    let body = response_json(response).await;
    assert_eq!(body["error"]["code"], "payload_too_large");
}

#[tokio::test]
async fn credential_lengths_return_field_validation_without_minting_tokens() {
    let (app, pool) = fixture().await;
    let requests = [
        (
            "email",
            token_request(
                "/api/v2/auth/token",
                &format!("{}@example.test", "a".repeat(320)),
                PASSWORD,
                None,
            ),
        ),
        (
            "password",
            token_request("/api/v2/auth/token", EMAIL, &"p".repeat(1025), None),
        ),
        (
            "token_name",
            token_request(
                "/api/v2/auth/token",
                EMAIL,
                PASSWORD,
                Some(&"x".repeat(256)),
            ),
        ),
    ];
    for (field, request) in requests {
        let response = app
            .clone()
            .oneshot(request)
            .await
            .expect("invalid length request should complete");
        assert_eq!(response.status(), StatusCode::UNPROCESSABLE_ENTITY);
        let body = response_json(response).await;
        assert_eq!(body["error"]["code"], "validation_error");
        assert!(body["error"]["fields"][field].is_string());
    }
    let token_count: i64 = sqlx::query_scalar("SELECT count(*) FROM api_tokens")
        .fetch_one(&pool)
        .await
        .expect("token count should be readable");
    assert_eq!(token_count, 0);
}

#[tokio::test]
async fn bad_disabled_and_unknown_accounts_share_the_same_401() {
    let (app, pool) = fixture().await;
    let user_id = db::create_user(&pool, EMAIL, PASSWORD.to_owned())
        .await
        .expect("test account should be created");

    let bad_password = app
        .clone()
        .oneshot(token_request(
            "/api/v2/auth/token",
            EMAIL,
            "another-invalid-test-password",
            None,
        ))
        .await
        .expect("bad credential request should complete");
    assert_eq!(bad_password.status(), StatusCode::UNAUTHORIZED);
    let bad_body = response_json(bad_password).await;

    sqlx::query("UPDATE users SET active = 0 WHERE id = ?")
        .bind(user_id)
        .execute(&pool)
        .await
        .expect("account should be disabled");
    let disabled = app
        .clone()
        .oneshot(token_request("/api/v2/auth/token", EMAIL, PASSWORD, None))
        .await
        .expect("disabled account request should complete");
    assert_eq!(disabled.status(), StatusCode::UNAUTHORIZED);
    let disabled_body = response_json(disabled).await;

    let unknown = app
        .oneshot(token_request(
            "/api/v2/auth/token",
            "unknown-native-token@example.test",
            PASSWORD,
            None,
        ))
        .await
        .expect("unknown account request should complete");
    assert_eq!(unknown.status(), StatusCode::UNAUTHORIZED);
    let unknown_body = response_json(unknown).await;

    assert_eq!(bad_body["error"]["code"], "invalid_credentials");
    assert_eq!(bad_body["error"], disabled_body["error"]);
    assert_eq!(bad_body["error"], unknown_body["error"]);
}

#[tokio::test]
async fn browser_purpose_tokens_are_not_accepted_as_resource_pats() {
    use sha2::{Digest, Sha256};

    let (app, pool) = fixture().await;
    let user_id = db::create_user(&pool, EMAIL, PASSWORD.to_owned())
        .await
        .expect("test account should be created");
    let browser_token = "test-browser-purpose-token";
    let token_hash = hex::encode(Sha256::digest(browser_token.as_bytes()));
    sqlx::query(
        "INSERT INTO api_tokens (user_id, name, token_hash, created_at, purpose) \
         VALUES (?, 'browser session', ?, unixepoch(), 'browser')",
    )
    .bind(user_id)
    .bind(token_hash)
    .execute(&pool)
    .await
    .expect("browser token fixture should be stored");

    let response = app
        .oneshot(
            Request::get("/api/v2/projects")
                .header("authorization", format!("Bearer {browser_token}"))
                .body(Body::empty())
                .expect("PAT request should be valid"),
        )
        .await
        .expect("browser purpose token request should complete");
    assert_eq!(response.status(), StatusCode::UNAUTHORIZED);
}

#[tokio::test]
async fn v1_and_v2_token_minting_share_the_password_attempt_quota() {
    let (app, _) = fixture().await;
    let email = "quota-native-token@example.test";
    for attempt in 0..5 {
        let path = if attempt % 2 == 0 {
            "/api/v2/auth/token"
        } else {
            "/api/v1/auth/token"
        };
        let response = app
            .clone()
            .oneshot(token_request(path, email, PASSWORD, None))
            .await
            .expect("invalid credential request should complete");
        assert_eq!(response.status(), StatusCode::UNAUTHORIZED);
    }
    let response = app
        .oneshot(token_request("/api/v2/auth/token", email, PASSWORD, None))
        .await
        .expect("rate limited request should complete");
    assert_eq!(response.status(), StatusCode::TOO_MANY_REQUESTS);
    assert_eq!(response.headers()["retry-after"], "300");
    assert_eq!(
        response_json(response).await["error"]["code"],
        "rate_limited"
    );
}
