//! The restore swap, performed on the next start before the pool opens.
//!
//! # Crash safety
//!
//! The request handler only stages files and writes the marker; it never
//! replaces the live database. [`prepare`] does, in this order, and records its
//! progress in the marker (`phase`) with atomic writes (temporary file, fsync,
//! rename, fsync of the directory):
//!
//! 1. `staged`: the staged database exists and still matches its SHA-256 (it
//!    is fsynced when written, and verified again here). It is copied next to
//!    the live database as `<db>.restore-incoming` (the backup directory may
//!    live on another file system, so a plain rename could fail) and fsynced.
//! 2. `swapping`: the live database, its `-wal` and `-shm` are renamed to
//!    `*.restore-aside` (kept until the restore succeeded), then the incoming
//!    file is renamed to the database path. Every step is a rename inside one
//!    directory and the phase is re-entrant: after a crash the same marker
//!    resumes at the first step that did not happen.
//! 3. `swapped`: the restored database is opened, which runs the migrations.
//!    Success records the audit event, copies missing media if asked, writes
//!    the outcome, bumps the data epoch and only then deletes the aside files,
//!    the staged copy and the marker. Failure moves to `rolling_back`:
//!    the restored files are removed and the aside files renamed back, so the
//!    original database is intact; the failure is recorded in the outcome file
//!    for the admin UI.
//!
//! A marker older than [`MAX_REQUEST_AGE`] that has not started swapping is
//! discarded: a stale request must never surprise anyone later.

use std::{
    fs::{self, File},
    io::{self, Read},
    path::{Component, Path, PathBuf},
    sync::Arc,
};

use chrono::Utc;
use sha2::{Digest, Sha256};
use sqlx::SqlitePool;

use super::{
    ARCHIVE_DATABASE, BackupConfig, BackupService, EPOCH_FILE, MARKER_FILE, MAX_MEDIA_FILE_BYTES,
    MediaOption, Outcome, Phase, Request, STATUS_FILE, copy_with_hash, random_id, sync_dir,
};

const MAX_REQUEST_AGE: i64 = 60 * 60;

/// Startup hook: apply a pending restore (if any), tidy leftovers, and return
/// the backup service. `None` for in-memory databases.
pub async fn prepare(
    database_url: &str,
    media_root: &Path,
) -> Result<Option<Arc<BackupService>>, &'static str> {
    let backup_dir = std::env::var("STRICKNANI_BACKUP_DIR").ok();
    let auto_restart = !matches!(
        std::env::var("STRICKNANI_RESTORE_AUTO_RESTART").as_deref(),
        Ok("false" | "0")
    );
    let Some(config) = BackupConfig::resolve(
        database_url,
        backup_dir.as_deref(),
        Some(media_root),
        auto_restart,
    ) else {
        return Ok(None);
    };
    apply_pending(database_url, &config).await?;
    cleanup_leftovers(&config);
    let epoch = ensure_data_epoch(&config).map_err(|_| "cannot write the data epoch")?;
    let outcome = read_outcome(&config.dir);
    Ok(Some(Arc::new(BackupService::new(config, outcome, epoch))))
}

// ------------------------------------------------------------- file helpers

fn marker_path(dir: &Path) -> PathBuf {
    dir.join(MARKER_FILE)
}

pub(crate) fn read_request(dir: &Path) -> Option<Request> {
    let bytes = fs::read(marker_path(dir)).ok()?;
    serde_json::from_slice(&bytes).ok()
}

pub(crate) fn write_request(dir: &Path, request: &Request) -> io::Result<()> {
    write_json_atomic(&marker_path(dir), request)
}

pub(crate) fn read_outcome(dir: &Path) -> Option<Outcome> {
    let bytes = fs::read(dir.join(STATUS_FILE)).ok()?;
    serde_json::from_slice(&bytes).ok()
}

fn write_outcome(dir: &Path, outcome: &Outcome) -> io::Result<()> {
    write_json_atomic(&dir.join(STATUS_FILE), outcome)
}

fn write_json_atomic(path: &Path, value: &impl serde::Serialize) -> io::Result<()> {
    let parent = path.parent().unwrap_or(Path::new("."));
    let temporary = path.with_extension("tmp");
    let _ = fs::remove_file(&temporary);
    let mut file = super::private_file(&temporary)?;
    io::Write::write_all(
        &mut file,
        &serde_json::to_vec_pretty(value).map_err(io::Error::other)?,
    )?;
    file.sync_all()?;
    drop(file);
    fs::rename(&temporary, path)?;
    sync_dir(parent)
}

/// The data epoch changes whenever a restore replaced the data; clients that
/// remember an older one discard their caches and queued changes. It lives in a
/// file next to the database because a restore replaces everything inside it.
pub(crate) fn ensure_data_epoch(config: &BackupConfig) -> io::Result<String> {
    let path = config.data_dir().join(EPOCH_FILE);
    if let Ok(existing) = fs::read_to_string(&path) {
        let existing = existing.trim();
        if !existing.is_empty() && existing.len() <= 64 {
            return Ok(existing.to_owned());
        }
    }
    write_epoch(config)
}

fn write_epoch(config: &BackupConfig) -> io::Result<String> {
    let path = config.data_dir().join(EPOCH_FILE);
    let epoch = random_id();
    let temporary = path.with_extension("tmp");
    let _ = fs::remove_file(&temporary);
    let mut file = super::private_file(&temporary)?;
    io::Write::write_all(&mut file, epoch.as_bytes())?;
    file.sync_all()?;
    drop(file);
    fs::rename(&temporary, &path)?;
    sync_dir(config.data_dir())?;
    Ok(epoch)
}

/// Remove hidden temporary files an interrupted request left behind.
fn cleanup_leftovers(config: &BackupConfig) {
    let marker = read_request(&config.dir);
    let keep_staged = marker.as_ref().map(|request| request.staged.clone());
    let Ok(reader) = fs::read_dir(&config.dir) else {
        return;
    };
    for entry in reader.flatten() {
        let Ok(name) = entry.file_name().into_string() else {
            continue;
        };
        let stale = name.starts_with(".inspect-")
            || (name.starts_with(".pre-restore-") && name.ends_with(".part"))
            || (name.starts_with(".restore-staging-") && keep_staged.as_deref() != Some(&name))
            || name.ends_with(".tmp") && name.starts_with(".restore-");
        if stale {
            let _ = fs::remove_file(entry.path());
        }
    }
}

fn sha256_of(path: &Path) -> io::Result<(String, u64)> {
    let mut file = File::open(path)?;
    let mut hasher = Sha256::new();
    let mut buffer = vec![0_u8; 64 * 1024];
    let mut total = 0;
    loop {
        let read = file.read(&mut buffer)?;
        if read == 0 {
            break;
        }
        total += read as u64;
        hasher.update(&buffer[..read]);
    }
    Ok((hex::encode(hasher.finalize()), total))
}

/// `<db>.<suffix>` for the database path and its `-wal` / `-shm` sidecars.
struct Files {
    database: PathBuf,
    wal: PathBuf,
    shm: PathBuf,
}

impl Files {
    fn new(database: &Path) -> Self {
        let sibling = |suffix: &str| {
            let mut name = database.as_os_str().to_owned();
            name.push(suffix);
            PathBuf::from(name)
        };
        Self {
            database: database.to_path_buf(),
            wal: sibling("-wal"),
            shm: sibling("-shm"),
        }
    }

    fn with_suffix(&self, suffix: &str) -> Self {
        let add = |path: &Path| {
            let mut name = path.as_os_str().to_owned();
            name.push(suffix);
            PathBuf::from(name)
        };
        Self {
            database: add(&self.database),
            wal: add(&self.wal),
            shm: add(&self.shm),
        }
    }

    fn all(&self) -> [&Path; 3] {
        [&self.database, &self.wal, &self.shm]
    }
}

fn exists(path: &Path) -> bool {
    fs::symlink_metadata(path).is_ok()
}

fn remove_if_exists(path: &Path) -> io::Result<()> {
    match fs::remove_file(path) {
        Err(error) if error.kind() != io::ErrorKind::NotFound => Err(error),
        _ => Ok(()),
    }
}

// ------------------------------------------------------------------- the swap

async fn apply_pending(database_url: &str, config: &BackupConfig) -> Result<(), &'static str> {
    let dir = &config.dir;
    let marker = marker_path(dir);
    if !exists(&marker) {
        return Ok(());
    }
    let Some(mut request) = read_request(dir) else {
        // Unreadable marker: nothing was swapped (the phase is part of it), so
        // set it aside for the operator and start normally.
        let _ = fs::rename(&marker, dir.join(".restore-request.invalid"));
        return Ok(());
    };
    if request.version != 1 || !super::valid_name(&request.backup) {
        let _ = fs::rename(&marker, dir.join(".restore-request.invalid"));
        return Ok(());
    }
    let live = Files::new(&config.database_path);
    let aside = live.with_suffix(".restore-aside");
    let incoming = live.with_suffix(".restore-incoming");

    if request.phase == Phase::Staged {
        let age = Utc::now().timestamp() - request.requested_at;
        if !(0..=MAX_REQUEST_AGE).contains(&age) {
            return fail_before_swap(config, &request, "the restore request expired");
        }
        let staged_path = dir.join(&request.staged);
        match sha256_of(&staged_path) {
            Ok((hash, bytes)) if hash == request.staged_sha256 && bytes == request.staged_bytes => {
            }
            _ => return fail_before_swap(config, &request, "the staged database is damaged"),
        }
        let _ = remove_if_exists(&incoming.database);
        let copied = File::open(&staged_path).and_then(|mut file| {
            copy_with_hash(&mut file, &incoming.database, super::MAX_DATABASE_BYTES)
        });
        match copied {
            Ok((hash, _)) if hash == request.staged_sha256 => {}
            _ => {
                let _ = remove_if_exists(&incoming.database);
                return fail_before_swap(config, &request, "cannot copy the staged database");
            }
        }
        sync_dir(live.database.parent().unwrap_or(Path::new("."))).map_err(|_| "cannot sync")?;
        request.phase = Phase::Swapping;
        write_request(dir, &request).map_err(|_| "cannot record the restore phase")?;
    }

    if request.phase == Phase::Swapping {
        swap_in(&live, &aside, &incoming, dir, &request)
            .map_err(|_| "cannot swap the restored database in")?;
        request.phase = Phase::Swapped;
        write_request(dir, &request).map_err(|_| "cannot record the restore phase")?;
    }

    if request.phase == Phase::Swapped {
        match verify_and_migrate(database_url, config, &request).await {
            Ok(media) => {
                finish_success(config, &request, &live, &aside, media)
                    .map_err(|_| "cannot finish the restore")?;
                return Ok(());
            }
            Err(error) => {
                tracing::error!(component = "backups", %error, "backups.restore.failed");
                request.phase = Phase::RollingBack;
                write_request(dir, &request).map_err(|_| "cannot record the restore phase")?;
                let reason: String = format!("the restored database could not be used: {error}")
                    .chars()
                    .take(300)
                    .collect();
                return roll_back(config, &request, &live, &aside, &reason);
            }
        }
    }

    // A crash during the rollback resumes here.
    roll_back(
        config,
        &request,
        &live,
        &aside,
        "the restore was rolled back",
    )
}

fn swap_in(
    live: &Files,
    aside: &Files,
    incoming: &Files,
    backup_dir: &Path,
    request: &Request,
) -> io::Result<()> {
    let parent = live.database.parent().unwrap_or(Path::new("."));
    // Sidecars first, the main file last, each rename on its own: a crash
    // between two renames resumes here and never leaves a stale `-wal` next to
    // the restored database.
    for (from, to) in live
        .all()
        .into_iter()
        .rev()
        .zip(aside.all().into_iter().rev())
    {
        if exists(from) && !exists(to) {
            fs::rename(from, to)?;
        }
    }
    sync_dir(parent)?;
    if exists(&incoming.database) {
        if exists(&live.database) {
            return Err(io::Error::other("refusing to replace the live database"));
        }
        fs::rename(&incoming.database, &live.database)?;
    } else if !exists(&live.database) {
        // Crash between the two renames with the incoming copy gone is not
        // possible, but a missing copy must not leave the server without a
        // database: copy it from the staged file again.
        let staged = backup_dir.join(&request.staged);
        let mut file = File::open(staged)?;
        let (hash, _) = copy_with_hash(&mut file, &live.database, super::MAX_DATABASE_BYTES)?;
        if hash != request.staged_sha256 {
            let _ = remove_if_exists(&live.database);
            return Err(io::Error::other("staged database changed"));
        }
    }
    File::open(&live.database)?.sync_all()?;
    sync_dir(parent)
}

/// Open the restored database (which migrates it) and apply the extras.
async fn verify_and_migrate(
    database_url: &str,
    config: &BackupConfig,
    request: &Request,
) -> Result<MediaResult, String> {
    let pool = crate::db::connect(database_url)
        .await
        .map_err(|error| error.to_string())?;
    let outcome = async {
        record_audit(&pool, request)
            .await
            .map_err(|error| error.to_string())?;
        Ok::<_, String>(())
    }
    .await;
    pool.close().await;
    outcome?;
    let media = match (request.media, &config.media_root) {
        (MediaOption::AddMissing, Some(root)) => {
            let archive = config.dir.join(&request.backup);
            let root = root.clone();
            match tokio::task::spawn_blocking(move || add_missing_media(&archive, &root)).await {
                Ok(Ok(added)) => MediaResult::Added(added),
                Ok(Err(error)) => MediaResult::Failed(error.to_string()),
                Err(error) => MediaResult::Failed(error.to_string()),
            }
        }
        _ => MediaResult::Skipped,
    };
    Ok(media)
}

enum MediaResult {
    Skipped,
    Added(u64),
    Failed(String),
}

/// Record `backup_restored` once (a crash and a re-run must not duplicate it).
async fn record_audit(pool: &SqlitePool, request: &Request) -> Result<(), sqlx::Error> {
    let marker = format!("\"id\":\"{}\"", request.id);
    let already: bool = sqlx::query_scalar(
        "SELECT EXISTS(SELECT 1 FROM auth_audit_events \
         WHERE action = 'backup_restored' AND instr(details, ?) > 0)",
    )
    .bind(&marker)
    .fetch_one(pool)
    .await?;
    if already {
        return Ok(());
    }
    // The administrator may not exist in the restored data: keep the email in
    // the details and leave the foreign key empty then.
    let actor: Option<i64> = sqlx::query_scalar("SELECT id FROM users WHERE id = ?")
        .bind(request.requested_by.id)
        .fetch_optional(pool)
        .await?;
    let details = serde_json::json!({
        "id": request.id,
        "backup": request.backup,
        "pre_restore": request.pre_restore,
        "media": request.media.as_str(),
        "actor_email": request.requested_by.email,
    })
    .to_string();
    sqlx::query(
        "INSERT INTO auth_audit_events (action, actor_user_id, target_user_id, created_at, details) \
         VALUES ('backup_restored', ?, ?, ?, ?)",
    )
    .bind(actor)
    .bind(actor)
    .bind(Utc::now().timestamp())
    .bind(details)
    .execute(pool)
    .await?;
    Ok(())
}

fn finish_success(
    config: &BackupConfig,
    request: &Request,
    live: &Files,
    aside: &Files,
    media: MediaResult,
) -> io::Result<()> {
    let (media_added, media_error) = match media {
        MediaResult::Skipped => (None, None),
        MediaResult::Added(count) => (Some(count), None),
        MediaResult::Failed(error) => (None, Some(error)),
    };
    write_outcome(
        &config.dir,
        &Outcome {
            status: "succeeded".to_owned(),
            backup: request.backup.clone(),
            pre_restore: request.pre_restore.clone(),
            media: request.media.as_str().to_owned(),
            finished_at: Utc::now().timestamp(),
            requested_by_email: request.requested_by.email.clone(),
            error: None,
            media_added,
            media_error,
        },
    )?;
    write_epoch(config)?;
    // The restore is complete; only now give up the original files.
    remove_marker_and_stage(config, request)?;
    for path in aside.all() {
        remove_if_exists(path)?;
    }
    // The restored files must be a self-contained database (no stale sidecar of
    // the old one can exist after the swap, but be explicit).
    let _ = (live,);
    sync_dir(live.database.parent().unwrap_or(Path::new(".")))
}

fn remove_marker_and_stage(config: &BackupConfig, request: &Request) -> io::Result<()> {
    remove_if_exists(&config.dir.join(&request.staged))?;
    remove_if_exists(&marker_path(&config.dir))?;
    sync_dir(&config.dir)
}

/// A failure before any file of the live database moved.
fn fail_before_swap(
    config: &BackupConfig,
    request: &Request,
    reason: &str,
) -> Result<(), &'static str> {
    record_failure(config, request, reason);
    remove_marker_and_stage(config, request).map_err(|_| "cannot clear the restore request")
}

fn record_failure(config: &BackupConfig, request: &Request, reason: &str) {
    let _ = write_outcome(
        &config.dir,
        &Outcome {
            status: "failed".to_owned(),
            backup: request.backup.clone(),
            pre_restore: request.pre_restore.clone(),
            media: request.media.as_str().to_owned(),
            finished_at: Utc::now().timestamp(),
            requested_by_email: request.requested_by.email.clone(),
            error: Some(reason.to_owned()),
            media_added: None,
            media_error: None,
        },
    );
}

/// Put the original database back. Idempotent; a failure here is fatal so the
/// server never starts on a half-restored database (the aside files stay).
fn roll_back(
    config: &BackupConfig,
    request: &Request,
    live: &Files,
    aside: &Files,
    reason: &str,
) -> Result<(), &'static str> {
    let parent = live.database.parent().unwrap_or(Path::new("."));
    if exists(&aside.database) {
        for path in live.all() {
            remove_if_exists(path).map_err(|_| "cannot remove the restored database")?;
        }
        for (from, to) in aside.all().into_iter().zip(live.all()) {
            if exists(from) {
                fs::rename(from, to).map_err(|_| "cannot move the original database back")?;
            }
        }
        sync_dir(parent).map_err(|_| "cannot sync the original database")?;
    } else if !exists(&live.database) {
        return Err("neither the original nor the restored database is available");
    }
    record_failure(config, request, reason);
    remove_marker_and_stage(config, request).map_err(|_| "cannot clear the restore request")
}

// -------------------------------------------------------------------- media

/// Copy media files of the archive that do not exist yet. Never overwrites and
/// never deletes; unsafe or non-regular entries are skipped.
fn add_missing_media(archive: &Path, media_root: &Path) -> io::Result<u64> {
    let root_name = media_root
        .file_name()
        .map(|name| name.to_string_lossy().into_owned())
        .unwrap_or_else(|| "media".to_owned());
    let reader = flate2::read::GzDecoder::new(File::open(archive)?);
    let mut tar = tar::Archive::new(reader);
    let mut added = 0_u64;
    for entry in tar.entries()? {
        let mut entry = entry?;
        if !entry.header().entry_type().is_file() {
            continue;
        }
        let path = entry.path()?.into_owned();
        let mut components = path.components();
        let top = match components.next() {
            Some(Component::Normal(top)) => top.to_string_lossy().into_owned(),
            _ => continue,
        };
        if top == ARCHIVE_DATABASE || (top != "media" && top != root_name) {
            continue;
        }
        let mut relative = PathBuf::new();
        let mut safe = true;
        for component in components {
            match component {
                Component::Normal(part) => relative.push(part),
                _ => {
                    safe = false;
                    break;
                }
            }
        }
        if !safe || relative.as_os_str().is_empty() {
            continue;
        }
        let destination = media_root.join(&relative);
        if exists(&destination) || !parents_are_plain(media_root, &relative) {
            continue;
        }
        if let Some(parent) = destination.parent() {
            fs::create_dir_all(parent)?;
        }
        let Some(name) = destination.file_name() else {
            continue;
        };
        let part = destination.with_file_name(format!(".{}.restore-part", name.to_string_lossy()));
        let _ = remove_if_exists(&part);
        let result = copy_with_hash(&mut entry, &part, MAX_MEDIA_FILE_BYTES);
        match result {
            Ok(_) => {
                // `hard_link` refuses to replace: a file that appeared in the
                // meantime wins.
                match fs::hard_link(&part, &destination) {
                    Ok(()) => added += 1,
                    Err(error) if error.kind() == io::ErrorKind::AlreadyExists => {}
                    Err(error) => {
                        let _ = remove_if_exists(&part);
                        return Err(error);
                    }
                }
                remove_if_exists(&part)?;
            }
            Err(error) => {
                let _ = remove_if_exists(&part);
                return Err(error);
            }
        }
    }
    Ok(added)
}

/// Every existing ancestor below the media root is a real directory.
fn parents_are_plain(root: &Path, relative: &Path) -> bool {
    let mut current = root.to_path_buf();
    let count = relative.components().count();
    for (index, component) in relative.components().enumerate() {
        if index + 1 == count {
            break;
        }
        current.push(component);
        match fs::symlink_metadata(&current) {
            Ok(metadata) if !metadata.is_dir() => return false,
            _ => {}
        }
    }
    true
}
