use axum::{
    Router,
    body::Body,
    http::{Request, StatusCode},
};
use http_body_util::BodyExt;
use sha2::{Digest, Sha256};
use sqlx::{SqlitePool, sqlite::SqlitePoolOptions};
use stricknani_server::{api_v2, db};
use tower::ServiceExt;

const ADMIN_TOKEN: &str = "sna_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
const MEMBER_TOKEN: &str = "sna_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
const API_TOKEN: &str = "sna_cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc";

struct Fixture {
    _media_root: tempfile::TempDir,
    app: Router,
    pool: SqlitePool,
    admin_id: i64,
}

async fn fixture() -> Fixture {
    let pool = db::connect("sqlite::memory:")
        .await
        .expect("database migrates");
    let admin_id = db::create_user(
        &pool,
        "audit-admin@example.test",
        "administrator-password".into(),
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
        "audit-member@example.test",
        "member-password-long".into(),
    )
    .await
    .expect("member exists");
    add_token(&pool, admin_id, ADMIN_TOKEN, "browser").await;
    add_token(&pool, member_id, MEMBER_TOKEN, "browser").await;
    add_token(&pool, admin_id, API_TOKEN, "api").await;
    let media_root = tempfile::tempdir().expect("media root should be created");
    Fixture {
        app: api_v2::router(
            db::AppState::new(pool.clone())
                .with_secure_cookies(true)
                .with_media_root(media_root.path().to_path_buf()),
            false,
        ),
        _media_root: media_root,
        pool,
        admin_id,
    }
}

async fn add_token(pool: &SqlitePool, user_id: i64, token: &str, purpose: &str) {
    sqlx::query(
        "INSERT INTO api_tokens (user_id, name, token_hash, created_at, expires_at, purpose) \
         VALUES (?, 'audit test token', ?, unixepoch(), unixepoch() + 86400, ?)",
    )
    .bind(user_id)
    .bind(hex::encode(Sha256::digest(token.as_bytes())))
    .bind(purpose)
    .execute(pool)
    .await
    .unwrap();
}

fn request(path: &str, token: Option<&str>) -> Request<Body> {
    let mut request = Request::get(path);
    if let Some(token) = token {
        request = request.header("cookie", format!("__Host-stricknani_session={token}"));
    }
    request.body(Body::empty()).unwrap()
}

async fn json(response: axum::response::Response) -> serde_json::Value {
    serde_json::from_slice(&response.into_body().collect().await.unwrap().to_bytes()).unwrap()
}

#[tokio::test]
async fn audit_api_is_active_admin_only_and_bearer_tokens_do_not_authenticate() {
    let fixture = fixture().await;
    let anonymous = fixture
        .app
        .clone()
        .oneshot(request("/admin/audit", None))
        .await
        .unwrap();
    assert_eq!(anonymous.status(), StatusCode::UNAUTHORIZED);
    assert_eq!(json(anonymous).await["error"]["code"], "session_expired");

    let member = fixture
        .app
        .clone()
        .oneshot(request("/admin/audit", Some(MEMBER_TOKEN)))
        .await
        .unwrap();
    assert_eq!(member.status(), StatusCode::FORBIDDEN);
    assert_eq!(json(member).await["error"]["code"], "admin_required");

    let bearer = fixture
        .app
        .clone()
        .oneshot(
            Request::get("/admin/audit")
                .header("authorization", format!("Bearer {API_TOKEN}"))
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(bearer.status(), StatusCode::UNAUTHORIZED);

    let admin = fixture
        .app
        .oneshot(request("/admin/audit", Some(ADMIN_TOKEN)))
        .await
        .unwrap();
    assert_eq!(admin.status(), StatusCode::OK);
    assert_eq!(admin.headers()["cache-control"], "no-store");
}

#[tokio::test]
async fn audit_api_returns_only_the_safe_projection_and_preserves_legacy_actor_fields() {
    let fixture = fixture().await;
    sqlx::query(
        "INSERT INTO audit_events \
         (actor_user_id, entity_type, entity_id, action, details, created_at) \
         VALUES (?, 'project', 72, 'custom-historical-action', \
                 '{\"notes\":\"secret audit detail\",\"token\":\"secret credential\"}', \
                 '2026-02-01T00:00:00.000000Z')",
    )
    .bind(fixture.admin_id)
    .execute(&fixture.pool)
    .await
    .unwrap();
    sqlx::query(
        "INSERT INTO user_avatars(user_id, filename, original_filename, content_type) \
         VALUES (?, 'audit-avatar.png', 'audit-avatar.png', 'image/png')",
    )
    .bind(fixture.admin_id)
    .execute(&fixture.pool)
    .await
    .unwrap();
    sqlx::query(
        "INSERT INTO audit_events \
         (actor_user_id, legacy_actor_user_id, entity_type, entity_id, action, details, created_at) \
         VALUES (NULL, 987, 'project', 73, 'custom-historical-action', \
                 '{\"private\":\"never expose\"}', '2026-02-02T00:00:00.000000Z')",
    )
    .execute(&fixture.pool)
    .await
    .unwrap();

    let response = fixture
        .app
        .oneshot(request(
            "/admin/audit?entity=project&action=custom-historical-action",
            Some(ADMIN_TOKEN),
        ))
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    let result = json(response).await;
    assert_eq!(result["meta"]["page"], 1);
    assert_eq!(result["meta"]["per_page"], 50);
    assert_eq!(result["meta"]["has_more"], false);
    let events = result["data"].as_array().unwrap();
    assert_eq!(events.len(), 2);
    assert_eq!(events[0]["entity_id"], 73);
    assert_eq!(events[0]["actor_email"], serde_json::Value::Null);
    assert_eq!(events[0]["actor_avatar_url"], serde_json::Value::Null);
    assert_eq!(events[0]["legacy_actor_user_id"], 987);
    assert_eq!(events[0]["action"], "custom-historical-action");
    assert_eq!(events[1]["actor_email"], "audit-admin@example.test");
    assert_eq!(
        events[1]["actor_avatar_url"],
        format!(
            "/api/v2/admin/users/{}/avatar/thumb_audit-avatar.jpg",
            fixture.admin_id
        )
    );
    assert_eq!(events[1]["legacy_actor_user_id"], serde_json::Value::Null);
    assert!(events.iter().all(|event| event.get("details").is_none()));
    let serialized = serde_json::to_string(&result).unwrap();
    assert!(!serialized.contains("secret audit detail"));
    assert!(!serialized.contains("secret credential"));
    assert!(!serialized.contains("never expose"));
}

#[tokio::test]
async fn audit_resource_previews_use_current_admin_scoped_media_urls() {
    let fixture = fixture().await;
    let project_id: i64 = sqlx::query_scalar(
        "INSERT INTO projects(owner_id, name, created_at, updated_at) \
         VALUES (?, 'Preview project', '2026-01-01', '2026-01-01') RETURNING id",
    )
    .bind(fixture.admin_id)
    .fetch_one(&fixture.pool)
    .await
    .unwrap();
    let yarn_id: i64 = sqlx::query_scalar(
        "INSERT INTO yarns(owner_id, name, created_at, updated_at) \
         VALUES (?, 'Preview yarn', '2026-01-01', '2026-01-01') RETURNING id",
    )
    .bind(fixture.admin_id)
    .fetch_one(&fixture.pool)
    .await
    .unwrap();
    let category_id: i64 = sqlx::query_scalar(
        "INSERT INTO categories(user_id, name) VALUES (?, 'Preview category') RETURNING id",
    )
    .bind(fixture.admin_id)
    .fetch_one(&fixture.pool)
    .await
    .unwrap();

    for (kind, yarn, filename, content_type, is_primary) in [
        ("title", false, "project-cover.jpg", "image/jpeg", true),
        (
            "attachment",
            false,
            "project-notes.pdf",
            "application/pdf",
            false,
        ),
        ("photo", true, "yarn-cover.jpg", "image/jpeg", true),
    ] {
        let (project_ref, yarn_ref) = if yarn {
            (None, Some(yarn_id))
        } else {
            (Some(project_id), None)
        };
        sqlx::query(
            "INSERT INTO media_files( \
                 owner_id, project_id, yarn_id, kind, public_id, filename, \
                 original_filename, content_type, size_bytes, is_primary \
             ) VALUES (?, ?, ?, ?, 5, ?, ?, ?, 12, ?)",
        )
        .bind(fixture.admin_id)
        .bind(project_ref)
        .bind(yarn_ref)
        .bind(kind)
        .bind(filename)
        .bind(filename)
        .bind(content_type)
        .bind(is_primary)
        .execute(&fixture.pool)
        .await
        .unwrap();
    }
    let project_dir = fixture
        ._media_root
        .path()
        .join("projects")
        .join(project_id.to_string());
    let project_thumbnail_dir = fixture
        ._media_root
        .path()
        .join("thumbnails/projects")
        .join(project_id.to_string());
    let yarn_dir = fixture
        ._media_root
        .path()
        .join("yarns")
        .join(yarn_id.to_string());
    let yarn_thumbnail_dir = fixture
        ._media_root
        .path()
        .join("thumbnails/yarns")
        .join(yarn_id.to_string());
    for directory in [
        &project_dir,
        &project_thumbnail_dir,
        &yarn_dir,
        &yarn_thumbnail_dir,
    ] {
        tokio::fs::create_dir_all(directory).await.unwrap();
    }
    tokio::fs::write(project_dir.join("project-cover.jpg"), b"project image")
        .await
        .unwrap();
    tokio::fs::write(
        project_thumbnail_dir.join("thumb_project-cover.jpg"),
        b"project thumbnail",
    )
    .await
    .unwrap();
    tokio::fs::write(project_dir.join("project-notes.pdf"), b"attachment")
        .await
        .unwrap();
    tokio::fs::write(yarn_dir.join("yarn-cover.jpg"), b"yarn image")
        .await
        .unwrap();
    tokio::fs::write(
        yarn_thumbnail_dir.join("thumb_yarn-cover.jpg"),
        b"yarn thumbnail",
    )
    .await
    .unwrap();

    let mut event_ids = Vec::new();
    for (kind, id, created_at) in [
        ("project", project_id, "2026-02-01"),
        ("yarn", yarn_id, "2026-02-02"),
        ("category", category_id, "2026-02-03"),
        ("project", 987_654, "2026-02-04"),
    ] {
        let event_id: i64 = sqlx::query_scalar(
            "INSERT INTO audit_events(actor_user_id, entity_type, entity_id, action, created_at) \
             VALUES (?, ?, ?, 'created', ?) RETURNING id",
        )
        .bind(fixture.admin_id)
        .bind(kind)
        .bind(id)
        .bind(created_at)
        .fetch_one(&fixture.pool)
        .await
        .unwrap();
        event_ids.push((kind, id, event_id));
    }

    let response = fixture
        .app
        .clone()
        .oneshot(request("/admin/audit", Some(ADMIN_TOKEN)))
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    let body = json(response).await;
    let events = body["data"].as_array().unwrap();
    let resource_for = |kind: &str, id: i64| {
        events
            .iter()
            .find(|event| event["entity_type"] == kind && event["entity_id"] == id)
            .unwrap()["resource"]
            .clone()
    };
    let project_preview =
        format!("/api/v2/admin/resources/project/{project_id}/media/image/5/thumbnail");
    let yarn_preview = format!("/api/v2/admin/resources/yarn/{yarn_id}/media/photo/5/thumbnail");
    assert_eq!(
        resource_for("project", project_id)["preview_url"],
        project_preview
    );
    assert_eq!(resource_for("yarn", yarn_id)["preview_url"], yarn_preview);
    assert_eq!(
        resource_for("category", category_id)["preview_url"],
        serde_json::Value::Null
    );
    let deleted = resource_for("project", 987_654);
    assert_eq!(deleted["preview_url"], serde_json::Value::Null);
    assert_eq!(deleted["exists"], false);

    for (path, token, expected_status, expected_body) in [
        (
            format!("/admin/resources/project/{project_id}/media/image/5/thumbnail"),
            ADMIN_TOKEN,
            StatusCode::OK,
            Some(b"project thumbnail".as_slice()),
        ),
        (
            format!("/admin/resources/project/{project_id}/media/image/5/thumbnail"),
            MEMBER_TOKEN,
            StatusCode::FORBIDDEN,
            None,
        ),
        (
            format!("/admin/resources/project/{project_id}/media/attachment/5/thumbnail"),
            ADMIN_TOKEN,
            StatusCode::NOT_FOUND,
            None,
        ),
        (
            format!("/admin/resources/yarn/{yarn_id}/media/photo/5/thumbnail"),
            ADMIN_TOKEN,
            StatusCode::OK,
            Some(b"yarn thumbnail".as_slice()),
        ),
    ] {
        let response = fixture
            .app
            .clone()
            .oneshot(request(&path, Some(token)))
            .await
            .unwrap();
        assert_eq!(response.status(), expected_status);
        let body = response.into_body().collect().await.unwrap().to_bytes();
        if let Some(expected_body) = expected_body {
            assert_eq!(body.as_ref(), expected_body);
        }
    }

    let project_event_id = event_ids
        .iter()
        .find(|(kind, id, _)| *kind == "project" && *id == project_id)
        .unwrap()
        .2;
    let detail = fixture
        .app
        .clone()
        .oneshot(request(
            &format!("/admin/audit/{project_event_id}"),
            Some(ADMIN_TOKEN),
        ))
        .await
        .unwrap();
    assert_eq!(detail.status(), StatusCode::OK);
    let detail = json(detail).await;
    assert_eq!(
        detail["data"]["event"]["resource"]["preview_url"],
        project_preview
    );

    sqlx::query("DELETE FROM media_files WHERE project_id = ?")
        .bind(project_id)
        .execute(&fixture.pool)
        .await
        .unwrap();
    let detail_after_media_removal = fixture
        .app
        .oneshot(request(
            &format!("/admin/audit/{project_event_id}"),
            Some(ADMIN_TOKEN),
        ))
        .await
        .unwrap();
    let detail_after_media_removal = json(detail_after_media_removal).await;
    assert_eq!(
        detail_after_media_removal["data"]["event"]["resource"]["preview_url"],
        serde_json::Value::Null
    );
}

#[tokio::test]
async fn audit_api_filters_and_query_limits_match_the_legacy_view() {
    let fixture = fixture().await;
    for entity_id in 1000..1051 {
        sqlx::query(
            "INSERT INTO audit_events \
             (actor_user_id, entity_type, entity_id, action, created_at) \
             VALUES (?, 'project', ?, 'created', '2026-03-01T00:00:00.000000Z')",
        )
        .bind(fixture.admin_id)
        .bind(entity_id)
        .execute(&fixture.pool)
        .await
        .unwrap();
    }
    sqlx::query(
        "INSERT INTO audit_events \
         (actor_user_id, entity_type, entity_id, action, created_at) \
         VALUES (?, 'yarn', 77, 'created', '2026-03-02T00:00:00.000000Z')",
    )
    .bind(fixture.admin_id)
    .execute(&fixture.pool)
    .await
    .unwrap();

    let first = fixture
        .app
        .clone()
        .oneshot(request(
            "/admin/audit?entity=project&action=created&page=1",
            Some(ADMIN_TOKEN),
        ))
        .await
        .unwrap();
    assert_eq!(first.status(), StatusCode::OK);
    let first = json(first).await;
    assert_eq!(first["meta"]["has_more"], true);
    assert_eq!(first["data"].as_array().unwrap().len(), 50);
    assert_eq!(first["data"][0]["entity_id"], 1050);

    let second = fixture
        .app
        .clone()
        .oneshot(request(
            "/admin/audit?entity=project&action=created&page=2",
            Some(ADMIN_TOKEN),
        ))
        .await
        .unwrap();
    let second = json(second).await;
    assert_eq!(second["meta"]["has_more"], false);
    assert_eq!(second["data"].as_array().unwrap().len(), 1);
    assert_eq!(second["data"][0]["entity_id"], 1000);

    for query in [
        "?page=0",
        "?page=10001",
        "?entity=untrusted",
        "?unknown=field",
        "?page=1&page=2",
        "?action=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
        "?action=%01",
    ] {
        let response = fixture
            .app
            .clone()
            .oneshot(request(&format!("/admin/audit{query}"), Some(ADMIN_TOKEN)))
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::BAD_REQUEST, "query: {query}");
        assert_eq!(json(response).await["error"]["code"], "invalid_query");
    }
    let oversized_query = format!("?{}", "x".repeat(513));
    let oversized = fixture
        .app
        .oneshot(request(
            &format!("/admin/audit{oversized_query}"),
            Some(ADMIN_TOKEN),
        ))
        .await
        .unwrap();
    assert_eq!(oversized.status(), StatusCode::BAD_REQUEST);
    assert_eq!(json(oversized).await["error"]["code"], "invalid_query");
}

#[tokio::test]
async fn audit_detail_projects_only_allowlisted_legacy_changes_for_admins() {
    let fixture = fixture().await;
    let project_id: i64 = sqlx::query_scalar(
        "INSERT INTO projects(owner_id, name, created_at, updated_at) \
         VALUES (?, 'Current project', '2026-01-01', '2026-01-01') RETURNING id",
    )
    .bind(fixture.admin_id)
    .fetch_one(&fixture.pool)
    .await
    .unwrap();
    let event_id: i64 = sqlx::query_scalar(
        "INSERT INTO audit_events(actor_user_id, entity_type, entity_id, action, details, created_at) \
         VALUES (?, 'project', ?, 'updated', ?, '2026-02-01') RETURNING id",
    )
    .bind(fixture.admin_id)
    .bind(project_id)
    .bind(r#"{"changes":{"name":{"old":"Before","new":"After"},"tags":{"old":"['a']","new":"['b']"},"password_hash":{"old":"private","new":"private2"}}}"#)
    .fetch_one(&fixture.pool)
    .await
    .unwrap();

    let member = fixture
        .app
        .clone()
        .oneshot(request(
            &format!("/admin/audit/{event_id}"),
            Some(MEMBER_TOKEN),
        ))
        .await
        .unwrap();
    assert_eq!(member.status(), StatusCode::FORBIDDEN);

    let response = fixture
        .app
        .oneshot(request(
            &format!("/admin/audit/{event_id}"),
            Some(ADMIN_TOKEN),
        ))
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    let body = json(response).await;
    assert_eq!(body["data"]["event"]["resource"]["kind"], "project");
    assert_eq!(body["data"]["event"]["resource"]["name"], "Current project");
    assert_eq!(body["data"]["event"]["resource"]["name_source"], "current");
    assert_eq!(
        body["data"]["event"]["resource"]["href"],
        format!("/app/admin/resources/project/{project_id}")
    );
    assert_eq!(body["data"]["event"]["has_changes"], true);
    assert_eq!(body["data"]["changes"]["version"], 1);
    assert_eq!(
        body["data"]["changes"]["items"].as_array().unwrap().len(),
        2
    );
    assert_eq!(body["data"]["unavailable_reason"], serde_json::Value::Null);
    let serialized = serde_json::to_string(&body).unwrap();
    assert!(!serialized.contains("password_hash"));
    assert!(!serialized.contains("private2"));
    assert!(!serialized.contains("details"));
}

#[tokio::test]
async fn audit_detail_distinguishes_missing_and_oversized_history() {
    let fixture = fixture().await;
    let missing_id: i64 = sqlx::query_scalar(
        "INSERT INTO audit_events(actor_user_id, entity_type, entity_id, action, created_at) \
         VALUES (?, 'project', 987654, 'created', '2026-01-01') RETURNING id",
    )
    .bind(fixture.admin_id)
    .fetch_one(&fixture.pool)
    .await
    .unwrap();
    let oversized_id: i64 = sqlx::query_scalar(
        "INSERT INTO audit_events(actor_user_id, entity_type, entity_id, action, change_version, safe_changes, created_at) \
         VALUES (?, 'project', 987655, 'updated', 1, ?, '2026-01-02') RETURNING id",
    )
    .bind(fixture.admin_id)
    .bind("x".repeat(262_145))
    .fetch_one(&fixture.pool)
    .await
    .unwrap();

    for (id, expected_reason, expected_has_changes) in [
        (missing_id, "not_recorded", false),
        (oversized_id, "too_large", true),
    ] {
        let response = fixture
            .app
            .clone()
            .oneshot(request(&format!("/admin/audit/{id}"), Some(ADMIN_TOKEN)))
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::OK);
        let body = json(response).await;
        assert_eq!(body["data"]["changes"], serde_json::Value::Null);
        assert_eq!(body["data"]["unavailable_reason"], expected_reason);
        assert_eq!(body["data"]["event"]["has_changes"], expected_has_changes);
        assert_eq!(
            body["data"]["event"]["resource"]["name_source"],
            "unavailable"
        );
        assert_eq!(
            body["data"]["event"]["resource"]["href"],
            format!("/app/admin/audit/{id}")
        );
    }
}

#[tokio::test]
async fn audit_history_migration_preserves_existing_legacy_rows() {
    let pool = SqlitePoolOptions::new()
        .max_connections(1)
        .connect("sqlite::memory:")
        .await
        .unwrap();
    sqlx::query("CREATE TABLE audit_events (id INTEGER PRIMARY KEY, details TEXT)")
        .execute(&pool)
        .await
        .unwrap();
    sqlx::query("INSERT INTO audit_events(id, details) VALUES (41, ?)")
        .bind(r#"{"historical":true}"#)
        .execute(&pool)
        .await
        .unwrap();
    sqlx::raw_sql(include_str!("../migrations/0014_audit_history.sql"))
        .execute(&pool)
        .await
        .unwrap();
    let row = sqlx::query(
        "SELECT id, details, resource_name_snapshot, change_version, safe_changes FROM audit_events",
    )
    .fetch_one(&pool)
    .await
    .unwrap();
    use sqlx::Row;
    assert_eq!(row.get::<i64, _>("id"), 41);
    assert_eq!(row.get::<String, _>("details"), r#"{"historical":true}"#);
    assert_eq!(row.get::<Option<String>, _>("resource_name_snapshot"), None);
    assert_eq!(row.get::<Option<i64>, _>("change_version"), None);
    assert_eq!(row.get::<Option<String>, _>("safe_changes"), None);
}
