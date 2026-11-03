# Codex backend handoff — 2026-11-03

Claude owns all further backend, Nix, UI and rollout work. Codex workers were
interrupted. This branch preserves unfinished files from the shared Rust
worktree and the three isolated worker worktrees. It is a WIP snapshot, not a
new verified release. No production changes were performed.

Base: `7300946572651ed14ef50c77566489fc24b26a62`. That forward-only fix removes
category references accidentally included in the first media commit. Never
rewrite the shared Rust history. The source manifest records copied file hashes.
Unrelated UI, catalogs, REWRITE.md and browser helpers were not changed.

| Task | Done / green | Broken or unverified | Next |
| --- | --- | --- | --- |
| T124.3 protocol | Original 12 protocol/mock-issuer tests passed; HTTPS/issuer/nonce/audience/azp/signature checks implemented. | Callback-burn/discard and later runtime refinements need final unit/lint rerun. | Run current protocol/runtime tests and review the exact candidate. |
| T124.4 login HTTP | Latest v4 login route tests 6/6 passed, including signed browser login, finite native PAT exchange, wrong verifier/redirect burn and replay, unknown/inactive identities and native error callback. Metadata test 1/1 passed. | Final all-target Clippy/full suite not completed. | Complete focused unit filters, strict lint and exact combined suite. |
| T124.5 identities | Earlier storage tests 14/14 passed; v4 HTTP account tests 6/6 passed. Proofs bind token/action/target; browser callback rechecks originating session. | Latest subject-hint fix and dead-code cleanup are preserved but need final lint/unit confirmation. | Review list/admin scope, one-use proof and callback handling; rerun checks. |
| T124.6 configuration | Rust parser/main wiring, explicit module options and docs assembled. Remote Nix enabled/negative evaluations, callback logging suppression, nixfmt/statix passed. | Config unit-filter compile failed with a moved runtime in a test; missing clone was fixed locally but not rerun before handoff. | Rerun config unit filter, then strict lint/full suite; configure only preview first. |
| T126.3 password capability | Login honors password_enabled and rechecks it during mint; orphan OIDC tokens rejected; step-up helper merged. Admin reset enables local password capability while preserving disabled status; self password change revokes all tokens. | These latest root-owned account/admin changes and new regressions have not been run as one candidate. | Run auth/account/admin regression tests and review policy. |
| T121.2.categories | Separate category slice passed 30 focused tests plus strict Clippy. Per-owner logical clock serializes watermark and writes; owner transfer tombstones covered. | Current combination with media/OIDC not fully tested. Claude is already taking this slice into its build-fix branch. | Integrate once, run combined sync/importer tests, then switch Android categories to v2. |
| T121.6 media | On Rust through media commit plus fix 7300946; 134 committed backend files matched tested frozen source; 24 focused media/history/sync tests and strict Clippy passed. stitch_sample_html is included. | No fresh clean-archive package build after the last forward fix. | Build exact committed source; back up preview and retain closure before migration0015. |

## Latest failure and receipts

The v4 route receipt is 0: account 6, login 6, metadata 1 passed. The last
config-filter receipt is 101 due to a test moving `runtime` through
`with_pat_lifetime_seconds`; the local clone fix is included here. A later
retry was not observed as complete before workers stopped. Strict all-target
Clippy and the final combined full suite were not completed.

Remote receipt directories (builder roles are recorded in Concord):

- `~/build/stricknani-oidc-integration-v4-20261103/reports/`:
  `routes.exit=0`, `config.exit=101`.
- `~/build/stricknani-media-integrated-17d8-20261103/reports/`:
  fmt/focused/clippy all 0, 24 focused tests.
- `~/build/stricknani-category-delta-0544f53-retry6-20261103-01/reports/`:
  focused/strict gates 0, 30 focused tests.

The old storage-only unused revoker was removed; the generic proof-consume
wrapper is now test-only. Root configuration reconciliation guards removal of
an active password-disabled user's final provider and revokes removed-provider
credentials atomically; it must not run based on network discovery outcomes.

## Integration and rollout

Migrations: 0015 media positions/canonical cover; 0016 category sync metadata,
clock and tombstones; 0017 identities, password capability, origin-marked
expiring OIDC credentials, step-up proofs and typed auth audit. Claude is the
single preview rollout writer and owns a consistent DB/media backup and
retained old closure before migrations. Production remains disabled until
explicit cutover approval. Local copies of this snapshot were mechanically
assembled for handoff and were not compiled as this exact branch.

New core/runtime paths and module/router registrations are included. Keep the
root auth.rs policy changes together with its thin step-up wrapper; do not
replace it with an older worker copy. Provider discovery is lazy; an IdP
outage must preserve password login and must not revoke existing tokens.

Remaining follow-ups: final dependency audit; callback/proxy logging review;
CLI disable-user still needs a separately reviewed last-admin/token-revocation
policy; broader local-auth audit and last_used_at remain follow-ups. Existing
fixture scanner false positives are not credentials: do not open incident
notes or rotate values because of inert test strings.
