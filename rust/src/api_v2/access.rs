//! Shared browser-session and CSRF checks for same-origin API v2 resources.

use std::{
    collections::{HashMap, VecDeque},
    sync::{Arc, Mutex, PoisonError},
    time::{Duration, Instant},
};

use axum::{
    http::{HeaderMap, StatusCode},
    response::Response,
};
use subtle::ConstantTimeEq;

use crate::{auth, db::AppState};

use super::api_error;

/// A bounded in-memory request quota keyed by authenticated owner.
#[derive(Clone)]
pub(super) struct OwnerRequestLimiter {
    attempts: Arc<Mutex<HashMap<i64, VecDeque<Instant>>>>,
    limit: usize,
    window: Duration,
    max_keys: usize,
}

impl OwnerRequestLimiter {
    pub(super) fn new(limit: usize, window: Duration, max_keys: usize) -> Self {
        Self {
            attempts: Arc::default(),
            limit,
            window,
            max_keys,
        }
    }

    pub(super) fn reserve(&self, owner_id: i64) -> Result<(), u64> {
        let now = Instant::now();
        let mut entries = self.attempts.lock().unwrap_or_else(PoisonError::into_inner);
        entries.retain(|_, attempts| {
            attempts.retain(|attempt| now.duration_since(*attempt) < self.window);
            !attempts.is_empty()
        });
        if let Some(attempts) = entries.get(&owner_id)
            && attempts.len() >= self.limit
        {
            let retry_after = self
                .window
                .saturating_sub(now.duration_since(attempts[0]))
                .as_secs()
                .max(1);
            return Err(retry_after);
        }
        if !entries.contains_key(&owner_id) && entries.len() >= self.max_keys {
            return Err(self.window.as_secs().max(1));
        }
        entries.entry(owner_id).or_default().push_back(now);
        Ok(())
    }
}

pub(super) fn rate_limited(retry_after: u64, message: &'static str) -> Response {
    let mut response = api_error(StatusCode::TOO_MANY_REQUESTS, "rate_limited", message);
    if let Ok(value) = retry_after.to_string().parse() {
        response.headers_mut().insert("retry-after", value);
    }
    response
}

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

pub(super) async fn resource_user(
    state: &AppState,
    headers: &HeaderMap,
) -> Result<i64, Box<Response>> {
    match pat_user_if_authorization(state, headers).await? {
        Some(user_id) => Ok(user_id),
        None => browser_user(state, headers).await,
    }
}

pub(super) async fn resource_mutation_user(
    state: &AppState,
    headers: &HeaderMap,
) -> Result<i64, Box<Response>> {
    match pat_user_if_authorization(state, headers).await? {
        Some(user_id) => Ok(user_id),
        None => mutation_user(state, headers).await,
    }
}

pub(super) async fn optional_resource_user(
    state: &AppState,
    headers: &HeaderMap,
) -> Result<Option<i64>, Box<Response>> {
    if let Some(user_id) = pat_user_if_authorization(state, headers).await? {
        return Ok(Some(user_id));
    }

    match auth::browser_user(&state.pool, headers, state.secure_cookies).await {
        Ok(user_id) => Ok(Some(user_id)),
        Err(auth::AuthError::Unauthorized) => Ok(None),
        Err(auth::AuthError::Database(_)) => Err(Box::new(auth_error(AuthFailure::Internal))),
    }
}

async fn pat_user_if_authorization(
    state: &AppState,
    headers: &HeaderMap,
) -> Result<Option<i64>, Box<Response>> {
    if !headers.contains_key(axum::http::header::AUTHORIZATION) {
        return Ok(None);
    }

    match auth::bearer_pat_user(&state.pool, headers).await {
        Ok(user_id) => Ok(Some(user_id)),
        Err(auth::AuthError::Unauthorized) => Err(Box::new(auth_error(AuthFailure::Unauthorized))),
        Err(auth::AuthError::Database(_)) => Err(Box::new(auth_error(AuthFailure::Internal))),
    }
}

pub(super) async fn admin_user(
    state: &AppState,
    headers: &HeaderMap,
) -> Result<i64, Box<Response>> {
    authenticated_admin_user(state, headers, AdminAuthMode::Read).await
}

pub(super) async fn admin_mutation_user(
    state: &AppState,
    headers: &HeaderMap,
) -> Result<i64, Box<Response>> {
    authenticated_admin_user(state, headers, AdminAuthMode::Mutation).await
}

#[derive(Clone, Copy)]
enum AdminAuthMode {
    Read,
    Mutation,
}

async fn authenticated_admin_user(
    state: &AppState,
    headers: &HeaderMap,
    mode: AdminAuthMode,
) -> Result<i64, Box<Response>> {
    let user_id = match mode {
        AdminAuthMode::Read => resource_user(state, headers).await?,
        AdminAuthMode::Mutation => resource_mutation_user(state, headers).await?,
    };

    match crate::admin_service::require_active_admin(&state.pool, user_id).await {
        Ok(()) => Ok(user_id),
        Err(crate::admin_service::AdminError::Forbidden) => Err(Box::new(api_error(
            StatusCode::FORBIDDEN,
            "admin_required",
            "Administrator access is required",
        ))),
        Err(_) => Err(Box::new(api_error(
            StatusCode::INTERNAL_SERVER_ERROR,
            "internal_error",
            "Request failed",
        ))),
    }
}
