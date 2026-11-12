//! Which client made a request, recorded on audit events for administrators.
//!
//! The Android app and the web app send `X-Stricknani-Client`; everything else
//! is classified from the `User-Agent`. Both headers are untrusted input: they
//! are length-limited, restricted to printable ASCII where they are parsed, and
//! never trusted for anything but a label. No IP address is kept.
//!
//! Header grammar: `<kind>/<version>[ (<params>)]`, for example
//! `android/1.7.1 (code 123; debug)` or `web/1.7.1 (build abc123)`.

use std::future::Future;

use axum::{
    extract::Request,
    http::{HeaderMap, header},
    middleware::Next,
    response::Response,
};

pub(crate) const CLIENT_HEADER: &str = "x-stricknani-client";

const MAX_HEADER_BYTES: usize = 128;
const MAX_VERSION_CHARS: usize = 32;
const MAX_PARAMS_CHARS: usize = 48;
const MAX_USER_AGENT_CHARS: usize = 256;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum ClientKind {
    Web,
    Android,
    Api,
    /// No request caused the event (the trash purge, imports, the CLI).
    System,
}

impl ClientKind {
    pub(crate) const fn as_str(self) -> &'static str {
        match self {
            Self::Web => "web",
            Self::Android => "android",
            Self::Api => "api",
            Self::System => "system",
        }
    }

    pub(crate) fn parse(value: &str) -> Option<Self> {
        match value {
            "web" => Some(Self::Web),
            "android" => Some(Self::Android),
            "api" => Some(Self::Api),
            "system" => Some(Self::System),
            _ => None,
        }
    }
}

/// A compact, normalized description of the client of one request.
#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) struct ClientInfo {
    pub(crate) kind: ClientKind,
    /// The app version as the client reported it, with its build parameters.
    pub(crate) version: Option<String>,
    /// A short display label such as `Firefox 159 · Linux`.
    pub(crate) label: Option<String>,
    /// The raw `User-Agent`, printable characters only, at most 256 characters.
    pub(crate) user_agent: Option<String>,
}

impl ClientInfo {
    pub(crate) const fn system() -> Self {
        Self {
            kind: ClientKind::System,
            version: None,
            label: None,
            user_agent: None,
        }
    }

    pub(crate) fn from_headers(headers: &HeaderMap) -> Self {
        let user_agent = headers
            .get(header::USER_AGENT)
            .map(|value| sanitize_user_agent(value.as_bytes()))
            .filter(|value| !value.is_empty());
        let declared = headers
            .get(CLIENT_HEADER)
            .and_then(|value| parse_client_header(value.as_bytes()));
        let detected = user_agent.as_deref().map(detect_user_agent);
        let (kind, version, label) = match (declared, detected) {
            (Some(declared), detected) => {
                let label = match declared.kind {
                    ClientKind::Android => Some(android_label(&declared)),
                    ClientKind::Web => detected
                        .and_then(|detected| detected.label)
                        .or_else(|| Some(format!("Web {}", declared.version))),
                    _ => detected
                        .and_then(|detected| detected.label)
                        .or_else(|| Some(format!("API {}", declared.version))),
                };
                (declared.kind, Some(declared.full_version()), label)
            }
            (None, Some(detected)) => (detected.kind, None, detected.label),
            (None, None) => (ClientKind::Api, None, None),
        };
        Self {
            kind,
            version,
            label,
            user_agent,
        }
    }
}

struct Declared {
    kind: ClientKind,
    version: String,
    params: Option<String>,
}

impl Declared {
    fn full_version(&self) -> String {
        match &self.params {
            Some(params) => format!("{} ({params})", self.version),
            None => self.version.clone(),
        }
    }
}

fn android_label(declared: &Declared) -> String {
    let flavour = declared
        .params
        .as_deref()
        .and_then(|params| {
            params
                .split(';')
                .map(str::trim)
                .find(|token| matches!(*token, "debug" | "release"))
        })
        .map(|token| format!(" ({token})"))
        .unwrap_or_default();
    format!("Android {}{flavour}", declared.version)
}

fn parse_client_header(bytes: &[u8]) -> Option<Declared> {
    if bytes.is_empty() || bytes.len() > MAX_HEADER_BYTES {
        return None;
    }
    if !bytes.iter().all(|byte| (0x20..=0x7e).contains(byte)) {
        return None;
    }
    let value = std::str::from_utf8(bytes).ok()?.trim();
    let (kind, rest) = value.split_once('/')?;
    let kind = match ClientKind::parse(kind)? {
        ClientKind::System => return None,
        kind => kind,
    };
    let (version, params) = match rest.split_once(' ') {
        Some((version, params)) => (version, Some(params.trim())),
        None => (rest, None),
    };
    let version_ok = !version.is_empty()
        && version.chars().count() <= MAX_VERSION_CHARS
        && version
            .chars()
            .all(|c| c.is_ascii_alphanumeric() || matches!(c, '.' | '_' | '+' | '-'));
    if !version_ok {
        return None;
    }
    let params = match params {
        None | Some("") => None,
        Some(params) => {
            let inner = params.strip_prefix('(')?.strip_suffix(')')?;
            let inner_ok = !inner.is_empty()
                && inner.chars().count() <= MAX_PARAMS_CHARS
                && inner.chars().all(|c| {
                    c.is_ascii_alphanumeric()
                        || matches!(c, '.' | '_' | '+' | '-' | ';' | ':' | ' ')
                });
            if !inner_ok {
                return None;
            }
            Some(inner.to_owned())
        }
    };
    Some(Declared {
        kind,
        version: version.to_owned(),
        params,
    })
}

fn sanitize_user_agent(bytes: &[u8]) -> String {
    String::from_utf8_lossy(bytes)
        .chars()
        .filter(|c| !c.is_control() && *c != '\u{fffd}')
        .take(MAX_USER_AGENT_CHARS)
        .collect::<String>()
        .trim()
        .to_owned()
}

struct Detected {
    kind: ClientKind,
    label: Option<String>,
}

/// A conservative classifier: a handful of browser families, `okhttp` and the
/// first `product/version` token. Anything else is a plain API client.
fn detect_user_agent(user_agent: &str) -> Detected {
    if let Some(version) = product_version(user_agent, "okhttp/") {
        return Detected {
            kind: ClientKind::Android,
            label: Some(format!("Android (okhttp {version})")),
        };
    }
    if let Some((browser, version)) = browser(user_agent) {
        let label = match os(user_agent) {
            Some(os) => format!("{browser} {version} \u{b7} {os}"),
            None => format!("{browser} {version}"),
        };
        return Detected {
            kind: ClientKind::Web,
            label: Some(label),
        };
    }
    let label = first_product(user_agent).map(|(name, version)| format!("{name} {version}"));
    Detected {
        kind: ClientKind::Api,
        label,
    }
}

/// The major version after `marker`, for example `Firefox/159.0` gives `159`.
fn product_version(user_agent: &str, marker: &str) -> Option<String> {
    let start = user_agent.find(marker)? + marker.len();
    let version: String = user_agent[start..]
        .chars()
        .take_while(|c| c.is_ascii_digit() || *c == '.')
        .take(12)
        .collect();
    (!version.is_empty()).then_some(version)
}

fn major(version: &str) -> &str {
    version.split('.').next().unwrap_or(version)
}

fn browser(user_agent: &str) -> Option<(&'static str, String)> {
    const MARKERS: [(&str, &str); 8] = [
        ("Edg/", "Edge"),
        ("EdgA/", "Edge"),
        ("OPR/", "Opera"),
        ("Firefox/", "Firefox"),
        ("FxiOS/", "Firefox"),
        ("Chrome/", "Chrome"),
        ("CriOS/", "Chrome"),
        ("Version/", "Safari"),
    ];
    for (marker, name) in MARKERS {
        // `Version/` only names Safari when `Safari/` is present as well.
        if marker == "Version/" && !user_agent.contains("Safari/") {
            continue;
        }
        if let Some(version) = product_version(user_agent, marker) {
            return Some((name, major(&version).to_owned()));
        }
    }
    None
}

fn os(user_agent: &str) -> Option<&'static str> {
    // Order matters: Android UAs also say Linux, iOS UAs also say Mac OS X.
    const MARKERS: [(&str, &str); 7] = [
        ("Android", "Android"),
        ("iPhone", "iOS"),
        ("iPad", "iOS"),
        ("Windows", "Windows"),
        ("Mac OS X", "macOS"),
        ("CrOS", "ChromeOS"),
        ("Linux", "Linux"),
    ];
    MARKERS
        .iter()
        .find(|(marker, _)| user_agent.contains(marker))
        .map(|(_, name)| *name)
}

/// The first `name/version` token with a short name and a numeric version.
fn first_product(user_agent: &str) -> Option<(String, String)> {
    let token = user_agent.split_whitespace().next()?;
    let (name, version) = token.split_once('/')?;
    let name_ok = !name.is_empty()
        && name.len() <= 24
        && name
            .chars()
            .all(|c| c.is_ascii_alphanumeric() || matches!(c, '.' | '_' | '-'));
    let version: String = version
        .chars()
        .take_while(|c| c.is_ascii_digit() || *c == '.')
        .take(12)
        .collect();
    (name_ok && !version.is_empty()).then(|| (name.to_owned(), version))
}

tokio::task_local! {
    static CLIENT: ClientInfo;
}

/// The client of the request being served; `System` outside any request.
pub(crate) fn current() -> ClientInfo {
    CLIENT
        .try_with(Clone::clone)
        .unwrap_or_else(|_| ClientInfo::system())
}

/// Run `future` with `client` as the current request's client.
pub(crate) async fn scope<F: Future>(client: ClientInfo, future: F) -> F::Output {
    CLIENT.scope(client, future).await
}

/// Middleware: remember the client of every request for the audit log.
pub(crate) async fn record_client(request: Request, next: Next) -> Response {
    let client = ClientInfo::from_headers(request.headers());
    scope(client, next.run(request)).await
}

#[cfg(test)]
mod tests {
    use axum::http::{HeaderMap, HeaderValue, header};

    use super::{CLIENT_HEADER, ClientInfo, ClientKind};

    fn headers(client: Option<&[u8]>, user_agent: Option<&[u8]>) -> HeaderMap {
        let mut map = HeaderMap::new();
        if let Some(value) = client {
            map.insert(CLIENT_HEADER, HeaderValue::from_bytes(value).unwrap());
        }
        if let Some(value) = user_agent {
            map.insert(header::USER_AGENT, HeaderValue::from_bytes(value).unwrap());
        }
        map
    }

    const FIREFOX: &[u8] =
        b"Mozilla/5.0 (X11; Linux x86_64; rv:159.0) Gecko/20100101 Firefox/159.0";

    #[test]
    fn android_header_wins_over_okhttp_user_agent() {
        let info = ClientInfo::from_headers(&headers(
            Some(b"android/1.7.1 (code 123; debug)"),
            Some(b"okhttp/4.12.0"),
        ));
        assert_eq!(info.kind, ClientKind::Android);
        assert_eq!(info.version.as_deref(), Some("1.7.1 (code 123; debug)"));
        assert_eq!(info.label.as_deref(), Some("Android 1.7.1 (debug)"));
        assert_eq!(info.user_agent.as_deref(), Some("okhttp/4.12.0"));
    }

    #[test]
    fn web_header_uses_the_browser_for_the_label() {
        let info =
            ClientInfo::from_headers(&headers(Some(b"web/1.7.1 (build abc123)"), Some(FIREFOX)));
        assert_eq!(info.kind, ClientKind::Web);
        assert_eq!(info.version.as_deref(), Some("1.7.1 (build abc123)"));
        assert_eq!(info.label.as_deref(), Some("Firefox 159 \u{b7} Linux"));
        let bare = ClientInfo::from_headers(&headers(Some(b"web/1.7.1"), None));
        assert_eq!(bare.label.as_deref(), Some("Web 1.7.1"));
    }

    #[test]
    fn user_agent_fallback_classifies_browsers_and_okhttp() {
        let firefox = ClientInfo::from_headers(&headers(None, Some(FIREFOX)));
        assert_eq!(firefox.kind, ClientKind::Web);
        assert_eq!(firefox.version, None);
        assert_eq!(firefox.label.as_deref(), Some("Firefox 159 \u{b7} Linux"));

        let chrome = ClientInfo::from_headers(&headers(
            None,
            Some(b"Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36 Edg/124.0.2478.51"),
        ));
        assert_eq!(chrome.label.as_deref(), Some("Edge 124 \u{b7} Windows"));

        let safari = ClientInfo::from_headers(&headers(
            None,
            Some(b"Mozilla/5.0 (iPhone; CPU iPhone OS 17_4 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.4 Mobile/15E148 Safari/604.1"),
        ));
        assert_eq!(safari.label.as_deref(), Some("Safari 17 \u{b7} iOS"));

        let android_chrome = ClientInfo::from_headers(&headers(
            None,
            Some(b"Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Mobile Safari/537.36"),
        ));
        assert_eq!(
            android_chrome.label.as_deref(),
            Some("Chrome 125 \u{b7} Android")
        );

        let okhttp = ClientInfo::from_headers(&headers(None, Some(b"okhttp/4.12.0")));
        assert_eq!(okhttp.kind, ClientKind::Android);
        assert_eq!(okhttp.label.as_deref(), Some("Android (okhttp 4.12.0)"));

        let curl = ClientInfo::from_headers(&headers(None, Some(b"curl/8.4.0")));
        assert_eq!(curl.kind, ClientKind::Api);
        assert_eq!(curl.label.as_deref(), Some("curl 8.4.0"));

        let bare = ClientInfo::from_headers(&headers(None, None));
        assert_eq!(bare.kind, ClientKind::Api);
        assert_eq!(bare.label, None);
        assert_eq!(bare.user_agent, None);
    }

    #[test]
    fn invalid_or_oversized_client_headers_are_ignored() {
        let oversized = format!("android/1.0 ({})", "a".repeat(200));
        for value in [
            oversized.as_bytes(),
            b"system/1.0",
            b"android/",
            b"android/1.0\" onload=x",
            b"android/1.0 (code 1; <b>)",
            b"android/1.0 (unclosed",
            b"android 1.0",
            b"android/\xe9\xe9",
            b"",
        ] {
            let info = ClientInfo::from_headers(&headers(Some(value), Some(b"okhttp/4.12.0")));
            assert_eq!(info.version, None, "{value:?}");
            assert_eq!(info.kind, ClientKind::Android, "falls back to the UA");
            assert_eq!(info.label.as_deref(), Some("Android (okhttp 4.12.0)"));
        }
    }

    #[test]
    fn user_agent_is_sanitized_and_truncated() {
        let long = format!("curl/8.0 {}", "x".repeat(600));
        let info = ClientInfo::from_headers(&headers(None, Some(long.as_bytes())));
        assert_eq!(
            info.user_agent.as_deref().map(|ua| ua.chars().count()),
            Some(256)
        );
        let hostile =
            ClientInfo::from_headers(&headers(None, Some(b"curl/8.0 \t\"quoted\" \xff\xfe tail")));
        let ua = hostile.user_agent.unwrap();
        assert!(
            ua.chars().all(|c| !c.is_control() && c != '\u{fffd}'),
            "{ua:?}"
        );
        assert!(ua.contains("tail"));
    }

    #[tokio::test]
    async fn current_is_system_outside_a_request() {
        assert_eq!(super::current().kind, ClientKind::System);
        let scoped = super::scope(
            ClientInfo::from_headers(&headers(Some(b"web/1.0"), None)),
            async { super::current().kind },
        )
        .await;
        assert_eq!(scoped, ClientKind::Web);
    }
}
