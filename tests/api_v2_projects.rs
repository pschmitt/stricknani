use axum::{
    body::Body,
    http::{Request, StatusCode},
};
use http_body_util::BodyExt;
use sqlx::SqlitePool;
use stricknani_server::{api_v2, db};
use tower::ServiceExt;

const HOST: &str = "app.example.test";
const ORIGIN: &str = "https://app.example.test";

async fn fixture() -> (axum::Router, SqlitePool) {
    let pool = db::connect("sqlite::memory:")
        .await
        .expect("in-memory database should migrate");
    let state = db::AppState::new(pool.clone()).with_secure_cookies(true);
    (api_v2::router(state, false), pool)
}

async fn json(response: axum::response::Response) -> serde_json::Value {
    let bytes = response
        .into_body()
        .collect()
        .await
        .expect("response body should be readable")
        .to_bytes();
    serde_json::from_slice(&bytes).expect("response should be JSON")
}

fn cookie(response: &axum::response::Response, prefix: &str) -> String {
    response
        .headers()
        .get_all("set-cookie")
        .iter()
        .filter_map(|value| value.to_str().ok())
        .find_map(|value| value.strip_prefix(prefix))
        .and_then(|value| value.split(';').next())
        .expect("expected cookie should be set")
        .to_owned()
}

async fn signed_in(app: &axum::Router, email: &str, password: &str) -> (String, String) {
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
                    serde_json::json!({"email":email,"password":password}).to_string(),
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

fn browser_request(
    method: &str,
    path: &str,
    session: &str,
    csrf: Option<&str>,
    body: Option<String>,
) -> Request<Body> {
    let mut request = Request::builder()
        .method(method)
        .uri(path)
        .header("cookie", format!("__Host-stricknani_session={session}"));
    if let Some(csrf) = csrf {
        request = request
            .header("host", HOST)
            .header("origin", ORIGIN)
            .header("sec-fetch-site", "same-origin")
            .header("x-csrf-token", csrf);
    }
    if body.is_some() {
        request = request.header("content-type", "application/json");
    }
    request
        .body(body.map_or_else(Body::empty, Body::from))
        .unwrap()
}

#[tokio::test]
async fn projects_crud_conflict_favorites_and_sanitized_html_share_owner_operations() {
    let (app, pool) = fixture().await;
    let owner_id = db::create_user(
        &pool,
        "owner@example.test",
        "owner-password-long".to_owned(),
    )
    .await
    .unwrap();
    let (session, csrf) = signed_in(&app, "owner@example.test", "owner-password-long").await;

    let created = app
        .clone()
        .oneshot(browser_request(
            "POST",
            "/projects",
            &session,
            Some(&csrf),
            Some(
                serde_json::json!({
                    "name": "  Wool socks ",
                    "description": "**safe text**\n\n<script>alert(1)</script>",
                    "steps": [{"title":"Cast on", "description":"*step*"}]
                })
                .to_string(),
            ),
        ))
        .await
        .unwrap();
    assert_eq!(created.status(), StatusCode::CREATED);
    let created = json(created).await["data"].clone();
    let id = created["id"].as_i64().expect("IDs stay numeric");
    let timestamp = created["updated_at"].as_str().unwrap().to_owned();
    assert_eq!(created["name"], "Wool socks");
    assert!(
        created["description_html"]
            .as_str()
            .unwrap()
            .contains("<strong>safe text</strong>")
    );
    assert!(
        !created["description_html"]
            .as_str()
            .unwrap()
            .contains("<script")
    );
    assert!(
        created["steps"][0]["description_html"]
            .as_str()
            .unwrap()
            .contains("<em>step</em>")
    );

    let listed = app
        .clone()
        .oneshot(browser_request(
            "GET",
            "/projects?page=1",
            &session,
            None,
            None,
        ))
        .await
        .unwrap();
    assert_eq!(listed.status(), StatusCode::OK);
    let listed = json(listed).await;
    assert_eq!(listed["data"][0]["id"], id);
    assert_eq!(listed["meta"]["page"], 1);
    assert_eq!(listed["meta"]["per_page"], 50);

    let favorite = app
        .clone()
        .oneshot(browser_request(
            "PUT",
            &format!("/projects/{id}/favorite"),
            &session,
            Some(&csrf),
            None,
        ))
        .await
        .unwrap();
    assert_eq!(favorite.status(), StatusCode::OK);
    assert_eq!(json(favorite).await["data"]["is_favorite"], true);

    let stale = app
        .clone()
        .oneshot(browser_request(
            "PUT",
            &format!("/projects/{id}"),
            &session,
            Some(&csrf),
            Some(
                serde_json::json!({"name":"stale", "expected_updated_at":"2000-01-01T00:00:00Z"})
                    .to_string(),
            ),
        ))
        .await
        .unwrap();
    assert_eq!(stale.status(), StatusCode::CONFLICT);
    let stale = json(stale).await;
    assert_eq!(stale["error"]["code"], "conflict");
    assert_eq!(stale["error"]["current"]["id"], id);
    assert_eq!(stale["error"]["current"]["updated_at"], timestamp);

    let updated = app
        .clone()
        .oneshot(browser_request(
            "PUT",
            &format!("/projects/{id}"),
            &session,
            Some(&csrf),
            Some(serde_json::json!({"name":"fresh", "expected_updated_at":timestamp}).to_string()),
        ))
        .await
        .unwrap();
    assert_eq!(updated.status(), StatusCode::OK);
    assert_eq!(json(updated).await["data"]["name"], "fresh");

    let deleted = app
        .oneshot(browser_request(
            "DELETE",
            &format!("/projects/{id}"),
            &session,
            Some(&csrf),
            None,
        ))
        .await
        .unwrap();
    assert_eq!(deleted.status(), StatusCode::NO_CONTENT);
    assert_eq!(
        sqlx::query_scalar::<_, i64>(
            "SELECT COUNT(*) FROM audit_events WHERE actor_user_id = ? AND entity_id = ?"
        )
        .bind(owner_id)
        .bind(id)
        .fetch_one(&pool)
        .await
        .unwrap(),
        // created, updated, the step the update left out (step_trashed), trashed
        4
    );
}

#[tokio::test]
async fn project_api_hides_foreign_projects_and_requires_csrf_for_mutations() {
    let (app, pool) = fixture().await;
    let owner_id = db::create_user(
        &pool,
        "owner@example.test",
        "owner-password-long".to_owned(),
    )
    .await
    .unwrap();
    let foreign_id = db::create_user(
        &pool,
        "foreign@example.test",
        "foreign-password-long".to_owned(),
    )
    .await
    .unwrap();
    let project_id: i64 = sqlx::query_scalar("INSERT INTO projects (owner_id,name,created_at,updated_at) VALUES (?,'private','2026-01-01T00:00:00Z','2026-01-01T00:00:00Z') RETURNING id")
        .bind(owner_id)
        .fetch_one(&pool)
        .await
        .unwrap();
    assert_ne!(owner_id, foreign_id);
    let (session, csrf) = signed_in(&app, "foreign@example.test", "foreign-password-long").await;

    let hidden = app
        .clone()
        .oneshot(browser_request(
            "GET",
            &format!("/projects/{project_id}"),
            &session,
            None,
            None,
        ))
        .await
        .unwrap();
    assert_eq!(hidden.status(), StatusCode::NOT_FOUND);
    assert_eq!(json(hidden).await["error"]["code"], "not_found");

    let no_csrf = app
        .clone()
        .oneshot(browser_request(
            "DELETE",
            &format!("/projects/{project_id}"),
            &session,
            None,
            None,
        ))
        .await
        .unwrap();
    assert_eq!(no_csrf.status(), StatusCode::FORBIDDEN);

    let cross_origin = app
        .clone()
        .oneshot(
            Request::post("/projects")
                .header("host", HOST)
                .header("origin", "https://attacker.example.test")
                .header("sec-fetch-site", "cross-site")
                .header("cookie", format!("__Host-stricknani_session={session}"))
                .header("x-csrf-token", &csrf)
                .header("content-type", "application/json")
                .body(Body::from(r#"{"name":"cross-origin"}"#))
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(cross_origin.status(), StatusCode::FORBIDDEN);

    let foreign_delete = app
        .oneshot(browser_request(
            "DELETE",
            &format!("/projects/{project_id}"),
            &session,
            Some(&csrf),
            None,
        ))
        .await
        .unwrap();
    assert_eq!(foreign_delete.status(), StatusCode::NOT_FOUND);
    assert_eq!(
        sqlx::query_scalar::<_, i64>("SELECT COUNT(*) FROM projects WHERE id = ?")
            .bind(project_id)
            .fetch_one(&pool)
            .await
            .unwrap(),
        1
    );
}

#[tokio::test]
async fn project_json_allows_large_patterns_but_returns_stable_error_over_two_mib() {
    let (app, pool) = fixture().await;
    db::create_user(
        &pool,
        "large@example.test",
        "large-pattern-password".to_owned(),
    )
    .await
    .unwrap();
    let (session, csrf) = signed_in(&app, "large@example.test", "large-pattern-password").await;
    let long_description = "knit pattern details;".repeat(500);
    let create_body = serde_json::json!({
        "name": "Large pattern",
        "description": long_description,
    })
    .to_string();
    assert!(create_body.len() > 8 * 1024);
    let created = app
        .clone()
        .oneshot(browser_request(
            "POST",
            "/projects",
            &session,
            Some(&csrf),
            Some(create_body),
        ))
        .await
        .unwrap();
    assert_eq!(created.status(), StatusCode::CREATED);
    let created = json(created).await["data"].clone();
    assert_eq!(
        created["description"].as_str().unwrap().len(),
        long_description.len()
    );
    let project_id = created["id"].as_i64().unwrap();

    let update_body = serde_json::json!({
        "name": "Large pattern updated",
        "description": long_description,
        "expected_updated_at": created["updated_at"],
    })
    .to_string();
    assert!(update_body.len() > 8 * 1024);
    let updated = app
        .clone()
        .oneshot(browser_request(
            "PUT",
            &format!("/projects/{project_id}"),
            &session,
            Some(&csrf),
            Some(update_body),
        ))
        .await
        .unwrap();
    assert_eq!(updated.status(), StatusCode::OK);
    assert_eq!(json(updated).await["data"]["name"], "Large pattern updated");

    let oversized = format!(
        "{{\"name\":\"Too large\",\"description\":\"{}\"}}",
        "x".repeat(2 * 1024 * 1024)
    );
    let response = app
        .oneshot(browser_request(
            "POST",
            "/projects",
            &session,
            Some(&csrf),
            Some(oversized),
        ))
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::PAYLOAD_TOO_LARGE);
    let error = json(response).await;
    assert_eq!(error["error"]["code"], "payload_too_large");
    assert_eq!(error["error"]["message"], "Request body is too large");
}

#[tokio::test]
async fn v2_project_tags_preserve_each_json_label_across_unrelated_updates() {
    let (app, pool) = fixture().await;
    db::create_user(
        &pool,
        "tag-labels@example.test",
        "tag-label-password-long".to_owned(),
    )
    .await
    .unwrap();
    let (session, csrf) =
        signed_in(&app, "tag-labels@example.test", "tag-label-password-long").await;
    let tags = serde_json::json!(["color, texture", "two words", "#literal"]);

    let created = app
        .clone()
        .oneshot(browser_request(
            "POST",
            "/projects",
            &session,
            Some(&csrf),
            Some(serde_json::json!({ "name": "Tagged shawl", "tags": tags.clone() }).to_string()),
        ))
        .await
        .unwrap();
    assert_eq!(created.status(), StatusCode::CREATED);
    let created = json(created).await["data"].clone();
    let project_id = created["id"].as_i64().unwrap();
    assert_eq!(created["tags"], tags);

    let updated = app
        .clone()
        .oneshot(browser_request(
            "PUT",
            &format!("/projects/{project_id}"),
            &session,
            Some(&csrf),
            Some(
                serde_json::json!({
                    "name": "Tagged shawl renamed",
                    "tags": tags.clone(),
                    "expected_updated_at": created["updated_at"].clone()
                })
                .to_string(),
            ),
        ))
        .await
        .unwrap();
    assert_eq!(updated.status(), StatusCode::OK);
    let updated = json(updated).await["data"].clone();
    assert_eq!(updated["name"], "Tagged shawl renamed");
    assert_eq!(updated["tags"], tags);

    let stored_tags: String = sqlx::query_scalar("SELECT tags FROM projects WHERE id = ?")
        .bind(project_id)
        .fetch_one(&pool)
        .await
        .unwrap();
    assert_eq!(
        serde_json::from_str::<Vec<String>>(&stored_tags).unwrap(),
        tags.as_array()
            .unwrap()
            .iter()
            .map(|tag| tag.as_str().unwrap().to_owned())
            .collect::<Vec<_>>()
    );
}

async fn create_project_json(
    app: &axum::Router,
    session: &str,
    csrf: &str,
    body: serde_json::Value,
) -> (StatusCode, serde_json::Value) {
    let response = app
        .clone()
        .oneshot(browser_request(
            "POST",
            "/projects",
            session,
            Some(csrf),
            Some(body.to_string()),
        ))
        .await
        .unwrap();
    let status = response.status();
    (status, json(response).await)
}

#[tokio::test]
async fn project_tag_suggestions_are_scoped_counted_and_ordered() {
    let (app, pool) = fixture().await;
    let anonymous = app
        .clone()
        .oneshot(Request::get("/projects/tags").body(Body::empty()).unwrap())
        .await
        .unwrap();
    assert_eq!(anonymous.status(), StatusCode::UNAUTHORIZED);

    let ana = db::create_user(
        &pool,
        "ana-tags@example.test",
        "ana-tags-password-long".to_owned(),
    )
    .await
    .unwrap();
    db::create_user(
        &pool,
        "bo-tags@example.test",
        "bo-tags-password-long".to_owned(),
    )
    .await
    .unwrap();
    let (ana_session, ana_csrf) =
        signed_in(&app, "ana-tags@example.test", "ana-tags-password-long").await;
    let (bo_session, bo_csrf) =
        signed_in(&app, "bo-tags@example.test", "bo-tags-password-long").await;
    for (name, tags) in [
        ("One", serde_json::json!(["winter", "socks", "Gift"])),
        ("Two", serde_json::json!(["winter", "gift", "gift"])),
        (
            "Three",
            serde_json::json!(["winter", "baby blanket", "gift"]),
        ),
        ("Four", serde_json::json!(["socks"])),
    ] {
        let (status, _) = create_project_json(
            &app,
            &ana_session,
            &ana_csrf,
            serde_json::json!({ "name": name, "tags": tags }),
        )
        .await;
        assert_eq!(status, StatusCode::CREATED);
    }
    let (status, shared) = create_project_json(
        &app,
        &bo_session,
        &bo_csrf,
        serde_json::json!({ "name": "Bo shared", "tags": ["lace", "winter"] }),
    )
    .await;
    assert_eq!(status, StatusCode::CREATED);
    let (status, _) = create_project_json(
        &app,
        &bo_session,
        &bo_csrf,
        serde_json::json!({ "name": "Bo private", "tags": ["secret"] }),
    )
    .await;
    assert_eq!(status, StatusCode::CREATED);

    async fn fetch(app: &axum::Router, session: &str) -> serde_json::Value {
        let response = app
            .clone()
            .oneshot(browser_request(
                "GET",
                "/projects/tags",
                session,
                None,
                None,
            ))
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::OK);
        json(response).await["data"].clone()
    }
    // Own projects only; most used first, then by name. Tags that differ in case are one entry
    // in their most common spelling ("gift" in two projects, "Gift" in one) and count once per
    // project.
    assert_eq!(
        fetch(&app, &ana_session).await,
        serde_json::json!([
            {"tag": "gift", "count": 3},
            {"tag": "winter", "count": 3},
            {"tag": "socks", "count": 2},
            {"tag": "baby blanket", "count": 1},
        ])
    );
    assert_eq!(
        fetch(&app, &bo_session).await,
        serde_json::json!([
            {"tag": "lace", "count": 1},
            {"tag": "secret", "count": 1},
            {"tag": "winter", "count": 1},
        ])
    );

    // A project shared with Ana contributes its tags too (the "Mine" library semantics).
    sqlx::query(
        "INSERT INTO resource_shares (kind, resource_id, user_id, role, created_at, updated_at) \
         VALUES ('project', ?, ?, 'viewer', 'x', 'x')",
    )
    .bind(shared["data"]["id"].as_i64().unwrap())
    .bind(ana)
    .execute(&pool)
    .await
    .unwrap();
    assert_eq!(
        fetch(&app, &ana_session).await,
        serde_json::json!([
            {"tag": "winter", "count": 4},
            {"tag": "gift", "count": 3},
            {"tag": "socks", "count": 2},
            {"tag": "baby blanket", "count": 1},
            {"tag": "lace", "count": 1},
        ])
    );
}

async fn yarn_count(pool: &SqlitePool, owner_id: i64) -> i64 {
    sqlx::query_scalar("SELECT COUNT(*) FROM yarns WHERE owner_id = ?")
        .bind(owner_id)
        .fetch_one(pool)
        .await
        .unwrap()
}

#[tokio::test]
async fn project_create_links_a_stash_yarn_creates_a_new_one_or_links_none() {
    let (app, pool) = fixture().await;
    let owner_id = db::create_user(&pool, "yarn@example.test", "owner-password-long".to_owned())
        .await
        .unwrap();
    let other_id = db::create_user(
        &pool,
        "other@example.test",
        "other-password-long".to_owned(),
    )
    .await
    .unwrap();
    let (session, csrf) = signed_in(&app, "yarn@example.test", "owner-password-long").await;
    let stash_id: i64 = sqlx::query_scalar(
        "INSERT INTO yarns (owner_id, name, brand, created_at, updated_at) \
         VALUES (?, 'Merino Extra Fine', 'DROPS', '2026-01-01T00:00:00Z', '2026-01-01T00:00:00Z') \
         RETURNING id",
    )
    .bind(owner_id)
    .fetch_one(&pool)
    .await
    .unwrap();
    let foreign_id: i64 = sqlx::query_scalar(
        "INSERT INTO yarns (owner_id, name, created_at, updated_at) \
         VALUES (?, 'Foreign', '2026-01-01T00:00:00Z', '2026-01-01T00:00:00Z') RETURNING id",
    )
    .bind(other_id)
    .fetch_one(&pool)
    .await
    .unwrap();

    // 1. A stash yarn is linked, nothing new is created.
    let (status, body) = create_project_json(
        &app,
        &session,
        &csrf,
        serde_json::json!({"name": "Stash cardigan", "yarn_ids": [stash_id]}),
    )
    .await;
    assert_eq!(status, StatusCode::CREATED);
    assert_eq!(body["data"]["yarn_ids"], serde_json::json!([stash_id]));
    assert_eq!(yarn_count(&pool, owner_id).await, 1);

    // 2. A new yarn is created and linked in the same request, next to a stash yarn.
    let (status, body) = create_project_json(
        &app,
        &session,
        &csrf,
        serde_json::json!({
            "name": "New cardigan",
            "yarn_ids": [stash_id],
            "new_yarns": [{
                "name": "DROPS Air", "brand": "DROPS", "weight_grams": 50,
                "length_meters": 150, "notes": "Garngruppe C"
            }]
        }),
    )
    .await;
    assert_eq!(status, StatusCode::CREATED);
    let ids = body["data"]["yarn_ids"].as_array().unwrap().clone();
    assert_eq!(ids.len(), 2);
    assert_eq!(yarn_count(&pool, owner_id).await, 2);
    let (name, grams): (String, Option<i64>) =
        sqlx::query_as("SELECT name, weight_grams FROM yarns WHERE owner_id = ? AND id != ?")
            .bind(owner_id)
            .bind(stash_id)
            .fetch_one(&pool)
            .await
            .unwrap();
    assert_eq!((name.as_str(), grams), ("DROPS Air", Some(50)));

    // 3. Choosing no yarn at all links nothing and creates nothing.
    let (status, body) = create_project_json(
        &app,
        &session,
        &csrf,
        serde_json::json!({"name": "No yarn", "yarn_ids": [], "new_yarns": []}),
    )
    .await;
    assert_eq!(status, StatusCode::CREATED);
    assert_eq!(body["data"]["yarn_ids"], serde_json::json!([]));
    assert_eq!(yarn_count(&pool, owner_id).await, 2);

    // 4. Someone else's yarn is never linked; only the new yarn of that request is.
    let (_, body) = create_project_json(
        &app,
        &session,
        &csrf,
        serde_json::json!({
            "name": "Foreign link", "yarn_ids": [foreign_id],
            "new_yarns": [{"name": "Orphan check"}]
        }),
    )
    .await;
    assert_eq!(body["data"]["yarn_ids"].as_array().map(Vec::len), Some(1));
    assert_ne!(body["data"]["yarn_ids"][0], serde_json::json!(foreign_id));

    // 5. A nameless new yarn is rejected before anything is created.
    let before = yarn_count(&pool, owner_id).await;
    let (status, _) = create_project_json(
        &app,
        &session,
        &csrf,
        serde_json::json!({"name": "Bad", "new_yarns": [{"name": "  "}]}),
    )
    .await;
    assert!(status.is_client_error());
    assert_eq!(yarn_count(&pool, owner_id).await, before);
}

#[tokio::test]
async fn project_status_defaults_writes_filters_and_toggles() {
    let (app, pool) = fixture().await;
    db::create_user(
        &pool,
        "status@example.test",
        "status-password-long".to_owned(),
    )
    .await
    .unwrap();
    db::create_user(
        &pool,
        "other@example.test",
        "other-password-long".to_owned(),
    )
    .await
    .unwrap();
    let (session, csrf) = signed_in(&app, "status@example.test", "status-password-long").await;
    let (other_session, other_csrf) =
        signed_in(&app, "other@example.test", "other-password-long").await;

    // Default and explicit status on create.
    let (code, first) =
        create_project_json(&app, &session, &csrf, serde_json::json!({"name": "Socks"})).await;
    assert_eq!(code, StatusCode::CREATED);
    assert_eq!(first["data"]["status"], "in_progress");
    let first_id = first["data"]["id"].as_i64().unwrap();
    let (_, second) = create_project_json(
        &app,
        &session,
        &csrf,
        serde_json::json!({"name": "Hat", "status": "done"}),
    )
    .await;
    assert_eq!(second["data"]["status"], "done");
    let second_id = second["data"]["id"].as_i64().unwrap();

    // Invalid values are rejected on create, update, toggle and filter.
    let (code, rejected) = create_project_json(
        &app,
        &session,
        &csrf,
        serde_json::json!({"name": "Bad", "status": "archived"}),
    )
    .await;
    assert_eq!(code, StatusCode::UNPROCESSABLE_ENTITY);
    assert_eq!(rejected["error"]["fields"]["status"], "Invalid value");
    let bad_toggle = app
        .clone()
        .oneshot(browser_request(
            "PUT",
            &format!("/projects/{first_id}/status"),
            &session,
            Some(&csrf),
            Some(serde_json::json!({"status": "paused"}).to_string()),
        ))
        .await
        .unwrap();
    assert_eq!(bad_toggle.status(), StatusCode::UNPROCESSABLE_ENTITY);
    let bad_filter = app
        .clone()
        .oneshot(browser_request(
            "GET",
            "/projects?status=paused",
            &session,
            None,
            None,
        ))
        .await
        .unwrap();
    assert_eq!(bad_filter.status(), StatusCode::UNPROCESSABLE_ENTITY);

    // Filtering, in the list payload too.
    for (query, expected) in [
        ("", vec!["Hat", "Socks"]),
        ("?status=in_progress", vec!["Socks"]),
        ("?status=done", vec!["Hat"]),
    ] {
        let response = app
            .clone()
            .oneshot(browser_request(
                "GET",
                &format!("/projects{query}"),
                &session,
                None,
                None,
            ))
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::OK);
        let body = json(response).await;
        let mut names: Vec<_> = body["data"]
            .as_array()
            .unwrap()
            .iter()
            .map(|item| item["name"].as_str().unwrap().to_owned())
            .collect();
        names.sort();
        assert_eq!(names, expected, "filter {query}");
        for item in body["data"].as_array().unwrap() {
            let want = if item["name"] == "Hat" {
                "done"
            } else {
                "in_progress"
            };
            assert_eq!(item["status"], want);
        }
    }

    // A full update without a status keeps it; with one it changes it.
    let current = json(
        app.clone()
            .oneshot(browser_request(
                "GET",
                &format!("/projects/{second_id}"),
                &session,
                None,
                None,
            ))
            .await
            .unwrap(),
    )
    .await;
    let updated = app
        .clone()
        .oneshot(browser_request(
            "PUT",
            &format!("/projects/{second_id}"),
            &session,
            Some(&csrf),
            Some(
                serde_json::json!({
                    "name": "Hat",
                    "expected_updated_at": current["data"]["updated_at"],
                })
                .to_string(),
            ),
        ))
        .await
        .unwrap();
    assert_eq!(updated.status(), StatusCode::OK);
    let updated = json(updated).await;
    assert_eq!(updated["data"]["status"], "done");
    let reopened = app
        .clone()
        .oneshot(browser_request(
            "PUT",
            &format!("/projects/{second_id}"),
            &session,
            Some(&csrf),
            Some(
                serde_json::json!({
                    "name": "Hat",
                    "status": "in_progress",
                    "expected_updated_at": updated["data"]["updated_at"],
                })
                .to_string(),
            ),
        ))
        .await
        .unwrap();
    assert_eq!(json(reopened).await["data"]["status"], "in_progress");

    // The quick toggle changes the status, bumps updated_at only on a change,
    // and a repeated request is a no-op.
    let before = json(
        app.clone()
            .oneshot(browser_request(
                "GET",
                &format!("/projects/{first_id}"),
                &session,
                None,
                None,
            ))
            .await
            .unwrap(),
    )
    .await["data"]["updated_at"]
        .clone();
    let mut stamps = Vec::new();
    for _ in 0..2 {
        let response = app
            .clone()
            .oneshot(browser_request(
                "PUT",
                &format!("/projects/{first_id}/status"),
                &session,
                Some(&csrf),
                Some(serde_json::json!({"status": "done"}).to_string()),
            ))
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::OK);
        let body = json(response).await;
        assert_eq!(body["data"]["status"], "done");
        stamps.push(body["data"]["updated_at"].clone());
    }
    assert_ne!(stamps[0], before);
    assert_eq!(stamps[0], stamps[1]);

    // Needs CSRF and write access.
    let no_csrf = app
        .clone()
        .oneshot(browser_request(
            "PUT",
            &format!("/projects/{first_id}/status"),
            &session,
            None,
            Some(serde_json::json!({"status": "in_progress"}).to_string()),
        ))
        .await
        .unwrap();
    assert!(no_csrf.status().is_client_error());
    let foreign = app
        .clone()
        .oneshot(browser_request(
            "PUT",
            &format!("/projects/{first_id}/status"),
            &other_session,
            Some(&other_csrf),
            Some(serde_json::json!({"status": "in_progress"}).to_string()),
        ))
        .await
        .unwrap();
    assert_eq!(foreign.status(), StatusCode::NOT_FOUND);
}
