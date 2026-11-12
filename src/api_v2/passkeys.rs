//! API v2 routes for passkeys (WebAuthn): sign-in, step-up and management.
//!
//! Sign-in mirrors the two password paths. The browser path (`/auth/passkey/options`,
//! `/auth/passkey/login`) needs the same-origin and login-CSRF checks of `/auth/login` and ends
//! in the same session cookie. The native path (`/auth/passkey/token/options`,
//! `/auth/passkey/token`) has no cookies and ends in the same API-purpose PAT as
//! `/auth/token`. Managing passkeys needs a session or a PAT, and adding or removing one needs
//! recent authentication (a password, an OIDC sign-in or a passkey): the session stamp in a
//! browser, an `X-Reauth-Token` proof on a bearer request.

use std::net::SocketAddr;

use axum::{
    Extension, Json, Router,
    extract::{ConnectInfo, DefaultBodyLimit, Path, State, rejection::JsonRejection},
    http::{HeaderMap, StatusCode},
    response::{IntoResponse, Response},
    routing::{get, patch, post},
};
use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use webauthn_rs::prelude::{PublicKeyCredential, RegisterPublicKeyCredential};

use crate::{
    account_service::{self, MAX_DEVICE_NAME_CHARS},
    auth,
    auth_stepup_service::{self, CurrentCredential, StepupError},
    oidc_identity_service::{self, OidcIdentityError, RecentAction},
    passkeys::{LoginChannel, PasskeyError, PasskeyRuntime, PasskeyView, Started},
};

use super::{
    ApiV2State, EmptyRequest, Envelope, SESSION_SECONDS, access, api_error, api_error_fields,
    header_text, login_csrf_cookie_name,
    oidc_account::{self, ActionName, ActionTargetRequest},
    oidc_common::{client_ip, internal_error},
    same_origin_request, valid_login_csrf,
};

const DEFAULT_TOKEN_NAME: &str = "Android (passkey login)";
/// Challenge requests are cheap and a login page requests one on load (conditional UI), so the
/// budget is wider than for password attempts. Failed verifications use the password budget.
const CHALLENGE_REQUESTS_PER_WINDOW: usize = 30;
const REAUTH_TTL_SECONDS: i64 = 600;

pub(super) fn router() -> Router<ApiV2State> {
    Router::new()
        .route("/auth/passkey/options", post(web_login_options))
        .route("/auth/passkey/login", post(web_login))
        .route("/auth/passkey/token/options", post(native_login_options))
        .route("/auth/passkey/token", post(native_login))
        .route("/account/passkeys", get(list_passkeys))
        .route(
            "/account/passkeys/registration/options",
            post(registration_options),
        )
        .route(
            "/account/passkeys/registration/verify",
            post(registration_verify),
        )
        .route(
            "/account/passkeys/{id}",
            patch(rename_passkey).delete(delete_passkey),
        )
        .route("/account/reauth/passkey/options", post(reauth_options))
        .route("/account/reauth/passkey", post(reauth_verify))
        .layer(DefaultBodyLimit::max(32 * 1024))
}

fn runtime(state: &ApiV2State) -> Result<&PasskeyRuntime, Box<Response>> {
    state.app.passkeys.as_deref().ok_or_else(|| {
        Box::new(api_error(
            StatusCode::NOT_FOUND,
            "passkeys_unavailable",
            "Passkeys are not available on this server",
        ))
    })
}

/// Fail early when a browser asks from a host outside the RP ID: it would reject the options.
fn require_covered_host(
    runtime: &PasskeyRuntime,
    headers: &HeaderMap,
) -> Result<(), Box<Response>> {
    let Some(host) = header_text(headers, "host") else {
        return Ok(());
    };
    if runtime.covers_host(host) {
        return Ok(());
    }
    Err(Box::new(api_error(
        StatusCode::CONFLICT,
        "passkey_host_unsupported",
        "Passkeys do not work on this host name. Open the site on its main address instead.",
    )))
}

#[derive(Serialize)]
struct ChallengeData {
    challenge_id: String,
    options: serde_json::Value,
}

/// The creation options as the clients consume them. `residentKey: preferred` makes
/// authenticators store a discoverable credential, which username-less sign-in needs (webauthn-rs
/// would send `discouraged`). The native clients drop `extensions`: Android's Credential Manager
/// does not implement the credProtect or uvm extensions webauthn-rs asks for.
fn creation_options(
    started: &Started<webauthn_rs::prelude::CreationChallengeResponse>,
    native: bool,
) -> ChallengeData {
    let mut options = serde_json::to_value(&started.options).unwrap_or_default();
    if let Some(key) = options
        .get_mut("publicKey")
        .and_then(|key| key.as_object_mut())
    {
        if let Some(selection) = key
            .get_mut("authenticatorSelection")
            .and_then(|selection| selection.as_object_mut())
        {
            selection.insert("residentKey".to_owned(), "preferred".into());
        }
        if native {
            key.remove("extensions");
        }
    }
    ChallengeData {
        challenge_id: started.challenge_id.clone(),
        options,
    }
}

/// The request options. The `mediation` hint is dropped: the client decides between a modal
/// prompt and the autofill (conditional) UI.
fn request_options(
    started: &Started<webauthn_rs::prelude::RequestChallengeResponse>,
    native: bool,
) -> ChallengeData {
    let mut options = serde_json::to_value(&started.options).unwrap_or_default();
    if let Some(object) = options.as_object_mut() {
        object.remove("mediation");
        if native
            && let Some(key) = object
                .get_mut("publicKey")
                .and_then(|key| key.as_object_mut())
        {
            key.remove("extensions");
        }
    }
    ChallengeData {
        challenge_id: started.challenge_id.clone(),
        options,
    }
}

fn passkey_error(error: PasskeyError) -> Response {
    match error {
        PasskeyError::InvalidChallenge => api_error(
            StatusCode::BAD_REQUEST,
            "invalid_challenge",
            "The passkey request expired. Try again.",
        ),
        PasskeyError::Verification => api_error(
            StatusCode::UNAUTHORIZED,
            "invalid_passkey",
            "The passkey could not be verified",
        ),
        PasskeyError::AlreadyRegistered => api_error(
            StatusCode::CONFLICT,
            "passkey_exists",
            "This passkey is already registered",
        ),
        PasskeyError::LimitReached => api_error(
            StatusCode::CONFLICT,
            "passkey_limit",
            "Remove a passkey before adding another",
        ),
        PasskeyError::NoPasskeys => api_error(
            StatusCode::CONFLICT,
            "no_passkeys",
            "This account has no passkeys",
        ),
        PasskeyError::NotFound => {
            api_error(StatusCode::NOT_FOUND, "not_found", "Passkey was not found")
        }
        PasskeyError::Identity(error) => identity_error(error),
        PasskeyError::Random | PasskeyError::Database(_) => internal_error(),
    }
}

fn identity_error(error: OidcIdentityError) -> Response {
    match error {
        OidcIdentityError::RecentAuthenticationRequired => reauth_required(),
        OidcIdentityError::NotFound => {
            api_error(StatusCode::NOT_FOUND, "not_found", "Passkey was not found")
        }
        OidcIdentityError::Forbidden => api_error(
            StatusCode::FORBIDDEN,
            "forbidden",
            "This operation is not allowed",
        ),
        _ => internal_error(),
    }
}

fn reauth_required() -> Response {
    api_error(
        StatusCode::FORBIDDEN,
        "reauth_required",
        "Reauthentication is required",
    )
}

fn stepup_error(error: StepupError) -> Response {
    match error {
        StepupError::Unauthorized => api_error(
            StatusCode::UNAUTHORIZED,
            "session_expired",
            "Sign in to continue",
        ),
        StepupError::Identity(error) => identity_error(error),
        StepupError::InvalidCredentials | StepupError::Verification | StepupError::Database(_) => {
            internal_error()
        }
    }
}

fn json_rejection(rejection: JsonRejection) -> Response {
    if rejection.status() == StatusCode::PAYLOAD_TOO_LARGE {
        api_error(
            StatusCode::PAYLOAD_TOO_LARGE,
            "payload_too_large",
            "Request body is too large",
        )
    } else {
        api_error(
            StatusCode::BAD_REQUEST,
            "invalid_json",
            "Request body is invalid",
        )
    }
}

fn field_error(field: &'static str, message: &'static str) -> Response {
    api_error_fields(
        StatusCode::UNPROCESSABLE_ENTITY,
        "validation_error",
        "Check the submitted fields",
        std::collections::BTreeMap::from([(field, message)]),
    )
}

fn timestamp(value: i64) -> String {
    DateTime::from_timestamp(value, 0)
        .unwrap_or(DateTime::<Utc>::UNIX_EPOCH)
        .to_rfc3339_opts(chrono::SecondsFormat::Micros, true)
}

/// Rate-limit a ceremony start by client address.
fn reserve_challenge(state: &ApiV2State, ip: &str) -> Option<Response> {
    let keys = [auth::rate_key("passkey-challenge", ip)];
    state
        .app
        .auth_limiter
        .reserve_with_limit(&keys, CHALLENGE_REQUESTS_PER_WINDOW)
        .is_none()
        .then(|| {
            access::rate_limited(
                auth::RATE_LIMIT_WINDOW.as_secs(),
                "Too many passkey requests",
            )
        })
}

/// Same-origin and login-CSRF checks of `/auth/login`.
fn check_login_request(state: &ApiV2State, headers: &HeaderMap) -> Result<(), Box<Response>> {
    if !same_origin_request(headers, state.app.secure_cookies) {
        return Err(Box::new(api_error(
            StatusCode::FORBIDDEN,
            "csrf_failed",
            "Request origin was rejected",
        )));
    }
    let Some(key) = state.csrf_key.as_ref() else {
        return Err(Box::new(internal_error()));
    };
    let supplied = header_text(headers, "x-csrf-token").unwrap_or_default();
    let cookie =
        auth::cookie(headers, login_csrf_cookie_name(state.app.secure_cookies)).unwrap_or_default();
    if valid_login_csrf(key, cookie, supplied) {
        Ok(())
    } else {
        Err(Box::new(api_error(
            StatusCode::FORBIDDEN,
            "csrf_failed",
            "CSRF token is missing or expired",
        )))
    }
}

fn challenge_response(data: ChallengeData) -> Response {
    Json(Envelope { data }).into_response()
}

async fn web_login_options(
    State(state): State<ApiV2State>,
    headers: HeaderMap,
    peer: Option<Extension<ConnectInfo<SocketAddr>>>,
    request: Result<Json<EmptyRequest>, JsonRejection>,
) -> Response {
    let runtime = match runtime(&state) {
        Ok(runtime) => runtime,
        Err(response) => return *response,
    };
    if let Err(response) = require_covered_host(runtime, &headers) {
        return *response;
    }
    if let Err(response) = check_login_request(&state, &headers) {
        return *response;
    }
    if let Err(error) = request {
        return json_rejection(error);
    }
    if let Some(response) = reserve_challenge(&state, &client_ip(peer)) {
        return response;
    }
    match runtime.begin_login(LoginChannel::Web) {
        Ok(started) => challenge_response(request_options(&started, false)),
        Err(error) => passkey_error(error),
    }
}

#[derive(Deserialize)]
struct LoginRequest {
    challenge_id: String,
    credential: PublicKeyCredential,
    /// Native sign-in only: names the minted token `Android · <device_name>`.
    device_name: Option<String>,
}

/// Verify a sign-in assertion under the password failure budget: a success refunds it.
async fn verified_login(
    state: &ApiV2State,
    peer: Option<Extension<ConnectInfo<SocketAddr>>>,
    channel: LoginChannel,
    request: &LoginRequest,
) -> Result<i64, Box<Response>> {
    let runtime = runtime(state)?;
    let keys = [auth::rate_key("passkey-login", &client_ip(peer))];
    let Some(attempt) = state.app.auth_limiter.reserve(&keys) else {
        return Err(Box::new(super::with_retry_after(api_error(
            StatusCode::TOO_MANY_REQUESTS,
            "rate_limited",
            "Too many login attempts",
        ))));
    };
    let result = runtime
        .finish_login(
            &state.app.pool,
            channel,
            &request.challenge_id,
            &request.credential,
            Utc::now().timestamp(),
        )
        .await;
    match result {
        Ok(user_id) => {
            state.app.auth_limiter.refund(&keys, attempt);
            Ok(user_id)
        }
        Err(PasskeyError::Database(_) | PasskeyError::Random) => {
            state.app.auth_limiter.refund(&keys, attempt);
            Err(Box::new(internal_error()))
        }
        Err(error) => Err(Box::new(passkey_error(error))),
    }
}

async fn web_login(
    State(state): State<ApiV2State>,
    headers: HeaderMap,
    peer: Option<Extension<ConnectInfo<SocketAddr>>>,
    request: Result<Json<LoginRequest>, JsonRejection>,
) -> Response {
    if let Err(response) = runtime(&state) {
        return *response;
    }
    if let Err(response) = check_login_request(&state, &headers) {
        return *response;
    }
    let Json(request) = match request {
        Ok(request) => request,
        Err(error) => return json_rejection(error),
    };
    let user_id = match verified_login(&state, peer, LoginChannel::Web, &request).await {
        Ok(user_id) => user_id,
        Err(response) => return *response,
    };
    let token = match crate::passkeys::mint_token(
        &state.app.pool,
        user_id,
        "Browser session",
        "browser",
        Some(SESSION_SECONDS),
    )
    .await
    {
        Ok(token) => token,
        Err(error) => return passkey_error(error),
    };
    super::login_session_response(&state, &headers, &auth::MintedToken { user_id, token }).await
}

async fn native_login_options(
    State(state): State<ApiV2State>,
    peer: Option<Extension<ConnectInfo<SocketAddr>>>,
    request: Result<Json<EmptyRequest>, JsonRejection>,
) -> Response {
    let runtime = match runtime(&state) {
        Ok(runtime) => runtime,
        Err(response) => return *response,
    };
    if let Err(error) = request {
        return json_rejection(error);
    }
    if let Some(response) = reserve_challenge(&state, &client_ip(peer)) {
        return response;
    }
    match runtime.begin_login(LoginChannel::Native) {
        Ok(started) => challenge_response(request_options(&started, true)),
        Err(error) => passkey_error(error),
    }
}

#[derive(Serialize)]
struct TokenData {
    token: String,
}

async fn native_login(
    State(state): State<ApiV2State>,
    peer: Option<Extension<ConnectInfo<SocketAddr>>>,
    request: Result<Json<LoginRequest>, JsonRejection>,
) -> Response {
    if let Err(response) = runtime(&state) {
        return *response;
    }
    let Json(request) = match request {
        Ok(request) => request,
        Err(error) => return json_rejection(error),
    };
    if request
        .device_name
        .as_ref()
        .is_some_and(|name| name.trim().chars().count() > MAX_DEVICE_NAME_CHARS)
    {
        return field_error("device_name", "Device name is too long");
    }
    let name = request
        .device_name
        .as_deref()
        .and_then(account_service::clean_device_name)
        .map(account_service::device_token_name)
        .unwrap_or_else(|| DEFAULT_TOKEN_NAME.to_owned());
    let user_id = match verified_login(&state, peer, LoginChannel::Native, &request).await {
        Ok(user_id) => user_id,
        Err(response) => return *response,
    };
    match crate::passkeys::mint_token(&state.app.pool, user_id, &name, "api", None).await {
        Ok(token) => Json(Envelope {
            data: TokenData { token },
        })
        .into_response(),
        Err(error) => passkey_error(error),
    }
}

#[derive(Serialize)]
struct PasskeyDto {
    id: i64,
    nickname: String,
    created_at: String,
    last_used_at: Option<String>,
    transports: Vec<String>,
    backup_eligible: bool,
    backup_state: bool,
}

impl From<PasskeyView> for PasskeyDto {
    fn from(view: PasskeyView) -> Self {
        Self {
            id: view.id,
            nickname: view.nickname,
            created_at: timestamp(view.created_at),
            last_used_at: view.last_used_at.map(timestamp),
            transports: view.transports,
            backup_eligible: view.backup_eligible,
            backup_state: view.backup_state,
        }
    }
}

#[derive(Serialize)]
struct PasskeyList {
    data: Vec<PasskeyDto>,
    meta: PasskeyListMeta,
}

#[derive(Serialize)]
struct PasskeyListMeta {
    limit: i64,
}

async fn list_passkeys(State(state): State<ApiV2State>, headers: HeaderMap) -> Response {
    if let Err(response) = runtime(&state) {
        return *response;
    }
    let user_id = match access::resource_user(&state.app, &headers).await {
        Ok(user_id) => user_id,
        Err(response) => return *response,
    };
    match crate::passkeys::list(&state.app.pool, user_id).await {
        Ok(passkeys) => Json(PasskeyList {
            data: passkeys.into_iter().map(PasskeyDto::from).collect(),
            meta: PasskeyListMeta {
                limit: crate::passkeys::MAX_PASSKEYS_PER_USER,
            },
        })
        .into_response(),
        Err(_) => internal_error(),
    }
}

/// The credential behind the request and, for a bearer request, the hash of its
/// `X-Reauth-Token` proof (required: native clients have no session stamp).
async fn stepup_context(
    state: &ApiV2State,
    headers: &HeaderMap,
    user_id: i64,
) -> Result<(CurrentCredential, Option<String>), Box<Response>> {
    let credential = auth_stepup_service::current_credential(
        &state.app.pool,
        headers,
        user_id,
        state.app.secure_cookies,
    )
    .await
    .map_err(|error| Box::new(stepup_error(error)))?;
    let proof_hash = match credential.kind {
        auth_stepup_service::CredentialKind::Native => Some(
            auth_stepup_service::native_proof_hash(headers)
                .map_err(|error| Box::new(stepup_error(error)))?,
        ),
        auth_stepup_service::CredentialKind::Browser => None,
    };
    Ok((credential, proof_hash))
}

async fn registration_options(
    State(state): State<ApiV2State>,
    headers: HeaderMap,
    request: Result<Json<EmptyRequest>, JsonRejection>,
) -> Response {
    let runtime = match runtime(&state) {
        Ok(runtime) => runtime,
        Err(response) => return *response,
    };
    let user_id = match access::resource_mutation_user(&state.app, &headers).await {
        Ok(user_id) => user_id,
        Err(response) => return *response,
    };
    if let Err(error) = request {
        return json_rejection(error);
    }
    let (credential, proof_hash) = match stepup_context(&state, &headers, user_id).await {
        Ok(context) => context,
        Err(response) => return *response,
    };
    if credential.kind == auth_stepup_service::CredentialKind::Browser
        && let Err(response) = require_covered_host(runtime, &headers)
    {
        return *response;
    }
    let recent = match auth_stepup_service::recent_auth(credential, proof_hash.as_deref()) {
        Ok(recent) => recent,
        Err(error) => return stepup_error(error),
    };
    if let Err(error) = oidc_identity_service::validate_recent_auth(
        &state.app.pool,
        recent,
        user_id,
        user_id,
        RecentAction::ManagePasskeys,
        Utc::now().timestamp(),
    )
    .await
    {
        return identity_error(error);
    }
    match runtime
        .begin_registration(&state.app.pool, user_id, credential.token_id)
        .await
    {
        Ok(started) => challenge_response(creation_options(
            &started,
            credential.kind == auth_stepup_service::CredentialKind::Native,
        )),
        Err(error) => passkey_error(error),
    }
}

#[derive(Deserialize)]
struct RegistrationRequest {
    challenge_id: String,
    credential: RegisterPublicKeyCredential,
    nickname: Option<String>,
}

async fn registration_verify(
    State(state): State<ApiV2State>,
    headers: HeaderMap,
    request: Result<Json<RegistrationRequest>, JsonRejection>,
) -> Response {
    let runtime = match runtime(&state) {
        Ok(runtime) => runtime,
        Err(response) => return *response,
    };
    let user_id = match access::resource_mutation_user(&state.app, &headers).await {
        Ok(user_id) => user_id,
        Err(response) => return *response,
    };
    let Json(request) = match request {
        Ok(request) => request,
        Err(error) => return json_rejection(error),
    };
    let nickname = match request.nickname.as_deref() {
        None => None,
        Some(value) => match account_service::clean_device_name(value) {
            Some(value) => Some(value),
            None if value.trim().is_empty() => None,
            None => return field_error("nickname", "Name must be 1 to 100 characters"),
        },
    };
    let (credential, proof_hash) = match stepup_context(&state, &headers, user_id).await {
        Ok(context) => context,
        Err(response) => return *response,
    };
    let recent = match auth_stepup_service::recent_auth(credential, proof_hash.as_deref()) {
        Ok(recent) => recent,
        Err(error) => return stepup_error(error),
    };
    match runtime
        .finish_registration(
            &state.app.pool,
            user_id,
            credential.token_id,
            &request.challenge_id,
            &request.credential,
            nickname,
            recent,
            Utc::now().timestamp(),
        )
        .await
    {
        Ok(view) => (
            StatusCode::CREATED,
            Json(Envelope {
                data: PasskeyDto::from(view),
            }),
        )
            .into_response(),
        Err(PasskeyError::Verification) => api_error(
            StatusCode::UNPROCESSABLE_ENTITY,
            "passkey_rejected",
            "The passkey could not be registered",
        ),
        Err(error) => passkey_error(error),
    }
}

#[derive(Deserialize)]
struct RenameRequest {
    nickname: String,
}

async fn rename_passkey(
    State(state): State<ApiV2State>,
    headers: HeaderMap,
    Path(id): Path<i64>,
    request: Result<Json<RenameRequest>, JsonRejection>,
) -> Response {
    if let Err(response) = runtime(&state) {
        return *response;
    }
    let user_id = match access::resource_mutation_user(&state.app, &headers).await {
        Ok(user_id) => user_id,
        Err(response) => return *response,
    };
    let Json(request) = match request {
        Ok(request) => request,
        Err(error) => return json_rejection(error),
    };
    let Some(nickname) = account_service::clean_device_name(&request.nickname) else {
        return field_error("nickname", "Name must be 1 to 100 characters");
    };
    match crate::passkeys::rename(&state.app.pool, user_id, id, nickname).await {
        Ok(view) => Json(Envelope {
            data: PasskeyDto::from(view),
        })
        .into_response(),
        Err(error) => passkey_error(error),
    }
}

async fn delete_passkey(
    State(state): State<ApiV2State>,
    headers: HeaderMap,
    Path(id): Path<i64>,
) -> Response {
    if let Err(response) = runtime(&state) {
        return *response;
    }
    let user_id = match access::resource_mutation_user(&state.app, &headers).await {
        Ok(user_id) => user_id,
        Err(response) => return *response,
    };
    let (credential, proof_hash) = match stepup_context(&state, &headers, user_id).await {
        Ok(context) => context,
        Err(response) => return *response,
    };
    let recent = match auth_stepup_service::recent_auth(credential, proof_hash.as_deref()) {
        Ok(recent) => recent,
        Err(error) => return stepup_error(error),
    };
    match crate::passkeys::remove(&state.app.pool, user_id, id, recent, Utc::now().timestamp())
        .await
    {
        Ok(()) => StatusCode::NO_CONTENT.into_response(),
        Err(error) => passkey_error(error),
    }
}

async fn reauth_options(
    State(state): State<ApiV2State>,
    headers: HeaderMap,
    request: Result<Json<EmptyRequest>, JsonRejection>,
) -> Response {
    let runtime = match runtime(&state) {
        Ok(runtime) => runtime,
        Err(response) => return *response,
    };
    let credential = match oidc_account::authenticate(&state, &headers, true, false).await {
        Ok(credential) => credential,
        Err(response) => return *response,
    };
    if credential.kind == auth_stepup_service::CredentialKind::Browser
        && let Err(response) = require_covered_host(runtime, &headers)
    {
        return *response;
    }
    if let Err(error) = request {
        return json_rejection(error);
    }
    match runtime
        .begin_reauth(&state.app.pool, credential.user_id, credential.token_id)
        .await
    {
        Ok(started) => challenge_response(request_options(
            &started,
            credential.kind == auth_stepup_service::CredentialKind::Native,
        )),
        Err(error) => passkey_error(error),
    }
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct ReauthRequest {
    challenge_id: String,
    credential: PublicKeyCredential,
    action: ActionName,
    target: Option<ActionTargetRequest>,
}

#[derive(Serialize)]
struct ReauthBrowser {
    reauthenticated: bool,
    expires_at: String,
}

#[derive(Serialize)]
struct ReauthNative {
    reauth_token: String,
    expires_at: String,
}

/// Step up with a passkey: the same answer as `POST /account/reauth` with a password.
async fn reauth_verify(
    State(state): State<ApiV2State>,
    headers: HeaderMap,
    peer: Option<Extension<ConnectInfo<SocketAddr>>>,
    request: Result<Json<ReauthRequest>, JsonRejection>,
) -> Response {
    let runtime = match runtime(&state) {
        Ok(runtime) => runtime,
        Err(response) => return *response,
    };
    let Json(mut request) = match request {
        Ok(request) => request,
        Err(error) => return json_rejection(error),
    };
    let credential = match oidc_account::authenticate(
        &state,
        &headers,
        true,
        matches!(request.action, ActionName::AdminUnlinkIdentity),
    )
    .await
    {
        Ok(credential) => credential,
        Err(response) => return *response,
    };
    let action =
        match oidc_account::parse_action(request.action, request.target.take(), credential.user_id)
        {
            Ok(action) => action,
            Err(response) => return *response,
        };
    if let Err(response) =
        oidc_account::validate_admin_action_target(&state.app.pool, credential.user_id, &action)
            .await
    {
        return *response;
    }
    let keys = [auth::rate_key("reauth-passkey", &client_ip(peer))];
    let Some(attempt) = state.app.auth_limiter.reserve(&keys) else {
        return access::rate_limited(
            auth::RATE_LIMIT_WINDOW.as_secs(),
            "Too many reauthentication attempts",
        );
    };
    let now = Utc::now().timestamp();
    if let Err(error) = runtime
        .finish_reauth(
            &state.app.pool,
            credential.user_id,
            credential.token_id,
            &request.challenge_id,
            &request.credential,
            now,
        )
        .await
    {
        if matches!(error, PasskeyError::Database(_) | PasskeyError::Random) {
            state.app.auth_limiter.refund(&keys, attempt);
        }
        return passkey_error(error);
    }
    state.app.auth_limiter.refund(&keys, attempt);
    match auth_stepup_service::complete_stepup(
        &state.app.pool,
        credential,
        action.recent_action(),
        action.target_user_id(credential.user_id),
        now,
    )
    .await
    {
        Ok(None) => Json(Envelope {
            data: ReauthBrowser {
                reauthenticated: true,
                expires_at: timestamp(now + REAUTH_TTL_SECONDS),
            },
        })
        .into_response(),
        Ok(Some(proof)) => Json(Envelope {
            data: ReauthNative {
                reauth_token: proof,
                expires_at: timestamp(now + REAUTH_TTL_SECONDS),
            },
        })
        .into_response(),
        Err(error) => stepup_error(error),
    }
}
