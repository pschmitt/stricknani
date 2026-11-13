//! Safe, administrator-scoped reads of audit events for browser adapters.

use std::collections::{BTreeMap, HashSet};

use serde::{Deserialize, Serialize};
use sqlx::{Row, SqlitePool};
use url::form_urlencoded;

use crate::admin_service::{AdminError, require_active_admin};
use crate::audit::{ChangeField, ChangeItemV1, ChangeSetV1, ChangeValue, Entity};

const PAGE_SIZE: i64 = 50;
const MAX_PAGE: u32 = 10_000;
const MAX_QUERY_BYTES: usize = 512;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum AuditEntity {
    All,
    Project,
    Yarn,
    Category,
    /// Server-level events (a backup restore); administrators only.
    Server,
}

impl AuditEntity {
    fn parse(value: Option<&str>) -> Option<Self> {
        match value.unwrap_or("all") {
            "all" => Some(Self::All),
            "project" => Some(Self::Project),
            "yarn" => Some(Self::Yarn),
            "category" => Some(Self::Category),
            "server" => Some(Self::Server),
            _ => None,
        }
    }

    const fn sql_value(self) -> Option<&'static str> {
        match self {
            Self::All => None,
            Self::Project => Some("project"),
            Self::Yarn => Some("yarn"),
            Self::Category => Some("category"),
            Self::Server => Some("server"),
        }
    }
}

/// Which part a filtered user played in an event.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum AuditUserRole {
    /// The user, or an administrator acting as the user, did it.
    Any,
    /// The user is `actor_user_id`.
    Actor,
    /// The user is `subject_user_id`: the other account of a share, role
    /// change, transfer or impersonation.
    Subject,
}

impl AuditUserRole {
    fn parse(value: &str) -> Option<Self> {
        match value {
            "any" => Some(Self::Any),
            "actor" => Some(Self::Actor),
            "subject" => Some(Self::Subject),
            _ => None,
        }
    }
}

#[derive(Clone, Debug)]
pub(crate) struct AuditFilter {
    page: Option<u32>,
    entity: AuditEntity,
    action: Option<String>,
    user: Option<i64>,
    role: AuditUserRole,
}

/// The account an audit list is filtered to, echoed so clients can label it.
#[derive(Clone, Debug, Serialize)]
pub(crate) struct AuditFilterUser {
    pub(crate) id: i64,
    pub(crate) email: String,
    /// The administrator avatar path; null without a picture.
    pub(crate) avatar_url: Option<String>,
}

/// `user` and `role` as bound parameters: the user id, then a flag and the
/// user id again for each of the actor, subject and impersonator columns.
macro_rules! user_filter_sql {
    () => {
        "(? IS NULL \
          OR (? AND e.actor_user_id = ?) \
          OR (? AND e.subject_user_id = ?) \
          OR (? AND e.impersonated_by_user_id = ?))"
    };
}

impl AuditFilter {
    pub(crate) fn parse(raw: Option<&str>) -> Result<Self, AuditError> {
        let Some(raw) = raw else {
            return Ok(Self {
                page: None,
                entity: AuditEntity::All,
                action: None,
                user: None,
                role: AuditUserRole::Any,
            });
        };
        if raw.len() > MAX_QUERY_BYTES {
            return Err(AuditError::InvalidQuery);
        }
        let mut page = None;
        let mut entity = None;
        let mut action = None;
        let mut user = None;
        let mut role = None;
        let mut fields = 0;
        for (key, value) in form_urlencoded::parse(raw.as_bytes()) {
            fields += 1;
            if fields > 8 {
                return Err(AuditError::InvalidQuery);
            }
            match key.as_ref() {
                "page" if page.is_none() => {
                    page = Some(value.parse().map_err(|_| AuditError::InvalidQuery)?);
                }
                "entity" if entity.is_none() => entity = Some(value.into_owned()),
                "action" if action.is_none() => action = Some(value.into_owned()),
                "user" if user.is_none() => user = Some(value.into_owned()),
                "role" if role.is_none() => role = Some(value.into_owned()),
                _ => return Err(AuditError::InvalidQuery),
            }
        }
        let entity = AuditEntity::parse(entity.as_deref()).ok_or(AuditError::InvalidQuery)?;
        let action = action
            .map(|action| {
                if action.len() > 64 || action.chars().any(char::is_control) {
                    return Err(AuditError::InvalidQuery);
                }
                Ok(action.trim().to_owned())
            })
            .transpose()?
            .filter(|action| !action.is_empty());
        if page.is_some_and(|page| page == 0 || page > MAX_PAGE) {
            return Err(AuditError::InvalidQuery);
        }
        let user = user
            .map(|user| {
                user.parse::<i64>()
                    .ok()
                    .filter(|id| *id > 0)
                    .ok_or(AuditError::InvalidUser)
            })
            .transpose()?;
        let role = match role {
            Some(role) => AuditUserRole::parse(&role).ok_or(AuditError::InvalidUser)?,
            None => AuditUserRole::Any,
        };
        Ok(Self {
            page,
            entity,
            action,
            user,
            role,
        })
    }

    pub(crate) fn action(&self) -> Option<&str> {
        self.action.as_deref()
    }
}

#[derive(Debug)]
pub(crate) enum AuditError {
    Database,
    Forbidden,
    InvalidQuery,
    /// `user` is not a positive id, or `role` is not `actor`, `subject` or `any`.
    InvalidUser,
    /// `user` names no account.
    UserNotFound,
}

#[derive(Clone, Debug, Serialize)]
pub(crate) struct AuditResource {
    pub(crate) kind: Option<Entity>,
    pub(crate) id: i64,
    pub(crate) name: Option<String>,
    pub(crate) name_source: &'static str,
    pub(crate) href: Option<String>,
    pub(crate) preview_url: Option<String>,
    /// False while the resource is in the trash and once it is gone.
    pub(crate) exists: bool,
    /// `active`, `in_trash` or `gone` (purged, or hard-deleted before the trash existed).
    pub(crate) state: &'static str,
    /// Present while the resource is in the trash.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub(crate) trash: Option<AuditTrash>,
}

/// Where a trashed resource stands: when it goes, and whether this viewer may restore it.
#[derive(Clone, Debug, Serialize)]
pub(crate) struct AuditTrash {
    pub(crate) deleted_at: String,
    pub(crate) purge_at: String,
    pub(crate) days_remaining: i64,
    /// Only the owner and administrators may restore; the Trash page lists the item.
    pub(crate) can_restore: bool,
}

/// URLs of a current picture that a change label refers to.
#[derive(Clone, Debug, Serialize)]
pub(crate) struct AuditChangeMedia {
    pub(crate) url: String,
    pub(crate) thumbnail_url: String,
}

/// The media file an upload or removal event was about.
#[derive(Clone, Debug, Serialize)]
pub(crate) struct AuditMedia {
    /// `image`, `photo` or `attachment`.
    pub(crate) kind: String,
    pub(crate) id: i64,
    pub(crate) name: Option<String>,
    /// False once the file has been deleted; `url` is then null.
    pub(crate) exists: bool,
    pub(crate) is_image: bool,
    pub(crate) content_type: Option<String>,
    pub(crate) size_bytes: Option<i64>,
    pub(crate) url: Option<String>,
    pub(crate) thumbnail_url: Option<String>,
}

#[derive(Clone, Debug, Serialize)]
pub(crate) struct AuditEvent {
    pub(crate) id: i64,
    pub(crate) actor_email: Option<String>,
    /// Display name of the actor; the only identity readers without
    /// `can_manage` get (their `actor_email` is blank).
    #[serde(skip_serializing_if = "Option::is_none")]
    pub(crate) actor_name: Option<String>,
    pub(crate) actor_avatar_url: Option<String>,
    pub(crate) legacy_actor_user_id: Option<i64>,
    /// The reader is the actor. Resource history and activity reads only.
    #[serde(skip_serializing_if = "std::ops::Not::not")]
    pub(crate) actor_is_self: bool,
    pub(crate) entity_type: String,
    pub(crate) entity_id: i64,
    pub(crate) action: String,
    pub(crate) created_at: String,
    pub(crate) resource: AuditResource,
    pub(crate) has_changes: bool,
    pub(crate) media: Option<AuditMedia>,
    /// Thumbnails of the pictures this event's change values name, keyed by
    /// the exact label string used in the values (and the event media name).
    /// Resolved at read time against the resource's current media; omitted
    /// when nothing resolves. Set on resource history responses (normal media URLs) and administrator audit responses (read-only administrator media URLs).
    #[serde(skip_serializing_if = "Option::is_none")]
    pub(crate) media_thumbnails: Option<BTreeMap<String, AuditChangeMedia>>,
    /// The other account of a share, role change, revoke, leave or transfer.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub(crate) subject: Option<AuditSubject>,
    /// The administrator who made this change while acting as the actor.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub(crate) impersonated_by: Option<AuditImpersonator>,
    /// No account caused this event: the automatic purge of the trash, or a
    /// change the server made on its own (a restore from a backup).
    #[serde(skip_serializing_if = "std::ops::Not::not")]
    pub(crate) system: bool,
    /// The client that caused the event. Administrator audit reads only: the
    /// per-resource history never sets it, so it is absent from those responses.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub(crate) client: Option<AuditClient>,
    /// What a server event (`entity_type` `server`) was about. Administrator audit reads only.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub(crate) server: Option<AuditServerEvent>,
}

/// A backup restore: the backup, the safety copy taken first, the media option.
#[derive(Clone, Debug, Serialize, serde::Deserialize)]
pub(crate) struct AuditServerEvent {
    pub(crate) backup: String,
    pub(crate) pre_restore: String,
    pub(crate) media: String,
    /// The requesting administrator, kept even when that account is not in the restored data.
    pub(crate) actor_email: Option<String>,
}

/// Client of the request behind an event. `kind` is `web`, `android`, `api`,
/// `system` (no request) or `unknown` (written before clients were recorded).
#[derive(Clone, Debug, Serialize)]
pub(crate) struct AuditClient {
    pub(crate) kind: String,
    pub(crate) version: Option<String>,
    pub(crate) label: Option<String>,
    pub(crate) user_agent: Option<String>,
}

fn audit_client(row: &sqlx::sqlite::SqliteRow) -> AuditClient {
    let kind = row
        .get::<Option<String>, _>("client_kind")
        .filter(|kind| crate::client_info::ClientKind::parse(kind).is_some())
        .unwrap_or_else(|| "unknown".to_owned());
    AuditClient {
        kind,
        version: row.get("client_version"),
        label: row.get("client_label"),
        user_agent: row.get("client_user_agent"),
    }
}

/// An administrator who acted as another account. The email is only given to
/// administrators.
#[derive(Clone, Debug, Serialize)]
pub(crate) struct AuditImpersonator {
    pub(crate) id: Option<i64>,
    pub(crate) name: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub(crate) email: Option<String>,
    /// The avatar path any signed-in user may read; null without a picture.
    pub(crate) avatar_url: Option<String>,
}

/// The other account a sharing event is about. Never carries an email.
#[derive(Clone, Debug, Serialize)]
pub(crate) struct AuditSubject {
    pub(crate) id: Option<i64>,
    pub(crate) name: Option<String>,
    pub(crate) role: Option<String>,
    /// The avatar path any signed-in user may read; null without a picture.
    pub(crate) avatar_url: Option<String>,
}

#[derive(Clone, Debug, Serialize)]
pub(crate) struct AuditDetail {
    pub(crate) event: AuditEvent,
    pub(crate) changes: Option<ChangeSetV1>,
    pub(crate) unavailable_reason: Option<&'static str>,
}

pub(crate) struct AuditPage {
    pub(crate) events: Vec<AuditEvent>,
    pub(crate) user: Option<AuditFilterUser>,
    pub(crate) page: u32,
    pub(crate) per_page: u32,
    pub(crate) has_more: bool,
}

#[derive(Clone, Copy)]
enum AuditAudience {
    Administrator,
    Owner {
        viewer_id: i64,
        /// Only owners and administrators see other accounts' emails.
        reveal_email: bool,
    },
}

macro_rules! media_event_columns {
    () => {
        ", e.media_kind AS media_kind, e.media_public_id AS media_public_id, \
        e.media_name AS media_name, mf.filename AS media_current_filename, \
        mf.content_type AS media_content_type, mf.is_safe_inline AS media_safe_inline, \
        mf.size_bytes AS media_size_bytes "
    };
}

macro_rules! media_event_join {
    () => {
        " LEFT JOIN media_files AS mf ON mf.id = (SELECT m.id FROM media_files AS m \
            WHERE e.media_public_id IS NOT NULL AND m.public_id = e.media_public_id \
              AND CASE m.kind WHEN 'title' THEN 'image' WHEN 'step' THEN 'image' ELSE m.kind END = e.media_kind \
              AND ((e.entity_type = 'project' AND m.project_id = e.entity_id) \
                OR (e.entity_type = 'yarn' AND m.yarn_id = e.entity_id)) LIMIT 1) "
    };
}

macro_rules! resource_event_columns {
    () => {
        concat!("SELECT e.id, e.actor_user_id, e.legacy_actor_user_id, \
        u.email AS actor_email, avatar.filename AS actor_avatar_filename, u.use_gravatar AS actor_use_gravatar, \
        COALESCE(u.is_admin, 0) AS actor_is_admin, \
        (e.actor_user_id IS NOT NULL AND EXISTS (SELECT 1 FROM resource_shares AS actor_share \
           WHERE actor_share.kind = e.entity_type AND actor_share.resource_id = e.entity_id \
             AND actor_share.user_id = e.actor_user_id)) AS actor_has_share, \
        e.entity_type, e.entity_id, e.action, e.created_at, \
        e.resource_name_snapshot, e.change_version, \
        e.subject_user_id, e.subject_role, \
        (SELECT email FROM users WHERE id = e.subject_user_id) AS subject_email, \
        (SELECT use_gravatar FROM users WHERE id = e.subject_user_id) AS subject_use_gravatar, \
        (SELECT filename FROM user_avatars WHERE user_id = e.subject_user_id) AS subject_avatar_filename, \
        e.impersonated_by_user_id, \
        e.client_kind, e.client_version, e.client_label, e.client_user_agent, \
        e.server_details, \
        (SELECT email FROM users WHERE id = e.impersonated_by_user_id) AS impersonator_email, \
        (SELECT use_gravatar FROM users WHERE id = e.impersonated_by_user_id) AS impersonator_use_gravatar, \
        (SELECT filename FROM user_avatars WHERE user_id = e.impersonated_by_user_id) AS impersonator_avatar_filename, \
        CASE WHEN length(e.safe_changes) <= 262144 THEN e.safe_changes END AS safe_changes, \
        CASE WHEN length(e.details) <= 262144 THEN e.details END AS details, \
        COALESCE(length(e.safe_changes) > 262144, 0) AS safe_changes_too_large, \
        COALESCE(length(e.details) > 262144, 0) AS legacy_details_too_large, \
        (e.safe_changes IS NOT NULL) AS safe_changes_present, \
        (e.details IS NOT NULL) AS legacy_details_present, \
        CASE e.entity_type WHEN 'project' THEN (SELECT name FROM projects WHERE id=e.entity_id) \
          WHEN 'yarn' THEN (SELECT name FROM yarns WHERE id=e.entity_id) \
          WHEN 'category' THEN COALESCE((SELECT name FROM categories WHERE id=e.entity_id), \
            (SELECT name FROM trashed_categories WHERE id=e.entity_id)) END AS current_name, \
        CASE e.entity_type WHEN 'project' THEN (SELECT deleted_at FROM projects WHERE id=e.entity_id) \
          WHEN 'yarn' THEN (SELECT deleted_at FROM yarns WHERE id=e.entity_id) \
          WHEN 'category' THEN (SELECT deleted_at FROM trashed_categories WHERE id=e.entity_id) \
          END AS current_deleted_at, \
        CASE e.entity_type WHEN 'project' THEN (SELECT owner_id FROM projects WHERE id=e.entity_id) \
          WHEN 'yarn' THEN (SELECT owner_id FROM yarns WHERE id=e.entity_id) \
          WHEN 'category' THEN COALESCE((SELECT user_id FROM categories WHERE id=e.entity_id), \
            (SELECT user_id FROM trashed_categories WHERE id=e.entity_id)) \
          END AS current_owner_id, \
        CASE e.entity_type \
          WHEN 'project' THEN (SELECT m.public_id FROM media_files AS m \
            JOIN projects AS p ON p.id = m.project_id AND p.owner_id = m.owner_id \
            WHERE p.id = e.entity_id AND m.kind IN ('title', 'step') \
            ORDER BY m.is_project_cover DESC, m.position, m.id LIMIT 1) \
          WHEN 'yarn' THEN (SELECT m.public_id FROM media_files AS m \
            JOIN yarns AS y ON y.id = m.yarn_id AND y.owner_id = m.owner_id \
            WHERE y.id = e.entity_id AND m.kind = 'photo' AND m.is_primary = 1 \
            ORDER BY m.position, m.id LIMIT 1) \
        END AS current_preview_media_id, \
        CASE e.entity_type \
          WHEN 'project' THEN (SELECT m.filename FROM media_files AS m \
            JOIN projects AS p ON p.id = m.project_id AND p.owner_id = m.owner_id \
            WHERE p.id = e.entity_id AND m.kind IN ('title', 'step') \
            ORDER BY m.is_project_cover DESC, m.position, m.id LIMIT 1) \
          WHEN 'yarn' THEN (SELECT m.filename FROM media_files AS m \
            JOIN yarns AS y ON y.id = m.yarn_id AND y.owner_id = m.owner_id \
            WHERE y.id = e.entity_id AND m.kind = 'photo' AND m.is_primary = 1 \
            ORDER BY m.position, m.id LIMIT 1) \
        END AS current_preview_filename",
        media_event_columns!())
    };
}

const RESOURCE_EVENT_SELECT: &str = concat!(
    resource_event_columns!(),
    "FROM audit_events AS e \
 LEFT JOIN users AS u ON u.id = e.actor_user_id \
 LEFT JOIN user_avatars AS avatar ON avatar.user_id = e.actor_user_id ",
    media_event_join!(),
    " WHERE e.entity_type = ? AND e.entity_id = ?"
);

pub(crate) async fn list_events(
    pool: &SqlitePool,
    actor_id: i64,
    filter: &AuditFilter,
    gravatar_enabled: bool,
) -> Result<AuditPage, AuditError> {
    require_active_admin(pool, actor_id)
        .await
        .map_err(|error| match error {
            AdminError::Forbidden => AuditError::Forbidden,
            _ => AuditError::Database,
        })?;
    let entity = filter.entity.sql_value();
    let action = filter.action();
    let user = match filter.user {
        Some(id) => {
            let row: Option<(String, i64, Option<String>)> = sqlx::query_as(
                "SELECT u.email, u.use_gravatar, \
                 (SELECT filename FROM user_avatars WHERE user_id = u.id) \
                 FROM users AS u WHERE u.id = ?",
            )
            .bind(id)
            .fetch_optional(pool)
            .await
            .map_err(|_| AuditError::Database)?;
            let (email, use_gravatar, filename) = row.ok_or(AuditError::UserNotFound)?;
            Some(AuditFilterUser {
                id,
                email,
                avatar_url: crate::media::admin_avatar(
                    id,
                    filename.as_deref(),
                    use_gravatar != 0,
                    gravatar_enabled,
                )
                .map(|(url, _)| url),
            })
        }
        None => None,
    };
    let user_id = user.as_ref().map(|user| user.id);
    let as_actor = matches!(filter.role, AuditUserRole::Any | AuditUserRole::Actor);
    let as_subject = matches!(filter.role, AuditUserRole::Any | AuditUserRole::Subject);
    let as_impersonator = filter.role == AuditUserRole::Any;
    let total: i64 = sqlx::query_scalar(concat!(
        "SELECT count(*) FROM audit_events AS e \
         WHERE (? IS NULL OR e.entity_type = ?) AND (? IS NULL OR e.action = ?) AND ",
        user_filter_sql!()
    ))
    .bind(entity)
    .bind(entity)
    .bind(action)
    .bind(action)
    .bind(user_id)
    .bind(as_actor)
    .bind(user_id)
    .bind(as_subject)
    .bind(user_id)
    .bind(as_impersonator)
    .bind(user_id)
    .fetch_one(pool)
    .await
    .map_err(|_| AuditError::Database)?;
    let page_count = total
        .saturating_add(PAGE_SIZE - 1)
        .checked_div(PAGE_SIZE)
        .unwrap_or(1)
        .max(1)
        .min(i64::from(MAX_PAGE)) as u32;
    let page = filter.page.unwrap_or(1).min(page_count);
    let offset = i64::from(page - 1) * PAGE_SIZE;
    let rows = sqlx::query(concat!(
        resource_event_columns!(),
        "FROM audit_events AS e \
         LEFT JOIN users AS u ON u.id = e.actor_user_id \
         LEFT JOIN user_avatars AS avatar ON avatar.user_id = e.actor_user_id ",
        media_event_join!(),
        " WHERE (? IS NULL OR e.entity_type = ?) AND (? IS NULL OR e.action = ?) AND ",
        user_filter_sql!(),
        " ORDER BY e.created_at DESC, e.id DESC LIMIT ? OFFSET ?"
    ))
    .bind(entity)
    .bind(entity)
    .bind(action)
    .bind(action)
    .bind(user_id)
    .bind(as_actor)
    .bind(user_id)
    .bind(as_subject)
    .bind(user_id)
    .bind(as_impersonator)
    .bind(user_id)
    .bind(PAGE_SIZE)
    .bind(offset)
    .fetch_all(pool)
    .await
    .map_err(|_| AuditError::Database)?;
    let mut events: Vec<AuditEvent> = rows
        .iter()
        .map(|row| make_event(row, AuditAudience::Administrator, gravatar_enabled))
        .collect();
    let wanted: Vec<Vec<String>> = rows.iter().map(media_thumbnails_labels).collect();
    attach_admin_media_thumbnails(pool, &mut events, &wanted).await?;
    Ok(AuditPage {
        events,
        user,
        page,
        per_page: PAGE_SIZE as u32,
        has_more: page < page_count,
    })
}

pub(crate) async fn get_event_detail(
    pool: &SqlitePool,
    actor_id: i64,
    event_id: i64,
    gravatar_enabled: bool,
) -> Result<Option<AuditDetail>, AuditError> {
    require_active_admin(pool, actor_id)
        .await
        .map_err(|error| match error {
            AdminError::Forbidden => AuditError::Forbidden,
            _ => AuditError::Database,
        })?;
    let row = sqlx::query(concat!(
        resource_event_columns!(),
        "FROM audit_events AS e \
         LEFT JOIN users AS u ON u.id = e.actor_user_id \
         LEFT JOIN user_avatars AS avatar ON avatar.user_id = e.actor_user_id ",
        media_event_join!(),
        " WHERE e.id = ?"
    ))
    .bind(event_id)
    .fetch_optional(pool)
    .await
    .map_err(|_| AuditError::Database)?;
    let Some(row) = row else {
        return Ok(None);
    };
    let mut events = vec![make_event(
        &row,
        AuditAudience::Administrator,
        gravatar_enabled,
    )];
    attach_admin_media_thumbnails(pool, &mut events, &[media_thumbnails_labels(&row)]).await?;
    let event = events.remove(0);
    let (changes, unavailable_reason) = project_changes(&row);
    Ok(Some(AuditDetail {
        event,
        changes,
        unavailable_reason,
    }))
}

/// Who reads a resource's history.
#[derive(Clone, Copy)]
pub(crate) struct ResourceViewer {
    pub(crate) id: i64,
    pub(crate) reveal_email: bool,
}

pub(crate) async fn list_resource_events(
    pool: &SqlitePool,
    owner_id: i64,
    viewer: ResourceViewer,
    entity: AuditEntity,
    resource_id: i64,
    page: u32,
    gravatar_enabled: bool,
) -> Result<Option<AuditPage>, AuditError> {
    if page == 0 || page > MAX_PAGE || !matches!(entity, AuditEntity::Project | AuditEntity::Yarn) {
        return Err(AuditError::InvalidQuery);
    }
    let mut transaction = crate::db::begin_write(pool)
        .await
        .map_err(|_| AuditError::Database)?;
    if !resource_belongs_to(&mut transaction, owner_id, entity, resource_id).await? {
        return Ok(None);
    }
    let entity_type = entity.sql_value().expect("resource entity has a SQL name");
    let (table, owner_column) = match entity {
        AuditEntity::Project => ("projects", "owner_id"),
        AuditEntity::Yarn => ("yarns", "owner_id"),
        _ => unreachable!("resource entity checked above"),
    };
    let count_sql = format!(
        "SELECT count(*) FROM audit_events AS e \
         WHERE e.entity_type = ? AND e.entity_id = ? \
           AND EXISTS (SELECT 1 FROM {table} AS resource \
                       WHERE resource.id = e.entity_id AND resource.{owner_column} = ?)"
    );
    let total: i64 = sqlx::query_scalar(sqlx::AssertSqlSafe(count_sql.as_str()))
        .bind(entity_type)
        .bind(resource_id)
        .bind(owner_id)
        .fetch_one(&mut *transaction)
        .await
        .map_err(|_| AuditError::Database)?;
    let page_size = 20_i64;
    let page_count = total
        .saturating_add(page_size - 1)
        .checked_div(page_size)
        .unwrap_or(1)
        .max(1)
        .min(i64::from(MAX_PAGE)) as u32;
    let offset = i64::from(page - 1) * page_size;
    let list_sql = format!(
        "{RESOURCE_EVENT_SELECT} \
           AND EXISTS (SELECT 1 FROM {table} AS resource \
                       WHERE resource.id = e.entity_id AND resource.{owner_column} = ?) \
         ORDER BY e.created_at DESC, e.id DESC LIMIT ? OFFSET ?"
    );
    let rows = sqlx::query(sqlx::AssertSqlSafe(list_sql.as_str()))
        .bind(entity_type)
        .bind(resource_id)
        .bind(owner_id)
        .bind(page_size)
        .bind(offset)
        .fetch_all(&mut *transaction)
        .await
        .map_err(|_| AuditError::Database)?;
    let wanted: Vec<Vec<String>> = rows.iter().map(media_thumbnails_labels).collect();
    let current = if wanted.iter().any(|labels| !labels.is_empty()) {
        current_media_labels(&mut transaction, entity, resource_id, owner_id).await?
    } else {
        Vec::new()
    };
    let events = rows
        .iter()
        .zip(&wanted)
        .map(|(row, labels)| {
            let mut event = make_event(
                row,
                AuditAudience::Owner {
                    viewer_id: viewer.id,
                    reveal_email: viewer.reveal_email,
                },
                gravatar_enabled,
            );
            event.media_thumbnails = resolve_media_thumbnails(labels, &current);
            event
        })
        .collect();
    transaction
        .commit()
        .await
        .map_err(|_| AuditError::Database)?;
    Ok(Some(AuditPage {
        events,
        user: None,
        page,
        per_page: page_size as u32,
        has_more: page < page_count,
    }))
}

pub(crate) async fn get_resource_event_detail(
    pool: &SqlitePool,
    owner_id: i64,
    viewer: ResourceViewer,
    entity: AuditEntity,
    resource_id: i64,
    event_id: i64,
    gravatar_enabled: bool,
) -> Result<Option<AuditDetail>, AuditError> {
    if !matches!(entity, AuditEntity::Project | AuditEntity::Yarn) {
        return Err(AuditError::InvalidQuery);
    }
    let mut transaction = crate::db::begin_write(pool)
        .await
        .map_err(|_| AuditError::Database)?;
    if !resource_belongs_to(&mut transaction, owner_id, entity, resource_id).await? {
        return Ok(None);
    }
    let entity_type = entity.sql_value().expect("resource entity has a SQL name");
    let (table, owner_column) = match entity {
        AuditEntity::Project => ("projects", "owner_id"),
        AuditEntity::Yarn => ("yarns", "owner_id"),
        _ => unreachable!("resource entity checked above"),
    };
    let query = format!(
        "{RESOURCE_EVENT_SELECT} AND e.id = ? \
           AND EXISTS (SELECT 1 FROM {table} AS resource \
                       WHERE resource.id = e.entity_id AND resource.{owner_column} = ?)"
    );
    let row = sqlx::query(sqlx::AssertSqlSafe(query.as_str()))
        .bind(entity_type)
        .bind(resource_id)
        .bind(event_id)
        .bind(owner_id)
        .fetch_optional(&mut *transaction)
        .await
        .map_err(|_| AuditError::Database)?;
    let Some(row) = row else {
        return Ok(None);
    };
    let mut event = make_event(
        &row,
        AuditAudience::Owner {
            viewer_id: viewer.id,
            reveal_email: viewer.reveal_email,
        },
        gravatar_enabled,
    );
    let wanted = media_thumbnails_labels(&row);
    if !wanted.is_empty() {
        let current = current_media_labels(&mut transaction, entity, resource_id, owner_id).await?;
        event.media_thumbnails = resolve_media_thumbnails(&wanted, &current);
    }
    let (changes, unavailable_reason) = project_changes(&row);
    transaction
        .commit()
        .await
        .map_err(|_| AuditError::Database)?;
    Ok(Some(AuditDetail {
        event,
        changes,
        unavailable_reason,
    }))
}

/// Window within which repeated identical events collapse into one feed item.
const ACTIVITY_COALESCE_SECONDS: i64 = 10 * 60;
const ACTIVITY_BATCH: i64 = 100;
pub(crate) const ACTIVITY_DEFAULT_LIMIT: u32 = 10;
pub(crate) const ACTIVITY_MAX_LIMIT: u32 = 50;

/// Who caused a feed item, as the reader may know it.
#[derive(Clone, Debug, Serialize)]
pub(crate) struct ActivityActor {
    /// `self`, `user`, `admin` (an administrator whose identity is hidden from
    /// this reader), `system` (no account: the purge job, a restore the server
    /// ran) or `unknown` (a row without a readable actor).
    pub(crate) kind: &'static str,
    /// Display name, never an email. Only for `self` and `user`.
    pub(crate) name: Option<String>,
    pub(crate) is_self: bool,
    pub(crate) avatar_url: Option<String>,
}

#[derive(Clone, Debug, Serialize)]
pub(crate) struct ActivityItem {
    /// Newest audit event folded into this item.
    pub(crate) id: i64,
    /// Oldest audit event folded into this item (equals `id` when `count` is 1).
    pub(crate) first_event_id: i64,
    pub(crate) created_at: String,
    pub(crate) first_created_at: String,
    pub(crate) action: String,
    pub(crate) entity_type: String,
    pub(crate) entity_id: i64,
    pub(crate) entity_name: Option<String>,
    pub(crate) exists: bool,
    /// `active`, `in_trash` or `gone`, as on audit resources.
    pub(crate) state: &'static str,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub(crate) trash: Option<AuditTrash>,
    pub(crate) href: Option<String>,
    pub(crate) thumbnail_url: Option<String>,
    pub(crate) actor: ActivityActor,
    pub(crate) changed_fields: Vec<String>,
    pub(crate) count: u32,
    /// No account caused this event.
    #[serde(skip_serializing_if = "std::ops::Not::not")]
    pub(crate) system: bool,
    /// The other account of a sharing event, or the owner an administrator or
    /// the system acted on. Never carries an email.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub(crate) subject: Option<AuditSubject>,
    #[serde(skip)]
    actor_user_id: Option<i64>,
    #[serde(skip)]
    oldest_seconds: Option<i64>,
}

pub(crate) struct ActivityPage {
    pub(crate) items: Vec<ActivityItem>,
    pub(crate) next_before: Option<i64>,
    pub(crate) has_more: bool,
}

fn parse_event_seconds(value: &str) -> Option<i64> {
    chrono::DateTime::parse_from_rfc3339(value)
        .map(|time| time.timestamp())
        .ok()
        .or_else(|| {
            chrono::NaiveDateTime::parse_from_str(value, "%Y-%m-%dT%H:%M:%S%.f")
                .ok()
                .map(|time| time.and_utc().timestamp())
        })
}

fn activity_item(
    row: &sqlx::sqlite::SqliteRow,
    user_id: i64,
    viewer_is_admin: bool,
    gravatar_enabled: bool,
) -> ActivityItem {
    let event = make_event(
        row,
        AuditAudience::Owner {
            viewer_id: user_id,
            reveal_email: true,
        },
        gravatar_enabled,
    );
    let snapshot: Option<String> = row.get("resource_name_snapshot");
    let current: Option<String> = row.get("current_name");
    let actor_user_id: Option<i64> = row.get("actor_user_id");
    let is_self = actor_user_id == Some(user_id);
    // The reader may open the resource (owner, also from the trash; share; administrator).
    // Events the reader caused on resources they lost access to stay in the
    // feed under their recorded name, without a link, picture or live name.
    let can_open = viewer_is_admin || row.get::<bool, _>("viewer_can_open");
    // Other people's administrators stay anonymous to a member, unless that
    // administrator owns the resource or was invited to it.
    let hide_admin = !viewer_is_admin
        && !is_self
        && actor_user_id.is_some()
        && row.get::<bool, _>("actor_is_admin")
        && row.get::<Option<i64>, _>("current_owner_id") != actor_user_id
        && !row.get::<bool, _>("actor_has_share");
    let kind = if is_self {
        "self"
    } else if event.system {
        "system"
    } else if actor_user_id.is_none() || event.actor_name.is_none() {
        "unknown"
    } else if hide_admin {
        "admin"
    } else {
        "user"
    };
    let shows_actor = matches!(kind, "self" | "user");
    let mut changed_fields = Vec::new();
    if let (Some(changes), _) = project_changes(row) {
        for item in &changes.items {
            if let Ok(serde_json::Value::String(field)) = serde_json::to_value(item.field) {
                changed_fields.push(field);
            }
        }
    }
    ActivityItem {
        id: event.id,
        first_event_id: event.id,
        oldest_seconds: parse_event_seconds(&event.created_at),
        first_created_at: event.created_at.clone(),
        created_at: event.created_at,
        action: event.action,
        entity_type: event.entity_type,
        entity_id: event.entity_id,
        entity_name: if can_open {
            current.or(snapshot)
        } else {
            snapshot
        },
        exists: event.resource.exists,
        state: event.resource.state,
        trash: event.resource.trash.filter(|_| can_open),
        href: event.resource.href.filter(|_| can_open),
        // Administrators read any trashed item's pictures, like on the Trash page.
        thumbnail_url: event
            .resource
            .preview_url
            .or_else(|| {
                (viewer_is_admin && event.resource.state == "in_trash")
                    .then_some(event.resource.kind)
                    .flatten()
                    .and_then(|kind| owner_preview_url(row, kind, event.resource.id))
            })
            .filter(|_| can_open),
        actor: ActivityActor {
            kind,
            name: event.actor_name.filter(|_| shows_actor),
            is_self,
            avatar_url: event.actor_avatar_url.filter(|_| shows_actor),
        },
        changed_fields,
        count: 1,
        system: event.system,
        subject: event.subject,
        actor_user_id,
    }
}

/// Actions that are one-off facts: a run of them never folds into one item.
fn is_standalone_action(action: &str) -> bool {
    matches!(
        action,
        "created"
            | "deleted"
            | "trashed"
            | "restored"
            | "purged"
            | "step_trashed"
            | "step_restored"
            | "step_purged"
            | "shared"
            | "share_role_changed"
            | "share_revoked"
            | "share_left"
            | "ownership_transferred"
            | "timer_started"
            | "timer_stopped"
            | "timer_total_changed"
    )
}

impl ActivityItem {
    /// Repeated edits by one actor to one resource collapse; lifecycle,
    /// sharing and timer events are always their own item.
    fn absorbs(&self, older: &Self) -> bool {
        if self.entity_type != older.entity_type
            || self.entity_id != older.entity_id
            || self.action != older.action
            || self.actor_user_id != older.actor_user_id
            || is_standalone_action(&self.action)
        {
            return false;
        }
        match (self.oldest_seconds, older.oldest_seconds) {
            (Some(newer), Some(older)) => newer - older <= ACTIVITY_COALESCE_SECONDS,
            _ => false,
        }
    }

    fn absorb(&mut self, older: Self) {
        self.count = self.count.saturating_add(older.count);
        self.first_event_id = older.first_event_id;
        self.first_created_at = older.first_created_at;
        self.oldest_seconds = older.oldest_seconds;
        for field in older.changed_fields {
            if !self.changed_fields.contains(&field) {
                self.changed_fields.push(field);
            }
        }
    }
}

/// The user's recent project and yarn events, newest first, with runs of
/// identical edits coalesced. `before` is an audit event id; only events older
/// than it are returned.
///
/// Every audit action counts (timer, trash, sharing, ...). Visible events are
/// those on resources the user can currently see (own, shared with them, or
/// linked to a project they can see; whoever acted), those on resources they
/// own even while in the trash, and every event the user caused themselves,
/// also on resources they do not own or that are gone. Other people's
/// resources never appear for any other reason.
pub(crate) async fn list_activity(
    pool: &SqlitePool,
    user_id: i64,
    limit: u32,
    before: Option<i64>,
    gravatar_enabled: bool,
) -> Result<ActivityPage, AuditError> {
    use crate::resource_access::{ResourceKind, visible_sql};

    let limit = limit.clamp(1, ACTIVITY_MAX_LIMIT) as usize;
    let viewer_is_admin: bool = sqlx::query_scalar(
        "SELECT EXISTS(SELECT 1 FROM users WHERE id = ? AND is_admin = 1 AND active = 1)",
    )
    .bind(user_id)
    .fetch_one(pool)
    .await
    .map_err(|_| AuditError::Database)?;
    let mut cursor: Option<(String, i64)> = match before {
        Some(id) => {
            let row = sqlx::query("SELECT created_at, id FROM audit_events WHERE id = ?")
                .bind(id)
                .fetch_optional(pool)
                .await
                .map_err(|_| AuditError::Database)?;
            match row {
                Some(row) => Some((row.get("created_at"), row.get("id"))),
                None => {
                    return Ok(ActivityPage {
                        items: Vec::new(),
                        next_before: None,
                        has_more: false,
                    });
                }
            }
        }
        None => None,
    };
    const PAGE_CURSOR: &str = "(?2 IS NULL OR (e.created_at, e.id) < (?2, ?3))";
    let branch = |from: &str, filter: &str| {
        format!(
            "SELECT id, created_at FROM (SELECT e.id, e.created_at FROM {from} \
               WHERE {filter} AND {PAGE_CURSOR} \
               ORDER BY e.created_at DESC, e.id DESC LIMIT ?4)"
        )
    };
    // Resources the user owns, even in the trash.
    let owned = |entity: &str, table: &str| {
        branch(
            &format!(
                "{table} AS r JOIN audit_events AS e \
                   ON e.entity_type = '{entity}' AND e.entity_id = r.id"
            ),
            "r.owner_id = ?1",
        )
    };
    // Resources the user can open now: shares and linked yarns.
    let visible = |entity: &str, table: &str, kind: ResourceKind| {
        branch(
            &format!(
                "{table} AS r JOIN audit_events AS e \
                   ON e.entity_type = '{entity}' AND e.entity_id = r.id"
            ),
            &visible_sql(kind, "r", 1),
        )
    };
    // Whatever the user did themselves, on resources of anybody, gone or not.
    let acted = |entity: &str| {
        branch(
            "audit_events AS e",
            &format!("e.actor_user_id = ?1 AND e.entity_type = '{entity}'"),
        )
    };
    let can_open = |entity: &str, table: &str, kind: ResourceKind| {
        format!(
            "WHEN '{entity}' THEN EXISTS(SELECT 1 FROM {table} AS vr \
               WHERE vr.id = e.entity_id AND (vr.owner_id = ?1 OR {visible}))",
            visible = visible_sql(kind, "vr", 1)
        )
    };
    let sql = format!(
        "{columns}, CASE e.entity_type {open_project} {open_yarn} ELSE 0 END AS viewer_can_open FROM (\
           {a} UNION {b} UNION {c} UNION {d} UNION {e} UNION {f} \
           ORDER BY created_at DESC, id DESC LIMIT ?4) AS v \
         JOIN audit_events AS e ON e.id = v.id \
         LEFT JOIN users AS u ON u.id = e.actor_user_id \
         LEFT JOIN user_avatars AS avatar ON avatar.user_id = e.actor_user_id \
         {media_join} \
         ORDER BY e.created_at DESC, e.id DESC",
        media_join = media_event_join!(),
        columns = resource_event_columns!(),
        open_project = can_open("project", "projects", ResourceKind::Project),
        open_yarn = can_open("yarn", "yarns", ResourceKind::Yarn),
        a = owned("project", "projects"),
        b = owned("yarn", "yarns"),
        c = visible("project", "projects", ResourceKind::Project),
        d = visible("yarn", "yarns", ResourceKind::Yarn),
        e = acted("project"),
        f = acted("yarn"),
    );

    let mut items: Vec<ActivityItem> = Vec::new();
    let mut has_more = false;
    'batches: loop {
        let rows = sqlx::query(sqlx::AssertSqlSafe(sql.as_str()))
            .bind(user_id)
            .bind(cursor.as_ref().map(|(created_at, _)| created_at.clone()))
            .bind(cursor.as_ref().map(|(_, id)| *id))
            .bind(ACTIVITY_BATCH)
            .fetch_all(pool)
            .await
            .map_err(|_| AuditError::Database)?;
        let fetched = rows.len() as i64;
        for row in rows {
            cursor = Some((row.get("created_at"), row.get("id")));
            let item = activity_item(&row, user_id, viewer_is_admin, gravatar_enabled);
            if let Some(last) = items.last_mut()
                && last.absorbs(&item)
            {
                last.absorb(item);
                continue;
            }
            if items.len() == limit {
                has_more = true;
                break 'batches;
            }
            items.push(item);
        }
        if fetched < ACTIVITY_BATCH {
            break;
        }
    }
    let next_before = if has_more {
        items.last().map(|item| item.first_event_id)
    } else {
        None
    };
    Ok(ActivityPage {
        items,
        next_before,
        has_more,
    })
}

/// Labels a history event refers to pictures by: the picture lists and
/// cover/primary values of its changes, plus the name of its media ref.
fn media_thumbnails_labels(row: &sqlx::sqlite::SqliteRow) -> Vec<String> {
    let mut labels: Vec<String> = Vec::new();
    let mut push = |label: &str| {
        if !label.is_empty() && !labels.iter().any(|known| known == label) {
            labels.push(label.to_owned());
        }
    };
    if let (Some(changes), _) = project_changes(row) {
        for item in changes.items.iter().filter(|item| {
            matches!(
                item.field,
                ChangeField::ProjectCoverImage
                    | ChangeField::ProjectImageOrder
                    | ChangeField::ProjectStepImageOrder
                    | ChangeField::YarnPrimaryPhoto
                    | ChangeField::YarnPhotoOrder
            )
        }) {
            for value in [&item.before, &item.after] {
                match value {
                    ChangeValue::Text(Some(label)) => push(label),
                    ChangeValue::TextList(list) => list.iter().for_each(|label| push(label)),
                    _ => {}
                }
            }
        }
    }
    if let Some(name) = row.get::<Option<String>, _>("media_name") {
        push(&name);
    }
    labels
}

/// A current picture of a resource, before its URLs are chosen.
struct CurrentMedia {
    label: String,
    filename: String,
    public_id: i64,
}

impl CurrentMedia {
    fn from_row(row: &sqlx::sqlite::SqliteRow) -> Self {
        let filename: String = row.get("filename");
        let original: String = row.get("original_filename");
        let public_id: i64 = row.get("public_id");
        Self {
            label: crate::audit::media_label(Some(&original), &filename, public_id),
            filename,
            public_id,
        }
    }

    /// Owner-facing URLs (the normal media routes).
    fn owner_urls(&self, subdir: &str, resource_id: i64) -> AuditChangeMedia {
        AuditChangeMedia {
            url: crate::media::media_url(subdir, resource_id, &self.filename),
            thumbnail_url: crate::media::thumbnail_url(subdir, resource_id, &self.filename),
        }
    }

    /// Read-only administrator URLs, valid for any owner's resource.
    fn admin_urls(
        &self,
        kind: &str,
        media_kind: &str,
        resource_id: i64,
    ) -> Option<AuditChangeMedia> {
        Some(AuditChangeMedia {
            url: crate::media::admin_resource_media_url(
                kind,
                resource_id,
                media_kind,
                self.public_id,
                false,
            )?,
            thumbnail_url: crate::media::admin_resource_media_url(
                kind,
                resource_id,
                media_kind,
                self.public_id,
                true,
            )?,
        })
    }
}

/// The resource's current pictures as `(label, urls)` in display order:
/// project title pictures before step pictures, each by position.
async fn current_media_labels(
    transaction: &mut sqlx::Transaction<'_, sqlx::Sqlite>,
    entity: AuditEntity,
    resource_id: i64,
    owner_id: i64,
) -> Result<Vec<(String, AuditChangeMedia)>, AuditError> {
    let (query, subdir) = match entity {
        AuditEntity::Project => (
            "SELECT public_id, filename, original_filename FROM media_files \
             WHERE project_id = ? AND owner_id = ? AND kind IN ('title', 'step') \
             ORDER BY CASE kind WHEN 'title' THEN 0 ELSE 1 END, position, id LIMIT 2000",
            "projects",
        ),
        AuditEntity::Yarn => (
            "SELECT public_id, filename, original_filename FROM media_files \
             WHERE yarn_id = ? AND owner_id = ? AND kind = 'photo' \
             ORDER BY position, id LIMIT 2000",
            "yarns",
        ),
        _ => return Ok(Vec::new()),
    };
    let rows = sqlx::query(query)
        .bind(resource_id)
        .bind(owner_id)
        .fetch_all(&mut **transaction)
        .await
        .map_err(|_| AuditError::Database)?;
    Ok(rows
        .iter()
        .map(|row| {
            let media = CurrentMedia::from_row(row);
            let urls = media.owner_urls(subdir, resource_id);
            (media.label, urls)
        })
        .collect())
}

/// Sets `media_thumbnails` on administrator audit events. The pictures of all
/// resources on the page are read with one bounded query per resource kind,
/// and the URLs are the read-only administrator media routes.
async fn attach_admin_media_thumbnails(
    pool: &SqlitePool,
    events: &mut [AuditEvent],
    wanted: &[Vec<String>],
) -> Result<(), AuditError> {
    for (entity_type, media_kind, query) in [
        (
            "project",
            "image",
            "SELECT project_id AS parent_id, public_id, filename, original_filename \
             FROM media_files \
             WHERE project_id IN (SELECT value FROM json_each(?)) \
               AND kind IN ('title', 'step') \
             ORDER BY project_id, CASE kind WHEN 'title' THEN 0 ELSE 1 END, position, id \
             LIMIT 20000",
        ),
        (
            "yarn",
            "photo",
            "SELECT yarn_id AS parent_id, public_id, filename, original_filename \
             FROM media_files \
             WHERE yarn_id IN (SELECT value FROM json_each(?)) AND kind = 'photo' \
             ORDER BY yarn_id, position, id LIMIT 20000",
        ),
    ] {
        let mut ids: Vec<i64> = events
            .iter()
            .zip(wanted)
            .filter(|(event, labels)| {
                event.entity_type == entity_type && event.entity_id > 0 && !labels.is_empty()
            })
            .map(|(event, _)| event.entity_id)
            .collect();
        ids.sort_unstable();
        ids.dedup();
        if ids.is_empty() {
            continue;
        }
        let json = serde_json::to_string(&ids).map_err(|_| AuditError::Database)?;
        let rows = sqlx::query(query)
            .bind(json)
            .fetch_all(pool)
            .await
            .map_err(|_| AuditError::Database)?;
        let mut by_parent: BTreeMap<i64, Vec<(String, AuditChangeMedia)>> = BTreeMap::new();
        for row in &rows {
            let parent_id: i64 = row.get("parent_id");
            let media = CurrentMedia::from_row(row);
            if let Some(urls) = media.admin_urls(entity_type, media_kind, parent_id) {
                by_parent
                    .entry(parent_id)
                    .or_default()
                    .push((media.label, urls));
            }
        }
        for (event, labels) in events.iter_mut().zip(wanted) {
            if event.entity_type == entity_type
                && let Some(current) = by_parent.get(&event.entity_id)
            {
                event.media_thumbnails = resolve_media_thumbnails(labels, current);
            }
        }
    }
    Ok(())
}

/// Maps each wanted label to the first current picture with that label.
fn resolve_media_thumbnails(
    wanted: &[String],
    current: &[(String, AuditChangeMedia)],
) -> Option<BTreeMap<String, AuditChangeMedia>> {
    let map: BTreeMap<String, AuditChangeMedia> = wanted
        .iter()
        .filter_map(|label| {
            current
                .iter()
                .find(|(known, _)| known == label)
                .map(|(_, media)| (label.clone(), media.clone()))
        })
        .collect();
    (!map.is_empty()).then_some(map)
}

async fn resource_belongs_to(
    transaction: &mut sqlx::Transaction<'_, sqlx::Sqlite>,
    owner_id: i64,
    entity: AuditEntity,
    resource_id: i64,
) -> Result<bool, AuditError> {
    let query = match entity {
        AuditEntity::Project => {
            "SELECT EXISTS(SELECT 1 FROM projects WHERE id = ? AND owner_id = ?)"
        }
        AuditEntity::Yarn => "SELECT EXISTS(SELECT 1 FROM yarns WHERE id = ? AND owner_id = ?)",
        _ => return Ok(false),
    };
    sqlx::query_scalar(query)
        .bind(resource_id)
        .bind(owner_id)
        .fetch_one(&mut **transaction)
        .await
        .map_err(|_| AuditError::Database)
}

fn make_event(
    row: &sqlx::sqlite::SqliteRow,
    audience: AuditAudience,
    gravatar_enabled: bool,
) -> AuditEvent {
    let entity_type: String = row.get("entity_type");
    let entity_id: i64 = row.get("entity_id");
    let snapshot: Option<String> = row.get("resource_name_snapshot");
    let current: Option<String> = row.get("current_name");
    let (name, name_source) = if let Some(name) = snapshot {
        (Some(name), "event")
    } else if let Some(name) = current {
        (Some(name), "current")
    } else {
        (None, "unavailable")
    };
    let kind = Entity::parse(&entity_type);
    let present = current_name_present(row);
    let deleted_at = row.get::<Option<String>, _>("current_deleted_at");
    let in_trash = present && deleted_at.is_some();
    let exists = present && !in_trash;
    let state = if exists {
        "active"
    } else if in_trash {
        "in_trash"
    } else {
        "gone"
    };
    // A restore button belongs to events about the item itself. File and step
    // events are about a child: they link to the Trash page only.
    let about_the_item = row.get::<Option<String>, _>("media_kind").is_none()
        && !row.get::<String, _>("action").starts_with("step_");
    let trash = deleted_at
        .as_deref()
        .filter(|_| in_trash && about_the_item)
        .and_then(|value| chrono::DateTime::parse_from_rfc3339(value).ok())
        .map(|trashed| {
            let trashed = trashed.with_timezone(&chrono::Utc);
            let can_restore = match audience {
                AuditAudience::Administrator => true,
                AuditAudience::Owner { viewer_id, .. } => {
                    row.get::<Option<i64>, _>("current_owner_id") == Some(viewer_id)
                }
            };
            AuditTrash {
                deleted_at: trashed.to_rfc3339_opts(chrono::SecondsFormat::Micros, true),
                purge_at: (trashed + chrono::Duration::days(crate::trash::RETENTION_DAYS))
                    .to_rfc3339_opts(chrono::SecondsFormat::Micros, true),
                days_remaining: crate::trash::days_remaining(trashed, chrono::Utc::now()),
                can_restore,
            }
        });
    // A trashed item's pictures stay readable for its owner and administrators
    // (the Trash page shows them too); everyone else sees none until it is restored.
    let show_preview = exists
        || (in_trash
            && match audience {
                AuditAudience::Administrator => true,
                AuditAudience::Owner { viewer_id, .. } => {
                    row.get::<Option<i64>, _>("current_owner_id") == Some(viewer_id)
                }
            });
    let preview_media_id = row.get::<Option<i64>, _>("current_preview_media_id");
    let resource = AuditResource {
        kind,
        id: entity_id,
        name,
        name_source,
        href: kind.and_then(|kind| match audience {
            AuditAudience::Administrator => (entity_id > 0).then(|| {
                if exists {
                    // Administrators open projects and yarns on the normal
                    // pages, whoever owns them.
                    match kind {
                        Entity::Project => format!("/app/projects/{entity_id}"),
                        Entity::Yarn => format!("/app/yarns/{entity_id}"),
                        Entity::Category => {
                            format!("/app/admin/resources/{}/{entity_id}", kind.as_str())
                        }
                    }
                } else {
                    format!("/app/admin/audit/{}", row.get::<i64, _>("id"))
                }
            }),
            AuditAudience::Owner { .. } if exists => match kind {
                Entity::Project => Some(format!("/app/projects/{entity_id}")),
                Entity::Yarn => Some(format!("/app/yarns/{entity_id}")),
                Entity::Category => None,
            },
            AuditAudience::Owner { .. } => None,
        }),
        preview_url: if show_preview {
            kind.and_then(|kind| {
                let media_kind = match kind {
                    Entity::Project => "image",
                    Entity::Yarn => "photo",
                    Entity::Category => return None,
                };
                match audience {
                    AuditAudience::Administrator => preview_media_id.and_then(|media_id| {
                        crate::media::admin_resource_media_url(
                            kind.as_str(),
                            entity_id,
                            media_kind,
                            media_id,
                            true,
                        )
                    }),
                    AuditAudience::Owner { .. } => owner_preview_url(row, kind, entity_id),
                }
            })
        } else {
            None
        },
        exists,
        state,
        trash,
    };
    let media = make_media(row, kind, entity_id, audience);
    let has_changes = row.get::<bool, _>("safe_changes_present")
        || row.get::<Option<i64>, _>("change_version").is_some()
        || row.get::<bool, _>("legacy_details_present");
    AuditEvent {
        id: row.get("id"),
        actor_email: match audience {
            AuditAudience::Owner {
                reveal_email: false,
                ..
            } => row
                .get::<Option<String>, _>("actor_email")
                .map(|_| String::new()),
            _ => row.get("actor_email"),
        },
        actor_name: row
            .get::<Option<String>, _>("actor_email")
            .map(|email| crate::resource_access::display_name(&email)),
        actor_avatar_url: {
            let actor_user_id = row.get::<Option<i64>, _>("actor_user_id");
            let filename = row.get::<Option<String>, _>("actor_avatar_filename");
            let use_gravatar = row.get::<Option<i64>, _>("actor_use_gravatar").unwrap_or(1) != 0;
            match (audience, actor_user_id) {
                (AuditAudience::Administrator, Some(actor_id)) => crate::media::admin_avatar(
                    actor_id,
                    filename.as_deref(),
                    use_gravatar,
                    gravatar_enabled,
                )
                .map(|(url, _)| url),
                (AuditAudience::Owner { viewer_id, .. }, Some(actor_id))
                    if actor_id == viewer_id =>
                {
                    crate::media::self_avatar(
                        actor_id,
                        filename.as_deref(),
                        use_gravatar,
                        gravatar_enabled,
                    )
                    .map(|(url, _)| url)
                }
                // Everyone else gets the avatar path any signed-in user may read.
                (AuditAudience::Owner { .. }, Some(actor_id)) => crate::media::people_avatar(
                    actor_id,
                    filename.as_deref(),
                    use_gravatar,
                    gravatar_enabled,
                )
                .map(|(url, _)| url),
                _ => None,
            }
        },
        legacy_actor_user_id: row.get("legacy_actor_user_id"),
        actor_is_self: match audience {
            AuditAudience::Owner { viewer_id, .. } => {
                row.get::<Option<i64>, _>("actor_user_id") == Some(viewer_id)
            }
            AuditAudience::Administrator => false,
        },
        system: row.get::<Option<i64>, _>("actor_user_id").is_none()
            && row.get::<Option<i64>, _>("legacy_actor_user_id").is_none()
            && (row.get::<String, _>("action") == "purged"
                || row.get::<Option<String>, _>("client_kind").as_deref() == Some("system")),
        entity_type,
        entity_id,
        action: row.get("action"),
        created_at: row.get("created_at"),
        resource,
        has_changes,
        media,
        media_thumbnails: None,
        client: matches!(audience, AuditAudience::Administrator).then(|| audit_client(row)),
        server: matches!(audience, AuditAudience::Administrator)
            .then(|| {
                row.get::<Option<String>, _>("server_details")
                    .and_then(|json| serde_json::from_str::<AuditServerEvent>(&json).ok())
            })
            .flatten(),
        impersonated_by: row
            .get::<Option<i64>, _>("impersonated_by_user_id")
            .map(|id| {
                let email = row.get::<Option<String>, _>("impersonator_email");
                AuditImpersonator {
                    id: Some(id),
                    name: email.as_deref().map(crate::resource_access::display_name),
                    email: email.filter(|_| matches!(audience, AuditAudience::Administrator)),
                    avatar_url: crate::media::people_avatar(
                        id,
                        row.get::<Option<String>, _>("impersonator_avatar_filename")
                            .as_deref(),
                        row.get::<Option<i64>, _>("impersonator_use_gravatar")
                            .unwrap_or(1)
                            != 0,
                        gravatar_enabled,
                    )
                    .map(|(url, _)| url),
                }
            }),
        subject: row
            .get::<Option<String>, _>("subject_role")
            .map(Some)
            .or_else(|| {
                row.get::<Option<i64>, _>("subject_user_id")
                    .is_some()
                    .then_some(None)
            })
            .map(|role| AuditSubject {
                id: row.get("subject_user_id"),
                name: row
                    .get::<Option<String>, _>("subject_email")
                    .map(|email| crate::resource_access::display_name(&email)),
                role,
                avatar_url: row
                    .get::<Option<i64>, _>("subject_user_id")
                    .and_then(|subject_id| {
                        let use_gravatar = row
                            .get::<Option<i64>, _>("subject_use_gravatar")
                            .unwrap_or(1)
                            != 0;
                        crate::media::people_avatar(
                            subject_id,
                            row.get::<Option<String>, _>("subject_avatar_filename")
                                .as_deref(),
                            use_gravatar,
                            gravatar_enabled,
                        )
                    })
                    .map(|(url, _)| url),
            }),
    }
}

fn make_media(
    row: &sqlx::sqlite::SqliteRow,
    kind: Option<Entity>,
    entity_id: i64,
    audience: AuditAudience,
) -> Option<AuditMedia> {
    let media_kind = row.get::<Option<String>, _>("media_kind")?;
    let public_id = row.get::<Option<i64>, _>("media_public_id")?;
    let kind = kind?;
    let current_filename = row.get::<Option<String>, _>("media_current_filename");
    let content_type = row.get::<Option<String>, _>("media_content_type");
    let safe_inline = row
        .get::<Option<bool>, _>("media_safe_inline")
        .unwrap_or(false);
    let is_image = media_kind != "attachment"
        || (safe_inline
            && content_type
                .as_deref()
                .is_some_and(|value| value.starts_with("image/")));
    let (url, thumbnail_url) = match (&current_filename, audience) {
        (None, _) => (None, None),
        (Some(_), AuditAudience::Administrator) => (
            crate::media::admin_resource_media_url(
                kind.as_str(),
                entity_id,
                &media_kind,
                public_id,
                false,
            ),
            is_image
                .then(|| {
                    crate::media::admin_resource_media_url(
                        kind.as_str(),
                        entity_id,
                        &media_kind,
                        public_id,
                        true,
                    )
                })
                .flatten(),
        ),
        (Some(filename), AuditAudience::Owner { .. }) => {
            let parent = match kind {
                Entity::Project => "projects",
                Entity::Yarn => "yarns",
                Entity::Category => return None,
            };
            (
                Some(format!("/media/{parent}/{entity_id}/{filename}")),
                (is_image && media_kind != "attachment").then(|| {
                    format!(
                        "/media/thumbnails/{parent}/{entity_id}/{}",
                        crate::media::avatar_thumbnail_name(filename)
                    )
                }),
            )
        }
    };
    Some(AuditMedia {
        kind: media_kind,
        id: public_id,
        name: row.get("media_name"),
        exists: current_filename.is_some(),
        is_image,
        content_type,
        size_bytes: row.get("media_size_bytes"),
        url,
        thumbnail_url,
    })
}

/// The plain media thumbnail of a project's or yarn's cover, as its owner,
/// share recipients and administrators load it.
fn owner_preview_url(
    row: &sqlx::sqlite::SqliteRow,
    kind: Entity,
    entity_id: i64,
) -> Option<String> {
    let parent = match kind {
        Entity::Project => "projects",
        Entity::Yarn => "yarns",
        Entity::Category => return None,
    };
    row.get::<Option<String>, _>("current_preview_filename")
        .map(|filename| {
            format!(
                "/media/thumbnails/{parent}/{entity_id}/{}",
                crate::media::avatar_thumbnail_name(&filename)
            )
        })
}

fn current_name_present(row: &sqlx::sqlite::SqliteRow) -> bool {
    row.get::<Option<String>, _>("current_name").is_some()
}

fn project_changes(row: &sqlx::sqlite::SqliteRow) -> (Option<ChangeSetV1>, Option<&'static str>) {
    let entity_type: String = row.get("entity_type");
    if let Some(version) = row.get::<Option<i64>, _>("change_version") {
        if version != 1 {
            return (None, Some("malformed"));
        }
        if row.get::<bool, _>("safe_changes_too_large") {
            return (None, Some("too_large"));
        }
        let Some(encoded) = row.get::<Option<String>, _>("safe_changes") else {
            return (None, Some("too_large"));
        };
        if encoded.len() > crate::audit::MAX_CHANGESET_BYTES {
            return (None, Some("too_large"));
        }
        let parsed = serde_json::from_str::<ChangeSetV1>(&encoded);
        return match parsed {
            Ok(changes) if projected_changes_valid(&changes, &entity_type) => (Some(changes), None),
            _ => (None, Some("malformed")),
        };
    }
    let details: Option<String> = row.get("details");
    if row.get::<bool, _>("legacy_details_too_large") {
        return (None, Some("too_large"));
    }
    let Some(details) = details else {
        return (None, Some("not_recorded"));
    };
    match project_legacy_changes(&details, &entity_type) {
        Ok(Some(changes)) => (Some(changes), None),
        Ok(None) => (None, Some("not_recorded")),
        Err(reason) => (None, Some(reason)),
    }
}

fn fields_match_entity(changes: &ChangeSetV1, entity_type: &str) -> bool {
    let Some(entity) = Entity::parse(entity_type) else {
        return false;
    };
    changes.matches_entity(entity)
}

fn projected_changes_valid(changes: &ChangeSetV1, entity_type: &str) -> bool {
    changes.version == 1
        && changes.items.len() <= crate::audit::MAX_CHANGE_ITEMS
        && changes
            .items
            .iter()
            .map(|item| item.field)
            .collect::<HashSet<_>>()
            .len()
            == changes.items.len()
        && changes.items.iter().all(|item| {
            item.is_valid()
                || (matches!(item.field, ChangeField::ProjectTags | ChangeField::ProjectYarns)
                    && matches!((&item.before, &item.after), (ChangeValue::LegacyText(before), ChangeValue::LegacyText(after))
                        if safe_legacy_list(item.field, before) && safe_legacy_list(item.field, after)))
        })
        && fields_match_entity(changes, entity_type)
        && serde_json::to_vec(changes)
            .is_ok_and(|encoded| encoded.len() <= crate::audit::MAX_CHANGESET_BYTES)
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct LegacyDiff {
    old: serde_json::Value,
    new: serde_json::Value,
}

fn project_legacy_changes(
    details: &str,
    entity_type: &str,
) -> Result<Option<ChangeSetV1>, &'static str> {
    if details.len() > crate::audit::MAX_CHANGESET_BYTES {
        return Err("too_large");
    }
    let root: serde_json::Value = serde_json::from_str(details).map_err(|_| "malformed")?;
    let Some(changes) = root.get("changes").and_then(serde_json::Value::as_object) else {
        return Ok(None);
    };
    let Some(entity) = Entity::parse(entity_type) else {
        return Err("malformed");
    };
    let mut items = Vec::new();
    for (name, raw) in changes {
        let Some(field) = ChangeField::from_legacy_field(entity, name) else {
            continue;
        };
        let diff: LegacyDiff = serde_json::from_value(raw.clone()).map_err(|_| "malformed")?;
        let before = legacy_value(field, diff.old)?;
        let after = legacy_value(field, diff.new)?;
        let item = ChangeItemV1 {
            field,
            before,
            after,
        };
        let safe_legacy_list =
            matches!(field, ChangeField::ProjectTags | ChangeField::ProjectYarns)
                && matches!(item.before, ChangeValue::LegacyText(_))
                && matches!(item.after, ChangeValue::LegacyText(_));
        if !item.is_valid() && !safe_legacy_list {
            return Err("malformed");
        }
        items.push(item);
        if items.len() > crate::audit::MAX_CHANGE_ITEMS {
            return Err("too_large");
        }
    }
    if items.is_empty() {
        return Ok(None);
    }
    let result = ChangeSetV1 { version: 1, items };
    if !fields_match_entity(&result, entity_type) {
        return Err("malformed");
    }
    let encoded = serde_json::to_vec(&result).map_err(|_| "malformed")?;
    if encoded.len() > crate::audit::MAX_CHANGESET_BYTES {
        return Err("too_large");
    }
    Ok(Some(result))
}

fn legacy_value(field: ChangeField, value: serde_json::Value) -> Result<ChangeValue, &'static str> {
    let expected = field.value_type();
    let value = match expected {
        crate::audit::ChangeValueType::Text => ChangeValue::Text(match value {
            serde_json::Value::Null => None,
            serde_json::Value::String(value) if value.len() <= 2 * 1024 * 1024 => Some(value),
            _ => return Err("malformed"),
        }),
        crate::audit::ChangeValueType::Integer => ChangeValue::Integer(match value {
            serde_json::Value::Null => None,
            serde_json::Value::Number(number) => Some(number.as_i64().ok_or("malformed")?),
            _ => return Err("malformed"),
        }),
        crate::audit::ChangeValueType::Boolean => {
            ChangeValue::Boolean(value.as_bool().ok_or("malformed")?)
        }
        crate::audit::ChangeValueType::LinkChanged => ChangeValue::LinkChanged(match value {
            serde_json::Value::Null => false,
            serde_json::Value::String(_) => true,
            _ => return Err("malformed"),
        }),
        crate::audit::ChangeValueType::TextList | crate::audit::ChangeValueType::Yarns => {
            if !matches!(field, ChangeField::ProjectTags | ChangeField::ProjectYarns) {
                return Err("malformed");
            }
            let text = value.as_str().ok_or("malformed")?;
            if !safe_legacy_list(field, text) {
                return Err("malformed");
            }
            ChangeValue::LegacyText(text.to_owned())
        }
        _ => return Err("malformed"),
    };
    Ok(value)
}

fn safe_legacy_list(field: ChangeField, value: &str) -> bool {
    let Some(body) = value
        .strip_prefix('[')
        .and_then(|value| value.strip_suffix(']'))
    else {
        return false;
    };
    if body.trim().is_empty() {
        return true;
    }
    let allow_integers = matches!(field, ChangeField::ProjectYarns);
    let bytes = body.as_bytes();
    let mut index = 0;
    let mut count = 0;
    while index < bytes.len() {
        while index < bytes.len() && bytes[index].is_ascii_whitespace() {
            index += 1;
        }
        if index == bytes.len() {
            break;
        }
        let quote = bytes[index];
        if quote == b'\'' || quote == b'"' {
            index += 1;
            let mut closed = false;
            while index < bytes.len() {
                if bytes[index] == b'\\' {
                    index += 2;
                    continue;
                }
                if bytes[index] == quote {
                    index += 1;
                    closed = true;
                    break;
                }
                index += 1;
            }
            if !closed {
                return false;
            }
        } else if allow_integers {
            let start = index;
            while index < bytes.len() && bytes[index].is_ascii_digit() {
                index += 1;
            }
            if start == index {
                return false;
            }
        } else {
            return false;
        }
        count += 1;
        if count > 4096 {
            return false;
        }
        while index < bytes.len() && bytes[index].is_ascii_whitespace() {
            index += 1;
        }
        if index == bytes.len() {
            break;
        }
        if bytes[index] != b',' {
            return false;
        }
        index += 1;
        while index < bytes.len() && bytes[index].is_ascii_whitespace() {
            index += 1;
        }
        if index == bytes.len() {
            break;
        }
    }
    true
}

#[cfg(test)]
mod tests {
    use super::{project_legacy_changes, safe_legacy_list};
    use crate::audit::ChangeField;

    #[test]
    fn legacy_projector_keeps_known_fields_and_omits_unknown_fields() {
        let details = r#"{"changes":{"name":{"old":"Before","new":"After"},"tags":{"old":"['a']","new":"['b']"},"password_hash":{"old":"x","new":"y"}}}"#;
        let changes = project_legacy_changes(details, "project").unwrap().unwrap();
        assert_eq!(changes.items.len(), 2);
        let encoded = serde_json::to_string(&changes).unwrap();
        assert!(encoded.contains("project.name"));
        assert!(encoded.contains("legacy_text"));
        assert!(!encoded.contains("password_hash"));
        assert!(!encoded.contains("\"x\""));
    }

    #[test]
    fn python_list_projection_accepts_only_quoted_scalar_lists() {
        assert!(safe_legacy_list(ChangeField::ProjectTags, "['one', 'two']"));
        assert!(safe_legacy_list(ChangeField::ProjectTags, "[]"));
        assert!(safe_legacy_list(ChangeField::ProjectYarns, "[17, 28]"));
        assert!(!safe_legacy_list(ChangeField::ProjectTags, "[object()]"));
        assert!(!safe_legacy_list(
            ChangeField::ProjectTags,
            "['one',, 'two']"
        ));
        assert!(!safe_legacy_list(ChangeField::ProjectYarns, "[object()]"));
        assert!(!safe_legacy_list(
            ChangeField::ProjectTags,
            "{'nested': 'mapping'}"
        ));
        assert!(!safe_legacy_list(
            ChangeField::ProjectTags,
            "['unterminated]"
        ));
    }
}
