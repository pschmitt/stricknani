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
| T48 | P1 | done | demo | bug | Fix missing demo user profile picture (404 error)
| T47 | P2 | done | ux | refactor | Reformatting the "technical specs" section for better print layout
| T46 | P2 | done | cli | refactor | Improve stricknani-cli project export command arguments
| T1 | P4 | todo | frontend/build | refactor | Replace runtime Tailwind with prebuilt static CSS bundle |
| T32 | P3 | todo | frontend | feat | Implement offline mode (PWA) |
| T33 | P3 | done | frontend | feat | Add PWA installation capability |


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
- **Status**: todo
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
- **Status**: todo
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
- **Status**: todo
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

### T48: Fix missing demo user profile picture (404 error)

- **Area**: demo
- **Priority**: P1
- **Status**: todo
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
- **Status**: todo
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
- **Status**: todo
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
- **Status**: todo
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
- **Status**: todo
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
- **Status**: todo
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
- **Status**: todo
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
- **Status**: todo
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
- **Status**: todo
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
- **Status**: todo
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
- **Status**: todo
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
- **Status**: todo
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
