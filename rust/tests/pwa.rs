use axum::body::Body;
use axum::http::{Request, StatusCode};
use http_body_util::BodyExt;
use serde_json::Value;
use stricknani_server::pwa;
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
    let body = response.into_body().collect().await.unwrap().to_bytes();
    assert!(String::from_utf8_lossy(&body).contains("stricknani-pwa-v1"));
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
    assert!(html.contains("You're offline"));
    assert!(html.contains("Keine Verbindung"));
    assert!(html.contains("Your private data is not included"));
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
