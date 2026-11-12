//! Transactional storage operations for identities verified by the OIDC layer.
//!
//! This module accepts only [`ValidatedIdentity`] values from the protocol
//! boundary. It never matches accounts by email and never returns a raw
//! provider subject to callers.

use std::collections::HashSet;

use rand::TryRng;
use rand::rngs::SysRng;
use sha2::{Digest, Sha256};
use sqlx::{Row, Sqlite, SqlitePool, Transaction};
use zeroize::{Zeroize, Zeroizing};

use crate::oidc::ValidatedIdentity;

const MAX_PROVIDER_ID_BYTES: usize = 128;
const MAX_ISSUER_BYTES: usize = 2048;
const MAX_SUBJECT_BYTES: usize = 255;
const MAX_EMAIL_BYTES: usize = 320;
const MAX_TOKEN_NAME_BYTES: usize = 255;
const MAX_OIDC_PAT_TTL_SECONDS: i64 = 7 * 24 * 60 * 60;
const MAX_OIDC_BROWSER_TTL_SECONDS: i64 = 14 * 24 * 60 * 60;
const MAX_API_TOKENS_PER_HOUR: i64 = 5;
const RECENT_AUTH_MAX_AGE_SECONDS: i64 = 600;

/// Recent authentication input. Native proofs are one-time, hash-only rows
/// bound to the requested operation and target. Browser freshness comes only
/// from the server-side session stamp.
#[derive(Clone, Copy)]
pub(crate) enum RecentAuth<'a> {
    BrowserSession { token_id: i64 },
    NativeProof { token_id: i64, proof_hash: &'a str },
}

/// Hash a native proof before placing it in a [`RecentAuth`] value.
pub(crate) fn hash_recent_auth_proof(proof: &str) -> String {
    hex::encode(Sha256::digest(proof.as_bytes()))
}

#[derive(Clone, Copy)]
pub(crate) enum RecentAction<'a> {
    LinkIdentity {
        provider_id: &'a str,
    },
    UnlinkIdentity {
        identity_id: i64,
    },
    AdminUnlinkIdentity {
        target_user_id: i64,
        identity_id: i64,
    },
    ChangePassword,
    CreateApiToken,
    RevokeApiToken {
        token_id: i64,
    },
    DeleteAccount,
    /// Add or remove a passkey.
    ManagePasskeys,
    RestoreBackup,
}

#[derive(Clone, Copy)]
enum AuthAuditAction {
    IdentityLinked,
    IdentityUnlinked,
    IdentityAdminUnlinked,
    OidcLoginSucceeded,
    OidcTokenMinted,
    ApiTokenCreated,
    AccountDeleted,
}

impl AuthAuditAction {
    const fn as_str(self) -> &'static str {
        match self {
            Self::IdentityLinked => "identity_linked",
            Self::IdentityUnlinked => "identity_unlinked",
            Self::IdentityAdminUnlinked => "identity_admin_unlinked",
            Self::OidcLoginSucceeded => "oidc_login_succeeded",
            Self::OidcTokenMinted => "oidc_token_minted",
            Self::ApiTokenCreated => "api_token_created",
            Self::AccountDeleted => "account_deleted",
        }
    }
}

/// Issue a one-time native step-up proof after the caller has verified a
/// password or OIDC reauthentication. The raw proof is returned once and is
/// never persisted. The caller must bind the HTTP response to the same PAT.
pub(crate) async fn issue_native_proof(
    pool: &SqlitePool,
    actor_user_id: i64,
    api_token_id: i64,
    target_user_id: i64,
    action: RecentAction<'_>,
    now: i64,
    expires_at: i64,
) -> Result<String, OidcIdentityError> {
    if expires_at <= now || expires_at > now.saturating_add(600) {
        return Err(OidcIdentityError::InvalidTokenExpiry);
    }
    action.ensure_target(actor_user_id, target_user_id)?;
    let (provider_id, identity_id, target_token_id) = action.fields();

    let mut tx = crate::db::begin_write(pool).await?;
    require_actors_tx(&mut tx, action, actor_user_id, target_user_id).await?;
    validate_action_target_tx(&mut tx, actor_user_id, target_user_id, action, now).await?;
    if !api_token_active_tx(&mut tx, api_token_id, actor_user_id, now).await? {
        return Err(OidcIdentityError::Forbidden);
    }
    sqlx::query("DELETE FROM auth_reauth_proofs WHERE consumed_at IS NOT NULL OR expires_at <= ?")
        .bind(now)
        .execute(&mut *tx)
        .await?;
    let mut proof_bytes = [0_u8; 32];
    SysRng
        .try_fill_bytes(&mut proof_bytes)
        .map_err(|_| OidcIdentityError::TokenGeneration)?;
    let proof = Zeroizing::new(format!("reauth_{}", hex::encode(proof_bytes)));
    proof_bytes.zeroize();
    let proof_hash = hash_recent_auth_proof(&proof);
    sqlx::query(
        "INSERT INTO auth_reauth_proofs \
         (proof_hash, user_id, api_token_id, purpose, target_user_id, provider_id, identity_id, target_token_id, expires_at) \
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
    )
    .bind(proof_hash)
    .bind(actor_user_id)
    .bind(api_token_id)
    .bind(action.purpose())
    .bind(target_user_id)
    .bind(provider_id)
    .bind(identity_id)
    .bind(target_token_id)
    .bind(expires_at)
    .execute(&mut *tx)
    .await?;
    tx.commit().await?;
    Ok(proof.to_string())
}

impl<'a> RecentAction<'a> {
    fn purpose(self) -> &'static str {
        match self {
            Self::LinkIdentity { .. } => "link_identity",
            Self::UnlinkIdentity { .. } => "unlink_identity",
            Self::AdminUnlinkIdentity { .. } => "admin_unlink_identity",
            Self::ChangePassword => "change_password",
            Self::CreateApiToken => "create_api_token",
            Self::RevokeApiToken { .. } => "revoke_api_token",
            Self::DeleteAccount => "delete_account",
            Self::ManagePasskeys => "manage_passkeys",
            Self::RestoreBackup => "restore_backup",
        }
    }

    fn is_admin_operation(self) -> bool {
        matches!(self, Self::AdminUnlinkIdentity { .. })
    }

    fn ensure_target(
        self,
        actor_user_id: i64,
        target_user_id: i64,
    ) -> Result<(), OidcIdentityError> {
        if actor_user_id <= 0 || target_user_id <= 0 {
            return Err(OidcIdentityError::InvalidIdentity);
        }
        let expected_target = match self {
            Self::AdminUnlinkIdentity {
                target_user_id,
                identity_id,
            } => {
                if identity_id <= 0 {
                    return Err(OidcIdentityError::InvalidIdentity);
                }
                target_user_id
            }
            Self::LinkIdentity { provider_id } => {
                if provider_id.is_empty() || provider_id.len() > MAX_PROVIDER_ID_BYTES {
                    return Err(OidcIdentityError::InvalidIdentity);
                }
                actor_user_id
            }
            Self::UnlinkIdentity { identity_id } => {
                if identity_id <= 0 {
                    return Err(OidcIdentityError::InvalidIdentity);
                }
                actor_user_id
            }
            Self::ChangePassword
            | Self::CreateApiToken
            | Self::DeleteAccount
            | Self::ManagePasskeys
            | Self::RestoreBackup => actor_user_id,
            Self::RevokeApiToken { token_id } => {
                if token_id <= 0 {
                    return Err(OidcIdentityError::InvalidIdentity);
                }
                actor_user_id
            }
        };
        if expected_target == target_user_id {
            Ok(())
        } else {
            Err(OidcIdentityError::Forbidden)
        }
    }

    fn fields(self) -> (Option<&'a str>, Option<i64>, Option<i64>) {
        match self {
            Self::LinkIdentity { provider_id } => (Some(provider_id), None, None),
            Self::UnlinkIdentity { identity_id }
            | Self::AdminUnlinkIdentity { identity_id, .. } => (None, Some(identity_id), None),
            Self::RevokeApiToken { token_id } => (None, None, Some(token_id)),
            Self::ChangePassword
            | Self::CreateApiToken
            | Self::DeleteAccount
            | Self::ManagePasskeys
            | Self::RestoreBackup => (None, None, None),
        }
    }
}

/// Validate recent authentication when a sensitive flow starts. Native proof
/// validation here is non-consuming; the matching completion service consumes
/// it atomically with its identity mutation.
pub(crate) async fn validate_recent_auth(
    pool: &SqlitePool,
    reauth: RecentAuth<'_>,
    actor_user_id: i64,
    target_user_id: i64,
    action: RecentAction<'_>,
    now: i64,
) -> Result<(), OidcIdentityError> {
    action.ensure_target(actor_user_id, target_user_id)?;
    let mut tx = crate::db::begin_write(pool).await?;
    require_actors_tx(&mut tx, action, actor_user_id, target_user_id).await?;
    require_recent_auth_tx(
        &mut tx,
        reauth,
        actor_user_id,
        target_user_id,
        action,
        now,
        false,
    )
    .await?;
    tx.commit().await?;
    Ok(())
}

/// Consume a validated proof inside a caller's serialized mutation transaction.
/// Generic password/token services use this for the other frozen reauth actions.
pub(crate) async fn consume_recent_auth_tx(
    tx: &mut Transaction<'_, Sqlite>,
    reauth: RecentAuth<'_>,
    actor_user_id: i64,
    target_user_id: i64,
    action: RecentAction<'_>,
    now: i64,
) -> Result<(), OidcIdentityError> {
    action.ensure_target(actor_user_id, target_user_id)?;
    require_actors_tx(tx, action, actor_user_id, target_user_id).await?;
    require_recent_auth_tx(tx, reauth, actor_user_id, target_user_id, action, now, true).await
}

/// Safe identity projection for account and administrator views.
#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) struct IdentitySummary {
    pub id: i64,
    pub provider_id: String,
    pub subject_hint: String,
    pub verified_email: Option<String>,
    pub linked_at: i64,
}

/// An OIDC login token. The token is returned once; only its SHA-256 hash is
/// persisted.
// The route layer consumes these fields in the next OIDC slice; keep this
// storage-only slice warning-clean before that caller is merged.
#[allow(dead_code)]
pub(crate) struct MintedToken {
    pub user_id: i64,
    pub token: String,
    pub expires_at: i64,
}

#[derive(Debug, thiserror::Error)]
pub(crate) enum OidcIdentityError {
    #[error("database error")]
    Database(#[from] sqlx::Error),
    #[error("identity is invalid")]
    InvalidIdentity,
    #[error("identity is already linked")]
    IdentityAlreadyLinked,
    #[error("identity was not found")]
    NotFound,
    #[error("account is inactive or unauthorized")]
    Forbidden,
    #[error("recent authentication is required")]
    RecentAuthenticationRequired,
    #[error("unlinking would remove the last usable login method")]
    LastLoginMethod,
    #[error("token expiry must be in the future")]
    InvalidTokenExpiry,
    #[error("API token issuance rate limit exceeded; retry after {0} seconds")]
    TokenRateLimited(u64),
    #[error("token generation failed")]
    TokenGeneration,
}

/// List identities belonging to an active account.
pub(crate) async fn list_identities(
    pool: &SqlitePool,
    user_id: i64,
) -> Result<Vec<IdentitySummary>, OidcIdentityError> {
    let mut tx = crate::db::begin_write(pool).await?;
    require_active_user_tx(&mut tx, user_id).await?;
    list_identities_tx(tx, user_id).await
}

/// List identities for an active target account after checking the active
/// administrator inside the same read transaction.
pub(crate) async fn admin_list_identities(
    pool: &SqlitePool,
    actor_user_id: i64,
    target_user_id: i64,
) -> Result<Vec<IdentitySummary>, OidcIdentityError> {
    let mut tx = crate::db::begin_write(pool).await?;
    require_active_admin_tx(&mut tx, actor_user_id).await?;
    require_active_user_tx(&mut tx, target_user_id).await?;
    list_identities_tx(tx, target_user_id).await
}

async fn list_identities_tx(
    mut tx: Transaction<'_, Sqlite>,
    user_id: i64,
) -> Result<Vec<IdentitySummary>, OidcIdentityError> {
    let rows = sqlx::query(
        "SELECT id, provider_id, issuer, subject, verified_email, linked_at \
         FROM oidc_identities WHERE user_id = ? ORDER BY linked_at, id",
    )
    .bind(user_id)
    .fetch_all(&mut *tx)
    .await?;
    let result = rows.into_iter().map(decode_summary).collect();
    tx.commit().await?;
    result
}

/// Link a validated identity to the currently authenticated user's account.
/// The caller supplies the current configured provider IDs so stale callbacks
/// from a disabled provider cannot create a link.
pub(crate) async fn link_identity(
    pool: &SqlitePool,
    user_id: i64,
    identity: &ValidatedIdentity,
    enabled_provider_ids: &[String],
    reauth: RecentAuth<'_>,
    now: i64,
) -> Result<IdentitySummary, OidcIdentityError> {
    validate_identity(identity)?;
    if !enabled_provider_ids
        .iter()
        .any(|id| id == identity.provider_id())
    {
        return Err(OidcIdentityError::NotFound);
    }
    let verified_email = if identity.email_verified() {
        identity.email()
    } else {
        None
    };
    let mut tx = crate::db::begin_write(pool).await?;
    require_active_user_tx(&mut tx, user_id).await?;
    require_recent_auth_tx(
        &mut tx,
        reauth,
        user_id,
        user_id,
        RecentAction::LinkIdentity {
            provider_id: identity.provider_id(),
        },
        now,
        true,
    )
    .await?;
    let inserted = sqlx::query(
        "INSERT INTO oidc_identities \
         (user_id, provider_id, issuer, subject, verified_email, linked_at) \
         VALUES (?, ?, ?, ?, ?, ?)",
    )
    .bind(user_id)
    .bind(identity.provider_id())
    .bind(identity.issuer())
    .bind(identity.subject())
    .bind(verified_email)
    .bind(now)
    .execute(&mut *tx)
    .await;
    let identity_id = match inserted {
        Ok(result) => result.last_insert_rowid(),
        Err(error) if is_unique_violation(&error) => {
            return Err(OidcIdentityError::IdentityAlreadyLinked);
        }
        Err(error) => return Err(error.into()),
    };
    insert_auth_audit(
        &mut tx,
        AuthAuditAction::IdentityLinked,
        Some(user_id),
        user_id,
        identity_id,
        identity.provider_id(),
        now,
    )
    .await?;
    let result = IdentitySummary {
        id: identity_id,
        provider_id: identity.provider_id().to_owned(),
        subject_hint: subject_hint(identity.issuer(), identity.subject()),
        verified_email: verified_email.map(str::to_owned),
        linked_at: now,
    };
    tx.commit().await?;
    Ok(result)
}

/// Unlink one identity owned by the authenticated user. Existing grants
/// minted through the identity are revoked before the link is deleted.
pub(crate) async fn unlink_identity(
    pool: &SqlitePool,
    user_id: i64,
    identity_id: i64,
    reauth: RecentAuth<'_>,
    enabled_provider_ids: &[String],
    now: i64,
) -> Result<(), OidcIdentityError> {
    let mut tx = crate::db::begin_write(pool).await?;
    require_active_user_tx(&mut tx, user_id).await?;
    require_recent_auth_tx(
        &mut tx,
        reauth,
        user_id,
        user_id,
        RecentAction::UnlinkIdentity { identity_id },
        now,
        true,
    )
    .await?;
    delete_identity_tx(
        tx,
        user_id,
        identity_id,
        enabled_provider_ids,
        AuthAuditAction::IdentityUnlinked,
        user_id,
        now,
    )
    .await
}

/// Remove an identity from a target account after rechecking active
/// administrator and target-account state under the serialized write lock.
pub(crate) async fn admin_unlink_identity(
    pool: &SqlitePool,
    actor_user_id: i64,
    target_user_id: i64,
    identity_id: i64,
    reauth: RecentAuth<'_>,
    enabled_provider_ids: &[String],
    now: i64,
) -> Result<(), OidcIdentityError> {
    let mut tx = crate::db::begin_write(pool).await?;
    require_active_admin_tx(&mut tx, actor_user_id).await?;
    require_active_user_tx(&mut tx, target_user_id).await?;
    require_recent_auth_tx(
        &mut tx,
        reauth,
        actor_user_id,
        target_user_id,
        RecentAction::AdminUnlinkIdentity {
            target_user_id,
            identity_id,
        },
        now,
        true,
    )
    .await?;
    delete_identity_tx(
        tx,
        target_user_id,
        identity_id,
        enabled_provider_ids,
        AuthAuditAction::IdentityAdminUnlinked,
        actor_user_id,
        now,
    )
    .await
}

async fn delete_identity_tx(
    mut tx: Transaction<'_, Sqlite>,
    owner_user_id: i64,
    identity_id: i64,
    enabled_provider_ids: &[String],
    audit: AuthAuditAction,
    actor_user_id: i64,
    now: i64,
) -> Result<(), OidcIdentityError> {
    let provider_id = get_identity_tx(&mut tx, identity_id, owner_user_id).await?;
    ensure_login_method_remains_tx(&mut tx, owner_user_id, identity_id, enabled_provider_ids)
        .await?;
    revoke_identity_tokens_tx(&mut tx, identity_id, now).await?;
    sqlx::query("DELETE FROM oidc_identities WHERE id = ? AND user_id = ?")
        .bind(identity_id)
        .bind(owner_user_id)
        .execute(&mut *tx)
        .await?;
    insert_auth_audit(
        &mut tx,
        audit,
        Some(actor_user_id),
        owner_user_id,
        identity_id,
        &provider_id,
        now,
    )
    .await?;
    tx.commit().await?;
    Ok(())
}

/// Resolve only the exact validated `(issuer, subject)` pair. An unknown or
/// inactive identity returns `None`, leaving callers one neutral unlinked
/// result to display.
pub(crate) async fn lookup_login_identity(
    pool: &SqlitePool,
    identity: &ValidatedIdentity,
) -> Result<Option<i64>, OidcIdentityError> {
    validate_identity_key(identity)?;
    let row = sqlx::query(
        "SELECT u.id FROM oidc_identities i JOIN users u ON u.id = i.user_id \
         WHERE i.issuer = ? COLLATE BINARY AND i.subject = ? COLLATE BINARY \
           AND u.active = 1",
    )
    .bind(identity.issuer())
    .bind(identity.subject())
    .fetch_optional(pool)
    .await?;
    Ok(row.map(|row| row.get("id")))
}

/// Mint a finite-lived API token only if the validated identity is still
/// bound to an active user. The binding and token insert share one serialized
/// transaction; only the token hash reaches SQLite.
pub(crate) async fn mint_login_token(
    pool: &SqlitePool,
    identity: &ValidatedIdentity,
    enabled_provider_ids: &[String],
    name: &str,
    purpose: &str,
    now: i64,
    expires_at: i64,
) -> Result<MintedToken, OidcIdentityError> {
    validate_identity_key(identity)?;
    let max_ttl = match purpose {
        "api" => MAX_OIDC_PAT_TTL_SECONDS,
        "browser" => MAX_OIDC_BROWSER_TTL_SECONDS,
        _ => return Err(OidcIdentityError::InvalidIdentity),
    };
    if expires_at <= now
        || expires_at.saturating_sub(now) > max_ttl
        || name.is_empty()
        || name.len() > MAX_TOKEN_NAME_BYTES
    {
        return Err(OidcIdentityError::InvalidTokenExpiry);
    }
    let mut tx = crate::db::begin_write(pool).await?;
    let binding = sqlx::query(
        "UPDATE oidc_identities SET id = id WHERE issuer = ? COLLATE BINARY \
         AND subject = ? COLLATE BINARY \
         AND EXISTS (SELECT 1 FROM users WHERE users.id = oidc_identities.user_id \
                     AND users.active = 1)",
    )
    .bind(identity.issuer())
    .bind(identity.subject())
    .execute(&mut *tx)
    .await?;
    if binding.rows_affected() != 1 {
        return Err(OidcIdentityError::NotFound);
    }
    let row = sqlx::query(
        "SELECT i.id AS identity_id, i.user_id, i.provider_id FROM oidc_identities i \
         JOIN users u ON u.id = i.user_id WHERE i.issuer = ? COLLATE BINARY \
           AND i.subject = ? COLLATE BINARY AND u.active = 1",
    )
    .bind(identity.issuer())
    .bind(identity.subject())
    .fetch_one(&mut *tx)
    .await?;
    let identity_id: i64 = row.get("identity_id");
    let user_id: i64 = row.get("user_id");
    let provider_id: String = row.get("provider_id");
    if !enabled_provider_ids.iter().any(|id| id == &provider_id) {
        return Err(OidcIdentityError::NotFound);
    }
    if purpose == "api" {
        let window_start = now.saturating_sub(3600);
        let oldest_recent: Option<i64> = sqlx::query_scalar(
            "SELECT min(created_at) FROM api_tokens WHERE user_id = ? AND purpose = 'api' \
             AND created_at > ?",
        )
        .bind(user_id)
        .bind(window_start)
        .fetch_one(&mut *tx)
        .await?;
        let recent_count: i64 = sqlx::query_scalar(
            "SELECT count(*) FROM api_tokens WHERE user_id = ? AND purpose = 'api' \
             AND created_at > ?",
        )
        .bind(user_id)
        .bind(window_start)
        .fetch_one(&mut *tx)
        .await?;
        if recent_count >= MAX_API_TOKENS_PER_HOUR {
            let retry_after = oldest_recent
                .map(|oldest| (3600_i64 - now.saturating_sub(oldest)).max(1) as u64)
                .unwrap_or(1);
            return Err(OidcIdentityError::TokenRateLimited(retry_after));
        }
    }
    let mut token_bytes = [0_u8; 32];
    SysRng
        .try_fill_bytes(&mut token_bytes)
        .map_err(|_| OidcIdentityError::TokenGeneration)?;
    let token = Zeroizing::new(format!("sna_{}", hex::encode(token_bytes)));
    token_bytes.zeroize();
    let token_hash = hex::encode(Sha256::digest(token.as_bytes()));
    sqlx::query(
        "INSERT INTO api_tokens \
         (user_id, name, token_hash, created_at, expires_at, purpose, auth_origin, \
          oidc_identity_id, oidc_provider_id) \
         VALUES (?, ?, ?, ?, ?, ?, 'oidc', ?, ?)",
    )
    .bind(user_id)
    .bind(name)
    .bind(token_hash)
    .bind(now)
    .bind(expires_at)
    .bind(purpose)
    .bind(identity_id)
    .bind(&provider_id)
    .execute(&mut *tx)
    .await?;
    insert_auth_audit(
        &mut tx,
        AuthAuditAction::OidcLoginSucceeded,
        Some(user_id),
        user_id,
        identity_id,
        &provider_id,
        now,
    )
    .await?;
    insert_auth_audit(
        &mut tx,
        AuthAuditAction::OidcTokenMinted,
        Some(user_id),
        user_id,
        identity_id,
        &provider_id,
        now,
    )
    .await?;
    tx.commit().await?;
    Ok(MintedToken {
        user_id,
        token: token.to_string(),
        expires_at,
    })
}

/// Store a successful reauthentication on its browser-session row. The
/// session is revalidated and updated under a write lock; callers supply
/// `now` from the server clock after verifying the password or OIDC reauth.
pub(crate) async fn stamp_browser_reauth(
    pool: &SqlitePool,
    user_id: i64,
    browser_token_id: i64,
    now: i64,
) -> Result<(), OidcIdentityError> {
    let mut tx = crate::db::begin_write(pool).await?;
    require_active_user_tx(&mut tx, user_id).await?;
    let result = sqlx::query(
        "UPDATE api_tokens SET reauth_at = ? WHERE id = ? AND user_id = ? \
         AND purpose = 'browser' AND revoked_at IS NULL \
         AND (expires_at IS NULL OR expires_at > ?)",
    )
    .bind(now)
    .bind(browser_token_id)
    .bind(user_id)
    .bind(now)
    .execute(&mut *tx)
    .await?;
    if result.rows_affected() != 1 {
        return Err(OidcIdentityError::Forbidden);
    }
    tx.commit().await?;
    Ok(())
}

fn validate_identity(identity: &ValidatedIdentity) -> Result<(), OidcIdentityError> {
    validate_identity_key(identity)?;
    let email_valid = !identity.email_verified()
        || identity.email().is_none_or(|email| {
            email.len() <= MAX_EMAIL_BYTES
                && email.contains('@')
                && !email.contains(char::is_whitespace)
        });
    if identity.provider_id().is_empty()
        || identity.provider_id().len() > MAX_PROVIDER_ID_BYTES
        || !email_valid
    {
        return Err(OidcIdentityError::InvalidIdentity);
    }
    Ok(())
}

fn validate_identity_key(identity: &ValidatedIdentity) -> Result<(), OidcIdentityError> {
    if identity.issuer().is_empty()
        || identity.issuer().len() > MAX_ISSUER_BYTES
        || identity.subject().is_empty()
        || identity.subject().len() > MAX_SUBJECT_BYTES
    {
        return Err(OidcIdentityError::InvalidIdentity);
    }
    Ok(())
}

async fn require_active_user_tx(
    tx: &mut Transaction<'_, Sqlite>,
    user_id: i64,
) -> Result<(), OidcIdentityError> {
    require_active_tx(tx, user_id, false).await
}

async fn require_active_admin_tx(
    tx: &mut Transaction<'_, Sqlite>,
    user_id: i64,
) -> Result<(), OidcIdentityError> {
    require_active_tx(tx, user_id, true).await
}

async fn require_active_tx(
    tx: &mut Transaction<'_, Sqlite>,
    user_id: i64,
    admin: bool,
) -> Result<(), OidcIdentityError> {
    let result = sqlx::query(
        "UPDATE users SET id = id WHERE id = ? AND active = 1 AND (is_admin = 1 OR ? = 0)",
    )
    .bind(user_id)
    .bind(admin)
    .execute(&mut **tx)
    .await?;
    if result.rows_affected() == 1 {
        Ok(())
    } else {
        Err(OidcIdentityError::Forbidden)
    }
}

/// Lock the acting account (an administrator for admin operations) and, for
/// admin operations, the target account.
async fn require_actors_tx(
    tx: &mut Transaction<'_, Sqlite>,
    action: RecentAction<'_>,
    actor_user_id: i64,
    target_user_id: i64,
) -> Result<(), OidcIdentityError> {
    if action.is_admin_operation() {
        require_active_admin_tx(tx, actor_user_id).await?;
        require_active_user_tx(tx, target_user_id).await?;
    } else {
        require_active_user_tx(tx, actor_user_id).await?;
    }
    Ok(())
}

async fn api_token_active_tx(
    tx: &mut Transaction<'_, Sqlite>,
    token_id: i64,
    user_id: i64,
    now: i64,
) -> Result<bool, OidcIdentityError> {
    Ok(sqlx::query_scalar(
        "SELECT EXISTS(SELECT 1 FROM api_tokens WHERE id = ? AND user_id = ? \
         AND purpose = 'api' AND revoked_at IS NULL \
         AND (expires_at IS NULL OR expires_at > ?))",
    )
    .bind(token_id)
    .bind(user_id)
    .bind(now)
    .fetch_one(&mut **tx)
    .await?)
}

async fn require_recent_auth_tx(
    tx: &mut Transaction<'_, Sqlite>,
    reauth: RecentAuth<'_>,
    actor_user_id: i64,
    target_user_id: i64,
    action: RecentAction<'_>,
    now: i64,
    consume_native_proof: bool,
) -> Result<(), OidcIdentityError> {
    action.ensure_target(actor_user_id, target_user_id)?;
    validate_action_target_tx(tx, actor_user_id, target_user_id, action, now).await?;
    match reauth {
        RecentAuth::BrowserSession { token_id } => {
            let reauth_at: Option<i64> = sqlx::query_scalar(
                "SELECT reauth_at FROM api_tokens WHERE id = ? AND user_id = ? \
                 AND purpose = 'browser' AND revoked_at IS NULL \
                 AND (expires_at IS NULL OR expires_at > ?)",
            )
            .bind(token_id)
            .bind(actor_user_id)
            .bind(now)
            .fetch_optional(&mut **tx)
            .await?
            .flatten();
            if !reauth_at.is_some_and(|stamp| {
                stamp <= now && now.saturating_sub(stamp) <= RECENT_AUTH_MAX_AGE_SECONDS
            }) {
                return Err(OidcIdentityError::RecentAuthenticationRequired);
            }
        }
        RecentAuth::NativeProof {
            token_id,
            proof_hash,
        } => {
            if !api_token_active_tx(tx, token_id, actor_user_id, now).await? {
                return Err(OidcIdentityError::RecentAuthenticationRequired);
            }
            let (provider_id, identity_id, target_token_id) = action.fields();
            let valid: bool = if consume_native_proof {
                sqlx::query(
                    "UPDATE auth_reauth_proofs SET consumed_at = ? \
                     WHERE proof_hash = ? AND user_id = ? AND api_token_id = ? \
                       AND purpose = ? AND target_user_id = ? \
                       AND provider_id IS ? AND identity_id IS ? AND target_token_id IS ? \
                       AND consumed_at IS NULL AND expires_at > ?",
                )
                .bind(now)
                .bind(proof_hash)
                .bind(actor_user_id)
                .bind(token_id)
                .bind(action.purpose())
                .bind(target_user_id)
                .bind(provider_id)
                .bind(identity_id)
                .bind(target_token_id)
                .bind(now)
                .execute(&mut **tx)
                .await?
                .rows_affected()
                    == 1
            } else {
                sqlx::query_scalar(
                    "SELECT EXISTS(SELECT 1 FROM auth_reauth_proofs \
                     WHERE proof_hash = ? AND user_id = ? AND api_token_id = ? \
                       AND purpose = ? AND target_user_id = ? \
                       AND provider_id IS ? AND identity_id IS ? AND target_token_id IS ? \
                       AND consumed_at IS NULL AND expires_at > ?)",
                )
                .bind(proof_hash)
                .bind(actor_user_id)
                .bind(token_id)
                .bind(action.purpose())
                .bind(target_user_id)
                .bind(provider_id)
                .bind(identity_id)
                .bind(target_token_id)
                .bind(now)
                .fetch_one(&mut **tx)
                .await?
            };
            if !valid {
                return Err(OidcIdentityError::RecentAuthenticationRequired);
            }
        }
    }
    Ok(())
}

async fn validate_action_target_tx(
    tx: &mut Transaction<'_, Sqlite>,
    actor_user_id: i64,
    target_user_id: i64,
    action: RecentAction<'_>,
    now: i64,
) -> Result<(), OidcIdentityError> {
    let visible = match action {
        RecentAction::UnlinkIdentity { identity_id }
        | RecentAction::AdminUnlinkIdentity { identity_id, .. } => {
            sqlx::query_scalar(
                "SELECT EXISTS(SELECT 1 FROM oidc_identities WHERE id = ? AND user_id = ?)",
            )
            .bind(identity_id)
            .bind(target_user_id)
            .fetch_one(&mut **tx)
            .await?
        }
        RecentAction::RevokeApiToken { token_id } => {
            api_token_active_tx(tx, token_id, actor_user_id, now).await?
        }
        RecentAction::LinkIdentity { .. }
        | RecentAction::ChangePassword
        | RecentAction::CreateApiToken
        | RecentAction::DeleteAccount
        | RecentAction::ManagePasskeys
        | RecentAction::RestoreBackup => true,
    };
    if visible {
        Ok(())
    } else {
        Err(OidcIdentityError::NotFound)
    }
}

async fn ensure_login_method_remains_tx(
    tx: &mut Transaction<'_, Sqlite>,
    user_id: i64,
    removed_identity_id: i64,
    enabled_provider_ids: &[String],
) -> Result<(), OidcIdentityError> {
    let password_enabled: bool =
        sqlx::query_scalar("SELECT password_enabled = 1 FROM users WHERE id = ? AND active = 1")
            .bind(user_id)
            .fetch_optional(&mut **tx)
            .await?
            .ok_or(OidcIdentityError::Forbidden)?;
    if password_enabled {
        return Ok(());
    }
    if enabled_provider_ids.is_empty() {
        return Err(OidcIdentityError::LastLoginMethod);
    }
    let enabled: HashSet<&str> = enabled_provider_ids.iter().map(String::as_str).collect();
    let provider_ids: Vec<String> =
        sqlx::query_scalar("SELECT provider_id FROM oidc_identities WHERE user_id = ? AND id <> ?")
            .bind(user_id)
            .bind(removed_identity_id)
            .fetch_all(&mut **tx)
            .await?;
    if provider_ids
        .iter()
        .any(|provider| enabled.contains(provider.as_str()))
    {
        Ok(())
    } else {
        Err(OidcIdentityError::LastLoginMethod)
    }
}

async fn get_identity_tx(
    tx: &mut Transaction<'_, Sqlite>,
    identity_id: i64,
    user_id: i64,
) -> Result<String, OidcIdentityError> {
    sqlx::query("SELECT provider_id FROM oidc_identities WHERE id = ? AND user_id = ?")
        .bind(identity_id)
        .bind(user_id)
        .fetch_optional(&mut **tx)
        .await?
        .map(|row| row.get("provider_id"))
        .ok_or(OidcIdentityError::NotFound)
}

async fn revoke_identity_tokens_tx(
    tx: &mut Transaction<'_, Sqlite>,
    identity_id: i64,
    now: i64,
) -> Result<(), OidcIdentityError> {
    sqlx::query(
        "UPDATE api_tokens SET revoked_at = coalesce(revoked_at, ?) \
         WHERE oidc_identity_id = ? AND revoked_at IS NULL",
    )
    .bind(now)
    .bind(identity_id)
    .execute(&mut **tx)
    .await?;
    Ok(())
}

/// Record an API token creation by `user_id` in the caller's transaction.
pub(crate) async fn record_api_token_created_tx(
    tx: &mut Transaction<'_, Sqlite>,
    user_id: i64,
    now: i64,
) -> Result<(), OidcIdentityError> {
    sqlx::query(
        "INSERT INTO auth_audit_events (action, actor_user_id, target_user_id, created_at) \
         VALUES (?, ?, ?, ?)",
    )
    .bind(AuthAuditAction::ApiTokenCreated.as_str())
    .bind(user_id)
    .bind(user_id)
    .bind(now)
    .execute(&mut **tx)
    .await?;
    Ok(())
}

/// Record an account deletion in the caller's transaction. The row has no
/// actor and no target: the account is gone and the event must not identify it.
pub(crate) async fn record_account_deleted_tx(
    tx: &mut Transaction<'_, Sqlite>,
    now: i64,
) -> Result<(), OidcIdentityError> {
    sqlx::query("INSERT INTO auth_audit_events (action, created_at) VALUES (?, ?)")
        .bind(AuthAuditAction::AccountDeleted.as_str())
        .bind(now)
        .execute(&mut **tx)
        .await?;
    Ok(())
}

async fn insert_auth_audit(
    tx: &mut Transaction<'_, Sqlite>,
    action: AuthAuditAction,
    actor_user_id: Option<i64>,
    target_user_id: i64,
    identity_id: i64,
    provider_id: &str,
    now: i64,
) -> Result<(), OidcIdentityError> {
    sqlx::query(
        "INSERT INTO auth_audit_events \
         (action, actor_user_id, target_user_id, identity_id, provider_id, created_at) \
         VALUES (?, ?, ?, ?, ?, ?)",
    )
    .bind(action.as_str())
    .bind(actor_user_id)
    .bind(target_user_id)
    .bind(identity_id)
    .bind(provider_id)
    .bind(now)
    .execute(&mut **tx)
    .await?;
    Ok(())
}

fn decode_summary(row: sqlx::sqlite::SqliteRow) -> Result<IdentitySummary, OidcIdentityError> {
    let issuer: String = row.try_get("issuer")?;
    let subject: String = row.try_get("subject")?;
    Ok(IdentitySummary {
        id: row.try_get("id")?,
        provider_id: row.try_get("provider_id")?,
        subject_hint: subject_hint(&issuer, &subject),
        verified_email: row.try_get("verified_email")?,
        linked_at: row.try_get("linked_at")?,
    })
}

fn subject_hint(issuer: &str, subject: &str) -> String {
    let mut digest = Sha256::new();
    digest.update(issuer.as_bytes());
    digest.update([0]);
    digest.update(subject.as_bytes());
    let hex = hex::encode(digest.finalize());
    format!("id:{}", &hex[..8])
}

fn is_unique_violation(error: &sqlx::Error) -> bool {
    matches!(error, sqlx::Error::Database(db_error) if db_error.is_unique_violation())
}

#[cfg(test)]
mod tests {
    use chrono::{DateTime, Utc};

    use super::*;
    use crate::db;

    async fn fixture_user(pool: &SqlitePool, email: &str, is_admin: bool) -> i64 {
        let id = db::create_user(pool, email, "fixture-password-123".to_owned())
            .await
            .unwrap();
        if is_admin {
            sqlx::query("UPDATE users SET is_admin = 1 WHERE id = ?")
                .bind(id)
                .execute(pool)
                .await
                .unwrap();
        }
        id
    }

    async fn fixture_reauth(pool: &SqlitePool, user_id: i64, now: i64) -> RecentAuth<'static> {
        let session_number: i64 = sqlx::query_scalar(
            "SELECT count(*) FROM api_tokens WHERE user_id = ? AND purpose = 'browser'",
        )
        .bind(user_id)
        .fetch_one(pool)
        .await
        .unwrap();
        let token_id: i64 = sqlx::query_scalar(
            "INSERT INTO api_tokens (user_id, name, token_hash, created_at, purpose, reauth_at) \
             VALUES (?, 'test browser', ?, ?, 'browser', ?) RETURNING id",
        )
        .bind(user_id)
        .bind(format!("fixture-session-{user_id}-{session_number}"))
        .bind(now - 60)
        .bind(now)
        .fetch_one(pool)
        .await
        .unwrap();
        RecentAuth::BrowserSession { token_id }
    }

    #[tokio::test]
    async fn browser_reauthentication_stamp_is_server_side_and_session_bound() {
        let pool = db::connect("sqlite::memory:").await.unwrap();
        let user_id = fixture_user(&pool, "reauth-stamp@example.test", false).await;
        let now = Utc::now().timestamp();
        let token_id: i64 = sqlx::query_scalar(
            "INSERT INTO api_tokens (user_id, name, token_hash, created_at, purpose) \
             VALUES (?, 'browser', 'fixture-browser-session', ?, 'browser') RETURNING id",
        )
        .bind(user_id)
        .bind(now - 60)
        .fetch_one(&pool)
        .await
        .unwrap();
        stamp_browser_reauth(&pool, user_id, token_id, now)
            .await
            .unwrap();
        let reauth_at: i64 = sqlx::query_scalar("SELECT reauth_at FROM api_tokens WHERE id = ?")
            .bind(token_id)
            .fetch_one(&pool)
            .await
            .unwrap();
        assert_eq!(reauth_at, now);

        let another_user = fixture_user(&pool, "other-reauth@example.test", false).await;
        assert!(matches!(
            stamp_browser_reauth(&pool, another_user, token_id, now).await,
            Err(OidcIdentityError::Forbidden)
        ));
    }

    fn identity(provider: &str, issuer: &str, subject: &str) -> ValidatedIdentity {
        ValidatedIdentity::fixture(
            provider,
            issuer,
            subject,
            Some("user@example.test"),
            true,
            None,
        )
    }

    fn enabled(provider: &str) -> Vec<String> {
        vec![provider.to_owned()]
    }

    #[tokio::test]
    async fn linking_is_unique_across_owners_and_audited_atomically() {
        let pool = db::connect("sqlite::memory:").await.unwrap();
        let first = fixture_user(&pool, "first@example.test", false).await;
        let second = fixture_user(&pool, "second@example.test", false).await;
        let original_hash: String =
            sqlx::query_scalar("SELECT password_hash FROM users WHERE id = ?")
                .bind(first)
                .fetch_one(&pool)
                .await
                .unwrap();
        let now = Utc::now().timestamp();
        let proof = fixture_reauth(&pool, first, now).await;
        let linked = link_identity(
            &pool,
            first,
            &identity("provider", "https://idp.test", "Subject"),
            &enabled("provider"),
            proof,
            now,
        )
        .await
        .unwrap();
        assert_eq!(linked.provider_id, "provider");
        assert_eq!(
            linked.subject_hint,
            subject_hint("https://idp.test", "Subject")
        );
        assert!(linked.subject_hint.starts_with("id:"));
        assert!(!linked.subject_hint.contains("Subject"));
        let audit_count: i64 = sqlx::query_scalar(
            "SELECT count(*) FROM auth_audit_events WHERE action = 'identity_linked'",
        )
        .fetch_one(&pool)
        .await
        .unwrap();
        assert_eq!(audit_count, 1);

        let proof = fixture_reauth(&pool, second, now).await;
        let error = link_identity(
            &pool,
            second,
            &identity("provider", "https://idp.test", "Subject"),
            &enabled("provider"),
            proof,
            now,
        )
        .await
        .unwrap_err();
        assert!(matches!(error, OidcIdentityError::IdentityAlreadyLinked));
        let owner: i64 = sqlx::query_scalar("SELECT user_id FROM oidc_identities WHERE id = ?")
            .bind(linked.id)
            .fetch_one(&pool)
            .await
            .unwrap();
        assert_eq!(owner, first);
        let current_hash: String =
            sqlx::query_scalar("SELECT password_hash FROM users WHERE id = ?")
                .bind(first)
                .fetch_one(&pool)
                .await
                .unwrap();
        let password_enabled: bool =
            sqlx::query_scalar("SELECT password_enabled = 1 FROM users WHERE id = ?")
                .bind(first)
                .fetch_one(&pool)
                .await
                .unwrap();
        assert_eq!(current_hash, original_hash);
        assert!(password_enabled);
    }

    #[tokio::test]
    async fn unlinking_last_login_method_fails_without_partial_audit() {
        let pool = db::connect("sqlite::memory:").await.unwrap();
        let user_id = fixture_user(&pool, "oidc-only@example.test", false).await;
        sqlx::query("UPDATE users SET password_enabled = 0 WHERE id = ?")
            .bind(user_id)
            .execute(&pool)
            .await
            .unwrap();
        let now = Utc::now().timestamp();
        let proof = fixture_reauth(&pool, user_id, now).await;
        let linked = link_identity(
            &pool,
            user_id,
            &identity("provider", "https://idp.test", "subject"),
            &enabled("provider"),
            proof,
            now,
        )
        .await
        .unwrap();
        let proof = fixture_reauth(&pool, user_id, now).await;
        let error = unlink_identity(&pool, user_id, linked.id, proof, &enabled("provider"), now)
            .await
            .unwrap_err();
        assert!(matches!(error, OidcIdentityError::LastLoginMethod));
        let identity_count: i64 =
            sqlx::query_scalar("SELECT count(*) FROM oidc_identities WHERE user_id = ?")
                .bind(user_id)
                .fetch_one(&pool)
                .await
                .unwrap();
        let audit_count: i64 = sqlx::query_scalar(
            "SELECT count(*) FROM auth_audit_events WHERE action = 'identity_unlinked'",
        )
        .fetch_one(&pool)
        .await
        .unwrap();
        assert_eq!(identity_count, 1);
        assert_eq!(audit_count, 0);
    }

    #[tokio::test]
    async fn admin_list_and_unlink_recheck_admin_and_target_login_methods() {
        let pool = db::connect("sqlite::memory:").await.unwrap();
        let admin_id = fixture_user(&pool, "admin@example.test", true).await;
        let target_id = fixture_user(&pool, "target@example.test", false).await;
        sqlx::query("UPDATE users SET password_enabled = 0 WHERE id = ?")
            .bind(target_id)
            .execute(&pool)
            .await
            .unwrap();
        let now = Utc::now().timestamp();
        let target_proof = fixture_reauth(&pool, target_id, now).await;
        let linked = link_identity(
            &pool,
            target_id,
            &identity("provider", "https://idp.test", "admin-test-subject"),
            &enabled("provider"),
            target_proof,
            now,
        )
        .await
        .unwrap();
        assert_eq!(
            admin_list_identities(&pool, admin_id, target_id)
                .await
                .unwrap()
                .len(),
            1
        );

        let admin_proof = fixture_reauth(&pool, admin_id, now).await;
        let error = admin_unlink_identity(
            &pool,
            admin_id,
            target_id,
            linked.id,
            admin_proof,
            &enabled("provider"),
            now,
        )
        .await
        .unwrap_err();
        assert!(matches!(error, OidcIdentityError::LastLoginMethod));

        sqlx::query("UPDATE users SET password_enabled = 1 WHERE id = ?")
            .bind(target_id)
            .execute(&pool)
            .await
            .unwrap();
        let admin_proof = fixture_reauth(&pool, admin_id, now).await;
        admin_unlink_identity(
            &pool,
            admin_id,
            target_id,
            linked.id,
            admin_proof,
            &enabled("provider"),
            now,
        )
        .await
        .unwrap();

        sqlx::query("UPDATE users SET active = 0 WHERE id = ?")
            .bind(admin_id)
            .execute(&pool)
            .await
            .unwrap();
        assert!(matches!(
            admin_list_identities(&pool, admin_id, target_id).await,
            Err(OidcIdentityError::Forbidden)
        ));
        let admin_proof = fixture_reauth(&pool, admin_id, now).await;
        assert!(matches!(
            admin_unlink_identity(
                &pool,
                admin_id,
                target_id,
                linked.id,
                admin_proof,
                &enabled("provider"),
                now,
            )
            .await,
            Err(OidcIdentityError::Forbidden)
        ));
    }

    #[tokio::test]
    async fn inactive_accounts_cannot_link_or_mint_tokens() {
        let pool = db::connect("sqlite::memory:").await.unwrap();
        let user_id = fixture_user(&pool, "inactive@example.test", false).await;
        let now = Utc::now().timestamp();
        let proof = fixture_reauth(&pool, user_id, now).await;
        let valid = identity("provider", "https://idp.test", "subject");
        link_identity(&pool, user_id, &valid, &enabled("provider"), proof, now)
            .await
            .unwrap();
        sqlx::query("UPDATE users SET active = 0 WHERE id = ?")
            .bind(user_id)
            .execute(&pool)
            .await
            .unwrap();
        assert!(matches!(
            list_identities(&pool, user_id).await,
            Err(OidcIdentityError::Forbidden)
        ));
        assert!(matches!(
            mint_login_token(
                &pool,
                &valid,
                &enabled("provider"),
                "oidc",
                "api",
                now,
                now + 60
            )
            .await,
            Err(OidcIdentityError::NotFound)
        ));
    }

    #[test]
    fn subject_hint_is_stable_and_does_not_reveal_subject_fragments() {
        let issuer = "https://idp.test";
        let subject = "alice-private-subject";
        let hint = subject_hint(issuer, subject);
        let mut expected_input = issuer.as_bytes().to_vec();
        expected_input.push(0);
        expected_input.extend_from_slice(subject.as_bytes());
        let expected_digest = hex::encode(Sha256::digest(expected_input));
        assert_eq!(hint, format!("id:{}", &expected_digest[..8]));
        assert_eq!(hint, subject_hint(issuer, subject));
        assert!(hint.starts_with("id:"));
        assert!(!hint.contains("alice"));
        assert!(!hint.contains("ject"));
        assert_ne!(
            hint,
            subject_hint("https://other-idp.test", "alice-private-subject")
        );
    }

    #[tokio::test]
    async fn token_mint_persists_only_hash_and_requires_finite_expiry() {
        let pool = db::connect("sqlite::memory:").await.unwrap();
        let user_id = fixture_user(&pool, "token@example.test", false).await;
        let now = Utc::now().timestamp();
        let proof = fixture_reauth(&pool, user_id, now).await;
        let valid = identity("provider", "https://idp.test", "subject");
        link_identity(&pool, user_id, &valid, &enabled("provider"), proof, now)
            .await
            .unwrap();
        assert!(matches!(
            mint_login_token(&pool, &valid, &enabled("provider"), "oidc", "api", now, now,).await,
            Err(OidcIdentityError::InvalidTokenExpiry)
        ));
        let minted = mint_login_token(
            &pool,
            &valid,
            &enabled("provider"),
            "oidc",
            "api",
            now,
            now + 3600,
        )
        .await
        .unwrap();
        let stored: (String, String, Option<i64>) = sqlx::query_as(
            "SELECT token_hash, auth_origin, expires_at FROM api_tokens WHERE user_id = ? AND auth_origin = 'oidc'",
        )
        .bind(user_id)
        .fetch_one(&pool)
        .await
        .unwrap();
        assert_ne!(stored.0, minted.token);
        assert_eq!(stored.1, "oidc");
        assert_eq!(stored.2, Some(now + 3600));
        assert_eq!(
            hex::encode(Sha256::digest(minted.token.as_bytes())),
            stored.0
        );
        assert!(matches!(
            mint_login_token(
                &pool,
                &valid,
                &enabled("provider"),
                "oidc",
                "api",
                now,
                now + MAX_OIDC_PAT_TTL_SECONDS + 1,
            )
            .await,
            Err(OidcIdentityError::InvalidTokenExpiry)
        ));
    }

    #[tokio::test]
    async fn oidc_api_token_mint_obeys_hourly_quota_including_revoked_tokens() {
        let pool = db::connect("sqlite::memory:").await.unwrap();
        let user_id = fixture_user(&pool, "quota@example.test", false).await;
        let now = Utc::now().timestamp();
        let proof = fixture_reauth(&pool, user_id, now).await;
        let valid = identity("provider", "https://idp.test", "quota-subject");
        link_identity(&pool, user_id, &valid, &enabled("provider"), proof, now)
            .await
            .unwrap();

        for index in 0..MAX_API_TOKENS_PER_HOUR {
            sqlx::query(
                "INSERT INTO api_tokens \
                 (user_id, name, token_hash, created_at, purpose, revoked_at) \
                 VALUES (?, ?, ?, ?, 'api', ?)",
            )
            .bind(user_id)
            .bind(format!("old token {index}"))
            .bind(hex::encode(Sha256::digest(
                format!("stored-{index}").as_bytes(),
            )))
            .bind(now - 60)
            .bind((index % 2 == 0).then_some(now - 30))
            .execute(&pool)
            .await
            .unwrap();
        }

        let before_audit: i64 = sqlx::query_scalar("SELECT count(*) FROM auth_audit_events")
            .fetch_one(&pool)
            .await
            .unwrap();
        assert!(matches!(
            mint_login_token(
                &pool,
                &valid,
                &enabled("provider"),
                "sixth API token",
                "api",
                now,
                now + 3600,
            )
            .await,
            Err(OidcIdentityError::TokenRateLimited(3540))
        ));
        let api_count: i64 = sqlx::query_scalar(
            "SELECT count(*) FROM api_tokens WHERE user_id = ? AND purpose = 'api'",
        )
        .bind(user_id)
        .fetch_one(&pool)
        .await
        .unwrap();
        let after_audit: i64 = sqlx::query_scalar("SELECT count(*) FROM auth_audit_events")
            .fetch_one(&pool)
            .await
            .unwrap();
        assert_eq!(api_count, MAX_API_TOKENS_PER_HOUR);
        assert_eq!(after_audit, before_audit);

        let browser = mint_login_token(
            &pool,
            &valid,
            &enabled("provider"),
            "browser session",
            "browser",
            now,
            now + 3600,
        )
        .await
        .unwrap();
        assert!(!browser.token.is_empty());
    }

    #[tokio::test]
    async fn native_step_up_proof_is_target_bound_hash_only_and_one_time() {
        let pool = db::connect("sqlite::memory:").await.unwrap();
        let user_id = fixture_user(&pool, "native-step-up@example.test", false).await;
        let now = Utc::now().timestamp();
        let token_id: i64 = sqlx::query_scalar(
            "INSERT INTO api_tokens (user_id, name, token_hash, created_at, purpose) \
             VALUES (?, 'native', 'fixture-pat-hash', ?, 'api') RETURNING id",
        )
        .bind(user_id)
        .bind(now - 60)
        .fetch_one(&pool)
        .await
        .unwrap();
        let action = RecentAction::LinkIdentity {
            provider_id: "provider",
        };
        let proof = issue_native_proof(&pool, user_id, token_id, user_id, action, now, now + 600)
            .await
            .unwrap();
        let proof_hash = hash_recent_auth_proof(&proof);
        let stored_hash: String =
            sqlx::query_scalar("SELECT proof_hash FROM auth_reauth_proofs WHERE user_id = ?")
                .bind(user_id)
                .fetch_one(&pool)
                .await
                .unwrap();
        assert_eq!(stored_hash, proof_hash);
        assert_ne!(stored_hash, proof);

        validate_recent_auth(
            &pool,
            RecentAuth::NativeProof {
                token_id,
                proof_hash: &proof_hash,
            },
            user_id,
            user_id,
            action,
            now,
        )
        .await
        .unwrap();
        let consumed_at: Option<i64> =
            sqlx::query_scalar("SELECT consumed_at FROM auth_reauth_proofs WHERE proof_hash = ?")
                .bind(&proof_hash)
                .fetch_one(&pool)
                .await
                .unwrap();
        assert_eq!(consumed_at, None);

        link_identity(
            &pool,
            user_id,
            &identity("provider", "https://idp.test", "native-subject"),
            &enabled("provider"),
            RecentAuth::NativeProof {
                token_id,
                proof_hash: &proof_hash,
            },
            now,
        )
        .await
        .unwrap();
        let replay = link_identity(
            &pool,
            user_id,
            &identity("provider", "https://idp.test", "another-subject"),
            &enabled("provider"),
            RecentAuth::NativeProof {
                token_id,
                proof_hash: &proof_hash,
            },
            now,
        )
        .await
        .unwrap_err();
        assert!(matches!(
            replay,
            OidcIdentityError::RecentAuthenticationRequired
        ));
    }

    #[tokio::test]
    async fn reauth_actions_are_isolated_and_generic_proofs_consume_in_write_tx() {
        let pool = db::connect("sqlite::memory:").await.unwrap();
        let user_id = fixture_user(&pool, "reauth-actions@example.test", false).await;
        let now = Utc::now().timestamp();
        let api_token_id: i64 = sqlx::query_scalar(
            "INSERT INTO api_tokens (user_id, name, token_hash, created_at, purpose) \
             VALUES (?, 'native', 'fixture-action-pat', ?, 'api') RETURNING id",
        )
        .bind(user_id)
        .bind(now - 60)
        .fetch_one(&pool)
        .await
        .unwrap();
        let action = RecentAction::ChangePassword;
        let proof = issue_native_proof(
            &pool,
            user_id,
            api_token_id,
            user_id,
            action,
            now,
            now + 600,
        )
        .await
        .unwrap();
        let proof_hash = hash_recent_auth_proof(&proof);
        let recent_auth = RecentAuth::NativeProof {
            token_id: api_token_id,
            proof_hash: &proof_hash,
        };
        let error = validate_recent_auth(
            &pool,
            recent_auth,
            user_id,
            user_id,
            RecentAction::CreateApiToken,
            now,
        )
        .await
        .unwrap_err();
        assert!(matches!(
            error,
            OidcIdentityError::RecentAuthenticationRequired
        ));

        validate_recent_auth(&pool, recent_auth, user_id, user_id, action, now)
            .await
            .unwrap();
        let mut tx = crate::db::begin_write(&pool).await.unwrap();
        consume_recent_auth_tx(&mut tx, recent_auth, user_id, user_id, action, now)
            .await
            .unwrap();
        tx.commit().await.unwrap();
        let mut tx = crate::db::begin_write(&pool).await.unwrap();
        let replay = consume_recent_auth_tx(&mut tx, recent_auth, user_id, user_id, action, now)
            .await
            .unwrap_err();
        assert!(matches!(
            replay,
            OidcIdentityError::RecentAuthenticationRequired
        ));
    }

    #[test]
    fn all_reauth_actions_have_distinct_purposes_and_targets() {
        let actions = [
            RecentAction::LinkIdentity {
                provider_id: "authelia",
            },
            RecentAction::UnlinkIdentity { identity_id: 11 },
            RecentAction::AdminUnlinkIdentity {
                target_user_id: 22,
                identity_id: 12,
            },
            RecentAction::ChangePassword,
            RecentAction::CreateApiToken,
            RecentAction::RevokeApiToken { token_id: 13 },
            RecentAction::DeleteAccount,
        ];
        let purposes: Vec<&str> = actions.iter().map(|action| action.purpose()).collect();
        assert_eq!(
            purposes,
            [
                "link_identity",
                "unlink_identity",
                "admin_unlink_identity",
                "change_password",
                "create_api_token",
                "revoke_api_token",
                "delete_account",
            ]
        );
        assert!(actions[0].ensure_target(1, 1).is_ok());
        assert!(actions[2].ensure_target(1, 22).is_ok());
        assert!(matches!(
            actions[2].ensure_target(1, 1),
            Err(OidcIdentityError::Forbidden)
        ));
        assert_eq!(actions[0].fields(), (Some("authelia"), None, None));
        assert_eq!(actions[5].fields(), (None, None, Some(13)));
    }

    #[tokio::test]
    async fn failed_link_does_not_leave_audit_event() {
        let pool = db::connect("sqlite::memory:").await.unwrap();
        let user_id = fixture_user(&pool, "recent@example.test", false).await;
        let now = Utc::now().timestamp();
        let proof = fixture_reauth(&pool, user_id, now - 601).await;
        let error = link_identity(
            &pool,
            user_id,
            &identity("provider", "https://idp.test", "subject"),
            &enabled("provider"),
            proof,
            now,
        )
        .await
        .unwrap_err();
        assert!(matches!(
            error,
            OidcIdentityError::RecentAuthenticationRequired
        ));
        let identity_count: i64 = sqlx::query_scalar("SELECT count(*) FROM oidc_identities")
            .fetch_one(&pool)
            .await
            .unwrap();
        let audit_count: i64 = sqlx::query_scalar("SELECT count(*) FROM auth_audit_events")
            .fetch_one(&pool)
            .await
            .unwrap();
        assert_eq!(identity_count, 0);
        assert_eq!(audit_count, 0);
    }

    #[tokio::test]
    async fn unlink_revokes_identity_tokens_and_records_typed_audit() {
        let pool = db::connect("sqlite::memory:").await.unwrap();
        let user_id = fixture_user(&pool, "unlink@example.test", false).await;
        let now = Utc::now().timestamp();
        let proof = fixture_reauth(&pool, user_id, now).await;
        let valid = identity("provider", "https://idp.test", "subject");
        let linked = link_identity(&pool, user_id, &valid, &enabled("provider"), proof, now)
            .await
            .unwrap();
        let minted = mint_login_token(
            &pool,
            &valid,
            &enabled("provider"),
            "oidc",
            "api",
            now,
            now + 60,
        )
        .await
        .unwrap();
        let proof = fixture_reauth(&pool, user_id, now).await;
        unlink_identity(&pool, user_id, linked.id, proof, &enabled("provider"), now)
            .await
            .unwrap();
        let token_state: (Option<i64>, Option<i64>, String) = sqlx::query_as(
            "SELECT revoked_at, oidc_identity_id, auth_origin FROM api_tokens WHERE token_hash = ?",
        )
        .bind(hex::encode(Sha256::digest(minted.token.as_bytes())))
        .fetch_one(&pool)
        .await
        .unwrap();
        assert_eq!(token_state.0, Some(now));
        assert_eq!(token_state.1, None);
        assert_eq!(token_state.2, "oidc");
        let audit_action: String = sqlx::query_scalar("SELECT action FROM auth_audit_events WHERE target_user_id = ? ORDER BY id DESC LIMIT 1")
            .bind(user_id)
            .fetch_one(&pool)
            .await
            .unwrap();
        assert_eq!(audit_action, "identity_unlinked");
    }

    #[tokio::test]
    async fn unknown_identity_does_not_match_by_email_or_subject_case() {
        let pool = db::connect("sqlite::memory:").await.unwrap();
        let user_id = fixture_user(&pool, "user@example.test", false).await;
        let now = Utc::now().timestamp();
        let proof = fixture_reauth(&pool, user_id, now).await;
        let valid = identity("provider", "https://idp.test", "Subject");
        link_identity(&pool, user_id, &valid, &enabled("provider"), proof, now)
            .await
            .unwrap();
        assert_eq!(
            lookup_login_identity(&pool, &identity("provider", "https://idp.test", "subject"))
                .await
                .unwrap(),
            None
        );
        assert_eq!(
            lookup_login_identity(
                &pool,
                &identity("provider-alias", "https://idp.test", "Subject")
            )
            .await
            .unwrap(),
            Some(user_id)
        );
        let changed_email = ValidatedIdentity::fixture(
            "provider",
            "https://idp.test",
            "different-subject",
            Some("user@example.test"),
            true,
            None::<DateTime<Utc>>,
        );
        assert_eq!(
            lookup_login_identity(&pool, &changed_email).await.unwrap(),
            None
        );
    }
}
