use std::{
    collections::HashMap,
    net::SocketAddr,
    sync::{
        Arc, Mutex,
        atomic::{AtomicUsize, Ordering},
    },
};

use axum::{
    Json, Router,
    extract::{Form, State},
    routing::{get, post},
};
use base64::{Engine, engine::general_purpose::URL_SAFE_NO_PAD};
use chrono::Utc;
use openidconnect::{
    Audience, EmptyAdditionalClaims, EndUserEmail, IssuerUrl, JsonWebKeyId, Nonce,
    PrivateSigningKey, StandardClaims, SubjectIdentifier,
    core::{
        CoreIdToken, CoreIdTokenClaims, CoreJsonWebKeySet, CoreJwsSigningAlgorithm,
        CoreRsaPrivateSigningKey,
    },
};
use rsa::{
    PrivateKey,
    pkcs1::{EncodeRsaPrivateKey, LineEnding},
};
use serde_json::Value;
use sha2::{Digest, Sha256};
use tokio::{net::TcpListener, sync::Mutex as AsyncMutex, task::JoinHandle};
use url::Url;

use stricknani_server::oidc::ProviderConfig;
use zeroize::Zeroizing;

const CLIENT_ID: &str = "oidc-http-test-client";

struct IssuerState {
    issuer: String,
    jwks: Value,
    signing_key: String,
    token: AsyncMutex<Option<String>>,
    challenge: Mutex<Option<String>>,
    token_calls: AtomicUsize,
}

/// Loopback OIDC issuer that signs a fresh ID token for each prepared callback.
pub struct MockOidcIssuer {
    issuer: String,
    state: Arc<IssuerState>,
    task: JoinHandle<()>,
}

impl MockOidcIssuer {
    pub async fn start() -> Self {
        let private = PrivateKey::new(&mut rand::rng(), 2048).unwrap();
        let signing_key = private.to_pkcs1_pem(LineEnding::LF).unwrap().to_string();
        let signing_key_object = CoreRsaPrivateSigningKey::from_pem(
            &signing_key,
            Some(JsonWebKeyId::new("mock-signing-key".into())),
        )
        .unwrap();
        let key_set = serde_json::to_value(CoreJsonWebKeySet::new(vec![
            signing_key_object.as_verification_key(),
        ]))
        .unwrap();
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let address: SocketAddr = listener.local_addr().unwrap();
        let issuer = format!("http://{address}/");
        let state = Arc::new(IssuerState {
            issuer: issuer.clone(),
            jwks: key_set,
            signing_key,
            token: AsyncMutex::new(None),
            challenge: Mutex::new(None),
            token_calls: AtomicUsize::new(0),
        });
        let app = Router::new()
            .route("/.well-known/openid-configuration", get(metadata))
            .route("/jwks", get(jwks))
            .route("/token", post(token))
            .with_state(Arc::clone(&state));
        let task = tokio::spawn(async move {
            let _ = axum::serve(listener, app).await;
        });
        Self {
            issuer,
            state,
            task,
        }
    }

    pub fn issuer(&self) -> &str {
        &self.issuer
    }

    pub fn provider_config(&self, native_redirect_uris: Vec<String>) -> ProviderConfig {
        ProviderConfig {
            id: "authelia".into(),
            issuer: self.issuer.clone(),
            client_id: CLIENT_ID.into(),
            client_secret: Zeroizing::new("test-only-secret".into()),
            callback_uri: "https://app.example.test/api/v2/auth/oidc/callback/authelia".into(),
            allowed_endpoint_origins: Vec::new(),
            native_redirect_uris,
            allow_http_for_tests: true,
        }
    }

    /// Prepare the next token response to match a provider authorization URL.
    pub async fn prepare_callback(&self, authorization_url: &Url, subject: &str, email: &str) {
        let query: HashMap<String, String> = authorization_url.query_pairs().into_owned().collect();
        let nonce = query.get("nonce").expect("authorization URL has nonce");
        let challenge = query
            .get("code_challenge")
            .expect("authorization URL has PKCE challenge");
        *self.state.challenge.lock().unwrap() = Some(challenge.clone());
        *self.state.token.lock().await = Some(sign_id_token(
            &self.state.signing_key,
            &self.issuer,
            nonce,
            subject,
            email,
        ));
    }

    pub fn token_calls(&self) -> usize {
        self.state.token_calls.load(Ordering::Relaxed)
    }
}

impl Drop for MockOidcIssuer {
    fn drop(&mut self) {
        self.task.abort();
    }
}

async fn metadata(State(state): State<Arc<IssuerState>>) -> Json<Value> {
    Json(serde_json::json!({
        "issuer": state.issuer,
        "authorization_endpoint": format!("{}authorize", state.issuer),
        "token_endpoint": format!("{}token", state.issuer),
        "jwks_uri": format!("{}jwks", state.issuer),
        "response_types_supported": ["code"],
        "subject_types_supported": ["public"],
        "id_token_signing_alg_values_supported": ["RS256"],
        "token_endpoint_auth_methods_supported": ["client_secret_basic"],
        "authorization_response_iss_parameter_supported": true
    }))
}

async fn jwks(State(state): State<Arc<IssuerState>>) -> Json<Value> {
    Json(state.jwks.clone())
}

async fn token(
    State(state): State<Arc<IssuerState>>,
    headers: axum::http::HeaderMap,
    Form(fields): Form<HashMap<String, String>>,
) -> Result<Json<Value>, axum::http::StatusCode> {
    state.token_calls.fetch_add(1, Ordering::Relaxed);
    if !headers
        .get(axum::http::header::AUTHORIZATION)
        .is_some_and(|value| value.as_bytes().starts_with(b"Basic "))
    {
        return Err(axum::http::StatusCode::UNAUTHORIZED);
    }
    let verifier = fields
        .get("code_verifier")
        .ok_or(axum::http::StatusCode::BAD_REQUEST)?;
    let challenge = URL_SAFE_NO_PAD.encode(Sha256::digest(verifier.as_bytes()));
    if state.challenge.lock().unwrap().as_deref() != Some(challenge.as_str()) {
        return Err(axum::http::StatusCode::BAD_REQUEST);
    }
    let id_token = state
        .token
        .lock()
        .await
        .clone()
        .ok_or(axum::http::StatusCode::SERVICE_UNAVAILABLE)?;
    Ok(Json(serde_json::json!({
        "access_token": "opaque-test-access-token",
        "token_type": "Bearer",
        "expires_in": 300,
        "id_token": id_token
    })))
}

fn sign_id_token(
    signing_key_pem: &str,
    issuer: &str,
    nonce: &str,
    subject: &str,
    email: &str,
) -> String {
    let now = Utc::now();
    let standard_claims = StandardClaims::new(SubjectIdentifier::new(subject.to_owned()))
        .set_email(Some(EndUserEmail::new(email.to_owned())))
        .set_email_verified(Some(true));
    let claims = CoreIdTokenClaims::new(
        IssuerUrl::new(issuer.to_owned()).unwrap(),
        vec![Audience::new(CLIENT_ID.to_owned())],
        now + chrono::TimeDelta::minutes(5),
        now,
        standard_claims,
        EmptyAdditionalClaims {},
    )
    .set_nonce(Some(Nonce::new(nonce.to_owned())))
    .set_auth_time(Some(now - chrono::TimeDelta::seconds(10)));
    let signing_key = CoreRsaPrivateSigningKey::from_pem(
        signing_key_pem,
        Some(JsonWebKeyId::new("mock-signing-key".into())),
    )
    .unwrap();
    CoreIdToken::new(
        claims,
        &signing_key,
        CoreJwsSigningAlgorithm::RsaSsaPkcs1V15Sha256,
        None,
        None,
    )
    .unwrap()
    .to_string()
}
