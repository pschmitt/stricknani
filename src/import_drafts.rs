//! Imports that wait for the user's review (`import_drafts`, see the migration). The API starts
//! and lists them (`api_v2::import_drafts`); this module keeps the table tidy and tells the user
//! when a draft is done, through the web push loop that also sends timer reminders.

use serde::Serialize;
use sqlx::{Row, SqlitePool};

use crate::Language;
use crate::web_push::{Delivery, Sender, Subscription, vapid_key};

/// Drafts older than this are deleted; nobody reviews an import after a month.
const MAX_AGE_SECONDS: i64 = 30 * 24 * 60 * 60;
/// At most this many results are announced per round of the push loop.
const NOTIFY_BATCH: i64 = 50;

/// The error stored for a draft whose import stopped with the server.
pub const INTERRUPTED_ERROR: &str =
    r#"{"error":{"code":"import_interrupted","message":"The import was interrupted. Try again."}}"#;

/// At start-up: imports that were running when the server stopped will never finish.
pub async fn fail_interrupted(pool: &SqlitePool, now: i64) -> Result<u64, sqlx::Error> {
    sqlx::query(
        "UPDATE import_drafts SET status = 'failed', error_status = 503, error_json = ?, \
         finished_at = ? WHERE status = 'running'",
    )
    .bind(INTERRUPTED_ERROR)
    .bind(now)
    .execute(pool)
    .await
    .map(|result| result.rows_affected())
}

#[derive(Serialize)]
struct DraftPayload {
    title: String,
    body: String,
    tag: String,
    path: String,
    /// The first picture through the import image proxy (needs the session cookie).
    #[serde(skip_serializing_if = "Option::is_none")]
    image: Option<String>,
    open_label: String,
    timestamp: i64,
}

/// The app path that opens a draft in its editor.
pub fn draft_path(target: &str, id: i64) -> String {
    match target {
        "yarn" => format!("/app/yarns/new?draft={id}"),
        _ => format!("/app/projects/new?draft={id}"),
    }
}

fn draft_payload(
    language: Language,
    id: i64,
    target: &str,
    ready: bool,
    title: Option<&str>,
    image_url: Option<&str>,
    now: i64,
) -> Vec<u8> {
    let tr = |english, german| crate::i18n::text(language, english, german);
    let (title, body) = if ready {
        (
            if target == "yarn" {
                tr("Yarn ready to review", "Garn bereit zum Prüfen")
            } else {
                tr("Pattern ready to review", "Anleitung bereit zum Prüfen")
            }
            .to_owned(),
            title.unwrap_or_default().to_owned(),
        )
    } else {
        (
            tr("The import did not work", "Der Import hat nicht geklappt").to_owned(),
            tr(
                "Open the import to see why and try again.",
                "Öffne den Import, um den Grund zu sehen und es erneut zu versuchen.",
            )
            .to_owned(),
        )
    };
    serde_json::to_vec(&DraftPayload {
        title,
        body,
        tag: format!("import-draft-{id}"),
        path: draft_path(target, id),
        image: image_url
            .filter(|_| ready)
            .map(|url| format!("/api/v2/imports/image?url={}", urlencoding_encode(url))),
        open_label: tr("Review", "Prüfen").to_owned(),
        timestamp: now * 1000,
    })
    .unwrap_or_default()
}

fn urlencoding_encode(value: &str) -> String {
    url::form_urlencoded::byte_serialize(value.as_bytes()).collect()
}

/// Announces finished drafts once (claims each first, so a result is never sent twice) and
/// deletes drafts older than a month. Returns the number announced to at least one browser.
pub async fn deliver_finished(
    pool: &SqlitePool,
    sender: &Sender,
    now: i64,
) -> Result<usize, sqlx::Error> {
    sqlx::query("DELETE FROM import_drafts WHERE created_at < ?")
        .bind(now - MAX_AGE_SECONDS)
        .execute(pool)
        .await?;
    let finished = sqlx::query(
        "SELECT id, user_id, target, status, title, image_url, locale FROM import_drafts \
         WHERE status IN ('ready', 'failed') AND notified_at IS NULL ORDER BY id LIMIT ?",
    )
    .bind(NOTIFY_BATCH)
    .fetch_all(pool)
    .await?;
    if finished.is_empty() {
        return Ok(0);
    }
    let key = vapid_key(pool).await?;
    let mut delivered = 0;
    for row in finished {
        let id: i64 = row.get("id");
        let claimed = sqlx::query(
            "UPDATE import_drafts SET notified_at = ? WHERE id = ? AND notified_at IS NULL",
        )
        .bind(now)
        .bind(id)
        .execute(pool)
        .await?
        .rows_affected();
        if claimed == 0 {
            continue;
        }
        let user_id: i64 = row.get("user_id");
        let target: String = row.get("target");
        let status: String = row.get("status");
        let title: Option<String> = row.get("title");
        let image_url: Option<String> = row.get("image_url");
        let locale: String = row.get("locale");
        let language = if locale == "de" {
            Language::German
        } else {
            Language::English
        };
        let payload = draft_payload(
            language,
            id,
            &target,
            status == "ready",
            title.as_deref(),
            image_url.as_deref(),
            now,
        );
        let subscriptions = sqlx::query(
            "SELECT id, endpoint, p256dh, auth FROM push_subscriptions WHERE user_id = ?",
        )
        .bind(user_id)
        .fetch_all(pool)
        .await?;
        let mut sent = false;
        for row in subscriptions {
            let subscription = Subscription {
                id: row.get("id"),
                endpoint: row.get("endpoint"),
                p256dh: row.get("p256dh"),
                auth: row.get("auth"),
            };
            match sender.send(&key, &subscription, &payload, now).await {
                Delivery::Sent => sent = true,
                Delivery::Gone => {
                    sqlx::query("DELETE FROM push_subscriptions WHERE id = ?")
                        .bind(subscription.id)
                        .execute(pool)
                        .await?;
                }
                Delivery::Failed => {}
            }
        }
        if sent {
            delivered += 1;
        }
    }
    Ok(delivered)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn message(ready: bool, target: &str) -> serde_json::Value {
        serde_json::from_slice(&draft_payload(
            Language::English,
            7,
            target,
            ready,
            Some("Little Pearl Hat"),
            Some("https://images.example/a b.jpg"),
            100,
        ))
        .unwrap()
    }

    #[test]
    fn a_ready_project_names_it_and_opens_its_editor() {
        let ready = message(true, "project");
        assert_eq!(ready["title"], "Pattern ready to review");
        assert_eq!(ready["body"], "Little Pearl Hat");
        assert_eq!(ready["path"], "/app/projects/new?draft=7");
        assert_eq!(
            ready["image"],
            "/api/v2/imports/image?url=https%3A%2F%2Fimages.example%2Fa+b.jpg"
        );
        assert_eq!(message(true, "yarn")["path"], "/app/yarns/new?draft=7");
    }

    #[test]
    fn a_failed_import_says_so_without_a_picture() {
        let failed = message(false, "project");
        assert_eq!(failed["title"], "The import did not work");
        assert!(failed.get("image").is_none());
    }
}
