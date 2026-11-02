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
| **T120.9 Acceptance, preview, and cleanup** | This audit and the linked device, native accessibility, screen-reader, contrast, and performance reports. | **Open.** Phone journey and current-package matrix/lab refresh remain. Strict native screen-reader acceptance passed; older measurements remain tied to their package. Legacy consumer review is complete. |
| **T120.10 Requested update activation** | Explicit reload-to-update behavior, cross-tab readiness, dirty-form and in-flight mutation guards; frontend and PWA tests plus exact packaged PWA journey. | **Complete.** The packaged PWA browser gate exited 0. |
| **T120.11 Audit resource history** | Typed audit changes, event resource names, separate read-only cross-owner admin resource views, media authorization, and lazy diffs; Rust tests and exact packaged admin journey. | **Complete.** Ordinary ownership, edit permissions, and API v1 remain unchanged. Migration 14 is applied and database integrity passed. |
| **T120.12 Reusable resource cards** | Shared project/yarn/category cards across audit and related-resource views, protected previews, fallbacks, and responsive links; exact packaged admin, core, yarn, and accessibility journeys. | **Complete.** All five packaged browser gates listed in the checkpoint exited 0. |
| **T120.13 Independent Rust core packaging** | Clean frontend-only source changes leave the core derivation unchanged; package, module, flake and image checks passed. | **Complete.** The final deployed frontend reuses the verified core. |
| **T120.14 Header and action icons** | Shared bounded action icons, compact brand/version/profile header, native history and guarded audit scroll snapshots; exact packaged browser journeys. | **Complete.** Mobile English/German import controls, draft cancellation, filters/scroll and compact audit chevrons passed. |

## Current source, package, and live provenance

The deployed application is
[`72b1faa`](https://github.com/pschmitt/stricknani/commit/72b1faaec3c3dc488cea7cc9b27a7daa14ac9b83),
pinned by public configuration
[`bc0645107`](https://github.com/pschmitt/nixos-config/commit/bc0645107fc5e42ac0acfc2e85f366c76a065cf2).
The application input NAR is
`sha256-w5Em/zSMHQIHiSQWlpSiYe5RP/+JcCRrU1cMdcQHldk=`.
The [deployment checkpoint](web-ui-checkpoint.md) records its canonical system,
server/core/web packages, live asset fingerprints and database verification.
Production, nginx and nixbot identities were preserved during this rollout;
only staging and the expected application-input Nix daemon trigger changed.

For this canonical package, formatting, Svelte checking (zero errors and
warnings), 150 frontend tests, static build and four health tests passed.
The consolidated capability probe, core Chromium journey, portable
accessibility journey and worker/bootstrap byte checks all exited zero.
All five application CI workflows and all four public configuration CI
workflows passed. The portable accessibility result covers keyboard behavior
and reflow; it does not establish native speech or physical-device behavior.

Earlier `52221ad` and `fe9ff79` journeys remain historical evidence for their
named artifacts, including administrator/yarn/category/PWA flows and the
three-engine matrix. Their results are not new runs against `72b1faa`.

## Remaining acceptance

Checkpoint `2c4d6ca` adds the six required media/import/archive-state/account/admin
action hints to session and login responses, with strict client validation and
backend session/configuration tests. The full Rust suite and 150 frontend tests
passed. The UI consumption passed all seven groups of the consolidated browser
probe against canonical candidate `72b1faa`, including actual administrator
role checks and retaining sign-out and ordinary resource editing. The preview
rollout is verified at public pin `bc0645107`; the deployed application is
`72b1faa`. All five application CI workflows passed.

1. Run the complete approved workflow on a real phone, including touch,
  soft-keyboard, safe-area, upload, and navigation behavior.

The deployed `72b1faa` package passed the Firefox and WebKit core journeys and
native Firefox 200% zoom. The coordinator independently read their zero exit
receipts. The corrected matrix wrapper changes into the intended source root
and checks the resolved module path and SHA-256 before running; earlier attempts
that imported the historical runner are diagnostics, not current acceptance.
The executed core runner SHA-256 is
`dcf77a2c501302c7e59214a72465646cef625dde996ef10c701694c98b3c62fa`.

Current-package contrast also passed: 88 assessed checks and 28 explicit skips,
including photo-dialog title text and the actual focused title outline in both
themes. The coordinator read the zero exit, report and frozen source/runtime
provenance for `contrast-photo-07`; see [contrast](web-ui-contrast.md).

The current-package five-sample mobile lab also passed with frozen source and
runtime provenance: median LCP 2164ms, CLS 0 and an assessable 32ms interaction
candidate. Initial-route JavaScript and CSS measured 65,183 and 7,257 gzip bytes,
within their budgets. The coordinator independently read the zero exit and
report for `performance-current-02`; see [performance](web-ui-performance.md)
for the profile, method and limits. These are lab measurements, not field p75.

Strict native screen-reader acceptance passed on canonical `72b1faa` with
Firefox, Orca and native X11 input: all 13 report checks are true, including
route/heading speech, photo-dialog title and close control, Escape restoration,
and invalid-step focus/error speech. The parent independently read its zero
exit receipt and report. See [screen-reader verification](web-ui-screenreader.md).

The `52221ad` browser matrix passed Chromium, Firefox and WebKit. The updated
print-failure leg verifies an actual intercepted image failure, no premature
printing, an accessible error and successful retry. The normal service-worker
journey remains enabled.

Rendered contrast and five-sample mobile lab performance passed against
`52221ad`, as did native Firefox 200% zoom. These remain bounded
lab results, not full WCAG coverage or production field p75. The
[legacy consumer review](web-ui-legacy-cleanup.md) is complete: Android v1,
`/projects/import`, `/media`, mounted HTML views and the root-scoped worker
still require their assets. Actual retirement and production cutover remain
separate from this preview.

This audit does not mark T120 or T120.9 complete.
