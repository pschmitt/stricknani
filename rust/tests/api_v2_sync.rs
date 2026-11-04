use axum::{
    body::Body,
    http::{Request, StatusCode},
};
use chrono::{DateTime, SecondsFormat, Utc};
use http_body_util::BodyExt;
use sha2::{Digest, Sha256};
use sqlx::SqlitePool;
use stricknani_server::{app_with_state, db};
use tower::ServiceExt;

const OWNER_PAT: &str = "sync-v2-owner-api-pat";
const OTHER_PAT: &str = "sync-v2-other-api-pat";
const OWNER_SESSION: &str = "sync-v2-owner-browser-session";
const ADMIN_SESSION: &str = "sync-v2-admin-browser-session";

struct Fixture {
    app: axum::Router,
    pool: SqlitePool,
    owner_id: i64,
    other_id: i64,
    admin_id: i64,
}

async fn fixture() -> Fixture {
    let pool = db::connect("sqlite::memory:")
        .await
        .expect("database migrates");
    let owner_id = seed_user(&pool, "sync-owner@example.test", OWNER_PAT, "api").await;
    seed_token(&pool, owner_id, OWNER_SESSION, "browser").await;
    let other_id = seed_user(&pool, "sync-other@example.test", OTHER_PAT, "api").await;
    let admin_id = seed_user(&pool, "sync-admin@example.test", ADMIN_SESSION, "browser").await;
    sqlx::query("UPDATE users SET is_admin = 1 WHERE id = ?")
        .bind(admin_id)
        .execute(&pool)
        .await
        .unwrap();
    Fixture {
        app: app_with_state(db::AppState::new(pool.clone()).with_secure_cookies(true)),
        pool,
        owner_id,
        other_id,
        admin_id,
    }
}

async fn seed_user(pool: &SqlitePool, email: &str, token: &str, purpose: &str) -> i64 {
    let user_id =
        sqlx::query("INSERT INTO users (email, password_hash) VALUES (?, 'test-only-hash')")
            .bind(email)
            .execute(pool)
            .await
            .expect("test user is inserted")
            .last_insert_rowid();
    seed_token(pool, user_id, token, purpose).await;
    user_id
}

async fn seed_token(pool: &SqlitePool, user_id: i64, token: &str, purpose: &str) {
    sqlx::query(
        "INSERT INTO api_tokens (user_id, name, token_hash, created_at, expires_at, purpose) \
         VALUES (?, 'sync v2 fixture', ?, unixepoch(), unixepoch() + 86400, ?)",
    )
    .bind(user_id)
    .bind(hex::encode(Sha256::digest(token.as_bytes())))
    .bind(purpose)
    .execute(pool)
    .await
    .expect("test token is inserted");
}

async fn insert_project(
    pool: &SqlitePool,
    owner_id: i64,
    name: &str,
    at: &str,
    description: Option<&str>,
    notes: Option<&str>,
) -> i64 {
    let at = stored_timestamp(at);
    sqlx::query(
        "INSERT INTO projects (owner_id, name, description, notes, tags, created_at, updated_at) \
         VALUES (?, ?, ?, ?, '[]', ?, ?)",
    )
    .bind(owner_id)
    .bind(name)
    .bind(description)
    .bind(notes)
    .bind(&at)
    .bind(&at)
    .execute(pool)
    .await
    .expect("project is inserted")
    .last_insert_rowid()
}

async fn insert_yarn(
    pool: &SqlitePool,
    owner_id: i64,
    name: &str,
    at: &str,
    notes: Option<&str>,
) -> i64 {
    let at = stored_timestamp(at);
    sqlx::query(
        "INSERT INTO yarns (owner_id, name, notes, created_at, updated_at) VALUES (?, ?, ?, ?, ?)",
    )
    .bind(owner_id)
    .bind(name)
    .bind(notes)
    .bind(&at)
    .bind(&at)
    .execute(pool)
    .await
    .expect("yarn is inserted")
    .last_insert_rowid()
}

async fn insert_deletion(
    pool: &SqlitePool,
    owner_id: i64,
    kind: &str,
    entity_id: i64,
    at: &str,
) -> i64 {
    let at = stored_timestamp(at);
    sqlx::query(
        "INSERT INTO deletion_events (owner_id, entity_type, entity_id, changed_at) VALUES (?, ?, ?, ?)",
    )
    .bind(owner_id)
    .bind(kind)
    .bind(entity_id)
    .bind(at)
    .execute(pool)
    .await
    .expect("tombstone is inserted")
    .last_insert_rowid()
}

fn stored_timestamp(value: &str) -> String {
    DateTime::parse_from_rfc3339(value)
        .expect("fixture timestamp is RFC3339")
        .with_timezone(&Utc)
        .to_rfc3339_opts(SecondsFormat::Micros, true)
}

fn get(path: &str, bearer: Option<&str>, cookie: Option<&str>) -> Request<Body> {
    let mut request = Request::get(path);
    if let Some(token) = bearer {
        request = request.header("authorization", format!("Bearer {token}"));
    }
    if let Some(token) = cookie {
        request = request.header("cookie", format!("__Host-stricknani_session={token}"));
    }
    request.body(Body::empty()).unwrap()
}

async fn send(
    app: &axum::Router,
    path: &str,
    bearer: Option<&str>,
    cookie: Option<&str>,
) -> axum::response::Response {
    app.clone()
        .oneshot(get(path, bearer, cookie))
        .await
        .expect("router responds")
}

async fn json(response: axum::response::Response) -> serde_json::Value {
    serde_json::from_slice(&response.into_body().collect().await.unwrap().to_bytes()).unwrap()
}

fn encode_query_value(value: &str) -> String {
    value
        .replace('%', "%25")
        .replace(':', "%3A")
        .replace('+', "%2B")
}

struct OrderedMediaFixture {
    project_id: Option<i64>,
    yarn_id: Option<i64>,
    step_id: Option<i64>,
    kind: &'static str,
    public_id: i64,
    position: i64,
    is_primary: bool,
    is_project_cover: bool,
}

async fn insert_ordered_media(pool: &SqlitePool, owner_id: i64, media: OrderedMediaFixture) {
    sqlx::query(
        "INSERT INTO media_files \
         (owner_id,project_id,yarn_id,step_id,kind,public_id,filename,original_filename, \
          content_type,size_bytes,position,is_primary,is_project_cover) \
         VALUES (?,?,?,?,?,?,?,?,'image/png',0,?,?,?)",
    )
    .bind(owner_id)
    .bind(media.project_id)
    .bind(media.yarn_id)
    .bind(media.step_id)
    .bind(media.kind)
    .bind(media.public_id)
    .bind(format!("sync-media-{}.png", media.public_id))
    .bind(format!("sync-media-{}.png", media.public_id))
    .bind(media.position)
    .bind(media.is_primary)
    .bind(media.is_project_cover)
    .execute(pool)
    .await
    .expect("ordered media fixture is inserted");
}

async fn put_json(
    app: &axum::Router,
    path: &str,
    bearer: &str,
    body: serde_json::Value,
) -> axum::response::Response {
    let request = Request::builder()
        .method("PUT")
        .uri(path)
        .header("authorization", format!("Bearer {bearer}"))
        .header("content-type", "application/json")
        .body(Body::from(body.to_string()))
        .unwrap();
    app.clone().oneshot(request).await.expect("router responds")
}

async fn assert_project_sync_matches_detail(fixture: &Fixture, project_id: i64) {
    let detail = json(
        send(
            &fixture.app,
            &format!("/api/v2/projects/{project_id}"),
            Some(OWNER_PAT),
            None,
        )
        .await,
    )
    .await;
    assert_eq!(detail["data"]["id"], project_id);
    let sync = json(
        send(
            &fixture.app,
            "/api/v2/sync/projects?since=2020-01-01T00%3A00%3A00Z",
            Some(OWNER_PAT),
            None,
        )
        .await,
    )
    .await;
    let synced = sync["data"]["updated"]
        .as_array()
        .unwrap()
        .iter()
        .find(|item| item["id"] == project_id)
        .expect("changed project appears in delta");
    assert_eq!(synced["images"], detail["data"]["images"]);
    assert_eq!(synced["attachments"], detail["data"]["attachments"]);
    assert_eq!(synced["preview_url"], detail["data"]["preview_url"]);
    assert_eq!(synced["updated_at"], detail["data"]["updated_at"]);
}

#[tokio::test]
async fn v2_project_and_yarn_sync_return_owner_scoped_dtos_and_tombstones() {
    let fixture = fixture().await;
    let project_id = insert_project(
        &fixture.pool,
        fixture.owner_id,
        "Owner project",
        "2024-01-01T01:00:00Z",
        Some("**soft wool**\n<script>unsafe()</script>"),
        Some("Hand washed"),
    )
    .await;
    let other_project_id = insert_project(
        &fixture.pool,
        fixture.other_id,
        "Other project",
        "2024-01-01T01:00:00Z",
        None,
        None,
    )
    .await;
    let step_id: i64 = sqlx::query_scalar(
        "INSERT INTO project_steps (project_id, title, description, step_number) \
         VALUES (?, 'Cast on', '*two stitches*', 1) RETURNING id",
    )
    .bind(project_id)
    .fetch_one(&fixture.pool)
    .await
    .unwrap();
    let yarn_id = insert_yarn(
        &fixture.pool,
        fixture.owner_id,
        "Owner yarn",
        "2024-01-01T01:00:00Z",
        Some("**warm wool**"),
    )
    .await;
    let other_yarn_id = insert_yarn(
        &fixture.pool,
        fixture.other_id,
        "Other yarn",
        "2024-01-01T01:00:00Z",
        None,
    )
    .await;
    insert_deletion(
        &fixture.pool,
        fixture.owner_id,
        "project",
        80_001,
        "2024-01-01T02:00:00Z",
    )
    .await;
    insert_deletion(
        &fixture.pool,
        fixture.other_id,
        "project",
        80_002,
        "2024-01-01T02:00:00Z",
    )
    .await;
    insert_deletion(
        &fixture.pool,
        fixture.owner_id,
        "yarn",
        90_001,
        "2024-01-01T02:00:00Z",
    )
    .await;
    insert_deletion(
        &fixture.pool,
        fixture.other_id,
        "yarn",
        90_002,
        "2024-01-01T02:00:00Z",
    )
    .await;

    for (endpoint, own_id, other_id, own_deleted, other_deleted) in [
        ("projects", project_id, other_project_id, 80_001, 80_002),
        ("yarns", yarn_id, other_yarn_id, 90_001, 90_002),
    ] {
        let response = send(
            &fixture.app,
            &format!("/api/v2/sync/{endpoint}"),
            Some(OWNER_PAT),
            None,
        )
        .await;
        assert_eq!(response.status(), StatusCode::OK);
        let body = json(response).await;
        let data = &body["data"];
        assert_eq!(data.as_object().unwrap().len(), 6);
        assert_eq!(data["updated"].as_array().map(Vec::len), Some(1));
        assert_eq!(data["updated"][0]["id"], own_id);
        assert_eq!(data["deleted_ids"], serde_json::json!([own_deleted]));
        assert_eq!(data["full_resync_required"], false);
        assert_eq!(data["has_more"], false);
        assert!(data["next_cursor"].is_null());
        assert!(data["server_time"].as_str().is_some());
        assert_ne!(data["updated"][0]["id"], other_id);
        assert_ne!(data["deleted_ids"], serde_json::json!([other_deleted]));
    }

    let project =
        json(send(&fixture.app, "/api/v2/sync/projects", Some(OWNER_PAT), None).await).await;
    let project = &project["data"]["updated"][0];
    assert_eq!(
        project["description"],
        "**soft wool**\n<script>unsafe()</script>"
    );
    assert!(
        project["description_html"]
            .as_str()
            .unwrap()
            .contains("<strong>soft wool</strong>")
    );
    assert!(
        !project["description_html"]
            .as_str()
            .unwrap()
            .contains("<script")
    );
    assert_eq!(project["steps"][0]["id"], step_id);
    assert!(
        project["steps"][0]["description_html"]
            .as_str()
            .unwrap()
            .contains("<em>two stitches</em>")
    );

    let yarn = json(send(&fixture.app, "/api/v2/sync/yarns", Some(OWNER_PAT), None).await).await;
    let yarn = &yarn["data"]["updated"][0];
    assert_eq!(yarn["name"], "Owner yarn");
    assert!(
        yarn["notes_html"]
            .as_str()
            .unwrap()
            .contains("<strong>warm wool</strong>")
    );

    let exclusive = send(
        &fixture.app,
        "/api/v2/sync/projects?since=2024-01-01T01%3A00%3A00Z",
        Some(OWNER_PAT),
        None,
    )
    .await;
    assert_eq!(exclusive.status(), StatusCode::OK);
    assert_eq!(
        json(exclusive).await["data"]["updated"],
        serde_json::json!([])
    );
}

#[tokio::test]
async fn v2_sync_cursor_pages_are_stable_across_equal_times_and_late_changes() {
    let fixture = fixture().await;
    let first = insert_project(
        &fixture.pool,
        fixture.owner_id,
        "First",
        "2024-01-01T01:00:00Z",
        None,
        None,
    )
    .await;
    let second = insert_project(
        &fixture.pool,
        fixture.owner_id,
        "Second",
        "2024-01-01T01:00:00Z",
        None,
        None,
    )
    .await;
    insert_deletion(
        &fixture.pool,
        fixture.owner_id,
        "project",
        70_001,
        "2024-01-01T01:00:00Z",
    )
    .await;
    insert_deletion(
        &fixture.pool,
        fixture.owner_id,
        "project",
        70_002,
        "2024-01-01T01:00:00Z",
    )
    .await;

    let first_response = send(
        &fixture.app,
        "/api/v2/sync/projects?limit=1",
        Some(OWNER_PAT),
        None,
    )
    .await;
    assert_eq!(first_response.status(), StatusCode::OK);
    let first_page = json(first_response).await;
    let data = &first_page["data"];
    assert_eq!(data["has_more"], true);
    let snapshot = data["server_time"].as_str().unwrap().to_owned();
    let mut cursor = data["next_cursor"].as_str().unwrap().to_owned();
    let mut updated_ids = data["updated"]
        .as_array()
        .unwrap()
        .iter()
        .map(|item| item["id"].as_i64().unwrap())
        .collect::<Vec<_>>();
    let mut deleted_ids = data["deleted_ids"]
        .as_array()
        .unwrap()
        .iter()
        .map(|id| id.as_i64().unwrap())
        .collect::<Vec<_>>();

    let late_at = Utc::now().to_rfc3339_opts(SecondsFormat::Micros, true);
    let late_id = insert_project(
        &fixture.pool,
        fixture.owner_id,
        "After snapshot",
        &late_at,
        None,
        None,
    )
    .await;
    insert_deletion(&fixture.pool, fixture.owner_id, "project", 70_003, &late_at).await;

    let mut page_count = 1;
    loop {
        let response = send(
            &fixture.app,
            &format!("/api/v2/sync/projects?cursor={cursor}"),
            Some(OWNER_PAT),
            None,
        )
        .await;
        assert_eq!(response.status(), StatusCode::OK);
        let page = json(response).await;
        let data = &page["data"];
        assert_eq!(data["server_time"], snapshot);
        updated_ids.extend(
            data["updated"]
                .as_array()
                .unwrap()
                .iter()
                .map(|item| item["id"].as_i64().unwrap()),
        );
        deleted_ids.extend(
            data["deleted_ids"]
                .as_array()
                .unwrap()
                .iter()
                .map(|id| id.as_i64().unwrap()),
        );
        page_count += 1;
        assert!(page_count <= 5, "cursor advances through a finite snapshot");
        if data["has_more"] == false {
            assert!(data["next_cursor"].is_null());
            break;
        }
        cursor = data["next_cursor"].as_str().unwrap().to_owned();
    }

    updated_ids.sort_unstable();
    deleted_ids.sort_unstable();
    assert_eq!(page_count, 4);
    assert_eq!(updated_ids, vec![first, second]);
    assert_eq!(deleted_ids, vec![70_001, 70_002]);
    assert!(!updated_ids.contains(&late_id));
    assert!(!deleted_ids.contains(&70_003));

    let mut next_cursor = None;
    let mut next_updated = Vec::new();
    let mut next_deleted = Vec::new();
    loop {
        let query = match next_cursor.as_deref() {
            Some(cursor) => format!("cursor={cursor}"),
            None => format!("since={}&limit=1", encode_query_value(&snapshot)),
        };
        let response = send(
            &fixture.app,
            &format!("/api/v2/sync/projects?{query}"),
            Some(OWNER_PAT),
            None,
        )
        .await;
        assert_eq!(response.status(), StatusCode::OK);
        let data = json(response).await["data"].clone();
        next_updated.extend(
            data["updated"]
                .as_array()
                .unwrap()
                .iter()
                .map(|item| item["id"].as_i64().unwrap()),
        );
        next_deleted.extend(
            data["deleted_ids"]
                .as_array()
                .unwrap()
                .iter()
                .map(|id| id.as_i64().unwrap()),
        );
        if data["has_more"] == false {
            assert!(data["next_cursor"].is_null());
            break;
        }
        next_cursor = Some(data["next_cursor"].as_str().unwrap().to_owned());
    }
    assert_eq!(next_updated, vec![late_id]);
    assert_eq!(next_deleted, vec![70_003]);
}

#[tokio::test]
async fn v2_sync_rejects_bad_queries_and_bound_or_version_mismatched_cursors() {
    let fixture = fixture().await;
    insert_project(
        &fixture.pool,
        fixture.owner_id,
        "First",
        "2024-01-01T01:00:00Z",
        None,
        None,
    )
    .await;
    insert_project(
        &fixture.pool,
        fixture.owner_id,
        "Second",
        "2024-01-01T02:00:00Z",
        None,
        None,
    )
    .await;
    insert_yarn(
        &fixture.pool,
        fixture.owner_id,
        "Yarn one",
        "2024-01-01T01:00:00Z",
        None,
    )
    .await;
    insert_yarn(
        &fixture.pool,
        fixture.owner_id,
        "Yarn two",
        "2024-01-01T02:00:00Z",
        None,
    )
    .await;
    insert_project(
        &fixture.pool,
        fixture.other_id,
        "Foreign project",
        "2024-01-01T01:00:00Z",
        None,
        None,
    )
    .await;

    for (query, field) in [
        ("limit=0", "limit"),
        ("limit=51", "limit"),
        ("limit=invalid", "limit"),
        ("since=not-a-timestamp", "since"),
        ("since=2024-01-01", "since"),
        ("since=2024-01-01T01%3A00%3A00", "since"),
    ] {
        let response = send(
            &fixture.app,
            &format!("/api/v2/sync/projects?{query}"),
            Some(OWNER_PAT),
            None,
        )
        .await;
        assert_eq!(
            response.status(),
            StatusCode::UNPROCESSABLE_ENTITY,
            "{query}"
        );
        let error = json(response).await["error"].clone();
        assert_eq!(error["code"], "validation_error", "{query}");
        assert!(error["fields"][field].is_string(), "{query}");
    }

    for query in ["unknown=yes", "limit=1&limit=2", "limit=invalid&limit=1"] {
        let response = send(
            &fixture.app,
            &format!("/api/v2/sync/projects?{query}"),
            Some(OWNER_PAT),
            None,
        )
        .await;
        assert_eq!(response.status(), StatusCode::BAD_REQUEST, "{query}");
        assert_eq!(
            json(response).await["error"]["code"],
            "invalid_query",
            "{query}"
        );
    }

    let page = json(
        send(
            &fixture.app,
            "/api/v2/sync/projects?limit=1",
            Some(OWNER_PAT),
            None,
        )
        .await,
    )
    .await;
    let cursor = page["data"]["next_cursor"].as_str().unwrap();
    let bad_since_and_cursor = send(
        &fixture.app,
        &format!("/api/v2/sync/projects?cursor={cursor}&since=not-a-timestamp"),
        Some(OWNER_PAT),
        None,
    )
    .await;
    assert_eq!(bad_since_and_cursor.status(), StatusCode::BAD_REQUEST);
    assert_eq!(
        json(bad_since_and_cursor).await["error"]["code"],
        "invalid_query"
    );

    let timezone_offset = send(
        &fixture.app,
        "/api/v2/sync/projects?since=2024-01-01T03%3A00%3A00%2B02%3A00",
        Some(OWNER_PAT),
        None,
    )
    .await;
    assert_eq!(timezone_offset.status(), StatusCode::OK);

    let invalid_cursor_paths = [
        "/api/v2/sync/projects?cursor=not-a-cursor".to_owned(),
        format!("/api/v2/sync/yarns?cursor={cursor}"),
        format!("/api/v2/sync/projects?cursor={}", "a".repeat(4097)),
    ];
    for path in invalid_cursor_paths {
        let response = send(&fixture.app, &path, Some(OWNER_PAT), None).await;
        assert_eq!(
            response.status(),
            StatusCode::BAD_REQUEST,
            "cursor rejected"
        );
        assert_eq!(json(response).await["error"]["code"], "invalid_cursor");
    }

    let mutually_exclusive = send(
        &fixture.app,
        &format!("/api/v2/sync/projects?cursor={cursor}&since=2024-01-01T00%3A00%3A00Z"),
        Some(OWNER_PAT),
        None,
    )
    .await;
    assert_eq!(mutually_exclusive.status(), StatusCode::BAD_REQUEST);
    assert_eq!(
        json(mutually_exclusive).await["error"]["code"],
        "invalid_query"
    );

    let other_owner = send(
        &fixture.app,
        &format!("/api/v2/sync/projects?cursor={cursor}"),
        Some(OTHER_PAT),
        None,
    )
    .await;
    assert_eq!(other_owner.status(), StatusCode::BAD_REQUEST);
    assert_eq!(json(other_owner).await["error"]["code"], "invalid_cursor");

    let v1_page = json(
        send(
            &fixture.app,
            "/api/v1/sync/projects?limit=1",
            Some(OWNER_PAT),
            None,
        )
        .await,
    )
    .await;
    let v1_cursor = v1_page["next_cursor"].as_str().unwrap();
    let version_mismatch = send(
        &fixture.app,
        &format!("/api/v2/sync/projects?cursor={v1_cursor}"),
        Some(OWNER_PAT),
        None,
    )
    .await;
    assert_eq!(version_mismatch.status(), StatusCode::BAD_REQUEST);
    assert_eq!(
        json(version_mismatch).await["error"]["code"],
        "invalid_cursor"
    );
}

#[tokio::test]
async fn v2_sync_defaults_to_fifty_and_caps_each_page() {
    let fixture = fixture().await;
    for index in 0..51 {
        insert_project(
            &fixture.pool,
            fixture.owner_id,
            &format!("Project {index}"),
            "2024-01-01T01:00:00Z",
            None,
            None,
        )
        .await;
    }

    let response = send(&fixture.app, "/api/v2/sync/projects", Some(OWNER_PAT), None).await;
    assert_eq!(response.status(), StatusCode::OK);
    let data = json(response).await["data"].clone();
    assert_eq!(data["updated"].as_array().unwrap().len(), 50);
    assert_eq!(data["has_more"], true);
    assert!(data["next_cursor"].as_str().is_some());
}

#[tokio::test]
async fn v2_sync_uses_browser_reads_but_invalid_bearer_never_falls_back_to_cookie() {
    let fixture = fixture().await;
    insert_project(
        &fixture.pool,
        fixture.admin_id,
        "Admin project",
        "2024-01-01T01:00:00Z",
        None,
        None,
    )
    .await;

    let browser = send(
        &fixture.app,
        "/api/v2/sync/projects",
        None,
        Some(ADMIN_SESSION),
    )
    .await;
    assert_eq!(browser.status(), StatusCode::OK);
    assert_eq!(
        json(browser).await["data"]["updated"][0]["name"],
        "Admin project"
    );

    let invalid_bearer_with_valid_cookie = send(
        &fixture.app,
        "/api/v2/sync/projects",
        Some("synthetic-invalid-sync-pat"),
        Some(ADMIN_SESSION),
    )
    .await;
    assert_eq!(
        invalid_bearer_with_valid_cookie.status(),
        StatusCode::UNAUTHORIZED
    );
    assert_eq!(
        json(invalid_bearer_with_valid_cookie).await["error"]["code"],
        "session_expired"
    );

    let anonymous = send(&fixture.app, "/api/v2/sync/projects", None, None).await;
    assert_eq!(anonymous.status(), StatusCode::UNAUTHORIZED);
}

#[tokio::test]
async fn v2_sync_media_projection_tracks_title_step_and_photo_reorders() {
    let fixture = fixture().await;
    let project_id = insert_project(
        &fixture.pool,
        fixture.owner_id,
        "Ordered project",
        "2024-01-01T01:00:00Z",
        Some("Project description"),
        None,
    )
    .await;
    let step_id: i64 = sqlx::query_scalar(
        "INSERT INTO project_steps (project_id,title,step_number) \
         VALUES (?,'Step',1) RETURNING id",
    )
    .bind(project_id)
    .fetch_one(&fixture.pool)
    .await
    .unwrap();
    let yarn_id = insert_yarn(
        &fixture.pool,
        fixture.owner_id,
        "Ordered yarn",
        "2024-01-01T01:00:00Z",
        None,
    )
    .await;
    for media in [
        OrderedMediaFixture {
            project_id: Some(project_id),
            yarn_id: None,
            step_id: None,
            kind: "title",
            public_id: 1001,
            position: 0,
            is_primary: true,
            is_project_cover: true,
        },
        OrderedMediaFixture {
            project_id: Some(project_id),
            yarn_id: None,
            step_id: None,
            kind: "title",
            public_id: 1002,
            position: 1,
            is_primary: false,
            is_project_cover: false,
        },
        OrderedMediaFixture {
            project_id: Some(project_id),
            yarn_id: None,
            step_id: Some(step_id),
            kind: "step",
            public_id: 1101,
            position: 0,
            is_primary: false,
            is_project_cover: false,
        },
        OrderedMediaFixture {
            project_id: Some(project_id),
            yarn_id: None,
            step_id: Some(step_id),
            kind: "step",
            public_id: 1102,
            position: 1,
            is_primary: false,
            is_project_cover: false,
        },
        OrderedMediaFixture {
            project_id: None,
            yarn_id: Some(yarn_id),
            step_id: None,
            kind: "photo",
            public_id: 2001,
            position: 0,
            is_primary: true,
            is_project_cover: false,
        },
        OrderedMediaFixture {
            project_id: None,
            yarn_id: Some(yarn_id),
            step_id: None,
            kind: "photo",
            public_id: 2002,
            position: 1,
            is_primary: false,
            is_project_cover: false,
        },
    ] {
        insert_ordered_media(&fixture.pool, fixture.owner_id, media).await;
    }

    let before_step_order: String =
        sqlx::query_scalar("SELECT updated_at FROM projects WHERE id = ?")
            .bind(project_id)
            .fetch_one(&fixture.pool)
            .await
            .unwrap();
    let step_reorder = put_json(
        &fixture.app,
        &format!("/api/v2/projects/{project_id}/steps/{step_id}/images/order"),
        OWNER_PAT,
        serde_json::json!({"ids": [1102, 1101]}),
    )
    .await;
    assert_eq!(step_reorder.status(), StatusCode::OK);
    let after_step_order: String =
        sqlx::query_scalar("SELECT updated_at FROM projects WHERE id = ?")
            .bind(project_id)
            .fetch_one(&fixture.pool)
            .await
            .unwrap();
    assert!(after_step_order > before_step_order);
    assert_project_sync_matches_detail(&fixture, project_id).await;
    let step_synced = json(
        send(
            &fixture.app,
            "/api/v2/sync/projects?since=2020-01-01T00%3A00%3A00Z",
            Some(OWNER_PAT),
            None,
        )
        .await,
    )
    .await;
    assert!(!step_synced["data"]["updated"][0]["preview_url"].is_null());
    assert_eq!(
        step_synced["data"]["updated"][0]["images"][0]["id"], 1001,
        "step ordering does not change the project cover"
    );
    assert_eq!(step_synced["data"]["updated"][0]["images"][2]["id"], 1102);
    assert_eq!(
        step_synced["data"]["updated"][0]["images"][2]["position"],
        0
    );

    let title_reorder = put_json(
        &fixture.app,
        &format!("/api/v2/projects/{project_id}/images/order"),
        OWNER_PAT,
        serde_json::json!({"ids": [1002, 1001]}),
    )
    .await;
    assert_eq!(title_reorder.status(), StatusCode::OK);
    assert_project_sync_matches_detail(&fixture, project_id).await;
    let title_synced = json(
        send(
            &fixture.app,
            "/api/v2/sync/projects?since=2020-01-01T00%3A00%3A00Z",
            Some(OWNER_PAT),
            None,
        )
        .await,
    )
    .await;
    assert_eq!(title_synced["data"]["updated"][0]["images"][0]["id"], 1002);
    assert!(
        title_synced["data"]["updated"][0]["images"][0]["is_primary"]
            .as_bool()
            .unwrap()
    );
    assert_eq!(
        title_synced["data"]["updated"][0]["images"][1]["position"],
        1
    );

    let before_yarn_order: String = sqlx::query_scalar("SELECT updated_at FROM yarns WHERE id = ?")
        .bind(yarn_id)
        .fetch_one(&fixture.pool)
        .await
        .unwrap();
    let photo_reorder = put_json(
        &fixture.app,
        &format!("/api/v2/yarns/{yarn_id}/photos/order"),
        OWNER_PAT,
        serde_json::json!({"ids": [2002, 2001]}),
    )
    .await;
    assert_eq!(photo_reorder.status(), StatusCode::OK);
    let after_yarn_order: String = sqlx::query_scalar("SELECT updated_at FROM yarns WHERE id = ?")
        .bind(yarn_id)
        .fetch_one(&fixture.pool)
        .await
        .unwrap();
    assert!(after_yarn_order > before_yarn_order);
    let yarn_detail = json(
        send(
            &fixture.app,
            &format!("/api/v2/yarns/{yarn_id}"),
            Some(OWNER_PAT),
            None,
        )
        .await,
    )
    .await;
    let yarn_sync = json(
        send(
            &fixture.app,
            "/api/v2/sync/yarns?since=2020-01-01T00%3A00%3A00Z",
            Some(OWNER_PAT),
            None,
        )
        .await,
    )
    .await;
    let yarn_synced = &yarn_sync["data"]["updated"][0];
    assert_eq!(yarn_synced["photos"], yarn_detail["data"]["photos"]);
    assert_eq!(
        yarn_synced["preview_url"],
        yarn_detail["data"]["preview_url"]
    );
    assert_eq!(yarn_synced["photos"][0]["id"], 2002);
    assert_eq!(yarn_synced["photos"][0]["position"], 0);
    assert!(yarn_synced["photos"][0]["is_primary"].as_bool().unwrap());

    let audit_count_before: i64 = sqlx::query_scalar(
        "SELECT COUNT(*) FROM audit_events WHERE entity_type = 'project' AND entity_id = ?",
    )
    .bind(project_id)
    .fetch_one(&fixture.pool)
    .await
    .unwrap();
    let updated_at_before_invalid: String =
        sqlx::query_scalar("SELECT updated_at FROM projects WHERE id = ?")
            .bind(project_id)
            .fetch_one(&fixture.pool)
            .await
            .unwrap();
    let invalid = put_json(
        &fixture.app,
        &format!("/api/v2/projects/{project_id}/images/order"),
        OWNER_PAT,
        serde_json::json!({"ids": [1002, 1002]}),
    )
    .await;
    assert_eq!(invalid.status(), StatusCode::UNPROCESSABLE_ENTITY);
    let current_updated_at: String =
        sqlx::query_scalar("SELECT updated_at FROM projects WHERE id = ?")
            .bind(project_id)
            .fetch_one(&fixture.pool)
            .await
            .unwrap();
    assert_eq!(current_updated_at, updated_at_before_invalid);
    let audit_count_after: i64 = sqlx::query_scalar(
        "SELECT COUNT(*) FROM audit_events WHERE entity_type = 'project' AND entity_id = ?",
    )
    .bind(project_id)
    .fetch_one(&fixture.pool)
    .await
    .unwrap();
    assert_eq!(audit_count_after, audit_count_before);
    let no_new_sync = json(
        send(
            &fixture.app,
            &format!(
                "/api/v2/sync/projects?since={}",
                encode_query_value(&updated_at_before_invalid)
            ),
            Some(OWNER_PAT),
            None,
        )
        .await,
    )
    .await;
    assert_eq!(no_new_sync["data"]["updated"], serde_json::json!([]));
}
