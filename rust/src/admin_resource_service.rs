//! Active-administrator reads of user-owned resources.

use serde::Serialize;
use sqlx::{Row, SqlitePool};

use crate::{
    admin_service::{AdminError, require_active_admin},
    db::AppState,
    projects::{self, ProjectResponse, TagPolicy},
    yarns::{self, YarnResponse},
};

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum ResourceKind {
    Project,
    Yarn,
    Category,
}

impl ResourceKind {
    pub(crate) fn parse(value: &str) -> Option<Self> {
        match value {
            "project" => Some(Self::Project),
            "yarn" => Some(Self::Yarn),
            "category" => Some(Self::Category),
            _ => None,
        }
    }

    pub(crate) const fn as_str(self) -> &'static str {
        match self {
            Self::Project => "project",
            Self::Yarn => "yarn",
            Self::Category => "category",
        }
    }
}

#[derive(Debug)]
pub(crate) enum ResourceError {
    Database,
    Forbidden,
    NotFound,
}

#[derive(Debug, Serialize)]
pub(crate) struct ResourceOwner {
    pub(crate) id: i64,
    pub(crate) email: String,
    pub(crate) avatar_url: Option<String>,
}

#[derive(Debug, Serialize)]
#[serde(untagged)]
pub(crate) enum ResourceData {
    Project(ProjectResponse),
    Yarn(YarnResponse),
    Category(crate::categories::CategoryResponse),
}

#[derive(Debug)]
pub(crate) struct AdminResource {
    pub(crate) id: i64,
    pub(crate) name: String,
    pub(crate) owner: Option<ResourceOwner>,
    pub(crate) resource: ResourceData,
}

struct OwnerRow {
    id: i64,
    email: Option<String>,
    avatar_filename: Option<String>,
}

async fn owner_for_resource(
    pool: &SqlitePool,
    kind: ResourceKind,
    resource_id: i64,
) -> Result<Option<OwnerRow>, sqlx::Error> {
    let row = match kind {
        ResourceKind::Project => {
            sqlx::query(
                "SELECT p.owner_id AS id, u.email, avatar.filename AS avatar_filename \
                 FROM projects AS p LEFT JOIN users AS u ON u.id = p.owner_id \
                 LEFT JOIN user_avatars AS avatar ON avatar.user_id = u.id WHERE p.id = ?",
            )
            .bind(resource_id)
            .fetch_optional(pool)
            .await?
        }
        ResourceKind::Yarn => {
            sqlx::query(
                "SELECT y.owner_id AS id, u.email, avatar.filename AS avatar_filename \
                 FROM yarns AS y LEFT JOIN users AS u ON u.id = y.owner_id \
                 LEFT JOIN user_avatars AS avatar ON avatar.user_id = u.id WHERE y.id = ?",
            )
            .bind(resource_id)
            .fetch_optional(pool)
            .await?
        }
        ResourceKind::Category => {
            sqlx::query(
                "SELECT c.user_id AS id, u.email, avatar.filename AS avatar_filename \
                 FROM categories AS c LEFT JOIN users AS u ON u.id = c.user_id \
                 LEFT JOIN user_avatars AS avatar ON avatar.user_id = u.id WHERE c.id = ?",
            )
            .bind(resource_id)
            .fetch_optional(pool)
            .await?
        }
    };
    Ok(row.map(|row| OwnerRow {
        id: row.get("id"),
        email: row.get("email"),
        avatar_filename: row.get("avatar_filename"),
    }))
}

pub(crate) async fn load(
    state: &AppState,
    actor_id: i64,
    kind: ResourceKind,
    resource_id: i64,
) -> Result<AdminResource, ResourceError> {
    if resource_id <= 0 {
        return Err(ResourceError::NotFound);
    }
    require_active_admin(&state.pool, actor_id)
        .await
        .map_err(|error| match error {
            AdminError::Forbidden => ResourceError::Forbidden,
            _ => ResourceError::Database,
        })?;

    let owner = owner_for_resource(&state.pool, kind, resource_id)
        .await
        .map_err(|_| ResourceError::Database)?
        .ok_or(ResourceError::NotFound)?;
    let owner_id = owner.id;
    let resource = match kind {
        ResourceKind::Project => ResourceData::Project(
            projects::load_project_with_tag_policy(
                state,
                owner_id,
                resource_id,
                TagPolicy::PreserveLabels,
            )
            .await
            .map_err(|_| ResourceError::Database)?
            .ok_or(ResourceError::NotFound)?,
        ),
        ResourceKind::Yarn => ResourceData::Yarn(
            yarns::load_for_owner(state, owner_id, resource_id)
                .await
                .map_err(|_| ResourceError::Database)?
                .ok_or(ResourceError::NotFound)?,
        ),
        ResourceKind::Category => {
            let row = sqlx::query_as::<_, (i64, String)>(
                "SELECT id, name FROM categories WHERE id = ? AND user_id = ?",
            )
            .bind(resource_id)
            .bind(owner_id)
            .fetch_optional(&state.pool)
            .await
            .map_err(|_| ResourceError::Database)?
            .ok_or(ResourceError::NotFound)?;
            ResourceData::Category(crate::categories::CategoryResponse {
                id: row.0,
                name: row.1,
            })
        }
    };
    let name = match &resource {
        ResourceData::Project(project) => project.name.clone(),
        ResourceData::Yarn(yarn) => yarn.name.clone(),
        ResourceData::Category(category) => category.name.clone(),
    };

    Ok(AdminResource {
        id: resource_id,
        name,
        owner: owner.email.map(|email| ResourceOwner {
            id: owner_id,
            email,
            avatar_url: owner
                .avatar_filename
                .as_deref()
                .and_then(|filename| crate::media::admin_avatar_thumbnail_url(owner_id, filename)),
        }),
        resource,
    })
}
