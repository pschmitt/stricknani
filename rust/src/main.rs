use std::io::{self, Read};
use std::net::SocketAddr;
use std::path::PathBuf;
use std::process::ExitCode;

use stricknani_server::config::Config;
use stricknani_server::db;
use stricknani_server::importer::{ImportOptions, import_snapshot};
use stricknani_server::security;

const HELP: &str = "stricknani-server\n\nUSAGE:\n    stricknani-server [serve]\n    stricknani-server create-user EMAIL\n    stricknani-server create-admin EMAIL\n    stricknani-server disable-user EMAIL\n    stricknani-server revoke-token ID\n    stricknani-server import-snapshot --source-db PATH --source-media PATH --destination DIR\n\ncreate-user and create-admin read the password from standard input. import-snapshot imports only a copied, self-contained legacy snapshot.\n\nREQUIRED ENVIRONMENT FOR SERVING AND ACCOUNT COMMANDS:\n    RUST_DATABASE_URL  SQLite database URL\n    RUST_MEDIA_ROOT    Absolute path for uploaded media\n\nOPTIONAL ENVIRONMENT:\n    BIND_HOST          IP address to listen on (default: 127.0.0.1)\n    BIND_PORT          Port to listen on (default: PORT, then 7674)\n    PORT               Fallback port\n    STATIC_ROOT        Static asset directory\n    TRUSTED_HOSTS      Comma-separated exact public host names or authorities\n    SESSION_COOKIE_SECURE  Set true for non-loopback listeners behind HTTPS\n";

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
    server_main()
}

#[tokio::main]
async fn server_main() -> ExitCode {
    tracing_subscriber::fmt()
        .json()
        .with_writer(std::io::stderr)
        .with_env_filter(
            tracing_subscriber::EnvFilter::try_from_default_env()
                .unwrap_or_else(|_| tracing_subscriber::EnvFilter::new("info")),
        )
        .init();
    match run().await {
        Ok(()) => ExitCode::SUCCESS,
        Err(message) => {
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
    let state = db::AppState::new(pool)
        .with_media_root(config.media_root)
        .with_secure_cookies(config.secure_cookies)
        .with_signup_enabled(config.signup_enabled)
        .with_archive_enabled(config.archive_enabled)
        .with_ocr_enabled(config.ocr_enabled);

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
                tracing::warn!(
                    component = "media",
                    operation = "cleanup",
                    "media.cleanup.failed"
                );
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
    let app = stricknani_server::app_with_services(
        config.static_root,
        state,
        worker_path,
        config.external_imports,
        ai,
        ocr,
    );
    let app = security::with_host_validation(app, config.trusted_hosts.clone());
    let result = axum::serve(
        listener,
        app.into_make_service_with_connect_info::<SocketAddr>(),
    )
    .with_graceful_shutdown(shutdown_signal())
    .await;
    cleanup.abort();
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
