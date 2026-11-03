# Stricknani Rust Rewrite Brief

## Goal

Replace the Python web server and backend with a standalone Rust application while preserving Stricknani's existing behavior and data. Treat this as a parity migration, not a product redesign. Use this document as the implementation brief; the tracked task is T119 in `TODO.md`.

## Existing product boundary

- The server is Python, FastAPI, SQLAlchemy, Jinja2, and HTMX. It serves HTML pages and a versioned JSON API.
- The Android app is a separate Kotlin client of the JSON API. Keep it working and out of scope for this rewrite.
- SQLite is the development default; PostgreSQL is also supported. Uploaded media lives on the local filesystem.
- The web client uses existing templates, static assets, translations, service-worker behavior, and HTMX conventions.
- The CLI, environment-variable configuration, Nix package, and container image are part of the shipped application.
- The current Python tests, Alembic history, API schemas, and production database schema are behavioral and compatibility evidence. Read the current code; this brief is not a substitute for it.

## Required outcome

1. A Rust server can replace the Python server behind the existing HTTP boundary. Do not introduce FFI between the two applications.
2. Preserve the existing web routes, rendered page behavior, HTMX partials and headers, and user-visible errors, redirects, and validation.
3. Preserve the `/api/v1/` request/response contract, including authentication, token handling, uploads, and delta sync, so the existing Android client continues to work unchanged.
4. Preserve database meaning and existing user data. Keep SQLite and PostgreSQL support unless a concrete incompatibility is found and reported before changing scope.
5. Preserve media files and their authorization rules, existing environment-variable configuration, CLI behavior, and deployment interfaces.
6. Ship the Rust service through the existing Nix and container workflows. The final runtime must not need Python. Keep the Python implementation only as long as it is needed for parity comparison and safe transition.

## Behavior that must not regress

- Accounts, sessions, password policy, API tokens, token revocation, CSRF behavior, rate limits, and authorization ownership checks.
- Projects, yarns, categories, favorites, attachments, images, audit logs, and deletion behavior.
- API pagination and delta-sync cursors, including deletion records and cursor error behavior.
- Upload limits and validation; media content types, filenames, paths, and per-object access checks.
- SSRF protection for URL imports, including redirect validation; import parsing, deduplication, AI-provider selection, PDF/image handling, optional OCR, and Wayback behavior.
- HTML sanitization, Markdown rendering, security headers, CSP/nonces, trusted-host checks, and cache headers.
- English and German translations, generated static assets, PWA/service-worker behavior, and progressive enhancement.
- Gauge calculations and CLI output/exit behavior.

When an existing behavior appears accidental or unsafe, record it as a separate follow-up. Do not quietly change it as part of the port. Fix a security issue only when necessary to preserve or improve the existing security contract, and document the change separately.

## Migration approach

Use the `port-to-rust` and `port-from-python` skills, together with `rust-testing` and `rust-code-review`. Work in small, reviewable slices; do not do a big-bang rewrite.

1. **Inventory and contract:** map every route, API schema, database entity, CLI command, environment variable, background task, import path, and filesystem effect. Identify the current test coverage and any gaps. Record acceptable differences and exact parity evidence before implementing the replacement.
2. **Choose the Rust stack:** propose an async HTTP framework, database access and migration approach, session/CSRF handling, and server-side template strategy. Inspect the existing Jinja templates before choosing a Rust template engine; Jinja-like syntax does not guarantee drop-in compatibility. Keep the current HTTP and data contracts as the primary seam.
3. **Characterize behavior:** turn the current implementation and tests into black-box cases that can run against both servers. Cover successful and failing requests, relevant headers/cookies, database effects, and media-file effects. Keep fixtures disposable and local; tests must not call external services.
4. **Port leaf-first:** start with pure logic such as gauge calculation and parsing, then shared domain behavior and data access, followed by authentication, API, imports, and server-rendered web routes. Keep the Python server runnable during this phase.
5. **Prove parity:** send the same recorded requests and fixtures to the Python and Rust implementations using isolated databases and media directories. Compare status, relevant headers, response JSON/HTML semantics, database state, and files. Explain each intentional difference and its approval source.
6. **Prepare cutover:** document how an existing Alembic-managed database is recognized, how future Rust migrations are tracked, how the old server can be restored, and how a backup is made. Do not run against production data or deploy/cut over production as part of this task without the user's explicit go-ahead.
7. **Remove Python runtime:** only after parity is demonstrated, remove Python from the runtime package and image. Keep the existing Android client and web assets unless separately requested.
   *Done on the `rust` branch:* the Python application, its tests, packages, image and CI are gone. The last Python release is the signed tag `v0.1.0` on `main` (the cutover rollback). The remaining Python is development tooling only (`scripts/` i18n generators, `ci/` browser harnesses).

## Database and migration guardrails

- Do not rename or drop tables, columns, indexes, or constraints during parity work.
- Do not run Python Alembic and Rust migration systems concurrently against the same database.
- Before changing migration ownership, document how an existing database at every supported Alembic revision transitions without losing rows or invalidating media references.
- Test upgrades on disposable copies of representative databases, including SQLite and PostgreSQL. Never use a production database as a test fixture.
- If SQLAlchemy/Alembic behavior cannot be represented safely in the Rust migration plan, stop and present the choices before changing the data contract.

## Acceptance criteria

- Every existing route and API v1 operation is accounted for; unsupported or intentionally removed behavior has a user-approved decision.
- The existing Android app can use the Rust API without a client change.
- Python and Rust pass the same parity corpus for web/API behavior, authentication, sync, imports, and media handling, with unexplained differences resolved.
- Existing schema and data survive a tested transition. Both SQLite and PostgreSQL are covered.
- Rust formatting, linting, tests, Nix checks, and container build pass according to `AGENTS.md` and CI.
- The Nix package and container run without Python in the final runtime closure.
- The task reports remaining parity gaps and migration/cutover risks. Production cutover is a separate decision.

## Working rules

- Follow `AGENTS.md`, including its required checks and operational safeguards.
- Do not overwrite or revert unrelated changes in the worktree. If relevant files are already modified, use an isolated git worktree and merge deliberately.
- Keep API and database behavior stable before proposing cleanup or redesign.
- Make every port slice small enough that a reviewer can compare it with the Python behavior.
- If the scope, existing behavior, or safe database transition is unclear, document the question and stop before making an irreversible choice.
