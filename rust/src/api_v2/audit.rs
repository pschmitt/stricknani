//! Safe, same-origin audit history for active administrators.

use axum::{
    Json, Router,
    extract::{Path, RawQuery, State},
    http::StatusCode,
    response::{IntoResponse, Response},
    routing::get,
};
use serde::Serialize;

use super::{ApiV2State, access, api_error};

#[derive(Serialize)]
struct AuditMeta {
    page: u32,
    per_page: u32,
    has_more: bool,
}

#[derive(Serialize)]
struct AuditResponse {
    data: Vec<crate::audit_service::AuditEvent>,
    meta: AuditMeta,
}

pub(super) fn router() -> Router<ApiV2State> {
    Router::new()
        .route("/admin/audit", get(list_events))
        .route("/admin/audit/{event_id}", get(event_detail))
}

#[derive(Serialize)]
struct AuditDetailResponse {
    data: crate::audit_service::AuditDetail,
}

async fn event_detail(
    State(state): State<ApiV2State>,
    headers: axum::http::HeaderMap,
    Path(event_id): Path<i64>,
) -> Response {
    let actor_id = match access::admin_user(&state.app, &headers).await {
        Ok(actor_id) => actor_id,
        Err(response) => return *response,
    };
    match crate::audit_service::get_event_detail(&state.app.pool, actor_id, event_id).await {
        Ok(Some(data)) => Json(AuditDetailResponse { data }).into_response(),
        Ok(None) => api_error(
            StatusCode::NOT_FOUND,
            "not_found",
            "Audit event was not found",
        ),
        Err(crate::audit_service::AuditError::Forbidden) => api_error(
            StatusCode::FORBIDDEN,
            "admin_required",
            "Administrator access is required",
        ),
        Err(_) => internal_error(),
    }
}

async fn list_events(
    State(state): State<ApiV2State>,
    headers: axum::http::HeaderMap,
    RawQuery(raw_query): RawQuery,
) -> Response {
    let actor_id = match access::admin_user(&state.app, &headers).await {
        Ok(actor_id) => actor_id,
        Err(response) => return *response,
    };
    let filter = match crate::audit_service::AuditFilter::parse(raw_query.as_deref()) {
        Ok(filter) => filter,
        Err(crate::audit_service::AuditError::InvalidQuery) => {
            return api_error(
                StatusCode::BAD_REQUEST,
                "invalid_query",
                "Audit filters are invalid",
            );
        }
        Err(_) => return internal_error(),
    };
    match crate::audit_service::list_events(&state.app.pool, actor_id, &filter).await {
        Ok(page) => Json(AuditResponse {
            data: page.events,
            meta: AuditMeta {
                page: page.page,
                per_page: page.per_page,
                has_more: page.has_more,
            },
        })
        .into_response(),
        Err(crate::audit_service::AuditError::Forbidden) => api_error(
            StatusCode::FORBIDDEN,
            "admin_required",
            "Administrator access is required",
        ),
        Err(crate::audit_service::AuditError::InvalidQuery) => api_error(
            StatusCode::BAD_REQUEST,
            "invalid_query",
            "Audit filters are invalid",
        ),
        Err(crate::audit_service::AuditError::Database) => internal_error(),
    }
}

fn internal_error() -> Response {
    api_error(
        StatusCode::INTERNAL_SERVER_ERROR,
        "internal_error",
        "Request failed",
    )
}
