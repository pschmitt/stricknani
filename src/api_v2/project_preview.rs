//! Authenticated Markdown previews for unsaved project text.

use axum::{
    Json, Router,
    body::{Body, to_bytes},
    extract::State,
    http::{Request, StatusCode},
    response::{IntoResponse, Response},
    routing::post,
};
use serde::{Deserialize, Serialize};

use super::{ApiV2State, Envelope, access};

const MAX_PROJECT_JSON_BYTES: usize = 2 * 1024 * 1024;

pub(super) fn router() -> Router<ApiV2State> {
    Router::new().route("/projects/preview", post(preview))
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct ProjectPreviewRequest {
    description: Option<String>,
    notes: Option<String>,
}

#[derive(Serialize)]
struct ProjectPreview {
    description_html: Option<String>,
    notes_html: Option<String>,
}

async fn preview(State(state): State<ApiV2State>, request: Request<Body>) -> Response {
    let owner_id = match access::resource_mutation_user(&state.app, request.headers()).await {
        Ok(owner_id) => owner_id,
        Err(response) => return *response,
    };

    if let Err(retry_after) = state.project_preview_limiter.reserve(owner_id) {
        return access::rate_limited(retry_after, "Too many project previews; try again later");
    }

    let bytes = match to_bytes(request.into_body(), MAX_PROJECT_JSON_BYTES).await {
        Ok(bytes) => bytes,
        Err(_) => {
            return super::api_error(
                StatusCode::PAYLOAD_TOO_LARGE,
                "payload_too_large",
                "Request body is too large",
            );
        }
    };
    let request = match serde_json::from_slice::<ProjectPreviewRequest>(&bytes) {
        Ok(request) => request,
        Err(_) => {
            return super::api_error(
                StatusCode::UNPROCESSABLE_ENTITY,
                "validation_error",
                "Request JSON is invalid",
            );
        }
    };

    let permit = match state.project_preview_slots.clone().try_acquire_owned() {
        Ok(permit) => permit,
        Err(_) => {
            return access::rate_limited(1, "Project preview capacity is busy; try again soon");
        }
    };
    let render = tokio::task::spawn_blocking(move || {
        let _permit = permit;
        ProjectPreview {
            description_html: request.description.as_deref().map(crate::web::rich_text),
            notes_html: request.notes.as_deref().map(crate::web::rich_text),
        }
    })
    .await;

    match render {
        Ok(data) => Json(Envelope { data }).into_response(),
        Err(_) => super::api_error(
            StatusCode::INTERNAL_SERVER_ERROR,
            "internal_error",
            "Request failed",
        ),
    }
}
