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
    is_admin: bool,
    user: Option<MetaUser>,
    /// Id of the bearer PAT making this request; absent for browser sessions
    /// and anonymous callers. Never the token value or hash.
    #[serde(skip_serializing_if = "Option::is_none")]
    current_token_id: Option<i64>,
    auth: AuthDiscovery,
}

#[derive(Serialize)]
struct AuthDiscovery {
    password: bool,
    oidc: Vec<ProviderSummary>,
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

    Json(MetaEnvelope {
        data: MetaData {
            version: env!("CARGO_PKG_VERSION"),
            build_id: state.app.build_id,
            is_admin,
            user,
            current_token_id,
            auth: AuthDiscovery {
                password: true,
                oidc,
            },
        },
    })
    .into_response()
}
