//! Authenticated, owner-scoped media uploads and serving.

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
use sqlx::{FromRow, Sqlite, SqlitePool, Transaction};
use tokio::io::AsyncWriteExt;

use crate::auth::{AuthError, bearer_user};
use crate::db::AppState;
use crate::media_ids::{self, Namespace};

const MAX_UPLOAD_BYTES: usize = 25 * 1024 * 1024;
const MAX_IMAGE_PIXELS: u64 = 64_000_000;
const MAX_IMAGE_DIMENSION: u32 = 20_000;
const MAX_IMAGE_ALLOCATION: u64 = 256 * 1024 * 1024;
const MAX_ALT_TEXT_BYTES: usize = 4096;
const GC_BATCH_SIZE: i64 = 100;

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
    is_safe_inline: bool,
    legacy_image_type: Option<String>,
    is_stitch_sample: Option<bool>,
    legacy_is_title_image: Option<bool>,
}

#[derive(Debug, Serialize)]
struct ImageResponse {
    id: i64,
    url: String,
    thumbnail_url: String,
    alt_text: String,
    image_type: String,
    is_title_image: bool,
    is_stitch_sample: bool,
    step_id: Option<i64>,
}

#[derive(Debug, Serialize)]
struct AttachmentResponse {
    id: i64,
    filename: String,
    original_filename: String,
    content_type: String,
    size_bytes: i64,
    url: String,
}

#[derive(Debug, Serialize)]
struct YarnPhotoResponse {
    id: i64,
    url: String,
    thumbnail_url: String,
    alt_text: String,
    is_primary: bool,
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
) -> Result<(), sqlx::Error> {
    let current = sqlx::query_scalar::<_, String>(
        "SELECT updated_at FROM projects WHERE id = ? AND owner_id = ?",
    )
    .bind(project_id)
    .bind(user_id)
    .fetch_optional(&mut **tx)
    .await?
    .ok_or_else(|| sqlx::Error::Protocol("owned project disappeared".to_owned()))?;
    sqlx::query("UPDATE projects SET updated_at = ? WHERE id = ? AND owner_id = ?")
        .bind(newer_timestamp(&current))
        .bind(project_id)
        .bind(user_id)
        .execute(&mut **tx)
        .await?;
    Ok(())
}

async fn bump_yarn_timestamp(
    tx: &mut Transaction<'_, Sqlite>,
    user_id: i64,
    yarn_id: i64,
) -> Result<(), sqlx::Error> {
    let current = sqlx::query_scalar::<_, String>(
        "SELECT updated_at FROM yarns WHERE id = ? AND owner_id = ?",
    )
    .bind(yarn_id)
    .bind(user_id)
    .fetch_optional(&mut **tx)
    .await?
    .ok_or_else(|| sqlx::Error::Protocol("owned yarn disappeared".to_owned()))?;
    sqlx::query("UPDATE yarns SET updated_at = ? WHERE id = ? AND owner_id = ?")
        .bind(newer_timestamp(&current))
        .bind(yarn_id)
        .bind(user_id)
        .execute(&mut **tx)
        .await?;
    Ok(())
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
    match project_exists(&state.pool, user_id, project_id).await {
        Ok(true) => {}
        Ok(false) => return not_found("Project not found"),
        Err(_) => return database_error(),
    }
    upload_project_image(&state, user_id, project_id, None, "title", multipart).await
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
    match step_belongs_to_project(&state.pool, user_id, project_id, step_id).await {
        Ok(true) => {}
        Ok(false) => return not_found("Step not found"),
        Err(_) => return database_error(),
    }
    upload_project_image(
        &state,
        user_id,
        project_id,
        Some(step_id),
        "step",
        multipart,
    )
    .await
}

async fn upload_project_image(
    state: &AppState,
    user_id: i64,
    project_id: i64,
    step_id: Option<i64>,
    kind: &'static str,
    multipart: Multipart,
) -> Response {
    let root = match media_root(state) {
        Ok(root) => root,
        Err(error) => return error.into_response(),
    };
    let upload = match read_upload(multipart, true).await {
        Ok(upload) => upload,
        Err(error) => return upload_error(error),
    };
    let prepared = match process_image(upload.bytes).await {
        Ok(image) => image,
        Err(error) => return upload_error(error),
    };
    let alt_text = if upload.alt_text.trim().is_empty() {
        upload.original_filename.clone()
    } else {
        upload.alt_text.trim().to_owned()
    };
    let files = match save_image_files(&root, "projects", project_id, &prepared).await {
        Ok(files) => files,
        Err(_) => return database_error(),
    };

    let mut tx = match state.pool.begin().await {
        Ok(tx) => tx,
        Err(_) => {
            cleanup_saved_files(&root, "projects", project_id, &files).await;
            return database_error();
        }
    };
    match verify_image_target(&mut tx, user_id, project_id, step_id).await {
        Ok(true) => {}
        Ok(false) => {
            let _ = tx.rollback().await;
            cleanup_saved_files(&root, "projects", project_id, &files).await;
            return not_found(if step_id.is_some() {
                "Step not found"
            } else {
                "Project not found"
            });
        }
        Err(_) => {
            let _ = tx.rollback().await;
            cleanup_saved_files(&root, "projects", project_id, &files).await;
            return database_error();
        }
    }
    let is_primary = if kind == "title" {
        match sqlx::query_scalar::<_, i64>(
            "SELECT EXISTS(SELECT 1 FROM media_files \
             WHERE project_id = ? AND kind = 'title' AND is_primary = 1)",
        )
        .bind(project_id)
        .fetch_one(&mut *tx)
        .await
        {
            Ok(exists) => exists == 0,
            Err(_) => {
                let _ = tx.rollback().await;
                cleanup_saved_files(&root, "projects", project_id, &files).await;
                return database_error();
            }
        }
    } else {
        false
    };
    if bump_project_timestamp(&mut tx, user_id, project_id)
        .await
        .is_err()
    {
        let _ = tx.rollback().await;
        cleanup_saved_files(&root, "projects", project_id, &files).await;
        return database_error();
    }
    let public_id = match media_ids::allocate(&mut tx, Namespace::Image).await {
        Ok(public_id) => public_id,
        Err(_) => {
            let _ = tx.rollback().await;
            cleanup_saved_files(&root, "projects", project_id, &files).await;
            return database_error();
        }
    };
    let insert = sqlx::query(
        "INSERT INTO media_files \
         (owner_id, project_id, step_id, kind, public_id, filename, original_filename, content_type, \
          size_bytes, width, height, alt_text, is_primary, created_at) \
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
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
    .bind(Utc::now().to_rfc3339_opts(SecondsFormat::Micros, true))
    .execute(&mut *tx)
    .await;
    let media_id = match insert {
        Ok(result) => result.last_insert_rowid(),
        Err(_) => {
            let _ = tx.rollback().await;
            cleanup_saved_files(&root, "projects", project_id, &files).await;
            return database_error();
        }
    };
    if tx.commit().await.is_err() {
        cleanup_saved_files(&root, "projects", project_id, &files).await;
        return database_error();
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
        is_safe_inline: true,
        legacy_image_type: None,
        is_stitch_sample: None,
        legacy_is_title_image: None,
    };
    json_response(StatusCode::CREATED, image_dto(&row))
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
    match project_exists(&state.pool, user_id, project_id).await {
        Ok(true) => {}
        Ok(false) => return not_found("Project not found"),
        Err(_) => return database_error(),
    }
    let root = match media_root(&state) {
        Ok(root) => root,
        Err(error) => return error.into_response(),
    };
    let upload = match read_upload(multipart, false).await {
        Ok(upload) => upload,
        Err(error) => return upload_error(error),
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
        Err(_) => return database_error(),
    };
    let mut tx = match state.pool.begin().await {
        Ok(tx) => tx,
        Err(_) => {
            cleanup_saved_files(&root, "projects", project_id, &files).await;
            return database_error();
        }
    };
    match verify_project_target(&mut tx, user_id, project_id).await {
        Ok(true) => {}
        Ok(false) => {
            let _ = tx.rollback().await;
            cleanup_saved_files(&root, "projects", project_id, &files).await;
            return not_found("Project not found");
        }
        Err(_) => {
            let _ = tx.rollback().await;
            cleanup_saved_files(&root, "projects", project_id, &files).await;
            return database_error();
        }
    }
    if bump_project_timestamp(&mut tx, user_id, project_id)
        .await
        .is_err()
    {
        let _ = tx.rollback().await;
        cleanup_saved_files(&root, "projects", project_id, &files).await;
        return database_error();
    }
    let public_id = match media_ids::allocate(&mut tx, Namespace::Attachment).await {
        Ok(public_id) => public_id,
        Err(_) => {
            let _ = tx.rollback().await;
            cleanup_saved_files(&root, "projects", project_id, &files).await;
            return database_error();
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
            return database_error();
        }
    };
    if tx.commit().await.is_err() {
        cleanup_saved_files(&root, "projects", project_id, &files).await;
        return database_error();
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
        is_safe_inline,
        legacy_image_type: None,
        is_stitch_sample: None,
        legacy_is_title_image: None,
    };
    json_response(StatusCode::CREATED, attachment_dto(&row))
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
    let yarn_name = match yarn_name(&state.pool, user_id, yarn_id).await {
        Ok(Some(name)) => name,
        Ok(None) => return not_found("Yarn not found"),
        Err(_) => return database_error(),
    };
    let root = match media_root(&state) {
        Ok(root) => root,
        Err(error) => return error.into_response(),
    };
    let upload = match read_upload(multipart, false).await {
        Ok(upload) => upload,
        Err(error) => return upload_error(error),
    };
    let prepared = match process_image(upload.bytes).await {
        Ok(image) => image,
        Err(error) => return upload_error(error),
    };
    let files = match save_image_files(&root, "yarns", yarn_id, &prepared).await {
        Ok(files) => files,
        Err(_) => return database_error(),
    };
    let mut tx = match state.pool.begin().await {
        Ok(tx) => tx,
        Err(_) => {
            cleanup_saved_files(&root, "yarns", yarn_id, &files).await;
            return database_error();
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
            return not_found("Yarn not found");
        }
        Err(_) => {
            let _ = tx.rollback().await;
            cleanup_saved_files(&root, "yarns", yarn_id, &files).await;
            return database_error();
        }
    }
    let primary = sqlx::query_scalar::<_, i64>(
        "SELECT EXISTS(SELECT 1 FROM media_files WHERE yarn_id = ? AND kind = 'photo' \
         AND is_primary = 1)",
    )
    .bind(yarn_id)
    .fetch_one(&mut *tx)
    .await;
    let is_primary = match primary {
        Ok(exists) => exists == 0,
        Err(_) => {
            let _ = tx.rollback().await;
            cleanup_saved_files(&root, "yarns", yarn_id, &files).await;
            return database_error();
        }
    };
    if bump_yarn_timestamp(&mut tx, user_id, yarn_id)
        .await
        .is_err()
    {
        let _ = tx.rollback().await;
        cleanup_saved_files(&root, "yarns", yarn_id, &files).await;
        return database_error();
    }
    let public_id = match media_ids::allocate(&mut tx, Namespace::Photo).await {
        Ok(public_id) => public_id,
        Err(_) => {
            let _ = tx.rollback().await;
            cleanup_saved_files(&root, "yarns", yarn_id, &files).await;
            return database_error();
        }
    };
    let insert = sqlx::query(
        "INSERT INTO media_files \
         (owner_id, yarn_id, kind, public_id, filename, original_filename, content_type, size_bytes, \
          width, height, alt_text, is_primary, created_at) \
         VALUES (?, ?, 'photo', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
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
    .bind(Utc::now().to_rfc3339_opts(SecondsFormat::Micros, true))
    .execute(&mut *tx)
    .await;
    let media_id = match insert {
        Ok(result) => result.last_insert_rowid(),
        Err(_) => {
            let _ = tx.rollback().await;
            cleanup_saved_files(&root, "yarns", yarn_id, &files).await;
            return database_error();
        }
    };
    if tx.commit().await.is_err() {
        cleanup_saved_files(&root, "yarns", yarn_id, &files).await;
        return database_error();
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
        is_safe_inline: true,
        legacy_image_type: None,
        is_stitch_sample: None,
        legacy_is_title_image: None,
    };
    json_response(StatusCode::CREATED, yarn_photo_dto(&row))
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
    delete_project_media(&state, user_id, project_id, image_id, Namespace::Image).await
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
    delete_project_media(
        &state,
        user_id,
        project_id,
        attachment_id,
        Namespace::Attachment,
    )
    .await
}

async fn delete_project_media(
    state: &AppState,
    user_id: i64,
    project_id: i64,
    media_id: i64,
    namespace: Namespace,
) -> Response {
    let mut tx = match state.pool.begin().await {
        Ok(tx) => tx,
        Err(_) => return database_error(),
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
            return not_found(if namespace == Namespace::Attachment {
                "Attachment not found"
            } else {
                "Image not found"
            });
        }
        Err(_) => {
            let _ = tx.rollback().await;
            return database_error();
        }
    };
    if bump_project_timestamp(&mut tx, user_id, project_id)
        .await
        .is_err()
    {
        let _ = tx.rollback().await;
        return database_error();
    }
    if sqlx::query("DELETE FROM media_files WHERE id = ?")
        .bind(row.id)
        .execute(&mut *tx)
        .await
        .is_err()
    {
        let _ = tx.rollback().await;
        return database_error();
    }
    if tx.commit().await.is_err() {
        return database_error();
    }
    let _ = cleanup_pending(state).await;
    StatusCode::NO_CONTENT.into_response()
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
    let mut tx = match state.pool.begin().await {
        Ok(tx) => tx,
        Err(_) => return database_error(),
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
            return not_found("Photo not found");
        }
        Err(_) => {
            let _ = tx.rollback().await;
            return database_error();
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
            return not_found("Photo not found");
        }
        Err(_) => {
            let _ = tx.rollback().await;
            return database_error();
        }
    }
    if bump_yarn_timestamp(&mut tx, user_id, yarn_id)
        .await
        .is_err()
    {
        let _ = tx.rollback().await;
        return database_error();
    }
    if sqlx::query("DELETE FROM media_files WHERE id = ?")
        .bind(photo.id)
        .execute(&mut *tx)
        .await
        .is_err()
    {
        let _ = tx.rollback().await;
        return database_error();
    }
    if photo.is_primary {
        let next = sqlx::query_scalar::<_, i64>(
            "SELECT id FROM media_files WHERE yarn_id = ? AND kind = 'photo' \
             ORDER BY id LIMIT 1",
        )
        .bind(yarn_id)
        .fetch_optional(&mut *tx)
        .await;
        match next {
            Ok(Some(id)) => {
                if sqlx::query("UPDATE media_files SET is_primary = 1 WHERE id = ?")
                    .bind(id)
                    .execute(&mut *tx)
                    .await
                    .is_err()
                {
                    let _ = tx.rollback().await;
                    return database_error();
                }
            }
            Ok(None) => {}
            Err(_) => {
                let _ = tx.rollback().await;
                return database_error();
            }
        }
    }
    if tx.commit().await.is_err() {
        return database_error();
    }
    let _ = cleanup_pending(&state).await;
    StatusCode::NO_CONTENT.into_response()
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
        is_title_image: row.legacy_is_title_image.unwrap_or(row.kind == "title"),
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

fn safe_original_filename(value: &str) -> String {
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
