//! Administrator backups: listing, restore preview and the guarded restore.
//!
//! Restoring needs an administrator, a recent re-authentication (the password
//! or OIDC step-up of `/account/reauth`, action `restore_backup`) and the exact
//! backup name as confirmation. See `crate::backups` for the mechanism.

use std::collections::BTreeMap;

use axum::{
    Json, Router,
    extract::{DefaultBodyLimit, Path, State, rejection::JsonRejection},
    http::{HeaderMap, StatusCode},
    response::{IntoResponse, Response},
    routing::{get, post},
};
use chrono::{DateTime, SecondsFormat, Utc};
use serde::{Deserialize, Serialize};

use super::{ApiV2State, Envelope, access, api_error, api_error_fields};
use crate::{
    auth_stepup_service::{self, CredentialKind, StepupError},
    backups::{Accepted, Actor, BackupService, Entry, MediaOption, Preview, RestoreError},
    oidc_identity_service::{self, RecentAction},
};

pub(super) fn router() -> Router<ApiV2State> {
    Router::new()
        .route("/admin/backups", get(list))
        .route("/admin/backups/{name}/restore-preview", get(preview))
        .route("/admin/backups/{name}/restore", post(restore))
        .layer(DefaultBodyLimit::max(4 * 1024))
}

#[derive(Serialize)]
struct ListData {
    available: bool,
    server_schema_version: i64,
    backups: Vec<Entry>,
    /// The newest `pre-restore-*` file: what "Undo last restore" restores.
    latest_pre_restore: Option<String>,
    pending_restore: Option<PendingView>,
    last_restore: Option<OutcomeView>,
    data_epoch: Option<String>,
    /// The server exits after staging a restore (and relies on a supervisor).
    auto_restart: bool,
}

#[derive(Serialize)]
struct PendingView {
    backup: String,
    pre_restore: String,
    requested_at: String,
}

#[derive(Serialize)]
struct OutcomeView {
    status: String,
    backup: String,
    pre_restore: String,
    media: String,
    finished_at: String,
    requested_by_email: String,
    /// The requesting administrator's avatar path, when that account still exists.
    requested_by_avatar_url: Option<String>,
    error: Option<String>,
    media_added: Option<u64>,
    media_error: Option<String>,
}

fn rfc3339(seconds: i64) -> String {
    DateTime::<Utc>::from_timestamp(seconds, 0)
        .unwrap_or_default()
        .to_rfc3339_opts(SecondsFormat::Secs, true)
}

async fn outcome_view(
    outcome: &crate::backups::Outcome,
    pool: &sqlx::SqlitePool,
    gravatar_enabled: bool,
) -> OutcomeView {
    let requester: Option<(i64, i64, Option<String>)> = sqlx::query_as(
        "SELECT u.id, u.use_gravatar, \
         (SELECT filename FROM user_avatars WHERE user_id = u.id) \
         FROM users AS u WHERE u.email = ?",
    )
    .bind(&outcome.requested_by_email)
    .fetch_optional(pool)
    .await
    .ok()
    .flatten();
    let requested_by_avatar_url = requester.and_then(|(id, use_gravatar, filename)| {
        crate::media::admin_avatar(id, filename.as_deref(), use_gravatar != 0, gravatar_enabled)
            .map(|(url, _)| url)
    });
    OutcomeView {
        status: outcome.status.clone(),
        backup: outcome.backup.clone(),
        pre_restore: outcome.pre_restore.clone(),
        media: outcome.media.clone(),
        finished_at: rfc3339(outcome.finished_at),
        requested_by_email: outcome.requested_by_email.clone(),
        requested_by_avatar_url,
        error: outcome.error.clone(),
        media_added: outcome.media_added,
        media_error: outcome.media_error.clone(),
    }
}

fn internal_error() -> Response {
    api_error(
        StatusCode::INTERNAL_SERVER_ERROR,
        "internal_error",
        "Request failed",
    )
}

fn unavailable() -> Response {
    api_error(
        StatusCode::SERVICE_UNAVAILABLE,
        "backups_unavailable",
        "Backups are not available on this server",
    )
}

async fn list(State(state): State<ApiV2State>, headers: HeaderMap) -> Response {
    if let Err(response) = access::admin_user(&state.app, &headers).await {
        return *response;
    }
    let Some(service) = state.app.backups.as_deref() else {
        return Json(Envelope {
            data: ListData {
                available: false,
                server_schema_version: crate::backups::latest_migration(),
                backups: Vec::new(),
                latest_pre_restore: None,
                pending_restore: None,
                last_restore: None,
                data_epoch: None,
                auto_restart: false,
            },
        })
        .into_response();
    };
    let Ok(backups) = service.entries().await else {
        return internal_error();
    };
    let latest_pre_restore = backups
        .iter()
        .find(|entry| entry.kind == crate::backups::Kind::PreRestore && entry.restorable)
        .map(|entry| entry.name.clone());
    let last_restore = match service.last_restore() {
        Some(outcome) => {
            Some(outcome_view(outcome, &state.app.pool, state.app.gravatar.enabled).await)
        }
        None => None,
    };
    Json(Envelope {
        data: ListData {
            available: true,
            server_schema_version: crate::backups::latest_migration(),
            backups,
            latest_pre_restore,
            pending_restore: service.staged_request().map(|request| PendingView {
                backup: request.backup,
                pre_restore: request.pre_restore,
                requested_at: rfc3339(request.requested_at),
            }),
            last_restore,
            data_epoch: Some(service.data_epoch().to_owned()),
            auto_restart: service.config().auto_restart,
        },
    })
    .into_response()
}

async fn preview(
    State(state): State<ApiV2State>,
    headers: HeaderMap,
    Path(name): Path<String>,
) -> Response {
    if let Err(response) = access::admin_user(&state.app, &headers).await {
        return *response;
    }
    let Some(service) = state.app.backups.as_deref() else {
        return unavailable();
    };
    match service.preview(&state.app.pool, &name).await {
        Ok(preview) => Json(Envelope { data: preview }).into_response(),
        Err(error) => restore_error(error),
    }
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct RestoreRequest {
    /// The exact backup name.
    confirmation: String,
    #[serde(default = "default_media")]
    media: MediaOption,
}

fn default_media() -> MediaOption {
    MediaOption::Skip
}

async fn restore(
    State(state): State<ApiV2State>,
    headers: HeaderMap,
    Path(name): Path<String>,
    request: Result<Json<RestoreRequest>, JsonRejection>,
) -> Response {
    let actor_id = match access::admin_mutation_user(&state.app, &headers).await {
        Ok(actor) => actor,
        Err(response) => return *response,
    };
    let Some(service) = state.app.backups.clone() else {
        return unavailable();
    };
    let Json(request) = match request {
        Ok(request) => request,
        Err(_) => {
            return api_error(
                StatusCode::BAD_REQUEST,
                "invalid_json",
                "Request body is invalid",
            );
        }
    };
    let pool = &state.app.pool;
    // Re-authentication: a fresh browser stamp or a one-time native proof,
    // checked now without consuming it, so a typo costs nothing.
    let credential = match auth_stepup_service::current_credential(
        pool,
        &headers,
        actor_id,
        state.app.secure_cookies,
    )
    .await
    {
        Ok(credential) => credential,
        Err(error) => return stepup_error(error),
    };
    let proof_hash = match credential.kind {
        CredentialKind::Browser => None,
        CredentialKind::Native => match auth_stepup_service::native_proof_hash(&headers) {
            Ok(hash) => Some(hash),
            Err(error) => return stepup_error(error),
        },
    };
    let recent = match auth_stepup_service::recent_auth(credential, proof_hash.as_deref()) {
        Ok(recent) => recent,
        Err(error) => return stepup_error(error),
    };
    let now = Utc::now().timestamp();
    if oidc_identity_service::validate_recent_auth(
        pool,
        recent,
        actor_id,
        actor_id,
        RecentAction::RestoreBackup,
        now,
    )
    .await
    .is_err()
    {
        return reauth_required();
    }
    // Everything that needs no side effect, before a proof is spent.
    if let Err(error) = service
        .validate(&name, &request.confirmation, request.media)
        .await
    {
        return restore_error(error);
    }
    let consumed = async {
        let mut tx = crate::db::begin_write(pool).await?;
        oidc_identity_service::consume_recent_auth_tx(
            &mut tx,
            recent,
            actor_id,
            actor_id,
            RecentAction::RestoreBackup,
            now,
        )
        .await
        .map_err(|_| sqlx::Error::RowNotFound)?;
        tx.commit().await
    }
    .await;
    if consumed.is_err() {
        return reauth_required();
    }
    let email: Option<String> = sqlx::query_scalar("SELECT email FROM users WHERE id = ?")
        .bind(actor_id)
        .fetch_optional(pool)
        .await
        .ok()
        .flatten();
    let actor = Actor {
        id: actor_id,
        email: email.unwrap_or_default(),
    };
    match service
        .request_restore(pool, actor, &name, &request.confirmation, request.media)
        .await
    {
        Ok(accepted) => {
            restart_soon(&service);
            (StatusCode::ACCEPTED, Json(Envelope { data: accepted })).into_response()
        }
        Err(error) => restore_error(error),
    }
}

/// Let the response reach the client, then stop for the supervisor to restart.
fn restart_soon(service: &std::sync::Arc<BackupService>) {
    if !service.config().auto_restart {
        return;
    }
    tokio::spawn(async {
        tokio::time::sleep(std::time::Duration::from_millis(1500)).await;
        crate::backups::request_restart();
    });
}

fn reauth_required() -> Response {
    api_error(
        StatusCode::FORBIDDEN,
        "reauth_required",
        "Reauthentication is required",
    )
}

fn stepup_error(error: StepupError) -> Response {
    match error {
        StepupError::Unauthorized => api_error(
            StatusCode::UNAUTHORIZED,
            "session_expired",
            "Sign in to continue",
        ),
        StepupError::InvalidCredentials => api_error(
            StatusCode::UNAUTHORIZED,
            "invalid_credentials",
            "Password is incorrect",
        ),
        StepupError::Identity(_) => reauth_required(),
        StepupError::Verification | StepupError::Database(_) => internal_error(),
    }
}

fn restore_error(error: RestoreError) -> Response {
    match error {
        RestoreError::NotFound => api_error(StatusCode::NOT_FOUND, "not_found", "Backup not found"),
        RestoreError::ConfirmationMismatch => api_error_fields(
            StatusCode::UNPROCESSABLE_ENTITY,
            "confirmation_mismatch",
            "Type the exact backup name to confirm",
            BTreeMap::from([("confirmation", "Type the exact backup name to confirm")]),
        ),
        RestoreError::Unreadable => api_error(
            StatusCode::CONFLICT,
            "backup_unreadable",
            "This backup cannot be read or is damaged",
        ),
        RestoreError::NewerSchema => api_error(
            StatusCode::CONFLICT,
            "backup_newer_schema",
            "This backup was written by a newer version of the server",
        ),
        RestoreError::MediaUnavailable => api_error_fields(
            StatusCode::UNPROCESSABLE_ENTITY,
            "media_unavailable",
            "This backup has no media to add",
            BTreeMap::from([("media", "This backup has no media to add")]),
        ),
        RestoreError::Busy => api_error(
            StatusCode::CONFLICT,
            "restore_in_progress",
            "A restore is already in progress",
        ),
        RestoreError::PreRestoreFailed => api_error(
            StatusCode::INTERNAL_SERVER_ERROR,
            "pre_restore_failed",
            "The safety backup of the current data failed; nothing was changed",
        ),
        RestoreError::StageFailed => api_error(
            StatusCode::INTERNAL_SERVER_ERROR,
            "stage_failed",
            "The backup could not be prepared; nothing was changed",
        ),
        RestoreError::Unavailable => unavailable(),
    }
}

#[allow(dead_code)]
fn _types(_: Accepted, _: Preview) {}
