//! Audit recording through the API v2 routes the native and web clients use:
//! every update path records a field-level diff, uploads and removals record
//! the media they were about, and every event has renderable details.
use axum::{
    Router,
    body::Body,
    http::{Request, StatusCode},
};
use http_body_util::BodyExt;
use serde_json::{Value, json};
use sqlx::SqlitePool;
use std::io::Cursor;
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

async fn upload(
    app: &Router,
    session: &Session,
    path: &str,
    file_name: &str,
    content_type: &str,
    bytes: &[u8],
) -> (StatusCode, Value) {
    let boundary = "audit-recording-boundary";
    let mut body = Vec::new();
    body.extend_from_slice(
        format!(
            "--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"{file_name}\"\r\nContent-Type: {content_type}\r\n\r\n"
        )
        .as_bytes(),
    );
    body.extend_from_slice(bytes);
    body.extend_from_slice(format!("\r\n--{boundary}--\r\n").as_bytes());
    let response = app
        .clone()
        .oneshot(
            Request::post(path)
                .header("host", HOST)
                .header("origin", ORIGIN)
                .header("sec-fetch-site", "same-origin")
                .header(
                    "cookie",
                    format!("__Host-stricknani_session={}", session.cookie),
                )
                .header("x-csrf-token", &session.csrf)
                .header(
                    "content-type",
                    format!("multipart/form-data; boundary={boundary}"),
                )
                .body(Body::from(body))
                .unwrap(),
        )
        .await
        .unwrap();
    let status = response.status();
    (status, body_json(response).await)
}

fn png_bytes() -> Vec<u8> {
    let image = image::DynamicImage::new_rgba8(2, 2);
    let mut output = Cursor::new(Vec::new());
    image
        .write_to(&mut output, image::ImageFormat::Png)
        .unwrap();
    output.into_inner()
}

struct Recorded {
    action: String,
    safe_changes: Option<Value>,
    media_kind: Option<String>,
    media_public_id: Option<i64>,
    media_name: Option<String>,
}

async fn recorded(pool: &SqlitePool, entity: &str, id: i64) -> Vec<Recorded> {
    use sqlx::Row;
    sqlx::query(
        "SELECT action, safe_changes, media_kind, media_public_id, media_name \
         FROM audit_events WHERE entity_type = ? AND entity_id = ? \
         AND action != 'step_trashed' ORDER BY id",
    )
    .bind(entity)
    .bind(id)
    .fetch_all(pool)
    .await
    .unwrap()
    .into_iter()
    .map(|row| Recorded {
        action: row.get("action"),
        safe_changes: row
            .get::<Option<String>, _>("safe_changes")
            .map(|raw| serde_json::from_str(&raw).unwrap()),
        media_kind: row.get("media_kind"),
        media_public_id: row.get("media_public_id"),
        media_name: row.get("media_name"),
    })
    .collect()
}

fn fields(event: &Recorded) -> Vec<String> {
    event
        .safe_changes
        .as_ref()
        .map(|changes| {
            changes["items"]
                .as_array()
                .unwrap()
                .iter()
                .map(|item| item["field"].as_str().unwrap().to_owned())
                .collect()
        })
        .unwrap_or_default()
}

fn item<'a>(event: &'a Recorded, field: &str) -> &'a Value {
    event.safe_changes.as_ref().unwrap()["items"]
        .as_array()
        .unwrap()
        .iter()
        .find(|item| item["field"] == field)
        .unwrap_or_else(|| panic!("change for {field} should be recorded"))
}

async fn create_project(fixture: &Fixture) -> i64 {
    let (status, project) = send(
        &fixture.app,
        &fixture.owner,
        "POST",
        "/projects",
        Some(
            json!({"name": "Rollkragenpulli", "notes": "first", "steps": [
                {"title": "Cast on", "description": "80 stitches", "step_number": 1}
            ]}),
        ),
    )
    .await;
    assert_eq!(status, StatusCode::CREATED);
    project["data"]["id"].as_i64().unwrap()
}

fn put_body(notes: &str) -> Value {
    json!({"name": "Rollkragenpulli", "notes": notes, "steps": [
        {"title": "Cast on", "description": "80 stitches", "step_number": 1}
    ]})
}

#[tokio::test]
async fn project_update_records_a_field_diff_and_a_no_op_save_records_nothing() {
    let fixture = fixture().await;
    let id = create_project(&fixture).await;
    let path = format!("/projects/{id}");

    let (status, _) = send(
        &fixture.app,
        &fixture.owner,
        "PUT",
        &path,
        Some(put_body("second")),
    )
    .await;
    assert_eq!(status, StatusCode::OK);
    let events = recorded(&fixture.pool, "project", id).await;
    assert_eq!(events.len(), 2);
    assert_eq!(events[1].action, "updated");
    assert_eq!(fields(&events[1]), vec!["project.notes"]);
    let change = item(&events[1], "project.notes");
    assert_eq!(change["before"]["value"], "first");
    assert_eq!(change["after"]["value"], "second");

    // The same payload again changes nothing: no event.
    let (status, _) = send(
        &fixture.app,
        &fixture.owner,
        "PUT",
        &path,
        Some(put_body("second")),
    )
    .await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(recorded(&fixture.pool, "project", id).await.len(), 2);
}

#[tokio::test]
async fn step_edits_tags_and_link_changes_are_recorded_without_the_link_value() {
    let fixture = fixture().await;
    let id = create_project(&fixture).await;
    let (status, _) = send(
        &fixture.app,
        &fixture.owner,
        "PUT",
        &format!("/projects/{id}"),
        Some(
            json!({"name": "Rollkragenpulli", "notes": "first", "tags": ["wool"],
            "link": "https://example.test/pattern?token=secret-value",
            "steps": [
                {"title": "Cast on", "description": "90 stitches", "step_number": 1},
                {"title": "Knit", "description": "plain", "step_number": 2}
            ]}),
        ),
    )
    .await;
    assert_eq!(status, StatusCode::OK);
    let events = recorded(&fixture.pool, "project", id).await;
    let update = &events[1];
    let changed = fields(update);
    for field in [
        "project.tags",
        "project.link",
        "project.steps",
        "project.step_count",
    ] {
        assert!(
            changed.contains(&field.to_owned()),
            "{field} in {changed:?}"
        );
    }
    assert_eq!(
        item(update, "project.step_count")["after"]["value"],
        json!(2)
    );
    let raw = serde_json::to_string(update.safe_changes.as_ref().unwrap()).unwrap();
    assert!(
        !raw.contains("secret-value"),
        "link values are never stored"
    );
}

#[tokio::test]
async fn category_change_on_a_project_is_recorded_as_before_and_after() {
    let fixture = fixture().await;
    let id = create_project(&fixture).await;
    let mut body = put_body("first");
    body["category"] = json!("Sweaters");
    let (status, _) = send(
        &fixture.app,
        &fixture.owner,
        "PUT",
        &format!("/projects/{id}"),
        Some(body),
    )
    .await;
    assert_eq!(status, StatusCode::OK);
    let update = &recorded(&fixture.pool, "project", id).await[1];
    let change = item(update, "project.category");
    assert_eq!(change["before"]["value"], Value::Null);
    assert_eq!(change["after"]["value"], "Sweaters");
}

#[tokio::test]
async fn yarn_update_records_a_diff_and_a_no_op_save_records_nothing() {
    let fixture = fixture().await;
    let (status, yarn) = send(
        &fixture.app,
        &fixture.owner,
        "POST",
        "/yarns",
        Some(json!({"name": "Wool", "brand": "Old"})),
    )
    .await;
    assert_eq!(status, StatusCode::CREATED);
    let id = yarn["data"]["id"].as_i64().unwrap();
    let path = format!("/yarns/{id}");
    let (status, _) = send(
        &fixture.app,
        &fixture.owner,
        "PUT",
        &path,
        Some(json!({"name": "Wool", "brand": "New", "weight_grams": 50})),
    )
    .await;
    assert_eq!(status, StatusCode::OK);
    let events = recorded(&fixture.pool, "yarn", id).await;
    assert_eq!(events.len(), 2);
    assert_eq!(fields(&events[1]), vec!["yarn.brand", "yarn.weight_grams"]);
    assert_eq!(item(&events[1], "yarn.brand")["before"]["value"], "Old");
    let (status, _) = send(
        &fixture.app,
        &fixture.owner,
        "PUT",
        &path,
        Some(json!({"name": "Wool", "brand": "New", "weight_grams": 50})),
    )
    .await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(recorded(&fixture.pool, "yarn", id).await.len(), 2);
}

#[tokio::test]
async fn category_rename_is_recorded_and_a_same_name_save_is_not() {
    let fixture = fixture().await;
    let (status, category) = send(
        &fixture.app,
        &fixture.owner,
        "POST",
        "/categories",
        Some(json!({"name": "Hats"})),
    )
    .await;
    assert_eq!(status, StatusCode::CREATED);
    let id = category["data"]["id"].as_i64().unwrap();
    let path = format!("/categories/{id}");
    let (status, _) = send(
        &fixture.app,
        &fixture.owner,
        "PUT",
        &path,
        Some(json!({"name": "Caps"})),
    )
    .await;
    assert_eq!(status, StatusCode::OK);
    let events = recorded(&fixture.pool, "category", id).await;
    assert_eq!(events.len(), 2);
    assert_eq!(item(&events[1], "category.name")["after"]["value"], "Caps");
    let (status, _) = send(
        &fixture.app,
        &fixture.owner,
        "PUT",
        &path,
        Some(json!({"name": "Caps"})),
    )
    .await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(recorded(&fixture.pool, "category", id).await.len(), 2);
}

#[tokio::test]
async fn very_long_text_is_abbreviated_instead_of_dropping_the_diff() {
    let fixture = fixture().await;
    let id = create_project(&fixture).await;
    let long = "knit ".repeat(80_000);
    let (status, _) = send(
        &fixture.app,
        &fixture.owner,
        "PUT",
        &format!("/projects/{id}"),
        Some(put_body(&long)),
    )
    .await;
    assert_eq!(status, StatusCode::OK);
    let update = &recorded(&fixture.pool, "project", id).await[1];
    let after = item(update, "project.notes")["after"]["value"]
        .as_str()
        .unwrap();
    assert!(after.chars().count() < 10_000);
    assert!(after.ends_with('\u{2026}'));
}

#[tokio::test]
async fn project_media_events_name_the_file_and_reorders_record_the_new_order() {
    let fixture = fixture().await;
    let id = create_project(&fixture).await;
    let png = png_bytes();
    let mut ids = Vec::new();
    for name in ["front.png", "back.png"] {
        let (status, media) = upload(
            &fixture.app,
            &fixture.owner,
            &format!("/projects/{id}/images/title"),
            name,
            "image/png",
            &png,
        )
        .await;
        assert_eq!(status, StatusCode::CREATED);
        ids.push(media["data"]["id"].as_i64().unwrap());
    }
    let (status, attachment) = upload(
        &fixture.app,
        &fixture.owner,
        &format!("/projects/{id}/attachments"),
        "chart.png",
        "image/png",
        &png,
    )
    .await;
    assert_eq!(status, StatusCode::CREATED);
    let attachment_id = attachment["data"]["id"].as_i64().unwrap();

    let events = recorded(&fixture.pool, "project", id).await;
    assert_eq!(events[1].action, "uploaded");
    assert_eq!(events[1].media_kind.as_deref(), Some("image"));
    assert_eq!(events[1].media_public_id, Some(ids[0]));
    assert_eq!(events[1].media_name.as_deref(), Some("front.png"));
    assert_eq!(events[3].media_kind.as_deref(), Some("attachment"));
    assert_eq!(events[3].media_name.as_deref(), Some("chart.png"));

    // Reorder: the second picture becomes the cover.
    let (status, _) = send(
        &fixture.app,
        &fixture.owner,
        "PUT",
        &format!("/projects/{id}/images/order"),
        Some(json!({"ids": [ids[1], ids[0]]})),
    )
    .await;
    assert_eq!(status, StatusCode::OK);
    let events = recorded(&fixture.pool, "project", id).await;
    let order = events.last().unwrap();
    assert_eq!(order.action, "updated");
    let image_order = item(order, "project.image_order");
    assert_eq!(
        image_order["before"]["value"],
        json!(["front.png", "back.png"])
    );
    assert_eq!(
        image_order["after"]["value"],
        json!(["back.png", "front.png"])
    );
    let cover = item(order, "project.cover_image");
    assert_eq!(cover["before"]["value"], "front.png");
    assert_eq!(cover["after"]["value"], "back.png");

    // Removals keep the file's identity.
    let (status, _) = send(
        &fixture.app,
        &fixture.owner,
        "DELETE",
        &format!("/projects/{id}/attachments/{attachment_id}"),
        None,
    )
    .await;
    assert_eq!(status, StatusCode::NO_CONTENT);
    let events = recorded(&fixture.pool, "project", id).await;
    let removed = events.last().unwrap();
    assert_eq!(removed.action, "trashed");
    assert_eq!(removed.media_kind.as_deref(), Some("attachment"));
    assert_eq!(removed.media_name.as_deref(), Some("chart.png"));
}

#[tokio::test]
async fn yarn_photo_events_and_order_changes_are_recorded() {
    let fixture = fixture().await;
    let (_, yarn) = send(
        &fixture.app,
        &fixture.owner,
        "POST",
        "/yarns",
        Some(json!({"name": "Wool"})),
    )
    .await;
    let id = yarn["data"]["id"].as_i64().unwrap();
    let png = png_bytes();
    let mut ids = Vec::new();
    for name in ["a.png", "b.png"] {
        let (status, photo) = upload(
            &fixture.app,
            &fixture.owner,
            &format!("/yarns/{id}/photos"),
            name,
            "image/png",
            &png,
        )
        .await;
        assert_eq!(status, StatusCode::CREATED);
        ids.push(photo["data"]["id"].as_i64().unwrap());
    }
    let events = recorded(&fixture.pool, "yarn", id).await;
    assert_eq!(events[1].media_kind.as_deref(), Some("photo"));
    assert_eq!(events[2].media_name.as_deref(), Some("b.png"));
    let (status, _) = send(
        &fixture.app,
        &fixture.owner,
        "PUT",
        &format!("/yarns/{id}/photos/order"),
        Some(json!({"ids": [ids[1], ids[0]]})),
    )
    .await;
    assert_eq!(status, StatusCode::OK);
    let events = recorded(&fixture.pool, "yarn", id).await;
    let order = events.last().unwrap();
    assert!(fields(order).contains(&"yarn.photo_order".to_owned()));
    let (status, _) = send(
        &fixture.app,
        &fixture.owner,
        "DELETE",
        &format!("/yarns/{id}/photos/{}", ids[0]),
        None,
    )
    .await;
    assert_eq!(status, StatusCode::NO_CONTENT);
    let removed = recorded(&fixture.pool, "yarn", id).await.pop().unwrap();
    assert_eq!(removed.media_kind.as_deref(), Some("photo"));
    assert_eq!(removed.media_name.as_deref(), Some("a.png"));
}

/// The sequence Android produces when a project is edited: save, upload two
/// pictures, remove two, save. Every event must show something.
#[tokio::test]
async fn android_edit_sequence_leaves_only_events_with_renderable_details() {
    let fixture = fixture().await;
    let id = create_project(&fixture).await;
    let path = format!("/projects/{id}");
    let png = png_bytes();
    send(
        &fixture.app,
        &fixture.owner,
        "PUT",
        &path,
        Some(put_body("edited once")),
    )
    .await;
    let mut ids = Vec::new();
    for name in ["one.png", "two.png"] {
        let (_, media) = upload(
            &fixture.app,
            &fixture.owner,
            &format!("{path}/images/title"),
            name,
            "image/png",
            &png,
        )
        .await;
        ids.push(media["data"]["id"].as_i64().unwrap());
    }
    for media_id in &ids {
        let (status, _) = send(
            &fixture.app,
            &fixture.owner,
            "DELETE",
            &format!("{path}/images/{media_id}"),
            None,
        )
        .await;
        assert_eq!(status, StatusCode::NO_CONTENT);
    }
    // A repeated, unchanged save (Android resends the form) is not an event.
    send(
        &fixture.app,
        &fixture.owner,
        "PUT",
        &path,
        Some(put_body("edited once")),
    )
    .await;
    send(
        &fixture.app,
        &fixture.owner,
        "PUT",
        &path,
        Some(put_body("edited twice")),
    )
    .await;

    let (_, live) = upload(
        &fixture.app,
        &fixture.owner,
        &format!("{path}/images/title"),
        "live.png",
        "image/png",
        &png,
    )
    .await;
    let live_id = live["data"]["id"].as_i64().unwrap();
    let (status, audit) = send(&fixture.app, &fixture.admin, "GET", "/admin/audit", None).await;
    assert_eq!(status, StatusCode::OK);
    let events: Vec<&Value> = audit["data"]
        .as_array()
        .unwrap()
        .iter()
        .filter(|event| event["entity_type"] == "project" && event["action"] != "step_trashed")
        .collect();
    let actions: Vec<_> = events
        .iter()
        .rev()
        .map(|event| event["action"].as_str().unwrap())
        .filter(|action| *action != "step_trashed")
        .collect();
    assert_eq!(
        actions,
        [
            "created", "updated", "uploaded", "uploaded", "trashed", "trashed", "updated",
            "uploaded"
        ]
    );
    for event in &events {
        // Administrators open any owner's project on the normal page.
        assert_eq!(event["resource"]["href"], format!("/app/projects/{id}"));
        let has_media = !event["media"].is_null();
        assert!(
            event["has_changes"] == true || has_media,
            "event {event} has nothing to render"
        );
        if event["media"]["id"] != live_id
            && (event["action"] == "uploaded" || event["action"] == "trashed")
        {
            assert!(has_media);
            // Both pictures were removed afterwards: say so instead of linking.
            assert_eq!(event["media"]["exists"], false);
            assert!(event["media"]["url"].is_null());
        }
    }
    // Uploaded media of another user's project opens through the read-only admin route.
    let uploaded = events
        .iter()
        .find(|event| event["media"]["id"] == live_id)
        .unwrap();
    assert_eq!(uploaded["media"]["exists"], true);
    let url = uploaded["media"]["url"].as_str().unwrap().to_owned();
    assert!(url.starts_with(&format!(
        "/api/v2/admin/resources/project/{id}/media/image/"
    )));
    assert!(uploaded["media"]["thumbnail_url"].is_string());
}

#[tokio::test]
async fn admin_media_route_is_admin_only_and_read_only_across_owners() {
    let fixture = fixture().await;
    let id = create_project(&fixture).await;
    let png = png_bytes();
    let (_, media) = upload(
        &fixture.app,
        &fixture.owner,
        &format!("/projects/{id}/images/title"),
        "front.png",
        "image/png",
        &png,
    )
    .await;
    let media_id = media["data"]["id"].as_i64().unwrap();
    let url = format!("/admin/resources/project/{id}/media/image/{media_id}");
    let (admin_status, _) = send(&fixture.app, &fixture.admin, "GET", &url, None).await;
    assert_eq!(admin_status, StatusCode::OK);
    let (member_status, _) = send(&fixture.app, &fixture.member, "GET", &url, None).await;
    assert_eq!(member_status, StatusCode::FORBIDDEN);
    let (owner_status, _) = send(&fixture.app, &fixture.owner, "GET", &url, None).await;
    assert_eq!(owner_status, StatusCode::FORBIDDEN);
    // Another user's media cannot be mutated through the admin route.
    let (delete_status, _) = send(&fixture.app, &fixture.admin, "DELETE", &url, None).await;
    assert!(matches!(
        delete_status,
        StatusCode::METHOD_NOT_ALLOWED | StatusCode::NOT_FOUND | StatusCode::FORBIDDEN
    ));
    assert_eq!(
        sqlx::query_scalar::<_, i64>("SELECT count(*) FROM media_files WHERE public_id = ?")
            .bind(media_id)
            .fetch_one(&fixture.pool)
            .await
            .unwrap(),
        1
    );
}

#[tokio::test]
async fn owner_history_links_uploaded_media_through_the_owner_routes() {
    let fixture = fixture().await;
    let id = create_project(&fixture).await;
    let png = png_bytes();
    let (_, media) = upload(
        &fixture.app,
        &fixture.owner,
        &format!("/projects/{id}/images/title"),
        "front.png",
        "image/png",
        &png,
    )
    .await;
    let media_id = media["data"]["id"].as_i64().unwrap();
    let (status, history) = send(
        &fixture.app,
        &fixture.owner,
        "GET",
        &format!("/projects/{id}/history"),
        None,
    )
    .await;
    assert_eq!(status, StatusCode::OK);
    let uploaded = history["data"]
        .as_array()
        .unwrap()
        .iter()
        .find(|event| event["action"] == "uploaded")
        .unwrap();
    assert_eq!(uploaded["media"]["id"], media_id);
    assert_eq!(uploaded["media"]["name"], "front.png");
    assert!(
        uploaded["media"]["url"]
            .as_str()
            .unwrap()
            .starts_with(&format!("/media/projects/{id}/"))
    );
    let (status, _) = send(
        &fixture.app,
        &fixture.owner,
        "DELETE",
        &format!("/projects/{id}/images/{media_id}"),
        None,
    )
    .await;
    assert_eq!(status, StatusCode::NO_CONTENT);
    let (_, history) = send(
        &fixture.app,
        &fixture.owner,
        "GET",
        &format!("/projects/{id}/history"),
        None,
    )
    .await;
    let uploaded = history["data"]
        .as_array()
        .unwrap()
        .iter()
        .find(|event| event["action"] == "uploaded")
        .unwrap();
    assert_eq!(uploaded["media"]["exists"], false);
    assert!(uploaded["media"]["url"].is_null());
    assert_eq!(uploaded["media"]["name"], "front.png");
}
