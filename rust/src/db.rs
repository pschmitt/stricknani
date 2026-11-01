//! Rust-owned SQLite storage. Never open the Python application's live database.
use argon2::{Argon2, PasswordHasher, password_hash::SaltString};
use rand::rngs::OsRng;
use sqlx::{
    SqlitePool,
    sqlite::{SqliteConnectOptions, SqlitePoolOptions},
};
use std::{str::FromStr, sync::Arc, time::Duration};

#[derive(Clone)]
pub struct AppState {
    pub pool: SqlitePool,
    pub build_id: String,
    pub media_root: Option<std::path::PathBuf>,
    pub secure_cookies: bool,
    pub auth_limiter: Arc<crate::auth::RateLimiter>,
}

impl AppState {
    pub fn with_secure_cookies(mut self, secure: bool) -> Self {
        self.secure_cookies = secure;
        self
    }

    pub fn with_media_root(mut self, root: std::path::PathBuf) -> Self {
        self.media_root = Some(root);
        self
    }

    pub fn new(pool: SqlitePool) -> Self {
        Self {
            pool,
            media_root: None,
            secure_cookies: false,
            build_id: format!("{:032x}", rand::random::<u128>()),
            auth_limiter: Arc::new(crate::auth::RateLimiter::default()),
        }
    }
}

#[derive(Debug, thiserror::Error)]
pub enum DatabaseError {
    #[error("invalid Rust database URL")]
    InvalidUrl,
    #[error("database is not an initialized Rust database or an empty database")]
    ForeignDatabase,
    #[error("cannot open Rust database")]
    Connect(#[source] sqlx::Error),
    #[error("Rust database migration failed")]
    Migration(#[source] sqlx::migrate::MigrateError),
    #[error("invalid account email or password")]
    InvalidAccount,
    #[error("account already exists or cannot be stored")]
    Account(#[source] sqlx::Error),
    #[error("password hashing failed")]
    Password,
}

pub async fn connect(url: &str) -> Result<SqlitePool, DatabaseError> {
    if !url.starts_with("sqlite:") {
        return Err(DatabaseError::InvalidUrl);
    }
    let options = SqliteConnectOptions::from_str(url).map_err(|_| DatabaseError::InvalidUrl)?;
    connect_options(options).await
}

pub(crate) async fn connect_file(path: &std::path::Path) -> Result<SqlitePool, DatabaseError> {
    connect_options(SqliteConnectOptions::new().filename(path)).await
}

async fn connect_options(options: SqliteConnectOptions) -> Result<SqlitePool, DatabaseError> {
    let options = options
        .create_if_missing(true)
        .foreign_keys(true)
        .busy_timeout(Duration::from_secs(5));
    // A single connection also makes disposable in-memory databases reliable.
    let pool = SqlitePoolOptions::new()
        .max_connections(1)
        .connect_with(options)
        .await
        .map_err(DatabaseError::Connect)?;
    let tables: Vec<String> = sqlx::query_scalar(
        "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'",
    )
    .fetch_all(&pool)
    .await
    .map_err(DatabaseError::Connect)?;
    if !tables.is_empty()
        && (!tables.iter().any(|name| name == "stricknani_meta")
            || tables.iter().any(|name| name == "alembic_version"))
    {
        pool.close().await;
        return Err(DatabaseError::ForeignDatabase);
    }
    if !tables.is_empty() {
        let owned = sqlx::query_scalar::<_, String>(
            "SELECT app_id FROM stricknani_meta WHERE singleton = 1",
        )
        .fetch_optional(&pool)
        .await
        .map_err(DatabaseError::Connect)?;
        if owned.as_deref() != Some("stricknani-rust") {
            pool.close().await;
            return Err(DatabaseError::ForeignDatabase);
        }
    }
    sqlx::migrate!()
        .run(&pool)
        .await
        .map_err(DatabaseError::Migration)?;
    Ok(pool)
}

pub async fn create_user(
    pool: &SqlitePool,
    email: &str,
    password: String,
) -> Result<i64, DatabaseError> {
    let password = zeroize::Zeroizing::new(password);
    let email = email.trim().to_lowercase();
    if email.len() > 320
        || !email.contains('@')
        || email.contains(char::is_whitespace)
        || password.len() < 12
        || password.len() > 1024
    {
        return Err(DatabaseError::InvalidAccount);
    }
    let password_hash = tokio::task::spawn_blocking(move || {
        let salt = SaltString::generate(&mut OsRng);
        Argon2::default()
            .hash_password(password.as_bytes(), &salt)
            .map(|hash| hash.to_string())
    })
    .await
    .map_err(|_| DatabaseError::Password)?
    .map_err(|_| DatabaseError::Password)?;
    let result = sqlx::query("INSERT INTO users (email, password_hash) VALUES (?, ?)")
        .bind(email)
        .bind(password_hash)
        .execute(pool)
        .await
        .map_err(DatabaseError::Account)?;
    Ok(result.last_insert_rowid())
}
