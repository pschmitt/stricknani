//! Types crossing the boundary between OIDC verification and account storage.

use chrono::{DateTime, Utc};

/// An identity constructed only after all OIDC token checks have succeeded.
///
/// Fields stay private so downstream code cannot accidentally treat claims
/// parsed from an unverified JWT as an authenticated identity.
#[derive(Clone, PartialEq, Eq)]
pub struct ValidatedIdentity {
    provider_id: String,
    issuer: String,
    subject: String,
    email: Option<String>,
    email_verified: bool,
    auth_time: Option<DateTime<Utc>>,
}

impl ValidatedIdentity {
    pub fn provider_id(&self) -> &str {
        &self.provider_id
    }

    pub fn issuer(&self) -> &str {
        &self.issuer
    }

    pub fn subject(&self) -> &str {
        &self.subject
    }

    pub fn email(&self) -> Option<&str> {
        self.email.as_deref()
    }

    pub fn email_verified(&self) -> bool {
        self.email_verified
    }

    pub fn auth_time(&self) -> Option<DateTime<Utc>> {
        self.auth_time
    }

    #[cfg(test)]
    pub(crate) fn fixture(
        provider_id: &str,
        issuer: &str,
        subject: &str,
        email: Option<&str>,
        email_verified: bool,
        auth_time: Option<DateTime<Utc>>,
    ) -> Self {
        Self {
            provider_id: provider_id.to_owned(),
            issuer: issuer.to_owned(),
            subject: subject.to_owned(),
            email: email.map(str::to_owned),
            email_verified,
            auth_time,
        }
    }

    pub(super) fn new(
        provider_id: String,
        issuer: String,
        subject: String,
        email: Option<String>,
        email_verified: bool,
        auth_time: Option<DateTime<Utc>>,
    ) -> Self {
        Self {
            provider_id,
            issuer,
            subject,
            email,
            email_verified,
            auth_time,
        }
    }
}

/// The operation authorized by a short-lived OIDC flow.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum FlowPurpose {
    /// Authenticate an existing local account through a linked identity.
    Login,
    /// Link the validated identity to this authenticated local account.
    Link { owner_user_id: i64 },
    /// Reauthenticate this local account for a sensitive operation.
    Reauth {
        owner_user_id: i64,
        action: ReauthAction,
    },
}

/// Sensitive operation for which a fresh OIDC authentication was requested.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum ReauthAction {
    LinkIdentity,
    UnlinkIdentity,
    AdminUnlinkIdentity,
    ChangePassword,
    CreateApiToken,
    RevokeApiToken,
}
