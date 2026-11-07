//! Per-user data export: one gzip-compressed tar with everything the caller can read.
//!
//! The archive format is the contract in `docs/data-export.md`. The JSON is
//! built first (the access rules are the ones of sync), then the archive is written
//! on a blocking thread and streamed to the client through a bounded channel,
//! so large media never sit in memory.

use std::{
    collections::{HashMap, HashSet},
    io::{self, Read, Write},
    pin::Pin,
    sync::{Arc, Mutex, PoisonError},
    task::{Context, Poll},
};

use axum::{
    body::{Body, Bytes},
    http::{
        StatusCode,
        header::{CACHE_CONTROL, CONTENT_DISPOSITION, CONTENT_TYPE},
    },
    response::{IntoResponse, Response},
};
use chrono::{SecondsFormat, Utc};
use serde_json::{Map, Value, json};
use sha2::{Digest, Sha256};
use tokio::sync::mpsc;
use zip::{CompressionMethod, ZipWriter, write::SimpleFileOptions};

use crate::{
    db::AppState,
    projects::TagPolicy,
    resource_access::{
        AccessInfo, ResourceKind, Surface, derived_yarn_sql, list_access, visible_sql,
    },
};

const FORMAT: &str = "stricknani-export";
const FORMAT_VERSION: u32 = 1;
const CHUNK: usize = 64 * 1024;
/// Channel depth in chunks: bounds the memory held for a slow client.
const CHANNEL_DEPTH: usize = 8;
/// Key that marks a media entry until its file has been written.
const JOB_KEY: &str = "_job";

/// Users with an export in progress: one export per user at a time.
#[derive(Clone, Default)]
pub(crate) struct ExportLimiter(Arc<Mutex<HashSet<i64>>>);

impl ExportLimiter {
    /// Claim the user's export slot, or `None` when an export is running.
    pub(crate) fn begin(&self, user_id: i64) -> Option<ExportGuard> {
        let mut running = self.0.lock().unwrap_or_else(PoisonError::into_inner);
        running.insert(user_id).then(|| ExportGuard {
            running: self.0.clone(),
            user_id,
        })
    }
}

/// Releases the user's export slot when dropped, also when the client
/// disconnects and the response body is dropped unread.
pub(crate) struct ExportGuard {
    running: Arc<Mutex<HashSet<i64>>>,
    user_id: i64,
}

impl Drop for ExportGuard {
    fn drop(&mut self) {
        self.running
            .lock()
            .unwrap_or_else(PoisonError::into_inner)
            .remove(&self.user_id);
    }
}

/// A media file to copy into the archive.
struct MediaJob {
    subdir: &'static str,
    parent_id: i64,
    filename: String,
    archive_path: String,
}

enum Outcome {
    Written {
        size: u64,
        sha256: String,
        path: std::path::PathBuf,
    },
    Missing,
}

/// Everything the archive describes, read before any media file.
pub(crate) struct Snapshot {
    account: Value,
    categories: Vec<Value>,
    projects: Vec<Value>,
    yarns: Vec<Value>,
    jobs: Vec<MediaJob>,
}

type MediaRow = (
    i64,
    Option<i64>,
    String,
    String,
    String,
    String,
    i64,
    String,
    bool,
    i64,
    bool,
);

const MEDIA_COLUMNS: &str = "id, step_id, kind, filename, original_filename, content_type, \
     size_bytes, alt_text, is_primary, position, is_project_cover";

/// One media entry; `jobs` gets the file so that the writer can fill in the
/// archive path, the size and the checksum afterwards.
fn media_entry(
    jobs: &mut Vec<MediaJob>,
    subdir: &'static str,
    parent_id: i64,
    row: &MediaRow,
    is_primary: bool,
) -> Value {
    let index = jobs.len();
    jobs.push(MediaJob {
        subdir,
        parent_id,
        filename: row.3.clone(),
        archive_path: format!("media/{subdir}/{parent_id}/{}", row.3),
    });
    let optional = |value: &str| (!value.is_empty()).then(|| value.to_owned());
    json!({
        JOB_KEY: index,
        "id": row.0,
        "original_filename": optional(&row.4),
        "content_type": optional(&row.5),
        "size_bytes": row.6,
        "alt_text": row.7,
        "position": row.9,
        "is_primary": is_primary,
    })
}

fn db_error(message: impl ToString) -> sqlx::Error {
    sqlx::Error::Protocol(message.to_string())
}

/// Read what `user_id` can read. Items are listed with the sync visibility
/// rules (own, shared, derived yarns) and loaded with the v2 detail builders;
/// the caller's role is the share role, never the administrator role.
pub(crate) async fn snapshot(state: &AppState, user_id: i64) -> Result<Snapshot, sqlx::Error> {
    let mut read = state.pool.begin().await?;
    let email = sqlx::query_scalar::<_, String>("SELECT email FROM users WHERE id = ?")
        .bind(user_id)
        .fetch_one(&mut *read)
        .await?;
    let categories = sqlx::query_as::<_, (i64, String)>(
        "SELECT id, name FROM categories WHERE user_id = ? ORDER BY id",
    )
    .bind(user_id)
    .fetch_all(&mut *read)
    .await?
    .into_iter()
    .map(|(id, name)| json!({"id": id, "name": name}))
    .collect();
    let project_sql = format!(
        "SELECT r.id, r.owner_id, \
                (SELECT s.role FROM resource_shares s WHERE s.kind = 'project' \
                 AND s.resource_id = r.id AND s.user_id = ?1) \
         FROM projects r WHERE {} ORDER BY r.id",
        visible_sql(ResourceKind::Project, "r", 1)
    );
    let project_rows =
        sqlx::query_as::<_, (i64, i64, Option<String>)>(sqlx::AssertSqlSafe(project_sql.as_str()))
            .bind(user_id)
            .fetch_all(&mut *read)
            .await?;
    let yarn_sql = format!(
        "SELECT r.id, r.owner_id, \
                (SELECT s.role FROM resource_shares s WHERE s.kind = 'yarn' \
                 AND s.resource_id = r.id AND s.user_id = ?1), \
                {} \
         FROM yarns r WHERE {} ORDER BY r.id",
        derived_yarn_sql("r", 1),
        visible_sql(ResourceKind::Yarn, "r", 1)
    );
    let yarn_rows = sqlx::query_as::<_, (i64, i64, Option<String>, bool)>(sqlx::AssertSqlSafe(
        yarn_sql.as_str(),
    ))
    .bind(user_id)
    .fetch_all(&mut *read)
    .await?;
    drop(read);

    let mut jobs = Vec::new();
    let mut projects = Vec::with_capacity(project_rows.len());
    for (id, owner_id, role) in project_rows {
        let Some(project) = crate::projects::load_project_with_tag_policy(
            state,
            user_id,
            id,
            TagPolicy::PreserveLabels,
        )
        .await?
        else {
            continue;
        };
        let access = list_access(user_id, owner_id, false, role.as_deref(), false);
        let rows = sqlx::query_as::<_, MediaRow>(sqlx::AssertSqlSafe(format!(
            "SELECT {MEDIA_COLUMNS} FROM media_files WHERE project_id = ? \
             ORDER BY CASE kind WHEN 'title' THEN 0 WHEN 'step' THEN 1 ELSE 2 END, \
                      step_id, position, id"
        )))
        .bind(id)
        .fetch_all(&state.pool)
        .await?;
        projects.push(project_value(&mut jobs, project, access, &rows)?);
    }
    let project_ids: HashSet<i64> = projects.iter().filter_map(|p| p["id"].as_i64()).collect();

    let mut yarns = Vec::with_capacity(yarn_rows.len());
    for (id, owner_id, role, derived) in yarn_rows {
        let Some(yarn) = crate::yarns::load_for_owner(state, user_id, id, Surface::V2).await?
        else {
            continue;
        };
        let access = list_access(user_id, owner_id, false, role.as_deref(), derived);
        let rows = sqlx::query_as::<_, MediaRow>(sqlx::AssertSqlSafe(format!(
            "SELECT {MEDIA_COLUMNS} FROM media_files WHERE yarn_id = ? AND kind = 'photo' \
             ORDER BY position, id"
        )))
        .bind(id)
        .fetch_all(&state.pool)
        .await?;
        yarns.push(yarn_value(&mut jobs, yarn, access, &rows, &project_ids)?);
    }
    Ok(Snapshot {
        account: json!({"id": user_id, "email": email}),
        categories,
        projects,
        yarns,
        jobs,
    })
}

fn object(value: Value) -> Result<Map<String, Value>, sqlx::Error> {
    match value {
        Value::Object(map) => Ok(map),
        _ => Err(db_error("DTO was not an object")),
    }
}

fn project_value(
    jobs: &mut Vec<MediaJob>,
    project: crate::projects::ProjectResponse,
    access: AccessInfo,
    rows: &[MediaRow],
) -> Result<Value, sqlx::Error> {
    let project_id = project.id;
    let mut map = object(serde_json::to_value(&project).map_err(db_error)?)?;
    // The canonical cover is the lowest-id image flagged as cover.
    let cover = rows
        .iter()
        .filter(|row| matches!(row.2.as_str(), "title" | "step") && row.10)
        .map(|row| row.0)
        .min();
    let mut images = Vec::new();
    let mut attachments = Vec::new();
    let mut step_images: HashMap<i64, Vec<Value>> = HashMap::new();
    for row in rows {
        match row.2.as_str() {
            "title" => {
                let entry = media_entry(jobs, "projects", project_id, row, cover == Some(row.0));
                images.push(entry);
            }
            "step" => {
                let entry = media_entry(jobs, "projects", project_id, row, cover == Some(row.0));
                if let Some(step_id) = row.1 {
                    step_images.entry(step_id).or_default().push(entry);
                }
            }
            "attachment" => {
                attachments.push(media_entry(jobs, "projects", project_id, row, false));
            }
            _ => {}
        }
    }
    if let Some(Value::Array(steps)) = map.get_mut("steps") {
        for step in steps {
            let step_id = step["id"].as_i64().unwrap_or_default();
            if let Some(step) = step.as_object_mut() {
                step.insert(
                    "images".into(),
                    Value::Array(step_images.remove(&step_id).unwrap_or_default()),
                );
            }
        }
    }
    map.insert("images".into(), Value::Array(images));
    map.insert("attachments".into(), Value::Array(attachments));
    map.insert(
        "access".into(),
        serde_json::to_value(access).map_err(db_error)?,
    );
    Ok(Value::Object(map))
}

fn yarn_value(
    jobs: &mut Vec<MediaJob>,
    yarn: crate::yarns::YarnResponse,
    access: AccessInfo,
    rows: &[MediaRow],
    project_ids: &HashSet<i64>,
) -> Result<Value, sqlx::Error> {
    let yarn_id = yarn.id;
    let mut map = object(serde_json::to_value(&yarn).map_err(db_error)?)?;
    let primary = rows.iter().find(|row| row.8).map(|row| row.0);
    let photos = rows
        .iter()
        .map(|row| media_entry(jobs, "yarns", yarn_id, row, primary == Some(row.0)))
        .collect();
    map.insert("photos".into(), Value::Array(photos));
    // Only projects that are part of the archive are linked.
    if let Some(Value::Array(ids)) = map.get_mut("project_ids") {
        ids.retain(|id| id.as_i64().is_some_and(|id| project_ids.contains(&id)));
    }
    map.insert(
        "access".into(),
        serde_json::to_value(access).map_err(db_error)?,
    );
    Ok(Value::Object(map))
}

/// Replace the media placeholders of `value` with their final entries.
fn finalize(value: &mut Value, jobs: &[MediaJob], outcomes: &[Outcome]) {
    match value {
        Value::Object(map) => {
            if let Some(index) = map.remove(JOB_KEY).and_then(|index| index.as_u64()) {
                let index = index as usize;
                match &outcomes[index] {
                    Outcome::Written { size, sha256, .. } => {
                        map.insert("file".into(), json!(jobs[index].archive_path));
                        map.insert("size_bytes".into(), json!(size));
                        map.insert("sha256".into(), json!(sha256));
                    }
                    Outcome::Missing => {
                        map.insert("file".into(), Value::Null);
                        map.insert("missing".into(), Value::Bool(true));
                        map.insert("sha256".into(), Value::Null);
                    }
                }
            } else {
                map.values_mut()
                    .for_each(|child| finalize(child, jobs, outcomes));
            }
        }
        Value::Array(items) => items
            .iter_mut()
            .for_each(|child| finalize(child, jobs, outcomes)),
        _ => {}
    }
}

type Chunk = Result<Bytes, io::Error>;

/// Sync `Write` that forwards buffered chunks to the response body. A closed
/// receiver (client gone) turns into a `BrokenPipe` and stops the archive.
struct ChannelWriter {
    tx: mpsc::Sender<Chunk>,
    buffer: Vec<u8>,
}

impl ChannelWriter {
    fn send_buffer(&mut self) -> io::Result<()> {
        if self.buffer.is_empty() {
            return Ok(());
        }
        let chunk = Bytes::from(std::mem::replace(
            &mut self.buffer,
            Vec::with_capacity(CHUNK),
        ));
        self.tx
            .blocking_send(Ok(chunk))
            .map_err(|_| io::Error::from(io::ErrorKind::BrokenPipe))
    }
}

impl Write for ChannelWriter {
    fn write(&mut self, data: &[u8]) -> io::Result<usize> {
        self.buffer.extend_from_slice(data);
        if self.buffer.len() >= CHUNK {
            self.send_buffer()?;
        }
        Ok(data.len())
    }

    fn flush(&mut self) -> io::Result<()> {
        self.send_buffer()
    }
}

/// Response body: the channel stream, holding the user's export slot until the
/// body is finished or dropped.
struct ArchiveBody {
    rx: mpsc::Receiver<Chunk>,
    _guard: ExportGuard,
}

impl tokio_stream::Stream for ArchiveBody {
    type Item = Chunk;

    fn poll_next(mut self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<Option<Chunk>> {
        self.rx.poll_recv(cx)
    }
}

/// Archive container requested by the client.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Default)]
pub(crate) enum ArchiveFormat {
    #[default]
    TarGz,
    Zip,
}

impl ArchiveFormat {
    pub(crate) fn parse(value: Option<&str>) -> Option<Self> {
        match value {
            None | Some("tar.gz") => Some(Self::TarGz),
            Some("zip") => Some(Self::Zip),
            Some(_) => None,
        }
    }

    const fn extension(self) -> &'static str {
        match self {
            Self::TarGz => "tar.gz",
            Self::Zip => "zip",
        }
    }

    const fn content_type(self) -> &'static str {
        match self {
            Self::TarGz => "application/gzip",
            Self::Zip => "application/zip",
        }
    }
}

/// What the archive content code needs from a container.
trait Sink {
    /// Add an in-memory JSON file (compressed by the container if it can).
    fn add_json(&mut self, path: &str, bytes: &[u8]) -> io::Result<()>;
    /// Add a media file of exactly `size` bytes read from `file`.
    fn add_media(&mut self, path: &str, size: u64, file: std::fs::File) -> io::Result<()>;
    fn finish(self: Box<Self>) -> io::Result<()>;
}

struct TarSink {
    tar: tar::Builder<flate2::write::GzEncoder<ChannelWriter>>,
    mtime: u64,
}

impl TarSink {
    fn header(&self, size: u64) -> tar::Header {
        let mut header = tar::Header::new_ustar();
        header.set_entry_type(tar::EntryType::Regular);
        header.set_size(size);
        header.set_mode(0o644);
        header.set_mtime(self.mtime);
        header
    }
}

impl Sink for TarSink {
    fn add_json(&mut self, path: &str, bytes: &[u8]) -> io::Result<()> {
        let mut header = self.header(bytes.len() as u64);
        self.tar.append_data(&mut header, path, bytes)
    }

    fn add_media(&mut self, path: &str, size: u64, file: std::fs::File) -> io::Result<()> {
        let mut header = self.header(size);
        // Exactly the hashed size; a file that shrank fails the download.
        self.tar.append_data(&mut header, path, file.take(size))
    }

    fn finish(self: Box<Self>) -> io::Result<()> {
        let mut out = self.tar.into_inner()?.finish()?;
        out.flush()
    }
}

struct ZipSink {
    zip: ZipWriter<zip::write::StreamWriter<ChannelWriter>>,
}

impl Sink for ZipSink {
    fn add_json(&mut self, path: &str, bytes: &[u8]) -> io::Result<()> {
        let options = SimpleFileOptions::default()
            .compression_method(CompressionMethod::Deflated)
            .unix_permissions(0o644);
        self.zip
            .start_file(path, options)
            .map_err(io::Error::from)?;
        self.zip.write_all(bytes)
    }

    fn add_media(&mut self, path: &str, size: u64, file: std::fs::File) -> io::Result<()> {
        let options = SimpleFileOptions::default()
            .compression_method(CompressionMethod::Stored)
            .unix_permissions(0o644)
            .large_file(true);
        self.zip
            .start_file(path, options)
            .map_err(io::Error::from)?;
        let copied = io::copy(&mut file.take(size), &mut self.zip)?;
        if copied == size {
            Ok(())
        } else {
            Err(io::Error::from(io::ErrorKind::UnexpectedEof))
        }
    }

    fn finish(self: Box<Self>) -> io::Result<()> {
        let mut out = self.zip.finish().map_err(io::Error::from)?;
        out.flush()
    }
}

/// First pass over the media: tar needs every size up front and the JSON
/// carries the checksums, so each existing file is hashed before anything
/// is written.
fn inspect_media(
    jobs: &[MediaJob],
    media_root: Option<&std::path::Path>,
    runtime: &tokio::runtime::Handle,
) -> io::Result<Vec<Outcome>> {
    let mut buffer = vec![0_u8; CHUNK];
    let mut outcomes = Vec::with_capacity(jobs.len());
    for job in jobs {
        let opened = media_root
            .and_then(|root| {
                runtime
                    .block_on(crate::media::existing_file_path(
                        root,
                        job.subdir,
                        job.parent_id,
                        &job.filename,
                    ))
                    .ok()
                    .flatten()
            })
            .and_then(|path| std::fs::File::open(&path).ok().map(|file| (path, file)));
        let Some((path, mut file)) = opened else {
            outcomes.push(Outcome::Missing);
            continue;
        };
        let mut hasher = Sha256::new();
        let mut size = 0_u64;
        let complete = loop {
            match file.read(&mut buffer) {
                Ok(0) => break true,
                Ok(read) => {
                    hasher.update(&buffer[..read]);
                    size += read as u64;
                }
                Err(_) => break false,
            }
        };
        outcomes.push(if complete {
            Outcome::Written {
                size,
                sha256: hex::encode(hasher.finalize()),
                path,
            }
        } else {
            Outcome::Missing
        });
    }
    Ok(outcomes)
}

fn write_archive(
    snapshot: Snapshot,
    media_root: Option<std::path::PathBuf>,
    runtime: &tokio::runtime::Handle,
    format: ArchiveFormat,
    out: ChannelWriter,
) -> io::Result<()> {
    let Snapshot {
        account,
        categories,
        mut projects,
        mut yarns,
        jobs,
    } = snapshot;
    let now = Utc::now();
    let mtime = now.timestamp().max(0) as u64;
    let top = format!("stricknani-export-{}", now.format("%Y-%m-%d"));

    let outcomes = inspect_media(&jobs, media_root.as_deref(), runtime)?;
    let media_files = outcomes
        .iter()
        .filter(|outcome| matches!(outcome, Outcome::Written { .. }))
        .count();
    projects
        .iter_mut()
        .chain(yarns.iter_mut())
        .for_each(|item| finalize(item, &jobs, &outcomes));
    let manifest = json!({
        "format": FORMAT,
        "format_version": FORMAT_VERSION,
        "created_at": now.to_rfc3339_opts(SecondsFormat::Secs, true),
        "server": {
            "version": env!("CARGO_PKG_VERSION"),
            "revision": crate::error_reporting::git_revision(),
        },
        "account": account,
        "counts": {
            "projects": projects.len(),
            "yarns": yarns.len(),
            "categories": categories.len(),
            "media_files": media_files,
        },
    });

    let mut sink: Box<dyn Sink> = match format {
        ArchiveFormat::TarGz => Box::new(TarSink {
            tar: tar::Builder::new(flate2::write::GzEncoder::new(
                out,
                flate2::Compression::default(),
            )),
            mtime,
        }),
        ArchiveFormat::Zip => Box::new(ZipSink {
            zip: ZipWriter::new_stream(out),
        }),
    };
    for (name, value) in [
        ("manifest.json", manifest),
        ("projects.json", Value::Array(projects)),
        ("yarns.json", Value::Array(yarns)),
        ("categories.json", Value::Array(categories)),
    ] {
        let bytes = serde_json::to_vec_pretty(&value).map_err(io::Error::other)?;
        sink.add_json(&format!("{top}/{name}"), &bytes)?;
    }
    for (job, outcome) in jobs.iter().zip(&outcomes) {
        let Outcome::Written { size, path, .. } = outcome else {
            continue;
        };
        let file = std::fs::File::open(path)?;
        sink.add_media(&format!("{top}/{}", job.archive_path), *size, file)?;
    }
    sink.finish()
}

/// Start the archive stream. The returned response owns `guard` until its body is
/// finished or dropped.
pub(crate) fn response(
    state: &AppState,
    snapshot: Snapshot,
    format: ArchiveFormat,
    guard: ExportGuard,
) -> Response {
    let (tx, rx) = mpsc::channel::<Chunk>(CHANNEL_DEPTH);
    let media_root = state.media_root.clone();
    let runtime = tokio::runtime::Handle::current();
    let error_tx = tx.clone();
    tokio::task::spawn_blocking(move || {
        let out = ChannelWriter {
            tx,
            buffer: Vec::with_capacity(CHUNK),
        };
        if let Err(error) = write_archive(snapshot, media_root, &runtime, format, out) {
            // A truncated archive must fail the download, not look complete.
            let _ = error_tx.blocking_send(Err(error));
        }
    });
    let filename = format!(
        "stricknani-export-{}.{}",
        Utc::now().format("%Y-%m-%d"),
        format.extension()
    );
    (
        StatusCode::OK,
        [
            (CONTENT_TYPE, format.content_type().to_owned()),
            (
                CONTENT_DISPOSITION,
                format!("attachment; filename=\"{filename}\""),
            ),
            (CACHE_CONTROL, "no-store".to_owned()),
        ],
        Body::from_stream(ArchiveBody { rx, _guard: guard }),
    )
        .into_response()
}
