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
| T39 | P0 | todo | search | bug | Fix universal search (Ctrl-K) CSRF 500 error |
| T38 | P2 | done | ux | refactor | Improve print layout to save space and show only relevant content |
| T37 | P3 | done | ux | refactor | Standardize "instructions" header size to match other section headers |
| T36 | P2 | done | frontend | refactor | Minimize templated JS/CSS in favor of static loading |
| T34 | P2 | done | cli | refactor | Make --query flag positional in `stricknani-cli project show` |
| T35 | P2 | done | ux | refactor | Hide empty "other materials" widget on project view page |
| T18 | P1 | done | demo | feat | Improve demo assets with knitting-related images and content |
| T30 | P1 | done | cli | feat | Add `stricknani-cli project|yarn ID_OR_NAME` with pretty print and --json support |
| T31 | P0 | done | nix | feat | Add backup.enable, schedule, and retention settings to Nix module (enabled by default) |
| T1 | P4 | todo | frontend/build | refactor | Replace runtime Tailwind with prebuilt static CSS bundle |
| T32 | P3 | todo | frontend | feat | Implement offline mode (PWA) |
| T33 | P3 | todo | frontend | feat | Add PWA installation capability |


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
