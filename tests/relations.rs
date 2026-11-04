use axum::{
    body::Body,
    http::{Request, StatusCode},
};
use http_body_util::BodyExt;
use serde_json::{Value, json};
use sha2::{Digest, Sha256};
use stricknani_server::{app_with_state, db};
use tower::ServiceExt;

async fn request(app: &axum::Router, method: &str, path: &str, body: Value) -> (StatusCode, Value) {
    let response = app
        .clone()
        .oneshot(
            Request::builder()
                .method(method)
                .uri(path)
                .header("authorization", "Bearer relations-test-only")
                .header("content-type", "application/json")
                .body(Body::from(body.to_string()))
                .unwrap(),
        )
        .await
        .unwrap();
    let status = response.status();
    let bytes = response.into_body().collect().await.unwrap().to_bytes();
    let value = if bytes.is_empty() {
        Value::Null
    } else {
        serde_json::from_slice(&bytes).unwrap()
    };
    (status, value)
}

#[tokio::test]
async fn category_changes_update_project_projection_and_sync_timestamp() {
    let pool = db::connect("sqlite::memory:").await.unwrap();
    sqlx::query(
        "INSERT INTO users(id,email,password_hash) VALUES (1,'relations@example.invalid','unused')",
    )
    .execute(&pool)
    .await
    .unwrap();
    sqlx::query("INSERT INTO api_tokens(user_id,name,token_hash,created_at) VALUES(1,'test',?,0)")
        .bind(hex::encode(Sha256::digest(b"relations-test-only")))
        .execute(&pool)
        .await
        .unwrap();
    let app = app_with_state(db::AppState::new(pool));
    let (status, project) = request(
        &app,
        "POST",
        "/api/v1/projects",
        json!({"name":"Scarf","category":"Accessories"}),
    )
    .await;
    assert_eq!(status, StatusCode::CREATED);
    let (_, categories) = request(&app, "GET", "/api/v1/categories", Value::Null).await;
    let category_id = categories[0]["id"].as_i64().unwrap();
    let project_id = project["id"].as_i64().unwrap();
    let (status, _) = request(
        &app,
        "PUT",
        &format!("/api/v1/categories/{category_id}"),
        json!({"name":"Winter"}),
    )
    .await;
    assert_eq!(status, StatusCode::OK);
    let (_, renamed) = request(
        &app,
        "GET",
        &format!("/api/v1/projects/{project_id}"),
        Value::Null,
    )
    .await;
    assert_eq!(renamed["category"], "Winter");
    assert!(renamed["updated_at"].as_str() > project["updated_at"].as_str());
    let (status, _) = request(
        &app,
        "DELETE",
        &format!("/api/v1/categories/{category_id}"),
        Value::Null,
    )
    .await;
    assert_eq!(status, StatusCode::NO_CONTENT);
    let (_, cleared) = request(
        &app,
        "GET",
        &format!("/api/v1/projects/{project_id}"),
        Value::Null,
    )
    .await;
    assert!(cleared["category"].is_null());
    assert!(cleared["updated_at"].as_str() > renamed["updated_at"].as_str());
}
