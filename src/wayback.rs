//! Optional Wayback Machine submission and availability lookup.
//!
//! The client is disabled by default. It only sends source URLs to fixed
//! Internet Archive API hosts; it never fetches a user-supplied URL itself.

use std::{future::Future, net::IpAddr, pin::Pin, sync::Arc, time::Duration};

use reqwest::{Client, StatusCode};
use serde_json::Value;
use tokio::sync::{OwnedSemaphorePermit, Semaphore};
use url::{Host, Url};

use crate::imports::{DnsResolver, SystemResolver, is_public_address};

const MAX_SOURCE_URL_BYTES: usize = 2_048;
const MAX_RESPONSE_BYTES: usize = 64 * 1024;
const REQUEST_TIMEOUT: Duration = Duration::from_secs(15);
const TOTAL_TIMEOUT: Duration = Duration::from_secs(30);
const MAX_CONCURRENT_REQUESTS: usize = 2;

pub type WaybackFuture<'a, T> = Pin<Box<dyn Future<Output = T> + Send + 'a>>;

/// Redacted errors safe to return to callers or record in durable job state.
#[derive(Clone, Copy, Debug, Eq, PartialEq, thiserror::Error)]
pub enum WaybackError {
    #[error("wayback_disabled")]
    Disabled,
    #[error("wayback_invalid_source")]
    InvalidSource,
    #[error("wayback_unsafe_source")]
    UnsafeSource,
    #[error("wayback_source_resolution_failed")]
    SourceResolutionFailed,
    #[error("wayback_timed_out")]
    TimedOut,
    #[error("wayback_transport_failed")]
    TransportFailed,
    #[error("wayback_redirect_rejected")]
    RedirectRejected,
    #[error("wayback_invalid_archive_response")]
    InvalidArchiveResponse,
    #[error("wayback_save_failed")]
    SaveFailed,
    #[error("wayback_internal_error")]
    Internal,
}

/// Durable state a caller can persist after trying to archive a source URL.
#[derive(Clone, Debug, Eq, PartialEq)]
pub enum WaybackOutcome {
    /// Submission was accepted, but a snapshot is not available yet.
    Pending,
    /// A trusted Wayback snapshot URL is available.
    Success { snapshot_url: Url },
    /// Submission and any fallback lookup failed.
    Failure { error: WaybackError },
}

/// Injectable transport for deterministic tests. Implementations must not
/// follow redirects or route requests through ambient proxies.
pub trait WaybackTransport: Send + Sync {
    fn save<'a>(
        &'a self,
        source_url: &'a Url,
    ) -> WaybackFuture<'a, Result<Option<Url>, WaybackError>>;

    fn availability<'a>(
        &'a self,
        source_url: &'a Url,
    ) -> WaybackFuture<'a, Result<Option<Url>, WaybackError>>;
}

const SAVE_ENDPOINT_PREFIX: &str = "https://web.archive.org/save/";

/// Production HTTP transport, restricted to the fixed Wayback origin.
#[derive(Clone, Copy, Debug, Default)]
pub struct ReqwestWaybackTransport;

impl WaybackTransport for ReqwestWaybackTransport {
    fn save<'a>(
        &'a self,
        source_url: &'a Url,
    ) -> WaybackFuture<'a, Result<Option<Url>, WaybackError>> {
        Box::pin(async move {
            let client = wayback_http_client()?;
            let endpoint = save_endpoint(source_url)?;
            let response = client
                .get(endpoint)
                .send()
                .await
                .map_err(|_error| WaybackError::TransportFailed)?;

            if is_redirect(response.status()) {
                let location = response
                    .headers()
                    .get(reqwest::header::LOCATION)
                    .and_then(|value| value.to_str().ok())
                    .ok_or(WaybackError::RedirectRejected)?;
                let archive_url = response
                    .url()
                    .join(location)
                    .map_err(|_error| WaybackError::RedirectRejected)?;
                return checked_snapshot_url(&archive_url).map(Some);
            }
            if !response.status().is_success() {
                return Err(WaybackError::SaveFailed);
            }
            discard_bounded(response).await?;
            Ok(None)
        })
    }

    fn availability<'a>(
        &'a self,
        source_url: &'a Url,
    ) -> WaybackFuture<'a, Result<Option<Url>, WaybackError>> {
        Box::pin(async move {
            let client = wayback_http_client()?;
            let mut endpoint = Url::parse("https://archive.org/wayback/available")
                .map_err(|_error| WaybackError::Internal)?;
            endpoint
                .query_pairs_mut()
                .append_pair("url", source_url.as_str());
            let response = client
                .get(endpoint)
                .send()
                .await
                .map_err(|_error| WaybackError::TransportFailed)?;
            if is_redirect(response.status()) {
                return Err(WaybackError::RedirectRejected);
            }
            if !response.status().is_success() {
                return Err(WaybackError::TransportFailed);
            }
            let body = read_bounded(response).await?;
            let value: Value = serde_json::from_slice(&body)
                .map_err(|_error| WaybackError::InvalidArchiveResponse)?;
            let Some(raw_url) = value
                .pointer("/archived_snapshots/closest/url")
                .and_then(Value::as_str)
            else {
                return Ok(None);
            };
            let archive_url =
                Url::parse(raw_url).map_err(|_error| WaybackError::InvalidArchiveResponse)?;
            checked_snapshot_url(&archive_url).map(Some)
        })
    }
}

fn wayback_http_client() -> Result<Client, WaybackError> {
    Client::builder()
        .no_proxy()
        .redirect(reqwest::redirect::Policy::none())
        .timeout(REQUEST_TIMEOUT)
        .connect_timeout(REQUEST_TIMEOUT)
        .user_agent(concat!(
            env!("CARGO_PKG_NAME"),
            "/",
            env!("CARGO_PKG_VERSION")
        ))
        .build()
        .map_err(|_error| WaybackError::Internal)
}

async fn discard_bounded(mut response: reqwest::Response) -> Result<(), WaybackError> {
    if response
        .content_length()
        .is_some_and(|length| length > MAX_RESPONSE_BYTES as u64)
    {
        return Err(WaybackError::InvalidArchiveResponse);
    }
    let mut total = 0usize;
    while let Some(chunk) = response
        .chunk()
        .await
        .map_err(|_error| WaybackError::TransportFailed)?
    {
        total = total.saturating_add(chunk.len());
        if total > MAX_RESPONSE_BYTES {
            return Err(WaybackError::InvalidArchiveResponse);
        }
    }
    Ok(())
}

async fn read_bounded(mut response: reqwest::Response) -> Result<Vec<u8>, WaybackError> {
    if response
        .content_length()
        .is_some_and(|length| length > MAX_RESPONSE_BYTES as u64)
    {
        return Err(WaybackError::InvalidArchiveResponse);
    }
    let mut body = Vec::with_capacity(1024);
    while let Some(chunk) = response
        .chunk()
        .await
        .map_err(|_error| WaybackError::TransportFailed)?
    {
        if body.len().saturating_add(chunk.len()) > MAX_RESPONSE_BYTES {
            return Err(WaybackError::InvalidArchiveResponse);
        }
        body.extend_from_slice(&chunk);
    }
    Ok(body)
}

fn is_redirect(status: StatusCode) -> bool {
    matches!(status.as_u16(), 301 | 302 | 303 | 307 | 308)
}

fn save_endpoint(source_url: &Url) -> Result<Url, WaybackError> {
    Url::parse(&format!("{SAVE_ENDPOINT_PREFIX}{source_url}"))
        .map_err(|_error| WaybackError::InvalidSource)
}

fn checked_snapshot_url(url: &Url) -> Result<Url, WaybackError> {
    if url.scheme() == "https"
        && url.host_str() == Some("web.archive.org")
        && url.port().is_none()
        && url.username().is_empty()
        && url.password().is_none()
        && url.fragment().is_none()
        && url.path().starts_with("/web/")
    {
        Ok(url.clone())
    } else {
        Err(WaybackError::InvalidArchiveResponse)
    }
}

/// Optional archive client. It performs no work unless constructed enabled.
/// Clone one instance into application state to share its concurrency limit.
pub struct WaybackClient<T = ReqwestWaybackTransport, R = SystemResolver> {
    enabled: bool,
    transport: Arc<T>,
    resolver: Arc<R>,
    semaphore: Arc<Semaphore>,
}

impl<T, R> Clone for WaybackClient<T, R> {
    fn clone(&self) -> Self {
        Self {
            enabled: self.enabled,
            transport: Arc::clone(&self.transport),
            resolver: Arc::clone(&self.resolver),
            semaphore: Arc::clone(&self.semaphore),
        }
    }
}

impl Default for WaybackClient<ReqwestWaybackTransport, SystemResolver> {
    fn default() -> Self {
        Self::new(false)
    }
}

impl WaybackClient<ReqwestWaybackTransport, SystemResolver> {
    pub fn new(enabled: bool) -> Self {
        Self::with_transport_and_resolver(
            enabled,
            Arc::new(ReqwestWaybackTransport),
            Arc::new(SystemResolver),
        )
    }
}

impl<T, R> WaybackClient<T, R>
where
    T: WaybackTransport + 'static,
    R: DnsResolver + Send + Sync + 'static,
{
    pub fn with_transport_and_resolver(enabled: bool, transport: Arc<T>, resolver: Arc<R>) -> Self {
        Self {
            enabled,
            transport,
            resolver,
            semaphore: Arc::new(Semaphore::new(MAX_CONCURRENT_REQUESTS)),
        }
    }

    pub const fn is_enabled(&self) -> bool {
        self.enabled
    }

    /// Submit a validated public source URL and check for a resulting snapshot.
    /// Errors are returned as redacted variants with no submitted URL attached.
    pub async fn archive(&self, raw_source_url: &str) -> WaybackOutcome {
        if !self.enabled {
            return WaybackOutcome::Failure {
                error: WaybackError::Disabled,
            };
        }
        let deadline = tokio::time::Instant::now() + TOTAL_TIMEOUT;
        let source_url = match tokio::time::timeout_at(
            deadline,
            validate_source_url(raw_source_url, self.resolver.as_ref()),
        )
        .await
        {
            Ok(Ok(url)) => url,
            Ok(Err(error)) => return WaybackOutcome::Failure { error },
            Err(_elapsed) => {
                return WaybackOutcome::Failure {
                    error: WaybackError::TimedOut,
                };
            }
        };
        let permit =
            match tokio::time::timeout_at(deadline, self.semaphore.clone().acquire_owned()).await {
                Ok(Ok(permit)) => permit,
                Ok(Err(_error)) => {
                    return WaybackOutcome::Failure {
                        error: WaybackError::Internal,
                    };
                }
                Err(_elapsed) => {
                    return WaybackOutcome::Failure {
                        error: WaybackError::TimedOut,
                    };
                }
            };
        self.archive_validated(source_url, deadline, permit).await
    }

    /// Check for a snapshot without submitting another save request.
    pub async fn check_availability(&self, raw_source_url: &str) -> WaybackOutcome {
        if !self.enabled {
            return WaybackOutcome::Failure {
                error: WaybackError::Disabled,
            };
        }
        let deadline = tokio::time::Instant::now() + TOTAL_TIMEOUT;
        let source_url = match tokio::time::timeout_at(
            deadline,
            validate_source_url(raw_source_url, self.resolver.as_ref()),
        )
        .await
        {
            Ok(Ok(url)) => url,
            Ok(Err(error)) => return WaybackOutcome::Failure { error },
            Err(_elapsed) => {
                return WaybackOutcome::Failure {
                    error: WaybackError::TimedOut,
                };
            }
        };
        let _permit =
            match tokio::time::timeout_at(deadline, self.semaphore.clone().acquire_owned()).await {
                Ok(Ok(permit)) => permit,
                Ok(Err(_error)) => {
                    return WaybackOutcome::Failure {
                        error: WaybackError::Internal,
                    };
                }
                Err(_elapsed) => {
                    return WaybackOutcome::Failure {
                        error: WaybackError::TimedOut,
                    };
                }
            };
        match tokio::time::timeout_at(deadline, self.transport.availability(&source_url)).await {
            Ok(Ok(Some(snapshot_url))) => match checked_snapshot_url(&snapshot_url) {
                Ok(snapshot_url) => WaybackOutcome::Success { snapshot_url },
                Err(error) => WaybackOutcome::Failure { error },
            },
            Ok(Ok(None)) => WaybackOutcome::Pending,
            Ok(Err(error)) => WaybackOutcome::Failure { error },
            Err(_elapsed) => WaybackOutcome::Failure {
                error: WaybackError::TimedOut,
            },
        }
    }

    async fn archive_validated(
        &self,
        source_url: Url,
        deadline: tokio::time::Instant,
        _permit: OwnedSemaphorePermit,
    ) -> WaybackOutcome {
        let save_result =
            match tokio::time::timeout_at(deadline, self.transport.save(&source_url)).await {
                Ok(result) => result,
                Err(_elapsed) => {
                    return WaybackOutcome::Failure {
                        error: WaybackError::TimedOut,
                    };
                }
            };
        let (saved_snapshot, save_accepted) = match save_result {
            Ok(Some(snapshot_url)) => (Some(snapshot_url), true),
            Ok(None) => (None, true),
            Err(_error) => (None, false),
        };
        if let Some(snapshot_url) = saved_snapshot {
            return match checked_snapshot_url(&snapshot_url) {
                Ok(snapshot_url) => WaybackOutcome::Success { snapshot_url },
                Err(error) => WaybackOutcome::Failure { error },
            };
        }

        let availability =
            match tokio::time::timeout_at(deadline, self.transport.availability(&source_url)).await
            {
                Ok(result) => result,
                Err(_elapsed) => {
                    return WaybackOutcome::Failure {
                        error: WaybackError::TimedOut,
                    };
                }
            };
        match availability {
            Ok(Some(snapshot_url)) => match checked_snapshot_url(&snapshot_url) {
                Ok(snapshot_url) => WaybackOutcome::Success { snapshot_url },
                Err(error) => WaybackOutcome::Failure { error },
            },
            Ok(None) if save_accepted => WaybackOutcome::Pending,
            Ok(None) => WaybackOutcome::Failure {
                error: WaybackError::SaveFailed,
            },
            Err(_error) if save_accepted => WaybackOutcome::Pending,
            Err(error) => WaybackOutcome::Failure { error },
        }
    }
}

/// Validate and resolve a user-supplied source before disclosing it to the
/// external archive. Every resolved address must be publicly routable.
pub async fn validate_source_url<R: DnsResolver>(
    input: &str,
    resolver: &R,
) -> Result<Url, WaybackError> {
    if input.len() > MAX_SOURCE_URL_BYTES
        || input.trim() != input
        || input.bytes().any(|byte| byte.is_ascii_control())
    {
        return Err(WaybackError::InvalidSource);
    }
    let url = Url::parse(input).map_err(|_error| WaybackError::InvalidSource)?;
    if !matches!(url.scheme(), "http" | "https")
        || url.fragment().is_some()
        || !url.username().is_empty()
        || url.password().is_some()
        || input
            .split_once("://")
            .and_then(|(_, rest)| rest.split(['/', '?', '#']).next())
            .is_some_and(|authority| authority.contains('@'))
        || url.port().is_some_and(|port| {
            let allowed = if url.scheme() == "https" { 443 } else { 80 };
            port != allowed
        })
    {
        return Err(WaybackError::InvalidSource);
    }

    let host = url.host().ok_or(WaybackError::InvalidSource)?;
    let addresses = match host {
        Host::Ipv4(address) => vec![IpAddr::V4(address)],
        Host::Ipv6(address) => vec![IpAddr::V6(address)],
        Host::Domain(hostname) => {
            if is_reserved_hostname(hostname) || !hostname.contains('.') {
                return Err(WaybackError::UnsafeSource);
            }
            resolver
                .resolve(hostname)
                .await
                .map_err(|_error| WaybackError::SourceResolutionFailed)?
        }
    };
    if addresses.is_empty() {
        return Err(WaybackError::SourceResolutionFailed);
    }
    if addresses.iter().any(|address| !is_public_address(*address)) {
        return Err(WaybackError::UnsafeSource);
    }
    Ok(url)
}

fn is_reserved_hostname(hostname: &str) -> bool {
    let hostname = hostname.trim_end_matches('.').to_ascii_lowercase();
    [
        "localhost",
        "local",
        "internal",
        "home.arpa",
        "onion",
        "test",
        "invalid",
        "example",
    ]
    .iter()
    .any(|suffix| hostname == *suffix || hostname.ends_with(&format!(".{suffix}")))
        || ["example.com", "example.org", "example.net"]
            .iter()
            .any(|suffix| hostname == *suffix || hostname.ends_with(&format!(".{suffix}")))
}

#[cfg(test)]
mod tests {
    use super::save_endpoint;
    use url::Url;

    #[test]
    fn save_endpoint_keeps_the_source_url_path_and_query_shape() {
        let source = Url::parse("https://ravelry.com/patterns/123?color=blue&size=2")
            .expect("source URL should parse");

        let endpoint = save_endpoint(&source).expect("save endpoint should parse");

        assert_eq!(
            endpoint.as_str(),
            "https://web.archive.org/save/https://ravelry.com/patterns/123?color=blue&size=2"
        );
    }
}
