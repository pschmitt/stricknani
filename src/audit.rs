//! Typed, transaction-bound audit events with bounded, allowlisted changes.
use std::collections::HashSet;

use serde::{Deserialize, Serialize};
use sqlx::{Sqlite, Transaction};

pub(crate) const MAX_CHANGESET_BYTES: usize = 256 * 1024;
pub(crate) const MAX_CHANGE_ITEMS: usize = 64;
// Write routes accept JSON bodies up to 2 MiB. Keep a single persisted field
// below that limit and let the aggregate limit mark unusually large diffs.
const MAX_TEXT_BYTES: usize = 2 * 1024 * 1024;
const MAX_LIST_ITEMS: usize = 1_000_000;

#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub(crate) enum Entity {
    Project,
    Yarn,
    Category,
}

impl Entity {
    pub(crate) const fn as_str(self) -> &'static str {
        match self {
            Self::Project => "project",
            Self::Yarn => "yarn",
            Self::Category => "category",
        }
    }

    pub(crate) fn parse(value: &str) -> Option<Self> {
        match value {
            "project" => Some(Self::Project),
            "yarn" => Some(Self::Yarn),
            "category" => Some(Self::Category),
            _ => None,
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum Action {
    Created,
    Updated,
    Uploaded,
    /// A project or yarn was shared with another account.
    Shared,
    ShareRoleChanged,
    ShareRevoked,
    /// A recipient left a share of their own accord.
    ShareLeft,
    OwnershipTransferred,
    /// A project timer went from stopped to running.
    TimerStarted,
    /// A running project timer was stopped; the change records the run's length.
    TimerStopped,
    /// The accumulated timer total was edited by hand.
    TimerTotalChanged,
    /// A project or yarn was moved to the trash.
    Trashed,
    /// A project or yarn was taken out of the trash.
    Restored,
    /// A trashed project or yarn was destroyed, by hand or after the retention period.
    Purged,
    /// The same three steps for one step of a project (the event is on the project).
    StepTrashed,
    StepRestored,
    StepPurged,
}

impl Action {
    const fn as_str(self) -> &'static str {
        match self {
            Self::Created => "created",
            Self::Updated => "updated",
            Self::Uploaded => "uploaded",
            Self::Shared => "shared",
            Self::ShareRoleChanged => "share_role_changed",
            Self::ShareRevoked => "share_revoked",
            Self::ShareLeft => "share_left",
            Self::OwnershipTransferred => "ownership_transferred",
            Self::TimerStarted => "timer_started",
            Self::TimerStopped => "timer_stopped",
            Self::TimerTotalChanged => "timer_total_changed",
            Self::Trashed => "trashed",
            Self::Restored => "restored",
            Self::Purged => "purged",
            Self::StepTrashed => "step_trashed",
            Self::StepRestored => "step_restored",
            Self::StepPurged => "step_purged",
        }
    }
}

/// A versioned, domain-specific diff. It is deliberately separate from the
/// legacy `details` column, whose historical contents are not trusted.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub(crate) struct ChangeSetV1 {
    pub(crate) version: u8,
    pub(crate) items: Vec<ChangeItemV1>,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub(crate) struct ChangeItemV1 {
    pub(crate) field: ChangeField,
    pub(crate) before: ChangeValue,
    pub(crate) after: ChangeValue,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash, Serialize, Deserialize)]
pub(crate) enum ChangeField {
    #[serde(rename = "project.name")]
    ProjectName,
    #[serde(rename = "project.category")]
    ProjectCategory,
    #[serde(rename = "project.yarn")]
    ProjectYarn,
    #[serde(rename = "project.needles")]
    ProjectNeedles,
    #[serde(rename = "project.stitch_sample")]
    ProjectStitchSample,
    #[serde(rename = "project.description")]
    ProjectDescription,
    #[serde(rename = "project.notes")]
    ProjectNotes,
    #[serde(rename = "project.other_materials")]
    ProjectOtherMaterials,
    #[serde(rename = "project.tags")]
    ProjectTags,
    #[serde(rename = "project.yarns")]
    ProjectYarns,
    #[serde(rename = "project.steps")]
    ProjectSteps,
    #[serde(rename = "project.step_count")]
    ProjectStepCount,
    #[serde(rename = "project.image_count")]
    ProjectImageCount,
    #[serde(rename = "project.attachment_count")]
    ProjectAttachmentCount,
    #[serde(rename = "project.is_ai_enhanced")]
    ProjectIsAiEnhanced,
    #[serde(rename = "project.link")]
    ProjectLink,
    #[serde(rename = "project.cover_image")]
    ProjectCoverImage,
    #[serde(rename = "project.image_order")]
    ProjectImageOrder,
    #[serde(rename = "project.step_image_order")]
    ProjectStepImageOrder,
    /// The project timer's accumulated seconds.
    #[serde(rename = "project.timer_seconds")]
    ProjectTimerSeconds,
    #[serde(rename = "yarn.name")]
    YarnName,
    #[serde(rename = "yarn.description")]
    YarnDescription,
    #[serde(rename = "yarn.brand")]
    YarnBrand,
    #[serde(rename = "yarn.colorway")]
    YarnColorway,
    #[serde(rename = "yarn.dye_lot")]
    YarnDyeLot,
    #[serde(rename = "yarn.fiber_content")]
    YarnFiberContent,
    #[serde(rename = "yarn.weight_category")]
    YarnWeightCategory,
    #[serde(rename = "yarn.recommended_needles")]
    YarnRecommendedNeedles,
    #[serde(rename = "yarn.weight_grams")]
    YarnWeightGrams,
    #[serde(rename = "yarn.length_meters")]
    YarnLengthMeters,
    #[serde(rename = "yarn.notes")]
    YarnNotes,
    #[serde(rename = "yarn.photo_count")]
    YarnPhotoCount,
    #[serde(rename = "yarn.is_ai_enhanced")]
    YarnIsAiEnhanced,
    #[serde(rename = "yarn.link")]
    YarnLink,
    #[serde(rename = "yarn.primary_photo")]
    YarnPrimaryPhoto,
    #[serde(rename = "yarn.photo_order")]
    YarnPhotoOrder,
    #[serde(rename = "category.name")]
    CategoryName,
}

/// Typed values used by the allowlisted audit fields. `LinkChanged` records
/// only whether a URL was present before/after, never its value.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "type", content = "value", rename_all = "snake_case")]
pub(crate) enum ChangeValue {
    Text(Option<String>),
    TextList(Vec<String>),
    Integer(Option<i64>),
    Boolean(bool),
    Steps(Vec<StepSnapshot>),
    Yarns(Vec<String>),
    LinkChanged(bool),
    /// The Python reference serialized list snapshots using `str(list)`.
    /// This variant is used only after a strict legacy-list grammar check.
    LegacyText(String),
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub(crate) struct StepSnapshot {
    pub(crate) number: i64,
    pub(crate) title: String,
    pub(crate) description: Option<String>,
}

impl ChangeSetV1 {
    pub(crate) fn new(items: Vec<ChangeItemV1>) -> Option<Self> {
        if items.is_empty() {
            return None;
        }
        let changes = Self { version: 1, items };
        changes.is_structurally_valid().then_some(changes)
    }

    pub(crate) fn is_structurally_valid(&self) -> bool {
        self.version == 1
            && self.items.len() <= MAX_CHANGE_ITEMS
            && self
                .items
                .iter()
                .map(|item| item.field)
                .collect::<HashSet<_>>()
                .len()
                == self.items.len()
            && self.items.iter().all(ChangeItemV1::is_valid)
    }

    pub(crate) fn matches_entity(&self, entity: Entity) -> bool {
        self.items.iter().all(|item| item.field.entity() == entity)
    }
}

impl ChangeItemV1 {
    pub(crate) fn new(field: ChangeField, before: ChangeValue, after: ChangeValue) -> Option<Self> {
        let item = Self {
            field,
            before,
            after,
        };
        item.is_valid().then_some(item)
    }

    pub(crate) fn is_valid(&self) -> bool {
        let expected_type = self.field.value_type();
        self.before.value_type() == expected_type
            && self.after.value_type() == expected_type
            && self.before.is_within_bounds()
            && self.after.is_within_bounds()
    }
}

#[derive(Clone, Copy, PartialEq, Eq)]
pub(crate) enum ChangeValueType {
    Text,
    TextList,
    Integer,
    Boolean,
    Steps,
    Yarns,
    LinkChanged,
    LegacyText,
}

impl ChangeField {
    const fn entity(self) -> Entity {
        match self {
            Self::ProjectName
            | Self::ProjectCategory
            | Self::ProjectYarn
            | Self::ProjectNeedles
            | Self::ProjectStitchSample
            | Self::ProjectDescription
            | Self::ProjectNotes
            | Self::ProjectOtherMaterials
            | Self::ProjectTags
            | Self::ProjectYarns
            | Self::ProjectSteps
            | Self::ProjectStepCount
            | Self::ProjectImageCount
            | Self::ProjectAttachmentCount
            | Self::ProjectIsAiEnhanced
            | Self::ProjectLink
            | Self::ProjectCoverImage
            | Self::ProjectImageOrder
            | Self::ProjectStepImageOrder
            | Self::ProjectTimerSeconds => Entity::Project,
            Self::YarnName
            | Self::YarnDescription
            | Self::YarnBrand
            | Self::YarnColorway
            | Self::YarnDyeLot
            | Self::YarnFiberContent
            | Self::YarnWeightCategory
            | Self::YarnRecommendedNeedles
            | Self::YarnWeightGrams
            | Self::YarnLengthMeters
            | Self::YarnNotes
            | Self::YarnPhotoCount
            | Self::YarnIsAiEnhanced
            | Self::YarnLink
            | Self::YarnPrimaryPhoto
            | Self::YarnPhotoOrder => Entity::Yarn,
            Self::CategoryName => Entity::Category,
        }
    }

    pub(crate) fn value_type(self) -> ChangeValueType {
        match self {
            Self::ProjectTags
            | Self::ProjectImageOrder
            | Self::ProjectStepImageOrder
            | Self::YarnPhotoOrder => ChangeValueType::TextList,
            Self::ProjectYarns => ChangeValueType::Yarns,
            Self::ProjectSteps => ChangeValueType::Steps,
            Self::ProjectStepCount
            | Self::ProjectImageCount
            | Self::ProjectAttachmentCount
            | Self::YarnWeightGrams
            | Self::YarnLengthMeters
            | Self::YarnPhotoCount
            | Self::ProjectTimerSeconds => ChangeValueType::Integer,
            Self::ProjectIsAiEnhanced | Self::YarnIsAiEnhanced => ChangeValueType::Boolean,
            Self::ProjectLink | Self::YarnLink => ChangeValueType::LinkChanged,
            Self::ProjectName
            | Self::ProjectCategory
            | Self::ProjectYarn
            | Self::ProjectNeedles
            | Self::ProjectStitchSample
            | Self::ProjectDescription
            | Self::ProjectNotes
            | Self::ProjectOtherMaterials
            | Self::ProjectCoverImage
            | Self::YarnPrimaryPhoto
            | Self::YarnName
            | Self::YarnDescription
            | Self::YarnBrand
            | Self::YarnColorway
            | Self::YarnDyeLot
            | Self::YarnFiberContent
            | Self::YarnWeightCategory
            | Self::YarnRecommendedNeedles
            | Self::YarnNotes
            | Self::CategoryName => ChangeValueType::Text,
        }
    }

    pub(crate) fn from_legacy_field(entity: Entity, field: &str) -> Option<Self> {
        match (entity, field) {
            (Entity::Project, "name") => Some(Self::ProjectName),
            (Entity::Project, "category") => Some(Self::ProjectCategory),
            (Entity::Project, "yarn") => Some(Self::ProjectYarn),
            (Entity::Project, "needles") => Some(Self::ProjectNeedles),
            (Entity::Project, "stitch_sample") => Some(Self::ProjectStitchSample),
            (Entity::Project, "description") => Some(Self::ProjectDescription),
            (Entity::Project, "notes") => Some(Self::ProjectNotes),
            (Entity::Project, "other_materials") => Some(Self::ProjectOtherMaterials),
            (Entity::Project, "tags") => Some(Self::ProjectTags),
            (Entity::Project, "yarn_ids") => Some(Self::ProjectYarns),
            (Entity::Project, "step_count") => Some(Self::ProjectStepCount),
            (Entity::Project, "image_count") => Some(Self::ProjectImageCount),
            (Entity::Project, "attachment_count") => Some(Self::ProjectAttachmentCount),
            (Entity::Project, "is_ai_enhanced") => Some(Self::ProjectIsAiEnhanced),
            (Entity::Project, "link") => Some(Self::ProjectLink),
            (Entity::Category, "name") => Some(Self::CategoryName),
            (Entity::Yarn, "name") => Some(Self::YarnName),
            (Entity::Yarn, "description") => Some(Self::YarnDescription),
            (Entity::Yarn, "brand") => Some(Self::YarnBrand),
            (Entity::Yarn, "colorway") => Some(Self::YarnColorway),
            (Entity::Yarn, "dye_lot") => Some(Self::YarnDyeLot),
            (Entity::Yarn, "fiber_content") => Some(Self::YarnFiberContent),
            (Entity::Yarn, "weight_category") => Some(Self::YarnWeightCategory),
            (Entity::Yarn, "recommended_needles") => Some(Self::YarnRecommendedNeedles),
            (Entity::Yarn, "weight_grams") => Some(Self::YarnWeightGrams),
            (Entity::Yarn, "length_meters") => Some(Self::YarnLengthMeters),
            (Entity::Yarn, "notes") => Some(Self::YarnNotes),
            (Entity::Yarn, "photo_count") => Some(Self::YarnPhotoCount),
            (Entity::Yarn, "is_ai_enhanced") => Some(Self::YarnIsAiEnhanced),
            (Entity::Yarn, "link") => Some(Self::YarnLink),
            _ => None,
        }
    }
}

impl ChangeValue {
    pub(crate) fn value_type(&self) -> ChangeValueType {
        match self {
            Self::Text(_) => ChangeValueType::Text,
            Self::TextList(_) => ChangeValueType::TextList,
            Self::Integer(_) => ChangeValueType::Integer,
            Self::Boolean(_) => ChangeValueType::Boolean,
            Self::Steps(_) => ChangeValueType::Steps,
            Self::Yarns(_) => ChangeValueType::Yarns,
            Self::LinkChanged(_) => ChangeValueType::LinkChanged,
            Self::LegacyText(_) => ChangeValueType::LegacyText,
        }
    }

    pub(crate) fn is_within_bounds(&self) -> bool {
        match self {
            Self::Text(Some(text)) | Self::LegacyText(text) => text.len() <= MAX_TEXT_BYTES,
            Self::Text(None) | Self::Integer(_) | Self::Boolean(_) | Self::LinkChanged(_) => true,
            Self::TextList(values) | Self::Yarns(values) => {
                values.len() <= MAX_LIST_ITEMS
                    && values.iter().all(|value| value.len() <= MAX_TEXT_BYTES)
            }
            Self::Steps(steps) => {
                steps.len() <= MAX_LIST_ITEMS
                    && steps.iter().all(|step| {
                        step.title.len() <= MAX_TEXT_BYTES
                            && step
                                .description
                                .as_ref()
                                .is_none_or(|value| value.len() <= MAX_TEXT_BYTES)
                    })
            }
        }
    }
}

pub(crate) fn text_change(
    field: ChangeField,
    before: Option<&str>,
    after: Option<&str>,
) -> Option<ChangeItemV1> {
    let before = before.map(str::to_owned);
    let after = after.map(str::to_owned);
    (before != after).then_some(ChangeItemV1 {
        field,
        before: ChangeValue::Text(before),
        after: ChangeValue::Text(after),
    })
}

pub(crate) fn change_item(
    field: ChangeField,
    before: ChangeValue,
    after: ChangeValue,
) -> Option<ChangeItemV1> {
    (before != after).then_some(ChangeItemV1 {
        field,
        before,
        after,
    })
}

pub(crate) fn changes(
    items: impl IntoIterator<Item = Option<ChangeItemV1>>,
) -> Option<ChangeSetV1> {
    let items: Vec<_> = items.into_iter().flatten().collect();
    ChangeSetV1::new(items)
}

/// Build a change set from collected items; `None` when there are none or the
/// items are structurally unrepresentable.
pub(crate) fn changes_from_items(items: Vec<ChangeItemV1>) -> Option<ChangeSetV1> {
    ChangeSetV1::new(items)
}

/// The public media namespace an upload or removal event refers to.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum MediaKind {
    Image,
    Photo,
    Attachment,
}

impl MediaKind {
    pub(crate) const fn as_str(self) -> &'static str {
        match self {
            Self::Image => "image",
            Self::Photo => "photo",
            Self::Attachment => "attachment",
        }
    }
}

/// The media file an upload/removal event was about. `name` is the file name
/// the user supplied (or the stored one), never a path.
#[derive(Clone, Debug)]
pub(crate) struct MediaRef {
    pub(crate) kind: MediaKind,
    pub(crate) public_id: i64,
    pub(crate) name: Option<String>,
}

/// A readable label for a media file in order/cover diffs.
pub(crate) fn media_label(original: Option<&str>, stored: &str, public_id: i64) -> String {
    let name = original
        .map(str::trim)
        .filter(|value| !value.is_empty())
        .or_else(|| Some(stored.trim()).filter(|value| !value.is_empty()));
    match name {
        Some(name) => name.chars().take(200).collect(),
        None => format!("#{public_id}"),
    }
}

const COMPACT_TEXT_CHARS: usize = 8_000;

fn truncate_text(value: &mut String) {
    if value.chars().count() > COMPACT_TEXT_CHARS {
        let cut: String = value.chars().take(COMPACT_TEXT_CHARS).collect();
        *value = format!("{cut}\u{2026}");
    }
}

impl ChangeSetV1 {
    /// Shorten every long text so a change set that exceeded the size budget
    /// still shows a useful (if abbreviated) diff instead of nothing.
    pub(crate) fn compacted(&self) -> Self {
        let mut compact = self.clone();
        for item in &mut compact.items {
            for value in [&mut item.before, &mut item.after] {
                match value {
                    ChangeValue::Text(Some(text)) | ChangeValue::LegacyText(text) => {
                        truncate_text(text);
                    }
                    ChangeValue::TextList(values) | ChangeValue::Yarns(values) => {
                        values.iter_mut().for_each(truncate_text);
                    }
                    ChangeValue::Steps(steps) => {
                        for step in steps {
                            truncate_text(&mut step.title);
                            if let Some(description) = step.description.as_mut() {
                                truncate_text(description);
                            }
                        }
                    }
                    _ => {}
                }
            }
        }
        compact
    }
}

/// Insert a minimal audit event in the same transaction as its domain mutation.
///
/// This compatibility entry point records only identifiers and a safe resource
/// name snapshot; new domain writes should use `record_with_data` for typed,
/// allowlisted changes. Neither path accepts raw details, credentials, hashes,
/// or debug output.
#[cfg(test)]
pub(crate) async fn record(
    tx: &mut Transaction<'_, Sqlite>,
    actor_id: i64,
    entity: Entity,
    target_id: i64,
    action: Action,
) -> Result<(), sqlx::Error> {
    record_with_data(tx, actor_id, entity, target_id, action, None, None).await
}

/// Record an upload or removal together with the media file it was about.
pub(crate) async fn record_with_media(
    tx: &mut Transaction<'_, Sqlite>,
    actor_id: i64,
    entity: Entity,
    target_id: i64,
    action: Action,
    media: &MediaRef,
) -> Result<(), sqlx::Error> {
    record_event(
        tx,
        Some(actor_id),
        entity,
        target_id,
        action,
        None,
        None,
        Some(media),
    )
    .await
}

/// The other account a sharing event is about, and the role involved.
pub(crate) struct SharingSubject<'a> {
    pub(crate) user_id: i64,
    pub(crate) role: Option<&'a str>,
}

/// Record a share, role change, revoke, leave or transfer with its subject.
pub(crate) async fn record_sharing(
    conn: &mut sqlx::SqliteConnection,
    actor_id: i64,
    entity: Entity,
    target_id: i64,
    action: Action,
    subject: &SharingSubject<'_>,
) -> Result<(), sqlx::Error> {
    let name_query = match entity {
        Entity::Project => "SELECT name FROM projects WHERE id = ?",
        Entity::Yarn => "SELECT name FROM yarns WHERE id = ?",
        Entity::Category => "SELECT name FROM categories WHERE id = ?",
    };
    let resource_name = sqlx::query_scalar::<_, String>(name_query)
        .bind(target_id)
        .fetch_optional(&mut *conn)
        .await?;
    sqlx::query(
        "INSERT INTO audit_events \
         (actor_user_id, entity_type, entity_id, action, created_at, \
          resource_name_snapshot, subject_user_id, subject_role, impersonated_by_user_id) \
         VALUES (?, ?, ?, ?, strftime('%Y-%m-%dT%H:%M:%f000Z', 'now'), ?, ?, ?, ?)",
    )
    .bind(actor_id)
    .bind(entity.as_str())
    .bind(target_id)
    .bind(action.as_str())
    .bind(resource_name)
    .bind(subject.user_id)
    .bind(subject.role)
    .bind(crate::impersonation::current_impersonator())
    .execute(conn)
    .await?;
    Ok(())
}

/// Record a resource snapshot and optional typed diff with the domain write.
/// If no name is supplied, look up the current entity name in the same transaction;
/// delete handlers pass the pre-delete name explicitly.
pub(crate) async fn record_with_data(
    tx: &mut Transaction<'_, Sqlite>,
    actor_id: i64,
    entity: Entity,
    target_id: i64,
    action: Action,
    resource_name: Option<&str>,
    changes: Option<&ChangeSetV1>,
) -> Result<(), sqlx::Error> {
    record_event(
        tx,
        Some(actor_id),
        entity,
        target_id,
        action,
        resource_name,
        changes,
        None,
    )
    .await
}

/// Record a trash lifecycle step (`trashed`, `restored`, `purged`). The owner
/// is the event's subject whenever somebody else (an administrator) or no
/// account (the automatic purge) acted, so the log reads "restored X's project".
#[allow(clippy::too_many_arguments)]
pub(crate) async fn record_lifecycle(
    tx: &mut Transaction<'_, Sqlite>,
    actor_id: Option<i64>,
    owner_id: i64,
    entity: Entity,
    target_id: i64,
    action: Action,
    resource_name: &str,
    media: Option<&MediaRef>,
) -> Result<(), sqlx::Error> {
    record_event(
        tx,
        actor_id,
        entity,
        target_id,
        action,
        Some(resource_name),
        None,
        media,
    )
    .await?;
    if actor_id != Some(owner_id) {
        sqlx::query("UPDATE audit_events SET subject_user_id = ? WHERE id = last_insert_rowid()")
            .bind(owner_id)
            .execute(&mut **tx)
            .await?;
    }
    Ok(())
}

#[allow(clippy::too_many_arguments)]
async fn record_event(
    tx: &mut Transaction<'_, Sqlite>,
    actor_id: Option<i64>,
    entity: Entity,
    target_id: i64,
    action: Action,
    resource_name: Option<&str>,
    changes: Option<&ChangeSetV1>,
    media: Option<&MediaRef>,
) -> Result<(), sqlx::Error> {
    let resource_name = match resource_name {
        Some(name) => Some(name.to_owned()),
        None => lookup_resource_name(tx, entity, target_id).await?,
    };
    let (change_version, safe_changes) = match changes {
        Some(changes) => {
            if !changes.is_structurally_valid() || !changes.matches_entity(entity) {
                return Err(sqlx::Error::Encode(Box::new(std::io::Error::other(
                    "invalid typed audit change set",
                ))));
            }
            let encoded = serde_json::to_vec(changes)
                .map_err(|error| sqlx::Error::Encode(Box::new(error)))?;
            if encoded.len() > MAX_CHANGESET_BYTES {
                let compact = serde_json::to_string(&changes.compacted())
                    .map_err(|error| sqlx::Error::Encode(Box::new(error)))?;
                if compact.len() > MAX_CHANGESET_BYTES {
                    (Some(i64::from(changes.version)), None)
                } else {
                    (Some(i64::from(changes.version)), Some(compact))
                }
            } else {
                let encoded = String::from_utf8(encoded)
                    .map_err(|error| sqlx::Error::Encode(Box::new(error)))?;
                (Some(i64::from(changes.version)), Some(encoded))
            }
        }
        None => (None, None),
    };
    sqlx::query(
        "INSERT INTO audit_events \
         (actor_user_id, entity_type, entity_id, action, created_at, \
          resource_name_snapshot, change_version, safe_changes, \
          media_kind, media_public_id, media_name, impersonated_by_user_id) \
         VALUES (?, ?, ?, ?, strftime('%Y-%m-%dT%H:%M:%f000Z', 'now'), ?, ?, ?, ?, ?, ?, ?)",
    )
    .bind(actor_id)
    .bind(entity.as_str())
    .bind(target_id)
    .bind(action.as_str())
    .bind(resource_name)
    .bind(change_version)
    .bind(safe_changes)
    .bind(media.map(|media| media.kind.as_str()))
    .bind(media.map(|media| media.public_id))
    .bind(media.and_then(|media| media.name.as_deref()))
    .bind(crate::impersonation::current_impersonator())
    .execute(&mut **tx)
    .await?;
    Ok(())
}

async fn lookup_resource_name(
    tx: &mut Transaction<'_, Sqlite>,
    entity: Entity,
    target_id: i64,
) -> Result<Option<String>, sqlx::Error> {
    let query = match entity {
        Entity::Project => "SELECT name FROM projects WHERE id = ?",
        Entity::Yarn => "SELECT name FROM yarns WHERE id = ?",
        Entity::Category => "SELECT name FROM categories WHERE id = ?",
    };
    sqlx::query_scalar::<_, String>(query)
        .bind(target_id)
        .fetch_optional(&mut **tx)
        .await
}

#[cfg(test)]
mod tests {
    use super::{
        Action, ChangeField, ChangeItemV1, ChangeSetV1, ChangeValue, Entity, MAX_CHANGESET_BYTES,
        record, record_with_data,
    };
    use crate::db;

    #[tokio::test]
    async fn record_is_transactional_and_contains_no_details() {
        let pool = db::connect("sqlite::memory:")
            .await
            .expect("test database should migrate");
        let actor_id: i64 = sqlx::query_scalar(
            "INSERT INTO users(email, password_hash) VALUES('audit@example.test', 'test-hash') RETURNING id",
        )
        .fetch_one(&pool)
        .await
        .expect("test actor should be inserted");

        let mut tx = crate::db::begin_write(&pool)
            .await
            .expect("transaction should begin");
        record(&mut tx, actor_id, Entity::Project, 17, Action::Created)
            .await
            .expect("event should be inserted in transaction");
        tx.rollback().await.expect("transaction should roll back");
        let count: i64 = sqlx::query_scalar("SELECT count(*) FROM audit_events")
            .fetch_one(&pool)
            .await
            .expect("audit count should be readable");
        assert_eq!(count, 0);

        let mut tx = crate::db::begin_write(&pool)
            .await
            .expect("transaction should begin");
        record(&mut tx, actor_id, Entity::Project, 17, Action::Created)
            .await
            .expect("event should be inserted in transaction");
        tx.commit().await.expect("transaction should commit");
        let row = sqlx::query(
            "SELECT actor_user_id, entity_type, entity_id, action, details \
             FROM audit_events",
        )
        .fetch_one(&pool)
        .await
        .expect("committed event should be readable");
        use sqlx::Row;
        assert_eq!(row.get::<i64, _>("actor_user_id"), actor_id);
        assert_eq!(row.get::<String, _>("entity_type"), "project");
        assert_eq!(row.get::<i64, _>("entity_id"), 17);
        assert_eq!(row.get::<String, _>("action"), "created");
        assert!(row.get::<Option<String>, _>("details").is_none());
    }

    #[tokio::test]
    async fn record_with_data_persists_snapshot_and_typed_changes_atomically() {
        let pool = db::connect("sqlite::memory:")
            .await
            .expect("test database should migrate");
        let actor_id: i64 = sqlx::query_scalar(
            "INSERT INTO users(email, password_hash) VALUES('audit-data@example.test', 'test-hash') RETURNING id",
        )
        .fetch_one(&pool)
        .await
        .expect("test actor should be inserted");
        let changes = ChangeSetV1::new(vec![
            ChangeItemV1::new(
                ChangeField::ProjectName,
                ChangeValue::Text(None),
                ChangeValue::Text(Some("Sample project".to_owned())),
            )
            .unwrap(),
        ])
        .unwrap();

        let mut tx = crate::db::begin_write(&pool)
            .await
            .expect("transaction should begin");
        record_with_data(
            &mut tx,
            actor_id,
            Entity::Project,
            17,
            Action::Created,
            Some("Sample project"),
            Some(&changes),
        )
        .await
        .expect("typed event should be inserted");
        tx.rollback().await.expect("transaction should roll back");
        let rolled_back: i64 = sqlx::query_scalar("SELECT count(*) FROM audit_events")
            .fetch_one(&pool)
            .await
            .unwrap();
        assert_eq!(rolled_back, 0);

        let mut tx = crate::db::begin_write(&pool)
            .await
            .expect("transaction should begin");
        record_with_data(
            &mut tx,
            actor_id,
            Entity::Project,
            17,
            Action::Created,
            Some("Sample project"),
            Some(&changes),
        )
        .await
        .expect("typed event should be inserted");
        tx.commit().await.expect("transaction should commit");
        let row = sqlx::query(
            "SELECT resource_name_snapshot, change_version, safe_changes, details FROM audit_events",
        )
        .fetch_one(&pool)
        .await
        .unwrap();
        use sqlx::Row;
        assert_eq!(
            row.get::<Option<String>, _>("resource_name_snapshot")
                .as_deref(),
            Some("Sample project")
        );
        assert_eq!(row.get::<Option<i64>, _>("change_version"), Some(1));
        assert!(
            row.get::<Option<String>, _>("safe_changes")
                .unwrap()
                .contains("project.name")
        );
        assert!(row.get::<Option<String>, _>("details").is_none());
    }

    #[test]
    fn changeset_uses_typed_allowlisted_values_and_rejects_mismatches() {
        let item = ChangeItemV1::new(
            ChangeField::CategoryName,
            ChangeValue::Text(Some("old".to_owned())),
            ChangeValue::Text(Some("new".to_owned())),
        )
        .unwrap();
        let changes = ChangeSetV1::new(vec![item]).unwrap();
        let json = serde_json::to_value(&changes).unwrap();
        assert_eq!(json["version"], 1);
        assert_eq!(json["items"][0]["field"], "category.name");
        assert_eq!(json["items"][0]["before"]["type"], "text");
        assert_eq!(json["items"][0]["before"]["value"], "old");

        assert!(
            ChangeItemV1::new(
                ChangeField::CategoryName,
                ChangeValue::Boolean(false),
                ChangeValue::Boolean(true),
            )
            .is_none()
        );
        assert!(ChangeSetV1::new(Vec::new()).is_none());
        assert!(!changes.matches_entity(Entity::Yarn));
    }

    #[test]
    fn aggregate_oversize_is_distinct_from_structural_invalidity() {
        let item = ChangeItemV1::new(
            ChangeField::CategoryName,
            ChangeValue::Text(None),
            ChangeValue::Text(Some("x".repeat(MAX_CHANGESET_BYTES))),
        )
        .unwrap();
        let changes = ChangeSetV1::new(vec![item]).unwrap();
        assert!(changes.is_structurally_valid());
        assert!(serde_json::to_vec(&changes).unwrap().len() > MAX_CHANGESET_BYTES);
    }

    #[tokio::test]
    async fn oversized_changeset_is_compacted_and_still_shows_a_diff() {
        let pool = db::connect("sqlite::memory:")
            .await
            .expect("test database should migrate");
        let actor_id: i64 = sqlx::query_scalar(
            "INSERT INTO users(email, password_hash) VALUES('audit-large@example.test', 'test-hash') RETURNING id",
        )
        .fetch_one(&pool)
        .await
        .expect("test actor should be inserted");
        let changes = ChangeSetV1::new(vec![
            ChangeItemV1::new(
                ChangeField::ProjectNotes,
                ChangeValue::Text(None),
                ChangeValue::Text(Some("x".repeat(MAX_CHANGESET_BYTES))),
            )
            .unwrap(),
        ])
        .unwrap();
        let mut tx = crate::db::begin_write(&pool)
            .await
            .expect("transaction should begin");
        record_with_data(
            &mut tx,
            actor_id,
            Entity::Project,
            17,
            Action::Updated,
            Some("Sample project"),
            Some(&changes),
        )
        .await
        .expect("oversized history should not reject the domain update");
        tx.commit().await.expect("event should commit");
        let row = sqlx::query("SELECT change_version, safe_changes FROM audit_events")
            .fetch_one(&pool)
            .await
            .unwrap();
        use sqlx::Row;
        assert_eq!(row.get::<Option<i64>, _>("change_version"), Some(1));
        let stored = row.get::<Option<String>, _>("safe_changes").unwrap();
        assert!(stored.len() < MAX_CHANGESET_BYTES);
        assert!(stored.contains('\u{2026}'));
    }

    #[tokio::test]
    async fn unrepresentably_large_changeset_keeps_event_and_marks_detail_unavailable() {
        let pool = db::connect("sqlite::memory:")
            .await
            .expect("test database should migrate");
        let actor_id: i64 = sqlx::query_scalar(
            "INSERT INTO users(email, password_hash) VALUES('audit-huge@example.test', 'test-hash') RETURNING id",
        )
        .fetch_one(&pool)
        .await
        .expect("test actor should be inserted");
        let steps = (0..20_000)
            .map(|number| super::StepSnapshot {
                number,
                title: "step title".to_owned(),
                description: Some("d".repeat(40)),
            })
            .collect();
        let changes = ChangeSetV1::new(vec![
            ChangeItemV1::new(
                ChangeField::ProjectSteps,
                ChangeValue::Steps(Vec::new()),
                ChangeValue::Steps(steps),
            )
            .unwrap(),
        ])
        .unwrap();
        let mut tx = crate::db::begin_write(&pool)
            .await
            .expect("transaction should begin");
        record_with_data(
            &mut tx,
            actor_id,
            Entity::Project,
            17,
            Action::Updated,
            Some("Sample project"),
            Some(&changes),
        )
        .await
        .expect("oversized history should not reject the domain update");
        tx.commit().await.expect("event should commit");
        let row = sqlx::query("SELECT change_version, safe_changes FROM audit_events")
            .fetch_one(&pool)
            .await
            .unwrap();
        use sqlx::Row;
        assert_eq!(row.get::<Option<i64>, _>("change_version"), Some(1));
        assert_eq!(row.get::<Option<String>, _>("safe_changes"), None);
    }
}
