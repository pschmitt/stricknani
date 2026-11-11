//! Central owner/administrator access rules for projects and yarns.
//!
//! Owners keep their rights. An active administrator may act on any user's
//! project or yarn; the resource keeps its owner, new media land in the owner's
//! storage and audit events record the administrator as the actor.

use serde::Serialize;
use sqlx::{Row, SqlitePool};

/// Which resources a list or sync request covers.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Default, Serialize, serde::Deserialize)]
#[serde(rename_all = "lowercase")]
pub(crate) enum Scope {
    #[default]
    Mine,
    All,
}

impl Scope {
    pub(crate) fn parse(value: &str) -> Option<Self> {
        match value {
            "mine" => Some(Self::Mine),
            "all" => Some(Self::All),
            _ => None,
        }
    }
}

/// Owner filter of a v2 list request. It only narrows what the caller already
/// sees, so it can never reveal an owner the caller has no access to.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum OwnerFilter {
    /// The caller's own items.
    Mine,
    /// Items owned by somebody else (shared with the caller, or an administrator's view).
    Shared,
    /// One owner's items.
    User(i64),
}

impl OwnerFilter {
    /// `Ok(None)` is no filter (absent, empty or `all`); `Err` is an unknown value.
    pub(crate) fn parse(value: Option<&str>) -> Result<Option<Self>, ()> {
        match value.map(str::trim) {
            None | Some("" | "all") => Ok(None),
            Some("mine" | "me") => Ok(Some(Self::Mine)),
            Some("shared") => Ok(Some(Self::Shared)),
            Some(other) => other
                .parse::<i64>()
                .ok()
                .filter(|id| *id > 0)
                .map(|id| Some(Self::User(id)))
                .ok_or(()),
        }
    }

    /// Binds for `(?eq IS NULL OR owner_id = ?eq) AND (?ne IS NULL OR owner_id != ?ne)`.
    pub(crate) fn binds(filter: Option<Self>, actor_id: i64) -> (Option<i64>, Option<i64>) {
        match filter {
            None => (None, None),
            Some(Self::Mine) => (Some(actor_id), None),
            Some(Self::Shared) => (None, Some(actor_id)),
            Some(Self::User(id)) => (Some(id), None),
        }
    }
}

/// The owners, other than the caller, of the items the caller sees in `scope`:
/// the options of the owner filter. Share recipients get names and avatars
/// only, never emails (see [`owner_info`]).
pub(crate) async fn visible_owners(
    pool: &SqlitePool,
    actor_id: i64,
    kind: ResourceKind,
    scope: Scope,
    gravatar_enabled: bool,
) -> Result<Vec<OwnerInfo>, sqlx::Error> {
    let table = kind.table();
    let sql = match scope {
        Scope::All => format!(
            "SELECT DISTINCT t.owner_id FROM {table} t \
             WHERE t.owner_id != ?1 AND t.deleted_at IS NULL"
        ),
        Scope::Mine => format!(
            "SELECT DISTINCT t.owner_id FROM {table} t WHERE t.owner_id != ?1 AND {}",
            visible_sql(kind, "t", 1)
        ),
    };
    let ids: Vec<i64> = sqlx::query_scalar(sqlx::AssertSqlSafe(sql.as_str()))
        .bind(actor_id)
        .fetch_all(pool)
        .await?;
    let mut owners = Vec::with_capacity(ids.len());
    for id in ids {
        if let Some(owner) = owner_info(pool, actor_id, id, gravatar_enabled).await? {
            owners.push(owner);
        }
    }
    owners.sort_by(|a, b| {
        a.name
            .to_lowercase()
            .cmp(&b.name.to_lowercase())
            .then(a.id.cmp(&b.id))
    });
    Ok(owners)
}

/// Owner summary attached to every project and yarn resource.
#[derive(Clone, Debug, Serialize, PartialEq, Eq)]
pub struct OwnerInfo {
    pub id: i64,
    pub email: String,
    pub name: String,
    pub avatar_url: Option<String>,
}

/// Whether `user_id` is an active administrator.
pub(crate) async fn is_active_admin(pool: &SqlitePool, user_id: i64) -> Result<bool, sqlx::Error> {
    sqlx::query_scalar::<_, bool>(
        "SELECT EXISTS(SELECT 1 FROM users WHERE id = ? AND is_admin = 1 AND active = 1)",
    )
    .bind(user_id)
    .fetch_one(pool)
    .await
}

/// Resolve the effective scope: only administrators may widen to `All`.
/// `requested = None` defaults to `All` for administrators.
pub(crate) async fn effective_scope(
    pool: &SqlitePool,
    user_id: i64,
    requested: Option<Scope>,
) -> Result<Scope, sqlx::Error> {
    if is_active_admin(pool, user_id).await? {
        Ok(requested.unwrap_or(Scope::All))
    } else {
        Ok(Scope::Mine)
    }
}

/// What a user may do with one project or yarn.
///
/// `Viewer` also covers the derived, always read-only access to the yarns of a
/// project that was shared with the user.
#[derive(Clone, Copy, Debug, PartialEq, Eq, PartialOrd, Ord)]
pub(crate) enum Access {
    Viewer,
    Editor,
    Owner,
    Admin,
}

impl Access {
    pub(crate) const fn as_str(self) -> &'static str {
        match self {
            Self::Viewer => "viewer",
            Self::Editor => "editor",
            Self::Owner => "owner",
            Self::Admin => "admin",
        }
    }

    /// Change the resource's content and media.
    pub(crate) const fn can_edit(self) -> bool {
        !matches!(self, Self::Viewer)
    }

    /// Share, change roles, revoke, transfer or delete.
    pub(crate) const fn can_manage(self) -> bool {
        matches!(self, Self::Owner | Self::Admin)
    }
}

/// What a route needs from the caller's access.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum Need {
    /// Any access: see the resource and its media and history.
    Read,
    /// Change content and media.
    Edit,
    /// Share, transfer, delete.
    Manage,
}

impl Need {
    pub(crate) const fn allows(self, access: Access) -> bool {
        match self {
            Self::Read => true,
            Self::Edit => access.can_edit(),
            Self::Manage => access.can_manage(),
        }
    }
}

/// Why a [`require`] check failed.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum Denied {
    /// The resource does not exist or the caller cannot see it.
    NotFound,
    /// The caller can see the resource but may not do this to it.
    Forbidden,
    Database,
}

/// [`grant`] plus the access level a route needs. Missing access reads as
/// absence, insufficient access as `Forbidden`.
pub(crate) async fn require(
    pool: &SqlitePool,
    actor_id: i64,
    kind: ResourceKind,
    resource_id: i64,
    surface: Surface,
    need: Need,
) -> Result<Grant, Denied> {
    let grant = grant(pool, actor_id, kind, resource_id, surface)
        .await
        .map_err(|_| Denied::Database)?
        .ok_or(Denied::NotFound)?;
    if need.allows(grant.access) {
        Ok(grant)
    } else {
        Err(Denied::Forbidden)
    }
}

/// The resource kinds that can be shared.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, serde::Deserialize)]
#[serde(rename_all = "snake_case")]
pub(crate) enum ResourceKind {
    Project,
    Yarn,
}

impl ResourceKind {
    pub(crate) const fn as_str(self) -> &'static str {
        match self {
            Self::Project => "project",
            Self::Yarn => "yarn",
        }
    }

    pub(crate) const fn table(self) -> &'static str {
        match self {
            Self::Project => "projects",
            Self::Yarn => "yarns",
        }
    }
}

/// Which API generation asks. API v1 predates sharing and keeps its
/// owner/administrator-only rules, so shares never widen what it can see.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum Surface {
    V1,
    V2,
}

/// The effective access of a user to one resource and the resource's owner.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) struct Grant {
    pub(crate) owner_id: i64,
    pub(crate) access: Access,
    /// The access comes only from a share of a project linked to this yarn.
    pub(crate) derived: bool,
}

/// Role information attached to v2 list, detail and sync responses.
#[derive(Clone, Copy, Debug, Serialize, PartialEq, Eq)]
pub struct AccessInfo {
    pub role: &'static str,
    pub derived: bool,
    pub can_edit: bool,
    pub can_manage: bool,
}

impl AccessInfo {
    /// The creator of a resource owns it.
    pub const OWNER: Self = Self {
        role: "owner",
        derived: false,
        can_edit: true,
        can_manage: true,
    };
}

impl AccessInfo {
    /// A trashed project or yarn opens read-only: nothing to edit, share or
    /// time; the page offers restore and delete forever instead.
    pub const fn read_only_trashed(role: &'static str) -> Self {
        Self {
            role,
            derived: false,
            can_edit: false,
            can_manage: false,
        }
    }
}

impl From<Grant> for AccessInfo {
    fn from(grant: Grant) -> Self {
        Self {
            role: grant.access.as_str(),
            derived: grant.derived,
            can_edit: grant.access.can_edit(),
            can_manage: grant.access.can_manage(),
        }
    }
}

/// The caller's role for one list row, from columns the list query selected.
/// `admin` is the caller's administrator state, `derived` that the row is a
/// yarn visible only through a shared project.
pub(crate) fn list_access(
    actor_id: i64,
    owner_id: i64,
    admin: bool,
    share_role: Option<&str>,
    derived: bool,
) -> AccessInfo {
    let grant = if owner_id == actor_id {
        Grant {
            owner_id,
            access: Access::Owner,
            derived: false,
        }
    } else if admin {
        Grant {
            owner_id,
            access: Access::Admin,
            derived: false,
        }
    } else {
        let access = match share_role {
            Some("editor") => Access::Editor,
            _ => Access::Viewer,
        };
        Grant {
            owner_id,
            access,
            derived: share_role.is_none() && derived,
        }
    };
    grant.into()
}

/// SQL predicate: `alias` rows the user bound to `?{param}` can see through
/// ownership, a direct share or (yarns only) a shared linked project.
pub(crate) fn visible_sql(kind: ResourceKind, alias: &str, param: usize) -> String {
    let direct = format!(
        "EXISTS(SELECT 1 FROM resource_shares s WHERE s.kind = '{kind}' \
         AND s.resource_id = {alias}.id AND s.user_id = ?{param})",
        kind = kind.as_str()
    );
    // Trashed rows are visible to nobody until they are restored.
    match kind {
        ResourceKind::Project => {
            format!("({alias}.deleted_at IS NULL AND ({alias}.owner_id = ?{param} OR {direct}))")
        }
        ResourceKind::Yarn => format!(
            "({alias}.deleted_at IS NULL AND ({alias}.owner_id = ?{param} OR {direct} OR {derived}))",
            derived = derived_yarn_sql(alias, param)
        ),
    }
}

/// SQL predicate: the yarn is linked to a project the user owns or has been
/// given a share of. This is the derived read-only access.
pub(crate) fn derived_yarn_sql(alias: &str, param: usize) -> String {
    format!(
        "EXISTS(SELECT 1 FROM project_yarns py JOIN projects dp ON dp.id = py.project_id \
         WHERE py.yarn_id = {alias}.id AND dp.deleted_at IS NULL \
         AND (dp.owner_id = ?{param} OR EXISTS( \
         SELECT 1 FROM resource_shares ds WHERE ds.kind = 'project' \
         AND ds.resource_id = dp.id AND ds.user_id = ?{param})))"
    )
}

/// The single permission function: what `actor_id` may do with a resource, or
/// `None` when it must look like it does not exist.
///
/// Owners keep full rights, active administrators act like owners, and (on
/// API v2 only) shares grant `viewer` or `editor`. Yarns of a shared project
/// are readable by the recipient for as long as the share and the link exist;
/// that access is never writable and does not extend to other projects.
pub(crate) async fn grant(
    pool: &SqlitePool,
    actor_id: i64,
    kind: ResourceKind,
    resource_id: i64,
    surface: Surface,
) -> Result<Option<Grant>, sqlx::Error> {
    grant_in(pool, actor_id, kind, resource_id, surface, false).await
}

/// [`grant`] for the trash: also resolves rows that were moved to the trash.
/// Only the owner and administrators get a grant on a trashed row; shares
/// and derived access are suspended while it is trashed.
pub(crate) async fn grant_including_trashed(
    pool: &SqlitePool,
    actor_id: i64,
    kind: ResourceKind,
    resource_id: i64,
) -> Result<Option<Grant>, sqlx::Error> {
    grant_in(pool, actor_id, kind, resource_id, Surface::V2, true).await
}

async fn grant_in(
    pool: &SqlitePool,
    actor_id: i64,
    kind: ResourceKind,
    resource_id: i64,
    surface: Surface,
    include_trashed: bool,
) -> Result<Option<Grant>, sqlx::Error> {
    let derived = match kind {
        ResourceKind::Project => "0".to_owned(),
        ResourceKind::Yarn => derived_yarn_sql("r", 1),
    };
    let sql = format!(
        "SELECT r.owner_id, \
                EXISTS(SELECT 1 FROM users a WHERE a.id = ?1 AND a.is_admin = 1 AND a.active = 1), \
                EXISTS(SELECT 1 FROM users a WHERE a.id = ?1 AND a.active = 1), \
                (SELECT s.role FROM resource_shares s WHERE s.kind = '{kind}' \
                    AND s.resource_id = r.id AND s.user_id = ?1), \
                {derived} \
         FROM {table} r WHERE r.id = ?2{trashed}",
        kind = kind.as_str(),
        table = kind.table(),
        trashed = if include_trashed {
            ""
        } else {
            " AND r.deleted_at IS NULL"
        },
    );
    let row = sqlx::query_as::<_, (i64, bool, bool, Option<String>, bool)>(sqlx::AssertSqlSafe(
        sql.as_str(),
    ))
    .bind(actor_id)
    .bind(resource_id)
    .fetch_optional(pool)
    .await?;
    let Some((owner_id, admin, active, role, derived)) = row else {
        return Ok(None);
    };
    if include_trashed && owner_id != actor_id && !admin {
        return Ok(None);
    }
    if owner_id == actor_id {
        return Ok(Some(Grant {
            owner_id,
            access: Access::Owner,
            derived: false,
        }));
    }
    if admin {
        return Ok(Some(Grant {
            owner_id,
            access: Access::Admin,
            derived: false,
        }));
    }
    if surface == Surface::V1 || !active {
        return Ok(None);
    }
    let direct = match role.as_deref() {
        Some("editor") => Some(Access::Editor),
        Some("viewer") => Some(Access::Viewer),
        _ => None,
    };
    Ok(match (direct, derived) {
        (Some(access), _) => Some(Grant {
            owner_id,
            access,
            derived: false,
        }),
        (None, true) => Some(Grant {
            owner_id,
            access: Access::Viewer,
            derived: true,
        }),
        (None, false) => None,
    })
}

/// Owner summary for display; the avatar path is the self path for the actor
/// and the administrator path for anyone else.
pub(crate) async fn owner_info(
    pool: &SqlitePool,
    actor_id: i64,
    owner_id: i64,
    gravatar_enabled: bool,
) -> Result<Option<OwnerInfo>, sqlx::Error> {
    let row = sqlx::query(
        "SELECT u.email, u.use_gravatar, a.filename AS avatar_filename FROM users u \
         LEFT JOIN user_avatars a ON a.user_id = u.id WHERE u.id = ?",
    )
    .bind(owner_id)
    .fetch_optional(pool)
    .await?;
    let admin = actor_id != owner_id && is_active_admin(pool, actor_id).await?;
    Ok(row.map(|row| {
        let email: String = row.get("email");
        let name_source = email.clone();
        let use_gravatar = row.get::<i64, _>("use_gravatar") != 0;
        let filename: Option<String> = row.get("avatar_filename");
        let avatar = if actor_id == owner_id {
            crate::media::self_avatar(
                owner_id,
                filename.as_deref(),
                use_gravatar,
                gravatar_enabled,
            )
        } else if admin {
            crate::media::admin_avatar(
                owner_id,
                filename.as_deref(),
                use_gravatar,
                gravatar_enabled,
            )
        } else {
            crate::media::people_avatar(
                owner_id,
                filename.as_deref(),
                use_gravatar,
                gravatar_enabled,
            )
        };
        // Share recipients see who owns the resource, never the owner's email.
        let email = if actor_id == owner_id || admin {
            email
        } else {
            String::new()
        };
        OwnerInfo {
            id: owner_id,
            name: display_name(&name_source),
            email,
            avatar_url: avatar.map(|(url, _)| url),
        }
    }))
}

pub(crate) fn display_name(email: &str) -> String {
    let local = email.split('@').next().unwrap_or(email);
    let mut chars = local.chars();
    match chars.next() {
        Some(first) => first.to_uppercase().chain(chars).collect(),
        None => email.to_owned(),
    }
}

#[cfg(test)]
mod tests {
    use super::display_name;

    #[test]
    fn display_name_capitalises_local_part() {
        assert_eq!(display_name("anika@example.org"), "Anika");
        assert_eq!(display_name("x"), "X");
    }
}
