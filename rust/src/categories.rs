//! Authenticated category CRUD for the versioned JSON API.

use axum::extract::{Path, State};
use axum::http::{HeaderMap, StatusCode};
use axum::response::{IntoResponse, Response};
use axum::routing::{get, put};
use axum::{Json, Router};
use chrono::{SecondsFormat, Utc};
use serde::{Deserialize, Serialize};
use sqlx::SqlitePool;

use crate::db::AppState;
use crate::{
    audit::{self, Action, ChangeField, ChangeItemV1, ChangeSetV1, ChangeValue, Entity},
    auth::bearer_user,
};

/// Build the category endpoints, to be nested under `/api/v1`.
pub fn router() -> Router<AppState> {
    Router::new()
        .route("/categories", get(list_categories).post(create_category))
        .route("/sync/categories", get(sync_categories))
        .route(
            "/categories/{category_id}",
            put(update_category).delete(delete_category),
        )
}

#[derive(Debug, Deserialize)]
pub(crate) struct CategoryWriteRequest {
    pub(crate) name: String,
}

#[derive(Debug, Serialize)]
pub(crate) struct CategoryResponse {
    pub(crate) id: i64,
    pub(crate) name: String,
}

pub(crate) enum CategoryError {
    InvalidName,
    Conflict,
    NotFound,
    Database,
}

impl CategoryError {
    fn into_v1_response(self) -> Response {
        match self {
            Self::InvalidName => bad_name(),
            Self::Conflict => category_conflict(),
            Self::NotFound => not_found(),
            Self::Database => database_error(),
        }
    }
}

#[derive(Debug, Deserialize)]
struct CategorySyncQuery {
    since: Option<String>,
}

#[derive(Debug, Serialize)]
struct CategorySyncResponse {
    updated: Vec<CategoryResponse>,
    deleted_ids: Vec<i64>,
    server_time: String,
}

#[derive(Debug, Serialize)]
struct ErrorResponse {
    detail: String,
}

async fn list_categories(State(state): State<AppState>, headers: HeaderMap) -> Response {
    let user_id = match bearer_user(&state.pool, &headers).await {
        Ok(user_id) => user_id,
        Err(error) => return error.into_response(),
    };

    match list_for_owner(&state, user_id).await {
        Ok(categories) => Json(categories).into_response(),
        Err(error) => error.into_v1_response(),
    }
}

pub(crate) async fn list_for_owner(
    state: &AppState,
    user_id: i64,
) -> Result<Vec<CategoryResponse>, CategoryError> {
    sqlx::query_as::<_, (i64, String)>(
        "SELECT id, name FROM categories WHERE user_id = ? ORDER BY name",
    )
    .bind(user_id)
    .fetch_all(&state.pool)
    .await
    .map(|rows| {
        rows.into_iter()
            .map(|(id, name)| CategoryResponse { id, name })
            .collect()
    })
    .map_err(|_| CategoryError::Database)
}

async fn sync_categories(
    State(state): State<AppState>,
    headers: HeaderMap,
    axum::extract::Query(query): axum::extract::Query<CategorySyncQuery>,
) -> Response {
    let _ignored_since = query.since;
    let user_id = match bearer_user(&state.pool, &headers).await {
        Ok(user_id) => user_id,
        Err(error) => return error.into_response(),
    };

    let rows = sqlx::query_as::<_, (i64, String)>(
        "SELECT id, name FROM categories WHERE user_id = ? ORDER BY name",
    )
    .bind(user_id)
    .fetch_all(&state.pool)
    .await;
    match rows {
        Ok(rows) => Json(CategorySyncResponse {
            updated: rows
                .into_iter()
                .map(|(id, name)| CategoryResponse { id, name })
                .collect(),
            deleted_ids: Vec::new(),
            server_time: Utc::now().to_rfc3339_opts(SecondsFormat::Micros, true),
        })
        .into_response(),
        Err(_) => database_error(),
    }
}

async fn create_category(
    State(state): State<AppState>,
    headers: HeaderMap,
    Json(payload): Json<CategoryWriteRequest>,
) -> Response {
    let user_id = match bearer_user(&state.pool, &headers).await {
        Ok(user_id) => user_id,
        Err(error) => return error.into_response(),
    };
    match create_for_owner(&state, user_id, payload).await {
        Ok(category) => (StatusCode::CREATED, Json(category)).into_response(),
        Err(error) => error.into_v1_response(),
    }
}

pub(crate) async fn create_for_owner(
    state: &AppState,
    user_id: i64,
    payload: CategoryWriteRequest,
) -> Result<CategoryResponse, CategoryError> {
    let Some(name) = cleaned_name(&payload.name) else {
        return Err(CategoryError::InvalidName);
    };

    // Keep the audit event atomic with the insert. A duplicate replay remains
    // idempotent and must not create another event.
    let mut tx = state
        .pool
        .begin()
        .await
        .map_err(|_| CategoryError::Database)?;
    let insert =
        sqlx::query("INSERT INTO categories (user_id, name) VALUES (?, ?) ON CONFLICT DO NOTHING")
            .bind(user_id)
            .bind(&name)
            .execute(&mut *tx)
            .await;
    let (inserted, category_id) = match insert {
        Ok(result) => (result.rows_affected() == 1, result.last_insert_rowid()),
        Err(_) => return Err(CategoryError::Database),
    };
    if inserted
        && audit::record_with_data(
            &mut tx,
            user_id,
            Entity::Category,
            category_id,
            Action::Created,
            Some(&name),
            ChangeItemV1::new(
                ChangeField::CategoryName,
                ChangeValue::Text(None),
                ChangeValue::Text(Some(name.clone())),
            )
            .and_then(|item| ChangeSetV1::new(vec![item]))
            .as_ref(),
        )
        .await
        .is_err()
    {
        return Err(CategoryError::Database);
    }
    tx.commit().await.map_err(|_| CategoryError::Database)?;

    find_category(&state.pool, user_id, &name)
        .await
        .map_err(|_| CategoryError::Database)?
        .ok_or(CategoryError::Database)
}

async fn update_category(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(category_id): Path<i64>,
    Json(payload): Json<CategoryWriteRequest>,
) -> Response {
    let user_id = match bearer_user(&state.pool, &headers).await {
        Ok(user_id) => user_id,
        Err(error) => return error.into_response(),
    };
    match update_for_owner(&state, user_id, category_id, payload).await {
        Ok(category) => Json(category).into_response(),
        Err(error) => error.into_v1_response(),
    }
}

pub(crate) async fn update_for_owner(
    state: &AppState,
    user_id: i64,
    category_id: i64,
    payload: CategoryWriteRequest,
) -> Result<CategoryResponse, CategoryError> {
    let existing =
        sqlx::query_scalar::<_, i64>("SELECT id FROM categories WHERE id = ? AND user_id = ?")
            .bind(category_id)
            .bind(user_id)
            .fetch_optional(&state.pool)
            .await;
    match existing {
        Ok(Some(_)) => {}
        Ok(None) => return Err(CategoryError::NotFound),
        Err(_) => return Err(CategoryError::Database),
    }

    let Some(name) = cleaned_name(&payload.name) else {
        return Err(CategoryError::InvalidName);
    };

    let conflict = sqlx::query_scalar::<_, i64>(
        "SELECT id FROM categories \
         WHERE user_id = ? AND name = ? COLLATE NOCASE AND id != ?",
    )
    .bind(user_id)
    .bind(&name)
    .bind(category_id)
    .fetch_optional(&state.pool)
    .await;
    match conflict {
        Ok(Some(_)) => return Err(CategoryError::Conflict),
        Ok(None) => {}
        Err(_) => return Err(CategoryError::Database),
    }

    let mut tx = state
        .pool
        .begin()
        .await
        .map_err(|_| CategoryError::Database)?;
    let old_name =
        sqlx::query_scalar::<_, String>("SELECT name FROM categories WHERE id = ? AND user_id = ?")
            .bind(category_id)
            .bind(user_id)
            .fetch_optional(&mut *tx)
            .await
            .map_err(|_| CategoryError::Database)?
            .ok_or(CategoryError::NotFound)?;
    let update = sqlx::query("UPDATE categories SET name = ? WHERE id = ? AND user_id = ?")
        .bind(&name)
        .bind(category_id)
        .bind(user_id)
        .execute(&mut *tx)
        .await;
    match update {
        Ok(result) if result.rows_affected() == 0 => return Err(CategoryError::NotFound),
        Ok(_) => {}
        Err(sqlx::Error::Database(error)) if error.is_unique_violation() => {
            return Err(CategoryError::Conflict);
        }
        Err(_) => return Err(CategoryError::Database),
    }
    if bump_category_projects(&mut tx, user_id, category_id)
        .await
        .is_err()
    {
        let _ = tx.rollback().await;
        return Err(CategoryError::Database);
    }
    let changes = ChangeItemV1::new(
        ChangeField::CategoryName,
        ChangeValue::Text(Some(old_name.clone())),
        ChangeValue::Text(Some(name.clone())),
    )
    .and_then(|item| ChangeSetV1::new(vec![item]));
    if audit::record_with_data(
        &mut tx,
        user_id,
        Entity::Category,
        category_id,
        Action::Updated,
        Some(&name),
        changes.as_ref(),
    )
    .await
    .is_err()
    {
        let _ = tx.rollback().await;
        return Err(CategoryError::Database);
    }
    tx.commit().await.map_err(|_| CategoryError::Database)?;

    find_category_by_id(&state.pool, user_id, category_id)
        .await
        .map_err(|_| CategoryError::Database)?
        .ok_or(CategoryError::NotFound)
}

async fn delete_category(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(category_id): Path<i64>,
) -> Response {
    let user_id = match bearer_user(&state.pool, &headers).await {
        Ok(user_id) => user_id,
        Err(error) => return error.into_response(),
    };

    match delete_for_owner(&state, user_id, category_id).await {
        Ok(()) => StatusCode::NO_CONTENT.into_response(),
        Err(error) => error.into_v1_response(),
    }
}

pub(crate) async fn delete_for_owner(
    state: &AppState,
    user_id: i64,
    category_id: i64,
) -> Result<(), CategoryError> {
    let mut tx = state
        .pool
        .begin()
        .await
        .map_err(|_| CategoryError::Database)?;
    let old_name =
        sqlx::query_scalar::<_, String>("SELECT name FROM categories WHERE id = ? AND user_id = ?")
            .bind(category_id)
            .bind(user_id)
            .fetch_optional(&mut *tx)
            .await
            .map_err(|_| CategoryError::Database)?
            .ok_or(CategoryError::NotFound)?;
    if bump_category_projects(&mut tx, user_id, category_id)
        .await
        .is_err()
    {
        return Err(CategoryError::Database);
    }
    let result = sqlx::query("DELETE FROM categories WHERE id = ? AND user_id = ?")
        .bind(category_id)
        .bind(user_id)
        .execute(&mut *tx)
        .await;
    match result {
        Ok(result) if result.rows_affected() == 1 => {
            if audit::record_with_data(
                &mut tx,
                user_id,
                Entity::Category,
                category_id,
                Action::Deleted,
                Some(&old_name),
                None,
            )
            .await
            .is_err()
            {
                let _ = tx.rollback().await;
                return Err(CategoryError::Database);
            }
            tx.commit().await.map_err(|_| CategoryError::Database)
        }
        Ok(_) => Err(CategoryError::NotFound),
        Err(_) => Err(CategoryError::Database),
    }
}

async fn bump_category_projects(
    tx: &mut sqlx::Transaction<'_, sqlx::Sqlite>,
    user_id: i64,
    category_id: i64,
) -> Result<(), sqlx::Error> {
    let rows: Vec<(i64, String)> = sqlx::query_as(
        "SELECT id, updated_at FROM projects WHERE owner_id = ? AND category_id = ?",
    )
    .bind(user_id)
    .bind(category_id)
    .fetch_all(&mut **tx)
    .await?;
    for (id, previous) in rows {
        let previous = chrono::DateTime::parse_from_rfc3339(&previous)
            .map_err(|error| sqlx::Error::Decode(Box::new(error)))?
            .with_timezone(&chrono::Utc);
        let now = chrono::Utc::now().max(previous + chrono::Duration::microseconds(1));
        sqlx::query("UPDATE projects SET updated_at = ? WHERE id = ? AND owner_id = ?")
            .bind(now.to_rfc3339_opts(chrono::SecondsFormat::Micros, true))
            .bind(id)
            .bind(user_id)
            .execute(&mut **tx)
            .await?;
    }
    Ok(())
}

fn cleaned_name(name: &str) -> Option<String> {
    let name = name.trim();
    (!name.is_empty()).then(|| name.to_owned())
}

async fn find_category(
    pool: &SqlitePool,
    user_id: i64,
    name: &str,
) -> Result<Option<CategoryResponse>, sqlx::Error> {
    sqlx::query_as::<_, (i64, String)>(
        "SELECT id, name FROM categories WHERE user_id = ? AND name = ? COLLATE NOCASE",
    )
    .bind(user_id)
    .bind(name)
    .fetch_optional(pool)
    .await
    .map(|row| row.map(|(id, name)| CategoryResponse { id, name }))
}

async fn find_category_by_id(
    pool: &SqlitePool,
    user_id: i64,
    category_id: i64,
) -> Result<Option<CategoryResponse>, sqlx::Error> {
    sqlx::query_as::<_, (i64, String)>(
        "SELECT id, name FROM categories WHERE user_id = ? AND id = ?",
    )
    .bind(user_id)
    .bind(category_id)
    .fetch_optional(pool)
    .await
    .map(|row| row.map(|(id, name)| CategoryResponse { id, name }))
}

fn bad_name() -> Response {
    error_response(StatusCode::BAD_REQUEST, "Category name is required")
}

fn category_conflict() -> Response {
    error_response(
        StatusCode::CONFLICT,
        "Another category already uses that name",
    )
}

fn not_found() -> Response {
    error_response(StatusCode::NOT_FOUND, "Not Found")
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
