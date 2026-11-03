//! Host validation and response security policy for the Rust web server.

use std::{collections::BTreeSet, net::IpAddr, str::FromStr};

use axum::{
    Router,
    extract::State,
    http::{HeaderValue, StatusCode, header},
    middleware::{self, Next},
    response::{IntoResponse, Response},
};
use base64::{Engine, engine::general_purpose::STANDARD};

/// Validated CSP hashes produced by the static frontend build.
///
/// This response extension is the only way to add generated inline hashes to
/// the otherwise unchanged global CSP. It is crate-private so request-driven
/// routes cannot create policy exceptions.
#[derive(Clone, Debug)]
pub(crate) struct FrontendCsp(HeaderValue);

impl FrontendCsp {
    pub(crate) fn from_hashes(
        script_hashes: &[String],
        style_hashes: &[String],
        style_attribute_hashes: &[String],
    ) -> Option<Self> {
        if script_hashes.len() > 64 || style_hashes.len() > 64 || style_attribute_hashes.len() > 64
        {
            return None;
        }
        let script_hashes = script_hashes
            .iter()
            .map(|hash| validate_hash(hash))
            .collect::<Option<Vec<_>>>()?;
        let style_hashes = style_hashes
            .iter()
            .map(|hash| validate_hash(hash))
            .collect::<Option<Vec<_>>>()?;
        let style_attribute_hashes = style_attribute_hashes
            .iter()
            .map(|hash| validate_hash(hash))
            .collect::<Option<Vec<_>>>()?;
        let script_hashes = script_hashes
            .iter()
            .map(|hash| format!("'{hash}'"))
            .collect::<Vec<_>>()
            .join(" ");
        let style_hashes = style_hashes
            .iter()
            .map(|hash| format!("'{hash}'"))
            .collect::<Vec<_>>()
            .join(" ");
        let script_sources = if script_hashes.is_empty() {
            "'self'".to_owned()
        } else {
            format!("'self' {script_hashes}")
        };
        let style_sources = if style_hashes.is_empty() {
            "'self'".to_owned()
        } else {
            format!("'self' {style_hashes}")
        };
        let style_attribute_hashes = if style_attribute_hashes.is_empty() {
            "'none'".to_owned()
        } else {
            let hashes = style_attribute_hashes
                .iter()
                .map(|hash| format!("'{hash}'"))
                .collect::<Vec<_>>()
                .join(" ");
            format!("'unsafe-hashes' {hashes}")
        };
        let policy = format!(
            "default-src 'self'; base-uri 'self'; object-src 'none'; frame-ancestors 'none'; form-action 'self'; img-src 'self' data: blob:; style-src {style_sources}; style-src-attr {style_attribute_hashes}; script-src {script_sources}; font-src 'self' data:; connect-src 'self'"
        );
        HeaderValue::from_str(&policy).ok().map(Self)
    }

    fn header_value(&self) -> &HeaderValue {
        &self.0
    }
}

fn validate_hash(value: &str) -> Option<&str> {
    let encoded = value.strip_prefix("sha256-")?;
    let decoded = STANDARD.decode(encoded).ok()?;
    (decoded.len() == 32 && STANDARD.encode(decoded) == encoded).then_some(value)
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct TrustedHosts(BTreeSet<HostAuthority>);

#[derive(Clone, Debug, PartialEq, Eq, PartialOrd, Ord)]
struct HostAuthority {
    host: String,
    port: Option<u16>,
}

#[derive(Debug, thiserror::Error, PartialEq, Eq)]
#[error("TRUSTED_HOSTS must contain one or more valid host authorities")]
pub struct TrustedHostsError;

impl TrustedHosts {
    /// Parse a comma-separated list of exact host names or authorities.
    pub fn parse(value: &str) -> Result<Self, TrustedHostsError> {
        if value.len() > 4096 {
            return Err(TrustedHostsError);
        }
        let mut entries = BTreeSet::new();
        for entry in value.split(',') {
            let entry = entry.trim();
            if entry.is_empty() {
                return Err(TrustedHostsError);
            }
            entries.insert(HostAuthority::parse(entry)?);
        }
        if entries.is_empty() {
            return Err(TrustedHostsError);
        }
        Ok(Self(entries))
    }

    /// Restrict the development default to loopback names and addresses.
    pub fn loopback_defaults() -> Self {
        Self(
            ["localhost", "127.0.0.1", "[::1]"]
                .into_iter()
                .filter_map(|value| HostAuthority::parse(value).ok())
                .collect(),
        )
    }

    /// Check whether a Host authority is accepted by this allowlist.
    pub fn allows_host(&self, host: &str) -> bool {
        HostAuthority::parse(host).is_ok_and(|actual| self.allows(&actual))
    }

    fn allows(&self, actual: &HostAuthority) -> bool {
        self.0.iter().any(|allowed| {
            allowed.host == actual.host && allowed.port.is_none_or(|port| Some(port) == actual.port)
        })
    }
}

impl HostAuthority {
    fn parse(value: &str) -> Result<Self, TrustedHostsError> {
        if value.len() > 255
            || value.trim() != value
            || value.chars().any(|ch| {
                ch.is_ascii_whitespace() || matches!(ch, ',' | '@' | '/' | '\\' | '?' | '#' | '%')
            })
        {
            return Err(TrustedHostsError);
        }
        let authority =
            axum::http::uri::Authority::from_str(value).map_err(|_| TrustedHostsError)?;
        let raw_host = authority.host();
        if raw_host.is_empty() || authority.as_str().ends_with(':') {
            return Err(TrustedHostsError);
        }
        let host = if raw_host.starts_with('[') && raw_host.ends_with(']') {
            let address = raw_host[1..raw_host.len() - 1]
                .parse::<std::net::Ipv6Addr>()
                .map_err(|_| TrustedHostsError)?;
            format!("[{}]", address)
        } else if raw_host.contains(':') {
            return Err(TrustedHostsError);
        } else if let Ok(address) = raw_host.parse::<IpAddr>() {
            match address {
                IpAddr::V4(address) => address.to_string(),
                IpAddr::V6(_) => return Err(TrustedHostsError),
            }
        } else {
            match url::Host::parse(raw_host).map_err(|_| TrustedHostsError)? {
                url::Host::Domain(name) => name.to_ascii_lowercase(),
                _ => return Err(TrustedHostsError),
            }
        };
        let authority_suffix = authority
            .as_str()
            .strip_prefix(raw_host)
            .ok_or(TrustedHostsError)?;
        let port = match authority_suffix {
            "" => None,
            suffix if suffix.starts_with(':') => {
                let parsed = suffix[1..].parse::<u16>().map_err(|_| TrustedHostsError)?;
                if parsed == 0 || authority.port_u16() != Some(parsed) {
                    return Err(TrustedHostsError);
                }
                Some(parsed)
            }
            _ => return Err(TrustedHostsError),
        };
        Ok(Self { host, port })
    }
}

/// Add browser response headers while keeping successful static assets cacheable.
pub async fn security_headers(request: axum::extract::Request, next: Next) -> Response {
    let path = request.uri().path();
    let is_static = path.starts_with("/static/")
        || path.starts_with("/app/_app/")
        || path.starts_with("/app/icons/")
        || path == "/app/favicon.svg";
    let is_public_pwa = matches!(
        request.uri().path(),
        "/sw.js"
            | "/register-pwa.js"
            | "/manifest.webmanifest"
            | "/offline"
            | "/app/sw.js"
            | "/app/manifest.webmanifest"
            | "/app/offline.html"
            | "/app/offline.css"
            | "/app/offline-theme.js"
            | "/app/icons/icon-192.png"
            | "/app/icons/icon-512.png"
            | "/app/icons/icon-maskable-512.png"
    );
    let mut response = next.run(request).await;
    let cache_static = is_static && response.status().is_success();
    let preserve_pwa_cache = is_public_pwa && response.status().is_success();
    let frontend_csp = response
        .extensions()
        .get::<FrontendCsp>()
        .map(FrontendCsp::header_value)
        .cloned();
    let headers = response.headers_mut();
    headers.insert(
        "content-security-policy",
        frontend_csp.unwrap_or_else(|| {
            "default-src 'self'; base-uri 'self'; object-src 'none'; frame-ancestors 'none'; form-action 'self'; img-src 'self' data: blob:; style-src 'self'; script-src 'self'; font-src 'self' data:; connect-src 'self'"
                .parse()
                .expect("static CSP header is valid")
        }),
    );
    headers.insert(
        "x-content-type-options",
        "nosniff".parse().expect("static header is valid"),
    );
    headers.insert(
        "x-frame-options",
        "DENY".parse().expect("static header is valid"),
    );
    headers.insert(
        "referrer-policy",
        "strict-origin-when-cross-origin"
            .parse()
            .expect("static header is valid"),
    );
    headers.insert(
        "permissions-policy",
        "camera=(), microphone=(), geolocation=()"
            .parse()
            .expect("static header is valid"),
    );
    headers.insert(
        "cross-origin-opener-policy",
        "same-origin".parse().expect("static header is valid"),
    );
    headers.insert(
        "cross-origin-resource-policy",
        "same-origin".parse().expect("static header is valid"),
    );
    if cache_static {
        headers.insert(
            header::CACHE_CONTROL,
            "public, max-age=3600, stale-while-revalidate=86400"
                .parse()
                .expect("static cache header is valid"),
        );
    } else if !preserve_pwa_cache {
        headers.insert(
            header::CACHE_CONTROL,
            "private, no-store"
                .parse()
                .expect("private cache header is valid"),
        );
    }
    response
}

/// Apply exact Host validation to the public listener only.
pub fn with_host_validation(router: Router, trusted_hosts: TrustedHosts) -> Router {
    router
        .layer(middleware::from_fn_with_state(trusted_hosts, validate_host))
        .layer(middleware::from_fn(security_headers))
}

async fn validate_host(
    State(trusted_hosts): State<TrustedHosts>,
    request: axum::extract::Request,
    next: Next,
) -> Response {
    let hosts = request.headers().get_all(header::HOST);
    let mut values = hosts.iter();
    let Some(value) = values.next() else {
        return StatusCode::BAD_REQUEST.into_response();
    };
    if values.next().is_some() {
        return StatusCode::BAD_REQUEST.into_response();
    }
    let Ok(value) = value.to_str() else {
        return StatusCode::BAD_REQUEST.into_response();
    };
    let Ok(host) = HostAuthority::parse(value) else {
        return StatusCode::BAD_REQUEST.into_response();
    };
    if !trusted_hosts.allows(&host) {
        return StatusCode::MISDIRECTED_REQUEST.into_response();
    }
    next.run(request).await
}

#[cfg(test)]
mod tests {
    use axum::{
        Router,
        body::Body,
        http::{Request, StatusCode},
        routing::get,
    };
    use tower::ServiceExt;

    use super::{TrustedHosts, with_host_validation};

    #[test]
    fn parses_exact_hosts_and_bracketed_ipv6_with_optional_ports() {
        let hosts = TrustedHosts::parse("Example.COM, [2001:db8::1]:8443, 192.0.2.7")
            .expect("valid allowlist");
        assert!(hosts.allows(&super::HostAuthority::parse("example.com:9999").expect("host")));
        assert!(
            hosts.allows(&super::HostAuthority::parse("[2001:0db8::1]:8443").expect("IPv6 host"))
        );
        assert!(!hosts.allows(
            &super::HostAuthority::parse("example.com.attacker.test").expect("different host")
        ));
        assert!(
            !hosts
                .allows(&super::HostAuthority::parse("[2001:db8::1]:443").expect("different port"))
        );
    }

    #[test]
    fn rejects_ambiguous_or_malformed_host_entries() {
        for input in [
            "",
            ",example.test",
            "example.test,",
            "user@example.test",
            "example.test/path",
            "example.test:",
            "2001:db8::1",
            "example.test:70000",
        ] {
            assert!(TrustedHosts::parse(input).is_err(), "accepted {input:?}");
        }
    }

    #[tokio::test]
    async fn host_policy_rejects_missing_multiple_and_untrusted_host_values() {
        let app = with_host_validation(
            Router::new().route("/", get(|| async { "ok" })),
            TrustedHosts::parse("example.test").expect("host list"),
        );
        let missing = app
            .clone()
            .oneshot(Request::get("/").body(Body::empty()).expect("request"))
            .await
            .expect("router");
        assert_eq!(missing.status(), StatusCode::BAD_REQUEST);
        let mut request = Request::get("/").body(Body::empty()).expect("request");
        request.headers_mut().append(
            axum::http::header::HOST,
            "example.test".parse().expect("valid Host value"),
        );
        request.headers_mut().append(
            axum::http::header::HOST,
            "attacker.test".parse().expect("valid Host value"),
        );
        let duplicate = app.clone().oneshot(request).await.expect("router");
        assert_eq!(duplicate.status(), StatusCode::BAD_REQUEST);
        let untrusted = app
            .clone()
            .oneshot(
                Request::get("/")
                    .header("host", "example.test.attacker.test")
                    .body(Body::empty())
                    .expect("request"),
            )
            .await
            .expect("router");
        assert_eq!(untrusted.status(), StatusCode::MISDIRECTED_REQUEST);
        let accepted = app
            .oneshot(
                Request::get("/")
                    .header("host", "EXAMPLE.test:8080")
                    .header("x-forwarded-host", "attacker.test")
                    .body(Body::empty())
                    .expect("request"),
            )
            .await
            .expect("router");
        assert_eq!(accepted.status(), StatusCode::OK);
    }
}
