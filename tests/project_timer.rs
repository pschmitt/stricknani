use axum::{
    body::Body,
    http::{Request, StatusCode},
};
use http_body_util::BodyExt;
use sha2::{Digest, Sha256};
use sqlx::SqlitePool;
use stricknani_server::{api_v2, db};
use tower::ServiceExt;

const HOST: &str = "app.example.test";
const ORIGIN: &str = "https://app.example.test";
const PASSWORD: &str = "timer-test-password-long";

async fn fixture() -> (axum::Router, SqlitePool) {
    let pool = db::connect("sqlite::memory:").await.unwrap();
    let state = db::AppState::new(pool.clone()).with_secure_cookies(true);
    (api_v2::router(state, false), pool)
}

async fn json(response: axum::response::Response) -> serde_json::Value {
    let bytes = response.into_body().collect().await.unwrap().to_bytes();
    serde_json::from_slice(&bytes).unwrap()
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

async fn signed_in(app: &axum::Router, email: &str) -> (String, String) {
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
    session: &str,
    csrf: Option<&str>,
    body: Option<serde_json::Value>,
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
        .body(body.map_or_else(Body::empty, |body| Body::from(body.to_string())))
        .unwrap()
}

fn bearer_request(
    method: &str,
    path: &str,
    token: &str,
    body: Option<serde_json::Value>,
) -> Request<Body> {
    let mut request = Request::builder()
        .method(method)
        .uri(path)
        .header("authorization", format!("Bearer {token}"));
    if body.is_some() {
        request = request.header("content-type", "application/json");
    }
    request
        .body(body.map_or_else(Body::empty, |body| Body::from(body.to_string())))
        .unwrap()
}

async fn insert_pat(pool: &SqlitePool, user_id: i64, token: &str) {
    sqlx::query(
        "INSERT INTO api_tokens(user_id, name, token_hash, purpose, created_at, expires_at) \
         VALUES (?, 'project timer test PAT', ?, 'api', unixepoch(), unixepoch() + 86400)",
    )
    .bind(user_id)
    .bind(hex::encode(Sha256::digest(token.as_bytes())))
    .execute(pool)
    .await
    .unwrap();
}

async fn project(pool: &SqlitePool, owner_id: i64) -> i64 {
    sqlx::query_scalar(
        "INSERT INTO projects(owner_id, name, created_at, updated_at) VALUES (?, 'Timer test', 'now', 'now') RETURNING id",
    )
    .bind(owner_id)
    .fetch_one(pool)
    .await
    .unwrap()
}

async fn send(
    app: &axum::Router,
    method: &str,
    path: &str,
    session: &str,
    csrf: Option<&str>,
    body: Option<serde_json::Value>,
) -> (StatusCode, serde_json::Value) {
    let response = app
        .clone()
        .oneshot(request(method, path, session, csrf, body))
        .await
        .unwrap();
    let status = response.status();
    (status, json(response).await)
}

#[tokio::test]
async fn shared_timer_reads_for_viewers_but_only_editors_can_change_it() {
    let (app, pool) = fixture().await;
    let owner_id = db::create_user(&pool, "owner@example.test", PASSWORD.to_owned())
        .await
        .unwrap();
    let viewer_id = db::create_user(&pool, "viewer@example.test", PASSWORD.to_owned())
        .await
        .unwrap();
    let editor_id = db::create_user(&pool, "editor@example.test", PASSWORD.to_owned())
        .await
        .unwrap();
    db::create_user(&pool, "stranger@example.test", PASSWORD.to_owned())
        .await
        .unwrap();
    let project_id = project(&pool, owner_id).await;
    sqlx::query(
        "INSERT INTO resource_shares(kind, resource_id, user_id, role, created_at, updated_at) VALUES ('project', ?, ?, 'viewer', 'now', 'now')",
    )
    .bind(project_id)
    .bind(viewer_id)
    .execute(&pool)
    .await
    .unwrap();
    sqlx::query(
        "INSERT INTO resource_shares(kind, resource_id, user_id, role, created_at, updated_at) VALUES ('project', ?, ?, 'editor', 'now', 'now')",
    )
    .bind(project_id)
    .bind(editor_id)
    .execute(&pool)
    .await
    .unwrap();
    let (owner, owner_csrf) = signed_in(&app, "owner@example.test").await;
    let (viewer, viewer_csrf) = signed_in(&app, "viewer@example.test").await;
    let (editor, editor_csrf) = signed_in(&app, "editor@example.test").await;
    let (stranger, stranger_csrf) = signed_in(&app, "stranger@example.test").await;
    let path = format!("/projects/{project_id}/timer");

    let (status, timer) = send(&app, "GET", &path, &viewer, None, None).await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(timer["data"]["total_seconds"], 0);
    assert!(timer["data"]["started_at"].is_null());
    assert!(
        timer["data"]["server_time"]
            .as_str()
            .unwrap()
            .ends_with('Z')
    );

    let (status, error) = send(
        &app,
        "PUT",
        &path,
        &viewer,
        Some(&viewer_csrf),
        Some(serde_json::json!({"action":"start"})),
    )
    .await;
    assert_eq!(status, StatusCode::FORBIDDEN);
    assert_eq!(error["error"]["code"], "forbidden");

    let (status, _) = send(
        &app,
        "PUT",
        &path,
        &owner,
        None,
        Some(serde_json::json!({"action":"start"})),
    )
    .await;
    assert_eq!(status, StatusCode::FORBIDDEN);

    let (status, edited) = send(
        &app,
        "PUT",
        &path,
        &editor,
        Some(&editor_csrf),
        Some(serde_json::json!({"action":"set_total","total_seconds":42})),
    )
    .await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(edited["data"]["total_seconds"], 42);
    let (_, shared_after_edit) = send(&app, "GET", &path, &viewer, None, None).await;
    assert_eq!(shared_after_edit["data"]["total_seconds"], 42);

    let (status, error) = send(&app, "GET", &path, &stranger, None, None).await;
    assert_eq!(status, StatusCode::NOT_FOUND);
    assert_eq!(error["error"]["code"], "not_found");
    let (status, error) = send(
        &app,
        "PUT",
        &path,
        &stranger,
        Some(&stranger_csrf),
        Some(serde_json::json!({"action":"start"})),
    )
    .await;
    assert_eq!(status, StatusCode::NOT_FOUND);
    assert_eq!(error["error"]["code"], "not_found");

    let anonymous_get = app
        .clone()
        .oneshot(Request::get(&path).body(Body::empty()).unwrap())
        .await
        .unwrap();
    assert_eq!(anonymous_get.status(), StatusCode::UNAUTHORIZED);

    let anonymous_put = app
        .clone()
        .oneshot(request(
            "PUT",
            &path,
            "",
            Some("unused"),
            Some(serde_json::json!({"action":"start"})),
        ))
        .await
        .unwrap();
    assert_eq!(anonymous_put.status(), StatusCode::UNAUTHORIZED);

    let (status, timer) = send(
        &app,
        "PUT",
        &path,
        &owner,
        Some(&owner_csrf),
        Some(serde_json::json!({"action":"start"})),
    )
    .await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(timer["data"]["total_seconds"], 42);
    assert!(!timer["data"]["started_at"].is_null());

    const OWNER_PAT: &str = "project-timer-owner-api-pat";
    insert_pat(&pool, owner_id, OWNER_PAT).await;
    let pat_set = app
        .clone()
        .oneshot(bearer_request(
            "PUT",
            &path,
            OWNER_PAT,
            Some(serde_json::json!({"action":"set_total","total_seconds":123})),
        ))
        .await
        .unwrap();
    assert_eq!(pat_set.status(), StatusCode::OK);
    assert_eq!(json(pat_set).await["data"]["total_seconds"], 123);
    let pat_get = app
        .clone()
        .oneshot(bearer_request("GET", &path, OWNER_PAT, None))
        .await
        .unwrap();
    assert_eq!(pat_get.status(), StatusCode::OK);

    let invalid_bearer_with_cookie = app
        .clone()
        .oneshot(
            Request::get(&path)
                .header("authorization", "Bearer invalid-project-timer-pat")
                .header("cookie", format!("__Host-stricknani_session={owner}"))
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(
        invalid_bearer_with_cookie.status(),
        StatusCode::UNAUTHORIZED
    );
    let invalid_bearer_put_with_cookie = app
        .clone()
        .oneshot(
            Request::put(&path)
                .header("authorization", "Bearer invalid-project-timer-pat")
                .header("cookie", format!("__Host-stricknani_session={owner}"))
                .header("host", HOST)
                .header("origin", ORIGIN)
                .header("sec-fetch-site", "same-origin")
                .header("x-csrf-token", &owner_csrf)
                .header("content-type", "application/json")
                .body(Body::from(
                    serde_json::json!({"action":"start"}).to_string(),
                ))
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(
        invalid_bearer_put_with_cookie.status(),
        StatusCode::UNAUTHORIZED
    );

    sqlx::query("DELETE FROM projects WHERE id = ?")
        .bind(project_id)
        .execute(&pool)
        .await
        .unwrap();
    let timer_count: i64 =
        sqlx::query_scalar("SELECT COUNT(*) FROM project_timers WHERE project_id = ?")
            .bind(project_id)
            .fetch_one(&pool)
            .await
            .unwrap();
    assert_eq!(timer_count, 0);
}

#[tokio::test]
async fn start_stop_retries_persist_and_set_total_rebases_an_active_timer() {
    let (app, pool) = fixture().await;
    let owner_id = db::create_user(&pool, "owner@example.test", PASSWORD.to_owned())
        .await
        .unwrap();
    let project_id = project(&pool, owner_id).await;
    let (owner, csrf) = signed_in(&app, "owner@example.test").await;
    let path = format!("/projects/{project_id}/timer");

    let (_, started) = send(
        &app,
        "PUT",
        &path,
        &owner,
        Some(&csrf),
        Some(serde_json::json!({"action":"start"})),
    )
    .await;
    let original_start = started["data"]["started_at"].clone();
    let (_, retried) = send(
        &app,
        "PUT",
        &path,
        &owner,
        Some(&csrf),
        Some(serde_json::json!({"action":"start"})),
    )
    .await;
    assert_eq!(retried["data"]["started_at"], original_start);

    sqlx::query("UPDATE project_timers SET started_at = 1 WHERE project_id = ?")
        .bind(project_id)
        .execute(&pool)
        .await
        .unwrap();

    let (_, adjusted) = send(
        &app,
        "PUT",
        &path,
        &owner,
        Some(&csrf),
        Some(serde_json::json!({"action":"set_total","total_seconds":86400})),
    )
    .await;
    assert_eq!(adjusted["data"]["total_seconds"], 86400);
    assert_ne!(adjusted["data"]["started_at"], "1970-01-01T00:00:01Z");

    let (_, stopped) = send(
        &app,
        "PUT",
        &path,
        &owner,
        Some(&csrf),
        Some(serde_json::json!({"action":"stop"})),
    )
    .await;
    let total = stopped["data"]["total_seconds"].as_i64().unwrap();
    assert!(total >= 86400);
    assert!(stopped["data"]["started_at"].is_null());
    let (_, retried_stop) = send(
        &app,
        "PUT",
        &path,
        &owner,
        Some(&csrf),
        Some(serde_json::json!({"action":"stop"})),
    )
    .await;
    assert_eq!(retried_stop["data"]["total_seconds"], total);
    let (_, persisted) = send(&app, "GET", &path, &owner, None, None).await;
    assert_eq!(persisted["data"]["total_seconds"], total);
}

#[tokio::test]
async fn concurrent_start_and_stop_retries_apply_each_transition_once() {
    let (app, pool) = fixture().await;
    let owner_id = db::create_user(&pool, "owner@example.test", PASSWORD.to_owned())
        .await
        .unwrap();
    let project_id = project(&pool, owner_id).await;
    let (owner, csrf) = signed_in(&app, "owner@example.test").await;
    let path = format!("/projects/{project_id}/timer");

    sqlx::query(
        "INSERT INTO project_timers(project_id, total_seconds, started_at) VALUES (?, 0, 1)",
    )
    .bind(project_id)
    .execute(&pool)
    .await
    .unwrap();
    let start = serde_json::json!({"action":"start"});
    let (first_start, second_start) = tokio::join!(
        send(&app, "PUT", &path, &owner, Some(&csrf), Some(start.clone())),
        send(&app, "PUT", &path, &owner, Some(&csrf), Some(start)),
    );
    assert_eq!(first_start.0, StatusCode::OK);
    assert_eq!(second_start.0, StatusCode::OK);
    let persisted_start: Option<i64> =
        sqlx::query_scalar("SELECT started_at FROM project_timers WHERE project_id = ?")
            .bind(project_id)
            .fetch_one(&pool)
            .await
            .unwrap();
    assert_eq!(persisted_start, Some(1));

    sqlx::query("UPDATE project_timers SET started_at = unixepoch() - 10 WHERE project_id = ?")
        .bind(project_id)
        .execute(&pool)
        .await
        .unwrap();
    let stop = serde_json::json!({"action":"stop"});
    let (first_stop, second_stop) = tokio::join!(
        send(&app, "PUT", &path, &owner, Some(&csrf), Some(stop.clone())),
        send(&app, "PUT", &path, &owner, Some(&csrf), Some(stop)),
    );
    assert_eq!(first_stop.0, StatusCode::OK);
    assert_eq!(second_stop.0, StatusCode::OK);
    let persisted_total: i64 =
        sqlx::query_scalar("SELECT total_seconds FROM project_timers WHERE project_id = ?")
            .bind(project_id)
            .fetch_one(&pool)
            .await
            .unwrap();
    assert!((10..=12).contains(&persisted_total));
    let (_, final_timer) = send(&app, "GET", &path, &owner, None, None).await;
    assert_eq!(final_timer["data"]["total_seconds"], persisted_total);
    assert!(final_timer["data"]["started_at"].is_null());
}

#[tokio::test]
async fn invalid_totals_and_malformed_actions_are_rejected_without_changing_timer() {
    let (app, pool) = fixture().await;
    let owner_id = db::create_user(&pool, "owner@example.test", PASSWORD.to_owned())
        .await
        .unwrap();
    let project_id = project(&pool, owner_id).await;
    let (owner, csrf) = signed_in(&app, "owner@example.test").await;
    let path = format!("/projects/{project_id}/timer");
    for total_seconds in [-1, 315_576_001] {
        let (status, _) = send(
            &app,
            "PUT",
            &path,
            &owner,
            Some(&csrf),
            Some(serde_json::json!({"action":"set_total","total_seconds":total_seconds})),
        )
        .await;
        assert_eq!(status, StatusCode::UNPROCESSABLE_ENTITY);
    }
    let (status, _) = send(
        &app,
        "PUT",
        &path,
        &owner,
        Some(&csrf),
        Some(serde_json::json!({"action":"start","total_seconds":12})),
    )
    .await;
    assert_eq!(status, StatusCode::UNPROCESSABLE_ENTITY);
    let (_, unchanged) = send(&app, "GET", &path, &owner, None, None).await;
    assert_eq!(unchanged["data"]["total_seconds"], 0);
    assert!(unchanged["data"]["started_at"].is_null());
}

async fn timer_events(
    pool: &SqlitePool,
    project_id: i64,
) -> Vec<(String, i64, Option<i64>, Option<serde_json::Value>)> {
    sqlx::query_as::<_, (String, i64, Option<i64>, Option<String>)>(
        "SELECT action, actor_user_id, impersonated_by_user_id, safe_changes FROM audit_events \
         WHERE entity_type = 'project' AND entity_id = ? AND action LIKE 'timer\\_%' ESCAPE '\\' \
         ORDER BY id",
    )
    .bind(project_id)
    .fetch_all(pool)
    .await
    .unwrap()
    .into_iter()
    .map(|(action, actor, impersonator, changes)| {
        (
            action,
            actor,
            impersonator,
            changes.map(|text| serde_json::from_str(&text).unwrap()),
        )
    })
    .collect()
}

#[tokio::test]
async fn each_timer_action_writes_one_audit_event_and_repeats_write_none() {
    let (app, pool) = fixture().await;
    let owner_id = db::create_user(&pool, "owner@example.test", PASSWORD.to_owned())
        .await
        .unwrap();
    let editor_id = db::create_user(&pool, "editor@example.test", PASSWORD.to_owned())
        .await
        .unwrap();
    let project_id = project(&pool, owner_id).await;
    sqlx::query(
        "INSERT INTO resource_shares(kind, resource_id, user_id, role, created_at, updated_at) VALUES ('project', ?, ?, 'editor', 'now', 'now')",
    )
    .bind(project_id)
    .bind(editor_id)
    .execute(&pool)
    .await
    .unwrap();
    let (owner, owner_csrf) = signed_in(&app, "owner@example.test").await;
    let (editor, editor_csrf) = signed_in(&app, "editor@example.test").await;
    let path = format!("/projects/{project_id}/timer");
    macro_rules! put {
        ($session:expr, $csrf:expr, $body:expr $(,)?) => {
            send(&app, "PUT", &path, $session, Some($csrf), Some($body))
        };
    }

    // Starting records one event; the retry of a running start records none.
    let (_, started) = put!(&owner, &owner_csrf, serde_json::json!({"action":"start"})).await;
    assert_eq!(started["data"]["started_by"], owner_id);
    put!(&owner, &owner_csrf, serde_json::json!({"action":"start"})).await;
    // A second editor "starting" the running timer neither records nor takes it over.
    let (_, taken) = put!(&editor, &editor_csrf, serde_json::json!({"action":"start"})).await;
    assert_eq!(taken["data"]["started_by"], owner_id);

    // Stopping records the run: before and after total.
    sqlx::query("UPDATE project_timers SET started_at = unixepoch() - 1500 WHERE project_id = ?")
        .bind(project_id)
        .execute(&pool)
        .await
        .unwrap();
    let (_, stopped) = put!(&editor, &editor_csrf, serde_json::json!({"action":"stop"})).await;
    assert!(stopped["data"]["started_by"].is_null());
    put!(&editor, &editor_csrf, serde_json::json!({"action":"stop"})).await;

    // Editing the total records old and new; saving the same value records nothing.
    let total = stopped["data"]["total_seconds"].as_i64().unwrap();
    assert!((1500..=1503).contains(&total));
    put!(
        &owner,
        &owner_csrf,
        serde_json::json!({"action":"set_total","total_seconds":11400}),
    )
    .await;
    put!(
        &owner,
        &owner_csrf,
        serde_json::json!({"action":"set_total","total_seconds":11400}),
    )
    .await;

    // Reads and a failed validation record nothing.
    send(&app, "GET", &path, &owner, None, None).await;
    put!(
        &owner,
        &owner_csrf,
        serde_json::json!({"action":"set_total","total_seconds":-5}),
    )
    .await;

    let events = timer_events(&pool, project_id).await;
    let actions: Vec<_> = events.iter().map(|event| event.0.as_str()).collect();
    assert_eq!(
        actions,
        ["timer_started", "timer_stopped", "timer_total_changed"]
    );
    assert_eq!(events[0].1, owner_id);
    assert!(events[0].3.is_none());
    assert_eq!(events[1].1, editor_id);
    assert_eq!(events[0].2, None);
    let stop_change = &events[1].3.as_ref().unwrap()["items"][0];
    assert_eq!(stop_change["field"], "project.timer_seconds");
    assert_eq!(stop_change["before"]["value"], 0);
    assert_eq!(stop_change["after"]["value"], total);
    let edit_change = &events[2].3.as_ref().unwrap()["items"][0];
    assert_eq!(edit_change["before"]["value"], total);
    assert_eq!(edit_change["after"]["value"], 11400);

    // The owner-visible project history lists them.
    let (status, history) = send(
        &app,
        "GET",
        &format!("/projects/{project_id}/history"),
        &owner,
        None,
        None,
    )
    .await;
    assert_eq!(status, StatusCode::OK);
    let listed: Vec<_> = history["data"]
        .as_array()
        .unwrap()
        .iter()
        .map(|event| event["action"].as_str().unwrap())
        .collect();
    assert_eq!(
        listed,
        ["timer_total_changed", "timer_stopped", "timer_started"]
    );
}

#[tokio::test]
async fn timer_exposes_who_started_the_running_run_to_every_reader() {
    let (app, pool) = fixture().await;
    let owner_id = db::create_user(&pool, "owner@example.test", PASSWORD.to_owned())
        .await
        .unwrap();
    let viewer_id = db::create_user(&pool, "viewer@example.test", PASSWORD.to_owned())
        .await
        .unwrap();
    let editor_id = db::create_user(&pool, "editor@example.test", PASSWORD.to_owned())
        .await
        .unwrap();
    let project_id = project(&pool, owner_id).await;
    for (user_id, role) in [(viewer_id, "viewer"), (editor_id, "editor")] {
        sqlx::query(
            "INSERT INTO resource_shares(kind, resource_id, user_id, role, created_at, updated_at) VALUES ('project', ?, ?, ?, 'now', 'now')",
        )
        .bind(project_id)
        .bind(user_id)
        .bind(role)
        .execute(&pool)
        .await
        .unwrap();
    }
    let (viewer, _) = signed_in(&app, "viewer@example.test").await;
    let (editor, editor_csrf) = signed_in(&app, "editor@example.test").await;
    let (owner, owner_csrf) = signed_in(&app, "owner@example.test").await;
    let path = format!("/projects/{project_id}/timer");

    let (_, idle) = send(&app, "GET", &path, &viewer, None, None).await;
    assert!(idle["data"]["started_by"].is_null());

    send(
        &app,
        "PUT",
        &path,
        &editor,
        Some(&editor_csrf),
        Some(serde_json::json!({"action":"start"})),
    )
    .await;
    for session in [&viewer, &owner, &editor] {
        let (_, running) = send(&app, "GET", &path, session, None, None).await;
        assert_eq!(running["data"]["started_by"], editor_id);
    }

    // Editing the total keeps the run, and the starter, as they are.
    let (_, edited) = send(
        &app,
        "PUT",
        &path,
        &owner,
        Some(&owner_csrf),
        Some(serde_json::json!({"action":"set_total","total_seconds":600})),
    )
    .await;
    assert_eq!(edited["data"]["started_by"], editor_id);

    // A new run belongs to whoever starts it.
    send(
        &app,
        "PUT",
        &path,
        &owner,
        Some(&owner_csrf),
        Some(serde_json::json!({"action":"stop"})),
    )
    .await;
    let (_, again) = send(
        &app,
        "PUT",
        &path,
        &owner,
        Some(&owner_csrf),
        Some(serde_json::json!({"action":"start"})),
    )
    .await;
    assert_eq!(again["data"]["started_by"], owner_id);
}
