//! Rust-owned SQLite storage. Never open the Python application's live database.
//!
//! # Concurrency model
//!
//! File-backed databases run in WAL mode (`synchronous = NORMAL`) behind a
//! small connection pool, so readers never wait for a writer. SQLite still
//! allows a single writer at a time: every write transaction must be started
//! with [`begin_write`] (`BEGIN IMMEDIATE`), which takes the write lock up
//! front and waits for it under the busy timeout. A deferred transaction that
//! reads first and then writes can instead fail at once with `SQLITE_BUSY`
//! when another connection committed in between, because the busy handler is
//! not consulted for a read-to-write lock upgrade. Single-statement writes
//! outside a transaction are fine; they wait under the busy timeout.
//!
//! In-memory databases (`sqlite::memory:`) give every connection its own empty
//! database, so they keep exactly one connection and no WAL.
//!
//! `PRAGMA journal_mode = WAL` persists in the database file: an existing
//! rollback-journal database switches on its first open by this version.
//! With WAL the live database consists of the main file plus `-wal` and
//! `-shm`, so backups must use `sqlite3 .backup` (or `VACUUM INTO`), never a
//! raw copy of the main file.
use sqlx::{
    Executor, Sqlite, SqlitePool, Transaction,
    sqlite::{SqliteConnectOptions, SqliteJournalMode, SqlitePoolOptions, SqliteSynchronous},
};
use std::{str::FromStr, sync::Arc, time::Duration};

#[derive(Clone)]
pub struct AppState {
    pub pool: SqlitePool,
    pub build_id: String,
    pub media_root: Option<std::path::PathBuf>,
    pub secure_cookies: bool,
    pub signup_enabled: bool,
    pub archive_enabled: bool,
    pub ocr_enabled: bool,
    pub gravatar: crate::gravatar::GravatarSettings,
    pub error_reporting: crate::error_reporting::ErrorReportingSettings,
    pub auth_limiter: Arc<crate::auth::RateLimiter>,
    pub external_imports: bool,
    pub image_fetcher: Arc<dyn crate::image_import::ImageFetcher>,
    pub oidc: Option<Arc<crate::oidc_http_state::OidcRuntime>>,
    /// The passkey (WebAuthn) relying party; passkeys are unavailable without it.
    pub passkeys: Option<Arc<crate::passkeys::PasskeyRuntime>>,
}

impl AppState {
    pub fn with_ocr_enabled(mut self, enabled: bool) -> Self {
        self.ocr_enabled = enabled;
        self
    }

    /// Configure the server-side Gravatar fallback (disabled by default here;
    /// the server binary enables it from `GRAVATAR_ENABLED`).
    pub fn with_gravatar(mut self, gravatar: crate::gravatar::GravatarSettings) -> Self {
        self.gravatar = gravatar;
        self
    }

    /// Configure the optional Sentry error reporting (inactive by default).
    pub fn with_error_reporting(
        mut self,
        error_reporting: crate::error_reporting::ErrorReportingSettings,
    ) -> Self {
        self.error_reporting = error_reporting;
        self
    }

    /// Enable durable archive jobs; disabled candidates never enqueue requests.
    pub fn with_archive_enabled(mut self, enabled: bool) -> Self {
        self.archive_enabled = enabled;
        self
    }

    /// Public registration is opt-in; administrator bootstrap remains available.
    pub fn with_signup_enabled(mut self, enabled: bool) -> Self {
        self.signup_enabled = enabled;
        self
    }

    pub fn with_secure_cookies(mut self, secure: bool) -> Self {
        self.secure_cookies = secure;
        self
    }

    pub fn with_media_root(mut self, root: std::path::PathBuf) -> Self {
        self.media_root = Some(root);
        self
    }

    /// Enable server-side URL imports for API project creation.
    pub fn with_external_imports(mut self, enabled: bool) -> Self {
        self.external_imports = enabled;
        self
    }

    /// Replace the URL fetcher, primarily for deterministic tests.
    pub fn with_image_fetcher(
        mut self,
        fetcher: Arc<dyn crate::image_import::ImageFetcher>,
    ) -> Self {
        self.image_fetcher = fetcher;
        self
    }

    /// Enable an explicitly configured OIDC provider for API v2.
    pub fn with_oidc(mut self, runtime: Arc<crate::oidc_http_state::OidcRuntime>) -> Self {
        self.oidc = Some(runtime);
        self
    }

    /// Enable passkey (WebAuthn) sign-in and step-up.
    pub fn with_passkeys(mut self, runtime: Arc<crate::passkeys::PasskeyRuntime>) -> Self {
        self.passkeys = Some(runtime);
        self
    }

    pub fn new(pool: SqlitePool) -> Self {
        Self {
            pool,
            media_root: None,
            secure_cookies: false,
            signup_enabled: false,
            archive_enabled: false,
            ocr_enabled: false,
            gravatar: crate::gravatar::GravatarSettings::default(),
            error_reporting: crate::error_reporting::ErrorReportingSettings::default(),
            build_id: format!("{:032x}", rand::random::<u128>()),
            auth_limiter: Arc::new(crate::auth::RateLimiter::default()),
            external_imports: false,
            image_fetcher: Arc::new(crate::image_import::HttpImageFetcher),
            oidc: None,
            passkeys: None,
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

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
/// Privilege assigned atomically when an account is created.
pub enum AccountRole {
    /// An ordinary account with access only to its own resources.
    Member,
    /// An administrator allowed to manage accounts and inspect audit events.
    Admin,
}

impl AccountRole {
    const fn is_admin(self) -> bool {
        matches!(self, Self::Admin)
    }
}

pub async fn connect(url: &str) -> Result<SqlitePool, DatabaseError> {
    if !url.starts_with("sqlite:") {
        return Err(DatabaseError::InvalidUrl);
    }
    let options = SqliteConnectOptions::from_str(url).map_err(|_| DatabaseError::InvalidUrl)?;
    connect_options(options, url_is_in_memory(url)).await
}

/// Open a database that is later published as a single file (the snapshot
/// importer's staging database): one connection, rollback journal, so no
/// `-wal` sidecar can be left behind. The server enables WAL on first open.
pub(crate) async fn connect_file(path: &std::path::Path) -> Result<SqlitePool, DatabaseError> {
    let options = SqliteConnectOptions::new()
        .filename(path)
        .create_if_missing(true)
        .foreign_keys(true)
        .busy_timeout(BUSY_TIMEOUT);
    let pool = SqlitePoolOptions::new()
        .max_connections(1)
        .connect_with(options)
        .await
        .map_err(DatabaseError::Connect)?;
    prepare(pool).await
}

const BUSY_TIMEOUT: Duration = Duration::from_secs(10);
const MAX_CONNECTIONS: u32 = 4;

/// Start a write transaction with `BEGIN IMMEDIATE`.
///
/// Use it for every transaction that writes, even if it reads first: it takes
/// the SQLite write lock up front, so concurrent writers queue under the busy
/// timeout instead of failing with `SQLITE_BUSY` on a lock upgrade.
pub async fn begin_write(pool: &SqlitePool) -> Result<Transaction<'static, Sqlite>, sqlx::Error> {
    pool.begin_with("BEGIN IMMEDIATE").await
}

/// Whether a database URL names a private, per-connection database:
/// `sqlite::memory:`, `sqlite://:memory:`, or any `mode=memory` URI. sqlx
/// rewrites `:memory:` into a shared-cache `file:sqlx-in-memory-N` name and
/// consumes the `mode=memory` query, so the URL is checked before parsing.
fn url_is_in_memory(url: &str) -> bool {
    url.contains(":memory:") || url.contains("mode=memory")
}

/// The same check on already parsed options, for the rewritten name and an
/// empty (temporary) filename.
fn options_are_in_memory(options: &SqliteConnectOptions) -> bool {
    let name = options.get_filename().to_string_lossy();
    name.is_empty()
        || name.contains(":memory:")
        || name.contains("mode=memory")
        || name.starts_with("file:sqlx-in-memory-")
}

async fn connect_options(
    options: SqliteConnectOptions,
    in_memory_url: bool,
) -> Result<SqlitePool, DatabaseError> {
    let in_memory = in_memory_url || options_are_in_memory(&options);
    let options = options
        .create_if_missing(true)
        .foreign_keys(true)
        .busy_timeout(BUSY_TIMEOUT);
    let (pool_options, options) = if in_memory {
        // Every in-memory connection would get its own empty database.
        (SqlitePoolOptions::new().max_connections(1), options)
    } else {
        (
            SqlitePoolOptions::new()
                .max_connections(MAX_CONNECTIONS)
                .min_connections(1)
                .idle_timeout(Some(Duration::from_secs(600))),
            options
                .journal_mode(SqliteJournalMode::Wal)
                .synchronous(SqliteSynchronous::Normal),
        )
    };
    let pool = pool_options
        .connect_with(options)
        .await
        .map_err(DatabaseError::Connect)?;
    prepare(pool).await
}

/// Check ownership of the database and run the migrations.
async fn prepare(pool: SqlitePool) -> Result<SqlitePool, DatabaseError> {
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
        // The marker written by migration 0001. It keeps its historical value:
        // applied migrations are checksummed, so it cannot be renamed in place.
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
    create_account(pool, email, password, AccountRole::Member).await
}

/// Create an account with its role written atomically with the account row.
pub async fn create_account(
    pool: &SqlitePool,
    email: &str,
    password: String,
    role: AccountRole,
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
    crate::auth::validate_new_password(&password).map_err(|_| DatabaseError::InvalidAccount)?;
    let password_hash = crate::auth::hash_password_for_account(password)
        .await
        .map_err(|_| DatabaseError::Password)?;
    insert_prehashed_account(pool, &email, &password_hash, role).await
}

/// Insert a validated account hash using the caller's existing transaction.
/// This is crate-private so only typed account services can supply hashes.
pub(crate) async fn insert_prehashed_account<'e, E>(
    executor: E,
    email: &str,
    password_hash: &str,
    role: AccountRole,
) -> Result<i64, DatabaseError>
where
    E: Executor<'e, Database = sqlx::Sqlite>,
{
    let email = email.trim().to_lowercase();
    if email.len() > 320 || !email.contains('@') || email.contains(char::is_whitespace) {
        return Err(DatabaseError::InvalidAccount);
    }
    let result = sqlx::query("INSERT INTO users (email, password_hash, is_admin) VALUES (?, ?, ?)")
        .bind(email)
        .bind(password_hash)
        .bind(role.is_admin())
        .execute(executor)
        .await
        .map_err(DatabaseError::Account)?;
    Ok(result.last_insert_rowid())
}
