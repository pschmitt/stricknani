//! Android App Links: `/.well-known/assetlinks.json` tells Android that the Stricknani app may
//! open this server's project and yarn links directly (a link sent through a messenger opens the
//! app, not the browser). Every instance runs on its own domain, so the operator lists the signing
//! certificates of the app builds it trusts (`ANDROID_APP_LINK_FINGERPRINTS`); without them the
//! file is not served and links open in the browser.

use axum::{
    Router,
    http::header,
    response::{IntoResponse, Response},
    routing::get,
};

/// The package name of the Stricknani Android app.
pub const DEFAULT_PACKAGE: &str = "blue.anika.wolle";

/// The app (package and SHA-256 signing certificate fingerprints) allowed to open this server's
/// links.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct AppLinks {
    package: String,
    fingerprints: Vec<String>,
}

impl AppLinks {
    /// Reads `ANDROID_APP_LINK_FINGERPRINTS` (comma or space separated SHA-256 fingerprints, with
    /// or without colons) and `ANDROID_APP_PACKAGE`. None when no fingerprint is configured.
    pub fn parse(
        package: Option<&str>,
        fingerprints: Option<&str>,
    ) -> Result<Option<Self>, &'static str> {
        let fingerprints = fingerprints
            .unwrap_or_default()
            .split(|c: char| c == ',' || c.is_whitespace())
            .filter(|value| !value.is_empty())
            .map(normalize_fingerprint)
            .collect::<Option<Vec<_>>>()
            .ok_or("ANDROID_APP_LINK_FINGERPRINTS must list SHA-256 fingerprints (64 hex digits)")?;
        if fingerprints.is_empty() {
            return Ok(None);
        }
        let package = package
            .map(str::trim)
            .filter(|value| !value.is_empty())
            .unwrap_or(DEFAULT_PACKAGE);
        let valid_package = package.split('.').count() >= 2
            && package.split('.').all(|part| {
                part.chars()
                    .next()
                    .is_some_and(|first| first.is_ascii_alphabetic())
                    && part.chars().all(|c| c.is_ascii_alphanumeric() || c == '_')
            });
        if !valid_package {
            return Err("ANDROID_APP_PACKAGE must be an Android package name");
        }
        Ok(Some(Self {
            package: package.to_owned(),
            fingerprints,
        }))
    }

    fn statement(&self) -> serde_json::Value {
        serde_json::json!([{
            "relation": ["delegate_permission/common.handle_all_urls"],
            "target": {
                "namespace": "android_app",
                "package_name": self.package,
                "sha256_cert_fingerprints": self.fingerprints,
            }
        }])
    }
}

/// `AB:CD:...` in upper case, or None when it is not 32 bytes of hex.
fn normalize_fingerprint(value: &str) -> Option<String> {
    let hex: String = value.chars().filter(|c| *c != ':').collect();
    if hex.len() != 64 || !hex.chars().all(|c| c.is_ascii_hexdigit()) {
        return None;
    }
    let upper = hex.to_ascii_uppercase();
    Some(
        upper
            .as_bytes()
            .chunks(2)
            .map(|pair| String::from_utf8_lossy(pair).into_owned())
            .collect::<Vec<_>>()
            .join(":"),
    )
}

/// Serves `/.well-known/assetlinks.json` for [links].
pub fn router<S: Clone + Send + Sync + 'static>(links: AppLinks) -> Router<S> {
    let body = links.statement().to_string();
    Router::new().route(
        "/.well-known/assetlinks.json",
        get(move || {
            let body = body.clone();
            async move { asset_links(body) }
        }),
    )
}

fn asset_links(body: String) -> Response {
    (
        [
            (header::CONTENT_TYPE, "application/json"),
            (header::CACHE_CONTROL, "public, max-age=3600"),
        ],
        body,
    )
        .into_response()
}

#[cfg(test)]
mod tests {
    use super::*;
    use axum::body::{Body, to_bytes};
    use axum::http::{Request, StatusCode};
    use tower::ServiceExt;

    const FINGERPRINT: &str = "6150524c3401c232a8b582e873d692e71ad9c6726317fc6c69889040f60da11f";

    #[test]
    fn nothing_is_served_without_fingerprints() {
        assert_eq!(AppLinks::parse(None, None), Ok(None));
        assert_eq!(AppLinks::parse(None, Some(" , ")), Ok(None));
    }

    #[test]
    fn fingerprints_are_normalized_and_checked() {
        let links = AppLinks::parse(None, Some(&format!("{FINGERPRINT}, AA:{}", "BB".repeat(31))))
            .unwrap()
            .unwrap();
        assert_eq!(links.package, DEFAULT_PACKAGE);
        assert!(links.fingerprints[0].starts_with("61:50:52:4C:"));
        assert_eq!(links.fingerprints[0].len(), 95);
        assert!(links.fingerprints[1].starts_with("AA:BB:"));
        assert!(AppLinks::parse(None, Some("abc")).is_err());
        assert!(AppLinks::parse(Some("not a package"), Some(FINGERPRINT)).is_err());
    }

    #[tokio::test]
    async fn the_statement_names_the_app() {
        let links = AppLinks::parse(Some("org.example.app"), Some(FINGERPRINT))
            .unwrap()
            .unwrap();
        let response = router::<()>(links)
            .oneshot(
                Request::get("/.well-known/assetlinks.json")
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::OK);
        assert_eq!(
            response.headers()[header::CONTENT_TYPE],
            "application/json"
        );
        let body: serde_json::Value =
            serde_json::from_slice(&to_bytes(response.into_body(), 1 << 16).await.unwrap())
                .unwrap();
        assert_eq!(body[0]["target"]["package_name"], "org.example.app");
        assert_eq!(
            body[0]["relation"][0],
            "delegate_permission/common.handle_all_urls"
        );
        assert_eq!(
            body[0]["target"]["sha256_cert_fingerprints"][0]
                .as_str()
                .unwrap()
                .len(),
            95
        );
    }
}
