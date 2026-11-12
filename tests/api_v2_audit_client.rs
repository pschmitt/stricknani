//! The administrator audit log records which client caused an event; nothing
//! else ever exposes it.
use axum::{
    Router,
    body::Body,
    http::{Request, StatusCode},
};
use http_body_util::BodyExt;
use serde_json::{Value, json};
use sqlx::SqlitePool;
use stricknani_server::{api_v2, db};
use tower::ServiceExt;

const HOST: &str = "app.example.test";
const ORIGIN: &str = "https://app.example.test";

struct Session {
    cookie: String,
    csrf: String,
}

struct Fixture {
    _root: tempfile::TempDir,
    app: Router,
    pool: SqlitePool,
    owner: Session,
    admin: Session,
    member: Session,
}

async fn fixture() -> Fixture {
    let pool = db::connect("sqlite::memory:")
        .await
        .expect("database migrates");
    db::create_user(
        &pool,
        "rec-owner@example.test",
        "owner-password-long".into(),
    )
    .await
    .unwrap();
    let admin_id = db::create_user(
        &pool,
        "rec-admin@example.test",
        "admin-password-long".into(),
    )
    .await
    .unwrap();
    db::create_user(
        &pool,
        "rec-member@example.test",
        "member-password-long".into(),
    )
    .await
    .unwrap();
    sqlx::query("UPDATE users SET is_admin = 1 WHERE id = ?")
        .bind(admin_id)
        .execute(&pool)
        .await
        .unwrap();
    let root = tempfile::tempdir().unwrap();
    let app = api_v2::router(
        db::AppState::new(pool.clone())
            .with_secure_cookies(true)
            .with_media_root(root.path().to_path_buf()),
        false,
    );
    let owner = sign_in(&app, "rec-owner@example.test", "owner-password-long").await;
    let admin = sign_in(&app, "rec-admin@example.test", "admin-password-long").await;
    let member = sign_in(&app, "rec-member@example.test", "member-password-long").await;
    Fixture {
        _root: root,
        app,
        pool,
        owner,
        admin,
        member,
    }
}

async fn body_json(response: axum::response::Response) -> Value {
    let bytes = response.into_body().collect().await.unwrap().to_bytes();
    serde_json::from_slice(&bytes).unwrap_or(Value::Null)
}

fn cookie_value(response: &axum::response::Response, prefix: &str) -> String {
    response
        .headers()
        .get_all("set-cookie")
        .iter()
        .filter_map(|value| value.to_str().ok())
        .find_map(|value| value.strip_prefix(prefix))
        .and_then(|value| value.split(';').next())
        .expect("cookie should be set")
        .to_owned()
}

async fn sign_in(app: &Router, email: &str, password: &str) -> Session {
    let bootstrap = app
        .clone()
        .oneshot(Request::get("/session").body(Body::empty()).unwrap())
        .await
        .unwrap();
    let preauth = cookie_value(&bootstrap, "__Host-stricknani_v2_login_csrf=");
    let pre_csrf = body_json(bootstrap).await["data"]["csrf_token"]
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
                    json!({"email": email, "password": password}).to_string(),
                ))
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(login.status(), StatusCode::OK);
    let cookie = cookie_value(&login, "__Host-stricknani_session=");
    let csrf = body_json(login).await["data"]["csrf_token"]
        .as_str()
        .unwrap()
        .to_owned();
    Session { cookie, csrf }
}

async fn send(
    app: &Router,
    session: &Session,
    method: &str,
    path: &str,
    body: Option<Value>,
) -> (StatusCode, Value) {
    send_as(app, session, method, path, body, &[]).await
}

async fn send_as(
    app: &Router,
    session: &Session,
    method: &str,
    path: &str,
    body: Option<Value>,
    extra: &[(&str, &[u8])],
) -> (StatusCode, Value) {
    let mut request = Request::builder()
        .method(method)
        .uri(path)
        .header("host", HOST)
        .header("origin", ORIGIN)
        .header("sec-fetch-site", "same-origin")
        .header(
            "cookie",
            format!("__Host-stricknani_session={}", session.cookie),
        )
        .header("x-csrf-token", &session.csrf);
    for (name, value) in extra {
        request = request.header(*name, *value);
    }
    let body = match body {
        Some(value) => {
            request = request.header("content-type", "application/json");
            Body::from(value.to_string())
        }
        None => Body::empty(),
    };
    let response = app
        .clone()
        .oneshot(request.body(body).unwrap())
        .await
        .unwrap();
    let status = response.status();
    (status, body_json(response).await)
}

const FIREFOX: &str = "Mozilla/5.0 (X11; Linux x86_64; rv:159.0) Gecko/20100101 Firefox/159.0";

async fn create_as(fixture: &Fixture, name: &str, extra: &[(&str, &[u8])]) -> i64 {
    let (status, project) = send_as(
        &fixture.app,
        &fixture.owner,
        "POST",
        "/projects",
        Some(json!({"name": name})),
        extra,
    )
    .await;
    assert_eq!(status, StatusCode::CREATED);
    project["data"]["id"].as_i64().unwrap()
}

/// The administrator log entry for `action` on project `id`.
async fn admin_event(fixture: &Fixture, id: i64, action: &str) -> Value {
    let (status, list) = send(
        &fixture.app,
        &fixture.admin,
        "GET",
        &format!("/admin/audit?entity=project&action={action}"),
        None,
    )
    .await;
    assert_eq!(status, StatusCode::OK);
    list["data"]
        .as_array()
        .unwrap()
        .iter()
        .find(|event| event["entity_id"] == id)
        .unwrap_or_else(|| panic!("no {action} event for project {id}"))
        .clone()
}

#[tokio::test]
async fn client_header_is_recorded_and_shown_to_administrators() {
    let fixture = fixture().await;
    let id = create_as(
        &fixture,
        "Android made",
        &[
            ("x-stricknani-client", b"android/1.7.1 (code 123; debug)"),
            ("user-agent", b"okhttp/4.12.0"),
        ],
    )
    .await;
    let event = admin_event(&fixture, id, "created").await;
    assert_eq!(
        event["client"],
        json!({
            "kind": "android",
            "version": "1.7.1 (code 123; debug)",
            "label": "Android 1.7.1 (debug)",
            "user_agent": "okhttp/4.12.0",
        })
    );
    let (status, detail) = send(
        &fixture.app,
        &fixture.admin,
        "GET",
        &format!("/admin/audit/{}", event["id"]),
        None,
    )
    .await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(detail["data"]["event"]["client"], event["client"]);

    let web = create_as(
        &fixture,
        "Web made",
        &[
            ("x-stricknani-client", b"web/1.7.1 (build abc123)"),
            ("user-agent", FIREFOX.as_bytes()),
        ],
    )
    .await;
    let event = admin_event(&fixture, web, "created").await;
    assert_eq!(event["client"]["kind"], "web");
    assert_eq!(event["client"]["version"], "1.7.1 (build abc123)");
    assert_eq!(event["client"]["label"], "Firefox 159 \u{b7} Linux");
    assert_eq!(event["client"]["user_agent"], FIREFOX);
}

#[tokio::test]
async fn user_agent_is_the_fallback_when_there_is_no_client_header() {
    let fixture = fixture().await;
    let browser = create_as(&fixture, "Browser", &[("user-agent", FIREFOX.as_bytes())]).await;
    let event = admin_event(&fixture, browser, "created").await;
    assert_eq!(event["client"]["kind"], "web");
    assert_eq!(event["client"]["version"], Value::Null);
    assert_eq!(event["client"]["label"], "Firefox 159 \u{b7} Linux");

    let old_android = create_as(&fixture, "Old app", &[("user-agent", b"okhttp/4.12.0")]).await;
    let event = admin_event(&fixture, old_android, "created").await;
    assert_eq!(event["client"]["kind"], "android");
    assert_eq!(event["client"]["label"], "Android (okhttp 4.12.0)");

    let nothing = create_as(&fixture, "Nothing", &[]).await;
    let event = admin_event(&fixture, nothing, "created").await;
    assert_eq!(event["client"]["kind"], "api");
    assert_eq!(event["client"]["label"], Value::Null);
}

#[tokio::test]
async fn invalid_oversized_and_hostile_headers_never_break_recording() {
    let fixture = fixture().await;
    let oversized_header = format!("android/1.7.1 ({})", "a".repeat(500));
    let id = create_as(
        &fixture,
        "Hostile",
        &[
            ("x-stricknani-client", oversized_header.as_bytes()),
            ("user-agent", b"curl/8.4.0"),
        ],
    )
    .await;
    let event = admin_event(&fixture, id, "created").await;
    assert_eq!(event["client"]["kind"], "api", "bad header is ignored");
    assert_eq!(event["client"]["version"], Value::Null);
    assert_eq!(event["client"]["label"], "curl 8.4.0");

    let long_agent = format!("curl/8.4.0 {}", "x".repeat(2000));
    let injected = create_as(
        &fixture,
        "Injected",
        &[
            ("x-stricknani-client", b"web/1.0\"}<script>"),
            ("user-agent", long_agent.as_bytes()),
        ],
    )
    .await;
    let event = admin_event(&fixture, injected, "created").await;
    assert_eq!(event["client"]["version"], Value::Null);
    let stored = event["client"]["user_agent"].as_str().unwrap();
    assert_eq!(stored.chars().count(), 256);
    let bytes: Vec<(String,)> = sqlx::query_as(
        "SELECT client_user_agent FROM audit_events WHERE client_user_agent IS NOT NULL",
    )
    .fetch_all(&fixture.pool)
    .await
    .unwrap();
    assert!(bytes.iter().all(|(agent,)| agent.chars().count() <= 256));
}

#[tokio::test]
async fn events_without_a_recorded_client_read_as_unknown() {
    let fixture = fixture().await;
    let id = create_as(&fixture, "Old", &[]).await;
    sqlx::query(
        "UPDATE audit_events SET client_kind = NULL, client_version = NULL, \
         client_label = NULL, client_user_agent = NULL WHERE entity_id = ?",
    )
    .bind(id)
    .execute(&fixture.pool)
    .await
    .unwrap();
    let event = admin_event(&fixture, id, "created").await;
    assert_eq!(
        event["client"],
        json!({"kind": "unknown", "version": null, "label": null, "user_agent": null})
    );
}

#[tokio::test]
async fn ordinary_readers_never_see_the_client() {
    let fixture = fixture().await;
    let id = create_as(
        &fixture,
        "Shared",
        &[
            ("x-stricknani-client", b"android/1.7.1 (code 123; debug)"),
            ("user-agent", b"okhttp/4.12.0 secret-device-marker"),
        ],
    )
    .await;
    let member_id: i64 = sqlx::query_scalar("SELECT id FROM users WHERE email = ?")
        .bind("rec-member@example.test")
        .fetch_one(&fixture.pool)
        .await
        .unwrap();
    let (status, _) = send(
        &fixture.app,
        &fixture.owner,
        "POST",
        &format!("/projects/{id}/shares"),
        Some(json!({"user_id": member_id, "role": "editor"})),
    )
    .await;
    assert_eq!(status, StatusCode::CREATED);
    // The share event itself carries a client in the database as well.
    let event_id: i64 = sqlx::query_scalar(
        "SELECT id FROM audit_events WHERE entity_id = ? AND action = 'created'",
    )
    .bind(id)
    .fetch_one(&fixture.pool)
    .await
    .unwrap();

    let mut checked = 0;
    for session in [&fixture.owner, &fixture.member, &fixture.admin] {
        for path in [
            format!("/projects/{id}/history"),
            format!("/projects/{id}/history/{event_id}"),
            "/activity".to_owned(),
        ] {
            let (status, body) = send(&fixture.app, session, "GET", &path, None).await;
            assert_eq!(status, StatusCode::OK, "{path}");
            let text = body.to_string();
            assert!(!text.contains("client"), "{path} leaks a client: {text}");
            assert!(!text.contains("secret-device-marker"), "{path}: {text}");
            assert!(!text.contains("okhttp"), "{path}: {text}");
            checked += 1;
        }
    }
    assert_eq!(checked, 9);
    // Only the administrator audit shows it.
    let admin = admin_event(&fixture, id, "created").await;
    assert_eq!(admin["client"]["kind"], "android");

    // A member of no kind may read the administrator log.
    let (status, _) = send(&fixture.app, &fixture.member, "GET", "/admin/audit", None).await;
    assert_eq!(status, StatusCode::FORBIDDEN);
}
