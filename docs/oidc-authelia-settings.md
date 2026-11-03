# Authelia OIDC client settings

This records the Authelia client registration contract for Stricknani's
preview and production OIDC relying parties. It is documentation only: no
provider client or secret has been created or enabled. Verify the deployed
Authelia version and validate its exact schema before applying these settings.

Authelia acts as the OpenID Connect provider. Stricknani is the confidential
relying party and brokers the provider callback on its server. The server
callback is therefore the Authelia `redirect_uri`; the Android app's verified
HTTPS App Link or explicitly allowlisted `stricknani://oidc-callback` fallback
is only Stricknani's final first-party return after the server
has exchanged and validated the provider authorization code. Do not register
the app link or a custom app scheme as an Authelia redirect URI.

The client options and allowed values below follow Authelia's [OpenID Connect
client configuration](https://www.authelia.com/configuration/identity-providers/openid-connect/clients/).

## Preview client

Register a separate confidential client for preview, with only the preview
server callback:

```yaml
identity_providers:
  oidc:
    clients:
      - client_id: "stricknani-preview"
        client_name: "Stricknani Preview"
        client_secret: "<PREVIEW_CLIENT_SECRET_DIGEST>"
        public: false
        redirect_uris:
          - "https://rust.anika.blue/api/v2/auth/oidc/callback/authelia"
        scopes:
          - openid
          - email
          - profile
        grant_types:
          - authorization_code
        response_types:
          - code
        consent_mode: explicit
        require_pkce: true
        pkce_challenge_method: S256
        token_endpoint_auth_method: client_secret_basic
        id_token_signed_response_alg: RS256
```

## Production client

Register a distinct confidential client for production, with only the
production server callback:

```yaml
identity_providers:
  oidc:
    clients:
      - client_id: "stricknani"
        client_name: "Stricknani"
        client_secret: "<PRODUCTION_CLIENT_SECRET_DIGEST>"
        public: false
        redirect_uris:
          - "https://wolle.anika.blue/api/v2/auth/oidc/callback/authelia"
        scopes:
          - openid
          - email
          - profile
        grant_types:
          - authorization_code
        response_types:
          - code
        consent_mode: explicit
        require_pkce: true
        pkce_challenge_method: S256
        token_endpoint_auth_method: client_secret_basic
        id_token_signed_response_alg: RS256
```

The remaining angle-bracket strings are documentation placeholders, not literal
configuration values. Supply the client ID at runtime to Stricknani. Generate
an independent high-entropy secret for each environment. The relying party
must receive the original secret through its runtime secret-file interface;
Authelia's `client_secret` field takes the Authelia-supported password digest
of that same secret, not the original plaintext. Keep the source secret and
digest in their respective secret-management paths and never place either in
public Nix files, generated store paths, command arguments, logs, or this
document. The official client example demonstrates the digest representation;
consult the deployed-version docs for its supported digest generation method.

The callback URIs are case-sensitive exact matches. Do not add wildcards,
app-link URLs, custom schemes, or the other environment's callback to either
client. Keep refresh-token grant support absent unless the server contract is
changed to require it.

For step-up, the server requests bounded `max_age` and checks the returned
`auth_time`. These are protocol request/ID-token fields, not Authelia client
YAML settings. Explicit consent may prompt on each authorization; review that
behavior during rehearsal rather than silently broadening consent.

## Authorization policy and account linking

Authelia's client `authorization_policy` option does not provide an `inherit`
value; its documented default is `two_factor`. Before registration, inspect the
currently deployed provider's policy and decide explicitly which policy the
client should use. Do not invent a new per-client MFA requirement or policy as
part of this integration. Record and review the selected existing policy
before applying the client entry.

Stricknani currently has no trusted local email-verification state. Keep
the Stricknani provider's `trust_email_verified` setting explicitly false,
keep automatic email linking disabled, and keep automatic account creation
disabled. Existing users must link from their authenticated Stricknani account
through the reviewed browser or Android flow. Email equality alone must never
select or attach a local account.

## Review and rehearsal checklist

- Confirm the deployed Authelia version and use its matching documentation or
  schema validation. This document does not assert which version is deployed.
- Confirm both registrations are confidential clients (`public: false`) and
  that `client_secret` contains the digest while Stricknani gets the matching
  original value from its runtime secret file.
- Verify each case-sensitive callback exactly against the server route and
  confirm neither registration contains the other environment's callback.
- Confirm only `authorization_code` and `code` are enabled, PKCE is enforced
  with `S256`, token authentication is `client_secret_basic`, and consent is
  explicit. Ensure the provider returns the `openid`, `email`, and `profile`
  scopes required by the server.
- Review the effective Authelia authorization policy and verify it preserves
  the currently intended behavior before enabling either client.
- Rehearse login and existing-user linking separately for web and Android.
  Verify an unknown identity cannot silently create or claim a local account;
  the native exact return URI receives only Stricknani's short-lived first-party
  return, never the provider authorization code or a PAT.
- Check proxy, Authelia, and application logs redact callback query strings,
  authorization codes, `state`, `nonce`, PKCE verifiers, tokens, client
  credentials, and provider error descriptions. Confirm log access and
  retention are appropriate.
- Document how to rotate each environment's secret: provision a new secret
  and digest through the secret manager, update both ends in a coordinated
  window, verify login and linking, then retire the old value. Never print the
  secret or digest while validating or comparing them.

These entries are not enabled or configured by this document. Production
cutover remains a separate reviewed decision.
