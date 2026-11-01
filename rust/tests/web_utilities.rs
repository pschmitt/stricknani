use std::path::PathBuf;

use axum::{body::Body, http::Request};
use http_body_util::BodyExt;
use sha2::{Digest, Sha256};
use sqlx::SqlitePool;
use stricknani_server::{app_with_state, db};
use tower::ServiceExt;

const SESSION: &str = "web-utilities-session-fixture";

struct Fixture {
    app: axum::Router,
    pool: SqlitePool,
    project_id: i64,
    yarn_id: i64,
}

async fn fixture(ocr_enabled: bool, archive_enabled: bool) -> Fixture {
    let pool = db::connect("sqlite::memory:")
        .await
        .expect("test database should migrate");
    let owner_id = db::create_user(
        &pool,
        "utilities@example.test",
        "fixture-password".to_owned(),
    )
    .await
    .expect("owner should be created");
    sqlx::query(
        "INSERT INTO api_tokens (user_id, name, token_hash, created_at, expires_at, purpose) \
         VALUES (?, 'browser fixture', ?, unixepoch(), unixepoch() + 86400, 'browser')",
    )
    .bind(owner_id)
    .bind(hex::encode(Sha256::digest(SESSION.as_bytes())))
    .execute(&pool)
    .await
    .expect("browser session should be created");
    let project_id: i64 = sqlx::query_scalar(
        "INSERT INTO projects (owner_id, name, link, link_archive, created_at, updated_at) \
         VALUES (?, 'Project', 'https://example.test/project', \
                 'https://web.archive.org/web/20260101000000/https://example.test/project', \
                 '2026-01-01T00:00:00Z', '2026-01-01T00:00:00Z') RETURNING id",
    )
    .bind(owner_id)
    .fetch_one(&pool)
    .await
    .expect("project should be created");
    let yarn_id: i64 = sqlx::query_scalar(
        "INSERT INTO yarns (owner_id, name, link, link_archive, created_at, updated_at) \
         VALUES (?, 'Yarn', 'https://example.test/yarn', \
                 'https://web.archive.org/web/20260101000000/https://example.test/yarn', \
                 '2026-01-01T00:00:00Z', '2026-01-01T00:00:00Z') RETURNING id",
    )
    .bind(owner_id)
    .fetch_one(&pool)
    .await
    .expect("yarn should be created");
    for (parent, kind, filename, public_id) in [
        ("project", "title", "project.png", 1_i64),
        ("yarn", "photo", "yarn.png", 2_i64),
    ] {
        sqlx::query(
            "INSERT INTO media_files \
             (owner_id, project_id, yarn_id, kind, public_id, filename, original_filename, \
              content_type, size_bytes, width, height, alt_text, is_primary, is_safe_inline, created_at) \
             VALUES (?, ?, ?, ?, ?, ?, ?, 'image/png', 8, 2, 2, 'fixture', 1, 1, \
                     '2026-01-01T00:00:00Z')",
        )
        .bind(owner_id)
        .bind((parent == "project").then_some(project_id))
        .bind((parent == "yarn").then_some(yarn_id))
        .bind(kind)
        .bind(public_id)
        .bind(filename)
        .bind(filename)
        .execute(&pool)
        .await
        .expect("image record should be created");
    }
    let state = db::AppState::new(pool.clone())
        .with_ocr_enabled(ocr_enabled)
        .with_archive_enabled(archive_enabled);
    let static_root = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../stricknani/static");
    Fixture {
        app: app_with_state(static_root, state),
        pool,
        project_id,
        yarn_id,
    }
}

async fn detail(fixture: &Fixture, path: &str) -> String {
    let response = fixture
        .app
        .clone()
        .oneshot(
            Request::get(path)
                .header("cookie", format!("stricknani_session={SESSION}"))
                .body(Body::empty())
                .expect("request should build"),
        )
        .await
        .expect("router should respond");
    assert_eq!(response.status(), axum::http::StatusCode::OK);
    String::from_utf8(
        response
            .into_body()
            .collect()
            .await
            .expect("body should be readable")
            .to_bytes()
            .to_vec(),
    )
    .expect("HTML should be UTF-8")
}

#[tokio::test]
async fn project_and_yarn_details_show_gated_ocr_and_csrf_archive_controls() {
    let fixture = fixture(true, true).await;
    for path in [
        format!("/projects/{}", fixture.project_id),
        format!("/yarns/{}", fixture.yarn_id),
    ] {
        let html = detail(&fixture, &path).await;
        let parsed = scraper::Html::parse_document(&html);
        let ocr = scraper::Selector::parse("[data-ocr-widget]").expect("selector is valid");
        let archive_form =
            scraper::Selector::parse("form[action$='/archive']").expect("selector is valid");
        let archived_link = scraper::Selector::parse("a[href^='https://web.archive.org/web/']")
            .expect("selector is valid");
        assert_eq!(parsed.select(&ocr).count(), 1);
        let archive_form = parsed
            .select(&archive_form)
            .next()
            .expect("archive action should be shown");
        assert!(
            archive_form
                .select(&scraper::Selector::parse("input[name='csrf']").expect("selector is valid"))
                .next()
                .is_some()
        );
        assert_eq!(parsed.select(&archived_link).count(), 1);
    }
}

#[tokio::test]
async fn disabled_controls_are_hidden_and_untrusted_archive_links_are_not_rendered() {
    let fixture = fixture(false, false).await;
    sqlx::query("UPDATE projects SET link_archive = 'javascript:alert(1)' WHERE id = ?")
        .bind(fixture.project_id)
        .execute(&fixture.pool)
        .await
        .expect("fixture link should update");
    let html = detail(&fixture, &format!("/projects/{}", fixture.project_id)).await;
    let parsed = scraper::Html::parse_document(&html);
    let ocr = scraper::Selector::parse("[data-ocr-widget]").expect("selector is valid");
    let archive_form =
        scraper::Selector::parse("form[action$='/archive']").expect("selector is valid");
    assert_eq!(parsed.select(&ocr).count(), 0);
    assert_eq!(parsed.select(&archive_form).count(), 0);
    assert!(!html.contains("javascript:alert(1)"));
}

#[test]
fn utility_script_uses_text_sinks_and_never_html_injection() {
    let script = include_str!("../../stricknani/static/rust-utilities.js");
    assert!(script.contains("X-CSRF-Token"));
    assert!(script.contains("status.textContent"));
    assert!(script.contains("output.value"));
    assert!(!script.contains("innerHTML"));
}
