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

const OWNER_PAT: &str = "history-owner-api-pat";
const OTHER_PAT: &str = "history-other-api-pat";
const ADMIN_PAT: &str = "history-admin-api-pat";
const OWNER_SESSION: &str = "history-owner-browser-session";
const INVALID_PAT: &str = "history-invalid-pat";

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
    let owner_id = create_user(&pool, "history-owner@example.test").await;
    let other_id = create_user(&pool, "history-other@example.test").await;
    let admin_id = create_user(&pool, "history-admin@example.test").await;
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
    db::create_user(pool, email, "history-test-password".into())
        .await
        .expect("test user is created")
}

async fn add_token(pool: &SqlitePool, user_id: i64, token: &str, purpose: &str) {
    sqlx::query(
        "INSERT INTO api_tokens (user_id, name, token_hash, created_at, expires_at, purpose) \
         VALUES (?, 'resource history test token', ?, unixepoch(), unixepoch() + 86400, ?)",
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

#[tokio::test]
async fn owner_history_accepts_pat_and_browser_session_and_projects_owner_urls() {
    let fixture = fixture().await;
    let project_id = project(&fixture.pool, fixture.owner_id, "Owner project").await;
    let yarn_id = yarn(&fixture.pool, fixture.owner_id, "Owner yarn").await;
    let event_id = event(
        &fixture.pool,
        EventInput {
            actor_id: Some(fixture.owner_id),
            entity_type: "project",
            entity_id: project_id,
            action: "updated",
            created_at: "2026-04-01T00:00:00Z",
            details: None,
            safe_changes: None,
        },
    )
    .await;
    let yarn_event_id = event(
        &fixture.pool,
        EventInput {
            actor_id: Some(fixture.owner_id),
            entity_type: "yarn",
            entity_id: yarn_id,
            action: "photo_uploaded",
            created_at: "2026-04-02T00:00:00Z",
            details: None,
            safe_changes: None,
        },
    )
    .await;
    sqlx::query(
        "INSERT INTO user_avatars(user_id, filename, original_filename, content_type) \
         VALUES (?, 'owner-avatar.png', 'owner-avatar.png', 'image/png')",
    )
    .bind(fixture.owner_id)
    .execute(&fixture.pool)
    .await
    .unwrap();
    sqlx::query(
        "INSERT INTO media_files(owner_id, project_id, kind, public_id, filename, \
         original_filename, content_type, size_bytes, is_primary) \
         VALUES (?, ?, 'title', 42, 'owner-cover.jpg', 'cover.jpg', 'image/jpeg', 1, 1)",
    )
    .bind(fixture.owner_id)
    .bind(project_id)
    .execute(&fixture.pool)
    .await
    .unwrap();

    let path = format!("/projects/{project_id}/history");
    let pat = fixture
        .app
        .clone()
        .oneshot(bearer(&path, OWNER_PAT))
        .await
        .unwrap();
    assert_eq!(pat.status(), StatusCode::OK);
    assert_eq!(pat.headers()["cache-control"], "no-store");
    let body = json(pat).await;
    assert_eq!(body["meta"]["page"], 1);
    assert_eq!(body["meta"]["per_page"], 20);
    assert_eq!(body["meta"]["has_more"], false);
    assert_eq!(body["data"].as_array().unwrap().len(), 1);
    assert_eq!(body["data"][0]["id"], event_id);
    assert_eq!(
        body["data"][0]["resource"]["href"],
        format!("/app/projects/{project_id}")
    );
    assert_eq!(
        body["data"][0]["resource"]["preview_url"],
        format!("/media/thumbnails/projects/{project_id}/thumb_owner-cover.jpg")
    );
    assert_eq!(
        body["data"][0]["actor_avatar_url"],
        format!(
            "/media/thumbnails/users/{}/thumb_owner-avatar.jpg",
            fixture.owner_id
        )
    );
    assert!(
        !serde_json::to_string(&body)
            .unwrap()
            .contains("/api/v2/admin/")
    );

    let browser = fixture
        .app
        .clone()
        .oneshot(cookie(&path, OWNER_SESSION))
        .await
        .unwrap();
    assert_eq!(browser.status(), StatusCode::OK);

    let yarn_history = fixture
        .app
        .oneshot(bearer(&format!("/yarns/{yarn_id}/history"), OWNER_PAT))
        .await
        .unwrap();
    assert_eq!(yarn_history.status(), StatusCode::OK);
    let yarn_history = json(yarn_history).await;
    assert_eq!(yarn_history["data"][0]["id"], yarn_event_id);
    assert_eq!(
        yarn_history["data"][0]["resource"]["href"],
        format!("/app/yarns/{yarn_id}")
    );
}

#[tokio::test]
async fn owner_history_auth_and_parent_scope_hide_foreign_resources_even_from_admins() {
    let fixture = fixture().await;
    let own_project = project(&fixture.pool, fixture.owner_id, "Owner project").await;
    let foreign_project = project(&fixture.pool, fixture.other_id, "Foreign project").await;
    let foreign_event = event(
        &fixture.pool,
        EventInput {
            actor_id: Some(fixture.other_id),
            entity_type: "project",
            entity_id: foreign_project,
            action: "updated",
            created_at: "2026-04-01T00:00:00Z",
            details: None,
            safe_changes: None,
        },
    )
    .await;
    let own_path = format!("/projects/{own_project}/history");
    let no_fallback = fixture
        .app
        .clone()
        .oneshot(
            Request::get(&own_path)
                .header("authorization", format!("Bearer {INVALID_PAT}"))
                .header(
                    "cookie",
                    format!("__Host-stricknani_session={OWNER_SESSION}"),
                )
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(no_fallback.status(), StatusCode::UNAUTHORIZED);
    assert_eq!(json(no_fallback).await["error"]["code"], "session_expired");

    for (token, path) in [
        (OWNER_PAT, format!("/projects/{foreign_project}/history")),
        (OTHER_PAT, format!("/projects/{own_project}/history")),
        (OWNER_PAT, "/projects/999999/history".to_owned()),
    ] {
        let response = fixture
            .app
            .clone()
            .oneshot(bearer(&path, token))
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::NOT_FOUND, "{path}");
        assert_eq!(json(response).await["error"]["code"], "not_found");
    }

    // Administrators read the history of any owner's resource.
    let admin_history = fixture
        .app
        .clone()
        .oneshot(bearer(&own_path, ADMIN_PAT))
        .await
        .unwrap();
    assert_eq!(admin_history.status(), StatusCode::OK);

    let foreign_event_detail = fixture
        .app
        .oneshot(bearer(
            &format!("/projects/{own_project}/history/{foreign_event}"),
            OWNER_PAT,
        ))
        .await
        .unwrap();
    assert_eq!(foreign_event_detail.status(), StatusCode::NOT_FOUND);
}

#[tokio::test]
async fn history_pages_include_child_actions_and_break_timestamp_ties_by_id() {
    let fixture = fixture().await;
    let project_id = project(&fixture.pool, fixture.owner_id, "Paged project").await;
    let mut inserted = Vec::new();
    for index in 0..21 {
        let action = match index % 3 {
            0 => "step_updated",
            1 => "media_uploaded",
            _ => "favorite_added",
        };
        inserted.push(
            event(
                &fixture.pool,
                EventInput {
                    actor_id: Some(fixture.owner_id),
                    entity_type: "project",
                    entity_id: project_id,
                    action,
                    created_at: "2026-04-02T00:00:00Z",
                    details: None,
                    safe_changes: None,
                },
            )
            .await,
        );
    }

    let first = fixture
        .app
        .clone()
        .oneshot(bearer(
            &format!("/projects/{project_id}/history"),
            OWNER_PAT,
        ))
        .await
        .unwrap();
    assert_eq!(first.status(), StatusCode::OK);
    let first = json(first).await;
    assert_eq!(first["data"].as_array().unwrap().len(), 20);
    assert_eq!(first["meta"]["has_more"], true);
    assert_eq!(first["data"][0]["id"], inserted[20]);
    assert_eq!(first["data"][19]["id"], inserted[1]);

    let second = fixture
        .app
        .oneshot(bearer(
            &format!("/projects/{project_id}/history?page=2"),
            OWNER_PAT,
        ))
        .await
        .unwrap();
    let second = json(second).await;
    assert_eq!(second["data"].as_array().unwrap().len(), 1);
    assert_eq!(second["data"][0]["id"], inserted[0]);
    assert_eq!(second["meta"]["has_more"], false);
}

#[tokio::test]
async fn history_detail_reuses_safe_changes_projection_and_checks_resource_type() {
    let fixture = fixture().await;
    let project_id = project(&fixture.pool, fixture.owner_id, "Change project").await;
    let other_project_id = project(&fixture.pool, fixture.owner_id, "Another project").await;
    let yarn_id = yarn(&fixture.pool, fixture.owner_id, "Change yarn").await;
    let event_id = event(
        &fixture.pool,
        EventInput {
            actor_id: Some(fixture.admin_id),
            entity_type: "project",
            entity_id: project_id,
            action: "updated",
            created_at: "2026-04-03T00:00:00Z",
            details: Some(
                r#"{"changes":{"name":{"old":"Old","new":"New"},"password_hash":{"old":"sensitive","new":"secret"}}}"#,
            ),
            safe_changes: None,
        },
    )
    .await;
    let safe_event_id = event(
        &fixture.pool,
        EventInput {
            actor_id: Some(fixture.owner_id),
            entity_type: "project",
            entity_id: project_id,
            action: "updated",
            created_at: "2026-04-04T00:00:00Z",
            details: None,
            safe_changes: Some(r#"{"version":1,"items":[{"field":"project.name","before":{"type":"text","value":"Before"},"after":{"type":"text","value":"After"}}]}"#),
        },
    )
    .await;
    sqlx::query(
        "INSERT INTO user_avatars(user_id, filename, original_filename, content_type) \
         VALUES (?, 'admin-avatar.png', 'admin-avatar.png', 'image/png')",
    )
    .bind(fixture.admin_id)
    .execute(&fixture.pool)
    .await
    .unwrap();

    let detail = fixture
        .app
        .clone()
        .oneshot(bearer(
            &format!("/projects/{project_id}/history/{event_id}"),
            OWNER_PAT,
        ))
        .await
        .unwrap();
    assert_eq!(detail.status(), StatusCode::OK);
    let detail = json(detail).await;
    assert_eq!(detail["data"]["event"]["id"], event_id);
    assert_eq!(
        detail["data"]["event"]["actor_avatar_url"],
        serde_json::Value::Null
    );
    assert_eq!(
        detail["data"]["changes"]["items"].as_array().unwrap().len(),
        1
    );
    assert_eq!(
        detail["data"]["changes"]["items"][0]["field"],
        "project.name"
    );
    assert_eq!(
        detail["data"]["unavailable_reason"],
        serde_json::Value::Null
    );
    let serialized = serde_json::to_string(&detail).unwrap();
    assert!(!serialized.contains("sensitive"));
    assert!(!serialized.contains("secret"));
    assert!(!serialized.contains("/api/v2/admin/"));
    assert!(detail.get("meta").is_none());

    let safe_detail = fixture
        .app
        .clone()
        .oneshot(bearer(
            &format!("/projects/{project_id}/history/{safe_event_id}"),
            OWNER_PAT,
        ))
        .await
        .unwrap();
    assert_eq!(
        json(safe_detail).await["data"]["changes"]["items"][0]["after"]["value"],
        "After"
    );

    for path in [
        format!("/projects/{other_project_id}/history/{event_id}"),
        format!("/yarns/{yarn_id}/history/{event_id}"),
    ] {
        let response = fixture
            .app
            .clone()
            .oneshot(bearer(&path, OWNER_PAT))
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::NOT_FOUND, "{path}");
    }
}

#[tokio::test]
async fn history_page_query_and_deleted_parent_are_not_disclosed() {
    let fixture = fixture().await;
    let project_id = project(&fixture.pool, fixture.owner_id, "Deleted project").await;
    let event_id = event(
        &fixture.pool,
        EventInput {
            actor_id: Some(fixture.owner_id),
            entity_type: "project",
            entity_id: project_id,
            action: "deleted",
            created_at: "2026-04-05T00:00:00Z",
            details: None,
            safe_changes: None,
        },
    )
    .await;
    for query in [
        "?page=0",
        "?page=10001",
        "?page=nope",
        "?unknown=x",
        "?page=1&page=2",
    ] {
        let response = fixture
            .app
            .clone()
            .oneshot(bearer(
                &format!("/projects/{project_id}/history{query}"),
                OWNER_PAT,
            ))
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::BAD_REQUEST, "{query}");
        assert_eq!(json(response).await["error"]["code"], "invalid_query");
    }
    sqlx::query("DELETE FROM projects WHERE id = ? AND owner_id = ?")
        .bind(project_id)
        .bind(fixture.owner_id)
        .execute(&fixture.pool)
        .await
        .unwrap();
    for path in [
        format!("/projects/{project_id}/history"),
        format!("/projects/{project_id}/history/{event_id}"),
    ] {
        let response = fixture
            .app
            .clone()
            .oneshot(bearer(&path, OWNER_PAT))
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::NOT_FOUND, "{path}");
    }
}

#[tokio::test]
async fn audit_previews_follow_canonical_cover_even_when_legacy_flags_disagree() {
    let fixture = fixture().await;
    let id = project(&fixture.pool, fixture.owner_id, "Reordered project").await;
    let event_id = event(
        &fixture.pool,
        EventInput {
            actor_id: Some(fixture.owner_id),
            entity_type: "project",
            entity_id: id,
            action: "updated",
            created_at: "2026-04-01T00:00:00Z",
            details: None,
            safe_changes: None,
        },
    )
    .await;
    for (public_id, filename, position, old_primary, cover) in [
        (42, "old-cover.jpg", 0, 1, 0),
        (43, "selected-cover.jpg", 1, 0, 1),
    ] {
        sqlx::query(
            "INSERT INTO media_files(owner_id, project_id, kind, public_id, filename, \
             original_filename, content_type, size_bytes, position, is_primary, is_project_cover) \
             VALUES (?, ?, 'title', ?, ?, ?, 'image/jpeg', 1, ?, ?, ?)",
        )
        .bind(fixture.owner_id)
        .bind(id)
        .bind(public_id)
        .bind(filename)
        .bind(filename)
        .bind(position)
        .bind(old_primary)
        .bind(cover)
        .execute(&fixture.pool)
        .await
        .unwrap();
    }
    for path in [
        format!("/projects/{id}/history"),
        format!("/projects/{id}/history/{event_id}"),
    ] {
        let response = fixture
            .app
            .clone()
            .oneshot(bearer(&path, OWNER_PAT))
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::OK);
        let body = json(response).await;
        let event = if path.ends_with("/history") {
            &body["data"][0]
        } else {
            &body["data"]["event"]
        };
        assert_eq!(
            event["resource"]["preview_url"],
            format!("/media/thumbnails/projects/{id}/thumb_selected-cover.jpg")
        );
    }
    for path in [
        "/admin/audit".to_owned(),
        format!("/admin/audit/{event_id}"),
    ] {
        let response = fixture
            .app
            .clone()
            .oneshot(bearer(&path, ADMIN_PAT))
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::OK);
        let body = json(response).await;
        let event = if path == "/admin/audit" {
            &body["data"][0]
        } else {
            &body["data"]["event"]
        };
        assert_eq!(
            event["resource"]["preview_url"],
            format!("/api/v2/admin/resources/project/{id}/media/image/43/thumbnail")
        );
    }
}

async fn media_row(
    pool: &SqlitePool,
    owner_id: i64,
    parent: (&str, i64),
    row: (&str, i64, &str, &str, i64),
) {
    let (kind, public_id, filename, original, position) = row;
    let column = if kind == "photo" {
        "yarn_id"
    } else {
        "project_id"
    };
    let sql = format!(
        "INSERT INTO media_files(owner_id, {column}, kind, public_id, filename, \
         original_filename, content_type, size_bytes, position) \
         VALUES (?, ?, ?, ?, ?, ?, 'image/jpeg', 1, ?)"
    );
    assert!(parent.0 == "project" || parent.0 == "yarn");
    sqlx::query(sqlx::AssertSqlSafe(sql.as_str()))
        .bind(owner_id)
        .bind(parent.1)
        .bind(kind)
        .bind(public_id)
        .bind(filename)
        .bind(original)
        .bind(position)
        .execute(pool)
        .await
        .unwrap();
}

#[tokio::test]
async fn order_events_resolve_current_pictures_to_thumbnails_for_readers() {
    let fixture = fixture().await;
    let project_id = project(&fixture.pool, fixture.owner_id, "Picture project").await;
    media_row(
        &fixture.pool,
        fixture.owner_id,
        ("project", project_id),
        ("title", 1, "one-stored.jpg", "19413.jpg", 0),
    )
    .await;
    media_row(
        &fixture.pool,
        fixture.owner_id,
        ("project", project_id),
        ("title", 2, "two-stored.jpg", "second.jpg", 1),
    )
    .await;
    let changes = r#"{"version":1,"items":[{"field":"project.image_order","before":{"type":"text_list","value":["second.jpg","19413.jpg","deleted.jpg"]},"after":{"type":"text_list","value":["19413.jpg","second.jpg"]}},{"field":"project.cover_image","before":{"type":"text","value":"second.jpg"},"after":{"type":"text","value":"19413.jpg"}}]}"#;
    let event_id = event(
        &fixture.pool,
        EventInput {
            actor_id: Some(fixture.owner_id),
            entity_type: "project",
            entity_id: project_id,
            action: "updated",
            created_at: "2026-05-01T00:00:00Z",
            details: None,
            safe_changes: Some(changes),
        },
    )
    .await;
    let plain_id = event(
        &fixture.pool,
        EventInput {
            actor_id: Some(fixture.owner_id),
            entity_type: "project",
            entity_id: project_id,
            action: "updated",
            created_at: "2026-05-02T00:00:00Z",
            details: None,
            safe_changes: Some(r#"{"version":1,"items":[{"field":"project.name","before":{"type":"text","value":"A"},"after":{"type":"text","value":"B"}}]}"#),
        },
    )
    .await;

    let list = json(
        fixture
            .app
            .clone()
            .oneshot(bearer(
                &format!("/projects/{project_id}/history"),
                OWNER_PAT,
            ))
            .await
            .unwrap(),
    )
    .await;
    let events = list["data"].as_array().unwrap();
    let order = events.iter().find(|e| e["id"] == event_id).unwrap();
    let map = order["media_thumbnails"].as_object().unwrap();
    assert_eq!(map.len(), 2, "deleted photo label is absent: {map:?}");
    assert_eq!(
        map["19413.jpg"]["url"],
        format!("/media/projects/{project_id}/one-stored.jpg")
    );
    assert_eq!(
        map["second.jpg"]["thumbnail_url"],
        format!("/media/thumbnails/projects/{project_id}/thumb_two-stored.jpg")
    );
    assert!(!map.contains_key("deleted.jpg"));
    let plain = events.iter().find(|e| e["id"] == plain_id).unwrap();
    assert!(plain.get("media_thumbnails").is_none());

    let detail = json(
        fixture
            .app
            .clone()
            .oneshot(bearer(
                &format!("/projects/{project_id}/history/{event_id}"),
                OWNER_PAT,
            ))
            .await
            .unwrap(),
    )
    .await;
    assert_eq!(
        detail["data"]["event"]["media_thumbnails"],
        order["media_thumbnails"]
    );

    // Another account never reads this history, so never its pictures ...
    let denied = fixture
        .app
        .clone()
        .oneshot(bearer(
            &format!("/projects/{project_id}/history"),
            OTHER_PAT,
        ))
        .await
        .unwrap();
    assert_eq!(denied.status(), StatusCode::NOT_FOUND);
    // ... until the project is shared with it: then it gets the same map.
    sqlx::query(
        "INSERT INTO resource_shares (kind, resource_id, user_id, role, created_at, updated_at) \
         VALUES ('project', ?, ?, 'viewer', 'x', 'x')",
    )
    .bind(project_id)
    .bind(fixture.other_id)
    .execute(&fixture.pool)
    .await
    .unwrap();
    let shared = json(
        fixture
            .app
            .clone()
            .oneshot(bearer(
                &format!("/projects/{project_id}/history"),
                OTHER_PAT,
            ))
            .await
            .unwrap(),
    )
    .await;
    let shared_order = shared["data"]
        .as_array()
        .unwrap()
        .iter()
        .find(|e| e["id"] == event_id)
        .unwrap();
    assert_eq!(shared_order["media_thumbnails"], order["media_thumbnails"]);
}

#[tokio::test]
async fn yarn_photo_order_events_resolve_thumbnails() {
    let fixture = fixture().await;
    let yarn_id = yarn(&fixture.pool, fixture.owner_id, "Photo yarn").await;
    media_row(
        &fixture.pool,
        fixture.owner_id,
        ("yarn", yarn_id),
        ("photo", 7, "ball-stored.jpg", "ball.jpg", 0),
    )
    .await;
    media_row(
        &fixture.pool,
        fixture.owner_id,
        ("yarn", yarn_id),
        ("photo", 8, "label-stored.jpg", "", 1),
    )
    .await;
    let event_id = event(
        &fixture.pool,
        EventInput {
            actor_id: Some(fixture.owner_id),
            entity_type: "yarn",
            entity_id: yarn_id,
            action: "updated",
            created_at: "2026-05-03T00:00:00Z",
            details: None,
            safe_changes: Some(r#"{"version":1,"items":[{"field":"yarn.photo_order","before":{"type":"text_list","value":["label-stored.jpg","ball.jpg"]},"after":{"type":"text_list","value":["ball.jpg","label-stored.jpg"]}},{"field":"yarn.primary_photo","before":{"type":"text","value":"label-stored.jpg"},"after":{"type":"text","value":"ball.jpg"}}]}"#),
        },
    )
    .await;
    let detail = json(
        fixture
            .app
            .clone()
            .oneshot(bearer(
                &format!("/yarns/{yarn_id}/history/{event_id}"),
                OWNER_PAT,
            ))
            .await
            .unwrap(),
    )
    .await;
    let map = detail["data"]["event"]["media_thumbnails"]
        .as_object()
        .unwrap();
    assert_eq!(map.len(), 2);
    assert_eq!(
        map["ball.jpg"]["thumbnail_url"],
        format!("/media/thumbnails/yarns/{yarn_id}/thumb_ball-stored.jpg")
    );
    assert_eq!(
        map["label-stored.jpg"]["url"],
        format!("/media/yarns/{yarn_id}/label-stored.jpg")
    );
}

#[tokio::test]
async fn admin_audit_resolves_thumbnails_to_read_only_administrator_routes() {
    let fixture = fixture().await;
    let project_id = project(&fixture.pool, fixture.owner_id, "Admin picture project").await;
    media_row(
        &fixture.pool,
        fixture.owner_id,
        ("project", project_id),
        ("title", 1, "one-stored.jpg", "19413.jpg", 0),
    )
    .await;
    media_row(
        &fixture.pool,
        fixture.owner_id,
        ("project", project_id),
        ("title", 2, "two-stored.jpg", "second.jpg", 1),
    )
    .await;
    let yarn_id = yarn(&fixture.pool, fixture.other_id, "Admin photo yarn").await;
    media_row(
        &fixture.pool,
        fixture.other_id,
        ("yarn", yarn_id),
        ("photo", 7, "ball-stored.jpg", "ball.jpg", 0),
    )
    .await;
    let project_event = event(
        &fixture.pool,
        EventInput {
            actor_id: Some(fixture.owner_id),
            entity_type: "project",
            entity_id: project_id,
            action: "updated",
            created_at: "2026-05-01T00:00:00Z",
            details: None,
            safe_changes: Some(r#"{"version":1,"items":[{"field":"project.image_order","before":{"type":"text_list","value":["second.jpg","19413.jpg","deleted.jpg"]},"after":{"type":"text_list","value":["19413.jpg","second.jpg"]}}]}"#),
        },
    )
    .await;
    let yarn_event = event(
        &fixture.pool,
        EventInput {
            actor_id: Some(fixture.other_id),
            entity_type: "yarn",
            entity_id: yarn_id,
            action: "updated",
            created_at: "2026-05-02T00:00:00Z",
            details: None,
            safe_changes: Some(r#"{"version":1,"items":[{"field":"yarn.primary_photo","before":{"type":"text","value":"gone.jpg"},"after":{"type":"text","value":"ball.jpg"}}]}"#),
        },
    )
    .await;
    let plain_event = event(
        &fixture.pool,
        EventInput {
            actor_id: Some(fixture.owner_id),
            entity_type: "project",
            entity_id: project_id,
            action: "updated",
            created_at: "2026-05-03T00:00:00Z",
            details: None,
            safe_changes: Some(r#"{"version":1,"items":[{"field":"project.name","before":{"type":"text","value":"A"},"after":{"type":"text","value":"B"}}]}"#),
        },
    )
    .await;

    let list = json(
        fixture
            .app
            .clone()
            .oneshot(bearer("/admin/audit", ADMIN_PAT))
            .await
            .unwrap(),
    )
    .await;
    let events = list["data"].as_array().unwrap();
    let project_json = events.iter().find(|e| e["id"] == project_event).unwrap();
    let map = project_json["media_thumbnails"].as_object().unwrap();
    assert_eq!(map.len(), 2, "deleted label is absent: {map:?}");
    assert_eq!(
        map["19413.jpg"]["url"],
        format!("/api/v2/admin/resources/project/{project_id}/media/image/1")
    );
    assert_eq!(
        map["second.jpg"]["thumbnail_url"],
        format!("/api/v2/admin/resources/project/{project_id}/media/image/2/thumbnail")
    );
    let yarn_json = events.iter().find(|e| e["id"] == yarn_event).unwrap();
    let yarn_map = yarn_json["media_thumbnails"].as_object().unwrap();
    assert_eq!(yarn_map.len(), 1);
    assert_eq!(
        yarn_map["ball.jpg"]["thumbnail_url"],
        format!("/api/v2/admin/resources/yarn/{yarn_id}/media/photo/7/thumbnail")
    );
    let plain = events.iter().find(|e| e["id"] == plain_event).unwrap();
    assert!(plain.get("media_thumbnails").is_none());

    let detail = json(
        fixture
            .app
            .clone()
            .oneshot(bearer(&format!("/admin/audit/{yarn_event}"), ADMIN_PAT))
            .await
            .unwrap(),
    )
    .await;
    assert_eq!(
        detail["data"]["event"]["media_thumbnails"],
        yarn_json["media_thumbnails"]
    );

    for token in [OWNER_PAT, OTHER_PAT] {
        let denied = fixture
            .app
            .clone()
            .oneshot(bearer("/admin/audit", token))
            .await
            .unwrap();
        assert_eq!(denied.status(), StatusCode::FORBIDDEN);
        let denied = fixture
            .app
            .clone()
            .oneshot(bearer(&format!("/admin/audit/{yarn_event}"), token))
            .await
            .unwrap();
        assert_eq!(denied.status(), StatusCode::FORBIDDEN);
    }
}
