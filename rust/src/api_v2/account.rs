//! Browser self-service account routes.

use std::collections::BTreeMap;

use axum::{
    Json, Router,
    extract::{
        DefaultBodyLimit, Multipart, Path, State, multipart::MultipartRejection,
        rejection::JsonRejection,
    },
    http::{HeaderMap, StatusCode},
    response::{IntoResponse, Response},
    routing::{get, post},
};
use serde::Deserialize;
use zeroize::Zeroizing;

use crate::{
    account_service::{self, AccountError},
    auth, media,
};

use super::{
    ApiV2State, Envelope,
    access::{browser_user, mutation_user, rate_limited},
};

const MAX_AVATAR_REQUEST_BYTES: usize = 26 * 1024 * 1024;

pub(super) fn router() -> Router<ApiV2State> {
    let json_routes = Router::new()
        .route("/account", get(get_account))
        .route("/account/api-tokens", get(list_tokens).post(create_token))
        .route(
            "/account/api-tokens/{id}",
            axum::routing::delete(revoke_token),
        )
        .route("/account/password", axum::routing::put(change_password))
        .layer(DefaultBodyLimit::max(2 * 1024 * 1024));
    let avatar_routes = Router::new()
        .route("/account/avatar", post(upload_avatar).delete(delete_avatar))
        .layer(DefaultBodyLimit::max(MAX_AVATAR_REQUEST_BYTES));
    json_routes.merge(avatar_routes)
}

async fn get_account(State(state): State<ApiV2State>, headers: HeaderMap) -> Response {
    let user_id = match browser_user(&state.app, &headers).await {
        Ok(user_id) => user_id,
        Err(error) => return *error,
    };
    match account_service::account_dto(&state.app.pool, user_id).await {
        Ok(data) => Json(Envelope { data }).into_response(),
        Err(error) => account_error(error),
    }
}

async fn list_tokens(State(state): State<ApiV2State>, headers: HeaderMap) -> Response {
    let user_id = match browser_user(&state.app, &headers).await {
        Ok(user_id) => user_id,
        Err(error) => return *error,
    };
    match account_service::list_tokens(&state.app.pool, user_id).await {
        Ok(data) => Json(Envelope { data }).into_response(),
        Err(error) => account_error(error),
    }
}

#[derive(Deserialize)]
struct CreateTokenRequest {
    name: Option<String>,
}

async fn create_token(
    State(state): State<ApiV2State>,
    headers: HeaderMap,
    request: Result<Json<CreateTokenRequest>, JsonRejection>,
) -> Response {
    let user_id = match mutation_user(&state.app, &headers).await {
        Ok(user_id) => user_id,
        Err(error) => return *error,
    };
    let Json(request) = match request {
        Ok(request) => request,
        Err(error) => return json_rejection(error),
    };
    match account_service::create_token(&state.app.pool, user_id, request.name).await {
        Ok(token) => (
            StatusCode::CREATED,
            Json(Envelope {
                data: CreatedTokenDto {
                    id: token.id,
                    name: token.name,
                    created_at: token.created_at,
                    token: token.token.to_string(),
                },
            }),
        )
            .into_response(),
        Err(error) => account_error(error),
    }
}

#[derive(serde::Serialize)]
struct CreatedTokenDto {
    id: i64,
    name: String,
    created_at: String,
    token: String,
}

async fn revoke_token(
    State(state): State<ApiV2State>,
    headers: HeaderMap,
    Path(id): Path<i64>,
) -> Response {
    let user_id = match mutation_user(&state.app, &headers).await {
        Ok(user_id) => user_id,
        Err(error) => return *error,
    };
    match account_service::revoke_token(&state.app.pool, user_id, id).await {
        Ok(true) => StatusCode::NO_CONTENT.into_response(),
        Ok(false) => super::api_error(StatusCode::NOT_FOUND, "not_found", "API token not found"),
        Err(error) => account_error(error),
    }
}

#[derive(Deserialize)]
struct ChangePasswordRequest {
    current_password: String,
    new_password: String,
}

async fn change_password(
    State(state): State<ApiV2State>,
    headers: HeaderMap,
    request: Result<Json<ChangePasswordRequest>, JsonRejection>,
) -> Response {
    let user_id = match mutation_user(&state.app, &headers).await {
        Ok(user_id) => user_id,
        Err(error) => return *error,
    };
    let Json(mut request) = match request {
        Ok(request) => request,
        Err(error) => return json_rejection(error),
    };
    let current_password = Zeroizing::new(std::mem::take(&mut request.current_password));
    let new_password = Zeroizing::new(std::mem::take(&mut request.new_password));
    if current_password.is_empty() || current_password.len() > account_service::MAX_PASSWORD_BYTES {
        return field_error("current_password", "Current password is invalid");
    }
    match account_service::change_password(&state.app.pool, user_id, current_password, new_password)
        .await
    {
        Ok(()) => {
            let mut response = StatusCode::NO_CONTENT.into_response();
            super::append_set_cookie(
                &mut response,
                super::clear_cookie(
                    auth::session_cookie_name(state.app.secure_cookies),
                    state.app.secure_cookies,
                ),
            );
            response
        }
        Err(error) => account_error(error),
    }
}

async fn upload_avatar(
    State(state): State<ApiV2State>,
    headers: HeaderMap,
    multipart: Result<Multipart, MultipartRejection>,
) -> Response {
    let user_id = match mutation_user(&state.app, &headers).await {
        Ok(user_id) => user_id,
        Err(error) => return *error,
    };
    let Some(root) = state.app.media_root.as_deref() else {
        return super::api_error(
            StatusCode::SERVICE_UNAVAILABLE,
            "media_unavailable",
            "Media storage is unavailable",
        );
    };
    let multipart = match multipart {
        Ok(multipart) => multipart,
        Err(error) => return multipart_error(error),
    };
    let upload = match media::read_avatar_upload(multipart).await {
        Ok(upload) => upload,
        Err(error) => return upload_error(error),
    };
    match account_service::save_avatar(&state.app.pool, root, user_id, upload).await {
        Ok(avatar_url) => Json(Envelope {
            data: AvatarDto { avatar_url },
        })
        .into_response(),
        Err(error) => account_error(error),
    }
}

async fn delete_avatar(State(state): State<ApiV2State>, headers: HeaderMap) -> Response {
    let user_id = match mutation_user(&state.app, &headers).await {
        Ok(user_id) => user_id,
        Err(error) => return *error,
    };
    match account_service::delete_avatar(&state.app.pool, user_id).await {
        Ok(()) => StatusCode::NO_CONTENT.into_response(),
        Err(error) => account_error(error),
    }
}

#[derive(serde::Serialize)]
struct AvatarDto {
    avatar_url: String,
}

fn field_error(field: &'static str, message: &'static str) -> Response {
    super::api_error_fields(
        StatusCode::UNPROCESSABLE_ENTITY,
        "validation_error",
        "Account fields are invalid",
        BTreeMap::from([(field, message)]),
    )
}

fn account_error(error: AccountError) -> Response {
    match error {
        AccountError::Database => super::api_error(
            StatusCode::INTERNAL_SERVER_ERROR,
            "internal_error",
            "Request failed",
        ),
        AccountError::InvalidTokenName => {
            field_error("name", "Token names must be 255 bytes or fewer")
        }
        AccountError::TokenRateLimited(retry_after) => {
            rate_limited(retry_after, "API token creation limit reached")
        }
        AccountError::TokenNotFound => super::api_error(
            StatusCode::NOT_FOUND,
            "not_found",
            "Account resource not found",
        ),
        AccountError::PasswordTooLong => {
            field_error("new_password", "Passwords must be 1024 bytes or fewer")
        }
        AccountError::PasswordPolicy(crate::auth::NewPasswordError::TooShort) => field_error(
            "new_password",
            "Password must contain at least 12 characters",
        ),
        AccountError::PasswordPolicy(crate::auth::NewPasswordError::Common) => {
            field_error("new_password", "Choose a less common password")
        }
        AccountError::CurrentPasswordIncorrect => {
            field_error("current_password", "Current password is incorrect")
        }
        AccountError::PasswordConflict => super::api_error(
            StatusCode::CONFLICT,
            "conflict",
            "Account credentials changed; sign in again",
        ),
        AccountError::InvalidAvatar(media::UploadError::TooLarge) => super::api_error(
            StatusCode::PAYLOAD_TOO_LARGE,
            "payload_too_large",
            "Uploaded image is too large",
        ),
        AccountError::InvalidAvatar(media::UploadError::InvalidImage) => super::api_error(
            StatusCode::BAD_REQUEST,
            "invalid_image",
            "Uploaded file is not a supported image",
        ),
        AccountError::InvalidAvatar(media::UploadError::BadMultipart) => super::api_error(
            StatusCode::BAD_REQUEST,
            "invalid_multipart",
            "Multipart upload is invalid",
        ),
    }
}

fn multipart_error(error: MultipartRejection) -> Response {
    if error.status() == StatusCode::PAYLOAD_TOO_LARGE {
        super::api_error(
            StatusCode::PAYLOAD_TOO_LARGE,
            "payload_too_large",
            "Uploaded image is too large",
        )
    } else {
        super::api_error(
            StatusCode::BAD_REQUEST,
            "invalid_multipart",
            "Multipart upload is invalid",
        )
    }
}

fn json_rejection(error: JsonRejection) -> Response {
    if error.status() == StatusCode::PAYLOAD_TOO_LARGE {
        super::api_error(
            StatusCode::PAYLOAD_TOO_LARGE,
            "payload_too_large",
            "Request body is too large",
        )
    } else {
        super::api_error(
            StatusCode::BAD_REQUEST,
            "invalid_json",
            "Invalid JSON request",
        )
    }
}

fn upload_error(error: media::UploadError) -> Response {
    account_error(AccountError::InvalidAvatar(error))
}
