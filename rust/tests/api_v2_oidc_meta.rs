use std::sync::Arc;

use axum::{
    body::Body,
    http::{Request, StatusCode},
};
use http_body_util::BodyExt;
use serde_json::{Value, json};
use stricknani_server::{api_v2, db, oidc::ProviderConfig, oidc_http_state::OidcRuntime};
use tower::ServiceExt;
use zeroize::Zeroizing;

#[tokio::test]
async fn metadata_advertises_only_explicitly_configured_oidc_without_discovery() {
    for enabled in [false, true] {
        let pool = db::connect("sqlite::memory:").await.unwrap();
        let mut state = db::AppState::new(pool).with_secure_cookies(true);
        if enabled {
            let runtime = OidcRuntime::from_config(ProviderConfig {
                id: "authelia".into(),
                issuer: "https://issuer.example.test/".into(),
                client_id: "metadata-test-client".into(),
                client_secret: Zeroizing::new("metadata-test-only-secret".into()),
                callback_uri: "https://app.example.test/api/v2/auth/oidc/callback/authelia".into(),
                allowed_endpoint_origins: Vec::new(),
                native_redirect_uris: vec!["stricknani://oidc-callback".into()],
                allow_http_for_tests: false,
            })
            .unwrap();
            state = state.with_oidc(Arc::new(runtime));
        }
        let app = api_v2::router(state, false);
        let response = app
            .clone()
            .oneshot(Request::get("/meta").body(Body::empty()).unwrap())
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::OK);
        assert!(
            response.headers()["cache-control"]
                .to_str()
                .unwrap()
                .contains("no-store")
        );
        let value: Value =
            serde_json::from_slice(&response.into_body().collect().await.unwrap().to_bytes())
                .unwrap();
        assert_eq!(value["data"]["user"], Value::Null);
        assert_eq!(value["data"]["auth"]["password"], true);
        let providers = if enabled {
            json!([{"id":"authelia","name":"Authelia"}])
        } else {
            json!([])
        };
        assert_eq!(value["data"]["auth"]["oidc"], providers);
        let invalid = app
            .oneshot(
                Request::get("/meta")
                    .header("authorization", "Bearer invalid-test-pat")
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(invalid.status(), StatusCode::UNAUTHORIZED);
    }
}
