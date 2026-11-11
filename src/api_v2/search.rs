//! Typeahead filter suggestions for the search bars of the project and yarn lists.

use axum::{
    Json, Router,
    extract::{Query, State, rejection::QueryRejection},
    http::{HeaderMap, StatusCode},
    response::{IntoResponse, Response},
    routing::get,
};
use serde::Deserialize;

use crate::search_suggestions::{self as suggestions, SuggestionKind};

use super::{ApiV2State, Envelope, access::resource_user, api_error};

pub(super) fn router() -> Router<ApiV2State> {
    Router::new().route("/search/suggestions", get(search_suggestions))
}

#[derive(Debug, Deserialize)]
struct SuggestionQuery {
    q: Option<String>,
    /// `projects` (default) or `yarns`.
    kind: Option<String>,
}

/// Tag, category and (administrators only) owner suggestions for the typed text.
async fn search_suggestions(
    State(state): State<ApiV2State>,
    headers: HeaderMap,
    query: Result<Query<SuggestionQuery>, QueryRejection>,
) -> Response {
    let user_id = match resource_user(&state.app, &headers).await {
        Ok(user_id) => user_id,
        Err(response) => return *response,
    };
    let Ok(Query(query)) = query else {
        return api_error(
            StatusCode::UNPROCESSABLE_ENTITY,
            "validation_error",
            "Invalid query",
        );
    };
    let kind = match query.kind.as_deref() {
        None => SuggestionKind::Projects,
        Some(value) => match SuggestionKind::parse(value) {
            Some(kind) => kind,
            None => {
                return api_error(
                    StatusCode::UNPROCESSABLE_ENTITY,
                    "validation_error",
                    "Invalid kind",
                );
            }
        },
    };
    match suggestions::suggest(&state.app, user_id, kind, query.q.as_deref().unwrap_or("")).await {
        Ok(data) => Json(Envelope { data }).into_response(),
        Err(_) => api_error(
            StatusCode::INTERNAL_SERVER_ERROR,
            "internal_error",
            "Request failed",
        ),
    }
}
