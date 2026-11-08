//! Local-only mode: the server inside the Android app (T130, `docs/local-only.md`).
//!
//! The app starts the server in its own process, on an ephemeral loopback port,
//! with the database and media in its private storage. One local account is
//! created on the first start; the app talks to it with a PAT that this module
//! mints and keeps in the data directory. Every other app on the device can
//! reach a loopback port, so the router answers only requests that carry a
//! Bearer token (plus the anonymous health and metadata probes): no cookie
//! login, no sign-up, no OIDC and no web UI.

use std::net::{Ipv4Addr, SocketAddr};
use std::path::{Path, PathBuf};
use std::sync::Mutex;

use axum::Router;
use axum::extract::Request;
use axum::http::{Method, StatusCode, header};
use axum::middleware::Next;
use axum::response::{IntoResponse, Response};
use sqlx::SqlitePool;

use crate::db;

/// The local account's address. `.invalid` never resolves, so nothing is ever sent to it.
pub const LOCAL_EMAIL: &str = "me@local.stricknani.invalid";
const TOKEN_FILE: &str = "local-token";
const TOKEN_NAME: &str = "This phone";

/// What the app needs to talk to a started server.
#[derive(Clone, PartialEq, Eq)]
pub struct Started {
    pub port: u16,
    pub token: String,
}

impl std::fmt::Debug for Started {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("Started")
            .field("port", &self.port)
            .finish_non_exhaustive()
    }
}

#[derive(Debug, thiserror::Error)]
pub enum LocalError {
    #[error("cannot create the data directory")]
    DataDir,
    #[error("cannot open the database")]
    Database,
    #[error("cannot create the local account")]
    Account,
    #[error("cannot store the access token")]
    Token,
    #[error("cannot start the server")]
    Runtime,
}

/// The data directory's layout.
fn database_url(data_dir: &Path) -> String {
    format!(
        "sqlite:{}?mode=rwc",
        data_dir.join("stricknani.db").display()
    )
}

fn media_root(data_dir: &Path) -> PathBuf {
    data_dir.join("media")
}

/// Create the local account and its PAT on the first start; afterwards return the
/// stored token as long as it is still valid, else mint a new one.
pub async fn bootstrap(pool: &SqlitePool, data_dir: &Path) -> Result<String, LocalError> {
    let token_path = data_dir.join(TOKEN_FILE);
    if let Ok(token) = std::fs::read_to_string(&token_path) {
        let token = token.trim().to_owned();
        if token_is_valid(pool, &token).await? {
            return Ok(token);
        }
    }
    let user_id = match local_user(pool).await? {
        Some(id) => id,
        None => {
            // Nobody signs in with it: the password only satisfies the account schema.
            let password = crate::auth::generate_token().map_err(|()| LocalError::Account)?;
            db::create_user(pool, LOCAL_EMAIL, password)
                .await
                .map_err(|_| LocalError::Account)?
        }
    };
    let token = crate::auth::generate_token().map_err(|()| LocalError::Token)?;
    sqlx::query(
        "INSERT INTO api_tokens (user_id, name, token_hash, created_at, purpose) \
         VALUES (?, ?, ?, unixepoch(), 'api')",
    )
    .bind(user_id)
    .bind(TOKEN_NAME)
    .bind(crate::auth::token_hash(token.as_bytes()))
    .execute(pool)
    .await
    .map_err(|_| LocalError::Token)?;
    write_private(&token_path, &token).map_err(|_| LocalError::Token)?;
    Ok(token)
}

async fn local_user(pool: &SqlitePool) -> Result<Option<i64>, LocalError> {
    sqlx::query_scalar("SELECT id FROM users WHERE email = ? AND active = 1")
        .bind(LOCAL_EMAIL)
        .fetch_optional(pool)
        .await
        .map_err(|_| LocalError::Database)
}

async fn token_is_valid(pool: &SqlitePool, token: &str) -> Result<bool, LocalError> {
    let found: Option<i64> = sqlx::query_scalar(
        "SELECT api_tokens.id FROM api_tokens JOIN users ON users.id = api_tokens.user_id \
         WHERE api_tokens.token_hash = ? AND api_tokens.purpose = 'api' \
           AND api_tokens.revoked_at IS NULL \
           AND (api_tokens.expires_at IS NULL OR api_tokens.expires_at > unixepoch()) \
           AND users.active = 1 AND users.email = ?",
    )
    .bind(crate::auth::token_hash(token.as_bytes()))
    .bind(LOCAL_EMAIL)
    .fetch_optional(pool)
    .await
    .map_err(|_| LocalError::Database)?;
    Ok(found.is_some())
}

fn write_private(path: &Path, contents: &str) -> std::io::Result<()> {
    use std::io::Write;
    #[cfg(unix)]
    use std::os::unix::fs::OpenOptionsExt;
    let temporary = path.with_extension("tmp");
    let mut options = std::fs::OpenOptions::new();
    options.write(true).create(true).truncate(true);
    #[cfg(unix)]
    options.mode(0o600);
    let mut file = options.open(&temporary)?;
    file.write_all(contents.as_bytes())?;
    file.sync_all()?;
    std::fs::rename(temporary, path)
}

/// Requests without a Bearer token get 401, except the anonymous probes the app
/// makes before it has a session.
async fn require_bearer(request: Request, next: Next) -> Response {
    let anonymous = request.method() == Method::GET
        && matches!(request.uri().path(), "/healthz" | "/api/v2/meta");
    let bearer = request
        .headers()
        .get(header::AUTHORIZATION)
        .and_then(|value| value.to_str().ok())
        .is_some_and(|value| value.starts_with("Bearer "));
    if anonymous || bearer {
        next.run(request).await
    } else {
        StatusCode::UNAUTHORIZED.into_response()
    }
}

/// The local router: the regular app without optional services, Bearer-only.
pub fn router(state: db::AppState) -> Router {
    // Imports are off, so the document worker is never started.
    let worker_path = PathBuf::from("/nonexistent/stricknani-document-worker");
    crate::app_with_local_services(state, worker_path)
        .layer(axum::middleware::from_fn(require_bearer))
}

/// Open the database in [data_dir], bootstrap the account and bind a loopback
/// port ([port] if it is free, else any; 0 for any). The returned future serves
/// until [shutdown] resolves.
pub async fn prepare<S>(
    data_dir: &Path,
    port: u16,
    shutdown: S,
) -> Result<
    (
        Started,
        impl std::future::Future<Output = ()> + Send + use<S>,
    ),
    LocalError,
>
where
    S: std::future::Future<Output = ()> + Send + 'static,
{
    std::fs::create_dir_all(media_root(data_dir)).map_err(|_| LocalError::DataDir)?;
    let pool = db::connect(&database_url(data_dir))
        .await
        .map_err(|_| LocalError::Database)?;
    let token = bootstrap(&pool, data_dir).await?;
    let state = db::AppState::new(pool)
        .with_media_root(media_root(data_dir))
        .with_secure_cookies(false)
        .with_signup_enabled(false)
        .with_archive_enabled(false)
        .with_ocr_enabled(false);
    let cleanup_state = state.clone();
    // The preferred port keeps the app's URLs (and its image cache keys) stable
    // across restarts; another app holding it only costs a new port.
    let listener =
        match tokio::net::TcpListener::bind(SocketAddr::from((Ipv4Addr::LOCALHOST, port))).await {
            Ok(listener) => listener,
            Err(_) => tokio::net::TcpListener::bind(SocketAddr::from((Ipv4Addr::LOCALHOST, 0)))
                .await
                .map_err(|_| LocalError::Runtime)?,
        };
    let port = listener
        .local_addr()
        .map_err(|_| LocalError::Runtime)?
        .port();
    let app = router(state);
    let serve = async move {
        let cleanup = tokio::spawn(async move {
            loop {
                let _ = crate::media::cleanup_pending(&cleanup_state).await;
                tokio::time::sleep(std::time::Duration::from_secs(30)).await;
            }
        });
        let _ = axum::serve(
            listener,
            app.into_make_service_with_connect_info::<SocketAddr>(),
        )
        .with_graceful_shutdown(shutdown)
        .await;
        cleanup.abort();
    };
    Ok((Started { port, token }, serve))
}

struct Running {
    runtime: tokio::runtime::Runtime,
    stop: tokio::sync::oneshot::Sender<()>,
    started: Started,
    data_dir: PathBuf,
}

static RUNNING: Mutex<Option<Running>> = Mutex::new(None);

/// Start the server for [data_dir] (blocking until it listens) or return the
/// running one. A different data directory stops the old server first.
/// [preferred_port] is used when free (0: any port).
pub fn start(data_dir: &Path, preferred_port: u16) -> Result<Started, LocalError> {
    let mut running = RUNNING
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner());
    if let Some(current) = running.as_ref()
        && current.data_dir == data_dir
    {
        return Ok(current.started.clone());
    }
    if let Some(previous) = running.take() {
        shut_down(previous);
    }
    let runtime = tokio::runtime::Builder::new_multi_thread()
        .worker_threads(2)
        .thread_name("stricknani-local")
        .enable_all()
        .build()
        .map_err(|_| LocalError::Runtime)?;
    let (stop, stopped) = tokio::sync::oneshot::channel::<()>();
    let (started, serve) = runtime.block_on(prepare(data_dir, preferred_port, async move {
        let _ = stopped.await;
    }))?;
    runtime.spawn(serve);
    *running = Some(Running {
        runtime,
        stop,
        started: started.clone(),
        data_dir: data_dir.to_path_buf(),
    });
    Ok(started)
}

/// Stop the running server, if any, and wait briefly for open requests.
pub fn stop() {
    let previous = RUNNING
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner())
        .take();
    if let Some(previous) = previous {
        shut_down(previous);
    }
}

fn shut_down(running: Running) {
    let _ = running.stop.send(());
    running
        .runtime
        .shutdown_timeout(std::time::Duration::from_secs(5));
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn bootstrap_creates_the_account_once_and_reuses_the_token() {
        let dir = tempfile::tempdir().unwrap();
        let pool = db::connect(&database_url(dir.path())).await.unwrap();
        let first = bootstrap(&pool, dir.path()).await.unwrap();
        let second = bootstrap(&pool, dir.path()).await.unwrap();
        assert_eq!(first, second);
        let users: i64 = sqlx::query_scalar("SELECT count(*) FROM users")
            .fetch_one(&pool)
            .await
            .unwrap();
        assert_eq!(users, 1);
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            let mode = std::fs::metadata(dir.path().join(TOKEN_FILE))
                .unwrap()
                .permissions()
                .mode();
            assert_eq!(mode & 0o777, 0o600);
        }
    }

    #[tokio::test]
    async fn a_revoked_token_is_replaced() {
        let dir = tempfile::tempdir().unwrap();
        let pool = db::connect(&database_url(dir.path())).await.unwrap();
        let first = bootstrap(&pool, dir.path()).await.unwrap();
        sqlx::query("UPDATE api_tokens SET revoked_at = unixepoch()")
            .execute(&pool)
            .await
            .unwrap();
        let second = bootstrap(&pool, dir.path()).await.unwrap();
        assert_ne!(first, second);
        assert!(token_is_valid(&pool, &second).await.unwrap());
    }

    #[test]
    fn serves_only_bearer_requests_and_keeps_data_across_restarts() {
        let dir = tempfile::tempdir().unwrap();
        let started = start(dir.path(), 0).unwrap();
        assert_eq!(
            start(dir.path(), 0).unwrap(),
            started,
            "a second start reuses it"
        );
        let base = format!("http://127.0.0.1:{}", started.port);
        let client = reqwest::Client::new();
        let runtime = tokio::runtime::Runtime::new().unwrap();
        runtime.block_on(async {
            let health = client.get(format!("{base}/healthz")).send().await.unwrap();
            assert_eq!(health.status(), 200);
            let anonymous = client
                .get(format!("{base}/api/v2/projects"))
                .send()
                .await
                .unwrap();
            assert_eq!(anonymous.status(), 401);
            let login = client
                .post(format!("{base}/api/v2/auth/login"))
                .header("content-type", "application/json")
                .body(serde_json::json!({"email": LOCAL_EMAIL, "password": "x"}).to_string())
                .send()
                .await
                .unwrap();
            assert_eq!(login.status(), 401, "no cookie login without a token");
            let created = client
                .post(format!("{base}/api/v2/projects"))
                .bearer_auth(&started.token)
                .header("content-type", "application/json")
                .body(serde_json::json!({"name": "Local scarf"}).to_string())
                .send()
                .await
                .unwrap();
            assert!(created.status().is_success(), "{}", created.status());
        });
        stop();
        let restarted = start(dir.path(), started.port).unwrap();
        assert_eq!(restarted.token, started.token);
        assert_eq!(restarted.port, started.port, "the preferred port is reused");
        let base = format!("http://127.0.0.1:{}", restarted.port);
        runtime.block_on(async {
            let projects = client
                .get(format!("{base}/api/v2/projects"))
                .bearer_auth(&restarted.token)
                .send()
                .await
                .unwrap()
                .text()
                .await
                .unwrap();
            assert!(projects.contains("Local scarf"), "{projects}");
        });
        stop();
    }
}
