//! Allocate stable public media IDs in the source database's ID namespaces.

use sqlx::{Sqlite, Transaction};

/// The independent ID sequence exposed by each legacy media collection.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum Namespace {
    /// Project title and step images share the legacy `images` sequence.
    Image,
    /// Yarn photos use the legacy `yarn_images` sequence.
    Photo,
    /// Project attachments use their own legacy sequence.
    Attachment,
}

impl Namespace {
    pub(crate) const fn as_str(self) -> &'static str {
        match self {
            Self::Image => "image",
            Self::Photo => "photo",
            Self::Attachment => "attachment",
        }
    }
}

/// Allocate the next ID in `namespace` as part of the caller's transaction.
///
/// The ID is committed only if the transaction commits. Once committed, it is
/// never returned again, including after the corresponding media row is deleted.
pub async fn allocate(
    tx: &mut Transaction<'_, Sqlite>,
    namespace: Namespace,
) -> Result<i64, sqlx::Error> {
    sqlx::query_scalar(
        "UPDATE media_id_sequences SET next_id = next_id + 1 \
         WHERE namespace = ? RETURNING next_id - 1",
    )
    .bind(namespace.as_str())
    .fetch_one(&mut **tx)
    .await
}
