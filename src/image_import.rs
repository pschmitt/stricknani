//! Bounded, SSRF-resistant image downloads used by project creation imports.

use std::{collections::HashSet, future::Future, pin::Pin, time::Duration};

use sha2::{Digest, Sha256};
use url::Url;

use crate::{
    db::AppState,
    import_document::{AssetKind, ImportAsset},
    media,
    url_import::assets::diagram_hint,
};

const MAX_URL_BYTES: usize = 2_048;
const MAX_IMAGE_BYTES: usize = 5 * 1024 * 1024;
const MAX_IMAGES_PER_TARGET: usize = 10;
const MAX_TOTAL_ATTEMPTS: usize = 40;
const MAX_TOTAL_RAW_BYTES: usize = 25 * 1024 * 1024;
// Android's API client uses a ten-second request timeout; leave time for response serialization.
const TOTAL_DEADLINE: Duration = Duration::from_secs(8);

pub type FetchFuture<'a> =
    Pin<Box<dyn Future<Output = Result<FetchedImage, FetchError>> + Send + 'a>>;

/// A completed image response with its untrusted HTTP content type and bounded body.
#[derive(Clone, Debug)]
pub struct FetchedImage {
    pub content_type: Option<String>,
    pub bytes: Vec<u8>,
}

/// An abstract fetcher so import behavior can be tested without external networking.
pub trait ImageFetcher: Send + Sync {
    fn fetch<'a>(&'a self, url: &'a Url) -> FetchFuture<'a>;
}

/// The production fetcher validates and pins every redirect destination.
#[derive(Debug, Default)]
pub struct HttpImageFetcher;

pub type FetchError = crate::imports::FetchError;

impl ImageFetcher for HttpImageFetcher {
    fn fetch<'a>(&'a self, url: &'a Url) -> FetchFuture<'a> {
        Box::pin(async move {
            let response = crate::imports::fetch_url(url.as_str()).await?;
            Ok(FetchedImage {
                content_type: response.content_type,
                bytes: response.body,
            })
        })
    }
}

pub(crate) struct PreparedCandidate {
    step_index: Option<usize>,
    original_filename: String,
    alt_text: String,
    image: media::PreparedImage,
}

/// Download and decode bounded gallery and per-step candidates before any database transaction.
pub(crate) async fn prepare_project_images(
    state: &AppState,
    project_name: &str,
    gallery_urls: &[String],
    step_urls: &[Vec<String>],
) -> Vec<PreparedCandidate> {
    let deadline = tokio::time::Instant::now() + TOTAL_DEADLINE;
    let mut attempts = 0;
    let mut raw_bytes = 0;
    let mut gallery_count = 0;
    let mut step_counts = vec![0; step_urls.len()];
    let mut seen = HashSet::new();
    let mut candidates = Vec::new();

    for (step_index, urls) in std::iter::once((None, gallery_urls)).chain(
        step_urls
            .iter()
            .enumerate()
            .map(|(index, urls)| (Some(index), urls.as_slice())),
    ) {
        for raw_url in urls {
            if tokio::time::Instant::now() >= deadline {
                return candidates;
            }
            if attempts >= MAX_TOTAL_ATTEMPTS || raw_bytes >= MAX_TOTAL_RAW_BYTES {
                return candidates;
            }
            let target_count = match step_index {
                Some(index) => &mut step_counts[index],
                None => &mut gallery_count,
            };
            if *target_count >= MAX_IMAGES_PER_TARGET {
                break;
            }
            attempts += 1;
            let Some(url) = parse_import_url(raw_url) else {
                continue;
            };
            let fetched =
                match tokio::time::timeout_at(deadline, state.image_fetcher.fetch(&url)).await {
                    Ok(Ok(image)) => image,
                    Ok(Err(_)) | Err(_) => continue,
                };
            raw_bytes = raw_bytes.saturating_add(fetched.bytes.len());
            if fetched.bytes.is_empty() || fetched.bytes.len() > MAX_IMAGE_BYTES {
                continue;
            }
            if raw_bytes > MAX_TOTAL_RAW_BYTES
                || !allowed_image_type(&url, fetched.content_type.as_deref())
            {
                continue;
            }
            let digest = Sha256::digest(&fetched.bytes);
            if !seen.insert(digest.to_vec()) {
                continue;
            }
            let image = match tokio::time::timeout_at(
                deadline,
                media::prepare_imported_image(fetched.bytes),
            )
            .await
            {
                Ok(Ok(image)) => image,
                Ok(Err(())) => continue,
                Err(_) => return candidates,
            };
            let original_filename = url
                .path_segments()
                .and_then(|mut segments| segments.next_back())
                .filter(|name| !name.is_empty())
                .map(media::safe_original_filename)
                .unwrap_or_else(|| "imported-image".to_owned());
            let ordinal = *target_count + 1;
            let alt_text = match step_index {
                Some(_) => original_filename.clone(),
                None => format!("{project_name} (imported image {ordinal})"),
            };
            *target_count += 1;
            candidates.push(PreparedCandidate {
                step_index,
                original_filename,
                alt_text,
                image,
            });
        }
    }
    candidates
}

/// Store prepared images after project creation, in short owner-checked transactions.
pub(crate) async fn persist_project_images(
    state: &AppState,
    user_id: i64,
    project_id: i64,
    step_ids: &[i64],
    candidates: Vec<PreparedCandidate>,
) {
    let mut gallery_primary_assigned = false;
    for candidate in candidates {
        let step_id = candidate
            .step_index
            .and_then(|index| step_ids.get(index).copied());
        if candidate.step_index.is_some() && step_id.is_none() {
            continue;
        }
        let result = media::persist_imported_project_image(
            state,
            user_id,
            project_id,
            step_id,
            media::ImportedImageMetadata {
                original_filename: &candidate.original_filename,
                alt_text: &candidate.alt_text,
                is_primary: candidate.step_index.is_none() && !gallery_primary_assigned,
            },
            &candidate.image,
        )
        .await;
        match result {
            Ok(()) if candidate.step_index.is_none() => gallery_primary_assigned = true,
            Ok(()) => {}
            Err(_) => tracing::debug!("project image import storage skipped"),
        }
    }
}

const MAX_ATTACHMENTS: usize = 8;
const MAX_ATTACHMENT_BYTES: usize = 5 * 1024 * 1024;
const MAX_LABEL_CHARS: usize = 120;

/// A downloaded and content-checked diagram image or PDF, ready to be stored as an attachment.
pub(crate) struct PreparedAttachment {
    original_filename: String,
    content_type: &'static str,
    bytes: Vec<u8>,
}

/// The attachment name the user sees. A label like "Diagram for DROPS 273-22" is used as is
/// (diagram images without a diagram word in it get a "Diagram - " prefix), otherwise the name
/// of the downloaded file is used.
pub(crate) fn attachment_filename(
    kind: AssetKind,
    label: Option<&str>,
    url: &Url,
    extension: &str,
) -> String {
    let cleaned: String = label
        .unwrap_or_default()
        .chars()
        .map(|c| {
            if matches!(c, '/' | '\\') || c.is_control() {
                ' '
            } else {
                c
            }
        })
        .collect::<String>()
        .split_whitespace()
        .collect::<Vec<_>>()
        .join(" ")
        .chars()
        .take(MAX_LABEL_CHARS)
        .collect();
    let file_stem = url
        .path_segments()
        .and_then(|mut segments| segments.next_back())
        .map(|name| name.rsplit_once('.').map_or(name, |(stem, _)| stem))
        .filter(|stem| !stem.is_empty())
        .unwrap_or("file");
    let stem = match (kind, cleaned.is_empty()) {
        (AssetKind::Diagram, true) => format!("Diagram - {file_stem}"),
        (AssetKind::Diagram, false) if !diagram_hint(&cleaned) => format!("Diagram - {cleaned}"),
        (AssetKind::Pdf, true) => file_stem.to_owned(),
        (_, _) => cleaned,
    };
    media::safe_original_filename(&format!("{stem}.{extension}"))
}

/// Download diagram images and PDFs of an import (before any database transaction), with the same
/// SSRF-safe fetcher and limits as photos. Files that are not what they claim to be are dropped.
pub(crate) async fn prepare_project_attachments(
    state: &AppState,
    assets: &[ImportAsset],
) -> Vec<PreparedAttachment> {
    let deadline = tokio::time::Instant::now() + TOTAL_DEADLINE;
    let mut seen = HashSet::new();
    let mut prepared = Vec::new();
    for asset in assets.iter().take(MAX_ATTACHMENTS) {
        if tokio::time::Instant::now() >= deadline {
            break;
        }
        let Some(url) = parse_import_url(&asset.url) else {
            continue;
        };
        let fetched = match tokio::time::timeout_at(deadline, state.image_fetcher.fetch(&url)).await
        {
            Ok(Ok(fetched)) => fetched,
            Ok(Err(_)) | Err(_) => continue,
        };
        if fetched.bytes.is_empty() || fetched.bytes.len() > MAX_ATTACHMENT_BYTES {
            continue;
        }
        let (content_type, extension) = match asset.kind {
            AssetKind::Diagram => match image::guess_format(&fetched.bytes) {
                Ok(image::ImageFormat::Jpeg) => ("image/jpeg", "jpg"),
                Ok(image::ImageFormat::Png) => ("image/png", "png"),
                Ok(image::ImageFormat::WebP) => ("image/webp", "webp"),
                Ok(image::ImageFormat::Gif) => ("image/gif", "gif"),
                _ => continue,
            },
            AssetKind::Pdf if fetched.bytes.starts_with(b"%PDF-") => ("application/pdf", "pdf"),
            AssetKind::Pdf => continue,
        };
        if !seen.insert(Sha256::digest(&fetched.bytes).to_vec()) {
            continue;
        }
        prepared.push(PreparedAttachment {
            original_filename: attachment_filename(
                asset.kind,
                asset.label.as_deref(),
                &url,
                extension,
            ),
            content_type,
            bytes: fetched.bytes,
        });
    }
    prepared
}

/// Store prepared diagrams and PDFs as project attachments after project creation.
pub(crate) async fn persist_project_attachments(
    state: &AppState,
    user_id: i64,
    project_id: i64,
    attachments: Vec<PreparedAttachment>,
) {
    for attachment in attachments {
        let result = media::persist_imported_attachment(
            state,
            user_id,
            project_id,
            attachment.original_filename,
            Some(attachment.content_type),
            attachment.bytes,
        )
        .await;
        if result.is_err() {
            tracing::debug!("project attachment import storage skipped");
        }
    }
}

/// Download and decode bounded yarn photo candidates before the yarn exists.
pub(crate) async fn prepare_yarn_images(
    state: &AppState,
    yarn_name: &str,
    urls: &[String],
) -> Vec<PreparedCandidate> {
    prepare_project_images(state, yarn_name, urls, &[]).await
}

/// Store prepared images as yarn photos; the first becomes the primary photo.
pub(crate) async fn persist_yarn_images(
    state: &AppState,
    user_id: i64,
    yarn_id: i64,
    yarn_name: &str,
    candidates: Vec<PreparedCandidate>,
) {
    for candidate in candidates {
        let result = media::persist_imported_yarn_photo(
            state,
            user_id,
            yarn_id,
            yarn_name,
            &candidate.original_filename,
            &candidate.image,
        )
        .await;
        if result.is_err() {
            tracing::debug!("yarn image import storage skipped");
        }
    }
}

fn parse_import_url(value: &str) -> Option<Url> {
    if value.len() > MAX_URL_BYTES {
        return None;
    }
    let url = Url::parse(value.trim()).ok()?;
    if !matches!(url.scheme(), "http" | "https")
        || url.host().is_none()
        || !url.username().is_empty()
        || url.password().is_some()
        || url.fragment().is_some()
    {
        return None;
    }
    Some(url)
}

fn allowed_image_type(url: &Url, content_type: Option<&str>) -> bool {
    let allowed_mime = ["image/jpeg", "image/png", "image/webp", "image/gif"];
    let allowed_extension = ["jpg", "jpeg", "png", "webp", "gif"];
    let content_type = content_type
        .and_then(|value| value.split(';').next())
        .map(str::trim)
        .map(str::to_ascii_lowercase);
    content_type
        .as_deref()
        .is_some_and(|value| allowed_mime.contains(&value))
        || url
            .path_segments()
            .and_then(|mut segments| segments.next_back())
            .and_then(|name| name.rsplit_once('.').map(|(_, extension)| extension))
            .is_some_and(|extension| {
                allowed_extension.contains(&extension.to_ascii_lowercase().as_str())
            })
}

#[cfg(test)]
mod tests {
    use super::{attachment_filename, parse_import_url};
    use crate::import_document::AssetKind;
    use url::Url;

    #[test]
    fn attachment_names_carry_the_label_and_kind() {
        let url =
            Url::parse("https://images.garnstudio.com/drops/mag/273/22/22-chart.jpg").unwrap();
        assert_eq!(
            attachment_filename(
                AssetKind::Diagram,
                Some("Diagram for DROPS 273-22"),
                &url,
                "jpg"
            ),
            "Diagram for DROPS 273-22.jpg"
        );
        assert_eq!(
            attachment_filename(AssetKind::Diagram, Some("Front / back"), &url, "jpg"),
            "Diagram - Front back.jpg"
        );
        assert_eq!(
            attachment_filename(AssetKind::Diagram, None, &url, "jpg"),
            "Diagram - 22-chart.jpg"
        );
        let pdf = Url::parse("https://example.test/files/pattern.pdf?x=1").unwrap();
        assert_eq!(
            attachment_filename(AssetKind::Pdf, None, &pdf, "pdf"),
            "pattern.pdf"
        );
    }

    #[test]
    fn import_urls_reject_unsafe_schemes_and_userinfo() {
        assert!(parse_import_url("file:///etc/passwd").is_none());
        assert!(parse_import_url("https://user:pass@example.test/image.png").is_none());
        assert!(parse_import_url("https://example.test/image.png").is_some());
    }
}
