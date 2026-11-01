use std::fs;

use axum::{Router, body::Body, http::Request, middleware};
use http_body_util::BodyExt;
use stricknani_server::{frontend, security::security_headers};
use tempfile::TempDir;
use tower::ServiceExt;

struct BuildFixture {
    root: TempDir,
}

impl BuildFixture {
    fn new() -> Self {
        let root = tempfile::tempdir().expect("build root should be created");
        fs::create_dir_all(root.path().join("_app/immutable/assets"))
            .expect("asset tree should be created");
        fs::write(
            root.path().join("200.html"),
            r#"<!doctype html><html><head></head><body><div id="svelte"></div><script>boot()</script></body></html>"#,
        )
        .expect("fallback shell should be written");
        fs::write(
            root.path().join("csp.json"),
            r#"{"script_hashes":["sha256-AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="],"style_hashes":["sha256-AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="],"style_attribute_hashes":["sha256-S8qMpvofolR8Mpjy4kQvEm7m1q8clzU4dfDH0AmvZjo="]}"#,
        )
        .expect("CSP manifest should be written");
        fs::write(
            root.path().join("_app/immutable/assets/app.css"),
            "body { color: black; }",
        )
        .expect("stylesheet should be written");
        fs::write(
            root.path().join("_app/immutable/entry.js"),
            "export const app = true;",
        )
        .expect("script should be written");
        fs::write(root.path().join("messages.json"), "{}")
            .expect("public translations should be written");
        fs::write(root.path().join("favicon.svg"), "<svg></svg>")
            .expect("favicon should be written");
        Self { root }
    }

    fn router(&self) -> Router {
        frontend::router(self.root.path().to_path_buf())
            .layer(middleware::from_fn(security_headers))
    }
}

async fn request(app: Router, uri: &str) -> axum::response::Response {
    app.oneshot(
        Request::get(uri)
            .body(Body::empty())
            .expect("request should be valid"),
    )
    .await
    .expect("router should respond")
}

#[tokio::test]
async fn known_app_routes_use_a_private_shell_and_manifest_hashes() {
    let fixture = BuildFixture::new();
    let app = fixture.router();

    for path in [
        "/app",
        "/app/",
        "/app/projects",
        "/app/projects/42/edit",
        "/app/create",
        "/app/more",
        "/app/admin",
        "/app/audit",
    ] {
        let response = request(app.clone(), path).await;
        assert_eq!(response.status(), axum::http::StatusCode::OK, "{path}");
        assert_eq!(response.headers()["cache-control"], "private, no-store");
        assert_eq!(
            response.headers()["content-security-policy"],
            "default-src 'self'; base-uri 'self'; object-src 'none'; frame-ancestors 'none'; form-action 'self'; img-src 'self' data: blob:; style-src 'self' 'sha256-AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA='; style-src-attr 'unsafe-hashes' 'sha256-S8qMpvofolR8Mpjy4kQvEm7m1q8clzU4dfDH0AmvZjo='; script-src 'self' 'sha256-AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA='; font-src 'self' data:; connect-src 'self'"
        );
        assert_eq!(
            response.headers()["content-type"],
            "text/html; charset=utf-8"
        );
        let body = response.into_body().collect().await.unwrap().to_bytes();
        assert!(String::from_utf8_lossy(&body).contains("id=\"svelte\""));
    }
}

#[tokio::test]
async fn unknown_app_paths_do_not_receive_the_shell() {
    let fixture = BuildFixture::new();
    for path in [
        "/app/admin/unknown",
        "/app/projects/not-an-id",
        "/app/projects/42/delete/extra",
    ] {
        let response = request(fixture.router(), path).await;
        assert_eq!(
            response.status(),
            axum::http::StatusCode::NOT_FOUND,
            "{path}"
        );
    }
}

#[tokio::test]
async fn app_assets_keep_file_mime_types_and_missing_assets_stay_404() {
    let fixture = BuildFixture::new();
    let css = request(fixture.router(), "/app/_app/immutable/assets/app.css").await;
    assert_eq!(css.status(), axum::http::StatusCode::OK);
    assert_eq!(css.headers()["content-type"], "text/css");
    assert!(
        css.headers()["cache-control"]
            .to_str()
            .unwrap()
            .contains("public")
    );

    let js = request(fixture.router(), "/app/_app/immutable/entry.js").await;
    assert_eq!(js.status(), axum::http::StatusCode::OK);
    assert!(
        js.headers()["content-type"]
            .to_str()
            .unwrap()
            .contains("javascript")
    );

    let missing = request(fixture.router(), "/app/_app/immutable/missing.js").await;
    assert_eq!(missing.status(), axum::http::StatusCode::NOT_FOUND);

    let traversal = request(fixture.router(), "/app/_app/%2e%2e/200.html").await;
    assert_eq!(traversal.status(), axum::http::StatusCode::NOT_FOUND);

    let manifest = request(fixture.router(), "/app/csp.json").await;
    assert_eq!(manifest.status(), axum::http::StatusCode::NOT_FOUND);

    let messages = request(fixture.router(), "/app/messages.json").await;
    assert_eq!(messages.status(), axum::http::StatusCode::OK);
    assert_eq!(
        messages.headers()["cache-control"],
        "public, max-age=3600, stale-while-revalidate=86400"
    );
    let favicon = request(fixture.router(), "/app/favicon.svg").await;
    assert_eq!(favicon.status(), axum::http::StatusCode::OK);
    assert_eq!(favicon.headers()["content-type"], "image/svg+xml");
}

#[tokio::test]
async fn app_paths_do_not_capture_api_media_or_health_routes() {
    let fixture = BuildFixture::new();
    let app = Router::new()
        .route("/api/v2/ping", axum::routing::get(|| async { "api" }))
        .route("/healthz", axum::routing::get(|| async { "ok" }))
        .merge(fixture.router())
        .layer(middleware::from_fn(security_headers));
    for (path, expected) in [("/api/v2/ping", "api"), ("/healthz", "ok")] {
        let response = request(app.clone(), path).await;
        assert_eq!(
            response.headers()["content-security-policy"],
            "default-src 'self'; base-uri 'self'; object-src 'none'; frame-ancestors 'none'; form-action 'self'; img-src 'self' data: blob:; style-src 'self'; script-src 'self'; font-src 'self' data:; connect-src 'self'"
        );
        let body = response.into_body().collect().await.unwrap().to_bytes();
        assert_eq!(String::from_utf8_lossy(&body), expected);
    }
    let media = request(app, "/media/unknown").await;
    assert_eq!(media.status(), axum::http::StatusCode::NOT_FOUND);
}

#[tokio::test]
async fn invalid_csp_manifest_fails_closed_without_serving_html() {
    let fixture = BuildFixture::new();
    let manifest = fixture.root.path().join("csp.json");
    let too_many_hashes = format!(
        "{{\"script_hashes\":[{}],\"style_hashes\":[]}}",
        std::iter::repeat_n(
            "\"sha256-AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=\"",
            65
        )
        .collect::<Vec<_>>()
        .join(",")
    );
    let too_many_style_attribute_hashes = format!(
        "{{\"script_hashes\":[],\"style_hashes\":[],\"style_attribute_hashes\":[{}]}}",
        std::iter::repeat_n(
            "\"sha256-AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=\"",
            65
        )
        .collect::<Vec<_>>()
        .join(",")
    );
    for invalid in [
        r#"{"script_hashes":["sha256-short"],"style_hashes":[]}"#,
        r#"{"script_hashes":[],"style_hashes":[],"style_attribute_hashes":["sha256-short"]}"#,
        too_many_hashes.as_str(),
        too_many_style_attribute_hashes.as_str(),
    ] {
        fs::write(&manifest, invalid).unwrap();
        let response = request(fixture.router(), "/app").await;
        assert_eq!(
            response.status(),
            axum::http::StatusCode::SERVICE_UNAVAILABLE
        );
        let body = response.into_body().collect().await.unwrap().to_bytes();
        assert!(body.is_empty());
    }
}

#[tokio::test]
async fn old_manifest_defaults_to_disallowing_style_attributes() {
    let fixture = BuildFixture::new();
    fs::write(
        fixture.root.path().join("csp.json"),
        r#"{"script_hashes":[],"style_hashes":[]}"#,
    )
    .unwrap();

    let response = request(fixture.router(), "/app").await;
    assert_eq!(response.status(), axum::http::StatusCode::OK);
    assert_eq!(
        response.headers()["content-security-policy"],
        "default-src 'self'; base-uri 'self'; object-src 'none'; frame-ancestors 'none'; form-action 'self'; img-src 'self' data: blob:; style-src 'self'; style-src-attr 'none'; script-src 'self'; font-src 'self' data:; connect-src 'self'"
    );
}

#[tokio::test]
async fn missing_or_invalid_build_does_not_make_a_shell_route() {
    let root = tempfile::tempdir().unwrap();
    let app = frontend::router(root.path().to_path_buf());
    let response = request(app, "/app").await;
    assert_eq!(
        response.status(),
        axum::http::StatusCode::SERVICE_UNAVAILABLE
    );
}
