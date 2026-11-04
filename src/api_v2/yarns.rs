//! Same-origin browser yarn resource routes.

use std::collections::BTreeMap;

use axum::{
    Json, Router,
    extract::{
        DefaultBodyLimit, Path, Query, State, rejection::JsonRejection, rejection::QueryRejection,
    },
    http::{HeaderMap, StatusCode},
    response::{IntoResponse, Response},
    routing::get,
};
use serde::{Deserialize, Serialize};

use crate::yarns as yarn_service;
use yarn_service::{ListQuery, YarnError, YarnListItemResponse, YarnResponse, YarnWriteRequest};

use super::{
    ApiV2State, Envelope,
    access::{resource_mutation_user, resource_user},
};

pub(super) fn router() -> Router<ApiV2State> {
    Router::new()
        .route("/yarns", get(list_yarns).post(create_yarn))
        .route(
            "/yarns/{yarn_id}",
            get(get_yarn).put(update_yarn).delete(delete_yarn),
        )
        .route(
            "/yarns/{yarn_id}/favorite",
            axum::routing::put(favorite_yarn).delete(unfavorite_yarn),
        )
        .layer(DefaultBodyLimit::max(2 * 1024 * 1024))
}

#[derive(Debug, Deserialize)]
struct YarnPageQuery {
    page: Option<i64>,
    favorite: Option<bool>,
    q: Option<String>,
    scope: Option<String>,
    owner_id: Option<i64>,
}

#[derive(Serialize)]
struct ListEnvelope<T> {
    data: Vec<T>,
    meta: PageMeta,
}

#[derive(Serialize)]
struct PageMeta {
    page: i64,
    per_page: i64,
    has_more: bool,
    scope: crate::resource_access::Scope,
}

#[derive(Serialize)]
pub(super) struct YarnDto {
    id: i64,
    name: String,
    description: Option<String>,
    description_html: Option<String>,
    brand: Option<String>,
    colorway: Option<String>,
    dye_lot: Option<String>,
    fiber_content: Option<String>,
    weight_category: Option<String>,
    recommended_needles: Option<String>,
    weight_grams: Option<i64>,
    length_meters: Option<i64>,
    notes: Option<String>,
    notes_html: Option<String>,
    link: Option<String>,
    link_archive: Option<String>,
    is_ai_enhanced: bool,
    is_favorite: bool,
    created_at: String,
    updated_at: String,
    project_ids: Vec<i64>,
    photos: Vec<serde_json::Value>,
    preview_url: Option<String>,
    owner: Option<crate::resource_access::OwnerInfo>,
}

impl From<YarnResponse> for YarnDto {
    fn from(yarn: YarnResponse) -> Self {
        Self {
            id: yarn.id,
            name: yarn.name,
            description_html: yarn.description.as_deref().map(crate::web::rich_text),
            description: yarn.description,
            brand: yarn.brand,
            colorway: yarn.colorway,
            dye_lot: yarn.dye_lot,
            fiber_content: yarn.fiber_content,
            weight_category: yarn.weight_category,
            recommended_needles: yarn.recommended_needles,
            weight_grams: yarn.weight_grams,
            length_meters: yarn.length_meters,
            notes_html: yarn.notes.as_deref().map(crate::web::rich_text),
            notes: yarn.notes,
            link: yarn.link,
            link_archive: yarn.link_archive,
            is_ai_enhanced: yarn.is_ai_enhanced,
            is_favorite: yarn.is_favorite,
            created_at: yarn.created_at,
            updated_at: yarn.updated_at,
            project_ids: yarn.project_ids,
            photos: yarn.photos,
            preview_url: None,
            owner: yarn.owner,
        }
    }
}

pub(super) async fn yarn_dto_v2(
    state: &ApiV2State,
    user_id: i64,
    yarn: YarnResponse,
) -> Result<YarnDto, sqlx::Error> {
    let permit = state
        .sync_projection_slots
        .clone()
        .acquire_owned()
        .await
        .map_err(|_| sqlx::Error::Protocol("yarn projection capacity closed".into()))?;
    let yarn_id = yarn.id;
    let media_owner = yarn.owner.as_ref().map_or(user_id, |owner| owner.id);
    let (photos, preview_url) =
        crate::media::yarn_photos_v2(&state.app.pool, media_owner, yarn_id).await?;
    tokio::task::spawn_blocking(move || {
        let _permit = permit;
        yarn_dto_v2_with_media(yarn, photos, preview_url)
    })
    .await
    .map_err(|error| sqlx::Error::Protocol(error.to_string()))
}

pub(super) fn yarn_dto_v2_with_media(
    yarn: YarnResponse,
    photos: Vec<serde_json::Value>,
    preview_url: Option<String>,
) -> YarnDto {
    let mut dto = YarnDto::from(yarn);
    dto.photos = photos;
    dto.preview_url = preview_url;
    dto
}

#[derive(Serialize)]
struct ErrorBody {
    error: ApiError,
}

#[derive(Serialize)]
struct ApiError {
    code: &'static str,
    message: &'static str,
    #[serde(skip_serializing_if = "Option::is_none")]
    fields: Option<BTreeMap<&'static str, &'static str>>,
    #[serde(skip_serializing_if = "Option::is_none")]
    current: Option<YarnDto>,
}

fn yarn_error(error: YarnError) -> Response {
    let (status, code, message, fields, current) = match error {
        YarnError::NotFound => (
            StatusCode::NOT_FOUND,
            "not_found",
            "Yarn not found",
            None,
            None,
        ),
        YarnError::Database => (
            StatusCode::INTERNAL_SERVER_ERROR,
            "internal_error",
            "Request failed",
            None,
            None,
        ),
        YarnError::Invalid("Invalid expected_updated_at") => (
            StatusCode::UNPROCESSABLE_ENTITY,
            "validation_error",
            "Yarn fields are invalid",
            Some(BTreeMap::from([(
                "expected_updated_at",
                "Invalid timestamp",
            )])),
            None,
        ),
        YarnError::Invalid(_) => (
            StatusCode::UNPROCESSABLE_ENTITY,
            "validation_error",
            "Yarn fields are invalid",
            None,
            None,
        ),
        YarnError::Conflict(yarn) => (
            StatusCode::CONFLICT,
            "conflict",
            "Yarn changed since it was loaded",
            None,
            Some((*yarn).into()),
        ),
    };
    (
        status,
        Json(ErrorBody {
            error: ApiError {
                code,
                message,
                fields,
                current,
            },
        }),
    )
        .into_response()
}

async fn list_yarns(
    State(state): State<ApiV2State>,
    headers: HeaderMap,
    query: Result<Query<YarnPageQuery>, QueryRejection>,
) -> Response {
    let user_id = match resource_user(&state.app, &headers).await {
        Ok(user_id) => user_id,
        Err(error) => return *error,
    };
    let Query(query) = match query {
        Ok(query) => query,
        Err(_) => return validation_error("Invalid query", None),
    };
    match yarn_service::list_for_owner(
        &state.app,
        user_id,
        ListQuery {
            page: query.page,
            favorite: query.favorite,
            q: query.q,
            scope: query.scope,
            owner_id: query.owner_id,
        },
    )
    .await
    {
        Ok(page) => {
            let ids: Vec<_> = page.items.iter().map(|yarn| yarn.id).collect();
            let previews =
                match crate::media::yarn_previews_v2(&state.app.pool, user_id, &ids).await {
                    Ok(previews) => previews,
                    Err(_) => return yarn_error(YarnError::Database),
                };
            let data: Vec<YarnListItemResponse> = page
                .items
                .into_iter()
                .map(|mut yarn| {
                    yarn.preview_url = previews.get(&yarn.id).cloned();
                    yarn
                })
                .collect();
            Json(ListEnvelope {
                data,
                meta: PageMeta {
                    page: page.page,
                    per_page: page.per_page,
                    has_more: page.has_more,
                    scope: page.scope,
                },
            })
            .into_response()
        }
        Err(error) => yarn_error(error),
    }
}

async fn get_yarn(
    State(state): State<ApiV2State>,
    headers: HeaderMap,
    Path(yarn_id): Path<i64>,
) -> Response {
    let user_id = match resource_user(&state.app, &headers).await {
        Ok(user_id) => user_id,
        Err(error) => return *error,
    };
    match yarn_service::load_for_owner(&state.app, user_id, yarn_id).await {
        Ok(Some(yarn)) => match yarn_dto_v2(&state, user_id, yarn).await {
            Ok(yarn) => Json(Envelope { data: yarn }).into_response(),
            Err(_) => yarn_error(YarnError::Database),
        },
        Ok(None) => yarn_error(YarnError::NotFound),
        Err(_) => yarn_error(YarnError::Database),
    }
}

async fn create_yarn(
    State(state): State<ApiV2State>,
    headers: HeaderMap,
    payload: Result<Json<YarnWriteRequest>, JsonRejection>,
) -> Response {
    let user_id = match resource_mutation_user(&state.app, &headers).await {
        Ok(user_id) => user_id,
        Err(error) => return *error,
    };
    let Json(payload) = match payload {
        Ok(payload) => payload,
        Err(rejection) => return json_rejection(rejection),
    };
    if let Some(response) = validate_yarn(&payload) {
        return response;
    }
    match yarn_service::create_for_owner(&state.app, user_id, payload).await {
        Ok(yarn) => match yarn_dto_v2(&state, user_id, yarn).await {
            Ok(yarn) => (StatusCode::CREATED, Json(Envelope { data: yarn })).into_response(),
            Err(_) => yarn_error(YarnError::Database),
        },
        Err(error) => yarn_error(error),
    }
}

async fn update_yarn(
    State(state): State<ApiV2State>,
    headers: HeaderMap,
    Path(yarn_id): Path<i64>,
    payload: Result<Json<YarnWriteRequest>, JsonRejection>,
) -> Response {
    let user_id = match resource_mutation_user(&state.app, &headers).await {
        Ok(user_id) => user_id,
        Err(error) => return *error,
    };
    let Json(payload) = match payload {
        Ok(payload) => payload,
        Err(rejection) => return json_rejection(rejection),
    };
    if let Some(response) = validate_yarn(&payload) {
        return response;
    }
    match yarn_service::update_for_owner(&state.app, user_id, yarn_id, payload).await {
        Ok(yarn) => match yarn_dto_v2(&state, user_id, yarn).await {
            Ok(yarn) => Json(Envelope { data: yarn }).into_response(),
            Err(_) => yarn_error(YarnError::Database),
        },
        Err(error) => yarn_error(error),
    }
}

async fn delete_yarn(
    State(state): State<ApiV2State>,
    headers: HeaderMap,
    Path(yarn_id): Path<i64>,
) -> Response {
    let user_id = match resource_mutation_user(&state.app, &headers).await {
        Ok(user_id) => user_id,
        Err(error) => return *error,
    };
    match yarn_service::delete_for_owner(&state.app, user_id, yarn_id).await {
        Ok(()) => StatusCode::NO_CONTENT.into_response(),
        Err(error) => yarn_error(error),
    }
}

async fn favorite_yarn(
    State(state): State<ApiV2State>,
    headers: HeaderMap,
    Path(id): Path<i64>,
) -> Response {
    set_favorite(state, headers, id, true).await
}

async fn unfavorite_yarn(
    State(state): State<ApiV2State>,
    headers: HeaderMap,
    Path(id): Path<i64>,
) -> Response {
    set_favorite(state, headers, id, false).await
}

async fn set_favorite(
    state: ApiV2State,
    headers: HeaderMap,
    yarn_id: i64,
    favorite: bool,
) -> Response {
    let user_id = match resource_mutation_user(&state.app, &headers).await {
        Ok(user_id) => user_id,
        Err(error) => return *error,
    };
    match yarn_service::set_favorite_for_owner(&state.app, user_id, yarn_id, favorite).await {
        Ok(yarn) => match yarn_dto_v2(&state, user_id, yarn).await {
            Ok(yarn) => Json(Envelope { data: yarn }).into_response(),
            Err(_) => yarn_error(YarnError::Database),
        },
        Err(error) => yarn_error(error),
    }
}

fn validate_yarn(payload: &YarnWriteRequest) -> Option<Response> {
    if payload.name.trim().is_empty() || payload.name.trim().len() > 256 {
        return Some(validation_error("Yarn fields are invalid", Some("name")));
    }
    if payload.weight_grams.is_some_and(|value| value < 0) {
        return Some(validation_error(
            "Yarn fields are invalid",
            Some("weight_grams"),
        ));
    }
    if payload.length_meters.is_some_and(|value| value < 0) {
        return Some(validation_error(
            "Yarn fields are invalid",
            Some("length_meters"),
        ));
    }
    if payload
        .expected_updated_at
        .as_ref()
        .is_some_and(|value| crate::time::parse_utc(value).is_none())
    {
        return Some(validation_error(
            "Yarn fields are invalid",
            Some("expected_updated_at"),
        ));
    }
    None
}

fn validation_error(message: &'static str, field: Option<&'static str>) -> Response {
    let fields = field.map(|field| BTreeMap::from([(field, "Invalid value")]));
    (
        StatusCode::UNPROCESSABLE_ENTITY,
        Json(ErrorBody {
            error: ApiError {
                code: "validation_error",
                message,
                fields,
                current: None,
            },
        }),
    )
        .into_response()
}

fn json_rejection(rejection: JsonRejection) -> Response {
    let (status, code, message) = if rejection.status() == StatusCode::PAYLOAD_TOO_LARGE {
        (
            StatusCode::PAYLOAD_TOO_LARGE,
            "payload_too_large",
            "Request body is too large",
        )
    } else {
        (
            StatusCode::BAD_REQUEST,
            "invalid_json",
            "Request body is invalid",
        )
    };
    (
        status,
        Json(ErrorBody {
            error: ApiError {
                code,
                message,
                fields: None,
                current: None,
            },
        }),
    )
        .into_response()
}
