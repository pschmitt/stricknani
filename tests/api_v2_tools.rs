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

fn base_gauge() -> serde_json::Value {
    serde_json::json!({
        "pattern_gauge_stitches": 20,
        "pattern_gauge_rows": 28,
        "user_gauge_stitches": 22,
        "user_gauge_rows": 30,
        "pattern_cast_on_stitches": 80,
        "pattern_row_count": 100
    })
}

#[tokio::test]
async fn default_and_explicit_ten_cm_swatches_are_identical_including_ties() {
    let app = app().await;
    let mut cases = vec![base_gauge()];
    // Half-up ties: 5 * 1 / 2 = 2.5, 7 * 1 / 2 = 3.5, 121 * 19 / 22 = 104.5.
    for (cast_on, user, pattern, expected) in [(5, 1, 2, "3"), (7, 1, 2, "4"), (121, 19, 22, "105")]
    {
        let case = serde_json::json!({
            "pattern_gauge_stitches": pattern,
            "pattern_gauge_rows": pattern,
            "user_gauge_stitches": user,
            "user_gauge_rows": user,
            "pattern_cast_on_stitches": cast_on,
            "pattern_row_count": cast_on
        });
        let (status, response) = request(app.clone(), case.clone()).await;
        assert_eq!(status, axum::http::StatusCode::OK);
        assert_eq!(response["data"]["adjusted_stitches"], expected);
        assert_eq!(response["data"]["adjusted_rows"], expected);
        cases.push(case);
    }
    for case in cases {
        let (_, omitted) = request(app.clone(), case.clone()).await;
        let mut explicit = case;
        for field in [
            "pattern_swatch_width_cm",
            "pattern_swatch_height_cm",
            "user_swatch_width_cm",
            "user_swatch_height_cm",
        ] {
            explicit[field] = serde_json::json!(10);
        }
        explicit["user_swatch_width_cm"] = serde_json::json!(10.0);
        let (status, with_ten) = request(app.clone(), explicit).await;
        assert_eq!(status, axum::http::StatusCode::OK);
        assert_eq!(omitted, with_ten);
    }
}

#[tokio::test]
async fn swatch_sizes_compare_densities() {
    let app = app().await;
    // 5 x 5 cm with 9/12 equals 18/24 per 10 cm.
    let mut sized = base_gauge();
    sized["user_gauge_stitches"] = serde_json::json!(9);
    sized["user_gauge_rows"] = serde_json::json!(12);
    sized["user_swatch_width_cm"] = serde_json::json!(5);
    sized["user_swatch_height_cm"] = serde_json::json!(5.0);
    let mut per_ten = base_gauge();
    per_ten["user_gauge_stitches"] = serde_json::json!(18);
    per_ten["user_gauge_rows"] = serde_json::json!(24);
    let (status, sized) = request(app.clone(), sized).await;
    assert_eq!(status, axum::http::StatusCode::OK);
    let (_, per_ten) = request(app.clone(), per_ten).await;
    assert_eq!(sized, per_ten);

    // Pattern swatch of 20 cm doubles the pattern width: 80 * 22 * 20 / (20 * 10) = 176.
    let mut pattern = base_gauge();
    pattern["pattern_swatch_width_cm"] = serde_json::json!(20);
    let (_, pattern) = request(app.clone(), pattern).await;
    assert_eq!(pattern["data"]["adjusted_stitches"], "176");
    assert_eq!(pattern["data"]["adjusted_rows"], "107");

    // Decimal sizes are exact: 12.5 cm user width, tie 1 * 5 * 10 / (2 * 12.5) = 2.
    let (_, tie) = request(
        app.clone(),
        serde_json::json!({
            "pattern_gauge_stitches": 2,
            "pattern_gauge_rows": 2,
            "user_gauge_stitches": 5,
            "user_gauge_rows": 5,
            "pattern_cast_on_stitches": 1,
            "pattern_row_count": 1,
            "user_swatch_width_cm": 12.5,
            "user_swatch_height_cm": 4
        }),
    )
    .await;
    assert_eq!(tie["data"]["adjusted_stitches"], "2");
    // 1 * 5 * 10 / (2 * 4) = 6.25 -> 6
    assert_eq!(tie["data"]["adjusted_rows"], "6");

    // Extreme ratio exceeds u64 but stays exact: 4294967295^2 * 100000.
    let (status, big) = request(
        app,
        serde_json::json!({
            "pattern_gauge_stitches": 1,
            "pattern_gauge_rows": 1,
            "user_gauge_stitches": 4294967295_u64,
            "user_gauge_rows": 1,
            "pattern_cast_on_stitches": 4294967295_u64,
            "pattern_swatch_width_cm": 100,
            "user_swatch_width_cm": 0.001
        }),
    )
    .await;
    assert_eq!(status, axum::http::StatusCode::OK);
    assert_eq!(
        big["data"]["adjusted_stitches"],
        "1844674406511961702500000"
    );
}

#[tokio::test]
async fn invalid_swatch_sizes_return_field_errors() {
    let app = app().await;
    for field in [
        "pattern_swatch_width_cm",
        "pattern_swatch_height_cm",
        "user_swatch_width_cm",
        "user_swatch_height_cm",
    ] {
        for bad in [
            serde_json::json!(0),
            serde_json::json!(-5),
            serde_json::json!(100.001),
            serde_json::json!(101),
            serde_json::json!(1.0001),
            serde_json::json!("10"),
            serde_json::json!(true),
        ] {
            let mut payload = base_gauge();
            payload[field] = bad.clone();
            let (status, response) = request(app.clone(), payload).await;
            assert_eq!(
                status,
                axum::http::StatusCode::UNPROCESSABLE_ENTITY,
                "{field} {bad}"
            );
            assert_eq!(response["error"]["code"], "validation_error");
            assert!(response["error"]["fields"][field].is_string());
        }
    }
    for ok in [
        serde_json::json!(100),
        serde_json::json!(0.001),
        serde_json::json!(null),
    ] {
        let mut payload = base_gauge();
        payload["user_swatch_width_cm"] = ok;
        let (status, _) = request(app.clone(), payload).await;
        assert_eq!(status, axum::http::StatusCode::OK);
    }
}
