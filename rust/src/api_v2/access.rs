//! Shared browser-session and CSRF checks for same-origin API v2 resources.

use axum::{
    http::{HeaderMap, StatusCode},
    response::Response,
};
use subtle::ConstantTimeEq;

use crate::{auth, db::AppState};

use super::api_error;

#[derive(Clone, Copy)]
enum AuthFailure {
    Unauthorized,
    Csrf,
    Internal,
}

fn auth_error(error: AuthFailure) -> Response {
    match error {
        AuthFailure::Unauthorized => api_error(
            StatusCode::UNAUTHORIZED,
            "session_expired",
            "Sign in to continue",
        ),
        AuthFailure::Csrf => api_error(
            StatusCode::FORBIDDEN,
            "csrf_failed",
            "CSRF token is missing or invalid",
        ),
        AuthFailure::Internal => api_error(
            StatusCode::INTERNAL_SERVER_ERROR,
            "internal_error",
            "Request failed",
        ),
    }
}

pub(super) async fn browser_user(
    state: &AppState,
    headers: &HeaderMap,
) -> Result<i64, Box<Response>> {
    match auth::browser_user(&state.pool, headers, state.secure_cookies).await {
        Ok(user_id) => Ok(user_id),
        Err(auth::AuthError::Unauthorized) => Err(Box::new(auth_error(AuthFailure::Unauthorized))),
        Err(auth::AuthError::Database(_)) => Err(Box::new(auth_error(AuthFailure::Internal))),
    }
}

pub(super) async fn mutation_user(
    state: &AppState,
    headers: &HeaderMap,
) -> Result<i64, Box<Response>> {
    if !super::same_origin_request(headers, state.secure_cookies) {
        return Err(Box::new(auth_error(AuthFailure::Csrf)));
    }
    let user_id = browser_user(state, headers).await?;
    let Some(token) = auth::cookie(headers, auth::session_cookie_name(state.secure_cookies)) else {
        return Err(Box::new(auth_error(AuthFailure::Unauthorized)));
    };
    let expected = crate::web::csrf(token);
    let supplied = headers
        .get("x-csrf-token")
        .and_then(|value| value.to_str().ok())
        .unwrap_or_default();
    if !bool::from(expected.as_bytes().ct_eq(supplied.as_bytes())) {
        return Err(Box::new(auth_error(AuthFailure::Csrf)));
    }
    Ok(user_id)
}
