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

/// The owner of a project when `actor_id` may act on it (owner or active admin).
pub(crate) async fn project_owner(
    pool: &SqlitePool,
    actor_id: i64,
    project_id: i64,
) -> Result<Option<i64>, sqlx::Error> {
    sqlx::query_scalar::<_, i64>(
        "SELECT p.owner_id FROM projects p WHERE p.id = ? AND (p.owner_id = ? OR EXISTS( \
         SELECT 1 FROM users a WHERE a.id = ? AND a.is_admin = 1 AND a.active = 1))",
    )
    .bind(project_id)
    .bind(actor_id)
    .bind(actor_id)
    .fetch_optional(pool)
    .await
}

/// The owner of a yarn when `actor_id` may act on it (owner or active admin).
pub(crate) async fn yarn_owner(
    pool: &SqlitePool,
    actor_id: i64,
    yarn_id: i64,
) -> Result<Option<i64>, sqlx::Error> {
    sqlx::query_scalar::<_, i64>(
        "SELECT y.owner_id FROM yarns y WHERE y.id = ? AND (y.owner_id = ? OR EXISTS( \
         SELECT 1 FROM users a WHERE a.id = ? AND a.is_admin = 1 AND a.active = 1))",
    )
    .bind(yarn_id)
    .bind(actor_id)
    .bind(actor_id)
    .fetch_optional(pool)
    .await
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
    Ok(row.map(|row| {
        let email: String = row.get("email");
        let use_gravatar = row.get::<i64, _>("use_gravatar") != 0;
        let filename: Option<String> = row.get("avatar_filename");
        let avatar = if actor_id == owner_id {
            crate::media::self_avatar(
                owner_id,
                filename.as_deref(),
                use_gravatar,
                gravatar_enabled,
            )
        } else {
            crate::media::admin_avatar(
                owner_id,
                filename.as_deref(),
                use_gravatar,
                gravatar_enabled,
            )
        };
        OwnerInfo {
            id: owner_id,
            name: display_name(&email),
            email,
            avatar_url: avatar.map(|(url, _)| url),
        }
    }))
}

fn display_name(email: &str) -> String {
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
