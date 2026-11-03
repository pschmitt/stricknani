//! Public progressive web app resources and the non-personalized offline fallback.

use std::{
    fs,
    path::{Path, PathBuf},
    sync::Arc,
};

use askama::Template;
use axum::Router;
use axum::body::Body;
use axum::extract::State;
use axum::http::header::{CACHE_CONTROL, CONTENT_LANGUAGE, CONTENT_TYPE};
use axum::http::{HeaderValue, Response, StatusCode};
use axum::response::IntoResponse;
use axum::routing::get;

const SERVICE_WORKER: &str = include_str!("../assets/service-worker.js");
const REGISTRATION_SCRIPT: &str = include_str!("../assets/register-pwa.js");
const MANIFEST: &str = include_str!("../assets/manifest.webmanifest");
const MAX_APP_WORKER_BYTES: u64 = 512 * 1024;
const MAX_APP_MANIFEST_BYTES: u64 = 32 * 1024;
const MAX_APP_OFFLINE_HTML_BYTES: u64 = 64 * 1024;
const MAX_APP_OFFLINE_CSS_BYTES: u64 = 64 * 1024;
const MAX_APP_OFFLINE_JS_BYTES: u64 = 8 * 1024;
const MAX_APP_ICON_BYTES: u64 = 256 * 1024;

#[derive(Clone, Default)]
struct AppPwaState {
    configured: bool,
    worker: Option<Arc<Vec<u8>>>,
    manifest: Option<Arc<Vec<u8>>>,
    offline_html: Option<Arc<Vec<u8>>>,
    offline_css: Option<Arc<Vec<u8>>>,
    offline_theme_js: Option<Arc<Vec<u8>>>,
    icon_192: Option<Arc<Vec<u8>>>,
    icon_512: Option<Arc<Vec<u8>>>,
}

/// Routes for the service worker, PWA manifest, registration script, and offline page.
pub fn router() -> Router {
    router_with_web_root(std::env::var_os("WEB_ROOT").map(PathBuf::from))
}

/// Build PWA routes against an optional generated browser build directory.
pub fn router_with_web_root(web_root: Option<PathBuf>) -> Router {
    let state = web_root.map(load_app_pwa_assets).unwrap_or_default();
    Router::new()
        .route("/sw.js", get(service_worker))
        .route("/register-pwa.js", get(registration_script))
        .route("/manifest.webmanifest", get(manifest))
        .route("/offline", get(offline))
        .route("/app/sw.js", get(app_service_worker))
        .route("/app/manifest.webmanifest", get(app_manifest))
        .route("/app/offline.html", get(app_offline_html))
        .route("/app/offline.css", get(app_offline_css))
        .route("/app/offline-theme.js", get(app_offline_theme_js))
        .route("/app/icons/icon-192.png", get(app_icon_192))
        .route("/app/icons/icon-512.png", get(app_icon_512))
        .with_state(state)
}

fn load_app_pwa_assets(root: PathBuf) -> AppPwaState {
    AppPwaState {
        configured: true,
        worker: read_bounded_file(&root.join("app-sw.js"), MAX_APP_WORKER_BYTES),
        manifest: read_bounded_file(&root.join("manifest.webmanifest"), MAX_APP_MANIFEST_BYTES),
        offline_html: read_bounded_file(&root.join("offline.html"), MAX_APP_OFFLINE_HTML_BYTES),
        offline_css: read_bounded_file(&root.join("offline.css"), MAX_APP_OFFLINE_CSS_BYTES),
        offline_theme_js: read_bounded_file(
            &root.join("offline-theme.js"),
            MAX_APP_OFFLINE_JS_BYTES,
        ),
        icon_192: read_bounded_file(&root.join("icons/icon-192.png"), MAX_APP_ICON_BYTES),
        icon_512: read_bounded_file(&root.join("icons/icon-512.png"), MAX_APP_ICON_BYTES),
    }
}

fn read_bounded_file(path: &Path, max_bytes: u64) -> Option<Arc<Vec<u8>>> {
    let metadata = fs::metadata(path).ok()?;
    if !metadata.is_file() || metadata.len() > max_bytes {
        return None;
    }
    let contents = fs::read(path).ok()?;
    (contents.len() as u64 <= max_bytes).then(|| Arc::new(contents))
}

async fn service_worker() -> Response<Body> {
    let mut response = asset_response(
        SERVICE_WORKER,
        "application/javascript; charset=utf-8",
        "no-cache",
    );
    response
        .headers_mut()
        .insert("service-worker-allowed", HeaderValue::from_static("/"));
    response
}

async fn registration_script() -> Response<Body> {
    asset_response(
        REGISTRATION_SCRIPT,
        "application/javascript; charset=utf-8",
        "public, max-age=3600",
    )
}

async fn manifest() -> Response<Body> {
    asset_response(
        MANIFEST,
        "application/manifest+json; charset=utf-8",
        "public, max-age=3600",
    )
}

async fn offline() -> Response<Body> {
    let template = OfflineTemplate {
        labels: OfflineLabels::new(),
    };
    let body = match template.render() {
        Ok(body) => body,
        Err(_) => return StatusCode::INTERNAL_SERVER_ERROR.into_response(),
    };
    let mut response = asset_response(&body, "text/html; charset=utf-8", "no-store");
    response
        .headers_mut()
        .insert(CONTENT_LANGUAGE, HeaderValue::from_static("en, de"));
    response
}

async fn app_service_worker(State(state): State<AppPwaState>) -> Response<Body> {
    let Some(worker) = state.worker.clone() else {
        return app_missing(&state);
    };
    let mut response =
        asset_bytes_response(worker, "application/javascript; charset=utf-8", "no-cache");
    response
        .headers_mut()
        .insert("service-worker-allowed", HeaderValue::from_static("/app/"));
    response
}

async fn app_manifest(State(state): State<AppPwaState>) -> Response<Body> {
    optional_asset(
        state.manifest.clone(),
        &state,
        "application/manifest+json; charset=utf-8",
        "public, max-age=3600",
    )
}

async fn app_offline_html(State(state): State<AppPwaState>) -> Response<Body> {
    let mut response = optional_asset(
        state.offline_html.clone(),
        &state,
        "text/html; charset=utf-8",
        "no-store",
    );
    if response.status().is_success() {
        response
            .headers_mut()
            .insert(CONTENT_LANGUAGE, HeaderValue::from_static("en, de"));
    }
    response
}

async fn app_offline_css(State(state): State<AppPwaState>) -> Response<Body> {
    optional_asset(
        state.offline_css.clone(),
        &state,
        "text/css; charset=utf-8",
        "public, max-age=3600",
    )
}

async fn app_offline_theme_js(State(state): State<AppPwaState>) -> Response<Body> {
    optional_asset(
        state.offline_theme_js.clone(),
        &state,
        "text/javascript; charset=utf-8",
        "public, max-age=3600",
    )
}

async fn app_icon_192(State(state): State<AppPwaState>) -> Response<Body> {
    optional_asset(
        state.icon_192.clone(),
        &state,
        "image/png",
        "public, max-age=3600",
    )
}

async fn app_icon_512(State(state): State<AppPwaState>) -> Response<Body> {
    optional_asset(
        state.icon_512.clone(),
        &state,
        "image/png",
        "public, max-age=3600",
    )
}

fn optional_asset(
    asset: Option<Arc<Vec<u8>>>,
    state: &AppPwaState,
    content_type: &'static str,
    cache_control: &'static str,
) -> Response<Body> {
    match asset {
        Some(bytes) => asset_bytes_response(bytes, content_type, cache_control),
        None => app_missing(state),
    }
}

fn app_missing(state: &AppPwaState) -> Response<Body> {
    if state.configured {
        StatusCode::SERVICE_UNAVAILABLE.into_response()
    } else {
        StatusCode::NOT_FOUND.into_response()
    }
}

fn asset_bytes_response(
    body: Arc<Vec<u8>>,
    content_type: &'static str,
    cache_control: &'static str,
) -> Response<Body> {
    let mut response = Response::new(Body::from(body.as_ref().clone()));
    let headers = response.headers_mut();
    headers.insert(CONTENT_TYPE, HeaderValue::from_static(content_type));
    headers.insert(CACHE_CONTROL, HeaderValue::from_static(cache_control));
    headers.insert(
        "x-content-type-options",
        HeaderValue::from_static("nosniff"),
    );
    response
}

fn asset_response(
    body: &str,
    content_type: &'static str,
    cache_control: &'static str,
) -> Response<Body> {
    let mut response = Response::new(Body::from(body.to_owned()));
    let headers = response.headers_mut();
    headers.insert(CONTENT_TYPE, HeaderValue::from_static(content_type));
    headers.insert(CACHE_CONTROL, HeaderValue::from_static(cache_control));
    headers.insert(
        "x-content-type-options",
        HeaderValue::from_static("nosniff"),
    );
    response
}

#[derive(Template)]
#[template(path = "offline.html")]
struct OfflineTemplate {
    labels: OfflineLabels,
}

struct OfflineLabels {
    heading: LabelPair,
    status: LabelPair,
    reason: LabelPair,
    message: LabelPair,
    action: LabelPair,
}

impl OfflineLabels {
    fn new() -> Self {
        Self {
            heading: tr("You are offline", "Du bist offline"),
            status: tr("Offline", "Offline"),
            reason: tr("No connection", "Keine Verbindung"),
            message: tr(
                "This page is not available while you're offline. Private pages, API responses, and media are not cached.",
                "Diese Seite ist offline nicht verfügbar. Private Seiten, API-Antworten und Medien werden nicht zwischengespeichert.",
            ),
            action: tr("Try Again", "Erneut versuchen"),
        }
    }
}

struct LabelPair {
    en: &'static str,
    de: &'static str,
}

fn tr(english: &'static str, german: &'static str) -> LabelPair {
    LabelPair {
        en: crate::i18n::text(crate::Language::English, english, german),
        de: crate::i18n::text(crate::Language::German, english, german),
    }
}
