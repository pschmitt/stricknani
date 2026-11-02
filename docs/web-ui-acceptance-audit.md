# T120 acceptance audit

This is a requirement-to-evidence audit, not a completion claim. The checklist
is in [`WEB-UI-REWRITE.md`](../WEB-UI-REWRITE.md). T120 and T120.9 remain
`wip`; T120.10, T120.11, and T120.12 are complete in [`TODO.md`](../TODO.md).
The deployed `/app` preview and the remaining acceptance work are described in
[`web-ui-checkpoint.md`](web-ui-checkpoint.md).

## Slice status

| Slice | Evidence | Assessment |
| --- | --- | --- |
| **T120.1 Design and contracts** | [`web-ui-design.md`](web-ui-design.md), [`web-v2-contract.md`](web-v2-contract.md), and the brief define journeys, routes, theme, API shapes, and security boundaries. | **Documented.** The remaining acceptance and cleanup work stays open. |
| **T120.2 Foundation and auth shell** | [`web/src/routes`](../web/src/routes), [`rust/src/frontend.rs`](../rust/src/frontend.rs), [`rust/src/pwa.rs`](../rust/src/pwa.rs), and Rust frontend/PWA tests cover the static shell, route fallback, CSP manifest, and app-scoped worker. | **Implemented.** Packaged PWA and accessibility browser gates passed. |
| **T120.3 Browser API v2** | [`rust/src/api_v2`](../rust/src/api_v2), [`web-v2-contract.md`](web-v2-contract.md), and API tests cover sessions, CSRF, ownership, pagination, and capabilities. | **Implemented and tested.** Android v1 remains separate; a complete Android-client run is not claimed here. |
| **T120.4 Projects and reader** | [`ProjectLibrary.svelte`](../web/src/lib/features/projects/ProjectLibrary.svelte), [`ProjectReader.svelte`](../web/src/lib/features/projects/ProjectReader.svelte), and [`web_ui_browser.py`](../ci/stricknani/web_ui_browser.py). | **Implemented.** Exact packaged core project browser journey passed. Synthetic phone viewports passed; real-phone soft-keyboard and safe-area behavior remain open. |
| **T120.5 Project editor and media** | [`ProjectEditor.svelte`](../web/src/lib/features/projects/ProjectEditor.svelte), project/media API tests, and the core browser journey cover editing, conflicts, uploads, attachments, and reader media. | **Implemented.** Synthetic browser coverage passed; physical-device media and keyboard usability remain unproven. |
| **T120.6 Yarns and categories** | [`YarnLibrary.svelte`](../web/src/lib/features/yarns/YarnLibrary.svelte), [`CategoryManager.svelte`](../web/src/lib/features/categories/CategoryManager.svelte), API tests, and [`web_yarn_browser.py`](../ci/stricknani/web_yarn_browser.py). | **Implemented.** Exact packaged yarn/category browser journey passed. |
| **T120.7 Imports and jobs** | [`ImportWorkbench.svelte`](../web/src/lib/features/imports/ImportWorkbench.svelte), [`web_import_browser.py`](../ci/stricknani/web_import_browser.py), and OCR/archive controls and tests. | **Implemented.** Preserve SSRF, size, ownership, and feature-capability boundaries. |
| **T120.8 Account, tools, admin, and i18n** | Account/signup/admin components, API security tests, and account/admin browser runners. | **Implemented.** English and German catalogs are packaged; exact packaged admin journey passed. |
| **T120.9 Acceptance, preview, and cleanup** | This audit and the linked device, native accessibility, screen-reader, contrast, and performance reports. | **Open.** Phone journey, complete native screen-reader run, current contrast/performance reruns, and conditional legacy cleanup review remain. |
| **T120.10 Requested update activation** | Explicit reload-to-update behavior, cross-tab readiness, dirty-form and in-flight mutation guards; frontend and PWA tests plus exact packaged PWA journey. | **Complete.** The packaged PWA browser gate exited 0. |
| **T120.11 Audit resource history** | Typed audit changes, event resource names, separate read-only cross-owner admin resource views, media authorization, and lazy diffs; Rust tests and exact packaged admin journey. | **Complete.** Ordinary ownership, edit permissions, and API v1 remain unchanged. Migration 14 is applied and database integrity passed. |
| **T120.12 Reusable resource cards** | Shared project/yarn/category cards across audit and related-resource views, protected previews, fallbacks, and responsive links; exact packaged admin, core, yarn, and accessibility journeys. | **Complete.** All five packaged browser gates listed in the checkpoint exited 0. |

## Current source, package, and live provenance

The deployed application source is
`fe9ff79ce93a87899b15042243e52caa698fdd54`; public configuration pins it at
`6ba1610af9596d1af129f6bc29eb8b835ea2dc3b`. The normalized source archive
SHA-256 is `f29d412242b3d7d67b9f41d4ba470f6571fbf87eee4fb389c02fc93a0800fe18`.
The web package is
`/nix/store/my3zp6kycxxjg3jp70ljbsajvhhd5qrq-stricknani-web-0.1.0`; the
server package is
`/nix/store/fdz83ix5jn5d0p53call8rbgz8fc6i11-stricknani-server-0.1.0` and
its raw executable (`bin/.stricknani-server-wrapped`) SHA-256 is
`8e4fd12218576109d28ade9b7e8f1830cc2c58992a77ecdc975f960040a851a5`.

Root independently verified the live HTML SHA-256
`73ba77b39da950412853b1ba1fe25edf0f035ce596f4657954d4ec4e783ca84a`, the
service worker SHA-256
`24c326c6602b31d36256d4fe5bfd2c9e62416a94e55812bf50f6280f9dd93c9b` with
scope `/app/`, and the catalog SHA-256 prefix `f5b67b45` (738 entries per
language). Migration 14 completed; SQLite integrity passed. The pre-migration
backup is
`/mnt/data/backups/stricknani-rust/2026-11-02-pre-audit-history/database.sqlite3`,
SHA-256 `e9ef8e0add28ce2b2cf0f94e8d1bba1b15e1df0080fa1b5315149ecf635b58ec`.
Production, nginx, and nixbot identities remained unchanged. The expected
nix-daemon restart was limited to the application nix-path pointer.

The packaged frontend passed formatting, Svelte checking with zero errors and
warnings, 142 tests, and static build. Rust passed 304 tests across 49 suites,
four health tests, and Clippy. The exact packaged admin, core project,
yarn/category, accessibility, and PWA browser gates all exited 0.

The exact-source CI watch for harness checkpoint `63d2f1ba` passed all four
registered workflows. The public auto-merge workflow was canceled, not
reported as a build failure. Therefore this document does not claim that all
CI is green.

## Remaining acceptance

1. Run the complete approved workflow on a real phone, including touch,
  soft-keyboard, safe-area, upload, and navigation behavior.
2. Complete native screen-reader acceptance for labels, errors, route changes,
  and dialog changes. Synthetic accessibility and focused Firefox zoom checks
  do not establish this.
3. Rerun rendered contrast and lab performance against the current packaged
  version. Earlier bounded contrast and five-sample lab performance receipts
  remain historical and do not establish full WCAG coverage or production
  field p75.
4. Finish the conditional legacy consumer and cleanup review before removing
  legacy templates, aliases, styles, scripts, routes, or the root-scope worker.
  Preserve Android v1, `/projects/import`, `/api/v1`, `/media`, and shared
  backend services. Production cutover remains separate.

T120.13 is in progress as packaging work: split frontend-independent Rust
compilation from final web/server assembly, and pass `corePackage` and
`webPackage` through the default package
wiring in `nix/module.nix`. The current preview package override is unaffected.
This audit does not mark T120 or T120.9 complete.
