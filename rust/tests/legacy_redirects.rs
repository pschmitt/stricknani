use axum::{
    body::Body,
    http::{Request, StatusCode},
};
use stricknani_server::{app_with_state, db};
use tower::ServiceExt;

async fn app() -> axum::Router {
    let pool = db::connect("sqlite::memory:")
        .await
        .expect("test database should migrate");
    app_with_state(db::AppState::new(pool))
}

async fn request(app: &axum::Router, method: &str, uri: &str) -> (StatusCode, String) {
    let response = app
        .clone()
        .oneshot(
            Request::builder()
                .method(method)
                .uri(uri)
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();
    let location = response
        .headers()
        .get("location")
        .and_then(|value| value.to_str().ok())
        .unwrap_or_default()
        .to_owned();
    (response.status(), location)
}

#[tokio::test]
async fn legacy_pages_redirect_permanently_to_the_app() {
    let app = app().await;
    for (legacy, target) in [
        ("/login", "/app/login"),
        ("/signup", "/app/signup"),
        ("/projects", "/app/projects"),
        ("/projects/", "/app/projects"),
        ("/projects/new", "/app/projects/new"),
        ("/projects/7", "/app/projects/7"),
        ("/projects/7/edit", "/app/projects/7/edit"),
        ("/yarns", "/app/yarns"),
        ("/yarns/", "/app/yarns"),
        ("/yarns/new", "/app/yarns/new"),
        ("/yarns/12", "/app/yarns/12"),
        ("/yarns/12/edit", "/app/yarns/12/edit"),
        ("/categories", "/app/categories"),
        ("/gauge", "/app/gauge"),
        ("/gauge/", "/app/gauge"),
        ("/imports", "/app/imports"),
        ("/account", "/app/account"),
        ("/accounts", "/app/admin/users"),
        ("/admin/users", "/app/admin/users"),
        ("/audit", "/app/admin/audit"),
        ("/admin/audit", "/app/admin/audit"),
    ] {
        assert_eq!(
            request(&app, "GET", legacy).await,
            (StatusCode::PERMANENT_REDIRECT, target.to_owned()),
            "{legacy}"
        );
    }
}

#[tokio::test]
async fn query_strings_survive_the_redirect() {
    let app = app().await;
    assert_eq!(
        request(&app, "GET", "/projects?q=socks&favorite=1").await,
        (
            StatusCode::PERMANENT_REDIRECT,
            "/app/projects?q=socks&favorite=1".to_owned()
        )
    );
}

#[tokio::test]
async fn root_still_lands_on_the_app() {
    let app = app().await;
    assert_eq!(
        request(&app, "GET", "/").await,
        (StatusCode::SEE_OTHER, "/app/".to_owned())
    );
}

#[tokio::test]
async fn non_numeric_ids_are_not_redirected() {
    let app = app().await;
    for path in ["/projects/abc", "/yarns/-1/edit", "/projects/1%2F..%2Fx"] {
        assert_eq!(
            request(&app, "GET", path).await.0,
            StatusCode::NOT_FOUND,
            "{path}"
        );
    }
}

#[tokio::test]
async fn removed_form_endpoints_and_assets_are_gone() {
    let app = app().await;
    for (method, path) in [
        ("POST", "/login"),
        ("POST", "/logout"),
        ("POST", "/projects/new"),
        ("POST", "/projects/1/save"),
        ("POST", "/projects/1/favorite"),
        ("POST", "/projects/1/archive"),
        ("POST", "/yarns/1/archive"),
        ("POST", "/gauge/calculate"),
        ("POST", "/signup"),
        ("GET", "/language/en"),
        ("GET", "/static/css/app.css"),
        ("GET", "/static/vendor/anything.css"),
    ] {
        let (status, _) = request(&app, method, path).await;
        assert!(
            status == StatusCode::NOT_FOUND || status == StatusCode::METHOD_NOT_ALLOWED,
            "{method} {path} -> {status}"
        );
    }
}
