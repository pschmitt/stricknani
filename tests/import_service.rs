use std::{
    future::Future,
    io::Cursor,
    path::PathBuf,
    pin::Pin,
    sync::{Arc, Mutex},
};

use axum::{
    Router,
    body::Body,
    http::{Request, StatusCode},
};
use http_body_util::BodyExt;
use scraper::{Html, Selector};
use serde_json::Value;
use sha2::{Digest, Sha256};
use sqlx::SqlitePool;
use stricknani_server::{
    ai::{AiClient, AiConfig, AiError, AiTransport, ApiKey},
    auth, categories, db, import_service, projects, yarns,
};
use tower::ServiceExt;

const BROWSER_TOKEN: &str = "import-browser-fixture-token";

type PostFuture<'a> = Pin<Box<dyn Future<Output = Result<Value, AiError>> + Send + 'a>>;

struct FixtureAiTransport {
    calls: Arc<Mutex<usize>>,
    requests: Arc<Mutex<Vec<Value>>>,
}

impl AiTransport for FixtureAiTransport {
    fn post<'a>(
        &'a self,
        _endpoint: &'a url::Url,
        _api_key: &'a ApiKey,
        body: &'a Value,
    ) -> PostFuture<'a> {
        Box::pin(async move {
            *self.calls.lock().expect("fixture counter lock") += 1;
            self.requests
                .lock()
                .expect("fixture request lock")
                .push(body.clone());
            Ok(serde_json::json!({
                "choices": [{"message": {"content": "{\"name\":\"AI improved title\",\"yarn\":\"Merino wool\",\"link\":\"https://untrusted.example/\"}"}}]
            }))
        })
    }
}

fn fixture_ai(calls: Arc<Mutex<usize>>, requests: Arc<Mutex<Vec<Value>>>) -> AiClient {
    let config = AiConfig::from_lookup(true, |key| match key {
        "AI_API_KEY" => Some("fixture-key".to_owned()),
        _ => None,
    })
    .expect("fixture AI config is valid")
    .expect("AI config is enabled");
    AiClient::with_transport(config, Arc::new(FixtureAiTransport { calls, requests }))
}

async fn setup() -> (axum::Router, SqlitePool) {
    setup_with_ai(None).await
}

async fn setup_with_ai(ai: Option<AiClient>) -> (axum::Router, SqlitePool) {
    let pool = db::connect("sqlite::memory:")
        .await
        .expect("test database migrates");
    let user_id =
        sqlx::query("INSERT INTO users (email, password_hash) VALUES (?, 'fixture-hash')")
            .bind("importer@example.test")
            .execute(&pool)
            .await
            .expect("test user inserts")
            .last_insert_rowid();
    let token_hash = hex::encode(Sha256::digest(BROWSER_TOKEN.as_bytes()));
    sqlx::query(
        "INSERT INTO api_tokens (user_id, name, token_hash, created_at, purpose) \
         VALUES (?, 'browser fixture', ?, unixepoch(), 'browser')",
    )
    .bind(user_id)
    .bind(token_hash)
    .execute(&pool)
    .await
    .expect("browser session fixture inserts");

    let state = db::AppState::new(pool.clone());
    let api = Router::new().nest(
        "/api/v1",
        auth::router()
            .merge(categories::router())
            .merge(yarns::router())
            .merge(projects::router())
            .with_state(state.clone()),
    );
    let app = import_service::router_with_ai(
        api,
        state,
        PathBuf::from(env!("CARGO_BIN_EXE_stricknani-server")),
        false,
        ai,
    );
    (app, pool)
}

fn valid_csrf() -> String {
    let mut hash = Sha256::new();
    hash.update(b"stricknani browser csrf v1\0");
    hash.update(BROWSER_TOKEN.as_bytes());
    hex::encode(hash.finalize())
}

fn form_body(pairs: &[(&str, &str)]) -> String {
    let mut serializer = url::form_urlencoded::Serializer::new(String::new());
    serializer.extend_pairs(pairs.iter().copied());
    serializer.finish()
}

fn png_bytes() -> Vec<u8> {
    let image = image::DynamicImage::new_rgba8(2, 2);
    let mut output = Cursor::new(Vec::new());
    image
        .write_to(&mut output, image::ImageFormat::Png)
        .expect("fixture PNG encodes");
    output.into_inner()
}

fn image_preview_request(token: &str, csrf: &str) -> Request<Body> {
    let boundary = "ai-image-boundary";
    let mut body = format!(
        "--{boundary}\r\nContent-Disposition: form-data; name=\"csrf\"\r\n\r\n{csrf}\r\n\
         --{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"pattern.png\"\r\nContent-Type: image/jpeg\r\n\r\n"
    )
    .into_bytes();
    body.extend_from_slice(&png_bytes());
    body.extend_from_slice(
        format!(
            "\r\n--{boundary}\r\nContent-Disposition: form-data; name=\"use_ai\"\r\n\r\ntrue\r\n--{boundary}--\r\n"
        )
        .as_bytes(),
    );
    Request::post("/imports/preview-file")
        .header("cookie", format!("stricknani_session={token}; language=en"))
        .header(
            "content-type",
            format!("multipart/form-data; boundary={boundary}"),
        )
        .body(Body::from(body))
        .expect("multipart request builds")
}

async fn html_body(response: axum::response::Response) -> String {
    String::from_utf8(
        response
            .into_body()
            .collect()
            .await
            .expect("response body reads")
            .to_bytes()
            .to_vec(),
    )
    .expect("HTML is UTF-8")
}

fn hidden_value(html: &str, selector: &str) -> String {
    let document = Html::parse_document(html);
    document
        .select(&Selector::parse(selector).expect("fixture selector is valid"))
        .next()
        .and_then(|element| element.value().attr("value"))
        .expect("hidden form field exists")
        .to_owned()
}

#[tokio::test]
async fn url_import_is_bearer_only_and_disabled_without_making_a_request() {
    let (app, _) = setup().await;
    let no_auth = app
        .clone()
        .oneshot(
            Request::post("/projects/import")
                .header("content-type", "application/x-www-form-urlencoded")
                .body(Body::from("type=url&url=https%3A%2F%2Fpatterns.example%2F"))
                .expect("request builds"),
        )
        .await
        .expect("router responds");
    assert_eq!(no_auth.status(), StatusCode::UNAUTHORIZED);

    let cookie_only = app
        .clone()
        .oneshot(
            Request::post("/projects/import")
                .header("content-type", "application/x-www-form-urlencoded")
                .header("cookie", format!("stricknani_session={BROWSER_TOKEN}"))
                .body(Body::from("type=url&url=https%3A%2F%2Fpatterns.example%2F"))
                .expect("request builds"),
        )
        .await
        .expect("router responds");
    assert_eq!(cookie_only.status(), StatusCode::UNAUTHORIZED);

    let disabled = app
        .oneshot(
            Request::post("/projects/import")
                .header("content-type", "application/x-www-form-urlencoded")
                .header("authorization", format!("Bearer {BROWSER_TOKEN}"))
                .body(Body::from("type=url&url=https%3A%2F%2Fpatterns.example%2F"))
                .expect("request builds"),
        )
        .await
        .expect("router responds");
    assert_eq!(disabled.status(), StatusCode::SERVICE_UNAVAILABLE);
}

#[tokio::test]
async fn browser_text_import_checks_csrf_then_previews_and_creates_through_api() {
    let (app, pool) = setup().await;
    let invalid = app
        .clone()
        .oneshot(
            Request::post("/imports/preview")
                .header("content-type", "application/x-www-form-urlencoded")
                .header(
                    "cookie",
                    format!("stricknani_session={BROWSER_TOKEN}; language=en"),
                )
                .body(Body::from(form_body(&[
                    ("csrf", "invalid"),
                    ("type", "text"),
                    ("text", "A warm striped scarf."),
                ])))
                .expect("request builds"),
        )
        .await
        .expect("router responds");
    assert_eq!(invalid.status(), StatusCode::FORBIDDEN);

    let csrf_value = valid_csrf();
    let preview = app
        .clone()
        .oneshot(
            Request::post("/imports/preview")
                .header("content-type", "application/x-www-form-urlencoded")
                .header(
                    "cookie",
                    format!("stricknani_session={BROWSER_TOKEN}; language=en"),
                )
                .body(Body::from(form_body(&[
                    ("csrf", &csrf_value),
                    ("type", "text"),
                    ("text", "A warm striped scarf."),
                ])))
                .expect("request builds"),
        )
        .await
        .expect("router responds");
    assert_eq!(preview.status(), StatusCode::OK);
    let html = html_body(preview).await;
    assert!(html.contains("A warm striped scarf."));
    let draft_json = hidden_value(
        &html,
        "form[action='/imports/create-project'] input[name='draft_json']",
    );
    let csrf = hidden_value(
        &html,
        "form[action='/imports/create-project'] input[name='csrf']",
    );
    let review_name = hidden_value(
        &html,
        "form[action='/imports/create-project'] input[name='name']",
    );
    let body = form_body(&[
        ("csrf", &csrf),
        ("draft_json", &draft_json),
        ("name", &review_name),
        ("description", "A reviewed pattern description."),
        ("category", ""),
        ("needles", ""),
        ("stitch_sample", ""),
        ("other_materials", ""),
        ("yarn", ""),
        ("link", ""),
    ]);
    let created = app
        .oneshot(
            Request::post("/imports/create-project")
                .header("content-type", "application/x-www-form-urlencoded")
                .header(
                    "cookie",
                    format!("stricknani_session={BROWSER_TOKEN}; language=en"),
                )
                .body(Body::from(body))
                .expect("request builds"),
        )
        .await
        .expect("router responds");
    assert_eq!(created.status(), StatusCode::SEE_OTHER);
    let location = created
        .headers()
        .get("location")
        .and_then(|value| value.to_str().ok())
        .expect("created project redirects");
    assert!(location.starts_with("/projects/"));
    let count: i64 = sqlx::query_scalar("SELECT count(*) FROM projects")
        .fetch_one(&pool)
        .await
        .expect("project count reads");
    assert_eq!(count, 1);
    let payload: Value = serde_json::from_str(&draft_json).expect("preview draft is JSON");
    assert_eq!(payload["project"]["description"], "A warm striped scarf.");
}

#[tokio::test]
async fn browser_ai_preview_calls_provider_only_after_explicit_opt_in() {
    let calls = Arc::new(Mutex::new(0));
    let requests = Arc::new(Mutex::new(Vec::new()));
    let (app, pool) = setup_with_ai(Some(fixture_ai(calls.clone(), requests.clone()))).await;
    let csrf = valid_csrf();
    let cookie = format!("stricknani_session={BROWSER_TOKEN}; language=en");

    let unchecked = app
        .clone()
        .oneshot(
            Request::post("/imports/preview")
                .header("content-type", "application/x-www-form-urlencoded")
                .header("cookie", cookie.clone())
                .body(Body::from(form_body(&[
                    ("csrf", &csrf),
                    ("type", "text"),
                    ("text", "A warm striped scarf."),
                ])))
                .expect("request builds"),
        )
        .await
        .expect("router responds");
    assert_eq!(unchecked.status(), StatusCode::OK);
    assert_eq!(*calls.lock().expect("fixture counter lock"), 0);

    let opted_in = app
        .clone()
        .oneshot(
            Request::post("/imports/preview")
                .header("content-type", "application/x-www-form-urlencoded")
                .header("cookie", cookie)
                .body(Body::from(form_body(&[
                    ("csrf", &csrf),
                    ("type", "text"),
                    ("text", "A warm striped scarf."),
                    ("use_ai", "true"),
                ])))
                .expect("request builds"),
        )
        .await
        .expect("router responds");
    assert_eq!(opted_in.status(), StatusCode::OK);
    assert_eq!(*calls.lock().expect("fixture counter lock"), 1);
    let html = html_body(opted_in).await;
    let draft: Value = serde_json::from_str(&hidden_value(
        &html,
        "form[action='/imports/create-project'] input[name='draft_json']",
    ))
    .expect("preview draft is JSON");
    assert_eq!(draft["project"]["name"], "AI improved title");
    assert_ne!(draft["project"]["link"], "https://untrusted.example/");

    let project_marker = hidden_value(
        &html,
        "form[action='/imports/create-project'] input[name='is_ai_enhanced']",
    );
    let yarn_marker = hidden_value(
        &html,
        "form[action='/imports/create-yarn'] input[name='is_ai_enhanced']",
    );
    assert_eq!(project_marker, "true");
    assert_eq!(yarn_marker, "true");

    let create_project = app
        .clone()
        .oneshot(
            Request::post("/imports/create-project")
                .header("content-type", "application/x-www-form-urlencoded")
                .header(
                    "cookie",
                    format!("stricknani_session={BROWSER_TOKEN}; language=en"),
                )
                .body(Body::from(form_body(&[
                    (
                        "csrf",
                        &hidden_value(
                            &html,
                            "form[action='/imports/create-project'] input[name='csrf']",
                        ),
                    ),
                    (
                        "draft_json",
                        &hidden_value(
                            &html,
                            "form[action='/imports/create-project'] input[name='draft_json']",
                        ),
                    ),
                    ("is_ai_enhanced", &project_marker),
                    (
                        "name",
                        &hidden_value(
                            &html,
                            "form[action='/imports/create-project'] input[name='name']",
                        ),
                    ),
                    ("description", "A reviewed pattern description."),
                    ("category", ""),
                    ("needles", ""),
                    ("stitch_sample", ""),
                    ("other_materials", ""),
                    ("yarn", "Merino wool"),
                    ("link", ""),
                ])))
                .expect("request builds"),
        )
        .await
        .expect("router responds");
    assert_eq!(create_project.status(), StatusCode::SEE_OTHER);
    let enhanced_projects: i64 = sqlx::query_scalar("SELECT is_ai_enhanced FROM projects")
        .fetch_one(&pool)
        .await
        .expect("created project flag reads");
    assert_eq!(enhanced_projects, 1);

    let create_yarn = app
        .oneshot(
            Request::post("/imports/create-yarn")
                .header("content-type", "application/x-www-form-urlencoded")
                .header(
                    "cookie",
                    format!("stricknani_session={BROWSER_TOKEN}; language=en"),
                )
                .body(Body::from(form_body(&[
                    (
                        "csrf",
                        &hidden_value(
                            &html,
                            "form[action='/imports/create-yarn'] input[name='csrf']",
                        ),
                    ),
                    (
                        "draft_json",
                        &hidden_value(
                            &html,
                            "form[action='/imports/create-yarn'] input[name='draft_json']",
                        ),
                    ),
                    ("is_ai_enhanced", &yarn_marker),
                    ("yarn_name", "Merino wool"),
                    ("yarn_brand", ""),
                    ("yarn_colorway", ""),
                    ("yarn_weight", ""),
                    ("yarn_length", ""),
                    ("yarn_weight_category", ""),
                    ("yarn_fiber_content", ""),
                    ("yarn_link", ""),
                ])))
                .expect("request builds"),
        )
        .await
        .expect("router responds");
    assert_eq!(create_yarn.status(), StatusCode::SEE_OTHER);
    let enhanced_yarns: i64 = sqlx::query_scalar("SELECT is_ai_enhanced FROM yarns")
        .fetch_one(&pool)
        .await
        .expect("created yarn flag reads");
    assert_eq!(enhanced_yarns, 1);
    assert_eq!(*calls.lock().expect("fixture counter lock"), 1);
}

#[tokio::test]
async fn browser_ai_preview_bounds_unicode_source_text_before_calling_provider() {
    let calls = Arc::new(Mutex::new(0));
    let requests = Arc::new(Mutex::new(Vec::new()));
    let (app, _) = setup_with_ai(Some(fixture_ai(calls.clone(), requests.clone()))).await;
    let long_text = "ä".repeat(13_000);
    let response = app
        .oneshot(
            Request::post("/imports/preview")
                .header("content-type", "application/x-www-form-urlencoded")
                .header(
                    "cookie",
                    format!("stricknani_session={BROWSER_TOKEN}; language=en"),
                )
                .body(Body::from(form_body(&[
                    ("csrf", &valid_csrf()),
                    ("type", "text"),
                    ("text", &long_text),
                    ("use_ai", "true"),
                ])))
                .expect("request builds"),
        )
        .await
        .expect("router responds");
    assert_eq!(response.status(), StatusCode::OK);
    assert_eq!(*calls.lock().expect("fixture counter lock"), 1);

    let requests = requests.lock().expect("fixture request lock");
    let prompt = requests[0]["messages"][1]["content"][0]["text"]
        .as_str()
        .expect("AI user prompt contains text");
    let supplied_source = prompt
        .split_once("Source text:\n")
        .expect("prompt includes source text")
        .1
        .split_once("\n\nExisting parsed draft JSON:")
        .expect("prompt separates source and draft")
        .0;
    assert_eq!(supplied_source.chars().count(), 12_000);
    assert!(supplied_source.chars().all(|character| character == 'ä'));
}

#[tokio::test]
async fn browser_file_ai_uses_parsed_image_format_not_claimed_mime_type() {
    let calls = Arc::new(Mutex::new(0));
    let requests = Arc::new(Mutex::new(Vec::new()));
    let (app, _) = setup_with_ai(Some(fixture_ai(calls.clone(), requests.clone()))).await;
    let response = app
        .oneshot(image_preview_request(BROWSER_TOKEN, &valid_csrf()))
        .await
        .expect("router responds");

    assert_eq!(response.status(), StatusCode::OK);
    assert_eq!(*calls.lock().expect("fixture counter lock"), 1);
    let requests = requests.lock().expect("fixture request lock");
    let image_url = requests[0]["messages"][1]["content"][1]["image_url"]["url"]
        .as_str()
        .expect("AI request has image data URL");
    assert!(image_url.starts_with("data:image/png;base64,"));
}
