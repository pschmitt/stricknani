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
- Keep only actionable, implementation-ready tasks here.

## Now

| ID | Priority | Status | Area | Category | Summary |
| -- | -------- | ------ | ---- | -------- | ------- |
| T29 | P2 | todo | ux | feat | Make the "instructions" block collapsible on project pages |

## Next

| ID | Priority | Status | Area | Category | Summary |
| -- | -------- | ------ | ---- | -------- | ------- |
| T13 | P2 | wip | reliability | refactor | Replace broad exception swallowing in import/parse paths with explicit error handling |
| T14 | P2 | todo | security | bug | Simplify and harden CSRF token flow (single source of truth for token location) |
| T15 | P2 | todo | data-model | refactor | Add DB invariant for a single primary yarn image and simplify fallback logic |
| T18 | P3 | todo | demo | feat | Improve demo assets with knitting-related images and content |
| T19 | P3 | todo | cli | feat | Make CLI commands default to list when no subcommand is provided |
| T20 | P3 | todo | cli | feat | Add comprehensive tests for CLI commands |
| T1 | P3 | todo | frontend/build | refactor | Replace runtime Tailwind with prebuilt static CSS bundle |


## Done

| ID | Priority | Status | Area | Category | Summary |
| -- | -------- | ------ | ---- | -------- | ------- |
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

### T16: Add markdown image autocomplete for `!` trigger in text fields

- **Area**: ux
- **Priority**: P2
- **Status**: done

### T18: Improve demo assets with knitting-related images and content

- **Area**: demo
- **Priority**: P3
- **Status**: todo
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
- **Status**: todo
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
- **Status**: todo
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
- **Status**: todo
- **Category**: feat
- **Description**:
  - Add a toggle/collapse button to the instructions section on project view pages
  - Remember collapsed state in localStorage for consistent UX across sessions
