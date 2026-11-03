//! Same-origin browser media upload and delete routes.

use crate::{
    db::AppState,
    media::{self, MediaOperationError, UploadError},
    media_ids::Namespace,
};
use axum::{
    Json, Router,
    extract::{DefaultBodyLimit, Path, State, multipart::MultipartRejection},
    http::{HeaderMap, StatusCode},
    response::{IntoResponse, Response},
    routing::post,
};

use super::{ApiV2State, Envelope, access::resource_mutation_user};

const MAX_MULTIPART_BODY_BYTES: usize = 26 * 1024 * 1024;

pub(super) fn router() -> Router<ApiV2State> {
    Router::new()
        .route(
            "/projects/{project_id}/images/title",
            post(upload_title_image),
        )
        .route(
            "/projects/{project_id}/steps/{step_id}/images",
            post(upload_step_image),
        )
        .route(
            "/projects/{project_id}/attachments",
            post(upload_attachment),
        )
        .route(
            "/projects/{project_id}/images/{image_id}",
            axum::routing::delete(delete_project_image),
        )
        .route(
            "/projects/{project_id}/attachments/{attachment_id}",
            axum::routing::delete(delete_project_attachment),
        )
        .route("/yarns/{yarn_id}/photos", post(upload_yarn_photo))
        .route(
            "/yarns/{yarn_id}/photos/{photo_id}",
            axum::routing::delete(delete_yarn_photo),
        )
        .layer(DefaultBodyLimit::max(MAX_MULTIPART_BODY_BYTES))
}

fn media_error(error: MediaOperationError) -> Response {
    match error {
        MediaOperationError::NotFound(_) => {
            super::api_error(StatusCode::NOT_FOUND, "not_found", "Media item not found")
        }
        MediaOperationError::ServiceUnavailable => super::api_error(
            StatusCode::SERVICE_UNAVAILABLE,
            "media_unavailable",
            "Media storage is unavailable",
        ),
        MediaOperationError::Upload(UploadError::TooLarge) => super::api_error(
            StatusCode::PAYLOAD_TOO_LARGE,
            "payload_too_large",
            "Uploaded file is too large",
        ),
        MediaOperationError::Upload(UploadError::InvalidImage) => super::api_error(
            StatusCode::BAD_REQUEST,
            "invalid_image",
            "Uploaded file is not a supported image",
        ),
        MediaOperationError::Upload(UploadError::BadMultipart) => super::api_error(
            StatusCode::BAD_REQUEST,
            "invalid_multipart",
            "Multipart upload is invalid",
        ),
        MediaOperationError::Database => super::api_error(
            StatusCode::INTERNAL_SERVER_ERROR,
            "internal_error",
            "Request failed",
        ),
    }
}

fn multipart_error(error: MultipartRejection) -> Response {
    if error.status() == StatusCode::PAYLOAD_TOO_LARGE {
        super::api_error(
            StatusCode::PAYLOAD_TOO_LARGE,
            "payload_too_large",
            "Uploaded file is too large",
        )
    } else {
        super::api_error(
            StatusCode::BAD_REQUEST,
            "invalid_multipart",
            "Multipart upload is invalid",
        )
    }
}

async fn upload_title_image(
    State(state): State<ApiV2State>,
    headers: HeaderMap,
    Path(project_id): Path<i64>,
    multipart: Result<axum::extract::Multipart, MultipartRejection>,
) -> Response {
    let user_id = match resource_mutation_user(&state.app, &headers).await {
        Ok(user_id) => user_id,
        Err(error) => return *error,
    };
    let multipart = match multipart {
        Ok(multipart) => multipart,
        Err(error) => return multipart_error(error),
    };
    match media::upload_project_image(&state.app, user_id, project_id, None, "title", multipart)
        .await
    {
        Ok(data) => (StatusCode::CREATED, Json(Envelope { data })).into_response(),
        Err(error) => media_error(error),
    }
}

async fn upload_step_image(
    State(state): State<ApiV2State>,
    headers: HeaderMap,
    Path((project_id, step_id)): Path<(i64, i64)>,
    multipart: Result<axum::extract::Multipart, MultipartRejection>,
) -> Response {
    let user_id = match resource_mutation_user(&state.app, &headers).await {
        Ok(user_id) => user_id,
        Err(error) => return *error,
    };
    let multipart = match multipart {
        Ok(multipart) => multipart,
        Err(error) => return multipart_error(error),
    };
    match media::upload_project_image(
        &state.app,
        user_id,
        project_id,
        Some(step_id),
        "step",
        multipart,
    )
    .await
    {
        Ok(data) => (StatusCode::CREATED, Json(Envelope { data })).into_response(),
        Err(error) => media_error(error),
    }
}

async fn upload_attachment(
    State(state): State<ApiV2State>,
    headers: HeaderMap,
    Path(project_id): Path<i64>,
    multipart: Result<axum::extract::Multipart, MultipartRejection>,
) -> Response {
    let user_id = match resource_mutation_user(&state.app, &headers).await {
        Ok(user_id) => user_id,
        Err(error) => return *error,
    };
    let multipart = match multipart {
        Ok(multipart) => multipart,
        Err(error) => return multipart_error(error),
    };
    match media::upload_project_attachment(&state.app, user_id, project_id, multipart).await {
        Ok(data) => (StatusCode::CREATED, Json(Envelope { data })).into_response(),
        Err(error) => media_error(error),
    }
}

async fn upload_yarn_photo(
    State(state): State<ApiV2State>,
    headers: HeaderMap,
    Path(yarn_id): Path<i64>,
    multipart: Result<axum::extract::Multipart, MultipartRejection>,
) -> Response {
    let user_id = match resource_mutation_user(&state.app, &headers).await {
        Ok(user_id) => user_id,
        Err(error) => return *error,
    };
    let multipart = match multipart {
        Ok(multipart) => multipart,
        Err(error) => return multipart_error(error),
    };
    match media::upload_yarn_photo_for_owner(&state.app, user_id, yarn_id, multipart).await {
        Ok(data) => (StatusCode::CREATED, Json(Envelope { data })).into_response(),
        Err(error) => media_error(error),
    }
}

async fn delete_project_image(
    State(state): State<ApiV2State>,
    headers: HeaderMap,
    Path((project_id, image_id)): Path<(i64, i64)>,
) -> Response {
    delete_project_media(state.app, headers, project_id, image_id, Namespace::Image).await
}

async fn delete_project_attachment(
    State(state): State<ApiV2State>,
    headers: HeaderMap,
    Path((project_id, attachment_id)): Path<(i64, i64)>,
) -> Response {
    delete_project_media(
        state.app,
        headers,
        project_id,
        attachment_id,
        Namespace::Attachment,
    )
    .await
}

async fn delete_project_media(
    state: AppState,
    headers: HeaderMap,
    project_id: i64,
    media_id: i64,
    namespace: Namespace,
) -> Response {
    let user_id = match resource_mutation_user(&state, &headers).await {
        Ok(user_id) => user_id,
        Err(error) => return *error,
    };
    match media::delete_project_media(&state, user_id, project_id, media_id, namespace).await {
        Ok(()) => StatusCode::NO_CONTENT.into_response(),
        Err(error) => media_error(error),
    }
}

async fn delete_yarn_photo(
    State(state): State<ApiV2State>,
    headers: HeaderMap,
    Path((yarn_id, photo_id)): Path<(i64, i64)>,
) -> Response {
    let user_id = match resource_mutation_user(&state.app, &headers).await {
        Ok(user_id) => user_id,
        Err(error) => return *error,
    };
    match media::delete_yarn_photo_for_owner(&state.app, user_id, yarn_id, photo_id).await {
        Ok(()) => StatusCode::NO_CONTENT.into_response(),
        Err(error) => media_error(error),
    }
}
