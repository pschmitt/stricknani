use std::{
    collections::HashMap,
    io::Cursor,
    sync::{Arc, Mutex},
};

use axum::{
    Router,
    body::Body,
    http::{Request, StatusCode},
};
use http_body_util::BodyExt;
use sha2::{Digest, Sha256};
use sqlx::SqlitePool;
use stricknani_server::{
    db,
    image_import::{FetchError, FetchFuture, FetchedImage, HttpImageFetcher, ImageFetcher},
    media, projects, yarns,
};
use tower::ServiceExt;
use url::Url;

const TOKEN: &str = "url-image-import-test-token";

#[derive(Default)]
struct FixtureFetcher {
    responses: Mutex<HashMap<String, FetchedImage>>,
    requested: Mutex<Vec<String>>,
}

impl ImageFetcher for FixtureFetcher {
    fn fetch<'a>(&'a self, url: &'a Url) -> FetchFuture<'a> {
        let key = url.as_str().to_owned();
        self.requested
            .lock()
            .expect("request list lock should be healthy")
            .push(key.clone());
        let response = self
            .responses
            .lock()
            .expect("fixture response lock should be healthy")
            .get(&key)
            .cloned();
        Box::pin(async move { response.ok_or(FetchError::Transport) })
    }
}

struct Harness {
    app: Router,
    pool: SqlitePool,
    user_id: i64,
    fetcher: Arc<FixtureFetcher>,
    _media_root: tempfile::TempDir,
}

async fn harness(external_imports: bool) -> Harness {
    let pool = db::connect("sqlite::memory:")
        .await
        .expect("test database should migrate");
    let user_id: i64 = sqlx::query_scalar(
        "INSERT INTO users (email, password_hash) VALUES ('import@example.test', 'test-hash') RETURNING id",
    )
    .fetch_one(&pool)
    .await
    .expect("owner should be inserted");
    let token_hash = hex::encode(Sha256::digest(TOKEN.as_bytes()));
    sqlx::query(
        "INSERT INTO api_tokens (user_id, name, token_hash, created_at) VALUES (?, 'test', ?, unixepoch())",
    )
    .bind(user_id)
    .bind(token_hash)
    .execute(&pool)
    .await
    .expect("API token should be inserted");

    let fetcher = Arc::new(FixtureFetcher::default());
    let media_root = tempfile::tempdir().expect("media root should be created");
    let state = db::AppState::new(pool.clone())
        .with_media_root(media_root.path().to_path_buf())
        .with_external_imports(external_imports)
        .with_image_fetcher(fetcher.clone());
    let app = Router::new()
        .nest(
            "/api/v1",
            projects::router()
                .merge(yarns::router())
                .with_state(state.clone()),
        )
        .merge(media::router().with_state(state));
    Harness {
        app,
        pool,
        user_id,
        fetcher,
        _media_root: media_root,
    }
}

fn image_bytes(red: u8, green: u8, blue: u8) -> Vec<u8> {
    let image = image::DynamicImage::ImageRgb8(image::RgbImage::from_fn(96, 80, |x, y| {
        image::Rgb([red.wrapping_add(x as u8), green.wrapping_add(y as u8), blue])
    }));
    let mut output = Cursor::new(Vec::new());
    image
        .write_to(&mut output, image::ImageFormat::Png)
        .expect("fixture PNG should encode");
    output.into_inner()
}

fn fixture_image(bytes: Vec<u8>) -> FetchedImage {
    FetchedImage {
        content_type: Some("image/png".to_owned()),
        bytes,
    }
}

fn request(method: &str, path: &str, body: serde_json::Value) -> Request<Body> {
    Request::builder()
        .method(method)
        .uri(path)
        .header("authorization", format!("Bearer {TOKEN}"))
        .header("content-type", "application/json")
        .body(Body::from(body.to_string()))
        .expect("request should be valid")
}

async fn json_body(response: axum::response::Response) -> serde_json::Value {
    let bytes = response
        .into_body()
        .collect()
        .await
        .expect("body should collect")
        .to_bytes();
    serde_json::from_slice(&bytes).expect("response should contain JSON")
}

#[tokio::test]
async fn create_imports_gallery_and_step_images_into_owned_media_rows() {
    let harness = harness(true).await;
    let gallery = image_bytes(4, 20, 80);
    let gallery_extra = image_bytes(12, 55, 130);
    let step_image = image_bytes(100, 30, 1);
    harness
        .fetcher
        .responses
        .lock()
        .unwrap()
        .extend(HashMap::from([
            (
                "https://images.example.test/gallery.png".to_owned(),
                fixture_image(gallery.clone()),
            ),
            (
                "https://images.example.test/gallery-copy.png".to_owned(),
                fixture_image(gallery),
            ),
            (
                "https://images.example.test/gallery-extra.png".to_owned(),
                fixture_image(gallery_extra),
            ),
            (
                "https://images.example.test/step.png".to_owned(),
                fixture_image(step_image),
            ),
            (
                "https://images.example.test/not-image".to_owned(),
                FetchedImage {
                    content_type: Some("text/html".to_owned()),
                    bytes: b"<html>not an image</html>".to_vec(),
                },
            ),
        ]));

    let response = harness
        .app
        .clone()
        .oneshot(request(
            "POST",
            "/api/v1/projects",
            serde_json::json!({
                "name": "Imported socks",
                "image_urls": [
                    "https://images.example.test/gallery.png",
                    "https://images.example.test/gallery-extra.png",
                    "https://images.example.test/gallery-copy.png",
                    "https://images.example.test/not-image"
                ],
                "steps": [{
                    "title": "Heel",
                    "step_number": 1,
                    "image_urls": ["https://images.example.test/step.png"]
                }]
            }),
        ))
        .await
        .expect("API should respond");
    assert_eq!(response.status(), StatusCode::CREATED);
    let project = json_body(response).await;
    let project_id = project["id"]
        .as_i64()
        .expect("project ID should be numeric");
    let step_id = project["steps"][0]["id"]
        .as_i64()
        .expect("step ID should be numeric");
    let images = project["images"]
        .as_array()
        .expect("images should be returned");
    assert_eq!(
        images.len(),
        3,
        "duplicate and non-image URLs should be skipped"
    );
    assert_eq!(images[0]["is_title_image"], true);
    assert_eq!(images[0]["step_id"], serde_json::Value::Null);
    assert_eq!(images[1]["is_title_image"], false);
    assert_eq!(images[1]["step_id"], serde_json::Value::Null);
    assert_eq!(images[2]["is_title_image"], false);
    assert_eq!(images[2]["step_id"], step_id);
    assert!(
        images[0]["url"]
            .as_str()
            .unwrap()
            .starts_with("/media/projects/")
    );

    let rows: Vec<(String, Option<i64>, i64, i64)> = sqlx::query_as(
        "SELECT kind, step_id, owner_id, is_primary FROM media_files WHERE project_id = ? ORDER BY id",
    )
    .bind(project_id)
    .fetch_all(&harness.pool)
    .await
    .expect("media rows should be queryable");
    assert_eq!(rows.len(), 3);
    assert_eq!(rows[0], ("title".to_owned(), None, harness.user_id, 1));
    assert_eq!(rows[1], ("title".to_owned(), None, harness.user_id, 0));
    assert_eq!(
        rows[2],
        ("step".to_owned(), Some(step_id), harness.user_id, 0)
    );
    assert_eq!(harness.fetcher.requested.lock().unwrap().len(), 5);

    let denied = harness
        .app
        .clone()
        .oneshot(
            Request::get(images[0]["url"].as_str().unwrap())
                .header("authorization", "Bearer another-token")
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .expect("media endpoint should respond");
    assert_eq!(denied.status(), StatusCode::UNAUTHORIZED);
    let foreign_id: i64 = sqlx::query_scalar(
        "INSERT INTO users (email, password_hash) VALUES ('foreign@example.test', 'fixture-hash') RETURNING id",
    ).fetch_one(&harness.pool).await.unwrap();
    sqlx::query("INSERT INTO api_tokens (user_id, name, token_hash, created_at) VALUES (?, 'foreign', ?, unixepoch())")
        .bind(foreign_id)
        .bind(hex::encode(Sha256::digest(b"foreign-fixture-token")))
        .execute(&harness.pool).await.unwrap();
    let foreign = harness
        .app
        .clone()
        .oneshot(
            Request::get(images[0]["url"].as_str().unwrap())
                .header("authorization", "Bearer foreign-fixture-token")
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(foreign.status(), StatusCode::FORBIDDEN);
}

#[tokio::test]
async fn external_imports_off_keeps_create_501_without_fetch_or_rows() {
    let harness = harness(false).await;
    let response = harness
        .app
        .oneshot(request(
            "POST",
            "/api/v1/projects",
            serde_json::json!({
                "name": "No external fetch",
                "image_urls": ["https://images.example.test/image.png"]
            }),
        ))
        .await
        .expect("API should respond");
    assert_eq!(response.status(), StatusCode::NOT_IMPLEMENTED);
    assert_eq!(
        sqlx::query_scalar::<_, i64>("SELECT count(*) FROM projects")
            .fetch_one(&harness.pool)
            .await
            .unwrap(),
        0
    );
    assert!(harness.fetcher.requested.lock().unwrap().is_empty());
}

#[tokio::test]
async fn update_ignores_create_only_image_url_fields() {
    let harness = harness(true).await;
    let created = harness
        .app
        .clone()
        .oneshot(request(
            "POST",
            "/api/v1/projects",
            serde_json::json!({"name": "Existing project"}),
        ))
        .await
        .unwrap();
    let project = json_body(created).await;
    let project_id = project["id"].as_i64().unwrap();
    let response = harness
        .app
        .clone()
        .oneshot(request(
            "PUT",
            &format!("/api/v1/projects/{project_id}"),
            serde_json::json!({
                "name": "Updated project",
                "image_urls": ["https://images.example.test/ignored.png"],
                "steps": [{"title":"New step","image_urls":["https://images.example.test/ignored-step.png"]}]
            }),
        ))
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    assert!(harness.fetcher.requested.lock().unwrap().is_empty());
}

#[tokio::test]
async fn production_fetcher_rejects_loopback_before_connecting() {
    let error = HttpImageFetcher
        .fetch(&Url::parse("http://127.0.0.1/image.png").unwrap())
        .await
        .unwrap_err();
    assert_eq!(error, FetchError::UnsafeAddress);
}

#[tokio::test]
async fn yarn_create_attaches_selected_photos_with_the_first_as_primary() {
    let harness = harness(true).await;
    harness
        .fetcher
        .responses
        .lock()
        .unwrap()
        .extend(HashMap::from([
            (
                "https://shop.example.test/air-1.png".to_owned(),
                fixture_image(image_bytes(9, 40, 70)),
            ),
            (
                "https://shop.example.test/air-2.png".to_owned(),
                fixture_image(image_bytes(90, 140, 7)),
            ),
        ]));
    let response = harness
        .app
        .clone()
        .oneshot(request(
            "POST",
            "/api/v1/yarns",
            serde_json::json!({
                "name": "DROPS Air",
                "brand": "DROPS Design",
                "weight_grams": 50,
                "length_meters": 150,
                "image_urls": [
                    "https://shop.example.test/air-1.png",
                    "https://shop.example.test/missing.png",
                    "https://shop.example.test/air-2.png"
                ]
            }),
        ))
        .await
        .expect("API should respond");
    assert_eq!(response.status(), StatusCode::CREATED);
    let yarn = json_body(response).await;
    let yarn_id = yarn["id"].as_i64().expect("yarn ID should be numeric");
    let rows: Vec<(String, i64, i64, i64)> = sqlx::query_as(
        "SELECT kind, owner_id, is_primary, position FROM media_files WHERE yarn_id = ? ORDER BY position, id",
    )
    .bind(yarn_id)
    .fetch_all(&harness.pool)
    .await
    .expect("media rows should be queryable");
    assert_eq!(rows.len(), 2, "the unreachable URL is skipped");
    assert_eq!(rows[0], ("photo".to_owned(), harness.user_id, 1, 0));
    assert_eq!(rows[1].2, 0);
}

#[tokio::test]
async fn yarn_create_without_external_imports_ignores_image_urls() {
    let harness = harness(false).await;
    let response = harness
        .app
        .clone()
        .oneshot(request(
            "POST",
            "/api/v1/yarns",
            serde_json::json!({"name": "Plain", "image_urls": ["https://shop.example.test/a.png"]}),
        ))
        .await
        .expect("API should respond");
    assert_eq!(response.status(), StatusCode::CREATED);
    assert!(harness.fetcher.requested.lock().unwrap().is_empty());
}

#[tokio::test]
async fn create_stores_diagram_and_pdf_assets_as_labelled_attachments() {
    let harness = harness(true).await;
    let pdf = b"%PDF-1.4\n1 0 obj\n<<>>\nendobj\ntrailer\n<<>>\n%%EOF\n".to_vec();
    harness
        .fetcher
        .responses
        .lock()
        .unwrap()
        .extend(HashMap::from([
            (
                "https://images.example.test/drops/22-chart.jpg".to_owned(),
                // The server may mislabel the type; the bytes decide.
                FetchedImage {
                    content_type: Some("application/octet-stream".to_owned()),
                    bytes: image_bytes(9, 9, 9),
                },
            ),
            (
                "https://images.example.test/files/pattern.pdf".to_owned(),
                FetchedImage {
                    content_type: Some("application/pdf".to_owned()),
                    bytes: pdf,
                },
            ),
            (
                "https://images.example.test/files/fake.pdf".to_owned(),
                FetchedImage {
                    content_type: Some("application/pdf".to_owned()),
                    bytes: b"<html>not a pdf</html>".to_vec(),
                },
            ),
        ]));
    let response = harness
        .app
        .clone()
        .oneshot(request(
            "POST",
            "/api/v1/projects",
            serde_json::json!({
                "name": "Wave sweater",
                "attachment_urls": [
                    {"url": "https://images.example.test/drops/22-chart.jpg",
                     "kind": "diagram", "label": "Diagram for DROPS 273-22"},
                    {"url": "https://images.example.test/files/pattern.pdf", "kind": "pdf"},
                    {"url": "https://images.example.test/files/fake.pdf", "kind": "pdf"},
                    {"url": "https://images.example.test/missing.jpg", "kind": "diagram"}
                ]
            }),
        ))
        .await
        .expect("API should respond");
    assert_eq!(response.status(), StatusCode::CREATED);
    let project = json_body(response).await;
    let names: Vec<String> = sqlx::query_scalar(
        "SELECT original_filename FROM media_files \
         WHERE project_id = ? AND kind = 'attachment' ORDER BY id",
    )
    .bind(project["id"].as_i64().unwrap())
    .fetch_all(&harness.pool)
    .await
    .unwrap();
    assert_eq!(names, vec!["Diagram for DROPS 273-22.png", "pattern.pdf"]);
    let attachments = project["attachments"].as_array().unwrap();
    assert_eq!(attachments.len(), 2);
    assert_eq!(attachments[0]["content_type"], "image/png");
    assert_eq!(attachments[1]["content_type"], "application/pdf");
    assert_eq!(project["images"].as_array().unwrap().len(), 0);
    let _ = harness.user_id;
}
