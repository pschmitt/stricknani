# Authentication security follow-ups

Read-only source triage for the Rust authentication paths. These notes identify
observed behavior and tests to add before OIDC web and Android cutover. They do
not authorize production changes.

## Cutover gates

1. **Malformed stored password hash returns the generic credential failure and
   consumes the failed attempt.** `rust/src/auth.rs:379-389` maps any verifier
   error to `PasswordTokenError::Internal` and refunds the limiter reservation.
   An unsupported or corrupt stored PHC hash therefore differs from an
   incorrect password and does not count toward throttling. Map stored-hash
   parse/verification failure to the same external 401 as wrong credentials,
   without refunding that attempt; retain internal errors for actual worker or
   infrastructure failures if they can be distinguished. Existing
   `rust/tests/auth.rs:135-186` covers ordinary invalid credentials and the
   limiter threshold, but not an unsupported stored hash. Add a test asserting
   the generic 401, no token, and eventual 429 for repeated attempts against a
   malformed-hash account.

2. **Admin password reset must not silently reactivate an account.**
   `rust/src/admin_service.rs:254-260` sets `active = 1` and revokes only browser
   sessions. Resetting an inactive account consequently enables password
   login, and any still-valid PAT becomes usable. Preserve the target's active
   state during reset. Revoke PATs too if reset is intended to invalidate all
   credentials; confirm this reset policy as part of the change. Existing
   `rust/tests/api_v2_admin.rs:314-336` and
   `rust/tests/api_v2_admin_pat.rs:361-385` assert browser-session revocation,
   but do not assert the inactive state is preserved or that PATs are revoked.
   Add coverage for both API entry paths, with an inactive target and a live
   PAT.

3. **Disable must prevent PAT resurrection on re-enable.**
   `rust/src/admin_service.rs:212-214` revokes browser sessions only.
   `rust/src/auth.rs:195-203` rejects all tokens while `users.active = 0`, but
   does not revoke PAT rows; re-enabling the user makes those old PATs valid
   again. Revoke browser sessions and PATs in the same transaction that marks
   the account inactive. Existing active-state coverage in
   `rust/tests/api_v2_admin.rs` and `rust/tests/api_v2_admin_pat.rs` does not
   test disable, re-enable, then reuse of the same PAT. Add that sequence and
   assert the PAT remains unauthorized after re-enable.

These three behaviors are confirmed defects for the requested OIDC cutover
security bar. The active-state update and all associated token revocations
must commit atomically; otherwise a concurrent authentication request or
re-enable could observe a partially applied security change.

## Other verified findings and policy questions

- **CLI disable bypasses the API's administrator safeguards.**
  `rust/src/main.rs:365-382` directly sets `active = 0`; it has no last-active-
  administrator check and does not revoke token rows. Inactive status blocks
  token use while disabled, but the unrevoked PAT can return on re-enable.
  `rust/tests/cli.rs:151-205` only verifies that the account becomes inactive.
  This is an operator CLI without an actor identity, so the API's self-disable
  guard cannot be copied mechanically. Decide whether CLI disable should
  enforce the last-admin rule and share the API's token revocation semantics;
  test the chosen contract. No actor or new authorization model is assumed.

- **The existing audit log is resource history, not an authentication audit.**
  `rust/src/audit.rs:14-58` limits entities to project, yarn, and category and
  actions to resource create/update/delete/upload/remove. Auth and account
  operations are not recorded. Decide the required security event set,
  retention, and actor representation before adding events; any auth events
  should use bounded typed fields and no free text or credentials.

- **The admin module comment is stale.** `rust/src/api_v2/admin.rs:1` says
  “Browser-only”; `rust/tests/api_v2_admin_pat.rs:130-178,340-376` demonstrates
  admin routes accept API-purpose PATs. Update the comment when editing that
  module. This is a documentation defect, not an authorization bypass.

- **`last_used_at` (resolved: now updated on PAT authentication, throttled to 5 minutes).** Original note: It is imported at
  `rust/src/importer.rs:1127-1135`; no Rust auth write was found in the bounded
  auth path search. Decide whether PAT visibility/expiry requires last-use
  tracking. This does not establish that browser sessions should be globally
  rotated: login creates another session, which can be valid multi-device
  behavior. No session-fixation defect is established by this observation.

- **Rate-limit peer identity comes from the socket connection.** Login handlers
  read optional Axum `ConnectInfo<SocketAddr>` (`rust/src/auth.rs:231-250`), and
  the production server supplies it with
  `into_make_service_with_connect_info` (`rust/src/main.rs:279-281`). When the
  extension is absent, the key is `unknown`; no forwarded-header trust is
  applied in the login path. Behind a reverse proxy, this source identifies
  the TCP peer (the proxy), not necessarily the original client. Confirm the
  deployed proxy topology and explicit trusted-proxy policy before changing
  this; do not trust arbitrary forwarded headers. The source alone does not
  prove that production is currently missing connection information.

## Verification limits

This was a bounded source and existing-test review. No test, build, or live
proxy check was run. Evidence paths above are repository-relative.
