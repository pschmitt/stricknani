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
    time::{Duration, Instant},
};

use axum::{
    body::{Body, Bytes},
    http::{
        StatusCode,
        header::{CACHE_CONTROL, CONTENT_DISPOSITION, CONTENT_TYPE},
    },
    response::{IntoResponse, Response},
};
use base64::{Engine as _, engine::general_purpose::URL_SAFE_NO_PAD};
use chrono::{DateTime, SecondsFormat, Utc};
use serde_json::{Map, Value, json};
use sha2::{Digest, Sha256};
use tokio::sync::mpsc;
use zeroize::Zeroizing;
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

/// Passphrase of an encrypted export; zeroed when dropped.
pub(crate) type Passphrase = Zeroizing<String>;

/// Shortest and longest accepted passphrase, in bytes of UTF-8.
pub(crate) const PASSPHRASE_BYTES: std::ops::RangeInclusive<usize> = 12..=1024;
/// How long a download ticket stays valid.
pub(crate) const TICKET_TTL: Duration = Duration::from_secs(5 * 60);
/// Tickets one user may hold at a time; the oldest is dropped beyond it.
const TICKETS_PER_USER: usize = 8;
/// scrypt work factor (`N = 2^18`, 256 MiB) of encrypted exports: the default of the age CLI.
const EXPORT_WORK_FACTOR: u8 = 18;

struct Ticket {
    user_id: i64,
    format: ArchiveFormat,
    passphrase: Passphrase,
    expires: Instant,
    issued: Instant,
}

/// Download tickets of encrypted exports. They live in memory only, with the
/// passphrase, are bound to one account and are valid once until they expire.
#[derive(Clone)]
pub(crate) struct ExportTickets {
    tickets: Arc<Mutex<HashMap<String, Ticket>>>,
    ttl: Duration,
}

impl ExportTickets {
    pub(crate) fn new(ttl: Duration) -> Self {
        Self {
            tickets: Arc::default(),
            ttl,
        }
    }

    fn lock(&self) -> std::sync::MutexGuard<'_, HashMap<String, Ticket>> {
        self.tickets.lock().unwrap_or_else(PoisonError::into_inner)
    }

    /// Store a ticket and return its token with the time it expires.
    pub(crate) fn issue(
        &self,
        user_id: i64,
        format: ArchiveFormat,
        passphrase: Passphrase,
    ) -> (String, DateTime<Utc>) {
        use rand::TryRng;
        let mut random = [0_u8; 32];
        rand::rngs::SysRng
            .try_fill_bytes(&mut random)
            .expect("the operating system random number generator failed");
        let token = URL_SAFE_NO_PAD.encode(random);
        let now = Instant::now();
        let mut tickets = self.lock();
        tickets.retain(|_, ticket| ticket.expires > now);
        let own: Vec<(String, Instant)> = tickets
            .iter()
            .filter(|(_, ticket)| ticket.user_id == user_id)
            .map(|(token, ticket)| (token.clone(), ticket.issued))
            .collect();
        if own.len() >= TICKETS_PER_USER
            && let Some((oldest, _)) = own.into_iter().min_by_key(|(_, issued)| *issued)
        {
            tickets.remove(&oldest);
        }
        tickets.insert(
            token.clone(),
            Ticket {
                user_id,
                format,
                passphrase,
                expires: now + self.ttl,
                issued: now,
            },
        );
        let expires_at = Utc::now() + chrono::Duration::from_std(self.ttl).unwrap_or_default();
        (token, expires_at)
    }

    /// Whether `token` is a live ticket of `user_id`. Does not use it up.
    pub(crate) fn is_valid(&self, token: &str, user_id: i64) -> bool {
        self.lock()
            .get(token)
            .is_some_and(|ticket| ticket.user_id == user_id && ticket.expires > Instant::now())
    }

    /// Use the ticket up. `None` for an unknown, used, expired or foreign ticket;
    /// a foreign ticket stays valid for its owner.
    pub(crate) fn redeem(&self, token: &str, user_id: i64) -> Option<(ArchiveFormat, Passphrase)> {
        let mut tickets = self.lock();
        let now = Instant::now();
        tickets.retain(|_, ticket| ticket.expires > now);
        if tickets.get(token)?.user_id != user_id {
            return None;
        }
        let ticket = tickets.remove(token)?;
        Some((ticket.format, ticket.passphrase))
    }
}

/// The archive's destination: the response channel, directly or through age.
enum Target {
    Plain(ChannelWriter),
    Age(Option<age::stream::StreamWriter<ChannelWriter>>),
}

/// Cloneable handle on the [`Target`], so that the age stream can be finished
/// after the container writers (which own a handle) are done.
#[derive(Clone)]
struct Output(Arc<Mutex<Target>>);

impl Output {
    fn new(channel: ChannelWriter, passphrase: Option<Passphrase>) -> io::Result<Self> {
        let target = match passphrase {
            None => Target::Plain(channel),
            Some(passphrase) => {
                let mut recipient = age::scrypt::Recipient::new(age::secrecy::SecretString::from(
                    passphrase.as_str().to_owned(),
                ));
                recipient.set_work_factor(EXPORT_WORK_FACTOR);
                let encryptor = age::Encryptor::with_recipients(std::iter::once(
                    &recipient as &dyn age::Recipient,
                ))
                .map_err(io::Error::other)?;
                Target::Age(Some(encryptor.wrap_output(channel)?))
            }
        };
        Ok(Self(Arc::new(Mutex::new(target))))
    }

    fn with<T>(&self, f: impl FnOnce(&mut Target) -> io::Result<T>) -> io::Result<T> {
        f(&mut self.0.lock().unwrap_or_else(PoisonError::into_inner))
    }

    /// Write the last bytes (for age: the final authenticated chunk) and flush.
    fn finish(&self) -> io::Result<()> {
        self.with(|target| match target {
            Target::Plain(channel) => channel.flush(),
            Target::Age(writer) => {
                let writer = writer
                    .take()
                    .ok_or_else(|| io::Error::from(io::ErrorKind::BrokenPipe))?;
                writer.finish()?.flush()
            }
        })
    }
}

impl Write for Output {
    fn write(&mut self, data: &[u8]) -> io::Result<usize> {
        self.with(|target| match target {
            Target::Plain(channel) => channel.write(data),
            Target::Age(Some(writer)) => writer.write(data),
            Target::Age(None) => Err(io::Error::from(io::ErrorKind::BrokenPipe)),
        })
    }

    fn flush(&mut self) -> io::Result<()> {
        self.with(|target| match target {
            Target::Plain(channel) => channel.flush(),
            Target::Age(Some(writer)) => writer.flush(),
            Target::Age(None) => Ok(()),
        })
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

    pub(crate) const fn extension(self) -> &'static str {
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
    tar: tar::Builder<flate2::write::GzEncoder<Output>>,
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
    zip: ZipWriter<zip::write::StreamWriter<Output>>,
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
        // Deflated, not stored: streamed entries carry their sizes in a data descriptor,
        // and sequential readers (java.util.zip.ZipInputStream) reject a stored entry
        // with one. Level 1 keeps the cost low for already compressed photos.
        let options = SimpleFileOptions::default()
            .compression_method(CompressionMethod::Deflated)
            .compression_level(Some(1))
            .unix_permissions(0o644)
            // zip64 fields only where an entry needs them: they switch the data
            // descriptor to 64-bit sizes, which ZipInputStream misreads. The archive
            // itself may still grow past 4 GiB (zip64 end records and offsets).
            .large_file(size >= u64::from(u32::MAX));
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
    channel: ChannelWriter,
    passphrase: Option<Passphrase>,
) -> io::Result<()> {
    let out = Output::new(channel, passphrase)?;
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
            // Fast compression: photos dominate an export and are already compressed, so a
            // higher level costs several times the CPU for almost no size.
            tar: tar::Builder::new(flate2::write::GzEncoder::new(
                out.clone(),
                flate2::Compression::fast(),
            )),
            mtime,
        }),
        ArchiveFormat::Zip => Box::new(ZipSink {
            zip: ZipWriter::new_stream(out.clone()),
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
    sink.finish()?;
    out.finish()
}

/// Start the archive stream, age-encrypted when a passphrase is given. The returned
/// response owns `guard` until its body is finished or dropped.
pub(crate) fn response(
    state: &AppState,
    snapshot: Snapshot,
    format: ArchiveFormat,
    guard: ExportGuard,
    passphrase: Option<Passphrase>,
) -> Response {
    let (tx, rx) = mpsc::channel::<Chunk>(CHANNEL_DEPTH);
    let passphrase_given = passphrase.is_some();
    let media_root = state.media_root.clone();
    let runtime = tokio::runtime::Handle::current();
    let error_tx = tx.clone();
    tokio::task::spawn_blocking(move || {
        let out = ChannelWriter {
            tx,
            buffer: Vec::with_capacity(CHUNK),
        };
        if let Err(error) = write_archive(snapshot, media_root, &runtime, format, out, passphrase) {
            // A truncated archive must fail the download, not look complete.
            let _ = error_tx.blocking_send(Err(error));
        }
    });
    let encrypted = passphrase_given;
    let filename = format!(
        "stricknani-export-{}.{}{}",
        Utc::now().format("%Y-%m-%d"),
        format.extension(),
        if encrypted { ".age" } else { "" }
    );
    let content_type = if encrypted {
        "application/octet-stream"
    } else {
        format.content_type()
    };
    (
        StatusCode::OK,
        [
            (CONTENT_TYPE, content_type.to_owned()),
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
