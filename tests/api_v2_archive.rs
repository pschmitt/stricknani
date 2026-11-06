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

struct Fixture {
    app: axum::Router,
    pool: SqlitePool,
    owner_id: i64,
    project_id: i64,
    foreign_project_id: i64,
    no_link_project_id: i64,
}

async fn fixture(enabled: bool) -> Fixture {
    let pool = db::connect("sqlite::memory:").await.unwrap();
    let owner_id = db::create_user(
        &pool,
        "archive-api-owner@example.test",
        "archive-api-password".to_owned(),
    )
    .await
    .unwrap();
    let foreign_id = db::create_user(
        &pool,
        "archive-api-foreign@example.test",
        "archive-api-password".to_owned(),
    )
    .await
    .unwrap();
    let project_id = create_project(&pool, owner_id, Some("https://example.test/pattern")).await;
    let foreign_project_id =
        create_project(&pool, foreign_id, Some("https://example.test/private")).await;
    let no_link_project_id = create_project(&pool, owner_id, None).await;
    let app = api_v2::router(
        db::AppState::new(pool.clone())
            .with_secure_cookies(true)
            .with_archive_enabled(enabled),
        false,
    );
    Fixture {
        app,
        pool,
        owner_id,
        project_id,
        foreign_project_id,
        no_link_project_id,
    }
}

async fn create_yarn(pool: &SqlitePool, owner_id: i64, link: &str) -> i64 {
    sqlx::query_scalar(
        "INSERT INTO yarns (owner_id,name,link,created_at,updated_at) \
         VALUES (?,'Archive API yarn',?,'2026-01-01T00:00:00Z','2026-01-01T00:00:00Z') \
         RETURNING id",
    )
    .bind(owner_id)
    .bind(link)
    .fetch_one(pool)
    .await
    .unwrap()
}

async fn create_project(pool: &SqlitePool, owner_id: i64, link: Option<&str>) -> i64 {
    sqlx::query_scalar(
        "INSERT INTO projects (owner_id,name,link,created_at,updated_at) \
         VALUES (?,'Archive API project',?,'2026-01-01T00:00:00Z','2026-01-01T00:00:00Z') \
         RETURNING id",
    )
    .bind(owner_id)
    .bind(link)
    .fetch_one(pool)
    .await
    .unwrap()
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

async fn signed_in(app: &axum::Router) -> (String, String) {
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
                    serde_json::json!({
                        "email":"archive-api-owner@example.test",
                        "password":"archive-api-password"
                    })
                    .to_string(),
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

fn archive_request(
    method: &str,
    path: &str,
    session: &str,
    csrf: Option<&str>,
    origin: Option<&str>,
) -> Request<Body> {
    let mut request = Request::builder()
        .method(method)
        .uri(path)
        .header("host", HOST)
        .header("sec-fetch-site", "same-origin")
        .header("cookie", format!("__Host-stricknani_session={session}"));
    if let Some(origin) = origin {
        request = request.header("origin", origin);
    }
    if let Some(csrf) = csrf {
        request = request.header("x-csrf-token", csrf);
    }
    request.body(Body::empty()).unwrap()
}

#[tokio::test]
async fn archive_api_enforces_csrf_owner_scope_and_reports_safe_current_status() {
    let fixture = fixture(true).await;
    let (session, csrf) = signed_in(&fixture.app).await;

    let rejected = fixture
        .app
        .clone()
        .oneshot(archive_request(
            "POST",
            &format!("/projects/{}/archive", fixture.project_id),
            &session,
            None,
            Some(ORIGIN),
        ))
        .await
        .unwrap();
    assert_eq!(rejected.status(), StatusCode::FORBIDDEN);
    assert_eq!(
        sqlx::query_scalar::<_, i64>("SELECT count(*) FROM archive_jobs")
            .fetch_one(&fixture.pool)
            .await
            .unwrap(),
        0
    );

    let cross_origin = fixture
        .app
        .clone()
        .oneshot(archive_request(
            "POST",
            &format!("/projects/{}/archive", fixture.project_id),
            &session,
            Some(&csrf),
            Some("https://attacker.example"),
        ))
        .await
        .unwrap();
    assert_eq!(cross_origin.status(), StatusCode::FORBIDDEN);
    assert_eq!(
        sqlx::query_scalar::<_, i64>("SELECT count(*) FROM archive_jobs")
            .fetch_one(&fixture.pool)
            .await
            .unwrap(),
        0
    );

    let accepted = fixture
        .app
        .clone()
        .oneshot(archive_request(
            "POST",
            &format!("/projects/{}/archive", fixture.project_id),
            &session,
            Some(&csrf),
            Some(ORIGIN),
        ))
        .await
        .unwrap();
    assert_eq!(accepted.status(), StatusCode::ACCEPTED);
    assert_eq!(json(accepted).await["data"]["state"], "pending");

    let repeated = fixture
        .app
        .clone()
        .oneshot(archive_request(
            "POST",
            &format!("/projects/{}/archive", fixture.project_id),
            &session,
            Some(&csrf),
            Some(ORIGIN),
        ))
        .await
        .unwrap();
    assert_eq!(repeated.status(), StatusCode::ACCEPTED);
    assert_eq!(
        sqlx::query_scalar::<_, i64>("SELECT count(*) FROM archive_jobs")
            .fetch_one(&fixture.pool)
            .await
            .unwrap(),
        1
    );

    let status = fixture
        .app
        .clone()
        .oneshot(archive_request(
            "GET",
            &format!("/projects/{}/archive", fixture.project_id),
            &session,
            None,
            None,
        ))
        .await
        .unwrap();
    assert_eq!(status.status(), StatusCode::OK);
    let status = json(status).await;
    assert_eq!(status["data"]["state"], "pending");
    assert!(status["data"]["updated_at"].as_i64().is_some());
    let rendered = status.to_string();
    assert!(!rendered.contains("example.test"));
    assert!(!rendered.contains("last_error"));
    assert!(!rendered.contains("job_id"));

    let no_link = fixture
        .app
        .clone()
        .oneshot(archive_request(
            "GET",
            &format!("/projects/{}/archive", fixture.no_link_project_id),
            &session,
            None,
            None,
        ))
        .await
        .unwrap();
    assert_eq!(
        json(no_link).await["data"]["state"],
        serde_json::Value::Null
    );

    let no_link_enqueue = fixture
        .app
        .clone()
        .oneshot(archive_request(
            "POST",
            &format!("/projects/{}/archive", fixture.no_link_project_id),
            &session,
            Some(&csrf),
            Some(ORIGIN),
        ))
        .await
        .unwrap();
    assert_eq!(no_link_enqueue.status(), StatusCode::NOT_FOUND);

    let foreign = fixture
        .app
        .clone()
        .oneshot(archive_request(
            "GET",
            &format!("/projects/{}/archive", fixture.foreign_project_id),
            &session,
            None,
            None,
        ))
        .await
        .unwrap();
    assert_eq!(foreign.status(), StatusCode::NOT_FOUND);
}

#[tokio::test]
async fn meta_reports_whether_archiving_is_enabled() {
    for enabled in [true, false] {
        let fixture = fixture(enabled).await;
        let response = fixture
            .app
            .clone()
            .oneshot(
                Request::get("/meta")
                    .header("host", HOST)
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::OK);
        assert_eq!(json(response).await["data"]["archive"], enabled);
    }
}

#[tokio::test]
async fn disabled_archive_api_does_not_enqueue() {
    let fixture = fixture(false).await;
    let (session, csrf) = signed_in(&fixture.app).await;
    let response = fixture
        .app
        .clone()
        .oneshot(archive_request(
            "POST",
            &format!("/projects/{}/archive", fixture.project_id),
            &session,
            Some(&csrf),
            Some(ORIGIN),
        ))
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::SERVICE_UNAVAILABLE);
    assert_eq!(json(response).await["error"]["code"], "archive_unavailable");
    let status = fixture
        .app
        .clone()
        .oneshot(archive_request(
            "GET",
            &format!("/projects/{}/archive", fixture.project_id),
            &session,
            None,
            None,
        ))
        .await
        .unwrap();
    assert_eq!(status.status(), StatusCode::SERVICE_UNAVAILABLE);
    assert_eq!(
        sqlx::query_scalar::<_, i64>("SELECT count(*) FROM archive_jobs")
            .fetch_one(&fixture.pool)
            .await
            .unwrap(),
        0
    );
}

#[tokio::test]
async fn yarn_archive_api_uses_owner_scope_and_only_reports_current_link_jobs() {
    let fixture = fixture(true).await;
    let yarn_id = create_yarn(&fixture.pool, fixture.owner_id, "https://example.test/yarn").await;
    let (session, csrf) = signed_in(&fixture.app).await;
    let accepted = fixture
        .app
        .clone()
        .oneshot(archive_request(
            "POST",
            &format!("/yarns/{yarn_id}/archive"),
            &session,
            Some(&csrf),
            Some(ORIGIN),
        ))
        .await
        .unwrap();
    assert_eq!(accepted.status(), StatusCode::ACCEPTED);
    assert_eq!(json(accepted).await["data"]["state"], "pending");

    let status = fixture
        .app
        .clone()
        .oneshot(archive_request(
            "GET",
            &format!("/yarns/{yarn_id}/archive"),
            &session,
            None,
            None,
        ))
        .await
        .unwrap();
    assert_eq!(json(status).await["data"]["state"], "pending");

    sqlx::query("UPDATE yarns SET link = NULL WHERE id = ?")
        .bind(yarn_id)
        .execute(&fixture.pool)
        .await
        .unwrap();
    let changed_link = fixture
        .app
        .clone()
        .oneshot(archive_request(
            "GET",
            &format!("/yarns/{yarn_id}/archive"),
            &session,
            None,
            None,
        ))
        .await
        .unwrap();
    assert_eq!(
        json(changed_link).await["data"]["state"],
        serde_json::Value::Null
    );
}
