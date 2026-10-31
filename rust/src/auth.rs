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

use crate::db::AppState;

const RATE_LIMIT_ATTEMPTS: usize = 5;
const RATE_LIMIT_WINDOW: Duration = Duration::from_secs(5 * 60);
const MAX_RATE_LIMIT_KEYS: usize = 4096;
const MAX_PARALLEL_PASSWORD_CHECKS: usize = 4;
const MAX_EMAIL_BYTES: usize = 320;
const MAX_PASSWORD_BYTES: usize = 1024;
const MAX_TOKEN_NAME_BYTES: usize = 255;
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
    fn reserve(&self, keys: &[String]) -> Option<u64> {
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

    let hash = token_hash(token.as_bytes());
    sqlx::query_scalar::<_, i64>(
        "SELECT users.id FROM api_tokens \
         JOIN users ON users.id = api_tokens.user_id \
         WHERE api_tokens.token_hash = ? \
           AND api_tokens.revoked_at IS NULL \
           AND (api_tokens.expires_at IS NULL OR api_tokens.expires_at > unixepoch()) \
           AND users.active = 1",
    )
    .bind(hash)
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
    let email = request.email.trim().to_lowercase();
    if email.len() > MAX_EMAIL_BYTES
        || password.len() > MAX_PASSWORD_BYTES
        || request
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
    let keys = [rate_key("ip", &ip), rate_key("email", &email)];

    let Some(attempt_id) = state.auth_limiter.reserve(&keys) else {
        return (
            StatusCode::TOO_MANY_REQUESTS,
            [(
                axum::http::header::RETRY_AFTER,
                RATE_LIMIT_WINDOW.as_secs().to_string(),
            )],
            Json(ErrorResponse {
                detail: "Too many attempts. Please try again later.".to_owned(),
            }),
        )
            .into_response();
    };

    let query = sqlx::query_as::<_, (i64, String, i64)>(
        "SELECT id, password_hash, active FROM users WHERE email = ?",
    )
    .bind(&email)
    .fetch_optional(&state.pool)
    .await;

    let user = match query {
        Ok(user) => user,
        Err(error) => {
            state.auth_limiter.refund(&keys, attempt_id);
            return AuthError::Database(error).into_response();
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
            return StatusCode::INTERNAL_SERVER_ERROR.into_response();
        }
    };

    let Some((user_id, current_hash, _)) =
        user.filter(|(_, _, active)| *active != 0 && verification.valid)
    else {
        return (
            StatusCode::UNAUTHORIZED,
            Json(ErrorResponse {
                detail: "Invalid email or password".to_owned(),
            }),
        )
            .into_response();
    };

    if let Some(upgraded_hash) = verification.upgraded_hash {
        let upgrade =
            sqlx::query("UPDATE users SET password_hash = ? WHERE id = ? AND password_hash = ?")
                .bind(upgraded_hash)
                .bind(user_id)
                .bind(current_hash)
                .execute(&state.pool)
                .await;
        match upgrade {
            Ok(result) if result.rows_affected() == 1 => {}
            Ok(_) => {
                return (
                    StatusCode::UNAUTHORIZED,
                    Json(ErrorResponse {
                        detail: "Invalid email or password".to_owned(),
                    }),
                )
                    .into_response();
            }
            Err(error) => {
                state.auth_limiter.refund(&keys, attempt_id);
                return AuthError::Database(error).into_response();
            }
        }
    }

    let mut token_bytes = [0_u8; 32];
    OsRng.fill_bytes(&mut token_bytes);
    let token = format!("{TOKEN_PREFIX}{}", hex::encode(token_bytes));
    let hash = token_hash(token.as_bytes());
    let name = request
        .token_name
        .map(|name| name.trim().to_owned())
        .filter(|name| !name.is_empty())
        .unwrap_or_else(|| "Android (password login)".to_owned());

    match sqlx::query(
        "INSERT INTO api_tokens (user_id, name, token_hash, created_at) \
         VALUES (?, ?, ?, unixepoch())",
    )
    .bind(user_id)
    .bind(name)
    .bind(hash)
    .execute(&state.pool)
    .await
    {
        Ok(_) => {
            state.auth_limiter.refund(&keys, attempt_id);
            (
                StatusCode::OK,
                [(axum::http::header::CACHE_CONTROL, "no-store")],
                Json(TokenResponse { token }),
            )
                .into_response()
        }
        Err(error) => {
            state.auth_limiter.refund(&keys, attempt_id);
            AuthError::Database(error).into_response()
        }
    }
}

struct PasswordVerification {
    valid: bool,
    upgraded_hash: Option<String>,
}

async fn verify_password(
    password: zeroize::Zeroizing<String>,
    hash: String,
) -> Result<PasswordVerification, ()> {
    static PASSWORD_CHECKS: std::sync::LazyLock<Arc<Semaphore>> =
        std::sync::LazyLock::new(|| Arc::new(Semaphore::new(MAX_PARALLEL_PASSWORD_CHECKS)));

    let permit: OwnedSemaphorePermit = PASSWORD_CHECKS
        .clone()
        .acquire_owned()
        .await
        .map_err(|_| ())?;
    tokio::task::spawn_blocking(move || {
        let _permit = permit;
        if is_bcrypt_hash(&hash) {
            let valid = bcrypt::verify(password.as_bytes(), &hash).map_err(|_| ())?;
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

        let parsed = PasswordHash::new(&hash).map_err(|_| ())?;
        Ok(PasswordVerification {
            valid: Argon2::default()
                .verify_password(password.as_bytes(), &parsed)
                .is_ok(),
            upgraded_hash: None,
        })
    })
    .await
    .map_err(|_| ())?
}

fn is_bcrypt_hash(hash: &str) -> bool {
    ["$2a$", "$2b$", "$2y$"]
        .iter()
        .any(|prefix| hash.starts_with(prefix))
}

fn token_hash(token: &[u8]) -> String {
    hex::encode(Sha256::digest(token))
}

fn rate_key(kind: &str, value: &str) -> String {
    let digest = Sha256::digest(value.as_bytes());
    format!("{kind}:{}", hex::encode(digest))
}

#[cfg(test)]
mod tests {
    use super::RateLimiter;

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
