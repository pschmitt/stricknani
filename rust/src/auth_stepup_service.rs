//! Password and credential handling for sensitive API v2 actions.

use axum::http::{HeaderMap, header};
use sqlx::SqlitePool;
use zeroize::Zeroizing;

use crate::{
    auth,
    oidc_identity_service::{self, OidcIdentityError, RecentAction, RecentAuth},
};

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum CredentialKind {
    Browser,
    Native,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) struct CurrentCredential {
    pub user_id: i64,
    pub token_id: i64,
    pub kind: CredentialKind,
}

#[derive(Debug, thiserror::Error)]
pub(crate) enum StepupError {
    #[error("request credential is invalid")]
    Unauthorized,
    #[error("password is invalid")]
    InvalidCredentials,
    #[error("password verification failed")]
    Verification,
    #[error("database error")]
    Database(#[from] sqlx::Error),
    #[error(transparent)]
    Identity(#[from] OidcIdentityError),
}

/// Resolve the token row behind a credential already accepted by API auth.
/// Authorization headers always select the PAT path; callers reject invalid
/// bearer credentials before invoking this helper and never fall back to a
/// browser cookie.
pub(crate) async fn current_credential(
    pool: &SqlitePool,
    headers: &HeaderMap,
    user_id: i64,
    secure_cookies: bool,
) -> Result<CurrentCredential, StepupError> {
    let (raw_token, purpose, kind) = if headers.contains_key(header::AUTHORIZATION) {
        let values = headers.get_all(header::AUTHORIZATION);
        if values.iter().count() != 1 {
            return Err(StepupError::Unauthorized);
        }
        let bearer = values
            .iter()
            .next()
            .and_then(|value| value.to_str().ok())
            .and_then(parse_bearer)
            .ok_or(StepupError::Unauthorized)?;
        (bearer, "api", CredentialKind::Native)
    } else {
        let token = auth::cookie(headers, auth::session_cookie_name(secure_cookies))
            .ok_or(StepupError::Unauthorized)?;
        (token, "browser", CredentialKind::Browser)
    };
    let token_id = sqlx::query_scalar::<_, i64>(
        "SELECT id FROM api_tokens WHERE token_hash = ? AND user_id = ? AND purpose = ? \
         AND revoked_at IS NULL AND (expires_at IS NULL OR expires_at > unixepoch())",
    )
    .bind(auth::token_hash(raw_token.as_bytes()))
    .bind(user_id)
    .bind(purpose)
    .fetch_optional(pool)
    .await?
    .ok_or(StepupError::Unauthorized)?;
    Ok(CurrentCredential {
        user_id,
        token_id,
        kind,
    })
}

pub(crate) fn native_proof_hash(headers: &HeaderMap) -> Result<String, StepupError> {
    let values = headers.get_all("x-reauth-token");
    if values.iter().count() != 1 {
        return Err(StepupError::Identity(
            OidcIdentityError::RecentAuthenticationRequired,
        ));
    }
    let proof = values
        .iter()
        .next()
        .and_then(|value| value.to_str().ok())
        .filter(|value| !value.is_empty() && value.len() <= 256)
        .ok_or(StepupError::Identity(
            OidcIdentityError::RecentAuthenticationRequired,
        ))?;
    Ok(oidc_identity_service::hash_recent_auth_proof(proof))
}

pub(crate) fn recent_auth<'a>(
    credential: CurrentCredential,
    proof_hash: Option<&'a str>,
) -> Result<RecentAuth<'a>, StepupError> {
    match credential.kind {
        CredentialKind::Browser => Ok(RecentAuth::BrowserSession {
            token_id: credential.token_id,
        }),
        CredentialKind::Native => Ok(RecentAuth::NativeProof {
            token_id: credential.token_id,
            proof_hash: proof_hash.ok_or(StepupError::Identity(
                OidcIdentityError::RecentAuthenticationRequired,
            ))?,
        }),
    }
}

/// Verify an account password and either stamp the current browser session or
/// issue a one-time proof bound to the current API token and exact action.
pub(crate) async fn password_stepup(
    pool: &SqlitePool,
    credential: CurrentCredential,
    password: Zeroizing<String>,
    action: RecentAction<'_>,
    target_user_id: i64,
    now: i64,
) -> Result<Option<String>, StepupError> {
    let row: Option<(String, i64)> = sqlx::query_as(
        "SELECT password_hash, password_enabled FROM users WHERE id = ? AND active = 1",
    )
    .bind(credential.user_id)
    .fetch_optional(pool)
    .await?;
    let candidate_hash = row
        .as_ref()
        .filter(|(_, password_enabled)| *password_enabled == 1)
        .map(|(hash, _)| hash.clone());
    if !auth::verify_password_for_stepup(password, candidate_hash)
        .await
        .map_err(|_| StepupError::Verification)?
    {
        return Err(StepupError::InvalidCredentials);
    }

    match credential.kind {
        CredentialKind::Browser => {
            oidc_identity_service::stamp_browser_reauth(
                pool,
                credential.user_id,
                credential.token_id,
                now,
            )
            .await?;
            Ok(None)
        }
        CredentialKind::Native => {
            let expires_at = now.saturating_add(600);
            let proof = oidc_identity_service::issue_native_proof(
                pool,
                credential.user_id,
                credential.token_id,
                target_user_id,
                action,
                now,
                expires_at,
            )
            .await?;
            Ok(Some(proof))
        }
    }
}

pub(crate) fn parse_bearer(value: &str) -> Option<&str> {
    let mut parts = value.split_ascii_whitespace();
    let scheme = parts.next()?;
    let token = parts.next()?;
    (scheme.eq_ignore_ascii_case("bearer") && parts.next().is_none() && !token.is_empty())
        .then_some(token)
}
