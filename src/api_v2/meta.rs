//! Public API metadata with optional current-user role information.

use super::{ApiV2State, access, api_error};
use axum::{
    Json, Router,
    extract::State,
    http::StatusCode,
    response::{IntoResponse, Response},
    routing::get,
};
use serde::Serialize;

#[derive(Serialize)]
struct MetaEnvelope {
    data: MetaData,
}

#[derive(Serialize)]
struct MetaData {
    version: &'static str,
    build_id: String,
    /// Commit the server was built from (`9a7bfc70`, maybe `-dirty`); null when
    /// the build did not record one. `build_id` changes on every start instead.
    revision: Option<String>,
    is_admin: bool,
    /// Whether public self-service registration is enabled on this instance.
    registration_enabled: bool,
    /// What link imports can do on this instance, so clients can hide or enable the entry points.
    imports: MetaImports,
    /// Saving a source link to the web archive is enabled, so native clients can
    /// offer the archive control (`/api/v2/{projects,yarns}/{id}/archive`).
    archive: bool,
    user: Option<MetaUser>,
    /// Id of the bearer PAT making this request; absent for browser sessions
    /// and anonymous callers. Never the token value or hash.
    #[serde(skip_serializing_if = "Option::is_none")]
    current_token_id: Option<i64>,
    auth: AuthDiscovery,
    error_reporting: super::telemetry::ErrorReportingInfo,
    /// Changes whenever an administrator restored a backup. Clients that
    /// remember another value discard their caches and queued changes.
    #[serde(skip_serializing_if = "Option::is_none")]
    data_epoch: Option<String>,
    /// Outcome of the last backup restore, so a client that waited for the
    /// restart can tell success from failure.
    #[serde(skip_serializing_if = "Option::is_none")]
    last_restore: Option<LastRestore>,
}

#[derive(Serialize)]
struct LastRestore {
    status: String,
    finished_at: i64,
}

#[derive(Serialize)]
struct MetaImports {
    /// Importing a project or yarn from a web link is enabled.
    url: bool,
    /// An AI provider is configured for gap filling.
    ai: bool,
}

#[derive(Serialize)]
struct AuthDiscovery {
    password: bool,
    oidc: Vec<ProviderSummary>,
    /// Passkey (WebAuthn) sign-in is available, so native clients can offer it.
    passkeys: bool,
}

#[derive(Serialize)]
struct ProviderSummary {
    id: String,
    name: &'static str,
}

#[derive(Serialize)]
struct MetaUser {
    id: i64,
    email: String,
    is_admin: bool,
    avatar_url: Option<String>,
    avatar_source: Option<&'static str>,
}

pub(super) fn router() -> Router<ApiV2State> {
    Router::new().route("/meta", get(meta))
}

async fn meta(State(state): State<ApiV2State>, headers: axum::http::HeaderMap) -> Response {
    let user_id = match access::optional_resource_user(&state.app, &headers).await {
        Ok(user_id) => user_id,
        Err(response) => return *response,
    };

    let user = match user_id {
        Some(user_id) => {
            match super::load_user(&state.app.pool, user_id, state.app.gravatar.enabled).await {
                Ok(user) => user.map(|user| MetaUser {
                    id: user.id,
                    email: user.email,
                    is_admin: user.is_admin,
                    avatar_url: user.avatar_url,
                    avatar_source: user.avatar_source,
                }),
                Err(_) => {
                    return api_error(
                        StatusCode::INTERNAL_SERVER_ERROR,
                        "internal_error",
                        "Request failed",
                    );
                }
            }
        }
        None => None,
    };
    let current_token_id = match (
        user_id,
        headers.contains_key(axum::http::header::AUTHORIZATION),
    ) {
        (Some(user_id), true) => match crate::auth_stepup_service::current_credential(
            &state.app.pool,
            &headers,
            user_id,
            state.app.secure_cookies,
        )
        .await
        {
            Ok(credential) => Some(credential.token_id),
            Err(_) => None,
        },
        _ => None,
    };
    let is_admin = user.as_ref().is_some_and(|user| user.is_admin);
    let oidc = state
        .app
        .oidc
        .as_ref()
        .map(|runtime| ProviderSummary {
            id: runtime.provider_id().to_owned(),
            name: "Authelia",
        })
        .into_iter()
        .collect();

    let error_reporting = super::telemetry::ErrorReportingInfo::load(&state, user_id).await;
    let data_epoch = state
        .app
        .backups
        .as_ref()
        .map(|backups| backups.data_epoch().to_owned());
    let last_restore = state
        .app
        .backups
        .as_ref()
        .and_then(|backups| backups.last_restore())
        .map(|outcome| LastRestore {
            status: outcome.status.clone(),
            finished_at: outcome.finished_at,
        });
    Json(MetaEnvelope {
        data: MetaData {
            version: env!("CARGO_PKG_VERSION"),
            build_id: state.app.build_id,
            revision: crate::error_reporting::git_revision(),
            is_admin,
            registration_enabled: state.app.signup_enabled,
            imports: MetaImports {
                url: state.app.external_imports && state.imports.is_some(),
                ai: state.ai_enabled
                    && state
                        .imports
                        .as_ref()
                        .is_some_and(|imports| imports.ai.is_some()),
            },
            archive: state.app.archive_enabled,
            user,
            current_token_id,
            auth: AuthDiscovery {
                password: true,
                oidc,
                passkeys: state.app.passkeys.is_some(),
            },
            error_reporting,
            data_epoch,
            last_restore,
        },
    })
    .into_response()
}
