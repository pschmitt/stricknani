//! Web Push (RFC 8030, encrypted per RFC 8291, VAPID per RFC 8292) for
//! project timer reminders, so they reach a browser with no Stricknani tab
//! open. Browsers register a subscription; the web app stores the user's
//! reminder schedule for a running timer; [`deliver_due`] sends the reminders
//! that are due and re-arms them for the next interval.

use std::time::Duration;

use base64::{Engine, engine::general_purpose::URL_SAFE_NO_PAD};
use p256::ecdsa::{Signature, SigningKey, signature::Signer};
use serde::Serialize;
use sqlx::{Row, SqlitePool};
use web_push_native::{Auth, WebPushBuilder, p256};

use crate::Language;
use crate::resource_access::{self, Need, ResourceKind, Surface};

/// VAPID `sub` claim when the operator configured none.
pub const DEFAULT_SUBJECT: &str = "https://github.com/pschmitt/stricknani";

/// Push services browsers use. Only these receive requests, so a stored
/// subscription can never make the server call an arbitrary address.
const PUSH_SERVICE_HOSTS: &[&str] = &[
    "fcm.googleapis.com",
    "android.googleapis.com",
    "push.services.mozilla.com",
    "push.apple.com",
    "notify.windows.com",
];

/// How long a push service keeps an undelivered reminder.
const PUSH_TTL: Duration = Duration::from_secs(60 * 60);
const SEND_TIMEOUT: Duration = Duration::from_secs(10);
const DUE_BATCH: i64 = 100;

/// Whether `endpoint` is an HTTPS URL of a known push service.
pub fn endpoint_allowed(endpoint: &str) -> bool {
    let Ok(url) = reqwest::Url::parse(endpoint) else {
        return false;
    };
    let Some(host) = url.host_str() else {
        return false;
    };
    url.scheme() == "https"
        && url.username().is_empty()
        && url.password().is_none()
        && url.port().is_none()
        && endpoint.len() <= 2048
        && PUSH_SERVICE_HOSTS
            .iter()
            .any(|service| host == *service || host.ends_with(&format!(".{service}")))
}

/// The server's VAPID signing key, created and stored on first use.
pub async fn vapid_key(pool: &SqlitePool) -> Result<SigningKey, sqlx::Error> {
    if let Some(key) = stored_vapid_key(pool).await? {
        return Ok(key);
    }
    let key = loop {
        let bytes: [u8; 32] = rand::random();
        if let Ok(key) = SigningKey::from_slice(&bytes) {
            break key;
        }
    };
    sqlx::query(
        "INSERT OR IGNORE INTO push_vapid_key(singleton, private_key, created_at) \
         VALUES (1, ?, unixepoch())",
    )
    .bind(key.to_bytes().to_vec())
    .execute(pool)
    .await?;
    // Another request may have created one first; everyone uses the stored key.
    stored_vapid_key(pool)
        .await?
        .ok_or_else(|| sqlx::Error::Protocol("VAPID key missing after insert".into()))
}

async fn stored_vapid_key(pool: &SqlitePool) -> Result<Option<SigningKey>, sqlx::Error> {
    let bytes: Option<Vec<u8>> =
        sqlx::query_scalar("SELECT private_key FROM push_vapid_key WHERE singleton = 1")
            .fetch_optional(pool)
            .await?;
    bytes
        .map(|bytes| {
            SigningKey::from_slice(&bytes)
                .map_err(|_| sqlx::Error::Protocol("stored VAPID key is invalid".into()))
        })
        .transpose()
}

/// The application server key browsers subscribe with (uncompressed P-256
/// point, base64url without padding).
pub fn public_key(key: &SigningKey) -> String {
    URL_SAFE_NO_PAD.encode(key.verifying_key().to_encoded_point(false).as_bytes())
}

/// `Authorization: vapid t=<JWT>, k=<public key>` for one push service.
fn vapid_authorization(
    key: &SigningKey,
    endpoint: &reqwest::Url,
    subject: &str,
    now: i64,
) -> String {
    #[derive(Serialize)]
    struct Claims<'a> {
        aud: String,
        exp: i64,
        sub: &'a str,
    }
    let header = URL_SAFE_NO_PAD.encode(br#"{"typ":"JWT","alg":"ES256"}"#);
    let claims = Claims {
        aud: endpoint.origin().ascii_serialization(),
        exp: now + 12 * 60 * 60,
        sub: subject,
    };
    let claims = URL_SAFE_NO_PAD.encode(serde_json::to_vec(&claims).unwrap_or_default());
    let signing_input = format!("{header}.{claims}");
    let signature: Signature = key.sign(signing_input.as_bytes());
    format!(
        "vapid t={signing_input}.{}, k={}",
        URL_SAFE_NO_PAD.encode(signature.to_bytes()),
        public_key(key)
    )
}

/// A browser's push subscription as stored.
pub struct Subscription {
    pub id: i64,
    pub endpoint: String,
    pub p256dh: Vec<u8>,
    pub auth: Vec<u8>,
}

/// Outcome of one push request.
#[derive(Debug, PartialEq, Eq)]
pub enum Delivery {
    Sent,
    /// The browser dropped the subscription (404/410); it is deleted.
    Gone,
    Failed,
}

/// Sends pushes: the HTTP client, the VAPID subject and which endpoints may
/// be called (tests allow their local push service).
pub struct Sender {
    client: reqwest::Client,
    subject: String,
    allow_endpoint: fn(&str) -> bool,
}

impl Sender {
    pub fn new(subject: Option<String>) -> Result<Self, reqwest::Error> {
        Self::with_endpoint_check(subject, endpoint_allowed)
    }

    pub fn with_endpoint_check(
        subject: Option<String>,
        allow_endpoint: fn(&str) -> bool,
    ) -> Result<Self, reqwest::Error> {
        let builder = || {
            reqwest::Client::builder()
                .redirect(reqwest::redirect::Policy::none())
                .timeout(SEND_TIMEOUT)
                .connect_timeout(SEND_TIMEOUT)
                .user_agent(concat!(
                    env!("CARGO_PKG_NAME"),
                    "/",
                    env!("CARGO_PKG_VERSION")
                ))
        };
        // Without system CA certificates (the Nix build sandbox, minimal
        // containers) the default client cannot be built. Fall back to one
        // without TLS roots: HTTPS pushes then fail (reminders still show in
        // open tabs), but the server still starts.
        let client = builder()
            .build()
            .or_else(|_| builder().tls_certs_only(Vec::new()).build())?;
        Ok(Self {
            client,
            subject: subject
                .filter(|subject| valid_subject(subject))
                .unwrap_or_else(|| DEFAULT_SUBJECT.to_owned()),
            allow_endpoint,
        })
    }

    /// Encrypts `payload` for the subscription and posts it to its push service.
    pub async fn send(
        &self,
        key: &SigningKey,
        subscription: &Subscription,
        payload: &[u8],
        now: i64,
    ) -> Delivery {
        if !(self.allow_endpoint)(&subscription.endpoint) {
            return Delivery::Gone;
        }
        let (Ok(url), Ok(uri), Ok(ua_public)) = (
            reqwest::Url::parse(&subscription.endpoint),
            subscription.endpoint.parse::<http::Uri>(),
            p256::PublicKey::from_sec1_bytes(&subscription.p256dh),
        ) else {
            return Delivery::Gone;
        };
        if subscription.auth.len() != 16 {
            return Delivery::Gone;
        }
        let auth = Auth::clone_from_slice(&subscription.auth);
        let Ok(request) = WebPushBuilder::new(uri, ua_public, auth)
            .with_valid_duration(PUSH_TTL)
            .build(payload.to_vec())
        else {
            return Delivery::Failed;
        };
        let (parts, body) = request.into_parts();
        let mut builder = self
            .client
            .post(url.clone())
            .header(
                reqwest::header::AUTHORIZATION,
                vapid_authorization(key, &url, &self.subject, now),
            )
            .header("Urgency", "normal")
            .body(body);
        for (name, value) in &parts.headers {
            builder = builder.header(name, value);
        }
        match builder.send().await {
            Ok(response) if response.status().is_success() => Delivery::Sent,
            Ok(response) if matches!(response.status().as_u16(), 404 | 410) => Delivery::Gone,
            _ => Delivery::Failed,
        }
    }
}

/// A VAPID subject must be a `mailto:` or `https:` URI (RFC 8292).
pub fn valid_subject(subject: &str) -> bool {
    (subject.starts_with("mailto:") && subject.len() > 7 && !subject.contains(char::is_whitespace))
        || reqwest::Url::parse(subject).is_ok_and(|url| url.scheme() == "https")
}

#[derive(Serialize)]
struct ReminderPayload<'a> {
    title: &'a str,
    body: &'a str,
    tag: String,
    path: String,
}

fn reminder_payload(language: Language, project_id: i64) -> Vec<u8> {
    let tr = |english, german| crate::i18n::text(language, english, german);
    serde_json::to_vec(&ReminderPayload {
        title: tr(
            "Project timer is still running",
            "Der Projekttimer läuft noch",
        ),
        body: tr(
            "Open the project to stop the timer or keep timing.",
            "Öffne das Projekt, um den Timer zu stoppen oder weiterlaufen zu lassen.",
        ),
        tag: format!("project-timer-{project_id}"),
        path: format!("/app/projects/{project_id}"),
    })
    .unwrap_or_default()
}

/// Sends every reminder due at `now` and re-arms it one interval later.
/// Schedules of runs that ended, and of projects the user may no longer edit,
/// are dropped. Returns the number of reminders sent to at least one browser.
pub async fn deliver_due(
    pool: &SqlitePool,
    sender: &Sender,
    now: i64,
) -> Result<usize, sqlx::Error> {
    sqlx::query(
        "DELETE FROM timer_reminders WHERE NOT EXISTS (SELECT 1 FROM project_timers t \
         WHERE t.project_id = timer_reminders.project_id \
           AND t.started_at = timer_reminders.started_at)",
    )
    .execute(pool)
    .await?;
    let due = sqlx::query(
        "SELECT user_id, project_id, interval_minutes, next_at, locale FROM timer_reminders \
         WHERE next_at <= ? ORDER BY next_at LIMIT ?",
    )
    .bind(now)
    .bind(DUE_BATCH)
    .fetch_all(pool)
    .await?;
    if due.is_empty() {
        return Ok(0);
    }
    let key = vapid_key(pool).await?;
    let mut delivered = 0;
    for row in due {
        let user_id: i64 = row.get("user_id");
        let project_id: i64 = row.get("project_id");
        let interval: i64 = row.get("interval_minutes");
        let next_at: i64 = row.get("next_at");
        let locale: String = row.get("locale");
        if resource_access::require(
            pool,
            user_id,
            ResourceKind::Project,
            project_id,
            Surface::V2,
            Need::Edit,
        )
        .await
        .is_err()
        {
            sqlx::query("DELETE FROM timer_reminders WHERE user_id = ? AND project_id = ?")
                .bind(user_id)
                .bind(project_id)
                .execute(pool)
                .await?;
            continue;
        }
        // Claim this reminder; a concurrent run that re-armed it first wins.
        let claimed = sqlx::query(
            "UPDATE timer_reminders SET next_at = ? \
             WHERE user_id = ? AND project_id = ? AND next_at = ?",
        )
        .bind(now + interval * 60)
        .bind(user_id)
        .bind(project_id)
        .bind(next_at)
        .execute(pool)
        .await?
        .rows_affected();
        if claimed == 0 {
            continue;
        }
        let language = if locale == "de" {
            Language::German
        } else {
            Language::English
        };
        let payload = reminder_payload(language, project_id);
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
