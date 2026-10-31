//! Authenticated category CRUD for the versioned JSON API.

use axum::extract::{Path, State};
use axum::http::{HeaderMap, StatusCode};
use axum::response::{IntoResponse, Response};
use axum::routing::{get, put};
use axum::{Json, Router};
use chrono::{SecondsFormat, Utc};
use serde::{Deserialize, Serialize};
use sqlx::SqlitePool;

use crate::auth::bearer_user;
use crate::db::AppState;

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
struct CategoryWriteRequest {
    name: String,
}

#[derive(Debug, Serialize)]
struct CategoryResponse {
    id: i64,
    name: String,
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

    match sqlx::query_as::<_, (i64, String)>(
        "SELECT id, name FROM categories WHERE user_id = ? ORDER BY name",
    )
    .bind(user_id)
    .fetch_all(&state.pool)
    .await
    {
        Ok(rows) => Json(
            rows.into_iter()
                .map(|(id, name)| CategoryResponse { id, name })
                .collect::<Vec<_>>(),
        )
        .into_response(),
        Err(_) => database_error(),
    }
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
    let Some(name) = cleaned_name(&payload.name) else {
        return bad_name();
    };

    // The unique constraint makes concurrent duplicate requests idempotent.
    if sqlx::query("INSERT INTO categories (user_id, name) VALUES (?, ?) ON CONFLICT DO NOTHING")
        .bind(user_id)
        .bind(&name)
        .execute(&state.pool)
        .await
        .is_err()
    {
        return database_error();
    }

    match find_category(&state.pool, user_id, &name).await {
        Ok(Some(category)) => (StatusCode::CREATED, Json(category)).into_response(),
        Ok(None) => database_error(),
        Err(_) => database_error(),
    }
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
    let existing =
        sqlx::query_scalar::<_, i64>("SELECT id FROM categories WHERE id = ? AND user_id = ?")
            .bind(category_id)
            .bind(user_id)
            .fetch_optional(&state.pool)
            .await;
    match existing {
        Ok(Some(_)) => {}
        Ok(None) => return not_found(),
        Err(_) => return database_error(),
    }

    let Some(name) = cleaned_name(&payload.name) else {
        return bad_name();
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
        Ok(Some(_)) => return category_conflict(),
        Ok(None) => {}
        Err(_) => return database_error(),
    }

    let mut tx = match state.pool.begin().await {
        Ok(tx) => tx,
        Err(_) => return database_error(),
    };
    let update = sqlx::query("UPDATE categories SET name = ? WHERE id = ? AND user_id = ?")
        .bind(&name)
        .bind(category_id)
        .bind(user_id)
        .execute(&mut *tx)
        .await;
    match update {
        Ok(result) if result.rows_affected() == 0 => return not_found(),
        Ok(_) => {}
        Err(sqlx::Error::Database(error)) if error.is_unique_violation() => {
            return category_conflict();
        }
        Err(_) => return database_error(),
    }
    if bump_category_projects(&mut tx, user_id, category_id)
        .await
        .is_err()
        || tx.commit().await.is_err()
    {
        return database_error();
    }

    match find_category_by_id(&state.pool, user_id, category_id).await {
        Ok(Some(category)) => Json(category).into_response(),
        Ok(None) => not_found(),
        Err(_) => database_error(),
    }
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

    let mut tx = match state.pool.begin().await {
        Ok(tx) => tx,
        Err(_) => return database_error(),
    };
    if bump_category_projects(&mut tx, user_id, category_id)
        .await
        .is_err()
    {
        return database_error();
    }
    let result = sqlx::query("DELETE FROM categories WHERE id = ? AND user_id = ?")
        .bind(category_id)
        .bind(user_id)
        .execute(&mut *tx)
        .await;
    match result {
        Ok(result) if result.rows_affected() == 1 => {
            if tx.commit().await.is_err() {
                return database_error();
            }
            StatusCode::NO_CONTENT.into_response()
        }
        Ok(_) => not_found(),
        Err(_) => database_error(),
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
