//! The trash (API v2): soft delete, hiding, restore, purge, retention,
//! authorization (owner, other account, share recipient, administrator), sync
//! reappearance and the migration.

use axum::{
    body::Body,
    http::{Request, StatusCode},
};
use chrono::{Duration, SecondsFormat, Utc};
use http_body_util::BodyExt;
use serde_json::{Value, json};
use sha2::{Digest, Sha256};
use sqlx::SqlitePool;
use stricknani_server::{app_with_state, db, trash};
use tower::ServiceExt;

const NAMES: [&str; 4] = ["anika", "ben", "vera", "admin"];
const OLD: &str = "2026-01-01T00:00:00.000000Z";

struct Fx {
    _root: tempfile::TempDir,
    app: axum::Router,
    pool: SqlitePool,
    anika: i64,
    vera: i64,
    /// Anika's project: one step, one title image, linked to `y1`, shared with vera (viewer).
    p1: i64,
    /// Anika's yarn, linked to `p1`, with a photo.
    y1: i64,
}

fn pat(name: &str) -> String {
    format!("trash-{name}-api-pat")
}

async fn seed_user(pool: &SqlitePool, name: &str) -> i64 {
    let id = sqlx::query(
        "INSERT INTO users (email, password_hash, is_admin) VALUES (?, 'test-only-hash', ?)",
    )
    .bind(format!("{name}@example.test"))
    .bind(name == "admin")
    .execute(pool)
    .await
    .unwrap()
    .last_insert_rowid();
    sqlx::query(
        "INSERT INTO api_tokens (user_id, name, token_hash, created_at, expires_at, purpose) \
         VALUES (?, 'trash fixture', ?, unixepoch(), unixepoch() + 86400, 'api')",
    )
    .bind(id)
    .bind(hex::encode(Sha256::digest(pat(name).as_bytes())))
    .execute(pool)
    .await
    .unwrap();
    id
}

async fn media(pool: &SqlitePool, owner: i64, project: Option<i64>, yarn: Option<i64>, id: i64) {
    let kind = if project.is_some() { "title" } else { "photo" };
    sqlx::query(
        "INSERT INTO media_files \
         (owner_id,project_id,yarn_id,kind,public_id,filename,original_filename, \
          content_type,size_bytes,position,is_primary,is_project_cover) \
         VALUES (?,?,?,?,?,?,?,'image/png',0,0,1,?)",
    )
    .bind(owner)
    .bind(project)
    .bind(yarn)
    .bind(kind)
    .bind(id)
    .bind(format!("trash-media-{id}.png"))
    .bind(format!("trash-media-{id}.png"))
    .bind(project.is_some())
    .execute(pool)
    .await
    .unwrap();
}

async fn fx() -> Fx {
    let pool = db::connect("sqlite::memory:").await.unwrap();
    let mut ids = Vec::new();
    for name in NAMES {
        ids.push(seed_user(&pool, name).await);
    }
    let [anika, _ben, vera, _admin] = ids[..] else {
        unreachable!()
    };
    let p1: i64 = sqlx::query_scalar(
        "INSERT INTO projects (owner_id,name,tags,created_at,updated_at) \
         VALUES (?,'Shawl','[]',?,?) RETURNING id",
    )
    .bind(anika)
    .bind(OLD)
    .bind(OLD)
    .fetch_one(&pool)
    .await
    .unwrap();
    let y1: i64 = sqlx::query_scalar(
        "INSERT INTO yarns (owner_id,name,created_at,updated_at) VALUES (?,'Merino',?,?) RETURNING id",
    )
    .bind(anika)
    .bind(OLD)
    .bind(OLD)
    .fetch_one(&pool)
    .await
    .unwrap();
    sqlx::query("INSERT INTO project_yarns (project_id, yarn_id) VALUES (?, ?)")
        .bind(p1)
        .bind(y1)
        .execute(&pool)
        .await
        .unwrap();
    sqlx::query(
        "INSERT INTO project_steps (project_id, title, step_number) VALUES (?, 'Cast on', 1)",
    )
    .bind(p1)
    .execute(&pool)
    .await
    .unwrap();
    media(&pool, anika, Some(p1), None, 101).await;
    media(&pool, anika, None, Some(y1), 102).await;
    sqlx::query(
        "INSERT INTO resource_shares (kind, resource_id, user_id, role, created_by, created_at, updated_at) \
         VALUES ('project', ?, ?, 'viewer', ?, ?, ?)",
    )
    .bind(p1)
    .bind(vera)
    .bind(anika)
    .bind(OLD)
    .bind(OLD)
    .execute(&pool)
    .await
    .unwrap();
    let root = tempfile::tempdir().unwrap();
    let state = db::AppState::new(pool.clone())
        .with_secure_cookies(true)
        .with_media_root(root.path().to_path_buf());
    Fx {
        _root: root,
        app: app_with_state(state),
        pool,
        anika,
        vera,
        p1,
        y1,
    }
}

async fn call(f: &Fx, method: &str, path: &str, who: Option<&str>) -> (StatusCode, Value) {
    call_body(f, method, path, who, None).await
}

async fn call_body(
    f: &Fx,
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
    let response = f
        .app
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

async fn count(pool: &SqlitePool, sql: &'static str, id: i64) -> i64 {
    sqlx::query_scalar(sql)
        .bind(id)
        .fetch_one(pool)
        .await
        .unwrap()
}

fn ids(value: &Value, field: &str) -> Vec<i64> {
    let mut ids: Vec<i64> = value["data"][field]
        .as_array()
        .unwrap()
        .iter()
        .map(|item| item.get("id").unwrap_or(item).as_i64().unwrap())
        .collect();
    ids.sort_unstable();
    ids
}

async fn tick() {
    tokio::time::sleep(std::time::Duration::from_millis(5)).await;
}

fn list_ids(body: &Value) -> Vec<(String, i64)> {
    body["data"]
        .as_array()
        .unwrap()
        .iter()
        .map(|item| {
            (
                item["kind"].as_str().unwrap().to_owned(),
                item["id"].as_i64().unwrap(),
            )
        })
        .collect()
}

#[tokio::test]
async fn deleting_a_project_moves_it_to_the_trash_and_keeps_everything() {
    let f = fx().await;
    let p = f.p1;
    let (status, _) = call(
        &f,
        "DELETE",
        &format!("/api/v2/projects/{p}"),
        Some("anika"),
    )
    .await;
    assert_eq!(status, StatusCode::NO_CONTENT);

    // Hidden from its owner, the share recipient, lists, search and sharing.
    for who in ["anika", "vera", "admin"] {
        // Only the owner and administrators can still open it, read-only.
        let (status, detail) = call(&f, "GET", &format!("/api/v2/projects/{p}"), Some(who)).await;
        if who == "vera" {
            assert_eq!(status, StatusCode::NOT_FOUND, "{who}");
        } else {
            assert_eq!(status, StatusCode::OK, "{who}");
            assert!(detail["data"]["trashed_at"].is_string());
        }
        let (_, list) = call(&f, "GET", "/api/v2/projects?q=Shawl", Some(who)).await;
        assert!(list["data"].as_array().unwrap().is_empty(), "{who}");
    }
    let (status, _) = call(
        &f,
        "GET",
        &format!("/api/v2/projects/{p}/shares"),
        Some("anika"),
    )
    .await;
    assert_eq!(status, StatusCode::NOT_FOUND);
    let (status, _) = call_body(
        &f,
        "PUT",
        &format!("/api/v2/projects/{p}"),
        Some("anika"),
        Some(json!({"name": "Edited"})),
    )
    .await;
    assert_eq!(status, StatusCode::NOT_FOUND);
    // The linked yarn no longer lists the project.
    let (_, yarn) = call(&f, "GET", &format!("/api/v2/yarns/{}", f.y1), Some("anika")).await;
    assert_eq!(yarn["data"]["project_ids"], json!([]));

    // Nothing was destroyed: rows, links, shares, media and no file garbage.
    assert_eq!(
        count(&f.pool, "SELECT count(*) FROM projects WHERE id = ?", p).await,
        1
    );
    assert_eq!(
        count(
            &f.pool,
            "SELECT count(*) FROM project_steps WHERE project_id = ?",
            p
        )
        .await,
        1
    );
    assert_eq!(
        count(
            &f.pool,
            "SELECT count(*) FROM media_files WHERE project_id = ?",
            p
        )
        .await,
        1
    );
    assert_eq!(
        count(
            &f.pool,
            "SELECT count(*) FROM project_yarns WHERE project_id = ?",
            p
        )
        .await,
        1
    );
    assert_eq!(
        count(
            &f.pool,
            "SELECT count(*) FROM resource_shares WHERE kind='project' AND resource_id = ?",
            p
        )
        .await,
        1
    );
    let queued: i64 = sqlx::query_scalar("SELECT count(*) FROM media_gc")
        .fetch_one(&f.pool)
        .await
        .unwrap();
    assert_eq!(queued, 0, "files of a trashed project stay on disk");
    let (action, actor): (String, Option<i64>) = sqlx::query_as(
        "SELECT action, actor_user_id FROM audit_events WHERE entity_type='project' AND entity_id=? \
         ORDER BY id DESC LIMIT 1",
    )
    .bind(p)
    .fetch_one(&f.pool)
    .await
    .unwrap();
    assert_eq!((action.as_str(), actor), ("trashed", Some(f.anika)));

    // The trash lists it with the retention countdown.
    let (status, trash) = call(&f, "GET", "/api/v2/trash", Some("anika")).await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(list_ids(&trash), vec![("project".to_owned(), p)]);
    assert_eq!(trash["data"][0]["name"], "Shawl");
    assert_eq!(trash["data"][0]["days_remaining"], 90);
    assert_eq!(trash["meta"]["retention_days"], 90);
}

#[tokio::test]
async fn api_v1_delete_also_moves_to_the_trash() {
    let f = fx().await;
    let (status, _) = call(
        &f,
        "DELETE",
        &format!("/api/v1/projects/{}", f.p1),
        Some("anika"),
    )
    .await;
    assert_eq!(status, StatusCode::NO_CONTENT);
    let (status, _) = call(
        &f,
        "GET",
        &format!("/api/v1/projects/{}", f.p1),
        Some("anika"),
    )
    .await;
    assert_eq!(status, StatusCode::NOT_FOUND);
    let (_, trash) = call(&f, "GET", "/api/v2/trash", Some("anika")).await;
    assert_eq!(list_ids(&trash), vec![("project".to_owned(), f.p1)]);
    let (status, _) = call(
        &f,
        "DELETE",
        &format!("/api/v1/yarns/{}", f.y1),
        Some("anika"),
    )
    .await;
    assert_eq!(status, StatusCode::NO_CONTENT);
    let (_, trash) = call(&f, "GET", "/api/v2/trash", Some("anika")).await;
    assert_eq!(trash["data"].as_array().unwrap().len(), 2);
}

#[tokio::test]
async fn trash_authorization_owner_other_account_recipient_and_administrator() {
    let f = fx().await;
    let p = f.p1;
    call(
        &f,
        "DELETE",
        &format!("/api/v2/projects/{p}"),
        Some("anika"),
    )
    .await;
    let restore = format!("/api/v2/trash/projects/{p}/restore");
    let forever = format!("/api/v2/trash/projects/{p}");

    // Anonymous.
    assert_eq!(
        call(&f, "GET", "/api/v2/trash", None).await.0,
        StatusCode::UNAUTHORIZED
    );
    for status in [
        call(&f, "POST", &restore, None).await.0,
        call(&f, "DELETE", &forever, None).await.0,
    ] {
        assert!(
            matches!(status, StatusCode::UNAUTHORIZED | StatusCode::FORBIDDEN),
            "{status}"
        );
    }
    // Another account and the share recipient see nothing and change nothing.
    for who in ["ben", "vera"] {
        let (_, list) = call(&f, "GET", "/api/v2/trash", Some(who)).await;
        assert!(list["data"].as_array().unwrap().is_empty(), "{who}");
        assert_eq!(
            call(&f, "POST", &restore, Some(who)).await.0,
            StatusCode::NOT_FOUND,
            "{who}"
        );
        assert_eq!(
            call(&f, "DELETE", &forever, Some(who)).await.0,
            StatusCode::NOT_FOUND,
            "{who}"
        );
    }
    // Only an administrator widens the listing to every account.
    let (_, list) = call(&f, "GET", "/api/v2/trash?scope=all", Some("ben")).await;
    assert!(list["data"].as_array().unwrap().is_empty());
    assert_eq!(list["meta"]["scope"], "mine");
    let (_, list) = call(&f, "GET", "/api/v2/trash", Some("admin")).await;
    assert!(list["data"].as_array().unwrap().is_empty());
    let (_, list) = call(&f, "GET", "/api/v2/trash?scope=all", Some("admin")).await;
    assert_eq!(list_ids(&list), vec![("project".to_owned(), p)]);
    assert_eq!(list["data"][0]["owner"]["id"], f.anika);
    assert_eq!(
        call(&f, "GET", "/api/v2/trash?scope=bogus", Some("anika"))
            .await
            .0,
        StatusCode::UNPROCESSABLE_ENTITY
    );
    // Unknown kinds and ids.
    assert_eq!(
        call(&f, "POST", "/api/v2/trash/things/1/restore", Some("anika"))
            .await
            .0,
        StatusCode::NOT_FOUND
    );
    assert_eq!(
        call(
            &f,
            "POST",
            "/api/v2/trash/projects/999999/restore",
            Some("anika")
        )
        .await
        .0,
        StatusCode::NOT_FOUND
    );
    // An administrator may restore someone else's item.
    assert_eq!(
        call(&f, "POST", &restore, Some("admin")).await.0,
        StatusCode::NO_CONTENT
    );
    let (status, _) = call(&f, "GET", &format!("/api/v2/projects/{p}"), Some("anika")).await;
    assert_eq!(status, StatusCode::OK);
    // A live item is not in the trash: restore and purge both answer 404.
    assert_eq!(
        call(&f, "POST", &restore, Some("anika")).await.0,
        StatusCode::NOT_FOUND
    );
    assert_eq!(
        call(&f, "DELETE", &forever, Some("anika")).await.0,
        StatusCode::NOT_FOUND
    );
    assert_eq!(
        count(&f.pool, "SELECT count(*) FROM projects WHERE id = ?", p).await,
        1
    );
}

#[tokio::test]
async fn restore_brings_back_shares_links_and_sync_clients_refetch() {
    let f = fx().await;
    let p = f.p1;
    // A client that synced everything before the deletion.
    let (_, before) = call(&f, "GET", "/api/v2/sync/projects?limit=50", Some("anika")).await;
    assert_eq!(ids(&before, "updated"), vec![p]);
    let since_before = before["data"]["server_time"].as_str().unwrap().to_owned();
    let (_, vera_before) = call(&f, "GET", "/api/v2/sync/projects?limit=50", Some("vera")).await;
    assert_eq!(ids(&vera_before, "updated"), vec![p]);
    let vera_since = vera_before["data"]["server_time"]
        .as_str()
        .unwrap()
        .to_owned();

    tick().await;
    call(
        &f,
        "DELETE",
        &format!("/api/v2/projects/{p}"),
        Some("anika"),
    )
    .await;
    tick().await;
    // The deletion reaches the owner's and the recipient's clients as a tombstone.
    let (_, owner_delta) = call(
        &f,
        "GET",
        &format!("/api/v2/sync/projects?since={since_before}&limit=50"),
        Some("anika"),
    )
    .await;
    assert_eq!(ids(&owner_delta, "deleted_ids"), vec![p]);
    assert!(ids(&owner_delta, "updated").is_empty());
    let after_delete = owner_delta["data"]["server_time"]
        .as_str()
        .unwrap()
        .to_owned();
    let (_, vera_delta) = call(
        &f,
        "GET",
        &format!("/api/v2/sync/projects?since={vera_since}&limit=50"),
        Some("vera"),
    )
    .await;
    assert_eq!(ids(&vera_delta, "deleted_ids"), vec![p]);
    // A fresh full sync does not list it.
    let (_, full) = call(&f, "GET", "/api/v2/sync/projects?limit=50", Some("anika")).await;
    assert!(ids(&full, "updated").is_empty());

    tick().await;
    let (status, _) = call(
        &f,
        "POST",
        &format!("/api/v2/trash/projects/{p}/restore"),
        Some("anika"),
    )
    .await;
    assert_eq!(status, StatusCode::NO_CONTENT);

    // The client that already saw the tombstone fetches the project again.
    let (_, delta) = call(
        &f,
        "GET",
        &format!("/api/v2/sync/projects?since={after_delete}&limit=50"),
        Some("anika"),
    )
    .await;
    assert_eq!(ids(&delta, "updated"), vec![p]);
    assert!(ids(&delta, "deleted_ids").is_empty());
    // So does one that never saw the deletion, and a fresh full sync, without a tombstone.
    let (_, old_client) = call(
        &f,
        "GET",
        &format!("/api/v2/sync/projects?since={since_before}&limit=50"),
        Some("anika"),
    )
    .await;
    assert_eq!(ids(&old_client, "updated"), vec![p]);
    assert!(ids(&old_client, "deleted_ids").is_empty());
    let (_, full) = call(&f, "GET", "/api/v2/sync/projects?limit=50", Some("anika")).await;
    assert_eq!(ids(&full, "updated"), vec![p]);
    assert!(ids(&full, "deleted_ids").is_empty());
    // The share recipient gets it back as well.
    let (_, vera_back) = call(
        &f,
        "GET",
        &format!("/api/v2/sync/projects?since={after_delete}&limit=50"),
        Some("vera"),
    )
    .await;
    assert_eq!(ids(&vera_back, "updated"), vec![p]);
    assert!(ids(&vera_back, "deleted_ids").is_empty());
    let (status, detail) = call(&f, "GET", &format!("/api/v2/projects/{p}"), Some("vera")).await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(detail["data"]["access"]["role"], "viewer");
    // The yarn link and the project's data are intact.
    let (_, detail) = call(&f, "GET", &format!("/api/v2/projects/{p}"), Some("anika")).await;
    assert_eq!(detail["data"]["yarn_ids"], json!([f.y1]));
    assert_eq!(detail["data"]["steps"].as_array().unwrap().len(), 1);
    assert_eq!(
        count(
            &f.pool,
            "SELECT count(*) FROM deletion_events WHERE entity_id = ?",
            p
        )
        .await,
        0
    );
    let (trash_list, _): (i64, i64) = (
        call(&f, "GET", "/api/v2/trash", Some("anika")).await.1["data"]
            .as_array()
            .unwrap()
            .len() as i64,
        0,
    );
    assert_eq!(trash_list, 0);
    let actions: Vec<String> = sqlx::query_scalar(
        "SELECT action FROM audit_events WHERE entity_type='project' AND entity_id=? ORDER BY id",
    )
    .bind(p)
    .fetch_all(&f.pool)
    .await
    .unwrap();
    assert_eq!(actions, vec!["trashed", "restored"]);
}

#[tokio::test]
async fn trashed_yarns_vanish_from_projects_and_derived_access_and_come_back() {
    let f = fx().await;
    let y = f.y1;
    // The recipient of the shared project reads the linked yarn.
    let (status, _) = call(&f, "GET", &format!("/api/v2/yarns/{y}"), Some("vera")).await;
    assert_eq!(status, StatusCode::OK);
    call(&f, "DELETE", &format!("/api/v2/yarns/{y}"), Some("anika")).await;
    for who in ["anika", "vera"] {
        let (status, _) = call(&f, "GET", &format!("/api/v2/yarns/{y}"), Some(who)).await;
        assert_eq!(
            status,
            if who == "anika" {
                StatusCode::OK
            } else {
                StatusCode::NOT_FOUND
            },
            "{who}"
        );
        let (_, list) = call(&f, "GET", "/api/v2/yarns", Some(who)).await;
        assert!(list["data"].as_array().unwrap().is_empty(), "{who}");
    }
    let (_, project) = call(
        &f,
        "GET",
        &format!("/api/v2/projects/{}", f.p1),
        Some("anika"),
    )
    .await;
    assert_eq!(project["data"]["yarn_ids"], json!([]));
    assert_eq!(
        count(
            &f.pool,
            "SELECT count(*) FROM media_files WHERE yarn_id = ?",
            y
        )
        .await,
        1
    );
    let (_, trash) = call(&f, "GET", "/api/v2/trash", Some("anika")).await;
    assert_eq!(list_ids(&trash), vec![("yarn".to_owned(), y)]);
    assert_eq!(
        call(
            &f,
            "POST",
            &format!("/api/v2/trash/yarns/{y}/restore"),
            Some("ben")
        )
        .await
        .0,
        StatusCode::NOT_FOUND
    );
    assert_eq!(
        call(
            &f,
            "POST",
            &format!("/api/v2/trash/yarns/{y}/restore"),
            Some("anika")
        )
        .await
        .0,
        StatusCode::NO_CONTENT
    );
    let (status, yarn) = call(&f, "GET", &format!("/api/v2/yarns/{y}"), Some("vera")).await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(yarn["data"]["project_ids"], json!([f.p1]));
    let (_, project) = call(
        &f,
        "GET",
        &format!("/api/v2/projects/{}", f.p1),
        Some("anika"),
    )
    .await;
    assert_eq!(project["data"]["yarn_ids"], json!([y]));
}

#[tokio::test]
async fn trashing_a_project_ends_derived_yarn_access_until_restore() {
    let f = fx().await;
    // Vera sees the yarn of the shared project through it.
    let (_, mine) = call(&f, "GET", "/api/v2/yarns", Some("vera")).await;
    assert_eq!(mine["data"].as_array().unwrap().len(), 1);
    call(
        &f,
        "DELETE",
        &format!("/api/v2/projects/{}", f.p1),
        Some("anika"),
    )
    .await;
    let (status, _) = call(&f, "GET", &format!("/api/v2/yarns/{}", f.y1), Some("vera")).await;
    assert_eq!(status, StatusCode::NOT_FOUND);
    call(
        &f,
        "POST",
        &format!("/api/v2/trash/projects/{}/restore", f.p1),
        Some("anika"),
    )
    .await;
    let (status, _) = call(&f, "GET", &format!("/api/v2/yarns/{}", f.y1), Some("vera")).await;
    assert_eq!(status, StatusCode::OK);
}

#[tokio::test]
async fn delete_forever_destroys_the_item_and_queues_its_files() {
    let f = fx().await;
    let p = f.p1;
    call(
        &f,
        "DELETE",
        &format!("/api/v2/projects/{p}"),
        Some("anika"),
    )
    .await;
    assert_eq!(
        call(
            &f,
            "DELETE",
            &format!("/api/v2/trash/projects/{p}"),
            Some("anika")
        )
        .await
        .0,
        StatusCode::NO_CONTENT
    );
    assert_eq!(
        count(&f.pool, "SELECT count(*) FROM projects WHERE id = ?", p).await,
        0
    );
    assert_eq!(
        count(
            &f.pool,
            "SELECT count(*) FROM project_steps WHERE project_id = ?",
            p
        )
        .await,
        0
    );
    assert_eq!(
        count(
            &f.pool,
            "SELECT count(*) FROM media_files WHERE project_id = ?",
            p
        )
        .await,
        0
    );
    assert_eq!(
        count(
            &f.pool,
            "SELECT count(*) FROM resource_shares WHERE kind='project' AND resource_id = ?",
            p
        )
        .await,
        0
    );
    // The media sweeper removes the file later, as for any hard delete.
    let queued: i64 = sqlx::query_scalar("SELECT count(*) FROM media_gc")
        .fetch_one(&f.pool)
        .await
        .unwrap();
    assert_eq!(queued, 1);
    let (_, trash) = call(&f, "GET", "/api/v2/trash", Some("anika")).await;
    assert!(trash["data"].as_array().unwrap().is_empty());
    let (action, actor): (String, Option<i64>) = sqlx::query_as(
        "SELECT action, actor_user_id FROM audit_events WHERE entity_type='project' AND entity_id=? \
         ORDER BY id DESC LIMIT 1",
    )
    .bind(p)
    .fetch_one(&f.pool)
    .await
    .unwrap();
    assert_eq!((action.as_str(), actor), ("purged", Some(f.anika)));
    // The yarn is untouched and its link is gone with the project.
    assert_eq!(
        count(&f.pool, "SELECT count(*) FROM yarns WHERE id = ?", f.y1).await,
        1
    );
    // Purging a yarn works the same way.
    call(
        &f,
        "DELETE",
        &format!("/api/v2/yarns/{}", f.y1),
        Some("anika"),
    )
    .await;
    assert_eq!(
        call(
            &f,
            "DELETE",
            &format!("/api/v2/trash/yarns/{}", f.y1),
            Some("admin")
        )
        .await
        .0,
        StatusCode::NO_CONTENT
    );
    assert_eq!(
        count(&f.pool, "SELECT count(*) FROM yarns WHERE id = ?", f.y1).await,
        0
    );
}

#[tokio::test]
async fn items_are_purged_after_ninety_days_by_the_system() {
    let f = fx().await;
    let (p, y) = (f.p1, f.y1);
    call(
        &f,
        "DELETE",
        &format!("/api/v2/projects/{p}"),
        Some("anika"),
    )
    .await;
    call(&f, "DELETE", &format!("/api/v2/yarns/{y}"), Some("anika")).await;
    let live: i64 = sqlx::query_scalar(
        "INSERT INTO projects (owner_id,name,tags,created_at,updated_at) \
         VALUES (?,'Live','[]',?,?) RETURNING id",
    )
    .bind(f.anika)
    .bind(OLD)
    .bind(OLD)
    .fetch_one(&f.pool)
    .await
    .unwrap();

    // Not due yet.
    assert_eq!(trash::purge_expired(&f.pool, Utc::now()).await.unwrap(), 0);
    // The yarn is 91 days old, the project 89.
    let stamp = |days: i64| {
        (Utc::now() - Duration::days(days)).to_rfc3339_opts(SecondsFormat::Micros, true)
    };
    sqlx::query("UPDATE yarns SET deleted_at = ? WHERE id = ?")
        .bind(stamp(91))
        .bind(y)
        .execute(&f.pool)
        .await
        .unwrap();
    sqlx::query("UPDATE projects SET deleted_at = ? WHERE id = ?")
        .bind(stamp(89))
        .bind(p)
        .execute(&f.pool)
        .await
        .unwrap();
    let (_, list) = call(&f, "GET", "/api/v2/trash", Some("anika")).await;
    let days: Vec<(String, i64)> = list["data"]
        .as_array()
        .unwrap()
        .iter()
        .map(|i| {
            (
                i["kind"].as_str().unwrap().to_owned(),
                i["days_remaining"].as_i64().unwrap(),
            )
        })
        .collect();
    assert!(days.contains(&("project".to_owned(), 1)), "{days:?}");
    assert!(days.contains(&("yarn".to_owned(), 0)), "{days:?}");

    assert_eq!(trash::purge_expired(&f.pool, Utc::now()).await.unwrap(), 1);
    assert_eq!(
        count(&f.pool, "SELECT count(*) FROM yarns WHERE id = ?", y).await,
        0
    );
    assert_eq!(
        count(&f.pool, "SELECT count(*) FROM projects WHERE id = ?", p).await,
        1
    );
    assert_eq!(
        count(&f.pool, "SELECT count(*) FROM projects WHERE id = ?", live).await,
        1
    );
    let actor: Option<i64> = sqlx::query_scalar(
        "SELECT actor_user_id FROM audit_events WHERE entity_type='yarn' AND entity_id=? AND action='purged'",
    )
    .bind(y)
    .fetch_one(&f.pool)
    .await
    .unwrap();
    assert_eq!(actor, None);
    // A later sweep takes the project once it is old enough.
    sqlx::query("UPDATE projects SET deleted_at = ? WHERE id = ?")
        .bind(stamp(120))
        .bind(p)
        .execute(&f.pool)
        .await
        .unwrap();
    assert_eq!(trash::purge_expired(&f.pool, Utc::now()).await.unwrap(), 1);
    assert_eq!(
        count(&f.pool, "SELECT count(*) FROM projects WHERE id = ?", p).await,
        0
    );
    assert_eq!(trash::purge_expired(&f.pool, Utc::now()).await.unwrap(), 0);
    let _ = f.vera;
}

#[tokio::test]
async fn migration_adds_nullable_trash_columns_and_keeps_existing_rows() {
    // Apply the schema as it was before the trash, add rows, then migrate.
    let pool = sqlx::sqlite::SqlitePoolOptions::new()
        .max_connections(1)
        .connect("sqlite::memory:")
        .await
        .unwrap();
    sqlx::query("PRAGMA foreign_keys = ON")
        .execute(&pool)
        .await
        .unwrap();
    let dir = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("migrations");
    let mut files: Vec<_> = std::fs::read_dir(dir)
        .unwrap()
        .map(|entry| entry.unwrap().path())
        .filter(|path| path.extension().is_some_and(|ext| ext == "sql"))
        .collect();
    files.sort();
    let trash_migration = files
        .iter()
        .position(|path| path.file_name().unwrap() == "0031_trash.sql")
        .expect("trash migration exists");
    for path in &files[..trash_migration] {
        sqlx::raw_sql(sqlx::AssertSqlSafe(std::fs::read_to_string(path).unwrap()))
            .execute(&pool)
            .await
            .unwrap_or_else(|error| panic!("{}: {error}", path.display()));
    }
    let user: i64 = sqlx::query_scalar(
        "INSERT INTO users (email, password_hash) VALUES ('m@example.test', 'x') RETURNING id",
    )
    .fetch_one(&pool)
    .await
    .unwrap();
    sqlx::query("INSERT INTO projects (owner_id, name, tags, created_at, updated_at) VALUES (?, 'Old', '[]', ?, ?)")
        .bind(user)
        .bind(OLD)
        .bind(OLD)
        .execute(&pool)
        .await
        .unwrap();
    sqlx::query(
        "INSERT INTO yarns (owner_id, name, created_at, updated_at) VALUES (?, 'Old', ?, ?)",
    )
    .bind(user)
    .bind(OLD)
    .bind(OLD)
    .execute(&pool)
    .await
    .unwrap();
    sqlx::raw_sql(sqlx::AssertSqlSafe(
        std::fs::read_to_string(&files[trash_migration]).unwrap(),
    ))
    .execute(&pool)
    .await
    .unwrap();
    for table in ["projects", "yarns"] {
        let columns: Vec<(String, String)> = sqlx::query_as(sqlx::AssertSqlSafe(format!(
            "SELECT name, type FROM pragma_table_info('{table}') WHERE name IN ('deleted_at','deleted_by')"
        )))
        .fetch_all(&pool)
        .await
        .unwrap();
        assert_eq!(columns.len(), 2, "{table}");
        let trashed: i64 = sqlx::query_scalar(sqlx::AssertSqlSafe(format!(
            "SELECT count(*) FROM {table} WHERE deleted_at IS NOT NULL OR deleted_by IS NOT NULL"
        )))
        .fetch_one(&pool)
        .await
        .unwrap();
        assert_eq!(trashed, 0, "existing rows are not trashed in {table}");
        let live: i64 =
            sqlx::query_scalar(sqlx::AssertSqlSafe(format!("SELECT count(*) FROM {table}")))
                .fetch_one(&pool)
                .await
                .unwrap();
        assert_eq!(live, 1);
        let index: i64 = sqlx::query_scalar(sqlx::AssertSqlSafe(format!(
            "SELECT count(*) FROM pragma_index_list('{table}') WHERE name = '{table}_trash'"
        )))
        .fetch_one(&pool)
        .await
        .unwrap();
        assert_eq!(index, 1);
    }
    // Deleting the account that trashed an item keeps the item.
    sqlx::query("UPDATE projects SET deleted_at = ?, deleted_by = ?")
        .bind(OLD)
        .bind(user)
        .execute(&pool)
        .await
        .unwrap();
    let other: i64 = sqlx::query_scalar(
        "INSERT INTO users (email, password_hash) VALUES ('n@example.test', 'x') RETURNING id",
    )
    .fetch_one(&pool)
    .await
    .unwrap();
    sqlx::query("UPDATE projects SET deleted_by = ?")
        .bind(other)
        .execute(&pool)
        .await
        .unwrap();
    sqlx::query("DELETE FROM users WHERE id = ?")
        .bind(other)
        .execute(&pool)
        .await
        .unwrap();
    let by: Option<i64> = sqlx::query_scalar("SELECT deleted_by FROM projects")
        .fetch_one(&pool)
        .await
        .unwrap();
    assert_eq!(by, None);
}

const ADMIN_SESSION: &str = "trash-admin-browser-session";

async fn admin_audit(f: &Fx) -> Vec<Value> {
    let admin: i64 = sqlx::query_scalar("SELECT id FROM users WHERE email = 'admin@example.test'")
        .fetch_one(&f.pool)
        .await
        .unwrap();
    let _ = sqlx::query(
        "INSERT INTO api_tokens (user_id, name, token_hash, created_at, expires_at, purpose) \
         VALUES (?, 'trash audit', ?, unixepoch(), unixepoch() + 86400, 'browser')",
    )
    .bind(admin)
    .bind(hex::encode(Sha256::digest(ADMIN_SESSION.as_bytes())))
    .execute(&f.pool)
    .await;
    let response = f
        .app
        .clone()
        .oneshot(
            Request::get("/api/v2/admin/audit")
                .header(
                    "cookie",
                    format!("__Host-stricknani_session={ADMIN_SESSION}"),
                )
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    let bytes = response.into_body().collect().await.unwrap().to_bytes();
    let body: Value = serde_json::from_slice(&bytes).unwrap();
    body["data"].as_array().unwrap().clone()
}

fn events_of<'a>(events: &'a [Value], id: i64, kind: &str) -> Vec<&'a Value> {
    // Newest first in the log; return oldest first.
    let mut found: Vec<&Value> = events
        .iter()
        .filter(|event| event["entity_id"] == id && event["entity_type"] == kind)
        .collect();
    found.reverse();
    found
}

#[tokio::test]
async fn audit_log_records_and_describes_every_lifecycle_step() {
    let f = fx().await;
    let p = f.p1;
    call(
        &f,
        "DELETE",
        &format!("/api/v2/projects/{p}"),
        Some("anika"),
    )
    .await;
    // An administrator restores the owner's project, the owner trashes it again.
    assert_eq!(
        call(
            &f,
            "POST",
            &format!("/api/v2/trash/projects/{p}/restore"),
            Some("admin")
        )
        .await
        .0,
        StatusCode::NO_CONTENT
    );
    call(
        &f,
        "DELETE",
        &format!("/api/v2/projects/{p}"),
        Some("anika"),
    )
    .await;
    // A hard delete written before the trash existed.
    sqlx::query(
        "INSERT INTO audit_events (actor_user_id, entity_type, entity_id, action, created_at, resource_name_snapshot) \
         VALUES (?, 'project', 9999, 'deleted', '2026-01-02T00:00:00.000000Z', 'Old hat')",
    )
    .bind(f.anika)
    .execute(&f.pool)
    .await
    .unwrap();

    let events = admin_audit(&f).await;
    let log = events_of(&events, p, "project");
    let actions: Vec<&str> = log.iter().map(|e| e["action"].as_str().unwrap()).collect();
    assert_eq!(actions, vec!["trashed", "restored", "trashed"]);
    for event in &log {
        // The name snapshot keeps the entries readable; the resource is in the trash now.
        assert_eq!(event["resource"]["name"], "Shawl");
        assert_eq!(event["resource"]["state"], "in_trash");
        assert_eq!(event["resource"]["exists"], false);
        assert_eq!(event["resource"]["trash"]["days_remaining"], 90);
        assert_eq!(event["resource"]["trash"]["can_restore"], true);
        assert!(
            event["resource"]["href"].is_null()
                || event["resource"]["href"] != json!(format!("/app/projects/{p}"))
        );
        assert!(event.get("system").is_none());
    }
    // Owner acted: no subject. Administrator acted: the owner is the subject.
    assert!(log[0]["subject"].is_null());
    assert_eq!(log[1]["actor_email"], "admin@example.test");
    assert_eq!(log[1]["subject"]["id"], f.anika);
    assert_eq!(log[1]["subject"]["name"], "Anika");
    assert!(log[2]["subject"].is_null());

    // The legacy hard delete reads as permanently deleted: gone, with name, actor and time.
    let legacy = events_of(&events, 9999, "project");
    assert_eq!(legacy.len(), 1);
    assert_eq!(legacy[0]["action"], "deleted");
    assert_eq!(legacy[0]["resource"]["name"], "Old hat");
    assert_eq!(legacy[0]["resource"]["state"], "gone");
    assert!(legacy[0]["resource"].get("trash").is_none());

    // Restored: state back to active everywhere.
    call(
        &f,
        "POST",
        &format!("/api/v2/trash/projects/{p}/restore"),
        Some("anika"),
    )
    .await;
    let events = admin_audit(&f).await;
    let log = events_of(&events, p, "project");
    assert_eq!(log.last().unwrap()["action"], "restored");
    assert!(log.last().unwrap()["subject"].is_null());
    assert!(log.iter().all(|e| e["resource"]["state"] == "active"));

    // The automatic purge is a system event without actor; the manual one names its actor.
    call(
        &f,
        "DELETE",
        &format!("/api/v2/projects/{p}"),
        Some("anika"),
    )
    .await;
    sqlx::query("UPDATE projects SET deleted_at = ? WHERE id = ?")
        .bind((Utc::now() - Duration::days(100)).to_rfc3339_opts(SecondsFormat::Micros, true))
        .bind(p)
        .execute(&f.pool)
        .await
        .unwrap();
    assert_eq!(trash::purge_expired(&f.pool, Utc::now()).await.unwrap(), 1);
    call(
        &f,
        "DELETE",
        &format!("/api/v2/yarns/{}", f.y1),
        Some("anika"),
    )
    .await;
    call(
        &f,
        "DELETE",
        &format!("/api/v2/trash/yarns/{}", f.y1),
        Some("admin"),
    )
    .await;
    let events = admin_audit(&f).await;
    let log = events_of(&events, p, "project");
    let purged = log.last().unwrap();
    assert_eq!(purged["action"], "purged");
    assert_eq!(purged["system"], true);
    assert!(purged["actor_email"].is_null());
    assert_eq!(purged["subject"]["id"], f.anika);
    assert_eq!(purged["resource"]["name"], "Shawl");
    assert_eq!(purged["resource"]["state"], "gone");
    assert!(log.iter().all(|e| e["resource"]["state"] == "gone"));
    let yarn_log = events_of(&events, f.y1, "yarn");
    let yarn_actions: Vec<&str> = yarn_log
        .iter()
        .map(|e| e["action"].as_str().unwrap())
        .collect();
    assert_eq!(yarn_actions, vec!["trashed", "purged"]);
    let manual = yarn_log.last().unwrap();
    assert!(manual.get("system").is_none());
    assert_eq!(manual["actor_email"], "admin@example.test");
    assert_eq!(manual["subject"]["name"], "Anika");
    assert_eq!(manual["resource"]["name"], "Merino");
}

#[tokio::test]
async fn owner_activity_feed_marks_trashed_items_and_offers_restore_to_the_owner_only() {
    let f = fx().await;
    call(
        &f,
        "DELETE",
        &format!("/api/v2/projects/{}", f.p1),
        Some("anika"),
    )
    .await;
    let (status, feed) = call(&f, "GET", "/api/v2/activity", Some("anika")).await;
    assert_eq!(status, StatusCode::OK, "{feed}");
    let item = feed["data"]
        .as_array()
        .unwrap()
        .iter()
        .find(|item| item["entity_id"] == f.p1 && item["action"] == "trashed")
        .expect("trashed entry in the feed");
    assert_eq!(item["state"], "in_trash");
    assert_eq!(item["exists"], false);
    assert_eq!(item["trash"]["can_restore"], true);
    assert_eq!(item["entity_name"], "Shawl");
    assert!(item["href"].is_null());
}

// --- children and categories -------------------------------------------------

async fn step_image(f: &Fx, step: i64) {
    sqlx::query(
        "INSERT INTO media_files \
         (owner_id,project_id,step_id,kind,public_id,filename,original_filename, \
          content_type,size_bytes,position,is_primary,is_project_cover) \
         VALUES (?,?,?,'step',103,'trash-media-103.png','row.png','image/png',0,0,0,0)",
    )
    .bind(f.anika)
    .bind(f.p1)
    .bind(step)
    .execute(&f.pool)
    .await
    .unwrap();
}

async fn gc_count(f: &Fx) -> i64 {
    sqlx::query_scalar("SELECT count(*) FROM media_gc")
        .fetch_one(&f.pool)
        .await
        .unwrap()
}

#[tokio::test]
async fn deleting_media_trashes_it_and_keeps_the_file() {
    let f = fx().await;
    let p = f.p1;
    let (status, _) = call(
        &f,
        "DELETE",
        &format!("/api/v2/projects/{p}/images/101"),
        Some("anika"),
    )
    .await;
    assert_eq!(status, StatusCode::NO_CONTENT);
    assert_eq!(
        count(
            &f.pool,
            "SELECT count(*) FROM media_files WHERE public_id = ? AND kind = 'title'",
            101
        )
        .await,
        0
    );
    assert_eq!(
        count(
            &f.pool,
            "SELECT count(*) FROM trashed_media WHERE public_id = ?",
            101
        )
        .await,
        1
    );
    assert_eq!(
        gc_count(&f).await,
        0,
        "the file stays on disk while trashed"
    );
    let (_, trash) = call(&f, "GET", "/api/v2/trash", Some("anika")).await;
    assert_eq!(list_ids(&trash), vec![("image".to_owned(), 101)]);
    assert_eq!(trash["data"][0]["parent"]["kind"], "project");
    assert_eq!(trash["data"][0]["parent"]["id"], p);
    assert_eq!(trash["data"][0]["parent"]["name"], "Shawl");
    let (action, media_kind, public_id): (String, String, i64) = sqlx::query_as(
        "SELECT action, media_kind, media_public_id FROM audit_events \
         WHERE entity_type='project' AND entity_id=? ORDER BY id DESC LIMIT 1",
    )
    .bind(p)
    .fetch_one(&f.pool)
    .await
    .unwrap();
    assert_eq!(
        (action.as_str(), media_kind.as_str(), public_id),
        ("trashed", "image", 101)
    );

    // Not for strangers.
    let restore = "/api/v2/trash/images/101/restore";
    assert_eq!(
        call(&f, "POST", restore, Some("ben")).await.0,
        StatusCode::NOT_FOUND
    );
    assert_eq!(
        call(&f, "POST", restore, Some("vera")).await.0,
        StatusCode::NOT_FOUND
    );
    assert_eq!(
        call(&f, "DELETE", "/api/v2/trash/images/101", Some("ben"))
            .await
            .0,
        StatusCode::NOT_FOUND
    );
    assert_eq!(
        call(&f, "POST", restore, Some("anika")).await.0,
        StatusCode::NO_CONTENT
    );
    assert_eq!(
        count(
            &f.pool,
            "SELECT count(*) FROM media_files WHERE public_id = ? AND kind = 'title'",
            101
        )
        .await,
        1
    );
    assert_eq!(
        count(
            &f.pool,
            "SELECT count(*) FROM trashed_media WHERE public_id = ?",
            101
        )
        .await,
        0
    );
    assert_eq!(gc_count(&f).await, 0, "restoring must not queue the file");
    assert_eq!(
        call(&f, "POST", restore, Some("anika")).await.0,
        StatusCode::NOT_FOUND
    );

    // Deleting forever queues the file for the sweeper.
    call(
        &f,
        "DELETE",
        &format!("/api/v2/projects/{p}/images/101"),
        Some("anika"),
    )
    .await;
    assert_eq!(
        call(&f, "DELETE", "/api/v2/trash/images/101", Some("admin"))
            .await
            .0,
        StatusCode::NO_CONTENT
    );
    assert_eq!(gc_count(&f).await, 1);
    let actions: Vec<String> = sqlx::query_scalar(
        "SELECT action FROM audit_events WHERE entity_type='project' AND entity_id=? AND media_public_id=101 ORDER BY id",
    )
    .bind(p)
    .fetch_all(&f.pool)
    .await
    .unwrap();
    assert_eq!(actions, vec!["trashed", "restored", "trashed", "purged"]);

    // A yarn photo.
    let y = f.y1;
    assert_eq!(
        call(
            &f,
            "DELETE",
            &format!("/api/v2/yarns/{y}/photos/102"),
            Some("anika")
        )
        .await
        .0,
        StatusCode::NO_CONTENT
    );
    let (_, trash) = call(&f, "GET", "/api/v2/trash", Some("anika")).await;
    assert_eq!(list_ids(&trash), vec![("photo".to_owned(), 102)]);
    assert_eq!(trash["data"][0]["parent"]["kind"], "yarn");
    assert_eq!(
        call(
            &f,
            "POST",
            "/api/v2/trash/photos/102/restore",
            Some("anika")
        )
        .await
        .0,
        StatusCode::NO_CONTENT
    );
    assert_eq!(
        count(
            &f.pool,
            "SELECT count(*) FROM media_files WHERE yarn_id = ?",
            y
        )
        .await,
        1
    );
    // The restored photo is the only one, so it may be primary again.
    let primary: bool = sqlx::query_scalar("SELECT is_primary FROM media_files WHERE yarn_id = ?")
        .bind(y)
        .fetch_one(&f.pool)
        .await
        .unwrap();
    assert!(primary);
}

#[tokio::test]
async fn a_child_cannot_be_restored_while_its_parent_is_in_the_trash() {
    let f = fx().await;
    let p = f.p1;
    call(
        &f,
        "DELETE",
        &format!("/api/v2/projects/{p}/images/101"),
        Some("anika"),
    )
    .await;
    call(
        &f,
        "DELETE",
        &format!("/api/v2/projects/{p}"),
        Some("anika"),
    )
    .await;
    // Only the parent is listed; the child waits behind it.
    let (_, trash) = call(&f, "GET", "/api/v2/trash", Some("anika")).await;
    assert_eq!(list_ids(&trash), vec![("project".to_owned(), p)]);
    let (status, body) = call(
        &f,
        "POST",
        "/api/v2/trash/images/101/restore",
        Some("anika"),
    )
    .await;
    assert_eq!(status, StatusCode::CONFLICT);
    assert_eq!(body["error"]["code"], "parent_in_trash");
    call(
        &f,
        "POST",
        &format!("/api/v2/trash/projects/{p}/restore"),
        Some("anika"),
    )
    .await;
    let (_, trash) = call(&f, "GET", "/api/v2/trash", Some("anika")).await;
    assert_eq!(list_ids(&trash), vec![("image".to_owned(), 101)]);
    assert_eq!(
        call(
            &f,
            "POST",
            "/api/v2/trash/images/101/restore",
            Some("anika")
        )
        .await
        .0,
        StatusCode::NO_CONTENT
    );
}

#[tokio::test]
async fn a_trashed_project_brings_back_its_children_and_a_purged_one_queues_all_files() {
    let f = fx().await;
    let p = f.p1;
    call(
        &f,
        "DELETE",
        &format!("/api/v2/projects/{p}"),
        Some("anika"),
    )
    .await;
    call(
        &f,
        "POST",
        &format!("/api/v2/trash/projects/{p}/restore"),
        Some("anika"),
    )
    .await;
    let (_, detail) = call(&f, "GET", &format!("/api/v2/projects/{p}"), Some("anika")).await;
    assert_eq!(detail["data"]["images"].as_array().unwrap().len(), 1);
    // A child trashed on its own earlier is purged together with its project.
    call(
        &f,
        "DELETE",
        &format!("/api/v2/projects/{p}/images/101"),
        Some("anika"),
    )
    .await;
    call(
        &f,
        "DELETE",
        &format!("/api/v2/projects/{p}"),
        Some("anika"),
    )
    .await;
    call(
        &f,
        "DELETE",
        &format!("/api/v2/trash/projects/{p}"),
        Some("anika"),
    )
    .await;
    assert_eq!(
        count(
            &f.pool,
            "SELECT count(*) FROM trashed_media WHERE project_id = ?",
            p
        )
        .await,
        0
    );
    assert_eq!(gc_count(&f).await, 1);
}

#[tokio::test]
async fn removing_a_step_trashes_it_with_its_images_and_restore_brings_both_back() {
    let f = fx().await;
    let p = f.p1;
    let step: i64 = sqlx::query_scalar("SELECT id FROM project_steps WHERE project_id = ?")
        .bind(p)
        .fetch_one(&f.pool)
        .await
        .unwrap();
    step_image(&f, step).await;
    let (status, _) = call_body(
        &f,
        "PUT",
        &format!("/api/v2/projects/{p}"),
        Some("anika"),
        Some(json!({"name": "Shawl", "steps": []})),
    )
    .await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(
        count(
            &f.pool,
            "SELECT count(*) FROM project_steps WHERE project_id = ?",
            p
        )
        .await,
        0
    );
    assert_eq!(
        count(
            &f.pool,
            "SELECT count(*) FROM trashed_steps WHERE project_id = ?",
            p
        )
        .await,
        1
    );
    assert_eq!(
        count(
            &f.pool,
            "SELECT count(*) FROM trashed_media WHERE step_id = ? AND with_step = 1",
            step
        )
        .await,
        1
    );
    assert_eq!(gc_count(&f).await, 0);
    // The step is listed, its image is not (it comes back with the step).
    let (_, trash) = call(&f, "GET", "/api/v2/trash", Some("anika")).await;
    assert_eq!(list_ids(&trash), vec![("step".to_owned(), step)]);
    assert_eq!(trash["data"][0]["name"], "Cast on");
    assert_eq!(trash["data"][0]["parent"]["id"], p);
    assert_eq!(
        call(
            &f,
            "POST",
            &format!("/api/v2/trash/steps/{step}/restore"),
            Some("ben")
        )
        .await
        .0,
        StatusCode::NOT_FOUND
    );
    assert_eq!(
        call(
            &f,
            "POST",
            &format!("/api/v2/trash/steps/{step}/restore"),
            Some("anika")
        )
        .await
        .0,
        StatusCode::NO_CONTENT
    );
    assert_eq!(
        count(
            &f.pool,
            "SELECT count(*) FROM project_steps WHERE id = ?",
            step
        )
        .await,
        1
    );
    assert_eq!(
        count(
            &f.pool,
            "SELECT count(*) FROM media_files WHERE step_id = ?",
            step
        )
        .await,
        1
    );
    assert_eq!(
        count(
            &f.pool,
            "SELECT count(*) FROM trashed_media WHERE step_id = ?",
            step
        )
        .await,
        0
    );
    assert_eq!(gc_count(&f).await, 0);
    let (action, name): (String, String) = sqlx::query_as(
        "SELECT action, resource_name_snapshot FROM audit_events WHERE action LIKE 'step_%' \
         ORDER BY id DESC LIMIT 1",
    )
    .fetch_one(&f.pool)
    .await
    .unwrap();
    assert_eq!(
        (action.as_str(), name.as_str()),
        ("step_restored", "Shawl \u{203a} Cast on")
    );
    // Remove it again and delete it forever: the step image's file is queued.
    call_body(
        &f,
        "PUT",
        &format!("/api/v2/projects/{p}"),
        Some("anika"),
        Some(json!({"name": "Shawl", "steps": []})),
    )
    .await;
    assert_eq!(
        call(
            &f,
            "DELETE",
            &format!("/api/v2/trash/steps/{step}"),
            Some("anika")
        )
        .await
        .0,
        StatusCode::NO_CONTENT
    );
    assert_eq!(
        count(
            &f.pool,
            "SELECT count(*) FROM trashed_steps WHERE id = ?",
            step
        )
        .await,
        0
    );
    assert_eq!(
        count(
            &f.pool,
            "SELECT count(*) FROM trashed_media WHERE step_id = ?",
            step
        )
        .await,
        0
    );
    assert_eq!(gc_count(&f).await, 1);
}

#[tokio::test]
async fn deleting_a_category_trashes_it_and_restore_relinks_its_projects() {
    let f = fx().await;
    let (status, created) = call_body(
        &f,
        "POST",
        "/api/v2/categories",
        Some("anika"),
        Some(json!({"name": "Shawls"})),
    )
    .await;
    assert_eq!(status, StatusCode::CREATED, "{created}");
    let category = created["data"]["id"].as_i64().unwrap();
    sqlx::query("UPDATE projects SET category_id = ? WHERE id = ?")
        .bind(category)
        .bind(f.p1)
        .execute(&f.pool)
        .await
        .unwrap();
    let (_, before) = call(&f, "GET", "/api/v2/sync/categories?limit=50", Some("anika")).await;
    let since = before["data"]["server_time"].as_str().unwrap().to_owned();
    tick().await;
    assert_eq!(
        call(
            &f,
            "DELETE",
            &format!("/api/v2/categories/{category}"),
            Some("anika")
        )
        .await
        .0,
        StatusCode::NO_CONTENT
    );
    tick().await;
    let (_, delta) = call(
        &f,
        "GET",
        &format!("/api/v2/sync/categories?since={since}&limit=50"),
        Some("anika"),
    )
    .await;
    assert_eq!(ids(&delta, "deleted_ids"), vec![category]);
    let (_, project) = call(
        &f,
        "GET",
        &format!("/api/v2/projects/{}", f.p1),
        Some("anika"),
    )
    .await;
    assert!(project["data"]["category"].is_null());
    let (_, trash) = call(&f, "GET", "/api/v2/trash", Some("anika")).await;
    assert_eq!(list_ids(&trash), vec![("category".to_owned(), category)]);
    assert_eq!(trash["data"][0]["name"], "Shawls");
    assert_eq!(
        call(
            &f,
            "POST",
            &format!("/api/v2/trash/categories/{category}/restore"),
            Some("ben")
        )
        .await
        .0,
        StatusCode::NOT_FOUND
    );
    let after_delete = delta["data"]["server_time"].as_str().unwrap().to_owned();
    tick().await;
    assert_eq!(
        call(
            &f,
            "POST",
            &format!("/api/v2/trash/categories/{category}/restore"),
            Some("anika")
        )
        .await
        .0,
        StatusCode::NO_CONTENT
    );
    let (_, project) = call(
        &f,
        "GET",
        &format!("/api/v2/projects/{}", f.p1),
        Some("anika"),
    )
    .await;
    assert_eq!(project["data"]["category"], "Shawls");
    let (_, delta) = call(
        &f,
        "GET",
        &format!("/api/v2/sync/categories?since={after_delete}&limit=50"),
        Some("anika"),
    )
    .await;
    assert_eq!(ids(&delta, "updated"), vec![category]);
    assert!(ids(&delta, "deleted_ids").is_empty());
    let (_, full) = call(&f, "GET", "/api/v2/sync/categories?limit=50", Some("anika")).await;
    assert!(ids(&full, "deleted_ids").is_empty());

    // The name may be used again meanwhile: then the restore is refused.
    call(
        &f,
        "DELETE",
        &format!("/api/v2/categories/{category}"),
        Some("anika"),
    )
    .await;
    call_body(
        &f,
        "POST",
        "/api/v2/categories",
        Some("anika"),
        Some(json!({"name": "shawls"})),
    )
    .await;
    let (status, body) = call(
        &f,
        "POST",
        &format!("/api/v2/trash/categories/{category}/restore"),
        Some("anika"),
    )
    .await;
    assert_eq!(status, StatusCode::CONFLICT);
    assert_eq!(body["error"]["code"], "name_taken");
    assert_eq!(
        call(
            &f,
            "DELETE",
            &format!("/api/v2/trash/categories/{category}"),
            Some("anika")
        )
        .await
        .0,
        StatusCode::NO_CONTENT
    );
    let actions: Vec<String> = sqlx::query_scalar(
        "SELECT action FROM audit_events WHERE entity_type='category' AND entity_id=? ORDER BY id",
    )
    .bind(category)
    .fetch_all(&f.pool)
    .await
    .unwrap();
    assert_eq!(
        actions,
        vec!["created", "trashed", "restored", "trashed", "purged"]
    );
}

#[tokio::test]
async fn children_and_categories_are_purged_after_ninety_days_too() {
    let f = fx().await;
    call(
        &f,
        "DELETE",
        &format!("/api/v2/projects/{}/images/101", f.p1),
        Some("anika"),
    )
    .await;
    call(
        &f,
        "DELETE",
        &format!("/api/v2/yarns/{}/photos/102", f.y1),
        Some("anika"),
    )
    .await;
    let step: i64 = sqlx::query_scalar("SELECT id FROM project_steps WHERE project_id = ?")
        .bind(f.p1)
        .fetch_one(&f.pool)
        .await
        .unwrap();
    call_body(
        &f,
        "PUT",
        &format!("/api/v2/projects/{}", f.p1),
        Some("anika"),
        Some(json!({"name":"Shawl","steps":[]})),
    )
    .await;
    let category: i64 =
        sqlx::query_scalar("INSERT INTO categories (user_id, name) VALUES (?, 'Old') RETURNING id")
            .bind(f.anika)
            .fetch_one(&f.pool)
            .await
            .unwrap();
    call(
        &f,
        "DELETE",
        &format!("/api/v2/categories/{category}"),
        Some("anika"),
    )
    .await;
    assert_eq!(trash::purge_expired(&f.pool, Utc::now()).await.unwrap(), 0);
    let old = (Utc::now() - Duration::days(91)).to_rfc3339_opts(SecondsFormat::Micros, true);
    for table in ["trashed_media", "trashed_steps", "trashed_categories"] {
        sqlx::query(sqlx::AssertSqlSafe(format!(
            "UPDATE {table} SET deleted_at = ?"
        )))
        .bind(&old)
        .execute(&f.pool)
        .await
        .unwrap();
    }
    assert_eq!(trash::purge_expired(&f.pool, Utc::now()).await.unwrap(), 4);
    for table in ["trashed_media", "trashed_steps", "trashed_categories"] {
        let left: i64 =
            sqlx::query_scalar(sqlx::AssertSqlSafe(format!("SELECT count(*) FROM {table}")))
                .fetch_one(&f.pool)
                .await
                .unwrap();
        assert_eq!(left, 0, "{table}");
    }
    assert_eq!(gc_count(&f).await, 2);
    let _ = step;
    let systems: i64 = sqlx::query_scalar(
        "SELECT count(*) FROM audit_events WHERE action IN ('purged','step_purged') AND actor_user_id IS NULL",
    )
    .fetch_one(&f.pool)
    .await
    .unwrap();
    assert_eq!(systems, 4);
}

#[tokio::test]
async fn the_trash_tables_keep_every_media_column() {
    let pool = db::connect("sqlite::memory:").await.unwrap();
    let live: Vec<String> =
        sqlx::query_scalar("SELECT name FROM pragma_table_info('media_files') WHERE name != 'id'")
            .fetch_all(&pool)
            .await
            .unwrap();
    let trashed: Vec<String> =
        sqlx::query_scalar("SELECT name FROM pragma_table_info('trashed_media')")
            .fetch_all(&pool)
            .await
            .unwrap();
    for column in live {
        assert!(
            trashed.contains(&column),
            "add `{column}` to trashed_media and media::TRASHED_MEDIA_COLUMNS"
        );
    }
}

// --- trashed lists and read-only detail ----------------------------------------

#[tokio::test]
async fn lists_exclude_the_trash_by_default_and_show_it_with_trashed_only() {
    let f = fx().await;
    let (p, y) = (f.p1, f.y1);
    call(
        &f,
        "DELETE",
        &format!("/api/v2/projects/{p}"),
        Some("anika"),
    )
    .await;
    call(&f, "DELETE", &format!("/api/v2/yarns/{y}"), Some("anika")).await;
    let live: i64 = sqlx::query_scalar(
        "INSERT INTO projects (owner_id,name,tags,created_at,updated_at) \
         VALUES (?,'Live','[]',?,?) RETURNING id",
    )
    .bind(f.anika)
    .bind(OLD)
    .bind(OLD)
    .fetch_one(&f.pool)
    .await
    .unwrap();

    for kind in ["projects", "yarns"] {
        let id = if kind == "projects" { p } else { y };
        let (_, list) = call(&f, "GET", &format!("/api/v2/{kind}"), Some("anika")).await;
        assert!(
            list["data"]
                .as_array()
                .unwrap()
                .iter()
                .all(|i| i["id"] != id),
            "{kind}"
        );
        let (status, only) = call(
            &f,
            "GET",
            &format!("/api/v2/{kind}?trashed=only"),
            Some("anika"),
        )
        .await;
        assert_eq!(status, StatusCode::OK, "{only}");
        let items = only["data"].as_array().unwrap();
        assert_eq!(items.len(), 1, "{kind}");
        assert_eq!(items[0]["id"], id);
        assert!(items[0]["trashed_at"].is_string());
        assert!(items[0]["purge_at"].is_string());
        assert_eq!(items[0]["access"]["can_edit"], false);
        assert_eq!(items[0]["access"]["can_manage"], false);
        // Others never see the trash, not even a share recipient.
        for who in ["ben", "vera"] {
            let (_, only) = call(
                &f,
                "GET",
                &format!("/api/v2/{kind}?trashed=only"),
                Some(who),
            )
            .await;
            assert!(only["data"].as_array().unwrap().is_empty(), "{kind} {who}");
        }
        // Administrators see every account's trash and may narrow it by owner.
        let (_, only) = call(
            &f,
            "GET",
            &format!("/api/v2/{kind}?trashed=only"),
            Some("admin"),
        )
        .await;
        assert_eq!(only["data"].as_array().unwrap().len(), 1, "{kind}");
        let (_, only) = call(
            &f,
            "GET",
            &format!("/api/v2/{kind}?trashed=only&owner_id={}", f.anika),
            Some("admin"),
        )
        .await;
        assert_eq!(only["data"].as_array().unwrap().len(), 1, "{kind}");
        let (_, only) = call(
            &f,
            "GET",
            &format!("/api/v2/{kind}?trashed=only&owner_id={}", f.vera),
            Some("admin"),
        )
        .await;
        assert!(only["data"].as_array().unwrap().is_empty(), "{kind}");
        assert_eq!(
            call(
                &f,
                "GET",
                &format!("/api/v2/{kind}?trashed=maybe"),
                Some("anika")
            )
            .await
            .0,
            StatusCode::UNPROCESSABLE_ENTITY
        );
    }
    // Live items never show up in the trashed list.
    let (_, only) = call(&f, "GET", "/api/v2/projects?trashed=only", Some("anika")).await;
    assert!(
        only["data"]
            .as_array()
            .unwrap()
            .iter()
            .all(|i| i["id"] != live)
    );
    // A live item has no trash timestamps.
    let (_, list) = call(&f, "GET", "/api/v2/projects", Some("anika")).await;
    assert_eq!(list["data"][0]["id"], live);
    assert!(list["data"][0].get("trashed_at").is_none_or(Value::is_null));
}

#[tokio::test]
async fn a_trashed_project_or_yarn_opens_read_only_for_its_owner_and_administrators() {
    let f = fx().await;
    let (p, y) = (f.p1, f.y1);
    call(
        &f,
        "DELETE",
        &format!("/api/v2/projects/{p}"),
        Some("anika"),
    )
    .await;
    call(&f, "DELETE", &format!("/api/v2/yarns/{y}"), Some("anika")).await;
    for (kind, id) in [("projects", p), ("yarns", y)] {
        for who in ["anika", "admin"] {
            let (status, detail) =
                call(&f, "GET", &format!("/api/v2/{kind}/{id}"), Some(who)).await;
            assert_eq!(status, StatusCode::OK, "{kind} {who}: {detail}");
            assert!(detail["data"]["trashed_at"].is_string());
            assert!(detail["data"]["purge_at"].is_string());
            assert_eq!(detail["data"]["access"]["can_edit"], false);
            assert_eq!(detail["data"]["access"]["can_manage"], false);
        }
        for who in ["ben", "vera"] {
            let (status, _) = call(&f, "GET", &format!("/api/v2/{kind}/{id}"), Some(who)).await;
            assert_eq!(status, StatusCode::NOT_FOUND, "{kind} {who}");
        }
        // Nothing can be changed while it is trashed.
        for (method, path, body) in [
            (
                "PUT",
                format!("/api/v2/{kind}/{id}"),
                Some(json!({"name": "X"})),
            ),
            ("DELETE", format!("/api/v2/{kind}/{id}"), None),
            (
                "POST",
                format!("/api/v2/{kind}/{id}/shares"),
                Some(json!({"user_id": f.vera, "role": "viewer"})),
            ),
        ] {
            let (status, _) = call_body(&f, method, &path, Some("anika"), body).await;
            assert_eq!(status, StatusCode::NOT_FOUND, "{method} {path}");
        }
    }
    // The trashed project still shows its content.
    let (_, detail) = call(&f, "GET", &format!("/api/v2/projects/{p}"), Some("anika")).await;
    assert_eq!(detail["data"]["name"], "Shawl");
    assert_eq!(detail["data"]["steps"].as_array().unwrap().len(), 1);
    // Restoring makes it a normal item again.
    call(
        &f,
        "POST",
        &format!("/api/v2/trash/projects/{p}/restore"),
        Some("anika"),
    )
    .await;
    let (_, detail) = call(&f, "GET", &format!("/api/v2/projects/{p}"), Some("anika")).await;
    assert!(detail["data"]["trashed_at"].is_null());
    assert_eq!(detail["data"]["access"]["can_edit"], true);
}

#[tokio::test]
async fn audit_state_covers_categories_and_hides_restore_for_child_events() {
    let f = fx().await;
    let category: i64 = sqlx::query_scalar(
        "INSERT INTO categories (user_id, name) VALUES (?, 'Hats') RETURNING id",
    )
    .bind(f.anika)
    .fetch_one(&f.pool)
    .await
    .unwrap();
    call(
        &f,
        "DELETE",
        &format!("/api/v2/categories/{category}"),
        Some("anika"),
    )
    .await;
    call(
        &f,
        "DELETE",
        &format!("/api/v2/projects/{}/images/101", f.p1),
        Some("anika"),
    )
    .await;
    call(
        &f,
        "DELETE",
        &format!("/api/v2/projects/{}", f.p1),
        Some("anika"),
    )
    .await;
    let events = admin_audit(&f).await;
    let cat = events_of(&events, category, "category");
    let last = cat.last().unwrap();
    assert_eq!(last["action"], "trashed");
    assert_eq!(last["resource"]["name"], "Hats");
    assert_eq!(last["resource"]["state"], "in_trash");
    assert_eq!(last["resource"]["trash"]["can_restore"], true);
    // The image event is about a file: the project is in the trash now, but the
    // event offers no restore (the Trash page lists the project).
    let image = events_of(&events, f.p1, "project")
        .into_iter()
        .find(|event| event["media"]["id"] == 101)
        .expect("image event");
    assert_eq!(image["resource"]["state"], "in_trash");
    assert!(image["resource"].get("trash").is_none());
}

#[tokio::test]
async fn trashed_only_honours_the_owner_filter_and_trashed_items_leave_status_and_owner_lists() {
    let f = fx().await;
    let (p, y) = (f.p1, f.y1);
    // Before the trash: vera sees anika's shared project, so anika is one of her owners.
    let (_, owners) = call(&f, "GET", "/api/v2/projects/owners", Some("vera")).await;
    assert_eq!(owners["data"].as_array().unwrap().len(), 1, "{owners}");
    for kind in ["projects", "yarns"] {
        call(
            &f,
            "DELETE",
            &format!("/api/v2/{kind}/{}", if kind == "projects" { p } else { y }),
            Some("anika"),
        )
        .await;
    }
    // Trashed items no longer make their owner an option for recipients or administrators.
    for (who, kind) in [
        ("vera", "projects"),
        ("admin", "projects"),
        ("admin", "yarns"),
    ] {
        let (_, owners) = call(&f, "GET", &format!("/api/v2/{kind}/owners"), Some(who)).await;
        assert!(
            owners["data"].as_array().unwrap().is_empty(),
            "{who} {kind}"
        );
    }
    // Status lists never contain the trash.
    let (_, in_progress) = call(
        &f,
        "GET",
        "/api/v2/projects?status=in_progress&owner=mine",
        Some("anika"),
    )
    .await;
    assert!(in_progress["data"].as_array().unwrap().is_empty());

    for kind in ["projects", "yarns"] {
        let n = |body: &Value| body["data"].as_array().unwrap().len();
        let anika = f.anika;
        let vera = f.vera;
        let cases: [(&str, String, usize); 8] = [
            ("anika", "owner=me".to_owned(), 1),
            ("anika", "owner=mine".to_owned(), 1),
            ("anika", "owner=shared".to_owned(), 0),
            ("anika", format!("owner={vera}"), 0),
            ("admin", format!("owner={anika}"), 1),
            ("admin", "owner=shared".to_owned(), 1),
            ("admin", "owner=me".to_owned(), 0),
            ("admin", format!("owner_id={anika}"), 1),
        ];
        for (who, filter, expected) in cases {
            let (status, body) = call(
                &f,
                "GET",
                &format!("/api/v2/{kind}?trashed=only&{filter}"),
                Some(who),
            )
            .await;
            assert_eq!(status, StatusCode::OK, "{kind} {who} {filter}: {body}");
            assert_eq!(n(&body), expected, "{kind} {who} {filter}: {body}");
        }
        // Recipients never see the trash through a share, whatever they ask for.
        let (_, body) = call(
            &f,
            "GET",
            &format!("/api/v2/{kind}?trashed=only&owner=shared"),
            Some("vera"),
        )
        .await;
        assert!(body["data"].as_array().unwrap().is_empty(), "{kind}");
    }
}

async fn get_media(f: &Fx, path: &str, who: &str) -> (StatusCode, Vec<u8>) {
    let response = f
        .app
        .clone()
        .oneshot(
            Request::get(path)
                .header("authorization", format!("Bearer {}", pat(who)))
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();
    let status = response.status();
    let bytes = response.into_body().collect().await.unwrap().to_bytes();
    (status, bytes.to_vec())
}

/// Write the stored file and its thumbnail of `trash-media-{id}.png`.
fn write_media_files(f: &Fx, subdir: &str, parent: i64, id: i64) {
    let name = format!("trash-media-{id}");
    let source = f._root.path().join(subdir).join(parent.to_string());
    let thumbs = f
        ._root
        .path()
        .join("thumbnails")
        .join(subdir)
        .join(parent.to_string());
    std::fs::create_dir_all(&source).unwrap();
    std::fs::create_dir_all(&thumbs).unwrap();
    std::fs::write(source.join(format!("{name}.png")), b"source bytes").unwrap();
    std::fs::write(thumbs.join(format!("thumb_{name}.jpg")), b"thumb bytes").unwrap();
}

#[tokio::test]
async fn legacy_url_aliases_survive_trash_and_restore() {
    let f = fx().await;
    let p = f.p1;
    write_media_files(&f, "projects", p, 101);
    let media_id: i64 = sqlx::query_scalar("SELECT id FROM media_files WHERE public_id = 101")
        .fetch_one(&f.pool)
        .await
        .unwrap();
    sqlx::query(
        "INSERT INTO media_url_aliases (subdir, parent_id, legacy_filename, legacy_thumbnail, media_id) \
         VALUES ('projects', ?, 'old name.png', 'thumb_old name.jpg', ?)",
    )
    .bind(p)
    .bind(media_id)
    .execute(&f.pool)
    .await
    .unwrap();
    let source = format!("/media/projects/{p}/old%20name.png");
    let thumb = format!("/media/thumbnails/projects/{p}/thumb_old%20name.jpg");

    assert_eq!(get_media(&f, &source, "anika").await.0, StatusCode::OK);
    assert_eq!(get_media(&f, &thumb, "anika").await.0, StatusCode::OK);

    let (status, _) = call(
        &f,
        "DELETE",
        &format!("/api/v2/projects/{p}/images/101"),
        Some("anika"),
    )
    .await;
    assert_eq!(status, StatusCode::NO_CONTENT);
    // The aliases moved with the trashed row.
    assert_eq!(
        count(
            &f.pool,
            "SELECT count(*) FROM trashed_media_url_aliases WHERE media_id = ?",
            media_id
        )
        .await,
        1
    );
    // The old URLs are gone for everybody while the file is in the trash.
    for who in ["ben", "vera", "anika", "admin"] {
        for url in [&source, &thumb] {
            assert_eq!(
                get_media(&f, url, who).await.0,
                StatusCode::NOT_FOUND,
                "{who} {url}"
            );
        }
    }

    let (status, _) = call(
        &f,
        "POST",
        "/api/v2/trash/images/101/restore",
        Some("anika"),
    )
    .await;
    assert_eq!(status, StatusCode::NO_CONTENT);
    assert_eq!(
        count(
            &f.pool,
            "SELECT count(*) FROM media_url_aliases WHERE media_id = ?",
            media_id
        )
        .await,
        1
    );
    assert_eq!(
        count(
            &f.pool,
            "SELECT count(*) FROM trashed_media_url_aliases WHERE media_id = ?",
            media_id
        )
        .await,
        0
    );
    assert_eq!(
        get_media(&f, &source, "anika").await,
        (StatusCode::OK, b"source bytes".to_vec())
    );
    assert_eq!(get_media(&f, &thumb, "anika").await.0, StatusCode::OK);
}

#[tokio::test]
async fn purging_trashed_media_drops_its_aliases() {
    let f = fx().await;
    let p = f.p1;
    let media_id: i64 = sqlx::query_scalar("SELECT id FROM media_files WHERE public_id = 101")
        .fetch_one(&f.pool)
        .await
        .unwrap();
    sqlx::query(
        "INSERT INTO media_url_aliases (subdir, parent_id, legacy_filename, media_id) \
         VALUES ('projects', ?, 'old.png', ?)",
    )
    .bind(p)
    .bind(media_id)
    .execute(&f.pool)
    .await
    .unwrap();
    call(
        &f,
        "DELETE",
        &format!("/api/v2/projects/{p}/images/101"),
        Some("anika"),
    )
    .await;
    let (status, _) = call(&f, "DELETE", "/api/v2/trash/images/101", Some("anika")).await;
    assert_eq!(status, StatusCode::NO_CONTENT);
    assert_eq!(
        count(
            &f.pool,
            "SELECT count(*) FROM trashed_media_url_aliases WHERE media_id = ?",
            media_id
        )
        .await,
        0
    );
    assert_eq!(
        count(
            &f.pool,
            "SELECT count(*) FROM media_url_aliases WHERE media_id = ?",
            media_id
        )
        .await,
        0
    );
}

#[tokio::test]
async fn trash_items_carry_a_thumbnail_the_owner_and_admin_can_load() {
    let f = fx().await;
    let (p, y) = (f.p1, f.y1);
    write_media_files(&f, "projects", p, 101);
    write_media_files(&f, "yarns", y, 102);
    // A second project image, trashed on its own, and the whole yarn.
    sqlx::query(
        "INSERT INTO media_files \
         (owner_id,project_id,kind,public_id,filename,original_filename,content_type,size_bytes, \
          position,is_primary,is_project_cover) \
         VALUES (?,?,'title',103,'trash-media-103.png','trash-media-103.png','image/png',0,1,0,0)",
    )
    .bind(f.anika)
    .bind(p)
    .execute(&f.pool)
    .await
    .unwrap();
    write_media_files(&f, "projects", p, 103);
    call(
        &f,
        "DELETE",
        &format!("/api/v2/projects/{p}/images/103"),
        Some("anika"),
    )
    .await;
    call(&f, "DELETE", &format!("/api/v2/yarns/{y}"), Some("anika")).await;
    call(
        &f,
        "DELETE",
        &format!("/api/v2/projects/{p}"),
        Some("anika"),
    )
    .await;

    let (_, trash) = call(&f, "GET", "/api/v2/trash", Some("anika")).await;
    let thumb_of = |kind: &str| {
        trash["data"]
            .as_array()
            .unwrap()
            .iter()
            .find(|item| item["kind"] == kind)
            .and_then(|item| item["thumbnail_url"].as_str())
            .map(str::to_owned)
    };
    assert_eq!(
        thumb_of("project").as_deref(),
        Some(format!("/media/thumbnails/projects/{p}/thumb_trash-media-101.jpg").as_str())
    );
    assert_eq!(
        thumb_of("yarn").as_deref(),
        Some(format!("/media/thumbnails/yarns/{y}/thumb_trash-media-102.jpg").as_str())
    );
    // The project is in the trash, so its separately trashed image is not listed.
    assert!(thumb_of("image").is_none());
    for kind in ["project", "yarn"] {
        let url = thumb_of(kind).unwrap();
        assert_eq!(get_media(&f, &url, "anika").await.0, StatusCode::OK);
        assert_eq!(get_media(&f, &url, "admin").await.0, StatusCode::OK);
        assert_ne!(get_media(&f, &url, "ben").await.0, StatusCode::OK);
    }

    // Restore the project: its trashed image is listed with a trash thumbnail, which only the
    // owner and administrators can load (the file's normal URLs stay 404).
    call(
        &f,
        "POST",
        &format!("/api/v2/trash/projects/{p}/restore"),
        Some("anika"),
    )
    .await;
    let (_, trash) = call(&f, "GET", "/api/v2/trash", Some("anika")).await;
    let image = trash["data"]
        .as_array()
        .unwrap()
        .iter()
        .find(|item| item["kind"] == "image")
        .unwrap();
    let url = image["thumbnail_url"].as_str().unwrap().to_owned();
    assert_eq!(url, "/api/v2/trash/images/103/thumbnail");
    assert_eq!(
        get_media(&f, &url, "anika").await,
        (StatusCode::OK, b"thumb bytes".to_vec())
    );
    assert_eq!(get_media(&f, &url, "admin").await.0, StatusCode::OK);
    assert_eq!(get_media(&f, &url, "ben").await.0, StatusCode::NOT_FOUND);
    assert_eq!(get_media(&f, &url, "vera").await.0, StatusCode::NOT_FOUND);
    let direct = format!("/media/thumbnails/projects/{p}/thumb_trash-media-103.jpg");
    assert_eq!(
        get_media(&f, &direct, "anika").await.0,
        StatusCode::NOT_FOUND
    );
    // Restored: the trash thumbnail is gone, the normal one is back.
    call(
        &f,
        "POST",
        "/api/v2/trash/images/103/restore",
        Some("anika"),
    )
    .await;
    assert_eq!(get_media(&f, &url, "anika").await.0, StatusCode::NOT_FOUND);
    assert_eq!(get_media(&f, &direct, "anika").await.0, StatusCode::OK);
}
