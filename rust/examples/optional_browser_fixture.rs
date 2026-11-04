//! Loopback-only browser fixture with deterministic OCR and archive services.
//!
//! This example is a development companion, not a production server target. It
//! uses the real application router and database while replacing optional
//! external transports with deterministic in-process implementations.

use std::{
    env,
    error::Error,
    net::{IpAddr, Ipv4Addr},
    path::{Path, PathBuf},
    sync::Arc,
    time::Duration,
};

use stricknani_server::{
    ai, archive_jobs,
    config::Config,
    db::{self, AppState},
    image_import::{FetchFuture, ImageFetcher},
    imports::{DnsResolver, FetchError},
    ocr::{OcrError, OcrFuture, OcrLanguage, OcrService, OcrTransport},
    security,
    wayback::{WaybackClient, WaybackError, WaybackFuture, WaybackTransport},
};
use url::Url;

#[derive(Clone, Copy)]
enum FixtureResult {
    Success,
    Failure,
}

struct FixtureOcr(FixtureResult);

impl OcrTransport for FixtureOcr {
    fn recognize<'a>(&'a self, _image_bytes: Vec<u8>, _language: OcrLanguage) -> OcrFuture<'a> {
        Box::pin(async move {
            match self.0 {
                FixtureResult::Success => Ok("Deterministic fixture OCR text".to_owned()),
                FixtureResult::Failure => Err(OcrError::Failed),
            }
        })
    }
}

struct FixtureWayback(FixtureResult);

impl WaybackTransport for FixtureWayback {
    fn save<'a>(
        &'a self,
        _source_url: &'a Url,
    ) -> WaybackFuture<'a, Result<Option<Url>, WaybackError>> {
        Box::pin(async move {
            match self.0 {
                FixtureResult::Success => {
                    Url::parse("https://web.archive.org/web/20260101000000/https://example.com/")
                        .map(Some)
                        .map_err(|_error| WaybackError::Internal)
                }
                FixtureResult::Failure => Err(WaybackError::InvalidArchiveResponse),
            }
        })
    }

    fn availability<'a>(
        &'a self,
        _source_url: &'a Url,
    ) -> WaybackFuture<'a, Result<Option<Url>, WaybackError>> {
        match self.0 {
            FixtureResult::Success => Box::pin(async { Ok(None) }),
            FixtureResult::Failure => Box::pin(async { Err(WaybackError::InvalidArchiveResponse) }),
        }
    }
}

struct FixtureResolver;

impl DnsResolver for FixtureResolver {
    async fn resolve<'a>(&'a self, _hostname: &'a str) -> Result<Vec<IpAddr>, FetchError> {
        Ok(vec![IpAddr::V4(Ipv4Addr::new(93, 184, 216, 34))])
    }
}

struct DenyImageFetcher;

impl ImageFetcher for DenyImageFetcher {
    fn fetch<'a>(&'a self, _url: &'a Url) -> FetchFuture<'a> {
        Box::pin(async { Err(FetchError::Transport) })
    }
}

struct Options {
    worker: PathBuf,
    fixture_runtime: PathBuf,
    ocr: FixtureResult,
    archive: FixtureResult,
}

fn parse_options() -> Result<Options, &'static str> {
    let mut args = env::args_os().skip(1);
    let mut worker = None;
    let mut fixture_runtime = None;
    let mut ocr = FixtureResult::Success;
    let mut archive = FixtureResult::Success;
    while let Some(argument) = args.next() {
        match argument.to_str() {
            Some("--document-worker") if worker.is_none() => {
                worker = args.next().map(PathBuf::from);
            }
            Some("--fixture-runtime") if fixture_runtime.is_none() => {
                fixture_runtime = args.next().map(PathBuf::from);
            }
            Some("--ocr-mode") => {
                ocr = match args
                    .next()
                    .and_then(|value| value.into_string().ok())
                    .as_deref()
                {
                    Some("success") => FixtureResult::Success,
                    Some("failure") => FixtureResult::Failure,
                    _ => return Err("invalid OCR fixture mode"),
                };
            }
            Some("--archive-mode") => {
                archive = match args
                    .next()
                    .and_then(|value| value.into_string().ok())
                    .as_deref()
                {
                    Some("success") => FixtureResult::Success,
                    Some("failure") => FixtureResult::Failure,
                    _ => return Err("invalid archive fixture mode"),
                };
            }
            _ => return Err("invalid fixture arguments"),
        }
    }
    let worker = worker.ok_or("--document-worker is required")?;
    let fixture_runtime = fixture_runtime.ok_or("--fixture-runtime is required")?;
    if !worker.is_file() {
        return Err("document-worker must be an existing file");
    }
    Ok(Options {
        worker,
        fixture_runtime,
        ocr,
        archive,
    })
}

#[tokio::main]
async fn main() {
    if let Err(error) = run().await {
        eprintln!("optional browser fixture: {error}");
        std::process::exit(1);
    }
}

async fn run() -> Result<(), Box<dyn Error>> {
    let options = parse_options().map_err(std::io::Error::other)?;
    let config = Config::from_env()?;
    if !config.bind_address.ip().is_loopback() {
        return Err("optional browser fixture must bind to a loopback address".into());
    }
    validate_fixture_runtime(
        &options.fixture_runtime,
        &config.database_url,
        &config.media_root,
    )?;
    std::fs::create_dir_all(&config.media_root)?;

    let pool = db::connect(&config.database_url).await?;
    let state = AppState::new(pool)
        .with_media_root(config.media_root)
        .with_secure_cookies(config.secure_cookies)
        .with_signup_enabled(config.signup_enabled)
        .with_external_imports(false)
        .with_ocr_enabled(true)
        .with_archive_enabled(true)
        .with_image_fetcher(Arc::new(DenyImageFetcher));

    let ocr = OcrService::with_transport(Arc::new(FixtureOcr(options.ocr)));
    let archive_client = WaybackClient::with_transport_and_resolver(
        true,
        Arc::new(FixtureWayback(options.archive)),
        Arc::new(FixtureResolver),
    );
    let archive_pool = state.pool.clone();
    let archive_worker = tokio::spawn(async move {
        loop {
            if archive_jobs::process_one(&archive_pool, &archive_client)
                .await
                .is_err()
            {
                tracing::warn!(component = "archive_fixture", "archive fixture job failed");
            }
            tokio::time::sleep(Duration::from_millis(250)).await;
        }
    });

    let app = stricknani_server::app_with_services(
        state,
        options.worker,
        false,
        None::<ai::AiClient>,
        Some(ocr),
    );
    let app = security::with_host_validation(app, config.trusted_hosts);
    let listener = tokio::net::TcpListener::bind(config.bind_address).await?;
    let result = axum::serve(
        listener,
        app.into_make_service_with_connect_info::<std::net::SocketAddr>(),
    )
    .with_graceful_shutdown(shutdown_signal())
    .await;
    archive_worker.abort();
    result?;
    Ok(())
}

fn validate_fixture_runtime(
    runtime: &Path,
    database_url: &str,
    media_root: &Path,
) -> Result<(), &'static str> {
    let runtime = runtime
        .canonicalize()
        .map_err(|_| "fixture runtime is unavailable")?;
    let runtime_metadata =
        std::fs::symlink_metadata(&runtime).map_err(|_| "fixture runtime is unavailable")?;
    if !runtime_metadata.file_type().is_dir() || !private_permissions(&runtime_metadata) {
        return Err("fixture runtime must be a private directory");
    }

    let marker = runtime.join(".rust-fixture-owner");
    let marker_metadata =
        std::fs::symlink_metadata(&marker).map_err(|_| "fixture owner marker is unavailable")?;
    if !marker_metadata.file_type().is_file() || !private_permissions(&marker_metadata) {
        return Err("fixture owner marker must be a private regular file");
    }
    let owner = std::fs::read_to_string(marker).map_err(|_| "fixture owner marker is invalid")?;
    if owner.len() != 48 || !owner.bytes().all(|byte| byte.is_ascii_hexdigit()) {
        return Err("fixture owner marker is invalid");
    }

    let expected_database = runtime.join("fixture.sqlite3");
    if database_url != format!("sqlite:{}", expected_database.display()) {
        return Err("database path is outside the fixture runtime");
    }
    let database_metadata = std::fs::symlink_metadata(&expected_database)
        .map_err(|_| "fixture database is unavailable")?;
    if !database_metadata.file_type().is_file() {
        return Err("fixture database must be a regular file");
    }

    let expected_media = runtime.join("media");
    if media_root != expected_media {
        return Err("media path is outside the fixture runtime");
    }
    let media_metadata = std::fs::symlink_metadata(&expected_media)
        .map_err(|_| "fixture media directory is unavailable")?;
    if !media_metadata.file_type().is_dir() || !private_permissions(&media_metadata) {
        return Err("fixture media root must be a private directory");
    }
    Ok(())
}

#[cfg(unix)]
fn private_permissions(metadata: &std::fs::Metadata) -> bool {
    use std::os::unix::fs::PermissionsExt;

    metadata.permissions().mode() & 0o077 == 0
}

#[cfg(not(unix))]
fn private_permissions(_metadata: &std::fs::Metadata) -> bool {
    false
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

#[cfg(test)]
mod tests {
    use std::fs;

    use super::validate_fixture_runtime;

    #[cfg(unix)]
    fn fixture_paths(root: &std::path::Path) -> (std::path::PathBuf, String, std::path::PathBuf) {
        let runtime = root.join("runtime");
        fs::create_dir(&runtime).expect("fixture runtime should be created");
        fs::set_permissions(&runtime, fs::Permissions::from_mode(0o700))
            .expect("fixture runtime should be private");
        fs::write(runtime.join(".rust-fixture-owner"), "a".repeat(48))
            .expect("fixture marker should be written");
        fs::set_permissions(
            runtime.join(".rust-fixture-owner"),
            fs::Permissions::from_mode(0o600),
        )
        .expect("fixture marker should be private");
        let media = runtime.join("media");
        fs::create_dir(&media).expect("fixture media should be created");
        fs::set_permissions(&media, fs::Permissions::from_mode(0o700))
            .expect("fixture media should be private");
        let database = runtime.join("fixture.sqlite3");
        fs::write(&database, b"fixture").expect("fixture db should be created");
        (
            runtime.clone(),
            format!("sqlite:{}", database.display()),
            media,
        )
    }

    #[cfg(unix)]
    use std::os::unix::fs::PermissionsExt;

    #[test]
    #[cfg(unix)]
    fn accepts_only_private_helper_fixture_paths() {
        let temp = tempfile::tempdir().expect("temp directory should be created");
        let (runtime, database, media) = fixture_paths(temp.path());

        assert!(validate_fixture_runtime(&runtime, &database, &media).is_ok());
    }

    #[test]
    #[cfg(unix)]
    fn rejects_unrelated_database_and_media_paths() {
        let temp = tempfile::tempdir().expect("temp directory should be created");
        let (runtime, database, media) = fixture_paths(temp.path());
        let outside_database = format!("sqlite:{}", temp.path().join("real.sqlite3").display());
        let outside_media = temp.path().join("real-media");

        assert_eq!(
            validate_fixture_runtime(&runtime, &outside_database, &media),
            Err("database path is outside the fixture runtime")
        );
        assert_eq!(
            validate_fixture_runtime(&runtime, &database, &outside_media),
            Err("media path is outside the fixture runtime")
        );
    }

    #[test]
    #[cfg(unix)]
    fn rejects_missing_or_permissive_owner_marker() {
        let temp = tempfile::tempdir().expect("temp directory should be created");
        let (runtime, database, media) = fixture_paths(temp.path());
        let marker = runtime.join(".rust-fixture-owner");
        fs::remove_file(&marker).expect("fixture marker should be removed");
        assert_eq!(
            validate_fixture_runtime(&runtime, &database, &media),
            Err("fixture owner marker is unavailable")
        );

        fs::write(&marker, "a".repeat(48)).expect("fixture marker should be restored");
        fs::set_permissions(&marker, fs::Permissions::from_mode(0o644))
            .expect("fixture marker should be made public");
        assert_eq!(
            validate_fixture_runtime(&runtime, &database, &media),
            Err("fixture owner marker must be a private regular file")
        );
    }

    #[test]
    #[cfg(unix)]
    fn rejects_database_symlinks() {
        let temp = tempfile::tempdir().expect("temp directory should be created");
        let (runtime, database, media) = fixture_paths(temp.path());
        let database_path = runtime.join("fixture.sqlite3");
        fs::remove_file(&database_path).expect("fixture db should be removed");
        std::os::unix::fs::symlink(temp.path().join("real.sqlite3"), &database_path)
            .expect("fixture db symlink should be created");

        assert_eq!(
            validate_fixture_runtime(&runtime, &database, &media),
            Err("fixture database must be a regular file")
        );
    }
}
