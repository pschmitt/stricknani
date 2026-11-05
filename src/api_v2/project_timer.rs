//! Shared stopwatch state for each project.

use axum::{
    Json, Router,
    extract::{Path, State, rejection::JsonRejection},
    http::{HeaderMap, StatusCode},
    response::{IntoResponse, Response},
    routing::get,
};
use chrono::{DateTime, SecondsFormat, TimeZone, Utc};
use serde::{Deserialize, Serialize};

use crate::resource_access::{self, Need, ResourceKind, Surface};

use super::{ApiV2State, Envelope, access};

const MAX_SECONDS: i64 = 315_576_000;

pub(super) fn router() -> Router<ApiV2State> {
    Router::new().route(
        "/projects/{project_id}/timer",
        get(get_timer).put(put_timer),
    )
}

#[derive(Serialize)]
struct TimerDto {
    total_seconds: i64,
    started_at: Option<String>,
    server_time: String,
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct TimerRequest {
    action: TimerAction,
    total_seconds: Option<i64>,
}

#[derive(Clone, Copy, Deserialize)]
#[serde(rename_all = "snake_case")]
enum TimerAction {
    Start,
    Stop,
    SetTotal,
}

async fn get_timer(
    State(state): State<ApiV2State>,
    headers: HeaderMap,
    Path(project_id): Path<i64>,
) -> Response {
    let user_id = match access::resource_user(&state.app, &headers).await {
        Ok(user_id) => user_id,
        Err(response) => return *response,
    };
    match resource_access::require(
        &state.app.pool,
        user_id,
        ResourceKind::Project,
        project_id,
        Surface::V2,
        Need::Read,
    )
    .await
    {
        Ok(_) => (),
        Err(error) => return access_error(error),
    }
    match read_timer(&state, project_id, Utc::now()).await {
        Ok(data) => Json(Envelope { data }).into_response(),
        Err(_) => internal_error(),
    }
}

async fn put_timer(
    State(state): State<ApiV2State>,
    headers: HeaderMap,
    Path(project_id): Path<i64>,
    payload: Result<Json<TimerRequest>, JsonRejection>,
) -> Response {
    let user_id = match access::resource_mutation_user(&state.app, &headers).await {
        Ok(user_id) => user_id,
        Err(response) => return *response,
    };
    let Json(payload) = match payload {
        Ok(payload) => payload,
        Err(_) => return validation_error(),
    };
    let set_total = match (payload.action, payload.total_seconds) {
        (TimerAction::SetTotal, Some(value)) if (0..=MAX_SECONDS).contains(&value) => Some(value),
        (TimerAction::Start | TimerAction::Stop, None) => None,
        _ => return validation_error(),
    };
    match resource_access::require(
        &state.app.pool,
        user_id,
        ResourceKind::Project,
        project_id,
        Surface::V2,
        Need::Edit,
    )
    .await
    {
        Ok(_) => (),
        Err(error) => return access_error(error),
    }

    let now = Utc::now();
    let now_epoch = now.timestamp();
    let result = match payload.action {
        TimerAction::Start => {
            sqlx::query(
                "INSERT INTO project_timers(project_id, total_seconds, started_at) \
                 SELECT ?, 0, ? WHERE EXISTS (SELECT 1 FROM projects p WHERE p.id = ? AND \
                 (p.owner_id = ? OR EXISTS (SELECT 1 FROM users u WHERE u.id = ? AND u.is_admin = 1 AND u.active = 1) \
                 OR EXISTS (SELECT 1 FROM resource_shares s WHERE s.kind = 'project' AND s.resource_id = p.id AND s.user_id = ? AND s.role = 'editor'))) \
                 ON CONFLICT(project_id) DO UPDATE SET started_at = coalesce(project_timers.started_at, excluded.started_at)",
            )
            .bind(project_id)
            .bind(now_epoch)
            .bind(project_id)
            .bind(user_id)
            .bind(user_id)
            .bind(user_id)
            .execute(&state.app.pool)
            .await
        }
        TimerAction::Stop => {
            sqlx::query(
                "INSERT INTO project_timers(project_id, total_seconds, started_at) \
                 SELECT ?, 0, NULL WHERE EXISTS (SELECT 1 FROM projects p WHERE p.id = ? AND \
                 (p.owner_id = ? OR EXISTS (SELECT 1 FROM users u WHERE u.id = ? AND u.is_admin = 1 AND u.active = 1) \
                 OR EXISTS (SELECT 1 FROM resource_shares s WHERE s.kind = 'project' AND s.resource_id = p.id AND s.user_id = ? AND s.role = 'editor'))) \
                 ON CONFLICT(project_id) DO UPDATE SET \
                 total_seconds = min(315576000, project_timers.total_seconds + max(0, ? - coalesce(project_timers.started_at, ?))), \
                 started_at = NULL",
            )
            .bind(project_id)
            .bind(project_id)
            .bind(user_id)
            .bind(user_id)
            .bind(user_id)
            .bind(now_epoch)
            .bind(now_epoch)
            .execute(&state.app.pool)
            .await
        }
        TimerAction::SetTotal => {
            let Some(total_seconds) = set_total else {
                return validation_error();
            };
            sqlx::query(
                "INSERT INTO project_timers(project_id, total_seconds, started_at) \
                 SELECT ?, ?, NULL WHERE EXISTS (SELECT 1 FROM projects p WHERE p.id = ? AND \
                 (p.owner_id = ? OR EXISTS (SELECT 1 FROM users u WHERE u.id = ? AND u.is_admin = 1 AND u.active = 1) \
                 OR EXISTS (SELECT 1 FROM resource_shares s WHERE s.kind = 'project' AND s.resource_id = p.id AND s.user_id = ? AND s.role = 'editor'))) \
                 ON CONFLICT(project_id) DO UPDATE SET total_seconds = excluded.total_seconds, \
                 started_at = CASE WHEN project_timers.started_at IS NULL THEN NULL ELSE ? END",
            )
            .bind(project_id)
            .bind(total_seconds)
            .bind(project_id)
            .bind(user_id)
            .bind(user_id)
            .bind(user_id)
            .bind(now_epoch)
            .execute(&state.app.pool)
            .await
        }
    };
    let result = match result {
        Ok(result) => result,
        Err(_) => return internal_error(),
    };
    if result.rows_affected() == 0 {
        return match resource_access::require(
            &state.app.pool,
            user_id,
            ResourceKind::Project,
            project_id,
            Surface::V2,
            Need::Edit,
        )
        .await
        {
            Ok(_) => internal_error(),
            Err(error) => access_error(error),
        };
    }
    if result.rows_affected() > 1 {
        return internal_error();
    }
    match read_timer(&state, project_id, now).await {
        Ok(data) => Json(Envelope { data }).into_response(),
        Err(_) => internal_error(),
    }
}

async fn read_timer(
    state: &ApiV2State,
    project_id: i64,
    now: DateTime<Utc>,
) -> Result<TimerDto, sqlx::Error> {
    let row = sqlx::query_as::<_, (i64, Option<i64>)>(
        "SELECT total_seconds, started_at FROM project_timers WHERE project_id = ?",
    )
    .bind(project_id)
    .fetch_optional(&state.app.pool)
    .await?;
    let (total_seconds, started_at) = row.unwrap_or((0, None));
    let now_epoch = now.timestamp();
    let total_seconds = started_at.map_or(total_seconds, |started| {
        total_seconds.saturating_add(now_epoch.saturating_sub(started).max(0))
    });
    Ok(TimerDto {
        total_seconds: total_seconds.min(MAX_SECONDS),
        started_at: started_at
            .and_then(|value| Utc.timestamp_opt(value, 0).single())
            .map(|value| value.to_rfc3339_opts(SecondsFormat::Secs, true)),
        server_time: now.to_rfc3339_opts(SecondsFormat::Micros, true),
    })
}

fn access_error(error: resource_access::Denied) -> Response {
    match error {
        resource_access::Denied::NotFound => {
            super::api_error(StatusCode::NOT_FOUND, "not_found", "Project not found")
        }
        resource_access::Denied::Forbidden => super::api_error(
            StatusCode::FORBIDDEN,
            "forbidden",
            "You may view this project but not change its timer",
        ),
        resource_access::Denied::Database => internal_error(),
    }
}

fn validation_error() -> Response {
    super::api_error(
        StatusCode::UNPROCESSABLE_ENTITY,
        "validation_error",
        "Invalid timer action or total_seconds",
    )
}

fn internal_error() -> Response {
    super::api_error(
        StatusCode::INTERNAL_SERVER_ERROR,
        "internal_error",
        "Request failed",
    )
}
