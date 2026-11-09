//! Web Push registration and timer reminder schedules (see `crate::web_push`).

use axum::{
    Json, Router,
    extract::{Path, State, rejection::JsonRejection},
    http::{HeaderMap, StatusCode},
    response::{IntoResponse, Response},
    routing::{get, put},
};
use base64::{Engine, engine::general_purpose::URL_SAFE_NO_PAD};
use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};

use crate::resource_access::{self, Need, ResourceKind, Surface};
use crate::web_push;

use super::{ApiV2State, Envelope, access};

/// How far ahead a client may schedule the next reminder.
const MAX_LEAD_SECONDS: i64 = 24 * 60 * 60;

pub(super) fn router() -> Router<ApiV2State> {
    Router::new()
        .route("/push/key", get(get_key))
        .route(
            "/push/subscription",
            put(put_subscription).delete(delete_subscription),
        )
        .route(
            "/projects/{project_id}/timer/reminder",
            put(put_reminder).delete(delete_reminder),
        )
}

#[derive(Serialize)]
struct KeyDto {
    public_key: String,
}

async fn get_key(State(state): State<ApiV2State>, headers: HeaderMap) -> Response {
    if let Err(response) = access::resource_user(&state.app, &headers).await {
        return *response;
    }
    match web_push::vapid_key(&state.app.pool).await {
        Ok(key) => Json(Envelope {
            data: KeyDto {
                public_key: web_push::public_key(&key),
            },
        })
        .into_response(),
        Err(_) => internal_error(),
    }
}

#[derive(Deserialize)]
struct SubscriptionKeys {
    p256dh: String,
    auth: String,
}

/// The browser's `PushSubscription.toJSON()`; other fields (expirationTime)
/// are accepted and ignored.
#[derive(Deserialize)]
struct SubscriptionRequest {
    endpoint: String,
    keys: SubscriptionKeys,
}

async fn put_subscription(
    State(state): State<ApiV2State>,
    headers: HeaderMap,
    payload: Result<Json<SubscriptionRequest>, JsonRejection>,
) -> Response {
    let user_id = match access::resource_mutation_user(&state.app, &headers).await {
        Ok(user_id) => user_id,
        Err(response) => return *response,
    };
    let Ok(Json(payload)) = payload else {
        return validation_error("Invalid push subscription");
    };
    if !web_push::endpoint_allowed(&payload.endpoint) {
        return validation_error("Push service not supported");
    }
    let (Ok(p256dh), Ok(auth)) = (
        URL_SAFE_NO_PAD.decode(payload.keys.p256dh.trim_end_matches('=')),
        URL_SAFE_NO_PAD.decode(payload.keys.auth.trim_end_matches('=')),
    ) else {
        return validation_error("Invalid push subscription");
    };
    if auth.len() != 16
        || p256dh.len() != 65
        || web_push_native::p256::PublicKey::from_sec1_bytes(&p256dh).is_err()
    {
        return validation_error("Invalid push subscription");
    }
    // The endpoint identifies the browser: re-subscribing (or another account
    // signing in there) replaces the earlier row.
    let result = sqlx::query(
        "INSERT INTO push_subscriptions(user_id, endpoint, p256dh, auth, created_at) \
         VALUES (?, ?, ?, ?, unixepoch()) \
         ON CONFLICT(endpoint) DO UPDATE SET user_id = excluded.user_id, \
           p256dh = excluded.p256dh, auth = excluded.auth, created_at = excluded.created_at",
    )
    .bind(user_id)
    .bind(&payload.endpoint)
    .bind(p256dh)
    .bind(auth)
    .execute(&state.app.pool)
    .await;
    match result {
        Ok(_) => StatusCode::NO_CONTENT.into_response(),
        Err(_) => internal_error(),
    }
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct UnsubscribeRequest {
    endpoint: String,
}

async fn delete_subscription(
    State(state): State<ApiV2State>,
    headers: HeaderMap,
    payload: Result<Json<UnsubscribeRequest>, JsonRejection>,
) -> Response {
    let user_id = match access::resource_mutation_user(&state.app, &headers).await {
        Ok(user_id) => user_id,
        Err(response) => return *response,
    };
    let Ok(Json(payload)) = payload else {
        return validation_error("Invalid push subscription");
    };
    match sqlx::query("DELETE FROM push_subscriptions WHERE user_id = ? AND endpoint = ?")
        .bind(user_id)
        .bind(&payload.endpoint)
        .execute(&state.app.pool)
        .await
    {
        Ok(_) => StatusCode::NO_CONTENT.into_response(),
        Err(_) => internal_error(),
    }
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct ReminderRequest {
    /// The run this schedule belongs to (the timer's `started_at`).
    started_at: String,
    /// 30, 60 or 120; 0 turns reminders off for this project.
    interval_minutes: i64,
    /// When the next reminder is due.
    next_at: Option<String>,
    locale: Option<String>,
}

async fn put_reminder(
    State(state): State<ApiV2State>,
    headers: HeaderMap,
    Path(project_id): Path<i64>,
    payload: Result<Json<ReminderRequest>, JsonRejection>,
) -> Response {
    let user_id = match access::resource_mutation_user(&state.app, &headers).await {
        Ok(user_id) => user_id,
        Err(response) => return *response,
    };
    let Ok(Json(payload)) = payload else {
        return validation_error("Invalid reminder");
    };
    if let Some(denied) = edit_denied(&state, user_id, project_id).await {
        return denied;
    }
    if payload.interval_minutes == 0 {
        return remove_reminder(&state, user_id, project_id).await;
    }
    let now = Utc::now().timestamp();
    let locale = payload.locale.as_deref().unwrap_or("en");
    let (Some(started_at), Some(next_at)) = (
        epoch(&payload.started_at),
        payload.next_at.as_deref().and_then(epoch),
    ) else {
        return validation_error("Invalid reminder");
    };
    if ![30, 60, 120].contains(&payload.interval_minutes)
        || !matches!(locale, "en" | "de")
        || next_at > now + MAX_LEAD_SECONDS
    {
        return validation_error("Invalid reminder");
    }
    let running: Option<(Option<i64>, Option<i64>)> = match sqlx::query_as(
        "SELECT started_at, started_by FROM project_timers WHERE project_id = ?",
    )
    .bind(project_id)
    .fetch_optional(&state.app.pool)
    .await
    {
        Ok(value) => value,
        Err(_) => return internal_error(),
    };
    let (run, starter) = running.unwrap_or((None, None));
    if run != Some(started_at) {
        return super::api_error(
            StatusCode::CONFLICT,
            "timer_not_running",
            "This timer run is not running any more",
        );
    }
    // Only the user who started the timer is reminded; for anyone else the
    // request is accepted but schedules nothing.
    if starter != Some(user_id) {
        return remove_reminder(&state, user_id, project_id).await;
    }
    let result = sqlx::query(
        "INSERT INTO timer_reminders(user_id, project_id, started_at, interval_minutes, next_at, locale) \
         VALUES (?, ?, ?, ?, ?, ?) \
         ON CONFLICT(user_id, project_id) DO UPDATE SET started_at = excluded.started_at, \
           interval_minutes = excluded.interval_minutes, next_at = excluded.next_at, \
           locale = excluded.locale",
    )
    .bind(user_id)
    .bind(project_id)
    .bind(started_at)
    .bind(payload.interval_minutes)
    .bind(next_at.max(now))
    .bind(locale)
    .execute(&state.app.pool)
    .await;
    match result {
        Ok(_) => StatusCode::NO_CONTENT.into_response(),
        Err(_) => internal_error(),
    }
}

async fn delete_reminder(
    State(state): State<ApiV2State>,
    headers: HeaderMap,
    Path(project_id): Path<i64>,
) -> Response {
    let user_id = match access::resource_mutation_user(&state.app, &headers).await {
        Ok(user_id) => user_id,
        Err(response) => return *response,
    };
    remove_reminder(&state, user_id, project_id).await
}

async fn remove_reminder(state: &ApiV2State, user_id: i64, project_id: i64) -> Response {
    match sqlx::query("DELETE FROM timer_reminders WHERE user_id = ? AND project_id = ?")
        .bind(user_id)
        .bind(project_id)
        .execute(&state.app.pool)
        .await
    {
        Ok(_) => StatusCode::NO_CONTENT.into_response(),
        Err(_) => internal_error(),
    }
}

/// The error response when the user may not edit the project, if any.
async fn edit_denied(state: &ApiV2State, user_id: i64, project_id: i64) -> Option<Response> {
    let denied = resource_access::require(
        &state.app.pool,
        user_id,
        ResourceKind::Project,
        project_id,
        Surface::V2,
        Need::Edit,
    )
    .await
    .err()?;
    Some(match denied {
        resource_access::Denied::NotFound => {
            super::api_error(StatusCode::NOT_FOUND, "not_found", "Project not found")
        }
        resource_access::Denied::Forbidden => super::api_error(
            StatusCode::FORBIDDEN,
            "forbidden",
            "You may view this project but not change its timer",
        ),
        resource_access::Denied::Database => internal_error(),
    })
}

fn epoch(value: &str) -> Option<i64> {
    DateTime::parse_from_rfc3339(value)
        .ok()
        .map(|value| value.timestamp())
}

fn validation_error(message: &'static str) -> Response {
    super::api_error(
        StatusCode::UNPROCESSABLE_ENTITY,
        "validation_error",
        message,
    )
}

fn internal_error() -> Response {
    super::api_error(
        StatusCode::INTERNAL_SERVER_ERROR,
        "internal_error",
        "Request failed",
    )
}
