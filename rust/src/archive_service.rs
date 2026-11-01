//! Native-authenticated API and CSRF-protected browser routes for archive jobs.

use axum::{
    Form, Json, Router,
    extract::{DefaultBodyLimit, Path, State, rejection::FormRejection},
    http::{HeaderMap, StatusCode},
    response::{IntoResponse, Redirect, Response},
    routing::post,
};
use serde::{Deserialize, Serialize};
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

/// Build API and browser retry endpoints for the optional archive worker.
pub fn router(api: Router, app: AppState, enabled: bool) -> Router {
    let state = ArchiveState {
        web: web::WebState { api, app },
        enabled,
    };
    Router::new()
        .route("/api/v1/projects/{id}/archive", post(api_project_archive))
        .route("/api/v1/yarns/{id}/archive", post(api_yarn_archive))
        .route("/projects/{id}/archive", post(browser_project_archive))
        .route("/yarns/{id}/archive", post(browser_yarn_archive))
        .layer(DefaultBodyLimit::max(4 * 1024))
        .with_state(state)
}

#[derive(Debug, Deserialize)]
struct ArchiveForm {
    csrf: String,
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

async fn browser_project_archive(
    State(state): State<ArchiveState>,
    headers: HeaderMap,
    Path(id): Path<i64>,
    form: Result<Form<ArchiveForm>, FormRejection>,
) -> Response {
    browser_enqueue(&state, &headers, form, ArchiveEntityKind::Project, id).await
}

async fn browser_yarn_archive(
    State(state): State<ArchiveState>,
    headers: HeaderMap,
    Path(id): Path<i64>,
    form: Result<Form<ArchiveForm>, FormRejection>,
) -> Response {
    browser_enqueue(&state, &headers, form, ArchiveEntityKind::Yarn, id).await
}

async fn browser_enqueue(
    state: &ArchiveState,
    headers: &HeaderMap,
    form: Result<Form<ArchiveForm>, FormRejection>,
    kind: ArchiveEntityKind,
    id: i64,
) -> Response {
    let session = match web::session(&state.web, headers).await {
        Ok(session) => session,
        Err(auth::AuthError::Unauthorized) => return Redirect::to("/login").into_response(),
        Err(_) => return StatusCode::INTERNAL_SERVER_ERROR.into_response(),
    };
    let Form(form) = match form {
        Ok(form) => form,
        Err(_) => return StatusCode::BAD_REQUEST.into_response(),
    };
    if !web::valid_csrf(&session.token, &form.csrf) {
        return StatusCode::FORBIDDEN.into_response();
    }
    if !state.enabled {
        return StatusCode::SERVICE_UNAVAILABLE.into_response();
    }
    match archive_jobs::enqueue(&state.web.app.pool, session.user_id, kind, id).await {
        Ok(Some(_job)) => Redirect::to(&entity_url(kind, id)).into_response(),
        Ok(None) => StatusCode::NOT_FOUND.into_response(),
        Err(_) => StatusCode::INTERNAL_SERVER_ERROR.into_response(),
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

fn entity_url(kind: ArchiveEntityKind, id: i64) -> String {
    match kind {
        ArchiveEntityKind::Project => format!("/projects/{id}"),
        ArchiveEntityKind::Yarn => format!("/yarns/{id}"),
    }
}

fn api_error(status: StatusCode, detail: &'static str) -> Response {
    (status, Json(json!({ "detail": detail }))).into_response()
}
