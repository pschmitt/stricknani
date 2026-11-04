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

use crate::{
    account_service::{MAX_DEVICE_NAME_CHARS, clean_device_name, device_token_name},
    auth, db,
};

use super::{ApiV2State, Envelope, api_error, api_error_fields, append_set_cookie};

pub(super) fn router() -> Router<ApiV2State> {
    Router::new()
        .route("/auth/signup", post(signup))
        .route("/auth/register", post(register))
        .layer(DefaultBodyLimit::max(8 * 1024))
}

/// Generic code for a registration that cannot proceed with these details.
/// It deliberately does not say whether the email is already taken.
const REGISTRATION_FAILED: &str = "registration_failed";
const REGISTER_TOKEN_NAME: &str = "Android (new account)";

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
        return signup_disabled();
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
    let password = Zeroizing::new(std::mem::take(&mut request.password));
    match create_member(&state, peer, &request.email, password).await {
        Ok(email) => {
            let mut response = (
                StatusCode::CREATED,
                Json(Envelope {
                    data: SignupData {
                        id: email.id,
                        email: email.email,
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
        Err(response) => *response,
    }
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct RegisterRequest {
    email: String,
    password: String,
    /// Device label; names the token `Android · <device_name>`.
    device_name: Option<String>,
}

#[derive(Serialize)]
struct RegisterData {
    token: String,
}

/// Native registration: creates the member and returns an API token, exactly
/// like `POST /auth/token` would for the same credentials. No cookies are
/// involved, so there is no CSRF or origin check.
async fn register(
    State(state): State<ApiV2State>,
    peer: Option<Extension<ConnectInfo<SocketAddr>>>,
    request: Result<Json<RegisterRequest>, JsonRejection>,
) -> Response {
    if !state.app.signup_enabled {
        return signup_disabled();
    }
    let Json(mut request) = match request {
        Ok(request) => request,
        Err(rejection) => return json_rejection(rejection),
    };
    if request
        .device_name
        .as_ref()
        .is_some_and(|name| name.trim().chars().count() > MAX_DEVICE_NAME_CHARS)
    {
        return validation_error("device_name", "Device name is too long.");
    }
    let name = request
        .device_name
        .as_deref()
        .and_then(clean_device_name)
        .map(device_token_name)
        .unwrap_or_else(|| REGISTER_TOKEN_NAME.to_owned());
    let ip = peer_ip(peer.as_ref());
    let password = Zeroizing::new(std::mem::take(&mut request.password));
    let token_password = Zeroizing::new((*password).clone());
    let created = match create_member(&state, peer, &request.email, password).await {
        Ok(created) => created,
        Err(response) => return *response,
    };
    match auth::mint_api_token(&state.app, &ip, &created.email, token_password, &name).await {
        Ok(token) => (
            StatusCode::CREATED,
            Json(Envelope {
                data: RegisterData { token: token.token },
            }),
        )
            .into_response(),
        // The account exists; the client can sign in with the same
        // credentials once the rate limit clears.
        Err(auth::PasswordTokenError::RateLimited) => super::with_retry_after(api_error(
            StatusCode::TOO_MANY_REQUESTS,
            "rate_limited",
            "Too many registration attempts",
        )),
        Err(_) => api_error(
            StatusCode::INTERNAL_SERVER_ERROR,
            "internal_error",
            "Request failed",
        ),
    }
}

struct CreatedMember {
    id: i64,
    email: String,
}

fn peer_ip(peer: Option<&Extension<ConnectInfo<SocketAddr>>>) -> String {
    peer.map(|Extension(ConnectInfo(address))| address.ip().to_string())
        .unwrap_or_else(|| "unknown".to_owned())
}

fn signup_disabled() -> Response {
    api_error(
        StatusCode::NOT_FOUND,
        "signup_disabled",
        "Account registration is unavailable",
    )
}

/// Rate limit, validate and create an ordinary (non-admin) member account
/// with the same email and password rules as the CLI and admin console.
async fn create_member(
    state: &ApiV2State,
    peer: Option<Extension<ConnectInfo<SocketAddr>>>,
    raw_email: &str,
    password: Zeroizing<String>,
) -> Result<CreatedMember, Box<Response>> {
    let email = raw_email.trim().to_lowercase();
    let ip = peer_ip(peer.as_ref());
    let keys = [auth::rate_key("ip", &ip), auth::rate_key("email", &email)];
    if state.app.auth_limiter.reserve(&keys).is_none() {
        return Err(Box::new(super::with_retry_after(api_error(
            StatusCode::TOO_MANY_REQUESTS,
            "rate_limited",
            "Too many registration attempts",
        ))));
    }

    if email.is_empty()
        || email.len() > 320
        || !email.contains('@')
        || email.contains(char::is_whitespace)
    {
        return Err(Box::new(validation_error(
            "email",
            "Enter a valid email address.",
        )));
    }
    if password.len() > 1024 {
        return Err(Box::new(validation_error(
            "password",
            "Password is too long.",
        )));
    }
    if let Err(error) = auth::validate_new_password(&password) {
        return Err(Box::new(validation_error(
            "password",
            match error {
                auth::NewPasswordError::TooShort => "Use at least 12 characters.",
                auth::NewPasswordError::Common => "Choose a less common password.",
            },
        )));
    }

    let mut password = password;
    let password = std::mem::take(&mut *password);
    match db::create_account(&state.app.pool, &email, password, db::AccountRole::Member).await {
        Ok(id) => Ok(CreatedMember { id, email }),
        Err(db::DatabaseError::InvalidAccount) => Err(Box::new(validation_error(
            "password",
            "Password does not meet the account policy.",
        ))),
        Err(db::DatabaseError::Account(error))
            if error
                .as_database_error()
                .is_some_and(sqlx::error::DatabaseError::is_unique_violation) =>
        {
            Err(Box::new(api_error(
                StatusCode::CONFLICT,
                REGISTRATION_FAILED,
                "The account could not be created",
            )))
        }
        Err(_) => Err(Box::new(api_error(
            StatusCode::INTERNAL_SERVER_ERROR,
            "internal_error",
            "Request failed",
        ))),
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
