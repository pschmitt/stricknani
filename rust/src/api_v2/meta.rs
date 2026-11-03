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
}

#[derive(Serialize)]
struct MetaUser {
    id: i64,
    email: String,
    is_admin: bool,
    avatar_url: Option<String>,
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
        Some(user_id) => match super::load_user(&state.app.pool, user_id).await {
            Ok(user) => user.map(|user| MetaUser {
                id: user.id,
                email: user.email,
                is_admin: user.is_admin,
                avatar_url: user.avatar_url,
            }),
            Err(_) => {
                return api_error(
                    StatusCode::INTERNAL_SERVER_ERROR,
                    "internal_error",
                    "Request failed",
                );
            }
        },
        None => None,
    };
    let is_admin = user.as_ref().is_some_and(|user| user.is_admin);

    Json(MetaEnvelope {
        data: MetaData {
            version: env!("CARGO_PKG_VERSION"),
            build_id: state.app.build_id,
            is_admin,
            user,
        },
    })
    .into_response()
}
