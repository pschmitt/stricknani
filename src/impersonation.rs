//! Administrator impersonation of a user account.
//!
//! Two carriers exist. The browser: the session row (`api_tokens`, purpose
//! `browser`) keeps the administrator as its owner and records the account it
//! acts as plus an expiry. Only [`crate::auth::browser_user`] resolves that
//! account; API-purpose PATs never do.
//! Requests made while impersonating run inside [`scope`], so audit events
//! written by the services can name the acting administrator.
//!
//! The native app: a short-lived `sni_` bearer credential in
//! `impersonation_tokens`, bound to (target account, administrator) and
//! resolved by [`crate::auth`] only on the bearer path. The same request
//! restrictions and audit scope apply as for the browser.

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
    sqlx::query(
        "INSERT INTO audit_events \
         (actor_user_id, entity_type, entity_id, action, created_at, \
          resource_name_snapshot, subject_user_id) \
         VALUES (?, 'user', ?, ?, strftime('%Y-%m-%dT%H:%M:%f000Z', 'now'), \
                 (SELECT email FROM users WHERE id = ?), ?)",
    )
    .bind(admin_id)
    .bind(target_id)
    .bind(action)
    .bind(target_id)
    .bind(target_id)
    .execute(conn)
    .await?;
    Ok(())
}

/// The administrator is an active administrator and the target an active,
/// different, non-administrator account.
async fn validate_pair(
    conn: &mut sqlx::SqliteConnection,
    admin_id: i64,
    target_id: i64,
) -> Result<(), ImpersonationError> {
    let is_admin: bool = sqlx::query_scalar(
        "SELECT EXISTS(SELECT 1 FROM users WHERE id = ? AND active = 1 AND is_admin = 1)",
    )
    .bind(admin_id)
    .fetch_one(&mut *conn)
    .await?;
    if !is_admin {
        return Err(ImpersonationError::Forbidden);
    }
    let target =
        sqlx::query_as::<_, (bool, bool)>("SELECT active, is_admin FROM users WHERE id = ?")
            .bind(target_id)
            .fetch_optional(&mut *conn)
            .await?
            .ok_or(ImpersonationError::NotFound)?;
    if target_id == admin_id || !target.0 || target.1 {
        return Err(ImpersonationError::InvalidTarget);
    }
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
    validate_pair(&mut tx, admin_id, target_id).await?;
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

/// Prefix of a native impersonation credential. It differs from the `sna_` PAT
/// prefix so the request guard only looks a bearer up when it can be one.
pub(crate) const NATIVE_TOKEN_PREFIX: &str = "sni_";

/// A freshly started native impersonation. The raw token is shown once.
pub(crate) struct NativeStarted {
    pub(crate) token: String,
    pub(crate) info: ImpersonationInfo,
}

/// Whether a raw bearer value can be a native impersonation credential.
pub(crate) fn is_native_token(raw_token: &str) -> bool {
    raw_token.starts_with(NATIVE_TOKEN_PREFIX)
}

/// A native credential row: `live` means not revoked, not expired, and the
/// administrator is still an active administrator and the target still an
/// active non-administrator.
struct NativeRecorded {
    id: i64,
    admin_id: i64,
    target_id: i64,
    revoked: bool,
    live: bool,
}

async fn native_recorded(
    pool: &SqlitePool,
    raw_token: &str,
) -> Result<Option<NativeRecorded>, sqlx::Error> {
    let row = sqlx::query_as::<_, (i64, i64, i64, bool, bool)>(
        "SELECT t.id, t.admin_user_id, t.target_user_id, t.revoked_at IS NOT NULL, \
                (t.revoked_at IS NULL AND t.expires_at > unixepoch() \
                 AND target.active = 1 AND target.is_admin = 0 \
                 AND admin.active = 1 AND admin.is_admin = 1) \
         FROM impersonation_tokens t \
         JOIN users target ON target.id = t.target_user_id \
         JOIN users admin ON admin.id = t.admin_user_id \
         WHERE t.token_hash = ?",
    )
    .bind(auth::token_hash(raw_token.as_bytes()))
    .fetch_optional(pool)
    .await?;
    Ok(
        row.map(|(id, admin_id, target_id, revoked, live)| NativeRecorded {
            id,
            admin_id,
            target_id,
            revoked,
            live,
        }),
    )
}

/// The target account a live native credential acts as. Used by
/// [`crate::auth`] for bearer requests; the target's own error-reporting
/// preference applies, as for a browser impersonation.
pub(crate) async fn native_target(
    pool: &SqlitePool,
    raw_token: &str,
) -> Result<Option<(i64, i64)>, sqlx::Error> {
    sqlx::query_as::<_, (i64, i64)>(
        "SELECT target.id, target.error_reporting \
         FROM impersonation_tokens t \
         JOIN users target ON target.id = t.target_user_id \
         JOIN users admin ON admin.id = t.admin_user_id \
         WHERE t.token_hash = ? AND t.revoked_at IS NULL AND t.expires_at > unixepoch() \
           AND target.active = 1 AND target.is_admin = 0 \
           AND admin.active = 1 AND admin.is_admin = 1",
    )
    .bind(auth::token_hash(raw_token.as_bytes()))
    .fetch_optional(pool)
    .await
}

async fn native_info(
    pool: &SqlitePool,
    admin_id: i64,
    target_id: i64,
    expires_at: i64,
) -> Result<ImpersonationInfo, ImpersonationError> {
    let emails =
        sqlx::query_as::<_, (i64, String)>("SELECT id, email FROM users WHERE id IN (?, ?)")
            .bind(admin_id)
            .bind(target_id)
            .fetch_all(pool)
            .await?;
    let email = |id: i64| {
        emails
            .iter()
            .find(|(user, _)| *user == id)
            .map(|(_, email)| email.clone())
    };
    let (Some(admin_email), Some(user_email)) = (email(admin_id), email(target_id)) else {
        return Err(ImpersonationError::Database);
    };
    Ok(ImpersonationInfo {
        user: ImpersonatedUser {
            id: target_id,
            name: display_name(&user_email),
            email: user_email,
        },
        admin: ImpersonatingAdmin {
            id: admin_id,
            email: admin_email,
        },
        expires_at,
    })
}

/// Revoke one native credential and log it, exactly once however many stops
/// and expiry checks race. Returns whether this call ended it.
async fn native_end(
    pool: &SqlitePool,
    id: i64,
    admin_id: i64,
    target_id: i64,
) -> Result<bool, sqlx::Error> {
    let mut tx = crate::db::begin_write(pool).await?;
    let updated = sqlx::query(
        "UPDATE impersonation_tokens SET revoked_at = unixepoch() \
         WHERE id = ? AND revoked_at IS NULL",
    )
    .bind(id)
    .execute(&mut *tx)
    .await?
    .rows_affected();
    if updated == 1 {
        audit(&mut tx, "impersonation_stopped", admin_id, target_id).await?;
    }
    tx.commit().await?;
    Ok(updated == 1)
}

/// Issue a native impersonation credential for `target_id`. The caller has
/// authenticated `admin_id` with an API-purpose PAT. Only one native
/// impersonation runs per administrator: starting another ends the previous.
pub(crate) async fn start_native(
    pool: &SqlitePool,
    admin_id: i64,
    target_id: i64,
) -> Result<NativeStarted, ImpersonationError> {
    let token = format!(
        "{NATIVE_TOKEN_PREFIX}{}",
        auth::generate_token()
            .map_err(|()| ImpersonationError::Database)?
            .trim_start_matches("sna_")
    );
    let mut tx = crate::db::begin_write(pool).await?;
    validate_pair(&mut tx, admin_id, target_id).await?;
    let previous = sqlx::query_as::<_, (i64, i64)>(
        "SELECT id, target_user_id FROM impersonation_tokens \
         WHERE admin_user_id = ? AND revoked_at IS NULL",
    )
    .bind(admin_id)
    .fetch_all(&mut *tx)
    .await?;
    for (id, previous_target) in previous {
        sqlx::query("UPDATE impersonation_tokens SET revoked_at = unixepoch() WHERE id = ?")
            .bind(id)
            .execute(&mut *tx)
            .await?;
        audit(&mut tx, "impersonation_stopped", admin_id, previous_target).await?;
    }
    let expires_at = chrono::Utc::now().timestamp() + IMPERSONATION_SECONDS;
    sqlx::query(
        "INSERT INTO impersonation_tokens \
         (token_hash, admin_user_id, target_user_id, created_at, expires_at) \
         VALUES (?, ?, ?, unixepoch(), ?)",
    )
    .bind(auth::token_hash(token.as_bytes()))
    .bind(admin_id)
    .bind(target_id)
    .bind(expires_at)
    .execute(&mut *tx)
    .await?;
    audit(&mut tx, "impersonation_started", admin_id, target_id).await?;
    tx.commit().await?;
    let info = native_info(pool, admin_id, target_id, expires_at).await?;
    Ok(NativeStarted { token, info })
}

/// End the native impersonation carried by `raw_token`. Idempotent: returns
/// whether this call ended one. An unknown token is `NotFound`.
pub(crate) async fn stop_native(
    pool: &SqlitePool,
    raw_token: &str,
) -> Result<bool, ImpersonationError> {
    let recorded = native_recorded(pool, raw_token)
        .await?
        .ok_or(ImpersonationError::NotFound)?;
    Ok(native_end(pool, recorded.id, recorded.admin_id, recorded.target_id).await?)
}

/// End every native impersonation of `admin_id`: the administrator's own way
/// out when the app lost its credential.
pub(crate) async fn stop_all_native(pool: &SqlitePool, admin_id: i64) -> Result<bool, sqlx::Error> {
    let rows = sqlx::query_as::<_, (i64, i64)>(
        "SELECT id, target_user_id FROM impersonation_tokens \
         WHERE admin_user_id = ? AND revoked_at IS NULL",
    )
    .bind(admin_id)
    .fetch_all(pool)
    .await?;
    let mut ended = false;
    for (id, target_id) in rows {
        ended |= native_end(pool, id, admin_id, target_id).await?;
    }
    Ok(ended)
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

/// Request guard for browser sessions and native impersonation credentials: ends an impersonation that has expired,
/// refuses account-security and administration requests while one is active,
/// and records the acting administrator for audit events written meanwhile.
/// Other Bearer requests (API-purpose PATs) are not touched: they never
/// impersonate.
pub(crate) async fn guard(
    State(state): State<AppState>,
    request: Request<Body>,
    next: Next,
) -> Response {
    if let Some(raw_token) = native_token(request.headers()) {
        return native_guard(&state, &raw_token, request, next).await;
    }
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

/// The native impersonation credential of a request, if it carries exactly one
/// Authorization header holding a `sni_` bearer token.
fn native_token(headers: &HeaderMap) -> Option<String> {
    let mut values = headers.get_all(header::AUTHORIZATION).iter();
    let value = values.next()?;
    if values.next().is_some() {
        return None;
    }
    let token = crate::auth_stepup_service::parse_bearer(value.to_str().ok()?)?;
    is_native_token(token).then(|| token.to_owned())
}

/// The guard for a native credential: same restrictions and audit scope as a
/// browser impersonation. A credential that is no longer good (expired,
/// stopped, administrator demoted, target disabled) ends here and is logged once.
async fn native_guard(
    state: &AppState,
    raw_token: &str,
    request: Request<Body>,
    next: Next,
) -> Response {
    let recorded = match native_recorded(&state.pool, raw_token).await {
        Ok(Some(recorded)) => recorded,
        // Unknown: the normal bearer authentication rejects it.
        Ok(None) => return next.run(request).await,
        Err(_) => return internal_error(),
    };
    let is_stop = request.uri().path().ends_with("/admin/impersonation/stop");
    if !recorded.live {
        if is_stop {
            return next.run(request).await;
        }
        if !recorded.revoked
            && native_end(
                &state.pool,
                recorded.id,
                recorded.admin_id,
                recorded.target_id,
            )
            .await
            .is_err()
        {
            return internal_error();
        }
        return error(
            StatusCode::UNAUTHORIZED,
            "impersonation_expired",
            "Impersonation has ended",
        );
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
