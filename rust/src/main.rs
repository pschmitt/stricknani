use std::io::{self, Read};
use std::net::SocketAddr;
use std::process::ExitCode;

use stricknani_server::config::Config;
use stricknani_server::db;

const HELP: &str = "stricknani-server\n\nUSAGE:\n    stricknani-server [serve]\n    stricknani-server create-user EMAIL\n    stricknani-server disable-user EMAIL\n    stricknani-server revoke-token ID\n\ncreate-user reads the password from standard input.\n\nREQUIRED ENVIRONMENT:\n    RUST_DATABASE_URL  SQLite database URL\n    RUST_MEDIA_ROOT    Absolute path for uploaded media\n\nOPTIONAL ENVIRONMENT:\n    BIND_HOST          IP address to listen on (default: 127.0.0.1)\n    BIND_PORT          Port to listen on (default: PORT, then 7674)\n    PORT               Fallback port\n    STATIC_ROOT        Static asset directory\n";

#[tokio::main]
async fn main() -> ExitCode {
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
        _ => Err("unknown command; use --help for usage"),
    }
}

async fn serve() -> Result<(), &'static str> {
    let config = Config::from_env().map_err(|_| "invalid server configuration")?;
    let pool = db::connect(&config.database_url)
        .await
        .map_err(|_| "cannot initialize Rust database")?;
    let state = db::AppState::new(pool).with_media_root(config.media_root);

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
    let result = axum::serve(
        listener,
        stricknani_server::app_with_state(config.static_root, state)
            .into_make_service_with_connect_info::<SocketAddr>(),
    )
    .with_graceful_shutdown(shutdown_signal())
    .await;
    cleanup.abort();
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
    let config = Config::from_env().map_err(|_| "invalid server configuration")?;
    let pool = db::connect(&config.database_url)
        .await
        .map_err(|_| "cannot initialize Rust database")?;

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
    if password.len() > 1024 {
        return Err("password from standard input is too long");
    }
    if password.is_empty() {
        return Err("password from standard input is empty");
    }

    db::create_user(&pool, email, std::mem::take(&mut *password))
        .await
        .map_err(|_| "cannot create user")?;
    println!("user created");
    Ok(())
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
