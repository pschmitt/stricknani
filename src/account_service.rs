//! Shared typed account operations used by the browser and API adapters.

use sqlx::SqlitePool;
use zeroize::Zeroizing;

use crate::{
    auth, media,
    oidc_identity_service::{self, RecentAction, RecentAuth},
};

pub(crate) const MAX_PASSWORD_BYTES: usize = 1024;
pub(crate) const MAX_TOKEN_NAME_BYTES: usize = 255;
pub(crate) const MAX_TOKENS_PER_HOUR: i64 = 5;

#[derive(Debug)]
pub(crate) enum AccountError {
    Database,
    InvalidTokenName,
    TokenRateLimited(u64),
    TokenNotFound,
    PasswordTooLong,
    PasswordPolicy(auth::NewPasswordError),
    CurrentPasswordIncorrect,
    PasswordConflict,
    InvalidAvatar(media::UploadError),
    InvalidExpiry,
    /// The bearer token that made the request is no longer valid.
    CallerTokenInvalid,
    /// A sensitive action lacks a valid, unconsumed step-up proof.
    ReauthRequired,
    /// The confirmation text is neither the account email nor `DELETE`.
    ConfirmationMismatch,
    /// The last active administrator cannot delete their own account.
    LastAdministrator,
}

/// How a bearer caller proved recent authentication for token creation.
pub(crate) enum BearerStepUp {
    /// The handler already verified the current password.
    PasswordVerified,
    /// SHA-256 of an `X-Reauth-Token` proof, consumed with the insert.
    Proof(String),
}

pub(crate) struct BearerCaller {
    pub(crate) token_id: i64,
    pub(crate) stepup: BearerStepUp,
}

#[derive(Default)]
pub(crate) struct CreateTokenOptions {
    /// Requested expiry as a Unix timestamp.
    pub(crate) requested_expires_at: Option<i64>,
    /// Set when a bearer PAT (not a browser session) makes the request.
    pub(crate) bearer: Option<BearerCaller>,
}

pub(crate) struct CreatedToken {
    pub(crate) id: i64,
    pub(crate) name: String,
    pub(crate) created_at: String,
    pub(crate) expires_at: Option<String>,
    pub(crate) token: Zeroizing<String>,
}

#[derive(Debug, serde::Serialize)]
pub(crate) struct TokenMetadata {
    pub(crate) id: i64,
    pub(crate) name: String,
    pub(crate) created_at: String,
    pub(crate) expires_at: Option<String>,
    pub(crate) last_used_at: Option<String>,
}

pub(crate) async fn list_tokens(
    pool: &SqlitePool,
    user_id: i64,
) -> Result<Vec<TokenMetadata>, AccountError> {
    let rows = sqlx::query(
        "SELECT id, name, created_at, expires_at, last_used_at FROM api_tokens \
         WHERE user_id = ? AND purpose = 'api' AND revoked_at IS NULL \
           AND (expires_at IS NULL OR expires_at > unixepoch()) ORDER BY id DESC",
    )
    .bind(user_id)
    .fetch_all(pool)
    .await
    .map_err(|_| AccountError::Database)?;
    use sqlx::Row;
    Ok(rows
        .into_iter()
        .map(|row| TokenMetadata {
            id: row.get("id"),
            name: row.get("name"),
            created_at: format_timestamp(row.get("created_at")),
            expires_at: row
                .get::<Option<i64>, _>("expires_at")
                .map(format_timestamp),
            last_used_at: row
                .get::<Option<i64>, _>("last_used_at")
                .map(format_timestamp),
        })
        .collect())
}

/// Create an API token. A token created by a bearer caller is stepped up
/// inside the insert transaction and never outlives the caller's own token.
pub(crate) async fn create_token_with(
    pool: &SqlitePool,
    user_id: i64,
    requested_name: Option<String>,
    options: CreateTokenOptions,
) -> Result<CreatedToken, AccountError> {
    let now = chrono::Utc::now().timestamp();
    if options.requested_expires_at.is_some_and(|at| at <= now) {
        return Err(AccountError::InvalidExpiry);
    }
    let name = requested_name.unwrap_or_default().trim().to_owned();
    let name = if name.is_empty() {
        "API token".to_owned()
    } else {
        name
    };
    if name.len() > MAX_TOKEN_NAME_BYTES {
        return Err(AccountError::InvalidTokenName);
    }

    // The no-op active-user write obtains SQLite's write lock before quota
    // inspection, so concurrent requests cannot race past the hourly limit.
    let mut tx = crate::db::begin_write(pool)
        .await
        .map_err(|_| AccountError::Database)?;
    let active = sqlx::query("UPDATE users SET id = id WHERE id = ? AND active = 1")
        .bind(user_id)
        .execute(&mut *tx)
        .await
        .map_err(|_| AccountError::Database)?;
    if active.rows_affected() != 1 {
        return Err(AccountError::TokenNotFound);
    }
    let mut expires_at = options.requested_expires_at;
    if let Some(caller) = &options.bearer {
        let caller_expiry: Option<Option<i64>> = sqlx::query_scalar(
            "SELECT expires_at FROM api_tokens WHERE id = ? AND user_id = ? \
             AND purpose = 'api' AND revoked_at IS NULL \
             AND (expires_at IS NULL OR expires_at > ?)",
        )
        .bind(caller.token_id)
        .bind(user_id)
        .bind(now)
        .fetch_optional(&mut *tx)
        .await
        .map_err(|_| AccountError::Database)?;
        let Some(caller_expiry) = caller_expiry else {
            return Err(AccountError::CallerTokenInvalid);
        };
        expires_at = match (expires_at, caller_expiry) {
            (Some(requested), Some(caller)) => Some(requested.min(caller)),
            (requested, caller) => requested.or(caller),
        };
        if let BearerStepUp::Proof(proof_hash) = &caller.stepup {
            oidc_identity_service::consume_recent_auth_tx(
                &mut tx,
                RecentAuth::NativeProof {
                    token_id: caller.token_id,
                    proof_hash,
                },
                user_id,
                user_id,
                RecentAction::CreateApiToken,
                now,
            )
            .await
            .map_err(|_| AccountError::ReauthRequired)?;
        }
    }
    let oldest_recent: Option<i64> = sqlx::query_scalar(
        "SELECT min(created_at) FROM api_tokens WHERE user_id = ? AND purpose = 'api' \
         AND created_at > unixepoch() - 3600",
    )
    .bind(user_id)
    .fetch_one(&mut *tx)
    .await
    .map_err(|_| AccountError::Database)?;
    let recent_count: i64 = sqlx::query_scalar(
        "SELECT count(*) FROM api_tokens WHERE user_id = ? AND purpose = 'api' \
         AND created_at > unixepoch() - 3600",
    )
    .bind(user_id)
    .fetch_one(&mut *tx)
    .await
    .map_err(|_| AccountError::Database)?;
    if recent_count >= MAX_TOKENS_PER_HOUR {
        let oldest_recent = oldest_recent.unwrap_or_else(|| chrono::Utc::now().timestamp());
        let retry_after =
            (3600_i64 - (chrono::Utc::now().timestamp() - oldest_recent)).max(1) as u64;
        return Err(AccountError::TokenRateLimited(retry_after));
    }
    let token = auth::generate_token().map_err(|_| AccountError::Database)?;
    let token_hash = auth::token_hash(token.as_bytes());
    let result = sqlx::query(
        "INSERT INTO api_tokens (user_id, name, token_hash, created_at, expires_at) \
         VALUES (?, ?, ?, unixepoch(), ?)",
    )
    .bind(user_id)
    .bind(&name)
    .bind(token_hash)
    .bind(expires_at)
    .execute(&mut *tx)
    .await
    .map_err(|_| AccountError::Database)?;
    let created_at: i64 = sqlx::query_scalar("SELECT created_at FROM api_tokens WHERE id = ?")
        .bind(result.last_insert_rowid())
        .fetch_one(&mut *tx)
        .await
        .map_err(|_| AccountError::Database)?;
    oidc_identity_service::record_api_token_created_tx(&mut tx, user_id, now)
        .await
        .map_err(|_| AccountError::Database)?;
    tx.commit().await.map_err(|_| AccountError::Database)?;
    Ok(CreatedToken {
        id: result.last_insert_rowid(),
        name,
        created_at: format_timestamp(created_at),
        expires_at: expires_at.map(format_timestamp),
        token: Zeroizing::new(token),
    })
}

/// Longest token name a client may set through rename or `device_name`.
pub(crate) const MAX_DEVICE_NAME_CHARS: usize = 100;

/// Trim a client-supplied device name; `None` when blank or over the limit.
pub(crate) fn clean_device_name(name: &str) -> Option<&str> {
    let name = name.trim();
    (!name.is_empty() && name.chars().count() <= MAX_DEVICE_NAME_CHARS).then_some(name)
}

/// `Android · <device_name>`, the name a minted PAT gets from `device_name`.
pub(crate) fn device_token_name(device_name: &str) -> String {
    format!("Android \u{b7} {device_name}")
}

/// Rename the caller's own active API token.
pub(crate) async fn rename_token(
    pool: &SqlitePool,
    user_id: i64,
    token_id: i64,
    name: &str,
) -> Result<TokenMetadata, AccountError> {
    use sqlx::Row;
    let row = sqlx::query(
        "UPDATE api_tokens SET name = ? WHERE id = ? AND user_id = ? AND purpose = 'api' \
         AND revoked_at IS NULL AND (expires_at IS NULL OR expires_at > unixepoch()) \
         RETURNING id, name, created_at, expires_at, last_used_at",
    )
    .bind(name)
    .bind(token_id)
    .bind(user_id)
    .fetch_optional(pool)
    .await
    .map_err(|_| AccountError::Database)?
    .ok_or(AccountError::CallerTokenInvalid)?;
    Ok(TokenMetadata {
        id: row.get("id"),
        name: row.get("name"),
        created_at: format_timestamp(row.get("created_at")),
        expires_at: row
            .get::<Option<i64>, _>("expires_at")
            .map(format_timestamp),
        last_used_at: row
            .get::<Option<i64>, _>("last_used_at")
            .map(format_timestamp),
    })
}

pub(crate) async fn revoke_token(
    pool: &SqlitePool,
    user_id: i64,
    token_id: i64,
) -> Result<bool, AccountError> {
    let result = sqlx::query(
        "UPDATE api_tokens SET revoked_at = coalesce(revoked_at, unixepoch()) \
         WHERE id = ? AND user_id = ? AND purpose = 'api'",
    )
    .bind(token_id)
    .bind(user_id)
    .execute(pool)
    .await
    .map_err(|_| AccountError::Database)?;
    Ok(result.rows_affected() > 0)
}

pub(crate) async fn change_password(
    pool: &SqlitePool,
    user_id: i64,
    current_password: Zeroizing<String>,
    new_password: Zeroizing<String>,
) -> Result<(), AccountError> {
    if new_password.len() > MAX_PASSWORD_BYTES {
        return Err(AccountError::PasswordTooLong);
    }
    auth::validate_new_password(&new_password).map_err(AccountError::PasswordPolicy)?;
    let old_hash: Option<String> = sqlx::query_scalar(
        "SELECT password_hash FROM users WHERE id = ? AND active = 1 AND password_enabled = 1",
    )
    .bind(user_id)
    .fetch_optional(pool)
    .await
    .map_err(|_| AccountError::Database)?;
    let Some(old_hash) = old_hash else {
        return Err(AccountError::PasswordConflict);
    };
    if !auth::verify_password_for_account(current_password, old_hash.clone())
        .await
        .map_err(|_| AccountError::Database)?
    {
        return Err(AccountError::CurrentPasswordIncorrect);
    }
    let password_hash = auth::hash_password_for_account(new_password)
        .await
        .map_err(|_| AccountError::Database)?;
    if !update_password_and_revoke_sessions(pool, user_id, &password_hash, &old_hash)
        .await
        .map_err(|_| AccountError::Database)?
    {
        return Err(AccountError::PasswordConflict);
    }
    Ok(())
}

pub(crate) async fn update_password_and_revoke_sessions(
    pool: &SqlitePool,
    user_id: i64,
    password_hash: &str,
    expected_password_hash: &str,
) -> Result<bool, sqlx::Error> {
    let mut tx = crate::db::begin_write(pool).await?;
    let result = sqlx::query(
        "UPDATE users SET password_hash = ? WHERE id = ? AND active = 1 AND password_enabled = 1 AND password_hash = ?",
    )
    .bind(password_hash)
    .bind(user_id)
    .bind(expected_password_hash)
    .execute(&mut *tx)
    .await?;
    if result.rows_affected() == 0 {
        return Ok(false);
    }
    sqlx::query(
        "UPDATE api_tokens SET revoked_at = unixepoch() \
         WHERE user_id = ? AND revoked_at IS NULL",
    )
    .bind(user_id)
    .execute(&mut *tx)
    .await?;
    tx.commit().await?;
    Ok(true)
}

pub(crate) async fn save_avatar(
    pool: &SqlitePool,
    root: &std::path::Path,
    user_id: i64,
    upload: media::AvatarUpload,
) -> Result<String, AccountError> {
    let image = media::prepare_avatar(upload.bytes)
        .await
        .map_err(AccountError::InvalidAvatar)?;
    let filename = media::save_avatar(root, user_id, &image)
        .await
        .map_err(|_| AccountError::Database)?;
    let created_at = chrono::Utc::now().to_rfc3339_opts(chrono::SecondsFormat::Micros, true);
    let result = async {
        let mut tx = crate::db::begin_write(pool).await.map_err(|_| AccountError::Database)?;
        sqlx::query(
            "INSERT INTO user_avatars \
             (user_id, filename, original_filename, content_type, width, height, created_at) \
             VALUES (?, ?, ?, ?, ?, ?, ?) \
             ON CONFLICT(user_id) DO UPDATE SET filename = excluded.filename, \
               original_filename = excluded.original_filename, content_type = excluded.content_type, \
               width = excluded.width, height = excluded.height, created_at = excluded.created_at",
        )
        .bind(user_id)
        .bind(&filename)
        .bind(upload.original_filename)
        .bind(image.content_type)
        .bind(i64::from(image.width))
        .bind(i64::from(image.height))
        .bind(&created_at)
        .execute(&mut *tx)
        .await
        .map_err(|_| AccountError::Database)?;
        let profile = sqlx::query("UPDATE users SET profile_image = ? WHERE id = ? AND active = 1")
            .bind(&filename)
            .bind(user_id)
            .execute(&mut *tx)
            .await
            .map_err(|_| AccountError::Database)?;
        if profile.rows_affected() != 1 {
            return Err(AccountError::TokenNotFound);
        }
        tx.commit().await.map_err(|_| AccountError::Database)?;
        Ok::<(), AccountError>(())
    }
    .await;
    if result.is_err() {
        media::cleanup_avatar(root, user_id, &filename).await;
    }
    result.map(|()| {
        format!(
            "/media/thumbnails/users/{user_id}/{}",
            media::avatar_thumbnail_name(&filename)
        )
    })
}

pub(crate) async fn delete_avatar(pool: &SqlitePool, user_id: i64) -> Result<(), AccountError> {
    let mut tx = crate::db::begin_write(pool)
        .await
        .map_err(|_| AccountError::Database)?;
    sqlx::query("DELETE FROM user_avatars WHERE user_id = ?")
        .bind(user_id)
        .execute(&mut *tx)
        .await
        .map_err(|_| AccountError::Database)?;
    let profile = sqlx::query("UPDATE users SET profile_image = NULL WHERE id = ? AND active = 1")
        .bind(user_id)
        .execute(&mut *tx)
        .await
        .map_err(|_| AccountError::Database)?;
    if profile.rows_affected() != 1 {
        return Err(AccountError::TokenNotFound);
    }
    tx.commit().await.map_err(|_| AccountError::Database)?;
    Ok(())
}

pub(crate) async fn account_dto(
    pool: &SqlitePool,
    user_id: i64,
    gravatar_enabled: bool,
    error_reporting_available: bool,
) -> Result<AccountDto, AccountError> {
    use sqlx::Row;
    let row = sqlx::query(
        "SELECT users.id, users.email, users.is_admin, users.use_gravatar, \
         users.error_reporting, user_avatars.filename \
         FROM users LEFT JOIN user_avatars ON user_avatars.user_id = users.id \
         WHERE users.id = ? AND users.active = 1",
    )
    .bind(user_id)
    .fetch_optional(pool)
    .await
    .map_err(|_| AccountError::Database)?
    .ok_or(AccountError::TokenNotFound)?;
    let filename: Option<String> = row.get("filename");
    let use_gravatar = row.get::<i64, _>("use_gravatar") != 0;
    let avatar = media::self_avatar(user_id, filename.as_deref(), use_gravatar, gravatar_enabled);
    Ok(AccountDto {
        id: row.get("id"),
        email: row.get("email"),
        is_admin: row.get::<i64, _>("is_admin") != 0,
        avatar_source: avatar.as_ref().map(|(_, source)| source.as_str()),
        avatar_url: avatar.map(|(url, _)| url),
        use_gravatar,
        gravatar_enabled,
        error_reporting_enabled: row.get::<i64, _>("error_reporting") != 0,
        error_reporting_available,
    })
}

/// Store the per-user Gravatar preference.
pub(crate) async fn set_use_gravatar(
    pool: &SqlitePool,
    user_id: i64,
    enabled: bool,
) -> Result<(), AccountError> {
    let result = sqlx::query("UPDATE users SET use_gravatar = ? WHERE id = ? AND active = 1")
        .bind(i64::from(enabled))
        .bind(user_id)
        .execute(pool)
        .await
        .map_err(|_| AccountError::Database)?;
    if result.rows_affected() == 1 {
        Ok(())
    } else {
        Err(AccountError::TokenNotFound)
    }
}

/// Store the per-user error-reporting preference.
pub(crate) async fn set_error_reporting(
    pool: &SqlitePool,
    user_id: i64,
    enabled: bool,
) -> Result<(), AccountError> {
    let result = sqlx::query("UPDATE users SET error_reporting = ? WHERE id = ? AND active = 1")
        .bind(i64::from(enabled))
        .bind(user_id)
        .execute(pool)
        .await
        .map_err(|_| AccountError::Database)?;
    if result.rows_affected() == 1 {
        Ok(())
    } else {
        Err(AccountError::TokenNotFound)
    }
}

/// The user's error-reporting preference; the default for unknown users.
pub(crate) async fn error_reporting_enabled(pool: &SqlitePool, user_id: i64) -> bool {
    sqlx::query_scalar::<_, i64>("SELECT error_reporting FROM users WHERE id = ? AND active = 1")
        .bind(user_id)
        .fetch_optional(pool)
        .await
        .ok()
        .flatten()
        .map_or(crate::error_reporting::DEFAULT_USER_ENABLED, |value| {
            value != 0
        })
}

#[derive(serde::Serialize)]
pub(crate) struct AccountDto {
    pub(crate) id: i64,
    pub(crate) email: String,
    pub(crate) is_admin: bool,
    pub(crate) avatar_url: Option<String>,
    /// `"upload"`, `"gravatar"` or null when no avatar applies.
    pub(crate) avatar_source: Option<&'static str>,
    /// The user's preference for the Gravatar fallback (default true).
    pub(crate) use_gravatar: bool,
    /// Whether the server offers the Gravatar fallback at all.
    pub(crate) gravatar_enabled: bool,
    /// The user's error-reporting preference (default true). Only has an effect
    /// when `error_reporting_available`.
    pub(crate) error_reporting_enabled: bool,
    /// Whether the operator configured error reporting for this instance.
    pub(crate) error_reporting_available: bool,
}

fn format_timestamp(timestamp: i64) -> String {
    chrono::DateTime::from_timestamp(timestamp, 0)
        .unwrap_or(chrono::DateTime::UNIX_EPOCH)
        .to_rfc3339_opts(chrono::SecondsFormat::Micros, true)
}

/// How the caller proved recent authentication for account deletion.
pub(crate) enum DeleteProof {
    /// The handler already verified the current password.
    PasswordVerified,
    /// The browser session carries a fresh server-side `reauth_at` stamp.
    BrowserSession { token_id: i64 },
    /// SHA-256 of an `X-Reauth-Token` proof, consumed with the deletion.
    NativeProof { token_id: i64, proof_hash: String },
    /// The server operator (`stricknani-server delete-user`); a disabled
    /// account can be deleted too.
    Operator,
}

/// Whether `confirmation` is the literal `DELETE` or the account email.
fn confirmation_matches(confirmation: &str, email: &str) -> bool {
    let confirmation = confirmation.trim();
    !confirmation.is_empty()
        && (confirmation == "DELETE" || confirmation.eq_ignore_ascii_case(email))
}

/// Permanently delete an account and everything it owns.
///
/// One transaction removes the account, its sessions and API tokens, OIDC
/// identities, favourites, avatar, projects, yarns, steps and media rows (the
/// schema cascades; media rows queue their files in `media_gc`). Resource audit
/// history authored by the account is deleted too, and a PII-free
/// `account_deleted` auth-audit row is recorded. Files are removed after the
/// commit; anything that fails stays queued for the background sweeper.
pub(crate) async fn delete_account(
    state: &crate::db::AppState,
    user_id: i64,
    proof: DeleteProof,
    confirmation: &str,
) -> Result<(), AccountError> {
    let now = chrono::Utc::now().timestamp();
    let mut tx = crate::db::begin_write(&state.pool)
        .await
        .map_err(|_| AccountError::Database)?;
    // The no-op write takes SQLite's write lock before the guards run, so two
    // concurrent deletions of the last two administrators cannot both pass.
    let operator = matches!(proof, DeleteProof::Operator);
    let active = sqlx::query("UPDATE users SET id = id WHERE id = ? AND (active = 1 OR ?)")
        .bind(user_id)
        .bind(operator)
        .execute(&mut *tx)
        .await
        .map_err(|_| AccountError::Database)?;
    if active.rows_affected() != 1 {
        return Err(AccountError::TokenNotFound);
    }
    let (email, is_admin): (String, bool) =
        sqlx::query_as("SELECT email, is_admin = 1 FROM users WHERE id = ?")
            .bind(user_id)
            .fetch_one(&mut *tx)
            .await
            .map_err(|_| AccountError::Database)?;
    if !confirmation_matches(confirmation, &email) {
        return Err(AccountError::ConfirmationMismatch);
    }
    match &proof {
        DeleteProof::PasswordVerified | DeleteProof::Operator => {}
        DeleteProof::BrowserSession { token_id } => {
            oidc_identity_service::consume_recent_auth_tx(
                &mut tx,
                RecentAuth::BrowserSession {
                    token_id: *token_id,
                },
                user_id,
                user_id,
                RecentAction::DeleteAccount,
                now,
            )
            .await
            .map_err(|_| AccountError::ReauthRequired)?;
        }
        DeleteProof::NativeProof {
            token_id,
            proof_hash,
        } => {
            oidc_identity_service::consume_recent_auth_tx(
                &mut tx,
                RecentAuth::NativeProof {
                    token_id: *token_id,
                    proof_hash,
                },
                user_id,
                user_id,
                RecentAction::DeleteAccount,
                now,
            )
            .await
            .map_err(|_| AccountError::ReauthRequired)?;
        }
    }
    if is_admin {
        let other_admins: i64 = sqlx::query_scalar(
            "SELECT count(*) FROM users WHERE is_admin = 1 AND active = 1 AND id <> ?",
        )
        .bind(user_id)
        .fetch_one(&mut *tx)
        .await
        .map_err(|_| AccountError::Database)?;
        if other_admins == 0 {
            return Err(AccountError::LastAdministrator);
        }
    }
    // Recipients of what the account owns lose it with the account.
    let share_watch = crate::sharing::watch_account_deletion(&mut tx, user_id)
        .await
        .map_err(|_| AccountError::Database)?;
    for statement in [
        // Resource history authored by the account (titles, change sets).
        "DELETE FROM audit_events WHERE actor_user_id = ?1",
        // Administrator events about the account itself keep only the id.
        "UPDATE audit_events SET resource_name_snapshot = NULL, details = NULL, \
             safe_changes = NULL WHERE entity_type = 'user' AND entity_id = ?1",
        // Tokens and identities first: no ON DELETE SET NULL update can then
        // trip the token-origin triggers while the rows cascade away.
        "DELETE FROM api_tokens WHERE user_id = ?1",
        "DELETE FROM oidc_identities WHERE user_id = ?1",
        "DELETE FROM users WHERE id = ?1",
    ] {
        sqlx::query(statement)
            .bind(user_id)
            .execute(&mut *tx)
            .await
            .map_err(|_| AccountError::Database)?;
    }
    crate::sharing::settle(&mut tx, share_watch, false)
        .await
        .map_err(|_| AccountError::Database)?;
    oidc_identity_service::record_account_deleted_tx(&mut tx, now)
        .await
        .map_err(|_| AccountError::Database)?;
    tx.commit().await.map_err(|_| AccountError::Database)?;
    media::drain_pending(state).await;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::update_password_and_revoke_sessions;

    #[tokio::test]
    async fn stale_password_cas_preserves_new_hash_and_browser_sessions() {
        let pool = crate::db::connect("sqlite::memory:")
            .await
            .expect("test database should migrate");
        let user_id = crate::db::create_user(
            &pool,
            "stale-account-service@example.test",
            "account-service-test-password".to_owned(),
        )
        .await
        .expect("test account should be created");
        let old_hash: String = sqlx::query_scalar("SELECT password_hash FROM users WHERE id = ?")
            .bind(user_id)
            .fetch_one(&pool)
            .await
            .unwrap();
        let browser_token_id: i64 = sqlx::query_scalar(
            "INSERT INTO api_tokens (user_id, name, token_hash, purpose, created_at) \
             VALUES (?, 'browser', 'test-browser-session-hash', 'browser', unixepoch()) \
             RETURNING id",
        )
        .bind(user_id)
        .fetch_one(&pool)
        .await
        .unwrap();
        sqlx::query("UPDATE users SET password_hash = 'newer-password-hash' WHERE id = ?")
            .bind(user_id)
            .execute(&pool)
            .await
            .unwrap();

        let changed = update_password_and_revoke_sessions(
            &pool,
            user_id,
            "stale-browser-password-hash",
            &old_hash,
        )
        .await
        .unwrap();
        assert!(!changed);
        let stored_hash: String =
            sqlx::query_scalar("SELECT password_hash FROM users WHERE id = ?")
                .bind(user_id)
                .fetch_one(&pool)
                .await
                .unwrap();
        assert_eq!(stored_hash, "newer-password-hash");
        let revoked_at: Option<i64> =
            sqlx::query_scalar("SELECT revoked_at FROM api_tokens WHERE id = ?")
                .bind(browser_token_id)
                .fetch_one(&pool)
                .await
                .unwrap();
        assert_eq!(revoked_at, None);
    }
}
