use axum::{
    body::Body,
    http::{Request, StatusCode},
};
use http_body_util::BodyExt;
use sqlx::SqlitePool;
use stricknani_server::{api_v2, auth, db};
use tower::ServiceExt;

const EMAIL: &str = "invalid-hash@example.test";
const PASSWORD: &str = "a-long-test-password";

async fn pool() -> SqlitePool {
    db::connect("sqlite::memory:")
        .await
        .expect("in-memory database should migrate")
}

async fn invalid_hash_fixture(hash: &str) -> (SqlitePool, i64) {
    let pool = pool().await;
    let user_id: i64 =
        sqlx::query_scalar("INSERT INTO users (email, password_hash) VALUES (?, ?) RETURNING id")
            .bind(EMAIL)
            .bind(hash)
            .fetch_one(&pool)
            .await
            .expect("test user should be inserted");
    (pool, user_id)
}

fn v1_app(pool: SqlitePool) -> axum::Router {
    auth::router().with_state(db::AppState::new(pool))
}

fn v2_app(pool: SqlitePool) -> axum::Router {
    api_v2::router(db::AppState::new(pool), true)
}

fn token_request(email: &str) -> Request<Body> {
    Request::post("/auth/token")
        .header("content-type", "application/json")
        .body(Body::from(
            serde_json::json!({"email":email,"password":PASSWORD}).to_string(),
        ))
        .expect("request is valid")
}

async fn response_json(response: axum::response::Response) -> serde_json::Value {
    serde_json::from_slice(
        &response
            .into_body()
            .collect()
            .await
            .expect("body should be readable")
            .to_bytes(),
    )
    .expect("body should be JSON")
}

#[tokio::test]
async fn v1_invalid_stored_hash_matches_unknown_account_and_keeps_failed_attempts() {
    let (pool, user_id) = invalid_hash_fixture("not-a-supported-password-hash").await;
    let app = v1_app(pool.clone());
    let unknown = v1_app(pool.clone())
        .oneshot(token_request("unknown@example.test"))
        .await
        .expect("router should respond");
    let unknown_body = response_json(unknown).await;

    for _ in 0..5 {
        let response = app
            .clone()
            .oneshot(token_request(EMAIL))
            .await
            .expect("router should respond");
        assert_eq!(response.status(), StatusCode::UNAUTHORIZED);
        assert_eq!(response_json(response).await, unknown_body);
    }
    let limited = app
        .clone()
        .oneshot(token_request(EMAIL))
        .await
        .expect("router should respond");
    assert_eq!(limited.status(), StatusCode::TOO_MANY_REQUESTS);

    let tokens: i64 = sqlx::query_scalar("SELECT count(*) FROM api_tokens WHERE user_id = ?")
        .bind(user_id)
        .fetch_one(&pool)
        .await
        .expect("token count should be readable");
    assert_eq!(tokens, 0);
}

#[tokio::test]
async fn v2_invalid_hash_is_the_same_generic_error_as_bad_password_and_disabled_account() {
    let pool = pool().await;
    let user_id = db::create_user(&pool, EMAIL, "different-real-password".to_owned())
        .await
        .expect("test user should be created");
    let app = v2_app(pool.clone());
    let known_bad_password = app
        .clone()
        .oneshot(token_request(EMAIL))
        .await
        .expect("router should respond");
    let expected = response_json(known_bad_password).await;

    sqlx::query("UPDATE users SET password_hash = ? WHERE id = ?")
        .bind("$argon2id$v=19$m=invalid,t=2,p=1$salt$hash")
        .bind(user_id)
        .execute(&pool)
        .await
        .expect("stored hash should be corrupted");
    let invalid_hash = app
        .clone()
        .oneshot(token_request(EMAIL))
        .await
        .expect("router should respond");
    assert_eq!(invalid_hash.status(), StatusCode::UNAUTHORIZED);
    assert_eq!(response_json(invalid_hash).await, expected);

    sqlx::query("UPDATE users SET active = 0 WHERE id = ?")
        .bind(user_id)
        .execute(&pool)
        .await
        .expect("account should be disabled");
    let disabled = app
        .clone()
        .oneshot(token_request(EMAIL))
        .await
        .expect("router should respond");
    assert_eq!(disabled.status(), StatusCode::UNAUTHORIZED);
    assert_eq!(response_json(disabled).await, expected);

    let tokens: i64 = sqlx::query_scalar("SELECT count(*) FROM api_tokens WHERE user_id = ?")
        .bind(user_id)
        .fetch_one(&pool)
        .await
        .expect("token count should be readable");
    assert_eq!(tokens, 0);
}

#[tokio::test]
async fn malformed_bcrypt_hash_is_rejected_without_refunding_the_attempt() {
    let (pool, _) = invalid_hash_fixture("$2b$99$malformed-bcrypt-fixture").await;
    let app = v1_app(pool);
    for _ in 0..5 {
        assert_eq!(
            app.clone()
                .oneshot(token_request(EMAIL))
                .await
                .expect("router should respond")
                .status(),
            StatusCode::UNAUTHORIZED
        );
    }
    assert_eq!(
        app.oneshot(token_request(EMAIL))
            .await
            .expect("router should respond")
            .status(),
        StatusCode::TOO_MANY_REQUESTS
    );
}

#[tokio::test]
async fn disabled_password_capability_cannot_mint_native_tokens_with_the_correct_password() {
    let pool = pool().await;
    let user_id = db::create_user(&pool, EMAIL, PASSWORD.to_owned())
        .await
        .unwrap();
    sqlx::query("UPDATE users SET password_enabled = 0 WHERE id = ?")
        .bind(user_id)
        .execute(&pool)
        .await
        .unwrap();
    for app in [v1_app(pool.clone()), v2_app(pool.clone())] {
        let response = app.oneshot(token_request(EMAIL)).await.unwrap();
        assert_eq!(response.status(), StatusCode::UNAUTHORIZED);
    }
    let count: i64 = sqlx::query_scalar("SELECT count(*) FROM api_tokens WHERE user_id = ?")
        .bind(user_id)
        .fetch_one(&pool)
        .await
        .unwrap();
    assert_eq!(count, 0);
}
