//! Public account registration for the cookie-authenticated browser API.

use std::{collections::BTreeMap, net::SocketAddr};

use axum::{
    Json, Router,
    extract::{ConnectInfo, DefaultBodyLimit, Extension, State, rejection::JsonRejection},
    http::{HeaderMap, StatusCode},
    response::{IntoResponse, Response},
    routing::post,
};
use serde::{Deserialize, Serialize};
use zeroize::Zeroizing;

use crate::{auth, db};

use super::{ApiV2State, Envelope, api_error, api_error_fields, append_set_cookie};

pub(super) fn router() -> Router<ApiV2State> {
    Router::new()
        .route("/auth/signup", post(signup))
        .layer(DefaultBodyLimit::max(8 * 1024))
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct SignupRequest {
    email: String,
    password: String,
}

#[derive(Serialize)]
struct SignupData {
    id: i64,
    email: String,
    is_admin: bool,
}

async fn signup(
    State(state): State<ApiV2State>,
    headers: HeaderMap,
    peer: Option<Extension<ConnectInfo<SocketAddr>>>,
    request: Result<Json<SignupRequest>, JsonRejection>,
) -> Response {
    if !state.app.signup_enabled {
        return api_error(
            StatusCode::NOT_FOUND,
            "signup_disabled",
            "Account registration is unavailable",
        );
    }
    if !super::same_origin_request(&headers, state.app.secure_cookies) {
        return api_error(
            StatusCode::FORBIDDEN,
            "csrf_failed",
            "Request origin was rejected",
        );
    }
    let Some(key) = state.csrf_key.as_ref() else {
        return api_error(
            StatusCode::INTERNAL_SERVER_ERROR,
            "internal_error",
            "Request failed",
        );
    };
    let cookie = auth::cookie(
        &headers,
        super::login_csrf_cookie_name(state.app.secure_cookies),
    )
    .unwrap_or_default();
    let supplied = super::header_text(&headers, "x-csrf-token").unwrap_or_default();
    if !super::valid_login_csrf(key, cookie, supplied) {
        return api_error(
            StatusCode::FORBIDDEN,
            "csrf_failed",
            "CSRF token is missing or expired",
        );
    }

    let Json(mut request) = match request {
        Ok(request) => request,
        Err(rejection) => return json_rejection(rejection),
    };
    let mut password = Zeroizing::new(std::mem::take(&mut request.password));
    let email = request.email.trim().to_lowercase();
    let ip = peer
        .map(|Extension(ConnectInfo(address))| address.ip().to_string())
        .unwrap_or_else(|| "unknown".to_owned());
    let keys = [auth::rate_key("ip", &ip), auth::rate_key("email", &email)];
    if state.app.auth_limiter.reserve(&keys).is_none() {
        return super::with_retry_after(api_error(
            StatusCode::TOO_MANY_REQUESTS,
            "rate_limited",
            "Too many registration attempts",
        ));
    }

    if email.is_empty()
        || email.len() > 320
        || !email.contains('@')
        || email.contains(char::is_whitespace)
    {
        return validation_error("email", "Enter a valid email address.");
    }
    if password.len() > 1024 {
        return validation_error("password", "Password is too long.");
    }
    if let Err(error) = auth::validate_new_password(&password) {
        return validation_error(
            "password",
            match error {
                auth::NewPasswordError::TooShort => "Use at least 12 characters.",
                auth::NewPasswordError::Common => "Choose a less common password.",
            },
        );
    }

    let password = std::mem::take(&mut *password);
    match db::create_account(&state.app.pool, &email, password, db::AccountRole::Member).await {
        Ok(id) => {
            let mut response = (
                StatusCode::CREATED,
                Json(Envelope {
                    data: SignupData {
                        id,
                        email,
                        is_admin: false,
                    },
                }),
            )
                .into_response();
            append_set_cookie(
                &mut response,
                super::clear_cookie(
                    super::login_csrf_cookie_name(state.app.secure_cookies),
                    state.app.secure_cookies,
                ),
            );
            response
        }
        Err(db::DatabaseError::InvalidAccount) => {
            validation_error("password", "Password does not meet the account policy.")
        }
        Err(db::DatabaseError::Account(error))
            if error
                .as_database_error()
                .is_some_and(sqlx::error::DatabaseError::is_unique_violation) =>
        {
            api_error(
                StatusCode::CONFLICT,
                "account_exists",
                "An account already exists with that email",
            )
        }
        Err(_) => api_error(
            StatusCode::INTERNAL_SERVER_ERROR,
            "internal_error",
            "Request failed",
        ),
    }
}

fn validation_error(field: &'static str, message: &'static str) -> Response {
    api_error_fields(
        StatusCode::UNPROCESSABLE_ENTITY,
        "validation_error",
        "Check the submitted fields",
        BTreeMap::from([(field, message)]),
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
