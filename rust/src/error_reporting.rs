//! Optional, operator-configured Sentry error reporting.
//!
//! Nothing here is active unless the operator sets a DSN. Without one the
//! server never initialises the SDK, `/api/v2/meta` and `/api/v2/session`
//! advertise `available: false`, and the tunnel route answers 404.
//!
//! Privacy rules, enforced in code rather than by convention:
//!
//! - `send_default_pii` is off, and [`scrub_event`] removes request headers,
//!   cookies, bodies and query strings, `extra` data, the server name, and
//!   every user field except a numeric id. Free text is passed through
//!   [`scrub_text`] to drop emails, bearer tokens and token-looking strings.
//! - A user who turned error reporting off is never reported. Their id is
//!   bound to the request's Sentry scope as an opt-out marker when they
//!   authenticate; [`scrub_event`] drops any event carrying the marker.
//! - Browser and Android clients send through the same-origin tunnel
//!   ([`ErrorReportingSettings::prepare_envelope`]), which only forwards
//!   envelopes addressed to the configured DSN, caps their size, drops events
//!   from opted-out users and scrubs events again on the server side.

use std::{fmt, io::Read, sync::Arc, time::Duration};

use sentry::{
    protocol::{Event, User},
    types::Dsn,
};
use serde_json::{Map, Value};
use tokio::sync::Semaphore;
use url::Url;

/// Whether a user who never chose has error reporting on, once the instance
/// has a DSN. It must equal the `DEFAULT` of `users.error_reporting` in the
/// migration (checked by a test).
pub const DEFAULT_USER_ENABLED: bool = true;

/// Same-origin route that web and Android clients send envelopes to.
pub const TUNNEL_PATH: &str = "/api/v2/telemetry/sentry";

/// Largest envelope accepted by the tunnel, before and after decompression.
pub const MAX_ENVELOPE_BYTES: usize = 256 * 1024;

const MAX_DSN_FILE_BYTES: usize = 1024;
const MAX_ENVIRONMENT_BYTES: usize = 64;
const DEFAULT_ENVIRONMENT: &str = "production";
const FORWARD_TIMEOUT: Duration = Duration::from_secs(5);
const MAX_CONCURRENT_FORWARDS: usize = 8;
const MAX_ENVELOPE_ITEMS: usize = 16;
/// Envelope item types the tunnel forwards; attachments and replays are not.
const ALLOWED_ITEM_TYPES: [&str; 5] = [
    "event",
    "transaction",
    "session",
    "sessions",
    "client_report",
];
/// Tag set on a request scope when the signed-in user opted out.
const OPT_OUT_TAG: &str = "stricknani.error_reporting_opt_out";
const REDACTED: &str = "[redacted]";

/// Why the error-reporting configuration is unusable.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct SettingsError(pub &'static str);

impl fmt::Display for SettingsError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter.write_str(self.0)
    }
}

impl std::error::Error for SettingsError {}

struct Configured {
    dsn: Dsn,
    environment: String,
    traces_sample_rate: f32,
    client: reqwest::Client,
    slots: Semaphore,
}

/// Instance-level error-reporting configuration; `Default` is "not configured".
#[derive(Clone, Default)]
pub struct ErrorReportingSettings {
    configured: Option<Arc<Configured>>,
}

impl fmt::Debug for ErrorReportingSettings {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter
            .debug_struct("ErrorReportingSettings")
            .field(
                "configured",
                &if self.configured.is_some() {
                    "[CONFIGURED]"
                } else {
                    "[NONE]"
                },
            )
            .finish()
    }
}

impl ErrorReportingSettings {
    /// Read `RUST_SENTRY_DSN_FILE` (preferred) or `RUST_SENTRY_DSN`, plus
    /// `RUST_SENTRY_ENVIRONMENT` and `RUST_SENTRY_TRACES_SAMPLE_RATE`.
    pub fn from_lookup(
        get: &mut impl FnMut(&str) -> Option<String>,
    ) -> Result<Self, SettingsError> {
        let dsn = match get("RUST_SENTRY_DSN_FILE").filter(|value| !value.trim().is_empty()) {
            Some(path) => Some(read_dsn_file(&path)?),
            None => get("RUST_SENTRY_DSN"),
        };
        let Some(dsn) = dsn
            .map(|value| value.trim().to_owned())
            .filter(|v| !v.is_empty())
        else {
            return Ok(Self::default());
        };
        let environment = get("RUST_SENTRY_ENVIRONMENT")
            .map(|value| value.trim().to_owned())
            .filter(|value| !value.is_empty())
            .unwrap_or_else(|| DEFAULT_ENVIRONMENT.to_owned());
        let traces_sample_rate = match get("RUST_SENTRY_TRACES_SAMPLE_RATE") {
            None => 0.0,
            Some(value) => value
                .trim()
                .parse::<f32>()
                .ok()
                .filter(|rate| (0.0..=1.0).contains(rate))
                .ok_or(SettingsError(
                    "RUST_SENTRY_TRACES_SAMPLE_RATE must be a number from 0 to 1",
                ))?,
        };
        Self::new(&dsn, environment, traces_sample_rate)
    }

    /// Read the settings from the process environment.
    pub fn from_env() -> Result<Self, SettingsError> {
        Self::from_lookup(&mut |key| std::env::var(key).ok())
    }

    /// Build configured settings from a DSN string.
    pub fn new(
        dsn: &str,
        environment: String,
        traces_sample_rate: f32,
    ) -> Result<Self, SettingsError> {
        let dsn: Dsn = dsn
            .parse()
            .map_err(|_| SettingsError("the Sentry DSN is invalid"))?;
        if environment.len() > MAX_ENVIRONMENT_BYTES
            || environment
                .chars()
                .any(|c| c.is_control() || c.is_whitespace())
        {
            return Err(SettingsError(
                "RUST_SENTRY_ENVIRONMENT must be a short name without whitespace",
            ));
        }
        let mut builder = reqwest::Client::builder()
            .no_proxy()
            .redirect(reqwest::redirect::Policy::none())
            .timeout(FORWARD_TIMEOUT)
            .connect_timeout(FORWARD_TIMEOUT)
            .user_agent(concat!(
                env!("CARGO_PKG_NAME"),
                "/",
                env!("CARGO_PKG_VERSION")
            ));
        if dsn.scheme() == sentry::types::Scheme::Http {
            // Plain-HTTP DSNs only exist for local test servers. They never
            // negotiate TLS, so do not require system CA certificates, which
            // the Nix build sandbox lacks.
            builder = builder.tls_certs_only(Vec::new());
        }
        let client = builder
            .build()
            .map_err(|_| SettingsError("cannot build the Sentry tunnel client"))?;
        Ok(Self {
            configured: Some(Arc::new(Configured {
                dsn,
                environment,
                traces_sample_rate,
                client,
                slots: Semaphore::new(MAX_CONCURRENT_FORWARDS),
            })),
        })
    }

    /// Whether the operator configured a DSN.
    pub fn is_available(&self) -> bool {
        self.configured.is_some()
    }

    /// The DSN without any secret key, safe to give to clients (it is the
    /// public key by design).
    pub fn public_dsn(&self) -> Option<String> {
        self.configured.as_ref().map(|c| public_dsn(&c.dsn))
    }

    /// The configured environment name.
    pub fn environment(&self) -> Option<&str> {
        self.configured.as_ref().map(|c| c.environment.as_str())
    }

    /// Initialise the server SDK. `None` when no DSN is configured. Keep the
    /// returned guard alive for the life of the process: dropping it flushes
    /// pending events.
    pub fn init_sdk(&self) -> Option<sentry::ClientInitGuard> {
        let configured = self.configured.as_ref()?;
        let mut options = sentry::ClientOptions::new()
            .release(concat!(
                env!("CARGO_PKG_NAME"),
                "@",
                env!("CARGO_PKG_VERSION")
            ))
            .environment(configured.environment.clone())
            .server_name("stricknani")
            .send_default_pii(false)
            .attach_stacktrace(true)
            .max_breadcrumbs(10)
            .traces_sample_rate(configured.traces_sample_rate)
            .before_send(scrub_event)
            .before_breadcrumb(scrub_breadcrumb);
        options.dsn = Some(configured.dsn.clone());
        Some(sentry::init(options))
    }

    /// Whether the tracing integration should start transactions.
    pub fn traces_enabled(&self) -> bool {
        self.configured
            .as_ref()
            .is_some_and(|c| c.traces_sample_rate > 0.0)
    }

    /// Validate and re-scrub a tunnelled envelope. The result is the body to
    /// forward. Wrong DSN, malformed envelopes, forbidden item types and
    /// oversize payloads are rejected.
    pub fn prepare_envelope(&self, body: &[u8], gzip: bool) -> Result<Vec<u8>, EnvelopeError> {
        let configured = self.configured.as_ref().ok_or(EnvelopeError::Unavailable)?;
        let decoded;
        let body = if gzip {
            decoded = gunzip(body)?;
            decoded.as_slice()
        } else {
            body
        };
        if body.len() > MAX_ENVELOPE_BYTES {
            return Err(EnvelopeError::TooLarge);
        }
        let (header, items) = parse_envelope(body)?;
        let claimed = header
            .get("dsn")
            .and_then(Value::as_str)
            .and_then(|dsn| dsn.parse::<Dsn>().ok())
            .ok_or(EnvelopeError::WrongDsn)?;
        if !same_project(&claimed, &configured.dsn) {
            return Err(EnvelopeError::WrongDsn);
        }
        Ok(serialize_envelope(&header, &items))
    }

    /// Forward a prepared envelope to the configured Sentry host. The target
    /// URL comes only from the operator's DSN, never from the request.
    pub async fn forward(&self, envelope: Vec<u8>) -> Result<(), ForwardError> {
        let configured = self.configured.as_ref().ok_or(ForwardError::Failed)?;
        let _slot = configured
            .slots
            .try_acquire()
            .map_err(|_| ForwardError::Busy)?;
        let response = configured
            .client
            .post(configured.dsn.envelope_api_url())
            .header("content-type", "application/x-sentry-envelope")
            .body(envelope)
            .send()
            .await
            .map_err(|_| ForwardError::Failed)?;
        if response.status().is_success() {
            Ok(())
        } else {
            Err(ForwardError::Failed)
        }
    }
}

/// Why the tunnel refused an envelope.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum EnvelopeError {
    Unavailable,
    TooLarge,
    Malformed,
    WrongDsn,
    ForbiddenItem,
}

/// Why forwarding to Sentry failed.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ForwardError {
    Busy,
    Failed,
}

fn read_dsn_file(path: &str) -> Result<String, SettingsError> {
    let file = std::fs::File::open(path)
        .map_err(|_| SettingsError("RUST_SENTRY_DSN_FILE cannot be read"))?;
    let mut contents = String::new();
    file.take(MAX_DSN_FILE_BYTES as u64 + 1)
        .read_to_string(&mut contents)
        .map_err(|_| SettingsError("RUST_SENTRY_DSN_FILE cannot be read"))?;
    if contents.len() > MAX_DSN_FILE_BYTES {
        return Err(SettingsError("RUST_SENTRY_DSN_FILE is too large"));
    }
    Ok(contents)
}

/// `scheme://public_key@host[:port]/path/project`, never the secret key.
fn public_dsn(dsn: &Dsn) -> String {
    let port = if dsn.port() == dsn.scheme().default_port() {
        String::new()
    } else {
        format!(":{}", dsn.port())
    };
    format!(
        "{}://{}@{}{}{}{}",
        dsn.scheme(),
        dsn.public_key(),
        dsn.host(),
        port,
        dsn.path(),
        dsn.project_id()
    )
}

/// Two DSNs name the same project when everything but the secret key matches.
fn same_project(left: &Dsn, right: &Dsn) -> bool {
    left.scheme() == right.scheme()
        && left.public_key() == right.public_key()
        && left.host().eq_ignore_ascii_case(right.host())
        && left.port() == right.port()
        && left.path() == right.path()
        && left.project_id() == right.project_id()
}

// ---------------------------------------------------------------------------
// Per-user opt-out
// ---------------------------------------------------------------------------

/// Bind the authenticated user to the current request's Sentry scope.
///
/// Called from authentication. With the SDK inactive this is a cheap no-op.
/// An opted-out user gets an opt-out marker (and no identity); everyone else
/// is identified by numeric id only.
pub(crate) fn bind_user(user_id: i64, enabled: bool) {
    let hub = sentry::Hub::current();
    if hub.client().is_none() {
        return;
    }
    hub.configure_scope(|scope| {
        if enabled {
            scope.set_user(Some(User {
                id: Some(user_id.to_string()),
                ..User::default()
            }));
        } else {
            scope.set_user(None);
            scope.set_tag(OPT_OUT_TAG, "1");
        }
    });
}

// ---------------------------------------------------------------------------
// Scrubbing of SDK events
// ---------------------------------------------------------------------------

/// `before_send` for the server SDK: drop opted-out users, then scrub.
pub fn scrub_event(mut event: Event<'static>) -> Option<Event<'static>> {
    if event.tags.remove(OPT_OUT_TAG).is_some() {
        return None;
    }
    event.server_name = None;
    event.modules.clear();
    event.extra.clear();
    event.culprit = None;
    event.user = event
        .user
        .take()
        .and_then(|user| user.id)
        .filter(|id| !id.is_empty() && id.bytes().all(|b| b.is_ascii_digit()))
        .map(|id| User {
            id: Some(id),
            ..User::default()
        });
    if let Some(request) = event.request.as_mut() {
        request.headers.clear();
        request.cookies = None;
        request.data = None;
        request.query_string = None;
        request.env.clear();
        if let Some(url) = request.url.as_mut() {
            scrub_url(url);
        }
    }
    event.message = event.message.take().map(|text| scrub_text(&text));
    event.transaction = event.transaction.take().map(|text| scrub_text(&text));
    if let Some(entry) = event.logentry.as_mut() {
        entry.message = scrub_text(&entry.message);
        entry.params.clear();
    }
    for exception in event.exception.values.iter_mut() {
        exception.value = exception.value.take().map(|text| scrub_text(&text));
    }
    for breadcrumb in event.breadcrumbs.values.iter_mut() {
        breadcrumb.message = breadcrumb.message.take().map(|text| scrub_text(&text));
        breadcrumb.data.clear();
    }
    for value in event.tags.values_mut() {
        *value = scrub_text(value);
    }
    event.contexts.retain(|name, _| {
        matches!(
            name.as_str(),
            "os" | "runtime" | "rust" | "device" | "trace"
        )
    });
    Some(event)
}

/// `before_breadcrumb` for the server SDK.
pub fn scrub_breadcrumb(mut breadcrumb: sentry::Breadcrumb) -> Option<sentry::Breadcrumb> {
    breadcrumb.message = breadcrumb.message.take().map(|text| scrub_text(&text));
    breadcrumb.data.clear();
    Some(breadcrumb)
}

fn token_like(segment: &str, min_len: usize) -> bool {
    segment.len() >= min_len
        && segment
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || b == b'_' || b == b'-')
        && segment.bytes().any(|b| b.is_ascii_digit())
}

/// Drop credentials, query string, fragment and token-looking path segments.
pub fn scrub_url(url: &mut Url) {
    let _ = url.set_username("");
    let _ = url.set_password(None);
    url.set_query(None);
    url.set_fragment(None);
    let path = url
        .path()
        .split('/')
        .map(|segment| {
            if token_like(segment, 24) {
                REDACTED
            } else {
                segment
            }
        })
        .collect::<Vec<_>>()
        .join("/");
    url.set_path(&path);
}

fn sensitive_key(key: &str) -> bool {
    let key = key.to_ascii_lowercase();
    [
        "token", "secret", "password", "passwd", "key", "auth", "cookie", "session", "code",
        "state", "bearer", "email", "csrf",
    ]
    .iter()
    .any(|needle| key.contains(needle))
}

fn is_separator(c: char) -> bool {
    c.is_whitespace()
        || matches!(
            c,
            '"' | '\'' | '<' | '>' | '(' | ')' | '[' | ']' | '{' | '}' | ',' | ';' | '`'
        )
}

/// Redact emails, bearer tokens, `secret=value` pairs, query strings and
/// long token-looking words in free text. Everything else is kept.
pub fn scrub_text(input: &str) -> String {
    let mut output = String::with_capacity(input.len());
    let mut token = String::new();
    let mut previous_was_bearer = false;
    let flush = |token: &mut String, output: &mut String, previous_was_bearer: &mut bool| {
        if token.is_empty() {
            return;
        }
        let redacted = if *previous_was_bearer {
            REDACTED.to_owned()
        } else {
            scrub_word(token)
        };
        *previous_was_bearer = token.eq_ignore_ascii_case("bearer");
        output.push_str(&redacted);
        token.clear();
    };
    for c in input.chars() {
        if is_separator(c) {
            flush(&mut token, &mut output, &mut previous_was_bearer);
            output.push(c);
        } else {
            token.push(c);
        }
    }
    flush(&mut token, &mut output, &mut previous_was_bearer);
    output
}

fn scrub_word(word: &str) -> String {
    if let Some((before, after)) = word.split_once('@')
        && !before.is_empty()
        && after.contains('.')
    {
        return "[email]".to_owned();
    }
    if let Some((before, after)) = word.split_once('?') {
        let query = if after.is_empty() { "" } else { "?[redacted]" };
        return format!("{}{query}", scrub_word(before));
    }
    if let Some((before, _)) = word.split_once('#') {
        return scrub_word(before);
    }
    if let Some((key, value)) = word.split_once('=') {
        if !value.is_empty() && sensitive_key(key) {
            return format!("{key}={REDACTED}");
        }
        return format!("{key}={}", scrub_word(value));
    }
    if word.contains('/') {
        return word
            .split('/')
            .map(|segment| {
                if token_like(segment, 24) {
                    REDACTED
                } else {
                    segment
                }
            })
            .collect::<Vec<_>>()
            .join("/");
    }
    if token_like(word, 32) {
        return REDACTED.to_owned();
    }
    word.to_owned()
}

// ---------------------------------------------------------------------------
// Tunnel envelopes
// ---------------------------------------------------------------------------

struct Item {
    header: Map<String, Value>,
    payload: Vec<u8>,
}

fn gunzip(body: &[u8]) -> Result<Vec<u8>, EnvelopeError> {
    let mut decoded = Vec::new();
    flate2::read::GzDecoder::new(body)
        .take(MAX_ENVELOPE_BYTES as u64 + 1)
        .read_to_end(&mut decoded)
        .map_err(|_| EnvelopeError::Malformed)?;
    if decoded.len() > MAX_ENVELOPE_BYTES {
        return Err(EnvelopeError::TooLarge);
    }
    Ok(decoded)
}

fn split_line(bytes: &[u8]) -> (&[u8], &[u8]) {
    match bytes.iter().position(|b| *b == b'\n') {
        Some(end) => (&bytes[..end], &bytes[end + 1..]),
        None => (bytes, &[]),
    }
}

fn parse_envelope(body: &[u8]) -> Result<(Value, Vec<Item>), EnvelopeError> {
    let (header_line, mut rest) = split_line(body);
    let header: Value =
        serde_json::from_slice(header_line).map_err(|_| EnvelopeError::Malformed)?;
    if !header.is_object() {
        return Err(EnvelopeError::Malformed);
    }
    let mut items = Vec::new();
    while !rest.is_empty() {
        if rest == b"\n" {
            break;
        }
        if items.len() >= MAX_ENVELOPE_ITEMS {
            return Err(EnvelopeError::Malformed);
        }
        let (item_line, after) = split_line(rest);
        let Value::Object(item_header) =
            serde_json::from_slice(item_line).map_err(|_| EnvelopeError::Malformed)?
        else {
            return Err(EnvelopeError::Malformed);
        };
        let kind = item_header
            .get("type")
            .and_then(Value::as_str)
            .ok_or(EnvelopeError::Malformed)?;
        if !ALLOWED_ITEM_TYPES.contains(&kind) {
            return Err(EnvelopeError::ForbiddenItem);
        }
        let (payload, remaining) = match item_header.get("length").and_then(Value::as_u64) {
            Some(length) => {
                let length = usize::try_from(length).map_err(|_| EnvelopeError::Malformed)?;
                if length > after.len() {
                    return Err(EnvelopeError::Malformed);
                }
                let (payload, tail) = after.split_at(length);
                (payload, tail.strip_prefix(b"\n").unwrap_or(tail))
            }
            None => split_line(after),
        };
        items.push(Item {
            header: item_header,
            payload: payload.to_vec(),
        });
        rest = remaining;
    }
    if items.is_empty() {
        return Err(EnvelopeError::Malformed);
    }
    Ok((header, items))
}

fn serialize_envelope(header: &Value, items: &[Item]) -> Vec<u8> {
    let mut out = serde_json::to_vec(header).unwrap_or_default();
    out.push(b'\n');
    for item in items {
        let kind = item.header.get("type").and_then(Value::as_str);
        let payload = if matches!(kind, Some("event" | "transaction")) {
            scrub_event_payload(&item.payload)
        } else {
            item.payload.clone()
        };
        let mut item_header = item.header.clone();
        item_header.insert("length".to_owned(), Value::from(payload.len()));
        out.extend(serde_json::to_vec(&item_header).unwrap_or_default());
        out.push(b'\n');
        out.extend(payload);
        out.push(b'\n');
    }
    out
}

/// Scrub a client-built event. Unparseable payloads are replaced by an empty
/// object rather than forwarded unscrubbed.
fn scrub_event_payload(payload: &[u8]) -> Vec<u8> {
    let mut value: Value = serde_json::from_slice(payload).unwrap_or(Value::Object(Map::new()));
    scrub_json_event(&mut value);
    serde_json::to_vec(&value).unwrap_or_else(|_| b"{}".to_vec())
}

/// The JSON counterpart of [`scrub_event`] for events built by other SDKs.
pub fn scrub_json_event(event: &mut Value) {
    let Some(object) = event.as_object_mut() else {
        return;
    };
    for key in ["user", "extra", "server_name", "modules", "culprit"] {
        object.remove(key);
    }
    if let Some(request) = object.get_mut("request").and_then(Value::as_object_mut) {
        for key in ["headers", "cookies", "data", "query_string", "env"] {
            request.remove(key);
        }
        if let Some(Value::String(url)) = request.get_mut("url") {
            *url = match Url::parse(url) {
                Ok(mut parsed) => {
                    scrub_url(&mut parsed);
                    parsed.to_string()
                }
                Err(_) => scrub_text(url),
            };
        }
    }
    for key in ["message", "transaction"] {
        if let Some(Value::String(text)) = object.get_mut(key) {
            *text = scrub_text(text);
        }
    }
    if let Some(Value::String(text)) = object
        .get_mut("logentry")
        .and_then(Value::as_object_mut)
        .and_then(|entry| entry.get_mut("message"))
    {
        *text = scrub_text(text);
    }
    if let Some(Value::Object(tags)) = object.get_mut("tags") {
        for value in tags.values_mut() {
            if let Value::String(text) = value {
                *text = scrub_text(text);
            }
        }
    }
    for list in ["exception", "breadcrumbs"] {
        let Some(values) = object.get_mut(list) else {
            continue;
        };
        let items = match values {
            Value::Object(map) => map.get_mut("values").and_then(Value::as_array_mut),
            Value::Array(array) => Some(array),
            _ => None,
        };
        for item in items.into_iter().flatten().filter_map(Value::as_object_mut) {
            for key in ["value", "message"] {
                if let Some(Value::String(text)) = item.get_mut(key) {
                    *text = scrub_text(text);
                }
            }
            item.remove("data");
        }
    }
    if let Some(Value::Array(spans)) = object.get_mut("spans") {
        for span in spans.iter_mut().filter_map(Value::as_object_mut) {
            span.remove("data");
            if let Some(Value::String(text)) = span.get_mut("description") {
                *text = scrub_text(text);
            }
        }
    }
    if let Some(Value::Object(contexts)) = object.get_mut("contexts") {
        contexts.retain(|name, _| {
            matches!(
                name.as_str(),
                "os" | "runtime" | "rust" | "device" | "trace" | "browser" | "app" | "culture"
            )
        });
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const DSN: &str = "https://abc123publickey@sentry.example.test/42";

    fn settings() -> ErrorReportingSettings {
        ErrorReportingSettings::new(DSN, "test".to_owned(), 0.0).expect("valid settings")
    }

    fn envelope(dsn: &str, payload: &str) -> Vec<u8> {
        format!(
            "{{\"dsn\":\"{dsn}\"}}\n{{\"type\":\"event\",\"length\":{}}}\n{payload}\n",
            payload.len()
        )
        .into_bytes()
    }

    #[test]
    fn scrubs_secrets_from_text() {
        assert_eq!(
            scrub_text("failed for alice@example.test with Bearer abcdef0123456789"),
            "failed for [email] with Bearer [redacted]"
        );
        assert_eq!(
            scrub_text("GET /x?token=abc&y=1 failed"),
            "GET /x?[redacted] failed"
        );
        assert_eq!(scrub_text("password=hunter2"), "password=[redacted]");
        assert_eq!(
            scrub_text("id 0123456789abcdef0123456789abcdef01234567"),
            "id [redacted]"
        );
        assert_eq!(scrub_text("plain database error"), "plain database error");
    }

    #[test]
    fn scrubs_urls() {
        let mut url = Url::parse(
            "https://u:p@stricknani.test/signup/AbCdEf0123456789AbCdEf0123456789?token=secret#frag",
        )
        .unwrap();
        scrub_url(&mut url);
        assert_eq!(url.as_str(), "https://stricknani.test/signup/[redacted]");
    }

    #[test]
    fn event_scrubbing_keeps_only_a_numeric_user_id() {
        let mut event = Event::new();
        event.message = Some("mail bob@example.test failed".to_owned());
        event.server_name = Some("host".into());
        event.user = Some(User {
            id: Some("7".to_owned()),
            email: Some("bob@example.test".to_owned()),
            ip_address: Some(sentry::protocol::IpAddress::Auto),
            username: Some("bob".to_owned()),
            ..User::default()
        });
        event
            .extra
            .insert("body".to_owned(), Value::String("secret".to_owned()));
        let mut request = sentry::protocol::Request::default();
        request
            .headers
            .insert("authorization".into(), "Bearer x".into());
        request.cookies = Some("stricknani_session=abc".to_owned());
        request.query_string = Some("token=abc".to_owned());
        request.url = Url::parse("https://stricknani.test/api?token=abc").ok();
        event.request = Some(request);

        let event = scrub_event(event).expect("kept");
        assert_eq!(event.message.as_deref(), Some("mail [email] failed"));
        assert!(event.server_name.is_none());
        assert!(event.extra.is_empty());
        let user = event.user.expect("user id kept");
        assert_eq!(user.id.as_deref(), Some("7"));
        assert!(user.email.is_none() && user.username.is_none() && user.ip_address.is_none());
        let request = event.request.expect("request kept");
        assert!(request.headers.is_empty());
        assert!(request.cookies.is_none() && request.query_string.is_none());
        assert_eq!(request.url.unwrap().as_str(), "https://stricknani.test/api");
    }

    #[test]
    fn opted_out_users_are_never_reported() {
        let mut event = Event::new();
        event.tags.insert(OPT_OUT_TAG.to_owned(), "1".to_owned());
        assert!(scrub_event(event).is_none());
    }

    #[test]
    fn opt_out_marker_set_by_bind_user_drops_events_and_enabled_users_are_identified() {
        let options = sentry::ClientOptions::new().before_send(scrub_event);
        let events = sentry::test::with_captured_events_options(
            || {
                bind_user(5, false);
                sentry::capture_message("opted out", sentry::Level::Error);
            },
            options.clone(),
        );
        assert!(events.is_empty());

        let events = sentry::test::with_captured_events_options(
            || {
                bind_user(5, true);
                sentry::capture_message("opted in", sentry::Level::Error);
            },
            options,
        );
        assert_eq!(events.len(), 1);
        assert_eq!(
            events[0].user.as_ref().and_then(|u| u.id.as_deref()),
            Some("5")
        );
    }

    #[test]
    fn accepts_envelopes_for_the_configured_dsn_only() {
        let settings = settings();
        let ok = envelope(DSN, "{\"message\":\"boom\"}");
        assert!(settings.prepare_envelope(&ok, false).is_ok());
        // The secret key part of a DSN does not change which project it names.
        let other_project = envelope("https://abc123publickey@sentry.example.test/43", "{}");
        assert_eq!(
            settings.prepare_envelope(&other_project, false),
            Err(EnvelopeError::WrongDsn)
        );
        let other_host = envelope("https://abc123publickey@evil.example.test/42", "{}");
        assert_eq!(
            settings.prepare_envelope(&other_host, false),
            Err(EnvelopeError::WrongDsn)
        );
        assert_eq!(
            settings.prepare_envelope(b"{}\n{\"type\":\"event\"}\n{}\n", false),
            Err(EnvelopeError::WrongDsn)
        );
    }

    #[test]
    fn rejects_malformed_oversize_and_forbidden_envelopes() {
        let settings = settings();
        assert_eq!(
            settings.prepare_envelope(b"not json\n", false),
            Err(EnvelopeError::Malformed)
        );
        let attachment =
            format!("{{\"dsn\":\"{DSN}\"}}\n{{\"type\":\"attachment\",\"length\":2}}\nxx\n");
        assert_eq!(
            settings.prepare_envelope(attachment.as_bytes(), false),
            Err(EnvelopeError::ForbiddenItem)
        );
        let huge = envelope(
            DSN,
            &format!("{{\"message\":\"{}\"}}", "a".repeat(MAX_ENVELOPE_BYTES)),
        );
        assert_eq!(
            settings.prepare_envelope(&huge, false),
            Err(EnvelopeError::TooLarge)
        );
    }

    #[test]
    fn tunnel_scrubs_client_events() {
        let settings = settings();
        let body = envelope(
            DSN,
            "{\"message\":\"hi bob@example.test\",\"user\":{\"email\":\"bob@example.test\"},\"request\":{\"url\":\"https://s.test/p?token=abc\",\"cookies\":\"a=b\"}}",
        );
        let forwarded =
            String::from_utf8(settings.prepare_envelope(&body, false).unwrap()).unwrap();
        assert!(!forwarded.contains("bob@example.test"));
        assert!(!forwarded.contains("token=abc"));
        assert!(!forwarded.contains("cookies"));
        assert!(forwarded.contains("[email]"));
    }

    #[test]
    fn dsn_parsing_never_exposes_the_secret_key() {
        let settings = ErrorReportingSettings::new(
            "https://pub:topsecret@sentry.example.test:8443/9",
            "prod".to_owned(),
            0.0,
        )
        .unwrap();
        assert_eq!(
            settings.public_dsn().as_deref(),
            Some("https://pub@sentry.example.test:8443/9")
        );
    }

    #[test]
    fn configuration_is_optional_and_validated() {
        let none = |_: &str| None;
        assert!(
            !ErrorReportingSettings::from_lookup(&mut |k| none(k))
                .unwrap()
                .is_available()
        );
        let with = |dsn: &'static str, rate: Option<&'static str>| {
            ErrorReportingSettings::from_lookup(&mut |key| match key {
                "RUST_SENTRY_DSN" => Some(dsn.to_owned()),
                "RUST_SENTRY_TRACES_SAMPLE_RATE" => rate.map(str::to_owned),
                _ => None,
            })
        };
        assert!(with(DSN, None).unwrap().is_available());
        assert!(with("not a dsn", None).is_err());
        assert!(with(DSN, Some("2")).is_err());
        assert!(with(DSN, Some("0.25")).is_ok());
    }

    #[test]
    fn default_matches_the_migration() {
        let migration = include_str!("../migrations/0021_user_error_reporting.sql");
        let default = if DEFAULT_USER_ENABLED {
            "DEFAULT 1"
        } else {
            "DEFAULT 0"
        };
        assert!(migration.contains(default));
    }
}
