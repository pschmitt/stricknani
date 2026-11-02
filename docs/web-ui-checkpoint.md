# T120 implementation checkpoint

The web UI rewrite is tracked in [`TODO.md`](../TODO.md) and specified in
[`WEB-UI-REWRITE.md`](../WEB-UI-REWRITE.md). The `/app` interface is an
incremental Rust preview alongside the existing Python application. Production
has not been cut over, and its executable and storage remain unchanged.

## Current status — 2026-11-02

The current v8 package source is exact commit `86d7e79b6862` (full source
manifest SHA-256
`305a29682e8b0ef989d1831167d96556d8bcdf32ceb28eafce61a6ca39c10f80`, 913
files). Its generated runtime catalog has 690 English and 690 German entries.
The package build produced web root
`/nix/store/z7jygybm66gbw0jfkixkws43fl88ddpc-stricknani-web-0.1.0`, Rust
package `/nix/store/giwgffbjqvsymdyaiysi6asqd6mff7zx-stricknani-server-0.1.0`,
and preview closure
`/nix/store/7iicbw26y9nwf40rjgxmkk1p5p55g2bn-nixos-system-rofl-10-26.11.20261030.1c89083`.
The web/Rust package and preview-closure builds exited 0. Root independently
verified the exact v8 core and admin browser journeys both exited 0.

The v8 closure was copied and switched successfully. Root verified that it is
the active system closure, with the preview and production services plus nginx
active. The production service unit remains unchanged. HTTPS `/app/` returned
200; the app-scoped worker returned 200 with scope `/app/`; and the anonymous
session endpoint returned a private, no-store response with no user. The live
690-entry English/German catalog was verified. A WIP preview notification was
sent after the checks. Published checkpoint `25c033e` added browser-harness
updates to the `86d7e79` runtime; this checkpoint also includes the SVG
comparison follow-up verified against that v8 artifact. Public configuration
commit `6ce9e108a` now pins application checkpoint `3630940`, whose runtime
files match `86d7e79`; its seven public CI runs passed. The persistent native
preview unit and removal of the temporary override are verified below.
Production Python code and storage remain unchanged.

The account/avatar navigation, action icons, More/language navigation, compact
library headings, mobile admin pagination wrapping, and 690-key EN/DE catalog
are included in the v8 package. Exact v8 core and admin browser checks cover the
updated routes; earlier combined-candidate PWA, print, three-engine browser,
and synthetic feature receipts remain evidence for their named artifacts and
are not relabeled as v8 reruns.

Earlier bounded evidence remains useful with its stated limits. The contrast
gate covered 84 assessable samples and 14 focus outlines across its recorded
routes/themes; it is not a full WCAG audit. The five-sample mobile lab profile
measured median/max LCP 1,916/1,924 ms, CLS 0, and a 32 ms scripted-session INP
candidate with 70.3–91.9% Event Timing coverage; it does not establish field
p75. Firefox `native-zoom-31` verified 200% full-page zoom and trusted native
input. Physical phone and complete Orca screen-reader acceptance remain open;
see [`web-ui-device-acceptance.md`](web-ui-device-acceptance.md) and
[`web-ui-screenreader.md`](web-ui-screenreader.md).

## Remaining acceptance

### Audit and update implementation checkpoint

The next candidate implements typed audit changes, resource names and separate
read-only administrator views, including media namespaces that distinguish
overlapping image and attachment public IDs. It also implements requested
reload-to-update with cross-tab draft/busy guards and retained public assets.
This implementation checkpoint is work in progress, not a deployment claim.

The frozen Rust `final-04` source manifest is
`7c69680c96710602cc643ab369d19d252be69c0a4fed4c1e7c736496ba1af37f`.
Formatting, all-target/all-feature Clippy, all 303 workspace tests across 49
suites, and four health tests passed. The corresponding integrated frontend
passed formatting, Svelte checking with zero errors/warnings, 136 tests across
20 files, and its static build. Its catalog has 739 English/German entries.
Both Nix packages built from committed checkpoint `19e36d4`. The core and admin
Chromium journeys passed against its exact realised server/web packages on a
separate build host. The core journey verifies project creation (POST 201),
editing (PUT 200), tags, media and dirty navigation. The admin journey verifies
cross-owner read-only pages, step images/attachments, lazy diffs, deleted
history, ownership denial and narrow layouts. These receipts establish those
journeys, not native-phone or screen-reader acceptance.

Follow-up worker fixes prevent activation-state reads from creating a cache,
protect waiting versions during cache pruning, and retain allowlisted public
assets after fallback. Their new package and full PWA browser journey remain
pending. No 739-entry candidate or schema-14 migration has been deployed.

A later automatic host upgrade restored the older pinned preview (666 catalog
entries and no app worker). The verified v8 runtime was restored using a
temporary staging-service ExecStart override, without changing the active
system generation or production service. HTTPS again matched the v8 690-entry
catalog and worker hashes. Persistence is now complete: the latest candidate
already matched the active/profile generation `28772h0m0z3alv3f4h27k7rhjm9pdi6r`,
so another system switch was unnecessary. The native unit uses the accepted v8
server; only the verified temporary override was removed, followed by a
staging-only restart. Production, nginx, nixbot and nix-daemon retained their
process identities and stayed active. Root independently verified the native
unit, override absence and accepted HTTPS hashes. This is persistence of v8,
not a new feature rollout; the earlier `7iicbw26…` closure remains its original
accepted artifact.

T120 and its subtasks remain open. Required work includes:

- Complete browser/package verification and rollout of requested updates with
  cross-tab draft/busy protection (T120.10), plus audit names, read-only admin
  views and recorded diffs with unchanged ordinary ownership (T120.11).
- Complete CI and pin/publication for the exact combined feature integration
  checkpoint. The v8 preview switch and post-switch checks are verified; keep
  that live state distinct from source publication.
- Complete the main workflows on a real phone. The latest run verified
  fixture and CDP bridge health, then stopped at the first sign-in tap because
  the required trusted touch events were not observed at the target. Cleanup
  completed; no app sign-in or workflow assertions passed. The scoped Firefox
  `native-zoom-31` checks are accepted; full physical-device and native
  screen-reader acceptance remain open.
- The bounded contrast gate passes on representative surfaces now included in
  the preview. It does not cover every component, state, or browser; evidence
  and limitations are in [`web-ui-contrast.md`](web-ui-contrast.md).
- Finish the conditional legacy cleanup/consumer review. T120 remains open;
  production migration and cutover require separate explicit approval.

## Legacy compatibility and cleanup constraints

Do not remove legacy routes or assets as part of T120 completion without a
separate consumer audit and cutover plan. `WEB_ROOT` adds the `/app` frontend;
it does not by itself disable the legacy HTML routers. Android v1 and its
`/projects/:id` and `/yarn/:id` deep links remain consumers. Preserve
`/projects/import`, which serves the Android bearer-token import flow outside
`/api/v1`, along with `/api/v1`, `/media`, and shared backend/media/PDF/import
services. The root-scoped legacy service worker and cached pages also need an
explicit compatibility/retirement plan; the new `/app` worker must not silently
strand existing clients. Python production code and assets remain unchanged.

## Historical checkpoints

The entries below record earlier work and are not claims about current live
state or completion of T120 acceptance. Their test results apply to the
revisions and environments named at the time.

- The live foundation introduced the static `/app` shell, cookie-authenticated
  v2 flows, strict CSP, isolated preview storage and reproducible assets. The
  preview was deployed while Python production remained unchanged.
- Subsequent milestones added project and yarn browsing/editing, media,
  favorites, categories, imports, account and token management, gauge,
  administrator and audit screens, signup APIs, and print support. Earlier
  feature-specific browser, Rust, frontend and health-check results remain
  useful historical evidence; current combined-candidate evidence is recorded
  above, with physical-device, native screen-reader, exact-checkpoint CI, and
  publication work still outstanding.
- Earlier rollout notes about missing Termux derivations and activation-wrapper
  failures are historical. The GTK test dependency issue was fixed in public
  revision `59ca774`; do not carry earlier infrastructure failures forward as
  the current blocker.
- Earlier aggregate bundle-size figures measured all generated assets, not a
  first-route transfer or production startup. Use the current first-route
  figures above for that measurement, and keep the synthetic localhost timing
  separate from production p75 acceptance.
