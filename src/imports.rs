//! Bounded, SSRF-safe HTTP fetching for user-supplied import URLs.
//!
//! This module deliberately has no logging and keeps request URLs out of errors. The production
//! transport disables proxies and automatic redirects, and pins each validated DNS answer before
//! connecting. Callers should not log the `final_url`, which may contain private query values.

use std::future::Future;
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr};
use std::time::Duration;

use ammonia::Builder as HtmlSanitizer;
use reqwest::header::{ACCEPT_ENCODING, CONTENT_LENGTH, CONTENT_TYPE, LOCATION};
use reqwest::{Client, StatusCode};
use thiserror::Error;
use url::{Host, Url};

const MAX_REDIRECTS: usize = 5;
const MAX_BODY_BYTES: usize = 5 * 1024 * 1024;
const TOTAL_TIMEOUT: Duration = Duration::from_secs(15);

/// Many shop and pattern sites reject unknown clients, so requests look like an ordinary desktop
/// browser. Nothing here weakens the address and redirect checks.
const BROWSER_USER_AGENT: &str = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";

fn browser_headers() -> reqwest::header::HeaderMap {
    use reqwest::header::{ACCEPT, ACCEPT_LANGUAGE, HeaderName, HeaderValue};
    let mut headers = reqwest::header::HeaderMap::new();
    headers.insert(
        ACCEPT,
        HeaderValue::from_static(
            "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        ),
    );
    headers.insert(
        ACCEPT_LANGUAGE,
        HeaderValue::from_static("de-DE,de;q=0.9,en-US;q=0.8,en;q=0.7"),
    );
    for (name, value) in [
        ("upgrade-insecure-requests", "1"),
        ("sec-fetch-dest", "document"),
        ("sec-fetch-mode", "navigate"),
        ("sec-fetch-site", "none"),
        ("sec-fetch-user", "?1"),
    ] {
        headers.insert(
            HeaderName::from_static(name),
            HeaderValue::from_static(value),
        );
    }
    headers
}

/// Make pasted text a fetchable URL: trim it, add `https://` to a bare host and drop the
/// fragment, which carries no server-side meaning but is rejected by [`fetch_url`].
pub fn normalize_input_url(input: &str) -> String {
    let trimmed = input.trim();
    let with_scheme = if trimmed.contains("://") {
        trimmed.to_owned()
    } else {
        format!("https://{trimmed}")
    };
    match with_scheme.split_once('#') {
        Some((before, _)) => before.to_owned(),
        None => with_scheme,
    }
}

/// A redacted error from URL validation, DNS, HTTP, or response decoding.
#[derive(Clone, Copy, Debug, Error, Eq, PartialEq)]
pub enum FetchError {
    #[error("URL is invalid")]
    InvalidUrl,
    #[error("only HTTP and HTTPS URLs are accepted")]
    UnsupportedScheme,
    #[error("URL credentials are not accepted")]
    CredentialsNotAllowed,
    #[error("URL fragments are not accepted")]
    FragmentNotAllowed,
    #[error("URL has no host")]
    MissingHost,
    #[error("URL host is not publicly routable")]
    UnsafeAddress,
    #[error("host resolution failed")]
    ResolutionFailed,
    #[error("host did not resolve to any addresses")]
    NoAddresses,
    #[error("redirect target is invalid")]
    InvalidRedirect,
    #[error("too many redirects")]
    TooManyRedirects,
    #[error("response body exceeds the configured limit")]
    BodyTooLarge,
    #[error("request timed out")]
    Timeout,
    #[error("HTTP transport failed")]
    Transport,
    #[error("remote server returned HTTP status {0}")]
    HttpStatus(u16),
}

/// Bounded response data for downstream HTML, image, or PDF parsing.
///
/// The URL can contain user-provided query data. Do not log this struct or its `final_url`.
pub struct FetchResult {
    pub final_url: Url,
    pub status: StatusCode,
    pub content_type: Option<String>,
    pub body: Vec<u8>,
    pub title: Option<String>,
    /// Extracted plain text for HTML and plain-text responses; binary formats remain `None`.
    pub text: Option<String>,
}

/// Resolver abstraction used by `fetch_with` so security policy can be tested without DNS.
pub trait DnsResolver: Sync {
    fn resolve<'a>(
        &'a self,
        hostname: &'a str,
    ) -> impl Future<Output = Result<Vec<IpAddr>, FetchError>> + Send + 'a;
}

/// HTTP transport abstraction used by `fetch_with` for isolated policy tests.
pub trait HttpTransport: Sync {
    fn get<'a>(
        &'a self,
        url: &'a Url,
        approved_addresses: &'a [SocketAddr],
        max_body_bytes: usize,
        timeout: Duration,
    ) -> impl Future<Output = Result<TransportResponse, FetchError>> + Send + 'a;
}

/// Response metadata returned by a transport implementation.
pub struct TransportResponse {
    pub status: StatusCode,
    pub location: Option<String>,
    pub content_type: Option<String>,
    pub content_length: Option<u64>,
    pub body: Vec<u8>,
}

/// Resolve names using the system resolver.
pub struct SystemResolver;

impl DnsResolver for SystemResolver {
    async fn resolve<'a>(&'a self, hostname: &'a str) -> Result<Vec<IpAddr>, FetchError> {
        if let Ok(address) = hostname.parse::<IpAddr>() {
            return Ok(vec![address]);
        }
        let addresses = tokio::net::lookup_host((hostname, 0))
            .await
            .map_err(|_| FetchError::ResolutionFailed)?
            .map(|address| address.ip())
            .collect::<Vec<_>>();
        Ok(addresses)
    }
}

/// Reqwest transport with proxies and automatic redirects disabled.
pub struct ReqwestTransport;

impl HttpTransport for ReqwestTransport {
    async fn get<'a>(
        &'a self,
        url: &'a Url,
        approved_addresses: &'a [SocketAddr],
        max_body_bytes: usize,
        timeout: Duration,
    ) -> Result<TransportResponse, FetchError> {
        let host = match url.host().ok_or(FetchError::MissingHost)? {
            Host::Domain(host) => Some(host),
            Host::Ipv4(_) | Host::Ipv6(_) => None,
        };
        let mut builder = Client::builder()
            .no_proxy()
            .redirect(reqwest::redirect::Policy::none())
            .timeout(timeout)
            .connect_timeout(timeout)
            .referer(false)
            .user_agent(BROWSER_USER_AGENT)
            .default_headers(browser_headers());

        if let Some(host) = host {
            builder = builder.resolve_to_addrs(host, approved_addresses);
        }
        let client = builder.build().map_err(|_| FetchError::Transport)?;
        let mut response = client
            .get(url.clone())
            .header(ACCEPT_ENCODING, "identity")
            .send()
            .await
            .map_err(|_| FetchError::Transport)?;

        let status = response.status();
        let location = response
            .headers()
            .get(LOCATION)
            .map(|value| value.to_str().map(str::to_owned))
            .transpose()
            .map_err(|_| FetchError::InvalidRedirect)?;
        let content_type = response
            .headers()
            .get(CONTENT_TYPE)
            .map(|value| value.to_str().map(str::to_owned))
            .transpose()
            .map_err(|_| FetchError::Transport)?;
        let content_length = response
            .headers()
            .get(CONTENT_LENGTH)
            .and_then(|value| value.to_str().ok())
            .and_then(|value| value.parse::<u64>().ok());

        if is_redirect(status) && location.is_some() {
            return Ok(TransportResponse {
                status,
                location,
                content_type,
                content_length,
                body: Vec::new(),
            });
        }
        if content_length.is_some_and(|length| length > max_body_bytes as u64) {
            return Err(FetchError::BodyTooLarge);
        }

        let mut body = Vec::new();
        while let Some(chunk) = response.chunk().await.map_err(|_| FetchError::Transport)? {
            let new_len = body
                .len()
                .checked_add(chunk.len())
                .ok_or(FetchError::BodyTooLarge)?;
            if new_len > max_body_bytes {
                return Err(FetchError::BodyTooLarge);
            }
            body.extend_from_slice(&chunk);
        }
        Ok(TransportResponse {
            status,
            location,
            content_type,
            content_length,
            body,
        })
    }
}

/// Fetch a public HTTP or HTTPS URL using the production resolver and transport.
pub async fn fetch_url(input: &str) -> Result<FetchResult, FetchError> {
    fetch_with(input, &SystemResolver, &ReqwestTransport).await
}

/// Fetch through injected DNS and HTTP implementations for isolated tests or trusted adapters.
///
/// Every resolved address is validated and passed to the transport for connection pinning. The
/// injected transport is trusted code and must honor the approved address set.
pub async fn fetch_with<R, T>(
    input: &str,
    resolver: &R,
    transport: &T,
) -> Result<FetchResult, FetchError>
where
    R: DnsResolver,
    T: HttpTransport,
{
    tokio::time::timeout(TOTAL_TIMEOUT, fetch_inner(input, resolver, transport))
        .await
        .map_err(|_| FetchError::Timeout)?
}

async fn fetch_inner<R, T>(
    input: &str,
    resolver: &R,
    transport: &T,
) -> Result<FetchResult, FetchError>
where
    R: DnsResolver,
    T: HttpTransport,
{
    let mut current_url = validate_url(input)?;
    for hop in 0..=MAX_REDIRECTS {
        let host = current_url.host().ok_or(FetchError::MissingHost)?;
        let addresses = match host {
            Host::Ipv4(address) => vec![IpAddr::V4(address)],
            Host::Ipv6(address) => vec![IpAddr::V6(address)],
            Host::Domain(hostname) => resolver.resolve(hostname).await?,
        };
        if addresses.is_empty() {
            return Err(FetchError::NoAddresses);
        }
        if addresses.iter().any(|address| !is_public_address(*address)) {
            return Err(FetchError::UnsafeAddress);
        }
        let approved_addresses: Vec<SocketAddr> = addresses
            .into_iter()
            .map(|address| SocketAddr::new(address, 0))
            .collect();

        let response = transport
            .get(
                &current_url,
                &approved_addresses,
                MAX_BODY_BYTES,
                TOTAL_TIMEOUT,
            )
            .await?;
        if is_redirect(response.status) {
            let Some(location) = response.location else {
                return Err(FetchError::HttpStatus(response.status.as_u16()));
            };
            if hop == MAX_REDIRECTS {
                return Err(FetchError::TooManyRedirects);
            }
            current_url = current_url
                .join(&location)
                .map_err(|_| FetchError::InvalidRedirect)?;
            validate_url(current_url.as_str()).map_err(|_| FetchError::InvalidRedirect)?;
            continue;
        }
        if !response.status.is_success() {
            return Err(FetchError::HttpStatus(response.status.as_u16()));
        }
        if response.body.len() > MAX_BODY_BYTES
            || response
                .content_length
                .is_some_and(|length| length > MAX_BODY_BYTES as u64)
        {
            return Err(FetchError::BodyTooLarge);
        }
        let (title, text) = extract_text(response.content_type.as_deref(), &response.body);
        return Ok(FetchResult {
            final_url: current_url,
            status: response.status,
            content_type: response.content_type,
            body: response.body,
            title,
            text,
        });
    }
    Err(FetchError::TooManyRedirects)
}

fn validate_url(input: &str) -> Result<Url, FetchError> {
    let url = Url::parse(input).map_err(|_| FetchError::InvalidUrl)?;
    if !matches!(url.scheme(), "http" | "https") {
        return Err(FetchError::UnsupportedScheme);
    }
    let authority_has_userinfo = input
        .split_once("://")
        .and_then(|(_, remainder)| remainder.split(['/', '?', '#']).next())
        .is_some_and(|authority| authority.contains('@'));
    if authority_has_userinfo || !url.username().is_empty() || url.password().is_some() {
        return Err(FetchError::CredentialsNotAllowed);
    }
    if url.fragment().is_some() {
        return Err(FetchError::FragmentNotAllowed);
    }
    match url.host().ok_or(FetchError::MissingHost)? {
        Host::Ipv4(address) if !is_public_address(IpAddr::V4(address)) => {
            return Err(FetchError::UnsafeAddress);
        }
        Host::Ipv6(address) if !is_public_address(IpAddr::V6(address)) => {
            return Err(FetchError::UnsafeAddress);
        }
        Host::Domain(_) | Host::Ipv4(_) | Host::Ipv6(_) => {}
    }
    Ok(url)
}

fn is_redirect(status: StatusCode) -> bool {
    matches!(status.as_u16(), 301 | 302 | 303 | 307 | 308)
}

/// Return whether an address is globally routable for an outbound import request.
pub fn is_public_address(address: IpAddr) -> bool {
    match address {
        IpAddr::V4(address) => is_public_v4(address),
        IpAddr::V6(address) => is_public_v6(address),
    }
}

fn is_public_v4(address: Ipv4Addr) -> bool {
    const BLOCKED: &[(u32, u8)] = &[
        (0x0000_0000, 8),  // This network
        (0x0a00_0000, 8),  // Private use
        (0x6440_0000, 10), // Shared address space
        (0x7f00_0000, 8),  // Loopback
        (0xa9fe_0000, 16), // Link-local and cloud metadata
        (0xac10_0000, 12), // Private use
        (0xc000_0000, 24), // IETF protocol assignments
        (0xc000_0200, 24), // Documentation
        (0xc058_6300, 24), // 6to4 relay anycast
        (0xc0a8_0000, 16), // Private use
        (0xc612_0000, 15), // Benchmarking
        (0xc633_6400, 24), // Documentation
        (0xcb00_7100, 24), // Documentation
        (0xe000_0000, 4),  // Multicast
        (0xf000_0000, 4),  // Reserved and limited broadcast
    ];
    let value = u32::from(address);
    !BLOCKED
        .iter()
        .any(|(network, prefix)| in_v4_subnet(value, *network, *prefix))
}

fn in_v4_subnet(address: u32, network: u32, prefix: u8) -> bool {
    let mask = u32::MAX << (32 - prefix);
    address & mask == network & mask
}

fn is_public_v6(address: Ipv6Addr) -> bool {
    if let Some(mapped) = address.to_ipv4_mapped() {
        return is_public_v4(mapped);
    }
    let segments = address.segments();
    let is_global_unicast = segments[0] & 0xe000 == 0x2000;
    let is_special_2001 = segments[0] == 0x2001 && segments[1] <= 0x01ff;
    let is_documentation = (segments[0] == 0x2001 && segments[1] == 0x0db8)
        || (segments[0] == 0x3fff && segments[1] & 0xf000 == 0);
    let is_6to4 = segments[0] == 0x2002;
    is_global_unicast && !is_special_2001 && !is_documentation && !is_6to4
}

fn extract_text(content_type: Option<&str>, body: &[u8]) -> (Option<String>, Option<String>) {
    let Some(content_type) = content_type else {
        return (None, None);
    };
    let media_type = content_type
        .split(';')
        .next()
        .unwrap_or_default()
        .trim()
        .to_ascii_lowercase();
    if media_type == "text/plain" {
        let text = crate::url_import::charset::decode_html(body, Some(content_type));
        return (None, Some(text.trim().to_owned()));
    }
    if media_type != "text/html" && media_type != "application/xhtml+xml" {
        return (None, None);
    }
    let html = crate::url_import::charset::decode_html(body, Some(content_type));
    let (title, html_without_title) = remove_title(&html);
    let sanitized = HtmlSanitizer::default()
        .clean(&html_without_title)
        .to_string();
    let text = html_to_text(&sanitized);
    (title, (!text.is_empty()).then_some(text))
}

fn remove_title(html: &str) -> (Option<String>, String) {
    let lower = html.to_ascii_lowercase();
    let mut search_from = 0;
    while let Some(relative_start) = lower[search_from..].find("<title") {
        let start = search_from + relative_start;
        let boundary = lower.as_bytes().get(start + 6).copied();
        if !boundary.is_some_and(|byte| byte.is_ascii_whitespace() || byte == b'>') {
            search_from = start + 6;
            continue;
        }
        let Some(open_end) = find_tag_end(html, start) else {
            return (None, html.to_owned());
        };
        let content_start = open_end + 1;
        let Some(close) = lower[content_start..]
            .find("</title")
            .map(|offset| offset + content_start)
        else {
            return (None, html.to_owned());
        };
        let Some(close_end) = find_tag_end(html, close) else {
            return (None, html.to_owned());
        };
        let title = html_to_text(&html[content_start..close]);
        let mut without_title = String::with_capacity(html.len());
        without_title.push_str(&html[..start]);
        without_title.push(' ');
        without_title.push_str(&html[close_end + 1..]);
        return ((!title.is_empty()).then_some(title), without_title);
    }
    (None, html.to_owned())
}

fn find_tag_end(html: &str, start: usize) -> Option<usize> {
    let mut quote = None;
    for (offset, byte) in html.as_bytes().iter().enumerate().skip(start) {
        match (quote, *byte) {
            (Some(current), value) if current == value => quote = None,
            (None, b'\'' | b'"') => quote = Some(*byte),
            (None, b'>') => return Some(offset),
            _ => {}
        }
    }
    None
}

fn html_to_text(html: &str) -> String {
    let mut text = String::with_capacity(html.len().min(MAX_BODY_BYTES));
    let mut inside_tag = false;
    for character in html.chars() {
        match character {
            '<' if !inside_tag => {
                inside_tag = true;
                text.push(' ');
            }
            '>' if inside_tag => {
                inside_tag = false;
                text.push(' ');
            }
            _ if !inside_tag => text.push(character),
            _ => {}
        }
    }
    normalize_whitespace(&decode_entities(&text))
}

fn decode_entities(input: &str) -> String {
    let mut output = String::with_capacity(input.len());
    let mut remaining = input;
    while let Some(start) = remaining.find('&') {
        output.push_str(&remaining[..start]);
        remaining = &remaining[start + 1..];
        let Some(end) = remaining.find(';') else {
            output.push('&');
            continue;
        };
        let entity = &remaining[..end];
        let decoded = entity
            .strip_prefix('#')
            .and_then(parse_numeric_entity)
            .or(match entity {
                "amp" => Some('&'),
                "lt" => Some('<'),
                "gt" => Some('>'),
                "quot" => Some('"'),
                "apos" => Some('\''),
                "nbsp" => Some(' '),
                _ => None,
            });
        if let Some(decoded) = decoded {
            output.push(decoded);
        } else {
            output.push('&');
            output.push_str(entity);
            output.push(';');
        }
        remaining = &remaining[end + 1..];
    }
    output.push_str(remaining);
    output
}

fn parse_numeric_entity(entity: &str) -> Option<char> {
    let (radix, digits) = if let Some(hex) = entity
        .strip_prefix('x')
        .or_else(|| entity.strip_prefix('X'))
    {
        (16, hex)
    } else {
        (10, entity)
    };
    u32::from_str_radix(digits, radix)
        .ok()
        .and_then(char::from_u32)
}

fn normalize_whitespace(input: &str) -> String {
    input.split_whitespace().collect::<Vec<_>>().join(" ")
}
