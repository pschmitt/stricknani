use axum::{
    Router,
    body::Body,
    http::{Request, StatusCode},
};
use http_body_util::BodyExt;
use sha2::{Digest, Sha256};
use sqlx::SqlitePool;
use stricknani_server::{app_with_state, db};
use tower::ServiceExt;

const ADMIN_SESSION: &str = "synthetic-admin-browser-session";
const ADMIN_PAT: &str = "synthetic-admin-api-pat";
const MEMBER_SESSION: &str = "synthetic-member-browser-session";
const MEMBER_PAT: &str = "synthetic-member-api-pat";
const DISABLE_PAT: &str = "synthetic-disable-target-api-pat";
const ORIGIN: &str = "https://app.example.test";

struct Fixture {
    app: Router,
    pool: SqlitePool,
    admin_id: i64,
    member_id: i64,
    _static_root: tempfile::TempDir,
}

async fn fixture() -> Fixture {
    let pool = db::connect("sqlite::memory:")
        .await
        .expect("database migrates");
    let admin_id = db::create_user(&pool, "admin@example.test", "admin-password-long".into())
        .await
        .expect("admin exists");
    sqlx::query("UPDATE users SET is_admin = 1 WHERE id = ?")
        .bind(admin_id)
        .execute(&pool)
        .await
        .unwrap();
    let member_id = db::create_user(&pool, "member@example.test", "member-password-long".into())
        .await
        .expect("member exists");
    add_token(&pool, admin_id, ADMIN_SESSION, "browser").await;
    add_token(&pool, admin_id, ADMIN_PAT, "api").await;
    add_token(&pool, member_id, MEMBER_SESSION, "browser").await;
    add_token(&pool, member_id, MEMBER_PAT, "api").await;
    let static_root = tempfile::tempdir().unwrap();
    let app = app_with_state(db::AppState::new(pool.clone()).with_secure_cookies(true));
    Fixture {
        app,
        pool,
        admin_id,
        member_id,
        _static_root: static_root,
    }
}

async fn add_token(pool: &SqlitePool, user_id: i64, token: &str, purpose: &str) {
    sqlx::query(
        "INSERT INTO api_tokens (user_id, name, token_hash, created_at, expires_at, purpose) \
         VALUES (?, 'synthetic fixture token', ?, unixepoch(), unixepoch() + 86400, ?)",
    )
    .bind(user_id)
    .bind(token_hash(token))
    .bind(purpose)
    .execute(pool)
    .await
    .unwrap();
}

fn token_hash(token: &str) -> String {
    hex::encode(Sha256::digest(token.as_bytes()))
}

fn request(method: &str, path: &str, token: &str, purpose: &str, body: &str) -> Request<Body> {
    let mut request = Request::builder().method(method).uri(path);
    if purpose == "browser" {
        request = request.header("cookie", format!("__Host-stricknani_session={token}"));
    } else {
        request = request.header("authorization", format!("Bearer {token}"));
    }
    request
        .header("content-type", "application/json")
        .body(Body::from(body.to_owned()))
        .unwrap()
}

fn admin_request(method: &str, path: &str, body: &str) -> Request<Body> {
    request(method, path, ADMIN_PAT, "api", body)
}

fn authenticated_read(token: &str, purpose: &str) -> Request<Body> {
    let path = if purpose == "browser" {
        "/api/v2/account/api-tokens"
    } else {
        "/api/v2/projects"
    };
    request("GET", path, token, purpose, "")
}

async fn json(response: axum::response::Response) -> serde_json::Value {
    serde_json::from_slice(&response.into_body().collect().await.unwrap().to_bytes()).unwrap()
}

async fn login(app: &Router, email: &str, password: &str) -> (String, String) {
    let bootstrap = app
        .clone()
        .oneshot(Request::get("/api/v2/session").body(Body::empty()).unwrap())
        .await
        .unwrap();
    let csrf_cookie = bootstrap.headers()["set-cookie"]
        .to_str()
        .unwrap()
        .split(';')
        .next()
        .unwrap()
        .to_owned();
    let csrf = json(bootstrap).await["data"]["csrf_token"]
        .as_str()
        .unwrap()
        .to_owned();
    let response = app
        .clone()
        .oneshot(
            Request::post("/api/v2/auth/login")
                .header("host", "app.example.test")
                .header("origin", ORIGIN)
                .header("sec-fetch-site", "same-origin")
                .header("cookie", csrf_cookie)
                .header("x-csrf-token", csrf)
                .header("content-type", "application/json")
                .body(Body::from(
                    serde_json::json!({"email": email, "password": password}).to_string(),
                ))
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    let session = response.headers()["set-cookie"]
        .to_str()
        .unwrap()
        .split(';')
        .next()
        .unwrap()
        .split_once('=')
        .unwrap()
        .1
        .to_owned();
    let csrf = json(response).await["data"]["csrf_token"]
        .as_str()
        .unwrap()
        .to_owned();
    (session, csrf)
}

#[tokio::test]
async fn reset_and_disable_revoke_every_token_without_reactivating_accounts() {
    let fixture = fixture().await;
    let disable_id = db::create_user(
        &fixture.pool,
        "disable-target@example.test",
        "disable-target-password-long".into(),
    )
    .await
    .unwrap();
    add_token(&fixture.pool, disable_id, DISABLE_PAT, "api").await;
    let valid_pat = fixture
        .app
        .clone()
        .oneshot(authenticated_read(DISABLE_PAT, "api"))
        .await
        .unwrap();
    assert_eq!(valid_pat.status(), StatusCode::OK);

    let reset = fixture
        .app
        .clone()
        .oneshot(admin_request(
            "POST",
            &format!("/api/v2/admin/users/{}/reset-password", fixture.member_id),
            r#"{"new_password":"replacement-password-long"}"#,
        ))
        .await
        .unwrap();
    assert_eq!(reset.status(), StatusCode::OK);
    let active: i64 = sqlx::query_scalar("SELECT active FROM users WHERE id = ?")
        .bind(fixture.member_id)
        .fetch_one(&fixture.pool)
        .await
        .unwrap();
    assert_eq!(active, 1);

    let disable = fixture
        .app
        .clone()
        .oneshot(admin_request(
            "PATCH",
            &format!("/api/v2/admin/users/{disable_id}/active"),
            r#"{"active":false}"#,
        ))
        .await
        .unwrap();
    assert_eq!(disable.status(), StatusCode::OK);
    let old_pat = fixture
        .app
        .clone()
        .oneshot(authenticated_read(DISABLE_PAT, "api"))
        .await
        .unwrap();
    assert_eq!(old_pat.status(), StatusCode::UNAUTHORIZED);
    let reenable = fixture
        .app
        .clone()
        .oneshot(admin_request(
            "PATCH",
            &format!("/api/v2/admin/users/{disable_id}/active"),
            r#"{"active":true}"#,
        ))
        .await
        .unwrap();
    assert_eq!(reenable.status(), StatusCode::OK);
    let old_pat_after_reenable = fixture
        .app
        .clone()
        .oneshot(authenticated_read(DISABLE_PAT, "api"))
        .await
        .unwrap();
    assert_eq!(old_pat_after_reenable.status(), StatusCode::UNAUTHORIZED);

    for (token, purpose) in [(MEMBER_SESSION, "browser"), (MEMBER_PAT, "api")] {
        let old = fixture
            .app
            .clone()
            .oneshot(authenticated_read(token, purpose))
            .await
            .unwrap();
        assert_eq!(old.status(), StatusCode::UNAUTHORIZED);
    }

    let disable = fixture
        .app
        .clone()
        .oneshot(admin_request(
            "PATCH",
            &format!("/api/v2/admin/users/{}/active", fixture.member_id),
            r#"{"active":false}"#,
        ))
        .await
        .unwrap();
    assert_eq!(disable.status(), StatusCode::OK);
    let inactive_reset = fixture
        .app
        .clone()
        .oneshot(admin_request(
            "POST",
            &format!("/api/v2/admin/users/{}/reset-password", fixture.member_id),
            r#"{"new_password":"inactive-reset-password-long"}"#,
        ))
        .await
        .unwrap();
    assert_eq!(inactive_reset.status(), StatusCode::OK);
    let active: i64 = sqlx::query_scalar("SELECT active FROM users WHERE id = ?")
        .bind(fixture.member_id)
        .fetch_one(&fixture.pool)
        .await
        .unwrap();
    assert_eq!(active, 0);

    let enable = fixture
        .app
        .clone()
        .oneshot(admin_request(
            "PATCH",
            &format!("/api/v2/admin/users/{}/active", fixture.member_id),
            r#"{"active":true}"#,
        ))
        .await
        .unwrap();
    assert_eq!(enable.status(), StatusCode::OK);
    for (token, purpose) in [(MEMBER_SESSION, "browser"), (MEMBER_PAT, "api")] {
        let old = fixture
            .app
            .clone()
            .oneshot(authenticated_read(token, purpose))
            .await
            .unwrap();
        assert_eq!(old.status(), StatusCode::UNAUTHORIZED);
    }

    let (new_session, _) = login(
        &fixture.app,
        "member@example.test",
        "inactive-reset-password-long",
    )
    .await;
    let fresh_session = fixture
        .app
        .clone()
        .oneshot(authenticated_read(&new_session, "browser"))
        .await
        .unwrap();
    assert_eq!(fresh_session.status(), StatusCode::OK);
    let new_pat = fixture
        .app
        .clone()
        .oneshot(
            Request::post("/api/v2/auth/token")
                .header("content-type", "application/json")
                .body(Body::from(
                    serde_json::json!({
                        "email": "member@example.test",
                        "password": "inactive-reset-password-long",
                        "token_name": "fresh test PAT"
                    })
                    .to_string(),
                ))
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(new_pat.status(), StatusCode::OK);
    let pat = json(new_pat).await["data"]["token"]
        .as_str()
        .unwrap()
        .to_owned();
    let fresh_pat = fixture
        .app
        .clone()
        .oneshot(authenticated_read(&pat, "api"))
        .await
        .unwrap();
    assert_eq!(fresh_pat.status(), StatusCode::OK);
}

#[tokio::test]
async fn rejected_reset_and_last_admin_disable_leave_password_and_tokens_unchanged() {
    let fixture = fixture().await;
    let before_hash: String = sqlx::query_scalar("SELECT password_hash FROM users WHERE id = ?")
        .bind(fixture.member_id)
        .fetch_one(&fixture.pool)
        .await
        .unwrap();
    let denied = fixture
        .app
        .clone()
        .oneshot(request(
            "POST",
            &format!("/api/v2/admin/users/{}/reset-password", fixture.member_id),
            MEMBER_PAT,
            "api",
            r#"{"new_password":"must-not-be-applied-password"}"#,
        ))
        .await
        .unwrap();
    assert_eq!(denied.status(), StatusCode::FORBIDDEN);
    let after_hash: String = sqlx::query_scalar("SELECT password_hash FROM users WHERE id = ?")
        .bind(fixture.member_id)
        .fetch_one(&fixture.pool)
        .await
        .unwrap();
    assert_eq!(after_hash, before_hash);

    let disable_last_admin = fixture
        .app
        .clone()
        .oneshot(admin_request(
            "PATCH",
            &format!("/api/v2/admin/users/{}/active", fixture.admin_id),
            r#"{"active":false}"#,
        ))
        .await
        .unwrap();
    assert_eq!(disable_last_admin.status(), StatusCode::CONFLICT);
    for (user_id, token) in [
        (fixture.member_id, MEMBER_SESSION),
        (fixture.member_id, MEMBER_PAT),
        (fixture.admin_id, ADMIN_SESSION),
        (fixture.admin_id, ADMIN_PAT),
    ] {
        let revoked: Option<i64> = sqlx::query_scalar(
            "SELECT revoked_at FROM api_tokens WHERE user_id = ? AND token_hash = ?",
        )
        .bind(user_id)
        .bind(token_hash(token))
        .fetch_one(&fixture.pool)
        .await
        .unwrap();
        assert_eq!(revoked, None);
    }
}

#[tokio::test]
async fn admin_password_recovery_enables_password_capability_and_preserves_disabled_status() {
    let fixture = fixture().await;
    sqlx::query("UPDATE users SET active = 0, password_enabled = 0 WHERE id = ?")
        .bind(fixture.member_id)
        .execute(&fixture.pool)
        .await
        .unwrap();
    let reset = fixture
        .app
        .clone()
        .oneshot(admin_request(
            "POST",
            &format!("/api/v2/admin/users/{}/reset-password", fixture.member_id),
            r#"{"new_password":"recovered-local-password-long"}"#,
        ))
        .await
        .unwrap();
    assert_eq!(reset.status(), StatusCode::OK);
    let capabilities: (i64, i64) =
        sqlx::query_as("SELECT active, password_enabled FROM users WHERE id = ?")
            .bind(fixture.member_id)
            .fetch_one(&fixture.pool)
            .await
            .unwrap();
    assert_eq!(capabilities, (0, 1));
    let live_tokens: i64 = sqlx::query_scalar(
        "SELECT count(*) FROM api_tokens WHERE user_id = ? AND revoked_at IS NULL",
    )
    .bind(fixture.member_id)
    .fetch_one(&fixture.pool)
    .await
    .unwrap();
    assert_eq!(live_tokens, 0);
}
