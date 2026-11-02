//! Safe, administrator-scoped reads of audit events for browser adapters.

use serde::Serialize;
use sqlx::{Row, SqlitePool};
use url::form_urlencoded;

use crate::admin_service::{AdminError, require_active_admin};

const PAGE_SIZE: i64 = 50;
const MAX_PAGE: u32 = 10_000;
const MAX_QUERY_BYTES: usize = 512;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum AuditEntity {
    All,
    Project,
    Yarn,
    Category,
}

impl AuditEntity {
    fn parse(value: Option<&str>) -> Option<Self> {
        match value.unwrap_or("all") {
            "all" => Some(Self::All),
            "project" => Some(Self::Project),
            "yarn" => Some(Self::Yarn),
            "category" => Some(Self::Category),
            _ => None,
        }
    }

    pub(crate) const fn value(self) -> &'static str {
        match self {
            Self::All => "all",
            Self::Project => "project",
            Self::Yarn => "yarn",
            Self::Category => "category",
        }
    }

    const fn sql_value(self) -> Option<&'static str> {
        match self {
            Self::All => None,
            Self::Project => Some("project"),
            Self::Yarn => Some("yarn"),
            Self::Category => Some("category"),
        }
    }
}

#[derive(Clone, Debug)]
pub(crate) struct AuditFilter {
    page: Option<u32>,
    entity: AuditEntity,
    action: Option<String>,
}

impl AuditFilter {
    pub(crate) fn parse(raw: Option<&str>) -> Result<Self, AuditError> {
        let Some(raw) = raw else {
            return Ok(Self {
                page: None,
                entity: AuditEntity::All,
                action: None,
            });
        };
        if raw.len() > MAX_QUERY_BYTES {
            return Err(AuditError::InvalidQuery);
        }
        let mut page = None;
        let mut entity = None;
        let mut action = None;
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
        Ok(Self {
            page,
            entity,
            action,
        })
    }

    pub(crate) const fn entity(&self) -> AuditEntity {
        self.entity
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
}

#[derive(Clone, Debug, Serialize)]
pub(crate) struct AuditEvent {
    pub(crate) id: i64,
    pub(crate) actor_email: Option<String>,
    pub(crate) actor_avatar_url: Option<String>,
    pub(crate) legacy_actor_user_id: Option<i64>,
    pub(crate) entity_type: String,
    pub(crate) entity_id: i64,
    pub(crate) action: String,
    pub(crate) created_at: String,
}

pub(crate) struct AuditPage {
    pub(crate) events: Vec<AuditEvent>,
    pub(crate) page: u32,
    pub(crate) page_count: u32,
    pub(crate) per_page: u32,
    pub(crate) has_more: bool,
}

pub(crate) async fn list_events(
    pool: &SqlitePool,
    actor_id: i64,
    filter: &AuditFilter,
) -> Result<AuditPage, AuditError> {
    require_active_admin(pool, actor_id)
        .await
        .map_err(|error| match error {
            AdminError::Forbidden => AuditError::Forbidden,
            _ => AuditError::Database,
        })?;
    let entity = filter.entity.sql_value();
    let action = filter.action();
    let total: i64 = sqlx::query_scalar(
        "SELECT count(*) FROM audit_events \
         WHERE (? IS NULL OR entity_type = ?) AND (? IS NULL OR action = ?)",
    )
    .bind(entity)
    .bind(entity)
    .bind(action)
    .bind(action)
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
    let rows = sqlx::query(
        "SELECT e.id, e.actor_user_id, e.legacy_actor_user_id, \
                u.email AS actor_email, avatar.filename AS actor_avatar_filename, \
                e.entity_type, e.entity_id, e.action, e.created_at \
         FROM audit_events AS e \
         LEFT JOIN users AS u ON u.id = e.actor_user_id \
         LEFT JOIN user_avatars AS avatar ON avatar.user_id = e.actor_user_id \
         WHERE (? IS NULL OR e.entity_type = ?) AND (? IS NULL OR e.action = ?) \
         ORDER BY e.created_at DESC, e.id DESC LIMIT ? OFFSET ?",
    )
    .bind(entity)
    .bind(entity)
    .bind(action)
    .bind(action)
    .bind(PAGE_SIZE)
    .bind(offset)
    .fetch_all(pool)
    .await
    .map_err(|_| AuditError::Database)?;
    let events = rows
        .into_iter()
        .map(|row| AuditEvent {
            id: row.get("id"),
            actor_email: row.get("actor_email"),
            actor_avatar_url: row
                .get::<Option<String>, _>("actor_avatar_filename")
                .and_then(|filename| {
                    row.get::<Option<i64>, _>("actor_user_id")
                        .and_then(|actor_id| {
                            crate::media::admin_avatar_thumbnail_url(actor_id, &filename)
                        })
                }),
            legacy_actor_user_id: row.get("legacy_actor_user_id"),
            entity_type: row.get("entity_type"),
            entity_id: row.get("entity_id"),
            action: row.get("action"),
            created_at: row.get("created_at"),
        })
        .collect();
    Ok(AuditPage {
        events,
        page,
        page_count,
        per_page: PAGE_SIZE as u32,
        has_more: page < page_count,
    })
}
