//! Administrators see and edit every user's projects and yarns; owners keep
//! their rights and other users stay confined to their own items.

use axum::{
    body::Body,
    http::{Request, StatusCode},
};
use http_body_util::BodyExt;
use sha2::{Digest, Sha256};
use sqlx::SqlitePool;
use std::io::Cursor;
use stricknani_server::{app_with_state, db};
use tower::ServiceExt;

const ADMIN_PAT: &str = "scope-admin-api-pat";
const ANIKA_PAT: &str = "scope-anika-api-pat";
const BEN_PAT: &str = "scope-ben-api-pat";
const HOST: &str = "app.example.test";
const ORIGIN: &str = "https://app.example.test";

struct Fixture {
    _root: tempfile::TempDir,
    app: axum::Router,
    pool: SqlitePool,
    admin_id: i64,
    anika_id: i64,
    ben_id: i64,
}

async fn seed_user(pool: &SqlitePool, email: &str, token: &str, admin: bool) -> i64 {
    let id = sqlx::query(
        "INSERT INTO users (email, password_hash, is_admin) VALUES (?, 'test-only-hash', ?)",
    )
    .bind(email)
    .bind(admin)
    .execute(pool)
    .await
    .unwrap()
    .last_insert_rowid();
    sqlx::query(
        "INSERT INTO api_tokens (user_id, name, token_hash, created_at, expires_at, purpose) \
         VALUES (?, 'scope fixture', ?, unixepoch(), unixepoch() + 86400, 'api')",
    )
    .bind(id)
    .bind(hex::encode(Sha256::digest(token.as_bytes())))
    .execute(pool)
    .await
    .unwrap();
    id
}

async fn fixture() -> Fixture {
    let pool = db::connect("sqlite::memory:").await.unwrap();
    let admin_id = seed_user(&pool, "admin@example.test", ADMIN_PAT, true).await;
    let anika_id = seed_user(&pool, "anika@example.test", ANIKA_PAT, false).await;
    let ben_id = seed_user(&pool, "ben@example.test", BEN_PAT, false).await;
    let root = tempfile::tempdir().unwrap();
    let state = db::AppState::new(pool.clone())
        .with_secure_cookies(true)
        .with_media_root(root.path().to_path_buf());
    Fixture {
        _root: root,
        app: app_with_state(state),
        pool,
        admin_id,
        anika_id,
        ben_id,
    }
}

fn micros(value: &str) -> String {
    chrono::DateTime::parse_from_rfc3339(value)
        .unwrap()
        .with_timezone(&chrono::Utc)
        .to_rfc3339_opts(chrono::SecondsFormat::Micros, true)
}

async fn project(pool: &SqlitePool, owner: i64, name: &str, at: &str) -> i64 {
    let at = &micros(at);
    sqlx::query_scalar(
        "INSERT INTO projects (owner_id,name,tags,created_at,updated_at) \
         VALUES (?,?,'[]',?,?) RETURNING id",
    )
    .bind(owner)
    .bind(name)
    .bind(at)
    .bind(at)
    .fetch_one(pool)
    .await
    .unwrap()
}

async fn set_project_tags(pool: &SqlitePool, project_id: i64, tags: &[&str]) {
    sqlx::query("UPDATE projects SET tags = ? WHERE id = ?")
        .bind(serde_json::to_string(tags).unwrap())
        .bind(project_id)
        .execute(pool)
        .await
        .unwrap();
}

async fn yarn(pool: &SqlitePool, owner: i64, name: &str, at: &str) -> i64 {
    let at = &micros(at);
    sqlx::query_scalar(
        "INSERT INTO yarns (owner_id,name,created_at,updated_at) VALUES (?,?,?,?) RETURNING id",
    )
    .bind(owner)
    .bind(name)
    .bind(at)
    .bind(at)
    .fetch_one(pool)
    .await
    .unwrap()
}

async fn call(
    app: &axum::Router,
    method: &str,
    path: &str,
    pat: &str,
    body: Option<serde_json::Value>,
) -> (StatusCode, serde_json::Value) {
    let mut request = Request::builder()
        .method(method)
        .uri(path)
        .header("authorization", format!("Bearer {pat}"));
    if body.is_some() {
        request = request.header("content-type", "application/json");
    }
    let response = app
        .clone()
        .oneshot(
            request
                .body(body.map_or_else(Body::empty, |b| Body::from(b.to_string())))
                .unwrap(),
        )
        .await
        .unwrap();
    let status = response.status();
    let bytes = response.into_body().collect().await.unwrap().to_bytes();
    let value = serde_json::from_slice(&bytes).unwrap_or(serde_json::Value::Null);
    (status, value)
}

fn names(list: &serde_json::Value) -> Vec<String> {
    let mut names: Vec<String> = list["data"]
        .as_array()
        .unwrap()
        .iter()
        .map(|item| item["name"].as_str().unwrap().to_owned())
        .collect();
    names.sort();
    names
}

#[tokio::test]
async fn lists_default_to_everything_for_admins_and_to_mine_for_everyone_else() {
    let f = fixture().await;
    project(&f.pool, f.admin_id, "admin hat", "2026-01-01T00:00:00Z").await;
    project(&f.pool, f.anika_id, "anika shawl", "2026-01-02T00:00:00Z").await;
    yarn(&f.pool, f.admin_id, "admin wool", "2026-01-01T00:00:00Z").await;
    yarn(&f.pool, f.anika_id, "anika wool", "2026-01-02T00:00:00Z").await;

    let (_, all) = call(&f.app, "GET", "/api/v2/projects", ADMIN_PAT, None).await;
    assert_eq!(names(&all), ["admin hat", "anika shawl"]);
    assert_eq!(all["meta"]["scope"], "all");
    let foreign = all["data"]
        .as_array()
        .unwrap()
        .iter()
        .find(|item| item["name"] == "anika shawl")
        .unwrap();
    assert_eq!(foreign["owner"]["id"], f.anika_id);
    assert_eq!(foreign["owner"]["email"], "anika@example.test");
    assert_eq!(foreign["owner"]["name"], "Anika");

    let (_, mine) = call(
        &f.app,
        "GET",
        "/api/v2/projects?scope=mine",
        ADMIN_PAT,
        None,
    )
    .await;
    assert_eq!(names(&mine), ["admin hat"]);
    assert_eq!(mine["meta"]["scope"], "mine");
    let (_, only_anika) = call(
        &f.app,
        "GET",
        &format!("/api/v2/yarns?scope=all&owner_id={}", f.anika_id),
        ADMIN_PAT,
        None,
    )
    .await;
    assert_eq!(names(&only_anika), ["anika wool"]);
    let (_, all_yarns) = call(&f.app, "GET", "/api/v2/yarns", ADMIN_PAT, None).await;
    assert_eq!(names(&all_yarns), ["admin wool", "anika wool"]);

    // Non-admins are always confined to their own items; scope=all is ignored.
    let (status, ignored) = call(&f.app, "GET", "/api/v2/projects?scope=all", BEN_PAT, None).await;
    assert_eq!(status, StatusCode::OK);
    assert!(names(&ignored).is_empty());
    assert_eq!(ignored["meta"]["scope"], "mine");
    let (_, anika) = call(
        &f.app,
        "GET",
        &format!("/api/v2/yarns?scope=all&owner_id={}", f.admin_id),
        ANIKA_PAT,
        None,
    )
    .await;
    assert_eq!(names(&anika), ["anika wool"]);
    let (status, _) = call(
        &f.app,
        "GET",
        "/api/v2/projects?scope=bogus",
        ADMIN_PAT,
        None,
    )
    .await;
    assert_eq!(status, StatusCode::UNPROCESSABLE_ENTITY);

    // v1 list: same rules.
    let (_, v1) = call(&f.app, "GET", "/api/v1/projects", ADMIN_PAT, None).await;
    assert_eq!(v1["items"].as_array().unwrap().len(), 2);
    let (_, v1_ben) = call(&f.app, "GET", "/api/v1/projects?scope=all", BEN_PAT, None).await;
    assert!(v1_ben["items"].as_array().unwrap().is_empty());
}

#[tokio::test]
async fn project_tag_inventory_expands_only_for_admin_all_scope() {
    let f = fixture().await;
    let admin_project = project(&f.pool, f.admin_id, "admin hat", "2026-01-01T00:00:00Z").await;
    set_project_tags(&f.pool, admin_project, &["admin-tag"]).await;
    let foreign_project = project(&f.pool, f.anika_id, "anika shawl", "2026-01-02T00:00:00Z").await;
    set_project_tags(&f.pool, foreign_project, &["foreign-tag"]).await;
    let ben_project = project(&f.pool, f.ben_id, "ben blanket", "2026-01-03T00:00:00Z").await;
    set_project_tags(&f.pool, ben_project, &["ben-private-tag"]).await;

    let (status, default_admin_tags) =
        call(&f.app, "GET", "/api/v2/projects/tags", ADMIN_PAT, None).await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(default_admin_tags["data"].as_array().unwrap().len(), 1);
    assert_eq!(default_admin_tags["data"][0]["tag"], "admin-tag");

    let (status, all_admin_tags) = call(
        &f.app,
        "GET",
        "/api/v2/projects/tags?scope=all",
        ADMIN_PAT,
        None,
    )
    .await;
    assert_eq!(status, StatusCode::OK);
    let admin_tag_names: Vec<_> = all_admin_tags["data"]
        .as_array()
        .unwrap()
        .iter()
        .map(|item| item["tag"].as_str().unwrap())
        .collect();
    assert_eq!(
        admin_tag_names,
        ["admin-tag", "ben-private-tag", "foreign-tag"]
    );

    let (status, ignored_all_tags) = call(
        &f.app,
        "GET",
        "/api/v2/projects/tags?scope=all",
        ANIKA_PAT,
        None,
    )
    .await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(ignored_all_tags["data"].as_array().unwrap().len(), 1);
    assert_eq!(ignored_all_tags["data"][0]["tag"], "foreign-tag");
    assert!(
        !ignored_all_tags["data"]
            .as_array()
            .unwrap()
            .iter()
            .any(|item| item["tag"] == "ben-private-tag")
    );
}

#[tokio::test]
async fn admin_reads_edits_favourites_and_deletes_foreign_project_through_v2() {
    let f = fixture().await;
    let id = project(&f.pool, f.anika_id, "anika shawl", "2026-01-02T00:00:00Z").await;
    let own_yarn = yarn(&f.pool, f.anika_id, "anika wool", "2026-01-02T00:00:00Z").await;
    let path = format!("/api/v2/projects/{id}");

    let (status, read) = call(&f.app, "GET", &path, ADMIN_PAT, None).await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(read["data"]["owner"]["id"], f.anika_id);

    // Another regular user cannot see it at all.
    let (status, _) = call(&f.app, "GET", &path, BEN_PAT, None).await;
    assert_eq!(status, StatusCode::NOT_FOUND);
    let (status, _) = call(
        &f.app,
        "PUT",
        &path,
        BEN_PAT,
        Some(serde_json::json!({"name":"hijack"})),
    )
    .await;
    assert_eq!(status, StatusCode::NOT_FOUND);
    let (status, _) = call(&f.app, "DELETE", &path, BEN_PAT, None).await;
    assert_eq!(status, StatusCode::NOT_FOUND);
    let (status, _) = call(&f.app, "PUT", &format!("{path}/favorite"), BEN_PAT, None).await;
    assert_eq!(status, StatusCode::NOT_FOUND);

    // The admin edits it; ownership and the owner's yarn link stay intact.
    let (status, updated) = call(
        &f.app,
        "PUT",
        &path,
        ADMIN_PAT,
        Some(serde_json::json!({
            "name":"anika shawl (fixed)",
            "category":"Shawls",
            "yarn_ids":[own_yarn],
            "steps":[{"title":"cast on","step_number":1}],
            "expected_updated_at": read["data"]["updated_at"],
        })),
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{updated}");
    assert_eq!(updated["data"]["name"], "anika shawl (fixed)");
    assert_eq!(updated["data"]["yarn_ids"], serde_json::json!([own_yarn]));
    assert_eq!(updated["data"]["owner"]["id"], f.anika_id);
    let owner: i64 = sqlx::query_scalar("SELECT owner_id FROM projects WHERE id = ?")
        .bind(id)
        .fetch_one(&f.pool)
        .await
        .unwrap();
    assert_eq!(owner, f.anika_id);
    // The category lands in the owner's categories, not the admin's.
    let category_owner: i64 = sqlx::query_scalar(
        "SELECT c.user_id FROM categories c JOIN projects p ON p.category_id = c.id WHERE p.id = ?",
    )
    .bind(id)
    .fetch_one(&f.pool)
    .await
    .unwrap();
    assert_eq!(category_owner, f.anika_id);

    // Audit: actor is the admin, the resource still belongs to Anika.
    let actor: i64 = sqlx::query_scalar(
        "SELECT actor_user_id FROM audit_events WHERE entity_type='project' AND entity_id=? \
         AND action='updated' ORDER BY id DESC LIMIT 1",
    )
    .bind(id)
    .fetch_one(&f.pool)
    .await
    .unwrap();
    assert_eq!(actor, f.admin_id);

    // Favourites are per user: the admin's favourite is not Anika's.
    let (status, fav) = call(&f.app, "PUT", &format!("{path}/favorite"), ADMIN_PAT, None).await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(fav["data"]["is_favorite"], true);
    let (_, anika_view) = call(&f.app, "GET", &path, ANIKA_PAT, None).await;
    assert_eq!(anika_view["data"]["is_favorite"], false);
    let (_, favourites) = call(
        &f.app,
        "GET",
        "/api/v2/projects?favorite=true",
        ADMIN_PAT,
        None,
    )
    .await;
    assert_eq!(names(&favourites), ["anika shawl (fixed)"]);

    // Anika sees the admin's change in her history with the admin as actor.
    let (status, history) = call(&f.app, "GET", &format!("{path}/history"), ADMIN_PAT, None).await;
    assert_eq!(status, StatusCode::OK, "{history}");

    // Delete: tombstone is written for the owner.
    let (status, _) = call(&f.app, "DELETE", &path, ADMIN_PAT, None).await;
    assert_eq!(status, StatusCode::NO_CONTENT);
    let tombstone: i64 = sqlx::query_scalar(
        "SELECT owner_id FROM deletion_events WHERE entity_type='project' AND entity_id=?",
    )
    .bind(id)
    .fetch_one(&f.pool)
    .await
    .unwrap();
    assert_eq!(tombstone, f.anika_id);
    let deleted_actor: i64 = sqlx::query_scalar(
        "SELECT actor_user_id FROM audit_events WHERE entity_type='project' AND entity_id=? \
         AND action='trashed'",
    )
    .bind(id)
    .fetch_one(&f.pool)
    .await
    .unwrap();
    assert_eq!(deleted_actor, f.admin_id);
}

#[tokio::test]
async fn admin_edits_foreign_yarn_and_projects_through_v1() {
    let f = fixture().await;
    let yarn_id = yarn(&f.pool, f.anika_id, "anika wool", "2026-01-02T00:00:00Z").await;
    let project_id = project(&f.pool, f.anika_id, "anika shawl", "2026-01-02T00:00:00Z").await;

    let (status, body) = call(
        &f.app,
        "PUT",
        &format!("/api/v1/yarns/{yarn_id}"),
        ADMIN_PAT,
        Some(serde_json::json!({"name":"anika wool 2"})),
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{body}");
    assert_eq!(body["owner"]["id"], f.anika_id);
    let (status, _) = call(
        &f.app,
        "PUT",
        &format!("/api/v1/yarns/{yarn_id}"),
        BEN_PAT,
        Some(serde_json::json!({"name":"mine now"})),
    )
    .await;
    assert_eq!(status, StatusCode::NOT_FOUND);

    let (status, body) = call(
        &f.app,
        "PUT",
        &format!("/api/v1/projects/{project_id}"),
        ADMIN_PAT,
        Some(serde_json::json!({"name":"anika shawl 2"})),
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{body}");
    let (status, _) = call(
        &f.app,
        "PUT",
        &format!("/api/v2/yarns/{yarn_id}/favorite"),
        ADMIN_PAT,
        None,
    )
    .await;
    assert_eq!(status, StatusCode::OK);
    let (status, _) = call(
        &f.app,
        "DELETE",
        &format!("/api/v1/yarns/{yarn_id}"),
        ADMIN_PAT,
        None,
    )
    .await;
    assert_eq!(status, StatusCode::NO_CONTENT);
    let (status, _) = call(
        &f.app,
        "DELETE",
        &format!("/api/v1/projects/{project_id}"),
        BEN_PAT,
        None,
    )
    .await;
    assert_eq!(status, StatusCode::NOT_FOUND);
    let tombstones: i64 = sqlx::query_scalar(
        "SELECT COUNT(*) FROM deletion_events WHERE owner_id = ? AND entity_type='yarn'",
    )
    .bind(f.anika_id)
    .fetch_one(&f.pool)
    .await
    .unwrap();
    assert_eq!(tombstones, 1);
}

#[tokio::test]
async fn demoted_or_inactive_administrators_lose_the_cross_owner_rights() {
    let f = fixture().await;
    let id = project(&f.pool, f.anika_id, "anika shawl", "2026-01-02T00:00:00Z").await;
    sqlx::query("UPDATE users SET is_admin = 0 WHERE id = ?")
        .bind(f.admin_id)
        .execute(&f.pool)
        .await
        .unwrap();
    let (status, _) = call(
        &f.app,
        "GET",
        &format!("/api/v2/projects/{id}"),
        ADMIN_PAT,
        None,
    )
    .await;
    assert_eq!(status, StatusCode::NOT_FOUND);
    let (_, list) = call(&f.app, "GET", "/api/v2/projects", ADMIN_PAT, None).await;
    assert!(names(&list).is_empty());
}

fn png_bytes() -> Vec<u8> {
    let image = image::DynamicImage::new_rgba8(2, 2);
    let mut output = Cursor::new(Vec::new());
    image
        .write_to(&mut output, image::ImageFormat::Png)
        .unwrap();
    output.into_inner()
}

async fn upload(app: &axum::Router, path: &str, pat: &str) -> (StatusCode, serde_json::Value) {
    let boundary = "scope-boundary";
    let mut body = format!(
        "--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"a.png\"\r\nContent-Type: image/png\r\n\r\n"
    )
    .into_bytes();
    body.extend_from_slice(&png_bytes());
    body.extend_from_slice(format!("\r\n--{boundary}--\r\n").as_bytes());
    let response = app
        .clone()
        .oneshot(
            Request::post(path)
                .header("authorization", format!("Bearer {pat}"))
                .header(
                    "content-type",
                    format!("multipart/form-data; boundary={boundary}"),
                )
                .body(Body::from(body))
                .unwrap(),
        )
        .await
        .unwrap();
    let status = response.status();
    let bytes = response.into_body().collect().await.unwrap().to_bytes();
    (status, serde_json::from_slice(&bytes).unwrap_or_default())
}

async fn fetch(app: &axum::Router, path: &str, pat: &str) -> StatusCode {
    app.clone()
        .oneshot(
            Request::get(path)
                .header("authorization", format!("Bearer {pat}"))
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap()
        .status()
}

#[tokio::test]
async fn admin_media_on_foreign_items_is_stored_under_the_owner_and_audited_to_the_admin() {
    let f = fixture().await;
    let yarn_id = yarn(&f.pool, f.anika_id, "anika wool", "2026-01-02T00:00:00Z").await;
    let project_id = project(&f.pool, f.anika_id, "anika shawl", "2026-01-02T00:00:00Z").await;

    let (status, _) = upload(&f.app, &format!("/api/v2/yarns/{yarn_id}/photos"), BEN_PAT).await;
    assert_eq!(status, StatusCode::NOT_FOUND);

    let (status, photo) = upload(
        &f.app,
        &format!("/api/v2/yarns/{yarn_id}/photos"),
        ADMIN_PAT,
    )
    .await;
    assert_eq!(status, StatusCode::CREATED, "{photo}");
    let (status, title) = upload(
        &f.app,
        &format!("/api/v2/projects/{project_id}/images/title"),
        ADMIN_PAT,
    )
    .await;
    assert_eq!(status, StatusCode::CREATED, "{title}");
    let owners: Vec<i64> = sqlx::query_scalar("SELECT DISTINCT owner_id FROM media_files")
        .fetch_all(&f.pool)
        .await
        .unwrap();
    assert_eq!(owners, [f.anika_id]);
    let actors: Vec<i64> = sqlx::query_scalar(
        "SELECT actor_user_id FROM audit_events WHERE action='uploaded' ORDER BY id",
    )
    .fetch_all(&f.pool)
    .await
    .unwrap();
    assert_eq!(actors, [f.admin_id, f.admin_id]);

    // The normal media URLs serve to the owner and to admins, not to others.
    let url = photo["data"]["url"].as_str().unwrap();
    assert_eq!(fetch(&f.app, url, ANIKA_PAT).await, StatusCode::OK);
    assert_eq!(fetch(&f.app, url, ADMIN_PAT).await, StatusCode::OK);
    assert_eq!(fetch(&f.app, url, BEN_PAT).await, StatusCode::FORBIDDEN);

    // The detail view of the foreign yarn lists the photo for the admin.
    let (_, detail) = call(
        &f.app,
        "GET",
        &format!("/api/v2/yarns/{yarn_id}"),
        ADMIN_PAT,
        None,
    )
    .await;
    assert_eq!(detail["data"]["photos"].as_array().unwrap().len(), 1);

    // Delete as admin.
    let photo_id = photo["data"]["id"].as_i64().unwrap();
    let (status, _) = call(
        &f.app,
        "DELETE",
        &format!("/api/v2/yarns/{yarn_id}/photos/{photo_id}"),
        BEN_PAT,
        None,
    )
    .await;
    assert_eq!(status, StatusCode::NOT_FOUND);
    let (status, _) = call(
        &f.app,
        "DELETE",
        &format!("/api/v2/yarns/{yarn_id}/photos/{photo_id}"),
        ADMIN_PAT,
        None,
    )
    .await;
    assert_eq!(status, StatusCode::NO_CONTENT);
    let removed: i64 = sqlx::query_scalar(
        "SELECT actor_user_id FROM audit_events WHERE action='trashed' AND entity_type='yarn'",
    )
    .fetch_one(&f.pool)
    .await
    .unwrap();
    assert_eq!(removed, f.admin_id);
}

#[tokio::test]
async fn cookie_session_admin_needs_csrf_for_foreign_edits() {
    let f = fixture().await;
    let id = project(&f.pool, f.anika_id, "anika shawl", "2026-01-02T00:00:00Z").await;
    // Promote a password user so the real login flow can be used.
    let user = db::create_user(
        &f.pool,
        "cookie-admin@example.test",
        "cookie-admin-pass".to_owned(),
    )
    .await
    .unwrap();
    sqlx::query("UPDATE users SET is_admin = 1 WHERE id = ?")
        .bind(user)
        .execute(&f.pool)
        .await
        .unwrap();
    let boot = f
        .app
        .clone()
        .oneshot(Request::get("/api/v2/session").body(Body::empty()).unwrap())
        .await
        .unwrap();
    let cookie_of = |response: &axum::response::Response, prefix: &str| -> String {
        response
            .headers()
            .get_all("set-cookie")
            .iter()
            .filter_map(|v| v.to_str().ok())
            .find_map(|v| v.strip_prefix(prefix))
            .and_then(|v| v.split(';').next())
            .unwrap()
            .to_owned()
    };
    let pre = cookie_of(&boot, "__Host-stricknani_v2_login_csrf=");
    let pre_csrf = {
        let bytes = boot.into_body().collect().await.unwrap().to_bytes();
        serde_json::from_slice::<serde_json::Value>(&bytes).unwrap()["data"]["csrf_token"]
            .as_str()
            .unwrap()
            .to_owned()
    };
    let login = f
        .app
        .clone()
        .oneshot(
            Request::post("/api/v2/auth/login")
                .header("host", HOST)
                .header("origin", ORIGIN)
                .header("sec-fetch-site", "same-origin")
                .header("cookie", format!("__Host-stricknani_v2_login_csrf={pre}"))
                .header("x-csrf-token", pre_csrf)
                .header("content-type", "application/json")
                .body(Body::from(
                    serde_json::json!({"email":"cookie-admin@example.test","password":"cookie-admin-pass"})
                        .to_string(),
                ))
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(login.status(), StatusCode::OK);
    let session = cookie_of(&login, "__Host-stricknani_session=");
    let csrf = {
        let bytes = login.into_body().collect().await.unwrap().to_bytes();
        serde_json::from_slice::<serde_json::Value>(&bytes).unwrap()["data"]["csrf_token"]
            .as_str()
            .unwrap()
            .to_owned()
    };
    let edit = |csrf: Option<&str>| {
        let mut request = Request::put(format!("/api/v2/projects/{id}"))
            .header("host", HOST)
            .header("origin", ORIGIN)
            .header("sec-fetch-site", "same-origin")
            .header("cookie", format!("__Host-stricknani_session={session}"))
            .header("content-type", "application/json");
        if let Some(csrf) = csrf {
            request = request.header("x-csrf-token", csrf);
        }
        request
            .body(Body::from(r#"{"name":"edited by cookie admin"}"#))
            .unwrap()
    };
    let denied = f.app.clone().oneshot(edit(None)).await.unwrap();
    assert_eq!(denied.status(), StatusCode::FORBIDDEN);
    let ok = f.app.clone().oneshot(edit(Some(&csrf))).await.unwrap();
    assert_eq!(ok.status(), StatusCode::OK);
    let name: String = sqlx::query_scalar("SELECT name FROM projects WHERE id = ?")
        .bind(id)
        .fetch_one(&f.pool)
        .await
        .unwrap();
    assert_eq!(name, "edited by cookie admin");
}

// ---------------------------------------------------------------- sync scope

fn ids(value: &serde_json::Value, key: &str) -> Vec<i64> {
    let mut ids: Vec<i64> = value["data"][key]
        .as_array()
        .unwrap()
        .iter()
        .map(|item| item.as_i64().or_else(|| item["id"].as_i64()).unwrap())
        .collect();
    ids.sort_unstable();
    ids
}

#[tokio::test]
async fn sync_includes_other_users_only_on_opt_in_and_cursors_are_scoped() {
    let f = fixture().await;
    let mine = project(&f.pool, f.admin_id, "admin hat", "2026-01-01T00:00:00Z").await;
    let a1 = project(&f.pool, f.anika_id, "a1", "2026-01-02T00:00:00Z").await;
    let a2 = project(&f.pool, f.anika_id, "a2", "2026-01-03T00:00:00Z").await;
    let y1 = yarn(&f.pool, f.anika_id, "y1", "2026-01-02T00:00:00Z").await;

    // Default and explicit mine: only the admin's own item.
    let (_, default) = call(&f.app, "GET", "/api/v2/sync/projects", ADMIN_PAT, None).await;
    assert_eq!(ids(&default, "updated"), [mine]);
    assert_eq!(default["data"]["scope"], "mine");
    let (_, yarns_mine) = call(&f.app, "GET", "/api/v2/sync/yarns", ADMIN_PAT, None).await;
    assert!(ids(&yarns_mine, "updated").is_empty());

    // Opt in: everything, with owner info.
    let (_, all) = call(
        &f.app,
        "GET",
        "/api/v2/sync/projects?scope=all",
        ADMIN_PAT,
        None,
    )
    .await;
    assert_eq!(ids(&all, "updated"), [mine, a1, a2]);
    assert_eq!(all["data"]["scope"], "all");
    let item = all["data"]["updated"]
        .as_array()
        .unwrap()
        .iter()
        .find(|item| item["id"] == a1)
        .unwrap();
    assert_eq!(item["owner"]["id"], f.anika_id);
    let (_, yarns_all) = call(
        &f.app,
        "GET",
        "/api/v2/sync/yarns?scope=all",
        ADMIN_PAT,
        None,
    )
    .await;
    assert_eq!(ids(&yarns_all, "updated"), [y1]);

    // Non-admins are served `mine` whatever they ask for.
    let (_, ben) = call(
        &f.app,
        "GET",
        "/api/v2/sync/projects?scope=all",
        BEN_PAT,
        None,
    )
    .await;
    assert!(ids(&ben, "updated").is_empty());
    assert_eq!(ben["data"]["scope"], "mine");
    let (status, _) = call(
        &f.app,
        "GET",
        "/api/v2/sync/projects?scope=zzz",
        ADMIN_PAT,
        None,
    )
    .await;
    assert_eq!(status, StatusCode::BAD_REQUEST);

    // A paged all-scope walk uses a cursor that is bound to its scope.
    let (_, page1) = call(
        &f.app,
        "GET",
        "/api/v2/sync/projects?scope=all&limit=2",
        ADMIN_PAT,
        None,
    )
    .await;
    assert_eq!(page1["data"]["has_more"], true);
    let cursor = page1["data"]["next_cursor"].as_str().unwrap().to_owned();
    let (status, page2) = call(
        &f.app,
        "GET",
        &format!("/api/v2/sync/projects?scope=all&cursor={cursor}"),
        ADMIN_PAT,
        None,
    )
    .await;
    assert_eq!(status, StatusCode::OK);
    let mut walked = ids(&page1, "updated");
    walked.extend(ids(&page2, "updated"));
    walked.sort_unstable();
    assert_eq!(walked, [mine, a1, a2]);

    // Reusing that cursor under another scope is refused and tells the client to restart.
    for path in [
        format!("/api/v2/sync/projects?cursor={cursor}"),
        format!("/api/v2/sync/projects?scope=mine&cursor={cursor}"),
    ] {
        let (status, body) = call(&f.app, "GET", &path, ADMIN_PAT, None).await;
        assert_eq!(status, StatusCode::CONFLICT);
        assert_eq!(body["error"]["code"], "scope_mismatch");
    }
    // ... and the reverse (a mine cursor under all).
    let (_, mine_page) = call(
        &f.app,
        "GET",
        "/api/v2/sync/projects?limit=1",
        ADMIN_PAT,
        None,
    )
    .await;
    // Only one own project: no cursor is issued, so exercise a demotion instead:
    assert!(mine_page["data"]["next_cursor"].is_null());
    sqlx::query("UPDATE users SET is_admin = 0 WHERE id = ?")
        .bind(f.admin_id)
        .execute(&f.pool)
        .await
        .unwrap();
    let (status, body) = call(
        &f.app,
        "GET",
        &format!("/api/v2/sync/projects?scope=all&cursor={cursor}"),
        ADMIN_PAT,
        None,
    )
    .await;
    assert_eq!(
        status,
        StatusCode::CONFLICT,
        "demoted admin must restart: {body}"
    );
}

#[tokio::test]
async fn sync_all_scope_delivers_tombstones_of_foreign_deletions_made_by_the_admin() {
    let f = fixture().await;
    let gone = project(&f.pool, f.anika_id, "gone", "2026-01-02T00:00:00Z").await;
    let kept = project(&f.pool, f.anika_id, "kept", "2026-01-02T00:00:00Z").await;
    let (_, first) = call(
        &f.app,
        "GET",
        "/api/v2/sync/projects?scope=all",
        ADMIN_PAT,
        None,
    )
    .await;
    assert_eq!(ids(&first, "updated"), [gone, kept]);
    let since = first["data"]["server_time"].as_str().unwrap().to_owned();

    // Owner deletes one; the admin deletes another of Anika's after the snapshot.
    let (status, _) = call(
        &f.app,
        "DELETE",
        &format!("/api/v2/projects/{gone}"),
        ANIKA_PAT,
        None,
    )
    .await;
    assert_eq!(status, StatusCode::NO_CONTENT);
    let (_, mid) = call(
        &f.app,
        "GET",
        &format!("/api/v2/sync/projects?scope=all&since={since}"),
        ADMIN_PAT,
        None,
    )
    .await;
    assert_eq!(ids(&mid, "deleted_ids"), [gone]);
    let since = mid["data"]["server_time"].as_str().unwrap().to_owned();
    let (status, _) = call(
        &f.app,
        "DELETE",
        &format!("/api/v2/projects/{kept}"),
        ADMIN_PAT,
        None,
    )
    .await;
    assert_eq!(status, StatusCode::NO_CONTENT);
    let (_, last) = call(
        &f.app,
        "GET",
        &format!("/api/v2/sync/projects?scope=all&since={since}"),
        ADMIN_PAT,
        None,
    )
    .await;
    assert_eq!(ids(&last, "deleted_ids"), [kept]);
    // The same window under `mine` carries no foreign tombstones.
    let (_, mine) = call(
        &f.app,
        "GET",
        &format!("/api/v2/sync/projects?since={since}"),
        ADMIN_PAT,
        None,
    )
    .await;
    assert!(ids(&mine, "deleted_ids").is_empty());
    // Anika's own sync still sees both tombstones.
    let (_, anika) = call(
        &f.app,
        "GET",
        "/api/v2/sync/projects?since=2000-01-01T00:00:00Z",
        ANIKA_PAT,
        None,
    )
    .await;
    assert_eq!(ids(&anika, "deleted_ids"), [gone, kept]);
}
