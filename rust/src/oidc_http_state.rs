//! Bounded, process-local state for API v2 OIDC authorization and exchange.
//!
//! This store deliberately keeps only hashes of callback state, browser flow
//! cookies, native grant codes, and client network addresses. Pending flows and
//! grants disappear when the process restarts.

use std::{
    collections::{HashMap, VecDeque},
    sync::{Arc, Mutex},
    time::{Duration, Instant},
};

use base64::{Engine, engine::general_purpose::URL_SAFE_NO_PAD};
use chrono::{DateTime, TimeDelta, Utc};
use rand::{RngCore, rngs::OsRng};
use sha2::{Digest, Sha256};
use subtle::ConstantTimeEq;
use url::Url;
use zeroize::{Zeroize, Zeroizing};

use crate::oidc::{
    FlowBinding, OidcProvider, ProviderConfig, ProviderError, ValidatedIdentity, principal_hash,
};

const FLOW_TTL: Duration = Duration::from_secs(10 * 60);
const GRANT_TTL: Duration = Duration::from_secs(60);
const MAX_PENDING: usize = 4096;
const MAX_GRANTS_PER_ACCOUNT: usize = 5;
const PAT_TTL: i64 = 7 * 24 * 60 * 60;
const PROVIDER_RETRY: Duration = Duration::from_secs(30);

/// Shared API v2 OIDC runtime. Disabled unless explicitly attached to `AppState`.
#[derive(Clone)]
pub struct OidcRuntime {
    provider_id: String,
    provider: Arc<tokio::sync::Mutex<ProviderCache>>,
    inner: Arc<Mutex<RuntimeState>>,
    pat_lifetime_seconds: i64,
}

struct ProviderCache {
    config: Option<ProviderConfig>,
    ready: Option<Arc<OidcProvider>>,
    last_attempt: Option<Instant>,
}

#[derive(Default)]
struct RuntimeState {
    flows: HashMap<[u8; 32], PendingFlow>,
    grants: HashMap<[u8; 32], PendingGrant>,
    limits: HashMap<([u8; 32], LimitKind), VecDeque<Instant>>,
}

struct PendingFlow {
    binding: FlowBinding,
    context: OidcFlowContext,
    transport: FlowTransport,
    created: Instant,
}

#[derive(Clone, Copy)]
pub(crate) enum FlowTransport {
    Browser,
    Native,
}

/// Single-use flow context retrieved by the provider callback state.
pub(crate) struct PendingHttpFlow {
    pub(crate) binding: FlowBinding,
    pub(crate) context: OidcFlowContext,
    pub(crate) transport: FlowTransport,
}

/// The typed target associated with a sensitive OIDC action.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct OidcFlowContext {
    pub action_target: Option<OidcActionTarget>,
    /// Database ID of the authenticated PAT that initiated native link/step-up.
    pub principal_token_id: Option<i64>,
    /// Digest of the one-use recent-auth proof reserved for this operation.
    pub recent_auth_proof_hash: Option<String>,
}

/// Target fields retained exactly across provider round trips.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct OidcActionTarget {
    pub provider: Option<String>,
    pub identity_id: Option<i64>,
    pub user_id: Option<i64>,
    pub token_id: Option<i64>,
}

struct PendingGrant {
    binding: FlowBinding,
    context: OidcFlowContext,
    identity: ValidatedIdentity,
    user_id: i64,
    expires_at: i64,
    created: Instant,
}

/// The native authorization result returned by a successful provider callback.
pub(crate) struct NativeGrant {
    pub(crate) code: Zeroizing<String>,
    pub(crate) app_state: String,
    pub(crate) redirect_uri: String,
    pub(crate) flow_kind: &'static str,
}

/// A grant removed from storage before proof validation, making every attempt final.
pub(crate) struct ConsumedGrant {
    pub(crate) binding: FlowBinding,
    pub(crate) context: OidcFlowContext,
    pub(crate) identity: ValidatedIdentity,
    pub(crate) user_id: i64,
    pub(crate) expires_at: i64,
}

#[derive(Clone, Copy, Hash, PartialEq, Eq)]
enum LimitKind {
    Start,
    Callback,
    Exchange,
}

#[derive(Clone, Copy)]
pub(crate) enum OidcEndpoint {
    Start,
    Callback,
    Exchange,
}

#[derive(Clone, Copy, Debug, thiserror::Error, PartialEq, Eq)]
pub enum OidcRuntimeError {
    #[error("OIDC flow is invalid or expired")]
    InvalidFlow,
    #[error("OIDC runtime is unavailable")]
    Unavailable,
    #[error("OIDC state capacity is full")]
    Capacity,
    #[error("OIDC request is rate limited")]
    RateLimited(u64),
    #[error("OIDC provider request failed")]
    Provider,
    #[error("OIDC runtime configuration is invalid")]
    Configuration,
}

impl From<ProviderError> for OidcRuntimeError {
    fn from(error: ProviderError) -> Self {
        match error {
            ProviderError::Configuration => Self::Configuration,
            ProviderError::InvalidFlow
            | ProviderError::InvalidCallback
            | ProviderError::InvalidToken => Self::InvalidFlow,
            ProviderError::Unavailable => Self::Provider,
        }
    }
}

impl OidcRuntime {
    /// Attach one configured OIDC provider to the API runtime.
    pub fn new(provider: Arc<OidcProvider>) -> Self {
        Self {
            provider_id: provider.id().to_owned(),
            provider: Arc::new(tokio::sync::Mutex::new(ProviderCache {
                config: None,
                ready: Some(provider),
                last_attempt: None,
            })),
            inner: Arc::default(),
            pat_lifetime_seconds: PAT_TTL,
        }
    }

    /// Configure the provider without network access; discovery is lazy and retryable.
    pub fn from_config(config: ProviderConfig) -> Result<Self, ProviderError> {
        config.validate()?;
        Ok(Self {
            provider_id: config.id.clone(),
            provider: Arc::new(tokio::sync::Mutex::new(ProviderCache {
                config: Some(config),
                ready: None,
                last_attempt: None,
            })),
            inner: Arc::default(),
            pat_lifetime_seconds: PAT_TTL,
        })
    }

    /// Get a ready provider, performing at most one serialized discovery per retry window.
    pub async fn provider(&self) -> Result<Arc<OidcProvider>, OidcRuntimeError> {
        let mut cache = self.provider.lock().await;
        let now = Instant::now();
        if let Some(ready) = &cache.ready {
            return Ok(Arc::clone(ready));
        }
        if cache
            .last_attempt
            .is_some_and(|last| now.duration_since(last) < PROVIDER_RETRY)
        {
            return Err(OidcRuntimeError::Provider);
        }
        let Some(config) = cache.config.clone() else {
            return cache
                .ready
                .as_ref()
                .map(Arc::clone)
                .ok_or(OidcRuntimeError::Provider);
        };
        cache.last_attempt = Some(now);
        match OidcProvider::discover(config).await {
            Ok(provider) => {
                let provider = Arc::new(provider);
                cache.ready = Some(Arc::clone(&provider));
                Ok(provider)
            }
            Err(_) => Err(OidcRuntimeError::Provider),
        }
    }

    /// Set a finite PAT lifetime no longer than the seven-day default.
    pub fn with_pat_lifetime_seconds(mut self, seconds: u64) -> Result<Self, ProviderError> {
        if seconds == 0 || seconds > PAT_TTL as u64 {
            return Err(ProviderError::Configuration);
        }
        self.pat_lifetime_seconds = seconds as i64;
        Ok(self)
    }

    pub(crate) fn pat_lifetime_seconds(&self) -> i64 {
        self.pat_lifetime_seconds
    }

    pub fn provider_id(&self) -> &str {
        &self.provider_id
    }

    /// Reserve per-IP capacity for one endpoint class. Only a hash of the IP is retained.
    pub(crate) fn allow_request(
        &self,
        endpoint: OidcEndpoint,
        ip: &str,
    ) -> Result<(), OidcRuntimeError> {
        let kind = match endpoint {
            OidcEndpoint::Start => LimitKind::Start,
            OidcEndpoint::Callback => LimitKind::Callback,
            OidcEndpoint::Exchange => LimitKind::Exchange,
        };
        let (limit, window) = match endpoint {
            OidcEndpoint::Start => (20, Duration::from_secs(60)),
            OidcEndpoint::Callback => (30, Duration::from_secs(60)),
            OidcEndpoint::Exchange => (10, Duration::from_secs(60)),
        };
        let key = (digest(ip.as_bytes()), kind);
        let now = Instant::now();
        let mut state = self
            .inner
            .lock()
            .map_err(|_| OidcRuntimeError::Unavailable)?;
        prune(&mut state, now);
        if let Some(attempts) = state.limits.get(&key)
            && attempts.len() >= limit
        {
            let retry = window
                .saturating_sub(now.duration_since(attempts[0]))
                .as_secs()
                .max(1);
            return Err(OidcRuntimeError::RateLimited(retry));
        }
        if !state.limits.contains_key(&key) && state.limits.len() >= MAX_PENDING {
            return Err(OidcRuntimeError::Capacity);
        }
        state.limits.entry(key).or_default().push_back(now);
        Ok(())
    }

    /// Burn a core provider flow without exchanging a code after HTTP-level rejection.
    pub(crate) async fn discard_flow(&self, provider_state: &str) {
        if let Some(provider) = self.provider.lock().await.ready.as_ref().map(Arc::clone) {
            let _ = provider.discard_flow(provider_state);
        }
    }

    /// Start a provider flow and retain the matching typed HTTP context.
    pub(crate) async fn start(
        &self,
        binding: FlowBinding,
        context: OidcFlowContext,
        transport: FlowTransport,
    ) -> Result<(Url, String, DateTime<Utc>), OidcRuntimeError> {
        if binding.provider_id != self.provider_id {
            return Err(OidcRuntimeError::InvalidFlow);
        }
        let provider = self.provider().await?;
        let mut state = self
            .inner
            .lock()
            .map_err(|_| OidcRuntimeError::Unavailable)?;
        let now = Instant::now();
        prune(&mut state, now);
        if pending_count(&state) >= MAX_PENDING {
            return Err(OidcRuntimeError::Capacity);
        }
        let authorization_url = provider.start(binding.clone())?;
        let provider_state = authorization_url
            .query_pairs()
            .find(|(key, _)| key == "state")
            .map(|(_, value)| value.into_owned())
            .filter(|value| !value.is_empty())
            .ok_or(OidcRuntimeError::Provider)?;
        let key = digest(provider_state.as_bytes());
        state.flows.insert(
            key,
            PendingFlow {
                binding,
                context,
                transport,
                created: now,
            },
        );
        Ok((
            authorization_url,
            provider_state,
            Utc::now() + TimeDelta::seconds(FLOW_TTL.as_secs() as i64),
        ))
    }

    /// Remove a callback flow before checking its cookie or exchanging its code.
    pub(crate) fn take_flow(
        &self,
        provider_state: &str,
    ) -> Result<PendingHttpFlow, OidcRuntimeError> {
        if provider_state.is_empty() || provider_state.len() > 512 {
            return Err(OidcRuntimeError::InvalidFlow);
        }
        let mut state = self
            .inner
            .lock()
            .map_err(|_| OidcRuntimeError::Unavailable)?;
        let flow = state
            .flows
            .remove(&digest(provider_state.as_bytes()))
            .ok_or(OidcRuntimeError::InvalidFlow)?;
        if flow.created.elapsed() >= FLOW_TTL {
            return Err(OidcRuntimeError::InvalidFlow);
        }
        Ok(PendingHttpFlow {
            binding: flow.binding,
            context: flow.context,
            transport: flow.transport,
        })
    }

    /// Complete the provider check. Cookie mismatch still burns the core flow state.
    pub(crate) async fn verify_callback(
        &self,
        flow: PendingHttpFlow,
        code: &str,
        state: &str,
        response_issuer: Option<&str>,
        browser_cookie: Option<&str>,
    ) -> Result<
        (
            FlowBinding,
            OidcFlowContext,
            ValidatedIdentity,
            FlowTransport,
        ),
        OidcRuntimeError,
    > {
        let PendingHttpFlow {
            binding,
            context,
            transport,
        } = flow;
        let cookie_matches = match transport {
            FlowTransport::Browser => browser_cookie.is_some_and(|cookie| {
                bool::from(principal_hash(cookie.as_bytes()).ct_eq(&binding.principal_hash))
            }),
            FlowTransport::Native => browser_cookie.is_none(),
        };
        if !cookie_matches {
            self.discard_flow(state).await;
            return Err(OidcRuntimeError::InvalidFlow);
        }
        let provider = match self.provider().await {
            Ok(provider) => provider,
            Err(error) => {
                self.discard_flow(state).await;
                return Err(error);
            }
        };
        let (binding, identity) = provider
            .callback(code, state, response_issuer, &binding)
            .await?;
        Ok((binding, context, identity, transport))
    }

    /// Store a validated native login grant and return its one-time opaque code.
    pub(crate) fn create_native_grant(
        &self,
        binding: FlowBinding,
        context: OidcFlowContext,
        identity: ValidatedIdentity,
        user_id: i64,
    ) -> Result<NativeGrant, OidcRuntimeError> {
        let redirect_uri = binding
            .native_redirect_uri
            .clone()
            .ok_or(OidcRuntimeError::InvalidFlow)?;
        let app_state = binding
            .app_state
            .clone()
            .ok_or(OidcRuntimeError::InvalidFlow)?;
        let flow_kind = flow_kind(binding.purpose);
        let mut random = [0_u8; 32];
        OsRng
            .try_fill_bytes(&mut random)
            .map_err(|_| OidcRuntimeError::Unavailable)?;
        let code = Zeroizing::new(URL_SAFE_NO_PAD.encode(random));
        random.zeroize();
        let now = Instant::now();
        let expires_at = Utc::now().timestamp() + GRANT_TTL.as_secs() as i64;
        let mut state = self
            .inner
            .lock()
            .map_err(|_| OidcRuntimeError::Unavailable)?;
        prune(&mut state, now);
        if pending_count(&state) >= MAX_PENDING {
            return Err(OidcRuntimeError::Capacity);
        }
        if state
            .grants
            .values()
            .filter(|grant| grant.user_id == user_id)
            .count()
            >= MAX_GRANTS_PER_ACCOUNT
        {
            return Err(OidcRuntimeError::Capacity);
        }
        state.grants.insert(
            digest(code.as_bytes()),
            PendingGrant {
                binding,
                context,
                identity,
                user_id,
                expires_at,
                created: now,
            },
        );
        Ok(NativeGrant {
            code,
            app_state,
            redirect_uri,
            flow_kind,
        })
    }

    /// Burn a native grant before the caller validates its verifier or redirect URI.
    pub(crate) fn consume_grant_for(
        &self,
        code: &str,
        expected: crate::oidc::FlowPurpose,
    ) -> Result<ConsumedGrant, OidcRuntimeError> {
        let grant = self.take_grant(code)?;
        if grant.binding.purpose != expected {
            return Err(OidcRuntimeError::InvalidFlow);
        }
        Ok(consumed_grant(grant))
    }

    /// Burn a reauthentication grant while allowing its stored action to select the adapter.
    pub(crate) fn consume_reauth_grant(
        &self,
        code: &str,
        owner_user_id: i64,
    ) -> Result<ConsumedGrant, OidcRuntimeError> {
        let grant = self.take_grant(code)?;
        if !matches!(
            grant.binding.purpose,
            crate::oidc::FlowPurpose::Reauth {
                owner_user_id: flow_owner,
                ..
            } if flow_owner == owner_user_id
        ) {
            return Err(OidcRuntimeError::InvalidFlow);
        }
        Ok(consumed_grant(grant))
    }

    fn take_grant(&self, code: &str) -> Result<PendingGrant, OidcRuntimeError> {
        if code.is_empty() || code.len() > 128 {
            return Err(OidcRuntimeError::InvalidFlow);
        }
        let mut state = self
            .inner
            .lock()
            .map_err(|_| OidcRuntimeError::Unavailable)?;
        let grant = state
            .grants
            .remove(&digest(code.as_bytes()))
            .ok_or(OidcRuntimeError::InvalidFlow)?;
        if grant.created.elapsed() >= GRANT_TTL || grant.expires_at <= Utc::now().timestamp() {
            return Err(OidcRuntimeError::InvalidFlow);
        }
        Ok(grant)
    }

    pub(crate) fn enabled_provider_ids(&self) -> Vec<String> {
        vec![self.provider_id.clone()]
    }
}

fn consumed_grant(grant: PendingGrant) -> ConsumedGrant {
    ConsumedGrant {
        binding: grant.binding,
        context: grant.context,
        identity: grant.identity,
        user_id: grant.user_id,
        expires_at: grant.expires_at,
    }
}

pub(crate) fn flow_kind(purpose: crate::oidc::FlowPurpose) -> &'static str {
    match purpose {
        crate::oidc::FlowPurpose::Login => "login",
        crate::oidc::FlowPurpose::Link { .. } => "link",
        crate::oidc::FlowPurpose::Reauth { .. } => "reauth",
    }
}

fn pending_count(state: &RuntimeState) -> usize {
    state.flows.len() + state.grants.len()
}

fn prune(state: &mut RuntimeState, now: Instant) {
    state
        .flows
        .retain(|_, flow| now.duration_since(flow.created) < FLOW_TTL);
    state
        .grants
        .retain(|_, grant| now.duration_since(grant.created) < GRANT_TTL);
    for attempts in state.limits.values_mut() {
        attempts.retain(|at| now.duration_since(*at) < Duration::from_secs(60));
    }
    state.limits.retain(|_, attempts| !attempts.is_empty());
}

fn digest(value: &[u8]) -> [u8; 32] {
    Sha256::digest(value).into()
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::oidc::{FlowPurpose, ReauthAction};

    fn config() -> ProviderConfig {
        ProviderConfig {
            id: "authelia".into(),
            issuer: "https://auth.example.test/".into(),
            client_id: "stricknani-test".into(),
            client_secret: Zeroizing::new("test-only-secret".into()),
            callback_uri: "https://app.example.test/api/v2/auth/oidc/callback/authelia".into(),
            allowed_endpoint_origins: Vec::new(),
            native_redirect_uris: vec!["stricknani://oidc-callback".into()],
            allow_http_for_tests: false,
        }
    }

    fn binding(purpose: FlowPurpose) -> FlowBinding {
        FlowBinding {
            provider_id: "authelia".into(),
            purpose,
            principal_hash: principal_hash(b"test-principal"),
            native_redirect_uri: Some("stricknani://oidc-callback".into()),
            return_path: None,
            app_code_challenge: Some("a".repeat(43)),
            app_state: Some("state_1".into()),
            max_auth_age_secs: None,
        }
    }

    fn identity() -> ValidatedIdentity {
        ValidatedIdentity::fixture(
            "authelia",
            "https://auth.example.test/",
            "subject-test",
            None,
            false,
            None,
        )
    }

    #[test]
    fn static_provider_config_is_validated_without_network_access() {
        let runtime = OidcRuntime::from_config(config()).unwrap();
        assert_eq!(runtime.provider_id(), "authelia");
        assert_eq!(runtime.pat_lifetime_seconds(), PAT_TTL);
        assert!(runtime.clone().with_pat_lifetime_seconds(0).is_err());
        assert!(
            runtime
                .clone()
                .with_pat_lifetime_seconds(PAT_TTL as u64 + 1)
                .is_err()
        );
        assert!(runtime.clone().with_pat_lifetime_seconds(86_400).is_ok());

        let mut invalid = config();
        invalid.callback_uri = "https://app.example.test/other".into();
        assert!(matches!(
            OidcRuntime::from_config(invalid),
            Err(ProviderError::Configuration)
        ));
    }

    #[test]
    fn wrong_purpose_and_replay_burn_a_native_grant() {
        let runtime = OidcRuntime::from_config(config()).unwrap();
        let grant = runtime
            .create_native_grant(
                binding(FlowPurpose::Login),
                OidcFlowContext::default(),
                identity(),
                7,
            )
            .unwrap();
        let code = grant.code.to_string();
        assert!(matches!(
            runtime.consume_grant_for(&code, FlowPurpose::Link { owner_user_id: 7 }),
            Err(OidcRuntimeError::InvalidFlow)
        ));
        assert!(matches!(
            runtime.consume_grant_for(&code, FlowPurpose::Login),
            Err(OidcRuntimeError::InvalidFlow)
        ));
    }

    #[test]
    fn reauth_grant_returns_stored_action_and_target_only_to_its_owner() {
        let runtime = OidcRuntime::from_config(config()).unwrap();
        let context = OidcFlowContext {
            action_target: Some(OidcActionTarget {
                provider: None,
                identity_id: None,
                user_id: Some(19),
                token_id: None,
            }),
            principal_token_id: Some(41),
            recent_auth_proof_hash: Some("f".repeat(64)),
        };
        let grant = runtime
            .create_native_grant(
                binding(FlowPurpose::Reauth {
                    owner_user_id: 7,
                    action: ReauthAction::AdminUnlinkIdentity,
                }),
                context,
                identity(),
                7,
            )
            .unwrap();
        let code = grant.code.to_string();
        assert!(matches!(
            runtime.consume_reauth_grant(&code, 8),
            Err(OidcRuntimeError::InvalidFlow)
        ));
        assert!(matches!(
            runtime.consume_reauth_grant(&code, 7),
            Err(OidcRuntimeError::InvalidFlow)
        ));

        let grant = runtime
            .create_native_grant(
                binding(FlowPurpose::Reauth {
                    owner_user_id: 7,
                    action: ReauthAction::AdminUnlinkIdentity,
                }),
                OidcFlowContext {
                    action_target: Some(OidcActionTarget {
                        provider: None,
                        identity_id: None,
                        user_id: Some(19),
                        token_id: None,
                    }),
                    principal_token_id: Some(41),
                    recent_auth_proof_hash: Some("f".repeat(64)),
                },
                identity(),
                7,
            )
            .unwrap();
        let consumed = runtime.consume_reauth_grant(&grant.code, 7).unwrap();
        assert_eq!(
            consumed.binding.purpose,
            FlowPurpose::Reauth {
                owner_user_id: 7,
                action: ReauthAction::AdminUnlinkIdentity,
            }
        );
        assert_eq!(consumed.context.principal_token_id, Some(41));
        assert_eq!(
            consumed
                .context
                .action_target
                .as_ref()
                .and_then(|target| target.user_id),
            Some(19)
        );
    }

    #[test]
    fn native_grants_are_capped_per_account_and_globally() {
        let runtime = OidcRuntime::from_config(config()).unwrap();
        for _ in 0..MAX_GRANTS_PER_ACCOUNT {
            runtime
                .create_native_grant(
                    binding(FlowPurpose::Login),
                    OidcFlowContext::default(),
                    identity(),
                    7,
                )
                .unwrap();
        }
        assert!(matches!(
            runtime.create_native_grant(
                binding(FlowPurpose::Login),
                OidcFlowContext::default(),
                identity(),
                7,
            ),
            Err(OidcRuntimeError::Capacity)
        ));
    }
}
