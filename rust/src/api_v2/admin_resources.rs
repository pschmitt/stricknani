//! Read-only cross-owner resource views for active administrators.

use axum::{
    Json, Router,
    extract::{Path, State},
    http::StatusCode,
    response::{IntoResponse, Response},
    routing::get,
};
use serde::Serialize;

use super::{ApiV2State, Envelope, access, api_error};

#[derive(Serialize)]
struct ResourceData {
    kind: &'static str,
    id: i64,
    name: String,
    owner: Option<crate::admin_resource_service::ResourceOwner>,
    resource: serde_json::Value,
    read_only: bool,
}

pub(super) fn router() -> Router<ApiV2State> {
    Router::new()
        .route("/admin/resources/{kind}/{id}", get(get_resource))
        .route(
            "/admin/resources/{kind}/{id}/media/{media_kind}/{media_id}",
            get(get_resource_media),
        )
        .route(
            "/admin/resources/{kind}/{id}/media/{media_kind}/{media_id}/thumbnail",
            get(get_resource_media_thumbnail),
        )
}

async fn get_resource(
    State(state): State<ApiV2State>,
    headers: axum::http::HeaderMap,
    Path((kind, id)): Path<(String, i64)>,
) -> Response {
    let actor_id = match access::admin_user(&state.app, &headers).await {
        Ok(actor_id) => actor_id,
        Err(response) => return *response,
    };
    let Some(kind) = crate::admin_resource_service::ResourceKind::parse(&kind) else {
        return not_found();
    };
    match crate::admin_resource_service::load(&state.app, actor_id, kind, id).await {
        Ok(resource) => {
            let serialized = match resource.resource {
                crate::admin_resource_service::ResourceData::Project(project) => {
                    serde_json::to_value(super::projects::ProjectDto::from(project))
                }
                crate::admin_resource_service::ResourceData::Yarn(yarn) => {
                    serde_json::to_value(super::yarns::YarnDto::from(yarn))
                }
                crate::admin_resource_service::ResourceData::Category(category) => {
                    serde_json::to_value(category)
                }
            };
            let value = match serialized {
                Ok(value) => value,
                Err(_) => return internal_error(),
            };
            let mut value = value;
            rewrite_media_urls(&mut value, kind.as_str(), resource.id);
            Json(Envelope {
                data: ResourceData {
                    kind: kind.as_str(),
                    id: resource.id,
                    name: resource.name,
                    owner: resource.owner,
                    resource: value,
                    read_only: true,
                },
            })
            .into_response()
        }
        Err(crate::admin_resource_service::ResourceError::Forbidden) => forbidden(),
        Err(crate::admin_resource_service::ResourceError::NotFound) => not_found(),
        Err(crate::admin_resource_service::ResourceError::Database) => internal_error(),
    }
}

async fn get_resource_media(
    State(state): State<ApiV2State>,
    headers: axum::http::HeaderMap,
    Path((kind, id, media_kind, media_id)): Path<(String, i64, String, i64)>,
) -> Response {
    serve_resource_media(&state, &headers, &kind, &media_kind, id, media_id, false).await
}

async fn get_resource_media_thumbnail(
    State(state): State<ApiV2State>,
    headers: axum::http::HeaderMap,
    Path((kind, id, media_kind, media_id)): Path<(String, i64, String, i64)>,
) -> Response {
    serve_resource_media(&state, &headers, &kind, &media_kind, id, media_id, true).await
}

async fn serve_resource_media(
    state: &ApiV2State,
    headers: &axum::http::HeaderMap,
    kind: &str,
    media_kind: &str,
    resource_id: i64,
    media_id: i64,
    thumbnail: bool,
) -> Response {
    let actor_id = match access::admin_user(&state.app, headers).await {
        Ok(actor_id) => actor_id,
        Err(response) => return *response,
    };
    match crate::media::serve_admin_resource_media(
        &state.app,
        actor_id,
        kind,
        media_kind,
        resource_id,
        media_id,
        thumbnail,
    )
    .await
    {
        Ok(response) => response,
        Err(crate::media::AdminResourceMediaError::Forbidden) => forbidden(),
        Err(crate::media::AdminResourceMediaError::NotFound) => not_found(),
        Err(crate::media::AdminResourceMediaError::Database) => internal_error(),
        Err(crate::media::AdminResourceMediaError::Unavailable) => api_error(
            StatusCode::SERVICE_UNAVAILABLE,
            "media_unavailable",
            "Media storage is unavailable",
        ),
    }
}

fn rewrite_media_urls(resource: &mut serde_json::Value, kind: &str, resource_id: i64) {
    rewrite_nested_media(resource, kind, resource_id);
}

fn rewrite_nested_media(value: &mut serde_json::Value, kind: &str, resource_id: i64) {
    match value {
        serde_json::Value::Object(object) => {
            for (key, child) in object.iter_mut() {
                if matches!(
                    (kind, key.as_str()),
                    ("project", "images" | "attachments") | ("yarn", "photos")
                ) && let Some(items) = child.as_array_mut()
                {
                    let media_kind = match (kind, key.as_str()) {
                        ("project", "images") => "image",
                        ("project", "attachments") => "attachment",
                        ("yarn", "photos") => "photo",
                        _ => continue,
                    };
                    for item in items {
                        rewrite_media_item(item, kind, resource_id, media_kind);
                    }
                } else {
                    rewrite_nested_media(child, kind, resource_id);
                }
            }
        }
        serde_json::Value::Array(items) => {
            for item in items {
                rewrite_nested_media(item, kind, resource_id);
            }
        }
        _ => {}
    }
}

fn rewrite_media_item(
    item: &mut serde_json::Value,
    kind: &str,
    resource_id: i64,
    media_kind: &str,
) {
    let Some(media_id) = item.get("id").and_then(serde_json::Value::as_i64) else {
        return;
    };
    if let Some(url) =
        crate::media::admin_resource_media_url(kind, resource_id, media_kind, media_id, false)
        && let Some(url_field) = item.get_mut("url")
    {
        *url_field = serde_json::Value::String(url);
    }
    if item.get("thumbnail_url").is_some()
        && let Some(url) =
            crate::media::admin_resource_media_url(kind, resource_id, media_kind, media_id, true)
        && let Some(url_field) = item.get_mut("thumbnail_url")
    {
        *url_field = serde_json::Value::String(url);
    }
}

fn forbidden() -> Response {
    api_error(
        StatusCode::FORBIDDEN,
        "admin_required",
        "Administrator access is required",
    )
}

fn not_found() -> Response {
    api_error(StatusCode::NOT_FOUND, "not_found", "Resource not found")
}

fn internal_error() -> Response {
    api_error(
        StatusCode::INTERNAL_SERVER_ERROR,
        "internal_error",
        "Request failed",
    )
}
