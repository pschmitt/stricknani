//! Passkeys (WebAuthn): an optional sign-in and step-up method next to the password.
//!
//! This module is the relying party. The HTTP layer is in `api_v2/passkeys.rs`; the protocol
//! (challenges, attestation and assertion verification, signature counters, origin and RP ID
//! checks) is `webauthn-rs`. What lives here:
//!
//! * the relying-party configuration: RP ID = the instance host name, accepted origins = the web
//!   origin(s) plus the Android app origin(s) derived from the configured signing certificates;
//! * the in-memory challenge store (short TTL, single use, bounded);
//! * the `passkeys` table (credentials of a user) and the auth-audit events for them.
//!
//! Security notes. Every ceremony is bound to a server-side challenge that is removed when it is
//! looked up, so it can be used once; it expires after [`CHALLENGE_TTL`]. Challenge state is kept
//! in memory on purpose: it is useless after a restart and never has to be serialized (which
//! webauthn-rs gates behind a `danger-` feature). User verification is always required, so a
//! passkey assertion is a two-factor proof (possession plus PIN or biometrics). Failures of a
//! login ceremony are reported as one indistinct error, so nothing reveals whether a credential
//! or an account exists.

use std::{
    collections::HashMap,
    sync::{Mutex, PoisonError},
    time::{Duration, Instant},
};

use base64::{Engine, engine::general_purpose::URL_SAFE_NO_PAD};
use rand::{TryRng, rngs::SysRng};
use serde::Serialize;
use sqlx::SqlitePool;
use webauthn_rs::prelude::{
    AuthenticationResult, CreationChallengeResponse, DiscoverableAuthentication, Passkey,
    PasskeyAuthentication, PasskeyRegistration, PublicKeyCredential, RegisterPublicKeyCredential,
    RequestChallengeResponse, Url, Uuid, Webauthn, WebauthnBuilder,
};

use crate::{
    app_links::AppLinks,
    oidc_identity_service::{self, OidcIdentityError, RecentAction, RecentAuth},
    security::TrustedHosts,
};

/// A ceremony must be completed within this time.
pub(crate) const CHALLENGE_TTL: Duration = Duration::from_secs(5 * 60);
/// Passkeys one account may hold.
pub(crate) const MAX_PASSKEYS_PER_USER: i64 = 10;
/// Pending ceremonies the server keeps at most; the oldest is dropped beyond it.
const MAX_PENDING_CHALLENGES: usize = 2048;
const RP_NAME: &str = "Stricknani";
const DEFAULT_NICKNAME: &str = "Passkey";

/// Relying-party settings derived from the server configuration.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct PasskeyConfig {
    rp_id: String,
    /// Web origin first (it must be under the RP ID), then Android app origins.
    origins: Vec<Url>,
    /// Trusted DNS names outside the RP ID: passkeys cannot work on them.
    uncovered_hosts: Vec<String>,
    /// In non-secure development with the `localhost` RP ID, loopback origins are aliases too.
    loopback_aliases: bool,
}

/// Whether `host` is the RP ID or a subdomain of it.
fn host_under(host: &str, rp_id: &str) -> bool {
    host == rp_id
        || host
            .strip_suffix(rp_id)
            .is_some_and(|prefix| prefix.ends_with('.'))
}

/// Names that make a poor default RP ID: loopback names and names without a dot.
fn is_local_name(host: &str) -> bool {
    host == "localhost" || host.ends_with(".localhost") || !host.contains('.')
}

impl PasskeyConfig {
    /// Derive the settings. `None` when passkeys are disabled or the instance has no DNS name
    /// (WebAuthn needs a host name as RP ID, never an IP address).
    ///
    /// * RP ID: `PASSKEY_RP_ID` or else the first real DNS name in `TRUSTED_HOSTS` in configured
    ///   order (loopback and single-label names such as `localhost` only when nothing else is
    ///   trusted, for development);
    /// * web origins: every trusted DNS name under the RP ID, over `https` when the session cookies
    ///   are `Secure` (HTTP is only meant for `localhost` development);
    /// * Android origins: `android:apk-key-hash:<base64url SHA-256 of each signing certificate>`
    ///   from `ANDROID_APP_LINK_FINGERPRINTS`.
    pub fn from_settings(
        enabled: bool,
        rp_id_override: Option<&str>,
        trusted_hosts: &TrustedHosts,
        secure: bool,
        bind_port: u16,
        android: Option<&AppLinks>,
    ) -> Result<Option<Self>, &'static str> {
        if !enabled {
            return Ok(None);
        }
        let authorities = trusted_hosts.domain_authorities();
        let rp_id = match rp_id_override
            .map(str::trim)
            .filter(|value| !value.is_empty())
        {
            Some(value) => value.to_ascii_lowercase(),
            None => match authorities
                .iter()
                .find(|(host, _)| !is_local_name(host))
                .or_else(|| authorities.first())
            {
                Some((host, _)) => host.clone(),
                None => return Ok(None),
            },
        };
        if rp_id.parse::<std::net::IpAddr>().is_ok() || rp_id.contains(['/', ':', '@']) {
            return Err("PASSKEY_RP_ID must be a DNS name");
        }
        let loopback_aliases = !secure
            && rp_id == "localhost"
            && authorities.iter().any(|(host, _)| host == "localhost");
        let scheme = if secure { "https" } else { "http" };
        let mut origins = Vec::new();
        let mut uncovered_hosts: Vec<String> = Vec::new();
        for (host, port) in authorities {
            if !host_under(&host, &rp_id) {
                if !is_local_name(&host)
                    && !(loopback_aliases && host == "127.0.0.1")
                    && !uncovered_hosts.contains(&host)
                {
                    uncovered_hosts.push(host);
                }
                continue;
            }
            let port = port.or_else(|| (!secure).then_some(bind_port));
            let origin = match port {
                Some(port) => format!("{scheme}://{host}:{port}"),
                None => format!("{scheme}://{host}"),
            };
            origins.push(Url::parse(&origin).map_err(|_| "passkey origin is invalid")?);
        }
        if loopback_aliases {
            origins.push(Url::parse("http://127.0.0.1").map_err(|_| "passkey origin is invalid")?);
            origins.push(Url::parse("http://[::1]").map_err(|_| "passkey origin is invalid")?);
        }
        if origins.is_empty() {
            return Err("PASSKEY_RP_ID must be a trusted host or a parent domain of one");
        }
        for fingerprint in android.map(AppLinks::fingerprints).unwrap_or_default() {
            origins.push(android_origin(fingerprint).ok_or("Android fingerprint is invalid")?);
        }
        Ok(Some(Self {
            rp_id,
            origins,
            uncovered_hosts,
            loopback_aliases,
        }))
    }

    pub fn rp_id(&self) -> &str {
        &self.rp_id
    }

    pub fn origins(&self) -> &[Url] {
        &self.origins
    }

    /// Trusted DNS names that are not under the RP ID (passkeys do not work on them).
    pub fn uncovered_hosts(&self) -> &[String] {
        &self.uncovered_hosts
    }
}

/// The origin Android's Credential Manager reports for an app signed with a certificate whose
/// SHA-256 fingerprint is given (`AB:CD:...`).
fn android_origin(fingerprint: &str) -> Option<Url> {
    let hex: String = fingerprint.chars().filter(|c| *c != ':').collect();
    let bytes = hex::decode(hex).ok().filter(|bytes| bytes.len() == 32)?;
    Url::parse(&format!(
        "android:apk-key-hash:{}",
        URL_SAFE_NO_PAD.encode(bytes)
    ))
    .ok()
}

/// How a login ceremony ends, so a challenge begun for one cannot finish the other.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum LoginChannel {
    /// A browser session (cookie plus CSRF token).
    Web,
    /// An API-purpose personal access token for a native app.
    Native,
}

enum Ceremony {
    Register {
        user_id: i64,
        token_id: i64,
        user_handle: [u8; 16],
        state: PasskeyRegistration,
    },
    Login {
        channel: LoginChannel,
        state: DiscoverableAuthentication,
    },
    Reauth {
        user_id: i64,
        token_id: i64,
        state: PasskeyAuthentication,
    },
}

struct Pending {
    ceremony: Ceremony,
    expires: Instant,
}

#[derive(Debug, thiserror::Error)]
pub(crate) enum PasskeyError {
    #[error("the challenge is unknown, expired or already used")]
    InvalidChallenge,
    #[error("the passkey could not be verified")]
    Verification,
    #[error("the passkey is already registered")]
    AlreadyRegistered,
    #[error("the account holds the maximum number of passkeys")]
    LimitReached,
    #[error("the account has no passkeys")]
    NoPasskeys,
    #[error("the passkey was not found")]
    NotFound,
    #[error("random number generation failed")]
    Random,
    #[error("database error")]
    Database(#[from] sqlx::Error),
    #[error(transparent)]
    Identity(#[from] OidcIdentityError),
}

/// A passkey as shown to its owner.
#[derive(Debug, Serialize, PartialEq, Eq)]
pub(crate) struct PasskeyView {
    pub id: i64,
    pub nickname: String,
    pub created_at: i64,
    pub last_used_at: Option<i64>,
    pub transports: Vec<String>,
    /// Whether the credential can be synced between devices (a "multi-device" passkey).
    pub backup_eligible: bool,
    /// Whether it is synced right now.
    pub backup_state: bool,
}

/// A started ceremony: the opaque id the client returns with its answer, and the WebAuthn options.
pub(crate) struct Started<T> {
    pub challenge_id: String,
    pub options: T,
}

/// The relying party and its pending ceremonies.
pub struct PasskeyRuntime {
    webauthn: Webauthn,
    rp_id: String,
    loopback_aliases: bool,
    pending: Mutex<HashMap<String, Pending>>,
}

impl std::fmt::Debug for PasskeyRuntime {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        formatter
            .debug_struct("PasskeyRuntime")
            .field("rp_id", &self.rp_id)
            .finish_non_exhaustive()
    }
}

impl PasskeyRuntime {
    pub fn new(config: &PasskeyConfig) -> Result<Self, &'static str> {
        let (first, rest) = config
            .origins
            .split_first()
            .ok_or("passkey origins are missing")?;
        let mut builder = WebauthnBuilder::new(&config.rp_id, first)
            .map_err(|_| "passkey relying party is invalid")?
            .rp_name(RP_NAME)
            .allow_any_port(config.loopback_aliases);
        for origin in rest {
            builder = builder.append_allowed_origin(origin);
        }
        Ok(Self {
            webauthn: builder
                .build()
                .map_err(|_| "passkey relying party is invalid")?,
            rp_id: config.rp_id.clone(),
            loopback_aliases: config.loopback_aliases,
            pending: Mutex::default(),
        })
    }

    pub fn rp_id(&self) -> &str {
        &self.rp_id
    }

    /// Whether a browser on this `Host` header can use passkeys: the host (port ignored) is the
    /// RP ID or under it. Browsers reject options for any other host. In non-secure development
    /// with the RP ID `localhost`, `127.0.0.1` and `[::1]` are aliases on any port.
    pub fn covers_host(&self, host: &str) -> bool {
        let name = host
            .rsplit_once(':')
            .filter(|(_, port)| port.bytes().all(|b| b.is_ascii_digit()))
            .map_or(host, |(name, _)| name)
            .to_ascii_lowercase();
        host_under(&name, &self.rp_id)
            || (self.loopback_aliases && matches!(name.as_str(), "127.0.0.1" | "[::1]"))
    }

    /// Store a ceremony and return its id.
    fn insert(&self, ceremony: Ceremony) -> Result<String, PasskeyError> {
        let mut id = [0_u8; 32];
        SysRng
            .try_fill_bytes(&mut id)
            .map_err(|_| PasskeyError::Random)?;
        let id = URL_SAFE_NO_PAD.encode(id);
        let now = Instant::now();
        let mut pending = self.pending.lock().unwrap_or_else(PoisonError::into_inner);
        pending.retain(|_, entry| entry.expires > now);
        if pending.len() >= MAX_PENDING_CHALLENGES
            && let Some(oldest) = pending
                .iter()
                .min_by_key(|(_, entry)| entry.expires)
                .map(|(key, _)| key.clone())
        {
            pending.remove(&oldest);
        }
        pending.insert(
            id.clone(),
            Pending {
                ceremony,
                expires: now + CHALLENGE_TTL,
            },
        );
        Ok(id)
    }

    /// Remove and return a pending ceremony: one use only, and only before it expires.
    fn take(&self, id: &str) -> Result<Ceremony, PasskeyError> {
        let entry = self
            .pending
            .lock()
            .unwrap_or_else(PoisonError::into_inner)
            .remove(id)
            .ok_or(PasskeyError::InvalidChallenge)?;
        if entry.expires <= Instant::now() {
            return Err(PasskeyError::InvalidChallenge);
        }
        Ok(entry.ceremony)
    }

    /// Start registering a passkey for `user_id`. The caller has checked recent authentication.
    pub(crate) async fn begin_registration(
        &self,
        pool: &SqlitePool,
        user_id: i64,
        token_id: i64,
    ) -> Result<Started<CreationChallengeResponse>, PasskeyError> {
        let email: String =
            sqlx::query_scalar("SELECT email FROM users WHERE id = ? AND active = 1")
                .bind(user_id)
                .fetch_optional(pool)
                .await?
                .ok_or(PasskeyError::NotFound)?;
        let existing: Vec<(Vec<u8>, Vec<u8>)> =
            sqlx::query_as("SELECT credential_id, user_handle FROM passkeys WHERE user_id = ?")
                .bind(user_id)
                .fetch_all(pool)
                .await?;
        if existing.len() as i64 >= MAX_PASSKEYS_PER_USER {
            return Err(PasskeyError::LimitReached);
        }
        // One stable handle per account: an authenticator that already holds a credential for
        // this handle is told so (exclude list) instead of silently replacing it.
        let user_handle = match existing
            .first()
            .and_then(|(_, handle)| <[u8; 16]>::try_from(handle.as_slice()).ok())
        {
            Some(handle) => handle,
            None => {
                let mut handle = [0_u8; 16];
                SysRng
                    .try_fill_bytes(&mut handle)
                    .map_err(|_| PasskeyError::Random)?;
                handle
            }
        };
        let exclude: Vec<_> = existing.iter().map(|(id, _)| id.clone()).collect();
        let (options, state) = self
            .webauthn
            .start_passkey_registration(
                Uuid::from_bytes(user_handle),
                &email,
                &email,
                (!exclude.is_empty()).then_some(exclude),
            )
            .map_err(|_| PasskeyError::Verification)?;
        let challenge_id = self.insert(Ceremony::Register {
            user_id,
            token_id,
            user_handle,
            state,
        })?;
        Ok(Started {
            challenge_id,
            options,
        })
    }

    /// Verify the attestation and store the passkey. Recent authentication is consumed in the
    /// same transaction as the insert.
    #[allow(clippy::too_many_arguments)]
    pub(crate) async fn finish_registration(
        &self,
        pool: &SqlitePool,
        user_id: i64,
        token_id: i64,
        challenge_id: &str,
        credential: &RegisterPublicKeyCredential,
        nickname: Option<&str>,
        reauth: RecentAuth<'_>,
        now: i64,
    ) -> Result<PasskeyView, PasskeyError> {
        let Ceremony::Register {
            user_id: owner,
            token_id: session,
            user_handle,
            state,
        } = self.take(challenge_id)?
        else {
            return Err(PasskeyError::InvalidChallenge);
        };
        if owner != user_id || session != token_id {
            return Err(PasskeyError::InvalidChallenge);
        }
        let passkey = self
            .webauthn
            .finish_passkey_registration(credential, &state)
            .map_err(|_| PasskeyError::Verification)?;
        let credential_id = passkey.cred_id().as_slice().to_vec();
        let serialized = serde_json::to_string(&passkey).map_err(|_| PasskeyError::Verification)?;
        let transports = credential
            .response
            .transports
            .as_ref()
            .map(|transports| {
                transports
                    .iter()
                    .filter_map(|transport| serde_json::to_value(transport).ok())
                    .filter_map(|value| value.as_str().map(str::to_owned))
                    .filter(|value| value.len() <= 16)
                    .take(6)
                    .collect::<Vec<_>>()
            })
            .unwrap_or_default();
        let nickname = nickname
            .map(str::trim)
            .filter(|value| !value.is_empty())
            .unwrap_or(DEFAULT_NICKNAME);

        let mut tx = crate::db::begin_write(pool).await?;
        oidc_identity_service::consume_recent_auth_tx(
            &mut tx,
            reauth,
            user_id,
            user_id,
            RecentAction::ManagePasskeys,
            now,
        )
        .await?;
        let count: i64 = sqlx::query_scalar("SELECT count(*) FROM passkeys WHERE user_id = ?")
            .bind(user_id)
            .fetch_one(&mut *tx)
            .await?;
        if count >= MAX_PASSKEYS_PER_USER {
            return Err(PasskeyError::LimitReached);
        }
        let inserted = sqlx::query_scalar::<_, i64>(
            "INSERT INTO passkeys (user_id, credential_id, user_handle, passkey, nickname, \
             transports, sign_count, backup_eligible, backup_state, created_at) \
             SELECT ?, ?, ?, ?, ?, ?, 0, ?, ?, ? WHERE EXISTS \
             (SELECT 1 FROM users WHERE id = ? AND active = 1) RETURNING id",
        )
        .bind(user_id)
        .bind(&credential_id)
        .bind(user_handle.as_slice())
        .bind(&serialized)
        .bind(nickname)
        .bind(serde_json::to_string(&transports).unwrap_or_else(|_| "[]".to_owned()))
        .bind(flag(&serialized, "backup_eligible"))
        .bind(flag(&serialized, "backup_state"))
        .bind(now)
        .bind(user_id)
        .fetch_optional(&mut *tx)
        .await;
        let id = match inserted {
            Ok(Some(id)) => id,
            Ok(None) => return Err(PasskeyError::NotFound),
            Err(sqlx::Error::Database(error)) if error.is_unique_violation() => {
                return Err(PasskeyError::AlreadyRegistered);
            }
            Err(error) => return Err(error.into()),
        };
        record_event_tx(&mut tx, "passkey_registered", user_id, user_id, now).await?;
        tx.commit().await?;
        load_view(pool, user_id, id)
            .await?
            .ok_or(PasskeyError::NotFound)
    }

    /// Start a username-less sign-in: the authenticator offers the passkeys it holds for this
    /// site and the signed user handle says which account they belong to.
    pub(crate) fn begin_login(
        &self,
        channel: LoginChannel,
    ) -> Result<Started<RequestChallengeResponse>, PasskeyError> {
        let (options, state) = self
            .webauthn
            .start_discoverable_authentication()
            .map_err(|_| PasskeyError::Verification)?;
        let challenge_id = self.insert(Ceremony::Login { channel, state })?;
        Ok(Started {
            challenge_id,
            options,
        })
    }

    /// Verify a sign-in assertion and return the account. Every failure (unknown credential,
    /// bad signature, wrong origin or RP ID, disabled account) is [`PasskeyError::Verification`].
    pub(crate) async fn finish_login(
        &self,
        pool: &SqlitePool,
        channel: LoginChannel,
        challenge_id: &str,
        credential: &PublicKeyCredential,
        now: i64,
    ) -> Result<i64, PasskeyError> {
        let Ceremony::Login {
            channel: begun,
            state,
        } = self.take(challenge_id)?
        else {
            return Err(PasskeyError::InvalidChallenge);
        };
        if begun != channel {
            return Err(PasskeyError::InvalidChallenge);
        }
        let (handle, credential_id) = self
            .webauthn
            .identify_discoverable_authentication(credential)
            .map_err(|_| PasskeyError::Verification)?;
        let row: Option<(i64, i64, String)> = sqlx::query_as(
            "SELECT passkeys.id, passkeys.user_id, passkeys.passkey FROM passkeys \
             JOIN users ON users.id = passkeys.user_id \
             WHERE passkeys.credential_id = ? AND passkeys.user_handle = ? AND users.active = 1",
        )
        .bind(credential_id)
        .bind(handle.as_bytes().as_slice())
        .fetch_optional(pool)
        .await?;
        let (passkey_id, user_id, serialized) = row.ok_or(PasskeyError::Verification)?;
        let mut passkey: Passkey =
            serde_json::from_str(&serialized).map_err(|_| PasskeyError::Verification)?;
        let result = self
            .webauthn
            .finish_discoverable_authentication_v2(credential, state, &passkey)
            .map_err(|_| PasskeyError::Verification)?;
        passkey.update_credential(&result);
        let mut tx = crate::db::begin_write(pool).await?;
        if !store_use(&mut tx, passkey_id, user_id, &passkey, &result, now).await? {
            return Err(PasskeyError::Verification);
        }
        record_event_tx(&mut tx, "passkey_login_succeeded", user_id, user_id, now).await?;
        tx.commit().await?;
        Ok(user_id)
    }

    /// Start a step-up ceremony for the signed-in user with one of their own passkeys.
    pub(crate) async fn begin_reauth(
        &self,
        pool: &SqlitePool,
        user_id: i64,
        token_id: i64,
    ) -> Result<Started<RequestChallengeResponse>, PasskeyError> {
        let passkeys = load_passkeys(pool, user_id).await?;
        if passkeys.is_empty() {
            return Err(PasskeyError::NoPasskeys);
        }
        let list: Vec<Passkey> = passkeys.into_iter().map(|(_, passkey)| passkey).collect();
        let (options, state) = self
            .webauthn
            .start_passkey_authentication(&list)
            .map_err(|_| PasskeyError::Verification)?;
        let challenge_id = self.insert(Ceremony::Reauth {
            user_id,
            token_id,
            state,
        })?;
        Ok(Started {
            challenge_id,
            options,
        })
    }

    /// Verify a step-up assertion of `user_id` made on the session `token_id`.
    pub(crate) async fn finish_reauth(
        &self,
        pool: &SqlitePool,
        user_id: i64,
        token_id: i64,
        challenge_id: &str,
        credential: &PublicKeyCredential,
        now: i64,
    ) -> Result<(), PasskeyError> {
        let Ceremony::Reauth {
            user_id: owner,
            token_id: session,
            state,
        } = self.take(challenge_id)?
        else {
            return Err(PasskeyError::InvalidChallenge);
        };
        if owner != user_id || session != token_id {
            return Err(PasskeyError::InvalidChallenge);
        }
        let result = self
            .webauthn
            .finish_passkey_authentication(credential, &state)
            .map_err(|_| PasskeyError::Verification)?;
        let (passkey_id, mut passkey) = load_passkeys(pool, user_id)
            .await?
            .into_iter()
            .find(|(_, passkey)| passkey.cred_id() == result.cred_id())
            .ok_or(PasskeyError::Verification)?;
        passkey.update_credential(&result);
        let mut tx = crate::db::begin_write(pool).await?;
        if !store_use(&mut tx, passkey_id, user_id, &passkey, &result, now).await? {
            return Err(PasskeyError::Verification);
        }
        record_event_tx(&mut tx, "passkey_reauthenticated", user_id, user_id, now).await?;
        tx.commit().await?;
        Ok(())
    }
}

/// Write the counter, backup flags and last use after a verified assertion. False when the
/// account was disabled or the passkey removed in the meantime.
async fn store_use(
    tx: &mut sqlx::Transaction<'_, sqlx::Sqlite>,
    passkey_id: i64,
    user_id: i64,
    passkey: &Passkey,
    result: &AuthenticationResult,
    now: i64,
) -> Result<bool, PasskeyError> {
    let serialized = serde_json::to_string(passkey).map_err(|_| PasskeyError::Verification)?;
    let updated = sqlx::query(
        "UPDATE passkeys SET passkey = ?, sign_count = ?, backup_eligible = ?, backup_state = ?, \
         last_used_at = ? WHERE id = ? AND user_id = ? \
         AND EXISTS (SELECT 1 FROM users WHERE id = ? AND active = 1)",
    )
    .bind(serialized)
    .bind(i64::from(result.counter()))
    .bind(i64::from(result.backup_eligible()))
    .bind(i64::from(result.backup_state()))
    .bind(now)
    .bind(passkey_id)
    .bind(user_id)
    .bind(user_id)
    .execute(&mut **tx)
    .await?;
    Ok(updated.rows_affected() == 1)
}

/// Backup flags as stored inside a serialized credential (`webauthn-rs` keeps them there).
fn flag(serialized: &str, name: &str) -> i64 {
    fn find(value: &serde_json::Value, name: &str) -> Option<bool> {
        match value {
            serde_json::Value::Object(map) => map
                .get(name)
                .and_then(serde_json::Value::as_bool)
                .or_else(|| map.values().find_map(|inner| find(inner, name))),
            _ => None,
        }
    }
    serde_json::from_str::<serde_json::Value>(serialized)
        .ok()
        .and_then(|value| find(&value, name))
        .map_or(0, i64::from)
}

async fn load_passkeys(
    pool: &SqlitePool,
    user_id: i64,
) -> Result<Vec<(i64, Passkey)>, PasskeyError> {
    let rows: Vec<(i64, String)> =
        sqlx::query_as("SELECT id, passkey FROM passkeys WHERE user_id = ? ORDER BY id")
            .bind(user_id)
            .fetch_all(pool)
            .await?;
    Ok(rows
        .into_iter()
        .filter_map(|(id, serialized)| {
            serde_json::from_str(&serialized)
                .ok()
                .map(|passkey| (id, passkey))
        })
        .collect())
}

type ViewRow = (i64, String, i64, Option<i64>, String, i64, i64);

fn view(row: ViewRow) -> PasskeyView {
    let (id, nickname, created_at, last_used_at, transports, eligible, state) = row;
    PasskeyView {
        id,
        nickname,
        created_at,
        last_used_at,
        transports: serde_json::from_str(&transports).unwrap_or_default(),
        backup_eligible: eligible != 0,
        backup_state: state != 0,
    }
}

/// The passkeys of an account, oldest first.
pub(crate) async fn list(pool: &SqlitePool, user_id: i64) -> Result<Vec<PasskeyView>, sqlx::Error> {
    Ok(sqlx::query_as::<_, ViewRow>(
        "SELECT id, nickname, created_at, last_used_at, transports, backup_eligible, \
         backup_state FROM passkeys WHERE user_id = ? ORDER BY created_at, id",
    )
    .bind(user_id)
    .fetch_all(pool)
    .await?
    .into_iter()
    .map(view)
    .collect())
}

async fn load_view(
    pool: &SqlitePool,
    user_id: i64,
    id: i64,
) -> Result<Option<PasskeyView>, sqlx::Error> {
    Ok(sqlx::query_as::<_, ViewRow>(
        "SELECT id, nickname, created_at, last_used_at, transports, backup_eligible, \
         backup_state FROM passkeys WHERE id = ? AND user_id = ?",
    )
    .bind(id)
    .bind(user_id)
    .fetch_optional(pool)
    .await?
    .map(view))
}

/// Rename one of the account's own passkeys.
pub(crate) async fn rename(
    pool: &SqlitePool,
    user_id: i64,
    id: i64,
    nickname: &str,
) -> Result<PasskeyView, PasskeyError> {
    let updated = sqlx::query("UPDATE passkeys SET nickname = ? WHERE id = ? AND user_id = ?")
        .bind(nickname)
        .bind(id)
        .bind(user_id)
        .execute(pool)
        .await?;
    if updated.rows_affected() != 1 {
        return Err(PasskeyError::NotFound);
    }
    load_view(pool, user_id, id)
        .await?
        .ok_or(PasskeyError::NotFound)
}

/// Remove one of the account's own passkeys; consumes recent authentication.
pub(crate) async fn remove(
    pool: &SqlitePool,
    user_id: i64,
    id: i64,
    reauth: RecentAuth<'_>,
    now: i64,
) -> Result<(), PasskeyError> {
    let mut tx = crate::db::begin_write(pool).await?;
    oidc_identity_service::consume_recent_auth_tx(
        &mut tx,
        reauth,
        user_id,
        user_id,
        RecentAction::ManagePasskeys,
        now,
    )
    .await?;
    let deleted = sqlx::query("DELETE FROM passkeys WHERE id = ? AND user_id = ?")
        .bind(id)
        .bind(user_id)
        .execute(&mut *tx)
        .await?;
    if deleted.rows_affected() != 1 {
        return Err(PasskeyError::NotFound);
    }
    record_event_tx(&mut tx, "passkey_removed", user_id, user_id, now).await?;
    tx.commit().await?;
    Ok(())
}

/// Create a session or API token after a verified passkey sign-in, like the password path does:
/// same table, same purposes, same lifetimes, local origin.
pub(crate) async fn mint_token(
    pool: &SqlitePool,
    user_id: i64,
    name: &str,
    purpose: &str,
    expires_in: Option<i64>,
) -> Result<String, PasskeyError> {
    let token = crate::auth::generate_token().map_err(|()| PasskeyError::Random)?;
    let mut tx = crate::db::begin_write(pool).await?;
    let inserted = sqlx::query(
        "INSERT INTO api_tokens (user_id, name, token_hash, created_at, purpose, expires_at) \
         SELECT id, ?, ?, unixepoch(), ?, \
                CASE WHEN ? IS NULL THEN NULL ELSE unixepoch() + ? END \
         FROM users WHERE id = ? AND active = 1",
    )
    .bind(name)
    .bind(crate::auth::token_hash(token.as_bytes()))
    .bind(purpose)
    .bind(expires_in)
    .bind(expires_in)
    .bind(user_id)
    .execute(&mut *tx)
    .await?;
    if inserted.rows_affected() != 1 {
        return Err(PasskeyError::Verification);
    }
    tx.commit().await?;
    Ok(token)
}

async fn record_event_tx(
    tx: &mut sqlx::Transaction<'_, sqlx::Sqlite>,
    action: &'static str,
    actor_user_id: i64,
    target_user_id: i64,
    now: i64,
) -> Result<(), sqlx::Error> {
    sqlx::query(
        "INSERT INTO auth_audit_events (action, actor_user_id, target_user_id, created_at) \
         VALUES (?, ?, ?, ?)",
    )
    .bind(action)
    .bind(actor_user_id)
    .bind(target_user_id)
    .bind(now)
    .execute(&mut **tx)
    .await?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn hosts(value: &str) -> TrustedHosts {
        TrustedHosts::parse(value).unwrap()
    }

    fn default_rp_id(trusted: &str) -> String {
        PasskeyConfig::from_settings(true, None, &hosts(trusted), true, 7674, None)
            .unwrap()
            .unwrap()
            .rp_id()
            .to_owned()
    }

    #[test]
    fn default_rp_id_for_production_hosts_is_the_configured_primary() {
        // The module lists hostName first, then aliases, then loopback names.
        assert_eq!(
            default_rp_id(
                "wool.anika.blue,stricken.anika.blue,wolle.anika.blue,127.0.0.1,localhost"
            ),
            "wool.anika.blue"
        );
        // Configured order wins over alphabetical order.
        assert_eq!(
            default_rp_id("wolle.example.org,aaa.example.org"),
            "wolle.example.org"
        );
    }

    #[test]
    fn default_rp_id_skips_loopback_and_single_label_names() {
        assert_eq!(
            default_rp_id("localhost,127.0.0.1,wolle.example.org"),
            "wolle.example.org"
        );
        assert_eq!(
            default_rp_id("app.localhost,intranet,wolle.example.org"),
            "wolle.example.org"
        );
    }

    #[test]
    fn default_rp_id_falls_back_to_localhost_for_development() {
        assert_eq!(default_rp_id("127.0.0.1,localhost"), "localhost");
        assert_eq!(default_rp_id("localhost"), "localhost");
    }

    #[test]
    fn hosts_outside_the_rp_id_are_reported_and_not_covered() {
        let config = PasskeyConfig::from_settings(
            true,
            None,
            &hosts("wool.anika.blue,wolle.anika.blue,localhost"),
            true,
            7674,
            None,
        )
        .unwrap()
        .unwrap();
        assert_eq!(config.uncovered_hosts(), ["wolle.anika.blue"]);
        let runtime = PasskeyRuntime::new(&config).unwrap();
        assert!(runtime.covers_host("wool.anika.blue"));
        assert!(runtime.covers_host("WOOL.anika.blue:443"));
        assert!(!runtime.covers_host("wolle.anika.blue"));
        assert!(!runtime.covers_host("evilwool.anika.blue"));
    }

    #[test]
    fn rp_id_is_the_first_dns_name_and_origins_follow_the_cookie_scheme() {
        let config = PasskeyConfig::from_settings(
            true,
            None,
            &hosts("127.0.0.1,wolle.example.org,www.wolle.example.org"),
            true,
            7674,
            None,
        )
        .unwrap()
        .unwrap();
        assert_eq!(config.rp_id(), "wolle.example.org");
        let origins: Vec<_> = config.origins().iter().map(Url::as_str).collect();
        assert_eq!(
            origins,
            [
                "https://wolle.example.org/",
                "https://www.wolle.example.org/"
            ]
        );
    }

    #[test]
    fn local_development_uses_http_and_the_bind_port() {
        let config = PasskeyConfig::from_settings(
            true,
            None,
            &TrustedHosts::loopback_defaults(),
            false,
            7674,
            None,
        )
        .unwrap()
        .unwrap();
        assert_eq!(config.rp_id(), "localhost");
        assert!(config.uncovered_hosts().is_empty());
        let origins: Vec<_> = config.origins().iter().map(Url::as_str).collect();
        assert_eq!(
            origins,
            [
                "http://localhost:7674/",
                "http://127.0.0.1/",
                "http://[::1]/",
            ]
        );
        let runtime = PasskeyRuntime::new(&config).unwrap();
        assert!(runtime.covers_host("localhost:7674"));
        assert!(runtime.covers_host("127.0.0.1"));
        assert!(runtime.covers_host("127.0.0.1:41234"));
        assert!(runtime.covers_host("[::1]:41234"));
        assert!(runtime.covers_host("[::1]"));
        assert!(!runtime.covers_host("127.0.0.2:41234"));
        assert!(!runtime.covers_host("127.0.0.1.attacker.test:41234"));
        assert!(!runtime.covers_host("localhost.attacker.test:41234"));

        let production = PasskeyConfig::from_settings(
            true,
            None,
            &hosts("wolle.example.org,localhost"),
            true,
            7674,
            None,
        )
        .unwrap()
        .unwrap();
        let production_runtime = PasskeyRuntime::new(&production).unwrap();
        assert_eq!(production.origins().len(), 1);
        assert!(production_runtime.covers_host("wolle.example.org:443"));
        assert!(!production_runtime.covers_host("127.0.0.1:41234"));

        let secure_localhost =
            PasskeyConfig::from_settings(true, None, &hosts("localhost"), true, 7674, None)
                .unwrap()
                .unwrap();
        let secure_runtime = PasskeyRuntime::new(&secure_localhost).unwrap();
        assert_eq!(secure_localhost.origins().len(), 1);
        assert!(!secure_runtime.covers_host("127.0.0.1:41234"));

        assert!(
            PasskeyConfig::from_settings(
                true,
                Some("localhost"),
                &hosts("wolle.example.org"),
                false,
                7674,
                None,
            )
            .is_err()
        );
    }

    #[test]
    fn disabled_or_ip_only_instances_have_no_passkeys() {
        assert_eq!(
            PasskeyConfig::from_settings(
                false,
                None,
                &hosts("wolle.example.org"),
                true,
                7674,
                None
            ),
            Ok(None)
        );
        assert_eq!(
            PasskeyConfig::from_settings(true, None, &hosts("127.0.0.1"), true, 7674, None),
            Ok(None)
        );
    }

    #[test]
    fn the_rp_id_override_must_cover_a_trusted_host() {
        let trusted = hosts("wolle.example.org");
        let config =
            PasskeyConfig::from_settings(true, Some("example.org"), &trusted, true, 7674, None)
                .unwrap()
                .unwrap();
        assert_eq!(config.rp_id(), "example.org");
        assert!(
            PasskeyConfig::from_settings(true, Some("other.test"), &trusted, true, 7674, None)
                .is_err()
        );
        assert!(
            PasskeyConfig::from_settings(true, Some("10.0.0.1"), &trusted, true, 7674, None)
                .is_err()
        );
    }

    #[test]
    fn android_origins_are_the_base64url_certificate_hash() {
        let links = AppLinks::parse(
            None,
            Some("6150524c3401c232a8b582e873d692e71ad9c6726317fc6c69889040f60da11f"),
        )
        .unwrap()
        .unwrap();
        let config = PasskeyConfig::from_settings(
            true,
            None,
            &hosts("wolle.example.org"),
            true,
            7674,
            Some(&links),
        )
        .unwrap()
        .unwrap();
        assert_eq!(
            config.origins()[1].as_str(),
            "android:apk-key-hash:YVBSTDQBwjKotYLoc9aS5xrZxnJjF_xsaYiQQPYNoR8"
        );
    }

    #[test]
    fn a_challenge_is_used_once_and_expires() {
        let config =
            PasskeyConfig::from_settings(true, None, &hosts("wolle.example.org"), true, 7674, None)
                .unwrap()
                .unwrap();
        let runtime = PasskeyRuntime::new(&config).unwrap();
        let started = runtime.begin_login(LoginChannel::Web).unwrap();
        assert!(runtime.take(&started.challenge_id).is_ok());
        assert!(matches!(
            runtime.take(&started.challenge_id),
            Err(PasskeyError::InvalidChallenge)
        ));

        let started = runtime.begin_login(LoginChannel::Web).unwrap();
        runtime
            .pending
            .lock()
            .unwrap()
            .get_mut(&started.challenge_id)
            .unwrap()
            .expires = Instant::now() - Duration::from_secs(1);
        assert!(matches!(
            runtime.take(&started.challenge_id),
            Err(PasskeyError::InvalidChallenge)
        ));
    }

    #[test]
    fn pending_challenges_are_bounded() {
        let config =
            PasskeyConfig::from_settings(true, None, &hosts("wolle.example.org"), true, 7674, None)
                .unwrap()
                .unwrap();
        let runtime = PasskeyRuntime::new(&config).unwrap();
        for _ in 0..MAX_PENDING_CHALLENGES + 10 {
            runtime.begin_login(LoginChannel::Native).unwrap();
        }
        assert_eq!(
            runtime.pending.lock().unwrap().len(),
            MAX_PENDING_CHALLENGES
        );
    }
}
