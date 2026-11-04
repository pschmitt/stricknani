//! Bearer PAT access to the account self-service routes: read, revoke,
//! avatar, password change and step-up gated token creation.

use axum::{
    body::Body,
    http::{Request, StatusCode},
};
use http_body_util::BodyExt;
use sha2::{Digest, Sha256};
use sqlx::SqlitePool;
use std::io::Cursor;
use stricknani_server::{api_v2, db};
use tower::ServiceExt;

const PASSWORD: &str = "bearer-account-password-long";

struct Fixture {
    app: axum::Router,
    pool: SqlitePool,
    user_id: i64,
    _media: tempfile::TempDir,
}

async fn fixture() -> Fixture {
    let pool = db::connect("sqlite::memory:").await.unwrap();
    let media = tempfile::tempdir().unwrap();
    let state = db::AppState::new(pool.clone())
        .with_secure_cookies(true)
        .with_media_root(media.path().to_path_buf());
    let user_id = db::create_user(&pool, "bearer@example.test", PASSWORD.to_owned())
        .await
        .unwrap();
    Fixture {
        app: api_v2::router(state, false),
        pool,
        user_id,
        _media: media,
    }
}

/// Insert an API PAT and return its id. `expires_in` is seconds from now.
async fn add_pat(f: &Fixture, secret: &str, expires_in: Option<i64>) -> i64 {
    sqlx::query_scalar(
        "INSERT INTO api_tokens (user_id, name, token_hash, purpose, created_at, expires_at) \
         VALUES (?, 'fixture', ?, 'api', unixepoch(), unixepoch() + ?) RETURNING id",
    )
    .bind(f.user_id)
    .bind(hex::encode(Sha256::digest(secret.as_bytes())))
    .bind(expires_in)
    .fetch_one(&f.pool)
    .await
    .unwrap()
}

async fn json(response: axum::response::Response) -> serde_json::Value {
    let bytes = response.into_body().collect().await.unwrap().to_bytes();
    serde_json::from_slice(&bytes).unwrap_or(serde_json::Value::Null)
}

fn bearer(
    method: &str,
    path: &str,
    token: &str,
    extra: &[(&str, &str)],
    body: Option<serde_json::Value>,
) -> Request<Body> {
    let mut builder = Request::builder()
        .method(method)
        .uri(path)
        .header("authorization", format!("Bearer {token}"));
    for (name, value) in extra {
        builder = builder.header(*name, *value);
    }
    if body.is_some() {
        builder = builder.header("content-type", "application/json");
    }
    builder
        .body(body.map_or_else(Body::empty, |b| Body::from(b.to_string())))
        .unwrap()
}

async fn send(f: &Fixture, request: Request<Body>) -> axum::response::Response {
    f.app.clone().oneshot(request).await.unwrap()
}

fn png_bytes() -> Vec<u8> {
    let mut output = Cursor::new(Vec::new());
    image::DynamicImage::new_rgba8(2, 2)
        .write_to(&mut output, image::ImageFormat::Png)
        .unwrap();
    output.into_inner()
}

fn multipart(token: &str, bytes: &[u8], content_type: &str) -> Request<Body> {
    let boundary = "bearer-avatar-boundary";
    let mut body = format!(
        "--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"p.png\"\r\nContent-Type: {content_type}\r\n\r\n"
    )
    .into_bytes();
    body.extend_from_slice(bytes);
    body.extend_from_slice(format!("\r\n--{boundary}--\r\n").as_bytes());
    Request::post("/account/avatar")
        .header("authorization", format!("Bearer {token}"))
        .header(
            "content-type",
            format!("multipart/form-data; boundary={boundary}"),
        )
        .body(Body::from(body))
        .unwrap()
}

#[tokio::test]
async fn bearer_reads_account_and_tokens_and_meta_exposes_only_the_current_token_id() {
    let f = fixture().await;
    let current = add_pat(&f, "read-pat", Some(3600)).await;
    let other = add_pat(&f, "other-pat", None).await;

    let account = send(&f, bearer("GET", "/account", "read-pat", &[], None)).await;
    assert_eq!(account.status(), StatusCode::OK);
    assert_eq!(json(account).await["data"]["email"], "bearer@example.test");

    let listed = send(
        &f,
        bearer("GET", "/account/api-tokens", "read-pat", &[], None),
    )
    .await;
    assert_eq!(listed.status(), StatusCode::OK);
    let listed = json(listed).await;
    let ids: Vec<i64> = listed["data"]
        .as_array()
        .unwrap()
        .iter()
        .map(|t| t["id"].as_i64().unwrap())
        .collect();
    assert!(ids.contains(&current) && ids.contains(&other));
    let text = listed.to_string();
    assert!(!text.contains("read-pat") && !text.contains("token_hash"));

    let meta = send(&f, bearer("GET", "/meta", "read-pat", &[], None)).await;
    let meta = json(meta).await;
    assert_eq!(meta["data"]["current_token_id"], current);
    let text = meta.to_string();
    assert!(!text.contains("read-pat"));
    assert!(!text.contains(&hex::encode(Sha256::digest(b"read-pat"))));

    let anonymous = send(&f, Request::get("/meta").body(Body::empty()).unwrap()).await;
    assert!(
        json(anonymous).await["data"]
            .get("current_token_id")
            .is_none()
    );
}

#[tokio::test]
async fn invalid_bearer_never_falls_back_to_a_cookie_session() {
    let f = fixture().await;
    let response = send(
        &f,
        Request::get("/account")
            .header("authorization", "Bearer unknown-pat")
            .header("cookie", "__Host-stricknani_session=anything")
            .body(Body::empty())
            .unwrap(),
    )
    .await;
    assert_eq!(response.status(), StatusCode::UNAUTHORIZED);
    // A browser-purpose token is not accepted as a bearer PAT either.
    sqlx::query(
        "INSERT INTO api_tokens (user_id, name, token_hash, purpose, created_at, expires_at) \
         VALUES (?, 'b', ?, 'browser', unixepoch(), unixepoch() + 3600)",
    )
    .bind(f.user_id)
    .bind(hex::encode(Sha256::digest(b"browser-secret")))
    .execute(&f.pool)
    .await
    .unwrap();
    let response = send(&f, bearer("GET", "/account", "browser-secret", &[], None)).await;
    assert_eq!(response.status(), StatusCode::UNAUTHORIZED);
}

#[tokio::test]
async fn bearer_create_requires_step_up() {
    let f = fixture().await;
    add_pat(&f, "creator", None).await;
    let before: i64 = sqlx::query_scalar("SELECT count(*) FROM api_tokens")
        .fetch_one(&f.pool)
        .await
        .unwrap();

    let missing = send(
        &f,
        bearer(
            "POST",
            "/account/api-tokens",
            "creator",
            &[],
            Some(serde_json::json!({"name":"new"})),
        ),
    )
    .await;
    assert_eq!(missing.status(), StatusCode::FORBIDDEN);
    assert_eq!(json(missing).await["error"]["code"], "reauth_required");

    let wrong = send(
        &f,
        bearer(
            "POST",
            "/account/api-tokens",
            "creator",
            &[],
            Some(serde_json::json!({"name":"new","current_password":"wrong-password-here"})),
        ),
    )
    .await;
    assert_eq!(wrong.status(), StatusCode::UNAUTHORIZED);
    assert_eq!(json(wrong).await["error"]["code"], "invalid_credentials");

    let bad_proof = send(
        &f,
        bearer(
            "POST",
            "/account/api-tokens",
            "creator",
            &[("x-reauth-token", "reauth_not-a-real-proof")],
            Some(serde_json::json!({"name":"new"})),
        ),
    )
    .await;
    assert_eq!(bad_proof.status(), StatusCode::FORBIDDEN);
    assert_eq!(json(bad_proof).await["error"]["code"], "reauth_required");

    let after: i64 = sqlx::query_scalar("SELECT count(*) FROM api_tokens")
        .fetch_one(&f.pool)
        .await
        .unwrap();
    assert_eq!(before, after, "no token may be minted without step-up");
}

#[tokio::test]
async fn bearer_create_with_password_audits_and_returns_usable_token_once() {
    let f = fixture().await;
    add_pat(&f, "creator", None).await;
    let created = send(
        &f,
        bearer(
            "POST",
            "/account/api-tokens",
            "creator",
            &[],
            Some(serde_json::json!({"name":"phone","current_password":PASSWORD})),
        ),
    )
    .await;
    assert_eq!(created.status(), StatusCode::CREATED);
    let created = json(created).await["data"].clone();
    assert_eq!(created["name"], "phone");
    assert!(created["expires_at"].is_null());
    let secret = created["token"].as_str().unwrap().to_owned();

    let works = send(&f, bearer("GET", "/account", &secret, &[], None)).await;
    assert_eq!(works.status(), StatusCode::OK);
    let listed = send(
        &f,
        bearer("GET", "/account/api-tokens", "creator", &[], None),
    )
    .await;
    assert!(!json(listed).await.to_string().contains(&secret));
    let audited: i64 = sqlx::query_scalar(
        "SELECT count(*) FROM auth_audit_events WHERE action = 'api_token_created' \
         AND actor_user_id = ? AND target_user_id = ?",
    )
    .bind(f.user_id)
    .bind(f.user_id)
    .fetch_one(&f.pool)
    .await
    .unwrap();
    assert_eq!(audited, 1);
}

#[tokio::test]
async fn bearer_create_accepts_a_one_use_reauth_proof() {
    let f = fixture().await;
    add_pat(&f, "creator", None).await;
    let reauth = send(
        &f,
        bearer(
            "POST",
            "/account/reauth",
            "creator",
            &[],
            Some(serde_json::json!({"password":PASSWORD,"action":"create_api_token"})),
        ),
    )
    .await;
    assert_eq!(reauth.status(), StatusCode::OK);
    let proof = json(reauth).await["data"]["reauth_token"]
        .as_str()
        .unwrap()
        .to_owned();
    let created = send(
        &f,
        bearer(
            "POST",
            "/account/api-tokens",
            "creator",
            &[("x-reauth-token", &proof)],
            Some(serde_json::json!({"name":"proofed"})),
        ),
    )
    .await;
    assert_eq!(created.status(), StatusCode::CREATED);
    let replay = send(
        &f,
        bearer(
            "POST",
            "/account/api-tokens",
            "creator",
            &[("x-reauth-token", &proof)],
            Some(serde_json::json!({"name":"replayed"})),
        ),
    )
    .await;
    assert_eq!(replay.status(), StatusCode::FORBIDDEN);
    assert_eq!(json(replay).await["error"]["code"], "reauth_required");
}

#[tokio::test]
async fn oidc_only_account_cannot_use_a_password_for_step_up() {
    let f = fixture().await;
    add_pat(&f, "creator", None).await;
    sqlx::query("UPDATE users SET password_enabled = 0 WHERE id = ?")
        .bind(f.user_id)
        .execute(&f.pool)
        .await
        .unwrap();
    let response = send(
        &f,
        bearer(
            "POST",
            "/account/api-tokens",
            "creator",
            &[],
            Some(serde_json::json!({"name":"x","current_password":PASSWORD})),
        ),
    )
    .await;
    assert_eq!(response.status(), StatusCode::UNAUTHORIZED);
    assert_eq!(json(response).await["error"]["code"], "invalid_credentials");
}

#[tokio::test]
async fn bearer_created_tokens_never_outlive_the_calling_token() {
    let f = fixture().await;
    let caller = add_pat(&f, "short-lived", Some(3600)).await;
    let caller_expiry: i64 = sqlx::query_scalar("SELECT expires_at FROM api_tokens WHERE id = ?")
        .bind(caller)
        .fetch_one(&f.pool)
        .await
        .unwrap();
    let create = |body: serde_json::Value| {
        let mut body = body;
        body["current_password"] = PASSWORD.into();
        bearer(
            "POST",
            "/account/api-tokens",
            "short-lived",
            &[],
            Some(body),
        )
    };
    let expiry_of = |value: &serde_json::Value| {
        chrono::DateTime::parse_from_rfc3339(value["data"]["expires_at"].as_str().unwrap())
            .unwrap()
            .timestamp()
    };

    // No requested expiry inherits the caller's.
    let inherited = json(send(&f, create(serde_json::json!({"name":"a"}))).await).await;
    assert_eq!(expiry_of(&inherited), caller_expiry);

    // A later request is clamped.
    let far = (chrono::Utc::now() + chrono::Duration::days(30)).to_rfc3339();
    let clamped =
        json(send(&f, create(serde_json::json!({"name":"b","expires_at":far}))).await).await;
    assert_eq!(expiry_of(&clamped), caller_expiry);

    // An earlier request is kept.
    let soon_at = chrono::Utc::now() + chrono::Duration::minutes(10);
    let soon = json(
        send(
            &f,
            create(serde_json::json!({"name":"c","expires_at":soon_at.to_rfc3339()})),
        )
        .await,
    )
    .await;
    assert_eq!(expiry_of(&soon), soon_at.timestamp());

    // A past expiry is rejected.
    let past = (chrono::Utc::now() - chrono::Duration::hours(1)).to_rfc3339();
    let rejected = send(
        &f,
        create(serde_json::json!({"name":"d","expires_at":past})),
    )
    .await;
    assert_eq!(rejected.status(), StatusCode::UNPROCESSABLE_ENTITY);
    let malformed = send(
        &f,
        create(serde_json::json!({"name":"e","expires_at":"tomorrow"})),
    )
    .await;
    assert_eq!(malformed.status(), StatusCode::UNPROCESSABLE_ENTITY);
}

#[tokio::test]
async fn bearer_password_checks_share_the_attempt_budget() {
    let f = fixture().await;
    add_pat(&f, "creator", None).await;
    let mut limited = false;
    for _ in 0..8 {
        let response = send(
            &f,
            bearer(
                "POST",
                "/account/api-tokens",
                "creator",
                &[],
                Some(serde_json::json!({"current_password":"definitely-wrong-password"})),
            ),
        )
        .await;
        if response.status() == StatusCode::TOO_MANY_REQUESTS {
            assert!(response.headers().contains_key("retry-after"));
            limited = true;
            break;
        }
        assert_eq!(response.status(), StatusCode::UNAUTHORIZED);
    }
    assert!(limited, "repeated wrong passwords must be rate limited");
}

#[tokio::test]
async fn bearer_revoke_reports_current_token_and_signs_the_caller_out() {
    let f = fixture().await;
    let current = add_pat(&f, "me", None).await;
    let other = add_pat(&f, "someone-else", None).await;
    let foreign_user = db::create_user(&f.pool, "foreign@example.test", PASSWORD.to_owned())
        .await
        .unwrap();
    let foreign: i64 = sqlx::query_scalar(
        "INSERT INTO api_tokens (user_id, name, token_hash, purpose, created_at) \
         VALUES (?, 'foreign', 'foreign-hash', 'api', unixepoch()) RETURNING id",
    )
    .bind(foreign_user)
    .fetch_one(&f.pool)
    .await
    .unwrap();

    let not_mine = send(
        &f,
        bearer(
            "DELETE",
            &format!("/account/api-tokens/{foreign}"),
            "me",
            &[],
            None,
        ),
    )
    .await;
    assert_eq!(not_mine.status(), StatusCode::NOT_FOUND);

    let revoked_other = send(
        &f,
        bearer(
            "DELETE",
            &format!("/account/api-tokens/{other}"),
            "me",
            &[],
            None,
        ),
    )
    .await;
    assert_eq!(revoked_other.status(), StatusCode::OK);
    let body = json(revoked_other).await;
    assert_eq!(body["data"]["id"], other);
    assert_eq!(body["data"]["current_token"], false);
    let still = send(&f, bearer("GET", "/account", "me", &[], None)).await;
    assert_eq!(still.status(), StatusCode::OK);

    let own = send(
        &f,
        bearer(
            "DELETE",
            &format!("/account/api-tokens/{current}"),
            "me",
            &[],
            None,
        ),
    )
    .await;
    assert_eq!(own.status(), StatusCode::OK);
    let body = json(own).await;
    assert_eq!(body["data"]["current_token"], true);
    assert_eq!(body["data"]["revoked"], true);

    let after = send(&f, bearer("GET", "/account", "me", &[], None)).await;
    assert_eq!(after.status(), StatusCode::UNAUTHORIZED);
    assert_eq!(json(after).await["error"]["code"], "session_expired");
}

#[tokio::test]
async fn bearer_avatar_upload_and_delete_use_browser_limits() {
    let f = fixture().await;
    add_pat(&f, "avatar-pat", None).await;
    let uploaded = send(&f, multipart("avatar-pat", &png_bytes(), "image/png")).await;
    assert_eq!(uploaded.status(), StatusCode::OK);
    let url = json(uploaded).await["data"]["avatar_url"]
        .as_str()
        .unwrap()
        .to_owned();
    assert!(url.starts_with("/media/thumbnails/users/"));
    let account = json(send(&f, bearer("GET", "/account", "avatar-pat", &[], None)).await).await;
    assert_eq!(account["data"]["avatar_url"], url.as_str());

    let invalid = send(&f, multipart("avatar-pat", b"not an image", "image/png")).await;
    assert_eq!(invalid.status(), StatusCode::BAD_REQUEST);
    assert_eq!(json(invalid).await["error"]["code"], "invalid_image");

    let oversized = vec![0_u8; 26 * 1024 * 1024];
    let too_large = send(&f, multipart("avatar-pat", &oversized, "image/png")).await;
    assert_eq!(too_large.status(), StatusCode::PAYLOAD_TOO_LARGE);

    let deleted = send(
        &f,
        bearer("DELETE", "/account/avatar", "avatar-pat", &[], None),
    )
    .await;
    assert_eq!(deleted.status(), StatusCode::NO_CONTENT);
    let account = json(send(&f, bearer("GET", "/account", "avatar-pat", &[], None)).await).await;
    assert!(account["data"]["avatar_url"].is_null());
}

#[tokio::test]
async fn bearer_password_change_needs_the_current_password_and_revokes_every_credential() {
    let f = fixture().await;
    add_pat(&f, "pw-pat", None).await;
    add_pat(&f, "pw-other-pat", None).await;
    let new_password = "a-brand-new-password-123";

    let missing = send(
        &f,
        bearer(
            "PUT",
            "/account/password",
            "pw-pat",
            &[],
            Some(serde_json::json!({"new_password":new_password})),
        ),
    )
    .await;
    assert_eq!(missing.status(), StatusCode::BAD_REQUEST);
    let wrong = send(
        &f,
        bearer(
            "PUT",
            "/account/password",
            "pw-pat",
            &[],
            Some(serde_json::json!({"current_password":"nope-nope-nope","new_password":new_password})),
        ),
    )
    .await;
    assert_eq!(wrong.status(), StatusCode::UNPROCESSABLE_ENTITY);
    assert_eq!(
        json(wrong).await["error"]["fields"]["current_password"],
        "Current password is incorrect"
    );
    let still = send(&f, bearer("GET", "/account", "pw-pat", &[], None)).await;
    assert_eq!(still.status(), StatusCode::OK);

    let changed = send(
        &f,
        bearer(
            "PUT",
            "/account/password",
            "pw-pat",
            &[],
            Some(serde_json::json!({"current_password":PASSWORD,"new_password":new_password})),
        ),
    )
    .await;
    assert_eq!(changed.status(), StatusCode::NO_CONTENT);
    for token in ["pw-pat", "pw-other-pat"] {
        let response = send(&f, bearer("GET", "/account", token, &[], None)).await;
        assert_eq!(response.status(), StatusCode::UNAUTHORIZED, "{token}");
    }
    let live: i64 = sqlx::query_scalar(
        "SELECT count(*) FROM api_tokens WHERE user_id = ? AND revoked_at IS NULL",
    )
    .bind(f.user_id)
    .fetch_one(&f.pool)
    .await
    .unwrap();
    assert_eq!(live, 0, "a password change mints no replacement token");
}
