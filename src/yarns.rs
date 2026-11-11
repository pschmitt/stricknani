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
    audit::{self, Action, ChangeField, ChangeItemV1, ChangeValue, Entity},
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
    /// Remote photo URLs fetched and attached on create (imports). Ignored on update.
    #[serde(default)]
    pub(crate) image_urls: Vec<String>,
}

#[derive(Debug, Deserialize)]
pub(crate) struct ListQuery {
    pub(crate) page: Option<i64>,
    pub(crate) favorite: Option<bool>,
    pub(crate) q: Option<String>,
    /// `mine` or `all`; administrators default to `all`, everyone else is always `mine`.
    #[serde(default)]
    pub(crate) scope: Option<String>,
    /// Administrator-only restriction to one owner's yarns.
    #[serde(default)]
    pub(crate) owner_id: Option<i64>,
}

#[derive(Debug, FromRow)]
struct YarnRow {
    id: i64,
    owner_id: i64,
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

#[derive(Debug, FromRow)]
struct YarnListRow {
    #[sqlx(flatten)]
    yarn: YarnRow,
    share_role: Option<String>,
    is_derived: bool,
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
    pub(crate) owner: Option<crate::resource_access::OwnerInfo>,
    /// The caller's role; set on API v2 only.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub(crate) access: Option<crate::resource_access::AccessInfo>,
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
    pub(crate) owner: Option<crate::resource_access::OwnerInfo>,
    /// The caller's role; set on API v2 only.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub(crate) access: Option<crate::resource_access::AccessInfo>,
}

#[derive(Debug, Serialize)]
pub(crate) struct YarnPage {
    pub(crate) items: Vec<YarnListItemResponse>,
    pub(crate) page: i64,
    pub(crate) per_page: i64,
    pub(crate) has_more: bool,
    pub(crate) scope: crate::resource_access::Scope,
}

pub(crate) enum YarnError {
    NotFound,
    /// The caller can read the yarn but may not do this to it.
    Forbidden,
    Database,
    Invalid(&'static str),
    Conflict(Box<YarnResponse>),
}

async fn yarn_grant(
    state: &AppState,
    actor_id: i64,
    yarn_id: i64,
    surface: crate::resource_access::Surface,
    need: crate::resource_access::Need,
) -> Result<crate::resource_access::Grant, YarnError> {
    use crate::resource_access::Denied;
    crate::resource_access::require(
        &state.pool,
        actor_id,
        crate::resource_access::ResourceKind::Yarn,
        yarn_id,
        surface,
        need,
    )
    .await
    .map_err(|denied| match denied {
        Denied::NotFound => YarnError::NotFound,
        Denied::Forbidden => YarnError::Forbidden,
        Denied::Database => YarnError::Database,
    })
}

impl YarnError {
    fn into_v1_response(self) -> Response {
        match self {
            // API v1 never grants read-only access; fail closed as absent.
            Self::NotFound | Self::Forbidden => yarn_not_found(),
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
    match list_for_owner(&state, user_id, query, crate::resource_access::Surface::V1).await {
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
    surface: crate::resource_access::Surface,
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
    let requested_scope = match query.scope.as_deref() {
        None => None,
        Some(value) => Some(
            crate::resource_access::Scope::parse(value)
                .ok_or(YarnError::Invalid("Invalid scope"))?,
        ),
    };
    let scope = crate::resource_access::effective_scope(&state.pool, user_id, requested_scope)
        .await
        .map_err(|_| YarnError::Database)?;
    let owner_filter = match scope {
        crate::resource_access::Scope::Mine => Some(user_id),
        crate::resource_access::Scope::All => query.owner_id,
    };

    let v2 = surface == crate::resource_access::Surface::V2;
    // API v2 lists what is shared with the caller, directly or through a
    // shared project, next to their own yarns.
    let share_user = (v2 && scope == crate::resource_access::Scope::Mine).then_some(user_id);
    let derived = crate::resource_access::derived_yarn_sql("yarns", 1);
    let list_sql = format!(
        "SELECT yarns.*, \
                (SELECT s.role FROM resource_shares s WHERE s.kind = 'yarn' \
                 AND s.resource_id = yarns.id AND s.user_id = ?1) AS share_role, \
                {derived} AS is_derived \
         FROM yarns \
         WHERE yarns.deleted_at IS NULL \
           AND ((?2 IS NULL OR owner_id = ?2) OR (?3 IS NOT NULL AND (EXISTS( \
                SELECT 1 FROM resource_shares s WHERE s.kind = 'yarn' \
                AND s.resource_id = yarns.id AND s.user_id = ?3) OR {derived}))) \
           AND (?4 IS NULL OR EXISTS (SELECT 1 FROM user_favorite_yarns f \
                                     WHERE f.user_id = ?1 AND f.yarn_id = yarns.id) = ?4) \
           AND (?5 IS NULL OR yarns.name LIKE ?5 ESCAPE '!' \
                OR COALESCE(yarns.brand, '') LIKE ?5 ESCAPE '!' \
                OR COALESCE(yarns.colorway, '') LIKE ?5 ESCAPE '!' \
                OR COALESCE(yarns.fiber_content, '') LIKE ?5 ESCAPE '!' \
                OR COALESCE(yarns.notes, '') LIKE ?5 ESCAPE '!') \
         ORDER BY updated_at DESC, id DESC LIMIT ?6 OFFSET ?7"
    );
    let rows = sqlx::query_as::<_, YarnListRow>(sqlx::AssertSqlSafe(list_sql.as_str()))
        .bind(user_id)
        .bind(owner_filter)
        .bind(share_user)
        .bind(
            query
                .favorite
                .map(|favorite| if favorite { 1_i64 } else { 0_i64 }),
        )
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
    let mut owners = std::collections::HashMap::new();
    let admin = v2
        && crate::resource_access::is_active_admin(&state.pool, user_id)
            .await
            .map_err(|_| YarnError::Database)?;
    for row in rows.into_iter().take(PAGE_SIZE as usize) {
        let YarnListRow {
            yarn,
            share_role,
            is_derived,
        } = row;
        let access = v2.then(|| {
            crate::resource_access::list_access(
                user_id,
                yarn.owner_id,
                admin,
                share_role.as_deref(),
                is_derived,
            )
        });
        let photos = match crate::media::yarn_photos(&state.pool, yarn.owner_id, yarn.id).await {
            Ok(photos) => photos,
            Err(_) => return Err(YarnError::Database),
        };
        if let std::collections::hash_map::Entry::Vacant(entry) = owners.entry(yarn.owner_id) {
            entry.insert(
                crate::resource_access::owner_info(
                    &state.pool,
                    user_id,
                    yarn.owner_id,
                    state.gravatar.enabled,
                )
                .await
                .map_err(|_| YarnError::Database)?,
            );
        }
        items.push(YarnListItemResponse {
            owner: owners.get(&yarn.owner_id).cloned().flatten(),
            id: yarn.id,
            name: yarn.name,
            brand: yarn.brand,
            colorway: yarn.colorway,
            weight_category: yarn.weight_category,
            is_favorite: favorite_ids.contains(&yarn.id),
            updated_at: yarn.updated_at,
            preview_url: crate::media::yarn_preview(&photos),
            access,
        });
    }

    Ok(YarnPage {
        items,
        page,
        per_page: PAGE_SIZE,
        has_more,
        scope,
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
    match load_for_owner(
        &state,
        user_id,
        yarn_id,
        crate::resource_access::Surface::V1,
    )
    .await
    {
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
    let audit_changes = audit::changes_from_items(yarn_audit_changes(None, &payload, &name));
    let prepared_images =
        if !payload.image_urls.is_empty() && state.external_imports && state.media_root.is_some() {
            let urls: Vec<String> = payload.image_urls.iter().take(6).cloned().collect();
            crate::image_import::prepare_yarn_images(state, &name, &urls).await
        } else {
            Vec::new()
        };
    let now = timestamp(Utc::now());
    let mut tx = crate::db::begin_write(&state.pool)
        .await
        .map_err(|_| YarnError::Database)?;
    let result = sqlx::query(
        "INSERT INTO yarns (owner_id, name, description, brand, colorway, dye_lot, \
         fiber_content, weight_category, recommended_needles, weight_grams, length_meters, \
         notes, link, is_ai_enhanced, created_at, updated_at) \
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
    )
    .bind(user_id)
    .bind(&name)
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
    if audit::record_with_data(
        &mut tx,
        user_id,
        Entity::Yarn,
        id,
        Action::Created,
        Some(&name),
        audit_changes.as_ref(),
    )
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
    if !prepared_images.is_empty() {
        crate::image_import::persist_yarn_images(state, user_id, id, &name, prepared_images).await;
    }
    load_for_owner(state, user_id, id, crate::resource_access::Surface::V1)
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
    match update_for_owner(
        &state,
        user_id,
        yarn_id,
        payload,
        crate::resource_access::Surface::V1,
    )
    .await
    {
        Ok(yarn) => Json(yarn).into_response(),
        Err(error) => error.into_v1_response(),
    }
}

pub(crate) async fn update_for_owner(
    state: &AppState,
    user_id: i64,
    yarn_id: i64,
    payload: YarnWriteRequest,
    surface: crate::resource_access::Surface,
) -> Result<YarnResponse, YarnError> {
    let actor_id = user_id;
    // Editors of a direct share may edit; derived read-only access never does.
    let user_id = yarn_grant(
        state,
        actor_id,
        yarn_id,
        surface,
        crate::resource_access::Need::Edit,
    )
    .await?
    .owner_id;
    let name = clean_name(&payload.name);
    let expected = match payload.expected_updated_at.as_deref() {
        Some(value) => match parse_timestamp(value) {
            Some(value) => Some(timestamp(value)),
            None => return Err(YarnError::Invalid("Invalid expected_updated_at")),
        },
        None => None,
    };

    let mut tx = crate::db::begin_write(&state.pool)
        .await
        .map_err(|_| YarnError::Database)?;
    let current = match get_owned_yarn_in(&mut tx, user_id, yarn_id).await {
        Ok(Some(yarn)) => yarn,
        Ok(None) => return Err(YarnError::NotFound),
        Err(_) => return Err(YarnError::Database),
    };
    let audit_items = yarn_audit_changes(Some(&current), &payload, &name);
    let audit_changes = audit::changes_from_items(audit_items.clone());
    if expected
        .as_deref()
        .is_some_and(|value| value != current.updated_at)
    {
        let _ = tx.rollback().await;
        let current = current_for_conflict(state, actor_id, yarn_id, surface).await?;
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
        .bind(&name)
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
                match current_for_conflict(state, actor_id, yarn_id, surface).await {
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
    // A save that changes nothing is not an event worth recording.
    if !audit_items.is_empty()
        && audit::record_with_data(
            &mut tx,
            actor_id,
            Entity::Yarn,
            yarn_id,
            Action::Updated,
            Some(&name),
            audit_changes.as_ref(),
        )
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
    load_for_owner(state, actor_id, yarn_id, surface)
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
    let actor_id = user_id;
    // Deleting is owner/administrator only, also for editors of a share.
    let user_id = yarn_grant(
        state,
        actor_id,
        yarn_id,
        crate::resource_access::Surface::V2,
        crate::resource_access::Need::Manage,
    )
    .await?
    .owner_id;
    let mut tx = crate::db::begin_write(&state.pool)
        .await
        .map_err(|_| YarnError::Database)?;
    let old_name = match get_owned_yarn_in(&mut tx, user_id, yarn_id).await {
        Ok(Some(yarn)) => yarn.name,
        Ok(None) => {
            let _ = tx.rollback().await;
            return Err(YarnError::NotFound);
        }
        Err(_) => {
            let _ = tx.rollback().await;
            return Err(YarnError::Database);
        }
    };
    if bump_linked_project_timestamps(&mut tx, user_id, yarn_id)
        .await
        .is_err()
    {
        let _ = tx.rollback().await;
        return Err(YarnError::Database);
    }
    // Recipients and viewers through shared projects learn of the removal.
    let delete_watch = match crate::sharing::yarn_candidates(&mut tx, yarn_id, user_id).await {
        Ok(candidates) => match crate::sharing::watch(&mut tx, candidates).await {
            Ok(watched) => watched,
            Err(_) => return Err(YarnError::Database),
        },
        Err(_) => return Err(YarnError::Database),
    };
    let deleted_at = timestamp(Utc::now());
    if sqlx::query(
        "INSERT INTO deletion_events (owner_id, entity_type, entity_id, changed_at) \
         VALUES (?, 'yarn', ?, ?)",
    )
    .bind(user_id)
    .bind(yarn_id)
    .bind(&deleted_at)
    .execute(&mut *tx)
    .await
    .is_err()
    {
        let _ = tx.rollback().await;
        return Err(YarnError::Database);
    }
    // Soft delete: the row, its photos, links and shares stay in the trash
    // until it is restored or purged.
    let deleted = sqlx::query(
        "UPDATE yarns SET deleted_at = ?, deleted_by = ? \
         WHERE id = ? AND owner_id = ? AND deleted_at IS NULL",
    )
    .bind(&deleted_at)
    .bind(actor_id)
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
    if crate::sharing::settle(&mut tx, delete_watch, false)
        .await
        .is_err()
    {
        let _ = tx.rollback().await;
        return Err(YarnError::Database);
    }
    if audit::record_lifecycle(
        &mut tx,
        Some(actor_id),
        user_id,
        Entity::Yarn,
        yarn_id,
        Action::Trashed,
        &old_name,
        None,
    )
    .await
    .is_err()
    {
        let _ = tx.rollback().await;
        return Err(YarnError::Database);
    }
    tx.commit().await.map_err(|_| YarnError::Database)
}

/// Take a yarn out of the trash. Only its owner or an administrator may.
///
/// Like a restored project, the yarn reappears for everyone who could see it
/// (shares and links to projects were kept) and sync clients fetch it again.
pub(crate) async fn restore_for_actor(
    state: &AppState,
    actor_id: i64,
    yarn_id: i64,
) -> Result<(), YarnError> {
    use crate::resource_access::{Need, ResourceKind};
    let grant = crate::resource_access::grant_including_trashed(
        &state.pool,
        actor_id,
        ResourceKind::Yarn,
        yarn_id,
    )
    .await
    .map_err(|_| YarnError::Database)?
    .ok_or(YarnError::NotFound)?;
    if !Need::Manage.allows(grant.access) {
        return Err(YarnError::Forbidden);
    }
    let owner_id = grant.owner_id;
    let mut tx = crate::db::begin_write(&state.pool)
        .await
        .map_err(|_| YarnError::Database)?;
    let (name, previous, deleted_at) = sqlx::query_as::<_, (String, String, Option<String>)>(
        "SELECT name, updated_at, deleted_at FROM yarns WHERE id = ? AND owner_id = ?",
    )
    .bind(yarn_id)
    .bind(owner_id)
    .fetch_optional(&mut *tx)
    .await
    .map_err(|_| YarnError::Database)?
    .ok_or(YarnError::NotFound)?;
    if deleted_at.is_none() {
        return Err(YarnError::NotFound);
    }
    let candidates = crate::sharing::yarn_candidates(&mut tx, yarn_id, owner_id)
        .await
        .map_err(|_| YarnError::Database)?;
    let watch = crate::sharing::watch(&mut tx, candidates)
        .await
        .map_err(|_| YarnError::Database)?;
    let updated_at = newer_timestamp(&previous);
    sqlx::query(
        "UPDATE yarns SET deleted_at = NULL, deleted_by = NULL, updated_at = ? \
         WHERE id = ? AND owner_id = ?",
    )
    .bind(updated_at)
    .bind(yarn_id)
    .bind(owner_id)
    .execute(&mut *tx)
    .await
    .map_err(|_| YarnError::Database)?;
    sqlx::query("DELETE FROM deletion_events WHERE entity_type = 'yarn' AND entity_id = ?")
        .bind(yarn_id)
        .execute(&mut *tx)
        .await
        .map_err(|_| YarnError::Database)?;
    bump_linked_project_timestamps(&mut tx, owner_id, yarn_id)
        .await
        .map_err(|_| YarnError::Database)?;
    crate::sharing::settle(&mut tx, watch, false)
        .await
        .map_err(|_| YarnError::Database)?;
    audit::record_lifecycle(
        &mut tx,
        Some(actor_id),
        owner_id,
        Entity::Yarn,
        yarn_id,
        Action::Restored,
        &name,
        None,
    )
    .await
    .map_err(|_| YarnError::Database)?;
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
    match set_favorite_for_owner(
        &state,
        user_id,
        yarn_id,
        favorite,
        crate::resource_access::Surface::V1,
    )
    .await
    {
        Ok(yarn) => Json(yarn).into_response(),
        Err(error) => error.into_v1_response(),
    }
}

pub(crate) async fn set_favorite_for_owner(
    state: &AppState,
    user_id: i64,
    yarn_id: i64,
    favorite: bool,
    surface: crate::resource_access::Surface,
) -> Result<YarnResponse, YarnError> {
    // Favourites are personal: any reader may set one.
    yarn_grant(
        state,
        user_id,
        yarn_id,
        surface,
        crate::resource_access::Need::Read,
    )
    .await?;
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
    load_for_owner(state, user_id, yarn_id, surface)
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
    surface: crate::resource_access::Surface,
) -> Result<Option<YarnResponse>, sqlx::Error> {
    get_yarn_response(
        &state.pool,
        user_id,
        yarn_id,
        state.gravatar.enabled,
        surface,
    )
    .await
}

async fn current_for_conflict(
    state: &AppState,
    user_id: i64,
    yarn_id: i64,
    surface: crate::resource_access::Surface,
) -> Result<YarnResponse, YarnError> {
    load_for_owner(state, user_id, yarn_id, surface)
        .await
        .map_err(|_| YarnError::Database)?
        .ok_or(YarnError::NotFound)
}

async fn get_yarn_response(
    pool: &SqlitePool,
    actor_id: i64,
    yarn_id: i64,
    gravatar_enabled: bool,
    surface: crate::resource_access::Surface,
) -> Result<Option<YarnResponse>, sqlx::Error> {
    let Some(grant) = crate::resource_access::grant(
        pool,
        actor_id,
        crate::resource_access::ResourceKind::Yarn,
        yarn_id,
        surface,
    )
    .await?
    else {
        return Ok(None);
    };
    let owner_id = grant.owner_id;
    let Some(yarn) = get_owned_yarn(pool, owner_id, yarn_id).await? else {
        return Ok(None);
    };
    let favorite = is_favorite(pool, actor_id, yarn_id).await?;
    let owner =
        crate::resource_access::owner_info(pool, actor_id, owner_id, gravatar_enabled).await?;
    let mut response = yarn_response(pool, actor_id, grant, yarn, favorite, surface).await?;
    response.owner = owner;
    response.access = (surface == crate::resource_access::Surface::V2).then(|| grant.into());
    Ok(Some(response))
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
    actor_id: i64,
    grant: crate::resource_access::Grant,
    yarn: YarnRow,
    is_favorite: bool,
    surface: crate::resource_access::Surface,
) -> Result<YarnResponse, sqlx::Error> {
    let user_id = grant.owner_id;
    // The owner (and administrators) see the owner's projects. On v2 every
    // reader also sees the linked projects they can open themselves; sharing a
    // yarn never reveals the projects that use it.
    let project_sql = format!(
        "SELECT p.id FROM projects p \
         JOIN project_yarns py ON py.project_id = p.id \
         WHERE py.yarn_id = ?1 AND p.deleted_at IS NULL AND ((?3 AND p.owner_id = ?2) OR (?4 AND {})) ORDER BY p.id",
        crate::resource_access::visible_sql(crate::resource_access::ResourceKind::Project, "p", 5)
    );
    let project_ids = sqlx::query_scalar::<_, i64>(sqlx::AssertSqlSafe(project_sql.as_str()))
        .bind(yarn.id)
        .bind(user_id)
        .bind(grant.access.can_manage())
        .bind(surface == crate::resource_access::Surface::V2)
        .bind(actor_id)
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
        owner: None,
        access: None,
    })
}

fn yarn_audit_changes(
    before: Option<&YarnRow>,
    payload: &YarnWriteRequest,
    name: &str,
) -> Vec<ChangeItemV1> {
    let mut items = Vec::new();
    let mut text = |field, old: Option<&str>, new: Option<&str>| {
        if let Some(item) = audit::text_change(field, old, new) {
            items.push(item);
        }
    };
    text(
        ChangeField::YarnName,
        before.map(|row| row.name.as_str()),
        Some(name),
    );
    text(
        ChangeField::YarnDescription,
        before.and_then(|row| row.description.as_deref()),
        payload
            .description
            .as_deref()
            .map(str::trim)
            .filter(|value| !value.is_empty()),
    );
    text(
        ChangeField::YarnBrand,
        before.and_then(|row| row.brand.as_deref()),
        payload
            .brand
            .as_deref()
            .map(str::trim)
            .filter(|value| !value.is_empty()),
    );
    text(
        ChangeField::YarnColorway,
        before.and_then(|row| row.colorway.as_deref()),
        payload
            .colorway
            .as_deref()
            .map(str::trim)
            .filter(|value| !value.is_empty()),
    );
    text(
        ChangeField::YarnDyeLot,
        before.and_then(|row| row.dye_lot.as_deref()),
        payload
            .dye_lot
            .as_deref()
            .map(str::trim)
            .filter(|value| !value.is_empty()),
    );
    text(
        ChangeField::YarnFiberContent,
        before.and_then(|row| row.fiber_content.as_deref()),
        payload
            .fiber_content
            .as_deref()
            .map(str::trim)
            .filter(|value| !value.is_empty()),
    );
    text(
        ChangeField::YarnWeightCategory,
        before.and_then(|row| row.weight_category.as_deref()),
        payload
            .weight_category
            .as_deref()
            .map(str::trim)
            .filter(|value| !value.is_empty()),
    );
    text(
        ChangeField::YarnRecommendedNeedles,
        before.and_then(|row| row.recommended_needles.as_deref()),
        payload
            .recommended_needles
            .as_deref()
            .map(str::trim)
            .filter(|value| !value.is_empty()),
    );
    text(
        ChangeField::YarnNotes,
        before.and_then(|row| row.notes.as_deref()),
        payload
            .notes
            .as_deref()
            .map(str::trim)
            .filter(|value| !value.is_empty()),
    );
    drop(text);
    if let Some(item) = audit::change_item(
        ChangeField::YarnWeightGrams,
        ChangeValue::Integer(before.and_then(|row| row.weight_grams)),
        ChangeValue::Integer(payload.weight_grams),
    ) {
        items.push(item);
    }
    if let Some(item) = audit::change_item(
        ChangeField::YarnLengthMeters,
        ChangeValue::Integer(before.and_then(|row| row.length_meters)),
        ChangeValue::Integer(payload.length_meters),
    ) {
        items.push(item);
    }
    if let Some(item) = audit::change_item(
        ChangeField::YarnIsAiEnhanced,
        ChangeValue::Boolean(before.is_some_and(|row| row.is_ai_enhanced)),
        ChangeValue::Boolean(payload.is_ai_enhanced),
    ) {
        items.push(item);
    }
    let before_link = before.and_then(|row| row.link.as_deref());
    let after_link = clean_optional(payload.link.clone());
    if before_link != after_link.as_deref() {
        items.push(ChangeItemV1 {
            field: ChangeField::YarnLink,
            before: ChangeValue::LinkChanged(before_link.is_some()),
            after: ChangeValue::LinkChanged(after_link.is_some()),
        });
    }
    items
}

/// Re-stamp the yarn owner's projects that link the yarn, because their
/// summaries show the yarn's name. Projects of other accounts that link the
/// yarn (possible after a transfer) are left alone, as before sharing existed.
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
    get_yarn_response(
        &state.pool,
        user_id,
        yarn_id,
        state.gravatar.enabled,
        crate::resource_access::Surface::V1,
    )
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
