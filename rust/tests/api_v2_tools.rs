use axum::{Router, body::Body, http::Request};
use http_body_util::BodyExt;
use stricknani_server::{api_v2, db};
use tower::ServiceExt;

async fn app() -> Router {
    let pool = db::connect("sqlite::memory:")
        .await
        .expect("in-memory database should migrate");
    api_v2::router(db::AppState::new(pool), false)
}

async fn request(
    app: Router,
    payload: serde_json::Value,
) -> (axum::http::StatusCode, serde_json::Value) {
    let response = app
        .oneshot(
            Request::post("/tools/gauge")
                .header("content-type", "application/json")
                .body(Body::from(payload.to_string()))
                .unwrap(),
        )
        .await
        .unwrap();
    let status = response.status();
    let bytes = response.into_body().collect().await.unwrap().to_bytes();
    (status, serde_json::from_slice(&bytes).unwrap())
}

#[tokio::test]
async fn gauge_endpoint_uses_shared_formula_and_optional_row_count() {
    let app = app().await;
    let (status, response) = request(
        app.clone(),
        serde_json::json!({
            "pattern_gauge_stitches": 20,
            "pattern_gauge_rows": 28,
            "user_gauge_stitches": 22,
            "user_gauge_rows": 30,
            "pattern_cast_on_stitches": 80,
            "pattern_row_count": 100
        }),
    )
    .await;
    assert_eq!(status, axum::http::StatusCode::OK);
    assert_eq!(response["data"]["adjusted_stitches"], "88");
    assert_eq!(response["data"]["adjusted_rows"], "107");

    let (status, response) = request(
        app.clone(),
        serde_json::json!({
            "pattern_gauge_stitches": 2,
            "pattern_gauge_rows": 2,
            "user_gauge_stitches": 3,
            "user_gauge_rows": 3,
            "pattern_cast_on_stitches": 1
        }),
    )
    .await;
    assert_eq!(status, axum::http::StatusCode::OK);
    assert_eq!(response["data"]["adjusted_stitches"], "2");
    assert!(response["data"]["adjusted_rows"].is_null());

    let (status, response) = request(
        app,
        serde_json::json!({
            "pattern_gauge_stitches": 1,
            "pattern_gauge_rows": 1,
            "user_gauge_stitches": 4294967295_u64,
            "user_gauge_rows": 1,
            "pattern_cast_on_stitches": 4294967295_u64
        }),
    )
    .await;
    assert_eq!(status, axum::http::StatusCode::OK);
    assert_eq!(
        response["data"]["adjusted_stitches"],
        "18446744065119617025"
    );
}

#[tokio::test]
async fn oversized_gauge_json_has_stable_413_envelope_and_no_store() {
    let app = app().await;
    let payload = serde_json::json!({
        "pattern_gauge_stitches": 20,
        "pattern_gauge_rows": 28,
        "user_gauge_stitches": 22,
        "user_gauge_rows": 30,
        "pattern_cast_on_stitches": 80
    });
    let body = format!("{}{}", payload, " ".repeat(9 * 1024));
    let response = app
        .oneshot(
            Request::post("/tools/gauge")
                .header("content-type", "application/json")
                .body(Body::from(body))
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(response.status(), axum::http::StatusCode::PAYLOAD_TOO_LARGE);
    assert_eq!(response.headers()["cache-control"], "no-store");
    let bytes = response.into_body().collect().await.unwrap().to_bytes();
    let response: serde_json::Value = serde_json::from_slice(&bytes).unwrap();
    assert_eq!(response["error"]["code"], "payload_too_large");
}

#[tokio::test]
async fn gauge_endpoint_is_public_and_never_sets_cookies() {
    let app = app().await;
    let response = app
        .oneshot(
            Request::post("/tools/gauge")
                .header("content-type", "application/json")
                .body(Body::from(
                    serde_json::json!({
                        "pattern_gauge_stitches": 20,
                        "pattern_gauge_rows": 28,
                        "user_gauge_stitches": 22,
                        "user_gauge_rows": 30,
                        "pattern_cast_on_stitches": 80
                    })
                    .to_string(),
                ))
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(response.status(), axum::http::StatusCode::OK);
    assert_eq!(response.headers()["cache-control"], "no-store");
    assert!(!response.headers().contains_key("set-cookie"));
}

#[tokio::test]
async fn gauge_endpoint_reports_missing_zero_and_unknown_fields() {
    let app = app().await;
    let (status, response) = request(
        app.clone(),
        serde_json::json!({
            "pattern_gauge_stitches": 0,
            "pattern_gauge_rows": 28,
            "user_gauge_stitches": 22,
            "user_gauge_rows": 30,
            "pattern_cast_on_stitches": 80
        }),
    )
    .await;
    assert_eq!(status, axum::http::StatusCode::UNPROCESSABLE_ENTITY);
    assert_eq!(response["error"]["code"], "validation_error");
    assert_eq!(
        response["error"]["fields"]["pattern_gauge_stitches"],
        "Must be a positive integer"
    );

    let (status, response) = request(
        app.clone(),
        serde_json::json!({
            "pattern_gauge_stitches": 20,
            "pattern_gauge_rows": 28,
            "user_gauge_stitches": 22,
            "user_gauge_rows": 30,
            "pattern_cast_on_stitches": 80,
            "pattern_row_count": 0
        }),
    )
    .await;
    assert_eq!(status, axum::http::StatusCode::UNPROCESSABLE_ENTITY);
    assert!(response["error"]["fields"]["pattern_row_count"].is_string());

    let (status, response) = request(app.clone(), serde_json::json!({})).await;
    assert_eq!(status, axum::http::StatusCode::UNPROCESSABLE_ENTITY);
    assert!(response["error"]["fields"]["user_gauge_rows"].is_string());

    let (status, response) = request(app, serde_json::json!({"unexpected":true})).await;
    assert_eq!(status, axum::http::StatusCode::BAD_REQUEST);
    assert_eq!(response["error"]["code"], "invalid_json");
}

#[tokio::test]
async fn gauge_endpoint_rejects_malformed_and_wrongly_typed_json() {
    let app = app().await;
    let response = app
        .oneshot(
            Request::post("/tools/gauge")
                .header("content-type", "application/json")
                .body(Body::from("{\"pattern_gauge_stitches\":"))
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(response.status(), axum::http::StatusCode::BAD_REQUEST);
    let bytes = response.into_body().collect().await.unwrap().to_bytes();
    let response: serde_json::Value = serde_json::from_slice(&bytes).unwrap();
    assert_eq!(response["error"]["code"], "invalid_json");
}

#[tokio::test]
async fn gauge_endpoint_returns_field_errors_for_negative_and_wrongly_typed_counts() {
    let app = app().await;
    for bad_value in [
        serde_json::json!(-1),
        serde_json::json!("20"),
        serde_json::json!(4294967296_u64),
    ] {
        let (status, response) = request(
            app.clone(),
            serde_json::json!({
                "pattern_gauge_stitches": bad_value,
                "pattern_gauge_rows": 28,
                "user_gauge_stitches": 22,
                "user_gauge_rows": 30,
                "pattern_cast_on_stitches": 80
            }),
        )
        .await;
        assert_eq!(status, axum::http::StatusCode::UNPROCESSABLE_ENTITY);
        assert_eq!(response["error"]["code"], "validation_error");
        assert_eq!(
            response["error"]["fields"]["pattern_gauge_stitches"],
            "Must be a positive integer"
        );
    }
}
