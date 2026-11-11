use axum::{body::Body, http::Request};
use http_body_util::BodyExt;
use sha2::{Digest, Sha256};
use sqlx::SqlitePool;
use stricknani_server::{app_with_state, db};
use tower::ServiceExt;

const ADMIN_SESSION: &str = "sna_0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
const MEMBER_SESSION: &str = "sna_fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210";
const ADMIN_PAT: &str = "sna_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

struct TestApp {
    router: axum::Router,
    pool: SqlitePool,
}

async fn test_app() -> TestApp {
    let pool = db::connect("sqlite::memory:")
        .await
        .expect("test database should migrate");
    let admin_id = db::create_user(
        &pool,
        "<script>alert(1)</script>@example.test",
        "admin-audit-test-password".to_owned(),
    )
    .await
    .expect("admin account should be created");
    let member_id = db::create_user(
        &pool,
        "audit-member@example.test",
        "member-audit-test-password".to_owned(),
    )
    .await
    .expect("member account should be created");
    sqlx::query("UPDATE users SET is_admin = 1 WHERE id = ?")
        .bind(admin_id)
        .execute(&pool)
        .await
        .expect("admin role should be set");
    add_session(&pool, admin_id, ADMIN_SESSION).await;
    add_session(&pool, member_id, MEMBER_SESSION).await;
    add_api_token(&pool, admin_id, ADMIN_PAT).await;
    let router = app_with_state(db::AppState::new(pool.clone()).with_secure_cookies(true));
    TestApp { router, pool }
}

async fn add_session(pool: &SqlitePool, user_id: i64, token: &str) {
    sqlx::query(
        "INSERT INTO api_tokens (user_id, name, token_hash, created_at, expires_at, purpose) \
         VALUES (?, 'Audit test session', ?, unixepoch(), unixepoch() + 86400, 'browser')",
    )
    .bind(user_id)
    .bind(hex::encode(Sha256::digest(token.as_bytes())))
    .execute(pool)
    .await
    .expect("browser session should be stored");
}

async fn add_api_token(pool: &SqlitePool, user_id: i64, token: &str) {
    sqlx::query(
        "INSERT INTO api_tokens (user_id, name, token_hash, created_at, purpose) \
         VALUES (?, 'Audit test API token', ?, unixepoch(), 'api')",
    )
    .bind(user_id)
    .bind(hex::encode(Sha256::digest(token.as_bytes())))
    .execute(pool)
    .await
    .expect("API token should be stored");
}

async fn api(
    app: &TestApp,
    method: &str,
    path: &str,
    body: Option<&str>,
) -> axum::response::Response {
    let mut request = Request::builder()
        .method(method)
        .uri(path)
        .header("authorization", format!("Bearer {ADMIN_PAT}"));
    if body.is_some() {
        request = request.header("content-type", "application/json");
    }
    app.router
        .clone()
        .oneshot(
            request
                .body(body.map_or_else(Body::empty, |body| Body::from(body.to_owned())))
                .expect("API request should build"),
        )
        .await
        .expect("API router should respond")
}

async fn json(response: axum::response::Response) -> serde_json::Value {
    serde_json::from_slice(
        &response
            .into_body()
            .collect()
            .await
            .expect("JSON response should collect")
            .to_bytes(),
    )
    .expect("response should contain JSON")
}

async fn count_events(pool: &SqlitePool, entity: &str, target_id: i64) -> i64 {
    sqlx::query_scalar("SELECT count(*) FROM audit_events WHERE entity_type = ? AND entity_id = ?")
        .bind(entity)
        .bind(target_id)
        .fetch_one(pool)
        .await
        .expect("audit events should be countable")
}

async fn event_actions(pool: &SqlitePool, entity: &str, target_id: i64) -> Vec<String> {
    sqlx::query_scalar(
        "SELECT action FROM audit_events WHERE entity_type = ? AND entity_id = ? ORDER BY id",
    )
    .bind(entity)
    .bind(target_id)
    .fetch_all(pool)
    .await
    .expect("audit actions should be readable")
}

#[tokio::test]
async fn category_events_cover_mutations_but_not_duplicates_conflicts_or_missing_rows() {
    let app = test_app().await;
    let created = api(
        &app,
        "POST",
        "/api/v1/categories",
        Some(r#"{"name":"Lace"}"#),
    )
    .await;
    assert_eq!(created.status(), axum::http::StatusCode::CREATED);
    let category_id = json(created).await["id"]
        .as_i64()
        .expect("category ID should be numeric");
    assert_eq!(count_events(&app.pool, "category", category_id).await, 1);
    assert_eq!(
        event_actions(&app.pool, "category", category_id).await,
        vec!["created".to_owned()]
    );

    let duplicate = api(
        &app,
        "POST",
        "/api/v1/categories",
        Some(r#"{"name":"lace"}"#),
    )
    .await;
    assert_eq!(duplicate.status(), axum::http::StatusCode::CREATED);
    assert_eq!(json(duplicate).await["id"], category_id);
    assert_eq!(count_events(&app.pool, "category", category_id).await, 1);

    let other = api(
        &app,
        "POST",
        "/api/v1/categories",
        Some(r#"{"name":"Wool"}"#),
    )
    .await;
    assert_eq!(other.status(), axum::http::StatusCode::CREATED);
    let conflict_id = json(other).await["id"].as_i64().unwrap();
    assert_eq!(count_events(&app.pool, "category", conflict_id).await, 1);

    let conflict = api(
        &app,
        "PUT",
        &format!("/api/v1/categories/{category_id}"),
        Some(r#"{"name":"wOoL"}"#),
    )
    .await;
    assert_eq!(conflict.status(), axum::http::StatusCode::CONFLICT);
    assert_eq!(count_events(&app.pool, "category", category_id).await, 1);

    let updated = api(
        &app,
        "PUT",
        &format!("/api/v1/categories/{category_id}"),
        Some(r#"{"name":"Cotton"}"#),
    )
    .await;
    assert_eq!(updated.status(), axum::http::StatusCode::OK);
    assert_eq!(count_events(&app.pool, "category", category_id).await, 2);
    assert_eq!(
        event_actions(&app.pool, "category", category_id).await,
        vec!["created".to_owned(), "updated".to_owned()]
    );

    let missing = api(
        &app,
        "PUT",
        "/api/v1/categories/99999",
        Some(r#"{"name":"Missing"}"#),
    )
    .await;
    assert_eq!(missing.status(), axum::http::StatusCode::NOT_FOUND);

    let deleted = api(
        &app,
        "DELETE",
        &format!("/api/v1/categories/{category_id}"),
        None,
    )
    .await;
    assert_eq!(deleted.status(), axum::http::StatusCode::NO_CONTENT);
    assert_eq!(count_events(&app.pool, "category", category_id).await, 3);
    assert_eq!(
        event_actions(&app.pool, "category", category_id).await,
        vec![
            "created".to_owned(),
            "updated".to_owned(),
            "trashed".to_owned(),
        ]
    );

    let repeat_delete = api(
        &app,
        "DELETE",
        &format!("/api/v1/categories/{category_id}"),
        None,
    )
    .await;
    assert_eq!(repeat_delete.status(), axum::http::StatusCode::NOT_FOUND);
    assert_eq!(count_events(&app.pool, "category", category_id).await, 3);
}

#[tokio::test]
async fn yarn_events_share_mutation_transactions_and_skip_cas_conflicts() {
    let app = test_app().await;
    let created = api(
        &app,
        "POST",
        "/api/v1/yarns",
        Some(r#"{"name":"Blue merino"}"#),
    )
    .await;
    assert_eq!(created.status(), axum::http::StatusCode::CREATED);
    let created = json(created).await;
    let yarn_id = created["id"].as_i64().expect("yarn ID should be numeric");
    let original_updated_at = created["updated_at"].as_str().unwrap();
    assert_eq!(count_events(&app.pool, "yarn", yarn_id).await, 1);
    assert_eq!(
        event_actions(&app.pool, "yarn", yarn_id).await,
        vec!["created".to_owned()]
    );

    let favorite = api(
        &app,
        "POST",
        &format!("/api/v1/yarns/{yarn_id}/favorite"),
        None,
    )
    .await;
    assert_eq!(favorite.status(), axum::http::StatusCode::OK);
    let unfavorite = api(
        &app,
        "DELETE",
        &format!("/api/v1/yarns/{yarn_id}/favorite"),
        None,
    )
    .await;
    assert_eq!(unfavorite.status(), axum::http::StatusCode::OK);
    assert_eq!(count_events(&app.pool, "yarn", yarn_id).await, 1);

    let update_body =
        format!(r#"{{"name":"Red merino","expected_updated_at":"{original_updated_at}"}}"#);
    let updated = api(
        &app,
        "PUT",
        &format!("/api/v1/yarns/{yarn_id}"),
        Some(&update_body),
    )
    .await;
    assert_eq!(updated.status(), axum::http::StatusCode::OK);
    let updated = json(updated).await;
    assert_eq!(updated["name"], "Red merino");
    assert_eq!(count_events(&app.pool, "yarn", yarn_id).await, 2);
    assert_eq!(
        event_actions(&app.pool, "yarn", yarn_id).await,
        vec!["created".to_owned(), "updated".to_owned()]
    );

    let stale_body =
        format!(r#"{{"name":"Stale write","expected_updated_at":"{original_updated_at}"}}"#);
    let conflict = api(
        &app,
        "PUT",
        &format!("/api/v1/yarns/{yarn_id}"),
        Some(&stale_body),
    )
    .await;
    assert_eq!(conflict.status(), axum::http::StatusCode::CONFLICT);
    assert_eq!(count_events(&app.pool, "yarn", yarn_id).await, 2);

    let deleted = api(&app, "DELETE", &format!("/api/v1/yarns/{yarn_id}"), None).await;
    assert_eq!(deleted.status(), axum::http::StatusCode::NO_CONTENT);
    assert_eq!(count_events(&app.pool, "yarn", yarn_id).await, 3);
    assert_eq!(
        event_actions(&app.pool, "yarn", yarn_id).await,
        vec![
            "created".to_owned(),
            "updated".to_owned(),
            "trashed".to_owned(),
        ]
    );

    let repeated = api(&app, "DELETE", &format!("/api/v1/yarns/{yarn_id}"), None).await;
    assert_eq!(repeated.status(), axum::http::StatusCode::NOT_FOUND);
    assert_eq!(count_events(&app.pool, "yarn", yarn_id).await, 3);
}
