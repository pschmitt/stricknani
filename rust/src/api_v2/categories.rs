//! Same-origin browser category resource routes.

use std::collections::BTreeMap;

use axum::{
    Json, Router,
    extract::{DefaultBodyLimit, Path, State, rejection::JsonRejection},
    http::{HeaderMap, StatusCode},
    response::{IntoResponse, Response},
    routing::get,
};
use serde::Serialize;

use crate::categories as category_service;
use category_service::{CategoryError, CategoryWriteRequest};

use super::{
    ApiV2State, Envelope,
    access::{resource_mutation_user, resource_user},
};

pub(super) fn router() -> Router<ApiV2State> {
    Router::new()
        .route("/categories", get(list_categories).post(create_category))
        .route(
            "/categories/{category_id}",
            axum::routing::put(update_category).delete(delete_category),
        )
        .layer(DefaultBodyLimit::max(2 * 1024 * 1024))
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
}

fn category_error(error: CategoryError) -> Response {
    let (status, code, message, fields) = match error {
        CategoryError::InvalidName => (
            StatusCode::UNPROCESSABLE_ENTITY,
            "validation_error",
            "Category name is required",
            Some(BTreeMap::from([("name", "Enter a category name")])),
        ),
        CategoryError::Conflict => (
            StatusCode::CONFLICT,
            "conflict",
            "Another category already uses that name",
            Some(BTreeMap::from([("name", "Name already exists")])),
        ),
        CategoryError::NotFound => (
            StatusCode::NOT_FOUND,
            "not_found",
            "Category not found",
            None,
        ),
        CategoryError::Database => (
            StatusCode::INTERNAL_SERVER_ERROR,
            "internal_error",
            "Request failed",
            None,
        ),
    };
    (
        status,
        Json(ErrorBody {
            error: ApiError {
                code,
                message,
                fields,
            },
        }),
    )
        .into_response()
}

async fn list_categories(State(state): State<ApiV2State>, headers: HeaderMap) -> Response {
    let user_id = match resource_user(&state.app, &headers).await {
        Ok(user_id) => user_id,
        Err(error) => return *error,
    };
    match category_service::list_for_owner(&state.app, user_id).await {
        Ok(categories) => Json(Envelope { data: categories }).into_response(),
        Err(error) => category_error(error),
    }
}

async fn create_category(
    State(state): State<ApiV2State>,
    headers: HeaderMap,
    payload: Result<Json<CategoryWriteRequest>, JsonRejection>,
) -> Response {
    let user_id = match resource_mutation_user(&state.app, &headers).await {
        Ok(user_id) => user_id,
        Err(error) => return *error,
    };
    let Json(payload) = match payload {
        Ok(payload) => payload,
        Err(rejection) => return json_rejection(rejection),
    };
    if payload.name.trim().is_empty() || payload.name.len() > 256 {
        return category_error(CategoryError::InvalidName);
    }
    match category_service::create_for_owner(&state.app, user_id, payload).await {
        Ok(category) => (StatusCode::CREATED, Json(Envelope { data: category })).into_response(),
        Err(error) => category_error(error),
    }
}

async fn update_category(
    State(state): State<ApiV2State>,
    headers: HeaderMap,
    Path(category_id): Path<i64>,
    payload: Result<Json<CategoryWriteRequest>, JsonRejection>,
) -> Response {
    let user_id = match resource_mutation_user(&state.app, &headers).await {
        Ok(user_id) => user_id,
        Err(error) => return *error,
    };
    let Json(payload) = match payload {
        Ok(payload) => payload,
        Err(rejection) => return json_rejection(rejection),
    };
    if payload.name.trim().is_empty() || payload.name.len() > 256 {
        return category_error(CategoryError::InvalidName);
    }
    match category_service::update_for_owner(&state.app, user_id, category_id, payload).await {
        Ok(category) => Json(Envelope { data: category }).into_response(),
        Err(error) => category_error(error),
    }
}

async fn delete_category(
    State(state): State<ApiV2State>,
    headers: HeaderMap,
    Path(category_id): Path<i64>,
) -> Response {
    let user_id = match resource_mutation_user(&state.app, &headers).await {
        Ok(user_id) => user_id,
        Err(error) => return *error,
    };
    match category_service::delete_for_owner(&state.app, user_id, category_id).await {
        Ok(()) => StatusCode::NO_CONTENT.into_response(),
        Err(error) => category_error(error),
    }
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
            },
        }),
    )
        .into_response()
}
