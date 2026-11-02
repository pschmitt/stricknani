use std::fs;

use axum::body::Body;
use axum::http::{Request, StatusCode};
use axum::middleware;
use http_body_util::BodyExt;
use serde_json::Value;
use stricknani_server::frontend;
use stricknani_server::pwa;
use stricknani_server::security::security_headers;
use tower::ServiceExt;

async fn get(path: &str) -> axum::response::Response {
    pwa::router()
        .oneshot(Request::get(path).body(Body::empty()).unwrap())
        .await
        .unwrap()
}

#[tokio::test]
async fn root_service_worker_has_root_scope_and_is_revalidated() {
    let response = get("/sw.js").await;

    assert_eq!(response.status(), StatusCode::OK);
    assert_eq!(
        response.headers().get("content-type").unwrap(),
        "application/javascript; charset=utf-8"
    );
    assert_eq!(
        response.headers().get("service-worker-allowed").unwrap(),
        "/"
    );
    assert_eq!(response.headers().get("cache-control").unwrap(), "no-cache");
    assert_eq!(
        response.headers().get("x-content-type-options").unwrap(),
        "nosniff"
    );
}

#[tokio::test]
async fn registration_script_registers_only_the_root_worker_without_inline_code() {
    let response = get("/register-pwa.js").await;

    assert_eq!(response.status(), StatusCode::OK);
    assert_eq!(
        response.headers().get("content-type").unwrap(),
        "application/javascript; charset=utf-8"
    );
    let body = response.into_body().collect().await.unwrap().to_bytes();
    let script = String::from_utf8(body.to_vec()).unwrap();
    assert!(script.contains("register(\"/sw.js\", { scope: \"/\" })"));
    assert!(script.contains("window.addEventListener(\"load\""));
}

#[tokio::test]
async fn manifest_is_public_json_with_root_scope_and_existing_icons() {
    let response = get("/manifest.webmanifest").await;

    assert_eq!(response.status(), StatusCode::OK);
    assert_eq!(
        response.headers().get("content-type").unwrap(),
        "application/manifest+json; charset=utf-8"
    );
    let body = response.into_body().collect().await.unwrap().to_bytes();
    let manifest: Value = serde_json::from_slice(&body).unwrap();
    assert_eq!(manifest["scope"], "/");
    assert_eq!(manifest["start_url"], "/");
    assert_eq!(manifest["icons"][0]["src"], "/static/icons/icon-192.png");
    assert_eq!(manifest["icons"][1]["src"], "/static/icons/icon-512.png");
}

#[tokio::test]
async fn offline_page_is_bilingual_non_personalized_and_not_http_cached() {
    let app = pwa::router();
    let english = app
        .clone()
        .oneshot(
            Request::get("/offline")
                .header("cookie", "stricknani_session=private-user-a")
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();
    let german = app
        .oneshot(
            Request::get("/offline")
                .header("cookie", "stricknani_session=private-user-b")
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();

    assert_eq!(english.status(), StatusCode::OK);
    assert_eq!(english.headers().get("cache-control").unwrap(), "no-store");
    assert_eq!(english.headers().get("content-language").unwrap(), "en, de");
    assert!(english.headers().get("set-cookie").is_none());
    let english_body = english.into_body().collect().await.unwrap().to_bytes();
    let german_body = german.into_body().collect().await.unwrap().to_bytes();
    assert_eq!(english_body, german_body);
    let html = String::from_utf8(english_body.to_vec()).unwrap();
    assert!(html.contains("You are offline"));
    assert!(html.contains("<section lang=\"en\""));
    assert!(html.contains("<section lang=\"de\""));
    assert!(html.contains("Private pages, API responses, and media are not cached."));
    assert!(
        html.contains("Private Seiten, API-Antworten und Medien werden nicht zwischengespeichert.")
    );
    let catalog: Value = serde_json::from_str(include_str!("../locales/messages.json")).unwrap();
    for msgid in ["You are offline", "No connection", "Try Again"] {
        let translation = catalog["de"][msgid]
            .as_str()
            .expect("reviewed German catalog translation is present");
        assert!(
            html.contains(translation),
            "missing German translation: {msgid}"
        );
    }
}

#[tokio::test]
async fn pwa_router_does_not_serve_authenticated_or_api_routes() {
    for path in [
        "/projects",
        "/api/v1/projects",
        "/media/projects/1/image.png",
    ] {
        let response = get(path).await;
        assert_eq!(response.status(), StatusCode::NOT_FOUND, "path: {path}");
    }
}

#[test]
fn service_worker_precaches_all_offline_fallback_stylesheets() {
    let offline = scraper::Html::parse_document(include_str!("../templates/offline.html"));
    let stylesheet_selector = scraper::Selector::parse("link[rel='stylesheet']")
        .expect("stylesheet selector should parse");
    let worker = include_str!("../assets/service-worker.js");
    let allowlist = worker
        .split("const PUBLIC_PATHS = new Set([")
        .nth(1)
        .and_then(|tail| tail.split("]);").next())
        .expect("service worker should declare its public cache allowlist");

    for stylesheet in offline.select(&stylesheet_selector) {
        let href = stylesheet
            .value()
            .attr("href")
            .expect("offline stylesheet should have a URL");
        assert!(
            allowlist.contains(&format!("\"{href}\"")),
            "offline fallback stylesheet is missing from the service worker precache: {href}"
        );
    }
    for private_path in ["/projects", "/api/v1/projects", "/media/projects/"] {
        assert!(
            !allowlist.contains(private_path),
            "private route must not enter the service worker precache: {private_path}"
        );
    }
}

#[tokio::test]
async fn app_worker_and_offline_assets_are_explicit_app_scoped_public_routes() {
    let root = tempfile::tempdir().unwrap();
    fs::create_dir_all(root.path().join("icons")).unwrap();
    fs::write(
        root.path().join("app-sw.js"),
        "self.addEventListener('install',()=>{});",
    )
    .unwrap();
    fs::write(
        root.path().join("manifest.webmanifest"),
        include_str!("../../web/static/manifest.webmanifest"),
    )
    .unwrap();
    fs::write(
        root.path().join("offline.html"),
        include_str!("../../web/static/offline.html"),
    )
    .unwrap();
    fs::write(
        root.path().join("offline.css"),
        include_str!("../../web/static/offline.css"),
    )
    .unwrap();
    fs::write(root.path().join("icons/icon-192.png"), b"png192").unwrap();
    fs::write(root.path().join("icons/icon-512.png"), b"png512").unwrap();
    let app = pwa::router_with_web_root(Some(root.path().to_path_buf()))
        .layer(middleware::from_fn(security_headers));

    let worker = app
        .clone()
        .oneshot(Request::get("/app/sw.js").body(Body::empty()).unwrap())
        .await
        .unwrap();
    assert_eq!(worker.status(), StatusCode::OK);
    assert_eq!(worker.headers()["service-worker-allowed"], "/app/");
    assert_eq!(worker.headers()["cache-control"], "no-cache");
    assert_eq!(
        worker.headers()["content-type"],
        "application/javascript; charset=utf-8"
    );
    assert!(worker.headers().contains_key("content-security-policy"));

    for (path, content_type) in [
        (
            "/app/manifest.webmanifest",
            "application/manifest+json; charset=utf-8",
        ),
        ("/app/offline.html", "text/html; charset=utf-8"),
        ("/app/offline.css", "text/css; charset=utf-8"),
        ("/app/icons/icon-192.png", "image/png"),
        ("/app/icons/icon-512.png", "image/png"),
    ] {
        let response = app
            .clone()
            .oneshot(Request::get(path).body(Body::empty()).unwrap())
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::OK, "{path}");
        assert_eq!(response.headers()["content-type"], content_type, "{path}");
        assert!(response.headers().get("x-content-type-options").is_some());
        assert!(response.headers().contains_key("content-security-policy"));
        if path == "/app/offline.html" {
            assert_eq!(response.headers()["content-language"], "en, de");
        }
        match path {
            "/app/offline.html" => {
                assert_eq!(response.headers()["cache-control"], "no-store");
            }
            _ => assert!(
                response.headers()["cache-control"]
                    .to_str()
                    .unwrap()
                    .contains("public")
            ),
        }
    }

    for path in [
        "/app/messages.json",
        "/app/csp.json",
        "/app/pwa-assets.json",
        "/app/projects/42",
        "/api/v2/projects",
        "/media/projects/42/image.png",
    ] {
        let response = app
            .clone()
            .oneshot(Request::get(path).body(Body::empty()).unwrap())
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::NOT_FOUND, "{path}");
    }
}

#[tokio::test]
async fn configured_app_pwa_assets_fail_closed_when_the_build_is_incomplete() {
    let root = tempfile::tempdir().unwrap();
    let app = pwa::router_with_web_root(Some(root.path().to_path_buf()))
        .layer(middleware::from_fn(security_headers));
    for path in [
        "/app/sw.js",
        "/app/manifest.webmanifest",
        "/app/offline.html",
    ] {
        let response = app
            .clone()
            .oneshot(Request::get(path).body(Body::empty()).unwrap())
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::SERVICE_UNAVAILABLE, "{path}");
    }
}

#[tokio::test]
async fn app_worker_routes_coexist_with_the_frontend_shell_without_fallback_leaks() {
    let root = tempfile::tempdir().unwrap();
    fs::create_dir_all(root.path().join("_app/immutable")).unwrap();
    fs::create_dir_all(root.path().join("icons")).unwrap();
    fs::write(
        root.path().join("app-sw.js"),
        "self.addEventListener('install',()=>{});",
    )
    .unwrap();
    fs::write(
        root.path().join("manifest.webmanifest"),
        include_str!("../../web/static/manifest.webmanifest"),
    )
    .unwrap();
    fs::write(
        root.path().join("offline.html"),
        include_str!("../../web/static/offline.html"),
    )
    .unwrap();
    fs::write(
        root.path().join("offline.css"),
        include_str!("../../web/static/offline.css"),
    )
    .unwrap();
    fs::write(root.path().join("icons/icon-192.png"), b"png192").unwrap();
    fs::write(root.path().join("icons/icon-512.png"), b"png512").unwrap();
    fs::write(root.path().join("200.html"), "<html>app shell</html>").unwrap();
    fs::write(
        root.path().join("csp.json"),
        r#"{"script_hashes":[],"style_hashes":[]}"#,
    )
    .unwrap();

    let web_root = root.path().to_path_buf();
    let app = pwa::router_with_web_root(Some(web_root.clone())).merge(frontend::router(web_root));
    for (path, expected) in [
        ("/app/sw.js", StatusCode::OK),
        ("/app/manifest.webmanifest", StatusCode::OK),
        ("/app/projects/42", StatusCode::OK),
        ("/app/pwa-assets.json", StatusCode::NOT_FOUND),
        ("/app/csp.json", StatusCode::NOT_FOUND),
    ] {
        let response = app
            .clone()
            .oneshot(Request::get(path).body(Body::empty()).unwrap())
            .await
            .unwrap();
        assert_eq!(response.status(), expected, "{path}");
    }
}

#[test]
fn app_worker_requires_explicit_preparation_and_retains_assets_until_page_ack() {
    let worker = include_str!("../../web/static/service-worker.js");
    assert_eq!(worker.matches("skipWaiting").count(), 1);
    let commit = worker
        .split("async function commitUpdate")
        .nth(1)
        .and_then(|body| body.split("async function cancelUpdate").next())
        .expect("worker should define one explicit commit handler");
    assert!(commit.contains("VALIDATE_UPDATE"));
    assert!(commit.contains("sameClientSet"));
    assert!(commit.contains("writeActivationState"));
    assert!(commit.contains("event.waitUntil(self.skipWaiting())"));
    assert!(!worker.contains("clients.claim"));
    assert!(worker.contains("function isAppClientPath(pathname)"));
    assert!(worker.contains("pathname === \"/app\""));
    assert!(worker.contains("__APP_VERSION__"));
    assert!(worker.contains("__APP_CODE_VERSION__"));
    assert!(worker.contains("__APP_ASSETS__"));
    assert!(worker.contains("UPDATE_STATE_CACHE"));
    assert!(worker.contains("UPDATE_ACTIVATED"));
    assert!(worker.contains("RELOAD_ACK"));
    assert!(worker.contains("pageVersion === APP_CODE_VERSION"));
    assert!(worker.contains("retainedImmutableResponse(request)"));
    assert!(worker.contains("ignoreSearch: false"));
    let activation_reader = worker
        .split("async function readActivationState")
        .nth(1)
        .and_then(|body| body.split("async function deleteActivationState").next())
        .expect("worker should define an activation-state reader");
    assert!(activation_reader.contains("caches.match(UPDATE_STATE_URL"));
    assert!(!activation_reader.contains("caches.open(UPDATE_STATE_CACHE)"));
    let active_worker_guard = worker
        .split("async function activeWorkerCanPrune")
        .nth(1)
        .and_then(|body| body.split("async function deleteOldAppCaches").next())
        .expect("worker should verify the active worker before cache pruning");
    assert!(active_worker_guard.contains("registration.waiting"));
    assert!(active_worker_guard.contains("registration.installing"));
    assert!(active_worker_guard.contains("GET_VERSION"));
    assert!(worker.contains("function isPromotableImmutableAsset(url)"));
    assert!(worker.contains("PUBLIC_ASSETS.includes(url.pathname)"));
    assert!(worker.contains("responseMatches(retained, url)"));
    assert!(worker.contains("void queueImmutableCacheWrite(cache, url, retained.clone())"));
    assert!(worker.contains(".finally(finishBackground)"));
    assert!(worker.contains("await cacheWriteQueue;"));
    let cache_pruner = worker
        .split("async function pruneOldCachesIfSafe")
        .nth(1)
        .and_then(|body| body.split("async function installAppShell").next())
        .expect("worker should define guarded cache pruning");
    assert!(cache_pruner.contains("activeWorkerCanPrune(activeWorker)"));
    assert!(worker.contains("registration.waiting"));
    assert!(worker.contains("registration.installing"));
    let activation = worker
        .split("self.addEventListener(\"activate\"")
        .nth(1)
        .and_then(|body| {
            body.split("async function retainedImmutableResponse")
                .next()
        })
        .expect("worker should define an activation handler");
    assert!(!activation.contains("deleteOldAppCaches"));
    for forbidden in ["/app/messages.json", "/api/", "/media/", "/app/projects"] {
        assert!(
            !worker.contains(forbidden),
            "private or mutable path listed: {forbidden}"
        );
    }
}
