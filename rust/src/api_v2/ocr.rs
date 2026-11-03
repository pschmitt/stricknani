//! Cookie-authenticated OCR over owner-scoped stored images.

use axum::{
    Json, Router,
    body::{Body, to_bytes},
    extract::State,
    http::{Request, StatusCode},
    response::{IntoResponse, Response},
    routing::post,
};
use serde::{Deserialize, Serialize};

use crate::{
    media::{self, OcrMediaError, OcrMediaParent},
    ocr::{OcrError, OcrLanguage},
};

use super::{
    ApiV2State, Envelope,
    access::{self, resource_mutation_user},
};

const MAX_OCR_REQUEST_BYTES: usize = 8 * 1024;

pub(super) fn router() -> Router<ApiV2State> {
    Router::new()
        .route("/utils/ocr", post(ocr))
        .layer(axum::extract::DefaultBodyLimit::max(MAX_OCR_REQUEST_BYTES))
}

#[derive(Debug, Deserialize)]
struct OcrRequest {
    src: String,
    #[serde(default)]
    lang: Option<String>,
    #[serde(default)]
    force: bool,
}

#[derive(Serialize)]
struct OcrData {
    text: String,
}

async fn ocr(State(state): State<ApiV2State>, request: Request<Body>) -> Response {
    let owner_id = match resource_mutation_user(&state.app, request.headers()).await {
        Ok(owner_id) => owner_id,
        Err(response) => return *response,
    };
    let Some(service) = state.ocr_service else {
        return super::api_error(
            StatusCode::SERVICE_UNAVAILABLE,
            "ocr_unavailable",
            "OCR is unavailable",
        );
    };
    if !state.app.ocr_enabled {
        return super::api_error(
            StatusCode::SERVICE_UNAVAILABLE,
            "ocr_unavailable",
            "OCR is unavailable",
        );
    }
    let request_bytes = match to_bytes(request.into_body(), MAX_OCR_REQUEST_BYTES).await {
        Ok(bytes) => bytes,
        Err(_) => {
            return super::api_error(
                StatusCode::PAYLOAD_TOO_LARGE,
                "payload_too_large",
                "OCR request is too large",
            );
        }
    };
    let request = match serde_json::from_slice::<OcrRequest>(&request_bytes) {
        Ok(request) => request,
        Err(_) => {
            return super::api_error(
                StatusCode::UNPROCESSABLE_ENTITY,
                "validation_error",
                "OCR request is invalid",
            );
        }
    };
    let language = match OcrLanguage::parse(request.lang.as_deref()) {
        Ok(language) => language,
        Err(_) => {
            return super::api_error(
                StatusCode::UNPROCESSABLE_ENTITY,
                "invalid_language",
                "OCR language is invalid",
            );
        }
    };
    let uri = match crate::ocr_service::validated_media_path(&request.src) {
        Ok(uri) => uri,
        Err(()) => {
            return super::api_error(
                StatusCode::UNPROCESSABLE_ENTITY,
                "invalid_src",
                "OCR source must be a canonical project or yarn image path",
            );
        }
    };
    let reference = match media_reference(uri.path()) {
        Some(reference) => reference,
        None => {
            return super::api_error(
                StatusCode::UNPROCESSABLE_ENTITY,
                "invalid_src",
                "OCR source must be a canonical project or yarn image path",
            );
        }
    };
    if let Err(retry_after) = state.ocr_limiter.reserve(owner_id) {
        return access::rate_limited(retry_after, "Too many OCR requests; try again later");
    }
    let image =
        match media::read_owned_ocr_image(&state.app, owner_id, reference.0, reference.1).await {
            Ok(image) => image,
            Err(error) => return media_error(error),
        };
    if !is_image_content_type(&image.content_type) {
        return super::api_error(
            StatusCode::UNPROCESSABLE_ENTITY,
            "invalid_image",
            "OCR source is not an image",
        );
    }
    let _force = request.force;
    match service
        .recognize(image.bytes, Some(language.as_str()))
        .await
    {
        Ok(text) => Json(Envelope {
            data: OcrData { text },
        })
        .into_response(),
        Err(error) => ocr_error(error),
    }
}

fn media_reference(path: &str) -> Option<(OcrMediaParent, &str)> {
    let segments: Vec<_> = path.split('/').collect();
    let entity_id = segments.get(3)?.parse::<i64>().ok()?;
    if entity_id <= 0 || entity_id.to_string() != *segments.get(3)? || segments.len() != 5 {
        return None;
    }
    let parent = match *segments.get(2)? {
        "projects" => OcrMediaParent::Project(entity_id),
        "yarns" => OcrMediaParent::Yarn(entity_id),
        _ => return None,
    };
    Some((parent, segments[4]))
}

fn media_error(error: OcrMediaError) -> Response {
    match error {
        OcrMediaError::NotFound => {
            super::api_error(StatusCode::NOT_FOUND, "not_found", "Image not found")
        }
        OcrMediaError::Database => super::api_error(
            StatusCode::INTERNAL_SERVER_ERROR,
            "internal_error",
            "Request failed",
        ),
        OcrMediaError::Unavailable => super::api_error(
            StatusCode::SERVICE_UNAVAILABLE,
            "media_unavailable",
            "Media storage is unavailable",
        ),
        OcrMediaError::TooLarge => super::api_error(
            StatusCode::PAYLOAD_TOO_LARGE,
            "payload_too_large",
            "Image is too large for OCR",
        ),
        OcrMediaError::InvalidImage => super::api_error(
            StatusCode::UNPROCESSABLE_ENTITY,
            "invalid_image",
            "OCR source is not an image",
        ),
    }
}

fn ocr_error(error: OcrError) -> Response {
    match error {
        OcrError::FileTooLarge | OcrError::OutputTooLarge => super::api_error(
            StatusCode::PAYLOAD_TOO_LARGE,
            "payload_too_large",
            "OCR input or output is too large",
        ),
        OcrError::InvalidImage => super::api_error(
            StatusCode::UNPROCESSABLE_ENTITY,
            "invalid_image",
            "OCR source is not a valid image",
        ),
        OcrError::InvalidLanguage => super::api_error(
            StatusCode::UNPROCESSABLE_ENTITY,
            "invalid_language",
            "OCR language is invalid",
        ),
        OcrError::NotAvailable => super::api_error(
            StatusCode::SERVICE_UNAVAILABLE,
            "ocr_unavailable",
            "OCR is unavailable",
        ),
        OcrError::TimedOut => {
            super::api_error(StatusCode::GATEWAY_TIMEOUT, "ocr_timeout", "OCR timed out")
        }
        OcrError::Failed | OcrError::Internal => super::api_error(
            StatusCode::INTERNAL_SERVER_ERROR,
            "ocr_failed",
            "OCR failed",
        ),
    }
}

fn is_image_content_type(content_type: &str) -> bool {
    let media_type = content_type.split(';').next().unwrap_or_default().trim();
    let Some((top_level, subtype)) = media_type.split_once('/') else {
        return false;
    };
    top_level.eq_ignore_ascii_case("image") && !subtype.trim().is_empty()
}
