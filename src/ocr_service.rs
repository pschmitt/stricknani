//! Authenticated OCR utility endpoint over the shared authorized media routes.

use axum::{
    Json, Router,
    body::{Body, to_bytes},
    extract::State,
    http::{HeaderMap, Method, Request, StatusCode, Uri, header},
    response::{IntoResponse, Response},
    routing::post,
};
use serde::{Deserialize, Serialize};
use tower::ServiceExt;

use crate::{
    auth,
    db::AppState,
    ocr::{MAX_OCR_BYTES, OcrError, OcrService},
    web::{self, WebState},
};

#[derive(Clone)]
struct OcrState {
    api: Router,
    app: AppState,
    service: Option<OcrService>,
}

/// Build the OCR endpoint using the shared API/media router for authorization.
pub fn router(api: Router, app: AppState, service: Option<OcrService>) -> Router {
    Router::new()
        .route("/utils/ocr", post(ocr))
        .with_state(OcrState { api, app, service })
}

#[derive(Debug, Deserialize)]
struct OcrRequest {
    src: String,
    #[serde(default)]
    lang: Option<String>,
    #[serde(default)]
    force: bool,
}

#[derive(Debug, Serialize)]
struct OcrResponse {
    text: String,
}

async fn ocr(
    State(state): State<OcrState>,
    headers: HeaderMap,
    Json(request): Json<OcrRequest>,
) -> Response {
    let browser = if headers.contains_key(header::AUTHORIZATION) {
        if auth::bearer_user(&state.app.pool, &headers).await.is_err() {
            return StatusCode::UNAUTHORIZED.into_response();
        }
        false
    } else {
        let web_state = WebState {
            api: state.api.clone(),
            app: state.app.clone(),
        };
        let session = match web::session(&web_state, &headers).await {
            Ok(session) => session,
            Err(auth::AuthError::Unauthorized) => return StatusCode::UNAUTHORIZED.into_response(),
            Err(_) => return StatusCode::INTERNAL_SERVER_ERROR.into_response(),
        };
        let csrf = headers
            .get("x-csrf-token")
            .and_then(|value| value.to_str().ok())
            .unwrap_or_default();
        if !web::valid_csrf(&session.token, csrf) {
            return StatusCode::FORBIDDEN.into_response();
        }
        true
    };

    let Some(service) = state.service else {
        return (
            StatusCode::SERVICE_UNAVAILABLE,
            Json(error("ocr_not_available")),
        )
            .into_response();
    };
    let path = match validated_media_path(&request.src) {
        Ok(path) => path,
        Err(()) => return (StatusCode::BAD_REQUEST, Json(error("invalid_src"))).into_response(),
    };

    let mut media_request = Request::builder().method(Method::GET).uri(path);
    if browser {
        if let Some(cookie) = headers.get(header::COOKIE) {
            media_request = media_request.header(header::COOKIE, cookie.clone());
        }
    } else if let Some(authorization) = headers.get(header::AUTHORIZATION) {
        media_request = media_request.header(header::AUTHORIZATION, authorization.clone());
    }
    let media_request = match media_request.body(Body::empty()) {
        Ok(request) => request,
        Err(_) => return StatusCode::INTERNAL_SERVER_ERROR.into_response(),
    };
    let media_response = match state.api.clone().oneshot(media_request).await {
        Ok(response) => response,
        Err(_) => return StatusCode::INTERNAL_SERVER_ERROR.into_response(),
    };
    if media_response.status() == StatusCode::FORBIDDEN
        || media_response.status() == StatusCode::NOT_FOUND
    {
        return StatusCode::NOT_FOUND.into_response();
    }
    if !media_response.status().is_success() {
        return StatusCode::INTERNAL_SERVER_ERROR.into_response();
    }
    let content_type = media_response
        .headers()
        .get(header::CONTENT_TYPE)
        .and_then(|value| value.to_str().ok())
        .unwrap_or_default();
    if !is_image_content_type(content_type) {
        return (StatusCode::BAD_REQUEST, Json(error("invalid_src"))).into_response();
    }
    if media_response
        .headers()
        .get(header::CONTENT_LENGTH)
        .and_then(|value| value.to_str().ok())
        .and_then(|value| value.parse::<usize>().ok())
        .is_some_and(|length| length > MAX_OCR_BYTES)
    {
        return (StatusCode::PAYLOAD_TOO_LARGE, Json(error("file_too_large"))).into_response();
    }
    let image_bytes = match to_bytes(media_response.into_body(), MAX_OCR_BYTES).await {
        Ok(bytes) => bytes.to_vec(),
        Err(_) => {
            return (StatusCode::PAYLOAD_TOO_LARGE, Json(error("file_too_large"))).into_response();
        }
    };

    // OCR has no cache in this port yet. Accept `force` for API compatibility;
    // every request with an authorized image performs recognition.
    let _force = request.force;
    match service
        .recognize(image_bytes, request.lang.as_deref())
        .await
    {
        Ok(text) => Json(OcrResponse { text }).into_response(),
        Err(error) => ocr_error(error),
    }
}

pub(crate) fn validated_media_path(src: &str) -> Result<Uri, ()> {
    if src.contains('#') || src.contains('?') {
        return Err(());
    }
    let uri = src.parse::<Uri>().map_err(|_| ())?;
    if uri.scheme().is_some() || uri.authority().is_some() || uri.query().is_some() {
        return Err(());
    }
    let path = uri.path();
    let segments: Vec<_> = path.split('/').collect();
    let entity_id = segments.get(3).ok_or(())?.parse::<i64>().map_err(|_| ())?;
    if segments.len() != 5
        || !segments[0].is_empty()
        || segments[1] != "media"
        || !matches!(segments[2], "projects" | "yarns")
        || entity_id <= 0
        || entity_id.to_string() != segments[3]
        || segments[4].is_empty()
        || matches!(segments[4], "." | "..")
        || segments[4].contains(['/', '\\', '%'])
        || segments[4].chars().any(char::is_control)
    {
        return Err(());
    }
    Ok(uri)
}

fn is_image_content_type(content_type: &str) -> bool {
    let media_type = content_type.split(';').next().unwrap_or_default().trim();
    let Some((top_level, subtype)) = media_type.split_once('/') else {
        return false;
    };
    top_level.eq_ignore_ascii_case("image") && !subtype.trim().is_empty()
}

fn ocr_error(error_value: OcrError) -> Response {
    let status = match error_value {
        OcrError::FileTooLarge | OcrError::OutputTooLarge => StatusCode::PAYLOAD_TOO_LARGE,
        OcrError::InvalidImage | OcrError::InvalidLanguage => StatusCode::BAD_REQUEST,
        OcrError::NotAvailable => StatusCode::NOT_IMPLEMENTED,
        OcrError::TimedOut => StatusCode::GATEWAY_TIMEOUT,
        OcrError::Failed | OcrError::Internal => StatusCode::INTERNAL_SERVER_ERROR,
    };
    (status, Json(error(error_value.to_string()))).into_response()
}

#[derive(Serialize)]
struct ErrorBody {
    detail: String,
}

fn error(detail: impl Into<String>) -> ErrorBody {
    ErrorBody {
        detail: detail.into(),
    }
}
