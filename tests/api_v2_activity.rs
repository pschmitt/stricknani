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
    assert_eq!(item["actor"]["name"], "activity-owner");
    assert_eq!(item["changed_fields"], serde_json::json!(["project.name"]));
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
    assert_eq!(data[0]["actor"]["name"], serde_json::Value::Null);
    assert_eq!(data[0]["actor"]["avatar_url"], serde_json::Value::Null);
    let text = serde_json::to_string(&body).unwrap();
    assert!(!text.contains("Theirs") && !text.contains("Their yarn"));
    assert!(!text.contains("example.test"));
    let (_, other) = get(&f, "/activity", OTHER_PAT).await;
    assert_eq!(other["data"].as_array().unwrap().len(), 3);
    let (_, admin) = get(&f, "/activity", ADMIN_PAT).await;
    assert_eq!(admin["data"].as_array().unwrap().len(), 0);
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
