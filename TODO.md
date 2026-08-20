# TODO

Execution-oriented backlog for Stricknani.

## Status Legend

- `todo`: not started
- `wip`: in progress
- `blocked`: waiting on a dependency/decision
- `done`: completed and merged

## Priority Rubric

- `P0`: bug or production risk, do first
- `P1`: high product impact
- `P2`: medium impact / tech debt
- `P3`: quality-of-life or cleanup

## Rules

- If a task is actively being worked on, mark it `wip` immediately.
- When marking `wip`, add agent identifier: `(PID: <pid>, AGENT: <name>)`
  - Example: `T3 | P2 | wip (PID: 12345, AGENT: explorer) | ux | ...`
- Keep only actionable, implementation-ready tasks here.
- Unknown changes may indicate another agent is running; check PID before proceeding.

## Now

| ID | Priority | Status | Area | Category | Summary |
| -- | -------- | ------ | ---- | -------- | ------- |
| T52 | P0 | done | security | bug | Add SSRF guard to import fetch layer (block private/loopback/link-local, re-validate redirects) |
| T54 | P0 | done | import | bug | Return friendly 4xx/502 on import fetch failures instead of 500 with raw error text |
| T99 | P3 | todo | web/ux | refactor | Overhaul the web UI look and feel further (scope needs user input on specifics beyond T88's M3 migration) |
| T100 | P2 | done | web/ux | refactor | Make search box inputs more rounded (pill-shaped) across list pages and the global search modal |
| T101 | P2 | done | web/ux | feat | Use real Material 3 cards (not plain list rows) consistently on both project/yarn list pages and their detail/view pages |
| T102 | P1 | done | web/ux | bug | Fix misplaced badges/icons caused by negative-offset utility classes missing from the static CSS bundle (admin shield badge, yarn-search icon, sidebar restore tab, vertical-centering transforms) |
| T103 | P1 | done | web/ux | bug | Fix project/yarn detail pages: sections couldn't actually be collapsed, and drop the two-column sidebar layout in favor of a single content column |
| T104 | P3 | done | web/ux | refactor | Replace the app logo's hover animation (flat blue circle + slight grow) with a cuter squash-and-stretch "boing" wobble fitting the yarn-ball mascot |

## Next

| ID | Priority | Status | Area | Category | Summary |
| -- | -------- | ------ | ---- | -------- | ------- |
| T39 | P0 | done | search | bug | Fix universal search (Ctrl-K) CSRF 500 error |
| T41 | P1 | done | ux | bug | Fix remaining print layout issues (Wayback buttons, footer, spacing) |
| T38 | P2 | done | ux | refactor | Improve print layout to save space and show only relevant content |
| T37 | P3 | done | ux | refactor | Standardize "instructions" header size to match other section headers |
| T36 | P2 | done | frontend | refactor | Minimize templated JS/CSS in favor of static loading |
| T34 | P2 | done | cli | refactor | Make --query flag positional in `stricknani-cli project show` |
| T35 | P2 | done | ux | refactor | Hide empty "other materials" widget on project view page |
| T18 | P1 | done | demo | feat | Improve demo assets with knitting-related images and content |
| T30 | P1 | done | cli | feat | Add `stricknani-cli project|yarn ID_OR_NAME` with pretty print and --json support |
| T31 | P0 | done | nix | feat | Add backup.enable, schedule, and retention settings to Nix module (enabled by default) |
| T45 | P1 | done | ux | bug | Fix printing bug: collapsed instructions not included in print output
| T44 | P2 | done | test | feat | Add comprehensive tests for printing features
| T43 | P2 | done | ux | refactor | Hide "yarns used" widget when no yarns are linked to project |
| T50 | P2 | done | ux | refactor | Unify PhotoSwipe UI for step images and gallery images
| T49 | P2 | done | ux | bug | Fix image completion menu arrow key scrolling
| T48 | P1 | done | demo | bug | Fix missing demo user profile picture (404 error)
| T47 | P2 | done | ux | refactor | Reformatting the "technical specs" section for better print layout
| T46 | P2 | done | cli | refactor | Improve stricknani-cli project export command arguments
| T1 | P4 | done | frontend/build | refactor | Replace runtime Tailwind with prebuilt static CSS bundle |
| T32 | P3 | done | frontend | feat | Implement offline mode (PWA) |
| T33 | P3 | done | frontend | feat | Add PWA installation capability |
| T51 | P2 | done | ux | refactor | Add line breaks between consecutive images in wysiwyg editor preview |
| T53 | P0 | done | security | bug | Fail-fast on default/unset SECRET_KEY (and CSRF_SECRET_KEY) in production |
| T55 | P0 | done | security | bug | Enforce upload size cap and set Image.MAX_IMAGE_PIXELS (memory-DoS / decompression bomb) |
| T56 | P1 | done | data-model | refactor | Add DB indexes on FK columns (Image.project_id/step_id, Step.project_id, Attachment.project_id, Category.user_id) |
| T57 | P1 | done | perf | refactor | Add Cache-Control immutable headers to /static and /media |
| T58 | P1 | done | security | feat | Add security-headers middleware (baseline CSP, nosniff, X-Frame-Options, Referrer-Policy, HSTS) and TrustedHostMiddleware (strict CSP → T71) |
| T59 | P1 | done | security | refactor | Media serving hardening: nosniff + Content-Disposition + block traces/imports + upload extension allowlist (per-object authz → T70) |
| T60 | P1 | done | test | bug | Reset config.TESTING in conftest teardown; add real CSRF enforcement test |
| T61 | P1 | done | api | bug | Add server-side gauge validation (gt=0, safe int parse) to prevent crafted-input 500s |
| T62 | P1 | done | a11y | bug | Enforce/render alt text on uploaded images (template + JS previews) |
| T63 | P1 | done | a11y | refactor | Add aria-labels to icon-only buttons and a skip-link/#main-content landmark |
| T64 | P2 | done | perf | feat | Paginate project & yarn lists; push favorite/name ordering into SQL |
| T65 | P2 | done | perf | refactor | Persist image width/height columns; stop PIL-opening every image on detail render |
| T66 | P2 | done | test | refactor | Add pytest-socket --disable-socket to lock in offline tests |
| T67 | P2 | done | build | refactor | Compile .mo catalogs at build time (Dockerfile + nix) instead of lazy runtime write |
| T68 | P2 | done | ci | feat | CI parity: add `nix develop -c just test` job, lint-template-js step, and coverage measurement |
| T69 | P2 | done | security | feat | Add login/signup rate limiting, password policy, and revocable sessions |
| T70 | P2 | done | security | feat | Per-object media authorization — serve /media through an ownership-checked route (deferred from T59) |
| T71 | P2 | done | security | refactor | Tighten CSP to a strict nonce-based policy after removing runtime Tailwind + inline JS (depends on T1/T36; deferred from T58) |
| T72 | P1 | done | build | bug | `nix flake check`/`nix build` fails: pyproject requires `curl-cffi>=0.15.0` but nixpkgs pins `python3.pkgs.curl-cffi` at 0.12.0 (pythonRuntimeDepsCheckHook rejects it); Docker/uv build is unaffected since it pulls curl-cffi from PyPI |
| T73 | P1 | done | security | bug | `stricknani/static/js/features/wysiwyg_editor.js` imports TipTap modules directly from `https://esm.sh/...`, violating AGENTS.md's no-CDN-links rule and already unreachable under T71's strict `script-src 'self'` CSP (module load will be blocked); vendor TipTap via `vendir.yml` per AGENTS.md |
| T74 | P1 | done | api | feat | Add `ApiToken` model + `/user/api-tokens` settings UI, `require_api_token` Bearer-auth dependency, and CSRF exemption for Bearer requests (backend foundation for the Android app; mirrors `android/TODO.md` SNA-1) |
| T75 | P1 | done | api | feat | Add versioned JSON API (`/api/v1/`): projects/yarns/categories CRUD, favorites, image/attachment upload, `GET /api/v1/meta` (backend foundation for the Android app; mirrors `android/TODO.md` SNA-2) |
| T76 | P1 | done | api | feat | Add `require_auth_or_api_token` dependency so `/media` serving accepts either the session cookie or a Bearer API token (backend foundation for the Android app; mirrors `android/TODO.md` SNA-4) |
| T77 | P1 | done | api | feat | Add delta-sync endpoints (`/api/v1/sync/{projects,yarns,categories}`) sourcing deletions from `AuditLog`, with opt-in bounded pagination (`limit`/opaque `cursor`) for projects and yarns (backend foundation for the Android app; mirrors `android/TODO.md` SNA-3) |
| T78 | P1 | done | test/ci | feat | Add disposable browser E2E tests for critical Stricknani user journeys, following the NetBox/Syncwich CI pattern |
| T79 | P2 | done | ci | feat | Capture named Stricknani browser screenshots in CI E2E runs and upload them as reviewable artifacts, following the NetBox/Syncwich screenshot pattern |
| T80 | P1 | done | android/test/ci | feat | Add disposable Android instrumentation E2E tests against a seeded Stricknani fixture, with PR smoke and manual full journeys |
| T81 | P1 | done | android/test | feat | Add focused Android Compose/instrumentation coverage for route, accessibility, dialog, loading/error, and offline UI states |
| T82 | P2 | done | android/ci | feat | Add manual Android screenshot capture CI for phone and tablet layouts, light/dark themes, and reviewable artifacts |
| T83 | P2 | done | test/ci | refactor | Split browser E2E into a fast pull-request smoke suite and a longer manual cache/offline journey |
| T84 | P2 | done | ci/test | refactor | Enforce the documented 80% Python coverage threshold in CI instead of only uploading a report |
| T85 | P2 | done | ci | refactor | Pin CI runtimes and E2E browser dependencies for reproducible web and Android verification |
| T86 | P2 | done | ci/build | refactor | Replace fixed container startup sleeps with health-readiness checks and cache disposable fixture images |
 | T87 | P3 | done | release/decision | feat | Prepare gated Google Play store-assets and publishing workflow; upload the first internal release and reviewed assets |
 | T88 | P2 | done | frontend/ux | refactor | Complete the web UI migration to a truly Material 3-native design |
| T89 | P2 | done | web/i18n | bug | Translate the login-page “Please sign in below” string |
| T90 | P1 | done | privacy/legal | feat | Publish a complete privacy policy for the web app and Android client |
| T91 | P2 | done | dev | bug | Make `just run` generate ephemeral runtime secrets when SECRET_KEY/CSRF_SECRET_KEY are unset, while preserving production fail-fast validation |
| T92 | P2 | done | web/ux | bug | Fix QR-code sizing so it preserves its square aspect ratio and display the generated `stricknani://` setup URL below it |
| T93 | P3 | done | web/ux | feat | Add a subtle, accessible hover animation to the Stricknani app logo in the top-left corner |
| T94 | P1 | done | web/ux | bug | Fix web UI regressions: repair the search bar styling and header arrow rendering |
| T95 | P1 | done | web/ux | bug | Repair shared dialog regressions introduced by the Material 3 migration, including import and create-user dialogs |
| T96 | P1 | done | web/ux | bug | Normalize admin user-profile icon sizing so avatars render consistently |
| T97 | P0 | done | security | bug | Normalize upload ingress through bounded reads and validated image storage |
| T98 | P0 | done | security/import | bug | Stream and SSRF-guard all remote image imports before buffering or persisting them |


## Done

| ID | Priority | Status | Area | Category | Summary |
| -- | -------- | ------ | ---- | -------- | ------- |
| T13 | P2 | done | reliability | refactor | Replace broad exception swallowing in import/parse paths with explicit error handling |
| T20 | P1 | done | cli | feat | Add comprehensive tests for CLI commands |
| T19 | P1 | done | cli | feat | Make CLI commands default to list when no subcommand is provided |
| T15 | P2 | done | data-model | refactor | Add DB invariant for a single primary yarn image and simplify fallback logic |
| T14 | P2 | done | security | bug | Simplify and harden CSRF token flow (single source of truth for token location) |
| T29 | P2 | done | ux | feat | Make the "instructions" block collapsible on project pages |
| T25 | P0 | done | dev | refactor | Replace DEBUG-based hot-reload injection with explicit AUTO_RELOAD variable |
| T26 | P0 | done | dev | feat | Add bug/feat/refactor/docs category to tasks and update todo.sh with filtering flags |
| T28 | P0 | done | dev | feat | Add `todo.sh TICKET_ID_OR_PARTIAL_NAME` to show task details |
| T12 | P1 | done | web/templating | refactor | Remove hidden DB/auth lookups from `render_template`; require explicit `current_user` context |
| T11 | P1 | done | i18n/web | refactor | Remove per-request global Jinja i18n mutation to avoid cross-request language bleed |
| T10 | P1 | done | projects | refactor | Extract shared create/update project import workflows to common services |
| T21 | P1 | done | import | feat | Merge import dialog states: show URL entry and file upload in a single unified view |
| T9 | P1 | done | import | refactor | Consolidate duplicated import/image-dedupe logic into a single reusable pipeline |
| T22 | P3 | done | dev | feat | Add JS auto-reload in dev mode: reload page when server restarts |
| T23 | P3 | done | dev | refactor | Improve run.sh: wait 2s static, then poll health endpoint with 20s timeout |
| T16 | P2 | done | ux | feat | Add markdown image autocomplete for `!` trigger in text fields |


## Task Details

### T45: Fix printing bug: collapsed instructions not included in print output

- **Area**: ux
- **Priority**: P1
- **Status**: done
- **Category**: bug
- **Description**:
  - When instructions are collapsed in the UI, they are not included in the print output
  - Print layout should always include instructions regardless of their collapsed state in the browser
  - This is a critical bug that affects the primary use case for printing
- **Root Cause**:
  - Likely caused by CSS `display: none` or similar properties being applied to collapsed content
  - Print media queries may not be overriding the collapsed state properly
  - JavaScript collapse state is being respected in print view when it shouldn't be
- **Implementation**:
  - Ensure print CSS forces instructions to be visible regardless of collapse state
  - Add specific print media query rules to override any collapse-related styling
  - Test both collapsed and expanded states to ensure instructions always print
  - Consider using `!important` in print CSS to override inline styles
- **Specific Fixes Needed**:
  - Update `stricknani/static/css/project_detail_print.css` to force instructions visibility
  - Add rules like: `.instructions-section { display: block !important; }`
  - Ensure any collapse-related classes are overridden in print view
  - Test with localStorage collapse state to ensure it doesn't affect printing
- **Files to Modify**:
  - `stricknani/static/css/project_detail_print.css` - main print CSS file
  - Possibly `stricknani/templates/projects/detail.html` if template logic affects printing
- **Testing**:
  - Test printing with instructions in both collapsed and expanded states
  - Verify instructions always appear in print output
  - Test with different collapse scenarios (localStorage states)
  - Ensure no regression in other print functionality

### T44: Add comprehensive tests for printing features

- **Area**: test
- **Priority**: P2
- **Status**: done
- **Category**: feat
- **Description**:
  - Add thorough test coverage for all printing-related features
  - Ensure print functionality works correctly and doesn't regress
  - Test both the general print layout and specific print features
- **Test Coverage Needed**:
  - General project detail page printing
  - Instructions-only printing feature
  - Print layout CSS and media queries
  - Conditional rendering of elements in print view
  - Print-specific template partials
  - Different project types (with/without yarns, images, etc.)
  - Mobile vs desktop print output
- **Types of Tests to Add**:
  - Unit tests for print-related template logic
  - Integration tests for print routes/endpoints
  - Functional tests for print button functionality
  - Visual regression tests for print layout (if possible)
  - Browser compatibility tests for print media queries
- **Implementation**:
  - Add pytest tests in `tests/` directory
  - Create test fixtures for different project scenarios
  - Test print-specific template rendering
  - Verify CSS media query behavior
  - Add tests for print-only features (instructions print, etc.)
  - Ensure tests cover edge cases (empty projects, projects with minimal content)
- **Files to Create/Modify**:
  - `tests/test_printing.py` - main printing tests
  - `tests/conftest.py` - add print-related fixtures
  - Possibly update existing project/yarn tests to include print scenarios
- **Testing Tools to Consider**:
  - pytest with appropriate plugins
  - Selenium/WebDriver for browser-based print testing
  - CSS coverage tools to ensure print styles are tested
  - Screenshot comparison for visual regression testing

### T43: Hide "yarns used" widget when no yarns are linked to project

- **Area**: ux
- **Priority**: P2
- **Status**: done
- **Category**: refactor
- **Description**:
  - Conditionally render the "yarns used" widget only when there are actually yarns linked to the project
  - Hide the widget when no yarns are associated with the project
  - Improve UI consistency by not showing empty sections
  - Similar to the existing pattern for hiding empty "other materials" widget
- **Implementation**:
  - Update the project detail template to check if yarns list is empty before rendering the widget
  - Add appropriate Jinja2 conditional logic: `{% if project.yarns %}` or similar
  - Ensure the change doesn't affect the edit/form views where the field should always be visible
  - Test with projects that have yarns, no yarns, and various edge cases
- **Files to Modify**:
  - `stricknani/templates/projects/detail.html` - main project detail template
  - Possibly `stricknani/templates/projects/_yarns_used.html` if it exists as a partial
- **Benefits**:
  - Cleaner UI with no empty sections
  - Consistent with other conditional rendering patterns in the app
  - Better user experience by only showing relevant information
  - Reduces visual clutter on project pages

### T50: Unify PhotoSwipe UI for step images and gallery images

- **Area**: ux
- **Priority**: P2
- **Status**: done
- **Category**: refactor
- **Description**:
  - Step images in PhotoSwipe UI are missing preview thumbnails and OCR button
  - Gallery images have full PhotoSwipe UI with previews and OCR functionality
  - Need to consolidate to a single shared PhotoSwipe implementation
- **Current Issues**:
  - Inconsistent UI between step images and gallery images
  - Step images lack preview thumbnails at bottom
  - OCR button is missing from step image PhotoSwipe
  - Code duplication between different PhotoSwipe implementations
  - Poor user experience due to inconsistent features
- **Implementation**:
  - Create a shared PhotoSwipe component/implementation
  - Ensure all image types (steps, gallery) use the same UI
  - Add missing preview thumbnails to step image PhotoSwipe
  - Add OCR button functionality to step image PhotoSwipe
  - Refactor to eliminate code duplication
- **Specific Changes Needed**:
  - Identify existing PhotoSwipe implementations
  - Create unified PhotoSwipe template/partial
  - Update step image handling to use shared implementation
  - Ensure OCR functionality works for step images
  - Add preview thumbnail generation for step images
  - Standardize PhotoSwipe configuration across all uses
- **Files to Modify**:
  - PhotoSwipe initialization JavaScript
  - Template files that render step images and gallery images
  - Possibly create shared PhotoSwipe partial template
  - CSS files for consistent styling
  - OCR functionality integration
- **Benefits**:
  - Consistent user experience across all image views
  - Reduced code duplication and maintenance burden
  - All image types get full PhotoSwipe features
  - Easier to add new features to all image types
  - Better maintainability and consistency

### T49: Fix image completion menu arrow key scrolling

- **Area**: ux
- **Priority**: P2
- **Status**: done
- **Category**: bug
- **Description**:
  - Image completion menu (triggered by "!") doesn't scroll with arrow keys
  - Mouse wheel scrolling works, but keyboard navigation is broken
  - Arrow keys should scroll the completion menu to reveal items below the visible area
- **Current Behavior**:
  - Arrow keys navigate through completion items but don't scroll the menu
  - Users can't see or select items that are below the visible portion
  - Mouse wheel scrolling works correctly
  - This creates a poor user experience for accessing many images
- **Expected Behavior**:
  - Arrow key navigation should automatically scroll the menu to keep selected item visible
  - Similar to how native select dropdowns or autocomplete menus work
  - Smooth scrolling experience when navigating through many options
- **Root Cause**:
  - Likely missing JavaScript event handlers for keyboard navigation
  - No scroll behavior tied to arrow key events
  - CSS overflow properties may not be properly configured
- **Implementation**:
  - Add JavaScript event listeners for arrow key navigation
  - Implement auto-scrolling logic to keep selected item in view
  - Ensure scroll container has proper CSS overflow properties
  - Test with various numbers of completion items
- **Files to Modify**:
  - JavaScript completion menu code (likely in `stricknani/static/js/`)
  - Possibly template files that render the completion menu
  - CSS files for completion menu styling
- **Technical Approach**:
  - Listen for `keydown` events on arrow keys
  - Calculate position of selected item relative to scroll container
  - Use `scrollTop` to adjust scroll position as needed
  - Ensure smooth animation for better UX
  - Handle edge cases (first/last items)

### T48: Fix missing demo user profile picture (404 error)

- **Area**: demo
- **Priority**: P1
- **Status**: done
- **Category**: bug
- **Description**:
  - Demo user's profile picture is missing, resulting in 404 error
  - GET request to `/media/thumbnails/users/1/thumb_20260210_153146_a6139a25.jpg` returns 404
  - This affects the visual appearance of demo projects and user interface
- **Root Cause**:
  - Missing profile image file in the media directory
  - Either the file was never created, was deleted, or the path is incorrect
  - Demo setup process may not be properly handling user profile images
- **Implementation**:
  - Verify the expected location and filename of the demo user profile picture
  - Ensure the image file exists in the correct media directory
  - Update demo seeding process to properly handle user profile images
  - Add validation to check for missing media files during demo setup
  - Consider using a fallback image or placeholder when profile picture is missing
- **Files to Check/Modify**:
  - `stricknani/scripts/seed_demo.py` - demo data seeding script
  - Media directory structure and file permissions
  - User profile image handling logic
  - Template fallback logic for missing images
- **Specific Fixes**:
  - Ensure demo user profile image file exists at expected path
  - Update seeding script to copy/create the profile image properly
  - Add error handling for missing media files
  - Consider using Gravatar or generated avatar as fallback
- **Testing**:
  - Verify profile picture displays correctly after fix
  - Test with fresh demo database setup
  - Ensure no 404 errors for user profile images
  - Test fallback behavior when image is missing

### T47: Reformatting the "technical specs" section for better print layout

- **Area**: ux
- **Priority**: P2
- **Status**: done
- **Category**: refactor
- **Description**:
  - Redesign the technical specifications section to be more space-efficient when printing
  - Current layout wastes significant room in print output
  - Optimize for readability while maximizing space utilization
- **Current Issues**:
  - Excessive white space and padding in print view
  - Inefficient use of horizontal and vertical space
  - Layout doesn't adapt well to print medium
  - Important information may be spread out unnecessarily
- **Implementation**:
  - Create compact, print-optimized layout for technical specs
  - Use multi-column or tabular layout for better space utilization
  - Reduce excessive padding and margins in print view
  - Ensure all technical information remains readable and accessible
  - Consider using smaller font sizes specifically for print
  - Group related specifications together logically
- **Specific Improvements**:
  - Replace verbose labels with abbreviations where appropriate (e.g., "Gauge" instead of "Recommended Gauge")
  - Use compact table layout instead of spaced-out div structure
  - Remove decorative elements that don't add value in print
  - Optimize line height and font size for print readability
  - Consider landscape orientation for wide technical spec tables
- **Files to Modify**:
  - `stricknani/static/css/project_detail_print.css` - print-specific styling
  - `stricknani/templates/projects/detail.html` - template structure
  - Possibly create print-specific partial template for technical specs
- **Design Goals**:
  - Maximize information density without sacrificing readability
  - Ensure all technical specifications fit on minimal pages
  - Maintain visual hierarchy and scanning ease
  - Keep print output professional and well-organized
  - Ensure consistency with other print-optimized sections

### T46: Improve stricknani-cli project export command arguments

- **Area**: cli
- **Priority**: P2
- **Status**: done
- **Category**: refactor
- **Description**:
  - Refactor the `stricknani-cli project export` command to improve usability
  - Remove unnecessary `--email` flag (project ID is sufficient)
  - Make project ID a positional argument instead of a flag
  - Support partial project name matching like `stricknani-cli project show`
- **Current Issues**:
  - Command requires `--email` flag which is redundant
  - Project identification is overly complex
  - Inconsistent with other CLI commands that use positional args and partial matching
- **Implementation**:
  - Remove `--email` parameter from the export command
  - Change project identifier from `--project-id` flag to positional argument
  - Add support for partial project name matching (fuzzy matching)
  - Update command signature: `stricknani-cli project export PROJECT_ID_OR_NAME`
  - Ensure backward compatibility or provide clear migration path
  - Update help text and documentation
- **Files to Modify**:
  - `stricknani/scripts/cli.py` - main CLI command definitions
  - Export command implementation (likely in same file or related module)
  - Help text and usage examples
  - Possibly update tests to reflect new command signature
- **Benefits**:
  - More intuitive command interface
  - Consistent with other CLI commands in the application
  - Simpler usage for common export scenarios
  - Better user experience with partial name matching
- **Example Changes**:
  - Before: `stricknani-cli project export --project-id 123 --email user@example.com`
  - After: `stricknani-cli project export 123` or `stricknani-cli project export "my project"`

### T1: Replace runtime Tailwind with prebuilt static CSS bundle

- **Primary Files**: `stricknani/templates/base.html`, `justfile`, `flake.nix`
- **Description**: Replace runtime Tailwind-in-browser usage with a prebuilt static CSS bundle for performance and easier CSP hardening.

### T32: Implement offline mode (PWA)

- **Area**: frontend
- **Priority**: P3
- **Status**: done
- **Category**: feat
- **Description**:
  - Add service worker for offline caching of static assets
  - Implement cache-first strategy for app shell and critical resources
  - Add offline detection and graceful degradation UI
  - Cache API responses for recently viewed projects/yarns
- **Implementation**:
  - Create service worker file with caching strategies
  - Register service worker in main JavaScript
  - Add manifest.json for PWA capabilities
  - Implement offline fallback pages
- **Exit Criteria**:
  - App loads and functions without network connection
  - Recently viewed content available offline
  - Clear offline/online status indicators

### T33: Add PWA installation capability

- **Area**: frontend
- **Priority**: P3
- **Status**: done
- **Category**: feat
- **Description**:
  - Add web app manifest with PWA configuration
  - Implement install prompt for supported browsers
  - Add install button in UI for manual installation
  - Configure splash screens and icons for various devices
- **Implementation**:
  - Create web app manifest (manifest.json)
  - Add beforeinstallprompt event handling
  - Implement install button component
  - Generate appropriate icons for different screen sizes
- **Exit Criteria**:
  - App can be installed to home screen on mobile devices
  - App launches in standalone mode when installed
  - Proper icons and splash screens displayed

### T51: Add line breaks between consecutive images in wysiwyg editor preview

- **Area**: ux
- **Priority**: P2
- **Status**: done
- **Category**: refactor
- **Description**:
  - In the wysiwyg editor preview, consecutive images are displayed side by side
  - This doesn't match the final rendered view where each image appears on its own line
  - Need to modify the wysiwyg editor CSS to make consecutive images display on separate lines
  - This will make the preview more accurately reflect the final output
- **Current Behavior**:
  - Multiple consecutive images in wysiwyg editor appear horizontally aligned
  - Final view shows each image on its own line with proper spacing
  - Creates inconsistency between preview and final result
- **Expected Behavior**:
  - Wysiwyg editor preview should match final view layout
  - Each consecutive image should appear on its own line
  - Consistent spacing and layout between preview and final output
- **Implementation**:
  - Modify `.wysiwyg-image-node` CSS to use `display: block` instead of `display: inline-block`
  - Add appropriate margins to match final view spacing
  - Ensure the change doesn't break image editing functionality
  - Test with various image sizes and combinations
- **Files to Modify**:
  - `stricknani/static/css/app.css` - wysiwyg image node styling
  - Possibly adjust related wysiwyg CSS rules for consistency
- **Testing**:
  - Verify wysiwyg preview matches final view for consecutive images
  - Test image editing functionality (resize, delete, drag-and-drop)
  - Ensure no regression in other wysiwyg features
  - Test with different image sizes (sm, md, lg, xl)

### T16: Add markdown image autocomplete for `!` trigger in text fields

- **Area**: ux
- **Priority**: P2
- **Status**: done

### T18: Improve demo assets with knitting-related images and content

- **Area**: demo
- **Priority**: P1
- **Status**: done
- **Description**:
  - Update demo project images with knitting/crochet-related stock photos or AI-generated images
  - Improve demo project content (names, descriptions, notes) to be more authentic and varied
  - Current demo images are generic/random and lack relevance to the app's domain
- **Options**:
  - Source free stock images from sites like Unsplash, Pexels, or Pixabay
  - Generate fitting images using nano banana pro or similar AI tools
  - Ensure images are properly licensed for use
- **Exit Criteria**:
  - Demo projects have relevant, high-quality cover images
  - Demo content (descriptions, notes, yarn details) feels authentic

### T19: Make CLI commands default to list when no subcommand is provided

- **Area**: cli
- **Priority**: P3
- **Status**: done
- **Description**:
  - Commands like `stricknani-cli project` should implicitly run `stricknani-cli project list`
  - Same behavior for `stricknani-cli yarn` -> `stricknani-cli yarn list`
  - Apply to all entity-level CLI commands (project, yarn, etc.)
- **Implementation**:
  - Configure Click/Typer to use `list` as the default subcommand
  - Update CLI help text to reflect the default behavior
- **Examples**:
  - `stricknani-cli project` -> shows project list
  - `stricknani-cli yarn` -> shows yarn list

### T21: Merge import dialog states: show URL entry and file upload in a single unified view

- **Area**: import
- **Priority**: P1
- **Status**: done
- **Description**:
  - Combine the current tabbed/separate states for URL import and file upload into one unified dialog
  - Show both the URL text entry field and file upload widget simultaneously
  - Allow users to use either (or both) import methods in a single import session
- **Implementation**:
  - Update import dialog template to render both input methods side-by-side or stacked
  - Adjust backend to handle multiple import sources in one request
  - Ensure the UI is clean and intuitive when both options are visible
- **Benefits**:
  - Simpler UX without tab switching
  - More flexible import workflow

### T20: Add comprehensive tests for CLI commands

- **Area**: cli
- **Priority**: P3
- **Status**: done
- **Description**:
  - Write unit and integration tests for all CLI commands
  - Test both success and error paths (invalid arguments, missing resources, etc.)
  - Cover all subcommands (list, create, delete, etc.) for each entity
- **Tools**:
  - Use Click's built-in testing utilities or pytest-click
  - Mock database/file system operations where appropriate
- **Test Coverage**:
  - `stricknani-cli project` (list + all subcommands)
  - `stricknani-cli yarn` (list + all subcommands)
  - Help text and argument parsing
  - Exit codes for success/failure scenarios

### T22: Add JS auto-reload in dev mode: reload page when server restarts

- **Area**: dev
- **Priority**: P3
- **Status**: done
- **Description**:
  - Automatically reload the browser page when the development server restarts due to file changes
  - Improves developer workflow by avoiding manual page refreshes after code changes
- **Implementation Options**:
  - Use a WebSocket connection to notify the client when the server reloads
  - Implement a heartbeat/ping endpoint that the JS client polls for server status
  - Use existing livereload mechanisms (e.g., werkzeug's reloader events)
- **Implementation**:
  - Detect dev mode (debug=True or environment variable)
  - Add client-side code to listen for server restart events
  - Automatically call `window.location.reload()` when server restart is detected

### T23: Improve run.sh: wait 2s static, then poll health endpoint with 20s timeout

- **Area**: dev
- **Priority**: P3
- **Status**: done
- **Description**:
  - Modify `run.sh` to wait 2 seconds, then poll the health endpoint using curl
  - Keep trying until the health endpoint responds successfully
  - Give up and exit with error after 20 seconds total timeout
- **Implementation**:
  - Implement a loop with timeout that curls the health endpoint (e.g., `/health`)
  - Exit with error message if health check fails after 20 seconds
  - Exit successfully when health endpoint returns 200 OK

### T25: Replace DEBUG-based hot-reload injection with explicit AUTO_RELOAD variable

- **Area**: dev
- **Priority**: P0
- **Status**: done
- **Category**: refactor
- **Description**:
  - Currently hot-reload JS is injected when `DEBUG=True`
  - Introduce explicit `AUTO_RELOAD` environment variable to control this behavior
  - Decouple reload logic from debug mode

### T26: Add bug/feat/refactor/docs category to tasks and update todo.sh with filtering flags

- **Area**: dev
- **Priority**: P0
- **Status**: done
- **Category**: feat
- **Description**:
  - Add category column to TODO.md tables: bug, feat, refactor, or docs
  - Update todo.sh to accept cumulative filters: `--bug`, `--feat`, `--ref`, `--docs`
  - Example: `just todo --open --bug` lists open bugs, `just todo --done --feat` lists done features

### T27: Figure out how to lint/format Jinja-embedded JS/CSS files (form.js, etc.)

- **Area**: frontend
- **Priority**: P2
- **Status**: done
- **Category**: feat
- **Description**:
  - Currently cannot run biome/ruff/eslint on files like `form.js` that contain Jinja2 template syntax
  - Need a strategy to extract, lint/format, and validate embedded JS/CSS before committing
  - Options: extract-to-temp, use Jinja-aware linting, or migrate inline scripts to external files

### T28: Add `todo.sh TICKET_ID_OR_PARTIAL_NAME` to show task details

- **Area**: dev
- **Priority**: P0
- **Status**: done
- **Category**: feat
- **Description**:
  - Allow querying a specific task by ID (e.g., `just todo T25`) or partial name
  - Print full task details from Task Details section including description, implementation notes, etc.

### T29: Make the "instructions" block collapsible on project pages

- **Area**: ux
- **Priority**: P2
- **Status**: done
- **Category**: feat
- **Description**:
  - Add a toggle/collapse button to the instructions section on project view pages
  - Remember collapsed state in localStorage for consistent UX across sessions

### T30: Add `stricknani-cli project|yarn ID_OR_NAME` with pretty print and --json support

- **Area**: cli
- **Priority**: P1
- **Status**: done
- **Category**: feat
- **Description**:
  - Implement `stricknani-cli project PROJECT_ID_OR_PARTIAL_NAME` command
  - Implement `stricknani-cli yarn YARN_ID_OR_PARTIAL_NAME` command
  - Output pretty-printed details (name, description, stats, etc.)
  - Add `--json` flag for machine-readable JSON output
  - Match by ID or partial name (fuzzy matching similar to todo.sh)

### T31: Add database backup settings to Nix module

- **Area**: nix
- **Priority**: P0
- **Status**: done
- **Category**: feat
- **Description**:
  - Add `backup.enable` option to Nix module (enabled by default)
  - Add configurable `backup.schedule` (cron expression, default: daily)
  - Add `backup.retention` settings (keep last N backups, default: 7)
  - Implement automatic backup archive containing database + media with retention policy

### T34: Make --query flag positional in `stricknani-cli project show`

- **Area**: cli
- **Priority**: P2
- **Status**: done
- **Category**: refactor
- **Description**:
  - Change the `--query Q` flag to a positional argument in the `stricknani-cli project show` command
  - Update command signature from `stricknani-cli project show --query Q` to `stricknani-cli project show Q`
  - Maintain backward compatibility if possible, or update documentation
- **Implementation**:
  - Modify the Click command definition to use a positional argument instead of an option
  - Update help text and usage examples
  - Ensure the query parameter is still optional with appropriate default behavior

### T35: Hide empty "other materials" widget on project view page

- **Area**: ux
- **Priority**: P2
- **Status**: done
- **Category**: refactor
- **Description**:
  - Conditionally render the "other materials" widget only when there is content to display
  - Hide the widget when the field is empty, null, or contains only whitespace
  - Improve UI consistency by not showing empty sections
- **Implementation**:
  - Update the project detail template to check if other_materials has content before rendering
  - Add appropriate Jinja2 conditional logic in the template
  - Ensure the change doesn't affect the edit/form views where the field should always be visible

### T39: Fix universal search (Ctrl-K) CSRF 500 error

- **Area**: search
- **Priority**: P0
- **Status**: done
- **Category**: bug
- **Description**:
  - Universal search (bound to Ctrl-K) is failing with HTTP 500 error due to CSRF protection
  - Console shows: "Response Status Error Code 500 from /search/global"
  - Likely missing CSRF token in the HTMX request
- **Root Cause**:
  - HTMX POST request to `/search/global` is not including proper CSRF token
  - Server-side CSRF protection is rejecting the request
- **Implementation**:
  - Add CSRF token to the universal search HTMX request
  - Ensure the search endpoint properly handles CSRF tokens
  - Test both authenticated and unauthenticated search scenarios
  - Verify the fix works with the Ctrl-K keyboard shortcut
- **Files to Check**:
  - `stricknani/templates/shared/_search_bar.html` - search form template
  - `stricknani/routes/search.py` - search route handlers
  - CSRF token generation and validation logic
  - HTMX request configuration
- **Testing**:
  - Test Ctrl-K shortcut triggers search without errors
  - Verify search results are returned properly
  - Test both global search and specific entity searches
  - Ensure no regression in existing search functionality

### T38: Improve print layout to save space and show only relevant content

- **Area**: ux
- **Priority**: P2
- **Status**: done
- **Category**: refactor
- **Description**:
  - Optimize the print layout to eliminate wasted space and focus on essential content
  - Remove non-essential elements like audit logs and footers from print view
  - Reformat project details section to show only print-relevant information
  - Improve readability and space utilization for physical printing
- **Implementation**:
  - Create a dedicated print CSS file or media query section
  - Hide elements that don't make sense in print (Wayback buttons, interactive controls, etc.)
  - Reorganize content layout for better space utilization
  - Add print-specific styling to optimize text flow and image sizing
  - Ensure important metadata is prominently displayed
- **Specific Changes**:
  - Remove audit log section from print view
  - Remove footer content from print view
  - Hide interactive buttons and controls
  - Optimize project details section for print readability
  - Consider multi-column layout for better space usage
- **Files to Modify**:
  - `stricknani/static/css/project_detail_print.css` (create/update)
  - `stricknani/templates/projects/detail.html` (print media queries)
  - Possibly create a dedicated print template if significant restructuring needed

### T37: Standardize "instructions" header size to match other section headers

- **Area**: ux
- **Priority**: P3
- **Status**: done
- **Category**: refactor
- **Description**:
  - Reduce the size of the "instructions" header to match the styling of other section headers
  - Ensure visual consistency across all project detail section headers
  - Target headers like "Technical specifications", "Description", etc. as reference
- **Implementation**:
  - Identify the current CSS classes/styles applied to the "instructions" header
  - Update the template to use the same header classes as other sections
  - Ensure the change maintains proper hierarchy and readability
  - Test across different screen sizes and devices

### T36: Minimize templated JS/CSS in favor of static loading

- **Area**: frontend
- **Priority**: P2
- **Status**: done
- **Category**: refactor
- **Description**:
  - Reduce the amount of Jinja2 templating in JavaScript and CSS files
  - Extract templated content to minimal wrapper files that load static JS/CSS
  - Enable proper linting and formatting of frontend assets
  - Apply to both JavaScript (templates/*.js) and CSS files
- **Implementation**:
  - Identify essential templated variables that must be passed from backend to frontend
  - Create minimal template wrappers that inject only necessary dynamic content
  - Move bulk of JS/CSS logic to static files that can be properly linted/formatted
  - Update build process to handle the separation between templated and static assets
- **Benefits**:
  - Enable biome/ruff/eslint linting on frontend code
  - Improve code maintainability and developer experience
  - Better separation of concerns between backend templating and frontend logic
  - Easier to apply consistent formatting across the codebase

### T52: Add SSRF guard to import fetch layer

- **Area**: security
- **Priority**: P0
- **Status**: done
- **Category**: bug
- **Description**:
  - Import fetches arbitrary user-supplied URLs server-side (`fetch_url`, curl_cffi with Chrome impersonation, `follow_redirects=True`); `is_valid_import_url` only checks scheme + netloc.
  - An authenticated user can make the server fetch `http://169.254.169.254/...` (cloud metadata), `http://127.0.0.1`, `http://10.x` internal services; response text is reflected in the import result. Redirects to internal hosts are not re-validated.
- **Implementation**:
  - Add a shared guard: resolve the host, reject private/loopback/link-local/reserved/multicast IPs (`ipaddress` + `socket.getaddrinfo`), enforce http/https.
  - Apply inside `fetch_url` before the request AND after each redirect hop (disable auto-redirects and follow manually, validating each `Location`).
  - Apply the same guard in `ImageDownloader` (httpx path).
- **Files**: `stricknani/importing/fetch.py`, `stricknani/importing/images/validator.py`, `stricknani/importing/images/downloader.py`, `stricknani/utils/wayback.py`
- **Testing**: guard unit tests (internal IPs/hosts rejected, redirect-to-internal rejected, external allowed via mocked DNS).

### T53: Fail-fast on default/unset SECRET_KEY

- **Area**: security
- **Priority**: P0
- **Status**: done
- **Category**: bug
- **Description**:
  - `SECRET_KEY` falls back to a hardcoded `"dev-secret-key-change-in-production"` (`config.py:21`); session tokens are HS256 JWTs signed with it, so an operator who forgets to set it exposes a publicly-known signing key → forge any session incl. admin.
- **Implementation**:
  - In non-DEBUG/non-TESTING, refuse to start (raise at import/lifespan) if `SECRET_KEY` is unset or equals the default. Apply the same guard to `CSRF_SECRET_KEY` (also fixes its random-per-process value breaking CSRF across workers/restarts).
- **Files**: `stricknani/config.py`, `stricknani/main.py`

### T54: Friendly error on import fetch failures

- **Area**: import
- **Priority**: P0
- **Status**: done
- **Category**: bug
- **Description**:
  - `PatternImporter.fetch_and_parse` calls `fetch_url` with no try/except; `FetchError` (network/timeout/non-2xx) bubbles to the catch-all `except Exception` → HTTP 500 with `str(e)` leaked. The most common real case (dead/404 link) crashes instead of returning a clean 4xx/502. The graceful `URLSource`/`ImportPipeline` mapping is dead code in the web path.
- **Implementation**:
  - Catch `FetchError` at the import route boundary (or route endpoints through `URLSource`/`ImportPipeline`) and translate to `HTTPException(400/502/504)` using `FetchError.status_code`, without echoing the raw exception string.
- **Files**: `stricknani/importing/importer.py`, `stricknani/routes/projects.py`, `stricknani/routes/yarn.py`
- **Testing**: import of a URL that 404s/times out → 4xx/502, no raw error text in the response.

### T55: Upload size cap + Pillow decompression-bomb guard

- **Area**: security
- **Priority**: P0
- **Status**: done
- **Category**: bug
- **Description**:
  - `save_uploaded_file` does `await upload_file.read()` (whole file into memory, no cap); `Image.MAX_IMAGE_PIXELS` is never set. A large upload exhausts RAM; a crafted small "gigapixel" image is a decompression bomb opened by Pillow in `create_thumbnail`/`get_image_dimensions`/OCR.
- **Implementation**:
  - Enforce a `MAX_UPLOAD_BYTES` (reject oversized → 413, ideally streamed) across direct upload paths.
  - Set `Image.MAX_IMAGE_PIXELS` to a sane cap at startup and wrap opens in `try/except DecompressionBombError`.
  - Validate real content type via magic bytes before persisting.
- **Files**: `stricknani/utils/files.py`, `stricknani/services/projects/images.py`, `stricknani/services/projects/attachments.py`, `stricknani/main.py`

### T56: Add DB indexes on FK columns

- **Area**: data-model
- **Priority**: P1
- **Status**: done
- **Category**: refactor
- **Description**:
  - `Image.project_id`/`step_id`, `Step.project_id`, `Attachment.project_id`, `Category.user_id` have no `index=True`, so every `selectinload` (`WHERE project_id IN (...)`) full-scans. On the hottest list/detail paths.
- **Implementation**: add `index=True` (consider composite `(project_id, is_title_image)` on images) + one Alembic migration (`uv run alembic -c stricknani/alembic.ini revision -m ...`).
- **Files**: `stricknani/models/project.py`, `stricknani/models/category.py`, `stricknani/alembic/versions/`

### T57: Cache-Control on /static and /media

- **Area**: perf
- **Priority**: P1
- **Status**: done
- **Category**: refactor
- **Description**:
  - `/static` and `/media` are plain `StaticFiles` with no `Cache-Control max-age`, so the browser revalidates every asset (incl. a 400 KB font, 260 KB Tailwind JS, every thumbnail) on every navigation — a serial RTT each, worst on mobile.
- **Implementation**: subclass `StaticFiles` to add `Cache-Control: public, max-age=31536000, immutable` for content-addressed media/vendor assets (filenames are already unique), shorter TTL where appropriate.
- **Files**: `stricknani/main.py`

### T58: Security-headers middleware + TrustedHost

- **Area**: security
- **Priority**: P1
- **Status**: done
- **Category**: feat
- **Description**:
  - No CSP, `X-Content-Type-Options: nosniff`, `X-Frame-Options`, `Referrer-Policy`, or HSTS; `ALLOWED_HOSTS` is defined but `TrustedHostMiddleware` is never registered.
- **Implementation**: add a response-header middleware (nosniff, X-Frame-Options DENY, Referrer-Policy, HSTS when TLS, CSP baseline) and register `TrustedHostMiddleware(allowed_hosts=config.ALLOWED_HOSTS)`. Strict CSP depends on T1/T36.
- **Files**: `stricknani/main.py`

### T59: Authorize /media serving

- **Area**: security
- **Priority**: P1
- **Status**: done
- **Category**: refactor
- **Description**:
  - `/media` is an unauthenticated raw static mount → IDOR on private photos/PDF attachments/import-traces, and stored-XSS via a preserved `.svg`/`.html` upload extension served without `nosniff`.
- **Implementation**: serve media through an ownership-checked route (or at minimum deny `import-traces`/`imports`, force safe `Content-Type` + `Content-Disposition: attachment` + `nosniff`, and restrict stored extensions to an image allowlist).
- **Files**: `stricknani/main.py`, `stricknani/routes/`, `stricknani/utils/files.py`

### T60: Fix config.TESTING leak + add CSRF test

- **Area**: test
- **Priority**: P1
- **Status**: done
- **Category**: bug
- **Description**:
  - `tests/conftest.py:67` sets `config.TESTING = True` and never resets it; `test_login_cookie_not_secure_by_default` fails when run in isolation (order-dependent). The flag also short-circuits CSRF validation, so the entire CSRF path is untested.
- **Implementation**: reset `TESTING` in fixture teardown (or an autouse fixture); add a test with `TESTING=False` asserting POST without token → 403 and with token → success.
- **Files**: `tests/conftest.py`, `tests/` (new CSRF test)

### T61: Server-side gauge validation

- **Area**: api
- **Priority**: P1
- **Status**: done
- **Category**: bug
- **Description**:
  - `/gauge/calculate` divides by `pattern_gauge_stitches`/`rows` with no `gt=0` and does `int(pattern_row_count)` on a raw string; only client-side `min=1`. Crafted POST → `ZeroDivisionError`/`ValueError` → 500.
- **Implementation**: use `Annotated[int, Form(gt=0)]` for gauge fields and validated/optional parse for row count.
- **Files**: `stricknani/routes/gauge.py`, `stricknani/utils/gauge.py`

### T62: Enforce alt text on uploaded images

- **Area**: a11y
- **Priority**: P1
- **Status**: done
- **Category**: bug
- **Description**:
  - SPEC §11 makes alt text mandatory. `image_upload.html:74` renders `alt="{{ image.alt_text or '' }}"` and JS previews render `<img>` with no alt (`projects/form.js:1637,2182`, `yarn/form.js:161,767`).
- **Implementation**: make alt required in the upload flow; render a meaningful fallback (project/step name + index); add `alt` to the template-literal previews.
- **Files**: `stricknani/templates/macros/image_upload.html`, `stricknani/templates/projects/form.js`, `stricknani/templates/yarn/form.js`

### T63: aria-labels + skip-link

- **Area**: a11y
- **Priority**: P1
- **Status**: done
- **Category**: refactor
- **Description**:
  - Icon-only buttons lack accessible names (delete-image `image_upload.html:85`, mobile language `unified_navbar.html:296`, theme toggle `:285`); no skip-link and `<main>` has no id.
- **Implementation**: add `aria-label` (copy the WYSIWYG toolbar pattern); add `id="main-content"` on `<main>` and a focusable skip link in `base.html`; add `aria-label` to `<nav>`.
- **Files**: `stricknani/templates/macros/image_upload.html`, `stricknani/templates/shared/unified_navbar.html`, `stricknani/templates/base.html`

### T64: Paginate project & yarn lists

- **Area**: perf
- **Priority**: P2
- **Status**: done
- **Category**: feat
- **Description**:
  - `list_projects`/`list_yarns` select all of a user's rows with full eager loads and render all cards; also re-sort in Python after a SQL `ORDER BY`, defeating both the sort and pagination.
- **Implementation**: push favorite+name ordering into SQL; add LIMIT/OFFSET (or keyset) pagination with HTMX infinite-scroll on the existing `_list_partial`.
- **Files**: `stricknani/routes/projects.py`, `stricknani/routes/yarn.py`, list partials

### T65: Persist image dimensions

- **Area**: perf
- **Priority**: P2
- **Status**: done
- **Category**: refactor
- **Description**:
  - Project detail render calls `get_image_dimensions` per image, `PIL.Image.open`-ing every full-res file serially and uncached; also displays full-size originals in thumbnail-sized grid cells.
- **Implementation**: store `width`/`height` columns on `Image` at upload time (+ migration) and read from the row; point grid `<img src>` at `thumbnail_url` (keep the lightbox `<a href>` on the original).
- **Files**: `stricknani/models/project.py`, `stricknani/services/projects/images.py`, `stricknani/templates/projects/detail.html`, `stricknani/alembic/versions/`

### T66: Lock in offline tests with pytest-socket

- **Area**: test
- **Priority**: P2
- **Status**: done
- **Category**: refactor
- **Description**:
  - The suite is offline only by convention; a future test that forgets to mock `fetch_url`/AI/wayback would silently hit the network and flake CI.
- **Implementation**: add `pytest-socket` dev dep, `addopts = --disable-socket` in `[tool.pytest.ini_options]`, allow sockets only where a fixture explicitly opts in.
- **Files**: `pyproject.toml`, `tests/conftest.py`

### T67: Compile .mo at build time

- **Area**: build
- **Priority**: P2
- **Status**: done
- **Category**: refactor
- **Description**:
  - `.mo` files are gitignored and compiled lazily at first request into the package dir (fails on read-only rootfs → silent English; non-atomic write races). Neither Docker nor nix compiles them.
- **Implementation**: run `just i18n-compile` at Docker build and in the nix package; and/or compile once atomically in the FastAPI lifespan; add a read-only-fallback test.
- **Files**: `Dockerfile`, `nix/package.nix`, `stricknani/utils/i18n.py`

### T68: CI parity

- **Area**: ci
- **Priority**: P2
- **Status**: done
- **Category**: feat
- **Description**:
  - CI runs bare uv (not `nix develop`), so the devShell libstdc++ fix is never exercised; `lint-template-js`/`-format` run in `just check` but no workflow; no coverage measurement.
- **Implementation**: add a `nix develop -c just test` job (or flake `checks` entry), add the template-JS lint step to lint.yaml, add pytest-cov with a threshold.
- **Files**: `.github/workflows/`, `pyproject.toml`, `flake.nix`

### T69: Auth hardening (rate limit, password policy, revocable sessions)

- **Area**: security
- **Priority**: P2
- **Status**: done
- **Category**: feat
- **Description**:
  - No rate limiting/lockout on login/signup; no password policy (1-char accepted); sessions are non-revocable 1-week JWTs (logout only clears the cookie).
- **Implementation**: add per-IP/per-account rate limiting (e.g. slowapi); enforce a minimum password policy; add a token version/jti in the DB checked in `get_current_user`, bumped on password change/logout-all.
- **Files**: `stricknani/routes/auth.py`, `stricknani/utils/auth.py`, `stricknani/models/user.py`, `stricknani/config.py`

### T78: Add disposable browser E2E tests for critical user journeys

- **Area**: test/ci
- **Priority**: P1
- **Status**: done
- **Category**: feat
- **Description**:
  - The pytest suite exercises backend behavior but no real browser journey currently protects the primary web UI.
  - Add an isolated, disposable E2E environment and cover the highest-value authenticated flows: signup/login/logout, project and yarn creation/editing, detail-page navigation, and representative image/form interactions.
- **Implementation**:
  - Use Playwright (or the repository-standard browser E2E tool) with a dedicated `just` target and documented local invocation.
  - Start Stricknani against a temporary database/media directory, seed deterministic fixture data, and wait for `/healthz`; never point the suite at production or shared data.
  - Run the E2E journey in GitHub Actions alongside the existing checks, with browser dependencies cached or installed reproducibly.
  - Upload screenshots, traces/video, server logs, and test reports on failure; keep destructive cases limited to disposable fixture records.
- **Files**: `tests/e2e/`, `justfile`, `pyproject.toml`, `.github/workflows/`, and E2E setup documentation.
- **Testing**: verify the complete journey locally and in a hosted CI run, including an assertion that the disposable environment is torn down afterward.

### T79: Capture named browser screenshots in CI E2E runs

- **Area**: ci
- **Priority**: P2
- **Status**: done
- **Category**: feat
- **Description**:
  - Make the disposable browser E2E workflow useful for visual review and failure diagnosis by retaining screenshots from meaningful application states, not only test logs.
  - Keep all captures based on deterministic disposable fixture data; never capture production or personal records.
- **Implementation**:
  - Capture clearly named screenshots at key states such as authenticated home/list, project detail, yarn detail, and create/edit forms.
  - Pull the images into the GitHub Actions workspace and upload them on both successful and failed E2E runs alongside traces, logs, and test reports.
  - Add a local screenshot target that reuses the same fixture and journey where practical, and fail if a required state is blank or still loading.
  - Preserve stable viewport/theme naming so later visual comparisons can identify regressions without rerunning CI.
- **Files**: `tests/e2e/`, `justfile`, `.github/workflows/`, and screenshot documentation.
- **Testing**: verify the hosted workflow produces non-empty, reviewable screenshots for every named state and always tears down the disposable environment.

### T80: Add disposable Android instrumentation E2E tests

- **Area**: android/test/ci
- **Priority**: P1
- **Status**: done
- **Category**: feat
- **Description**:
  - The Android app has instrumentation-test dependencies but no `androidTest` sources or emulator journey; CI currently runs only JVM tests and APK assembly.
  - Add an isolated Android E2E environment that never uses the production Stricknani server or personal data.
- **Implementation**:
  - [x] Start a disposable Stricknani instance with temporary database/media storage, seed the existing deterministic demo user, API token, categories, projects, yarns, and representative images, then wait for `/healthz`.
  - [x] Add a short onboarding/sync/detail/settings smoke journey for pull requests and a manual full-lane scaffold covering cached browsing, search, and settings.
  - [x] Pass the fixture URL/token through instrumentation arguments, build before emulator startup, use a fresh API-34 emulator, and tear down the fixture with volumes on every exit path.
  - [x] Upload instrumentation reports, logcat, emulator screenshots, server logs, and host diagnostics on every run.
  - [x] Extend the manual full lane with an explicit network-offline cached browse and queued-edit assertion.
- **Files**: `android/app/src/androidTest/`, `.github/workflows/android-e2e.yaml`, `ci/stricknani/`, `android/justfile`, and Android E2E documentation.
- **Testing**: remote `just e2e-build rofl-13.brkn.lol` and `just check rofl-13.brkn.lol` pass; GitHub Actions must still run both lanes against the disposable fixture and confirm no production endpoint is contacted.
- **Completed**: Full hosted lane `32252063146` passed on 2026-08-19, including both cached-browsing journeys against the disposable seeded fixture.

### T81: Add focused Android Compose/instrumentation coverage

- **Area**: android/test
- **Priority**: P1
- **Status**: done
- **Category**: feat
- **Description**:
  - Pure JVM tests cannot catch Compose navigation, layout, semantics, dialog, accessibility, loading/error, or offline rendering regressions.
  - Add focused instrumentation coverage alongside the broader E2E journey, following the route-level coverage in NetBox and Syncwich.
- **Implementation**:
  - Cover onboarding validation, home/projects/yarns navigation, list/detail/editor states, search, gauge, settings/about, backup dialogs, image viewer, empty/loading/error states, and offline/cache-first rendering.
  - Assert stable accessibility labels and semantics for important controls, not only screenshots.
  - Keep tests deterministic and fixture-driven; isolate mutation-heavy or permission-gated paths from the PR smoke suite where appropriate.
- **Completed**:
  - Added focused route/accessibility, dialog, empty-state, sync-feedback, and offline-cache tests.
  - Added a manual `focused` Android E2E lane that runs the focused classes separately against the disposable fixture.
- **Files**: `android/app/src/androidTest/kotlin/`, Android test fixtures/helpers, and `android/justfile`.
- **Testing**: remote `just e2e-build rofl-13.brkn.lol` and `just check rofl-13.brkn.lol` pass; the API-34 emulator execution is owned by the manual GitHub Actions lane.

### T82: Add manual Android screenshot capture CI

- **Area**: android/ci
- **Priority**: P2
- **Status**: done
- **Category**: feat
- **Description**:
  - `screengrab` is already a declared Android test dependency, but Stricknani has no screenshot test, Fastlane configuration, or screenshot workflow.
  - Provide a repeatable visual-review path for the app's primary phone and tablet layouts.
- **Implementation**:
  - Add a manual GitHub Actions workflow using the same disposable seeded fixture as T80 and an emulator matrix for phone, 7-inch tablet, and 10-inch tablet profiles.
  - Capture named onboarding, home/list, project detail, yarn detail, settings, and offline states in light and dark themes.
  - Upload screenshots and instrumentation reports on success and failure, including a direct failure screenshot when the test journey aborts.
  - Add an optional `open_pr` path for committing reviewed screenshots to a stable update branch; keep Play Console mutation behind a separate explicit gate.
- **Completed**:
  - Added the manual six-job phone/7-inch tablet/10-inch tablet × light/dark workflow with disposable fixture teardown and diagnostics.
  - Added named onboarding, home, list/detail, settings, and offline screenshot instrumentation plus review documentation.
- **Files**: `.github/workflows/android-screenshots.yaml`, `android/app/src/androidTest/`, `fastlane/`, `android/justfile`, and screenshot documentation.
- **Testing**: remote `just e2e-build rofl-13.brkn.lol` and `just check rofl-13.brkn.lol` pass; the hosted matrix still needs one manual dispatch to verify rendered tablet captures.

### T83: Split browser E2E into smoke and full suites

- **Area**: test/ci
- **Priority**: P2
- **Status**: done
- **Category**: refactor
- **Description**:
  - T78 currently describes one browser E2E journey; the CI cost and coverage need separate pull-request and deeper verification paths.
- **Implementation**:
  - Run a short deterministic login/list/detail/settings smoke journey on pull requests with path filters for browser-test and app changes.
  - Keep the longer manual journey for project/yarn editing, image/form interactions, search, import boundaries, responsive layouts, and offline/cache behavior.
  - Add workflow concurrency cancellation, explicit timeouts, deterministic fixture teardown, and artifacts on every run.
- **Files**: `tests/e2e/`, `.github/workflows/`, `justfile`, and E2E documentation.
- **Testing**: verify the smoke suite is fast and mutation-safe on pull requests while the full suite remains runnable by manual dispatch.

### T84: Enforce the documented Python coverage threshold

- **Area**: ci/test
- **Priority**: P2
- **Status**: done
- **Category**: refactor
- **Description**:
  - The product specification requires at least 80% coverage, but CI currently only generates and uploads `coverage.xml` without enforcing a minimum.
- **Implementation**:
  - Add `--cov-fail-under=80` to the canonical test command or configure the threshold in `pyproject.toml` so local and CI checks agree.
  - Keep the coverage artifact and make failures identify the measured total and the missing threshold.
- **Files**: `pyproject.toml`, `justfile`, `.github/workflows/test.yaml`, and `docs/spec-and-implementation.md` if the chosen threshold or scope changes.
- **Testing**: verify CI fails below the threshold and passes with the current suite after accounting for intentionally excluded modules. The primary Python 3.14 job enforces the 80% gate; the separate Nix job runs the full suite as a test-only parity lane because its Python 3.13 coverage run reports different line data for the same passing tests.

### T85: Pin CI runtimes and E2E browser dependencies

- **Area**: ci
- **Priority**: P2
- **Status**: done
- **Category**: refactor
- **Description**:
  - Web CI currently uses floating `uv latest` and Python `3.x`; browser and emulator verification should not silently change underneath the test suite.
- **Implementation**:
  - Pin the supported Python and uv versions, Playwright/browser versions, and any Ruby/Fastlane tooling used by Android screenshots.
  - Cache dependency downloads and document the version update path so upgrades remain intentional and reviewable.
- **Files**: `.github/workflows/`, `pyproject.toml`, `uv.lock`, `package.json`/lockfiles for E2E tooling, `fastlane/`, and setup documentation.
- **Testing**: run all affected CI lanes from a clean cache and confirm they use the declared versions.

### T86: Harden container readiness and disposable fixture startup

- **Area**: ci/build
- **Priority**: P2
- **Status**: done
- **Category**: refactor
- **Description**:
  - Container verification relies on a fixed ten-second sleep, and future disposable E2E/screenshot fixtures will repeatedly pull the same images without a cache strategy.
- **Implementation**:
  - Replace fixed sleeps with bounded `/healthz` polling that reports useful logs on timeout.
  - Pin fixture image versions, cache large Docker images in GitHub Actions where practical, use `docker compose --wait` for disposable services, and always remove volumes/orphans.
- **Files**: `.github/workflows/container.yaml`, `.github/workflows/`, `ci/`, and fixture startup scripts.
- **Testing**: verify cold and cached CI runs, delayed startup, health timeout diagnostics, and cleanup after both success and failure.

### T87: Decide on Google Play distribution

- **Area**: release/decision
- **Priority**: P3
- **Status**: done
- **Category**: feat
- **Description**:
  - Android release signing, GitHub Releases, and Obtainium are implemented; Google Play publishing is approved for staged internal testing.
- **Implementation**:
  - Decide whether the self-hosted audience warrants Play distribution. If approved, prepare store copy, privacy/data-safety information, content rating, signed AAB publication, and the gated screenshot/store-assets workflow from T82.
  - Keep publishing disabled by default and require an explicit repository-level gate and credentials check before any upload.
- **Files**: `android/TODO.md`, `.github/workflows/`, `fastlane/`, `android/README.md`, and store metadata.
- **Testing**: validate a signed AAB and store assets without publishing first; publish only after the explicit product decision and gate are enabled.
- **Progress**: Configured the shared Play service-account secret and publication gate, committed 24
  reviewed screenshots, uploaded version `0.1.0` (code `1`) to the internal track in workflow
  `32291620107`, and uploaded the icon, feature graphic, and 24 screenshots in workflow
  `32292338197`. Production promotion remains a deliberate Play Console action.

### T88: Migrate the web UI to Material 3 Expressive

- **Area**: frontend/ux
- **Priority**: P2
- **Status**: done
- **Category**: refactor
- **Description**:
  - Replace the current DaisyUI/Tailwind visual language with a Material Design 3 / Material 3
    Expressive direction across the web UI.
  - Keep Projects and Yarns visually and behaviorally consistent while the migration is staged.
- **Implementation**:
  - Define the Material 3 color, typography, shape, elevation, state-layer, and responsive layout
    tokens for light and dark themes.
  - Replace shared navigation, buttons, forms, cards, dialogs, lists, and feedback components
    incrementally, starting from shared templates/macros so individual pages inherit the system.
  - Keep all frontend dependencies vendored through `vendir.yml`; prefer existing vanilla JS and
    avoid introducing a client-side framework unless a concrete interaction requires it.
  - Preserve accessibility semantics, keyboard navigation, responsive behavior, translations,
    and the existing project/yarn UI parity throughout the migration.
- **Current progress**:
  - Completed the framework-free M3 component layer and restored the remaining layout/control
    primitives used by project, yarn, admin, import, and account pages without reintroducing a
    runtime CSS framework.
  - Repaired search positioning, responsive native dialogs, menu/form controls, detail navigation,
    and consistent avatar sizing; added semantic color/state aliases for the remaining feature
    fragments.
  - Verified desktop, responsive, light/dark, CRUD, import-dialog, admin-dialog, and avatar paths
    through the browser E2E suites.
- **Files**: `stricknani/templates/`, `stricknani/static/css/`, `stricknani/static/js/`,
  `vendir.yml`, and both translation catalogs as UI strings change.
- **Testing**: add/update browser E2E and screenshot coverage for light/dark themes and key
  responsive viewports; run i18n checks, frontend lint/format, and the full web test suite.
- **Completed**: Added the shared M3 compatibility/component fixes and browser assertions for search,
  import/create-user dialogs, and admin avatar dimensions. `just e2e-smoke`, `just e2e-full`, and
  `tests/test_health.py` pass.

### T89: Translate the login-page “Please sign in below” string

- **Area**: web/i18n
- **Priority**: P2
- **Status**: done
- **Category**: bug
- **Description**:
  - The login page renders “Please sign in below” without passing it through the translation
    catalog, so German users see English text.
- **Implementation**:
  - Wrap the template string in the existing translation helper.
  - Add the English and German catalog entries and regenerate compiled catalogs.
- **Files**: login template and both locale catalogs.
- **Testing**: run the i18n update/compile/check commands and assert the rendered login page uses the
  translated string.
- **Completed**: The template and German catalog already contained the translation; added a
  request-scoped regression test proving the rendered login page uses it and does not leak the
  English source string.

### T90: Publish a complete privacy policy for the web app and Android client

- **Area**: privacy/legal
- **Priority**: P1
- **Status**: done
- **Category**: feat
- **Description**:
  - Provide a clear, user-facing privacy policy covering the hosted web app, Android client,
    authentication/API tokens, offline cache, synchronization, uploaded media, logs, and
    self-hosted deployments.
- **Implementation**:
  - Publish the policy at a stable web route and link it from the login/signup experience and
    footer; keep the Android-facing copy aligned with `android/PRIVACY.md`.
  - Document data collected, purpose, retention, third-party services, controller/contact,
    deletion/export rights, security limitations, and how self-hosted operators adapt the policy.
  - Add German coverage or an explicit language/fallback policy, and avoid claiming guarantees the
    deployment cannot enforce.
- **Files**: privacy-policy template/route, footer/auth templates, locale catalogs, `android/PRIVACY.md`,
  and related documentation.
- **Testing**: assert the route is reachable without authentication, linked from public entry
  points, translated/fallback-safe, and covered by web/browser smoke tests.
- **Completed**: Added the public `/privacy` page, linked it from the shared footer and login/
  signup experience, documented hosted/self-hosted web behavior alongside the existing Android
  policy, and added English/German rendering and public-link regression tests.

### T97: Normalize upload ingress through bounded reads and validated image storage

- **Area**: security
- **Priority**: P0
- **Status**: done
- **Category**: bug
- **Description**:
  - The backend audit found that yarn photos and user/admin profile images bypassed the shared
    image validator and saved arbitrary upload bytes using the client-provided extension.
  - Several project, attachment, import, and crop-image paths also read uploads without a bounded
    size check, leaving memory and thumbnailing failures dependent on untrusted input.
- **Implementation**:
  - Added a configurable `MAX_UPLOAD_BYTES` limit and chunked upload reader.
  - Routed image upload paths through content validation and canonical safe image extensions, with
    explicit 400 responses for invalid images and 413 responses for oversized uploads.
  - Applied bounded reads to project images, attachments, imports, and crop-image operations.
- **Testing**: Added regression coverage for invalid avatar/yarn-photo uploads and the size cap;
  the focused user/API/yarn/health suite passes (28 tests), along with Ruff and mypy.

### T98: Stream and SSRF-guard all remote image imports before buffering or persisting them

- **Area**: security/import
- **Priority**: P0
- **Status**: done
- **Category**: bug
- **Description**:
  - The backend audit found that the shared remote-image downloader checked its size limit only
    after `httpx` had already buffered the complete response.
  - Garnstudio inline-symbol localization used a separate redirect-following client without the
    shared SSRF/content validation path, and retained URL-derived extensions.
- **Implementation**:
  - Stream remote image responses with a content-length and chunked-body cap, while re-validating
    every redirect target before connecting.
  - Route inline-symbol localization through the hardened downloader and persist only validated
    images with canonical extensions.
- **Testing**: Existing import, SSRF, and health coverage passes (25 tests in the focused run),
  including the updated streamed-response import fixture.

### T99: Overhaul the web UI look and feel further

- **Area**: web/ux
- **Priority**: P3
- **Status**: todo
- **Category**: refactor
- **Description**:
  - User flagged that the web UI should get another visual pass beyond the T88 Material 3 migration.
  - No concrete specifics given yet beyond the two items split out as T100 and T101 — this ticket
    is a placeholder for whatever else the user wants once they scope it further (e.g. spacing
    rhythm, color/elevation tuning, typography).
  - Blocked on user input before implementation starts.

### T100: Make search box inputs more rounded across list pages and global search

- **Area**: web/ux
- **Priority**: P2
- **Status**: done
- **Category**: refactor
- **Description**:
  - User wants the search boxes to look more rounded/pill-shaped.
  - Root cause: `stricknani/static/css/app.css`'s generic `.md3-text-field { border-radius: 0.25rem
    0.25rem 0 0; }` rule loads after `material.css` and has equal (single-class) specificity to
    `material.css`'s `.md3-search-bar__input` pill-radius rule, so it won by source order and the
    list-page search bars rendered with only slightly-rounded top corners instead of the intended
    full stadium shape.
- **Implementation**:
  - Rescoped `material.css`'s rule to `.md3-search-bar__control .md3-search-bar__input` (raises
    specificity above the single-class `app.css` rule regardless of load order) and set
    `border-radius: 999px` for an explicit full pill, verified in-browser (light + dark) on the
    project and yarn list pages.
  - Left the global search modal (`base.html` `#globalSearchModal`) and regular form `.md3-text-field`
    inputs untouched — those intentionally keep the M3 filled-field look; only the standalone
    list-page search bar was in scope.
- **Testing**: `just lint-css` (pre-existing unrelated `.md3-chip` specificity warning only, no new
  issues; this change actually resolved one of the two prior warnings).

### T101: Use real Material 3 cards consistently on project/yarn list and detail pages

- **Area**: web/ux
- **Priority**: P2
- **Status**: done
- **Category**: feat
- **Description**:
  - User wants "real material cards" on the project/yarn list *and* view (detail) pages.
- **Findings** (verified in-browser via seeded demo data, light + dark):
  - List pages already render `.md3-feature-card` (`macros/cards.html` `list_card` macro) with
    border, `--md-sys-shape-extra-large` radius, `surface-container-low` background, and
    `elevation-1` shadow plus a hover lift.
  - Detail pages (`projects/detail.html`, `yarn/detail.html`) already render every section (Gallery,
    Description, Technical Specifications, Instructions/step cards, Notes, Linked Projects, Audit
    Log) as `.md3-disclosure` panels, which share the identical card treatment (border, extra-large
    radius, `surface-container-low` background, `elevation-1` shadow) — they're real M3 cards, just
    also collapsible.
  - No plain/unstyled row-based list or detail rendering exists; there is no alternate table/row view
    to convert. Concluding this was already satisfied by the T88 migration; no code change made.
  - Nested widgets inside a detail card (e.g. "Yarns Used" row, "Linked Projects" row) are
    intentionally plain rows rather than nested elevated cards — stacking two elevated surfaces is
    not idiomatic Material 3, so this was left as-is.

### T102: Fix misplaced badges/icons from negative-offset utility classes missing in static CSS

- **Area**: web/ux
- **Priority**: P1
- **Status**: done
- **Category**: bug
- **Description**:
  - User reported the admin badge (shield icon overlay) on `admin/users` was misplaced, and suspected
    more badges/icons elsewhere had the same problem.
  - Root cause: the T1 migration off runtime Tailwind to a static utility bundle only ported the
    *positive* offset/margin/transform utilities. Templates still reference several Tailwind-style
    *negative*-prefixed classes that were never added to `material.css`: `-bottom-1`, `-right-1`,
    `-mr-4`, and `-translate-y-1/2`. A missing class is simply not applied (silently), so any element
    relying on one keeps its default/no-op position instead of erroring — which is why this went
    unnoticed rather than breaking loudly.
  - Audited every template's `class="..."` attribute for this pattern (any token matching
    `-(top|bottom|left|right|inset|translate-x|translate-y|m[trblxy]?)-...`); these four were the only
    negative-prefixed tokens in use anywhere in `stricknani/templates/`.
- **Implementation** (all in `stricknani/static/css/material.css` unless noted):
  - Added `.-bottom-1` / `.-right-1` (±0.25rem) — fixes the admin avatar's shield badge
    (`admin/_user_card.html`), which previously had no offset at all and sat at its default
    (top-left-ish) position instead of pinned to the avatar's bottom-right corner.
  - Added `.-translate-y-1\/2` alongside the existing `.translate-y-1\/2` (both `translateY(-50%)`)
    — fixes vertical centering for: the swipe-nav arrows (`base.html`), and an inline search icon
    (`projects/form.html`'s yarn picker).
  - Added `.-mr-4` (`margin-right: -1rem`) — fixes the project/yarn form's collapsed-sidebar "restore
    details" tab (`projects/form.html`, `yarn/form.html`), which pulls itself half off the viewport
    edge.
  - Separately found and fixed a related bug while verifying the yarn-search icon fix in-browser: the
    input itself (`projects/form.html`'s `#yarn_search`) had no left padding reserved for the icon, so
    even once vertically centered the magnifier still overlapped the placeholder text horizontally.
    Added `pl-10` to the input, plus a new `.input.pl-10`/`.textarea.pl-10`/`.md3-text-field.pl-10`
    rule in `material.css` (two-class selector, so it reliably beats the single-class
    `padding: 0.75rem 1rem` shorthand on `.md3-text-field`/`.input` regardless of file load order —
    same specificity trick used for the T100 search-bar fix).
- **Testing**: Verified all of the above in-browser (Playwright, light + dark, seeded demo data):
  admin badge now sits correctly on the avatar corner; yarn-search icon is centered with proper
  spacing. The sidebar restore-tab margin fix was not interactively triggered (requires collapsing
  the details sidebar via JS) but is a single, low-risk CSS property.
  `just lint-css` (1 pre-existing unrelated warning only), `pytest tests/test_health.py` (4 passed),
  `just lint-template-js` pass.

### T103: Fix uncollapsible detail-page sections and drop the two-column sidebar layout

- **Area**: web/ux
- **Priority**: P1
- **Status**: done
- **Category**: bug
- **Description**:
  - User reported the project/yarn view (detail) pages "look a bit wrong" and that section
    collapsing didn't work, and asked to move Project/Yarn Details and Notes out of the sidebar
    into the main content column (no more multi-column layout).
- **Root cause (collapse bug)**:
  - Every `.md3-disclosure` accordion on `projects/detail.html`/`yarn/detail.html` (Gallery,
    Attachments, Description, Technical Specs, Instructions, each step, Audit Log, and the sidebar
    Details/Notes cards) rendered its toggle as a bare `<input type="checkbox">` with no `id`, and
    its title as a plain `<div class="md3-disclosure__title">` — never a `<label for="...">`
    pointing at the checkbox. Nothing could actually change the checkbox's `:checked` state by
    clicking, so sections could never be toggled. The edit-form pages (`projects/form.html`,
    `yarn/form.html`) already used the correct `<label for="...">` pattern for their own sidebar
    disclosure — the detail pages just never adopted it.
- **Root cause (layout)**:
  - Both detail templates duplicated their "Details"/"Notes" content: once in a `lg:hidden` block
    for mobile, and again in a `hidden lg:flex` sidebar column laid out via a `.md3-detail-layout`
    2-column CSS grid at `>=1024px` — plus, on `projects/detail.html`, a third duplicate of "Other
    Materials" that was already shown inside Technical Specifications. A `detail_sidebar_toggle.js`
    (collapse-sidebar-to-a-narrow-rail feature) referenced Tailwind-era classes/ids
    (`lg:col-span-3`, `#main-column`, `#sidebar-column`) that no longer existed anywhere in the
    current markup or CSS — fully dead code.
- **Implementation**:
  - Gave every disclosure checkbox a unique `id` and converted its title to `<label for="...">` on
    both detail pages.
  - Collapsed the duplicated Details/Notes (and, for projects, Other Materials) renderings down to
    one copy each, in the single main content column; moved yarn's "Yarn Details" and "Linked
    Projects" out of the sidebar into that same column (right after Gallery, and after Notes,
    respectively).
  - Removed the two-column grid CSS (`.md3-detail-main`/`.md3-detail-sidebar`/`.md3-detail-restore`,
    and the `>=1024px` grid override) — `.md3-detail-layout` is now just the single-column flex list
    it already shared with `.md3-list-layout`/`.md3-form-layout`. Deleted
    `detail_sidebar_toggle.js` and its restore-tab buttons/`data-call-change` wiring.
  - Rewrote `project_detail_print.css`'s force-expand-for-print rules from the stale DaisyUI
    `.collapse`/`.collapse-content` selectors (dead since the T88 M3 migration) to the current
    `.md3-disclosure`/`.md3-disclosure__content` ones, so a section a user leaves collapsed on
    screen still fully expands and prints.
  - Due to several other automated agent sessions actively committing to this same repo checkout
    mid-task (one of them silently reverted an uncommitted CSS edit of mine — see git history around
    `ee7a035`), the final CSS/JS cleanup step was done in an isolated git worktree and merged back
    once verified, to avoid further collisions.
- **Testing**: Playwright against a seeded demo instance — verified the disclosure toggle actually
  changes `:checked`/visibility on both pages; single-column layout confirmed at 1440px and 390px
  viewports; print-media screenshot confirmed a collapsed section still renders fully. Updated
  `tests/e2e/test_full.py` (dropped the two-column bounding-box assertion) and
  `tests/test_printing.py` (updated the stale `.collapse` selector assertions to
  `.md3-disclosure`). Full non-e2e suite: 292 passed, 1 skipped. `just lint-css`,
  `just lint-template-js`, `just lint-template-js-format`, `just i18n-check` all pass.

### T104: Give the app logo a cuter hover animation

- **Area**: web/ux
- **Priority**: P3
- **Status**: done
- **Category**: refactor
- **Description**:
  - User felt the existing logo hover animation (a flat `primary-container` circle fill behind the
    icon, plus a small rotate+scale on both the link and the image) looked "weird" and asked for
    something cuter, fitting the yarn-ball-with-a-face mascot.
- **Implementation** (`stricknani/static/css/material.css`, `.md3-app-logo__link`/`__image`):
  - Replaced the flat circular background fill with a soft drop shadow (`box-shadow`) so hovering
    feels like the logo lifts slightly rather than sitting on a hard colored disc.
  - Replaced the static rotate+scale transform with a `@keyframes md3-logo-boing` squash-and-stretch
    wobble (non-uniform scale + alternating rotation, `cubic-bezier(0.34, 1.56, 0.64, 1)` for a
    bouncy overshoot), played once on hover/focus — reads as the yarn ball bouncing rather than a
    generic UI hover state.
  - Extended the existing `prefers-reduced-motion: reduce` block to also null out the new
    `animation`, matching the existing transform/filter overrides there.
- **Testing**: Verified with Playwright (frame-by-frame screenshots through the ~650ms animation,
  light + dark) that the squash/stretch/rotate plays and settles cleanly; confirmed
  `getComputedStyle(...).animationName === "none"` under `reduced_motion: reduce` while hovered.
  `just lint-css` (same 2 pre-existing unrelated warnings, none new), `pytest tests/test_health.py`
  passes.
