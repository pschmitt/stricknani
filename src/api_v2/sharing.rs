//! Sharing, leaving and ownership transfer of projects and yarns, plus the
//! people search used to pick recipients. See `docs/sharing-api.md`.

use axum::{
    Json, Router,
    extract::{
        DefaultBodyLimit, Path, Query, State, rejection::JsonRejection, rejection::QueryRejection,
    },
    http::{HeaderMap, StatusCode},
    response::{IntoResponse, Response},
    routing::{get, post},
};
use serde::{Deserialize, Serialize};

use crate::{
    resource_access::{AccessInfo, ResourceKind, Surface, grant},
    sharing::{self, Role, ShareError},
};

use super::{
    ApiV2State, Envelope,
    access::{resource_mutation_user, resource_user},
    api_error,
};

pub(super) fn router() -> Router<ApiV2State> {
    Router::new()
        .route(
            "/projects/{id}/shares",
            get(list_project_shares).post(add_project_share),
        )
        .route(
            "/projects/{id}/shares/{user_id}",
            axum::routing::patch(change_project_share).delete(revoke_project_share),
        )
        .route(
            "/projects/{id}/yarn-candidates",
            get(project_yarn_candidates),
        )
        .route("/projects/{id}/leave", post(leave_project))
        .route("/projects/{id}/transfer", post(transfer_project))
        .route(
            "/yarns/{id}/shares",
            get(list_yarn_shares).post(add_yarn_share),
        )
        .route(
            "/yarns/{id}/shares/{user_id}",
            axum::routing::patch(change_yarn_share).delete(revoke_yarn_share),
        )
        .route("/yarns/{id}/leave", post(leave_yarn))
        .route("/yarns/{id}/transfer", post(transfer_yarn))
        .route("/people", get(search_people))
        .route("/people/{id}/avatar/{filename}", get(person_avatar))
        .layer(DefaultBodyLimit::max(8 * 1024))
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct AddShareBody {
    user_id: i64,
    role: String,
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct ChangeShareBody {
    role: String,
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct TransferBody {
    new_owner_id: i64,
    #[serde(default)]
    move_linked_yarns: bool,
}

#[derive(Deserialize)]
struct PeopleQuery {
    q: Option<String>,
    page: Option<i64>,
}

#[derive(Serialize)]
struct TransferDto {
    new_owner: sharing::Person,
    /// The caller's access after the transfer.
    access: AccessInfo,
    moved_yarn_ids: Vec<i64>,
}

fn share_error(error: ShareError) -> Response {
    match error {
        ShareError::NotFound => api_error(StatusCode::NOT_FOUND, "not_found", "Resource not found"),
        ShareError::Forbidden => api_error(
            StatusCode::FORBIDDEN,
            "forbidden",
            "Only the owner or an administrator can do this",
        ),
        ShareError::Invalid(message) => api_error(
            StatusCode::UNPROCESSABLE_ENTITY,
            "validation_error",
            message,
        ),
        ShareError::Conflict(message) => api_error(StatusCode::CONFLICT, "conflict", message),
        ShareError::Database => api_error(
            StatusCode::INTERNAL_SERVER_ERROR,
            "internal_error",
            "Request failed",
        ),
    }
}

fn bad_body(_rejection: JsonRejection) -> Response {
    api_error(
        StatusCode::UNPROCESSABLE_ENTITY,
        "validation_error",
        "Invalid request body",
    )
}

fn bad_role() -> Response {
    api_error(
        StatusCode::UNPROCESSABLE_ENTITY,
        "validation_error",
        "Role must be viewer or editor",
    )
}

// --- generic handlers -------------------------------------------------------

async fn list_shares(
    state: ApiV2State,
    headers: HeaderMap,
    kind: ResourceKind,
    id: i64,
) -> Response {
    let user_id = match resource_user(&state.app, &headers).await {
        Ok(user_id) => user_id,
        Err(response) => return *response,
    };
    match sharing::list_shares(&state.app, user_id, kind, id).await {
        Ok(list) => Json(Envelope { data: list }).into_response(),
        Err(error) => share_error(error),
    }
}

async fn add_share(
    state: ApiV2State,
    headers: HeaderMap,
    kind: ResourceKind,
    id: i64,
    body: Result<Json<AddShareBody>, JsonRejection>,
) -> Response {
    let user_id = match resource_mutation_user(&state.app, &headers).await {
        Ok(user_id) => user_id,
        Err(response) => return *response,
    };
    let Json(body) = match body {
        Ok(body) => body,
        Err(rejection) => return bad_body(rejection),
    };
    let Some(role) = Role::parse(&body.role) else {
        return bad_role();
    };
    match sharing::add_share(&state.app, user_id, kind, id, body.user_id, role).await {
        Ok(entry) => (StatusCode::CREATED, Json(Envelope { data: entry })).into_response(),
        Err(error) => share_error(error),
    }
}

async fn change_share(
    state: ApiV2State,
    headers: HeaderMap,
    kind: ResourceKind,
    id: i64,
    target: i64,
    body: Result<Json<ChangeShareBody>, JsonRejection>,
) -> Response {
    let user_id = match resource_mutation_user(&state.app, &headers).await {
        Ok(user_id) => user_id,
        Err(response) => return *response,
    };
    let Json(body) = match body {
        Ok(body) => body,
        Err(rejection) => return bad_body(rejection),
    };
    let Some(role) = Role::parse(&body.role) else {
        return bad_role();
    };
    match sharing::change_share(&state.app, user_id, kind, id, target, role).await {
        Ok(entry) => Json(Envelope { data: entry }).into_response(),
        Err(error) => share_error(error),
    }
}

async fn revoke_share(
    state: ApiV2State,
    headers: HeaderMap,
    kind: ResourceKind,
    id: i64,
    target: i64,
) -> Response {
    let user_id = match resource_mutation_user(&state.app, &headers).await {
        Ok(user_id) => user_id,
        Err(response) => return *response,
    };
    match sharing::revoke_share(&state.app, user_id, kind, id, target).await {
        Ok(()) => StatusCode::NO_CONTENT.into_response(),
        Err(error) => share_error(error),
    }
}

async fn leave(state: ApiV2State, headers: HeaderMap, kind: ResourceKind, id: i64) -> Response {
    let user_id = match resource_mutation_user(&state.app, &headers).await {
        Ok(user_id) => user_id,
        Err(response) => return *response,
    };
    match sharing::leave_share(&state.app, user_id, kind, id).await {
        Ok(()) => StatusCode::NO_CONTENT.into_response(),
        Err(error) => share_error(error),
    }
}

async fn transfer(
    state: ApiV2State,
    headers: HeaderMap,
    kind: ResourceKind,
    id: i64,
    body: Result<Json<TransferBody>, JsonRejection>,
) -> Response {
    let user_id = match resource_mutation_user(&state.app, &headers).await {
        Ok(user_id) => user_id,
        Err(response) => return *response,
    };
    let Json(body) = match body {
        Ok(body) => body,
        Err(rejection) => return bad_body(rejection),
    };
    let outcome = match sharing::transfer(
        &state.app,
        user_id,
        kind,
        id,
        body.new_owner_id,
        body.move_linked_yarns,
    )
    .await
    {
        Ok(outcome) => outcome,
        Err(error) => return share_error(error),
    };
    let access = match grant(&state.app.pool, user_id, kind, id, Surface::V2).await {
        Ok(Some(grant)) => AccessInfo::from(grant),
        _ => {
            return api_error(
                StatusCode::INTERNAL_SERVER_ERROR,
                "internal_error",
                "Request failed",
            );
        }
    };
    let Ok(Some(new_owner)) = sharing::person_by_id(&state.app, body.new_owner_id).await else {
        return api_error(
            StatusCode::INTERNAL_SERVER_ERROR,
            "internal_error",
            "Request failed",
        );
    };
    Json(Envelope {
        data: TransferDto {
            new_owner,
            access,
            moved_yarn_ids: outcome.moved_yarn_ids,
        },
    })
    .into_response()
}

// --- project routes ---------------------------------------------------------

async fn list_project_shares(
    State(state): State<ApiV2State>,
    headers: HeaderMap,
    Path(id): Path<i64>,
) -> Response {
    list_shares(state, headers, ResourceKind::Project, id).await
}

async fn add_project_share(
    State(state): State<ApiV2State>,
    headers: HeaderMap,
    Path(id): Path<i64>,
    body: Result<Json<AddShareBody>, JsonRejection>,
) -> Response {
    add_share(state, headers, ResourceKind::Project, id, body).await
}

async fn change_project_share(
    State(state): State<ApiV2State>,
    headers: HeaderMap,
    Path((id, target)): Path<(i64, i64)>,
    body: Result<Json<ChangeShareBody>, JsonRejection>,
) -> Response {
    change_share(state, headers, ResourceKind::Project, id, target, body).await
}

async fn revoke_project_share(
    State(state): State<ApiV2State>,
    headers: HeaderMap,
    Path((id, target)): Path<(i64, i64)>,
) -> Response {
    revoke_share(state, headers, ResourceKind::Project, id, target).await
}

async fn leave_project(
    State(state): State<ApiV2State>,
    headers: HeaderMap,
    Path(id): Path<i64>,
) -> Response {
    leave(state, headers, ResourceKind::Project, id).await
}

async fn transfer_project(
    State(state): State<ApiV2State>,
    headers: HeaderMap,
    Path(id): Path<i64>,
    body: Result<Json<TransferBody>, JsonRejection>,
) -> Response {
    transfer(state, headers, ResourceKind::Project, id, body).await
}

async fn project_yarn_candidates(
    State(state): State<ApiV2State>,
    headers: HeaderMap,
    Path(id): Path<i64>,
    query: Result<Query<PeopleQuery>, QueryRejection>,
) -> Response {
    let user_id = match resource_user(&state.app, &headers).await {
        Ok(user_id) => user_id,
        Err(response) => return *response,
    };
    let Query(query) = match query {
        Ok(query) => query,
        Err(_) => {
            return api_error(
                StatusCode::UNPROCESSABLE_ENTITY,
                "validation_error",
                "Invalid query",
            );
        }
    };
    let page = query.page.unwrap_or(1);
    if !(1..=1000).contains(&page) || query.q.as_ref().is_some_and(|q| q.len() > 64) {
        return api_error(
            StatusCode::UNPROCESSABLE_ENTITY,
            "validation_error",
            "Invalid query",
        );
    }
    match sharing::linkable_yarns(&state.app, user_id, id, query.q.as_deref(), page).await {
        Ok(page) => Json(Envelope { data: page }).into_response(),
        Err(error) => share_error(error),
    }
}

// --- yarn routes ------------------------------------------------------------

async fn list_yarn_shares(
    State(state): State<ApiV2State>,
    headers: HeaderMap,
    Path(id): Path<i64>,
) -> Response {
    list_shares(state, headers, ResourceKind::Yarn, id).await
}

async fn add_yarn_share(
    State(state): State<ApiV2State>,
    headers: HeaderMap,
    Path(id): Path<i64>,
    body: Result<Json<AddShareBody>, JsonRejection>,
) -> Response {
    add_share(state, headers, ResourceKind::Yarn, id, body).await
}

async fn change_yarn_share(
    State(state): State<ApiV2State>,
    headers: HeaderMap,
    Path((id, target)): Path<(i64, i64)>,
    body: Result<Json<ChangeShareBody>, JsonRejection>,
) -> Response {
    change_share(state, headers, ResourceKind::Yarn, id, target, body).await
}

async fn revoke_yarn_share(
    State(state): State<ApiV2State>,
    headers: HeaderMap,
    Path((id, target)): Path<(i64, i64)>,
) -> Response {
    revoke_share(state, headers, ResourceKind::Yarn, id, target).await
}

async fn leave_yarn(
    State(state): State<ApiV2State>,
    headers: HeaderMap,
    Path(id): Path<i64>,
) -> Response {
    leave(state, headers, ResourceKind::Yarn, id).await
}

async fn transfer_yarn(
    State(state): State<ApiV2State>,
    headers: HeaderMap,
    Path(id): Path<i64>,
    body: Result<Json<TransferBody>, JsonRejection>,
) -> Response {
    transfer(state, headers, ResourceKind::Yarn, id, body).await
}

// --- people -----------------------------------------------------------------

async fn search_people(
    State(state): State<ApiV2State>,
    headers: HeaderMap,
    query: Result<Query<PeopleQuery>, QueryRejection>,
) -> Response {
    let user_id = match resource_user(&state.app, &headers).await {
        Ok(user_id) => user_id,
        Err(response) => return *response,
    };
    let Query(query) = match query {
        Ok(query) => query,
        Err(_) => {
            return api_error(
                StatusCode::UNPROCESSABLE_ENTITY,
                "validation_error",
                "Invalid query",
            );
        }
    };
    if query.q.as_ref().is_some_and(|q| q.len() > 64) {
        return api_error(
            StatusCode::UNPROCESSABLE_ENTITY,
            "validation_error",
            "Search query is too long",
        );
    }
    let page = query.page.unwrap_or(1);
    if !(1..=1000).contains(&page) {
        return api_error(
            StatusCode::UNPROCESSABLE_ENTITY,
            "validation_error",
            "Invalid page",
        );
    }
    match sharing::search_people(&state.app, user_id, query.q.as_deref(), page).await {
        Ok(page) => Json(Envelope { data: page }).into_response(),
        Err(error) => share_error(error),
    }
}

async fn person_avatar(
    State(state): State<ApiV2State>,
    headers: HeaderMap,
    Path((id, filename)): Path<(i64, String)>,
) -> Response {
    if let Err(response) = resource_user(&state.app, &headers).await {
        return *response;
    }
    match crate::media::serve_people_avatar_thumbnail(&state.app, id, &filename).await {
        Ok(response) => response,
        Err(
            crate::media::AdminAvatarError::NotFound | crate::media::AdminAvatarError::Forbidden,
        ) => api_error(StatusCode::NOT_FOUND, "not_found", "Avatar not found"),
        Err(crate::media::AdminAvatarError::Database) => api_error(
            StatusCode::INTERNAL_SERVER_ERROR,
            "internal_error",
            "Request failed",
        ),
        Err(crate::media::AdminAvatarError::Unavailable) => api_error(
            StatusCode::SERVICE_UNAVAILABLE,
            "media_unavailable",
            "Avatar storage is unavailable",
        ),
    }
}
