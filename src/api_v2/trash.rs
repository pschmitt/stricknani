//! The trash: list, restore and delete forever.
//!
//! `GET /trash` lists the caller's trashed projects and yarns, plus what the
//! caller trashed in somebody else's project or yarn (`scope=all` lists every
//! account's, administrators only; `can_restore` tells whether the caller may
//! act on an item). `POST /trash/{kind}/{id}/restore`
//! and `DELETE /trash/{kind}/{id}` act on one item; `kind` is `projects`,
//! `yarns`, `categories`, `steps`, `images`, `attachments` or `photos`. Only
//! the owner and administrators may; anyone else gets `404`. Restoring a child
//! whose project or yarn is in the trash answers `409 parent_in_trash`, and a
//! category whose name is taken again `409 name_taken`.

use axum::{
    Json, Router,
    extract::{Path, Query, State},
    http::{HeaderMap, StatusCode},
    response::{IntoResponse, Response},
    routing::{delete, get, post},
};
use serde::{Deserialize, Serialize};

use crate::trash::{self, TrashError, TrashItem, TrashKind};

use super::{
    ApiV2State,
    access::{resource_mutation_user, resource_user},
    api_error,
};

pub(super) fn router() -> Router<ApiV2State> {
    Router::new()
        .route("/trash", get(list_trash))
        .route("/trash/{kind}/{id}", delete(purge_item))
        .route("/trash/{kind}/{id}/restore", post(restore_item))
        .route("/trash/{kind}/{id}/thumbnail", get(trashed_thumbnail))
}

#[derive(Debug, Deserialize)]
struct TrashQuery {
    scope: Option<String>,
}

#[derive(Serialize)]
struct TrashList {
    data: Vec<TrashItem>,
    meta: TrashMeta,
}

#[derive(Serialize)]
struct TrashMeta {
    scope: crate::resource_access::Scope,
    retention_days: i64,
}

fn trash_error(error: TrashError) -> Response {
    match error {
        TrashError::NotFound => api_error(
            StatusCode::NOT_FOUND,
            "not_found",
            "Item not found in the trash",
        ),
        TrashError::Invalid(message) => api_error(
            StatusCode::UNPROCESSABLE_ENTITY,
            "validation_error",
            message,
        ),
        TrashError::ParentInTrash => api_error(
            StatusCode::CONFLICT,
            "parent_in_trash",
            "Restore the project or yarn it belongs to first",
        ),
        TrashError::NameTaken => api_error(
            StatusCode::CONFLICT,
            "name_taken",
            "A category with this name exists already",
        ),
        TrashError::Database => api_error(
            StatusCode::INTERNAL_SERVER_ERROR,
            "internal_error",
            "Request failed",
        ),
    }
}

fn parse_kind(kind: &str) -> Result<TrashKind, Box<Response>> {
    TrashKind::from_segment(kind).ok_or_else(|| Box::new(trash_error(TrashError::NotFound)))
}

async fn list_trash(
    State(state): State<ApiV2State>,
    headers: HeaderMap,
    Query(query): Query<TrashQuery>,
) -> Response {
    let user_id = match resource_user(&state.app, &headers).await {
        Ok(user_id) => user_id,
        Err(response) => return *response,
    };
    match trash::list(&state.app, user_id, query.scope.as_deref()).await {
        Ok((scope, data)) => Json(TrashList {
            data,
            meta: TrashMeta {
                scope,
                retention_days: trash::RETENTION_DAYS,
            },
        })
        .into_response(),
        Err(error) => trash_error(error),
    }
}

async fn restore_item(
    State(state): State<ApiV2State>,
    headers: HeaderMap,
    Path((kind, id)): Path<(String, i64)>,
) -> Response {
    let user_id = match resource_mutation_user(&state.app, &headers).await {
        Ok(user_id) => user_id,
        Err(response) => return *response,
    };
    let kind = match parse_kind(&kind) {
        Ok(kind) => kind,
        Err(response) => return *response,
    };
    match trash::restore(&state.app, user_id, kind, id).await {
        Ok(()) => StatusCode::NO_CONTENT.into_response(),
        Err(error) => trash_error(error),
    }
}

async fn purge_item(
    State(state): State<ApiV2State>,
    headers: HeaderMap,
    Path((kind, id)): Path<(String, i64)>,
) -> Response {
    let user_id = match resource_mutation_user(&state.app, &headers).await {
        Ok(user_id) => user_id,
        Err(response) => return *response,
    };
    let kind = match parse_kind(&kind) {
        Ok(kind) => kind,
        Err(response) => return *response,
    };
    match trash::purge(&state.app, user_id, kind, id).await {
        Ok(()) => StatusCode::NO_CONTENT.into_response(),
        Err(error) => trash_error(error),
    }
}

/// The thumbnail of an image or photo in the trash (owner and administrators).
async fn trashed_thumbnail(
    State(state): State<ApiV2State>,
    headers: HeaderMap,
    Path((kind, id)): Path<(String, i64)>,
) -> Response {
    let user_id = match resource_user(&state.app, &headers).await {
        Ok(user_id) => user_id,
        Err(response) => return *response,
    };
    let namespace = match kind.as_str() {
        "images" => "image",
        "photos" => "photo",
        _ => return trash_error(TrashError::NotFound),
    };
    crate::media::serve_trashed_media_thumbnail(&state.app, user_id, namespace, id).await
}
