use std::io::Cursor;

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

const OWNER_PAT: &str = "synthetic-owner-purpose-api-pat";
const OWNER_SESSION: &str = "synthetic-owner-browser-session";
const ADMIN_SESSION: &str = "synthetic-admin-browser-session";
const ORIGIN: &str = "https://app.example.test";

struct Fixture {
    app: Router,
    pool: SqlitePool,
    owner_id: i64,
    foreign_project_id: i64,
    _media_root: tempfile::TempDir,
}

async fn fixture() -> Fixture {
    let pool = db::connect("sqlite::memory:")
        .await
        .expect("database migrates");
    let owner_id = db::create_user(
        &pool,
        "pat-owner@example.test",
        "owner-password-long".into(),
    )
    .await
    .expect("resource owner exists");
    let admin_id = db::create_user(
        &pool,
        "pat-admin@example.test",
        "admin-password-long".into(),
    )
    .await
    .expect("admin exists");
    sqlx::query("UPDATE users SET is_admin = 1 WHERE id = ?")
        .bind(admin_id)
        .execute(&pool)
        .await
        .unwrap();
    add_token(&pool, owner_id, OWNER_PAT, "api").await;
    add_token(&pool, owner_id, OWNER_SESSION, "browser").await;
    add_token(&pool, admin_id, ADMIN_SESSION, "browser").await;
    let foreign_project_id = insert_project(&pool, admin_id, "Owned by another user").await;
    let media_root = tempfile::tempdir().expect("media root should be created");
    let state = db::AppState::new(pool.clone())
        .with_secure_cookies(true)
        .with_media_root(media_root.path().to_path_buf());
    Fixture {
        app: api_v2::router(state, false),
        pool,
        owner_id,
        foreign_project_id,
        _media_root: media_root,
    }
}

async fn add_token(pool: &SqlitePool, user_id: i64, token: &str, purpose: &str) {
    sqlx::query(
        "INSERT INTO api_tokens (user_id, name, token_hash, created_at, expires_at, purpose) \
         VALUES (?, 'synthetic resource-auth fixture', ?, unixepoch(), unixepoch() + 86400, ?)",
    )
    .bind(user_id)
    .bind(hex::encode(Sha256::digest(token.as_bytes())))
    .bind(purpose)
    .execute(pool)
    .await
    .expect("synthetic auth token should be stored");
}

async fn insert_project(pool: &SqlitePool, owner_id: i64, name: &str) -> i64 {
    sqlx::query_scalar(
        "INSERT INTO projects (owner_id, name, created_at, updated_at) \
         VALUES (?, ?, '2026-01-01T00:00:00Z', '2026-01-01T00:00:00Z') RETURNING id",
    )
    .bind(owner_id)
    .bind(name)
    .fetch_one(pool)
    .await
    .unwrap()
}

fn request(
    method: &str,
    path: &str,
    bearer: Option<&str>,
    cookie: Option<&str>,
    origin: bool,
    body: Option<String>,
) -> Request<Body> {
    let mut builder = Request::builder().method(method).uri(path);
    if let Some(token) = bearer {
        builder = builder.header("authorization", format!("Bearer {token}"));
    }
    if let Some(token) = cookie {
        builder = builder.header("cookie", format!("__Host-stricknani_session={token}"));
    }
    if origin {
        builder = builder
            .header("host", "app.example.test")
            .header("origin", ORIGIN)
            .header("sec-fetch-site", "same-origin");
    }
    if body.is_some() {
        builder = builder.header("content-type", "application/json");
    }
    builder
        .body(body.map_or_else(Body::empty, Body::from))
        .unwrap()
}

async fn json(response: axum::response::Response) -> serde_json::Value {
    serde_json::from_slice(&response.into_body().collect().await.unwrap().to_bytes()).unwrap()
}

fn png_bytes() -> Vec<u8> {
    let image = image::DynamicImage::new_rgba8(2, 2);
    let mut output = Cursor::new(Vec::new());
    image
        .write_to(&mut output, image::ImageFormat::Png)
        .expect("test image should encode");
    output.into_inner()
}

fn title_upload(path: &str, token: &str) -> Request<Body> {
    let boundary = "native-pat-media-boundary";
    let bytes = png_bytes();
    let mut body = format!(
        "--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"pat-title.png\"\r\nContent-Type: image/png\r\n\r\n"
    )
    .into_bytes();
    body.extend_from_slice(&bytes);
    body.extend_from_slice(format!("\r\n--{boundary}--\r\n").as_bytes());
    Request::post(path)
        .header("authorization", format!("Bearer {token}"))
        .header(
            "content-type",
            format!("multipart/form-data; boundary={boundary}"),
        )
        .body(Body::from(body))
        .unwrap()
}

#[tokio::test]
async fn api_pat_owns_resource_crud_favorites_category_and_media_without_browser_csrf() {
    let fixture = fixture().await;

    let create = fixture
        .app
        .clone()
        .oneshot(request(
            "POST",
            "/projects",
            Some(OWNER_PAT),
            None,
            false,
            Some(serde_json::json!({"name":"PAT project","tags":["test"]}).to_string()),
        ))
        .await
        .unwrap();
    assert_eq!(create.status(), StatusCode::CREATED);
    let project = json(create).await["data"].clone();
    let project_id = project["id"].as_i64().unwrap();
    let updated_at = project["updated_at"].as_str().unwrap().to_owned();
    assert_eq!(
        sqlx::query_scalar::<_, i64>("SELECT owner_id FROM projects WHERE id = ?")
            .bind(project_id)
            .fetch_one(&fixture.pool)
            .await
            .unwrap(),
        fixture.owner_id
    );

    let list = fixture
        .app
        .clone()
        .oneshot(request(
            "GET",
            "/projects?page=1",
            Some(OWNER_PAT),
            None,
            false,
            None,
        ))
        .await
        .unwrap();
    assert_eq!(list.status(), StatusCode::OK);
    assert_eq!(json(list).await["data"][0]["id"], project_id);

    let detail = fixture
        .app
        .clone()
        .oneshot(request(
            "GET",
            &format!("/projects/{project_id}"),
            Some(OWNER_PAT),
            None,
            false,
            None,
        ))
        .await
        .unwrap();
    assert_eq!(detail.status(), StatusCode::OK);
    assert_eq!(json(detail).await["data"]["id"], project_id);

    let update = fixture
        .app
        .clone()
        .oneshot(request(
            "PUT",
            &format!("/projects/{project_id}"),
            Some(OWNER_PAT),
            None,
            false,
            Some(
                serde_json::json!({"name":"PAT project updated","expected_updated_at":updated_at})
                    .to_string(),
            ),
        ))
        .await
        .unwrap();
    assert_eq!(update.status(), StatusCode::OK);
    assert_eq!(json(update).await["data"]["name"], "PAT project updated");

    for (method, expected_favorite) in [("PUT", true), ("DELETE", false)] {
        let favorite = fixture
            .app
            .clone()
            .oneshot(request(
                method,
                &format!("/projects/{project_id}/favorite"),
                Some(OWNER_PAT),
                None,
                false,
                None,
            ))
            .await
            .unwrap();
        assert_eq!(favorite.status(), StatusCode::OK, "{method}");
        assert_eq!(
            json(favorite).await["data"]["is_favorite"],
            expected_favorite
        );
    }

    let category = fixture
        .app
        .clone()
        .oneshot(request(
            "POST",
            "/categories",
            Some(OWNER_PAT),
            None,
            false,
            Some(serde_json::json!({"name":"PAT category"}).to_string()),
        ))
        .await
        .unwrap();
    assert_eq!(category.status(), StatusCode::CREATED);
    let category_id = json(category).await["data"]["id"].as_i64().unwrap();
    let update_category = fixture
        .app
        .clone()
        .oneshot(request(
            "PUT",
            &format!("/categories/{category_id}"),
            Some(OWNER_PAT),
            None,
            false,
            Some(serde_json::json!({"name":"Renamed PAT category"}).to_string()),
        ))
        .await
        .unwrap();
    assert_eq!(update_category.status(), StatusCode::OK);
    assert_eq!(
        json(update_category).await["data"]["name"],
        "Renamed PAT category"
    );
    let delete_category = fixture
        .app
        .clone()
        .oneshot(request(
            "DELETE",
            &format!("/categories/{category_id}"),
            Some(OWNER_PAT),
            None,
            false,
            None,
        ))
        .await
        .unwrap();
    assert_eq!(delete_category.status(), StatusCode::NO_CONTENT);

    let media = fixture
        .app
        .clone()
        .oneshot(title_upload(
            &format!("/projects/{project_id}/images/title"),
            OWNER_PAT,
        ))
        .await
        .unwrap();
    assert_eq!(media.status(), StatusCode::CREATED);
    let image_id = json(media).await["data"]["id"].as_i64().unwrap();
    let delete_media = fixture
        .app
        .clone()
        .oneshot(request(
            "DELETE",
            &format!("/projects/{project_id}/images/{image_id}"),
            Some(OWNER_PAT),
            None,
            false,
            None,
        ))
        .await
        .unwrap();
    assert_eq!(delete_media.status(), StatusCode::NO_CONTENT);

    let foreign = fixture
        .app
        .clone()
        .oneshot(request(
            "GET",
            &format!("/projects/{}", fixture.foreign_project_id),
            Some(OWNER_PAT),
            None,
            false,
            None,
        ))
        .await
        .unwrap();
    assert_eq!(foreign.status(), StatusCode::NOT_FOUND);

    let forbidden_admin = fixture
        .app
        .clone()
        .oneshot(request(
            "GET",
            "/admin/users",
            Some(OWNER_PAT),
            None,
            false,
            None,
        ))
        .await
        .unwrap();
    assert_eq!(forbidden_admin.status(), StatusCode::FORBIDDEN);
    assert_eq!(
        json(forbidden_admin).await["error"]["code"],
        "admin_required"
    );

    let invalid_bearer_with_admin_cookie = fixture
        .app
        .clone()
        .oneshot(request(
            "GET",
            &format!("/projects/{}", fixture.foreign_project_id),
            Some("synthetic-invalid-pat"),
            Some(ADMIN_SESSION),
            false,
            None,
        ))
        .await
        .unwrap();
    assert_eq!(
        invalid_bearer_with_admin_cookie.status(),
        StatusCode::UNAUTHORIZED
    );
    assert_eq!(
        json(invalid_bearer_with_admin_cookie).await["error"]["code"],
        "session_expired"
    );

    let browser_write_without_origin = fixture
        .app
        .clone()
        .oneshot(request(
            "POST",
            "/categories",
            None,
            Some(OWNER_SESSION),
            true,
            Some(serde_json::json!({"name":"Browser blocked"}).to_string()),
        ))
        .await
        .unwrap();
    assert_eq!(browser_write_without_origin.status(), StatusCode::FORBIDDEN);
    assert_eq!(
        json(browser_write_without_origin).await["error"]["code"],
        "csrf_failed"
    );
}
