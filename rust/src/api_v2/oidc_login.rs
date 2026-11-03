//! API v2 browser and native OIDC login flows.

use std::net::SocketAddr;

use axum::{
    Extension, Json, Router,
    extract::{ConnectInfo, DefaultBodyLimit, State, rejection::JsonRejection},
    http::{HeaderMap, HeaderValue, StatusCode, header},
    response::{IntoResponse, Response},
    routing::{get, post},
};
use base64::{Engine, engine::general_purpose::URL_SAFE_NO_PAD};
use chrono::{DateTime, Utc};
use rand::{RngCore, rngs::OsRng};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use subtle::ConstantTimeEq;
use url::Url;
use zeroize::{Zeroize, Zeroizing};

use crate::{
    auth,
    oidc::{FlowBinding, FlowPurpose},
    oidc_http_state::{FlowTransport, OidcEndpoint, OidcFlowContext, OidcRuntimeError},
};

use super::{
    ApiV2State, SESSION_SECONDS, api_error, append_set_cookie, clear_cookie, session_cookie,
};

const FLOW_COOKIE: &str = "__Host-stricknani_v2_oidc_flow";
const MAX_TOKEN_NAME_BYTES: usize = 128;
const DEFAULT_TOKEN_NAME: &str = "Android (Authelia)";

pub(super) fn router() -> Router<ApiV2State> {
    Router::new()
        .route("/auth/oidc/start", post(start))
        .route("/auth/oidc/callback/authelia", get(callback))
        .route("/auth/oidc/exchange", post(exchange))
        .layer(DefaultBodyLimit::max(8 * 1024))
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct StartRequest {
    provider: String,
    client: String,
    return_to: Option<String>,
    redirect_uri: Option<String>,
    app_state: Option<String>,
    code_challenge: Option<String>,
    code_challenge_method: Option<String>,
}

#[derive(Serialize)]
struct StartEnvelope {
    data: StartData,
}

#[derive(Serialize)]
struct StartData {
    authorization_url: String,
    expires_at: String,
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct ExchangeRequest {
    code: String,
    code_verifier: String,
    redirect_uri: String,
    token_name: Option<String>,
}

#[derive(Serialize)]
struct ExchangeEnvelope {
    data: ExchangeData,
}

#[derive(Serialize)]
struct ExchangeData {
    token: String,
    expires_at: String,
    auth_origin: &'static str,
}

async fn start(
    State(state): State<ApiV2State>,
    headers: HeaderMap,
    peer: Option<Extension<ConnectInfo<SocketAddr>>>,
    request: Result<Json<StartRequest>, JsonRejection>,
) -> Response {
    let Some(runtime) = state.app.oidc.as_ref() else {
        return oidc_disabled();
    };
    let ip = client_ip(peer);
    if let Err(error) = runtime.allow_request(OidcEndpoint::Start, &ip) {
        return runtime_error(error);
    }
    let Json(request) = match request {
        Ok(request) => request,
        Err(_) => {
            return api_error(
                StatusCode::BAD_REQUEST,
                "invalid_json",
                "Request body is invalid",
            );
        }
    };
    if request.provider != runtime.provider_id() {
        return oidc_disabled();
    }

    let (binding, transport, cookie) = match request.client.as_str() {
        "browser" => {
            if !super::same_origin_request(&headers, state.app.secure_cookies) {
                return api_error(
                    StatusCode::FORBIDDEN,
                    "csrf_failed",
                    "Request origin was rejected",
                );
            }
            let Some(key) = state.csrf_key.as_ref() else {
                return api_error(
                    StatusCode::INTERNAL_SERVER_ERROR,
                    "internal_error",
                    "Request failed",
                );
            };
            let cookie_name = super::login_csrf_cookie_name(state.app.secure_cookies);
            let csrf_cookie = auth::cookie(&headers, cookie_name).unwrap_or_default();
            let csrf_header = super::header_text(&headers, "x-csrf-token").unwrap_or_default();
            if !super::valid_login_csrf(key, csrf_cookie, csrf_header) {
                return api_error(
                    StatusCode::FORBIDDEN,
                    "csrf_failed",
                    "CSRF token is missing or expired",
                );
            }
            if request.redirect_uri.is_some()
                || request.app_state.is_some()
                || request.code_challenge.is_some()
                || request.code_challenge_method.is_some()
            {
                return validation_error("client", "Browser start fields are invalid");
            }
            let return_path = request.return_to.unwrap_or_else(|| "/app/".to_owned());
            if !valid_app_return_path(&return_path) {
                return validation_error("return_to", "Return path is invalid");
            }
            let flow_cookie = match random_secret() {
                Ok(cookie) => cookie,
                Err(()) => return internal_error(),
            };
            let binding = FlowBinding {
                provider_id: request.provider,
                purpose: FlowPurpose::Login,
                principal_hash: crate::oidc::principal_hash(flow_cookie.as_bytes()),
                native_redirect_uri: None,
                return_path: Some(return_path),
                app_code_challenge: None,
                app_state: None,
                max_auth_age_secs: None,
            };
            (binding, FlowTransport::Browser, Some(flow_cookie))
        }
        "android" => {
            if request.return_to.is_some()
                || request.code_challenge_method.as_deref() != Some("S256")
            {
                return validation_error("client", "Native start fields are invalid");
            }
            let (Some(redirect_uri), Some(app_state), Some(challenge)) = (
                request.redirect_uri,
                request.app_state,
                request.code_challenge,
            ) else {
                return validation_error("redirect_uri", "Native start fields are required");
            };
            if !valid_s256_challenge(&challenge) || !valid_app_state(&app_state) {
                return validation_error("code_challenge", "Native PKCE fields are invalid");
            }
            let binding = FlowBinding {
                provider_id: request.provider,
                purpose: FlowPurpose::Login,
                principal_hash: crate::oidc::principal_hash(app_state.as_bytes()),
                native_redirect_uri: Some(redirect_uri),
                return_path: None,
                app_code_challenge: Some(challenge),
                app_state: Some(app_state),
                max_auth_age_secs: None,
            };
            (binding, FlowTransport::Native, None)
        }
        _ => return validation_error("client", "Client must be browser or android"),
    };
    let (authorization_url, _provider_state, expires_at) = match runtime
        .start(binding, OidcFlowContext::default(), transport)
        .await
    {
        Ok(started) => started,
        Err(error) => return runtime_error(error),
    };
    let mut response = Json(StartEnvelope {
        data: StartData {
            authorization_url: authorization_url.to_string(),
            expires_at: expires_at.to_rfc3339_opts(chrono::SecondsFormat::Millis, true),
        },
    })
    .into_response();
    if let Some(cookie) = cookie {
        append_set_cookie(&mut response, flow_cookie(&cookie));
    }
    response
}

#[derive(Deserialize)]
struct CallbackQuery {
    code: Option<String>,
    state: Option<String>,
    iss: Option<String>,
    error: Option<String>,
}

async fn callback(
    State(state): State<ApiV2State>,
    headers: HeaderMap,
    peer: Option<Extension<ConnectInfo<SocketAddr>>>,
    query: Result<axum::extract::Query<CallbackQuery>, axum::extract::rejection::QueryRejection>,
) -> Response {
    let Some(runtime) = state.app.oidc.as_ref() else {
        return oidc_disabled();
    };
    let axum::extract::Query(query) = match query {
        Ok(query) => query,
        Err(_) => return callback_error_response(invalid_flow(), state.app.secure_cookies),
    };
    let Some(provider_state) = query.state.as_deref() else {
        return callback_error_response(invalid_flow(), state.app.secure_cookies);
    };
    let pending = match runtime.take_flow(provider_state) {
        Ok(flow) => flow,
        Err(error) => {
            return callback_error_response(runtime_error(error), state.app.secure_cookies);
        }
    };
    let ip = client_ip(peer);
    let expected_binding = pending.binding.clone();
    let expected_context = pending.context.clone();
    let transport = pending.transport;
    if let Err(error) = runtime.allow_request(OidcEndpoint::Callback, &ip) {
        runtime.discard_flow(provider_state).await;
        let mut response = dispatch_callback_failure(
            &state,
            &headers,
            expected_binding,
            expected_context,
            transport,
        )
        .await;
        if let OidcRuntimeError::RateLimited(retry) = error
            && let Ok(value) = HeaderValue::from_str(&retry.to_string())
        {
            response.headers_mut().insert(header::RETRY_AFTER, value);
        }
        return response;
    }
    let browser_cookie = match transport {
        FlowTransport::Browser => auth::cookie(&headers, FLOW_COOKIE),
        FlowTransport::Native => None,
    };
    let callback_code = if query.error.is_some() {
        "provider-error"
    } else {
        query.code.as_deref().unwrap_or("missing-code")
    };
    let completed = runtime
        .verify_callback(
            pending,
            callback_code,
            provider_state,
            query.iss.as_deref(),
            browser_cookie,
        )
        .await;
    let (binding, context, identity, transport) = match completed {
        Ok(completed) if query.error.is_none() && query.code.is_some() => completed,
        Ok(_) | Err(_) => {
            return dispatch_callback_failure(
                &state,
                &headers,
                expected_binding,
                expected_context,
                transport,
            )
            .await;
        }
    };
    if !matches!(binding.purpose, FlowPurpose::Login) {
        return super::oidc_account::complete_oidc_callback(
            &state,
            &headers,
            binding,
            context,
            Some(identity),
            transport,
            None,
        )
        .await;
    }
    match crate::oidc_identity_service::lookup_login_identity(&state.app.pool, &identity).await {
        Ok(Some(user_id)) => match transport {
            FlowTransport::Browser => browser_success(&state, &binding, &identity, user_id).await,
            FlowTransport::Native => {
                match runtime.create_native_grant(binding, context, identity, user_id) {
                    Ok(grant) => native_success(grant),
                    Err(_) => callback_failure(
                        &expected_binding,
                        FlowTransport::Native,
                        Some("failed"),
                        state.app.secure_cookies,
                    ),
                }
            }
        },
        Ok(None) => callback_failure(
            &binding,
            transport,
            Some("unlinked"),
            state.app.secure_cookies,
        ),
        Err(_) => callback_failure(
            &expected_binding,
            transport,
            Some("failed"),
            state.app.secure_cookies,
        ),
    }
}

async fn dispatch_callback_failure(
    state: &ApiV2State,
    headers: &HeaderMap,
    binding: FlowBinding,
    context: OidcFlowContext,
    transport: FlowTransport,
) -> Response {
    if !matches!(binding.purpose, FlowPurpose::Login) {
        super::oidc_account::complete_oidc_callback(
            state,
            headers,
            binding,
            context,
            None,
            transport,
            Some("failed"),
        )
        .await
    } else {
        callback_failure(
            &binding,
            transport,
            Some("failed"),
            state.app.secure_cookies,
        )
    }
}

async fn browser_success(
    state: &ApiV2State,
    binding: &FlowBinding,
    identity: &crate::oidc::ValidatedIdentity,
    user_id: i64,
) -> Response {
    let now = Utc::now().timestamp();
    let expires_at = now + SESSION_SECONDS;
    let name = "Browser session (Authelia)";
    let providers = state
        .app
        .oidc
        .as_ref()
        .map(|runtime| runtime.enabled_provider_ids())
        .unwrap_or_default();
    let token = match crate::oidc_identity_service::mint_login_token(
        &state.app.pool,
        identity,
        &providers,
        name,
        "browser",
        now,
        expires_at,
    )
    .await
    {
        Ok(token) if token.user_id == user_id => token,
        _ => {
            return callback_failure(
                binding,
                FlowTransport::Browser,
                None,
                state.app.secure_cookies,
            );
        }
    };
    let target = binding
        .return_path
        .as_deref()
        .filter(|path| valid_app_return_path(path))
        .unwrap_or("/app/");
    let mut response = redirect_response(target);
    append_set_cookie(
        &mut response,
        session_cookie(&token.token, state.app.secure_cookies),
    );
    append_set_cookie(&mut response, clear_cookie(FLOW_COOKIE, true));
    response
}

fn native_success(grant: crate::oidc_http_state::NativeGrant) -> Response {
    let Ok(mut target) = Url::parse(&grant.redirect_uri) else {
        return invalid_flow();
    };
    target
        .query_pairs_mut()
        .append_pair("code", &grant.code)
        .append_pair("state", &grant.app_state)
        .append_pair("flow", grant.flow_kind);
    redirect_response(target.as_str())
}

fn callback_failure(
    binding: &FlowBinding,
    transport: FlowTransport,
    code: Option<&str>,
    _secure_cookies: bool,
) -> Response {
    match transport {
        FlowTransport::Browser => {
            let mut target = binding
                .return_path
                .as_deref()
                .filter(|path| valid_app_return_path(path))
                .and_then(|path| Url::parse(&format!("https://local.invalid{path}")).ok())
                .unwrap_or_else(|| {
                    Url::parse("https://local.invalid/app/login").expect("static URL")
                });
            target
                .query_pairs_mut()
                .append_pair("oidc_error", code.unwrap_or("failed"));
            let mut response = redirect_response(&format!(
                "{}{}",
                target.path(),
                target
                    .query()
                    .map(|query| format!("?{query}"))
                    .unwrap_or_default()
            ));
            append_set_cookie(&mut response, clear_cookie(FLOW_COOKIE, true));
            response
        }
        FlowTransport::Native => {
            let Some(mut target) = binding
                .native_redirect_uri
                .as_deref()
                .and_then(|uri| Url::parse(uri).ok())
            else {
                return invalid_flow();
            };
            let Some(app_state) = binding.app_state.as_deref() else {
                return invalid_flow();
            };
            target
                .query_pairs_mut()
                .append_pair("state", app_state)
                .append_pair("flow", flow_name(binding.purpose))
                .append_pair("error", "oidc_failed");
            redirect_response(target.as_str())
        }
    }
}

fn flow_name(purpose: FlowPurpose) -> &'static str {
    match purpose {
        FlowPurpose::Login => "login",
        FlowPurpose::Link { .. } => "link",
        FlowPurpose::Reauth { .. } => "reauth",
    }
}

fn callback_error_response(mut response: Response, _secure_cookies: bool) -> Response {
    response.headers_mut().insert(
        header::REFERRER_POLICY,
        HeaderValue::from_static("no-referrer"),
    );
    response
        .headers_mut()
        .insert(header::CACHE_CONTROL, HeaderValue::from_static("no-store"));
    response
}

async fn exchange(
    State(state): State<ApiV2State>,
    peer: Option<Extension<ConnectInfo<SocketAddr>>>,
    request: Result<Json<ExchangeRequest>, JsonRejection>,
) -> Response {
    let Some(runtime) = state.app.oidc.as_ref() else {
        return oidc_disabled();
    };
    let Json(mut request) = match request {
        Ok(request) => request,
        Err(_) => {
            return api_error(
                StatusCode::BAD_REQUEST,
                "invalid_json",
                "Request body is invalid",
            );
        }
    };
    let code = std::mem::take(&mut request.code);
    let grant = match runtime.consume_grant_for(&code, FlowPurpose::Login) {
        Ok(grant) => grant,
        Err(error) => return runtime_error(error),
    };
    let ip = client_ip(peer);
    if let Err(error) = runtime.allow_request(OidcEndpoint::Exchange, &ip) {
        return runtime_error(error);
    }
    if grant.expires_at <= Utc::now().timestamp()
        || grant.context.action_target.is_some()
        || !valid_verifier(&request.code_verifier)
        || request.redirect_uri
            != grant
                .binding
                .native_redirect_uri
                .as_deref()
                .unwrap_or_default()
        || !valid_app_proof(
            &request.code_verifier,
            grant.binding.app_code_challenge.as_deref(),
        )
    {
        return invalid_flow();
    }
    if request.redirect_uri.len() > 2048 {
        return validation_error("redirect_uri", "Redirect URI is too long");
    }
    let name = request
        .token_name
        .take()
        .map(|name| name.trim().to_owned())
        .filter(|name| !name.is_empty())
        .unwrap_or_else(|| DEFAULT_TOKEN_NAME.to_owned());
    if name.len() > MAX_TOKEN_NAME_BYTES {
        return validation_error("token_name", "Token name is too long");
    }
    let now = Utc::now().timestamp();
    let expires_at = now + runtime.pat_lifetime_seconds();
    let providers = runtime.enabled_provider_ids();
    let minted = match crate::oidc_identity_service::mint_login_token(
        &state.app.pool,
        &grant.identity,
        &providers,
        &name,
        "api",
        now,
        expires_at,
    )
    .await
    {
        Ok(minted) if minted.user_id == grant.user_id => minted,
        Ok(_) | Err(crate::oidc_identity_service::OidcIdentityError::NotFound) => {
            return api_error(
                StatusCode::FORBIDDEN,
                "oidc_identity_unlinked",
                "This identity is not linked to an active account",
            );
        }
        Err(crate::oidc_identity_service::OidcIdentityError::TokenRateLimited(retry)) => {
            let mut response = api_error(
                StatusCode::TOO_MANY_REQUESTS,
                "rate_limited",
                "Too many token requests",
            );
            if let Ok(value) = HeaderValue::from_str(&retry.to_string()) {
                response.headers_mut().insert(header::RETRY_AFTER, value);
            }
            return response;
        }
        Err(_) => return internal_error(),
    };
    let expiry = DateTime::from_timestamp(minted.expires_at, 0).unwrap_or_else(Utc::now);
    Json(ExchangeEnvelope {
        data: ExchangeData {
            token: minted.token.to_string(),
            expires_at: expiry.to_rfc3339_opts(chrono::SecondsFormat::Millis, true),
            auth_origin: "oidc",
        },
    })
    .into_response()
}

fn valid_app_return_path(value: &str) -> bool {
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

fn valid_app_state(value: &str) -> bool {
    !value.is_empty()
        && value.len() <= 256
        && value
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || b"-._~".contains(&byte))
}

fn valid_s256_challenge(value: &str) -> bool {
    value.len() == 43
        && value
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || byte == b'-' || byte == b'_')
}

fn valid_verifier(value: &str) -> bool {
    (43..=128).contains(&value.len())
        && value
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || b"-._~".contains(&byte))
}

fn valid_app_proof(verifier: &str, expected: Option<&str>) -> bool {
    let Some(expected) = expected else {
        return false;
    };
    let actual = URL_SAFE_NO_PAD.encode(Sha256::digest(verifier.as_bytes()));
    bool::from(actual.as_bytes().ct_eq(expected.as_bytes()))
}

fn random_secret() -> Result<Zeroizing<String>, ()> {
    let mut bytes = [0_u8; 32];
    OsRng.try_fill_bytes(&mut bytes).map_err(|_| ())?;
    let encoded = URL_SAFE_NO_PAD.encode(bytes);
    bytes.zeroize();
    Ok(Zeroizing::new(encoded))
}

fn flow_cookie(value: &str) -> String {
    format!("{FLOW_COOKIE}={value}; Path=/; HttpOnly; Secure; SameSite=Lax; Max-Age=600")
}

fn redirect_response(target: &str) -> Response {
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

fn client_ip(peer: Option<Extension<ConnectInfo<SocketAddr>>>) -> String {
    peer.map(|Extension(ConnectInfo(address))| address.ip().to_string())
        .unwrap_or_else(|| "unknown".to_owned())
}

fn runtime_error(error: OidcRuntimeError) -> Response {
    match error {
        OidcRuntimeError::RateLimited(retry) => {
            let mut response = api_error(
                StatusCode::TOO_MANY_REQUESTS,
                "rate_limited",
                "Too many OIDC requests",
            );
            if let Ok(value) = HeaderValue::from_str(&retry.to_string()) {
                response.headers_mut().insert(header::RETRY_AFTER, value);
            }
            response
        }
        OidcRuntimeError::Provider => api_error(
            StatusCode::SERVICE_UNAVAILABLE,
            "oidc_unavailable",
            "Identity provider is unavailable",
        ),
        OidcRuntimeError::InvalidFlow => invalid_flow(),
        OidcRuntimeError::Configuration => internal_error(),
        OidcRuntimeError::Capacity | OidcRuntimeError::Unavailable => api_error(
            StatusCode::SERVICE_UNAVAILABLE,
            "oidc_unavailable",
            "Identity provider is unavailable",
        ),
    }
}

fn oidc_disabled() -> Response {
    api_error(
        StatusCode::NOT_FOUND,
        "oidc_disabled",
        "Identity provider is unavailable",
    )
}

fn invalid_flow() -> Response {
    api_error(
        StatusCode::BAD_REQUEST,
        "invalid_oidc_flow",
        "OIDC flow is invalid or expired",
    )
}

fn internal_error() -> Response {
    api_error(
        StatusCode::INTERNAL_SERVER_ERROR,
        "internal_error",
        "Request failed",
    )
}

fn validation_error(field: &'static str, message: &'static str) -> Response {
    super::api_error_fields(
        StatusCode::UNPROCESSABLE_ENTITY,
        "validation_error",
        "Check the submitted fields",
        std::collections::BTreeMap::from([(field, message)]),
    )
}
