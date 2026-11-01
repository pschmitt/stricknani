use std::{
    net::IpAddr,
    sync::{Arc, Mutex},
};

use stricknani_server::{
    imports::{DnsResolver, FetchError},
    wayback::{
        WaybackClient, WaybackError, WaybackFuture, WaybackOutcome, WaybackTransport,
        validate_source_url,
    },
};
use url::Url;

struct FixtureResolver {
    addresses: Vec<IpAddr>,
}

impl DnsResolver for FixtureResolver {
    async fn resolve<'a>(&'a self, _hostname: &'a str) -> Result<Vec<IpAddr>, FetchError> {
        Ok(self.addresses.clone())
    }
}

struct FixtureTransport {
    save_result: Result<Option<Url>, WaybackError>,
    availability_result: Result<Option<Url>, WaybackError>,
    calls: Mutex<Vec<(&'static str, String)>>,
}

impl FixtureTransport {
    fn new(
        save_result: Result<Option<Url>, WaybackError>,
        availability_result: Result<Option<Url>, WaybackError>,
    ) -> Self {
        Self {
            save_result,
            availability_result,
            calls: Mutex::new(Vec::new()),
        }
    }
}

impl WaybackTransport for FixtureTransport {
    fn save<'a>(
        &'a self,
        source_url: &'a Url,
    ) -> WaybackFuture<'a, Result<Option<Url>, WaybackError>> {
        self.calls
            .lock()
            .expect("fixture call list should not be poisoned")
            .push(("save", source_url.as_str().to_owned()));
        let result = self.save_result.clone();
        Box::pin(async move { result })
    }

    fn availability<'a>(
        &'a self,
        source_url: &'a Url,
    ) -> WaybackFuture<'a, Result<Option<Url>, WaybackError>> {
        self.calls
            .lock()
            .expect("fixture call list should not be poisoned")
            .push(("availability", source_url.as_str().to_owned()));
        let result = self.availability_result.clone();
        Box::pin(async move { result })
    }
}

fn resolver() -> Arc<FixtureResolver> {
    Arc::new(FixtureResolver {
        addresses: vec!["8.8.8.8".parse().expect("fixture IP should parse")],
    })
}

fn client(
    enabled: bool,
    transport: Arc<FixtureTransport>,
) -> WaybackClient<FixtureTransport, FixtureResolver> {
    WaybackClient::with_transport_and_resolver(enabled, transport, resolver())
}

fn archived_url() -> Url {
    Url::parse("https://web.archive.org/web/20250101000000/https://ravelry.com/pattern/1")
        .expect("fixture archive URL should parse")
}

#[tokio::test]
async fn disabled_client_never_calls_transport() {
    let transport = Arc::new(FixtureTransport::new(Ok(None), Ok(None)));
    let client = client(false, transport.clone());

    assert_eq!(
        client.archive("https://ravelry.com/pattern/1").await,
        WaybackOutcome::Failure {
            error: WaybackError::Disabled
        }
    );
    assert!(
        transport
            .calls
            .lock()
            .expect("fixture call list should not be poisoned")
            .is_empty()
    );
}

#[tokio::test]
async fn accepted_save_without_snapshot_is_pending() {
    let transport = Arc::new(FixtureTransport::new(Ok(None), Ok(None)));
    let client = client(true, transport.clone());

    assert_eq!(
        client.archive("https://ravelry.com/pattern/1").await,
        WaybackOutcome::Pending
    );
    assert_eq!(
        transport
            .calls
            .lock()
            .expect("fixture call list should not be poisoned")
            .iter()
            .map(|(kind, _)| *kind)
            .collect::<Vec<_>>(),
        vec!["save", "availability"]
    );
}

#[tokio::test]
async fn availability_fallback_returns_a_trusted_snapshot() {
    let transport = Arc::new(FixtureTransport::new(
        Err(WaybackError::TransportFailed),
        Ok(Some(archived_url())),
    ));
    let client = client(true, transport);

    assert_eq!(
        client.archive("https://ravelry.com/pattern/1").await,
        WaybackOutcome::Success {
            snapshot_url: archived_url()
        }
    );
}

#[tokio::test]
async fn source_validation_rejects_private_and_reserved_hosts_before_transport() {
    let private_resolver = FixtureResolver {
        addresses: vec!["10.0.0.4".parse().expect("fixture IP should parse")],
    };
    assert_eq!(
        validate_source_url("https://ravelry.com/pattern/1", &private_resolver).await,
        Err(WaybackError::UnsafeSource)
    );

    for source in [
        "http://127.0.0.1/admin",
        "https://user@ravelry.com/pattern/1",
        "https://ravelry.com:8443/pattern/1",
        "https://ravelry.com/pattern/1#private",
        "https://localhost/path",
        "https://foo.example.com/path",
        "https://example.test/path",
    ] {
        assert!(
            validate_source_url(source, &resolver()).await.is_err(),
            "source should be rejected: {source:?}"
        );
    }
}

#[tokio::test]
async fn rejected_source_is_never_disclosed_to_archive_transport() {
    let transport = Arc::new(FixtureTransport::new(Ok(None), Ok(None)));
    let client = client(true, transport.clone());

    assert_eq!(
        client
            .archive("http://169.254.169.254/latest/meta-data")
            .await,
        WaybackOutcome::Failure {
            error: WaybackError::UnsafeSource
        }
    );
    assert!(
        transport
            .calls
            .lock()
            .expect("fixture call list should not be poisoned")
            .is_empty()
    );
}

#[tokio::test]
async fn untrusted_archive_urls_are_rejected() {
    let external_url =
        Url::parse("https://attacker.example/web/snapshot").expect("fixture URL should parse");
    let transport = Arc::new(FixtureTransport::new(Ok(Some(external_url)), Ok(None)));
    let client = client(true, transport);

    assert_eq!(
        client.archive("https://ravelry.com/pattern/1").await,
        WaybackOutcome::Failure {
            error: WaybackError::InvalidArchiveResponse
        }
    );
}
