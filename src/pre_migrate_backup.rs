//! Consistent database snapshot taken right before pending migrations run.
//!
//! A deploy that carries a schema change is the one moment an operator really
//! wants a fresh backup. At startup the server compares the migrations already
//! applied to the file-backed database with the embedded ones. When some are
//! pending (and the database is not brand new), it writes a `VACUUM INTO`
//! snapshot, which is consistent with the WAL, verifies it with
//! `PRAGMA integrity_check`, and only then migrates. If the snapshot cannot be
//! written or verified, startup fails and the database is left untouched.
//!
//! Configuration (environment):
//!
//! * `STRICKNANI_PRE_MIGRATE_BACKUP` = `true` (default) or `false`.
//! * `STRICKNANI_BACKUP_DIR`: target directory. Default: `backups` next to the
//!   database file.
//! * `STRICKNANI_PRE_MIGRATE_BACKUP_RETENTION`: how many `pre-migrate-*`
//!   snapshots to keep (default 10, at least 1). Other files are never pruned.
//!
//! Snapshots are named
//! `pre-migrate-<last applied>-to-<newest>-<UTC YYYYmmdd-HHMMSS>.sqlite3`
//! and have mode 0600. Only paths and sizes are logged, never contents.
use sqlx::{
    Connection, Executor, Row, SqlitePool,
    migrate::Migrator,
    sqlite::{SqliteConnectOptions, SqliteJournalMode},
};
use std::{
    collections::BTreeSet,
    io,
    path::{Path, PathBuf},
    str::FromStr,
};

const PREFIX: &str = "pre-migrate-";
const SUFFIX: &str = ".sqlite3";
const PARTIAL_SUFFIX: &str = ".partial";
/// `YYYYmmdd-HHMMSS` (UTC).
const TIMESTAMP_LEN: usize = 15;
pub const DEFAULT_RETENTION: usize = 10;

#[derive(Debug, thiserror::Error)]
pub enum BackupError {
    #[error("{0}")]
    Config(String),
    #[error("{context}: {source}")]
    Io {
        context: String,
        #[source]
        source: io::Error,
    },
    #[error("{context}: {source}")]
    Sqlite {
        context: &'static str,
        #[source]
        source: sqlx::Error,
    },
    #[error("snapshot {path} failed verification: {detail}")]
    Verify { path: String, detail: String },
}

fn io_error(context: impl Into<String>, source: io::Error) -> BackupError {
    BackupError::Io {
        context: context.into(),
        source,
    }
}

fn sqlite_error(context: &'static str, source: sqlx::Error) -> BackupError {
    BackupError::Sqlite { context, source }
}

/// Where and how many pre-migration snapshots to keep.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct PreMigrateBackup {
    pub dir: PathBuf,
    pub retention: usize,
}

/// A snapshot that was written and verified.
#[derive(Debug)]
pub struct CreatedBackup {
    pub path: PathBuf,
    pub bytes: u64,
}

impl PreMigrateBackup {
    pub fn new(dir: impl Into<PathBuf>) -> Self {
        Self {
            dir: dir.into(),
            retention: DEFAULT_RETENTION,
        }
    }

    /// Settings from the process environment; `None` when disabled.
    pub fn from_env(database_file: &Path) -> Result<Option<Self>, BackupError> {
        Self::from_lookup(|key| std::env::var(key).ok(), database_file)
    }

    pub(crate) fn from_lookup(
        mut get: impl FnMut(&str) -> Option<String>,
        database_file: &Path,
    ) -> Result<Option<Self>, BackupError> {
        let enabled = match get("STRICKNANI_PRE_MIGRATE_BACKUP")
            .map(|value| value.trim().to_ascii_lowercase())
            .as_deref()
        {
            None | Some("") | Some("true") | Some("1") | Some("yes") | Some("on") => true,
            Some("false") | Some("0") | Some("no") | Some("off") => false,
            Some(_) => {
                return Err(BackupError::Config(
                    "STRICKNANI_PRE_MIGRATE_BACKUP must be true or false".into(),
                ));
            }
        };
        if !enabled {
            return Ok(None);
        }
        let dir = backup_dir_from(&mut get, database_file);
        let retention = match get("STRICKNANI_PRE_MIGRATE_BACKUP_RETENTION")
            .filter(|value| !value.trim().is_empty())
        {
            None => DEFAULT_RETENTION,
            Some(value) => match value.trim().parse::<usize>() {
                Ok(count) if count >= 1 => count,
                _ => {
                    return Err(BackupError::Config(
                        "STRICKNANI_PRE_MIGRATE_BACKUP_RETENTION must be a whole number of at least 1"
                            .into(),
                    ));
                }
            },
        };
        Ok(Some(Self { dir, retention }))
    }
}

/// The resolved backup directory: `STRICKNANI_BACKUP_DIR`, else `backups` in
/// the directory of the database file. Pre-migration snapshots, the NixOS
/// pre-deploy backups and restore safety copies all live here.
pub fn backup_dir(database_file: &Path) -> PathBuf {
    backup_dir_from(&mut |key| std::env::var(key).ok(), database_file)
}

pub(crate) fn backup_dir_from(
    get: &mut impl FnMut(&str) -> Option<String>,
    database_file: &Path,
) -> PathBuf {
    match get("STRICKNANI_BACKUP_DIR").filter(|value| !value.trim().is_empty()) {
        Some(dir) => PathBuf::from(dir),
        None => database_file
            .parent()
            .unwrap_or_else(|| Path::new(""))
            .join("backups"),
    }
}

/// Migrations the database lacks: `(last applied, newest embedded)`, or `None`
/// when nothing is pending or the database has no applied migrations yet.
async fn pending_range(
    pool: &SqlitePool,
    migrator: &Migrator,
) -> Result<Option<(i64, i64)>, BackupError> {
    let has_table: Option<i64> = sqlx::query_scalar(
        "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = '_sqlx_migrations'",
    )
    .fetch_optional(pool)
    .await
    .map_err(|source| sqlite_error("cannot inspect the migration table", source))?;
    if has_table.is_none() {
        return Ok(None);
    }
    let applied: BTreeSet<i64> =
        sqlx::query_scalar::<_, i64>("SELECT version FROM _sqlx_migrations WHERE success = 1")
            .fetch_all(pool)
            .await
            .map_err(|source| sqlite_error("cannot read the applied migrations", source))?
            .into_iter()
            .collect();
    let Some(last_applied) = applied.last().copied() else {
        return Ok(None);
    };
    let embedded: BTreeSet<i64> = migrator
        .iter()
        .filter(|migration| !migration.migration_type.is_down_migration())
        .map(|migration| migration.version)
        .collect();
    if embedded.difference(&applied).next().is_none() {
        return Ok(None);
    }
    Ok(embedded.last().map(|newest| (last_applied, *newest)))
}

/// Snapshot the database when migrations are pending. Returns the snapshot, or
/// `None` when no backup was needed.
pub async fn backup_if_pending(
    pool: &SqlitePool,
    migrator: &Migrator,
    settings: &PreMigrateBackup,
) -> Result<Option<CreatedBackup>, BackupError> {
    let Some((from, to)) = pending_range(pool, migrator).await? else {
        return Ok(None);
    };
    let timestamp = chrono::Utc::now().format("%Y%m%d-%H%M%S");
    let name = format!("{PREFIX}{from:04}-to-{to:04}-{timestamp}{SUFFIX}");
    let created = snapshot(pool, settings, &name).await?;
    tracing::info!(
        component = "backup",
        path = %created.path.display(),
        bytes = created.bytes,
        from,
        to,
        "db.pre_migrate_backup"
    );
    prune(settings);
    Ok(Some(created))
}

async fn snapshot(
    pool: &SqlitePool,
    settings: &PreMigrateBackup,
    name: &str,
) -> Result<CreatedBackup, BackupError> {
    create_dir(&settings.dir)?;
    let final_path = settings.dir.join(name);
    let partial = settings.dir.join(format!("{name}{PARTIAL_SUFFIX}"));
    let result = write_snapshot(pool, &partial, &final_path).await;
    if result.is_err() {
        let _ = std::fs::remove_file(&partial);
    }
    result
}

fn create_dir(dir: &Path) -> Result<(), BackupError> {
    let mut builder = std::fs::DirBuilder::new();
    builder.recursive(true);
    #[cfg(unix)]
    std::os::unix::fs::DirBuilderExt::mode(&mut builder, 0o700);
    builder.create(dir).map_err(|source| {
        io_error(
            format!("cannot create backup directory {}", dir.display()),
            source,
        )
    })
}

async fn write_snapshot(
    pool: &SqlitePool,
    partial: &Path,
    final_path: &Path,
) -> Result<CreatedBackup, BackupError> {
    let mut options = std::fs::OpenOptions::new();
    options.write(true).create_new(true);
    #[cfg(unix)]
    std::os::unix::fs::OpenOptionsExt::mode(&mut options, 0o600);
    // VACUUM INTO accepts an empty target file, which lets the snapshot be
    // created with restrictive permissions from the start.
    options
        .open(partial)
        .map_err(|source| io_error(format!("cannot create {}", partial.display()), source))?;
    let target = partial
        .to_str()
        .ok_or_else(|| BackupError::Config("backup path is not valid UTF-8".into()))?;
    sqlx::query("VACUUM INTO ?")
        .bind(target)
        .execute(pool)
        .await
        .map_err(|source| sqlite_error("VACUUM INTO failed", source))?;
    verify(partial).await?;
    std::fs::File::open(partial)
        .and_then(|file| file.sync_all())
        .map_err(|source| io_error(format!("cannot sync {}", partial.display()), source))?;
    std::fs::rename(partial, final_path).map_err(|source| {
        io_error(
            format!("cannot move snapshot to {}", final_path.display()),
            source,
        )
    })?;
    if let Some(parent) = final_path.parent()
        && let Ok(dir) = std::fs::File::open(parent)
    {
        let _ = dir.sync_all();
    }
    let bytes = std::fs::metadata(final_path)
        .map_err(|source| io_error(format!("cannot stat {}", final_path.display()), source))?
        .len();
    Ok(CreatedBackup {
        path: final_path.to_path_buf(),
        bytes,
    })
}

/// Open the copy on its own and require `PRAGMA integrity_check` to say `ok`.
async fn verify(path: &Path) -> Result<(), BackupError> {
    let shown = path.display().to_string();
    let options = SqliteConnectOptions::from_str("sqlite:")
        .map_err(|source| sqlite_error("cannot prepare verification", source))?
        .filename(path)
        .create_if_missing(false)
        // Leave a self-contained single file, with no -wal/-shm sidecars.
        .journal_mode(SqliteJournalMode::Delete);
    let mut connection = sqlx::SqliteConnection::connect_with(&options)
        .await
        .map_err(|source| sqlite_error("cannot open the snapshot for verification", source))?;
    let rows = connection
        .fetch_all("PRAGMA integrity_check")
        .await
        .map_err(|source| sqlite_error("integrity check failed to run", source))?;
    let results: Vec<String> = rows
        .iter()
        .map(|row| row.try_get::<String, _>(0).unwrap_or_default())
        .collect();
    let _ = connection.close().await;
    if results.len() == 1 && results[0] == "ok" {
        Ok(())
    } else {
        Err(BackupError::Verify {
            path: shown,
            detail: results.into_iter().take(5).collect::<Vec<_>>().join("; "),
        })
    }
}

/// The UTC timestamp of a `pre-migrate-*.sqlite3` file name, or `None` for any
/// other name, so nothing else in the directory is ever pruned.
fn snapshot_timestamp(name: &str) -> Option<&str> {
    let stem = name.strip_prefix(PREFIX)?.strip_suffix(SUFFIX)?;
    let stamp = stem.get(stem.len().checked_sub(TIMESTAMP_LEN)?..)?;
    let (date, rest) = stamp.split_at(8);
    let valid = date.bytes().all(|b| b.is_ascii_digit())
        && rest.starts_with('-')
        && rest[1..].bytes().all(|b| b.is_ascii_digit());
    valid.then_some(stamp)
}

/// Delete the oldest `pre-migrate-*` snapshots beyond the retention.
pub(crate) fn prune(settings: &PreMigrateBackup) {
    let Ok(entries) = std::fs::read_dir(&settings.dir) else {
        return;
    };
    let mut snapshots: Vec<(String, String, PathBuf)> = entries
        .flatten()
        .filter(|entry| entry.file_type().is_ok_and(|kind| kind.is_file()))
        .filter_map(|entry| {
            let name = entry.file_name().into_string().ok()?;
            let stamp = snapshot_timestamp(&name)?.to_owned();
            Some((stamp, name, entry.path()))
        })
        .collect();
    snapshots.sort();
    let excess = snapshots.len().saturating_sub(settings.retention);
    for (_, name, path) in snapshots.into_iter().take(excess) {
        if let Err(error) = std::fs::remove_file(&path) {
            tracing::warn!(component = "backup", file = %name, %error, "db.pre_migrate_backup.prune_failed");
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use sqlx::sqlite::SqlitePoolOptions;

    async fn migrator(dir: &Path, files: &[(&str, &str)]) -> Migrator {
        std::fs::create_dir_all(dir).unwrap();
        for (name, sql) in files {
            std::fs::write(dir.join(name), sql).unwrap();
        }
        Migrator::new(dir).await.unwrap()
    }

    async fn open(path: &Path) -> SqlitePool {
        let options = SqliteConnectOptions::new()
            .filename(path)
            .create_if_missing(true)
            .journal_mode(SqliteJournalMode::Wal);
        SqlitePoolOptions::new()
            .max_connections(2)
            .connect_with(options)
            .await
            .unwrap()
    }

    const M1: (&str, &str) = (
        "0001_a.sql",
        "CREATE TABLE a (id INTEGER PRIMARY KEY, v TEXT);",
    );
    const M2: (&str, &str) = ("0002_b.sql", "CREATE TABLE b (id INTEGER PRIMARY KEY);");

    fn names(dir: &Path) -> Vec<String> {
        let mut names: Vec<String> = std::fs::read_dir(dir)
            .unwrap()
            .map(|entry| entry.unwrap().file_name().into_string().unwrap())
            .collect();
        names.sort();
        names
    }

    #[tokio::test]
    async fn fresh_database_needs_no_backup() {
        let tmp = tempfile::tempdir().unwrap();
        let pool = open(&tmp.path().join("db.sqlite3")).await;
        let m = migrator(&tmp.path().join("m"), &[M1, M2]).await;
        let settings = PreMigrateBackup::new(tmp.path().join("backups"));
        assert!(
            backup_if_pending(&pool, &m, &settings)
                .await
                .unwrap()
                .is_none()
        );
        assert!(!settings.dir.exists());
    }

    #[tokio::test]
    async fn up_to_date_database_needs_no_backup() {
        let tmp = tempfile::tempdir().unwrap();
        let pool = open(&tmp.path().join("db.sqlite3")).await;
        let m = migrator(&tmp.path().join("m"), &[M1, M2]).await;
        m.run(&pool).await.unwrap();
        let settings = PreMigrateBackup::new(tmp.path().join("backups"));
        assert!(
            backup_if_pending(&pool, &m, &settings)
                .await
                .unwrap()
                .is_none()
        );
        assert!(!settings.dir.exists());
    }

    #[tokio::test]
    async fn pending_migrations_are_backed_up_before_migrating() {
        let tmp = tempfile::tempdir().unwrap();
        let pool = open(&tmp.path().join("db.sqlite3")).await;
        let old = migrator(&tmp.path().join("old"), &[M1]).await;
        let new = migrator(&tmp.path().join("new"), &[M1, M2]).await;
        old.run(&pool).await.unwrap();
        // Keep data in the WAL: it must still be in the snapshot.
        sqlx::query("PRAGMA wal_autocheckpoint = 0")
            .execute(&pool)
            .await
            .unwrap();
        sqlx::query("INSERT INTO a (v) VALUES ('in-wal')")
            .execute(&pool)
            .await
            .unwrap();
        let wal = tmp.path().join("db.sqlite3-wal");
        assert!(std::fs::metadata(&wal).unwrap().len() > 0);

        let settings = PreMigrateBackup::new(tmp.path().join("backups"));
        let created = backup_if_pending(&pool, &new, &settings)
            .await
            .unwrap()
            .unwrap();
        let name = created.path.file_name().unwrap().to_str().unwrap();
        assert!(name.starts_with("pre-migrate-0001-to-0002-"), "{name}");
        assert!(name.ends_with(".sqlite3"));
        assert_eq!(names(&settings.dir), vec![name.to_owned()]);
        assert!(created.bytes > 0);
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            let mode = std::fs::metadata(&created.path)
                .unwrap()
                .permissions()
                .mode();
            assert_eq!(mode & 0o777, 0o600);
        }

        // The snapshot is a valid database with the WAL-resident row, still
        // at the old schema, while the live database is not migrated yet.
        let copy = open(&created.path).await;
        let value: String = sqlx::query_scalar("SELECT v FROM a")
            .fetch_one(&copy)
            .await
            .unwrap();
        assert_eq!(value, "in-wal");
        let tables: i64 = sqlx::query_scalar("SELECT count(*) FROM sqlite_master WHERE name = 'b'")
            .fetch_one(&copy)
            .await
            .unwrap();
        assert_eq!(tables, 0);
        copy.close().await;

        new.run(&pool).await.unwrap();
        assert!(
            backup_if_pending(&pool, &new, &settings)
                .await
                .unwrap()
                .is_none()
        );
        assert_eq!(names(&settings.dir).len(), 1);
    }

    #[tokio::test]
    async fn unwritable_backup_dir_is_an_error_and_leaves_the_database_alone() {
        let tmp = tempfile::tempdir().unwrap();
        let pool = open(&tmp.path().join("db.sqlite3")).await;
        let old = migrator(&tmp.path().join("old"), &[M1]).await;
        let new = migrator(&tmp.path().join("new"), &[M1, M2]).await;
        old.run(&pool).await.unwrap();
        // A regular file where the directory should be: fails even as root.
        let blocker = tmp.path().join("blocker");
        std::fs::write(&blocker, b"x").unwrap();
        let settings = PreMigrateBackup::new(blocker.join("backups"));
        let error = migrate_for_test(&pool, &new, Some(&settings))
            .await
            .unwrap_err();
        assert!(error.to_string().contains("backup directory"), "{error}");
        let applied: i64 = sqlx::query_scalar("SELECT count(*) FROM _sqlx_migrations")
            .fetch_one(&pool)
            .await
            .unwrap();
        assert_eq!(applied, 1);
        let b: i64 = sqlx::query_scalar("SELECT count(*) FROM sqlite_master WHERE name = 'b'")
            .fetch_one(&pool)
            .await
            .unwrap();
        assert_eq!(b, 0);
    }

    async fn migrate_for_test(
        pool: &SqlitePool,
        migrator: &Migrator,
        settings: Option<&PreMigrateBackup>,
    ) -> Result<(), crate::db::DatabaseError> {
        crate::db::migrate_with_backup(pool, migrator, settings).await
    }

    #[tokio::test]
    async fn disabled_backup_migrates_without_a_snapshot() {
        let tmp = tempfile::tempdir().unwrap();
        let pool = open(&tmp.path().join("db.sqlite3")).await;
        let old = migrator(&tmp.path().join("old"), &[M1]).await;
        let new = migrator(&tmp.path().join("new"), &[M1, M2]).await;
        old.run(&pool).await.unwrap();
        migrate_for_test(&pool, &new, None).await.unwrap();
        assert!(!tmp.path().join("backups").exists());
    }

    #[tokio::test]
    async fn real_migrations_on_a_fresh_file_database_make_no_backup() {
        let tmp = tempfile::tempdir().unwrap();
        let url = format!("sqlite:{}", tmp.path().join("db.sqlite3").display());
        let backups = tmp.path().join("backups");
        for _ in 0..2 {
            let pool =
                crate::db::connect_with_backup(&url, Some(PreMigrateBackup::new(backups.clone())))
                    .await
                    .unwrap();
            pool.close().await;
        }
        assert!(!backups.exists());
    }

    #[test]
    fn pruning_only_touches_pre_migrate_snapshots() {
        let tmp = tempfile::tempdir().unwrap();
        let keep = [
            "stricknani-20260101-000000.tar.gz",
            "predeploy-1.7.1-20260101-000000.sqlite3",
            "pre-migrate-notes.sqlite3",
            "pre-migrate-0001-to-0002-20260101-000000.sqlite3.partial",
            "other.sqlite3",
        ];
        for name in keep {
            std::fs::write(tmp.path().join(name), b"x").unwrap();
        }
        let snapshots: Vec<String> = (1..=5)
            .map(|day| format!("pre-migrate-0001-to-0002-2026010{day}-000000.sqlite3"))
            .collect();
        for name in &snapshots {
            std::fs::write(tmp.path().join(name), b"x").unwrap();
        }
        let mut settings = PreMigrateBackup::new(tmp.path());
        settings.retention = 2;
        prune(&settings);
        let mut expected: Vec<String> = keep.iter().map(|name| (*name).to_owned()).collect();
        expected.extend(snapshots[3..].iter().cloned());
        expected.sort();
        assert_eq!(names(tmp.path()), expected);
    }

    #[test]
    fn settings_come_from_the_environment() {
        let db = Path::new("/var/lib/stricknani/stricknani.sqlite3");
        let get = |pairs: &'static [(&'static str, &'static str)]| {
            PreMigrateBackup::from_lookup(
                |key| {
                    pairs
                        .iter()
                        .find(|(name, _)| *name == key)
                        .map(|(_, value)| (*value).to_owned())
                },
                db,
            )
        };
        assert_eq!(
            get(&[]).unwrap(),
            Some(PreMigrateBackup {
                dir: PathBuf::from("/var/lib/stricknani/backups"),
                retention: 10
            })
        );
        assert_eq!(
            get(&[("STRICKNANI_PRE_MIGRATE_BACKUP", "false")]).unwrap(),
            None
        );
        let custom = get(&[
            ("STRICKNANI_BACKUP_DIR", "/srv/b"),
            ("STRICKNANI_PRE_MIGRATE_BACKUP_RETENTION", "3"),
        ])
        .unwrap()
        .unwrap();
        assert_eq!(custom.dir, PathBuf::from("/srv/b"));
        assert_eq!(custom.retention, 3);
        assert!(get(&[("STRICKNANI_PRE_MIGRATE_BACKUP", "maybe")]).is_err());
        assert!(get(&[("STRICKNANI_PRE_MIGRATE_BACKUP_RETENTION", "0")]).is_err());
    }
}
