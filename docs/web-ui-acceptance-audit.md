# T120 acceptance audit

This is a requirement-to-evidence audit, not a completion claim. The checklist
is in [`WEB-UI-REWRITE.md`](../WEB-UI-REWRITE.md). T120 and T120.9 remain
`wip`; T120.10–T120.14 are complete in [`TODO.md`](../TODO.md).
The deployed `/app` preview and the remaining acceptance work are described in
[`web-ui-checkpoint.md`](web-ui-checkpoint.md).

## Slice status

| Slice | Evidence | Assessment |
| --- | --- | --- |
| **T120.1 Design and contracts** | [`web-ui-design.md`](web-ui-design.md), [`web-v2-contract.md`](web-v2-contract.md), and the brief define journeys, routes, theme, API shapes, and security boundaries. | **Documented.** The remaining acceptance and cleanup work stays open. |
| **T120.2 Foundation and auth shell** | [`web/src/routes`](../web/src/routes), [`rust/src/frontend.rs`](../rust/src/frontend.rs), [`rust/src/pwa.rs`](../rust/src/pwa.rs), and Rust frontend/PWA tests cover the static shell, route fallback, CSP manifest, and app-scoped worker. | **Implemented.** Packaged PWA and accessibility browser gates passed. |
| **T120.3 Browser API v2** | [`rust/src/api_v2`](../rust/src/api_v2), [`web-v2-contract.md`](web-v2-contract.md), and API tests cover sessions, CSRF, ownership, pagination, and capabilities. | **Implemented.** Explicit action hints and UI consumption passed the consolidated canonical `72b1faa` browser probe. Android v1 remains separate. |
| **T120.4 Projects and reader** | [`ProjectLibrary.svelte`](../web/src/lib/features/projects/ProjectLibrary.svelte), [`ProjectReader.svelte`](../web/src/lib/features/projects/ProjectReader.svelte), and [`web_ui_browser.py`](../ci/stricknani/web_ui_browser.py). | **Implemented.** Exact packaged core project browser journey passed. Synthetic phone viewports passed; real-phone soft-keyboard and safe-area behavior remain open. |
| **T120.5 Project editor and media** | [`ProjectEditor.svelte`](../web/src/lib/features/projects/ProjectEditor.svelte), project/media API tests, and the core browser journey cover editing, conflicts, uploads, attachments, and reader media. | **Implemented.** Synthetic browser coverage passed; physical-device media and keyboard usability remain unproven. |
| **T120.6 Yarns and categories** | [`YarnLibrary.svelte`](../web/src/lib/features/yarns/YarnLibrary.svelte), [`CategoryManager.svelte`](../web/src/lib/features/categories/CategoryManager.svelte), API tests, and [`web_yarn_browser.py`](../ci/stricknani/web_yarn_browser.py). | **Implemented.** Exact packaged yarn/category browser journey passed. |
| **T120.7 Imports and jobs** | [`ImportWorkbench.svelte`](../web/src/lib/features/imports/ImportWorkbench.svelte), [`web_import_browser.py`](../ci/stricknani/web_import_browser.py), and OCR/archive controls and tests. | **Implemented.** Preserve SSRF, size, ownership, and feature-capability boundaries. |
| **T120.8 Account, tools, admin, and i18n** | Account/signup/admin components, API security tests, and account/admin browser runners. | **Implemented.** English and German catalogs are packaged; exact packaged admin journey passed. |
| **T120.9 Acceptance, preview, and cleanup** | This audit and the linked device, native accessibility, screen-reader, contrast, and performance reports. | **Open.** Phone journey and complete native screen-reader run remain. Current contrast/performance/native zoom passed; legacy consumer review is complete. |
| **T120.10 Requested update activation** | Explicit reload-to-update behavior, cross-tab readiness, dirty-form and in-flight mutation guards; frontend and PWA tests plus exact packaged PWA journey. | **Complete.** The packaged PWA browser gate exited 0. |
| **T120.11 Audit resource history** | Typed audit changes, event resource names, separate read-only cross-owner admin resource views, media authorization, and lazy diffs; Rust tests and exact packaged admin journey. | **Complete.** Ordinary ownership, edit permissions, and API v1 remain unchanged. Migration 14 is applied and database integrity passed. |
| **T120.12 Reusable resource cards** | Shared project/yarn/category cards across audit and related-resource views, protected previews, fallbacks, and responsive links; exact packaged admin, core, yarn, and accessibility journeys. | **Complete.** All five packaged browser gates listed in the checkpoint exited 0. |
| **T120.13 Independent Rust core packaging** | Clean frontend-only source changes leave the core derivation unchanged; package, module, flake and image checks passed. | **Complete.** The final deployed frontend reuses the verified core. |
| **T120.14 Header and action icons** | Shared bounded action icons, compact brand/version/profile header, native history and guarded audit scroll snapshots; exact packaged browser journeys. | **Complete.** Mobile English/German import controls, draft cancellation, filters/scroll and compact audit chevrons passed. |

## Current source, package, and live provenance

The deployed application source is
`52221ad266d5c38124f7ca442add952055223e7b`; public configuration pins it at
`db9a7a20cee270a113356b79a9baa7592458e3bc`. The source NAR hash
is `sha256-OHp+SibLsGdiv4av8jtuMvZKQjjgiQyNrA8jzrvTh5Q=`.
The web package is
`/nix/store/v2dd2d6jjn2v168flw2k1yr8xvq1pvmx-stricknani-web-0.1.0`; the
server package is
`/nix/store/z1v66gbfzm2ax4y4n8cjmnkr347cpsdf-stricknani-server-0.1.0` and
its raw executable (`bin/.stricknani-server-wrapped`) SHA-256 is
`9e4b0e376c7aa73019eb43a7bb2df748c616613fd1c816f103075ccd408d0c4f`.

Root independently verified the live HTML SHA-256
`bf63f41919e0a6426e000569170ca5c66f6654492476a6f14250ccdee8d98056`, the
service worker SHA-256
`5feb0521c818a2ff9a5c6c13b7612cf94de49356828b96d1845b60eb4ec3aa31` with
scope `/app/`, and the catalog SHA-256 prefix `f5b67b45` (738 entries per
language). Migration 14 completed; SQLite integrity passed. The pre-migration
backup is
`/mnt/data/backups/stricknani-rust/2026-11-02-pre-audit-history/database.sqlite3`,
SHA-256 `e9ef8e0add28ce2b2cf0f94e8d1bba1b15e1df0080fa1b5315149ecf635b58ec`.
Production, nginx, and nixbot identities remained unchanged. The expected
nix-daemon restart was limited to the application nix-path pointer.

The current frontend passed formatting, Svelte checking with zero errors and
warnings, 138 tests, static build and four health tests. Exact `52221ad`
packages passed core and administrator browser journeys, including native Back
draft guards and audit filter/scroll restoration. Header/icon checks passed
at 320/390/1280px; import controls passed at 320px in English and German.
The earlier `fe9ff79` milestone passed 304 Rust tests across 49 suites and all
five packaged browser gates; the PWA protocol and resource APIs are unchanged.

All four CI workflows for harness checkpoint
`2c94f380b6201a75882c4f1a4a9903c4a61f38a9` succeeded. The Android replay job's
first attempt failed while downloading the emulator SDK before tests started;
one retry passed. This result applies to that application checkpoint, not to
unrelated later infrastructure commits.

## Remaining acceptance

Checkpoint `2c4d6ca` adds the six required media/import/archive-state/account/admin
action hints to session and login responses, with strict client validation and
backend session/configuration tests. The full Rust suite and 150 frontend tests
passed. The UI consumption passed all seven groups of the consolidated browser
probe against canonical candidate `72b1faa`, including actual administrator
role checks and retaining sign-out and ordinary resource editing. The preview
rollout remains pending; the deployed application is still `52221ad`.

1. Run the complete approved workflow on a real phone, including touch,
  soft-keyboard, safe-area, upload, and navigation behavior.
2. Complete native screen-reader acceptance for labels, errors, route changes,
  and dialog changes. Synthetic accessibility and focused Firefox zoom checks
  do not establish this.
The current browser matrix passes Chromium, Firefox and WebKit. The updated
print-failure leg verifies an actual intercepted image failure, no premature
printing, an accessible error and successful retry. The normal service-worker
journey remains enabled.

Current rendered contrast and five-sample mobile lab performance passed against
the deployed package, as did native Firefox 200% zoom. These remain bounded
lab results, not full WCAG coverage or production field p75. The
[legacy consumer review](web-ui-legacy-cleanup.md) is complete: Android v1,
`/projects/import`, `/media`, mounted HTML views and the root-scoped worker
still require their assets. Actual retirement and production cutover remain
separate from this preview.

This audit does not mark T120 or T120.9 complete.
