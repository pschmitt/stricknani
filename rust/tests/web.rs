use std::io::Cursor;
use std::path::PathBuf;

use axum::{
    body::Body,
    http::{Request, StatusCode},
};
use http_body_util::BodyExt;
use sha2::Digest;
use sqlx::SqlitePool;
use stricknani_server::{app_with_state, db};
use tower::ServiceExt;

const EMAIL: &str = "web@example.test";
const PASSWORD: &str = "a-long-browser-password";

async fn real_app() -> (axum::Router, SqlitePool) {
    let pool = db::connect("sqlite::memory:")
        .await
        .expect("disposable database should migrate");
    db::create_user(&pool, EMAIL, PASSWORD.to_owned())
        .await
        .expect("test account should be created");
    let static_root = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../stricknani/static");
    (
        app_with_state(
            static_root,
            db::AppState::new(pool.clone()).with_secure_cookies(true),
        ),
        pool,
    )
}

async fn real_app_with_media() -> (axum::Router, SqlitePool, tempfile::TempDir) {
    let pool = db::connect("sqlite::memory:")
        .await
        .expect("disposable database should migrate");
    db::create_user(&pool, EMAIL, PASSWORD.to_owned())
        .await
        .expect("test account should be created");
    let root = tempfile::tempdir().expect("media directory should be created");
    let static_root = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../stricknani/static");
    let app = app_with_state(
        static_root,
        db::AppState::new(pool.clone())
            .with_secure_cookies(true)
            .with_media_root(root.path().to_path_buf()),
    );
    (app, pool, root)
}

fn browser_upload_request(path: &str, session: &str, csrf: &str, bytes: &[u8]) -> Request<Body> {
    browser_upload_request_as(path, session, csrf, bytes, "image/png", Some("Sample"))
}

fn browser_upload_request_as(
    path: &str,
    session: &str,
    csrf: &str,
    bytes: &[u8],
    content_type: &str,
    alt_text: Option<&str>,
) -> Request<Body> {
    let boundary = "browser-upload-boundary";
    let mut body = Vec::new();
    body.extend_from_slice(
        format!("--{boundary}\r\nContent-Disposition: form-data; name=\"csrf\"\r\n\r\n{csrf}\r\n")
            .as_bytes(),
    );
    body.extend_from_slice(format!("--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"sample.upload\"\r\nContent-Type: {content_type}\r\n\r\n").as_bytes());
    body.extend_from_slice(bytes);
    body.extend_from_slice(b"\r\n");
    if let Some(alt_text) = alt_text {
        body.extend_from_slice(format!("--{boundary}\r\nContent-Disposition: form-data; name=\"alt_text\"\r\n\r\n{alt_text}\r\n").as_bytes());
    }
    body.extend_from_slice(format!("--{boundary}--\r\n").as_bytes());
    Request::post(path)
        .header("cookie", format!("__Host-stricknani_session={session}"))
        .header(
            "content-type",
            format!("multipart/form-data; boundary={boundary}"),
        )
        .body(Body::from(body))
        .expect("multipart browser request is valid")
}

fn test_png() -> Vec<u8> {
    let image = image::DynamicImage::new_rgba8(2, 2);
    let mut output = Cursor::new(Vec::new());
    image
        .write_to(&mut output, image::ImageFormat::Png)
        .expect("test PNG should encode");
    output.into_inner()
}

async fn body(response: axum::response::Response) -> String {
    String::from_utf8(
        response
            .into_body()
            .collect()
            .await
            .expect("response body should be readable")
            .to_bytes()
            .to_vec(),
    )
    .expect("response body should be UTF-8")
}

async fn login_page(app: &axum::Router) -> (String, String) {
    let response = app
        .clone()
        .oneshot(
            Request::get("/login")
                .body(Body::empty())
                .expect("request is valid"),
        )
        .await
        .expect("router should respond");
    assert_eq!(response.status(), StatusCode::OK);
    let csrf_cookie = response
        .headers()
        .get_all("set-cookie")
        .iter()
        .find_map(|value| {
            let value = value.to_str().ok()?;
            let (name, value) = value.split_once('=')?;
            matches!(
                name,
                "stricknani_login_csrf" | "__Host-stricknani_login_csrf"
            )
            .then(|| value.split(';').next())?
        })
        .expect("login CSRF cookie should be set")
        .to_owned();
    let html = body(response).await;
    assert!(html.contains("name=\"csrf\""));
    let field = html
        .split("name=\"csrf\" value=\"")
        .nth(1)
        .and_then(|value| value.split('\"').next())
        .expect("login page should contain hidden CSRF field")
        .to_owned();
    assert_eq!(field, csrf_cookie);
    (csrf_cookie, field)
}

fn form_request(path: &str, cookie: &str, fields: &[(&str, &str)]) -> Request<Body> {
    let encoded = fields
        .iter()
        .map(|(key, value)| {
            format!(
                "{key}={}",
                url::form_urlencoded::byte_serialize(value.as_bytes()).collect::<String>()
            )
        })
        .collect::<Vec<_>>()
        .join("&");
    Request::post(path)
        .header("content-type", "application/x-www-form-urlencoded")
        .header("cookie", cookie)
        .body(Body::from(encoded))
        .expect("form request is valid")
}

async fn authenticated(app: &axum::Router) -> String {
    let (csrf_cookie, csrf_field) = login_page(app).await;
    let response = app
        .clone()
        .oneshot(form_request(
            "/login",
            &format!("__Host-stricknani_login_csrf={csrf_cookie}"),
            &[
                ("email", EMAIL),
                ("password", PASSWORD),
                ("csrf", &csrf_field),
            ],
        ))
        .await
        .expect("router should respond");
    assert_eq!(response.status(), StatusCode::SEE_OTHER);
    let session_cookie = response
        .headers()
        .get_all("set-cookie")
        .iter()
        .find_map(|value| {
            let value = value.to_str().ok()?;
            let (name, value) = value.split_once('=')?;
            matches!(name, "stricknani_session" | "__Host-stricknani_session")
                .then(|| value.split(';').next())?
        })
        .expect("session cookie should be set")
        .to_owned();
    for value in response.headers().get_all("set-cookie") {
        let value = value.to_str().expect("cookie header is valid");
        if value.starts_with("__Host-stricknani_session=") {
            assert!(value.contains("HttpOnly"));
            assert!(value.contains("SameSite=Lax"));
            assert!(value.contains("Secure"));
        }
    }
    session_cookie
}

#[tokio::test]
async fn login_page_sets_matching_csrf_cookie_and_hidden_field() {
    let (app, _) = real_app().await;
    let (cookie, field) = login_page(&app).await;
    assert_eq!(cookie, field);
}

#[tokio::test]
async fn login_rejects_wrong_csrf_before_authenticating() {
    let (app, pool) = real_app().await;
    let (cookie, _) = login_page(&app).await;
    let response = app
        .oneshot(form_request(
            "/login",
            &format!("__Host-stricknani_login_csrf={cookie}"),
            &[("email", EMAIL), ("password", PASSWORD), ("csrf", "wrong")],
        ))
        .await
        .expect("router should respond");
    assert_eq!(response.status(), StatusCode::FORBIDDEN);
    let token_count: i64 = sqlx::query_scalar("SELECT count(*) FROM api_tokens")
        .fetch_one(&pool)
        .await
        .expect("token count should be readable");
    assert_eq!(token_count, 0);
}

#[tokio::test]
async fn browser_session_cookie_is_secure_and_api_still_requires_bearer_auth() {
    let (app, _) = real_app().await;
    let session = authenticated(&app).await;
    let unprefixed = app
        .clone()
        .oneshot(
            Request::get("/projects")
                .header("cookie", format!("stricknani_session={session}"))
                .body(Body::empty())
                .expect("request is valid"),
        )
        .await
        .expect("router should respond");
    assert_eq!(unprefixed.status(), StatusCode::SEE_OTHER);
    let response = app
        .oneshot(
            Request::get("/api/v1/projects")
                .header("cookie", format!("__Host-stricknani_session={session}"))
                .body(Body::empty())
                .expect("request is valid"),
        )
        .await
        .expect("router should respond");
    assert_eq!(response.status(), StatusCode::UNAUTHORIZED);
}

#[tokio::test]
async fn private_catalog_escapes_names_and_detail_sanitizes_markdown() {
    let (app, _) = real_app().await;
    let session = authenticated(&app).await;
    let created = app
        .clone()
        .oneshot(
            Request::post("/api/v1/projects")
                .header("authorization", format!("Bearer {session}"))
                .header("content-type", "application/json")
                .body(Body::from(
                    serde_json::json!({
                        "name": "<script>alert(1)</script> & Socks",
                        "description": "[safe](https://example.test) <script>alert(2)</script>",
                        "notes": "<img src=x onerror=alert(3)>",
                    })
                    .to_string(),
                ))
                .expect("request is valid"),
        )
        .await
        .expect("router should respond");
    assert_eq!(created.status(), StatusCode::CREATED);
    let created: serde_json::Value =
        serde_json::from_str(&body(created).await).expect("created project should be JSON");
    let id = created["id"]
        .as_i64()
        .expect("project ID should be numeric");

    let catalog = app
        .clone()
        .oneshot(
            Request::get("/projects")
                .header("cookie", format!("__Host-stricknani_session={session}"))
                .body(Body::empty())
                .expect("request is valid"),
        )
        .await
        .expect("router should respond");
    let catalog = body(catalog).await;
    let parsed_catalog = scraper::Html::parse_document(&catalog);
    let catalog_text = parsed_catalog.root_element().text().collect::<String>();
    assert!(catalog_text.contains("<script>alert(1)</script> & Socks"));
    let script_selector =
        scraper::Selector::parse("script:not([src])").expect("script selector is valid");
    assert!(parsed_catalog.select(&script_selector).next().is_none());

    let detail = app
        .oneshot(
            Request::get(format!("/projects/{id}"))
                .header("cookie", format!("__Host-stricknani_session={session}"))
                .body(Body::empty())
                .expect("request is valid"),
        )
        .await
        .expect("router should respond");
    let detail = body(detail).await;
    assert!(detail.contains("<a href=\"https://example.test\""));
    assert!(!detail.contains("<script>"));
    assert!(!detail.contains("onerror"));
}

#[tokio::test]
async fn detail_requires_ownership_and_logout_revokes_session_token() {
    let (app, pool) = real_app().await;
    let owner_id: i64 = sqlx::query_scalar("SELECT id FROM users WHERE email = ?")
        .bind(EMAIL)
        .fetch_one(&pool)
        .await
        .expect("test user should exist");
    let project_id: i64 = sqlx::query_scalar(
        "INSERT INTO projects (owner_id, name, created_at, updated_at) VALUES (?, 'Private', '2026-01-01T00:00:00.000000Z', '2026-01-01T00:00:00.000000Z') RETURNING id",
    )
    .bind(owner_id)
    .fetch_one(&pool)
    .await
    .expect("private project should be inserted");
    let stranger_id = db::create_user(&pool, "stranger@example.test", PASSWORD.to_owned())
        .await
        .expect("stranger should be created");
    let token = "stranger-browser-token";
    let anonymous_response = app
        .clone()
        .oneshot(
            Request::get(format!("/projects/{project_id}"))
                .body(Body::empty())
                .expect("request is valid"),
        )
        .await
        .expect("router should respond");
    assert_eq!(anonymous_response.status(), StatusCode::SEE_OTHER);
    let token_hash = hex::encode(sha2::Sha256::digest(token.as_bytes()));
    sqlx::query("INSERT INTO api_tokens (user_id, name, token_hash, created_at, expires_at, purpose) VALUES (?, 'test', ?, unixepoch(), unixepoch() + 86400, 'browser')")
        .bind(stranger_id)
        .bind(token_hash)
        .execute(&pool)
        .await
        .expect("stranger token should be inserted");
    let stranger_response = app
        .clone()
        .oneshot(
            Request::get(format!("/projects/{project_id}"))
                .header("cookie", format!("__Host-stricknani_session={token}"))
                .body(Body::empty())
                .expect("request is valid"),
        )
        .await
        .expect("router should respond");
    assert_eq!(stranger_response.status(), StatusCode::NOT_FOUND);

    let session = authenticated(&app).await;
    let csrf = {
        let response = app
            .clone()
            .oneshot(
                Request::get(format!("/projects/{project_id}"))
                    .header("cookie", format!("__Host-stricknani_session={session}"))
                    .body(Body::empty())
                    .expect("request is valid"),
            )
            .await
            .expect("router should respond");
        let html = body(response).await;
        html.split("name=\"csrf\" value=\"")
            .nth(1)
            .and_then(|value| value.split('\"').next())
            .expect("authenticated page should have logout CSRF field")
            .to_owned()
    };
    let logout = app
        .clone()
        .oneshot(form_request(
            "/logout",
            &format!("__Host-stricknani_session={session}"),
            &[("csrf", &csrf)],
        ))
        .await
        .expect("router should respond");
    assert_eq!(logout.status(), StatusCode::SEE_OTHER);
    let revoked_at: Option<i64> =
        sqlx::query_scalar("SELECT revoked_at FROM api_tokens WHERE token_hash = ?")
            .bind(hex::encode(sha2::Sha256::digest(session.as_bytes())))
            .fetch_one(&pool)
            .await
            .expect("session token should exist");
    assert!(revoked_at.is_some());
}

fn hidden(html: &str, name: &str) -> String {
    html.split(&format!("name=\"{name}\" value=\""))
        .nth(1)
        .and_then(|tail| tail.split('\"').next())
        .unwrap_or_default()
        .to_owned()
}

async fn get_html(app: &axum::Router, path: &str, session: &str) -> String {
    let response = app
        .clone()
        .oneshot(
            Request::get(path)
                .header("cookie", format!("__Host-stricknani_session={session}"))
                .body(Body::empty())
                .expect("request is valid"),
        )
        .await
        .expect("router should respond");
    assert_eq!(response.status(), StatusCode::OK);
    body(response).await
}

#[tokio::test]
async fn browser_project_create_and_edit_preserve_step_ids_and_whole_resource_fields() {
    let (app, _) = real_app().await;
    let session = authenticated(&app).await;
    let csrf = hidden(&get_html(&app, "/projects/new", &session).await, "csrf");
    let created = app
        .clone()
        .oneshot(form_request(
            "/projects/new",
            &format!("__Host-stricknani_session={session}"),
            &[
                ("csrf", &csrf),
                ("name", "Sleeves"),
                ("category", "Sweaters"),
                ("needles", "4 mm"),
                ("description", "Keep this description"),
                ("notes", "Keep these notes"),
                ("tags", "warm\nblue"),
                ("step_title", "Cast on"),
                ("step_description", "Twenty stitches"),
                ("step_number", "1"),
            ],
        ))
        .await
        .expect("router should respond");
    assert_eq!(created.status(), StatusCode::SEE_OTHER);
    let location = created
        .headers()
        .get("location")
        .and_then(|v| v.to_str().ok())
        .expect("edit redirect exists")
        .to_owned();
    let id = location
        .split('/')
        .nth(2)
        .expect("project ID in redirect")
        .parse::<i64>()
        .expect("numeric ID");
    let created_value: serde_json::Value = serde_json::from_str(
        &body(
            app.clone()
                .oneshot(
                    Request::get(format!("/api/v1/projects/{id}"))
                        .header("authorization", format!("Bearer {session}"))
                        .body(Body::empty())
                        .expect("request valid"),
                )
                .await
                .expect("API responds"),
        )
        .await,
    )
    .expect("project JSON");
    let step_id = created_value["steps"][0]["id"].as_i64().expect("step id");
    let edit = get_html(&app, &location, &session).await;
    assert!(edit.contains(&format!("name=\"step_id\" value=\"{step_id}\"")));
    let stale = hidden(&edit, "expected_updated_at");
    let saved = app
        .clone()
        .oneshot(form_request(
            &format!("/projects/{id}/save"),
            &format!("__Host-stricknani_session={session}"),
            &[
                ("csrf", &hidden(&edit, "csrf")),
                ("expected_updated_at", &stale),
                ("name", "Sleeves revised"),
                ("category", "Sweaters"),
                ("needles", "4 mm"),
                ("description", "Keep this description"),
                ("notes", "Keep these notes"),
                ("tags", "warm\nblue"),
                ("step_id", &step_id.to_string()),
                ("step_title", "Cast on revised"),
                ("step_description", "Twenty stitches"),
                ("step_number", "1"),
            ],
        ))
        .await
        .expect("router should respond");
    assert_eq!(saved.status(), StatusCode::SEE_OTHER);
    let after: serde_json::Value = serde_json::from_str(
        &body(
            app.clone()
                .oneshot(
                    Request::get(format!("/api/v1/projects/{id}"))
                        .header("authorization", format!("Bearer {session}"))
                        .body(Body::empty())
                        .expect("request valid"),
                )
                .await
                .expect("API responds"),
        )
        .await,
    )
    .expect("project JSON");
    assert_eq!(after["steps"][0]["id"], step_id);
    assert_eq!(after["notes"], "Keep these notes");
    assert_eq!(after["tags"], serde_json::json!(["warm", "blue"]));
}

#[tokio::test]
async fn browser_mutations_require_csrf_and_foreign_owner_cannot_edit() {
    let (app, pool) = real_app().await;
    let owner = authenticated(&app).await;
    let create_csrf = hidden(&get_html(&app, "/projects/new", &owner).await, "csrf");
    let response = app
        .clone()
        .oneshot(form_request(
            "/projects/new",
            &format!("__Host-stricknani_session={owner}"),
            &[("csrf", "bad"), ("name", "Private")],
        ))
        .await
        .expect("router responds");
    assert_eq!(response.status(), StatusCode::FORBIDDEN);
    let created = app
        .clone()
        .oneshot(form_request(
            "/projects/new",
            &format!("__Host-stricknani_session={owner}"),
            &[("csrf", &create_csrf), ("name", "Private")],
        ))
        .await
        .expect("router responds");
    let id = created
        .headers()
        .get("location")
        .and_then(|v| v.to_str().ok())
        .unwrap()
        .split('/')
        .nth(2)
        .unwrap()
        .parse::<i64>()
        .unwrap();
    db::create_user(&pool, "other-web@example.test", PASSWORD.to_owned())
        .await
        .expect("second account");
    let user_id: i64 = sqlx::query_scalar("SELECT id FROM users WHERE email = ?")
        .bind("other-web@example.test")
        .fetch_one(&pool)
        .await
        .expect("user exists");
    let token = "second-owner-session";
    sqlx::query("INSERT INTO api_tokens(user_id,name,token_hash,created_at,expires_at,purpose) VALUES(?, 'browser', ?, unixepoch(), unixepoch() + 86400, 'browser')").bind(user_id).bind(hex::encode(sha2::Sha256::digest(token.as_bytes()))).execute(&pool).await.expect("token inserted");
    let response = app
        .oneshot(
            Request::get(format!("/projects/{id}/edit"))
                .header("cookie", format!("__Host-stricknani_session={token}"))
                .body(Body::empty())
                .expect("request valid"),
        )
        .await
        .expect("router responds");
    assert_eq!(response.status(), StatusCode::NOT_FOUND);
}

#[tokio::test]
async fn browser_favorite_delete_and_conflict_use_api_semantics() {
    let (app, _) = real_app().await;
    let session = authenticated(&app).await;
    let csrf = hidden(&get_html(&app, "/yarns/new", &session).await, "csrf");
    let created = app
        .clone()
        .oneshot(form_request(
            "/yarns/new",
            &format!("__Host-stricknani_session={session}"),
            &[
                ("csrf", &csrf),
                ("name", "Blue wool"),
                ("brand", "Local mill"),
                ("colorway", "Indigo"),
                ("notes", "Keep notes"),
                ("fiber_content", "Wool"),
                ("weight_category", "DK"),
            ],
        ))
        .await
        .expect("router responds");
    let id = created
        .headers()
        .get("location")
        .and_then(|v| v.to_str().ok())
        .unwrap()
        .split('/')
        .nth(2)
        .unwrap()
        .parse::<i64>()
        .unwrap();
    let edit = get_html(&app, &format!("/yarns/{id}/edit"), &session).await;
    let timestamp = hidden(&edit, "expected_updated_at");
    let edit_csrf = hidden(&edit, "csrf");
    let update=app.clone().oneshot(Request::put(format!("/api/v1/yarns/{id}")).header("authorization",format!("Bearer {session}")).header("content-type","application/json").body(Body::from(serde_json::json!({"name":"Blue wool current","expected_updated_at":timestamp,"notes":"Keep notes","brand":"Local mill","colorway":"Indigo","fiber_content":"Wool","weight_category":"DK"}).to_string())).expect("request valid")).await.expect("API responds");
    assert_eq!(update.status(), StatusCode::OK);
    let conflict = app
        .clone()
        .oneshot(form_request(
            &format!("/yarns/{id}/save"),
            &format!("__Host-stricknani_session={session}"),
            &[
                ("csrf", &edit_csrf),
                ("expected_updated_at", &timestamp),
                ("name", "My stale edit"),
                ("notes", "Keep notes"),
                ("brand", "Local mill"),
                ("colorway", "Indigo"),
                ("fiber_content", "Wool"),
                ("weight_category", "DK"),
            ],
        ))
        .await
        .expect("router responds");
    assert_eq!(conflict.status(), StatusCode::OK);
    let html = body(conflict).await;
    assert!(html.contains("My stale edit"));
    assert!(html.contains("Blue wool current"));
    let favorite = app
        .clone()
        .oneshot(form_request(
            &format!("/yarns/{id}/favorite"),
            &format!("__Host-stricknani_session={session}"),
            &[("csrf", &edit_csrf)],
        ))
        .await
        .expect("router responds");
    assert_eq!(favorite.status(), StatusCode::SEE_OTHER);
    let confirm = app
        .clone()
        .oneshot(
            Request::get(format!("/yarns/{id}/delete"))
                .header("cookie", format!("__Host-stricknani_session={session}"))
                .body(Body::empty())
                .expect("request valid"),
        )
        .await
        .expect("router responds");
    let confirm_html = body(confirm).await;
    let delete = app
        .clone()
        .oneshot(form_request(
            &format!("/yarns/{id}/delete"),
            &format!("__Host-stricknani_session={session}"),
            &[("csrf", &hidden(&confirm_html, "csrf"))],
        ))
        .await
        .expect("router responds");
    assert_eq!(delete.status(), StatusCode::SEE_OTHER);
    let gone = app
        .oneshot(
            Request::get(format!("/api/v1/yarns/{id}"))
                .header("authorization", format!("Bearer {session}"))
                .body(Body::empty())
                .expect("request valid"),
        )
        .await
        .expect("API responds");
    assert_eq!(gone.status(), StatusCode::NOT_FOUND);
}

#[tokio::test]
async fn browser_category_management_uses_authenticated_api_operations() {
    let (app, _) = real_app().await;
    let session = authenticated(&app).await;
    let page = get_html(&app, "/categories", &session).await;
    let csrf = hidden(&page, "csrf");
    let created = app
        .clone()
        .oneshot(form_request(
            "/categories/new",
            &format!("__Host-stricknani_session={session}"),
            &[("csrf", &csrf), ("name", "Accessories")],
        ))
        .await
        .expect("router responds");
    assert_eq!(created.status(), StatusCode::SEE_OTHER);
    let listing = app
        .clone()
        .oneshot(
            Request::get("/api/v1/categories")
                .header("authorization", format!("Bearer {session}"))
                .body(Body::empty())
                .expect("request is valid"),
        )
        .await
        .expect("API responds");
    let categories: serde_json::Value =
        serde_json::from_str(&body(listing).await).expect("categories are JSON");
    assert!(
        categories
            .as_array()
            .unwrap()
            .iter()
            .any(|row| row["name"] == "Accessories")
    );
}

#[tokio::test]
async fn project_and_yarn_search_matches_text_fields_with_literal_wildcards() {
    let (app, pool) = real_app().await;
    let session = authenticated(&app).await;
    let owner_id: i64 = sqlx::query_scalar("SELECT id FROM users WHERE email = ?")
        .bind(EMAIL)
        .fetch_one(&pool)
        .await
        .unwrap();
    let category_id: i64 = sqlx::query_scalar(
        "INSERT INTO categories(user_id, name) VALUES (?, 'Cardigan') RETURNING id",
    )
    .bind(owner_id)
    .fetch_one(&pool)
    .await
    .unwrap();
    let project_id: i64 = sqlx::query_scalar(
        "INSERT INTO projects(owner_id,category_id,name,tags,notes,created_at,updated_at) \
         VALUES(?,?,'Winter cowl 100%','[\"blue\"]','Needle chart', \
         '2026-01-01T00:00:00.000000Z','2026-01-01T00:00:00.000000Z') RETURNING id",
    )
    .bind(owner_id)
    .bind(category_id)
    .fetch_one(&pool)
    .await
    .unwrap();
    sqlx::query(
        "INSERT INTO projects(owner_id,category_id,name,tags,notes,created_at,updated_at) \
         VALUES(?,?,'Winter cowl 100x','[\"blue\"]','Other notes', \
         '2026-01-01T00:00:00.000000Z','2026-01-01T00:00:00.000000Z')",
    )
    .bind(owner_id)
    .bind(category_id)
    .execute(&pool)
    .await
    .unwrap();
    let other_id = db::create_user(&pool, "search-other@example.test", PASSWORD.to_owned())
        .await
        .unwrap();
    sqlx::query(
        "INSERT INTO projects(owner_id,category_id,name,tags,notes,created_at,updated_at) \
         VALUES(?,?,'Winter cowl 100%','[\"blue\"]','Needle chart', \
         '2026-01-01T00:00:00.000000Z','2026-01-01T00:00:00.000000Z')",
    )
    .bind(other_id)
    .bind(category_id)
    .execute(&pool)
    .await
    .unwrap();
    sqlx::query("INSERT INTO user_favorites(user_id,project_id) VALUES(?,?)")
        .bind(owner_id)
        .bind(project_id)
        .execute(&pool)
        .await
        .unwrap();

    let literal_percent = app
        .clone()
        .oneshot(
            Request::get("/api/v1/projects?q=100%25&favorite=true")
                .header("authorization", format!("Bearer {session}"))
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(literal_percent.status(), StatusCode::OK);
    let results: serde_json::Value = serde_json::from_str(&body(literal_percent).await).unwrap();
    assert_eq!(results["items"].as_array().unwrap().len(), 1);
    assert_eq!(results["items"][0]["id"], project_id);

    let notes_search = app
        .clone()
        .oneshot(
            Request::get("/api/v1/projects?q=Needle+chart")
                .header("authorization", format!("Bearer {session}"))
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();
    let results: serde_json::Value = serde_json::from_str(&body(notes_search).await).unwrap();
    assert_eq!(results["items"].as_array().unwrap().len(), 1);
    assert_eq!(results["items"][0]["id"], project_id);

    let yarn_id: i64 = sqlx::query_scalar(
        "INSERT INTO yarns(owner_id,name,brand,colorway,fiber_content,notes,created_at,updated_at) \
         VALUES(?,'Cozy yarn','Local mill','Blue','Merino wool','Use for winter', \
         '2026-01-01T00:00:00.000000Z','2026-01-01T00:00:00.000000Z') RETURNING id",
    )
    .bind(owner_id)
    .fetch_one(&pool)
    .await
    .unwrap();
    sqlx::query("INSERT INTO user_favorite_yarns(user_id,yarn_id) VALUES(?,?)")
        .bind(owner_id)
        .bind(yarn_id)
        .execute(&pool)
        .await
        .unwrap();
    let yarn_search = app
        .clone()
        .oneshot(
            Request::get("/api/v1/yarns?q=merino&favorite=true")
                .header("authorization", format!("Bearer {session}"))
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();
    let results: serde_json::Value = serde_json::from_str(&body(yarn_search).await).unwrap();
    assert_eq!(results["items"].as_array().unwrap().len(), 1);
    assert_eq!(results["items"][0]["id"], yarn_id);

    let too_long = app
        .oneshot(
            Request::get(format!("/api/v1/yarns?q={}", "x".repeat(257)))
                .header("authorization", format!("Bearer {session}"))
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(too_long.status(), StatusCode::UNPROCESSABLE_ENTITY);
}

#[tokio::test]
async fn browser_catalog_filters_keep_encoded_values_across_pages() {
    let (app, pool) = real_app().await;
    let session = authenticated(&app).await;
    let owner_id: i64 = sqlx::query_scalar("SELECT id FROM users WHERE email = ?")
        .bind(EMAIL)
        .fetch_one(&pool)
        .await
        .unwrap();
    let category_id: i64 = sqlx::query_scalar(
        "INSERT INTO categories(user_id, name) VALUES (?, 'Cardigan') RETURNING id",
    )
    .bind(owner_id)
    .fetch_one(&pool)
    .await
    .unwrap();
    for _ in 0..51 {
        let project_id: i64 = sqlx::query_scalar(
            "INSERT INTO projects(owner_id,category_id,name,tags,notes,created_at,updated_at) \
             VALUES(?,?,'Filtered project','[\"blue\"]','Needle 100% & warm', \
             '2026-01-01T00:00:00.000000Z','2026-01-01T00:00:00.000000Z') RETURNING id",
        )
        .bind(owner_id)
        .bind(category_id)
        .fetch_one(&pool)
        .await
        .unwrap();
        sqlx::query("INSERT INTO user_favorites(user_id,project_id) VALUES(?,?)")
            .bind(owner_id)
            .bind(project_id)
            .execute(&pool)
            .await
            .unwrap();
    }

    let page = get_html(
        &app,
        "/projects?q=Needle+100%25+%26+warm&category=Cardigan&tag=blue&favorite=favorites",
        &session,
    )
    .await;
    let parsed_page = scraper::Html::parse_document(&page);
    let query_selector =
        scraper::Selector::parse("input[name='q']").expect("query selector is valid");
    let query_input = parsed_page
        .select(&query_selector)
        .next()
        .expect("query input is present");
    assert_eq!(
        query_input.value().attr("value"),
        Some("Needle 100% & warm")
    );
    let next_link_selector =
        scraper::Selector::parse("a[href*='page=2']").expect("next-link selector is valid");
    let next_link = parsed_page
        .select(&next_link_selector)
        .find_map(|link| link.value().attr("href"))
        .expect("second-page link is present");
    let next_url = url::Url::parse(&format!("http://localhost{next_link}"))
        .expect("pagination link should be a valid relative URL");
    let query: std::collections::HashMap<_, _> = next_url.query_pairs().into_owned().collect();
    assert_eq!(query.get("page").map(String::as_str), Some("2"));
    assert_eq!(
        query.get("q").map(String::as_str),
        Some("Needle 100% & warm")
    );
    assert_eq!(query.get("category").map(String::as_str), Some("Cardigan"));
    assert_eq!(query.get("tag").map(String::as_str), Some("blue"));
    assert_eq!(query.get("favorite").map(String::as_str), Some("favorites"));

    let second_page = app
        .oneshot(
            Request::get(
                "/api/v1/projects?page=2&q=Needle+100%25+%26+warm&category=Cardigan&tag=blue&favorite=true",
            )
            .header("authorization", format!("Bearer {session}"))
            .body(Body::empty())
            .unwrap(),
        )
        .await
        .unwrap();
    let results: serde_json::Value = serde_json::from_str(&body(second_page).await).unwrap();
    assert_eq!(results["items"].as_array().unwrap().len(), 1);
    assert_eq!(results["has_more"], false);
}

#[tokio::test]
async fn browser_media_upload_and_delete_forward_with_session_pat_and_csrf() {
    let (app, _, _root) = real_app_with_media().await;
    let session = authenticated(&app).await;
    let create = app
        .clone()
        .oneshot(
            Request::post("/api/v1/projects")
                .header("authorization", format!("Bearer {session}"))
                .header("content-type", "application/json")
                .body(Body::from(
                    serde_json::json!({"name":"Media project"}).to_string(),
                ))
                .expect("API request is valid"),
        )
        .await
        .expect("API responds");
    let project: serde_json::Value =
        serde_json::from_str(&body(create).await).expect("project JSON");
    let id = project["id"].as_i64().expect("project id");
    let detail = get_html(&app, &format!("/projects/{id}"), &session).await;
    let csrf = hidden(&detail, "csrf");
    let mut bad_request = browser_upload_request(
        &format!("/projects/{id}/media/title"),
        &session,
        "bad",
        &test_png(),
    );
    bad_request
        .headers_mut()
        .insert("accept-language", "en".parse().unwrap());
    let bad = app
        .clone()
        .oneshot(bad_request)
        .await
        .expect("router responds");
    assert_eq!(bad.status(), StatusCode::OK);
    let bad_html = body(bad).await;
    assert!(bad_html.contains("expired"));
    let uploaded = app
        .clone()
        .oneshot(browser_upload_request(
            &format!("/projects/{id}/media/title"),
            &session,
            &csrf,
            &test_png(),
        ))
        .await
        .expect("router responds");
    assert_eq!(uploaded.status(), StatusCode::SEE_OTHER);
    let api = app
        .clone()
        .oneshot(
            Request::get(format!("/api/v1/projects/{id}"))
                .header("authorization", format!("Bearer {session}"))
                .body(Body::empty())
                .expect("request valid"),
        )
        .await
        .expect("API responds");
    let project: serde_json::Value = serde_json::from_str(&body(api).await).expect("project JSON");
    let image_id = project["images"][0]["id"]
        .as_i64()
        .expect("public image id");
    let delete = app
        .clone()
        .oneshot(form_request(
            &format!("/projects/{id}/media/images/{image_id}/delete"),
            &format!("__Host-stricknani_session={session}"),
            &[("csrf", &csrf)],
        ))
        .await
        .expect("router responds");
    assert_eq!(delete.status(), StatusCode::SEE_OTHER);
    let api = app
        .clone()
        .oneshot(
            Request::get(format!("/api/v1/projects/{id}"))
                .header("authorization", format!("Bearer {session}"))
                .body(Body::empty())
                .expect("request valid"),
        )
        .await
        .expect("API responds");
    let project: serde_json::Value = serde_json::from_str(&body(api).await).expect("project JSON");
    assert!(project["images"].as_array().unwrap().is_empty());

    let pdf = b"%PDF-1.4\nexample\n%%EOF\n";
    let attachment = app
        .clone()
        .oneshot(browser_upload_request_as(
            &format!("/projects/{id}/media/attachments"),
            &session,
            &csrf,
            pdf,
            "application/pdf",
            None,
        ))
        .await
        .expect("router responds");
    assert_eq!(attachment.status(), StatusCode::SEE_OTHER);
    let api = app
        .clone()
        .oneshot(
            Request::get(format!("/api/v1/projects/{id}"))
                .header("authorization", format!("Bearer {session}"))
                .body(Body::empty())
                .expect("request valid"),
        )
        .await
        .expect("API responds");
    let project: serde_json::Value = serde_json::from_str(&body(api).await).expect("project JSON");
    let attachment_id = project["attachments"][0]["id"]
        .as_i64()
        .expect("public attachment id");
    let deleted = app
        .clone()
        .oneshot(form_request(
            &format!("/projects/{id}/media/attachments/{attachment_id}/delete"),
            &format!("__Host-stricknani_session={session}"),
            &[("csrf", &csrf)],
        ))
        .await
        .expect("router responds");
    assert_eq!(deleted.status(), StatusCode::SEE_OTHER);

    let create_yarn = app
        .clone()
        .oneshot(
            Request::post("/api/v1/yarns")
                .header("authorization", format!("Bearer {session}"))
                .header("content-type", "application/json")
                .body(Body::from(
                    serde_json::json!({"name":"Photo yarn"}).to_string(),
                ))
                .expect("request valid"),
        )
        .await
        .expect("API responds");
    let yarn: serde_json::Value =
        serde_json::from_str(&body(create_yarn).await).expect("yarn JSON");
    let yarn_id = yarn["id"].as_i64().expect("yarn id");
    let photo = app
        .clone()
        .oneshot(browser_upload_request_as(
            &format!("/yarns/{yarn_id}/media/photos"),
            &session,
            &csrf,
            &test_png(),
            "image/png",
            None,
        ))
        .await
        .expect("router responds");
    assert_eq!(photo.status(), StatusCode::SEE_OTHER);
    let api = app
        .clone()
        .oneshot(
            Request::get(format!("/api/v1/yarns/{yarn_id}"))
                .header("authorization", format!("Bearer {session}"))
                .body(Body::empty())
                .expect("request valid"),
        )
        .await
        .expect("API responds");
    let yarn: serde_json::Value = serde_json::from_str(&body(api).await).expect("yarn JSON");
    let photo_id = yarn["photos"][0]["id"].as_i64().expect("public photo id");
    let deleted = app
        .oneshot(form_request(
            &format!("/yarns/{yarn_id}/media/photos/{photo_id}/delete"),
            &format!("__Host-stricknani_session={session}"),
            &[("csrf", &csrf)],
        ))
        .await
        .expect("router responds");
    assert_eq!(deleted.status(), StatusCode::SEE_OTHER);
}
