//! Owner-scoped project endpoints for API v1.

use std::collections::HashSet;

use axum::extract::{Path, Query, State};
use axum::http::{HeaderMap, StatusCode};
use axum::response::{IntoResponse, Response};
use axum::routing::{get, post};
use axum::{Json, Router};
use chrono::{DateTime, Duration, SecondsFormat, Utc};
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use sqlx::{FromRow, Sqlite, Transaction};

use crate::audit::{Action as AuditAction, Entity as AuditEntity};
use crate::auth::bearer_user;
use crate::db::AppState;

const PAGE_SIZE: i64 = 50;

/// Build the project endpoints, to be nested under `/api/v1`.
pub fn router() -> Router<AppState> {
    Router::new()
        .route("/projects", get(list_projects).post(create_project))
        .route(
            "/projects/{project_id}",
            get(get_project).put(update_project).delete(delete_project),
        )
        .route(
            "/projects/{project_id}/favorite",
            post(favorite_project).delete(unfavorite_project),
        )
}

#[derive(Debug, Deserialize)]
pub(crate) struct ListQuery {
    pub(crate) page: Option<i64>,
    pub(crate) category: Option<String>,
    pub(crate) tag: Option<String>,
    pub(crate) favorite: Option<bool>,
    pub(crate) q: Option<String>,
}

#[derive(Debug, Deserialize)]
pub(crate) struct ProjectWriteRequest {
    pub(crate) name: String,
    #[serde(default)]
    pub(crate) category: Option<String>,
    #[serde(default)]
    pub(crate) needles: Option<String>,
    #[serde(default)]
    pub(crate) stitch_sample: Option<String>,
    #[serde(default)]
    pub(crate) description: Option<String>,
    #[serde(default)]
    pub(crate) notes: Option<String>,
    #[serde(default)]
    pub(crate) other_materials: Option<String>,
    #[serde(default)]
    pub(crate) tags: Vec<String>,
    #[serde(default)]
    pub(crate) link: Option<String>,
    #[serde(default)]
    pub(crate) is_ai_enhanced: bool,
    #[serde(default)]
    pub(crate) yarn_ids: Vec<i64>,
    #[serde(default)]
    pub(crate) steps: Vec<StepWriteRequest>,
    #[serde(default)]
    pub(crate) image_urls: Vec<String>,
    #[serde(default)]
    pub(crate) expected_updated_at: Option<String>,
}

#[derive(Debug, Deserialize)]
pub(crate) struct StepWriteRequest {
    #[serde(default)]
    pub(crate) id: Option<i64>,
    pub(crate) title: String,
    #[serde(default)]
    pub(crate) description: Option<String>,
    #[serde(default)]
    pub(crate) step_number: i64,
    #[serde(default)]
    pub(crate) image_urls: Vec<String>,
}

#[derive(Debug, Serialize)]
pub(crate) struct ProjectPage {
    pub(crate) items: Vec<ProjectListItem>,
    pub(crate) page: i64,
    pub(crate) per_page: i64,
    pub(crate) has_more: bool,
}

#[derive(Debug, Serialize)]
pub(crate) struct ProjectListItem {
    pub(crate) id: i64,
    pub(crate) name: String,
    pub(crate) category: Option<String>,
    pub(crate) tags: Vec<String>,
    pub(crate) is_favorite: bool,
    pub(crate) updated_at: String,
    pub(crate) preview_url: Option<String>,
}

#[derive(Debug, Serialize, Clone)]
pub(crate) struct ProjectResponse {
    pub(crate) id: i64,
    pub(crate) name: String,
    pub(crate) category: Option<String>,
    pub(crate) yarn: Option<String>,
    pub(crate) needles: Option<String>,
    pub(crate) stitch_sample: Option<String>,
    pub(crate) description: Option<String>,
    pub(crate) notes: Option<String>,
    pub(crate) other_materials: Option<String>,
    pub(crate) tags: Vec<String>,
    pub(crate) link: Option<String>,
    pub(crate) link_archive: Option<String>,
    pub(crate) is_ai_enhanced: bool,
    pub(crate) is_favorite: bool,
    pub(crate) created_at: String,
    pub(crate) updated_at: String,
    pub(crate) yarn_ids: Vec<i64>,
    pub(crate) steps: Vec<StepResponse>,
    // Media upload/import routes land in a later slice; this DTO returns empty arrays meanwhile.
    pub(crate) images: Vec<Value>,
    pub(crate) attachments: Vec<Value>,
}

#[derive(Debug, Serialize, Clone)]
pub(crate) struct StepResponse {
    pub(crate) id: i64,
    pub(crate) title: String,
    pub(crate) description: Option<String>,
    pub(crate) step_number: i64,
}

pub(crate) enum ProjectError {
    NotFound,
    Database,
    Invalid(&'static str),
    Conflict(Box<ProjectResponse>),
    ImageImportUnavailable,
}

/// Tag parsing mode at the API boundary: v1 keeps its tokenized labels while
/// browser v2 preserves each JSON array item as a label.
#[derive(Debug, Clone, Copy)]
pub(crate) enum TagPolicy {
    Legacy,
    PreserveLabels,
}

impl ProjectError {
    fn into_v1_response(self) -> Response {
        match self {
            Self::NotFound => not_found(),
            Self::Database => database_error(),
            Self::Invalid(detail) => invalid_project(detail),
            Self::Conflict(project) => conflict(*project),
            Self::ImageImportUnavailable => image_import_not_implemented(),
        }
    }
}

#[derive(Debug, FromRow)]
struct ProjectRow {
    id: i64,
    name: String,
    category: Option<String>,
    needles: Option<String>,
    stitch_sample: Option<String>,
    description: Option<String>,
    notes: Option<String>,
    other_materials: Option<String>,
    tags: String,
    link: Option<String>,
    link_archive: Option<String>,
    is_ai_enhanced: bool,
    created_at: String,
    updated_at: String,
}

#[derive(Debug, FromRow)]
struct ProjectListRow {
    id: i64,
    name: String,
    category: Option<String>,
    tags: String,
    is_favorite: bool,
    updated_at: String,
}

#[derive(Debug, FromRow)]
struct StepRow {
    id: i64,
    title: String,
    description: Option<String>,
    step_number: i64,
}

#[derive(Debug, Serialize)]
struct ErrorResponse {
    detail: Value,
}

async fn list_projects(
    State(state): State<AppState>,
    headers: HeaderMap,
    query: Result<Query<ListQuery>, axum::extract::rejection::QueryRejection>,
) -> Response {
    let user_id = match bearer_user(&state.pool, &headers).await {
        Ok(user_id) => user_id,
        Err(error) => return error.into_response(),
    };
    let Query(query) = match query {
        Ok(query) => query,
        Err(_) => return error_response(StatusCode::UNPROCESSABLE_ENTITY, json!("Invalid query")),
    };
    match list_for_owner(&state, user_id, query).await {
        Ok(page) => Json(page).into_response(),
        Err(ProjectError::Invalid(detail)) => {
            error_response(StatusCode::UNPROCESSABLE_ENTITY, json!(detail))
        }
        Err(ProjectError::Database) => database_error(),
        Err(_) => database_error(),
    }
}

pub(crate) async fn list_for_owner(
    state: &AppState,
    user_id: i64,
    query: ListQuery,
) -> Result<ProjectPage, ProjectError> {
    list_for_owner_with_tag_policy(state, user_id, query, TagPolicy::Legacy).await
}

pub(crate) async fn list_for_owner_with_tag_policy(
    state: &AppState,
    user_id: i64,
    query: ListQuery,
    tag_policy: TagPolicy,
) -> Result<ProjectPage, ProjectError> {
    let page = query.page.unwrap_or(1);
    if page < 1 {
        return Err(ProjectError::Invalid("Invalid page"));
    }
    let category = query.category.filter(|value| !value.is_empty());
    let tag = query.tag.filter(|value| !value.is_empty());
    if query.q.as_ref().is_some_and(|value| value.len() > 256) {
        return Err(ProjectError::Invalid("Search query is too long"));
    }
    let search = query
        .q
        .map(|value| value.trim().to_owned())
        .filter(|value| !value.is_empty());
    let search_pattern = search.as_deref().map(literal_like_pattern);
    let favorite = query.favorite.map(i64::from);
    let rows = sqlx::query_as::<_, ProjectListRow>(
        "SELECT p.id, p.name, c.name AS category, p.tags, \
                EXISTS(SELECT 1 FROM user_favorites f \
                       WHERE f.user_id = ? AND f.project_id = p.id) AS is_favorite, \
                p.updated_at \
         FROM projects p LEFT JOIN categories c ON c.id = p.category_id \
         WHERE p.owner_id = ? \
           AND (? IS NULL OR c.name = ?) \
           AND (? IS NULL OR instr(lower(p.tags), lower(?)) > 0) \
           AND (? IS NULL OR p.name LIKE ? ESCAPE '!' \
                OR COALESCE(c.name, '') LIKE ? ESCAPE '!' \
                OR p.tags LIKE ? ESCAPE '!' \
                OR COALESCE(p.notes, '') LIKE ? ESCAPE '!') \
           AND (? IS NULL OR EXISTS(SELECT 1 FROM user_favorites f \
                                    WHERE f.user_id = ? AND f.project_id = p.id) = ?) \
         ORDER BY p.updated_at DESC, p.id DESC LIMIT ? OFFSET ?",
    )
    .bind(user_id)
    .bind(user_id)
    .bind(category.as_deref())
    .bind(category.as_deref())
    .bind(tag.as_deref())
    .bind(tag.as_deref())
    .bind(search_pattern.as_deref())
    .bind(search_pattern.as_deref())
    .bind(search_pattern.as_deref())
    .bind(search_pattern.as_deref())
    .bind(search_pattern.as_deref())
    .bind(favorite)
    .bind(user_id)
    .bind(favorite)
    .bind(PAGE_SIZE + 1)
    .bind((page - 1).saturating_mul(PAGE_SIZE))
    .fetch_all(&state.pool)
    .await;
    let rows = rows.map_err(|_| ProjectError::Database)?;
    let has_more = rows.len() > PAGE_SIZE as usize;
    let mut items = Vec::new();
    for row in rows.into_iter().take(PAGE_SIZE as usize) {
        let (images, _) = match crate::media::project_media(&state.pool, user_id, row.id).await {
            Ok(media) => media,
            Err(_) => return Err(ProjectError::Database),
        };
        items.push(ProjectListItem {
            id: row.id,
            name: row.name,
            category: row.category,
            tags: decode_tags(&row.tags, tag_policy),
            is_favorite: row.is_favorite,
            updated_at: row.updated_at,
            preview_url: crate::media::project_preview(&images),
        });
    }

    Ok(ProjectPage {
        items,
        page,
        per_page: PAGE_SIZE,
        has_more,
    })
}

fn literal_like_pattern(value: &str) -> String {
    format!(
        "%{}%",
        value
            .replace('!', "!!")
            .replace('%', "!%")
            .replace('_', "!_")
    )
}

async fn get_project(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(project_id): Path<i64>,
) -> Response {
    let user_id = match bearer_user(&state.pool, &headers).await {
        Ok(user_id) => user_id,
        Err(error) => return error.into_response(),
    };
    match load_project(&state, user_id, project_id).await {
        Ok(Some(project)) => Json(project).into_response(),
        Ok(None) => not_found(),
        Err(_) => database_error(),
    }
}

async fn create_project(
    State(state): State<AppState>,
    headers: HeaderMap,
    Json(payload): Json<ProjectWriteRequest>,
) -> Response {
    let user_id = match bearer_user(&state.pool, &headers).await {
        Ok(user_id) => user_id,
        Err(error) => return error.into_response(),
    };
    match create_for_owner(&state, user_id, payload).await {
        Ok(project) => (StatusCode::CREATED, Json(project)).into_response(),
        Err(error) => error.into_v1_response(),
    }
}

pub(crate) async fn create_for_owner(
    state: &AppState,
    user_id: i64,
    payload: ProjectWriteRequest,
) -> Result<ProjectResponse, ProjectError> {
    create_for_owner_with_tag_policy(state, user_id, payload, TagPolicy::Legacy).await
}

pub(crate) async fn create_for_owner_with_tag_policy(
    state: &AppState,
    user_id: i64,
    payload: ProjectWriteRequest,
    tag_policy: TagPolicy,
) -> Result<ProjectResponse, ProjectError> {
    if payload_has_image_urls(&payload) && (!state.external_imports || state.media_root.is_none()) {
        return Err(if state.external_imports {
            ProjectError::Database
        } else {
            ProjectError::ImageImportUnavailable
        });
    }
    let step_image_urls = payload
        .steps
        .iter()
        .map(|step| step.image_urls.clone())
        .collect::<Vec<_>>();
    let prepared_images = if state.external_imports && state.media_root.is_some() {
        crate::image_import::prepare_project_images(
            state,
            payload.name.trim(),
            &payload.image_urls,
            &step_image_urls,
        )
        .await
    } else {
        Vec::new()
    };
    let name = payload.name.trim().to_owned();
    let mut transaction = state
        .pool
        .begin()
        .await
        .map_err(|_| ProjectError::Database)?;
    let yarn_ids = owned_yarn_ids(&mut transaction, user_id, &payload.yarn_ids)
        .await
        .map_err(|_| ProjectError::Database)?;
    let category_id = ensure_category(&mut transaction, user_id, payload.category.as_deref())
        .await
        .map_err(|_| ProjectError::Database)?;
    let now = timestamp(Utc::now());
    let tags = encode_tags(&payload.tags, tag_policy);
    let inserted = sqlx::query(
        "INSERT INTO projects (owner_id, category_id, name, needles, stitch_sample, \
         description, notes, other_materials, tags, link, is_ai_enhanced, created_at, updated_at) \
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
    )
    .bind(user_id)
    .bind(category_id)
    .bind(name)
    .bind(cleaned_optional(payload.needles))
    .bind(cleaned_optional(payload.stitch_sample))
    .bind(cleaned_optional(payload.description))
    .bind(cleaned_optional(payload.notes))
    .bind(cleaned_optional(payload.other_materials))
    .bind(tags)
    .bind(cleaned_optional(payload.link))
    .bind(payload.is_ai_enhanced)
    .bind(&now)
    .bind(&now)
    .execute(&mut *transaction)
    .await;
    let project_id = inserted
        .map_err(|_| ProjectError::Database)?
        .last_insert_rowid();
    if insert_project_yarns(&mut transaction, project_id, &yarn_ids)
        .await
        .is_err()
        || bump_yarn_timestamps(&mut transaction, user_id, &yarn_ids)
            .await
            .is_err()
    {
        return Err(ProjectError::Database);
    }
    let step_ids = insert_steps(&mut transaction, project_id, &payload.steps)
        .await
        .map_err(|_| ProjectError::Database)?;
    if crate::audit::record(
        &mut transaction,
        user_id,
        AuditEntity::Project,
        project_id,
        AuditAction::Created,
    )
    .await
    .is_err()
    {
        return Err(ProjectError::Database);
    }
    if state.archive_enabled
        && crate::archive_jobs::enqueue_in_transaction(
            &mut transaction,
            user_id,
            crate::archive_jobs::ArchiveEntityKind::Project,
            project_id,
        )
        .await
        .is_err()
    {
        return Err(ProjectError::Database);
    }
    if transaction.commit().await.is_err() {
        return Err(ProjectError::Database);
    }
    if !prepared_images.is_empty() {
        crate::image_import::persist_project_images(
            state,
            user_id,
            project_id,
            &step_ids,
            prepared_images,
        )
        .await;
    }

    load_project_with_tag_policy(state, user_id, project_id, tag_policy)
        .await
        .map_err(|_| ProjectError::Database)?
        .ok_or(ProjectError::Database)
}

async fn update_project(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(project_id): Path<i64>,
    Json(payload): Json<ProjectWriteRequest>,
) -> Response {
    let user_id = match bearer_user(&state.pool, &headers).await {
        Ok(user_id) => user_id,
        Err(error) => return error.into_response(),
    };
    match update_for_owner(&state, user_id, project_id, payload).await {
        Ok(project) => Json(project).into_response(),
        Err(error) => error.into_v1_response(),
    }
}

pub(crate) async fn update_for_owner(
    state: &AppState,
    user_id: i64,
    project_id: i64,
    payload: ProjectWriteRequest,
) -> Result<ProjectResponse, ProjectError> {
    update_for_owner_with_tag_policy(state, user_id, project_id, payload, TagPolicy::Legacy).await
}

pub(crate) async fn update_for_owner_with_tag_policy(
    state: &AppState,
    user_id: i64,
    project_id: i64,
    payload: ProjectWriteRequest,
    tag_policy: TagPolicy,
) -> Result<ProjectResponse, ProjectError> {
    let name = payload.name.trim().to_owned();
    let expected_updated_at = match payload.expected_updated_at.as_deref() {
        Some(value) => Some(
            parse_timestamp(value).ok_or(ProjectError::Invalid("Invalid expected_updated_at"))?,
        ),
        None => None,
    };
    let current = load_project_with_tag_policy(state, user_id, project_id, tag_policy)
        .await
        .map_err(|_| ProjectError::Database)?
        .ok_or(ProjectError::NotFound)?;
    if expected_updated_at.is_some_and(|expected| {
        parse_timestamp(&current.updated_at).is_some_and(|actual| actual != expected)
    }) {
        return Err(ProjectError::Conflict(Box::new(current)));
    }

    let current_step_ids = project_step_ids(state, project_id)
        .await
        .map_err(|_| ProjectError::Database)?;
    let mut transaction = state
        .pool
        .begin()
        .await
        .map_err(|_| ProjectError::Database)?;
    let old_yarn_ids = linked_yarn_ids(&mut transaction, project_id)
        .await
        .map_err(|_| ProjectError::Database)?;
    let yarn_ids = owned_yarn_ids(&mut transaction, user_id, &payload.yarn_ids)
        .await
        .map_err(|_| ProjectError::Database)?;
    let category_id = ensure_category(&mut transaction, user_id, payload.category.as_deref())
        .await
        .map_err(|_| ProjectError::Database)?;
    let updated_at = next_timestamp(&current.updated_at).ok_or(ProjectError::Database)?;
    let tags = encode_tags(&payload.tags, tag_policy);
    let updated = sqlx::query(
        "UPDATE projects SET category_id = ?, name = ?, needles = ?, stitch_sample = ?, \
         description = ?, notes = ?, other_materials = ?, tags = ?, link = ?, \
         is_ai_enhanced = ?, updated_at = ? \
         WHERE id = ? AND owner_id = ? AND updated_at = ?",
    )
    .bind(category_id)
    .bind(name)
    .bind(cleaned_optional(payload.needles))
    .bind(cleaned_optional(payload.stitch_sample))
    .bind(cleaned_optional(payload.description))
    .bind(cleaned_optional(payload.notes))
    .bind(cleaned_optional(payload.other_materials))
    .bind(tags)
    .bind(cleaned_optional(payload.link))
    .bind(payload.is_ai_enhanced)
    .bind(updated_at)
    .bind(project_id)
    .bind(user_id)
    .bind(&current.updated_at)
    .execute(&mut *transaction)
    .await;
    match updated {
        Ok(result) if result.rows_affected() == 1 => {}
        Ok(_) => {
            let _ = transaction.rollback().await;
            return match load_project_with_tag_policy(state, user_id, project_id, tag_policy).await
            {
                Ok(Some(project)) => Err(ProjectError::Conflict(Box::new(project))),
                Ok(None) => Err(ProjectError::NotFound),
                Err(_) => Err(ProjectError::Database),
            };
        }
        Err(_) => return Err(ProjectError::Database),
    }
    let changed_yarn_ids = changed_ids(&old_yarn_ids, &yarn_ids);
    if replace_project_yarns(&mut transaction, project_id, &yarn_ids)
        .await
        .is_err()
        || bump_yarn_timestamps(&mut transaction, user_id, &changed_yarn_ids)
            .await
            .is_err()
        || replace_steps(
            &mut transaction,
            project_id,
            &current_step_ids,
            &payload.steps,
        )
        .await
        .is_err()
    {
        return Err(ProjectError::Database);
    }
    if crate::audit::record(
        &mut transaction,
        user_id,
        AuditEntity::Project,
        project_id,
        AuditAction::Updated,
    )
    .await
    .is_err()
    {
        return Err(ProjectError::Database);
    }
    if state.archive_enabled
        && crate::archive_jobs::enqueue_in_transaction(
            &mut transaction,
            user_id,
            crate::archive_jobs::ArchiveEntityKind::Project,
            project_id,
        )
        .await
        .is_err()
    {
        return Err(ProjectError::Database);
    }
    if transaction.commit().await.is_err() {
        return Err(ProjectError::Database);
    }
    load_project_with_tag_policy(state, user_id, project_id, tag_policy)
        .await
        .map_err(|_| ProjectError::Database)?
        .ok_or(ProjectError::NotFound)
}

async fn delete_project(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(project_id): Path<i64>,
) -> Response {
    let user_id = match bearer_user(&state.pool, &headers).await {
        Ok(user_id) => user_id,
        Err(error) => return error.into_response(),
    };
    match delete_for_owner(&state, user_id, project_id).await {
        Ok(()) => StatusCode::NO_CONTENT.into_response(),
        Err(error) => error.into_v1_response(),
    }
}

pub(crate) async fn delete_for_owner(
    state: &AppState,
    user_id: i64,
    project_id: i64,
) -> Result<(), ProjectError> {
    let mut transaction = state
        .pool
        .begin()
        .await
        .map_err(|_| ProjectError::Database)?;
    let exists =
        sqlx::query_scalar::<_, i64>("SELECT id FROM projects WHERE id = ? AND owner_id = ?")
            .bind(project_id)
            .bind(user_id)
            .fetch_optional(&mut *transaction)
            .await;
    match exists {
        Ok(Some(_)) => {}
        Ok(None) => return Err(ProjectError::NotFound),
        Err(_) => return Err(ProjectError::Database),
    }
    let linked_yarn_ids = match linked_yarn_ids(&mut transaction, project_id).await {
        Ok(ids) => ids,
        Err(_) => return Err(ProjectError::Database),
    };
    if bump_yarn_timestamps(&mut transaction, user_id, &linked_yarn_ids)
        .await
        .is_err()
    {
        return Err(ProjectError::Database);
    }
    let changed_at = timestamp(Utc::now());
    if sqlx::query(
        "INSERT INTO deletion_events (owner_id, entity_type, entity_id, changed_at) \
         VALUES (?, 'project', ?, ?)",
    )
    .bind(user_id)
    .bind(project_id)
    .bind(changed_at)
    .execute(&mut *transaction)
    .await
    .is_err()
    {
        return Err(ProjectError::Database);
    }
    match sqlx::query("DELETE FROM projects WHERE id = ? AND owner_id = ?")
        .bind(project_id)
        .bind(user_id)
        .execute(&mut *transaction)
        .await
    {
        Ok(result) if result.rows_affected() == 1 => {}
        Ok(_) => return Err(ProjectError::NotFound),
        Err(_) => return Err(ProjectError::Database),
    }
    if crate::audit::record(
        &mut transaction,
        user_id,
        AuditEntity::Project,
        project_id,
        AuditAction::Deleted,
    )
    .await
    .is_err()
    {
        return Err(ProjectError::Database);
    }
    transaction
        .commit()
        .await
        .map_err(|_| ProjectError::Database)
}

async fn favorite_project(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(project_id): Path<i64>,
) -> Response {
    set_favorite(state, headers, project_id, true).await
}

async fn unfavorite_project(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(project_id): Path<i64>,
) -> Response {
    set_favorite(state, headers, project_id, false).await
}

async fn set_favorite(
    state: AppState,
    headers: HeaderMap,
    project_id: i64,
    favorite: bool,
) -> Response {
    let user_id = match bearer_user(&state.pool, &headers).await {
        Ok(user_id) => user_id,
        Err(error) => return error.into_response(),
    };
    match set_favorite_for_owner(&state, user_id, project_id, favorite).await {
        Ok(project) => Json(project).into_response(),
        Err(error) => error.into_v1_response(),
    }
}

pub(crate) async fn set_favorite_for_owner(
    state: &AppState,
    user_id: i64,
    project_id: i64,
    favorite: bool,
) -> Result<ProjectResponse, ProjectError> {
    set_favorite_for_owner_with_tag_policy(state, user_id, project_id, favorite, TagPolicy::Legacy)
        .await
}

pub(crate) async fn set_favorite_for_owner_with_tag_policy(
    state: &AppState,
    user_id: i64,
    project_id: i64,
    favorite: bool,
    tag_policy: TagPolicy,
) -> Result<ProjectResponse, ProjectError> {
    let project_exists =
        sqlx::query_scalar::<_, i64>("SELECT id FROM projects WHERE id = ? AND owner_id = ?")
            .bind(project_id)
            .bind(user_id)
            .fetch_optional(&state.pool)
            .await;
    match project_exists {
        Ok(Some(_)) => {}
        Ok(None) => return Err(ProjectError::NotFound),
        Err(_) => return Err(ProjectError::Database),
    }
    let result = if favorite {
        sqlx::query(
            "INSERT INTO user_favorites (user_id, project_id) VALUES (?, ?) \
             ON CONFLICT DO NOTHING",
        )
        .bind(user_id)
        .bind(project_id)
        .execute(&state.pool)
        .await
    } else {
        sqlx::query("DELETE FROM user_favorites WHERE user_id = ? AND project_id = ?")
            .bind(user_id)
            .bind(project_id)
            .execute(&state.pool)
            .await
    };
    if result.is_err() {
        return Err(ProjectError::Database);
    }
    load_project_with_tag_policy(state, user_id, project_id, tag_policy)
        .await
        .map_err(|_| ProjectError::Database)?
        .ok_or(ProjectError::NotFound)
}

pub(crate) async fn load_project(
    state: &AppState,
    user_id: i64,
    project_id: i64,
) -> Result<Option<ProjectResponse>, sqlx::Error> {
    load_project_with_tag_policy(state, user_id, project_id, TagPolicy::Legacy).await
}

pub(crate) async fn load_project_with_tag_policy(
    state: &AppState,
    user_id: i64,
    project_id: i64,
    tag_policy: TagPolicy,
) -> Result<Option<ProjectResponse>, sqlx::Error> {
    let row = sqlx::query_as::<_, ProjectRow>(
        "SELECT p.id, p.name, c.name AS category, p.needles, p.stitch_sample, \
                p.description, p.notes, p.other_materials, p.tags, p.link, \
                p.link_archive, p.is_ai_enhanced, p.created_at, p.updated_at \
         FROM projects p LEFT JOIN categories c ON c.id = p.category_id \
         WHERE p.id = ? AND p.owner_id = ?",
    )
    .bind(project_id)
    .bind(user_id)
    .fetch_optional(&state.pool)
    .await?;
    let Some(row) = row else {
        return Ok(None);
    };
    let yarns = sqlx::query_as::<_, (i64, String)>(
        "SELECT y.id, y.name FROM project_yarns py \
         JOIN yarns y ON y.id = py.yarn_id \
         WHERE py.project_id = ? AND y.owner_id = ? ORDER BY py.rowid",
    )
    .bind(project_id)
    .bind(user_id)
    .fetch_all(&state.pool)
    .await?;
    let steps = sqlx::query_as::<_, StepRow>(
        "SELECT id, title, description, step_number FROM project_steps \
         WHERE project_id = ? ORDER BY step_number, id",
    )
    .bind(project_id)
    .fetch_all(&state.pool)
    .await?;
    let is_favorite = sqlx::query_scalar::<_, bool>(
        "SELECT EXISTS(SELECT 1 FROM user_favorites WHERE user_id = ? AND project_id = ?)",
    )
    .bind(user_id)
    .bind(project_id)
    .fetch_one(&state.pool)
    .await?;

    let (images, attachments) =
        crate::media::project_media(&state.pool, user_id, project_id).await?;
    let yarn = yarns.first().map(|(_, name)| name.clone());
    Ok(Some(ProjectResponse {
        id: row.id,
        name: row.name,
        category: row.category,
        yarn,
        needles: row.needles,
        stitch_sample: row.stitch_sample,
        description: row.description,
        notes: row.notes,
        other_materials: row.other_materials,
        tags: decode_tags(&row.tags, tag_policy),
        link: row.link,
        link_archive: row.link_archive,
        is_ai_enhanced: row.is_ai_enhanced,
        is_favorite,
        created_at: row.created_at,
        updated_at: row.updated_at,
        yarn_ids: yarns.into_iter().map(|(id, _)| id).collect(),
        steps: steps
            .into_iter()
            .map(|step| StepResponse {
                id: step.id,
                title: step.title,
                description: step.description,
                step_number: step.step_number,
            })
            .collect(),
        images,
        attachments,
    }))
}

/// Load an owner's full project DTO for project delta sync.
pub(crate) async fn sync_value(
    state: &AppState,
    user_id: i64,
    id: i64,
) -> Result<Option<Value>, sqlx::Error> {
    load_project(state, user_id, id)
        .await?
        .map(|project| {
            serde_json::to_value(project).map_err(|error| sqlx::Error::Decode(Box::new(error)))
        })
        .transpose()
}

async fn project_step_ids(state: &AppState, project_id: i64) -> Result<HashSet<i64>, sqlx::Error> {
    sqlx::query_scalar::<_, i64>("SELECT id FROM project_steps WHERE project_id = ?")
        .bind(project_id)
        .fetch_all(&state.pool)
        .await
        .map(|ids| ids.into_iter().collect())
}

async fn ensure_category(
    transaction: &mut Transaction<'_, Sqlite>,
    user_id: i64,
    name: Option<&str>,
) -> Result<Option<i64>, sqlx::Error> {
    let Some(name) = name.map(str::trim).filter(|name| !name.is_empty()) else {
        return Ok(None);
    };
    if let Some(id) = sqlx::query_scalar::<_, i64>(
        "SELECT id FROM categories WHERE user_id = ? AND name = ? COLLATE NOCASE",
    )
    .bind(user_id)
    .bind(name)
    .fetch_optional(&mut **transaction)
    .await?
    {
        return Ok(Some(id));
    }
    sqlx::query("INSERT INTO categories (user_id, name) VALUES (?, ?) ON CONFLICT DO NOTHING")
        .bind(user_id)
        .bind(name)
        .execute(&mut **transaction)
        .await?;
    sqlx::query_scalar::<_, i64>(
        "SELECT id FROM categories WHERE user_id = ? AND name = ? COLLATE NOCASE",
    )
    .bind(user_id)
    .bind(name)
    .fetch_optional(&mut **transaction)
    .await
}

async fn owned_yarn_ids(
    transaction: &mut Transaction<'_, Sqlite>,
    user_id: i64,
    requested_ids: &[i64],
) -> Result<Vec<i64>, sqlx::Error> {
    let mut found_ids = Vec::new();
    let mut seen = HashSet::new();
    for yarn_id in requested_ids {
        if *yarn_id <= 0 || !seen.insert(*yarn_id) {
            continue;
        }
        let found_id =
            sqlx::query_scalar::<_, i64>("SELECT id FROM yarns WHERE id = ? AND owner_id = ?")
                .bind(yarn_id)
                .bind(user_id)
                .fetch_optional(&mut **transaction)
                .await?;
        if let Some(found_id) = found_id {
            found_ids.push(found_id);
        }
    }
    Ok(found_ids)
}

async fn linked_yarn_ids(
    transaction: &mut Transaction<'_, Sqlite>,
    project_id: i64,
) -> Result<Vec<i64>, sqlx::Error> {
    sqlx::query_scalar::<_, i64>("SELECT yarn_id FROM project_yarns WHERE project_id = ?")
        .bind(project_id)
        .fetch_all(&mut **transaction)
        .await
}

fn changed_ids(old_ids: &[i64], new_ids: &[i64]) -> Vec<i64> {
    let old_id_set: HashSet<i64> = old_ids.iter().copied().collect();
    let new_id_set: HashSet<i64> = new_ids.iter().copied().collect();
    old_ids
        .iter()
        .filter(|id| !new_id_set.contains(*id))
        .chain(new_ids.iter().filter(|id| !old_id_set.contains(*id)))
        .copied()
        .collect()
}

async fn bump_yarn_timestamps(
    transaction: &mut Transaction<'_, Sqlite>,
    user_id: i64,
    yarn_ids: &[i64],
) -> Result<(), sqlx::Error> {
    for yarn_id in yarn_ids {
        let current = sqlx::query_scalar::<_, String>(
            "SELECT updated_at FROM yarns WHERE id = ? AND owner_id = ?",
        )
        .bind(yarn_id)
        .bind(user_id)
        .fetch_optional(&mut **transaction)
        .await?
        .ok_or(sqlx::Error::RowNotFound)?;
        let updated_at = next_timestamp(&current).ok_or_else(|| {
            sqlx::Error::Decode(Box::new(std::io::Error::other(
                "invalid yarn update timestamp",
            )))
        })?;
        let result = sqlx::query("UPDATE yarns SET updated_at = ? WHERE id = ? AND owner_id = ?")
            .bind(updated_at)
            .bind(yarn_id)
            .bind(user_id)
            .execute(&mut **transaction)
            .await?;
        if result.rows_affected() != 1 {
            return Err(sqlx::Error::RowNotFound);
        }
    }
    Ok(())
}

async fn insert_project_yarns(
    transaction: &mut Transaction<'_, Sqlite>,
    project_id: i64,
    yarn_ids: &[i64],
) -> Result<(), sqlx::Error> {
    for yarn_id in yarn_ids {
        sqlx::query("INSERT INTO project_yarns (project_id, yarn_id) VALUES (?, ?)")
            .bind(project_id)
            .bind(yarn_id)
            .execute(&mut **transaction)
            .await?;
    }
    Ok(())
}

async fn replace_project_yarns(
    transaction: &mut Transaction<'_, Sqlite>,
    project_id: i64,
    yarn_ids: &[i64],
) -> Result<(), sqlx::Error> {
    sqlx::query("DELETE FROM project_yarns WHERE project_id = ?")
        .bind(project_id)
        .execute(&mut **transaction)
        .await?;
    insert_project_yarns(transaction, project_id, yarn_ids).await
}

async fn insert_steps(
    transaction: &mut Transaction<'_, Sqlite>,
    project_id: i64,
    steps: &[StepWriteRequest],
) -> Result<Vec<i64>, sqlx::Error> {
    let mut step_ids = Vec::with_capacity(steps.len());
    for step in steps {
        let inserted = sqlx::query(
            "INSERT INTO project_steps (project_id, title, description, step_number) \
             VALUES (?, ?, ?, ?)",
        )
        .bind(project_id)
        .bind(step.title.trim())
        .bind(stripped_optional(step.description.clone()))
        .bind(step.step_number)
        .execute(&mut **transaction)
        .await?;
        step_ids.push(inserted.last_insert_rowid());
    }
    Ok(step_ids)
}

async fn replace_steps(
    transaction: &mut Transaction<'_, Sqlite>,
    project_id: i64,
    existing_ids: &HashSet<i64>,
    steps: &[StepWriteRequest],
) -> Result<(), sqlx::Error> {
    let retained_ids: HashSet<i64> = steps
        .iter()
        .filter_map(|step| step.id.filter(|id| existing_ids.contains(id)))
        .collect();
    for removed_id in existing_ids.difference(&retained_ids) {
        sqlx::query("DELETE FROM project_steps WHERE id = ? AND project_id = ?")
            .bind(removed_id)
            .bind(project_id)
            .execute(&mut **transaction)
            .await?;
    }
    for (position, step) in steps.iter().enumerate() {
        let step_number = if step.step_number == 0 {
            position as i64 + 1
        } else {
            step.step_number
        };
        if let Some(id) = step.id.filter(|id| existing_ids.contains(id)) {
            sqlx::query(
                "UPDATE project_steps SET title = ?, description = ?, step_number = ? \
                 WHERE id = ? AND project_id = ?",
            )
            .bind(step.title.trim())
            .bind(stripped_optional(step.description.clone()))
            .bind(step_number)
            .bind(id)
            .bind(project_id)
            .execute(&mut **transaction)
            .await?;
        } else {
            sqlx::query(
                "INSERT INTO project_steps (project_id, title, description, step_number) \
                 VALUES (?, ?, ?, ?)",
            )
            .bind(project_id)
            .bind(step.title.trim())
            .bind(stripped_optional(step.description.clone()))
            .bind(step_number)
            .execute(&mut **transaction)
            .await?;
        }
    }
    Ok(())
}

fn payload_has_image_urls(payload: &ProjectWriteRequest) -> bool {
    !payload.image_urls.is_empty() || payload.steps.iter().any(|step| !step.image_urls.is_empty())
}

fn cleaned_optional(value: Option<String>) -> Option<String> {
    value.and_then(|value| {
        let value = value.trim();
        (!value.is_empty()).then(|| value.to_owned())
    })
}

fn stripped_optional(value: Option<String>) -> Option<String> {
    value.map(|value| value.trim().to_owned())
}

fn encode_tags(tags: &[String], tag_policy: TagPolicy) -> String {
    let normalized: Vec<String> = match tag_policy {
        TagPolicy::PreserveLabels => tags.to_vec(),
        TagPolicy::Legacy => {
            let mut seen = HashSet::new();
            tags.iter()
                .flat_map(|tag| {
                    tag.split(|character: char| {
                        character == ',' || character == '#' || character.is_whitespace()
                    })
                })
                .map(str::trim)
                .filter(|tag| !tag.is_empty())
                .filter(|tag| seen.insert(tag.to_lowercase()))
                .map(str::to_owned)
                .collect()
        }
    };
    serde_json::to_string(&normalized).unwrap_or_else(|_| "[]".to_owned())
}

fn decode_tags(tags: &str, tag_policy: TagPolicy) -> Vec<String> {
    match serde_json::from_str::<Vec<String>>(tags) {
        Ok(tags) if matches!(tag_policy, TagPolicy::PreserveLabels) => tags,
        Ok(tags) => tags
            .into_iter()
            .map(|tag| tag.trim().to_owned())
            .filter(|tag| !tag.is_empty())
            .collect(),
        Err(_) => tags
            .split(',')
            .map(|tag| tag.trim().to_owned())
            .filter(|tag| !tag.is_empty())
            .collect(),
    }
}

fn parse_timestamp(value: &str) -> Option<DateTime<Utc>> {
    crate::time::parse_utc(value)
}

fn timestamp(value: DateTime<Utc>) -> String {
    value.to_rfc3339_opts(SecondsFormat::Micros, true)
}

fn next_timestamp(current: &str) -> Option<String> {
    let current = parse_timestamp(current)?;
    let next_microsecond = current.checked_add_signed(Duration::microseconds(1))?;
    Some(timestamp(Utc::now().max(next_microsecond)))
}

fn conflict(project: ProjectResponse) -> Response {
    error_response(StatusCode::CONFLICT, json!(project))
}

fn invalid_project(detail: &'static str) -> Response {
    error_response(StatusCode::UNPROCESSABLE_ENTITY, json!(detail))
}

fn image_import_not_implemented() -> Response {
    error_response(
        StatusCode::NOT_IMPLEMENTED,
        json!("Project image URL imports are not implemented yet"),
    )
}

fn not_found() -> Response {
    error_response(StatusCode::NOT_FOUND, json!("Project not found"))
}

fn database_error() -> Response {
    error_response(StatusCode::INTERNAL_SERVER_ERROR, json!("database error"))
}

fn error_response(status: StatusCode, detail: Value) -> Response {
    (status, Json(ErrorResponse { detail })).into_response()
}
