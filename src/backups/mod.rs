//! Administrator backups: listing, inspection, and the guarded restore request.
//!
//! The backup directory (`STRICKNANI_BACKUP_DIR`, default `backups` next to the
//! database file) holds the nightly archives (`stricknani-<ts>.tar.gz`: a
//! `database.sqlite3` plus the media tree), the pre-deploy, pre-migration and
//! pre-restore database copies, and anything an operator dropped there. Nothing
//! outside that directory is ever exposed: names are validated against a strict
//! alphabet, symlinks and anything that is not a regular file are ignored.
//!
//! A restore never touches the live database in place. The request handler
//! first writes `pre-restore-<ts>.sqlite3` (`VACUUM INTO` plus an integrity
//! check), stages the chosen database next to it, verifies the staged copy and
//! persists a marker file; the server then exits with [`RESTART_EXIT_CODE`].
//! On the next start [`startup::prepare`] performs the swap before the pool
//! opens, see that module for the crash-safety argument.

use std::{
    collections::HashMap,
    fs::{self, File, OpenOptions},
    io::{self, Read, Write},
    path::{Component, Path, PathBuf},
    sync::{
        Arc, Mutex, PoisonError,
        atomic::{AtomicBool, Ordering},
    },
};

use chrono::{DateTime, NaiveDateTime, SecondsFormat, TimeZone, Utc};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use sqlx::{Connection, SqliteConnection, SqlitePool, sqlite::SqliteConnectOptions};

pub(crate) mod startup;

pub use startup::prepare as prepare_startup;

/// Exit status of a server that stopped to complete a restore. systemd units
/// list it in `RestartForceExitStatus`; it is a deliberate, non-error stop.
pub const RESTART_EXIT_CODE: u8 = 75;

/// Name of the database inside a nightly archive.
const ARCHIVE_DATABASE: &str = "database.sqlite3";
/// Largest database the server stages or inspects (decompressed).
const MAX_DATABASE_BYTES: u64 = 8 * 1024 * 1024 * 1024;
/// Largest single media file `add_missing` extracts from an archive.
const MAX_MEDIA_FILE_BYTES: u64 = 2 * 1024 * 1024 * 1024;
const MAX_NAME_BYTES: usize = 200;

const MARKER_FILE: &str = ".restore-request.json";
const STATUS_FILE: &str = ".restore-status.json";
const EPOCH_FILE: &str = ".data-epoch";

static RESTART_REQUESTED: AtomicBool = AtomicBool::new(false);
static RESTART_SIGNAL: tokio::sync::Notify = tokio::sync::Notify::const_new();

/// Ask the server to stop gracefully with [`RESTART_EXIT_CODE`].
pub fn request_restart() {
    RESTART_REQUESTED.store(true, Ordering::SeqCst);
    RESTART_SIGNAL.notify_waiters();
    RESTART_SIGNAL.notify_one();
}

/// Whether a restore asked the server to restart.
pub fn restart_requested() -> bool {
    RESTART_REQUESTED.load(Ordering::SeqCst)
}

/// Resolves once [`request_restart`] was called.
pub async fn restart_signal() {
    loop {
        if restart_requested() {
            return;
        }
        RESTART_SIGNAL.notified().await;
    }
}

/// Where backups live and what a restore replaces.
#[derive(Clone, Debug)]
pub struct BackupConfig {
    pub dir: PathBuf,
    pub database_path: PathBuf,
    pub media_root: Option<PathBuf>,
    /// Exit after staging a restore so the supervisor restarts the server.
    pub auto_restart: bool,
}

impl BackupConfig {
    /// `None` for in-memory databases, which have no file to back up or replace.
    pub fn resolve(
        database_url: &str,
        backup_dir: Option<&str>,
        media_root: Option<&Path>,
        auto_restart: bool,
    ) -> Option<Self> {
        let database_path = crate::config::database_path(database_url)?;
        let name = database_path.file_name()?.to_string_lossy();
        if name.is_empty() || name.contains(":memory:") || name.starts_with("file:") {
            return None;
        }
        let dir = match backup_dir.map(str::trim).filter(|dir| !dir.is_empty()) {
            Some(dir) => PathBuf::from(dir),
            None => database_path.parent()?.join("backups"),
        };
        Some(Self {
            dir,
            database_path,
            media_root: media_root.map(Path::to_path_buf),
            auto_restart,
        })
    }

    fn data_dir(&self) -> &Path {
        self.database_path.parent().unwrap_or(Path::new("."))
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Kind {
    Nightly,
    Predeploy,
    PreMigrate,
    PreRestore,
    Manual,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum Format {
    Sqlite,
    TarGz,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub enum MediaOption {
    #[serde(rename = "none")]
    Skip,
    #[serde(rename = "add_missing")]
    AddMissing,
}

impl MediaOption {
    pub(crate) const fn as_str(self) -> &'static str {
        match self {
            Self::Skip => "none",
            Self::AddMissing => "add_missing",
        }
    }
}

#[derive(Clone, Debug)]
pub(crate) struct BackupFile {
    pub name: String,
    pub kind: Kind,
    format: Format,
    pub created_at: DateTime<Utc>,
    pub size: u64,
    mtime_ns: u128,
}

/// What an inspection of one backup found.
#[derive(Clone, Debug, Default, Serialize)]
pub(crate) struct Details {
    pub readable: bool,
    pub error: Option<&'static str>,
    pub has_media: bool,
    pub schema_version: Option<i64>,
    pub counts: Option<Counts>,
}

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Serialize)]
pub(crate) struct Counts {
    pub users: Option<i64>,
    pub projects: Option<i64>,
    pub yarns: Option<i64>,
    pub trashed: Option<i64>,
}

/// One listing row.
#[derive(Clone, Debug, Serialize)]
pub(crate) struct Entry {
    pub name: String,
    pub kind: Kind,
    pub created_at: String,
    pub size_bytes: u64,
    pub has_media: bool,
    pub schema_version: Option<i64>,
    pub counts: Option<Counts>,
    pub readable: bool,
    pub newer_schema: bool,
    pub restorable: bool,
    pub error: Option<&'static str>,
}

#[derive(Debug, thiserror::Error)]
pub(crate) enum RestoreError {
    #[error("backup not found")]
    NotFound,
    #[error("confirmation does not match")]
    ConfirmationMismatch,
    #[error("backup cannot be read")]
    Unreadable,
    #[error("backup was written by a newer server")]
    NewerSchema,
    #[error("media option is not available for this backup")]
    MediaUnavailable,
    #[error("a restore is already in progress")]
    Busy,
    #[error("the pre-restore backup failed")]
    PreRestoreFailed,
    #[error("staging the backup failed")]
    StageFailed,
    #[error("backups are not available")]
    Unavailable,
}

#[derive(Clone, Debug, Serialize)]
pub(crate) struct Preview {
    pub backup: Entry,
    pub current: Snapshot,
    pub backup_snapshot: Snapshot,
    /// Audit events recorded after the backup was taken: a rough measure of
    /// the work that disappears.
    pub changes_since_backup: Option<i64>,
    pub media_options: Vec<&'static str>,
    pub restarts_server: bool,
}

#[derive(Clone, Debug, Serialize)]
pub(crate) struct Snapshot {
    pub schema_version: Option<i64>,
    pub counts: Option<Counts>,
}

#[derive(Clone, Debug, Serialize)]
pub(crate) struct Accepted {
    pub backup: String,
    pub pre_restore: String,
    pub restarting: bool,
}

/// Who asked for a restore.
#[derive(Clone, Debug, Serialize, Deserialize)]
pub(crate) struct Actor {
    pub id: i64,
    pub email: String,
}

/// The persisted restore request (`.restore-request.json`).
#[derive(Clone, Debug, Serialize, Deserialize)]
pub(crate) struct Request {
    pub version: u32,
    pub id: String,
    pub backup: String,
    pub staged: String,
    pub staged_sha256: String,
    pub staged_bytes: u64,
    pub pre_restore: String,
    pub media: MediaOption,
    pub requested_by: Actor,
    pub requested_at: i64,
    pub backup_schema_version: i64,
    pub phase: Phase,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub(crate) enum Phase {
    Staged,
    Swapping,
    Swapped,
    RollingBack,
}

/// Outcome of the last restore, shown by the admin UI (`.restore-status.json`).
#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct Outcome {
    pub status: String,
    pub backup: String,
    pub pre_restore: String,
    pub media: String,
    pub finished_at: i64,
    pub requested_by_email: String,
    pub error: Option<String>,
    pub media_added: Option<u64>,
    pub media_error: Option<String>,
}

/// Shared state of the backup feature.
pub struct BackupService {
    pub(crate) config: BackupConfig,
    cache: Mutex<HashMap<(String, u128, u64), Arc<Details>>>,
    pending: AtomicBool,
    last_restore: Option<Outcome>,
    data_epoch: String,
}

impl BackupService {
    pub(crate) fn new(
        config: BackupConfig,
        last_restore: Option<Outcome>,
        data_epoch: String,
    ) -> Self {
        Self {
            config,
            cache: Mutex::new(HashMap::new()),
            pending: AtomicBool::new(false),
            last_restore,
            data_epoch,
        }
    }

    /// For tests and embedders that do not run [`prepare_startup`].
    pub fn for_config(config: BackupConfig) -> Self {
        let epoch = startup::ensure_data_epoch(&config).unwrap_or_else(|_| "0".to_owned());
        let outcome = startup::read_outcome(&config.dir);
        Self::new(config, outcome, epoch)
    }

    pub fn data_epoch(&self) -> &str {
        &self.data_epoch
    }

    pub fn last_restore(&self) -> Option<&Outcome> {
        self.last_restore.as_ref()
    }

    /// A restore is staged: the server rejects writes until it restarts.
    pub fn restore_pending(&self) -> bool {
        self.pending.load(Ordering::SeqCst)
    }

    pub(crate) fn config(&self) -> &BackupConfig {
        &self.config
    }

    pub(crate) fn staged_request(&self) -> Option<Request> {
        startup::read_request(&self.config.dir)
    }

    /// Every regular file of the backup directory that looks like a backup.
    pub(crate) fn list_files(&self) -> io::Result<Vec<BackupFile>> {
        let mut files = Vec::new();
        let reader = match fs::read_dir(&self.config.dir) {
            Ok(reader) => reader,
            Err(error) if error.kind() == io::ErrorKind::NotFound => return Ok(files),
            Err(error) => return Err(error),
        };
        for entry in reader {
            let Ok(entry) = entry else { continue };
            let Ok(name) = entry.file_name().into_string() else {
                continue;
            };
            let Ok(metadata) = fs::symlink_metadata(entry.path()) else {
                continue;
            };
            if !metadata.is_file() {
                continue;
            }
            if let Some(file) = classify(&name, &metadata) {
                files.push(file);
            }
        }
        files.sort_by(|a, b| {
            b.created_at
                .cmp(&a.created_at)
                .then_with(|| a.name.cmp(&b.name))
        });
        Ok(files)
    }

    /// Resolve a client-supplied name to a backup file inside the directory.
    pub(crate) fn find(&self, name: &str) -> Result<BackupFile, RestoreError> {
        if !valid_name(name) {
            return Err(RestoreError::NotFound);
        }
        let path = self.config.dir.join(name);
        let metadata = fs::symlink_metadata(&path).map_err(|_| RestoreError::NotFound)?;
        if !metadata.is_file() {
            return Err(RestoreError::NotFound);
        }
        classify(name, &metadata).ok_or(RestoreError::NotFound)
    }

    pub(crate) async fn details(&self, file: &BackupFile) -> Arc<Details> {
        let key = (file.name.clone(), file.mtime_ns, file.size);
        if let Some(found) = self
            .cache
            .lock()
            .unwrap_or_else(PoisonError::into_inner)
            .get(&key)
        {
            return found.clone();
        }
        let details = Arc::new(inspect(&self.config.dir, file).await);
        let mut cache = self.cache.lock().unwrap_or_else(PoisonError::into_inner);
        // Entries of deleted or replaced files would otherwise pile up.
        cache.retain(|(name, _, _), _| name != &file.name);
        cache.insert(key, details.clone());
        details
    }

    pub(crate) fn entry(file: &BackupFile, details: &Details) -> Entry {
        let newer = details
            .schema_version
            .is_some_and(|version| version > latest_migration());
        Entry {
            name: file.name.clone(),
            kind: file.kind,
            created_at: file.created_at.to_rfc3339_opts(SecondsFormat::Secs, true),
            size_bytes: file.size,
            has_media: details.has_media,
            schema_version: details.schema_version,
            counts: details.counts,
            readable: details.readable,
            newer_schema: newer,
            restorable: details.readable && details.schema_version.is_some() && !newer,
            error: details.error,
        }
    }

    pub(crate) async fn entries(&self) -> io::Result<Vec<Entry>> {
        let files = self.list_files()?;
        let mut entries = Vec::with_capacity(files.len());
        for file in &files {
            let details = self.details(file).await;
            entries.push(Self::entry(file, &details));
        }
        Ok(entries)
    }

    pub(crate) async fn preview(
        &self,
        pool: &SqlitePool,
        name: &str,
    ) -> Result<Preview, RestoreError> {
        let file = self.find(name)?;
        let details = self.details(&file).await;
        let entry = Self::entry(&file, &details);
        let mut conn = pool
            .acquire()
            .await
            .map_err(|_| RestoreError::Unavailable)?;
        let current = Snapshot {
            schema_version: schema_version(&mut conn).await,
            counts: Some(counts(&mut conn).await),
        };
        let changes_since_backup =
            sqlx::query_scalar::<_, i64>("SELECT count(*) FROM audit_events WHERE created_at > ?")
                .bind(file.created_at.to_rfc3339_opts(SecondsFormat::Millis, true))
                .fetch_one(&mut *conn)
                .await
                .ok();
        let mut media_options = vec!["none"];
        if details.has_media && self.config.media_root.is_some() {
            media_options.push("add_missing");
        }
        Ok(Preview {
            backup_snapshot: Snapshot {
                schema_version: details.schema_version,
                counts: details.counts,
            },
            backup: entry,
            current,
            changes_since_backup,
            media_options,
            restarts_server: self.config.auto_restart,
        })
    }

    /// Every check that needs no side effect: name, confirmation, readable,
    /// not newer than this server, media option available.
    pub(crate) async fn validate(
        &self,
        name: &str,
        confirmation: &str,
        media: MediaOption,
    ) -> Result<(BackupFile, i64), RestoreError> {
        let file = self.find(name)?;
        if confirmation != file.name {
            return Err(RestoreError::ConfirmationMismatch);
        }
        let details = self.details(&file).await;
        let Some(backup_schema_version) = details.schema_version.filter(|_| details.readable)
        else {
            return Err(RestoreError::Unreadable);
        };
        if backup_schema_version > latest_migration() {
            return Err(RestoreError::NewerSchema);
        }
        if media == MediaOption::AddMissing
            && !(details.has_media && self.config.media_root.is_some())
        {
            return Err(RestoreError::MediaUnavailable);
        }
        Ok((file, backup_schema_version))
    }

    /// Validate, take the pre-restore backup, stage the chosen database and
    /// persist the restore request. The caller restarts the server on success.
    pub(crate) async fn request_restore(
        &self,
        pool: &SqlitePool,
        actor: Actor,
        name: &str,
        confirmation: &str,
        media: MediaOption,
    ) -> Result<Accepted, RestoreError> {
        let (file, backup_schema_version) = self.validate(name, confirmation, media).await?;
        if startup::read_request(&self.config.dir).is_some() {
            return Err(RestoreError::Busy);
        }
        if self
            .pending
            .compare_exchange(false, true, Ordering::SeqCst, Ordering::SeqCst)
            .is_err()
        {
            return Err(RestoreError::Busy);
        }
        // From here on the server rejects writes (see `guard_writes`); every
        // failure below lifts the block again.
        let result = self
            .stage(pool, actor, &file, backup_schema_version, media)
            .await;
        if result.is_err() {
            self.pending.store(false, Ordering::SeqCst);
        }
        let (_request, pre_restore) = result?;
        Ok(Accepted {
            backup: file.name,
            pre_restore,
            restarting: self.config.auto_restart,
        })
    }

    async fn stage(
        &self,
        pool: &SqlitePool,
        actor: Actor,
        file: &BackupFile,
        backup_schema_version: i64,
        media: MediaOption,
    ) -> Result<(Request, String), RestoreError> {
        let dir = self.config.dir.clone();
        let stamp = Utc::now().format("%Y%m%d-%H%M%S").to_string();
        // 1. Before anything else: a verified copy of the current database.
        let pre_restore = write_pre_restore(pool, &dir, &stamp)
            .await
            .map_err(|error| {
                tracing::error!(component = "backups", %error, "backups.pre_restore.failed");
                RestoreError::PreRestoreFailed
            })?;
        // 2. Stage the chosen database and verify it.
        let staged = format!(".restore-staging-{stamp}.sqlite3");
        let staged_path = dir.join(&staged);
        let source = dir.join(&file.name);
        let format = file.format;
        let staging = {
            let staged_path = staged_path.clone();
            tokio::task::spawn_blocking(move || stage_database(&source, format, &staged_path)).await
        };
        let (sha256, bytes) = match staging {
            Ok(Ok(result)) => result,
            _ => {
                let _ = fs::remove_file(&staged_path);
                return Err(RestoreError::StageFailed);
            }
        };
        if inspect_database(&staged_path).await.is_err() {
            let _ = fs::remove_file(&staged_path);
            return Err(RestoreError::StageFailed);
        }
        // 3. Persist the request; the next start performs the swap.
        let request = Request {
            version: 1,
            id: random_id(),
            backup: file.name.clone(),
            staged,
            staged_sha256: sha256,
            staged_bytes: bytes,
            pre_restore: pre_restore.clone(),
            media,
            requested_by: actor,
            requested_at: Utc::now().timestamp(),
            backup_schema_version,
            phase: Phase::Staged,
        };
        if startup::write_request(&dir, &request).is_err() {
            let _ = fs::remove_file(&staged_path);
            return Err(RestoreError::StageFailed);
        }
        Ok((request, pre_restore))
    }
}

/// The newest migration this server knows.
pub fn latest_migration() -> i64 {
    sqlx::migrate!()
        .iter()
        .map(|migration| migration.version)
        .max()
        .unwrap_or(0)
}

fn random_id() -> String {
    format!("{:032x}", rand::random::<u128>())
}

/// Names a client may send: one path segment of a restricted alphabet.
pub(crate) fn valid_name(name: &str) -> bool {
    !name.is_empty()
        && name.len() <= MAX_NAME_BYTES
        && !name.starts_with('.')
        && name
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'.' | b'-' | b'_'))
        && (name.ends_with(".sqlite3") || name.ends_with(".tar.gz"))
}

fn classify(name: &str, metadata: &fs::Metadata) -> Option<BackupFile> {
    if !valid_name(name) {
        return None;
    }
    let (stem, format) = match name.strip_suffix(".tar.gz") {
        Some(stem) => (stem, Format::TarGz),
        None => (name.strip_suffix(".sqlite3")?, Format::Sqlite),
    };
    let kind = match format {
        Format::TarGz if stem.starts_with("stricknani-") => Kind::Nightly,
        Format::Sqlite if stem.starts_with("predeploy-") => Kind::Predeploy,
        Format::Sqlite if stem.starts_with("pre-migrate-") => Kind::PreMigrate,
        Format::Sqlite if stem.starts_with("pre-restore-") => Kind::PreRestore,
        _ => Kind::Manual,
    };
    let modified = metadata.modified().ok()?;
    let mtime_ns = modified
        .duration_since(std::time::UNIX_EPOCH)
        .map(|duration| duration.as_nanos())
        .unwrap_or(0);
    let created_at = timestamp_from_name(stem).unwrap_or_else(|| DateTime::<Utc>::from(modified));
    Some(BackupFile {
        name: name.to_owned(),
        kind,
        format,
        created_at,
        size: metadata.len(),
        mtime_ns,
    })
}

/// `…-YYYYmmdd-HHMMSS` at the end of a stem, in UTC.
fn timestamp_from_name(stem: &str) -> Option<DateTime<Utc>> {
    let mut parts = stem.rsplitn(3, '-');
    let time = parts.next()?;
    let date = parts.next()?;
    if date.len() != 8
        || time.len() != 6
        || !(date.bytes().chain(time.bytes())).all(|b| b.is_ascii_digit())
    {
        return None;
    }
    let naive = NaiveDateTime::parse_from_str(&format!("{date}{time}"), "%Y%m%d%H%M%S").ok()?;
    Some(Utc.from_utc_datetime(&naive))
}

// ---------------------------------------------------------------- inspection

async fn inspect(dir: &Path, file: &BackupFile) -> Details {
    match file.format {
        Format::Sqlite => {
            let path = dir.join(&file.name);
            match inspect_database(&path).await {
                Ok((version, counts)) => Details {
                    readable: true,
                    error: None,
                    has_media: false,
                    schema_version: Some(version),
                    counts: Some(counts),
                },
                Err(error) => unreadable(error),
            }
        }
        Format::TarGz => {
            let archive = dir.join(&file.name);
            let dir = dir.to_path_buf();
            let extracted =
                tokio::task::spawn_blocking(move || extract_archive_database(&archive, &dir)).await;
            match extracted {
                Ok(Ok((temp, has_media))) => match inspect_database(temp.path()).await {
                    Ok((version, counts)) => Details {
                        readable: true,
                        error: None,
                        has_media,
                        schema_version: Some(version),
                        counts: Some(counts),
                    },
                    Err(error) => Details {
                        has_media,
                        ..unreadable(error)
                    },
                },
                Ok(Err(ArchiveError::NoDatabase)) => unreadable("archive_without_database"),
                _ => unreadable("archive_unreadable"),
            }
        }
    }
}

fn unreadable(error: &'static str) -> Details {
    Details {
        readable: false,
        error: Some(error),
        ..Details::default()
    }
}

#[derive(Debug)]
enum ArchiveError {
    NoDatabase,
    Io,
}

impl From<io::Error> for ArchiveError {
    fn from(_: io::Error) -> Self {
        Self::Io
    }
}

/// Stream `database.sqlite3` of a nightly archive into a temporary file inside
/// the backup directory (bounded). Also reports whether a media tree follows.
fn extract_archive_database(
    archive: &Path,
    dir: &Path,
) -> Result<(tempfile::NamedTempFile, bool), ArchiveError> {
    let reader = flate2::read::GzDecoder::new(File::open(archive)?);
    let mut tar = tar::Archive::new(reader);
    let mut temp = None;
    let mut has_media = false;
    for entry in tar.entries()? {
        let mut entry = entry?;
        let path = entry.path()?.into_owned();
        let top = path.components().find_map(|component| match component {
            Component::Normal(part) => Some(part.to_string_lossy().into_owned()),
            _ => None,
        });
        let is_database = entry.header().entry_type().is_file()
            && path
                .components()
                .filter(|c| matches!(c, Component::Normal(_)))
                .count()
                == 1
            && top.as_deref() == Some(ARCHIVE_DATABASE);
        if is_database && temp.is_none() {
            let mut file = tempfile::Builder::new()
                .prefix(".inspect-")
                .suffix(".sqlite3")
                .tempfile_in(dir)?;
            let copied = io::copy(
                &mut (&mut entry).take(MAX_DATABASE_BYTES + 1),
                file.as_file_mut(),
            )?;
            if copied > MAX_DATABASE_BYTES {
                return Err(ArchiveError::Io);
            }
            file.as_file_mut().sync_all()?;
            temp = Some(file);
            continue;
        }
        if top.is_some() && top.as_deref() != Some(ARCHIVE_DATABASE) {
            has_media = true;
        }
        if temp.is_some() {
            // The nightly archive lists the media tree right after the
            // database; looking any further would decompress the whole media.
            break;
        }
    }
    temp.map(|temp| (temp, has_media))
        .ok_or(ArchiveError::NoDatabase)
}

async fn open_read_only(path: &Path) -> Result<SqliteConnection, &'static str> {
    let options = SqliteConnectOptions::new()
        .filename(path)
        .read_only(true)
        .immutable(true);
    SqliteConnection::connect_with(&options)
        .await
        .map_err(|_| "database_unreadable")
}

/// Open a copy strictly read-only and read what the UI shows. Also runs
/// `PRAGMA integrity_check`, so a damaged file is never offered for restore.
async fn inspect_database(path: &Path) -> Result<(i64, Counts), &'static str> {
    let mut conn = open_read_only(path).await?;
    let result = async {
        let rows: Vec<String> = sqlx::query_scalar("PRAGMA integrity_check")
            .fetch_all(&mut conn)
            .await
            .map_err(|_| "database_unreadable")?;
        if rows.len() != 1 || rows[0] != "ok" {
            return Err("integrity_check_failed");
        }
        let owner: Option<String> =
            sqlx::query_scalar("SELECT app_id FROM stricknani_meta WHERE singleton = 1")
                .fetch_optional(&mut conn)
                .await
                .map_err(|_| "not_a_stricknani_database")?;
        if owner.as_deref() != Some("stricknani-rust") {
            return Err("not_a_stricknani_database");
        }
        let version = schema_version(&mut conn)
            .await
            .ok_or("not_a_stricknani_database")?;
        Ok((version, counts(&mut conn).await))
    }
    .await;
    let _ = conn.close().await;
    result
}

async fn schema_version(conn: &mut SqliteConnection) -> Option<i64> {
    sqlx::query_scalar::<_, Option<i64>>(
        "SELECT max(version) FROM _sqlx_migrations WHERE success = 1",
    )
    .fetch_one(conn)
    .await
    .ok()
    .flatten()
}

async fn counts(conn: &mut SqliteConnection) -> Counts {
    async fn one(conn: &mut SqliteConnection, sql: &'static str) -> Option<i64> {
        sqlx::query_scalar::<_, i64>(sql).fetch_one(conn).await.ok()
    }
    let users = one(conn, "SELECT count(*) FROM users").await;
    let (projects, yarns, trashed);
    if one(conn, "SELECT count(deleted_at) FROM projects")
        .await
        .is_some()
    {
        projects = one(
            conn,
            "SELECT count(*) FROM projects WHERE deleted_at IS NULL",
        )
        .await;
        yarns = one(conn, "SELECT count(*) FROM yarns WHERE deleted_at IS NULL").await;
        trashed = one(
            conn,
            "SELECT (SELECT count(*) FROM projects WHERE deleted_at IS NOT NULL) \
             + (SELECT count(*) FROM yarns WHERE deleted_at IS NOT NULL)",
        )
        .await;
    } else {
        // Before the trash existed nothing could be trashed.
        projects = one(conn, "SELECT count(*) FROM projects").await;
        yarns = one(conn, "SELECT count(*) FROM yarns").await;
        trashed = Some(0);
    }
    Counts {
        users,
        projects,
        yarns,
        trashed,
    }
}

// ------------------------------------------------------------------- staging

fn private_file(path: &Path) -> io::Result<File> {
    let mut options = OpenOptions::new();
    options.write(true).create_new(true);
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt;
        options.mode(0o600);
    }
    options.open(path)
}

pub(crate) fn sync_dir(dir: &Path) -> io::Result<()> {
    File::open(dir)?.sync_all()
}

/// Copy `source` into a new private file, hashing on the way, and fsync it.
pub(crate) fn copy_with_hash(
    source: &mut impl Read,
    destination: &Path,
    limit: u64,
) -> io::Result<(String, u64)> {
    let mut out = private_file(destination)?;
    let result = (|| {
        let mut hasher = Sha256::new();
        let mut buffer = vec![0_u8; 64 * 1024];
        let mut total = 0_u64;
        loop {
            let read = source.read(&mut buffer)?;
            if read == 0 {
                break;
            }
            total += read as u64;
            if total > limit {
                return Err(io::Error::other("file is too large"));
            }
            hasher.update(&buffer[..read]);
            out.write_all(&buffer[..read])?;
        }
        out.sync_all()?;
        Ok((hex::encode(hasher.finalize()), total))
    })();
    if result.is_err() {
        drop(out);
        let _ = fs::remove_file(destination);
    }
    result
}

fn stage_database(source: &Path, format: Format, staged: &Path) -> io::Result<(String, u64)> {
    let result = match format {
        Format::Sqlite => {
            let mut file = File::open(source)?;
            copy_with_hash(&mut file, staged, MAX_DATABASE_BYTES)
        }
        Format::TarGz => {
            let reader = flate2::read::GzDecoder::new(File::open(source)?);
            let mut tar = tar::Archive::new(reader);
            let mut found = None;
            for entry in tar.entries()? {
                let mut entry = entry?;
                let path = entry.path()?.into_owned();
                let normal: Vec<_> = path
                    .components()
                    .filter(|component| matches!(component, Component::Normal(_)))
                    .collect();
                if entry.header().entry_type().is_file()
                    && normal.len() == 1
                    && normal[0].as_os_str() == ARCHIVE_DATABASE
                {
                    found = Some(copy_with_hash(&mut entry, staged, MAX_DATABASE_BYTES)?);
                    break;
                }
            }
            found.ok_or_else(|| io::Error::other("archive has no database"))
        }
    }?;
    if let Some(parent) = staged.parent() {
        sync_dir(parent)?;
    }
    Ok(result)
}

/// `VACUUM INTO` a hidden part file, verify it, then publish it atomically.
async fn write_pre_restore(pool: &SqlitePool, dir: &Path, stamp: &str) -> Result<String, String> {
    fs::create_dir_all(dir).map_err(|error| error.to_string())?;
    let name = format!("pre-restore-{stamp}.sqlite3");
    let part = dir.join(format!(".{name}.part"));
    let target = dir.join(&name);
    if target.exists() {
        return Err("pre-restore backup name is taken".to_owned());
    }
    let _ = fs::remove_file(&part);
    let part_str = part
        .to_str()
        .ok_or_else(|| "backup path is not UTF-8".to_owned())?
        .to_owned();
    let outcome = async {
        sqlx::query("VACUUM INTO ?")
            .bind(&part_str)
            .execute(pool)
            .await
            .map_err(|error| error.to_string())?;
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            fs::set_permissions(&part, fs::Permissions::from_mode(0o600))
                .map_err(|error| error.to_string())?;
        }
        File::open(&part)
            .and_then(|file| file.sync_all())
            .map_err(|error| error.to_string())?;
        inspect_database(&part)
            .await
            .map_err(|error| error.to_owned())?;
        fs::rename(&part, &target).map_err(|error| error.to_string())?;
        sync_dir(dir).map_err(|error| error.to_string())
    }
    .await;
    if let Err(error) = outcome {
        let _ = fs::remove_file(&part);
        return Err(error);
    }
    Ok(name)
}

// ----------------------------------------------------------- write blocking

/// While a restore is staged the server refuses writes: anything accepted
/// after the pre-restore backup would be lost without a trace.
pub(crate) async fn guard_writes(
    axum::extract::State(state): axum::extract::State<crate::db::AppState>,
    request: axum::extract::Request,
    next: axum::middleware::Next,
) -> axum::response::Response {
    use axum::{http::Method, response::IntoResponse};
    let pending = state
        .backups
        .as_ref()
        .is_some_and(|service| service.restore_pending());
    if pending
        && !matches!(
            *request.method(),
            Method::GET | Method::HEAD | Method::OPTIONS
        )
    {
        let mut response = (
            axum::http::StatusCode::SERVICE_UNAVAILABLE,
            axum::Json(serde_json::json!({
                "error": {
                    "code": "restore_in_progress",
                    "message": "The server is restoring a backup and restarts shortly"
                }
            })),
        )
            .into_response();
        response.headers_mut().insert(
            axum::http::header::RETRY_AFTER,
            axum::http::HeaderValue::from_static("15"),
        );
        return response;
    }
    next.run(request).await
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn names_are_one_safe_segment() {
        assert!(valid_name("stricknani-20260101-020000.tar.gz"));
        assert!(valid_name("pre-restore-20260101-020000.sqlite3"));
        assert!(!valid_name("../etc/passwd.sqlite3"));
        assert!(!valid_name("a/b.sqlite3"));
        assert!(!valid_name(".hidden.sqlite3"));
        assert!(!valid_name("evil\\name.sqlite3"));
        assert!(!valid_name("notes.txt"));
        assert!(!valid_name(""));
    }

    #[test]
    fn timestamps_come_from_the_name() {
        let parsed = timestamp_from_name("predeploy-1.7.1-20260102-030405").unwrap();
        assert_eq!(parsed.to_rfc3339(), "2026-01-02T03:04:05+00:00");
        assert!(timestamp_from_name("manual-copy").is_none());
    }
}
