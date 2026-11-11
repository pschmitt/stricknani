//! The trash: deleted projects, yarns, categories, steps and media, restore,
//! purge and retention.
//!
//! * Projects and yarns are flagged in place (`deleted_at`). The row keeps its
//!   steps, media, links and shares and every media file stays on disk. Every
//!   read path filters trashed rows out (`resource_access::visible_sql`,
//!   `resource_access::grant` and the list queries), so a trashed item is gone
//!   for everybody, including sync, which keeps receiving the deletion
//!   tombstone.
//! * Categories, steps and media files move into `trashed_categories`,
//!   `trashed_steps` and `trashed_media`; the live row is removed, so no other
//!   query has to know about the trash. Media files stay on disk until the
//!   trashed row is purged.
//!
//! The owner and administrators can list the trash, restore an item or delete
//! it forever. The server purges items after [`RETENTION_DAYS`].
//!
//! Children and parents: a child (step, image, attachment, photo) trashed on
//! its own is listed only while its parent is live, and restoring it while the
//! parent is in the trash is refused with [`TrashError::ParentInTrash`] (restore
//! the parent first). Children that were part of the parent when it was
//! trashed are never separate items: restoring the parent brings them back.

use chrono::{DateTime, Duration, SecondsFormat, Utc};
use serde::Serialize;
use sqlx::{Row, SqlitePool};

use crate::audit::{self, Action, Entity};
use crate::db::AppState;
use crate::resource_access::{self, OwnerInfo, Scope};
use sqlx::{Sqlite, Transaction};

/// How long an item stays in the trash before the server destroys it.
pub const RETENTION_DAYS: i64 = 90;

/// How many expired items one sweep destroys at most.
const PURGE_BATCH: i64 = 200;

/// Most items one listing returns.
const LIST_LIMIT: i64 = 1000;

/// Every kind of item the trash holds.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum TrashKind {
    Project,
    Yarn,
    Category,
    Step,
    /// A project title or step image.
    Image,
    Attachment,
    /// A yarn photo.
    Photo,
}

impl TrashKind {
    /// The plural path segment of `/trash/{segment}/{id}`.
    pub(crate) fn from_segment(segment: &str) -> Option<Self> {
        Some(match segment {
            "projects" => Self::Project,
            "yarns" => Self::Yarn,
            "categories" => Self::Category,
            "steps" => Self::Step,
            "images" => Self::Image,
            "attachments" => Self::Attachment,
            "photos" => Self::Photo,
            _ => return None,
        })
    }

    const fn namespace(self) -> &'static str {
        match self {
            Self::Image => "image",
            Self::Attachment => "attachment",
            _ => "photo",
        }
    }
}

#[derive(Debug)]
pub(crate) enum TrashError {
    /// Not in the trash, or not visible to the caller.
    NotFound,
    Invalid(&'static str),
    /// The parent of this child is in the trash: restore it first.
    ParentInTrash,
    /// A live category already has this name.
    NameTaken,
    Database,
}

impl From<sqlx::Error> for TrashError {
    fn from(_: sqlx::Error) -> Self {
        Self::Database
    }
}

impl From<crate::media::MediaRestoreError> for TrashError {
    fn from(error: crate::media::MediaRestoreError) -> Self {
        match error {
            crate::media::MediaRestoreError::NotFound => Self::NotFound,
            crate::media::MediaRestoreError::ParentInTrash => Self::ParentInTrash,
            crate::media::MediaRestoreError::Database => Self::Database,
        }
    }
}

/// The project or yarn a trashed child belonged to.
#[derive(Debug, Serialize, PartialEq, Eq, Clone)]
pub struct TrashParent {
    pub kind: &'static str,
    pub id: i64,
    pub name: String,
}

#[derive(Debug, Serialize, PartialEq, Eq)]
pub struct TrashItem {
    /// `project`, `yarn`, `category`, `step`, `image`, `attachment` or `photo`.
    pub kind: &'static str,
    pub id: i64,
    pub name: String,
    pub owner: Option<OwnerInfo>,
    pub deleted_at: String,
    /// When the server destroys the item unless it is restored.
    pub purge_at: String,
    /// Whole days left, rounded up; `0` once the purge is due.
    pub days_remaining: i64,
    /// Children only: the project or yarn they belong to.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub parent: Option<TrashParent>,
    /// Children only: for a step the project, for a file its kind of use.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub detail: Option<String>,
}

fn parse(value: &str) -> Option<DateTime<Utc>> {
    DateTime::parse_from_rfc3339(value)
        .ok()
        .map(|value| value.with_timezone(&Utc))
}

fn stamp(value: DateTime<Utc>) -> String {
    value.to_rfc3339_opts(SecondsFormat::Micros, true)
}

pub(crate) fn now_stamp() -> String {
    stamp(Utc::now())
}

/// Days left for an item trashed at `deleted_at`, rounded up and never negative.
pub(crate) fn days_remaining(deleted_at: DateTime<Utc>, now: DateTime<Utc>) -> i64 {
    let left = deleted_at + Duration::days(RETENTION_DAYS) - now;
    let seconds = left.num_seconds();
    if seconds <= 0 {
        0
    } else {
        (seconds + 86_399) / 86_400
    }
}

struct ListRow {
    kind: &'static str,
    id: i64,
    name: String,
    owner_id: i64,
    deleted_at: String,
    parent: Option<TrashParent>,
    detail: Option<String>,
}

/// Items in the trash, newest deletion first. `Mine` lists the caller's own;
/// `All` is for administrators and lists every account's.
pub(crate) async fn list(
    state: &AppState,
    actor_id: i64,
    requested: Option<&str>,
) -> Result<(Scope, Vec<TrashItem>), TrashError> {
    let requested = match requested {
        None => Scope::Mine,
        Some(value) => Scope::parse(value).ok_or(TrashError::Invalid("Invalid scope"))?,
    };
    let scope = resource_access::effective_scope(&state.pool, actor_id, Some(requested)).await?;
    let owner = (scope == Scope::Mine).then_some(actor_id);
    let mut rows: Vec<ListRow> = Vec::new();
    for (kind, sql) in [
        (
            "project",
            "SELECT id, name, owner_id, deleted_at FROM projects \
             WHERE deleted_at IS NOT NULL AND (?1 IS NULL OR owner_id = ?1)",
        ),
        (
            "yarn",
            "SELECT id, name, owner_id, deleted_at FROM yarns \
             WHERE deleted_at IS NOT NULL AND (?1 IS NULL OR owner_id = ?1)",
        ),
        (
            "category",
            "SELECT id, name, user_id, deleted_at FROM trashed_categories \
             WHERE ?1 IS NULL OR user_id = ?1",
        ),
    ] {
        for row in sqlx::query(sql).bind(owner).fetch_all(&state.pool).await? {
            rows.push(ListRow {
                kind,
                id: row.get(0),
                name: row.get(1),
                owner_id: row.get(2),
                deleted_at: row.get(3),
                parent: None,
                detail: None,
            });
        }
    }
    // Steps whose project is live.
    for row in sqlx::query(
        "SELECT s.id, s.title, s.owner_id, s.deleted_at, p.id, p.name \
         FROM trashed_steps s JOIN projects p ON p.id = s.project_id \
         WHERE p.deleted_at IS NULL AND (?1 IS NULL OR s.owner_id = ?1)",
    )
    .bind(owner)
    .fetch_all(&state.pool)
    .await?
    {
        rows.push(ListRow {
            kind: "step",
            id: row.get(0),
            name: row.get(1),
            owner_id: row.get(2),
            deleted_at: row.get(3),
            parent: Some(TrashParent {
                kind: "project",
                id: row.get(4),
                name: row.get(5),
            }),
            detail: None,
        });
    }
    // Files trashed on their own whose project or yarn is live and whose step
    // (when they belonged to one) is not in the trash either.
    for row in sqlx::query(
        "SELECT m.public_id, m.original_filename, m.owner_id, m.deleted_at, m.kind, \
                p.id, p.name, y.id, y.name \
         FROM trashed_media m \
         LEFT JOIN projects p ON p.id = m.project_id \
         LEFT JOIN yarns y ON y.id = m.yarn_id \
         WHERE m.with_step = 0 AND (?1 IS NULL OR m.owner_id = ?1) \
           AND COALESCE(p.deleted_at, y.deleted_at) IS NULL \
           AND (m.step_id IS NULL OR NOT EXISTS (SELECT 1 FROM trashed_steps s WHERE s.id = m.step_id))",
    )
    .bind(owner)
    .fetch_all(&state.pool)
    .await?
    {
        let media_kind: String = row.get(4);
        let parent = match (row.get::<Option<i64>, _>(5), row.get::<Option<i64>, _>(7)) {
            (Some(id), _) => TrashParent {
                kind: "project",
                id,
                name: row.get(6),
            },
            (None, Some(id)) => TrashParent {
                kind: "yarn",
                id,
                name: row.get(8),
            },
            _ => continue,
        };
        rows.push(ListRow {
            kind: match media_kind.as_str() {
                "attachment" => "attachment",
                "photo" => "photo",
                _ => "image",
            },
            id: row.get(0),
            name: row.get(1),
            owner_id: row.get(2),
            deleted_at: row.get(3),
            parent: Some(parent),
            detail: None,
        });
    }
    rows.sort_by(|a, b| {
        (&b.deleted_at, a.kind, b.id).cmp(&(&a.deleted_at, b.kind, a.id))
    });
    rows.truncate(LIST_LIMIT as usize);
    let now = Utc::now();
    let mut owners = std::collections::HashMap::new();
    let mut items = Vec::with_capacity(rows.len());
    for row in rows {
        let Some(trashed) = parse(&row.deleted_at) else {
            continue;
        };
        if let std::collections::hash_map::Entry::Vacant(entry) = owners.entry(row.owner_id) {
            entry.insert(
                resource_access::owner_info(
                    &state.pool,
                    actor_id,
                    row.owner_id,
                    state.gravatar.enabled,
                )
                .await?,
            );
        }
        items.push(TrashItem {
            kind: row.kind,
            id: row.id,
            name: row.name,
            owner: owners.get(&row.owner_id).cloned().flatten(),
            purge_at: stamp(trashed + Duration::days(RETENTION_DAYS)),
            days_remaining: days_remaining(trashed, now),
            deleted_at: row.deleted_at,
            parent: row.parent,
            detail: row.detail,
        });
    }
    Ok((scope, items))
}

/// Whether `actor_id` may restore or purge items of `owner_id`: the owner and
/// active administrators.
async fn may_manage(pool: &SqlitePool, actor_id: i64, owner_id: i64) -> Result<bool, sqlx::Error> {
    if actor_id == owner_id {
        return Ok(true);
    }
    resource_access::is_active_admin(pool, actor_id).await
}

fn project_error(error: crate::projects::ProjectError) -> TrashError {
    use crate::projects::ProjectError;
    match error {
        ProjectError::Database => TrashError::Database,
        _ => TrashError::NotFound,
    }
}

/// Take an item out of the trash. Owner or administrator only; anyone else
/// gets `NotFound`, as does an item that is not in the trash.
pub(crate) async fn restore(
    state: &AppState,
    actor_id: i64,
    kind: TrashKind,
    id: i64,
) -> Result<(), TrashError> {
    match kind {
        TrashKind::Project => crate::projects::restore_for_actor(state, actor_id, id)
            .await
            .map_err(project_error),
        TrashKind::Yarn => crate::yarns::restore_for_actor(state, actor_id, id)
            .await
            .map_err(|error| match error {
                crate::yarns::YarnError::Database => TrashError::Database,
                _ => TrashError::NotFound,
            }),
        TrashKind::Category => restore_category(state, actor_id, id).await,
        TrashKind::Step => restore_step(state, actor_id, id).await,
        TrashKind::Image | TrashKind::Attachment | TrashKind::Photo => {
            restore_media(state, actor_id, kind, id).await
        }
    }
}

/// Destroy a trashed item for good: what a hard delete always did. Owner or
/// administrator only, and only for an item that is in the trash.
pub(crate) async fn purge(
    state: &AppState,
    actor_id: i64,
    kind: TrashKind,
    id: i64,
) -> Result<(), TrashError> {
    let owner_id = owner_of(&state.pool, kind, id)
        .await?
        .ok_or(TrashError::NotFound)?;
    if !may_manage(&state.pool, actor_id, owner_id).await? {
        return Err(TrashError::NotFound);
    }
    let mut tx = crate::db::begin_write(&state.pool).await?;
    if purge_in(&mut tx, kind, id, Some(actor_id)).await? {
        tx.commit().await?;
        Ok(())
    } else {
        Err(TrashError::NotFound)
    }
}

async fn owner_of(pool: &SqlitePool, kind: TrashKind, id: i64) -> Result<Option<i64>, sqlx::Error> {
    let sql = match kind {
        TrashKind::Project => {
            "SELECT owner_id FROM projects WHERE id = ?1 AND deleted_at IS NOT NULL"
        }
        TrashKind::Yarn => "SELECT owner_id FROM yarns WHERE id = ?1 AND deleted_at IS NOT NULL",
        TrashKind::Category => "SELECT user_id FROM trashed_categories WHERE id = ?1",
        TrashKind::Step => "SELECT owner_id FROM trashed_steps WHERE id = ?1",
        TrashKind::Image => {
            "SELECT owner_id FROM trashed_media WHERE public_id = ?1 AND kind IN ('title','step')"
        }
        TrashKind::Attachment => {
            "SELECT owner_id FROM trashed_media WHERE public_id = ?1 AND kind = 'attachment'"
        }
        TrashKind::Photo => {
            "SELECT owner_id FROM trashed_media WHERE public_id = ?1 AND kind = 'photo'"
        }
    };
    sqlx::query_scalar::<_, i64>(sql)
        .bind(id)
        .fetch_optional(pool)
        .await
}

/// Destroy one trashed item inside `tx`. `false` when it is not in the trash.
///
/// The cascade removes steps, media rows, links and shares; the media rows'
/// triggers queue their files for the background sweeper.
async fn purge_in(
    tx: &mut Transaction<'_, Sqlite>,
    kind: TrashKind,
    id: i64,
    actor_id: Option<i64>,
) -> Result<bool, sqlx::Error> {
    match kind {
        TrashKind::Project | TrashKind::Yarn => {
            let (select, delete, entity, action) = match kind {
                TrashKind::Project => (
                    "SELECT name, owner_id FROM projects WHERE id = ? AND deleted_at IS NOT NULL",
                    "DELETE FROM projects WHERE id = ? AND deleted_at IS NOT NULL",
                    Entity::Project,
                    Action::Purged,
                ),
                _ => (
                    "SELECT name, owner_id FROM yarns WHERE id = ? AND deleted_at IS NOT NULL",
                    "DELETE FROM yarns WHERE id = ? AND deleted_at IS NOT NULL",
                    Entity::Yarn,
                    Action::Purged,
                ),
            };
            let Some((name, owner_id)) = sqlx::query_as::<_, (String, i64)>(select)
                .bind(id)
                .fetch_optional(&mut **tx)
                .await?
            else {
                return Ok(false);
            };
            if sqlx::query(delete)
                .bind(id)
                .execute(&mut **tx)
                .await?
                .rows_affected()
                != 1
            {
                return Ok(false);
            }
            // Manual purges name the account that did it; the automatic one
            // has no actor and reads as a system event. The owner is the
            // subject unless the owner acted.
            audit::record_lifecycle(tx, actor_id, owner_id, entity, id, action, &name, None)
                .await?;
            Ok(true)
        }
        TrashKind::Category => {
            let Some((name, owner_id)) = sqlx::query_as::<_, (String, i64)>(
                "SELECT name, user_id FROM trashed_categories WHERE id = ?",
            )
            .bind(id)
            .fetch_optional(&mut **tx)
            .await?
            else {
                return Ok(false);
            };
            sqlx::query("DELETE FROM trashed_categories WHERE id = ?")
                .bind(id)
                .execute(&mut **tx)
                .await?;
            audit::record_lifecycle(
                tx,
                actor_id,
                owner_id,
                Entity::Category,
                id,
                Action::Purged,
                &name,
                None,
            )
            .await?;
            Ok(true)
        }
        TrashKind::Step => {
            let Some((title, project_id, owner_id)) = sqlx::query_as::<_, (String, i64, i64)>(
                "SELECT title, project_id, owner_id FROM trashed_steps WHERE id = ?",
            )
            .bind(id)
            .fetch_optional(&mut **tx)
            .await?
            else {
                return Ok(false);
            };
            let label = step_label(tx, project_id, &title).await?;
            sqlx::query("DELETE FROM trashed_steps WHERE id = ?")
                .bind(id)
                .execute(&mut **tx)
                .await?;
            audit::record_lifecycle(
                tx,
                actor_id,
                owner_id,
                Entity::Project,
                project_id,
                Action::StepPurged,
                &label,
                None,
            )
            .await?;
            Ok(true)
        }
        TrashKind::Image | TrashKind::Attachment | TrashKind::Photo => {
            let Some(media) =
                crate::media::find_trashed_media(tx, kind.namespace(), id).await?
            else {
                return Ok(false);
            };
            sqlx::query("DELETE FROM trashed_media WHERE id = ?")
                .bind(media.id)
                .execute(&mut **tx)
                .await?;
            let (entity, parent_id, parent_name) = media_parent(tx, &media).await?;
            audit::record_lifecycle(
                tx,
                actor_id,
                media.owner_id,
                entity,
                parent_id,
                Action::Purged,
                &parent_name,
                Some(&media.audit_ref()),
            )
            .await?;
            Ok(true)
        }
    }
}

async fn step_label(
    tx: &mut Transaction<'_, Sqlite>,
    project_id: i64,
    title: &str,
) -> Result<String, sqlx::Error> {
    let project = sqlx::query_scalar::<_, String>("SELECT name FROM projects WHERE id = ?")
        .bind(project_id)
        .fetch_optional(&mut **tx)
        .await?
        .unwrap_or_default();
    Ok(format!("{project} \u{203a} {title}"))
}

async fn media_parent(
    tx: &mut Transaction<'_, Sqlite>,
    media: &crate::media::TrashedMedia,
) -> Result<(Entity, i64, String), sqlx::Error> {
    if let Some(project_id) = media.project_id {
        let name = sqlx::query_scalar::<_, String>("SELECT name FROM projects WHERE id = ?")
            .bind(project_id)
            .fetch_optional(&mut **tx)
            .await?
            .unwrap_or_default();
        Ok((Entity::Project, project_id, name))
    } else {
        let yarn_id = media.yarn_id.unwrap_or_default();
        let name = sqlx::query_scalar::<_, String>("SELECT name FROM yarns WHERE id = ?")
            .bind(yarn_id)
            .fetch_optional(&mut **tx)
            .await?
            .unwrap_or_default();
        Ok((Entity::Yarn, yarn_id, name))
    }
}

// --- steps -------------------------------------------------------------------

/// Move a project step and its images to the trash (called when a project
/// update leaves the step out).
pub(crate) async fn trash_step(
    tx: &mut Transaction<'_, Sqlite>,
    actor_id: i64,
    owner_id: i64,
    project_id: i64,
    step_id: i64,
) -> Result<(), sqlx::Error> {
    let Some((title, description, number)) = sqlx::query_as::<_, (String, Option<String>, i64)>(
        "SELECT title, description, step_number FROM project_steps WHERE id = ? AND project_id = ?",
    )
    .bind(step_id)
    .bind(project_id)
    .fetch_optional(&mut **tx)
    .await?
    else {
        return Ok(());
    };
    let now = now_stamp();
    let images = sqlx::query_scalar::<_, i64>("SELECT id FROM media_files WHERE step_id = ?")
        .bind(step_id)
        .fetch_all(&mut **tx)
        .await?;
    for image in images {
        crate::media::trash_media_row(tx, image, actor_id, &now, true).await?;
    }
    sqlx::query(
        "INSERT INTO trashed_steps \
         (id, project_id, owner_id, title, description, step_number, deleted_at, deleted_by) \
         VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
    )
    .bind(step_id)
    .bind(project_id)
    .bind(owner_id)
    .bind(&title)
    .bind(description)
    .bind(number)
    .bind(&now)
    .bind(actor_id)
    .execute(&mut **tx)
    .await?;
    sqlx::query("DELETE FROM project_steps WHERE id = ? AND project_id = ?")
        .bind(step_id)
        .bind(project_id)
        .execute(&mut **tx)
        .await?;
    let label = step_label(tx, project_id, &title).await?;
    audit::record_lifecycle(
        tx,
        Some(actor_id),
        owner_id,
        Entity::Project,
        project_id,
        Action::StepTrashed,
        &label,
        None,
    )
    .await
}

async fn restore_step(state: &AppState, actor_id: i64, id: i64) -> Result<(), TrashError> {
    let (project_id, owner_id, title, description, number) =
        sqlx::query_as::<_, (i64, i64, String, Option<String>, i64)>(
            "SELECT project_id, owner_id, title, description, step_number \
             FROM trashed_steps WHERE id = ?",
        )
        .bind(id)
        .fetch_optional(&state.pool)
        .await?
        .ok_or(TrashError::NotFound)?;
    if !may_manage(&state.pool, actor_id, owner_id).await? {
        return Err(TrashError::NotFound);
    }
    let mut tx = crate::db::begin_write(&state.pool).await?;
    let live = sqlx::query_scalar::<_, bool>(
        "SELECT EXISTS(SELECT 1 FROM projects WHERE id = ? AND deleted_at IS NULL)",
    )
    .bind(project_id)
    .fetch_one(&mut *tx)
    .await?;
    if !live {
        return Err(TrashError::ParentInTrash);
    }
    sqlx::query(
        "INSERT INTO project_steps (id, project_id, title, description, step_number) \
         VALUES (?, ?, ?, ?, ?)",
    )
    .bind(id)
    .bind(project_id)
    .bind(&title)
    .bind(description)
    .bind(number)
    .execute(&mut *tx)
    .await?;
    let images =
        sqlx::query_scalar::<_, i64>("SELECT id FROM trashed_media WHERE step_id = ? AND with_step = 1")
            .bind(id)
            .fetch_all(&mut *tx)
            .await?;
    for image in images {
        crate::media::restore_trashed_media_row(&mut tx, image, false).await?;
    }
    sqlx::query("DELETE FROM trashed_steps WHERE id = ?")
        .bind(id)
        .execute(&mut *tx)
        .await?;
    crate::media::bump_parent_after_trash_change(&mut tx, owner_id, Some(project_id), None).await?;
    let label = step_label(&mut tx, project_id, &title).await?;
    audit::record_lifecycle(
        &mut tx,
        Some(actor_id),
        owner_id,
        Entity::Project,
        project_id,
        Action::StepRestored,
        &label,
        None,
    )
    .await?;
    tx.commit().await?;
    Ok(())
}

// --- media -------------------------------------------------------------------

async fn restore_media(
    state: &AppState,
    actor_id: i64,
    kind: TrashKind,
    public_id: i64,
) -> Result<(), TrashError> {
    let mut tx = crate::db::begin_write(&state.pool).await?;
    let media = crate::media::find_trashed_media(&mut tx, kind.namespace(), public_id)
        .await?
        .ok_or(TrashError::NotFound)?;
    if !may_manage(&state.pool, actor_id, media.owner_id).await? {
        return Err(TrashError::NotFound);
    }
    crate::media::restore_trashed_media_row(&mut tx, media.id, true).await?;
    crate::media::bump_parent_after_trash_change(
        &mut tx,
        media.owner_id,
        media.project_id,
        media.yarn_id,
    )
    .await?;
    let (entity, parent_id, parent_name) = media_parent(&mut tx, &media).await?;
    audit::record_lifecycle(
        &mut tx,
        Some(actor_id),
        media.owner_id,
        entity,
        parent_id,
        Action::Restored,
        &parent_name,
        Some(&media.audit_ref()),
    )
    .await?;
    tx.commit().await?;
    Ok(())
}

// --- categories ----------------------------------------------------------------

/// Move a category to the trash (called by the category delete, before the row
/// is removed). The projects that used it are remembered.
pub(crate) async fn trash_category(
    tx: &mut Transaction<'_, Sqlite>,
    actor_id: i64,
    owner_id: i64,
    category_id: i64,
    name: &str,
) -> Result<(), sqlx::Error> {
    let projects = sqlx::query_scalar::<_, i64>(
        "SELECT id FROM projects WHERE owner_id = ? AND category_id = ? ORDER BY id",
    )
    .bind(owner_id)
    .bind(category_id)
    .fetch_all(&mut **tx)
    .await?;
    sqlx::query(
        "INSERT INTO trashed_categories (id, user_id, name, project_ids, deleted_at, deleted_by) \
         VALUES (?, ?, ?, ?, ?, ?)",
    )
    .bind(category_id)
    .bind(owner_id)
    .bind(name)
    .bind(serde_json::to_string(&projects).unwrap_or_else(|_| "[]".to_owned()))
    .bind(now_stamp())
    .bind(actor_id)
    .execute(&mut **tx)
    .await?;
    audit::record_lifecycle(
        tx,
        Some(actor_id),
        owner_id,
        Entity::Category,
        category_id,
        Action::Trashed,
        name,
        None,
    )
    .await
}

async fn restore_category(state: &AppState, actor_id: i64, id: i64) -> Result<(), TrashError> {
    let (owner_id, name, project_ids) = sqlx::query_as::<_, (i64, String, String)>(
        "SELECT user_id, name, project_ids FROM trashed_categories WHERE id = ?",
    )
    .bind(id)
    .fetch_optional(&state.pool)
    .await?
    .ok_or(TrashError::NotFound)?;
    if !may_manage(&state.pool, actor_id, owner_id).await? {
        return Err(TrashError::NotFound);
    }
    let mut tx = crate::db::begin_write(&state.pool).await?;
    let taken = sqlx::query_scalar::<_, bool>(
        "SELECT EXISTS(SELECT 1 FROM categories WHERE user_id = ? AND name = ? COLLATE NOCASE)",
    )
    .bind(owner_id)
    .bind(&name)
    .fetch_one(&mut *tx)
    .await?;
    if taken {
        return Err(TrashError::NameTaken);
    }
    // The category's sync metadata and clock follow from the insert trigger;
    // the old deletion event would make a full sync list it as deleted.
    sqlx::query("INSERT INTO categories (id, user_id, name) VALUES (?, ?, ?)")
        .bind(id)
        .bind(owner_id)
        .bind(&name)
        .execute(&mut *tx)
        .await?;
    sqlx::query("DELETE FROM category_deletion_events WHERE owner_id = ? AND category_id = ?")
        .bind(owner_id)
        .bind(id)
        .execute(&mut *tx)
        .await?;
    let projects: Vec<i64> = serde_json::from_str(&project_ids).unwrap_or_default();
    for project in projects {
        sqlx::query(
            "UPDATE projects SET category_id = ? \
             WHERE id = ? AND owner_id = ? AND category_id IS NULL",
        )
        .bind(id)
        .bind(project)
        .bind(owner_id)
        .execute(&mut *tx)
        .await?;
    }
    crate::categories::bump_projects_in(&mut tx, owner_id, id).await?;
    sqlx::query("DELETE FROM trashed_categories WHERE id = ?")
        .bind(id)
        .execute(&mut *tx)
        .await?;
    audit::record_lifecycle(
        &mut tx,
        Some(actor_id),
        owner_id,
        Entity::Category,
        id,
        Action::Restored,
        &name,
        None,
    )
    .await?;
    tx.commit().await?;
    Ok(())
}

// --- retention -----------------------------------------------------------------

/// Destroy every item that has been in the trash for longer than the
/// retention period. Returns how many were destroyed; a full batch means more
/// may be due.
pub async fn purge_expired(pool: &SqlitePool, now: DateTime<Utc>) -> Result<usize, sqlx::Error> {
    purge_expired_before(pool, now - Duration::days(RETENTION_DAYS)).await
}

/// [`purge_expired`] with an explicit cutoff; tests use it to age items.
#[doc(hidden)]
pub async fn purge_expired_before(
    pool: &SqlitePool,
    cutoff: DateTime<Utc>,
) -> Result<usize, sqlx::Error> {
    let cutoff = stamp(cutoff);
    let mut purged = 0;
    // Parents first: their children go with them.
    for (kind, select) in [
        (
            TrashKind::Project,
            "SELECT id FROM projects WHERE deleted_at IS NOT NULL AND deleted_at < ?1 \
             ORDER BY deleted_at LIMIT ?2",
        ),
        (
            TrashKind::Yarn,
            "SELECT id FROM yarns WHERE deleted_at IS NOT NULL AND deleted_at < ?1 \
             ORDER BY deleted_at LIMIT ?2",
        ),
        (
            TrashKind::Category,
            "SELECT id FROM trashed_categories WHERE deleted_at < ?1 ORDER BY deleted_at LIMIT ?2",
        ),
        (
            TrashKind::Step,
            "SELECT id FROM trashed_steps WHERE deleted_at < ?1 ORDER BY deleted_at LIMIT ?2",
        ),
        (
            TrashKind::Image,
            "SELECT public_id FROM trashed_media WHERE with_step = 0 AND deleted_at < ?1 \
             AND kind IN ('title','step') ORDER BY deleted_at LIMIT ?2",
        ),
        (
            TrashKind::Attachment,
            "SELECT public_id FROM trashed_media WHERE with_step = 0 AND deleted_at < ?1 \
             AND kind = 'attachment' ORDER BY deleted_at LIMIT ?2",
        ),
        (
            TrashKind::Photo,
            "SELECT public_id FROM trashed_media WHERE with_step = 0 AND deleted_at < ?1 \
             AND kind = 'photo' ORDER BY deleted_at LIMIT ?2",
        ),
    ] {
        let ids = sqlx::query_scalar::<_, i64>(select)
            .bind(&cutoff)
            .bind(PURGE_BATCH)
            .fetch_all(pool)
            .await?;
        for id in ids {
            let mut tx = crate::db::begin_write(pool).await?;
            if purge_in(&mut tx, kind, id, None).await? {
                tx.commit().await?;
                purged += 1;
            } else {
                tx.rollback().await?;
            }
        }
    }
    Ok(purged)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn days_remaining_rounds_up_and_stops_at_zero() {
        let now = Utc::now();
        assert_eq!(days_remaining(now, now), RETENTION_DAYS);
        assert_eq!(
            days_remaining(now - Duration::days(89) - Duration::hours(1), now),
            1
        );
        assert_eq!(days_remaining(now - Duration::days(RETENTION_DAYS), now), 0);
        assert_eq!(days_remaining(now - Duration::days(200), now), 0);
    }
}
