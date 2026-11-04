//! Authenticated, owner-scoped media uploads and serving.

use std::collections::HashMap;
use std::collections::HashSet;
use std::io::{Cursor, ErrorKind};
use std::path::{Path, PathBuf};
use std::sync::{Arc, LazyLock};

use axum::body::Body;
use axum::extract::{Multipart, Path as AxumPath, State};
use axum::http::{HeaderMap, HeaderName, HeaderValue, StatusCode, header};
use axum::response::{IntoResponse, Response};
use axum::routing::{delete, get, post};
use axum::{Json, Router};
use chrono::{DateTime, SecondsFormat, Utc};
use image::{DynamicImage, ImageFormat, ImageReader, Limits};
use rand::RngCore;
use rand::rngs::OsRng;
use serde::Serialize;
use serde_json::Value;
use sqlx::{FromRow, QueryBuilder, Sqlite, SqlitePool, Transaction};
use tokio::io::{AsyncReadExt, AsyncWriteExt};

use crate::audit::{Action as AuditAction, Entity as AuditEntity};
use crate::auth::{AuthError, bearer_user};
use crate::db::AppState;
use crate::media_ids::{self, Namespace};

const MAX_UPLOAD_BYTES: usize = 25 * 1024 * 1024;
const MAX_IMAGE_PIXELS: u64 = 64_000_000;
const MAX_IMAGE_DIMENSION: u32 = 20_000;
const MAX_IMAGE_ALLOCATION: u64 = 256 * 1024 * 1024;
const MAX_ALT_TEXT_BYTES: usize = 4096;
const GC_BATCH_SIZE: i64 = 100;
const MAX_ORDERED_MEDIA_COUNT: usize = 4096;

/// Build media upload and serving endpoints at their full API/media paths.
pub fn router() -> Router<AppState> {
    Router::new()
        .route(
            "/api/v1/projects/{project_id}/images/title",
            post(upload_title_image),
        )
        .route(
            "/api/v1/projects/{project_id}/steps/{step_id}/images",
            post(upload_step_image),
        )
        .route(
            "/api/v1/projects/{project_id}/attachments",
            post(upload_attachment),
        )
        .route("/api/v1/yarns/{yarn_id}/photos", post(upload_yarn_photo))
        .route(
            "/api/v1/projects/{project_id}/images/{image_id}",
            delete(delete_project_image),
        )
        .route(
            "/api/v1/projects/{project_id}/attachments/{attachment_id}",
            delete(delete_project_attachment),
        )
        .route(
            "/api/v1/yarns/{yarn_id}/photos/{photo_id}",
            delete(delete_yarn_photo),
        )
        .route(
            "/media/thumbnails/{subdir}/{parent_id}/{filename}",
            get(serve_thumbnail),
        )
        .route(
            "/media/thumbnails/users/{user_id}/{filename}",
            get(serve_avatar_thumbnail),
        )
        .route("/media/users/{user_id}/{filename}", get(serve_avatar))
        .route("/media/{subdir}/{parent_id}/{filename}", get(serve_media))
}

#[derive(Debug, FromRow)]
struct MediaRow {
    id: i64,
    public_id: i64,
    owner_id: i64,
    project_id: Option<i64>,
    yarn_id: Option<i64>,
    step_id: Option<i64>,
    kind: String,
    filename: String,
    original_filename: String,
    content_type: String,
    size_bytes: i64,
    alt_text: String,
    is_primary: bool,
    position: i64,
    is_project_cover: bool,
    is_safe_inline: bool,
    legacy_image_type: Option<String>,
    is_stitch_sample: Option<bool>,
    legacy_is_title_image: Option<bool>,
}

#[derive(Clone, Copy, Debug)]
pub(crate) enum OcrMediaParent {
    Project(i64),
    Yarn(i64),
}

#[derive(Debug)]
pub(crate) struct OcrImage {
    pub(crate) bytes: Vec<u8>,
    pub(crate) content_type: String,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) enum OcrMediaError {
    NotFound,
    Database,
    Unavailable,
    TooLarge,
    InvalidImage,
}

#[derive(Debug, Serialize)]
pub(crate) struct ImageResponse {
    pub(crate) id: i64,
    pub(crate) url: String,
    pub(crate) thumbnail_url: String,
    pub(crate) alt_text: String,
    pub(crate) image_type: String,
    pub(crate) is_title_image: bool,
    pub(crate) is_stitch_sample: bool,
    pub(crate) step_id: Option<i64>,
}

#[derive(Debug, Serialize)]
pub(crate) struct AttachmentResponse {
    pub(crate) id: i64,
    pub(crate) filename: String,
    pub(crate) original_filename: String,
    pub(crate) content_type: String,
    pub(crate) size_bytes: i64,
    pub(crate) url: String,
}

#[derive(Debug, Serialize)]
pub(crate) struct YarnPhotoResponse {
    pub(crate) id: i64,
    pub(crate) url: String,
    pub(crate) thumbnail_url: String,
    pub(crate) alt_text: String,
    pub(crate) is_primary: bool,
}

#[derive(Debug)]
struct Upload {
    bytes: Vec<u8>,
    original_filename: String,
    alt_text: String,
    claimed_content_type: Option<String>,
}

#[derive(Debug)]
pub(crate) struct PreparedImage {
    pub(crate) bytes: Vec<u8>,
    pub(crate) thumbnail: Vec<u8>,
    pub(crate) width: u32,
    pub(crate) height: u32,
    pub(crate) content_type: &'static str,
    pub(crate) extension: &'static str,
}

pub(crate) struct AvatarUpload {
    pub(crate) bytes: Vec<u8>,
    pub(crate) original_filename: String,
}

#[derive(Debug)]
struct SavedFiles {
    filename: String,
    thumbnail_path: Option<PathBuf>,
}

#[derive(Debug, FromRow)]
struct GarbageRow {
    filename: String,
    subdir: String,
    parent_id: i64,
}

#[derive(Debug, FromRow)]
struct AvatarRow {
    filename: String,
    original_filename: Option<String>,
    content_type: Option<String>,
}

#[derive(Clone, Copy, Debug)]
pub(crate) enum AdminAvatarError {
    Forbidden,
    NotFound,
    Database,
    Unavailable,
}

#[derive(Debug, FromRow)]
struct AvatarGarbageRow {
    user_id: i64,
    filename: String,
}

#[derive(Debug, Serialize)]
struct ErrorResponse {
    detail: String,
}

async fn project_exists(
    pool: &SqlitePool,
    user_id: i64,
    project_id: i64,
) -> Result<bool, sqlx::Error> {
    sqlx::query_scalar::<_, i64>(
        "SELECT EXISTS(SELECT 1 FROM projects WHERE id = ? AND owner_id = ?)",
    )
    .bind(project_id)
    .bind(user_id)
    .fetch_one(pool)
    .await
    .map(|exists| exists != 0)
}

async fn step_belongs_to_project(
    pool: &SqlitePool,
    user_id: i64,
    project_id: i64,
    step_id: i64,
) -> Result<bool, sqlx::Error> {
    sqlx::query_scalar::<_, i64>(
        "SELECT EXISTS(SELECT 1 FROM project_steps s \
         JOIN projects p ON p.id = s.project_id \
         WHERE p.id = ? AND p.owner_id = ? AND s.id = ?)",
    )
    .bind(project_id)
    .bind(user_id)
    .bind(step_id)
    .fetch_one(pool)
    .await
    .map(|exists| exists != 0)
}

async fn verify_project_target(
    tx: &mut Transaction<'_, Sqlite>,
    user_id: i64,
    project_id: i64,
) -> Result<bool, sqlx::Error> {
    sqlx::query_scalar::<_, i64>(
        "SELECT EXISTS(SELECT 1 FROM projects WHERE id = ? AND owner_id = ?)",
    )
    .bind(project_id)
    .bind(user_id)
    .fetch_one(&mut **tx)
    .await
    .map(|exists| exists != 0)
}

async fn verify_image_target(
    tx: &mut Transaction<'_, Sqlite>,
    user_id: i64,
    project_id: i64,
    step_id: Option<i64>,
) -> Result<bool, sqlx::Error> {
    match step_id {
        Some(step_id) => sqlx::query_scalar::<_, i64>(
            "SELECT EXISTS(SELECT 1 FROM project_steps s \
                 JOIN projects p ON p.id = s.project_id \
                 WHERE p.id = ? AND p.owner_id = ? AND s.id = ?)",
        )
        .bind(project_id)
        .bind(user_id)
        .bind(step_id)
        .fetch_one(&mut **tx)
        .await
        .map(|exists| exists != 0),
        None => verify_project_target(tx, user_id, project_id).await,
    }
}

async fn yarn_name(
    pool: &SqlitePool,
    user_id: i64,
    yarn_id: i64,
) -> Result<Option<String>, sqlx::Error> {
    sqlx::query_scalar::<_, String>("SELECT name FROM yarns WHERE id = ? AND owner_id = ?")
        .bind(yarn_id)
        .bind(user_id)
        .fetch_optional(pool)
        .await
}

async fn bump_project_timestamp(
    tx: &mut Transaction<'_, Sqlite>,
    user_id: i64,
    project_id: i64,
) -> Result<String, sqlx::Error> {
    let current = sqlx::query_scalar::<_, String>(
        "SELECT updated_at FROM projects WHERE id = ? AND owner_id = ?",
    )
    .bind(project_id)
    .bind(user_id)
    .fetch_optional(&mut **tx)
    .await?
    .ok_or_else(|| sqlx::Error::Protocol("owned project disappeared".to_owned()))?;
    let updated_at = newer_timestamp(&current);
    sqlx::query("UPDATE projects SET updated_at = ? WHERE id = ? AND owner_id = ?")
        .bind(&updated_at)
        .bind(project_id)
        .bind(user_id)
        .execute(&mut **tx)
        .await?;
    Ok(updated_at)
}

async fn bump_yarn_timestamp(
    tx: &mut Transaction<'_, Sqlite>,
    user_id: i64,
    yarn_id: i64,
) -> Result<String, sqlx::Error> {
    let current = sqlx::query_scalar::<_, String>(
        "SELECT updated_at FROM yarns WHERE id = ? AND owner_id = ?",
    )
    .bind(yarn_id)
    .bind(user_id)
    .fetch_optional(&mut **tx)
    .await?
    .ok_or_else(|| sqlx::Error::Protocol("owned yarn disappeared".to_owned()))?;
    let updated_at = newer_timestamp(&current);
    sqlx::query("UPDATE yarns SET updated_at = ? WHERE id = ? AND owner_id = ?")
        .bind(&updated_at)
        .bind(yarn_id)
        .bind(user_id)
        .execute(&mut **tx)
        .await?;
    Ok(updated_at)
}

async fn next_project_position(
    tx: &mut Transaction<'_, Sqlite>,
    project_id: i64,
    step_id: Option<i64>,
) -> Result<i64, sqlx::Error> {
    let kind = if step_id.is_some() { "step" } else { "title" };
    sqlx::query_scalar::<_, i64>(
        "SELECT COALESCE(MAX(position) + 1, 0) FROM media_files \
         WHERE project_id = ? AND kind = ? AND step_id IS ?",
    )
    .bind(project_id)
    .bind(kind)
    .bind(step_id)
    .fetch_one(&mut **tx)
    .await
}

async fn next_yarn_position(
    tx: &mut Transaction<'_, Sqlite>,
    yarn_id: i64,
) -> Result<i64, sqlx::Error> {
    sqlx::query_scalar::<_, i64>(
        "SELECT COALESCE(MAX(position) + 1, 0) FROM media_files \
         WHERE yarn_id = ? AND kind = 'photo'",
    )
    .bind(yarn_id)
    .fetch_one(&mut **tx)
    .await
}

async fn shift_yarn_photo_positions(
    tx: &mut Transaction<'_, Sqlite>,
    yarn_id: i64,
) -> Result<(), sqlx::Error> {
    let (count, max_position) = sqlx::query_as::<_, (i64, Option<i64>)>(
        "SELECT COUNT(*),MAX(position) FROM media_files WHERE yarn_id = ? AND kind = 'photo'",
    )
    .bind(yarn_id)
    .fetch_one(&mut **tx)
    .await?;
    if count == 0 {
        return Ok(());
    }
    let offset = max_position
        .unwrap_or(0)
        .checked_add(count)
        .and_then(|value| value.checked_add(1))
        .ok_or_else(|| sqlx::Error::Protocol("photo positions exhausted".to_owned()))?;
    sqlx::query(
        "UPDATE media_files SET position = position + ? WHERE yarn_id = ? AND kind = 'photo'",
    )
    .bind(offset)
    .bind(yarn_id)
    .execute(&mut **tx)
    .await?;
    let rows = sqlx::query_as::<_, (i64, i64)>(
        "SELECT id,position FROM media_files WHERE yarn_id = ? AND kind = 'photo' ORDER BY position,id",
    )
    .bind(yarn_id)
    .fetch_all(&mut **tx)
    .await?;
    for (position, (id, _)) in rows.into_iter().enumerate() {
        sqlx::query("UPDATE media_files SET position = ? WHERE id = ?")
            .bind(position as i64 + 1)
            .bind(id)
            .execute(&mut **tx)
            .await?;
    }
    Ok(())
}

async fn project_has_cover(
    tx: &mut Transaction<'_, Sqlite>,
    project_id: i64,
) -> Result<bool, sqlx::Error> {
    sqlx::query_scalar::<_, bool>(
        "SELECT EXISTS(SELECT 1 FROM media_files \
         WHERE project_id = ? AND kind IN ('title', 'step') AND is_project_cover = 1)",
    )
    .bind(project_id)
    .fetch_one(&mut **tx)
    .await
}

async fn project_has_media(
    tx: &mut Transaction<'_, Sqlite>,
    project_id: i64,
) -> Result<bool, sqlx::Error> {
    sqlx::query_scalar::<_, bool>(
        "SELECT EXISTS(SELECT 1 FROM media_files \
         WHERE project_id = ? AND kind IN ('title', 'step'))",
    )
    .bind(project_id)
    .fetch_one(&mut **tx)
    .await
}

async fn registered_media(
    pool: &SqlitePool,
    subdir: &str,
    parent_id: i64,
    requested_name: &str,
    thumbnail: bool,
) -> Result<Option<MediaRow>, sqlx::Error> {
    let rows = if subdir == "projects" {
        sqlx::query_as::<_, MediaRow>("SELECT * FROM media_files WHERE project_id = ?")
            .bind(parent_id)
            .fetch_all(pool)
            .await?
    } else {
        sqlx::query_as::<_, MediaRow>("SELECT * FROM media_files WHERE yarn_id = ?")
            .bind(parent_id)
            .fetch_all(pool)
            .await?
    };
    if let Some(row) = rows.into_iter().find(|row| {
        if thumbnail {
            thumbnail_name(&row.filename) == requested_name
        } else {
            row.filename == requested_name
        }
    }) {
        return Ok(Some(row));
    }

    let alias_column = if thumbnail {
        "legacy_thumbnail"
    } else {
        "legacy_filename"
    };
    let query = format!(
        "SELECT media_files.* FROM media_url_aliases \
         JOIN media_files ON media_files.id = media_url_aliases.media_id \
         WHERE media_url_aliases.subdir = ? AND media_url_aliases.parent_id = ? \
         AND ((media_url_aliases.subdir = 'projects' \
                 AND media_files.project_id = media_url_aliases.parent_id) \
             OR (media_url_aliases.subdir = 'yarns' \
                 AND media_files.yarn_id = media_url_aliases.parent_id)) \
         AND media_url_aliases.{alias_column} = ?"
    );
    sqlx::query_as::<_, MediaRow>(&query)
        .bind(subdir)
        .bind(parent_id)
        .bind(requested_name)
        .fetch_optional(pool)
        .await
}

/// Read one canonical image only when both its media row and parent belong to the active owner.
pub(crate) async fn read_owned_ocr_image(
    state: &AppState,
    owner_id: i64,
    parent: OcrMediaParent,
    filename: &str,
) -> Result<OcrImage, OcrMediaError> {
    if owner_id <= 0 || !safe_filename(filename) {
        return Err(OcrMediaError::NotFound);
    }
    let row = match parent {
        OcrMediaParent::Project(project_id) if project_id > 0 => {
            sqlx::query_as::<_, MediaRow>(
                "SELECT media_files.* FROM media_files \
                 JOIN projects ON projects.id = media_files.project_id \
                   AND projects.owner_id = media_files.owner_id \
                 JOIN users ON users.id = media_files.owner_id AND users.active = 1 \
                 WHERE media_files.owner_id = ? AND media_files.project_id = ? \
                   AND media_files.filename = ? AND media_files.kind IN ('title', 'step')",
            )
            .bind(owner_id)
            .bind(project_id)
            .bind(filename)
            .fetch_optional(&state.pool)
            .await
        }
        OcrMediaParent::Yarn(yarn_id) if yarn_id > 0 => {
            sqlx::query_as::<_, MediaRow>(
                "SELECT media_files.* FROM media_files \
                 JOIN yarns ON yarns.id = media_files.yarn_id \
                   AND yarns.owner_id = media_files.owner_id \
                 JOIN users ON users.id = media_files.owner_id AND users.active = 1 \
                 WHERE media_files.owner_id = ? AND media_files.yarn_id = ? \
                   AND media_files.filename = ? AND media_files.kind = 'photo'",
            )
            .bind(owner_id)
            .bind(yarn_id)
            .bind(filename)
            .fetch_optional(&state.pool)
            .await
        }
        _ => return Err(OcrMediaError::NotFound),
    }
    .map_err(|_| OcrMediaError::Database)?
    .ok_or(OcrMediaError::NotFound)?;

    let declared_size = usize::try_from(row.size_bytes).map_err(|_| OcrMediaError::TooLarge)?;
    if declared_size > crate::ocr::MAX_OCR_BYTES {
        return Err(OcrMediaError::TooLarge);
    }
    let content_type = safe_mime_type(Some(&row.content_type))
        .filter(|value| value.starts_with("image/"))
        .ok_or(OcrMediaError::InvalidImage)?;
    let (subdir, parent_id) = match parent {
        OcrMediaParent::Project(project_id) => ("projects", project_id),
        OcrMediaParent::Yarn(yarn_id) => ("yarns", yarn_id),
    };
    let root = media_root(state).map_err(|_| OcrMediaError::Unavailable)?;
    let path = existing_file_path(&root, subdir, parent_id, &row.filename)
        .await
        .map_err(|_| OcrMediaError::NotFound)?
        .ok_or(OcrMediaError::NotFound)?;
    let file = tokio::fs::File::open(path)
        .await
        .map_err(|_| OcrMediaError::NotFound)?;
    let mut bytes = Vec::with_capacity(declared_size);
    file.take((crate::ocr::MAX_OCR_BYTES + 1) as u64)
        .read_to_end(&mut bytes)
        .await
        .map_err(|_| OcrMediaError::Database)?;
    if bytes.len() > crate::ocr::MAX_OCR_BYTES {
        return Err(OcrMediaError::TooLarge);
    }
    Ok(OcrImage {
        bytes,
        content_type,
    })
}

fn upload_error(error: UploadError) -> Response {
    match error {
        UploadError::TooLarge => {
            error_response(StatusCode::PAYLOAD_TOO_LARGE, "Uploaded file is too large")
        }
        UploadError::InvalidImage => error_response(
            StatusCode::BAD_REQUEST,
            "Uploaded file is not a supported image",
        ),
        UploadError::BadMultipart => {
            error_response(StatusCode::BAD_REQUEST, "Invalid multipart upload")
        }
    }
}

fn legacy_media_error(error: MediaOperationError) -> Response {
    match error {
        MediaOperationError::NotFound(detail) => not_found(detail),
        MediaOperationError::ServiceUnavailable => StatusCode::SERVICE_UNAVAILABLE.into_response(),
        MediaOperationError::Upload(error) => upload_error(error),
        MediaOperationError::Database => database_error(),
    }
}

fn not_found(detail: &str) -> Response {
    error_response(StatusCode::NOT_FOUND, detail)
}

fn database_error() -> Response {
    error_response(StatusCode::INTERNAL_SERVER_ERROR, "database error")
}

fn error_response(status: StatusCode, detail: &str) -> Response {
    (
        status,
        Json(ErrorResponse {
            detail: detail.to_owned(),
        }),
    )
        .into_response()
}

fn media_response(
    filename: &str,
    content_type: &str,
    attachment: bool,
    bytes: Vec<u8>,
) -> Response {
    let disposition = if attachment { "attachment" } else { "inline" };
    let mut response = Response::new(Body::from(bytes));
    *response.status_mut() = StatusCode::OK;
    let headers = response.headers_mut();
    if let Ok(value) = HeaderValue::from_str(content_type) {
        headers.insert(header::CONTENT_TYPE, value);
    }
    if let Ok(value) = HeaderValue::from_str(&format!("{disposition}; filename=\"{filename}\"")) {
        headers.insert(header::CONTENT_DISPOSITION, value);
    }
    headers.insert(
        HeaderName::from_static("x-content-type-options"),
        HeaderValue::from_static("nosniff"),
    );
    headers.insert(
        header::CACHE_CONTROL,
        HeaderValue::from_static("private, no-store"),
    );
    response
}

#[derive(Debug)]
pub(crate) enum UploadError {
    TooLarge,
    InvalidImage,
    BadMultipart,
}

/// Typed failures returned by shared media mutations before HTTP adapter mapping.
#[derive(Debug)]
pub(crate) enum MediaOperationError {
    NotFound(&'static str),
    ServiceUnavailable,
    Upload(UploadError),
    Database,
}

#[derive(Debug)]
pub(crate) enum MediaOrderError {
    NotFound,
    Invalid,
    Database,
}

#[derive(Debug)]
pub(crate) struct MediaOrderResult {
    pub(crate) ids: Vec<i64>,
    pub(crate) updated_at: String,
}

#[derive(Clone, Copy)]
enum MediaOrderGroup {
    ProjectTitles(i64),
    ProjectSteps { project_id: i64, step_id: i64 },
    YarnPhotos(i64),
}

type OrderedMediaRow = (i64, i64, i64, bool);

pub(crate) async fn reorder_project_titles(
    state: &AppState,
    user_id: i64,
    project_id: i64,
    ids: Vec<i64>,
) -> Result<MediaOrderResult, MediaOrderError> {
    reorder_media(
        state,
        user_id,
        MediaOrderGroup::ProjectTitles(project_id),
        ids,
    )
    .await
}

pub(crate) async fn reorder_project_step_images(
    state: &AppState,
    user_id: i64,
    project_id: i64,
    step_id: i64,
    ids: Vec<i64>,
) -> Result<MediaOrderResult, MediaOrderError> {
    reorder_media(
        state,
        user_id,
        MediaOrderGroup::ProjectSteps {
            project_id,
            step_id,
        },
        ids,
    )
    .await
}

pub(crate) async fn reorder_yarn_photos(
    state: &AppState,
    user_id: i64,
    yarn_id: i64,
    ids: Vec<i64>,
) -> Result<MediaOrderResult, MediaOrderError> {
    reorder_media(state, user_id, MediaOrderGroup::YarnPhotos(yarn_id), ids).await
}

async fn reorder_media(
    state: &AppState,
    user_id: i64,
    group: MediaOrderGroup,
    ids: Vec<i64>,
) -> Result<MediaOrderResult, MediaOrderError> {
    if ids.len() > MAX_ORDERED_MEDIA_COUNT {
        return Err(MediaOrderError::Invalid);
    }
    let mut tx = state
        .pool
        .begin()
        .await
        .map_err(|_| MediaOrderError::Database)?;
    lock_media_parent(&mut tx, user_id, group).await?;
    if let MediaOrderGroup::ProjectSteps {
        project_id,
        step_id,
    } = group
    {
        let belongs = sqlx::query_scalar::<_, bool>(
            "SELECT EXISTS(SELECT 1 FROM project_steps WHERE id = ? AND project_id = ?)",
        )
        .bind(step_id)
        .bind(project_id)
        .fetch_one(&mut *tx)
        .await
        .map_err(|_| MediaOrderError::Database)?;
        if !belongs {
            let _ = tx.rollback().await;
            return Err(MediaOrderError::NotFound);
        }
    }
    let rows = load_ordered_group(&mut tx, group).await?;
    let current_ids: Vec<_> = rows.iter().map(|(_, public_id, _, _)| *public_id).collect();
    let current_id_set: HashSet<_> = current_ids.iter().copied().collect();
    let requested_set: HashSet<_> = ids.iter().copied().collect();
    if requested_set.len() != ids.len() || requested_set != current_id_set {
        let _ = tx.rollback().await;
        return Err(MediaOrderError::Invalid);
    }
    let first_internal_id = ids.first().and_then(|first| {
        rows.iter()
            .find(|(_, public_id, _, _)| public_id == first)
            .map(|(id, _, _, _)| *id)
    });
    let current_primary = rows
        .iter()
        .find(|(_, _, _, is_primary)| *is_primary)
        .map(|(id, _, _, _)| *id);
    let primary_changes = match group {
        MediaOrderGroup::ProjectTitles(_) | MediaOrderGroup::YarnPhotos(_) => {
            first_internal_id != current_primary
        }
        MediaOrderGroup::ProjectSteps { .. } => false,
    };
    let changed = current_ids != ids || primary_changes;
    let updated_at = if changed {
        rewrite_group_order(&mut tx, group, &rows, &ids).await?;
        if matches!(
            group,
            MediaOrderGroup::ProjectTitles(_) | MediaOrderGroup::YarnPhotos(_)
        ) {
            set_group_primary(&mut tx, group, first_internal_id).await?;
        }
        let updated_at = match group {
            MediaOrderGroup::ProjectTitles(project_id)
            | MediaOrderGroup::ProjectSteps { project_id, .. } => {
                bump_project_timestamp(&mut tx, user_id, project_id).await
            }
            MediaOrderGroup::YarnPhotos(yarn_id) => {
                bump_yarn_timestamp(&mut tx, user_id, yarn_id).await
            }
        }
        .map_err(|_| MediaOrderError::Database)?;
        if record_order_change(&mut tx, user_id, group).await.is_err() {
            let _ = tx.rollback().await;
            return Err(MediaOrderError::Database);
        }
        updated_at
    } else {
        current_parent_timestamp(&mut tx, user_id, group).await?
    };
    tx.commit().await.map_err(|_| MediaOrderError::Database)?;
    Ok(MediaOrderResult { ids, updated_at })
}

async fn lock_media_parent(
    tx: &mut Transaction<'_, Sqlite>,
    user_id: i64,
    group: MediaOrderGroup,
) -> Result<(), MediaOrderError> {
    let result = match group {
        MediaOrderGroup::ProjectTitles(project_id)
        | MediaOrderGroup::ProjectSteps { project_id, .. } => {
            sqlx::query("UPDATE projects SET updated_at = updated_at WHERE id = ? AND owner_id = ?")
                .bind(project_id)
                .bind(user_id)
                .execute(&mut **tx)
                .await
        }
        MediaOrderGroup::YarnPhotos(yarn_id) => {
            sqlx::query("UPDATE yarns SET updated_at = updated_at WHERE id = ? AND owner_id = ?")
                .bind(yarn_id)
                .bind(user_id)
                .execute(&mut **tx)
                .await
        }
    }
    .map_err(|_| MediaOrderError::Database)?;
    if result.rows_affected() == 0 {
        return Err(MediaOrderError::NotFound);
    }
    Ok(())
}

async fn load_ordered_group(
    tx: &mut Transaction<'_, Sqlite>,
    group: MediaOrderGroup,
) -> Result<Vec<OrderedMediaRow>, MediaOrderError> {
    let query = match group {
        MediaOrderGroup::ProjectTitles(project_id) => sqlx::query_as::<_, OrderedMediaRow>(
            "SELECT id,public_id,position,is_project_cover FROM media_files \
             WHERE project_id = ? AND kind = 'title' AND step_id IS NULL \
             ORDER BY position,id",
        )
        .bind(project_id)
        .fetch_all(&mut **tx),
        MediaOrderGroup::ProjectSteps {
            project_id,
            step_id,
        } => sqlx::query_as::<_, OrderedMediaRow>(
            "SELECT id,public_id,position,is_project_cover FROM media_files \
                 WHERE project_id = ? AND kind = 'step' AND step_id = ? \
                 ORDER BY position,id",
        )
        .bind(project_id)
        .bind(step_id)
        .fetch_all(&mut **tx),
        MediaOrderGroup::YarnPhotos(yarn_id) => sqlx::query_as::<_, OrderedMediaRow>(
            "SELECT id,public_id,position,is_primary FROM media_files \
             WHERE yarn_id = ? AND kind = 'photo' ORDER BY position,id",
        )
        .bind(yarn_id)
        .fetch_all(&mut **tx),
    };
    query.await.map_err(|_| MediaOrderError::Database)
}

async fn rewrite_group_order(
    tx: &mut Transaction<'_, Sqlite>,
    group: MediaOrderGroup,
    rows: &[OrderedMediaRow],
    ids: &[i64],
) -> Result<(), MediaOrderError> {
    if rows.is_empty() {
        return Ok(());
    }
    let max_position = rows
        .iter()
        .map(|(_, _, position, _)| *position)
        .max()
        .unwrap_or(0);
    let offset = max_position
        .checked_add(rows.len() as i64)
        .and_then(|value| value.checked_add(1))
        .ok_or(MediaOrderError::Database)?;
    match group {
        MediaOrderGroup::ProjectTitles(project_id) => {
            sqlx::query("UPDATE media_files SET position = position + ? WHERE project_id = ? AND kind = 'title' AND step_id IS NULL")
                .bind(offset)
                .bind(project_id)
                .execute(&mut **tx)
                .await
        }
        MediaOrderGroup::ProjectSteps { project_id, step_id } => {
            sqlx::query("UPDATE media_files SET position = position + ? WHERE project_id = ? AND kind = 'step' AND step_id = ?")
                .bind(offset)
                .bind(project_id)
                .bind(step_id)
                .execute(&mut **tx)
                .await
        }
        MediaOrderGroup::YarnPhotos(yarn_id) => {
            sqlx::query("UPDATE media_files SET position = position + ? WHERE yarn_id = ? AND kind = 'photo'")
                .bind(offset)
                .bind(yarn_id)
                .execute(&mut **tx)
                .await
        }
    }
    .map_err(|_| MediaOrderError::Database)?;
    for (position, public_id) in ids.iter().enumerate() {
        let internal_id = rows
            .iter()
            .find(|(_, existing_public_id, _, _)| existing_public_id == public_id)
            .map(|(id, _, _, _)| *id)
            .ok_or(MediaOrderError::Invalid)?;
        sqlx::query("UPDATE media_files SET position = ? WHERE id = ?")
            .bind(position as i64)
            .bind(internal_id)
            .execute(&mut **tx)
            .await
            .map_err(|_| MediaOrderError::Database)?;
    }
    Ok(())
}

async fn set_group_primary(
    tx: &mut Transaction<'_, Sqlite>,
    group: MediaOrderGroup,
    id: Option<i64>,
) -> Result<(), MediaOrderError> {
    if matches!(group, MediaOrderGroup::ProjectTitles(_)) && id.is_none() {
        let has_step_cover = sqlx::query_scalar::<_, bool>(
            "SELECT EXISTS(SELECT 1 FROM media_files WHERE project_id = ? \
             AND kind = 'step' AND is_project_cover = 1)",
        )
        .bind(match group {
            MediaOrderGroup::ProjectTitles(project_id) => project_id,
            _ => unreachable!(),
        })
        .fetch_one(&mut **tx)
        .await
        .map_err(|_| MediaOrderError::Database)?;
        if has_step_cover {
            return Ok(());
        }
    }
    let cleared = match group {
        MediaOrderGroup::ProjectTitles(project_id) => {
            sqlx::query("UPDATE media_files SET is_project_cover = 0 WHERE project_id = ? AND kind IN ('title','step')")
                .bind(project_id)
                .execute(&mut **tx)
                .await
        }
        MediaOrderGroup::ProjectSteps { .. } => return Ok(()),
        MediaOrderGroup::YarnPhotos(yarn_id) => {
            sqlx::query("UPDATE media_files SET is_primary = 0 WHERE yarn_id = ? AND kind = 'photo'")
                .bind(yarn_id)
                .execute(&mut **tx)
                .await
        }
    }
    .map_err(|_| MediaOrderError::Database)?;
    let _ = cleared;
    if let Some(id) = id {
        let query = match group {
            MediaOrderGroup::ProjectTitles(_) => {
                "UPDATE media_files SET is_project_cover = 1 WHERE id = ?"
            }
            MediaOrderGroup::YarnPhotos(_) => "UPDATE media_files SET is_primary = 1 WHERE id = ?",
            MediaOrderGroup::ProjectSteps { .. } => return Ok(()),
        };
        sqlx::query(query)
            .bind(id)
            .execute(&mut **tx)
            .await
            .map_err(|_| MediaOrderError::Database)?;
    }
    Ok(())
}

async fn current_parent_timestamp(
    tx: &mut Transaction<'_, Sqlite>,
    user_id: i64,
    group: MediaOrderGroup,
) -> Result<String, MediaOrderError> {
    match group {
        MediaOrderGroup::ProjectTitles(project_id)
        | MediaOrderGroup::ProjectSteps { project_id, .. } => {
            sqlx::query_scalar("SELECT updated_at FROM projects WHERE id = ? AND owner_id = ?")
                .bind(project_id)
                .bind(user_id)
                .fetch_one(&mut **tx)
                .await
        }
        MediaOrderGroup::YarnPhotos(yarn_id) => {
            sqlx::query_scalar("SELECT updated_at FROM yarns WHERE id = ? AND owner_id = ?")
                .bind(yarn_id)
                .bind(user_id)
                .fetch_one(&mut **tx)
                .await
        }
    }
    .map_err(|_| MediaOrderError::Database)
}

async fn record_order_change(
    tx: &mut Transaction<'_, Sqlite>,
    user_id: i64,
    group: MediaOrderGroup,
) -> Result<(), sqlx::Error> {
    match group {
        MediaOrderGroup::ProjectTitles(project_id)
        | MediaOrderGroup::ProjectSteps { project_id, .. } => {
            crate::audit::record(
                tx,
                user_id,
                AuditEntity::Project,
                project_id,
                AuditAction::Updated,
            )
            .await
        }
        MediaOrderGroup::YarnPhotos(yarn_id) => {
            crate::audit::record(
                tx,
                user_id,
                AuditEntity::Yarn,
                yarn_id,
                AuditAction::Updated,
            )
            .await
        }
    }
}

async fn compact_group(
    tx: &mut Transaction<'_, Sqlite>,
    group: MediaOrderGroup,
) -> Result<Vec<OrderedMediaRow>, MediaOrderError> {
    let rows = load_ordered_group(tx, group).await?;
    let ids: Vec<_> = rows.iter().map(|(_, public_id, _, _)| *public_id).collect();
    rewrite_group_order(tx, group, &rows, &ids).await?;
    Ok(rows)
}

async fn select_project_cover_after_delete(
    tx: &mut Transaction<'_, Sqlite>,
    project_id: i64,
) -> Result<Option<i64>, sqlx::Error> {
    sqlx::query_scalar::<_, i64>(
        "SELECT id FROM media_files WHERE project_id = ? AND kind IN ('title','step') \
         ORDER BY CASE kind WHEN 'title' THEN 0 ELSE 1 END, position, id LIMIT 1",
    )
    .bind(project_id)
    .fetch_optional(&mut **tx)
    .await
}

async fn restore_project_cover_after_delete(
    tx: &mut Transaction<'_, Sqlite>,
    project_id: i64,
) -> Result<(), sqlx::Error> {
    sqlx::query(
        "UPDATE media_files SET is_project_cover = 0 WHERE project_id = ? AND kind IN ('title','step')",
    )
    .bind(project_id)
    .execute(&mut **tx)
    .await?;
    if let Some(id) = select_project_cover_after_delete(tx, project_id).await? {
        sqlx::query("UPDATE media_files SET is_project_cover = 1 WHERE id = ?")
            .bind(id)
            .execute(&mut **tx)
            .await?;
    }
    Ok(())
}

async fn upload_title_image(
    State(state): State<AppState>,
    headers: HeaderMap,
    AxumPath(project_id): AxumPath<i64>,
    multipart: Multipart,
) -> Response {
    let user_id = match authenticate(&state, &headers).await {
        Ok(id) => id,
        Err(error) => return error.into_response(),
    };
    match upload_project_image(&state, user_id, project_id, None, "title", multipart).await {
        Ok(result) => json_response(StatusCode::CREATED, result),
        Err(error) => legacy_media_error(error),
    }
}

async fn upload_step_image(
    State(state): State<AppState>,
    headers: HeaderMap,
    AxumPath((project_id, step_id)): AxumPath<(i64, i64)>,
    multipart: Multipart,
) -> Response {
    let user_id = match authenticate(&state, &headers).await {
        Ok(id) => id,
        Err(error) => return error.into_response(),
    };
    match upload_project_image(
        &state,
        user_id,
        project_id,
        Some(step_id),
        "step",
        multipart,
    )
    .await
    {
        Ok(result) => json_response(StatusCode::CREATED, result),
        Err(error) => legacy_media_error(error),
    }
}

pub(crate) async fn upload_project_image(
    state: &AppState,
    user_id: i64,
    project_id: i64,
    step_id: Option<i64>,
    kind: &'static str,
    multipart: Multipart,
) -> Result<ImageResponse, MediaOperationError> {
    match step_id {
        Some(step_id) => {
            match step_belongs_to_project(&state.pool, user_id, project_id, step_id).await {
                Ok(true) => {}
                Ok(false) => return Err(MediaOperationError::NotFound("Step not found")),
                Err(_) => return Err(MediaOperationError::Database),
            }
        }
        None => match project_exists(&state.pool, user_id, project_id).await {
            Ok(true) => {}
            Ok(false) => return Err(MediaOperationError::NotFound("Project not found")),
            Err(_) => return Err(MediaOperationError::Database),
        },
    }
    let root = match media_root(state) {
        Ok(root) => root,
        Err(_) => return Err(MediaOperationError::ServiceUnavailable),
    };
    let upload = match read_upload(multipart, true).await {
        Ok(upload) => upload,
        Err(error) => return Err(MediaOperationError::Upload(error)),
    };
    let prepared = match process_image(upload.bytes).await {
        Ok(image) => image,
        Err(error) => return Err(MediaOperationError::Upload(error)),
    };
    let alt_text = if upload.alt_text.trim().is_empty() {
        upload.original_filename.clone()
    } else {
        upload.alt_text.trim().to_owned()
    };
    let files = match save_image_files(&root, "projects", project_id, &prepared).await {
        Ok(files) => files,
        Err(_) => return Err(MediaOperationError::Database),
    };

    let mut tx = match state.pool.begin().await {
        Ok(tx) => tx,
        Err(_) => {
            cleanup_saved_files(&root, "projects", project_id, &files).await;
            return Err(MediaOperationError::Database);
        }
    };
    match verify_image_target(&mut tx, user_id, project_id, step_id).await {
        Ok(true) => {}
        Ok(false) => {
            let _ = tx.rollback().await;
            cleanup_saved_files(&root, "projects", project_id, &files).await;
            return Err(MediaOperationError::NotFound(if step_id.is_some() {
                "Step not found"
            } else {
                "Project not found"
            }));
        }
        Err(_) => {
            let _ = tx.rollback().await;
            cleanup_saved_files(&root, "projects", project_id, &files).await;
            return Err(MediaOperationError::Database);
        }
    }
    if bump_project_timestamp(&mut tx, user_id, project_id)
        .await
        .is_err()
    {
        let _ = tx.rollback().await;
        cleanup_saved_files(&root, "projects", project_id, &files).await;
        return Err(MediaOperationError::Database);
    }
    let is_primary = if kind == "title" {
        match sqlx::query_scalar::<_, i64>(
            "SELECT EXISTS(SELECT 1 FROM media_files \
             WHERE project_id = ? AND step_id IS NULL \
               AND COALESCE(legacy_is_title_image, kind = 'title' AND is_primary) = 1 \
               AND COALESCE(is_stitch_sample, 0) = 0)",
        )
        .bind(project_id)
        .fetch_one(&mut *tx)
        .await
        {
            Ok(exists) => exists == 0,
            Err(_) => {
                let _ = tx.rollback().await;
                cleanup_saved_files(&root, "projects", project_id, &files).await;
                return Err(MediaOperationError::Database);
            }
        }
    } else {
        false
    };
    let is_project_cover = match project_has_cover(&mut tx, project_id).await {
        Ok(has_cover) => !has_cover,
        Err(_) => {
            let _ = tx.rollback().await;
            cleanup_saved_files(&root, "projects", project_id, &files).await;
            return Err(MediaOperationError::Database);
        }
    };
    let position = match next_project_position(&mut tx, project_id, step_id).await {
        Ok(position) => position,
        Err(_) => {
            let _ = tx.rollback().await;
            cleanup_saved_files(&root, "projects", project_id, &files).await;
            return Err(MediaOperationError::Database);
        }
    };
    let public_id = match media_ids::allocate(&mut tx, Namespace::Image).await {
        Ok(public_id) => public_id,
        Err(_) => {
            let _ = tx.rollback().await;
            cleanup_saved_files(&root, "projects", project_id, &files).await;
            return Err(MediaOperationError::Database);
        }
    };
    let insert = sqlx::query(
        "INSERT INTO media_files \
         (owner_id, project_id, step_id, kind, public_id, filename, original_filename, content_type, \
          size_bytes, width, height, alt_text, is_primary, position, is_project_cover, created_at) \
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
    )
    .bind(user_id)
    .bind(project_id)
    .bind(step_id)
    .bind(kind)
    .bind(public_id)
    .bind(&files.filename)
    .bind(&upload.original_filename)
    .bind(prepared.content_type)
    .bind(prepared.bytes.len() as i64)
    .bind(prepared.width as i64)
    .bind(prepared.height as i64)
    .bind(&alt_text)
    .bind(is_primary)
    .bind(position)
    .bind(is_project_cover)
    .bind(Utc::now().to_rfc3339_opts(SecondsFormat::Micros, true))
    .execute(&mut *tx)
    .await;
    let media_id = match insert {
        Ok(result) => result.last_insert_rowid(),
        Err(_) => {
            let _ = tx.rollback().await;
            cleanup_saved_files(&root, "projects", project_id, &files).await;
            return Err(MediaOperationError::Database);
        }
    };
    if crate::audit::record(
        &mut tx,
        user_id,
        AuditEntity::Project,
        project_id,
        AuditAction::Uploaded,
    )
    .await
    .is_err()
    {
        let _ = tx.rollback().await;
        cleanup_saved_files(&root, "projects", project_id, &files).await;
        return Err(MediaOperationError::Database);
    }
    if tx.commit().await.is_err() {
        cleanup_saved_files(&root, "projects", project_id, &files).await;
        return Err(MediaOperationError::Database);
    }
    let row = MediaRow {
        id: media_id,
        public_id,
        owner_id: user_id,
        project_id: Some(project_id),
        yarn_id: None,
        step_id,
        kind: kind.to_owned(),
        filename: files.filename,
        original_filename: upload.original_filename,
        content_type: prepared.content_type.to_owned(),
        size_bytes: prepared.bytes.len() as i64,
        alt_text,
        is_primary,
        position,
        is_project_cover,
        is_safe_inline: true,
        legacy_image_type: None,
        is_stitch_sample: None,
        legacy_is_title_image: None,
    };
    Ok(image_dto(&row))
}

/// Decode imported content with the bounded uploader decoder and import's minimum dimensions.
pub(crate) async fn prepare_imported_image(bytes: Vec<u8>) -> Result<PreparedImage, ()> {
    let image = process_image(bytes).await.map_err(|_| ())?;
    if image.width < 64 || image.height < 64 {
        return Err(());
    }
    Ok(image)
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum ProjectImageStoreError {
    NotFound,
    Database,
}

pub(crate) struct ImportedImageMetadata<'a> {
    pub original_filename: &'a str,
    pub alt_text: &'a str,
    pub is_primary: bool,
}

/// Persist an imported image through the upload storage registry and owner checks.
pub(crate) async fn persist_imported_project_image(
    state: &AppState,
    user_id: i64,
    project_id: i64,
    step_id: Option<i64>,
    metadata: ImportedImageMetadata<'_>,
    prepared: &PreparedImage,
) -> Result<(), ProjectImageStoreError> {
    let root = media_root(state).map_err(|_| ProjectImageStoreError::Database)?;
    let files = save_image_files(&root, "projects", project_id, prepared)
        .await
        .map_err(|_| ProjectImageStoreError::Database)?;
    let mut tx = match state.pool.begin().await {
        Ok(tx) => tx,
        Err(_) => {
            cleanup_saved_files(&root, "projects", project_id, &files).await;
            return Err(ProjectImageStoreError::Database);
        }
    };
    match verify_image_target(&mut tx, user_id, project_id, step_id).await {
        Ok(true) => {}
        Ok(false) => {
            let _ = tx.rollback().await;
            cleanup_saved_files(&root, "projects", project_id, &files).await;
            return Err(ProjectImageStoreError::NotFound);
        }
        Err(_) => {
            let _ = tx.rollback().await;
            cleanup_saved_files(&root, "projects", project_id, &files).await;
            return Err(ProjectImageStoreError::Database);
        }
    }
    let kind = if step_id.is_some() { "step" } else { "title" };
    if bump_project_timestamp(&mut tx, user_id, project_id)
        .await
        .is_err()
    {
        let _ = tx.rollback().await;
        cleanup_saved_files(&root, "projects", project_id, &files).await;
        return Err(ProjectImageStoreError::Database);
    }
    let has_project_cover = match project_has_cover(&mut tx, project_id).await {
        Ok(has_cover) => has_cover,
        Err(_) => {
            let _ = tx.rollback().await;
            cleanup_saved_files(&root, "projects", project_id, &files).await;
            return Err(ProjectImageStoreError::Database);
        }
    };
    let has_media = match project_has_media(&mut tx, project_id).await {
        Ok(has_media) => has_media,
        Err(_) => {
            let _ = tx.rollback().await;
            cleanup_saved_files(&root, "projects", project_id, &files).await;
            return Err(ProjectImageStoreError::Database);
        }
    };
    let is_primary = metadata.is_primary;
    let is_project_cover = !has_project_cover && (metadata.is_primary || !has_media);
    let position = match next_project_position(&mut tx, project_id, step_id).await {
        Ok(position) => position,
        Err(_) => {
            let _ = tx.rollback().await;
            cleanup_saved_files(&root, "projects", project_id, &files).await;
            return Err(ProjectImageStoreError::Database);
        }
    };
    let public_id = match media_ids::allocate(&mut tx, Namespace::Image).await {
        Ok(public_id) => public_id,
        Err(_) => {
            let _ = tx.rollback().await;
            cleanup_saved_files(&root, "projects", project_id, &files).await;
            return Err(ProjectImageStoreError::Database);
        }
    };
    let inserted = sqlx::query(
        "INSERT INTO media_files \
         (owner_id, project_id, step_id, kind, public_id, filename, original_filename, content_type, \
          size_bytes, width, height, alt_text, is_primary, position, is_project_cover, created_at) \
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
    )
    .bind(user_id)
    .bind(project_id)
    .bind(step_id)
    .bind(kind)
    .bind(public_id)
    .bind(&files.filename)
    .bind(metadata.original_filename)
    .bind(prepared.content_type)
    .bind(prepared.bytes.len() as i64)
    .bind(prepared.width as i64)
    .bind(prepared.height as i64)
    .bind(metadata.alt_text)
    .bind(is_primary)
    .bind(position)
    .bind(is_project_cover)
    .bind(Utc::now().to_rfc3339_opts(SecondsFormat::Micros, true))
    .execute(&mut *tx)
    .await;
    if inserted.is_err() {
        let _ = tx.rollback().await;
        cleanup_saved_files(&root, "projects", project_id, &files).await;
        return Err(ProjectImageStoreError::Database);
    }
    if tx.commit().await.is_err() {
        cleanup_saved_files(&root, "projects", project_id, &files).await;
        return Err(ProjectImageStoreError::Database);
    }
    Ok(())
}

/// Persist an imported image as a yarn photo, after the yarn was created.
pub(crate) async fn persist_imported_yarn_photo(
    state: &AppState,
    user_id: i64,
    yarn_id: i64,
    yarn_label: &str,
    original_filename: &str,
    prepared: &PreparedImage,
) -> Result<(), ProjectImageStoreError> {
    let root = media_root(state).map_err(|_| ProjectImageStoreError::Database)?;
    let files = save_image_files(&root, "yarns", yarn_id, prepared)
        .await
        .map_err(|_| ProjectImageStoreError::Database)?;
    let mut tx = match state.pool.begin().await {
        Ok(tx) => tx,
        Err(_) => {
            cleanup_saved_files(&root, "yarns", yarn_id, &files).await;
            return Err(ProjectImageStoreError::Database);
        }
    };
    let result = async {
        let owner = sqlx::query_scalar::<_, i64>("SELECT owner_id FROM yarns WHERE id = ?")
            .bind(yarn_id)
            .fetch_optional(&mut *tx)
            .await
            .map_err(|_| ProjectImageStoreError::Database)?;
        if owner != Some(user_id) {
            return Err(ProjectImageStoreError::NotFound);
        }
        bump_yarn_timestamp(&mut tx, user_id, yarn_id)
            .await
            .map_err(|_| ProjectImageStoreError::Database)?;
        let has_primary = sqlx::query_scalar::<_, bool>(
            "SELECT EXISTS(SELECT 1 FROM media_files WHERE yarn_id = ? AND kind = 'photo' \
             AND is_primary = 1)",
        )
        .bind(yarn_id)
        .fetch_one(&mut *tx)
        .await
        .map_err(|_| ProjectImageStoreError::Database)?;
        let is_primary = !has_primary;
        let position = if is_primary {
            shift_yarn_photo_positions(&mut tx, yarn_id)
                .await
                .map_err(|_| ProjectImageStoreError::Database)?;
            0
        } else {
            next_yarn_position(&mut tx, yarn_id)
                .await
                .map_err(|_| ProjectImageStoreError::Database)?
        };
        let public_id = media_ids::allocate(&mut tx, Namespace::Photo)
            .await
            .map_err(|_| ProjectImageStoreError::Database)?;
        sqlx::query(
            "INSERT INTO media_files \
             (owner_id, yarn_id, kind, public_id, filename, original_filename, content_type, \
              size_bytes, width, height, alt_text, is_primary, position, created_at) \
             VALUES (?, ?, 'photo', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        )
        .bind(user_id)
        .bind(yarn_id)
        .bind(public_id)
        .bind(&files.filename)
        .bind(original_filename)
        .bind(prepared.content_type)
        .bind(prepared.bytes.len() as i64)
        .bind(prepared.width as i64)
        .bind(prepared.height as i64)
        .bind(yarn_label)
        .bind(is_primary)
        .bind(position)
        .bind(Utc::now().to_rfc3339_opts(SecondsFormat::Micros, true))
        .execute(&mut *tx)
        .await
        .map_err(|_| ProjectImageStoreError::Database)?;
        Ok(())
    }
    .await;
    match result {
        Ok(()) => {
            if tx.commit().await.is_err() {
                cleanup_saved_files(&root, "yarns", yarn_id, &files).await;
                return Err(ProjectImageStoreError::Database);
            }
            Ok(())
        }
        Err(error) => {
            let _ = tx.rollback().await;
            cleanup_saved_files(&root, "yarns", yarn_id, &files).await;
            Err(error)
        }
    }
}

async fn upload_attachment(
    State(state): State<AppState>,
    headers: HeaderMap,
    AxumPath(project_id): AxumPath<i64>,
    multipart: Multipart,
) -> Response {
    let user_id = match authenticate(&state, &headers).await {
        Ok(id) => id,
        Err(error) => return error.into_response(),
    };
    match upload_project_attachment(&state, user_id, project_id, multipart).await {
        Ok(result) => json_response(StatusCode::CREATED, result),
        Err(error) => legacy_media_error(error),
    }
}

pub(crate) async fn upload_project_attachment(
    state: &AppState,
    user_id: i64,
    project_id: i64,
    multipart: Multipart,
) -> Result<AttachmentResponse, MediaOperationError> {
    match project_exists(&state.pool, user_id, project_id).await {
        Ok(true) => {}
        Ok(false) => return Err(MediaOperationError::NotFound("Project not found")),
        Err(_) => return Err(MediaOperationError::Database),
    }
    let root = match media_root(state) {
        Ok(root) => root,
        Err(_) => return Err(MediaOperationError::ServiceUnavailable),
    };
    let upload = match read_upload(multipart, false).await {
        Ok(upload) => upload,
        Err(error) => return Err(MediaOperationError::Upload(error)),
    };
    let claimed_type = safe_mime_type(upload.claimed_content_type.as_deref())
        .unwrap_or_else(|| "application/octet-stream".to_owned());
    let original_extension = safe_extension(&upload.original_filename);
    let (content_type, extension, is_safe_inline, dimensions) =
        classify_attachment(&upload.bytes, &claimed_type).await;
    let extension = extension.unwrap_or(original_extension);
    let filename = opaque_filename(&extension);
    let files = match save_files(
        &root,
        "projects",
        project_id,
        &filename,
        &upload.bytes,
        None,
    )
    .await
    {
        Ok(files) => files,
        Err(_) => return Err(MediaOperationError::Database),
    };
    let mut tx = match state.pool.begin().await {
        Ok(tx) => tx,
        Err(_) => {
            cleanup_saved_files(&root, "projects", project_id, &files).await;
            return Err(MediaOperationError::Database);
        }
    };
    match verify_project_target(&mut tx, user_id, project_id).await {
        Ok(true) => {}
        Ok(false) => {
            let _ = tx.rollback().await;
            cleanup_saved_files(&root, "projects", project_id, &files).await;
            return Err(MediaOperationError::NotFound("Project not found"));
        }
        Err(_) => {
            let _ = tx.rollback().await;
            cleanup_saved_files(&root, "projects", project_id, &files).await;
            return Err(MediaOperationError::Database);
        }
    }
    if bump_project_timestamp(&mut tx, user_id, project_id)
        .await
        .is_err()
    {
        let _ = tx.rollback().await;
        cleanup_saved_files(&root, "projects", project_id, &files).await;
        return Err(MediaOperationError::Database);
    }
    let public_id = match media_ids::allocate(&mut tx, Namespace::Attachment).await {
        Ok(public_id) => public_id,
        Err(_) => {
            let _ = tx.rollback().await;
            cleanup_saved_files(&root, "projects", project_id, &files).await;
            return Err(MediaOperationError::Database);
        }
    };
    let insert = sqlx::query(
        "INSERT INTO media_files \
         (owner_id, project_id, kind, public_id, filename, original_filename, content_type, size_bytes, \
          width, height, is_safe_inline, created_at) \
         VALUES (?, ?, 'attachment', ?, ?, ?, ?, ?, ?, ?, ?, ?)",
    )
    .bind(user_id)
    .bind(project_id)
    .bind(public_id)
    .bind(&files.filename)
    .bind(&upload.original_filename)
    .bind(&content_type)
    .bind(upload.bytes.len() as i64)
    .bind(dimensions.map(|(width, _)| width as i64))
    .bind(dimensions.map(|(_, height)| height as i64))
    .bind(is_safe_inline)
    .bind(Utc::now().to_rfc3339_opts(SecondsFormat::Micros, true))
    .execute(&mut *tx)
    .await;
    let media_id = match insert {
        Ok(result) => result.last_insert_rowid(),
        Err(_) => {
            let _ = tx.rollback().await;
            cleanup_saved_files(&root, "projects", project_id, &files).await;
            return Err(MediaOperationError::Database);
        }
    };
    if crate::audit::record(
        &mut tx,
        user_id,
        AuditEntity::Project,
        project_id,
        AuditAction::Uploaded,
    )
    .await
    .is_err()
    {
        let _ = tx.rollback().await;
        cleanup_saved_files(&root, "projects", project_id, &files).await;
        return Err(MediaOperationError::Database);
    }
    if tx.commit().await.is_err() {
        cleanup_saved_files(&root, "projects", project_id, &files).await;
        return Err(MediaOperationError::Database);
    }
    let row = MediaRow {
        id: media_id,
        public_id,
        owner_id: user_id,
        project_id: Some(project_id),
        yarn_id: None,
        step_id: None,
        kind: "attachment".to_owned(),
        filename: files.filename,
        original_filename: upload.original_filename,
        content_type: content_type.to_owned(),
        size_bytes: upload.bytes.len() as i64,
        alt_text: String::new(),
        is_primary: false,
        position: 0,
        is_project_cover: false,
        is_safe_inline,
        legacy_image_type: None,
        is_stitch_sample: None,
        legacy_is_title_image: None,
    };
    Ok(attachment_dto(&row))
}

async fn upload_yarn_photo(
    State(state): State<AppState>,
    headers: HeaderMap,
    AxumPath(yarn_id): AxumPath<i64>,
    multipart: Multipart,
) -> Response {
    let user_id = match authenticate(&state, &headers).await {
        Ok(id) => id,
        Err(error) => return error.into_response(),
    };
    match upload_yarn_photo_for_owner(&state, user_id, yarn_id, multipart).await {
        Ok(result) => json_response(StatusCode::CREATED, result),
        Err(error) => legacy_media_error(error),
    }
}

pub(crate) async fn upload_yarn_photo_for_owner(
    state: &AppState,
    user_id: i64,
    yarn_id: i64,
    multipart: Multipart,
) -> Result<YarnPhotoResponse, MediaOperationError> {
    let yarn_name = match yarn_name(&state.pool, user_id, yarn_id).await {
        Ok(Some(name)) => name,
        Ok(None) => return Err(MediaOperationError::NotFound("Yarn not found")),
        Err(_) => return Err(MediaOperationError::Database),
    };
    let root = match media_root(state) {
        Ok(root) => root,
        Err(_) => return Err(MediaOperationError::ServiceUnavailable),
    };
    let upload = match read_upload(multipart, false).await {
        Ok(upload) => upload,
        Err(error) => return Err(MediaOperationError::Upload(error)),
    };
    let prepared = match process_image(upload.bytes).await {
        Ok(image) => image,
        Err(error) => return Err(MediaOperationError::Upload(error)),
    };
    let files = match save_image_files(&root, "yarns", yarn_id, &prepared).await {
        Ok(files) => files,
        Err(_) => return Err(MediaOperationError::Database),
    };
    let mut tx = match state.pool.begin().await {
        Ok(tx) => tx,
        Err(_) => {
            cleanup_saved_files(&root, "yarns", yarn_id, &files).await;
            return Err(MediaOperationError::Database);
        }
    };
    let owner = sqlx::query_scalar::<_, i64>("SELECT owner_id FROM yarns WHERE id = ?")
        .bind(yarn_id)
        .fetch_optional(&mut *tx)
        .await;
    match owner {
        Ok(Some(owner_id)) if owner_id == user_id => {}
        Ok(_) => {
            let _ = tx.rollback().await;
            cleanup_saved_files(&root, "yarns", yarn_id, &files).await;
            return Err(MediaOperationError::NotFound("Yarn not found"));
        }
        Err(_) => {
            let _ = tx.rollback().await;
            cleanup_saved_files(&root, "yarns", yarn_id, &files).await;
            return Err(MediaOperationError::Database);
        }
    }
    if bump_yarn_timestamp(&mut tx, user_id, yarn_id)
        .await
        .is_err()
    {
        let _ = tx.rollback().await;
        cleanup_saved_files(&root, "yarns", yarn_id, &files).await;
        return Err(MediaOperationError::Database);
    }
    let is_primary = match sqlx::query_scalar::<_, bool>(
        "SELECT EXISTS(SELECT 1 FROM media_files WHERE yarn_id = ? AND kind = 'photo' \
         AND is_primary = 1)",
    )
    .bind(yarn_id)
    .fetch_one(&mut *tx)
    .await
    {
        Ok(has_primary) => !has_primary,
        Err(_) => {
            let _ = tx.rollback().await;
            cleanup_saved_files(&root, "yarns", yarn_id, &files).await;
            return Err(MediaOperationError::Database);
        }
    };
    let position = if is_primary {
        if shift_yarn_photo_positions(&mut tx, yarn_id).await.is_err() {
            let _ = tx.rollback().await;
            cleanup_saved_files(&root, "yarns", yarn_id, &files).await;
            return Err(MediaOperationError::Database);
        }
        0
    } else {
        match next_yarn_position(&mut tx, yarn_id).await {
            Ok(position) => position,
            Err(_) => {
                let _ = tx.rollback().await;
                cleanup_saved_files(&root, "yarns", yarn_id, &files).await;
                return Err(MediaOperationError::Database);
            }
        }
    };
    let public_id = match media_ids::allocate(&mut tx, Namespace::Photo).await {
        Ok(public_id) => public_id,
        Err(_) => {
            let _ = tx.rollback().await;
            cleanup_saved_files(&root, "yarns", yarn_id, &files).await;
            return Err(MediaOperationError::Database);
        }
    };
    let insert = sqlx::query(
        "INSERT INTO media_files \
         (owner_id, yarn_id, kind, public_id, filename, original_filename, content_type, size_bytes, \
          width, height, alt_text, is_primary, position, created_at) \
         VALUES (?, ?, 'photo', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
    )
    .bind(user_id)
    .bind(yarn_id)
    .bind(public_id)
    .bind(&files.filename)
    .bind(&upload.original_filename)
    .bind(prepared.content_type)
    .bind(prepared.bytes.len() as i64)
    .bind(prepared.width as i64)
    .bind(prepared.height as i64)
    .bind(&yarn_name)
    .bind(is_primary)
    .bind(position)
    .bind(Utc::now().to_rfc3339_opts(SecondsFormat::Micros, true))
    .execute(&mut *tx)
    .await;
    let media_id = match insert {
        Ok(result) => result.last_insert_rowid(),
        Err(_) => {
            let _ = tx.rollback().await;
            cleanup_saved_files(&root, "yarns", yarn_id, &files).await;
            return Err(MediaOperationError::Database);
        }
    };
    if crate::audit::record(
        &mut tx,
        user_id,
        AuditEntity::Yarn,
        yarn_id,
        AuditAction::Uploaded,
    )
    .await
    .is_err()
    {
        let _ = tx.rollback().await;
        cleanup_saved_files(&root, "yarns", yarn_id, &files).await;
        return Err(MediaOperationError::Database);
    }
    if tx.commit().await.is_err() {
        cleanup_saved_files(&root, "yarns", yarn_id, &files).await;
        return Err(MediaOperationError::Database);
    }
    let row = MediaRow {
        id: media_id,
        public_id,
        owner_id: user_id,
        project_id: None,
        yarn_id: Some(yarn_id),
        step_id: None,
        kind: "photo".to_owned(),
        filename: files.filename,
        original_filename: upload.original_filename,
        content_type: prepared.content_type.to_owned(),
        size_bytes: prepared.bytes.len() as i64,
        alt_text: yarn_name,
        is_primary,
        position,
        is_project_cover: false,
        is_safe_inline: true,
        legacy_image_type: None,
        is_stitch_sample: None,
        legacy_is_title_image: None,
    };
    Ok(yarn_photo_dto(&row))
}

async fn delete_project_image(
    State(state): State<AppState>,
    headers: HeaderMap,
    AxumPath((project_id, image_id)): AxumPath<(i64, i64)>,
) -> Response {
    let user_id = match authenticate(&state, &headers).await {
        Ok(id) => id,
        Err(error) => return error.into_response(),
    };
    match delete_project_media(&state, user_id, project_id, image_id, Namespace::Image).await {
        Ok(()) => StatusCode::NO_CONTENT.into_response(),
        Err(error) => legacy_media_error(error),
    }
}

async fn delete_project_attachment(
    State(state): State<AppState>,
    headers: HeaderMap,
    AxumPath((project_id, attachment_id)): AxumPath<(i64, i64)>,
) -> Response {
    let user_id = match authenticate(&state, &headers).await {
        Ok(id) => id,
        Err(error) => return error.into_response(),
    };
    match delete_project_media(
        &state,
        user_id,
        project_id,
        attachment_id,
        Namespace::Attachment,
    )
    .await
    {
        Ok(()) => StatusCode::NO_CONTENT.into_response(),
        Err(error) => legacy_media_error(error),
    }
}

pub(crate) async fn delete_project_media(
    state: &AppState,
    user_id: i64,
    project_id: i64,
    media_id: i64,
    namespace: Namespace,
) -> Result<(), MediaOperationError> {
    let mut tx = match state.pool.begin().await {
        Ok(tx) => tx,
        Err(_) => return Err(MediaOperationError::Database),
    };
    let row = match sqlx::query_as::<_, MediaRow>(
        "SELECT * FROM media_files WHERE public_id = ? AND owner_id = ? AND project_id = ? \
         AND CASE kind WHEN 'title' THEN 'image' WHEN 'step' THEN 'image' \
             WHEN 'photo' THEN 'photo' WHEN 'attachment' THEN 'attachment' END = ?",
    )
    .bind(media_id)
    .bind(user_id)
    .bind(project_id)
    .bind(namespace.as_str())
    .fetch_optional(&mut *tx)
    .await
    {
        Ok(Some(row)) => row,
        Ok(None) => {
            let _ = tx.rollback().await;
            return Err(MediaOperationError::NotFound(
                if namespace == Namespace::Attachment {
                    "Attachment not found"
                } else {
                    "Image not found"
                },
            ));
        }
        Err(_) => {
            let _ = tx.rollback().await;
            return Err(MediaOperationError::Database);
        }
    };
    if bump_project_timestamp(&mut tx, user_id, project_id)
        .await
        .is_err()
    {
        let _ = tx.rollback().await;
        return Err(MediaOperationError::Database);
    }
    if sqlx::query("DELETE FROM media_files WHERE id = ?")
        .bind(row.id)
        .execute(&mut *tx)
        .await
        .is_err()
    {
        let _ = tx.rollback().await;
        return Err(MediaOperationError::Database);
    }
    let group = match (row.kind.as_str(), row.step_id) {
        ("title", None) => Some(MediaOrderGroup::ProjectTitles(project_id)),
        ("step", Some(step_id)) => Some(MediaOrderGroup::ProjectSteps {
            project_id,
            step_id,
        }),
        _ => None,
    };
    if let Some(group) = group
        && compact_group(&mut tx, group).await.is_err()
    {
        let _ = tx.rollback().await;
        return Err(MediaOperationError::Database);
    }
    if row.is_project_cover
        && restore_project_cover_after_delete(&mut tx, project_id)
            .await
            .is_err()
    {
        let _ = tx.rollback().await;
        return Err(MediaOperationError::Database);
    }
    if crate::audit::record(
        &mut tx,
        user_id,
        AuditEntity::Project,
        project_id,
        AuditAction::Removed,
    )
    .await
    .is_err()
    {
        let _ = tx.rollback().await;
        return Err(MediaOperationError::Database);
    }
    if tx.commit().await.is_err() {
        return Err(MediaOperationError::Database);
    }
    let _ = cleanup_pending(state).await;
    Ok(())
}

async fn delete_yarn_photo(
    State(state): State<AppState>,
    headers: HeaderMap,
    AxumPath((yarn_id, photo_id)): AxumPath<(i64, i64)>,
) -> Response {
    let user_id = match authenticate(&state, &headers).await {
        Ok(id) => id,
        Err(error) => return error.into_response(),
    };
    match delete_yarn_photo_for_owner(&state, user_id, yarn_id, photo_id).await {
        Ok(()) => StatusCode::NO_CONTENT.into_response(),
        Err(error) => legacy_media_error(error),
    }
}

pub(crate) async fn delete_yarn_photo_for_owner(
    state: &AppState,
    user_id: i64,
    yarn_id: i64,
    photo_id: i64,
) -> Result<(), MediaOperationError> {
    let mut tx = match state.pool.begin().await {
        Ok(tx) => tx,
        Err(_) => return Err(MediaOperationError::Database),
    };
    let photo = match sqlx::query_as::<_, MediaRow>(
        "SELECT * FROM media_files WHERE public_id = ? AND owner_id = ? \
         AND yarn_id = ? AND kind = 'photo'",
    )
    .bind(photo_id)
    .bind(user_id)
    .bind(yarn_id)
    .fetch_optional(&mut *tx)
    .await
    {
        Ok(Some(photo)) => photo,
        Ok(None) => {
            let _ = tx.rollback().await;
            return Err(MediaOperationError::NotFound("Photo not found"));
        }
        Err(_) => {
            let _ = tx.rollback().await;
            return Err(MediaOperationError::Database);
        }
    };
    let still_owned = sqlx::query_scalar::<_, i64>("SELECT owner_id FROM yarns WHERE id = ?")
        .bind(yarn_id)
        .fetch_optional(&mut *tx)
        .await;
    match still_owned {
        Ok(Some(owner_id)) if owner_id == user_id => {}
        Ok(_) => {
            let _ = tx.rollback().await;
            return Err(MediaOperationError::NotFound("Photo not found"));
        }
        Err(_) => {
            let _ = tx.rollback().await;
            return Err(MediaOperationError::Database);
        }
    }
    if bump_yarn_timestamp(&mut tx, user_id, yarn_id)
        .await
        .is_err()
    {
        let _ = tx.rollback().await;
        return Err(MediaOperationError::Database);
    }
    if sqlx::query("DELETE FROM media_files WHERE id = ?")
        .bind(photo.id)
        .execute(&mut *tx)
        .await
        .is_err()
    {
        let _ = tx.rollback().await;
        return Err(MediaOperationError::Database);
    }
    let photo_group = MediaOrderGroup::YarnPhotos(yarn_id);
    let remaining = match compact_group(&mut tx, photo_group).await {
        Ok(rows) => rows,
        Err(_) => {
            let _ = tx.rollback().await;
            return Err(MediaOperationError::Database);
        }
    };
    if photo.is_primary
        && set_group_primary(
            &mut tx,
            photo_group,
            remaining.first().map(|(id, _, _, _)| *id),
        )
        .await
        .is_err()
    {
        let _ = tx.rollback().await;
        return Err(MediaOperationError::Database);
    }
    if crate::audit::record(
        &mut tx,
        user_id,
        AuditEntity::Yarn,
        yarn_id,
        AuditAction::Removed,
    )
    .await
    .is_err()
    {
        let _ = tx.rollback().await;
        return Err(MediaOperationError::Database);
    }
    if tx.commit().await.is_err() {
        return Err(MediaOperationError::Database);
    }
    let _ = cleanup_pending(state).await;
    Ok(())
}

async fn serve_media(
    State(state): State<AppState>,
    headers: HeaderMap,
    AxumPath((subdir, parent_id, filename)): AxumPath<(String, String, String)>,
) -> Response {
    serve_registered(&state, &headers, &subdir, &parent_id, &filename, false).await
}

async fn serve_thumbnail(
    State(state): State<AppState>,
    headers: HeaderMap,
    AxumPath((subdir, parent_id, filename)): AxumPath<(String, String, String)>,
) -> Response {
    serve_registered(&state, &headers, &subdir, &parent_id, &filename, true).await
}

async fn serve_avatar(
    State(state): State<AppState>,
    headers: HeaderMap,
    AxumPath((user_id, filename)): AxumPath<(String, String)>,
) -> Response {
    serve_user_avatar(&state, &headers, &user_id, &filename, false).await
}

async fn serve_avatar_thumbnail(
    State(state): State<AppState>,
    headers: HeaderMap,
    AxumPath((user_id, filename)): AxumPath<(String, String)>,
) -> Response {
    serve_user_avatar(&state, &headers, &user_id, &filename, true).await
}

async fn serve_user_avatar(
    state: &AppState,
    headers: &HeaderMap,
    user_id: &str,
    requested_name: &str,
    thumbnail: bool,
) -> Response {
    let actor_id = match authenticate(state, headers).await {
        Ok(id) => id,
        Err(error) => return error.into_response(),
    };
    let user_id = match user_id.parse::<i64>() {
        Ok(id) if id > 0 => id,
        _ => return StatusCode::NOT_FOUND.into_response(),
    };
    if !safe_user_avatar_filename(requested_name) {
        return StatusCode::NOT_FOUND.into_response();
    }
    if actor_id != user_id {
        return StatusCode::FORBIDDEN.into_response();
    }
    if requested_name == crate::gravatar::SERVE_NAME {
        return serve_gravatar(state, user_id).await;
    }
    let avatar = match sqlx::query_as::<_, AvatarRow>(
        "SELECT filename, original_filename, content_type FROM user_avatars WHERE user_id = ?",
    )
    .bind(user_id)
    .fetch_optional(&state.pool)
    .await
    {
        Ok(Some(avatar)) => avatar,
        Ok(None) => return StatusCode::NOT_FOUND.into_response(),
        Err(_) => return database_error(),
    };
    let expected_name = if thumbnail {
        thumbnail_name(&avatar.filename)
    } else {
        avatar.filename.clone()
    };
    if requested_name != expected_name {
        return StatusCode::NOT_FOUND.into_response();
    }
    let root = match media_root(state) {
        Ok(root) => root,
        Err(error) => return error.into_response(),
    };
    let path = match user_avatar_path(&root, user_id, &avatar.filename, thumbnail).await {
        Ok(Some(path)) => path,
        Ok(None) => return StatusCode::NOT_FOUND.into_response(),
        Err(_) => return StatusCode::NOT_FOUND.into_response(),
    };
    let bytes = match tokio::fs::read(path).await {
        Ok(bytes) => bytes,
        Err(_) => return StatusCode::NOT_FOUND.into_response(),
    };
    let (content_type, attachment, disposition_name) = if thumbnail {
        (
            "image/jpeg".to_owned(),
            false,
            "avatar-thumbnail.jpg".to_owned(),
        )
    } else {
        let content_type = safe_mime_type(avatar.content_type.as_deref())
            .unwrap_or_else(|| "application/octet-stream".to_owned());
        let inline = matches!(
            content_type.as_str(),
            "image/png" | "image/jpeg" | "image/webp" | "image/gif" | "image/bmp" | "image/tiff"
        );
        let source_name = avatar
            .original_filename
            .as_deref()
            .unwrap_or(&avatar.filename);
        (
            content_type,
            !inline,
            safe_disposition_filename(source_name),
        )
    };
    media_response(&disposition_name, &content_type, attachment, bytes)
}

/// Serve the server-fetched Gravatar for `user_id`. The caller has already
/// authorized access. An uploaded avatar, the user's opt-out, the global switch
/// and a missing Gravatar all answer 404 so clients show their fallback.
async fn serve_gravatar(state: &AppState, user_id: i64) -> Response {
    if !state.gravatar.enabled {
        return StatusCode::NOT_FOUND.into_response();
    }
    let row = match sqlx::query_as::<_, (String, bool, bool)>(
        "SELECT users.email, users.use_gravatar, \
         EXISTS(SELECT 1 FROM user_avatars WHERE user_avatars.user_id = users.id) \
         FROM users WHERE users.id = ? AND users.active = 1",
    )
    .bind(user_id)
    .fetch_optional(&state.pool)
    .await
    {
        Ok(Some(row)) => row,
        Ok(None) => return StatusCode::NOT_FOUND.into_response(),
        Err(_) => return database_error(),
    };
    let (email, use_gravatar, has_upload) = row;
    if has_upload || !use_gravatar {
        return StatusCode::NOT_FOUND.into_response();
    }
    let root = match media_root(state) {
        Ok(root) => root,
        Err(error) => return error.into_response(),
    };
    match crate::gravatar::resolve(&state.gravatar, &root, &email).await {
        Some(image) => media_response("gravatar", image.content_type, false, image.bytes),
        None => StatusCode::NOT_FOUND.into_response(),
    }
}

/// Where a user's avatar comes from.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum AvatarSource {
    Upload,
    Gravatar,
}

impl AvatarSource {
    pub(crate) fn as_str(self) -> &'static str {
        match self {
            Self::Upload => "upload",
            Self::Gravatar => "gravatar",
        }
    }
}

/// Resolve which avatar a user shows. An upload always wins; otherwise the
/// Gravatar applies when it is globally enabled and the user has not opted out.
pub(crate) fn avatar_source(
    upload_filename: Option<&str>,
    use_gravatar: bool,
    gravatar_enabled: bool,
) -> Option<AvatarSource> {
    match upload_filename {
        Some(_) => Some(AvatarSource::Upload),
        None if use_gravatar && gravatar_enabled => Some(AvatarSource::Gravatar),
        None => None,
    }
}

/// The protected self-avatar path for the user's own sessions.
pub(crate) fn self_avatar(
    user_id: i64,
    upload_filename: Option<&str>,
    use_gravatar: bool,
    gravatar_enabled: bool,
) -> Option<(String, AvatarSource)> {
    let source = avatar_source(upload_filename, use_gravatar, gravatar_enabled)?;
    let name = match (source, upload_filename) {
        (AvatarSource::Upload, Some(filename)) => thumbnail_name(filename),
        _ => crate::gravatar::SERVE_NAME.to_owned(),
    };
    Some((format!("/media/thumbnails/users/{user_id}/{name}"), source))
}

/// The administrator-readable avatar path for another user.
pub(crate) fn admin_avatar(
    user_id: i64,
    upload_filename: Option<&str>,
    use_gravatar: bool,
    gravatar_enabled: bool,
) -> Option<(String, AvatarSource)> {
    let source = avatar_source(upload_filename, use_gravatar, gravatar_enabled)?;
    match (source, upload_filename) {
        (AvatarSource::Upload, Some(filename)) => {
            admin_avatar_thumbnail_url(user_id, filename).map(|url| (url, source))
        }
        _ if user_id > 0 => Some((
            format!(
                "/api/v2/admin/users/{user_id}/avatar/{}",
                crate::gravatar::SERVE_NAME
            ),
            source,
        )),
        _ => None,
    }
}

/// Serve a registered avatar thumbnail only after verifying the requesting admin is active.
pub(crate) async fn serve_admin_user_avatar_thumbnail(
    state: &AppState,
    admin_id: i64,
    user_id: i64,
    requested_name: &str,
) -> Result<Response, AdminAvatarError> {
    crate::admin_service::require_active_admin(&state.pool, admin_id)
        .await
        .map_err(|error| match error {
            crate::admin_service::AdminError::Forbidden => AdminAvatarError::Forbidden,
            _ => AdminAvatarError::Database,
        })?;
    if user_id <= 0 || !safe_user_avatar_filename(requested_name) {
        return Err(AdminAvatarError::NotFound);
    }
    if requested_name == crate::gravatar::SERVE_NAME {
        return match serve_gravatar(state, user_id).await {
            response if response.status() == StatusCode::OK => Ok(response),
            response if response.status() == StatusCode::SERVICE_UNAVAILABLE => {
                Err(AdminAvatarError::Unavailable)
            }
            response if response.status() == StatusCode::INTERNAL_SERVER_ERROR => {
                Err(AdminAvatarError::Database)
            }
            _ => Err(AdminAvatarError::NotFound),
        };
    }
    let avatar = sqlx::query_as::<_, AvatarRow>(
        "SELECT filename, original_filename, content_type FROM user_avatars WHERE user_id = ?",
    )
    .bind(user_id)
    .fetch_optional(&state.pool)
    .await
    .map_err(|_| AdminAvatarError::Database)?
    .ok_or(AdminAvatarError::NotFound)?;
    if requested_name != thumbnail_name(&avatar.filename) {
        return Err(AdminAvatarError::NotFound);
    }
    let root = media_root(state).map_err(|_| AdminAvatarError::Unavailable)?;
    let path = user_avatar_path(&root, user_id, &avatar.filename, true)
        .await
        .map_err(|_| AdminAvatarError::NotFound)?
        .ok_or(AdminAvatarError::NotFound)?;
    let file = tokio::fs::File::open(path)
        .await
        .map_err(|_| AdminAvatarError::NotFound)?;
    let mut bytes = Vec::new();
    file.take((MAX_UPLOAD_BYTES + 1) as u64)
        .read_to_end(&mut bytes)
        .await
        .map_err(|_| AdminAvatarError::NotFound)?;
    if bytes.len() > MAX_UPLOAD_BYTES {
        return Err(AdminAvatarError::NotFound);
    }
    Ok(media_response(
        "avatar-thumbnail.jpg",
        "image/jpeg",
        false,
        bytes,
    ))
}

async fn serve_registered(
    state: &AppState,
    headers: &HeaderMap,
    subdir: &str,
    parent_id: &str,
    requested_name: &str,
    thumbnail: bool,
) -> Response {
    let user_id = match authenticate(state, headers).await {
        Ok(id) => id,
        Err(error) => return error.into_response(),
    };
    let parent_id = match parent_id.parse::<i64>() {
        Ok(id) if id > 0 => id,
        _ => return StatusCode::NOT_FOUND.into_response(),
    };
    if !valid_subdir(subdir) || !safe_media_request_name(requested_name) {
        return StatusCode::NOT_FOUND.into_response();
    }
    let row =
        match registered_media(&state.pool, subdir, parent_id, requested_name, thumbnail).await {
            Ok(Some(row)) => row,
            Ok(None) => return StatusCode::NOT_FOUND.into_response(),
            Err(_) => return database_error(),
        };
    if row.owner_id != user_id {
        return StatusCode::FORBIDDEN.into_response();
    }
    let root = match media_root(state) {
        Ok(root) => root,
        Err(error) => return error.into_response(),
    };
    let (path, content_type, attachment) = if thumbnail {
        (
            thumbnail_path(&root, subdir, parent_id, &row.filename).await,
            "image/jpeg".to_owned(),
            false,
        )
    } else {
        (
            existing_file_path(&root, subdir, parent_id, &row.filename).await,
            row.content_type.clone(),
            row.kind == "attachment" && !row.is_safe_inline,
        )
    };
    let path = match path {
        Ok(Some(path)) => path,
        Ok(None) => return StatusCode::NOT_FOUND.into_response(),
        Err(_) => return StatusCode::NOT_FOUND.into_response(),
    };
    let bytes = match tokio::fs::read(path).await {
        Ok(bytes) => bytes,
        Err(_) => return StatusCode::NOT_FOUND.into_response(),
    };
    media_response(&row.filename, &content_type, attachment, bytes)
}

#[derive(Debug)]
pub(crate) enum AdminResourceMediaError {
    Database,
    Forbidden,
    NotFound,
    Unavailable,
}

/// Serve project or yarn media after checking the active administrator and
/// the exact resource-to-media relationship.
pub(crate) async fn serve_admin_resource_media(
    state: &AppState,
    admin_id: i64,
    kind: &str,
    media_kind: &str,
    parent_id: i64,
    public_id: i64,
    thumbnail: bool,
) -> Result<Response, AdminResourceMediaError> {
    crate::admin_service::require_active_admin(&state.pool, admin_id)
        .await
        .map_err(|error| match error {
            crate::admin_service::AdminError::Forbidden => AdminResourceMediaError::Forbidden,
            _ => AdminResourceMediaError::Database,
        })?;
    if parent_id <= 0 || public_id <= 0 {
        return Err(AdminResourceMediaError::NotFound);
    }
    let row = match (kind, media_kind) {
        ("project", "image") => {
            sqlx::query_as::<_, MediaRow>(
                "SELECT m.* FROM media_files AS m \
                 JOIN projects AS p ON p.id = m.project_id AND p.owner_id = m.owner_id \
                 WHERE p.id = ? AND m.public_id = ? AND m.kind IN ('title', 'step')",
            )
            .bind(parent_id)
            .bind(public_id)
            .fetch_optional(&state.pool)
            .await
        }
        ("project", "attachment") => {
            sqlx::query_as::<_, MediaRow>(
                "SELECT m.* FROM media_files AS m \
                 JOIN projects AS p ON p.id = m.project_id AND p.owner_id = m.owner_id \
                 WHERE p.id = ? AND m.public_id = ? AND m.kind = 'attachment'",
            )
            .bind(parent_id)
            .bind(public_id)
            .fetch_optional(&state.pool)
            .await
        }
        ("yarn", "photo") => {
            sqlx::query_as::<_, MediaRow>(
                "SELECT m.* FROM media_files AS m \
                 JOIN yarns AS y ON y.id = m.yarn_id AND y.owner_id = m.owner_id \
                 WHERE y.id = ? AND m.public_id = ? AND m.kind = 'photo'",
            )
            .bind(parent_id)
            .bind(public_id)
            .fetch_optional(&state.pool)
            .await
        }
        _ => return Err(AdminResourceMediaError::NotFound),
    }
    .map_err(|_| AdminResourceMediaError::Database)?
    .ok_or(AdminResourceMediaError::NotFound)?;
    if thumbnail && media_kind == "attachment" {
        return Err(AdminResourceMediaError::NotFound);
    }

    let root = media_root(state).map_err(|_| AdminResourceMediaError::Unavailable)?;
    let subdir = match kind {
        "project" => "projects",
        "yarn" => "yarns",
        _ => return Err(AdminResourceMediaError::NotFound),
    };
    let path = if thumbnail {
        thumbnail_path(&root, subdir, parent_id, &row.filename).await
    } else {
        existing_file_path(&root, subdir, parent_id, &row.filename).await
    }
    .map_err(|_| AdminResourceMediaError::NotFound)?
    .ok_or(AdminResourceMediaError::NotFound)?;
    let file = tokio::fs::File::open(path)
        .await
        .map_err(|_| AdminResourceMediaError::NotFound)?;
    let mut bytes = Vec::new();
    file.take((MAX_UPLOAD_BYTES + 1) as u64)
        .read_to_end(&mut bytes)
        .await
        .map_err(|_| AdminResourceMediaError::NotFound)?;
    if bytes.len() > MAX_UPLOAD_BYTES {
        return Err(AdminResourceMediaError::NotFound);
    }
    let (content_type, attachment, filename) = if thumbnail {
        ("image/jpeg", false, "thumbnail.jpg".to_owned())
    } else {
        (
            row.content_type.as_str(),
            row.kind == "attachment" && !row.is_safe_inline,
            safe_disposition_filename(&row.original_filename),
        )
    };
    Ok(media_response(&filename, content_type, attachment, bytes))
}

/// Return current project image and attachment DTO values for project serializers.
pub(crate) async fn project_media(
    pool: &SqlitePool,
    user_id: i64,
    project_id: i64,
) -> Result<(Vec<Value>, Vec<Value>), sqlx::Error> {
    let rows = sqlx::query_as::<_, MediaRow>(
        "SELECT * FROM media_files WHERE owner_id = ? AND project_id = ? \
         ORDER BY id",
    )
    .bind(user_id)
    .bind(project_id)
    .fetch_all(pool)
    .await?;
    let mut images = Vec::new();
    let mut attachments = Vec::new();
    for row in rows {
        match row.kind.as_str() {
            "title" | "step" => images.push(
                serde_json::to_value(image_dto(&row))
                    .map_err(|error| sqlx::Error::Protocol(error.to_string()))?,
            ),
            "attachment" => attachments.push(
                serde_json::to_value(attachment_dto(&row))
                    .map_err(|error| sqlx::Error::Protocol(error.to_string()))?,
            ),
            _ => {}
        }
    }
    Ok((images, attachments))
}

/// Return ordered v2 media DTOs and the canonical project cover preview.
pub(crate) async fn project_media_v2(
    pool: &SqlitePool,
    user_id: i64,
    project_id: i64,
) -> Result<(Vec<Value>, Vec<Value>, Option<String>), sqlx::Error> {
    let rows = sqlx::query_as::<_, MediaRow>(
        "SELECT * FROM media_files WHERE owner_id = ? AND project_id = ? \
         ORDER BY CASE kind WHEN 'title' THEN 0 WHEN 'step' THEN 1 ELSE 2 END, \
                  step_id, position, id",
    )
    .bind(user_id)
    .bind(project_id)
    .fetch_all(pool)
    .await?;
    let mut id_order: Vec<_> = rows
        .iter()
        .filter(|row| matches!(row.kind.as_str(), "title" | "step"))
        .collect();
    id_order.sort_by_key(|row| row.id);
    let canonical_cover = id_order
        .iter()
        .find(|row| row.is_project_cover)
        .map(|row| row.id);
    let mut images = Vec::new();
    let mut attachments = Vec::new();
    let mut preview = None;
    for row in rows {
        match row.kind.as_str() {
            "title" | "step" => {
                let is_primary = canonical_cover == Some(row.id);
                let mut value = serde_json::to_value(image_dto(&row))
                    .map_err(|error| sqlx::Error::Protocol(error.to_string()))?;
                let object = value
                    .as_object_mut()
                    .ok_or_else(|| sqlx::Error::Protocol("image DTO was not an object".into()))?;
                object.insert("position".into(), Value::from(row.position));
                object.insert("is_primary".into(), Value::from(is_primary));
                if is_primary {
                    preview = value["thumbnail_url"].as_str().map(str::to_owned);
                }
                images.push(value);
            }
            "attachment" => attachments.push(
                serde_json::to_value(attachment_dto(&row))
                    .map_err(|error| sqlx::Error::Protocol(error.to_string()))?,
            ),
            _ => {}
        }
    }
    Ok((images, attachments, preview))
}

/// Load canonical project cover thumbnails for one paged list response.
pub(crate) async fn project_previews_v2(
    pool: &SqlitePool,
    user_id: i64,
    project_ids: &[i64],
) -> Result<HashMap<i64, String>, sqlx::Error> {
    if project_ids.is_empty() {
        return Ok(HashMap::new());
    }
    let mut query = QueryBuilder::<Sqlite>::new(
        "SELECT project_id,filename FROM media_files WHERE owner_id = ",
    );
    query.push_bind(user_id).push(" AND project_id IN (");
    let mut separated = query.separated(",");
    for project_id in project_ids {
        separated.push_bind(project_id);
    }
    separated.push_unseparated(") AND kind IN ('title','step') AND is_project_cover = 1");
    let rows = query
        .build_query_as::<(i64, String)>()
        .fetch_all(pool)
        .await?;
    Ok(rows
        .into_iter()
        .map(|(project_id, filename)| {
            (project_id, thumbnail_url("projects", project_id, &filename))
        })
        .collect())
}

/// Return current yarn photo DTO values for yarn serializers.
pub(crate) async fn yarn_photos(
    pool: &SqlitePool,
    user_id: i64,
    yarn_id: i64,
) -> Result<Vec<Value>, sqlx::Error> {
    let rows = sqlx::query_as::<_, MediaRow>(
        "SELECT * FROM media_files WHERE owner_id = ? AND yarn_id = ? AND kind = 'photo' \
         ORDER BY is_primary DESC, id DESC",
    )
    .bind(user_id)
    .bind(yarn_id)
    .fetch_all(pool)
    .await?;
    rows.iter()
        .map(|row| {
            serde_json::to_value(yarn_photo_dto(row))
                .map_err(|error| sqlx::Error::Protocol(error.to_string()))
        })
        .collect()
}

/// Return ordered v2 yarn photo DTOs and the canonical cover preview.
pub(crate) async fn yarn_photos_v2(
    pool: &SqlitePool,
    user_id: i64,
    yarn_id: i64,
) -> Result<(Vec<Value>, Option<String>), sqlx::Error> {
    let rows = sqlx::query_as::<_, MediaRow>(
        "SELECT * FROM media_files WHERE owner_id = ? AND yarn_id = ? AND kind = 'photo' \
         ORDER BY position,id",
    )
    .bind(user_id)
    .bind(yarn_id)
    .fetch_all(pool)
    .await?;
    let canonical_cover = rows.iter().find(|row| row.is_primary);
    let mut preview = None;
    let photos = rows
        .iter()
        .map(|row| {
            let is_primary = canonical_cover.is_some_and(|cover| cover.id == row.id);
            let mut value = serde_json::to_value(yarn_photo_dto(row))
                .map_err(|error| sqlx::Error::Protocol(error.to_string()))?;
            let object = value
                .as_object_mut()
                .ok_or_else(|| sqlx::Error::Protocol("photo DTO was not an object".into()))?;
            object.insert("position".into(), Value::from(row.position));
            object.insert("is_primary".into(), Value::from(is_primary));
            if is_primary {
                preview = value["thumbnail_url"].as_str().map(str::to_owned);
            }
            Ok(value)
        })
        .collect::<Result<Vec<_>, sqlx::Error>>()?;
    Ok((photos, preview))
}

/// Load canonical yarn cover thumbnails for one paged list response.
pub(crate) async fn yarn_previews_v2(
    pool: &SqlitePool,
    user_id: i64,
    yarn_ids: &[i64],
) -> Result<HashMap<i64, String>, sqlx::Error> {
    if yarn_ids.is_empty() {
        return Ok(HashMap::new());
    }
    let mut query =
        QueryBuilder::<Sqlite>::new("SELECT yarn_id,filename FROM media_files WHERE owner_id = ");
    query.push_bind(user_id).push(" AND yarn_id IN (");
    let mut separated = query.separated(",");
    for yarn_id in yarn_ids {
        separated.push_bind(yarn_id);
    }
    separated.push_unseparated(") AND kind = 'photo' AND is_primary = 1");
    let rows = query
        .build_query_as::<(i64, String)>()
        .fetch_all(pool)
        .await?;
    Ok(rows
        .into_iter()
        .map(|(yarn_id, filename)| (yarn_id, thumbnail_url("yarns", yarn_id, &filename)))
        .collect())
}

/// Select the project preview URL from an already serialized image list.
pub(crate) fn project_preview(images: &[Value]) -> Option<String> {
    images
        .iter()
        .find(|image| image["is_title_image"] == true)
        .or_else(|| images.first())
        .and_then(|image| image["thumbnail_url"].as_str())
        .map(str::to_owned)
}

/// Select the yarn preview URL from an already serialized photo list.
pub(crate) fn yarn_preview(photos: &[Value]) -> Option<String> {
    photos
        .iter()
        .find(|photo| photo["is_primary"] == true)
        .and_then(|photo| photo["thumbnail_url"].as_str())
        .map(str::to_owned)
}

/// Process at most one bounded batch from the durable media deletion queue.
pub async fn cleanup_pending(state: &AppState) -> Result<(), sqlx::Error> {
    let Some(root) = state.media_root.as_ref() else {
        return Ok(());
    };
    let batch_size = GC_BATCH_SIZE / 2;
    let rows = sqlx::query_as::<_, GarbageRow>(
        "SELECT filename, subdir, parent_id FROM media_gc ORDER BY rowid LIMIT ?",
    )
    .bind(batch_size)
    .fetch_all(&state.pool)
    .await?;
    for row in rows {
        let file_ok = remove_stored_file(root, &row.subdir, row.parent_id, &row.filename).await;
        let thumb_ok =
            remove_stored_thumbnail(root, &row.subdir, row.parent_id, &row.filename).await;
        if file_ok && thumb_ok {
            sqlx::query("DELETE FROM media_gc WHERE filename = ?")
                .bind(row.filename)
                .execute(&state.pool)
                .await?;
        }
    }
    let avatars = sqlx::query_as::<_, AvatarGarbageRow>(
        "SELECT user_id, filename FROM avatar_gc ORDER BY rowid LIMIT ?",
    )
    .bind(batch_size)
    .fetch_all(&state.pool)
    .await?;
    for avatar in avatars {
        if remove_stored_avatar(root, avatar.user_id, &avatar.filename).await {
            sqlx::query("DELETE FROM avatar_gc WHERE user_id = ? AND filename = ?")
                .bind(avatar.user_id)
                .bind(avatar.filename)
                .execute(&state.pool)
                .await?;
        }
    }
    Ok(())
}

fn image_dto(row: &MediaRow) -> ImageResponse {
    let project_id = row.project_id.unwrap_or_default();
    ImageResponse {
        id: row.public_id,
        url: media_url("projects", project_id, &row.filename),
        thumbnail_url: thumbnail_url("projects", project_id, &row.filename),
        alt_text: row.alt_text.clone(),
        image_type: row
            .legacy_image_type
            .clone()
            .unwrap_or_else(|| "photo".to_owned()),
        is_title_image: row
            .legacy_is_title_image
            .unwrap_or(row.kind == "title" && row.is_primary),
        is_stitch_sample: row.is_stitch_sample.unwrap_or(false),
        step_id: row.step_id,
    }
}

fn attachment_dto(row: &MediaRow) -> AttachmentResponse {
    let project_id = row.project_id.unwrap_or_default();
    AttachmentResponse {
        id: row.public_id,
        filename: row.filename.clone(),
        original_filename: row.original_filename.clone(),
        content_type: row.content_type.clone(),
        size_bytes: row.size_bytes,
        url: media_url("projects", project_id, &row.filename),
    }
}

fn yarn_photo_dto(row: &MediaRow) -> YarnPhotoResponse {
    let yarn_id = row.yarn_id.unwrap_or_default();
    YarnPhotoResponse {
        id: row.public_id,
        url: media_url("yarns", yarn_id, &row.filename),
        thumbnail_url: thumbnail_url("yarns", yarn_id, &row.filename),
        alt_text: row.alt_text.clone(),
        is_primary: row.is_primary,
    }
}

fn media_url(subdir: &str, parent_id: i64, filename: &str) -> String {
    format!("/media/{subdir}/{parent_id}/{filename}")
}

pub(crate) fn admin_resource_media_url(
    kind: &str,
    parent_id: i64,
    media_kind: &str,
    public_id: i64,
    thumbnail: bool,
) -> Option<String> {
    let supported = matches!(
        (kind, media_kind),
        ("project", "image" | "attachment") | ("yarn", "photo")
    );
    if !supported || parent_id <= 0 || public_id <= 0 {
        return None;
    }
    let url = format!("/api/v2/admin/resources/{kind}/{parent_id}/media/{media_kind}/{public_id}");
    Some(if thumbnail {
        format!("{url}/thumbnail")
    } else {
        url
    })
}

fn thumbnail_url(subdir: &str, parent_id: i64, filename: &str) -> String {
    format!(
        "/media/thumbnails/{subdir}/{parent_id}/{}",
        thumbnail_name(filename)
    )
}

fn thumbnail_name(filename: &str) -> String {
    let stem = Path::new(filename)
        .file_stem()
        .and_then(|stem| stem.to_str())
        .unwrap_or("media");
    format!("thumb_{stem}.jpg")
}

fn json_response<T: Serialize>(status: StatusCode, body: T) -> Response {
    (status, Json(body)).into_response()
}

async fn authenticate(state: &AppState, headers: &HeaderMap) -> Result<i64, AuthError> {
    if headers.contains_key(axum::http::header::AUTHORIZATION) {
        bearer_user(&state.pool, headers).await
    } else {
        crate::auth::browser_user(&state.pool, headers, state.secure_cookies).await
    }
}

fn media_root(state: &AppState) -> Result<PathBuf, StatusCode> {
    state
        .media_root
        .clone()
        .ok_or(StatusCode::SERVICE_UNAVAILABLE)
}

async fn read_upload(mut multipart: Multipart, want_alt_text: bool) -> Result<Upload, UploadError> {
    let mut bytes = None;
    let mut original_filename = None;
    let mut alt_text = String::new();
    let mut saw_alt_text = false;
    let mut upload_content_type = None;
    loop {
        let field = multipart.next_field().await.map_err(map_multipart_error)?;
        let Some(mut field) = field else { break };
        let field_name = field.name().unwrap_or_default().to_owned();
        match field_name.as_str() {
            "file" if bytes.is_none() => {
                upload_content_type = field.content_type().map(str::to_owned);
                original_filename =
                    Some(safe_original_filename(field.file_name().unwrap_or("file")));
                let mut content = Vec::new();
                while let Some(chunk) = field.chunk().await.map_err(map_multipart_error)? {
                    if content.len().saturating_add(chunk.len()) > MAX_UPLOAD_BYTES {
                        return Err(UploadError::TooLarge);
                    }
                    content.extend_from_slice(&chunk);
                }
                bytes = Some(content);
            }
            "alt_text" if want_alt_text && !saw_alt_text => {
                let mut text = Vec::new();
                while let Some(chunk) = field.chunk().await.map_err(map_multipart_error)? {
                    if text.len().saturating_add(chunk.len()) > MAX_ALT_TEXT_BYTES {
                        return Err(UploadError::BadMultipart);
                    }
                    text.extend_from_slice(&chunk);
                }
                alt_text = String::from_utf8(text).map_err(|_| UploadError::BadMultipart)?;
                saw_alt_text = true;
            }
            _ => return Err(UploadError::BadMultipart),
        }
    }
    let Some(bytes) = bytes else {
        return Err(UploadError::BadMultipart);
    };
    Ok(Upload {
        bytes,
        original_filename: original_filename.unwrap_or_else(|| "file".to_owned()),
        alt_text,
        claimed_content_type: upload_content_type,
    })
}

fn map_multipart_error(error: axum::extract::multipart::MultipartError) -> UploadError {
    if error.status() == StatusCode::PAYLOAD_TOO_LARGE {
        UploadError::TooLarge
    } else {
        UploadError::BadMultipart
    }
}

async fn process_image(bytes: Vec<u8>) -> Result<PreparedImage, UploadError> {
    let permit = IMAGE_PROCESSING
        .clone()
        .acquire_owned()
        .await
        .map_err(|_| UploadError::InvalidImage)?;
    tokio::task::spawn_blocking(move || {
        let _permit = permit;
        decode_image(bytes)
    })
    .await
    .map_err(|_| UploadError::InvalidImage)?
}

static IMAGE_PROCESSING: LazyLock<Arc<tokio::sync::Semaphore>> =
    LazyLock::new(|| Arc::new(tokio::sync::Semaphore::new(2)));

/// Read and validate the first multipart field before a handler reads file bytes.
pub(crate) async fn read_avatar_csrf(multipart: &mut Multipart) -> Result<String, UploadError> {
    let mut field = multipart
        .next_field()
        .await
        .map_err(map_multipart_error)?
        .ok_or(UploadError::BadMultipart)?;
    if field.name() != Some("csrf") {
        return Err(UploadError::BadMultipart);
    }
    let mut bytes = Vec::new();
    while let Some(chunk) = field.chunk().await.map_err(map_multipart_error)? {
        if bytes.len().saturating_add(chunk.len()) > 128 {
            return Err(UploadError::TooLarge);
        }
        bytes.extend_from_slice(&chunk);
    }
    String::from_utf8(bytes).map_err(|_| UploadError::BadMultipart)
}

/// Read the remaining avatar upload after the caller has validated its CSRF field.
pub(crate) async fn read_avatar_upload(
    mut multipart: Multipart,
) -> Result<AvatarUpload, UploadError> {
    let mut bytes = None;
    let mut original_filename = None;
    while let Some(mut field) = multipart.next_field().await.map_err(map_multipart_error)? {
        if field.name() != Some("file") || bytes.is_some() {
            return Err(UploadError::BadMultipart);
        }
        original_filename = Some(safe_original_filename(
            field.file_name().unwrap_or("avatar"),
        ));
        let mut content = Vec::new();
        while let Some(chunk) = field.chunk().await.map_err(map_multipart_error)? {
            if content.len().saturating_add(chunk.len()) > MAX_UPLOAD_BYTES {
                return Err(UploadError::TooLarge);
            }
            content.extend_from_slice(&chunk);
        }
        bytes = Some(content);
    }
    let Some(bytes) = bytes.filter(|bytes| !bytes.is_empty()) else {
        return Err(UploadError::BadMultipart);
    };
    Ok(AvatarUpload {
        bytes,
        original_filename: original_filename.unwrap_or_else(|| "avatar".to_owned()),
    })
}

/// Decode an avatar under the same bounded decoder used for project and yarn images.
pub(crate) async fn prepare_avatar(bytes: Vec<u8>) -> Result<PreparedImage, UploadError> {
    process_image(bytes).await
}

/// Save an opaque original and JPEG thumbnail beneath the user avatar directories.
pub(crate) async fn save_avatar(
    root: &Path,
    user_id: i64,
    image: &PreparedImage,
) -> Result<String, std::io::Error> {
    if user_id <= 0 {
        return Err(std::io::Error::new(
            ErrorKind::InvalidInput,
            "invalid avatar user",
        ));
    }
    Ok(save_image_files(root, "users", user_id, image)
        .await?
        .filename)
}

/// Remove only registered-style avatar paths after a failed database transaction.
pub(crate) async fn cleanup_avatar(root: &Path, user_id: i64, filename: &str) {
    let _ = remove_stored_avatar(root, user_id, filename).await;
}

pub(crate) fn avatar_thumbnail_name(filename: &str) -> String {
    thumbnail_name(filename)
}

pub(crate) fn admin_avatar_thumbnail_url(user_id: i64, filename: &str) -> Option<String> {
    if user_id <= 0 || !safe_filename(filename) {
        return None;
    }
    Some(format!(
        "/api/v2/admin/users/{user_id}/avatar/{}",
        thumbnail_name(filename)
    ))
}

fn decode_image(bytes: Vec<u8>) -> Result<PreparedImage, UploadError> {
    let mut reader = ImageReader::new(Cursor::new(bytes.as_slice()))
        .with_guessed_format()
        .map_err(|_| UploadError::InvalidImage)?;
    let format = reader.format().ok_or(UploadError::InvalidImage)?;
    if !matches!(
        format,
        ImageFormat::Png
            | ImageFormat::Jpeg
            | ImageFormat::WebP
            | ImageFormat::Gif
            | ImageFormat::Bmp
            | ImageFormat::Tiff
    ) {
        return Err(UploadError::InvalidImage);
    }
    let mut limits = Limits::default();
    limits.max_image_width = Some(MAX_IMAGE_DIMENSION);
    limits.max_image_height = Some(MAX_IMAGE_DIMENSION);
    limits.max_alloc = Some(MAX_IMAGE_ALLOCATION);
    reader.limits(limits);
    let image = reader.decode().map_err(|_| UploadError::InvalidImage)?;
    let width = image.width();
    let height = image.height();
    if width == 0 || height == 0 || u64::from(width) * u64::from(height) > MAX_IMAGE_PIXELS {
        return Err(UploadError::InvalidImage);
    }
    let (content_type, extension) = image_format_mime(format);
    let thumbnail = encode_jpeg(&image.thumbnail(300, 300))?;
    Ok(PreparedImage {
        // Keep the validated source bytes intact so animated GIF/WebP assets
        // are not silently flattened during storage.
        bytes,
        thumbnail,
        width,
        height,
        content_type,
        extension,
    })
}

fn image_format_mime(format: ImageFormat) -> (&'static str, &'static str) {
    match format {
        ImageFormat::Png => ("image/png", ".png"),
        ImageFormat::Jpeg => ("image/jpeg", ".jpg"),
        ImageFormat::WebP => ("image/webp", ".webp"),
        ImageFormat::Gif => ("image/gif", ".gif"),
        ImageFormat::Bmp => ("image/bmp", ".bmp"),
        ImageFormat::Tiff => ("image/tiff", ".tiff"),
        _ => ("application/octet-stream", ".bin"),
    }
}

fn encode_jpeg(image: &DynamicImage) -> Result<Vec<u8>, UploadError> {
    let mut output = Cursor::new(Vec::new());
    DynamicImage::ImageRgb8(image.to_rgb8())
        .write_to(&mut output, ImageFormat::Jpeg)
        .map_err(|_| UploadError::InvalidImage)?;
    Ok(output.into_inner())
}

fn safe_mime_type(value: Option<&str>) -> Option<String> {
    let value = value?.split(';').next()?.trim();
    let (kind, subtype) = value.split_once('/')?;
    let valid_token = |part: &str| {
        !part.is_empty()
            && part.bytes().all(|byte| {
                byte.is_ascii_alphanumeric()
                    || matches!(byte, b'!' | b'#'..=b'$' | b'&'..=b'.' | b'^'..=b'_' | b'`' | b'|' | b'~')
            })
    };
    (valid_token(kind) && valid_token(subtype)).then(|| value.to_ascii_lowercase())
}

fn safe_extension(filename: &str) -> String {
    let extension = filename.rsplit_once('.').map(|(_, ext)| ext).unwrap_or("");
    if !extension.is_empty()
        && extension.len() <= 16
        && extension.bytes().all(|byte| byte.is_ascii_alphanumeric())
    {
        format!(".{}", extension.to_ascii_lowercase())
    } else {
        ".bin".to_owned()
    }
}

async fn classify_attachment(
    bytes: &[u8],
    claimed_type: &str,
) -> (String, Option<String>, bool, Option<(u32, u32)>) {
    if claimed_type.starts_with("image/")
        && let Ok(image) = process_image(bytes.to_vec()).await
    {
        return (
            image.content_type.to_owned(),
            Some(image.extension.to_owned()),
            true,
            Some((image.width, image.height)),
        );
    }
    if claimed_type == "application/pdf"
        && bytes.starts_with(b"%PDF-")
        && bytes.windows(5).any(|window| window == b"%%EOF")
    {
        return (
            "application/pdf".to_owned(),
            Some(".pdf".to_owned()),
            true,
            None,
        );
    }
    (claimed_type.to_owned(), None, false, None)
}

pub(crate) fn safe_original_filename(value: &str) -> String {
    let basename = value.rsplit(['/', '\\']).next().unwrap_or("file");
    let cleaned: String = basename
        .chars()
        .filter(|ch| !ch.is_control())
        .take(255)
        .collect();
    if cleaned.is_empty() || cleaned == "." || cleaned == ".." {
        "file".to_owned()
    } else {
        cleaned
    }
}

fn valid_subdir(subdir: &str) -> bool {
    matches!(subdir, "projects" | "yarns")
}

fn safe_segment(segment: &str) -> bool {
    !segment.is_empty()
        && segment != "."
        && segment != ".."
        && segment
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'-' | b'_'))
}

fn safe_filename(filename: &str) -> bool {
    !filename.is_empty()
        && filename != "."
        && filename != ".."
        && !filename.contains("..")
        && filename
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'.' | b'_' | b'-'))
}

fn safe_user_avatar_filename(filename: &str) -> bool {
    !filename.is_empty()
        && filename != "."
        && filename != ".."
        && !filename
            .chars()
            .any(|character| character.is_control() || matches!(character, '/' | '\\' | '\0'))
}

fn safe_disposition_filename(filename: &str) -> String {
    let cleaned: String = filename
        .chars()
        .filter(|character| {
            character.is_ascii_alphanumeric() || matches!(character, '.' | '_' | '-')
        })
        .take(128)
        .collect();
    if cleaned.is_empty() || cleaned == "." || cleaned == ".." {
        "avatar".to_owned()
    } else {
        cleaned
    }
}

fn safe_media_request_name(filename: &str) -> bool {
    safe_filename(filename)
        || (!filename.is_empty()
            && filename != "."
            && filename != ".."
            && !filename.contains("..")
            && !filename
                .chars()
                .any(|character| character.is_control() || matches!(character, '/' | '\\' | '\0')))
}

fn timestamp(value: DateTime<Utc>) -> String {
    value.to_rfc3339_opts(SecondsFormat::Micros, true)
}

fn newer_timestamp(current: &str) -> String {
    let current_micros = DateTime::parse_from_rfc3339(current)
        .map(|value| value.timestamp_micros())
        .unwrap_or(i64::MIN);
    let next_micros = Utc::now()
        .timestamp_micros()
        .max(current_micros.saturating_add(1));
    DateTime::<Utc>::from_timestamp_micros(next_micros)
        .map(timestamp)
        .unwrap_or_else(|| timestamp(Utc::now()))
}

fn opaque_filename(extension: &str) -> String {
    let mut random = [0_u8; 16];
    OsRng.fill_bytes(&mut random);
    format!("{}{}", hex::encode(random), extension)
}

async fn save_image_files(
    root: &Path,
    subdir: &str,
    parent_id: i64,
    image: &PreparedImage,
) -> Result<SavedFiles, std::io::Error> {
    let filename = opaque_filename(image.extension);
    save_files(
        root,
        subdir,
        parent_id,
        &filename,
        &image.bytes,
        Some(&image.thumbnail),
    )
    .await
}

async fn save_files(
    root: &Path,
    subdir: &str,
    parent_id: i64,
    filename: &str,
    bytes: &[u8],
    thumbnail: Option<&[u8]>,
) -> Result<SavedFiles, std::io::Error> {
    let parent_component = parent_id.to_string();
    let directory = ensure_directory(root, &[subdir, &parent_component]).await?;
    let file_path = directory.join(filename);
    write_staged(&directory, &file_path, bytes).await?;
    let thumbnail_path = if let Some(thumbnail) = thumbnail {
        let thumb_dir =
            match ensure_directory(root, &["thumbnails", subdir, &parent_component]).await {
                Ok(directory) => directory,
                Err(error) => {
                    let _ = remove_path_safe(root, &file_path).await;
                    return Err(error);
                }
            };
        let thumb_path = thumb_dir.join(thumbnail_name(filename));
        if let Err(error) = write_staged(&thumb_dir, &thumb_path, thumbnail).await {
            let _ = remove_path_safe(root, &file_path).await;
            return Err(error);
        }
        Some(thumb_path)
    } else {
        None
    };
    Ok(SavedFiles {
        filename: filename.to_owned(),
        thumbnail_path,
    })
}

async fn ensure_directory(root: &Path, segments: &[&str]) -> Result<PathBuf, std::io::Error> {
    tokio::fs::create_dir_all(root).await?;
    let canonical_root = tokio::fs::canonicalize(root).await?;
    let mut requested = canonical_root.clone();
    for segment in segments {
        if !safe_segment(segment) {
            return Err(std::io::Error::new(
                ErrorKind::InvalidInput,
                "unsafe media path",
            ));
        }
        requested.push(segment);
    }
    tokio::fs::create_dir_all(&requested).await?;
    let canonical_directory = tokio::fs::canonicalize(&requested).await?;
    if !canonical_directory.starts_with(&canonical_root) {
        return Err(std::io::Error::new(
            ErrorKind::PermissionDenied,
            "media path escaped root",
        ));
    }
    Ok(canonical_directory)
}

async fn write_staged(
    directory: &Path,
    destination: &Path,
    bytes: &[u8],
) -> Result<(), std::io::Error> {
    for _ in 0..4 {
        let stage_name = format!(".stage-{}.tmp", opaque_filename(""));
        let stage_path = directory.join(stage_name);
        let mut file = match tokio::fs::OpenOptions::new()
            .write(true)
            .create_new(true)
            .open(&stage_path)
            .await
        {
            Ok(file) => file,
            Err(error) if error.kind() == ErrorKind::AlreadyExists => continue,
            Err(error) => return Err(error),
        };
        if let Err(error) = file.write_all(bytes).await {
            drop(file);
            remove_path_unchecked(&stage_path).await;
            return Err(error);
        }
        drop(file);
        match tokio::fs::hard_link(&stage_path, destination).await {
            Ok(()) => {
                remove_path_unchecked(&stage_path).await;
                return Ok(());
            }
            Err(error) if error.kind() == ErrorKind::AlreadyExists => {
                remove_path_unchecked(&stage_path).await;
            }
            Err(error) => {
                remove_path_unchecked(&stage_path).await;
                return Err(error);
            }
        }
    }
    Err(std::io::Error::new(
        ErrorKind::AlreadyExists,
        "media filename collision",
    ))
}

async fn cleanup_saved_files(root: &Path, subdir: &str, parent_id: i64, files: &SavedFiles) {
    let _ = remove_stored_file(root, subdir, parent_id, &files.filename).await;
    if let Some(path) = &files.thumbnail_path {
        let _ = remove_path_safe(root, path).await;
    }
}

async fn existing_file_path(
    root: &Path,
    subdir: &str,
    parent_id: i64,
    filename: &str,
) -> Result<Option<PathBuf>, std::io::Error> {
    let parent_component = parent_id.to_string();
    safe_existing_path(root, &[subdir, &parent_component], filename).await
}

async fn thumbnail_path(
    root: &Path,
    subdir: &str,
    parent_id: i64,
    filename: &str,
) -> Result<Option<PathBuf>, std::io::Error> {
    let parent_component = parent_id.to_string();
    safe_existing_path(
        root,
        &["thumbnails", subdir, &parent_component],
        &thumbnail_name(filename),
    )
    .await
}

async fn user_avatar_path(
    root: &Path,
    user_id: i64,
    filename: &str,
    thumbnail: bool,
) -> Result<Option<PathBuf>, std::io::Error> {
    if user_id <= 0 || !safe_user_avatar_filename(filename) {
        return Err(std::io::Error::new(
            ErrorKind::InvalidInput,
            "unsafe avatar path",
        ));
    }
    let user_component = user_id.to_string();
    let stored_name = if thumbnail {
        thumbnail_name(filename)
    } else {
        filename.to_owned()
    };
    let segments = if thumbnail {
        vec!["thumbnails", "users", user_component.as_str()]
    } else {
        vec!["users", user_component.as_str()]
    };
    let canonical_root = tokio::fs::canonicalize(root).await?;
    let mut directory = canonical_root.clone();
    for segment in &segments {
        if !safe_segment(segment) {
            return Err(std::io::Error::new(
                ErrorKind::InvalidInput,
                "unsafe avatar directory",
            ));
        }
        directory.push(segment);
    }
    let canonical_directory = match tokio::fs::canonicalize(&directory).await {
        Ok(path) => path,
        Err(error) if error.kind() == ErrorKind::NotFound => return Ok(None),
        Err(error) => return Err(error),
    };
    if !canonical_directory.starts_with(&canonical_root) {
        return Err(std::io::Error::new(
            ErrorKind::PermissionDenied,
            "avatar directory escaped media root",
        ));
    }
    let candidate = canonical_directory.join(stored_name);
    let metadata = match tokio::fs::symlink_metadata(&candidate).await {
        Ok(metadata) => metadata,
        Err(error) if error.kind() == ErrorKind::NotFound => return Ok(None),
        Err(error) => return Err(error),
    };
    if metadata.file_type().is_symlink() || !metadata.is_file() {
        return Err(std::io::Error::new(
            ErrorKind::PermissionDenied,
            "refusing non-regular avatar file",
        ));
    }
    let canonical_file = tokio::fs::canonicalize(candidate).await?;
    if !canonical_file.starts_with(&canonical_root) {
        return Err(std::io::Error::new(
            ErrorKind::PermissionDenied,
            "avatar file escaped media root",
        ));
    }
    Ok(Some(canonical_file))
}

async fn safe_existing_path(
    root: &Path,
    segments: &[&str],
    filename: &str,
) -> Result<Option<PathBuf>, std::io::Error> {
    if !safe_filename(filename) || segments.iter().any(|segment| !safe_segment(segment)) {
        return Err(std::io::Error::new(
            ErrorKind::InvalidInput,
            "unsafe media path",
        ));
    }
    let canonical_root = tokio::fs::canonicalize(root).await?;
    let mut directory = canonical_root.clone();
    for segment in segments {
        directory.push(segment);
    }
    let canonical_directory = match tokio::fs::canonicalize(&directory).await {
        Ok(path) => path,
        Err(error) if error.kind() == ErrorKind::NotFound => return Ok(None),
        Err(error) => return Err(error),
    };
    if !canonical_directory.starts_with(&canonical_root) {
        return Err(std::io::Error::new(
            ErrorKind::PermissionDenied,
            "media path escaped root",
        ));
    }
    let candidate = canonical_directory.join(filename);
    let metadata = match tokio::fs::symlink_metadata(&candidate).await {
        Ok(metadata) => metadata,
        Err(error) if error.kind() == ErrorKind::NotFound => return Ok(None),
        Err(error) => return Err(error),
    };
    if metadata.file_type().is_symlink() || !metadata.is_file() {
        return Err(std::io::Error::new(
            ErrorKind::PermissionDenied,
            "refusing non-regular media file",
        ));
    }
    let canonical_file = tokio::fs::canonicalize(candidate).await?;
    if !canonical_file.starts_with(&canonical_root) {
        return Err(std::io::Error::new(
            ErrorKind::PermissionDenied,
            "media file escaped root",
        ));
    }
    Ok(Some(canonical_file))
}

async fn remove_stored_file(root: &Path, subdir: &str, parent_id: i64, filename: &str) -> bool {
    match existing_file_path(root, subdir, parent_id, filename).await {
        Ok(Some(path)) => remove_path_safe(root, &path).await.is_ok(),
        Ok(None) => true,
        Err(_) => false,
    }
}

async fn remove_stored_thumbnail(
    root: &Path,
    subdir: &str,
    parent_id: i64,
    filename: &str,
) -> bool {
    match thumbnail_path(root, subdir, parent_id, filename).await {
        Ok(Some(path)) => remove_path_safe(root, &path).await.is_ok(),
        Ok(None) => true,
        Err(_) => false,
    }
}

async fn remove_stored_avatar(root: &Path, user_id: i64, filename: &str) -> bool {
    let original_ok = match user_avatar_path(root, user_id, filename, false).await {
        Ok(Some(path)) => remove_path_safe(root, &path).await.is_ok(),
        Ok(None) => true,
        Err(_) => false,
    };
    let thumbnail_ok = match user_avatar_path(root, user_id, filename, true).await {
        Ok(Some(path)) => remove_path_safe(root, &path).await.is_ok(),
        Ok(None) => true,
        Err(_) => false,
    };
    original_ok && thumbnail_ok
}

async fn remove_path_safe(root: &Path, path: &Path) -> Result<(), std::io::Error> {
    let canonical_root = tokio::fs::canonicalize(root).await?;
    let metadata = match tokio::fs::symlink_metadata(path).await {
        Ok(metadata) => metadata,
        Err(error) if error.kind() == ErrorKind::NotFound => return Ok(()),
        Err(error) => return Err(error),
    };
    if metadata.file_type().is_symlink() || !metadata.is_file() {
        return Err(std::io::Error::new(
            ErrorKind::PermissionDenied,
            "refusing symlink media path",
        ));
    }
    let canonical_file = tokio::fs::canonicalize(path).await?;
    if !canonical_file.starts_with(&canonical_root) {
        return Err(std::io::Error::new(
            ErrorKind::PermissionDenied,
            "media path escaped root",
        ));
    }
    tokio::fs::remove_file(canonical_file).await
}

async fn remove_path_unchecked(path: &Path) {
    let _ = tokio::fs::remove_file(path).await;
}

#[cfg(test)]
mod admin_avatar_url_tests {
    use super::admin_avatar;

    #[test]
    fn admin_avatar_url_shapes_match_the_web_contract() {
        let gravatar = admin_avatar(7, None, true, true).map(|(url, _)| url);
        assert_eq!(
            gravatar.as_deref(),
            Some("/api/v2/admin/users/7/avatar/gravatar")
        );
        assert!(admin_avatar(7, None, false, true).is_none());
        assert!(admin_avatar(7, None, true, false).is_none());
        let upload = admin_avatar(7, Some("0123456789abcdef0123456789abcdef.png"), true, true)
            .map(|(url, _)| url)
            .expect("upload url");
        assert!(upload.starts_with("/api/v2/admin/users/7/avatar/thumb_"));
        assert!(upload.ends_with(".jpg"));
    }
}
