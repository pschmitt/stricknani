//! Stateless browser tools backed by shared domain calculations.

use std::{collections::BTreeMap, num::NonZeroU32};

use axum::{
    Json, Router,
    extract::{DefaultBodyLimit, rejection::JsonRejection},
    http::StatusCode,
    response::{IntoResponse, Response},
    routing::post,
};
use serde::{Deserialize, Serialize};

use crate::domain::gauge::GaugeInput;

use super::Envelope;

pub(super) fn router() -> Router<super::ApiV2State> {
    Router::new()
        .route("/tools/gauge", post(calculate_gauge))
        .layer(DefaultBodyLimit::max(8 * 1024))
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct GaugeRequest {
    pattern_gauge_stitches: Option<serde_json::Value>,
    pattern_gauge_rows: Option<serde_json::Value>,
    user_gauge_stitches: Option<serde_json::Value>,
    user_gauge_rows: Option<serde_json::Value>,
    pattern_cast_on_stitches: Option<serde_json::Value>,
    pattern_row_count: Option<serde_json::Value>,
}

#[derive(Serialize)]
struct GaugeResponse {
    adjusted_stitches: String,
    adjusted_rows: Option<String>,
}

#[derive(Serialize)]
struct ErrorEnvelope {
    error: GaugeError,
}

#[derive(Serialize)]
struct GaugeError {
    code: &'static str,
    message: &'static str,
    #[serde(skip_serializing_if = "Option::is_none")]
    fields: Option<BTreeMap<&'static str, &'static str>>,
}

async fn calculate_gauge(request: Result<Json<GaugeRequest>, JsonRejection>) -> Response {
    let Json(request) = match request {
        Ok(request) => request,
        Err(rejection) => {
            let status = rejection.status();
            if status == StatusCode::PAYLOAD_TOO_LARGE {
                return error_response(
                    status,
                    "payload_too_large",
                    "Request body is too large",
                    None,
                );
            }
            return error_response(
                StatusCode::BAD_REQUEST,
                "invalid_json",
                "Request body is invalid",
                None,
            );
        }
    };

    let mut fields = BTreeMap::new();
    let pattern_gauge_stitches = positive(
        request.pattern_gauge_stitches,
        "pattern_gauge_stitches",
        &mut fields,
    );
    let pattern_gauge_rows = positive(
        request.pattern_gauge_rows,
        "pattern_gauge_rows",
        &mut fields,
    );
    let user_gauge_stitches = positive(
        request.user_gauge_stitches,
        "user_gauge_stitches",
        &mut fields,
    );
    let user_gauge_rows = positive(request.user_gauge_rows, "user_gauge_rows", &mut fields);
    let pattern_cast_on_stitches = positive(
        request.pattern_cast_on_stitches,
        "pattern_cast_on_stitches",
        &mut fields,
    );
    let pattern_row_count = request
        .pattern_row_count
        .and_then(|value| positive(Some(value), "pattern_row_count", &mut fields));

    if !fields.is_empty() {
        return error_response(
            StatusCode::UNPROCESSABLE_ENTITY,
            "validation_error",
            "One or more fields are invalid",
            Some(fields),
        );
    }

    let (
        Some(pattern_gauge_stitches),
        Some(pattern_gauge_rows),
        Some(user_gauge_stitches),
        Some(user_gauge_rows),
        Some(pattern_cast_on_stitches),
    ) = (
        pattern_gauge_stitches,
        pattern_gauge_rows,
        user_gauge_stitches,
        user_gauge_rows,
        pattern_cast_on_stitches,
    )
    else {
        return error_response(
            StatusCode::UNPROCESSABLE_ENTITY,
            "validation_error",
            "One or more fields are invalid",
            Some(fields),
        );
    };

    let result = GaugeInput {
        pattern_gauge_stitches,
        pattern_gauge_rows,
        user_gauge_stitches,
        user_gauge_rows,
        pattern_cast_on_stitches,
        pattern_row_count,
    }
    .calculate();

    Json(Envelope {
        data: GaugeResponse {
            adjusted_stitches: result.adjusted_stitches.to_string(),
            adjusted_rows: result.adjusted_rows.map(|rows| rows.to_string()),
        },
    })
    .into_response()
}

fn positive(
    value: Option<serde_json::Value>,
    field: &'static str,
    fields: &mut BTreeMap<&'static str, &'static str>,
) -> Option<NonZeroU32> {
    let count = value
        .as_ref()
        .and_then(serde_json::Value::as_u64)
        .and_then(|value| u32::try_from(value).ok())
        .and_then(NonZeroU32::new);
    count.or_else(|| {
        fields.insert(field, "Must be a positive integer");
        None
    })
}

fn error_response(
    status: StatusCode,
    code: &'static str,
    message: &'static str,
    fields: Option<BTreeMap<&'static str, &'static str>>,
) -> Response {
    (
        status,
        Json(ErrorEnvelope {
            error: GaugeError {
                code,
                message,
                fields,
            },
        }),
    )
        .into_response()
}
