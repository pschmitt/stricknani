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

const OWNER_PAT: &str = "activity-owner-api-pat";
const OTHER_PAT: &str = "activity-other-api-pat";
const ADMIN_PAT: &str = "activity-admin-api-pat";
const OWNER_SESSION: &str = "activity-owner-browser-session";
const INVALID_PAT: &str = "activity-invalid-pat";

struct Fixture {
    app: Router,
    pool: SqlitePool,
    owner_id: i64,
    other_id: i64,
    admin_id: i64,
}

async fn fixture() -> Fixture {
    let pool = db::connect("sqlite::memory:")
        .await
        .expect("database migrates");
    let owner_id = create_user(&pool, "activity-owner@example.test").await;
    let other_id = create_user(&pool, "activity-other@example.test").await;
    let admin_id = create_user(&pool, "activity-admin@example.test").await;
    sqlx::query("UPDATE users SET is_admin = 1 WHERE id = ?")
        .bind(admin_id)
        .execute(&pool)
        .await
        .unwrap();
    add_token(&pool, owner_id, OWNER_PAT, "api").await;
    add_token(&pool, owner_id, OWNER_SESSION, "browser").await;
    add_token(&pool, other_id, OTHER_PAT, "api").await;
    add_token(&pool, admin_id, ADMIN_PAT, "api").await;
    let app = api_v2::router(
        db::AppState::new(pool.clone()).with_secure_cookies(true),
        false,
    );
    Fixture {
        app,
        pool,
        owner_id,
        other_id,
        admin_id,
    }
}

async fn create_user(pool: &SqlitePool, email: &str) -> i64 {
    db::create_user(pool, email, "activity-test-password".into())
        .await
        .expect("test user is created")
}

async fn add_token(pool: &SqlitePool, user_id: i64, token: &str, purpose: &str) {
    sqlx::query(
        "INSERT INTO api_tokens (user_id, name, token_hash, created_at, expires_at, purpose) \
         VALUES (?, 'activity test token', ?, unixepoch(), unixepoch() + 86400, ?)",
    )
    .bind(user_id)
    .bind(hex::encode(Sha256::digest(token.as_bytes())))
    .bind(purpose)
    .execute(pool)
    .await
    .unwrap();
}

async fn project(pool: &SqlitePool, owner_id: i64, name: &str) -> i64 {
    sqlx::query_scalar(
        "INSERT INTO projects(owner_id, name, created_at, updated_at) \
         VALUES (?, ?, '2026-01-01T00:00:00Z', '2026-01-01T00:00:00Z') RETURNING id",
    )
    .bind(owner_id)
    .bind(name)
    .fetch_one(pool)
    .await
    .unwrap()
}

async fn yarn(pool: &SqlitePool, owner_id: i64, name: &str) -> i64 {
    sqlx::query_scalar(
        "INSERT INTO yarns(owner_id, name, created_at, updated_at) \
         VALUES (?, ?, '2026-01-01T00:00:00Z', '2026-01-01T00:00:00Z') RETURNING id",
    )
    .bind(owner_id)
    .bind(name)
    .fetch_one(pool)
    .await
    .unwrap()
}

struct EventInput<'a> {
    actor_id: Option<i64>,
    entity_type: &'a str,
    entity_id: i64,
    action: &'a str,
    created_at: &'a str,
    details: Option<&'a str>,
    safe_changes: Option<&'a str>,
}

async fn event(pool: &SqlitePool, input: EventInput<'_>) -> i64 {
    sqlx::query_scalar(
        "INSERT INTO audit_events \
         (actor_user_id, entity_type, entity_id, action, created_at, details, change_version, safe_changes) \
         VALUES (?, ?, ?, ?, ?, ?, CASE WHEN ? IS NULL THEN NULL ELSE 1 END, ?) RETURNING id",
    )
    .bind(input.actor_id)
    .bind(input.entity_type)
    .bind(input.entity_id)
    .bind(input.action)
    .bind(input.created_at)
    .bind(input.details)
    .bind(input.safe_changes)
    .bind(input.safe_changes)
    .fetch_one(pool)
    .await
    .unwrap()
}

fn bearer(path: &str, token: &str) -> Request<Body> {
    Request::get(path)
        .header("authorization", format!("Bearer {token}"))
        .body(Body::empty())
        .unwrap()
}

fn cookie(path: &str, token: &str) -> Request<Body> {
    Request::get(path)
        .header("cookie", format!("__Host-stricknani_session={token}"))
        .body(Body::empty())
        .unwrap()
}

async fn json(response: axum::response::Response) -> serde_json::Value {
    serde_json::from_slice(&response.into_body().collect().await.unwrap().to_bytes()).unwrap()
}

async fn get(f: &Fixture, path: &str, token: &str) -> (StatusCode, serde_json::Value) {
    let response = f.app.clone().oneshot(bearer(path, token)).await.unwrap();
    let status = response.status();
    (status, json(response).await)
}

async fn ev(f: &Fixture, entity: &str, id: i64, action: &str, at: &str, actor: Option<i64>) -> i64 {
    event(
        &f.pool,
        EventInput {
            actor_id: actor,
            entity_type: entity,
            entity_id: id,
            action,
            created_at: at,
            details: None,
            safe_changes: None,
        },
    )
    .await
}

#[tokio::test]
async fn activity_requires_authentication_and_accepts_pat_and_session() {
    let f = fixture().await;
    let response = f
        .app
        .clone()
        .oneshot(Request::get("/activity").body(Body::empty()).unwrap())
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::UNAUTHORIZED);
    let (status, _) = get(&f, "/activity", INVALID_PAT).await;
    assert_eq!(status, StatusCode::UNAUTHORIZED);
    let (status, body) = get(&f, "/activity", OWNER_PAT).await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(body["data"].as_array().unwrap().len(), 0);
    assert_eq!(body["meta"]["limit"], 10);
    assert_eq!(body["meta"]["has_more"], false);
    let response = f
        .app
        .clone()
        .oneshot(cookie("/activity", OWNER_SESSION))
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    assert_eq!(response.headers()["cache-control"], "no-store");
}

#[tokio::test]
async fn activity_shape_name_thumbnail_and_changed_fields() {
    let f = fixture().await;
    let pid = project(&f.pool, f.owner_id, "Current name").await;
    sqlx::query(
        "INSERT INTO media_files(owner_id, project_id, kind, public_id, filename, \
         original_filename, content_type, size_bytes, is_primary) \
         VALUES (?, ?, 'title', 42, 'cover.jpg', 'cover.jpg', 'image/jpeg', 1, 1)",
    )
    .bind(f.owner_id)
    .bind(pid)
    .execute(&f.pool)
    .await
    .unwrap();
    let id = event(
        &f.pool,
        EventInput {
            actor_id: Some(f.owner_id),
            entity_type: "project",
            entity_id: pid,
            action: "updated",
            created_at: "2026-04-01T10:00:00.000000Z",
            details: None,
            safe_changes: Some(
                r#"{"version":1,"items":[{"field":"project.name","before":{"type":"text","value":"A"},"after":{"type":"text","value":"B"}}]}"#,
            ),
        },
    )
    .await;
    let (status, body) = get(&f, "/activity", OWNER_PAT).await;
    assert_eq!(status, StatusCode::OK);
    let item = &body["data"][0];
    assert_eq!(item["id"], id);
    assert_eq!(item["created_at"], "2026-04-01T10:00:00.000000Z");
    assert_eq!(item["action"], "updated");
    assert_eq!(item["entity_type"], "project");
    assert_eq!(item["entity_id"], pid);
    assert_eq!(item["entity_name"], "Current name");
    assert_eq!(item["exists"], true);
    assert_eq!(item["count"], 1);
    assert_eq!(item["href"], format!("/app/projects/{pid}"));
    assert_eq!(
        item["thumbnail_url"],
        format!("/media/thumbnails/projects/{pid}/thumb_cover.jpg")
    );
    assert_eq!(item["actor"]["is_self"], true);
    assert_eq!(item["actor"]["name"], "Activity Owner");
    assert_eq!(item["actor"]["kind"], "self");
    assert_eq!(item["changed_fields"], serde_json::json!(["project.name"]));
}

#[tokio::test]
async fn trashed_items_keep_their_thumbnail_for_the_owner_and_administrators_only() {
    let f = fixture().await;
    let pid = project(&f.pool, f.owner_id, "Binned").await;
    sqlx::query(
        "INSERT INTO media_files(owner_id, project_id, kind, public_id, filename, \
         original_filename, content_type, size_bytes, is_primary) \
         VALUES (?, ?, 'title', 43, 'binned.jpg', 'binned.jpg', 'image/jpeg', 1, 1)",
    )
    .bind(f.owner_id)
    .bind(pid)
    .execute(&f.pool)
    .await
    .unwrap();
    share(&f.pool, "project", pid, f.other_id, "viewer").await;
    ev(
        &f,
        "project",
        pid,
        "updated",
        "2026-04-01T00:00:00Z",
        Some(f.other_id),
    )
    .await;
    ev(
        &f,
        "project",
        pid,
        "updated",
        "2026-04-01T00:01:00Z",
        Some(f.admin_id),
    )
    .await;
    ev(
        &f,
        "project",
        pid,
        "trashed",
        "2026-04-01T00:02:00Z",
        Some(f.owner_id),
    )
    .await;
    sqlx::query("UPDATE projects SET deleted_at = '2026-04-01T00:02:00Z' WHERE id = ?")
        .bind(pid)
        .execute(&f.pool)
        .await
        .unwrap();
    let thumbnail = format!("/media/thumbnails/projects/{pid}/thumb_binned.jpg");

    let (_, owner) = get(&f, "/activity", OWNER_PAT).await;
    let owner_item = &owner["data"][0];
    assert_eq!(owner_item["action"], "trashed");
    assert_eq!(owner_item["state"], "in_trash");
    assert_eq!(owner_item["thumbnail_url"], thumbnail);

    let (_, admin) = get(&f, "/activity", ADMIN_PAT).await;
    let admin_items = admin["data"].as_array().unwrap();
    assert!(!admin_items.is_empty());
    for item in admin_items.iter().filter(|item| item["entity_id"] == pid) {
        assert_eq!(item["thumbnail_url"], thumbnail);
    }

    let (_, other) = get(&f, "/activity", OTHER_PAT).await;
    for item in other["data"].as_array().unwrap() {
        assert_eq!(item["thumbnail_url"], serde_json::Value::Null);
    }
}

#[tokio::test]
async fn activity_is_owner_scoped() {
    let f = fixture().await;
    let own = project(&f.pool, f.owner_id, "Mine").await;
    let own_yarn = yarn(&f.pool, f.owner_id, "My yarn").await;
    let foreign = project(&f.pool, f.other_id, "Theirs").await;
    let foreign_yarn = yarn(&f.pool, f.other_id, "Their yarn").await;
    ev(
        &f,
        "project",
        own,
        "created",
        "2026-04-01T00:00:00Z",
        Some(f.owner_id),
    )
    .await;
    ev(
        &f,
        "yarn",
        own_yarn,
        "created",
        "2026-04-01T00:01:00Z",
        Some(f.owner_id),
    )
    .await;
    ev(
        &f,
        "project",
        foreign,
        "created",
        "2026-04-01T00:02:00Z",
        Some(f.other_id),
    )
    .await;
    ev(
        &f,
        "yarn",
        foreign_yarn,
        "updated",
        "2026-04-01T00:03:00Z",
        Some(f.other_id),
    )
    .await;
    // A foreign user's deleted resource must stay invisible too.
    ev(
        &f,
        "project",
        9999,
        "deleted",
        "2026-04-01T00:04:00Z",
        Some(f.other_id),
    )
    .await;
    // Admin acting on the owner's resource is visible but anonymous.
    ev(
        &f,
        "project",
        own,
        "updated",
        "2026-04-01T00:05:00Z",
        Some(f.admin_id),
    )
    .await;
    ev(
        &f,
        "category",
        1,
        "created",
        "2026-04-01T00:06:00Z",
        Some(f.owner_id),
    )
    .await;

    let (_, body) = get(&f, "/activity", OWNER_PAT).await;
    let data = body["data"].as_array().unwrap();
    assert_eq!(data.len(), 3);
    assert_eq!(data[0]["actor"]["is_self"], false);
    assert_eq!(data[0]["actor"]["kind"], "admin");
    assert_eq!(data[0]["actor"]["name"], serde_json::Value::Null);
    assert_eq!(data[0]["actor"]["avatar_url"], serde_json::Value::Null);
    let text = serde_json::to_string(&body).unwrap();
    assert!(!text.contains("Theirs") && !text.contains("Their yarn"));
    assert!(!text.contains("example.test"));
    let (_, other) = get(&f, "/activity", OTHER_PAT).await;
    assert_eq!(other["data"].as_array().unwrap().len(), 3);
    // The administrator's own edit of the member's project is in their feed.
    let (_, admin) = get(&f, "/activity", ADMIN_PAT).await;
    let admin_items = admin["data"].as_array().unwrap();
    assert_eq!(admin_items.len(), 1);
    assert_eq!(admin_items[0]["entity_id"], own);
    assert_eq!(admin_items[0]["actor"]["kind"], "self");
}

#[tokio::test]
async fn activity_orders_newest_first_limits_and_pages_with_before() {
    let f = fixture().await;
    let mut ids = Vec::new();
    for i in 0..5 {
        let pid = project(&f.pool, f.owner_id, &format!("P{i}")).await;
        ids.push(
            ev(
                &f,
                "project",
                pid,
                "created",
                &format!("2026-04-01T00:0{i}:00Z"),
                Some(f.owner_id),
            )
            .await,
        );
    }
    let (_, body) = get(&f, "/activity?limit=2", OWNER_PAT).await;
    let got: Vec<i64> = body["data"]
        .as_array()
        .unwrap()
        .iter()
        .map(|e| e["id"].as_i64().unwrap())
        .collect();
    assert_eq!(got, vec![ids[4], ids[3]]);
    assert_eq!(body["meta"]["has_more"], true);
    assert_eq!(body["meta"]["next_before"], ids[3]);
    let (_, body) = get(
        &f,
        &format!("/activity?limit=2&before={}", ids[3]),
        OWNER_PAT,
    )
    .await;
    let got: Vec<i64> = body["data"]
        .as_array()
        .unwrap()
        .iter()
        .map(|e| e["id"].as_i64().unwrap())
        .collect();
    assert_eq!(got, vec![ids[2], ids[1]]);
    let (_, body) = get(
        &f,
        &format!("/activity?limit=2&before={}", ids[1]),
        OWNER_PAT,
    )
    .await;
    assert_eq!(body["data"].as_array().unwrap().len(), 1);
    assert_eq!(body["data"][0]["id"], ids[0]);
    assert_eq!(body["meta"]["has_more"], false);
    assert_eq!(body["meta"]["next_before"], serde_json::Value::Null);

    for query in [
        "?limit=0",
        "?limit=51",
        "?limit=x",
        "?before=0",
        "?before=x",
        "?foo=1",
        "?limit=1&limit=2",
    ] {
        let (status, body) = get(&f, &format!("/activity{query}"), OWNER_PAT).await;
        assert_eq!(status, StatusCode::BAD_REQUEST, "{query}");
        assert_eq!(body["error"]["code"], "invalid_query");
    }
    let (status, _) = get(&f, "/activity?limit=50", OWNER_PAT).await;
    assert_eq!(status, StatusCode::OK);
}

#[tokio::test]
async fn activity_coalesces_autosaves_within_the_window_only() {
    let f = fixture().await;
    let pid = project(&f.pool, f.owner_id, "Autosaved").await;
    let other = project(&f.pool, f.owner_id, "Other").await;
    let owner = Some(f.owner_id);
    let first = ev(&f, "project", pid, "updated", "2026-04-01T09:00:00Z", owner).await;
    // Gap larger than ten minutes: a separate item.
    let a = ev(&f, "project", pid, "updated", "2026-04-01T10:00:00Z", owner).await;
    ev(&f, "project", pid, "updated", "2026-04-01T10:04:00Z", owner).await;
    let last = ev(&f, "project", pid, "updated", "2026-04-01T10:09:00Z", owner).await;
    // Different actor is not folded in.
    let admin_edit = ev(
        &f,
        "project",
        pid,
        "updated",
        "2026-04-01T10:10:00Z",
        Some(f.admin_id),
    )
    .await;
    let b = ev(&f, "project", pid, "updated", "2026-04-01T10:11:00Z", owner).await;
    // A different action breaks the run.
    let up = ev(
        &f,
        "project",
        pid,
        "uploaded",
        "2026-04-01T10:12:00Z",
        owner,
    )
    .await;
    // A different resource breaks adjacency.
    let o = ev(
        &f,
        "project",
        other,
        "created",
        "2026-04-01T10:13:00Z",
        owner,
    )
    .await;

    let (_, body) = get(&f, "/activity", OWNER_PAT).await;
    let data = body["data"].as_array().unwrap();
    let summary: Vec<(i64, i64, i64)> = data
        .iter()
        .map(|e| {
            (
                e["id"].as_i64().unwrap(),
                e["first_event_id"].as_i64().unwrap(),
                e["count"].as_i64().unwrap(),
            )
        })
        .collect();
    assert_eq!(
        summary,
        vec![
            (o, o, 1),
            (up, up, 1),
            (b, b, 1),
            (admin_edit, admin_edit, 1),
            (last, a, 3),
            (first, first, 1),
        ]
    );
    assert_eq!(data[4]["first_created_at"], "2026-04-01T10:00:00Z");

    // A page boundary never splits a run.
    let (_, body) = get(&f, "/activity?limit=5", OWNER_PAT).await;
    assert_eq!(body["data"][4]["count"], 3);
    assert_eq!(body["meta"]["next_before"], a);
    let (_, body) = get(&f, &format!("/activity?limit=5&before={a}"), OWNER_PAT).await;
    assert_eq!(body["data"][0]["id"], first);
}

#[tokio::test]
async fn activity_keeps_deleted_resources_for_their_actor_only() {
    let f = fixture().await;
    let pid = project(&f.pool, f.owner_id, "Doomed").await;
    ev(
        &f,
        "project",
        pid,
        "created",
        "2026-04-01T00:00:00Z",
        Some(f.owner_id),
    )
    .await;
    event(
        &f.pool,
        EventInput {
            actor_id: Some(f.owner_id),
            entity_type: "project",
            entity_id: pid,
            action: "deleted",
            created_at: "2026-04-01T00:01:00Z",
            details: None,
            safe_changes: None,
        },
    )
    .await;
    sqlx::query("UPDATE audit_events SET resource_name_snapshot = 'Doomed' WHERE entity_id = ?")
        .bind(pid)
        .execute(&f.pool)
        .await
        .unwrap();
    sqlx::query("DELETE FROM projects WHERE id = ?")
        .bind(pid)
        .execute(&f.pool)
        .await
        .unwrap();
    let (_, body) = get(&f, "/activity", OWNER_PAT).await;
    let data = body["data"].as_array().unwrap();
    assert_eq!(data.len(), 2);
    assert_eq!(data[0]["action"], "deleted");
    assert_eq!(data[0]["entity_name"], "Doomed");
    assert_eq!(data[0]["exists"], false);
    assert_eq!(data[0]["thumbnail_url"], serde_json::Value::Null);
    assert_eq!(data[0]["href"], serde_json::Value::Null);
    let (_, other) = get(&f, "/activity", OTHER_PAT).await;
    assert_eq!(other["data"].as_array().unwrap().len(), 0);
    let (_, admin) = get(&f, "/activity", ADMIN_PAT).await;
    assert_eq!(admin["data"].as_array().unwrap().len(), 0);
}

#[tokio::test]
async fn my_activity_page_works_for_a_non_admin_member_and_never_exposes_client_info() {
    let f = fixture().await;
    let own = project(&f.pool, f.owner_id, "Mine").await;
    let foreign = project(&f.pool, f.other_id, "Theirs").await;
    for minute in 0..25 {
        let at = format!("2026-06-01T00:{minute:02}:00Z");
        ev(&f, "project", own, "updated", &at, Some(f.owner_id)).await;
        // Far enough apart that autosave coalescing keeps them separate.
        sqlx::query(
            "UPDATE audit_events SET created_at = ? WHERE id = (SELECT max(id) FROM audit_events)",
        )
        .bind(format!("2026-06-{:02}T00:00:00Z", minute + 1))
        .execute(&f.pool)
        .await
        .unwrap();
    }
    ev(
        &f,
        "project",
        foreign,
        "created",
        "2026-07-01T00:00:00Z",
        Some(f.other_id),
    )
    .await;
    // The audit table does hold client info; the feed must not carry it.
    sqlx::query(
        "UPDATE audit_events SET client_kind = 'web', client_label = 'Firefox 1 · Linux', \
         client_user_agent = 'Mozilla/5.0 secret-agent'",
    )
    .execute(&f.pool)
    .await
    .unwrap();

    // OWNER_PAT and OWNER_SESSION belong to a member, not an administrator.
    let is_admin: i64 = sqlx::query_scalar("SELECT is_admin FROM users WHERE id = ?")
        .bind(f.owner_id)
        .fetch_one(&f.pool)
        .await
        .unwrap();
    assert_eq!(is_admin, 0);

    let session = f
        .app
        .clone()
        .oneshot(cookie("/activity?limit=20", OWNER_SESSION))
        .await
        .unwrap();
    assert_eq!(session.status(), StatusCode::OK);
    let first = json(session).await;
    assert_eq!(first["data"].as_array().unwrap().len(), 20);
    assert_eq!(first["meta"]["has_more"], true);
    let before = first["meta"]["next_before"].as_i64().unwrap();
    let (status, second) = get(
        &f,
        &format!("/activity?limit=20&before={before}"),
        OWNER_PAT,
    )
    .await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(second["data"].as_array().unwrap().len(), 5);
    assert_eq!(second["meta"]["has_more"], false);

    for body in [&first, &second] {
        let text = serde_json::to_string(body).unwrap();
        assert!(!text.contains("client"), "no client field: {text}");
        assert!(!text.contains("secret-agent") && !text.contains("Firefox"));
        assert!(!text.contains("Theirs"));
        assert!(!text.contains("example.test"));
        for item in body["data"].as_array().unwrap() {
            assert_eq!(item["entity_id"], own);
        }
    }
}

async fn share(pool: &SqlitePool, kind: &str, id: i64, user_id: i64, role: &str) {
    sqlx::query(
        "INSERT INTO resource_shares (kind, resource_id, user_id, role, created_at, updated_at) \
         VALUES (?, ?, ?, ?, '2026-01-01T00:00:00Z', '2026-01-01T00:00:00Z')",
    )
    .bind(kind)
    .bind(id)
    .bind(user_id)
    .bind(role)
    .execute(pool)
    .await
    .unwrap();
}

#[tokio::test]
async fn timer_events_on_a_shared_project_reach_the_owner_and_the_sharer_only() {
    let f = fixture().await;
    let pid = project(&f.pool, f.owner_id, "Shared hat").await;
    share(&f.pool, "project", pid, f.other_id, "editor").await;
    let started = ev(
        &f,
        "project",
        pid,
        "timer_started",
        "2026-05-01T10:00:00Z",
        Some(f.other_id),
    )
    .await;
    let (_, owner) = get(&f, "/activity", OWNER_PAT).await;
    let item = &owner["data"][0];
    assert_eq!(item["id"], started);
    assert_eq!(item["action"], "timer_started");
    assert_eq!(item["actor"]["kind"], "user");
    assert_eq!(item["actor"]["name"], "Activity Other");
    assert_eq!(item["actor"]["is_self"], false);
    assert_eq!(item["href"], format!("/app/projects/{pid}"));
    let (_, sharer) = get(&f, "/activity", OTHER_PAT).await;
    let item = &sharer["data"][0];
    assert_eq!(item["id"], started);
    assert_eq!(item["actor"]["kind"], "self");
    assert_eq!(item["actor"]["name"], "Activity Other");
    assert_eq!(item["href"], format!("/app/projects/{pid}"));
    // Somebody without a share, even an administrator, has nothing to see.
    let (_, admin) = get(&f, "/activity", ADMIN_PAT).await;
    assert_eq!(admin["data"].as_array().unwrap().len(), 0);
}

#[tokio::test]
async fn a_member_never_sees_events_on_a_private_project_of_somebody_else() {
    let f = fixture().await;
    let pid = project(&f.pool, f.owner_id, "Private").await;
    for (action, at) in [
        ("created", "2026-05-01T10:00:00Z"),
        ("timer_started", "2026-05-01T10:01:00Z"),
        ("trashed", "2026-05-01T10:02:00Z"),
    ] {
        ev(&f, "project", pid, action, at, Some(f.owner_id)).await;
    }
    let (_, other) = get(&f, "/activity", OTHER_PAT).await;
    assert_eq!(other["data"].as_array().unwrap().len(), 0);
    let text = serde_json::to_string(&other).unwrap();
    assert!(!text.contains("Private"));
    // A share opens the history, and its end hides it again.
    share(&f.pool, "project", pid, f.other_id, "editor").await;
    let (_, other) = get(&f, "/activity", OTHER_PAT).await;
    assert_eq!(other["data"].as_array().unwrap().len(), 3);
    sqlx::query("DELETE FROM resource_shares")
        .execute(&f.pool)
        .await
        .unwrap();
    let (_, other) = get(&f, "/activity", OTHER_PAT).await;
    assert_eq!(other["data"].as_array().unwrap().len(), 0);
}

#[tokio::test]
async fn own_actions_on_foreign_resources_show_without_link_unless_the_reader_may_open_them() {
    let f = fixture().await;
    let pid = project(&f.pool, f.owner_id, "Babymuetze").await;
    sqlx::query("UPDATE projects SET name = 'Renamed later' WHERE id = ?")
        .bind(pid)
        .execute(&f.pool)
        .await
        .unwrap();
    let by_admin = ev(
        &f,
        "project",
        pid,
        "timer_started",
        "2026-05-01T10:00:00Z",
        Some(f.admin_id),
    )
    .await;
    let restored = ev(
        &f,
        "project",
        pid,
        "restored",
        "2026-05-01T10:05:00Z",
        Some(f.admin_id),
    )
    .await;
    sqlx::query(
        "UPDATE audit_events SET resource_name_snapshot = 'Babymuetze', subject_user_id = ?, \
         client_kind = 'system', client_label = 'Manual restore' WHERE id = ?",
    )
    .bind(f.owner_id)
    .bind(restored)
    .execute(&f.pool)
    .await
    .unwrap();
    let by_other = ev(
        &f,
        "project",
        pid,
        "updated",
        "2026-05-01T11:00:00Z",
        Some(f.other_id),
    )
    .await;
    sqlx::query("UPDATE audit_events SET resource_name_snapshot = 'Babymuetze' WHERE id = ?")
        .bind(by_other)
        .execute(&f.pool)
        .await
        .unwrap();

    let (_, admin) = get(&f, "/activity", ADMIN_PAT).await;
    let data = admin["data"].as_array().unwrap();
    let ids: Vec<i64> = data.iter().map(|e| e["id"].as_i64().unwrap()).collect();
    assert_eq!(ids, vec![restored, by_admin]);
    assert_eq!(data[0]["actor"]["kind"], "self");
    assert_eq!(data[0]["entity_name"], "Renamed later");
    assert_eq!(data[0]["href"], format!("/app/projects/{pid}"));
    assert_eq!(data[0]["subject"]["name"], "Activity Owner");
    assert!(
        data[0].get("system").is_none(),
        "the admin acted, not the server"
    );
    assert_eq!(data[1]["action"], "timer_started");

    // The member never had access: what they did stays, as a plain name.
    let (_, other) = get(&f, "/activity", OTHER_PAT).await;
    let data = other["data"].as_array().unwrap();
    assert_eq!(data.len(), 1);
    assert_eq!(data[0]["id"], by_other);
    assert_eq!(data[0]["entity_name"], "Babymuetze");
    assert_eq!(data[0]["href"], serde_json::Value::Null);
    assert_eq!(data[0]["thumbnail_url"], serde_json::Value::Null);

    // The owner reads the same events, the administrator anonymously.
    let (_, owner) = get(&f, "/activity", OWNER_PAT).await;
    let data = owner["data"].as_array().unwrap();
    assert_eq!(data.len(), 3);
    assert_eq!(data[1]["actor"]["kind"], "admin");
    assert_eq!(data[1]["actor"]["name"], serde_json::Value::Null);
    assert_eq!(data[2]["actor"]["kind"], "admin");
}

#[tokio::test]
async fn every_action_kind_serialises_with_an_actor() {
    let f = fixture().await;
    let pid = project(&f.pool, f.owner_id, "Everything").await;
    let yid = yarn(&f.pool, f.owner_id, "Everything yarn").await;
    let project_actions = [
        "created",
        "updated",
        "uploaded",
        "removed",
        "deleted",
        "trashed",
        "restored",
        "purged",
        "step_created",
        "step_updated",
        "step_deleted",
        "step_trashed",
        "step_restored",
        "step_purged",
        "title_image_uploaded",
        "stitch_sample_image_uploaded",
        "step_image_uploaded",
        "attachment_uploaded",
        "attachment_deleted",
        "image_deleted",
        "photo_uploaded",
        "photo_deleted",
        "photo_promoted",
        "title_image_promoted",
        "shared",
        "share_role_changed",
        "share_revoked",
        "share_left",
        "ownership_transferred",
        "timer_started",
        "timer_stopped",
        "timer_total_changed",
    ];
    for (i, action) in project_actions.iter().enumerate() {
        let (entity, id) = if i % 2 == 0 {
            ("project", pid)
        } else {
            ("yarn", yid)
        };
        ev(
            &f,
            entity,
            id,
            action,
            &format!("2026-06-{:02}T10:00:00Z", i + 1),
            Some(f.owner_id),
        )
        .await;
    }
    let (status, body) = get(&f, "/activity?limit=50", OWNER_PAT).await;
    assert_eq!(status, StatusCode::OK);
    let data = body["data"].as_array().unwrap();
    let mut seen: Vec<&str> = data.iter().map(|e| e["action"].as_str().unwrap()).collect();
    seen.sort_unstable();
    let mut wanted = project_actions.to_vec();
    wanted.sort_unstable();
    assert_eq!(seen, wanted);
    for item in data {
        assert_eq!(item["actor"]["kind"], "self", "{item}");
        assert_eq!(item["actor"]["name"], "Activity Owner");
        assert!(item.get("client").is_none());
    }
    assert!(
        !serde_json::to_string(&body)
            .unwrap()
            .contains("example.test")
    );
}

#[tokio::test]
async fn system_and_unknown_actors_are_told_apart_and_sharing_never_carries_an_email() {
    let f = fixture().await;
    let pid = project(&f.pool, f.owner_id, "Systemic").await;
    let purge = ev(&f, "project", pid, "purged", "2026-05-01T10:00:00Z", None).await;
    let legacy = ev(&f, "project", pid, "updated", "2026-05-01T09:00:00Z", None).await;
    let shared = ev(
        &f,
        "project",
        pid,
        "shared",
        "2026-05-01T11:00:00Z",
        Some(f.owner_id),
    )
    .await;
    sqlx::query(
        "UPDATE audit_events SET subject_user_id = ?, subject_role = 'viewer' WHERE id = ?",
    )
    .bind(f.other_id)
    .bind(shared)
    .execute(&f.pool)
    .await
    .unwrap();
    let (_, body) = get(&f, "/activity", OWNER_PAT).await;
    let data = body["data"].as_array().unwrap();
    assert_eq!(data[0]["id"], shared);
    assert_eq!(data[0]["subject"]["name"], "Activity Other");
    assert_eq!(data[0]["subject"]["role"], "viewer");
    assert_eq!(data[1]["id"], purge);
    assert_eq!(data[1]["actor"]["kind"], "system");
    assert_eq!(data[1]["system"], true);
    assert_eq!(data[2]["id"], legacy);
    assert_eq!(data[2]["actor"]["kind"], "unknown");
    assert!(data[2].get("system").is_none());
    assert!(
        !serde_json::to_string(&body)
            .unwrap()
            .contains("example.test")
    );
}
