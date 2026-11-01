//! Typed, transaction-bound audit events that deliberately exclude user content.
use sqlx::{Sqlite, Transaction};

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum Entity {
    Project,
    Yarn,
    Category,
}

impl Entity {
    const fn as_str(self) -> &'static str {
        match self {
            Self::Project => "project",
            Self::Yarn => "yarn",
            Self::Category => "category",
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum Action {
    Created,
    Updated,
    Deleted,
    Uploaded,
    Removed,
}

impl Action {
    const fn as_str(self) -> &'static str {
        match self {
            Self::Created => "created",
            Self::Updated => "updated",
            Self::Deleted => "deleted",
            Self::Uploaded => "uploaded",
            Self::Removed => "removed",
        }
    }
}

/// Insert a minimal audit event in the same transaction as its domain mutation.
///
/// The event records only actor, target, entity, and action identifiers. It does
/// not accept arbitrary details, user text, credentials, hashes, or debug output.
pub(crate) async fn record(
    tx: &mut Transaction<'_, Sqlite>,
    actor_id: i64,
    entity: Entity,
    target_id: i64,
    action: Action,
) -> Result<(), sqlx::Error> {
    sqlx::query(
        "INSERT INTO audit_events (actor_user_id, entity_type, entity_id, action, created_at) \
         VALUES (?, ?, ?, ?, strftime('%Y-%m-%dT%H:%M:%f000Z', 'now'))",
    )
    .bind(actor_id)
    .bind(entity.as_str())
    .bind(target_id)
    .bind(action.as_str())
    .execute(&mut **tx)
    .await?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::{Action, Entity, record};
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

        let mut tx = pool.begin().await.expect("transaction should begin");
        record(&mut tx, actor_id, Entity::Project, 17, Action::Created)
            .await
            .expect("event should be inserted in transaction");
        tx.rollback().await.expect("transaction should roll back");
        let count: i64 = sqlx::query_scalar("SELECT count(*) FROM audit_events")
            .fetch_one(&pool)
            .await
            .expect("audit count should be readable");
        assert_eq!(count, 0);

        let mut tx = pool.begin().await.expect("transaction should begin");
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
}
