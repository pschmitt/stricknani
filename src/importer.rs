//! Import a verified, immutable copy of the legacy SQLite database and media tree.
//!
//! This operation publishes a complete Rust data directory atomically. It never opens a legacy
//! database for writing, and never replaces an existing destination.

use std::collections::{HashMap, HashSet};
use std::ffi::OsStr;
use std::fs::{self, File, OpenOptions};
use std::io::{BufReader, Read, Write};
use std::path::{Component, Path, PathBuf};

use chrono::{DateTime, NaiveDateTime, SecondsFormat, Utc};
use image::{DynamicImage, ImageFormat, ImageReader, Limits};
use rand::TryRng;
use rand::rngs::SysRng;
use serde::Serialize;
use sha2::{Digest, Sha256};
use sqlx::sqlite::{SqliteConnectOptions, SqlitePoolOptions};
use sqlx::{FromRow, Row, Sqlite, SqlitePool, Transaction};
use tempfile::{Builder, TempDir};
use thiserror::Error;

const MAX_IMAGE_PIXELS: u64 = 64_000_000;
const MAX_IMAGE_DIMENSION: u32 = 20_000;
const MAX_IMAGE_ALLOCATION: u64 = 256 * 1024 * 1024;
const COPY_BUFFER_SIZE: usize = 1024 * 1024;

/// Source snapshot and target directory for a one-time migration.
#[derive(Clone, Debug)]
pub struct ImportOptions {
    /// A self-contained copy of the legacy SQLite database, with no WAL sidecars.
    pub source_db: PathBuf,
    /// A copied legacy media root containing the `projects`, `yarns`, and `users` trees.
    pub source_media: PathBuf,
    /// A new directory to atomically publish with `database.sqlite3`, `media/`, and a report.
    pub destination: PathBuf,
}

/// Aggregate-only report. It contains no account identifiers, filenames, hashes, or row values.
#[derive(Clone, Debug, Default, Eq, PartialEq, Serialize)]
pub struct ImportReport {
    pub users: usize,
    pub api_tokens: usize,
    pub categories: usize,
    pub synthesized_categories: usize,
    pub yarns: usize,
    pub projects: usize,
    pub project_steps: usize,
    pub project_yarns: usize,
    pub project_favorites: usize,
    pub yarn_favorites: usize,
    pub project_images: usize,
    pub yarn_images: usize,
    pub attachments: usize,
    pub user_avatars: usize,
    pub audit_events: usize,
    pub pending_archive_jobs: usize,
    pub deletion_events: usize,
    pub reused_media_aliases: usize,
    pub reused_thumbnail_aliases: usize,
}

/// A redacted failure from validation, import, or publication.
#[derive(Debug, Error, Eq, PartialEq)]
pub enum ImportError {
    #[error("migration paths must be absolute canonical paths with a new destination")]
    InvalidPath,
    #[error("destination already exists")]
    DestinationExists,
    #[error("legacy SQLite snapshot is not a self-contained readable snapshot")]
    SourceOpen,
    #[error("legacy snapshot changed while it was being imported")]
    SourceChanged,
    #[error("legacy snapshot schema or contents are invalid")]
    SourceData,
    #[error(
        "source identity collisions prevent a lossless import (email={emails}, category={categories})"
    )]
    IdentityCollision { emails: usize, categories: usize },
    #[error("source contains {count} invalid relationships")]
    InvalidRelationships { count: usize },
    #[error("source contains {count} missing or unsafe media files")]
    InvalidMedia { count: usize },
    #[error("could not create private staging storage")]
    Staging,
    #[error("could not initialize or write the Rust database")]
    Database,
    #[error("could not copy or validate legacy media")]
    Media,
    #[error("atomic no-replace publication is unsupported on this operating system")]
    UnsupportedPublication,
    #[error("could not atomically publish the imported data directory")]
    Publish,
}

#[derive(Debug, FromRow)]
struct UserRow {
    id: i64,
    email: String,
    hashed_password: String,
    is_active: bool,
    is_admin: bool,
    token_version: i64,
    created_at: String,
    profile_image: Option<String>,
}

#[derive(Debug, FromRow)]
struct TokenRow {
    id: i64,
    name: String,
    token_hash: String,
    created_at: String,
    last_used_at: Option<String>,
    expires_at: Option<String>,
    user_id: i64,
}

#[derive(Debug, FromRow)]
struct CategoryRow {
    id: i64,
    name: String,
    created_at: String,
    user_id: i64,
}

#[derive(Debug, FromRow)]
struct YarnRow {
    id: i64,
    name: String,
    description: Option<String>,
    brand: Option<String>,
    colorway: Option<String>,
    dye_lot: Option<String>,
    fiber_content: Option<String>,
    weight_category: Option<String>,
    recommended_needles: Option<String>,
    weight_grams: Option<i64>,
    length_meters: Option<i64>,
    notes: Option<String>,
    link: Option<String>,
    link_archive: Option<String>,
    link_archive_requested_at: Option<String>,
    link_archive_failed: bool,
    is_ai_enhanced: bool,
    created_at: String,
    updated_at: String,
    owner_id: i64,
}

#[derive(Debug, FromRow)]
struct ProjectRow {
    id: i64,
    name: String,
    category: Option<String>,
    yarn: Option<String>,
    needles: Option<String>,
    stitch_sample: Option<String>,
    description: Option<String>,
    notes: Option<String>,
    other_materials: Option<String>,
    tags: Option<String>,
    link: Option<String>,
    link_archive: Option<String>,
    link_archive_requested_at: Option<String>,
    link_archive_failed: bool,
    is_ai_enhanced: bool,
    created_at: String,
    updated_at: String,
    owner_id: i64,
}

#[derive(Debug, FromRow)]
struct StepRow {
    id: i64,
    title: String,
    description: Option<String>,
    step_number: i64,
    created_at: String,
    project_id: i64,
}

#[derive(Debug, FromRow)]
struct PairRow {
    user_id: i64,
    entity_id: i64,
}

#[derive(Debug, FromRow)]
struct ProjectYarnRow {
    project_id: i64,
    yarn_id: i64,
}

#[derive(Debug, FromRow)]
struct ImageRow {
    id: i64,
    filename: String,
    original_filename: String,
    image_type: String,
    alt_text: String,
    is_title_image: bool,
    is_stitch_sample: Option<bool>,
    width: Option<i64>,
    height: Option<i64>,
    created_at: String,
    project_id: i64,
    step_id: Option<i64>,
}

#[derive(Debug, FromRow)]
struct AttachmentRow {
    id: i64,
    filename: String,
    original_filename: String,
    content_type: String,
    size_bytes: i64,
    created_at: String,
    project_id: i64,
}

#[derive(Debug, FromRow)]
struct YarnImageRow {
    id: i64,
    filename: String,
    original_filename: String,
    alt_text: String,
    is_primary: bool,
    created_at: String,
    yarn_id: i64,
}

#[derive(Debug, FromRow)]
struct AuditRow {
    id: i64,
    actor_user_id: i64,
    entity_type: String,
    entity_id: i64,
    action: String,
    details: Option<String>,
    created_at: String,
}

#[derive(Clone, Debug)]
struct SourceMedia {
    source: &'static str,
    id: i64,
    owner_id: i64,
    subdir: &'static str,
    parent_id: i64,
    kind: &'static str,
    filename: String,
    original_filename: String,
    content_type: Option<String>,
    size_bytes: Option<i64>,
    alt_text: String,
    is_primary: bool,
    position: i64,
    is_project_cover: bool,
    width: Option<i64>,
    height: Option<i64>,
    created_at: String,
    project_id: Option<i64>,
    yarn_id: Option<i64>,
    step_id: Option<i64>,
    legacy_image_type: Option<String>,
    is_stitch_sample: Option<bool>,
    legacy_is_title_image: Option<bool>,
}

#[derive(Debug)]
struct PreparedMedia {
    source: SourceMedia,
    physical_filename: String,
    content_type: String,
    size_bytes: i64,
    width: Option<i64>,
    height: Option<i64>,
    is_safe_inline: bool,
    legacy_thumbnail: Option<String>,
    digest: [u8; 32],
}

#[derive(Debug)]
struct PreparedAvatar {
    user_id: i64,
    filename: String,
    content_type: &'static str,
    width: u32,
    height: u32,
}

/// Import a copied SQLite snapshot into a new Rust data directory and publish it atomically.
///
/// The source database is opened with SQLite read-only and immutable flags. The destination is
/// first constructed in a private temporary directory beside its final path; errors remove only
/// that staging directory. Existing destinations are never replaced.
pub async fn import_snapshot(options: ImportOptions) -> Result<ImportReport, ImportError> {
    let paths = validate_paths(&options)?;
    let source_fingerprint = fingerprint_inputs_async(&paths.source_db, &paths.source_media)
        .await
        .map_err(|_| ImportError::SourceOpen)?;
    let source_pool = open_source(&paths.source_db).await?;
    let source_result = read_source(&source_pool).await;
    source_pool.close().await;
    let source = source_result?;
    validate_source(&source)?;

    let mut staging = create_staging_dir(&paths.destination_parent, &paths.destination_name)?;
    set_private_permissions(staging.path()).map_err(|_| ImportError::Staging)?;
    let media_root = staging.path().join("media");
    create_private_dir(&media_root).map_err(|_| ImportError::Staging)?;
    let database_path = staging.path().join("database.sqlite3");
    create_private_file(&database_path).map_err(|_| ImportError::Staging)?;

    let pool = crate::db::connect_file(&database_path)
        .await
        .map_err(|_| ImportError::Database)?;
    let import_result = import_rows(&pool, &source, &paths.source_media, &media_root).await;
    if import_result.is_err() {
        pool.close().await;
        return import_result;
    }
    let report = import_result?;
    pool.close().await;

    if sidecars_exist(&paths.source_db)
        || fingerprint_inputs_async(&paths.source_db, &paths.source_media)
            .await
            .map_err(|_| ImportError::SourceChanged)?
            != source_fingerprint
    {
        return Err(ImportError::SourceChanged);
    }

    write_report(staging.path(), &report)?;
    sync_tree(staging.path()).map_err(|_| ImportError::Staging)?;
    publish_noreplace(
        &mut staging,
        &paths.destination_parent,
        &paths.destination_name,
    )?;
    Ok(report)
}

struct ValidatedPaths {
    source_db: PathBuf,
    source_media: PathBuf,
    destination_parent: PathBuf,
    destination_name: std::ffi::OsString,
}

fn validate_paths(options: &ImportOptions) -> Result<ValidatedPaths, ImportError> {
    if !options.source_db.is_absolute()
        || !options.source_media.is_absolute()
        || !options.destination.is_absolute()
        || options
            .source_db
            .components()
            .any(|component| matches!(component, Component::ParentDir))
        || options
            .source_media
            .components()
            .any(|component| matches!(component, Component::ParentDir))
        || options
            .destination
            .components()
            .any(|component| matches!(component, Component::ParentDir))
    {
        return Err(ImportError::InvalidPath);
    }

    let source_db = fs::canonicalize(&options.source_db).map_err(|_| ImportError::InvalidPath)?;
    let source_media =
        fs::canonicalize(&options.source_media).map_err(|_| ImportError::InvalidPath)?;
    if source_db != options.source_db
        || source_media != options.source_media
        || !source_db.is_file()
        || !source_media.is_dir()
    {
        return Err(ImportError::InvalidPath);
    }
    if sidecars_exist(&source_db) {
        return Err(ImportError::SourceOpen);
    }

    let destination_name = options
        .destination
        .file_name()
        .filter(|name| *name != OsStr::new(".") && *name != OsStr::new(".."))
        .ok_or(ImportError::InvalidPath)?;
    let parent = options
        .destination
        .parent()
        .filter(|parent| parent.is_absolute())
        .ok_or(ImportError::InvalidPath)?;
    let destination_parent = fs::canonicalize(parent).map_err(|_| ImportError::InvalidPath)?;
    if destination_parent != parent {
        return Err(ImportError::InvalidPath);
    }
    if destination_parent.starts_with(&source_media) {
        return Err(ImportError::InvalidPath);
    }
    if !destination_parent.is_dir()
        || destination_parent.join(destination_name).exists()
        || destination_parent
            .join(destination_name)
            .symlink_metadata()
            .is_ok()
    {
        return Err(ImportError::DestinationExists);
    }
    Ok(ValidatedPaths {
        source_db,
        source_media,
        destination_parent,
        destination_name: destination_name.to_os_string(),
    })
}

fn sidecar_path(database: &Path, suffix: &str) -> PathBuf {
    let mut value = database.as_os_str().to_os_string();
    value.push(suffix);
    PathBuf::from(value)
}

fn sidecars_exist(database: &Path) -> bool {
    fs::symlink_metadata(sidecar_path(database, "-wal")).is_ok()
        || fs::symlink_metadata(sidecar_path(database, "-shm")).is_ok()
}

async fn fingerprint_inputs_async(database: &Path, media_root: &Path) -> std::io::Result<[u8; 32]> {
    let database = database.to_path_buf();
    let media_root = media_root.to_path_buf();
    tokio::task::spawn_blocking(move || fingerprint_inputs(&database, &media_root))
        .await
        .map_err(|_| std::io::Error::other("source fingerprint task failed"))?
}

fn fingerprint_inputs(database: &Path, media_root: &Path) -> std::io::Result<[u8; 32]> {
    let mut hash = Sha256::new();
    hash.update(b"stricknani-import-input-v1\0");
    hash.update(b"database\0");
    hash_file_into(database, &mut hash)?;
    hash.update(b"media\0");
    hash_directory_into(media_root, media_root, &mut hash)?;
    Ok(hash.finalize().into())
}

fn hash_directory_into(root: &Path, directory: &Path, hash: &mut Sha256) -> std::io::Result<()> {
    let mut entries = fs::read_dir(directory)?.collect::<Result<Vec<_>, _>>()?;
    entries.sort_by_key(|entry| entry.file_name());
    for entry in entries {
        let path = entry.path();
        let metadata = fs::symlink_metadata(&path)?;
        if metadata.file_type().is_symlink() {
            return Err(std::io::Error::other("symlink in source media tree"));
        }
        let relative = path
            .strip_prefix(root)
            .map_err(|_| std::io::Error::other("source path escaped media root"))?;
        hash.update(relative.to_string_lossy().as_bytes());
        hash.update([0]);
        if metadata.is_dir() {
            hash.update(b"directory\0");
            hash_directory_into(root, &path, hash)?;
        } else if metadata.is_file() {
            hash.update(b"file\0");
            hash.update(metadata.len().to_le_bytes());
            hash_modified_time(&metadata, hash);
            hash_file_into(&path, hash)?;
        } else {
            return Err(std::io::Error::other("unsupported source media entry"));
        }
    }
    Ok(())
}

fn hash_modified_time(metadata: &fs::Metadata, hash: &mut Sha256) {
    if let Ok(modified) = metadata.modified()
        && let Ok(elapsed) = modified.duration_since(std::time::UNIX_EPOCH)
    {
        hash.update(elapsed.as_secs().to_le_bytes());
        hash.update(elapsed.subsec_nanos().to_le_bytes());
    }
}

fn hash_file_into(path: &Path, hash: &mut Sha256) -> std::io::Result<()> {
    let mut file = open_readonly_nofollow(path)?;
    let before = file.metadata()?;
    if !before.is_file() {
        return Err(std::io::Error::other("source is not a regular file"));
    }
    hash.update(before.len().to_le_bytes());
    hash_modified_time(&before, hash);
    let mut buffer = vec![0_u8; COPY_BUFFER_SIZE];
    loop {
        let count = file.read(&mut buffer)?;
        if count == 0 {
            break;
        }
        hash.update(&buffer[..count]);
    }
    let after = file.metadata()?;
    if before.len() != after.len() || before.modified().ok() != after.modified().ok() {
        return Err(std::io::Error::other("source file changed while reading"));
    }
    Ok(())
}

fn open_readonly_nofollow(path: &Path) -> std::io::Result<File> {
    #[cfg(target_os = "linux")]
    {
        use rustix::fs::{CWD, Mode, OFlags, openat};

        let descriptor = openat(
            CWD,
            path,
            OFlags::RDONLY | OFlags::NOFOLLOW | OFlags::CLOEXEC,
            Mode::empty(),
        )
        .map_err(std::io::Error::from)?;
        Ok(File::from(descriptor))
    }
    #[cfg(not(target_os = "linux"))]
    {
        OpenOptions::new().read(true).open(path)
    }
}

fn sync_tree(path: &Path) -> std::io::Result<()> {
    let metadata = fs::symlink_metadata(path)?;
    if metadata.file_type().is_symlink() {
        return Err(std::io::Error::other("symlink in staging directory"));
    }
    if metadata.is_dir() {
        for entry in fs::read_dir(path)? {
            sync_tree(&entry?.path())?;
        }
    }
    File::open(path)?.sync_all()
}

async fn open_source(path: &Path) -> Result<SqlitePool, ImportError> {
    let options = SqliteConnectOptions::new()
        .filename(path)
        .read_only(true)
        .immutable(true)
        .foreign_keys(false);
    SqlitePoolOptions::new()
        .max_connections(1)
        .connect_with(options)
        .await
        .map_err(|_| ImportError::SourceOpen)
}

#[derive(Default)]
struct SourceData {
    users: Vec<UserRow>,
    tokens: Vec<TokenRow>,
    categories: Vec<CategoryRow>,
    yarns: Vec<YarnRow>,
    projects: Vec<ProjectRow>,
    steps: Vec<StepRow>,
    project_yarns: Vec<ProjectYarnRow>,
    project_favorites: Vec<PairRow>,
    yarn_favorites: Vec<PairRow>,
    images: Vec<ImageRow>,
    attachments: Vec<AttachmentRow>,
    yarn_images: Vec<YarnImageRow>,
    audits: Vec<AuditRow>,
}

async fn read_source(pool: &SqlitePool) -> Result<SourceData, ImportError> {
    let mut transaction = pool.begin().await.map_err(|_| ImportError::SourceData)?;
    let check: String = sqlx::query_scalar("PRAGMA integrity_check")
        .fetch_one(&mut *transaction)
        .await
        .map_err(|_| ImportError::SourceData)?;
    if check != "ok" {
        return Err(ImportError::SourceData);
    }
    let foreign_key_issues = sqlx::query("PRAGMA foreign_key_check")
        .fetch_all(&mut *transaction)
        .await
        .map_err(|_| ImportError::SourceData)?;
    let foreign_key_issues = foreign_key_issues
        .into_iter()
        .filter(|row| {
            let table: String = row.get("table");
            let parent: String = row.get("parent");
            table != "audit_logs" || parent != "users"
        })
        .count();
    if foreign_key_issues > 0 {
        return Err(ImportError::InvalidRelationships {
            count: foreign_key_issues,
        });
    }

    let data = SourceData {
        users: sqlx::query_as(
        "SELECT id,email,hashed_password,is_active,is_admin,token_version,created_at,profile_image FROM users ORDER BY id",
    )
    .fetch_all(&mut *transaction)
    .await
    .map_err(|_| ImportError::SourceData)?,
        tokens: sqlx::query_as(
        "SELECT id,name,token_hash,created_at,last_used_at,expires_at,user_id FROM api_tokens ORDER BY id",
    )
    .fetch_all(&mut *transaction)
    .await
    .map_err(|_| ImportError::SourceData)?,
        categories: sqlx::query_as("SELECT id,name,created_at,user_id FROM categories ORDER BY id")
            .fetch_all(&mut *transaction)
            .await
            .map_err(|_| ImportError::SourceData)?,
        yarns: sqlx::query_as(
        "SELECT id,name,description,brand,colorway,dye_lot,fiber_content,weight_category,recommended_needles,weight_grams,length_meters,notes,link,link_archive,link_archive_requested_at,link_archive_failed,is_ai_enhanced,created_at,updated_at,owner_id FROM yarns ORDER BY id",
    )
    .fetch_all(&mut *transaction)
    .await
    .map_err(|_| ImportError::SourceData)?,
        projects: sqlx::query_as(
        "SELECT id,name,category,yarn,needles,stitch_sample,description,notes,other_materials,tags,link,link_archive,link_archive_requested_at,link_archive_failed,is_ai_enhanced,created_at,updated_at,owner_id FROM projects ORDER BY id",
    )
    .fetch_all(&mut *transaction)
    .await
    .map_err(|_| ImportError::SourceData)?,
        steps: sqlx::query_as(
        "SELECT id,title,description,step_number,created_at,project_id FROM steps ORDER BY id",
    )
    .fetch_all(&mut *transaction)
    .await
    .map_err(|_| ImportError::SourceData)?,
        project_yarns: sqlx::query_as("SELECT project_id,yarn_id FROM project_yarns ORDER BY project_id,yarn_id")
            .fetch_all(&mut *transaction)
            .await
            .map_err(|_| ImportError::SourceData)?,
        project_favorites: sqlx::query_as(
        "SELECT user_id,project_id AS entity_id FROM user_favorites ORDER BY user_id,project_id",
    )
    .fetch_all(&mut *transaction)
    .await
    .map_err(|_| ImportError::SourceData)?,
        yarn_favorites: sqlx::query_as(
        "SELECT user_id,yarn_id AS entity_id FROM user_favorite_yarns ORDER BY user_id,yarn_id",
    )
    .fetch_all(&mut *transaction)
    .await
    .map_err(|_| ImportError::SourceData)?,
        images: sqlx::query_as(
        "SELECT id,filename,original_filename,image_type,alt_text,is_title_image,is_stitch_sample,width,height,created_at,project_id,step_id FROM images ORDER BY id",
    )
    .fetch_all(&mut *transaction)
    .await
    .map_err(|_| ImportError::SourceData)?,
        attachments: sqlx::query_as(
        "SELECT id,filename,original_filename,content_type,size_bytes,created_at,project_id FROM attachments ORDER BY id",
    )
    .fetch_all(&mut *transaction)
    .await
    .map_err(|_| ImportError::SourceData)?,
        yarn_images: sqlx::query_as(
        "SELECT id,filename,original_filename,alt_text,is_primary,created_at,yarn_id FROM yarn_images ORDER BY id",
    )
    .fetch_all(&mut *transaction)
    .await
    .map_err(|_| ImportError::SourceData)?,
        audits: sqlx::query_as(
        "SELECT id,actor_user_id,entity_type,entity_id,action,details,created_at FROM audit_logs ORDER BY id",
    )
    .fetch_all(&mut *transaction)
    .await
    .map_err(|_| ImportError::SourceData)?,
    };
    transaction
        .commit()
        .await
        .map_err(|_| ImportError::SourceData)?;
    Ok(data)
}

fn validate_source(data: &SourceData) -> Result<(), ImportError> {
    let user_ids: HashSet<i64> = data.users.iter().map(|row| row.id).collect();
    let project_owners: HashMap<i64, i64> = data
        .projects
        .iter()
        .map(|row| (row.id, row.owner_id))
        .collect();
    let yarn_owners: HashMap<i64, i64> = data
        .yarns
        .iter()
        .map(|row| (row.id, row.owner_id))
        .collect();
    let project_ids: HashSet<i64> = project_owners.keys().copied().collect();
    let yarn_ids: HashSet<i64> = yarn_owners.keys().copied().collect();
    let step_projects: HashMap<i64, i64> = data
        .steps
        .iter()
        .map(|row| (row.id, row.project_id))
        .collect();
    let mut invalid = 0;

    let mut email_keys = HashSet::new();
    let mut email_collisions = 0;
    for user in &data.users {
        let normalized_email = normalize_email(&user.email);
        if !email_keys.insert(normalized_email.clone()) {
            email_collisions += 1;
        }
        if user.id <= 0
            || user.email_keys_invalid()
            || normalized_email.len() > MAX_EMAIL_BYTES
            || user.hashed_password.is_empty()
            || user.token_version < 0
        {
            invalid += 1;
        }
    }

    let mut category_keys = HashSet::new();
    let mut category_by_name = HashMap::new();
    let mut category_collisions = 0;
    for category in &data.categories {
        let key = (category.user_id, category.name.to_ascii_lowercase());
        if !category_keys.insert(key) {
            category_collisions += 1;
        }
        if category.id <= 0 || category.name.is_empty() || !user_ids.contains(&category.user_id) {
            invalid += 1;
        }
        category_by_name.insert((category.user_id, category.name.clone()), category.id);
    }
    for project in &data.projects {
        if project.id <= 0 || !user_ids.contains(&project.owner_id) {
            invalid += 1;
        }
        if let Some(label) = &project.category {
            let key = (project.owner_id, label.to_ascii_lowercase());
            if !category_keys.contains(&key) {
                category_keys.insert(key);
                category_by_name.insert((project.owner_id, label.clone()), 0);
            } else if !category_by_name.contains_key(&(project.owner_id, label.clone())) {
                category_collisions += 1;
            }
        }
    }
    if email_collisions != 0 || category_collisions != 0 {
        return Err(ImportError::IdentityCollision {
            emails: email_collisions,
            categories: category_collisions,
        });
    }

    for token in &data.tokens {
        if token.id <= 0 || !user_ids.contains(&token.user_id) || token.token_hash.is_empty() {
            invalid += 1;
        }
    }
    for yarn in &data.yarns {
        if yarn.id <= 0 || !user_ids.contains(&yarn.owner_id) {
            invalid += 1;
        }
    }
    for step in &data.steps {
        if step.id <= 0 || !project_ids.contains(&step.project_id) {
            invalid += 1;
        }
    }
    for link in &data.project_yarns {
        match (
            project_owners.get(&link.project_id),
            yarn_owners.get(&link.yarn_id),
        ) {
            (Some(project_owner), Some(yarn_owner)) if project_owner == yarn_owner => {}
            _ => invalid += 1,
        }
    }
    for favorite in &data.project_favorites {
        if !user_ids.contains(&favorite.user_id) || !project_ids.contains(&favorite.entity_id) {
            invalid += 1;
        }
    }
    for favorite in &data.yarn_favorites {
        if !user_ids.contains(&favorite.user_id) || !yarn_ids.contains(&favorite.entity_id) {
            invalid += 1;
        }
    }
    for image in &data.images {
        if !project_ids.contains(&image.project_id)
            || image.step_id.is_some_and(|step_id| {
                step_projects.get(&step_id).copied() != Some(image.project_id)
            })
        {
            invalid += 1;
        }
    }
    for attachment in &data.attachments {
        if !project_ids.contains(&attachment.project_id) {
            invalid += 1;
        }
    }
    for photo in &data.yarn_images {
        if !yarn_ids.contains(&photo.yarn_id) {
            invalid += 1;
        }
    }

    if invalid > 0 {
        return Err(ImportError::InvalidRelationships { count: invalid });
    }
    Ok(())
}

impl UserRow {
    fn email_keys_invalid(&self) -> bool {
        let email = normalize_email(&self.email);
        email.is_empty() || email.contains(char::is_whitespace)
    }
}

const MAX_EMAIL_BYTES: usize = 320;

fn normalize_email(email: &str) -> String {
    email.trim().to_lowercase()
}

async fn import_rows(
    pool: &SqlitePool,
    data: &SourceData,
    source_media: &Path,
    target_media: &Path,
) -> Result<ImportReport, ImportError> {
    let media_records = source_media_records(data);
    let avatar_refs: Vec<(i64, String)> = data
        .users
        .iter()
        .filter_map(|user| {
            user.profile_image
                .as_ref()
                .map(|filename| (user.id, filename.clone()))
        })
        .collect();
    let media_root = source_media.to_path_buf();
    let media_records = tokio::task::spawn_blocking(move || {
        validate_media_paths(&media_records, &avatar_refs, &media_root)?;
        Ok::<_, ImportError>(media_records)
    })
    .await
    .map_err(|_| ImportError::InvalidMedia { count: 1 })??;

    let mut tx = pool.begin().await.map_err(|_| ImportError::Database)?;
    let mut report = ImportReport {
        users: data.users.len(),
        api_tokens: data.tokens.len(),
        categories: data.categories.len(),
        yarns: data.yarns.len(),
        projects: data.projects.len(),
        project_steps: data.steps.len(),
        project_yarns: data.project_yarns.len(),
        project_favorites: data.project_favorites.len(),
        yarn_favorites: data.yarn_favorites.len(),
        project_images: data.images.len(),
        yarn_images: data.yarn_images.len(),
        attachments: data.attachments.len(),
        audit_events: data.audits.len(),
        ..ImportReport::default()
    };

    insert_users(&mut tx, &data.users).await?;
    insert_tokens(&mut tx, &data.tokens).await?;
    let category_ids = insert_categories(&mut tx, data, &mut report).await?;
    insert_yarns(&mut tx, &data.yarns).await?;
    insert_projects(&mut tx, &data.projects, &category_ids).await?;
    insert_steps(&mut tx, &data.steps).await?;
    insert_associations(&mut tx, data).await?;
    insert_audits_and_tombstones(&mut tx, data, &mut report).await?;

    let mut aliases: HashMap<(String, i64, String), [u8; 32]> = HashMap::new();
    let mut thumbnail_aliases = HashSet::new();
    for media in media_records {
        let source_root = source_media.to_path_buf();
        let target_root = target_media.to_path_buf();
        let mut prepared = tokio::task::spawn_blocking(move || {
            prepare_and_copy_media(&source_root, &target_root, media)
        })
        .await
        .map_err(|_| ImportError::Media)??;
        let alias_key = (
            prepared.source.subdir.to_owned(),
            prepared.source.parent_id,
            prepared.source.filename.clone(),
        );
        let reused_alias = match aliases.get(&alias_key) {
            Some(digest) if *digest == prepared.digest => true,
            Some(_) => return Err(ImportError::InvalidMedia { count: 1 }),
            None => {
                aliases.insert(alias_key, prepared.digest);
                false
            }
        };
        if reused_alias {
            report.reused_media_aliases += 1;
        } else if let Some(thumbnail) = &prepared.legacy_thumbnail {
            let thumbnail_key = (
                prepared.source.subdir.to_owned(),
                prepared.source.parent_id,
                thumbnail.clone(),
            );
            if !thumbnail_aliases.insert(thumbnail_key) {
                prepared.legacy_thumbnail = None;
                report.reused_thumbnail_aliases += 1;
            }
        }
        insert_media(&mut tx, &prepared, reused_alias).await?;
    }
    report.user_avatars = import_avatars(&mut tx, data, source_media, target_media).await?;
    // Preserve accepted legacy requests without resubmitting their save operation.
    // The candidate worker remains disabled unless explicitly configured at runtime.
    for (table, kind) in [("projects", "project"), ("yarns", "yarn")] {
        let statement = format!(
            "INSERT INTO archive_jobs (owner_id,entity_type,entity_id,source_url,save_accepted) \
             SELECT owner_id,?,id,link,1 FROM {table} \
             WHERE link IS NOT NULL AND trim(link) <> '' AND length(link) <= 2048 \
             AND archive_requested_at IS NOT NULL AND archive_failed = 0 \
             AND (link_archive IS NULL OR trim(link_archive) = '')"
        );
        report.pending_archive_jobs += sqlx::query(&statement)
            .bind(kind)
            .execute(&mut *tx)
            .await
            .map_err(|_| ImportError::Database)?
            .rows_affected() as usize;
    }
    tx.commit().await.map_err(|_| ImportError::Database)?;

    let foreign_key_issues = sqlx::query("PRAGMA foreign_key_check")
        .fetch_all(pool)
        .await
        .map_err(|_| ImportError::Database)?;
    if !foreign_key_issues.is_empty() {
        return Err(ImportError::InvalidRelationships {
            count: foreign_key_issues.len(),
        });
    }
    Ok(report)
}

fn validate_media_paths(
    media_records: &[SourceMedia],
    avatar_refs: &[(i64, String)],
    source_media: &Path,
) -> Result<(), ImportError> {
    let mut missing = 0;
    for media in media_records {
        if source_media_file(source_media, media.subdir, media.parent_id, &media.filename).is_err()
        {
            missing += 1;
        }
    }
    for (user_id, filename) in avatar_refs {
        if source_media_file(source_media, "users", *user_id, filename).is_err() {
            missing += 1;
        }
    }
    if missing > 0 {
        return Err(ImportError::InvalidMedia { count: missing });
    }
    Ok(())
}

fn source_media_file(
    root: &Path,
    subdir: &str,
    parent_id: i64,
    filename: &str,
) -> Result<PathBuf, ()> {
    let name = Path::new(filename);
    if parent_id <= 0
        || name.components().count() != 1
        || !matches!(name.components().next(), Some(Component::Normal(_)))
        || filename.contains(['/', '\\'])
        || filename.is_empty()
    {
        return Err(());
    }
    let parent = root.join(subdir).join(parent_id.to_string());
    let canonical_parent = fs::canonicalize(&parent).map_err(|_| ())?;
    if !canonical_parent.starts_with(root) || canonical_parent != parent {
        return Err(());
    }
    let path = parent.join(name);
    if fs::symlink_metadata(&path)
        .map_err(|_| ())?
        .file_type()
        .is_symlink()
    {
        return Err(());
    }
    let canonical_file = fs::canonicalize(&path).map_err(|_| ())?;
    if !canonical_file.starts_with(&canonical_parent) || !canonical_file.is_file() {
        return Err(());
    }
    Ok(canonical_file)
}

fn source_media_records(data: &SourceData) -> Vec<SourceMedia> {
    let project_owners: HashMap<i64, i64> = data
        .projects
        .iter()
        .map(|project| (project.id, project.owner_id))
        .collect();
    let yarn_owners: HashMap<i64, i64> = data
        .yarns
        .iter()
        .map(|yarn| (yarn.id, yarn.owner_id))
        .collect();
    let mut records =
        Vec::with_capacity(data.images.len() + data.attachments.len() + data.yarn_images.len());
    records.extend(data.images.iter().map(|row| SourceMedia {
        source: "images",
        id: row.id,
        owner_id: project_owners.get(&row.project_id).copied().unwrap_or(-1),
        subdir: "projects",
        parent_id: row.project_id,
        kind: if row.step_id.is_some() {
            "step"
        } else {
            "title"
        },
        filename: row.filename.clone(),
        original_filename: row.original_filename.clone(),
        content_type: None,
        size_bytes: None,
        alt_text: row.alt_text.clone(),
        // Legacy title-image state is kept independently: Python allowed multiple
        // title images, while the Rust `is_primary` index allows only one.
        is_primary: false,
        position: 0,
        is_project_cover: false,
        width: row.width,
        height: row.height,
        created_at: row.created_at.clone(),
        project_id: Some(row.project_id),
        yarn_id: None,
        step_id: row.step_id,
        legacy_image_type: Some(row.image_type.clone()),
        is_stitch_sample: row.is_stitch_sample,
        legacy_is_title_image: Some(row.is_title_image),
    }));
    records.extend(data.attachments.iter().map(|row| SourceMedia {
        source: "attachments",
        id: row.id,
        owner_id: project_owners.get(&row.project_id).copied().unwrap_or(-1),
        subdir: "projects",
        parent_id: row.project_id,
        kind: "attachment",
        filename: row.filename.clone(),
        original_filename: row.original_filename.clone(),
        content_type: Some(row.content_type.clone()),
        size_bytes: Some(row.size_bytes),
        alt_text: row.original_filename.clone(),
        is_primary: false,
        position: 0,
        is_project_cover: false,
        width: None,
        height: None,
        created_at: row.created_at.clone(),
        project_id: Some(row.project_id),
        yarn_id: None,
        step_id: None,
        legacy_image_type: None,
        is_stitch_sample: None,
        legacy_is_title_image: None,
    }));
    records.extend(data.yarn_images.iter().map(|row| SourceMedia {
        source: "yarn_images",
        id: row.id,
        owner_id: yarn_owners.get(&row.yarn_id).copied().unwrap_or(-1),
        subdir: "yarns",
        parent_id: row.yarn_id,
        kind: "photo",
        filename: row.filename.clone(),
        original_filename: row.original_filename.clone(),
        content_type: None,
        size_bytes: None,
        alt_text: row.alt_text.clone(),
        is_primary: row.is_primary,
        position: 0,
        is_project_cover: false,
        width: None,
        height: None,
        created_at: row.created_at.clone(),
        project_id: None,
        yarn_id: Some(row.yarn_id),
        step_id: None,
        legacy_image_type: None,
        is_stitch_sample: None,
        legacy_is_title_image: None,
    }));
    assign_source_media_positions(&mut records);
    records
}

fn assign_source_media_positions(records: &mut [SourceMedia]) {
    let mut project_positions = HashMap::new();
    let mut project_images: HashMap<i64, Vec<(usize, i64, bool)>> = HashMap::new();
    for record in records
        .iter_mut()
        .filter(|record| matches!(record.kind, "title" | "step"))
    {
        let group = (record.project_id, record.step_id);
        let position = project_positions.entry(group).or_insert(0_i64);
        record.position = *position;
        *position += 1;
    }
    for (index, record) in records
        .iter()
        .enumerate()
        .filter(|(_, record)| matches!(record.kind, "title" | "step"))
    {
        if let Some(project_id) = record.project_id {
            project_images.entry(project_id).or_default().push((
                index,
                record.id,
                record.legacy_is_title_image.unwrap_or(false),
            ));
        }
    }
    for images in project_images.values_mut() {
        images.sort_by_key(|(_, id, _)| *id);
        let cover_index = images
            .iter()
            .find(|(_, _, is_legacy_cover)| *is_legacy_cover)
            .or_else(|| images.first())
            .map(|(index, _, _)| *index);
        if let Some(index) = cover_index {
            records[index].is_project_cover = true;
        }
    }

    let mut yarn_photos: Vec<_> = records
        .iter()
        .enumerate()
        .filter(|(_, record)| record.kind == "photo")
        .map(|(index, record)| (index, record.parent_id, record.is_primary, record.id))
        .collect();
    yarn_photos.sort_by_key(|(_, yarn_id, is_primary, id)| {
        (*yarn_id, !*is_primary, std::cmp::Reverse(*id))
    });
    let mut current_yarn = None;
    let mut position = 0_i64;
    for (index, yarn_id, _, _) in yarn_photos {
        if current_yarn != Some(yarn_id) {
            current_yarn = Some(yarn_id);
            position = 0;
        }
        records[index].position = position;
        position += 1;
    }
}

async fn insert_users(
    tx: &mut Transaction<'_, Sqlite>,
    rows: &[UserRow],
) -> Result<(), ImportError> {
    for row in rows {
        sqlx::query(
            "INSERT INTO users (id,email,password_hash,active,is_admin,created_at,profile_image,legacy_token_version,legacy_email) VALUES (?,?,?,?,?,?,?,?,?)",
        )
        .bind(row.id)
        .bind(normalize_email(&row.email))
        .bind(&row.hashed_password)
        .bind(row.is_active)
        .bind(row.is_admin)
        .bind(timestamp(&row.created_at)?)
        .bind(&row.profile_image)
        .bind(row.token_version)
        .bind(&row.email)
        .execute(&mut **tx)
        .await
        .map_err(|_| ImportError::Database)?;
    }
    Ok(())
}

async fn insert_tokens(
    tx: &mut Transaction<'_, Sqlite>,
    rows: &[TokenRow],
) -> Result<(), ImportError> {
    for row in rows {
        sqlx::query(
            "INSERT INTO api_tokens (id,user_id,name,token_hash,created_at,last_used_at,expires_at) \
             VALUES (?,?,?,?,?,?,?)",
        )
        .bind(row.id)
        .bind(row.user_id)
        .bind(&row.name)
        .bind(&row.token_hash)
        .bind(epoch_seconds(&row.created_at)?)
        .bind(row.last_used_at.as_deref().map(epoch_seconds).transpose()?)
        .bind(row.expires_at.as_deref().map(epoch_seconds).transpose()?)
        .execute(&mut **tx)
        .await
        .map_err(|_| ImportError::Database)?;
    }
    Ok(())
}

async fn insert_categories(
    tx: &mut Transaction<'_, Sqlite>,
    data: &SourceData,
    report: &mut ImportReport,
) -> Result<HashMap<(i64, String), i64>, ImportError> {
    let mut ids = HashMap::new();
    for row in &data.categories {
        sqlx::query("INSERT INTO categories (id,user_id,name,created_at) VALUES (?,?,?,?)")
            .bind(row.id)
            .bind(row.user_id)
            .bind(&row.name)
            .bind(timestamp(&row.created_at)?)
            .execute(&mut **tx)
            .await
            .map_err(|_| ImportError::Database)?;
        ids.insert((row.user_id, row.name.clone()), row.id);
    }
    let mut missing = HashSet::new();
    for project in &data.projects {
        if let Some(name) = &project.category
            && !ids.contains_key(&(project.owner_id, name.clone()))
        {
            missing.insert((project.owner_id, name.clone()));
        }
    }
    for (owner_id, name) in missing {
        let result =
            sqlx::query("INSERT INTO categories (user_id,name,created_at) VALUES (?,?,NULL)")
                .bind(owner_id)
                .bind(&name)
                .execute(&mut **tx)
                .await
                .map_err(|_| ImportError::Database)?;
        ids.insert((owner_id, name), result.last_insert_rowid());
        report.synthesized_categories += 1;
    }
    report.categories += report.synthesized_categories;
    Ok(ids)
}

async fn insert_yarns(
    tx: &mut Transaction<'_, Sqlite>,
    rows: &[YarnRow],
) -> Result<(), ImportError> {
    for row in rows {
        sqlx::query(
            "INSERT INTO yarns (id,owner_id,name,description,brand,colorway,dye_lot,fiber_content,weight_category,recommended_needles,weight_grams,length_meters,notes,link,link_archive,is_ai_enhanced,created_at,updated_at,archive_requested_at,archive_failed) \
             VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        )
        .bind(row.id)
        .bind(row.owner_id)
        .bind(&row.name)
        .bind(&row.description)
        .bind(&row.brand)
        .bind(&row.colorway)
        .bind(&row.dye_lot)
        .bind(&row.fiber_content)
        .bind(&row.weight_category)
        .bind(&row.recommended_needles)
        .bind(row.weight_grams)
        .bind(row.length_meters)
        .bind(&row.notes)
        .bind(&row.link)
        .bind(&row.link_archive)
        .bind(row.is_ai_enhanced)
        .bind(timestamp(&row.created_at)?)
        .bind(timestamp(&row.updated_at)?)
        .bind(row.link_archive_requested_at.as_deref().map(timestamp).transpose()?)
        .bind(row.link_archive_failed)
        .execute(&mut **tx)
        .await
        .map_err(|_| ImportError::Database)?;
    }
    Ok(())
}

async fn insert_projects(
    tx: &mut Transaction<'_, Sqlite>,
    rows: &[ProjectRow],
    category_ids: &HashMap<(i64, String), i64>,
) -> Result<(), ImportError> {
    for row in rows {
        let category_id = row
            .category
            .as_ref()
            .and_then(|name| category_ids.get(&(row.owner_id, name.clone())).copied());
        sqlx::query(
            "INSERT INTO projects (id,owner_id,category_id,name,needles,stitch_sample,description,notes,other_materials,tags,link,link_archive,is_ai_enhanced,created_at,updated_at,legacy_yarn_text,archive_requested_at,archive_failed) \
             VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        )
        .bind(row.id)
        .bind(row.owner_id)
        .bind(category_id)
        .bind(&row.name)
        .bind(&row.needles)
        .bind(&row.stitch_sample)
        .bind(&row.description)
        .bind(&row.notes)
        .bind(&row.other_materials)
        .bind(row.tags.as_deref().unwrap_or("[]"))
        .bind(&row.link)
        .bind(&row.link_archive)
        .bind(row.is_ai_enhanced)
        .bind(timestamp(&row.created_at)?)
        .bind(timestamp(&row.updated_at)?)
        .bind(&row.yarn)
        .bind(row.link_archive_requested_at.as_deref().map(timestamp).transpose()?)
        .bind(row.link_archive_failed)
        .execute(&mut **tx)
        .await
        .map_err(|_| ImportError::Database)?;
    }
    Ok(())
}

async fn insert_steps(
    tx: &mut Transaction<'_, Sqlite>,
    rows: &[StepRow],
) -> Result<(), ImportError> {
    for row in rows {
        sqlx::query(
            "INSERT INTO project_steps (id,project_id,title,description,step_number,created_at) \
             VALUES (?,?,?,?,?,?)",
        )
        .bind(row.id)
        .bind(row.project_id)
        .bind(&row.title)
        .bind(&row.description)
        .bind(row.step_number)
        .bind(timestamp(&row.created_at)?)
        .execute(&mut **tx)
        .await
        .map_err(|_| ImportError::Database)?;
    }
    Ok(())
}

async fn insert_associations(
    tx: &mut Transaction<'_, Sqlite>,
    data: &SourceData,
) -> Result<(), ImportError> {
    for row in &data.project_yarns {
        sqlx::query("INSERT INTO project_yarns (project_id,yarn_id) VALUES (?,?)")
            .bind(row.project_id)
            .bind(row.yarn_id)
            .execute(&mut **tx)
            .await
            .map_err(|_| ImportError::Database)?;
    }
    for row in &data.project_favorites {
        sqlx::query("INSERT INTO user_favorites (user_id,project_id) VALUES (?,?)")
            .bind(row.user_id)
            .bind(row.entity_id)
            .execute(&mut **tx)
            .await
            .map_err(|_| ImportError::Database)?;
    }
    for row in &data.yarn_favorites {
        sqlx::query("INSERT INTO user_favorite_yarns (user_id,yarn_id) VALUES (?,?)")
            .bind(row.user_id)
            .bind(row.entity_id)
            .execute(&mut **tx)
            .await
            .map_err(|_| ImportError::Database)?;
    }
    Ok(())
}

async fn insert_audits_and_tombstones(
    tx: &mut Transaction<'_, Sqlite>,
    data: &SourceData,
    report: &mut ImportReport,
) -> Result<(), ImportError> {
    let users: HashSet<i64> = data.users.iter().map(|row| row.id).collect();
    let live_projects: HashSet<i64> = data.projects.iter().map(|row| row.id).collect();
    let live_yarns: HashSet<i64> = data.yarns.iter().map(|row| row.id).collect();
    for audit in &data.audits {
        let actor = users
            .contains(&audit.actor_user_id)
            .then_some(audit.actor_user_id);
        sqlx::query(
            "INSERT INTO audit_events (id,actor_user_id,entity_type,entity_id,action,details,created_at,legacy_actor_user_id) \
             VALUES (?,?,?,?,?,?,?,?)",
        )
        .bind(audit.id)
        .bind(actor)
        .bind(&audit.entity_type)
        .bind(audit.entity_id)
        .bind(&audit.action)
        .bind(&audit.details)
        .bind(timestamp(&audit.created_at)?)
        .bind(audit.actor_user_id)
        .execute(&mut **tx)
        .await
        .map_err(|_| ImportError::Database)?;

        let deleted_owner = match (audit.action.as_str(), audit.entity_type.as_str()) {
            ("deleted", "project") if !live_projects.contains(&audit.entity_id) => actor,
            ("deleted", "yarn") if !live_yarns.contains(&audit.entity_id) => actor,
            _ => None,
        };
        if let Some(owner_id) = deleted_owner {
            sqlx::query(
                "INSERT INTO deletion_events (owner_id,entity_type,entity_id,changed_at) VALUES (?,?,?,?)",
            )
            .bind(owner_id)
            .bind(&audit.entity_type)
            .bind(audit.entity_id)
            .bind(timestamp(&audit.created_at)?)
            .execute(&mut **tx)
            .await
            .map_err(|_| ImportError::Database)?;
            report.deletion_events += 1;
        }
    }
    Ok(())
}

fn prepare_and_copy_media(
    source_root: &Path,
    target_root: &Path,
    source: SourceMedia,
) -> Result<PreparedMedia, ImportError> {
    let source_path = source_media_file(
        source_root,
        source.subdir,
        source.parent_id,
        &source.filename,
    )
    .map_err(|_| ImportError::InvalidMedia { count: 1 })?;
    let source_size = fs::metadata(&source_path)
        .map_err(|_| ImportError::InvalidMedia { count: 1 })?
        .len();
    if source
        .size_bytes
        .is_some_and(|size| size < 0 || size as u64 != source_size)
    {
        return Err(ImportError::InvalidMedia { count: 1 });
    }
    let is_image = source.kind != "attachment";
    let decoded = decode_image(&source_path).ok();
    if is_image && decoded.is_none() {
        return Err(ImportError::InvalidMedia { count: 1 });
    }
    let detected_mime = decoded.as_ref().map(|item| item.mime.to_owned());
    let content_type = if is_image {
        detected_mime
            .clone()
            .ok_or(ImportError::InvalidMedia { count: 1 })?
    } else {
        source
            .content_type
            .clone()
            .filter(|value| !value.trim().is_empty())
            .unwrap_or_else(|| "application/octet-stream".to_owned())
    };
    let decoded_thumbnail = decoded.as_ref().map(|image| image.thumbnail.clone());
    let thumbnail = decoded_thumbnail.or_else(|| {
        (source.kind == "attachment")
            .then(|| source_legacy_thumbnail(source_root, &source))
            .flatten()
    });
    let (detected_width, detected_height) = decoded
        .as_ref()
        .map(|image| (Some(i64::from(image.width)), Some(i64::from(image.height))))
        .unwrap_or((None, None));
    let (width, height) = if source.source == "images" {
        (source.width, source.height)
    } else {
        (
            source.width.or(detected_width),
            source.height.or(detected_height),
        )
    };
    let is_safe_inline = if source.kind == "attachment" {
        let declared = source.content_type.as_deref().unwrap_or_default();
        detected_mime.as_deref() == Some(declared)
            || (declared.eq_ignore_ascii_case("application/pdf") && valid_pdf(&source_path))
    } else {
        true
    };

    let physical_filename = new_physical_filename(
        target_root,
        source.subdir,
        source.parent_id,
        &source.filename,
    )?;
    let target_dir = target_root
        .join(source.subdir)
        .join(source.parent_id.to_string());
    create_private_dir_all(&target_dir).map_err(|_| ImportError::Media)?;
    let target_path = target_dir.join(&physical_filename);
    let digest = copy_and_verify(&source_path, &target_path)?;

    let legacy_thumbnail = thumbnail
        .as_ref()
        .map(|_| legacy_thumbnail_name(&source.filename));
    if let Some(thumbnail) = &thumbnail {
        let thumbnail_dir = target_root
            .join("thumbnails")
            .join(source.subdir)
            .join(source.parent_id.to_string());
        create_private_dir_all(&thumbnail_dir).map_err(|_| ImportError::Media)?;
        let target_thumbnail = thumbnail_dir.join(thumbnail_name(&physical_filename));
        write_private(&target_thumbnail, thumbnail).map_err(|_| ImportError::Media)?;
    }

    Ok(PreparedMedia {
        source,
        physical_filename,
        content_type,
        size_bytes: i64::try_from(source_size)
            .map_err(|_| ImportError::InvalidMedia { count: 1 })?,
        width,
        height,
        is_safe_inline,
        legacy_thumbnail,
        digest,
    })
}

struct DecodedImage {
    width: u32,
    height: u32,
    mime: &'static str,
    thumbnail: Vec<u8>,
}

fn decode_image(path: &Path) -> Result<DecodedImage, ()> {
    let file = open_readonly_nofollow(path).map_err(|_| ())?;
    let mut reader = ImageReader::new(BufReader::new(file))
        .with_guessed_format()
        .map_err(|_| ())?;
    let format = reader.format().ok_or(())?;
    let mime = image_mime(format).ok_or(())?;
    let mut limits = Limits::default();
    limits.max_image_width = Some(MAX_IMAGE_DIMENSION);
    limits.max_image_height = Some(MAX_IMAGE_DIMENSION);
    limits.max_alloc = Some(MAX_IMAGE_ALLOCATION);
    reader.limits(limits);
    let image = reader.decode().map_err(|_| ())?;
    let width = image.width();
    let height = image.height();
    if width == 0 || height == 0 || u64::from(width) * u64::from(height) > MAX_IMAGE_PIXELS {
        return Err(());
    }
    let thumbnail = encode_thumbnail(&image).map_err(|_| ())?;
    Ok(DecodedImage {
        width,
        height,
        mime,
        thumbnail,
    })
}

fn image_mime(format: ImageFormat) -> Option<&'static str> {
    match format {
        ImageFormat::Png => Some("image/png"),
        ImageFormat::Jpeg => Some("image/jpeg"),
        ImageFormat::WebP => Some("image/webp"),
        ImageFormat::Gif => Some("image/gif"),
        ImageFormat::Bmp => Some("image/bmp"),
        ImageFormat::Tiff => Some("image/tiff"),
        _ => None,
    }
}

fn source_legacy_thumbnail(root: &Path, media: &SourceMedia) -> Option<Vec<u8>> {
    let filename = legacy_thumbnail_name(&media.filename);
    let parent = root
        .join("thumbnails")
        .join(media.subdir)
        .join(media.parent_id.to_string());
    let canonical_parent = fs::canonicalize(&parent).ok()?;
    if canonical_parent != parent || !canonical_parent.starts_with(root) {
        return None;
    }
    let path = parent.join(filename);
    if fs::symlink_metadata(&path).ok()?.file_type().is_symlink() {
        return None;
    }
    let path = fs::canonicalize(path).ok()?;
    if !path.starts_with(canonical_parent) || !path.is_file() {
        return None;
    }
    decode_image(&path).ok().map(|decoded| decoded.thumbnail)
}

fn encode_thumbnail(image: &DynamicImage) -> Result<Vec<u8>, ()> {
    let rgb = image.thumbnail(300, 300).to_rgb8();
    let mut bytes = std::io::Cursor::new(Vec::new());
    DynamicImage::ImageRgb8(rgb)
        .write_to(&mut bytes, ImageFormat::Jpeg)
        .map_err(|_| ())?;
    Ok(bytes.into_inner())
}

fn valid_pdf(path: &Path) -> bool {
    let Ok(mut file) = open_readonly_nofollow(path) else {
        return false;
    };
    let Ok(length) = file.metadata().map(|metadata| metadata.len()) else {
        return false;
    };
    if length < 10 {
        return false;
    }
    let mut header = [0_u8; 5];
    if file.read_exact(&mut header).is_err() || &header != b"%PDF-" {
        return false;
    }
    let start = length.saturating_sub(1024);
    if std::io::Seek::seek(&mut file, std::io::SeekFrom::Start(start)).is_err() {
        return false;
    }
    let mut tail = Vec::with_capacity((length - start) as usize);
    file.read_to_end(&mut tail).is_ok() && tail.windows(5).any(|window| window == b"%%EOF")
}

fn new_physical_filename(
    target_root: &Path,
    subdir: &str,
    parent_id: i64,
    source_filename: &str,
) -> Result<String, ImportError> {
    let extension = if subdir == "projects" || subdir == "yarns" {
        let suffix = Path::new(source_filename)
            .extension()
            .and_then(OsStr::to_str)
            .filter(|value| {
                !value.is_empty()
                    && value.len() <= 10
                    && value.bytes().all(|byte| byte.is_ascii_alphanumeric())
            })
            .unwrap_or("bin");
        suffix.to_ascii_lowercase()
    } else {
        "bin".to_owned()
    };
    let directory = target_root.join(subdir).join(parent_id.to_string());
    for _ in 0..8 {
        let mut random = [0_u8; 24];
        SysRng
            .try_fill_bytes(&mut random)
            .expect("the operating system random number generator failed");
        let candidate = format!("{}.{}", hex::encode(random), extension);
        if !directory.join(&candidate).exists() {
            return Ok(candidate);
        }
    }
    Err(ImportError::Media)
}

fn legacy_thumbnail_name(filename: &str) -> String {
    let stem = Path::new(filename)
        .file_stem()
        .and_then(OsStr::to_str)
        .unwrap_or("file");
    format!("thumb_{stem}.jpg")
}

fn thumbnail_name(filename: &str) -> String {
    legacy_thumbnail_name(filename)
}

fn copy_and_verify(source: &Path, target: &Path) -> Result<[u8; 32], ImportError> {
    let mut source_file = open_readonly_nofollow(source).map_err(|_| ImportError::Media)?;
    let mut target_file = private_file(target).map_err(|_| ImportError::Media)?;
    let mut source_hash = Sha256::new();
    let mut buffer = vec![0_u8; COPY_BUFFER_SIZE];
    loop {
        let count = source_file
            .read(&mut buffer)
            .map_err(|_| ImportError::Media)?;
        if count == 0 {
            break;
        }
        source_hash.update(&buffer[..count]);
        target_file
            .write_all(&buffer[..count])
            .map_err(|_| ImportError::Media)?;
    }
    target_file.sync_all().map_err(|_| ImportError::Media)?;
    drop(target_file);
    let source_hash = source_hash.finalize();
    let target_hash = hash_file(target)?;
    if source_hash[..] != target_hash[..] {
        return Err(ImportError::Media);
    }
    Ok(target_hash)
}

fn hash_file(path: &Path) -> Result<[u8; 32], ImportError> {
    let mut file = File::open(path).map_err(|_| ImportError::Media)?;
    let mut hash = Sha256::new();
    let mut buffer = vec![0_u8; COPY_BUFFER_SIZE];
    loop {
        let count = file.read(&mut buffer).map_err(|_| ImportError::Media)?;
        if count == 0 {
            break;
        }
        hash.update(&buffer[..count]);
    }
    Ok(hash.finalize().into())
}

async fn insert_media(
    tx: &mut Transaction<'_, Sqlite>,
    media: &PreparedMedia,
    reused_alias: bool,
) -> Result<(), ImportError> {
    let source = &media.source;
    let result = sqlx::query(
        "INSERT INTO media_files (owner_id,project_id,yarn_id,step_id,kind,public_id,filename,original_filename,content_type,size_bytes,width,height,alt_text,is_primary,position,is_project_cover,is_safe_inline,created_at,legacy_image_type,is_stitch_sample,legacy_is_title_image,legacy_source,legacy_id) \
         VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
    )
    .bind(source.owner_id)
    .bind(source.project_id)
    .bind(source.yarn_id)
    .bind(source.step_id)
    .bind(source.kind)
    .bind(source.id)
    .bind(&media.physical_filename)
    .bind(&source.original_filename)
    .bind(&media.content_type)
    .bind(media.size_bytes)
    .bind(media.width)
    .bind(media.height)
    .bind(&source.alt_text)
    .bind(source.is_primary)
    .bind(source.position)
    .bind(source.is_project_cover)
    .bind(media.is_safe_inline)
    .bind(timestamp(&source.created_at)?)
    .bind(&source.legacy_image_type)
    .bind(source.is_stitch_sample)
    .bind(source.legacy_is_title_image)
    .bind(source.source)
    .bind(source.id)
    .execute(&mut **tx)
    .await
    .map_err(|_| ImportError::Database)?;
    let media_id = result.last_insert_rowid();
    if !reused_alias {
        sqlx::query(
            "INSERT INTO media_url_aliases (subdir,parent_id,legacy_filename,legacy_thumbnail,media_id) VALUES (?,?,?,?,?)",
        )
        .bind(source.subdir)
        .bind(source.parent_id)
        .bind(&source.filename)
        .bind(&media.legacy_thumbnail)
        .bind(media_id)
        .execute(&mut **tx)
        .await
        .map_err(|_| ImportError::InvalidMedia { count: 1 })?;
    }
    Ok(())
}

async fn import_avatars(
    tx: &mut Transaction<'_, Sqlite>,
    data: &SourceData,
    source_root: &Path,
    target_root: &Path,
) -> Result<usize, ImportError> {
    let mut count = 0;
    for user in &data.users {
        let Some(filename) = user.profile_image.as_deref() else {
            continue;
        };
        let source_root = source_root.to_path_buf();
        let target_root = target_root.to_path_buf();
        let filename = filename.to_owned();
        let user_id = user.id;
        let avatar = tokio::task::spawn_blocking(move || {
            prepare_avatar(&source_root, &target_root, user_id, filename)
        })
        .await
        .map_err(|_| ImportError::Media)??;
        sqlx::query(
            "INSERT INTO user_avatars (user_id,filename,original_filename,content_type,width,height,created_at) VALUES (?,?,?,?,?,?,?)",
        )
        .bind(avatar.user_id)
        .bind(&avatar.filename)
        .bind(&avatar.filename)
        .bind(avatar.content_type)
        .bind(i64::from(avatar.width))
        .bind(i64::from(avatar.height))
        .bind(None::<String>)
        .execute(&mut **tx)
        .await
        .map_err(|_| ImportError::Database)?;
        count += 1;
    }
    Ok(count)
}

fn prepare_avatar(
    source_root: &Path,
    target_root: &Path,
    user_id: i64,
    filename: String,
) -> Result<PreparedAvatar, ImportError> {
    let source_path = source_media_file(source_root, "users", user_id, &filename)
        .map_err(|_| ImportError::InvalidMedia { count: 1 })?;
    let decoded = decode_image(&source_path).map_err(|_| ImportError::InvalidMedia { count: 1 })?;
    let directory = target_root.join("users").join(user_id.to_string());
    create_private_dir_all(&directory).map_err(|_| ImportError::Media)?;
    copy_and_verify(&source_path, &directory.join(&filename))?;
    let thumbnails = target_root
        .join("thumbnails")
        .join("users")
        .join(user_id.to_string());
    create_private_dir_all(&thumbnails).map_err(|_| ImportError::Media)?;
    write_private(
        &thumbnails.join(legacy_thumbnail_name(&filename)),
        &decoded.thumbnail,
    )
    .map_err(|_| ImportError::Media)?;
    Ok(PreparedAvatar {
        user_id,
        filename,
        content_type: decoded.mime,
        width: decoded.width,
        height: decoded.height,
    })
}

fn timestamp(value: &str) -> Result<String, ImportError> {
    let parsed = match DateTime::parse_from_rfc3339(value) {
        Ok(value) => value.with_timezone(&Utc),
        Err(_) => ["%Y-%m-%d %H:%M:%S%.f", "%Y-%m-%dT%H:%M:%S%.f"]
            .iter()
            .find_map(|format| NaiveDateTime::parse_from_str(value, format).ok())
            .map(|value| value.and_utc())
            .ok_or(ImportError::SourceData)?,
    };
    Ok(parsed.to_rfc3339_opts(SecondsFormat::Micros, true))
}

fn epoch_seconds(value: &str) -> Result<i64, ImportError> {
    let value = timestamp(value)?;
    DateTime::parse_from_rfc3339(&value)
        .map(|date| date.timestamp())
        .map_err(|_| ImportError::SourceData)
}

fn create_staging_dir(parent: &Path, name: &OsStr) -> Result<TempDir, ImportError> {
    Builder::new()
        .prefix(&format!(".{}.import-", name.to_string_lossy()))
        .tempdir_in(parent)
        .map_err(|_| ImportError::Staging)
}

fn create_private_dir(path: &Path) -> std::io::Result<()> {
    fs::create_dir(path)?;
    set_private_permissions(path)
}

fn create_private_dir_all(path: &Path) -> std::io::Result<()> {
    fs::create_dir_all(path)?;
    set_private_permissions(path)
}

fn create_private_file(path: &Path) -> std::io::Result<()> {
    private_file(path).map(drop)
}

fn private_file(path: &Path) -> std::io::Result<File> {
    let mut options = OpenOptions::new();
    options.write(true).create_new(true);
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt;
        options.mode(0o600);
    }
    options.open(path)
}

fn write_private(path: &Path, bytes: &[u8]) -> std::io::Result<()> {
    let mut file = private_file(path)?;
    file.write_all(bytes)?;
    file.sync_all()
}

fn set_private_permissions(path: &Path) -> std::io::Result<()> {
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        fs::set_permissions(path, fs::Permissions::from_mode(0o700))?;
    }
    Ok(())
}

fn write_report(directory: &Path, report: &ImportReport) -> Result<(), ImportError> {
    let path = directory.join("migration-report.json");
    let bytes = serde_json::to_vec_pretty(report).map_err(|_| ImportError::Staging)?;
    write_private(&path, &bytes).map_err(|_| ImportError::Staging)
}

#[cfg(target_os = "linux")]
fn publish_noreplace(
    staging: &mut TempDir,
    parent: &Path,
    destination_name: &OsStr,
) -> Result<(), ImportError> {
    use rustix::fs::{CWD, RenameFlags, renameat_with};
    let parent = File::open(parent).map_err(|_| ImportError::Publish)?;

    renameat_with(
        CWD,
        staging.path(),
        &parent,
        Path::new(destination_name),
        RenameFlags::NOREPLACE,
    )
    .map_err(|_| ImportError::Publish)?;
    if parent.sync_all().is_err() {
        let _ = renameat_with(
            &parent,
            Path::new(destination_name),
            CWD,
            staging.path(),
            RenameFlags::NOREPLACE,
        );
        return Err(ImportError::Publish);
    }
    Ok(())
}

#[cfg(not(target_os = "linux"))]
fn publish_noreplace(
    _staging: &mut TempDir,
    _parent: &Path,
    _destination_name: &OsStr,
) -> Result<(), ImportError> {
    Err(ImportError::UnsupportedPublication)
}

#[cfg(test)]
mod media_position_tests {
    use super::{SourceMedia, assign_source_media_positions};

    fn image(id: i64, step_id: Option<i64>, legacy_cover: bool) -> SourceMedia {
        SourceMedia {
            source: "images",
            id,
            owner_id: 1,
            subdir: "projects",
            parent_id: 1,
            kind: if step_id.is_some() { "step" } else { "title" },
            filename: format!("{id}.png"),
            original_filename: format!("{id}.png"),
            content_type: None,
            size_bytes: None,
            alt_text: String::new(),
            is_primary: false,
            position: 0,
            is_project_cover: false,
            width: None,
            height: None,
            created_at: "2026-01-01T00:00:00Z".to_owned(),
            project_id: Some(1),
            yarn_id: None,
            step_id,
            legacy_image_type: Some("image/png".to_owned()),
            is_stitch_sample: None,
            legacy_is_title_image: Some(legacy_cover),
        }
    }

    fn yarn_photo(id: i64, yarn_id: i64, is_primary: bool) -> SourceMedia {
        SourceMedia {
            source: "yarn_images",
            id,
            owner_id: 1,
            subdir: "yarns",
            parent_id: yarn_id,
            kind: "photo",
            filename: format!("{id}.png"),
            original_filename: format!("{id}.png"),
            content_type: None,
            size_bytes: None,
            alt_text: String::new(),
            is_primary,
            position: 0,
            is_project_cover: false,
            width: None,
            height: None,
            created_at: "2026-01-01T00:00:00Z".to_owned(),
            project_id: None,
            yarn_id: Some(yarn_id),
            step_id: None,
            legacy_image_type: None,
            is_stitch_sample: None,
            legacy_is_title_image: None,
        }
    }

    #[test]
    fn import_positions_follow_existing_project_and_yarn_display_order() {
        let mut records = vec![
            image(9, Some(11), false),
            image(20, None, false),
            image(30, None, true),
            yarn_photo(101, 5, false),
            yarn_photo(100, 5, true),
            yarn_photo(102, 5, false),
            yarn_photo(600, 6, false),
            yarn_photo(601, 6, false),
        ];
        assign_source_media_positions(&mut records);

        assert_eq!(records[0].position, 0, "each step has an independent group");
        assert_eq!(records[1].position, 0);
        assert_eq!(records[2].position, 1);
        assert!(
            records[2].is_project_cover,
            "legacy title flag remains the chosen cover"
        );
        assert!(!records[1].is_project_cover);
        assert!(
            records
                .iter()
                .filter(|row| matches!(row.kind, "title" | "step"))
                .all(|row| !row.is_primary)
        );
        assert_eq!(records[4].position, 0, "primary photo remains first");
        assert_eq!(
            records[5].position, 1,
            "nonprimary photos retain descending-id order"
        );
        assert_eq!(records[3].position, 2);
        assert_eq!(records[6].position, 1);
        assert_eq!(records[7].position, 0);
        assert!(!records[6].is_primary && !records[7].is_primary);
    }
}
