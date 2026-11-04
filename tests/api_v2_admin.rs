use axum::{
    Router,
    body::Body,
    http::{Request, StatusCode},
};
use http_body_util::BodyExt;
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
    _media_root: tempfile::TempDir,
}

async fn fixture() -> Fixture {
    let pool = db::connect("sqlite::memory:")
        .await
        .expect("database migrates");
    let admin_id = db::create_user(&pool, "admin@example.test", "administrator-password".into())
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
        _media_root: media_root,
    }
}

#[tokio::test]
async fn admin_avatar_thumbnail_is_private_and_bounded_to_registered_files() {
    let fixture = fixture().await;
    let original = "profile.png";
    let thumbnail = "thumb_profile.jpg";
    let user_dir = fixture
        ._media_root
        .path()
        .join("thumbnails/users")
        .join(fixture.member_id.to_string());
    tokio::fs::create_dir_all(&user_dir).await.unwrap();
    tokio::fs::write(user_dir.join(thumbnail), b"synthetic thumbnail")
        .await
        .unwrap();
    sqlx::query(
        "INSERT INTO user_avatars(user_id, filename, original_filename, content_type) \
         VALUES (?, ?, 'profile.png', 'image/png')",
    )
    .bind(fixture.member_id)
    .bind(original)
    .execute(&fixture.pool)
    .await
    .unwrap();

    let users = fixture
        .app
        .clone()
        .oneshot(request("GET", "/admin/users", Some(ADMIN_TOKEN), None, ""))
        .await
        .unwrap();
    let users = json(users).await;
    let member = users["data"]
        .as_array()
        .unwrap()
        .iter()
        .find(|user| user["id"] == fixture.member_id)
        .unwrap();
    assert_eq!(
        member["avatar_url"],
        format!(
            "/api/v2/admin/users/{}/avatar/{thumbnail}",
            fixture.member_id
        )
    );

    let path = format!("/admin/users/{}/avatar/{thumbnail}", fixture.member_id);
    let anonymous = fixture
        .app
        .clone()
        .oneshot(request("GET", &path, None, None, ""))
        .await
        .unwrap();
    assert_eq!(anonymous.status(), StatusCode::UNAUTHORIZED);

    let member_request = fixture
        .app
        .clone()
        .oneshot(request("GET", &path, Some(MEMBER_TOKEN), None, ""))
        .await
        .unwrap();
    assert_eq!(member_request.status(), StatusCode::FORBIDDEN);

    let admin = fixture
        .app
        .clone()
        .oneshot(request("GET", &path, Some(ADMIN_TOKEN), None, ""))
        .await
        .unwrap();
    assert_eq!(admin.status(), StatusCode::OK);
    assert_eq!(admin.headers()["content-type"], "image/jpeg");
    assert_eq!(admin.headers()["x-content-type-options"], "nosniff");
    assert_eq!(admin.headers()["cache-control"], "no-store");
    assert_eq!(
        admin.into_body().collect().await.unwrap().to_bytes(),
        "synthetic thumbnail"
    );

    let pat = fixture
        .app
        .clone()
        .oneshot(
            Request::get(&path)
                .header("authorization", format!("Bearer {API_TOKEN}"))
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(pat.status(), StatusCode::OK);
    assert_eq!(pat.headers()["cache-control"], "no-store");
    assert_eq!(
        pat.into_body().collect().await.unwrap().to_bytes(),
        "synthetic thumbnail"
    );

    let wrong_name = fixture
        .app
        .clone()
        .oneshot(request(
            "GET",
            &format!("/admin/users/{}/avatar/thumb_other.jpg", fixture.member_id),
            Some(ADMIN_TOKEN),
            None,
            "",
        ))
        .await
        .unwrap();
    assert_eq!(wrong_name.status(), StatusCode::NOT_FOUND);

    let missing_avatar = fixture
        .app
        .clone()
        .oneshot(request(
            "GET",
            "/admin/users/999999/avatar/thumb_missing.jpg",
            Some(ADMIN_TOKEN),
            None,
            "",
        ))
        .await
        .unwrap();
    assert_eq!(missing_avatar.status(), StatusCode::NOT_FOUND);

    sqlx::query("UPDATE users SET active = 0 WHERE id = ?")
        .bind(fixture.admin_id)
        .execute(&fixture.pool)
        .await
        .unwrap();
    let inactive_admin = fixture
        .app
        .clone()
        .oneshot(request("GET", &path, Some(ADMIN_TOKEN), None, ""))
        .await
        .unwrap();
    assert_eq!(inactive_admin.status(), StatusCode::UNAUTHORIZED);

    sqlx::query("UPDATE users SET active = 1 WHERE id = ?")
        .bind(fixture.admin_id)
        .execute(&fixture.pool)
        .await
        .unwrap();
    sqlx::query("DROP TABLE user_avatars")
        .execute(&fixture.pool)
        .await
        .unwrap();
    let database_failure = fixture
        .app
        .oneshot(request("GET", &path, Some(ADMIN_TOKEN), None, ""))
        .await
        .unwrap();
    assert_eq!(database_failure.status(), StatusCode::INTERNAL_SERVER_ERROR);
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

fn request(
    method: &str,
    path: &str,
    token: Option<&str>,
    csrf_token: Option<&str>,
    body: &str,
) -> Request<Body> {
    let mut request = Request::builder()
        .method(method)
        .uri(path)
        .header("host", HOST)
        .header("content-type", "application/json");
    if let Some(token) = token {
        request = request.header("cookie", format!("__Host-stricknani_session={token}"));
    }
    if let Some(csrf_token) = csrf_token {
        request = request
            .header("origin", ORIGIN)
            .header("sec-fetch-site", "same-origin")
            .header("x-csrf-token", csrf_token);
    }
    request.body(Body::from(body.to_owned())).unwrap()
}

async fn json(response: axum::response::Response) -> serde_json::Value {
    serde_json::from_slice(&response.into_body().collect().await.unwrap().to_bytes()).unwrap()
}

#[tokio::test]
async fn admin_crud_uses_typed_roles_and_revokes_sessions_atomically() {
    let fixture = fixture().await;
    let response = fixture
        .app
        .clone()
        .oneshot(request(
            "POST",
            "/admin/users",
            Some(ADMIN_TOKEN),
            Some(&csrf(ADMIN_TOKEN)),
            r#"{"email":" New@example.test ","password":"new-member-password-long"}"#,
        ))
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::CREATED);
    assert_eq!(response.headers()["cache-control"], "no-store");
    let created = json(response).await;
    assert_eq!(created["data"]["email"], "new@example.test");
    assert_eq!(created["data"]["is_admin"], false);
    let new_id = created["data"]["id"].as_i64().unwrap();

    let duplicate = fixture
        .app
        .clone()
        .oneshot(request(
            "POST",
            "/admin/users",
            Some(ADMIN_TOKEN),
            Some(&csrf(ADMIN_TOKEN)),
            r#"{"email":"new@example.test","password":"another-member-password-long"}"#,
        ))
        .await
        .unwrap();
    assert_eq!(duplicate.status(), StatusCode::CONFLICT);
    let duplicate = json(duplicate).await;
    assert_eq!(duplicate["error"]["code"], "email_in_use");
    assert!(duplicate["error"]["fields"].get("email").is_some());

    let active = fixture
        .app
        .clone()
        .oneshot(request(
            "PATCH",
            &format!("/admin/users/{new_id}/active"),
            Some(ADMIN_TOKEN),
            Some(&csrf(ADMIN_TOKEN)),
            r#"{"active":false}"#,
        ))
        .await
        .unwrap();
    assert_eq!(active.status(), StatusCode::OK);
    assert_eq!(json(active).await["data"]["active"], false);

    let reset = fixture
        .app
        .clone()
        .oneshot(request(
            "POST",
            &format!("/admin/users/{}/reset-password", fixture.member_id),
            Some(ADMIN_TOKEN),
            Some(&csrf(ADMIN_TOKEN)),
            r#"{"new_password":"reset-member-password-long"}"#,
        ))
        .await
        .unwrap();
    assert_eq!(reset.status(), StatusCode::OK);
    assert_eq!(json(reset).await["data"]["session_revoked"], true);
    let revoked: Option<i64> = sqlx::query_scalar(
        "SELECT revoked_at FROM api_tokens WHERE user_id = ? AND purpose = 'browser' AND token_hash = ?",
    )
    .bind(fixture.member_id)
    .bind(hex::encode(Sha256::digest(MEMBER_TOKEN.as_bytes())))
    .fetch_one(&fixture.pool)
    .await
    .unwrap();
    assert!(revoked.is_some());
}

#[tokio::test]
async fn admin_list_is_paged_and_accepts_admin_api_pat_but_rejects_members() {
    let fixture = fixture().await;
    for index in 0..49 {
        sqlx::query(
            "INSERT INTO users (email, password_hash, is_admin) VALUES (?, 'test-hash', 0)",
        )
        .bind(format!("extra-{index:02}@example.test"))
        .execute(&fixture.pool)
        .await
        .unwrap();
    }
    let response = fixture
        .app
        .clone()
        .oneshot(request(
            "GET",
            "/admin/users?page=1",
            Some(ADMIN_TOKEN),
            None,
            "",
        ))
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    let result = json(response).await;
    assert_eq!(result["meta"]["page"], 1);
    assert_eq!(result["meta"]["per_page"], 50);
    assert_eq!(result["meta"]["has_more"], true);
    assert_eq!(result["data"].as_array().unwrap().len(), 50);
    assert!(
        result["data"]
            .as_array()
            .unwrap()
            .iter()
            .any(|user| user["id"] == fixture.admin_id)
    );

    let member = fixture
        .app
        .clone()
        .oneshot(request("GET", "/admin/users", Some(MEMBER_TOKEN), None, ""))
        .await
        .unwrap();
    assert_eq!(member.status(), StatusCode::FORBIDDEN);

    let bearer = fixture
        .app
        .clone()
        .oneshot(
            Request::get("/admin/users")
                .header("authorization", format!("Bearer {API_TOKEN}"))
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(bearer.status(), StatusCode::OK);
    assert_eq!(bearer.headers()["cache-control"], "no-store");

    let invalid = fixture
        .app
        .clone()
        .oneshot(request(
            "GET",
            "/admin/users?page=10001",
            Some(ADMIN_TOKEN),
            None,
            "",
        ))
        .await
        .unwrap();
    assert_eq!(invalid.status(), StatusCode::BAD_REQUEST);
    assert_eq!(json(invalid).await["error"]["code"], "invalid_query");
    let next_page = fixture
        .app
        .oneshot(request(
            "GET",
            "/admin/users?page=2",
            Some(ADMIN_TOKEN),
            None,
            "",
        ))
        .await
        .unwrap();
    let next = json(next_page).await;
    assert_eq!(next["meta"]["has_more"], false);
    assert_eq!(next["data"].as_array().unwrap().len(), 1);
}

#[tokio::test]
async fn admin_mutations_require_csrf_and_self_reset_clears_cookie() {
    let fixture = fixture().await;
    let member_create = fixture
        .app
        .clone()
        .oneshot(request(
            "POST",
            "/admin/users",
            Some(MEMBER_TOKEN),
            Some(&csrf(MEMBER_TOKEN)),
            r#"{"email":"denied@example.test","password":"short"}"#,
        ))
        .await
        .unwrap();
    assert_eq!(member_create.status(), StatusCode::FORBIDDEN);
    assert_eq!(json(member_create).await["error"]["code"], "admin_required");

    for request in [
        request(
            "POST",
            "/admin/users",
            Some(ADMIN_TOKEN),
            None,
            r#"{"email":"no@example.test","password":"long-password-enough"}"#,
        ),
        Request::post("/admin/users")
            .header("host", HOST)
            .header("origin", "https://evil.example.test")
            .header("sec-fetch-site", "cross-site")
            .header("cookie", format!("__Host-stricknani_session={ADMIN_TOKEN}"))
            .header("x-csrf-token", csrf(ADMIN_TOKEN))
            .header("content-type", "application/json")
            .body(Body::from(
                r#"{"email":"no@example.test","password":"long-password-enough"}"#,
            ))
            .unwrap(),
    ] {
        let response = fixture.app.clone().oneshot(request).await.unwrap();
        assert_eq!(response.status(), StatusCode::FORBIDDEN);
        assert_eq!(json(response).await["error"]["code"], "csrf_failed");
    }
    let count: i64 =
        sqlx::query_scalar("SELECT count(*) FROM users WHERE email = 'no@example.test'")
            .fetch_one(&fixture.pool)
            .await
            .unwrap();
    assert_eq!(count, 0);

    let self_disable = fixture
        .app
        .clone()
        .oneshot(request(
            "PATCH",
            &format!("/admin/users/{}/active", fixture.admin_id),
            Some(ADMIN_TOKEN),
            Some(&csrf(ADMIN_TOKEN)),
            r#"{"active":false}"#,
        ))
        .await
        .unwrap();
    assert_eq!(self_disable.status(), StatusCode::CONFLICT);
    assert_eq!(json(self_disable).await["error"]["code"], "admin_conflict");

    let response = fixture
        .app
        .clone()
        .oneshot(request(
            "POST",
            &format!("/admin/users/{}/reset-password", fixture.admin_id),
            Some(ADMIN_TOKEN),
            Some(&csrf(ADMIN_TOKEN)),
            r#"{"new_password":"admin-reset-password-long"}"#,
        ))
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    assert!(
        response
            .headers()
            .get_all("set-cookie")
            .iter()
            .filter_map(|value| value.to_str().ok())
            .any(|value| value.starts_with("__Host-stricknani_session=")
                && value.contains("Max-Age=0"))
    );
}

#[tokio::test]
async fn admin_json_is_bounded_strict_and_has_stable_errors() {
    let fixture = fixture().await;
    let invalid_json = fixture
        .app
        .clone()
        .oneshot(request(
            "POST",
            "/admin/users",
            Some(ADMIN_TOKEN),
            Some(&csrf(ADMIN_TOKEN)),
            "{",
        ))
        .await
        .unwrap();
    assert_eq!(invalid_json.status(), StatusCode::BAD_REQUEST);
    assert_eq!(json(invalid_json).await["error"]["code"], "invalid_json");

    let unknown = fixture
        .app
        .clone()
        .oneshot(request(
            "POST",
            "/admin/users",
            Some(ADMIN_TOKEN),
            Some(&csrf(ADMIN_TOKEN)),
            r#"{"email":"x@example.test","password":"long-enough-password","root":true}"#,
        ))
        .await
        .unwrap();
    assert_eq!(unknown.status(), StatusCode::BAD_REQUEST);
    assert_eq!(json(unknown).await["error"]["code"], "invalid_json");

    let invalid_email = fixture
        .app
        .clone()
        .oneshot(request(
            "POST",
            "/admin/users",
            Some(ADMIN_TOKEN),
            Some(&csrf(ADMIN_TOKEN)),
            r#"{"email":"@example.test","password":"long-enough-password"}"#,
        ))
        .await
        .unwrap();
    assert_eq!(invalid_email.status(), StatusCode::UNPROCESSABLE_ENTITY);
    let invalid_email = json(invalid_email).await;
    assert_eq!(invalid_email["error"]["code"], "invalid_email");
    assert!(invalid_email["error"]["fields"].get("email").is_some());

    let weak_password = fixture
        .app
        .clone()
        .oneshot(request(
            "POST",
            "/admin/users",
            Some(ADMIN_TOKEN),
            Some(&csrf(ADMIN_TOKEN)),
            r#"{"email":"weak@example.test","password":"short"}"#,
        ))
        .await
        .unwrap();
    assert_eq!(weak_password.status(), StatusCode::UNPROCESSABLE_ENTITY);
    let weak_password = json(weak_password).await;
    assert_eq!(weak_password["error"]["code"], "invalid_password");
    assert!(weak_password["error"]["fields"].get("password").is_some());

    let too_large = fixture
        .app
        .oneshot(request(
            "POST",
            "/admin/users",
            Some(ADMIN_TOKEN),
            Some(&csrf(ADMIN_TOKEN)),
            &format!(
                r#"{{"email":"x@example.test","password":"{}"}}"#,
                "x".repeat(9000)
            ),
        ))
        .await
        .unwrap();
    assert_eq!(too_large.status(), StatusCode::PAYLOAD_TOO_LARGE);
    assert_eq!(json(too_large).await["error"]["code"], "payload_too_large");
}
