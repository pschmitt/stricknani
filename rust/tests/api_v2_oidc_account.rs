mod common;

use std::sync::Arc;

use axum::{
    body::Body,
    http::{Request, StatusCode},
};
use base64::{Engine, engine::general_purpose::URL_SAFE_NO_PAD};
use http_body_util::BodyExt;
use sha2::{Digest, Sha256};
use sqlx::SqlitePool;
use stricknani_server::{api_v2, db, oidc_http_state::OidcRuntime};
use tower::ServiceExt;

const HOST: &str = "app.example.test";
const ORIGIN: &str = "https://app.example.test";
const PASSWORD: &str = "oidc-account-test-password";

async fn fixture() -> (axum::Router, SqlitePool) {
    let pool = db::connect("sqlite::memory:")
        .await
        .expect("in-memory database should migrate");
    let state = db::AppState::new(pool.clone()).with_secure_cookies(true);
    (api_v2::router(state, false), pool)
}

async fn json(response: axum::response::Response) -> serde_json::Value {
    let bytes = response.into_body().collect().await.unwrap().to_bytes();
    serde_json::from_slice(&bytes).unwrap()
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

async fn signed_in(app: &axum::Router, email: &str) -> (String, String) {
    let bootstrap = app
        .clone()
        .oneshot(Request::get("/session").body(Body::empty()).unwrap())
        .await
        .unwrap();
    let preauth = cookie(&bootstrap, "__Host-stricknani_v2_login_csrf=");
    let pre_csrf = json(bootstrap).await["data"]["csrf_token"]
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
                    serde_json::json!({"email":email,"password":PASSWORD}).to_string(),
                ))
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(login.status(), StatusCode::OK);
    let session = cookie(&login, "__Host-stricknani_session=");
    let csrf = json(login).await["data"]["csrf_token"]
        .as_str()
        .unwrap()
        .to_owned();
    (session, csrf)
}

fn cookie_request(
    method: &str,
    path: &str,
    session: &str,
    csrf: Option<&str>,
    body: Option<String>,
) -> Request<Body> {
    let mut request = Request::builder()
        .method(method)
        .uri(path)
        .header("cookie", format!("__Host-stricknani_session={session}"));
    if let Some(csrf) = csrf {
        request = request
            .header("host", HOST)
            .header("origin", ORIGIN)
            .header("sec-fetch-site", "same-origin")
            .header("x-csrf-token", csrf);
    }
    if body.is_some() {
        request = request.header("content-type", "application/json");
    }
    request
        .body(body.map_or_else(Body::empty, Body::from))
        .unwrap()
}

fn pat_request(
    method: &str,
    path: &str,
    token: &str,
    proof: Option<&str>,
    body: Option<String>,
) -> Request<Body> {
    let mut request = Request::builder()
        .method(method)
        .uri(path)
        .header("authorization", format!("Bearer {token}"));
    if let Some(proof) = proof {
        request = request.header("x-reauth-token", proof);
    }
    if body.is_some() {
        request = request.header("content-type", "application/json");
    }
    request
        .body(body.map_or_else(Body::empty, Body::from))
        .unwrap()
}

async fn insert_user(pool: &SqlitePool, email: &str, is_admin: bool) -> i64 {
    let user_id = db::create_user(pool, email, PASSWORD.to_owned())
        .await
        .expect("fixture user should be created");
    if is_admin {
        sqlx::query("UPDATE users SET is_admin = 1 WHERE id = ?")
            .bind(user_id)
            .execute(pool)
            .await
            .expect("fixture role should be updated");
    }
    user_id
}

async fn insert_identity(pool: &SqlitePool, user_id: i64, subject: &str) -> i64 {
    sqlx::query_scalar(
        "INSERT INTO oidc_identities \
         (user_id, provider_id, issuer, subject, linked_at) \
         VALUES (?, 'authelia', 'https://issuer.example.test', ?, unixepoch()) \
         RETURNING id",
    )
    .bind(user_id)
    .bind(subject)
    .fetch_one(pool)
    .await
    .expect("fixture identity should be inserted")
}

async fn insert_pat(pool: &SqlitePool, user_id: i64, token: &str) {
    sqlx::query(
        "INSERT INTO api_tokens (user_id, name, token_hash, created_at) \
         VALUES (?, 'account test', ?, unixepoch())",
    )
    .bind(user_id)
    .bind(hex::encode(Sha256::digest(token.as_bytes())))
    .execute(pool)
    .await
    .expect("fixture PAT should be inserted");
}

async fn insert_oidc_pat(pool: &SqlitePool, user_id: i64, identity_id: i64, token: &str) {
    sqlx::query(
        "INSERT INTO api_tokens \
         (user_id, name, token_hash, created_at, expires_at, purpose, auth_origin, \
          oidc_identity_id, oidc_provider_id) \
         VALUES (?, 'oidc account test', ?, unixepoch(), unixepoch() + 3600, 'api', 'oidc', ?, 'authelia')",
    )
    .bind(user_id)
    .bind(hex::encode(Sha256::digest(token.as_bytes())))
    .bind(identity_id)
    .execute(pool)
    .await
    .expect("fixture OIDC PAT should be inserted");
}

#[tokio::test]
async fn reauth_schema_rejects_null_and_mismatched_unlink_targets() {
    let pool = db::connect("sqlite::memory:").await.unwrap();
    let owner = db::create_user(&pool, "owner@example.test", PASSWORD.to_owned())
        .await
        .unwrap();
    let other = db::create_user(&pool, "other@example.test", PASSWORD.to_owned())
        .await
        .unwrap();
    let token_id = sqlx::query(
        "INSERT INTO api_tokens (user_id, name, token_hash, created_at) \
         VALUES (?, 'test token', 'schema-proof-test-token-hash', unixepoch())",
    )
    .bind(owner)
    .execute(&pool)
    .await
    .unwrap()
    .last_insert_rowid();

    for (proof_hash, purpose, target_user_id, identity_id) in [
        ("a", "unlink_identity", owner, None),
        ("b", "unlink_identity", other, Some(1_i64)),
        ("c", "admin_unlink_identity", other, None),
    ] {
        let result = sqlx::query(
            "INSERT INTO auth_reauth_proofs \
             (proof_hash, user_id, api_token_id, purpose, target_user_id, identity_id, expires_at) \
             VALUES (?, ?, ?, ?, ?, ?, unixepoch() + 300)",
        )
        .bind(proof_hash.repeat(64))
        .bind(owner)
        .bind(token_id)
        .bind(purpose)
        .bind(target_user_id)
        .bind(identity_id)
        .execute(&pool)
        .await;
        assert!(result.is_err(), "schema accepted invalid {purpose} target");
    }
}

#[tokio::test]
async fn identity_list_is_owner_scoped_and_strict_for_pat_and_admin_access() {
    let (app, pool) = fixture().await;
    let owner = insert_user(&pool, "owner@example.test", false).await;
    let other = insert_user(&pool, "other@example.test", false).await;
    let admin = insert_user(&pool, "admin@example.test", true).await;
    let owner_identity = insert_identity(&pool, owner, "owner-private-subject").await;
    let other_identity = insert_identity(&pool, other, "other-private-subject").await;
    insert_oidc_pat(&pool, owner, owner_identity, "owner-current-oidc-pat").await;
    insert_pat(&pool, owner, "owner-secondary-pat").await;
    insert_pat(&pool, other, "other-pat").await;
    insert_pat(&pool, admin, "admin-pat").await;

    let owner_response = app
        .clone()
        .oneshot(pat_request(
            "GET",
            "/account/identities",
            "owner-current-oidc-pat",
            None,
            None,
        ))
        .await
        .unwrap();
    assert_eq!(owner_response.status(), StatusCode::OK);
    let owner_json = json(owner_response).await;
    assert_eq!(owner_json["data"].as_array().unwrap().len(), 1);
    assert_eq!(owner_json["data"][0]["id"], owner_identity);
    assert_eq!(owner_json["data"][0]["current_credential"], true);
    assert_eq!(owner_json["meta"]["password_enabled"], true);
    assert!(
        owner_json["data"][0]["subject_hint"]
            .as_str()
            .unwrap()
            .starts_with("id:")
    );
    assert!(!owner_json.to_string().contains("owner-private-subject"));

    let (session, _) = signed_in(&app, "owner@example.test").await;
    let cookie_response = app
        .clone()
        .oneshot(cookie_request(
            "GET",
            "/account/identities",
            &session,
            None,
            None,
        ))
        .await
        .unwrap();
    assert_eq!(cookie_response.status(), StatusCode::OK);
    assert_eq!(
        json(cookie_response).await["data"][0]["current_credential"],
        false
    );

    let invalid_bearer = app
        .clone()
        .oneshot(
            Request::get("/account/identities")
                .header("authorization", "Bearer invalid-token")
                .header("cookie", format!("__Host-stricknani_session={session}"))
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(invalid_bearer.status(), StatusCode::UNAUTHORIZED);

    let non_admin = app
        .clone()
        .oneshot(pat_request(
            "GET",
            &format!("/admin/users/{other}/identities"),
            "owner-secondary-pat",
            None,
            None,
        ))
        .await
        .unwrap();
    assert_eq!(non_admin.status(), StatusCode::FORBIDDEN);
    let admin_response = app
        .oneshot(pat_request(
            "GET",
            &format!("/admin/users/{other}/identities"),
            "admin-pat",
            None,
            None,
        ))
        .await
        .unwrap();
    assert_eq!(admin_response.status(), StatusCode::OK);
    let admin_json = json(admin_response).await;
    assert_eq!(admin_json["data"].as_array().unwrap().len(), 1);
    assert_eq!(admin_json["data"][0]["id"], other_identity);
    assert_ne!(admin_json["data"][0]["id"], owner_identity);
}

#[tokio::test]
async fn password_stepup_errors_are_distinct_from_session_expiry() {
    let (app, pool) = fixture().await;
    insert_user(&pool, "stepup@example.test", false).await;
    let (session, csrf) = signed_in(&app, "stepup@example.test").await;
    let body = |password: &str| {
        Some(serde_json::json!({"password":password,"action":"create_api_token"}).to_string())
    };
    let valid = app
        .clone()
        .oneshot(cookie_request(
            "POST",
            "/account/reauth",
            &session,
            Some(&csrf),
            body(PASSWORD),
        ))
        .await
        .unwrap();
    assert_eq!(valid.status(), StatusCode::OK);
    assert_eq!(json(valid).await["data"]["reauthenticated"], true);

    let invalid = app
        .clone()
        .oneshot(cookie_request(
            "POST",
            "/account/reauth",
            &session,
            Some(&csrf),
            body("incorrect-password"),
        ))
        .await
        .unwrap();
    assert_eq!(invalid.status(), StatusCode::UNAUTHORIZED);
    assert_eq!(json(invalid).await["error"]["code"], "invalid_credentials");

    let still_valid = app
        .oneshot(cookie_request(
            "GET",
            "/account/identities",
            &session,
            None,
            None,
        ))
        .await
        .unwrap();
    assert_eq!(still_valid.status(), StatusCode::OK);
}

#[tokio::test]
async fn native_proof_binds_pat_action_and_target_and_revokes_identity_token() {
    let (app, pool) = fixture().await;
    let user = insert_user(&pool, "native-proof@example.test", false).await;
    let first = insert_identity(&pool, user, "native-subject-one").await;
    let second = insert_identity(&pool, user, "native-subject-two").await;
    let third = insert_identity(&pool, user, "native-subject-three").await;
    insert_pat(&pool, user, "native-pat-one").await;
    insert_pat(&pool, user, "native-pat-two").await;

    let reauth = app
        .clone()
        .oneshot(pat_request(
            "POST",
            "/account/reauth",
            "native-pat-one",
            None,
            Some(
                serde_json::json!({
                    "password": PASSWORD,
                    "action": "unlink_identity",
                    "target": {"identity_id": first}
                })
                .to_string(),
            ),
        ))
        .await
        .unwrap();
    assert_eq!(reauth.status(), StatusCode::OK);
    let proof = json(reauth).await["data"]["reauth_token"]
        .as_str()
        .unwrap()
        .to_owned();

    let wrong_target = app
        .clone()
        .oneshot(pat_request(
            "DELETE",
            &format!("/account/identities/{second}"),
            "native-pat-one",
            Some(&proof),
            None,
        ))
        .await
        .unwrap();
    assert_eq!(wrong_target.status(), StatusCode::FORBIDDEN);
    assert_eq!(json(wrong_target).await["error"]["code"], "reauth_required");

    let wrong_pat = app
        .clone()
        .oneshot(pat_request(
            "DELETE",
            &format!("/account/identities/{first}"),
            "native-pat-two",
            Some(&proof),
            None,
        ))
        .await
        .unwrap();
    assert_eq!(wrong_pat.status(), StatusCode::FORBIDDEN);
    assert_eq!(json(wrong_pat).await["error"]["code"], "reauth_required");

    let removed = app
        .clone()
        .oneshot(pat_request(
            "DELETE",
            &format!("/account/identities/{first}"),
            "native-pat-one",
            Some(&proof),
            None,
        ))
        .await
        .unwrap();
    assert_eq!(removed.status(), StatusCode::NO_CONTENT);

    let replay = app
        .clone()
        .oneshot(pat_request(
            "DELETE",
            &format!("/account/identities/{third}"),
            "native-pat-one",
            Some(&proof),
            None,
        ))
        .await
        .unwrap();
    assert_eq!(replay.status(), StatusCode::FORBIDDEN);
    assert_eq!(json(replay).await["error"]["code"], "reauth_required");

    insert_oidc_pat(&pool, user, second, "native-current-oidc-pat").await;
    let current_token: i64 = sqlx::query_scalar("SELECT id FROM api_tokens WHERE token_hash = ?")
        .bind(hex::encode(Sha256::digest(b"native-current-oidc-pat")))
        .fetch_one(&pool)
        .await
        .unwrap();
    let current_reauth = app
        .clone()
        .oneshot(pat_request(
            "POST",
            "/account/reauth",
            "native-current-oidc-pat",
            None,
            Some(
                serde_json::json!({
                    "password": PASSWORD,
                    "action": "unlink_identity",
                    "target": {"identity_id": second}
                })
                .to_string(),
            ),
        ))
        .await
        .unwrap();
    assert_eq!(current_reauth.status(), StatusCode::OK);
    let current_proof = json(current_reauth).await["data"]["reauth_token"]
        .as_str()
        .unwrap()
        .to_owned();
    let current_unlink = app
        .clone()
        .oneshot(pat_request(
            "DELETE",
            &format!("/account/identities/{second}"),
            "native-current-oidc-pat",
            Some(&current_proof),
            None,
        ))
        .await
        .unwrap();
    assert_eq!(current_unlink.status(), StatusCode::NO_CONTENT);
    let token_state: (Option<i64>, Option<i64>) =
        sqlx::query_as("SELECT revoked_at, oidc_identity_id FROM api_tokens WHERE id = ?")
            .bind(current_token)
            .fetch_one(&pool)
            .await
            .unwrap();
    assert!(token_state.0.is_some());
    assert_eq!(token_state.1, None);
}

#[tokio::test]
async fn unlink_rejects_the_final_login_method_without_partial_revocation() {
    let (app, pool) = fixture().await;
    let user = insert_user(&pool, "last-method@example.test", false).await;
    let identity_id = insert_identity(&pool, user, "last-login-subject").await;
    let (session, csrf) = signed_in(&app, "last-method@example.test").await;
    sqlx::query("UPDATE users SET password_enabled = 0 WHERE id = ?")
        .bind(user)
        .execute(&pool)
        .await
        .unwrap();
    sqlx::query(
        "UPDATE api_tokens SET reauth_at = unixepoch() \
         WHERE user_id = ? AND purpose = 'browser' AND revoked_at IS NULL",
    )
    .bind(user)
    .execute(&pool)
    .await
    .unwrap();
    let response = app
        .clone()
        .oneshot(cookie_request(
            "DELETE",
            &format!("/account/identities/{identity_id}"),
            &session,
            Some(&csrf),
            None,
        ))
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::CONFLICT);
    assert_eq!(json(response).await["error"]["code"], "last_login_method");
    let identity_count: i64 =
        sqlx::query_scalar("SELECT count(*) FROM oidc_identities WHERE id = ? AND user_id = ?")
            .bind(identity_id)
            .bind(user)
            .fetch_one(&pool)
            .await
            .unwrap();
    assert_eq!(identity_count, 1);
}

#[tokio::test]
async fn native_link_start_validates_but_does_not_consume_its_reauth_proof() {
    let pool = db::connect("sqlite::memory:").await.unwrap();
    let user = insert_user(&pool, "link-proof@example.test", false).await;
    insert_pat(&pool, user, "native-link-pat").await;
    let issuer = common::oidc::MockOidcIssuer::start().await;
    let runtime =
        OidcRuntime::from_config(issuer.provider_config(vec!["stricknani://oidc-callback".into()]))
            .unwrap();
    let state = db::AppState::new(pool.clone())
        .with_secure_cookies(true)
        .with_oidc(Arc::new(runtime));
    let app = api_v2::router(state, false);

    let reauth = app
        .clone()
        .oneshot(pat_request(
            "POST",
            "/account/reauth",
            "native-link-pat",
            None,
            Some(
                serde_json::json!({
                    "password": PASSWORD,
                    "action": "link_identity",
                    "target": {"provider": "authelia"}
                })
                .to_string(),
            ),
        ))
        .await
        .unwrap();
    assert_eq!(reauth.status(), StatusCode::OK);
    let proof = json(reauth).await["data"]["reauth_token"]
        .as_str()
        .unwrap()
        .to_owned();
    let proof_hash = hex::encode(Sha256::digest(proof.as_bytes()));
    let start_body = serde_json::json!({
        "provider": "authelia",
        "client": "android",
        "redirect_uri": "stricknani://oidc-callback",
        "app_state": "native-link-state",
        "code_challenge": "A".repeat(43),
        "code_challenge_method": "S256"
    })
    .to_string();

    for _ in 0..2 {
        let response = app
            .clone()
            .oneshot(pat_request(
                "POST",
                "/account/identities",
                "native-link-pat",
                Some(&proof),
                Some(start_body.clone()),
            ))
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::OK);
        assert!(
            json(response).await["data"]["authorization_url"]
                .as_str()
                .is_some()
        );
    }

    let consumed_at: Option<i64> =
        sqlx::query_scalar("SELECT consumed_at FROM auth_reauth_proofs WHERE proof_hash = ?")
            .bind(proof_hash)
            .fetch_one(&pool)
            .await
            .unwrap();
    assert_eq!(consumed_at, None);
    assert_eq!(issuer.token_calls(), 0);
}

#[tokio::test]
async fn native_link_callback_verifies_signed_identity_and_exchanges_bound_grant() {
    let pool = db::connect("sqlite::memory:").await.unwrap();
    let user = insert_user(&pool, "signed-link@example.test", false).await;
    insert_pat(&pool, user, "signed-link-pat").await;
    let issuer = common::oidc::MockOidcIssuer::start().await;
    let runtime =
        OidcRuntime::from_config(issuer.provider_config(vec!["stricknani://oidc-callback".into()]))
            .unwrap();
    let state = db::AppState::new(pool.clone())
        .with_secure_cookies(true)
        .with_oidc(Arc::new(runtime));
    let app = api_v2::router(state, false);

    let reauth = app
        .clone()
        .oneshot(pat_request(
            "POST",
            "/account/reauth",
            "signed-link-pat",
            None,
            Some(
                serde_json::json!({
                    "password": PASSWORD,
                    "action": "link_identity",
                    "target": {"provider": "authelia"}
                })
                .to_string(),
            ),
        ))
        .await
        .unwrap();
    assert_eq!(reauth.status(), StatusCode::OK);
    let proof = json(reauth).await["data"]["reauth_token"]
        .as_str()
        .unwrap()
        .to_owned();

    let code_verifier = "v".repeat(43);
    let code_challenge = URL_SAFE_NO_PAD.encode(Sha256::digest(code_verifier.as_bytes()));
    let start = app
        .clone()
        .oneshot(pat_request(
            "POST",
            "/account/identities",
            "signed-link-pat",
            Some(&proof),
            Some(
                serde_json::json!({
                    "provider": "authelia",
                    "client": "android",
                    "redirect_uri": "stricknani://oidc-callback",
                    "app_state": "signed-link-app-state",
                    "code_challenge": code_challenge,
                    "code_challenge_method": "S256"
                })
                .to_string(),
            ),
        ))
        .await
        .unwrap();
    assert_eq!(start.status(), StatusCode::OK);
    let authorization_url = url::Url::parse(
        json(start).await["data"]["authorization_url"]
            .as_str()
            .unwrap(),
    )
    .unwrap();
    issuer
        .prepare_callback(
            &authorization_url,
            "signed-subject",
            "signed-link@example.test",
        )
        .await;
    let provider_state = authorization_url
        .query_pairs()
        .find_map(|(key, value)| (key == "state").then(|| value.into_owned()))
        .unwrap();
    let mut callback_url =
        url::Url::parse("https://app.example.test/auth/oidc/callback/authelia").unwrap();
    callback_url
        .query_pairs_mut()
        .append_pair("code", "provider-code")
        .append_pair("state", &provider_state)
        .append_pair("iss", issuer.issuer());
    let callback = app
        .clone()
        .oneshot(
            Request::get(callback_url.as_str())
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(callback.status(), StatusCode::SEE_OTHER);
    let native_redirect =
        url::Url::parse(callback.headers()["location"].to_str().unwrap()).unwrap();
    let flow = native_redirect
        .query_pairs()
        .find_map(|(key, value)| (key == "flow").then(|| value.into_owned()));
    assert_eq!(flow.as_deref(), Some("link"));
    let code = native_redirect
        .query_pairs()
        .find_map(|(key, value)| (key == "code").then(|| value.into_owned()))
        .unwrap();
    let exchange = app
        .oneshot(pat_request(
            "POST",
            "/account/identities/link/exchange",
            "signed-link-pat",
            Some(&proof),
            Some(
                serde_json::json!({
                    "code": code,
                    "code_verifier": code_verifier,
                    "redirect_uri": "stricknani://oidc-callback"
                })
                .to_string(),
            ),
        ))
        .await
        .unwrap();
    assert_eq!(exchange.status(), StatusCode::OK);
    let linked = json(exchange).await["data"]["identity"].clone();
    assert_eq!(linked["provider"], "authelia");
    assert_eq!(linked["email"], "signed-link@example.test");
    assert!(linked["subject_hint"].as_str().unwrap().starts_with("id:"));
    assert_eq!(issuer.token_calls(), 1);
    let identity_count: i64 =
        sqlx::query_scalar("SELECT count(*) FROM oidc_identities WHERE user_id = ?")
            .bind(user)
            .fetch_one(&pool)
            .await
            .unwrap();
    assert_eq!(identity_count, 1);
}
