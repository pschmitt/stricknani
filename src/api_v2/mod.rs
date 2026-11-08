//! Cookie-authenticated API v2 routes for the same-origin browser client.

use std::{
    collections::BTreeMap,
    net::SocketAddr,
    time::{Duration, SystemTime, UNIX_EPOCH},
};

use axum::{
    Json, Router,
    body::Body,
    extract::{ConnectInfo, DefaultBodyLimit, Extension, State, rejection::JsonRejection},
    http::{HeaderMap, HeaderName, HeaderValue, Request, StatusCode, header},
    middleware::{self, Next},
    response::{IntoResponse, Response},
    routing::{get, post},
};
use hmac::{Hmac, KeyInit, Mac};
use rand::{TryRng, rngs::SysRng};
use serde::{Deserialize, Serialize};
use sha2::Sha256;
use sqlx::SqlitePool;
use std::{path::PathBuf, sync::Arc};
use subtle::ConstantTimeEq;
use zeroize::Zeroizing;

use crate::{auth, db::AppState, import_service::ImportContext};

mod access;
mod account;
mod activity;
mod admin;
mod admin_resources;
mod archive;
mod audit;
mod categories;
mod imports;
mod media;
mod meta;
mod native_auth;
mod ocr;
mod oidc_account;
mod oidc_login;
mod project_preview;
mod project_timer;
mod projects;
mod push;
mod resource_history;
mod sharing;
mod signup;
mod sync;
mod telemetry;
mod tools;
mod yarns;

use self::{access::OwnerRequestLimiter, imports::PreviewLimiter};

const SESSION_SECONDS: i64 = 14 * 24 * 60 * 60;
const LOGIN_CSRF_SECONDS: i64 = 10 * 60;
const LOGIN_CSRF_COOKIE: &str = "stricknani_v2_login_csrf";
const HOST_LOGIN_CSRF_COOKIE: &str = "__Host-stricknani_v2_login_csrf";
const LOGIN_CSRF_DOMAIN: &[u8] = b"stricknani api v2 login csrf\0";

#[derive(Clone)]
struct ApiV2State {
    app: AppState,
    csrf_key: Option<[u8; 32]>,
    ai_enabled: bool,
    imports: Option<ImportContext>,
    ocr_service: Option<crate::ocr::OcrService>,
    import_preview_limiter: PreviewLimiter,
    import_image_limiter: OwnerRequestLimiter,
    ocr_limiter: OwnerRequestLimiter,
    project_preview_limiter: OwnerRequestLimiter,
    project_preview_slots: Arc<tokio::sync::Semaphore>,
    sync_projection_slots: Arc<tokio::sync::Semaphore>,
    exports: crate::export::ExportLimiter,
    export_tickets: crate::export::ExportTickets,
    restores: crate::restore::RestoreLimiter,
    restore_limit: u64,
}

/// Build API v2 routes without optional import services.
///
/// The boolean is an availability hint only; the AI capability remains false
/// until the composed application supplies an actual configured AI client.
/// Use the application constructor to mount import previews with their parser
/// worker and optional AI client.
pub fn router(app: AppState, ai_enabled: bool) -> Router {
    router_with_context(
        app,
        ai_enabled,
        None,
        None,
        crate::restore::MAX_ARCHIVE_BYTES,
        crate::export::TICKET_TTL,
    )
}

/// Like [`router`], with a smaller cap on the restore upload (`POST /account/import`), so that
/// tests can exercise the `413` path without a 2 GiB body.
#[doc(hidden)]
pub fn router_with_restore_limit(app: AppState, ai_enabled: bool, restore_limit: u64) -> Router {
    router_with_context(
        app,
        ai_enabled,
        None,
        None,
        restore_limit,
        crate::export::TICKET_TTL,
    )
}

/// Like [`router`], with a different lifetime for the download tickets of encrypted exports,
/// so that tests can exercise expiry.
#[doc(hidden)]
pub fn router_with_export_ticket_ttl(app: AppState, ai_enabled: bool, ttl: Duration) -> Router {
    router_with_context(
        app,
        ai_enabled,
        None,
        None,
        crate::restore::MAX_ARCHIVE_BYTES,
        ttl,
    )
}

pub(crate) fn router_with_optional_services(
    app: AppState,
    worker_path: PathBuf,
    ai: Option<Arc<crate::ai::AiClient>>,
    ocr_service: Option<crate::ocr::OcrService>,
) -> Router {
    let ai_enabled = ai.is_some();
    router_with_context(
        app,
        ai_enabled,
        Some(ImportContext::new(worker_path, ai)),
        ocr_service,
        crate::restore::MAX_ARCHIVE_BYTES,
        crate::export::TICKET_TTL,
    )
}

fn router_with_context(
    app: AppState,
    ai_enabled: bool,
    imports: Option<ImportContext>,
    ocr_service: Option<crate::ocr::OcrService>,
    restore_limit: u64,
    ticket_ttl: Duration,
) -> Router {
    let mut csrf_key = [0_u8; 32];
    let csrf_key = SysRng.try_fill_bytes(&mut csrf_key).ok().map(|()| csrf_key);
    let state = ApiV2State {
        app,
        csrf_key,
        ai_enabled,
        imports,
        ocr_service,
        import_preview_limiter: PreviewLimiter::default(),
        import_image_limiter: OwnerRequestLimiter::new(150, Duration::from_secs(10 * 60), 4096),
        ocr_limiter: OwnerRequestLimiter::new(5, Duration::from_secs(5 * 60), 4096),
        project_preview_limiter: OwnerRequestLimiter::new(20, Duration::from_secs(60), 4096),
        project_preview_slots: Arc::new(tokio::sync::Semaphore::new(4)),
        sync_projection_slots: Arc::new(tokio::sync::Semaphore::new(4)),
        exports: crate::export::ExportLimiter::default(),
        export_tickets: crate::export::ExportTickets::new(ticket_ttl),
        restores: crate::restore::RestoreLimiter::default(),
        restore_limit,
    };
    Router::new()
        .route("/session", get(session))
        .route("/auth/login", post(login))
        .route("/auth/logout", post(logout))
        .layer(DefaultBodyLimit::max(8 * 1024))
        .merge(native_auth::router())
        .merge(oidc_account::router())
        .merge(oidc_login::router())
        .merge(projects::router())
        .merge(resource_history::router())
        .merge(sharing::router())
        .merge(activity::router())
        .merge(sync::router())
        .merge(project_preview::router())
        .merge(project_timer::router())
        .merge(push::router())
        .merge(yarns::router())
        .merge(categories::router())
        .merge(media::router())
        .merge(imports::router())
        .merge(ocr::router())
        .merge(archive::router())
        .merge(meta::router())
        .merge(telemetry::router())
        .merge(tools::router())
        .merge(account::router())
        .merge(admin::router())
        .merge(admin_resources::router())
        .merge(audit::router())
        .merge(signup::router())
        .with_state(state)
        .layer(middleware::from_fn(no_store))
}

#[derive(Serialize)]
struct Envelope<T> {
    data: T,
}

#[derive(Serialize)]
struct SessionData {
    user: Option<UserSummary>,
    csrf_token: String,
    capabilities: Capabilities,
    error_reporting: telemetry::ErrorReportingInfo,
    locale: &'static str,
}

#[derive(Serialize)]
struct UserSummary {
    id: i64,
    email: String,
    is_admin: bool,
    avatar_url: Option<String>,
    avatar_source: Option<&'static str>,
}

#[derive(Serialize)]
struct Capabilities {
    signup: bool,
    external_imports: bool,
    ai: bool,
    ocr: bool,
    archive: bool,
    media_upload: bool,
    media_delete: bool,
    import_preview: bool,
    archive_state: bool,
    account_actions: bool,
    admin_actions: bool,
}

impl Capabilities {
    fn from_state(state: &ApiV2State, user: Option<&UserSummary>) -> Self {
        let authenticated = user.is_some();
        Self {
            signup: state.app.signup_enabled,
            external_imports: state.app.external_imports,
            ai: state.ai_enabled
                && state
                    .imports
                    .as_ref()
                    .and_then(|imports| imports.ai.as_ref())
                    .is_some(),
            ocr: state.app.ocr_enabled && state.ocr_service.is_some(),
            archive: state.app.archive_enabled,
            media_upload: authenticated && state.app.media_root.is_some(),
            media_delete: authenticated && state.app.media_root.is_some(),
            import_preview: authenticated && state.imports.is_some(),
            archive_state: authenticated && state.app.archive_enabled,
            account_actions: authenticated,
            admin_actions: user.is_some_and(|user| user.is_admin),
        }
    }
}

#[derive(Deserialize)]
struct LoginRequest {
    email: String,
    password: String,
}

#[derive(Deserialize)]
struct EmptyRequest {}

#[derive(Serialize)]
struct ApiErrorBody {
    error: ApiError,
}

#[derive(Serialize)]
struct ApiError {
    code: &'static str,
    message: &'static str,
    #[serde(skip_serializing_if = "Option::is_none")]
    fields: Option<BTreeMap<&'static str, &'static str>>,
}

fn api_error(status: StatusCode, code: &'static str, message: &'static str) -> Response {
    (
        status,
        Json(ApiErrorBody {
            error: ApiError {
                code,
                message,
                fields: None,
            },
        }),
    )
        .into_response()
}

fn api_error_fields(
    status: StatusCode,
    code: &'static str,
    message: &'static str,
    fields: BTreeMap<&'static str, &'static str>,
) -> Response {
    (
        status,
        Json(ApiErrorBody {
            error: ApiError {
                code,
                message,
                fields: Some(fields),
            },
        }),
    )
        .into_response()
}

async fn no_store(request: Request<Body>, next: Next) -> Response {
    let mut response = next.run(request).await;
    response
        .headers_mut()
        .insert(header::CACHE_CONTROL, HeaderValue::from_static("no-store"));
    response
}

async fn session(State(state): State<ApiV2State>, headers: HeaderMap) -> Response {
    let user = match session_user(
        &state.app.pool,
        &headers,
        state.app.secure_cookies,
        state.app.gravatar.enabled,
    )
    .await
    {
        Ok(Some((user, token))) => {
            let csrf_token = crate::web::csrf(&token);
            let capabilities = Capabilities::from_state(&state, Some(&user));
            let error_reporting = telemetry::ErrorReportingInfo::load(&state, Some(user.id)).await;
            return Json(Envelope {
                data: SessionData {
                    user: Some(user),
                    csrf_token,
                    capabilities,
                    error_reporting,
                    locale: locale(&headers),
                },
            })
            .into_response();
        }
        Ok(None) => None,
        Err(_) => {
            return api_error(
                StatusCode::INTERNAL_SERVER_ERROR,
                "internal_error",
                "Request failed",
            );
        }
    };

    let Some(token) = signed_login_csrf(&state) else {
        return api_error(
            StatusCode::INTERNAL_SERVER_ERROR,
            "internal_error",
            "Request failed",
        );
    };
    let capabilities = Capabilities::from_state(&state, user.as_ref());
    let error_reporting = telemetry::ErrorReportingInfo::load(&state, None).await;
    let mut response = Json(Envelope {
        data: SessionData {
            user,
            csrf_token: token.clone(),
            capabilities,
            error_reporting,
            locale: locale(&headers),
        },
    })
    .into_response();
    append_set_cookie(
        &mut response,
        csrf_cookie(&token, state.app.secure_cookies, LOGIN_CSRF_SECONDS),
    );
    if auth::cookie(
        &headers,
        auth::session_cookie_name(state.app.secure_cookies),
    )
    .is_some()
    {
        append_set_cookie(
            &mut response,
            clear_cookie(
                auth::session_cookie_name(state.app.secure_cookies),
                state.app.secure_cookies,
            ),
        );
    }
    response
}

async fn login(
    State(state): State<ApiV2State>,
    headers: HeaderMap,
    peer: Option<Extension<ConnectInfo<SocketAddr>>>,
    request: Result<Json<LoginRequest>, JsonRejection>,
) -> Response {
    if !same_origin_request(&headers, state.app.secure_cookies) {
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
    let supplied = header_text(&headers, "x-csrf-token").unwrap_or_default();
    let cookie_name = login_csrf_cookie_name(state.app.secure_cookies);
    let cookie = auth::cookie(&headers, cookie_name).unwrap_or_default();
    if !valid_login_csrf(key, cookie, supplied) {
        return api_error(
            StatusCode::FORBIDDEN,
            "csrf_failed",
            "CSRF token is missing or expired",
        );
    }
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
    let password = Zeroizing::new(std::mem::take(&mut request.password));
    let ip = peer
        .map(|Extension(ConnectInfo(address))| address.ip().to_string())
        .unwrap_or_else(|| "unknown".to_owned());
    let token =
        match auth::mint_browser_token(&state.app, &ip, &request.email, password, SESSION_SECONDS)
            .await
        {
            Ok(token) => token,
            Err(auth::PasswordTokenError::InvalidRequest) => {
                return api_error(
                    StatusCode::UNPROCESSABLE_ENTITY,
                    "validation_error",
                    "Email or password is invalid",
                );
            }
            Err(auth::PasswordTokenError::RateLimited) => {
                return with_retry_after(api_error(
                    StatusCode::TOO_MANY_REQUESTS,
                    "rate_limited",
                    "Too many login attempts",
                ));
            }
            Err(auth::PasswordTokenError::InvalidCredentials) => {
                return api_error(
                    StatusCode::UNAUTHORIZED,
                    "invalid_credentials",
                    "Email or password is incorrect",
                );
            }
            Err(auth::PasswordTokenError::Internal) => {
                return api_error(
                    StatusCode::INTERNAL_SERVER_ERROR,
                    "internal_error",
                    "Request failed",
                );
            }
            Err(auth::PasswordTokenError::Database) => {
                return api_error(
                    StatusCode::INTERNAL_SERVER_ERROR,
                    "internal_error",
                    "Request failed",
                );
            }
        };
    let user = match load_user(&state.app.pool, token.user_id, state.app.gravatar.enabled).await {
        Ok(Some(user)) => user,
        _ => {
            return api_error(
                StatusCode::INTERNAL_SERVER_ERROR,
                "internal_error",
                "Request failed",
            );
        }
    };
    let csrf_token = crate::web::csrf(&token.token);
    let capabilities = Capabilities::from_state(&state, Some(&user));
    let error_reporting = telemetry::ErrorReportingInfo::load(&state, Some(user.id)).await;
    let mut response = Json(Envelope {
        data: SessionData {
            user: Some(user),
            csrf_token,
            capabilities,
            error_reporting,
            locale: locale(&headers),
        },
    })
    .into_response();
    append_set_cookie(
        &mut response,
        session_cookie(&token.token, state.app.secure_cookies),
    );
    append_set_cookie(
        &mut response,
        clear_cookie(cookie_name, state.app.secure_cookies),
    );
    response
}

async fn logout(
    State(state): State<ApiV2State>,
    headers: HeaderMap,
    request: Result<Json<EmptyRequest>, JsonRejection>,
) -> Response {
    if !same_origin_request(&headers, state.app.secure_cookies) {
        return api_error(
            StatusCode::FORBIDDEN,
            "csrf_failed",
            "Request origin was rejected",
        );
    }
    if request.is_err() {
        return api_error(
            StatusCode::BAD_REQUEST,
            "invalid_json",
            "Request body is invalid",
        );
    }
    let cookie_name = auth::session_cookie_name(state.app.secure_cookies);
    let Some(token) = auth::cookie(&headers, cookie_name) else {
        return clear_session_cookie(
            api_error(
                StatusCode::UNAUTHORIZED,
                "session_expired",
                "Your session has expired",
            ),
            state.app.secure_cookies,
        );
    };
    match auth::browser_user(&state.app.pool, &headers, state.app.secure_cookies).await {
        Ok(_) => {}
        Err(auth::AuthError::Unauthorized) => {
            return clear_session_cookie(
                api_error(
                    StatusCode::UNAUTHORIZED,
                    "session_expired",
                    "Your session has expired",
                ),
                state.app.secure_cookies,
            );
        }
        Err(_) => {
            return api_error(
                StatusCode::INTERNAL_SERVER_ERROR,
                "internal_error",
                "Request failed",
            );
        }
    }
    let supplied = header_text(&headers, "x-csrf-token").unwrap_or_default();
    if !bool::from(
        crate::web::csrf(token)
            .as_bytes()
            .ct_eq(supplied.as_bytes()),
    ) {
        return api_error(
            StatusCode::FORBIDDEN,
            "csrf_failed",
            "CSRF token is missing or invalid",
        );
    }
    if sqlx::query("UPDATE api_tokens SET revoked_at = unixepoch() WHERE token_hash = ? AND purpose = 'browser' AND revoked_at IS NULL")
        .bind(auth::token_hash(token.as_bytes()))
        .execute(&state.app.pool)
        .await
        .is_err()
    {
        return api_error(StatusCode::INTERNAL_SERVER_ERROR, "internal_error", "Request failed");
    }
    let mut response = StatusCode::NO_CONTENT.into_response();
    append_set_cookie(
        &mut response,
        clear_cookie(cookie_name, state.app.secure_cookies),
    );
    response
}

async fn session_user(
    pool: &SqlitePool,
    headers: &HeaderMap,
    secure: bool,
    gravatar_enabled: bool,
) -> Result<Option<(UserSummary, String)>, sqlx::Error> {
    match auth::browser_user(pool, headers, secure).await {
        Ok(user_id) => match auth::cookie(headers, auth::session_cookie_name(secure)) {
            Some(token) => load_user(pool, user_id, gravatar_enabled)
                .await
                .map(|user| user.map(|user| (user, token.to_owned()))),
            None => Ok(None),
        },
        Err(auth::AuthError::Unauthorized) => Ok(None),
        Err(auth::AuthError::Database(error)) => Err(error),
    }
}

async fn load_user(
    pool: &SqlitePool,
    id: i64,
    gravatar_enabled: bool,
) -> Result<Option<UserSummary>, sqlx::Error> {
    sqlx::query_as::<_, (String, bool, bool, Option<String>)>(
        "SELECT users.email, users.is_admin, users.use_gravatar, user_avatars.filename \
         FROM users LEFT JOIN user_avatars ON user_avatars.user_id = users.id \
         WHERE users.id = ? AND users.active = 1",
    )
    .bind(id)
    .fetch_optional(pool)
    .await
    .map(|user| {
        user.map(|(email, is_admin, use_gravatar, filename)| {
            let avatar =
                crate::media::self_avatar(id, filename.as_deref(), use_gravatar, gravatar_enabled);
            UserSummary {
                id,
                email,
                is_admin,
                avatar_source: avatar.as_ref().map(|(_, source)| source.as_str()),
                avatar_url: avatar.map(|(url, _)| url),
            }
        })
    })
}

fn locale(headers: &HeaderMap) -> &'static str {
    match crate::language_from_headers(headers) {
        crate::Language::English => "en",
        crate::Language::German => "de",
    }
}

fn signed_login_csrf(state: &ApiV2State) -> Option<String> {
    let key = state.csrf_key.as_ref()?;
    let issued = now_seconds()?;
    let mut nonce = [0_u8; 32];
    SysRng.try_fill_bytes(&mut nonce).ok()?;
    let nonce = hex::encode(nonce);
    let signature = hex::encode(hmac_sha256(key, &login_csrf_payload(issued, &nonce))?);
    Some(format!("{issued}.{nonce}.{signature}"))
}

fn valid_login_csrf(key: &[u8; 32], cookie: &str, supplied: &str) -> bool {
    if !bool::from(cookie.as_bytes().ct_eq(supplied.as_bytes())) {
        return false;
    }
    let mut parts = cookie.split('.');
    let (Some(issued), Some(nonce), Some(signature), None) =
        (parts.next(), parts.next(), parts.next(), parts.next())
    else {
        return false;
    };
    if nonce.len() != 64 || hex::decode(nonce).is_err() || signature.len() != 64 {
        return false;
    }
    let (Ok(issued), Ok(signature)) = (issued.parse::<i64>(), hex::decode(signature)) else {
        return false;
    };
    let Some(now) = now_seconds() else {
        return false;
    };
    if issued < 0
        || issued > now
        || now
            .checked_sub(issued)
            .is_none_or(|age| age > LOGIN_CSRF_SECONDS)
    {
        return false;
    }
    let Ok(mut mac) = Hmac::<Sha256>::new_from_slice(key) else {
        return false;
    };
    mac.update(&login_csrf_payload(issued, nonce));
    mac.verify_slice(&signature).is_ok()
}

fn login_csrf_payload(issued: i64, nonce: &str) -> Vec<u8> {
    let issued = issued.to_string();
    let mut payload = Vec::with_capacity(LOGIN_CSRF_DOMAIN.len() + issued.len() + 1 + nonce.len());
    payload.extend_from_slice(LOGIN_CSRF_DOMAIN);
    payload.extend_from_slice(issued.as_bytes());
    payload.push(b'.');
    payload.extend_from_slice(nonce.as_bytes());
    payload
}

fn hmac_sha256(key: &[u8; 32], message: &[u8]) -> Option<[u8; 32]> {
    let mut mac = Hmac::<Sha256>::new_from_slice(key).ok()?;
    mac.update(message);
    Some(mac.finalize().into_bytes().into())
}

fn now_seconds() -> Option<i64> {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .ok()
        .and_then(|duration| i64::try_from(duration.as_secs()).ok())
}

fn same_origin_request(headers: &HeaderMap, secure: bool) -> bool {
    let Some(origin) = header_text(headers, header::ORIGIN.as_str()) else {
        return false;
    };
    if headers
        .get("sec-fetch-site")
        .and_then(|value| value.to_str().ok())
        .is_some_and(|value| value != "same-origin")
    {
        return false;
    }
    let Some(host) = headers
        .get(header::HOST)
        .and_then(|value| value.to_str().ok())
    else {
        return false;
    };
    let scheme = if secure { "https" } else { "http" };
    let Ok(expected) = url::Url::parse(&format!("{scheme}://{host}")) else {
        return false;
    };
    let Ok(actual) = url::Url::parse(origin) else {
        return false;
    };
    actual.username().is_empty()
        && actual.password().is_none()
        && actual.path() == "/"
        && actual.query().is_none()
        && actual.fragment().is_none()
        && actual.origin() == expected.origin()
}

fn header_text<'a>(headers: &'a HeaderMap, name: &str) -> Option<&'a str> {
    headers.get(name).and_then(|value| value.to_str().ok())
}

fn login_csrf_cookie_name(secure: bool) -> &'static str {
    if secure {
        HOST_LOGIN_CSRF_COOKIE
    } else {
        LOGIN_CSRF_COOKIE
    }
}

fn csrf_cookie(value: &str, secure: bool, max_age: i64) -> String {
    let mut cookie = format!(
        "{}={value}; Path=/; HttpOnly; SameSite=Lax; Max-Age={max_age}",
        login_csrf_cookie_name(secure)
    );
    if secure {
        cookie.push_str("; Secure");
    }
    cookie
}

fn session_cookie(value: &str, secure: bool) -> String {
    let mut cookie = format!(
        "{}={value}; Path=/; HttpOnly; SameSite=Lax; Max-Age={SESSION_SECONDS}",
        auth::session_cookie_name(secure)
    );
    if secure {
        cookie.push_str("; Secure");
    }
    cookie
}

fn clear_cookie(name: &str, secure: bool) -> String {
    let mut cookie = format!("{name}=; Path=/; HttpOnly; SameSite=Lax; Max-Age=0");
    if secure {
        cookie.push_str("; Secure");
    }
    cookie
}

fn clear_session_cookie(mut response: Response, secure: bool) -> Response {
    append_set_cookie(
        &mut response,
        clear_cookie(auth::session_cookie_name(secure), secure),
    );
    response
}

fn append_set_cookie(response: &mut Response, cookie: String) {
    if let Ok(value) = HeaderValue::from_str(&cookie) {
        response.headers_mut().append(header::SET_COOKIE, value);
    }
}

fn with_retry_after(mut response: Response) -> Response {
    response.headers_mut().insert(
        HeaderName::from_static("retry-after"),
        HeaderValue::from_str(&auth::RATE_LIMIT_WINDOW.as_secs().to_string())
            .unwrap_or_else(|_| HeaderValue::from_static("300")),
    );
    response
}

#[cfg(test)]
mod tests {
    use super::{
        LOGIN_CSRF_SECONDS, hmac_sha256, login_csrf_payload, now_seconds, valid_login_csrf,
    };

    #[test]
    fn expired_signed_login_csrf_is_rejected() {
        let key = [7_u8; 32];
        let issued = now_seconds().expect("clock is after the Unix epoch") - LOGIN_CSRF_SECONDS - 1;
        let nonce = "00".repeat(32);
        let signature = hex::encode(
            hmac_sha256(&key, &login_csrf_payload(issued, &nonce))
                .expect("a 32-byte HMAC key is accepted"),
        );
        let token = format!("{issued}.{nonce}.{signature}");

        assert!(!valid_login_csrf(&key, &token, &token));
    }

    #[test]
    fn malformed_negative_and_future_login_csrf_timestamps_are_rejected() {
        let key = [9_u8; 32];
        let nonce = "00".repeat(32);
        for issued in ["-1", "9223372036854775807", "not-a-time"] {
            let signature = if let Ok(issued_number) = issued.parse::<i64>() {
                hex::encode(
                    hmac_sha256(&key, &login_csrf_payload(issued_number, &nonce))
                        .expect("a 32-byte HMAC key is accepted"),
                )
            } else {
                "00".repeat(32)
            };
            let token = format!("{issued}.{nonce}.{signature}");
            assert!(
                !valid_login_csrf(&key, &token, &token),
                "timestamp: {issued}"
            );
        }
    }
}
