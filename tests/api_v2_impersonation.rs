//! Administrator impersonation: browser-session only, limited to ordinary
//! active accounts, logged, expiring, and attributable in the audit log.
use axum::{
    Router,
    body::Body,
    http::{Request, StatusCode},
};
use http_body_util::BodyExt;
use serde_json::{Value, json};
use sha2::{Digest, Sha256};
use sqlx::SqlitePool;
use stricknani_server::{api_v2, db};
use tower::ServiceExt;

const ADMIN_TOKEN: &str = "sna_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
const MEMBER_TOKEN: &str = "sna_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
const API_TOKEN: &str = "sna_cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc";
const HOST: &str = "app.example.test";
const ORIGIN: &str = "https://app.example.test";

struct Fixture {
    app: Router,
    pool: SqlitePool,
    admin_id: i64,
    member_id: i64,
    other_admin_id: i64,
    disabled_id: i64,
    _media_root: tempfile::TempDir,
}

async fn fixture() -> Fixture {
    let pool = db::connect("sqlite::memory:")
        .await
        .expect("database migrates");
    let admin_id = db::create_user(&pool, "admin@example.test", "administrator-password".into())
        .await
        .unwrap();
    let other_admin_id =
        db::create_user(&pool, "boss@example.test", "administrator-password".into())
            .await
            .unwrap();
    for id in [admin_id, other_admin_id] {
        sqlx::query("UPDATE users SET is_admin = 1 WHERE id = ?")
            .bind(id)
            .execute(&pool)
            .await
            .unwrap();
    }
    let member_id = db::create_user(&pool, "member@example.test", "member-password-long".into())
        .await
        .unwrap();
    let disabled_id = db::create_user(&pool, "gone@example.test", "member-password-long".into())
        .await
        .unwrap();
    sqlx::query("UPDATE users SET active = 0 WHERE id = ?")
        .bind(disabled_id)
        .execute(&pool)
        .await
        .unwrap();
    add_token(&pool, admin_id, ADMIN_TOKEN, "browser").await;
    add_token(&pool, member_id, MEMBER_TOKEN, "browser").await;
    add_token(&pool, admin_id, API_TOKEN, "api").await;
    let media_root = tempfile::tempdir().unwrap();
    Fixture {
        app: api_v2::router(
            db::AppState::new(pool.clone())
                .with_secure_cookies(true)
                .with_media_root(media_root.path().to_path_buf()),
            false,
        ),
        pool,
        admin_id,
        member_id,
        other_admin_id,
        disabled_id,
        _media_root: media_root,
    }
}

async fn add_token(pool: &SqlitePool, user_id: i64, token: &str, purpose: &str) {
    sqlx::query(
        "INSERT INTO api_tokens (user_id, name, token_hash, created_at, expires_at, purpose) \
         VALUES (?, 'test token', ?, unixepoch(), unixepoch() + 86400, ?)",
    )
    .bind(user_id)
    .bind(hex::encode(Sha256::digest(token.as_bytes())))
    .bind(purpose)
    .execute(pool)
    .await
    .unwrap();
}

fn csrf(token: &str) -> String {
    let mut hash = Sha256::new();
    hash.update(b"stricknani browser csrf v1\0");
    hash.update(token.as_bytes());
    hex::encode(hash.finalize())
}

/// A same-origin browser request with the cookie session `token`.
async fn browser(
    fixture: &Fixture,
    method: &str,
    path: &str,
    token: &str,
    body: Option<Value>,
) -> (StatusCode, Value) {
    let mut request = Request::builder()
        .method(method)
        .uri(path)
        .header("host", HOST)
        .header("origin", ORIGIN)
        .header("sec-fetch-site", "same-origin")
        .header("cookie", format!("__Host-stricknani_session={token}"))
        .header("x-csrf-token", csrf(token));
    let body = match body {
        Some(value) => {
            request = request.header("content-type", "application/json");
            Body::from(value.to_string())
        }
        None => Body::empty(),
    };
    send(fixture, request.body(body).unwrap()).await
}

async fn bearer(fixture: &Fixture, method: &str, path: &str) -> (StatusCode, Value) {
    let request = Request::builder()
        .method(method)
        .uri(path)
        .header("host", HOST)
        .header("origin", ORIGIN)
        .header("sec-fetch-site", "same-origin")
        .header("authorization", format!("Bearer {API_TOKEN}"))
        .header("content-type", "application/json")
        .body(Body::from("{}"))
        .unwrap();
    send(fixture, request).await
}

async fn send(fixture: &Fixture, request: Request<Body>) -> (StatusCode, Value) {
    let response = fixture.app.clone().oneshot(request).await.unwrap();
    let status = response.status();
    let bytes = response.into_body().collect().await.unwrap().to_bytes();
    (
        status,
        serde_json::from_slice(&bytes).unwrap_or(Value::Null),
    )
}

async fn start(fixture: &Fixture, target: i64) -> (StatusCode, Value) {
    browser(
        fixture,
        "POST",
        &format!("/admin/users/{target}/impersonate"),
        ADMIN_TOKEN,
        None,
    )
    .await
}

async fn audit_actions(fixture: &Fixture) -> Vec<(String, Option<i64>, Option<i64>)> {
    sqlx::query_as::<_, (String, Option<i64>, Option<i64>)>(
        "SELECT action, actor_user_id, subject_user_id FROM audit_events \
         WHERE entity_type = 'user' ORDER BY id",
    )
    .fetch_all(&fixture.pool)
    .await
    .unwrap()
}

#[tokio::test]
async fn admin_starts_and_stops_and_the_session_acts_as_the_target() {
    let fixture = fixture().await;
    let (status, body) = start(&fixture, fixture.member_id).await;
    assert_eq!(status, StatusCode::OK, "{body}");
    assert_eq!(body["data"]["user"]["email"], "member@example.test");
    assert_eq!(body["data"]["admin"]["email"], "admin@example.test");
    assert!(body["data"]["expires_at"].as_i64().unwrap() > 0);

    let (_, session) = browser(&fixture, "GET", "/session", ADMIN_TOKEN, None).await;
    assert_eq!(session["data"]["user"]["id"], fixture.member_id);
    assert_eq!(session["data"]["user"]["is_admin"], false);
    assert_eq!(
        session["data"]["impersonation"]["admin"]["id"],
        fixture.admin_id
    );
    assert_eq!(
        session["data"]["impersonation"]["user"]["id"],
        fixture.member_id
    );
    let (status, account) = browser(&fixture, "GET", "/account", ADMIN_TOKEN, None).await;
    assert_eq!(status, StatusCode::OK);
    assert!(account.to_string().contains("member@example.test"));

    let (status, body) = browser(
        &fixture,
        "POST",
        "/admin/impersonation/stop",
        ADMIN_TOKEN,
        None,
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{body}");
    assert_eq!(body["data"]["stopped"], true);
    let (_, session) = browser(&fixture, "GET", "/session", ADMIN_TOKEN, None).await;
    assert_eq!(session["data"]["user"]["id"], fixture.admin_id);
    assert!(session["data"].get("impersonation").is_none());
    let (status, _) = browser(&fixture, "GET", "/admin/users", ADMIN_TOKEN, None).await;
    assert_eq!(status, StatusCode::OK);

    // Stopping again is harmless and logs nothing more.
    let (_, body) = browser(
        &fixture,
        "POST",
        "/admin/impersonation/stop",
        ADMIN_TOKEN,
        None,
    )
    .await;
    assert_eq!(body["data"]["stopped"], false);
    assert_eq!(
        audit_actions(&fixture).await,
        vec![
            (
                "impersonation_started".to_owned(),
                Some(fixture.admin_id),
                Some(fixture.member_id)
            ),
            (
                "impersonation_stopped".to_owned(),
                Some(fixture.admin_id),
                Some(fixture.member_id)
            ),
        ]
    );
}

#[tokio::test]
async fn only_administrators_may_impersonate_ordinary_active_accounts() {
    let fixture = fixture().await;
    let (status, body) = browser(
        &fixture,
        "POST",
        &format!("/admin/users/{}/impersonate", fixture.admin_id),
        MEMBER_TOKEN,
        None,
    )
    .await;
    assert_eq!(status, StatusCode::FORBIDDEN);
    assert_eq!(body["error"]["code"], "admin_required");

    for target in [
        fixture.admin_id,
        fixture.other_admin_id,
        fixture.disabled_id,
    ] {
        let (status, body) = start(&fixture, target).await;
        assert_eq!(status, StatusCode::CONFLICT, "target {target}");
        assert_eq!(body["error"]["code"], "impersonation_invalid_target");
    }
    let (status, _) = start(&fixture, 9999).await;
    assert_eq!(status, StatusCode::NOT_FOUND);

    // Same-origin and CSRF protection apply like any admin mutation.
    let request = Request::builder()
        .method("POST")
        .uri(format!("/admin/users/{}/impersonate", fixture.member_id))
        .header("host", HOST)
        .header("cookie", format!("__Host-stricknani_session={ADMIN_TOKEN}"))
        .body(Body::empty())
        .unwrap();
    let (status, body) = send(&fixture, request).await;
    assert_eq!(status, StatusCode::FORBIDDEN);
    assert_eq!(body["error"]["code"], "csrf_failed");

    assert!(audit_actions(&fixture).await.is_empty());
    let (_, session) = browser(&fixture, "GET", "/session", ADMIN_TOKEN, None).await;
    assert_eq!(session["data"]["user"]["id"], fixture.admin_id);
}

#[tokio::test]
async fn bearer_tokens_can_neither_start_nor_stop_nor_carry_an_impersonation() {
    let fixture = fixture().await;
    let (status, body) = bearer(
        &fixture,
        "POST",
        &format!("/admin/users/{}/impersonate", fixture.member_id),
    )
    .await;
    assert_eq!(status, StatusCode::FORBIDDEN);
    assert_eq!(body["error"]["code"], "impersonation_forbidden");
    let (status, _) = bearer(&fixture, "POST", "/admin/impersonation/stop").await;
    assert_eq!(status, StatusCode::FORBIDDEN);

    // While the browser session impersonates, the admin's PAT is unchanged.
    let (status, _) = start(&fixture, fixture.member_id).await;
    assert_eq!(status, StatusCode::OK);
    let (status, _) = bearer(&fixture, "GET", "/admin/users").await;
    assert_eq!(status, StatusCode::OK);
    let (status, account) = bearer(&fixture, "GET", "/account").await;
    assert_eq!(status, StatusCode::OK);
    assert!(account.to_string().contains("admin@example.test"));
}

#[tokio::test]
async fn administration_and_account_security_are_refused_while_impersonating() {
    let fixture = fixture().await;
    let (status, _) = start(&fixture, fixture.member_id).await;
    assert_eq!(status, StatusCode::OK);

    let refused = [
        ("GET", "/admin/users"),
        ("GET", "/admin/audit"),
        ("POST", "/admin/users"),
        ("PATCH", "/admin/users/1/active"),
        ("POST", "/admin/users/1/impersonate"),
        ("PUT", "/account/password"),
        ("POST", "/account/api-tokens"),
        ("GET", "/account/api-tokens"),
        ("DELETE", "/account/api-tokens/1"),
        ("POST", "/account/delete"),
        ("PATCH", "/account"),
        ("POST", "/account/identities/link"),
        ("DELETE", "/account/identities/1"),
        ("POST", "/account/reauth"),
        ("POST", "/account/export"),
        ("PUT", "/push/subscription"),
        ("POST", "/auth/oidc/start"),
    ];
    for (method, path) in refused {
        let (status, body) = browser(&fixture, method, path, ADMIN_TOKEN, Some(json!({}))).await;
        assert_eq!(status, StatusCode::FORBIDDEN, "{method} {path}");
        assert_eq!(
            body["error"]["code"], "impersonation_forbidden",
            "{method} {path}"
        );
    }
    let tokens: i64 = sqlx::query_scalar("SELECT count(*) FROM api_tokens")
        .fetch_one(&fixture.pool)
        .await
        .unwrap();
    assert_eq!(tokens, 3);

    // Ordinary resource routes work as the target.
    let (status, project) = browser(
        &fixture,
        "POST",
        "/projects",
        ADMIN_TOKEN,
        Some(json!({"name": "Mütze", "notes": "", "steps": []})),
    )
    .await;
    assert_eq!(status, StatusCode::CREATED, "{project}");
    let owner: i64 = sqlx::query_scalar("SELECT owner_id FROM projects WHERE name = 'Mütze'")
        .fetch_one(&fixture.pool)
        .await
        .unwrap();
    assert_eq!(owner, fixture.member_id);
}

#[tokio::test]
async fn changes_made_while_impersonating_name_the_administrator() {
    let fixture = fixture().await;
    let (status, _) = start(&fixture, fixture.member_id).await;
    assert_eq!(status, StatusCode::OK);
    let (status, _) = browser(
        &fixture,
        "POST",
        "/projects",
        ADMIN_TOKEN,
        Some(json!({"name": "Mütze", "notes": "", "steps": []})),
    )
    .await;
    assert_eq!(status, StatusCode::CREATED);
    // The member's own request carries no impersonator.
    let (status, _) = browser(
        &fixture,
        "POST",
        "/projects",
        MEMBER_TOKEN,
        Some(json!({"name": "Schal", "notes": "", "steps": []})),
    )
    .await;
    assert_eq!(status, StatusCode::CREATED);

    let rows = sqlx::query_as::<_, (String, i64, Option<i64>)>(
        "SELECT resource_name_snapshot, actor_user_id, impersonated_by_user_id \
         FROM audit_events WHERE entity_type = 'project' ORDER BY id",
    )
    .fetch_all(&fixture.pool)
    .await
    .unwrap();
    assert_eq!(
        rows,
        vec![
            (
                "Mütze".to_owned(),
                fixture.member_id,
                Some(fixture.admin_id)
            ),
            ("Schal".to_owned(), fixture.member_id, None),
        ]
    );

    // The administrator's audit log shows who was behind it.
    let (status, audit) = bearer(&fixture, "GET", "/admin/audit").await;
    assert_eq!(status, StatusCode::OK);
    let events = audit["data"].as_array().unwrap();
    let event = events
        .iter()
        .find(|event| event["resource"]["name"] == "Mütze")
        .unwrap();
    assert_eq!(event["impersonated_by"]["id"], fixture.admin_id);
    assert_eq!(event["impersonated_by"]["email"], "admin@example.test");
    let started = events
        .iter()
        .find(|event| event["action"] == "impersonation_started")
        .unwrap();
    assert_eq!(started["actor_email"], "admin@example.test");
    assert_eq!(started["subject"]["id"], fixture.member_id);
    assert!(started.get("impersonated_by").is_none());
}

#[tokio::test]
async fn timer_events_while_impersonating_name_the_administrator() {
    let fixture = fixture().await;
    let project_id: i64 = sqlx::query_scalar(
        "INSERT INTO projects(owner_id, name, created_at, updated_at) \
         VALUES (?, 'Timer', 'now', 'now') RETURNING id",
    )
    .bind(fixture.member_id)
    .fetch_one(&fixture.pool)
    .await
    .unwrap();
    let path = format!("/projects/{project_id}/timer");
    let (status, _) = start(&fixture, fixture.member_id).await;
    assert_eq!(status, StatusCode::OK);
    let (status, timer) = browser(
        &fixture,
        "PUT",
        &path,
        ADMIN_TOKEN,
        Some(json!({"action": "start"})),
    )
    .await;
    assert_eq!(status, StatusCode::OK);
    // The run belongs to the account being acted as.
    assert_eq!(timer["data"]["started_by"], fixture.member_id);
    let (status, _) = browser(
        &fixture,
        "PUT",
        &path,
        MEMBER_TOKEN,
        Some(json!({"action": "stop"})),
    )
    .await;
    assert_eq!(status, StatusCode::OK);

    let rows = sqlx::query_as::<_, (String, i64, Option<i64>)>(
        "SELECT action, actor_user_id, impersonated_by_user_id FROM audit_events \
         WHERE entity_type = 'project' AND action LIKE 'timer\\_%' ESCAPE '\\' ORDER BY id",
    )
    .fetch_all(&fixture.pool)
    .await
    .unwrap();
    assert_eq!(
        rows,
        vec![
            (
                "timer_started".to_owned(),
                fixture.member_id,
                Some(fixture.admin_id)
            ),
            ("timer_stopped".to_owned(), fixture.member_id, None),
        ]
    );
    let (status, audit) = bearer(&fixture, "GET", "/admin/audit").await;
    assert_eq!(status, StatusCode::OK);
    let started = audit["data"]
        .as_array()
        .unwrap()
        .iter()
        .find(|event| event["action"] == "timer_started")
        .unwrap();
    assert_eq!(started["impersonated_by"]["id"], fixture.admin_id);
}

#[tokio::test]
async fn an_expired_impersonation_ends_when_it_is_noticed_and_is_logged_once() {
    let fixture = fixture().await;
    let (status, _) = start(&fixture, fixture.member_id).await;
    assert_eq!(status, StatusCode::OK);
    sqlx::query("UPDATE api_tokens SET impersonation_expires_at = unixepoch() - 1")
        .execute(&fixture.pool)
        .await
        .unwrap();

    // A write meant for the impersonated account is not applied to the
    // administrator's own account.
    let (status, body) = browser(
        &fixture,
        "POST",
        "/projects",
        ADMIN_TOKEN,
        Some(json!({"name": "Mütze", "notes": "", "steps": []})),
    )
    .await;
    assert_eq!(status, StatusCode::CONFLICT);
    assert_eq!(body["error"]["code"], "impersonation_expired");
    let projects: i64 = sqlx::query_scalar("SELECT count(*) FROM projects")
        .fetch_one(&fixture.pool)
        .await
        .unwrap();
    assert_eq!(projects, 0);

    let (_, session) = browser(&fixture, "GET", "/session", ADMIN_TOKEN, None).await;
    assert_eq!(session["data"]["user"]["id"], fixture.admin_id);
    assert!(session["data"].get("impersonation").is_none());
    let (status, _) = browser(&fixture, "GET", "/admin/users", ADMIN_TOKEN, None).await;
    assert_eq!(status, StatusCode::OK);
    let actions: Vec<String> = audit_actions(&fixture)
        .await
        .into_iter()
        .map(|(action, ..)| action)
        .collect();
    assert_eq!(actions, ["impersonation_started", "impersonation_stopped"]);
}

#[tokio::test]
async fn expiry_noticed_by_a_read_ends_it_without_an_error() {
    let fixture = fixture().await;
    let (status, _) = start(&fixture, fixture.member_id).await;
    assert_eq!(status, StatusCode::OK);
    sqlx::query("UPDATE api_tokens SET impersonation_expires_at = unixepoch() - 1")
        .execute(&fixture.pool)
        .await
        .unwrap();
    let (status, _) = browser(&fixture, "GET", "/session", ADMIN_TOKEN, None).await;
    assert_eq!(status, StatusCode::OK);
    let (status, _) = browser(&fixture, "GET", "/session", ADMIN_TOKEN, None).await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(audit_actions(&fixture).await.len(), 2);
}

#[tokio::test]
async fn disabling_the_target_or_signing_out_ends_the_impersonation() {
    let fixture = fixture().await;
    let (status, _) = start(&fixture, fixture.member_id).await;
    assert_eq!(status, StatusCode::OK);
    sqlx::query("UPDATE users SET active = 0 WHERE id = ?")
        .bind(fixture.member_id)
        .execute(&fixture.pool)
        .await
        .unwrap();
    let (_, session) = browser(&fixture, "GET", "/session", ADMIN_TOKEN, None).await;
    assert_eq!(session["data"]["user"]["id"], fixture.admin_id);

    sqlx::query("UPDATE users SET active = 1 WHERE id = ?")
        .bind(fixture.member_id)
        .execute(&fixture.pool)
        .await
        .unwrap();
    let (status, _) = start(&fixture, fixture.member_id).await;
    assert_eq!(status, StatusCode::OK);
    let (status, _) = browser(
        &fixture,
        "POST",
        "/auth/logout",
        ADMIN_TOKEN,
        Some(json!({})),
    )
    .await;
    assert_eq!(status, StatusCode::NO_CONTENT);
    let actions: Vec<String> = audit_actions(&fixture)
        .await
        .into_iter()
        .map(|(action, ..)| action)
        .collect();
    assert_eq!(
        actions,
        [
            "impersonation_started",
            "impersonation_stopped",
            "impersonation_started",
            "impersonation_stopped"
        ]
    );
}
