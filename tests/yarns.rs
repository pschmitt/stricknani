use axum::body::Body;
use axum::http::{Request, StatusCode};
use http_body_util::BodyExt;
use sha2::{Digest, Sha256};
use sqlx::SqlitePool;
use stricknani_server::{app_with_state, db};
use tower::ServiceExt;

const OWNER_TOKEN: &str = "yarns-owner-token";
const OTHER_TOKEN: &str = "yarns-other-token";

async fn app() -> (axum::Router, SqlitePool, i64, i64) {
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

fn request(method: &str, path: &str, token: &str, body: Option<String>) -> Request<Body> {
    let mut request = Request::builder()
        .method(method)
        .uri(path)
        .header("authorization", format!("Bearer {token}"));
    if body.is_some() {
        request = request.header("content-type", "application/json");
    }
    request
        .body(body.map_or_else(Body::empty, Body::from))
        .expect("request is valid")
}

async fn json_body(response: axum::response::Response) -> serde_json::Value {
    let body = response
        .into_body()
        .collect()
        .await
        .expect("response body is readable")
        .to_bytes();
    serde_json::from_slice(&body).expect("response is JSON")
}

fn write_body(name: &str, expected_updated_at: Option<&str>) -> String {
    let mut value = serde_json::json!({
        "name": name,
        "description": "  soft merino  ",
        "brand": "  Wool Co  ",
        "colorway": "Blue",
        "dye_lot": "Lot 7",
        "fiber_content": "Merino wool",
        "weight_category": "DK",
        "recommended_needles": "4 mm",
        "weight_grams": 100,
        "length_meters": 200,
        "notes": " test notes ",
        "link": "https://example.test/yarn",
        "is_ai_enhanced": true
    });
    if let Some(expected_updated_at) = expected_updated_at {
        value["expected_updated_at"] = serde_json::json!(expected_updated_at);
    }
    value.to_string()
}

#[tokio::test]
async fn yarn_crud_supports_cas_and_returns_current_state_on_conflict() {
    let (app, _, _, _) = app().await;

    let unauthenticated = app
        .clone()
        .oneshot(
            Request::get("/api/v1/yarns")
                .body(Body::empty())
                .expect("request is valid"),
        )
        .await
        .expect("router should respond");
    assert_eq!(unauthenticated.status(), StatusCode::UNAUTHORIZED);

    let created = app
        .clone()
        .oneshot(request(
            "POST",
            "/api/v1/yarns",
            OWNER_TOKEN,
            Some(write_body("  Blue wool  ", None)),
        ))
        .await
        .expect("router should respond");
    assert_eq!(created.status(), StatusCode::CREATED);
    let created = json_body(created).await;
    let id = created["id"].as_i64().expect("yarn ID is numeric");
    let original_updated_at = created["updated_at"]
        .as_str()
        .expect("updated_at is serialized")
        .to_owned();
    assert_eq!(created["name"], "Blue wool");
    assert_eq!(created["brand"], "Wool Co");
    assert_eq!(created["description"], "soft merino");
    assert_eq!(created["is_favorite"], false);
    assert_eq!(created["project_ids"], serde_json::json!([]));
    assert_eq!(created["photos"], serde_json::json!([]));
    assert!(original_updated_at.ends_with('Z'));
    chrono::DateTime::parse_from_rfc3339(&original_updated_at).unwrap();

    let other_get = app
        .clone()
        .oneshot(request(
            "GET",
            &format!("/api/v1/yarns/{id}"),
            OTHER_TOKEN,
            None,
        ))
        .await
        .expect("router should respond");
    assert_eq!(other_get.status(), StatusCode::NOT_FOUND);

    let updated = app
        .clone()
        .oneshot(request(
            "PUT",
            &format!("/api/v1/yarns/{id}"),
            OWNER_TOKEN,
            Some(write_body("Updated wool", Some(&original_updated_at))),
        ))
        .await
        .expect("router should respond");
    assert_eq!(updated.status(), StatusCode::OK);
    let updated = json_body(updated).await;
    assert_eq!(updated["name"], "Updated wool");
    let current_updated_at = updated["updated_at"].as_str().unwrap().to_owned();
    assert!(current_updated_at > original_updated_at);

    let stale = app
        .clone()
        .oneshot(request(
            "PUT",
            &format!("/api/v1/yarns/{id}"),
            OWNER_TOKEN,
            Some(write_body("Stale write", Some(&original_updated_at))),
        ))
        .await
        .expect("router should respond");
    assert_eq!(stale.status(), StatusCode::CONFLICT);
    let stale = json_body(stale).await;
    assert_eq!(stale["detail"]["name"], "Updated wool");
    assert_eq!(stale["detail"]["updated_at"], current_updated_at);

    let deleted = app
        .clone()
        .oneshot(request(
            "DELETE",
            &format!("/api/v1/yarns/{id}"),
            OWNER_TOKEN,
            None,
        ))
        .await
        .expect("router should respond");
    assert_eq!(deleted.status(), StatusCode::NO_CONTENT);

    let recreated = app
        .oneshot(request(
            "POST",
            "/api/v1/yarns",
            OWNER_TOKEN,
            Some(write_body("New wool", None)),
        ))
        .await
        .expect("router should respond");
    assert_eq!(recreated.status(), StatusCode::CREATED);
    assert!(json_body(recreated).await["id"].as_i64().unwrap() > id);
}

#[tokio::test]
async fn yarn_favorites_are_idempotent_scoped_and_filterable() {
    let (app, _, _, _) = app().await;
    let created = app
        .clone()
        .oneshot(request(
            "POST",
            "/api/v1/yarns",
            OWNER_TOKEN,
            Some(write_body("Favourite wool", None)),
        ))
        .await
        .expect("router should respond");
    let id = json_body(created).await["id"]
        .as_i64()
        .expect("yarn ID is numeric");

    for _ in 0..2 {
        let favorite = app
            .clone()
            .oneshot(request(
                "POST",
                &format!("/api/v1/yarns/{id}/favorite"),
                OWNER_TOKEN,
                None,
            ))
            .await
            .expect("router should respond");
        assert_eq!(favorite.status(), StatusCode::OK);
        assert_eq!(json_body(favorite).await["is_favorite"], true);
    }

    let favorites = app
        .clone()
        .oneshot(request(
            "GET",
            "/api/v1/yarns?favorite=true",
            OWNER_TOKEN,
            None,
        ))
        .await
        .expect("router should respond");
    let favorites = json_body(favorites).await;
    assert_eq!(favorites["items"].as_array().unwrap().len(), 1);
    assert_eq!(favorites["items"][0]["is_favorite"], true);

    let other_favorite = app
        .clone()
        .oneshot(request(
            "POST",
            &format!("/api/v1/yarns/{id}/favorite"),
            OTHER_TOKEN,
            None,
        ))
        .await
        .expect("router should respond");
    assert_eq!(other_favorite.status(), StatusCode::NOT_FOUND);

    let unfavorite = app
        .clone()
        .oneshot(request(
            "DELETE",
            &format!("/api/v1/yarns/{id}/favorite"),
            OWNER_TOKEN,
            None,
        ))
        .await
        .expect("router should respond");
    assert_eq!(unfavorite.status(), StatusCode::OK);
    assert_eq!(json_body(unfavorite).await["is_favorite"], false);

    let non_favorites = app
        .oneshot(request(
            "GET",
            "/api/v1/yarns?favorite=false&page=1",
            OWNER_TOKEN,
            None,
        ))
        .await
        .expect("router should respond");
    let non_favorites = json_body(non_favorites).await;
    assert_eq!(non_favorites["page"], 1);
    assert_eq!(non_favorites["per_page"], 50);
    assert_eq!(non_favorites["items"].as_array().unwrap().len(), 1);
    assert_eq!(
        non_favorites["items"][0]["preview_url"],
        serde_json::Value::Null
    );
}

#[tokio::test]
async fn yarn_project_ids_rename_timestamps_and_delete_tombstone_stay_in_sync() {
    let (app, pool, owner_id, other_id) = app().await;
    let created = app
        .clone()
        .oneshot(request(
            "POST",
            "/api/v1/yarns",
            OWNER_TOKEN,
            Some(write_body("Linked yarn", None)),
        ))
        .await
        .expect("router should respond");
    let created = json_body(created).await;
    let yarn_id = created["id"].as_i64().expect("yarn ID is numeric");
    let initial_yarn_updated_at = created["updated_at"].as_str().unwrap().to_owned();
    let initial_project_time = "2020-01-01T00:00:00.000000+00:00";

    let owner_project_id =
        insert_project(&pool, owner_id, "Owner project", initial_project_time).await;
    let other_project_id =
        insert_project(&pool, other_id, "Other project", initial_project_time).await;
    for project_id in [owner_project_id, other_project_id] {
        sqlx::query("INSERT INTO project_yarns (project_id, yarn_id) VALUES (?, ?)")
            .bind(project_id)
            .bind(yarn_id)
            .execute(&pool)
            .await
            .expect("yarn association should be inserted");
    }

    let unchanged_name = app
        .clone()
        .oneshot(request(
            "PUT",
            &format!("/api/v1/yarns/{yarn_id}"),
            OWNER_TOKEN,
            Some(write_body("Linked yarn", Some(&initial_yarn_updated_at))),
        ))
        .await
        .expect("router should respond");
    assert_eq!(unchanged_name.status(), StatusCode::OK);
    let unchanged_name = json_body(unchanged_name).await;
    let yarn_updated_at = unchanged_name["updated_at"].as_str().unwrap().to_owned();
    let unchanged_project_time: String =
        sqlx::query_scalar("SELECT updated_at FROM projects WHERE id = ?")
            .bind(owner_project_id)
            .fetch_one(&pool)
            .await
            .expect("linked project timestamp should be readable");
    assert_eq!(unchanged_project_time, initial_project_time);

    let detail = app
        .clone()
        .oneshot(request(
            "GET",
            &format!("/api/v1/yarns/{yarn_id}"),
            OWNER_TOKEN,
            None,
        ))
        .await
        .expect("router should respond");
    assert_eq!(
        json_body(detail).await["project_ids"],
        serde_json::json!([owner_project_id])
    );

    let renamed = app
        .clone()
        .oneshot(request(
            "PUT",
            &format!("/api/v1/yarns/{yarn_id}"),
            OWNER_TOKEN,
            Some(write_body("Renamed linked yarn", Some(&yarn_updated_at))),
        ))
        .await
        .expect("router should respond");
    assert_eq!(renamed.status(), StatusCode::OK);
    let renamed = json_body(renamed).await;
    assert_eq!(
        renamed["project_ids"],
        serde_json::json!([owner_project_id])
    );
    let renamed_project_time: String =
        sqlx::query_scalar("SELECT updated_at FROM projects WHERE id = ?")
            .bind(owner_project_id)
            .fetch_one(&pool)
            .await
            .expect("linked project timestamp should be readable");
    assert!(renamed_project_time.as_str() > initial_project_time);
    let other_project_time: String =
        sqlx::query_scalar("SELECT updated_at FROM projects WHERE id = ?")
            .bind(other_project_id)
            .fetch_one(&pool)
            .await
            .expect("other project timestamp should be readable");
    assert_eq!(other_project_time, initial_project_time);

    let deleted = app
        .oneshot(request(
            "DELETE",
            &format!("/api/v1/yarns/{yarn_id}"),
            OWNER_TOKEN,
            None,
        ))
        .await
        .expect("router should respond");
    assert_eq!(deleted.status(), StatusCode::NO_CONTENT);
    let deletion: (i64, String, i64, String) = sqlx::query_as(
        "SELECT owner_id, entity_type, entity_id, changed_at \
         FROM deletion_events WHERE entity_type = 'yarn' AND entity_id = ?",
    )
    .bind(yarn_id)
    .fetch_one(&pool)
    .await
    .expect("yarn tombstone should be stored");
    assert_eq!(deletion.0, owner_id);
    assert_eq!(deletion.1, "yarn");
    assert_eq!(deletion.2, yarn_id);
    assert!(deletion.3.ends_with('Z'));
    chrono::DateTime::parse_from_rfc3339(&deletion.3).unwrap();
    let deleted_project_time: String =
        sqlx::query_scalar("SELECT updated_at FROM projects WHERE id = ?")
            .bind(owner_project_id)
            .fetch_one(&pool)
            .await
            .expect("project timestamp should be readable after deleting yarn");
    assert!(deleted_project_time > renamed_project_time);
    let remaining_links: i64 =
        sqlx::query_scalar("SELECT count(*) FROM project_yarns WHERE yarn_id = ?")
            .bind(yarn_id)
            .fetch_one(&pool)
            .await
            .expect("yarn links should stay with the trashed yarn");
    // The trashed yarn keeps its links until it is purged.
    assert_eq!(remaining_links, 2);
    let other_project_time_after_delete: String =
        sqlx::query_scalar("SELECT updated_at FROM projects WHERE id = ?")
            .bind(other_project_id)
            .fetch_one(&pool)
            .await
            .expect("other project's timestamp should remain readable");
    assert_eq!(other_project_time_after_delete, initial_project_time);
}

async fn insert_project(pool: &SqlitePool, owner_id: i64, name: &str, timestamp: &str) -> i64 {
    sqlx::query(
        "INSERT INTO projects (owner_id, name, tags, created_at, updated_at) \
         VALUES (?, ?, '[]', ?, ?)",
    )
    .bind(owner_id)
    .bind(name)
    .bind(timestamp)
    .bind(timestamp)
    .execute(pool)
    .await
    .expect("test project should be inserted")
    .last_insert_rowid()
}
