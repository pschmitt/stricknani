# Production cutover plan — 2026-11-03

This is a conditional schedule for moving `https://wolle.anika.blue` from the
Python service to the Rust service and its new web UI. It is planning material,
not authorization or a booked maintenance window. Schedule by readiness:
freeze as soon as the required changes and checks pass, rehearse as soon as
authorized, then cut over once rehearsal passes and the user records an explicit
GO for the window. There is no calendar waiting period. Allow roughly
**60–90 minutes** initially; a fresh rehearsal must measure the actual duration.
No deadline overrides a failed, unknown, or unchecked gate. Until GO, production traffic, storage, and services
remain untouched.

A rehearsal snapshot/copy requires its own explicit authorization and a
consistent database/media capture, including any necessary brief write pause.
That approval does not authorize the later production cutover. Do not start
either operation merely because its proposed date has arrived.

The user's updated decision makes **Authelia OIDC on web and Android, linking
existing local users, and the associated authentication fixes mandatory before
cutover**. This supersedes the earlier Nov 6 proposal and the suggestion to
enable OIDC after go-live. Readiness depends on implementing and rehearsing
that flow against Authelia; it does not require waiting for a particular date.

Use [`rust-migration-plan.md`](rust-migration-plan.md) as the operational
runbook for snapshot, import, verification, routing, and rollback mechanics.
This checklist schedules and records those steps; it does not replace or loosen
their safeguards. Prior T119 acceptance evidence does not establish acceptance
of later UI, API, Android, packaging, or migration changes.

## Conditional milestones

- **Candidate freeze, immediately when ready (Claude + Codex):** close T120 P1 work required
  for acceptance, freeze the UI, and identify the exact packaged web assets,
  Rust revision, and Android compatibility evidence. If P1s remain or the
  candidate changes, reset the schedule and repeat affected checks.
- **Rehearsal and click-through, immediately after freeze and authorization
  (Codex, with Claude and user):** rehearse
  the exact frozen candidate from a fresh authorized production copy into the
  isolated preview, then complete independent verification and user browser
  click-through. Protect the preview's existing edited data: record and verify
  its backup before replacing anything. Measure rehearsal and recovery time.
- **Cutover, immediately after successful rehearsal and explicit user GO:** proceed only after all gates below are
  checked, the candidate is unchanged since evidence was collected, and the
  user explicitly approves the recorded window and data-loss policy. Otherwise
  close the gaps and repeat affected checks before requesting the window.

## GO gates — complete before touching production

- [ ] **Final legacy release — Claude + Codex:** verify the signed immutable
  `v0.1.0` tag at the final Python `main` revision, the GitHub release and
  signed APKs, and Play internal/alpha workflow outcomes. Tag creation alone
  does not complete this gate. Codex realizes and tests the matching Nix
  rollback package and records its closure; publish the versioned legacy
  container and record its immutable digest. The rolling `latest` tag is not
  the rollback identifier. Evidence: `tag/commit: ____  release/runs: ____
  Nix closure: ____  container digest: ____`.
- [ ] **Public privacy policy — Claude + Codex:** preserve the existing public
  `/privacy` URL with an HTTP redirect to anonymous `/app/privacy`; verify both
  URLs on preview and production, with no authentication or crawler exclusion.
  Port the existing EN/DE policy and retain sign-in/footer links before Play
  submission. Record deployed SHA and HTTP evidence: `____________`.
- [ ] **UI and product acceptance — Claude:** T120 P1 acceptance gaps closed;
  exact packaged web/PWA assets frozen; required mobile and desktop journeys,
  accessibility, and browser/API checks green. Record evidence and candidate
  hashes: `UI/package: __________  checks: __________`.
- [ ] **Server and operations acceptance — Root:** review authentication,
  authorization, media, sync/offline behavior, migrations/importer,
  configuration, security, and packaging on the exact candidate. Keep optional
  external providers disabled unless separately approved. Record evidence:
  `Rust SHA: __________  package/image hash: __________  checks: __________`.
- [ ] **Android compatibility — Claude + Root:** confirm installed Android
  clients still use same-origin API v1 paths and retain PAT authentication,
  offline caches, and queued-write behavior. A new Android release may target
  its own compatible server version; it need not force an update for cutover.
  Record supported versions and evidence: `____________`.
- [ ] **Reviewed integration — Root + Claude:** merge `rust` to `main` only
  after review and green CI for the exact candidate. This checklist does not
  perform a merge. Record merged SHA and CI links: `____________`.
- [ ] **Fresh rehearsal — Root:** authorized fresh-copy rehearsal into the
  isolated preview completed using the frozen candidate. Back up the preview's
  existing edited data first. Require independent aggregate verification and
  successful user click-through; retain reports in protected operations
  storage, not this repository. Record rehearsal duration, report locations,
  and result: `____________`.
- [ ] **OIDC and authentication — Codex + Claude:** Authelia login works on web
  and Android, including explicit linking of existing local users, step-up,
  exact redirects, App Links/PKCE, flow binding, mix-up protection, ID-token
  validation, callback hygiene, and expiring/marked OIDC PATs. Malformed stored
  password hashes return uniform 401; reset/disable revoke PATs and browser
  sessions, and reset preserves the disabled state. Verify the Nix module,
  separate preview/production Authelia clients and SOPS-backed credentials.
  Rehearse login/linking on the preview against Authelia in browser and on the
  test phone. Record server/UI/infra revisions and evidence: `____________`.
- [ ] **Scope decisions — user:** review any remaining native API v2 work and
  other release blockers explicitly; OIDC is already required. Review the recorded
  differences: browser print/save is not the legacy generated PDF; domain CLI
  automation uses the authenticated API/browser rather than direct-DB CLI.
- [ ] **Security review — Root + user:** rerun `cargo audit` on the final
  candidate and review the recorded 2026-11-01 findings: optional SQLx RSA
  driver (inactive for SQLite-only use) and build-time `basic-toml`
  unmaintained notice. See [`rust-security-review.md`](rust-security-review.md)
  for the recorded dependency paths and scoped decisions.
  Record explicit disposition and next review deadline **2026-12-01**; no
  blanket waiver. OIDC adds runtime RSA verification; reassess the RSA advisory
  against that actual path rather than reusing the SQLite-only disposition.
  Stop for new findings or changed dependency paths.
- [ ] **Window and decision — user:** choose the date/time and duration after
  rehearsal; record exact candidate SHA and package hash, backup locations,
  rollback choice, and write-loss/reconciliation policy. User's explicit GO:
  `approved by/date/time: __________`. Without this record, do not begin
  production operations.

## Two-release sequence

1. **Final Python `v0.1.0`, before merging `rust`:** Claude owns the signed tag,
   GitHub/APK release and Play internal/alpha upload. Codex owns legacy
   container/Nix artifacts and their rollback verification. The tag is being
   released; record terminal workflow results before marking it complete.
2. **Rehearsal, approved cutover, production verification:** use the exact
   tested candidate and the gates above. Prepare the Rust/web/Android 1.0.0
   version changes before freezing that candidate so the eventual release can
   identify the same verified revision. Android's versionCode must advance
   beyond legacy and test artifacts.
3. **New-stack `v1.0.0`, after verification:** tag the verified post-merge
   `main` revision, publish the Rust container/Nix and GitHub release, and
   publish the signed Android app through the existing release/Play workflows.
   Claude owns Android signing, EN/DE release notes and in-app About/what's new;
   Codex owns server/container/Nix and workflow review. Confirm all component
   versions, artifacts, digest/closure and release notes agree. Never move a
   semantic version tag to repair a workflow; keep rolling `latest` separate.

## Approved-window checklist

Root runs the runbook only after GO. Record actual start/end and evidence in the
protected operations record.

- [ ] Enable the edge write barrier for browser and API traffic; pause Android
  sync and account for queued mutations and every background writer. Drain
  in-flight requests, stop Python services/jobs/workers, and confirm SQLite is
  quiescent.
- [ ] Make a matched database-and-media backup under
  `/mnt/data/backups/stricknani/<date>-pre-rust-cutover`, mode `0700`, without
  overwriting any prior backup. Validate integrity and sidecars; reject
  symlinks. Never weaken source validation or import from a production source
  path.
- [ ] Import the protected, immutable copy into a fresh, nonexistent
  Rust-owned destination separate from Python and all prior Rust data. Run the
  independent verifier. Confirm aggregate counts, preserved IDs/relationships,
  owner/admin authorization, media references and bytes, and service health;
  expose no sensitive row data in reports.
- [ ] While writes remain blocked, change only the same-origin upstream. Keep
  host, TLS, API v1 paths, and Android base URL unchanged; use no redirect.
  Check health, browser login, PAT/API authentication, representative reads,
  media, and two-owner authorization. Lift the barrier only after all pass.

## Rollback and observation

- **Before Rust accepts writes:** keep the barrier up, return the upstream to
  Python at the same origin and paths, verify Python health/login, then lift the
  barrier. Preserve the Rust import and snapshots for diagnosis.
- **After Rust accepts writes:** never route blindly to stale Python data. Keep
  writes blocked, preserve Rust state, take a separate protected Rust backup,
  and use a reviewed reconciliation/export plan. If reconciliation is not
  verified, restore service from preserved Rust state; the user decides the
  recovery outcome and any accepted-write loss.
- [ ] Monitor errors, auth, writes, media, and Android sync for **24–48 hours**;
  record aggregate outcomes. Retire Python only after a separate user decision
  and completion of the agreed backup-retention/recovery window. Keep API v1
  until a separate retirement decision and installed-client inventory that
  includes `web.rs` and other internal callers.
