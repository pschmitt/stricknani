use axum::{
    Router,
    body::Body,
    http::{Request, StatusCode},
    middleware,
    routing::get,
};
use stricknani_server::security::{TrustedHosts, security_headers, with_host_validation};
use tower::ServiceExt;

#[tokio::test]
async fn security_headers_cache_static_assets_and_protect_dynamic_and_media_responses() {
    let app = Router::new()
        .route("/", get(|| async { "page" }))
        .route("/api/v1/private", get(|| async { "private" }))
        .route("/media/image", get(|| async { "media" }))
        .route("/app/messages.json", get(|| async { "{}" }))
        .route(
            "/static/app.css",
            get(|| async { "body { color: black; }" }),
        )
        .layer(middleware::from_fn(security_headers));

    let dynamic = app
        .clone()
        .oneshot(Request::get("/").body(Body::empty()).unwrap())
        .await
        .unwrap();
    assert_eq!(dynamic.headers()["cache-control"], "private, no-store");
    assert_eq!(dynamic.headers()["x-content-type-options"], "nosniff");
    assert_eq!(dynamic.headers()["x-frame-options"], "DENY");
    assert_eq!(
        dynamic.headers()["referrer-policy"],
        "strict-origin-when-cross-origin"
    );
    assert!(
        dynamic.headers()["content-security-policy"]
            .to_str()
            .unwrap()
            .contains("font-src 'self' data:")
    );
    assert!(
        dynamic.headers()["content-security-policy"]
            .to_str()
            .unwrap()
            .contains("frame-ancestors 'none'")
    );

    let private = app
        .clone()
        .oneshot(Request::get("/api/v1/private").body(Body::empty()).unwrap())
        .await
        .unwrap();
    assert_eq!(private.headers()["cache-control"], "private, no-store");
    let media = app
        .clone()
        .oneshot(Request::get("/media/image").body(Body::empty()).unwrap())
        .await
        .unwrap();
    assert_eq!(media.headers()["cache-control"], "private, no-store");
    let static_asset = app
        .clone()
        .oneshot(Request::get("/static/app.css").body(Body::empty()).unwrap())
        .await
        .unwrap();
    assert_eq!(static_asset.status(), StatusCode::OK);
    assert_eq!(
        static_asset.headers()["cache-control"],
        "public, max-age=3600, stale-while-revalidate=86400"
    );

    let catalog = app
        .oneshot(
            Request::get("/app/messages.json")
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(catalog.status(), StatusCode::OK);
    assert_eq!(catalog.headers()["cache-control"], "private, no-store");
}

#[tokio::test]
async fn served_host_wrapper_rejects_missing_and_spoofed_hosts_but_ignores_forwarded_host() {
    let app = Router::new().route("/", get(|| async { "ok" }));
    let app = with_host_validation(app, TrustedHosts::parse("rust.example.test").unwrap());

    let missing = app
        .clone()
        .oneshot(Request::get("/").body(Body::empty()).unwrap())
        .await
        .unwrap();
    assert_eq!(missing.status(), StatusCode::BAD_REQUEST);
    assert!(missing.headers().contains_key("content-security-policy"));

    let spoofed = app
        .clone()
        .oneshot(
            Request::get("/")
                .header("host", "rust.example.test.attacker.test")
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(spoofed.status(), StatusCode::MISDIRECTED_REQUEST);

    let trusted = app
        .oneshot(
            Request::get("/")
                .header("host", "RUST.example.test:8088")
                .header("x-forwarded-host", "attacker.test")
                .header("x-forwarded-proto", "https")
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(trusted.status(), StatusCode::OK);
}
