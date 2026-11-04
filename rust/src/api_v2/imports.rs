//! Same-origin document import previews for the browser API.

use std::{collections::BTreeMap, time::Duration};

use axum::{
    Json, Router,
    body::{Body, to_bytes},
    extract::{
        DefaultBodyLimit, Multipart, State,
        multipart::{MultipartError, MultipartRejection},
    },
    http::{HeaderMap, Request, StatusCode},
    response::{IntoResponse, Response},
    routing::{get, post},
};
use serde::{Deserialize, Serialize};

use crate::{
    ai::{AiClient, AiImage},
    document_worker::WorkerError,
    import_document::ImportDocumentDraft,
    import_service::{self, ImportFailure},
    imports::FetchError,
    url_import::ImportTarget,
};

use super::{
    ApiV2State, Envelope,
    access::{self, OwnerRequestLimiter, resource_mutation_user},
};

const MAX_PREVIEW_JSON_BYTES: usize = 32 * 1024 * 1024;
const MAX_PREVIEW_MULTIPART_BYTES: usize = import_service::MAX_UPLOAD_BYTES + 64 * 1024;
const IMPORT_RATE_LIMIT: usize = 20;
const IMPORT_RATE_WINDOW: Duration = Duration::from_secs(10 * 60);
const MAX_IMPORT_RATE_KEYS: usize = 4096;

#[derive(Clone)]
pub(super) struct PreviewLimiter(OwnerRequestLimiter);

impl Default for PreviewLimiter {
    fn default() -> Self {
        Self(OwnerRequestLimiter::new(
            IMPORT_RATE_LIMIT,
            IMPORT_RATE_WINDOW,
            MAX_IMPORT_RATE_KEYS,
        ))
    }
}

impl PreviewLimiter {
    fn reserve(&self, owner_id: i64) -> Result<(), u64> {
        self.0.reserve(owner_id)
    }
}

pub(super) fn router() -> Router<ApiV2State> {
    Router::new()
        .route(
            "/imports/preview",
            post(preview).layer(DefaultBodyLimit::max(MAX_PREVIEW_JSON_BYTES)),
        )
        .route("/imports/image", get(preview_image))
        .route(
            "/imports/preview-file",
            post(preview_file).layer(DefaultBodyLimit::max(MAX_PREVIEW_MULTIPART_BYTES)),
        )
}

#[derive(Debug, Deserialize)]
#[serde(tag = "type", rename_all = "snake_case")]
enum PreviewRequest {
    Url {
        url: String,
        #[serde(default)]
        use_ai: bool,
        #[serde(default)]
        target: ImportTarget,
    },
    Text {
        text: String,
        #[serde(default)]
        use_ai: bool,
    },
}

#[derive(Serialize)]
struct PreviewData {
    draft: ImportDocumentDraft,
    content_sha256: String,
    ai_requested: bool,
    is_ai_enhanced: bool,
    ai_fallback: bool,
}

#[derive(Serialize)]
struct ErrorBody {
    error: ImportApiError,
}

#[derive(Serialize)]
struct ImportApiError {
    code: &'static str,
    message: &'static str,
    #[serde(skip_serializing_if = "Option::is_none")]
    fields: Option<BTreeMap<&'static str, &'static str>>,
}

async fn preview(State(state): State<ApiV2State>, request: Request<Body>) -> Response {
    let user_id = match resource_mutation_user(&state.app, request.headers()).await {
        Ok(user_id) => user_id,
        Err(response) => return *response,
    };
    let Some(imports) = state.imports else {
        return api_error(
            StatusCode::SERVICE_UNAVAILABLE,
            "import_unavailable",
            "Document import is unavailable",
        );
    };
    if let Err(retry_after) = state.import_preview_limiter.reserve(user_id) {
        return rate_limited(retry_after);
    }
    let bytes = match to_bytes(request.into_body(), MAX_PREVIEW_JSON_BYTES).await {
        Ok(bytes) => bytes,
        Err(_) => return payload_too_large(),
    };
    let request = match serde_json::from_slice::<PreviewRequest>(&bytes) {
        Ok(request) => request,
        Err(_) => return validation_error("Request JSON is invalid", "body"),
    };
    let mut ai_target = ImportTarget::Project;
    let mut url_import = false;
    let mut browse = import_service::BrowseState::Off;
    let (mut draft, source_text, ai_requested) = match request {
        PreviewRequest::Url {
            url,
            use_ai,
            target,
        } => {
            ai_target = target;
            url_import = true;
            if !state.app.external_imports {
                return api_error(
                    StatusCode::SERVICE_UNAVAILABLE,
                    "feature_disabled",
                    "Remote URL imports are disabled",
                );
            }
            if url.trim().is_empty() || url.len() > 2048 {
                return validation_error("URL is required and must be at most 2048 bytes", "url");
            }
            let may_browse = use_ai && imports.ai.as_deref().is_some_and(AiClient::browse_enabled);
            match import_service::import_url_browsing(
                &imports.worker_path,
                &url,
                target,
                may_browse,
            )
            .await
            {
                Ok(imported) => {
                    browse = imported.browse;
                    (imported.draft, imported.source_text, use_ai)
                }
                Err(error) => return import_error(error),
            }
        }
        PreviewRequest::Text { text, use_ai } => {
            if text.trim().is_empty() {
                return validation_error("Text is required", "text");
            }
            if text.len() > import_service::MAX_UPLOAD_BYTES {
                return payload_too_large();
            }
            let source_text = text.clone();
            match import_service::parse_bytes(
                &imports.worker_path,
                text.into_bytes(),
                Some("text/plain".to_owned()),
                Some("pattern.txt".to_owned()),
                None,
            )
            .await
            {
                Ok(draft) => (draft, source_text, use_ai),
                Err(error) => return worker_error(error),
            }
        }
    };
    let is_ai_enhanced = if url_import {
        let enhanced = import_service::normalize_with_ai(
            imports.ai.as_deref(),
            &mut draft,
            ai_requested,
            &source_text,
            ai_target,
            &browse,
        )
        .await;
        // A refused page gave the AI nothing to start from, so its failure is the fetch failure.
        if let import_service::BrowseState::Blocked { error, .. } = &browse
            && !enhanced
        {
            return import_error(ImportFailure::Fetch(*error));
        }
        enhanced
    } else {
        import_service::enhance_draft(
            imports.ai.as_deref(),
            &mut draft,
            ai_requested,
            source_text,
            Vec::new(),
        )
        .await
    };
    preview_response(draft, ai_requested, is_ai_enhanced)
}

#[derive(Deserialize)]
struct ImageQuery {
    url: String,
}

/// Same-origin thumbnail of a picture found on an imported page, so the review step can show it
/// without the browser or app contacting the remote site. Goes through the safe fetcher.
async fn preview_image(
    State(state): State<ApiV2State>,
    headers: HeaderMap,
    axum::extract::Query(query): axum::extract::Query<ImageQuery>,
) -> Response {
    let user_id = match access::resource_user(&state.app, &headers).await {
        Ok(user_id) => user_id,
        Err(response) => return *response,
    };
    if !state.app.external_imports || state.imports.is_none() {
        return api_error(
            StatusCode::SERVICE_UNAVAILABLE,
            "feature_disabled",
            "Remote URL imports are disabled",
        );
    }
    if let Err(retry_after) = state.import_image_limiter.reserve(user_id) {
        return rate_limited(retry_after);
    }
    if query.url.len() > 2048 {
        return validation_error("URL must be at most 2048 bytes", "url");
    }
    let fetched =
        match crate::imports::fetch_url(&crate::imports::normalize_input_url(&query.url)).await {
            Ok(fetched) => fetched,
            Err(error) => return import_error(ImportFailure::Fetch(error)),
        };
    const MAX_IMAGE_BYTES: usize = 3 * 1024 * 1024;
    let media_type = match image::guess_format(&fetched.body) {
        Ok(image::ImageFormat::Jpeg) => "image/jpeg",
        Ok(image::ImageFormat::Png) => "image/png",
        Ok(image::ImageFormat::WebP) => "image/webp",
        Ok(image::ImageFormat::Gif) => "image/gif",
        _ => {
            return api_error(
                StatusCode::UNPROCESSABLE_ENTITY,
                "unsupported_document",
                "This document type is not supported",
            );
        }
    };
    if fetched.body.len() > MAX_IMAGE_BYTES {
        return payload_too_large();
    }
    (
        StatusCode::OK,
        [
            (axum::http::header::CONTENT_TYPE, media_type),
            (axum::http::header::CACHE_CONTROL, "private, max-age=3600"),
            (axum::http::header::X_CONTENT_TYPE_OPTIONS, "nosniff"),
            (
                axum::http::header::CONTENT_SECURITY_POLICY,
                "default-src 'none'; sandbox",
            ),
        ],
        fetched.body,
    )
        .into_response()
}

async fn preview_file(
    State(state): State<ApiV2State>,
    headers: HeaderMap,
    multipart: Result<Multipart, MultipartRejection>,
) -> Response {
    let user_id = match resource_mutation_user(&state.app, &headers).await {
        Ok(user_id) => user_id,
        Err(response) => return *response,
    };
    let Some(imports) = state.imports else {
        return api_error(
            StatusCode::SERVICE_UNAVAILABLE,
            "import_unavailable",
            "Document import is unavailable",
        );
    };
    if let Err(retry_after) = state.import_preview_limiter.reserve(user_id) {
        return rate_limited(retry_after);
    }
    let mut multipart = match multipart {
        Ok(multipart) => multipart,
        Err(error) => return multipart_error(error),
    };
    let mut file = None;
    let mut use_ai = false;
    let mut ai_seen = false;
    loop {
        let field = match multipart.next_field().await {
            Ok(Some(field)) => field,
            Ok(None) => break,
            Err(error) => return multipart_stream_error(error),
        };
        match field.name() {
            Some("use_ai") if !ai_seen => {
                ai_seen = true;
                use_ai = match field.text().await {
                    Ok(value) if matches!(value.as_str(), "true" | "1" | "on") => true,
                    Ok(value) if matches!(value.as_str(), "false" | "0" | "off" | "") => false,
                    _ => return validation_error("AI opt-in must be a boolean", "use_ai"),
                };
            }
            Some("file") if file.is_none() => {
                let filename = field.file_name().unwrap_or("upload").to_owned();
                let content_type = field
                    .content_type()
                    .map(str::to_owned)
                    .unwrap_or_else(|| "application/octet-stream".to_owned());
                match field.bytes().await {
                    Ok(bytes) if bytes.len() <= import_service::MAX_UPLOAD_BYTES => {
                        file = Some((filename, content_type, bytes.to_vec()));
                    }
                    Ok(_) => return payload_too_large(),
                    Err(error) => return multipart_stream_error(error),
                }
            }
            _ => return validation_error("Unexpected multipart field", "file"),
        }
    }
    let Some((filename, content_type, bytes)) = file else {
        return validation_error("A document file is required", "file");
    };
    let ai_image = use_ai.then(|| bytes.clone());
    let mut draft = match import_service::parse_bytes(
        &imports.worker_path,
        bytes,
        Some(content_type),
        Some(filename),
        None,
    )
    .await
    {
        Ok(draft) => draft,
        Err(error) => return worker_error(error),
    };
    let images = match (
        draft
            .image
            .as_ref()
            .and_then(|facts| import_service::ai_media_type(&facts.format)),
        ai_image,
    ) {
        (Some(media_type), Some(bytes)) => vec![AiImage {
            media_type: media_type.to_owned(),
            bytes,
        }],
        _ => Vec::new(),
    };
    let is_ai_enhanced = import_service::enhance_draft(
        imports.ai.as_deref(),
        &mut draft,
        use_ai,
        String::new(),
        images,
    )
    .await;
    preview_response(draft, use_ai, is_ai_enhanced)
}

fn preview_response(
    draft: ImportDocumentDraft,
    ai_requested: bool,
    is_ai_enhanced: bool,
) -> Response {
    let content_sha256 = draft.sha256.clone();
    Json(Envelope {
        data: PreviewData {
            draft,
            content_sha256,
            ai_requested,
            is_ai_enhanced,
            ai_fallback: ai_requested && !is_ai_enhanced,
        },
    })
    .into_response()
}

fn multipart_error(error: MultipartRejection) -> Response {
    if error.status() == StatusCode::PAYLOAD_TOO_LARGE {
        payload_too_large()
    } else {
        multipart_error_response()
    }
}

fn multipart_stream_error(error: MultipartError) -> Response {
    if error.status() == StatusCode::PAYLOAD_TOO_LARGE {
        payload_too_large()
    } else {
        multipart_error_response()
    }
}

fn multipart_error_response() -> Response {
    api_error(
        StatusCode::BAD_REQUEST,
        "invalid_multipart",
        "Multipart upload is invalid",
    )
}

fn import_error(error: ImportFailure) -> Response {
    match error {
        ImportFailure::Fetch(FetchError::BodyTooLarge) => payload_too_large(),
        ImportFailure::Fetch(
            FetchError::InvalidUrl
            | FetchError::UnsupportedScheme
            | FetchError::CredentialsNotAllowed
            | FetchError::FragmentNotAllowed
            | FetchError::MissingHost,
        ) => validation_error("URL must be a valid HTTP or HTTPS address", "url"),
        ImportFailure::Fetch(FetchError::Timeout) => api_error(
            StatusCode::GATEWAY_TIMEOUT,
            "import_timeout",
            "Remote import timed out",
        ),
        ImportFailure::Fetch(
            FetchError::UnsafeAddress | FetchError::InvalidRedirect | FetchError::TooManyRedirects,
        ) => api_error(
            StatusCode::UNPROCESSABLE_ENTITY,
            "unsafe_url",
            "Remote URL is not allowed",
        ),
        ImportFailure::Fetch(FetchError::HttpStatus(401 | 403 | 429 | 503)) => api_error(
            StatusCode::BAD_GATEWAY,
            "import_blocked",
            "The website refused the request. Try the page text instead",
        ),
        ImportFailure::Fetch(FetchError::HttpStatus(404 | 410)) => api_error(
            StatusCode::BAD_GATEWAY,
            "import_not_found",
            "The page was not found. Check the link",
        ),
        ImportFailure::Fetch(_) => api_error(
            StatusCode::BAD_GATEWAY,
            "import_fetch_failed",
            "Remote document could not be fetched",
        ),
        ImportFailure::Worker(error) => worker_error(error),
    }
}

fn worker_error(error: WorkerError) -> Response {
    match error {
        WorkerError::RequestTooLarge | WorkerError::ResponseTooLarge => payload_too_large(),
        WorkerError::UnsupportedDocument => api_error(
            StatusCode::UNPROCESSABLE_ENTITY,
            "unsupported_document",
            "This document type is not supported",
        ),
        WorkerError::InvalidDocument => api_error(
            StatusCode::UNPROCESSABLE_ENTITY,
            "invalid_document",
            "The imported document could not be parsed",
        ),
        WorkerError::TimedOut => api_error(
            StatusCode::GATEWAY_TIMEOUT,
            "import_timeout",
            "Document parsing timed out",
        ),
        _ => api_error(
            StatusCode::SERVICE_UNAVAILABLE,
            "import_unavailable",
            "Document parser is unavailable",
        ),
    }
}

fn validation_error(message: &'static str, field: &'static str) -> Response {
    api_error_with_fields(
        StatusCode::UNPROCESSABLE_ENTITY,
        "validation_error",
        message,
        BTreeMap::from([(field, "Invalid value")]),
    )
}

fn payload_too_large() -> Response {
    api_error(
        StatusCode::PAYLOAD_TOO_LARGE,
        "payload_too_large",
        "Import document is too large",
    )
}

fn rate_limited(retry_after: u64) -> Response {
    access::rate_limited(retry_after, "Too many import previews; try again later")
}

fn api_error(status: StatusCode, code: &'static str, message: &'static str) -> Response {
    (
        status,
        Json(ErrorBody {
            error: ImportApiError {
                code,
                message,
                fields: None,
            },
        }),
    )
        .into_response()
}

fn api_error_with_fields(
    status: StatusCode,
    code: &'static str,
    message: &'static str,
    fields: BTreeMap<&'static str, &'static str>,
) -> Response {
    (
        status,
        Json(ErrorBody {
            error: ImportApiError {
                code,
                message,
                fields: Some(fields),
            },
        }),
    )
        .into_response()
}

#[cfg(test)]
mod tests {
    use super::{IMPORT_RATE_LIMIT, MAX_IMPORT_RATE_KEYS, PreviewLimiter};

    #[test]
    fn preview_limiter_caps_requests_per_owner() {
        let limiter = PreviewLimiter::default();
        for _ in 0..IMPORT_RATE_LIMIT {
            assert_eq!(limiter.reserve(42), Ok(()));
        }
        assert!(limiter.reserve(42).is_err());
        assert_eq!(limiter.reserve(43), Ok(()));
    }

    #[test]
    fn preview_limiter_caps_owner_key_storage() {
        let limiter = PreviewLimiter::default();
        for owner_id in 1..=i64::try_from(MAX_IMPORT_RATE_KEYS).unwrap() {
            assert_eq!(limiter.reserve(owner_id), Ok(()));
        }
        assert!(
            limiter
                .reserve(i64::try_from(MAX_IMPORT_RATE_KEYS + 1).unwrap())
                .is_err()
        );
    }
}
