use std::io::{self, Read};
use std::net::SocketAddr;
use std::path::PathBuf;
use std::process::ExitCode;
use std::sync::Arc;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpStream;
use tokio::time::Duration;

use stricknani_server::config::Config;
use stricknani_server::db;
use stricknani_server::error_reporting::ErrorReportingSettings;
use stricknani_server::importer::{ImportOptions, import_snapshot};
use stricknani_server::operator_avatar;
use stricknani_server::security;

const HELP: &str = "stricknani-server\n\nUSAGE:\n    stricknani-server [serve]\n    stricknani-server health-check\n    stricknani-server create-user EMAIL\n    stricknani-server create-admin EMAIL\n    stricknani-server disable-user EMAIL\n    stricknani-server set-avatar EMAIL PATH\n    stricknani-server clear-avatar EMAIL\n    stricknani-server revoke-token ID\n    stricknani-server import-snapshot --source-db PATH --source-media PATH --destination DIR\n\nhealth-check probes only the local /healthz endpoint with a three-second timeout.\ncreate-user and create-admin read the password from standard input. set-avatar stores the image file at PATH (validated and resized like an app upload) as the avatar of an active user; clear-avatar removes it. import-snapshot imports only a copied, self-contained legacy snapshot.\n\nREQUIRED ENVIRONMENT FOR SERVING AND ACCOUNT COMMANDS:\n    STRICKNANI_DATABASE_URL  SQLite database URL\n    STRICKNANI_MEDIA_ROOT    Absolute path for uploaded media\n\nOPTIONAL ENVIRONMENT:\n    BIND_HOST          IP address to listen on (default: 127.0.0.1)\n    BIND_PORT          Port to listen on (default: PORT, then 7674)\n    PORT               Fallback port\n    WEB_ROOT           Directory of the built web app served under /app/\n    TRUSTED_HOSTS      Comma-separated exact public host names or authorities\n    SESSION_COOKIE_SECURE  Set true for non-loopback listeners behind HTTPS\n    STRICKNANI_SENTRY_DSN_FILE   File holding a Sentry DSN (or STRICKNANI_SENTRY_DSN); error reporting is off without one\n    STRICKNANI_SENTRY_ENVIRONMENT  Sentry environment name (default: production)\n    STRICKNANI_SENTRY_TRACES_SAMPLE_RATE  Performance trace sample rate, 0 to 1 (default: 0)\n    STRICKNANI_PUSH_SUBJECT  Web Push (VAPID) contact, a mailto: or https: URI (default: the project page)\n";

fn main() -> ExitCode {
    if std::env::args_os()
        .nth(1)
        .is_some_and(|arg| arg == "document-worker")
    {
        if std::env::args_os().count() != 2 {
            return ExitCode::FAILURE;
        }
        return match stricknani_server::document_worker::run_stdio() {
            Ok(()) => ExitCode::SUCCESS,
            Err(_) => ExitCode::FAILURE,
        };
    }
    // Initialise Sentry before the async runtime exists, and only for serving.
    // It stays inert unless the operator configured a DSN.
    let _error_reporting = init_error_reporting();
    server_main()
}

fn init_error_reporting() -> Option<sentry::ClientInitGuard> {
    let command = std::env::args_os().nth(1);
    if command.is_some_and(|command| command != "serve") {
        return None;
    }
    // A broken configuration is reported by `serve` itself.
    ErrorReportingSettings::from_env().ok()?.init_sdk()
}

#[tokio::main]
async fn server_main() -> ExitCode {
    use tracing_subscriber::{
        Layer, filter::LevelFilter, layer::SubscriberExt, util::SubscriberInitExt,
    };
    tracing_subscriber::registry()
        .with(
            tracing_subscriber::fmt::layer()
                .json()
                .with_writer(std::io::stderr)
                .with_filter(
                    tracing_subscriber::EnvFilter::try_from_default_env()
                        .unwrap_or_else(|_| tracing_subscriber::EnvFilter::new("info")),
                ),
        )
        // `error!` events become Sentry events only when the SDK is active;
        // everything below ERROR stays local.
        .with(sentry::integrations::tracing::ErrorLayer::new().with_filter(LevelFilter::ERROR))
        .init();
    match run().await {
        Ok(()) => ExitCode::SUCCESS,
        Err(message) => {
            // `run` returns static operator-facing reasons (string literals,
            // never configuration or request data). The event message stays
            // fixed so failures group; the reason goes into a tag so an
            // event says which step failed.
            sentry::with_scope(
                |scope| scope.set_tag("startup.reason", message),
                || sentry::capture_message("server.startup.failed", sentry::Level::Error),
            );
            eprintln!("stricknani-server: {message}");
            ExitCode::FAILURE
        }
    }
}

async fn run() -> Result<(), &'static str> {
    let mut arguments = std::env::args().skip(1);
    let Some(command) = arguments.next() else {
        return serve().await;
    };

    match command.as_str() {
        "--help" | "-h" | "help" => {
            println!("{HELP}");
            Ok(())
        }
        "serve" if arguments.next().is_none() => serve().await,
        "health-check" if arguments.next().is_none() => health_check().await,
        "create-user" => {
            let Some(email) = arguments.next() else {
                return Err("usage: stricknani-server create-user EMAIL");
            };
            if arguments.next().is_some() {
                return Err("usage: stricknani-server create-user EMAIL");
            }
            create_user(&email).await
        }
        "create-admin" => {
            let Some(email) = arguments.next() else {
                return Err("usage: stricknani-server create-admin EMAIL");
            };
            if arguments.next().is_some() {
                return Err("usage: stricknani-server create-admin EMAIL");
            }
            create_admin(&email).await
        }
        "disable-user" => {
            let Some(email) = arguments.next() else {
                return Err("usage: stricknani-server disable-user EMAIL");
            };
            if arguments.next().is_some() {
                return Err("usage: stricknani-server disable-user EMAIL");
            }
            disable_user(&email).await
        }
        "set-avatar" => {
            let (Some(email), Some(path), None) =
                (arguments.next(), arguments.next(), arguments.next())
            else {
                return Err("usage: stricknani-server set-avatar EMAIL PATH");
            };
            set_avatar(&email, &path).await
        }
        "clear-avatar" => {
            let (Some(email), None) = (arguments.next(), arguments.next()) else {
                return Err("usage: stricknani-server clear-avatar EMAIL");
            };
            clear_avatar(&email).await
        }
        "revoke-token" => {
            let Some(id) = arguments.next() else {
                return Err("usage: stricknani-server revoke-token ID");
            };
            if arguments.next().is_some() {
                return Err("usage: stricknani-server revoke-token ID");
            }
            revoke_token(&id).await
        }
        "import-snapshot" => import_snapshot_command(arguments.collect()).await,
        _ => Err("unknown command; use --help for usage"),
    }
}

async fn health_check() -> Result<(), &'static str> {
    let port = std::env::var("BIND_PORT")
        .or_else(|_| std::env::var("PORT"))
        .unwrap_or_else(|_| "7674".to_owned())
        .parse::<u16>()
        .ok()
        .filter(|port| *port != 0)
        .ok_or("invalid health-check port")?;
    let trusted_host = std::env::var("TRUSTED_HOSTS")
        .ok()
        .and_then(|hosts| {
            hosts
                .split(',')
                .map(str::trim)
                .find(|host| !host.is_empty())
                .map(str::to_owned)
        })
        .unwrap_or_else(|| "127.0.0.1".to_owned());
    let host = reqwest::header::HeaderValue::try_from(trusted_host.as_str())
        .map_err(|_| "invalid health-check host")?;
    let host = host.to_str().map_err(|_| "invalid health-check host")?;
    let result = tokio::time::timeout(Duration::from_secs(3), async {
        let mut stream = TcpStream::connect(("127.0.0.1", port)).await?;
        stream
            .write_all(
                format!("GET /healthz HTTP/1.1\r\nHost: {host}\r\nConnection: close\r\n\r\n")
                    .as_bytes(),
            )
            .await?;

        let mut status_line = Vec::with_capacity(64);
        loop {
            let byte = stream.read_u8().await?;
            if status_line.len() == 256 {
                return Err(std::io::Error::new(
                    std::io::ErrorKind::InvalidData,
                    "status line too long",
                ));
            }
            status_line.push(byte);
            if byte == b'\n' {
                break;
            }
        }
        Ok::<_, std::io::Error>(status_line)
    })
    .await
    .map_err(|_| "health check timed out")?
    .map_err(|_| "health endpoint is unavailable")?;
    let status = std::str::from_utf8(&result).map_err(|_| "health endpoint is unavailable")?;
    let mut fields = status.split_ascii_whitespace();
    let protocol = fields.next();
    let code = fields.next();
    if !matches!(protocol, Some("HTTP/1.0" | "HTTP/1.1")) || code != Some("200") {
        return Err("health endpoint returned a non-success status");
    }
    println!("healthy");
    Ok(())
}

async fn import_snapshot_command(arguments: Vec<String>) -> Result<(), &'static str> {
    let (source_db, source_media, destination) = parse_import_options(arguments)?;
    let report = import_snapshot(ImportOptions {
        source_db,
        source_media,
        destination,
    })
    .await
    .map_err(|error| {
        eprintln!("import-snapshot: {error}");
        "snapshot import failed"
    })?;
    let report = serde_json::to_string_pretty(&report)
        .map_err(|_| "snapshot imported, but its aggregate report could not be rendered")?;
    println!("{report}");
    Ok(())
}

fn parse_import_options(
    arguments: Vec<String>,
) -> Result<(PathBuf, PathBuf, PathBuf), &'static str> {
    let mut source_db = None;
    let mut source_media = None;
    let mut destination = None;
    let mut args = arguments.into_iter();
    while let Some(option) = args.next() {
        let value = args
            .next()
            .filter(|value| !value.is_empty())
            .ok_or("usage: stricknani-server import-snapshot --source-db PATH --source-media PATH --destination DIR")?;
        let target = match option.as_str() {
            "--source-db" => &mut source_db,
            "--source-media" => &mut source_media,
            "--destination" => &mut destination,
            _ => return Err("unknown import option; use --help for usage"),
        };
        if target.replace(PathBuf::from(value)).is_some() {
            return Err("duplicate import option; use --help for usage");
        }
    }
    match (source_db, source_media, destination) {
        (Some(source_db), Some(source_media), Some(destination)) => {
            Ok((source_db, source_media, destination))
        }
        _ => Err(
            "usage: stricknani-server import-snapshot --source-db PATH --source-media PATH --destination DIR",
        ),
    }
}

async fn serve() -> Result<(), &'static str> {
    let config = Config::from_env().map_err(|_| "invalid server configuration")?;
    let pool = db::connect(&config.database_url)
        .await
        .map_err(|_| "cannot initialize Rust database")?;
    let enabled_oidc_providers = if config.oidc.is_some() {
        vec!["authelia".to_owned()]
    } else {
        Vec::new()
    };
    stricknani_server::oidc_configuration_service::reconcile(&pool, &enabled_oidc_providers)
        .await
        .map_err(|_| "cannot reconcile OIDC provider configuration")?;
    let state = db::AppState::new(pool)
        .with_media_root(config.media_root)
        .with_secure_cookies(config.secure_cookies)
        .with_signup_enabled(config.signup_enabled)
        .with_archive_enabled(config.archive_enabled)
        .with_ocr_enabled(config.ocr_enabled)
        .with_gravatar(stricknani_server::gravatar::GravatarSettings::enabled(
            config.gravatar_enabled,
        ))
        .with_error_reporting(config.error_reporting.clone());
    let state = match config.oidc {
        Some(provider_config) => {
            let runtime =
                stricknani_server::oidc_http_state::OidcRuntime::from_config(provider_config)
                    .map_err(|_| "cannot initialize OIDC provider configuration")?
                    .with_pat_lifetime_seconds(config.oidc_pat_ttl_seconds)
                    .map_err(|_| "invalid OIDC PAT lifetime")?;
            state.with_oidc(Arc::new(runtime))
        }
        None => state,
    };

    let listener = tokio::net::TcpListener::bind(config.bind_address)
        .await
        .map_err(|_| "cannot bind server address")?;
    let cleanup_state = state.clone();
    let cleanup = tokio::spawn(async move {
        loop {
            if stricknani_server::media::cleanup_pending(&cleanup_state)
                .await
                .is_err()
            {
                sentry::capture_message("media.cleanup.failed", sentry::Level::Error);
                tracing::warn!(
                    component = "media",
                    operation = "cleanup",
                    "media.cleanup.failed"
                );
            }
            tokio::time::sleep(std::time::Duration::from_secs(30)).await;
        }
    });
    // Web Push timer reminders (see stricknani_server::web_push).
    let push_pool = state.pool.clone();
    let push_sender =
        stricknani_server::web_push::Sender::new(std::env::var("STRICKNANI_PUSH_SUBJECT").ok())
            .map_err(|_| "cannot initialize web push")?;
    let push_worker = tokio::spawn(async move {
        loop {
            let now = chrono::Utc::now().timestamp();
            if stricknani_server::web_push::deliver_due(&push_pool, &push_sender, now)
                .await
                .is_err()
            {
                sentry::capture_message("push.reminders.failed", sentry::Level::Error);
                tracing::warn!(component = "push", "push.reminders.failed");
            }
            tokio::time::sleep(std::time::Duration::from_secs(30)).await;
        }
    });
    let archive_worker = config.archive_enabled.then(|| {
        let pool = state.pool.clone();
        tokio::spawn(async move {
            let client = stricknani_server::wayback::WaybackClient::new(true);
            loop {
                if stricknani_server::archive_jobs::process_one(&pool, &client)
                    .await
                    .is_err()
                {
                    sentry::capture_message("archive.job.failed", sentry::Level::Error);
                    tracing::warn!(component = "archive", "archive.job.failed");
                }
                tokio::time::sleep(std::time::Duration::from_secs(30)).await;
            }
        })
    });
    let worker_path = std::env::current_exe().map_err(|_| "cannot locate document worker")?;
    let ai = config
        .ai
        .map(stricknani_server::ai::AiClient::new)
        .transpose()
        .map_err(|_| "cannot initialize AI provider")?;
    let ocr = config
        .ocr_enabled
        .then(stricknani_server::ocr::OcrService::new);
    let app =
        stricknani_server::app_with_services(state, worker_path, config.external_imports, ai, ocr);
    let app = if config.error_reporting.server_reporting() {
        // One Sentry hub per request: the signed-in user's opt-out marker and
        // identity stay scoped to that request.
        let http = sentry::integrations::tower::SentryHttpLayer::new();
        let http = if config.error_reporting.traces_enabled() {
            http.enable_transaction()
        } else {
            http
        };
        app.layer(axum::middleware::from_fn(
            stricknani_server::error_reporting::report_http_errors,
        ))
        .layer(http)
        .layer(sentry::integrations::tower::NewSentryLayer::<
            axum::extract::Request,
        >::new_from_top())
    } else {
        app
    };
    let app = security::with_host_validation(app, config.trusted_hosts.clone());
    let result = axum::serve(
        listener,
        app.into_make_service_with_connect_info::<SocketAddr>(),
    )
    .with_graceful_shutdown(shutdown_signal())
    .await;
    cleanup.abort();
    push_worker.abort();
    if let Some(worker) = archive_worker {
        worker.abort();
    }
    result.map_err(|_| "server failed")
}

async fn shutdown_signal() {
    #[cfg(unix)]
    if let Ok(mut terminate) =
        tokio::signal::unix::signal(tokio::signal::unix::SignalKind::terminate())
    {
        tokio::select! {
            _ = tokio::signal::ctrl_c() => {},
            _ = terminate.recv() => {},
        }
        return;
    }
    let _ = tokio::signal::ctrl_c().await;
}

async fn create_user(email: &str) -> Result<(), &'static str> {
    create_account_command(
        email,
        db::AccountRole::Member,
        "user created",
        "cannot create user",
    )
    .await
}

async fn create_admin(email: &str) -> Result<(), &'static str> {
    create_account_command(
        email,
        db::AccountRole::Admin,
        "admin created",
        "cannot create admin",
    )
    .await
}

async fn create_account_command(
    email: &str,
    role: db::AccountRole,
    success_message: &'static str,
    failure_message: &'static str,
) -> Result<(), &'static str> {
    let config = Config::from_env().map_err(|_| "invalid server configuration")?;
    let pool = db::connect(&config.database_url)
        .await
        .map_err(|_| "cannot initialize Rust database")?;
    let mut password = read_account_password()?;
    db::create_account(&pool, email, std::mem::take(&mut *password), role)
        .await
        .map_err(|_| failure_message)?;
    println!("{success_message}");
    Ok(())
}

fn read_account_password() -> Result<zeroize::Zeroizing<String>, &'static str> {
    let mut password = zeroize::Zeroizing::new(String::new());
    io::stdin()
        .take(1027)
        .read_to_string(&mut password)
        .map_err(|_| "cannot read password from standard input")?;
    if password.ends_with('\n') {
        password.pop();
        if password.ends_with('\r') {
            password.pop();
        }
    }
    if password.len() < 12 {
        return Err("password from standard input must be at least 12 bytes");
    }
    if password.len() > 1024 {
        return Err("password from standard input is too long");
    }
    Ok(password)
}

async fn disable_user(email: &str) -> Result<(), &'static str> {
    let config = Config::from_env().map_err(|_| "invalid server configuration")?;
    let pool = db::connect(&config.database_url)
        .await
        .map_err(|_| "cannot initialize Rust database")?;
    let normalized_email = email.trim().to_lowercase();
    let result = sqlx::query("UPDATE users SET active = 0 WHERE email = ?")
        .bind(normalized_email)
        .execute(&pool)
        .await
        .map_err(|_| "cannot disable user")?;
    if result.rows_affected() == 0 {
        return Err("cannot disable user");
    }

    println!("user disabled");
    Ok(())
}

async fn set_avatar(email: &str, path: &str) -> Result<(), &'static str> {
    const MAX_AVATAR_FILE_BYTES: u64 = 25 * 1024 * 1024;
    let config = Config::from_env().map_err(|_| "invalid server configuration")?;
    let pool = db::connect(&config.database_url)
        .await
        .map_err(|_| "cannot initialize Rust database")?;
    let file = std::fs::File::open(path).map_err(|_| "cannot read avatar file")?;
    let mut bytes = Vec::new();
    file.take(MAX_AVATAR_FILE_BYTES + 1)
        .read_to_end(&mut bytes)
        .map_err(|_| "cannot read avatar file")?;
    if bytes.len() as u64 > MAX_AVATAR_FILE_BYTES {
        return Err("avatar file is too large");
    }
    let original_filename = std::path::Path::new(path).file_name().map_or_else(
        || "avatar".to_owned(),
        |name| name.to_string_lossy().into_owned(),
    );
    operator_avatar::set_avatar(&pool, &config.media_root, email, bytes, original_filename).await?;
    println!("avatar set");
    Ok(())
}

async fn clear_avatar(email: &str) -> Result<(), &'static str> {
    let config = Config::from_env().map_err(|_| "invalid server configuration")?;
    let pool = db::connect(&config.database_url)
        .await
        .map_err(|_| "cannot initialize Rust database")?;
    operator_avatar::clear_avatar(&pool, email).await?;
    println!("avatar cleared");
    Ok(())
}

async fn revoke_token(id: &str) -> Result<(), &'static str> {
    let id = id
        .parse::<i64>()
        .ok()
        .filter(|id| *id > 0)
        .ok_or("token ID must be a positive integer")?;
    let config = Config::from_env().map_err(|_| "invalid server configuration")?;
    let pool = db::connect(&config.database_url)
        .await
        .map_err(|_| "cannot initialize Rust database")?;
    let result = sqlx::query("UPDATE api_tokens SET revoked_at = unixepoch() WHERE id = ?")
        .bind(id)
        .execute(&pool)
        .await
        .map_err(|_| "cannot revoke token")?;
    if result.rows_affected() == 0 {
        return Err("cannot revoke token");
    }

    println!("token revoked");
    Ok(())
}
