//! Permanent redirects from the removed server-rendered pages to the SvelteKit app under `/app/`.
//!
//! Bookmarks, old emails and the Android app's web links point at the pre-`/app/` paths. Only
//! `GET` is routed: the old form `POST` endpoints no longer exist.

use axum::{
    Router,
    extract::{OriginalUri, Path},
    http::Uri,
    response::{IntoResponse, Redirect, Response},
    routing::get,
};

/// Redirects for every legacy page path, merged into the main router.
pub fn router() -> Router {
    let mut router = Router::new();
    for (legacy, target) in [
        ("/login", "/app/login"),
        ("/signup", "/app/signup"),
        ("/projects", "/app/projects"),
        ("/projects/", "/app/projects"),
        ("/projects/new", "/app/projects/new"),
        ("/yarns", "/app/yarns"),
        ("/yarns/", "/app/yarns"),
        ("/yarns/new", "/app/yarns/new"),
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
        router = router.route(legacy, get(move |uri| fixed(uri, target)));
    }
    router
        .route(
            "/projects/{id}",
            get(|id, uri| item(id, uri, "projects", "")),
        )
        .route(
            "/projects/{id}/edit",
            get(|id, uri| item(id, uri, "projects", "/edit")),
        )
        .route("/yarns/{id}", get(|id, uri| item(id, uri, "yarns", "")))
        .route(
            "/yarns/{id}/edit",
            get(|id, uri| item(id, uri, "yarns", "/edit")),
        )
}

async fn fixed(OriginalUri(uri): OriginalUri, target: &'static str) -> Response {
    permanent(target, &uri)
}

async fn item(
    Path(id): Path<String>,
    OriginalUri(uri): OriginalUri,
    kind: &'static str,
    suffix: &'static str,
) -> Response {
    // Only plain ids are forwarded so the target path cannot be steered by the request.
    if id.is_empty() || !id.bytes().all(|byte| byte.is_ascii_digit()) {
        return axum::http::StatusCode::NOT_FOUND.into_response();
    }
    permanent(&format!("/app/{kind}/{id}{suffix}"), &uri)
}

fn permanent(target: &str, uri: &Uri) -> Response {
    let mut location = target.to_owned();
    if let Some(query) = uri.query() {
        location.push('?');
        location.push_str(query);
    }
    Redirect::permanent(&location).into_response()
}
