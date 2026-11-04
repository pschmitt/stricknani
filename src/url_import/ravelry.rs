//! Ravelry enrichment through the public read-only API.
//!
//! Ravelry pattern and yarn pages carry schema.org markup, but only the API has gauge, needle
//! sizes, yarn weight, yardage and fiber content as clean fields. The API needs a read-only
//! Basic-auth key pair (`RAVELRY_USERNAME` / `RAVELRY_PASSWORD`); without it the generic HTML
//! extraction still runs. The API host is a constant, so no user-supplied URL is ever fetched.

use std::{fmt, future::Future, pin::Pin, sync::Arc, time::Duration};

use regex::Regex;
use reqwest::{Client, header};
use serde_json::Value;
use url::Url;
use zeroize::Zeroizing;

use super::extract::strip_html;
use super::text;
use crate::import_document::{
    FieldSource, ImportDocumentDraft, PageKind, YarnDetails, YarnImportDraft,
};

const API_BASE: &str = "https://api.ravelry.com";
const MAX_RESPONSE_BYTES: usize = 2 * 1024 * 1024;
const TIMEOUT: Duration = Duration::from_secs(8);
const METERS_PER_YARD: f64 = 0.9144;

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum RavelryKind {
    Pattern,
    Yarn,
}

/// A recognised Ravelry library URL.
pub fn detect(url: &Url) -> Option<RavelryKind> {
    let host = url.host_str()?.to_ascii_lowercase();
    if host != "ravelry.com" && host != "www.ravelry.com" {
        return None;
    }
    let mut segments = url.path_segments()?;
    match (segments.next()?, segments.next()?, segments.next()) {
        ("patterns", "library", Some(permalink)) if !permalink.is_empty() => {
            Some(RavelryKind::Pattern)
        }
        ("yarns", "library", Some(permalink)) if !permalink.is_empty() => Some(RavelryKind::Yarn),
        _ => None,
    }
}

/// The numeric Ravelry id from the page source.
pub fn id_from_html(html: &str, kind: RavelryKind) -> Option<u64> {
    let pattern = match kind {
        RavelryKind::Pattern => r#"(?i)pattern[-_]id["'=:\s]+(\d{2,9})"#,
        RavelryKind::Yarn => r#"(?i)yarn[-_]id["'=:\s]+(\d{2,9})"#,
    };
    Regex::new(pattern)
        .ok()?
        .captures(html)
        .and_then(|captures| captures[1].parse().ok())
}

pub struct RavelryCredentials {
    username: String,
    password: Zeroizing<String>,
}

impl fmt::Debug for RavelryCredentials {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str("RavelryCredentials([REDACTED])")
    }
}

impl RavelryCredentials {
    pub fn new(username: String, password: String) -> Option<Self> {
        let username = username.trim().to_owned();
        let password = password.trim().to_owned();
        (!username.is_empty() && !password.is_empty()).then(|| Self {
            username,
            password: Zeroizing::new(password),
        })
    }

    /// `RAVELRY_USERNAME` and `RAVELRY_PASSWORD`; both or neither.
    pub fn from_lookup(mut lookup: impl FnMut(&str) -> Option<String>) -> Option<Self> {
        Self::new(lookup("RAVELRY_USERNAME")?, lookup("RAVELRY_PASSWORD")?)
    }
}

type JsonFuture<'a> = Pin<Box<dyn Future<Output = Result<Value, RavelryError>> + Send + 'a>>;

#[derive(Clone, Copy, Debug, Eq, PartialEq, thiserror::Error)]
pub enum RavelryError {
    #[error("Ravelry request failed")]
    Transport,
    #[error("Ravelry returned HTTP status {0}")]
    Status(u16),
    #[error("Ravelry response was invalid")]
    Invalid,
}

/// Injectable so tests never touch the network.
pub trait RavelryTransport: Send + Sync {
    fn get_json<'a>(&'a self, path: &'a str, credentials: &'a RavelryCredentials)
    -> JsonFuture<'a>;
}

pub struct ReqwestRavelry {
    client: Client,
}

impl ReqwestRavelry {
    pub fn new() -> Result<Self, RavelryError> {
        let client = Client::builder()
            .no_proxy()
            .redirect(reqwest::redirect::Policy::none())
            .timeout(TIMEOUT)
            .user_agent(concat!("stricknani/", env!("CARGO_PKG_VERSION")))
            .build()
            .map_err(|_| RavelryError::Transport)?;
        Ok(Self { client })
    }
}

impl RavelryTransport for ReqwestRavelry {
    fn get_json<'a>(
        &'a self,
        path: &'a str,
        credentials: &'a RavelryCredentials,
    ) -> JsonFuture<'a> {
        Box::pin(async move {
            let mut response = self
                .client
                .get(format!("{API_BASE}{path}"))
                .basic_auth(&credentials.username, Some(credentials.password.as_str()))
                .header(header::ACCEPT, "application/json")
                .send()
                .await
                .map_err(|_| RavelryError::Transport)?;
            if !response.status().is_success() {
                return Err(RavelryError::Status(response.status().as_u16()));
            }
            let mut bytes = Vec::new();
            while let Some(chunk) = response
                .chunk()
                .await
                .map_err(|_| RavelryError::Transport)?
            {
                if bytes.len() + chunk.len() > MAX_RESPONSE_BYTES {
                    return Err(RavelryError::Invalid);
                }
                bytes.extend_from_slice(&chunk);
            }
            serde_json::from_slice(&bytes).map_err(|_| RavelryError::Invalid)
        })
    }
}

#[derive(Clone)]
pub struct RavelryClient {
    credentials: Arc<RavelryCredentials>,
    transport: Arc<dyn RavelryTransport>,
}

impl RavelryClient {
    pub fn new(credentials: RavelryCredentials) -> Result<Self, RavelryError> {
        Ok(Self::with_transport(
            credentials,
            Arc::new(ReqwestRavelry::new()?),
        ))
    }

    pub fn with_transport(
        credentials: RavelryCredentials,
        transport: Arc<dyn RavelryTransport>,
    ) -> Self {
        Self {
            credentials: Arc::new(credentials),
            transport,
        }
    }

    pub async fn fetch(&self, kind: RavelryKind, id: u64) -> Result<Value, RavelryError> {
        let path = match kind {
            RavelryKind::Pattern => format!("/patterns/{id}.json"),
            RavelryKind::Yarn => format!("/yarns/{id}.json"),
        };
        self.transport.get_json(&path, &self.credentials).await
    }
}

/// Process-wide client built from the environment, `None` when no key pair is configured.
pub fn client_from_environment() -> Option<&'static RavelryClient> {
    static CLIENT: std::sync::OnceLock<Option<RavelryClient>> = std::sync::OnceLock::new();
    CLIENT
        .get_or_init(|| {
            let credentials = RavelryCredentials::from_lookup(|name| std::env::var(name).ok())?;
            RavelryClient::new(credentials).ok()
        })
        .as_ref()
}

// ------------------------------------------------------------- mapping

fn text_of(value: Option<&Value>) -> Option<String> {
    match value? {
        Value::String(s) => {
            let s = strip_html(s);
            (!s.is_empty()).then_some(s)
        }
        Value::Number(n) => Some(n.to_string()),
        _ => None,
    }
}

fn set(
    draft: &mut ImportDocumentDraft,
    key: &str,
    target: impl FnOnce(&mut ImportDocumentDraft) -> &mut Option<String>,
    value: Option<String>,
) {
    if let Some(value) = value.filter(|v| !v.trim().is_empty()) {
        *target(draft) = Some(value);
        draft.provenance.insert(key.to_owned(), FieldSource::Site);
    }
}

fn metric_sizes(list: Option<&Value>) -> Option<String> {
    let sizes: Vec<String> = list?
        .as_array()?
        .iter()
        .filter_map(|size| {
            let metric = size.get("metric")?.as_f64()?;
            (metric > 0.0).then(|| {
                if (metric - metric.round()).abs() < 0.001 {
                    format!("{} mm", metric.round() as i64)
                } else {
                    format!("{metric} mm")
                }
            })
        })
        .collect();
    let mut unique = Vec::new();
    for size in sizes {
        if !unique.contains(&size) {
            unique.push(size);
        }
    }
    (!unique.is_empty()).then(|| unique.join(", "))
}

fn photo_urls(photos: Option<&Value>) -> Vec<String> {
    let Some(list) = photos.and_then(Value::as_array) else {
        return Vec::new();
    };
    let mut photos: Vec<&Value> = list.iter().collect();
    photos.sort_by_key(|photo| photo.get("sort_order").and_then(Value::as_i64).unwrap_or(0));
    photos
        .into_iter()
        .filter_map(|photo| {
            [
                "medium2_url",
                "medium_url",
                "small2_url",
                "small_url",
                "square_url",
            ]
            .iter()
            .find_map(|key| photo.get(*key).and_then(Value::as_str))
        })
        .filter(|url| url.starts_with("https://"))
        .map(str::to_owned)
        .take(10)
        .collect()
}

fn yards_to_meters(yards: f64) -> i64 {
    (yards * METERS_PER_YARD).round() as i64
}

/// Merge a `/patterns/{id}.json` document (either the bare pattern or `{"pattern": ...}`).
pub fn apply_pattern(draft: &mut ImportDocumentDraft, json: &Value) {
    let pattern = json.get("pattern").unwrap_or(json);
    draft.page_kind = PageKind::Pattern;
    set(
        draft,
        "project.name",
        |d| &mut d.project.name,
        text_of(pattern.get("name")),
    );
    let designer = pattern
        .pointer("/pattern_author/name")
        .and_then(Value::as_str)
        .map(str::to_owned);
    set(
        draft,
        "project.designer",
        |d| &mut d.project.designer,
        designer,
    );
    set(
        draft,
        "project.description",
        |d| &mut d.project.description,
        text_of(pattern.get("notes_html").or_else(|| pattern.get("notes")))
            .map(|d| d.chars().take(2000).collect()),
    );
    set(
        draft,
        "project.needles",
        |d| &mut d.project.needles,
        metric_sizes(pattern.get("pattern_needle_sizes")),
    );
    set(
        draft,
        "project.stitchSample",
        |d| &mut d.project.stitch_sample,
        text_of(pattern.get("gauge_description")),
    );
    set(
        draft,
        "project.sizes",
        |d| &mut d.project.sizes,
        text_of(pattern.get("sizes_available")),
    );
    let weight = text_of(pattern.get("yarn_weight_description"))
        .or_else(|| text_of(pattern.pointer("/yarn_weight/name")));
    let weight = weight.map(|w| text::weight_category(&w).unwrap_or(w));
    set(
        draft,
        "project.weightCategory",
        |d| &mut d.project.weight_category,
        weight,
    );
    if let Some(difficulty) = pattern.get("difficulty_average").and_then(Value::as_f64)
        && difficulty > 0.0
    {
        draft.project.difficulty = Some(format!("{difficulty:.1} / 10"));
        draft
            .provenance
            .insert("project.difficulty".into(), FieldSource::Site);
    }
    let category = pattern
        .get("pattern_categories")
        .and_then(Value::as_array)
        .and_then(|list| list.last())
        .and_then(|category| category.get("name"))
        .and_then(Value::as_str)
        .map(str::to_owned);
    set(
        draft,
        "project.category",
        |d| &mut d.project.category,
        category,
    );

    let yards = pattern
        .get("yardage")
        .and_then(Value::as_f64)
        .filter(|y| *y > 0.0);
    if let Some(yards) = yards {
        let max = pattern
            .get("yardage_max")
            .and_then(Value::as_f64)
            .filter(|m| *m > yards);
        let line = match max {
            Some(max) => format!(
                "Yardage: {}-{} yds ({}-{} m)",
                yards.round(),
                max.round(),
                yards_to_meters(yards),
                yards_to_meters(max)
            ),
            None => format!(
                "Yardage: {} yds ({} m)",
                yards.round(),
                yards_to_meters(yards)
            ),
        };
        let existing = draft.project.other_materials.take().unwrap_or_default();
        draft.project.other_materials = Some(if existing.is_empty() {
            line
        } else {
            format!("{existing}\n{line}")
        });
        draft
            .provenance
            .insert("project.otherMaterials".into(), FieldSource::Site);
    }

    if let Some(packs) = pattern.get("packs").and_then(Value::as_array) {
        for pack in packs.iter().take(6) {
            let name =
                text_of(pack.get("yarn_name")).or_else(|| text_of(pack.pointer("/yarn/name")));
            let brand = text_of(pack.pointer("/yarn/yarn_company_name"))
                .or_else(|| text_of(pack.pointer("/yarn/yarn_company/name")));
            if name.is_none() {
                continue;
            }
            draft.project.yarns.push(YarnImportDraft {
                name,
                brand,
                colorway: text_of(pack.get("colorway")),
                weight_category: text_of(pack.pointer("/yarn/yarn_weight/name")),
                link: pack
                    .pointer("/yarn/permalink")
                    .and_then(Value::as_str)
                    .map(|p| format!("https://www.ravelry.com/yarns/library/{p}")),
                ..YarnImportDraft::default()
            });
        }
    }
    let photos = photo_urls(pattern.get("photos"));
    if !photos.is_empty() {
        draft.project.image_urls = photos;
        draft
            .provenance
            .insert("project.imageUrls".into(), FieldSource::Site);
    }
    let craft = pattern.pointer("/craft/name").and_then(Value::as_str);
    if let Some(craft) = craft
        && !draft
            .project
            .tags
            .iter()
            .any(|tag| tag.eq_ignore_ascii_case(craft))
    {
        draft.project.tags.insert(0, craft.to_lowercase());
    }
}

/// Merge a `/yarns/{id}.json` document.
pub fn apply_yarn(draft: &mut ImportDocumentDraft, json: &Value) {
    let source = json.get("yarn").unwrap_or(json);
    draft.page_kind = PageKind::Yarn;
    let mut yarn: YarnDetails = draft.yarn.take().unwrap_or_default();
    let mut put = |key: &str, target: &mut Option<String>, value: Option<String>| {
        if let Some(value) = value.filter(|v| !v.trim().is_empty()) {
            *target = Some(value);
            draft
                .provenance
                .insert(format!("yarn.{key}"), FieldSource::Site);
        }
    };
    put("name", &mut yarn.name, text_of(source.get("name")));
    put(
        "brand",
        &mut yarn.brand,
        text_of(source.get("yarn_company_name"))
            .or_else(|| text_of(source.pointer("/yarn_company/name"))),
    );
    let weight = text_of(source.pointer("/yarn_weight/name"));
    put(
        "weightCategory",
        &mut yarn.weight_category,
        weight.map(|w| text::weight_category(&w).unwrap_or(w)),
    );
    put(
        "description",
        &mut yarn.description,
        text_of(source.get("notes_html").or_else(|| source.get("notes")))
            .map(|d| d.chars().take(1500).collect()),
    );

    let fibers = source
        .get("yarn_fibers")
        .and_then(Value::as_array)
        .map(|list| {
            list.iter()
                .filter_map(|fiber| {
                    let name = fiber
                        .pointer("/fiber_type/name")
                        .or_else(|| fiber.get("fiber_type_name"))
                        .and_then(Value::as_str)?;
                    match fiber.get("percentage").and_then(Value::as_f64) {
                        Some(percent) => Some(format!("{}% {}", percent.round() as i64, name)),
                        None => Some(name.to_owned()),
                    }
                })
                .collect::<Vec<_>>()
                .join(", ")
        });
    put("fiberContent", &mut yarn.fiber_content, fibers);

    let needles = {
        let min = source
            .pointer("/min_needle_size/metric")
            .and_then(Value::as_f64);
        let max = source
            .pointer("/max_needle_size/metric")
            .and_then(Value::as_f64);
        match (min, max) {
            (Some(min), Some(max)) if (max - min).abs() > 0.01 => Some(format!("{min}-{max} mm")),
            (Some(value), _) | (_, Some(value)) => Some(format!("{value} mm")),
            _ => None,
        }
    };
    put("recommendedNeedles", &mut yarn.recommended_needles, needles);
    if source.get("machine_washable").and_then(Value::as_bool) == Some(true) {
        put("care", &mut yarn.care, Some("Machine washable".to_owned()));
    }
    if let Some(grams) = source
        .get("grams")
        .and_then(Value::as_i64)
        .filter(|g| (5..=2000).contains(g))
    {
        yarn.weight_grams = Some(grams);
        draft
            .provenance
            .insert("yarn.weightGrams".into(), FieldSource::Site);
    }
    if let Some(yards) = source
        .get("yardage")
        .and_then(Value::as_f64)
        .filter(|y| *y > 0.0)
    {
        yarn.length_meters = Some(yards_to_meters(yards));
        draft
            .provenance
            .insert("yarn.lengthMeters".into(), FieldSource::Site);
    }
    let photos = photo_urls(source.get("photos"));
    if !photos.is_empty() {
        yarn.image_urls = photos;
        draft
            .provenance
            .insert("yarn.imageUrls".into(), FieldSource::Site);
    }
    draft.yarn = Some(yarn);
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn library_urls_are_detected() {
        let pattern = Url::parse("https://www.ravelry.com/patterns/library/hitchhiker").unwrap();
        let yarn = Url::parse("https://www.ravelry.com/yarns/library/drops-air").unwrap();
        let other = Url::parse("https://example.com/patterns/library/x").unwrap();
        assert_eq!(detect(&pattern), Some(RavelryKind::Pattern));
        assert_eq!(detect(&yarn), Some(RavelryKind::Yarn));
        assert_eq!(detect(&other), None);
    }

    #[test]
    fn ids_come_from_page_attributes() {
        assert_eq!(
            id_from_html(r#"<div pattern-id="211562" id="x">"#, RavelryKind::Pattern),
            Some(211562)
        );
        assert_eq!(id_from_html("nothing", RavelryKind::Yarn), None);
    }
}
