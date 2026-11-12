//! Administrator impersonation of a user account, for the browser session only.
//!
//! The session row (`api_tokens`, purpose `browser`) keeps the administrator as
//! its owner and records the account it acts as plus an expiry. Only
//! [`crate::auth::browser_user`] resolves that account; Bearer tokens never do.
//! Requests made while impersonating run inside [`scope`], so audit events
//! written by the services can name the acting administrator.

use std::future::Future;

use axum::{
    body::Body,
    extract::State,
    http::{HeaderMap, Method, Request, StatusCode, header},
    middleware::Next,
    response::{IntoResponse, Response},
};
use serde::Serialize;
use sqlx::SqlitePool;

use crate::{auth, db::AppState, resource_access::display_name};

/// How long one impersonation lasts.
pub(crate) const IMPERSONATION_SECONDS: i64 = 60 * 60;

tokio::task_local! {
    static IMPERSONATOR: i64;
}

/// The administrator acting as the current request's user, if any.
pub(crate) fn current_impersonator() -> Option<i64> {
    IMPERSONATOR.try_with(|admin| *admin).ok()
}

/// Run `future` with `admin_id` recorded as the impersonating administrator.
pub(crate) async fn scope<F: Future>(admin_id: i64, future: F) -> F::Output {
    IMPERSONATOR.scope(admin_id, future).await
}

#[derive(Debug, PartialEq, Eq)]
pub(crate) enum ImpersonationError {
    /// The caller is not an active administrator or the session is not theirs.
    Forbidden,
    NotFound,
    /// Self, another administrator or a disabled account.
    InvalidTarget,
    Database,
}

impl From<sqlx::Error> for ImpersonationError {
    fn from(_: sqlx::Error) -> Self {
        Self::Database
    }
}

#[derive(Clone, Debug, Serialize)]
pub(crate) struct ImpersonatedUser {
    pub(crate) id: i64,
    pub(crate) email: String,
    pub(crate) name: String,
}

#[derive(Clone, Debug, Serialize)]
pub(crate) struct ImpersonatingAdmin {
    pub(crate) id: i64,
    pub(crate) email: String,
}

#[derive(Clone, Debug, Serialize)]
pub(crate) struct ImpersonationInfo {
    pub(crate) user: ImpersonatedUser,
    pub(crate) admin: ImpersonatingAdmin,
    pub(crate) expires_at: i64,
}

/// An impersonation recorded on a browser session.
struct Recorded {
    token_id: i64,
    admin_id: i64,
    target_id: i64,
    expires_at: i64,
    /// The expiry has not passed and the target is still an active non-admin.
    live: bool,
}

async fn recorded(pool: &SqlitePool, raw_token: &str) -> Result<Option<Recorded>, sqlx::Error> {
    let row = sqlx::query_as::<_, (i64, i64, i64, i64, bool)>(
        "SELECT t.id, t.user_id, t.impersonated_user_id, \
                COALESCE(t.impersonation_expires_at, 0), \
                (COALESCE(t.impersonation_expires_at, 0) > unixepoch() \
                 AND target.active = 1 AND target.is_admin = 0) \
         FROM api_tokens t \
         JOIN users target ON target.id = t.impersonated_user_id \
         WHERE t.token_hash = ? AND t.purpose = 'browser' AND t.revoked_at IS NULL \
           AND t.impersonated_user_id IS NOT NULL",
    )
    .bind(auth::token_hash(raw_token.as_bytes()))
    .fetch_optional(pool)
    .await?;
    Ok(row.map(
        |(token_id, admin_id, target_id, expires_at, live)| Recorded {
            token_id,
            admin_id,
            target_id,
            expires_at,
            live,
        },
    ))
}

/// The active impersonation of a browser session, for the session payload.
pub(crate) async fn info(
    pool: &SqlitePool,
    raw_token: &str,
) -> Result<Option<ImpersonationInfo>, sqlx::Error> {
    let Some(recorded) = recorded(pool, raw_token).await?.filter(|r| r.live) else {
        return Ok(None);
    };
    let emails =
        sqlx::query_as::<_, (i64, String)>("SELECT id, email FROM users WHERE id IN (?, ?)")
            .bind(recorded.admin_id)
            .bind(recorded.target_id)
            .fetch_all(pool)
            .await?;
    let email = |id: i64| {
        emails
            .iter()
            .find(|(user, _)| *user == id)
            .map(|(_, email)| email.clone())
    };
    let (Some(admin_email), Some(user_email)) =
        (email(recorded.admin_id), email(recorded.target_id))
    else {
        return Ok(None);
    };
    Ok(Some(ImpersonationInfo {
        user: ImpersonatedUser {
            id: recorded.target_id,
            name: display_name(&user_email),
            email: user_email,
        },
        admin: ImpersonatingAdmin {
            id: recorded.admin_id,
            email: admin_email,
        },
        expires_at: recorded.expires_at,
    }))
}

async fn audit(
    conn: &mut sqlx::SqliteConnection,
    action: &str,
    admin_id: i64,
    target_id: i64,
) -> Result<(), sqlx::Error> {
    let client = crate::client_info::current();
    sqlx::query(
        "INSERT INTO audit_events \
         (actor_user_id, entity_type, entity_id, action, created_at, \
          resource_name_snapshot, subject_user_id, \
          client_kind, client_version, client_label, client_user_agent) \
         VALUES (?, 'user', ?, ?, strftime('%Y-%m-%dT%H:%M:%f000Z', 'now'), \
                 (SELECT email FROM users WHERE id = ?), ?, ?, ?, ?, ?)",
    )
    .bind(admin_id)
    .bind(target_id)
    .bind(action)
    .bind(target_id)
    .bind(target_id)
    .bind(client.kind.as_str())
    .bind(client.version)
    .bind(client.label)
    .bind(client.user_agent)
    .execute(conn)
    .await?;
    Ok(())
}

/// Start acting as `target_id` on the administrator's browser session.
pub(crate) async fn start(
    pool: &SqlitePool,
    raw_token: &str,
    admin_id: i64,
    target_id: i64,
) -> Result<ImpersonationInfo, ImpersonationError> {
    let mut tx = crate::db::begin_write(pool).await?;
    let is_admin: bool = sqlx::query_scalar(
        "SELECT EXISTS(SELECT 1 FROM users WHERE id = ? AND active = 1 AND is_admin = 1)",
    )
    .bind(admin_id)
    .fetch_one(&mut *tx)
    .await?;
    if !is_admin {
        return Err(ImpersonationError::Forbidden);
    }
    let target =
        sqlx::query_as::<_, (bool, bool)>("SELECT active, is_admin FROM users WHERE id = ?")
            .bind(target_id)
            .fetch_optional(&mut *tx)
            .await?
            .ok_or(ImpersonationError::NotFound)?;
    if target_id == admin_id || !target.0 || target.1 {
        return Err(ImpersonationError::InvalidTarget);
    }
    let expires_at = chrono::Utc::now().timestamp() + IMPERSONATION_SECONDS;
    let updated = sqlx::query(
        "UPDATE api_tokens SET impersonated_user_id = ?, impersonation_expires_at = ? \
         WHERE token_hash = ? AND user_id = ? AND purpose = 'browser' \
           AND revoked_at IS NULL AND (expires_at IS NULL OR expires_at > unixepoch())",
    )
    .bind(target_id)
    .bind(expires_at)
    .bind(auth::token_hash(raw_token.as_bytes()))
    .bind(admin_id)
    .execute(&mut *tx)
    .await?
    .rows_affected();
    if updated != 1 {
        return Err(ImpersonationError::Forbidden);
    }
    audit(&mut tx, "impersonation_started", admin_id, target_id).await?;
    tx.commit().await?;
    info(pool, raw_token)
        .await?
        .ok_or(ImpersonationError::Database)
}

/// End the impersonation of a browser session and log it. Returns whether this
/// call ended one, so concurrent stops and expiry checks log it exactly once.
async fn end(pool: &SqlitePool, recorded: &Recorded) -> Result<bool, sqlx::Error> {
    let mut tx = crate::db::begin_write(pool).await?;
    let updated = sqlx::query(
        "UPDATE api_tokens SET impersonated_user_id = NULL, impersonation_expires_at = NULL \
         WHERE id = ? AND impersonated_user_id = ?",
    )
    .bind(recorded.token_id)
    .bind(recorded.target_id)
    .execute(&mut *tx)
    .await?
    .rows_affected();
    if updated == 1 {
        audit(
            &mut tx,
            "impersonation_stopped",
            recorded.admin_id,
            recorded.target_id,
        )
        .await?;
    }
    tx.commit().await?;
    Ok(updated == 1)
}

/// Stop impersonating on a browser session. Returns false when none was active.
pub(crate) async fn stop(pool: &SqlitePool, raw_token: &str) -> Result<bool, sqlx::Error> {
    match recorded(pool, raw_token).await? {
        Some(recorded) => end(pool, &recorded).await,
        None => Ok(false),
    }
}

/// Whether an impersonating session may not make this request. Resource routes
/// (projects, yarns, media, reading the account) stay open; administration and
/// everything that changes how the account signs in or leaves with its data
/// does not.
fn forbidden_while_impersonating(method: &Method, path: &str) -> bool {
    let path = path.strip_prefix("/api/v2").unwrap_or(path);
    let safe = matches!(*method, Method::GET | Method::HEAD);
    if path == "/admin/impersonation/stop" {
        return false;
    }
    if path == "/admin" || path.starts_with("/admin/") {
        return true;
    }
    if safe && matches!(path, "/account" | "/account/identities") {
        return false;
    }
    if path == "/account" || path.starts_with("/account/") {
        return true;
    }
    if path.starts_with("/auth/") && path != "/auth/logout" {
        return true;
    }
    path == "/push/subscription"
}

fn error(status: StatusCode, code: &'static str, message: &'static str) -> Response {
    crate::api_v2::error_response(status, code, message)
}

fn internal_error() -> Response {
    error(
        StatusCode::INTERNAL_SERVER_ERROR,
        "internal_error",
        "Request failed",
    )
}

/// Request guard for browser sessions: ends an impersonation that has expired,
/// refuses account-security and administration requests while one is active,
/// and records the acting administrator for audit events written meanwhile.
/// Bearer requests are not touched: they never impersonate.
pub(crate) async fn guard(
    State(state): State<AppState>,
    request: Request<Body>,
    next: Next,
) -> Response {
    let Some(raw_token) = browser_token(request.headers(), state.secure_cookies) else {
        return next.run(request).await;
    };
    let recorded = match recorded(&state.pool, &raw_token).await {
        Ok(Some(recorded)) => recorded,
        Ok(None) => return next.run(request).await,
        Err(_) => return internal_error(),
    };
    if !recorded.live {
        let Ok(ended) = end(&state.pool, &recorded).await else {
            return internal_error();
        };
        // A write the page meant for the impersonated account must not land on
        // the administrator's own account; reads simply see the real session.
        if ended && !matches!(*request.method(), Method::GET | Method::HEAD) {
            return error(
                StatusCode::CONFLICT,
                "impersonation_expired",
                "Impersonation has ended",
            );
        }
        return next.run(request).await;
    }
    if forbidden_while_impersonating(request.method(), request.uri().path()) {
        return error(
            StatusCode::FORBIDDEN,
            "impersonation_forbidden",
            "This is not available while impersonating",
        );
    }
    scope(recorded.admin_id, next.run(request)).await
}

/// The browser session token of a request that carries no Authorization header.
fn browser_token(headers: &HeaderMap, secure_cookies: bool) -> Option<String> {
    if headers.contains_key(header::AUTHORIZATION) {
        return None;
    }
    auth::cookie(headers, auth::session_cookie_name(secure_cookies)).map(str::to_owned)
}

impl IntoResponse for ImpersonationError {
    fn into_response(self) -> Response {
        match self {
            Self::Forbidden => error(
                StatusCode::FORBIDDEN,
                "admin_required",
                "Administrator access is required",
            ),
            Self::NotFound => error(StatusCode::NOT_FOUND, "not_found", "User not found"),
            Self::InvalidTarget => error(
                StatusCode::CONFLICT,
                "impersonation_invalid_target",
                "This account cannot be impersonated",
            ),
            Self::Database => internal_error(),
        }
    }
}
