//! The per-user order of the detail-page sections, shared by the web app and Android.

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
const PASSWORD: &str = "detail-order-password-long";
const PAT: &str = "detail-order-pat-secret";
const OTHER_PAT: &str = "detail-order-other-pat-secret";

const PROJECT_DEFAULT: [&str; 4] = ["details", "materials", "description", "steps"];
const YARN_DEFAULT: [&str; 4] = ["details", "description", "notes", "used_in"];

struct Fixture {
    app: axum::Router,
    pool: SqlitePool,
    user_id: i64,
}

async fn fixture() -> Fixture {
    let pool = db::connect("sqlite::memory:").await.unwrap();
    let user_id = db::create_user(&pool, "owner@example.test", PASSWORD.to_owned())
        .await
        .unwrap();
    let other_id = db::create_user(&pool, "other@example.test", PASSWORD.to_owned())
        .await
        .unwrap();
    for (id, secret) in [(user_id, PAT), (other_id, OTHER_PAT)] {
        sqlx::query(
            "INSERT INTO api_tokens (user_id, name, token_hash, purpose, created_at) \
             VALUES (?, 'fixture', ?, 'api', unixepoch())",
        )
        .bind(id)
        .bind(hex::encode(Sha256::digest(secret.as_bytes())))
        .execute(&pool)
        .await
        .unwrap();
    }
    let state = db::AppState::new(pool.clone()).with_secure_cookies(true);
    Fixture {
        app: api_v2::router(state, false),
        pool,
        user_id,
    }
}

async fn json(response: axum::response::Response) -> serde_json::Value {
    let bytes = response.into_body().collect().await.unwrap().to_bytes();
    serde_json::from_slice(&bytes).unwrap_or(serde_json::Value::Null)
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
                    serde_json::json!({"email":"owner@example.test","password":PASSWORD})
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

fn pat_request(
    method: &str,
    path: &str,
    token: &str,
    body: Option<serde_json::Value>,
) -> Request<Body> {
    let mut builder = Request::builder()
        .method(method)
        .uri(path)
        .header("authorization", format!("Bearer {token}"));
    if body.is_some() {
        builder = builder.header("content-type", "application/json");
    }
    builder
        .body(body.map_or_else(Body::empty, |body| Body::from(body.to_string())))
        .unwrap()
}

fn cookie_request(
    method: &str,
    path: &str,
    session: &str,
    csrf: Option<&str>,
    body: Option<serde_json::Value>,
) -> Request<Body> {
    let mut builder = Request::builder()
        .method(method)
        .uri(path)
        .header("cookie", format!("__Host-stricknani_session={session}"));
    if let Some(csrf) = csrf {
        builder = builder
            .header("host", HOST)
            .header("origin", ORIGIN)
            .header("sec-fetch-site", "same-origin")
            .header("x-csrf-token", csrf);
    }
    if body.is_some() {
        builder = builder.header("content-type", "application/json");
    }
    builder
        .body(body.map_or_else(Body::empty, |body| Body::from(body.to_string())))
        .unwrap()
}

async fn send(f: &Fixture, request: Request<Body>) -> axum::response::Response {
    f.app.clone().oneshot(request).await.unwrap()
}

fn strings(value: &serde_json::Value) -> Vec<String> {
    value
        .as_array()
        .unwrap()
        .iter()
        .map(|item| item.as_str().unwrap().to_owned())
        .collect()
}

#[tokio::test]
async fn defaults_are_reported_for_account_and_session() {
    let f = fixture().await;
    let account = json(send(&f, pat_request("GET", "/account", PAT, None)).await).await;
    let order = &account["data"]["detail_card_order"];
    assert_eq!(strings(&order["project"]), PROJECT_DEFAULT);
    assert_eq!(strings(&order["yarn"]), YARN_DEFAULT);

    let (session, _csrf) = signed_in(&f.app).await;
    let session =
        json(send(&f, cookie_request("GET", "/session", &session, None, None)).await).await;
    let order = &session["data"]["user"]["detail_card_order"];
    assert_eq!(strings(&order["project"]), PROJECT_DEFAULT);
    assert_eq!(strings(&order["yarn"]), YARN_DEFAULT);
}

#[tokio::test]
async fn pat_round_trip_fills_in_missing_sections_and_resets() {
    let f = fixture().await;
    let response = send(
        &f,
        pat_request(
            "PATCH",
            "/account",
            PAT,
            Some(serde_json::json!({
                "detail_card_order": {"project": ["steps", "details"], "yarn": ["notes"]}
            })),
        ),
    )
    .await;
    assert_eq!(response.status(), StatusCode::OK);
    let body = json(response).await;
    let order = &body["data"]["detail_card_order"];
    assert_eq!(
        strings(&order["project"]),
        ["steps", "details", "materials", "description"]
    );
    assert_eq!(
        strings(&order["yarn"]),
        ["notes", "details", "description", "used_in"]
    );
    let stored: Option<String> =
        sqlx::query_scalar("SELECT detail_order_project FROM users WHERE id = ?")
            .bind(f.user_id)
            .fetch_one(&f.pool)
            .await
            .unwrap();
    assert_eq!(
        stored.as_deref(),
        Some("steps,details,materials,description")
    );

    // Another account is unaffected.
    let other = json(send(&f, pat_request("GET", "/account", OTHER_PAT, None)).await).await;
    assert_eq!(
        strings(&other["data"]["detail_card_order"]["project"]),
        PROJECT_DEFAULT
    );

    // A page left out of the request keeps its order; an empty list resets the other one.
    let reset = json(
        send(
            &f,
            pat_request(
                "PATCH",
                "/account",
                PAT,
                Some(serde_json::json!({"detail_card_order": {"project": []}})),
            ),
        )
        .await,
    )
    .await;
    let order = &reset["data"]["detail_card_order"];
    assert_eq!(strings(&order["project"]), PROJECT_DEFAULT);
    assert_eq!(
        strings(&order["yarn"]),
        ["notes", "details", "description", "used_in"]
    );
    let stored: Option<String> =
        sqlx::query_scalar("SELECT detail_order_project FROM users WHERE id = ?")
            .bind(f.user_id)
            .fetch_one(&f.pool)
            .await
            .unwrap();
    assert_eq!(stored, None);
}

#[tokio::test]
async fn unknown_repeated_and_malformed_orders_are_rejected_without_changes() {
    let f = fixture().await;
    for body in [
        serde_json::json!({"detail_card_order": {"project": ["details", "bogus"]}}),
        // A yarn section id is not a project section.
        serde_json::json!({"detail_card_order": {"project": ["notes"]}}),
        serde_json::json!({"detail_card_order": {"yarn": ["details", "details"]}}),
        // The valid page must not be stored when the other one is rejected.
        serde_json::json!({"detail_card_order": {"project": ["steps"], "yarn": ["nope"]}}),
    ] {
        let response = send(&f, pat_request("PATCH", "/account", PAT, Some(body))).await;
        assert_eq!(response.status(), StatusCode::UNPROCESSABLE_ENTITY);
        assert_eq!(json(response).await["error"]["code"], "validation_error");
    }
    for body in [
        serde_json::json!({"detail_card_order": {"project": "details"}}),
        serde_json::json!({"detail_card_order": {"project": [1]}}),
        serde_json::json!({"detail_card_order": {"extra": []}}),
        serde_json::json!({"detail_card_order": null, "unknown": 1}),
    ] {
        let response = send(&f, pat_request("PATCH", "/account", PAT, Some(body))).await;
        assert_eq!(response.status(), StatusCode::BAD_REQUEST);
    }
    let stored: (Option<String>, Option<String>) =
        sqlx::query_as("SELECT detail_order_project, detail_order_yarn FROM users WHERE id = ?")
            .bind(f.user_id)
            .fetch_one(&f.pool)
            .await
            .unwrap();
    assert_eq!(stored, (None, None));
}

#[tokio::test]
async fn requires_authentication_and_csrf_for_browser_sessions() {
    let f = fixture().await;
    let body = serde_json::json!({"detail_card_order": {"yarn": ["used_in"]}});
    let anonymous = send(
        &f,
        Request::patch("/account")
            .header("content-type", "application/json")
            .body(Body::from(body.to_string()))
            .unwrap(),
    )
    .await;
    // Without a session the cross-origin guard answers first.
    assert!(
        matches!(
            anonymous.status(),
            StatusCode::UNAUTHORIZED | StatusCode::FORBIDDEN
        ),
        "unexpected status {}",
        anonymous.status()
    );
    let bad_token = send(
        &f,
        pat_request("PATCH", "/account", "wrong-token", Some(body.clone())),
    )
    .await;
    assert_eq!(bad_token.status(), StatusCode::UNAUTHORIZED);

    let (session, csrf) = signed_in(&f.app).await;
    let without_csrf = send(
        &f,
        cookie_request("PATCH", "/account", &session, None, Some(body.clone())),
    )
    .await;
    assert!(
        without_csrf.status() == StatusCode::FORBIDDEN
            || without_csrf.status() == StatusCode::BAD_REQUEST,
        "unexpected status {}",
        without_csrf.status()
    );
    let wrong_csrf = send(
        &f,
        cookie_request(
            "PATCH",
            "/account",
            &session,
            Some("wrong"),
            Some(body.clone()),
        ),
    )
    .await;
    assert_ne!(wrong_csrf.status(), StatusCode::OK);

    let response = send(
        &f,
        cookie_request("PATCH", "/account", &session, Some(&csrf), Some(body)),
    )
    .await;
    assert_eq!(response.status(), StatusCode::OK);
    let body = json(response).await;
    assert_eq!(
        strings(&body["data"]["detail_card_order"]["yarn"]),
        ["used_in", "details", "description", "notes"]
    );
    // The browser session now sees it in the session payload, too.
    let session =
        json(send(&f, cookie_request("GET", "/session", &session, None, None)).await).await;
    assert_eq!(
        strings(&session["data"]["user"]["detail_card_order"]["yarn"]),
        ["used_in", "details", "description", "notes"]
    );
}

#[tokio::test]
async fn migration_adds_nullable_columns_with_default_order() {
    let f = fixture().await;
    let columns: Vec<(String, i64)> = sqlx::query_as(
        "SELECT name, \"notnull\" FROM pragma_table_info('users') \
         WHERE name IN ('detail_order_project', 'detail_order_yarn') ORDER BY name",
    )
    .fetch_all(&f.pool)
    .await
    .unwrap();
    assert_eq!(
        columns,
        [
            ("detail_order_project".to_owned(), 0),
            ("detail_order_yarn".to_owned(), 0)
        ]
    );
    let stored: (Option<String>, Option<String>) =
        sqlx::query_as("SELECT detail_order_project, detail_order_yarn FROM users WHERE id = ?")
            .bind(f.user_id)
            .fetch_one(&f.pool)
            .await
            .unwrap();
    assert_eq!(stored, (None, None));
}
