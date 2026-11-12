use std::future::Future;
use std::io;
use std::pin::Pin;
use std::sync::Mutex;
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::Duration;

use chrono::Utc;
use openidconnect::core::{
    CoreClient, CoreClientAuthMethod, CoreIdToken, CoreJwsSigningAlgorithm, CoreProviderMetadata,
    CoreResponseType,
};
use openidconnect::{
    AsyncHttpClient, AuthType, AuthenticationFlow, AuthorizationCode, ClaimsVerificationError,
    ClientId, ClientSecret, CsrfToken, EndpointMaybeSet, EndpointNotSet, EndpointSet, HttpRequest,
    HttpResponse, IssuerUrl, Nonce, PkceCodeChallenge, PkceCodeVerifier, RedirectUrl, Scope,
    SignatureVerificationError, TokenResponse,
};
use reqwest::redirect::Policy;
use serde::Deserialize;
use url::Url;
use zeroize::Zeroizing;

use super::flow::{FlowBinding, FlowError, FlowStore};
use super::types::ValidatedIdentity;

const HTTP_TIMEOUT: Duration = Duration::from_secs(8);
const MAX_RESPONSE_BYTES: usize = 1024 * 1024;
const MAX_AUTH_TIME_AGE_SECS: i64 = 5 * 60;
const JWKS_REFRESH_INTERVAL: Duration = Duration::from_secs(30);

type DiscoveredClient = CoreClient<
    EndpointSet,
    EndpointNotSet,
    EndpointNotSet,
    EndpointNotSet,
    EndpointMaybeSet,
    EndpointMaybeSet,
>;

/// Runtime settings for one confidential authorization-code OIDC provider.
#[derive(Clone)]
pub struct ProviderConfig {
    pub id: String,
    pub issuer: String,
    pub client_id: String,
    pub client_secret: Zeroizing<String>,
    pub callback_uri: String,
    /// Additional endpoint origins explicitly approved by the deployment.
    pub allowed_endpoint_origins: Vec<String>,
    /// Exact verified HTTPS App Links accepted for native returns.
    pub native_redirect_uris: Vec<String>,
    /// Permit plain HTTP only for local mock-provider tests.
    pub allow_http_for_tests: bool,
}

impl ProviderConfig {
    /// Validate static configuration without contacting the issuer.
    pub fn validate(&self) -> Result<(), ProviderError> {
        if self.id.is_empty()
            || self.id.len() > 64
            || !self
                .id
                .bytes()
                .all(|byte| byte.is_ascii_alphanumeric() || byte == b'-' || byte == b'_')
            || self.client_id.is_empty()
            || self.client_id.len() > 256
            || self.client_secret.is_empty()
            || self.client_secret.len() > 4096
            || self.issuer.len() > 2048
        {
            return Err(ProviderError::Configuration);
        }
        let issuer =
            IssuerUrl::new(self.issuer.clone()).map_err(|_| ProviderError::Configuration)?;
        let issuer_uri = parse_https_url(issuer.as_str(), self.allow_http_for_tests)?;
        if issuer_uri.query().is_some() {
            return Err(ProviderError::Configuration);
        }
        let callback = parse_https_url(&self.callback_uri, self.allow_http_for_tests)?;
        if callback.path() != "/api/v2/auth/oidc/callback/authelia" || callback.query().is_some() {
            return Err(ProviderError::Configuration);
        }
        for origin in &self.allowed_endpoint_origins {
            normalize_origin(origin, self.allow_http_for_tests)?;
        }
        for redirect in &self.native_redirect_uris {
            if redirect.len() > 2048 {
                return Err(ProviderError::Configuration);
            }
            validate_native_redirect(redirect)?;
        }
        Ok(())
    }
}

/// A discovered provider client and its bounded single-use flow store.
pub struct OidcProvider {
    id: String,
    issuer: String,
    client: Mutex<DiscoveredClient>,
    client_id: String,
    client_secret: Zeroizing<String>,
    issuer_url: IssuerUrl,
    http: SafeHttpClient,
    callback_uri: RedirectUrl,
    allowed_endpoint_origins: Vec<String>,
    flows: FlowStore,
    response_iss_supported: AtomicBool,
    last_jwks_refresh: Mutex<Option<std::time::Instant>>,
    allow_http_for_tests: bool,
    native_redirect_uris: Vec<String>,
}

#[derive(Clone, Copy, Debug, thiserror::Error, PartialEq, Eq)]
pub enum ProviderError {
    #[error("OIDC provider configuration is invalid")]
    Configuration,
    #[error("OIDC provider is unavailable")]
    Unavailable,
    #[error("OIDC callback is invalid")]
    InvalidCallback,
    #[error("OIDC authorization flow is invalid or expired")]
    InvalidFlow,
    #[error("OIDC identity token is invalid")]
    InvalidToken,
}

impl From<FlowError> for ProviderError {
    fn from(_: FlowError) -> Self {
        Self::InvalidFlow
    }
}

#[derive(Deserialize)]
struct IssuerResponseMetadata {
    issuer: String,
    authorization_endpoint: String,
    token_endpoint: Option<String>,
    jwks_uri: String,
    #[serde(default)]
    authorization_response_iss_parameter_supported: bool,
}

impl OidcProvider {
    /// Discover and validate the issuer before retaining any provider endpoints.
    pub async fn discover(config: ProviderConfig) -> Result<Self, ProviderError> {
        config.validate()?;
        if config.id.is_empty()
            || config.id.len() > 64
            || !config
                .id
                .bytes()
                .all(|byte| byte.is_ascii_alphanumeric() || byte == b'-' || byte == b'_')
            || config.client_id.is_empty()
            || config.client_id.len() > 256
            || config.client_secret.is_empty()
            || config.client_secret.len() > 4096
            || config.issuer.len() > 2048
        {
            return Err(ProviderError::Configuration);
        }
        let issuer_url =
            IssuerUrl::new(config.issuer.clone()).map_err(|_| ProviderError::Configuration)?;
        let callback = parse_https_url(&config.callback_uri, config.allow_http_for_tests)?;
        if callback.path() != "/api/v2/auth/oidc/callback/authelia" || callback.query().is_some() {
            return Err(ProviderError::Configuration);
        }
        let callback_uri =
            RedirectUrl::new(callback.to_string()).map_err(|_| ProviderError::Configuration)?;
        let allowed_endpoint_origins = config
            .allowed_endpoint_origins
            .iter()
            .map(|origin| normalize_origin(origin, config.allow_http_for_tests))
            .collect::<Result<Vec<_>, _>>()?;
        let native_redirect_uris = config
            .native_redirect_uris
            .iter()
            .map(|uri| validate_native_redirect(uri))
            .collect::<Result<Vec<_>, ProviderError>>()?;
        let http = SafeHttpClient::new(config.allow_http_for_tests)?;
        let raw_metadata = fetch_discovery_metadata(&http, &issuer_url).await?;
        validate_raw_endpoints(
            &raw_metadata,
            &issuer_url,
            &allowed_endpoint_origins,
            config.allow_http_for_tests,
        )?;
        let metadata_iss_supported = raw_metadata.authorization_response_iss_parameter_supported;
        let metadata = CoreProviderMetadata::discover_async(issuer_url.clone(), &http)
            .await
            .map_err(|_| ProviderError::Unavailable)?;
        if metadata.issuer() != &issuer_url {
            return Err(ProviderError::Configuration);
        }
        if !supports_configured_auth(&metadata) {
            return Err(ProviderError::Configuration);
        }
        for endpoint in [
            Some(metadata.authorization_endpoint().url()),
            metadata.token_endpoint().map(|url| url.url()),
            Some(metadata.jwks_uri().url()),
        ]
        .into_iter()
        .flatten()
        {
            validate_endpoint(
                endpoint,
                &issuer_url,
                &allowed_endpoint_origins,
                config.allow_http_for_tests,
            )?;
        }
        let client_id = config.client_id;
        let client_secret = Zeroizing::new(config.client_secret.to_string());
        let client = make_client(metadata, &client_id, &client_secret, callback_uri.clone());

        Ok(Self {
            id: config.id,
            issuer: config.issuer,
            client: Mutex::new(client),
            client_id,
            client_secret,
            issuer_url,
            http,
            callback_uri,
            allowed_endpoint_origins,
            flows: FlowStore::new(),
            response_iss_supported: AtomicBool::new(metadata_iss_supported),
            last_jwks_refresh: Mutex::new(None),
            allow_http_for_tests: config.allow_http_for_tests,
            native_redirect_uris,
        })
    }

    pub fn id(&self) -> &str {
        &self.id
    }

    pub fn issuer(&self) -> &str {
        &self.issuer
    }

    /// Create and retain a fresh state, nonce and independent server PKCE pair.
    pub fn start(&self, binding: FlowBinding) -> Result<Url, ProviderError> {
        if binding.provider_id != self.id {
            return Err(ProviderError::InvalidFlow);
        }
        if let Some(redirect) = &binding.native_redirect_uri
            && !self.native_redirect_uris.contains(redirect)
        {
            return Err(ProviderError::InvalidFlow);
        }
        match (
            binding.native_redirect_uri.is_some(),
            binding.app_code_challenge.as_deref(),
            binding.app_state.as_deref(),
        ) {
            (true, Some(challenge), Some(state))
                if valid_s256_challenge(challenge) && valid_app_state(state) => {}
            (false, None, None) => {}
            _ => return Err(ProviderError::InvalidFlow),
        }
        if binding
            .return_path
            .as_deref()
            .is_some_and(|path| !valid_local_path(path))
        {
            return Err(ProviderError::InvalidFlow);
        }
        if binding.max_auth_age_secs.is_some_and(|age| age > 86_400) {
            return Err(ProviderError::InvalidFlow);
        }
        let is_reauth = matches!(binding.purpose, super::FlowPurpose::Reauth { .. });
        let max_auth_age_secs = binding.max_auth_age_secs.or(is_reauth.then_some(0));
        let flow = self.flows.insert(binding)?;
        let verifier = PkceCodeVerifier::new(flow.provider_verifier.to_string());
        let challenge = PkceCodeChallenge::from_code_verifier_sha256(&verifier);
        let client = self
            .client
            .lock()
            .map_err(|_| ProviderError::Unavailable)?
            .clone();
        let state = flow.state;
        let nonce = flow.nonce;
        let mut request = client
            .authorize_url(
                AuthenticationFlow::<CoreResponseType>::AuthorizationCode,
                move || CsrfToken::new(state),
                move || Nonce::new(nonce.to_string()),
            )
            .add_scope(Scope::new("openid".into()))
            .add_scope(Scope::new("email".into()))
            .add_scope(Scope::new("profile".into()))
            .set_pkce_challenge(challenge);
        if let Some(max_age) = max_auth_age_secs {
            request = request.add_extra_param("prompt", "login");
            request = request.add_extra_param("max_age", max_age.to_string());
        }
        let (url, _, _) = request.url();
        validate_endpoint(
            &url,
            &IssuerUrl::new(self.issuer.clone()).map_err(|_| ProviderError::Configuration)?,
            &self.allowed_endpoint_origins,
            self.allow_http_for_tests,
        )?;
        Ok(url)
    }

    /// Consume flow state, exchange the code and return a verified identity.
    /// The caller remains responsible for local account resolution and token minting.
    pub async fn callback(
        &self,
        code: &str,
        state: &str,
        response_issuer: Option<&str>,
        expected_binding: &FlowBinding,
    ) -> Result<(FlowBinding, ValidatedIdentity), ProviderError> {
        let flow = self.flows.consume(state, expected_binding)?;
        if code.is_empty() || code.len() > 4096 {
            return Err(ProviderError::InvalidCallback);
        }
        if response_issuer.is_some_and(|value| value.len() > 2048) {
            return Err(ProviderError::InvalidCallback);
        }
        if !valid_response_issuer(
            self.response_iss_supported.load(Ordering::Relaxed),
            response_issuer,
            &self.issuer,
        ) {
            return Err(ProviderError::InvalidCallback);
        }
        let verifier = PkceCodeVerifier::new(flow.provider_verifier.to_string());
        let client = self
            .client
            .lock()
            .map_err(|_| ProviderError::Unavailable)?
            .clone();
        let request = client
            .exchange_code(AuthorizationCode::new(code.to_owned()))
            .map_err(|_| ProviderError::Configuration)?
            .set_pkce_verifier(verifier);
        let token_response = request
            .request_async(&self.http)
            .await
            .map_err(|_| ProviderError::Unavailable)?;
        let id_token: &CoreIdToken = token_response
            .id_token()
            .ok_or(ProviderError::InvalidToken)?;
        let identity = match self.verify_identity(id_token, &flow.nonce, &flow.binding) {
            Ok(identity) => identity,
            Err(VerifyFailure::UnknownSigningKey) => {
                self.refresh_jwks().await?;
                self.verify_identity(id_token, &flow.nonce, &flow.binding)
                    .map_err(|_| ProviderError::InvalidToken)?
            }
            Err(VerifyFailure::Invalid) => return Err(ProviderError::InvalidToken),
        };
        Ok((flow.binding, identity))
    }

    pub fn callback_uri(&self) -> &str {
        self.callback_uri.as_str()
    }

    /// Discard a pending authorization state without exchanging any code.
    pub fn discard_flow(&self, state: &str) -> Result<(), ProviderError> {
        self.flows
            .discard(state)
            .map_err(|_| ProviderError::InvalidFlow)
    }

    fn verify_identity(
        &self,
        id_token: &CoreIdToken,
        nonce: &str,
        binding: &FlowBinding,
    ) -> Result<ValidatedIdentity, VerifyFailure> {
        let client = self.client.lock().map_err(|_| VerifyFailure::Invalid)?;
        let verifier = client
            .id_token_verifier()
            .set_allowed_algs([
                CoreJwsSigningAlgorithm::RsaSsaPkcs1V15Sha256,
                CoreJwsSigningAlgorithm::RsaSsaPssSha256,
            ])
            .set_issue_time_verifier_fn(|issued_at| {
                if issued_at <= Utc::now() + chrono::TimeDelta::seconds(60) {
                    Ok(())
                } else {
                    Err("future iat".to_owned())
                }
            })
            .set_time_fn(|| Utc::now() - chrono::TimeDelta::seconds(60))
            .set_auth_time_verifier_fn(|_| Ok(()));
        let claims = id_token
            .claims(&verifier, &Nonce::new(nonce.to_owned()))
            .map_err(|error| match error {
                ClaimsVerificationError::SignatureVerification(
                    SignatureVerificationError::NoMatchingKey,
                ) => VerifyFailure::UnknownSigningKey,
                _ => VerifyFailure::Invalid,
            })?;
        let authorized_party = claims.authorized_party();
        if !valid_audience_party(
            claims.audiences().len(),
            authorized_party.map(|party| party.as_str()),
            &self.client_id,
        ) {
            return Err(VerifyFailure::Invalid);
        }
        let auth_time = claims.auth_time();
        if binding.max_auth_age_secs.is_some()
            || matches!(binding.purpose, super::FlowPurpose::Reauth { .. })
        {
            let auth_time = auth_time.ok_or(VerifyFailure::Invalid)?;
            let age = Utc::now().signed_duration_since(auth_time).num_seconds();
            let max_age = binding
                .max_auth_age_secs
                .map(i64::try_from)
                .transpose()
                .map_err(|_| VerifyFailure::Invalid)?
                .unwrap_or(MAX_AUTH_TIME_AGE_SECS);
            if !(-60..=max_age).contains(&age) {
                return Err(VerifyFailure::Invalid);
            }
        }
        Ok(ValidatedIdentity::new(
            self.id.clone(),
            self.issuer.clone(),
            claims.subject().as_str().to_owned(),
            claims.email().map(|email| email.as_str().to_owned()),
            claims.email_verified().unwrap_or(false),
            auth_time,
        ))
    }

    async fn refresh_jwks(&self) -> Result<(), ProviderError> {
        {
            let mut last = self
                .last_jwks_refresh
                .lock()
                .map_err(|_| ProviderError::Unavailable)?;
            if last.is_some_and(|at| at.elapsed() < JWKS_REFRESH_INTERVAL) {
                return Err(ProviderError::InvalidToken);
            }
            *last = Some(std::time::Instant::now());
        }
        let raw_metadata = fetch_discovery_metadata(&self.http, &self.issuer_url).await?;
        validate_raw_endpoints(
            &raw_metadata,
            &self.issuer_url,
            &self.allowed_endpoint_origins,
            self.allow_http_for_tests,
        )?;
        let metadata = CoreProviderMetadata::discover_async(self.issuer_url.clone(), &self.http)
            .await
            .map_err(|_| ProviderError::Unavailable)?;
        if !supports_configured_auth(&metadata) {
            return Err(ProviderError::Configuration);
        }
        for endpoint in [
            Some(metadata.authorization_endpoint().url()),
            metadata.token_endpoint().map(|url| url.url()),
            Some(metadata.jwks_uri().url()),
        ]
        .into_iter()
        .flatten()
        {
            validate_endpoint(
                endpoint,
                &self.issuer_url,
                &self.allowed_endpoint_origins,
                self.allow_http_for_tests,
            )?;
        }
        let iss_supported = raw_metadata.authorization_response_iss_parameter_supported;
        let client = make_client(
            metadata,
            &self.client_id,
            &self.client_secret,
            self.callback_uri.clone(),
        );
        *self.client.lock().map_err(|_| ProviderError::Unavailable)? = client;
        self.response_iss_supported
            .store(iss_supported, Ordering::Relaxed);
        Ok(())
    }
}

enum VerifyFailure {
    UnknownSigningKey,
    Invalid,
}

fn make_client(
    metadata: CoreProviderMetadata,
    client_id: &str,
    client_secret: &str,
    callback_uri: RedirectUrl,
) -> DiscoveredClient {
    CoreClient::from_provider_metadata(
        metadata,
        ClientId::new(client_id.to_owned()),
        Some(ClientSecret::new(client_secret.to_owned())),
    )
    .set_auth_type(AuthType::BasicAuth)
    .set_redirect_uri(callback_uri)
}

fn supports_configured_auth(metadata: &CoreProviderMetadata) -> bool {
    metadata
        .token_endpoint_auth_methods_supported()
        .is_none_or(|methods| methods.contains(&CoreClientAuthMethod::ClientSecretBasic))
        && metadata
            .id_token_signing_alg_values_supported()
            .iter()
            .any(|alg| {
                matches!(
                    alg,
                    CoreJwsSigningAlgorithm::RsaSsaPkcs1V15Sha256
                        | CoreJwsSigningAlgorithm::RsaSsaPssSha256
                )
            })
}

fn parse_https_url(value: &str, allow_http: bool) -> Result<Url, ProviderError> {
    if value.len() > 2048 {
        return Err(ProviderError::Configuration);
    }
    let url = Url::parse(value).map_err(|_| ProviderError::Configuration)?;
    if url.host_str().is_none()
        || !url.username().is_empty()
        || url.password().is_some()
        || url.fragment().is_some()
        || (url.scheme() != "https" && !(allow_http && url.scheme() == "http"))
    {
        return Err(ProviderError::Configuration);
    }
    Ok(url)
}

fn validate_native_redirect(value: &str) -> Result<String, ProviderError> {
    if value == "stricknani://oidc-callback" {
        return Ok(value.to_owned());
    }
    let parsed = parse_https_url(value, false)?;
    if parsed.query().is_some() {
        return Err(ProviderError::Configuration);
    }
    Ok(parsed.to_string())
}

fn valid_local_path(path: &str) -> bool {
    path.len() <= 2048
        && path.starts_with('/')
        && !path.starts_with("//")
        && !path.contains('\\')
        && !path.bytes().any(|byte| byte.is_ascii_control())
        && !path.split('/').any(|part| part == "." || part == "..")
        && Url::parse(&format!("https://local.invalid{path}"))
            .is_ok_and(|url| url.host_str() == Some("local.invalid"))
}

fn valid_s256_challenge(challenge: &str) -> bool {
    challenge.len() == 43
        && challenge
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || byte == b'-' || byte == b'_')
}

fn valid_app_state(state: &str) -> bool {
    !state.is_empty()
        && state.len() <= 256
        && state
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || b"-._~".contains(&byte))
}

fn valid_response_issuer(supported: bool, response: Option<&str>, expected: &str) -> bool {
    match (supported, response) {
        (true, Some(actual)) => actual == expected,
        (true, None) => false,
        (false, Some(actual)) => actual == expected,
        (false, None) => true,
    }
}

fn valid_audience_party(
    audience_count: usize,
    authorized_party: Option<&str>,
    client_id: &str,
) -> bool {
    audience_count > 0
        && authorized_party.is_none_or(|party| party == client_id)
        && (audience_count == 1 || authorized_party.is_some())
}

fn normalize_origin(value: &str, allow_http: bool) -> Result<String, ProviderError> {
    let url = parse_https_url(value, allow_http)?;
    if url.path() != "/" || url.query().is_some() {
        return Err(ProviderError::Configuration);
    }
    Ok(url.origin().ascii_serialization())
}

fn validate_endpoint(
    endpoint: &Url,
    issuer: &IssuerUrl,
    allowed_origins: &[String],
    allow_http: bool,
) -> Result<(), ProviderError> {
    let endpoint = parse_https_url(endpoint.as_str(), allow_http)?;
    let issuer_url = Url::parse(issuer.as_str()).map_err(|_| ProviderError::Configuration)?;
    let endpoint_origin = endpoint.origin().ascii_serialization();
    let issuer_origin = issuer_url.origin().ascii_serialization();
    if endpoint_origin != issuer_origin && !allowed_origins.contains(&endpoint_origin) {
        return Err(ProviderError::Configuration);
    }
    Ok(())
}

async fn fetch_discovery_metadata(
    http: &SafeHttpClient,
    issuer: &IssuerUrl,
) -> Result<IssuerResponseMetadata, ProviderError> {
    let issuer_url = Url::parse(issuer.as_str()).map_err(|_| ProviderError::Configuration)?;
    let discovery = issuer_url
        .join(".well-known/openid-configuration")
        .map_err(|_| ProviderError::Configuration)?;
    let response = http.get(discovery).await?;
    let metadata: IssuerResponseMetadata =
        serde_json::from_slice(&response).map_err(|_| ProviderError::Unavailable)?;
    if metadata.issuer != issuer.as_str() {
        return Err(ProviderError::Configuration);
    }
    Ok(metadata)
}

fn validate_raw_endpoints(
    metadata: &IssuerResponseMetadata,
    issuer: &IssuerUrl,
    allowed_origins: &[String],
    allow_http: bool,
) -> Result<(), ProviderError> {
    let authorization =
        Url::parse(&metadata.authorization_endpoint).map_err(|_| ProviderError::Configuration)?;
    let jwks = Url::parse(&metadata.jwks_uri).map_err(|_| ProviderError::Configuration)?;
    validate_endpoint(&authorization, issuer, allowed_origins, allow_http)?;
    validate_endpoint(&jwks, issuer, allowed_origins, allow_http)?;
    let token = metadata
        .token_endpoint
        .as_deref()
        .ok_or(ProviderError::Configuration)?;
    let token = Url::parse(token).map_err(|_| ProviderError::Configuration)?;
    validate_endpoint(&token, issuer, allowed_origins, allow_http)
}

struct SafeHttpClient {
    client: reqwest::Client,
}

impl SafeHttpClient {
    fn new(allow_http: bool) -> Result<Self, ProviderError> {
        let mut builder = reqwest::Client::builder()
            .redirect(Policy::none())
            .timeout(HTTP_TIMEOUT)
            .https_only(!allow_http);
        if allow_http {
            // Plain-HTTP mode (tests only) never negotiates TLS, so do not
            // require system CA certificates, which a build sandbox lacks.
            builder = builder.tls_certs_only(Vec::new());
        }
        let client = builder.build().map_err(|_| ProviderError::Unavailable)?;
        Ok(Self { client })
    }

    async fn get(&self, url: Url) -> Result<Vec<u8>, ProviderError> {
        let response = self
            .client
            .get(url)
            .send()
            .await
            .map_err(|_| ProviderError::Unavailable)?;
        read_bounded(response).await
    }
}

impl<'c> AsyncHttpClient<'c> for SafeHttpClient {
    type Error = io::Error;
    type Future = Pin<Box<dyn Future<Output = Result<HttpResponse, Self::Error>> + Send + 'c>>;

    fn call(&'c self, request: HttpRequest) -> Self::Future {
        Box::pin(async move {
            let request = reqwest::Request::try_from(request)
                .map_err(|_| io::Error::other("invalid HTTP request"))?;
            let response = self
                .client
                .execute(request)
                .await
                .map_err(|_| io::Error::other("provider request failed"))?;
            let status = response.status();
            let version = response.version();
            let headers = response.headers().clone();
            let body = read_bounded(response)
                .await
                .map_err(|_| io::Error::other("provider response rejected"))?;
            let mut builder = axum::http::Response::builder()
                .status(status)
                .version(version);
            for (name, value) in &headers {
                builder = builder.header(name, value);
            }
            builder
                .body(body)
                .map_err(|_| io::Error::other("invalid provider response"))
        })
    }
}

async fn read_bounded(response: reqwest::Response) -> Result<Vec<u8>, ProviderError> {
    if response
        .content_length()
        .is_some_and(|size| size > MAX_RESPONSE_BYTES as u64)
    {
        return Err(ProviderError::Unavailable);
    }
    let mut response = response;
    let mut output = Vec::new();
    while let Some(chunk) = response
        .chunk()
        .await
        .map_err(|_| ProviderError::Unavailable)?
    {
        if output.len().saturating_add(chunk.len()) > MAX_RESPONSE_BYTES {
            return Err(ProviderError::Unavailable);
        }
        output.extend_from_slice(&chunk);
    }
    Ok(output)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::HashMap;
    use std::sync::atomic::{AtomicUsize, Ordering};
    use std::sync::{Arc, Mutex as StdMutex};

    use axum::extract::{Form, State};
    use axum::routing::{get, post};
    use axum::{Json, Router};
    use base64::Engine;
    use base64::engine::general_purpose::URL_SAFE_NO_PAD;
    use chrono::DateTime;
    use openidconnect::core::{CoreIdTokenClaims, CoreJsonWebKeySet, CoreRsaPrivateSigningKey};
    use openidconnect::{
        Audience, EmptyAdditionalClaims, EndUserEmail, JsonWebKeyId, PrivateSigningKey,
        StandardClaims, SubjectIdentifier,
    };
    use rsa::RsaPrivateKey;
    use rsa::pkcs1::{EncodeRsaPrivateKey, LineEnding};
    use serde_json::Value;
    use sha2::{Digest, Sha256};
    use tokio::net::TcpListener;
    use tokio::sync::Mutex as AsyncMutex;

    fn issuer() -> IssuerUrl {
        IssuerUrl::new("https://auth.example.test/".to_owned()).unwrap()
    }

    struct MockIssuerState {
        issuer: String,
        jwks: Value,
        token: AsyncMutex<Option<String>>,
        challenge: StdMutex<Option<String>>,
        jwks_calls: AtomicUsize,
        token_calls: AtomicUsize,
    }

    struct MockIssuer {
        base_url: String,
        state: Arc<MockIssuerState>,
        task: tokio::task::JoinHandle<()>,
    }

    struct MockClaims {
        issuer: String,
        nonce: String,
        audiences: Vec<String>,
        authorized_party: Option<String>,
        expiration: DateTime<Utc>,
        issue_time: DateTime<Utc>,
        auth_time: Option<DateTime<Utc>>,
        algorithm: CoreJwsSigningAlgorithm,
        signing_key_id: String,
    }

    impl MockIssuer {
        async fn start(jwks_value: CoreJsonWebKeySet) -> Self {
            let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
            let address = listener.local_addr().unwrap();
            let base_url = format!("http://{address}/");
            let issuer = base_url.clone();
            let state = Arc::new(MockIssuerState {
                issuer: issuer.clone(),
                jwks: serde_json::to_value(&jwks_value).unwrap(),
                token: AsyncMutex::new(None),
                challenge: StdMutex::new(None),
                jwks_calls: AtomicUsize::new(0),
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
                base_url,
                state,
                task,
            }
        }

        fn provider_config(&self) -> ProviderConfig {
            ProviderConfig {
                id: "authelia".into(),
                issuer: self.base_url.clone(),
                client_id: "oidc-test-client".into(),
                client_secret: Zeroizing::new("test-only-client-secret".into()),
                callback_uri: "https://app.example.test/api/v2/auth/oidc/callback/authelia".into(),
                allowed_endpoint_origins: Vec::new(),
                native_redirect_uris: Vec::new(),
                allow_http_for_tests: true,
            }
        }
    }

    impl Drop for MockIssuer {
        fn drop(&mut self) {
            self.task.abort();
        }
    }

    async fn metadata(State(state): State<Arc<MockIssuerState>>) -> Json<Value> {
        Json(serde_json::json!({
            "issuer": state.issuer,
            "authorization_endpoint": format!("{}authorize", state.issuer),
            "token_endpoint": format!("{}token", state.issuer),
            "jwks_uri": format!("{}jwks", state.issuer),
            "response_types_supported": ["code"],
            "subject_types_supported": ["public"],
            "id_token_signing_alg_values_supported": ["RS256", "PS256", "RS384"],
            "token_endpoint_auth_methods_supported": ["client_secret_basic"],
            "authorization_response_iss_parameter_supported": true,
        }))
    }

    async fn jwks(State(state): State<Arc<MockIssuerState>>) -> Json<Value> {
        state.jwks_calls.fetch_add(1, Ordering::Relaxed);
        Json(state.jwks.clone())
    }

    async fn token(
        State(state): State<Arc<MockIssuerState>>,
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
            "id_token": id_token,
        })))
    }

    fn generate_signing_key() -> String {
        let private = RsaPrivateKey::new(&mut rand::rng(), 2048).unwrap();
        private.to_pkcs1_pem(LineEnding::LF).unwrap().to_string()
    }

    fn sign_claims(signing_key_pem: &str, claims: MockClaims) -> String {
        let standard_claims =
            StandardClaims::new(SubjectIdentifier::new("stable-provider-subject".to_owned()))
                .set_email(Some(EndUserEmail::new("person@example.test".to_owned())))
                .set_email_verified(Some(true));
        let id_token_claims = CoreIdTokenClaims::new(
            IssuerUrl::new(claims.issuer).unwrap(),
            claims.audiences.into_iter().map(Audience::new).collect(),
            claims.expiration,
            claims.issue_time,
            standard_claims,
            EmptyAdditionalClaims {},
        )
        .set_nonce(Some(Nonce::new(claims.nonce)))
        .set_auth_time(claims.auth_time)
        .set_authorized_party(claims.authorized_party.map(openidconnect::ClientId::new));
        let signing_key = CoreRsaPrivateSigningKey::from_pem(
            signing_key_pem,
            Some(JsonWebKeyId::new(claims.signing_key_id)),
        )
        .unwrap();
        CoreIdToken::new(id_token_claims, &signing_key, claims.algorithm, None, None)
            .unwrap()
            .to_string()
    }

    async fn call_mock_callback(
        signing_key_pem: &str,
        claims: MockClaims,
        max_auth_age_secs: Option<u64>,
        response_issuer_override: Option<&str>,
    ) -> (Result<String, ProviderError>, usize, usize, usize) {
        call_mock_callback_with_published_key(
            signing_key_pem,
            signing_key_pem,
            claims,
            max_auth_age_secs,
            response_issuer_override,
        )
        .await
    }

    async fn call_mock_callback_with_published_key(
        signing_key_pem: &str,
        published_key_pem: &str,
        mut claims: MockClaims,
        max_auth_age_secs: Option<u64>,
        response_issuer_override: Option<&str>,
    ) -> (Result<String, ProviderError>, usize, usize, usize) {
        let published_key = CoreRsaPrivateSigningKey::from_pem(
            published_key_pem,
            Some(JsonWebKeyId::new("mock-signing-key".into())),
        )
        .unwrap();
        let jwks = CoreJsonWebKeySet::new(vec![published_key.as_verification_key()]);
        let mock = MockIssuer::start(jwks).await;
        let provider = OidcProvider::discover(mock.provider_config())
            .await
            .unwrap();
        let purpose = match max_auth_age_secs {
            Some(_) => super::super::FlowPurpose::Reauth {
                owner_user_id: 42,
                action: super::super::ReauthAction::ChangePassword,
            },
            None => super::super::FlowPurpose::Login,
        };
        let binding = FlowBinding {
            provider_id: "authelia".into(),
            purpose,
            principal_hash: super::super::principal_hash(b"test-principal-token"),
            native_redirect_uri: None,
            return_path: Some("/app/account".into()),
            app_code_challenge: None,
            app_state: None,
            max_auth_age_secs,
        };
        if claims.issuer.is_empty() {
            claims.issuer.clone_from(&mock.base_url);
        }
        let authorization_url = provider.start(binding.clone()).unwrap();
        let query: HashMap<String, String> = authorization_url.query_pairs().into_owned().collect();
        let state = query.get("state").unwrap().clone();
        let nonce = query.get("nonce").unwrap().clone();
        let challenge = query.get("code_challenge").unwrap().clone();
        *mock.state.challenge.lock().unwrap() = Some(challenge);
        if claims.nonce.is_empty() {
            claims.nonce = nonce;
        }
        *mock.state.token.lock().await = Some(sign_claims(signing_key_pem, claims));
        let response_issuer = response_issuer_override.unwrap_or(&mock.base_url);
        let result = provider
            .callback("single-use-code", &state, Some(response_issuer), &binding)
            .await
            .map(|(_, identity)| identity.subject().to_owned());
        (
            result,
            mock.state.token_calls.load(Ordering::Relaxed),
            mock.state.jwks_calls.load(Ordering::Relaxed),
            provider.flows.len().unwrap(),
        )
    }

    fn claims(issuer: String, now: DateTime<Utc>) -> MockClaims {
        MockClaims {
            issuer,
            nonce: String::new(),
            audiences: vec!["oidc-test-client".into()],
            authorized_party: None,
            expiration: now + chrono::TimeDelta::minutes(5),
            issue_time: now,
            auth_time: Some(now - chrono::TimeDelta::seconds(30)),
            algorithm: CoreJwsSigningAlgorithm::RsaSsaPkcs1V15Sha256,
            signing_key_id: "mock-signing-key".into(),
        }
    }

    #[tokio::test]
    async fn signed_id_tokens_are_accepted_only_when_claims_and_flow_match() {
        let signing_key_pem = generate_signing_key();
        let now = Utc::now();

        let (accepted, calls, _, _) =
            call_mock_callback(&signing_key_pem, claims(String::new(), now), None, None).await;
        assert_eq!(accepted.unwrap(), "stable-provider-subject");
        assert_eq!(calls, 1);

        let other_signing_key = generate_signing_key();
        let bad_signature = call_mock_callback_with_published_key(
            &signing_key_pem,
            &other_signing_key,
            claims(String::new(), now),
            None,
            None,
        )
        .await;
        assert!(matches!(bad_signature.0, Err(ProviderError::InvalidToken)));
        assert_eq!(bad_signature.1, 1);

        let unknown_key_id = call_mock_callback(
            &signing_key_pem,
            MockClaims {
                signing_key_id: "rotated-signing-key".into(),
                ..claims(String::new(), now)
            },
            None,
            None,
        )
        .await;
        assert!(matches!(unknown_key_id.0, Err(ProviderError::InvalidToken)));
        assert_eq!(unknown_key_id.2, 2);

        let wrong_nonce = call_mock_callback(
            &signing_key_pem,
            MockClaims {
                nonce: "wrong-nonce".into(),
                ..claims(String::new(), now)
            },
            None,
            None,
        )
        .await
        .0;
        assert!(matches!(wrong_nonce, Err(ProviderError::InvalidToken)));

        let wrong_issuer = call_mock_callback(
            &signing_key_pem,
            claims("https://attacker.example.test/".into(), now),
            None,
            None,
        )
        .await
        .0;
        assert!(matches!(wrong_issuer, Err(ProviderError::InvalidToken)));

        let wrong_audience = call_mock_callback(
            &signing_key_pem,
            MockClaims {
                audiences: vec!["another-client".into()],
                ..claims(String::new(), now)
            },
            None,
            None,
        )
        .await
        .0;
        assert!(matches!(wrong_audience, Err(ProviderError::InvalidToken)));

        let wrong_authorized_party = call_mock_callback(
            &signing_key_pem,
            MockClaims {
                audiences: vec!["oidc-test-client".into(), "other-audience".into()],
                authorized_party: Some("another-client".into()),
                ..claims(String::new(), now)
            },
            None,
            None,
        )
        .await
        .0;
        assert!(matches!(
            wrong_authorized_party,
            Err(ProviderError::InvalidToken)
        ));

        let missing_authorized_party = call_mock_callback(
            &signing_key_pem,
            MockClaims {
                audiences: vec!["oidc-test-client".into(), "other-audience".into()],
                ..claims(String::new(), now)
            },
            None,
            None,
        )
        .await
        .0;
        assert!(matches!(
            missing_authorized_party,
            Err(ProviderError::InvalidToken)
        ));

        let expired = call_mock_callback(
            &signing_key_pem,
            MockClaims {
                expiration: now - chrono::TimeDelta::seconds(120),
                ..claims(String::new(), now)
            },
            None,
            None,
        )
        .await
        .0;
        assert!(matches!(expired, Err(ProviderError::InvalidToken)));

        let future_issued = call_mock_callback(
            &signing_key_pem,
            MockClaims {
                issue_time: now + chrono::TimeDelta::seconds(120),
                ..claims(String::new(), now)
            },
            None,
            None,
        )
        .await
        .0;
        assert!(matches!(future_issued, Err(ProviderError::InvalidToken)));

        let unsupported_algorithm = call_mock_callback(
            &signing_key_pem,
            MockClaims {
                algorithm: CoreJwsSigningAlgorithm::RsaSsaPkcs1V15Sha384,
                ..claims(String::new(), now)
            },
            None,
            None,
        )
        .await
        .0;
        assert!(matches!(
            unsupported_algorithm,
            Err(ProviderError::InvalidToken)
        ));

        let stale_auth_time = call_mock_callback(
            &signing_key_pem,
            MockClaims {
                auth_time: Some(now - chrono::TimeDelta::seconds(700)),
                ..claims(String::new(), now)
            },
            Some(600),
            None,
        )
        .await
        .0;
        assert!(matches!(stale_auth_time, Err(ProviderError::InvalidToken)));

        let wrong_response_issuer = call_mock_callback(
            &signing_key_pem,
            claims(String::new(), now),
            None,
            Some("https://attacker.example.test/"),
        )
        .await;
        assert!(matches!(
            wrong_response_issuer.0,
            Err(ProviderError::InvalidCallback)
        ));
        assert_eq!(wrong_response_issuer.1, 0);
        assert_eq!(wrong_response_issuer.3, 0);
    }

    #[test]
    fn endpoint_validation_rejects_http_and_unlisted_origins() {
        let allowed = vec!["https://keys.example.test".to_owned()];
        assert!(
            validate_endpoint(
                &Url::parse("http://auth.example.test/jwks").unwrap(),
                &issuer(),
                &allowed,
                false,
            )
            .is_err()
        );
        assert!(
            validate_endpoint(
                &Url::parse("https://evil.example.test/jwks").unwrap(),
                &issuer(),
                &allowed,
                false,
            )
            .is_err()
        );
        assert!(
            validate_endpoint(
                &Url::parse("https://keys.example.test/jwks").unwrap(),
                &issuer(),
                &allowed,
                false,
            )
            .is_ok()
        );
    }

    #[test]
    fn callback_and_provider_urls_cannot_include_credentials_or_fragments() {
        assert!(parse_https_url("https://user@auth.example.test/callback", false).is_err());
        assert!(parse_https_url("https://auth.example.test/callback#fragment", false).is_err());
        assert!(parse_https_url("http://auth.example.test/callback", false).is_err());
        assert!(parse_https_url("http://127.0.0.1/callback", true).is_ok());
    }

    #[test]
    fn local_return_paths_cannot_escape_to_another_origin() {
        assert!(valid_local_path("/account"));
        assert!(!valid_local_path("//evil.example/path"));
        assert!(!valid_local_path("/../admin"));
        assert!(!valid_local_path("/\\\\evil.example"));
    }

    #[test]
    fn native_flow_requires_exact_allowlisted_uri_and_valid_correlation_proof() {
        assert!(valid_s256_challenge(&"a".repeat(43)));
        assert!(!valid_s256_challenge(&"a".repeat(42)));
        let invalid_challenge = "a".repeat(42) + "+";
        assert!(!valid_s256_challenge(&invalid_challenge));
        assert!(valid_app_state("android-state_1"));
        assert!(!valid_app_state("state with spaces"));
        assert_eq!(
            validate_native_redirect("stricknani://oidc-callback"),
            Ok("stricknani://oidc-callback".into())
        );
        assert!(validate_native_redirect("stricknani://untrusted").is_err());
    }

    #[test]
    fn authorization_response_issuer_is_enforced_when_advertised() {
        assert!(valid_response_issuer(
            true,
            Some("https://auth.example.test/"),
            "https://auth.example.test/"
        ));
        assert!(!valid_response_issuer(
            true,
            None,
            "https://auth.example.test/"
        ));
        assert!(!valid_response_issuer(
            true,
            Some("https://attacker.example.test/"),
            "https://auth.example.test/"
        ));
        assert!(valid_response_issuer(
            false,
            None,
            "https://auth.example.test/"
        ));
    }

    #[test]
    fn multiple_audiences_require_matching_authorized_party() {
        assert!(valid_audience_party(1, None, "client"));
        assert!(valid_audience_party(1, Some("client"), "client"));
        assert!(!valid_audience_party(1, Some("other"), "client"));
        assert!(!valid_audience_party(2, None, "client"));
        assert!(valid_audience_party(2, Some("client"), "client"));
        assert!(!valid_audience_party(2, Some("other"), "client"));
    }
}
