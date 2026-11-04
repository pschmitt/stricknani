pub(crate) mod account_service;
pub(crate) mod admin_resource_service;
pub(crate) mod admin_service;
pub mod ai;
pub mod api_v2;
pub mod archive_jobs;
pub mod archive_service;
mod audit;
pub(crate) mod audit_service;
pub mod auth;
pub(crate) mod auth_stepup_service;
pub mod categories;
pub mod config;
pub mod db;
pub mod document_worker;
pub mod error_reporting;
pub mod frontend;
pub mod gravatar;
mod i18n;
pub mod image_import;
pub mod import_document;
pub mod import_service;
pub mod importer;
pub mod imports;
pub mod legacy_redirects;
pub mod media;
pub mod media_ids;
pub mod ocr;
pub mod ocr_service;
pub mod oidc;
pub mod oidc_configuration_service;
pub mod oidc_http_state;
pub(crate) mod oidc_identity_service;
pub mod projects;
pub mod pwa;
pub(crate) mod resource_access;
pub mod security;
pub mod sync;
pub mod url_import;
pub mod wayback;
mod web;
pub mod yarn_import;
pub mod yarns;
use std::path::PathBuf;
use std::sync::Arc;

use axum::extract::{OriginalUri, Path};
use axum::http::{HeaderMap, StatusCode};
use axum::response::{IntoResponse, Redirect, Response};
use axum::routing::get;
use axum::{Json, Router};
use serde::Serialize;

mod domain;
mod time;

pub fn app() -> Router {
    app_with_web_root(configured_web_root())
        .layer(axum::middleware::from_fn(security::security_headers))
}

fn configured_web_root() -> Option<PathBuf> {
    std::env::var_os("WEB_ROOT").map(PathBuf::from)
}

fn app_with_web_root(web_root: Option<PathBuf>) -> Router {
    let app = Router::new()
        .route("/healthz", get(healthz))
        .merge(pwa::router_with_web_root(web_root.clone()));
    match web_root {
        Some(root) => app.merge(frontend::router(root)),
        None => app,
    }
}

/// Build the persistent candidate with the default (disabled) optional integrations.
pub fn app_with_state(state: db::AppState) -> Router {
    app_with_imports(state, PathBuf::new(), false)
}

/// Build the server with explicit document-worker and external-import settings.
pub fn app_with_imports(
    state: db::AppState,
    worker_path: PathBuf,
    external_imports: bool,
) -> Router {
    app_with_imports_and_ai(state, worker_path, external_imports, None)
}

/// Build optional AI imports with an explicitly configured provider client.
pub fn app_with_imports_and_ai(
    state: db::AppState,
    worker_path: PathBuf,
    external_imports: bool,
    ai: Option<ai::AiClient>,
) -> Router {
    app_with_services(state, worker_path, external_imports, ai, None)
}

/// Build the candidate with explicitly enabled optional integrations.
pub fn app_with_services(
    state: db::AppState,
    worker_path: PathBuf,
    external_imports: bool,
    ai: Option<ai::AiClient>,
    ocr: Option<ocr::OcrService>,
) -> Router {
    app_with_services_and_web_root(
        state,
        worker_path,
        external_imports,
        ai,
        ocr,
        configured_web_root(),
    )
}

fn app_with_services_and_web_root(
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

    // `/` has always been the landing route: it sends everyone to the app.
    let mut web_router = Router::new()
        .route("/", get(app_landing))
        .merge(legacy_redirects::router());
    if web_root.is_some() {
        web_router = web_router.route("/yarn/{id}", get(android_yarn_deep_link));
    }

    app_with_web_root(web_root.clone())
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

async fn app_landing() -> Redirect {
    Redirect::to("/app/")
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
    async fn unconfigured_frontend_still_sends_the_root_to_the_app() {
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
            (StatusCode::SEE_OTHER, "/app/".into())
        );
        assert_eq!(
            location(&app, "/", Some(&format!("stricknani_session={token}"))).await,
            (StatusCode::SEE_OTHER, "/app/".into())
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

#[derive(Debug, Serialize)]
struct HealthResponse {
    status: &'static str,
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
    use axum::body::Body;
    use axum::http::{Request, StatusCode};
    use http_body_util::BodyExt;
    use tower::ServiceExt;

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
    async fn legacy_static_assets_are_gone() {
        let response = app()
            .oneshot(
                Request::get("/static/css/app.css")
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();

        assert_eq!(response.status(), StatusCode::NOT_FOUND);
    }
}
