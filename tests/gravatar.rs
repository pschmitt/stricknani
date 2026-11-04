//! Server-side Gravatar fallback: hashing, caching, upload precedence, the
//! per-user and global switches, limits and access control.

use std::{
    collections::HashMap,
    io::Cursor,
    sync::{
        Arc, Mutex,
        atomic::{AtomicUsize, Ordering},
    },
    time::Duration,
};

use axum::{
    Router,
    body::Body,
    extract::{Path, RawQuery, State},
    http::{Request, StatusCode, header},
    response::{IntoResponse, Response},
    routing::get,
};
use http_body_util::BodyExt;
use sha2::{Digest, Sha256};
use sqlx::SqlitePool;
use stricknani_server::{api_v2, db, gravatar, media};
use tower::ServiceExt;

#[derive(Clone)]
enum Behavior {
    Image(Vec<u8>, &'static str),
    NotFound,
    Slow,
    Redirect(String),
}

#[derive(Default)]
struct MockState {
    behaviors: Mutex<HashMap<String, Behavior>>,
    default: Mutex<Option<Behavior>>,
    hits: AtomicUsize,
    seen: Mutex<Vec<(String, Option<String>)>>,
}

struct Mock {
    state: Arc<MockState>,
    base_url: String,
}

impl Mock {
    async fn start(default: Behavior) -> Self {
        let state = Arc::new(MockState {
            default: Mutex::new(Some(default)),
            ..MockState::default()
        });
        let app = Router::new()
            .route("/avatar/{hash}", get(mock_avatar))
            .with_state(state.clone());
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let addr = listener.local_addr().unwrap();
        tokio::spawn(async move { axum::serve(listener, app).await.unwrap() });
        Self {
            state,
            base_url: format!("http://{addr}/avatar"),
        }
    }

    fn hits(&self) -> usize {
        self.state.hits.load(Ordering::SeqCst)
    }
}

async fn mock_avatar(
    State(state): State<Arc<MockState>>,
    Path(hash): Path<String>,
    RawQuery(query): RawQuery,
) -> Response {
    state.hits.fetch_add(1, Ordering::SeqCst);
    state.seen.lock().unwrap().push((hash.clone(), query));
    let behavior = state
        .behaviors
        .lock()
        .unwrap()
        .get(&hash)
        .cloned()
        .or_else(|| state.default.lock().unwrap().clone())
        .unwrap();
    match behavior {
        Behavior::Image(bytes, content_type) => {
            ([(header::CONTENT_TYPE, content_type)], bytes).into_response()
        }
        Behavior::NotFound => StatusCode::NOT_FOUND.into_response(),
        Behavior::Slow => {
            tokio::time::sleep(Duration::from_secs(5)).await;
            StatusCode::OK.into_response()
        }
        Behavior::Redirect(location) => (
            StatusCode::FOUND,
            [(header::LOCATION, location)],
            Body::empty(),
        )
            .into_response(),
    }
}

fn png_bytes() -> Vec<u8> {
    let mut output = Cursor::new(Vec::new());
    image::DynamicImage::new_rgba8(2, 2)
        .write_to(&mut output, image::ImageFormat::Png)
        .unwrap();
    output.into_inner()
}

struct Fixture {
    app: Router,
    pool: SqlitePool,
    media_root: tempfile::TempDir,
    user_id: i64,
    other_id: i64,
}

const USER_PAT: &str = "gravatar-user-pat";
const OTHER_PAT: &str = "gravatar-other-pat";
const ADMIN_PAT: &str = "gravatar-admin-pat";

async fn add_pat(pool: &SqlitePool, user_id: i64, secret: &str) {
    sqlx::query(
        "INSERT INTO api_tokens (user_id, name, token_hash, purpose, created_at) \
         VALUES (?, 'fixture', ?, 'api', unixepoch())",
    )
    .bind(user_id)
    .bind(hex::encode(Sha256::digest(secret.as_bytes())))
    .execute(pool)
    .await
    .unwrap();
}

async fn fixture(settings: gravatar::GravatarSettings) -> Fixture {
    let pool = db::connect("sqlite::memory:").await.unwrap();
    let media_root = tempfile::tempdir().unwrap();
    let user_id = db::create_user(&pool, "alice@example.test", "alice-password-long".into())
        .await
        .unwrap();
    // Gravatar normalizes: the stored address is mixed-case with whitespace.
    sqlx::query("UPDATE users SET email = '  Alice@Example.TEST ' WHERE id = ?")
        .bind(user_id)
        .execute(&pool)
        .await
        .unwrap();
    let other_id = db::create_user(&pool, "bob@example.test", "bob-password-long".into())
        .await
        .unwrap();
    let admin_id = db::create_user(&pool, "admin@example.test", "admin-password-long".into())
        .await
        .unwrap();
    sqlx::query("UPDATE users SET is_admin = 1 WHERE id = ?")
        .bind(admin_id)
        .execute(&pool)
        .await
        .unwrap();
    add_pat(&pool, user_id, USER_PAT).await;
    add_pat(&pool, other_id, OTHER_PAT).await;
    add_pat(&pool, admin_id, ADMIN_PAT).await;
    let state = db::AppState::new(pool.clone())
        .with_secure_cookies(true)
        .with_media_root(media_root.path().to_path_buf())
        .with_gravatar(settings);
    let app = api_v2::router(state.clone(), false).merge(media::router().with_state(state));
    Fixture {
        app,
        pool,
        media_root,
        user_id,
        other_id,
    }
}

fn settings(mock: &Mock) -> gravatar::GravatarSettings {
    gravatar::GravatarSettings::enabled(true).with_base_url(&mock.base_url)
}

fn req(method: &str, path: &str, token: &str, body: Option<serde_json::Value>) -> Request<Body> {
    let mut builder = Request::builder()
        .method(method)
        .uri(path)
        .header("authorization", format!("Bearer {token}"));
    if body.is_some() {
        builder = builder.header("content-type", "application/json");
    }
    builder
        .body(body.map_or_else(Body::empty, |b| Body::from(b.to_string())))
        .unwrap()
}

async fn send(f: &Fixture, request: Request<Body>) -> Response {
    f.app.clone().oneshot(request).await.unwrap()
}

async fn json(response: Response) -> serde_json::Value {
    let bytes = response.into_body().collect().await.unwrap().to_bytes();
    serde_json::from_slice(&bytes).unwrap_or(serde_json::Value::Null)
}

async fn account(f: &Fixture) -> serde_json::Value {
    json(send(f, req("GET", "/account", USER_PAT, None)).await).await["data"].clone()
}

fn route(user_id: i64) -> String {
    format!("/media/thumbnails/users/{user_id}/gravatar")
}

fn upload(token: &str) -> Request<Body> {
    let boundary = "gravatar-boundary";
    let mut body = format!(
        "--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"p.png\"\r\nContent-Type: image/png\r\n\r\n"
    )
    .into_bytes();
    body.extend_from_slice(&png_bytes());
    body.extend_from_slice(format!("\r\n--{boundary}--\r\n").as_bytes());
    Request::post("/account/avatar")
        .header("authorization", format!("Bearer {token}"))
        .header(
            "content-type",
            format!("multipart/form-data; boundary={boundary}"),
        )
        .body(Body::from(body))
        .unwrap()
}

#[test]
fn hash_is_sha256_of_the_trimmed_lowercased_address() {
    // sha256("alice@example.test")
    let expected = "69b1145a03334875161ea18c1373b5703aeeff144990a9f847867ff83ed5aaad";
    assert_eq!(gravatar::email_hash("alice@example.test"), expected);
    assert_eq!(gravatar::email_hash("  Alice@Example.TEST \n"), expected);
}

#[tokio::test]
async fn serves_the_fetched_gravatar_and_caches_it() {
    let mock = Mock::start(Behavior::Image(png_bytes(), "image/png")).await;
    let f = fixture(settings(&mock)).await;

    let data = account(&f).await;
    assert_eq!(data["avatar_source"], "gravatar");
    assert_eq!(data["use_gravatar"], true);
    assert_eq!(data["gravatar_enabled"], true);
    assert_eq!(data["avatar_url"], route(f.user_id).as_str());
    // The payload never blocks on Gravatar.
    assert_eq!(mock.hits(), 0);

    let meta = json(send(&f, req("GET", "/meta", USER_PAT, None)).await).await;
    assert_eq!(
        meta["data"]["user"]["avatar_url"],
        route(f.user_id).as_str()
    );
    assert_eq!(meta["data"]["user"]["avatar_source"], "gravatar");

    for _ in 0..2 {
        let response = send(&f, req("GET", &route(f.user_id), USER_PAT, None)).await;
        assert_eq!(response.status(), StatusCode::OK);
        assert_eq!(response.headers()["content-type"], "image/png");
        assert_eq!(response.headers()["x-content-type-options"], "nosniff");
        let bytes = response.into_body().collect().await.unwrap().to_bytes();
        assert_eq!(bytes.as_ref(), png_bytes().as_slice());
    }
    assert_eq!(mock.hits(), 1, "second request must come from the cache");
    let seen = mock.state.seen.lock().unwrap().clone();
    assert_eq!(
        seen,
        vec![(
            gravatar::email_hash("alice@example.test"),
            Some("s=256&d=404".to_owned())
        )]
    );
    assert!(
        f.media_root
            .path()
            .join("gravatar")
            .join(format!(
                "{}.png",
                gravatar::email_hash("alice@example.test")
            ))
            .exists()
    );
}

#[tokio::test]
async fn an_uploaded_avatar_always_wins() {
    let mock = Mock::start(Behavior::Image(png_bytes(), "image/png")).await;
    let f = fixture(settings(&mock)).await;
    let uploaded = send(&f, upload(USER_PAT)).await;
    assert_eq!(uploaded.status(), StatusCode::OK);
    let upload_url = json(uploaded).await["data"]["avatar_url"]
        .as_str()
        .unwrap()
        .to_owned();

    let data = account(&f).await;
    assert_eq!(data["avatar_source"], "upload");
    assert_eq!(data["avatar_url"], upload_url.as_str());
    let stale = send(&f, req("GET", &route(f.user_id), USER_PAT, None)).await;
    assert_eq!(stale.status(), StatusCode::NOT_FOUND);
    assert_eq!(mock.hits(), 0);

    // Removing the upload falls back to the Gravatar.
    let deleted = send(&f, req("DELETE", "/account/avatar", USER_PAT, None)).await;
    assert_eq!(deleted.status(), StatusCode::NO_CONTENT);
    assert_eq!(account(&f).await["avatar_source"], "gravatar");
}

#[tokio::test]
async fn a_404_is_negatively_cached_and_clients_see_no_avatar() {
    let mock = Mock::start(Behavior::NotFound).await;
    let f = fixture(settings(&mock)).await;
    for _ in 0..3 {
        let response = send(&f, req("GET", &route(f.user_id), USER_PAT, None)).await;
        assert_eq!(response.status(), StatusCode::NOT_FOUND);
    }
    assert_eq!(mock.hits(), 1);
}

#[tokio::test]
async fn expired_entries_are_refreshed() {
    let mock = Mock::start(Behavior::Image(png_bytes(), "image/png")).await;
    let f = fixture(settings(&mock).with_ttl(Duration::ZERO)).await;
    for _ in 0..2 {
        let response = send(&f, req("GET", &route(f.user_id), USER_PAT, None)).await;
        assert_eq!(response.status(), StatusCode::OK);
    }
    assert_eq!(mock.hits(), 2);
}

#[tokio::test]
async fn slow_responses_time_out_without_erroring() {
    let mock = Mock::start(Behavior::Slow).await;
    let f = fixture(settings(&mock).with_timeout(Duration::from_millis(200))).await;
    let response = send(&f, req("GET", &route(f.user_id), USER_PAT, None)).await;
    assert_eq!(response.status(), StatusCode::NOT_FOUND);
    // The failure is remembered briefly instead of retried on every request.
    let again = send(&f, req("GET", &route(f.user_id), USER_PAT, None)).await;
    assert_eq!(again.status(), StatusCode::NOT_FOUND);
    assert_eq!(mock.hits(), 1);
}

#[tokio::test]
async fn oversized_and_non_image_bodies_are_rejected() {
    let mut huge = png_bytes();
    huge.resize(2 * 1024 * 1024, 0);
    let mock = Mock::start(Behavior::Image(huge, "image/png")).await;
    let f = fixture(settings(&mock)).await;
    let response = send(&f, req("GET", &route(f.user_id), USER_PAT, None)).await;
    assert_eq!(response.status(), StatusCode::NOT_FOUND);
    let cached_png = f
        .media_root
        .path()
        .join("gravatar")
        .read_dir()
        .is_ok_and(|entries| {
            entries
                .flatten()
                .any(|entry| entry.file_name().to_string_lossy().ends_with(".png"))
        });
    assert!(!cached_png, "an oversized image must not be cached");

    let html = Mock::start(Behavior::Image(b"<html></html>".to_vec(), "text/html")).await;
    let f = fixture(settings(&html)).await;
    let response = send(&f, req("GET", &route(f.user_id), USER_PAT, None)).await;
    assert_eq!(response.status(), StatusCode::NOT_FOUND);

    // Declared as an image but not one.
    let fake = Mock::start(Behavior::Image(b"not an image".to_vec(), "image/png")).await;
    let f = fixture(settings(&fake)).await;
    let response = send(&f, req("GET", &route(f.user_id), USER_PAT, None)).await;
    assert_eq!(response.status(), StatusCode::NOT_FOUND);
}

#[tokio::test]
async fn off_host_redirects_are_not_followed() {
    let target = Mock::start(Behavior::Image(png_bytes(), "image/png")).await;
    let mock = Mock::start(Behavior::Redirect(format!(
        "{}/somebody",
        target.base_url.replace("127.0.0.1", "localhost")
    )))
    .await;
    let f = fixture(settings(&mock)).await;
    let response = send(&f, req("GET", &route(f.user_id), USER_PAT, None)).await;
    assert_eq!(response.status(), StatusCode::NOT_FOUND);
    assert_eq!(target.hits(), 0);
}

#[tokio::test]
async fn the_per_user_toggle_controls_the_fallback() {
    let mock = Mock::start(Behavior::Image(png_bytes(), "image/png")).await;
    let f = fixture(settings(&mock)).await;

    let off = send(
        &f,
        req(
            "PATCH",
            "/account",
            USER_PAT,
            Some(serde_json::json!({"use_gravatar": false})),
        ),
    )
    .await;
    assert_eq!(off.status(), StatusCode::OK);
    let data = json(off).await["data"].clone();
    assert_eq!(data["use_gravatar"], false);
    assert!(data["avatar_url"].is_null());
    assert!(data["avatar_source"].is_null());
    let stored: i64 = sqlx::query_scalar("SELECT use_gravatar FROM users WHERE id = ?")
        .bind(f.user_id)
        .fetch_one(&f.pool)
        .await
        .unwrap();
    assert_eq!(stored, 0);
    let response = send(&f, req("GET", &route(f.user_id), USER_PAT, None)).await;
    assert_eq!(response.status(), StatusCode::NOT_FOUND);
    assert_eq!(mock.hits(), 0, "an opted-out address is never sent");

    let on = send(
        &f,
        req(
            "PATCH",
            "/account",
            USER_PAT,
            Some(serde_json::json!({"use_gravatar": true})),
        ),
    )
    .await;
    assert_eq!(json(on).await["data"]["avatar_source"], "gravatar");
    let response = send(&f, req("GET", &route(f.user_id), USER_PAT, None)).await;
    assert_eq!(response.status(), StatusCode::OK);

    for body in [
        serde_json::json!({"use_gravatar": "yes"}),
        serde_json::json!({"use_gravatar": true, "email": "x@y.z"}),
    ] {
        let bad = send(&f, req("PATCH", "/account", USER_PAT, Some(body))).await;
        assert!(bad.status().is_client_error());
    }
    let anonymous = send(
        &f,
        Request::patch("/account")
            .header("content-type", "application/json")
            .body(Body::from("{\"use_gravatar\":false}"))
            .unwrap(),
    )
    .await;
    assert!(matches!(
        anonymous.status(),
        StatusCode::UNAUTHORIZED | StatusCode::FORBIDDEN
    ));
}

#[tokio::test]
async fn the_global_switch_disables_everything() {
    let mock = Mock::start(Behavior::Image(png_bytes(), "image/png")).await;
    let f = fixture(gravatar::GravatarSettings::enabled(false).with_base_url(&mock.base_url)).await;
    let data = account(&f).await;
    assert!(data["avatar_url"].is_null());
    assert!(data["avatar_source"].is_null());
    assert_eq!(data["gravatar_enabled"], false);
    let response = send(&f, req("GET", &route(f.user_id), USER_PAT, None)).await;
    assert_eq!(response.status(), StatusCode::NOT_FOUND);
    assert_eq!(mock.hits(), 0);
}

#[tokio::test]
async fn avatar_routes_follow_the_upload_access_rules() {
    let mock = Mock::start(Behavior::Image(png_bytes(), "image/png")).await;
    let f = fixture(settings(&mock)).await;

    // Another member cannot read it, exactly like an uploaded avatar.
    let other = send(&f, req("GET", &route(f.user_id), OTHER_PAT, None)).await;
    assert_eq!(other.status(), StatusCode::FORBIDDEN);
    let anonymous = send(
        &f,
        Request::get(route(f.user_id)).body(Body::empty()).unwrap(),
    )
    .await;
    assert_eq!(anonymous.status(), StatusCode::UNAUTHORIZED);
    assert_eq!(mock.hits(), 0);

    // Administrators read it through the admin path only.
    let admin_path = format!("/admin/users/{}/avatar/gravatar", f.user_id);
    let member = send(&f, req("GET", &admin_path, OTHER_PAT, None)).await;
    assert_eq!(member.status(), StatusCode::FORBIDDEN);
    let admin = send(&f, req("GET", &admin_path, ADMIN_PAT, None)).await;
    assert_eq!(admin.status(), StatusCode::OK);
    assert_eq!(admin.headers()["content-type"], "image/png");

    let users = json(send(&f, req("GET", "/admin/users", ADMIN_PAT, None)).await).await;
    let urls: HashMap<String, Option<String>> = users["data"]
        .as_array()
        .unwrap()
        .iter()
        .map(|user| {
            (
                user["email"].as_str().unwrap().trim().to_lowercase(),
                user["avatar_url"].as_str().map(str::to_owned),
            )
        })
        .collect();
    assert_eq!(
        urls["alice@example.test"].as_deref(),
        Some(format!("/api/v2/admin/users/{}/avatar/gravatar", f.user_id).as_str())
    );
    assert!(urls["bob@example.test"].is_some());
    let _ = f.other_id;
}
