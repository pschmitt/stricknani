//! Same-origin browser routes for durable archive requests and status.

use axum::{
    Json, Router,
    extract::{Path, State},
    http::{HeaderMap, StatusCode},
    response::{IntoResponse, Response},
    routing::get,
};
use serde::Serialize;

use crate::archive_jobs::{self, ArchiveEntityKind, ArchiveJobState, ArchiveJobStatus};

use super::{
    ApiV2State, Envelope,
    access::{browser_user, mutation_user},
};

pub(super) fn router() -> Router<ApiV2State> {
    Router::new()
        .route(
            "/projects/{id}/archive",
            get(project_status).post(project_enqueue),
        )
        .route("/yarns/{id}/archive", get(yarn_status).post(yarn_enqueue))
}

#[derive(Serialize)]
struct ArchiveStatus {
    state: Option<&'static str>,
    updated_at: Option<i64>,
}

impl From<ArchiveJobStatus> for ArchiveStatus {
    fn from(status: ArchiveJobStatus) -> Self {
        Self {
            state: status.state.map(state_name),
            updated_at: status.updated_at,
        }
    }
}

#[derive(Serialize)]
struct ArchiveAccepted {
    state: &'static str,
}

async fn project_status(
    State(state): State<ApiV2State>,
    headers: HeaderMap,
    Path(id): Path<i64>,
) -> Response {
    status(&state, &headers, ArchiveEntityKind::Project, id).await
}

async fn yarn_status(
    State(state): State<ApiV2State>,
    headers: HeaderMap,
    Path(id): Path<i64>,
) -> Response {
    status(&state, &headers, ArchiveEntityKind::Yarn, id).await
}

async fn status(
    state: &ApiV2State,
    headers: &HeaderMap,
    kind: ArchiveEntityKind,
    id: i64,
) -> Response {
    let owner_id = match browser_user(&state.app, headers).await {
        Ok(owner_id) => owner_id,
        Err(response) => return *response,
    };
    if !state.app.archive_enabled {
        return unavailable();
    }
    match archive_jobs::status_for_owner(&state.app.pool, owner_id, kind, id).await {
        Ok(Some(status)) => Json(Envelope {
            data: ArchiveStatus::from(status),
        })
        .into_response(),
        Ok(None) => not_found(),
        Err(_) => internal_error(),
    }
}

async fn project_enqueue(
    State(state): State<ApiV2State>,
    headers: HeaderMap,
    Path(id): Path<i64>,
) -> Response {
    enqueue(&state, &headers, ArchiveEntityKind::Project, id).await
}

async fn yarn_enqueue(
    State(state): State<ApiV2State>,
    headers: HeaderMap,
    Path(id): Path<i64>,
) -> Response {
    enqueue(&state, &headers, ArchiveEntityKind::Yarn, id).await
}

async fn enqueue(
    state: &ApiV2State,
    headers: &HeaderMap,
    kind: ArchiveEntityKind,
    id: i64,
) -> Response {
    let owner_id = match mutation_user(&state.app, headers).await {
        Ok(owner_id) => owner_id,
        Err(response) => return *response,
    };
    if !state.app.archive_enabled {
        return unavailable();
    }
    match archive_jobs::enqueue(&state.app.pool, owner_id, kind, id).await {
        Ok(Some(job)) => (
            StatusCode::ACCEPTED,
            Json(Envelope {
                data: ArchiveAccepted {
                    state: state_name(job.state),
                },
            }),
        )
            .into_response(),
        Ok(None) => not_found(),
        Err(_) => internal_error(),
    }
}

fn state_name(state: ArchiveJobState) -> &'static str {
    match state {
        ArchiveJobState::Pending => "pending",
        ArchiveJobState::Running => "running",
        ArchiveJobState::Archived => "archived",
        ArchiveJobState::Failed => "failed",
        ArchiveJobState::Superseded => "superseded",
    }
}

fn not_found() -> Response {
    super::api_error(StatusCode::NOT_FOUND, "not_found", "Resource not found")
}

fn unavailable() -> Response {
    super::api_error(
        StatusCode::SERVICE_UNAVAILABLE,
        "archive_unavailable",
        "Archiving is unavailable",
    )
}

fn internal_error() -> Response {
    super::api_error(
        StatusCode::INTERNAL_SERVER_ERROR,
        "internal_error",
        "Request failed",
    )
}
