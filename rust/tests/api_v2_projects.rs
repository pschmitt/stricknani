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
        3
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
