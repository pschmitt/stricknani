//! Owner-scoped recent activity feed for the Home page.

use axum::{
    Json, Router,
    extract::{RawQuery, State},
    http::{HeaderMap, StatusCode},
    response::{IntoResponse, Response},
    routing::get,
};
use serde::Serialize;
use url::form_urlencoded;

use crate::audit_service::{self, ACTIVITY_DEFAULT_LIMIT, ACTIVITY_MAX_LIMIT, ActivityItem};

use super::{ApiV2State, access, api_error};

pub(super) fn router() -> Router<ApiV2State> {
    Router::new().route("/activity", get(list_activity))
}

#[derive(Serialize)]
struct ActivityMeta {
    limit: u32,
    has_more: bool,
    next_before: Option<i64>,
}

#[derive(Serialize)]
struct ActivityEnvelope {
    data: Vec<ActivityItem>,
    meta: ActivityMeta,
}

async fn list_activity(
    State(state): State<ApiV2State>,
    headers: HeaderMap,
    RawQuery(raw_query): RawQuery,
) -> Response {
    let user_id = match access::resource_user(&state.app, &headers).await {
        Ok(user_id) => user_id,
        Err(response) => return *response,
    };
    let Ok((limit, before)) = parse_query(raw_query.as_deref()) else {
        return api_error(
            StatusCode::BAD_REQUEST,
            "invalid_query",
            "Activity limit or cursor is invalid",
        );
    };
    match audit_service::list_activity(
        &state.app.pool,
        user_id,
        limit,
        before,
        state.app.gravatar.enabled,
    )
    .await
    {
        Ok(page) => Json(ActivityEnvelope {
            data: page.items,
            meta: ActivityMeta {
                limit,
                has_more: page.has_more,
                next_before: page.next_before,
            },
        })
        .into_response(),
        Err(_) => api_error(
            StatusCode::INTERNAL_SERVER_ERROR,
            "internal_error",
            "Request failed",
        ),
    }
}

fn parse_query(raw_query: Option<&str>) -> Result<(u32, Option<i64>), ()> {
    let mut limit = None;
    let mut before = None;
    if let Some(raw_query) = raw_query {
        if raw_query.len() > 512 {
            return Err(());
        }
        for (key, value) in form_urlencoded::parse(raw_query.as_bytes()) {
            match key.as_ref() {
                "limit" if limit.is_none() => {
                    let parsed = value.parse::<u32>().map_err(|_| ())?;
                    if parsed == 0 || parsed > ACTIVITY_MAX_LIMIT {
                        return Err(());
                    }
                    limit = Some(parsed);
                }
                "before" if before.is_none() => {
                    let parsed = value.parse::<i64>().map_err(|_| ())?;
                    if parsed <= 0 {
                        return Err(());
                    }
                    before = Some(parsed);
                }
                _ => return Err(()),
            }
        }
    }
    Ok((limit.unwrap_or(ACTIVITY_DEFAULT_LIMIT), before))
}
