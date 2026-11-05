//! Project and yarn audit history for everyone who may read the resource.

use axum::{
    Json, Router,
    extract::{Path, RawQuery, State},
    http::{HeaderMap, StatusCode},
    response::{IntoResponse, Response},
    routing::get,
};
use serde::Serialize;
use url::form_urlencoded;

use crate::audit_service::{self, AuditEntity, AuditError};

use super::{ApiV2State, access, api_error};

const MAX_HISTORY_PAGE: u32 = 10_000;
const HISTORY_PAGE_SIZE: u32 = 20;

pub(super) fn router() -> Router<ApiV2State> {
    Router::new()
        .route("/projects/{project_id}/history", get(project_history))
        .route(
            "/projects/{project_id}/history/{event_id}",
            get(project_history_detail),
        )
        .route("/yarns/{yarn_id}/history", get(yarn_history))
        .route(
            "/yarns/{yarn_id}/history/{event_id}",
            get(yarn_history_detail),
        )
}

#[derive(Serialize)]
struct HistoryMeta {
    page: u32,
    per_page: u32,
    has_more: bool,
}

#[derive(Serialize)]
struct HistoryEnvelope<T> {
    data: T,
    #[serde(skip_serializing_if = "Option::is_none")]
    meta: Option<HistoryMeta>,
}

async fn project_history(
    State(state): State<ApiV2State>,
    headers: HeaderMap,
    Path(project_id): Path<i64>,
    RawQuery(raw_query): RawQuery,
) -> Response {
    list_history(
        &state,
        &headers,
        AuditEntity::Project,
        project_id,
        raw_query.as_deref(),
    )
    .await
}

async fn yarn_history(
    State(state): State<ApiV2State>,
    headers: HeaderMap,
    Path(yarn_id): Path<i64>,
    RawQuery(raw_query): RawQuery,
) -> Response {
    list_history(
        &state,
        &headers,
        AuditEntity::Yarn,
        yarn_id,
        raw_query.as_deref(),
    )
    .await
}

async fn list_history(
    state: &ApiV2State,
    headers: &HeaderMap,
    entity: AuditEntity,
    resource_id: i64,
    raw_query: Option<&str>,
) -> Response {
    let actor_id = match access::resource_user(&state.app, headers).await {
        Ok(actor_id) => actor_id,
        Err(response) => return *response,
    };
    // Anyone who may read the resource reads its history.
    let owner_id = match resource_owner(&state.app.pool, actor_id, entity, resource_id).await {
        Ok(Some(owner_id)) => owner_id,
        Ok(None) => return not_found(),
        Err(_) => return internal_error(),
    };
    let page = match parse_page(raw_query) {
        Ok(page) => page,
        Err(()) => return invalid_query(),
    };
    match audit_service::list_resource_events(
        &state.app.pool,
        owner_id,
        entity,
        resource_id,
        page,
        state.app.gravatar.enabled,
    )
    .await
    {
        Ok(Some(result)) => Json(HistoryEnvelope {
            data: result.events,
            meta: Some(HistoryMeta {
                page: result.page,
                per_page: HISTORY_PAGE_SIZE,
                has_more: result.has_more,
            }),
        })
        .into_response(),
        Ok(None) => not_found(),
        Err(AuditError::InvalidQuery) => invalid_query(),
        Err(_) => internal_error(),
    }
}

async fn project_history_detail(
    State(state): State<ApiV2State>,
    headers: HeaderMap,
    Path((project_id, event_id)): Path<(i64, i64)>,
) -> Response {
    event_detail(&state, &headers, AuditEntity::Project, project_id, event_id).await
}

async fn yarn_history_detail(
    State(state): State<ApiV2State>,
    headers: HeaderMap,
    Path((yarn_id, event_id)): Path<(i64, i64)>,
) -> Response {
    event_detail(&state, &headers, AuditEntity::Yarn, yarn_id, event_id).await
}

async fn event_detail(
    state: &ApiV2State,
    headers: &HeaderMap,
    entity: AuditEntity,
    resource_id: i64,
    event_id: i64,
) -> Response {
    let actor_id = match access::resource_user(&state.app, headers).await {
        Ok(actor_id) => actor_id,
        Err(response) => return *response,
    };
    // Anyone who may read the resource reads its history.
    let owner_id = match resource_owner(&state.app.pool, actor_id, entity, resource_id).await {
        Ok(Some(owner_id)) => owner_id,
        Ok(None) => return not_found(),
        Err(_) => return internal_error(),
    };
    match audit_service::get_resource_event_detail(
        &state.app.pool,
        owner_id,
        entity,
        resource_id,
        event_id,
        state.app.gravatar.enabled,
    )
    .await
    {
        Ok(Some(data)) => Json(HistoryEnvelope { data, meta: None }).into_response(),
        Ok(None) => not_found(),
        Err(_) => internal_error(),
    }
}

fn parse_page(raw_query: Option<&str>) -> Result<u32, ()> {
    let mut page = None;
    if let Some(raw_query) = raw_query {
        if raw_query.len() > 512 {
            return Err(());
        }
        for (key, value) in form_urlencoded::parse(raw_query.as_bytes()) {
            if key != "page" || page.is_some() {
                return Err(());
            }
            let parsed = value.parse::<u32>().map_err(|_| ())?;
            if parsed == 0 || parsed > MAX_HISTORY_PAGE {
                return Err(());
            }
            page = Some(parsed);
        }
    }
    Ok(page.unwrap_or(1))
}

fn invalid_query() -> Response {
    api_error(
        StatusCode::BAD_REQUEST,
        "invalid_query",
        "History page is invalid",
    )
}

fn not_found() -> Response {
    api_error(StatusCode::NOT_FOUND, "not_found", "Resource was not found")
}

fn internal_error() -> Response {
    api_error(
        StatusCode::INTERNAL_SERVER_ERROR,
        "internal_error",
        "Request failed",
    )
}

/// The owner whose history is read, when the actor may read the resource at
/// all: the owner, an active administrator, a share recipient or the viewer of
/// a yarn derived from a shared project.
async fn resource_owner(
    pool: &sqlx::SqlitePool,
    actor_id: i64,
    entity: AuditEntity,
    resource_id: i64,
) -> Result<Option<i64>, sqlx::Error> {
    let kind = match entity {
        AuditEntity::Project => crate::resource_access::ResourceKind::Project,
        _ => crate::resource_access::ResourceKind::Yarn,
    };
    Ok(crate::resource_access::grant(
        pool,
        actor_id,
        kind,
        resource_id,
        crate::resource_access::Surface::V2,
    )
    .await?
    .map(|grant| grant.owner_id))
}
