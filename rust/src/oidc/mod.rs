//! OIDC protocol primitives. This module validates provider responses and
//! returns identities; application code owns account lookup, linking, and
//! session or token issuance.

mod flow;
mod provider;
mod types;

pub use flow::{FlowBinding, principal_hash};
pub use provider::{OidcProvider, ProviderConfig, ProviderError};
pub use types::{FlowPurpose, ReauthAction, ValidatedIdentity};
