use axum::{
    body::Body,
    http::{Request, StatusCode},
};
use http_body_util::BodyExt;
use sqlx::SqlitePool;
use std::io::Cursor;
use stricknani_server::{api_v2, db};
use tower::ServiceExt;

const HOST: &str = "app.example.test";
const ORIGIN: &str = "https://app.example.test";

struct Fixture {
    _root: tempfile::TempDir,
    app: axum::Router,
    pool: SqlitePool,
    owner_id: i64,
    project_id: i64,
    step_id: i64,
    yarn_id: i64,
    foreign_project_id: i64,
    foreign_yarn_id: i64,
}

async fn fixture() -> Fixture {
    let pool = db::connect("sqlite::memory:")
        .await
        .expect("in-memory database should migrate");
    let owner_id = db::create_user(
        &pool,
        "media-owner@example.test",
        "media-owner-password".to_owned(),
    )
    .await
    .unwrap();
    let foreign_id = db::create_user(
        &pool,
        "media-foreign@example.test",
        "media-foreign-password".to_owned(),
    )
    .await
    .unwrap();
    let project_id = insert_project(&pool, owner_id, "owner project").await;
    let foreign_project_id = insert_project(&pool, foreign_id, "foreign project").await;
    let step_id = sqlx::query_scalar::<_, i64>(
        "INSERT INTO project_steps (project_id,title,step_number) VALUES (?, 'step', 1) RETURNING id",
    )
    .bind(project_id)
    .fetch_one(&pool)
    .await
    .unwrap();
    let yarn_id = insert_yarn(&pool, owner_id, "Owner wool").await;
    let foreign_yarn_id = insert_yarn(&pool, foreign_id, "Foreign wool").await;
    let root = tempfile::tempdir().expect("media root should be created");
    let state = db::AppState::new(pool.clone())
        .with_secure_cookies(true)
        .with_media_root(root.path().to_path_buf());
    Fixture {
        _root: root,
        app: api_v2::router(state, false),
        pool,
        owner_id,
        project_id,
        step_id,
        yarn_id,
        foreign_project_id,
        foreign_yarn_id,
    }
}

async fn insert_project(pool: &SqlitePool, owner_id: i64, name: &str) -> i64 {
    sqlx::query_scalar(
        "INSERT INTO projects (owner_id,name,created_at,updated_at) VALUES (?,?, '2026-01-01T00:00:00Z','2026-01-01T00:00:00Z') RETURNING id",
    )
    .bind(owner_id)
    .bind(name)
    .fetch_one(pool)
    .await
    .unwrap()
}

async fn insert_yarn(pool: &SqlitePool, owner_id: i64, name: &str) -> i64 {
    sqlx::query_scalar(
        "INSERT INTO yarns (owner_id,name,created_at,updated_at) VALUES (?,?, '2026-01-01T00:00:00Z','2026-01-01T00:00:00Z') RETURNING id",
    )
    .bind(owner_id)
    .bind(name)
    .fetch_one(pool)
    .await
    .unwrap()
}

async fn json(response: axum::response::Response) -> serde_json::Value {
    let bytes = response
        .into_body()
        .collect()
        .await
        .expect("response body should be readable")
        .to_bytes();
    serde_json::from_slice(&bytes).expect("response should be JSON")
}

fn cookie(response: &axum::response::Response, prefix: &str) -> String {
    response
        .headers()
        .get_all("set-cookie")
        .iter()
        .filter_map(|value| value.to_str().ok())
        .find_map(|value| value.strip_prefix(prefix))
        .and_then(|value| value.split(';').next())
        .expect("expected cookie should be set")
        .to_owned()
}

async fn signed_in(app: &axum::Router, email: &str, password: &str) -> (String, String) {
    let bootstrap = app
        .clone()
        .oneshot(Request::get("/session").body(Body::empty()).unwrap())
        .await
        .unwrap();
    let preauth = cookie(&bootstrap, "__Host-stricknani_v2_login_csrf=");
    let pre_csrf = json(bootstrap).await["data"]["csrf_token"]
        .as_str()
        .unwrap()
        .to_owned();
    let login = app
        .clone()
        .oneshot(
            Request::post("/auth/login")
                .header("host", HOST)
                .header("origin", ORIGIN)
                .header("sec-fetch-site", "same-origin")
                .header(
                    "cookie",
                    format!("__Host-stricknani_v2_login_csrf={preauth}"),
                )
                .header("x-csrf-token", pre_csrf)
                .header("content-type", "application/json")
                .body(Body::from(
                    serde_json::json!({"email":email,"password":password}).to_string(),
                ))
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(login.status(), StatusCode::OK);
    let session = cookie(&login, "__Host-stricknani_session=");
    let csrf = json(login).await["data"]["csrf_token"]
        .as_str()
        .unwrap()
        .to_owned();
    (session, csrf)
}

struct MultipartSpec<'a> {
    method: &'a str,
    path: &'a str,
    session: &'a str,
    csrf: Option<&'a str>,
    origin: Option<&'a str>,
    file_name: &'a str,
    content_type: &'a str,
    bytes: &'a [u8],
    alt_text: Option<&'a str>,
}

fn multipart_request(spec: MultipartSpec<'_>) -> Request<Body> {
    let boundary = "codex-v2-media-boundary";
    let mut body = Vec::new();
    if let Some(alt_text) = spec.alt_text {
        body.extend_from_slice(
            format!(
                "--{boundary}\r\nContent-Disposition: form-data; name=\"alt_text\"\r\n\r\n{alt_text}\r\n"
            )
            .as_bytes(),
        );
    }
    body.extend_from_slice(
        format!(
            "--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"{}\"\r\nContent-Type: {}\r\n\r\n",
            spec.file_name,
            spec.content_type
        )
        .as_bytes(),
    );
    body.extend_from_slice(spec.bytes);
    body.extend_from_slice(format!("\r\n--{boundary}--\r\n").as_bytes());
    let mut request = Request::builder()
        .method(spec.method)
        .uri(spec.path)
        .header("host", HOST)
        .header(
            "cookie",
            format!("__Host-stricknani_session={}", spec.session),
        )
        .header(
            "content-type",
            format!("multipart/form-data; boundary={boundary}"),
        );
    if let Some(csrf) = spec.csrf {
        request = request.header("x-csrf-token", csrf);
    }
    if let Some(origin) = spec.origin {
        request = request.header("origin", origin);
    }
    request.body(Body::from(body)).unwrap()
}

fn png_bytes() -> Vec<u8> {
    let image = image::DynamicImage::new_rgba8(2, 2);
    let mut output = Cursor::new(Vec::new());
    image
        .write_to(&mut output, image::ImageFormat::Png)
        .expect("test PNG should encode");
    output.into_inner()
}

#[tokio::test]
async fn project_title_and_step_uploads_share_storage_and_delete_semantics() {
    let fixture = fixture().await;
    let (session, csrf) = signed_in(
        &fixture.app,
        "media-owner@example.test",
        "media-owner-password",
    )
    .await;
    let png = png_bytes();

    let title = fixture
        .app
        .clone()
        .oneshot(multipart_request(MultipartSpec {
            method: "POST",
            path: &format!("/projects/{}/images/title", fixture.project_id),
            session: &session,
            csrf: Some(&csrf),
            origin: Some(ORIGIN),
            file_name: "title.png",
            content_type: "image/png",
            bytes: &png,
            alt_text: Some("Title photo"),
        }))
        .await
        .unwrap();
    assert_eq!(title.status(), StatusCode::CREATED);
    let title = json(title).await["data"].clone();
    let title_id = title["id"].as_i64().unwrap();
    assert_eq!(title["is_title_image"], true);
    assert_eq!(title["alt_text"], "Title photo");
    assert!(
        title["url"]
            .as_str()
            .unwrap()
            .starts_with("/media/projects/")
    );

    let step = fixture
        .app
        .clone()
        .oneshot(multipart_request(MultipartSpec {
            method: "POST",
            path: &format!(
                "/projects/{}/steps/{}/images",
                fixture.project_id, fixture.step_id
            ),
            session: &session,
            csrf: Some(&csrf),
            origin: Some(ORIGIN),
            file_name: "step.png",
            content_type: "image/png",
            bytes: &png,
            alt_text: Some("Step photo"),
        }))
        .await
        .unwrap();
    assert_eq!(step.status(), StatusCode::CREATED);
    let step = json(step).await["data"].clone();
    assert_eq!(step["step_id"], fixture.step_id);

    let uploaded: i64 = sqlx::query_scalar(
        "SELECT COUNT(*) FROM audit_events WHERE actor_user_id = ? AND entity_type = 'project' AND entity_id = ? AND action = 'uploaded'",
    )
    .bind(fixture.owner_id)
    .bind(fixture.project_id)
    .fetch_one(&fixture.pool)
    .await
    .unwrap();
    assert_eq!(uploaded, 2);

    let deleted = fixture
        .app
        .clone()
        .oneshot(
            Request::delete(format!(
                "/projects/{}/images/{title_id}",
                fixture.project_id
            ))
            .header("host", HOST)
            .header("origin", ORIGIN)
            .header("sec-fetch-site", "same-origin")
            .header("cookie", format!("__Host-stricknani_session={session}"))
            .header("x-csrf-token", &csrf)
            .body(Body::empty())
            .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(deleted.status(), StatusCode::NO_CONTENT);
    let removed: i64 = sqlx::query_scalar(
        "SELECT COUNT(*) FROM audit_events WHERE actor_user_id = ? AND entity_type = 'project' AND entity_id = ? AND action = 'removed'",
    )
    .bind(fixture.owner_id)
    .bind(fixture.project_id)
    .fetch_one(&fixture.pool)
    .await
    .unwrap();
    assert_eq!(removed, 1);
}

#[tokio::test]
async fn attachment_upload_preserves_classification_and_owner_scoped_delete() {
    let fixture = fixture().await;
    let (session, csrf) = signed_in(
        &fixture.app,
        "media-owner@example.test",
        "media-owner-password",
    )
    .await;
    let (foreign_session, foreign_csrf) = signed_in(
        &fixture.app,
        "media-foreign@example.test",
        "media-foreign-password",
    )
    .await;
    let bytes = b"%PDF-1.4\n%%EOF";
    let uploaded = fixture
        .app
        .clone()
        .oneshot(multipart_request(MultipartSpec {
            method: "POST",
            path: &format!("/projects/{}/attachments", fixture.project_id),
            session: &session,
            csrf: Some(&csrf),
            origin: Some(ORIGIN),
            file_name: "pattern.pdf",
            content_type: "application/pdf",
            bytes,
            alt_text: None,
        }))
        .await
        .unwrap();
    assert_eq!(uploaded.status(), StatusCode::CREATED);
    let attachment = json(uploaded).await["data"].clone();
    let attachment_id = attachment["id"].as_i64().unwrap();
    assert_eq!(attachment["original_filename"], "pattern.pdf");
    assert_eq!(attachment["content_type"], "application/pdf");

    let foreign_delete = fixture
        .app
        .clone()
        .oneshot(
            Request::delete(format!(
                "/projects/{}/attachments/{attachment_id}",
                fixture.project_id
            ))
            .header("host", HOST)
            .header("origin", ORIGIN)
            .header("sec-fetch-site", "same-origin")
            .header(
                "cookie",
                format!("__Host-stricknani_session={foreign_session}"),
            )
            .header("x-csrf-token", foreign_csrf)
            .body(Body::empty())
            .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(foreign_delete.status(), StatusCode::NOT_FOUND);

    let deleted = fixture
        .app
        .oneshot(
            Request::delete(format!(
                "/projects/{}/attachments/{attachment_id}",
                fixture.project_id
            ))
            .header("host", HOST)
            .header("origin", ORIGIN)
            .header("sec-fetch-site", "same-origin")
            .header("cookie", format!("__Host-stricknani_session={session}"))
            .header("x-csrf-token", csrf)
            .body(Body::empty())
            .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(deleted.status(), StatusCode::NO_CONTENT);
}

#[tokio::test]
async fn attachment_upload_accepts_valid_files_larger_than_two_mib() {
    let fixture = fixture().await;
    let (session, csrf) = signed_in(
        &fixture.app,
        "media-owner@example.test",
        "media-owner-password",
    )
    .await;
    let mut bytes = vec![b'x'; 3 * 1024 * 1024];
    bytes[..8].copy_from_slice(b"%PDF-1.4");
    let trailer_start = bytes.len() - 5;
    bytes[trailer_start..].copy_from_slice(b"%%EOF");

    let uploaded = fixture
        .app
        .clone()
        .oneshot(multipart_request(MultipartSpec {
            method: "POST",
            path: &format!("/projects/{}/attachments", fixture.project_id),
            session: &session,
            csrf: Some(&csrf),
            origin: Some(ORIGIN),
            file_name: "large-pattern.pdf",
            content_type: "application/pdf",
            bytes: &bytes,
            alt_text: None,
        }))
        .await
        .unwrap();

    assert_eq!(uploaded.status(), StatusCode::CREATED);
    let attachment = json(uploaded).await["data"].clone();
    assert_eq!(attachment["content_type"], "application/pdf");
    assert_eq!(attachment["original_filename"], "large-pattern.pdf");
}

#[tokio::test]
async fn yarn_photo_upload_delete_promotes_primary_photo() {
    let fixture = fixture().await;
    let (session, csrf) = signed_in(
        &fixture.app,
        "media-owner@example.test",
        "media-owner-password",
    )
    .await;
    let png = png_bytes();
    let mut ids = Vec::new();
    for file_name in ["first.png", "second.png"] {
        let response = fixture
            .app
            .clone()
            .oneshot(multipart_request(MultipartSpec {
                method: "POST",
                path: &format!("/yarns/{}/photos", fixture.yarn_id),
                session: &session,
                csrf: Some(&csrf),
                origin: Some(ORIGIN),
                file_name,
                content_type: "image/png",
                bytes: &png,
                alt_text: None,
            }))
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::CREATED);
        let photo = json(response).await["data"].clone();
        ids.push(photo["id"].as_i64().unwrap());
        assert_eq!(photo["is_primary"], ids.len() == 1);
    }

    let delete_primary = fixture
        .app
        .clone()
        .oneshot(
            Request::delete(format!("/yarns/{}/photos/{}", fixture.yarn_id, ids[0]))
                .header("host", HOST)
                .header("origin", ORIGIN)
                .header("sec-fetch-site", "same-origin")
                .header("cookie", format!("__Host-stricknani_session={session}"))
                .header("x-csrf-token", &csrf)
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(delete_primary.status(), StatusCode::NO_CONTENT);
    let remaining_primary: i64 = sqlx::query_scalar(
        "SELECT is_primary FROM media_files WHERE yarn_id = ? AND public_id = ?",
    )
    .bind(fixture.yarn_id)
    .bind(ids[1])
    .fetch_one(&fixture.pool)
    .await
    .unwrap();
    assert_eq!(remaining_primary, 1);
}

#[tokio::test]
async fn media_rejections_have_v2_envelopes_and_never_mutate_foreign_or_csrf_denied_targets() {
    let fixture = fixture().await;
    let (session, csrf) = signed_in(
        &fixture.app,
        "media-owner@example.test",
        "media-owner-password",
    )
    .await;
    let (foreign_session, foreign_csrf) = signed_in(
        &fixture.app,
        "media-foreign@example.test",
        "media-foreign-password",
    )
    .await;
    let png = png_bytes();

    let anonymous_malformed = fixture
        .app
        .clone()
        .oneshot(
            Request::post(format!("/projects/{}/images/title", fixture.project_id))
                .header("host", HOST)
                .header("origin", ORIGIN)
                .header("sec-fetch-site", "same-origin")
                .header("content-type", "application/octet-stream")
                .body(Body::from("ignored"))
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(anonymous_malformed.status(), StatusCode::UNAUTHORIZED);

    for (token, origin) in [
        (None, Some(ORIGIN)),
        (Some("invalid-csrf"), Some(ORIGIN)),
        (Some(csrf.as_str()), Some("https://evil.example.test")),
    ] {
        let denied = fixture
            .app
            .clone()
            .oneshot(multipart_request(MultipartSpec {
                method: "POST",
                path: &format!("/projects/{}/images/title", fixture.project_id),
                session: &session,
                csrf: token,
                origin,
                file_name: "denied.png",
                content_type: "image/png",
                bytes: &png,
                alt_text: None,
            }))
            .await
            .unwrap();
        assert_eq!(denied.status(), StatusCode::FORBIDDEN);
        assert_eq!(json(denied).await["error"]["code"], "csrf_failed");
    }

    let malformed = fixture
        .app
        .clone()
        .oneshot(
            Request::post(format!("/projects/{}/images/title", fixture.project_id))
                .header("host", HOST)
                .header("origin", ORIGIN)
                .header("sec-fetch-site", "same-origin")
                .header("cookie", format!("__Host-stricknani_session={session}"))
                .header("x-csrf-token", &csrf)
                .header("content-type", "multipart/form-data")
                .body(Body::from("malformed multipart"))
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(malformed.status(), StatusCode::BAD_REQUEST);
    assert_eq!(json(malformed).await["error"]["code"], "invalid_multipart");

    let oversized_bytes = vec![0; 25 * 1024 * 1024 + 1];
    let oversized = multipart_request(MultipartSpec {
        method: "POST",
        path: &format!("/projects/{}/images/title", fixture.project_id),
        session: &session,
        csrf: Some(&csrf),
        origin: Some(ORIGIN),
        file_name: "large.png",
        content_type: "image/png",
        bytes: &oversized_bytes,
        alt_text: None,
    });
    let oversized = fixture.app.clone().oneshot(oversized).await.unwrap();
    assert_eq!(oversized.status(), StatusCode::PAYLOAD_TOO_LARGE);
    assert_eq!(json(oversized).await["error"]["code"], "payload_too_large");

    let foreign_target = fixture
        .app
        .clone()
        .oneshot(multipart_request(MultipartSpec {
            method: "POST",
            path: &format!("/projects/{}/images/title", fixture.foreign_project_id),
            session: &session,
            csrf: Some(&csrf),
            origin: Some(ORIGIN),
            file_name: "foreign.png",
            content_type: "image/png",
            bytes: &png,
            alt_text: None,
        }))
        .await
        .unwrap();
    assert_eq!(foreign_target.status(), StatusCode::NOT_FOUND);

    let foreign_yarn = fixture
        .app
        .clone()
        .oneshot(multipart_request(MultipartSpec {
            method: "POST",
            path: &format!("/yarns/{}/photos", fixture.foreign_yarn_id),
            session: &session,
            csrf: Some(&csrf),
            origin: Some(ORIGIN),
            file_name: "foreign.png",
            content_type: "image/png",
            bytes: &png,
            alt_text: None,
        }))
        .await
        .unwrap();
    assert_eq!(foreign_yarn.status(), StatusCode::NOT_FOUND);
    assert_ne!(session, foreign_session);
    assert_ne!(csrf, foreign_csrf);

    let media_rows: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM media_files")
        .fetch_one(&fixture.pool)
        .await
        .unwrap();
    assert_eq!(media_rows, 0);
}
