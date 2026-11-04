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
