//! Passkeys (WebAuthn): registration, sign-in on the web and for native apps, step-up, management
//! and the failure modes (reused challenge, wrong origin or RP ID, disabled account, missing
//! step-up, other people's passkeys). A software authenticator plays the browser and the phone.

use std::sync::Arc;

use axum::{
    body::Body,
    http::{Request, StatusCode},
};
use base64::{Engine, engine::general_purpose::URL_SAFE_NO_PAD};
use http_body_util::BodyExt;
use sha2::{Digest, Sha256};
use sqlx::SqlitePool;
use stricknani_server::{
    api_v2,
    app_links::AppLinks,
    db,
    passkeys::{PasskeyConfig, PasskeyRuntime},
    security::TrustedHosts,
};
use tower::ServiceExt;
use webauthn_authenticator_rs::{
    AuthenticatorBackend, WebauthnAuthenticator, softpasskey::SoftPasskey,
};
use webauthn_rs::prelude::{
    CreationChallengeResponse, PublicKeyCredential, RegisterPublicKeyCredential,
    RequestChallengeResponse, Url,
};

const HOST: &str = "wolle.example.org";
const ORIGIN: &str = "https://wolle.example.org";
const FINGERPRINT: &str = "6150524c3401c232a8b582e873d692e71ad9c6726317fc6c69889040f60da11f";
const ANDROID_ORIGIN: &str = "android:apk-key-hash:YVBSTDQBwjKotYLoc9aS5xrZxnJjF_xsaYiQQPYNoR8";
const EMAIL: &str = "knitter@example.test";
const PASSWORD: &str = "passkey-test-password-long";

struct Fixture {
    app: axum::Router,
    pool: SqlitePool,
    user_id: i64,
}

fn runtime() -> Arc<PasskeyRuntime> {
    let links = AppLinks::parse(None, Some(FINGERPRINT)).unwrap().unwrap();
    let config = PasskeyConfig::from_settings(
        true,
        None,
        &TrustedHosts::parse(HOST).unwrap(),
        true,
        7674,
        Some(&links),
    )
    .unwrap()
    .unwrap();
    Arc::new(PasskeyRuntime::new(&config).unwrap())
}

async fn fixture() -> Fixture {
    let pool = db::connect("sqlite::memory:").await.unwrap();
    let state = db::AppState::new(pool.clone())
        .with_secure_cookies(true)
        .with_passkeys(runtime());
    let user_id = db::create_user(&pool, EMAIL, PASSWORD.to_owned())
        .await
        .unwrap();
    Fixture {
        app: api_v2::router(state, false),
        pool,
        user_id,
    }
}

async fn json(response: axum::response::Response) -> serde_json::Value {
    let bytes = response.into_body().collect().await.unwrap().to_bytes();
    serde_json::from_slice(&bytes).unwrap_or(serde_json::Value::Null)
}

fn set_cookie(response: &axum::response::Response, prefix: &str) -> Option<String> {
    response
        .headers()
        .get_all("set-cookie")
        .iter()
        .filter_map(|value| value.to_str().ok())
        .find_map(|value| value.strip_prefix(prefix))
        .and_then(|value| value.split(';').next())
        .map(str::to_owned)
}

async fn send(f: &Fixture, request: Request<Body>) -> axum::response::Response {
    f.app.clone().oneshot(request).await.unwrap()
}

/// An anonymous browser: the login CSRF cookie and token from `GET /session`.
async fn anonymous(f: &Fixture) -> (String, String) {
    let response = send(f, Request::get("/session").body(Body::empty()).unwrap()).await;
    let cookie = set_cookie(&response, "__Host-stricknani_v2_login_csrf=").unwrap();
    let csrf = json(response).await["data"]["csrf_token"]
        .as_str()
        .unwrap()
        .to_owned();
    (cookie, csrf)
}

fn login_post(
    path: &str,
    cookie: &str,
    csrf: &str,
    origin: &str,
    body: serde_json::Value,
) -> Request<Body> {
    Request::post(path)
        .header("host", HOST)
        .header("origin", origin)
        .header("sec-fetch-site", "same-origin")
        .header(
            "cookie",
            format!("__Host-stricknani_v2_login_csrf={cookie}"),
        )
        .header("x-csrf-token", csrf)
        .header("content-type", "application/json")
        .body(Body::from(body.to_string()))
        .unwrap()
}

/// A signed-in browser: its session cookie and CSRF token (password sign-in).
async fn password_session(f: &Fixture) -> (String, String) {
    let (cookie, csrf) = anonymous(f).await;
    let response = send(
        f,
        login_post(
            "/auth/login",
            &cookie,
            &csrf,
            ORIGIN,
            serde_json::json!({"email": EMAIL, "password": PASSWORD}),
        ),
    )
    .await;
    assert_eq!(response.status(), StatusCode::OK);
    let session = set_cookie(&response, "__Host-stricknani_session=").unwrap();
    let csrf = json(response).await["data"]["csrf_token"]
        .as_str()
        .unwrap()
        .to_owned();
    (session, csrf)
}

fn browser(
    method: &str,
    path: &str,
    session: &(String, String),
    body: Option<serde_json::Value>,
) -> Request<Body> {
    let mut request = Request::builder()
        .method(method)
        .uri(path)
        .header("host", HOST)
        .header("origin", ORIGIN)
        .header("sec-fetch-site", "same-origin")
        .header("cookie", format!("__Host-stricknani_session={}", session.0))
        .header("x-csrf-token", &session.1);
    if body.is_some() {
        request = request.header("content-type", "application/json");
    }
    request
        .body(body.map_or_else(Body::empty, |body| Body::from(body.to_string())))
        .unwrap()
}

fn bearer(
    method: &str,
    path: &str,
    token: &str,
    reauth: Option<&str>,
    body: Option<serde_json::Value>,
) -> Request<Body> {
    let mut request = Request::builder()
        .method(method)
        .uri(path)
        .header("authorization", format!("Bearer {token}"));
    if let Some(reauth) = reauth {
        request = request.header("x-reauth-token", reauth);
    }
    if body.is_some() {
        request = request.header("content-type", "application/json");
    }
    request
        .body(body.map_or_else(Body::empty, |body| Body::from(body.to_string())))
        .unwrap()
}

async fn add_pat(f: &Fixture, secret: &str) {
    sqlx::query(
        "INSERT INTO api_tokens (user_id, name, token_hash, purpose, created_at) \
         VALUES (?, 'fixture', ?, 'api', unixepoch())",
    )
    .bind(f.user_id)
    .bind(hex::encode(Sha256::digest(secret.as_bytes())))
    .execute(&f.pool)
    .await
    .unwrap();
}

fn url(value: &str) -> Url {
    Url::parse(value).unwrap()
}

fn creation(options: &serde_json::Value) -> CreationChallengeResponse {
    serde_json::from_value(options.clone()).expect("registration options deserialize")
}

fn request_options(options: &serde_json::Value) -> RequestChallengeResponse {
    serde_json::from_value(options.clone()).expect("authentication options deserialize")
}

/// The options as the authenticator sees them once it has found a credential for the site: that
/// credential in the allow list (a phone discovers it itself; the software token needs the hint).
fn request_options_for(
    options: &serde_json::Value,
    credential_id: &[u8],
    rp_id: Option<&str>,
) -> RequestChallengeResponse {
    let mut options = options.clone();
    options["publicKey"]["allowCredentials"] = serde_json::json!([{
        "type": "public-key",
        "id": URL_SAFE_NO_PAD.encode(credential_id),
    }]);
    if let Some(rp_id) = rp_id {
        options["publicKey"]["rpId"] = rp_id.into();
    }
    request_options(&options)
}

/// Browser step-up with the password for managing passkeys.
async fn browser_stepup(f: &Fixture, session: &(String, String)) {
    let response = send(
        f,
        browser(
            "POST",
            "/account/reauth",
            session,
            Some(serde_json::json!({"password": PASSWORD, "action": "manage_passkeys"})),
        ),
    )
    .await;
    assert_eq!(response.status(), StatusCode::OK);
}

/// Native step-up: a one-time proof for managing passkeys.
async fn native_stepup(f: &Fixture, token: &str) -> String {
    let response = send(
        f,
        bearer(
            "POST",
            "/account/reauth",
            token,
            None,
            Some(serde_json::json!({"password": PASSWORD, "action": "manage_passkeys"})),
        ),
    )
    .await;
    assert_eq!(response.status(), StatusCode::OK);
    json(response).await["data"]["reauth_token"]
        .as_str()
        .unwrap()
        .to_owned()
}

/// What a browser does for a registration: options, authenticator, verify. Returns the user
/// handle (`user.id`) the authenticator stored and the response status and body.
async fn register_in_browser(
    f: &Fixture,
    session: &(String, String),
    authenticator: &mut SoftPasskey,
    nickname: &str,
) -> (axum::http::StatusCode, serde_json::Value) {
    let options = send(
        f,
        browser(
            "POST",
            "/account/passkeys/registration/options",
            session,
            Some(serde_json::json!({})),
        ),
    )
    .await;
    assert_eq!(options.status(), StatusCode::OK);
    let options = json(options).await;
    let challenge_id = options["data"]["challenge_id"].as_str().unwrap().to_owned();
    let public_key = &options["data"]["options"]["publicKey"];
    assert_eq!(public_key["rp"]["id"], HOST);
    assert_eq!(
        public_key["authenticatorSelection"]["residentKey"],
        "preferred"
    );
    assert_eq!(
        public_key["authenticatorSelection"]["userVerification"],
        "required"
    );
    let credential: RegisterPublicKeyCredential = authenticator
        .do_registration(url(ORIGIN), creation(&options["data"]["options"]))
        .unwrap();
    let response = send(
        f,
        browser(
            "POST",
            "/account/passkeys/registration/verify",
            session,
            Some(serde_json::json!({
                "challenge_id": challenge_id,
                "credential": credential,
                "nickname": nickname,
            })),
        ),
    )
    .await;
    (response.status(), json(response).await)
}

/// The sign-in options a browser fetches on the login page.
async fn login_options(f: &Fixture) -> (String, String, String, serde_json::Value) {
    let (cookie, csrf) = anonymous(f).await;
    let response = send(
        f,
        login_post(
            "/auth/passkey/options",
            &cookie,
            &csrf,
            ORIGIN,
            serde_json::json!({}),
        ),
    )
    .await;
    assert_eq!(response.status(), StatusCode::OK);
    let body = json(response).await;
    let challenge_id = body["data"]["challenge_id"].as_str().unwrap().to_owned();
    assert!(
        body["data"]["options"].get("mediation").is_none(),
        "the client chooses modal or autofill"
    );
    (cookie, csrf, challenge_id, body["data"]["options"].clone())
}

/// A discoverable sign-in assertion: the authenticator is told which credential it holds for the
/// site (what it discovers itself on a phone) and returns the user handle.
fn assert_discoverable(
    authenticator: &mut SoftPasskey,
    origin: &str,
    options: &serde_json::Value,
    credential_id: &[u8],
    user_handle: &[u8],
) -> PublicKeyCredential {
    // Straight to the backend: a page on the wrong origin is exactly what the browser would
    // refuse to forward, so the high-level checks must not stand in the way of the test.
    let options = request_options_for(options, credential_id, None);
    let mut credential = authenticator
        .perform_auth(url(origin), options.public_key, 60_000)
        .unwrap();
    credential.response.user_handle = Some(user_handle.to_vec());
    credential
}

async fn stored_credential(f: &Fixture) -> (Vec<u8>, Vec<u8>) {
    sqlx::query_as("SELECT credential_id, user_handle FROM passkeys ORDER BY id LIMIT 1")
        .fetch_one(&f.pool)
        .await
        .unwrap()
}

async fn count_audit(f: &Fixture, action: &str) -> i64 {
    sqlx::query_scalar("SELECT count(*) FROM auth_audit_events WHERE action = ?")
        .bind(action)
        .fetch_one(&f.pool)
        .await
        .unwrap()
}

#[tokio::test]
async fn a_passkey_registers_and_signs_in_on_the_web_like_a_password() {
    let f = fixture().await;
    let session = password_session(&f).await;
    let mut authenticator = SoftPasskey::new(true);

    // Without recent authentication nothing starts.
    let denied = send(
        &f,
        browser(
            "POST",
            "/account/passkeys/registration/options",
            &session,
            Some(serde_json::json!({})),
        ),
    )
    .await;
    assert_eq!(denied.status(), StatusCode::FORBIDDEN);
    assert_eq!(json(denied).await["error"]["code"], "reauth_required");

    browser_stepup(&f, &session).await;
    let (status, created) = register_in_browser(&f, &session, &mut authenticator, "Pixel").await;
    assert_eq!(status, StatusCode::CREATED, "{created}");
    assert_eq!(created["data"]["nickname"], "Pixel");
    assert!(created["data"]["last_used_at"].is_null());
    assert_eq!(count_audit(&f, "passkey_registered").await, 1);

    let listed = json(send(&f, browser("GET", "/account/passkeys", &session, None)).await).await;
    assert_eq!(listed["data"].as_array().unwrap().len(), 1);
    assert_eq!(listed["meta"]["limit"], 10);
    let text = listed.to_string();
    assert!(
        !text.contains("passkey\"")
            && !text.contains("credential_id")
            && !text.contains("user_handle"),
        "no credential internals in the list"
    );

    // Sign in username-less, as the browser's autofill would.
    let (credential_id, user_handle) = stored_credential(&f).await;
    let (cookie, csrf, challenge_id, options) = login_options(&f).await;
    let credential = assert_discoverable(
        &mut authenticator,
        ORIGIN,
        &options,
        &credential_id,
        &user_handle,
    );
    let response = send(
        &f,
        login_post(
            "/auth/passkey/login",
            &cookie,
            &csrf,
            ORIGIN,
            serde_json::json!({"challenge_id": challenge_id, "credential": credential}),
        ),
    )
    .await;
    assert_eq!(response.status(), StatusCode::OK);
    let new_session = set_cookie(&response, "__Host-stricknani_session=")
        .expect("same session cookie as password sign-in");
    let body = json(response).await;
    assert_eq!(body["data"]["user"]["email"], EMAIL);
    let new_csrf = body["data"]["csrf_token"].as_str().unwrap().to_owned();
    assert_ne!(new_csrf, csrf);
    let purpose: String = sqlx::query_scalar("SELECT purpose FROM api_tokens WHERE token_hash = ?")
        .bind(hex::encode(Sha256::digest(new_session.as_bytes())))
        .fetch_one(&f.pool)
        .await
        .unwrap();
    assert_eq!(purpose, "browser");
    let account = send(
        &f,
        browser("GET", "/account", &(new_session.clone(), new_csrf), None),
    )
    .await;
    assert_eq!(account.status(), StatusCode::OK);
    assert_eq!(count_audit(&f, "passkey_login_succeeded").await, 1);
    let last_used: Option<i64> = sqlx::query_scalar("SELECT last_used_at FROM passkeys")
        .fetch_one(&f.pool)
        .await
        .unwrap();
    assert!(last_used.is_some());
}

#[tokio::test]
async fn a_challenge_works_once() {
    let f = fixture().await;
    let session = password_session(&f).await;
    browser_stepup(&f, &session).await;
    let mut authenticator = SoftPasskey::new(true);
    register_in_browser(&f, &session, &mut authenticator, "Key").await;
    let (credential_id, user_handle) = stored_credential(&f).await;

    let (cookie, csrf, challenge_id, options) = login_options(&f).await;
    let credential = assert_discoverable(
        &mut authenticator,
        ORIGIN,
        &options,
        &credential_id,
        &user_handle,
    );
    let body = serde_json::json!({"challenge_id": challenge_id, "credential": credential});
    let first = send(
        &f,
        login_post("/auth/passkey/login", &cookie, &csrf, ORIGIN, body.clone()),
    )
    .await;
    assert_eq!(first.status(), StatusCode::OK);
    let replay = send(
        &f,
        login_post("/auth/passkey/login", &cookie, &csrf, ORIGIN, body),
    )
    .await;
    assert_eq!(replay.status(), StatusCode::BAD_REQUEST);
    assert_eq!(json(replay).await["error"]["code"], "invalid_challenge");

    // An unknown challenge id is the same.
    let (cookie, csrf, _, options) = login_options(&f).await;
    let credential = assert_discoverable(
        &mut authenticator,
        ORIGIN,
        &options,
        &credential_id,
        &user_handle,
    );
    let unknown = send(
        &f,
        login_post(
            "/auth/passkey/login",
            &cookie,
            &csrf,
            ORIGIN,
            serde_json::json!({"challenge_id": "nope", "credential": credential}),
        ),
    )
    .await;
    assert_eq!(unknown.status(), StatusCode::BAD_REQUEST);
}

#[tokio::test]
async fn a_wrong_origin_or_rp_id_is_rejected() {
    let f = fixture().await;
    let session = password_session(&f).await;
    browser_stepup(&f, &session).await;
    let mut authenticator = SoftPasskey::new(true);
    register_in_browser(&f, &session, &mut authenticator, "Key").await;
    let (credential_id, user_handle) = stored_credential(&f).await;

    // The page is on another origin: the client data names it, the server refuses.
    let (cookie, csrf, challenge_id, options) = login_options(&f).await;
    let credential = assert_discoverable(
        &mut authenticator,
        "https://wolle.evil.example.test",
        &options,
        &credential_id,
        &user_handle,
    );
    let response = send(
        &f,
        login_post(
            "/auth/passkey/login",
            &cookie,
            &csrf,
            ORIGIN,
            serde_json::json!({"challenge_id": challenge_id, "credential": credential}),
        ),
    )
    .await;
    assert_eq!(response.status(), StatusCode::UNAUTHORIZED);
    assert_eq!(json(response).await["error"]["code"], "invalid_passkey");

    // Signed for another RP ID: the authenticator data carries a different RP ID hash.
    let (cookie, csrf, challenge_id, options) = login_options(&f).await;
    let tampered = request_options_for(&options, &credential_id, Some("other.example.org"));
    let mut credential = authenticator
        .perform_auth(url(ORIGIN), tampered.public_key, 60_000)
        .unwrap();
    credential.response.user_handle = Some(user_handle.clone());
    let response = send(
        &f,
        login_post(
            "/auth/passkey/login",
            &cookie,
            &csrf,
            ORIGIN,
            serde_json::json!({"challenge_id": challenge_id, "credential": credential}),
        ),
    )
    .await;
    assert_eq!(response.status(), StatusCode::UNAUTHORIZED);

    // And the user handle must belong to the credential.
    let (cookie, csrf, challenge_id, options) = login_options(&f).await;
    let credential = assert_discoverable(
        &mut authenticator,
        ORIGIN,
        &options,
        &credential_id,
        &[7_u8; 16],
    );
    let response = send(
        &f,
        login_post(
            "/auth/passkey/login",
            &cookie,
            &csrf,
            ORIGIN,
            serde_json::json!({"challenge_id": challenge_id, "credential": credential}),
        ),
    )
    .await;
    assert_eq!(response.status(), StatusCode::UNAUTHORIZED);
    assert_eq!(count_audit(&f, "passkey_login_succeeded").await, 0);
}

#[tokio::test]
async fn a_disabled_account_cannot_sign_in_with_a_passkey() {
    let f = fixture().await;
    let session = password_session(&f).await;
    browser_stepup(&f, &session).await;
    let mut authenticator = SoftPasskey::new(true);
    register_in_browser(&f, &session, &mut authenticator, "Key").await;
    let (credential_id, user_handle) = stored_credential(&f).await;
    sqlx::query("UPDATE users SET active = 0 WHERE id = ?")
        .bind(f.user_id)
        .execute(&f.pool)
        .await
        .unwrap();

    let (cookie, csrf, challenge_id, options) = login_options(&f).await;
    let credential = assert_discoverable(
        &mut authenticator,
        ORIGIN,
        &options,
        &credential_id,
        &user_handle,
    );
    let response = send(
        &f,
        login_post(
            "/auth/passkey/login",
            &cookie,
            &csrf,
            ORIGIN,
            serde_json::json!({"challenge_id": challenge_id, "credential": credential}),
        ),
    )
    .await;
    assert_eq!(response.status(), StatusCode::UNAUTHORIZED);
    assert!(set_cookie(&response, "__Host-stricknani_session=").is_none());
}

#[tokio::test]
async fn web_sign_in_needs_the_login_csrf_and_the_same_origin() {
    let f = fixture().await;
    let (cookie, csrf) = anonymous(&f).await;
    let cross_site = send(
        &f,
        login_post(
            "/auth/passkey/options",
            &cookie,
            &csrf,
            "https://evil.example.test",
            serde_json::json!({}),
        ),
    )
    .await;
    assert_eq!(cross_site.status(), StatusCode::FORBIDDEN);
    let bad_csrf = send(
        &f,
        login_post(
            "/auth/passkey/options",
            &cookie,
            "not-the-token",
            ORIGIN,
            serde_json::json!({}),
        ),
    )
    .await;
    assert_eq!(bad_csrf.status(), StatusCode::FORBIDDEN);
}

#[tokio::test]
async fn an_android_app_signs_in_for_the_same_token_password_login_gives() {
    let f = fixture().await;
    add_pat(&f, "app-pat").await;
    let proof = native_stepup(&f, "app-pat").await;
    let mut authenticator = SoftPasskey::new(true);

    // The app asks for creation options (extensions are dropped for Credential Manager).
    let options = send(
        &f,
        bearer(
            "POST",
            "/account/passkeys/registration/options",
            "app-pat",
            Some(&proof),
            Some(serde_json::json!({})),
        ),
    )
    .await;
    assert_eq!(options.status(), StatusCode::OK);
    let options = json(options).await;
    assert!(
        options["data"]["options"]["publicKey"]
            .get("extensions")
            .is_none()
    );
    let challenge_id = options["data"]["challenge_id"].as_str().unwrap().to_owned();
    // Credential Manager reports the app origin, not the web origin.
    let public_key = creation(&options["data"]["options"]).public_key;
    let mut credential = serde_json::to_value(
        authenticator
            .perform_register(url(ANDROID_ORIGIN), public_key, 60_000)
            .unwrap(),
    )
    .unwrap();
    // Credential Manager's JSON carries more than the server needs.
    credential.as_object_mut().unwrap().remove("extensions");
    credential["authenticatorAttachment"] = "platform".into();
    credential["clientExtensionResults"] = serde_json::json!({"credProps": {"rk": true}});
    let created = send(
        &f,
        bearer(
            "POST",
            "/account/passkeys/registration/verify",
            "app-pat",
            Some(&proof),
            Some(serde_json::json!({
                "challenge_id": challenge_id,
                "credential": credential,
                "nickname": "Pixel 9"
            })),
        ),
    )
    .await;
    assert_eq!(
        created.status(),
        StatusCode::CREATED,
        "{}",
        json(created).await
    );

    // The proof was consumed by the registration: it cannot be used again.
    let again = send(
        &f,
        bearer(
            "POST",
            "/account/passkeys/registration/options",
            "app-pat",
            Some(&proof),
            Some(serde_json::json!({})),
        ),
    )
    .await;
    assert_eq!(again.status(), StatusCode::FORBIDDEN);

    // Sign in from the app, no cookies, no CSRF.
    let (credential_id, user_handle) = stored_credential(&f).await;
    let options = json(
        send(
            &f,
            Request::post("/auth/passkey/token/options")
                .header("content-type", "application/json")
                .body(Body::from("{}"))
                .unwrap(),
        )
        .await,
    )
    .await;
    let challenge_id = options["data"]["challenge_id"].as_str().unwrap().to_owned();
    assert!(
        options["data"]["options"]["publicKey"]
            .get("extensions")
            .is_none()
    );
    let request = request_options_for(&options["data"]["options"], &credential_id, None);
    let mut assertion = authenticator
        .perform_auth(url(ANDROID_ORIGIN), request.public_key, 60_000)
        .unwrap();
    assertion.response.user_handle = Some(user_handle.clone());
    let token_request = |challenge_id: &str| {
        Request::post("/auth/passkey/token")
            .header("content-type", "application/json")
            .body(Body::from(
                serde_json::json!({
                    "challenge_id": challenge_id,
                    "credential": assertion,
                    "device_name": "Pixel 9"
                })
                .to_string(),
            ))
            .unwrap()
    };
    let minted = send(&f, token_request(&challenge_id)).await;
    assert_eq!(minted.status(), StatusCode::OK);
    let token = json(minted).await["data"]["token"]
        .as_str()
        .unwrap()
        .to_owned();
    assert!(token.starts_with("sna_"));
    let (purpose, name, origin): (String, String, String) =
        sqlx::query_as("SELECT purpose, name, auth_origin FROM api_tokens WHERE token_hash = ?")
            .bind(hex::encode(Sha256::digest(token.as_bytes())))
            .fetch_one(&f.pool)
            .await
            .unwrap();
    assert_eq!((purpose.as_str(), origin.as_str()), ("api", "local"));
    assert_eq!(name, "Android \u{b7} Pixel 9");
    let account = send(&f, bearer("GET", "/account", &token, None, None)).await;
    assert_eq!(account.status(), StatusCode::OK);

    // A web challenge cannot be finished as a token request (and the reverse).
    let (cookie, csrf, web_challenge, _) = login_options(&f).await;
    let crossed = send(&f, token_request(&web_challenge)).await;
    assert_eq!(crossed.status(), StatusCode::BAD_REQUEST);
    let _ = (cookie, csrf);

    // The web origin works for the same credential only on the web channel; the Android origin
    // is derived from the configured fingerprint and an unknown app is refused.
    let options = json(
        send(
            &f,
            Request::post("/auth/passkey/token/options")
                .header("content-type", "application/json")
                .body(Body::from("{}"))
                .unwrap(),
        )
        .await,
    )
    .await;
    let request = request_options_for(&options["data"]["options"], &credential_id, None);
    let mut stranger = authenticator
        .perform_auth(
            url("android:apk-key-hash:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"),
            request.public_key,
            60_000,
        )
        .unwrap();
    stranger.response.user_handle = Some(user_handle);
    let refused = send(
        &f,
        Request::post("/auth/passkey/token")
            .header("content-type", "application/json")
            .body(Body::from(
                serde_json::json!({
                    "challenge_id": options["data"]["challenge_id"],
                    "credential": stranger
                })
                .to_string(),
            ))
            .unwrap(),
    )
    .await;
    assert_eq!(refused.status(), StatusCode::UNAUTHORIZED);
}

#[tokio::test]
async fn a_passkey_confirms_sensitive_actions_in_the_browser() {
    let f = fixture().await;
    let session = password_session(&f).await;
    browser_stepup(&f, &session).await;
    let mut authenticator = SoftPasskey::new(true);
    register_in_browser(&f, &session, &mut authenticator, "Key").await;
    let (credential_id, _) = stored_credential(&f).await;
    // The step-up stamp from the password is old news: age it out.
    sqlx::query("UPDATE api_tokens SET reauth_at = unixepoch() - 3600")
        .execute(&f.pool)
        .await
        .unwrap();
    let delete = send(&f, browser("DELETE", "/account/passkeys/1", &session, None)).await;
    assert_eq!(delete.status(), StatusCode::FORBIDDEN);

    let options = json(
        send(
            &f,
            browser(
                "POST",
                "/account/reauth/passkey/options",
                &session,
                Some(serde_json::json!({})),
            ),
        )
        .await,
    )
    .await;
    let challenge_id = options["data"]["challenge_id"].as_str().unwrap().to_owned();
    let allowed = &options["data"]["options"]["publicKey"]["allowCredentials"];
    assert_eq!(
        allowed.as_array().unwrap().len(),
        1,
        "only the account's own passkeys"
    );
    let assertion = authenticator
        .do_authentication(url(ORIGIN), request_options(&options["data"]["options"]))
        .unwrap();
    let response = send(
        &f,
        browser(
            "POST",
            "/account/reauth/passkey",
            &session,
            Some(serde_json::json!({
                "challenge_id": challenge_id,
                "credential": assertion,
                "action": "manage_passkeys"
            })),
        ),
    )
    .await;
    assert_eq!(response.status(), StatusCode::OK);
    assert_eq!(json(response).await["data"]["reauthenticated"], true);
    assert_eq!(count_audit(&f, "passkey_reauthenticated").await, 1);

    // The same assertion cannot be replayed.
    let replay = send(
        &f,
        browser(
            "POST",
            "/account/reauth/passkey",
            &session,
            Some(serde_json::json!({
                "challenge_id": challenge_id,
                "credential": assertion,
                "action": "manage_passkeys"
            })),
        ),
    )
    .await;
    assert_eq!(replay.status(), StatusCode::BAD_REQUEST);

    let removed = send(&f, browser("DELETE", "/account/passkeys/1", &session, None)).await;
    assert_eq!(removed.status(), StatusCode::NO_CONTENT);
    assert_eq!(count_audit(&f, "passkey_removed").await, 1);
    let left: i64 = sqlx::query_scalar("SELECT count(*) FROM passkeys WHERE credential_id = ?")
        .bind(credential_id)
        .fetch_one(&f.pool)
        .await
        .unwrap();
    assert_eq!(left, 0);
}

#[tokio::test]
async fn a_passkey_confirms_sensitive_actions_for_an_app() {
    let f = fixture().await;
    add_pat(&f, "app-pat").await;
    let session = password_session(&f).await;
    browser_stepup(&f, &session).await;
    let mut authenticator = SoftPasskey::new(true);
    register_in_browser(&f, &session, &mut authenticator, "Key").await;

    let options = json(
        send(
            &f,
            bearer(
                "POST",
                "/account/reauth/passkey/options",
                "app-pat",
                None,
                Some(serde_json::json!({})),
            ),
        )
        .await,
    )
    .await;
    let assertion = authenticator
        .do_authentication(url(ORIGIN), request_options(&options["data"]["options"]))
        .unwrap();
    let response = send(
        &f,
        bearer(
            "POST",
            "/account/reauth/passkey",
            "app-pat",
            None,
            Some(serde_json::json!({
                "challenge_id": options["data"]["challenge_id"],
                "credential": assertion,
                "action": "manage_passkeys"
            })),
        ),
    )
    .await;
    assert_eq!(response.status(), StatusCode::OK);
    let proof = json(response).await["data"]["reauth_token"]
        .as_str()
        .unwrap()
        .to_owned();

    let without = send(
        &f,
        bearer("DELETE", "/account/passkeys/1", "app-pat", None, None),
    )
    .await;
    assert_eq!(without.status(), StatusCode::FORBIDDEN);
    let removed = send(
        &f,
        bearer(
            "DELETE",
            "/account/passkeys/1",
            "app-pat",
            Some(&proof),
            None,
        ),
    )
    .await;
    assert_eq!(removed.status(), StatusCode::NO_CONTENT);
}

#[tokio::test]
async fn a_passkey_proof_is_bound_to_the_action_it_was_issued_for() {
    let f = fixture().await;
    add_pat(&f, "app-pat").await;
    let proof = native_stepup(&f, "app-pat").await;
    // A proof for managing passkeys is not a proof for deleting the account.
    let delete = send(
        &f,
        bearer(
            "POST",
            "/account/delete",
            "app-pat",
            Some(&proof),
            Some(serde_json::json!({"confirmation": "DELETE"})),
        ),
    )
    .await;
    assert_eq!(delete.status(), StatusCode::FORBIDDEN);
}

#[tokio::test]
async fn passkeys_are_private_to_their_owner() {
    let f = fixture().await;
    let session = password_session(&f).await;
    browser_stepup(&f, &session).await;
    let mut authenticator = SoftPasskey::new(true);
    register_in_browser(&f, &session, &mut authenticator, "Mine").await;

    let other_id = db::create_user(
        &f.pool,
        "other@example.test",
        "other-passkey-password".to_owned(),
    )
    .await
    .unwrap();
    sqlx::query(
        "INSERT INTO api_tokens (user_id, name, token_hash, purpose, created_at) \
         VALUES (?, 'other', ?, 'api', unixepoch())",
    )
    .bind(other_id)
    .bind(hex::encode(Sha256::digest(b"other-pat")))
    .execute(&f.pool)
    .await
    .unwrap();
    let reauth = send(
        &f,
        bearer(
            "POST",
            "/account/reauth",
            "other-pat",
            None,
            Some(serde_json::json!({"password": "other-passkey-password", "action": "manage_passkeys"})),
        ),
    )
    .await;
    let proof = json(reauth).await["data"]["reauth_token"]
        .as_str()
        .unwrap()
        .to_owned();

    let listed = json(
        send(
            &f,
            bearer("GET", "/account/passkeys", "other-pat", None, None),
        )
        .await,
    )
    .await;
    assert_eq!(listed["data"].as_array().unwrap().len(), 0);
    let rename = send(
        &f,
        bearer(
            "PATCH",
            "/account/passkeys/1",
            "other-pat",
            None,
            Some(serde_json::json!({"nickname": "stolen"})),
        ),
    )
    .await;
    assert_eq!(rename.status(), StatusCode::NOT_FOUND);
    let delete = send(
        &f,
        bearer(
            "DELETE",
            "/account/passkeys/1",
            "other-pat",
            Some(&proof),
            None,
        ),
    )
    .await;
    assert_eq!(delete.status(), StatusCode::NOT_FOUND);
    let (nickname,): (String,) = sqlx::query_as("SELECT nickname FROM passkeys WHERE id = 1")
        .fetch_one(&f.pool)
        .await
        .unwrap();
    assert_eq!(nickname, "Mine");

    // Anonymous requests are refused, and mutating needs the CSRF token.
    let anonymous = send(
        &f,
        Request::get("/account/passkeys")
            .body(Body::empty())
            .unwrap(),
    )
    .await;
    assert_eq!(anonymous.status(), StatusCode::UNAUTHORIZED);
    let no_csrf = send(
        &f,
        Request::builder()
            .method("PATCH")
            .uri("/account/passkeys/1")
            .header("host", HOST)
            .header("origin", ORIGIN)
            .header("cookie", format!("__Host-stricknani_session={}", session.0))
            .header("content-type", "application/json")
            .body(Body::from(r#"{"nickname":"x"}"#))
            .unwrap(),
    )
    .await;
    assert_eq!(no_csrf.status(), StatusCode::FORBIDDEN);

    // The owner renames without step-up; blank and over-long names are refused.
    let renamed = send(
        &f,
        browser(
            "PATCH",
            "/account/passkeys/1",
            &session,
            Some(serde_json::json!({"nickname": "  Work laptop  "})),
        ),
    )
    .await;
    assert_eq!(renamed.status(), StatusCode::OK);
    assert_eq!(json(renamed).await["data"]["nickname"], "Work laptop");
    for bad in ["   ".to_owned(), "x".repeat(101)] {
        let response = send(
            &f,
            browser(
                "PATCH",
                "/account/passkeys/1",
                &session,
                Some(serde_json::json!({"nickname": bad})),
            ),
        )
        .await;
        assert_eq!(response.status(), StatusCode::UNPROCESSABLE_ENTITY);
    }
}

#[tokio::test]
async fn an_account_holds_at_most_ten_passkeys_and_deleting_it_removes_them() {
    let f = fixture().await;
    let session = password_session(&f).await;
    browser_stepup(&f, &session).await;
    for index in 0..10_u8 {
        sqlx::query(
            "INSERT INTO passkeys (user_id, credential_id, user_handle, passkey, nickname, created_at) \
             VALUES (?, ?, ?, '{}', 'filler', unixepoch())",
        )
        .bind(f.user_id)
        .bind(vec![index; 32])
        .bind(vec![1_u8; 16])
        .execute(&f.pool)
        .await
        .unwrap();
    }
    let full = send(
        &f,
        browser(
            "POST",
            "/account/passkeys/registration/options",
            &session,
            Some(serde_json::json!({})),
        ),
    )
    .await;
    assert_eq!(full.status(), StatusCode::CONFLICT);
    assert_eq!(json(full).await["error"]["code"], "passkey_limit");

    sqlx::query("DELETE FROM users WHERE id = ?")
        .bind(f.user_id)
        .execute(&f.pool)
        .await
        .unwrap();
    let left: i64 = sqlx::query_scalar("SELECT count(*) FROM passkeys")
        .fetch_one(&f.pool)
        .await
        .unwrap();
    assert_eq!(left, 0, "passkeys follow their account");
}

#[tokio::test]
async fn registration_excludes_credentials_the_account_already_has() {
    let f = fixture().await;
    let session = password_session(&f).await;
    browser_stepup(&f, &session).await;
    let mut authenticator = SoftPasskey::new(true);
    let (first, _) = register_in_browser(&f, &session, &mut authenticator, "One").await;
    assert_eq!(first, StatusCode::CREATED);

    // A second registration tells the authenticator what it already holds.
    let options = json(
        send(
            &f,
            browser(
                "POST",
                "/account/passkeys/registration/options",
                &session,
                Some(serde_json::json!({})),
            ),
        )
        .await,
    )
    .await;
    assert_eq!(
        options["data"]["options"]["publicKey"]["excludeCredentials"]
            .as_array()
            .unwrap()
            .len(),
        1,
        "the authenticator is told which credentials the account already has"
    );
}

#[tokio::test]
async fn challenge_requests_are_rate_limited() {
    let f = fixture().await;
    let (cookie, csrf) = anonymous(&f).await;
    let mut limited = false;
    for _ in 0..40 {
        let response = send(
            &f,
            login_post(
                "/auth/passkey/options",
                &cookie,
                &csrf,
                ORIGIN,
                serde_json::json!({}),
            ),
        )
        .await;
        if response.status() == StatusCode::TOO_MANY_REQUESTS {
            limited = true;
            break;
        }
    }
    assert!(limited, "30 challenge requests per window per address");
}

#[tokio::test]
async fn passkeys_are_unavailable_unless_configured() {
    let pool = db::connect("sqlite::memory:").await.unwrap();
    let app = api_v2::router(
        db::AppState::new(pool.clone()).with_secure_cookies(true),
        false,
    );
    db::create_user(&pool, EMAIL, PASSWORD.to_owned())
        .await
        .unwrap();
    let response = app
        .clone()
        .oneshot(Request::get("/session").body(Body::empty()).unwrap())
        .await
        .unwrap();
    assert_eq!(
        json(response).await["data"]["capabilities"]["passkeys"],
        false
    );
    let response = app
        .oneshot(
            Request::post("/auth/passkey/token/options")
                .header("content-type", "application/json")
                .body(Body::from("{}"))
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::NOT_FOUND);
    assert_eq!(
        json(response).await["error"]["code"],
        "passkeys_unavailable"
    );

    let f = fixture().await;
    let response = send(&f, Request::get("/session").body(Body::empty()).unwrap()).await;
    assert_eq!(
        json(response).await["data"]["capabilities"]["passkeys"],
        true
    );
}
