//! Safe, administrator-scoped reads of audit events for browser adapters.

use std::collections::HashSet;

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
pub(crate) struct AuditResource {
    pub(crate) kind: Option<Entity>,
    pub(crate) id: i64,
    pub(crate) name: Option<String>,
    pub(crate) name_source: &'static str,
    pub(crate) href: Option<String>,
    pub(crate) exists: bool,
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
    pub(crate) resource: AuditResource,
    pub(crate) has_changes: bool,
}

#[derive(Clone, Debug, Serialize)]
pub(crate) struct AuditDetail {
    pub(crate) event: AuditEvent,
    pub(crate) changes: Option<ChangeSetV1>,
    pub(crate) unavailable_reason: Option<&'static str>,
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
                e.entity_type, e.entity_id, e.action, e.created_at, \
                e.resource_name_snapshot, e.change_version, \
                (e.safe_changes IS NOT NULL) AS safe_changes_present, \
                (e.details IS NOT NULL) AS legacy_details_present, \
                CASE e.entity_type WHEN 'project' THEN (SELECT name FROM projects WHERE id=e.entity_id) \
                  WHEN 'yarn' THEN (SELECT name FROM yarns WHERE id=e.entity_id) \
                  WHEN 'category' THEN (SELECT name FROM categories WHERE id=e.entity_id) END AS current_name \
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
    let events = rows.into_iter().map(|row| make_event(&row)).collect();
    Ok(AuditPage {
        events,
        page,
        page_count,
        per_page: PAGE_SIZE as u32,
        has_more: page < page_count,
    })
}

pub(crate) async fn get_event_detail(
    pool: &SqlitePool,
    actor_id: i64,
    event_id: i64,
) -> Result<Option<AuditDetail>, AuditError> {
    require_active_admin(pool, actor_id)
        .await
        .map_err(|error| match error {
            AdminError::Forbidden => AuditError::Forbidden,
            _ => AuditError::Database,
        })?;
    let row = sqlx::query(
        "SELECT e.id, e.actor_user_id, e.legacy_actor_user_id, \
                u.email AS actor_email, avatar.filename AS actor_avatar_filename, \
                e.entity_type, e.entity_id, e.action, e.created_at, \
                e.resource_name_snapshot, e.change_version, \
                CASE WHEN length(e.safe_changes) <= 262144 THEN e.safe_changes END AS safe_changes, \
                CASE WHEN length(e.details) <= 262144 THEN e.details END AS details, \
                COALESCE(length(e.safe_changes) > 262144, 0) AS safe_changes_too_large, \
                COALESCE(length(e.details) > 262144, 0) AS legacy_details_too_large, \
                (e.safe_changes IS NOT NULL) AS safe_changes_present, \
                (e.details IS NOT NULL) AS legacy_details_present, \
                CASE e.entity_type WHEN 'project' THEN (SELECT name FROM projects WHERE id=e.entity_id) \
                  WHEN 'yarn' THEN (SELECT name FROM yarns WHERE id=e.entity_id) \
                  WHEN 'category' THEN (SELECT name FROM categories WHERE id=e.entity_id) END AS current_name \
         FROM audit_events AS e \
         LEFT JOIN users AS u ON u.id = e.actor_user_id \
         LEFT JOIN user_avatars AS avatar ON avatar.user_id = e.actor_user_id \
         WHERE e.id = ?",
    )
    .bind(event_id)
    .fetch_optional(pool)
    .await
    .map_err(|_| AuditError::Database)?;
    let Some(row) = row else {
        return Ok(None);
    };
    let event = make_event(&row);
    let (changes, unavailable_reason) = project_changes(&row);
    Ok(Some(AuditDetail {
        event,
        changes,
        unavailable_reason,
    }))
}

fn make_event(row: &sqlx::sqlite::SqliteRow) -> AuditEvent {
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
    let exists = current_name_present(row);
    let resource = AuditResource {
        kind,
        id: entity_id,
        name,
        name_source,
        href: kind.and_then(|kind| {
            (entity_id > 0).then(|| {
                if exists {
                    format!("/app/admin/resources/{}/{entity_id}", kind.as_str())
                } else {
                    format!("/app/admin/audit/{}", row.get::<i64, _>("id"))
                }
            })
        }),
        exists,
    };
    let has_changes = row.get::<bool, _>("safe_changes_present")
        || row.get::<Option<i64>, _>("change_version").is_some()
        || row.get::<bool, _>("legacy_details_present");
    AuditEvent {
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
        entity_type,
        entity_id,
        action: row.get("action"),
        created_at: row.get("created_at"),
        resource,
        has_changes,
    }
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
