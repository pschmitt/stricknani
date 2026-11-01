use axum::{body::Body, http::Request};
use http_body_util::BodyExt;
use scraper::{Html, Selector};
use sha2::{Digest, Sha256};
use sqlx::SqlitePool;
use std::path::PathBuf;
use stricknani_server::{app_with_state, db};
use tower::ServiceExt;

const ADMIN_SESSION: &str = "sna_0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
const MEMBER_SESSION: &str = "sna_fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210";
const ADMIN_PAT: &str = "sna_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

struct TestApp {
    router: axum::Router,
    pool: SqlitePool,
    admin_id: i64,
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
    let static_root = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../stricknani/static");
    let router = app_with_state(
        static_root,
        db::AppState::new(pool.clone()).with_secure_cookies(true),
    );
    TestApp {
        router,
        pool,
        admin_id,
    }
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

async fn get(app: &TestApp, path: &str, token: Option<&str>) -> axum::response::Response {
    let mut request = Request::get(path);
    if let Some(token) = token {
        request = request.header("cookie", format!("__Host-stricknani_session={token}"));
    }
    app.router
        .clone()
        .oneshot(request.body(Body::empty()).expect("request should build"))
        .await
        .expect("router should handle request")
}

async fn html(response: axum::response::Response) -> String {
    String::from_utf8(
        response
            .into_body()
            .collect()
            .await
            .expect("response body should collect")
            .to_bytes()
            .to_vec(),
    )
    .expect("page should be UTF-8")
}

fn parse_selector(selector: &str) -> Selector {
    Selector::parse(selector).expect("test selector should be valid")
}

fn visible_text(document: &Html) -> String {
    document.root_element().text().collect::<String>()
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
async fn admin_view_is_protected_and_inactive_admin_is_denied() {
    let app = test_app().await;
    let anonymous = get(&app, "/admin/audit", None).await;
    assert_eq!(anonymous.status(), axum::http::StatusCode::SEE_OTHER);
    assert_eq!(anonymous.headers()["location"], "/login");

    let member = get(&app, "/admin/audit", Some(MEMBER_SESSION)).await;
    assert_eq!(member.status(), axum::http::StatusCode::FORBIDDEN);

    let admin = get(&app, "/admin/audit", Some(ADMIN_SESSION)).await;
    assert_eq!(admin.status(), axum::http::StatusCode::OK);
    let cache_control = admin.headers()["cache-control"]
        .to_str()
        .expect("cache policy should be valid header text");
    assert!(
        cache_control
            .split(',')
            .map(str::trim)
            .any(|directive| directive.eq_ignore_ascii_case("no-store")),
        "admin audit pages must not be stored: {cache_control:?}"
    );

    sqlx::query("UPDATE users SET active = 0 WHERE id = ?")
        .bind(app.admin_id)
        .execute(&app.pool)
        .await
        .expect("admin should deactivate");
    let inactive = get(&app, "/admin/audit", Some(ADMIN_SESSION)).await;
    assert_eq!(inactive.status(), axum::http::StatusCode::SEE_OTHER);
}

#[tokio::test]
async fn view_escapes_legacy_text_and_never_renders_details_json() {
    let app = test_app().await;
    sqlx::query(
        "INSERT INTO audit_events \
         (actor_user_id, entity_type, entity_id, action, details, created_at) \
         VALUES (?, 'project', 73, '<img src=x onerror=alert(1)>', \
                 '{\"notes\":\"secret-note-must-not-render\"}', '2026-01-01T00:00:00.000000Z')",
    )
    .bind(app.admin_id)
    .execute(&app.pool)
    .await
    .expect("legacy event should be inserted");
    sqlx::query(
        "INSERT INTO audit_events \
         (actor_user_id, legacy_actor_user_id, entity_type, entity_id, action, details, created_at) \
         VALUES (NULL, 987, 'yarn', 42, 'deleted', '{\"name\":\"private\"}', \
                 '2026-01-02T00:00:00.000000Z')",
    )
    .execute(&app.pool)
    .await
    .expect("event with missing legacy actor should be inserted");

    let response = get(&app, "/admin/audit", Some(ADMIN_SESSION)).await;
    assert_eq!(response.status(), axum::http::StatusCode::OK);
    let page = html(response).await;
    let document = Html::parse_document(&page);
    let visible = visible_text(&document);

    let strong = document
        .select(&parse_selector("p strong"))
        .next()
        .expect("signed-in account should be visible");
    assert_eq!(
        strong.text().collect::<String>(),
        "<script>alert(1)</script>@example.test"
    );
    let action_cells = document
        .select(&parse_selector("tbody tr td:nth-child(4)"))
        .map(|cell| cell.text().collect::<String>())
        .collect::<Vec<_>>();
    assert!(
        action_cells
            .iter()
            .any(|action| { action == "<img src=x onerror=alert(1)>" })
    );
    assert!(
        document
            .select(&parse_selector("table img"))
            .next()
            .is_none()
    );
    assert!(
        document
            .select(&parse_selector("script:not([src])"))
            .next()
            .is_none(),
        "untrusted account text must not create executable script elements"
    );
    assert!(visible.contains("Legacy account #987"));
    assert!(!visible.contains("secret-note-must-not-render"));
    assert!(!visible.contains("private"));
}

#[tokio::test]
async fn entity_action_filters_and_pagination_are_bounded_and_preserved() {
    let app = test_app().await;
    for target_id in 1000..1052 {
        sqlx::query(
            "INSERT INTO audit_events \
             (actor_user_id, entity_type, entity_id, action, created_at) \
             VALUES (?, 'project', ?, 'created', '2026-02-01T00:00:00.000000Z')",
        )
        .bind(app.admin_id)
        .bind(target_id)
        .execute(&app.pool)
        .await
        .expect("project audit row should be inserted");
    }
    sqlx::query(
        "INSERT INTO audit_events \
         (actor_user_id, entity_type, entity_id, action, created_at) \
         VALUES (?, 'yarn', 55, 'created', '2026-02-02T00:00:00.000000Z')",
    )
    .bind(app.admin_id)
    .execute(&app.pool)
    .await
    .expect("yarn audit row should be inserted");

    let first_page = get(
        &app,
        "/admin/audit?entity=project&action=created",
        Some(ADMIN_SESSION),
    )
    .await;
    assert_eq!(first_page.status(), axum::http::StatusCode::OK);
    let first_page = html(first_page).await;
    let first_document = Html::parse_document(&first_page);
    let first_text = visible_text(&first_document);
    assert!(first_text.contains("#1051"));
    assert!(!first_text.contains("#1000"));
    let next_href = first_document
        .select(&parse_selector("nav.pagination a[href*='page=2']"))
        .filter_map(|anchor| anchor.value().attr("href"))
        .next()
        .expect("next page link should be present");
    assert_eq!(
        next_href,
        "/admin/audit?page=2&entity=project&action=created"
    );

    let second_page = get(
        &app,
        "/admin/audit?page=2&entity=project&action=created",
        Some(ADMIN_SESSION),
    )
    .await;
    assert_eq!(second_page.status(), axum::http::StatusCode::OK);
    let second_page = html(second_page).await;
    let second_text = visible_text(&Html::parse_document(&second_page));
    assert!(second_text.contains("#1000"));
    assert!(!second_text.contains("#1051"));

    assert_eq!(
        get(&app, "/admin/audit?page=0", Some(ADMIN_SESSION))
            .await
            .status(),
        axum::http::StatusCode::BAD_REQUEST
    );
    assert_eq!(
        get(&app, "/admin/audit?entity=untrusted", Some(ADMIN_SESSION))
            .await
            .status(),
        axum::http::StatusCode::BAD_REQUEST
    );
    assert_eq!(
        get(
            &app,
            &format!("/admin/audit?action={}", "a".repeat(513)),
            Some(ADMIN_SESSION),
        )
        .await
        .status(),
        axum::http::StatusCode::BAD_REQUEST
    );
    assert_eq!(
        get(&app, "/admin/audit?page=1&page=2", Some(ADMIN_SESSION),)
            .await
            .status(),
        axum::http::StatusCode::BAD_REQUEST
    );
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
            "deleted".to_owned(),
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
            "deleted".to_owned(),
        ]
    );

    let repeated = api(&app, "DELETE", &format!("/api/v1/yarns/{yarn_id}"), None).await;
    assert_eq!(repeated.status(), axum::http::StatusCode::NOT_FOUND);
    assert_eq!(count_events(&app.pool, "yarn", yarn_id).await, 3);
}
