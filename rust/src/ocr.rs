//! Optional, bounded OCR over caller-authorized image bytes.
//!
//! This module does not resolve media URLs or decide ownership. Callers must
//! authorize and load an image before passing its bytes to [`OcrService`].

use std::{
    future::Future,
    io::{Cursor, Write},
    pin::Pin,
    process::Stdio,
    sync::Arc,
    time::Duration,
};

use image::{DynamicImage, ImageFormat, ImageReader, Limits};
use tempfile::NamedTempFile;
use tokio::{
    io::AsyncReadExt,
    process::Command,
    sync::{AcquireError, Semaphore},
    task::JoinError,
    time,
};

pub const DEFAULT_OCR_LANG: &str = "deu+eng";
pub const MAX_OCR_BYTES: usize = 25 * 1024 * 1024;
pub const MAX_OCR_OUTPUT_BYTES: usize = 4 * 1024 * 1024;
const MAX_IMAGE_PIXELS: u64 = 64_000_000;
const MAX_IMAGE_DIMENSION: u32 = 20_000;
const MAX_IMAGE_ALLOCATION: u64 = 256 * 1024 * 1024;
const OCR_TIMEOUT: Duration = Duration::from_secs(30);
const OCR_CONCURRENCY: usize = 2;

/// Stable errors suitable for mapping to a small HTTP error response.
#[derive(Clone, Copy, Debug, Eq, PartialEq, thiserror::Error)]
pub enum OcrError {
    #[error("file_too_large")]
    FileTooLarge,
    #[error("invalid_image")]
    InvalidImage,
    #[error("invalid_language")]
    InvalidLanguage,
    #[error("ocr_not_available")]
    NotAvailable,
    #[error("ocr_timed_out")]
    TimedOut,
    #[error("ocr_output_too_large")]
    OutputTooLarge,
    #[error("ocr_failed")]
    Failed,
    #[error("ocr_internal_error")]
    Internal,
}

/// A validated Tesseract language expression, safe to pass as one argv item.
#[derive(Clone, Debug, Eq, Hash, PartialEq)]
pub struct OcrLanguage(String);

impl OcrLanguage {
    pub fn parse(language: Option<&str>) -> Result<Self, OcrError> {
        let language = language.unwrap_or(DEFAULT_OCR_LANG);
        if language.is_empty()
            || language.len() > 128
            || !language.as_bytes()[0].is_ascii_alphanumeric()
            || !language
                .bytes()
                .all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'_' | b'+' | b'-'))
        {
            return Err(OcrError::InvalidLanguage);
        }
        Ok(Self(language.to_owned()))
    }

    pub fn as_str(&self) -> &str {
        &self.0
    }
}

pub type OcrFuture<'a> = Pin<Box<dyn Future<Output = Result<String, OcrError>> + Send + 'a>>;

/// An injectable OCR transport; the production implementation invokes Tesseract.
pub trait OcrTransport: Send + Sync {
    fn recognize<'a>(&'a self, image_bytes: Vec<u8>, language: OcrLanguage) -> OcrFuture<'a>;
}

/// OCR service with a shared two-process limit. Clone this service into routes
/// so all requests share the same semaphore.
#[derive(Clone)]
pub struct OcrService {
    transport: Arc<dyn OcrTransport>,
    semaphore: Arc<Semaphore>,
}

impl Default for OcrService {
    fn default() -> Self {
        Self::new()
    }
}

impl OcrService {
    pub fn new() -> Self {
        Self::with_transport(Arc::new(TesseractTransport))
    }

    pub fn with_transport(transport: Arc<dyn OcrTransport>) -> Self {
        Self {
            transport,
            semaphore: Arc::new(Semaphore::new(OCR_CONCURRENCY)),
        }
    }

    /// Recognize an already-authorized image. Work continues in its own task if
    /// the request future is cancelled, retaining the concurrency slot until
    /// the child has been stopped and reaped.
    pub async fn recognize(
        &self,
        image_bytes: Vec<u8>,
        language: Option<&str>,
    ) -> Result<String, OcrError> {
        if image_bytes.len() > MAX_OCR_BYTES {
            return Err(OcrError::FileTooLarge);
        }
        if image_bytes.is_empty() {
            return Err(OcrError::InvalidImage);
        }
        let language = OcrLanguage::parse(language)?;
        let permit = self
            .semaphore
            .clone()
            .acquire_owned()
            .await
            .map_err(|_error: AcquireError| OcrError::Internal)?;
        let transport = Arc::clone(&self.transport);
        let worker = tokio::spawn(async move {
            let _permit = permit;
            transport.recognize(image_bytes, language).await
        });
        worker
            .await
            .map_err(|_error: JoinError| OcrError::Internal)?
    }
}

#[derive(Clone, Copy, Debug, Default)]
pub struct TesseractTransport;

impl OcrTransport for TesseractTransport {
    fn recognize<'a>(&'a self, image_bytes: Vec<u8>, language: OcrLanguage) -> OcrFuture<'a> {
        Box::pin(async move {
            // Keep decoding and PNG encoding off the async executor.
            let temp_image = tokio::task::spawn_blocking(move || normalize_image(image_bytes))
                .await
                .map_err(|_error| OcrError::Internal)??;
            run_tesseract(temp_image, language).await
        })
    }
}

fn normalize_image(bytes: Vec<u8>) -> Result<NamedTempFile, OcrError> {
    let mut reader = ImageReader::new(Cursor::new(bytes.as_slice()))
        .with_guessed_format()
        .map_err(|_error| OcrError::InvalidImage)?;
    let mut limits = Limits::default();
    limits.max_image_width = Some(MAX_IMAGE_DIMENSION);
    limits.max_image_height = Some(MAX_IMAGE_DIMENSION);
    limits.max_alloc = Some(MAX_IMAGE_ALLOCATION);
    reader.limits(limits);
    let image = reader.decode().map_err(|_error| OcrError::InvalidImage)?;
    let pixels = u64::from(image.width()) * u64::from(image.height());
    if pixels == 0 || pixels > MAX_IMAGE_PIXELS {
        return Err(OcrError::InvalidImage);
    }
    let rgb = image.to_rgb8();
    let mut png = Cursor::new(Vec::new());
    DynamicImage::ImageRgb8(rgb)
        .write_to(&mut png, ImageFormat::Png)
        .map_err(|_error| OcrError::InvalidImage)?;

    let mut temp = tempfile::Builder::new()
        .suffix(".png")
        .tempfile()
        .map_err(|_error| OcrError::Internal)?;
    temp.write_all(png.get_ref())
        .and_then(|()| temp.flush())
        .map_err(|_error| OcrError::Internal)?;
    Ok(temp)
}

async fn run_tesseract(image: NamedTempFile, language: OcrLanguage) -> Result<String, OcrError> {
    let mut child = Command::new("tesseract")
        .env_clear()
        .env("PATH", std::env::var_os("PATH").unwrap_or_default())
        .env("OMP_THREAD_LIMIT", "1")
        .arg(image.path())
        .arg("stdout")
        .arg("-l")
        .arg(language.as_str())
        .stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::null())
        .kill_on_drop(true)
        .spawn()
        .map_err(|error| {
            if error.kind() == std::io::ErrorKind::NotFound {
                OcrError::NotAvailable
            } else {
                OcrError::Failed
            }
        })?;
    let mut stdout = child.stdout.take().ok_or(OcrError::Internal)?;
    let output_reader = tokio::spawn(async move {
        let mut bytes = Vec::with_capacity(8 * 1024);
        let mut limited = (&mut stdout).take((MAX_OCR_OUTPUT_BYTES + 1) as u64);
        limited
            .read_to_end(&mut bytes)
            .await
            .map_err(|_error| OcrError::Failed)?;
        if bytes.len() > MAX_OCR_OUTPUT_BYTES {
            return Err(OcrError::OutputTooLarge);
        }
        Ok(bytes)
    });

    let status = match time::timeout(OCR_TIMEOUT, child.wait()).await {
        Ok(Ok(status)) => status,
        Ok(Err(_error)) => {
            let _ = child.kill().await;
            let _ = child.wait().await;
            output_reader.abort();
            return Err(OcrError::Failed);
        }
        Err(_elapsed) => {
            let _ = child.kill().await;
            // Reap explicitly before releasing the temp file and semaphore.
            let _ = child.wait().await;
            output_reader.abort();
            return Err(OcrError::TimedOut);
        }
    };
    let output = output_reader.await.map_err(|_error| OcrError::Internal)??;
    if !status.success() {
        return Err(OcrError::Failed);
    }
    String::from_utf8(output).map_err(|_error| OcrError::Failed)
}
