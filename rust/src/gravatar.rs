//! Privacy-preserving Gravatar fallback for users without an uploaded avatar.
//!
//! Clients never contact Gravatar. The server derives the SHA-256 address hash,
//! fetches the image from a fixed host with a short timeout and a size cap, and
//! caches the result (and 404s) on disk under the media root.

use std::{
    path::{Path, PathBuf},
    sync::Arc,
    time::{Duration, SystemTime},
};

use reqwest::{Client, StatusCode, Url, redirect::Policy};
use sha2::{Digest, Sha256};
use tokio::sync::Semaphore;

/// Public Gravatar avatar endpoint; only the hash is appended.
pub const DEFAULT_BASE_URL: &str = "https://gravatar.com/avatar";
/// File name clients use on the avatar routes for the Gravatar-backed image.
pub const SERVE_NAME: &str = "gravatar";
const CACHE_DIR: &str = "gravatar";
const MAX_BYTES: usize = 1024 * 1024;
const MAX_CONCURRENT_FETCHES: usize = 4;
const DEFAULT_TIMEOUT: Duration = Duration::from_secs(5);
const DEFAULT_TTL: Duration = Duration::from_secs(24 * 60 * 60);
const FAILURE_TTL: Duration = Duration::from_secs(5 * 60);

/// Runtime configuration, carried in `AppState`.
#[derive(Clone, Debug)]
pub struct GravatarSettings {
    pub enabled: bool,
    base_url: String,
    timeout: Duration,
    ttl: Duration,
    fetches: Arc<Semaphore>,
}

impl Default for GravatarSettings {
    fn default() -> Self {
        Self {
            enabled: false,
            base_url: DEFAULT_BASE_URL.to_owned(),
            timeout: DEFAULT_TIMEOUT,
            ttl: DEFAULT_TTL,
            fetches: Arc::new(Semaphore::new(MAX_CONCURRENT_FETCHES)),
        }
    }
}

impl GravatarSettings {
    pub fn enabled(enabled: bool) -> Self {
        Self {
            enabled,
            ..Self::default()
        }
    }

    /// Point the client at another endpoint. Intended for tests only.
    #[doc(hidden)]
    pub fn with_base_url(mut self, base_url: impl Into<String>) -> Self {
        self.base_url = base_url.into();
        self
    }

    /// Override the request timeout. Intended for tests only.
    #[doc(hidden)]
    pub fn with_timeout(mut self, timeout: Duration) -> Self {
        self.timeout = timeout;
        self
    }

    /// Override the cache lifetime. Intended for tests only.
    #[doc(hidden)]
    pub fn with_ttl(mut self, ttl: Duration) -> Self {
        self.ttl = ttl;
        self
    }
}

/// A cached or freshly fetched avatar image.
pub(crate) struct GravatarImage {
    pub(crate) bytes: Vec<u8>,
    pub(crate) content_type: &'static str,
}

/// Gravatar's SHA-256 address hash: lowercase hex of the trimmed, lowercased email.
pub fn email_hash(email: &str) -> String {
    hex::encode(Sha256::digest(email.trim().to_lowercase().as_bytes()))
}

const TYPES: [(&str, &str); 4] = [
    ("jpg", "image/jpeg"),
    ("png", "image/png"),
    ("gif", "image/gif"),
    ("webp", "image/webp"),
];

fn sniff(bytes: &[u8]) -> Option<(&'static str, &'static str)> {
    if bytes.starts_with(&[0xff, 0xd8, 0xff]) {
        Some(TYPES[0])
    } else if bytes.starts_with(b"\x89PNG\r\n\x1a\n") {
        Some(TYPES[1])
    } else if bytes.starts_with(b"GIF87a") || bytes.starts_with(b"GIF89a") {
        Some(TYPES[2])
    } else if bytes.len() > 12 && &bytes[..4] == b"RIFF" && &bytes[8..12] == b"WEBP" {
        Some(TYPES[3])
    } else {
        None
    }
}

enum Fetched {
    Image(Vec<u8>),
    Missing,
    Failed,
}

/// Return the Gravatar for `email`, using the on-disk cache and refreshing it
/// when stale. `None` means "no Gravatar" (404, disabled, or fetch failure).
pub(crate) async fn resolve(
    settings: &GravatarSettings,
    media_root: &Path,
    email: &str,
) -> Option<GravatarImage> {
    if !settings.enabled || email.trim().is_empty() {
        return None;
    }
    let hash = email_hash(email);
    let dir = media_root.join(CACHE_DIR);
    if let Some(image) = read_image(&dir, &hash, Some(settings.ttl)).await {
        return Some(image);
    }
    if is_fresh(&dir.join(format!("{hash}.miss")), settings.ttl).await {
        return None;
    }
    if is_fresh(&dir.join(format!("{hash}.fail")), FAILURE_TTL).await {
        return read_image(&dir, &hash, None).await;
    }
    let _permit = settings.fetches.acquire().await.ok()?;
    // Another request may have filled the cache while we waited.
    if let Some(image) = read_image(&dir, &hash, Some(settings.ttl)).await {
        return Some(image);
    }
    tokio::fs::create_dir_all(&dir).await.ok()?;
    match fetch(settings, &hash).await {
        Fetched::Image(bytes) => {
            let (ext, content_type) = sniff(&bytes)?;
            remove_entries(&dir, &hash).await;
            write_atomic(&dir, &format!("{hash}.{ext}"), &bytes).await?;
            Some(GravatarImage {
                bytes,
                content_type,
            })
        }
        Fetched::Missing => {
            remove_entries(&dir, &hash).await;
            write_atomic(&dir, &format!("{hash}.miss"), b"").await;
            None
        }
        Fetched::Failed => {
            write_atomic(&dir, &format!("{hash}.fail"), b"").await;
            read_image(&dir, &hash, None).await
        }
    }
}

async fn is_fresh(path: &Path, ttl: Duration) -> bool {
    let Ok(modified) = tokio::fs::metadata(path)
        .await
        .and_then(|meta| meta.modified())
    else {
        return false;
    };
    SystemTime::now()
        .duration_since(modified)
        .map(|age| age < ttl)
        .unwrap_or(true)
}

async fn read_image(dir: &Path, hash: &str, fresh_for: Option<Duration>) -> Option<GravatarImage> {
    for (ext, content_type) in TYPES {
        let path = dir.join(format!("{hash}.{ext}"));
        if let Some(ttl) = fresh_for
            && !is_fresh(&path, ttl).await
        {
            continue;
        }
        if let Ok(bytes) = tokio::fs::read(&path).await
            && bytes.len() <= MAX_BYTES
            && sniff(&bytes).is_some_and(|(_, sniffed)| sniffed == content_type)
        {
            return Some(GravatarImage {
                bytes,
                content_type,
            });
        }
    }
    None
}

async fn remove_entries(dir: &Path, hash: &str) {
    for ext in ["jpg", "png", "gif", "webp", "miss", "fail"] {
        let _ = tokio::fs::remove_file(dir.join(format!("{hash}.{ext}"))).await;
    }
}

async fn write_atomic(dir: &Path, name: &str, bytes: &[u8]) -> Option<()> {
    let tmp: PathBuf = dir.join(format!(".{name}.{:016x}.tmp", rand::random::<u64>()));
    tokio::fs::write(&tmp, bytes).await.ok()?;
    if tokio::fs::rename(&tmp, dir.join(name)).await.is_err() {
        let _ = tokio::fs::remove_file(&tmp).await;
        return None;
    }
    Some(())
}

async fn fetch(settings: &GravatarSettings, hash: &str) -> Fetched {
    let Ok(base) = Url::parse(&settings.base_url) else {
        return Fetched::Failed;
    };
    let Ok(url) = Url::parse(&format!(
        "{}/{hash}?s=256&d=404",
        settings.base_url.trim_end_matches('/')
    )) else {
        return Fetched::Failed;
    };
    // Only same-origin redirects are followed; anything else is a failure.
    let policy = Policy::custom(move |attempt| {
        let target = attempt.url();
        if attempt.previous().len() < 2
            && target.scheme() == base.scheme()
            && target.host_str() == base.host_str()
            && target.port_or_known_default() == base.port_or_known_default()
        {
            attempt.follow()
        } else {
            attempt.stop()
        }
    });
    let Ok(client) = Client::builder()
        .no_proxy()
        .redirect(policy)
        .timeout(settings.timeout)
        .connect_timeout(settings.timeout)
        .user_agent(concat!(
            env!("CARGO_PKG_NAME"),
            "/",
            env!("CARGO_PKG_VERSION")
        ))
        .build()
    else {
        return Fetched::Failed;
    };
    let Ok(mut response) = client.get(url).send().await else {
        return Fetched::Failed;
    };
    match response.status() {
        StatusCode::NOT_FOUND => return Fetched::Missing,
        StatusCode::OK => {}
        _ => return Fetched::Failed,
    }
    let declared_image = response
        .headers()
        .get(reqwest::header::CONTENT_TYPE)
        .and_then(|value| value.to_str().ok())
        .map(|value| value.split(';').next().unwrap_or("").trim().to_lowercase())
        .is_some_and(|value| TYPES.iter().any(|(_, content_type)| *content_type == value));
    if !declared_image
        || response
            .content_length()
            .is_some_and(|length| length > MAX_BYTES as u64)
    {
        return Fetched::Failed;
    }
    let mut body = Vec::new();
    loop {
        match response.chunk().await {
            Ok(Some(chunk)) => {
                if body.len().saturating_add(chunk.len()) > MAX_BYTES {
                    return Fetched::Failed;
                }
                body.extend_from_slice(&chunk);
            }
            Ok(None) => break,
            Err(_) => return Fetched::Failed,
        }
    }
    if sniff(&body).is_some() {
        Fetched::Image(body)
    } else {
        Fetched::Failed
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn hash_trims_and_lowercases() {
        assert_eq!(
            email_hash("  Test@Example.COM "),
            email_hash("test@example.com")
        );
        assert_eq!(email_hash("test@example.com").len(), 64);
    }
}
