use axum::{
    body::Body,
    http::{Request, StatusCode},
};
use http_body_util::BodyExt;
use sqlx::SqlitePool;
use std::io::Cursor;
use stricknani_server::{api_v2, db};
use tower::ServiceExt;

const HOST: &str = "app.example.test";
const ORIGIN: &str = "https://app.example.test";
const PASSWORD: &str = "account-test-password-long";

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

async fn signed_in(app: &axum::Router, email: &str, password: &str) -> (String, String) {
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
                    serde_json::json!({"email":email,"password":password}).to_string(),
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

fn request(
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

#[tokio::test]
async fn account_reads_and_api_token_lifecycle_are_cookie_scoped_and_secret_once() {
    let (app, pool) = fixture().await;
    let anonymous = app
        .clone()
        .oneshot(Request::get("/account").body(Body::empty()).unwrap())
        .await
        .unwrap();
    assert_eq!(anonymous.status(), StatusCode::UNAUTHORIZED);
    db::create_user(&pool, "owner@example.test", PASSWORD.to_owned())
        .await
        .unwrap();
    let (session, csrf) = signed_in(&app, "owner@example.test", PASSWORD).await;
    sqlx::query(
        "INSERT INTO api_tokens (user_id, name, token_hash, purpose, created_at, expires_at) \
         VALUES ((SELECT id FROM users WHERE email = 'owner@example.test'), 'expired', \
           'expired-api-token-hash', 'api', unixepoch() - 7200, unixepoch() - 3600)",
    )
    .execute(&pool)
    .await
    .unwrap();
    sqlx::query(
        "INSERT INTO api_tokens (user_id, name, token_hash, purpose, created_at, revoked_at) \
         VALUES ((SELECT id FROM users WHERE email = 'owner@example.test'), 'revoked', \
           'revoked-api-token-hash', 'api', unixepoch(), unixepoch())",
    )
    .execute(&pool)
    .await
    .unwrap();

    let browser_token: i64 = sqlx::query_scalar(
        "SELECT id FROM api_tokens WHERE purpose = 'browser' ORDER BY id DESC LIMIT 1",
    )
    .fetch_one(&pool)
    .await
    .unwrap();
    let protected_browser_token = app
        .clone()
        .oneshot(request(
            "DELETE",
            &format!("/account/api-tokens/{browser_token}"),
            &session,
            Some(&csrf),
            None,
        ))
        .await
        .unwrap();
    assert_eq!(protected_browser_token.status(), StatusCode::NOT_FOUND);
    let foreign_id = db::create_user(
        &pool,
        "foreign@example.test",
        "foreign-test-password-long".to_owned(),
    )
    .await
    .unwrap();
    let foreign_token_id: i64 = sqlx::query_scalar(
        "INSERT INTO api_tokens (user_id, name, token_hash, purpose, created_at) \
         VALUES (?, 'foreign', 'foreign-token-hash', 'api', unixepoch()) RETURNING id",
    )
    .bind(foreign_id)
    .fetch_one(&pool)
    .await
    .unwrap();
    let foreign_revoke = app
        .clone()
        .oneshot(request(
            "DELETE",
            &format!("/account/api-tokens/{foreign_token_id}"),
            &session,
            Some(&csrf),
            None,
        ))
        .await
        .unwrap();
    assert_eq!(foreign_revoke.status(), StatusCode::NOT_FOUND);

    let account = app
        .clone()
        .oneshot(request("GET", "/account", &session, None, None))
        .await
        .unwrap();
    assert_eq!(account.status(), StatusCode::OK);
    let account = json(account).await;
    assert_eq!(account["data"]["email"], "owner@example.test");
    assert!(account["data"]["id"].is_number());
    assert_eq!(account["data"]["avatar_url"], serde_json::Value::Null);

    let missing_csrf = app
        .clone()
        .oneshot(request(
            "POST",
            "/account/api-tokens",
            &session,
            None,
            Some(serde_json::json!({"name":"browser"}).to_string()),
        ))
        .await
        .unwrap();
    assert_eq!(missing_csrf.status(), StatusCode::FORBIDDEN);

    let created = app
        .clone()
        .oneshot(request(
            "POST",
            "/account/api-tokens",
            &session,
            Some(&csrf),
            Some(serde_json::json!({"name":"integration"}).to_string()),
        ))
        .await
        .unwrap();
    assert_eq!(created.status(), StatusCode::CREATED);
    let created = json(created).await["data"].clone();
    let token_id = created["id"].as_i64().unwrap();
    let secret = created["token"].as_str().unwrap().to_owned();
    assert!(!secret.is_empty());
    assert_eq!(created["name"], "integration");

    let too_long_name = app
        .clone()
        .oneshot(request(
            "POST",
            "/account/api-tokens",
            &session,
            Some(&csrf),
            Some(serde_json::json!({"name":"x".repeat(256)}).to_string()),
        ))
        .await
        .unwrap();
    assert_eq!(too_long_name.status(), StatusCode::UNPROCESSABLE_ENTITY);
    assert_eq!(
        json(too_long_name).await["error"]["fields"]["name"],
        "Token names must be 255 bytes or fewer"
    );

    let oversized = format!("{{\"name\":\"{}\"}}", "x".repeat(2 * 1024 * 1024));
    let oversized = app
        .clone()
        .oneshot(request(
            "POST",
            "/account/api-tokens",
            &session,
            Some(&csrf),
            Some(oversized),
        ))
        .await
        .unwrap();
    assert_eq!(oversized.status(), StatusCode::PAYLOAD_TOO_LARGE);
    assert_eq!(json(oversized).await["error"]["code"], "payload_too_large");

    let listed = app
        .clone()
        .oneshot(request("GET", "/account/api-tokens", &session, None, None))
        .await
        .unwrap();
    assert_eq!(listed.status(), StatusCode::OK);
    let listed = json(listed).await["data"].as_array().unwrap().clone();
    assert_eq!(listed.len(), 1);
    assert_eq!(listed[0]["id"], token_id);
    assert!(listed[0].get("token").is_none());
    assert!(listed[0].get("token_hash").is_none());
    assert!(listed[0]["created_at"].as_str().unwrap().contains('T'));

    let revoked = app
        .clone()
        .oneshot(request(
            "DELETE",
            &format!("/account/api-tokens/{token_id}"),
            &session,
            Some(&csrf),
            None,
        ))
        .await
        .unwrap();
    assert_eq!(revoked.status(), StatusCode::NO_CONTENT);
    let repeated = app
        .clone()
        .oneshot(request(
            "DELETE",
            &format!("/account/api-tokens/{token_id}"),
            &session,
            Some(&csrf),
            None,
        ))
        .await
        .unwrap();
    assert_eq!(repeated.status(), StatusCode::NO_CONTENT);
    let stored_hash: String = sqlx::query_scalar("SELECT token_hash FROM api_tokens WHERE id = ?")
        .bind(token_id)
        .fetch_one(&pool)
        .await
        .unwrap();
    assert_ne!(stored_hash, secret);
    let listed = app
        .clone()
        .oneshot(request("GET", "/account/api-tokens", &session, None, None))
        .await
        .unwrap();
    assert!(json(listed).await["data"].as_array().unwrap().is_empty());
}

#[tokio::test]
async fn concurrent_api_token_creation_cannot_exceed_five_per_hour() {
    let (app, pool) = fixture().await;
    db::create_user(&pool, "race@example.test", PASSWORD.to_owned())
        .await
        .unwrap();
    let (session, csrf) = signed_in(&app, "race@example.test", PASSWORD).await;
    let mut tasks = tokio::task::JoinSet::new();
    for index in 0..6 {
        let app = app.clone();
        let session = session.clone();
        let csrf = csrf.clone();
        tasks.spawn(async move {
            app.oneshot(request(
                "POST",
                "/account/api-tokens",
                &session,
                Some(&csrf),
                Some(serde_json::json!({"name":format!("concurrent-{index}")}).to_string()),
            ))
            .await
            .unwrap()
            .status()
        });
    }
    let mut created = 0;
    let mut limited = 0;
    while let Some(result) = tasks.join_next().await {
        match result.unwrap() {
            StatusCode::CREATED => created += 1,
            StatusCode::TOO_MANY_REQUESTS => limited += 1,
            status => panic!("unexpected concurrent token response: {status}"),
        }
    }
    assert_eq!((created, limited), (5, 1));
    let issued: i64 = sqlx::query_scalar(
        "SELECT count(*) FROM api_tokens WHERE purpose = 'api' AND created_at > unixepoch() - 3600",
    )
    .fetch_one(&pool)
    .await
    .unwrap();
    assert_eq!(issued, 5);
}

#[tokio::test]
async fn account_mutations_reject_cross_origin_and_token_quota_counts_revoked_api_only() {
    let (app, pool) = fixture().await;
    let owner_id = db::create_user(&pool, "quota@example.test", PASSWORD.to_owned())
        .await
        .unwrap();
    let (session, csrf) = signed_in(&app, "quota@example.test", PASSWORD).await;
    let bad_origin = Request::post("/account/api-tokens")
        .header("cookie", format!("__Host-stricknani_session={session}"))
        .header("host", HOST)
        .header("origin", "https://evil.example")
        .header("x-csrf-token", &csrf)
        .header("content-type", "application/json")
        .body(Body::from("{\"name\":\"rejected\"}"))
        .unwrap();
    let rejected = app.clone().oneshot(bad_origin).await.unwrap();
    assert_eq!(rejected.status(), StatusCode::FORBIDDEN);
    let count: i64 =
        sqlx::query_scalar("SELECT count(*) FROM api_tokens WHERE user_id = ? AND purpose = 'api'")
            .bind(owner_id)
            .fetch_one(&pool)
            .await
            .unwrap();
    assert_eq!(count, 0);

    for index in 0..5 {
        sqlx::query(
            "INSERT INTO api_tokens (user_id, name, token_hash, purpose, created_at, revoked_at) \
             VALUES (?, ?, ?, 'api', unixepoch(), CASE WHEN ? = 0 THEN unixepoch() ELSE NULL END)",
        )
        .bind(owner_id)
        .bind(format!("seed-{index}"))
        .bind(format!("hashed-seed-{index}"))
        .bind(index)
        .execute(&pool)
        .await
        .unwrap();
    }
    // Browser sessions do not count against the API-token creation quota.
    sqlx::query(
        "INSERT INTO api_tokens (user_id, name, token_hash, purpose, created_at) \
         VALUES (?, 'browser', 'browser-purpose-hash', 'browser', unixepoch())",
    )
    .bind(owner_id)
    .execute(&pool)
    .await
    .unwrap();
    let limited = app
        .clone()
        .oneshot(request(
            "POST",
            "/account/api-tokens",
            &session,
            Some(&csrf),
            Some(serde_json::json!({"name":"sixth"}).to_string()),
        ))
        .await
        .unwrap();
    assert_eq!(limited.status(), StatusCode::TOO_MANY_REQUESTS);
    assert!(limited.headers().contains_key("retry-after"));
    assert_eq!(json(limited).await["error"]["code"], "rate_limited");
}

#[tokio::test]
async fn password_change_commits_all_credential_revocations_before_clearing_cookie() {
    let (app, pool) = fixture().await;
    let owner_id = db::create_user(&pool, "password@example.test", PASSWORD.to_owned())
        .await
        .unwrap();
    let (session, csrf) = signed_in(&app, "password@example.test", PASSWORD).await;
    let pat_id = sqlx::query(
        "INSERT INTO api_tokens(user_id, name, token_hash, created_at, purpose) \
         VALUES (?, 'password change fixture', 'password-change-test-hash', unixepoch(), 'api')",
    )
    .bind(owner_id)
    .execute(&pool)
    .await
    .unwrap()
    .last_insert_rowid();
    let changed = app
        .clone()
        .oneshot(request(
            "PUT",
            "/account/password",
            &session,
            Some(&csrf),
            Some(
                serde_json::json!({
                    "current_password": PASSWORD,
                    "new_password": "a-different-long-account-password"
                })
                .to_string(),
            ),
        ))
        .await
        .unwrap();
    assert_eq!(changed.status(), StatusCode::NO_CONTENT);
    let set_cookie: Vec<_> = changed
        .headers()
        .get_all("set-cookie")
        .iter()
        .filter_map(|value| value.to_str().ok())
        .collect();
    assert!(set_cookie.iter().any(|value| value.contains("Max-Age=0")));

    let stale = app
        .clone()
        .oneshot(request("GET", "/account", &session, None, None))
        .await
        .unwrap();
    assert_eq!(stale.status(), StatusCode::UNAUTHORIZED);
    let revoked: i64 = sqlx::query_scalar(
        "SELECT count(*) FROM api_tokens WHERE user_id = ? AND purpose = 'browser' AND revoked_at IS NOT NULL",
    )
    .bind(owner_id)
    .fetch_one(&pool)
    .await
    .unwrap();
    assert!(revoked >= 1);
    let api_revoked: bool =
        sqlx::query_scalar("SELECT revoked_at IS NOT NULL FROM api_tokens WHERE id = ?")
            .bind(pat_id)
            .fetch_one(&pool)
            .await
            .unwrap();
    assert!(api_revoked);
}

#[tokio::test]
async fn invalid_password_changes_return_field_errors_without_revoking_the_session() {
    let (app, pool) = fixture().await;
    let owner_id = db::create_user(&pool, "password-errors@example.test", PASSWORD.to_owned())
        .await
        .unwrap();
    let (session, csrf) = signed_in(&app, "password-errors@example.test", PASSWORD).await;
    for (current, new, field) in [
        (
            "incorrect-current-password",
            "a-replacement-password-long",
            "current_password",
        ),
        (PASSWORD, "short", "new_password"),
    ] {
        let response = app
            .clone()
            .oneshot(request(
                "PUT",
                "/account/password",
                &session,
                Some(&csrf),
                Some(
                    serde_json::json!({
                        "current_password": current,
                        "new_password": new
                    })
                    .to_string(),
                ),
            ))
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::UNPROCESSABLE_ENTITY);
        assert!(json(response).await["error"]["fields"][field].is_string());
    }
    let still_authenticated = app
        .clone()
        .oneshot(request("GET", "/account", &session, None, None))
        .await
        .unwrap();
    assert_eq!(still_authenticated.status(), StatusCode::OK);
    let revoked: i64 = sqlx::query_scalar(
        "SELECT count(*) FROM api_tokens WHERE user_id = ? AND purpose = 'browser' AND revoked_at IS NOT NULL",
    )
    .bind(owner_id)
    .fetch_one(&pool)
    .await
    .unwrap();
    assert_eq!(revoked, 0);
}

#[tokio::test]
async fn avatar_routes_share_image_processing_and_owner_registry_lifecycle() {
    let pool = db::connect("sqlite::memory:").await.unwrap();
    let root = tempfile::tempdir().unwrap();
    let state = db::AppState::new(pool.clone())
        .with_secure_cookies(true)
        .with_media_root(root.path().to_path_buf());
    let app = api_v2::router(state, false);
    db::create_user(&pool, "avatar@example.test", PASSWORD.to_owned())
        .await
        .unwrap();
    let (session, csrf) = signed_in(&app, "avatar@example.test", PASSWORD).await;
    let session_without_avatar = app
        .clone()
        .oneshot(request("GET", "/session", &session, None, None))
        .await
        .unwrap();
    let session_data = json(session_without_avatar).await;
    assert!(session_data["data"]["user"]["avatar_url"].is_null());
    let boundary = "account-avatar-test-boundary";
    let mut body = format!(
        "--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"profile.png\"\r\nContent-Type: image/png\r\n\r\n"
    )
    .into_bytes();
    body.extend(png_bytes());
    body.extend_from_slice(format!("\r\n--{boundary}--\r\n").as_bytes());
    let uploaded = app
        .clone()
        .oneshot(
            Request::post("/account/avatar")
                .header("cookie", format!("__Host-stricknani_session={session}"))
                .header("host", HOST)
                .header("origin", ORIGIN)
                .header("sec-fetch-site", "same-origin")
                .header("x-csrf-token", &csrf)
                .header(
                    "content-type",
                    format!("multipart/form-data; boundary={boundary}"),
                )
                .body(Body::from(body))
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(uploaded.status(), StatusCode::OK);
    let avatar_url = json(uploaded).await["data"]["avatar_url"]
        .as_str()
        .unwrap()
        .to_owned();
    assert!(avatar_url.starts_with("/media/thumbnails/users/"));
    let session_with_avatar = app
        .clone()
        .oneshot(request("GET", "/session", &session, None, None))
        .await
        .unwrap();
    assert_eq!(
        json(session_with_avatar).await["data"]["user"]["avatar_url"],
        avatar_url
    );
    let filename: String = sqlx::query_scalar("SELECT filename FROM user_avatars")
        .fetch_one(&pool)
        .await
        .unwrap();
    assert!(filename.ends_with(".png"));

    let deleted = app
        .clone()
        .oneshot(request(
            "DELETE",
            "/account/avatar",
            &session,
            Some(&csrf),
            None,
        ))
        .await
        .unwrap();
    assert_eq!(deleted.status(), StatusCode::NO_CONTENT);
    let session_without_avatar = app
        .clone()
        .oneshot(request("GET", "/session", &session, None, None))
        .await
        .unwrap();
    let session_data = json(session_without_avatar).await;
    assert!(session_data["data"]["user"]["avatar_url"].is_null());
    let remaining: i64 = sqlx::query_scalar("SELECT count(*) FROM user_avatars")
        .fetch_one(&pool)
        .await
        .unwrap();
    assert_eq!(remaining, 0);

    let oversized_body = {
        let mut body = format!(
            "--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"large.png\"\r\nContent-Type: image/png\r\n\r\n"
        )
        .into_bytes();
        body.resize(body.len() + 25 * 1024 * 1024 + 1, 0);
        body.extend_from_slice(format!("\r\n--{boundary}--\r\n").as_bytes());
        body
    };
    let oversized = app
        .clone()
        .oneshot(
            Request::post("/account/avatar")
                .header("cookie", format!("__Host-stricknani_session={session}"))
                .header("host", HOST)
                .header("origin", ORIGIN)
                .header("sec-fetch-site", "same-origin")
                .header("x-csrf-token", &csrf)
                .header(
                    "content-type",
                    format!("multipart/form-data; boundary={boundary}"),
                )
                .body(Body::from(oversized_body))
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(oversized.status(), StatusCode::PAYLOAD_TOO_LARGE);
    assert_eq!(json(oversized).await["error"]["code"], "payload_too_large");
}

fn png_bytes() -> Vec<u8> {
    let mut output = Cursor::new(Vec::new());
    image::DynamicImage::new_rgba8(2, 2)
        .write_to(&mut output, image::ImageFormat::Png)
        .unwrap();
    output.into_inner()
}
