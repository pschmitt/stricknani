//! Web Push: subscriptions, reminder schedules and delivery to a push service.

use std::sync::{Arc, Mutex};

use axum::{
    Router,
    body::{Body, Bytes},
    extract::State,
    http::{HeaderMap, Request, StatusCode},
    routing::post,
};
use base64::{Engine, engine::general_purpose::URL_SAFE_NO_PAD};
use http_body_util::BodyExt;
use p256::elliptic_curve::sec1::ToEncodedPoint;
use sqlx::SqlitePool;
use stricknani_server::{api_v2, db, web_push};
use tower::ServiceExt;

const HOST: &str = "app.example.test";
const ORIGIN: &str = "https://app.example.test";
const PASSWORD: &str = "web-push-test-password";
const FCM: &str = "https://fcm.googleapis.com/fcm/send/example-browser";

async fn fixture() -> (Router, SqlitePool) {
    let pool = db::connect("sqlite::memory:").await.unwrap();
    let state = db::AppState::new(pool.clone()).with_secure_cookies(true);
    (api_v2::router(state, false), pool)
}

async fn json(response: axum::response::Response) -> serde_json::Value {
    let bytes = response.into_body().collect().await.unwrap().to_bytes();
    serde_json::from_slice(&bytes).unwrap_or(serde_json::Value::Null)
}

fn cookie(response: &axum::response::Response, prefix: &str) -> String {
    response
        .headers()
        .get_all("set-cookie")
        .iter()
        .filter_map(|value| value.to_str().ok())
        .find_map(|value| value.strip_prefix(prefix))
        .and_then(|value| value.split(';').next())
        .unwrap()
        .to_owned()
}

async fn signed_in(app: &Router, email: &str) -> (String, String) {
    let bootstrap = app
        .clone()
        .oneshot(Request::get("/session").body(Body::empty()).unwrap())
        .await
        .unwrap();
    let preauth = cookie(&bootstrap, "__Host-stricknani_v2_login_csrf=");
    let pre_csrf = json(bootstrap).await["data"]["csrf_token"]
        .as_str()
        .unwrap()
        .to_owned();
    let login = app
        .clone()
        .oneshot(
            Request::post("/auth/login")
                .header("host", HOST)
                .header("origin", ORIGIN)
                .header("sec-fetch-site", "same-origin")
                .header(
                    "cookie",
                    format!("__Host-stricknani_v2_login_csrf={preauth}"),
                )
                .header("x-csrf-token", pre_csrf)
                .header("content-type", "application/json")
                .body(Body::from(
                    serde_json::json!({"email":email,"password":PASSWORD}).to_string(),
                ))
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(login.status(), StatusCode::OK);
    let session = cookie(&login, "__Host-stricknani_session=");
    let csrf = json(login).await["data"]["csrf_token"]
        .as_str()
        .unwrap()
        .to_owned();
    (session, csrf)
}

fn request(
    method: &str,
    path: &str,
    (session, csrf): &(String, String),
    body: Option<serde_json::Value>,
) -> Request<Body> {
    let mut request = Request::builder()
        .method(method)
        .uri(path)
        .header("cookie", format!("__Host-stricknani_session={session}"))
        .header("host", HOST)
        .header("origin", ORIGIN)
        .header("sec-fetch-site", "same-origin")
        .header("x-csrf-token", csrf);
    if body.is_some() {
        request = request.header("content-type", "application/json");
    }
    request
        .body(body.map_or_else(Body::empty, |body| Body::from(body.to_string())))
        .unwrap()
}

async fn send(app: &Router, request: Request<Body>) -> axum::response::Response {
    app.clone().oneshot(request).await.unwrap()
}

async fn project(pool: &SqlitePool, owner_id: i64) -> i64 {
    sqlx::query_scalar(
        "INSERT INTO projects(owner_id, name, created_at, updated_at) \
         VALUES (?, 'Push test', 'now', 'now') RETURNING id",
    )
    .bind(owner_id)
    .fetch_one(pool)
    .await
    .unwrap()
}

/// A browser's push keys: its P-256 key pair and the 16-byte auth secret.
struct Browser {
    secret: p256::SecretKey,
    auth: [u8; 16],
}

impl Browser {
    fn new() -> Self {
        let secret = loop {
            let bytes: [u8; 32] = rand::random();
            if let Ok(secret) = p256::SecretKey::from_slice(&bytes) {
                break secret;
            }
        };
        Self {
            secret,
            auth: rand::random(),
        }
    }

    fn public(&self) -> Vec<u8> {
        self.secret
            .public_key()
            .to_encoded_point(false)
            .as_bytes()
            .to_vec()
    }

    fn subscription(&self, endpoint: &str) -> serde_json::Value {
        serde_json::json!({
            "endpoint": endpoint,
            "expirationTime": null,
            "keys": {
                "p256dh": URL_SAFE_NO_PAD.encode(self.public()),
                "auth": URL_SAFE_NO_PAD.encode(self.auth),
            }
        })
    }
}

#[tokio::test]
async fn browsers_subscribe_only_to_known_push_services() {
    let (app, pool) = fixture().await;
    db::create_user(&pool, "knitter@example.test", PASSWORD.to_owned())
        .await
        .unwrap();
    let user = signed_in(&app, "knitter@example.test").await;
    let browser = Browser::new();

    // Unauthenticated callers get no key.
    let anonymous = send(&app, Request::get("/push/key").body(Body::empty()).unwrap()).await;
    assert_eq!(anonymous.status(), StatusCode::UNAUTHORIZED);

    let key = send(&app, request("GET", "/push/key", &user, None)).await;
    assert_eq!(key.status(), StatusCode::OK);
    let public_key = json(key).await["data"]["public_key"]
        .as_str()
        .unwrap()
        .to_owned();
    let decoded = URL_SAFE_NO_PAD.decode(&public_key).unwrap();
    assert_eq!(decoded.len(), 65);
    assert!(p256::PublicKey::from_sec1_bytes(&decoded).is_ok());
    // The key is stored, not generated per request.
    let again = send(&app, request("GET", "/push/key", &user, None)).await;
    assert_eq!(json(again).await["data"]["public_key"], public_key);

    for endpoint in [
        "http://fcm.googleapis.com/fcm/send/x",
        "https://127.0.0.1/push",
        "https://evil.example/push",
        "https://fcm.googleapis.com:8443/fcm/send/x",
    ] {
        let response = send(
            &app,
            request(
                "PUT",
                "/push/subscription",
                &user,
                Some(browser.subscription(endpoint)),
            ),
        )
        .await;
        assert_eq!(
            response.status(),
            StatusCode::UNPROCESSABLE_ENTITY,
            "{endpoint}"
        );
    }
    let mut broken = browser.subscription(FCM);
    broken["keys"]["auth"] = serde_json::json!("c2hvcnQ");
    let response = send(
        &app,
        request("PUT", "/push/subscription", &user, Some(broken)),
    )
    .await;
    assert_eq!(response.status(), StatusCode::UNPROCESSABLE_ENTITY);

    let response = send(
        &app,
        request(
            "PUT",
            "/push/subscription",
            &user,
            Some(browser.subscription(FCM)),
        ),
    )
    .await;
    assert_eq!(response.status(), StatusCode::NO_CONTENT);
    let stored: i64 = sqlx::query_scalar("SELECT count(*) FROM push_subscriptions")
        .fetch_one(&pool)
        .await
        .unwrap();
    assert_eq!(stored, 1);

    let response = send(
        &app,
        request(
            "DELETE",
            "/push/subscription",
            &user,
            Some(serde_json::json!({"endpoint": FCM})),
        ),
    )
    .await;
    assert_eq!(response.status(), StatusCode::NO_CONTENT);
    let stored: i64 = sqlx::query_scalar("SELECT count(*) FROM push_subscriptions")
        .fetch_one(&pool)
        .await
        .unwrap();
    assert_eq!(stored, 0);
}

#[tokio::test]
async fn reminder_schedules_follow_the_running_timer_and_edit_access() {
    let (app, pool) = fixture().await;
    let owner_id = db::create_user(&pool, "owner@example.test", PASSWORD.to_owned())
        .await
        .unwrap();
    let viewer_id = db::create_user(&pool, "viewer@example.test", PASSWORD.to_owned())
        .await
        .unwrap();
    let project_id = project(&pool, owner_id).await;
    sqlx::query(
        "INSERT INTO resource_shares(kind, resource_id, user_id, role, created_at, updated_at) \
         VALUES ('project', ?, ?, 'viewer', 'now', 'now')",
    )
    .bind(project_id)
    .bind(viewer_id)
    .execute(&pool)
    .await
    .unwrap();
    let owner = signed_in(&app, "owner@example.test").await;
    let viewer = signed_in(&app, "viewer@example.test").await;
    let path = format!("/projects/{project_id}/timer/reminder");
    let schedule = |started_at: &str| {
        serde_json::json!({
            "started_at": started_at,
            "interval_minutes": 30,
            "next_at": chrono::Utc::now().to_rfc3339(),
            "locale": "de",
        })
    };

    // No running timer: no schedule.
    let response = send(
        &app,
        request("PUT", &path, &owner, Some(schedule("2026-01-01T00:00:00Z"))),
    )
    .await;
    assert_eq!(response.status(), StatusCode::CONFLICT);

    let timer = send(
        &app,
        request(
            "PUT",
            &format!("/projects/{project_id}/timer"),
            &owner,
            Some(serde_json::json!({"action": "start"})),
        ),
    )
    .await;
    let started_at = json(timer).await["data"]["started_at"]
        .as_str()
        .unwrap()
        .to_owned();

    let response = send(
        &app,
        request("PUT", &path, &viewer, Some(schedule(&started_at))),
    )
    .await;
    assert_eq!(response.status(), StatusCode::FORBIDDEN);

    let mut too_far = schedule(&started_at);
    too_far["next_at"] =
        serde_json::json!((chrono::Utc::now() + chrono::Duration::days(3)).to_rfc3339());
    let response = send(&app, request("PUT", &path, &owner, Some(too_far))).await;
    assert_eq!(response.status(), StatusCode::UNPROCESSABLE_ENTITY);
    let mut odd_interval = schedule(&started_at);
    odd_interval["interval_minutes"] = serde_json::json!(45);
    let response = send(&app, request("PUT", &path, &owner, Some(odd_interval))).await;
    assert_eq!(response.status(), StatusCode::UNPROCESSABLE_ENTITY);

    let response = send(
        &app,
        request("PUT", &path, &owner, Some(schedule(&started_at))),
    )
    .await;
    assert_eq!(response.status(), StatusCode::NO_CONTENT);
    let (interval, locale): (i64, String) = sqlx::query_as(
        "SELECT interval_minutes, locale FROM timer_reminders WHERE user_id = ? AND project_id = ?",
    )
    .bind(owner_id)
    .bind(project_id)
    .fetch_one(&pool)
    .await
    .unwrap();
    assert_eq!((interval, locale.as_str()), (30, "de"));

    let mut off = schedule(&started_at);
    off["interval_minutes"] = serde_json::json!(0);
    let response = send(&app, request("PUT", &path, &owner, Some(off))).await;
    assert_eq!(response.status(), StatusCode::NO_CONTENT);
    let remaining: i64 = sqlx::query_scalar("SELECT count(*) FROM timer_reminders")
        .fetch_one(&pool)
        .await
        .unwrap();
    assert_eq!(remaining, 0);
}

#[derive(Default)]
struct PushService {
    received: Mutex<Vec<(HeaderMap, Vec<u8>)>>,
    status: Mutex<u16>,
}

async fn push_service() -> (Arc<PushService>, String) {
    async fn receive(
        State(service): State<Arc<PushService>>,
        headers: HeaderMap,
        body: Bytes,
    ) -> StatusCode {
        service
            .received
            .lock()
            .unwrap()
            .push((headers, body.to_vec()));
        StatusCode::from_u16(*service.status.lock().unwrap()).unwrap()
    }
    let service = Arc::new(PushService {
        status: Mutex::new(201),
        ..Default::default()
    });
    let app = Router::new()
        .route("/push/{id}", post(receive))
        .with_state(service.clone());
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let addr = listener.local_addr().unwrap();
    tokio::spawn(async move { axum::serve(listener, app).await.unwrap() });
    (service, format!("http://{addr}/push/browser"))
}

#[tokio::test(flavor = "multi_thread")]
async fn due_reminders_are_encrypted_signed_and_rearmed() {
    let (_app, pool) = fixture().await;
    let owner_id = db::create_user(&pool, "owner@example.test", PASSWORD.to_owned())
        .await
        .unwrap();
    let project_id = project(&pool, owner_id).await;
    let (service, endpoint) = push_service().await;
    let browser = Browser::new();
    let now = chrono::Utc::now().timestamp();
    let started_at = now - 3600;
    sqlx::query(
        "INSERT INTO project_timers(project_id, total_seconds, started_at) VALUES (?, 0, ?)",
    )
    .bind(project_id)
    .bind(started_at)
    .execute(&pool)
    .await
    .unwrap();
    sqlx::query(
        "INSERT INTO push_subscriptions(user_id, endpoint, p256dh, auth, created_at) \
         VALUES (?, ?, ?, ?, unixepoch())",
    )
    .bind(owner_id)
    .bind(&endpoint)
    .bind(browser.public())
    .bind(browser.auth.to_vec())
    .execute(&pool)
    .await
    .unwrap();
    sqlx::query(
        "INSERT INTO timer_reminders(user_id, project_id, started_at, interval_minutes, next_at, locale) \
         VALUES (?, ?, ?, 60, ?, 'de')",
    )
    .bind(owner_id)
    .bind(project_id)
    .bind(started_at)
    .bind(now - 5)
    .execute(&pool)
    .await
    .unwrap();
    let sender = web_push::Sender::with_endpoint_check(None, |_| true).unwrap();

    assert_eq!(web_push::deliver_due(&pool, &sender, now).await.unwrap(), 1);
    let received = service.received.lock().unwrap().clone();
    assert_eq!(received.len(), 1);
    let (headers, body) = &received[0];
    assert_eq!(headers["content-encoding"], "aes128gcm");
    assert!(headers.contains_key("ttl"));
    let authorization = headers["authorization"].to_str().unwrap();
    assert!(authorization.starts_with("vapid t="), "{authorization}");
    let server_key = web_push::public_key(&web_push::vapid_key(&pool).await.unwrap());
    assert!(authorization.ends_with(&format!(", k={server_key}")));

    let auth = web_push_native::Auth::clone_from_slice(&browser.auth);
    let plain = web_push_native::decrypt(body.clone(), &browser.secret, &auth).unwrap();
    let message: serde_json::Value = serde_json::from_slice(&plain).unwrap();
    assert_eq!(message["title"], "Der Projekttimer läuft noch");
    assert_eq!(message["path"], format!("/app/projects/{project_id}"));
    assert_eq!(message["tag"], format!("project-timer-{project_id}"));

    // Re-armed one interval later; nothing more is due now.
    let next_at: i64 = sqlx::query_scalar("SELECT next_at FROM timer_reminders")
        .fetch_one(&pool)
        .await
        .unwrap();
    assert_eq!(next_at, now + 3600);
    assert_eq!(web_push::deliver_due(&pool, &sender, now).await.unwrap(), 0);

    // A browser that dropped its subscription (410) is forgotten.
    *service.status.lock().unwrap() = 410;
    assert_eq!(
        web_push::deliver_due(&pool, &sender, now + 3600)
            .await
            .unwrap(),
        0
    );
    let subscriptions: i64 = sqlx::query_scalar("SELECT count(*) FROM push_subscriptions")
        .fetch_one(&pool)
        .await
        .unwrap();
    assert_eq!(subscriptions, 0);

    // Stopping the timer ends the schedule.
    sqlx::query("UPDATE project_timers SET started_at = NULL WHERE project_id = ?")
        .bind(project_id)
        .execute(&pool)
        .await
        .unwrap();
    web_push::deliver_due(&pool, &sender, now + 7200)
        .await
        .unwrap();
    let schedules: i64 = sqlx::query_scalar("SELECT count(*) FROM timer_reminders")
        .fetch_one(&pool)
        .await
        .unwrap();
    assert_eq!(schedules, 0);
}

#[test]
fn only_https_push_services_and_valid_subjects_are_accepted() {
    assert!(web_push::endpoint_allowed(FCM));
    assert!(web_push::endpoint_allowed(
        "https://updates.push.services.mozilla.com/wpush/v2/x"
    ));
    assert!(web_push::endpoint_allowed("https://web.push.apple.com/x"));
    assert!(!web_push::endpoint_allowed(
        "https://push.apple.com.evil.example/x"
    ));
    assert!(!web_push::endpoint_allowed(
        "https://user:pw@fcm.googleapis.com/x"
    ));
    assert!(web_push::valid_subject("mailto:admin@example.test"));
    assert!(web_push::valid_subject("https://example.test"));
    assert!(!web_push::valid_subject("http://example.test"));
    assert!(!web_push::valid_subject("mailto:"));
}
