//! Sharing and ownership transfer of projects and yarns (API v2): the
//! authorization matrix, derived yarn access, people search, transfer, sync
//! appearance and tombstones, history, v1 isolation and account deletion.

use axum::{
    body::Body,
    http::{Request, StatusCode},
};
use http_body_util::BodyExt;
use serde_json::{Value, json};
use sha2::{Digest, Sha256};
use sqlx::SqlitePool;
use std::io::Cursor;
use stricknani_server::{app_with_state, db};
use tower::ServiceExt;

const NAMES: [&str; 11] = [
    "anika", "ben", "vera", "eddie", "yvonne", "yves", "frank", "dis", "admin", "nora", "nick",
];

struct Fx {
    _root: tempfile::TempDir,
    app: axum::Router,
    pool: SqlitePool,
    anika: i64,
    ben: i64,
    vera: i64,
    eddie: i64,
    yvonne: i64,
    yves: i64,
    frank: i64,
    dis: i64,
    admin: i64,
    nora: i64,
    nick: i64,
    /// Anika's project, linked to `y1`; shared with vera (viewer) and eddie (editor).
    p1: i64,
    /// Anika's second project, linked to `y1` and `y2`; shared with nobody.
    q1: i64,
    /// Linked to `p1` and `q1`.
    y1: i64,
    /// Linked to `q1`; shared with yvonne (viewer) and yves (editor).
    y2: i64,
    /// Anika's yarn linked nowhere.
    y3: i64,
}

fn pat(name: &str) -> String {
    format!("sharing-{name}-api-pat")
}

async fn seed_user(pool: &SqlitePool, name: &str, admin: bool, active: bool) -> i64 {
    let id = sqlx::query(
        "INSERT INTO users (email, password_hash, is_admin, active) \
         VALUES (?, 'test-only-hash', ?, ?)",
    )
    .bind(format!("{name}@example.test"))
    .bind(admin)
    .bind(active)
    .execute(pool)
    .await
    .unwrap()
    .last_insert_rowid();
    sqlx::query(
        "INSERT INTO api_tokens (user_id, name, token_hash, created_at, expires_at, purpose) \
         VALUES (?, 'sharing fixture', ?, unixepoch(), unixepoch() + 86400, 'api')",
    )
    .bind(id)
    .bind(hex::encode(Sha256::digest(pat(name).as_bytes())))
    .execute(pool)
    .await
    .unwrap();
    id
}

async fn insert_share(pool: &SqlitePool, kind: &str, id: i64, user: i64, role: &str, by: i64) {
    sqlx::query(
        "INSERT INTO resource_shares (kind, resource_id, user_id, role, created_by, created_at, updated_at) \
         VALUES (?, ?, ?, ?, ?, '2026-01-01T00:00:00.000000Z', '2026-01-01T00:00:00.000000Z')",
    )
    .bind(kind)
    .bind(id)
    .bind(user)
    .bind(role)
    .bind(by)
    .execute(pool)
    .await
    .unwrap();
}

const OLD: &str = "2026-01-01T00:00:00.000000Z";

async fn project(pool: &SqlitePool, owner: i64, name: &str) -> i64 {
    sqlx::query_scalar(
        "INSERT INTO projects (owner_id,name,tags,created_at,updated_at) \
         VALUES (?,?,'[]',?,?) RETURNING id",
    )
    .bind(owner)
    .bind(name)
    .bind(OLD)
    .bind(OLD)
    .fetch_one(pool)
    .await
    .unwrap()
}

async fn yarn(pool: &SqlitePool, owner: i64, name: &str) -> i64 {
    sqlx::query_scalar(
        "INSERT INTO yarns (owner_id,name,created_at,updated_at) VALUES (?,?,?,?) RETURNING id",
    )
    .bind(owner)
    .bind(name)
    .bind(OLD)
    .bind(OLD)
    .fetch_one(pool)
    .await
    .unwrap()
}

async fn link(pool: &SqlitePool, project: i64, yarn: i64) {
    sqlx::query("INSERT INTO project_yarns (project_id, yarn_id) VALUES (?, ?)")
        .bind(project)
        .bind(yarn)
        .execute(pool)
        .await
        .unwrap();
}

async fn fx() -> Fx {
    let pool = db::connect("sqlite::memory:").await.unwrap();
    let mut ids = Vec::new();
    for name in NAMES {
        ids.push(seed_user(&pool, name, name == "admin", name != "dis").await);
    }
    let [
        anika,
        ben,
        vera,
        eddie,
        yvonne,
        yves,
        frank,
        dis,
        admin,
        nora,
        nick,
    ] = ids[..]
    else {
        unreachable!()
    };
    let p1 = project(&pool, anika, "Shawl").await;
    let q1 = project(&pool, anika, "Hat").await;
    let y1 = yarn(&pool, anika, "Merino").await;
    let y2 = yarn(&pool, anika, "Alpaca").await;
    let y3 = yarn(&pool, anika, "Stash").await;
    link(&pool, p1, y1).await;
    link(&pool, q1, y1).await;
    link(&pool, q1, y2).await;
    insert_share(&pool, "project", p1, vera, "viewer", anika).await;
    insert_share(&pool, "project", p1, eddie, "editor", anika).await;
    insert_share(&pool, "project", p1, dis, "viewer", anika).await;
    insert_share(&pool, "yarn", y2, yvonne, "viewer", anika).await;
    insert_share(&pool, "yarn", y2, yves, "editor", anika).await;
    let root = tempfile::tempdir().unwrap();
    let state = db::AppState::new(pool.clone())
        .with_secure_cookies(true)
        .with_media_root(root.path().to_path_buf());
    Fx {
        _root: root,
        app: app_with_state(state),
        pool,
        anika,
        ben,
        vera,
        eddie,
        yvonne,
        yves,
        frank,
        dis,
        admin,
        nora,
        nick,
        p1,
        q1,
        y1,
        y2,
        y3,
    }
}

async fn call(
    app: &axum::Router,
    method: &str,
    path: &str,
    who: Option<&str>,
    body: Option<Value>,
) -> (StatusCode, Value) {
    let mut request = Request::builder().method(method).uri(path);
    if let Some(who) = who {
        request = request.header("authorization", format!("Bearer {}", pat(who)));
    }
    if body.is_some() {
        request = request.header("content-type", "application/json");
    }
    let response = app
        .clone()
        .oneshot(
            request
                .body(body.map_or_else(Body::empty, |b| Body::from(b.to_string())))
                .unwrap(),
        )
        .await
        .unwrap();
    let status = response.status();
    let bytes = response.into_body().collect().await.unwrap().to_bytes();
    (
        status,
        serde_json::from_slice(&bytes).unwrap_or(Value::Null),
    )
}

async fn raw(
    app: &axum::Router,
    method: &str,
    path: &str,
    who: Option<&str>,
) -> (StatusCode, Vec<u8>) {
    let mut request = Request::builder().method(method).uri(path);
    if let Some(who) = who {
        request = request.header("authorization", format!("Bearer {}", pat(who)));
    }
    let response = app
        .clone()
        .oneshot(request.body(Body::empty()).unwrap())
        .await
        .unwrap();
    let status = response.status();
    let bytes = response.into_body().collect().await.unwrap().to_bytes();
    (status, bytes.to_vec())
}

fn png_bytes() -> Vec<u8> {
    let image = image::DynamicImage::new_rgba8(2, 2);
    let mut output = Cursor::new(Vec::new());
    image
        .write_to(&mut output, image::ImageFormat::Png)
        .expect("test PNG should encode");
    output.into_inner()
}

async fn upload(app: &axum::Router, path: &str, who: Option<&str>) -> (StatusCode, Value) {
    let boundary = "sharing-boundary";
    let mut body = Vec::new();
    body.extend_from_slice(
        format!(
            "--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; \
             filename=\"a.png\"\r\nContent-Type: image/png\r\n\r\n"
        )
        .as_bytes(),
    );
    body.extend_from_slice(&png_bytes());
    body.extend_from_slice(format!("\r\n--{boundary}--\r\n").as_bytes());
    let mut request = Request::builder().method("POST").uri(path).header(
        "content-type",
        format!("multipart/form-data; boundary={boundary}"),
    );
    if let Some(who) = who {
        request = request.header("authorization", format!("Bearer {}", pat(who)));
    }
    let response = app
        .clone()
        .oneshot(request.body(Body::from(body)).unwrap())
        .await
        .unwrap();
    let status = response.status();
    let bytes = response.into_body().collect().await.unwrap().to_bytes();
    (
        status,
        serde_json::from_slice(&bytes).unwrap_or(Value::Null),
    )
}

fn ids(list: &Value) -> Vec<i64> {
    let mut ids: Vec<i64> = list["data"]
        .as_array()
        .unwrap()
        .iter()
        .map(|item| item["id"].as_i64().unwrap())
        .collect();
    ids.sort_unstable();
    ids
}

fn updated_ids(sync: &Value) -> Vec<i64> {
    let mut ids: Vec<i64> = sync["data"]["updated"]
        .as_array()
        .unwrap()
        .iter()
        .map(|item| item["id"].as_i64().unwrap())
        .collect();
    ids.sort_unstable();
    ids
}

fn deleted_ids(sync: &Value) -> Vec<i64> {
    let mut ids: Vec<i64> = sync["data"]["deleted_ids"]
        .as_array()
        .unwrap()
        .iter()
        .map(|id| id.as_i64().unwrap())
        .collect();
    ids.sort_unstable();
    ids
}

/// A sync request since `since`; returns the response and its `server_time`.
async fn sync(f: &Fx, kind: &str, who: &str, since: Option<&str>) -> (Value, String) {
    let path = match since {
        Some(since) => format!("/api/v2/sync/{kind}?since={since}&limit=50"),
        None => format!("/api/v2/sync/{kind}?limit=50"),
    };
    let (status, body) = call(&f.app, "GET", &path, Some(who), None).await;
    assert_eq!(status, StatusCode::OK, "{body}");
    let time = body["data"]["server_time"].as_str().unwrap().to_owned();
    (body, time)
}

/// Let the clock move so that events are strictly after the previous snapshot.
async fn tick() {
    tokio::time::sleep(std::time::Duration::from_millis(5)).await;
}

async fn share(f: &Fx, kind: &str, id: i64, who: &str, user: i64, role: &str) -> StatusCode {
    call(
        &f.app,
        "POST",
        &format!("/api/v2/{kind}s/{id}/shares"),
        Some(who),
        Some(json!({"user_id": user, "role": role})),
    )
    .await
    .0
}

// --- authorization matrix ---------------------------------------------------

struct Op {
    label: &'static str,
    method: &'static str,
    path: String,
    body: Option<Value>,
    multipart: bool,
}

fn op(label: &'static str, method: &'static str, path: String, body: Option<Value>) -> Op {
    Op {
        label,
        method,
        path,
        body,
        multipart: false,
    }
}

async fn run_op(f: &Fx, who: Option<&str>, op: &Op) -> StatusCode {
    if op.multipart {
        upload(&f.app, &op.path, who).await.0
    } else {
        call(&f.app, op.method, &op.path, who, op.body.clone())
            .await
            .0
    }
}

/// Anonymous reads are 401; anonymous mutations fail the CSRF check first.
fn anonymous(ops: &[Op]) -> Vec<u16> {
    ops.iter()
        .map(|op| if op.method == "GET" { 401 } else { 403 })
        .collect()
}

async fn assert_matrix(f: &Fx, who: Option<&str>, ops: &[Op], expected: &[u16]) {
    assert_eq!(ops.len(), expected.len());
    for (op, expected) in ops.iter().zip(expected) {
        let status = run_op(f, who, op).await;
        assert_eq!(
            status.as_u16(),
            *expected,
            "{} {} ({}) as {:?}",
            op.method,
            op.path,
            op.label,
            who
        );
    }
}

fn project_ops(f: &Fx, id: i64) -> Vec<Op> {
    let mut image = op(
        "image upload",
        "POST",
        format!("/api/v2/projects/{id}/images/title"),
        None,
    );
    image.multipart = true;
    vec![
        op("get", "GET", format!("/api/v2/projects/{id}"), None),
        op(
            "history",
            "GET",
            format!("/api/v2/projects/{id}/history"),
            None,
        ),
        op(
            "favorite",
            "PUT",
            format!("/api/v2/projects/{id}/favorite"),
            None,
        ),
        op(
            "update",
            "PUT",
            format!("/api/v2/projects/{id}"),
            Some(json!({"name": "Shawl", "yarn_ids": [f.y1]})),
        ),
        image,
        op(
            "share list",
            "GET",
            format!("/api/v2/projects/{id}/shares"),
            None,
        ),
        op(
            "share add",
            "POST",
            format!("/api/v2/projects/{id}/shares"),
            Some(json!({"user_id": f.nora, "role": "viewer"})),
        ),
        op(
            "share change",
            "PATCH",
            format!("/api/v2/projects/{id}/shares/{}", f.vera),
            Some(json!({"role": "editor"})),
        ),
        op(
            "share revoke",
            "DELETE",
            format!("/api/v2/projects/{id}/shares/{}", f.vera),
            None,
        ),
        op(
            "transfer",
            "POST",
            format!("/api/v2/projects/{id}/transfer"),
            Some(json!({"new_owner_id": f.nora})),
        ),
        op("delete", "DELETE", format!("/api/v2/projects/{id}"), None),
    ]
}

#[tokio::test]
async fn project_authorization_matrix() {
    let f = fx().await;
    let ops = project_ops(&f, f.p1);
    //                       get hist fav upd img slist sadd schg srev xfer del
    assert_matrix(&f, None, &ops, &anonymous(&ops)).await;
    // A disabled account's token is rejected outright, whatever it was granted.
    assert_matrix(&f, Some("dis"), &ops, &[401; 11]).await;
    // Strangers cannot tell the project exists.
    assert_matrix(&f, Some("ben"), &ops, &[404; 11]).await;
    // A former recipient is a stranger again.
    assert_eq!(
        share(&f, "project", f.p1, "anika", f.frank, "viewer").await,
        StatusCode::CREATED
    );
    let (status, _) = call(
        &f.app,
        "DELETE",
        &format!("/api/v2/projects/{}/shares/{}", f.p1, f.frank),
        Some("anika"),
        None,
    )
    .await;
    assert_eq!(status, StatusCode::NO_CONTENT);
    assert_matrix(&f, Some("frank"), &ops, &[404; 11]).await;
    // Viewers read and favourite, nothing else.
    assert_matrix(
        &f,
        Some("vera"),
        &ops,
        &[200, 200, 200, 403, 403, 403, 403, 403, 403, 403, 403],
    )
    .await;
    // Editors also change the project and its media, but never share,
    // transfer or delete.
    assert_matrix(
        &f,
        Some("eddie"),
        &ops,
        &[200, 200, 200, 200, 201, 403, 403, 403, 403, 403, 403],
    )
    .await;
    // Owner and administrator: everything non-destructive here.
    for who in ["anika", "admin"] {
        assert_matrix(&f, Some(who), &ops[..6], &[200, 200, 200, 200, 201, 200]).await;
    }
    let still_there: i64 = sqlx::query_scalar("SELECT count(*) FROM projects WHERE id = ?")
        .bind(f.p1)
        .fetch_one(&f.pool)
        .await
        .unwrap();
    assert_eq!(still_there, 1);
}

#[tokio::test]
async fn owner_and_admin_manage_projects_editors_and_viewers_cannot() {
    let f = fx().await;
    // Admin shares, changes, revokes; the audit actor is the admin.
    assert_eq!(
        share(&f, "project", f.q1, "admin", f.ben, "viewer").await,
        StatusCode::CREATED
    );
    let (status, _) = call(
        &f.app,
        "PATCH",
        &format!("/api/v2/projects/{}/shares/{}", f.q1, f.ben),
        Some("admin"),
        Some(json!({"role": "editor"})),
    )
    .await;
    assert_eq!(status, StatusCode::OK);
    let (status, _) = call(
        &f.app,
        "DELETE",
        &format!("/api/v2/projects/{}/shares/{}", f.q1, f.ben),
        Some("admin"),
        None,
    )
    .await;
    assert_eq!(status, StatusCode::NO_CONTENT);
    let actors: Vec<(String, i64)> = sqlx::query_as(
        "SELECT action, actor_user_id FROM audit_events WHERE entity_type='project' \
         AND entity_id=? ORDER BY id",
    )
    .bind(f.q1)
    .fetch_all(&f.pool)
    .await
    .unwrap();
    assert_eq!(
        actors,
        vec![
            ("shared".to_owned(), f.admin),
            ("share_role_changed".to_owned(), f.admin),
            ("share_revoked".to_owned(), f.admin),
        ]
    );
    // Editor delete is refused and the project survives; owner delete works.
    let (status, body) = call(
        &f.app,
        "DELETE",
        &format!("/api/v2/projects/{}", f.p1),
        Some("eddie"),
        None,
    )
    .await;
    assert_eq!(status, StatusCode::FORBIDDEN);
    assert_eq!(body["error"]["code"], "forbidden");
    let (status, _) = call(
        &f.app,
        "DELETE",
        &format!("/api/v2/projects/{}", f.p1),
        Some("anika"),
        None,
    )
    .await;
    assert_eq!(status, StatusCode::NO_CONTENT);
}

fn yarn_ops(f: &Fx, id: i64) -> Vec<Op> {
    let mut photo = op(
        "photo upload",
        "POST",
        format!("/api/v2/yarns/{id}/photos"),
        None,
    );
    photo.multipart = true;
    vec![
        op("get", "GET", format!("/api/v2/yarns/{id}"), None),
        op(
            "history",
            "GET",
            format!("/api/v2/yarns/{id}/history"),
            None,
        ),
        op(
            "favorite",
            "PUT",
            format!("/api/v2/yarns/{id}/favorite"),
            None,
        ),
        op(
            "update",
            "PUT",
            format!("/api/v2/yarns/{id}"),
            Some(json!({"name": "Renamed", "weight_grams": 100})),
        ),
        photo,
        op(
            "share list",
            "GET",
            format!("/api/v2/yarns/{id}/shares"),
            None,
        ),
        op(
            "share add",
            "POST",
            format!("/api/v2/yarns/{id}/shares"),
            Some(json!({"user_id": f.nora, "role": "viewer"})),
        ),
        op(
            "transfer",
            "POST",
            format!("/api/v2/yarns/{id}/transfer"),
            Some(json!({"new_owner_id": f.nora})),
        ),
        op("delete", "DELETE", format!("/api/v2/yarns/{id}"), None),
    ]
}

#[tokio::test]
async fn yarn_authorization_matrix_with_derived_access() {
    let f = fx().await;
    let ops = yarn_ops(&f, f.y2);
    assert_matrix(&f, None, &ops, &anonymous(&ops)).await;
    assert_matrix(&f, Some("dis"), &ops, &[401; 9]).await;
    assert_matrix(&f, Some("ben"), &ops, &[404; 9]).await;
    // yvonne holds a viewer share, yves an editor share.
    assert_matrix(
        &f,
        Some("yvonne"),
        &ops,
        &[200, 200, 200, 403, 403, 403, 403, 403, 403],
    )
    .await;
    assert_matrix(
        &f,
        Some("yves"),
        &ops,
        &[200, 200, 200, 200, 201, 403, 403, 403, 403],
    )
    .await;
    for who in ["anika", "admin"] {
        assert_matrix(&f, Some(who), &ops[..6], &[200, 200, 200, 200, 201, 200]).await;
    }

    // y1 is shared with nobody directly: vera (project viewer) and eddie
    // (project editor) hold derived access, which is read-only for both.
    let ops = yarn_ops(&f, f.y1);
    for who in ["vera", "eddie"] {
        assert_matrix(
            &f,
            Some(who),
            &ops,
            &[200, 200, 200, 403, 403, 403, 403, 403, 403],
        )
        .await;
    }
    // A yarn the project does not link stays invisible.
    let ops = yarn_ops(&f, f.y3);
    assert_matrix(&f, Some("vera"), &ops, &[404; 9]).await;
    assert_matrix(&f, Some("eddie"), &ops, &[404; 9]).await;
}

#[tokio::test]
async fn derived_access_is_not_transitive() {
    let f = fx().await;
    // vera sees y1 through p1, but y1 also sits in q1: q1 stays closed, and
    // y1's project list only names projects vera may open.
    let (status, _) = call(
        &f.app,
        "GET",
        &format!("/api/v2/projects/{}", f.q1),
        Some("vera"),
        None,
    )
    .await;
    assert_eq!(status, StatusCode::NOT_FOUND);
    let (_, yarn) = call(
        &f.app,
        "GET",
        &format!("/api/v2/yarns/{}", f.y1),
        Some("vera"),
        None,
    )
    .await;
    assert_eq!(yarn["data"]["project_ids"], json!([f.p1]));
    assert_eq!(yarn["data"]["access"]["role"], "viewer");
    assert_eq!(yarn["data"]["access"]["derived"], true);
    assert_eq!(yarn["data"]["access"]["can_edit"], false);
    // A yarn share grants nothing on the projects that use the yarn.
    let (status, _) = call(
        &f.app,
        "GET",
        &format!("/api/v2/projects/{}", f.q1),
        Some("yvonne"),
        None,
    )
    .await;
    assert_eq!(status, StatusCode::NOT_FOUND);
    let (_, yarn) = call(
        &f.app,
        "GET",
        &format!("/api/v2/yarns/{}", f.y2),
        Some("yvonne"),
        None,
    )
    .await;
    assert_eq!(yarn["data"]["project_ids"], json!([]));
    assert_eq!(yarn["data"]["access"]["derived"], false);
    // The derived yarn appears in vera's yarn list, with stock fields.
    let (_, list) = call(&f.app, "GET", "/api/v2/yarns", Some("vera"), None).await;
    assert_eq!(ids(&list), vec![f.y1]);
    let (_, list) = call(&f.app, "GET", "/api/v2/projects", Some("vera"), None).await;
    assert_eq!(ids(&list), vec![f.p1]);
    assert_eq!(list["data"][0]["access"]["role"], "viewer");
    assert_eq!(list["data"][0]["owner"]["email"], "");
    assert_eq!(list["data"][0]["owner"]["name"], "Anika");
}

#[tokio::test]
async fn derived_access_ends_on_unlink_and_on_revocation() {
    let f = fx().await;
    let y1 = format!("/api/v2/yarns/{}", f.y1);
    let (before_time, _) = {
        let (_, time) = sync(&f, "yarns", "vera", None).await;
        (time.clone(), time)
    };
    // Owner unlinks y1 from p1 (the project update drops it).
    tick().await;
    let (status, _) = call(
        &f.app,
        "PUT",
        &format!("/api/v2/projects/{}", f.p1),
        Some("anika"),
        Some(json!({"name": "Shawl", "yarn_ids": []})),
    )
    .await;
    assert_eq!(status, StatusCode::OK);
    let (status, _) = call(&f.app, "GET", &y1, Some("vera"), None).await;
    assert_eq!(status, StatusCode::NOT_FOUND);
    let (delta, _) = sync(&f, "yarns", "vera", Some(&before_time)).await;
    assert_eq!(deleted_ids(&delta), vec![f.y1]);
    assert!(updated_ids(&delta).is_empty());
    // Relinking brings it back for vera, even though the yarn is unchanged.
    let (_, mid) = sync(&f, "yarns", "vera", None).await;
    tick().await;
    call(
        &f.app,
        "PUT",
        &format!("/api/v2/projects/{}", f.p1),
        Some("anika"),
        Some(json!({"name": "Shawl", "yarn_ids": [f.y1]})),
    )
    .await;
    let (status, _) = call(&f.app, "GET", &y1, Some("vera"), None).await;
    assert_eq!(status, StatusCode::OK);
    let (delta, _) = sync(&f, "yarns", "vera", Some(&mid)).await;
    assert!(updated_ids(&delta).contains(&f.y1));
    // Revoking the project share ends it for good.
    let (_, mid) = sync(&f, "yarns", "vera", None).await;
    tick().await;
    let (status, _) = call(
        &f.app,
        "DELETE",
        &format!("/api/v2/projects/{}/shares/{}", f.p1, f.vera),
        Some("anika"),
        None,
    )
    .await;
    assert_eq!(status, StatusCode::NO_CONTENT);
    let (status, _) = call(&f.app, "GET", &y1, Some("vera"), None).await;
    assert_eq!(status, StatusCode::NOT_FOUND);
    let (delta, _) = sync(&f, "yarns", "vera", Some(&mid)).await;
    assert_eq!(deleted_ids(&delta), vec![f.y1]);
    let (delta, _) = sync(&f, "projects", "vera", Some(&mid)).await;
    assert_eq!(deleted_ids(&delta), vec![f.p1]);
}

#[tokio::test]
async fn editors_link_only_the_owners_yarns() {
    let f = fx().await;
    let own = yarn(&f.pool, f.eddie, "Eddie's stash").await;
    let (status, body) = call(
        &f.app,
        "PUT",
        &format!("/api/v2/projects/{}", f.p1),
        Some("eddie"),
        Some(json!({"name": "Shawl", "yarn_ids": [f.y1, own]})),
    )
    .await;
    assert_eq!(status, StatusCode::UNPROCESSABLE_ENTITY, "{body}");
    let linked: i64 = sqlx::query_scalar("SELECT count(*) FROM project_yarns WHERE yarn_id = ?")
        .bind(own)
        .fetch_one(&f.pool)
        .await
        .unwrap();
    assert_eq!(linked, 0);
    // The owner's yarns link fine, and the new link reaches the viewers.
    let (_, time) = sync(&f, "yarns", "vera", None).await;
    tick().await;
    let (status, _) = call(
        &f.app,
        "PUT",
        &format!("/api/v2/projects/{}", f.p1),
        Some("eddie"),
        Some(json!({"name": "Shawl", "yarn_ids": [f.y1, f.y3]})),
    )
    .await;
    assert_eq!(status, StatusCode::OK);
    let (delta, _) = sync(&f, "yarns", "vera", Some(&time)).await;
    assert!(updated_ids(&delta).contains(&f.y3));
    let (status, _) = call(
        &f.app,
        "GET",
        &format!("/api/v2/yarns/{}", f.y3),
        Some("vera"),
        None,
    )
    .await;
    assert_eq!(status, StatusCode::OK);
}

#[tokio::test]
async fn yarn_candidates_are_the_owners_yarns_for_editors_only() {
    let f = fx().await;
    let path = format!("/api/v2/projects/{}/yarn-candidates", f.p1);
    for (who, expected) in [
        (None, 401),
        (Some("ben"), 404),
        (Some("vera"), 403),
        (Some("eddie"), 200),
        (Some("anika"), 200),
        (Some("admin"), 200),
    ] {
        let (status, _) = call(&f.app, "GET", &path, who, None).await;
        assert_eq!(status.as_u16(), expected, "{who:?}");
    }
    yarn(&f.pool, f.eddie, "Eddie's own").await;
    let (_, body) = call(&f.app, "GET", &path, Some("eddie"), None).await;
    let items = body["data"]["items"].as_array().unwrap();
    let got: Vec<(i64, bool)> = items
        .iter()
        .map(|y| (y["id"].as_i64().unwrap(), y["linked"].as_bool().unwrap()))
        .collect();
    assert_eq!(got, vec![(f.y2, false), (f.y1, true), (f.y3, false)]);
    let (_, body) = call(
        &f.app,
        "GET",
        &format!("{path}?q=merino"),
        Some("eddie"),
        None,
    )
    .await;
    assert_eq!(body["data"]["items"].as_array().unwrap().len(), 1);
}

// --- sharing rules ------------------------------------------------------------

#[tokio::test]
async fn share_validation_rejects_self_owner_disabled_unknown_and_duplicates() {
    let f = fx().await;
    let p = format!("/api/v2/projects/{}/shares", f.p1);
    let post = |user: i64, role: &'static str| {
        let app = f.app.clone();
        let p = p.clone();
        async move {
            call(
                &app,
                "POST",
                &p,
                Some("anika"),
                Some(json!({"user_id": user, "role": role})),
            )
            .await
        }
    };
    assert_eq!(
        post(f.anika, "viewer").await.0,
        StatusCode::UNPROCESSABLE_ENTITY
    );
    assert_eq!(
        post(f.dis, "viewer").await.0,
        StatusCode::UNPROCESSABLE_ENTITY
    );
    assert_eq!(
        post(9999, "viewer").await.0,
        StatusCode::UNPROCESSABLE_ENTITY
    );
    assert_eq!(
        post(f.nora, "owner").await.0,
        StatusCode::UNPROCESSABLE_ENTITY
    );
    assert_eq!(post(f.vera, "viewer").await.0, StatusCode::CONFLICT);
    // An administrator cannot share with the owner or with themselves either.
    let (status, _) = call(
        &f.app,
        "POST",
        &p,
        Some("admin"),
        Some(json!({"user_id": f.anika, "role": "viewer"})),
    )
    .await;
    assert_eq!(status, StatusCode::UNPROCESSABLE_ENTITY);
    let (status, _) = call(
        &f.app,
        "POST",
        &p,
        Some("admin"),
        Some(json!({"user_id": f.admin, "role": "viewer"})),
    )
    .await;
    assert_eq!(status, StatusCode::UNPROCESSABLE_ENTITY);
    let (status, body) = post(f.nora, "viewer").await;
    assert_eq!(status, StatusCode::CREATED);
    assert_eq!(body["data"]["role"], "viewer");
    assert!(body["data"]["user"].get("email").is_none());
    // Unknown fields are refused.
    let (status, _) = call(
        &f.app,
        "POST",
        &p,
        Some("anika"),
        Some(json!({"user_id": f.nick, "role": "viewer", "extra": 1})),
    )
    .await;
    assert_eq!(status, StatusCode::UNPROCESSABLE_ENTITY);
    // The owner sees the list without any email.
    let (status, list) = call(&f.app, "GET", &p, Some("anika"), None).await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(list["data"]["owner"]["name"], "Anika");
    assert!(!list.to_string().contains("example.test"));
    assert_eq!(list["data"]["shares"].as_array().unwrap().len(), 4);
}

#[tokio::test]
async fn any_recipient_may_leave_and_the_resource_vanishes_for_them() {
    let f = fx().await;
    let (_, time) = sync(&f, "projects", "eddie", None).await;
    tick().await;
    let (status, _) = call(
        &f.app,
        "POST",
        &format!("/api/v2/projects/{}/leave", f.p1),
        Some("eddie"),
        None,
    )
    .await;
    assert_eq!(status, StatusCode::NO_CONTENT);
    let (status, _) = call(
        &f.app,
        "GET",
        &format!("/api/v2/projects/{}", f.p1),
        Some("eddie"),
        None,
    )
    .await;
    assert_eq!(status, StatusCode::NOT_FOUND);
    let (delta, _) = sync(&f, "projects", "eddie", Some(&time)).await;
    assert_eq!(deleted_ids(&delta), vec![f.p1]);
    // Leaving twice, as a stranger, or as the owner is not a thing.
    for who in ["eddie", "ben", "anika"] {
        let (status, _) = call(
            &f.app,
            "POST",
            &format!("/api/v2/projects/{}/leave", f.p1),
            Some(who),
            None,
        )
        .await;
        assert_eq!(status, StatusCode::NOT_FOUND, "{who}");
    }
    // Viewers of a yarn leave too; the owner's yarn is untouched.
    let (status, _) = call(
        &f.app,
        "POST",
        &format!("/api/v2/yarns/{}/leave", f.y2),
        Some("yvonne"),
        None,
    )
    .await;
    assert_eq!(status, StatusCode::NO_CONTENT);
    let (status, _) = call(
        &f.app,
        "GET",
        &format!("/api/v2/yarns/{}", f.y2),
        Some("anika"),
        None,
    )
    .await;
    assert_eq!(status, StatusCode::OK);
    // Other recipients keep theirs.
    let remaining: i64 =
        sqlx::query_scalar("SELECT count(*) FROM resource_shares WHERE user_id = ?")
            .bind(f.yves)
            .fetch_one(&f.pool)
            .await
            .unwrap();
    assert_eq!(remaining, 1);
}

#[tokio::test]
async fn people_search_returns_no_email_and_needs_authentication() {
    let f = fx().await;
    let (status, _) = call(&f.app, "GET", "/api/v2/people?q=an", None, None).await;
    assert_eq!(status, StatusCode::UNAUTHORIZED);
    let (status, _) = call(&f.app, "GET", "/api/v2/people?q=an", Some("dis"), None).await;
    assert_eq!(status, StatusCode::UNAUTHORIZED);
    let (status, body) = call(&f.app, "GET", "/api/v2/people?q=ani", Some("ben"), None).await;
    assert_eq!(status, StatusCode::OK);
    let text = body.to_string();
    assert!(!text.contains("example.test"), "{text}");
    assert!(!text.contains("is_admin") && !text.contains("email"));
    let names: Vec<&str> = body["data"]["items"]
        .as_array()
        .unwrap()
        .iter()
        .map(|person| person["name"].as_str().unwrap())
        .collect();
    assert_eq!(names, ["Anika"]);
    let person = &body["data"]["items"][0];
    let mut keys: Vec<&str> = person
        .as_object()
        .unwrap()
        .keys()
        .map(String::as_str)
        .collect();
    keys.sort_unstable();
    assert_eq!(keys, ["avatar_url", "id", "name"]);
    // The caller and disabled accounts are excluded; the domain is not searched.
    let (_, body) = call(&f.app, "GET", "/api/v2/people", Some("ben"), None).await;
    let all: Vec<i64> = body["data"]["items"]
        .as_array()
        .unwrap()
        .iter()
        .map(|person| person["id"].as_i64().unwrap())
        .collect();
    assert!(!all.contains(&f.ben) && !all.contains(&f.dis));
    assert_eq!(all.len(), NAMES.len() - 2);
    let (_, body) = call(&f.app, "GET", "/api/v2/people?q=example", Some("ben"), None).await;
    assert!(body["data"]["items"].as_array().unwrap().is_empty());
    let (status, _) = call(&f.app, "GET", "/api/v2/people?page=0", Some("ben"), None).await;
    assert_eq!(status, StatusCode::UNPROCESSABLE_ENTITY);
}

#[tokio::test]
async fn cookie_requests_need_csrf_on_share_routes() {
    let f = fx().await;
    let (status, body) = call(
        &f.app,
        "POST",
        &format!("/api/v2/projects/{}/shares", f.p1),
        None,
        Some(json!({"user_id": f.nora, "role": "viewer"})),
    )
    .await;
    assert_eq!(status, StatusCode::FORBIDDEN);
    assert_eq!(body["error"]["code"], "csrf_failed");
    // A bad bearer token never falls back to a cookie.
    let response = f
        .app
        .clone()
        .oneshot(
            Request::builder()
                .uri("/api/v2/people")
                .header("authorization", "Bearer not-a-token")
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::UNAUTHORIZED);
}

// --- transfer -------------------------------------------------------------------

#[tokio::test]
async fn project_transfer_keeps_the_old_owner_as_editor_and_leaves_yarns_behind() {
    let f = fx().await;
    // nora already has a viewer share: it is removed by the transfer.
    assert_eq!(
        share(&f, "project", f.p1, "anika", f.nora, "viewer").await,
        StatusCode::CREATED
    );
    let (_, nora_time) = sync(&f, "projects", "nora", None).await;
    let (_, anika_time) = sync(&f, "projects", "anika", None).await;
    tick().await;
    let (status, body) = call(
        &f.app,
        "POST",
        &format!("/api/v2/projects/{}/transfer", f.p1),
        Some("anika"),
        Some(json!({"new_owner_id": f.nora})),
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{body}");
    assert_eq!(body["data"]["moved_yarn_ids"], json!([]));
    assert_eq!(body["data"]["access"]["role"], "editor");
    assert_eq!(body["data"]["new_owner"]["id"], f.nora);
    // Anika can edit but neither share, transfer nor delete.
    let (_, project) = call(
        &f.app,
        "GET",
        &format!("/api/v2/projects/{}", f.p1),
        Some("anika"),
        None,
    )
    .await;
    assert_eq!(project["data"]["access"]["role"], "editor");
    assert_eq!(project["data"]["owner"]["id"], f.nora);
    for (method, path) in [
        ("DELETE", format!("/api/v2/projects/{}", f.p1)),
        ("GET", format!("/api/v2/projects/{}/shares", f.p1)),
    ] {
        let (status, _) = call(&f.app, method, &path, Some("anika"), None).await;
        assert_eq!(status, StatusCode::FORBIDDEN, "{method} {path}");
    }
    let (status, _) = call(
        &f.app,
        "PUT",
        &format!("/api/v2/projects/{}", f.p1),
        Some("anika"),
        Some(json!({"name": "Shawl 2", "yarn_ids": [f.y1]})),
    )
    .await;
    assert_eq!(status, StatusCode::OK);
    // nora owns it and her old share row is gone; the yarn stayed with anika
    // but nora can read it (the project is hers), anika still edits her own.
    let rows: Vec<(i64, String)> = sqlx::query_as(
        "SELECT user_id, role FROM resource_shares WHERE kind='project' AND resource_id=? \
         ORDER BY user_id",
    )
    .bind(f.p1)
    .fetch_all(&f.pool)
    .await
    .unwrap();
    assert_eq!(
        rows,
        vec![
            (f.anika, "editor".to_owned()),
            (f.vera, "viewer".to_owned()),
            (f.eddie, "editor".to_owned()),
            (f.dis, "viewer".to_owned()),
        ]
        .into_iter()
        .collect::<std::collections::BTreeMap<_, _>>()
        .into_iter()
        .collect::<Vec<_>>()
    );
    let y1_owner: i64 = sqlx::query_scalar("SELECT owner_id FROM yarns WHERE id = ?")
        .bind(f.y1)
        .fetch_one(&f.pool)
        .await
        .unwrap();
    assert_eq!(y1_owner, f.anika);
    let (status, yarn) = call(
        &f.app,
        "GET",
        &format!("/api/v2/yarns/{}", f.y1),
        Some("nora"),
        None,
    )
    .await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(yarn["data"]["access"]["derived"], true);
    let (status, _) = call(
        &f.app,
        "PUT",
        &format!("/api/v2/yarns/{}", f.y1),
        Some("nora"),
        Some(json!({"name": "Nope"})),
    )
    .await;
    assert_eq!(status, StatusCode::FORBIDDEN);
    // The new owner manages sharing; the recipients still have their access.
    let (status, _) = call(
        &f.app,
        "DELETE",
        &format!("/api/v2/projects/{}/shares/{}", f.p1, f.anika),
        Some("nora"),
        None,
    )
    .await;
    assert_eq!(status, StatusCode::NO_CONTENT);
    let (status, _) = call(
        &f.app,
        "GET",
        &format!("/api/v2/projects/{}", f.p1),
        Some("vera"),
        None,
    )
    .await;
    assert_eq!(status, StatusCode::OK);
    // Sync: nora sees the project, anika got a tombstone after her revoke.
    let (delta, _) = sync(&f, "projects", "nora", Some(&nora_time)).await;
    assert_eq!(updated_ids(&delta), vec![f.p1]);
    assert_eq!(delta["data"]["updated"][0]["access"]["role"], "owner");
    let (delta, _) = sync(&f, "projects", "anika", Some(&anika_time)).await;
    assert_eq!(deleted_ids(&delta), vec![f.p1]);
    // Media and history follow the project.
    let (status, _) = call(
        &f.app,
        "GET",
        &format!("/api/v2/projects/{}/history", f.p1),
        Some("nora"),
        None,
    )
    .await;
    assert_eq!(status, StatusCode::OK);
}

#[tokio::test]
async fn move_linked_yarns_moves_only_yarns_the_old_owner_owned() {
    let f = fx().await;
    // A yarn of someone else linked to the project stays where it is.
    let foreign = yarn(&f.pool, f.ben, "Ben's yarn").await;
    link(&f.pool, f.p1, foreign).await;
    let (status, body) = call(
        &f.app,
        "POST",
        &format!("/api/v2/projects/{}/transfer", f.p1),
        Some("anika"),
        Some(json!({"new_owner_id": f.nora, "move_linked_yarns": true})),
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{body}");
    assert_eq!(body["data"]["moved_yarn_ids"], json!([f.y1]));
    let owners: Vec<(i64, i64)> =
        sqlx::query_as("SELECT id, owner_id FROM yarns WHERE id IN (?, ?, ?) ORDER BY id")
            .bind(f.y1)
            .bind(f.y3)
            .bind(foreign)
            .fetch_all(&f.pool)
            .await
            .unwrap();
    assert_eq!(
        owners,
        vec![(f.y1, f.nora), (f.y3, f.anika), (foreign, f.ben)]
    );
    // The old owner keeps editing the moved yarn, and her other project q1
    // still shows it (editor share on the yarn).
    let (_, yarn) = call(
        &f.app,
        "GET",
        &format!("/api/v2/yarns/{}", f.y1),
        Some("anika"),
        None,
    )
    .await;
    assert_eq!(yarn["data"]["access"]["role"], "editor");
    assert_eq!(yarn["data"]["owner"]["id"], f.nora);
    let (_, q1) = call(
        &f.app,
        "GET",
        &format!("/api/v2/projects/{}", f.q1),
        Some("anika"),
        None,
    )
    .await;
    assert!(
        q1["data"]["yarn_ids"]
            .as_array()
            .unwrap()
            .contains(&json!(f.y1))
    );
    // Without the flag nothing moves.
    let (status, body) = call(
        &f.app,
        "POST",
        &format!("/api/v2/projects/{}/transfer", f.q1),
        Some("anika"),
        Some(json!({"new_owner_id": f.nora})),
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{body}");
    assert_eq!(body["data"]["moved_yarn_ids"], json!([]));
    let owner: i64 = sqlx::query_scalar("SELECT owner_id FROM yarns WHERE id = ?")
        .bind(f.y2)
        .fetch_one(&f.pool)
        .await
        .unwrap();
    assert_eq!(owner, f.anika);
}

#[tokio::test]
async fn transfer_validation_and_category_conflicts() {
    let f = fx().await;
    let path = format!("/api/v2/projects/{}/transfer", f.p1);
    for body in [
        json!({"new_owner_id": f.anika}),
        json!({"new_owner_id": f.dis}),
        json!({"new_owner_id": 9999}),
        json!({"new_owner_id": f.nora, "bogus": true}),
    ] {
        let (status, _) = call(&f.app, "POST", &path, Some("anika"), Some(body.clone())).await;
        assert_eq!(status, StatusCode::UNPROCESSABLE_ENTITY, "{body}");
    }
    let (status, _) = call(
        &f.app,
        "POST",
        &format!("/api/v2/yarns/{}/transfer", f.y3),
        Some("anika"),
        Some(json!({"new_owner_id": f.nora, "move_linked_yarns": true})),
    )
    .await;
    assert_eq!(status, StatusCode::UNPROCESSABLE_ENTITY);
    // Category names are unique per account: the project follows its category
    // by name instead of colliding with the new owner's.
    let cat: i64 = sqlx::query_scalar(
        "INSERT INTO categories (user_id, name) VALUES (?, 'Shawls') RETURNING id",
    )
    .bind(f.anika)
    .fetch_one(&f.pool)
    .await
    .unwrap();
    sqlx::query("UPDATE projects SET category_id = ? WHERE id = ?")
        .bind(cat)
        .bind(f.p1)
        .execute(&f.pool)
        .await
        .unwrap();
    let existing: i64 = sqlx::query_scalar(
        "INSERT INTO categories (user_id, name) VALUES (?, 'shawls') RETURNING id",
    )
    .bind(f.nora)
    .fetch_one(&f.pool)
    .await
    .unwrap();
    let (status, body) = call(
        &f.app,
        "POST",
        &path,
        Some("anika"),
        Some(json!({"new_owner_id": f.nora})),
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{body}");
    let moved: i64 = sqlx::query_scalar("SELECT category_id FROM projects WHERE id = ?")
        .bind(f.p1)
        .fetch_one(&f.pool)
        .await
        .unwrap();
    assert_eq!(moved, existing);
    // An unknown project is a plain 404, a stranger gets the same.
    let (status, _) = call(
        &f.app,
        "POST",
        "/api/v2/projects/99999/transfer",
        Some("admin"),
        Some(json!({"new_owner_id": f.nora})),
    )
    .await;
    assert_eq!(status, StatusCode::NOT_FOUND);
}

#[tokio::test]
async fn yarn_transfer_gives_the_old_owner_an_editor_share() {
    let f = fx().await;
    let (status, body) = call(
        &f.app,
        "POST",
        &format!("/api/v2/yarns/{}/transfer", f.y3),
        Some("anika"),
        Some(json!({"new_owner_id": f.nora})),
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{body}");
    let (_, yarn) = call(
        &f.app,
        "GET",
        &format!("/api/v2/yarns/{}", f.y3),
        Some("anika"),
        None,
    )
    .await;
    assert_eq!(yarn["data"]["access"]["role"], "editor");
    let (_, list) = call(&f.app, "GET", "/api/v2/yarns", Some("nora"), None).await;
    assert_eq!(ids(&list), vec![f.y3]);
    let (status, _) = call(
        &f.app,
        "POST",
        &format!("/api/v2/yarns/{}/leave", f.y3),
        Some("anika"),
        None,
    )
    .await;
    assert_eq!(status, StatusCode::NO_CONTENT);
}

// --- sync ---------------------------------------------------------------------

#[tokio::test]
async fn shared_resources_appear_even_when_older_than_the_cursor() {
    let f = fx().await;
    let (first, time) = sync(&f, "projects", "ben", None).await;
    assert!(updated_ids(&first).is_empty());
    let (first_yarns, yarn_time) = sync(&f, "yarns", "ben", None).await;
    assert!(updated_ids(&first_yarns).is_empty());
    tick().await;
    // p1 and y3 were last modified in January; the share is what is new.
    assert_eq!(
        share(&f, "project", f.p1, "anika", f.ben, "viewer").await,
        StatusCode::CREATED
    );
    assert_eq!(
        share(&f, "yarn", f.y3, "anika", f.ben, "editor").await,
        StatusCode::CREATED
    );
    let (delta, _) = sync(&f, "projects", "ben", Some(&time)).await;
    assert_eq!(updated_ids(&delta), vec![f.p1]);
    assert_eq!(delta["data"]["updated"][0]["access"]["role"], "viewer");
    assert_eq!(delta["data"]["updated"][0]["owner"]["id"], f.anika);
    assert_eq!(delta["data"]["updated"][0]["owner"]["email"], "");
    // The derived yarn y1 arrives with the project, y3 with its own share.
    let (delta, _) = sync(&f, "yarns", "ben", Some(&yarn_time)).await;
    assert_eq!(updated_ids(&delta), vec![f.y1, f.y3]);
    let roles: Vec<(i64, String, bool)> = delta["data"]["updated"]
        .as_array()
        .unwrap()
        .iter()
        .map(|y| {
            (
                y["id"].as_i64().unwrap(),
                y["access"]["role"].as_str().unwrap().to_owned(),
                y["access"]["derived"].as_bool().unwrap(),
            )
        })
        .collect();
    assert_eq!(
        roles,
        vec![
            (f.y1, "viewer".to_owned(), true),
            (f.y3, "editor".to_owned(), false)
        ]
    );
    // A role change reaches the recipient with the new role.
    let (_, time) = sync(&f, "projects", "ben", None).await;
    tick().await;
    let (status, _) = call(
        &f.app,
        "PATCH",
        &format!("/api/v2/projects/{}/shares/{}", f.p1, f.ben),
        Some("anika"),
        Some(json!({"role": "editor"})),
    )
    .await;
    assert_eq!(status, StatusCode::OK);
    let (delta, _) = sync(&f, "projects", "ben", Some(&time)).await;
    assert_eq!(delta["data"]["updated"][0]["access"]["role"], "editor");
    // The owner's own delta is unaffected by the recipients' tombstones.
    let (delta, _) = sync(&f, "projects", "anika", None).await;
    assert_eq!(updated_ids(&delta), vec![f.p1, f.q1]);
    assert!(deleted_ids(&delta).is_empty());
}

#[tokio::test]
async fn tombstones_for_revoke_delete_and_the_end_of_derived_access() {
    let f = fx().await;
    let (_, vera_projects) = sync(&f, "projects", "vera", None).await;
    let (_, yvonne_yarns) = sync(&f, "yarns", "yvonne", None).await;
    let (_, vera_yarns) = sync(&f, "yarns", "vera", None).await;
    tick().await;
    // Revoking a yarn share.
    let (status, _) = call(
        &f.app,
        "DELETE",
        &format!("/api/v2/yarns/{}/shares/{}", f.y2, f.yvonne),
        Some("anika"),
        None,
    )
    .await;
    assert_eq!(status, StatusCode::NO_CONTENT);
    let (delta, _) = sync(&f, "yarns", "yvonne", Some(&yvonne_yarns)).await;
    assert_eq!(deleted_ids(&delta), vec![f.y2]);
    // Deleting a shared project tombstones the project and its derived yarn
    // (y1 is still linked from q1, but vera has no access through q1).
    let (status, _) = call(
        &f.app,
        "DELETE",
        &format!("/api/v2/projects/{}", f.p1),
        Some("anika"),
        None,
    )
    .await;
    assert_eq!(status, StatusCode::NO_CONTENT);
    let (delta, _) = sync(&f, "projects", "vera", Some(&vera_projects)).await;
    assert_eq!(deleted_ids(&delta), vec![f.p1]);
    let (delta, _) = sync(&f, "yarns", "vera", Some(&vera_yarns)).await;
    assert_eq!(deleted_ids(&delta), vec![f.y1]);
    // The owner's deletion tombstone is not served to anyone else's delta as a
    // second entry, and an administrator's all-scope delta carries no
    // access-loss tombstones.
    let (status, all) = call(
        &f.app,
        "GET",
        "/api/v2/sync/yarns?scope=all&limit=50",
        Some("admin"),
        None,
    )
    .await;
    assert_eq!(status, StatusCode::OK);
    assert!(!deleted_ids(&all).contains(&f.y2), "{all}");
    // Deleting a shared yarn tombstones it for its recipient.
    let (_, yves_yarns) = sync(&f, "yarns", "yves", None).await;
    tick().await;
    let (status, _) = call(
        &f.app,
        "DELETE",
        &format!("/api/v2/yarns/{}", f.y2),
        Some("anika"),
        None,
    )
    .await;
    assert_eq!(status, StatusCode::NO_CONTENT);
    let (delta, _) = sync(&f, "yarns", "yves", Some(&yves_yarns)).await;
    assert_eq!(deleted_ids(&delta), vec![f.y2]);
}

#[tokio::test]
async fn replayed_cursors_are_stable_and_bound_to_their_snapshot() {
    let f = fx().await;
    // Three shared projects for ben so that a page size of one paginates.
    let extra = project(&f.pool, f.anika, "Third").await;
    let (_, time) = sync(&f, "projects", "ben", None).await;
    tick().await;
    for id in [f.p1, f.q1, extra] {
        assert_eq!(
            share(&f, "project", id, "anika", f.ben, "viewer").await,
            StatusCode::CREATED
        );
    }
    let first = call(
        &f.app,
        "GET",
        &format!("/api/v2/sync/projects?since={time}&limit=1"),
        Some("ben"),
        None,
    )
    .await
    .1;
    assert_eq!(first["data"]["has_more"], true);
    let cursor = first["data"]["next_cursor"].as_str().unwrap().to_owned();
    let snapshot = first["data"]["server_time"].as_str().unwrap().to_owned();
    // A share that happens after the snapshot is not part of this walk.
    tick().await;
    assert_eq!(
        share(&f, "project", f.p1, "anika", f.nick, "viewer").await,
        StatusCode::CREATED
    );
    let walk = |cursor: String| {
        let app = f.app.clone();
        async move {
            let mut seen = Vec::new();
            let mut cursor = cursor;
            loop {
                let (status, page) = call(
                    &app,
                    "GET",
                    &format!("/api/v2/sync/projects?cursor={cursor}&limit=1"),
                    Some("ben"),
                    None,
                )
                .await;
                assert_eq!(status, StatusCode::OK, "{page}");
                seen.extend(updated_ids(&page));
                match page["data"]["next_cursor"].as_str() {
                    Some(next) if page["data"]["has_more"] == true => cursor = next.to_owned(),
                    _ => return seen,
                }
            }
        }
    };
    let once = walk(cursor.clone()).await;
    let again = walk(cursor).await;
    assert_eq!(once, again);
    assert_eq!(once.len(), 2);
    // Starting over from the old snapshot sees everything again, and a cursor
    // is only valid for the user it was issued to.
    let (full, _) = sync(&f, "projects", "ben", Some(&time)).await;
    assert_eq!(updated_ids(&full).len(), 3);
    let (replay, _) = sync(&f, "projects", "nick", Some(&snapshot)).await;
    assert_eq!(updated_ids(&replay), vec![f.p1]);
    let (status, _) = call(
        &f.app,
        "GET",
        &format!(
            "/api/v2/sync/projects?cursor={}&limit=1",
            first["data"]["next_cursor"].as_str().unwrap()
        ),
        Some("nick"),
        None,
    )
    .await;
    assert_eq!(status, StatusCode::BAD_REQUEST);
    // Cursors issued in another scope are still refused.
    let (status, body) = call(
        &f.app,
        "GET",
        &format!(
            "/api/v2/sync/projects?scope=all&cursor={}&limit=1",
            first["data"]["next_cursor"].as_str().unwrap()
        ),
        Some("admin"),
        None,
    )
    .await;
    assert!(
        status == StatusCode::BAD_REQUEST || status == StatusCode::CONFLICT,
        "{body}"
    );
}

// --- media and history ------------------------------------------------------------

#[tokio::test]
async fn media_is_readable_by_recipients_and_writable_by_editors_only() {
    let f = fx().await;
    let (status, image) = upload(
        &f.app,
        &format!("/api/v2/projects/{}/images/title", f.p1),
        Some("eddie"),
    )
    .await;
    assert_eq!(status, StatusCode::CREATED, "{image}");
    let url = image["data"]["url"]
        .as_str()
        .or_else(|| image["url"].as_str())
        .unwrap()
        .to_owned();
    let image_id = image["data"]["id"]
        .as_i64()
        .or_else(|| image["id"].as_i64())
        .unwrap();
    // The upload lands in the owner's storage.
    let owner: i64 = sqlx::query_scalar("SELECT owner_id FROM media_files WHERE project_id = ?")
        .bind(f.p1)
        .fetch_one(&f.pool)
        .await
        .unwrap();
    assert_eq!(owner, f.anika);
    for (who, expected) in [
        ("anika", 200),
        ("vera", 200),
        ("eddie", 200),
        ("admin", 200),
        ("ben", 403),
        ("frank", 403),
    ] {
        let (status, _) = raw(&f.app, "GET", &url, Some(who)).await;
        assert_eq!(status.as_u16(), expected, "{who} {url}");
    }
    let (status, _) = raw(&f.app, "GET", &url, None).await;
    assert_eq!(status, StatusCode::UNAUTHORIZED);
    // Viewers cannot delete or reorder; editors can.
    let delete = format!("/api/v2/projects/{}/images/{image_id}", f.p1);
    let (status, _) = call(&f.app, "DELETE", &delete, Some("vera"), None).await;
    assert_eq!(status, StatusCode::FORBIDDEN);
    let (status, _) = call(&f.app, "DELETE", &delete, Some("ben"), None).await;
    assert_eq!(status, StatusCode::NOT_FOUND);
    let order = format!("/api/v2/projects/{}/images/order", f.p1);
    let (status, _) = call(
        &f.app,
        "PUT",
        &order,
        Some("vera"),
        Some(json!({"ids": [image_id]})),
    )
    .await;
    assert_eq!(status, StatusCode::FORBIDDEN);
    let (status, _) = call(
        &f.app,
        "PUT",
        &order,
        Some("eddie"),
        Some(json!({"ids": [image_id]})),
    )
    .await;
    assert_eq!(status, StatusCode::OK);
    // A derived yarn viewer reads yarn photos but never writes them.
    let (status, photo) = upload(
        &f.app,
        &format!("/api/v2/yarns/{}/photos", f.y1),
        Some("anika"),
    )
    .await;
    assert_eq!(status, StatusCode::CREATED);
    let photo_url = photo["data"]["url"]
        .as_str()
        .or_else(|| photo["url"].as_str())
        .unwrap()
        .to_owned();
    let (status, _) = raw(&f.app, "GET", &photo_url, Some("vera")).await;
    assert_eq!(status, StatusCode::OK);
    let (status, _) = raw(&f.app, "GET", &photo_url, Some("ben")).await;
    assert_eq!(status, StatusCode::FORBIDDEN);
    let (status, _) = call(
        &f.app,
        "DELETE",
        &format!(
            "/api/v2/yarns/{}/photos/{}",
            f.y1,
            photo["data"]["id"]
                .as_i64()
                .or_else(|| photo["id"].as_i64())
                .unwrap()
        ),
        Some("vera"),
        None,
    )
    .await;
    assert_eq!(status, StatusCode::FORBIDDEN);
    // The cover shows up as the preview in the recipient's list.
    let (_, list) = call(&f.app, "GET", "/api/v2/projects", Some("vera"), None).await;
    assert!(list["data"][0]["preview_url"].is_string(), "{list}");
    let (_, list) = call(&f.app, "GET", "/api/v2/yarns", Some("vera"), None).await;
    assert!(list["data"][0]["preview_url"].is_string(), "{list}");
}

#[tokio::test]
async fn image_descriptions_are_editable_by_editors_only() {
    let f = fx().await;
    let (status, image) = upload(
        &f.app,
        &format!("/api/v2/projects/{}/images/title", f.p1),
        Some("anika"),
    )
    .await;
    assert_eq!(status, StatusCode::CREATED, "{image}");
    let image_id = image["data"]["id"].as_i64().unwrap();
    let path = format!("/api/v2/projects/{}/images/{image_id}", f.p1);
    let body = Some(json!({"alt_text": "Shared description"}));
    for (who, expected) in [
        ("vera", StatusCode::FORBIDDEN),
        ("ben", StatusCode::NOT_FOUND),
        ("frank", StatusCode::NOT_FOUND),
    ] {
        let (status, _) = call(&f.app, "PATCH", &path, Some(who), body.clone()).await;
        assert_eq!(status, expected, "{who}");
    }
    let (status, _) = call(&f.app, "PATCH", &path, None, body.clone()).await;
    // Like every other media mutation, an anonymous write is refused by the mutation guard (403).
    assert_eq!(status, StatusCode::FORBIDDEN);
    let stored: String = sqlx::query_scalar("SELECT alt_text FROM media_files WHERE public_id = ?")
        .bind(image_id)
        .fetch_one(&f.pool)
        .await
        .unwrap();
    assert_eq!(stored, "a.png", "denied callers change nothing");
    let (status, updated) = call(&f.app, "PATCH", &path, Some("eddie"), body).await;
    assert_eq!(status, StatusCode::OK, "{updated}");
    assert_eq!(updated["data"]["alt_text"], "Shared description");
    // The audit entry names the editor, not the owner.
    let actor: i64 = sqlx::query_scalar(
        "SELECT actor_user_id FROM audit_events WHERE entity_type = 'project' \
         AND entity_id = ? AND action = 'updated' ORDER BY id DESC LIMIT 1",
    )
    .bind(f.p1)
    .fetch_one(&f.pool)
    .await
    .unwrap();
    assert_eq!(actor, f.eddie);

    // A derived yarn viewer may not change a yarn photo description; its owner may.
    let (status, photo) = upload(
        &f.app,
        &format!("/api/v2/yarns/{}/photos", f.y1),
        Some("anika"),
    )
    .await;
    assert_eq!(status, StatusCode::CREATED);
    let photo_path = format!(
        "/api/v2/yarns/{}/photos/{}",
        f.y1,
        photo["data"]["id"].as_i64().unwrap()
    );
    let body = Some(json!({"alt_text": "Skein"}));
    let (status, _) = call(&f.app, "PATCH", &photo_path, Some("vera"), body.clone()).await;
    assert_eq!(status, StatusCode::FORBIDDEN);
    let (status, _) = call(&f.app, "PATCH", &photo_path, Some("ben"), body.clone()).await;
    assert_eq!(status, StatusCode::NOT_FOUND);
    let (status, updated) = call(&f.app, "PATCH", &photo_path, Some("anika"), body).await;
    assert_eq!(status, StatusCode::OK, "{updated}");
    assert_eq!(updated["data"]["alt_text"], "Skein");
}

#[tokio::test]
async fn sharing_events_show_in_resource_history_with_the_other_account() {
    let f = fx().await;
    let base = format!("/api/v2/projects/{}", f.q1);
    assert_eq!(
        share(&f, "project", f.q1, "anika", f.ben, "viewer").await,
        StatusCode::CREATED
    );
    call(
        &f.app,
        "PATCH",
        &format!("{base}/shares/{}", f.ben),
        Some("anika"),
        Some(json!({"role": "editor"})),
    )
    .await;
    call(&f.app, "POST", &format!("{base}/leave"), Some("ben"), None).await;
    assert_eq!(
        share(&f, "project", f.q1, "anika", f.vera, "viewer").await,
        StatusCode::CREATED
    );
    call(
        &f.app,
        "DELETE",
        &format!("{base}/shares/{}", f.vera),
        Some("anika"),
        None,
    )
    .await;
    call(
        &f.app,
        "POST",
        &format!("{base}/transfer"),
        Some("anika"),
        Some(json!({"new_owner_id": f.nora})),
    )
    .await;
    let (status, history) = call(
        &f.app,
        "GET",
        &format!("{base}/history"),
        Some("nora"),
        None,
    )
    .await;
    assert_eq!(status, StatusCode::OK);
    let events = history["data"].as_array().unwrap();
    let actions: Vec<&str> = events
        .iter()
        .map(|e| e["action"].as_str().unwrap())
        .collect();
    assert_eq!(
        actions,
        [
            "ownership_transferred",
            "share_revoked",
            "shared",
            "share_left",
            "share_role_changed",
            "shared"
        ]
    );
    assert_eq!(events[0]["subject"]["name"], "Nora");
    assert_eq!(events[4]["subject"]["role"], "editor");
    assert_eq!(events[3]["subject"]["name"], "Ben");
    let text = history.to_string();
    assert!(!text.contains("\"subject_email\""));
    // An actor's email is shown the way it was before; subjects have none.
    assert!(events[0]["subject"].get("email").is_none());
}

// --- isolation from v1, accounts, migration -------------------------------------------

#[tokio::test]
async fn api_v1_never_sees_shares_and_its_bodies_do_not_change() {
    let f = fx().await;
    let (status, _) = call(
        &f.app,
        "GET",
        &format!("/api/v1/projects/{}", f.p1),
        Some("vera"),
        None,
    )
    .await;
    assert_eq!(status, StatusCode::NOT_FOUND);
    let (status, _) = call(
        &f.app,
        "GET",
        &format!("/api/v1/yarns/{}", f.y1),
        Some("vera"),
        None,
    )
    .await;
    assert_eq!(status, StatusCode::NOT_FOUND);
    let (status, _) = call(
        &f.app,
        "GET",
        &format!("/api/v1/yarns/{}", f.y2),
        Some("yves"),
        None,
    )
    .await;
    assert_eq!(status, StatusCode::NOT_FOUND);
    let (status, put) = call(
        &f.app,
        "PUT",
        &format!("/api/v1/projects/{}", f.p1),
        Some("eddie"),
        Some(json!({"name": "x"})),
    )
    .await;
    assert_eq!(status, StatusCode::NOT_FOUND, "{put}");
    let (_, list) = call(&f.app, "GET", "/api/v1/projects", Some("vera"), None).await;
    assert!(list["items"].as_array().unwrap().is_empty());
    // Owner responses carry no sharing fields.
    let (_, owner) = call(
        &f.app,
        "GET",
        &format!("/api/v1/projects/{}", f.p1),
        Some("anika"),
        None,
    )
    .await;
    assert!(owner.get("access").is_none());
    let (_, owner) = call(
        &f.app,
        "GET",
        &format!("/api/v1/yarns/{}", f.y2),
        Some("anika"),
        None,
    )
    .await;
    assert!(owner.get("access").is_none());
    let (_, list) = call(&f.app, "GET", "/api/v1/yarns", Some("anika"), None).await;
    assert!(list["items"][0].get("access").is_none());
    // v1 sync: no shared rows, and tombstones for lost access never appear.
    let (_, time) = {
        let (_, body) = call(
            &f.app,
            "GET",
            "/api/v1/sync/projects?limit=50",
            Some("vera"),
            None,
        )
        .await;
        assert!(body["updated"].as_array().unwrap().is_empty());
        (0, body["server_time"].as_str().unwrap().to_owned())
    };
    tick().await;
    call(
        &f.app,
        "DELETE",
        &format!("/api/v2/projects/{}/shares/{}", f.p1, f.vera),
        Some("anika"),
        None,
    )
    .await;
    let (_, body) = call(
        &f.app,
        "GET",
        &format!("/api/v1/sync/projects?since={time}&limit=50"),
        Some("vera"),
        None,
    )
    .await;
    assert!(body["deleted_ids"].as_array().unwrap().is_empty(), "{body}");
    // ... while v2 does carry it.
    let (delta, _) = sync(&f, "projects", "vera", Some(&time)).await;
    assert_eq!(deleted_ids(&delta), vec![f.p1]);
    // v2 responses for owners gain only the additive fields.
    let (_, owner) = call(
        &f.app,
        "GET",
        &format!("/api/v2/projects/{}", f.p1),
        Some("anika"),
        None,
    )
    .await;
    assert_eq!(owner["data"]["access"]["role"], "owner");
    assert_eq!(owner["data"]["owner"]["email"], "anika@example.test");
}

#[tokio::test]
async fn deleting_an_account_cascades_shares_both_ways() {
    let f = fx().await;
    const PASSWORD: &str = "sharing-delete-password";
    let doomed = db::create_user(&f.pool, "doomed@example.test", PASSWORD.to_owned())
        .await
        .unwrap();
    sqlx::query(
        "INSERT INTO api_tokens (user_id, name, token_hash, created_at, expires_at, purpose) \
         VALUES (?, 'doomed', ?, unixepoch(), unixepoch() + 86400, 'api')",
    )
    .bind(doomed)
    .bind(hex::encode(Sha256::digest(pat("doomed").as_bytes())))
    .execute(&f.pool)
    .await
    .unwrap();
    let dp = project(&f.pool, doomed, "Doomed project").await;
    let dy = yarn(&f.pool, doomed, "Doomed yarn").await;
    link(&f.pool, dp, dy).await;
    insert_share(&f.pool, "project", dp, f.vera, "viewer", doomed).await;
    insert_share(&f.pool, "yarn", dy, f.yvonne, "editor", doomed).await;
    // The doomed account is also a recipient of anika's project.
    insert_share(&f.pool, "project", f.q1, doomed, "editor", f.anika).await;
    let (_, vera_projects) = sync(&f, "projects", "vera", None).await;
    let (_, vera_yarns) = sync(&f, "yarns", "vera", None).await;
    let (_, yvonne_yarns) = sync(&f, "yarns", "yvonne", None).await;
    tick().await;
    let (status, body) = call(
        &f.app,
        "POST",
        "/api/v2/account/delete",
        Some("doomed"),
        Some(json!({"confirmation": "DELETE", "current_password": PASSWORD})),
    )
    .await;
    assert_eq!(status, StatusCode::NO_CONTENT, "{body}");
    // Shares in both directions are gone; anika's project and its other
    // shares are untouched.
    let shares: Vec<(String, i64)> =
        sqlx::query_as("SELECT kind, resource_id FROM resource_shares ORDER BY id")
            .fetch_all(&f.pool)
            .await
            .unwrap();
    assert!(shares.iter().all(|(_, id)| *id != dp && *id != dy));
    assert!(!shares.contains(&("project".to_owned(), f.q1)));
    assert!(shares.contains(&("project".to_owned(), f.p1)));
    // Recipients hear that the owned resources are gone.
    let (delta, _) = sync(&f, "projects", "vera", Some(&vera_projects)).await;
    assert_eq!(deleted_ids(&delta), vec![dp]);
    let (delta, _) = sync(&f, "yarns", "vera", Some(&vera_yarns)).await;
    assert_eq!(deleted_ids(&delta), vec![dy]);
    let (delta, _) = sync(&f, "yarns", "yvonne", Some(&yvonne_yarns)).await;
    assert_eq!(deleted_ids(&delta), vec![dy]);
    let (status, _) = call(
        &f.app,
        "GET",
        &format!("/api/v2/projects/{}", f.q1),
        Some("anika"),
        None,
    )
    .await;
    assert_eq!(status, StatusCode::OK);
}

#[tokio::test]
async fn migration_creates_the_share_tables_and_cleans_up_after_deletes() {
    let f = fx().await;
    let tables: Vec<String> = sqlx::query_scalar(
        "SELECT name FROM sqlite_master WHERE type='table' \
         AND name IN ('resource_shares','access_events') ORDER BY name",
    )
    .fetch_all(&f.pool)
    .await
    .unwrap();
    assert_eq!(tables, ["access_events", "resource_shares"]);
    let indexes: Vec<String> = sqlx::query_scalar(
        "SELECT name FROM sqlite_master WHERE type='index' AND tbl_name='resource_shares' \
         AND name = 'resource_shares_user'",
    )
    .fetch_all(&f.pool)
    .await
    .unwrap();
    assert_eq!(indexes.len(), 1);
    // The uniqueness and the role/kind checks are enforced by the schema.
    let duplicate = sqlx::query(
        "INSERT INTO resource_shares (kind, resource_id, user_id, role, created_at, updated_at) \
         VALUES ('project', ?, ?, 'editor', 'x', 'x')",
    )
    .bind(f.p1)
    .bind(f.vera)
    .execute(&f.pool)
    .await;
    assert!(duplicate.is_err());
    let bad_role = sqlx::query(
        "INSERT INTO resource_shares (kind, resource_id, user_id, role, created_at, updated_at) \
         VALUES ('project', ?, ?, 'owner', 'x', 'x')",
    )
    .bind(f.q1)
    .bind(f.vera)
    .execute(&f.pool)
    .await;
    assert!(bad_role.is_err());
    // Deleting the resource removes its shares and access events (no FK can).
    sqlx::query("INSERT INTO access_events VALUES (?, 'project', ?, 'x')")
        .bind(f.vera)
        .bind(f.p1)
        .execute(&f.pool)
        .await
        .unwrap();
    sqlx::query("DELETE FROM projects WHERE id = ?")
        .bind(f.p1)
        .execute(&f.pool)
        .await
        .unwrap();
    sqlx::query("DELETE FROM yarns WHERE id = ?")
        .bind(f.y2)
        .execute(&f.pool)
        .await
        .unwrap();
    let left: i64 = sqlx::query_scalar("SELECT count(*) FROM resource_shares")
        .fetch_one(&f.pool)
        .await
        .unwrap();
    assert_eq!(left, 0);
    let events: i64 = sqlx::query_scalar("SELECT count(*) FROM access_events")
        .fetch_one(&f.pool)
        .await
        .unwrap();
    assert_eq!(events, 0);
}

// --- history emails and re-sharing through links ------------------------------

#[tokio::test]
async fn only_managers_see_emails_in_history_and_other_responses() {
    let f = fx().await;
    // eddie (editor) changes the project; anika changes a derived yarn.
    let (status, _) = call(
        &f.app,
        "PUT",
        &format!("/api/v2/projects/{}", f.p1),
        Some("eddie"),
        Some(json!({"name": "Shawl 2", "yarn_ids": [f.y1]})),
    )
    .await;
    assert_eq!(status, StatusCode::OK);
    call(
        &f.app,
        "PUT",
        &format!("/api/v2/yarns/{}", f.y1),
        Some("anika"),
        Some(json!({"name": "Merino 2"})),
    )
    .await;
    assert_eq!(
        share(&f, "project", f.p1, "anika", f.ben, "viewer").await,
        StatusCode::CREATED
    );
    let project_history = format!("/api/v2/projects/{}/history", f.p1);
    let yarn_history = format!("/api/v2/yarns/{}/history", f.y1);
    for (who, path) in [
        ("vera", &project_history),
        ("eddie", &project_history),
        ("ben", &project_history),
        ("vera", &yarn_history),
        ("eddie", &yarn_history),
    ] {
        let (status, body) = call(&f.app, "GET", path, Some(who), None).await;
        assert_eq!(status, StatusCode::OK);
        let text = body.to_string();
        assert!(!text.contains("example.test"), "{who} {path}: {text}");
        for event in body["data"].as_array().unwrap() {
            assert_eq!(event["actor_email"], "", "{who} {path}");
            assert!(event["actor_name"].is_string());
        }
        let first = body["data"][0]["id"].as_i64().unwrap();
        let (status, detail) =
            call(&f.app, "GET", &format!("{path}/{first}"), Some(who), None).await;
        assert_eq!(status, StatusCode::OK);
        assert!(!detail.to_string().contains("example.test"), "{who} detail");
    }
    // Owner and administrator see them.
    for who in ["anika", "admin"] {
        let (_, body) = call(&f.app, "GET", &project_history, Some(who), None).await;
        let emails: Vec<&str> = body["data"]
            .as_array()
            .unwrap()
            .iter()
            .map(|e| e["actor_email"].as_str().unwrap())
            .collect();
        assert!(emails.contains(&"eddie@example.test"), "{who}: {emails:?}");
    }
    // Avatars of other actors use the readable people path, never the self path.
    let (_, body) = call(&f.app, "GET", &project_history, Some("vera"), None).await;
    assert!(!body.to_string().contains("/media/thumbnails/users/"));
    // Sharing responses, candidates and sync entries carry no email either.
    for (who, path) in [
        (
            "eddie",
            format!("/api/v2/projects/{}/yarn-candidates", f.p1),
        ),
        ("vera", "/api/v2/projects".to_owned()),
        ("vera", "/api/v2/yarns".to_owned()),
        ("ben", "/api/v2/sync/projects?limit=50".to_owned()),
        ("vera", "/api/v2/sync/yarns?limit=50".to_owned()),
        ("vera", format!("/api/v2/yarns/{}", f.y1)),
        ("vera", format!("/api/v2/projects/{}", f.p1)),
    ] {
        let (status, body) = call(&f.app, "GET", &path, Some(who), None).await;
        assert_eq!(status, StatusCode::OK, "{path}");
        assert!(!body.to_string().contains("example.test"), "{who} {path}");
    }
    let (_, body) = call(
        &f.app,
        "POST",
        &format!("/api/v2/projects/{}/transfer", f.q1),
        Some("anika"),
        Some(json!({"new_owner_id": f.nora})),
    )
    .await;
    assert!(!body.to_string().contains("example.test"));
    let (_, body) = call(
        &f.app,
        "GET",
        &format!("/api/v2/projects/{}/history", f.q1),
        Some("anika"),
        None,
    )
    .await;
    assert!(body.to_string().contains("ownership_transferred"));
    assert!(
        !body.to_string().contains("example.test"),
        "transfer event leaks"
    );
}

#[tokio::test]
async fn recipients_cannot_reshare_a_yarn_through_a_link() {
    let f = fx().await;
    // Bob (nora) owns a yarn and shares it with Alice (nick); Carol is ben.
    for role in ["viewer", "editor"] {
        let bob_yarn = yarn(&f.pool, f.nora, "Bob's yarn").await;
        assert_eq!(
            share(&f, "yarn", bob_yarn, "nora", f.nick, role).await,
            StatusCode::CREATED
        );
        // Alice links it on create ...
        let (status, created) = call(
            &f.app,
            "POST",
            "/api/v2/projects",
            Some("nick"),
            Some(json!({"name": "Alice's project", "yarn_ids": [bob_yarn]})),
        )
        .await;
        assert!(status.is_success() || status == StatusCode::UNPROCESSABLE_ENTITY);
        let alice_project = if status.is_success() {
            created["data"]["id"].as_i64().unwrap()
        } else {
            project(&f.pool, f.nick, "Alice's project").await
        };
        // ... and on update.
        let (status, _) = call(
            &f.app,
            "PUT",
            &format!("/api/v2/projects/{alice_project}"),
            Some("nick"),
            Some(json!({"name": "Alice's project", "yarn_ids": [bob_yarn]})),
        )
        .await;
        assert!(status.is_success() || status == StatusCode::UNPROCESSABLE_ENTITY);
        let links: i64 = sqlx::query_scalar("SELECT count(*) FROM project_yarns WHERE yarn_id = ?")
            .bind(bob_yarn)
            .fetch_one(&f.pool)
            .await
            .unwrap();
        assert_eq!(links, 0, "{role}: the link must not exist");
        // Sharing Alice's project with Carol gives Carol nothing of Bob's.
        assert_eq!(
            share(&f, "project", alice_project, "nick", f.ben, "editor").await,
            StatusCode::CREATED
        );
        let (status, _) = call(
            &f.app,
            "GET",
            &format!("/api/v2/yarns/{bob_yarn}"),
            Some("ben"),
            None,
        )
        .await;
        assert_eq!(status, StatusCode::NOT_FOUND, "{role}");
        let (_, list) = call(&f.app, "GET", "/api/v2/yarns", Some("ben"), None).await;
        assert!(!ids(&list).contains(&bob_yarn));
        // Carol, as editor, cannot link it either.
        let (status, _) = call(
            &f.app,
            "PUT",
            &format!("/api/v2/projects/{alice_project}"),
            Some("ben"),
            Some(json!({"name": "Alice's project", "yarn_ids": [bob_yarn]})),
        )
        .await;
        assert_eq!(status, StatusCode::UNPROCESSABLE_ENTITY);
    }
    // A stranger linking a guessed yarn id gets no link and no access.
    let mine = project(&f.pool, f.ben, "Ben's project").await;
    let (status, _) = call(
        &f.app,
        "PUT",
        &format!("/api/v2/projects/{mine}"),
        Some("ben"),
        Some(json!({"name": "Ben's project", "yarn_ids": [f.y3]})),
    )
    .await;
    assert!(status.is_success());
    let links: i64 = sqlx::query_scalar("SELECT count(*) FROM project_yarns WHERE yarn_id = ?")
        .bind(f.y3)
        .fetch_one(&f.pool)
        .await
        .unwrap();
    assert_eq!(links, 0);
    let (status, _) = call(
        &f.app,
        "GET",
        &format!("/api/v2/yarns/{}", f.y3),
        Some("ben"),
        None,
    )
    .await;
    assert_eq!(status, StatusCode::NOT_FOUND);
}

fn owner_names(list: &Value) -> Vec<String> {
    list["data"]
        .as_array()
        .unwrap()
        .iter()
        .map(|owner| owner["name"].as_str().unwrap().to_owned())
        .collect()
}

#[tokio::test]
async fn owner_filter_narrows_lists_and_options_never_reveal_unseen_owners() {
    let f = fx().await;
    let vp = project(&f.pool, f.vera, "Vera's own").await;
    let bp = project(&f.pool, f.ben, "Ben's shared").await;
    let hidden = project(&f.pool, f.ben, "Ben's private").await;
    insert_share(&f.pool, "project", bp, f.vera, "viewer", f.ben).await;

    // Vera: her own project, Anika's shared shawl, Ben's shared project.
    let list = |query: String, who: &'static str| {
        let app = f.app.clone();
        async move {
            let (status, body) = call(
                &app,
                "GET",
                &format!("/api/v2/projects{query}"),
                Some(who),
                None,
            )
            .await;
            assert_eq!(status, StatusCode::OK, "{query}");
            body
        }
    };
    assert_eq!(ids(&list("".to_owned(), "vera").await), vec![f.p1, vp, bp]);
    assert_eq!(
        ids(&list("?owner=all".to_owned(), "vera").await),
        vec![f.p1, vp, bp]
    );
    assert_eq!(ids(&list("?owner=mine".to_owned(), "vera").await), vec![vp]);
    assert_eq!(
        ids(&list("?owner=shared".to_owned(), "vera").await),
        vec![f.p1, bp]
    );
    let anika = format!("?owner={}", f.anika);
    assert_eq!(ids(&list(anika, "vera").await), vec![f.p1]);
    // An owner the caller cannot see items of yields an empty list, never their items.
    let ben = format!("?owner={}&status=in_progress", f.ben);
    let ben_list = list(ben, "eddie").await;
    assert!(ids(&ben_list).is_empty());
    let ben = format!("?owner={}", f.ben);
    assert!(!ids(&list(ben, "eddie").await).contains(&hidden));
    // Combines with the status filter.
    sqlx::query("UPDATE projects SET status = 'done' WHERE id = ?")
        .bind(bp)
        .execute(&f.pool)
        .await
        .unwrap();
    assert_eq!(
        ids(&list("?owner=shared&status=done".to_owned(), "vera").await),
        vec![bp]
    );
    // Bad values are rejected.
    for bad in ["?owner=nobody", "?owner=-3", "?owner=0"] {
        let (status, _) = call(
            &f.app,
            "GET",
            &format!("/api/v2/projects{bad}"),
            Some("vera"),
            None,
        )
        .await;
        assert_eq!(status, StatusCode::UNPROCESSABLE_ENTITY, "{bad}");
    }

    // Options: names only (no email), never the caller, never owners of unseen items.
    let (_, owners) = call(&f.app, "GET", "/api/v2/projects/owners", Some("vera"), None).await;
    assert_eq!(owner_names(&owners), vec!["Anika", "Ben"]);
    assert!(
        owners["data"]
            .as_array()
            .unwrap()
            .iter()
            .all(|owner| owner["email"] == "")
    );
    let (_, owners) = call(
        &f.app,
        "GET",
        "/api/v2/projects/owners",
        Some("eddie"),
        None,
    )
    .await;
    assert_eq!(owner_names(&owners), vec!["Anika"]);
    let (_, owners) = call(&f.app, "GET", "/api/v2/projects/owners", Some("nora"), None).await;
    assert!(owner_names(&owners).is_empty());
    // A member asking for scope=all still only sees their own visible set.
    let (_, owners) = call(
        &f.app,
        "GET",
        "/api/v2/projects/owners?scope=all",
        Some("eddie"),
        None,
    )
    .await;
    assert_eq!(owner_names(&owners), vec!["Anika"]);
    // An administrator sees every other owner, with emails.
    let (_, owners) = call(
        &f.app,
        "GET",
        "/api/v2/projects/owners?scope=all",
        Some("admin"),
        None,
    )
    .await;
    assert_eq!(owner_names(&owners), vec!["Anika", "Ben", "Vera"]);
    assert_eq!(owners["data"][0]["email"], "anika@example.test");

    // Yarns: yvonne sees Anika's shared alpaca only.
    let (_, owners) = call(&f.app, "GET", "/api/v2/yarns/owners", Some("yvonne"), None).await;
    assert_eq!(owner_names(&owners), vec!["Anika"]);
    let (_, shared) = call(
        &f.app,
        "GET",
        "/api/v2/yarns?owner=shared",
        Some("yvonne"),
        None,
    )
    .await;
    assert_eq!(ids(&shared), vec![f.y2]);
    let (_, mine) = call(
        &f.app,
        "GET",
        "/api/v2/yarns?owner=mine",
        Some("yvonne"),
        None,
    )
    .await;
    assert!(ids(&mine).is_empty());
    let (_, owners) = call(&f.app, "GET", "/api/v2/yarns/owners", Some("nick"), None).await;
    assert!(owner_names(&owners).is_empty());
    let (status, _) = call(&f.app, "GET", "/api/v2/yarns?owner=x", Some("yvonne"), None).await;
    assert_eq!(status, StatusCode::UNPROCESSABLE_ENTITY);
    let (status, _) = call(&f.app, "GET", "/api/v2/yarns/owners", None, None).await;
    assert_eq!(status, StatusCode::UNAUTHORIZED);

    // API v1 ignores the parameter.
    let (_, v1) = call(
        &f.app,
        "GET",
        "/api/v1/projects?owner=shared",
        Some("vera"),
        None,
    )
    .await;
    assert_eq!(v1["items"].as_array().unwrap().len(), 1);
}
