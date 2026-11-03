//! Static frontend shell and asset routes for the new browser application.

use std::{fs, path::PathBuf, sync::Arc};

use axum::{
    Router,
    body::Body,
    extract::{Path, State},
    http::{HeaderValue, StatusCode, header},
    response::{IntoResponse, Redirect, Response},
    routing::get,
};
use serde::Deserialize;
use tower_http::services::{ServeDir, ServeFile};

use crate::security::FrontendCsp;

const MAX_SHELL_BYTES: u64 = 2 * 1024 * 1024;
const MAX_CSP_MANIFEST_BYTES: u64 = 64 * 1024;
const MAX_FAVICON_BYTES: u64 = 64 * 1024;

#[derive(Clone)]
struct FrontendState {
    fallback: Option<Arc<Vec<u8>>>,
    csp: Option<FrontendCsp>,
    favicon_svg: Option<Arc<Vec<u8>>>,
}

#[derive(Deserialize)]
struct CspManifest {
    script_hashes: Vec<String>,
    style_hashes: Vec<String>,
    #[serde(default)]
    style_attribute_hashes: Vec<String>,
}

/// Serve the browser app from a static build directory.
///
/// `web_root` is the SvelteKit adapter-static output directory. It must contain
/// `200.html`, `csp.json`, and `_app/`; assets are mounted under `/app/_app/`.
pub fn router(web_root: PathBuf) -> Router {
    let csp = read_csp_manifest(&web_root.join("csp.json"));
    let fallback = csp
        .as_ref()
        .and_then(|_| read_bounded_file(&web_root.join("200.html"), MAX_SHELL_BYTES))
        .map(Arc::new);
    let favicon_svg =
        read_bounded_file(&web_root.join("favicon.svg"), MAX_FAVICON_BYTES).map(Arc::new);
    let state = FrontendState {
        fallback,
        csp,
        favicon_svg,
    };

    Router::new()
        .route("/privacy", get(public_privacy_redirect))
        .route("/app", get(app_root_without_slash))
        .route("/app/", get(app_root))
        .route_service(
            "/app/messages.json",
            ServeFile::new(web_root.join("messages.json")),
        )
        .route("/app/favicon.svg", get(app_favicon))
        .route("/app/{*path}", get(app_path))
        .nest_service("/app/_app", ServeDir::new(web_root.join("_app")))
        .with_state(state)
}

async fn public_privacy_redirect() -> Redirect {
    Redirect::permanent("/app/privacy")
}

async fn app_root_without_slash() -> Redirect {
    Redirect::permanent("/app/")
}

async fn app_root(State(state): State<FrontendState>) -> Response {
    app_shell(state)
}

async fn app_favicon(State(state): State<FrontendState>) -> Response {
    let Some(svg) = state.favicon_svg else {
        return StatusCode::NOT_FOUND.into_response();
    };
    let mut response = Response::new(Body::from(svg.as_ref().clone()));
    let headers = response.headers_mut();
    headers.insert(
        header::CONTENT_TYPE,
        HeaderValue::from_static("image/svg+xml"),
    );
    headers.insert(
        header::CACHE_CONTROL,
        HeaderValue::from_static("public, max-age=3600"),
    );
    headers.insert(
        "x-content-type-options",
        HeaderValue::from_static("nosniff"),
    );
    response
}

async fn app_path(State(state): State<FrontendState>, Path(path): Path<String>) -> Response {
    if !is_known_route(&path) {
        return StatusCode::NOT_FOUND.into_response();
    }
    app_shell(state)
}

fn app_shell(state: FrontendState) -> Response {
    let Some(html) = state.fallback else {
        return StatusCode::SERVICE_UNAVAILABLE.into_response();
    };
    let mut response = Response::new(html.as_ref().clone().into());
    let headers = response.headers_mut();
    headers.insert(
        header::CONTENT_TYPE,
        HeaderValue::from_static("text/html; charset=utf-8"),
    );
    headers.insert(
        header::CACHE_CONTROL,
        HeaderValue::from_static("private, no-store"),
    );
    if let Some(csp) = state.csp {
        response.extensions_mut().insert(csp);
    }
    response
}

fn is_known_route(path: &str) -> bool {
    let segments = path.split('/').collect::<Vec<_>>();
    match segments.as_slice() {
        ["login"]
        | ["signup"]
        | ["projects"]
        | ["projects", "new"]
        | ["yarns"]
        | ["yarns", "new"]
        | ["categories"]
        | ["imports"]
        | ["privacy"]
        | ["about"]
        | ["account"]
        | ["more"]
        | ["create"]
        | ["admin"]
        | ["audit"]
        | ["admin", "users"]
        | ["admin", "audit"]
        | ["gauge"] => true,
        ["admin", "audit", event_id] => valid_positive_id(event_id),
        ["admin", "resources", kind, id] => {
            matches!(*kind, "project" | "yarn" | "category") && valid_positive_id(id)
        }
        ["projects", id] | ["projects", id, "edit"] | ["yarns", id] | ["yarns", id, "edit"] => {
            valid_id(id)
        }
        _ => false,
    }
}

fn valid_id(value: &str) -> bool {
    !value.is_empty() && value.bytes().all(|byte| byte.is_ascii_digit())
}

fn valid_positive_id(value: &str) -> bool {
    valid_id(value) && value.parse::<u64>().is_ok_and(|id| id > 0)
}

fn read_csp_manifest(path: &std::path::Path) -> Option<FrontendCsp> {
    let contents = read_bounded_file(path, MAX_CSP_MANIFEST_BYTES)?;
    let manifest = serde_json::from_slice::<CspManifest>(&contents).ok()?;
    FrontendCsp::from_hashes(
        &manifest.script_hashes,
        &manifest.style_hashes,
        &manifest.style_attribute_hashes,
    )
}

fn read_bounded_file(path: &std::path::Path, max_bytes: u64) -> Option<Vec<u8>> {
    let metadata = fs::metadata(path).ok()?;
    if !metadata.is_file() || metadata.len() > max_bytes {
        return None;
    }
    let contents = fs::read(path).ok()?;
    (contents.len() as u64 <= max_bytes).then_some(contents)
}
