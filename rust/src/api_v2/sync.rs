//! Bounded cookie/PAT-authenticated project and yarn synchronization.

use std::collections::BTreeMap;

use axum::{
    Json, Router,
    extract::{RawQuery, State},
    http::{HeaderMap, StatusCode},
    response::{IntoResponse, Response},
    routing::get,
};
use chrono::DateTime;
use serde::Serialize;
use url::form_urlencoded;

use crate::{projects, sync as sync_service, yarns};

use super::{ApiV2State, access::resource_user};

const DEFAULT_PAGE_SIZE: i64 = 50;

pub(super) fn router() -> Router<ApiV2State> {
    Router::new()
        .route("/sync/projects", get(sync_projects))
        .route("/sync/yarns", get(sync_yarns))
}

#[derive(Serialize)]
struct SyncEnvelope<T> {
    data: SyncData<T>,
}

#[derive(Serialize)]
struct SyncData<T> {
    updated: Vec<T>,
    deleted_ids: Vec<i64>,
    server_time: String,
    full_resync_required: bool,
    has_more: bool,
    next_cursor: Option<String>,
}

#[derive(Default)]
struct SyncQuery {
    since: Option<String>,
    limit: Option<i64>,
    cursor: Option<String>,
}

#[derive(Clone, Copy)]
enum QueryError {
    Query,
    Since,
    Limit,
}

#[derive(Clone, Copy)]
enum ProjectionError {
    Internal,
}

async fn sync_projects(
    State(state): State<ApiV2State>,
    headers: HeaderMap,
    RawQuery(raw_query): RawQuery,
) -> Response {
    let owner_id = match resource_user(&state.app, &headers).await {
        Ok(owner_id) => owner_id,
        Err(response) => return *response,
    };
    let query = match parse_query(raw_query.as_deref()) {
        Ok(query) => query,
        Err(error) => return query_error(error),
    };
    if query
        .cursor
        .as_ref()
        .is_some_and(|cursor| cursor.len() > sync_service::MAX_CURSOR_BYTES)
    {
        return cursor_error();
    }
    let page = match sync_service::sync_page(
        &state.app,
        sync_service::EntityKind::Project,
        owner_id,
        query.since.as_deref(),
        query.limit,
        query.cursor.as_deref(),
        sync_service::CursorProtocol::V2,
    )
    .await
    {
        Ok(page) => page,
        Err(sync_service::SyncPageError::MutuallyExclusive) => return invalid_query(),
        Err(sync_service::SyncPageError::InvalidCursor) => return cursor_error(),
        Err(sync_service::SyncPageError::Internal) => return internal_error(),
    };

    let updated = match load_project_dtos(&state, owner_id, &page.updated_ids).await {
        Ok(updated) => updated,
        Err(ProjectionError::Internal) => return internal_error(),
    };
    Json(SyncEnvelope {
        data: SyncData {
            updated,
            deleted_ids: page.deleted_ids,
            server_time: page.server_time,
            full_resync_required: false,
            has_more: page.has_more,
            next_cursor: page.next_cursor,
        },
    })
    .into_response()
}

async fn sync_yarns(
    State(state): State<ApiV2State>,
    headers: HeaderMap,
    RawQuery(raw_query): RawQuery,
) -> Response {
    let owner_id = match resource_user(&state.app, &headers).await {
        Ok(owner_id) => owner_id,
        Err(response) => return *response,
    };
    let query = match parse_query(raw_query.as_deref()) {
        Ok(query) => query,
        Err(error) => return query_error(error),
    };
    if query
        .cursor
        .as_ref()
        .is_some_and(|cursor| cursor.len() > sync_service::MAX_CURSOR_BYTES)
    {
        return cursor_error();
    }
    let page = match sync_service::sync_page(
        &state.app,
        sync_service::EntityKind::Yarn,
        owner_id,
        query.since.as_deref(),
        query.limit,
        query.cursor.as_deref(),
        sync_service::CursorProtocol::V2,
    )
    .await
    {
        Ok(page) => page,
        Err(sync_service::SyncPageError::MutuallyExclusive) => return invalid_query(),
        Err(sync_service::SyncPageError::InvalidCursor) => return cursor_error(),
        Err(sync_service::SyncPageError::Internal) => return internal_error(),
    };

    let updated = match load_yarn_dtos(&state, owner_id, &page.updated_ids).await {
        Ok(updated) => updated,
        Err(ProjectionError::Internal) => return internal_error(),
    };
    Json(SyncEnvelope {
        data: SyncData {
            updated,
            deleted_ids: page.deleted_ids,
            server_time: page.server_time,
            full_resync_required: false,
            has_more: page.has_more,
            next_cursor: page.next_cursor,
        },
    })
    .into_response()
}

fn parse_query(raw_query: Option<&str>) -> Result<SyncQuery, QueryError> {
    let mut params = BTreeMap::new();
    if let Some(raw_query) = raw_query {
        for (key, value) in form_urlencoded::parse(raw_query.as_bytes()) {
            if !matches!(key.as_ref(), "since" | "limit" | "cursor")
                || params
                    .insert(key.into_owned(), value.into_owned())
                    .is_some()
            {
                return Err(QueryError::Query);
            }
        }
    }

    if params.contains_key("since") && params.contains_key("cursor") {
        return Err(QueryError::Query);
    }

    let mut since = params.remove("since");
    if let Some(since) = since.as_mut() {
        DateTime::parse_from_rfc3339(since).map_err(|_| QueryError::Since)?;
        *since = sync_service::normalize_timestamp(since).map_err(|_| QueryError::Since)?;
    }
    let limit = params
        .remove("limit")
        .map(|value| {
            value
                .parse::<i64>()
                .ok()
                .filter(|limit| (1..=sync_service::MAX_PAGE_SIZE).contains(limit))
                .ok_or(QueryError::Limit)
        })
        .transpose()?;
    let cursor = params.remove("cursor");
    Ok(SyncQuery {
        since,
        limit: if cursor.is_none() && limit.is_none() {
            Some(DEFAULT_PAGE_SIZE)
        } else {
            limit
        },
        cursor,
    })
}

async fn load_project_dtos(
    state: &ApiV2State,
    owner_id: i64,
    ids: &[i64],
) -> Result<Vec<super::projects::ProjectDto>, ProjectionError> {
    if ids.is_empty() {
        return Ok(Vec::new());
    }
    let permit = state
        .sync_projection_slots
        .clone()
        .acquire_owned()
        .await
        .map_err(|_| ProjectionError::Internal)?;
    let mut project_responses = Vec::with_capacity(ids.len());
    for id in ids {
        match projects::load_project_with_tag_policy(
            &state.app,
            owner_id,
            *id,
            projects::TagPolicy::PreserveLabels,
        )
        .await
        {
            Ok(Some(project)) => project_responses.push(project),
            Ok(None) => {}
            Err(_) => return Err(ProjectionError::Internal),
        }
    }
    tokio::task::spawn_blocking(move || {
        let _permit = permit;
        project_responses
            .into_iter()
            .map(super::projects::ProjectDto::from)
            .collect()
    })
    .await
    .map_err(|_| ProjectionError::Internal)
}

async fn load_yarn_dtos(
    state: &ApiV2State,
    owner_id: i64,
    ids: &[i64],
) -> Result<Vec<super::yarns::YarnDto>, ProjectionError> {
    if ids.is_empty() {
        return Ok(Vec::new());
    }
    let permit = state
        .sync_projection_slots
        .clone()
        .acquire_owned()
        .await
        .map_err(|_| ProjectionError::Internal)?;
    let mut yarn_responses = Vec::with_capacity(ids.len());
    for id in ids {
        match yarns::load_for_owner(&state.app, owner_id, *id).await {
            Ok(Some(yarn)) => yarn_responses.push(yarn),
            Ok(None) => {}
            Err(_) => return Err(ProjectionError::Internal),
        }
    }
    tokio::task::spawn_blocking(move || {
        let _permit = permit;
        yarn_responses
            .into_iter()
            .map(super::yarns::YarnDto::from)
            .collect()
    })
    .await
    .map_err(|_| ProjectionError::Internal)
}

fn query_error(error: QueryError) -> Response {
    match error {
        QueryError::Query => invalid_query(),
        QueryError::Since => validation_error("since", "Expected a valid UTC timestamp"),
        QueryError::Limit => validation_error("limit", "Expected an integer from 1 to 50"),
    }
}

fn validation_error(field: &'static str, message: &'static str) -> Response {
    let mut fields = BTreeMap::new();
    fields.insert(field, message);
    super::api_error_fields(
        StatusCode::UNPROCESSABLE_ENTITY,
        "validation_error",
        "Sync query is invalid",
        fields,
    )
}

fn invalid_query() -> Response {
    super::api_error(
        StatusCode::BAD_REQUEST,
        "invalid_query",
        "Invalid sync query",
    )
}

fn cursor_error() -> Response {
    super::api_error(
        StatusCode::BAD_REQUEST,
        "invalid_cursor",
        "Invalid sync cursor",
    )
}

fn internal_error() -> Response {
    super::api_error(
        StatusCode::INTERNAL_SERVER_ERROR,
        "internal_error",
        "Request failed",
    )
}
