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

| ID | Priority | Status | Area | Summary |
| -- | -------- | ------ | ---- | ------- |
| T24 | P3 | done | dev | Implement `just todo` command to list tasks from TODO.md in TSV format |
| T3 | P2 | wip | ux | Implement keyboard shortcuts for project/yarn list and view pages |

## Next

| ID | Priority | Status | Area | Summary |
| -- | -------- | ------ | ---- | ------- |
| T6 | P0 | done | data-integrity | Make DB/file operations atomic-ish: avoid deleting files before successful DB commit |
| T7 | P0 | done | auth/security | Enforce `is_active` in auth resolution so disabled users lose access immediately |
| T8 | P1 | done | architecture | Split oversized route modules into route/controller + service layers |
| T9 | P1 | todo | import | Consolidate duplicated import/image-dedupe logic into a single reusable pipeline |
| T21 | P1 | todo | import | Merge import dialog states: show URL entry and file upload in a single unified view |
| T10 | P1 | todo | projects | Extract shared create/update project import workflows to common services |
| T11 | P1 | todo | i18n/web | Remove per-request global Jinja i18n mutation to avoid cross-request language bleed |
| T12 | P1 | todo | web/templating | Remove hidden DB/auth lookups from `render_template`; require explicit `current_user` context |
| T13 | P2 | todo | reliability | Replace broad exception swallowing in import/parse paths with explicit error handling |
| T14 | P2 | todo | security | Simplify and harden CSRF token flow (single source of truth for token location) |
| T15 | P2 | todo | data-model | Add DB invariant for a single primary yarn image and simplify fallback logic |
| T17 | P2 | done | audit | Add audit log for projects/yarns (creation, edits, uploads, etc.) |
| T18 | P3 | todo | demo | Improve demo assets with knitting-related images and content |
| T19 | P3 | todo | cli | Make CLI commands default to list when no subcommand is provided |
| T20 | P3 | todo | cli | Add comprehensive tests for CLI commands |
| T22 | P3 | todo | dev | Add JS auto-reload in dev mode: reload page when server restarts |
| T23 | P3 | todo | dev | Improve run.sh: wait 2s static, then poll health endpoint with 20s timeout |
| T1 | P3 | todo | frontend/build | Replace runtime Tailwind with prebuilt static CSS bundle |


## Done

| ID | Priority | Status | Area | Summary |
| -- | -------- | ------ | ---- | ------- |
| T8 | P1 | done | architecture | Split oversized route modules into route/controller + service layers |
| T7 | P0 | done | auth/security | Enforce `is_active` in auth resolution so disabled users lose access immediately |
| T6 | P0 | done | data-integrity | Make DB/file operations atomic-ish: avoid deleting files before successful DB commit |
| T4 | P2 | done | docs | Reorganize project documents for faster agent onboarding and maintenance |
| T2 | P3 | done | ai/import | Add OpenRouter and Groq support for AI imports |
| T16 | P2 | done | ux | Add markdown image autocomplete for `!` trigger in text fields |
| T5 | P2 | done | ux | cropping of pictures via photoswipe (only when on the edit pages!) |


## Task Details

### T5: cropping of pictures via photoswipe (only when on the edit pages!)

- **Area**: ux
- **Priority**: P2
- **Status**: done
- **Implementation**:
  - Added crop button to PhotoSwipe UI (visible only on edit pages via `data-pswp-crop` attribute)
  - Created crop dialog with cropperjs integration
  - Added `/utils/crop-image` backend endpoint to save cropped images alongside originals
  - Cropped images are stored with `_crop` suffix in filename
  - Updated project and yarn form templates to mark images as croppable

### T6: Make DB/file operations atomic-ish: avoid deleting files before successful DB commit

- **Area**: data-integrity
- **Priority**: P0
- **Status**: done
- **Notes**:
  - For delete endpoints, commit DB changes first, then perform best-effort filesystem cleanup.
  - For import/dedupe flows, avoid irreversible file deletion before transaction success.

### T4: Reorganize project documents for faster agent onboarding and maintenance

- **Goal**: Separate operational instructions from product spec/history and add a clear document index.
- **Exit Criteria**:
  - `AGENTS.md` is concise and action-oriented.
  - Long-lived product/spec content is moved under `docs/`.
  - `README.md` points to the new doc structure.

### T3: Implement keyboard shortcuts for project/yarn list and view pages

- **Primary Files**: `stricknani/static/js/app.js`, `stricknani/templates/projects/list.html`, `stricknani/templates/yarn/list.html`, `stricknani/templates/projects/view.html`, `stricknani/templates/yarn/view.html`
- **Description**:
  - `D` on view pages: open delete project/yarn dialog
  - `c` on list pages: create new project/yarn
  - `n`/`p` on view pages: navigate to next/previous project/yarn (same behavior as swipe)
  - `e` on view pages: edit current project/yarn
  - `i` on list pages: open import dialog
- **Implementation Notes**:
  - Keep project and yarn UX consistent
  - Avoid inline translation strings in templates; pass config as JSON

### T1: Replace runtime Tailwind with prebuilt static CSS bundle

- **Primary Files**: `stricknani/templates/base.html`, `justfile`, `flake.nix`
- **Description**: Replace runtime Tailwind-in-browser usage with a prebuilt static CSS bundle for performance and easier CSP hardening.

### T2: Add OpenRouter and Groq support for AI imports

- **Verification Notes**:
  - Provider selection and defaults are implemented in `stricknani/utils/ai_provider.py`.
  - Coverage exists in `tests/test_ai_provider.py` and `tests/test_ai_ingest.py`.

### T17: Add audit log for projects/yarns (creation, edits, uploads, etc.)

- **Area**: audit
- **Priority**: P2
- **Status**: done
- **Description**:
  - Track meaningful actions on projects and yarns: creation, edits (with before/after values), image uploads, deletions, etc.
  - Log format examples: "User x created project", "user y uploaded a gallery image: img.png", "user z edited the description from xxx to yyy"
  - Display audit log on project/yarn view pages (e.g., expandable section or tab)
- **Investigation**:
  - Research existing Python/SQLAlchemy audit libraries (e.g., sqlalchemy-audit, flask-audit, or custom event-based approach)
  - Evaluate complexity vs. custom implementation
- **Implementation Notes**:
  - Consider using SQLAlchemy event listeners for declarative logging
  - Keep log entries immutable and tied to user + timestamp
  - Avoid performance impact on hot paths

### T16: Add markdown image autocomplete for `!` trigger in text fields

- **Area**: ux
- **Priority**: P2
- **Status**: todo
- **Description**:
  - When editing markdown-enabled fields (notes, descriptions), typing `!` should trigger an autocomplete dropdown
  - Offer available project/yarn images for insertion as markdown image syntax (`![alt](path)`)
  - Implementation likely via vanilla JS with a completion provider
- **Primary Files**: `stricknani/static/js/app.js`, relevant form templates

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
- **Status**: todo
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
- **Status**: todo
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

### T23: Improve run.sh: poll health endpoint with 20s timeout, no static wait

- **Area**: dev
- **Priority**: P3
- **Status**: todo
- **Description**:
  - Modify `run.sh` to poll the health endpoint using curl immediately (no initial 2s wait)
  - Keep trying until the health endpoint responds successfully
  - Give up and exit with error after 20 seconds total timeout
- **Implementation**:
  - Implement a loop with timeout that curls the health endpoint (e.g., `/health`)
  - Exit with error message if health check fails after 20 seconds
  - Exit successfully when health endpoint returns 200 OK

### T24: Implement `just todo` command to list tasks from TODO.md in TSV format

- **Area**: dev
- **Priority**: P3
- **Status**: wip
- **Description**:
  - Create `./scripts/todo.sh` that parses TODO.md and lists tasks
  - `just todo` -> lists current wip/todo tasks (default)
  - `just todo --done` -> lists done tasks
- **Output Format**:
  - Brief TSV output
  - Include header row (ID, Priority, Status, Area, Summary)
- **Implementation**:
  - Parse TODO.md markdown tables using mq
  - Filter by status (todo/wip for default, done for --done)
  - Add to justfile as `just todo` and `just todo --done`
