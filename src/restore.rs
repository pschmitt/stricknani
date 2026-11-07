//! Restore of a per-user data export (`POST /api/v2/account/import`).
//!
//! The archive format is the contract in `docs/data-export.md`. A restore adds the
//! archive's projects and yarns as new items owned by the caller, all or nothing:
//!
//! 1. The request body is streamed to a temporary file (bounded), and the container
//!    (tar.gz or zip) is detected from its first bytes.
//! 2. A first scan reads only `manifest.json` and the three JSON files (each bounded) and
//!    checks every entry name and type. A second scan reads the media files the JSON names,
//!    one at a time, through a bounded channel.
//! 3. Each media file goes through the upload checks and processing of `media.rs` and is staged
//!    in a scratch directory below the media root. No row exists yet.
//! 4. One write transaction inserts every row (so the new ids are known), moves the staged files
//!    to their final media paths and commits. Any failure rolls the transaction back and removes
//!    the moved files (or queues them in `media_gc` when removal fails).
//!
//! Errors never echo archive content.

use std::{
    collections::{BTreeMap, HashMap, HashSet},
    io::{self, Read},
    path::{Path, PathBuf},
    time::Duration,
};

use axum::body::Body;
use chrono::{DateTime, SecondsFormat, Utc};
use serde::{Deserialize, Deserializer, Serialize};
use serde_json::Value;
use sha2::{Digest, Sha256};
use sqlx::{Sqlite, Transaction};
use tokio::io::AsyncWriteExt;
use tokio_stream::StreamExt;

use crate::{
    audit::{self, Action, Entity},
    db::AppState,
    media::{self, MAX_UPLOAD_BYTES},
    media_ids::{self, Namespace},
};

/// Largest accepted request body.
pub(crate) const MAX_ARCHIVE_BYTES: u64 = 2 * 1024 * 1024 * 1024;
/// Largest decompressed JSON file.
const MAX_JSON_BYTES: u64 = 64 * 1024 * 1024;
/// Largest decompressed media entry (the upload limit).
const MAX_MEDIA_BYTES: u64 = MAX_UPLOAD_BYTES as u64;
/// Largest total of decompressed bytes one scan may consume.
const MAX_UNPACKED_BYTES: u64 = 4 * 1024 * 1024 * 1024;
/// Most entries in one archive, and most media files one restore stores.
const MAX_ENTRIES: usize = 500_000;
const MAX_MEDIA_FILES: usize = 100_000;
/// A client that sends nothing for this long loses its slot.
const IDLE_TIMEOUT: Duration = Duration::from_secs(120);
const FORMAT: &str = "stricknani-export";
const FORMAT_VERSION: i64 = 1;
const STAGE_PREFIX: &str = ".restore-stage-";
const UPLOAD_PREFIX: &str = ".restore-upload-";

/// One restore per user at a time; separate from the export limiter.
pub(crate) type RestoreLimiter = crate::export::ExportLimiter;

#[derive(Debug)]
pub(crate) enum RestoreError {
    /// Not a usable archive; the message is fixed and never carries archive content.
    Invalid,
    UnsupportedVersion,
    TooLarge,
    Timeout,
    Unavailable,
    Internal,
}

impl RestoreError {
    /// HTTP status, error code and message of the v2 error envelope.
    pub(crate) fn describe(&self) -> (u16, &'static str, &'static str) {
        match self {
            Self::Invalid => (
                400,
                "invalid_archive",
                "The file is not a valid Stricknani export",
            ),
            Self::UnsupportedVersion => (
                422,
                "unsupported_format_version",
                "This export was written by a newer version; update the server to restore it",
            ),
            Self::TooLarge => (
                413,
                "payload_too_large",
                "The archive is larger than the server accepts",
            ),
            Self::Timeout => (408, "request_timeout", "The upload stalled"),
            Self::Unavailable => (503, "service_unavailable", "Media storage is not available"),
            Self::Internal => (500, "internal_error", "Request failed"),
        }
    }
}

impl From<sqlx::Error> for RestoreError {
    fn from(_: sqlx::Error) -> Self {
        Self::Internal
    }
}

#[derive(Debug, Serialize)]
pub(crate) struct Summary {
    format_version: i64,
    projects: usize,
    yarns: usize,
    categories_created: usize,
    media_files: usize,
    media_skipped: usize,
}

// ---------------------------------------------------------------------------
// Archive JSON

fn null_default<'de, D, T>(deserializer: D) -> Result<T, D::Error>
where
    D: Deserializer<'de>,
    T: Default + Deserialize<'de>,
{
    Ok(Option::<T>::deserialize(deserializer)?.unwrap_or_default())
}

#[derive(Debug, Deserialize)]
struct MediaEntry {
    file: Option<String>,
    original_filename: Option<String>,
    content_type: Option<String>,
    alt_text: Option<String>,
    #[serde(default, deserialize_with = "null_default")]
    position: i64,
    #[serde(default, deserialize_with = "null_default")]
    is_primary: bool,
    #[serde(default, deserialize_with = "null_default")]
    missing: bool,
    sha256: Option<String>,
}

#[derive(Debug, Deserialize)]
struct StepIn {
    #[serde(default)]
    title: Option<String>,
    #[serde(default)]
    description: Option<String>,
    #[serde(default, deserialize_with = "null_default")]
    step_number: i64,
    #[serde(default, deserialize_with = "null_default")]
    images: Vec<MediaEntry>,
}

#[derive(Debug, Deserialize)]
struct ProjectIn {
    id: i64,
    name: String,
    category: Option<String>,
    needles: Option<String>,
    stitch_sample: Option<String>,
    description: Option<String>,
    notes: Option<String>,
    other_materials: Option<String>,
    #[serde(default, deserialize_with = "null_default")]
    tags: Vec<String>,
    link: Option<String>,
    link_archive: Option<String>,
    #[serde(default, deserialize_with = "null_default")]
    is_ai_enhanced: bool,
    #[serde(default, deserialize_with = "null_default")]
    is_favorite: bool,
    created_at: Option<String>,
    #[serde(default, deserialize_with = "null_default")]
    yarn_ids: Vec<i64>,
    #[serde(default, deserialize_with = "null_default")]
    steps: Vec<StepIn>,
    #[serde(default, deserialize_with = "null_default")]
    images: Vec<MediaEntry>,
    #[serde(default, deserialize_with = "null_default")]
    attachments: Vec<MediaEntry>,
}

#[derive(Debug, Deserialize)]
struct YarnIn {
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
    #[serde(default, deserialize_with = "null_default")]
    is_ai_enhanced: bool,
    #[serde(default, deserialize_with = "null_default")]
    is_favorite: bool,
    created_at: Option<String>,
    #[serde(default, deserialize_with = "null_default")]
    project_ids: Vec<i64>,
    #[serde(default, deserialize_with = "null_default")]
    photos: Vec<MediaEntry>,
}

#[derive(Debug, Deserialize)]
struct CategoryIn {
    name: String,
}

struct RawJson {
    projects: Option<Vec<u8>>,
    yarns: Option<Vec<u8>>,
    categories: Option<Vec<u8>>,
}

// ---------------------------------------------------------------------------
// Containers

#[derive(Clone, Copy, PartialEq, Eq)]
enum Container {
    TarGz,
    Zip,
}

fn detect(path: &Path) -> Result<Container, RestoreError> {
    let mut magic = [0_u8; 4];
    let mut file = std::fs::File::open(path).map_err(|_| RestoreError::Internal)?;
    let read = read_up_to(&mut file, &mut magic).map_err(|_| RestoreError::Internal)?;
    match &magic[..read] {
        [0x1f, 0x8b, ..] => Ok(Container::TarGz),
        [b'P', b'K', 3, 4] | [b'P', b'K', 5, 6] => Ok(Container::Zip),
        _ => Err(RestoreError::Invalid),
    }
}

fn read_up_to(reader: &mut impl Read, buffer: &mut [u8]) -> io::Result<usize> {
    let mut filled = 0;
    while filled < buffer.len() {
        match reader.read(&mut buffer[filled..])? {
            0 => break,
            read => filled += read,
        }
    }
    Ok(filled)
}

/// Reader that fails once more than `remaining` bytes were read through it.
struct Limited<R> {
    inner: R,
    remaining: u64,
}

impl<R: Read> Read for Limited<R> {
    fn read(&mut self, buffer: &mut [u8]) -> io::Result<usize> {
        let read = self.inner.read(buffer)?;
        let read_u64 = read as u64;
        if read_u64 > self.remaining {
            self.remaining = 0;
            return Err(io::Error::other("decompressed size limit"));
        }
        self.remaining -= read_u64;
        Ok(read)
    }
}

/// Validate an entry name: relative, no `..`, no NUL.
fn checked_name(raw: &[u8]) -> Result<String, RestoreError> {
    let name = std::str::from_utf8(raw).map_err(|_| RestoreError::Invalid)?;
    let name = name.strip_suffix('/').unwrap_or(name);
    if name.is_empty()
        || name.starts_with('/')
        || name.contains('\0')
        || name.split('/').any(|part| part == "..")
    {
        return Err(RestoreError::Invalid);
    }
    Ok(name.to_owned())
}

/// Set the top-level directory from the first entry and strip it from `name`. `None` for
/// entries outside it and for the directory itself.
fn relative(top: &mut Option<String>, name: &str, is_dir: bool) -> Option<String> {
    let top = top.get_or_insert_with(|| match name.split_once('/') {
        Some((first, _)) => first.to_owned(),
        None if is_dir => name.to_owned(),
        None => String::new(),
    });
    let rel = if top.is_empty() {
        name
    } else {
        name.strip_prefix(top.as_str())?.strip_prefix('/')?
    };
    (!rel.is_empty()).then(|| rel.to_owned())
}

/// What a scan visitor decides after seeing an entry.
enum Flow {
    Continue,
    Stop,
}

type Visitor<'a> = dyn FnMut(&str, &mut dyn Read, u64) -> Result<Flow, RestoreError> + 'a;

/// Walk the regular files of the archive, refusing unsafe names and entry types anywhere in it.
/// The visitor sees `(path relative to the top directory, reader, declared size)`.
fn scan(path: &Path, container: Container, visit: &mut Visitor<'_>) -> Result<(), RestoreError> {
    let file = std::fs::File::open(path).map_err(|_| RestoreError::Internal)?;
    let mut top = None;
    match container {
        Container::TarGz => {
            let decoder = flate2::read::GzDecoder::new(io::BufReader::new(file));
            let mut archive = tar::Archive::new(Limited {
                inner: decoder,
                remaining: MAX_UNPACKED_BYTES,
            });
            let entries = archive.entries().map_err(|_| RestoreError::Invalid)?;
            for (index, entry) in entries.enumerate() {
                if index >= MAX_ENTRIES {
                    return Err(RestoreError::Invalid);
                }
                let mut entry = entry.map_err(|_| RestoreError::Invalid)?;
                let kind = entry.header().entry_type();
                if kind.is_pax_global_extensions() {
                    continue;
                }
                let name = checked_name(&entry.path_bytes())?;
                let is_dir = kind.is_dir();
                if !is_dir && !kind.is_file() {
                    return Err(RestoreError::Invalid);
                }
                let rel = relative(&mut top, &name, is_dir);
                if let (Some(rel), false) = (rel, is_dir) {
                    let size = entry.size();
                    if matches!(visit(&rel, &mut entry, size)?, Flow::Stop) {
                        return Ok(());
                    }
                }
            }
        }
        Container::Zip => {
            let mut archive = zip::ZipArchive::new(io::BufReader::new(file))
                .map_err(|_| RestoreError::Invalid)?;
            if archive.len() > MAX_ENTRIES {
                return Err(RestoreError::Invalid);
            }
            let mut unpacked = 0_u64;
            for index in 0..archive.len() {
                let mut entry = archive.by_index(index).map_err(|_| RestoreError::Invalid)?;
                let name = checked_name(entry.name_raw())?;
                let is_dir = entry.is_dir();
                if entry.is_symlink()
                    || entry
                        .unix_mode()
                        .is_some_and(|mode| !matches!(mode & 0o170_000, 0 | 0o100_000 | 0o040_000))
                {
                    return Err(RestoreError::Invalid);
                }
                let rel = relative(&mut top, &name, is_dir);
                if let (Some(rel), false) = (rel, is_dir) {
                    let size = entry.size();
                    let mut reader = Limited {
                        inner: &mut entry,
                        remaining: MAX_UNPACKED_BYTES - unpacked,
                    };
                    let flow = visit(&rel, &mut reader, size)?;
                    unpacked = MAX_UNPACKED_BYTES - reader.remaining;
                    if matches!(flow, Flow::Stop) {
                        return Ok(());
                    }
                }
            }
        }
    }
    Ok(())
}

/// Read a whole entry, failing when it is larger than `cap`.
fn read_bounded(reader: &mut dyn Read, cap: u64) -> Result<Vec<u8>, RestoreError> {
    let mut bytes = Vec::new();
    reader
        .take(cap + 1)
        .read_to_end(&mut bytes)
        .map_err(|_| RestoreError::Invalid)?;
    if bytes.len() as u64 > cap {
        return Err(RestoreError::Invalid);
    }
    Ok(bytes)
}

/// First scan: the manifest (checked at once) and the three JSON files.
fn read_json(path: &Path, container: Container) -> Result<RawJson, RestoreError> {
    let mut raw = RawJson {
        projects: None,
        yarns: None,
        categories: None,
    };
    let mut manifest_seen = false;
    scan(path, container, &mut |rel, reader, size| {
        let slot = match rel {
            "manifest.json" if !manifest_seen => {
                if size > MAX_JSON_BYTES {
                    return Err(RestoreError::Invalid);
                }
                check_manifest(&read_bounded(reader, MAX_JSON_BYTES)?)?;
                manifest_seen = true;
                return Ok(Flow::Continue);
            }
            "projects.json" => &mut raw.projects,
            "yarns.json" => &mut raw.yarns,
            "categories.json" => &mut raw.categories,
            _ => return Ok(Flow::Continue),
        };
        if slot.is_none() {
            if size > MAX_JSON_BYTES {
                return Err(RestoreError::Invalid);
            }
            *slot = Some(read_bounded(reader, MAX_JSON_BYTES)?);
        }
        Ok(Flow::Continue)
    })?;
    if !manifest_seen {
        return Err(RestoreError::Invalid);
    }
    Ok(raw)
}

fn check_manifest(bytes: &[u8]) -> Result<(), RestoreError> {
    let manifest: Value = serde_json::from_slice(bytes).map_err(|_| RestoreError::Invalid)?;
    if manifest["format"].as_str() != Some(FORMAT) {
        return Err(RestoreError::Invalid);
    }
    let version = manifest["format_version"].as_i64();
    match version {
        Some(version) if version > FORMAT_VERSION => Err(RestoreError::UnsupportedVersion),
        Some(version) if version >= 1 => Ok(()),
        _ => Err(RestoreError::Invalid),
    }
}

fn parse_list<T: for<'de> Deserialize<'de>>(bytes: Option<&[u8]>) -> Result<Vec<T>, RestoreError> {
    match bytes {
        None => Ok(Vec::new()),
        Some(bytes) => serde_json::from_slice(bytes).map_err(|_| RestoreError::Invalid),
    }
}

// ---------------------------------------------------------------------------
// Plan

#[derive(Clone, Copy, PartialEq, Eq, PartialOrd, Ord)]
enum Target {
    Project(usize),
    Step(usize, usize),
    Yarn(usize),
}

#[derive(Clone, Copy, PartialEq, Eq)]
enum Kind {
    Title,
    Step,
    Attachment,
    Photo,
}

impl Kind {
    const fn column(self) -> &'static str {
        match self {
            Self::Title => "title",
            Self::Step => "step",
            Self::Attachment => "attachment",
            Self::Photo => "photo",
        }
    }

    const fn namespace(self) -> Namespace {
        match self {
            Self::Title | Self::Step => Namespace::Image,
            Self::Attachment => Namespace::Attachment,
            Self::Photo => Namespace::Photo,
        }
    }

    const fn is_image(self) -> bool {
        matches!(self, Self::Title | Self::Step)
    }
}

/// A media file after the upload checks, waiting in the staging directory.
struct Staged {
    path: PathBuf,
    thumbnail: Option<PathBuf>,
    extension: String,
    content_type: String,
    size: i64,
    dimensions: Option<(i64, i64)>,
    is_safe_inline: bool,
}

struct MediaJob {
    target: Target,
    kind: Kind,
    rel: String,
    original_filename: String,
    claimed_type: Option<String>,
    alt_text: Option<String>,
    sha256: Option<String>,
    entry_position: i64,
    entry_primary: bool,
    order: usize,
    staged: Option<Staged>,
    position: i64,
    primary: bool,
    cover: bool,
}

struct Plan {
    projects: Vec<ProjectIn>,
    yarns: Vec<YarnIn>,
    categories: Vec<String>,
    jobs: Vec<MediaJob>,
    skipped: usize,
}

/// A media path of an entry: below `media/`, relative, plain components.
fn valid_media_path(path: &str) -> bool {
    path.len() <= 1024
        && path.starts_with("media/")
        && !path.contains(['\\', '\0'])
        && path
            .split('/')
            .all(|part| !part.is_empty() && part != "." && part != "..")
}

fn add_entries(
    jobs: &mut Vec<MediaJob>,
    skipped: &mut usize,
    target: Target,
    kind: Kind,
    entries: Vec<MediaEntry>,
) -> Result<(), RestoreError> {
    for entry in entries {
        let Some(file) = entry.file.filter(|_| !entry.missing) else {
            *skipped += 1;
            continue;
        };
        if !valid_media_path(&file) || jobs.len() >= MAX_MEDIA_FILES {
            return Err(RestoreError::Invalid);
        }
        let original = entry
            .original_filename
            .unwrap_or_else(|| file.rsplit('/').next().unwrap_or("file").to_owned());
        let order = jobs.len();
        jobs.push(MediaJob {
            target,
            kind,
            rel: file,
            original_filename: media::safe_original_filename(&original),
            claimed_type: entry.content_type,
            alt_text: entry.alt_text,
            sha256: entry.sha256,
            entry_position: entry.position,
            entry_primary: entry.is_primary,
            order,
            staged: None,
            position: 0,
            primary: false,
            cover: false,
        });
    }
    Ok(())
}

fn build_plan(raw: &RawJson) -> Result<Plan, RestoreError> {
    let mut projects: Vec<ProjectIn> = parse_list(raw.projects.as_deref())?;
    let mut yarns: Vec<YarnIn> = parse_list(raw.yarns.as_deref())?;
    let listed: Vec<CategoryIn> = parse_list(raw.categories.as_deref())?;
    if projects
        .iter()
        .map(|project| project.name.trim())
        .chain(yarns.iter().map(|yarn| yarn.name.trim()))
        .any(str::is_empty)
    {
        return Err(RestoreError::Invalid);
    }
    let mut categories: Vec<String> = Vec::new();
    let mut seen = HashSet::new();
    for name in listed
        .iter()
        .map(|category| category.name.as_str())
        .chain(projects.iter().filter_map(|p| p.category.as_deref()))
    {
        let name = name.trim();
        if !name.is_empty() && seen.insert(name.to_lowercase()) {
            categories.push(name.to_owned());
        }
    }
    let mut jobs = Vec::new();
    let mut skipped = 0;
    for (index, project) in projects.iter_mut().enumerate() {
        let images = std::mem::take(&mut project.images);
        add_entries(
            &mut jobs,
            &mut skipped,
            Target::Project(index),
            Kind::Title,
            images,
        )?;
        for (step_index, step) in project.steps.iter_mut().enumerate() {
            let images = std::mem::take(&mut step.images);
            add_entries(
                &mut jobs,
                &mut skipped,
                Target::Step(index, step_index),
                Kind::Step,
                images,
            )?;
        }
        let attachments = std::mem::take(&mut project.attachments);
        add_entries(
            &mut jobs,
            &mut skipped,
            Target::Project(index),
            Kind::Attachment,
            attachments,
        )?;
    }
    for (index, yarn) in yarns.iter_mut().enumerate() {
        let photos = std::mem::take(&mut yarn.photos);
        add_entries(
            &mut jobs,
            &mut skipped,
            Target::Yarn(index),
            Kind::Photo,
            photos,
        )?;
    }
    finalize_media(&mut jobs);
    Ok(Plan {
        projects,
        yarns,
        categories,
        jobs,
        skipped,
    })
}

/// Decide positions, primary flags and the project cover, so that a restored project looks like
/// the exported one. Positions are renumbered per list; ties keep the archive order.
fn finalize_media(jobs: &mut [MediaJob]) {
    let sort_key = |job: &MediaJob| (job.entry_position, job.order);
    let mut groups: BTreeMap<(Target, bool), Vec<usize>> = BTreeMap::new();
    for (index, job) in jobs.iter().enumerate() {
        groups
            .entry((job.target, job.kind == Kind::Attachment))
            .or_default()
            .push(index);
    }
    for indexes in groups.values_mut() {
        indexes.sort_by_key(|&index| sort_key(&jobs[index]));
        for (position, &index) in indexes.iter().enumerate() {
            jobs[index].position = if jobs[index].kind == Kind::Attachment {
                0
            } else {
                position as i64
            };
        }
    }
    // Yarn photos: the flagged one, else the first, is the primary photo.
    let mut photo_groups: BTreeMap<usize, Vec<usize>> = BTreeMap::new();
    // Project images: one cover over title and step images.
    let mut cover_groups: BTreeMap<usize, Vec<usize>> = BTreeMap::new();
    for (index, job) in jobs.iter().enumerate() {
        match job.target {
            Target::Yarn(yarn) if job.kind == Kind::Photo => {
                photo_groups.entry(yarn).or_default().push(index);
            }
            Target::Project(project) | Target::Step(project, _) if job.kind.is_image() => {
                cover_groups.entry(project).or_default().push(index);
            }
            _ => {}
        }
    }
    for indexes in photo_groups.values_mut() {
        indexes.sort_by_key(|&index| sort_key(&jobs[index]));
        let primary = indexes
            .iter()
            .copied()
            .find(|&index| jobs[index].entry_primary)
            .unwrap_or(indexes[0]);
        jobs[primary].primary = true;
    }
    for indexes in cover_groups.values_mut() {
        // Titles first, then step images; inside a list by position.
        indexes.sort_by_key(|&index| {
            let job = &jobs[index];
            (job.kind != Kind::Title, job.target, sort_key(job))
        });
        let cover = indexes
            .iter()
            .copied()
            .find(|&index| jobs[index].entry_primary)
            .unwrap_or(indexes[0]);
        jobs[cover].cover = true;
        let first_title = indexes
            .iter()
            .copied()
            .find(|&index| jobs[index].kind == Kind::Title);
        let primary_title = if jobs[cover].kind == Kind::Title {
            Some(cover)
        } else {
            first_title
        };
        if let Some(index) = primary_title {
            jobs[index].primary = true;
        }
    }
}

// ---------------------------------------------------------------------------
// Staging

/// Scratch directory below the media root, removed when dropped.
struct StageDir(PathBuf);

impl Drop for StageDir {
    fn drop(&mut self) {
        let _ = std::fs::remove_dir_all(&self.0);
    }
}

async fn stage_job(
    stage: &Path,
    index: usize,
    job: &MediaJob,
    bytes: Vec<u8>,
) -> Result<Staged, RestoreError> {
    if let Some(expected) = &job.sha256
        && !hex::encode(Sha256::digest(&bytes)).eq_ignore_ascii_case(expected.trim())
    {
        return Err(RestoreError::Invalid);
    }
    let size = bytes.len() as i64;
    let original = stage.join(format!("{index}.bin"));
    if job.kind.is_image() || job.kind == Kind::Photo {
        let prepared = media::process_image(bytes)
            .await
            .map_err(|_| RestoreError::Invalid)?;
        let thumbnail = stage.join(format!("{index}.thumb"));
        tokio::fs::write(&original, &prepared.bytes)
            .await
            .map_err(|_| RestoreError::Internal)?;
        tokio::fs::write(&thumbnail, &prepared.thumbnail)
            .await
            .map_err(|_| RestoreError::Internal)?;
        return Ok(Staged {
            path: original,
            thumbnail: Some(thumbnail),
            extension: prepared.extension.to_owned(),
            content_type: prepared.content_type.to_owned(),
            size,
            dimensions: Some((i64::from(prepared.width), i64::from(prepared.height))),
            is_safe_inline: true,
        });
    }
    let claimed = media::safe_mime_type(job.claimed_type.as_deref())
        .unwrap_or_else(|| "application/octet-stream".to_owned());
    let (content_type, extension, is_safe_inline, dimensions) =
        media::classify_attachment(&bytes, &claimed).await;
    let extension = extension.unwrap_or_else(|| media::safe_extension(&job.original_filename));
    tokio::fs::write(&original, &bytes)
        .await
        .map_err(|_| RestoreError::Internal)?;
    Ok(Staged {
        path: original,
        thumbnail: None,
        extension,
        content_type,
        size,
        dimensions: dimensions.map(|(width, height)| (i64::from(width), i64::from(height))),
        is_safe_inline,
    })
}

/// Second scan: read the referenced media files and stage them.
async fn stage_media(
    path: &Path,
    container: Container,
    stage: &Path,
    jobs: &mut [MediaJob],
) -> Result<(), RestoreError> {
    let mut wanted: HashMap<String, Vec<usize>> = HashMap::new();
    for (index, job) in jobs.iter().enumerate() {
        wanted.entry(job.rel.clone()).or_default().push(index);
    }
    let names: HashSet<String> = wanted.keys().cloned().collect();
    let (tx, mut rx) = tokio::sync::mpsc::channel::<(String, Vec<u8>)>(2);
    let scan_path = path.to_owned();
    let reader = tokio::task::spawn_blocking(move || {
        let mut seen = HashSet::new();
        scan(&scan_path, container, &mut |rel, reader, size| {
            if !names.contains(rel) || !seen.insert(rel.to_owned()) {
                return Ok(Flow::Continue);
            }
            if size > MAX_MEDIA_BYTES {
                return Err(RestoreError::Invalid);
            }
            let bytes = read_bounded(reader, MAX_MEDIA_BYTES)?;
            if tx.blocking_send((rel.to_owned(), bytes)).is_err() {
                // The consumer failed and reports the error.
                return Ok(Flow::Stop);
            }
            Ok(Flow::Continue)
        })?;
        Ok::<usize, RestoreError>(seen.len())
    });
    let mut failure = None;
    let mut received = HashSet::new();
    while let Some((rel, bytes)) = rx.recv().await {
        let Some(indexes) = wanted.get(&rel) else {
            continue;
        };
        received.insert(rel.clone());
        for &index in indexes {
            match stage_job(stage, index, &jobs[index], bytes.clone()).await {
                Ok(staged) => jobs[index].staged = Some(staged),
                Err(error) => {
                    failure = Some(error);
                    break;
                }
            }
        }
        if failure.is_some() {
            break;
        }
    }
    drop(rx);
    let scanned = reader.await.map_err(|_| RestoreError::Internal)?;
    if let Some(error) = failure {
        return Err(error);
    }
    scanned?;
    // A referenced file that is not in the archive fails the restore.
    if received.len() != wanted.len() {
        return Err(RestoreError::Invalid);
    }
    Ok(())
}

// ---------------------------------------------------------------------------
// Insert

struct Placed {
    subdir: &'static str,
    parent_id: i64,
    filename: String,
}

fn micros(value: DateTime<Utc>) -> String {
    value.to_rfc3339_opts(SecondsFormat::Micros, true)
}

/// The archive's creation time when it parses, else `now`.
fn created_at(value: Option<&str>, now: DateTime<Utc>) -> String {
    micros(
        value
            .and_then(crate::time::parse_utc)
            .map_or(now, |parsed| parsed.min(now)),
    )
}

fn clean(value: Option<&str>) -> Option<String> {
    value
        .map(str::trim)
        .filter(|value| !value.is_empty())
        .map(str::to_owned)
}

fn cap_name(name: &str) -> String {
    name.trim().chars().take(255).collect()
}

async fn move_file(from: &Path, to: &Path) -> io::Result<()> {
    if tokio::fs::rename(from, to).await.is_ok() {
        return Ok(());
    }
    tokio::fs::copy(from, to).await?;
    let _ = tokio::fs::remove_file(from).await;
    Ok(())
}

/// Move a staged file (and its thumbnail) to its final media path.
async fn place(
    root: &Path,
    subdir: &'static str,
    parent_id: i64,
    staged: &Staged,
    placed: &mut Vec<Placed>,
) -> io::Result<String> {
    let parent = parent_id.to_string();
    let filename = media::opaque_filename(&staged.extension);
    let directory = media::ensure_directory(root, &[subdir, &parent]).await?;
    move_file(&staged.path, &directory.join(&filename)).await?;
    placed.push(Placed {
        subdir,
        parent_id,
        filename: filename.clone(),
    });
    if let Some(thumbnail) = &staged.thumbnail {
        let directory = media::ensure_directory(root, &["thumbnails", subdir, &parent]).await?;
        move_file(thumbnail, &directory.join(media::thumbnail_name(&filename))).await?;
    }
    Ok(filename)
}

async fn category_id(
    tx: &mut Transaction<'static, Sqlite>,
    user_id: i64,
    name: &str,
    created: &mut usize,
) -> Result<i64, sqlx::Error> {
    if let Some(id) = sqlx::query_scalar::<_, i64>(
        "SELECT id FROM categories WHERE user_id = ? AND name = ? COLLATE NOCASE",
    )
    .bind(user_id)
    .bind(name)
    .fetch_optional(&mut **tx)
    .await?
    {
        return Ok(id);
    }
    let id = sqlx::query_scalar::<_, i64>(
        "INSERT INTO categories (user_id, name) VALUES (?, ?) RETURNING id",
    )
    .bind(user_id)
    .bind(name)
    .fetch_one(&mut **tx)
    .await?;
    *created += 1;
    Ok(id)
}

/// Insert every row, then place the files. Files already placed are listed in `placed` also on
/// failure, so the caller can remove them after the rollback.
async fn insert_all(
    state: &AppState,
    user_id: i64,
    plan: &Plan,
    root: Option<&Path>,
    placed: &mut Vec<Placed>,
) -> Result<Summary, RestoreError> {
    let mut tx = crate::db::begin_write(&state.pool).await?;
    // Stamped after the write lock is held, so no sync can see a newer watermark first.
    let now = Utc::now();
    let updated = micros(now);

    let mut created = 0;
    let mut category_ids: HashMap<String, i64> = HashMap::new();
    for name in &plan.categories {
        let id = category_id(&mut tx, user_id, name, &mut created).await?;
        category_ids.insert(name.to_lowercase(), id);
    }

    let mut yarn_new: Vec<i64> = Vec::with_capacity(plan.yarns.len());
    let mut yarn_by_old: HashMap<i64, i64> = HashMap::new();
    for yarn in &plan.yarns {
        let name = cap_name(&yarn.name);
        let id = sqlx::query_scalar::<_, i64>(
            "INSERT INTO yarns (owner_id, name, description, brand, colorway, dye_lot, \
             fiber_content, weight_category, recommended_needles, weight_grams, length_meters, \
             notes, link, link_archive, is_ai_enhanced, created_at, updated_at) \
             VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id",
        )
        .bind(user_id)
        .bind(&name)
        .bind(clean(yarn.description.as_deref()))
        .bind(clean(yarn.brand.as_deref()))
        .bind(clean(yarn.colorway.as_deref()))
        .bind(clean(yarn.dye_lot.as_deref()))
        .bind(clean(yarn.fiber_content.as_deref()))
        .bind(clean(yarn.weight_category.as_deref()))
        .bind(clean(yarn.recommended_needles.as_deref()))
        .bind(yarn.weight_grams)
        .bind(yarn.length_meters)
        .bind(clean(yarn.notes.as_deref()))
        .bind(clean(yarn.link.as_deref()))
        .bind(clean(yarn.link_archive.as_deref()))
        .bind(yarn.is_ai_enhanced)
        .bind(created_at(yarn.created_at.as_deref(), now))
        .bind(&updated)
        .fetch_one(&mut *tx)
        .await?;
        if yarn.is_favorite {
            sqlx::query("INSERT INTO user_favorite_yarns (user_id, yarn_id) VALUES (?, ?)")
                .bind(user_id)
                .bind(id)
                .execute(&mut *tx)
                .await?;
        }
        audit::record_with_data(
            &mut tx,
            user_id,
            Entity::Yarn,
            id,
            Action::Created,
            Some(&name),
            None,
        )
        .await?;
        enqueue_archive(
            state,
            &mut tx,
            user_id,
            true,
            id,
            yarn.link_archive.as_deref(),
            yarn.link.as_deref(),
        )
        .await?;
        yarn_new.push(id);
        yarn_by_old.insert(yarn.id, id);
    }

    let mut project_new: Vec<i64> = Vec::with_capacity(plan.projects.len());
    let mut project_by_old: HashMap<i64, i64> = HashMap::new();
    let mut step_new: HashMap<(usize, usize), i64> = HashMap::new();
    for (index, project) in plan.projects.iter().enumerate() {
        let name = cap_name(&project.name);
        let category = project
            .category
            .as_deref()
            .map(str::trim)
            .filter(|name| !name.is_empty())
            .and_then(|name| category_ids.get(&name.to_lowercase()).copied());
        let tags = serde_json::to_string(&project.tags).unwrap_or_else(|_| "[]".to_owned());
        let id = sqlx::query_scalar::<_, i64>(
            "INSERT INTO projects (owner_id, category_id, name, needles, stitch_sample, \
             description, notes, other_materials, tags, link, link_archive, is_ai_enhanced, \
             created_at, updated_at) \
             VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id",
        )
        .bind(user_id)
        .bind(category)
        .bind(&name)
        .bind(clean(project.needles.as_deref()))
        .bind(clean(project.stitch_sample.as_deref()))
        .bind(clean(project.description.as_deref()))
        .bind(clean(project.notes.as_deref()))
        .bind(clean(project.other_materials.as_deref()))
        .bind(tags)
        .bind(clean(project.link.as_deref()))
        .bind(clean(project.link_archive.as_deref()))
        .bind(project.is_ai_enhanced)
        .bind(created_at(project.created_at.as_deref(), now))
        .bind(&updated)
        .fetch_one(&mut *tx)
        .await?;
        for (position, step) in project.steps.iter().enumerate() {
            let step_number = if step.step_number > 0 {
                step.step_number
            } else {
                position as i64 + 1
            };
            let step_id = sqlx::query_scalar::<_, i64>(
                "INSERT INTO project_steps (project_id, title, description, step_number) \
                 VALUES (?, ?, ?, ?) RETURNING id",
            )
            .bind(id)
            .bind(step.title.as_deref().unwrap_or_default().trim())
            .bind(
                step.description
                    .as_deref()
                    .map(|text| text.trim().to_owned()),
            )
            .bind(step_number)
            .fetch_one(&mut *tx)
            .await?;
            step_new.insert((index, position), step_id);
        }
        if project.is_favorite {
            sqlx::query("INSERT INTO user_favorites (user_id, project_id) VALUES (?, ?)")
                .bind(user_id)
                .bind(id)
                .execute(&mut *tx)
                .await?;
        }
        audit::record_with_data(
            &mut tx,
            user_id,
            Entity::Project,
            id,
            Action::Created,
            Some(&name),
            None,
        )
        .await?;
        enqueue_archive(
            state,
            &mut tx,
            user_id,
            false,
            id,
            project.link_archive.as_deref(),
            project.link.as_deref(),
        )
        .await?;
        project_new.push(id);
        project_by_old.insert(project.id, id);
    }

    // Links, from either side; targets outside the archive are dropped.
    let mut links: HashSet<(i64, i64)> = HashSet::new();
    for (index, project) in plan.projects.iter().enumerate() {
        for old in &project.yarn_ids {
            if let Some(&yarn) = yarn_by_old.get(old) {
                links.insert((project_new[index], yarn));
            }
        }
    }
    for (index, yarn) in plan.yarns.iter().enumerate() {
        for old in &yarn.project_ids {
            if let Some(&project) = project_by_old.get(old) {
                links.insert((project, yarn_new[index]));
            }
        }
    }
    let mut links: Vec<_> = links.into_iter().collect();
    links.sort_unstable();
    for (project, yarn) in links {
        sqlx::query("INSERT INTO project_yarns (project_id, yarn_id) VALUES (?, ?)")
            .bind(project)
            .bind(yarn)
            .execute(&mut *tx)
            .await?;
    }

    // Media, in list order, so that ids follow the positions.
    let mut order: Vec<usize> = (0..plan.jobs.len()).collect();
    order.sort_by_key(|&index| {
        let job = &plan.jobs[index];
        (job.target, job.kind as u8, job.position, job.order)
    });
    let mut stored = 0;
    for index in order {
        let job = &plan.jobs[index];
        let root = root.ok_or(RestoreError::Unavailable)?;
        let staged = job.staged.as_ref().ok_or(RestoreError::Internal)?;
        let (subdir, parent_id, step_id, label) = match job.target {
            Target::Project(project) => ("projects", project_new[project], None, None),
            Target::Step(project, step) => (
                "projects",
                project_new[project],
                Some(
                    *step_new
                        .get(&(project, step))
                        .ok_or(RestoreError::Internal)?,
                ),
                None,
            ),
            Target::Yarn(yarn) => (
                "yarns",
                yarn_new[yarn],
                None,
                Some(cap_name(&plan.yarns[yarn].name)),
            ),
        };
        let filename = place(root, subdir, parent_id, staged, placed)
            .await
            .map_err(|_| RestoreError::Internal)?;
        let public_id = media_ids::allocate(&mut tx, job.kind.namespace()).await?;
        let alt_text = match (job.kind, clean(job.alt_text.as_deref())) {
            (Kind::Attachment, _) => String::new(),
            (_, Some(alt)) => alt,
            (_, None) => label.unwrap_or_else(|| job.original_filename.clone()),
        };
        let (project_id, yarn_id) = if subdir == "projects" {
            (Some(parent_id), None)
        } else {
            (None, Some(parent_id))
        };
        sqlx::query(
            "INSERT INTO media_files \
             (owner_id, project_id, yarn_id, step_id, kind, public_id, filename, original_filename, \
              content_type, size_bytes, width, height, alt_text, is_primary, position, \
              is_project_cover, is_safe_inline, created_at) \
             VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        )
        .bind(user_id)
        .bind(project_id)
        .bind(yarn_id)
        .bind(step_id)
        .bind(job.kind.column())
        .bind(public_id)
        .bind(&filename)
        .bind(&job.original_filename)
        .bind(&staged.content_type)
        .bind(staged.size)
        .bind(staged.dimensions.map(|(width, _)| width))
        .bind(staged.dimensions.map(|(_, height)| height))
        .bind(alt_text)
        .bind(job.primary)
        .bind(job.position)
        .bind(job.cover)
        .bind(staged.is_safe_inline)
        .bind(&updated)
        .execute(&mut *tx)
        .await?;
        stored += 1;
    }

    tx.commit().await?;
    Ok(Summary {
        format_version: FORMAT_VERSION,
        projects: plan.projects.len(),
        yarns: plan.yarns.len(),
        categories_created: created,
        media_files: stored,
        media_skipped: plan.skipped,
    })
}

/// A restored item with a link but no archived copy asks for one, like a new item does.
async fn enqueue_archive(
    state: &AppState,
    tx: &mut Transaction<'static, Sqlite>,
    user_id: i64,
    yarn: bool,
    id: i64,
    archived: Option<&str>,
    link: Option<&str>,
) -> Result<(), RestoreError> {
    if !state.archive_enabled || clean(archived).is_some() || clean(link).is_none() {
        return Ok(());
    }
    let kind = if yarn {
        crate::archive_jobs::ArchiveEntityKind::Yarn
    } else {
        crate::archive_jobs::ArchiveEntityKind::Project
    };
    crate::archive_jobs::enqueue_in_transaction(tx, user_id, kind, id)
        .await
        .map_err(|_| RestoreError::Internal)?;
    Ok(())
}

/// Remove files placed by a failed restore; what cannot be removed now is queued for the sweeper.
async fn discard(state: &AppState, root: &Path, placed: Vec<Placed>) {
    for file in placed {
        let removed = media::remove_stored_file(root, file.subdir, file.parent_id, &file.filename)
            .await
            && media::remove_stored_thumbnail(root, file.subdir, file.parent_id, &file.filename)
                .await;
        if !removed {
            let _ = sqlx::query(
                "INSERT OR IGNORE INTO media_gc (filename, subdir, parent_id) VALUES (?, ?, ?)",
            )
            .bind(&file.filename)
            .bind(file.subdir)
            .bind(file.parent_id)
            .execute(&state.pool)
            .await;
        }
    }
}

// ---------------------------------------------------------------------------
// Entry point

fn random_suffix() -> String {
    use rand::TryRng;
    let mut random = [0_u8; 8];
    rand::rngs::SysRng
        .try_fill_bytes(&mut random)
        .expect("the operating system random number generator failed");
    hex::encode(random)
}

/// Stream the body into a temporary file, at most `limit` bytes.
async fn receive(
    body: Body,
    declared: Option<u64>,
    limit: u64,
    directory: &Path,
) -> Result<tempfile::NamedTempFile, RestoreError> {
    if declared.is_some_and(|length| length > limit) {
        return Err(RestoreError::TooLarge);
    }
    let named = tempfile::Builder::new()
        .prefix(UPLOAD_PREFIX)
        .tempfile_in(directory)
        .map_err(|_| RestoreError::Internal)?;
    let mut file = tokio::fs::File::from_std(named.reopen().map_err(|_| RestoreError::Internal)?);
    let mut stream = body.into_data_stream();
    let mut total = 0_u64;
    loop {
        let next = tokio::time::timeout(IDLE_TIMEOUT, stream.next())
            .await
            .map_err(|_| RestoreError::Timeout)?;
        let Some(chunk) = next else { break };
        let chunk = chunk.map_err(|_| RestoreError::Invalid)?;
        total += chunk.len() as u64;
        if total > limit {
            return Err(RestoreError::TooLarge);
        }
        file.write_all(&chunk)
            .await
            .map_err(|_| RestoreError::Internal)?;
    }
    file.flush().await.map_err(|_| RestoreError::Internal)?;
    Ok(named)
}

/// Restore the archive in `body` for `user_id`.
pub(crate) async fn restore(
    state: &AppState,
    user_id: i64,
    body: Body,
    declared_length: Option<u64>,
    limit: u64,
) -> Result<Summary, RestoreError> {
    let root = state.media_root.clone();
    let upload_dir = match &root {
        Some(root) => {
            tokio::fs::create_dir_all(root)
                .await
                .map_err(|_| RestoreError::Unavailable)?;
            root.clone()
        }
        None => std::env::temp_dir(),
    };
    let upload = receive(body, declared_length, limit, &upload_dir).await?;
    let path = upload.path().to_owned();

    let container = detect(&path)?;
    let json_path = path.clone();
    let raw = tokio::task::spawn_blocking(move || read_json(&json_path, container))
        .await
        .map_err(|_| RestoreError::Internal)??;
    let mut plan = build_plan(&raw)?;
    drop(raw);

    let mut stage = None;
    if !plan.jobs.is_empty() {
        let root = root.as_deref().ok_or(RestoreError::Unavailable)?;
        let directory = root.join(format!("{STAGE_PREFIX}{}", random_suffix()));
        tokio::fs::create_dir_all(&directory)
            .await
            .map_err(|_| RestoreError::Internal)?;
        let directory = StageDir(directory);
        stage_media(&path, container, &directory.0, &mut plan.jobs).await?;
        stage = Some(directory);
    }

    let mut placed = Vec::new();
    let result = insert_all(state, user_id, &plan, root.as_deref(), &mut placed).await;
    if result.is_err()
        && let Some(root) = root.as_deref()
    {
        discard(state, root, placed).await;
    }
    drop(stage);
    drop(upload);
    result
}
