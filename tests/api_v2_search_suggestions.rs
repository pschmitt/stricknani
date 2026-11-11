//! Typeahead filter suggestions: tags, categories and owners, matched without regard to case
//! or diacritics. Owners are suggested to administrators only; everyone else only gets values
//! from the items they can see.

use axum::{
    body::Body,
    http::{Request, StatusCode},
};
use http_body_util::BodyExt;
use sha2::{Digest, Sha256};
use sqlx::SqlitePool;
use stricknani_server::{app_with_state, db};
use tower::ServiceExt;

const ADMIN_PAT: &str = "suggest-admin-api-pat";
const ANIKA_PAT: &str = "suggest-anika-api-pat";
const BEN_PAT: &str = "suggest-ben-api-pat";

struct Fixture {
    _root: tempfile::TempDir,
    app: axum::Router,
    pool: SqlitePool,
    anika_id: i64,
    ben_id: i64,
}

async fn seed_user(pool: &SqlitePool, email: &str, token: &str, admin: bool) -> i64 {
    let id = sqlx::query(
        "INSERT INTO users (email, password_hash, is_admin) VALUES (?, 'test-only-hash', ?)",
    )
    .bind(email)
    .bind(admin)
    .execute(pool)
    .await
    .unwrap()
    .last_insert_rowid();
    sqlx::query(
        "INSERT INTO api_tokens (user_id, name, token_hash, created_at, expires_at, purpose) \
         VALUES (?, 'scope fixture', ?, unixepoch(), unixepoch() + 86400, 'api')",
    )
    .bind(id)
    .bind(hex::encode(Sha256::digest(token.as_bytes())))
    .execute(pool)
    .await
    .unwrap();
    id
}

async fn fixture() -> Fixture {
    let pool = db::connect("sqlite::memory:").await.unwrap();
    seed_user(&pool, "admin@example.test", ADMIN_PAT, true).await;
    let anika_id = seed_user(&pool, "anika@example.test", ANIKA_PAT, false).await;
    let ben_id = seed_user(&pool, "ben@example.test", BEN_PAT, false).await;
    let root = tempfile::tempdir().unwrap();
    let state = db::AppState::new(pool.clone())
        .with_secure_cookies(true)
        .with_media_root(root.path().to_path_buf());
    Fixture {
        _root: root,
        app: app_with_state(state),
        pool,
        anika_id,
        ben_id,
    }
}

fn micros(value: &str) -> String {
    chrono::DateTime::parse_from_rfc3339(value)
        .unwrap()
        .with_timezone(&chrono::Utc)
        .to_rfc3339_opts(chrono::SecondsFormat::Micros, true)
}

async fn project(pool: &SqlitePool, owner: i64, name: &str, at: &str) -> i64 {
    let at = &micros(at);
    sqlx::query_scalar(
        "INSERT INTO projects (owner_id,name,tags,created_at,updated_at) \
         VALUES (?,?,'[]',?,?) RETURNING id",
    )
    .bind(owner)
    .bind(name)
    .bind(at)
    .bind(at)
    .fetch_one(pool)
    .await
    .unwrap()
}

async fn set_project_tags(pool: &SqlitePool, project_id: i64, tags: &[&str]) {
    sqlx::query("UPDATE projects SET tags = ? WHERE id = ?")
        .bind(serde_json::to_string(tags).unwrap())
        .bind(project_id)
        .execute(pool)
        .await
        .unwrap();
}

async fn yarn(pool: &SqlitePool, owner: i64, name: &str, at: &str) -> i64 {
    let at = &micros(at);
    sqlx::query_scalar(
        "INSERT INTO yarns (owner_id,name,created_at,updated_at) VALUES (?,?,?,?) RETURNING id",
    )
    .bind(owner)
    .bind(name)
    .bind(at)
    .bind(at)
    .fetch_one(pool)
    .await
    .unwrap()
}

async fn call(
    app: &axum::Router,
    method: &str,
    path: &str,
    pat: &str,
    body: Option<serde_json::Value>,
) -> (StatusCode, serde_json::Value) {
    let mut request = Request::builder()
        .method(method)
        .uri(path)
        .header("authorization", format!("Bearer {pat}"));
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
    let value = serde_json::from_slice(&bytes).unwrap_or(serde_json::Value::Null);
    (status, value)
}

async fn categorize(pool: &SqlitePool, owner: i64, project_id: i64, name: &str) {
    let id: i64 = sqlx::query_scalar(
        "INSERT INTO categories (user_id, name) VALUES (?, ?) \
         ON CONFLICT(user_id, name) DO UPDATE SET name = name RETURNING id",
    )
    .bind(owner)
    .bind(name)
    .fetch_one(pool)
    .await
    .unwrap();
    sqlx::query("UPDATE projects SET category_id = ? WHERE id = ?")
        .bind(id)
        .bind(project_id)
        .execute(pool)
        .await
        .unwrap();
}

fn tag_names(body: &serde_json::Value) -> Vec<&str> {
    body["data"]["tags"]
        .as_array()
        .unwrap()
        .iter()
        .map(|tag| tag["tag"].as_str().unwrap())
        .collect()
}

fn category_names(body: &serde_json::Value) -> Vec<&str> {
    body["data"]["categories"]
        .as_array()
        .unwrap()
        .iter()
        .map(|category| category["name"].as_str().unwrap())
        .collect()
}

#[tokio::test]
async fn suggestions_need_a_signed_in_user_and_a_known_kind() {
    let f = fixture().await;
    let response = f
        .app
        .clone()
        .oneshot(
            Request::get("/api/v2/search/suggestions?q=a")
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::UNAUTHORIZED);
    let (status, _) = call(
        &f.app,
        "GET",
        "/api/v2/search/suggestions?q=a&kind=patterns",
        ANIKA_PAT,
        None,
    )
    .await;
    assert_eq!(status, StatusCode::UNPROCESSABLE_ENTITY);
    let (status, body) = call(
        &f.app,
        "GET",
        "/api/v2/search/suggestions?q=",
        ANIKA_PAT,
        None,
    )
    .await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(
        body["data"],
        serde_json::json!({"tags": [], "categories": [], "owners": []})
    );
}

#[tokio::test]
async fn tags_and_categories_match_prefixes_without_case_or_diacritics() {
    let f = fixture().await;
    let hat = project(&f.pool, f.anika_id, "Hat", "2026-01-01T00:00:00Z").await;
    set_project_tags(
        &f.pool,
        hat,
        &["Mütze", "Wintermütze", "Kinder-Mütze", "socks"],
    )
    .await;
    categorize(&f.pool, f.anika_id, hat, "Mützen").await;
    let shawl = project(&f.pool, f.anika_id, "Shawl", "2026-01-02T00:00:00Z").await;
    set_project_tags(&f.pool, shawl, &["Mütze"]).await;
    categorize(&f.pool, f.anika_id, shawl, "Schals").await;

    for typed in ["mü", "mu", "MÜ", "%20mu%20"] {
        let (status, body) = call(
            &f.app,
            "GET",
            &format!(
                "/api/v2/search/suggestions?q={}",
                typed.replace('ü', "%C3%BC").replace('Ü', "%C3%9C")
            ),
            ANIKA_PAT,
            None,
        )
        .await;
        assert_eq!(status, StatusCode::OK, "{typed}");
        // The whole-value prefix first, then the word prefix; "Wintermütze" is no match.
        assert_eq!(tag_names(&body), ["Mütze", "Kinder-Mütze"], "{typed}");
        assert_eq!(category_names(&body), ["Mützen"], "{typed}");
    }
    let (_, body) = call(
        &f.app,
        "GET",
        "/api/v2/search/suggestions?q=sch",
        ANIKA_PAT,
        None,
    )
    .await;
    assert_eq!(category_names(&body), ["Schals"]);
    assert!(tag_names(&body).is_empty());
    assert_eq!(body["data"]["tags"].as_array().unwrap().len(), 0);
}

#[tokio::test]
async fn members_get_no_owner_suggestions_and_only_values_they_can_see() {
    let f = fixture().await;
    let own = project(&f.pool, f.anika_id, "Anika hat", "2026-01-01T00:00:00Z").await;
    set_project_tags(&f.pool, own, &["bobble"]).await;
    let shared = project(&f.pool, f.ben_id, "Ben shared", "2026-01-02T00:00:00Z").await;
    set_project_tags(&f.pool, shared, &["bunny"]).await;
    categorize(&f.pool, f.ben_id, shared, "Bunnies").await;
    let private = project(&f.pool, f.ben_id, "Ben private", "2026-01-03T00:00:00Z").await;
    set_project_tags(&f.pool, private, &["bin-secret"]).await;
    categorize(&f.pool, f.ben_id, private, "Binned").await;
    sqlx::query(
        "INSERT INTO resource_shares (kind, resource_id, user_id, role, created_at, updated_at) \
         VALUES ('project', ?, ?, 'viewer', 'x', 'x')",
    )
    .bind(shared)
    .bind(f.anika_id)
    .execute(&f.pool)
    .await
    .unwrap();

    // "b" matches the name of Ben, who shares a project with Anika: an administrator would get
    // him as an owner suggestion, a member never does (he is visible in the lists, but never
    // suggested).
    for kind in ["projects", "yarns"] {
        let (status, body) = call(
            &f.app,
            "GET",
            &format!("/api/v2/search/suggestions?q=b&kind={kind}"),
            ANIKA_PAT,
            None,
        )
        .await;
        assert_eq!(status, StatusCode::OK);
        assert_eq!(body["data"]["owners"], serde_json::json!([]), "{kind}");
        let text = body.to_string();
        assert!(!text.contains("ben@example.test"), "{text}");
        assert!(!text.contains("bin-secret"), "{text}");
        assert!(!text.contains("Binned"), "{text}");
        if kind == "projects" {
            assert_eq!(tag_names(&body), ["bobble", "bunny"]);
            assert_eq!(category_names(&body), ["Bunnies"]);
        } else {
            assert!(tag_names(&body).is_empty());
            assert!(category_names(&body).is_empty());
        }
    }
}

#[tokio::test]
async fn administrators_get_owners_and_everybodys_tags_and_categories() {
    let f = fixture().await;
    let ben_project = project(&f.pool, f.ben_id, "Ben blanket", "2026-01-03T00:00:00Z").await;
    set_project_tags(&f.pool, ben_project, &["bin-secret"]).await;
    categorize(&f.pool, f.ben_id, ben_project, "Binned").await;
    project(&f.pool, f.anika_id, "Anika hat", "2026-01-01T00:00:00Z").await;
    yarn(&f.pool, f.anika_id, "Anika wool", "2026-01-02T00:00:00Z").await;

    let (status, body) = call(
        &f.app,
        "GET",
        "/api/v2/search/suggestions?q=BE&kind=projects",
        ADMIN_PAT,
        None,
    )
    .await;
    assert_eq!(status, StatusCode::OK);
    let owners = body["data"]["owners"].as_array().unwrap();
    assert_eq!(owners.len(), 1);
    assert_eq!(owners[0]["id"], f.ben_id);
    assert_eq!(owners[0]["name"], "Ben");
    // Id, name and avatar only: no email address in the payload.
    assert!(owners[0].get("email").is_none());
    assert!(!body.to_string().contains("example.test"));

    let (_, body) = call(
        &f.app,
        "GET",
        "/api/v2/search/suggestions?q=bi&kind=projects",
        ADMIN_PAT,
        None,
    )
    .await;
    assert_eq!(tag_names(&body), ["bin-secret"]);
    assert_eq!(category_names(&body), ["Binned"]);

    // Yarn lists have owners only; an owner without yarn does not appear.
    let (_, body) = call(
        &f.app,
        "GET",
        "/api/v2/search/suggestions?q=a&kind=yarns",
        ADMIN_PAT,
        None,
    )
    .await;
    let names: Vec<_> = body["data"]["owners"]
        .as_array()
        .unwrap()
        .iter()
        .map(|owner| owner["name"].as_str().unwrap())
        .collect();
    assert_eq!(names, ["Anika"]);
    assert!(tag_names(&body).is_empty());
}

#[tokio::test]
async fn suggestions_leave_out_the_trash_unless_the_trash_filter_is_on() {
    let f = fixture().await;
    let live = project(&f.pool, f.anika_id, "Live", "2026-01-01T00:00:00Z").await;
    set_project_tags(&f.pool, live, &["wolle", "wintertag"]).await;
    categorize(&f.pool, f.anika_id, live, "Winter").await;
    let binned = project(&f.pool, f.ben_id, "Binned", "2026-01-02T00:00:00Z").await;
    set_project_tags(&f.pool, binned, &["wintertrash"]).await;
    categorize(&f.pool, f.ben_id, binned, "Wintertrash").await;
    // Ben's project is in the trash; the category stays his own live category.
    let (status, _) = call(
        &f.app,
        "DELETE",
        &format!("/api/v2/projects/{binned}"),
        BEN_PAT,
        None,
    )
    .await;
    assert_eq!(status, StatusCode::NO_CONTENT);

    // Administrators see everybody's items: the trashed one and its owner are not suggested.
    let (_, body) = call(
        &f.app,
        "GET",
        "/api/v2/search/suggestions?q=win",
        ADMIN_PAT,
        None,
    )
    .await;
    assert_eq!(tag_names(&body), ["wintertag"], "{body}");
    assert_eq!(category_names(&body), ["Winter"], "{body}");
    let (_, body) = call(
        &f.app,
        "GET",
        "/api/v2/search/suggestions?q=ben",
        ADMIN_PAT,
        None,
    )
    .await;
    assert!(
        body["data"]["owners"].as_array().unwrap().is_empty(),
        "{body}"
    );

    // With the Trash filter on, they come from the trashed set instead.
    let (_, body) = call(
        &f.app,
        "GET",
        "/api/v2/search/suggestions?q=win&trashed=only",
        ADMIN_PAT,
        None,
    )
    .await;
    assert_eq!(tag_names(&body), ["wintertrash"], "{body}");
    assert_eq!(category_names(&body), ["Wintertrash"], "{body}");
    let (_, body) = call(
        &f.app,
        "GET",
        "/api/v2/search/suggestions?q=ben&trashed=only",
        ADMIN_PAT,
        None,
    )
    .await;
    assert_eq!(body["data"]["owners"][0]["name"], "Ben", "{body}");

    // Owners of the trash are their own: Ben sees his trash, Anika none of it.
    let (_, body) = call(
        &f.app,
        "GET",
        "/api/v2/search/suggestions?q=win&trashed=only",
        BEN_PAT,
        None,
    )
    .await;
    assert_eq!(tag_names(&body), ["wintertrash"], "{body}");
    let (_, body) = call(
        &f.app,
        "GET",
        "/api/v2/search/suggestions?q=win&trashed=only",
        ANIKA_PAT,
        None,
    )
    .await;
    assert!(tag_names(&body).is_empty(), "{body}");

    // `owner=` narrows tags the way it narrows lists, and bad values are rejected.
    let (_, body) = call(
        &f.app,
        "GET",
        &format!("/api/v2/search/suggestions?q=win&owner={}", f.anika_id),
        ADMIN_PAT,
        None,
    )
    .await;
    assert_eq!(tag_names(&body), ["wintertag"], "{body}");
    for bad in ["trashed=maybe", "owner=nobody"] {
        let (status, _) = call(
            &f.app,
            "GET",
            &format!("/api/v2/search/suggestions?q=win&{bad}"),
            ADMIN_PAT,
            None,
        )
        .await;
        assert_eq!(status, StatusCode::UNPROCESSABLE_ENTITY, "{bad}");
    }
}
