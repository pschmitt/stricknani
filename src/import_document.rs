//! Bounded local parsing of fetched import documents into reviewable drafts.
//!
//! This module does not fetch, persist, or schedule work. Callers should run PDF parsing on a
//! blocking thread. URLs found in a document are suggestions only and must pass the URL fetch
//! policy again before any follow-up request.

use std::collections::BTreeMap;
use std::io::Cursor;

use image::ImageReader;
use serde::{Deserialize, Serialize};
use serde_json::Value;
use sha2::{Digest, Sha256};
use thiserror::Error;
use url::Url;

const MAX_DOCUMENT_BYTES: usize = 5 * 1024 * 1024;
const MAX_EXTRACTED_TEXT_CHARS: usize = 200_000;
const MAX_PDF_PAGES: usize = 30;
const MAX_PDF_STREAM_BYTES: usize = 1024 * 1024;
const MAX_PDF_PAGE_TEXT_BYTES: usize = 256 * 1024;

/// A content type accepted by the import document parser.
#[derive(Clone, Copy, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum DocumentKind {
    Html,
    PlainText,
    Markdown,
    Pdf,
    Image,
}

/// Untrusted bytes and source hints passed to the pure document parser.
pub struct DocumentInput<'a> {
    pub bytes: &'a [u8],
    pub content_type: Option<&'a str>,
    pub filename: Option<&'a str>,
    pub source_url: Option<&'a Url>,
    /// Title and visible text already extracted by the safe fetch layer, when available.
    pub fetched_title: Option<&'a str>,
    pub fetched_text: Option<&'a str>,
}

/// Parsed draft fields that can be reviewed before a project or yarn is created.
#[derive(Clone, Debug, Default, Deserialize, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ProjectImportDraft {
    pub name: Option<String>,
    pub description: Option<String>,
    pub category: Option<String>,
    pub yarn: Option<String>,
    pub yarns: Vec<YarnImportDraft>,
    pub needles: Option<String>,
    pub other_materials: Option<String>,
    pub stitch_sample: Option<String>,
    pub gauge_width: Option<i64>,
    pub gauge_height: Option<i64>,
    pub fiber_content: Option<String>,
    pub colorway: Option<String>,
    pub weight_category: Option<String>,
    pub steps: Vec<StepImportDraft>,
    pub image_urls: Vec<String>,
    pub link: Option<String>,
    pub brand: Option<String>,
    /// Pattern designer or publisher, when the page names one.
    #[serde(default)]
    pub designer: Option<String>,
    #[serde(default)]
    pub difficulty: Option<String>,
    /// Available sizes, for example "S, M, L, XL".
    #[serde(default)]
    pub sizes: Option<String>,
    #[serde(default)]
    pub tags: Vec<String>,
    pub extras: BTreeMap<String, Value>,
}

/// What a page turned out to be about.
#[derive(Clone, Copy, Debug, Default, Deserialize, Eq, PartialEq, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum PageKind {
    Yarn,
    Pattern,
    #[default]
    Unknown,
}

/// Where a field value came from. Higher-trust sources win when merging.
#[derive(Clone, Copy, Debug, Deserialize, Eq, Ord, PartialEq, PartialOrd, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum FieldSource {
    /// Guessed from visible page text by pattern matching.
    Text,
    /// Written by an AI model from the page text.
    Ai,
    /// schema.org, microdata or OpenGraph markup.
    Structured,
    /// A site-specific extractor or API (Ravelry, Drops).
    Site,
}

/// A yarn product as found on a shop or yarn database page.
#[derive(Clone, Debug, Default, Deserialize, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct YarnDetails {
    pub name: Option<String>,
    pub brand: Option<String>,
    pub colorway: Option<String>,
    pub description: Option<String>,
    pub fiber_content: Option<String>,
    pub weight_category: Option<String>,
    /// Skein or ball weight in grams.
    pub weight_grams: Option<i64>,
    /// Length of one skein in meters.
    pub length_meters: Option<i64>,
    pub recommended_needles: Option<String>,
    pub gauge: Option<String>,
    pub care: Option<String>,
    /// Shop price as text, for example "7.95 EUR".
    pub price: Option<String>,
    pub link: Option<String>,
    pub image_urls: Vec<String>,
}

/// Yarn details extracted from structured metadata when a source provides them.
#[derive(Clone, Debug, Default, Deserialize, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct YarnImportDraft {
    pub name: Option<String>,
    pub brand: Option<String>,
    pub colorway: Option<String>,
    pub weight: Option<String>,
    pub length: Option<String>,
    pub weight_category: Option<String>,
    pub fiber_content: Option<String>,
    pub link: Option<String>,
    pub image_url: Option<String>,
    /// Quantity the pattern asks for, per size when the page says so ("250-300-350 g").
    #[serde(default)]
    pub amount: Option<String>,
    /// Free text worth keeping, for example the DROPS yarn group.
    #[serde(default)]
    pub notes: Option<String>,
}

/// One instruction step from schema.org recipe metadata.
#[derive(Clone, Debug, Default, Deserialize, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct StepImportDraft {
    pub step_number: usize,
    pub title: Option<String>,
    pub description: Option<String>,
    pub images: Vec<String>,
}

/// Image facts extracted without modifying or re-encoding the original bytes.
#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ImageFacts {
    pub format: String,
    pub width: u32,
    pub height: u32,
}

/// Parsed document, with a content digest suitable for duplicate detection.
#[derive(Clone, Debug, Deserialize, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ImportDocumentDraft {
    pub kind: DocumentKind,
    pub sha256: String,
    pub source_url: Option<String>,
    pub project: ProjectImportDraft,
    pub image: Option<ImageFacts>,
    /// Yarn details, present for yarn pages and for yarn-target imports.
    #[serde(default)]
    pub yarn: Option<YarnDetails>,
    #[serde(default)]
    pub page_kind: PageKind,
    /// Field path (for example `yarn.brand`) to the source it came from.
    #[serde(default)]
    pub provenance: BTreeMap<String, FieldSource>,
    /// Short machine codes such as `no_structured_data` for the UI to explain.
    #[serde(default)]
    pub warnings: Vec<String>,
}

/// Stable, non-sensitive parser errors.
#[derive(Clone, Copy, Debug, Error, Eq, PartialEq)]
pub enum DocumentParseError {
    #[error("document exceeds the configured size limit")]
    TooLarge,
    #[error("document type is unsupported")]
    UnsupportedType,
    #[error("document could not be decoded")]
    InvalidDocument,
}

/// Parse bounded HTML, text, Markdown, PDF, or image bytes into a project import draft.
pub fn parse_document(input: DocumentInput<'_>) -> Result<ImportDocumentDraft, DocumentParseError> {
    if input.bytes.len() > MAX_DOCUMENT_BYTES {
        return Err(DocumentParseError::TooLarge);
    }
    let kind = detect_kind(input.content_type, input.filename, input.bytes)?;
    let sha256 = hex::encode(Sha256::digest(input.bytes));
    let source_url = input.source_url.and_then(safe_web_url).map(Url::to_string);

    let mut yarn = None;
    let mut page_kind = PageKind::Unknown;
    let mut provenance = BTreeMap::new();
    let mut warnings = Vec::new();
    let (project, image) = match kind {
        DocumentKind::Html => {
            let extraction = crate::url_import::extract_html(
                &decode_html(input.bytes, input.content_type),
                input.source_url,
                input.fetched_title,
            );
            yarn = extraction.yarn;
            page_kind = extraction.kind;
            provenance = extraction.provenance;
            warnings = extraction.warnings;
            (extraction.project, None)
        }
        DocumentKind::PlainText => (parse_text(&input, false), None),
        DocumentKind::Markdown => (parse_text(&input, true), None),
        DocumentKind::Pdf => (parse_pdf(&input)?, None),
        DocumentKind::Image => (parse_image(&input)?, Some(image_facts(input.bytes)?)),
    };

    Ok(ImportDocumentDraft {
        kind,
        sha256,
        source_url,
        project,
        image,
        yarn,
        page_kind,
        provenance,
        warnings,
    })
}

fn detect_kind(
    content_type: Option<&str>,
    filename: Option<&str>,
    bytes: &[u8],
) -> Result<DocumentKind, DocumentParseError> {
    let mime = content_type
        .unwrap_or_default()
        .split(';')
        .next()
        .unwrap_or_default()
        .trim()
        .to_ascii_lowercase();
    let extension = filename.and_then(|name| {
        name.rsplit_once('.')
            .map(|(_, ext)| ext.to_ascii_lowercase())
    });
    let kind = match mime.as_str() {
        "text/html" | "application/xhtml+xml" => Some(DocumentKind::Html),
        "text/plain" => Some(DocumentKind::PlainText),
        "text/markdown" | "text/x-markdown" => Some(DocumentKind::Markdown),
        "application/pdf" => Some(DocumentKind::Pdf),
        value if value.starts_with("image/") => Some(DocumentKind::Image),
        _ => match extension.as_deref() {
            Some("html" | "htm") => Some(DocumentKind::Html),
            Some("txt") => Some(DocumentKind::PlainText),
            Some("md" | "markdown") => Some(DocumentKind::Markdown),
            Some("pdf") => Some(DocumentKind::Pdf),
            Some("jpg" | "jpeg" | "png" | "gif" | "webp" | "bmp" | "tif" | "tiff") => {
                Some(DocumentKind::Image)
            }
            _ if bytes.starts_with(b"%PDF-") => Some(DocumentKind::Pdf),
            _ if image::guess_format(bytes).is_ok() => Some(DocumentKind::Image),
            _ => None,
        },
    };
    kind.ok_or(DocumentParseError::UnsupportedType)
}

fn decode_html(bytes: &[u8], content_type: Option<&str>) -> String {
    crate::url_import::charset::decode_html(bytes, content_type)
}

fn parse_text(input: &DocumentInput<'_>, markdown: bool) -> ProjectImportDraft {
    let text = String::from_utf8_lossy(input.bytes);
    let text = text.trim();
    let name = if markdown {
        text.lines()
            .find_map(|line| {
                line.trim()
                    .strip_prefix('#')
                    .map(|heading| heading.trim_start_matches('#').trim())
                    .filter(|heading| !heading.is_empty())
            })
            .map(str::to_owned)
    } else {
        None
    }
    .or_else(|| input.filename.and_then(filename_stem));
    let mut extras = BTreeMap::new();
    if !text.is_empty() {
        extras.insert(
            "full_text".into(),
            Value::String(text.chars().take(MAX_EXTRACTED_TEXT_CHARS).collect()),
        );
    }
    ProjectImportDraft {
        name,
        description: nonempty(text).map(short_text),
        link: input.source_url.and_then(safe_web_url).map(Url::to_string),
        extras,
        ..ProjectImportDraft::default()
    }
}

fn parse_pdf(input: &DocumentInput<'_>) -> Result<ProjectImportDraft, DocumentParseError> {
    let document = lopdf::Document::load_mem_with_options(
        input.bytes,
        lopdf::LoadOptions::with_max_decompressed_size(MAX_PDF_STREAM_BYTES),
    )
    .map_err(|_| DocumentParseError::InvalidDocument)?;
    let page_count = document.get_pages().len();
    let mut text = String::new();
    let mut processed_pages = 0;
    for page_number in document.get_pages().keys().copied().take(MAX_PDF_PAGES) {
        let chunks =
            document.extract_text_chunks_with_limit(&[page_number], MAX_PDF_PAGE_TEXT_BYTES);
        let page_text = chunks
            .into_iter()
            .next()
            .ok_or(DocumentParseError::InvalidDocument)?
            .map_err(|_| DocumentParseError::InvalidDocument)?;
        if text.chars().count() >= MAX_EXTRACTED_TEXT_CHARS {
            break;
        }
        if !text.is_empty() {
            text.push_str("\n\n");
        }
        let remaining_chars = MAX_EXTRACTED_TEXT_CHARS.saturating_sub(text.chars().count());
        text.extend(page_text.chars().take(remaining_chars));
        processed_pages += 1;
    }
    let text = text.trim();
    let extras = BTreeMap::from([
        ("full_text".into(), Value::String(text.to_owned())),
        ("page_count".into(), Value::from(page_count)),
        (
            "pages_truncated".into(),
            Value::Bool(page_count > processed_pages),
        ),
    ]);
    Ok(ProjectImportDraft {
        name: input
            .fetched_title
            .and_then(nonempty)
            .map(str::to_owned)
            .or_else(|| input.filename.and_then(filename_stem)),
        description: nonempty(text).map(short_text),
        link: input.source_url.and_then(safe_web_url).map(Url::to_string),
        extras,
        ..ProjectImportDraft::default()
    })
}

fn parse_image(input: &DocumentInput<'_>) -> Result<ProjectImportDraft, DocumentParseError> {
    Ok(ProjectImportDraft {
        name: input
            .fetched_title
            .and_then(nonempty)
            .map(str::to_owned)
            .or_else(|| input.filename.and_then(filename_stem)),
        link: input.source_url.and_then(safe_web_url).map(Url::to_string),
        ..ProjectImportDraft::default()
    })
}

fn image_facts(bytes: &[u8]) -> Result<ImageFacts, DocumentParseError> {
    let format = image::guess_format(bytes).map_err(|_| DocumentParseError::InvalidDocument)?;
    let reader = ImageReader::with_format(Cursor::new(bytes), format);
    let (width, height) = reader
        .into_dimensions()
        .map_err(|_| DocumentParseError::InvalidDocument)?;
    Ok(ImageFacts {
        format: format!("{format:?}").to_ascii_lowercase(),
        width,
        height,
    })
}

pub(crate) fn resolve_url(base: Option<&Url>, candidate: &str) -> Option<String> {
    let candidate = candidate.trim();
    if candidate.is_empty()
        || candidate.starts_with("data:")
        || candidate.starts_with("javascript:")
    {
        return None;
    }
    let url = match Url::parse(candidate) {
        Ok(url) => url,
        Err(_) => base?.join(candidate).ok()?,
    };
    safe_web_url(&url).map(Url::to_string)
}

pub(crate) fn safe_web_url(url: &Url) -> Option<&Url> {
    (matches!(url.scheme(), "http" | "https")
        && url.username().is_empty()
        && url.password().is_none()
        && url.fragment().is_none())
    .then_some(url)
}

fn filename_stem(filename: &str) -> Option<String> {
    let stem = filename.rsplit_once('/').map_or(filename, |(_, name)| name);
    let stem = stem.rsplit_once('.').map_or(stem, |(base, _)| base);
    nonempty(stem).map(str::to_owned)
}

pub(crate) fn nonempty(value: &str) -> Option<&str> {
    let trimmed = value.trim();
    (!trimmed.is_empty()).then_some(trimmed)
}

fn short_text(text: &str) -> String {
    text.chars().take(2_000).collect()
}
