//! Account self-service routes for browser sessions and bearer PATs.
//!
//! A request carrying an `Authorization` header is authenticated only as an
//! API-purpose PAT (no cookie fallback, no CSRF). Otherwise it needs a browser
//! session and, for mutations, Origin/CSRF.

use std::{collections::BTreeMap, net::SocketAddr};

use axum::{
    Extension, Json, Router,
    extract::{
        ConnectInfo, DefaultBodyLimit, Multipart, Path, State, multipart::MultipartRejection,
        rejection::JsonRejection,
    },
    http::{HeaderMap, StatusCode, header::AUTHORIZATION},
    response::{IntoResponse, Response},
    routing::{get, post},
};
use serde::Deserialize;
use zeroize::Zeroizing;

use crate::{
    account_service::{
        self, AccountError, BearerCaller, BearerStepUp, CreateTokenOptions, DeleteProof,
    },
    auth,
    auth_stepup_service::{self, StepupError},
    media,
};

use super::{
    ApiV2State, Envelope,
    access::{rate_limited, resource_mutation_user, resource_user},
};

const MAX_AVATAR_REQUEST_BYTES: usize = 26 * 1024 * 1024;

pub(super) fn router() -> Router<ApiV2State> {
    let json_routes = Router::new()
        .route("/account", get(get_account).patch(update_account))
        .route("/account/api-tokens", get(list_tokens).post(create_token))
        .route(
            "/account/api-tokens/current",
            axum::routing::patch(rename_current_token),
        )
        .route(
            "/account/api-tokens/{id}",
            axum::routing::delete(revoke_token),
        )
        .route("/account/password", axum::routing::put(change_password))
        .route("/account/delete", post(delete_account))
        .layer(DefaultBodyLimit::max(2 * 1024 * 1024));
    let avatar_routes = Router::new()
        .route("/account/avatar", post(upload_avatar).delete(delete_avatar))
        .layer(DefaultBodyLimit::max(MAX_AVATAR_REQUEST_BYTES));
    json_routes.merge(avatar_routes)
}

async fn get_account(State(state): State<ApiV2State>, headers: HeaderMap) -> Response {
    let user_id = match resource_user(&state.app, &headers).await {
        Ok(user_id) => user_id,
        Err(error) => return *error,
    };
    match account_service::account_dto(
        &state.app.pool,
        user_id,
        state.app.gravatar.enabled,
        state.app.error_reporting.is_available(),
    )
    .await
    {
        Ok(data) => Json(Envelope { data }).into_response(),
        Err(error) => account_error(error),
    }
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct UpdateAccountRequest {
    use_gravatar: Option<bool>,
    error_reporting_enabled: Option<bool>,
}

async fn update_account(
    State(state): State<ApiV2State>,
    headers: HeaderMap,
    request: Result<Json<UpdateAccountRequest>, JsonRejection>,
) -> Response {
    let user_id = match resource_mutation_user(&state.app, &headers).await {
        Ok(user_id) => user_id,
        Err(error) => return *error,
    };
    let Json(request) = match request {
        Ok(request) => request,
        Err(error) => return json_rejection(error),
    };
    if let Some(enabled) = request.use_gravatar
        && let Err(error) =
            account_service::set_use_gravatar(&state.app.pool, user_id, enabled).await
    {
        return account_error(error);
    }
    if let Some(enabled) = request.error_reporting_enabled
        && let Err(error) =
            account_service::set_error_reporting(&state.app.pool, user_id, enabled).await
    {
        return account_error(error);
    }
    match account_service::account_dto(
        &state.app.pool,
        user_id,
        state.app.gravatar.enabled,
        state.app.error_reporting.is_available(),
    )
    .await
    {
        Ok(data) => Json(Envelope { data }).into_response(),
        Err(error) => account_error(error),
    }
}

async fn list_tokens(State(state): State<ApiV2State>, headers: HeaderMap) -> Response {
    let user_id = match resource_user(&state.app, &headers).await {
        Ok(user_id) => user_id,
        Err(error) => return *error,
    };
    match account_service::list_tokens(&state.app.pool, user_id).await {
        Ok(data) => Json(Envelope { data }).into_response(),
        Err(error) => account_error(error),
    }
}

#[derive(Deserialize)]
struct CreateTokenRequest {
    name: Option<String>,
    /// Optional requested expiry (RFC 3339). Bearer-created tokens are clamped
    /// to the calling token's own expiry.
    expires_at: Option<String>,
    /// Step-up for bearer callers (alternative: `X-Reauth-Token`).
    current_password: Option<String>,
}

async fn create_token(
    State(state): State<ApiV2State>,
    headers: HeaderMap,
    peer: Option<Extension<ConnectInfo<SocketAddr>>>,
    request: Result<Json<CreateTokenRequest>, JsonRejection>,
) -> Response {
    let user_id = match resource_mutation_user(&state.app, &headers).await {
        Ok(user_id) => user_id,
        Err(error) => return *error,
    };
    let Json(mut request) = match request {
        Ok(request) => request,
        Err(error) => return json_rejection(error),
    };
    let requested_expires_at = match request.expires_at.as_deref().map(parse_expiry) {
        None => None,
        Some(Some(at)) => Some(at),
        Some(None) => return field_error("expires_at", "Expiry must be an RFC 3339 timestamp"),
    };
    let mut options = CreateTokenOptions {
        requested_expires_at,
        bearer: None,
    };
    if is_bearer(&headers) {
        let credential = match auth_stepup_service::current_credential(
            &state.app.pool,
            &headers,
            user_id,
            state.app.secure_cookies,
        )
        .await
        {
            Ok(credential) => credential,
            Err(error) => return stepup_error(error),
        };
        let stepup = if headers.contains_key("x-reauth-token") {
            match auth_stepup_service::native_proof_hash(&headers) {
                Ok(hash) => BearerStepUp::Proof(hash),
                Err(error) => return stepup_error(error),
            }
        } else {
            let password = Zeroizing::new(request.current_password.take().unwrap_or_default());
            if password.is_empty() {
                return reauth_required();
            }
            if password.len() > account_service::MAX_PASSWORD_BYTES {
                return field_error("current_password", "Current password is invalid");
            }
            if let Some(response) = reserve_password_attempt(&state, peer) {
                return response;
            }
            if let Err(error) =
                auth_stepup_service::verify_current_password(&state.app.pool, user_id, password)
                    .await
            {
                return stepup_error(error);
            }
            BearerStepUp::PasswordVerified
        };
        options.bearer = Some(BearerCaller {
            token_id: credential.token_id,
            stepup,
        });
    }
    match account_service::create_token_with(&state.app.pool, user_id, request.name, options).await
    {
        Ok(token) => (
            StatusCode::CREATED,
            Json(Envelope {
                data: CreatedTokenDto {
                    id: token.id,
                    name: token.name,
                    created_at: token.created_at,
                    expires_at: token.expires_at,
                    token: token.token.to_string(),
                },
            }),
        )
            .into_response(),
        Err(error) => account_error(error),
    }
}

fn is_bearer(headers: &HeaderMap) -> bool {
    headers.contains_key(AUTHORIZATION)
}

fn parse_expiry(value: &str) -> Option<i64> {
    chrono::DateTime::parse_from_rfc3339(value)
        .ok()
        .map(|at| at.timestamp())
}

/// Bearer password checks share the reauthentication attempt budget.
fn reserve_password_attempt(
    state: &ApiV2State,
    peer: Option<Extension<ConnectInfo<SocketAddr>>>,
) -> Option<Response> {
    let key = format!(
        "reauth:{}",
        peer.map(|Extension(ConnectInfo(address))| address.ip().to_string())
            .unwrap_or_else(|| "unknown".to_owned())
    );
    state.app.auth_limiter.reserve(&[key]).is_none().then(|| {
        rate_limited(
            auth::RATE_LIMIT_WINDOW.as_secs(),
            "Too many password attempts",
        )
    })
}

fn reauth_required() -> Response {
    super::api_error(
        StatusCode::FORBIDDEN,
        "reauth_required",
        "Reauthentication is required",
    )
}

fn stepup_error(error: StepupError) -> Response {
    match error {
        StepupError::Unauthorized => super::api_error(
            StatusCode::UNAUTHORIZED,
            "session_expired",
            "Sign in to continue",
        ),
        StepupError::InvalidCredentials => super::api_error(
            StatusCode::UNAUTHORIZED,
            "invalid_credentials",
            "Password is incorrect",
        ),
        StepupError::Identity(_) => reauth_required(),
        StepupError::Verification | StepupError::Database(_) => super::api_error(
            StatusCode::INTERNAL_SERVER_ERROR,
            "internal_error",
            "Request failed",
        ),
    }
}

#[derive(Deserialize)]
struct RenameTokenRequest {
    name: String,
}

/// Rename the calling PAT. Browser sessions have no device name to set.
async fn rename_current_token(
    State(state): State<ApiV2State>,
    headers: HeaderMap,
    request: Result<Json<RenameTokenRequest>, JsonRejection>,
) -> Response {
    let user_id = match resource_mutation_user(&state.app, &headers).await {
        Ok(user_id) => user_id,
        Err(error) => return *error,
    };
    if !is_bearer(&headers) {
        return super::api_error(
            StatusCode::FORBIDDEN,
            "forbidden",
            "Only an app token can rename itself",
        );
    }
    let Json(request) = match request {
        Ok(request) => request,
        Err(error) => return json_rejection(error),
    };
    let Some(name) = account_service::clean_device_name(&request.name) else {
        return field_error("name", "Name must be 1 to 100 characters");
    };
    let credential = match auth_stepup_service::current_credential(
        &state.app.pool,
        &headers,
        user_id,
        state.app.secure_cookies,
    )
    .await
    {
        Ok(credential) => credential,
        Err(error) => return stepup_error(error),
    };
    match account_service::rename_token(&state.app.pool, user_id, credential.token_id, name).await {
        Ok(data) => Json(Envelope { data }).into_response(),
        Err(error) => account_error(error),
    }
}

#[derive(serde::Serialize)]
struct CreatedTokenDto {
    id: i64,
    name: String,
    created_at: String,
    expires_at: Option<String>,
    token: String,
}

async fn revoke_token(
    State(state): State<ApiV2State>,
    headers: HeaderMap,
    Path(id): Path<i64>,
) -> Response {
    let user_id = match resource_mutation_user(&state.app, &headers).await {
        Ok(user_id) => user_id,
        Err(error) => return *error,
    };
    // Resolve the caller's own token before revoking so the response can say
    // whether this call signed the caller out.
    let current_token_id = if is_bearer(&headers) {
        match auth_stepup_service::current_credential(
            &state.app.pool,
            &headers,
            user_id,
            state.app.secure_cookies,
        )
        .await
        {
            Ok(credential) => Some(credential.token_id),
            Err(error) => return stepup_error(error),
        }
    } else {
        None
    };
    match account_service::revoke_token(&state.app.pool, user_id, id).await {
        Ok(true) => match current_token_id {
            Some(current) => Json(Envelope {
                data: RevokedTokenDto {
                    id,
                    revoked: true,
                    current_token: current == id,
                },
            })
            .into_response(),
            None => StatusCode::NO_CONTENT.into_response(),
        },
        Ok(false) => super::api_error(StatusCode::NOT_FOUND, "not_found", "API token not found"),
        Err(error) => account_error(error),
    }
}

/// Bearer revoke result. `current_token` true means the caller revoked its
/// own token and every further request with it gets `401 session_expired`.
#[derive(serde::Serialize)]
struct RevokedTokenDto {
    id: i64,
    revoked: bool,
    current_token: bool,
}

#[derive(Deserialize)]
struct ChangePasswordRequest {
    current_password: String,
    new_password: String,
}

async fn change_password(
    State(state): State<ApiV2State>,
    headers: HeaderMap,
    peer: Option<Extension<ConnectInfo<SocketAddr>>>,
    request: Result<Json<ChangePasswordRequest>, JsonRejection>,
) -> Response {
    let user_id = match resource_mutation_user(&state.app, &headers).await {
        Ok(user_id) => user_id,
        Err(error) => return *error,
    };
    let Json(mut request) = match request {
        Ok(request) => request,
        Err(error) => return json_rejection(error),
    };
    let current_password = Zeroizing::new(std::mem::take(&mut request.current_password));
    let new_password = Zeroizing::new(std::mem::take(&mut request.new_password));
    if current_password.is_empty() || current_password.len() > account_service::MAX_PASSWORD_BYTES {
        return field_error("current_password", "Current password is invalid");
    }
    if is_bearer(&headers)
        && let Some(response) = reserve_password_attempt(&state, peer)
    {
        return response;
    }
    match account_service::change_password(&state.app.pool, user_id, current_password, new_password)
        .await
    {
        Ok(()) => {
            // Every browser session and PAT of the account is revoked,
            // including the bearer token that made this request.
            let mut response = StatusCode::NO_CONTENT.into_response();
            super::append_set_cookie(
                &mut response,
                super::clear_cookie(
                    auth::session_cookie_name(state.app.secure_cookies),
                    state.app.secure_cookies,
                ),
            );
            response
        }
        Err(error) => account_error(error),
    }
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct DeleteAccountRequest {
    /// `DELETE` or the account email.
    confirmation: String,
    /// Re-authentication by password. Without it a cookie session needs a
    /// fresh OIDC/password step-up stamp and a bearer PAT an `X-Reauth-Token`.
    current_password: Option<String>,
}

/// Permanently delete the caller's account and all of its data. Requires
/// re-authentication on top of the session or token (see the contract doc).
async fn delete_account(
    State(state): State<ApiV2State>,
    headers: HeaderMap,
    peer: Option<Extension<ConnectInfo<SocketAddr>>>,
    request: Result<Json<DeleteAccountRequest>, JsonRejection>,
) -> Response {
    let user_id = match resource_mutation_user(&state.app, &headers).await {
        Ok(user_id) => user_id,
        Err(error) => return *error,
    };
    let Json(mut request) = match request {
        Ok(request) => request,
        Err(error) => return json_rejection(error),
    };
    if request.confirmation.trim().is_empty() || request.confirmation.len() > 320 {
        return field_error(
            "confirmation",
            "Type DELETE or your email address to confirm",
        );
    }
    let credential = match auth_stepup_service::current_credential(
        &state.app.pool,
        &headers,
        user_id,
        state.app.secure_cookies,
    )
    .await
    {
        Ok(credential) => credential,
        Err(error) => return stepup_error(error),
    };
    let password = request
        .current_password
        .take()
        .map(Zeroizing::new)
        .filter(|password| !password.is_empty());
    let bearer = is_bearer(&headers);
    let proof = if let Some(password) = password {
        if password.len() > account_service::MAX_PASSWORD_BYTES {
            return field_error("current_password", "Current password is invalid");
        }
        if let Some(response) = reserve_password_attempt(&state, peer) {
            return response;
        }
        if let Err(error) =
            auth_stepup_service::verify_current_password(&state.app.pool, user_id, password).await
        {
            return stepup_error(error);
        }
        DeleteProof::PasswordVerified
    } else if !bearer {
        DeleteProof::BrowserSession {
            token_id: credential.token_id,
        }
    } else if headers.contains_key("x-reauth-token") {
        match auth_stepup_service::native_proof_hash(&headers) {
            Ok(proof_hash) => DeleteProof::NativeProof {
                token_id: credential.token_id,
                proof_hash,
            },
            Err(error) => return stepup_error(error),
        }
    } else {
        return reauth_required();
    };
    match account_service::delete_account(&state.app, user_id, proof, &request.confirmation).await {
        Ok(()) => {
            let mut response = StatusCode::NO_CONTENT.into_response();
            if !bearer {
                super::append_set_cookie(
                    &mut response,
                    super::clear_cookie(
                        auth::session_cookie_name(state.app.secure_cookies),
                        state.app.secure_cookies,
                    ),
                );
            }
            response
        }
        Err(error) => account_error(error),
    }
}

async fn upload_avatar(
    State(state): State<ApiV2State>,
    headers: HeaderMap,
    multipart: Result<Multipart, MultipartRejection>,
) -> Response {
    let user_id = match resource_mutation_user(&state.app, &headers).await {
        Ok(user_id) => user_id,
        Err(error) => return *error,
    };
    let Some(root) = state.app.media_root.as_deref() else {
        return super::api_error(
            StatusCode::SERVICE_UNAVAILABLE,
            "media_unavailable",
            "Media storage is unavailable",
        );
    };
    let multipart = match multipart {
        Ok(multipart) => multipart,
        Err(error) => return multipart_error(error),
    };
    let upload = match media::read_avatar_upload(multipart).await {
        Ok(upload) => upload,
        Err(error) => return upload_error(error),
    };
    match account_service::save_avatar(&state.app.pool, root, user_id, upload).await {
        Ok(avatar_url) => Json(Envelope {
            data: AvatarDto { avatar_url },
        })
        .into_response(),
        Err(error) => account_error(error),
    }
}

async fn delete_avatar(State(state): State<ApiV2State>, headers: HeaderMap) -> Response {
    let user_id = match resource_mutation_user(&state.app, &headers).await {
        Ok(user_id) => user_id,
        Err(error) => return *error,
    };
    match account_service::delete_avatar(&state.app.pool, user_id).await {
        Ok(()) => StatusCode::NO_CONTENT.into_response(),
        Err(error) => account_error(error),
    }
}

#[derive(serde::Serialize)]
struct AvatarDto {
    avatar_url: String,
}

fn field_error(field: &'static str, message: &'static str) -> Response {
    super::api_error_fields(
        StatusCode::UNPROCESSABLE_ENTITY,
        "validation_error",
        "Account fields are invalid",
        BTreeMap::from([(field, message)]),
    )
}

fn account_error(error: AccountError) -> Response {
    match error {
        AccountError::Database => super::api_error(
            StatusCode::INTERNAL_SERVER_ERROR,
            "internal_error",
            "Request failed",
        ),
        AccountError::InvalidTokenName => {
            field_error("name", "Token names must be 255 bytes or fewer")
        }
        AccountError::TokenRateLimited(retry_after) => {
            rate_limited(retry_after, "API token creation limit reached")
        }
        AccountError::TokenNotFound => super::api_error(
            StatusCode::NOT_FOUND,
            "not_found",
            "Account resource not found",
        ),
        AccountError::PasswordTooLong => {
            field_error("new_password", "Passwords must be 1024 bytes or fewer")
        }
        AccountError::PasswordPolicy(crate::auth::NewPasswordError::TooShort) => field_error(
            "new_password",
            "Password must contain at least 12 characters",
        ),
        AccountError::PasswordPolicy(crate::auth::NewPasswordError::Common) => {
            field_error("new_password", "Choose a less common password")
        }
        AccountError::CurrentPasswordIncorrect => {
            field_error("current_password", "Current password is incorrect")
        }
        AccountError::PasswordConflict => super::api_error(
            StatusCode::CONFLICT,
            "conflict",
            "Account credentials changed; sign in again",
        ),
        AccountError::InvalidExpiry => field_error("expires_at", "Expiry must be in the future"),
        AccountError::CallerTokenInvalid => super::api_error(
            StatusCode::UNAUTHORIZED,
            "session_expired",
            "Sign in to continue",
        ),
        AccountError::ReauthRequired => reauth_required(),
        AccountError::ConfirmationMismatch => field_error(
            "confirmation",
            "Type DELETE or your email address to confirm",
        ),
        AccountError::LastAdministrator => super::api_error(
            StatusCode::CONFLICT,
            "last_admin",
            "You are the last administrator. Make someone else an administrator first",
        ),
        AccountError::InvalidAvatar(media::UploadError::TooLarge) => super::api_error(
            StatusCode::PAYLOAD_TOO_LARGE,
            "payload_too_large",
            "Uploaded image is too large",
        ),
        AccountError::InvalidAvatar(media::UploadError::InvalidImage) => super::api_error(
            StatusCode::BAD_REQUEST,
            "invalid_image",
            "Uploaded file is not a supported image",
        ),
        AccountError::InvalidAvatar(media::UploadError::BadMultipart) => super::api_error(
            StatusCode::BAD_REQUEST,
            "invalid_multipart",
            "Multipart upload is invalid",
        ),
    }
}

fn multipart_error(error: MultipartRejection) -> Response {
    if error.status() == StatusCode::PAYLOAD_TOO_LARGE {
        super::api_error(
            StatusCode::PAYLOAD_TOO_LARGE,
            "payload_too_large",
            "Uploaded image is too large",
        )
    } else {
        super::api_error(
            StatusCode::BAD_REQUEST,
            "invalid_multipart",
            "Multipart upload is invalid",
        )
    }
}

fn json_rejection(error: JsonRejection) -> Response {
    if error.status() == StatusCode::PAYLOAD_TOO_LARGE {
        super::api_error(
            StatusCode::PAYLOAD_TOO_LARGE,
            "payload_too_large",
            "Request body is too large",
        )
    } else {
        super::api_error(
            StatusCode::BAD_REQUEST,
            "invalid_json",
            "Invalid JSON request",
        )
    }
}

fn upload_error(error: media::UploadError) -> Response {
    account_error(AccountError::InvalidAvatar(error))
}
