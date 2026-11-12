//! Native administrator impersonation: an administrator's API PAT obtains a
//! short-lived `sni_` bearer credential that acts as an ordinary active
//! account, carries the browser flow's restrictions, expires, is revocable and
//! is attributed to the administrator in the audit log.
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

const ADMIN_PAT: &str = "sna_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
const MEMBER_PAT: &str = "sna_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
const OTHER_ADMIN_PAT: &str =
    "sna_cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc";
const HOST: &str = "app.example.test";

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
    add_pat(&pool, admin_id, ADMIN_PAT).await;
    add_pat(&pool, member_id, MEMBER_PAT).await;
    add_pat(&pool, other_admin_id, OTHER_ADMIN_PAT).await;
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

async fn add_pat(pool: &SqlitePool, user_id: i64, token: &str) {
    sqlx::query(
        "INSERT INTO api_tokens (user_id, name, token_hash, created_at, purpose) \
         VALUES (?, 'test token', ?, unixepoch(), 'api')",
    )
    .bind(user_id)
    .bind(hex::encode(Sha256::digest(token.as_bytes())))
    .execute(pool)
    .await
    .unwrap();
}

async fn call(
    fixture: &Fixture,
    method: &str,
    path: &str,
    token: Option<&str>,
    body: Option<Value>,
) -> (StatusCode, Value) {
    let mut request = Request::builder()
        .method(method)
        .uri(path)
        .header("host", HOST);
    if let Some(token) = token {
        request = request.header("authorization", format!("Bearer {token}"));
    }
    let body = match body {
        Some(value) => {
            request = request.header("content-type", "application/json");
            Body::from(value.to_string())
        }
        None => Body::empty(),
    };
    let response = fixture
        .app
        .clone()
        .oneshot(request.body(body).unwrap())
        .await
        .unwrap();
    let status = response.status();
    let bytes = response.into_body().collect().await.unwrap().to_bytes();
    (
        status,
        serde_json::from_slice(&bytes).unwrap_or(Value::Null),
    )
}

async fn start(fixture: &Fixture, token: Option<&str>, target: i64) -> (StatusCode, Value) {
    call(
        fixture,
        "POST",
        &format!("/admin/users/{target}/impersonation-token"),
        token,
        None,
    )
    .await
}

/// Start as the administrator and return the credential.
async fn start_ok(fixture: &Fixture, target: i64) -> String {
    let (status, body) = start(fixture, Some(ADMIN_PAT), target).await;
    assert_eq!(status, StatusCode::OK, "{body}");
    body["data"]["token"].as_str().unwrap().to_owned()
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
async fn an_administrator_pat_obtains_a_credential_that_acts_as_the_target() {
    let fixture = fixture().await;
    let (status, body) = start(&fixture, Some(ADMIN_PAT), fixture.member_id).await;
    assert_eq!(status, StatusCode::OK, "{body}");
    let token = body["data"]["token"].as_str().unwrap();
    assert!(token.starts_with("sni_"), "distinct from the sna_ PATs");
    assert_eq!(body["data"]["user"]["email"], "member@example.test");
    assert_eq!(body["data"]["user"]["name"], "Member");
    assert_eq!(body["data"]["admin"]["email"], "admin@example.test");
    // The lifetime is the web impersonation's: one hour.
    let remaining = body["data"]["expires_at"].as_i64().unwrap() - chrono::Utc::now().timestamp();
    assert!((3590..=3600).contains(&remaining), "{remaining}");

    let (status, account) = call(&fixture, "GET", "/account", Some(token), None).await;
    assert_eq!(status, StatusCode::OK, "{account}");
    assert!(account.to_string().contains("member@example.test"));
    assert!(!account.to_string().contains("admin@example.test"));
    // The admin's own PAT is untouched.
    let (_, account) = call(&fixture, "GET", "/account", Some(ADMIN_PAT), None).await;
    assert!(account.to_string().contains("admin@example.test"));
    assert_eq!(
        audit_actions(&fixture).await,
        vec![(
            "impersonation_started".to_owned(),
            Some(fixture.admin_id),
            Some(fixture.member_id)
        )]
    );
}

#[tokio::test]
async fn only_an_administrators_api_pat_may_ask_and_only_for_ordinary_active_accounts() {
    let fixture = fixture().await;
    // No credential, an unknown one, a member's PAT.
    let (status, body) = start(&fixture, None, fixture.member_id).await;
    assert_eq!(status, StatusCode::FORBIDDEN);
    assert_eq!(body["error"]["code"], "impersonation_forbidden");
    let (status, _) = start(&fixture, Some("sna_unknown"), fixture.member_id).await;
    assert_eq!(status, StatusCode::UNAUTHORIZED);
    let (status, body) = start(&fixture, Some(MEMBER_PAT), fixture.other_admin_id).await;
    assert_eq!(status, StatusCode::FORBIDDEN);
    assert_eq!(body["error"]["code"], "admin_required");

    // A browser session cookie is not a native credential.
    let request = Request::builder()
        .method("POST")
        .uri(format!(
            "/admin/users/{}/impersonation-token",
            fixture.member_id
        ))
        .header("host", HOST)
        .header("cookie", "__Host-stricknani_session=anything")
        .body(Body::empty())
        .unwrap();
    let response = fixture.app.clone().oneshot(request).await.unwrap();
    assert_eq!(response.status(), StatusCode::FORBIDDEN);

    // Self, another administrator, a disabled account, nobody.
    for target in [
        fixture.admin_id,
        fixture.other_admin_id,
        fixture.disabled_id,
    ] {
        let (status, body) = start(&fixture, Some(ADMIN_PAT), target).await;
        assert_eq!(status, StatusCode::CONFLICT, "{target}");
        assert_eq!(body["error"]["code"], "impersonation_invalid_target");
    }
    let (status, _) = start(&fixture, Some(ADMIN_PAT), 9999).await;
    assert_eq!(status, StatusCode::NOT_FOUND);
    let issued: i64 = sqlx::query_scalar("SELECT count(*) FROM impersonation_tokens")
        .fetch_one(&fixture.pool)
        .await
        .unwrap();
    assert_eq!(issued, 0);
    assert!(audit_actions(&fixture).await.is_empty());

    // The credential itself can neither ask for another one nor use
    // administration.
    let token = start_ok(&fixture, fixture.member_id).await;
    let (status, body) = start(&fixture, Some(&token), fixture.member_id).await;
    assert_eq!(status, StatusCode::FORBIDDEN);
    assert_eq!(body["error"]["code"], "impersonation_forbidden");
}

#[tokio::test]
async fn the_credential_carries_the_browser_flows_restrictions() {
    let fixture = fixture().await;
    let token = start_ok(&fixture, fixture.member_id).await;
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
        let (status, body) = call(&fixture, method, path, Some(&token), Some(json!({}))).await;
        assert_eq!(status, StatusCode::FORBIDDEN, "{method} {path}");
        assert_eq!(
            body["error"]["code"], "impersonation_forbidden",
            "{method} {path}"
        );
    }
    // Nothing was created or changed on the way.
    let tokens: i64 = sqlx::query_scalar("SELECT count(*) FROM api_tokens")
        .fetch_one(&fixture.pool)
        .await
        .unwrap();
    assert_eq!(tokens, 3);
    let hash: String = sqlx::query_scalar("SELECT password_hash FROM users WHERE id = ?")
        .bind(fixture.member_id)
        .fetch_one(&fixture.pool)
        .await
        .unwrap();
    assert!(!hash.is_empty());

    // The credential is not a PAT: it never counts as the cookie either.
    let request = Request::builder()
        .uri("/session")
        .header("host", HOST)
        .header("cookie", format!("__Host-stricknani_session={token}"))
        .body(Body::empty())
        .unwrap();
    let response = fixture.app.clone().oneshot(request).await.unwrap();
    let bytes = response.into_body().collect().await.unwrap().to_bytes();
    let session: Value = serde_json::from_slice(&bytes).unwrap();
    assert!(session["data"]["user"].is_null(), "{session}");
}

#[tokio::test]
async fn changes_made_with_the_credential_name_the_administrator() {
    let fixture = fixture().await;
    let token = start_ok(&fixture, fixture.member_id).await;
    let (status, project) = call(
        &fixture,
        "POST",
        "/projects",
        Some(&token),
        Some(json!({"name": "Mütze", "notes": "", "steps": []})),
    )
    .await;
    assert_eq!(status, StatusCode::CREATED, "{project}");
    let (status, _) = call(
        &fixture,
        "POST",
        "/projects",
        Some(MEMBER_PAT),
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
    let owner: i64 = sqlx::query_scalar("SELECT owner_id FROM projects WHERE name = 'Mütze'")
        .fetch_one(&fixture.pool)
        .await
        .unwrap();
    assert_eq!(owner, fixture.member_id);

    // The administrator's audit log shows who was behind it.
    let (status, audit) = call(&fixture, "GET", "/admin/audit", Some(ADMIN_PAT), None).await;
    assert_eq!(status, StatusCode::OK);
    let event = audit["data"]
        .as_array()
        .unwrap()
        .iter()
        .find(|event| event["resource"]["name"] == "Mütze")
        .unwrap();
    assert_eq!(event["impersonated_by"]["id"], fixture.admin_id);
}

#[tokio::test]
async fn stopping_revokes_the_credential_once_and_logs_it_once() {
    let fixture = fixture().await;
    let token = start_ok(&fixture, fixture.member_id).await;
    let (status, body) = call(
        &fixture,
        "POST",
        "/admin/impersonation/stop",
        Some(&token),
        None,
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{body}");
    assert_eq!(body["data"]["stopped"], true);
    let (status, body) = call(&fixture, "GET", "/account", Some(&token), None).await;
    assert_eq!(status, StatusCode::UNAUTHORIZED);
    assert_eq!(body["error"]["code"], "impersonation_expired");
    let (status, body) = call(
        &fixture,
        "POST",
        "/admin/impersonation/stop",
        Some(&token),
        None,
    )
    .await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(body["data"]["stopped"], false);
    let actions: Vec<String> = audit_actions(&fixture)
        .await
        .into_iter()
        .map(|(action, ..)| action)
        .collect();
    assert_eq!(actions, ["impersonation_started", "impersonation_stopped"]);

    // A token the server never issued has nothing to stop.
    let (status, _) = call(
        &fixture,
        "POST",
        "/admin/impersonation/stop",
        Some("sni_unknown"),
        None,
    )
    .await;
    assert_eq!(status, StatusCode::UNAUTHORIZED);
}

#[tokio::test]
async fn the_credential_expires_after_its_lifetime_and_the_end_is_logged_once() {
    let fixture = fixture().await;
    let token = start_ok(&fixture, fixture.member_id).await;
    sqlx::query("UPDATE impersonation_tokens SET created_at = unixepoch() - 7200, expires_at = unixepoch() - 1")
        .execute(&fixture.pool)
        .await
        .unwrap();
    for _ in 0..2 {
        let (status, body) = call(&fixture, "GET", "/account", Some(&token), None).await;
        assert_eq!(status, StatusCode::UNAUTHORIZED);
        assert_eq!(body["error"]["code"], "impersonation_expired");
    }
    let stopped = audit_actions(&fixture)
        .await
        .into_iter()
        .filter(|(action, ..)| action == "impersonation_stopped")
        .count();
    assert_eq!(stopped, 1);
}

#[tokio::test]
async fn disabling_the_target_or_demoting_the_administrator_ends_it() {
    let fixture = fixture().await;
    let token = start_ok(&fixture, fixture.member_id).await;
    sqlx::query("UPDATE users SET active = 0 WHERE id = ?")
        .bind(fixture.member_id)
        .execute(&fixture.pool)
        .await
        .unwrap();
    let (status, _) = call(&fixture, "GET", "/account", Some(&token), None).await;
    assert_eq!(status, StatusCode::UNAUTHORIZED);
    sqlx::query("UPDATE users SET active = 1 WHERE id = ?")
        .bind(fixture.member_id)
        .execute(&fixture.pool)
        .await
        .unwrap();
    // Revoked for good: re-enabling does not bring it back.
    let (status, _) = call(&fixture, "GET", "/account", Some(&token), None).await;
    assert_eq!(status, StatusCode::UNAUTHORIZED);

    let token = start_ok(&fixture, fixture.member_id).await;
    sqlx::query("UPDATE users SET is_admin = 0 WHERE id = ?")
        .bind(fixture.admin_id)
        .execute(&fixture.pool)
        .await
        .unwrap();
    let (status, _) = call(&fixture, "GET", "/account", Some(&token), None).await;
    assert_eq!(status, StatusCode::UNAUTHORIZED);
}

#[tokio::test]
async fn starting_another_ends_the_previous_and_the_pat_can_end_them_all() {
    let fixture = fixture().await;
    let first = start_ok(&fixture, fixture.member_id).await;
    let second = start_ok(&fixture, fixture.member_id).await;
    assert_ne!(first, second);
    let (status, _) = call(&fixture, "GET", "/account", Some(&first), None).await;
    assert_eq!(status, StatusCode::UNAUTHORIZED);
    let (status, _) = call(&fixture, "GET", "/account", Some(&second), None).await;
    assert_eq!(status, StatusCode::OK);

    // Another administrator's PAT ends only their own.
    let (_, body) = call(
        &fixture,
        "POST",
        "/admin/impersonation/stop",
        Some(OTHER_ADMIN_PAT),
        None,
    )
    .await;
    assert_eq!(body["data"]["stopped"], false);
    let (status, _) = call(&fixture, "GET", "/account", Some(&second), None).await;
    assert_eq!(status, StatusCode::OK);
    // A member's PAT may not.
    let (status, _) = call(
        &fixture,
        "POST",
        "/admin/impersonation/stop",
        Some(MEMBER_PAT),
        None,
    )
    .await;
    assert_eq!(status, StatusCode::FORBIDDEN);

    let (_, body) = call(
        &fixture,
        "POST",
        "/admin/impersonation/stop",
        Some(ADMIN_PAT),
        None,
    )
    .await;
    assert_eq!(body["data"]["stopped"], true);
    let (status, _) = call(&fixture, "GET", "/account", Some(&second), None).await;
    assert_eq!(status, StatusCode::UNAUTHORIZED);
    let stopped = audit_actions(&fixture)
        .await
        .into_iter()
        .filter(|(action, ..)| action == "impersonation_stopped")
        .count();
    assert_eq!(stopped, 2, "one per ended impersonation, none twice");
}

#[tokio::test]
async fn the_credential_is_stored_only_as_a_hash_and_leaves_the_pat_list_alone() {
    let fixture = fixture().await;
    let token = start_ok(&fixture, fixture.member_id).await;
    let stored: String = sqlx::query_scalar("SELECT token_hash FROM impersonation_tokens")
        .fetch_one(&fixture.pool)
        .await
        .unwrap();
    assert_ne!(stored, token);
    assert_eq!(stored, hex::encode(Sha256::digest(token.as_bytes())));
    // Not an api_tokens row: the member's PAT list does not show or count it.
    let (status, list) = call(
        &fixture,
        "GET",
        "/account/api-tokens",
        Some(MEMBER_PAT),
        None,
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{list}");
    assert_eq!(list["data"].as_array().map(Vec::len), Some(1));
}
