//! Helpers shared by the OIDC login and account route modules.

use std::net::SocketAddr;

use axum::{
    Extension,
    extract::ConnectInfo,
    http::{HeaderValue, StatusCode, header},
    response::{IntoResponse, Response},
};
use base64::{Engine, engine::general_purpose::URL_SAFE_NO_PAD};
use rand::{TryRng, rngs::SysRng};
use url::Url;
use zeroize::{Zeroize, Zeroizing};

use super::api_error;

pub(super) const FLOW_COOKIE: &str = "__Host-stricknani_v2_oidc_flow";
pub(super) const FLOW_TTL_SECONDS: i64 = 600;

pub(super) fn valid_app_return_path(value: &str) -> bool {
    value.len() <= 2048
        && value.starts_with("/app/")
        && !value.starts_with("//")
        && !value.contains('\\')
        && !value.contains('#')
        && !value.bytes().any(|byte| byte.is_ascii_control())
        && Url::parse(&format!("https://local.invalid{value}")).is_ok_and(|url| {
            url.host_str() == Some("local.invalid")
                && url.path().starts_with("/app/")
                && !url.path_segments().is_some_and(|segments| {
                    segments.into_iter().any(|part| part == "." || part == "..")
                })
        })
}

pub(super) fn valid_app_state(value: &str) -> bool {
    !value.is_empty()
        && value.len() <= 256
        && value
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || b"-._~".contains(&byte))
}

pub(super) fn valid_s256_challenge(value: &str) -> bool {
    value.len() == 43
        && value
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || byte == b'-' || byte == b'_')
}

pub(super) fn random_secret() -> Result<Zeroizing<String>, ()> {
    let mut bytes = [0_u8; 32];
    SysRng.try_fill_bytes(&mut bytes).map_err(|_| ())?;
    let encoded = URL_SAFE_NO_PAD.encode(bytes);
    bytes.zeroize();
    Ok(Zeroizing::new(encoded))
}

pub(super) fn client_ip(peer: Option<Extension<ConnectInfo<SocketAddr>>>) -> String {
    peer.map(|Extension(ConnectInfo(address))| address.ip().to_string())
        .unwrap_or_else(|| "unknown".to_owned())
}

pub(super) fn redirect_response(target: &str) -> Response {
    let mut response = StatusCode::SEE_OTHER.into_response();
    if let Ok(value) = HeaderValue::from_str(target) {
        response.headers_mut().insert(header::LOCATION, value);
    }
    response.headers_mut().insert(
        header::REFERRER_POLICY,
        HeaderValue::from_static("no-referrer"),
    );
    response
}

pub(super) fn oidc_disabled() -> Response {
    api_error(
        StatusCode::NOT_FOUND,
        "oidc_disabled",
        "Identity provider is unavailable",
    )
}

pub(super) fn invalid_flow() -> Response {
    api_error(
        StatusCode::BAD_REQUEST,
        "invalid_oidc_flow",
        "OIDC flow is invalid or expired",
    )
}

pub(super) fn internal_error() -> Response {
    api_error(
        StatusCode::INTERNAL_SERVER_ERROR,
        "internal_error",
        "Request failed",
    )
}

pub(super) fn validation_error(field: &'static str, message: &'static str) -> Response {
    super::api_error_fields(
        StatusCode::UNPROCESSABLE_ENTITY,
        "validation_error",
        "Check the submitted fields",
        std::collections::BTreeMap::from([(field, message)]),
    )
}

pub(super) fn flow_cookie(value: &str) -> String {
    format!(
        "{FLOW_COOKIE}={value}; Path=/; HttpOnly; Secure; SameSite=Lax; Max-Age={FLOW_TTL_SECONDS}"
    )
}
