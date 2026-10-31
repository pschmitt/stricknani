use argon2::{Argon2, PasswordHash, PasswordVerifier};
use axum::body::Body;
use axum::http::{HeaderMap, Request, StatusCode};
use http_body_util::BodyExt;
use sha2::{Digest, Sha256};
use sqlx::SqlitePool;
use stricknani_server::{auth, db};
use tower::ServiceExt;

async fn pool() -> SqlitePool {
    db::connect("sqlite::memory:")
        .await
        .expect("in-memory database should migrate")
}

#[tokio::test]
async fn valid_credentials_mint_a_token_that_authenticates() {
    let pool = pool().await;
    let user_id = db::create_user(
        &pool,
        "knitter@example.test",
        "a-long-test-password".to_owned(),
    )
    .await
    .expect("test user should be created");
    let app = auth::router().with_state(db::AppState::new(pool.clone()));

    let response = app
        .oneshot(
            Request::post("/auth/token")
                .header("content-type", "application/json")
                .body(Body::from(
                    r#"{"email":"KNITTER@example.test","password":"a-long-test-password","token_name":" Test device "}"#,
                ))
                .expect("request is valid"),
        )
        .await
        .expect("router should respond");

    assert_eq!(response.status(), StatusCode::OK);
    assert_eq!(response.headers()["cache-control"], "no-store");
    let body = response
        .into_body()
        .collect()
        .await
        .expect("body is readable")
        .to_bytes();
    let json: serde_json::Value = serde_json::from_slice(&body).expect("response is JSON");
    let token = json["token"].as_str().expect("token is returned once");
    assert!(token.starts_with("sna_"));
    assert_eq!(token.len(), 68);

    let mut headers = HeaderMap::new();
    headers.insert(
        axum::http::header::AUTHORIZATION,
        format!("bearer {token}").parse().expect("header is valid"),
    );
    assert_eq!(
        auth::bearer_user(&pool, &headers)
            .await
            .expect("token authenticates"),
        user_id
    );

    let stored_hash: String = sqlx::query_scalar("SELECT token_hash FROM api_tokens")
        .fetch_one(&pool)
        .await
        .expect("token hash is stored");
    assert_eq!(stored_hash, hex::encode(Sha256::digest(token.as_bytes())));
    assert!(!stored_hash.contains(token));
}

#[tokio::test]
async fn legacy_bcrypt_accounts_upgrade_after_successful_password_login() {
    let pool = pool().await;
    let app = auth::router().with_state(db::AppState::new(pool.clone()));
    for version in ['a', 'b', 'y'] {
        let email = format!("bcrypt-{version}@example.test");
        let mut password_hash = bcrypt::hash("legacy-correct-password", 4)
            .expect("test bcrypt hash should be generated");
        password_hash.replace_range(2..3, &version.to_string());
        let user_id: i64 = sqlx::query_scalar(
            "INSERT INTO users (email, password_hash) VALUES (?, ?) RETURNING id",
        )
        .bind(&email)
        .bind(&password_hash)
        .fetch_one(&pool)
        .await
        .expect("legacy account should be inserted");

        let rejected = mint(&app, &email, "legacy-wrong-password").await;
        assert_eq!(rejected.status(), StatusCode::UNAUTHORIZED);
        let unchanged_hash: String =
            sqlx::query_scalar("SELECT password_hash FROM users WHERE id = ?")
                .bind(user_id)
                .fetch_one(&pool)
                .await
                .expect("legacy hash should remain available");
        assert_eq!(unchanged_hash, password_hash);

        let accepted = mint(&app, &email, "legacy-correct-password").await;
        assert_eq!(accepted.status(), StatusCode::OK);
        let body = accepted
            .into_body()
            .collect()
            .await
            .expect("response body should be readable")
            .to_bytes();
        let token_response: serde_json::Value =
            serde_json::from_slice(&body).expect("token response should be JSON");
        let token = token_response["token"]
            .as_str()
            .expect("new token should be returned");
        assert!(token.starts_with("sna_"));
        assert_eq!(token.len(), 68);

        let upgraded_hash: String =
            sqlx::query_scalar("SELECT password_hash FROM users WHERE id = ?")
                .bind(user_id)
                .fetch_one(&pool)
                .await
                .expect("upgraded hash should be stored");
        assert!(upgraded_hash.starts_with("$argon2id$"));
        let parsed = PasswordHash::new(&upgraded_hash).expect("hash should be valid Argon2");
        Argon2::default()
            .verify_password("legacy-correct-password".as_bytes(), &parsed)
            .expect("legacy password should verify against the upgraded hash");

        let subsequent = mint(&app, &email, "legacy-correct-password").await;
        assert_eq!(subsequent.status(), StatusCode::OK);
    }
}

#[tokio::test]
async fn invalid_credentials_return_401_and_record_no_token() {
    let pool = pool().await;
    db::create_user(
        &pool,
        "knitter@example.test",
        "a-long-test-password".to_owned(),
    )
    .await
    .expect("test user should be created");
    let app = auth::router().with_state(db::AppState::new(pool.clone()));

    let response = app
        .oneshot(
            Request::post("/auth/token")
                .header("content-type", "application/json")
                .body(Body::from(
                    r#"{"email":"knitter@example.test","password":"incorrect-test-password"}"#,
                ))
                .expect("request is valid"),
        )
        .await
        .expect("router should respond");

    assert_eq!(response.status(), StatusCode::UNAUTHORIZED);
    let count: i64 = sqlx::query_scalar("SELECT count(*) FROM api_tokens")
        .fetch_one(&pool)
        .await
        .expect("token count can be read");
    assert_eq!(count, 0);
}

#[tokio::test]
async fn unknown_account_uses_the_same_unauthorized_response() {
    let pool = pool().await;
    let app = auth::router().with_state(db::AppState::new(pool));
    let response = app
        .oneshot(
            Request::post("/auth/token")
                .header("content-type", "application/json")
                .body(Body::from(
                    r#"{"email":"unknown@example.test","password":"incorrect-test-password"}"#,
                ))
                .expect("request is valid"),
        )
        .await
        .expect("router should respond");

    assert_eq!(response.status(), StatusCode::UNAUTHORIZED);
}

#[tokio::test]
async fn repeated_invalid_credentials_return_429_with_retry_after() {
    let pool = pool().await;
    db::create_user(
        &pool,
        "knitter@example.test",
        "a-long-test-password".to_owned(),
    )
    .await
    .expect("test user should be created");
    let app = auth::router().with_state(db::AppState::new(pool));

    for _ in 0..5 {
        let response = mint(&app, "knitter@example.test", "incorrect-test-password").await;
        assert_eq!(response.status(), StatusCode::UNAUTHORIZED);
    }
    let blocked = mint(&app, "knitter@example.test", "incorrect-test-password").await;

    assert_eq!(blocked.status(), StatusCode::TOO_MANY_REQUESTS);
    assert_eq!(blocked.headers()[axum::http::header::RETRY_AFTER], "300");
}

#[tokio::test]
async fn oversized_email_is_rejected_before_authentication() {
    let pool = pool().await;
    let app = auth::router().with_state(db::AppState::new(pool));
    let email = format!("{}@example.test", "a".repeat(321));
    let body = serde_json::json!({ "email": email, "password": "some-password" });
    let response = app
        .oneshot(
            Request::post("/auth/token")
                .header("content-type", "application/json")
                .body(Body::from(body.to_string()))
                .expect("request is valid"),
        )
        .await
        .expect("router should respond");

    assert_eq!(response.status(), StatusCode::UNPROCESSABLE_ENTITY);
}

#[tokio::test]
async fn bearer_auth_rejects_revoked_expired_and_inactive_accounts() {
    let pool = pool().await;
    let user_id = db::create_user(
        &pool,
        "knitter@example.test",
        "a-long-test-password".to_owned(),
    )
    .await
    .expect("test user should be created");

    insert_token(&pool, user_id, "revoked-pat", Some("revoked_at"), None).await;
    insert_token(&pool, user_id, "expired-pat", None, Some("expires_at")).await;
    insert_token(&pool, user_id, "active-pat", None, None).await;

    assert!(matches!(
        bearer(&pool, "revoked-pat").await,
        Err(auth::AuthError::Unauthorized)
    ));
    assert!(matches!(
        bearer(&pool, "expired-pat").await,
        Err(auth::AuthError::Unauthorized)
    ));
    assert_eq!(
        bearer(&pool, "active-pat")
            .await
            .expect("active token authenticates"),
        user_id
    );

    sqlx::query("UPDATE users SET active = 0 WHERE id = ?")
        .bind(user_id)
        .execute(&pool)
        .await
        .expect("account can be disabled");
    assert!(matches!(
        bearer(&pool, "active-pat").await,
        Err(auth::AuthError::Unauthorized)
    ));
}

async fn insert_token(
    pool: &SqlitePool,
    user_id: i64,
    token: &str,
    revoked: Option<&str>,
    expires: Option<&str>,
) {
    let hash = hex::encode(Sha256::digest(token.as_bytes()));
    let revoked_at = revoked.map(|_| "unixepoch()").unwrap_or("NULL");
    let expires_at = expires.map(|_| "unixepoch() - 1").unwrap_or("NULL");
    let query = format!(
        "INSERT INTO api_tokens (user_id, name, token_hash, created_at, revoked_at, expires_at) \
         VALUES (?, 'test', ?, unixepoch(), {revoked_at}, {expires_at})"
    );
    sqlx::query(&query)
        .bind(user_id)
        .bind(hash)
        .execute(pool)
        .await
        .expect("test token can be inserted");
}

async fn bearer(pool: &SqlitePool, token: &str) -> Result<i64, auth::AuthError> {
    let mut headers = HeaderMap::new();
    headers.insert(
        axum::http::header::AUTHORIZATION,
        format!("Bearer {token}").parse().expect("header is valid"),
    );
    auth::bearer_user(pool, &headers).await
}

async fn mint(app: &axum::Router, email: &str, password: &str) -> axum::response::Response {
    app.clone()
        .oneshot(
            Request::post("/auth/token")
                .header("content-type", "application/json")
                .body(Body::from(
                    serde_json::json!({ "email": email, "password": password }).to_string(),
                ))
                .expect("request is valid"),
        )
        .await
        .expect("router should respond")
}
