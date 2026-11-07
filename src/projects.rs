//! Owner-scoped project endpoints for API v1.

use std::collections::HashSet;

use axum::extract::{Path, Query, State};
use axum::http::{HeaderMap, StatusCode};
use axum::response::{IntoResponse, Response};
use axum::routing::{get, post};
use axum::{Json, Router};
use chrono::{DateTime, Duration, SecondsFormat, Utc};
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use sqlx::{FromRow, Sqlite, Transaction};

use crate::audit::{
    Action as AuditAction, ChangeField, ChangeItemV1, ChangeValue, Entity as AuditEntity,
    StepSnapshot,
};
use crate::auth::bearer_user;
use crate::db::AppState;

const PAGE_SIZE: i64 = 50;

/// Build the project endpoints, to be nested under `/api/v1`.
pub fn router() -> Router<AppState> {
    Router::new()
        .route("/projects", get(list_projects).post(create_project))
        .route(
            "/projects/{project_id}",
            get(get_project).put(update_project).delete(delete_project),
        )
        .route(
            "/projects/{project_id}/favorite",
            post(favorite_project).delete(unfavorite_project),
        )
}

#[derive(Debug, Deserialize)]
pub(crate) struct ListQuery {
    pub(crate) page: Option<i64>,
    pub(crate) category: Option<String>,
    pub(crate) tag: Option<String>,
    pub(crate) favorite: Option<bool>,
    pub(crate) q: Option<String>,
    /// `mine` or `all`; administrators default to `all`, everyone else is always `mine`.
    #[serde(default)]
    pub(crate) scope: Option<String>,
    /// Administrator-only restriction to one owner's projects.
    #[serde(default)]
    pub(crate) owner_id: Option<i64>,
}

#[derive(Debug, Deserialize)]
pub(crate) struct ProjectWriteRequest {
    pub(crate) name: String,
    #[serde(default)]
    pub(crate) category: Option<String>,
    #[serde(default)]
    pub(crate) needles: Option<String>,
    #[serde(default)]
    pub(crate) stitch_sample: Option<String>,
    #[serde(default)]
    pub(crate) description: Option<String>,
    #[serde(default)]
    pub(crate) notes: Option<String>,
    #[serde(default)]
    pub(crate) other_materials: Option<String>,
    #[serde(default)]
    pub(crate) tags: Vec<String>,
    #[serde(default)]
    pub(crate) link: Option<String>,
    #[serde(default)]
    pub(crate) is_ai_enhanced: bool,
    #[serde(default)]
    pub(crate) yarn_ids: Vec<i64>,
    /// Yarns to create together with the project and link to it (imports). Create only.
    #[serde(default)]
    pub(crate) new_yarns: Vec<crate::yarns::YarnWriteRequest>,
    #[serde(default)]
    pub(crate) steps: Vec<StepWriteRequest>,
    #[serde(default)]
    pub(crate) image_urls: Vec<String>,
    /// Diagram images and PDFs of an import, downloaded and stored as attachments on create.
    #[serde(default)]
    pub(crate) attachment_urls: Vec<crate::import_document::ImportAsset>,
    #[serde(default)]
    pub(crate) expected_updated_at: Option<String>,
}

#[derive(Debug, Deserialize)]
pub(crate) struct StepWriteRequest {
    #[serde(default)]
    pub(crate) id: Option<i64>,
    pub(crate) title: String,
    #[serde(default)]
    pub(crate) description: Option<String>,
    #[serde(default)]
    pub(crate) step_number: i64,
    #[serde(default)]
    pub(crate) image_urls: Vec<String>,
}

#[derive(Debug, Serialize)]
pub(crate) struct ProjectPage {
    pub(crate) items: Vec<ProjectListItem>,
    pub(crate) page: i64,
    pub(crate) per_page: i64,
    pub(crate) has_more: bool,
    pub(crate) scope: crate::resource_access::Scope,
}

#[derive(Debug, Serialize)]
pub(crate) struct ProjectListItem {
    pub(crate) id: i64,
    pub(crate) name: String,
    pub(crate) category: Option<String>,
    pub(crate) tags: Vec<String>,
    pub(crate) is_favorite: bool,
    pub(crate) updated_at: String,
    pub(crate) preview_url: Option<String>,
    pub(crate) owner: Option<crate::resource_access::OwnerInfo>,
    /// The caller's role; set on API v2 only.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub(crate) access: Option<crate::resource_access::AccessInfo>,
}

#[derive(Debug, Serialize, Clone)]
pub(crate) struct ProjectResponse {
    pub(crate) id: i64,
    pub(crate) name: String,
    pub(crate) category: Option<String>,
    pub(crate) yarn: Option<String>,
    pub(crate) needles: Option<String>,
    pub(crate) stitch_sample: Option<String>,
    pub(crate) description: Option<String>,
    pub(crate) notes: Option<String>,
    pub(crate) other_materials: Option<String>,
    pub(crate) tags: Vec<String>,
    pub(crate) link: Option<String>,
    pub(crate) link_archive: Option<String>,
    pub(crate) is_ai_enhanced: bool,
    pub(crate) is_favorite: bool,
    pub(crate) created_at: String,
    pub(crate) updated_at: String,
    pub(crate) yarn_ids: Vec<i64>,
    pub(crate) steps: Vec<StepResponse>,
    // Media upload/import routes land in a later slice; this DTO returns empty arrays meanwhile.
    pub(crate) images: Vec<Value>,
    pub(crate) attachments: Vec<Value>,
    pub(crate) owner: Option<crate::resource_access::OwnerInfo>,
    /// The caller's role; set on API v2 only.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub(crate) access: Option<crate::resource_access::AccessInfo>,
}

#[derive(Debug, Serialize, Clone)]
pub(crate) struct StepResponse {
    pub(crate) id: i64,
    pub(crate) title: String,
    pub(crate) description: Option<String>,
    pub(crate) step_number: i64,
}

pub(crate) enum ProjectError {
    NotFound,
    /// The caller can read the project but may not do this to it.
    Forbidden,
    Database,
    Invalid(&'static str),
    Conflict(Box<ProjectResponse>),
    ImageImportUnavailable,
}

/// Tag parsing mode at the API boundary: v1 keeps its tokenized labels while
/// browser v2 preserves each JSON array item as a label. The policy also
/// identifies the API generation: only v2 (`PreserveLabels`) sees shares.
#[derive(Debug, Clone, Copy)]
pub(crate) enum TagPolicy {
    Legacy,
    PreserveLabels,
}

impl TagPolicy {
    fn surface(self) -> crate::resource_access::Surface {
        match self {
            Self::Legacy => crate::resource_access::Surface::V1,
            Self::PreserveLabels => crate::resource_access::Surface::V2,
        }
    }
}

async fn project_grant(
    state: &AppState,
    actor_id: i64,
    project_id: i64,
    tag_policy: TagPolicy,
    need: crate::resource_access::Need,
) -> Result<crate::resource_access::Grant, ProjectError> {
    use crate::resource_access::Denied;
    crate::resource_access::require(
        &state.pool,
        actor_id,
        crate::resource_access::ResourceKind::Project,
        project_id,
        tag_policy.surface(),
        need,
    )
    .await
    .map_err(|denied| match denied {
        Denied::NotFound => ProjectError::NotFound,
        Denied::Forbidden => ProjectError::Forbidden,
        Denied::Database => ProjectError::Database,
    })
}

impl ProjectError {
    fn into_v1_response(self) -> Response {
        match self {
            // API v1 never grants read-only access; fail closed as absent.
            Self::NotFound | Self::Forbidden => not_found(),
            Self::Database => database_error(),
            Self::Invalid(detail) => invalid_project(detail),
            Self::Conflict(project) => conflict(*project),
            Self::ImageImportUnavailable => image_import_not_implemented(),
        }
    }
}

#[derive(Debug, FromRow)]
struct ProjectRow {
    id: i64,
    name: String,
    category: Option<String>,
    needles: Option<String>,
    stitch_sample: Option<String>,
    description: Option<String>,
    notes: Option<String>,
    other_materials: Option<String>,
    tags: String,
    link: Option<String>,
    link_archive: Option<String>,
    is_ai_enhanced: bool,
    created_at: String,
    updated_at: String,
}

#[derive(Debug, FromRow)]
struct ProjectListRow {
    id: i64,
    owner_id: i64,
    name: String,
    category: Option<String>,
    tags: String,
    is_favorite: bool,
    updated_at: String,
    share_role: Option<String>,
}

#[derive(Debug, FromRow)]
struct StepRow {
    id: i64,
    title: String,
    description: Option<String>,
    step_number: i64,
}

#[derive(Debug, Serialize)]
struct ErrorResponse {
    detail: Value,
}

async fn list_projects(
    State(state): State<AppState>,
    headers: HeaderMap,
    query: Result<Query<ListQuery>, axum::extract::rejection::QueryRejection>,
) -> Response {
    let user_id = match bearer_user(&state.pool, &headers).await {
        Ok(user_id) => user_id,
        Err(error) => return error.into_response(),
    };
    let Query(query) = match query {
        Ok(query) => query,
        Err(_) => return error_response(StatusCode::UNPROCESSABLE_ENTITY, json!("Invalid query")),
    };
    match list_for_owner(&state, user_id, query).await {
        Ok(page) => Json(page).into_response(),
        Err(ProjectError::Invalid(detail)) => {
            error_response(StatusCode::UNPROCESSABLE_ENTITY, json!(detail))
        }
        Err(ProjectError::Database) => database_error(),
        Err(_) => database_error(),
    }
}

pub(crate) async fn list_for_owner(
    state: &AppState,
    user_id: i64,
    query: ListQuery,
) -> Result<ProjectPage, ProjectError> {
    list_for_owner_with_tag_policy(state, user_id, query, TagPolicy::Legacy).await
}

pub(crate) async fn list_for_owner_with_tag_policy(
    state: &AppState,
    user_id: i64,
    query: ListQuery,
    tag_policy: TagPolicy,
) -> Result<ProjectPage, ProjectError> {
    let page = query.page.unwrap_or(1);
    if page < 1 {
        return Err(ProjectError::Invalid("Invalid page"));
    }
    let category = query.category.filter(|value| !value.is_empty());
    let tag = query.tag.filter(|value| !value.is_empty());
    if query.q.as_ref().is_some_and(|value| value.len() > 256) {
        return Err(ProjectError::Invalid("Search query is too long"));
    }
    let search = query
        .q
        .map(|value| value.trim().to_owned())
        .filter(|value| !value.is_empty());
    let search_pattern = search.as_deref().map(literal_like_pattern);
    let favorite = query.favorite.map(i64::from);
    let requested_scope = match query.scope.as_deref() {
        None => None,
        Some(value) => Some(
            crate::resource_access::Scope::parse(value)
                .ok_or(ProjectError::Invalid("Invalid scope"))?,
        ),
    };
    let scope = crate::resource_access::effective_scope(&state.pool, user_id, requested_scope)
        .await
        .map_err(|_| ProjectError::Database)?;
    let owner_filter = match scope {
        crate::resource_access::Scope::Mine => Some(user_id),
        crate::resource_access::Scope::All => query.owner_id,
    };
    let v2 = matches!(tag_policy, TagPolicy::PreserveLabels);
    // API v2 lists what is shared with the caller next to their own projects.
    let share_user = (v2 && scope == crate::resource_access::Scope::Mine).then_some(user_id);
    let rows = sqlx::query_as::<_, ProjectListRow>(
        "SELECT p.id, p.owner_id, p.name, c.name AS category, p.tags, \
                EXISTS(SELECT 1 FROM user_favorites f \
                       WHERE f.user_id = ? AND f.project_id = p.id) AS is_favorite, \
                p.updated_at, \
                (SELECT s.role FROM resource_shares s WHERE s.kind = 'project' \
                 AND s.resource_id = p.id AND s.user_id = ?) AS share_role \
         FROM projects p LEFT JOIN categories c ON c.id = p.category_id \
         WHERE ((? IS NULL OR p.owner_id = ?) OR (? IS NOT NULL AND EXISTS( \
                SELECT 1 FROM resource_shares s WHERE s.kind = 'project' \
                AND s.resource_id = p.id AND s.user_id = ?))) \
           AND (? IS NULL OR c.name = ?) \
           AND (? IS NULL OR instr(lower(p.tags), lower(?)) > 0) \
           AND (? IS NULL OR p.name LIKE ? ESCAPE '!' \
                OR COALESCE(c.name, '') LIKE ? ESCAPE '!' \
                OR p.tags LIKE ? ESCAPE '!' \
                OR COALESCE(p.notes, '') LIKE ? ESCAPE '!') \
           AND (? IS NULL OR EXISTS(SELECT 1 FROM user_favorites f \
                                    WHERE f.user_id = ? AND f.project_id = p.id) = ?) \
         ORDER BY p.updated_at DESC, p.id DESC LIMIT ? OFFSET ?",
    )
    .bind(user_id)
    .bind(user_id)
    .bind(owner_filter)
    .bind(owner_filter)
    .bind(share_user)
    .bind(share_user)
    .bind(category.as_deref())
    .bind(category.as_deref())
    .bind(tag.as_deref())
    .bind(tag.as_deref())
    .bind(search_pattern.as_deref())
    .bind(search_pattern.as_deref())
    .bind(search_pattern.as_deref())
    .bind(search_pattern.as_deref())
    .bind(search_pattern.as_deref())
    .bind(favorite)
    .bind(user_id)
    .bind(favorite)
    .bind(PAGE_SIZE + 1)
    .bind((page - 1).saturating_mul(PAGE_SIZE))
    .fetch_all(&state.pool)
    .await;
    let rows = rows.map_err(|_| ProjectError::Database)?;
    let has_more = rows.len() > PAGE_SIZE as usize;
    let mut items = Vec::new();
    let mut owners = std::collections::HashMap::new();
    let admin = v2
        && crate::resource_access::is_active_admin(&state.pool, user_id)
            .await
            .map_err(|_| ProjectError::Database)?;
    for row in rows.into_iter().take(PAGE_SIZE as usize) {
        let access = v2.then(|| {
            crate::resource_access::list_access(
                user_id,
                row.owner_id,
                admin,
                row.share_role.as_deref(),
                false,
            )
        });
        let (images, _) = match crate::media::project_media(&state.pool, row.owner_id, row.id).await
        {
            Ok(media) => media,
            Err(_) => return Err(ProjectError::Database),
        };
        if let std::collections::hash_map::Entry::Vacant(entry) = owners.entry(row.owner_id) {
            entry.insert(
                crate::resource_access::owner_info(
                    &state.pool,
                    user_id,
                    row.owner_id,
                    state.gravatar.enabled,
                )
                .await
                .map_err(|_| ProjectError::Database)?,
            );
        }
        items.push(ProjectListItem {
            owner: owners.get(&row.owner_id).cloned().flatten(),
            id: row.id,
            name: row.name,
            category: row.category,
            tags: decode_tags(&row.tags, tag_policy),
            is_favorite: row.is_favorite,
            updated_at: row.updated_at,
            preview_url: crate::media::project_preview(&images),
            access,
        });
    }

    Ok(ProjectPage {
        items,
        page,
        per_page: PAGE_SIZE,
        has_more,
        scope,
    })
}

fn literal_like_pattern(value: &str) -> String {
    format!(
        "%{}%",
        value
            .replace('!', "!!")
            .replace('%', "!%")
            .replace('_', "!_")
    )
}

async fn get_project(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(project_id): Path<i64>,
) -> Response {
    let user_id = match bearer_user(&state.pool, &headers).await {
        Ok(user_id) => user_id,
        Err(error) => return error.into_response(),
    };
    match load_project(&state, user_id, project_id).await {
        Ok(Some(project)) => Json(project).into_response(),
        Ok(None) => not_found(),
        Err(_) => database_error(),
    }
}

async fn create_project(
    State(state): State<AppState>,
    headers: HeaderMap,
    Json(payload): Json<ProjectWriteRequest>,
) -> Response {
    let user_id = match bearer_user(&state.pool, &headers).await {
        Ok(user_id) => user_id,
        Err(error) => return error.into_response(),
    };
    match create_for_owner(&state, user_id, payload).await {
        Ok(project) => (StatusCode::CREATED, Json(project)).into_response(),
        Err(error) => error.into_v1_response(),
    }
}

pub(crate) async fn create_for_owner(
    state: &AppState,
    user_id: i64,
    payload: ProjectWriteRequest,
) -> Result<ProjectResponse, ProjectError> {
    create_for_owner_with_tag_policy(state, user_id, payload, TagPolicy::Legacy).await
}

pub(crate) const MAX_NEW_YARNS: usize = 8;

pub(crate) async fn create_for_owner_with_tag_policy(
    state: &AppState,
    user_id: i64,
    mut payload: ProjectWriteRequest,
    tag_policy: TagPolicy,
) -> Result<ProjectResponse, ProjectError> {
    // Yarns first, so the project insert links them like any other owned yarn. When the project
    // cannot be saved afterwards they are removed again, so a failed import leaves no strays.
    let new_yarns = std::mem::take(&mut payload.new_yarns);
    if new_yarns.len() > MAX_NEW_YARNS {
        return Err(ProjectError::Invalid("Too many new yarns"));
    }
    if new_yarns.iter().any(|yarn| yarn.name.trim().is_empty()) {
        return Err(ProjectError::Invalid("A new yarn needs a name"));
    }
    let mut created_yarn_ids = Vec::new();
    for yarn in new_yarns {
        match crate::yarns::create_for_owner(state, user_id, yarn).await {
            Ok(created) => {
                created_yarn_ids.push(created.id);
                payload.yarn_ids.push(created.id);
            }
            Err(_) => {
                remove_created_yarns(state, user_id, &created_yarn_ids).await;
                return Err(ProjectError::Database);
            }
        }
    }
    let result = create_project_with_yarn_ids(state, user_id, payload, tag_policy).await;
    if result.is_err() {
        remove_created_yarns(state, user_id, &created_yarn_ids).await;
    }
    result
}

async fn remove_created_yarns(state: &AppState, user_id: i64, ids: &[i64]) {
    for id in ids {
        let _ = crate::yarns::delete_for_owner(state, user_id, *id).await;
    }
}

async fn create_project_with_yarn_ids(
    state: &AppState,
    user_id: i64,
    payload: ProjectWriteRequest,
    tag_policy: TagPolicy,
) -> Result<ProjectResponse, ProjectError> {
    if payload_has_image_urls(&payload) && (!state.external_imports || state.media_root.is_none()) {
        return Err(if state.external_imports {
            ProjectError::Database
        } else {
            ProjectError::ImageImportUnavailable
        });
    }
    let step_image_urls = payload
        .steps
        .iter()
        .map(|step| step.image_urls.clone())
        .collect::<Vec<_>>();
    let (prepared_images, prepared_attachments) =
        if state.external_imports && state.media_root.is_some() {
            tokio::join!(
                crate::image_import::prepare_project_images(
                    state,
                    payload.name.trim(),
                    &payload.image_urls,
                    &step_image_urls,
                ),
                crate::image_import::prepare_project_attachments(state, &payload.attachment_urls),
            )
        } else {
            (Vec::new(), Vec::new())
        };
    let name = payload.name.trim().to_owned();
    let mut transaction = crate::db::begin_write(&state.pool)
        .await
        .map_err(|_| ProjectError::Database)?;
    let yarn_ids = owned_yarn_ids(&mut transaction, user_id, 0, &payload.yarn_ids)
        .await
        .map_err(|_| ProjectError::Database)?;
    let audit_yarn_names = yarn_names_for_audit(&mut transaction, user_id, &yarn_ids)
        .await
        .map_err(|_| ProjectError::Database)?;
    let category_id = ensure_category(&mut transaction, user_id, payload.category.as_deref())
        .await
        .map_err(|_| ProjectError::Database)?;
    let after_category = category_name_for_audit(&mut transaction, category_id)
        .await
        .map_err(|_| ProjectError::Database)?;
    let audit_changes = crate::audit::changes_from_items(project_audit_changes(
        None,
        &payload,
        &name,
        after_category.as_deref(),
        Vec::new(),
        audit_yarn_names,
        tag_policy,
    ));
    let now = timestamp(Utc::now());
    let tags = encode_tags(&payload.tags, tag_policy);
    let inserted = sqlx::query(
        "INSERT INTO projects (owner_id, category_id, name, needles, stitch_sample, \
         description, notes, other_materials, tags, link, is_ai_enhanced, created_at, updated_at) \
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
    )
    .bind(user_id)
    .bind(category_id)
    .bind(&name)
    .bind(cleaned_optional(payload.needles))
    .bind(cleaned_optional(payload.stitch_sample))
    .bind(cleaned_optional(payload.description))
    .bind(cleaned_optional(payload.notes))
    .bind(cleaned_optional(payload.other_materials))
    .bind(tags)
    .bind(cleaned_optional(payload.link))
    .bind(payload.is_ai_enhanced)
    .bind(&now)
    .bind(&now)
    .execute(&mut *transaction)
    .await;
    let project_id = inserted
        .map_err(|_| ProjectError::Database)?
        .last_insert_rowid();
    if insert_project_yarns(&mut transaction, project_id, &yarn_ids)
        .await
        .is_err()
        || bump_yarn_timestamps(&mut transaction, &yarn_ids)
            .await
            .is_err()
    {
        return Err(ProjectError::Database);
    }
    let step_ids = insert_steps(&mut transaction, project_id, &payload.steps)
        .await
        .map_err(|_| ProjectError::Database)?;
    if crate::audit::record_with_data(
        &mut transaction,
        user_id,
        AuditEntity::Project,
        project_id,
        AuditAction::Created,
        Some(&name),
        audit_changes.as_ref(),
    )
    .await
    .is_err()
    {
        return Err(ProjectError::Database);
    }
    if state.archive_enabled
        && crate::archive_jobs::enqueue_in_transaction(
            &mut transaction,
            user_id,
            crate::archive_jobs::ArchiveEntityKind::Project,
            project_id,
        )
        .await
        .is_err()
    {
        return Err(ProjectError::Database);
    }
    if transaction.commit().await.is_err() {
        return Err(ProjectError::Database);
    }
    if !prepared_images.is_empty() {
        crate::image_import::persist_project_images(
            state,
            user_id,
            project_id,
            &step_ids,
            prepared_images,
        )
        .await;
    }
    if !prepared_attachments.is_empty() {
        crate::image_import::persist_project_attachments(
            state,
            user_id,
            project_id,
            prepared_attachments,
        )
        .await;
    }

    load_project_with_tag_policy(state, user_id, project_id, tag_policy)
        .await
        .map_err(|_| ProjectError::Database)?
        .ok_or(ProjectError::Database)
}

async fn update_project(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(project_id): Path<i64>,
    Json(payload): Json<ProjectWriteRequest>,
) -> Response {
    let user_id = match bearer_user(&state.pool, &headers).await {
        Ok(user_id) => user_id,
        Err(error) => return error.into_response(),
    };
    match update_for_owner(&state, user_id, project_id, payload).await {
        Ok(project) => Json(project).into_response(),
        Err(error) => error.into_v1_response(),
    }
}

pub(crate) async fn update_for_owner(
    state: &AppState,
    user_id: i64,
    project_id: i64,
    payload: ProjectWriteRequest,
) -> Result<ProjectResponse, ProjectError> {
    update_for_owner_with_tag_policy(state, user_id, project_id, payload, TagPolicy::Legacy).await
}

pub(crate) async fn update_for_owner_with_tag_policy(
    state: &AppState,
    user_id: i64,
    project_id: i64,
    payload: ProjectWriteRequest,
    tag_policy: TagPolicy,
) -> Result<ProjectResponse, ProjectError> {
    let actor_id = user_id;
    let grant = project_grant(
        state,
        actor_id,
        project_id,
        tag_policy,
        crate::resource_access::Need::Edit,
    )
    .await?;
    let user_id = grant.owner_id;
    let name = payload.name.trim().to_owned();
    let expected_updated_at = match payload.expected_updated_at.as_deref() {
        Some(value) => Some(
            parse_timestamp(value).ok_or(ProjectError::Invalid("Invalid expected_updated_at"))?,
        ),
        None => None,
    };
    let current = load_project_with_tag_policy(state, actor_id, project_id, tag_policy)
        .await
        .map_err(|_| ProjectError::Database)?
        .ok_or(ProjectError::NotFound)?;
    if expected_updated_at.is_some_and(|expected| {
        parse_timestamp(&current.updated_at).is_some_and(|actual| actual != expected)
    }) {
        return Err(ProjectError::Conflict(Box::new(current)));
    }

    let current_step_ids = project_step_ids(state, project_id)
        .await
        .map_err(|_| ProjectError::Database)?;
    let mut transaction = crate::db::begin_write(&state.pool)
        .await
        .map_err(|_| ProjectError::Database)?;
    let old_yarn_ids = linked_yarn_ids(&mut transaction, project_id)
        .await
        .map_err(|_| ProjectError::Database)?;
    let old_audit_yarn_names = yarn_names_for_audit(&mut transaction, user_id, &old_yarn_ids)
        .await
        .map_err(|_| ProjectError::Database)?;
    let yarn_ids = owned_yarn_ids(&mut transaction, user_id, project_id, &payload.yarn_ids)
        .await
        .map_err(|_| ProjectError::Database)?;
    // An editor links only the owner's yarns (or keeps existing links); asking
    // for anything else is an error rather than a silent no-op.
    if grant.access == crate::resource_access::Access::Editor {
        let requested: HashSet<i64> = payload
            .yarn_ids
            .iter()
            .copied()
            .filter(|id| *id > 0)
            .collect();
        if requested.len() != yarn_ids.len() {
            return Err(ProjectError::Invalid(
                "Yarn is not available for this project",
            ));
        }
    }
    let new_audit_yarn_names = yarn_names_for_audit(&mut transaction, user_id, &yarn_ids)
        .await
        .map_err(|_| ProjectError::Database)?;
    let category_id = ensure_category(&mut transaction, user_id, payload.category.as_deref())
        .await
        .map_err(|_| ProjectError::Database)?;
    let after_category = category_name_for_audit(&mut transaction, category_id)
        .await
        .map_err(|_| ProjectError::Database)?;
    let audit_items = project_audit_changes(
        Some(&current),
        &payload,
        &name,
        after_category.as_deref(),
        old_audit_yarn_names,
        new_audit_yarn_names,
        tag_policy,
    );
    let audit_changes = crate::audit::changes_from_items(audit_items.clone());
    let updated_at = next_timestamp(&current.updated_at).ok_or(ProjectError::Database)?;
    let tags = encode_tags(&payload.tags, tag_policy);
    let updated = sqlx::query(
        "UPDATE projects SET category_id = ?, name = ?, needles = ?, stitch_sample = ?, \
         description = ?, notes = ?, other_materials = ?, tags = ?, link = ?, \
         is_ai_enhanced = ?, updated_at = ? \
         WHERE id = ? AND owner_id = ? AND updated_at = ?",
    )
    .bind(category_id)
    .bind(&name)
    .bind(cleaned_optional(payload.needles))
    .bind(cleaned_optional(payload.stitch_sample))
    .bind(cleaned_optional(payload.description))
    .bind(cleaned_optional(payload.notes))
    .bind(cleaned_optional(payload.other_materials))
    .bind(tags)
    .bind(cleaned_optional(payload.link))
    .bind(payload.is_ai_enhanced)
    .bind(updated_at)
    .bind(project_id)
    .bind(user_id)
    .bind(&current.updated_at)
    .execute(&mut *transaction)
    .await;
    match updated {
        Ok(result) if result.rows_affected() == 1 => {}
        Ok(_) => {
            let _ = transaction.rollback().await;
            return match load_project_with_tag_policy(state, actor_id, project_id, tag_policy).await
            {
                Ok(Some(project)) => Err(ProjectError::Conflict(Box::new(project))),
                Ok(None) => Err(ProjectError::NotFound),
                Err(_) => Err(ProjectError::Database),
            };
        }
        Err(_) => return Err(ProjectError::Database),
    }
    let changed_yarn_ids = changed_ids(&old_yarn_ids, &yarn_ids);
    // Recipients of the project see a yarn exactly while it is linked: unlinked
    // yarns get a tombstone for them, newly linked ones appear in their delta.
    let link_watch = if changed_yarn_ids.is_empty() {
        None
    } else {
        let candidates = crate::sharing::project_candidates(
            &mut transaction,
            project_id,
            user_id,
            &changed_yarn_ids,
        )
        .await
        .map_err(|_| ProjectError::Database)?;
        Some(
            crate::sharing::watch(&mut transaction, candidates)
                .await
                .map_err(|_| ProjectError::Database)?,
        )
    };
    if replace_project_yarns(&mut transaction, project_id, &yarn_ids)
        .await
        .is_err()
        || bump_yarn_timestamps(&mut transaction, &changed_yarn_ids)
            .await
            .is_err()
        || replace_steps(
            &mut transaction,
            project_id,
            &current_step_ids,
            &payload.steps,
        )
        .await
        .is_err()
    {
        return Err(ProjectError::Database);
    }
    if let Some(link_watch) = link_watch
        && crate::sharing::settle(&mut transaction, link_watch, false)
            .await
            .is_err()
    {
        return Err(ProjectError::Database);
    }
    // A save that changes nothing is not an event worth recording.
    if !audit_items.is_empty()
        && crate::audit::record_with_data(
            &mut transaction,
            actor_id,
            AuditEntity::Project,
            project_id,
            AuditAction::Updated,
            Some(&name),
            audit_changes.as_ref(),
        )
        .await
        .is_err()
    {
        return Err(ProjectError::Database);
    }
    if state.archive_enabled
        && crate::archive_jobs::enqueue_in_transaction(
            &mut transaction,
            user_id,
            crate::archive_jobs::ArchiveEntityKind::Project,
            project_id,
        )
        .await
        .is_err()
    {
        return Err(ProjectError::Database);
    }
    if transaction.commit().await.is_err() {
        return Err(ProjectError::Database);
    }
    load_project_with_tag_policy(state, actor_id, project_id, tag_policy)
        .await
        .map_err(|_| ProjectError::Database)?
        .ok_or(ProjectError::NotFound)
}

async fn delete_project(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(project_id): Path<i64>,
) -> Response {
    let user_id = match bearer_user(&state.pool, &headers).await {
        Ok(user_id) => user_id,
        Err(error) => return error.into_response(),
    };
    match delete_for_owner(&state, user_id, project_id).await {
        Ok(()) => StatusCode::NO_CONTENT.into_response(),
        Err(error) => error.into_v1_response(),
    }
}

pub(crate) async fn delete_for_owner(
    state: &AppState,
    user_id: i64,
    project_id: i64,
) -> Result<(), ProjectError> {
    let actor_id = user_id;
    // Deleting is owner/administrator only, also for editors of a share.
    let user_id = project_grant(
        state,
        actor_id,
        project_id,
        TagPolicy::PreserveLabels,
        crate::resource_access::Need::Manage,
    )
    .await?
    .owner_id;
    let mut transaction = crate::db::begin_write(&state.pool)
        .await
        .map_err(|_| ProjectError::Database)?;
    let exists =
        sqlx::query_scalar::<_, i64>("SELECT id FROM projects WHERE id = ? AND owner_id = ?")
            .bind(project_id)
            .bind(user_id)
            .fetch_optional(&mut *transaction)
            .await;
    match exists {
        Ok(Some(_)) => {}
        Ok(None) => return Err(ProjectError::NotFound),
        Err(_) => return Err(ProjectError::Database),
    }
    let linked_yarn_ids = match linked_yarn_ids(&mut transaction, project_id).await {
        Ok(ids) => ids,
        Err(_) => return Err(ProjectError::Database),
    };
    if bump_yarn_timestamps(&mut transaction, &linked_yarn_ids)
        .await
        .is_err()
    {
        return Err(ProjectError::Database);
    }
    let delete_watch = match crate::sharing::project_candidates(
        &mut transaction,
        project_id,
        user_id,
        &[],
    )
    .await
    {
        Ok(candidates) => crate::sharing::watch(&mut transaction, candidates)
            .await
            .map_err(|_| ProjectError::Database)?,
        Err(_) => return Err(ProjectError::Database),
    };
    let changed_at = timestamp(Utc::now());
    if sqlx::query(
        "INSERT INTO deletion_events (owner_id, entity_type, entity_id, changed_at) \
         VALUES (?, 'project', ?, ?)",
    )
    .bind(user_id)
    .bind(project_id)
    .bind(changed_at)
    .execute(&mut *transaction)
    .await
    .is_err()
    {
        return Err(ProjectError::Database);
    }
    let old_name =
        sqlx::query_scalar::<_, String>("SELECT name FROM projects WHERE id = ? AND owner_id = ?")
            .bind(project_id)
            .bind(user_id)
            .fetch_optional(&mut *transaction)
            .await
            .map_err(|_| ProjectError::Database)?
            .ok_or(ProjectError::NotFound)?;
    match sqlx::query("DELETE FROM projects WHERE id = ? AND owner_id = ?")
        .bind(project_id)
        .bind(user_id)
        .execute(&mut *transaction)
        .await
    {
        Ok(result) if result.rows_affected() == 1 => {}
        Ok(_) => return Err(ProjectError::NotFound),
        Err(_) => return Err(ProjectError::Database),
    }
    // Recipients and viewers of derived yarns learn of the removal.
    if crate::sharing::settle(&mut transaction, delete_watch, false)
        .await
        .is_err()
    {
        return Err(ProjectError::Database);
    }
    if crate::audit::record_with_data(
        &mut transaction,
        actor_id,
        AuditEntity::Project,
        project_id,
        AuditAction::Deleted,
        Some(&old_name),
        None,
    )
    .await
    .is_err()
    {
        return Err(ProjectError::Database);
    }
    transaction
        .commit()
        .await
        .map_err(|_| ProjectError::Database)
}

async fn favorite_project(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(project_id): Path<i64>,
) -> Response {
    set_favorite(state, headers, project_id, true).await
}

async fn unfavorite_project(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(project_id): Path<i64>,
) -> Response {
    set_favorite(state, headers, project_id, false).await
}

async fn set_favorite(
    state: AppState,
    headers: HeaderMap,
    project_id: i64,
    favorite: bool,
) -> Response {
    let user_id = match bearer_user(&state.pool, &headers).await {
        Ok(user_id) => user_id,
        Err(error) => return error.into_response(),
    };
    match set_favorite_for_owner(&state, user_id, project_id, favorite).await {
        Ok(project) => Json(project).into_response(),
        Err(error) => error.into_v1_response(),
    }
}

pub(crate) async fn set_favorite_for_owner(
    state: &AppState,
    user_id: i64,
    project_id: i64,
    favorite: bool,
) -> Result<ProjectResponse, ProjectError> {
    set_favorite_for_owner_with_tag_policy(state, user_id, project_id, favorite, TagPolicy::Legacy)
        .await
}

pub(crate) async fn set_favorite_for_owner_with_tag_policy(
    state: &AppState,
    user_id: i64,
    project_id: i64,
    favorite: bool,
    tag_policy: TagPolicy,
) -> Result<ProjectResponse, ProjectError> {
    // Favourites are personal: any reader may set one.
    project_grant(
        state,
        user_id,
        project_id,
        tag_policy,
        crate::resource_access::Need::Read,
    )
    .await?;
    let result = if favorite {
        sqlx::query(
            "INSERT INTO user_favorites (user_id, project_id) VALUES (?, ?) \
             ON CONFLICT DO NOTHING",
        )
        .bind(user_id)
        .bind(project_id)
        .execute(&state.pool)
        .await
    } else {
        sqlx::query("DELETE FROM user_favorites WHERE user_id = ? AND project_id = ?")
            .bind(user_id)
            .bind(project_id)
            .execute(&state.pool)
            .await
    };
    if result.is_err() {
        return Err(ProjectError::Database);
    }
    load_project_with_tag_policy(state, user_id, project_id, tag_policy)
        .await
        .map_err(|_| ProjectError::Database)?
        .ok_or(ProjectError::NotFound)
}

pub(crate) async fn load_project(
    state: &AppState,
    user_id: i64,
    project_id: i64,
) -> Result<Option<ProjectResponse>, sqlx::Error> {
    load_project_with_tag_policy(state, user_id, project_id, TagPolicy::Legacy).await
}

pub(crate) async fn load_project_with_tag_policy(
    state: &AppState,
    user_id: i64,
    project_id: i64,
    tag_policy: TagPolicy,
) -> Result<Option<ProjectResponse>, sqlx::Error> {
    let actor_id = user_id;
    let Some(grant) = crate::resource_access::grant(
        &state.pool,
        actor_id,
        crate::resource_access::ResourceKind::Project,
        project_id,
        tag_policy.surface(),
    )
    .await?
    else {
        return Ok(None);
    };
    let user_id = grant.owner_id;
    let row = sqlx::query_as::<_, ProjectRow>(
        "SELECT p.id, p.name, c.name AS category, p.needles, p.stitch_sample, \
                p.description, p.notes, p.other_materials, p.tags, p.link, \
                p.link_archive, p.is_ai_enhanced, p.created_at, p.updated_at \
         FROM projects p LEFT JOIN categories c ON c.id = p.category_id \
         WHERE p.id = ? AND p.owner_id = ?",
    )
    .bind(project_id)
    .bind(user_id)
    .fetch_optional(&state.pool)
    .await?;
    let Some(row) = row else {
        return Ok(None);
    };
    let yarns = sqlx::query_as::<_, (i64, String)>(
        "SELECT y.id, y.name FROM project_yarns py \
         JOIN yarns y ON y.id = py.yarn_id \
         WHERE py.project_id = ? ORDER BY py.rowid",
    )
    .bind(project_id)
    .fetch_all(&state.pool)
    .await?;
    let steps = sqlx::query_as::<_, StepRow>(
        "SELECT id, title, description, step_number FROM project_steps \
         WHERE project_id = ? ORDER BY step_number, id",
    )
    .bind(project_id)
    .fetch_all(&state.pool)
    .await?;
    let is_favorite = sqlx::query_scalar::<_, bool>(
        "SELECT EXISTS(SELECT 1 FROM user_favorites WHERE user_id = ? AND project_id = ?)",
    )
    .bind(actor_id)
    .bind(project_id)
    .fetch_one(&state.pool)
    .await?;
    let owner =
        crate::resource_access::owner_info(&state.pool, actor_id, user_id, state.gravatar.enabled)
            .await?;

    let (images, attachments) =
        crate::media::project_media(&state.pool, user_id, project_id).await?;
    let yarn = yarns.first().map(|(_, name)| name.clone());
    Ok(Some(ProjectResponse {
        id: row.id,
        name: row.name,
        category: row.category,
        yarn,
        needles: row.needles,
        stitch_sample: row.stitch_sample,
        description: row.description,
        notes: row.notes,
        other_materials: row.other_materials,
        tags: decode_tags(&row.tags, tag_policy),
        link: row.link,
        link_archive: row.link_archive,
        is_ai_enhanced: row.is_ai_enhanced,
        is_favorite,
        created_at: row.created_at,
        updated_at: row.updated_at,
        yarn_ids: yarns.into_iter().map(|(id, _)| id).collect(),
        steps: steps
            .into_iter()
            .map(|step| StepResponse {
                id: step.id,
                title: step.title,
                description: step.description,
                step_number: step.step_number,
            })
            .collect(),
        images,
        attachments,
        owner,
        access: matches!(tag_policy, TagPolicy::PreserveLabels).then(|| grant.into()),
    }))
}

/// Load an owner's full project DTO for project delta sync.
pub(crate) async fn sync_value(
    state: &AppState,
    user_id: i64,
    id: i64,
) -> Result<Option<Value>, sqlx::Error> {
    load_project(state, user_id, id)
        .await?
        .map(|project| {
            serde_json::to_value(project).map_err(|error| sqlx::Error::Decode(Box::new(error)))
        })
        .transpose()
}

async fn project_step_ids(state: &AppState, project_id: i64) -> Result<HashSet<i64>, sqlx::Error> {
    sqlx::query_scalar::<_, i64>("SELECT id FROM project_steps WHERE project_id = ?")
        .bind(project_id)
        .fetch_all(&state.pool)
        .await
        .map(|ids| ids.into_iter().collect())
}

async fn yarn_names_for_audit(
    tx: &mut Transaction<'_, Sqlite>,
    _owner_id: i64,
    yarn_ids: &[i64],
) -> Result<Vec<String>, sqlx::Error> {
    let mut names = Vec::with_capacity(yarn_ids.len());
    for id in yarn_ids {
        if let Some(name) = sqlx::query_scalar::<_, String>("SELECT name FROM yarns WHERE id = ?")
            .bind(id)
            .fetch_optional(&mut **tx)
            .await?
        {
            names.push(name);
        }
    }
    Ok(names)
}

async fn category_name_for_audit(
    tx: &mut Transaction<'_, Sqlite>,
    category_id: Option<i64>,
) -> Result<Option<String>, sqlx::Error> {
    match category_id {
        Some(id) => {
            sqlx::query_scalar::<_, String>("SELECT name FROM categories WHERE id = ?")
                .bind(id)
                .fetch_optional(&mut **tx)
                .await
        }
        None => Ok(None),
    }
}

fn project_audit_changes(
    before: Option<&ProjectResponse>,
    payload: &ProjectWriteRequest,
    name: &str,
    after_category: Option<&str>,
    before_yarn_names: Vec<String>,
    after_yarn_names: Vec<String>,
    tag_policy: TagPolicy,
) -> Vec<ChangeItemV1> {
    let mut items = Vec::new();
    let mut push_text = |field, old: Option<&str>, new: Option<&str>| {
        if let Some(item) = crate::audit::text_change(field, old, new) {
            items.push(item);
        }
    };
    push_text(
        ChangeField::ProjectName,
        before.map(|project| project.name.as_str()),
        Some(name),
    );
    push_text(
        ChangeField::ProjectCategory,
        before.and_then(|project| project.category.as_deref()),
        after_category,
    );
    push_text(
        ChangeField::ProjectNeedles,
        before.and_then(|project| project.needles.as_deref()),
        payload
            .needles
            .as_deref()
            .map(str::trim)
            .filter(|value| !value.is_empty()),
    );
    push_text(
        ChangeField::ProjectStitchSample,
        before.and_then(|project| project.stitch_sample.as_deref()),
        payload
            .stitch_sample
            .as_deref()
            .map(str::trim)
            .filter(|value| !value.is_empty()),
    );
    push_text(
        ChangeField::ProjectDescription,
        before.and_then(|project| project.description.as_deref()),
        payload
            .description
            .as_deref()
            .map(str::trim)
            .filter(|value| !value.is_empty()),
    );
    push_text(
        ChangeField::ProjectNotes,
        before.and_then(|project| project.notes.as_deref()),
        payload
            .notes
            .as_deref()
            .map(str::trim)
            .filter(|value| !value.is_empty()),
    );
    push_text(
        ChangeField::ProjectOtherMaterials,
        before.and_then(|project| project.other_materials.as_deref()),
        payload
            .other_materials
            .as_deref()
            .map(str::trim)
            .filter(|value| !value.is_empty()),
    );
    drop(push_text);
    let before_tags = before.map_or_else(Vec::new, |project| project.tags.clone());
    let after_tags = decode_tags(&encode_tags(&payload.tags, tag_policy), tag_policy);
    if let Some(item) = crate::audit::change_item(
        ChangeField::ProjectTags,
        ChangeValue::TextList(before_tags),
        ChangeValue::TextList(after_tags),
    ) {
        items.push(item);
    }
    let before_yarns = before_yarn_names;
    if let Some(item) = crate::audit::change_item(
        ChangeField::ProjectYarns,
        ChangeValue::Yarns(before_yarns.clone()),
        ChangeValue::Yarns(after_yarn_names.clone()),
    ) {
        items.push(item);
    }
    let before_steps = before.map_or_else(Vec::new, |project| {
        project
            .steps
            .iter()
            .map(|step| StepSnapshot {
                number: step.step_number,
                title: step.title.clone(),
                description: step.description.clone(),
            })
            .collect()
    });
    let after_steps = payload
        .steps
        .iter()
        .enumerate()
        .map(|(index, step)| StepSnapshot {
            number: if before.is_some() && step.step_number == 0 {
                index as i64 + 1
            } else {
                step.step_number
            },
            title: step.title.trim().to_owned(),
            description: step
                .description
                .as_deref()
                .map(|value| value.trim().to_owned()),
        })
        .collect::<Vec<_>>();
    if let Some(item) = crate::audit::change_item(
        ChangeField::ProjectSteps,
        ChangeValue::Steps(before_steps),
        ChangeValue::Steps(after_steps),
    ) {
        items.push(item);
    }
    let before_step_count = before.map(|project| project.steps.len() as i64);
    if let Some(item) = crate::audit::change_item(
        ChangeField::ProjectStepCount,
        ChangeValue::Integer(before_step_count),
        ChangeValue::Integer(Some(payload.steps.len() as i64)),
    ) {
        items.push(item);
    }
    let old_ai = before.is_some_and(|project| project.is_ai_enhanced);
    if let Some(item) = crate::audit::change_item(
        ChangeField::ProjectIsAiEnhanced,
        ChangeValue::Boolean(old_ai),
        ChangeValue::Boolean(payload.is_ai_enhanced),
    ) {
        items.push(item);
    }
    let before_link = before.and_then(|project| project.link.as_deref());
    let after_link = cleaned_optional(payload.link.clone());
    if before_link != after_link.as_deref() {
        items.push(ChangeItemV1 {
            field: ChangeField::ProjectLink,
            before: ChangeValue::LinkChanged(before_link.is_some()),
            after: ChangeValue::LinkChanged(after_link.is_some()),
        });
    }
    items
}

async fn ensure_category(
    transaction: &mut Transaction<'_, Sqlite>,
    user_id: i64,
    name: Option<&str>,
) -> Result<Option<i64>, sqlx::Error> {
    let Some(name) = name.map(str::trim).filter(|name| !name.is_empty()) else {
        return Ok(None);
    };
    if let Some(id) = sqlx::query_scalar::<_, i64>(
        "SELECT id FROM categories WHERE user_id = ? AND name = ? COLLATE NOCASE",
    )
    .bind(user_id)
    .bind(name)
    .fetch_optional(&mut **transaction)
    .await?
    {
        return Ok(Some(id));
    }
    sqlx::query("INSERT INTO categories (user_id, name) VALUES (?, ?) ON CONFLICT DO NOTHING")
        .bind(user_id)
        .bind(name)
        .execute(&mut **transaction)
        .await?;
    sqlx::query_scalar::<_, i64>(
        "SELECT id FROM categories WHERE user_id = ? AND name = ? COLLATE NOCASE",
    )
    .bind(user_id)
    .bind(name)
    .fetch_optional(&mut **transaction)
    .await
}

/// Yarns a project may link: the project owner's own, plus any yarn the
/// project already links (after a transfer without `move_linked_yarns` the old
/// owner's yarns stay linked, and keeping them in a save must keep working).
/// Nobody links a yarn of their own stash into someone else's project.
async fn owned_yarn_ids(
    transaction: &mut Transaction<'_, Sqlite>,
    user_id: i64,
    project_id: i64,
    requested_ids: &[i64],
) -> Result<Vec<i64>, sqlx::Error> {
    let mut found_ids = Vec::new();
    let mut seen = HashSet::new();
    for yarn_id in requested_ids {
        if *yarn_id <= 0 || !seen.insert(*yarn_id) {
            continue;
        }
        let found_id = sqlx::query_scalar::<_, i64>(
            "SELECT y.id FROM yarns y WHERE y.id = ?1 AND (y.owner_id = ?2 OR EXISTS( \
             SELECT 1 FROM project_yarns py WHERE py.yarn_id = y.id AND py.project_id = ?3))",
        )
        .bind(yarn_id)
        .bind(user_id)
        .bind(project_id)
        .fetch_optional(&mut **transaction)
        .await?;
        if let Some(found_id) = found_id {
            found_ids.push(found_id);
        }
    }
    Ok(found_ids)
}

async fn linked_yarn_ids(
    transaction: &mut Transaction<'_, Sqlite>,
    project_id: i64,
) -> Result<Vec<i64>, sqlx::Error> {
    sqlx::query_scalar::<_, i64>("SELECT yarn_id FROM project_yarns WHERE project_id = ?")
        .bind(project_id)
        .fetch_all(&mut **transaction)
        .await
}

fn changed_ids(old_ids: &[i64], new_ids: &[i64]) -> Vec<i64> {
    let old_id_set: HashSet<i64> = old_ids.iter().copied().collect();
    let new_id_set: HashSet<i64> = new_ids.iter().copied().collect();
    old_ids
        .iter()
        .filter(|id| !new_id_set.contains(*id))
        .chain(new_ids.iter().filter(|id| !old_id_set.contains(*id)))
        .copied()
        .collect()
}

async fn bump_yarn_timestamps(
    transaction: &mut Transaction<'_, Sqlite>,
    yarn_ids: &[i64],
) -> Result<(), sqlx::Error> {
    for yarn_id in yarn_ids {
        let current = sqlx::query_scalar::<_, String>("SELECT updated_at FROM yarns WHERE id = ?")
            .bind(yarn_id)
            .fetch_optional(&mut **transaction)
            .await?
            .ok_or(sqlx::Error::RowNotFound)?;
        let updated_at = next_timestamp(&current).ok_or_else(|| {
            sqlx::Error::Decode(Box::new(std::io::Error::other(
                "invalid yarn update timestamp",
            )))
        })?;
        let result = sqlx::query("UPDATE yarns SET updated_at = ? WHERE id = ?")
            .bind(updated_at)
            .bind(yarn_id)
            .execute(&mut **transaction)
            .await?;
        if result.rows_affected() != 1 {
            return Err(sqlx::Error::RowNotFound);
        }
    }
    Ok(())
}

async fn insert_project_yarns(
    transaction: &mut Transaction<'_, Sqlite>,
    project_id: i64,
    yarn_ids: &[i64],
) -> Result<(), sqlx::Error> {
    for yarn_id in yarn_ids {
        sqlx::query("INSERT INTO project_yarns (project_id, yarn_id) VALUES (?, ?)")
            .bind(project_id)
            .bind(yarn_id)
            .execute(&mut **transaction)
            .await?;
    }
    Ok(())
}

async fn replace_project_yarns(
    transaction: &mut Transaction<'_, Sqlite>,
    project_id: i64,
    yarn_ids: &[i64],
) -> Result<(), sqlx::Error> {
    sqlx::query("DELETE FROM project_yarns WHERE project_id = ?")
        .bind(project_id)
        .execute(&mut **transaction)
        .await?;
    insert_project_yarns(transaction, project_id, yarn_ids).await
}

async fn insert_steps(
    transaction: &mut Transaction<'_, Sqlite>,
    project_id: i64,
    steps: &[StepWriteRequest],
) -> Result<Vec<i64>, sqlx::Error> {
    let mut step_ids = Vec::with_capacity(steps.len());
    for step in steps {
        let inserted = sqlx::query(
            "INSERT INTO project_steps (project_id, title, description, step_number) \
             VALUES (?, ?, ?, ?)",
        )
        .bind(project_id)
        .bind(step.title.trim())
        .bind(stripped_optional(step.description.clone()))
        .bind(step.step_number)
        .execute(&mut **transaction)
        .await?;
        step_ids.push(inserted.last_insert_rowid());
    }
    Ok(step_ids)
}

async fn replace_steps(
    transaction: &mut Transaction<'_, Sqlite>,
    project_id: i64,
    existing_ids: &HashSet<i64>,
    steps: &[StepWriteRequest],
) -> Result<(), sqlx::Error> {
    let retained_ids: HashSet<i64> = steps
        .iter()
        .filter_map(|step| step.id.filter(|id| existing_ids.contains(id)))
        .collect();
    for removed_id in existing_ids.difference(&retained_ids) {
        sqlx::query("DELETE FROM project_steps WHERE id = ? AND project_id = ?")
            .bind(removed_id)
            .bind(project_id)
            .execute(&mut **transaction)
            .await?;
    }
    for (position, step) in steps.iter().enumerate() {
        let step_number = if step.step_number == 0 {
            position as i64 + 1
        } else {
            step.step_number
        };
        if let Some(id) = step.id.filter(|id| existing_ids.contains(id)) {
            sqlx::query(
                "UPDATE project_steps SET title = ?, description = ?, step_number = ? \
                 WHERE id = ? AND project_id = ?",
            )
            .bind(step.title.trim())
            .bind(stripped_optional(step.description.clone()))
            .bind(step_number)
            .bind(id)
            .bind(project_id)
            .execute(&mut **transaction)
            .await?;
        } else {
            sqlx::query(
                "INSERT INTO project_steps (project_id, title, description, step_number) \
                 VALUES (?, ?, ?, ?)",
            )
            .bind(project_id)
            .bind(step.title.trim())
            .bind(stripped_optional(step.description.clone()))
            .bind(step_number)
            .execute(&mut **transaction)
            .await?;
        }
    }
    Ok(())
}

fn payload_has_image_urls(payload: &ProjectWriteRequest) -> bool {
    !payload.image_urls.is_empty()
        || !payload.attachment_urls.is_empty()
        || payload.steps.iter().any(|step| !step.image_urls.is_empty())
}

fn cleaned_optional(value: Option<String>) -> Option<String> {
    value.and_then(|value| {
        let value = value.trim();
        (!value.is_empty()).then(|| value.to_owned())
    })
}

fn stripped_optional(value: Option<String>) -> Option<String> {
    value.map(|value| value.trim().to_owned())
}

fn encode_tags(tags: &[String], tag_policy: TagPolicy) -> String {
    let normalized: Vec<String> = match tag_policy {
        TagPolicy::PreserveLabels => tags.to_vec(),
        TagPolicy::Legacy => {
            let mut seen = HashSet::new();
            tags.iter()
                .flat_map(|tag| {
                    tag.split(|character: char| {
                        character == ',' || character == '#' || character.is_whitespace()
                    })
                })
                .map(str::trim)
                .filter(|tag| !tag.is_empty())
                .filter(|tag| seen.insert(tag.to_lowercase()))
                .map(str::to_owned)
                .collect()
        }
    };
    serde_json::to_string(&normalized).unwrap_or_else(|_| "[]".to_owned())
}

fn decode_tags(tags: &str, tag_policy: TagPolicy) -> Vec<String> {
    match serde_json::from_str::<Vec<String>>(tags) {
        Ok(tags) if matches!(tag_policy, TagPolicy::PreserveLabels) => tags,
        Ok(tags) => tags
            .into_iter()
            .map(|tag| tag.trim().to_owned())
            .filter(|tag| !tag.is_empty())
            .collect(),
        Err(_) => tags
            .split(',')
            .map(|tag| tag.trim().to_owned())
            .filter(|tag| !tag.is_empty())
            .collect(),
    }
}

fn parse_timestamp(value: &str) -> Option<DateTime<Utc>> {
    crate::time::parse_utc(value)
}

fn timestamp(value: DateTime<Utc>) -> String {
    value.to_rfc3339_opts(SecondsFormat::Micros, true)
}

fn next_timestamp(current: &str) -> Option<String> {
    let current = parse_timestamp(current)?;
    let next_microsecond = current.checked_add_signed(Duration::microseconds(1))?;
    Some(timestamp(Utc::now().max(next_microsecond)))
}

fn conflict(project: ProjectResponse) -> Response {
    error_response(StatusCode::CONFLICT, json!(project))
}

fn invalid_project(detail: &'static str) -> Response {
    error_response(StatusCode::UNPROCESSABLE_ENTITY, json!(detail))
}

fn image_import_not_implemented() -> Response {
    error_response(
        StatusCode::NOT_IMPLEMENTED,
        json!("Project image URL imports are not implemented yet"),
    )
}

fn not_found() -> Response {
    error_response(StatusCode::NOT_FOUND, json!("Project not found"))
}

fn database_error() -> Response {
    error_response(StatusCode::INTERNAL_SERVER_ERROR, json!("database error"))
}

fn error_response(status: StatusCode, detail: Value) -> Response {
    (status, Json(ErrorResponse { detail })).into_response()
}
