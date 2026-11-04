use axum::{
    Router,
    body::Body,
    http::{Request, StatusCode},
};
use http_body_util::BodyExt;
use serde_json::Value;
use sha2::{Digest, Sha256};
use sqlx::SqlitePool;
use stricknani_server::{archive_service, db};
use tower::ServiceExt;

const API_TOKEN: &str = "sna_archive-service-api-fixture";
const BROWSER_TOKEN: &str = "archive-service-browser-fixture";

struct Harness {
    app: Router,
    pool: SqlitePool,
    project_id: i64,
    yarn_id: i64,
    foreign_project_id: i64,
}

async fn harness(enabled: bool) -> Harness {
    let pool = db::connect("sqlite::memory:")
        .await
        .expect("test database should migrate");
    let owner_id = create_user(&pool, "archive-route@example.test").await;
    let foreign_owner_id = create_user(&pool, "archive-foreign@example.test").await;
    add_token(&pool, owner_id, API_TOKEN, "api").await;
    add_token(&pool, owner_id, BROWSER_TOKEN, "browser").await;
    let project_id = create_project(&pool, owner_id).await;
    let yarn_id = create_yarn(&pool, owner_id).await;
    let foreign_project_id = create_project(&pool, foreign_owner_id).await;
    let state = db::AppState::new(pool.clone());
    let app = archive_service::router(Router::new(), state, enabled);
    Harness {
        app,
        pool,
        project_id,
        yarn_id,
        foreign_project_id,
    }
}

async fn create_user(pool: &SqlitePool, email: &str) -> i64 {
    sqlx::query_scalar::<_, i64>(
        "INSERT INTO users (email, password_hash) VALUES (?, 'fixture-hash') RETURNING id",
    )
    .bind(email)
    .fetch_one(pool)
    .await
    .expect("fixture user should be created")
}

async fn add_token(pool: &SqlitePool, user_id: i64, token: &str, purpose: &str) {
    sqlx::query(
        "INSERT INTO api_tokens (user_id, name, token_hash, created_at, purpose) \
         VALUES (?, 'archive route fixture', ?, unixepoch(), ?)",
    )
    .bind(user_id)
    .bind(hex::encode(Sha256::digest(token.as_bytes())))
    .bind(purpose)
    .execute(pool)
    .await
    .expect("fixture token should be created");
}

async fn create_project(pool: &SqlitePool, owner_id: i64) -> i64 {
    sqlx::query_scalar::<_, i64>(
        "INSERT INTO projects (owner_id, name, link, created_at, updated_at) \
         VALUES (?, 'Archive route project', 'https://ravelry.com/pattern/route-test', \
                 '2026-01-01T00:00:00Z', '2026-01-01T00:00:00Z') RETURNING id",
    )
    .bind(owner_id)
    .fetch_one(pool)
    .await
    .expect("fixture project should be created")
}

async fn create_yarn(pool: &SqlitePool, owner_id: i64) -> i64 {
    sqlx::query_scalar::<_, i64>(
        "INSERT INTO yarns (owner_id, name, link, created_at, updated_at) \
         VALUES (?, 'Archive route yarn', 'https://ravelry.com/pattern/yarn-route-test', \
                 '2026-01-01T00:00:00Z', '2026-01-01T00:00:00Z') RETURNING id",
    )
    .bind(owner_id)
    .fetch_one(pool)
    .await
    .expect("fixture yarn should be created")
}

fn api_request(path: &str, token: Option<&str>) -> Request<Body> {
    let mut request = Request::post(path);
    if let Some(token) = token {
        request = request.header("authorization", format!("Bearer {token}"));
    }
    request
        .body(Body::empty())
        .expect("API request should build")
}

async fn json_body(response: axum::response::Response) -> Value {
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

async fn job_count(pool: &SqlitePool) -> i64 {
    sqlx::query_scalar("SELECT count(*) FROM archive_jobs")
        .fetch_one(pool)
        .await
        .expect("archive job count should be readable")
}

#[tokio::test]
async fn disabled_feature_authenticates_then_returns_503_without_enqueueing() {
    let harness = harness(false).await;
    let api = harness
        .app
        .clone()
        .oneshot(api_request(
            &format!("/api/v1/projects/{}/archive", harness.project_id),
            Some(API_TOKEN),
        ))
        .await
        .expect("API should respond");
    assert_eq!(api.status(), StatusCode::SERVICE_UNAVAILABLE);

    assert_eq!(job_count(&harness.pool).await, 0);

    let anonymous = harness
        .app
        .clone()
        .oneshot(api_request(
            &format!("/api/v1/projects/{}/archive", harness.project_id),
            None,
        ))
        .await
        .expect("API should respond");
    assert_eq!(anonymous.status(), StatusCode::UNAUTHORIZED);
    assert_eq!(job_count(&harness.pool).await, 0);
}

#[tokio::test]
async fn api_is_bearer_only_and_foreign_entities_are_not_found() {
    let harness = harness(true).await;
    let anonymous = harness
        .app
        .clone()
        .oneshot(api_request(
            &format!("/api/v1/projects/{}/archive", harness.project_id),
            None,
        ))
        .await
        .expect("API should respond");
    assert_eq!(anonymous.status(), StatusCode::UNAUTHORIZED);

    let foreign = harness
        .app
        .clone()
        .oneshot(api_request(
            &format!("/api/v1/projects/{}/archive", harness.foreign_project_id),
            Some(API_TOKEN),
        ))
        .await
        .expect("API should respond");
    assert_eq!(foreign.status(), StatusCode::NOT_FOUND);
    assert_eq!(job_count(&harness.pool).await, 0);
}

#[tokio::test]
async fn api_returns_accepted_state_and_duplicate_posts_share_one_job() {
    let harness = harness(true).await;
    let path = format!("/api/v1/projects/{}/archive", harness.project_id);
    for _ in 0..2 {
        let response = harness
            .app
            .clone()
            .oneshot(api_request(&path, Some(API_TOKEN)))
            .await
            .expect("API should respond");
        assert_eq!(response.status(), StatusCode::ACCEPTED);
        assert_eq!(json_body(response).await["state"], "pending");
    }
    let yarn_response = harness
        .app
        .clone()
        .oneshot(api_request(
            &format!("/api/v1/yarns/{}/archive", harness.yarn_id),
            Some(API_TOKEN),
        ))
        .await
        .expect("yarn API should respond");
    assert_eq!(yarn_response.status(), StatusCode::ACCEPTED);
    assert_eq!(job_count(&harness.pool).await, 2);
}
