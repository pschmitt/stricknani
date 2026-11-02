# T120 acceptance audit

This is a requirement-to-evidence audit, not a completion claim. The checklist
is in [`WEB-UI-REWRITE.md`](../WEB-UI-REWRITE.md) and `T120.1–T120.11` remain
`wip` in [`TODO.md`](../TODO.md). Frozen-candidate evidence below is distinct
from committed source and live preview state; passing it does not complete T120.

## T120.1–T120.9

| Slice | Implementation and evidence | Assessment |
| --- | --- | --- |
| **T120.1 Design and contracts** | [`web-ui-design.md`](web-ui-design.md), [`web-v2-contract.md`](web-v2-contract.md), and the brief define routes, mobile navigation, states, theme, API shapes, and security boundaries. | **Documented.** The design and contract artifacts exist; remaining acceptance and rollout work stays open. |
| **T120.2 Foundation and auth shell** | [`web/src/routes`](../web/src/routes), [`+layout.svelte`](../web/src/routes/+layout.svelte), [`rust/src/frontend.rs`](../rust/src/frontend.rs), [`rust/src/pwa.rs`](../rust/src/pwa.rs), [`rust/tests/frontend.rs`](../rust/tests/frontend.rs), and [`rust/tests/pwa.rs`](../rust/tests/pwa.rs) cover the static shell, route fallback, CSP manifest, and `/app` worker. [`rust/src/lib.rs`](../rust/src/lib.rs) and its tests implement the configured `/` → `/app/` landing. | **Implemented; v8 package, core/admin journeys, and preview rollout pass.** The v8 source pin and override cleanup are complete; newer feature acceptance remains open. |
| **T120.3 Browser API v2** | [`rust/src/api_v2`](../rust/src/api_v2), [`docs/web-v2-contract.md`](web-v2-contract.md), and [`api_v2.rs`](../rust/tests/api_v2.rs), [`api_v2_projects.rs`](../rust/tests/api_v2_projects.rs), [`api_v2_resources.rs`](../rust/tests/api_v2_resources.rs), [`api_v2_media.rs`](../rust/tests/api_v2_media.rs), [`api_v2_imports.rs`](../rust/tests/api_v2_imports.rs), [`api_v2_account.rs`](../rust/tests/api_v2_account.rs), [`api_v2_admin.rs`](../rust/tests/api_v2_admin.rs), [`api_v2_audit.rs`](../rust/tests/api_v2_audit.rs), [`api_v2_signup.rs`](../rust/tests/api_v2_signup.rs), [`api_v2_ocr.rs`](../rust/tests/api_v2_ocr.rs), [`api_v2_archive.rs`](../rust/tests/api_v2_archive.rs), and [`api_v2_tools.rs`](../rust/tests/api_v2_tools.rs) cover browser sessions, CSRF, ownership, stable errors, pagination, and capabilities. | **Implemented and API-tested.** No v2 feature gap is established; API tests and the combined synthetic journeys provide candidate evidence, not live verification or a full Android-client run. |
| **T120.4 Projects and reader** | [`ProjectLibrary.svelte`](../web/src/lib/features/projects/ProjectLibrary.svelte), [`ProjectReader.svelte`](../web/src/lib/features/projects/ProjectReader.svelte), [`web_ui_browser.py`](../ci/stricknani/web_ui_browser.py), and [`web_accessibility_browser.py`](../ci/stricknani/web_accessibility_browser.py) cover library filters, reader content, navigation, image dialogs, and keyboard behavior. | **Implemented; combined synthetic journeys pass.** Physical-phone viewport, soft-keyboard, and safe-area behavior remain unproven. |
| **T120.5 Project editor and media** | [`ProjectEditor.svelte`](../web/src/lib/features/projects/ProjectEditor.svelte), project/media API tests, [`web_ui_browser.py`](../ci/stricknani/web_ui_browser.py), and [`ProjectReader.svelte`](../web/src/lib/features/projects/ProjectReader.svelte) cover create/edit, step ordering, conflict handling, uploads, attachments, and reader media. | **Implemented; synthetic journeys pass.** Do not infer physical-device upload or keyboard usability from desktop automation. |
| **T120.6 Yarns and categories** | [`YarnLibrary.svelte`](../web/src/lib/features/yarns/YarnLibrary.svelte), [`YarnForm.svelte`](../web/src/lib/features/yarns/YarnForm.svelte), [`CategoryManager.svelte`](../web/src/lib/features/categories/CategoryManager.svelte), [`web_yarn_browser.py`](../ci/stricknani/web_yarn_browser.py), and resource API tests cover inventory, editing, photos, favorites, associations, and category changes. | **Implemented; combined synthetic journeys pass.** |
| **T120.7 Imports and jobs** | [`ImportWorkbench.svelte`](../web/src/lib/features/imports/ImportWorkbench.svelte), [`web_import_browser.py`](../ci/stricknani/web_import_browser.py), import API tests, [`OcrControl.svelte`](../web/src/lib/features/media/OcrControl.svelte), and [`ArchiveControl.svelte`](../web/src/lib/features/media/ArchiveControl.svelte) cover text/file/URL review, explicit AI opt-in, OCR, and archive status. | **Implemented; combined synthetic journeys pass.** Preserve SSRF, request-size, ownership, and feature-capability checks. |
| **T120.8 Account, tools, admin, and i18n** | Account/signup/admin components and [`web_account_browser.py`](../ci/stricknani/web_account_browser.py), [`web_admin_browser.py`](../ci/stricknani/web_admin_browser.py), and [`web_ui_browser.py`](../ci/stricknani/web_ui_browser.py) cover account/token, gauge, signup, admin/audit, and locale workflows. [`api_v2_account.rs`](../rust/tests/api_v2_account.rs), [`api_v2_admin.rs`](../rust/tests/api_v2_admin.rs), [`api_v2_signup.rs`](../rust/tests/api_v2_signup.rs), and [`api_v2_tools.rs`](../rust/tests/api_v2_tools.rs) cover API security. | **Implemented; combined synthetic journeys pass.** English/German source and generated catalogs exist. |
| **T120.9 Acceptance, preview, and cleanup** | [`web-ui-performance.md`](web-ui-performance.md), [`web-ui-device-acceptance.md`](web-ui-device-acceptance.md), [`web-ui-native-a11y.md`](web-ui-native-a11y.md), [`web-ui-screenreader.md`](web-ui-screenreader.md), [`web-ui-contrast.md`](web-ui-contrast.md), the PWA/accessibility runners, `rust/tests/frontend.rs`, and `rust/tests/pwa.rs` hold current evidence. | **Open.** Earlier combined-candidate, three-engine browser/PWA/print, lab performance, focused keyboard/zoom, and bounded contrast gates pass for their recorded artifacts. The v8 preview rollout is verified. Physical-device, complete native screen-reader, exact-checkpoint CI/publication, and conditional cleanup evidence remain open. |

## Cross-cutting evidence

- **Auth and API security — covered in code and tests.** The v2 contract and
  Rust tests cover browser-purpose cookie sessions, pre-auth CSRF, same-origin
  writes, expiry/logout, rate limits, no-store responses, owner-safe `404`s,
  role-gated admin access, and API-token secret-once behavior. V1 remains a
  separate bearer API for Android. The combined synthetic journeys passed, but
  this is not a full Android-client run against the candidate.
- **Static routing, CSP, and PWA — frozen-candidate gates pass.** The exact
  built-root PWA run passed. The three-engine print/browser matrix passed with strict CSP;
  WebKit screenshots were omitted because the screenshot tool injects styles,
  while the functional and CSP checks ran. Rust tests cover known-route shell
  fallback, real `404`s for unknown assets, API/media/health isolation, and
  failing closed on invalid CSP/build inputs. The new worker is `/app`-scoped;
  legacy worker coexistence is tested, but its retirement is not complete.
- **Accessibility — focused synthetic and scoped Firefox native checks pass;
  complete screen-reader and device checks remain open.**
  Keyboard-only navigation, SPA routing, dialog focus/Escape restoration,
  reduced motion, labels/error announcements, and narrow reflow passed in a
  synthetic browser. The accepted Firefox `native-zoom-31` run verified 200%
  full-page zoom: the CSS viewport changed from 1252 to 626 px at a fixed
  1440×1000 virtual display, with no overflow; native Enter search/clear and
  trusted X11 pointer search/request checks passed. A later helper change made
  those observed results required assertions, but that modified helper has no
  fresh receipt; this does not invalidate `native-zoom-31`. Chromium's headed
  launch failed before app assertions. Physical-phone behavior remains
  unproven. Orca produced speech output during
  the native fixture attempt, but expected labels were not captured after the
  test actions and the route transition failed; complete screen-reader
  acceptance is unproven. See [`web-ui-screenreader.md`](web-ui-screenreader.md).
- **Rendered contrast — bounded overlay gate passes.** The browser gate
  measured Login, Projects, reader/editor, yarn editor, Account, and Gauge in
  both themes. All 84 assessable samples passed, including 14 keyboard-focus
  samples; 28 route/category pairs had no visible target. The reviewed CSS
  contrast-control UI assets are included in the v8 package and live preview.
  This bounded result is not a full WCAG audit. Ratios, selectors, artifact
  provenance, and limitations are in [`web-ui-contrast.md`](web-ui-contrast.md).
- **Performance — controlled lab targets pass.** The five-sample mobile gzip
  report measures first-route JS/CSS/catalog at 52,732/6,150/21,247 bytes gzip-6,
  median/maximum LCP 1,916/1,924 ms, CLS 0, and an assessable scripted-session
  lab INP candidate of 32 ms in all samples with 70.3–91.9% observed Event
  Timing coverage. These meet the stated lab thresholds; they do not establish
  production p75. Method and limits are in the linked lab report.
- **Current provenance — keep source, package, and live state distinct.** The
  v8 package uses committed source `86d7e79`, with a 913-file source manifest
  SHA-256 `305a29682e8b0ef989d1831167d96556d8bcdf32ceb28eafce61a6ca39c10f80`.
  Its web package, Rust package, and preview closure built successfully. Root
  independently verified the exact v8 core and admin synthetic browser
  journeys exited 0. The v8 closure was switched successfully. Post-switch
  checks verified the active closure, app route (HTTP 200; SHA-256
  `3e4f67ce2db05c8935aeab22dfd075f56e192f424ffa1e4e6b0bca49bb0adbef`),
  app-scoped worker (HTTP 200; SHA-256
  `adba52b18e07b479d8cfb44b92ee842943ca60ad8d1f6219c5ba88eff0ba144c`),
  and anonymous session response (`user: null`, private/no-store, signup and
  external integrations disabled). The live catalog contains 690 English and
  690 German entries. Production service unit and nginx configuration remain
  unchanged. Published checkpoint `25c033e` added browser-harness updates;
  the SVG hidden-DOM comparison follow-up is included here and passed against
  the same v8 package. Public pin `6ce9e108a` and native preview persistence are now verified; the newer candidate remains separate.

  Earlier accepted receipts remain limited to their named artifacts: combined
  core and optional-service/signup journeys, PWA, Chromium/Firefox/WebKit and
  print, accessibility, performance, and contrast. They are not represented as
  v8 reruns. The v8 UI includes account/avatar navigation, action icons,
  More/language navigation, compact library headings, and wrapped mobile admin
  pagination. The v8 core/admin browser checks cover the updated routes.

  A full Python audit previously passed MyPy, changed-file Ruff check/format,
  10 helper tests, and four health tests. Recursive Ruff diagnostics in the
  pinned Android CI submodule were present on both baseline and candidate.
  Those results apply to the snapshots recorded in their original receipts;
  they do not replace exact-checkpoint CI. The v8 package source manifest and
  build provenance are recorded in [`web-ui-checkpoint.md`](web-ui-checkpoint.md).

## Prioritized remaining work

The newly requested reload-to-update action (T120.10) and audit resource
names, separate read-only admin views, and on-demand diffs (T120.11) are in
implementation. The user approved cross-owner read access only through the
separate admin views; ordinary ownership and mutation permissions remain intact.
The reviewed implementation checkpoint passes 303 Rust tests, four health tests,
136 frontend tests, formatting, Clippy, Svelte checking and the static build.
The exact realised v19 packages also pass core and read-only admin Chromium
journeys. Follow-up worker fixes still need their own package/PWA browser gate.
The active v8 preview source pin and temporary-override cleanup are complete;
production and nixbot kept their process identities. See the current checkpoint
for exact artifact provenance.

1. Pass CI for the exact committed integration checkpoint and publish/pin the
   combined feature candidate. The v8 live rollout is verified; keep package
   evidence distinct from committed source and live state. Exact-source CI and
   pin/publication are still open.
2. Complete the approved synthetic journey on the physical phone. Fresh
   preflight and fixture/CDP endpoint probes passed, but the one full attempt
   stopped at the first sign-in tap because required trusted touchstart and
   touchend events were not observed on the target. No app sign-in or workflow
   assertions passed, and no cause is established. Cleanup completed without
   touching unrelated mappings or tabs. This is not an accepted phone journey.
3. Complete the native screen-reader journey on the synthetic app, including
   labels, errors, navigation, and dialog changes. Orca run19 verified the
   owned Firefox window was active and native search click/focus/input worked,
   but Orca did not announce the expected search label. Later route, reader,
   editor, dialog, and alert checks did not run. Accepted Firefox
   `native-zoom-31` checks do not establish screen-reader behavior.
4. Keep contrast follow-up bounded to representative rendered coverage. The
   measured contrast-control overlay is included in the v8 package candidate; its
   gate passes; it does not cover every component/state or constitute full
   WCAG coverage.
5. Scripted-session lab INP passes at 32 ms across five assessable samples.
   Broader device/browser coverage and field measurements are follow-up; lab
   values do not claim production p75.
6. Finish the cleanup/consumer decision before deleting legacy Rust templates,
   aliases, styles, scripts, or the root-scope legacy worker. Their continued
   presence is a conditional cleanup item, not a demonstrated failure of the
   new `/app` workflows. Preserve Android v1, `/projects/import`, `/api/v1`,
   `/media`, and shared Rust services. Production cutover remains separately
   authorized.

No missing T120.1–T120.8 product workflow was established by this static audit.
The remaining gaps include T120.10 and T120.11, exact-checkpoint CI/publication, physical-device
behavior, complete native screen-reader acceptance, and conditional cleanup
evidence. The v8 package, core/admin journeys, and live switch are verified.
The scripted-session lab INP target and scoped Firefox `native-zoom-31` checks
pass. Complete screen-reader and physical-device acceptance remain open. This
audit does not mark T120 or any TODO item complete.
