# Optional OIDC alongside local accounts

T124 is a plan, not an implemented capability. Codex owns server contracts,
identity storage, authorization and packaging review. Claude owns the web and
Android screens. Keep the current native-login, media and history checkpoints
moving independently; do not enable a provider or migrate production as part
of writing this plan.

## Product behavior

Local password login remains available. OIDC is disabled unless configured;
start with one enabled provider, while representing providers by stable IDs
so additional providers do not require a new identity model. Public metadata
exposes button names and provider IDs only, never credentials.

A local user explicitly links a provider from Account. Identity matching uses
the validated `(issuer, subject)` pair, never a display name or an unverified
email. A pair can belong to only one local account. Linking an already linked
identity to another account fails without moving it. Unlinking must leave an
enabled, usable login method; an unexpired PAT alone is not a replacement login
method. Administrative unlinking follows the same protection and retains the
existing active/last-administrator rules.

Automatic account creation and automatic linking are separate options, both
off by default. Creation produces a regular member, never an administrator.
Email-based linking additionally requires the provider's verified-email claim,
an unambiguous local match and verified provenance for that local email.
Inventory the existing verification model before enabling it: an unverified
local registration must not acquire another person's provider identity merely
because its email matches. Until that prerequisite exists, use explicit linking.
An unknown identity with creation disabled receives a friendly unlinked-account
result without disclosing whether a particular email has a local account.

Provider outage or disabling must leave password login usable. Turning off a
provider does not delete its links. Before disabling the only usable method for
any account, require a recovery method or a reviewed recovery procedure.

## Configuration and storage

Provider settings: ID, issuer/discovery URL, client ID, client secret file,
display name, optional locally bundled icon, scopes (initially `openid email`),
and explicit allowed native redirect URIs. Profile scope is opt-in. Use bounded
timeouts, response sizes, cached discovery/JWKS and restricted endpoint hosts;
never accept an issuer or JWKS URL from a login request.

Expose the optional feature through the application and its NixOS module.
Provide a runtime configuration-file interface and systemd credentials for
sensitive settings. Deployments must keep secrets and private provider/account
identifiers out of generated store files, command arguments and logs. The
public module contains interfaces and safe defaults, not configured identities.

Add identities with local user ID, stable provider ID, exact validated issuer,
subject, optional verified email and link timestamp. Enforce uniqueness of the
issuer/subject pair transactionally. Return an opaque identity ID and a masked
subject hint to clients. Never expose raw tokens or provider secrets.

OIDC-only creation needs an explicit local-password capability. Do not insert a
null hash into the existing non-null password-verification path or pretend an
unusable placeholder is a valid recovery password. Keep local-only accounts
unchanged, and test imported hashes, disabled accounts and password setup.

Use a bounded, expiring authorization-flow store. A restart may safely abandon
pending logins; it must not complete a partially checked link. Persist identity
changes and their audit records atomically. Native exchange grants store only
hashed one-time codes and expire independently from normal PATs.

## Browser protocol

Use authorization code flow with S256 PKCE, random state and nonce. Bind each
flow to its provider, purpose, initiating browser and local account for linking.
Consume it once, recheck account/session validity, and reject replay, expiry,
provider mix-up and a callback from another browser. Validate signature through
trusted JWKS, allowed algorithms, exact issuer, audience/authorized party,
expiry and nonce before choosing or mutating a local account. Use a maintained
OIDC implementation rather than implementing JWT cryptography. These checks
follow [OpenID Connect Core's ID-token validation rules](https://openid.net/specs/openid-connect-core-1_0.html#IDTokenValidation).

Use an exact configured same-origin callback URI and validated in-app return
paths. Never redirect to a submitted arbitrary URL. Login starts use the
existing anonymous CSRF bootstrap; link and unlink require authenticated CSRF
for browser cookies. Require a recent authentication proof for adding/removing
a login method, with the precise proof exchange frozen in T124.2. Retain normal
session rotation and secure-cookie policy. The flow and redirect review uses
the [OAuth security best current practice](https://www.rfc-editor.org/rfc/rfc9700.html).

Audit successful login, link and unlink with safe account/provider identifiers.
Redact callback query strings, authorization codes, state, nonce, verifier,
tokens and provider error descriptions from access logs, tracing and errors.
Do not fetch provider avatar URLs automatically.

## Proposed API for client preparation

Paths below are under `/api/v2`. These are proposed until the relevant slice is
tested and its SHA is sent to the client owner; existing routes are unchanged.

| Route | Contract |
| --- | --- |
| `GET /meta` | Add `data.auth: {password: true, oidc: [{id, name}]}`; `oidc` is empty when disabled. Preserve current `data.user` and other fields. |
| `POST /auth/oidc/start` | `{provider, client: "browser"}` with anonymous CSRF, or native `{provider, client: "android", redirect_uri, app_state, code_challenge, code_challenge_method: "S256"}`. Return `{data: {authorization_url, expires_at}}`. |
| `GET /auth/oidc/authorize` | Browser handoff for a short-lived start ticket; establish browser binding, then redirect to the provider. Ticket placement and log redaction are finalized before implementation. |
| `GET /auth/oidc/callback` | Validate/consume the provider response. Browser login sets the usual cookie; linking completes only for its initiating account; native login produces an exchange grant. |
| `POST /auth/oidc/exchange` | `{code, code_verifier, redirect_uri, token_name?}`; atomically consume a matching native grant and return `{data: {token}}`, with no browser cookie. |
| `GET /account/identities` | Cookie or strict PAT; `{data: [{id, provider, subject_hint, email, linked_at}]}` for the current user only. This does not change the cookie-only `/account` contract. |
| `POST /account/identities/link` | `{provider, client, ...native PKCE fields when applicable}` plus recent authentication; return the start envelope. Cookie mutations retain Origin/CSRF; PAT requests use explicit credentials. |
| `POST /account/identities/link/exchange` | Native linking confirmation: authenticated initiating account plus `{code, code_verifier, redirect_uri}`. Consume a link-purpose grant, attach the identity transactionally and return `{data: {identity}}`; issue no replacement PAT. |
| `DELETE /account/identities/{identity_id}` | Current user's identity only; recent authentication and last-login-method guard; return 204. |
| `GET /admin/users/{user_id}/identities` | Active administrator; same identity projection. |
| `DELETE /admin/users/{user_id}/identities/{identity_id}` | Active administrator with write-time role recheck, recent authentication and last-method guard; 204 plus audit. |

Use the standard v2 error envelope and `no-store`. Reserve `oidc_unavailable`
(503), `oidc_disabled`/unknown provider (404), `oidc_identity_unlinked` (403),
`identity_already_linked`/`last_login_method` (409), invalid/expired/replayed flow
(400), field errors (422), and rate limits (429 with `Retry-After`). Exact
recent-auth errors and callback-to-UI result paths are part of T124.2.

## Android protocol

Use a Custom Tab or the system browser, following [OAuth for native apps](https://www.rfc-editor.org/rfc/rfc8252.html).
The app creates a fresh verifier/challenge before starting. The server uses its
own separate PKCE verifier for the provider exchange. The final app redirect
contains only a short-lived one-time code and the app's correlation state;
never put a PAT in a URL. Bind the grant to the initiating flow, exact allowlisted
redirect URI and app challenge. Exchange consumes it atomically, using S256 as
specified by [PKCE](https://www.rfc-editor.org/rfc/rfc7636.html).

Prefer verified HTTPS app links where available; retain an explicitly registered
custom scheme only with the same PKCE proof. Check the shipping manifest before
choosing exact callback values. Do not allow arbitrary query/fragment additions.
Reject cancellation, wrong verifier, replay and account changes without replacing
the app's current credentials or leaking queued writes across accounts.

Native link grants are distinct from login grants. The provider callback stages
the checked identity; only the initiating authenticated account can finish the
link exchange. A link code cannot mint a PAT through the public login exchange,
and a login code cannot attach an identity. `app_state` is separate from the
server's provider state and is checked by the app before any exchange.

## Implementation slices and acceptance

| Task | Deliverable and required evidence |
| --- | --- |
| T124.1 | Inventory local password/recovery and email-verification semantics; choose and audit OIDC dependencies; migration for identities and explicit password capability; verify existing local users unchanged. |
| T124.2 | Freeze recent-auth proof, browser/native flow DTOs, exact redirect registrations, callback result routes, quotas, TTLs, key rotation and log-redaction rules with the client owner. |
| T124.3 | Disabled-by-default configuration, runtime secrets, discovery/JWKS and metadata capability projection; remote packaging checks; disabled-mode parity. |
| T124.4 | Browser login and explicit link/unlink, safe admin management and audit. Test ownership, uniqueness, last method/admin, nonce/state/signature/audience/expiry, replay and concurrent writes. |
| T124.5 | Native handoff and one-time PAT exchange with independent PKCE. Test code theft, wrong verifier/redirect, replay, cancellation and account isolation. |
| T124.6 | Optional creation and verified-email linking, only after verification prerequisites; test email changes, conflicts, unverified claims and pre-registered accounts. |
| T124.7 | Claude's matching web/Android UI and end-to-end tests against a local test provider; provider outage, unlink/recovery and existing password/PAT/offline flows. |
| T124.8 | Reviewed preview configuration with backup, verified artifact and Signal milestone. Production provider enablement and migration remain separate decisions. |

Do not reserve a migration number before checking current media/category work.
Use isolated Luna implementation/test tasks with one writer per scope; the
coordinator reviews identity linking, authorization, secret handling and schema
changes before integration.
