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
use project_service::{
    ListQuery, ProjectError, ProjectListItem, ProjectResponse, ProjectWriteRequest, TagPolicy,
};
use serde::{Deserialize, Serialize};

use super::{
    ApiV2State, Envelope,
    access::{resource_mutation_user, resource_user},
};

pub(super) fn router() -> Router<ApiV2State> {
    Router::new()
        .route("/projects", get(list_projects).post(create_project))
        .route("/projects/tags", get(list_project_tags))
        .route("/projects/owners", get(list_project_owners))
        .route(
            "/projects/{project_id}",
            get(get_project).put(update_project).delete(delete_project),
        )
        .route(
            "/projects/{project_id}/favorite",
            axum::routing::put(favorite_project).delete(unfavorite_project),
        )
        .route(
            "/projects/{project_id}/status",
            axum::routing::put(set_project_status),
        )
        .layer(DefaultBodyLimit::max(2 * 1024 * 1024))
}

#[derive(Debug, Deserialize)]
struct ProjectPageQuery {
    page: Option<i64>,
    category: Option<String>,
    tag: Option<String>,
    favorite: Option<bool>,
    status: Option<String>,
    owner: Option<String>,
    q: Option<String>,
    scope: Option<String>,
    owner_id: Option<i64>,
    /// `only` lists the trashed projects instead of the live ones.
    trashed: Option<String>,
}

#[derive(Debug, Deserialize)]
struct ProjectTagQuery {
    scope: Option<String>,
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
pub(super) struct ProjectDto {
    id: i64,
    name: String,
    category: Option<String>,
    yarn: Option<String>,
    needles: Option<String>,
    stitch_sample: Option<String>,
    stitch_sample_html: Option<String>,
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
    status: String,
    created_at: String,
    updated_at: String,
    /// Set while the project is in the trash (then it is read-only).
    trashed_at: Option<String>,
    purge_at: Option<String>,
    preview_url: Option<String>,
    yarn_ids: Vec<i64>,
    steps: Vec<StepDto>,
    images: Vec<serde_json::Value>,
    attachments: Vec<serde_json::Value>,
    owner: Option<crate::resource_access::OwnerInfo>,
    #[serde(skip_serializing_if = "Option::is_none")]
    access: Option<crate::resource_access::AccessInfo>,
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
            stitch_sample_html: project
                .stitch_sample
                .as_deref()
                .map(crate::web::rich_text_lines),
            stitch_sample: project.stitch_sample,
            description_html: project.description.as_deref().map(crate::web::rich_text),
            description: project.description,
            notes_html: project.notes.as_deref().map(crate::web::rich_text),
            notes: project.notes,
            other_materials_html: project
                .other_materials
                .as_deref()
                .map(crate::web::rich_text_lines),
            other_materials: project.other_materials,
            tags: project.tags,
            link: project.link,
            link_archive: project.link_archive,
            is_ai_enhanced: project.is_ai_enhanced,
            is_favorite: project.is_favorite,
            status: project
                .status
                .unwrap_or_else(|| project_service::STATUS_IN_PROGRESS.to_owned()),
            created_at: project.created_at,
            updated_at: project.updated_at,
            trashed_at: project.trashed_at,
            purge_at: project.purge_at,
            preview_url: None,
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
            owner: project.owner,
            access: project.access,
        }
    }
}

pub(super) async fn project_dto_v2(
    state: &ApiV2State,
    user_id: i64,
    project: ProjectResponse,
) -> Result<ProjectDto, sqlx::Error> {
    let permit = state
        .sync_projection_slots
        .clone()
        .acquire_owned()
        .await
        .map_err(|_| sqlx::Error::Protocol("project projection capacity closed".into()))?;
    let project_id = project.id;
    let media_owner = project.owner.as_ref().map_or(user_id, |owner| owner.id);
    let (images, attachments, preview_url) =
        crate::media::project_media_v2(&state.app.pool, media_owner, project_id).await?;
    tokio::task::spawn_blocking(move || {
        let _permit = permit;
        project_dto_v2_with_media(project, images, attachments, preview_url)
    })
    .await
    .map_err(|error| sqlx::Error::Protocol(error.to_string()))
}

pub(super) fn project_dto_v2_with_media(
    project: ProjectResponse,
    images: Vec<serde_json::Value>,
    attachments: Vec<serde_json::Value>,
    preview_url: Option<String>,
) -> ProjectDto {
    let mut dto = ProjectDto::from(project);
    dto.images = images;
    dto.attachments = attachments;
    dto.preview_url = preview_url;
    dto
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
        ProjectError::Forbidden => (
            StatusCode::FORBIDDEN,
            "forbidden",
            "You may view this project but not change it",
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
    let user_id = match resource_user(&state.app, &headers).await {
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
            status: query.status,
            owner: query.owner,
            q: query.q,
            scope: query.scope,
            owner_id: query.owner_id,
            trashed_only: match query.trashed.as_deref() {
                None => false,
                Some("only") => true,
                Some(_) => return validation_error("Invalid query", Some("trashed")),
            },
        },
        TagPolicy::PreserveLabels,
    )
    .await
    {
        Ok(page) => page,
        Err(error) => return project_error(error),
    };
    let ids: Vec<_> = page.items.iter().map(|project| project.id).collect();
    let previews = match crate::media::project_previews_v2(&state.app.pool, &ids).await {
        Ok(previews) => previews,
        Err(_) => return project_error(ProjectError::Database),
    };
    let data: Vec<ProjectListItem> = page
        .items
        .into_iter()
        .map(|mut project| {
            project.preview_url = previews.get(&project.id).cloned();
            project
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

/// Distinct project tags visible in the requested library scope, with usage counts.
async fn list_project_tags(
    State(state): State<ApiV2State>,
    headers: HeaderMap,
    query: Result<Query<ProjectTagQuery>, QueryRejection>,
) -> Response {
    let user_id = match resource_user(&state.app, &headers).await {
        Ok(user_id) => user_id,
        Err(response) => return *response,
    };
    let Query(query) = match query {
        Ok(query) => query,
        Err(_) => return validation_error("Invalid query", None),
    };
    match project_service::tag_counts_for_scope(&state.app, user_id, query.scope.as_deref()).await {
        Ok(data) => Json(Envelope { data }).into_response(),
        Err(error) => project_error(error),
    }
}

/// The owners (other than the caller) of the visible projects: the owner filter's options.
async fn list_project_owners(
    State(state): State<ApiV2State>,
    headers: HeaderMap,
    query: Result<Query<ProjectTagQuery>, QueryRejection>,
) -> Response {
    let user_id = match resource_user(&state.app, &headers).await {
        Ok(user_id) => user_id,
        Err(response) => return *response,
    };
    let Query(query) = match query {
        Ok(query) => query,
        Err(_) => return validation_error("Invalid query", None),
    };
    match project_service::owners_for_scope(&state.app, user_id, query.scope.as_deref()).await {
        Ok(data) => Json(Envelope { data }).into_response(),
        Err(error) => project_error(error),
    }
}

async fn get_project(
    State(state): State<ApiV2State>,
    headers: HeaderMap,
    Path(project_id): Path<i64>,
) -> Response {
    let user_id = match resource_user(&state.app, &headers).await {
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
        Ok(Some(project)) => match project_dto_v2(&state, user_id, project).await {
            Ok(project) => Json(Envelope { data: project }).into_response(),
            Err(_) => project_error(ProjectError::Database),
        },
        Ok(None) => project_error(ProjectError::NotFound),
        Err(_) => project_error(ProjectError::Database),
    }
}

async fn create_project(
    State(state): State<ApiV2State>,
    headers: HeaderMap,
    payload: Result<Json<ProjectWriteRequest>, JsonRejection>,
) -> Response {
    let user_id = match resource_mutation_user(&state.app, &headers).await {
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
        Ok(project) => match project_dto_v2(&state, user_id, project).await {
            Ok(project) => (StatusCode::CREATED, Json(Envelope { data: project })).into_response(),
            Err(_) => project_error(ProjectError::Database),
        },
        Err(error) => project_error(error),
    }
}

async fn update_project(
    State(state): State<ApiV2State>,
    headers: HeaderMap,
    Path(project_id): Path<i64>,
    payload: Result<Json<ProjectWriteRequest>, JsonRejection>,
) -> Response {
    let user_id = match resource_mutation_user(&state.app, &headers).await {
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
    if !payload.new_yarns.is_empty() {
        return validation_error(
            "New yarns can only be created with a new project",
            Some("new_yarns"),
        );
    }
    if !payload.image_urls.is_empty()
        || !payload.attachment_urls.is_empty()
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
        Ok(project) => match project_dto_v2(&state, user_id, project).await {
            Ok(project) => Json(Envelope { data: project }).into_response(),
            Err(_) => project_error(ProjectError::Database),
        },
        Err(error) => project_error(error),
    }
}

async fn delete_project(
    State(state): State<ApiV2State>,
    headers: HeaderMap,
    Path(project_id): Path<i64>,
) -> Response {
    let user_id = match resource_mutation_user(&state.app, &headers).await {
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

#[derive(Deserialize)]
struct StatusRequest {
    status: String,
}

async fn set_project_status(
    State(state): State<ApiV2State>,
    headers: HeaderMap,
    Path(project_id): Path<i64>,
    payload: Result<Json<StatusRequest>, JsonRejection>,
) -> Response {
    let user_id = match resource_mutation_user(&state.app, &headers).await {
        Ok(user_id) => user_id,
        Err(response) => return *response,
    };
    let Json(payload) = match payload {
        Ok(payload) => payload,
        Err(rejection) => return json_rejection(rejection),
    };
    if project_service::parse_status(&payload.status).is_none() {
        return validation_error("Project fields are invalid", Some("status"));
    }
    match project_service::set_status_for_owner(&state.app, user_id, project_id, &payload.status)
        .await
    {
        Ok(project) => match project_dto_v2(&state, user_id, project).await {
            Ok(project) => Json(Envelope { data: project }).into_response(),
            Err(_) => project_error(ProjectError::Database),
        },
        Err(error) => project_error(error),
    }
}

async fn set_favorite(
    state: ApiV2State,
    headers: HeaderMap,
    project_id: i64,
    favorite: bool,
) -> Response {
    let user_id = match resource_mutation_user(&state.app, &headers).await {
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
        Ok(project) => match project_dto_v2(&state, user_id, project).await {
            Ok(project) => Json(Envelope { data: project }).into_response(),
            Err(_) => project_error(ProjectError::Database),
        },
        Err(error) => project_error(error),
    }
}

fn validate_project(payload: &ProjectWriteRequest) -> Option<Response> {
    let name = payload.name.trim();
    if name.is_empty() || name.len() > 256 {
        return validation_error("Project fields are invalid", Some("name")).into();
    }
    if payload
        .status
        .as_deref()
        .is_some_and(|value| project_service::parse_status(value).is_none())
    {
        return validation_error("Project fields are invalid", Some("status")).into();
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
