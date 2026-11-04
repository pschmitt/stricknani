use axum::body::Body;
use axum::http::{Request, StatusCode};
use http_body_util::BodyExt;
use sha2::{Digest, Sha256};
use sqlx::SqlitePool;
use stricknani_server::{app_with_state, db};
use tower::ServiceExt;

const OWNER_TOKEN: &str = "categories-owner-token";
const OTHER_TOKEN: &str = "categories-other-token";

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

fn request(method: &str, path: &str, token: &str, body: Option<&str>) -> Request<Body> {
    let mut request = Request::builder()
        .method(method)
        .uri(path)
        .header("authorization", format!("Bearer {token}"));
    if body.is_some() {
        request = request.header("content-type", "application/json");
    }
    request
        .body(body.map_or_else(Body::empty, |body| Body::from(body.to_owned())))
        .expect("request is valid")
}

async fn response_json(response: axum::response::Response) -> serde_json::Value {
    let body = response
        .into_body()
        .collect()
        .await
        .expect("response body is readable")
        .to_bytes();
    serde_json::from_slice(&body).expect("response is JSON")
}

#[tokio::test]
async fn category_crud_is_idempotent_and_scoped_to_the_token_owner() {
    let (app, _, owner_id, other_id) = app_and_users().await;

    let created = app
        .clone()
        .oneshot(request(
            "POST",
            "/api/v1/categories",
            OWNER_TOKEN,
            Some(r#"{"name":"  Lace  "}"#),
        ))
        .await
        .expect("router should respond");
    assert_eq!(created.status(), StatusCode::CREATED);
    let created = response_json(created).await;
    assert_eq!(created["name"], "Lace");
    let category_id = created["id"].as_i64().expect("category ID is numeric");

    let duplicate = app
        .clone()
        .oneshot(request(
            "POST",
            "/api/v1/categories",
            OWNER_TOKEN,
            Some(r#"{"name":"lace"}"#),
        ))
        .await
        .expect("router should respond");
    assert_eq!(duplicate.status(), StatusCode::CREATED);
    let duplicate = response_json(duplicate).await;
    assert_eq!(duplicate["id"], category_id);
    assert_eq!(duplicate["name"], "Lace");

    let other_list = app
        .clone()
        .oneshot(request("GET", "/api/v1/categories", OTHER_TOKEN, None))
        .await
        .expect("router should respond");
    assert_eq!(other_list.status(), StatusCode::OK);
    assert_eq!(response_json(other_list).await, serde_json::json!([]));

    let other_update = app
        .clone()
        .oneshot(request(
            "PUT",
            &format!("/api/v1/categories/{category_id}"),
            OTHER_TOKEN,
            Some(r#"{"name":"Hijacked"}"#),
        ))
        .await
        .expect("router should respond");
    assert_eq!(other_update.status(), StatusCode::NOT_FOUND);

    let renamed = app
        .clone()
        .oneshot(request(
            "PUT",
            &format!("/api/v1/categories/{category_id}"),
            OWNER_TOKEN,
            Some(r#"{"name":"Cotton"}"#),
        ))
        .await
        .expect("router should respond");
    assert_eq!(renamed.status(), StatusCode::OK);
    assert_eq!(response_json(renamed).await["name"], "Cotton");

    let conflict_category = app
        .clone()
        .oneshot(request(
            "POST",
            "/api/v1/categories",
            OWNER_TOKEN,
            Some(r#"{"name":"Wool"}"#),
        ))
        .await
        .expect("router should respond");
    let conflict_category = response_json(conflict_category).await;
    let conflict_id = conflict_category["id"]
        .as_i64()
        .expect("category ID is numeric");
    let conflict = app
        .clone()
        .oneshot(request(
            "PUT",
            &format!("/api/v1/categories/{category_id}"),
            OWNER_TOKEN,
            Some(r#"{"name":"wOoL"}"#),
        ))
        .await
        .expect("router should respond");
    assert_eq!(conflict.status(), StatusCode::CONFLICT);

    let deleted = app
        .clone()
        .oneshot(request(
            "DELETE",
            &format!("/api/v1/categories/{category_id}"),
            OWNER_TOKEN,
            None,
        ))
        .await
        .expect("router should respond");
    assert_eq!(deleted.status(), StatusCode::NO_CONTENT);

    let other_delete = app
        .clone()
        .oneshot(request(
            "DELETE",
            &format!("/api/v1/categories/{conflict_id}"),
            OTHER_TOKEN,
            None,
        ))
        .await
        .expect("router should respond");
    assert_eq!(other_delete.status(), StatusCode::NOT_FOUND);

    // Keep the seeded account IDs in use to assert that each token represents a distinct owner.
    assert_ne!(owner_id, other_id);
}

#[tokio::test]
async fn category_routes_require_a_bearer_token_and_validate_names() {
    let (app, _, _, _) = app_and_users().await;

    let unauthenticated = app
        .clone()
        .oneshot(
            Request::get("/api/v1/categories")
                .body(Body::empty())
                .expect("request is valid"),
        )
        .await
        .expect("router should respond");
    assert_eq!(unauthenticated.status(), StatusCode::UNAUTHORIZED);

    let empty_name = app
        .oneshot(request(
            "POST",
            "/api/v1/categories",
            OWNER_TOKEN,
            Some(r#"{"name":"  "}"#),
        ))
        .await
        .expect("router should respond");
    assert_eq!(empty_name.status(), StatusCode::BAD_REQUEST);
    assert_eq!(
        response_json(empty_name).await["detail"],
        "Category name is required"
    );
}

#[tokio::test]
async fn category_sync_returns_owner_snapshot_and_ignores_since() {
    let (app, pool, owner_id, other_id) = app_and_users().await;
    let owner_category_id = insert_category(&pool, owner_id, "Lace").await;
    let deleted_id = insert_category(&pool, owner_id, "Deleted").await;
    let other_category_id = insert_category(&pool, other_id, "Other").await;
    sqlx::query("DELETE FROM categories WHERE id = ?")
        .bind(deleted_id)
        .execute(&pool)
        .await
        .expect("category should be deleted");

    let response = app
        .clone()
        .oneshot(request(
            "GET",
            "/api/v1/sync/categories?since=2999-01-01T00%3A00%3A00Z",
            OWNER_TOKEN,
            None,
        ))
        .await
        .expect("router should respond");
    assert_eq!(response.status(), StatusCode::OK);
    let body = response_json(response).await;
    assert_eq!(body["updated"].as_array().map(Vec::len), Some(1));
    assert_eq!(body["updated"][0]["id"], owner_category_id);
    assert_eq!(body["updated"][0]["name"], "Lace");
    assert_eq!(body["deleted_ids"], serde_json::json!([]));
    let server_time = body["server_time"]
        .as_str()
        .expect("server_time is a string");
    let parsed = chrono::DateTime::parse_from_rfc3339(server_time).expect("server_time is RFC3339");
    assert_eq!(parsed.offset().local_minus_utc(), 0);
    assert_ne!(owner_category_id, other_category_id);

    let other_response = app
        .clone()
        .oneshot(request(
            "GET",
            "/api/v1/sync/categories?since=ignored",
            OTHER_TOKEN,
            None,
        ))
        .await
        .expect("router should respond");
    assert_eq!(other_response.status(), StatusCode::OK);
    let other_body = response_json(other_response).await;
    assert_eq!(other_body["updated"].as_array().map(Vec::len), Some(1));
    assert_eq!(other_body["updated"][0]["id"], other_category_id);
    assert_eq!(other_body["updated"][0]["name"], "Other");

    let unauthenticated = app
        .oneshot(
            Request::get("/api/v1/sync/categories?since=ignored")
                .body(Body::empty())
                .expect("request is valid"),
        )
        .await
        .expect("router should respond");
    assert_eq!(unauthenticated.status(), StatusCode::UNAUTHORIZED);
}

async fn insert_category(pool: &SqlitePool, user_id: i64, name: &str) -> i64 {
    sqlx::query("INSERT INTO categories (user_id, name) VALUES (?, ?)")
        .bind(user_id)
        .bind(name)
        .execute(pool)
        .await
        .expect("category should be inserted")
        .last_insert_rowid()
}
