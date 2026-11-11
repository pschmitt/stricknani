use axum::body::Body;
use axum::http::{Request, StatusCode};
use http_body_util::BodyExt;
use sha2::{Digest, Sha256};
use sqlx::SqlitePool;
use stricknani_server::{app_with_state, db};
use tower::ServiceExt;

const OWNER_TOKEN: &str = "projects-owner-token";
const OTHER_TOKEN: &str = "projects-other-token";

async fn app_and_users() -> (axum::Router, SqlitePool, i64, i64) {
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
    let user_id = sqlx::query("INSERT INTO users (email, password_hash) VALUES (?, 'test-hash')")
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

async fn seed_yarn(pool: &SqlitePool, owner_id: i64, name: &str) -> i64 {
    sqlx::query(
        "INSERT INTO yarns (owner_id, name, created_at, updated_at) \
         VALUES (?, ?, '2026-01-01T00:00:00.000000Z', '2026-01-01T00:00:00.000000Z')",
    )
    .bind(owner_id)
    .bind(name)
    .execute(pool)
    .await
    .expect("test yarn should be inserted")
    .last_insert_rowid()
}

async fn yarn_updated_at(pool: &SqlitePool, yarn_id: i64) -> String {
    sqlx::query_scalar("SELECT updated_at FROM yarns WHERE id = ?")
        .bind(yarn_id)
        .fetch_one(pool)
        .await
        .expect("test yarn should exist")
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

fn write_body(value: serde_json::Value) -> String {
    value.to_string()
}

#[tokio::test]
async fn project_crud_preserves_step_ids_and_rejects_stale_updates() {
    let (app, pool, owner_id, _) = app_and_users().await;
    let yarn_id = seed_yarn(&pool, owner_id, "Blue wool").await;
    let yarn_updated_before_link = yarn_updated_at(&pool, yarn_id).await;
    let created = app
        .clone()
        .oneshot(request(
            "POST",
            "/api/v1/projects",
            OWNER_TOKEN,
            Some(write_body(serde_json::json!({
                "name": "  Striped Socks  ",
                "category": "  Socks  ",
                "needles": "  4 mm ",
                "tags": ["#socks", "wool", "SOCKS"],
                "yarn_ids": [yarn_id],
                "steps": [
                    {"title": " Cast on ", "description": "CO 64", "step_number": 1},
                    {"title": " Knit heel ", "step_number": 2}
                ]
            }))),
        ))
        .await
        .expect("router should respond");
    assert_eq!(created.status(), StatusCode::CREATED);
    let created = json_body(created).await;
    let yarn_updated_after_link = yarn_updated_at(&pool, yarn_id).await;
    assert!(yarn_updated_after_link > yarn_updated_before_link);
    let project_id = created["id"].as_i64().expect("project ID is numeric");
    let initial_updated_at = created["updated_at"]
        .as_str()
        .expect("updated_at is serialized")
        .to_owned();
    let first_step_id = created["steps"][0]["id"]
        .as_i64()
        .expect("step ID is numeric");
    let second_step_id = created["steps"][1]["id"]
        .as_i64()
        .expect("step ID is numeric");
    assert_eq!(created["name"], "Striped Socks");
    assert_eq!(created["category"], "Socks");
    assert_eq!(created["needles"], "4 mm");
    assert_eq!(created["tags"], serde_json::json!(["socks", "wool"]));
    assert_eq!(created["yarn_ids"], serde_json::json!([yarn_id]));
    assert_eq!(created["yarn"], "Blue wool");
    assert_eq!(created["images"], serde_json::json!([]));
    assert_eq!(created["attachments"], serde_json::json!([]));
    assert!(initial_updated_at.ends_with('Z'));
    assert!(initial_updated_at.contains('.'));

    let updated = app
        .clone()
        .oneshot(request(
            "PUT",
            &format!("/api/v1/projects/{project_id}"),
            OWNER_TOKEN,
            Some(write_body(serde_json::json!({
                "name": "Striped Socks v2",
                "tags": ["socks"],
                "yarn_ids": [],
                "steps": [
                    {"id": second_step_id, "title": "Knit heel first", "step_number": 1},
                    {"id": first_step_id, "title": "Cast on", "step_number": 2}
                ]
            }))),
        ))
        .await
        .expect("router should respond");
    assert_eq!(updated.status(), StatusCode::OK);
    let updated = json_body(updated).await;
    let yarn_updated_after_unlink = yarn_updated_at(&pool, yarn_id).await;
    assert!(yarn_updated_after_unlink > yarn_updated_after_link);
    assert_eq!(updated["name"], "Striped Socks v2");
    assert_eq!(updated["yarn_ids"], serde_json::json!([]));
    assert_eq!(updated["yarn"], serde_json::Value::Null);
    assert_eq!(updated["steps"][0]["id"], second_step_id);
    assert_eq!(updated["steps"][1]["id"], first_step_id);
    assert_eq!(updated["steps"][0]["step_number"], 1);
    assert_eq!(updated["steps"][1]["step_number"], 2);
    let updated_at = updated["updated_at"]
        .as_str()
        .expect("timestamp is a string");
    assert!(updated_at > initial_updated_at.as_str());
    assert_eq!(updated_at.matches('.').count(), 1);
    assert_eq!(updated_at.len(), 27);
    let actions: Vec<String> = sqlx::query_scalar(
        "SELECT action FROM audit_events WHERE actor_user_id = ? AND entity_type = 'project' \
         AND entity_id = ? ORDER BY id",
    )
    .bind(owner_id)
    .bind(project_id)
    .fetch_all(&pool)
    .await
    .expect("project audit history should be queryable");
    assert_eq!(actions, vec!["created".to_owned(), "updated".to_owned()]);

    let stale = app
        .clone()
        .oneshot(request(
            "PUT",
            &format!("/api/v1/projects/{project_id}"),
            OWNER_TOKEN,
            Some(write_body(serde_json::json!({
                "name": "Stale local edit",
                "expected_updated_at": initial_updated_at.trim_end_matches('Z')
            }))),
        ))
        .await
        .expect("router should respond");
    assert_eq!(stale.status(), StatusCode::CONFLICT);
    let stale = json_body(stale).await;
    assert_eq!(stale["detail"]["name"], "Striped Socks v2");
    assert_eq!(stale["detail"]["updated_at"], updated_at);
}

#[tokio::test]
async fn project_favorites_are_idempotent_filterable_and_delete_keeps_ids_stable() {
    let (app, pool, owner_id, _) = app_and_users().await;
    let created = app
        .clone()
        .oneshot(request(
            "POST",
            "/api/v1/projects",
            OWNER_TOKEN,
            Some(write_body(serde_json::json!({
                "name": "Lace cardigan",
                "category": "Cardigan",
                "tags": ["winter", "lace"]
            }))),
        ))
        .await
        .expect("router should respond");
    let project_id = json_body(created).await["id"]
        .as_i64()
        .expect("project ID is numeric");

    for _ in 0..2 {
        let favorite = app
            .clone()
            .oneshot(request(
                "POST",
                &format!("/api/v1/projects/{project_id}/favorite"),
                OWNER_TOKEN,
                None,
            ))
            .await
            .expect("router should respond");
        assert_eq!(favorite.status(), StatusCode::OK);
        assert_eq!(json_body(favorite).await["is_favorite"], true);
    }
    let filtered = app
        .clone()
        .oneshot(request(
            "GET",
            "/api/v1/projects?page=1&category=Cardigan&tag=LACE&favorite=true",
            OWNER_TOKEN,
            None,
        ))
        .await
        .expect("router should respond");
    assert_eq!(filtered.status(), StatusCode::OK);
    let filtered = json_body(filtered).await;
    assert_eq!(filtered["per_page"], 50);
    assert_eq!(filtered["has_more"], false);
    assert_eq!(filtered["items"][0]["id"], project_id);
    assert_eq!(filtered["items"][0]["is_favorite"], true);

    let unfavorite = app
        .clone()
        .oneshot(request(
            "DELETE",
            &format!("/api/v1/projects/{project_id}/favorite"),
            OWNER_TOKEN,
            None,
        ))
        .await
        .expect("router should respond");
    assert_eq!(unfavorite.status(), StatusCode::OK);
    assert_eq!(json_body(unfavorite).await["is_favorite"], false);

    let deleted = app
        .clone()
        .oneshot(request(
            "DELETE",
            &format!("/api/v1/projects/{project_id}"),
            OWNER_TOKEN,
            None,
        ))
        .await
        .expect("router should respond");
    assert_eq!(deleted.status(), StatusCode::NO_CONTENT);
    let missing = app
        .clone()
        .oneshot(request(
            "GET",
            &format!("/api/v1/projects/{project_id}"),
            OWNER_TOKEN,
            None,
        ))
        .await
        .expect("router should respond");
    assert_eq!(missing.status(), StatusCode::NOT_FOUND);
    let (event_type, deleted_id, changed_at): (String, i64, String) = sqlx::query_as(
        "SELECT entity_type, entity_id, changed_at FROM deletion_events \
         WHERE owner_id = ? AND entity_id = ?",
    )
    .bind(owner_id)
    .bind(project_id)
    .fetch_one(&pool)
    .await
    .expect("project deletion should persist a sync event");
    assert_eq!(event_type, "project");
    assert_eq!(deleted_id, project_id);
    assert!(changed_at.ends_with('Z'));
    let actions: Vec<String> = sqlx::query_scalar(
        "SELECT action FROM audit_events WHERE actor_user_id = ? AND entity_type = 'project' \
         AND entity_id = ? ORDER BY id",
    )
    .bind(owner_id)
    .bind(project_id)
    .fetch_all(&pool)
    .await
    .expect("deleted project audit history should remain queryable");
    assert_eq!(actions, vec!["created".to_owned(), "deleted".to_owned()]);
    let recreated = app
        .oneshot(request(
            "POST",
            "/api/v1/projects",
            OWNER_TOKEN,
            Some(write_body(serde_json::json!({"name": "Another project"}))),
        ))
        .await
        .expect("router should respond");
    assert_eq!(recreated.status(), StatusCode::CREATED);
    assert!(json_body(recreated).await["id"].as_i64().unwrap() > project_id);
}

#[tokio::test]
async fn project_access_and_yarn_and_step_links_are_owner_scoped() {
    let (app, pool, owner_id, other_id) = app_and_users().await;
    let foreign_yarn_id = seed_yarn(&pool, other_id, "Other user's yarn").await;
    let owner_yarn_id = seed_yarn(&pool, owner_id, "Owner's yarn").await;
    let filtered_yarns = app
        .clone()
        .oneshot(request(
            "POST",
            "/api/v1/projects",
            OWNER_TOKEN,
            Some(write_body(serde_json::json!({
                "name": "Filter unavailable yarn links",
                "yarn_ids": [foreign_yarn_id, owner_yarn_id, 999_999]
            }))),
        ))
        .await
        .expect("router should respond");
    assert_eq!(filtered_yarns.status(), StatusCode::CREATED);
    assert_eq!(
        json_body(filtered_yarns).await["yarn_ids"],
        serde_json::json!([owner_yarn_id])
    );

    let other_project = app
        .clone()
        .oneshot(request(
            "POST",
            "/api/v1/projects",
            OTHER_TOKEN,
            Some(write_body(serde_json::json!({
                "name": "Private project",
                "steps": [{"title": "Other user's step"}]
            }))),
        ))
        .await
        .expect("router should respond");
    let other_project = json_body(other_project).await;
    let other_project_id = other_project["id"].as_i64().expect("project ID is numeric");
    let foreign_step_id = other_project["steps"][0]["id"]
        .as_i64()
        .expect("step ID is numeric");
    let cross_owner_get = app
        .clone()
        .oneshot(request(
            "GET",
            &format!("/api/v1/projects/{other_project_id}"),
            OWNER_TOKEN,
            None,
        ))
        .await
        .expect("router should respond");
    assert_eq!(cross_owner_get.status(), StatusCode::NOT_FOUND);
    let cross_owner_delete = app
        .clone()
        .oneshot(request(
            "DELETE",
            &format!("/api/v1/projects/{other_project_id}"),
            OWNER_TOKEN,
            None,
        ))
        .await
        .expect("router should respond");
    assert_eq!(cross_owner_delete.status(), StatusCode::NOT_FOUND);

    let owner_project = app
        .clone()
        .oneshot(request(
            "POST",
            "/api/v1/projects",
            OWNER_TOKEN,
            Some(write_body(serde_json::json!({"name": "Owner project"}))),
        ))
        .await
        .expect("router should respond");
    let owner_project_id = json_body(owner_project).await["id"]
        .as_i64()
        .expect("project ID is numeric");
    let unknown_step = app
        .clone()
        .oneshot(request(
            "PUT",
            &format!("/api/v1/projects/{owner_project_id}"),
            OWNER_TOKEN,
            Some(write_body(serde_json::json!({
                "name": "Owner project",
                "steps": [{"id": foreign_step_id, "title": "Reassign"}]
            }))),
        ))
        .await
        .expect("router should respond");
    assert_eq!(unknown_step.status(), StatusCode::OK);
    let unknown_step = json_body(unknown_step).await;
    let new_step_id = unknown_step["steps"][0]["id"]
        .as_i64()
        .expect("new step ID is numeric");
    assert_ne!(new_step_id, foreign_step_id);
    assert_eq!(unknown_step["steps"][0]["title"], "Reassign");
    let unchanged = app
        .oneshot(request(
            "GET",
            &format!("/api/v1/projects/{owner_project_id}"),
            OWNER_TOKEN,
            None,
        ))
        .await
        .expect("router should respond");
    assert_eq!(json_body(unchanged).await["steps"][0]["id"], new_step_id);
    assert_ne!(owner_id, other_id);
}

#[tokio::test]
async fn project_api_preserves_empty_names_titles_and_step_descriptions() {
    let (app, _, _, _) = app_and_users().await;
    let created = app
        .clone()
        .oneshot(request(
            "POST",
            "/api/v1/projects",
            OWNER_TOKEN,
            Some(write_body(serde_json::json!({
                "name": "   ",
                "steps": [{"title": "  ", "description": "   "}]
            }))),
        ))
        .await
        .expect("router should respond");
    assert_eq!(created.status(), StatusCode::CREATED);
    let created = json_body(created).await;
    assert_eq!(created["name"], "");
    assert_eq!(created["steps"][0]["title"], "");
    assert_eq!(created["steps"][0]["description"], "");

    let project_id = created["id"].as_i64().expect("project ID is numeric");
    let step_id = created["steps"][0]["id"]
        .as_i64()
        .expect("step ID is numeric");
    let updated = app
        .clone()
        .oneshot(request(
            "PUT",
            &format!("/api/v1/projects/{project_id}"),
            OWNER_TOKEN,
            Some(write_body(serde_json::json!({
                "name": "",
                "steps": [{"id": step_id, "title": "", "description": ""}]
            }))),
        ))
        .await
        .expect("router should respond");
    assert_eq!(updated.status(), StatusCode::OK);
    let updated = json_body(updated).await;
    assert_eq!(updated["name"], "");
    assert_eq!(updated["steps"][0]["title"], "");
    assert_eq!(updated["steps"][0]["description"], "");
}

#[tokio::test]
async fn deleting_project_advances_linked_yarn_timestamps() {
    let (app, pool, owner_id, _) = app_and_users().await;
    let yarn_id = seed_yarn(&pool, owner_id, "Project yarn").await;
    let before_link = yarn_updated_at(&pool, yarn_id).await;
    let created = app
        .clone()
        .oneshot(request(
            "POST",
            "/api/v1/projects",
            OWNER_TOKEN,
            Some(write_body(serde_json::json!({
                "name": "Project with yarn",
                "yarn_ids": [yarn_id]
            }))),
        ))
        .await
        .expect("router should respond");
    let project_id = json_body(created).await["id"]
        .as_i64()
        .expect("project ID is numeric");
    let after_link = yarn_updated_at(&pool, yarn_id).await;
    assert!(after_link > before_link);

    let deleted = app
        .oneshot(request(
            "DELETE",
            &format!("/api/v1/projects/{project_id}"),
            OWNER_TOKEN,
            None,
        ))
        .await
        .expect("router should respond");
    assert_eq!(deleted.status(), StatusCode::NO_CONTENT);
    let after_delete = yarn_updated_at(&pool, yarn_id).await;
    assert!(after_delete > after_link);
}

#[tokio::test]
async fn updating_project_without_changing_yarn_links_does_not_bump_yarns() {
    let (app, pool, owner_id, _) = app_and_users().await;
    let yarn_id = seed_yarn(&pool, owner_id, "Unchanged link yarn").await;
    let replacement_yarn_id = seed_yarn(&pool, owner_id, "Replacement yarn").await;
    let replacement_yarn_before_link = yarn_updated_at(&pool, replacement_yarn_id).await;
    let created = app
        .clone()
        .oneshot(request(
            "POST",
            "/api/v1/projects",
            OWNER_TOKEN,
            Some(write_body(serde_json::json!({
                "name": "Project",
                "yarn_ids": [yarn_id]
            }))),
        ))
        .await
        .expect("router should respond");
    let project_id = json_body(created).await["id"]
        .as_i64()
        .expect("project ID is numeric");
    let before_update = yarn_updated_at(&pool, yarn_id).await;

    let updated = app
        .clone()
        .oneshot(request(
            "PUT",
            &format!("/api/v1/projects/{project_id}"),
            OWNER_TOKEN,
            Some(write_body(serde_json::json!({
                "name": "Renamed project",
                "yarn_ids": [yarn_id]
            }))),
        ))
        .await
        .expect("router should respond");
    assert_eq!(updated.status(), StatusCode::OK);
    assert_eq!(yarn_updated_at(&pool, yarn_id).await, before_update);

    let replaced = app
        .oneshot(request(
            "PUT",
            &format!("/api/v1/projects/{project_id}"),
            OWNER_TOKEN,
            Some(write_body(serde_json::json!({
                "name": "Replaced project",
                "yarn_ids": [replacement_yarn_id]
            }))),
        ))
        .await
        .expect("router should respond");
    assert_eq!(replaced.status(), StatusCode::OK);
    assert!(yarn_updated_at(&pool, yarn_id).await > before_update);
    assert!(yarn_updated_at(&pool, replacement_yarn_id).await > replacement_yarn_before_link);
}

#[tokio::test]
async fn project_image_url_import_is_explicitly_not_implemented() {
    let (app, _, _, _) = app_and_users().await;
    for body in [
        serde_json::json!({"name": "Project image import", "image_urls": ["https://example.test/image.png"]}),
        serde_json::json!({"name": "Step image import", "steps": [{"title": "Step", "image_urls": ["https://example.test/image.png"]}]}),
    ] {
        let response = app
            .clone()
            .oneshot(request(
                "POST",
                "/api/v1/projects",
                OWNER_TOKEN,
                Some(write_body(body)),
            ))
            .await
            .expect("router should respond");
        assert_eq!(response.status(), StatusCode::NOT_IMPLEMENTED);
        assert_eq!(
            json_body(response).await["detail"],
            "Project image URL imports are not implemented yet"
        );
    }
}

#[tokio::test]
async fn v1_projects_neither_expose_nor_accept_status() {
    let (app, pool, owner_id, _) = app_and_users().await;
    let created = app
        .clone()
        .oneshot(request(
            "POST",
            "/api/v1/projects",
            OWNER_TOKEN,
            Some(serde_json::json!({"name": "Socks", "status": "done"}).to_string()),
        ))
        .await
        .unwrap();
    assert_eq!(created.status(), StatusCode::CREATED);
    let body = json_body(created).await;
    assert!(body.get("status").is_none(), "v1 payload stays unchanged");
    let id = body["id"].as_i64().unwrap();
    let stored: String =
        sqlx::query_scalar("SELECT status FROM projects WHERE id = ? AND owner_id = ?")
            .bind(id)
            .bind(owner_id)
            .fetch_one(&pool)
            .await
            .unwrap();
    assert_eq!(stored, "in_progress", "v1 writes ignore status");
    let listed = app
        .clone()
        .oneshot(request(
            "GET",
            "/api/v1/projects?status=done",
            OWNER_TOKEN,
            None,
        ))
        .await
        .unwrap();
    assert_eq!(listed.status(), StatusCode::OK);
    let listed = json_body(listed).await;
    assert_eq!(listed["items"].as_array().unwrap().len(), 1);
    assert!(listed["items"][0].get("status").is_none());
}
