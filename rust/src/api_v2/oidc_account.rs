//! API v2 handlers for linking and managing OIDC identities.

use std::net::SocketAddr;

use axum::{
    Extension, Json, Router,
    extract::{ConnectInfo, DefaultBodyLimit, Path, State, rejection::JsonRejection},
    http::{HeaderMap, HeaderValue, StatusCode, header},
    response::{IntoResponse, Response},
    routing::{delete, get, post},
};
use base64::{Engine, engine::general_purpose::URL_SAFE_NO_PAD};
use chrono::{DateTime, Utc};
use rand::{RngCore, rngs::OsRng};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use sqlx::SqlitePool;
use subtle::ConstantTimeEq;
use url::Url;
use zeroize::{Zeroize, Zeroizing};

use crate::{
    auth,
    auth_stepup_service::{self, CredentialKind, CurrentCredential, StepupError},
    oidc::{FlowBinding, FlowPurpose, ReauthAction, ValidatedIdentity, principal_hash},
    oidc_http_state::{
        FlowTransport, NativeGrant, OidcActionTarget, OidcEndpoint, OidcFlowContext,
        OidcRuntimeError,
    },
    oidc_identity_service::{
        self as identity_service, IdentitySummary, OidcIdentityError, RecentAction, RecentAuth,
    },
};

use super::{ApiV2State, Envelope, access, append_set_cookie};

const FLOW_COOKIE: &str = "__Host-stricknani_v2_oidc_flow";
const FLOW_TTL_SECONDS: i64 = 600;
const REAUTH_TTL_SECONDS: i64 = 600;
const MAX_BODY_BYTES: usize = 8 * 1024;

pub(super) fn router() -> Router<ApiV2State> {
    Router::new()
        .route("/account/identities", get(list_identities))
        .route("/account/identities/link", post(start_link))
        .route("/account/identities/link/exchange", post(exchange_link))
        .route("/account/identities/{identity_id}", delete(unlink_identity))
        .route("/account/reauth", post(password_reauth))
        .route("/account/reauth/oidc/start", post(start_oidc_reauth))
        .route("/account/reauth/oidc/exchange", post(exchange_oidc_reauth))
        .route(
            "/admin/users/{user_id}/identities",
            get(admin_list_identities),
        )
        .route(
            "/admin/users/{user_id}/identities/{identity_id}",
            delete(admin_unlink_identity),
        )
        .layer(DefaultBodyLimit::max(MAX_BODY_BYTES))
}

#[derive(Serialize)]
struct IdentityEnvelope {
    data: Vec<IdentityDto>,
    meta: IdentityMeta,
}

#[derive(Serialize)]
struct IdentityMeta {
    password_enabled: bool,
}

#[derive(Serialize)]
struct IdentityDto {
    id: i64,
    provider: String,
    subject_hint: String,
    email: Option<String>,
    linked_at: String,
    can_unlink: bool,
    current_credential: bool,
}

async fn list_identities(State(state): State<ApiV2State>, headers: HeaderMap) -> Response {
    let user_id = match access::resource_user(&state.app, &headers).await {
        Ok(id) => id,
        Err(error) => return *error,
    };
    let credential = match auth_stepup_service::current_credential(
        &state.app.pool,
        &headers,
        user_id,
        state.app.secure_cookies,
    )
    .await
    {
        Ok(value) => value,
        Err(error) => return stepup_error(error),
    };
    identity_list_response(&state, user_id, Some(credential), None).await
}

async fn admin_list_identities(
    State(state): State<ApiV2State>,
    Path(user_id): Path<i64>,
    headers: HeaderMap,
) -> Response {
    let actor_user_id = match access::admin_user(&state.app, &headers).await {
        Ok(id) => id,
        Err(error) => return *error,
    };
    identity_list_response(&state, user_id, None, Some(actor_user_id)).await
}

async fn identity_list_response(
    state: &ApiV2State,
    user_id: i64,
    credential: Option<CurrentCredential>,
    admin_actor: Option<i64>,
) -> Response {
    let result = match admin_actor {
        Some(actor) => {
            identity_service::admin_list_identities(&state.app.pool, actor, user_id).await
        }
        None => identity_service::list_identities(&state.app.pool, user_id).await,
    };
    let identities = match result {
        Ok(items) => items,
        Err(error) => return identity_error(error),
    };
    let password_enabled: Option<i64> =
        match sqlx::query_scalar("SELECT password_enabled FROM users WHERE id = ? AND active = 1")
            .bind(user_id)
            .fetch_optional(&state.app.pool)
            .await
        {
            Ok(value) => value,
            Err(_) => return internal_error(),
        };
    let Some(password_enabled) = password_enabled else {
        return not_found();
    };
    let provider_ids = enabled_provider_ids(state);
    let current_identity = match credential {
        Some(value) => match current_identity_id(&state.app.pool, value).await {
            Ok(id) => id,
            Err(_) => return internal_error(),
        },
        None => None,
    };
    let mut data = Vec::with_capacity(identities.len());
    for identity in identities.iter().cloned() {
        let linked_at = DateTime::from_timestamp(identity.linked_at, 0)
            .unwrap_or(DateTime::<Utc>::UNIX_EPOCH)
            .to_rfc3339_opts(chrono::SecondsFormat::Micros, true);
        let another_enabled_identity =
            identities_have_other_provider(&identities, identity.id, &provider_ids);
        let current_credential = current_identity == Some(identity.id);
        data.push(identity_dto(
            identity,
            password_enabled == 1 || another_enabled_identity,
            current_credential,
            linked_at,
        ));
    }
    Json(IdentityEnvelope {
        data,
        meta: IdentityMeta {
            password_enabled: password_enabled == 1,
        },
    })
    .into_response()
}

fn identities_have_other_provider(
    identities: &[IdentitySummary],
    current_id: i64,
    enabled_provider_ids: &[String],
) -> bool {
    identities.iter().any(|identity| {
        identity.id != current_id && enabled_provider_ids.contains(&identity.provider_id)
    })
}

fn identity_dto(
    identity: IdentitySummary,
    can_unlink: bool,
    current_credential: bool,
    linked_at: String,
) -> IdentityDto {
    IdentityDto {
        id: identity.id,
        provider: identity.provider_id,
        subject_hint: identity.subject_hint,
        email: identity.verified_email,
        linked_at,
        can_unlink,
        current_credential,
    }
}

async fn current_identity_id(
    pool: &SqlitePool,
    credential: CurrentCredential,
) -> Result<Option<i64>, sqlx::Error> {
    sqlx::query_scalar(
        "SELECT oidc_identity_id FROM api_tokens WHERE id = ? AND user_id = ? \
         AND revoked_at IS NULL AND (expires_at IS NULL OR expires_at > unixepoch())",
    )
    .bind(credential.token_id)
    .bind(credential.user_id)
    .fetch_optional(pool)
    .await
    .map(Option::flatten)
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct ReauthRequest {
    password: String,
    action: ActionName,
    target: Option<ActionTargetRequest>,
}

#[derive(Clone, Copy, Deserialize)]
#[serde(rename_all = "snake_case")]
enum ActionName {
    LinkIdentity,
    UnlinkIdentity,
    AdminUnlinkIdentity,
    ChangePassword,
    CreateApiToken,
    RevokeApiToken,
    DeleteAccount,
}

#[derive(Clone, Deserialize)]
#[serde(deny_unknown_fields)]
struct ActionTargetRequest {
    provider: Option<String>,
    identity_id: Option<i64>,
    user_id: Option<i64>,
    token_id: Option<i64>,
}

enum OwnedAction {
    LinkIdentity(String),
    UnlinkIdentity(i64),
    AdminUnlinkIdentity { user_id: i64, identity_id: i64 },
    ChangePassword,
    CreateApiToken,
    RevokeApiToken(i64),
    DeleteAccount,
}

impl OwnedAction {
    fn recent_action(&self) -> RecentAction<'_> {
        match self {
            Self::LinkIdentity(provider_id) => RecentAction::LinkIdentity { provider_id },
            Self::UnlinkIdentity(identity_id) => RecentAction::UnlinkIdentity {
                identity_id: *identity_id,
            },
            Self::AdminUnlinkIdentity {
                user_id,
                identity_id,
            } => RecentAction::AdminUnlinkIdentity {
                target_user_id: *user_id,
                identity_id: *identity_id,
            },
            Self::ChangePassword => RecentAction::ChangePassword,
            Self::CreateApiToken => RecentAction::CreateApiToken,
            Self::RevokeApiToken(token_id) => RecentAction::RevokeApiToken {
                token_id: *token_id,
            },
            Self::DeleteAccount => RecentAction::DeleteAccount,
        }
    }

    fn target_user_id(&self, actor_user_id: i64) -> i64 {
        match self {
            Self::AdminUnlinkIdentity { user_id, .. } => *user_id,
            _ => actor_user_id,
        }
    }

    fn reauth_action(&self) -> ReauthAction {
        match self {
            Self::LinkIdentity(_) => ReauthAction::LinkIdentity,
            Self::UnlinkIdentity(_) => ReauthAction::UnlinkIdentity,
            Self::AdminUnlinkIdentity { .. } => ReauthAction::AdminUnlinkIdentity,
            Self::ChangePassword => ReauthAction::ChangePassword,
            Self::CreateApiToken => ReauthAction::CreateApiToken,
            Self::RevokeApiToken(_) => ReauthAction::RevokeApiToken,
            Self::DeleteAccount => ReauthAction::DeleteAccount,
        }
    }

    fn context_target(&self) -> OidcActionTarget {
        match self {
            Self::LinkIdentity(provider_id) => OidcActionTarget {
                provider: Some(provider_id.clone()),
                ..OidcActionTarget::default()
            },
            Self::UnlinkIdentity(identity_id) => OidcActionTarget {
                identity_id: Some(*identity_id),
                ..OidcActionTarget::default()
            },
            Self::AdminUnlinkIdentity {
                user_id,
                identity_id,
            } => OidcActionTarget {
                identity_id: Some(*identity_id),
                user_id: Some(*user_id),
                ..OidcActionTarget::default()
            },
            Self::RevokeApiToken(token_id) => OidcActionTarget {
                token_id: Some(*token_id),
                ..OidcActionTarget::default()
            },
            Self::ChangePassword | Self::CreateApiToken | Self::DeleteAccount => {
                OidcActionTarget::default()
            }
        }
    }
}

fn boxed_validation_error(field: &'static str, message: &'static str) -> Box<Response> {
    Box::new(validation_error(field, message))
}

fn parse_action(
    action: ActionName,
    target: Option<ActionTargetRequest>,
    actor_user_id: i64,
) -> Result<OwnedAction, Box<Response>> {
    let none_target = |target: Option<ActionTargetRequest>| -> Result<(), Box<Response>> {
        if target.is_none() {
            Ok(())
        } else {
            Err(boxed_validation_error(
                "target",
                "Target fields do not match the action",
            ))
        }
    };
    match action {
        ActionName::LinkIdentity => {
            let Some(target) = target else {
                return Err(boxed_validation_error("target", "Provider is required"));
            };
            if target
                .provider
                .as_deref()
                .is_none_or(|value| value.is_empty() || value.len() > 128)
                || target.identity_id.is_some()
                || target.user_id.is_some()
                || target.token_id.is_some()
            {
                return Err(boxed_validation_error(
                    "target",
                    "Target fields do not match the action",
                ));
            }
            Ok(OwnedAction::LinkIdentity(
                target.provider.unwrap_or_default(),
            ))
        }
        ActionName::UnlinkIdentity => {
            let Some(target) = target else {
                return Err(boxed_validation_error("target", "Identity is required"));
            };
            if target.identity_id.is_none_or(|id| id <= 0)
                || target.provider.is_some()
                || target.user_id.is_some()
                || target.token_id.is_some()
            {
                return Err(boxed_validation_error(
                    "target",
                    "Target fields do not match the action",
                ));
            }
            Ok(OwnedAction::UnlinkIdentity(
                target.identity_id.unwrap_or_default(),
            ))
        }
        ActionName::AdminUnlinkIdentity => {
            let Some(target) = target else {
                return Err(boxed_validation_error(
                    "target",
                    "User and identity are required",
                ));
            };
            if target.user_id.is_none_or(|id| id <= 0)
                || target.identity_id.is_none_or(|id| id <= 0)
                || target.provider.is_some()
                || target.token_id.is_some()
            {
                return Err(boxed_validation_error(
                    "target",
                    "Target fields do not match the action",
                ));
            }
            Ok(OwnedAction::AdminUnlinkIdentity {
                user_id: target.user_id.unwrap_or_default(),
                identity_id: target.identity_id.unwrap_or_default(),
            })
        }
        ActionName::ChangePassword => {
            none_target(target)?;
            Ok(OwnedAction::ChangePassword)
        }
        ActionName::CreateApiToken => {
            none_target(target)?;
            Ok(OwnedAction::CreateApiToken)
        }
        ActionName::DeleteAccount => {
            none_target(target)?;
            Ok(OwnedAction::DeleteAccount)
        }
        ActionName::RevokeApiToken => {
            let Some(target) = target else {
                return Err(boxed_validation_error("target", "Token is required"));
            };
            if target.token_id.is_none_or(|id| id <= 0)
                || target.provider.is_some()
                || target.identity_id.is_some()
                || target.user_id.is_some()
            {
                return Err(boxed_validation_error(
                    "target",
                    "Target fields do not match the action",
                ));
            }
            Ok(OwnedAction::RevokeApiToken(
                target.token_id.unwrap_or_default(),
            ))
        }
    }
    .and_then(|parsed| {
        if parsed.target_user_id(actor_user_id) <= 0 {
            Err(boxed_validation_error("target", "Target is invalid"))
        } else {
            Ok(parsed)
        }
    })
}

async fn password_reauth(
    State(state): State<ApiV2State>,
    headers: HeaderMap,
    peer: Option<Extension<ConnectInfo<SocketAddr>>>,
    request: Result<Json<ReauthRequest>, JsonRejection>,
) -> Response {
    let Json(mut request) = match request {
        Ok(value) => value,
        Err(error) => return json_rejection(error),
    };
    let credential = match authenticate(
        &state,
        &headers,
        true,
        matches!(request.action, ActionName::AdminUnlinkIdentity),
    )
    .await
    {
        Ok(value) => value,
        Err(response) => return *response,
    };
    if request.password.is_empty() || request.password.len() > auth::MAX_PASSWORD_BYTES {
        return validation_error("password", "Password is invalid");
    }
    let action = match parse_action(request.action, request.target.take(), credential.user_id) {
        Ok(value) => value,
        Err(response) => return *response,
    };
    if let Err(response) =
        validate_admin_action_target(&state.app.pool, credential.user_id, &action).await
    {
        return *response;
    }
    if let OwnedAction::LinkIdentity(provider_id) = &action
        && !enabled_provider_ids(&state)
            .iter()
            .any(|id| id == provider_id)
    {
        return oidc_disabled();
    }
    let ip_key = format!(
        "reauth:{}",
        peer.map(|Extension(ConnectInfo(address))| address.ip().to_string())
            .unwrap_or_else(|| "unknown".to_owned())
    );
    let Some(_attempt) = state.app.auth_limiter.reserve(&[ip_key]) else {
        return access::rate_limited(
            auth::RATE_LIMIT_WINDOW.as_secs(),
            "Too many reauthentication attempts",
        );
    };
    let now = Utc::now().timestamp();
    match auth_stepup_service::password_stepup(
        &state.app.pool,
        credential,
        Zeroizing::new(std::mem::take(&mut request.password)),
        action.recent_action(),
        action.target_user_id(credential.user_id),
        now,
    )
    .await
    {
        Ok(None) => Json(Envelope {
            data: PasswordReauthBrowser {
                reauthenticated: true,
                expires_at: timestamp(now + REAUTH_TTL_SECONDS),
            },
        })
        .into_response(),
        Ok(Some(proof)) => Json(Envelope {
            data: PasswordReauthNative {
                reauth_token: proof,
                expires_at: timestamp(now + REAUTH_TTL_SECONDS),
            },
        })
        .into_response(),
        Err(error) => stepup_error(error),
    }
}

#[derive(Serialize)]
struct PasswordReauthBrowser {
    reauthenticated: bool,
    expires_at: String,
}

#[derive(Serialize)]
struct PasswordReauthNative {
    reauth_token: String,
    expires_at: String,
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct FlowStartRequest {
    provider: String,
    client: String,
    return_to: Option<String>,
    redirect_uri: Option<String>,
    app_state: Option<String>,
    code_challenge: Option<String>,
    code_challenge_method: Option<String>,
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct OidcReauthStartRequest {
    provider: String,
    action: ActionName,
    target: Option<ActionTargetRequest>,
    client: String,
    return_to: Option<String>,
    redirect_uri: Option<String>,
    app_state: Option<String>,
    code_challenge: Option<String>,
    code_challenge_method: Option<String>,
}

#[derive(Serialize)]
struct FlowStartEnvelope {
    data: FlowStartData,
}

#[derive(Serialize)]
struct FlowStartData {
    authorization_url: String,
    expires_at: String,
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct NativeExchangeRequest {
    code: String,
    code_verifier: String,
    redirect_uri: String,
}

#[derive(Serialize)]
struct NativeReauthEnvelope {
    data: NativeReauthData,
}

#[derive(Serialize)]
struct NativeReauthData {
    reauth_token: String,
    expires_at: String,
}

#[derive(Serialize)]
struct LinkedIdentityEnvelope {
    data: LinkedIdentityData,
}

#[derive(Serialize)]
struct LinkedIdentityData {
    identity: IdentityDto,
}

async fn start_link(
    State(state): State<ApiV2State>,
    headers: HeaderMap,
    peer: Option<Extension<ConnectInfo<SocketAddr>>>,
    request: Result<Json<FlowStartRequest>, JsonRejection>,
) -> Response {
    let credential = match authenticate(&state, &headers, true, false).await {
        Ok(value) => value,
        Err(response) => return *response,
    };
    let Json(request) = match request {
        Ok(value) => value,
        Err(error) => return json_rejection(error),
    };
    let Some(runtime) = state.app.oidc.as_ref() else {
        return oidc_disabled();
    };
    if request.provider != runtime.provider_id() {
        return oidc_disabled();
    }
    let action = OwnedAction::LinkIdentity(request.provider.clone());
    let proof_hash = match credential.kind {
        CredentialKind::Browser => None,
        CredentialKind::Native => match auth_stepup_service::native_proof_hash(&headers) {
            Ok(value) => Some(value),
            Err(error) => return stepup_error(error),
        },
    };
    let recent = match auth_stepup_service::recent_auth(credential, proof_hash.as_deref()) {
        Ok(value) => value,
        Err(error) => return stepup_error(error),
    };
    if let Err(error) = identity_service::validate_recent_auth(
        &state.app.pool,
        recent,
        credential.user_id,
        credential.user_id,
        action.recent_action(),
        Utc::now().timestamp(),
    )
    .await
    {
        return identity_error(error);
    }
    start_authenticated_flow(
        &state,
        &headers,
        peer,
        request,
        FlowPurpose::Link {
            owner_user_id: credential.user_id,
        },
        OidcFlowContext {
            action_target: Some(action.context_target()),
            principal_token_id: Some(credential.token_id),
            recent_auth_proof_hash: proof_hash,
        },
        false,
    )
    .await
}

async fn start_oidc_reauth(
    State(state): State<ApiV2State>,
    headers: HeaderMap,
    peer: Option<Extension<ConnectInfo<SocketAddr>>>,
    request: Result<Json<OidcReauthStartRequest>, JsonRejection>,
) -> Response {
    let Json(request) = match request {
        Ok(value) => value,
        Err(error) => return json_rejection(error),
    };
    let credential = match authenticate(
        &state,
        &headers,
        true,
        matches!(request.action, ActionName::AdminUnlinkIdentity),
    )
    .await
    {
        Ok(value) => value,
        Err(response) => return *response,
    };
    let action = match parse_action(request.action, request.target, credential.user_id) {
        Ok(value) => value,
        Err(response) => return *response,
    };
    let Some(runtime) = state.app.oidc.as_ref() else {
        return oidc_disabled();
    };
    if request.provider != runtime.provider_id() {
        return oidc_disabled();
    }
    if let Err(response) =
        validate_admin_action_target(&state.app.pool, credential.user_id, &action).await
    {
        return *response;
    }
    let owns_reauth_provider: bool = match sqlx::query_scalar(
        "SELECT EXISTS(SELECT 1 FROM oidc_identities WHERE user_id = ? AND provider_id = ?)",
    )
    .bind(credential.user_id)
    .bind(&request.provider)
    .fetch_one(&state.app.pool)
    .await
    {
        Ok(value) => value,
        Err(_) => return internal_error(),
    };
    if !owns_reauth_provider {
        return not_found();
    }
    let target = action.context_target();
    start_authenticated_flow(
        &state,
        &headers,
        peer,
        FlowStartRequest {
            provider: request.provider,
            client: request.client,
            return_to: request.return_to,
            redirect_uri: request.redirect_uri,
            app_state: request.app_state,
            code_challenge: request.code_challenge,
            code_challenge_method: request.code_challenge_method,
        },
        FlowPurpose::Reauth {
            owner_user_id: credential.user_id,
            action: action.reauth_action(),
        },
        OidcFlowContext {
            action_target: Some(target),
            principal_token_id: Some(credential.token_id),
            recent_auth_proof_hash: None,
        },
        true,
    )
    .await
}

async fn start_authenticated_flow(
    state: &ApiV2State,
    headers: &HeaderMap,
    peer: Option<Extension<ConnectInfo<SocketAddr>>>,
    request: FlowStartRequest,
    purpose: FlowPurpose,
    context: OidcFlowContext,
    require_fresh_idp_auth: bool,
) -> Response {
    let Some(runtime) = state.app.oidc.as_ref() else {
        return oidc_disabled();
    };
    if request.provider != runtime.provider_id() {
        return oidc_disabled();
    }
    if let Err(error) = runtime.allow_request(OidcEndpoint::Start, &client_ip(peer)) {
        return runtime_error(error);
    }
    let has_bearer = headers.contains_key(header::AUTHORIZATION);
    if (request.client == "browser" && has_bearer) || (request.client == "android" && !has_bearer) {
        return validation_error(
            "client",
            "Client does not match the authenticated credential",
        );
    }

    let (transport, cookie, return_path, redirect_uri, app_state, challenge) =
        match request.client.as_str() {
            "browser" => {
                if request.redirect_uri.is_some()
                    || request.app_state.is_some()
                    || request.code_challenge.is_some()
                    || request.code_challenge_method.is_some()
                {
                    return validation_error("client", "Browser flow fields are invalid");
                }
                let return_path = request
                    .return_to
                    .unwrap_or_else(|| "/app/account".to_owned());
                if !valid_app_return_path(&return_path) {
                    return validation_error("return_to", "Return path is invalid");
                }
                let flow_cookie = match random_secret() {
                    Ok(value) => value,
                    Err(()) => return internal_error(),
                };
                (
                    FlowTransport::Browser,
                    Some(flow_cookie),
                    Some(return_path),
                    None,
                    None,
                    None,
                )
            }
            "android" => {
                if request.return_to.is_some()
                    || request.code_challenge_method.as_deref() != Some("S256")
                {
                    return validation_error("client", "Native flow fields are invalid");
                }
                let (Some(redirect_uri), Some(app_state), Some(challenge)) = (
                    request.redirect_uri,
                    request.app_state,
                    request.code_challenge,
                ) else {
                    return validation_error("redirect_uri", "Native PKCE fields are required");
                };
                if !valid_s256_challenge(&challenge) || !valid_app_state(&app_state) {
                    return validation_error("code_challenge", "Native PKCE fields are invalid");
                }
                (
                    FlowTransport::Native,
                    None,
                    None,
                    Some(redirect_uri),
                    Some(app_state),
                    Some(challenge),
                )
            }
            _ => return validation_error("client", "Client must be browser or android"),
        };

    let principal = match transport {
        FlowTransport::Browser => cookie.as_deref().map(|value| value.as_bytes()),
        FlowTransport::Native => bearer_token(headers).map(str::as_bytes),
    };
    let Some(principal) = principal else {
        return auth_error();
    };
    let binding = FlowBinding {
        provider_id: request.provider,
        purpose,
        principal_hash: principal_hash(principal),
        native_redirect_uri: redirect_uri,
        return_path,
        app_code_challenge: challenge,
        app_state,
        max_auth_age_secs: require_fresh_idp_auth.then_some(REAUTH_TTL_SECONDS as u64),
    };
    let (authorization_url, _, expires_at) = match runtime.start(binding, context, transport).await
    {
        Ok(value) => value,
        Err(error) => return runtime_error(error),
    };
    let mut response = Json(FlowStartEnvelope {
        data: FlowStartData {
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

async fn unlink_identity(
    State(state): State<ApiV2State>,
    Path(identity_id): Path<i64>,
    headers: HeaderMap,
) -> Response {
    let credential = match authenticate(&state, &headers, true, false).await {
        Ok(value) => value,
        Err(response) => return *response,
    };
    if identity_id <= 0 {
        return not_found();
    }
    let proof_hash = match credential.kind {
        CredentialKind::Browser => None,
        CredentialKind::Native => match auth_stepup_service::native_proof_hash(&headers) {
            Ok(value) => Some(value),
            Err(error) => return stepup_error(error),
        },
    };
    let recent = match auth_stepup_service::recent_auth(credential, proof_hash.as_deref()) {
        Ok(value) => value,
        Err(error) => return stepup_error(error),
    };
    match identity_service::unlink_identity(
        &state.app.pool,
        credential.user_id,
        identity_id,
        recent,
        &enabled_provider_ids(&state),
        Utc::now().timestamp(),
    )
    .await
    {
        Ok(()) => StatusCode::NO_CONTENT.into_response(),
        Err(error) => identity_error(error),
    }
}

async fn admin_unlink_identity(
    State(state): State<ApiV2State>,
    Path((user_id, identity_id)): Path<(i64, i64)>,
    headers: HeaderMap,
) -> Response {
    let actor_user_id = match access::admin_mutation_user(&state.app, &headers).await {
        Ok(value) => value,
        Err(error) => return *error,
    };
    let credential = match auth_stepup_service::current_credential(
        &state.app.pool,
        &headers,
        actor_user_id,
        state.app.secure_cookies,
    )
    .await
    {
        Ok(value) => value,
        Err(error) => return stepup_error(error),
    };
    let proof_hash = match credential.kind {
        CredentialKind::Browser => None,
        CredentialKind::Native => match auth_stepup_service::native_proof_hash(&headers) {
            Ok(value) => Some(value),
            Err(error) => return stepup_error(error),
        },
    };
    let recent = match auth_stepup_service::recent_auth(credential, proof_hash.as_deref()) {
        Ok(value) => value,
        Err(error) => return stepup_error(error),
    };
    match identity_service::admin_unlink_identity(
        &state.app.pool,
        actor_user_id,
        user_id,
        identity_id,
        recent,
        &enabled_provider_ids(&state),
        Utc::now().timestamp(),
    )
    .await
    {
        Ok(()) => StatusCode::NO_CONTENT.into_response(),
        Err(error) => identity_error(error),
    }
}

/// Complete a non-login provider callback. The OIDC login module dispatches
/// here after consuming and verifying the corresponding one-use flow.
pub(super) async fn complete_oidc_callback(
    state: &ApiV2State,
    headers: &HeaderMap,
    binding: FlowBinding,
    context: OidcFlowContext,
    identity: Option<ValidatedIdentity>,
    transport: FlowTransport,
    failure: Option<&'static str>,
) -> Response {
    if failure.is_some() {
        return callback_redirect(&binding, transport, failure.unwrap_or("failed"));
    }
    let Some(identity) = identity else {
        return callback_redirect(&binding, transport, "failed");
    };
    if identity.provider_id() != binding.provider_id {
        return callback_redirect(&binding, transport, "failed");
    }
    match binding.purpose {
        FlowPurpose::Login => callback_redirect(&binding, transport, "failed"),
        FlowPurpose::Link { owner_user_id } => {
            complete_link_callback(
                state,
                headers,
                binding,
                context,
                identity,
                owner_user_id,
                transport,
            )
            .await
        }
        FlowPurpose::Reauth {
            owner_user_id,
            action,
        } => {
            complete_reauth_callback(
                state,
                headers,
                binding,
                context,
                identity,
                ReauthCallback {
                    owner_user_id,
                    action,
                    transport,
                },
            )
            .await
        }
    }
}

async fn complete_link_callback(
    state: &ApiV2State,
    headers: &HeaderMap,
    binding: FlowBinding,
    context: OidcFlowContext,
    identity: ValidatedIdentity,
    owner_user_id: i64,
    transport: FlowTransport,
) -> Response {
    let Some(target) = context.action_target.as_ref() else {
        return callback_redirect(&binding, transport, "failed");
    };
    if target.provider.as_deref() != Some(binding.provider_id.as_str())
        || context.principal_token_id.is_none()
        || target.identity_id.is_some()
        || target.user_id.is_some()
        || target.token_id.is_some()
    {
        return callback_redirect(&binding, transport, "failed");
    }
    match transport {
        FlowTransport::Browser => {
            let credential = match callback_browser_credential(state, headers, owner_user_id).await
            {
                Ok(value) if Some(value.token_id) == context.principal_token_id => value,
                _ => return callback_redirect(&binding, transport, "failed"),
            };
            let result = identity_service::link_identity(
                &state.app.pool,
                owner_user_id,
                &identity,
                &enabled_provider_ids(state),
                RecentAuth::BrowserSession {
                    token_id: credential.token_id,
                },
                Utc::now().timestamp(),
            )
            .await;
            match result {
                Ok(_) => callback_redirect_success(&binding, transport, "linked", None),
                Err(OidcIdentityError::IdentityAlreadyLinked) => {
                    callback_redirect(&binding, transport, "already_linked")
                }
                Err(_) => callback_redirect(&binding, transport, "failed"),
            }
        }
        FlowTransport::Native => {
            let Some(runtime) = state.app.oidc.as_ref() else {
                return callback_redirect(&binding, transport, "failed");
            };
            match runtime.create_native_grant(binding.clone(), context, identity, owner_user_id) {
                Ok(grant) => native_grant_redirect(grant),
                Err(_) => callback_redirect(&binding, transport, "failed"),
            }
        }
    }
}

struct ReauthCallback {
    owner_user_id: i64,
    action: ReauthAction,
    transport: FlowTransport,
}

async fn complete_reauth_callback(
    state: &ApiV2State,
    headers: &HeaderMap,
    binding: FlowBinding,
    context: OidcFlowContext,
    identity: ValidatedIdentity,
    callback: ReauthCallback,
) -> Response {
    let ReauthCallback {
        owner_user_id,
        action,
        transport,
    } = callback;
    let Some(target) = context.action_target.as_ref() else {
        return callback_redirect(&binding, transport, "failed");
    };
    if context.principal_token_id.is_none()
        || identity.auth_time().is_none_or(|auth_time| {
            let now = Utc::now();
            auth_time > now
                || now.timestamp().saturating_sub(auth_time.timestamp()) > REAUTH_TTL_SECONDS
        })
    {
        return callback_redirect(&binding, transport, "failed");
    }
    match identity_service::lookup_login_identity(&state.app.pool, &identity).await {
        Ok(Some(user_id)) if user_id == owner_user_id => {}
        _ => return callback_redirect(&binding, transport, "failed"),
    }
    match transport {
        FlowTransport::Browser => {
            let credential = match callback_browser_credential(state, headers, owner_user_id).await
            {
                Ok(value)
                    if Some(value.token_id) == context.principal_token_id
                        && value.kind == CredentialKind::Browser =>
                {
                    value
                }
                _ => return callback_redirect(&binding, transport, "failed"),
            };
            if identity_service::stamp_browser_reauth(
                &state.app.pool,
                owner_user_id,
                credential.token_id,
                Utc::now().timestamp(),
            )
            .await
            .is_err()
            {
                return callback_redirect(&binding, transport, "failed");
            }
            let action = reauth_action_name(action);
            callback_redirect_success(&binding, transport, "reauthed", Some(action))
        }
        FlowTransport::Native => {
            if !target_matches_reauth_action(target, action) {
                return callback_redirect(&binding, transport, "failed");
            }
            let Some(runtime) = state.app.oidc.as_ref() else {
                return callback_redirect(&binding, transport, "failed");
            };
            match runtime.create_native_grant(binding.clone(), context, identity, owner_user_id) {
                Ok(grant) => native_grant_redirect(grant),
                Err(_) => callback_redirect(&binding, transport, "failed"),
            }
        }
    }
}

async fn callback_browser_credential(
    state: &ApiV2State,
    headers: &HeaderMap,
    owner_user_id: i64,
) -> Result<CurrentCredential, ()> {
    let user_id = auth::browser_user(&state.app.pool, headers, state.app.secure_cookies)
        .await
        .map_err(|_| ())?;
    if user_id != owner_user_id {
        return Err(());
    }
    let credential = auth_stepup_service::current_credential(
        &state.app.pool,
        headers,
        user_id,
        state.app.secure_cookies,
    )
    .await
    .map_err(|_| ())?;
    (credential.kind == CredentialKind::Browser)
        .then_some(credential)
        .ok_or(())
}

fn target_matches_reauth_action(target: &OidcActionTarget, action: ReauthAction) -> bool {
    match action {
        ReauthAction::LinkIdentity => target.provider.is_some(),
        ReauthAction::UnlinkIdentity => target.identity_id.is_some(),
        ReauthAction::AdminUnlinkIdentity => {
            target.identity_id.is_some() && target.user_id.is_some()
        }
        ReauthAction::ChangePassword
        | ReauthAction::CreateApiToken
        | ReauthAction::DeleteAccount => {
            target.provider.is_none()
                && target.identity_id.is_none()
                && target.user_id.is_none()
                && target.token_id.is_none()
        }
        ReauthAction::RevokeApiToken => target.token_id.is_some(),
    }
}

async fn exchange_link(
    State(state): State<ApiV2State>,
    headers: HeaderMap,
    request: Result<Json<NativeExchangeRequest>, JsonRejection>,
) -> Response {
    let credential = match authenticate(&state, &headers, true, false).await {
        Ok(value) if value.kind == CredentialKind::Native => value,
        Ok(_) => return auth_error(),
        Err(response) => return *response,
    };
    let Json(request) = match request {
        Ok(value) => value,
        Err(error) => return json_rejection(error),
    };
    let Some(runtime) = state.app.oidc.as_ref() else {
        return oidc_disabled();
    };
    let grant = match runtime.consume_grant_for(
        &request.code,
        FlowPurpose::Link {
            owner_user_id: credential.user_id,
        },
    ) {
        Ok(value) => value,
        Err(error) => return runtime_error(error),
    };
    if !native_grant_matches(&grant.binding, &request, &headers, credential)
        || grant.user_id != credential.user_id
        || grant.context.principal_token_id != Some(credential.token_id)
    {
        return invalid_flow();
    }
    let Some(proof_hash) = grant.context.recent_auth_proof_hash.as_deref() else {
        return invalid_flow();
    };
    let supplied_hash = match auth_stepup_service::native_proof_hash(&headers) {
        Ok(value) => value,
        Err(error) => return stepup_error(error),
    };
    if !constant_time_eq(proof_hash, &supplied_hash) {
        return reauth_required();
    }
    let proof_hash = proof_hash.to_owned();
    let linked = identity_service::link_identity(
        &state.app.pool,
        credential.user_id,
        &grant.identity,
        &enabled_provider_ids(&state),
        RecentAuth::NativeProof {
            token_id: credential.token_id,
            proof_hash: &proof_hash,
        },
        Utc::now().timestamp(),
    )
    .await;
    match linked {
        Ok(identity) => linked_identity_response(&state, credential.user_id, identity).await,
        Err(error) => identity_error(error),
    }
}

async fn exchange_oidc_reauth(
    State(state): State<ApiV2State>,
    headers: HeaderMap,
    request: Result<Json<NativeExchangeRequest>, JsonRejection>,
) -> Response {
    let credential = match authenticate(&state, &headers, true, false).await {
        Ok(value) if value.kind == CredentialKind::Native => value,
        Ok(_) => return auth_error(),
        Err(response) => return *response,
    };
    let Json(request) = match request {
        Ok(value) => value,
        Err(error) => return json_rejection(error),
    };
    let Some(runtime) = state.app.oidc.as_ref() else {
        return oidc_disabled();
    };
    let grant = match runtime.consume_reauth_grant(&request.code, credential.user_id) {
        Ok(value) => value,
        Err(error) => return runtime_error(error),
    };
    if !native_grant_matches(&grant.binding, &request, &headers, credential)
        || grant.user_id != credential.user_id
        || grant.context.principal_token_id != Some(credential.token_id)
    {
        return invalid_flow();
    }
    let FlowPurpose::Reauth { action, .. } = grant.binding.purpose else {
        return invalid_flow();
    };
    let target = match action_from_context(action, &grant.context) {
        Ok(target) => target,
        Err(()) => return invalid_flow(),
    };
    if identity_service::lookup_login_identity(&state.app.pool, &grant.identity)
        .await
        .ok()
        != Some(Some(credential.user_id))
        || !identity_is_fresh(&grant.identity, Utc::now().timestamp())
    {
        return invalid_flow();
    }
    let proof = match identity_service::issue_native_proof(
        &state.app.pool,
        credential.user_id,
        credential.token_id,
        target.target_user_id(credential.user_id),
        target.recent_action(),
        Utc::now().timestamp(),
        Utc::now().timestamp().saturating_add(REAUTH_TTL_SECONDS),
    )
    .await
    {
        Ok(value) => value,
        Err(error) => return identity_error(error),
    };
    Json(NativeReauthEnvelope {
        data: NativeReauthData {
            reauth_token: proof,
            expires_at: timestamp(Utc::now().timestamp() + REAUTH_TTL_SECONDS),
        },
    })
    .into_response()
}

async fn authenticate(
    state: &ApiV2State,
    headers: &HeaderMap,
    mutation: bool,
    admin: bool,
) -> Result<CurrentCredential, Box<Response>> {
    let user_id = match (mutation, admin) {
        (true, true) => access::admin_mutation_user(&state.app, headers).await,
        (false, true) => access::admin_user(&state.app, headers).await,
        (true, false) => access::resource_mutation_user(&state.app, headers).await,
        (false, false) => access::resource_user(&state.app, headers).await,
    }?;
    auth_stepup_service::current_credential(
        &state.app.pool,
        headers,
        user_id,
        state.app.secure_cookies,
    )
    .await
    .map_err(|error| Box::new(stepup_error(error)))
}

async fn validate_admin_action_target(
    pool: &SqlitePool,
    actor_user_id: i64,
    action: &OwnedAction,
) -> Result<(), Box<Response>> {
    let OwnedAction::AdminUnlinkIdentity {
        user_id,
        identity_id,
    } = action
    else {
        return Ok(());
    };
    let valid: bool = sqlx::query_scalar(
        "SELECT EXISTS(SELECT 1 FROM users u JOIN oidc_identities i ON i.user_id = u.id \
         WHERE u.id = ? AND u.active = 1 AND i.id = ?)",
    )
    .bind(user_id)
    .bind(identity_id)
    .fetch_one(pool)
    .await
    .map_err(|_| Box::new(internal_error()))?;
    if !valid || actor_user_id <= 0 {
        return Err(Box::new(not_found()));
    }
    Ok(())
}

fn callback_redirect_for_flow(
    binding: &FlowBinding,
    transport: FlowTransport,
    success: bool,
    marker: &'static str,
    action: Option<&'static str>,
) -> Response {
    match transport {
        FlowTransport::Browser => {
            let target = binding
                .return_path
                .as_deref()
                .filter(|path| valid_app_return_path(path))
                .unwrap_or("/app/account");
            let mut url = match Url::parse(&format!("https://local.invalid{target}")) {
                Ok(url) => url,
                Err(_) => return invalid_flow(),
            };
            if success {
                url.query_pairs_mut().append_pair("oidc", marker);
                if let Some(action) = action {
                    url.query_pairs_mut().append_pair("action", action);
                }
            } else {
                url.query_pairs_mut().append_pair("oidc_error", marker);
            }
            let location = format!(
                "{}{}",
                url.path(),
                url.query().map_or(String::new(), |q| format!("?{q}"))
            );
            let mut response = StatusCode::SEE_OTHER.into_response();
            if let Ok(value) = HeaderValue::from_str(&location) {
                response.headers_mut().insert(header::LOCATION, value);
            }
            response.headers_mut().insert(
                header::REFERRER_POLICY,
                HeaderValue::from_static("no-referrer"),
            );
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
            if success {
                return invalid_flow();
            }
            target
                .query_pairs_mut()
                .append_pair("state", app_state)
                .append_pair("error", "oidc_failed")
                .append_pair(
                    "flow",
                    match &binding.purpose {
                        FlowPurpose::Login => "login",
                        FlowPurpose::Link { .. } => "link",
                        FlowPurpose::Reauth { .. } => "reauth",
                    },
                );
            redirect_response(target.as_str())
        }
    }
}

fn callback_redirect(
    binding: &FlowBinding,
    transport: FlowTransport,
    error: &'static str,
) -> Response {
    let marker = if error == "already_linked" {
        "already_linked"
    } else {
        "failed"
    };
    callback_redirect_for_flow(binding, transport, false, marker, None)
}

fn callback_redirect_success(
    binding: &FlowBinding,
    transport: FlowTransport,
    marker: &'static str,
    action: Option<&'static str>,
) -> Response {
    callback_redirect_for_flow(binding, transport, true, marker, action)
}

fn native_grant_redirect(grant: NativeGrant) -> Response {
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

fn native_grant_matches(
    binding: &FlowBinding,
    request: &NativeExchangeRequest,
    headers: &HeaderMap,
    credential: CurrentCredential,
) -> bool {
    if binding.native_redirect_uri.as_deref() != Some(request.redirect_uri.as_str())
        || credential.kind != CredentialKind::Native
        || !(43..=128).contains(&request.code_verifier.len())
        || !request
            .code_verifier
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || b"-._~".contains(&byte))
    {
        return false;
    }
    let Some(expected) = binding.app_code_challenge.as_deref() else {
        return false;
    };
    let actual = URL_SAFE_NO_PAD.encode(Sha256::digest(request.code_verifier.as_bytes()));
    let Some(token) = bearer_token(headers) else {
        return false;
    };
    bool::from(actual.as_bytes().ct_eq(expected.as_bytes()))
        && bool::from(principal_hash(token.as_bytes()).ct_eq(&binding.principal_hash))
}

fn bearer_token(headers: &HeaderMap) -> Option<&str> {
    let values = headers.get_all(header::AUTHORIZATION);
    (values.iter().count() == 1)
        .then(|| values.iter().next().and_then(|value| value.to_str().ok()))
        .flatten()
        .and_then(auth_stepup_service::parse_bearer)
}

fn action_from_context(action: ReauthAction, context: &OidcFlowContext) -> Result<OwnedAction, ()> {
    let target = context.action_target.as_ref().ok_or(())?;
    let empty = target.provider.is_none()
        && target.identity_id.is_none()
        && target.user_id.is_none()
        && target.token_id.is_none();
    match action {
        ReauthAction::LinkIdentity
            if target.provider.is_some()
                && target.identity_id.is_none()
                && target.user_id.is_none()
                && target.token_id.is_none() =>
        {
            Ok(OwnedAction::LinkIdentity(
                target.provider.clone().ok_or(())?,
            ))
        }
        ReauthAction::UnlinkIdentity
            if target.identity_id.is_some()
                && target.provider.is_none()
                && target.user_id.is_none()
                && target.token_id.is_none() =>
        {
            Ok(OwnedAction::UnlinkIdentity(target.identity_id.ok_or(())?))
        }
        ReauthAction::AdminUnlinkIdentity
            if target.identity_id.is_some()
                && target.user_id.is_some()
                && target.provider.is_none()
                && target.token_id.is_none() =>
        {
            Ok(OwnedAction::AdminUnlinkIdentity {
                user_id: target.user_id.ok_or(())?,
                identity_id: target.identity_id.ok_or(())?,
            })
        }
        ReauthAction::ChangePassword if empty => Ok(OwnedAction::ChangePassword),
        ReauthAction::CreateApiToken if empty => Ok(OwnedAction::CreateApiToken),
        ReauthAction::DeleteAccount if empty => Ok(OwnedAction::DeleteAccount),
        ReauthAction::RevokeApiToken
            if target.token_id.is_some()
                && target.provider.is_none()
                && target.identity_id.is_none()
                && target.user_id.is_none() =>
        {
            Ok(OwnedAction::RevokeApiToken(target.token_id.ok_or(())?))
        }
        _ => Err(()),
    }
}

fn identity_is_fresh(identity: &ValidatedIdentity, now: i64) -> bool {
    identity.auth_time().is_some_and(|auth_time| {
        let timestamp = auth_time.timestamp();
        timestamp <= now && now.saturating_sub(timestamp) <= REAUTH_TTL_SECONDS
    })
}

fn reauth_action_name(action: ReauthAction) -> &'static str {
    match action {
        ReauthAction::LinkIdentity => "link_identity",
        ReauthAction::UnlinkIdentity => "unlink_identity",
        ReauthAction::AdminUnlinkIdentity => "admin_unlink_identity",
        ReauthAction::ChangePassword => "change_password",
        ReauthAction::CreateApiToken => "create_api_token",
        ReauthAction::RevokeApiToken => "revoke_api_token",
        ReauthAction::DeleteAccount => "delete_account",
    }
}

async fn linked_identity_response(
    state: &ApiV2State,
    user_id: i64,
    identity: IdentitySummary,
) -> Response {
    let password_enabled = match sqlx::query_scalar::<_, i64>(
        "SELECT password_enabled FROM users WHERE id = ? AND active = 1",
    )
    .bind(user_id)
    .fetch_optional(&state.app.pool)
    .await
    {
        Ok(Some(value)) => value == 1,
        Ok(None) => return not_found(),
        Err(_) => return internal_error(),
    };
    let identities = match identity_service::list_identities(&state.app.pool, user_id).await {
        Ok(value) => value,
        Err(error) => return identity_error(error),
    };
    let can_unlink = password_enabled
        || identities_have_other_provider(&identities, identity.id, &enabled_provider_ids(state));
    let linked_at = timestamp(identity.linked_at);
    Json(LinkedIdentityEnvelope {
        data: LinkedIdentityData {
            identity: identity_dto(identity, can_unlink, false, linked_at),
        },
    })
    .into_response()
}

fn enabled_provider_ids(state: &ApiV2State) -> Vec<String> {
    state
        .app
        .oidc
        .as_ref()
        .map(|runtime| runtime.enabled_provider_ids())
        .unwrap_or_default()
}

fn timestamp(value: i64) -> String {
    DateTime::from_timestamp(value, 0)
        .unwrap_or(DateTime::<Utc>::UNIX_EPOCH)
        .to_rfc3339_opts(chrono::SecondsFormat::Micros, true)
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

fn random_secret() -> Result<Zeroizing<String>, ()> {
    let mut bytes = [0_u8; 32];
    OsRng.try_fill_bytes(&mut bytes).map_err(|_| ())?;
    let secret = URL_SAFE_NO_PAD.encode(bytes);
    bytes.zeroize();
    Ok(Zeroizing::new(secret))
}

fn flow_cookie(value: &str) -> String {
    format!(
        "{FLOW_COOKIE}={value}; Path=/; HttpOnly; Secure; SameSite=Lax; Max-Age={FLOW_TTL_SECONDS}"
    )
}

fn client_ip(peer: Option<Extension<ConnectInfo<SocketAddr>>>) -> String {
    peer.map(|Extension(ConnectInfo(address))| address.ip().to_string())
        .unwrap_or_else(|| "unknown".to_owned())
}

fn constant_time_eq(left: &str, right: &str) -> bool {
    bool::from(left.as_bytes().ct_eq(right.as_bytes()))
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

fn api_error(status: StatusCode, code: &'static str, message: &'static str) -> Response {
    super::api_error(status, code, message)
}

fn auth_error() -> Response {
    api_error(
        StatusCode::UNAUTHORIZED,
        "session_expired",
        "Sign in to continue",
    )
}

fn reauth_required() -> Response {
    api_error(
        StatusCode::FORBIDDEN,
        "reauth_required",
        "Reauthentication is required",
    )
}

fn not_found() -> Response {
    api_error(StatusCode::NOT_FOUND, "not_found", "Identity was not found")
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

fn runtime_error(error: OidcRuntimeError) -> Response {
    match error {
        OidcRuntimeError::RateLimited(retry) => {
            access::rate_limited(retry, "Too many OIDC requests")
        }
        OidcRuntimeError::Provider
        | OidcRuntimeError::Unavailable
        | OidcRuntimeError::Capacity
        | OidcRuntimeError::Configuration => api_error(
            StatusCode::SERVICE_UNAVAILABLE,
            "oidc_unavailable",
            "Identity provider is unavailable",
        ),
        OidcRuntimeError::InvalidFlow => invalid_flow(),
    }
}

fn stepup_error(error: StepupError) -> Response {
    match error {
        StepupError::Unauthorized => auth_error(),
        StepupError::InvalidCredentials => api_error(
            StatusCode::UNAUTHORIZED,
            "invalid_credentials",
            "Password is incorrect",
        ),
        StepupError::Verification | StepupError::Database(_) => internal_error(),
        StepupError::Identity(error) => identity_error(error),
    }
}

fn identity_error(error: OidcIdentityError) -> Response {
    match error {
        OidcIdentityError::RecentAuthenticationRequired => reauth_required(),
        OidcIdentityError::IdentityAlreadyLinked => api_error(
            StatusCode::CONFLICT,
            "identity_already_linked",
            "This identity is already linked",
        ),
        OidcIdentityError::LastLoginMethod => api_error(
            StatusCode::CONFLICT,
            "last_login_method",
            "Set up another login method before removing this identity",
        ),
        OidcIdentityError::NotFound => not_found(),
        OidcIdentityError::InvalidIdentity => validation_error("identity", "Identity is invalid"),
        OidcIdentityError::Forbidden => api_error(
            StatusCode::FORBIDDEN,
            "forbidden",
            "This operation is not allowed",
        ),
        OidcIdentityError::InvalidTokenExpiry | OidcIdentityError::TokenGeneration => {
            internal_error()
        }
        OidcIdentityError::TokenRateLimited(retry_after) => {
            access::rate_limited(retry_after, "Too many API tokens have been created")
        }
        OidcIdentityError::Database(_) => internal_error(),
    }
}
