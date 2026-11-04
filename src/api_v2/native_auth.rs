//! Native clients exchange account credentials for an API-purpose PAT.

use std::{collections::BTreeMap, net::SocketAddr};

use axum::{
    Extension, Json, Router,
    extract::{ConnectInfo, DefaultBodyLimit, State, rejection::JsonRejection},
    http::StatusCode,
    response::{IntoResponse, Response},
    routing::post,
};
use serde::{Deserialize, Serialize};
use zeroize::Zeroizing;

use crate::{
    account_service::{MAX_DEVICE_NAME_CHARS, clean_device_name, device_token_name},
    auth,
};

use super::{ApiV2State, access};

const DEFAULT_TOKEN_NAME: &str = "Android (password login)";

pub(super) fn router() -> Router<ApiV2State> {
    Router::new()
        .route("/auth/token", post(mint_token))
        .layer(DefaultBodyLimit::max(8 * 1024))
}

#[derive(Deserialize)]
struct TokenRequest {
    email: String,
    password: String,
    token_name: Option<String>,
    /// Device label; names the token `Android · <device_name>`.
    device_name: Option<String>,
}

#[derive(Serialize)]
struct TokenEnvelope {
    data: TokenData,
}

#[derive(Serialize)]
struct TokenData {
    token: String,
}

async fn mint_token(
    State(state): State<ApiV2State>,
    peer: Option<Extension<ConnectInfo<SocketAddr>>>,
    request: Result<Json<TokenRequest>, JsonRejection>,
) -> Response {
    let Json(mut request) = match request {
        Ok(request) => request,
        Err(rejection) => return json_rejection(rejection),
    };
    let password = Zeroizing::new(std::mem::take(&mut request.password));
    if request.email.trim().to_lowercase().len() > auth::MAX_EMAIL_BYTES {
        return validation_error("email", "Email is too long");
    }
    if password.len() > auth::MAX_PASSWORD_BYTES {
        return validation_error("password", "Password is too long");
    }
    if request
        .token_name
        .as_ref()
        .is_some_and(|name| name.len() > auth::MAX_TOKEN_NAME_BYTES)
    {
        return validation_error("token_name", "Token name is too long");
    }

    if request
        .device_name
        .as_ref()
        .is_some_and(|name| name.trim().chars().count() > MAX_DEVICE_NAME_CHARS)
    {
        return validation_error("device_name", "Device name is too long");
    }

    let email = request.email;
    let name = request
        .device_name
        .as_deref()
        .and_then(clean_device_name)
        .map(device_token_name)
        .or_else(|| {
            request
                .token_name
                .map(|name| name.trim().to_owned())
                .filter(|name| !name.is_empty())
        })
        .unwrap_or_else(|| DEFAULT_TOKEN_NAME.to_owned());
    let ip = peer
        .map(|Extension(ConnectInfo(address))| address.ip().to_string())
        .unwrap_or_else(|| "unknown".to_owned());

    match auth::mint_api_token(&state.app, &ip, &email, password, &name).await {
        Ok(token) => Json(TokenEnvelope {
            data: TokenData { token: token.token },
        })
        .into_response(),
        Err(auth::PasswordTokenError::InvalidCredentials) => api_error(
            StatusCode::UNAUTHORIZED,
            "invalid_credentials",
            "Email or password is incorrect",
        ),
        Err(auth::PasswordTokenError::RateLimited) => {
            access::rate_limited(auth::RATE_LIMIT_WINDOW.as_secs(), "Too many login attempts")
        }
        Err(auth::PasswordTokenError::InvalidRequest) => {
            validation_error("email", "Email or password length is invalid")
        }
        Err(auth::PasswordTokenError::Database | auth::PasswordTokenError::Internal) => api_error(
            StatusCode::INTERNAL_SERVER_ERROR,
            "internal_error",
            "Request failed",
        ),
    }
}

fn validation_error(field: &'static str, message: &'static str) -> Response {
    let fields = BTreeMap::from([(field, message)]);
    super::api_error_fields(
        StatusCode::UNPROCESSABLE_ENTITY,
        "validation_error",
        "Check the submitted fields",
        fields,
    )
}

fn json_rejection(rejection: JsonRejection) -> Response {
    if rejection.status() == StatusCode::PAYLOAD_TOO_LARGE {
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

fn api_error(status: StatusCode, code: &'static str, message: &'static str) -> Response {
    super::api_error(status, code, message)
}
