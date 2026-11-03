use std::collections::{HashMap, VecDeque};
use std::net::SocketAddr;
use std::sync::{Arc, Mutex, PoisonError};
use std::time::{Duration, Instant};

use argon2::{Argon2, PasswordHash, PasswordHasher, PasswordVerifier, password_hash::SaltString};
use axum::extract::{ConnectInfo, DefaultBodyLimit, Extension, State};
use axum::http::{HeaderMap, StatusCode};
use axum::response::{IntoResponse, Response};
use axum::routing::post;
use axum::{Json, Router};
use rand::RngCore;
use rand::rngs::OsRng;
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use sqlx::SqlitePool;
use tokio::sync::{OwnedSemaphorePermit, Semaphore};
use zeroize::Zeroize;

use crate::db::AppState;

const RATE_LIMIT_ATTEMPTS: usize = 5;
pub(crate) const RATE_LIMIT_WINDOW: Duration = Duration::from_secs(5 * 60);
const MAX_RATE_LIMIT_KEYS: usize = 4096;
const MAX_PARALLEL_PASSWORD_CHECKS: usize = 4;
pub(crate) const MAX_EMAIL_BYTES: usize = 320;
pub(crate) const MAX_PASSWORD_BYTES: usize = 1024;
pub(crate) const MAX_TOKEN_NAME_BYTES: usize = 255;
const TOKEN_PREFIX: &str = "sna_";
const DUMMY_PASSWORD_HASH: &str =
    "$argon2id$v=19$m=19456,t=2,p=1$c29tZXNhbHRzYWx0$AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";

/// A bounded in-memory limiter for password-token requests.
#[derive(Debug, Default)]
pub struct RateLimiter {
    entries: Mutex<HashMap<String, VecDeque<(u64, Instant)>>>,
    next_attempt_id: Mutex<u64>,
}

impl RateLimiter {
    pub(crate) fn reserve(&self, keys: &[String]) -> Option<u64> {
        let now = Instant::now();
        let mut entries = self.entries.lock().unwrap_or_else(PoisonError::into_inner);
        entries.retain(|_, attempts| {
            attempts.retain(|(_, attempt)| now.duration_since(*attempt) < RATE_LIMIT_WINDOW);
            !attempts.is_empty()
        });

        let limited = keys.iter().any(|key| {
            entries
                .get(key)
                .is_some_and(|attempts| attempts.len() >= RATE_LIMIT_ATTEMPTS)
        }) || entries.len()
            + keys
                .iter()
                .filter(|key| !entries.contains_key(*key))
                .count()
            > MAX_RATE_LIMIT_KEYS;
        if limited {
            return None;
        }

        let mut next_attempt_id = self
            .next_attempt_id
            .lock()
            .unwrap_or_else(PoisonError::into_inner);
        let attempt_id = *next_attempt_id;
        *next_attempt_id = next_attempt_id.wrapping_add(1);
        for key in keys {
            entries
                .entry(key.clone())
                .or_default()
                .push_back((attempt_id, now));
        }
        Some(attempt_id)
    }

    fn refund(&self, keys: &[String], attempt_id: u64) {
        let mut entries = self.entries.lock().unwrap_or_else(PoisonError::into_inner);
        for key in keys {
            if let Some(attempts) = entries.get_mut(key) {
                attempts.retain(|(id, _)| *id != attempt_id);
            }
        }
        entries.retain(|_, attempts| !attempts.is_empty());
    }
}

/// Build the unauthenticated password-token route, to be nested under `/api/v1`.
pub fn router() -> Router<AppState> {
    Router::new()
        .route("/auth/token", post(mint_token))
        .layer(DefaultBodyLimit::max(8 * 1024))
}

#[derive(Debug, thiserror::Error)]
pub enum AuthError {
    #[error("Invalid or missing API token")]
    Unauthorized,
    #[error("database error")]
    Database(#[from] sqlx::Error),
}

impl IntoResponse for AuthError {
    fn into_response(self) -> Response {
        let status = match &self {
            Self::Unauthorized => StatusCode::UNAUTHORIZED,
            Self::Database(_) => StatusCode::INTERNAL_SERVER_ERROR,
        };
        (
            status,
            Json(ErrorResponse {
                detail: self.to_string(),
            }),
        )
            .into_response()
    }
}

/// Resolve a valid bearer PAT to its active user ID.
pub async fn bearer_user(pool: &SqlitePool, headers: &HeaderMap) -> Result<i64, AuthError> {
    let token = bearer_token(headers)?;
    token_user(pool, token, None).await
}

/// Resolve a valid API-purpose bearer PAT, rejecting duplicate authorization headers.
pub(crate) async fn bearer_pat_user(
    pool: &SqlitePool,
    headers: &HeaderMap,
) -> Result<i64, AuthError> {
    if headers
        .get_all(axum::http::header::AUTHORIZATION)
        .iter()
        .count()
        != 1
    {
        return Err(AuthError::Unauthorized);
    }
    let token = bearer_token(headers)?;
    token_user(pool, token, Some("api")).await
}

fn bearer_token(headers: &HeaderMap) -> Result<&str, AuthError> {
    let Some(token) = headers
        .get(axum::http::header::AUTHORIZATION)
        .and_then(|value| value.to_str().ok())
        .and_then(|value| {
            let mut parts = value.split_ascii_whitespace();
            let scheme = parts.next()?;
            let token = parts.next()?;
            (scheme.eq_ignore_ascii_case("bearer") && parts.next().is_none()).then_some(token)
        })
        .filter(|token| !token.is_empty())
    else {
        return Err(AuthError::Unauthorized);
    };

    Ok(token)
}

/// Browser cookies are accepted only by browser/media handlers, never by API v1.
pub(crate) fn session_cookie_name(secure: bool) -> &'static str {
    if secure {
        "__Host-stricknani_session"
    } else {
        "stricknani_session"
    }
}

pub(crate) async fn browser_user(
    pool: &SqlitePool,
    headers: &HeaderMap,
    secure: bool,
) -> Result<i64, AuthError> {
    let token = cookie(headers, session_cookie_name(secure)).ok_or(AuthError::Unauthorized)?;
    token_user(pool, token, Some("browser")).await
}

pub(crate) fn cookie<'a>(headers: &'a HeaderMap, name: &str) -> Option<&'a str> {
    headers
        .get_all(axum::http::header::COOKIE)
        .iter()
        .filter_map(|header| header.to_str().ok())
        .flat_map(|value| value.split(';'))
        .filter_map(|pair| pair.trim().split_once('='))
        .find_map(|(key, value)| (key == name).then_some(value))
}

async fn token_user(
    pool: &SqlitePool,
    token: &str,
    purpose: Option<&str>,
) -> Result<i64, AuthError> {
    let hash = token_hash(token.as_bytes());
    sqlx::query_scalar::<_, i64>(
        "SELECT users.id FROM api_tokens \
         JOIN users ON users.id = api_tokens.user_id \
         WHERE api_tokens.token_hash = ? \
           AND api_tokens.revoked_at IS NULL \
           AND (api_tokens.expires_at IS NULL OR api_tokens.expires_at > unixepoch()) \
           AND (? = 0 OR api_tokens.purpose = ?) \
           AND users.active = 1",
    )
    .bind(hash)
    .bind(purpose.is_some())
    .bind(purpose)
    .fetch_optional(pool)
    .await?
    .ok_or(AuthError::Unauthorized)
}

#[derive(Deserialize)]
struct TokenRequest {
    email: String,
    password: String,
    token_name: Option<String>,
}

#[derive(Serialize)]
struct TokenResponse {
    token: String,
}

#[derive(Debug, Serialize)]
struct ErrorResponse {
    detail: String,
}

async fn mint_token(
    State(state): State<AppState>,
    peer: Option<Extension<ConnectInfo<SocketAddr>>>,
    Json(mut request): Json<TokenRequest>,
) -> Response {
    let password = zeroize::Zeroizing::new(std::mem::take(&mut request.password));
    if request
        .token_name
        .as_ref()
        .is_some_and(|name| name.len() > MAX_TOKEN_NAME_BYTES)
    {
        return (
            StatusCode::UNPROCESSABLE_ENTITY,
            Json(ErrorResponse {
                detail: "Invalid request".to_owned(),
            }),
        )
            .into_response();
    }
    let ip = peer
        .map(|Extension(ConnectInfo(address))| address.ip().to_string())
        .unwrap_or_else(|| "unknown".to_owned());
    let name = request
        .token_name
        .map(|name| name.trim().to_owned())
        .filter(|name| !name.is_empty())
        .unwrap_or_else(|| "Android (password login)".to_owned());
    match mint_password_token(&state, &ip, &request.email, password, &name, "api", None).await {
        Ok(token) => (
            StatusCode::OK,
            [(axum::http::header::CACHE_CONTROL, "no-store")],
            Json(TokenResponse { token: token.token }),
        )
            .into_response(),
        Err(PasswordTokenError::InvalidRequest) => (
            StatusCode::UNPROCESSABLE_ENTITY,
            Json(ErrorResponse {
                detail: "Invalid request".to_owned(),
            }),
        )
            .into_response(),
        Err(PasswordTokenError::RateLimited) => (
            StatusCode::TOO_MANY_REQUESTS,
            [(
                axum::http::header::RETRY_AFTER,
                RATE_LIMIT_WINDOW.as_secs().to_string(),
            )],
            Json(ErrorResponse {
                detail: "Too many attempts. Please try again later.".to_owned(),
            }),
        )
            .into_response(),
        Err(PasswordTokenError::InvalidCredentials) => (
            StatusCode::UNAUTHORIZED,
            Json(ErrorResponse {
                detail: "Invalid email or password".to_owned(),
            }),
        )
            .into_response(),
        Err(PasswordTokenError::Database) => (
            StatusCode::INTERNAL_SERVER_ERROR,
            Json(ErrorResponse {
                detail: "database error".to_owned(),
            }),
        )
            .into_response(),
        Err(PasswordTokenError::Internal) => StatusCode::INTERNAL_SERVER_ERROR.into_response(),
    }
}

pub(crate) struct MintedToken {
    pub(crate) user_id: i64,
    pub(crate) token: String,
}

#[derive(Debug, Eq, PartialEq)]
pub(crate) enum PasswordTokenError {
    InvalidRequest,
    RateLimited,
    InvalidCredentials,
    Database,
    Internal,
}

/// Verify a password and create a purpose-limited token for a browser session.
pub(crate) async fn mint_browser_token(
    state: &AppState,
    ip: &str,
    email: &str,
    password: zeroize::Zeroizing<String>,
    expires_in: i64,
) -> Result<MintedToken, PasswordTokenError> {
    mint_password_token(
        state,
        ip,
        email,
        password,
        "Browser session",
        "browser",
        Some(expires_in),
    )
    .await
}

/// Verify a password and mint an API-purpose PAT without creating a browser session.
pub(crate) async fn mint_api_token(
    state: &AppState,
    ip: &str,
    email: &str,
    password: zeroize::Zeroizing<String>,
    name: &str,
) -> Result<MintedToken, PasswordTokenError> {
    mint_password_token(state, ip, email, password, name, "api", None).await
}

async fn mint_password_token(
    state: &AppState,
    ip: &str,
    raw_email: &str,
    password: zeroize::Zeroizing<String>,
    name: &str,
    purpose: &str,
    expires_in: Option<i64>,
) -> Result<MintedToken, PasswordTokenError> {
    let email = raw_email.trim().to_lowercase();
    if email.len() > MAX_EMAIL_BYTES
        || password.len() > MAX_PASSWORD_BYTES
        || name.len() > MAX_TOKEN_NAME_BYTES
    {
        return Err(PasswordTokenError::InvalidRequest);
    }
    let keys = [rate_key("ip", ip), rate_key("email", &email)];
    let Some(attempt_id) = state.auth_limiter.reserve(&keys) else {
        return Err(PasswordTokenError::RateLimited);
    };

    let user = match sqlx::query_as::<_, (i64, String, i64)>(
        "SELECT id, password_hash, active FROM users WHERE email = ?",
    )
    .bind(&email)
    .fetch_optional(&state.pool)
    .await
    {
        Ok(user) => user,
        Err(_) => {
            state.auth_limiter.refund(&keys, attempt_id);
            return Err(PasswordTokenError::Database);
        }
    };

    let password_hash = user
        .as_ref()
        .filter(|(_, _, active)| *active != 0)
        .map(|(_, password_hash, _)| password_hash.as_str())
        .unwrap_or(DUMMY_PASSWORD_HASH);
    let verification = match verify_password(password, password_hash.to_owned()).await {
        Ok(verification) => verification,
        Err(()) => {
            state.auth_limiter.refund(&keys, attempt_id);
            return Err(PasswordTokenError::Internal);
        }
    };

    let Some((user_id, current_hash, _)) =
        user.filter(|(_, _, active)| *active != 0 && verification.valid)
    else {
        return Err(PasswordTokenError::InvalidCredentials);
    };
    let token = match generate_token() {
        Ok(token) => token,
        Err(()) => {
            state.auth_limiter.refund(&keys, attempt_id);
            return Err(PasswordTokenError::Internal);
        }
    };
    match persist_verified_token(
        &state.pool,
        VerifiedAccount {
            user_id,
            password_hash: current_hash,
            upgraded_hash: verification.upgraded_hash,
        },
        TokenToPersist {
            name,
            purpose,
            expires_in,
            token: &token,
        },
    )
    .await
    {
        Ok(true) => {
            state.auth_limiter.refund(&keys, attempt_id);
            Ok(MintedToken { user_id, token })
        }
        Ok(false) => Err(PasswordTokenError::InvalidCredentials),
        Err(_) => {
            state.auth_limiter.refund(&keys, attempt_id);
            Err(PasswordTokenError::Database)
        }
    }
}

struct VerifiedAccount {
    user_id: i64,
    password_hash: String,
    upgraded_hash: Option<String>,
}

struct TokenToPersist<'a> {
    name: &'a str,
    purpose: &'a str,
    expires_in: Option<i64>,
    token: &'a str,
}

async fn persist_verified_token(
    pool: &SqlitePool,
    account: VerifiedAccount,
    token: TokenToPersist<'_>,
) -> Result<bool, sqlx::Error> {
    let mut transaction = pool.begin().await?;
    let current = sqlx::query(
        "UPDATE users SET password_hash = coalesce(?, password_hash) \
         WHERE id = ? AND active = 1 AND password_hash = ?",
    )
    .bind(account.upgraded_hash)
    .bind(account.user_id)
    .bind(account.password_hash)
    .execute(&mut *transaction)
    .await?;
    if current.rows_affected() != 1 {
        transaction.rollback().await?;
        return Ok(false);
    }

    sqlx::query(
        "INSERT INTO api_tokens (user_id, name, token_hash, created_at, purpose, expires_at) \
         VALUES (?, ?, ?, unixepoch(), ?, CASE WHEN ? IS NULL THEN NULL ELSE unixepoch() + ? END)",
    )
    .bind(account.user_id)
    .bind(token.name)
    .bind(token_hash(token.token.as_bytes()))
    .bind(token.purpose)
    .bind(token.expires_in)
    .bind(token.expires_in)
    .execute(&mut *transaction)
    .await?;
    transaction.commit().await?;
    Ok(true)
}

struct PasswordVerification {
    valid: bool,
    upgraded_hash: Option<String>,
}

async fn verify_password(
    password: zeroize::Zeroizing<String>,
    hash: String,
) -> Result<PasswordVerification, ()> {
    let permit: OwnedSemaphorePermit =
        password_semaphore().acquire_owned().await.map_err(|_| ())?;
    tokio::task::spawn_blocking(move || {
        let _permit = permit;
        if is_bcrypt_hash(&hash) {
            let valid = match bcrypt::verify(password.as_bytes(), &hash) {
                Ok(valid) => valid,
                Err(_) => {
                    verify_dummy_password(password.as_bytes())?;
                    return Ok(PasswordVerification {
                        valid: false,
                        upgraded_hash: None,
                    });
                }
            };
            let upgraded_hash = valid.then(|| {
                let salt = SaltString::generate(&mut OsRng);
                Argon2::default()
                    .hash_password(password.as_bytes(), &salt)
                    .map(|hash| hash.to_string())
                    .map_err(|_| ())
            });
            return Ok(PasswordVerification {
                valid,
                upgraded_hash: upgraded_hash.transpose()?,
            });
        }

        let Ok(parsed) = PasswordHash::new(&hash) else {
            verify_dummy_password(password.as_bytes())?;
            return Ok(PasswordVerification {
                valid: false,
                upgraded_hash: None,
            });
        };
        match Argon2::default().verify_password(password.as_bytes(), &parsed) {
            Ok(()) => Ok(PasswordVerification {
                valid: true,
                upgraded_hash: None,
            }),
            Err(argon2::password_hash::Error::Password) => Ok(PasswordVerification {
                valid: false,
                upgraded_hash: None,
            }),
            Err(argon2::password_hash::Error::Crypto) => Err(()),
            Err(_) => {
                verify_dummy_password(password.as_bytes())?;
                Ok(PasswordVerification {
                    valid: false,
                    upgraded_hash: None,
                })
            }
        }
    })
    .await
    .map_err(|_| ())?
}

fn verify_dummy_password(password: &[u8]) -> Result<(), ()> {
    let parsed = PasswordHash::new(DUMMY_PASSWORD_HASH).map_err(|_| ())?;
    match Argon2::default().verify_password(password, &parsed) {
        Ok(()) | Err(argon2::password_hash::Error::Password) => Ok(()),
        Err(_) => Err(()),
    }
}

fn password_semaphore() -> Arc<Semaphore> {
    static PASSWORD_CHECKS: std::sync::LazyLock<Arc<Semaphore>> =
        std::sync::LazyLock::new(|| Arc::new(Semaphore::new(MAX_PARALLEL_PASSWORD_CHECKS)));
    PASSWORD_CHECKS.clone()
}

/// Verify a current password for browser account management.
pub(crate) async fn verify_password_for_account(
    password: zeroize::Zeroizing<String>,
    hash: String,
) -> Result<bool, ()> {
    verify_password(password, hash)
        .await
        .map(|verification| verification.valid)
}

/// Hash a replacement password under the same bounded blocking-work budget as login checks.
pub(crate) async fn hash_password_for_account(
    password: zeroize::Zeroizing<String>,
) -> Result<String, ()> {
    validate_new_password(&password).map_err(|_| ())?;
    let permit = password_semaphore().acquire_owned().await.map_err(|_| ())?;
    tokio::task::spawn_blocking(move || {
        let _permit = permit;
        let salt = SaltString::generate(&mut OsRng);
        Argon2::default()
            .hash_password(password.as_bytes(), &salt)
            .map(|hash| hash.to_string())
            .map_err(|_| ())
    })
    .await
    .map_err(|_| ())?
}

fn is_bcrypt_hash(hash: &str) -> bool {
    ["$2a$", "$2b$", "$2y$"]
        .iter()
        .any(|prefix| hash.starts_with(prefix))
}

pub(crate) fn generate_token() -> Result<String, ()> {
    let mut token_bytes = [0_u8; 32];
    OsRng.try_fill_bytes(&mut token_bytes).map_err(|_| ())?;
    let token = format!("{TOKEN_PREFIX}{}", hex::encode(token_bytes));
    token_bytes.zeroize();
    Ok(token)
}

pub(crate) fn token_hash(token: &[u8]) -> String {
    hex::encode(Sha256::digest(token))
}

pub(crate) fn rate_key(kind: &str, value: &str) -> String {
    let digest = Sha256::digest(value.as_bytes());
    format!("{kind}:{}", hex::encode(digest))
}

const COMMON_WEAK_PASSWORDS: [&str; 20] = [
    "password",
    "password1",
    "password123",
    "12345678",
    "123456789",
    "1234567890",
    "qwertyui",
    "qwerty123",
    "letmein1",
    "iloveyou1",
    "admin1234",
    "welcome1",
    "welcome123",
    "abcd1234",
    "11111111",
    "00000000",
    "changeme1",
    "trustno1",
    "sunshine1",
    "monkey123",
];

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum NewPasswordError {
    TooShort,
    Common,
}

/// Validate a password for a newly created or changed account.
///
/// Existing password verification intentionally does not call this policy.
pub(crate) fn validate_new_password(password: &str) -> Result<(), NewPasswordError> {
    let normalized = zeroize::Zeroizing::new(password.trim().to_lowercase());
    if COMMON_WEAK_PASSWORDS.contains(&normalized.as_str()) {
        return Err(NewPasswordError::Common);
    }
    if password.chars().count() < 12 {
        return Err(NewPasswordError::TooShort);
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::{
        NewPasswordError, RateLimiter, TokenToPersist, VerifiedAccount, hash_password_for_account,
        persist_verified_token, validate_new_password,
    };

    #[tokio::test]
    async fn stale_verified_account_state_cannot_mint_or_upgrade_a_token() {
        for stale_state in ["disabled", "password-reset"] {
            let pool = crate::db::connect("sqlite::memory:")
                .await
                .expect("in-memory database should migrate");
            let user_id = crate::db::create_user(
                &pool,
                "stale-auth@example.test",
                "a-long-test-password".to_owned(),
            )
            .await
            .expect("test user should be created");
            let verified_hash: String =
                sqlx::query_scalar("SELECT password_hash FROM users WHERE id = ?")
                    .bind(user_id)
                    .fetch_one(&pool)
                    .await
                    .expect("password hash should be readable");
            if stale_state == "disabled" {
                sqlx::query("UPDATE users SET active = 0 WHERE id = ?")
                    .bind(user_id)
                    .execute(&pool)
                    .await
                    .expect("account should be disabled");
            } else {
                sqlx::query("UPDATE users SET password_hash = 'reset-password-hash' WHERE id = ?")
                    .bind(user_id)
                    .execute(&pool)
                    .await
                    .expect("password should be reset");
            }

            assert!(
                !persist_verified_token(
                    &pool,
                    VerifiedAccount {
                        user_id,
                        password_hash: verified_hash,
                        upgraded_hash: Some("would-be-upgraded-hash".to_owned()),
                    },
                    TokenToPersist {
                        name: "test token",
                        purpose: "api",
                        expires_in: None,
                        token: "sna_stale-auth-fixture",
                    },
                )
                .await
                .expect("stale credentials should be a normal rejection")
            );
            let tokens: i64 =
                sqlx::query_scalar("SELECT count(*) FROM api_tokens WHERE user_id = ?")
                    .bind(user_id)
                    .fetch_one(&pool)
                    .await
                    .expect("token count should be readable");
            assert_eq!(tokens, 0, "stale state: {stale_state}");
            let current_hash: String =
                sqlx::query_scalar("SELECT password_hash FROM users WHERE id = ?")
                    .bind(user_id)
                    .fetch_one(&pool)
                    .await
                    .expect("password hash should remain readable");
            assert_ne!(current_hash, "would-be-upgraded-hash");
        }
    }

    #[test]
    fn new_password_policy_rejects_trimmed_case_insensitive_common_passwords() {
        assert_eq!(
            validate_new_password("  PaSsWoRd123  "),
            Err(NewPasswordError::Common)
        );
    }

    #[test]
    fn new_password_policy_keeps_the_native_minimum_length() {
        assert_eq!(
            validate_new_password("short-pass"),
            Err(NewPasswordError::TooShort)
        );
        assert_eq!(validate_new_password("long-enough-password"), Ok(()));
    }

    #[test]
    fn new_password_policy_counts_unicode_characters_not_utf8_bytes() {
        assert_eq!(
            validate_new_password(&"雪".repeat(4)),
            Err(NewPasswordError::TooShort)
        );
        assert_eq!(validate_new_password(&"雪".repeat(12)), Ok(()));
    }

    #[tokio::test]
    async fn new_password_hashing_rejects_common_passwords_before_hashing() {
        let password = zeroize::Zeroizing::new(" Password123 ".to_owned());
        assert!(hash_password_for_account(password).await.is_err());
    }

    #[test]
    fn limiter_reserves_atomically_and_refunds_successful_mints() {
        let limiter = RateLimiter::default();
        let keys = ["ip:peer".to_owned(), "email:digest".to_owned()];
        let mut reservations = (0..5)
            .map(|_| limiter.reserve(&keys).expect("attempt is within limit"))
            .collect::<Vec<_>>();

        assert!(limiter.reserve(&keys).is_none());
        limiter.refund(&keys, reservations.pop().expect("there is a reservation"));
        assert!(limiter.reserve(&keys).is_some());
    }

    #[test]
    fn limiter_state_is_independent_between_app_states() {
        let first = RateLimiter::default();
        let second = RateLimiter::default();
        let keys = ["ip:peer".to_owned()];

        for _ in 0..5 {
            assert!(first.reserve(&keys).is_some());
        }
        assert!(first.reserve(&keys).is_none());
        assert!(second.reserve(&keys).is_some());
    }
}
