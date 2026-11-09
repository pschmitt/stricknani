//! Imports that run on the server and wait for review (`import_drafts`): start one, close the
//! app, review it days later on any device. `POST` starts the same pipeline as
//! `POST /imports/preview` in the background and answers at once; `GET` lists and reads them,
//! `DELETE` dismisses one (also after the editor saved it). The push loop announces finished ones
//! (`crate::import_drafts`).

use axum::{
    Json, Router,
    body::{Body, to_bytes},
    extract::{DefaultBodyLimit, Path, State},
    http::{HeaderMap, Request, StatusCode},
    response::{IntoResponse, Response},
    routing::get,
};
use serde::{Deserialize, Serialize};
use serde_json::value::RawValue;
use sqlx::Row;

use super::{
    ApiV2State, Envelope,
    access::{resource_mutation_user, resource_user},
    imports::{
        PreviewRequest, api_error, build_preview, payload_too_large, rate_limited, validation_error,
    },
};

/// Imports one user may run at the same time.
const MAX_RUNNING: i64 = 3;
/// Drafts one user may keep waiting.
const MAX_STORED: i64 = 50;
const MAX_REQUEST_BYTES: usize = crate::import_service::MAX_UPLOAD_BYTES + 64 * 1024;
/// An error answer of the pipeline is stored up to this size.
const MAX_ERROR_BYTES: usize = 16 * 1024;

pub(super) fn router() -> Router<ApiV2State> {
    Router::new()
        .route(
            "/imports/drafts",
            get(list)
                .post(create)
                .layer(DefaultBodyLimit::max(MAX_REQUEST_BYTES)),
        )
        .route("/imports/drafts/{id}", get(show).delete(dismiss))
}

#[derive(Deserialize, Default)]
struct DraftOptions {
    /// The language of the "ready" notification (`en`, `de`).
    #[serde(default)]
    locale: Option<String>,
}

/// A draft as the cards and the editor see it. `preview` (the preview endpoint's `data`) only
/// comes with `GET /imports/drafts/{id}`; `error` is the preview endpoint's error body.
#[derive(Serialize)]
struct DraftDto {
    id: i64,
    target: String,
    source_kind: String,
    source_url: Option<String>,
    use_ai: bool,
    status: String,
    title: Option<String>,
    image_url: Option<String>,
    created_at: i64,
    finished_at: Option<i64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    error_status: Option<i64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    error: Option<Box<RawValue>>,
    #[serde(skip_serializing_if = "Option::is_none")]
    preview: Option<Box<RawValue>>,
}

/// The columns of [DraftDto] (a macro, so the queries stay static strings).
macro_rules! columns {
    () => {
        "id, target, source_kind, source_url, use_ai, status, title, image_url, created_at, \
         finished_at, error_status, error_json"
    };
}

fn dto(row: &sqlx::sqlite::SqliteRow, preview: Option<String>) -> DraftDto {
    let raw = |text: Option<String>| text.and_then(|text| RawValue::from_string(text).ok());
    DraftDto {
        id: row.get("id"),
        target: row.get("target"),
        source_kind: row.get("source_kind"),
        source_url: row.get("source_url"),
        use_ai: row.get::<i64, _>("use_ai") != 0,
        status: row.get("status"),
        title: row.get("title"),
        image_url: row.get("image_url"),
        created_at: row.get("created_at"),
        finished_at: row.get("finished_at"),
        error_status: row.get("error_status"),
        error: raw(row.get("error_json")),
        preview: raw(preview),
    }
}

fn database_error() -> Response {
    api_error(
        StatusCode::INTERNAL_SERVER_ERROR,
        "database_error",
        "The import could not be stored",
    )
}

fn not_found() -> Response {
    api_error(StatusCode::NOT_FOUND, "not_found", "Import not found")
}

async fn create(State(state): State<ApiV2State>, request: Request<Body>) -> Response {
    let user_id = match resource_mutation_user(&state.app, request.headers()).await {
        Ok(user_id) => user_id,
        Err(response) => return *response,
    };
    let Some(imports) = state.imports.clone() else {
        return api_error(
            StatusCode::SERVICE_UNAVAILABLE,
            "import_unavailable",
            "Document import is unavailable",
        );
    };
    let bytes = match to_bytes(request.into_body(), MAX_REQUEST_BYTES).await {
        Ok(bytes) => bytes,
        Err(_) => return payload_too_large(),
    };
    let Ok(preview_request) = serde_json::from_slice::<PreviewRequest>(&bytes) else {
        return validation_error("Request JSON is invalid", "body");
    };
    let options = serde_json::from_slice::<DraftOptions>(&bytes).unwrap_or_default();
    let locale = match options.locale.as_deref() {
        Some("de") => "de",
        _ => "en",
    };
    // Checked here as well, so a request that can never work is not stored as a draft.
    let (target, source_kind, source_url, use_ai) = match &preview_request {
        PreviewRequest::Url {
            url,
            use_ai,
            target,
        } => {
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
            let target = match target {
                crate::url_import::ImportTarget::Yarn => "yarn",
                crate::url_import::ImportTarget::Project => "project",
            };
            (target, "url", Some(url.trim().to_owned()), *use_ai)
        }
        PreviewRequest::Text { text, use_ai } => {
            if text.trim().is_empty() {
                return validation_error("Text is required", "text");
            }
            ("project", "text", None, *use_ai)
        }
    };
    let counts = sqlx::query(
        "SELECT COUNT(*) AS stored, COALESCE(SUM(status = 'running'), 0) AS running \
         FROM import_drafts WHERE user_id = ?",
    )
    .bind(user_id)
    .fetch_one(&state.app.pool)
    .await;
    let Ok(counts) = counts else {
        return database_error();
    };
    if counts.get::<i64, _>("running") >= MAX_RUNNING {
        return api_error(
            StatusCode::TOO_MANY_REQUESTS,
            "too_many_imports",
            "Wait until one of your imports is done",
        );
    }
    if counts.get::<i64, _>("stored") >= MAX_STORED {
        return api_error(
            StatusCode::CONFLICT,
            "too_many_drafts",
            "Review or dismiss some waiting imports first",
        );
    }
    if let Err(retry_after) = state.import_preview_limiter.reserve(user_id) {
        return rate_limited(retry_after);
    }
    let now = chrono::Utc::now().timestamp();
    let inserted = sqlx::query(concat!(
        "INSERT INTO import_drafts (user_id, target, source_kind, source_url, use_ai, status, \
         locale, created_at) VALUES (?, ?, ?, ?, ?, 'running', ?, ?) RETURNING ",
        columns!()
    ))
    .bind(user_id)
    .bind(target)
    .bind(source_kind)
    .bind(&source_url)
    .bind(i64::from(use_ai))
    .bind(locale)
    .bind(now)
    .fetch_one(&state.app.pool)
    .await;
    let Ok(row) = inserted else {
        return database_error();
    };
    let id: i64 = row.get("id");
    let job_state = state.clone();
    tokio::spawn(async move {
        run(job_state, imports, user_id, id, preview_request).await;
    });
    (
        StatusCode::ACCEPTED,
        Json(Envelope {
            data: dto(&row, None),
        }),
    )
        .into_response()
}

/// The import itself; its result or error is stored unless the draft was dismissed meanwhile.
async fn run(
    state: ApiV2State,
    imports: crate::import_service::ImportContext,
    user_id: i64,
    id: i64,
    request: PreviewRequest,
) {
    let result = build_preview(&state, &imports, user_id, request).await;
    let now = chrono::Utc::now().timestamp();
    let pool = &state.app.pool;
    let stored = match result {
        Ok(data) => {
            let title = if target_is_yarn(&data) {
                data.draft.yarn.as_ref().and_then(|yarn| yarn.name.clone())
            } else {
                data.draft.project.name.clone()
            }
            .map(|title| title.trim().chars().take(512).collect::<String>())
            .filter(|title| !title.is_empty());
            let image_url = data
                .draft
                .project
                .image_urls
                .first()
                .or_else(|| {
                    data.draft
                        .yarn
                        .as_ref()
                        .and_then(|yarn| yarn.image_urls.first())
                })
                .filter(|url| url.len() <= 2048)
                .cloned();
            let Ok(json) = serde_json::to_string(&data) else {
                return;
            };
            sqlx::query(
                "UPDATE import_drafts SET status = 'ready', preview_json = ?, title = ?, \
                 image_url = ?, finished_at = ? WHERE id = ? AND status = 'running'",
            )
            .bind(json)
            .bind(title)
            .bind(image_url)
            .bind(now)
            .bind(id)
            .execute(pool)
            .await
        }
        Err(response) => {
            let response = *response;
            let status = i64::from(response.status().as_u16());
            let body = to_bytes(response.into_body(), MAX_ERROR_BYTES)
                .await
                .ok()
                .and_then(|bytes| String::from_utf8(bytes.to_vec()).ok())
                .filter(|body| RawValue::from_string(body.clone()).is_ok());
            sqlx::query(
                "UPDATE import_drafts SET status = 'failed', error_status = ?, error_json = ?, \
                 finished_at = ? WHERE id = ? AND status = 'running'",
            )
            .bind(status)
            .bind(body)
            .bind(now)
            .bind(id)
            .execute(pool)
            .await
        }
    };
    if stored.is_err() {
        tracing::warn!(
            component = "imports",
            draft = id,
            "imports.draft_store_failed"
        );
    }
}

/// A yarn import's preview has the yarn's details and no project name.
fn target_is_yarn(data: &super::imports::PreviewData) -> bool {
    data.draft.yarn.is_some() && data.draft.project.name.is_none()
}

async fn list(State(state): State<ApiV2State>, headers: HeaderMap) -> Response {
    let user_id = match resource_user(&state.app, &headers).await {
        Ok(user_id) => user_id,
        Err(response) => return *response,
    };
    let rows = sqlx::query(concat!(
        "SELECT ",
        columns!(),
        " FROM import_drafts WHERE user_id = ? ORDER BY created_at DESC, id DESC"
    ))
    .bind(user_id)
    .fetch_all(&state.app.pool)
    .await;
    match rows {
        Ok(rows) => Json(Envelope {
            data: rows.iter().map(|row| dto(row, None)).collect::<Vec<_>>(),
        })
        .into_response(),
        Err(_) => database_error(),
    }
}

async fn show(
    State(state): State<ApiV2State>,
    headers: HeaderMap,
    Path(id): Path<i64>,
) -> Response {
    let user_id = match resource_user(&state.app, &headers).await {
        Ok(user_id) => user_id,
        Err(response) => return *response,
    };
    let row = sqlx::query(concat!(
        "SELECT ",
        columns!(),
        ", preview_json FROM import_drafts WHERE id = ? AND user_id = ?"
    ))
    .bind(id)
    .bind(user_id)
    .fetch_optional(&state.app.pool)
    .await;
    match row {
        Ok(Some(row)) => {
            let preview: Option<String> = row.get("preview_json");
            Json(Envelope {
                data: dto(&row, preview),
            })
            .into_response()
        }
        Ok(None) => not_found(),
        Err(_) => database_error(),
    }
}

async fn dismiss(
    State(state): State<ApiV2State>,
    headers: HeaderMap,
    Path(id): Path<i64>,
) -> Response {
    let user_id = match resource_mutation_user(&state.app, &headers).await {
        Ok(user_id) => user_id,
        Err(response) => return *response,
    };
    match sqlx::query("DELETE FROM import_drafts WHERE id = ? AND user_id = ?")
        .bind(id)
        .bind(user_id)
        .execute(&state.app.pool)
        .await
    {
        Ok(result) if result.rows_affected() > 0 => StatusCode::NO_CONTENT.into_response(),
        Ok(_) => not_found(),
        Err(_) => database_error(),
    }
}
