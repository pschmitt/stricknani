use std::fs;

use axum::body::Body;
use axum::http::{Request, StatusCode};
use axum::middleware;
use http_body_util::BodyExt;
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
async fn root_service_worker_only_retires_the_legacy_registration() {
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
    let body = response.into_body().collect().await.unwrap().to_bytes();
    let script = String::from_utf8(body.to_vec()).unwrap();
    assert!(script.contains("caches.delete"));
    assert!(script.contains("registration.unregister()"));
    assert!(!script.contains("fetch"));
}

#[tokio::test]
async fn legacy_pwa_routes_are_gone() {
    for path in ["/register-pwa.js", "/manifest.webmanifest", "/offline"] {
        let response = get(path).await;
        assert_eq!(response.status(), StatusCode::NOT_FOUND, "path: {path}");
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
        include_str!("../web/static/manifest.webmanifest"),
    )
    .unwrap();
    fs::write(
        root.path().join("offline.html"),
        include_str!("../web/static/offline.html"),
    )
    .unwrap();
    fs::write(
        root.path().join("offline.css"),
        include_str!("../web/static/offline.css"),
    )
    .unwrap();
    fs::write(
        root.path().join("offline-theme.js"),
        include_str!("../web/static/offline-theme.js"),
    )
    .unwrap();
    fs::write(root.path().join("icons/icon-192.png"), b"png192").unwrap();
    fs::write(root.path().join("icons/icon-512.png"), b"png512").unwrap();
    fs::write(root.path().join("icons/icon-maskable-512.png"), b"pngmask").unwrap();
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
        ("/app/offline-theme.js", "text/javascript; charset=utf-8"),
        ("/app/icons/icon-192.png", "image/png"),
        ("/app/icons/icon-512.png", "image/png"),
        ("/app/icons/icon-maskable-512.png", "image/png"),
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
            let body = response.into_body().collect().await.unwrap().to_bytes();
            let html = String::from_utf8(body.to_vec()).unwrap();
            assert!(
                html.contains(r#"<script src="/app/offline-theme.js"></script>"#),
                "the theme script must be external: script-src is 'self'"
            );
            let inline = scraper::Selector::parse("script:not([src])").unwrap();
            assert!(
                scraper::Html::parse_document(&html)
                    .select(&inline)
                    .next()
                    .is_none(),
                "offline.html must not carry an inline script"
            );
            continue;
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
        "/app/offline-theme.js",
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
        include_str!("../web/static/manifest.webmanifest"),
    )
    .unwrap();
    fs::write(
        root.path().join("offline.html"),
        include_str!("../web/static/offline.html"),
    )
    .unwrap();
    fs::write(
        root.path().join("offline.css"),
        include_str!("../web/static/offline.css"),
    )
    .unwrap();
    fs::write(
        root.path().join("offline-theme.js"),
        include_str!("../web/static/offline-theme.js"),
    )
    .unwrap();
    fs::write(root.path().join("icons/icon-192.png"), b"png192").unwrap();
    fs::write(root.path().join("icons/icon-512.png"), b"png512").unwrap();
    fs::write(root.path().join("icons/icon-maskable-512.png"), b"pngmask").unwrap();
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
fn app_worker_uses_per_tab_updates_and_retains_only_public_immutable_assets() {
    let worker = include_str!("../web/static/service-worker.js");
    let install = worker
        .split("self.addEventListener(\"install\"")
        .nth(1)
        .and_then(|body| body.split("self.addEventListener(\"activate\"").next())
        .expect("worker should install its cached app shell");
    let activation = worker
        .split("self.addEventListener(\"activate\"")
        .nth(1)
        .and_then(|body| body.split("self.addEventListener(\"fetch\"").next())
        .expect("worker should activate and claim open tabs");
    let messaging = worker
        .split("self.addEventListener(\"message\"")
        .nth(1)
        .and_then(|body| body.split("self.addEventListener(\"install\"").next())
        .expect("worker should answer version and prune requests");

    assert!(install.contains("await self.skipWaiting()"));
    assert!(install.find("cache.put").unwrap() < install.find("self.skipWaiting").unwrap());
    assert!(activation.contains("await self.clients.claim()"));
    assert!(!activation.contains("postMessage"));
    assert!(messaging.contains("GET_VERSION"));
    assert!(messaging.contains("PRUNE_APP_CACHES"));
    for retired_message in [
        "PREPARE_UPDATE",
        "COMMIT_UPDATE",
        "CANCEL_UPDATE",
        "VALIDATE_UPDATE",
        "RELOAD_ACK",
        "QUERY_APP_VERSION",
    ] {
        assert!(
            !worker.contains(retired_message),
            "worker must not depend on retired cross-tab message {retired_message}"
        );
    }
    assert!(worker.contains("const MAX_RETAINED_CACHES = 3;"));
    assert!(worker.contains("MAX_RETAINED_CACHES - 1"));

    assert!(worker.contains("ignoreSearch: false"));
    assert!(worker.contains("response.url === url.href"));
    assert!(worker.contains("!response.redirected"));
    assert!(worker.contains("PUBLIC_ASSETS.includes(url.pathname)"));
    assert!(worker.contains("isImmutableAssetPath(url.pathname)"));
    assert!(worker.contains("retainedImmutableResponse(request)"));

    assert!(worker.contains("const PUBLIC_ASSETS = __APP_ASSETS__;"));
    assert!(worker.contains("__APP_VERSION__"));
    assert!(worker.contains("__APP_CODE_VERSION__"));
    for forbidden in ["/app/messages.json", "/api/", "/media/", "/app/projects"] {
        assert!(
            !worker.contains(forbidden),
            "worker template must not allowlist private or mutable path {forbidden}"
        );
    }
}
