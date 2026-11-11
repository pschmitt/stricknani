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
    let bootstrap_data = json(bootstrap).await;
    assert_eq!(
        bootstrap_data["data"]["capabilities"]["media_upload"],
        false
    );
    assert_eq!(
        bootstrap_data["data"]["capabilities"]["media_delete"],
        false
    );
    assert_eq!(
        bootstrap_data["data"]["capabilities"]["account_actions"],
        false
    );
    let pre_csrf = bootstrap_data["data"]["csrf_token"]
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
    let login_data = json(login).await;
    assert_eq!(login_data["data"]["capabilities"]["media_upload"], true);
    assert_eq!(login_data["data"]["capabilities"]["media_delete"], true);
    assert_eq!(login_data["data"]["capabilities"]["import_preview"], false);
    assert_eq!(login_data["data"]["capabilities"]["archive_state"], false);
    assert_eq!(login_data["data"]["capabilities"]["account_actions"], true);
    assert_eq!(login_data["data"]["capabilities"]["admin_actions"], false);
    let csrf = login_data["data"]["csrf_token"]
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

fn media_order_request(path: &str, session: &str, csrf: &str, ids: &[i64]) -> Request<Body> {
    Request::put(path)
        .header("host", HOST)
        .header("origin", ORIGIN)
        .header("sec-fetch-site", "same-origin")
        .header("cookie", format!("__Host-stricknani_session={session}"))
        .header("x-csrf-token", csrf)
        .header("content-type", "application/json")
        .body(Body::from(serde_json::json!({"ids": ids}).to_string()))
        .unwrap()
}

fn delete_request(path: &str, session: &str, csrf: &str) -> Request<Body> {
    Request::delete(path)
        .header("host", HOST)
        .header("origin", ORIGIN)
        .header("sec-fetch-site", "same-origin")
        .header("cookie", format!("__Host-stricknani_session={session}"))
        .header("x-csrf-token", csrf)
        .body(Body::empty())
        .unwrap()
}

struct OrderedMediaFixture {
    owner_id: i64,
    project_id: Option<i64>,
    yarn_id: Option<i64>,
    step_id: Option<i64>,
    kind: &'static str,
    public_id: i64,
    position: i64,
    is_primary: bool,
    legacy_is_title_image: Option<bool>,
}

async fn insert_ordered_media(pool: &SqlitePool, media: OrderedMediaFixture) -> i64 {
    let OrderedMediaFixture {
        owner_id,
        project_id,
        yarn_id,
        step_id,
        kind,
        public_id,
        position,
        is_primary,
        legacy_is_title_image,
    } = media;
    sqlx::query_scalar(
        "INSERT INTO media_files \
         (owner_id,project_id,yarn_id,step_id,kind,public_id,filename,original_filename, \
          content_type,size_bytes,alt_text,is_primary,position,is_project_cover,legacy_is_title_image) \
         VALUES (?,?,?,?,?,?,?,?, 'image/png',0,'',?,?,?,?) RETURNING id",
    )
    .bind(owner_id)
    .bind(project_id)
    .bind(yarn_id)
    .bind(step_id)
    .bind(kind)
    .bind(public_id)
    .bind(format!("ordered-{public_id}.png"))
    .bind(format!("ordered-{public_id}.png"))
    .bind(is_primary)
    .bind(position)
    .bind(is_primary && kind != "photo")
    .bind(legacy_is_title_image)
    .fetch_one(pool)
    .await
    .unwrap()
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
        "SELECT COUNT(*) FROM audit_events WHERE actor_user_id = ? AND entity_type = 'project' AND entity_id = ? AND action = 'trashed'",
    )
    .bind(fixture.owner_id)
    .bind(fixture.project_id)
    .fetch_one(&fixture.pool)
    .await
    .unwrap();
    assert_eq!(removed, 1);
}

#[tokio::test]
async fn media_order_is_exact_owner_scoped_and_step_reorder_preserves_global_cover() {
    let fixture = fixture().await;
    let (session, csrf) = signed_in(
        &fixture.app,
        "media-owner@example.test",
        "media-owner-password",
    )
    .await;
    let step_cover_id = insert_ordered_media(
        &fixture.pool,
        OrderedMediaFixture {
            owner_id: fixture.owner_id,
            project_id: Some(fixture.project_id),
            yarn_id: None,
            step_id: Some(fixture.step_id),
            kind: "step",
            public_id: 7001,
            position: 0,
            is_primary: true,
            legacy_is_title_image: Some(true),
        },
    )
    .await;
    insert_ordered_media(
        &fixture.pool,
        OrderedMediaFixture {
            owner_id: fixture.owner_id,
            project_id: Some(fixture.project_id),
            yarn_id: None,
            step_id: Some(fixture.step_id),
            kind: "step",
            public_id: 7002,
            position: 1,
            is_primary: false,
            legacy_is_title_image: Some(false),
        },
    )
    .await;
    let empty_title_order = fixture
        .app
        .clone()
        .oneshot(media_order_request(
            &format!("/projects/{}/images/order", fixture.project_id),
            &session,
            &csrf,
            &[],
        ))
        .await
        .unwrap();
    assert_eq!(empty_title_order.status(), StatusCode::OK);
    let preserved_step_cover: i64 = sqlx::query_scalar(
        "SELECT id FROM media_files WHERE project_id = ? AND is_project_cover = 1",
    )
    .bind(fixture.project_id)
    .fetch_one(&fixture.pool)
    .await
    .unwrap();
    assert_eq!(preserved_step_cover, step_cover_id);
    let title_first_id = insert_ordered_media(
        &fixture.pool,
        OrderedMediaFixture {
            owner_id: fixture.owner_id,
            project_id: Some(fixture.project_id),
            yarn_id: None,
            step_id: None,
            kind: "title",
            public_id: 7101,
            position: 0,
            is_primary: false,
            legacy_is_title_image: Some(false),
        },
    )
    .await;
    let title_second_id = insert_ordered_media(
        &fixture.pool,
        OrderedMediaFixture {
            owner_id: fixture.owner_id,
            project_id: Some(fixture.project_id),
            yarn_id: None,
            step_id: None,
            kind: "title",
            public_id: 7102,
            position: 1,
            is_primary: false,
            legacy_is_title_image: Some(false),
        },
    )
    .await;
    let photo_first_id = insert_ordered_media(
        &fixture.pool,
        OrderedMediaFixture {
            owner_id: fixture.owner_id,
            project_id: None,
            yarn_id: Some(fixture.yarn_id),
            step_id: None,
            kind: "photo",
            public_id: 8001,
            position: 0,
            is_primary: false,
            legacy_is_title_image: None,
        },
    )
    .await;
    let photo_second_id = insert_ordered_media(
        &fixture.pool,
        OrderedMediaFixture {
            owner_id: fixture.owner_id,
            project_id: None,
            yarn_id: Some(fixture.yarn_id),
            step_id: None,
            kind: "photo",
            public_id: 8002,
            position: 1,
            is_primary: false,
            legacy_is_title_image: None,
        },
    )
    .await;
    assert_ne!(
        step_cover_id, 7001,
        "routes accept public IDs, not internal row IDs"
    );

    let initial_project = fixture
        .app
        .clone()
        .oneshot(
            Request::get(format!("/projects/{}", fixture.project_id))
                .header("host", HOST)
                .header("cookie", format!("__Host-stricknani_session={session}"))
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(initial_project.status(), StatusCode::OK);
    let initial_project = json(initial_project).await["data"].clone();
    assert_eq!(
        initial_project["preview_url"],
        format!(
            "/media/thumbnails/projects/{}/thumb_ordered-7001.jpg",
            fixture.project_id
        )
    );
    let initial_step_cover = initial_project["images"]
        .as_array()
        .unwrap()
        .iter()
        .find(|item| item["id"] == 7001)
        .unwrap();
    assert_eq!(initial_step_cover["position"], 0);
    assert_eq!(initial_step_cover["is_primary"], true);
    assert_eq!(initial_step_cover["is_title_image"], true);

    let before_step_reorder: String =
        sqlx::query_scalar("SELECT updated_at FROM projects WHERE id = ?")
            .bind(fixture.project_id)
            .fetch_one(&fixture.pool)
            .await
            .unwrap();
    let step_reorder = fixture
        .app
        .clone()
        .oneshot(media_order_request(
            &format!(
                "/projects/{}/steps/{}/images/order",
                fixture.project_id, fixture.step_id
            ),
            &session,
            &csrf,
            &[7002, 7001],
        ))
        .await
        .unwrap();
    assert_eq!(step_reorder.status(), StatusCode::OK);
    let step_reorder = json(step_reorder).await;
    assert_eq!(step_reorder["data"]["ids"], serde_json::json!([7002, 7001]));
    let step_updated_at = step_reorder["data"]["updated_at"].as_str().unwrap();
    assert!(step_updated_at > before_step_reorder.as_str());
    let step_cover_position: i64 =
        sqlx::query_scalar("SELECT position FROM media_files WHERE id = ?")
            .bind(step_cover_id)
            .fetch_one(&fixture.pool)
            .await
            .unwrap();
    assert_eq!(step_cover_position, 1);
    let project_cover: i64 = sqlx::query_scalar(
        "SELECT id FROM media_files WHERE project_id = ? AND is_project_cover = 1",
    )
    .bind(fixture.project_id)
    .fetch_one(&fixture.pool)
    .await
    .unwrap();
    assert_eq!(
        project_cover, step_cover_id,
        "step ordering must not change the project cover"
    );

    let invalid = fixture
        .app
        .clone()
        .oneshot(media_order_request(
            &format!("/projects/{}/images/order", fixture.project_id),
            &session,
            &csrf,
            &[7101, 7101],
        ))
        .await
        .unwrap();
    assert_eq!(invalid.status(), StatusCode::UNPROCESSABLE_ENTITY);
    let wrong_group = fixture
        .app
        .clone()
        .oneshot(media_order_request(
            &format!("/projects/{}/images/order", fixture.project_id),
            &session,
            &csrf,
            &[7101, 7001],
        ))
        .await
        .unwrap();
    assert_eq!(wrong_group.status(), StatusCode::UNPROCESSABLE_ENTITY);
    let missing_id = fixture
        .app
        .clone()
        .oneshot(media_order_request(
            &format!("/projects/{}/images/order", fixture.project_id),
            &session,
            &csrf,
            &[7101],
        ))
        .await
        .unwrap();
    assert_eq!(missing_id.status(), StatusCode::UNPROCESSABLE_ENTITY);
    let foreign_step_id: i64 = sqlx::query_scalar(
        "INSERT INTO project_steps (project_id,title,step_number) VALUES (?, 'other', 2) RETURNING id",
    )
    .bind(fixture.foreign_project_id)
    .fetch_one(&fixture.pool)
    .await
    .unwrap();
    let wrong_step = fixture
        .app
        .clone()
        .oneshot(media_order_request(
            &format!(
                "/projects/{}/steps/{}/images/order",
                fixture.project_id, foreign_step_id
            ),
            &session,
            &csrf,
            &[],
        ))
        .await
        .unwrap();
    assert_eq!(wrong_step.status(), StatusCode::NOT_FOUND);
    let foreign_parent = fixture
        .app
        .clone()
        .oneshot(media_order_request(
            &format!("/projects/{}/images/order", fixture.foreign_project_id),
            &session,
            &csrf,
            &[],
        ))
        .await
        .unwrap();
    assert_eq!(foreign_parent.status(), StatusCode::NOT_FOUND);
    let title_positions: Vec<(i64, i64)> = sqlx::query_as(
        "SELECT public_id,position FROM media_files WHERE project_id = ? AND kind = 'title' ORDER BY position",
    )
    .bind(fixture.project_id)
    .fetch_all(&fixture.pool)
    .await
    .unwrap();
    assert_eq!(title_positions, vec![(7101, 0), (7102, 1)]);

    let title_reorder = fixture
        .app
        .clone()
        .oneshot(media_order_request(
            &format!("/projects/{}/images/order", fixture.project_id),
            &session,
            &csrf,
            &[7102, 7101],
        ))
        .await
        .unwrap();
    assert_eq!(title_reorder.status(), StatusCode::OK);
    assert_eq!(
        json(title_reorder).await["data"]["ids"],
        serde_json::json!([7102, 7101])
    );
    let cover_id: i64 = sqlx::query_scalar(
        "SELECT id FROM media_files WHERE project_id = ? AND is_project_cover = 1",
    )
    .bind(fixture.project_id)
    .fetch_one(&fixture.pool)
    .await
    .unwrap();
    assert_eq!(cover_id, title_second_id);
    let step_legacy_flag: Option<bool> =
        sqlx::query_scalar("SELECT legacy_is_title_image FROM media_files WHERE id = ?")
            .bind(step_cover_id)
            .fetch_one(&fixture.pool)
            .await
            .unwrap();
    assert_eq!(step_legacy_flag, Some(true));

    let delete_title_cover = fixture
        .app
        .clone()
        .oneshot(delete_request(
            &format!("/projects/{}/images/7102", fixture.project_id),
            &session,
            &csrf,
        ))
        .await
        .unwrap();
    assert_eq!(delete_title_cover.status(), StatusCode::NO_CONTENT);
    let restored_cover: i64 = sqlx::query_scalar(
        "SELECT id FROM media_files WHERE project_id = ? AND is_project_cover = 1",
    )
    .bind(fixture.project_id)
    .fetch_one(&fixture.pool)
    .await
    .unwrap();
    assert_eq!(
        restored_cover, title_first_id,
        "deleting an explicit cover promotes the first remaining title image"
    );

    let initial_yarn = fixture
        .app
        .clone()
        .oneshot(
            Request::get(format!("/yarns/{}", fixture.yarn_id))
                .header("host", HOST)
                .header("cookie", format!("__Host-stricknani_session={session}"))
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(initial_yarn.status(), StatusCode::OK);
    let initial_yarn = json(initial_yarn).await["data"].clone();
    assert_eq!(initial_yarn["preview_url"], serde_json::Value::Null);
    assert_eq!(initial_yarn["photos"][0]["is_primary"], false);
    let upload_first_photo = fixture
        .app
        .clone()
        .oneshot(multipart_request(MultipartSpec {
            method: "POST",
            path: &format!("/yarns/{}/photos", fixture.yarn_id),
            session: &session,
            csrf: Some(&csrf),
            origin: Some(ORIGIN),
            file_name: "first-primary.png",
            content_type: "image/png",
            bytes: &png_bytes(),
            alt_text: None,
        }))
        .await
        .unwrap();
    assert_eq!(upload_first_photo.status(), StatusCode::CREATED);
    let upload_first_photo = json(upload_first_photo).await["data"].clone();
    let uploaded_photo_id = upload_first_photo["id"].as_i64().unwrap();
    assert_eq!(upload_first_photo["is_primary"], true);
    let old_photo_positions: Vec<(i64, i64)> = sqlx::query_as(
        "SELECT public_id,position FROM media_files WHERE yarn_id = ? AND public_id IN (8001,8002) ORDER BY position",
    )
    .bind(fixture.yarn_id)
    .fetch_all(&fixture.pool)
    .await
    .unwrap();
    assert_eq!(old_photo_positions, vec![(8001, 1), (8002, 2)]);
    let photo_reorder = fixture
        .app
        .clone()
        .oneshot(media_order_request(
            &format!("/yarns/{}/photos/order", fixture.yarn_id),
            &session,
            &csrf,
            &[8002, 8001, uploaded_photo_id],
        ))
        .await
        .unwrap();
    assert_eq!(photo_reorder.status(), StatusCode::OK);
    assert_eq!(
        json(photo_reorder).await["data"]["ids"],
        serde_json::json!([8002, 8001, uploaded_photo_id])
    );
    let primary_photo: i64 =
        sqlx::query_scalar("SELECT id FROM media_files WHERE yarn_id = ? AND is_primary = 1")
            .bind(fixture.yarn_id)
            .fetch_one(&fixture.pool)
            .await
            .unwrap();
    assert_eq!(primary_photo, photo_second_id);
    let delete_photo_cover = fixture
        .app
        .clone()
        .oneshot(delete_request(
            &format!("/yarns/{}/photos/8002", fixture.yarn_id),
            &session,
            &csrf,
        ))
        .await
        .unwrap();
    assert_eq!(delete_photo_cover.status(), StatusCode::NO_CONTENT);
    let compacted_photo: (i64, i64, bool) =
        sqlx::query_as("SELECT id,position,is_primary FROM media_files WHERE public_id = 8001")
            .fetch_one(&fixture.pool)
            .await
            .unwrap();
    assert_eq!(compacted_photo, (photo_first_id, 0, true));

    let audit_count: i64 = sqlx::query_scalar(
        "SELECT COUNT(*) FROM audit_events WHERE entity_type IN ('project','yarn') AND action = 'updated'",
    )
    .fetch_one(&fixture.pool)
    .await
    .unwrap();
    assert_eq!(audit_count, 3);
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
        let position: i64 = sqlx::query_scalar(
            "SELECT position FROM media_files WHERE yarn_id = ? AND public_id = ?",
        )
        .bind(fixture.yarn_id)
        .bind(ids.last().unwrap())
        .fetch_one(&fixture.pool)
        .await
        .unwrap();
        assert_eq!(position, (ids.len() - 1) as i64);
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
    let remaining_position: i64 =
        sqlx::query_scalar("SELECT position FROM media_files WHERE yarn_id = ? AND public_id = ?")
            .bind(fixture.yarn_id)
            .bind(ids[1])
            .fetch_one(&fixture.pool)
            .await
            .unwrap();
    assert_eq!(remaining_position, 0);
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

/// A `width` x `height` image whose left half is red and right half blue, in `format`.
fn two_tone(width: u32, height: u32, format: image::ImageFormat) -> Vec<u8> {
    let image = image::RgbImage::from_fn(width, height, |x, _| {
        if x < width / 2 {
            image::Rgb([220, 20, 20])
        } else {
            image::Rgb([20, 20, 220])
        }
    });
    let mut output = Cursor::new(Vec::new());
    image::DynamicImage::ImageRgb8(image)
        .write_to(&mut output, format)
        .expect("test image should encode");
    output.into_inner()
}

/// A JPEG whose EXIF says "rotate 90 degrees clockwise to display" (orientation 6), the way
/// phone cameras store portrait photos.
fn jpeg_with_orientation_6(width: u32, height: u32) -> Vec<u8> {
    let jpeg = two_tone(width, height, image::ImageFormat::Jpeg);
    assert_eq!(&jpeg[..2], &[0xFF, 0xD8]);
    let tiff: [u8; 26] = [
        0x4D, 0x4D, 0x00, 0x2A, 0x00, 0x00, 0x00, 0x08, // big-endian TIFF, IFD at 8
        0x00, 0x01, // one entry
        0x01, 0x12, 0x00, 0x03, 0x00, 0x00, 0x00, 0x01, 0x00, 0x06, 0x00,
        0x00, // Orientation=6
        0x00, 0x00, 0x00, 0x00, // no next IFD
    ];
    let mut app1 = vec![0xFF, 0xE1];
    let length = (2 + 6 + tiff.len()) as u16;
    app1.extend_from_slice(&length.to_be_bytes());
    app1.extend_from_slice(b"Exif\0\0");
    app1.extend_from_slice(&tiff);
    let mut out = jpeg[..2].to_vec();
    out.extend_from_slice(&app1);
    out.extend_from_slice(&jpeg[2..]);
    out
}

fn files_below(dir: &std::path::Path) -> Vec<std::path::PathBuf> {
    let mut found = Vec::new();
    if let Ok(entries) = std::fs::read_dir(dir) {
        for entry in entries.flatten() {
            let path = entry.path();
            if path.is_dir() {
                found.extend(files_below(&path));
            } else {
                found.push(path);
            }
        }
    }
    found
}

fn edit_request(path: &str, session: &str, csrf: &str, body: serde_json::Value) -> Request<Body> {
    Request::post(path)
        .header("host", HOST)
        .header("origin", ORIGIN)
        .header("sec-fetch-site", "same-origin")
        .header("cookie", format!("__Host-stricknani_session={session}"))
        .header("x-csrf-token", csrf)
        .header("content-type", "application/json")
        .body(Body::from(body.to_string()))
        .unwrap()
}

async fn stored_size(pool: &SqlitePool, public_id: i64) -> (i64, i64, String) {
    sqlx::query_as("SELECT width, height, filename FROM media_files WHERE public_id = ?")
        .bind(public_id)
        .fetch_one(pool)
        .await
        .unwrap()
}

#[tokio::test]
async fn uploads_respect_the_exif_orientation_for_dimensions_and_thumbnails() {
    let fixture = fixture().await;
    let (session, csrf) = signed_in(
        &fixture.app,
        "media-owner@example.test",
        "media-owner-password",
    )
    .await;
    // Stored 80 wide x 40 high, displayed (after orientation 6) 40 wide x 80 high.
    let jpeg = jpeg_with_orientation_6(80, 40);
    let response = fixture
        .app
        .clone()
        .oneshot(multipart_request(MultipartSpec {
            method: "POST",
            path: &format!("/yarns/{}/photos", fixture.yarn_id),
            session: &session,
            csrf: Some(&csrf),
            origin: Some(ORIGIN),
            file_name: "portrait.jpg",
            content_type: "image/jpeg",
            bytes: &jpeg,
            alt_text: None,
        }))
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::CREATED);
    let photo_id = json(response).await["data"]["id"].as_i64().unwrap();
    let (width, height, _) = stored_size(&fixture.pool, photo_id).await;
    assert_eq!((width, height), (40, 80), "dimensions are the upright ones");

    let thumbnails = files_below(&fixture._root.path().join("thumbnails"));
    assert_eq!(thumbnails.len(), 1);
    let thumbnail = image::open(&thumbnails[0]).expect("thumbnail should decode");
    assert!(
        thumbnail.height() > thumbnail.width(),
        "the thumbnail is upright (portrait): {}x{}",
        thumbnail.width(),
        thumbnail.height()
    );
}

#[tokio::test]
async fn image_edits_rotate_and_crop_in_place_and_keep_the_item() {
    let fixture = fixture().await;
    let (session, csrf) = signed_in(
        &fixture.app,
        "media-owner@example.test",
        "media-owner-password",
    )
    .await;
    let png = two_tone(64, 32, image::ImageFormat::Png);
    let upload = fixture
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
            alt_text: Some("Cover"),
        }))
        .await
        .unwrap();
    assert_eq!(upload.status(), StatusCode::CREATED);
    let uploaded = json(upload).await["data"].clone();
    let image_id = uploaded["id"].as_i64().unwrap();
    let (_, _, old_filename) = stored_size(&fixture.pool, image_id).await;
    let edit_path = format!("/projects/{}/images/{image_id}/edit", fixture.project_id);

    // Rotate a quarter turn: 64x32 becomes 32x64, same item, new file.
    let rotated = fixture
        .app
        .clone()
        .oneshot(edit_request(
            &edit_path,
            &session,
            &csrf,
            serde_json::json!({"rotate": 90}),
        ))
        .await
        .unwrap();
    assert_eq!(rotated.status(), StatusCode::OK);
    let rotated = json(rotated).await["data"].clone();
    assert_eq!(rotated["id"], image_id);
    assert_eq!(rotated["alt_text"], "Cover");
    assert_eq!(rotated["is_title_image"], true);
    assert_ne!(rotated["url"], uploaded["url"], "a new URL busts caches");
    let (width, height, new_filename) = stored_size(&fixture.pool, image_id).await;
    assert_eq!((width, height), (32, 64));
    let stored = files_below(fixture._root.path());
    assert!(
        stored.iter().all(|path| !path.ends_with(&old_filename)),
        "the old original is removed"
    );
    assert!(stored.iter().any(|path| path.ends_with(&new_filename)));

    // Crop the top half of the rotated image: 32x64 becomes 32x32. Rotating the two-tone image
    // clockwise puts the red (left) half on top, so the crop is all red.
    let cropped = fixture
        .app
        .clone()
        .oneshot(edit_request(
            &edit_path,
            &session,
            &csrf,
            serde_json::json!({"crop": {"x": 0.0, "y": 0.0, "width": 1.0, "height": 0.5}}),
        ))
        .await
        .unwrap();
    assert_eq!(cropped.status(), StatusCode::OK);
    let (width, height, filename) = stored_size(&fixture.pool, image_id).await;
    assert_eq!((width, height), (32, 32));
    let path = files_below(fixture._root.path())
        .into_iter()
        .find(|path| path.ends_with(&filename) && !path.to_string_lossy().contains("thumbnails"))
        .unwrap();
    let pixel = image::open(path).unwrap().to_rgb8().get_pixel(16, 16).0;
    assert!(
        pixel[0] > 150 && pixel[2] < 100,
        "the crop is the red half: {pixel:?}"
    );

    // Invalid edits change nothing.
    for body in [
        serde_json::json!({"rotate": 45}),
        serde_json::json!({}),
        serde_json::json!({"crop": {"x": 0.5, "y": 0.0, "width": 0.8, "height": 1.0}}),
        serde_json::json!({"crop": {"x": 0.0, "y": 0.0, "width": 0.1, "height": 0.1}}),
        serde_json::json!({"rotate": 90, "extra": true}),
    ] {
        let response = fixture
            .app
            .clone()
            .oneshot(edit_request(&edit_path, &session, &csrf, body.clone()))
            .await
            .unwrap();
        assert_eq!(
            response.status(),
            StatusCode::UNPROCESSABLE_ENTITY,
            "{body}"
        );
        assert_eq!(json(response).await["error"]["code"], "invalid_edit");
    }
    assert_eq!(stored_size(&fixture.pool, image_id).await.2, filename);

    // Someone else's project: not found, and a yarn photo id is not a project image.
    let foreign = fixture
        .app
        .clone()
        .oneshot(edit_request(
            &format!(
                "/projects/{}/images/{image_id}/edit",
                fixture.foreign_project_id
            ),
            &session,
            &csrf,
            serde_json::json!({"rotate": 90}),
        ))
        .await
        .unwrap();
    assert_eq!(foreign.status(), StatusCode::NOT_FOUND);
}

#[tokio::test]
async fn yarn_photo_edits_keep_the_primary_photo() {
    let fixture = fixture().await;
    let (session, csrf) = signed_in(
        &fixture.app,
        "media-owner@example.test",
        "media-owner-password",
    )
    .await;
    let jpeg = two_tone(64, 48, image::ImageFormat::Jpeg);
    let upload = fixture
        .app
        .clone()
        .oneshot(multipart_request(MultipartSpec {
            method: "POST",
            path: &format!("/yarns/{}/photos", fixture.yarn_id),
            session: &session,
            csrf: Some(&csrf),
            origin: Some(ORIGIN),
            file_name: "skein.jpg",
            content_type: "image/jpeg",
            bytes: &jpeg,
            alt_text: None,
        }))
        .await
        .unwrap();
    assert_eq!(upload.status(), StatusCode::CREATED);
    let photo_id = json(upload).await["data"]["id"].as_i64().unwrap();
    let edited = fixture
        .app
        .clone()
        .oneshot(edit_request(
            &format!("/yarns/{}/photos/{photo_id}/edit", fixture.yarn_id),
            &session,
            &csrf,
            serde_json::json!({"rotate": 270, "crop": {"x": 0.25, "y": 0.0, "width": 0.5, "height": 1.0}}),
        ))
        .await
        .unwrap();
    assert_eq!(edited.status(), StatusCode::OK);
    let edited = json(edited).await["data"].clone();
    assert_eq!(edited["id"], photo_id);
    assert_eq!(edited["is_primary"], true);
    // 64x48 rotated is 48x64; half its width is 24.
    let (width, height, filename) = stored_size(&fixture.pool, photo_id).await;
    assert_eq!((width, height), (24, 64));
    assert!(filename.ends_with(".jpg"), "JPEG stays JPEG: {filename}");

    let foreign = fixture
        .app
        .clone()
        .oneshot(edit_request(
            &format!("/yarns/{}/photos/{photo_id}/edit", fixture.foreign_yarn_id),
            &session,
            &csrf,
            serde_json::json!({"rotate": 90}),
        ))
        .await
        .unwrap();
    assert_eq!(foreign.status(), StatusCode::NOT_FOUND);
}

fn patch_request(
    path: &str,
    session: &str,
    csrf: Option<&str>,
    body: serde_json::Value,
) -> Request<Body> {
    let mut request = Request::patch(path)
        .header("host", HOST)
        .header("origin", ORIGIN)
        .header("sec-fetch-site", "same-origin")
        .header("cookie", format!("__Host-stricknani_session={session}"))
        .header("content-type", "application/json");
    if let Some(csrf) = csrf {
        request = request.header("x-csrf-token", csrf);
    }
    request.body(Body::from(body.to_string())).unwrap()
}

async fn alt_text_of(pool: &SqlitePool, public_id: i64) -> String {
    sqlx::query_scalar("SELECT alt_text FROM media_files WHERE public_id = ?")
        .bind(public_id)
        .fetch_one(pool)
        .await
        .unwrap()
}

#[tokio::test]
async fn image_descriptions_can_be_changed_validated_and_audited() {
    let fixture = fixture().await;
    let (session, csrf) = signed_in(
        &fixture.app,
        "media-owner@example.test",
        "media-owner-password",
    )
    .await;
    let png = two_tone(64, 32, image::ImageFormat::Png);
    let upload = fixture
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
            alt_text: None,
        }))
        .await
        .unwrap();
    assert_eq!(upload.status(), StatusCode::CREATED);
    let image_id = json(upload).await["data"]["id"].as_i64().unwrap();
    let path = format!("/projects/{}/images/{image_id}", fixture.project_id);
    let project_updated = |pool: SqlitePool, id: i64| async move {
        sqlx::query_scalar::<_, String>("SELECT updated_at FROM projects WHERE id = ?")
            .bind(id)
            .fetch_one(&pool)
            .await
            .unwrap()
    };
    let before = project_updated(fixture.pool.clone(), fixture.project_id).await;

    // A change is trimmed, answered like the upload, bumps the project and is audited.
    let response = fixture
        .app
        .clone()
        .oneshot(patch_request(
            &path,
            &session,
            Some(&csrf),
            serde_json::json!({"alt_text": "  Sleeve detail  "}),
        ))
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    let data = json(response).await["data"].clone();
    assert_eq!(data["id"], image_id);
    assert_eq!(data["alt_text"], "Sleeve detail");
    assert_eq!(data["step_id"], fixture.step_id);
    assert_eq!(alt_text_of(&fixture.pool, image_id).await, "Sleeve detail");
    let after = project_updated(fixture.pool.clone(), fixture.project_id).await;
    assert!(after > before, "{after} should follow {before}");
    let audits = sqlx::query_scalar::<_, i64>(
        "SELECT COUNT(*) FROM audit_events WHERE actor_user_id = ? AND entity_type = 'project' \
         AND entity_id = ? AND action = 'updated'",
    )
    .bind(fixture.owner_id)
    .bind(fixture.project_id)
    .fetch_one(&fixture.pool)
    .await
    .unwrap();
    assert_eq!(audits, 1);

    // The same text again is a no-op: no second audit entry.
    let again = fixture
        .app
        .clone()
        .oneshot(patch_request(
            &path,
            &session,
            Some(&csrf),
            serde_json::json!({"alt_text": "Sleeve detail"}),
        ))
        .await
        .unwrap();
    assert_eq!(again.status(), StatusCode::OK);
    let audits_after = sqlx::query_scalar::<_, i64>(
        "SELECT COUNT(*) FROM audit_events WHERE entity_type = 'project' AND action = 'updated'",
    )
    .fetch_one(&fixture.pool)
    .await
    .unwrap();
    assert_eq!(audits_after, 1);

    // Empty falls back to the original file name.
    let empty = fixture
        .app
        .clone()
        .oneshot(patch_request(
            &path,
            &session,
            Some(&csrf),
            serde_json::json!({"alt_text": "   "}),
        ))
        .await
        .unwrap();
    assert_eq!(empty.status(), StatusCode::OK);
    assert_eq!(json(empty).await["data"]["alt_text"], "step.png");
    assert_eq!(alt_text_of(&fixture.pool, image_id).await, "step.png");

    // Validation: too long, wrong shape, extra field.
    for body in [
        serde_json::json!({"alt_text": "x".repeat(4097)}),
        serde_json::json!({}),
        serde_json::json!({"alt_text": 5}),
        serde_json::json!({"alt_text": "ok", "extra": true}),
    ] {
        let response = fixture
            .app
            .clone()
            .oneshot(patch_request(&path, &session, Some(&csrf), body.clone()))
            .await
            .unwrap();
        assert_eq!(
            response.status(),
            StatusCode::UNPROCESSABLE_ENTITY,
            "{body}"
        );
        assert_eq!(json(response).await["error"]["code"], "validation_error");
    }
    assert_eq!(alt_text_of(&fixture.pool, image_id).await, "step.png");

    // A cookie mutation needs the CSRF token.
    let no_csrf = fixture
        .app
        .clone()
        .oneshot(patch_request(
            &path,
            &session,
            None,
            serde_json::json!({"alt_text": "No token"}),
        ))
        .await
        .unwrap();
    assert!(no_csrf.status().is_client_error());
    assert_eq!(alt_text_of(&fixture.pool, image_id).await, "step.png");

    // Another account's project and a wrong image id are not found.
    for path in [
        format!("/projects/{}/images/{image_id}", fixture.foreign_project_id),
        format!("/projects/{}/images/999999", fixture.project_id),
    ] {
        let response = fixture
            .app
            .clone()
            .oneshot(patch_request(
                &path,
                &session,
                Some(&csrf),
                serde_json::json!({"alt_text": "Nope"}),
            ))
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::NOT_FOUND, "{path}");
    }
}

#[tokio::test]
async fn yarn_photo_descriptions_can_be_changed() {
    let fixture = fixture().await;
    let (session, csrf) = signed_in(
        &fixture.app,
        "media-owner@example.test",
        "media-owner-password",
    )
    .await;
    let jpeg = two_tone(64, 48, image::ImageFormat::Jpeg);
    let upload = fixture
        .app
        .clone()
        .oneshot(multipart_request(MultipartSpec {
            method: "POST",
            path: &format!("/yarns/{}/photos", fixture.yarn_id),
            session: &session,
            csrf: Some(&csrf),
            origin: Some(ORIGIN),
            file_name: "skein.jpg",
            content_type: "image/jpeg",
            bytes: &jpeg,
            alt_text: None,
        }))
        .await
        .unwrap();
    let photo_id = json(upload).await["data"]["id"].as_i64().unwrap();
    let path = format!("/yarns/{}/photos/{photo_id}", fixture.yarn_id);
    let response = fixture
        .app
        .clone()
        .oneshot(patch_request(
            &path,
            &session,
            Some(&csrf),
            serde_json::json!({"alt_text": "Close-up"}),
        ))
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    let data = json(response).await["data"].clone();
    assert_eq!(data["alt_text"], "Close-up");
    assert_eq!(data["is_primary"], true);
    assert_eq!(alt_text_of(&fixture.pool, photo_id).await, "Close-up");
    let foreign = fixture
        .app
        .clone()
        .oneshot(patch_request(
            &format!("/yarns/{}/photos/{photo_id}", fixture.foreign_yarn_id),
            &session,
            Some(&csrf),
            serde_json::json!({"alt_text": "Nope"}),
        ))
        .await
        .unwrap();
    assert_eq!(foreign.status(), StatusCode::NOT_FOUND);
}

#[tokio::test]
async fn regenerating_thumbnails_turns_old_sideways_ones_upright() {
    let fixture = fixture().await;
    let (session, csrf) = signed_in(
        &fixture.app,
        "media-owner@example.test",
        "media-owner-password",
    )
    .await;
    let jpeg = jpeg_with_orientation_6(80, 40);
    let response = fixture
        .app
        .clone()
        .oneshot(multipart_request(MultipartSpec {
            method: "POST",
            path: &format!("/projects/{}/images/title", fixture.project_id),
            session: &session,
            csrf: Some(&csrf),
            origin: Some(ORIGIN),
            file_name: "portrait.jpg",
            content_type: "image/jpeg",
            bytes: &jpeg,
            alt_text: None,
        }))
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::CREATED);
    let image_id = json(response).await["data"]["id"].as_i64().unwrap();
    // What an upload before orientation support left behind: the size and the thumbnail both
    // sideways (landscape), the thumbnail still in place.
    sqlx::query("UPDATE media_files SET width = 80, height = 40 WHERE public_id = ?")
        .bind(image_id)
        .execute(&fixture.pool)
        .await
        .unwrap();
    let old_thumbnails = files_below(&fixture._root.path().join("thumbnails"));
    assert_eq!(old_thumbnails.len(), 1);
    std::fs::write(
        &old_thumbnails[0],
        two_tone(60, 30, image::ImageFormat::Jpeg),
    )
    .unwrap();

    let result =
        stricknani_server::media::regenerate_thumbnails(&fixture.pool, fixture._root.path())
            .await
            .unwrap();
    assert_eq!(result.updated, 1);
    assert_eq!(result.skipped, 0);
    let (width, height, _) = stored_size(&fixture.pool, image_id).await;
    assert_eq!((width, height), (40, 80));
    let thumbnails = files_below(&fixture._root.path().join("thumbnails"));
    assert_eq!(thumbnails.len(), 1);
    let thumbnail = image::open(&thumbnails[0]).unwrap();
    assert!(thumbnail.height() > thumbnail.width());
}
