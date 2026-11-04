use std::collections::HashMap;
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use openidconnect::{CsrfToken, Nonce, PkceCodeChallenge};
use sha2::{Digest, Sha256};
use subtle::ConstantTimeEq;
use zeroize::Zeroizing;

use super::FlowPurpose;

const FLOW_TTL: Duration = Duration::from_secs(10 * 60);
const MAX_FLOWS: usize = 4096;

/// Privacy-preserving request context to which an authorization flow is bound.
#[derive(Clone, PartialEq, Eq)]
pub struct FlowBinding {
    pub provider_id: String,
    pub purpose: FlowPurpose,
    /// Hash of the initiating browser cookie or authenticated session token.
    pub principal_hash: [u8; 32],
    /// Exact allow-listed native HTTPS return URI, when this is a native flow.
    pub native_redirect_uri: Option<String>,
    /// Validated local path to return to after a browser flow.
    pub return_path: Option<String>,
    /// App-generated PKCE S256 challenge for the final first-party native code.
    pub app_code_challenge: Option<String>,
    /// App correlation value returned unchanged to the verified app link.
    pub app_state: Option<String>,
    /// Emit `prompt=login` and reject an old or missing `auth_time` claim.
    pub max_auth_age_secs: Option<u64>,
}

/// Hash a browser-session token or native principal token before retaining it
/// in a pending flow record.
pub fn principal_hash(principal: &[u8]) -> [u8; 32] {
    digest(principal)
}

struct PendingFlow {
    binding: FlowBinding,
    state: String,
    nonce: Zeroizing<String>,
    provider_verifier: Zeroizing<String>,
    created: Instant,
}

/// A bounded in-memory store. Pending authorization is abandoned on restart.
#[derive(Clone, Default)]
pub(crate) struct FlowStore {
    flows: Arc<Mutex<HashMap<[u8; 32], PendingFlow>>>,
}

/// The provider-facing values needed to redirect the browser.
pub(crate) struct StartedFlow {
    pub(crate) state: String,
    pub(crate) nonce: Zeroizing<String>,
    pub(crate) provider_verifier: Zeroizing<String>,
}

impl FlowStore {
    pub fn new() -> Self {
        Self::default()
    }

    /// Save a single-use flow and return its fresh unpredictable OIDC values.
    pub fn insert(&self, binding: FlowBinding) -> Result<StartedFlow, FlowError> {
        let csrf = CsrfToken::new_random();
        let nonce = Nonce::new_random();
        let (_, verifier) = PkceCodeChallenge::new_random_sha256();
        let state = csrf.secret().to_owned();
        let nonce_value = Zeroizing::new(nonce.secret().to_owned());
        let verifier_value = Zeroizing::new(verifier.secret().to_owned());
        let key = digest(state.as_bytes());
        let now = Instant::now();
        let mut flows = self.flows.lock().map_err(|_| FlowError::Unavailable)?;
        flows.retain(|_, flow| now.duration_since(flow.created) < FLOW_TTL);
        if flows.len() >= MAX_FLOWS {
            return Err(FlowError::Capacity);
        }
        flows.insert(
            key,
            PendingFlow {
                binding,
                state,
                nonce: nonce_value.clone(),
                provider_verifier: verifier_value.clone(),
                created: now,
            },
        );
        Ok(StartedFlow {
            state: csrf.secret().to_owned(),
            nonce: nonce_value,
            provider_verifier: verifier_value,
        })
    }

    /// Burn a provider flow when its HTTP binding is rejected before token exchange.
    pub fn discard(&self, state: &str) -> Result<(), FlowError> {
        if state.is_empty() || state.len() > 512 {
            return Err(FlowError::Invalid);
        }
        let key = digest(state.as_bytes());
        let mut flows = self.flows.lock().map_err(|_| FlowError::Unavailable)?;
        let flow = flows.remove(&key).ok_or(FlowError::Invalid)?;
        if Instant::now().duration_since(flow.created) >= FLOW_TTL {
            return Err(FlowError::Expired);
        }
        Ok(())
    }

    /// Consume by state before processing the response; a failed callback cannot replay it.
    pub fn consume(&self, state: &str, expected: &FlowBinding) -> Result<ConsumedFlow, FlowError> {
        if state.is_empty() || state.len() > 512 {
            return Err(FlowError::Invalid);
        }
        let key = digest(state.as_bytes());
        let mut flows = self.flows.lock().map_err(|_| FlowError::Unavailable)?;
        let flow = flows.remove(&key).ok_or(FlowError::Invalid)?;
        if Instant::now().duration_since(flow.created) >= FLOW_TTL {
            return Err(FlowError::Expired);
        }
        if flow.state != state || !same_binding(&flow.binding, expected) {
            return Err(FlowError::Invalid);
        }
        Ok(ConsumedFlow {
            binding: flow.binding,
            nonce: flow.nonce,
            provider_verifier: flow.provider_verifier,
        })
    }

    #[cfg(test)]
    pub fn len(&self) -> Result<usize, FlowError> {
        self.flows
            .lock()
            .map(|flows| flows.len())
            .map_err(|_| FlowError::Unavailable)
    }

    #[cfg(test)]
    pub fn is_empty(&self) -> Result<bool, FlowError> {
        self.flows
            .lock()
            .map(|flows| flows.is_empty())
            .map_err(|_| FlowError::Unavailable)
    }
}

pub(crate) struct ConsumedFlow {
    pub binding: FlowBinding,
    pub nonce: Zeroizing<String>,
    pub provider_verifier: Zeroizing<String>,
}

#[derive(Clone, Copy, Debug, thiserror::Error, PartialEq, Eq)]
pub(crate) enum FlowError {
    #[error("authorization flow is invalid")]
    Invalid,
    #[error("authorization flow has expired")]
    Expired,
    #[error("authorization flow capacity is full")]
    Capacity,
    #[error("authorization flow store is unavailable")]
    Unavailable,
}

fn digest(value: &[u8]) -> [u8; 32] {
    Sha256::digest(value).into()
}

fn same_binding(left: &FlowBinding, right: &FlowBinding) -> bool {
    left.provider_id == right.provider_id
        && left.purpose == right.purpose
        && left.principal_hash.ct_eq(&right.principal_hash).unwrap_u8() == 1
        && left.native_redirect_uri == right.native_redirect_uri
        && left.return_path == right.return_path
        && left.app_code_challenge == right.app_code_challenge
        && left.app_state == right.app_state
        && left.max_auth_age_secs == right.max_auth_age_secs
}

#[cfg(test)]
mod tests {
    use super::*;

    fn binding() -> FlowBinding {
        FlowBinding {
            provider_id: "authelia".into(),
            purpose: FlowPurpose::Login,
            principal_hash: [7; 32],
            native_redirect_uri: None,
            return_path: Some("/account".into()),
            app_code_challenge: None,
            app_state: None,
            max_auth_age_secs: None,
        }
    }

    #[test]
    fn state_is_one_use_and_bound_to_the_initiator() {
        let store = FlowStore::new();
        let started = store.insert(binding()).unwrap();
        let state = started.state;
        let mut wrong = binding();
        wrong.principal_hash = [8; 32];
        assert!(matches!(
            store.consume(&state, &wrong),
            Err(FlowError::Invalid)
        ));
        assert_eq!(store.len().unwrap(), 0);
        assert!(matches!(
            store.consume(&state, &binding()),
            Err(FlowError::Invalid)
        ));
    }

    #[test]
    fn state_cannot_be_replayed_for_another_provider_or_purpose() {
        let store = FlowStore::new();
        let started = store.insert(binding()).unwrap();
        let mut wrong = binding();
        wrong.provider_id = "other".into();
        assert!(matches!(
            store.consume(&started.state, &wrong),
            Err(FlowError::Invalid)
        ));
        assert!(store.is_empty().unwrap());
    }

    #[test]
    fn discarded_state_cannot_be_replayed() {
        let store = FlowStore::new();
        let started = store.insert(binding()).unwrap();
        store.discard(&started.state).unwrap();
        assert!(matches!(
            store.consume(&started.state, &binding()),
            Err(FlowError::Invalid)
        ));
    }

    #[test]
    fn state_must_be_bounded() {
        let store = FlowStore::new();
        assert!(matches!(
            store.consume(&"s".repeat(513), &binding()),
            Err(FlowError::Invalid)
        ));
    }

    #[test]
    fn expired_state_is_removed_before_acceptance() {
        let store = FlowStore::new();
        let started = store.insert(binding()).unwrap();
        let key = digest(started.state.as_bytes());
        store.flows.lock().unwrap().get_mut(&key).unwrap().created = Instant::now() - FLOW_TTL;
        assert!(matches!(
            store.consume(&started.state, &binding()),
            Err(FlowError::Expired)
        ));
        assert!(store.is_empty().unwrap());
    }

    #[test]
    fn store_refuses_more_than_its_fixed_capacity() {
        let store = FlowStore::new();
        for _ in 0..MAX_FLOWS {
            store.insert(binding()).unwrap();
        }
        assert!(matches!(store.insert(binding()), Err(FlowError::Capacity)));
        assert_eq!(store.len().unwrap(), MAX_FLOWS);
    }
}
