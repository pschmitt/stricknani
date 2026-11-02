pub(crate) mod account_service;
pub(crate) mod admin_service;
pub mod ai;
pub mod api_v2;
pub mod archive_jobs;
pub mod archive_service;
mod audit;
pub(crate) mod audit_service;
pub mod auth;
pub mod categories;
pub mod config;
pub mod db;
pub mod document_worker;
pub mod frontend;
mod i18n;
pub mod image_import;
pub mod import_document;
pub mod import_service;
pub mod importer;
pub mod imports;
pub mod media;
pub mod media_ids;
pub mod ocr;
pub mod ocr_service;
pub mod projects;
pub mod pwa;
pub mod security;
pub mod sync;
pub mod wayback;
mod web;
mod web_accounts;
mod web_audit;
mod web_signup;
pub mod yarns;
use std::num::NonZeroU32;
use std::path::PathBuf;
use std::sync::Arc;

use askama::Template;
use axum::extract::rejection::FormRejection;
use axum::extract::{Form, OriginalUri, Path};
use axum::http::{HeaderMap, StatusCode};
use axum::response::{Html, IntoResponse, Redirect, Response};
use axum::routing::{get, post};
use axum::{Json, Router};
use serde::{Deserialize, Serialize};
use tower_http::services::ServeDir;

mod domain;
mod time;

use crate::domain::gauge::{GaugeInput, GaugeResult};

pub fn app() -> Router {
    let static_root = std::env::var_os("STATIC_ROOT")
        .map(PathBuf::from)
        .unwrap_or_else(|| PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../stricknani/static"));
    app_with_static_root(static_root).layer(axum::middleware::from_fn(security::security_headers))
}

pub fn app_with_static_root(static_root: PathBuf) -> Router {
    app_with_static_root_and_web_root(static_root, configured_web_root())
}

fn configured_web_root() -> Option<PathBuf> {
    std::env::var_os("WEB_ROOT").map(PathBuf::from)
}

fn app_with_static_root_and_web_root(static_root: PathBuf, web_root: Option<PathBuf>) -> Router {
    let app = Router::new()
        .route("/healthz", get(healthz))
        .route("/gauge/", get(gauge_page))
        .route("/gauge/calculate", post(calculate))
        .nest_service("/static", ServeDir::new(static_root))
        .merge(pwa::router_with_web_root(web_root.clone()));
    match web_root {
        Some(root) => app.merge(frontend::router(root)),
        None => app,
    }
}

/// Build the persistent candidate while keeping the existing stateless gauge app usable.
pub fn app_with_state(static_root: PathBuf, state: db::AppState) -> Router {
    app_with_imports(static_root, state, PathBuf::new(), false)
}

/// Build the server with explicit document-worker and external-import settings.
pub fn app_with_imports(
    static_root: PathBuf,
    state: db::AppState,
    worker_path: PathBuf,
    external_imports: bool,
) -> Router {
    app_with_imports_and_ai(static_root, state, worker_path, external_imports, None)
}

/// Build optional AI imports with an explicitly configured provider client.
pub fn app_with_imports_and_ai(
    static_root: PathBuf,
    state: db::AppState,
    worker_path: PathBuf,
    external_imports: bool,
    ai: Option<ai::AiClient>,
) -> Router {
    app_with_services(static_root, state, worker_path, external_imports, ai, None)
}

/// Build the candidate with explicitly enabled optional integrations.
pub fn app_with_services(
    static_root: PathBuf,
    state: db::AppState,
    worker_path: PathBuf,
    external_imports: bool,
    ai: Option<ai::AiClient>,
    ocr: Option<ocr::OcrService>,
) -> Router {
    app_with_services_and_web_root(
        static_root,
        state,
        worker_path,
        external_imports,
        ai,
        ocr,
        configured_web_root(),
    )
}

fn app_with_services_and_web_root(
    static_root: PathBuf,
    state: db::AppState,
    worker_path: PathBuf,
    external_imports: bool,
    ai: Option<ai::AiClient>,
    ocr: Option<ocr::OcrService>,
    web_root: Option<PathBuf>,
) -> Router {
    let state = state.with_external_imports(external_imports);
    let ai = ai.map(Arc::new);
    let core = Router::new()
        .nest(
            "/api/v1",
            auth::router()
                .merge(categories::router())
                .merge(yarns::router())
                .merge(projects::router())
                .merge(sync::router())
                .route("/meta", get(meta))
                .with_state(state.clone()),
        )
        .merge(media::router().with_state(state.clone()));

    let mut web_router = if web_root.is_some() {
        web::router_with_landing(core.clone(), state.clone(), true)
    } else {
        web::router(core.clone(), state.clone())
    };
    if web_root.is_some() {
        web_router = web_router.route("/yarn/{id}", get(android_yarn_deep_link));
    }

    app_with_static_root_and_web_root(static_root, web_root.clone())
        .nest(
            "/api/v2",
            api_v2::router_with_optional_services(
                state.clone(),
                worker_path.clone(),
                ai.clone(),
                ocr.clone(),
            ),
        )
        .merge(web_router)
        .merge(web_accounts::router(core.clone(), state.clone()))
        .merge(web_audit::router(core.clone(), state.clone()))
        .merge(web_signup::router(state.clone()))
        .merge(archive_service::router(
            core.clone(),
            state.clone(),
            state.archive_enabled,
        ))
        .merge(ocr_service::router(core.clone(), state.clone(), ocr))
        .merge(import_service::router_with_shared_ai(
            core.clone(),
            state,
            worker_path,
            external_imports,
            ai,
        ))
        .merge(core)
        .layer(axum::middleware::from_fn(security::security_headers))
}

async fn meta(
    axum::extract::State(state): axum::extract::State<db::AppState>,
) -> Json<serde_json::Value> {
    Json(serde_json::json!({ "version": env!("CARGO_PKG_VERSION"), "build_id": state.build_id }))
}

async fn healthz() -> Json<HealthResponse> {
    Json(HealthResponse { status: "ok" })
}

async fn android_yarn_deep_link(Path(raw_id): Path<String>, original_uri: OriginalUri) -> Response {
    if raw_id.is_empty() || !raw_id.bytes().all(|byte| byte.is_ascii_digit()) {
        return StatusCode::NOT_FOUND.into_response();
    }
    let Ok(id) = raw_id.parse::<i32>() else {
        return StatusCode::NOT_FOUND.into_response();
    };

    let mut target = format!("/app/yarns/{id}");
    if let Some(query) = original_uri.0.query() {
        target.push('?');
        target.push_str(query);
    }
    Redirect::permanent(&target).into_response()
}

async fn gauge_page(headers: HeaderMap) -> Response {
    render_page(
        GaugePage::new(
            language_from_headers(&headers),
            GaugeForm::default(),
            None,
            None,
        ),
        StatusCode::OK,
    )
}

async fn calculate(headers: HeaderMap, form: Result<Form<GaugeForm>, FormRejection>) -> Response {
    let language = language_from_headers(&headers);
    let (form, result, error, status) = match form {
        Ok(Form(form)) => match form.calculate(language) {
            Ok(result) => (form, Some(result), None, StatusCode::OK),
            Err(error) => (form, None, Some(error), StatusCode::UNPROCESSABLE_ENTITY),
        },
        Err(_) => (
            GaugeForm::default(),
            None,
            Some(GaugeLabels::for_language(language).invalid_form.to_owned()),
            StatusCode::UNPROCESSABLE_ENTITY,
        ),
    };

    let page = GaugePage::new(language, form, result, error);
    render_page(page, status)
}

#[cfg(test)]
mod frontend_landing_tests {
    use super::*;
    use axum::{body::Body, http::Request};
    use tower::ServiceExt;

    const EMAIL: &str = "landing@example.test";
    const PASSWORD: &str = "test-password-for-landing";

    async fn app(web_root: Option<PathBuf>) -> (Router, db::AppState) {
        let pool = db::connect("sqlite::memory:")
            .await
            .expect("test database should migrate");
        db::create_user(&pool, EMAIL, PASSWORD.to_owned())
            .await
            .expect("test user should be created");
        let state = db::AppState::new(pool).with_secure_cookies(false);
        let app = app_with_services_and_web_root(
            PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../stricknani/static"),
            state.clone(),
            PathBuf::new(),
            false,
            None,
            None,
            web_root,
        );
        (app, state)
    }

    async fn location(app: &Router, path: &str, cookie: Option<&str>) -> (StatusCode, String) {
        let mut request = Request::get(path);
        if let Some(cookie) = cookie {
            request = request.header("cookie", cookie);
        }
        let response = app
            .clone()
            .oneshot(
                request
                    .body(Body::empty())
                    .expect("request should be valid"),
            )
            .await
            .expect("router should respond");
        let location = response
            .headers()
            .get("location")
            .and_then(|value| value.to_str().ok())
            .unwrap_or_default()
            .to_owned();
        (response.status(), location)
    }

    #[tokio::test]
    async fn configured_frontend_is_the_root_landing_for_signed_in_and_out_requests() {
        let (app, state) = app(Some(std::env::temp_dir().join("unused-frontend-root"))).await;
        let token = auth::mint_browser_token(
            &state,
            "127.0.0.1",
            EMAIL,
            zeroize::Zeroizing::new(PASSWORD.to_owned()),
            3600,
        )
        .await
        .expect("test browser session should be issued")
        .token;

        for cookie in [None, Some(format!("stricknani_session={token}"))] {
            let (status, target) = location(&app, "/", cookie.as_deref()).await;
            assert_eq!(status, StatusCode::SEE_OTHER);
            assert_eq!(target, "/app/");
        }

        let post = app
            .clone()
            .oneshot(
                Request::post("/")
                    .body(Body::empty())
                    .expect("request should be valid"),
            )
            .await
            .expect("router should respond");
        assert_eq!(post.status(), StatusCode::METHOD_NOT_ALLOWED);
        let missing = app
            .oneshot(
                Request::get("/not-a-route")
                    .body(Body::empty())
                    .expect("request should be valid"),
            )
            .await
            .expect("router should respond");
        assert_eq!(missing.status(), StatusCode::NOT_FOUND);
    }

    #[tokio::test]
    async fn unconfigured_frontend_keeps_the_legacy_root_redirects() {
        let (app, state) = app(None).await;
        let token = auth::mint_browser_token(
            &state,
            "127.0.0.1",
            EMAIL,
            zeroize::Zeroizing::new(PASSWORD.to_owned()),
            3600,
        )
        .await
        .expect("test browser session should be issued")
        .token;

        assert_eq!(
            location(&app, "/", None).await,
            (StatusCode::SEE_OTHER, "/login".into())
        );
        assert_eq!(
            location(&app, "/", Some(&format!("stricknani_session={token}"))).await,
            (StatusCode::SEE_OTHER, "/projects".into())
        );
        let post = app
            .clone()
            .oneshot(
                Request::post("/")
                    .body(Body::empty())
                    .expect("request should be valid"),
            )
            .await
            .expect("router should respond");
        assert_eq!(post.status(), StatusCode::METHOD_NOT_ALLOWED);
        let missing = app
            .oneshot(
                Request::get("/not-a-route")
                    .body(Body::empty())
                    .expect("request should be valid"),
            )
            .await
            .expect("router should respond");
        assert_eq!(missing.status(), StatusCode::NOT_FOUND);
    }

    #[tokio::test]
    async fn configured_frontend_bridges_android_yarn_links_for_get_only() {
        let (app, state) = app(Some(std::env::temp_dir().join("unused-frontend-root"))).await;
        let token = auth::mint_browser_token(
            &state,
            "127.0.0.1",
            EMAIL,
            zeroize::Zeroizing::new(PASSWORD.to_owned()),
            3600,
        )
        .await
        .expect("test browser session should be issued")
        .token;

        for cookie in [None, Some(format!("stricknani_session={token}"))] {
            assert_eq!(
                location(&app, "/yarn/42", cookie.as_deref()).await,
                (StatusCode::PERMANENT_REDIRECT, "/app/yarns/42".into())
            );
        }
        assert_eq!(
            location(&app, "/yarn/42?tab=photos", None).await,
            (
                StatusCode::PERMANENT_REDIRECT,
                "/app/yarns/42?tab=photos".into()
            )
        );
        assert_eq!(
            location(&app, "/yarn/42?next=https://outside.example", None).await,
            (
                StatusCode::PERMANENT_REDIRECT,
                "/app/yarns/42?next=https://outside.example".into()
            )
        );

        for path in [
            "/yarn/not-a-number",
            "/yarn/-1",
            "/yarn/+1",
            "/yarn/2147483648",
        ] {
            let invalid = app
                .clone()
                .oneshot(
                    Request::get(path)
                        .body(Body::empty())
                        .expect("request should be valid"),
                )
                .await
                .expect("router should respond");
            assert_eq!(invalid.status(), StatusCode::NOT_FOUND, "path: {path}");
        }

        let post = app
            .oneshot(
                Request::post("/yarn/42")
                    .body(Body::empty())
                    .expect("request should be valid"),
            )
            .await
            .expect("router should respond");
        assert_eq!(post.status(), StatusCode::METHOD_NOT_ALLOWED);
    }

    #[tokio::test]
    async fn unconfigured_frontend_does_not_add_android_yarn_alias() {
        let (app, _) = app(None).await;
        for request in [Request::get("/yarn/42"), Request::post("/yarn/42")] {
            let response = app
                .clone()
                .oneshot(
                    request
                        .body(Body::empty())
                        .expect("request should be valid"),
                )
                .await
                .expect("router should respond");
            assert_eq!(response.status(), StatusCode::NOT_FOUND);
        }
    }
}

fn render_page(page: GaugePage, status: StatusCode) -> Response {
    match page.render() {
        Ok(html) => (status, Html(html)).into_response(),
        Err(_) => StatusCode::INTERNAL_SERVER_ERROR.into_response(),
    }
}

#[derive(Debug, Default, Deserialize)]
struct GaugeForm {
    pattern_gauge_stitches: Option<String>,
    pattern_gauge_rows: Option<String>,
    user_gauge_stitches: Option<String>,
    user_gauge_rows: Option<String>,
    pattern_cast_on_stitches: Option<String>,
    pattern_row_count: Option<String>,
}

impl GaugeForm {
    fn calculate(&self, language: Language) -> Result<GaugeResult, String> {
        let labels = GaugeLabels::for_language(language);
        let pattern_gauge_stitches = parse_required_count(
            self.pattern_gauge_stitches.as_deref(),
            labels.pattern_stitches,
            &labels,
        )?;
        let pattern_gauge_rows = parse_required_count(
            self.pattern_gauge_rows.as_deref(),
            labels.pattern_rows,
            &labels,
        )?;
        let user_gauge_stitches = parse_required_count(
            self.user_gauge_stitches.as_deref(),
            labels.user_stitches,
            &labels,
        )?;
        let user_gauge_rows =
            parse_required_count(self.user_gauge_rows.as_deref(), labels.user_rows, &labels)?;
        let pattern_cast_on_stitches = parse_required_count(
            self.pattern_cast_on_stitches.as_deref(),
            labels.cast_on_stitches,
            &labels,
        )?;
        let pattern_row_count = parse_optional_count(
            self.pattern_row_count.as_deref(),
            labels.pattern_row_count,
            &labels,
        )?;

        Ok(GaugeInput {
            pattern_gauge_stitches,
            pattern_gauge_rows,
            user_gauge_stitches,
            user_gauge_rows,
            pattern_cast_on_stitches,
            pattern_row_count,
        }
        .calculate())
    }
}

fn parse_required_count(
    value: Option<&str>,
    field_name: &str,
    labels: &GaugeLabels,
) -> Result<NonZeroU32, String> {
    let Some(value) = value.map(str::trim).filter(|value| !value.is_empty()) else {
        return Err(format!("{} {field_name}.", labels.required));
    };

    parse_positive_count(value, field_name, labels)
}

fn parse_optional_count(
    value: Option<&str>,
    field_name: &str,
    labels: &GaugeLabels,
) -> Result<Option<NonZeroU32>, String> {
    match value.map(str::trim).filter(|value| !value.is_empty()) {
        Some(value) => parse_positive_count(value, field_name, labels).map(Some),
        None => Ok(None),
    }
}

fn parse_positive_count(
    value: &str,
    field_name: &str,
    labels: &GaugeLabels,
) -> Result<NonZeroU32, String> {
    let count = value
        .parse::<u32>()
        .map_err(|_| format!("{} {field_name}.", labels.whole_number))?;

    NonZeroU32::new(count).ok_or_else(|| format!("{} {field_name}.", labels.positive))
}

#[derive(Debug, Serialize)]
struct HealthResponse {
    status: &'static str,
}

#[derive(Template)]
#[template(path = "gauge.html")]
struct GaugePage {
    labels: GaugeLabels,
    form: GaugeFormView,
    has_result: bool,
    adjusted_stitches: String,
    has_adjusted_rows: bool,
    adjusted_rows: String,
    has_error: bool,
    error: String,
}

impl GaugePage {
    fn new(
        language: Language,
        form: GaugeForm,
        result: Option<GaugeResult>,
        error: Option<String>,
    ) -> Self {
        Self {
            labels: GaugeLabels::for_language(language),
            form: GaugeFormView::from(form),
            has_result: result.is_some(),
            adjusted_stitches: result
                .as_ref()
                .map(|result| result.adjusted_stitches.to_string())
                .unwrap_or_default(),
            has_adjusted_rows: result
                .as_ref()
                .is_some_and(|result| result.adjusted_rows.is_some()),
            adjusted_rows: result
                .and_then(|result| result.adjusted_rows)
                .map(|rows| rows.to_string())
                .unwrap_or_default(),
            has_error: error.is_some(),
            error: error.unwrap_or_default(),
        }
    }
}

#[derive(Debug)]
struct GaugeFormView {
    pattern_gauge_stitches: String,
    pattern_gauge_rows: String,
    user_gauge_stitches: String,
    user_gauge_rows: String,
    pattern_cast_on_stitches: String,
    pattern_row_count: String,
}

impl From<GaugeForm> for GaugeFormView {
    fn from(form: GaugeForm) -> Self {
        Self {
            pattern_gauge_stitches: form.pattern_gauge_stitches.unwrap_or_default(),
            pattern_gauge_rows: form.pattern_gauge_rows.unwrap_or_default(),
            user_gauge_stitches: form.user_gauge_stitches.unwrap_or_default(),
            user_gauge_rows: form.user_gauge_rows.unwrap_or_default(),
            pattern_cast_on_stitches: form.pattern_cast_on_stitches.unwrap_or_default(),
            pattern_row_count: form.pattern_row_count.unwrap_or_default(),
        }
    }
}

#[derive(Debug)]
struct GaugeLabels {
    language: &'static str,
    title: &'static str,
    description: &'static str,
    pattern_gauge: &'static str,
    user_gauge: &'static str,
    pattern_stitches: &'static str,
    pattern_rows: &'static str,
    user_stitches: &'static str,
    user_rows: &'static str,
    cast_on_stitches: &'static str,
    pattern_row_count: &'static str,
    optional: &'static str,
    calculate: &'static str,
    result: &'static str,
    adjusted_stitches: &'static str,
    adjusted_rows: &'static str,
    required: &'static str,
    whole_number: &'static str,
    positive: &'static str,
    invalid_form: &'static str,
}

impl GaugeLabels {
    fn for_language(language: Language) -> Self {
        match language {
            Language::German => Self {
                language: "de",
                title: "Maschenprobe berechnen",
                description: "Berechne Maschen und Reihen für deine Maschenprobe.",
                pattern_gauge: "Maschenprobe der Anleitung (pro 10 cm)",
                user_gauge: "Deine Maschenprobe (pro 10 cm)",
                pattern_stitches: "Maschen",
                pattern_rows: "Reihen",
                user_stitches: "Deine Maschen",
                user_rows: "Deine Reihen",
                cast_on_stitches: "Anschlagmaschen",
                pattern_row_count: "Reihen stricken",
                optional: "optional",
                calculate: "Berechnen",
                result: "Ergebnis",
                adjusted_stitches: "Maschen anschlagen",
                adjusted_rows: "Reihen stricken",
                required: "Bitte gib einen Wert ein für",
                whole_number: "Bitte gib eine ganze Zahl ein für",
                positive: "Bitte gib eine Zahl größer als null ein für",
                invalid_form: "Das Formular konnte nicht gelesen werden. Bitte prüfe deine Eingaben.",
            },
            Language::English => Self {
                language: "en",
                title: "Gauge calculator",
                description: "Calculate stitch and row counts for your gauge.",
                pattern_gauge: "Pattern gauge (per 10 cm)",
                user_gauge: "Your gauge (per 10 cm)",
                pattern_stitches: "Stitches",
                pattern_rows: "Rows",
                user_stitches: "Your stitches",
                user_rows: "Your rows",
                cast_on_stitches: "Stitches to cast on",
                pattern_row_count: "Rows to knit",
                optional: "optional",
                calculate: "Calculate",
                result: "Result",
                adjusted_stitches: "Stitches to cast on",
                adjusted_rows: "Rows to knit",
                required: "Enter a value for",
                whole_number: "Enter a whole number for",
                positive: "Enter a number greater than zero for",
                invalid_form: "The form could not be read. Check your inputs and try again.",
            },
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum Language {
    English,
    German,
}

impl Language {
    fn configured_default() -> Self {
        match std::env::var("DEFAULT_LANGUAGE").as_deref() {
            Ok("en") => Self::English,
            _ => Self::German,
        }
    }
}

fn language_from_headers(headers: &HeaderMap) -> Language {
    if let Some(cookie) = headers.get("cookie").and_then(|value| value.to_str().ok()) {
        for item in cookie.split(';') {
            if let Some(("language", value)) = item.trim().split_once('=') {
                match value {
                    "en" => return Language::English,
                    "de" => return Language::German,
                    _ => {}
                }
            }
        }
    }

    if let Some(accept_language) = headers
        .get("accept-language")
        .and_then(|value| value.to_str().ok())
    {
        for language in accept_language.split(',') {
            if language.trim_start().starts_with("de") {
                return Language::German;
            }
            if language.trim_start().starts_with("en") {
                return Language::English;
            }
        }
    }

    Language::configured_default()
}

#[cfg(test)]
mod tests {
    use super::app;
    use crate::domain::gauge::adjusted_count;
    use axum::body::Body;
    use axum::http::{Request, StatusCode};
    use http_body_util::BodyExt;
    use tower::ServiceExt;

    const VALID_FORM: &str = "pattern_gauge_stitches=20&pattern_gauge_rows=26&user_gauge_stitches=18&user_gauge_rows=24&pattern_cast_on_stitches=120&pattern_row_count=100";

    #[test]
    fn counts_use_nearest_integer_with_half_rounded_up() {
        assert_eq!(adjusted_count(5, 1, 2), 3);
        assert_eq!(adjusted_count(7, 1, 2), 4);
        assert_eq!(adjusted_count(121, 19, 22), 105);
    }

    #[tokio::test]
    async fn health_returns_json_status() {
        let response = app()
            .oneshot(Request::get("/healthz").body(Body::empty()).unwrap())
            .await
            .unwrap();

        assert_eq!(response.status(), StatusCode::OK);
        let body = response.into_body().collect().await.unwrap().to_bytes();
        assert_eq!(body, r#"{"status":"ok"}"#);
    }

    #[tokio::test]
    async fn static_assets_are_served() {
        let response = app()
            .oneshot(
                Request::get("/static/css/app.css")
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();

        assert_eq!(response.status(), StatusCode::OK);
        assert!(
            response
                .headers()
                .get("content-type")
                .unwrap()
                .to_str()
                .unwrap()
                .contains("text/css")
        );
    }

    #[tokio::test]
    async fn gauge_get_renders_a_standalone_form() {
        let response = app()
            .oneshot(
                Request::get("/gauge/")
                    .header("accept-language", "en")
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();

        assert_eq!(response.status(), StatusCode::OK);
        let body = response.into_body().collect().await.unwrap().to_bytes();
        let html = String::from_utf8(body.to_vec()).unwrap();
        assert!(html.contains("Gauge calculator"));
        assert!(html.contains("method=\"post\" action=\"/gauge/calculate\""));
        assert!(html.contains("name=\"pattern_gauge_stitches\""));
        assert!(!html.contains("hx-post"));
    }

    #[tokio::test]
    async fn valid_gauge_form_renders_adjusted_counts() {
        let response = app()
            .oneshot(
                Request::post("/gauge/calculate")
                    .header("content-type", "application/x-www-form-urlencoded")
                    .header("accept-language", "en")
                    .body(Body::from(VALID_FORM))
                    .unwrap(),
            )
            .await
            .unwrap();

        assert_eq!(response.status(), StatusCode::OK);
        let body = response.into_body().collect().await.unwrap().to_bytes();
        let html = String::from_utf8(body.to_vec()).unwrap();
        assert!(html.contains("Stitches to cast on"));
        assert!(html.contains(">108<"));
        assert!(html.contains(">92<"));
    }

    #[tokio::test]
    async fn invalid_input_keeps_values_and_displays_a_friendly_error() {
        let form = VALID_FORM.replace("pattern_gauge_stitches=20", "pattern_gauge_stitches=0");
        let response = app()
            .oneshot(
                Request::post("/gauge/calculate")
                    .header("content-type", "application/x-www-form-urlencoded")
                    .header("accept-language", "en")
                    .body(Body::from(form))
                    .unwrap(),
            )
            .await
            .unwrap();

        assert_eq!(response.status(), StatusCode::UNPROCESSABLE_ENTITY);
        let body = response.into_body().collect().await.unwrap().to_bytes();
        let html = String::from_utf8(body.to_vec()).unwrap();
        assert!(html.contains("Enter a number greater than zero for Stitches."));
        assert!(html.contains("value=\"0\""));
    }

    #[tokio::test]
    async fn blank_optional_row_count_omits_the_row_result() {
        let form = VALID_FORM.replace("&pattern_row_count=100", "&pattern_row_count=");
        let response = app()
            .oneshot(
                Request::post("/gauge/calculate")
                    .header("content-type", "application/x-www-form-urlencoded")
                    .header("accept-language", "en")
                    .body(Body::from(form))
                    .unwrap(),
            )
            .await
            .unwrap();

        assert_eq!(response.status(), StatusCode::OK);
        let body = response.into_body().collect().await.unwrap().to_bytes();
        let html = String::from_utf8(body.to_vec()).unwrap();
        assert!(html.contains(">108<"));
        assert!(!html.contains("Rows to knit</dt>"));
    }

    #[tokio::test]
    async fn accept_language_selects_german_copy() {
        let response = app()
            .oneshot(
                Request::get("/gauge/")
                    .header("accept-language", "de-DE,de;q=0.9,en;q=0.8")
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();

        assert_eq!(response.status(), StatusCode::OK);
        let body = response.into_body().collect().await.unwrap().to_bytes();
        let html = String::from_utf8(body.to_vec()).unwrap();
        assert!(html.contains("Maschenprobe berechnen"));
        assert!(html.contains("<html lang=\"de\">"));
    }
}
