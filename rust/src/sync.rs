//! Authenticated full and delta sync endpoints for projects and yarns.

use axum::extract::{Query, State};
use axum::http::{HeaderMap, StatusCode};
use axum::response::{IntoResponse, Response};
use axum::routing::get;
use axum::{Json, Router};
use base64::Engine;
use base64::engine::general_purpose::{URL_SAFE, URL_SAFE_NO_PAD};
use chrono::{DateTime, SecondsFormat, Timelike, Utc};
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use sqlx::{QueryBuilder, Sqlite, SqlitePool};

use crate::auth::bearer_user;
use crate::db::AppState;

const MAX_PAGE_SIZE: i64 = 50;
const MAX_CURSOR_BYTES: usize = 4096;
const CURSOR_VERSION: u8 = 1;

/// Build project and yarn sync routes, to be nested under `/api/v1`.
pub fn router() -> Router<AppState> {
    Router::new()
        .route("/sync/projects", get(sync_projects))
        .route("/sync/yarns", get(sync_yarns))
}

#[derive(Debug, Deserialize)]
struct SyncQuery {
    since: Option<String>,
    limit: Option<String>,
    cursor: Option<String>,
}

#[derive(Debug, Clone, Copy, Deserialize, Serialize, PartialEq, Eq)]
#[serde(rename_all = "lowercase")]
enum EntityKind {
    Project,
    Yarn,
}

impl EntityKind {
    fn table(self) -> &'static str {
        match self {
            Self::Project => "projects",
            Self::Yarn => "yarns",
        }
    }

    fn as_str(self) -> &'static str {
        match self {
            Self::Project => "project",
            Self::Yarn => "yarn",
        }
    }
}

#[derive(Debug, Clone, Deserialize, Serialize)]
struct CursorPosition {
    at: String,
    id: i64,
}

#[derive(Debug, Deserialize, Serialize)]
struct CursorPayload {
    v: u8,
    entity: EntityKind,
    user_id: i64,
    since: Option<String>,
    snapshot: String,
    updated: Option<CursorPosition>,
    deleted: Option<CursorPosition>,
    limit: i64,
}

#[derive(Debug, Clone)]
struct PageState {
    since: Option<String>,
    snapshot: String,
    updated: Option<CursorPosition>,
    deleted: Option<CursorPosition>,
    limit: i64,
}

#[derive(Debug)]
struct UpdateEvent {
    id: i64,
    at: String,
}

#[derive(Debug)]
struct DeleteEvent {
    entity_id: i64,
    event_id: i64,
    at: String,
}

#[derive(Debug)]
struct PageEvents {
    updated_ids: Vec<i64>,
    deleted_ids: Vec<i64>,
    updated_position: Option<CursorPosition>,
    deleted_position: Option<CursorPosition>,
    has_more: bool,
}

#[derive(Debug)]
enum SyncFailure {
    Database,
    Serialization,
}

impl From<sqlx::Error> for SyncFailure {
    fn from(_: sqlx::Error) -> Self {
        Self::Database
    }
}

impl From<serde_json::Error> for SyncFailure {
    fn from(_: serde_json::Error) -> Self {
        Self::Serialization
    }
}

#[derive(Debug, Serialize)]
struct SyncResponse {
    updated: Vec<Value>,
    deleted_ids: Vec<i64>,
    server_time: String,
    full_resync_required: bool,
    has_more: bool,
    next_cursor: Option<String>,
}

#[derive(Debug, Serialize)]
struct ErrorResponse {
    detail: Value,
}

async fn sync_projects(
    State(state): State<AppState>,
    headers: HeaderMap,
    Query(query): Query<SyncQuery>,
) -> Response {
    sync_entities(EntityKind::Project, state, headers, query).await
}

async fn sync_yarns(
    State(state): State<AppState>,
    headers: HeaderMap,
    Query(query): Query<SyncQuery>,
) -> Response {
    sync_entities(EntityKind::Yarn, state, headers, query).await
}

async fn sync_entities(
    kind: EntityKind,
    state: AppState,
    headers: HeaderMap,
    query: SyncQuery,
) -> Response {
    let user_id = match bearer_user(&state.pool, &headers).await {
        Ok(user_id) => user_id,
        Err(error) => return error.into_response(),
    };

    let limit = match parse_limit(query.limit.as_deref()) {
        Ok(limit) => limit,
        Err(()) => return error_response(StatusCode::UNPROCESSABLE_ENTITY, "Invalid limit"),
    };
    if query
        .cursor
        .as_ref()
        .is_some_and(|cursor| cursor.len() > MAX_CURSOR_BYTES)
    {
        return error_response(StatusCode::UNPROCESSABLE_ENTITY, "Sync cursor is too long");
    }
    let since = match query.since.as_deref() {
        Some(since) => match parse_timestamp(since) {
            Some(since) => Some(format_timestamp(since)),
            None => {
                return error_response(StatusCode::UNPROCESSABLE_ENTITY, "Invalid since timestamp");
            }
        },
        None => None,
    };

    let snapshot = format_timestamp(now_utc_micros());
    let page = match resolve_page(
        query.cursor.as_deref(),
        since.as_deref(),
        limit,
        kind,
        user_id,
        &snapshot,
    ) {
        Ok(page) => page,
        Err(PageError::MutuallyExclusive) => {
            return error_response(
                StatusCode::BAD_REQUEST,
                "Use either since or cursor, not both",
            );
        }
        Err(PageError::InvalidCursor) => {
            return error_response(StatusCode::BAD_REQUEST, "Invalid sync cursor");
        }
    };

    let output = match page {
        None => legacy_sync(&state, kind, user_id, since.as_deref()).await,
        Some(page) => bounded_sync(&state, kind, user_id, page).await,
    };
    match output {
        Ok(response) => Json(response).into_response(),
        Err(_) => error_response(StatusCode::INTERNAL_SERVER_ERROR, "database error"),
    }
}

async fn legacy_sync(
    state: &AppState,
    kind: EntityKind,
    user_id: i64,
    since: Option<&str>,
) -> Result<SyncResponse, SyncFailure> {
    let snapshot = format_timestamp(now_utc_micros());
    let updated_events = load_updates(&state.pool, kind, user_id, since, None, None, None).await?;
    let updated_ids: Vec<i64> = updated_events.iter().map(|event| event.id).collect();
    let updated = serialize_ids(state, kind, user_id, &updated_ids).await?;
    let deleted_ids = if let Some(since) = since {
        load_deletions(&state.pool, kind, user_id, since, None, None, None)
            .await?
            .into_iter()
            .map(|event| event.entity_id)
            .collect()
    } else {
        Vec::new()
    };

    Ok(SyncResponse {
        updated,
        deleted_ids,
        server_time: snapshot,
        full_resync_required: false,
        has_more: false,
        next_cursor: None,
    })
}

async fn bounded_sync(
    state: &AppState,
    kind: EntityKind,
    user_id: i64,
    page: PageState,
) -> Result<SyncResponse, SyncFailure> {
    let updates = load_updates(
        &state.pool,
        kind,
        user_id,
        page.since.as_deref(),
        Some(&page.snapshot),
        page.updated.as_ref(),
        Some(page.limit + 1),
    )
    .await?;
    let deletions = load_deletions(
        &state.pool,
        kind,
        user_id,
        page.since.as_deref().unwrap_or(""),
        Some(&page.snapshot),
        page.deleted.as_ref(),
        Some(page.limit + 1),
    )
    .await?;
    let events = choose_events(updates, deletions, page.limit);
    let updated = serialize_ids(state, kind, user_id, &events.updated_ids).await?;
    let next_cursor = if events.has_more {
        Some(encode_cursor(&CursorPayload {
            v: CURSOR_VERSION,
            entity: kind,
            user_id,
            since: page.since.clone(),
            snapshot: page.snapshot.clone(),
            updated: events.updated_position.or(page.updated),
            deleted: events.deleted_position.or(page.deleted),
            limit: page.limit,
        })?)
    } else {
        None
    };

    Ok(SyncResponse {
        updated,
        deleted_ids: events.deleted_ids,
        server_time: page.snapshot,
        full_resync_required: false,
        has_more: events.has_more,
        next_cursor,
    })
}

async fn load_updates(
    pool: &SqlitePool,
    kind: EntityKind,
    user_id: i64,
    since: Option<&str>,
    snapshot: Option<&str>,
    position: Option<&CursorPosition>,
    row_limit: Option<i64>,
) -> Result<Vec<UpdateEvent>, sqlx::Error> {
    let mut query = QueryBuilder::<Sqlite>::new("SELECT id, updated_at FROM ");
    query
        .push(kind.table())
        .push(" WHERE owner_id = ")
        .push_bind(user_id);
    if let Some(snapshot) = snapshot {
        query.push(" AND updated_at <= ").push_bind(snapshot);
    }
    push_position_filter(&mut query, "updated_at", "id", since, position);
    if let Some(row_limit) = row_limit {
        query
            .push(" ORDER BY updated_at ASC, id ASC LIMIT ")
            .push_bind(row_limit);
    } else {
        query.push(" ORDER BY updated_at ASC, id ASC");
    }
    query
        .build_query_as::<(i64, String)>()
        .fetch_all(pool)
        .await
        .map(|rows| {
            rows.into_iter()
                .map(|(id, at)| UpdateEvent { id, at })
                .collect()
        })
}

async fn load_deletions(
    pool: &SqlitePool,
    kind: EntityKind,
    user_id: i64,
    since: &str,
    snapshot: Option<&str>,
    position: Option<&CursorPosition>,
    row_limit: Option<i64>,
) -> Result<Vec<DeleteEvent>, sqlx::Error> {
    let mut query = QueryBuilder::<Sqlite>::new(
        "SELECT entity_id, id, changed_at FROM deletion_events \
         WHERE owner_id = ",
    );
    query
        .push_bind(user_id)
        .push(" AND entity_type = ")
        .push_bind(kind.as_str());
    if let Some(snapshot) = snapshot {
        query.push(" AND changed_at <= ").push_bind(snapshot);
    }
    push_position_filter(&mut query, "changed_at", "id", Some(since), position);
    if let Some(row_limit) = row_limit {
        query
            .push(" ORDER BY changed_at ASC, id ASC LIMIT ")
            .push_bind(row_limit);
    } else {
        query.push(" ORDER BY changed_at ASC, id ASC");
    }
    query
        .build_query_as::<(i64, i64, String)>()
        .fetch_all(pool)
        .await
        .map(|rows| {
            rows.into_iter()
                .map(|(entity_id, event_id, at)| DeleteEvent {
                    entity_id,
                    event_id,
                    at,
                })
                .collect()
        })
}

fn push_position_filter<'a>(
    query: &mut QueryBuilder<'a, Sqlite>,
    timestamp_column: &str,
    id_column: &str,
    since: Option<&'a str>,
    position: Option<&'a CursorPosition>,
) {
    if let Some(position) = position {
        query
            .push(" AND (")
            .push(timestamp_column)
            .push(" > ")
            .push_bind(position.at.as_str())
            .push(" OR (")
            .push(timestamp_column)
            .push(" = ")
            .push_bind(position.at.as_str())
            .push(" AND ")
            .push(id_column)
            .push(" > ")
            .push_bind(position.id)
            .push("))");
    } else if let Some(since) = since {
        query
            .push(" AND ")
            .push(timestamp_column)
            .push(" > ")
            .push_bind(since);
    }
}

fn choose_events(updates: Vec<UpdateEvent>, deletions: Vec<DeleteEvent>, limit: i64) -> PageEvents {
    let mut update_index = 0;
    let mut delete_index = 0;
    let mut updated_ids = Vec::new();
    let mut deleted_ids = Vec::new();
    let mut updated_position = None;
    let mut deleted_position = None;

    while (updated_ids.len() + deleted_ids.len()) < limit as usize {
        let update = updates.get(update_index);
        let deletion = deletions.get(delete_index);
        if update.is_none() && deletion.is_none() {
            break;
        }

        let take_update = match (update, deletion) {
            (Some(update), Some(deletion)) => {
                (update.at.as_str(), 0, update.id) <= (deletion.at.as_str(), 1, deletion.event_id)
            }
            (Some(_), None) => true,
            (None, Some(_)) => false,
            (None, None) => false,
        };
        if take_update {
            let update = &updates[update_index];
            updated_ids.push(update.id);
            updated_position = Some(CursorPosition {
                at: update.at.clone(),
                id: update.id,
            });
            update_index += 1;
        } else {
            let deletion = &deletions[delete_index];
            deleted_ids.push(deletion.entity_id);
            deleted_position = Some(CursorPosition {
                at: deletion.at.clone(),
                id: deletion.event_id,
            });
            delete_index += 1;
        }
    }

    PageEvents {
        updated_ids,
        deleted_ids,
        updated_position,
        deleted_position,
        has_more: update_index < updates.len() || delete_index < deletions.len(),
    }
}

async fn serialize_ids(
    state: &AppState,
    kind: EntityKind,
    user_id: i64,
    ids: &[i64],
) -> Result<Vec<Value>, sqlx::Error> {
    let mut values = Vec::with_capacity(ids.len());
    for id in ids {
        let value = match kind {
            EntityKind::Project => crate::projects::sync_value(state, user_id, *id).await?,
            EntityKind::Yarn => crate::yarns::sync_value(state, user_id, *id).await?,
        };
        if let Some(value) = value {
            values.push(value);
        }
    }
    Ok(values)
}

enum PageError {
    MutuallyExclusive,
    InvalidCursor,
}

fn resolve_page(
    raw_cursor: Option<&str>,
    since: Option<&str>,
    limit: Option<i64>,
    kind: EntityKind,
    user_id: i64,
    snapshot: &str,
) -> Result<Option<PageState>, PageError> {
    if let Some(raw_cursor) = raw_cursor {
        if since.is_some() {
            return Err(PageError::MutuallyExclusive);
        }
        let mut cursor = decode_cursor(raw_cursor)?;
        if cursor.v != CURSOR_VERSION || cursor.entity != kind || cursor.user_id != user_id {
            return Err(PageError::InvalidCursor);
        }
        if !(1..=MAX_PAGE_SIZE).contains(&cursor.limit) {
            return Err(PageError::InvalidCursor);
        }
        if let Some(limit) = limit {
            cursor.limit = limit;
        }
        cursor.since = cursor
            .since
            .as_deref()
            .map(normalized_timestamp)
            .transpose()
            .map_err(|_| PageError::InvalidCursor)?;
        cursor.snapshot =
            normalized_timestamp(&cursor.snapshot).map_err(|_| PageError::InvalidCursor)?;
        cursor.updated = cursor
            .updated
            .map(normalize_position)
            .transpose()
            .map_err(|_| PageError::InvalidCursor)?;
        cursor.deleted = cursor
            .deleted
            .map(normalize_position)
            .transpose()
            .map_err(|_| PageError::InvalidCursor)?;
        return Ok(Some(PageState {
            since: cursor.since,
            snapshot: cursor.snapshot,
            updated: cursor.updated,
            deleted: cursor.deleted,
            limit: cursor.limit,
        }));
    }

    Ok(limit.map(|limit| PageState {
        since: since.map(str::to_owned),
        snapshot: snapshot.to_owned(),
        updated: None,
        deleted: None,
        limit,
    }))
}

fn parse_limit(raw_limit: Option<&str>) -> Result<Option<i64>, ()> {
    raw_limit
        .map(|value| {
            value
                .parse::<i64>()
                .ok()
                .filter(|limit| (1..=MAX_PAGE_SIZE).contains(limit))
                .ok_or(())
        })
        .transpose()
}

fn decode_cursor(raw: &str) -> Result<CursorPayload, PageError> {
    let mut padded = raw.to_owned();
    while !padded.len().is_multiple_of(4) {
        padded.push('=');
    }
    let decoded = URL_SAFE
        .decode(padded)
        .map_err(|_| PageError::InvalidCursor)?;
    let mut cursor: CursorPayload =
        serde_json::from_slice(&decoded).map_err(|_| PageError::InvalidCursor)?;
    cursor.since = cursor
        .since
        .as_deref()
        .map(normalized_timestamp)
        .transpose()
        .map_err(|_| PageError::InvalidCursor)?;
    Ok(cursor)
}

fn encode_cursor(cursor: &CursorPayload) -> Result<String, serde_json::Error> {
    serde_json::to_vec(cursor).map(|payload| URL_SAFE_NO_PAD.encode(payload))
}

fn normalize_position(position: CursorPosition) -> Result<CursorPosition, ()> {
    if position.id < 1 {
        return Err(());
    }
    Ok(CursorPosition {
        at: normalized_timestamp(&position.at)?,
        id: position.id,
    })
}

fn normalized_timestamp(value: &str) -> Result<String, ()> {
    parse_timestamp(value).map(format_timestamp).ok_or(())
}

fn parse_timestamp(value: &str) -> Option<DateTime<Utc>> {
    crate::time::parse_utc(value)
}

fn format_timestamp(value: DateTime<Utc>) -> String {
    value.to_rfc3339_opts(SecondsFormat::Micros, true)
}

fn now_utc_micros() -> DateTime<Utc> {
    let now = Utc::now();
    now.with_nanosecond(now.timestamp_subsec_micros() * 1_000)
        .unwrap_or(now)
}

fn error_response(status: StatusCode, detail: &str) -> Response {
    (
        status,
        Json(ErrorResponse {
            detail: json!(detail),
        }),
    )
        .into_response()
}
