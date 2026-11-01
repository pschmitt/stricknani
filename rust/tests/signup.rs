use std::path::PathBuf;

use axum::{
    body::Body,
    http::{Request, StatusCode},
};
use http_body_util::BodyExt;
use sqlx::SqlitePool;
use stricknani_server::{app_with_state, db};
use tower::ServiceExt;

const PASSWORD: &str = "signup-password-long-enough";

async fn app() -> (axum::Router, SqlitePool) {
    let pool = db::connect("sqlite::memory:")
        .await
        .expect("disposable database should migrate");
    let app = app_with_state(
        PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../stricknani/static"),
        db::AppState::new(pool.clone())
            .with_secure_cookies(true)
            .with_signup_enabled(true),
    );
    (app, pool)
}

async fn body(response: axum::response::Response) -> String {
    String::from_utf8(
        response
            .into_body()
            .collect()
            .await
            .expect("response body should be readable")
            .to_bytes()
            .to_vec(),
    )
    .expect("response body should be UTF-8")
}

async fn signup_csrf(app: &axum::Router) -> String {
    let response = app
        .clone()
        .oneshot(
            Request::get("/signup")
                .header("accept-language", "en")
                .body(Body::empty())
                .expect("request is valid"),
        )
        .await
        .expect("router should respond");
    assert_eq!(response.status(), StatusCode::OK);
    let cookie = response
        .headers()
        .get_all("set-cookie")
        .iter()
        .find_map(|value| {
            let value = value.to_str().ok()?;
            let (name, value) = value.split_once('=')?;
            (name == "__Host-stricknani_login_csrf").then(|| value.split(';').next())?
        })
        .expect("host-prefixed CSRF cookie should be set")
        .to_owned();
    assert!(
        response
            .headers()
            .get_all("set-cookie")
            .iter()
            .any(|value| {
                value.to_str().is_ok_and(|value| {
                    value.starts_with("__Host-stricknani_login_csrf=")
                        && value.contains("Path=/")
                        && value.contains("Secure")
                        && value.contains("HttpOnly")
                        && value.contains("SameSite=Lax")
                })
            })
    );
    let html = body(response).await;
    assert!(html.contains("action=\"/signup\""));
    assert!(html.contains(&format!("name=\"csrf\" value=\"{cookie}\"")));
    cookie
}

fn post_form(path: &str, cookie: &str, fields: &[(&str, &str)]) -> Request<Body> {
    let encoded = fields
        .iter()
        .map(|(key, value)| {
            format!(
                "{key}={}",
                url::form_urlencoded::byte_serialize(value.as_bytes()).collect::<String>()
            )
        })
        .collect::<Vec<_>>()
        .join("&");
    Request::post(path)
        .header("content-type", "application/x-www-form-urlencoded")
        .header("accept-language", "en")
        .header("cookie", format!("__Host-stricknani_login_csrf={cookie}"))
        .body(Body::from(encoded))
        .expect("form request is valid")
}

#[tokio::test]
async fn signup_creates_a_member_and_shows_login_link() {
    let (app, pool) = app().await;
    let csrf = signup_csrf(&app).await;
    let response = app
        .oneshot(post_form(
            "/signup",
            &csrf,
            &[
                ("email", "  New.Member@Example.Test "),
                ("password", PASSWORD),
                ("csrf", &csrf),
                ("is_admin", "1"),
            ],
        ))
        .await
        .expect("router should respond");
    assert_eq!(response.status(), StatusCode::CREATED);
    let html = body(response).await;
    assert!(html.contains("Your account was created"));
    assert!(html.contains("href=\"/login\""));
    let row = sqlx::query_as::<_, (String, i64)>("SELECT email, is_admin FROM users")
        .fetch_one(&pool)
        .await
        .expect("created account should be stored");
    assert_eq!(row, ("new.member@example.test".to_owned(), 0));
}

#[tokio::test]
async fn signup_rejects_a_request_without_matching_csrf_before_creating_an_account() {
    let (app, pool) = app().await;
    let csrf = signup_csrf(&app).await;
    let response = app
        .oneshot(post_form(
            "/signup",
            &csrf,
            &[
                ("email", "csrf@example.test"),
                ("password", PASSWORD),
                ("csrf", "wrong"),
            ],
        ))
        .await
        .expect("router should respond");
    assert_eq!(response.status(), StatusCode::FORBIDDEN);
    let count: i64 = sqlx::query_scalar("SELECT count(*) FROM users")
        .fetch_one(&pool)
        .await
        .expect("user count should be readable");
    assert_eq!(count, 0);
}

#[tokio::test]
async fn signup_rejects_trimmed_case_insensitive_common_passwords() {
    let (app, pool) = app().await;
    let csrf = signup_csrf(&app).await;
    let response = app
        .oneshot(post_form(
            "/signup",
            &csrf,
            &[
                ("email", "weak@example.test"),
                ("password", "  PaSsWoRd123  "),
                ("csrf", &csrf),
            ],
        ))
        .await
        .expect("router should respond");
    assert_eq!(response.status(), StatusCode::UNPROCESSABLE_ENTITY);
    assert!(body(response).await.contains("too common"));
    let count: i64 = sqlx::query_scalar("SELECT count(*) FROM users")
        .fetch_one(&pool)
        .await
        .expect("user count should be readable");
    assert_eq!(count, 0);
}

#[tokio::test]
async fn signup_attempts_are_charged_even_after_success() {
    let (app, pool) = app().await;
    let email = "limited@example.test";
    for attempt in 0..5 {
        let csrf = signup_csrf(&app).await;
        let response = app
            .clone()
            .oneshot(post_form(
                "/signup",
                &csrf,
                &[("email", email), ("password", PASSWORD), ("csrf", &csrf)],
            ))
            .await
            .expect("router should respond");
        assert_eq!(
            response.status(),
            if attempt == 0 {
                StatusCode::CREATED
            } else {
                StatusCode::CONFLICT
            }
        );
    }
    let csrf = signup_csrf(&app).await;
    let response = app
        .oneshot(post_form(
            "/signup",
            &csrf,
            &[("email", email), ("password", PASSWORD), ("csrf", &csrf)],
        ))
        .await
        .expect("router should respond");
    assert_eq!(response.status(), StatusCode::TOO_MANY_REQUESTS);
    let count: i64 = sqlx::query_scalar("SELECT count(*) FROM users")
        .fetch_one(&pool)
        .await
        .expect("user count should be readable");
    assert_eq!(count, 1);
}

#[tokio::test]
async fn signup_attempts_are_also_limited_per_ip() {
    let (app, pool) = app().await;
    let address = std::net::SocketAddr::from(([127, 0, 0, 1], 12345));
    for attempt in 0..5 {
        let csrf = signup_csrf(&app).await;
        let mut request = post_form(
            "/signup",
            &csrf,
            &[
                ("email", &format!("person-{attempt}@example.test")),
                ("password", "short"),
                ("csrf", &csrf),
            ],
        );
        request
            .extensions_mut()
            .insert(axum::extract::ConnectInfo(address));
        let response = app
            .clone()
            .oneshot(request)
            .await
            .expect("router should respond");
        assert_eq!(response.status(), StatusCode::UNPROCESSABLE_ENTITY);
    }
    let csrf = signup_csrf(&app).await;
    let mut request = post_form(
        "/signup",
        &csrf,
        &[
            ("email", "another@example.test"),
            ("password", "short"),
            ("csrf", &csrf),
        ],
    );
    request
        .extensions_mut()
        .insert(axum::extract::ConnectInfo(address));
    let response = app.oneshot(request).await.expect("router should respond");
    assert_eq!(response.status(), StatusCode::TOO_MANY_REQUESTS);
    let count: i64 = sqlx::query_scalar("SELECT count(*) FROM users")
        .fetch_one(&pool)
        .await
        .expect("user count should be readable");
    assert_eq!(count, 0);
}

#[tokio::test]
async fn public_signup_is_disabled_by_default() {
    let pool = db::connect("sqlite::memory:").await.unwrap();
    let app = app_with_state(
        PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../stricknani/static"),
        db::AppState::new(pool.clone()),
    );
    for method in ["GET", "POST"] {
        let response = app
            .clone()
            .oneshot(
                Request::builder()
                    .method(method)
                    .uri("/signup")
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::NOT_FOUND);
    }
    let response = app
        .oneshot(Request::get("/login").body(Body::empty()).unwrap())
        .await
        .unwrap();
    assert!(!body(response).await.contains("href=\"/signup\""));
    let count: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM users")
        .fetch_one(&pool)
        .await
        .unwrap();
    assert_eq!(count, 0);
}
