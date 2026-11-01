use std::collections::{HashMap, VecDeque};
use std::net::{IpAddr, SocketAddr};
use std::sync::Mutex;
use std::time::Duration;

use reqwest::StatusCode;
use stricknani_server::imports::{
    DnsResolver, FetchError, FetchResult, HttpTransport, TransportResponse, fetch_with,
    is_public_address,
};
use url::Url;

const BODY_LIMIT: usize = 5 * 1024 * 1024;

struct StaticResolver {
    answers: HashMap<String, Vec<IpAddr>>,
    lookups: Mutex<Vec<String>>,
}

impl StaticResolver {
    fn new(answers: &[(&str, &[&str])]) -> Self {
        Self {
            answers: answers
                .iter()
                .map(|(host, addresses)| {
                    (
                        (*host).to_owned(),
                        addresses
                            .iter()
                            .map(|address| address.parse().expect("test IP is valid"))
                            .collect(),
                    )
                })
                .collect(),
            lookups: Mutex::new(Vec::new()),
        }
    }
}

impl DnsResolver for StaticResolver {
    async fn resolve(&self, hostname: &str) -> Result<Vec<IpAddr>, FetchError> {
        self.lookups
            .lock()
            .expect("lookup list lock is healthy")
            .push(hostname.to_owned());
        self.answers
            .get(hostname)
            .cloned()
            .ok_or(FetchError::ResolutionFailed)
    }
}

#[derive(Clone)]
struct RecordedRequest {
    url: Url,
    addresses: Vec<SocketAddr>,
    max_body_bytes: usize,
    timeout: Duration,
}

struct FixtureTransport {
    responses: Mutex<VecDeque<TransportResponse>>,
    requests: Mutex<Vec<RecordedRequest>>,
}

impl FixtureTransport {
    fn new(responses: impl IntoIterator<Item = TransportResponse>) -> Self {
        Self {
            responses: Mutex::new(responses.into_iter().collect()),
            requests: Mutex::new(Vec::new()),
        }
    }
}

impl HttpTransport for FixtureTransport {
    async fn get(
        &self,
        url: &Url,
        approved_addresses: &[SocketAddr],
        max_body_bytes: usize,
        timeout: Duration,
    ) -> Result<TransportResponse, FetchError> {
        self.requests
            .lock()
            .expect("request list lock is healthy")
            .push(RecordedRequest {
                url: url.clone(),
                addresses: approved_addresses.to_vec(),
                max_body_bytes,
                timeout,
            });
        self.responses
            .lock()
            .expect("response queue lock is healthy")
            .pop_front()
            .ok_or(FetchError::Transport)
    }
}

fn response(
    status: StatusCode,
    location: Option<&str>,
    content_type: Option<&str>,
    body: &[u8],
) -> TransportResponse {
    TransportResponse {
        status,
        location: location.map(str::to_owned),
        content_type: content_type.map(str::to_owned),
        content_length: Some(body.len() as u64),
        body: body.to_vec(),
    }
}

fn empty_response() -> TransportResponse {
    response(StatusCode::OK, None, None, b"")
}

#[tokio::test]
async fn rejects_non_web_urls_credentials_fragments_and_internal_ip_literals() {
    let resolver = StaticResolver::new(&[]);
    let transport = FixtureTransport::new([]);

    assert_eq!(
        fetch_with("ftp://example.org/file", &resolver, &transport)
            .await
            .err(),
        Some(FetchError::UnsupportedScheme)
    );
    assert_eq!(
        fetch_with("https://name:secret@example.org/", &resolver, &transport)
            .await
            .err(),
        Some(FetchError::CredentialsNotAllowed)
    );
    assert_eq!(
        fetch_with("https://example.org/path#fragment", &resolver, &transport)
            .await
            .err(),
        Some(FetchError::FragmentNotAllowed)
    );
    assert_eq!(
        fetch_with(
            "http://169.254.169.254/latest/meta-data",
            &resolver,
            &transport
        )
        .await
        .err(),
        Some(FetchError::UnsafeAddress)
    );
    assert!(resolver.lookups.lock().unwrap().is_empty());
    assert!(transport.requests.lock().unwrap().is_empty());
}

#[test]
fn blocks_private_reserved_link_local_and_mapped_ranges() {
    for address in [
        "0.1.2.3",
        "10.2.3.4",
        "100.64.0.1",
        "127.0.0.1",
        "169.254.169.254",
        "172.16.0.1",
        "192.0.2.1",
        "192.168.1.1",
        "198.18.0.1",
        "198.51.100.1",
        "203.0.113.1",
        "224.0.0.1",
        "240.0.0.1",
        "::",
        "::1",
        "::ffff:127.0.0.1",
        "::ffff:169.254.169.254",
        "64:ff9b:1::1",
        "100::1",
        "2001:db8::1",
        "2002::1",
        "3fff::1",
        "fd00::1",
        "fe80::1",
        "ff02::1",
    ] {
        let ip = address.parse::<IpAddr>().expect("test IP is valid");
        assert!(!is_public_address(ip), "{address}");
    }
    for address in ["8.8.8.8", "93.184.216.34", "2606:4700:4700::1111"] {
        let ip = address.parse::<IpAddr>().expect("test IP is valid");
        assert!(is_public_address(ip), "{address}");
    }
}

#[tokio::test]
async fn rejects_mixed_public_and_private_dns_answers_before_transport() {
    let resolver = StaticResolver::new(&[("mixed.example", &["93.184.216.34", "10.0.0.1"])]);
    let transport = FixtureTransport::new([empty_response()]);

    assert_eq!(
        fetch_with("https://mixed.example/", &resolver, &transport)
            .await
            .err(),
        Some(FetchError::UnsafeAddress)
    );
    assert!(transport.requests.lock().unwrap().is_empty());
}

#[tokio::test]
async fn revalidates_redirect_targets_before_requesting_and_pins_each_public_hop() {
    let resolver = StaticResolver::new(&[
        ("origin.example", &["93.184.216.34"]),
        ("internal.example", &["127.0.0.1"]),
        ("cdn.example", &["1.1.1.1", "2606:4700:4700::1111"]),
    ]);
    let blocked_transport = FixtureTransport::new([response(
        StatusCode::FOUND,
        Some("http://internal.example/private?token=do-not-log"),
        None,
        b"",
    )]);
    let blocked = fetch_with(
        "https://origin.example/start",
        &resolver,
        &blocked_transport,
    )
    .await;
    assert_eq!(blocked.err(), Some(FetchError::UnsafeAddress));
    assert_eq!(blocked_transport.requests.lock().unwrap().len(), 1);
    assert_eq!(
        resolver.lookups.lock().unwrap().as_slice(),
        &["origin.example".to_owned(), "internal.example".to_owned()]
    );

    let resolver = StaticResolver::new(&[
        ("origin.example", &["93.184.216.34"]),
        ("cdn.example", &["1.1.1.1", "2606:4700:4700::1111"]),
    ]);
    let transport = FixtureTransport::new([
        response(
            StatusCode::TEMPORARY_REDIRECT,
            Some("https://cdn.example/pattern"),
            None,
            b"",
        ),
        response(StatusCode::OK, None, Some("text/plain"), b"pattern text"),
    ]);
    let result = fetch_with("https://origin.example/start", &resolver, &transport)
        .await
        .expect("validated public redirect succeeds");
    assert_eq!(result.text.as_deref(), Some("pattern text"));
    assert_eq!(result.final_url.host_str(), Some("cdn.example"));
    let requests = transport.requests.lock().unwrap();
    assert_eq!(requests.len(), 2);
    assert_eq!(requests[0].url.host_str(), Some("origin.example"));
    assert_eq!(requests[1].url.host_str(), Some("cdn.example"));
    assert_eq!(
        requests[0].addresses,
        ["93.184.216.34:0".parse::<SocketAddr>().unwrap()]
    );
    assert_eq!(
        requests[1].addresses,
        [
            "1.1.1.1:0".parse::<SocketAddr>().unwrap(),
            "[2606:4700:4700::1111]:0".parse::<SocketAddr>().unwrap()
        ]
    );
    assert_eq!(requests[0].max_body_bytes, BODY_LIMIT);
    assert!(requests[0].timeout <= Duration::from_secs(10));
    assert_eq!(
        resolver.lookups.lock().unwrap().as_slice(),
        &["origin.example".to_owned(), "cdn.example".to_owned()]
    );
}

#[tokio::test]
async fn applies_redirect_and_body_limits_without_leaking_query_values() {
    let resolver = StaticResolver::new(&[("loop.example", &["93.184.216.34"])]);
    let responses =
        (0..=5).map(|_| response(StatusCode::FOUND, Some("/next?private=secret"), None, b""));
    let transport = FixtureTransport::new(responses);
    let error = fetch_with(
        "https://loop.example/?api_key=private",
        &resolver,
        &transport,
    )
    .await
    .err()
    .expect("redirect chain is bounded");
    assert_eq!(error, FetchError::TooManyRedirects);
    assert!(!error.to_string().contains("private"));
    assert_eq!(transport.requests.lock().unwrap().len(), 6);

    let resolver = StaticResolver::new(&[("large.example", &["93.184.216.34"])]);
    let oversized = vec![b'x'; BODY_LIMIT + 1];
    let transport = FixtureTransport::new([response(
        StatusCode::OK,
        None,
        Some("application/octet-stream"),
        &oversized,
    )]);
    assert_eq!(
        fetch_with("https://large.example/file", &resolver, &transport)
            .await
            .err(),
        Some(FetchError::BodyTooLarge)
    );
}

#[tokio::test]
async fn extracts_minimal_html_title_and_visible_text_but_leaves_binary_for_callers() {
    let resolver = StaticResolver::new(&[("html.example", &["93.184.216.34"])]);
    let transport = FixtureTransport::new([response(
        StatusCode::OK,
        None,
        Some("text/html; charset=utf-8"),
        br#"<html><head><title>Knits &amp; more</title></head><body><p>Hello <b>world</b></p><script>hidden text</script></body></html>"#,
    )]);
    let result: FetchResult = fetch_with("https://html.example/", &resolver, &transport)
        .await
        .expect("bounded HTML response is returned");
    assert_eq!(result.title.as_deref(), Some("Knits & more"));
    assert_eq!(result.text.as_deref(), Some("Hello world"));
    assert!(!result.text.as_deref().unwrap().contains("hidden"));

    let resolver = StaticResolver::new(&[("pdf.example", &["93.184.216.34"])]);
    let transport = FixtureTransport::new([response(
        StatusCode::OK,
        None,
        Some("application/pdf"),
        b"%PDF-fixture",
    )]);
    let result = fetch_with("https://pdf.example/file.pdf", &resolver, &transport)
        .await
        .expect("binary response remains available for downstream parsing");
    assert_eq!(result.body, b"%PDF-fixture");
    assert_eq!(result.title, None);
    assert_eq!(result.text, None);
}
