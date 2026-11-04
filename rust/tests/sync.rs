use axum::body::Body;
use axum::http::{Request, StatusCode};
use chrono::{DateTime, FixedOffset, SecondsFormat};
use http_body_util::BodyExt;
use sha2::{Digest, Sha256};
use sqlx::SqlitePool;
use stricknani_server::{app_with_state, db};
use tower::ServiceExt;

const OWNER_TOKEN: &str = "sync-owner-token";
const OTHER_TOKEN: &str = "sync-other-token";

async fn fixture() -> (axum::Router, SqlitePool, i64, i64) {
    let pool = db::connect("sqlite::memory:")
        .await
        .expect("in-memory database should migrate");
    let owner_id = seed_user(&pool, "owner@example.test", OWNER_TOKEN).await;
    let other_id = seed_user(&pool, "other@example.test", OTHER_TOKEN).await;
    (
        app_with_state(db::AppState::new(pool.clone())),
        pool,
        owner_id,
        other_id,
    )
}

async fn seed_user(pool: &SqlitePool, email: &str, token: &str) -> i64 {
    let user_id =
        sqlx::query("INSERT INTO users (email, password_hash) VALUES (?, 'test-only-hash')")
            .bind(email)
            .execute(pool)
            .await
            .expect("test account should be inserted")
            .last_insert_rowid();
    let token_hash = hex::encode(Sha256::digest(token.as_bytes()));
    sqlx::query(
        "INSERT INTO api_tokens (user_id, name, token_hash, created_at) \
         VALUES (?, 'test', ?, unixepoch())",
    )
    .bind(user_id)
    .bind(token_hash)
    .execute(pool)
    .await
    .expect("test token should be inserted");
    user_id
}

async fn insert_project(pool: &SqlitePool, owner_id: i64, name: &str, at: &str) -> i64 {
    let at = stored_timestamp(at);
    sqlx::query(
        "INSERT INTO projects (owner_id, name, tags, created_at, updated_at) \
         VALUES (?, ?, '[]', ?, ?)",
    )
    .bind(owner_id)
    .bind(name)
    .bind(&at)
    .bind(&at)
    .execute(pool)
    .await
    .expect("project should be inserted")
    .last_insert_rowid()
}

async fn insert_yarn(pool: &SqlitePool, owner_id: i64, name: &str, at: &str) -> i64 {
    let at = stored_timestamp(at);
    sqlx::query("INSERT INTO yarns (owner_id, name, created_at, updated_at) VALUES (?, ?, ?, ?)")
        .bind(owner_id)
        .bind(name)
        .bind(&at)
        .bind(&at)
        .execute(pool)
        .await
        .expect("yarn should be inserted")
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
        "INSERT INTO deletion_events (owner_id, entity_type, entity_id, changed_at) \
         VALUES (?, ?, ?, ?)",
    )
    .bind(owner_id)
    .bind(kind)
    .bind(entity_id)
    .bind(&at)
    .execute(pool)
    .await
    .expect("deletion event should be inserted")
    .last_insert_rowid()
}

fn get(path: &str, token: Option<&str>) -> Request<Body> {
    let mut request = Request::get(path);
    if let Some(token) = token {
        request = request.header("authorization", format!("Bearer {token}"));
    }
    request.body(Body::empty()).expect("request is valid")
}

fn json_request(method: &str, path: &str, token: &str, body: serde_json::Value) -> Request<Body> {
    Request::builder()
        .method(method)
        .uri(path)
        .header("authorization", format!("Bearer {token}"))
        .header("content-type", "application/json")
        .body(Body::from(body.to_string()))
        .expect("request is valid")
}

fn stored_timestamp(value: &str) -> String {
    DateTime::parse_from_rfc3339(value)
        .expect("test timestamp is RFC3339")
        .with_timezone(&chrono::Utc)
        .to_rfc3339_opts(SecondsFormat::Micros, true)
}

async fn json(response: axum::response::Response) -> serde_json::Value {
    let body = response
        .into_body()
        .collect()
        .await
        .expect("response body is readable")
        .to_bytes();
    serde_json::from_slice(&body).expect("response is JSON")
}

async fn send(app: &axum::Router, path: &str, token: Option<&str>) -> axum::response::Response {
    app.clone()
        .oneshot(get(path, token))
        .await
        .expect("router should respond")
}

#[tokio::test]
async fn full_and_delta_sync_are_owner_scoped_and_normalize_offset_since() {
    let (app, pool, owner_id, other_id) = fixture().await;
    let owner_project = insert_project(
        &pool,
        owner_id,
        "Owner project",
        "2024-01-01T03:00:00.000000+00:00",
    )
    .await;
    let other_project = insert_project(
        &pool,
        other_id,
        "Other project",
        "2024-01-01T03:00:00.000000+00:00",
    )
    .await;
    let owner_yarn = insert_yarn(
        &pool,
        owner_id,
        "Owner yarn",
        "2024-01-01T03:00:00.000000+00:00",
    )
    .await;
    let other_yarn = insert_yarn(
        &pool,
        other_id,
        "Other yarn",
        "2024-01-01T03:00:00.000000+00:00",
    )
    .await;
    insert_deletion(
        &pool,
        owner_id,
        "project",
        80_001,
        "2024-01-01T04:00:00.000000+00:00",
    )
    .await;
    insert_deletion(
        &pool,
        other_id,
        "project",
        80_002,
        "2024-01-01T04:00:00.000000+00:00",
    )
    .await;
    insert_deletion(
        &pool,
        owner_id,
        "yarn",
        90_001,
        "2024-01-01T04:00:00.000000+00:00",
    )
    .await;
    insert_deletion(
        &pool,
        other_id,
        "yarn",
        90_002,
        "2024-01-01T04:00:00.000000+00:00",
    )
    .await;

    for (entity, owner_id, other_id, owner_token, other_token, own_deleted, other_deleted) in [
        (
            "projects",
            owner_project,
            other_project,
            OWNER_TOKEN,
            OTHER_TOKEN,
            80_001,
            80_002,
        ),
        (
            "yarns",
            owner_yarn,
            other_yarn,
            OWNER_TOKEN,
            OTHER_TOKEN,
            90_001,
            90_002,
        ),
    ] {
        let path = format!("/api/v1/sync/{entity}");
        let full = send(&app, &path, Some(owner_token)).await;
        assert_eq!(full.status(), StatusCode::OK);
        let full = json(full).await;
        assert_eq!(full["updated"].as_array().map(Vec::len), Some(1));
        assert_eq!(full["updated"][0]["id"], owner_id);
        assert_eq!(full["deleted_ids"], serde_json::json!([]));
        assert_eq!(full["full_resync_required"], false);
        let server_time = full["server_time"]
            .as_str()
            .expect("server_time is a string");
        let parsed = DateTime::parse_from_rfc3339(server_time).expect("RFC3339 timestamp");
        assert_eq!(parsed.offset().local_minus_utc(), 0);

        // This offset is exactly 02:00 UTC. Both data timestamps and tombstones
        // after that instant must appear, regardless of the supplied offset.
        let delta_path = format!("/api/v1/sync/{entity}?since=2024-01-01T04%3A00%3A00%2B02%3A00");
        let delta = json(send(&app, &delta_path, Some(owner_token)).await).await;
        assert_eq!(delta["updated"].as_array().map(Vec::len), Some(1));
        assert_eq!(delta["updated"][0]["id"], owner_id);
        assert_eq!(delta["deleted_ids"], serde_json::json!([own_deleted]));

        let naive_path = format!("/api/v1/sync/{entity}?since=2024-01-01T02%3A00%3A00");
        let naive_delta = json(send(&app, &naive_path, Some(owner_token)).await).await;
        assert_eq!(naive_delta["updated"], delta["updated"]);
        assert_eq!(naive_delta["deleted_ids"], delta["deleted_ids"]);

        let other_delta = json(send(&app, &delta_path, Some(other_token)).await).await;
        assert_eq!(other_delta["updated"].as_array().map(Vec::len), Some(1));
        assert_eq!(other_delta["updated"][0]["id"], other_id);
        assert_eq!(
            other_delta["deleted_ids"],
            serde_json::json!([other_deleted])
        );
    }

    let unauthorized = send(&app, "/api/v1/sync/projects", None).await;
    assert_eq!(unauthorized.status(), StatusCode::UNAUTHORIZED);
}

#[tokio::test]
async fn bounded_sync_merges_updates_and_deletes_without_duplicates_or_snapshot_drift() {
    let (app, pool, owner_id, _) = fixture().await;
    let first = insert_project(&pool, owner_id, "First", "2024-01-01T01:00:00.000000+00:00").await;
    let second = insert_project(
        &pool,
        owner_id,
        "Second",
        "2024-01-01T02:00:00.000000+00:00",
    )
    .await;
    insert_deletion(
        &pool,
        owner_id,
        "project",
        70_001,
        "2024-01-01T01:00:00.000000+00:00",
    )
    .await;
    insert_deletion(
        &pool,
        owner_id,
        "project",
        70_002,
        "2024-01-01T02:00:00.000000+00:00",
    )
    .await;

    let mut response = send(
        &app,
        "/api/v1/sync/projects?since=2020-01-01T00%3A00%3A00Z&limit=1",
        Some(OWNER_TOKEN),
    )
    .await;
    let first_page = json(response).await;
    assert_eq!(first_page["updated"][0]["id"], first);
    assert_eq!(first_page["deleted_ids"], serde_json::json!([]));
    assert_eq!(first_page["has_more"], true);
    let snapshot = first_page["server_time"].clone();
    let mut next_cursor = first_page["next_cursor"]
        .as_str()
        .expect("first page has cursor")
        .to_owned();
    let mut updated_ids = vec![first];
    let mut deleted_ids = Vec::new();

    let late = insert_project(
        &pool,
        owner_id,
        "After snapshot",
        "2099-01-01T00:00:00.000000+00:00",
    )
    .await;

    let mut page_count = 1;
    loop {
        response = send(
            &app,
            &format!("/api/v1/sync/projects?cursor={next_cursor}"),
            Some(OWNER_TOKEN),
        )
        .await;
        assert_eq!(response.status(), StatusCode::OK);
        let page = json(response).await;
        assert_eq!(page["server_time"], snapshot);
        updated_ids.extend(
            page["updated"]
                .as_array()
                .expect("updated is an array")
                .iter()
                .map(|entity| entity["id"].as_i64().expect("id is numeric")),
        );
        deleted_ids.extend(
            page["deleted_ids"]
                .as_array()
                .expect("deleted_ids is an array")
                .iter()
                .map(|id| id.as_i64().expect("deleted id is numeric")),
        );
        page_count += 1;
        assert!(page_count <= 5, "cursor must make forward progress");
        if !page["has_more"].as_bool().expect("has_more is boolean") {
            assert!(page["next_cursor"].is_null());
            break;
        }
        next_cursor = page["next_cursor"]
            .as_str()
            .expect("more pages have a cursor")
            .to_owned();
    }

    assert_eq!(page_count, 4);
    assert_eq!(updated_ids, vec![first, second]);
    assert_eq!(deleted_ids, vec![70_001, 70_002]);
    assert!(!updated_ids.contains(&late));
}

#[tokio::test]
async fn actual_yarn_timestamps_round_trip_through_offset_since_and_delta_update() {
    let (app, _, _, _) = fixture().await;
    let created = app
        .clone()
        .oneshot(json_request(
            "POST",
            "/api/v1/yarns",
            OWNER_TOKEN,
            serde_json::json!({"name":"Clock test"}),
        ))
        .await
        .expect("router should respond");
    assert_eq!(created.status(), StatusCode::CREATED);
    let created = json(created).await;
    let yarn_id = created["id"].as_i64().expect("yarn ID is numeric");
    let original_updated_at = created["updated_at"]
        .as_str()
        .expect("updated_at is a string");
    let original =
        DateTime::parse_from_rfc3339(original_updated_at).expect("yarn updated_at is RFC3339");
    let offset = FixedOffset::east_opt(2 * 60 * 60).expect("offset is valid");
    let offset_since = original
        .with_timezone(&offset)
        .to_rfc3339_opts(SecondsFormat::Micros, false);
    let query_since = offset_since.replace(':', "%3A").replace('+', "%2B");

    let unchanged = send(
        &app,
        &format!("/api/v1/sync/yarns?since={query_since}"),
        Some(OWNER_TOKEN),
    )
    .await;
    assert_eq!(unchanged.status(), StatusCode::OK);
    let unchanged = json(unchanged).await;
    assert_eq!(unchanged["updated"], serde_json::json!([]));

    let update = app
        .clone()
        .oneshot(json_request(
            "PUT",
            &format!("/api/v1/yarns/{yarn_id}"),
            OWNER_TOKEN,
            serde_json::json!({"name":"Clock test updated"}),
        ))
        .await
        .expect("router should respond");
    assert_eq!(update.status(), StatusCode::OK);

    let changed = send(
        &app,
        &format!("/api/v1/sync/yarns?since={query_since}"),
        Some(OWNER_TOKEN),
    )
    .await;
    assert_eq!(changed.status(), StatusCode::OK);
    let changed = json(changed).await;
    assert_eq!(changed["updated"].as_array().map(Vec::len), Some(1));
    assert_eq!(changed["updated"][0]["id"], yarn_id);
}

#[tokio::test]
async fn cursors_validate_binding_exclusivity_limits_and_size() {
    let (app, pool, owner_id, other_id) = fixture().await;
    insert_project(&pool, owner_id, "First", "2024-01-01T01:00:00.000000+00:00").await;
    insert_project(
        &pool,
        owner_id,
        "Second",
        "2024-01-01T02:00:00.000000+00:00",
    )
    .await;
    let response = send(&app, "/api/v1/sync/projects?limit=1", Some(OWNER_TOKEN)).await;
    let payload = json(response).await;
    let cursor = payload["next_cursor"]
        .as_str()
        .expect("multiple results create a cursor");

    let invalid_cursor_paths = [
        "/api/v1/sync/projects?cursor=not-a-cursor".to_owned(),
        format!("/api/v1/sync/projects?cursor={cursor}&since=2024-01-01T00%3A00%3A00Z"),
        format!("/api/v1/sync/projects?cursor={cursor}"),
    ];
    for path in invalid_cursor_paths {
        let response = send(&app, &path, Some(OTHER_TOKEN)).await;
        assert_eq!(response.status(), StatusCode::BAD_REQUEST);
    }

    let cross_entity = send(
        &app,
        &format!("/api/v1/sync/yarns?cursor={cursor}"),
        Some(OWNER_TOKEN),
    )
    .await;
    assert_eq!(cross_entity.status(), StatusCode::BAD_REQUEST);

    for limit in ["0", "51", "invalid"] {
        let response = send(
            &app,
            &format!("/api/v1/sync/projects?limit={limit}"),
            Some(OWNER_TOKEN),
        )
        .await;
        assert_eq!(response.status(), StatusCode::UNPROCESSABLE_ENTITY);
    }

    let invalid_since = send(
        &app,
        "/api/v1/sync/projects?since=not-a-timestamp",
        Some(OWNER_TOKEN),
    )
    .await;
    assert_eq!(invalid_since.status(), StatusCode::UNPROCESSABLE_ENTITY);

    let oversized_cursor = "a".repeat(4097);
    let response = send(
        &app,
        &format!("/api/v1/sync/projects?cursor={oversized_cursor}"),
        Some(OWNER_TOKEN),
    )
    .await;
    assert_eq!(response.status(), StatusCode::UNPROCESSABLE_ENTITY);

    assert_ne!(owner_id, other_id);
}
