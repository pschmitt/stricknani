use axum::{Router, body::Body, http::Request};
use http_body_util::BodyExt;
use sha2::{Digest, Sha256};
use sqlx::SqlitePool;
use std::{fs, io::Cursor, path::PathBuf};
use stricknani_server::{app_with_state, db, media};
use tempfile::TempDir;
use tower::ServiceExt;

const USER_EMAIL: &str = "account-user@example.test";
const ADMIN_EMAIL: &str = "account-admin@example.test";
const ORIGINAL_PASSWORD: &str = "original-browser-password";
const ADMIN_PASSWORD: &str = "administrator-browser-password";
const USER_TOKEN: &str = "sna_0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
const ADMIN_TOKEN: &str = "sna_fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210";
const EXPIRING_PAT: &str = "sna_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
const RESET_PAT: &str = "sna_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
const RESET_BROWSER_TOKEN: &str =
    "sna_cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc";

struct TestApp {
    router: Router,
    pool: SqlitePool,
    user_id: i64,
    admin_id: i64,
    media_root: Option<TempDir>,
}

async fn test_app() -> TestApp {
    let pool = db::connect("sqlite::memory:")
        .await
        .expect("test database should migrate");
    let user_id = db::create_user(&pool, USER_EMAIL, ORIGINAL_PASSWORD.to_owned())
        .await
        .expect("regular user should be created");
    let admin_id = db::create_user(&pool, ADMIN_EMAIL, ADMIN_PASSWORD.to_owned())
        .await
        .expect("admin user should be created");
    sqlx::query("UPDATE users SET is_admin = 1 WHERE id = ?")
        .bind(admin_id)
        .execute(&pool)
        .await
        .expect("admin role should be set");
    add_session(&pool, user_id, USER_TOKEN).await;
    add_session(&pool, admin_id, ADMIN_TOKEN).await;
    let static_root = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../stricknani/static");
    let router = app_with_state(
        static_root,
        db::AppState::new(pool.clone()).with_secure_cookies(true),
    );
    TestApp {
        router,
        pool,
        user_id,
        admin_id,
        media_root: None,
    }
}

async fn test_app_with_media() -> TestApp {
    let mut app = test_app().await;
    let root = tempfile::tempdir().expect("media root should be created");
    let static_root = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../stricknani/static");
    app.router = app_with_state(
        static_root,
        db::AppState::new(app.pool.clone())
            .with_secure_cookies(true)
            .with_media_root(root.path().to_path_buf()),
    );
    app.media_root = Some(root);
    app
}

async fn add_session(pool: &SqlitePool, user_id: i64, token: &str) {
    sqlx::query(
        "INSERT INTO api_tokens (user_id, name, token_hash, created_at, expires_at, purpose) \
         VALUES (?, 'Browser session', ?, unixepoch(), unixepoch() + 86400, 'browser')",
    )
    .bind(user_id)
    .bind(hex::encode(Sha256::digest(token.as_bytes())))
    .execute(pool)
    .await
    .expect("session token should be stored");
}

async fn add_expiring_pat(pool: &SqlitePool, user_id: i64, token: &str, name: &str) -> i64 {
    sqlx::query_scalar(
        "INSERT INTO api_tokens (user_id, name, token_hash, created_at, expires_at, purpose) \
         VALUES (?, ?, ?, unixepoch(), unixepoch() + 86400, 'api') RETURNING id",
    )
    .bind(user_id)
    .bind(name)
    .bind(hex::encode(Sha256::digest(token.as_bytes())))
    .fetch_one(pool)
    .await
    .expect("expiring API token should be inserted")
}

fn csrf(token: &str) -> String {
    let mut hash = Sha256::new();
    hash.update(b"stricknani browser csrf v1\0");
    hash.update(token.as_bytes());
    hex::encode(hash.finalize())
}

fn request(path: &str, token: &str, fields: &[(&str, &str)]) -> Request<Body> {
    let mut serializer = url::form_urlencoded::Serializer::new(String::new());
    for (key, value) in fields {
        serializer.append_pair(key, value);
    }
    let body = serializer.finish();
    Request::post(path)
        .header("cookie", format!("__Host-stricknani_session={token}"))
        .header("content-type", "application/x-www-form-urlencoded")
        .body(Body::from(body))
        .expect("request should build")
}

fn request_with_language(
    path: &str,
    token: &str,
    fields: &[(&str, &str)],
    language: &str,
) -> Request<Body> {
    let mut request = request(path, token, fields);
    request
        .headers_mut()
        .insert("accept-language", language.parse().unwrap());
    request
}

fn avatar_request(token: &str, csrf: &str, image: &[u8]) -> Request<Body> {
    let boundary = "account-avatar-boundary";
    let mut body = Vec::new();
    body.extend_from_slice(
        format!("--{boundary}\r\nContent-Disposition: form-data; name=\"csrf\"\r\n\r\n{csrf}\r\n")
            .as_bytes(),
    );
    body.extend_from_slice(
        format!("--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"profile.png\"\r\nContent-Type: image/png\r\n\r\n").as_bytes(),
    );
    body.extend_from_slice(image);
    body.extend_from_slice(format!("\r\n--{boundary}--\r\n").as_bytes());
    Request::post("/account/avatar")
        .header("cookie", format!("__Host-stricknani_session={token}"))
        .header(
            "content-type",
            format!("multipart/form-data; boundary={boundary}"),
        )
        .body(Body::from(body))
        .expect("avatar multipart request should build")
}

fn png_image() -> Vec<u8> {
    let image = image::DynamicImage::new_rgba8(4, 4);
    let mut output = Cursor::new(Vec::new());
    image
        .write_to(&mut output, image::ImageFormat::Png)
        .expect("test image should encode");
    output.into_inner()
}

async fn html(response: axum::response::Response) -> String {
    String::from_utf8(
        response
            .into_body()
            .collect()
            .await
            .expect("response body should collect")
            .to_bytes()
            .to_vec(),
    )
    .expect("HTML should be UTF-8")
}

#[tokio::test]
async fn account_page_is_authenticated_and_no_store() {
    let app = test_app().await;
    let response = app
        .router
        .clone()
        .oneshot(
            Request::get("/account")
                .header("accept-language", "en")
                .header("cookie", format!("__Host-stricknani_session={USER_TOKEN}"))
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();

    assert_eq!(response.status(), axum::http::StatusCode::OK);
    assert_cache_directive(&response, "no-store");
    let html = html(response).await;
    assert!(html.contains("API tokens"));
    assert!(html.contains("Change password"));
    let denied = app
        .router
        .oneshot(
            Request::get("/admin/users")
                .header("cookie", format!("__Host-stricknani_session={USER_TOKEN}"))
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(denied.status(), axum::http::StatusCode::FORBIDDEN);
}

#[tokio::test]
async fn csrf_protects_token_creation() {
    let app = test_app().await;
    let response = app
        .router
        .oneshot(request(
            "/account/api-tokens",
            USER_TOKEN,
            &[("csrf", "invalid"), ("name", "test")],
        ))
        .await
        .unwrap();

    assert_eq!(response.status(), axum::http::StatusCode::FORBIDDEN);
    let count: i64 =
        sqlx::query_scalar("SELECT count(*) FROM api_tokens WHERE user_id = ? AND purpose = 'api'")
            .bind(app.user_id)
            .fetch_one(&app.pool)
            .await
            .unwrap();
    assert_eq!(count, 0);
}

#[tokio::test]
async fn created_token_is_shown_once_and_can_be_revoked() {
    let app = test_app().await;
    let response = app
        .router
        .clone()
        .oneshot(request(
            "/account/api-tokens",
            USER_TOKEN,
            &[("csrf", &csrf(USER_TOKEN)), ("name", "work laptop")],
        ))
        .await
        .unwrap();
    assert_eq!(response.status(), axum::http::StatusCode::OK);
    assert_cache_directive(&response, "no-store");
    let page = html(response).await;
    let token = page
        .split("<code>")
        .nth(1)
        .and_then(|text| text.split("</code>").next())
        .expect("new raw token should be shown in the response")
        .to_owned();
    assert!(token.starts_with("sna_"));
    let stored_hash: String = sqlx::query_scalar(
        "SELECT token_hash FROM api_tokens WHERE user_id = ? AND purpose = 'api' ORDER BY id DESC LIMIT 1",
    )
    .bind(app.user_id)
    .fetch_one(&app.pool)
    .await
    .unwrap();
    assert_eq!(stored_hash, hex::encode(Sha256::digest(token.as_bytes())));

    let again = app
        .router
        .clone()
        .oneshot(
            Request::get("/account")
                .header("cookie", format!("__Host-stricknani_session={USER_TOKEN}"))
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();
    assert!(!html(again).await.contains(&format!("<code>{token}</code>")));

    let id: i64 = sqlx::query_scalar("SELECT id FROM api_tokens WHERE token_hash = ?")
        .bind(stored_hash)
        .fetch_one(&app.pool)
        .await
        .unwrap();
    let revoked = app
        .router
        .oneshot(request(
            &format!("/account/api-tokens/{id}/revoke"),
            USER_TOKEN,
            &[("csrf", &csrf(USER_TOKEN))],
        ))
        .await
        .unwrap();
    assert_eq!(revoked.status(), axum::http::StatusCode::SEE_OTHER);
    let revoked_at: Option<i64> =
        sqlx::query_scalar("SELECT revoked_at FROM api_tokens WHERE id = ?")
            .bind(id)
            .fetch_one(&app.pool)
            .await
            .unwrap();
    assert!(revoked_at.is_some());
}

fn assert_cache_directive(response: &axum::http::Response<Body>, expected: &str) {
    let directives = response.headers()["cache-control"]
        .to_str()
        .expect("cache-control should be valid ASCII");
    assert!(
        directives
            .split(',')
            .any(|directive| { directive.trim().eq_ignore_ascii_case(expected) })
    );
}

#[tokio::test]
async fn user_cannot_revoke_another_users_token() {
    let app = test_app().await;
    let other_id = db::create_user(
        &app.pool,
        "other@example.test",
        "another-test-password".to_owned(),
    )
    .await
    .unwrap();
    let token_hash = hex::encode(Sha256::digest(
        b"sna_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
    ));
    sqlx::query("INSERT INTO api_tokens (user_id,name,token_hash,created_at) VALUES (?, 'other', ?, unixepoch())")
        .bind(other_id)
        .bind(&token_hash)
        .execute(&app.pool)
        .await
        .unwrap();
    let id: i64 = sqlx::query_scalar("SELECT id FROM api_tokens WHERE token_hash = ?")
        .bind(token_hash)
        .fetch_one(&app.pool)
        .await
        .unwrap();
    let response = app
        .router
        .oneshot(request(
            &format!("/account/api-tokens/{id}/revoke"),
            USER_TOKEN,
            &[("csrf", &csrf(USER_TOKEN))],
        ))
        .await
        .unwrap();
    assert_eq!(response.status(), axum::http::StatusCode::SEE_OTHER);
    let revoked_at: Option<i64> =
        sqlx::query_scalar("SELECT revoked_at FROM api_tokens WHERE id = ?")
            .bind(id)
            .fetch_one(&app.pool)
            .await
            .unwrap();
    assert_eq!(revoked_at, None);
}

#[tokio::test]
async fn expiring_api_tokens_are_listed_and_can_be_revoked() {
    let app = test_app().await;
    let id = add_expiring_pat(
        &app.pool,
        app.user_id,
        EXPIRING_PAT,
        "Imported expiring token",
    )
    .await;
    let cookie_attempt = app
        .router
        .clone()
        .oneshot(
            Request::get("/account")
                .header(
                    "cookie",
                    format!("__Host-stricknani_session={EXPIRING_PAT}"),
                )
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(cookie_attempt.status(), axum::http::StatusCode::SEE_OTHER);
    let api_attempt = app
        .router
        .clone()
        .oneshot(
            Request::get("/api/v1/projects")
                .header("authorization", format!("Bearer {EXPIRING_PAT}"))
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(api_attempt.status(), axum::http::StatusCode::OK);
    let page = app
        .router
        .clone()
        .oneshot(
            Request::get("/account")
                .header("cookie", format!("__Host-stricknani_session={USER_TOKEN}"))
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();
    assert!(html(page).await.contains("Imported expiring token"));

    let revoked = app
        .router
        .oneshot(request(
            &format!("/account/api-tokens/{id}/revoke"),
            USER_TOKEN,
            &[("csrf", &csrf(USER_TOKEN))],
        ))
        .await
        .unwrap();
    assert_eq!(revoked.status(), axum::http::StatusCode::SEE_OTHER);
    let revoked_at: Option<i64> =
        sqlx::query_scalar("SELECT revoked_at FROM api_tokens WHERE id = ?")
            .bind(id)
            .fetch_one(&app.pool)
            .await
            .unwrap();
    assert!(revoked_at.is_some());
}

#[tokio::test]
async fn password_change_checks_current_password_and_revokes_sessions() {
    let app = test_app().await;
    let api_pat = add_expiring_pat(
        &app.pool,
        app.user_id,
        EXPIRING_PAT,
        "Expiring imported API token",
    )
    .await;
    let bad = app
        .router
        .clone()
        .oneshot(request(
            "/account/password",
            USER_TOKEN,
            &[
                ("csrf", &csrf(USER_TOKEN)),
                ("current_password", "incorrect-current-password"),
                ("new_password", "replacement-browser-password"),
            ],
        ))
        .await
        .unwrap();
    assert_eq!(bad.status(), axum::http::StatusCode::UNPROCESSABLE_ENTITY);

    let changed = app
        .router
        .clone()
        .oneshot(request(
            "/account/password",
            USER_TOKEN,
            &[
                ("csrf", &csrf(USER_TOKEN)),
                ("current_password", ORIGINAL_PASSWORD),
                ("new_password", "replacement-browser-password"),
            ],
        ))
        .await
        .unwrap();
    assert_eq!(changed.status(), axum::http::StatusCode::SEE_OTHER);
    let user_session: Option<i64> = sqlx::query_scalar(
        "SELECT revoked_at FROM api_tokens WHERE user_id = ? AND name = 'Browser session'",
    )
    .bind(app.user_id)
    .fetch_one(&app.pool)
    .await
    .unwrap();
    assert!(user_session.is_some());
    let api_pat_revoked: Option<i64> =
        sqlx::query_scalar("SELECT revoked_at FROM api_tokens WHERE id = ?")
            .bind(api_pat)
            .fetch_one(&app.pool)
            .await
            .unwrap();
    assert_eq!(api_pat_revoked, None);

    let new_login = app
        .router
        .oneshot(
            Request::post("/api/v1/auth/token")
                .header("content-type", "application/json")
                .body(Body::from(
                    r#"{"email":"account-user@example.test","password":"replacement-browser-password"}"#,
                ))
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(new_login.status(), axum::http::StatusCode::OK);
}

#[tokio::test]
async fn admin_can_create_disable_and_reset_accounts() {
    let app = test_app().await;
    let created = app
        .router
        .clone()
        .oneshot(request(
            "/admin/users",
            ADMIN_TOKEN,
            &[
                ("csrf", &csrf(ADMIN_TOKEN)),
                ("email", "created@example.test"),
                ("password", "created-account-password"),
                ("is_admin", "1"),
            ],
        ))
        .await
        .unwrap();
    assert_eq!(created.status(), axum::http::StatusCode::SEE_OTHER);
    let (user_id, is_admin): (i64, i64) =
        sqlx::query_as("SELECT id, is_admin FROM users WHERE email = ?")
            .bind("created@example.test")
            .fetch_one(&app.pool)
            .await
            .unwrap();
    assert_eq!(is_admin, 1);

    let disabled = app
        .router
        .clone()
        .oneshot(request(
            &format!("/admin/users/{user_id}/active"),
            ADMIN_TOKEN,
            &[("csrf", &csrf(ADMIN_TOKEN)), ("active", "0")],
        ))
        .await
        .unwrap();
    assert_eq!(disabled.status(), axum::http::StatusCode::SEE_OTHER);
    let active: i64 = sqlx::query_scalar("SELECT active FROM users WHERE id = ?")
        .bind(user_id)
        .fetch_one(&app.pool)
        .await
        .unwrap();
    assert_eq!(active, 0);

    let reenabled = app
        .router
        .clone()
        .oneshot(request(
            &format!("/admin/users/{user_id}/active"),
            ADMIN_TOKEN,
            &[("csrf", &csrf(ADMIN_TOKEN)), ("active", "1")],
        ))
        .await
        .unwrap();
    assert_eq!(reenabled.status(), axum::http::StatusCode::SEE_OTHER);
    add_session(&app.pool, user_id, RESET_BROWSER_TOKEN).await;

    let expiring_pat =
        add_expiring_pat(&app.pool, user_id, RESET_PAT, "Imported expiring token").await;

    let reset = app
        .router
        .oneshot(request(
            &format!("/admin/users/{user_id}/reset-password"),
            ADMIN_TOKEN,
            &[
                ("csrf", &csrf(ADMIN_TOKEN)),
                ("new_password", "reset-account-password"),
            ],
        ))
        .await
        .unwrap();
    assert_eq!(reset.status(), axum::http::StatusCode::SEE_OTHER);
    let active: i64 = sqlx::query_scalar("SELECT active FROM users WHERE id = ?")
        .bind(user_id)
        .fetch_one(&app.pool)
        .await
        .unwrap();
    assert_eq!(active, 1);
    let browser_session_revoked: Option<i64> =
        sqlx::query_scalar("SELECT revoked_at FROM api_tokens WHERE token_hash = ?")
            .bind(hex::encode(Sha256::digest(RESET_BROWSER_TOKEN.as_bytes())))
            .fetch_one(&app.pool)
            .await
            .unwrap();
    assert!(browser_session_revoked.is_some());
    let pat_revoked: Option<i64> =
        sqlx::query_scalar("SELECT revoked_at FROM api_tokens WHERE id = ?")
            .bind(expiring_pat)
            .fetch_one(&app.pool)
            .await
            .unwrap();
    assert_eq!(pat_revoked, None);
}

#[tokio::test]
async fn common_admin_password_is_rejected_without_creating_an_account() {
    let app = test_app().await;
    let before_password_hash: String =
        sqlx::query_scalar("SELECT password_hash FROM users WHERE id = ?")
            .bind(app.admin_id)
            .fetch_one(&app.pool)
            .await
            .unwrap();
    let before_sessions: i64 = sqlx::query_scalar(
        "SELECT count(*) FROM api_tokens WHERE user_id = ? AND purpose = 'browser' AND revoked_at IS NULL",
    )
    .bind(app.admin_id)
    .fetch_one(&app.pool)
    .await
    .unwrap();

    let response = app
        .router
        .oneshot(request_with_language(
            "/admin/users",
            ADMIN_TOKEN,
            &[
                ("csrf", &csrf(ADMIN_TOKEN)),
                ("email", "common@example.test"),
                ("password", "password123"),
                ("is_admin", "1"),
            ],
            "de",
        ))
        .await
        .unwrap();
    assert_eq!(
        response.status(),
        axum::http::StatusCode::UNPROCESSABLE_ENTITY
    );
    let page = html(response).await;
    assert!(page.contains("Wähle ein weniger häufig verwendetes Passwort."));
    let created: i64 = sqlx::query_scalar("SELECT count(*) FROM users WHERE email = ?")
        .bind("common@example.test")
        .fetch_one(&app.pool)
        .await
        .unwrap();
    assert_eq!(created, 0);
    let after_password_hash: String =
        sqlx::query_scalar("SELECT password_hash FROM users WHERE id = ?")
            .bind(app.admin_id)
            .fetch_one(&app.pool)
            .await
            .unwrap();
    assert_eq!(after_password_hash, before_password_hash);
    let after_sessions: i64 = sqlx::query_scalar(
        "SELECT count(*) FROM api_tokens WHERE user_id = ? AND purpose = 'browser' AND revoked_at IS NULL",
    )
    .bind(app.admin_id)
    .fetch_one(&app.pool)
    .await
    .unwrap();
    assert_eq!(after_sessions, before_sessions);
}

#[tokio::test]
async fn admin_routes_reject_non_admin_mutations() {
    let app = test_app().await;
    let response = app
        .router
        .oneshot(request(
            "/admin/users",
            USER_TOKEN,
            &[
                ("csrf", &csrf(USER_TOKEN)),
                ("email", "attacker@example.test"),
                ("password", "attacker-account-password"),
            ],
        ))
        .await
        .unwrap();
    assert_eq!(response.status(), axum::http::StatusCode::FORBIDDEN);
    let exists: i64 = sqlx::query_scalar("SELECT count(*) FROM users WHERE email = ?")
        .bind("attacker@example.test")
        .fetch_one(&app.pool)
        .await
        .unwrap();
    assert_eq!(exists, 0);
    let admin_check: i64 = sqlx::query_scalar("SELECT is_admin FROM users WHERE id = ?")
        .bind(app.admin_id)
        .fetch_one(&app.pool)
        .await
        .unwrap();
    assert_eq!(admin_check, 1);
}

#[tokio::test]
async fn concurrent_admin_disables_cannot_remove_all_active_admins() {
    let app = test_app().await;
    sqlx::query("UPDATE users SET is_admin = 1 WHERE id = ?")
        .bind(app.user_id)
        .execute(&app.pool)
        .await
        .unwrap();

    let first = app.router.clone().oneshot(request(
        &format!("/admin/users/{}/active", app.admin_id),
        USER_TOKEN,
        &[("csrf", &csrf(USER_TOKEN)), ("active", "0")],
    ));
    let second = app.router.clone().oneshot(request(
        &format!("/admin/users/{}/active", app.user_id),
        ADMIN_TOKEN,
        &[("csrf", &csrf(ADMIN_TOKEN)), ("active", "0")],
    ));
    let (first, second) = tokio::join!(first, second);
    let first = first.unwrap();
    let second = second.unwrap();
    let statuses = [first.status(), second.status()];
    assert_eq!(
        statuses
            .iter()
            .filter(|status| **status == axum::http::StatusCode::SEE_OTHER)
            .count(),
        1
    );
    assert!(statuses.iter().any(|status| {
        *status == axum::http::StatusCode::FORBIDDEN || *status == axum::http::StatusCode::CONFLICT
    }));
    let active_admins: i64 =
        sqlx::query_scalar("SELECT count(*) FROM users WHERE active = 1 AND is_admin = 1")
            .fetch_one(&app.pool)
            .await
            .unwrap();
    assert_eq!(active_admins, 1);
}

#[tokio::test]
async fn avatar_upload_replacement_and_delete_use_registry_and_gc() {
    let app = test_app_with_media().await;
    let root = app.media_root.as_ref().unwrap().path();
    let user_dir = root.join("users").join(app.user_id.to_string());
    let thumbnail_dir = root
        .join("thumbnails")
        .join("users")
        .join(app.user_id.to_string());
    fs::create_dir_all(&user_dir).unwrap();
    fs::create_dir_all(&thumbnail_dir).unwrap();
    let imported_filename = "legacy-profile.png";
    let imported_path = user_dir.join(imported_filename);
    let imported_thumbnail = thumbnail_dir.join("thumb_legacy-profile.jpg");
    fs::write(&imported_path, b"imported avatar bytes").unwrap();
    fs::write(&imported_thumbnail, b"imported avatar thumbnail").unwrap();
    sqlx::query(
        "INSERT INTO user_avatars \
         (user_id, filename, original_filename, content_type, width, height, created_at) \
         VALUES (?, ?, ?, 'image/png', 100, 100, '2026-01-01T00:00:00.000000Z')",
    )
    .bind(app.user_id)
    .bind(imported_filename)
    .bind(imported_filename)
    .execute(&app.pool)
    .await
    .unwrap();
    sqlx::query("UPDATE users SET profile_image = ? WHERE id = ?")
        .bind(imported_filename)
        .bind(app.user_id)
        .execute(&app.pool)
        .await
        .unwrap();

    let page = app
        .router
        .clone()
        .oneshot(
            Request::get("/account")
                .header("cookie", format!("__Host-stricknani_session={USER_TOKEN}"))
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();
    assert!(html(page).await.contains(&format!(
        "/media/thumbnails/users/{}/thumb_legacy-profile.jpg",
        app.user_id
    )));

    let uploaded = app
        .router
        .clone()
        .oneshot(avatar_request(USER_TOKEN, &csrf(USER_TOKEN), &png_image()))
        .await
        .unwrap();
    assert_eq!(uploaded.status(), axum::http::StatusCode::SEE_OTHER);
    let new_filename: String =
        sqlx::query_scalar("SELECT filename FROM user_avatars WHERE user_id = ?")
            .bind(app.user_id)
            .fetch_one(&app.pool)
            .await
            .unwrap();
    assert_ne!(new_filename, imported_filename);
    assert!(user_dir.join(&new_filename).is_file());
    let stem = new_filename.split('.').next().unwrap();
    assert!(thumbnail_dir.join(format!("thumb_{stem}.jpg")).is_file());
    assert!(
        imported_path.exists(),
        "replacement should queue old bytes for GC"
    );
    let old_queued: i64 =
        sqlx::query_scalar("SELECT count(*) FROM avatar_gc WHERE user_id = ? AND filename = ?")
            .bind(app.user_id)
            .bind(imported_filename)
            .fetch_one(&app.pool)
            .await
            .unwrap();
    assert_eq!(old_queued, 1);
    let profile_image: Option<String> =
        sqlx::query_scalar("SELECT profile_image FROM users WHERE id = ?")
            .bind(app.user_id)
            .fetch_one(&app.pool)
            .await
            .unwrap();
    assert_eq!(profile_image.as_deref(), Some(new_filename.as_str()));

    let state = db::AppState::new(app.pool.clone()).with_media_root(root.to_path_buf());
    media::cleanup_pending(&state).await.unwrap();
    assert!(!imported_path.exists());
    let old_queued: i64 =
        sqlx::query_scalar("SELECT count(*) FROM avatar_gc WHERE user_id = ? AND filename = ?")
            .bind(app.user_id)
            .bind(imported_filename)
            .fetch_one(&app.pool)
            .await
            .unwrap();
    assert_eq!(old_queued, 0);

    let deleted = app
        .router
        .clone()
        .oneshot(request(
            "/account/avatar/delete",
            USER_TOKEN,
            &[("csrf", &csrf(USER_TOKEN))],
        ))
        .await
        .unwrap();
    assert_eq!(deleted.status(), axum::http::StatusCode::SEE_OTHER);
    let profile_image: Option<String> =
        sqlx::query_scalar("SELECT profile_image FROM users WHERE id = ?")
            .bind(app.user_id)
            .fetch_one(&app.pool)
            .await
            .unwrap();
    assert_eq!(profile_image, None);
    let new_queued: i64 =
        sqlx::query_scalar("SELECT count(*) FROM avatar_gc WHERE user_id = ? AND filename = ?")
            .bind(app.user_id)
            .bind(&new_filename)
            .fetch_one(&app.pool)
            .await
            .unwrap();
    assert_eq!(new_queued, 1);
    assert!(user_dir.join(&new_filename).exists());
    let new_thumbnail = thumbnail_dir.join(format!("thumb_{stem}.jpg"));
    assert!(new_thumbnail.exists());
    media::cleanup_pending(&state).await.unwrap();
    assert!(!user_dir.join(&new_filename).exists());
    assert!(!new_thumbnail.exists());
}

#[tokio::test]
async fn avatar_csrf_is_checked_before_file_contents_are_read() {
    let app = test_app_with_media().await;
    let response = app
        .router
        .oneshot(avatar_request(USER_TOKEN, "invalid-csrf", b"not an image"))
        .await
        .unwrap();
    assert_eq!(response.status(), axum::http::StatusCode::FORBIDDEN);
}

#[tokio::test]
async fn avatar_files_are_removed_when_registry_write_fails() {
    let app = test_app_with_media().await;
    sqlx::query(
        "CREATE TRIGGER reject_avatar_insert BEFORE INSERT ON user_avatars \
         BEGIN SELECT RAISE(ABORT, 'test failure'); END",
    )
    .execute(&app.pool)
    .await
    .unwrap();
    let response = app
        .router
        .oneshot(avatar_request(USER_TOKEN, &csrf(USER_TOKEN), &png_image()))
        .await
        .unwrap();
    assert_eq!(
        response.status(),
        axum::http::StatusCode::INTERNAL_SERVER_ERROR
    );
    let root = app.media_root.as_ref().unwrap().path();
    let user_dir = root.join("users").join(app.user_id.to_string());
    let count = fs::read_dir(user_dir)
        .map(|entries| entries.count())
        .unwrap_or_default();
    assert_eq!(count, 0);
    let thumbnail_dir = root
        .join("thumbnails")
        .join("users")
        .join(app.user_id.to_string());
    let thumbnail_count = fs::read_dir(thumbnail_dir)
        .map(|entries| entries.count())
        .unwrap_or_default();
    assert_eq!(thumbnail_count, 0);
}
