# Local identity inventory for optional OIDC (T124.1)

Read-only inventory of how user identity works in the Rust server at
`origin/rust` (66d4f93), written before any OIDC schema or auth change. All
paths are relative to `rust/` unless noted. Nothing here changes behaviour.

## 1. User model

| Fact | Evidence |
| --- | --- |
| `users(id, email, password_hash, active, is_admin, created_at)` | `migrations/0001_accounts.sql:8-15` |
| `email TEXT NOT NULL COLLATE NOCASE UNIQUE`, so uniqueness is case-insensitive in SQLite | `0001_accounts.sql:10` |
| Every write path also lowercases and trims the email first | `src/db.rs:185,212`, `src/auth.rs:353`, `src/api_v2/signup.rs:85`, `src/admin_service.rs:135`, `src/importer.rs:823-825` |
| The original-case imported email is kept in `legacy_email`; nothing authenticates against it | `migrations/0011_legacy_email.sql`, `src/importer.rs:1103-1112` |
| `password_hash TEXT NOT NULL` | `0001_accounts.sql:11` |
| `active INTEGER NOT NULL DEFAULT 1 CHECK (0,1)` is the only disabled flag (no deleted/locked/last_login columns) | `0001_accounts.sql:12` |
| `is_admin INTEGER NOT NULL DEFAULT 0 CHECK (0,1)` is the only role | `0001_accounts.sql:13` |
| Later columns: `profile_image`, `legacy_token_version` (imported, unused by Rust auth), `legacy_email` | `0007_legacy_metadata.sql:3`, `0010`, `0011` |
| Email validation is only `len <= 320`, contains `@`, no whitespace; no syntax or domain check | `src/db.rs:186-188`, `src/admin_service.rs:314-321` |

There is no email-change endpoint anywhere (`rg "SET email"` finds nothing).
An email is immutable after creation or import. There is no endpoint to change
`is_admin` either: the role is set at creation (admin create, CLI
`create-admin`) or imported.

## 2. Password capability

* `password_hash` is NOT NULL, so every user has one. There is no "no local
  password" representation. Any OIDC-only design must either relax the column
  (table rebuild in SQLite) or move credentials to a separate table.
* Verification (`src/auth.rs:451-484`): hashes starting `$2a$/$2b$/$2y$` are
  verified with `bcrypt` and, on success, re-hashed to Argon2id (default
  parameters) with a compare-and-set update
  (`auth.rs:397-413`, test `tests/auth.rs:74`). Everything else is parsed as a
  PHC string and verified as Argon2 (`auth.rs:474-480`).
* The importer accepts any non-empty source hash (`src/importer.rs:713`) and
  copies it verbatim (`importer.rs:1107`). The Python app only ever wrote
  bcrypt (`stricknani/utils/auth.py:84,91`), so real imports are bcrypt or
  Argon2. A hash in any other format (including a placeholder) makes
  `PasswordHash::new` fail, `verify_password` returns `Err(())`, and login
  returns HTTP 500 `internal_error` (`auth.rs:384-390`, mapped at
  `src/api_v2/mod.rs:397-402`) instead of 401, and the failed attempt is
  refunded from the rate limiter (`auth.rs:387`).
* Policy for new or changed passwords only (`auth.rs:575-584`): at least 12
  Unicode characters and not in a 20-entry list of common passwords
  (`auth.rs:543-564`); max 1024 bytes (`auth.rs:27`). Existing hashes are never
  checked against the policy. CLI also enforces 12 bytes on stdin
  (`src/main.rs:345-363`).
* Login throttling: 5 attempts per 5 minutes, keyed by peer IP and by email
  digest, in memory, 4096 keys (`auth.rs:22-24`, `reserve` at `auth.rs:41`).
  Successful logins refund the attempt (`auth.rs:436`). With no `ConnectInfo`
  the IP key is the literal `unknown` (`auth.rs:248-250`,
  `api_v2/mod.rs` login), so behind a proxy without peer info every client
  shares one key.
* Unknown or disabled accounts run a dummy Argon2 verify
  (`auth.rs:30-31,379-383`) so timing and the 401 body do not distinguish them.

## 3. Email verification

None. There is no verified flag or timestamp, no mail transport, no token
table, and no confirmation route in `src/` or the Python app. Open signup
(`api_v2/signup.rs:119`, `web_signup.rs`) creates an immediately usable account
for any address. Admin- or CLI-created accounts and imported accounts are
equally unverified in the data model. No trusted verified-email signal exists
today.

## 4. Account recovery

* No self-service reset ("forgot password"): no mail, no reset tokens.
* Authenticated change: `PUT /api/v2/account/password` and web
  `POST /account/password` require the current password
  (`src/account_service.rs:158-193`), apply the new-password policy, and use a
  compare-and-set update that also revokes all of the user's **browser**
  sessions (`account_service.rs:195-222`). PATs are not revoked.
* Admin reset: `POST /api/v2/admin/users/{id}/reset-password`
  (`src/api_v2/admin.rs:178-221`, service `admin_service.rs:220-262`). It sets a
  new hash **and `active = 1`** (`admin_service.rs:254`), so it silently
  re-enables a disabled account. It revokes browser sessions only
  (`admin_service.rs:299-312`), not PATs. An admin resetting themselves also
  clears the cookie (`admin.rs:208-216`). It works for any target including
  other admins.
* Out-of-band recovery: CLI `create-user`/`create-admin` (new accounts only),
  `disable-user`, `revoke-token` (`src/main.rs:14,365-405`) plus direct SQLite
  access. There is no CLI password reset or enable command.
* Recovery therefore rests on an administrator, which is also the only
  recovery path for an OIDC-only user whose provider is down.

## 5. Local user creation

| Path | Behaviour | Evidence |
| --- | --- | --- |
| Open signup | Off by default. `RUST_SIGNUP_ENABLED` true/1; routes are not even mounted when false. Always creates a member. Anonymous CSRF + same-origin + shared login rate limiter | `src/config.rs:136-140`, `src/web_signup.rs:24-29`, `src/api_v2/signup.rs:45-96,119` |
| Admin create | Admin-only, optional `is_admin`, inside a transaction that re-checks admin rights after hashing | `src/admin_service.rs:127-175`, `api_v2/admin.rs:113-138` |
| CLI | `create-user`/`create-admin EMAIL`, password on stdin | `src/main.rs:306-363` |
| Import | Preserves ids, hashes, active and admin flags, creates no sessions; browser sessions are discarded by migration 0012 | `src/importer.rs:1098-1115`, `migrations/0012_token_purpose.sql` |
| Library | `db::create_account`, `insert_prehashed_account` | `src/db.rs:178-219` |

All creation paths call `INSERT INTO users (email, password_hash, is_admin)`
(`db.rs:216`), so a future nullable-password or identity-creation path has one
chokepoint to extend. A duplicate email returns 409 `account_exists`
(`signup.rs:149-153`), which discloses account existence on open signup.

## 6. Admin guards

* Active-admin check on every admin read/write
  (`admin_service.rs:54-67`); writes re-check inside the transaction with a
  no-op `UPDATE users SET id = id` to take SQLite's write lock
  (`admin_service.rs:264-279`).
* Disable (`set_user_active`, `admin_service.rs:177-218`): an admin cannot
  disable themselves (`:193`); disabling an active admin requires more than one
  active admin (`:196-205`); on disable, browser sessions are revoked (`:212-214`).
  Re-enable is the same endpoint with `active=true` (no mail, no review).
* Not guarded: CLI `disable-user` has no last-admin or self check and does not
  revoke any token (`main.rs:365-383`); there is no demote endpoint, so no
  last-admin demotion guard is needed today; admin reset re-enables (above);
  deleting users does not exist.
* Admin API accepts either the cookie (with CSRF) or an API-purpose PAT with no
  CSRF: `api_v2/access.rs:131-224`, `resource_user`/`resource_mutation_user`
  at `access.rs:131-164`, tests `tests/api_v2_admin_pat.rs:130,294`. The
  file-level doc comment in `api_v2/admin.rs:1` ("Browser-only") is stale. An
  admin PAT can therefore already reset any password and create admins.

## 7. Sessions and PATs

Both live in `api_tokens` (`0001_accounts.sql:16-24`): SHA-256 hex of the
secret (`auth.rs:534-536`), `created_at`, nullable `expires_at`, nullable
`revoked_at`, `purpose IN ('api','browser')` (`0012_token_purpose.sql`),
`last_used_at` (imported, never written by Rust auth, `0007:5`). Secrets are
`sna_` + 32 random bytes hex (`auth.rs:526-532`).

| | Browser session | PAT |
| --- | --- | --- |
| Created by | password login: `POST /api/v2/auth/login` (cookie, CSRF, 14 days, `api_v2/mod.rs:50,327-441`), legacy web form `web.rs:195-265` (mints via `/api/v1/auth/token`, then flips the row to `browser`) | `POST /api/v1/auth/token` or `/api/v2/auth/token` (email+password, no expiry, `auth.rs:229-297`, `api_v2/native_auth.rs:44-100`); self-service `POST /account/api-tokens` (5 per hour, `account_service.rs:10,67-139`) |
| Accepted by | cookie only (`__Host-stricknani_session` when secure, `auth.rs:162-177`) | `Authorization: Bearer` only, exactly one header (`auth.rs:127-141`); API resources and admin API, not `/account/*` |
| Expiry | `expires_at = now + 14 d` | none |
| CSRF | HMAC of the session token (`web::csrf`) | not needed |
| Validity query | `revoked_at IS NULL`, not expired, purpose matches, **`users.active = 1`** (`auth.rs:195-209`) |
| Revoked by | logout (`mod.rs:444-520`, `web.rs:292-310`), password change, admin reset, admin disable | `POST /account/api-tokens/{id}/revoke` (own, `account_service.rs:141-156`), CLI `revoke-token ID` (any, `main.rs:384-405`) |

Notes relevant to OIDC:

* **Disable cascade is implicit.** Disabling sets `active=0`, revokes browser
  sessions, and leaves PAT rows untouched; PATs stop working only because
  `token_user` joins `users.active = 1`. Re-enabling (admin API, or admin
  reset) makes every previously unrevoked, unexpired PAT valid again.
* **No session rotation.** Login adds a row and never revokes earlier ones; no
  device list is exposed for browser sessions (`list_tokens` filters
  `purpose = 'api'`, `account_service.rs:40-65`).
* **No step-up or recency concept.** The closest existing signal is
  `api_tokens.created_at` of the browser session row, but sessions last 14
  days and are not tied to a re-authentication event. A PAT has no recency.
  `last_used_at` is unused.
* Sensitive account mutations (`PUT /account/password`) are cookie-only with
  CSRF plus the current password (`api_v2/account.rs:133-167`); this is the
  only re-authentication that exists, and it needs a password.
* `Referrer-Policy: strict-origin-when-cross-origin` globally
  (`src/security.rs:244-249`): same-origin navigations send the full URL,
  including any query string, as Referer. API responses use `no-store`
  (`api_v2/mod.rs:256-262`). The app installs no request-logging layer
  (`rg TraceLayer` is empty); access logs would come from the reverse proxy.

## 8. Auth audit semantics

There is effectively **no auth audit trail today.**

* `audit_events` (`0007:39-47`, `0014`) records only resource history:
  `Entity` (`audit.rs:16`) is `project|yarn|category` and `Action` (`audit.rs:42`) is
  `created|updated|deleted|uploaded|removed` (`src/audit.rs:16-63`).
  `entity_id` is NOT NULL INTEGER and `actor_user_id` is
  `ON DELETE SET NULL`. Events are inserted in the caller's transaction
  (`audit.rs:405-470`).
* Not audited: login success or failure, logout, token create/revoke, password
  change, admin reset, create, disable/enable, signup, CLI actions. (`rg audit`
  across `account_service.rs`, `admin_service.rs`, `auth.rs`, `api_v2/admin.rs`,
  `api_v2/account.rs` finds none.) Failed logins are visible only to the
  in-memory limiter.
* Redaction: change sets are an allowlisted `ChangeField` enum with bounded
  text and size (`audit.rs:70-140`, limits at `:7-11`); legacy `details` are
  projected through the allowlist, so unknown keys such as `password_hash` are
  dropped (`src/audit_service.rs:620-632`). There is no secret redaction layer
  beyond that: any new auth event must carry no free text.
* The audit listing and parser only know the three entities
  (`Entity::parse`, `audit.rs:25-31`), so a new entity such as `user` or
  `identity` needs explicit handling in `audit_service.rs`/`web_audit.rs` to
  avoid leaking into project/yarn history views or being rejected.

## 9. Prerequisites for OIDC

1. **Password-less users.** Needs an explicit capability (nullable hash or a
   credentials table). Every reader of `users.password_hash` must handle it:
   `auth.rs:365-384` (login), `account_service.rs:168-193` (change password),
   `admin_service.rs:254` (reset: should become "set initial password"),
   `importer.rs:1103`, `db.rs:216`. Today an unusable placeholder gives
   HTTP 500 (not 401) on password login, which both breaks the generic
   response and discloses that the account is special (section 2).
2. **Verified-email auto-link.** Blocked: no trusted verified signal exists for
   local emails (section 3). Open signup lets anyone register any address, so
   matching on IdP email alone is an account-takeover vector. Prerequisite is
   a local email-verification provenance (for example
   `email_verified_at` plus source) with a decision on which existing accounts
   count (admin/CLI-created, imported, or none). Until then only explicit
   linking is safe. An email-change feature, if ever added, must clear it.
3. **Lockout protection.** Define "usable login method" as: a local password
   (non-placeholder) on an active account, or at least one linked identity
   whose provider is currently enabled and configured. The unlink guard must
   count inside the same write transaction (reuse the no-op-update lock
   pattern, `admin_service.rs:264-279`). PATs are not login methods. An
   administrator password reset is the only recovery for an identity-only user
   and also re-enables the account.
4. **Recent authentication.** No mechanism exists (section 7); needs a
   server-side per-session `reauth_at` (and a separate step-up artifact for
   PAT clients), and must work for users with no password.
5. **Disable semantics.** OIDC login and token exchange must check
   `users.active` in the same transaction; consider revoking PATs on disable
   so re-enable does not resurrect them.
6. **Audit.** New entity and actions and a no-free-text rule (section 8).
7. **Flow state.** There is no server-side flow/pending store and no
   cross-process state (limiter and CSRF key are in memory; restarts reset
   them), consistent with the plan's "restart abandons pending logins".

## 10. Risks

| # | Risk | Severity |
| --- | --- | --- |
| R1 | Auto-link by email on an instance with open signup or unverified imported accounts: pre-registration takeover | High |
| R2 | OIDC-minted PATs do not expire (`expires_at` NULL) and survive IdP-side disablement; re-enabling a local account revives old PATs | High |
| R3 | Placeholder password hash makes password login 500 and refunds the rate limit; enumeration oracle and unthrottled probing | Medium |
| R4 | No recent-auth primitive; OIDC-only users cannot use the password step-up that exists; admin PATs mutate without CSRF or step-up | Medium |
| R5 | Admin reset-password re-enables disabled accounts and does not revoke PATs | Medium |
| R6 | No auth audit trail at all, so link/unlink/login events will be the first and need a new entity; failures unobservable | Medium |
| R7 | Lockout: identity-only user plus provider outage, misconfiguration or unlink race; only recourse is an admin | Medium |
| R8 | CLI `disable-user` has no last-admin guard and no revocation; `is_admin` accounts could be locked out | Low |
| R9 | Referer policy leaks query strings on same-origin navigation (OIDC callback `code`/`state`); no app-level access log but proxy logs apply | Medium |
| R10 | `ip:unknown` limiter key behind a proxy lacking peer info creates a global bucket (existing; reusing it for OIDC endpoints would amplify) | Low |
| R11 | Email case: SQLite NOCASE ASCII only; non-ASCII emails compare case-sensitively in SQL but are lowercased in Rust (Unicode): subtle mismatch for IdP-supplied addresses | Low |
| R12 | Stale doc comment says admin API is browser-only; it accepts PATs | Low |
