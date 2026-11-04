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

fn request(
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

fn mutation_request(
    method: &str,
    path: &str,
    session: &str,
    csrf: Option<&str>,
    origin: Option<&str>,
    body: String,
) -> Request<Body> {
    let mut request = Request::builder()
        .method(method)
        .uri(path)
        .header("host", HOST)
        .header("sec-fetch-site", "same-origin")
        .header("cookie", format!("__Host-stricknani_session={session}"))
        .header("content-type", "application/json");
    if let Some(origin) = origin {
        request = request.header("origin", origin);
    }
    if let Some(csrf) = csrf {
        request = request.header("x-csrf-token", csrf);
    }
    request.body(Body::from(body)).unwrap()
}

#[tokio::test]
async fn yarns_api_supports_paging_cas_favorites_and_project_timestamp_bumps() {
    let (app, pool) = fixture().await;
    let owner_id = db::create_user(
        &pool,
        "yarn-owner@example.test",
        "yarn-owner-password".to_owned(),
    )
    .await
    .unwrap();
    let (session, csrf) = signed_in(&app, "yarn-owner@example.test", "yarn-owner-password").await;

    let created = app
        .clone()
        .oneshot(request(
            "POST",
            "/yarns",
            &session,
            Some(&csrf),
            Some(serde_json::json!({"name":"Blue wool", "description":"**soft**"}).to_string()),
        ))
        .await
        .unwrap();
    assert_eq!(created.status(), StatusCode::CREATED);
    let created = json(created).await["data"].clone();
    let yarn_id = created["id"].as_i64().expect("numeric yarn ID");
    let initial_updated_at = created["updated_at"].as_str().unwrap().to_owned();
    assert!(
        created["description_html"]
            .as_str()
            .unwrap()
            .contains("<strong>soft</strong>")
    );

    let project_id: i64 = sqlx::query_scalar(
        "INSERT INTO projects (owner_id,name,created_at,updated_at) VALUES (?,'socks','2026-01-01T00:00:00Z','2026-01-01T00:00:00Z') RETURNING id",
    )
    .bind(owner_id)
    .fetch_one(&pool)
    .await
    .unwrap();
    sqlx::query("INSERT INTO project_yarns (project_id,yarn_id) VALUES (?,?)")
        .bind(project_id)
        .bind(yarn_id)
        .execute(&pool)
        .await
        .unwrap();

    let stale = app
        .clone()
        .oneshot(request(
            "PUT",
            &format!("/yarns/{yarn_id}"),
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
    assert_eq!(stale["error"]["current"]["id"], yarn_id);
    assert_eq!(stale["error"]["current"]["updated_at"], initial_updated_at);

    let favorited = app
        .clone()
        .oneshot(request(
            "PUT",
            &format!("/yarns/{yarn_id}/favorite"),
            &session,
            Some(&csrf),
            None,
        ))
        .await
        .unwrap();
    assert_eq!(favorited.status(), StatusCode::OK);
    assert!(
        json(favorited).await["data"]["is_favorite"]
            .as_bool()
            .unwrap()
    );
    for _ in 0..2 {
        let repeat = app
            .clone()
            .oneshot(request(
                "PUT",
                &format!("/yarns/{yarn_id}/favorite"),
                &session,
                Some(&csrf),
                None,
            ))
            .await
            .unwrap();
        assert_eq!(repeat.status(), StatusCode::OK);
    }
    let favorite_count: i64 = sqlx::query_scalar(
        "SELECT COUNT(*) FROM user_favorite_yarns WHERE user_id = ? AND yarn_id = ?",
    )
    .bind(owner_id)
    .bind(yarn_id)
    .fetch_one(&pool)
    .await
    .unwrap();
    assert_eq!(favorite_count, 1);

    let update = app
        .clone()
        .oneshot(request(
            "PUT",
            &format!("/yarns/{yarn_id}"),
            &session,
            Some(&csrf),
            Some(
                serde_json::json!({"name":"Blue wool 2", "expected_updated_at":initial_updated_at})
                    .to_string(),
            ),
        ))
        .await
        .unwrap();
    assert_eq!(update.status(), StatusCode::OK);
    let updated = json(update).await["data"].clone();
    assert_eq!(updated["name"], "Blue wool 2");
    assert_eq!(updated["project_ids"], serde_json::json!([project_id]));
    let project_after_rename: String =
        sqlx::query_scalar("SELECT updated_at FROM projects WHERE id = ?")
            .bind(project_id)
            .fetch_one(&pool)
            .await
            .unwrap();
    assert!(project_after_rename.as_str() > "2026-01-01T00:00:00Z");

    let list = app
        .clone()
        .oneshot(request(
            "GET",
            "/yarns?page=1&favorite=true",
            &session,
            None,
            None,
        ))
        .await
        .unwrap();
    assert_eq!(list.status(), StatusCode::OK);
    let list = json(list).await;
    assert_eq!(list["data"][0]["id"], yarn_id);
    assert_eq!(list["meta"]["per_page"], 50);

    let unfavorite = app
        .clone()
        .oneshot(request(
            "DELETE",
            &format!("/yarns/{yarn_id}/favorite"),
            &session,
            Some(&csrf),
            None,
        ))
        .await
        .unwrap();
    assert_eq!(unfavorite.status(), StatusCode::OK);
    assert!(
        !json(unfavorite).await["data"]["is_favorite"]
            .as_bool()
            .unwrap()
    );

    let before_delete = project_after_rename;
    let deleted = app
        .oneshot(request(
            "DELETE",
            &format!("/yarns/{yarn_id}"),
            &session,
            Some(&csrf),
            None,
        ))
        .await
        .unwrap();
    assert_eq!(deleted.status(), StatusCode::NO_CONTENT);
    let project_after_delete: String =
        sqlx::query_scalar("SELECT updated_at FROM projects WHERE id = ?")
            .bind(project_id)
            .fetch_one(&pool)
            .await
            .unwrap();
    assert!(project_after_delete > before_delete);
}

#[tokio::test]
async fn categories_api_keeps_idempotence_conflicts_owner_scope_and_project_bumps() {
    let (app, pool) = fixture().await;
    let owner_id = db::create_user(
        &pool,
        "category-owner@example.test",
        "category-owner-password".to_owned(),
    )
    .await
    .unwrap();
    let foreign_id = db::create_user(
        &pool,
        "category-foreign@example.test",
        "category-foreign-password".to_owned(),
    )
    .await
    .unwrap();
    let (session, csrf) = signed_in(
        &app,
        "category-owner@example.test",
        "category-owner-password",
    )
    .await;

    let create = || {
        request(
            "POST",
            "/categories",
            &session,
            Some(&csrf),
            Some(r#"{"name":"Socks"}"#.to_owned()),
        )
    };
    let first = app.clone().oneshot(create()).await.unwrap();
    let duplicate = app.clone().oneshot(create()).await.unwrap();
    assert_eq!(first.status(), StatusCode::CREATED);
    assert_eq!(duplicate.status(), StatusCode::CREATED);
    let category = json(first).await["data"].clone();
    let duplicate = json(duplicate).await["data"].clone();
    let category_id = category["id"].as_i64().unwrap();
    assert_eq!(duplicate["id"], category_id);
    let created_events: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM audit_events WHERE actor_user_id = ? AND entity_type = 'category' AND entity_id = ? AND action = 'created'")
        .bind(owner_id).bind(category_id).fetch_one(&pool).await.unwrap();
    assert_eq!(created_events, 1);

    let other_category = app
        .clone()
        .oneshot(request(
            "POST",
            "/categories",
            &session,
            Some(&csrf),
            Some(r#"{"name":"Hats"}"#.to_owned()),
        ))
        .await
        .unwrap();
    assert_eq!(other_category.status(), StatusCode::CREATED);
    let project_id: i64 = sqlx::query_scalar("INSERT INTO projects (owner_id,category_id,name,created_at,updated_at) VALUES (?,?, 'test','2026-01-01T00:00:00Z','2026-01-01T00:00:00Z') RETURNING id")
        .bind(owner_id).bind(category_id).fetch_one(&pool).await.unwrap();

    let renamed = app
        .clone()
        .oneshot(request(
            "PUT",
            &format!("/categories/{category_id}"),
            &session,
            Some(&csrf),
            Some(r#"{"name":"Socks 2"}"#.to_owned()),
        ))
        .await
        .unwrap();
    assert_eq!(renamed.status(), StatusCode::OK);
    assert_eq!(json(renamed).await["data"]["name"], "Socks 2");
    let after_rename: String = sqlx::query_scalar("SELECT updated_at FROM projects WHERE id = ?")
        .bind(project_id)
        .fetch_one(&pool)
        .await
        .unwrap();
    assert!(after_rename.as_str() > "2026-01-01T00:00:00Z");

    let list = app
        .clone()
        .oneshot(request("GET", "/categories", &session, None, None))
        .await
        .unwrap();
    assert_eq!(list.status(), StatusCode::OK);
    assert_eq!(json(list).await["data"].as_array().unwrap().len(), 2);

    let (foreign_session, foreign_csrf) = signed_in(
        &app,
        "category-foreign@example.test",
        "category-foreign-password",
    )
    .await;
    let foreign_update = app
        .clone()
        .oneshot(request(
            "PUT",
            &format!("/categories/{category_id}"),
            &foreign_session,
            Some(&foreign_csrf),
            Some(r#"{"name":"stolen"}"#.to_owned()),
        ))
        .await
        .unwrap();
    assert_eq!(foreign_update.status(), StatusCode::NOT_FOUND);

    let renamed_conflict = app
        .clone()
        .oneshot(request(
            "PUT",
            &format!("/categories/{category_id}"),
            &session,
            Some(&csrf),
            Some(r#"{"name":"Hats"}"#.to_owned()),
        ))
        .await
        .unwrap();
    assert_eq!(renamed_conflict.status(), StatusCode::CONFLICT);
    assert_eq!(
        json(renamed_conflict).await["error"]["fields"]["name"],
        "Name already exists"
    );

    let delete = app
        .oneshot(request(
            "DELETE",
            &format!("/categories/{category_id}"),
            &session,
            Some(&csrf),
            None,
        ))
        .await
        .unwrap();
    assert_eq!(delete.status(), StatusCode::NO_CONTENT);
    let after_delete: String = sqlx::query_scalar("SELECT updated_at FROM projects WHERE id = ?")
        .bind(project_id)
        .fetch_one(&pool)
        .await
        .unwrap();
    assert!(after_delete > after_rename);
    assert_ne!(owner_id, foreign_id);
}

#[tokio::test]
async fn resource_routes_require_cookie_sessions_and_reject_csrf_or_cross_origin_writes() {
    let (app, pool) = fixture().await;
    db::create_user(
        &pool,
        "resource-security@example.test",
        "resource-security-password".to_owned(),
    )
    .await
    .unwrap();
    let (session, csrf) = signed_in(
        &app,
        "resource-security@example.test",
        "resource-security-password",
    )
    .await;

    let anonymous = app
        .clone()
        .oneshot(Request::get("/yarns").body(Body::empty()).unwrap())
        .await
        .unwrap();
    assert_eq!(anonymous.status(), StatusCode::UNAUTHORIZED);
    let bearer_only = Request::get("/categories")
        .header("authorization", "Bearer not-a-v2-session")
        .body(Body::empty())
        .unwrap();
    assert_eq!(
        app.clone().oneshot(bearer_only).await.unwrap().status(),
        StatusCode::UNAUTHORIZED
    );

    for (path, payload, table) in [
        ("/yarns", r#"{"name":"rejected yarn"}"#, "yarns"),
        (
            "/categories",
            r#"{"name":"rejected category"}"#,
            "categories",
        ),
    ] {
        for (token, origin) in [
            (None, Some(ORIGIN)),
            (Some("invalid-csrf"), Some(ORIGIN)),
            (Some(csrf.as_str()), Some("https://evil.example.test")),
        ] {
            let response = app
                .clone()
                .oneshot(mutation_request(
                    "POST",
                    path,
                    &session,
                    token,
                    origin,
                    payload.to_owned(),
                ))
                .await
                .unwrap();
            assert_eq!(response.status(), StatusCode::FORBIDDEN, "{path}");
            assert_eq!(json(response).await["error"]["code"], "csrf_failed");
        }
        let count: i64 =
            sqlx::query_scalar(sqlx::AssertSqlSafe(format!("SELECT COUNT(*) FROM {table}")))
                .fetch_one(&pool)
                .await
                .unwrap();
        assert_eq!(count, 0, "rejected writes must not mutate {table}");
    }
}

#[tokio::test]
async fn yarn_routes_hide_foreign_resources_from_read_and_all_mutations() {
    let (app, pool) = fixture().await;
    db::create_user(
        &pool,
        "yarn-owner-scope@example.test",
        "owner-scope-password".to_owned(),
    )
    .await
    .unwrap();
    db::create_user(
        &pool,
        "yarn-foreign-scope@example.test",
        "foreign-scope-password".to_owned(),
    )
    .await
    .unwrap();
    let (owner_session, owner_csrf) = signed_in(
        &app,
        "yarn-owner-scope@example.test",
        "owner-scope-password",
    )
    .await;
    let (foreign_session, foreign_csrf) = signed_in(
        &app,
        "yarn-foreign-scope@example.test",
        "foreign-scope-password",
    )
    .await;
    let created = app
        .clone()
        .oneshot(request(
            "POST",
            "/yarns",
            &owner_session,
            Some(&owner_csrf),
            Some(r#"{"name":"Private yarn"}"#.to_owned()),
        ))
        .await
        .unwrap();
    assert_eq!(created.status(), StatusCode::CREATED);
    let yarn_id = json(created).await["data"]["id"].as_i64().unwrap();

    let foreign_get = app
        .clone()
        .oneshot(request(
            "GET",
            &format!("/yarns/{yarn_id}"),
            &foreign_session,
            None,
            None,
        ))
        .await
        .unwrap();
    assert_eq!(foreign_get.status(), StatusCode::NOT_FOUND);

    let foreign_mutations = [
        (
            "PUT",
            format!("/yarns/{yarn_id}"),
            Some(r#"{"name":"stolen"}"#.to_owned()),
        ),
        ("DELETE", format!("/yarns/{yarn_id}"), None),
        ("PUT", format!("/yarns/{yarn_id}/favorite"), None),
        ("DELETE", format!("/yarns/{yarn_id}/favorite"), None),
    ];
    for (method, path, body) in foreign_mutations {
        let response = app
            .clone()
            .oneshot(request(
                method,
                &path,
                &foreign_session,
                Some(&foreign_csrf),
                body,
            ))
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::NOT_FOUND, "{method} {path}");
    }
    let owner_get = app
        .oneshot(request(
            "GET",
            &format!("/yarns/{yarn_id}"),
            &owner_session,
            None,
            None,
        ))
        .await
        .unwrap();
    assert_eq!(json(owner_get).await["data"]["name"], "Private yarn");
    let foreign_favorites: i64 =
        sqlx::query_scalar("SELECT COUNT(*) FROM user_favorite_yarns WHERE yarn_id = ?")
            .bind(yarn_id)
            .fetch_one(&pool)
            .await
            .unwrap();
    assert_eq!(foreign_favorites, 0);
}

#[tokio::test]
async fn yarn_validation_accepts_large_resource_bodies_and_rejects_over_limit_payloads() {
    let (app, _pool) = fixture().await;
    db::create_user(
        &_pool,
        "yarn-payload@example.test",
        "yarn-payload-password".to_owned(),
    )
    .await
    .unwrap();
    let (session, csrf) =
        signed_in(&app, "yarn-payload@example.test", "yarn-payload-password").await;

    let invalid = app
        .clone()
        .oneshot(request(
            "POST",
            "/yarns",
            &session,
            Some(&csrf),
            Some(r#"{"name":"  "}"#.to_owned()),
        ))
        .await
        .unwrap();
    assert_eq!(invalid.status(), StatusCode::UNPROCESSABLE_ENTITY);
    let invalid = json(invalid).await;
    assert_eq!(invalid["error"]["code"], "validation_error");
    assert_eq!(invalid["error"]["fields"]["name"], "Invalid value");

    let large_notes = "x".repeat(12 * 1024);
    let create = app
        .clone()
        .oneshot(request(
            "POST",
            "/yarns",
            &session,
            Some(&csrf),
            Some(serde_json::json!({"name":"Large notes", "notes":large_notes}).to_string()),
        ))
        .await
        .unwrap();
    assert_eq!(create.status(), StatusCode::CREATED);
    let yarn = json(create).await["data"].clone();
    let yarn_id = yarn["id"].as_i64().unwrap();
    let updated_at = yarn["updated_at"].as_str().unwrap();
    assert_eq!(yarn["notes"].as_str().unwrap().len(), 12 * 1024);

    let updated_notes = "y".repeat(16 * 1024);
    let update = app
        .clone()
        .oneshot(request(
            "PUT",
            &format!("/yarns/{yarn_id}"),
            &session,
            Some(&csrf),
            Some(
                serde_json::json!({"name":"Large notes", "notes":updated_notes, "expected_updated_at":updated_at})
                    .to_string(),
            ),
        ))
        .await
        .unwrap();
    assert_eq!(update.status(), StatusCode::OK);
    assert_eq!(
        json(update).await["data"]["notes"].as_str().unwrap().len(),
        16 * 1024
    );

    let oversized_payload = serde_json::json!({
        "name":"oversized",
        "notes":"z".repeat(2 * 1024 * 1024 + 1)
    })
    .to_string();
    let oversized_create = app
        .clone()
        .oneshot(request(
            "POST",
            "/yarns",
            &session,
            Some(&csrf),
            Some(oversized_payload.clone()),
        ))
        .await
        .unwrap();
    assert_eq!(oversized_create.status(), StatusCode::PAYLOAD_TOO_LARGE);
    assert_eq!(
        json(oversized_create).await["error"]["code"],
        "payload_too_large"
    );

    let oversized_update = app
        .oneshot(request(
            "PUT",
            &format!("/yarns/{yarn_id}"),
            &session,
            Some(&csrf),
            Some(oversized_payload),
        ))
        .await
        .unwrap();
    assert_eq!(oversized_update.status(), StatusCode::PAYLOAD_TOO_LARGE);
    assert_eq!(
        json(oversized_update).await["error"]["code"],
        "payload_too_large"
    );
}
