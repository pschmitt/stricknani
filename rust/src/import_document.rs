//! Bounded local parsing of fetched import documents into reviewable drafts.
//!
//! This module does not fetch, persist, or schedule work. Callers should run PDF parsing on a
//! blocking thread. URLs found in a document are suggestions only and must pass the URL fetch
//! policy again before any follow-up request.

use std::collections::BTreeMap;
use std::io::Cursor;

use image::ImageReader;
use scraper::{Html, Selector};
use serde::{Deserialize, Serialize};
use serde_json::Value;
use sha2::{Digest, Sha256};
use thiserror::Error;
use url::Url;

const MAX_DOCUMENT_BYTES: usize = 5 * 1024 * 1024;
const MAX_EXTRACTED_TEXT_CHARS: usize = 200_000;
const MAX_IMAGE_URLS: usize = 10;
const MAX_JSON_LD_BLOCKS: usize = 16;
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
    pub extras: BTreeMap<String, Value>,
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

    let (project, image) = match kind {
        DocumentKind::Html => (parse_html(&input), None),
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

fn parse_html(input: &DocumentInput<'_>) -> ProjectImportDraft {
    let html = String::from_utf8_lossy(input.bytes);
    let document = Html::parse_document(&html);
    let mut project = ProjectImportDraft::default();
    let json_ld_selector = selector("script[type='application/ld+json']");
    let title_selector = selector("title");
    let meta_selector = selector("meta");
    let link_selector = selector("link[rel='canonical']");
    let image_selector = selector("img[src]");
    let text_selector = selector("p, h1, h2, h3, li");

    let metadata = document
        .select(&meta_selector)
        .fold(BTreeMap::new(), |mut map, node| {
            let key = node
                .value()
                .attr("property")
                .or_else(|| node.value().attr("name"))
                .unwrap_or_default()
                .to_ascii_lowercase();
            let value = node.value().attr("content").and_then(nonempty);
            if !key.is_empty()
                && let Some(value) = value
            {
                map.entry(key).or_insert_with(|| value.to_owned());
            }
            map
        });

    let json_ld: Vec<Value> = document
        .select(&json_ld_selector)
        .take(MAX_JSON_LD_BLOCKS)
        .filter_map(|script| serde_json::from_str(&script.text().collect::<String>()).ok())
        .collect();
    let schema_objects = schema_objects(&json_ld);

    project.name = input
        .fetched_title
        .and_then(nonempty)
        .map(str::to_owned)
        .or_else(|| metadata_value(&metadata, &["og:title", "twitter:title"]))
        .or_else(|| {
            document
                .select(&title_selector)
                .next()
                .map(|node| node.text().collect::<String>())
                .and_then(|value| nonempty(&value).map(str::to_owned))
        })
        .or_else(|| schema_string(&schema_objects, &["name", "headline"]));
    project.description = metadata_value(
        &metadata,
        &["description", "og:description", "twitter:description"],
    )
    .or_else(|| schema_string(&schema_objects, &["description"]))
    .or_else(|| input.fetched_text.and_then(nonempty).map(short_text))
    .or_else(|| {
        let visible_text = document
            .select(&text_selector)
            .map(|node| node.text().collect::<String>())
            .filter_map(|text| nonempty(&text).map(str::to_owned))
            .collect::<Vec<_>>()
            .join("\n");
        nonempty(&visible_text).map(short_text)
    });
    project.link = document
        .select(&link_selector)
        .next()
        .and_then(|node| node.value().attr("href"))
        .and_then(|href| resolve_url(input.source_url, href))
        .or_else(|| input.source_url.and_then(safe_web_url).map(Url::to_string))
        .or_else(|| schema_string(&schema_objects, &["url"]));
    project.brand = metadata_value(&metadata, &["og:site_name", "application-name"])
        .or_else(|| schema_name(&schema_objects, &["brand", "author", "publisher"]));
    project.category = schema_string(&schema_objects, &["recipeCategory", "category"]);
    project.yarn = schema_string(&schema_objects, &["yarn", "yarnDescription"]);
    project.yarns = schema_yarns(&schema_objects, input.source_url);
    project.needles = schema_string(&schema_objects, &["needles", "needleSize"]);
    project.stitch_sample = metadata_value(&metadata, &["stitch_sample", "stitch-sample"]);
    project.fiber_content = schema_string(&schema_objects, &["fiberContent", "material"]);
    project.colorway = schema_string(&schema_objects, &["colorway"]);
    project.weight_category = schema_string(&schema_objects, &["weightCategory"]);
    project.gauge_width = metadata_value(&metadata, &["gauge_width", "gauge-width"])
        .and_then(|value| value.parse().ok());
    project.gauge_height = metadata_value(&metadata, &["gauge_height", "gauge-height"])
        .and_then(|value| value.parse().ok());
    let ingredients = schema_strings(&schema_objects, &["recipeIngredient"]);
    project.other_materials = (!ingredients.is_empty()).then(|| ingredients.join("\n"));
    project.steps = recipe_steps(&schema_objects, input.source_url);
    project.image_urls = metadata_value(&metadata, &["og:image", "twitter:image"])
        .into_iter()
        .chain(schema_strings(&schema_objects, &["image"]))
        .chain(
            document
                .select(&image_selector)
                .filter_map(|node| node.value().attr("src").map(str::to_owned)),
        )
        .filter_map(|url| resolve_url(input.source_url, &url))
        .take(MAX_IMAGE_URLS)
        .collect();
    project
        .extras
        .insert("structured_data".into(), Value::Array(json_ld));
    project
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

fn schema_objects(values: &[Value]) -> Vec<&serde_json::Map<String, Value>> {
    fn visit<'a>(value: &'a Value, output: &mut Vec<&'a serde_json::Map<String, Value>>) {
        match value {
            Value::Array(values) => values.iter().for_each(|value| visit(value, output)),
            Value::Object(map) => {
                if map.contains_key("@type") {
                    output.push(map);
                }
                if let Some(graph) = map.get("@graph") {
                    visit(graph, output);
                }
            }
            _ => {}
        }
    }
    let mut objects = Vec::new();
    values.iter().for_each(|value| visit(value, &mut objects));
    objects
}

fn schema_string(objects: &[&serde_json::Map<String, Value>], keys: &[&str]) -> Option<String> {
    objects.iter().find_map(|object| {
        keys.iter()
            .find_map(|key| object.get(*key).and_then(value_string))
    })
}

fn schema_name(objects: &[&serde_json::Map<String, Value>], keys: &[&str]) -> Option<String> {
    objects.iter().find_map(|object| {
        keys.iter().find_map(|key| {
            object
                .get(*key)
                .and_then(|value| match value {
                    Value::Object(map) => map.get("name"),
                    value => Some(value),
                })
                .and_then(value_string)
        })
    })
}

fn schema_strings(objects: &[&serde_json::Map<String, Value>], keys: &[&str]) -> Vec<String> {
    objects
        .iter()
        .flat_map(|object| keys.iter().filter_map(|key| object.get(*key)))
        .flat_map(|value| match value {
            Value::Array(values) => values.iter().collect::<Vec<_>>(),
            value => vec![value],
        })
        .filter_map(value_string)
        .collect()
}

fn schema_yarns(
    objects: &[&serde_json::Map<String, Value>],
    source_url: Option<&Url>,
) -> Vec<YarnImportDraft> {
    objects
        .iter()
        .flat_map(|object| ["yarn", "yarns"].iter().filter_map(|key| object.get(*key)))
        .flat_map(|value| match value {
            Value::Array(values) => values.iter().collect::<Vec<_>>(),
            value => vec![value],
        })
        .filter_map(|value| {
            let Value::Object(fields) = value else {
                return value_string(value).map(|name| YarnImportDraft {
                    name: Some(name),
                    ..YarnImportDraft::default()
                });
            };
            let text = |key: &str| fields.get(key).and_then(value_string);
            let link = text("url")
                .or_else(|| text("link"))
                .and_then(|value| resolve_url(source_url, &value));
            let yarn = YarnImportDraft {
                name: text("name"),
                brand: schema_name_from_value(fields.get("brand")),
                colorway: text("colorway"),
                weight: text("weight"),
                length: text("length"),
                weight_category: text("weightCategory").or_else(|| text("weight_category")),
                fiber_content: text("fiberContent").or_else(|| text("fiber_content")),
                link,
                image_url: text("image").and_then(|value| resolve_url(source_url, &value)),
            };
            (yarn.name.is_some() || yarn.brand.is_some()).then_some(yarn)
        })
        .collect()
}

fn schema_name_from_value(value: Option<&Value>) -> Option<String> {
    value.and_then(|value| match value {
        Value::Object(map) => map.get("name").and_then(value_string),
        value => value_string(value),
    })
}

fn recipe_steps(
    objects: &[&serde_json::Map<String, Value>],
    source_url: Option<&Url>,
) -> Vec<StepImportDraft> {
    let Some(instructions) = objects
        .iter()
        .find_map(|object| object.get("recipeInstructions"))
    else {
        return Vec::new();
    };
    let values = match instructions {
        Value::Array(values) => values.iter().collect::<Vec<_>>(),
        value => vec![value],
    };
    values
        .into_iter()
        .enumerate()
        .filter_map(|(index, value)| {
            let (title, description, images) = match value {
                Value::Object(map) => (
                    map.get("name").and_then(value_string),
                    map.get("text")
                        .or_else(|| map.get("description"))
                        .and_then(value_string),
                    map.get("image")
                        .map(value_strings)
                        .unwrap_or_default()
                        .into_iter()
                        .filter_map(|url| resolve_url(source_url, &url))
                        .collect(),
                ),
                value => (None, value_string(value), Vec::new()),
            };
            (title.is_some() || description.is_some()).then_some(StepImportDraft {
                step_number: index + 1,
                title,
                description,
                images,
            })
        })
        .collect()
}

fn value_strings(value: &Value) -> Vec<String> {
    match value {
        Value::Array(values) => values.iter().filter_map(value_string).collect(),
        value => value_string(value).into_iter().collect(),
    }
}

fn value_string(value: &Value) -> Option<String> {
    match value {
        Value::String(value) => nonempty(value).map(str::to_owned),
        Value::Object(map) => map
            .get("url")
            .or_else(|| map.get("name"))
            .and_then(value_string),
        _ => None,
    }
}

fn metadata_value(metadata: &BTreeMap<String, String>, keys: &[&str]) -> Option<String> {
    keys.iter().find_map(|key| metadata.get(*key).cloned())
}

fn resolve_url(base: Option<&Url>, candidate: &str) -> Option<String> {
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

fn safe_web_url(url: &Url) -> Option<&Url> {
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

fn nonempty(value: &str) -> Option<&str> {
    let trimmed = value.trim();
    (!trimmed.is_empty()).then_some(trimmed)
}

fn short_text(text: &str) -> String {
    text.chars().take(2_000).collect()
}

fn selector(value: &str) -> Selector {
    Selector::parse(value).expect("static CSS selector is valid")
}
