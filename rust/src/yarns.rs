//! Authenticated yarn JSON API routes.

use axum::extract::{Path, Query, State};
use axum::http::{HeaderMap, StatusCode};
use axum::response::{IntoResponse, Response};
use axum::routing::{get, post};
use axum::{Json, Router};
use chrono::{DateTime, SecondsFormat, Utc};
use serde::{Deserialize, Serialize};
use serde_json::json;
use sqlx::{FromRow, Sqlite, SqlitePool, Transaction};

use crate::{
    audit::{self, Action, Entity},
    auth::bearer_user,
    db::AppState,
};

const PAGE_SIZE: i64 = 50;

/// Build the yarn endpoints, to be nested under `/api/v1`.
pub fn router() -> Router<AppState> {
    Router::new()
        .route("/yarns", get(list_yarns).post(create_yarn))
        .route(
            "/yarns/{yarn_id}",
            get(get_yarn).put(update_yarn).delete(delete_yarn),
        )
        .route(
            "/yarns/{yarn_id}/favorite",
            post(favorite_yarn).delete(unfavorite_yarn),
        )
}

#[derive(Debug, Deserialize)]
pub(crate) struct YarnWriteRequest {
    pub(crate) name: String,
    #[serde(default)]
    pub(crate) description: Option<String>,
    #[serde(default)]
    pub(crate) brand: Option<String>,
    #[serde(default)]
    pub(crate) colorway: Option<String>,
    #[serde(default)]
    pub(crate) dye_lot: Option<String>,
    #[serde(default)]
    pub(crate) fiber_content: Option<String>,
    #[serde(default)]
    pub(crate) weight_category: Option<String>,
    #[serde(default)]
    pub(crate) recommended_needles: Option<String>,
    #[serde(default)]
    pub(crate) weight_grams: Option<i64>,
    #[serde(default)]
    pub(crate) length_meters: Option<i64>,
    #[serde(default)]
    pub(crate) notes: Option<String>,
    #[serde(default)]
    pub(crate) link: Option<String>,
    #[serde(default)]
    pub(crate) is_ai_enhanced: bool,
    #[serde(default)]
    pub(crate) expected_updated_at: Option<String>,
}

#[derive(Debug, Deserialize)]
pub(crate) struct ListQuery {
    pub(crate) page: Option<i64>,
    pub(crate) favorite: Option<bool>,
    pub(crate) q: Option<String>,
}

#[derive(Debug, FromRow)]
struct YarnRow {
    id: i64,
    name: String,
    description: Option<String>,
    brand: Option<String>,
    colorway: Option<String>,
    dye_lot: Option<String>,
    fiber_content: Option<String>,
    weight_category: Option<String>,
    recommended_needles: Option<String>,
    weight_grams: Option<i64>,
    length_meters: Option<i64>,
    notes: Option<String>,
    link: Option<String>,
    link_archive: Option<String>,
    is_ai_enhanced: bool,
    created_at: String,
    updated_at: String,
}

#[derive(Debug, Serialize)]
pub(crate) struct YarnResponse {
    pub(crate) id: i64,
    pub(crate) name: String,
    pub(crate) description: Option<String>,
    pub(crate) brand: Option<String>,
    pub(crate) colorway: Option<String>,
    pub(crate) dye_lot: Option<String>,
    pub(crate) fiber_content: Option<String>,
    pub(crate) weight_category: Option<String>,
    pub(crate) recommended_needles: Option<String>,
    pub(crate) weight_grams: Option<i64>,
    pub(crate) length_meters: Option<i64>,
    pub(crate) notes: Option<String>,
    pub(crate) link: Option<String>,
    pub(crate) link_archive: Option<String>,
    pub(crate) is_ai_enhanced: bool,
    pub(crate) is_favorite: bool,
    pub(crate) created_at: String,
    pub(crate) updated_at: String,
    pub(crate) project_ids: Vec<i64>,
    pub(crate) photos: Vec<serde_json::Value>,
}

#[derive(Debug, Serialize)]
pub(crate) struct YarnListItemResponse {
    pub(crate) id: i64,
    pub(crate) name: String,
    pub(crate) brand: Option<String>,
    pub(crate) colorway: Option<String>,
    pub(crate) weight_category: Option<String>,
    pub(crate) is_favorite: bool,
    pub(crate) updated_at: String,
    pub(crate) preview_url: Option<String>,
}

#[derive(Debug, Serialize)]
pub(crate) struct YarnPage {
    pub(crate) items: Vec<YarnListItemResponse>,
    pub(crate) page: i64,
    pub(crate) per_page: i64,
    pub(crate) has_more: bool,
}

pub(crate) enum YarnError {
    NotFound,
    Database,
    Invalid(&'static str),
    Conflict(Box<YarnResponse>),
}

impl YarnError {
    fn into_v1_response(self) -> Response {
        match self {
            Self::NotFound => yarn_not_found(),
            Self::Database => database_error(),
            Self::Invalid(detail) => {
                error_response(StatusCode::UNPROCESSABLE_ENTITY, json!(detail))
            }
            Self::Conflict(yarn) => error_response(
                StatusCode::CONFLICT,
                serde_json::to_value(*yarn).unwrap_or(serde_json::Value::Null),
            ),
        }
    }
}

#[derive(Debug, Serialize)]
struct ErrorResponse {
    detail: serde_json::Value,
}

async fn list_yarns(
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
        Err(YarnError::Invalid(detail)) => {
            error_response(StatusCode::UNPROCESSABLE_ENTITY, json!(detail))
        }
        Err(_) => database_error(),
    }
}

pub(crate) async fn list_for_owner(
    state: &AppState,
    user_id: i64,
    query: ListQuery,
) -> Result<YarnPage, YarnError> {
    let page = query.page.unwrap_or(1);
    if page < 1 {
        return Err(YarnError::Invalid("Invalid page"));
    }
    if query.q.as_ref().is_some_and(|value| value.len() > 256) {
        return Err(YarnError::Invalid("Search query is too long"));
    }
    let search = query
        .q
        .map(|value| value.trim().to_owned())
        .filter(|value| !value.is_empty());
    let search_pattern = search.as_deref().map(literal_like_pattern);

    let rows = sqlx::query_as::<_, YarnRow>(
        "SELECT yarns.* FROM yarns \
         WHERE owner_id = ? \
           AND (? IS NULL OR EXISTS (SELECT 1 FROM user_favorite_yarns f \
                                     WHERE f.user_id = ? AND f.yarn_id = yarns.id) = ?) \
           AND (? IS NULL OR yarns.name LIKE ? ESCAPE '!' \
                OR COALESCE(yarns.brand, '') LIKE ? ESCAPE '!' \
                OR COALESCE(yarns.colorway, '') LIKE ? ESCAPE '!' \
                OR COALESCE(yarns.fiber_content, '') LIKE ? ESCAPE '!' \
                OR COALESCE(yarns.notes, '') LIKE ? ESCAPE '!') \
         ORDER BY updated_at DESC, id DESC LIMIT ? OFFSET ?",
    )
    .bind(user_id)
    .bind(
        query
            .favorite
            .map(|favorite| if favorite { 1_i64 } else { 0_i64 }),
    )
    .bind(user_id)
    .bind(
        query
            .favorite
            .map(|favorite| if favorite { 1_i64 } else { 0_i64 }),
    )
    .bind(search_pattern.as_deref())
    .bind(search_pattern.as_deref())
    .bind(search_pattern.as_deref())
    .bind(search_pattern.as_deref())
    .bind(search_pattern.as_deref())
    .bind(search_pattern.as_deref())
    .bind(PAGE_SIZE + 1)
    .bind((page - 1).saturating_mul(PAGE_SIZE))
    .fetch_all(&state.pool)
    .await
    .map_err(|_| YarnError::Database)?;
    let has_more = rows.len() > PAGE_SIZE as usize;
    let favorite_ids = favorite_ids(&state.pool, user_id)
        .await
        .map_err(|_| YarnError::Database)?;
    let mut items = Vec::new();
    for yarn in rows.into_iter().take(PAGE_SIZE as usize) {
        let photos = match crate::media::yarn_photos(&state.pool, user_id, yarn.id).await {
            Ok(photos) => photos,
            Err(_) => return Err(YarnError::Database),
        };
        items.push(YarnListItemResponse {
            id: yarn.id,
            name: yarn.name,
            brand: yarn.brand,
            colorway: yarn.colorway,
            weight_category: yarn.weight_category,
            is_favorite: favorite_ids.contains(&yarn.id),
            updated_at: yarn.updated_at,
            preview_url: crate::media::yarn_preview(&photos),
        });
    }

    Ok(YarnPage {
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

async fn get_yarn(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(yarn_id): Path<i64>,
) -> Response {
    let user_id = match bearer_user(&state.pool, &headers).await {
        Ok(user_id) => user_id,
        Err(error) => return error.into_response(),
    };
    match load_for_owner(&state, user_id, yarn_id).await {
        Ok(Some(yarn)) => Json(yarn).into_response(),
        Ok(None) => yarn_not_found(),
        Err(_) => database_error(),
    }
}

async fn create_yarn(
    State(state): State<AppState>,
    headers: HeaderMap,
    Json(payload): Json<YarnWriteRequest>,
) -> Response {
    let user_id = match bearer_user(&state.pool, &headers).await {
        Ok(user_id) => user_id,
        Err(error) => return error.into_response(),
    };
    match create_for_owner(&state, user_id, payload).await {
        Ok(yarn) => (StatusCode::CREATED, Json(yarn)).into_response(),
        Err(error) => error.into_v1_response(),
    }
}

pub(crate) async fn create_for_owner(
    state: &AppState,
    user_id: i64,
    payload: YarnWriteRequest,
) -> Result<YarnResponse, YarnError> {
    let name = clean_name(&payload.name);
    let now = timestamp(Utc::now());
    let mut tx = state.pool.begin().await.map_err(|_| YarnError::Database)?;
    let result = sqlx::query(
        "INSERT INTO yarns (owner_id, name, description, brand, colorway, dye_lot, \
         fiber_content, weight_category, recommended_needles, weight_grams, length_meters, \
         notes, link, is_ai_enhanced, created_at, updated_at) \
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
    )
    .bind(user_id)
    .bind(name)
    .bind(clean_optional(payload.description))
    .bind(clean_optional(payload.brand))
    .bind(clean_optional(payload.colorway))
    .bind(clean_optional(payload.dye_lot))
    .bind(clean_optional(payload.fiber_content))
    .bind(clean_optional(payload.weight_category))
    .bind(clean_optional(payload.recommended_needles))
    .bind(payload.weight_grams)
    .bind(payload.length_meters)
    .bind(clean_optional(payload.notes))
    .bind(clean_optional(payload.link))
    .bind(payload.is_ai_enhanced)
    .bind(&now)
    .bind(&now)
    .execute(&mut *tx)
    .await;
    let id = result.map_err(|_| YarnError::Database)?.last_insert_rowid();
    if audit::record(&mut tx, user_id, Entity::Yarn, id, Action::Created)
        .await
        .is_err()
    {
        return Err(YarnError::Database);
    }
    if state.archive_enabled
        && crate::archive_jobs::enqueue_in_transaction(
            &mut tx,
            user_id,
            crate::archive_jobs::ArchiveEntityKind::Yarn,
            id,
        )
        .await
        .is_err()
    {
        return Err(YarnError::Database);
    }
    if tx.commit().await.is_err() {
        return Err(YarnError::Database);
    }
    load_for_owner(state, user_id, id)
        .await
        .map_err(|_| YarnError::Database)?
        .ok_or(YarnError::Database)
}

async fn update_yarn(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(yarn_id): Path<i64>,
    Json(payload): Json<YarnWriteRequest>,
) -> Response {
    let user_id = match bearer_user(&state.pool, &headers).await {
        Ok(user_id) => user_id,
        Err(error) => return error.into_response(),
    };
    match update_for_owner(&state, user_id, yarn_id, payload).await {
        Ok(yarn) => Json(yarn).into_response(),
        Err(error) => error.into_v1_response(),
    }
}

pub(crate) async fn update_for_owner(
    state: &AppState,
    user_id: i64,
    yarn_id: i64,
    payload: YarnWriteRequest,
) -> Result<YarnResponse, YarnError> {
    let name = clean_name(&payload.name);
    let expected = match payload.expected_updated_at.as_deref() {
        Some(value) => match parse_timestamp(value) {
            Some(value) => Some(timestamp(value)),
            None => return Err(YarnError::Invalid("Invalid expected_updated_at")),
        },
        None => None,
    };

    let mut tx = state.pool.begin().await.map_err(|_| YarnError::Database)?;
    let current = match get_owned_yarn_in(&mut tx, user_id, yarn_id).await {
        Ok(Some(yarn)) => yarn,
        Ok(None) => return Err(YarnError::NotFound),
        Err(_) => return Err(YarnError::Database),
    };
    if expected
        .as_deref()
        .is_some_and(|value| value != current.updated_at)
    {
        let _ = tx.rollback().await;
        let current = current_for_conflict(state, user_id, yarn_id).await?;
        return Err(YarnError::Conflict(Box::new(current)));
    }

    let name_changed = name != current.name.as_str();
    let updated_at = newer_timestamp(&current.updated_at);
    let query = if expected.is_some() {
        "UPDATE yarns SET name = ?, description = ?, brand = ?, colorway = ?, dye_lot = ?, \
         fiber_content = ?, weight_category = ?, recommended_needles = ?, weight_grams = ?, \
         length_meters = ?, notes = ?, link = ?, is_ai_enhanced = ?, updated_at = ? \
         WHERE id = ? AND owner_id = ? AND updated_at = ?"
    } else {
        "UPDATE yarns SET name = ?, description = ?, brand = ?, colorway = ?, dye_lot = ?, \
                 fiber_content = ?, weight_category = ?, recommended_needles = ?, weight_grams = ?, \
                 length_meters = ?, notes = ?, link = ?, is_ai_enhanced = ?, updated_at = ? \
                 WHERE id = ? AND owner_id = ?"
    };
    let mut update = sqlx::query(query)
        .bind(name)
        .bind(clean_optional(payload.description))
        .bind(clean_optional(payload.brand))
        .bind(clean_optional(payload.colorway))
        .bind(clean_optional(payload.dye_lot))
        .bind(clean_optional(payload.fiber_content))
        .bind(clean_optional(payload.weight_category))
        .bind(clean_optional(payload.recommended_needles))
        .bind(payload.weight_grams)
        .bind(payload.length_meters)
        .bind(clean_optional(payload.notes))
        .bind(clean_optional(payload.link))
        .bind(payload.is_ai_enhanced)
        .bind(&updated_at)
        .bind(yarn_id)
        .bind(user_id);
    if expected.is_some() {
        update = update.bind(&current.updated_at);
    }
    let result = update.execute(&mut *tx).await;
    match result {
        Ok(result) if result.rows_affected() == 1 => {}
        Ok(_) => {
            let _ = tx.rollback().await;
            return if expected.is_some() {
                match current_for_conflict(state, user_id, yarn_id).await {
                    Ok(current) => Err(YarnError::Conflict(Box::new(current))),
                    Err(error) => Err(error),
                }
            } else {
                Err(YarnError::NotFound)
            };
        }
        Err(_) => {
            let _ = tx.rollback().await;
            return Err(YarnError::Database);
        }
    }
    if name_changed
        && bump_linked_project_timestamps(&mut tx, user_id, yarn_id)
            .await
            .is_err()
    {
        let _ = tx.rollback().await;
        return Err(YarnError::Database);
    }
    if audit::record(&mut tx, user_id, Entity::Yarn, yarn_id, Action::Updated)
        .await
        .is_err()
    {
        let _ = tx.rollback().await;
        return Err(YarnError::Database);
    }
    if state.archive_enabled
        && crate::archive_jobs::enqueue_in_transaction(
            &mut tx,
            user_id,
            crate::archive_jobs::ArchiveEntityKind::Yarn,
            yarn_id,
        )
        .await
        .is_err()
    {
        return Err(YarnError::Database);
    }
    if tx.commit().await.is_err() {
        return Err(YarnError::Database);
    }
    load_for_owner(state, user_id, yarn_id)
        .await
        .map_err(|_| YarnError::Database)?
        .ok_or(YarnError::NotFound)
}

async fn delete_yarn(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(yarn_id): Path<i64>,
) -> Response {
    let user_id = match bearer_user(&state.pool, &headers).await {
        Ok(user_id) => user_id,
        Err(error) => return error.into_response(),
    };
    match delete_for_owner(&state, user_id, yarn_id).await {
        Ok(()) => StatusCode::NO_CONTENT.into_response(),
        Err(error) => error.into_v1_response(),
    }
}

pub(crate) async fn delete_for_owner(
    state: &AppState,
    user_id: i64,
    yarn_id: i64,
) -> Result<(), YarnError> {
    let mut tx = state.pool.begin().await.map_err(|_| YarnError::Database)?;
    match get_owned_yarn_in(&mut tx, user_id, yarn_id).await {
        Ok(Some(_)) => {}
        Ok(None) => {
            let _ = tx.rollback().await;
            return Err(YarnError::NotFound);
        }
        Err(_) => {
            let _ = tx.rollback().await;
            return Err(YarnError::Database);
        }
    }
    if bump_linked_project_timestamps(&mut tx, user_id, yarn_id)
        .await
        .is_err()
    {
        let _ = tx.rollback().await;
        return Err(YarnError::Database);
    }
    let deleted_at = timestamp(Utc::now());
    if sqlx::query(
        "INSERT INTO deletion_events (owner_id, entity_type, entity_id, changed_at) \
         VALUES (?, 'yarn', ?, ?)",
    )
    .bind(user_id)
    .bind(yarn_id)
    .bind(deleted_at)
    .execute(&mut *tx)
    .await
    .is_err()
    {
        let _ = tx.rollback().await;
        return Err(YarnError::Database);
    }
    let deleted = sqlx::query("DELETE FROM yarns WHERE id = ? AND owner_id = ?")
        .bind(yarn_id)
        .bind(user_id)
        .execute(&mut *tx)
        .await;
    match deleted {
        Ok(result) if result.rows_affected() == 1 => {}
        Ok(_) => {
            let _ = tx.rollback().await;
            return Err(YarnError::NotFound);
        }
        Err(_) => {
            let _ = tx.rollback().await;
            return Err(YarnError::Database);
        }
    }
    if audit::record(&mut tx, user_id, Entity::Yarn, yarn_id, Action::Deleted)
        .await
        .is_err()
    {
        let _ = tx.rollback().await;
        return Err(YarnError::Database);
    }
    tx.commit().await.map_err(|_| YarnError::Database)
}

async fn favorite_yarn(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(yarn_id): Path<i64>,
) -> Response {
    set_favorite(state, headers, yarn_id, true).await
}

async fn unfavorite_yarn(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(yarn_id): Path<i64>,
) -> Response {
    set_favorite(state, headers, yarn_id, false).await
}

async fn set_favorite(
    state: AppState,
    headers: HeaderMap,
    yarn_id: i64,
    favorite: bool,
) -> Response {
    let user_id = match bearer_user(&state.pool, &headers).await {
        Ok(user_id) => user_id,
        Err(error) => return error.into_response(),
    };
    match set_favorite_for_owner(&state, user_id, yarn_id, favorite).await {
        Ok(yarn) => Json(yarn).into_response(),
        Err(error) => error.into_v1_response(),
    }
}

pub(crate) async fn set_favorite_for_owner(
    state: &AppState,
    user_id: i64,
    yarn_id: i64,
    favorite: bool,
) -> Result<YarnResponse, YarnError> {
    match get_owned_yarn(&state.pool, user_id, yarn_id).await {
        Ok(Some(_)) => {}
        Ok(None) => return Err(YarnError::NotFound),
        Err(_) => return Err(YarnError::Database),
    }
    let result = if favorite {
        sqlx::query("INSERT INTO user_favorite_yarns (user_id, yarn_id) VALUES (?, ?) ON CONFLICT DO NOTHING")
            .bind(user_id).bind(yarn_id).execute(&state.pool).await
    } else {
        sqlx::query("DELETE FROM user_favorite_yarns WHERE user_id = ? AND yarn_id = ?")
            .bind(user_id)
            .bind(yarn_id)
            .execute(&state.pool)
            .await
    };
    if result.is_err() {
        return Err(YarnError::Database);
    }
    load_for_owner(state, user_id, yarn_id)
        .await
        .map_err(|_| YarnError::Database)?
        .ok_or(YarnError::NotFound)
}

async fn get_owned_yarn(
    pool: &SqlitePool,
    user_id: i64,
    yarn_id: i64,
) -> Result<Option<YarnRow>, sqlx::Error> {
    sqlx::query_as::<_, YarnRow>("SELECT * FROM yarns WHERE id = ? AND owner_id = ?")
        .bind(yarn_id)
        .bind(user_id)
        .fetch_optional(pool)
        .await
}

pub(crate) async fn load_for_owner(
    state: &AppState,
    user_id: i64,
    yarn_id: i64,
) -> Result<Option<YarnResponse>, sqlx::Error> {
    get_yarn_response(&state.pool, user_id, yarn_id).await
}

async fn current_for_conflict(
    state: &AppState,
    user_id: i64,
    yarn_id: i64,
) -> Result<YarnResponse, YarnError> {
    load_for_owner(state, user_id, yarn_id)
        .await
        .map_err(|_| YarnError::Database)?
        .ok_or(YarnError::NotFound)
}

async fn get_yarn_response(
    pool: &SqlitePool,
    user_id: i64,
    yarn_id: i64,
) -> Result<Option<YarnResponse>, sqlx::Error> {
    let Some(yarn) = get_owned_yarn(pool, user_id, yarn_id).await? else {
        return Ok(None);
    };
    let favorite = is_favorite(pool, user_id, yarn_id).await?;
    yarn_response(pool, user_id, yarn, favorite).await.map(Some)
}

async fn get_owned_yarn_in(
    tx: &mut Transaction<'_, Sqlite>,
    user_id: i64,
    yarn_id: i64,
) -> Result<Option<YarnRow>, sqlx::Error> {
    sqlx::query_as::<_, YarnRow>("SELECT * FROM yarns WHERE id = ? AND owner_id = ?")
        .bind(yarn_id)
        .bind(user_id)
        .fetch_optional(&mut **tx)
        .await
}

async fn is_favorite(pool: &SqlitePool, user_id: i64, yarn_id: i64) -> Result<bool, sqlx::Error> {
    sqlx::query_scalar::<_, i64>(
        "SELECT EXISTS(SELECT 1 FROM user_favorite_yarns WHERE user_id = ? AND yarn_id = ?)",
    )
    .bind(user_id)
    .bind(yarn_id)
    .fetch_one(pool)
    .await
    .map(|exists| exists != 0)
}

async fn favorite_ids(
    pool: &SqlitePool,
    user_id: i64,
) -> Result<std::collections::HashSet<i64>, sqlx::Error> {
    sqlx::query_scalar::<_, i64>("SELECT yarn_id FROM user_favorite_yarns WHERE user_id = ?")
        .bind(user_id)
        .fetch_all(pool)
        .await
        .map(|ids| ids.into_iter().collect())
}

async fn yarn_response(
    pool: &SqlitePool,
    user_id: i64,
    yarn: YarnRow,
    is_favorite: bool,
) -> Result<YarnResponse, sqlx::Error> {
    let project_ids = sqlx::query_scalar::<_, i64>(
        "SELECT p.id FROM projects p \
         JOIN project_yarns py ON py.project_id = p.id \
         WHERE p.owner_id = ? AND py.yarn_id = ? ORDER BY p.id",
    )
    .bind(user_id)
    .bind(yarn.id)
    .fetch_all(pool)
    .await?;
    let photos = crate::media::yarn_photos(pool, user_id, yarn.id).await?;
    Ok(YarnResponse {
        id: yarn.id,
        name: yarn.name,
        description: yarn.description,
        brand: yarn.brand,
        colorway: yarn.colorway,
        dye_lot: yarn.dye_lot,
        fiber_content: yarn.fiber_content,
        weight_category: yarn.weight_category,
        recommended_needles: yarn.recommended_needles,
        weight_grams: yarn.weight_grams,
        length_meters: yarn.length_meters,
        notes: yarn.notes,
        link: yarn.link,
        link_archive: yarn.link_archive,
        is_ai_enhanced: yarn.is_ai_enhanced,
        is_favorite,
        created_at: yarn.created_at,
        updated_at: yarn.updated_at,
        project_ids,
        photos,
    })
}

async fn bump_linked_project_timestamps(
    tx: &mut Transaction<'_, Sqlite>,
    owner_id: i64,
    yarn_id: i64,
) -> Result<(), sqlx::Error> {
    let latest_updated_at = sqlx::query_scalar::<_, Option<String>>(
        "SELECT MAX(p.updated_at) FROM projects p \
         JOIN project_yarns py ON py.project_id = p.id \
         WHERE p.owner_id = ? AND py.yarn_id = ?",
    )
    .bind(owner_id)
    .bind(yarn_id)
    .fetch_one(&mut **tx)
    .await?;
    if let Some(latest_updated_at) = latest_updated_at {
        let updated_at = newer_timestamp(&latest_updated_at);
        sqlx::query(
            "UPDATE projects SET updated_at = ? WHERE owner_id = ? \
             AND id IN (SELECT project_id FROM project_yarns WHERE yarn_id = ?)",
        )
        .bind(updated_at)
        .bind(owner_id)
        .bind(yarn_id)
        .execute(&mut **tx)
        .await?;
    }
    Ok(())
}

pub(crate) async fn sync_value(
    state: &AppState,
    user_id: i64,
    yarn_id: i64,
) -> Result<Option<serde_json::Value>, sqlx::Error> {
    get_yarn_response(&state.pool, user_id, yarn_id)
        .await?
        .map(serde_json::to_value)
        .transpose()
        .map_err(|error| sqlx::Error::Protocol(error.to_string()))
}

fn clean_name(value: &str) -> String {
    value.trim().to_owned()
}

fn clean_optional(value: Option<String>) -> Option<String> {
    value
        .map(|value| value.trim().to_owned())
        .filter(|value| !value.is_empty())
}

fn parse_timestamp(value: &str) -> Option<DateTime<Utc>> {
    crate::time::parse_utc(value)
}

fn timestamp(value: DateTime<Utc>) -> String {
    value.to_rfc3339_opts(SecondsFormat::Micros, true)
}

fn newer_timestamp(current: &str) -> String {
    let current_micros = parse_timestamp(current)
        .map(|current| current.timestamp_micros())
        .unwrap_or(i64::MIN);
    let now_micros = Utc::now().timestamp_micros();
    let next_micros = now_micros.max(current_micros.saturating_add(1));
    DateTime::<Utc>::from_timestamp_micros(next_micros)
        .map(timestamp)
        .unwrap_or_else(|| timestamp(Utc::now()))
}

fn error_response(status: StatusCode, detail: serde_json::Value) -> Response {
    (status, Json(ErrorResponse { detail })).into_response()
}

fn yarn_not_found() -> Response {
    error_response(StatusCode::NOT_FOUND, json!("Yarn not found"))
}

fn database_error() -> Response {
    error_response(StatusCode::INTERNAL_SERVER_ERROR, json!("database error"))
}
