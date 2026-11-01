//! Public progressive web app resources and the non-personalized offline fallback.

use askama::Template;
use axum::Router;
use axum::body::Body;
use axum::http::header::{CACHE_CONTROL, CONTENT_LANGUAGE, CONTENT_TYPE};
use axum::http::{HeaderValue, Response, StatusCode};
use axum::response::IntoResponse;
use axum::routing::get;

const SERVICE_WORKER: &str = include_str!("../assets/service-worker.js");
const REGISTRATION_SCRIPT: &str = include_str!("../assets/register-pwa.js");
const MANIFEST: &str = include_str!("../assets/manifest.webmanifest");

/// Routes for the service worker, PWA manifest, registration script, and offline page.
pub fn router() -> Router {
    Router::new()
        .route("/sw.js", get(service_worker))
        .route("/register-pwa.js", get(registration_script))
        .route("/manifest.webmanifest", get(manifest))
        .route("/offline", get(offline))
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
