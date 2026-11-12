//! A passkey can stand in for the password everywhere a step-up is needed, the database restore
//! included: the proof is bound to its action (a `manage_passkeys` proof does not restore), and a
//! native impersonation credential cannot reach the passkey routes at all.

use std::{fs, sync::Arc};

use axum::{
    Router,
    body::Body,
    http::{Request, StatusCode},
};
use http_body_util::BodyExt;
use serde_json::{Value, json};
use sha2::{Digest, Sha256};
use sqlx::SqlitePool;
use stricknani_server::{
    api_v2,
    backups::{BackupConfig, BackupService},
    db,
    passkeys::{PasskeyConfig, PasskeyRuntime},
    security::TrustedHosts,
};
use tower::ServiceExt;
use webauthn_authenticator_rs::{AuthenticatorBackend, softpasskey::SoftPasskey};
use webauthn_rs::prelude::{PublicKeyCredential, RegisterPublicKeyCredential, Url};

const ADMIN_TOKEN: &str = "sna_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
const PASSWORD: &str = "administrator-password";
const ORIGIN: &str = "https://wolle.example.org";
const BACKUP: &str = "predeploy-1.7.0-20260103-030000.sqlite3";

struct Fixture {
    app: Router,
    pool: SqlitePool,
    backups: std::path::PathBuf,
    _root: tempfile::TempDir,
}

async fn fixture() -> Fixture {
    let root = tempfile::tempdir().unwrap();
    let data = root.path().join("data");
    let backups = data.join("backups");
    let media = data.join("media");
    fs::create_dir_all(&backups).unwrap();
    fs::create_dir_all(&media).unwrap();
    let url = format!("sqlite:{}", data.join("stricknani.sqlite3").display());
    let pool = db::connect(&url).await.unwrap();
    let admin_id = db::create_user(&pool, "admin@example.test", PASSWORD.into())
        .await
        .unwrap();
    sqlx::query("UPDATE users SET is_admin = 1 WHERE id = ?")
        .bind(admin_id)
        .execute(&pool)
        .await
        .unwrap();
    sqlx::query(
        "INSERT INTO api_tokens (user_id, name, token_hash, created_at, purpose) \
         VALUES (?, 'app', ?, unixepoch(), 'api')",
    )
    .bind(admin_id)
    .bind(hex::encode(Sha256::digest(ADMIN_TOKEN.as_bytes())))
    .execute(&pool)
    .await
    .unwrap();
    // A real, current-schema backup to restore.
    sqlx::query("VACUUM INTO ?")
        .bind(backups.join(BACKUP).display().to_string())
        .execute(&pool)
        .await
        .unwrap();
    let config = BackupConfig::resolve(&url, None, Some(media.as_path()), false).unwrap();
    let service = Arc::new(BackupService::for_config(config));
    let passkeys = PasskeyConfig::from_settings(
        true,
        None,
        &TrustedHosts::parse("wolle.example.org").unwrap(),
        true,
        7674,
        None,
    )
    .unwrap()
    .unwrap();
    let state = db::AppState::new(pool.clone())
        .with_secure_cookies(true)
        .with_media_root(media)
        .with_backups(service)
        .with_passkeys(Arc::new(PasskeyRuntime::new(&passkeys).unwrap()));
    Fixture {
        app: api_v2::router(state, false),
        pool,
        backups,
        _root: root,
    }
}

async fn call(
    f: &Fixture,
    method: &str,
    path: &str,
    token: &str,
    reauth: Option<&str>,
    body: Value,
) -> (StatusCode, Value) {
    let mut request = Request::builder()
        .method(method)
        .uri(path)
        .header("authorization", format!("Bearer {token}"))
        .header("content-type", "application/json");
    if let Some(reauth) = reauth {
        request = request.header("x-reauth-token", reauth);
    }
    let response = f
        .app
        .clone()
        .oneshot(request.body(Body::from(body.to_string())).unwrap())
        .await
        .unwrap();
    let status = response.status();
    let bytes = response.into_body().collect().await.unwrap().to_bytes();
    (
        status,
        serde_json::from_slice(&bytes).unwrap_or(Value::Null),
    )
}

async fn password_proof(f: &Fixture, action: &str) -> String {
    let (status, body) = call(
        f,
        "POST",
        "/account/reauth",
        ADMIN_TOKEN,
        None,
        json!({"password": PASSWORD, "action": action}),
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{body}");
    body["data"]["reauth_token"].as_str().unwrap().to_owned()
}

fn url(value: &str) -> Url {
    Url::parse(value).unwrap()
}

/// Register a passkey for the administrator (native flow, password step-up).
async fn register(f: &Fixture, authenticator: &mut SoftPasskey) {
    let proof = password_proof(f, "manage_passkeys").await;
    let (status, options) = call(
        f,
        "POST",
        "/account/passkeys/registration/options",
        ADMIN_TOKEN,
        Some(&proof),
        json!({}),
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{options}");
    let public_key = serde_json::from_value::<webauthn_rs::prelude::CreationChallengeResponse>(
        options["data"]["options"].clone(),
    )
    .unwrap()
    .public_key;
    let credential: RegisterPublicKeyCredential = authenticator
        .perform_register(url(ORIGIN), public_key, 60_000)
        .unwrap();
    let (status, body) = call(
        f,
        "POST",
        "/account/passkeys/registration/verify",
        ADMIN_TOKEN,
        Some(&proof),
        json!({
            "challenge_id": options["data"]["challenge_id"],
            "credential": credential,
            "nickname": "Pixel",
        }),
    )
    .await;
    assert_eq!(status, StatusCode::CREATED, "{body}");
}

/// A passkey step-up for `action`: the one-time proof.
async fn passkey_proof(f: &Fixture, authenticator: &mut SoftPasskey, action: &str) -> String {
    let (status, options) = call(
        f,
        "POST",
        "/account/reauth/passkey/options",
        ADMIN_TOKEN,
        None,
        json!({}),
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{options}");
    let request = serde_json::from_value::<webauthn_rs::prelude::RequestChallengeResponse>(
        options["data"]["options"].clone(),
    )
    .unwrap();
    let assertion: PublicKeyCredential = authenticator
        .perform_auth(url(ORIGIN), request.public_key, 60_000)
        .unwrap();
    let (status, body) = call(
        f,
        "POST",
        "/account/reauth/passkey",
        ADMIN_TOKEN,
        None,
        json!({
            "challenge_id": options["data"]["challenge_id"],
            "credential": assertion,
            "action": action,
        }),
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{body}");
    body["data"]["reauth_token"].as_str().unwrap().to_owned()
}

#[tokio::test]
async fn a_database_restore_accepts_a_passkey_instead_of_the_password() {
    let f = fixture().await;
    let mut authenticator = SoftPasskey::new(true);
    register(&f, &mut authenticator).await;
    let path = format!("/admin/backups/{BACKUP}/restore");
    let request = json!({"confirmation": BACKUP});

    // Nothing without a proof.
    let (status, body) = call(&f, "POST", &path, ADMIN_TOKEN, None, request.clone()).await;
    assert_eq!(status, StatusCode::FORBIDDEN, "{body}");

    // A passkey proof for managing passkeys is not a proof for restoring.
    let wrong = passkey_proof(&f, &mut authenticator, "manage_passkeys").await;
    let (status, body) = call(
        &f,
        "POST",
        &path,
        ADMIN_TOKEN,
        Some(&wrong),
        request.clone(),
    )
    .await;
    assert_eq!(status, StatusCode::FORBIDDEN, "{body}");
    assert!(
        !f.backups.join(".restore-request.json").exists(),
        "nothing is staged by a wrong-action proof"
    );

    // The right one stages the restore.
    let proof = passkey_proof(&f, &mut authenticator, "restore_backup").await;
    let (status, body) = call(
        &f,
        "POST",
        &path,
        ADMIN_TOKEN,
        Some(&proof),
        request.clone(),
    )
    .await;
    assert_eq!(status, StatusCode::ACCEPTED, "{body}");
    assert!(f.backups.join(".restore-request.json").exists());

    // And it is spent.
    fs::remove_file(f.backups.join(".restore-request.json")).unwrap();
    let (status, _) = call(&f, "POST", &path, ADMIN_TOKEN, Some(&proof), request).await;
    assert_eq!(status, StatusCode::FORBIDDEN);
}

#[tokio::test]
async fn every_step_up_action_accepts_a_passkey_proof() {
    let f = fixture().await;
    let mut authenticator = SoftPasskey::new(true);
    register(&f, &mut authenticator).await;
    // Actions without a target.
    for action in [
        "change_password",
        "create_api_token",
        "delete_account",
        "restore_backup",
        "manage_passkeys",
    ] {
        let proof = passkey_proof(&f, &mut authenticator, action).await;
        assert!(proof.starts_with("reauth_"), "{action}");
        let purpose: String =
            sqlx::query_scalar("SELECT purpose FROM auth_reauth_proofs WHERE proof_hash = ?")
                .bind(hex::encode(Sha256::digest(proof.as_bytes())))
                .fetch_one(&f.pool)
                .await
                .unwrap();
        assert_eq!(purpose, action);
    }
}

#[tokio::test]
async fn a_native_impersonation_credential_cannot_use_passkey_routes() {
    let f = fixture().await;
    let member = db::create_user(
        &f.pool,
        "member@example.test",
        "member-password-long".into(),
    )
    .await
    .unwrap();
    let (status, started) = call(
        &f,
        "POST",
        &format!("/admin/users/{member}/impersonation-token"),
        ADMIN_TOKEN,
        None,
        json!({}),
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{started}");
    let token = started["data"]["token"].as_str().unwrap().to_owned();
    for (method, path) in [
        ("GET", "/account/passkeys"),
        ("POST", "/account/passkeys/registration/options"),
        ("POST", "/account/reauth/passkey/options"),
        ("POST", "/account/reauth/passkey"),
    ] {
        let (status, body) = call(&f, method, path, &token, None, json!({})).await;
        assert!(
            status == StatusCode::FORBIDDEN || status == StatusCode::UNAUTHORIZED,
            "{path}: {status} {body}"
        );
    }
}
