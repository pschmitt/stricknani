//! Browser-session and API-PAT administration endpoints for user accounts.

use std::collections::BTreeMap;

use axum::{
    Json, Router,
    extract::{DefaultBodyLimit, Path, Query, State, rejection::JsonRejection},
    http::StatusCode,
    response::{IntoResponse, Response},
    routing::{get, patch, post},
};
use serde::{Deserialize, Serialize};
use zeroize::Zeroizing;

use super::{
    ApiV2State, Envelope, access, api_error, api_error_fields, append_set_cookie, clear_cookie,
};

pub(super) fn router() -> Router<ApiV2State> {
    Router::new()
        .route("/admin/users", get(list_users).post(create_user))
        .route("/admin/users/{id}/active", patch(set_active))
        .route(
            "/admin/users/{id}/avatar/{filename}",
            get(user_avatar_thumbnail),
        )
        .route("/admin/users/{id}/reset-password", post(reset_password))
        .layer(DefaultBodyLimit::max(8 * 1024))
}

async fn user_avatar_thumbnail(
    State(state): State<ApiV2State>,
    headers: axum::http::HeaderMap,
    Path((id, filename)): Path<(i64, String)>,
) -> Response {
    let actor = match access::admin_user(&state.app, &headers).await {
        Ok(actor) => actor,
        Err(response) => return *response,
    };
    match crate::media::serve_admin_user_avatar_thumbnail(&state.app, actor, id, &filename).await {
        Ok(response) => response,
        Err(crate::media::AdminAvatarError::Forbidden) => forbidden(),
        Err(crate::media::AdminAvatarError::NotFound) => {
            api_error(StatusCode::NOT_FOUND, "not_found", "Avatar not found")
        }
        Err(crate::media::AdminAvatarError::Database) => internal_error(),
        Err(crate::media::AdminAvatarError::Unavailable) => api_error(
            StatusCode::SERVICE_UNAVAILABLE,
            "media_unavailable",
            "Avatar storage is unavailable",
        ),
    }
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct PageQuery {
    page: Option<u32>,
}

#[derive(Serialize)]
struct PageMeta {
    page: u32,
    per_page: u32,
    has_more: bool,
}

#[derive(Serialize)]
struct PageData<T> {
    data: T,
    meta: PageMeta,
}

async fn list_users(
    State(state): State<ApiV2State>,
    headers: axum::http::HeaderMap,
    query: Result<Query<PageQuery>, axum::extract::rejection::QueryRejection>,
) -> Response {
    let actor = match access::admin_user(&state.app, &headers).await {
        Ok(actor) => actor,
        Err(response) => return *response,
    };
    let Query(query) = match query {
        Ok(query) => query,
        Err(_) => return invalid_query(),
    };
    let page = query.page.unwrap_or(1);
    match crate::admin_service::list_users_page(
        &state.app.pool,
        actor,
        page,
        state.app.gravatar.enabled,
    )
    .await
    {
        Ok(result) => Json(PageData {
            data: result.users,
            meta: PageMeta {
                page: result.page,
                per_page: result.per_page,
                has_more: result.has_more,
            },
        })
        .into_response(),
        Err(crate::admin_service::AdminError::Forbidden) => forbidden(),
        Err(crate::admin_service::AdminError::InvalidPage) => invalid_query(),
        Err(_) => internal_error(),
    }
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct CreateUserRequest {
    email: String,
    password: String,
    #[serde(default)]
    is_admin: bool,
}

async fn create_user(
    State(state): State<ApiV2State>,
    headers: axum::http::HeaderMap,
    request: Result<Json<CreateUserRequest>, JsonRejection>,
) -> Response {
    let actor = match access::admin_mutation_user(&state.app, &headers).await {
        Ok(actor) => actor,
        Err(response) => return *response,
    };
    let Json(request) = match request {
        Ok(request) => request,
        Err(rejection) => return json_rejection(rejection),
    };
    match crate::admin_service::create_user(
        &state.app.pool,
        actor,
        request.email,
        Zeroizing::new(request.password),
        request.is_admin,
        state.app.gravatar.enabled,
    )
    .await
    {
        Ok(user) => (StatusCode::CREATED, Json(Envelope { data: user })).into_response(),
        Err(error) => map_admin_error(error, "password"),
    }
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct SetActiveRequest {
    active: bool,
}

async fn set_active(
    State(state): State<ApiV2State>,
    headers: axum::http::HeaderMap,
    Path(id): Path<i64>,
    request: Result<Json<SetActiveRequest>, JsonRejection>,
) -> Response {
    let actor = match access::admin_mutation_user(&state.app, &headers).await {
        Ok(actor) => actor,
        Err(response) => return *response,
    };
    let Json(request) = match request {
        Ok(request) => request,
        Err(rejection) => return json_rejection(rejection),
    };
    match crate::admin_service::set_user_active(
        &state.app.pool,
        actor,
        id,
        request.active,
        state.app.gravatar.enabled,
    )
    .await
    {
        Ok(user) => Json(Envelope { data: user }).into_response(),
        Err(error) => map_admin_error(error, "active"),
    }
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct ResetPasswordRequest {
    new_password: String,
}

#[derive(Serialize)]
struct ResetPasswordData {
    id: i64,
    session_revoked: bool,
}

async fn reset_password(
    State(state): State<ApiV2State>,
    headers: axum::http::HeaderMap,
    Path(id): Path<i64>,
    request: Result<Json<ResetPasswordRequest>, JsonRejection>,
) -> Response {
    let actor = match access::admin_mutation_user(&state.app, &headers).await {
        Ok(actor) => actor,
        Err(response) => return *response,
    };
    let Json(request) = match request {
        Ok(request) => request,
        Err(rejection) => return json_rejection(rejection),
    };
    match crate::admin_service::reset_password(
        &state.app.pool,
        actor,
        id,
        Zeroizing::new(request.new_password),
    )
    .await
    {
        Ok(()) => {
            let mut response = Json(Envelope {
                data: ResetPasswordData {
                    id,
                    session_revoked: true,
                },
            })
            .into_response();
            if actor == id {
                append_set_cookie(
                    &mut response,
                    clear_cookie(
                        crate::auth::session_cookie_name(state.app.secure_cookies),
                        state.app.secure_cookies,
                    ),
                );
            }
            response
        }
        Err(error) => map_admin_error(error, "new_password"),
    }
}

fn map_admin_error(error: crate::admin_service::AdminError, field: &'static str) -> Response {
    use crate::admin_service::AdminError;
    match error {
        AdminError::Forbidden => forbidden(),
        AdminError::NotFound => api_error(StatusCode::NOT_FOUND, "not_found", "User not found"),
        AdminError::Conflict => api_error(
            StatusCode::CONFLICT,
            "admin_conflict",
            "This change would leave the account system without an active administrator",
        ),
        AdminError::InvalidEmail => {
            field_error("invalid_email", "Enter a valid email address", "email")
        }
        AdminError::PasswordPolicy(crate::auth::NewPasswordError::TooShort) => field_error(
            "invalid_password",
            "Password must contain at least 12 characters",
            field,
        ),
        AdminError::PasswordTooLong => {
            field_error("password_too_long", "Password is too long", field)
        }
        AdminError::PasswordPolicy(crate::auth::NewPasswordError::Common) => {
            field_error("common_password", "Choose a less common password", field)
        }
        AdminError::AccountExists => api_error_fields(
            StatusCode::CONFLICT,
            "email_in_use",
            "An account with that email already exists",
            BTreeMap::from([("email", "An account with that email already exists")]),
        ),
        AdminError::InvalidPage => invalid_query(),
        AdminError::Database => internal_error(),
    }
}

fn invalid_query() -> Response {
    api_error(
        StatusCode::BAD_REQUEST,
        "invalid_query",
        "Page must be between 1 and 10000",
    )
}

fn field_error(code: &'static str, message: &'static str, field: &'static str) -> Response {
    api_error_fields(
        StatusCode::UNPROCESSABLE_ENTITY,
        code,
        message,
        BTreeMap::from([(field, message)]),
    )
}

fn forbidden() -> Response {
    api_error(
        StatusCode::FORBIDDEN,
        "admin_required",
        "Administrator access is required",
    )
}

fn internal_error() -> Response {
    api_error(
        StatusCode::INTERNAL_SERVER_ERROR,
        "internal_error",
        "Request failed",
    )
}

fn json_rejection(rejection: JsonRejection) -> Response {
    let status = rejection.status();
    if status == StatusCode::PAYLOAD_TOO_LARGE {
        api_error(
            StatusCode::PAYLOAD_TOO_LARGE,
            "payload_too_large",
            "Request body is too large",
        )
    } else {
        api_error(
            StatusCode::BAD_REQUEST,
            "invalid_json",
            "Request body is invalid",
        )
    }
}
