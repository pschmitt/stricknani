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
struct ListQuery {
    page: Option<i64>,
    category: Option<String>,
    tag: Option<String>,
    favorite: Option<bool>,
    q: Option<String>,
}

#[derive(Debug, Deserialize)]
struct ProjectWriteRequest {
    name: String,
    #[serde(default)]
    category: Option<String>,
    #[serde(default)]
    needles: Option<String>,
    #[serde(default)]
    stitch_sample: Option<String>,
    #[serde(default)]
    description: Option<String>,
    #[serde(default)]
    notes: Option<String>,
    #[serde(default)]
    other_materials: Option<String>,
    #[serde(default)]
    tags: Vec<String>,
    #[serde(default)]
    link: Option<String>,
    #[serde(default)]
    is_ai_enhanced: bool,
    #[serde(default)]
    yarn_ids: Vec<i64>,
    #[serde(default)]
    steps: Vec<StepWriteRequest>,
    #[serde(default)]
    image_urls: Vec<String>,
    #[serde(default)]
    expected_updated_at: Option<String>,
}

#[derive(Debug, Deserialize)]
struct StepWriteRequest {
    #[serde(default)]
    id: Option<i64>,
    title: String,
    #[serde(default)]
    description: Option<String>,
    #[serde(default)]
    step_number: i64,
    #[serde(default)]
    image_urls: Vec<String>,
}

#[derive(Debug, Serialize)]
struct ProjectPage {
    items: Vec<ProjectListItem>,
    page: i64,
    per_page: i64,
    has_more: bool,
}

#[derive(Debug, Serialize)]
struct ProjectListItem {
    id: i64,
    name: String,
    category: Option<String>,
    tags: Vec<String>,
    is_favorite: bool,
    updated_at: String,
    preview_url: Option<String>,
}

#[derive(Debug, Serialize, Clone)]
struct ProjectResponse {
    id: i64,
    name: String,
    category: Option<String>,
    yarn: Option<String>,
    needles: Option<String>,
    stitch_sample: Option<String>,
    description: Option<String>,
    notes: Option<String>,
    other_materials: Option<String>,
    tags: Vec<String>,
    link: Option<String>,
    link_archive: Option<String>,
    is_ai_enhanced: bool,
    is_favorite: bool,
    created_at: String,
    updated_at: String,
    yarn_ids: Vec<i64>,
    steps: Vec<StepResponse>,
    // Media upload/import routes land in a later slice; this DTO returns empty arrays meanwhile.
    images: Vec<Value>,
    attachments: Vec<Value>,
}

#[derive(Debug, Serialize, Clone)]
struct StepResponse {
    id: i64,
    title: String,
    description: Option<String>,
    step_number: i64,
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
    let page = query.page.unwrap_or(1);
    if page < 1 {
        return error_response(StatusCode::UNPROCESSABLE_ENTITY, json!("Invalid page"));
    }

    let category = query.category.filter(|value| !value.is_empty());
    let tag = query.tag.filter(|value| !value.is_empty());
    if query.q.as_ref().is_some_and(|value| value.len() > 256) {
        return error_response(
            StatusCode::UNPROCESSABLE_ENTITY,
            json!("Search query is too long"),
        );
    }
    let search = query
        .q
        .map(|value| value.trim().to_owned())
        .filter(|value| !value.is_empty());
    let search_pattern = search.as_deref().map(literal_like_pattern);
    let favorite = query
        .favorite
        .map(|favorite| if favorite { 1_i64 } else { 0_i64 });
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
    let rows = match rows {
        Ok(rows) => rows,
        Err(_) => return database_error(),
    };
    let has_more = rows.len() > PAGE_SIZE as usize;
    let mut items = Vec::new();
    for row in rows.into_iter().take(PAGE_SIZE as usize) {
        let (images, _) = match crate::media::project_media(&state.pool, user_id, row.id).await {
            Ok(media) => media,
            Err(_) => return database_error(),
        };
        items.push(ProjectListItem {
            id: row.id,
            name: row.name,
            category: row.category,
            tags: decode_tags(&row.tags),
            is_favorite: row.is_favorite,
            updated_at: row.updated_at,
            preview_url: crate::media::project_preview(&images),
        });
    }

    Json(ProjectPage {
        items,
        page,
        per_page: PAGE_SIZE,
        has_more,
    })
    .into_response()
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
    if payload_has_image_urls(&payload) {
        return image_import_not_implemented();
    }
    let name = payload.name.trim().to_owned();
    let mut transaction = match state.pool.begin().await {
        Ok(transaction) => transaction,
        Err(_) => return database_error(),
    };
    let yarn_ids = match owned_yarn_ids(&mut transaction, user_id, &payload.yarn_ids).await {
        Ok(yarn_ids) => yarn_ids,
        Err(_) => return database_error(),
    };
    let category_id =
        match ensure_category(&mut transaction, user_id, payload.category.as_deref()).await {
            Ok(category_id) => category_id,
            Err(_) => return database_error(),
        };
    let now = timestamp(Utc::now());
    let tags = encode_tags(&payload.tags);
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
    let project_id = match inserted {
        Ok(result) => result.last_insert_rowid(),
        Err(_) => return database_error(),
    };
    if insert_project_yarns(&mut transaction, project_id, &yarn_ids)
        .await
        .is_err()
        || bump_yarn_timestamps(&mut transaction, user_id, &yarn_ids)
            .await
            .is_err()
        || insert_steps(&mut transaction, project_id, &payload.steps)
            .await
            .is_err()
    {
        return database_error();
    }
    if transaction.commit().await.is_err() {
        return database_error();
    }

    match load_project(&state, user_id, project_id).await {
        Ok(Some(project)) => (StatusCode::CREATED, Json(project)).into_response(),
        Ok(None) => database_error(),
        Err(_) => database_error(),
    }
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
    if payload_has_image_urls(&payload) {
        return image_import_not_implemented();
    }
    let name = payload.name.trim().to_owned();
    let expected_updated_at = match payload.expected_updated_at.as_deref() {
        Some(value) => match parse_timestamp(value) {
            Some(value) => Some(value),
            None => return invalid_project("Invalid expected_updated_at"),
        },
        None => None,
    };
    let current = match load_project(&state, user_id, project_id).await {
        Ok(Some(project)) => project,
        Ok(None) => return not_found(),
        Err(_) => return database_error(),
    };
    if expected_updated_at.is_some_and(|expected| {
        parse_timestamp(&current.updated_at).is_some_and(|actual| actual != expected)
    }) {
        return conflict(current);
    }

    let current_step_ids = match project_step_ids(&state, project_id).await {
        Ok(ids) => ids,
        Err(_) => return database_error(),
    };
    let mut transaction = match state.pool.begin().await {
        Ok(transaction) => transaction,
        Err(_) => return database_error(),
    };
    let old_yarn_ids = match linked_yarn_ids(&mut transaction, project_id).await {
        Ok(ids) => ids,
        Err(_) => return database_error(),
    };
    let yarn_ids = match owned_yarn_ids(&mut transaction, user_id, &payload.yarn_ids).await {
        Ok(yarn_ids) => yarn_ids,
        Err(_) => return database_error(),
    };
    let category_id =
        match ensure_category(&mut transaction, user_id, payload.category.as_deref()).await {
            Ok(category_id) => category_id,
            Err(_) => return database_error(),
        };
    let updated_at = match next_timestamp(&current.updated_at) {
        Some(value) => value,
        None => return database_error(),
    };
    let tags = encode_tags(&payload.tags);
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
            return match load_project(&state, user_id, project_id).await {
                Ok(Some(project)) => conflict(project),
                Ok(None) => not_found(),
                Err(_) => database_error(),
            };
        }
        Err(_) => return database_error(),
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
        return database_error();
    }
    if transaction.commit().await.is_err() {
        return database_error();
    }
    match load_project(&state, user_id, project_id).await {
        Ok(Some(project)) => Json(project).into_response(),
        Ok(None) => not_found(),
        Err(_) => database_error(),
    }
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
    let mut transaction = match state.pool.begin().await {
        Ok(transaction) => transaction,
        Err(_) => return database_error(),
    };
    let exists =
        sqlx::query_scalar::<_, i64>("SELECT id FROM projects WHERE id = ? AND owner_id = ?")
            .bind(project_id)
            .bind(user_id)
            .fetch_optional(&mut *transaction)
            .await;
    match exists {
        Ok(Some(_)) => {}
        Ok(None) => return not_found(),
        Err(_) => return database_error(),
    }
    let linked_yarn_ids = match linked_yarn_ids(&mut transaction, project_id).await {
        Ok(ids) => ids,
        Err(_) => return database_error(),
    };
    if bump_yarn_timestamps(&mut transaction, user_id, &linked_yarn_ids)
        .await
        .is_err()
    {
        return database_error();
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
        return database_error();
    }
    match sqlx::query("DELETE FROM projects WHERE id = ? AND owner_id = ?")
        .bind(project_id)
        .bind(user_id)
        .execute(&mut *transaction)
        .await
    {
        Ok(result) if result.rows_affected() == 1 => {}
        Ok(_) => return not_found(),
        Err(_) => return database_error(),
    }
    match transaction.commit().await {
        Ok(()) => StatusCode::NO_CONTENT.into_response(),
        Err(_) => database_error(),
    }
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
    let project_exists =
        sqlx::query_scalar::<_, i64>("SELECT id FROM projects WHERE id = ? AND owner_id = ?")
            .bind(project_id)
            .bind(user_id)
            .fetch_optional(&state.pool)
            .await;
    match project_exists {
        Ok(Some(_)) => {}
        Ok(None) => return not_found(),
        Err(_) => return database_error(),
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
        return database_error();
    }
    match load_project(&state, user_id, project_id).await {
        Ok(Some(project)) => Json(project).into_response(),
        Ok(None) => not_found(),
        Err(_) => database_error(),
    }
}

async fn load_project(
    state: &AppState,
    user_id: i64,
    project_id: i64,
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
        tags: decode_tags(&row.tags),
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
) -> Result<(), sqlx::Error> {
    for step in steps {
        sqlx::query(
            "INSERT INTO project_steps (project_id, title, description, step_number) \
             VALUES (?, ?, ?, ?)",
        )
        .bind(project_id)
        .bind(step.title.trim())
        .bind(stripped_optional(step.description.clone()))
        .bind(step.step_number)
        .execute(&mut **transaction)
        .await?;
    }
    Ok(())
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

fn encode_tags(tags: &[String]) -> String {
    let mut seen = HashSet::new();
    let normalized: Vec<String> = tags
        .iter()
        .flat_map(|tag| {
            tag.split(|character: char| {
                character == ',' || character == '#' || character.is_whitespace()
            })
        })
        .map(str::trim)
        .filter(|tag| !tag.is_empty())
        .filter(|tag| seen.insert(tag.to_lowercase()))
        .map(str::to_owned)
        .collect();
    serde_json::to_string(&normalized).unwrap_or_else(|_| "[]".to_owned())
}

fn decode_tags(tags: &str) -> Vec<String> {
    serde_json::from_str::<Vec<String>>(tags)
        .unwrap_or_else(|_| tags.split(',').map(str::to_owned).collect())
        .into_iter()
        .map(|tag| tag.trim().to_owned())
        .filter(|tag| !tag.is_empty())
        .collect()
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
