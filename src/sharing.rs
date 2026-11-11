//! Sharing and ownership transfer for projects and yarns.
//!
//! Permissions live in [`crate::resource_access::grant`]. This module owns the
//! `resource_shares` writes, the sync side effects of access changes
//! (tombstones for users that lost access, access events for users that gained
//! it), audit events and ownership transfer.
//!
//! Sync model: a user's delta carries every resource they can currently see.
//! Because access can change without the resource's `updated_at` moving, every
//! access change goes through [`watch`] and [`settle`]: the set of
//! (user, resource) pairs that may be affected is read before the change and
//! compared with the state afterwards. Pairs that left the user's visible set
//! get a tombstone addressed to that user, pairs that entered it get an access
//! event that sync merges into the delta.

use std::collections::BTreeSet;

use serde::Serialize;
use sqlx::{Row, SqliteConnection, SqlitePool};

use crate::audit::{Action, Entity, SharingSubject, record_sharing};
use crate::db::AppState;
use crate::resource_access::{Grant, ResourceKind, Surface, display_name, grant, visible_sql};

/// The current time in the microsecond format of every other sync timestamp.
/// Taken in Rust: SQLite's clock has millisecond resolution, which could place
/// an event just before the snapshot of a sync that did not see it.
fn now() -> String {
    chrono::Utc::now().to_rfc3339_opts(chrono::SecondsFormat::Micros, true)
}

pub(crate) const MAX_PEOPLE_PAGE: i64 = 20;

/// One (user, resource) pair whose visibility may change.
pub(crate) type Candidate = (i64, ResourceKind, i64);

#[derive(Debug)]
pub(crate) enum ShareError {
    NotFound,
    /// Visible to the caller, but read-only or not manageable.
    Forbidden,
    Invalid(&'static str),
    Conflict(&'static str),
    Database,
}

impl From<sqlx::Error> for ShareError {
    fn from(error: sqlx::Error) -> Self {
        match &error {
            sqlx::Error::Database(db) if db.is_unique_violation() => {
                Self::Conflict("A resource with the same name already exists for that account")
            }
            _ => Self::Database,
        }
    }
}

// --- visibility bookkeeping -------------------------------------------------

/// Whether `user_id` can see the resource at all (as owner, recipient or
/// through a shared project).
pub(crate) async fn is_visible(
    conn: &mut SqliteConnection,
    user_id: i64,
    kind: ResourceKind,
    resource_id: i64,
) -> Result<bool, sqlx::Error> {
    let sql = format!(
        "SELECT EXISTS(SELECT 1 FROM {table} r WHERE r.id = ?2 AND {visible})",
        table = kind.table(),
        visible = visible_sql(kind, "r", 1)
    );
    sqlx::query_scalar::<_, bool>(sqlx::AssertSqlSafe(sql.as_str()))
        .bind(user_id)
        .bind(resource_id)
        .fetch_one(conn)
        .await
}

/// A set of pairs and whether each was visible before a change.
pub(crate) struct Watch(Vec<(Candidate, bool)>);

/// Record the current visibility of every candidate pair.
pub(crate) async fn watch(
    conn: &mut SqliteConnection,
    candidates: impl IntoIterator<Item = Candidate>,
) -> Result<Watch, sqlx::Error> {
    let unique: BTreeSet<(i64, &'static str, i64)> = candidates
        .into_iter()
        .map(|(user, kind, id)| (user, kind.as_str(), id))
        .collect();
    let mut seen = Vec::with_capacity(unique.len());
    for (user, kind, id) in unique {
        let kind = parse_kind(kind);
        let before = is_visible(conn, user, kind, id).await?;
        seen.push(((user, kind, id), before));
    }
    Ok(Watch(seen))
}

/// Compare with the state recorded by [`watch`]: tombstone what left, mark
/// what entered. `touch` also marks pairs that stayed visible (role or owner
/// changes that clients need to see).
pub(crate) async fn settle(
    conn: &mut SqliteConnection,
    watched: Watch,
    touch: bool,
) -> Result<(), sqlx::Error> {
    for ((user, kind, id), before) in watched.0 {
        let now = is_visible(conn, user, kind, id).await?;
        match (before, now) {
            (true, false) => tombstone(conn, user, kind, id).await?,
            (false, true) => access_event(conn, user, kind, id).await?,
            (true, true) if touch => access_event(conn, user, kind, id).await?,
            _ => {}
        }
    }
    Ok(())
}

fn parse_kind(value: &str) -> ResourceKind {
    if value == "project" {
        ResourceKind::Project
    } else {
        ResourceKind::Yarn
    }
}

async fn tombstone(
    conn: &mut SqliteConnection,
    user_id: i64,
    kind: ResourceKind,
    resource_id: i64,
) -> Result<(), sqlx::Error> {
    sqlx::query(
        "INSERT INTO deletion_events (owner_id, entity_type, entity_id, changed_at, shared) \
         VALUES (?, ?, ?, ?, 1)",
    )
    .bind(user_id)
    .bind(kind.as_str())
    .bind(resource_id)
    .bind(now())
    .execute(&mut *conn)
    .await?;
    sqlx::query(
        "DELETE FROM access_events WHERE user_id = ? AND entity_type = ? AND entity_id = ?",
    )
    .bind(user_id)
    .bind(kind.as_str())
    .bind(resource_id)
    .execute(conn)
    .await?;
    Ok(())
}

async fn access_event(
    conn: &mut SqliteConnection,
    user_id: i64,
    kind: ResourceKind,
    resource_id: i64,
) -> Result<(), sqlx::Error> {
    // Access is back (re-share, or a trashed item restored): an older
    // "access ended" tombstone would make a full sync list the item as deleted.
    sqlx::query(
        "DELETE FROM deletion_events \
         WHERE owner_id = ? AND entity_type = ? AND entity_id = ? AND shared = 1",
    )
    .bind(user_id)
    .bind(kind.as_str())
    .bind(resource_id)
    .execute(&mut *conn)
    .await?;
    sqlx::query(
        "INSERT INTO access_events (user_id, entity_type, entity_id, changed_at) \
         VALUES (?, ?, ?, ?) \
         ON CONFLICT(user_id, entity_type, entity_id) \
         DO UPDATE SET changed_at = excluded.changed_at",
    )
    .bind(user_id)
    .bind(kind.as_str())
    .bind(resource_id)
    .bind(now())
    .execute(conn)
    .await?;
    Ok(())
}

async fn direct_recipients(
    conn: &mut SqliteConnection,
    kind: ResourceKind,
    resource_id: i64,
) -> Result<Vec<i64>, sqlx::Error> {
    sqlx::query_scalar::<_, i64>(
        "SELECT user_id FROM resource_shares WHERE kind = ? AND resource_id = ? ORDER BY user_id",
    )
    .bind(kind.as_str())
    .bind(resource_id)
    .fetch_all(conn)
    .await
}

pub(crate) async fn linked_yarns(
    conn: &mut SqliteConnection,
    project_id: i64,
) -> Result<Vec<i64>, sqlx::Error> {
    sqlx::query_scalar::<_, i64>(
        "SELECT yarn_id FROM project_yarns WHERE project_id = ? ORDER BY yarn_id",
    )
    .bind(project_id)
    .fetch_all(conn)
    .await
}

/// Users that may see a project besides its owner, plus the owner for the
/// derived access to foreign yarns.
async fn project_audience(
    conn: &mut SqliteConnection,
    project_id: i64,
    owner_id: i64,
) -> Result<Vec<i64>, sqlx::Error> {
    let mut users = direct_recipients(conn, ResourceKind::Project, project_id).await?;
    users.push(owner_id);
    Ok(users)
}

/// Pairs that change when a project or its yarn links are deleted, unlinked,
/// shared or transferred: the project for its recipients and every linked yarn
/// (plus `extra_yarns`) for the project's whole audience.
pub(crate) async fn project_candidates(
    conn: &mut SqliteConnection,
    project_id: i64,
    owner_id: i64,
    extra_yarns: &[i64],
) -> Result<Vec<Candidate>, sqlx::Error> {
    let audience = project_audience(conn, project_id, owner_id).await?;
    let mut yarns = linked_yarns(conn, project_id).await?;
    yarns.extend_from_slice(extra_yarns);
    let mut candidates = Vec::new();
    for user in audience {
        if user != owner_id {
            candidates.push((user, ResourceKind::Project, project_id));
        }
        for yarn in &yarns {
            candidates.push((user, ResourceKind::Yarn, *yarn));
        }
    }
    Ok(candidates)
}

/// Pairs that change when a yarn is deleted or shared: its direct recipients
/// and everyone who sees it through a project.
pub(crate) async fn yarn_candidates(
    conn: &mut SqliteConnection,
    yarn_id: i64,
    owner_id: i64,
) -> Result<Vec<Candidate>, sqlx::Error> {
    let mut users = direct_recipients(conn, ResourceKind::Yarn, yarn_id).await?;
    let projects = sqlx::query_as::<_, (i64, i64)>(
        "SELECT p.id, p.owner_id FROM project_yarns py \
         JOIN projects p ON p.id = py.project_id WHERE py.yarn_id = ?",
    )
    .bind(yarn_id)
    .fetch_all(&mut *conn)
    .await?;
    for (project_id, project_owner) in projects {
        users.extend(project_audience(conn, project_id, project_owner).await?);
    }
    Ok(users
        .into_iter()
        .filter(|user| *user != owner_id)
        .map(|user| (user, ResourceKind::Yarn, yarn_id))
        .collect())
}

// --- API types --------------------------------------------------------------

/// A person as other accounts see them: never an email address.
#[derive(Clone, Debug, Serialize, PartialEq, Eq)]
pub struct Person {
    pub id: i64,
    pub name: String,
    pub avatar_url: Option<String>,
}

#[derive(Debug, Serialize)]
pub struct ShareEntry {
    pub user: Person,
    pub role: String,
    pub created_at: String,
    pub updated_at: String,
}

#[derive(Debug, Serialize)]
pub struct ShareList {
    pub owner: Person,
    pub shares: Vec<ShareEntry>,
}

#[derive(Debug, Serialize)]
pub struct PeoplePage {
    pub items: Vec<Person>,
    pub page: i64,
    pub per_page: i64,
    pub has_more: bool,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum Role {
    Viewer,
    Editor,
}

impl Role {
    pub(crate) fn parse(value: &str) -> Option<Self> {
        match value {
            "viewer" => Some(Self::Viewer),
            "editor" => Some(Self::Editor),
            _ => None,
        }
    }

    pub(crate) const fn as_str(self) -> &'static str {
        match self {
            Self::Viewer => "viewer",
            Self::Editor => "editor",
        }
    }
}

pub(crate) struct TransferOutcome {
    pub(crate) moved_yarn_ids: Vec<i64>,
}

fn entity(kind: ResourceKind) -> Entity {
    match kind {
        ResourceKind::Project => Entity::Project,
        ResourceKind::Yarn => Entity::Yarn,
    }
}

/// The public view of one account.
pub(crate) async fn person_by_id(
    state: &AppState,
    user_id: i64,
) -> Result<Option<Person>, sqlx::Error> {
    person(&state.pool, state, user_id).await
}

async fn person(
    pool: &SqlitePool,
    state: &AppState,
    user_id: i64,
) -> Result<Option<Person>, sqlx::Error> {
    let row = sqlx::query(
        "SELECT u.email, u.use_gravatar, a.filename AS avatar_filename FROM users u \
         LEFT JOIN user_avatars a ON a.user_id = u.id WHERE u.id = ?",
    )
    .bind(user_id)
    .fetch_optional(pool)
    .await?;
    Ok(row.map(|row| person_from_row(&row, user_id, state.gravatar.enabled)))
}

fn person_from_row(row: &sqlx::sqlite::SqliteRow, user_id: i64, gravatar_enabled: bool) -> Person {
    let email: String = row.get("email");
    let use_gravatar = row.get::<i64, _>("use_gravatar") != 0;
    let filename: Option<String> = row.get("avatar_filename");
    Person {
        id: user_id,
        name: display_name(&email),
        avatar_url: crate::media::people_avatar(
            user_id,
            filename.as_deref(),
            use_gravatar,
            gravatar_enabled,
        )
        .map(|(url, _)| url),
    }
}

// --- access resolution ------------------------------------------------------

/// The caller's grant, or `NotFound` when the resource must look absent.
pub(crate) async fn require_grant(
    pool: &SqlitePool,
    actor_id: i64,
    kind: ResourceKind,
    resource_id: i64,
) -> Result<Grant, ShareError> {
    grant(pool, actor_id, kind, resource_id, Surface::V2)
        .await
        .map_err(|_| ShareError::Database)?
        .ok_or(ShareError::NotFound)
}

/// Only the owner or an active administrator may manage sharing.
pub(crate) async fn require_manager(
    pool: &SqlitePool,
    actor_id: i64,
    kind: ResourceKind,
    resource_id: i64,
) -> Result<Grant, ShareError> {
    let grant = require_grant(pool, actor_id, kind, resource_id).await?;
    if grant.access.can_manage() {
        Ok(grant)
    } else {
        Err(ShareError::Forbidden)
    }
}

// --- share CRUD -------------------------------------------------------------

pub(crate) async fn list_shares(
    state: &AppState,
    actor_id: i64,
    kind: ResourceKind,
    resource_id: i64,
) -> Result<ShareList, ShareError> {
    let grant = require_manager(&state.pool, actor_id, kind, resource_id).await?;
    let owner = person(&state.pool, state, grant.owner_id)
        .await?
        .ok_or(ShareError::NotFound)?;
    let rows = sqlx::query(
        "SELECT s.user_id, s.role, s.created_at, s.updated_at, u.email, u.use_gravatar, \
                a.filename AS avatar_filename \
         FROM resource_shares s JOIN users u ON u.id = s.user_id \
         LEFT JOIN user_avatars a ON a.user_id = u.id \
         WHERE s.kind = ? AND s.resource_id = ? ORDER BY lower(u.email), s.user_id",
    )
    .bind(kind.as_str())
    .bind(resource_id)
    .fetch_all(&state.pool)
    .await?;
    let shares = rows
        .iter()
        .map(|row| ShareEntry {
            user: person_from_row(row, row.get("user_id"), state.gravatar.enabled),
            role: row.get("role"),
            created_at: row.get("created_at"),
            updated_at: row.get("updated_at"),
        })
        .collect();
    Ok(ShareList { owner, shares })
}

async fn recipient_state(
    conn: &mut SqliteConnection,
    user_id: i64,
) -> Result<Option<bool>, sqlx::Error> {
    sqlx::query_scalar::<_, bool>("SELECT active = 1 FROM users WHERE id = ?")
        .bind(user_id)
        .fetch_optional(conn)
        .await
}

/// Share with a new recipient. A repeated request for an existing share is a
/// conflict; use [`change_share`] to change the role.
pub(crate) async fn add_share(
    state: &AppState,
    actor_id: i64,
    kind: ResourceKind,
    resource_id: i64,
    user_id: i64,
    role: Role,
) -> Result<ShareEntry, ShareError> {
    let grant = require_manager(&state.pool, actor_id, kind, resource_id).await?;
    if user_id == actor_id || user_id == grant.owner_id {
        return Err(ShareError::Invalid(
            "A resource cannot be shared with its owner or with yourself",
        ));
    }
    let mut tx = crate::db::begin_write(&state.pool).await?;
    if recipient_state(&mut tx, user_id).await? != Some(true) {
        return Err(ShareError::Invalid("Unknown or disabled account"));
    }
    let exists = sqlx::query_scalar::<_, bool>(
        "SELECT EXISTS(SELECT 1 FROM resource_shares \
         WHERE kind = ? AND resource_id = ? AND user_id = ?)",
    )
    .bind(kind.as_str())
    .bind(resource_id)
    .bind(user_id)
    .fetch_one(&mut *tx)
    .await?;
    if exists {
        return Err(ShareError::Conflict("Already shared with this account"));
    }
    let candidates = share_candidates(&mut tx, kind, resource_id, user_id).await?;
    let watched = watch(&mut tx, candidates).await?;
    let at = now();
    sqlx::query(
        "INSERT INTO resource_shares \
         (kind, resource_id, user_id, role, created_by, created_at, updated_at) \
         VALUES (?, ?, ?, ?, ?, ?, ?)",
    )
    .bind(kind.as_str())
    .bind(resource_id)
    .bind(user_id)
    .bind(role.as_str())
    .bind(actor_id)
    .bind(&at)
    .bind(&at)
    .execute(&mut *tx)
    .await?;
    settle(&mut tx, watched, false).await?;
    record_sharing(
        &mut tx,
        actor_id,
        entity(kind),
        resource_id,
        Action::Shared,
        &SharingSubject {
            user_id,
            role: Some(role.as_str()),
        },
    )
    .await?;
    tx.commit().await?;
    share_entry(state, kind, resource_id, user_id).await
}

async fn share_candidates(
    conn: &mut SqliteConnection,
    kind: ResourceKind,
    resource_id: i64,
    user_id: i64,
) -> Result<Vec<Candidate>, sqlx::Error> {
    let mut candidates = vec![(user_id, kind, resource_id)];
    if kind == ResourceKind::Project {
        for yarn in linked_yarns(conn, resource_id).await? {
            candidates.push((user_id, ResourceKind::Yarn, yarn));
        }
    }
    Ok(candidates)
}

pub(crate) async fn change_share(
    state: &AppState,
    actor_id: i64,
    kind: ResourceKind,
    resource_id: i64,
    user_id: i64,
    role: Role,
) -> Result<ShareEntry, ShareError> {
    require_manager(&state.pool, actor_id, kind, resource_id).await?;
    let mut tx = crate::db::begin_write(&state.pool).await?;
    let current = sqlx::query_scalar::<_, String>(
        "SELECT role FROM resource_shares WHERE kind = ? AND resource_id = ? AND user_id = ?",
    )
    .bind(kind.as_str())
    .bind(resource_id)
    .bind(user_id)
    .fetch_optional(&mut *tx)
    .await?
    .ok_or(ShareError::NotFound)?;
    if current != role.as_str() {
        sqlx::query(
            "UPDATE resource_shares SET role = ?, updated_at = ? \
             WHERE kind = ? AND resource_id = ? AND user_id = ?",
        )
        .bind(role.as_str())
        .bind(now())
        .bind(kind.as_str())
        .bind(resource_id)
        .bind(user_id)
        .execute(&mut *tx)
        .await?;
        // Clients need the new role in their next delta.
        access_event(&mut tx, user_id, kind, resource_id).await?;
        record_sharing(
            &mut tx,
            actor_id,
            entity(kind),
            resource_id,
            Action::ShareRoleChanged,
            &SharingSubject {
                user_id,
                role: Some(role.as_str()),
            },
        )
        .await?;
    }
    tx.commit().await?;
    share_entry(state, kind, resource_id, user_id).await
}

async fn share_entry(
    state: &AppState,
    kind: ResourceKind,
    resource_id: i64,
    user_id: i64,
) -> Result<ShareEntry, ShareError> {
    let row = sqlx::query(
        "SELECT s.role, s.created_at, s.updated_at, u.email, u.use_gravatar, \
                a.filename AS avatar_filename \
         FROM resource_shares s JOIN users u ON u.id = s.user_id \
         LEFT JOIN user_avatars a ON a.user_id = u.id \
         WHERE s.kind = ? AND s.resource_id = ? AND s.user_id = ?",
    )
    .bind(kind.as_str())
    .bind(resource_id)
    .bind(user_id)
    .fetch_optional(&state.pool)
    .await?
    .ok_or(ShareError::NotFound)?;
    Ok(ShareEntry {
        user: person_from_row(&row, user_id, state.gravatar.enabled),
        role: row.get("role"),
        created_at: row.get("created_at"),
        updated_at: row.get("updated_at"),
    })
}

/// Remove a recipient (owner or administrator) or leave (the recipient).
async fn end_share(
    state: &AppState,
    actor_id: i64,
    kind: ResourceKind,
    resource_id: i64,
    user_id: i64,
    action: Action,
) -> Result<(), ShareError> {
    let mut tx = crate::db::begin_write(&state.pool).await?;
    let role = sqlx::query_scalar::<_, String>(
        "SELECT role FROM resource_shares WHERE kind = ? AND resource_id = ? AND user_id = ?",
    )
    .bind(kind.as_str())
    .bind(resource_id)
    .bind(user_id)
    .fetch_optional(&mut *tx)
    .await?
    .ok_or(ShareError::NotFound)?;
    let candidates = share_candidates(&mut tx, kind, resource_id, user_id).await?;
    let watched = watch(&mut tx, candidates).await?;
    sqlx::query("DELETE FROM resource_shares WHERE kind = ? AND resource_id = ? AND user_id = ?")
        .bind(kind.as_str())
        .bind(resource_id)
        .bind(user_id)
        .execute(&mut *tx)
        .await?;
    settle(&mut tx, watched, false).await?;
    record_sharing(
        &mut tx,
        actor_id,
        entity(kind),
        resource_id,
        action,
        &SharingSubject {
            user_id,
            role: Some(role.as_str()),
        },
    )
    .await?;
    tx.commit().await?;
    Ok(())
}

pub(crate) async fn revoke_share(
    state: &AppState,
    actor_id: i64,
    kind: ResourceKind,
    resource_id: i64,
    user_id: i64,
) -> Result<(), ShareError> {
    require_manager(&state.pool, actor_id, kind, resource_id).await?;
    end_share(
        state,
        actor_id,
        kind,
        resource_id,
        user_id,
        Action::ShareRevoked,
    )
    .await
}

/// Any recipient may leave their own share. Derived read access has no share
/// to leave and ends with the project share or the link.
pub(crate) async fn leave_share(
    state: &AppState,
    actor_id: i64,
    kind: ResourceKind,
    resource_id: i64,
) -> Result<(), ShareError> {
    end_share(
        state,
        actor_id,
        kind,
        resource_id,
        actor_id,
        Action::ShareLeft,
    )
    .await
}

// --- people search ----------------------------------------------------------

/// Search active accounts other than the caller by display name. Returns only
/// id, display name and avatar.
pub(crate) async fn search_people(
    state: &AppState,
    actor_id: i64,
    query: Option<&str>,
    page: i64,
) -> Result<PeoplePage, ShareError> {
    let pattern = query
        .map(str::trim)
        .filter(|value| !value.is_empty())
        .map(|value| {
            let escaped: String = value
                .chars()
                .flat_map(|c| match c {
                    '!' | '%' | '_' => vec!['!', c],
                    _ => vec![c],
                })
                .collect();
            format!("%{escaped}%")
        });
    // Only the part before the "@" is searchable: it is the display name.
    let rows = sqlx::query(
        "SELECT u.id, u.email, u.use_gravatar, a.filename AS avatar_filename \
         FROM users u LEFT JOIN user_avatars a ON a.user_id = u.id \
         WHERE u.active = 1 AND u.id <> ?1 \
           AND (?2 IS NULL OR substr(u.email, 1, instr(u.email || '@', '@') - 1) \
                LIKE ?2 ESCAPE '!') \
         ORDER BY lower(u.email), u.id LIMIT ?3 OFFSET ?4",
    )
    .bind(actor_id)
    .bind(pattern)
    .bind(MAX_PEOPLE_PAGE + 1)
    .bind((page - 1).saturating_mul(MAX_PEOPLE_PAGE))
    .fetch_all(&state.pool)
    .await?;
    let has_more = rows.len() as i64 > MAX_PEOPLE_PAGE;
    let items = rows
        .iter()
        .take(MAX_PEOPLE_PAGE as usize)
        .map(|row| person_from_row(row, row.get("id"), state.gravatar.enabled))
        .collect();
    Ok(PeoplePage {
        items,
        page,
        per_page: MAX_PEOPLE_PAGE,
        has_more,
    })
}

// --- transfer ---------------------------------------------------------------

/// Hand a resource to another account. The old owner keeps an editor share so
/// that a mistyped recipient cannot lock them out; any share the new owner
/// already had is removed.
pub(crate) async fn transfer(
    state: &AppState,
    actor_id: i64,
    kind: ResourceKind,
    resource_id: i64,
    new_owner_id: i64,
    move_linked_yarns: bool,
) -> Result<TransferOutcome, ShareError> {
    let grant = require_manager(&state.pool, actor_id, kind, resource_id).await?;
    let old_owner_id = grant.owner_id;
    if new_owner_id == old_owner_id {
        return Err(ShareError::Invalid(
            "The account already owns this resource",
        ));
    }
    if move_linked_yarns && kind == ResourceKind::Yarn {
        return Err(ShareError::Invalid("Only projects have linked yarns"));
    }
    let mut tx = crate::db::begin_write(&state.pool).await?;
    if recipient_state(&mut tx, new_owner_id).await? != Some(true) {
        return Err(ShareError::Invalid("Unknown or disabled account"));
    }
    // Re-check under the write transaction: the owner may have changed.
    let current_owner = sqlx::query_scalar::<_, i64>(sqlx::AssertSqlSafe(
        format!("SELECT owner_id FROM {} WHERE id = ?", kind.table()).as_str(),
    ))
    .bind(resource_id)
    .fetch_optional(&mut *tx)
    .await?
    .ok_or(ShareError::NotFound)?;
    if current_owner != old_owner_id {
        return Err(ShareError::Conflict("The resource changed owner meanwhile"));
    }

    let mut moving = vec![(kind, resource_id)];
    let mut watch_set: Vec<Candidate> = Vec::new();
    match kind {
        ResourceKind::Project => {
            let linked = linked_yarns(&mut tx, resource_id).await?;
            let mut moved_yarns = Vec::new();
            if move_linked_yarns {
                for yarn in &linked {
                    let owner =
                        sqlx::query_scalar::<_, i64>("SELECT owner_id FROM yarns WHERE id = ?")
                            .bind(yarn)
                            .fetch_one(&mut *tx)
                            .await?;
                    if owner == old_owner_id {
                        moved_yarns.push(*yarn);
                    }
                }
            }
            watch_set.extend(project_candidates(&mut tx, resource_id, old_owner_id, &[]).await?);
            for yarn in &moved_yarns {
                moving.push((ResourceKind::Yarn, *yarn));
                watch_set.extend(yarn_candidates(&mut tx, *yarn, old_owner_id).await?);
            }
        }
        ResourceKind::Yarn => {
            watch_set.extend(yarn_candidates(&mut tx, resource_id, old_owner_id).await?);
        }
    }
    for (moving_kind, id) in &moving {
        watch_set.push((old_owner_id, *moving_kind, *id));
        watch_set.push((new_owner_id, *moving_kind, *id));
    }
    let watched = watch(&mut tx, watch_set).await?;

    for (moving_kind, id) in &moving {
        move_resource(
            &mut tx,
            actor_id,
            *moving_kind,
            *id,
            old_owner_id,
            new_owner_id,
        )
        .await?;
    }
    settle(&mut tx, watched, true).await?;
    tx.commit().await?;
    Ok(TransferOutcome {
        moved_yarn_ids: moving
            .into_iter()
            .filter(|(moving_kind, _)| *moving_kind == ResourceKind::Yarn)
            .map(|(_, id)| id)
            .collect(),
    })
}

async fn move_resource(
    tx: &mut SqliteConnection,
    actor_id: i64,
    kind: ResourceKind,
    resource_id: i64,
    old_owner_id: i64,
    new_owner_id: i64,
) -> Result<(), ShareError> {
    if kind == ResourceKind::Project {
        // Categories belong to an account; the project follows its category
        // by name, created for the new owner when missing (names are unique
        // per account, case-insensitively).
        let category = sqlx::query_scalar::<_, String>(
            "SELECT c.name FROM projects p JOIN categories c ON c.id = p.category_id \
             WHERE p.id = ?",
        )
        .bind(resource_id)
        .fetch_optional(&mut *tx)
        .await?;
        let category_id = match category {
            Some(name) => {
                sqlx::query(
                    "INSERT INTO categories (user_id, name) VALUES (?, ?) ON CONFLICT DO NOTHING",
                )
                .bind(new_owner_id)
                .bind(&name)
                .execute(&mut *tx)
                .await?;
                sqlx::query_scalar::<_, i64>(
                    "SELECT id FROM categories WHERE user_id = ? AND name = ? COLLATE NOCASE",
                )
                .bind(new_owner_id)
                .bind(&name)
                .fetch_optional(&mut *tx)
                .await?
            }
            None => None,
        };
        sqlx::query("UPDATE projects SET owner_id = ?, category_id = ? WHERE id = ?")
            .bind(new_owner_id)
            .bind(category_id)
            .bind(resource_id)
            .execute(&mut *tx)
            .await?;
        sqlx::query("UPDATE media_files SET owner_id = ? WHERE project_id = ?")
            .bind(new_owner_id)
            .bind(resource_id)
            .execute(&mut *tx)
            .await?;
    } else {
        sqlx::query("UPDATE yarns SET owner_id = ? WHERE id = ?")
            .bind(new_owner_id)
            .bind(resource_id)
            .execute(&mut *tx)
            .await?;
        sqlx::query("UPDATE media_files SET owner_id = ? WHERE yarn_id = ?")
            .bind(new_owner_id)
            .bind(resource_id)
            .execute(&mut *tx)
            .await?;
    }
    sqlx::query("UPDATE archive_jobs SET owner_id = ? WHERE entity_type = ? AND entity_id = ?")
        .bind(new_owner_id)
        .bind(kind.as_str())
        .bind(resource_id)
        .execute(&mut *tx)
        .await?;
    // The new owner needs no share; the old owner keeps editor access.
    sqlx::query("DELETE FROM resource_shares WHERE kind = ? AND resource_id = ? AND user_id = ?")
        .bind(kind.as_str())
        .bind(resource_id)
        .bind(new_owner_id)
        .execute(&mut *tx)
        .await?;
    let at = now();
    sqlx::query(
        "INSERT INTO resource_shares \
         (kind, resource_id, user_id, role, created_by, created_at, updated_at) \
         VALUES (?, ?, ?, 'editor', ?, ?, ?) \
         ON CONFLICT(kind, resource_id, user_id) DO UPDATE SET role = 'editor', \
             updated_at = excluded.updated_at",
    )
    .bind(kind.as_str())
    .bind(resource_id)
    .bind(old_owner_id)
    .bind(actor_id)
    .bind(&at)
    .bind(&at)
    .execute(&mut *tx)
    .await?;
    record_sharing(
        tx,
        actor_id,
        entity(kind),
        resource_id,
        Action::OwnershipTransferred,
        &SharingSubject {
            user_id: new_owner_id,
            role: None,
        },
    )
    .await?;
    Ok(())
}

/// Before an account and everything it owns is deleted: remember who can see
/// the owned projects and yarns (and the yarns linked to them) so that they get
/// tombstones once the deletion is done. The deleted account itself is left
/// out.
pub(crate) async fn watch_account_deletion(
    conn: &mut SqliteConnection,
    user_id: i64,
) -> Result<Watch, sqlx::Error> {
    let projects = sqlx::query_scalar::<_, i64>("SELECT id FROM projects WHERE owner_id = ?")
        .bind(user_id)
        .fetch_all(&mut *conn)
        .await?;
    let yarns = sqlx::query_scalar::<_, i64>("SELECT id FROM yarns WHERE owner_id = ?")
        .bind(user_id)
        .fetch_all(&mut *conn)
        .await?;
    let mut candidates = Vec::new();
    for project in projects {
        candidates.extend(project_candidates(conn, project, user_id, &[]).await?);
    }
    for yarn in yarns {
        candidates.extend(yarn_candidates(conn, yarn, user_id).await?);
    }
    candidates.retain(|(user, _, _)| *user != user_id);
    watch(conn, candidates).await
}

// --- linkable yarns ---------------------------------------------------------

pub(crate) const CANDIDATE_PAGE_SIZE: i64 = 50;

/// A yarn an editor may link to a project: one of the project owner's. Only
/// what a picker needs; photos are not served before the link exists.
#[derive(Debug, Serialize)]
pub struct LinkableYarn {
    pub id: i64,
    pub name: String,
    pub brand: Option<String>,
    pub colorway: Option<String>,
    pub weight_category: Option<String>,
    pub linked: bool,
}

#[derive(Debug, Serialize)]
pub struct LinkableYarnPage {
    pub items: Vec<LinkableYarn>,
    pub page: i64,
    pub per_page: i64,
    pub has_more: bool,
}

/// The project owner's yarns, for the yarn picker of someone who may edit the
/// project (owner, administrator or editor). Editors cannot otherwise list the
/// owner's stash, and linking their own yarns into the project is not allowed.
pub(crate) async fn linkable_yarns(
    state: &AppState,
    actor_id: i64,
    project_id: i64,
    query: Option<&str>,
    page: i64,
) -> Result<LinkableYarnPage, ShareError> {
    let grant = crate::resource_access::require(
        &state.pool,
        actor_id,
        ResourceKind::Project,
        project_id,
        Surface::V2,
        crate::resource_access::Need::Edit,
    )
    .await
    .map_err(|denied| match denied {
        crate::resource_access::Denied::NotFound => ShareError::NotFound,
        crate::resource_access::Denied::Forbidden => ShareError::Forbidden,
        crate::resource_access::Denied::Database => ShareError::Database,
    })?;
    let pattern = query
        .map(str::trim)
        .filter(|value| !value.is_empty())
        .map(|value| {
            let escaped: String = value
                .chars()
                .flat_map(|c| match c {
                    '!' | '%' | '_' => vec!['!', c],
                    _ => vec![c],
                })
                .collect();
            format!("%{escaped}%")
        });
    let rows = sqlx::query(
        "SELECT y.id, y.name, y.brand, y.colorway, y.weight_category, \
                EXISTS(SELECT 1 FROM project_yarns py \
                       WHERE py.project_id = ?2 AND py.yarn_id = y.id) AS linked \
         FROM yarns y WHERE y.owner_id = ?1 AND y.deleted_at IS NULL \
           AND (?3 IS NULL OR y.name LIKE ?3 ESCAPE '!' \
                OR COALESCE(y.brand, '') LIKE ?3 ESCAPE '!' \
                OR COALESCE(y.colorway, '') LIKE ?3 ESCAPE '!') \
         ORDER BY lower(y.name), y.id LIMIT ?4 OFFSET ?5",
    )
    .bind(grant.owner_id)
    .bind(project_id)
    .bind(pattern)
    .bind(CANDIDATE_PAGE_SIZE + 1)
    .bind((page - 1).saturating_mul(CANDIDATE_PAGE_SIZE))
    .fetch_all(&state.pool)
    .await?;
    let has_more = rows.len() as i64 > CANDIDATE_PAGE_SIZE;
    let items = rows
        .iter()
        .take(CANDIDATE_PAGE_SIZE as usize)
        .map(|row| LinkableYarn {
            id: row.get("id"),
            name: row.get("name"),
            brand: row.get("brand"),
            colorway: row.get("colorway"),
            weight_category: row.get("weight_category"),
            linked: row.get("linked"),
        })
        .collect();
    Ok(LinkableYarnPage {
        items,
        page,
        per_page: CANDIDATE_PAGE_SIZE,
        has_more,
    })
}
