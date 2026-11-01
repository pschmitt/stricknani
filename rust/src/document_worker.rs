//! Isolated process boundary for parsing untrusted import documents.
//!
//! The server process should call [`parse_isolated`] with its own executable path. The child
//! handles one bounded JSON request on stdin and one bounded response on stdout. The parser does
//! not fetch URLs or persist data; the child still inherits its normal OS permissions.

use std::io::{self, Read, Write};
use std::path::Path;
use std::sync::OnceLock;
use std::time::Duration;

use base64::engine::general_purpose::STANDARD;
use serde::{Deserialize, Serialize};
use thiserror::Error;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::process::{Child, Command};
use tokio::sync::Semaphore as TokioSemaphore;
use tokio::time::{Instant, timeout_at};
use url::Url;

use crate::import_document::{
    DocumentInput, DocumentParseError, ImportDocumentDraft, parse_document,
};

const MAX_DOCUMENT_BYTES: usize = 5 * 1024 * 1024;
const MAX_REQUEST_BYTES: usize = 8 * 1024 * 1024;
const MAX_RESPONSE_BYTES: usize = 2 * 1024 * 1024;
const MAX_WORKER_TIME: Duration = Duration::from_secs(10);
const MAX_SOURCE_URL_BYTES: usize = 2 * 1024;
const MAX_CONTENT_TYPE_BYTES: usize = 256;
const MAX_FILENAME_BYTES: usize = 512;
const MAX_TITLE_BYTES: usize = 8 * 1024;
const MAX_FETCHED_TEXT_BYTES: usize = 256 * 1024;
const MAX_CONCURRENT_WORKERS: usize = 2;

static WORKER_SLOTS: OnceLock<TokioSemaphore> = OnceLock::new();

/// Owned request data for one isolated document parse.
#[derive(Clone, Deserialize, Serialize)]
pub struct DocumentRequest {
    #[serde(with = "base64_bytes")]
    pub bytes: Vec<u8>,
    pub content_type: Option<String>,
    pub filename: Option<String>,
    pub source_url: Option<String>,
    pub fetched_title: Option<String>,
    pub fetched_text: Option<String>,
}

/// Redacted worker failures. Variants deliberately carry no parser input or OS error text.
#[derive(Clone, Copy, Debug, Deserialize, Eq, Error, PartialEq, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum WorkerError {
    #[error("document worker request exceeds the configured size limit")]
    RequestTooLarge,
    #[error("document worker request is invalid")]
    InvalidRequest,
    #[error("document worker response exceeds the configured size limit")]
    ResponseTooLarge,
    #[error("document worker exceeded its time limit")]
    TimedOut,
    #[error("document worker could not be started")]
    SpawnFailed,
    #[error("document worker failed")]
    ChildFailed,
    #[error("document worker returned an invalid response")]
    InvalidResponse,
    #[error("document worker resource limits could not be applied")]
    ResourceLimitsUnavailable,
    #[error("document worker is unavailable on this operating system")]
    UnsupportedPlatform,
    #[error("document type is unsupported")]
    UnsupportedDocument,
    #[error("document could not be decoded")]
    InvalidDocument,
}

#[derive(Debug, Deserialize, Serialize)]
#[serde(untagged)]
enum WorkerResponse {
    Ok { document: Box<ImportDocumentDraft> },
    Error { error: WorkerError },
}

/// Parse a document in a bounded child process.
///
/// The supplied executable must be the server binary whose `document-worker` command dispatches
/// synchronously to [`run_stdio`] before creating the async runtime.
pub async fn parse_isolated(
    executable: &Path,
    request: DocumentRequest,
) -> Result<ImportDocumentDraft, WorkerError> {
    let deadline = Instant::now() + MAX_WORKER_TIME;
    validate_request(&request)?;
    let request_bytes = serde_json::to_vec(&request).map_err(|_| WorkerError::InvalidRequest)?;
    if request_bytes.len() > MAX_REQUEST_BYTES {
        return Err(WorkerError::RequestTooLarge);
    }

    let slots = WORKER_SLOTS.get_or_init(|| TokioSemaphore::new(MAX_CONCURRENT_WORKERS));
    let _permit = timeout_at(deadline, slots.acquire())
        .await
        .map_err(|_| WorkerError::TimedOut)?
        .map_err(|_| WorkerError::ChildFailed)?;

    let mut child = Command::new(executable)
        .arg("document-worker")
        .env_clear()
        .stdin(std::process::Stdio::piped())
        .stdout(std::process::Stdio::piped())
        .stderr(std::process::Stdio::null())
        .kill_on_drop(true)
        .spawn()
        .map_err(|_| WorkerError::SpawnFailed)?;

    let worker = async {
        let output = communicate(&mut child, request_bytes).await?;
        let status = child.wait().await.map_err(|_| WorkerError::ChildFailed)?;
        Ok::<_, WorkerError>((output, status))
    };
    let (output, status) = match timeout_at(deadline, worker).await {
        Ok(Ok(result)) => result,
        Ok(Err(error)) => {
            stop_child(&mut child).await;
            return Err(error);
        }
        Err(_) => {
            stop_child(&mut child).await;
            return Err(WorkerError::TimedOut);
        }
    };
    if !status.success() {
        return Err(WorkerError::ChildFailed);
    }
    let response: WorkerResponse =
        serde_json::from_slice(&output).map_err(|_| WorkerError::InvalidResponse)?;
    match response {
        WorkerResponse::Ok { document } => Ok(*document),
        WorkerResponse::Error { error } => Err(error),
    }
}

async fn communicate(child: &mut Child, request: Vec<u8>) -> Result<Vec<u8>, WorkerError> {
    let mut stdin = child.stdin.take().ok_or(WorkerError::ChildFailed)?;
    let mut stdout = child.stdout.take().ok_or(WorkerError::ChildFailed)?;
    let write_request = async move {
        stdin
            .write_all(&request)
            .await
            .map_err(|_| WorkerError::ChildFailed)?;
        stdin.shutdown().await.map_err(|_| WorkerError::ChildFailed)
    };
    let read_response = async move {
        let mut output = Vec::with_capacity(MAX_RESPONSE_BYTES.min(64 * 1024));
        let mut limited = (&mut stdout).take((MAX_RESPONSE_BYTES + 1) as u64);
        limited
            .read_to_end(&mut output)
            .await
            .map_err(|_| WorkerError::ChildFailed)?;
        if output.len() > MAX_RESPONSE_BYTES {
            return Err(WorkerError::ResponseTooLarge);
        }
        Ok(output)
    };
    let (write_result, response_result) = tokio::join!(write_request, read_response);
    write_result?;
    response_result
}

async fn stop_child(child: &mut Child) {
    let _ = child.kill().await;
    let _ = child.wait().await;
}

fn validate_request(request: &DocumentRequest) -> Result<(), WorkerError> {
    if request.bytes.len() > MAX_DOCUMENT_BYTES
        || request
            .content_type
            .as_ref()
            .is_some_and(|value| value.len() > MAX_CONTENT_TYPE_BYTES)
        || request
            .filename
            .as_ref()
            .is_some_and(|value| value.len() > MAX_FILENAME_BYTES)
        || request
            .source_url
            .as_ref()
            .is_some_and(|value| value.len() > MAX_SOURCE_URL_BYTES)
        || request
            .fetched_title
            .as_ref()
            .is_some_and(|value| value.len() > MAX_TITLE_BYTES)
        || request
            .fetched_text
            .as_ref()
            .is_some_and(|value| value.len() > MAX_FETCHED_TEXT_BYTES)
    {
        return Err(WorkerError::RequestTooLarge);
    }
    Ok(())
}

/// Run one worker request synchronously. Invoke this before constructing a Tokio runtime.
pub fn run_stdio() -> Result<(), WorkerError> {
    if let Err(error) = apply_resource_limits() {
        write_response(&WorkerResponse::Error { error })?;
        return Ok(());
    }

    let mut input = Vec::with_capacity(64 * 1024);
    io::stdin()
        .take((MAX_REQUEST_BYTES + 1) as u64)
        .read_to_end(&mut input)
        .map_err(|_| WorkerError::InvalidRequest)?;
    let response = if input.len() > MAX_REQUEST_BYTES {
        WorkerResponse::Error {
            error: WorkerError::RequestTooLarge,
        }
    } else {
        match parse_request(&input) {
            Ok(document) => WorkerResponse::Ok {
                document: Box::new(document),
            },
            Err(error) => WorkerResponse::Error { error },
        }
    };
    write_response(&response)
}

fn parse_request(bytes: &[u8]) -> Result<ImportDocumentDraft, WorkerError> {
    let request: DocumentRequest =
        serde_json::from_slice(bytes).map_err(|_| WorkerError::InvalidRequest)?;
    validate_request(&request)?;
    let source_url = request
        .source_url
        .as_deref()
        .map(Url::parse)
        .transpose()
        .map_err(|_| WorkerError::InvalidRequest)?;
    parse_document(DocumentInput {
        bytes: &request.bytes,
        content_type: request.content_type.as_deref(),
        filename: request.filename.as_deref(),
        source_url: source_url.as_ref(),
        fetched_title: request.fetched_title.as_deref(),
        fetched_text: request.fetched_text.as_deref(),
    })
    .map_err(map_parse_error)
}

fn map_parse_error(error: DocumentParseError) -> WorkerError {
    match error {
        DocumentParseError::TooLarge => WorkerError::RequestTooLarge,
        DocumentParseError::UnsupportedType => WorkerError::UnsupportedDocument,
        DocumentParseError::InvalidDocument => WorkerError::InvalidDocument,
    }
}

fn write_response(response: &WorkerResponse) -> Result<(), WorkerError> {
    let output = serde_json::to_vec(response).map_err(|_| WorkerError::InvalidResponse)?;
    if output.len() > MAX_RESPONSE_BYTES {
        return write_response(&WorkerResponse::Error {
            error: WorkerError::ResponseTooLarge,
        });
    }
    io::stdout()
        .lock()
        .write_all(&output)
        .map_err(|_| WorkerError::ChildFailed)
}

#[cfg(target_os = "linux")]
fn apply_resource_limits() -> Result<(), WorkerError> {
    use rustix::process::{Resource, Rlimit, setrlimit};

    for (resource, limit) in [
        (Resource::As, 512 * 1024 * 1024),
        (Resource::Cpu, 5),
        (Resource::Core, 0),
    ] {
        setrlimit(
            resource,
            Rlimit {
                current: Some(limit),
                maximum: Some(limit),
            },
        )
        .map_err(|_| WorkerError::ResourceLimitsUnavailable)?;
    }
    Ok(())
}

#[cfg(not(target_os = "linux"))]
fn apply_resource_limits() -> Result<(), WorkerError> {
    Err(WorkerError::UnsupportedPlatform)
}

mod base64_bytes {
    use base64::Engine;
    use serde::{Deserialize, Deserializer, Serializer};

    use super::STANDARD;

    pub fn serialize<S, B>(bytes: &B, serializer: S) -> Result<S::Ok, S::Error>
    where
        S: Serializer,
        B: AsRef<[u8]>,
    {
        serializer.serialize_str(&STANDARD.encode(bytes.as_ref()))
    }

    pub fn deserialize<'de, D>(deserializer: D) -> Result<Vec<u8>, D::Error>
    where
        D: Deserializer<'de>,
    {
        let encoded = String::deserialize(deserializer)?;
        STANDARD.decode(encoded).map_err(serde::de::Error::custom)
    }
}
