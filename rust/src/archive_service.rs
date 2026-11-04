//! Native-authenticated API routes for archive jobs.

use axum::{
    Json, Router,
    extract::{DefaultBodyLimit, Path, State},
    http::{HeaderMap, StatusCode},
    response::{IntoResponse, Response},
    routing::post,
};
use serde::Serialize;
use serde_json::json;

use crate::{
    archive_jobs::{self, ArchiveEntityKind, ArchiveJobState},
    auth,
    db::AppState,
    web,
};

#[derive(Clone)]
struct ArchiveState {
    web: web::WebState,
    enabled: bool,
}

/// Build the API retry endpoints for the optional archive worker.
pub fn router(api: Router, app: AppState, enabled: bool) -> Router {
    let state = ArchiveState {
        web: web::WebState { api, app },
        enabled,
    };
    Router::new()
        .route("/api/v1/projects/{id}/archive", post(api_project_archive))
        .route("/api/v1/yarns/{id}/archive", post(api_yarn_archive))
        .layer(DefaultBodyLimit::max(4 * 1024))
        .with_state(state)
}

#[derive(Serialize)]
struct ArchiveResponse {
    state: &'static str,
}

async fn api_project_archive(
    State(state): State<ArchiveState>,
    headers: HeaderMap,
    Path(id): Path<i64>,
) -> Response {
    api_enqueue(&state, &headers, ArchiveEntityKind::Project, id).await
}

async fn api_yarn_archive(
    State(state): State<ArchiveState>,
    headers: HeaderMap,
    Path(id): Path<i64>,
) -> Response {
    api_enqueue(&state, &headers, ArchiveEntityKind::Yarn, id).await
}

async fn api_enqueue(
    state: &ArchiveState,
    headers: &HeaderMap,
    kind: ArchiveEntityKind,
    id: i64,
) -> Response {
    let owner_id = match auth::bearer_user(&state.web.app.pool, headers).await {
        Ok(owner_id) => owner_id,
        Err(error) => return error.into_response(),
    };
    if !state.enabled {
        return api_error(StatusCode::SERVICE_UNAVAILABLE, "Archival is disabled");
    }
    match archive_jobs::enqueue(&state.web.app.pool, owner_id, kind, id).await {
        Ok(Some(job)) => (
            StatusCode::ACCEPTED,
            Json(ArchiveResponse {
                state: state_name(job.state),
            }),
        )
            .into_response(),
        Ok(None) => api_error(StatusCode::NOT_FOUND, "Not found"),
        Err(_error) => api_error(StatusCode::INTERNAL_SERVER_ERROR, "Archive job failed"),
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

fn api_error(status: StatusCode, detail: &'static str) -> Response {
    (status, Json(json!({ "detail": detail }))).into_response()
}
