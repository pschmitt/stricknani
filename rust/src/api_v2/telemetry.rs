//! Optional error reporting: the client-facing description and the
//! same-origin tunnel that forwards Sentry envelopes to the operator's DSN.
//!
//! Clients (web, Android) never talk to the Sentry host. They send envelopes
//! here, so the server can check the DSN, cap the size, scrub the event again
//! and honour the signed-in user's opt-out.

use axum::{
    Router,
    body::Bytes,
    extract::{DefaultBodyLimit, State},
    http::{HeaderMap, StatusCode, header},
    response::{IntoResponse, Response},
    routing::post,
};
use serde::Serialize;

use super::{ApiV2State, access::optional_resource_user, api_error};
use crate::{
    account_service,
    error_reporting::{
        DEFAULT_USER_ENABLED, EnvelopeError, ForwardError, MAX_ENVELOPE_BYTES, TUNNEL_PATH,
    },
};

/// What clients need to decide whether and how to report errors.
#[derive(Serialize)]
pub(super) struct ErrorReportingInfo {
    /// The operator configured a DSN. When false, clients must not report.
    available: bool,
    /// Public DSN (no secret key); `null` when unavailable.
    dsn: Option<String>,
    /// Same-origin tunnel path; `null` when unavailable.
    tunnel_path: Option<&'static str>,
    environment: Option<String>,
    /// The signed-in user's choice, or the instance default when signed out.
    enabled: bool,
}

impl ErrorReportingInfo {
    pub(super) async fn load(state: &ApiV2State, user_id: Option<i64>) -> Self {
        let settings = &state.app.error_reporting;
        let enabled = match user_id {
            Some(user_id) => {
                account_service::error_reporting_enabled(&state.app.pool, user_id).await
            }
            None => DEFAULT_USER_ENABLED,
        };
        let available = settings.is_available();
        Self {
            available,
            dsn: settings.public_dsn(),
            tunnel_path: available.then_some(TUNNEL_PATH),
            environment: settings.environment().map(str::to_owned),
            enabled,
        }
    }
}

pub(super) fn router() -> Router<ApiV2State> {
    Router::new()
        .route("/telemetry/sentry", post(tunnel))
        // Sentry SDKs without a tunnel option (Android) post to
        // `<dsn path>/api/<project>/envelope/` of a DSN that points here.
        .route(
            "/telemetry/sentry/api/{project}/envelope/",
            post(tunnel_for_dsn),
        )
        .layer(DefaultBodyLimit::max(MAX_ENVELOPE_BYTES))
}

async fn tunnel_for_dsn(
    state: State<ApiV2State>,
    _project: axum::extract::Path<String>,
    headers: HeaderMap,
    body: Bytes,
) -> Response {
    tunnel(state, headers, body).await
}

async fn tunnel(State(state): State<ApiV2State>, headers: HeaderMap, body: Bytes) -> Response {
    let settings = &state.app.error_reporting;
    if !settings.is_available() {
        return api_error(StatusCode::NOT_FOUND, "not_found", "Not found");
    }
    // Signed-in users who opted out are never forwarded, whichever client sent
    // the envelope. Signed-out callers follow the instance default.
    let user_id = match optional_resource_user(&state.app, &headers).await {
        Ok(user_id) => user_id,
        Err(response) => return *response,
    };
    if let Some(user_id) = user_id
        && !account_service::error_reporting_enabled(&state.app.pool, user_id).await
    {
        return StatusCode::NO_CONTENT.into_response();
    }
    let gzip = headers
        .get(header::CONTENT_ENCODING)
        .and_then(|value| value.to_str().ok())
        .is_some_and(|value| value.eq_ignore_ascii_case("gzip"));
    let envelope = match settings.prepare_envelope(&body, gzip) {
        Ok(envelope) => envelope,
        Err(EnvelopeError::TooLarge) => {
            return api_error(
                StatusCode::PAYLOAD_TOO_LARGE,
                "payload_too_large",
                "Report is too large",
            );
        }
        Err(EnvelopeError::WrongDsn) => {
            return api_error(
                StatusCode::FORBIDDEN,
                "wrong_dsn",
                "Unknown reporting target",
            );
        }
        Err(EnvelopeError::Unavailable) => {
            return api_error(StatusCode::NOT_FOUND, "not_found", "Not found");
        }
        Err(EnvelopeError::Malformed | EnvelopeError::ForbiddenItem) => {
            return api_error(StatusCode::BAD_REQUEST, "invalid_request", "Invalid report");
        }
    };
    match settings.forward(envelope).await {
        Ok(()) => StatusCode::NO_CONTENT.into_response(),
        Err(ForwardError::Busy) => api_error(
            StatusCode::TOO_MANY_REQUESTS,
            "rate_limited",
            "Too many reports, try again later",
        ),
        Err(ForwardError::Failed) => api_error(
            StatusCode::BAD_GATEWAY,
            "upstream_unavailable",
            "Reporting service unavailable",
        ),
    }
}
