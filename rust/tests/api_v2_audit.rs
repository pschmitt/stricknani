use axum::{
    Router,
    body::Body,
    http::{Request, StatusCode},
};
use http_body_util::BodyExt;
use sha2::{Digest, Sha256};
use sqlx::SqlitePool;
use stricknani_server::{api_v2, db};
use tower::ServiceExt;

const ADMIN_TOKEN: &str = "sna_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
const MEMBER_TOKEN: &str = "sna_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
const API_TOKEN: &str = "sna_cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc";

struct Fixture {
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
    Fixture {
        app: api_v2::router(
            db::AppState::new(pool.clone()).with_secure_cookies(true),
            false,
        ),
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
