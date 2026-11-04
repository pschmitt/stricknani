//! Self-service account deletion: re-authentication, confirmation, the
//! last-administrator guard, and complete removal of the account's data and
//! media files without touching other accounts.

use axum::{
    body::Body,
    http::{Request, StatusCode},
};
use http_body_util::BodyExt;
use sha2::{Digest, Sha256};
use sqlx::SqlitePool;
use std::path::{Path, PathBuf};
use stricknani_server::{
    api_v2,
    db::{self, AccountRole},
};
use tower::ServiceExt;

const HOST: &str = "app.example.test";
const ORIGIN: &str = "https://app.example.test";
const PASSWORD: &str = "delete-test-password-long";
const OTHER_PASSWORD: &str = "other-test-password-long";

struct Fixture {
    app: axum::Router,
    pool: SqlitePool,
    media: tempfile::TempDir,
}

async fn fixture() -> Fixture {
    let pool = db::connect("sqlite::memory:").await.unwrap();
    let media = tempfile::tempdir().unwrap();
    let state = db::AppState::new(pool.clone())
        .with_secure_cookies(true)
        .with_media_root(media.path().to_path_buf());
    Fixture {
        app: api_v2::router(state, false),
        pool,
        media,
    }
}

async fn json(response: axum::response::Response) -> serde_json::Value {
    let bytes = response.into_body().collect().await.unwrap().to_bytes();
    serde_json::from_slice(&bytes).unwrap_or(serde_json::Value::Null)
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

fn browser(
    method: &str,
    path: &str,
    session: &str,
    csrf: Option<&str>,
    body: serde_json::Value,
) -> Request<Body> {
    let mut request = Request::builder()
        .method(method)
        .uri(path)
        .header("cookie", format!("__Host-stricknani_session={session}"))
        .header("content-type", "application/json");
    if let Some(csrf) = csrf {
        request = request
            .header("host", HOST)
            .header("origin", ORIGIN)
            .header("sec-fetch-site", "same-origin")
            .header("x-csrf-token", csrf);
    }
    request.body(Body::from(body.to_string())).unwrap()
}

fn bearer(
    method: &str,
    path: &str,
    token: &str,
    extra: &[(&str, &str)],
    body: serde_json::Value,
) -> Request<Body> {
    let mut builder = Request::builder()
        .method(method)
        .uri(path)
        .header("authorization", format!("Bearer {token}"))
        .header("content-type", "application/json");
    for (name, value) in extra {
        builder = builder.header(*name, *value);
    }
    builder.body(Body::from(body.to_string())).unwrap()
}

async fn add_pat(pool: &SqlitePool, user_id: i64, secret: &str) -> i64 {
    sqlx::query_scalar(
        "INSERT INTO api_tokens (user_id, name, token_hash, purpose, created_at, expires_at) \
         VALUES (?, 'fixture', ?, 'api', unixepoch(), unixepoch() + 3600) RETURNING id",
    )
    .bind(user_id)
    .bind(hex::encode(Sha256::digest(secret.as_bytes())))
    .fetch_one(pool)
    .await
    .unwrap()
}

/// Files a user's data occupies on disk.
struct Files(Vec<PathBuf>);

impl Files {
    fn all_gone(&self) -> bool {
        self.0.iter().all(|path| !path.exists())
    }

    fn all_present(&self) -> bool {
        self.0.iter().all(|path| path.exists())
    }
}

fn write(root: &Path, segments: &[&str], name: &str) -> PathBuf {
    let dir = segments
        .iter()
        .fold(root.to_path_buf(), |acc, s| acc.join(s));
    std::fs::create_dir_all(&dir).unwrap();
    let path = dir.join(name);
    std::fs::write(&path, b"bytes").unwrap();
    path
}

/// A project (with a step, a title image and an attachment), a yarn photo,
/// favourites, an avatar and an audit event, each with real files on disk.
async fn populate(f: &Fixture, user_id: i64, tag: &str) -> Files {
    let root = f.media.path();
    let project: i64 = sqlx::query_scalar(
        "INSERT INTO projects(owner_id, name, created_at, updated_at) \
         VALUES (?, ?, '2026-01-01T00:00:00Z', '2026-01-01T00:00:00Z') RETURNING id",
    )
    .bind(user_id)
    .bind(format!("Project {tag}"))
    .fetch_one(&f.pool)
    .await
    .unwrap();
    let yarn: i64 = sqlx::query_scalar(
        "INSERT INTO yarns(owner_id, name, created_at, updated_at) \
         VALUES (?, ?, '2026-01-01T00:00:00Z', '2026-01-01T00:00:00Z') RETURNING id",
    )
    .bind(user_id)
    .bind(format!("Yarn {tag}"))
    .fetch_one(&f.pool)
    .await
    .unwrap();
    let step: i64 = sqlx::query_scalar(
        "INSERT INTO project_steps(project_id, title, step_number) VALUES (?, 'step', 1) \
         RETURNING id",
    )
    .bind(project)
    .fetch_one(&f.pool)
    .await
    .unwrap();
    sqlx::query("INSERT INTO user_favorites(user_id, project_id) VALUES (?, ?)")
        .bind(user_id)
        .bind(project)
        .execute(&f.pool)
        .await
        .unwrap();
    sqlx::query("INSERT INTO user_favorite_yarns(user_id, yarn_id) VALUES (?, ?)")
        .bind(user_id)
        .bind(yarn)
        .execute(&f.pool)
        .await
        .unwrap();

    let mut files = Vec::new();
    let project_id = project.to_string();
    let yarn_id = yarn.to_string();
    let user = user_id.to_string();
    for (kind, name, step_id, parent) in [
        ("title", format!("title-{tag}.jpg"), None, "projects"),
        ("step", format!("step-{tag}.jpg"), Some(step), "projects"),
        ("attachment", format!("pattern-{tag}.pdf"), None, "projects"),
        ("photo", format!("photo-{tag}.jpg"), None, "yarns"),
    ] {
        let (project_ref, yarn_ref) = if parent == "projects" {
            (Some(project), None)
        } else {
            (None, Some(yarn))
        };
        let parent_id = if parent == "projects" {
            &project_id
        } else {
            &yarn_id
        };
        let public_id: i64 =
            sqlx::query_scalar("SELECT coalesce(max(public_id), 0) + 1 FROM media_files")
                .fetch_one(&f.pool)
                .await
                .unwrap();
        sqlx::query(
            "INSERT INTO media_files(owner_id, project_id, yarn_id, step_id, kind, filename, \
                 original_filename, content_type, size_bytes, alt_text, is_primary, \
                 is_safe_inline, public_id) \
             VALUES (?, ?, ?, ?, ?, ?, 'orig', 'application/octet-stream', 5, '', 1, 0, ?)",
        )
        .bind(user_id)
        .bind(project_ref)
        .bind(yarn_ref)
        .bind(step_id)
        .bind(kind)
        .bind(&name)
        .bind(public_id)
        .execute(&f.pool)
        .await
        .unwrap();
        files.push(write(root, &[parent, parent_id], &name));
        let stem = name.rsplit_once('.').unwrap().0;
        files.push(write(
            root,
            &["thumbnails", parent, parent_id],
            &format!("thumb_{stem}.jpg"),
        ));
    }

    sqlx::query(
        "INSERT INTO user_avatars(user_id, filename, original_filename, content_type) \
         VALUES (?, 'avatar.jpg', 'me.jpg', 'image/jpeg')",
    )
    .bind(user_id)
    .execute(&f.pool)
    .await
    .unwrap();
    files.push(write(root, &["users", &user], "avatar.jpg"));
    files.push(write(
        root,
        &["thumbnails", "users", &user],
        "thumb_avatar.jpg",
    ));

    sqlx::query(
        "INSERT INTO audit_events(actor_user_id, entity_type, entity_id, action, \
             resource_name_snapshot, created_at) \
         VALUES (?, 'project', ?, 'created', ?, '2026-01-01T00:00:00Z')",
    )
    .bind(user_id)
    .bind(project)
    .bind(format!("secret title {tag}"))
    .execute(&f.pool)
    .await
    .unwrap();
    Files(files)
}

async fn count(pool: &SqlitePool, sql: &'static str, user_id: i64) -> i64 {
    sqlx::query_scalar(sql)
        .bind(user_id)
        .fetch_one(pool)
        .await
        .unwrap()
}

const OWNED_ROWS: [&str; 10] = [
    "SELECT count(*) FROM users WHERE id = ?",
    "SELECT count(*) FROM projects WHERE owner_id = ?",
    "SELECT count(*) FROM yarns WHERE owner_id = ?",
    "SELECT count(*) FROM media_files WHERE owner_id = ?",
    "SELECT count(*) FROM user_favorites WHERE user_id = ?",
    "SELECT count(*) FROM user_favorite_yarns WHERE user_id = ?",
    "SELECT count(*) FROM user_avatars WHERE user_id = ?",
    "SELECT count(*) FROM api_tokens WHERE user_id = ?",
    "SELECT count(*) FROM audit_events WHERE actor_user_id = ?",
    "SELECT count(*) FROM deletion_events WHERE owner_id = ?",
];

async fn assert_all_rows(pool: &SqlitePool, user_id: i64, present: bool) {
    for sql in OWNED_ROWS {
        let rows = count(pool, sql, user_id).await;
        if present {
            // deletion_events only exist after a user deleted something.
            if !sql.contains("deletion_events") {
                assert!(rows > 0, "expected rows for: {sql}");
            }
        } else {
            assert_eq!(rows, 0, "rows left behind for: {sql}");
        }
    }
}

#[tokio::test]
async fn password_deletion_removes_everything_and_files_but_not_other_accounts() {
    let f = fixture().await;
    let victim = db::create_user(&f.pool, "victim@example.test", PASSWORD.to_owned())
        .await
        .unwrap();
    let other = db::create_user(&f.pool, "other@example.test", OTHER_PASSWORD.to_owned())
        .await
        .unwrap();
    let victim_files = populate(&f, victim, "victim").await;
    let other_files = populate(&f, other, "other").await;
    let (session, csrf) = signed_in(&f.app, "victim@example.test", PASSWORD).await;
    add_pat(&f.pool, victim, "victim-pat-secret").await;
    assert_all_rows(&f.pool, victim, true).await;
    let other_before = {
        let mut rows = Vec::new();
        for sql in OWNED_ROWS {
            rows.push(count(&f.pool, sql, other).await);
        }
        rows
    };

    let response = f
        .app
        .clone()
        .oneshot(browser(
            "POST",
            "/account/delete",
            &session,
            Some(&csrf),
            serde_json::json!({"confirmation": "DELETE", "current_password": PASSWORD}),
        ))
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::NO_CONTENT);
    let clears_cookie = response
        .headers()
        .get_all("set-cookie")
        .iter()
        .any(|value| {
            let value = value.to_str().unwrap();
            value.starts_with("__Host-stricknani_session=") && value.contains("Max-Age=0")
        });
    assert!(clears_cookie, "the session cookie must be cleared");

    assert_all_rows(&f.pool, victim, false).await;
    assert!(
        victim_files.all_gone(),
        "victim media files must be removed"
    );
    let queued: i64 = sqlx::query_scalar(
        "SELECT (SELECT count(*) FROM media_gc) + (SELECT count(*) FROM avatar_gc)",
    )
    .fetch_one(&f.pool)
    .await
    .unwrap();
    assert_eq!(queued, 0, "deletion queue must be drained");

    // Other accounts are untouched, rows and files.
    let mut other_after = Vec::new();
    for sql in OWNED_ROWS {
        other_after.push(count(&f.pool, sql, other).await);
    }
    assert_eq!(other_before, other_after);
    assert!(other_files.all_present());

    // Audit: no PII-bearing rows of the victim, and an anonymous deletion event.
    for (title, expected) in [("secret title victim", 0), ("secret title other", 1)] {
        let rows: i64 = sqlx::query_scalar(
            "SELECT count(*) FROM audit_events WHERE resource_name_snapshot = ?",
        )
        .bind(title)
        .fetch_one(&f.pool)
        .await
        .unwrap();
        assert_eq!(rows, expected, "audit rows for {title}");
    }
    let event: (Option<i64>, Option<i64>) = sqlx::query_as(
        "SELECT actor_user_id, target_user_id FROM auth_audit_events \
         WHERE action = 'account_deleted'",
    )
    .fetch_one(&f.pool)
    .await
    .unwrap();
    assert_eq!(event, (None, None));

    // The old session no longer works, and the account cannot sign in again.
    let me = f
        .app
        .clone()
        .oneshot(
            Request::get("/account")
                .header("cookie", format!("__Host-stricknani_session={session}"))
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(me.status(), StatusCode::UNAUTHORIZED);
}

#[tokio::test]
async fn wrong_password_and_wrong_confirmation_delete_nothing() {
    let f = fixture().await;
    let user = db::create_user(&f.pool, "keep@example.test", PASSWORD.to_owned())
        .await
        .unwrap();
    let files = populate(&f, user, "keep").await;
    let (session, csrf) = signed_in(&f.app, "keep@example.test", PASSWORD).await;

    let wrong_password = f
        .app
        .clone()
        .oneshot(browser(
            "POST",
            "/account/delete",
            &session,
            Some(&csrf),
            serde_json::json!({"confirmation": "DELETE", "current_password": "not-the-password"}),
        ))
        .await
        .unwrap();
    assert_eq!(wrong_password.status(), StatusCode::UNAUTHORIZED);
    assert_eq!(
        json(wrong_password).await["error"]["code"],
        "invalid_credentials"
    );

    let wrong_confirmation = f
        .app
        .clone()
        .oneshot(browser(
            "POST",
            "/account/delete",
            &session,
            Some(&csrf),
            serde_json::json!({"confirmation": "delete me", "current_password": PASSWORD}),
        ))
        .await
        .unwrap();
    assert_eq!(
        wrong_confirmation.status(),
        StatusCode::UNPROCESSABLE_ENTITY
    );
    let body = json(wrong_confirmation).await;
    assert_eq!(body["error"]["code"], "validation_error");
    assert!(body["error"]["fields"]["confirmation"].is_string());

    // The account email is accepted, case-insensitively, as the confirmation.
    // Without any re-authentication a cookie session is refused.
    let no_reauth = f
        .app
        .clone()
        .oneshot(browser(
            "POST",
            "/account/delete",
            &session,
            Some(&csrf),
            serde_json::json!({"confirmation": "Keep@Example.test"}),
        ))
        .await
        .unwrap();
    assert_eq!(no_reauth.status(), StatusCode::FORBIDDEN);
    assert_eq!(json(no_reauth).await["error"]["code"], "reauth_required");

    assert_all_rows(&f.pool, user, true).await;
    assert!(files.all_present());
}

#[tokio::test]
async fn cookie_session_needs_csrf_and_same_origin() {
    let f = fixture().await;
    let user = db::create_user(&f.pool, "csrf@example.test", PASSWORD.to_owned())
        .await
        .unwrap();
    let files = populate(&f, user, "csrf").await;
    let (session, csrf) = signed_in(&f.app, "csrf@example.test", PASSWORD).await;
    let body = serde_json::json!({"confirmation": "DELETE", "current_password": PASSWORD});

    let no_headers = f
        .app
        .clone()
        .oneshot(browser(
            "POST",
            "/account/delete",
            &session,
            None,
            body.clone(),
        ))
        .await
        .unwrap();
    assert_eq!(no_headers.status(), StatusCode::FORBIDDEN);

    let bad_csrf = f
        .app
        .clone()
        .oneshot(browser(
            "POST",
            "/account/delete",
            &session,
            Some("not-the-csrf-token"),
            body.clone(),
        ))
        .await
        .unwrap();
    assert_eq!(bad_csrf.status(), StatusCode::FORBIDDEN);
    assert_eq!(json(bad_csrf).await["error"]["code"], "csrf_failed");

    let cross_origin = f
        .app
        .clone()
        .oneshot(
            Request::post("/account/delete")
                .header("cookie", format!("__Host-stricknani_session={session}"))
                .header("host", HOST)
                .header("origin", "https://evil.example.test")
                .header("sec-fetch-site", "cross-site")
                .header("x-csrf-token", &csrf)
                .header("content-type", "application/json")
                .body(Body::from(body.to_string()))
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(cross_origin.status(), StatusCode::FORBIDDEN);

    assert_all_rows(&f.pool, user, true).await;
    assert!(files.all_present());
}

#[tokio::test]
async fn cookie_session_can_delete_after_a_fresh_password_reauth() {
    let f = fixture().await;
    let user = db::create_user(&f.pool, "stamp@example.test", PASSWORD.to_owned())
        .await
        .unwrap();
    let (session, csrf) = signed_in(&f.app, "stamp@example.test", PASSWORD).await;
    let reauth = f
        .app
        .clone()
        .oneshot(browser(
            "POST",
            "/account/reauth",
            &session,
            Some(&csrf),
            serde_json::json!({"password": PASSWORD, "action": "delete_account"}),
        ))
        .await
        .unwrap();
    assert_eq!(reauth.status(), StatusCode::OK);
    let response = f
        .app
        .clone()
        .oneshot(browser(
            "POST",
            "/account/delete",
            &session,
            Some(&csrf),
            serde_json::json!({"confirmation": "stamp@example.test"}),
        ))
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::NO_CONTENT);
    assert_eq!(count(&f.pool, OWNED_ROWS[0], user).await, 0);
}

#[tokio::test]
async fn last_administrator_cannot_delete_but_one_of_two_can() {
    let f = fixture().await;
    let first = db::create_account(
        &f.pool,
        "admin1@example.test",
        PASSWORD.to_owned(),
        AccountRole::Admin,
    )
    .await
    .unwrap();
    let (session, csrf) = signed_in(&f.app, "admin1@example.test", PASSWORD).await;
    let body = serde_json::json!({"confirmation": "DELETE", "current_password": PASSWORD});

    let refused = f
        .app
        .clone()
        .oneshot(browser(
            "POST",
            "/account/delete",
            &session,
            Some(&csrf),
            body.clone(),
        ))
        .await
        .unwrap();
    assert_eq!(refused.status(), StatusCode::CONFLICT);
    assert_eq!(json(refused).await["error"]["code"], "last_admin");
    assert_eq!(count(&f.pool, OWNED_ROWS[0], first).await, 1);

    // An inactive administrator does not count as a successor.
    let inactive = db::create_account(
        &f.pool,
        "admin2@example.test",
        OTHER_PASSWORD.to_owned(),
        AccountRole::Admin,
    )
    .await
    .unwrap();
    sqlx::query("UPDATE users SET active = 0 WHERE id = ?")
        .bind(inactive)
        .execute(&f.pool)
        .await
        .unwrap();
    let still_refused = f
        .app
        .clone()
        .oneshot(browser(
            "POST",
            "/account/delete",
            &session,
            Some(&csrf),
            body.clone(),
        ))
        .await
        .unwrap();
    assert_eq!(still_refused.status(), StatusCode::CONFLICT);

    sqlx::query("UPDATE users SET active = 1 WHERE id = ?")
        .bind(inactive)
        .execute(&f.pool)
        .await
        .unwrap();
    let allowed = f
        .app
        .clone()
        .oneshot(browser(
            "POST",
            "/account/delete",
            &session,
            Some(&csrf),
            body,
        ))
        .await
        .unwrap();
    assert_eq!(allowed.status(), StatusCode::NO_CONTENT);
    assert_eq!(count(&f.pool, OWNED_ROWS[0], first).await, 0);
    assert_eq!(count(&f.pool, OWNED_ROWS[0], inactive).await, 1);
}

#[tokio::test]
async fn bearer_token_needs_a_password_or_a_single_use_proof() {
    let f = fixture().await;
    let user = db::create_user(&f.pool, "app@example.test", PASSWORD.to_owned())
        .await
        .unwrap();
    add_pat(&f.pool, user, "app-pat-secret").await;
    let body = serde_json::json!({"confirmation": "DELETE"});

    let no_proof = f
        .app
        .clone()
        .oneshot(bearer(
            "POST",
            "/account/delete",
            "app-pat-secret",
            &[],
            body.clone(),
        ))
        .await
        .unwrap();
    assert_eq!(no_proof.status(), StatusCode::FORBIDDEN);
    assert_eq!(json(no_proof).await["error"]["code"], "reauth_required");

    let bogus_proof = f
        .app
        .clone()
        .oneshot(bearer(
            "POST",
            "/account/delete",
            "app-pat-secret",
            &[("x-reauth-token", "reauth_bogus")],
            body.clone(),
        ))
        .await
        .unwrap();
    assert_eq!(bogus_proof.status(), StatusCode::FORBIDDEN);

    // A proof issued for another action is refused.
    let other_action = f
        .app
        .clone()
        .oneshot(bearer(
            "POST",
            "/account/reauth",
            "app-pat-secret",
            &[],
            serde_json::json!({"password": PASSWORD, "action": "create_api_token"}),
        ))
        .await
        .unwrap();
    assert_eq!(other_action.status(), StatusCode::OK);
    let wrong_purpose = json(other_action).await["data"]["reauth_token"]
        .as_str()
        .unwrap()
        .to_owned();
    let refused = f
        .app
        .clone()
        .oneshot(bearer(
            "POST",
            "/account/delete",
            "app-pat-secret",
            &[("x-reauth-token", &wrong_purpose)],
            body.clone(),
        ))
        .await
        .unwrap();
    assert_eq!(refused.status(), StatusCode::FORBIDDEN);

    let proof_response = f
        .app
        .clone()
        .oneshot(bearer(
            "POST",
            "/account/reauth",
            "app-pat-secret",
            &[],
            serde_json::json!({"password": PASSWORD, "action": "delete_account"}),
        ))
        .await
        .unwrap();
    assert_eq!(proof_response.status(), StatusCode::OK);
    let proof = json(proof_response).await["data"]["reauth_token"]
        .as_str()
        .unwrap()
        .to_owned();
    assert_eq!(count(&f.pool, OWNED_ROWS[0], user).await, 1);
    let wrong_confirmation = f
        .app
        .clone()
        .oneshot(bearer(
            "POST",
            "/account/delete",
            "app-pat-secret",
            &[("x-reauth-token", &proof)],
            serde_json::json!({"confirmation": "nope"}),
        ))
        .await
        .unwrap();
    assert_eq!(
        wrong_confirmation.status(),
        StatusCode::UNPROCESSABLE_ENTITY
    );
    // The failed attempt did not consume the single-use proof.
    let done = f
        .app
        .clone()
        .oneshot(bearer(
            "POST",
            "/account/delete",
            "app-pat-secret",
            &[("x-reauth-token", &proof)],
            body,
        ))
        .await
        .unwrap();
    assert_eq!(done.status(), StatusCode::NO_CONTENT);
    assert_eq!(count(&f.pool, OWNED_ROWS[0], user).await, 0);
}

#[tokio::test]
async fn bearer_token_can_delete_with_the_password() {
    let f = fixture().await;
    let user = db::create_user(&f.pool, "pw@example.test", PASSWORD.to_owned())
        .await
        .unwrap();
    let files = populate(&f, user, "pw").await;
    add_pat(&f.pool, user, "pw-pat-secret").await;

    let wrong = f
        .app
        .clone()
        .oneshot(bearer(
            "POST",
            "/account/delete",
            "pw-pat-secret",
            &[],
            serde_json::json!({"confirmation": "DELETE", "current_password": "nope-nope-nope"}),
        ))
        .await
        .unwrap();
    assert_eq!(wrong.status(), StatusCode::UNAUTHORIZED);
    assert!(files.all_present());

    let done = f
        .app
        .clone()
        .oneshot(bearer(
            "POST",
            "/account/delete",
            "pw-pat-secret",
            &[],
            serde_json::json!({"confirmation": "DELETE", "current_password": PASSWORD}),
        ))
        .await
        .unwrap();
    assert_eq!(done.status(), StatusCode::NO_CONTENT);
    assert_all_rows(&f.pool, user, false).await;
    assert!(files.all_gone());
}
