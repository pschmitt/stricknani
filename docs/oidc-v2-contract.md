# OIDC API v2 contract — 2026-11-03

Contract for parallel server and client implementation under T124. This does
not claim the endpoints are deployed. Codex owns server/security integration;
Claude owns web/Android and the private Authelia configuration. Existing v1
and password-login contracts remain available. Authelia login and linking on
both clients are production-cutover gates.

All paths below start with `/api/v2`. JSON uses the normal `data`/`error`
envelopes and `Cache-Control: no-store`. Provider ID is `authelia`. Callback
origins, client IDs and secrets are configured separately for preview and
production; native return URIs are allowlisted by Stricknani, not registered
as confidential-client redirects at Authelia.

## Discovery and linked accounts

`GET /meta` adds:

```json
{"data":{"auth":{"password":true,"oidc":[{"id":"authelia","name":"Authelia"}]}}}
```

This is an additive fragment: retain version, build ID, user and role fields,
including the user's nullable avatar URL. OIDC is an empty array when disabled;
never advertise a provider whose server flow is not configured. Password login
remains available globally. `auth.password` describes global availability; the
identity list's `meta.password_enabled` describes this local user and can be
false. Password reauthentication is unavailable for that user when false.

`GET /account/identities` accepts a valid browser session or strict API-purpose
PAT and returns:

```json
{"data":[{"id":1,"provider":"authelia","subject_hint":"id:abcd1234","email":null,"linked_at":"2026-11-03T12:00:00.000000Z","can_unlink":true,"current_credential":false}],"meta":{"password_enabled":true}}
```

The hint is a non-reversible display identifier, not the raw subject. Email is
nullable display information and is not an identity key. `can_unlink` is a UI
hint; the server rechecks the last usable method inside the write transaction.
`current_credential` is true only when this identity issued the caller's
current OIDC credential; use it to warn before unlinking that identity.
This endpoint does not make the existing cookie-only `/account` API PAT-capable.

## Login

`POST /auth/oidc/start`:

| Client | JSON body | Request authentication |
| --- | --- | --- |
| Browser | `{provider, client: "browser", return_to?: "/app/..."}` | Existing anonymous CSRF bootstrap cookie/token, same-origin Origin |
| Android | `{provider, client: "android", redirect_uri, app_state, code_challenge, code_challenge_method: "S256"}` | No ambient browser session; app creates fresh state/verifier before calling |

Both return `200 {data: {authorization_url, expires_at}}`; expiry is RFC3339.
The URL points directly to Authelia. Browser start sets its Secure, HttpOnly,
SameSite=Lax flow-binding cookie before navigation. The server generates its
own state, nonce and provider PKCE verifier independently from Android's proof.
Only same-origin `/app/` return paths are accepted; default is `/app/`.

`GET /auth/oidc/callback/authelia` is the registered provider redirect. Validate
flow/provider binding, nonce, signature, issuer, audience/azp, times and PKCE
before local account lookup. Match local identity only by exact issuer/subject.
Disabled local accounts cannot log in. Unknown identities receive the neutral
unlinked-account result; creation and email auto-linking remain off by default.

Browser success mints the normal session cookie, replaces the initiating
session where applicable and redirects to the stored clean return path. Login
failure redirects to `/app/login?oidc_error=unlinked` or
`/app/login?oidc_error=failed`, with no provider error text or account email.
Callback responses use `Referrer-Policy: no-referrer` and no-store; proxy and
application logs redact callback query strings.

Android success redirects to the exact configured app URI with `code`,
`state` (the original `app_state`) and `flow=login`. Failure includes the
stored flow kind (`login`, `link`, or `reauth`) and uses the same
pending state with `error=oidc_failed`; invalid/unrecognized flows receive a
same-origin error rather than an arbitrary redirect. The app rejects callbacks
without its own pending in-memory state/verifier before doing any exchange.

`POST /auth/oidc/exchange` accepts
`{code, code_verifier, redirect_uri, token_name?}` and returns
`200 {data: {token, expires_at, auth_origin: "oidc"}}`. It accepts login-purpose
grants only and sets no browser cookie. `expires_at` must be valid RFC3339;
clients reject a missing or malformed OIDC expiry rather than treating it as
non-expiring, while preserving offline work. With multiple providers, retain
the account's last successful provider or offer selection; do not silently
choose an unrelated provider from the list. State/grants are single-use; codes
expire after at most 60 seconds and are consumed on the first exchange attempt,
including invalid proofs. S256 verifier length is 43–128 characters. Redirect
comparison is exact. For the self-hosted Android client, the explicitly
configured fallback URI is `stricknani://oidc-callback`; allowlist that complete
URI, not the whole scheme. App-side S256 PKCE and pending state are required
because a custom URI scheme does not establish exclusive app ownership.
Verified HTTPS App Links remain preferred where the domain can be configured
in the manifest and verified by the server. Never put a PAT or provider token
in a URL.

OIDC PATs have a provider/identity marker and finite expiry (initial policy:
7 days, server configurable downward). Account disable/reset and identity unlink
revoke them. Expiry requires reauthentication; Android preserves offline data
and queued writes while requesting it. IdP-side disablement is not instant
local revocation without a notification/revalidation path; do not claim otherwise.

## Recent authentication

Browser-sensitive operations require a valid session with server-stored
`reauth_at` no older than 10 minutes. PAT-sensitive operations require
`X-Reauth-Token`, a one-use proof bound to user, authenticating PAT, action and
target, also expiring within 10 minutes. Neither trusts client timestamps.

`POST /account/reauth` accepts `{password, action, target?}` under browser
session plus Origin/CSRF, or explicit PAT. Valid password verification returns:

- Browser: `{data: {reauthenticated: true, expires_at}}` and updates only its
  current session's server stamp.
- PAT: `{data: {reauth_token, expires_at}}`; retain the proof in memory and send
  it only in `X-Reauth-Token` for the matching operation.

Actions are `link_identity`, `unlink_identity`, `admin_unlink_identity`,
`change_password`, `create_api_token` and `revoke_api_token`. Targets contain
only the applicable `provider`, `identity_id` and administrative `user_id`.
Exact target shapes are `{provider}` for `link_identity`, `{identity_id}` for
`unlink_identity`, `{user_id, identity_id}` for `admin_unlink_identity`, and
`{token_id}` for `revoke_api_token`. Password changes and token creation take
no target. Unrelated action/target combinations are rejected.

OIDC reauthentication uses `POST /account/reauth/oidc/start` with
`{provider, action, target?, client, return_to?, ...native start fields when applicable}`.
Return the same `200 {data: {authorization_url, expires_at}}` start envelope.
It requires the current authenticated principal (including a current PAT for
native requests), plus CSRF for cookies. Administrative step-up authenticates
the administrator's own linked identity, with the target account separately
bound to the authorized action. Request
fresh provider authentication and verify `auth_time`; the returned identity must
already belong to that same local user. Browser callback updates that session's
stamp and returns to the validated same-origin `/app/` `return_to` path
(default `/app/account`), appending `oidc=reauthed&action=<action>`. The action
is the stored allowlisted value, never arbitrary provider input. This also
supports admin provider step-up; retain the pending operation in client memory. Native callback uses `flow=reauth`;
`POST /account/reauth/oidc/exchange` with authenticated PAT and
`{code, code_verifier, redirect_uri}` returns the purpose-bound proof envelope.
Login, link and reauthentication grants cannot be exchanged at each other's
endpoints.

## Link, unlink and administrator management

`POST /account/identities/link` accepts
`{provider, client, return_to?, ...native start fields when applicable}`.
Authenticate the current user; require Origin/CSRF for cookies and recent-auth
evidence for both modes. Return the same start envelope. Check freshness at
start and completion, binding the flow to its initiating account and principal.
For PAT flows, validate the proof at start and consume it only with the actual
link transaction, not twice.

Browser callback links and returns to `/app/account?oidc=linked`. Link failure
returns `/app/account?oidc_error=failed`; an authenticated identity-conflict
result may use `oidc_error=already_linked`. Do not expose provider errors or
another local account identifier. Failed reauthentication likewise returns to
its validated local return path with `oidc_error=failed` and no success marker. Android gets
`flow=link`; `POST /account/identities/link/exchange` requires the initiating
PAT, its matching `X-Reauth-Token`, and `{code, code_verifier, redirect_uri}`.
Return `{data: {identity}}`, with the identity projection above; never issue a
replacement PAT from a link grant.

`DELETE /account/identities/{identity_id}` requires current-owner authentication
and recent-auth evidence; cookie mutations retain CSRF. Return 204. Refuse to
remove the final usable login method. Revoke tokens tied to that identity;
if this includes the current credential, the client signs in again on its next
protected request while retaining drafts/offline writes. Warn first when
`current_credential` is true. Recovery uses an enabled local password or another
still-linked identity; the removed identity cannot log in until explicitly
linked again from an authenticated account.

Administrators use `GET /admin/users/{user_id}/identities` (same list envelope)
and `DELETE /admin/users/{user_id}/identities/{identity_id}` (204). Both require
an active administrator; deletion additionally needs recent-auth proof bound
to that target and transaction-time role/last-method checks. An admin PAT alone
does not bypass step-up. Do not silently re-enable disabled users.

## Errors and invariants

| Status/code | Client behavior |
| --- | --- |
| `401 session_expired` | Current session/PAT is invalid; preserve drafts/queued writes and sign in again. |
| `401 invalid_credentials` | Password proof failed; show the generic error without treating it as a successful session refresh. |
| `403 reauth_required` | Obtain fresh proof for the requested action, then retry. |
| `403 oidc_identity_unlinked` | Friendly neutral login result; offer local sign-in/linking. |
| `404 oidc_disabled` / `not_found` | Provider unavailable by configuration, or an identity is not visible to the caller. |
| `409 identity_already_linked` | Authenticated link flow only; no other local account identifiers are disclosed. |
| `409 last_login_method` | Keep the identity and offer setup of another usable method. |
| `400 invalid_oidc_flow` | Expired/replayed/mismatched code, state, provider, nonce, proof or redirect; restart the flow. |
| `422 validation_error` | Render normal `error.fields` for malformed start/proof fields. |
| `429 rate_limited` | Respect `Retry-After`; retain pending local work. |
| `503 oidc_unavailable` | Provider/network/key validation unavailable; local password login remains available. |

Initial limits: 10-minute authorization flows, 60-second exchange grants,
10-minute step-up, 4096 global pending flows/grants and 5 pending grants per
account. Start/callback/exchange have separate bounded IP/flow/account limits;
token exchange also uses the existing per-account token issuance quota. Recheck
activity and identity binding in the same transaction as local token creation.
Link/unlink/login produce safe typed authentication audit events. No secret,
raw callback payload, verifier, nonce, authorization code or token is logged.

This HTTP contract is ready for Claude's client review. Protocol and storage
workers implement their separate modules; Codex integrates the HTTP adapters,
Nix options and security tests before enabling a provider.
