use axum::{
    body::Body,
    http::{Request, StatusCode},
};
use http_body_util::BodyExt;
use sha2::{Digest, Sha256};
use sqlx::{Connection, SqlitePool};
use stricknani_server::{app_with_state, db};
use tower::ServiceExt;

const OWNER_PAT: &str = "category-sync-owner-api-pat";
const OTHER_PAT: &str = "category-sync-other-api-pat";
const OWNER_SESSION: &str = "category-sync-owner-browser-session";

struct Fixture {
    app: axum::Router,
    pool: SqlitePool,
    owner_id: i64,
    other_id: i64,
}

async fn fixture() -> Fixture {
    let pool = db::connect("sqlite::memory:")
        .await
        .expect("database migrates");
    let owner_id = seed_user(&pool, "category-sync-owner@example.test", OWNER_PAT).await;
    seed_token(&pool, owner_id, OWNER_SESSION, "browser").await;
    let other_id = seed_user(&pool, "category-sync-other@example.test", OTHER_PAT).await;
    Fixture {
        app: app_with_state(db::AppState::new(pool.clone()).with_secure_cookies(true)),
        pool,
        owner_id,
        other_id,
    }
}

async fn seed_user(pool: &SqlitePool, email: &str, token: &str) -> i64 {
    let user_id =
        sqlx::query("INSERT INTO users (email, password_hash) VALUES (?, 'test-only-hash')")
            .bind(email)
            .execute(pool)
            .await
            .expect("test user is inserted")
            .last_insert_rowid();
    seed_token(pool, user_id, token, "api").await;
    user_id
}

async fn seed_token(pool: &SqlitePool, user_id: i64, token: &str, purpose: &str) {
    sqlx::query(
        "INSERT INTO api_tokens (user_id, name, token_hash, created_at, expires_at, purpose) \
         VALUES (?, 'category sync fixture', ?, unixepoch(), unixepoch() + 86400, ?)",
    )
    .bind(user_id)
    .bind(hex::encode(Sha256::digest(token.as_bytes())))
    .bind(purpose)
    .execute(pool)
    .await
    .expect("test token is inserted");
}

async fn insert_category(pool: &SqlitePool, owner_id: i64, name: &str, at: &str) -> i64 {
    let id = sqlx::query("INSERT INTO categories (user_id, name, created_at) VALUES (?, ?, NULL)")
        .bind(owner_id)
        .bind(name)
        .execute(pool)
        .await
        .expect("category is inserted")
        .last_insert_rowid();
    sqlx::query("UPDATE category_sync_metadata SET updated_at = ? WHERE category_id = ?")
        .bind(at)
        .bind(id)
        .execute(pool)
        .await
        .expect("sync timestamp is set");
    id
}

async fn insert_category_now(pool: &SqlitePool, owner_id: i64, name: &str) -> i64 {
    sqlx::query("INSERT INTO categories (user_id, name, created_at) VALUES (?, ?, NULL)")
        .bind(owner_id)
        .bind(name)
        .execute(pool)
        .await
        .expect("category is inserted")
        .last_insert_rowid()
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

fn json_request(method: &str, path: &str, token: &str, body: serde_json::Value) -> Request<Body> {
    Request::builder()
        .method(method)
        .uri(path)
        .header("authorization", format!("Bearer {token}"))
        .header("content-type", "application/json")
        .body(Body::from(body.to_string()))
        .unwrap()
}

async fn send(app: &axum::Router, request: Request<Body>) -> axum::response::Response {
    app.clone().oneshot(request).await.expect("router responds")
}

async fn get_path(
    app: &axum::Router,
    path: &str,
    bearer: Option<&str>,
    cookie: Option<&str>,
) -> axum::response::Response {
    send(app, get(path, bearer, cookie)).await
}

async fn json(response: axum::response::Response) -> serde_json::Value {
    serde_json::from_slice(&response.into_body().collect().await.unwrap().to_bytes()).unwrap()
}

#[tokio::test]
async fn category_sync_is_owner_scoped_and_paginates_stable_ties() {
    let fixture = fixture().await;
    let first_id = insert_category(
        &fixture.pool,
        fixture.owner_id,
        "First",
        "2024-01-01T00:00:00.000000Z",
    )
    .await;
    let second_id = insert_category(
        &fixture.pool,
        fixture.owner_id,
        "Second",
        "2024-01-01T00:00:00.000000Z",
    )
    .await;
    insert_category(
        &fixture.pool,
        fixture.other_id,
        "Private to other owner",
        "2024-01-01T00:00:00.000000Z",
    )
    .await;

    let first = get_path(
        &fixture.app,
        "/api/v2/sync/categories?since=2023-12-31T23%3A59%3A59Z&limit=1",
        Some(OWNER_PAT),
        None,
    )
    .await;
    assert_eq!(first.status(), StatusCode::OK);
    assert!(
        first.headers()["cache-control"]
            .to_str()
            .unwrap()
            .contains("no-store")
    );
    let first_data = json(first).await["data"].clone();
    assert_eq!(first_data["updated"].as_array().unwrap().len(), 1);
    assert_eq!(first_data["updated"][0]["id"], first_id);
    assert_eq!(first_data["updated"][0]["name"], "First");
    assert_eq!(first_data["full_resync_required"], false);
    assert_eq!(first_data["has_more"], true);
    let cursor = first_data["next_cursor"].as_str().unwrap();

    let second = get_path(
        &fixture.app,
        &format!("/api/v2/sync/categories?cursor={cursor}"),
        Some(OWNER_PAT),
        None,
    )
    .await;
    assert_eq!(second.status(), StatusCode::OK);
    let second_data = json(second).await["data"].clone();
    assert_eq!(second_data["updated"][0]["id"], second_id);
    assert_eq!(second_data["has_more"], false);
    assert!(second_data["next_cursor"].is_null());

    let other = get_path(
        &fixture.app,
        "/api/v2/sync/categories?since=2023-12-31T23%3A59%3A59Z",
        Some(OTHER_PAT),
        None,
    )
    .await;
    let other_data = json(other).await["data"].clone();
    assert_eq!(other_data["updated"].as_array().unwrap().len(), 1);
    assert_eq!(other_data["updated"][0]["name"], "Private to other owner");
}

#[tokio::test]
async fn category_sync_defaults_to_fifty_combined_events() {
    let fixture = fixture().await;
    for index in 0..51 {
        insert_category(
            &fixture.pool,
            fixture.owner_id,
            &format!("Category {index}"),
            "2024-01-01T00:00:00.000000Z",
        )
        .await;
    }
    let response = get_path(
        &fixture.app,
        "/api/v2/sync/categories",
        Some(OWNER_PAT),
        None,
    )
    .await;
    assert_eq!(response.status(), StatusCode::OK);
    let data = json(response).await["data"].clone();
    assert_eq!(data["updated"].as_array().unwrap().len(), 50);
    assert_eq!(data["has_more"], true);
    assert!(data["next_cursor"].as_str().is_some());
}

#[tokio::test]
async fn category_sync_full_page_includes_a_deliberately_clock_ahead_row() {
    let fixture = fixture().await;
    let id = insert_category(
        &fixture.pool,
        fixture.owner_id,
        "Ahead of wall clock",
        "2099-01-01T00:00:00.000000Z",
    )
    .await;
    sqlx::query("UPDATE category_sync_clock SET changed_at = ? WHERE owner_id = ?")
        .bind("2099-01-01T00:00:00.000000Z")
        .bind(fixture.owner_id)
        .execute(&fixture.pool)
        .await
        .unwrap();

    let response = get_path(
        &fixture.app,
        "/api/v2/sync/categories",
        Some(OWNER_PAT),
        None,
    )
    .await;
    assert_eq!(response.status(), StatusCode::OK);
    let data = json(response).await["data"].clone();
    assert_eq!(data["server_time"], "2099-01-01T00:00:00.000000Z");
    assert_eq!(data["updated"][0]["id"], id);
}

#[tokio::test]
async fn category_sync_returns_tombstones_and_late_writes_wait_for_next_snapshot() {
    let fixture = fixture().await;
    let deleted_id = insert_category(
        &fixture.pool,
        fixture.owner_id,
        "Deleted",
        "2024-01-01T00:00:00.000000Z",
    )
    .await;
    let remaining_id = insert_category(
        &fixture.pool,
        fixture.owner_id,
        "Remaining",
        "2024-01-01T00:00:00.000000Z",
    )
    .await;
    let response = get_path(
        &fixture.app,
        "/api/v2/sync/categories?since=2023-12-31T23%3A59%3A59Z&limit=1",
        Some(OWNER_PAT),
        None,
    )
    .await;
    let page = json(response).await["data"].clone();
    let cursor = page["next_cursor"].as_str().unwrap().to_owned();
    assert_eq!(page["updated"][0]["id"], deleted_id);

    sqlx::query("DELETE FROM categories WHERE id = ? AND user_id = ?")
        .bind(deleted_id)
        .bind(fixture.owner_id)
        .execute(&fixture.pool)
        .await
        .unwrap();
    let late_id = insert_category_now(&fixture.pool, fixture.owner_id, "Late").await;

    let continuation = get_path(
        &fixture.app,
        &format!("/api/v2/sync/categories?cursor={cursor}"),
        Some(OWNER_PAT),
        None,
    )
    .await;
    assert_eq!(continuation.status(), StatusCode::OK);
    let continued_data = json(continuation).await["data"].clone();
    assert_eq!(continued_data["deleted_ids"].as_array().unwrap().len(), 0);
    assert_eq!(continued_data["updated"][0]["id"], remaining_id);
    assert_eq!(continued_data["server_time"], page["server_time"]);
    assert_eq!(continued_data["has_more"], false);

    let watermark = page["server_time"].as_str().unwrap();
    let query = url::form_urlencoded::Serializer::new(String::new())
        .append_pair("since", watermark)
        .finish();
    let next = get_path(
        &fixture.app,
        &format!("/api/v2/sync/categories?{query}"),
        Some(OWNER_PAT),
        None,
    )
    .await;
    let next_data = json(next).await["data"].clone();
    assert_eq!(next_data["updated"][0]["id"], late_id);
    assert_eq!(next_data["deleted_ids"], serde_json::json!([deleted_id]));
}

#[tokio::test]
async fn category_writes_after_server_time_advance_within_the_same_millisecond() {
    let fixture = fixture().await;
    let initial = get_path(
        &fixture.app,
        "/api/v2/sync/categories",
        Some(OWNER_PAT),
        None,
    )
    .await;
    let previous_server_time = json(initial).await["data"]["server_time"]
        .as_str()
        .unwrap()
        .to_owned();
    sqlx::query("UPDATE category_sync_clock SET changed_at = ? WHERE owner_id = ?")
        .bind("2099-01-01T00:00:00.000000Z")
        .bind(fixture.owner_id)
        .execute(&fixture.pool)
        .await
        .unwrap();

    let create = json_request(
        "POST",
        "/api/v1/categories",
        OWNER_PAT,
        serde_json::json!({"name":"Same millisecond"}),
    );
    let created = send(&fixture.app, create).await;
    assert_eq!(created.status(), StatusCode::CREATED);
    let category_id = json(created).await["id"].as_i64().unwrap();
    let immediate_full = get_path(
        &fixture.app,
        "/api/v2/sync/categories",
        Some(OWNER_PAT),
        None,
    )
    .await;
    assert_eq!(immediate_full.status(), StatusCode::OK);
    assert_eq!(
        json(immediate_full).await["data"]["updated"][0]["id"],
        category_id
    );
    let after_create: String =
        sqlx::query_scalar("SELECT updated_at FROM category_sync_metadata WHERE category_id = ?")
            .bind(category_id)
            .fetch_one(&fixture.pool)
            .await
            .unwrap();
    for name in ["Updated once", "Updated twice"] {
        let update = json_request(
            "PUT",
            &format!("/api/v1/categories/{category_id}"),
            OWNER_PAT,
            serde_json::json!({"name":name}),
        );
        assert_eq!(send(&fixture.app, update).await.status(), StatusCode::OK);
    }
    let after_updates: String =
        sqlx::query_scalar("SELECT updated_at FROM category_sync_metadata WHERE category_id = ?")
            .bind(category_id)
            .fetch_one(&fixture.pool)
            .await
            .unwrap();
    assert!(after_updates > after_create);

    let query = url::form_urlencoded::Serializer::new(String::new())
        .append_pair("since", &previous_server_time)
        .finish();
    let delta = get_path(
        &fixture.app,
        &format!("/api/v2/sync/categories?{query}"),
        Some(OWNER_PAT),
        None,
    )
    .await;
    assert_eq!(delta.status(), StatusCode::OK);
    let data = json(delta).await["data"].clone();
    assert_eq!(data["updated"].as_array().unwrap().len(), 1);
    assert_eq!(data["updated"][0]["id"], category_id);
    assert_eq!(data["updated"][0]["name"], "Updated twice");
    assert!(data["server_time"].as_str().unwrap() > previous_server_time.as_str());
}

#[tokio::test]
async fn category_sync_watermark_serializes_with_a_concurrent_writer() {
    let fixture = fixture().await;
    let initial = get_path(
        &fixture.app,
        "/api/v2/sync/categories",
        Some(OWNER_PAT),
        None,
    )
    .await;
    assert_eq!(initial.status(), StatusCode::OK);

    let mut connection = fixture.pool.acquire().await.unwrap();
    let mut transaction = connection.begin_with("BEGIN IMMEDIATE").await.unwrap();
    let app = fixture.app.clone();
    let mut pending_sync = tokio::spawn(async move {
        app.oneshot(get("/api/v2/sync/categories", Some(OWNER_PAT), None))
            .await
            .expect("router responds")
    });
    assert!(
        tokio::time::timeout(std::time::Duration::from_millis(10), &mut pending_sync)
            .await
            .is_err()
    );

    let category_id = sqlx::query(
        "INSERT INTO categories (user_id, name, created_at) VALUES (?, 'Concurrent', NULL)",
    )
    .bind(fixture.owner_id)
    .execute(&mut *transaction)
    .await
    .unwrap()
    .last_insert_rowid();
    transaction.commit().await.unwrap();
    drop(connection);

    let response = pending_sync.await.unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    assert_eq!(
        json(response).await["data"]["updated"][0]["id"],
        category_id
    );
}

#[tokio::test]
async fn category_owner_change_emits_old_owner_tombstone_and_new_owner_update() {
    let fixture = fixture().await;
    let category_id = insert_category(
        &fixture.pool,
        fixture.owner_id,
        "Transferred",
        "2024-01-01T00:00:00.000000Z",
    )
    .await;
    sqlx::query("UPDATE categories SET user_id = ? WHERE id = ?")
        .bind(fixture.other_id)
        .bind(category_id)
        .execute(&fixture.pool)
        .await
        .unwrap();

    let owner_page = get_path(
        &fixture.app,
        "/api/v2/sync/categories?since=2000-01-01T00%3A00%3A00Z",
        Some(OWNER_PAT),
        None,
    )
    .await;
    assert_eq!(
        json(owner_page).await["data"]["deleted_ids"],
        serde_json::json!([category_id])
    );

    let new_owner_page = get_path(
        &fixture.app,
        "/api/v2/sync/categories?since=2000-01-01T00%3A00%3A00Z",
        Some(OTHER_PAT),
        None,
    )
    .await;
    assert_eq!(
        json(new_owner_page).await["data"]["updated"][0]["id"],
        category_id
    );
}

#[tokio::test]
async fn category_sync_tracks_v1_crud_without_changing_v1_snapshot_contract() {
    let fixture = fixture().await;
    let create = json_request(
        "POST",
        "/api/v1/categories",
        OWNER_PAT,
        serde_json::json!({"name":"Wool"}),
    );
    let created = send(&fixture.app, create).await;
    assert_eq!(created.status(), StatusCode::CREATED);
    let category_id = json(created).await["id"].as_i64().unwrap();
    let created_at: String =
        sqlx::query_scalar("SELECT updated_at FROM category_sync_metadata WHERE category_id = ?")
            .bind(category_id)
            .fetch_one(&fixture.pool)
            .await
            .unwrap();

    let duplicate = json_request(
        "POST",
        "/api/v1/categories",
        OWNER_PAT,
        serde_json::json!({"name":" wool "}),
    );
    assert_eq!(
        send(&fixture.app, duplicate).await.status(),
        StatusCode::CREATED
    );
    let event_count: i64 =
        sqlx::query_scalar("SELECT COUNT(*) FROM category_sync_metadata WHERE category_id = ?")
            .bind(category_id)
            .fetch_one(&fixture.pool)
            .await
            .unwrap();
    assert_eq!(event_count, 1);
    let after_duplicate: String =
        sqlx::query_scalar("SELECT updated_at FROM category_sync_metadata WHERE category_id = ?")
            .bind(category_id)
            .fetch_one(&fixture.pool)
            .await
            .unwrap();
    assert_eq!(after_duplicate, created_at);

    let snapshot = get_path(
        &fixture.app,
        "/api/v1/sync/categories?since=ignored",
        Some(OWNER_PAT),
        None,
    )
    .await;
    assert_eq!(snapshot.status(), StatusCode::OK);
    let snapshot_body = json(snapshot).await;
    assert_eq!(snapshot_body["updated"][0]["id"], category_id);
    assert!(snapshot_body.get("has_more").is_none());

    let update = json_request(
        "PUT",
        &format!("/api/v1/categories/{category_id}"),
        OWNER_PAT,
        serde_json::json!({"name":"Cotton"}),
    );
    assert_eq!(send(&fixture.app, update).await.status(), StatusCode::OK);
    let delta = get_path(
        &fixture.app,
        "/api/v2/sync/categories?since=2000-01-01T00%3A00%3A00Z",
        Some(OWNER_PAT),
        None,
    )
    .await;
    assert_eq!(json(delta).await["data"]["updated"][0]["name"], "Cotton");

    let delete = get_path(
        &fixture.app,
        &format!("/api/v1/categories/{category_id}"),
        Some(OWNER_PAT),
        None,
    )
    .await;
    assert_eq!(delete.status(), StatusCode::METHOD_NOT_ALLOWED);
    let delete = Request::builder()
        .method("DELETE")
        .uri(format!("/api/v1/categories/{category_id}"))
        .header("authorization", format!("Bearer {OWNER_PAT}"))
        .body(Body::empty())
        .unwrap();
    assert_eq!(
        send(&fixture.app, delete).await.status(),
        StatusCode::NO_CONTENT
    );

    let tombstones = get_path(
        &fixture.app,
        "/api/v2/sync/categories?since=2000-01-01T00%3A00%3A00Z",
        Some(OWNER_PAT),
        None,
    )
    .await;
    assert_eq!(
        json(tombstones).await["data"]["deleted_ids"],
        serde_json::json!([category_id])
    );
}

#[tokio::test]
async fn category_sync_validates_queries_authentication_and_cursor_scope() {
    let fixture = fixture().await;
    insert_category(
        &fixture.pool,
        fixture.owner_id,
        "A",
        "2024-01-01T00:00:00.000000Z",
    )
    .await;
    insert_category(
        &fixture.pool,
        fixture.owner_id,
        "B",
        "2024-01-01T00:00:00.000000Z",
    )
    .await;
    let invalid_since = get_path(
        &fixture.app,
        "/api/v2/sync/categories?since=not-a-timestamp",
        Some(OWNER_PAT),
        None,
    )
    .await;
    assert_eq!(invalid_since.status(), StatusCode::UNPROCESSABLE_ENTITY);
    assert_eq!(
        json(invalid_since).await["error"]["fields"]["since"],
        "Expected a valid UTC timestamp"
    );

    let invalid_limit = get_path(
        &fixture.app,
        "/api/v2/sync/categories?limit=51",
        Some(OWNER_PAT),
        None,
    )
    .await;
    assert_eq!(invalid_limit.status(), StatusCode::UNPROCESSABLE_ENTITY);
    assert!(json(invalid_limit).await["error"]["fields"]["limit"].is_string());

    let invalid_query = get_path(
        &fixture.app,
        "/api/v2/sync/categories?since=2024-01-01T00%3A00%3A00Z&cursor=x",
        Some(OWNER_PAT),
        None,
    )
    .await;
    assert_eq!(invalid_query.status(), StatusCode::BAD_REQUEST);
    assert_eq!(json(invalid_query).await["error"]["code"], "invalid_query");

    let invalid_cursor = get_path(
        &fixture.app,
        "/api/v2/sync/categories?cursor=not-a-cursor",
        Some(OWNER_PAT),
        None,
    )
    .await;
    assert_eq!(invalid_cursor.status(), StatusCode::BAD_REQUEST);
    assert_eq!(
        json(invalid_cursor).await["error"]["code"],
        "invalid_cursor"
    );

    let owner_cursor_data = json(
        get_path(
            &fixture.app,
            "/api/v2/sync/categories?limit=1",
            Some(OWNER_PAT),
            None,
        )
        .await,
    )
    .await;
    let category_cursor = owner_cursor_data["data"]["next_cursor"].as_str().unwrap();
    let wrong_owner = get_path(
        &fixture.app,
        &format!("/api/v2/sync/categories?cursor={category_cursor}"),
        Some(OTHER_PAT),
        None,
    )
    .await;
    assert_eq!(wrong_owner.status(), StatusCode::BAD_REQUEST);
    assert_eq!(json(wrong_owner).await["error"]["code"], "invalid_cursor");

    for name in ["Project A", "Project B"] {
        sqlx::query(
            "INSERT INTO projects (owner_id, name, created_at, updated_at) VALUES (?, ?, ?, ?)",
        )
        .bind(fixture.owner_id)
        .bind(name)
        .bind("2024-01-01T00:00:00.000000Z")
        .bind("2024-01-01T00:00:00.000000Z")
        .execute(&fixture.pool)
        .await
        .unwrap();
    }
    let v1_cursor_page = json(
        get_path(
            &fixture.app,
            "/api/v1/sync/projects?limit=1",
            Some(OWNER_PAT),
            None,
        )
        .await,
    )
    .await;
    let v1_cursor = v1_cursor_page["next_cursor"].as_str().unwrap();
    let wrong_protocol = get_path(
        &fixture.app,
        &format!("/api/v2/sync/categories?cursor={v1_cursor}"),
        Some(OWNER_PAT),
        None,
    )
    .await;
    assert_eq!(wrong_protocol.status(), StatusCode::BAD_REQUEST);
    assert_eq!(
        json(wrong_protocol).await["error"]["code"],
        "invalid_cursor"
    );

    let project_cursor_page = json(
        get_path(
            &fixture.app,
            "/api/v2/sync/projects?limit=1",
            Some(OWNER_PAT),
            None,
        )
        .await,
    )
    .await;
    let project_cursor = project_cursor_page["data"]["next_cursor"].as_str().unwrap();
    let wrong_entity = get_path(
        &fixture.app,
        &format!("/api/v2/sync/categories?cursor={project_cursor}"),
        Some(OWNER_PAT),
        None,
    )
    .await;
    assert_eq!(wrong_entity.status(), StatusCode::BAD_REQUEST);
    assert_eq!(json(wrong_entity).await["error"]["code"], "invalid_cursor");

    let browser_only = get_path(
        &fixture.app,
        "/api/v2/sync/categories",
        None,
        Some(OWNER_SESSION),
    )
    .await;
    assert_eq!(browser_only.status(), StatusCode::OK);
    let invalid_bearer_with_cookie = get_path(
        &fixture.app,
        "/api/v2/sync/categories",
        Some("synthetic-invalid-category-sync-pat"),
        Some(OWNER_SESSION),
    )
    .await;
    assert_eq!(
        invalid_bearer_with_cookie.status(),
        StatusCode::UNAUTHORIZED
    );
}

#[tokio::test]
async fn migration_0016_upgrades_a_database_at_0015_and_backfills_categories() {
    // `connect` already applies every migration, so open a raw pool instead.
    let pool = sqlx::sqlite::SqlitePoolOptions::new()
        .max_connections(1)
        .connect("sqlite::memory:")
        .await
        .expect("memory database connects");
    let full = sqlx::migrate!();
    let up_to_0015 = sqlx::migrate::Migrator {
        migrations: full
            .migrations
            .iter()
            .filter(|migration| migration.version <= 15)
            .cloned()
            .collect::<Vec<_>>()
            .into(),
        ..sqlx::migrate::Migrator::DEFAULT
    };
    up_to_0015.run(&pool).await.expect("0015 schema applies");
    let has_sync: i64 = sqlx::query_scalar(
        "SELECT count(*) FROM sqlite_master WHERE name = 'category_sync_metadata'",
    )
    .fetch_one(&pool)
    .await
    .unwrap();
    assert_eq!(has_sync, 0, "0016 must not be applied yet");

    let owner = seed_user(&pool, "upgrade@example.test", "upgrade-token").await;
    sqlx::query("INSERT INTO categories (user_id, name, created_at) VALUES (?, 'Socks', NULL), (?, 'Shawls', '2020-01-01T00:00:00')")
        .bind(owner)
        .bind(owner)
        .execute(&pool)
        .await
        .unwrap();

    full.run(&pool)
        .await
        .expect("0016 upgrades a 0015 database");

    let metadata: i64 =
        sqlx::query_scalar("SELECT count(*) FROM category_sync_metadata WHERE owner_id = ?")
            .bind(owner)
            .fetch_one(&pool)
            .await
            .unwrap();
    assert_eq!(metadata, 2, "existing categories are backfilled");
    let clocks: i64 = sqlx::query_scalar("SELECT count(*) FROM category_sync_clock")
        .fetch_one(&pool)
        .await
        .unwrap();
    assert_eq!(clocks, 1);
    let legacy_null: i64 = sqlx::query_scalar(
        "SELECT count(*) FROM categories WHERE name = 'Socks' AND created_at IS NULL",
    )
    .fetch_one(&pool)
    .await
    .unwrap();
    assert_eq!(legacy_null, 1, "legacy created_at is untouched");
    sqlx::query("DELETE FROM categories WHERE name = 'Socks'")
        .execute(&pool)
        .await
        .unwrap();
    let tombstones: i64 = sqlx::query_scalar("SELECT count(*) FROM category_deletion_events")
        .fetch_one(&pool)
        .await
        .unwrap();
    assert_eq!(tombstones, 1, "post-upgrade deletes emit tombstones");
}

#[tokio::test]
async fn deleting_an_owner_cascades_categories_without_sync_rows() {
    let fx = fixture().await;
    let owner = seed_user(&fx.pool, "gone@example.test", "gone-token").await;
    insert_category_now(&fx.pool, owner, "Hats").await;
    sqlx::query("DELETE FROM users WHERE id = ?")
        .bind(owner)
        .execute(&fx.pool)
        .await
        .expect("owner deletion cascades cleanly");
    for table in [
        "categories",
        "category_sync_metadata",
        "category_sync_clock",
        "category_deletion_events",
    ] {
        let count: i64 = sqlx::query_scalar(&format!("SELECT count(*) FROM {table}"))
            .fetch_one(&fx.pool)
            .await
            .unwrap();
        assert_eq!(count, 0, "{table} is empty after owner deletion");
    }
}
