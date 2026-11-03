# Rust Web UI Rewrite Brief

## Goal

Replace the current Rust browser UI with a fresh, fast, smartphone-first web
application for managing knitting projects and yarn. The work is a deliberate
product UI redesign, not a visual reskin of the old Python web pages. Existing
web URLs, page structure, and interaction details may change. Preserve useful
product workflows and security guarantees; do not preserve web compatibility
for its own sake.

The Android client and `/api/v1/` contract are separate. Keep API v1 behavior
stable for Android while the new browser uses a purpose-built `/api/v2/`
contract. Do not cut features that exist today without recording the product
decision and getting the user’s approval.

Android preparation now runs in parallel under T120.24 / [SNA-74](android/TODO.md).
Inventory native endpoints and DTOs, PAT onboarding, capability negotiation,
offline sync/replay, media and website/deep links against the implemented Rust
contracts. Record a phased transition and implement reviewed compatibility
slices; do not treat browser-session API v2 as a drop-in prefix replacement for
the native PAT API. Preserve queued writes, cached data and account isolation.

## Product and source map

The source of truth for current Rust behavior is the composed Axum application,
not `web.rs` alone:

- `rust/src/lib.rs` composes the browser, account, signup, audit, archive, OCR,
  import, API, media, and PWA routers.
- `rust/src/web.rs` implements project, yarn, category, and login pages,
  plus current project and yarn mutations.
- `rust/src/lib.rs` also exposes the current standalone gauge page/calculation.
- `rust/src/web_accounts.rs` implements account and administrator workflows.
- `rust/src/web_audit.rs` implements the administrator audit view.
- `rust/src/web_signup.rs` implements optional public signup.
- `rust/src/import_service.rs` implements `/imports`, preview, file preview,
  and project/yarn creation; `rust/templates/import.html` is its current UI.
- `rust/src/archive_service.rs` and `rust/src/ocr_service.rs` expose optional
  archive and OCR operations. `rust/src/pwa.rs` serves public PWA resources and
  a non-personalized offline fallback.
- `rust/templates/web.html`, `accounts.html`, `audit.html`, `gauge.html`,
  `signup.html`, `import.html`, and `offline.html` are the current page
  references. They are behavior evidence, not layout constraints.
- `docs/spec-and-implementation.md` describes the product; `REWRITE.md`
  describes Rust rewrite boundaries and security/product requirements.

Keep project and yarn pages consistent. A project detail is also a pattern
reader used while knitting, often on a phone with one hand; it must prioritize
legibility and quick return to the current step over dense metadata.

## Core journeys to preserve

### Everyday project work

1. Sign in and land in the project library. Search, filter by category, tags,
   and favorites, browse useful empty states, and open a project.
2. Read the project name, materials, needles, gauge/stitch sample, notes,
   instructions, ordered steps, and reference images. Follow linked yarns and
   source/archive links when present. Zoom a diagram or photograph without
   losing the current reading position. Print the complete instructions.
3. Create or edit a project, associate existing yarns, edit ordered steps,
   tags, instructions and notes, and understand whether changes were saved.
   Validation and conflict errors must preserve entered content. Warn before
   navigation can discard unsaved edits.
4. Add and remove a title image, step image, and attachment. Show file
   selection, upload progress, completion, and actionable failures. Keep image
   alt text and image ownership semantics.
5. Favorite, unfavorite, and delete projects, with confirmation for destructive
   actions and a clear return path.

### Everyday yarn work

1. Browse/search/filter yarn inventory and open an item with brand, colorway,
   dye lot, fiber, weight, length, needle recommendation, notes, and photos.
2. Create/edit yarn, add/remove photos, favorite/unfavorite, and delete with
   confirmation. Link yarns to projects and navigate back to the project.

### Secondary and settings journeys

- Manage categories; create, rename, and delete while communicating any
  validation or dependency errors.
- Import a project from a URL, pasted text, or HTML/text/Markdown/PDF/image
  file; preview and correct extracted fields before creating a project. Offer
  yarn creation when the import draft contains yarn details. Make optional AI
  enhancement explicit and opt-in. Keep source fetching SSRF-safe.
- Show pending, completion, and actionable failure states for imports and OCR;
  these currently use bounded synchronous requests. For the existing durable
  archive job, poll owner-scoped status only while its screen is active, with
  backoff and a retry limit. Add a status endpoint only if the view needs it.
  Do not create a generic job platform. Consider SSE only for a demonstrated
  benefit; WebSockets and collaborative editing are deferred.
- Use the standalone gauge calculator and any inline gauge assistance. Show
  invalid input and calculation results clearly.
- Account: change password, manage avatar, create an API token (show it only
  once), and revoke tokens. Sign out and handle session expiration.
- Admin: create users, enable/disable accounts, reset passwords, and inspect
  filtered/paginated audit events. Keep all admin navigation and actions behind
  authorization checks.
- Optional OCR and archive controls appear only when their capabilities are
  enabled. Provide clear busy, result, and failure states.
- Support English and German from the first UI release, including form errors,
  async feedback, accessibility labels, and browser-level empty/loading states.

## Interaction and visual direction

Initial phone layout (structure to validate in T120.1, not a pixel specification):

```text
Project library                 Pattern reader
Projects            Account     Back       Project actions
Search projects                 Project title / useful summary
Filters       Sort              Instructions / current step
Project photo + name            Readable text, reference image
Project photo + name            Previous step / Next step
Projects  Yarns  Create  More    Projects  Yarns  Create  More
```

- Design mobile-first for 320, 390, 768, and 1280 CSS-pixel viewports. Respect
  safe areas, virtual keyboards, zoom, and scroll restoration. Keep primary
  actions reachable; use a compact bottom navigation on phones for Projects,
  Yarns, Create, and More, with a clear desktop navigation/rail. Hide or group
  account and administrator links by role. Avoid stacked global toolbars on
  phones; put authenticated mobile language selection in Account settings and
  keep signed-out language controls in a compact footer.
- Use semantic HTML and plain CSS design tokens. Start with an opaque, calm
  light/dark system theme; use high-contrast surfaces and restrained color.
  Starting light-theme tokens: canvas `#f7f9fb`, surface `#ffffff`, text
  `#17242d`, secondary text `#566874`, blue accent `#185b96`, border `#cbd5df`.
  Use a native system sans-serif stack; start at 16px for controls and 18px for
  reading, with a reader measure of 60–70 characters. Actual project photos and
  yarn colors provide the visual interest. Validate contrast, matching dark
  tokens, and the palette on real content before building the full UI.
  Do not use glass, backdrop blur,
  translucent panels, or ornamental gradients.
- Use purposeful motion for state changes and navigation feedback, generally
  120–180 ms. Avoid continuous or decorative animation. Honor
  `prefers-reduced-motion: reduce` by removing nonessential movement.
- Target at least 44×44 CSS-pixel touch controls. Maintain visible focus,
  logical keyboard order, screen-reader labels, dialog focus handling, and
  readable contrast. Verify 200% zoom and narrow-screen reflow.
- Keep the pattern reader quiet and readable: comfortable text measure,
  adjustable text sizing, persistent step context, easy image zoom, and a print
  stylesheet/output that includes collapsed or otherwise hidden instructions.
- Provide skeletons only where they prevent layout shift. Prefer useful empty
  states with a direct next action. Distinguish no content, no search results,
  loading, stale content, permission denial, validation failure, conflict,
  network failure, and offline fallback.

## Proposed frontend and runtime boundary

Use Svelte 5, SvelteKit, TypeScript, and Vite with `@sveltejs/adapter-static`.
Build the browser application as static assets and have the existing Rust
binary serve those assets and the same-origin API. There is no Node process or
Python dependency in the deployed runtime. Prerender public/sign-in shells where
feasible, keep authenticated data out of generated HTML, and measure cold mobile
startup before choosing client-only rendering for every route. Use addressable client routes for
project lists/details/editors, yarn lists/details/editors, imports, account,
admin, audit, and tools. A client-side navigation miss may fall back to the
application shell only for known browser routes; `/api/*`, media, health, and
unknown asset requests must retain their real HTTP status and must never become
the SPA shell.

Use Bits UI selectively for accessible headless primitives such as dialogs,
menus, comboboxes, and select controls. Keep simple controls as native semantic
HTML. Avoid a heavy component system or animation suite. Centralize typed
`fetch` calls and response/error decoding in one API client. Add
TanStack Svelte Query only if demonstrated needs require cache invalidation or
deduplicated requests; do not build a bespoke cache framework.

Existing repository policy says frontend dependencies are vendored with
`vendir`. The Svelte/Vite build is an explicit exception: pin dependencies in
`package-lock.json`, build them into static assets in the package/CI pipeline,
and commit the lockfile. Do not vendor the full toolchain as individual browser
files. Ship the generated JS/CSS/assets; exclude Node, npm, source build tools,
and development dependencies from the final runtime image.

Keep the existing EN/DE PO catalogs as translation source of truth and generate
browser JSON at build time, extending the existing Rust catalog generator and
extraction/completeness checks to Svelte strings. No PO parser is needed in the
browser. The new browser translates stable API error codes rather than scraping
HTML messages. Reuse terminology without preserving the old layout or copy.

### Browser session and API boundary

- Use the app’s browser session cookie as `HttpOnly`, `Secure` when HTTPS is
  configured, and `SameSite=Lax`; never put bearer tokens or API tokens in
  `localStorage`, URLs, or JavaScript-readable persistent storage.
- Define pre-authentication CSRF bootstrap and JSON login explicitly, rather
  than submitting through the old HTML form. Use same-origin checks and a
  short-lived signed double-submit CSRF token for login; issue a browser-purpose
  session only after successful authentication. Preserve secure `__Host-`
  cookie naming, `Path=/`, and no `Domain` attribute. Session bootstrap may
  return user summary, capabilities and a CSRF token, never the session secret.
- Keep CSRF protection on every state-changing request. Handle `401` by
  clearing client session state, showing a clear expiry message, and returning
  to sign-in. Logout must revoke the browser session and clear client state.
- Define a stable v2 API contract before page implementation. Use predictable
  JSON errors with machine-readable `code`, user-safe `message`, and optional
  field errors. Cover `401`, `403`, ownership-safe `404`, validation `422`,
  conflict `409`, rate limiting, and server failures.
- Use bounded page-number pagination with deterministic order, page size and
  `has_more`; keep search/filter/page state in the URL. Include capability flags
  for optional integrations, media upload/delete, synchronous import preview,
  archive state, and account/admin actions. Avoid leaking whether a foreign user's
  resource exists.
- Auth/session and authenticated API responses use `Cache-Control: no-store`;
  do not enable permissive CORS. Rate-limit authentication and expensive work.
- Keep gauge calculation on the tested Rust helper; a small JSON endpoint can
  replace its HTML handler if needed. Do not duplicate the arithmetic in two
  languages merely to replace the form.
- Extract and reuse domain/service logic incrementally from current handlers.
  Do not implement v2 by making the Rust server call its own v1 router through
  `tower::ServiceExt`; do not rewrite the whole backend as a prerequisite for
  the UI.
- Preserve current API v1 semantics and tests for Android. Document v2
  versioning and error semantics alongside its schema/handlers.

### Resilience and performance

- PWA behavior caches only the public application shell and static assets. Do
  not persist authenticated API responses, private pages, or media in the
  service worker cache. Support in-memory draft state and warn about unsaved
  edits; do not claim offline writes or sync.
- Initial performance budgets: first-route JavaScript ≤150 KiB gzip and CSS
  ≤50 KiB gzip. Lazy-load editors, large media, and admin areas. Record
  controlled lab measurements and target LCP ≤2.5 s, INP ≤200 ms, and CLS ≤0.1;
  do not present lab targets as production p75 data.
- Set a restrictive Content Security Policy without `unsafe-eval` or
  `unsafe-inline`. Verify the actual generated fallback HTML and interactive
  components under Rust's HTTP CSP header. If SvelteKit needs bootstrap hashes,
  generate and integrate them into that header during the build; a meta CSP
  cannot relax a stricter HTTP policy. Keep uploaded and remote images on the established safe
  media/import paths. Do not weaken CSRF, authorization, upload limits, SSRF
  protections, or private cache headers to simplify UI integration.
- Develop and test with synthetic fixtures. After checks, deploy to the existing
  isolated Rust preview, retaining its separate storage and native application
  login. Protect any existing copied private data; do not overwrite it with
  fixtures. Production migration and cutover remain separately authorized.

## Backlog

Track the work as T120.1–T120.14 in `TODO.md`. Each slice owns a clear area and
lands as a small reviewable checkpoint. Coordinate under the repository’s
Concord workspace; the Luna implementation waves below own only their named
frontend area, while the parent integrates and reviews API, authorization,
performance, and acceptance decisions. Keep file claims disjoint before
parallel implementation. Luna agents implement the Rust API/service slices too;
the coordinator reviews their contracts and security-sensitive changes.

- [x] **T120.1 — UX/design and contract freeze.** Produce phone and desktop
  screen flows, reader/editor interaction notes, navigation and role model,
  loading/empty/error/upload state inventory, palette/type/spacing tokens, and
  v2 route/error/capability contract. Record approved scope decisions. Freeze
  contracts before page slices depend on them.
- [x] **T120.2 — Frontend foundation and auth shell.** Add Svelte 5/SvelteKit,
  TypeScript, Vite, static adapter, lockfile, build integration, typed API
  client, theme/responsive primitives, navigation shell, sign-in/signup,
  session expiry/logout, route guards, generated EN/DE catalogs, and test/lint scripts.
  Add a compact back chevron to the left of the logo, following native browser
  history. Hide it when there is no prior history entry; do not infer parents.
  Keep unsaved-change protection and the right-aligned mobile profile control.
  Rust serves built assets; deployed runtime remains Rust-only.
- [x] **T120.3 — Focused browser API v2.** Scoped Luna Rust slice: implement
  only the v2 operations the browser needs, with typed/schema documentation,
  shared domain services, cookie/CSRF auth, authorization, pagination,
  capabilities, predictable errors, and API tests. Leave v1/Android semantics
  intact. Do not add generalized endpoints without a concrete journey.
- [x] **T120.4 — Project library and reader.** Deliver searchable/filterable
  project library, favorites, pagination, empty/loading/error states, compact
  project summary, readable detail/step reader, linked yarns, image zoom,
  print-complete instructions, and optional OCR/archive states. Preserve
  ownership and not-found behavior.
- [x] **T120.5 — Project editor and media.** Deliver create/edit with validation,
  conflict preservation, unsaved-change warning, step ordering/editing,
  yarn association, tags, descriptions/notes, project title and step photos,
  attachments, accessible upload progress/retry/error/delete flows, and
  destructive-action confirmation.
- [x] **T120.6 — Yarns and categories.** Deliver yarn inventory search/filter,
  list/detail/create/edit/photo/favorite/delete flows, linked-project
  navigation, category create/rename/delete management, and shared responsive
  patterns with projects.
- [x] **T120.7 — Imports and jobs.** Deliver URL, pasted text, and file import
  entry; preview/edit extracted project and yarn fields; opt-in AI control;
  create project/yarn; and progress/success/failure/retry behavior for any
  synchronous imports/OCR and the existing asynchronous archive work. Keep
  SSRF/size/type boundaries; no new queue is required for import or OCR.
- [x] **T120.8 — Account, tools, admin, and i18n completion.** Deliver profile
  avatar, password, one-time API token reveal/revoke, standalone gauge, optional
  signup, admin user management, audit filter/pagination, role-gated navigation,
  complete English/German strings, and consistent async/validation feedback.
Feature workflows T120.1–T120.8 are delivered, including the explicit
action-availability contract and UI controls verified in canonical candidate
`72b1faa`. The current
[requirement/evidence audit](docs/web-ui-acceptance-audit.md) and
[deployment checkpoint](docs/web-ui-checkpoint.md) record its tests and source.
The overall rewrite remains open until T120.9 acceptance is complete.

- [ ] **T120.9 — Acceptance, preview, and cleanup.** Run focused Rust tests and
  Vitest logic/component tests, type/lint/build checks, then browser acceptance in Playwright on
  Chromium/Firefox/WebKit at phone/tablet/desktop sizes. Include real-phone
  browser acceptance, keyboard and screen-reader checks, 200% zoom, safe-area
  and soft-keyboard checks, print, theme, offline shell/cache inspection,
  performance bundle/lab reports, CSP/auth checks, and full journey smoke
  coverage. Fix issues, remove replaced Rust templates/styles/scripts only
  after route consumers are gone, update docs/screenshots, and report remaining
  limitations. Keep preview data isolated; do not cut over production.
  The current [legacy consumer review](docs/web-ui-legacy-cleanup.md) confirms
  active Android, Rust HTML and root-worker consumers, so asset removal is
  conditional on their retirement rather than a safe cleanup for this preview.

- [x] **T120.10 — Requested update activation.** Replace the close-all-tabs
  notice with a working reload-to-update action. Activation follows an explicit
  click and a bounded readiness handshake across open app tabs. Protect dirty
  forms and in-flight mutations before activation; cancel or time out cleanly
  when another tab cannot prepare. Prepared tabs may reload for that request;
  finding a new worker alone must not reload a tab. Keep cache contents public
  and retain the assets needed by older clients until their transition is
  confirmed. Cover clean, dirty, busy, multiple-tab, timeout and retry cases.
- [x] **T120.11 — Audit resource history.** Display project, yarn and category
  names with useful links. The user explicitly approved a separate read-only
  administrator view for resources belonging to other users; ordinary resource
  ownership checks, edit permissions and API v1 remain unchanged. Offer an
  on-demand diff for relevant recorded changes, including editable resource
  content. Store new typed changes and event-time names transactionally;
  project only recognized, bounded resource fields from compatible historical
  diffs. Never expose raw legacy details, authentication fields or arbitrary
  JSON. Deleted resources and events without sufficient history need honest
  fallback states rather than invented values. Test authorization, migration,
  rollback, historical projection and the read-only browser journey.

- [x] **T120.12 — Reusable resource cards.** Share project, yarn and category
  presentation across library lists, audit events and related resources inside
  project/yarn readers. Use compact thumbnail rows for dense contexts and the
  existing larger library layout where appropriate. Show names as keyboard
  accessible links, authorized lazy-loaded images and domain-icon fallbacks
  for missing or failed images. Audit links use the separate read-only admin
  routes, including links to recorded history for deleted resources. Images
  represent the current resource, not an event-time image snapshot. Keep
  favorite and other controls outside links, preserve phone spacing, and test
  cross-owner image authorization, deleted resources and related-resource links.

- [x] **T120.13 — Decouple frontend packaging from Rust compilation.** Split
  the frontend-independent Rust build from final web/server assembly so
  frontend-only changes do not rebuild Rust. Pass `corePackage` and `webPackage`
  through `nix/module.nix`; verify derivation dependencies and the default
  module package. The current preview's explicit package override is unaffected.

- [x] **T120.14 — Compact header and action icons.** Use the existing cute logo
  beside the title and a small version line. Put the profile avatar on the right,
  preserve dirty-form guards. Navigation now follows the revised T120.16 parent
  route specification. Add
  shared bounded icons beside action labels throughout imports, projects, yarns,
  account and administration. Verify narrow phone widths and keyboard names.

- [ ] **T120.15 — Reviewed reader and phone layout.** Implement the user's chosen
  single reading column with a compact cover/key-facts summary and collapsible
  materials. Recover mobile text width and make library filters readable without
  excessive height. Preserve content, step IDs, progress, media ownership and
  complete printing. Track remaining observations in
  [the design review follow-ups](docs/web-ui-review-followups.md).

### Ordered review work queue

The user's review instructions require verification after each step before the
next implementation step. The T120.15 reader checkpoint was already underway
when this order arrived; retain and verify it, then follow this queue:

1. **T120.16:** stable scrollbar gutter, shared `PageHeader`, three consistent
   left-aligned container widths, and a logical-parent header chevron. Reserve
   its slot on top-level pages so the logo never moves. Preserve dirty guards.
2. **T120.17:** shared Button variants (`primary`, `secondary`, `ghost`, `danger`),
   declared sizes, consistent geometry and hover/active/focus/disabled states;
   migrate every feature and remove conflicting local button styling. Use shared
   icon sizes/strokes and one aligned Create/More tile treatment.
3. **T120.18:** fixed-width, unwrapped row actions with wrapping text columns.
4. **T120.19:** project/yarn parity for navigation, delete placement, edit-only
   photo management, favorites and Cancel on forms.
5. **T120.20:** sticky Save/Cancel, separate danger zone, merged yarn picker and
   styled file controls. Review existing media-role API support before changing
   the upload interaction. Add viewport success/error feedback, consistent
   save-to-detail behavior, field-level errors, Ctrl/Cmd+S and shared confirmation
   dialogs; retain drafts and prevent duplicate writes under slow/offline requests.
6. **T120.21:** shared input height/radius and 16px input text.
7. **T120.15 completion:** finish the selected single-column reading presentation
   and show one primary pattern view while retaining access to all source content.
8. **T120.22–23:** responsive existing-mascot sign-in branding, followed by subtle
   reduced-motion-safe interaction feedback. Page morphs, Knit mode, counters and
   autosave remain separate proposals rather than prerequisites for this queue.

The subsequent simplification review adds **T120.25** at the same priority as
header/button consistency. After the shared controls are verified, deliver
content-first lists/details and compact chrome before branding/motion polish;
merge progressive form disclosure into T120.20. See the density budgets and
before/after checks in [the follow-ups](docs/web-ui-review-followups.md).

The shared-control checkpoint (T120.17) is verified in the canonical package
selected by `81fe420`: the six-route phone/desktop inventory covered 54 controls,
with no undersized targets or horizontal overflow; hover, keyboard focus and
layout-shift checks passed. Signup at 320px and functional project creation,
editing, conflicts and media also passed. The full core journey passes with
the `d8ed929` helper: its density failure was a reproduced resize/layout race,
fixed by waiting for two animation frames without changing the budgets.
T120.18's scoped attachment-name wrapping rule passes the 320/390/1280px
browser trial and source checks; it is deployed with T120.19 below.
Fixed action targets remain 44px; linked-yarn, tag and category text already
wraps. The separate narrow
YarnForm conflict-button layout belongs to the T120.20 form work and remains
unresolved.

T120.19 is implemented and deployed in the canonical package selected by
`23c2e03` (preview configuration `e7c5a9c86`). Core and yarn journeys, the EN/DE
component probe at 320/390/1280px, long attachment names, dirty Cancel behavior,
read-only detail photos/OCR, and deletion busy/error/retry checks passed against
the packaged UI. A later CI run exposed a native-dialog handling race in the
Cancel test; its deterministic harness correction is tracked separately and
must pass before the next rollout. Favorite controls share rendering only;
their pages retain resource/user/generation checks. Yarn detail photos become
read-only while the editor keeps uploads and removals. Project Cancel uses the
existing navigation guard. Yarn deletion moves to its editor to match project
deletion, preserving confirmation and busy/resource checks; the separate danger
zone follows in T120.20. Project print/text-size controls move into a native
disclosure. Detail overflow access to destructive actions remains a T120.25
follow-up and must preserve those same guards.

T120.20 is in progress. Luna scopes separate pure shared controls, the project
editor/picker, and the yarn editor. The coordinator owns shell feedback wiring,
translations and integration. Shared controls own presentation and native dialog
mechanics; pages retain authorization, resource/request identity, dirty drafts,
conflict resolution and all mutations. Browser Back/Forward and unload retain
their native dirty-draft confirmation behavior. Save/Cancel stays inside each
form, deletion moves to a separate danger zone, and feedback remains scoped to
the signed-in owner. No API, authentication or database migration is included.

The latest review fixes (T120.26) align the gauge calculator's optional count
caption, place Imports under Create, and give audit actions shared semantic
color badges. Audit entries reserve the diff action slot and use equal row
heights with consistent spacing. Source and translation checks pass; rendered
geometry, contrast and browser journeys remain rollout gates.

The coordinated header review replaces the separate chevron target with one
animated brand link (`328fce7`). The mark stays aligned while the chevron appears
within the unit. Nested pages retain the logical parent destination; top-level
pages link home. Minimum target size, dirty guards and reduced motion remain
acceptance gates. Browser checks must use this revised unit rather than the
superseded 44px empty parent slot.

T120.27 replaces the separate administrator project presentation with the normal
project reader. A read-only data adapter retains administrator resource/media
URLs and owner metadata, while suppressing editing, favorites, archive writes,
OCR and saved reading progress. Normal user routes keep their existing owner
checks. Source checks pass; cross-owner browser, media and layout verification
remain required before rollout.

Use the `ui-audit` inventory and motion scripts after each step on representative
synthetic fixtures. Compare header positions, role/size button signatures,
wrapping, hover feedback and layout shifts. Keep import/data repairs separate.

### Luna implementation waves and ownership

The paths below are proposed new ownership boundaries, finalized in T120.1.
Implementation has started. Checkboxes stay open until each slice passes its acceptance checks.

| Wave | Luna scopes (up to three in parallel) | Integration condition |
| --- | --- | --- |
| 1 | UX flows/tokens; browser API contracts/auth review; frontend build/packaging inventory | Coordinator reconciles contracts and one visual direction before feature work. |
| 2 | Foundation: `web/` config, shared components, client, auth routes; API: new `rust/src/api_v2/` and tests; packaging: scoped Nix/Docker/CI files | Shared types/paths frozen first; coordinator owns cross-router wiring and resolves shared-file edits. Use labeled mocks only until real endpoints pass. |
| 3 | Project list/reader routes; project editor/media routes; yarn/category routes | Agents consume frozen shared components/client. Each owns its route directories and corresponding tests, with no competing edits to the shell or client. |
| 4 | Imports/OCR/archive routes; account/tools/admin routes; browser acceptance/performance review | Coordinator integrates cross-feature fixes; API additions get a separate scoped Rust owner. |

`web/` is the proposed frontend source root. Shared design tokens, API schemas,
root routes, lockfiles and build configuration always have one named owner;
feature agents request changes instead of editing them concurrently. Commit
and push small checkpoints regularly. Each handoff reports changed files,
checks, screenshots when relevant, and known gaps. No feature is marked done
from mock-only tests.

## Acceptance criteria

- A user can complete every everyday and secondary journey listed above on a
  smartphone and desktop without relying on the legacy web UI.
- No current product workflow disappears silently; optional features are
  clearly capability-gated and existing API v1 remains compatible with
  Android.
- Every route has explicit loading, empty, error, forbidden/not-found, and
  signed-out behavior as relevant. Forms preserve input on validation,
  conflict, and recoverable network errors.
- Touch, keyboard, zoom, contrast, reduced-motion, and print behavior meet the
  interaction requirements above. Test assembled dialog/combobox focus and
  keyboard behavior; accessible primitives alone do not prove accessible flows.
  Both locales cover all user-facing text. Record cold startup under a stated
  mobile network/CPU profile as well as warm navigation and bundle sizes.
- Built output is static and served by Rust. APIs, media, health checks, and
  unknown paths never receive a misleading SPA fallback. No Node/Python
  runtime, local-storage token, private service-worker cache, unsafe CSP
  escape, external analytics, or unapproved production operation is introduced.
- Keep checks and preview evidence attached to each checkpoint. Use remote
  build resources for expensive Nix/Cargo/Playwright work as directed by
  `AGENTS.md`; do not run expensive builds on the agent host.

## Framework references

- [SvelteKit single-page apps](https://svelte.dev/docs/kit/single-page-apps)
- [SvelteKit static adapter](https://svelte.dev/docs/kit/adapter-static)
- [Bits UI introduction](https://bits-ui.com/docs/introduction)
- [Vite backend integration](https://vite.dev/guide/backend-integration)
- [Core Web Vitals targets and lab/field distinction](https://web.dev/articles/vitals)
- [WCAG 2.2 reference](https://www.w3.org/WAI/WCAG22/quickref/)
