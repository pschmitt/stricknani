use axum::{
    Router,
    body::Body,
    http::{HeaderValue, Request, StatusCode},
};
use http_body_util::BodyExt;
use image::{DynamicImage, ImageBuffer, ImageFormat, Rgba};
use sha2::{Digest, Sha256};
use sqlx::SqlitePool;
use std::io::Cursor;
use stricknani_server::{app_with_state, db};
use tower::ServiceExt;

const ADMIN_SESSION: &str = "synthetic-admin-browser-session";
const ADMIN_PAT: &str = "synthetic-admin-purpose-api-pat";
const MEMBER_SESSION: &str = "synthetic-member-browser-session";
const MEMBER_PAT: &str = "synthetic-member-purpose-api-pat";
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
    let admin_id = db::create_user(
        &pool,
        "pat-admin@example.test",
        "admin-password-long".into(),
    )
    .await
    .expect("admin exists");
    sqlx::query("UPDATE users SET is_admin = 1 WHERE id = ?")
        .bind(admin_id)
        .execute(&pool)
        .await
        .unwrap();
    let member_id = db::create_user(
        &pool,
        "pat-member@example.test",
        "member-password-long".into(),
    )
    .await
    .expect("member exists");
    add_token(&pool, admin_id, ADMIN_SESSION, "browser", None, false).await;
    add_token(&pool, admin_id, ADMIN_PAT, "api", None, false).await;
    add_token(&pool, member_id, MEMBER_SESSION, "browser", None, false).await;
    add_token(&pool, member_id, MEMBER_PAT, "api", None, false).await;
    let static_root = tempfile::tempdir().unwrap();
    let state = db::AppState::new(pool.clone())
        .with_secure_cookies(true)
        .with_media_root(static_root.path().to_path_buf());
    Fixture {
        app: app_with_state(static_root.path().to_path_buf(), state),
        pool,
        admin_id,
        member_id,
        _static_root: static_root,
    }
}

async fn add_token(
    pool: &SqlitePool,
    user_id: i64,
    token: &str,
    purpose: &str,
    expires_at: Option<i64>,
    revoked: bool,
) {
    sqlx::query(
        "INSERT INTO api_tokens (user_id, name, token_hash, created_at, expires_at, revoked_at, purpose) \
         VALUES (?, 'synthetic PAT fixture', ?, unixepoch(), COALESCE(?, unixepoch() + 86400), \
                 CASE WHEN ? THEN unixepoch() ELSE NULL END, ?)",
    )
    .bind(user_id)
    .bind(hex::encode(Sha256::digest(token.as_bytes())))
    .bind(expires_at)
    .bind(revoked)
    .bind(purpose)
    .execute(pool)
    .await
    .unwrap();
}

fn request(
    method: &str,
    path: &str,
    bearer: Option<&str>,
    cookie: Option<&str>,
    origin: bool,
    csrf: Option<&str>,
    body: &str,
) -> Request<Body> {
    let mut builder = Request::builder()
        .method(method)
        .uri(path)
        .header("host", "app.example.test")
        .header("content-type", "application/json");
    if let Some(token) = bearer {
        builder = builder.header("authorization", format!("Bearer {token}"));
    }
    if let Some(token) = cookie {
        builder = builder.header("cookie", format!("__Host-stricknani_session={token}"));
    }
    if origin {
        builder = builder
            .header("origin", ORIGIN)
            .header("sec-fetch-site", "same-origin");
    }
    if let Some(token) = csrf {
        builder = builder.header("x-csrf-token", csrf_token(token));
    }
    builder.body(Body::from(body.to_owned())).unwrap()
}

fn csrf_token(token: &str) -> String {
    let mut hash = Sha256::new();
    hash.update(b"stricknani browser csrf v1\0");
    hash.update(token.as_bytes());
    hex::encode(hash.finalize())
}

async fn json(response: axum::response::Response) -> serde_json::Value {
    serde_json::from_slice(&response.into_body().collect().await.unwrap().to_bytes()).unwrap()
}

#[tokio::test]
async fn admin_routes_accept_only_active_api_pats_and_authorization_never_falls_back_to_cookie() {
    let fixture = fixture().await;

    let valid_admin_pat = fixture
        .app
        .clone()
        .oneshot(request(
            "GET",
            "/api/v2/admin/users",
            Some(ADMIN_PAT),
            None,
            false,
            None,
            "",
        ))
        .await
        .unwrap();
    assert_eq!(valid_admin_pat.status(), StatusCode::OK);
    assert!(
        valid_admin_pat.headers()["cache-control"]
            .to_str()
            .unwrap()
            .contains("no-store")
    );

    let valid_member_pat_with_admin_cookie = fixture
        .app
        .clone()
        .oneshot(request(
            "GET",
            "/api/v2/admin/users",
            Some(MEMBER_PAT),
            Some(ADMIN_SESSION),
            false,
            None,
            "",
        ))
        .await
        .unwrap();
    assert_eq!(
        valid_member_pat_with_admin_cookie.status(),
        StatusCode::FORBIDDEN
    );
    assert_eq!(
        json(valid_member_pat_with_admin_cookie).await["error"]["code"],
        "admin_required"
    );

    let invalid_cases = [
        ("malformed", "Basic synthetic-value"),
        ("unknown", "Bearer synthetic-unknown-pat"),
        ("browser-purpose", "Bearer synthetic-browser-purpose-pat"),
        ("revoked", "Bearer synthetic-revoked-pat"),
        ("expired", "Bearer synthetic-expired-pat"),
        ("disabled-user", "Bearer synthetic-disabled-user-pat"),
    ];
    add_token(
        &fixture.pool,
        fixture.admin_id,
        "synthetic-browser-purpose-pat",
        "browser",
        None,
        false,
    )
    .await;
    add_token(
        &fixture.pool,
        fixture.admin_id,
        "synthetic-revoked-pat",
        "api",
        None,
        true,
    )
    .await;
    add_token(
        &fixture.pool,
        fixture.admin_id,
        "synthetic-expired-pat",
        "api",
        Some(0),
        false,
    )
    .await;
    let disabled_id = db::create_user(
        &fixture.pool,
        "disabled-admin@example.test",
        "disabled-password-long".into(),
    )
    .await
    .unwrap();
    sqlx::query("UPDATE users SET is_admin = 1, active = 0 WHERE id = ?")
        .bind(disabled_id)
        .execute(&fixture.pool)
        .await
        .unwrap();
    add_token(
        &fixture.pool,
        disabled_id,
        "synthetic-disabled-user-pat",
        "api",
        None,
        false,
    )
    .await;

    for (case, authorization) in invalid_cases {
        let response = fixture
            .app
            .clone()
            .oneshot(
                Request::builder()
                    .method("GET")
                    .uri("/api/v2/admin/users")
                    .header("authorization", authorization)
                    .header(
                        "cookie",
                        format!("__Host-stricknani_session={ADMIN_SESSION}"),
                    )
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::UNAUTHORIZED, "{case}");
        assert_eq!(
            json(response).await["error"]["code"],
            "session_expired",
            "{case}"
        );
    }

    let duplicate_authorization = fixture
        .app
        .clone()
        .oneshot({
            let mut request = Request::builder()
                .method("GET")
                .uri("/api/v2/admin/users")
                .header(
                    "cookie",
                    format!("__Host-stricknani_session={ADMIN_SESSION}"),
                )
                .body(Body::empty())
                .unwrap();
            request.headers_mut().append(
                "authorization",
                HeaderValue::from_static("Bearer synthetic-admin-purpose-api-pat"),
            );
            request.headers_mut().append(
                "authorization",
                HeaderValue::from_static("Bearer synthetic-unknown-pat"),
            );
            request
        })
        .await
        .unwrap();
    assert_eq!(duplicate_authorization.status(), StatusCode::UNAUTHORIZED);
    assert_eq!(
        json(duplicate_authorization).await["error"]["code"],
        "session_expired"
    );
}

#[tokio::test]
async fn admin_pat_mutations_need_no_browser_csrf_and_keep_admin_action_constraints() {
    let fixture = fixture().await;
    let self_deactivation = fixture
        .app
        .clone()
        .oneshot(request(
            "PATCH",
            &format!("/api/v2/admin/users/{}/active", fixture.admin_id),
            Some(ADMIN_PAT),
            None,
            false,
            None,
            r#"{"active":false}"#,
        ))
        .await
        .unwrap();
    assert_eq!(self_deactivation.status(), StatusCode::CONFLICT);
    assert_eq!(
        json(self_deactivation).await["error"]["code"],
        "admin_conflict"
    );

    let created = fixture
        .app
        .clone()
        .oneshot(request(
            "POST",
            "/api/v2/admin/users",
            Some(ADMIN_PAT),
            None,
            false,
            None,
            r#"{"email":"pat-created@example.test","password":"long-enough-password"}"#,
        ))
        .await
        .unwrap();
    assert_eq!(created.status(), StatusCode::CREATED);
    let created = json(created).await;
    let created_id = created["data"]["id"].as_i64().unwrap();
    assert_eq!(created["data"]["is_admin"], false);

    let deactivate = fixture
        .app
        .clone()
        .oneshot(request(
            "PATCH",
            &format!("/api/v2/admin/users/{created_id}/active"),
            Some(ADMIN_PAT),
            None,
            false,
            None,
            r#"{"active":false}"#,
        ))
        .await
        .unwrap();
    assert_eq!(deactivate.status(), StatusCode::OK);
    let active: i64 = sqlx::query_scalar("SELECT active FROM users WHERE id = ?")
        .bind(created_id)
        .fetch_one(&fixture.pool)
        .await
        .unwrap();
    assert_eq!(active, 0);

    let reset = fixture
        .app
        .clone()
        .oneshot(request(
            "POST",
            &format!("/api/v2/admin/users/{}/reset-password", fixture.member_id),
            Some(ADMIN_PAT),
            None,
            false,
            None,
            r#"{"new_password":"replacement-password-long"}"#,
        ))
        .await
        .unwrap();
    assert_eq!(reset.status(), StatusCode::OK);
    assert_eq!(json(reset).await["data"]["session_revoked"], true);
    let revoked_session: Option<i64> = sqlx::query_scalar(
        "SELECT revoked_at FROM api_tokens WHERE user_id = ? AND purpose = 'browser' AND token_hash = ?",
    )
    .bind(fixture.member_id)
    .bind(hex::encode(Sha256::digest(MEMBER_SESSION.as_bytes())))
    .fetch_one(&fixture.pool)
    .await
    .unwrap();
    assert!(revoked_session.is_some());

    let member_pat_write = fixture
        .app
        .clone()
        .oneshot(request(
            "POST",
            "/api/v2/admin/users",
            Some(MEMBER_PAT),
            Some(ADMIN_SESSION),
            false,
            None,
            r#"{"email":"must-not-exist@example.test","password":"long-enough-password"}"#,
        ))
        .await
        .unwrap();
    assert_eq!(member_pat_write.status(), StatusCode::FORBIDDEN);
    assert_eq!(
        json(member_pat_write).await["error"]["code"],
        "admin_required"
    );
    let unexpected_users: i64 = sqlx::query_scalar(
        "SELECT count(*) FROM users WHERE email = 'must-not-exist@example.test'",
    )
    .fetch_one(&fixture.pool)
    .await
    .unwrap();
    assert_eq!(unexpected_users, 0);
}

#[tokio::test]
async fn v1_meta_remains_the_public_version_and_build_id_response() {
    let fixture = fixture().await;
    let anonymous = fixture
        .app
        .clone()
        .oneshot(Request::get("/api/v1/meta").body(Body::empty()).unwrap())
        .await
        .unwrap();
    assert_eq!(anonymous.status(), StatusCode::OK);
    let anonymous = json(anonymous).await;
    assert!(anonymous["version"].is_string());
    assert!(anonymous["build_id"].is_string());
    assert_eq!(anonymous.as_object().unwrap().len(), 2);

    for authorization in [
        format!("Bearer {ADMIN_PAT}"),
        "Bearer synthetic-unknown-pat".to_owned(),
    ] {
        let response = fixture
            .app
            .clone()
            .oneshot(
                Request::get("/api/v1/meta")
                    .header("authorization", authorization)
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::OK);
        let response = json(response).await;
        assert!(response["version"].is_string());
        assert!(response["build_id"].is_string());
        assert_eq!(response.as_object().unwrap().len(), 2);
    }
}

#[tokio::test]
async fn v2_meta_resolves_current_browser_or_pat_identity() {
    let fixture = fixture().await;
    add_token(
        &fixture.pool,
        fixture.admin_id,
        "synthetic-expired-browser-session",
        "browser",
        Some(0),
        false,
    )
    .await;

    let anonymous = fixture
        .app
        .clone()
        .oneshot(Request::get("/api/v2/meta").body(Body::empty()).unwrap())
        .await
        .unwrap();
    assert_eq!(anonymous.status(), StatusCode::OK);
    assert!(
        anonymous.headers()["cache-control"]
            .to_str()
            .unwrap()
            .contains("no-store")
    );
    let anonymous = json(anonymous).await;
    let anonymous_data = &anonymous["data"];
    assert_eq!(anonymous_data["is_admin"], false);
    assert_eq!(anonymous_data["user"], serde_json::Value::Null);
    assert!(anonymous_data["version"].is_string());
    assert!(anonymous_data["build_id"].is_string());
    assert_eq!(anonymous_data.as_object().unwrap().len(), 4);

    let browser_admin = fixture
        .app
        .clone()
        .oneshot(
            Request::get("/api/v2/meta")
                .header(
                    "cookie",
                    format!("__Host-stricknani_session={ADMIN_SESSION}"),
                )
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();
    let browser_admin = json(browser_admin).await["data"].clone();
    assert_eq!(browser_admin["is_admin"], true);
    assert_eq!(browser_admin["user"]["id"], fixture.admin_id);
    assert_eq!(browser_admin["user"]["email"], "pat-admin@example.test");
    assert_eq!(browser_admin["user"]["is_admin"], true);
    assert_eq!(browser_admin["user"]["avatar_url"], serde_json::Value::Null);
    assert_eq!(browser_admin["user"].as_object().unwrap().len(), 4);

    for cookie in [
        "synthetic-unknown-browser-cookie",
        "synthetic-expired-browser-session",
    ] {
        let response = fixture
            .app
            .clone()
            .oneshot(
                Request::get("/api/v2/meta")
                    .header("cookie", format!("__Host-stricknani_session={cookie}"))
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::OK);
        let data = json(response).await["data"].clone();
        assert_eq!(data["is_admin"], false);
        assert_eq!(data["user"], serde_json::Value::Null);
    }

    for (pat, user_id, email, expected_admin, cookie) in [
        (
            ADMIN_PAT,
            fixture.admin_id,
            "pat-admin@example.test",
            true,
            None,
        ),
        (
            MEMBER_PAT,
            fixture.member_id,
            "pat-member@example.test",
            false,
            Some(ADMIN_SESSION),
        ),
    ] {
        let mut request =
            Request::get("/api/v2/meta").header("authorization", format!("Bearer {pat}"));
        if let Some(cookie) = cookie {
            request = request.header("cookie", format!("__Host-stricknani_session={cookie}"));
        }
        let response = fixture
            .app
            .clone()
            .oneshot(request.body(Body::empty()).unwrap())
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::OK);
        assert!(
            response.headers()["cache-control"]
                .to_str()
                .unwrap()
                .contains("no-store")
        );
        let data = json(response).await["data"].clone();
        assert_eq!(data["is_admin"], expected_admin);
        assert_eq!(data["user"]["id"], user_id);
        assert_eq!(data["user"]["email"], email);
        assert_eq!(data["user"]["is_admin"], expected_admin);
        assert_eq!(data["user"]["avatar_url"], serde_json::Value::Null);
        assert_eq!(data["user"].as_object().unwrap().len(), 4);
    }

    let bad_authorization_with_admin_cookie = fixture
        .app
        .clone()
        .oneshot(
            Request::get("/api/v2/meta")
                .header("authorization", "Bearer synthetic-unknown-pat")
                .header(
                    "cookie",
                    format!("__Host-stricknani_session={ADMIN_SESSION}"),
                )
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(
        bad_authorization_with_admin_cookie.status(),
        StatusCode::UNAUTHORIZED
    );
    assert!(
        bad_authorization_with_admin_cookie.headers()["cache-control"]
            .to_str()
            .unwrap()
            .contains("no-store")
    );
    let error = json(bad_authorization_with_admin_cookie).await;
    assert_eq!(error["error"]["code"], "session_expired");
    assert!(error.get("data").is_none());
}

#[tokio::test]
async fn v2_meta_exposes_owner_avatar_thumbnail_to_api_pat() {
    let fixture = fixture().await;

    let absent = fixture
        .app
        .clone()
        .oneshot(
            Request::get("/api/v2/meta")
                .header("authorization", format!("Bearer {ADMIN_PAT}"))
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(absent.status(), StatusCode::OK);
    assert_eq!(
        json(absent).await["data"]["user"]["avatar_url"],
        serde_json::Value::Null
    );

    let first_upload = upload_avatar(&fixture, [25, 100, 200, 255]).await;
    assert_eq!(first_upload.status(), StatusCode::OK);
    let first_avatar_url = json(first_upload).await["data"]["avatar_url"]
        .as_str()
        .unwrap()
        .to_owned();

    let first_meta = fixture
        .app
        .clone()
        .oneshot(
            Request::get("/api/v2/meta")
                .header("authorization", format!("Bearer {ADMIN_PAT}"))
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();
    let first_meta = json(first_meta).await;
    assert_eq!(first_meta["data"]["user"]["avatar_url"], first_avatar_url);

    let own_thumbnail = fixture
        .app
        .clone()
        .oneshot(
            Request::get(&first_avatar_url)
                .header("authorization", format!("Bearer {ADMIN_PAT}"))
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(own_thumbnail.status(), StatusCode::OK);
    assert!(own_thumbnail.headers().get("content-type").is_some());
    assert!(
        !own_thumbnail
            .into_body()
            .collect()
            .await
            .unwrap()
            .to_bytes()
            .is_empty()
    );

    let foreign_thumbnail = fixture
        .app
        .clone()
        .oneshot(
            Request::get(&first_avatar_url)
                .header("authorization", format!("Bearer {MEMBER_PAT}"))
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(foreign_thumbnail.status(), StatusCode::FORBIDDEN);

    let replacement = upload_avatar(&fixture, [220, 30, 75, 255]).await;
    assert_eq!(replacement.status(), StatusCode::OK);
    let replacement_url = json(replacement).await["data"]["avatar_url"]
        .as_str()
        .unwrap()
        .to_owned();
    assert_ne!(replacement_url, first_avatar_url);

    let replacement_meta = fixture
        .app
        .clone()
        .oneshot(
            Request::get("/api/v2/meta")
                .header("authorization", format!("Bearer {ADMIN_PAT}"))
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(
        json(replacement_meta).await["data"]["user"]["avatar_url"],
        replacement_url
    );
}

async fn upload_avatar(fixture: &Fixture, color: [u8; 4]) -> axum::response::Response {
    let boundary = "meta-avatar-test-boundary";
    let mut body = format!(
        "--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"profile.png\"\r\nContent-Type: image/png\r\n\r\n"
    )
    .into_bytes();
    body.extend(png_bytes(color));
    body.extend_from_slice(format!("\r\n--{boundary}--\r\n").as_bytes());
    fixture
        .app
        .clone()
        .oneshot(
            Request::post("/api/v2/account/avatar")
                .header(
                    "cookie",
                    format!("__Host-stricknani_session={ADMIN_SESSION}"),
                )
                .header("host", "app.example.test")
                .header("origin", ORIGIN)
                .header("sec-fetch-site", "same-origin")
                .header("x-csrf-token", csrf_token(ADMIN_SESSION))
                .header(
                    "content-type",
                    format!("multipart/form-data; boundary={boundary}"),
                )
                .body(Body::from(body))
                .unwrap(),
        )
        .await
        .unwrap()
}

fn png_bytes(color: [u8; 4]) -> Vec<u8> {
    let image = DynamicImage::ImageRgba8(ImageBuffer::from_pixel(2, 2, Rgba(color)));
    let mut output = Cursor::new(Vec::new());
    image.write_to(&mut output, ImageFormat::Png).unwrap();
    output.into_inner()
}
