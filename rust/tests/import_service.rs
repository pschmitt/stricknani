use std::{
    future::Future,
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
}

impl AiTransport for FixtureAiTransport {
    fn post<'a>(
        &'a self,
        _endpoint: &'a url::Url,
        _api_key: &'a ApiKey,
        _body: &'a Value,
    ) -> PostFuture<'a> {
        Box::pin(async move {
            *self.calls.lock().expect("fixture counter lock") += 1;
            Ok(serde_json::json!({
                "choices": [{"message": {"content": "{\"name\":\"AI improved title\",\"link\":\"https://untrusted.example/\"}"}}]
            }))
        })
    }
}

fn fixture_ai(calls: Arc<Mutex<usize>>) -> AiClient {
    let config = AiConfig::from_lookup(true, |key| match key {
        "AI_API_KEY" => Some("fixture-key".to_owned()),
        _ => None,
    })
    .expect("fixture AI config is valid")
    .expect("AI config is enabled");
    AiClient::with_transport(config, Arc::new(FixtureAiTransport { calls }))
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
    let (app, _) = setup_with_ai(Some(fixture_ai(calls.clone()))).await;
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
}
