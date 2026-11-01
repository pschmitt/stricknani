//! Same-origin browser project resource routes.

use std::collections::BTreeMap;

use crate::projects as project_service;
use axum::{
    Json, Router,
    extract::{
        DefaultBodyLimit, Path, Query, State, rejection::JsonRejection, rejection::QueryRejection,
    },
    http::{HeaderMap, StatusCode},
    response::{IntoResponse, Response},
    routing::get,
};
use project_service::{ListQuery, ProjectError, ProjectResponse, ProjectWriteRequest, TagPolicy};
use serde::{Deserialize, Serialize};

use super::{
    ApiV2State, Envelope,
    access::{browser_user, mutation_user},
};

pub(super) fn router() -> Router<ApiV2State> {
    Router::new()
        .route("/projects", get(list_projects).post(create_project))
        .route(
            "/projects/{project_id}",
            get(get_project).put(update_project).delete(delete_project),
        )
        .route(
            "/projects/{project_id}/favorite",
            axum::routing::put(favorite_project).delete(unfavorite_project),
        )
        .layer(DefaultBodyLimit::max(2 * 1024 * 1024))
}

#[derive(Debug, Deserialize)]
struct ProjectPageQuery {
    page: Option<i64>,
    category: Option<String>,
    tag: Option<String>,
    favorite: Option<bool>,
    q: Option<String>,
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
}

#[derive(Serialize)]
struct ProjectDto {
    id: i64,
    name: String,
    category: Option<String>,
    yarn: Option<String>,
    needles: Option<String>,
    stitch_sample: Option<String>,
    description: Option<String>,
    description_html: Option<String>,
    notes: Option<String>,
    notes_html: Option<String>,
    other_materials: Option<String>,
    other_materials_html: Option<String>,
    tags: Vec<String>,
    link: Option<String>,
    link_archive: Option<String>,
    is_ai_enhanced: bool,
    is_favorite: bool,
    created_at: String,
    updated_at: String,
    yarn_ids: Vec<i64>,
    steps: Vec<StepDto>,
    images: Vec<serde_json::Value>,
    attachments: Vec<serde_json::Value>,
}

#[derive(Serialize)]
struct StepDto {
    id: i64,
    title: String,
    description: Option<String>,
    description_html: Option<String>,
    step_number: i64,
}

impl From<ProjectResponse> for ProjectDto {
    fn from(project: ProjectResponse) -> Self {
        Self {
            id: project.id,
            name: project.name,
            category: project.category,
            yarn: project.yarn,
            needles: project.needles,
            stitch_sample: project.stitch_sample,
            description_html: project.description.as_deref().map(crate::web::rich_text),
            description: project.description,
            notes_html: project.notes.as_deref().map(crate::web::rich_text),
            notes: project.notes,
            other_materials_html: project
                .other_materials
                .as_deref()
                .map(crate::web::rich_text),
            other_materials: project.other_materials,
            tags: project.tags,
            link: project.link,
            link_archive: project.link_archive,
            is_ai_enhanced: project.is_ai_enhanced,
            is_favorite: project.is_favorite,
            created_at: project.created_at,
            updated_at: project.updated_at,
            yarn_ids: project.yarn_ids,
            steps: project
                .steps
                .into_iter()
                .map(|step| StepDto {
                    id: step.id,
                    title: step.title,
                    description_html: step.description.as_deref().map(crate::web::rich_text),
                    description: step.description,
                    step_number: step.step_number,
                })
                .collect(),
            images: project.images,
            attachments: project.attachments,
        }
    }
}

#[derive(Serialize)]
struct ApiErrorBody {
    error: ApiError,
}

#[derive(Serialize)]
struct ApiError {
    code: &'static str,
    message: &'static str,
    #[serde(skip_serializing_if = "Option::is_none")]
    fields: Option<BTreeMap<&'static str, &'static str>>,
    #[serde(skip_serializing_if = "Option::is_none")]
    current: Option<ProjectDto>,
}

fn project_error(error: ProjectError) -> Response {
    let (status, code, message, fields, current) = match error {
        ProjectError::NotFound => (
            StatusCode::NOT_FOUND,
            "not_found",
            "Project not found",
            None,
            None,
        ),
        ProjectError::Database => (
            StatusCode::INTERNAL_SERVER_ERROR,
            "internal_error",
            "Request failed",
            None,
            None,
        ),
        ProjectError::Invalid("Invalid expected_updated_at") => (
            StatusCode::UNPROCESSABLE_ENTITY,
            "validation_error",
            "Project fields are invalid",
            Some(BTreeMap::from([(
                "expected_updated_at",
                "Invalid timestamp",
            )])),
            None,
        ),
        ProjectError::Invalid(_) => (
            StatusCode::UNPROCESSABLE_ENTITY,
            "validation_error",
            "Project fields are invalid",
            None,
            None,
        ),
        ProjectError::Conflict(project) => (
            StatusCode::CONFLICT,
            "conflict",
            "Project changed since it was loaded",
            None,
            Some((*project).into()),
        ),
        ProjectError::ImageImportUnavailable => (
            StatusCode::NOT_IMPLEMENTED,
            "image_import_unavailable",
            "Project image URL imports are not enabled",
            None,
            None,
        ),
    };
    (
        status,
        Json(ApiErrorBody {
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

async fn list_projects(
    State(state): State<ApiV2State>,
    headers: HeaderMap,
    query: Result<Query<ProjectPageQuery>, QueryRejection>,
) -> Response {
    let user_id = match browser_user(&state.app, &headers).await {
        Ok(user_id) => user_id,
        Err(response) => return *response,
    };
    let Query(query) = match query {
        Ok(query) => query,
        Err(_) => return validation_error("Invalid query", None),
    };
    let page = match project_service::list_for_owner_with_tag_policy(
        &state.app,
        user_id,
        ListQuery {
            page: query.page,
            category: query.category,
            tag: query.tag,
            favorite: query.favorite,
            q: query.q,
        },
        TagPolicy::PreserveLabels,
    )
    .await
    {
        Ok(page) => page,
        Err(error) => return project_error(error),
    };
    let data = page.items;
    Json(ListEnvelope {
        data,
        meta: PageMeta {
            page: page.page,
            per_page: page.per_page,
            has_more: page.has_more,
        },
    })
    .into_response()
}

async fn get_project(
    State(state): State<ApiV2State>,
    headers: HeaderMap,
    Path(project_id): Path<i64>,
) -> Response {
    let user_id = match browser_user(&state.app, &headers).await {
        Ok(user_id) => user_id,
        Err(response) => return *response,
    };
    match project_service::load_project_with_tag_policy(
        &state.app,
        user_id,
        project_id,
        TagPolicy::PreserveLabels,
    )
    .await
    {
        Ok(Some(project)) => Json(Envelope {
            data: ProjectDto::from(project),
        })
        .into_response(),
        Ok(None) => project_error(ProjectError::NotFound),
        Err(_) => project_error(ProjectError::Database),
    }
}

async fn create_project(
    State(state): State<ApiV2State>,
    headers: HeaderMap,
    payload: Result<Json<ProjectWriteRequest>, JsonRejection>,
) -> Response {
    let user_id = match mutation_user(&state.app, &headers).await {
        Ok(user_id) => user_id,
        Err(response) => return *response,
    };
    let Json(payload) = match payload {
        Ok(payload) => payload,
        Err(rejection) => return json_rejection(rejection),
    };
    if let Some(response) = validate_project(&payload) {
        return response;
    }
    match project_service::create_for_owner_with_tag_policy(
        &state.app,
        user_id,
        payload,
        TagPolicy::PreserveLabels,
    )
    .await
    {
        Ok(project) => (
            StatusCode::CREATED,
            Json(Envelope {
                data: ProjectDto::from(project),
            }),
        )
            .into_response(),
        Err(error) => project_error(error),
    }
}

async fn update_project(
    State(state): State<ApiV2State>,
    headers: HeaderMap,
    Path(project_id): Path<i64>,
    payload: Result<Json<ProjectWriteRequest>, JsonRejection>,
) -> Response {
    let user_id = match mutation_user(&state.app, &headers).await {
        Ok(user_id) => user_id,
        Err(response) => return *response,
    };
    let Json(payload) = match payload {
        Ok(payload) => payload,
        Err(rejection) => return json_rejection(rejection),
    };
    if let Some(response) = validate_project(&payload) {
        return response;
    }
    if !payload.image_urls.is_empty()
        || payload.steps.iter().any(|step| !step.image_urls.is_empty())
    {
        return validation_error(
            "Media updates are not available in this slice",
            Some("image_urls"),
        );
    }
    match project_service::update_for_owner_with_tag_policy(
        &state.app,
        user_id,
        project_id,
        payload,
        TagPolicy::PreserveLabels,
    )
    .await
    {
        Ok(project) => Json(Envelope {
            data: ProjectDto::from(project),
        })
        .into_response(),
        Err(error) => project_error(error),
    }
}

async fn delete_project(
    State(state): State<ApiV2State>,
    headers: HeaderMap,
    Path(project_id): Path<i64>,
) -> Response {
    let user_id = match mutation_user(&state.app, &headers).await {
        Ok(user_id) => user_id,
        Err(response) => return *response,
    };
    match project_service::delete_for_owner(&state.app, user_id, project_id).await {
        Ok(()) => StatusCode::NO_CONTENT.into_response(),
        Err(error) => project_error(error),
    }
}

async fn favorite_project(
    State(state): State<ApiV2State>,
    headers: HeaderMap,
    Path(project_id): Path<i64>,
) -> Response {
    set_favorite(state, headers, project_id, true).await
}

async fn unfavorite_project(
    State(state): State<ApiV2State>,
    headers: HeaderMap,
    Path(project_id): Path<i64>,
) -> Response {
    set_favorite(state, headers, project_id, false).await
}

async fn set_favorite(
    state: ApiV2State,
    headers: HeaderMap,
    project_id: i64,
    favorite: bool,
) -> Response {
    let user_id = match mutation_user(&state.app, &headers).await {
        Ok(user_id) => user_id,
        Err(response) => return *response,
    };
    match project_service::set_favorite_for_owner_with_tag_policy(
        &state.app,
        user_id,
        project_id,
        favorite,
        TagPolicy::PreserveLabels,
    )
    .await
    {
        Ok(project) => Json(Envelope {
            data: ProjectDto::from(project),
        })
        .into_response(),
        Err(error) => project_error(error),
    }
}

fn validate_project(payload: &ProjectWriteRequest) -> Option<Response> {
    let name = payload.name.trim();
    if name.is_empty() || name.len() > 256 {
        return validation_error("Project fields are invalid", Some("name")).into();
    }
    if payload
        .expected_updated_at
        .as_ref()
        .is_some_and(|value| crate::time::parse_utc(value).is_none())
    {
        return validation_error("Project fields are invalid", Some("expected_updated_at")).into();
    }
    if payload
        .steps
        .iter()
        .any(|step| step.title.trim().is_empty() || step.title.len() > 256)
    {
        return validation_error("Project fields are invalid", Some("steps")).into();
    }
    None
}

fn validation_error(message: &'static str, field: Option<&'static str>) -> Response {
    let fields = field.map(|field| BTreeMap::from([(field, "Invalid value")]));
    (
        StatusCode::UNPROCESSABLE_ENTITY,
        Json(ApiErrorBody {
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
    if rejection.status() == StatusCode::PAYLOAD_TOO_LARGE {
        return (
            StatusCode::PAYLOAD_TOO_LARGE,
            Json(ApiErrorBody {
                error: ApiError {
                    code: "payload_too_large",
                    message: "Request body is too large",
                    fields: None,
                    current: None,
                },
            }),
        )
            .into_response();
    }
    validation_error("Invalid JSON request body", None)
}
